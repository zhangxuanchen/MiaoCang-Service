/* ================= 书架 ================= */

async function loadShelf() {
  state.shelf = await api('/api/shelf');
  renderShelf();
}

function renderShelf() {
  const tree = $('shelfTree');
  const s = state.shelf;
  if (!s) { tree.innerHTML = '<div class="empty">加载中…</div>'; return; }
  const cats = s.cats || [];
  if (!cats.length) {
    tree.innerHTML = `<div class="shelf-section"><span>🐱 我养的喵</span><button class="shelf-add" title="领养新猫" onclick="openCatModal()">＋</button></div><div class="empty">还没养猫，点 ＋ 领养一只吧</div>`;
    return;
  }

  /* 菜单栏即猫名单：每只真实养的猫一个入口，点进它的知识图谱；行尾 📥 是这只猫的收集箱待办数 */
  let html = `<div class="shelf-section"><span>🐱 我养的喵</span><button class="shelf-add" title="领养新猫" onclick="openCatModal()">＋</button></div>`;
  html += cats.map((c) => {
    const bookCount = (c.types || []).reduce((n, t) => n + ((t.books || []).length), 0);
    const typeCount = (c.types || []).length;
    const contentCount = (c.types || []).reduce((n, t) => n + (t.contentCount || 0), 0);
    const ib = c.inboxCount || 0;
    return `
    <div class="shelf-book ${state.view === 'graph' && state.graphCatId === c.id ? 'active' : ''} ${state.view === 'inbox' && state.inboxCatId === c.id ? 'active' : ''}" data-graph-cat="${c.id}" onclick="openGraph(${c.id})" title="${esc(c.name)}：${contentCount} 条内容 · ${bookCount} 本知识书 · ${typeCount} 个分类">
      <span class="dot" style="background:${esc(c.color || '#888')}"></span><span>${esc(c.icon || '🐱')} ${esc(c.name)}</span><span class="count">${contentCount}</span><button class="inbox-chip ${ib ? '' : 'zero'}" title="${esc(c.name)} 的收集箱：${ib} 条待裁决" onclick="event.stopPropagation();openInbox(${c.id})">📥${ib ? ib : ''}</button>
    </div>`;
  }).join('');

  /* 喵的整理：每只猫一个「xx 的整理」，把它的知识分类分层成一本本书 */
  html += `<div class="shelf-section"><span>📖 喵的整理</span></div>`;
  html += cats.map((c) => `
    <div class="shelf-book ${state.view === 'reading' && state.readingCatId === c.id ? 'active' : ''}" data-reading-cat="${c.id}" onclick="openReading(${c.id})" title="${esc(c.name)} 的整理：${c.readingCards || 0} 张理解卡 · ${c.readingBooks || 0} 本书">
      <span class="dot" style="background:${esc(c.color || '#888')}"></span><span>📖 ${esc(c.name)} 的整理</span><span class="rbadge rbadge-cards">${c.readingCards || 0} 卡</span><span class="rbadge rbadge-books">${c.readingBooks || 0} 书</span>
    </div>`).join('');

  /* 我的专题：每只猫一组学习主题（关键词检索聚合 + 手动收录） */
  html += `<div class="shelf-section"><span>📌 我的专题</span></div>`;
  html += cats.map((c) => (c.studyTopics || []).map((t) => `
    <div class="shelf-book ${state.view === 'study' && state.studyTopicId === t.id ? 'active' : ''}" onclick="openStudyTopic(${t.id})" title="${esc(t.name)}：专题（关键词聚合 + 手动收录）">
      <span class="dot" style="background:#0F6E56"></span><span>${esc(t.icon || '🧠')} ${esc(t.name)}</span>
    </div>`).join('')).join('');

  /* 专属工作区：会话区 + 记忆状态常驻主区域（原悬浮球/浮窗能力展开成常驻面板） */
  html += `
    <div class="shelf-book shelf-ws ${state.view === 'workspace' ? 'active' : ''}" data-ws="1" onclick="openWorkspace()" title="专属工作区：和喵协作处理它领地的文档，记忆状态常驻右侧">
      <span class="dot" style="background:#0F6E56"></span><span>💼 专属工作区</span>
    </div>`;

  /* 喵的日记：专属工作区 Agent 产生的行为日志（照 Memory-Observatory 三页面移植） */
  html += `<div class="shelf-section"><span>📓 喵的日记</span></div>`;
  html += `
    <div class="shelf-book ${state.view === 'diary-records' ? 'active' : ''}" data-diary="records" onclick="openDiary('records')" title="喵喵记录：喵与主人的每次交互，按轮记日记">
      <span class="dot" style="background:#0F6E56"></span><span>🐾 喵喵记录</span>
    </div>`;
  /* TODO 2026-09-22 临时隐藏，恢复时取消下面两行注释
    <div class="shelf-book ${state.view === 'diary-tokens' ? 'active' : ''}" data-diary="tokens" onclick="openDiary('tokens')" title="喵喵消耗：喵烧了多少 token，花在哪里">
      <span class="dot" style="background:#0F6E56"></span><span>🪙 喵喵消耗</span>
    </div>
    <div class="shelf-book ${state.view === 'diary-problems' ? 'active' : ''}" data-diary="problems" onclick="openDiary('problems')" title="喵喵问题：慢调用 / 循环 / 报错 / token 膨胀">
      <span class="dot" style="background:#0F6E56"></span><span>⚠️ 喵喵问题</span>
    </div>
  */

  tree.innerHTML = html;
  /* renderShelf 被频繁调用（切视图/管理操作后），会抹掉 setChatBusyUI toggle 上去的 .busy 类；
     setTimeout(0) 推迟到所有脚本加载完再同步一次忙碌态（shelf.js 先于 feedback.js 加载，直接调会 ReferenceError）。 */
  setTimeout(() => {
    if (typeof setChatBusyUI === 'function' && typeof chat !== 'undefined' && chat && chat.busy) {
      setChatBusyUI(true);
    }
  }, 0);
}

/* ================= 喵的整理 ================= */

/* 某只喵的整理书架：素材（喵喵整理 + 人与喵会话）被分类分层成一本本二级主题书 */
async function openReading(catId) {
  state.view = 'reading';
  state.readingCatId = catId;
  if (!state.readingTab || state.readingTab === 'books' || state.readingTab === 'cards') state.readingTab = 'wall';
  if (state.readingTimer) { clearInterval(state.readingTimer); state.readingTimer = null; }
  setNav();
  /* 点击瞬间就复位滚动并渲染占位骨架：旧视图立即让位，消除「接口等待后内容替换 + 跳顶」的高度跳动 */
  window.scrollTo(0, 0);
  const catName = (((state.shelf || {}).cats || []).find((c) => c.id === catId) || {}).name || '喵';
  $('main').innerHTML = `<div class="reading-loading">🐾 正在翻开「${esc(catName)}」的书架…</div>`;
  const [d, cards, learn] = await Promise.all([
    api('/api/cats/' + catId + '/reading').catch((e) => { toast(e.message, true); return null; }),
    api('/api/cats/' + catId + '/reading/cards').catch(() => []),
    api('/api/cats/' + catId + '/learn/today?limit=' + (state.learnLight ? 3 : 10)).catch(() => null),
  ]);
  if (!d) return;
  state.readingCards = cards;
  state.learn = learn;
  const books = d.books || [];
  state.readingBooks = books;
  $('main').innerHTML = `<div class="reading-page">
    <div class="view-head graph-head">
      <div class="graph-head-left">
        <button class="btn back" onclick="goHome()">← 返回书房</button>
        <button class="btn" onclick="openExportReading()" title="勾选卡片与书，排队导出为 Markdown">⬇ 导出</button>
      </div>
    </div>
    <div class="trace-strip">
      <div class="trace-summary" id="traceSummary" onclick="toggleTrace()">🐾 喵喵足迹 · 加载中…</div>
      <div class="trace-log" id="traceLog" style="display:none"></div>
    </div>
    <div class="run-live" id="runLive" style="display:none"></div>
    ` + renderReadingWall(catId, books, cards) + `
  </div>`;
  window.scrollTo(0, 0); /* 视图切换复位滚动：否则从滚动过的页面切过来，视口残留旧位置像整页下移 */
  startTrace(catId);
  bindTocObserver();
}

/* ================= 喵喵足迹：这只喵在干什么（整理中/休息中），人话事件流，5 秒轮询 ================= */
let traceTimer = null;
function startTrace(catId) {
  stopTrace();
  const tick = async () => {
    /* 足迹轮询只服务书房整理页条幅；工作区已改为文件树布局，不再展示整理状态 */
    if (state.view !== 'reading' || state.readingCatId !== catId) { stopTrace(); return; }
    const d = await api('/api/cats/' + catId + '/trace').catch(() => null);
    if (d) { state.lastTrace = d; renderTrace(d, catId); }
  };
  tick();
  traceTimer = setInterval(tick, 5000);
}
function stopTrace() { if (traceTimer) { clearInterval(traceTimer); traceTimer = null; } }
function renderTrace(d, catId) {
  const sum = $('traceSummary'), log = $('traceLog');
  if (!sum) return;
  const tok = d.tokens >= 10000 ? (d.tokens / 1000).toFixed(1) + 'k' : d.tokens;
  /* 「自动整理」开关控件已删：条幅只留手动触发按钮；文案同步去掉开关话术 */
  const detail = (d.detail || (d.on ? '等待下一次巡逻（每小时）' : '可随时手动整理')).replace('自动整理已关，可手动触发', '可随时手动整理');
  const ctl = `<span class="tr-ctl" onclick="event.stopPropagation()">
    <button class="tr-btn go" onclick="tidyNow(${catId})" ${d.working ? 'disabled' : ''} title="立即触发一轮整理">✨ 立即整理</button>
  </span>`;
  sum.innerHTML = d.working
    ? `<span class="tr-dot working"></span>整理中 · ${esc(detail)}${d.stageTotal ? `（${d.stageDone}/${d.stageTotal}）` : ''} · 已消耗 ${tok} tokens${ctl}`
    : `<span class="tr-dot idle"></span>休息中 · ${esc(detail)}${ctl}`;
  if (log.style.display !== 'none') {
    log.innerHTML = (d.events || []).map((e) =>
      `<div class="tr-row"><span class="tr-time">${e.time}</span><span class="tr-icon">${e.icon}</span><span class="tr-text">${esc(e.text)}</span></div>`
    ).join('') || '<div class="tr-row"><span class="tr-text">还没有足迹，喵巡逻发现变化后会有记录。</span></div>';
  }
}
async function toggleAutoTidy(catId, on) {
  try {
    const r = await api('/api/cats/' + catId + '/auto-tidy', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ on }) });
    /* 乐观更新：立即用后端返回值重绘按钮，不等 5 秒轮询 */
    if (state.lastTrace) {
      renderTrace({ ...state.lastTrace, on: r.on === true }, catId);
    }
    toast(on ? '✅ 已开启自动整理（每小时巡逻，无变化零消耗）' : '⏸ 已关闭自动整理，喵转入待命（可随时「立即整理」）');
    startTrace(catId);
  } catch (e) { toast(e.message, true); }
}
async function tidyNow(catId) {
  try {
    await api('/api/cats/' + catId + '/reading/generate?mode=auto', { method: 'POST' });
    toast('已通知喵开始整理，进度见足迹 🐾');
    startTrace(catId);
  } catch (e) { toast(e.message, true); }
}
function toggleTrace() {
  const log = $('traceLog');
  if (!log) return;
  log.style.display = log.style.display === 'none' ? 'block' : 'none';
  if (state.view === 'reading' && state.readingCatId) startTrace(state.readingCatId);
}

/* 书架结构体检已自动化：归纳完成后后端 autoTidy 自动执行合并/分裂（结果见任务完成条幅），前端不再展示建议条 */

function readingTab(t) { state.readingTab = t; state.learnDemo = null; openReading(state.readingCatId); }

