/* ===== 工作区文件树 + 文件预览（后端 /api/cats/{id}/files 扁平清单 → 前端组树） ===== */
async function loadWsTree(catId) {
  const box = $('wsTree');
  if (!box) return;
  const cats = (state.shelf && state.shelf.cats) || [];
  const c = cats.find((x) => x.id === catId);
  const catEl = $('wsTreeCat');
  if (catEl) catEl.textContent = c ? ((c.icon || '🐱') + ' ' + c.name) : '';
  box.innerHTML = '<div class="ws-tree-empty">加载中…</div>';
  try {
    const list = await api('/api/cats/' + catId + '/chat/files');
    state.wsFiles = list || [];
    state.wsCatId = catId; /* 树根节点显示父目录名用 */
    state.wsOpenDirs = new Set();
    /* 顶层目录默认展开：一眼看到主要结构（reading/、points/…）；根节点（父目录本身）也默认展开 */
    state.wsOpenDirs.add('');
    state.wsFiles.forEach((f) => { if (f.dir && f.path.indexOf('/') < 0) state.wsOpenDirs.add(f.path); });
    state.wsSelFile = null;
    renderWsTree();
    resetWsFile();
    wsTreeSig = JSON.stringify(state.wsFiles || []);
    startWsTreeWatch(); /* 进工作区/切猫后开始跟随文件系统变化 */
  } catch (e) {
    box.innerHTML = '<div class="ws-tree-empty">' + esc(e.message) + '</div>';
  }
}

/* 文件树自动跟随（5 秒轮询）：工作区里的文件不一定由本页会话产生——客户端同步推上来的、
   喵从别的入口/别的标签页写的、别的猫的工作区被整理后的产物。只在进入/切猫时拉一次，
   会一直显示旧结构，就是「文件变了文件夹没跟上」的来由。
   清单签名没变不重绘（保住目录展开态与选中文件），离开工作区自动停表。 */
let wsTreeTimer = null;
let wsTreeSig = '';
function startWsTreeWatch() {
  stopWsTreeWatch();
  const tick = async () => {
    if (state.view !== 'workspace' || state.wsCatId == null) { stopWsTreeWatch(); return; }
    if (document.hidden) return; /* 后台标签页不轮询 */
    try {
      const list = await api('/api/cats/' + state.wsCatId + '/chat/files');
      const sig = JSON.stringify(list || []);
      if (sig === wsTreeSig) return;
      wsTreeSig = sig;
      state.wsFiles = list || [];
      renderWsTree();
      /* 预览中的文件被删/被挪走 → 中栏回落到空预览 */
      if (state.wsSelFile && !state.wsFiles.some((f) => f.path === state.wsSelFile)) {
        state.wsSelFile = null;
        resetWsFile();
      }
    } catch (e) { /* 轮询失败静默，下个周期再试 */ }
  };
  wsTreeTimer = setInterval(tick, 5000);
}
function stopWsTreeWatch() { if (wsTreeTimer) { clearInterval(wsTreeTimer); wsTreeTimer = null; } }

/* 扁平 [{path,dir,size}] → 树模型：目录在前、同名 localeCompare 排序 */
function wsTreeModel() {
  const root = { children: new Map() };
  for (const f of state.wsFiles || []) {
    const parts = f.path.split('/');
    let node = root;
    for (let i = 0; i < parts.length - 1; i++) {
      const seg = parts[i];
      if (!node.children.has(seg)) node.children.set(seg, { name: seg, dir: true, path: parts.slice(0, i + 1).join('/'), children: new Map() });
      node = node.children.get(seg);
    }
    const last = parts[parts.length - 1];
    /* 目录节点必须带 children（顶层目录也走 last 分支创建，漏挂会让后续子项崩掉）；
       同名先登记成文件、后遇目录时原地升级为目录节点 */
    if (!node.children.has(last)) {
      node.children.set(last, f.dir
        ? { name: last, dir: true, path: f.path, children: new Map() }
        : { name: last, dir: false, path: f.path, size: f.size });
    } else if (f.dir && !node.children.get(last).children) {
      const ex = node.children.get(last);
      ex.dir = true; ex.path = f.path; ex.children = new Map(); delete ex.size;
    }
  }
  const sortKids = (n) => {
    const kids = [...n.children.values()];
    kids.sort((a, b) => (b.dir - a.dir) || a.name.localeCompare(b.name, 'zh-CN'));
    kids.forEach((k) => { if (k.dir) sortKids(k); });
    n.kids = kids;
  };
  sortKids(root);
  return root.kids || [];
}

function renderWsTree() {
  const box = $('wsTree');
  if (!box) return;
  const build = (nodes) => nodes.map((n) => {
    if (n.dir) {
      const open = state.wsOpenDirs && state.wsOpenDirs.has(n.path);
      return `<div class="ws-row" onclick="toggleWsDir('${esc(n.path)}')" oncontextmenu="wsFileMenu(event,'${esc(n.path)}',true)" title="${esc(n.path)}">` +
        `<span class="tw">${open ? '▾' : '▸'}</span><span class="f-ico">${open ? '📂' : '📁'}</span><span class="f-name">${esc(n.name)}</span></div>` +
        `<div class="ws-kids${open ? ' open' : ''}">${build(n.kids || [])}</div>`;
    }
    const sel = state.wsSelFile === n.path ? ' sel' : '';
    const ico = /\.md$/i.test(n.name) ? '📝'
      : (/\.xlsx$/i.test(n.name) ? '📊'
      : (/\.docx$/i.test(n.name) ? '📘'
      : (/\.json$/i.test(n.name) ? '🧾' : '📄')));
    return `<div class="ws-row${sel}" onclick="openWsFile('${esc(n.path)}')" oncontextmenu="wsFileMenu(event,'${esc(n.path)}',false)" title="${esc(n.path)}">` +
      `<span class="tw"></span><span class="f-ico">${ico}</span><span class="f-name">${esc(n.name)}</span></div>`;
  }).join('');
  const kids = wsTreeModel();
  if (!kids.length) { box.innerHTML = '<div class="ws-tree-empty">工作区还是空的，让喵整理一轮就有了</div>'; return; }
  /* 顶层根节点 = 父目录（工作区目录名 cat-{id}），可展开/合上整棵树 */
  const rootName = 'cat-' + (state.wsCatId != null ? state.wsCatId : (chat && chat.catId) || '');
  const rootOpen = !state.wsOpenDirs || state.wsOpenDirs.has('');
  box.innerHTML =
    `<div class="ws-row ws-root" onclick="toggleWsDir('')" title="${esc(rootName)}">` +
    `<span class="tw">${rootOpen ? '▾' : '▸'}</span><span class="f-ico">${rootOpen ? '📂' : '📁'}</span><span class="f-name">${esc(rootName)}</span></div>` +
    `<div class="ws-kids${rootOpen ? ' open' : ''}">${build(kids)}</div>`;
}

function toggleWsDir(path) {
  if (!state.wsOpenDirs) state.wsOpenDirs = new Set();
  if (state.wsOpenDirs.has(path)) state.wsOpenDirs.delete(path); else state.wsOpenDirs.add(path);
  renderWsTree();
}

/* 文件树右键菜单：删除文件/文件夹（复用图谱右键菜单，全局 pointerdown 已做点击外部关闭） */
function wsFileMenu(e, path, isDir) {
  e.preventDefault();
  e.stopPropagation();
  showNodeMenu(e.clientX, e.clientY, [
    { label: '🗑 删除' + (isDir ? '文件夹' : '文件'), danger: true, run: () => deleteWsEntry(path, isDir) },
  ]);
}

async function deleteWsEntry(path, isDir) {
  const label = isDir ? '文件夹「' + path + '」（含全部内容）' : '文件「' + path + '」';
  if (!confirm('删除' + label + '？删除后不可恢复。')) return;
  try {
    await api('/api/cats/' + chat.catId + '/chat/file?path=' + encodeURIComponent(path), { method: 'DELETE' });
    /* 就地摘除树节点（保留目录展开态）：过滤掉自身与前缀命中的子孙条目 */
    state.wsFiles = (state.wsFiles || []).filter((f) => f.path !== path && !f.path.startsWith(path + '/'));
    wsTreeSig = JSON.stringify(state.wsFiles); /* 与轮询基线对齐，避免下个 tick 白重绘一次 */
    if (state.wsSelFile && (state.wsSelFile === path || state.wsSelFile.startsWith(path + '/'))) {
      state.wsSelFile = null;
      resetWsFile();
    }
    renderWsTree();
    toast('已删除' + label);
  } catch (err) {
    toast(err.message, true);
  }
}

/* ---------- 预览导航历史栈（中栏工作区预览 / 图谱文件弹窗共用）：
   load 压栈渲染，back/fwd 走栈内导航（不重复压栈），文内链接跳转也经 load 累积历史 ---------- */
function makeNavStack(onLoad) {
  const s = { stack: [], idx: -1 };
  s.load = (path) => { s.stack = s.stack.slice(0, s.idx + 1); s.stack.push(path); s.idx = s.stack.length - 1; return onLoad(path); };
  s.back = () => { if (s.canBack()) { s.idx--; return onLoad(s.stack[s.idx]); } };
  s.fwd = () => { if (s.canFwd()) { s.idx++; return onLoad(s.stack[s.idx]); } };
  s.canBack = () => s.idx > 0;
  s.canFwd = () => s.idx < s.stack.length - 1;
  s.current = () => (s.idx >= 0 ? s.stack[s.idx] : '');
  return s;
}

/* md 文内相对链接 → 工作区路径（相对当前 md 所在目录解析，含 origin 归位兜底），仅接受 md/txt 目标 */
function resolveWsRelPath(mdPath, href) {
  const t = wsImgPathCandidates(mdPath, href).find((p) => /\.(md|txt)$/i.test(p));
  return t || null;
}

let wsNav = null; /* 中栏预览历史栈（按猫隔离） */
function wsNavFor() {
  if (!wsNav || wsNav.catId !== chat.catId) {
    wsNav = makeNavStack(wsRenderFile);
    wsNav.catId = chat.catId;
  }
  return wsNav;
}
function wsNavStep(d) { const n = wsNavFor(); if (d < 0) n.back(); else n.fwd(); }

/* 点文件 → 压栈并渲染（文件树点击 / 喵写文件联动 / 文内链接跳转统一入口）；
   后端已做路径越界防护与 6 万字截断 */
async function openWsFile(path) {
  if (!path) return;
  /* 切换文件前清掉可能残留的内嵌浏览器覆盖层 */
  closeWebBrowse($('wsFileBody'));
  return wsNavFor().load(path);
}

/* 渲染当前预览文件（历史导航与首次打开都落到这里，不再重复压栈） */
async function wsRenderFile(path) {
  state.wsSelFile = path;
  renderWsTree();
  const head = $('wsFileHead'), body = $('wsFileBody');
  if (!head || !body) return;
  const nav = wsNavFor();
  const ext = (path.match(/\.([a-z0-9]+)$/i) || [null, ''])[1].toLowerCase();
  const ico = { md: '📝', json: '🧾', xlsx: '📊', docx: '📘' }[ext] || '📄';
  head.innerHTML = `<button class="ws-nav" type="button" onclick="wsNavStep(-1)" title="后退" ${nav.canBack() ? '' : 'disabled'}>←</button>`
    + `<button class="ws-nav" type="button" onclick="wsNavStep(1)" title="前进" ${nav.canFwd() ? '' : 'disabled'}>→</button>`
    + '<span>' + ico + '</span><span class="fp">' + esc(path) + '</span>'
    + '<span class="ws-head-actions" id="wsHeadActions"></span>'
    + '<button class="ws-close" type="button" title="关闭预览" onclick="closeWsFile()">✕</button>';
  body.innerHTML = '<div class="empty">加载中…</div>';
  try {
    if (ext === 'xlsx' || ext === 'docx') {
      body.innerHTML = await renderOfficePreview(path, ext);
      return;
    }
    if (ext === 'xls' || ext === 'doc') {
      body.innerHTML = '<div class="empty">旧版二进制 ' + esc(ext.toUpperCase()) + ' 暂不支持在线预览，请在 Office 里另存为 ' + esc(ext) + 'x 后再打开</div>';
      return;
    }
    const r = await api('/api/cats/' + chat.catId + '/chat/file?path=' + encodeURIComponent(path));
    if (/\.md$/i.test(path)) {
      const raw = r.content || '';
      wsRawContent = raw;
      wsEditMode = false;
      /* 预览 / 编辑两块同时挂上：切换只切显示，textarea 里改了一半的内容不会丢。
         #wsFileBody 本身已带 md-body，预览面板不再重复挂类 */
      body.innerHTML = `<div id="wsPreviewPane">${mdFinal(raw, { resolve: (h) => resolveWsRelPath(path, h) })}</div>`
        + `<div id="wsEditPane" style="display:none"><textarea class="fs-editor" id="wsEditor" spellcheck="false">${esc(raw)}</textarea></div>`;
      wsInlineImages($('wsPreviewPane'), path, chat.catId); /* 相对路径图片渐进回填，不阻塞正文渲染 */
      mmdHydrate($('wsPreviewPane')); /* mermaid 代码块 → 流程图（库懒加载，异步填图） */
      /* 读取端对超大文件做过截断（r.truncated），照它保存会把原文截掉，索性不给编辑入口 */
      wsSetHead(r.truncated
        ? '<span class="fs-trunc">文件较大已截断，仅供预览</span>'
        : `<div class="fs-mode">
             <button class="fs-mode-btn on" type="button" data-mode="view" onclick="wsSetMode('view')">👁 预览</button>
             <button class="fs-mode-btn" type="button" data-mode="edit" onclick="wsSetMode('edit')">✏️ 编辑</button>
           </div>
           <button class="btn sm primary" type="button" id="wsSaveBtn" style="display:none" onclick="wsSaveFile()">💾 保存</button>`);
    } else {
      body.innerHTML = '<pre class="raw">' + esc(r.content || '') + '</pre>';
    }
  } catch (e) {
    body.innerHTML = '<div class="empty">' + esc(e.message) + '</div>';
  }
}

/* ---------- 工作区中栏预览的「预览 / 编辑 / 保存」：
   与详情页弹窗（fs* 那套）同一套交互，只是作用面从中栏 #wsFileBody 走 ---------- */
let wsRawContent = '';
let wsEditMode = false;

function wsSetHead(html) {
  const h = $('wsHeadActions');
  if (h) h.innerHTML = html;
}

/* 预览 / 编辑切换：只切显示 */
function wsSetMode(mode) {
  const pv = $('wsPreviewPane'), ed = $('wsEditPane'), ta = $('wsEditor');
  if (!pv || !ed || !ta) return;
  wsEditMode = mode === 'edit';
  pv.style.display = wsEditMode ? 'none' : '';
  ed.style.display = wsEditMode ? '' : 'none';
  const save = $('wsSaveBtn');
  if (save) save.style.display = wsEditMode ? '' : 'none';
  document.querySelectorAll('#wsHeadActions .fs-mode-btn')
    .forEach((b) => b.classList.toggle('on', b.dataset.mode === mode));
  if (wsEditMode) ta.focus();
}

/* 保存：把编辑框里的 md 覆盖写回工作区文件，成功后用新内容重渲染预览并切回预览态 */
async function wsSaveFile() {
  const ta = $('wsEditor');
  const path = state.wsSelFile;
  if (!ta || !path) return;
  const save = $('wsSaveBtn');
  if (save) save.disabled = true;
  try {
    await api('/api/cats/' + chat.catId + '/chat/file?path=' + encodeURIComponent(path),
      { method: 'PUT', body: JSON.stringify({ content: ta.value }) });
    wsRawContent = ta.value;
    const pv = $('wsPreviewPane');
    pv.innerHTML = mdFinal(wsRawContent, { resolve: (h) => resolveWsRelPath(path, h) });
    wsInlineImages(pv, path, chat.catId);
    mmdHydrate(pv);
    wsSetMode('view');
    toast('已保存 ' + path.split('/').pop());
  } catch (e) {
    toast('保存失败：' + e.message, true);
  } finally {
    if (save) save.disabled = false;
  }
}

/* 预览区文内链接委托：md 里的相对 .md/.txt 链接跳到对应文件（压栈，可回退）；
   外部 http(s) 链接由 renderer 直接 target=_blank，不经这里 */
document.addEventListener('click', (ev) => {
  const a = ev.target && ev.target.closest ? ev.target.closest('a[data-wslink]') : null;
  if (!a) return;
  ev.preventDefault();
  const path = a.getAttribute('data-wslink');
  if (!path) return;
  if (a.closest('#fsPrevBody')) openFsPreview(null, path);
  else if (a.closest('#wsFileBody')) openWsFile(path);
});

/* ===== 详情页内嵌浏览：外链就地变成浏览器，不跳走也不开新标签 =====
   微信这类站点用 CSP frame-ancestors 禁止被第三方 iframe 嵌入，直接嵌原址会白屏；
   改走服务端 /api/proxy/page 抓回来做只读改写（去脚本、补 base、no-referrer），
   再以本服务同源塞进 iframe —— 于是「详情页就地渲染」，页面本身不发生跳转。 */
const WB_HOSTS = '#wsFileBody, #fsPrevBody, #detailBody';

/* 文件内嵌链接 / 详情来源链接：http(s) 外链一律就地内嵌浏览 */
document.addEventListener('click', (ev) => {
  const a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
  if (!a) return;
  const host = a.closest(WB_HOSTS);
  if (!host) return;
  const href = a.getAttribute('href') || '';
  if (!/^https?:\/\//i.test(href)) return; /* data-wslink（javascript:）等文内跳转不在此列 */
  ev.preventDefault();
  openWebBrowse(host, href);
});

async function wbFetch(url) {
  const res = await fetch('/api/proxy/page?url=' + encodeURIComponent(url), { headers: authHeaders() });
  const text = await res.text();
  if (res.status === 401) { gotoLogin(); throw new Error('登录已过期'); }
  if (!res.ok) {
    let msg = '抓取失败';
    try { msg = JSON.parse(text).error || msg; } catch (e) { /* 非 JSON 错误体按默认文案 */ }
    throw new Error(msg);
  }
  return text;
}

/* 覆盖层定位：fixed + boundingRect，父容器保持可滚动不受影响。
   监听 host 的尺寸变化（ResizeObserver）与滚动/窗口尺寸变化（scroll/resize），实时同步位置 */
function wbSync(v) {
  const r = v.host.getBoundingClientRect();
  v.box.style.left   = r.left + 'px';
  v.box.style.top    = r.top + 'px';
  v.box.style.width  = r.width + 'px';
  v.box.style.height = r.height + 'px';
}

function openWebBrowse(host, url) {
  let v = host.__wb;
  if (!v) {
    host.classList.add('wb-host');
    const box = document.createElement('div');
    box.className = 'webbrowse';
    box.innerHTML = '<div class="wb-bar">'
      + '<button type="button" class="wb-btn" data-wb="back" title="后退">←</button>'
      + '<button type="button" class="wb-btn" data-wb="reload" title="重新加载">⟳</button>'
      + '<span class="wb-url"></span>'
      + '<button type="button" class="wb-btn" data-wb="ext" title="用系统浏览器打开">↗</button>'
      + '<button type="button" class="wb-btn" data-wb="close" title="关闭浏览器视图">×</button>'
      + '</div><div class="wb-stage"><div class="wb-loading">加载中…</div><iframe class="wb-frame"></iframe></div>';
    document.body.appendChild(box); /* fixed 定位，挂 body 上；不在 host 内，否则 host 滚动会把它带走 */
    v = host.__wb = { box, host, stack: [], idx: -1, ro: null, scrollHandler: null, resizeHandler: null };
    wbSync(v);
    /* host 自己尺寸变了（窗口缩放/分隔条拖拽/modal 开合） */
    v.ro = new ResizeObserver(() => wbSync(v));
    v.ro.observe(host);
    /* 任何祖先滚动都可能让 host 位移（ws-file-body 本身、modal-body、页面滚动） */
    v.scrollHandler = () => wbSync(v);
    v.resizeHandler = () => wbSync(v);
    let p = host;
    while (p && p !== document.body) {
      if (getComputedStyle(p).overflow !== 'visible') p.addEventListener('scroll', v.scrollHandler, { passive: true });
      p = p.parentElement;
    }
    document.addEventListener('scroll', v.scrollHandler, { passive: true, capture: true });
    window.addEventListener('resize', v.resizeHandler);
    box.querySelector('[data-wb="back"]').onclick = () => wbGo(host, v.idx - 1);
    box.querySelector('[data-wb="reload"]').onclick = () => wbGo(host, v.idx);
    box.querySelector('[data-wb="ext"]').onclick = () => window.open(v.stack[v.idx], '_blank', 'noopener');
    box.querySelector('[data-wb="close"]').onclick = () => closeWebBrowse(host);
  }
  v.stack = v.stack.slice(0, v.idx + 1).concat(url); /* 新导航丢弃前进历史 */
  wbGo(host, v.stack.length - 1);
}

async function wbGo(host, idx) {
  const v = host.__wb;
  if (!v || idx < 0 || idx >= v.stack.length) return;
  v.idx = idx;
  const url = v.stack[idx];
  const frame = v.box.querySelector('.wb-frame');
  const loading = v.box.querySelector('.wb-loading');
  const urlEl = v.box.querySelector('.wb-url');
  urlEl.textContent = url;
  urlEl.title = url;
  v.box.querySelector('[data-wb="back"]').disabled = idx === 0;
  loading.textContent = '加载中…';
  loading.style.display = 'block';
  frame.style.visibility = 'hidden';
  try {
    const html = await wbFetch(url);
    if (v.idx !== idx) return; /* 加载期间又跳走了，丢弃这次结果 */
    frame.onload = () => {
      loading.style.display = 'none';
      frame.style.visibility = 'visible';
      wbHookLinks(frame, host, url);
    };
    frame.srcdoc = html;
  } catch (e) {
    loading.textContent = e.message;
  }
}

/* iframe 内的链接点击：srcdoc 与本页同源，可挂监听继续走代理，保持「就地浏览」 */
function wbHookLinks(frame, host, curUrl) {
  let doc;
  try { doc = frame.contentDocument; } catch (e) { return; } /* 跨源（不该发生）就放弃接管 */
  if (!doc) return;
  doc.addEventListener('click', (ev) => {
    const a = ev.target && ev.target.closest ? ev.target.closest('a[href]') : null;
    if (!a) return;
    const raw = a.getAttribute('href') || '';
    if (!raw || raw.startsWith('#')) return;
    ev.preventDefault();
    let abs;
    try { abs = new URL(raw, curUrl).href; } catch (e) { return; }
    if (!/^https?:/i.test(abs)) return;
    openWebBrowse(host, abs);
  });
}

function closeWebBrowse(host) {
  const v = host.__wb;
  if (!v) return;
  if (v.ro) { try { v.ro.disconnect(); } catch (e) {} }
  if (v.scrollHandler) {
    let p = host;
    while (p && p !== document.body) {
      p.removeEventListener('scroll', v.scrollHandler);
      p = p.parentElement;
    }
    document.removeEventListener('scroll', v.scrollHandler, true);
  }
  if (v.resizeHandler) window.removeEventListener('resize', v.resizeHandler);
  v.box.remove();
  host.__wb = null;
  host.classList.remove('wb-host');
}

/* ===== 工作区 md 预览的图片内联 =====
   md 里的相对路径图片（剪藏 _images/… 等）浏览器会按页面 URL 解析成 404，
   且 <img src> 请求带不上 Authorization 头——渲染后逐张解析为工作区内路径候选，
   走 file-b64 取 base64 转 data URL 回填。外链 / data / blob 开头的不动。 */
function wsImgPathCandidates(mdPath, src) {
  try { src = decodeURIComponent(src); } catch (e) { /* 已是明文路径 */ }
  src = String(src || '').split('#')[0].split('?')[0].trim();
  if (!src || /^(https?:|data:|blob:)/i.test(src)) return [];
  const dir = String(mdPath || '').replace(/[^/]*$/, '');
  const norm = (p) => {
    const out = [];
    for (const seg of p.split('/')) {
      if (!seg || seg === '.') continue;
      if (seg === '..') { out.pop(); continue; }
      out.push(seg);
    }
    return out.join('/');
  };
  const cands = [];
  const push = (p) => { p = norm(p); if (p && !cands.includes(p)) cands.push(p); };
  const srcNorm = norm(src);
  if (srcNorm.startsWith('origin/')) push(srcNorm); /* ⓪ 链接本身已带 origin/ 前缀（工作区相对路径） */
  const base = norm(dir + src);          /* ① md 同目录（标准剪藏结构） */
  const firstSeg = srcNorm.split('/')[0] || '';
  const parentSeg = String(mdPath || '').replace(/\/[^/]*$/, '').split('/').pop() || '';
  if (firstSeg && firstSeg === parentSeg) {
    /* 链接以 md 所在目录名开头（INDEX.md 旧版索引的根相对形式）：同目录拼接是叠加误判，
       优先「剥目录名前缀的同目录」与「origin 根相对」，原样叠加降为兜底 */
    push(norm(dir + srcNorm.slice(firstSeg.length + 1)));
    push('origin/' + srcNorm);
  }
  push(base);
  if (dir.startsWith('origin/')) push(norm(dir.slice(7) + src));  /* ② md 在 origin/、图片在顶层 */
  if (!base.startsWith('origin/')) push('origin/' + base);        /* ③ 图片按 origin 归位上传 */
  push('origin/' + srcNorm);             /* ④ 链接相对 origin 根（兜底） */
  return cands;
}

async function wsInlineImages(container, mdPath, catId) {
  const MIME = { png: 'image/png', jpg: 'image/jpeg', jpeg: 'image/jpeg', gif: 'image/gif',
                 webp: 'image/webp', bmp: 'image/bmp', svg: 'image/svg+xml' };
  const imgs = Array.from(container.querySelectorAll('img'));
  for (const img of imgs) {
    const cands = wsImgPathCandidates(mdPath, img.getAttribute('src') || '');
    if (!cands.length) continue;
    const ext = (cands[0].match(/\.([a-z0-9]+)$/i) || [null, ''])[1].toLowerCase();
    const mime = MIME[ext];
    if (!mime) continue;
    let done = false, lastPath = cands[0];
    for (const wsPath of cands) {
      try {
        const r = await api('/api/cats/' + (catId || chat.catId) + '/chat/file-b64?path=' + encodeURIComponent(wsPath));
        img.src = 'data:' + mime + ';base64,' + (r.base64 || '');
        done = true;
        break;
      } catch (e) { lastPath = wsPath; }
    }
    if (!done) {
      const miss = document.createElement('span');
      miss.className = 'ws-img-miss';
      miss.textContent = '🖼 图片未同步：' + lastPath + '（剪藏时需把 _images 一起上传）';
      img.replaceWith(miss);
    }
  }
}

/* ===== 工作区 Office 文档预览：xlsx 表格 / docx 文档 =====
   xlsx 与 docx 都是 zip 包，用内置 JSZip 解出 XML 后在浏览器里直接渲染，无需后端转换服务。 */

/* 拉取 base64 → Uint8Array → JSZip 加载，按类型分发给渲染器 */
async function renderOfficePreview(path, ext) {
  const r = await api('/api/cats/' + chat.catId + '/chat/file-b64?path=' + encodeURIComponent(path));
  const bin = atob(r.base64 || '');
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  const zip = await JSZip.loadAsync(bytes);
  try {
    return ext === 'docx' ? await renderDocx(zip) : await renderXlsx(zip);
  } catch (e) {
    return '<div class="empty">解析失败：' + esc(e.message || e) + '（文件可能已损坏）</div>';
  }
}

/* docx → HTML：word/document.xml 的 w:p 段落文本（w:t/w:tab/w:br）+ w:tbl 表格 */
async function renderDocx(zip) {
  const f = zip.file('word/document.xml');
  if (!f) return '<div class="empty">不是有效的 .docx 文档（缺 word/document.xml）</div>';
  const doc = new DOMParser().parseFromString(await f.async('string'), 'application/xml');
  const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main';
  /* 按文档顺序拼接段落内容：w:t 文本、w:tab 缩进、w:br 换行 */
  const pHtml = (p) => {
    let s = '';
    const w = doc.createTreeWalker(p, NodeFilter.SHOW_ELEMENT);
    let n;
    while ((n = w.nextNode())) {
      const ln = n.localName;
      if (ln === 't') s += esc(n.textContent || '');
      else if (ln === 'tab') s += '&nbsp;&nbsp;';
      else if (ln === 'br') s += '<br>';
    }
    return s;
  };
  const body = doc.getElementsByTagNameNS(W, 'body')[0];
  if (!body) return '<div class="empty">文档内容为空</div>';
  let html = '';
  for (const node of body.children) {
    const ln = node.localName;
    if (ln === 'p') {
      const t = pHtml(node);
      html += '<p>' + (t.trim() ? t : '&nbsp;') + '</p>';
    } else if (ln === 'tbl') {
      let rows = '';
      for (const tr of node.getElementsByTagNameNS(W, 'tr')) {
        let cells = '';
        for (const tc of tr.getElementsByTagNameNS(W, 'tc')) {
          const t = [...tc.getElementsByTagNameNS(W, 'p')].map(pHtml).join('<br>');
          cells += '<td>' + t + '</td>';
        }
        rows += '<tr>' + cells + '</tr>';
      }
      html += '<table class="ws-docx-tbl">' + rows + '</table>';
    }
  }
  return html ? '<div class="ws-docx">' + html + '</div>' : '<div class="empty">文档内容为空</div>';
}

/* xlsx → HTML 表格：sharedStrings 共享字符串 + workbook 工作表名 + 每张 sheet 的单元格矩阵 */
async function renderXlsx(zip) {
  /* 1. 共享字符串表：t="s" 的单元格按下标引用这里的文本 */
  const sst = [];
  const ssFile = zip.file('xl/sharedStrings.xml');
  if (ssFile) {
    const d = new DOMParser().parseFromString(await ssFile.async('string'), 'application/xml');
    for (const si of d.getElementsByTagName('si')) {
      let s = '';
      for (const t of si.getElementsByTagName('t')) s += t.textContent || '';
      sst.push(s);
    }
  }
  /* 2. 工作表名与顺序（workbook.xml）+ rId → 实际文件路径（rels） */
  const wbFile = zip.file('xl/workbook.xml');
  if (!wbFile) return '<div class="empty">不是有效的 .xlsx 表格（缺 xl/workbook.xml）</div>';
  const wb = new DOMParser().parseFromString(await wbFile.async('string'), 'application/xml');
  const rels = {};
  const relsFile = zip.file('xl/_rels/workbook.xml.rels');
  if (relsFile) {
    const rd = new DOMParser().parseFromString(await relsFile.async('string'), 'application/xml');
    for (const rel of rd.getElementsByTagName('Relationship')) {
      rels[rel.getAttribute('Id')] = rel.getAttribute('Target');
    }
  }
  const sheets = wb.getElementsByTagName('sheet');
  if (!sheets.length) return '<div class="empty">表格里没有工作表</div>';
  const RNS = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  let html = '';
  for (const sh of sheets) {
    const name = sh.getAttribute('name') || 'Sheet';
    const rid = sh.getAttribute('r:id') || sh.getAttributeNS(RNS, 'id');
    let tPath = rels[rid] || ('worksheets/sheet' + (html.split('ws-sheet').length) + '.xml');
    if (tPath.startsWith('/')) tPath = tPath.slice(1);
    else if (!tPath.startsWith('xl/')) tPath = 'xl/' + tPath;
    const wsFile = zip.file(tPath);
    let rows = '';
    if (wsFile) {
      const wsd = new DOMParser().parseFromString(await wsFile.async('string'), 'application/xml');
      for (const row of wsd.getElementsByTagName('row')) {
        let cells = '';
        for (const c of row.getElementsByTagName('c')) {
          const t = c.getAttribute('t');
          let v = '';
          const vEl = c.getElementsByTagName('v')[0];
          if (t === 's' && vEl) v = sst[Number(vEl.textContent)] || '';
          else if (t === 'inlineStr') { for (const tt of c.getElementsByTagName('t')) v += tt.textContent || ''; }
          else if (vEl) v = vEl.textContent || '';
          cells += '<td>' + esc(v) + '</td>';
        }
        rows += '<tr>' + cells + '</tr>';
      }
    }
    html += '<section class="ws-sheet"><h4>' + esc(name) + '</h4>' +
      (rows ? '<div class="tbl-wrap"><table>' + rows + '</table></div>' : '<div class="empty">（空表）</div>') +
      '</section>';
  }
  return '<div class="ws-xlsx">' + html + '</div>';
}

function resetWsFile() {
  state.wsSelFile = null;
  const head = $('wsFileHead'), body = $('wsFileBody');
  if (head) head.innerHTML = '<span>🗂</span><span class="fp">从左侧选择一个文件查看内容</span>';
  if (body) body.innerHTML = '<div class="empty">左侧选择文件后，这里展示内容（Markdown 渲染）</div>';
}

/* 中栏预览头部的 ✕：关掉当前打开的页面，回到「请选择文件」空态。
   导航历史一并清空——留着的话关掉 A 再打开 B，按「后退」会退回已关闭的 A，不合直觉 */
function closeWsFile() {
  const n = wsNavFor();
  n.stack = [];
  n.idx = -1;
  resetWsFile();
  renderWsTree(); /* 清掉文件树里的选中高亮 */
}

/* 专属工作区两条竖直分隔条：树｜预览（#wsDividerTree 调 --ws-tree-w）、预览｜会话（#wsDivider 调 --ws-chat-w），
   两两独立调节；拖拽实时生效，松手记入 localStorage（存像素，恢复时 parseFloat 兼容旧表达式值） */
function initWsDivider() {
  const SIDE_L = 283;   /* 左栏（菜单+侧板）固定占位 */
  const MIN_TREE = 220, MIN_CHAT = 280, MIN_MID = 300; /* 三栏各自最小宽 */
  const bind = (id, cssVar, storeKey, isLeft) => {
    const dv = $(id);
    if (!dv || dv.dataset.bound) return;
    dv.dataset.bound = '1';
    let dragging = false;
    dv.addEventListener('mousedown', (e) => {
      dragging = true; e.preventDefault();
      dv.classList.add('on'); document.body.style.cursor = 'col-resize';
    });
    window.addEventListener('mousemove', (e) => {
      if (!dragging) return;
      const vw = document.documentElement.clientWidth;
      /* 左条：宽 = 鼠标x - 左栏起位；右条：宽 = 视口右缘 - 鼠标x。
         钳制 = 本栏最小宽 ~ 对侧两栏最小宽之和留下的上限，保证中栏预览始终可见 */
      const raw = isLeft ? e.clientX - SIDE_L : vw - e.clientX;
      const max = vw - SIDE_L - MIN_MID - (isLeft ? MIN_CHAT : MIN_TREE);
      const w = Math.min(Math.max(raw, isLeft ? MIN_TREE : MIN_CHAT), Math.max(320, max));
      document.body.style.setProperty(cssVar, w + 'px');
    });
    window.addEventListener('mouseup', () => {
      if (!dragging) return;
      dragging = false;
      dv.classList.remove('on'); document.body.style.cursor = '';
      localStorage.setItem(storeKey, getComputedStyle(document.body).getPropertyValue(cssVar).trim());
    });
  };
  bind('wsDividerTree', '--ws-tree-w', 'wsTreeW', true);
  bind('wsDivider', '--ws-chat-w', 'wsChatW', false);
}

