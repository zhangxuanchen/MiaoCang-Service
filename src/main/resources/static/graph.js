/* ================= 知识图谱 ================= */

/* G6 实例缓存：重绘前先销毁，避免画布叠加 */
let g6Graph = null;
/* 节点位置持久化：按视图（总览/单猫）分开记坐标，重开时与关闭时一致 */
let g6Scope = '';
function g6PosKey(scope) { return 'miaocang.g6pos.' + scope; }
function loadG6Pos(scope) {
  try { return JSON.parse(localStorage.getItem(g6PosKey(scope)) || '{}') || {}; } catch (e) { return {}; }
}
function saveG6Pos(scope) {
  if (!g6Graph || !scope) return;
  const pos = {};
  g6Graph.getNodeData().forEach((n) => {
    const p = g6Graph.getElementPosition(String(n.id));
    pos[n.id] = [Math.round(p[0]), Math.round(p[1])];
  });
  try { localStorage.setItem(g6PosKey(scope), JSON.stringify(pos)); } catch (e) { /* 存储满则忽略 */ }
}
function destroyGraph() {
  if (g6Graph) {
    saveG6Pos(g6Scope); /* 关闭/切视图前记录当前节点位置 */
    try { g6Graph.destroy(); } catch (e) { /* 画布已随 DOM 移除 */ }
    g6Graph = null;
  }
  clearBlink(); /* 画布没了，闪烁定时器也要收掉 */
}

/* ---------- 图谱就地增量更新：新增 / 修改节点当场重绘，不重渲染整页 ---------- */
/* 当前图谱的元信息（节点 id → {kind, num, ...}），增量操作靠它认节点 */
let g6Meta = null;
/* origin 实时图谱轮询：结构签名（文件夹+文件清单）变了才整图重绘，位置缓存保证不跳动 */
let graphWatchTimer = null;
let graphWatchSig = '';

/* 当前是否停在某张图谱上（画布还在 DOM 里） */
function graphAlive() { return !!(g6Graph && document.getElementById('g6-container')); }

/* 这个节点在当前图里画得出来吗：书已不上图（入口在目录/抽屉/维护页）；
   总览只画 猫→分类，单猫图谱画这只猫的 分类→内容 */
function graphShows(kind, catId) {
  if (!graphAlive()) return false;
  if (kind === 'book') return false;
  if (g6Scope === 'overview') return kind === 'cat' || kind === 'type';
  return g6Scope === 'cat-' + catId;
}

/* 就地摘除内容叶子（删除内容后画布立即更新）：走 detachG6Node 防 hover 残留引用卡死 */
function removeContentNode(id) {
  if (!graphAlive()) return;
  const nodeId = 'content-' + id;
  try {
    const node = g6Graph.getNodeData().find((n) => String(n.id) === nodeId);
    if (node) detachG6Node(nodeId);
    saveG6Pos(g6Scope);
  } catch (e) { /* 画布异常时忽略：后续 refreshView 全量重建兜底 */ }
}

/* 书架里查最新数据（须在 loadShelf 之后调用） */
function shelfCat(id) { return ((state.shelf && state.shelf.cats) || []).find((c) => c.id === id) || null; }
function shelfType(id) { return ((state.shelf && state.shelf.types) || []).find((t) => t.id === id) || null; }
function shelfBook(id) {
  const ts = (state.shelf && state.shelf.types) || [];
  for (const t of ts) {
    const b = (t.books || []).find((x) => x.id === id);
    if (b) return { book: b, type: t };
  }
  return null;
}

/* 节点 data 组装：就地新增和整图重绘共用，保证两种路径长得一模一样 */
function catNodeData(c) { return { kind: 'cat', color: c.color, icon: c.icon, label: c.name }; }
function typeNodeData(t) { return { kind: 'type', color: t.color, icon: t.icon, label: t.name }; }
function bookNodeData(b, t) { return { kind: 'book', color: t.color, label: b.title }; }
function contentNodeData(c, t) { return { kind: 'content', color: t.color, icon: TYPE_ICONS[c.contentType] || '📝', label: c.title }; }

/* 本版 G6 的 draw() 返回 { finished } 而不是 Promise，统一等它画完再取坐标 */
async function g6Draw() {
  const r = g6Graph.draw();
  if (r && r.finished) return r.finished;
  return r;
}

/* ---------- 刚变动的节点：闪烁高亮 10 秒，之后自动恢复常态 ---------- */
const BLINK_TOTAL = 10000; /* 闪烁总时长 */
const BLINK_STEP = 550;    /* 亮 / 灭切换间隔 */
let blinkTimer = null, blinkId = null, blinkData = null, blinkOn = false;

/* 只收定时器，不动画布（画布即将销毁时用） */
function clearBlink() {
  if (blinkTimer) { clearInterval(blinkTimer); blinkTimer = null; }
  blinkId = null;
  blinkData = null;
}

/* 收尾：把高亮彻底摘掉，节点回到常态外观 */
function stopBlink() {
  const id = blinkId, data = blinkData;
  clearBlink();
  if (id && data && graphAlive() && g6Meta[id]) {
    try {
      g6Graph.updateNodeData([{ id, data: Object.assign({}, data, { focus: false }) }]);
      g6Draw();
    } catch (e) { /* 画布已失效，忽略 */ }
  }
}

/* data 是该节点不含 focus 的基准数据，闪烁就是在它之上反复开关 focus */
function startBlink(id, data) {
  stopBlink();
  if (!graphAlive() || !g6Meta[id] || !data) return;
  blinkId = id;
  blinkData = data;
  blinkOn = true; /* 进来时节点已是高亮态，第一拍变暗 */
  const until = Date.now() + BLINK_TOTAL;
  blinkTimer = setInterval(async () => {
    if (!graphAlive() || !g6Meta[blinkId]) { clearBlink(); return; }
    if (Date.now() >= until) { stopBlink(); return; }
    blinkOn = !blinkOn;
    try {
      g6Graph.updateNodeData([{ id: blinkId, data: Object.assign({}, blinkData, { focus: blinkOn }) }]);
      await g6Draw();
    } catch (e) { clearBlink(); }
  }, BLINK_STEP);
}

/* 增量新增的节点也要登记进元信息表，右键菜单 / 后续改名 / 闪烁都靠它认节点 */
function nodeMeta(id, data, parentId) {
  const num = +String(id).split('-')[1];
  const parentNum = +String(parentId || '').split('-')[1];
  if (data.kind === 'type') return { kind: 'type', num, catId: parentNum, preset: false };
  if (data.kind === 'book') return { kind: 'book', num, typeId: parentNum, title: data.label };
  return { kind: 'cat', num };
}

/* 就地补一个节点，位置贴着父节点右下；失败返回 false，由调用方兜底 */
async function graphAddNode(id, data, parentId) {
  if (!graphAlive() || g6Meta[id]) return false;
  try {
    g6Graph.addNodeData([{ id, data: Object.assign({}, data, { focus: true }) }]);
    if (parentId) {
      g6Graph.addEdgeData([{ source: parentId, target: id, data: { level: id.startsWith('content-') ? 3 : 1 } }]);
    }
    await g6Draw();
    const to = (parentId && g6Graph.getElementPosition(parentId)) || g6Graph.getViewportCenter();
    const from = g6Graph.getElementPosition(id) || [0, 0];
    /* 注意签名：translateElementBy({ 节点id: [dx, dy] }, 是否动画) */
    g6Graph.translateElementBy({ [id]: [to[0] + 70 - from[0], to[1] + 60 - from[1]] }, false);
    g6Meta[id] = nodeMeta(id, data, parentId);
    saveG6Pos(g6Scope);
    startBlink(id, data);
    return true;
  } catch (e) { console.error('图谱新增节点失败:', e); return false; }
}

/* 就地改节点属性（名字/颜色/图标）：整份 data 覆盖，避免增量合并的歧义 */
async function graphUpdateNode(id, data) {
  if (!graphAlive() || !g6Meta[id]) return false;
  try {
    g6Graph.updateNodeData([{ id, data: Object.assign({}, data, { focus: true }) }]);
    await g6Draw();
    /* 书名要同步进元信息，否则右键菜单还弹旧名字 */
    if (g6Meta[id].kind === 'book') g6Meta[id].title = data.label;
    saveG6Pos(g6Scope);
    startBlink(id, data);
    return true;
  } catch (e) { console.error('图谱改节点失败:', e); return false; }
}

/* 新建节点后把它摆到台面上：当前图里画得出来就就地补进去，画不出来才跳到能看见它的图谱 */
async function revealNode(id, kind, catId, parentId, data) {
  if (kind !== 'cat' && !catId) return refreshView(); /* 分类/书没有归属的猫，画面里放不下 */
  if (graphShows(kind, catId) && (await graphAddNode(id, data, parentId))) return true;
  state.focusNodeId = id;
  return openGraph(kind === 'cat' ? null : catId);
}

/* 节点基准尺寸：猫大、分类中、书小、内容叶子最小；命中半径（g6NodeAt）也用这张表，改一处两边同步 */
const NODE_BASE = { cat: 64, type: 46, book: 32, content: 24 };

/* 波普风节点：实心色块 + 墨色描边 + 硬偏移阴影；猫大、分类中、书小 */
function g6NodeStyle(d) {
  const k = d.data.kind;
  const base = NODE_BASE[k] || NODE_BASE.book;
  /* 刚变动的节点：放大 + 绿描边 + 光晕，闪烁期间由 focus 反复开关 */
  const focus = !!d.data.focus;
  return {
    size: focus ? base + 12 : base,
    fill: d.data.color || '#FFFDF8',
    stroke: focus ? '#0F6E56' : '#1F1B16',
    lineWidth: focus ? 4 : (k === 'book' || k === 'content') ? 2 : 3,
    halo: focus,
    haloStroke: '#0F6E56',
    haloLineWidth: 16,
    shadowColor: '#1F1B16',
    shadowBlur: 0,
    shadowOffsetX: k === 'cat' ? 4 : k === 'type' ? 3 : k === 'content' ? 1 : 2,
    shadowOffsetY: k === 'cat' ? 4 : k === 'type' ? 3 : k === 'content' ? 1 : 2,
    cursor: 'pointer',
    iconText: d.data.icon || (k === 'book' ? '📖' : k === 'content' ? '📄' : '🐾'),
    iconFontSize: k === 'cat' ? 28 : k === 'type' ? 20 : 11,
    labelText: d.data.label,
    labelPlacement: 'bottom',
    labelFontSize: focus ? 13 : k === 'content' ? 10 : k === 'book' ? 11 : 13,
    labelFontWeight: 700,
    labelFill: '#1F1B16',
    labelWordWrap: true,
    labelMaxWidth: 120,
    labelBackground: true,
    labelBackgroundFill: 'rgba(255,246,233,0.94)',
    labelBackgroundRadius: 6,
    labelBackgroundPadding: [1, 5, 1, 5],
  };
}

/* 连线：轻盈细线；猫→分类墨色实线（骨架），分类→内容更淡更细的虚线（尘埃，level 2 分类→书随书层一起移除），弧线过渡更柔和 */
function g6EdgeStyle(d) {
  /* level 1 = 猫→分类；level 3 = 分类→内容，由深到浅逐渐外延 */
  const level = (d.data && d.data.level) || (String(d.source).startsWith('cat-') ? 1 : 3);
  if (level === 1) {
    return { stroke: 'rgba(31,27,22,0.55)', lineWidth: 1.8, lineDash: 0, curveOffset: 14 };
  }
  if (level === 3) {
    return { stroke: 'rgba(31,27,22,0.28)', lineWidth: 1, lineDash: [2, 5], lineCap: 'round', curveOffset: 26 };
  }
  return { stroke: 'rgba(31,27,22,0.45)', lineWidth: 1.8, lineDash: [5, 6], lineCap: 'round', curveOffset: 22 };
}

/* 拖拽联动：拖动节点时，其下游所有后代（分类→书）整体跟随移动，由粗到细一起外延 */
function bindDragCascade(graph, edges) {
  const children = {};
  edges.forEach((e) => { (children[e.source] = children[e.source] || []).push(e.target); });
  const descendantsOf = (root) => {
    const out = [], seen = {}, queue = [root];
    while (queue.length) {
      const cur = queue.shift();
      (children[cur] || []).forEach((child) => {
        if (!seen[child]) { seen[child] = 1; out.push(child); queue.push(child); }
      });
    }
    return out;
  };
  let drag = null;
  /* 绝对位置跟随：成员目标 = 初始位置 + 被拖节点全程位移，与「当前实际位置」的差即为待搬运增量。
     不用「上次同步点以来的增量」累加——真实高频拖动下 drag 事件与 G6 内部位置应用存在交错，
     增量累加可能重复/漏算（观感即节点比鼠标快/慢），绝对模式每帧自动归零误差 */
  const syncGroup = (dx, dy) => {
    const t = {};
    drag.group.forEach((mid) => {
      const b = drag.base[mid];
      if (!b) return;
      const now = graph.getElementPosition(mid);
      const ex = b[0] + dx - now[0], ey = b[1] + dy - now[1];
      if (ex || ey) t[mid] = [ex, ey];
    });
    if (Object.keys(t).length) graph.translateElementBy(t, false);
  };
  graph.on('node:dragstart', (e) => {
    const id = String(e.target.id || '');
    if (!id) return;
    /* 框选批量拖：G6 drag-element 对选中集合会「成员再搬一次」（被拖节点 2 倍位移），
       所以拖选中节点时先摘掉全部 selected 态让 G6 退化为普通单拖，
       其余选中成员由 cascade 按同位移同步，拖完恢复选中态 */
    let sel = [];
    try { sel = (graph.getElementDataByState('node', 'selected') || []).map((n) => String(n.id)); } catch (err) { /* 忽略 */ }
    drag = { id, group: sel.includes(id) ? sel.filter((x) => x !== id) : descendantsOf(id), restore: sel.includes(id) ? sel : null };
    drag.origin = graph.getElementPosition(id);
    drag.base = {};
    drag.group.forEach((mid) => { drag.base[mid] = graph.getElementPosition(mid); });
    if (drag.restore) drag.restore.forEach((sid) => { try { graph.setElementState(sid, []); } catch (err) { /* 忽略 */ } });
  });
  graph.on('node:drag', () => {
    if (!drag) return;
    const cur = graph.getElementPosition(drag.id);
    syncGroup(cur[0] - drag.origin[0], cur[1] - drag.origin[1]);
  });
  graph.on('node:dragend', () => {
    if (!drag) return;
    /* 终态校正：以最终位置为准把成员拉齐（绝对模式下的收尾，正常时差值已为 0） */
    const cur = graph.getElementPosition(drag.id);
    syncGroup(cur[0] - drag.origin[0], cur[1] - drag.origin[1]);
    if (drag.restore) drag.restore.forEach((sid) => { try { graph.setElementState(sid, ['selected']); } catch (err) { /* 忽略 */ } });
    drag = null;
  });
}

/* 知识图谱（AntV G6 力导向图）：总览 = 猫→分类 两层；单猫 = 猫→分类→书 三层 */
let graphRenderToken = 0; /* 渲染令牌：连续删除/快速切换时只让最后一次渲染生效，防旧异步渲染覆盖画布 */
async function openGraph(catId, forceRelayout = false) {
  const token = ++graphRenderToken;
  state.view = 'graph';
  state.graphCatId = catId || null;
  state.drawerBookId = null;
  setNav('graph');
  const s = state.shelf || await api('/api/shelf');
  const allCats = s.cats || [];

  if (!state.graphCatId && !allCats.length) {
    $('main').innerHTML = `${backBar()}<div class="empty">还没养猫，点右上角「领养新猫」开始</div>`;
    return;
  }

  let head = '';
  if (state.graphCatId) {
    const cat = allCats.find((c) => c.id === state.graphCatId);
    if (!cat) { state.graphCatId = null; return openGraph(); }
    head = `
      <div class="view-head graph-head">
        <div class="graph-head-left">
          <button class="btn back" onclick="goHome()">← 返回书房</button>
          <div class="kicker">Knowledge Graph · ${esc(cat.name)} 的知识图谱</div>
        </div>
        <div class="head-actions">
          <button class="btn" onclick="openGraph(${cat.id}, true)" title="重新扫描 origin 目录并重新撒开节点布局">🔄 重新布局</button>
          <button class="btn" onclick="openExportGraph()" title="勾选分类节点与内容原文，排队导出为 Markdown">⬇ 导出</button>
          <button class="btn" onclick="openInbox(${cat.id})">📥 收集箱${cat.inboxCount ? `（${cat.inboxCount}）` : ''}</button>
          <button class="btn primary" onclick="openCollect()">＋ 投入内容</button>
        </div>
      </div>`;
  } else {
    head = `
      <div class="view-head graph-head">
        <div class="graph-head-left">
          <button class="btn back" onclick="goHome()">← 返回书房</button>
          <div>
            <div class="kicker">Knowledge Graph · 知识宇宙</div>
            <h2>知识图谱</h2>
          </div>
        </div>
        <div class="head-actions">
          <button class="btn" onclick="openCatModal()">＋ 领养新猫</button>
        </div>
      </div>`;
  }
  /* 图谱页整幅铺满：会话统一走右下角悬浮球（当前猫会话），不再放常驻对话栏；
     单猫图谱右侧带📑分类目录（仿喵的整理卡片目录，只列一级节点，点击定位画布） */
  $('main').innerHTML = `${head}
    <div class="graph-bottom">
      <div class="graph-tidy-wrap">
        <div class="g6-wrap"><div id="g6-container"></div></div>
        ${state.graphCatId ? '<div class="card-toc graph-toc" id="graphToc"></div>' : ''}
      </div>
    </div>`;

  /* 组装 G6 数据：节点 = 猫/分类/内容（书已收进目录与抽屉，不再上图），边 = 归属关系 */
  const nodes = [], edges = [], meta = {};
  /* 本次渲染要高亮的新节点（新建后跳过来时的目标），取出即清，避免下次重开图谱还亮着 */
  const focusNode = state.focusNodeId;
  state.focusNodeId = null;
  const pushCat = (c) => {
    const id = 'cat-' + c.id;
    meta[id] = { kind: 'cat', num: c.id };
    nodes.push({ id, data: Object.assign(catNodeData(c), { focus: focusNode === id }) });
  };
  const pushType = (t, c) => {
    const id = 'type-' + t.id;
    /* preset：右键菜单据此隐藏「删除节点」（共享数据不可删，改为可解除挂接）；
       attached：该喵挂接的共享分类（空分类也常驻上图）；books 供右键「打开书」入口 */
    meta[id] = { kind: 'type', num: t.id, catId: c.id, preset: !!t.preset, attached: !!t.attached, books: (t.books || []).map((b) => ({ id: b.id, title: b.title })) };
    nodes.push({ id, data: Object.assign(typeNodeData(t), { focus: focusNode === id }) });
    edges.push({ source: 'cat-' + c.id, target: id, data: { level: 1 } });
  };
  const pushContent = (c, b, t) => {
    const id = 'content-' + c.id;
    /* 书已不上图：内容叶子直接挂分类下；meta 保留 bookId 供删书时摘除内容 */
    meta[id] = { kind: 'content', num: c.id, typeId: t.id, bookId: b.id };
    nodes.push({ id, data: Object.assign(contentNodeData(c, t), { focus: focusNode === id }) });
    edges.push({ source: 'type-' + t.id, target: id, data: { level: 3 } });
  };
  void pushContent; /* 总览分支暂不挂 DB 内容叶子，保留组装器以备内容视图复用 */

  /* 单猫图谱绑定 origin：实时扫文件系统——文件夹=一级类目，文件=内容叶子，根散文件直接挂猫 */
  const pushFolder = (grp, cid) => {
    const id = 'folder-' + grp.folder;
    meta[id] = { kind: 'folder', name: grp.folder, catId: cid };
    nodes.push({ id, data: Object.assign({ kind: 'type', color: '#0F6E56', icon: '📁', label: grp.folder }, { focus: focusNode === id }) });
    edges.push({ source: 'cat-' + cid, target: id, data: { level: 1 } });
  };
  const pushFsFile = (f, folder, cid) => {
    const id = 'fs-' + f.path;
    meta[id] = { kind: 'fsfile', path: f.path, name: f.name, folder, catId: cid };
    nodes.push({ id, data: Object.assign({ kind: 'content', color: '#7B5CB8', icon: /\.md$/i.test(f.name) ? '📝' : '📄', label: f.name }, { focus: focusNode === id }) });
    edges.push({ source: (folder ? 'folder-' + folder : 'cat-' + cid), target: id, data: { level: folder ? 3 : 1 } });
  };

  let emptyTip = '';
  if (state.graphCatId) {
    const cat = allCats.find((c) => c.id === state.graphCatId);
    const fs = await api(`/api/cats/${state.graphCatId}/graph-fs`);
    if (token !== graphRenderToken) return; /* 期间有更新的打开请求，放弃这次陈旧渲染 */
    state.graphFs = fs || [];
    graphWatchSig = JSON.stringify(state.graphFs); /* 轮询基线 */
    /* 导出与内容抽屉仍依赖 DB 内容清单，保留一份 */
    try {
      const contents = await api(`/api/cats/${state.graphCatId}/graph-contents`);
      if (token !== graphRenderToken) return;
      state.graphContents = contents || [];
    } catch (e) { state.graphContents = []; }
    pushCat(cat);
    (state.graphFs || []).forEach((grp) => {
      if (!grp.folder) {
        (grp.files || []).forEach((f) => pushFsFile(f, null, cat.id)); /* origin 根散文件：猫直接关联 */
        return;
      }
      pushFolder(grp, cat.id);
      (grp.files || []).forEach((f) => pushFsFile(f, grp.folder, cat.id));
    });
    if (!(state.graphFs || []).length) {
      emptyTip = `还没有 origin 目录或里面是空的——${esc(cat.name)} 的工作区 origin/ 下建文件夹（一级类目）和文件（内容），图谱会实时跟着变`;
    }
  } else {
    allCats.forEach((c) => {
      pushCat(c);
      (c.types || []).forEach((t) => {
        /* 总览：专属分类常驻；共享分类有内容或挂接才展示 */
        if (!((t.books || []).reduce((s, b) => s + (b.contentCount || 0), 0)) && t.shared && !t.attached) return;
        pushType(t, c);
      });
    });
  }

  /* 强制重新布局 或 所有节点都没有持久化位置时跑力导向，把节点撒开；
     持久化位置存在时跳过，保持与关闭时一致（用户手动拖过的不被破坏）。
     forceRelayout 用 window.forceRelayoutForG6 传递，绕开 renderG6 函数签名在浏览器旧缓存里残留导致的 ReferenceError。 */
  window.forceRelayoutForG6 = !!forceRelayout;
  const scope = state.graphCatId ? 'cat-' + state.graphCatId : 'overview';
  const cached = forceRelayout ? {} : loadG6Pos(scope);
  const allPositioned = nodes.length > 0 && nodes.every((n) => n.style && n.style.x != null);
  if (forceRelayout) { /* 强制重新布局时清掉缓存，让力导向能生效；普通刷新（不 force）不清 */
    try { localStorage.removeItem(g6PosKey(scope)); } catch (e) { /* 静默忽略 */ }
  }
  if (Object.keys(cached).length) {
    nodes.forEach((n) => {
      if (cached[n.id]) n.style = { x: cached[n.id][0], y: cached[n.id][1] };
    });
    /* 新节点没有缓存坐标：沿父节点（边 source）附近随机落位；最多 4 轮覆盖 猫→分类→书→内容 深度 */
    for (let round = 0; round < 4; round++) {
      edges.forEach((e) => {
        const t = nodes.find((n) => n.id === e.target), s = nodes.find((n) => n.id === e.source);
        if (t && s && !cached[t.id] && s.style && s.style.x != null) {
          t.style = { x: s.style.x + 60 + Math.random() * 70, y: s.style.y + 40 + Math.random() * 70 };
        }
      });
    }
    /* 兜底：父链断裂的孤立新节点撒在画布中部 */
    nodes.forEach((n) => {
      if (!n.style) n.style = { x: (Math.random() - 0.5) * 500, y: (Math.random() - 0.5) * 400 };
    });
  }

  /* 兼容新旧 graph.js：renderG6_v2 是新版，renderG6 是浏览器缓存里可能残留的旧版。
     只要图谱能初始化就行，forceRelayout 机制（重新布局）在 fallback 时退化为普通初始化。
     ——这个会话里浏览器缓存坑了无数次，这里彻底堵住。 */
  const doRenderG6 = (typeof renderG6_v2 === 'function') ? renderG6_v2 : (typeof renderG6 === 'function' ? renderG6 : null);
  if (!doRenderG6) {
    console.error('[graph] renderG6 / renderG6_v2 都不存在，图谱无法初始化');
    return;
  }
  doRenderG6(nodes, edges, meta, scope);
  renderGraphToc();
  if (state.graphCatId) startGraphWatch(); /* origin 实时观察：结构变了自动重绘 */
  syncGraphCatToChat(); /* 常驻对话栏已移除：进图谱时把会话对象同步到当前图谱猫，对话走悬浮窗 */
  /* 跳过来要高亮的节点：闪 10 秒再恢复常态 */
  if (focusNode) {
    const fn = nodes.find((n) => n.id === focusNode);
    if (fn) startBlink(focusNode, fn.data);
  }
  if (emptyTip) $('main').insertAdjacentHTML('beforeend', `<div class="empty">${emptyTip}</div>`);
}

/* ---------- 📑 分类目录：单猫图谱右侧目录（和喵的整理的卡片目录同款两级结构） ---------- */

/* 只列画布上真实存在的 origin 结构：文件夹（一级类目）+ 根散文件；点击定位节点，可重复调用刷新 */
function renderGraphToc() {
  const el = $('graphToc');
  if (!el || !state.graphCatId) return;
  const groups = (state.graphFs || []).filter((g) => g.folder);
  const loose = ((state.graphFs || []).find((g) => !g.folder) || {}).files || [];
  if (!(groups.length || loose.length)) {
    el.innerHTML = `<div class="toc-head">📑 origin 目录</div><div class="empty" style="font-size:11px;padding:6px 4px">origin 还没有文件夹和文件</div>`;
    return;
  }
  el.innerHTML = `<div class="toc-head">📑 origin 目录</div>` + groups.map((grp) => `
    <details class="toc-book" open>
      <summary title="点击收放「${esc(grp.folder)}」">
        <span>📁 ${esc(grp.folder)}</span><b class="toc-locate" title="定位到「${esc(grp.folder)}」" onclick="event.preventDefault();event.stopPropagation();jumpToGraphNode('folder-${esc(grp.folder)}')">${(grp.files || []).length}</b>
      </summary>
      ${(grp.files || []).map((f) => `
        <button type="button" class="toc-item" onclick="jumpToGraphNode('fs-${esc(f.path)}')" title="${esc(f.name)}">
          <span>${/\.md$/i.test(f.name) ? '📝' : '📄'} ${esc(f.name)}</span>
        </button>`).join('')}
    </details>`).join('')
    + (loose.length ? `<details class="toc-book" open><summary title="origin 根下散文件，直接挂在这只喵身上"><span>🧶 散文件</span><b>${loose.length}</b></summary>`
      + loose.map((f) => `
        <button type="button" class="toc-item" onclick="jumpToGraphNode('fs-${esc(f.path)}')" title="${esc(f.name)}">
          <span>${/\.md$/i.test(f.name) ? '📝' : '📄'} ${esc(f.name)}</span>
        </button>`).join('') + '</details>' : '');
}

/* origin 实时图谱：结构签名变化才整图重绘（graph-fs 每次现扫，喵写文件/客户端同步后图谱自动跟上） */
function startGraphWatch() {
  if (graphWatchTimer) clearInterval(graphWatchTimer);
  graphWatchTimer = setInterval(async () => {
    if (state.view !== 'graph' || !state.graphCatId || !graphAlive()) return;
    try {
      const fs = await api('/api/cats/' + state.graphCatId + '/graph-fs');
      const sig = JSON.stringify(fs || []);
      if (sig !== graphWatchSig) openGraph(state.graphCatId); /* openGraph 内会更新基线 */
    } catch (e) { /* 轮询失败静默，下个周期再试 */ }
  }, 15000);
}

/* origin 文件/文件夹删除（图谱右键）：走工作区文件删除接口，删完重开图谱即实时变化 */
async function deleteGraphFile(catId, path) {
  if (!confirm('确定删除 ' + path + ' ？目录会连同里面全部文件一起删除，不可恢复。')) return;
  try {
    await api('/api/cats/' + catId + '/chat/file?path=' + encodeURIComponent(path), { method: 'DELETE' });
    toast('已删除 ' + path);
    if (state.graphCatId === catId) openGraph(catId);
  } catch (e) { toast('删除失败：' + e.message, 'err'); }
}

let fsNav = null; /* 图谱文件预览弹窗的历史栈 */
/* 详情页编辑态：当前文件路径 + 原始 md + 是否停在编辑面板 */
let fsCurPath = '';
let fsRawContent = '';
let fsEditMode = false;
function fsNavFor() {
  if (!fsNav) fsNav = makeNavStack(fsRenderFile);
  return fsNav;
}
function fsNavStep(d) { const n = fsNavFor(); if (d < 0) n.back(); else n.fwd(); }

/* origin 文件预览（图谱节点单击/右键/文内链接）：历史栈导航 + md 渲染 + 图片内联，与工作区预览同一套管线 */
async function openFsPreview(catId, path) {
  /* 切换文件前清掉可能残留的内嵌浏览器覆盖层（workspace.js 提供） */
  if (typeof closeWebBrowse === 'function') closeWebBrowse($('fsPrevBody'));
  const nav = fsNavFor();
  if (catId != null) nav.catId = catId;
  $('fsPrevModal').style.display = 'flex';
  return nav.load(path);
}

/* 弹窗头部右侧的操作区（md 才有「预览 / 编辑 / 保存」，其他文件留空） */
function fsSetHead(html) {
  const h = $('fsHeadActions');
  if (h) h.innerHTML = html;
}

/* 弹窗渲染体（历史导航与首次打开都落到这里） */
async function fsRenderFile(path) {
  const catId = fsNav.catId;
  const back = $('fsNavBack'), fwd = $('fsNavFwd');
  if (back) back.disabled = !fsNav.canBack();
  if (fwd) fwd.disabled = !fsNav.canFwd();
  fsCurPath = path;
  fsRawContent = '';
  fsEditMode = false;
  $('fsPrevTitle').textContent = '📄 ' + path.split('/').pop();
  fsSetHead('');
  const body = $('fsPrevBody');
  body.innerHTML = '<div class="empty">加载中…</div>';
  try {
    const r = await api('/api/cats/' + catId + '/chat/file?path=' + encodeURIComponent(path));
    if (/\.md$/i.test(path)) {
      fsRawContent = r.content || '';
      /* 预览 / 编辑两块同时挂上：切换只切显示，textarea 里改了一半的内容不会丢 */
      body.innerHTML = `<div class="md-body" id="fsPreviewPane">${mdFinal(fsRawContent, { resolve: (h) => resolveWsRelPath(path, h) })}</div>`
        + `<div id="fsEditPane" style="display:none"><textarea class="fs-editor" id="fsEditor" spellcheck="false">${esc(fsRawContent)}</textarea></div>`;
      /* 容器是预览面板本身（md 内容在它内部），相对路径图片逐张回填；mermaid 代码块 → 流程图 */
      wsInlineImages($('fsPreviewPane'), path, catId);
      mmdHydrate($('fsPreviewPane'));
      /* 读取端对超大文件做过截断（r.truncated），照它保存会把原文截掉，索性不给编辑入口 */
      fsSetHead(r.truncated
        ? '<span class="fs-trunc">文件较大已截断，仅供预览</span>'
        : `<div class="fs-mode">
             <button class="fs-mode-btn on" type="button" data-mode="view" onclick="fsSetMode('view')">👁 预览</button>
             <button class="fs-mode-btn" type="button" data-mode="edit" onclick="fsSetMode('edit')">✏️ 编辑</button>
           </div>
           <button class="btn sm primary" type="button" id="fsSaveBtn" style="display:none" onclick="fsSaveFile()">💾 保存</button>`);
    } else {
      body.innerHTML = '<pre class="raw">' + esc(r.content || '') + '</pre>';
    }
  } catch (e) {
    body.innerHTML = '<div class="empty">' + esc(e.message) + '</div>';
  }
}

/* 预览 / 编辑切换：只切显示 */
function fsSetMode(mode) {
  const pv = $('fsPreviewPane'), ed = $('fsEditPane'), ta = $('fsEditor');
  if (!pv || !ed || !ta) return;
  fsEditMode = mode === 'edit';
  pv.style.display = fsEditMode ? 'none' : '';
  ed.style.display = fsEditMode ? '' : 'none';
  const save = $('fsSaveBtn');
  if (save) save.style.display = fsEditMode ? '' : 'none';
  document.querySelectorAll('#fsHeadActions .fs-mode-btn')
    .forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  if (fsEditMode) ta.focus();
}

/* 保存：把编辑框里的 md 覆盖写回工作区文件，成功后用新内容重渲染预览并切回预览态 */
async function fsSaveFile() {
  const ta = $('fsEditor');
  if (!ta || !fsCurPath) return;
  const path = fsCurPath, catId = fsNav.catId;
  const save = $('fsSaveBtn');
  if (save) save.disabled = true;
  try {
    await api('/api/cats/' + catId + '/chat/file?path=' + encodeURIComponent(path),
      { method: 'PUT', body: JSON.stringify({ content: ta.value }) });
    fsRawContent = ta.value;
    const pv = $('fsPreviewPane');
    pv.innerHTML = mdFinal(fsRawContent, { resolve: (h) => resolveWsRelPath(path, h) });
    wsInlineImages(pv, path, catId);
    mmdHydrate(pv);
    fsSetMode('view');
    toast('已保存 ' + path.split('/').pop());
  } catch (e) {
    toast('保存失败：' + e.message, true);
  } finally {
    if (save) save.disabled = false;
  }
}

/* 目录点击：画布居中到该节点并闪烁高亮（单猫图全图自适应可见，高亮即锚定） */
async function jumpToGraphNode(id) {
  if (!graphAlive() || !g6Meta[id]) return;
  try { g6Graph.focusElement(id); } catch (e) { /* 无此 API 时仅高亮 */ }
  const n = (g6Graph.getNodeData() || []).find((x) => String(x.id) === id);
  if (n) startBlink(id, n.data);
}

/* ---------- 节点右键上下文菜单：知识分类维护入口 ---------- */

function closeNodeMenu() {
  const m = $('g6NodeMenu');
  if (m) m.remove();
}

/* items: [{label, run, danger?, sep?}]，sep 项渲染成一条分隔线 */
function showNodeMenu(x, y, items) {
  closeNodeMenu();
  const menu = document.createElement('div');
  menu.id = 'g6NodeMenu';
  menu.className = 'g6-node-menu';
  menu.innerHTML = items.map((it, i) => it.sep
    ? '<div class="g6-menu-sep"></div>'
    : `<button type="button" class="g6-menu-item${it.danger ? ' danger' : ''}" data-i="${i}">${esc(it.label)}</button>`).join('');
  document.body.appendChild(menu);
  /* fixed 定位 + 视口夹取：靠右/靠下时菜单不会被裁出屏幕 */
  const r = menu.getBoundingClientRect();
  menu.style.left = Math.max(8, Math.min(x, window.innerWidth - r.width - 8)) + 'px';
  menu.style.top = Math.max(8, Math.min(y, window.innerHeight - r.height - 8)) + 'px';
  menu.addEventListener('click', (ev) => {
    const btn = ev.target.closest('button[data-i]');
    if (!btn) return;
    const it = items[+btn.dataset.i];
    closeNodeMenu(); /* 先关闭再执行：run() 会弹窗/跳转，菜单不该残留 */
    if (it && it.run) it.run();
  });
}

/* 光标 → 图世界坐标：本版 G6 的 getClientByCanvas 等换算 API 失效，
   改用视口中心已知的两个量（像素坐标 getCanvasCenter / 世界坐标 getViewportCenter）反推 */
function g6WorldAt(ev, el) {
  const rect = el.getBoundingClientRect();
  const cc = g6Graph.getCanvasCenter();
  const vc = g6Graph.getViewportCenter();
  const z = g6Graph.getZoom();
  return [vc[0] + (ev.clientX - rect.left - cc[0]) / z, vc[1] + (ev.clientY - rect.top - cc[1]) / z];
}
/* 命中测试：取离光标最近的节点，落在节点半径×0.62 内才算命中（留视觉余量） */
function g6NodeAt(ev, el, meta) {
  const w = g6WorldAt(ev, el);
  let best = '', bestD = Infinity;
  Object.keys(meta).forEach((id) => {
    const p = g6Graph.getElementPosition(id);
    if (!p) return;
    const d = Math.hypot(p[0] - w[0], p[1] - w[1]);
    if (d < bestD) { bestD = d; best = id; }
  });
  if (!best) return '';
  const k = meta[best].kind;
  return bestD <= (NODE_BASE[k] || NODE_BASE.book) * 0.62 ? best : '';
}

/* 按节点类型组装菜单；预设分类不给删除入口（后端必然拒绝） */
function nodeMenuItems(m) {
  if (m.kind === 'fsfile') return [ /* origin 文件：实时增删的入口 */
    { label: '🔍 打开预览', run: () => openFsPreview(m.catId, m.path) },
    { label: '🔄 重新布局', run: () => openGraph(m.catId, true) },
    { sep: true },
    { label: '🗑 删除文件', danger: true, run: () => deleteGraphFile(m.catId, m.path) },
  ];
  if (m.kind === 'folder') return [ /* origin 一级类目=文件夹 */
    { label: '🔄 重新布局', run: () => openGraph(m.catId, true) },
    { sep: true },
    { label: '🗑 删除文件夹（含全部文件）', danger: true, run: () => deleteGraphFile(m.catId, 'origin/' + m.name) },
  ];
  if (m.kind === 'content') return [
    { label: '🔍 查看详情', run: () => openDetail(m.num) },
    { label: '📦 移动到别的书', run: () => openMoveModal(m.num) },
  ];
  if (m.kind === 'cat') return [
    { label: '✏️ 编辑节点', run: () => openCatModal(m.num) },
    { label: '＋ 新增子节点（知识分类）', run: () => openTypeModal(null, m.num) },
    { sep: true },
    { label: '🗑 删除节点（送养这只猫）', danger: true, run: () => deleteCat(m.num) },
  ];
  if (m.kind === 'type') {
    const items = [
      { label: '✏️ 编辑节点', run: () => openTypeModal(m.num) },
      { label: '＋ 新增子节点（书）', run: () => openBookModal(m.num) },
      { label: '📥 新增内容', run: () => openCollect() },
    ];
    if (!m.preset) items.push({ sep: true }, { label: '🗑 删除节点', danger: true, run: () => deleteType(m.num) });
    else if (m.attached) items.push({ sep: true }, { label: '🔗 解除挂接（从本图谱摘除）', run: () => detachType(m.num, m.catId) });
    return items;
  }
  return []; /* 书不再上图：不会命中 book 节点 */
}

/* 就地摘除某本书的内容叶子（书已不上图：删书 = 摘掉它挂在分类下的内容）；
   分类因此空掉时一并移除分类节点 */
function removeGraphBook(id) {
  if (!graphAlive()) return;
  Object.keys(g6Meta).forEach((nodeId) => {
    if (g6Meta[nodeId] && g6Meta[nodeId].kind === 'content' && g6Meta[nodeId].bookId === id) {
      detachG6Node(nodeId);
    }
  });
  Object.keys(g6Meta).forEach((nodeId) => {
    if (!g6Meta[nodeId] || g6Meta[nodeId].kind !== 'type') return;
    try {
      if (!(g6Graph.getChildrenData(nodeId, 'edge') || []).length) detachG6Node(nodeId);
    } catch (e) { /* 分类节点清理失败忽略 */ }
  });
  saveG6Pos(g6Scope);
}

/* 就地摘除节点前先「请指针离开」：G6 v5 的 hover-activate 会把 hover 元素记在内部集合里，
   直接 removeNodeData 会让该集合残留已删元素——之后每次鼠标移动触发状态更新都引用不存在的
   元素抛 Unknown element type，画布从此卡死。先模拟 pointerleave 让 behavior 走正常清理路径。 */
function detachG6Node(nodeId) {
  try {
    g6Graph.emit('pointerleave', { targetType: 'node', target: { id: nodeId, type: 'node' } });
  } catch (e) { /* 模拟失败不阻塞删除 */ }
  try {
    g6Graph.setElementState(nodeId, []);
  } catch (e) { /* 状态清理失败不阻塞删除 */ }
  g6Graph.removeNodeData([nodeId]);
  delete g6Meta[nodeId];
}

/* ⌘(Cmd/Meta) 按下状态全局追踪：G6 的 behavior enable 回调拿到的是包装事件，修饰键在 v5.0.49
   不可靠（实测恒 false），所以在原生 keydown/keyup 层维护；window 失焦时重置防 Cmd+Tab 卡按下态 */
window.__cmdDown = false;
(function trackMetaKey() {
  document.addEventListener('keydown', (e) => { window.__cmdDown = !!(e.metaKey || e.key === 'Meta'); });
  document.addEventListener('keyup', (e) => { if (!e.metaKey || e.key === 'Meta') window.__cmdDown = false; });
  window.addEventListener('blur', () => { window.__cmdDown = false; });
})();

/* ---------- ⌘+左键自绘框选：G6 v5.0.49 的 brush-select 行为 enable 判定不可靠（两次实测框不中），
   改为容器原生 pointer 事件 + 自渲染选择框，pointerup 时矩形转世界坐标命中节点，打 selected 状态 ---------- */

let brush = null; /* 框选进行中的起点（canvas 坐标） */

function brushRectEl() {
  let el = document.getElementById('g6-brush-rect');
  if (!el) {
    el = document.createElement('div');
    el.id = 'g6-brush-rect';
    Object.assign(el.style, { position: 'absolute', border: '1.5px solid #0F6E56',
      background: 'rgba(15,110,86,0.08)', borderRadius: '4px', pointerEvents: 'none', display: 'none', zIndex: 30 });
    $('g6-container').appendChild(el);
  }
  return el;
}

/* 框选矩形（画布坐标）转世界坐标：G6 v5.0.49 语义里 getCanvasByViewport(屏幕坐标) = 世界坐标
   （5.0.49 实测 [100,100] → [-1146.5,-303.0]，与线性推导吻合）；无此 API 时线性推导兜底 */
function brushToWorldRect(cx0, cy0, cx1, cy1) {
  const g = g6Graph;
  if (typeof g.getCanvasByViewport === 'function') {
    const w0 = g.getCanvasByViewport([cx0, cy0]), w1 = g.getCanvasByViewport([cx1, cy1]);
    return [[w0[0], w0[1]], [w1[0], w1[1]]];
  }
  const cw = (p) => (Array.isArray(p) ? p : [p.x, p.y]);
  const cc = cw(g.getCanvasCenter ? g.getCanvasCenter() : [g.getWidth() / 2, g.getHeight() / 2]);
  const wc = cw(g.getViewportCenter ? g.getViewportCenter() : [0, 0]);
  const z = (typeof g.getZoom === 'function' ? g.getZoom() : 1) || 1;
  const toWorld = (x, y) => [wc[0] + (x - cc[0]) / z, wc[1] + (y - cc[1]) / z];
  return [toWorld(cx0, cy0), toWorld(cx1, cy1)];
}

function bindBrushSelect(el) {
  /* 修饰键直接读事件参数：PointerEvent 的 metaKey 在原生/合成事件里都可靠（G6 behavior 内部包装才丢失）；
     window.__cmdDown（keydown/keyup 追踪）仅作兜底 */
  const isMeta = (e) => !!(e && (e.metaKey || (e.nativeEvent && e.nativeEvent.metaKey))) || !!window.__cmdDown;
  el.addEventListener('pointerdown', (e) => {
    if (!isMeta(e) || e.button !== 0) return;
    const r = el.getBoundingClientRect();
    brush = { x0: e.clientX - r.left, y0: e.clientY - r.top };
    const box = brushRectEl();
    Object.assign(box.style, { display: 'block', left: brush.x0 + 'px', top: brush.y0 + 'px', width: '0px', height: '0px' });
    e.preventDefault();
  });
  el.addEventListener('pointermove', (e) => {
    if (!brush) return;
    const r = el.getBoundingClientRect();
    const x1 = e.clientX - r.left, y1 = e.clientY - r.top;
    const box = brushRectEl();
    Object.assign(box.style, { left: Math.min(brush.x0, x1) + 'px', top: Math.min(brush.y0, y1) + 'px',
      width: Math.abs(x1 - brush.x0) + 'px', height: Math.abs(y1 - brush.y0) + 'px' });
  });
  el.addEventListener('pointerup', (e) => {
    if (!brush) return;
    const r = el.getBoundingClientRect();
    const x1 = e.clientX - r.left, y1 = e.clientY - r.top;
    brushRectEl().style.display = 'none';
    const x0 = brush.x0, y0 = brush.y0;
    brush = null;
    if (!graphAlive()) return;
    const swap = (a, b) => (a > b ? [b, a] : [a, b]);
    const [cx0, cx1] = swap(x0, x1), [cy0, cy1] = swap(y0, y1);
    if (cx1 - cx0 < 6 || cy1 - cy0 < 6) return; /* 拖拽距离太短视为误触 */
    /* 框选刚结束：G6 会补发一次 click（实测可能延迟一帧以上），时间戳守卫防止选中被秒清 */
    window.__brushDone = Date.now();
    try {
      const [w0, w1] = brushToWorldRect(cx0, cy0, cx1, cy1);
      const hits = (g6Graph.getNodeData() || []).filter((n) => {
        const p = g6Graph.getElementPosition(n.id);
        return p && p[0] >= Math.min(w0[0], w1[0]) && p[0] <= Math.max(w0[0], w1[0])
              && p[1] >= Math.min(w0[1], w1[1]) && p[1] <= Math.max(w0[1], w1[1]);
      }).map((n) => String(n.id));
      if (hits.length) hits.forEach((id) => g6Graph.setElementState(id, ['selected']));
    } catch (err) { console.error('框选失败:', err); }
  });
}

/* 渲染 G6 画布 + 节点点击交互（内容→详情、分类→编辑/进图谱、猫→进图谱/编辑；书走右键菜单/目录）
   改名为 renderG6_v2，renderG6 作为别名保留——浏览器缓存里可能残留旧版 graph.js（只有 renderG6 没有 v2），
   让 openGraph 里的 fallback 能同时覆盖新旧两版 */
function renderG6_v2(nodes, edges, meta, scope) {
  destroyGraph();
  g6Scope = scope || '';
  g6Meta = meta; /* 增量更新（新增/改名）要靠它认节点 */
  const el = $('g6-container');
  if (!window.G6 || !window.G6.Graph) { el.innerHTML = '<div class="empty">图谱组件（AntV G6）加载失败</div>'; return; }
  const config = {
    container: el,
    autoFit: 'view',
    padding: 40,
    autoResize: true,
    data: { nodes, edges },
    /* zoomRange 下限：0.25 = 可以缩到四分之一（够把整张图谱收进一屏）。
       再小也没意义——节点会缩成几个像素的点、字看不清；命中测试仍是世界坐标（见 g6NodeAt），
       所以缩得越小越难点中，需要放大回来操作。 */
    zoomRange: [0.25, 3],
    node: {
      style: g6NodeStyle,
      state: {
        active: { shadowOffsetX: 6, shadowOffsetY: 6 },
        /* Cmd 框选批量选中的节点：绿描边 + 光晕恒亮 */
        selected: { stroke: '#0F6E56', lineWidth: 4, halo: true, haloStroke: '#0F6E56', haloLineWidth: 12 },
      },
    },
    edge: { type: 'quadratic', style: g6EdgeStyle },
    behaviors: [
      /* macOS 风格批量操作：按住 ⌘(Cmd/Meta) + 左键拖拽 = 框选节点（bindBrushSelect 自绘实现）；松开 ⌘ 恢复平移画布 */
      /* 自定义 enable 必须并保留 G6 内置的 targetType==='canvas' 判断（默认 enable 就是它）——
         覆盖后节点上的拖拽会冒泡成画布平移（「拖节点画布跟着飘」的根因） */
      { type: 'drag-canvas', enable: (e) => !window.__cmdDown && (!e || !('targetType' in e) || e.targetType === 'canvas') },
      'zoom-canvas',
      /* 拖动任一 selected 节点时，框选集合一起跟着走；⌘ 框选中禁止单节点拖拽，防止框选起点落在节点上时把节点拖跑 */
      { type: 'drag-element', enable: () => !window.__cmdDown },
      'hover-activate',
    ],
  };
  /* 强制重新布局（window.forceRelayoutForG6 由 openGraph 设）或 所有节点都没有持久化位置时跑力导向，把节点撒开；
     持久化位置存在时跳过，保持与关闭时一致（用户手动拖过的不被破坏）。
     用全局变量而非参数传递 forceRelayout，避免浏览器旧缓存里 renderG6 函数签名还停在 4 参数时 ReferenceError。 */
  const forceRelayout = !!window.forceRelayoutForG6;
  const allPositioned = nodes.length > 0 && nodes.every((n) => n.style && n.style.x != null);
  if (forceRelayout || !allPositioned) {
    config.layout = {
      type: 'd3-force',
      link: { distance: forceRelayout ? 220 : 170 },       /* 连线长度：刷新时拉得更开 */
      charge: { strength: forceRelayout ? -900 : -620 },    /* 节点间排斥力：刷新时更大 */
      collide: { radius: forceRelayout ? 80 : 62 },        /* 碰撞检测半径：避免节点贴脸 */
      center: { strength: 0.06 }                            /* 向画布中心聚拢的弱力，防止散开后飘走 */
    };
  }
  g6Graph = new window.G6.Graph(config);
  window.__g6 = g6Graph; /* 调试句柄 */
  bindDragCascade(g6Graph, edges);
  bindBrushSelect(el); /* ⌘+左键框选（自绘）：Cmd 按下时平移/拖拽已禁用，框住的节点打 selected */
  /* 右键节点：弹出自定义上下文菜单（知识分类维护）。
     G6 v5 实测既不派发 contextmenu，node:pointerdown 也不可靠（只转发 canvas:pointerdown），
     所以右键由容器原生事件触发，目标节点用「光标世界坐标 + 最近节点」自行命中 */
  el.addEventListener('contextmenu', (ev) => {
    ev.preventDefault(); /* 屏蔽浏览器原生菜单，避免和自定义菜单重叠 */
    const id = g6NodeAt(ev, el, meta);
    if (!id) return closeNodeMenu();
    showNodeMenu(ev.clientX, ev.clientY, nodeMenuItems(meta[id]));
  });
  g6Graph.on('afterlayout', () => saveG6Pos(g6Scope)); /* 力导向收敛后记录初始位置 */
  g6Graph.on('node:dragend', () => saveG6Pos(g6Scope)); /* 拖完（含批量/联动子树）记录最新位置 */
  /* 点画布空白：清掉框选（选中态节点全回到常态），位置已在 dragend 落盘。
     框选收尾补发的 click（实测有延迟）用 __brushDone 时间戳挡住，避免选中被秒清 */
  const brushGuard = () => Date.now() - (window.__brushDone || 0) < 1500; /* 实测 G6 补发 click 晚 ~500ms */
  g6Graph.on('canvas:click', () => {
    if (brushGuard()) return;
    try {
      const sel = (g6Graph.getElementDataByState('node', 'selected') || []).map((n) => String(n.id));
      if (sel.length) sel.forEach((id) => g6Graph.setElementState(id, []));
    } catch (e) { /* 清理失败忽略 */ }
  });
  g6Graph.on('node:click', (e) => {
    if (brushGuard()) return; /* 框选收尾那一下别误开详情 */
    const m = meta[String(e.target.id || '')];
    if (!m) return;
    if (m.kind === 'fsfile') return openFsPreview(m.catId, m.path); /* origin 文件 → 弹预览 */
    if (m.kind === 'folder') return openDrawerFolder(m.catId, m.name); /* 一级类目=origin 文件夹 → 弹类目抽屉 */
    if (m.kind === 'content') return openDetail(m.num);
    if (m.kind === 'type') return openDrawerType(m.num); /* 一级节点单击 → 弹出分类抽屉（书列表/维护入口） */
    return state.graphCatId ? openCatModal(m.num) : openGraph(m.num);
  });
  g6Graph.render().catch((err) => console.error('图谱渲染失败:', err));
}
/* renderG6 别名：让浏览器缓存里只有 renderG6（旧版 4 参数）的情况也能初始化
   ——openGraph 里 typeof renderG6 === 'function' 就能 fallback */
const renderG6 = renderG6_v2;
async function openDrawerBook(id) {
  state.view = 'graph';
  state.drawerBookId = id;
  const data = await api('/api/books/' + id);
  const b = data.book, t = data.type;
  let html = `
    <div class="drawer-head">
      <div class="drawer-kicker"><span class="dot" style="background:${esc(t?.color || '#888')}"></span>${esc(t?.icon || '')} ${esc(t?.name || '')} · GRAVITY</div>
      <div class="drawer-title">${esc(b.title)}</div>
      <div class="drawer-meta">${b.contentCount} 条内容</div>
      <div class="drawer-actions">
        <button class="btn sm" onclick="closeDrawer();openBook(${b.id})">📖 打开书架视图</button>
        <button class="btn sm" onclick="renameBook(${b.id}, '${esc(b.title).replace(/'/g, "\\'")}')">✏️ 改书名</button>
        <button class="btn sm danger" onclick="deleteBook(${b.id})">删除本书</button>
      </div>
    </div>`;
  /* 平铺：书已是最后一级类目，书下直接挂具体内容——目录不再作为层级展示，只留来源小标签 */
  const flat = [];
  data.sections.forEach((sec) => {
    sec.contents.forEach((c) => flat.push({ c, cat: sec.node.name }));
    sec.children.forEach((ch) => ch.contents.forEach((c) => flat.push({ c, cat: ch.node.name })));
  });
  data.loose.forEach((c) => flat.push({ c, cat: null }));
  if (!flat.length) {
    html += `<div class="empty">这本书还没有内容</div>`;
  } else {
    /* 喵的提取：这本书的内容若已被喵读厚成理解卡片，先列出（书籍+卡片主题，紧凑小字），未整理过则不出现 */
    html += await drawerExtractBlock(data, flat);
    html += `<div class="content-list">${flat.map((x) => drawerCard(x.c, x.cat)).join('')}</div>`;
  }
  $('drawerBody').innerHTML = html;
  $('drawerBody').scrollTop = 0;
  $('drawer').classList.add('open');
  $('drawerMask').style.display = 'block';
}

/* 书本抽屉「喵的提取」区块：这本书是否被整理过由后端指纹判定（data.tidy.tidied），卡片行由后端反追踪
   来源表（contentId → 卡片编号）查出（data.tidy.cards）——不依赖卡片 JSON 的 sources 字段，历史卡也能追到。
   没整理过 → 不出现；读过但没产卡 → 只显示进度说明 */
async function drawerExtractBlock(data, flat) {
  const tidy = data.tidy || { total: flat.length, tidied: 0, cards: [] };
  if (!tidy.tidied) return ''; /* 指纹判定：这本书的内容还没被喵整理过 → 不出现 */
  const catId = flat.map((x) => x.c.catId).find((v) => v) || (data.type && data.type.catId);
  if (!catId) return '';
  const cards = tidy.cards || [];
  if (cards.length) state.readingCards = await api('/api/cats/' + catId + '/reading/cards').catch(() => []);
  /* 上面填充 state.readingCards 是 openPointDetail 关联跳转的依赖（须指向同一猫的卡集） */
  const rows = cards.length
    ? `<div class="de-cards">${cards.map((c) => `
      <div class="de-row" onclick="openPointDetail(${catId}, '${esc(c.no || '')}')" title="点击查看卡片详情">
        ${c.bookTitle ? `<span class="de-book">📚 《${esc(c.bookTitle)}》</span>` : ''}
        <span class="de-topic">${esc(c.topic || '未命名卡')}</span>
        ${c.no ? `<span class="de-no">${esc(c.no)}</span>` : ''}
      </div>`).join('')}</div>`
    : `<div class="de-empty">喵已读过这本书的内容，但还没沉淀出理解卡片</div>`;
  return `<div class="drawer-extract">
    <div class="de-head">🐾 喵的提取 <span class="de-count">已整理 ${tidy.tidied}/${tidy.total} 篇 · ${cards.length} 张卡片</span></div>${rows}
  </div>`;
}

/* 点一级节点（知识分类）→ 右侧抽屉：分类信息 + 直接平铺内容列表（书层透明：分类下就是内容） */
function openDrawerType(typeId) {
  const t = ((state.shelf && state.shelf.types) || []).find((x) => x.id === typeId) ||
    (g6Meta['type-' + typeId]
      ? Object.assign({ icon: '', color: '#888', books: g6Meta['type-' + typeId].books || [] }, g6Meta['type-' + typeId], { id: typeId })
      : null);
  if (!t) return;
  state.view = 'graph';
  state.drawerTypeId = typeId;
  /* 与画布同源的内容叶子（该猫名下），过滤出该分类书下的条目，按书透明直列 */
  const bookIds = (t.books || []).map((b) => b.id);
  const items = (state.graphContents || []).filter((c) => bookIds.includes(c.bookId));
  const html = `
    <div class="drawer-head">
      <div class="drawer-kicker"><span class="dot" style="background:${esc(t.color || '#888')}"></span>知识分类 · CATEGORY${t.shared ? ' · 🌐 共享' : ''}</div>
      <div class="drawer-title">${esc(t.icon || '')} ${esc(t.name)}</div>
      <div class="drawer-meta">${items.length} 条内容</div>
      ${t.description ? `<div class="drawer-desc">${esc(t.description)}</div>` : ''}
      <div class="drawer-actions">
        <button class="btn sm" onclick="closeDrawer();openTypeModal(${t.id})">✏️ 编辑分类</button>
        <button class="btn sm" onclick="openCollect()">＋ 新增内容</button>
        ${t.preset ? '' : `<button class="btn sm danger" onclick="closeDrawer();deleteType(${t.id})">删除分类</button>`}
      </div>
    </div>
    ${items.length ? `<div class="content-list">${items.map((c) => drawerCard(c, null)).join('')}</div>` : '<div class="empty">这个分类还没有内容，点「＋ 新增内容」投递第一条</div>'}`;
  $('drawerBody').innerHTML = html;
  $('drawerBody').scrollTop = 0;
  $('drawer').classList.add('open');
  $('drawerMask').style.display = 'block';
}

function closeDrawer() {
  $('drawer').classList.remove('open');
  $('drawerMask').style.display = 'none';
  state.drawerBookId = null;
  state.drawerTypeId = null;
  state.drawerFolder = null;
}

/* 点一级类目（origin 顶层文件夹）→ 右侧抽屉：类目信息 + 文件直列（点文件开预览，与画布同源） */
function openDrawerFolder(catId, folder) {
  const grp = (state.graphFs || []).find((g) => g.folder === folder);
  if (!grp) return;
  const files = grp.files || [];
  state.view = 'graph';
  state.drawerFolder = { catId, folder };
  const html = `
    <div class="drawer-head">
      <div class="drawer-kicker"><span class="dot" style="background:#0F6E56"></span>一级类目 · ORIGIN</div>
      <div class="drawer-title">📁 ${esc(folder)}</div>
      <div class="drawer-meta">${files.length} 个文件</div>
      <div class="drawer-actions">
        <button class="btn sm" onclick="openCollect()">＋ 新增内容</button>
        <button class="btn sm" onclick="openGraph(${catId}, true)">🔄 重新布局</button>
        <button class="btn sm danger" onclick="closeDrawer();deleteGraphFile(${catId}, 'origin/${esc(folder).replace(/'/g, "\\'")}')">删除文件夹</button>
      </div>
    </div>
    ${files.length ? `<div class="content-list">${files.map((f) => fsDrawerRow(catId, f)).join('')}</div>`
      : '<div class="empty">这个类目还没有文件</div>'}`;
  $('drawerBody').innerHTML = html;
  $('drawerBody').scrollTop = 0;
  $('drawer').classList.add('open');
  $('drawerMask').style.display = 'block';
}

/* 类目抽屉里的 origin 文件行：单击开预览（deleteGraphFile 成功后由 refreshView 重建抽屉） */
function fsDrawerRow(catId, f) {
  const p = esc(f.path).replace(/'/g, "\\'");
  return `
    <div class="content-card drawer-card">
      <div class="content-icon">${/\.md$/i.test(f.name) ? '📝' : '📄'}</div>
      <div class="content-main">
        <div class="content-title" onclick="openFsPreview(${catId}, '${p}')">${esc(f.name)}</div>
        <div class="content-meta"><span class="content-path">${fmtDate(f.lastModified)}</span></div>
      </div>
      <div class="content-actions">
        <button class="btn sm" onclick="openFsPreview(${catId}, '${p}')">预览</button>
      </div>
    </div>`;
}

/* 抽屉内的精简内容卡片：详情 / 移动 / 重新分类 / 删除；cat = 所属目录（平铺时作来源小标签） */
function drawerCard(c, cat) {
  const tags = (cat ? `<span class="tag">📂 ${esc(cat)}</span>` : '') + (c.matchedTags || '').split(',').filter(Boolean).slice(0, 3)
    .map((t) => `<span class="tag">#${esc(t)}</span>`).join('');
  return `
    <div class="content-card drawer-card">
      <div class="content-icon">${TYPE_ICONS[c.contentType] || '📝'}</div>
      <div class="content-main">
        <div class="content-title" onclick="openDetail(${c.id})">${esc(c.title)}</div>
        <div class="content-summary md-body">${mdLite(c.summary || '')}</div>
        <div class="content-meta">${tags}<span class="content-path">${fmtDate(c.createdAt)}</span></div>
      </div>
      <div class="content-actions">
        <button class="btn sm" onclick="openMoveModal(${c.id})">移动</button>
        <button class="btn sm" onclick="reclassify(${c.id})">重分</button>
        <button class="btn sm danger" onclick="deleteContent(${c.id})">删除</button>
      </div>
    </div>`;
}

