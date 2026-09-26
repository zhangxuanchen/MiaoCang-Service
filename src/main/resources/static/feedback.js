/* ===== 喵的屏幕操作反馈：Agent 工具动作 → 实时 UI（打开详情/刷新文件树/重载预览） ===== */

/* SSE 的 data 若被序列化成 JSON 字符串字面量（"{\"a\":1}"）先解掉外层引号再解析。
   后端发 toolresult 时传的是 Java String，Spring 按 JSON 编码 → 到前端手里是带引号、引号还被转义的
   字符串，直接 JSON.parse 拿到的还是 string；而 mock 模式传的是 Map（不带引号）。
   不解这一层，真机永远进不了协议分支（mock 看不出问题）。解析失败原样返回。 */
function unwrapSseJson(t) {
  if (typeof t !== 'string') return t;
  const s = t.trim();
  if (s[0] !== '"') return t;
  try {
    const v = JSON.parse(s);
    return typeof v === 'string' ? v : t;
  } catch (e) { return t; }
}

/* 工具呼叫事件 {"name":"…","args":"…"} → 摘要 */
function parseToolCall(t) {
  t = unwrapSseJson(t);
  if (typeof t !== 'string' || t[0] !== '{') return null;
  try {
    const o = JSON.parse(t);
    return o && o.name ? { name: o.name, args: String(o.args || '').slice(0, 120) } : null;
  } catch (e) { return null; }
}

/* 工具结果协议 {"type":"file"|"doc"|"error",…} → 动作对象；非 JSON 返回 null（走旧文本渲染） */
function parseWsAction(t) {
  t = unwrapSseJson(t);
  if (typeof t !== 'string' || t[0] !== '{') return null;
  try {
    const o = JSON.parse(t);
    return (o && (o.type === 'file' || o.type === 'doc' || o.type === 'error')) ? o : null;
  } catch (e) { return null; }
}

/* 喵把路径传成「整条书库路径」（data/library/admin/cat-1/origin/x.md）时，按文件树里的真实
   相对路径归位——不然中栏高亮、头部路径、编辑保存、配图相对解析全对不上（后端虽已容错，
   但前端拿着原串比对会永远不相等）。树里找不到就原样返回，交给后端归一。 */
function wsNormalizePath(raw) {
  const s = (raw || '').replace(/\\/g, '/').replace(/^\.\//, '');
  if (!s) return '';
  const list = state.wsFiles || [];
  if (list.some((f) => f.path === s)) return s;
  const hit = list.find((f) => f.path && s.endsWith('/' + f.path));
  return hit ? hit.path : s;
}

/* 执行喵的屏幕动作：file.write → 软刷新文件树并把被改文件顶到中栏；file.read → 中栏打开预览；
   doc.open → 弹详情并高亮；doc.update → 详情开着且同 id 时实时重拉 + toast（均限专属工作区） */
function handleWsAction(a) {
  if (a.type === 'error') { toast(a.message || '喵的操作失败了', true); return; }
  if (a.type === 'file') {
    if (a.action === 'write') {
      /* 写失败（ok:false）不刷新也不开预览，免得去打开一个并不存在的文件 */
      if (a.ok !== false && Number(state.wsCatId) === Number(chat.catId)) wsTreeSoftRefresh(a.path);
    } else if (a.action === 'read' && a.path) {
      if (document.body.classList.contains('ws-mode')) openWsFile(wsNormalizePath(a.path));
    }
  } else if (a.type === 'doc') {
    const title = a.title || '';
    if (a.action === 'open' && a.docId != null) {
      openDetail(a.docId);
      flashDetail();
      toast('🐱 喵为你打开了《' + title + '》');
    } else if (a.action === 'update' && a.docId != null) {
      const modal = $('detailModal');
      if (modal && modal.style.display !== 'none' && Number(state.detailId) === Number(a.docId)) {
        openDetail(a.docId);
        flashDetail();
      }
      toast('🐱 喵把《' + title + '》更新好了');
    }
  }
}

/* 工作区文件树软刷新：保留展开态与选中，不重置预览区；changedPath 所在目录自动展开，
   并把该文件顶到中栏——主人说「打开/修改 xxx」时要当场看到操作效果 */
async function wsTreeSoftRefresh(changedPath) {
  try {
    const list = await api('/api/cats/' + chat.catId + '/chat/files');
    state.wsFiles = list || [];
    wsTreeSig = JSON.stringify(state.wsFiles); /* 与轮询基线对齐，避免下个 tick 白重绘一次 */
    const path = wsNormalizePath(changedPath); /* 树已刷新，按真实相对路径归位 */
    (state.wsFiles || []).forEach((f) => {
      if (f.dir && path && (path + '/').startsWith(f.path + '/')) {
        if (state.wsOpenDirs) state.wsOpenDirs.add(f.path);
      }
    });
    renderWsTree();
    if (path && document.body.classList.contains('ws-mode')) openWsFile(path);
  } catch (e) { /* 树刷新失败不打扰会话 */ }
}

/* 详情弹窗被喵操作的呼吸高亮 */
function flashDetail() {
  document.body.classList.remove('cat-acting');
  void document.body.offsetWidth;
  document.body.classList.add('cat-acting');
  setTimeout(() => document.body.classList.remove('cat-acting'), 1100);
}

/* 图谱页轻量同步：常驻对话栏已移除，会话统一走悬浮窗——进图谱时把会话对象切到当前图谱猫 */
function syncGraphCatToChat() {
  const cats = (state.shelf && state.shelf.cats) || [];
  if (!cats.length) return;
  const target = cats.find((c) => c.id === state.graphCatId) || cats.find((c) => c.id === chat.catId) || cats[0];
  if (!target) return;
  const prev = chat.catId;
  chat.catId = Number(target.id);
  const dsel = $('chatCatSelect');
  if (dsel) {
    if (!dsel.options.length) dsel.innerHTML = cats.map((c) => `<option value="${c.id}">${esc(c.icon || '🐱')} ${esc(c.name)}</option>`).join('');
    dsel.value = String(target.id);
  }
  if (chat.catId !== prev) {
    /* 换了会话对象：悬浮窗消息区/工具区清空，与 onChatCatChange 同款清理 */
    ['chatMsgs', 'chatTools'].forEach((id) => { const el = $(id); if (el) { el.innerHTML = ''; } });
    const t = $('chatTools'); if (t) t.style.display = 'none';
    const inp = $('chatInput'); if (inp) inp.value = '';
  }
  loadChatHeader();
}

/* 会话悬浮窗动作行：🧩 技能 / 🐱 设置 / 🧠 记忆浮窗入口（静态元素，全局绑一次；工作区模式 🧠 由 CSS 隐藏） */
function initChatSubActs() {
  const sk = $('chatSkillsBtn'), cf = $('chatCfgBtn'), mb = $('chatMemoryBtn');
  if (sk) sk.onclick = () => openSkills(chat.catId);
  if (cf) cf.onclick = () => openAgentConfig(chat.catId);
  if (mb) mb.onclick = () => toggleMemPopup();
}

/* 记忆浮窗按钮与拖动（memPopup 为 body 层静态元素，全局绑一次；工作区常驻态收起/关闭按钮由 CSS 隐藏） */
function initMemPopupStatic() {
  const r = $('memRefreshBtn'), c = $('memCloseBtn'), m = $('memMinBtn'), cp = $('memCompressBtn');
  if (r) r.onclick = renderMemPopup;
  if (c) c.onclick = closeMemPopup;
  if (m) m.onclick = () => { const p = $('memPopup'); if (p) p.classList.toggle('min'); };
  if (cp) cp.onclick = memPopupCompress;
  initMemPopupDrag();
}

async function onChatCatChange(surface) {
  surface = surface || 'drawer';
  const sel = $(surface === 'graph' ? 'gcCatSelect' : 'chatCatSelect');
  if (!sel) return;
  chat.catId = Number(sel.value);
  /* 工作区里换过喵 → 记下来，下次点「💼 专属工作区」直接回到这只 */
  if (state.view === 'workspace') localStorage.setItem('wsLastCat', String(chat.catId));
  /* 两个下拉保持一致：换猫时抽屉与常驻栏同步 */
  const other = $(surface === 'graph' ? 'chatCatSelect' : 'gcCatSelect');
  if (other) other.value = sel.value;
  /* 两个消息区都清空，谁活跃用谁 */
  ['chatMsgs', 'gcMsgs'].forEach((id) => { const el = $(id); if (el) el.innerHTML = ''; });
  ['chatTools', 'gcTools'].forEach((id) => { const el = $(id); if (el) { el.innerHTML = ''; el.style.display = 'none'; } });
  ['chatInput', 'gcInput'].forEach((id) => { const el = $(id); if (el) el.value = ''; });
  /* 输入框提示：猫=自己的工作区 */
  const inp = $('chatInput');
  if (inp) inp.placeholder = '和这只猫聊聊它工作区里的文档…（Enter 发送，Shift+Enter 换行）';
  await loadChatHeader();
  restoreChatHistory(surface);
  /* 图谱浮窗开着的话跟随换猫刷新 */
  const mp = $('memPopup');
  if (mp && mp.style.display !== 'none') renderMemPopup();
  /* 工作区换猫：文件树/文件预览跟随切换到新喵的工作区 */
  if (state.view === 'workspace') {
    loadWsTree(chat.catId);
  }
}

/* 顶部工作区信息（抽屉与常驻栏一起更新，存在才写） */
async function loadChatHeader() {
  if (chat.catId == null) return;
  const wsIds = ['chatWsInfo', 'gcWsInfo'];
  try {
    const m = await api(`/api/cats/${chat.catId}/chat/memory?sessionId=${encodeURIComponent(chatSessionId(chat.catId))}`);
    wsIds.forEach((wsId) => {
      const ws = $(wsId);
      if (ws) ws.textContent = `📂 ${m.workspacePath}/`;
    });
    updateMemPctBadge(m.snapshot);
  } catch (e) {
    wsIds.forEach((wsId) => { const ws = $(wsId); if (ws) ws.textContent = '📂 工作区加载失败'; });
  }
}

/* 🧠 按钮上的记忆区占用比率：已用（要旨+原文）/ MEMORY 预算（图谱常驻栏与会话悬浮窗双写） */
function updateMemPctBadge(snapshot) {
  const s = snapshot || {};
  const memUsed = (Number(s.summaryChars) || 0) + (Number(s.recentChars) || 0);
  const pct = Math.max(0, Math.min(100, memUsed / MEM_BUDGET.memory * 100)).toFixed(1) + '%';
  ['gcMemPct', 'chatMemPct'].forEach((id) => { const el = $(id); if (el) el.textContent = pct; });
}

/* 会话恢复：消息区为空时回填服务端最近的对话轮次，并定位到最新一条 */
async function restoreChatHistory(surface) {
  if (chat.catId == null) return;
  surface = surface || chatSurface();
  const S = CHAT_SURFACES[surface];
  if (!S) return;
  const box = $(S.msgs);
  if (!box || box.children.length) return; /* 已有消息不重复回填 */
  try {
    const m = await api(`/api/cats/${chat.catId}/chat/memory?sessionId=${encodeURIComponent(chatSessionId(chat.catId))}`);
    if (box.children.length) return; /* 拉取期间用户已发消息 */
    const turns = (m.snapshot && m.snapshot.recent) || [];
    turns.forEach((t) => {
      if (t.user) chatAppendMsg('user', t.user, surface);
      if (t.assistant) chatAppendMsg('assistant', t.assistant, surface);
    });
    chatScroll(surface); /* 定位到最新对话 */
  } catch (e) { /* 恢复失败静默，不影响新对话 */ }
}

function chatAppendMsg(role, text, surface) {
  surface = surface || chatSurface();
  const wrap = document.createElement('div');
  wrap.className = 'chat-msg ' + role;
  const who = role === 'user' ? '🧑 你' : '🐱 猫猫';
  wrap.innerHTML = `<div class="chat-who">${who}</div><div class="chat-bubble"></div>`;
  wrap.querySelector('.chat-bubble').innerHTML = text
    ? (window.marked && window.marked.parse ? mdFinal(text) : mdLite(text))
    : '<span class="chat-typing"></span>';
  $(CHAT_SURFACES[surface].msgs).appendChild(wrap);
  mmdHydrate(wrap); /* 气泡里的 mermaid 代码块 → 流程图 */
  chatScroll(surface);
  return wrap;
}

function chatScroll(surface) {
  const m = $(CHAT_SURFACES[surface || chatSurface()].msgs);
  if (m) m.scrollTop = m.scrollHeight;
}

/* 会话气泡 Markdown 渲染（参照 Memory-Observatory 工作区会话框）：
   优先 marked v5（完整 GFM：标题/列表/表格/任务列表/自动链接/删除线/围栏代码），
   未就绪或解析异常时回退到自研轻量渲染（围栏代码/行内码/加粗），保证不裸吐原文。
   marked v5 默认不消毒：消息源为本机 LLM 与主人输入，与 Observatory 同策略 */
function mdLite(s) {
  if (window.marked && window.marked.parse) {
    try {
      return window.marked.parse(String(s || ''), { gfm: true, breaks: true, headerIds: false, mangle: false });
    } catch (e) { /* 落到轻量渲染 */ }
  }
  let h = esc(s);
  /* 围栏代码块先剥离占位，避免后续换行转 <br> 破坏 pre 内源码 */
  const blocks = [];
  h = h.replace(/```[a-zA-Z0-9_-]*\n?([\s\S]*?)```/g, (_, code) => {
    blocks.push(`<pre class="chat-code">${code}</pre>`);
    return '\u0001B' + (blocks.length - 1) + '\u0002';
  });
  h = h.replace(/`([^`\n]+)`/g, '<code class="chat-inline">$1</code>');
  h = h.replace(/\*\*([^*\n]+)\*\*/g, '<b>$1</b>');
  h = h.replace(/\n/g, '<br>');
  return h.replace(/\u0001B(\d+)\u0002/g, (_, i) => blocks[+i]);
}

/* 回复完成后的完整渲染（流式期间用 mdLite 保性能，结束后一次性升级）：
   marked v5 + highlight.js 语法高亮 + 定制 renderer：
   - 代码块：语言标签 + 复制按钮的代码框
   - 链接：仅放行 http(s)，一律新窗口打开（会话区点链接不丢页面状态）
   - 图片：懒加载 + 尺寸约束（样式层处理） */
/* marked v5 按 CommonMark 规范解析：链接/图片目的地含裸空格即整段解析失败退回纯文本
   （剪藏工具常产出含空格的图片路径，如「…堆到 50 万份后…/000_xxx.png」）
   ——渲染前把 ![]() 与 []() 里的空格转成 %20，已编码的不受影响 */
function mdFixUrlSpaces(s) {
  return String(s || '').replace(/(!?\[[^\]]*\]\()([^)\n]*)(\))/g, (m, head, url, tail) =>
    head + url.replace(/ /g, '%20') + tail);
}

function mdFinal(s, ctx) {
  if (!(window.marked && window.marked.parse)) return mdLite(s);
  try {
    const renderer = new window.marked.Renderer();
    renderer.code = function (code, info, escaped) {
      if (escaped) code = String(code).replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'");
      code = String(code).replace(/\n$/, '');
      const lang = ((info || '').trim().split(/\s+/)[0] || '').toLowerCase();
      let html = '';
      if (window.hljs) {
        try {
          html = lang && window.hljs.getLanguage(lang)
            ? window.hljs.highlight(code, { language: lang }).value
            : window.hljs.highlightAuto(code).value;
        } catch (ignored) { /* 高亮失败按纯文本 */ }
      }
      const label = esc(lang || 'text');
      /* mermaid 流程图：真渲染成图，源码折叠留在下面（「源码」按钮切出来看/复制）。
         3.3MB 的 mermaid 库按需懒加载，没遇到图就不下载 */
      if (lang === 'mermaid') {
        return `<div class="chat-codebox mmd-block"><div class="cc-head"><span class="cc-lang">mermaid</span>` +
          `<button class="cc-copy" type="button">复制</button><button class="mmd-toggle" type="button">源码</button></div>` +
          `<div class="mmd-canvas"><div class="mmd-loading">图表渲染中…</div></div>` +
          `<pre class="chat-code mmd-src"><code class="hljs">${html || esc(code)}</code></pre></div>`;
      }
      return `<div class="chat-codebox"><div class="cc-head"><span class="cc-lang">${label}</span>` +
        `<button class="cc-copy" type="button">复制</button></div>` +
        `<pre class="chat-code"><code class="hljs">${html || esc(code)}</code></pre></div>`;
    };
    renderer.link = function (href, title, text) {
      const h = String(href || '');
      if (/^https?:\/\//i.test(h)) {
        return `<a href="${esc(h)}" target="_blank" rel="noopener noreferrer" title="${esc(title || '外部链接 · 新标签打开')}">${text}</a>`;
      }
      /* 预览上下文：文内相对链接解析为工作区文件跳转（渲染后由预览容器委托处理 data-wslink） */
      if (ctx && ctx.resolve && !/^(#|mailto:)/i.test(h)) {
        const target = ctx.resolve(h);
        if (target) {
          return `<a href="javascript:void(0)" class="ws-link" data-wslink="${esc(target)}" title="打开 ${esc(target)}">${text}</a>`;
        }
      }
      return text;
    };
    return window.marked.parse(mdFixUrlSpaces(String(s || '')), { gfm: true, breaks: true, headerIds: false, mangle: false, renderer });
  } catch (e) { return mdLite(s); }
}

/* ==================== mermaid 流程图渲染 ====================
   mdFinal 把 ```mermaid 输出成 .mmd-block（图位 + 折叠源码），渲染是异步的：
   调用方在 innerHTML 落地后调 mmdHydrate(容器) 把图位填成 SVG。
   库按需懒加载（vendor/mermaid.min.js ≈3.6MB），失败/语法错时回落为源码不炸页面。 */
let mmdLoading = null;
let mmdSeq = 0;

/* mermaid 的 gitGraph 分支名只认 ASCII 标识符：写成中文（`branch 收编`）整个图直接 lex 报错、
   一张都出不来，加引号就没事（`branch "收编"`）。猫写文档和主人手敲都容易漏，
   所以渲染前补一层。规则收得很紧：整行只有「关键字 + 单个名字」且名字含非 ASCII 才动手，
   带空格（如 `merge x tag:"v1"`）或已加引号的一律不碰。 */
function mmdNormalize(code) {
  if (!/^\s*gitGraph\b/m.test(code)) return code;
  return code.replace(/^([ \t]*)(branch|checkout|merge)([ \t]+)([^\s"'][^\s]*)[ \t]*$/gm,
    (line, ind, kw, sp, name) => (/[^\x00-\x7F]/.test(name) ? ind + kw + sp + '"' + name + '"' : line));
}

function ensureMermaid() {
  if (window.mermaid) return Promise.resolve(window.mermaid);
  if (!mmdLoading) {
    mmdLoading = new Promise((resolve, reject) => {
      const s = document.createElement('script');
      s.src = '/vendor/mermaid.min.js';
      s.onload = () => {
        try {
          window.mermaid.initialize({
            startOnLoad: false,
            securityLevel: 'strict', /* 图内不执行脚本/不透传 HTML */
            theme: 'base',
            fontFamily: '"PingFang SC","Hiragino Sans GB","Microsoft YaHei",Helvetica,Arial,sans-serif',
            themeVariables: {
              primaryColor: '#EAF6F1', primaryBorderColor: '#0F6E56', primaryTextColor: '#1b1a17',
              lineColor: '#1b1a17', secondaryColor: '#F2F1EE', tertiaryColor: '#ffffff', fontSize: '13px',
            },
          });
          resolve(window.mermaid);
        } catch (e) { reject(e); }
      };
      s.onerror = () => { mmdLoading = null; reject(new Error('mermaid 库加载失败')); };
      document.head.appendChild(s);
    });
  }
  return mmdLoading;
}

async function mmdHydrate(root) {
  const blocks = root && root.querySelectorAll ? root.querySelectorAll('.mmd-block:not(.mmd-done)') : [];
  if (!blocks.length) return;
  let mermaid = null;
  try { mermaid = await ensureMermaid(); } catch (e) { /* 库挂了 → 全部直接摊源码 */ }
  for (const b of blocks) {
    b.classList.add('mmd-done');
    const pre = b.querySelector('.mmd-src code');
    const src = pre ? pre.textContent : '';
    const canvas = b.querySelector('.mmd-canvas');
    if (!mermaid) {
      b.classList.add('mmd-open', 'mmd-failed');
      canvas.innerHTML = '<div class="mmd-err">⚠️ mermaid 库没加载出来，先看源码</div>';
      continue;
    }
    try {
      const { svg } = await mermaid.render('mmd-' + (++mmdSeq), mmdNormalize(src));
      canvas.innerHTML = svg;
    } catch (e) {
      /* 流程图语法错：把源码摊开 + 报错原因，避免只留一个空框 */
      b.classList.add('mmd-open', 'mmd-failed');
      canvas.innerHTML = '<div class="mmd-err">⚠️ mermaid 解析失败：' + esc((e && e.message) || e) + '</div>';
    }
  }
}

/* 「源码」按钮：图 ⇄ 源码切换 */
document.addEventListener('click', (ev) => {
  const btn = ev.target && ev.target.closest ? ev.target.closest('.mmd-toggle') : null;
  if (!btn) return;
  const box = btn.closest('.mmd-block');
  if (!box) return;
  box.classList.toggle('mmd-open');
});

/* 代码块「复制」按钮：事件委托（气泡会随流式重渲染，挂在容器上最稳） */
document.addEventListener('click', (ev) => {
  const btn = ev.target && ev.target.closest ? ev.target.closest('.cc-copy') : null;
  if (!btn) return;
  const box = btn.closest('.chat-codebox');
  const code = box ? box.querySelector('pre code') : null;
  if (!code) return;
  const done = () => { btn.textContent = '已复制'; setTimeout(() => { btn.textContent = '复制'; }, 1600); };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(code.textContent).then(done).catch(() => {});
  } else {
    const ta = document.createElement('textarea');
    ta.value = code.textContent; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); done(); } catch (ignored) { /* 忽略 */ }
    ta.remove();
  }
});

/* 工具足迹容器初始化：清空并装上可折叠摘要条（点击展开/缩回） */
function initChatTools(tools) {
  if (!tools) return;
  tools.classList.remove('expanded');
  tools.innerHTML = '';
  tools.style.display = 'none';
  const bar = document.createElement('div');
  bar.className = 'ct-bar';
  bar.innerHTML = `<span>🔧 工具足迹</span><span class="ct-count">0</span><span class="ct-latest"></span><span class="ct-arrow">▶</span>`;
  bar.addEventListener('click', () => {
    tools.classList.toggle('expanded');
    const lines = tools.querySelector('.ct-lines');
    if (lines && tools.classList.contains('expanded')) lines.scrollTop = lines.scrollHeight;
  });
  tools.appendChild(bar);
}

/* 主人当前所在页面的上下文：随每条消息发给会话区，喵据此结合用户正在看的页面答题 */
function currentPageContext() {
  const v = state.view || 'welcome';
  const h2 = ((document.querySelector('main h2') || {}).textContent || '').trim();
  const cats = (state.shelf && state.shelf.cats) || [];
  if (v === 'graph') {
    if (!state.graphCatId) return '知识图谱总览页（所有喵的宇宙图：猫→知识分类）';
    const c = cats.find((x) => x.id === state.graphCatId);
    return '知识图谱页 ·「' + (c ? c.name : '猫') + '」的图谱（画布层级：猫→分类→内容，右侧📑分类目录）';
  }
  if (v === 'reading') {
    const c = cats.find((x) => x.id === state.readingCatId);
    return '喵的整理页 ·「' + (c ? c.name : '') + '」的整理（书架+理解卡片墙+📑卡片目录，喵逐篇精读自动归纳）';
  }
  if (v === 'book') return '书详情页' + (h2 ? '「' + h2.slice(0, 30) + '」' : '') + '（书的目录与内容列表）';
  if (v === 'taxonomy') return '分类维护页（类型→书→目录的维护树）';
  if (v === 'study') return '我的专题页' + (h2 ? '「' + h2.slice(0, 30) + '」' : '') + '（学习主题聚合）';
  if (v === 'search') return '搜索结果页（关键词：' + (state.query || '') + '）';
  return '书房主页（书库总览）';
}

async function sendChat(surface) {
  surface = surface || chatSurface();
  /* busy 时发送按钮 = ⏹ 停止生成（最小化到头像后工作继续，停止走这里） */
  if (chat.busy) { if (chat.controller) chat.controller.abort(); return; }
  if (chat.catId == null) return;
  const S = CHAT_SURFACES[surface];
  const input = $(S.input);
  const text = input.value.trim();
  if (!text) return;
  /* 全库搜索分支：/关键词 或「搜 关键词」→ 搜当前用户全部喵的领地，结果以气泡插入会话流 */
  const sq = parseSearchQuery(text);
  if (sq) { input.value = ''; chatSearch(surface, sq); return; }
  input.value = '';
  /* 本轮是从哪起来的：专属工作区（body.ws-mode）还是猫的悬浮会话框——侧栏忙碌点据此区分，
     工作区会话只亮「💼 专属工作区」，不亮「🐱 我养的喵」里那只猫的行 */
  chat.fromWs = document.body.classList.contains('ws-mode');
  chat.busy = true;
  setChatBusyUI(true);
  chatAppendMsg('user', text, surface);
  const bubble = chatAppendMsg('assistant', '', surface);
  const bubbleBody = bubble.querySelector('.chat-bubble');
  /* 本轮状态行：独立于正文内容，跨静默期（tool call / 思考）可见到底在工作还是真停了 */
  const statusLine = document.createElement('div');
  statusLine.className = 'chat-turn-status';
  statusLine.innerHTML = '<span class="cts-dot"></span><span class="cts-text">思考中…</span>';
  bubbleBody.parentNode.insertBefore(statusLine, bubbleBody.nextSibling);
  const setStatus = (html) => { statusLine.innerHTML = html; };
  const tools = $(S.tools);
  initChatTools(tools);
  let acc = '';
  /* 流式期间直接用 mdFinal（marked v5 完整 GFM），heading/列表/表格不再裸显示；
     以前守 mdLite 担心性能，但 marked v5 对 10KB 文本 <1ms，没瓶颈 */
  const upd = () => { bubbleBody.innerHTML = (window.marked && window.marked.parse ? mdFinal(acc) : mdLite(acc)) || '<span class="chat-typing"></span>'; chatScroll(surface); };
  /* 工具足迹：默认收起为一条底部摘要条（最新动作 + 条数），点击展开/缩回完整日志 */
  const addToolLine = (cls, txt, act) => {
    tools.style.display = 'block';
    let lines = tools.querySelector('.ct-lines');
    if (!lines) {
      lines = document.createElement('div');
      lines.className = 'ct-lines';
      tools.appendChild(lines);
    }
    const l = document.createElement('div');
    /* 协议串字段并不齐：open_file 的 read 只有 type/action/path、open_doc 只有 type/action/docId/title，
       都没有 message —— 直接取 act.message 会让卡片空白，且下面 .replace 会 TypeError 冒泡到本轮
       catch，正文气泡里只留一行报错、预览根本打不开。这里统一兜一个可读文案 */
    const actText = act
      ? String(act.message || (act.type === 'doc' ? '《' + (act.title || '内容') + '》'
        : (act.path ? '已打开 ' + act.path : '喵执行了一个动作')))
      : '';
    if (act) {
      /* 喵的屏幕操作反馈卡片：可点击直达（文件→预览，内容→详情） */
      l.className = 'chat-tool-line ws-act';
      const ok = act.ok !== false;
      const ico = act.type === 'doc' ? '📖' : (act.action === 'write' ? '✍️' : '📄');
      const jump = act.type === 'doc' ? '查看详情' : '打开预览';
      l.innerHTML = `<div class="ws-act-card${ok ? '' : ' err'}"${ok ? ` onclick="${act.type === 'doc' ? `openDetail(${act.docId})` : `openWsFile('${esc(act.path || '')}')`}"` : ''}>` +
        `<span class="a-ico">${ok ? ico : '⚠️'}</span><span class="a-txt">${esc(actText)}</span>` +
        (ok ? `<span class="a-jump">↗ ${jump}</span>` : '') + `</div>`;
    } else if (cls === 'result') {
      l.className = 'chat-tool-line ' + cls;
      /* 工具结果常是 Markdown（如「## ✅本轮清理结果」）：走 marked 完整渲染，不再裸吐原文 */
      l.classList.add('md-body');
      l.innerHTML = mdFinal(txt);
      mmdHydrate(l); /* 工具结果里也可能贴 mermaid */
    } else {
      l.className = 'chat-tool-line ' + cls;
      l.textContent = txt;
    }
    lines.appendChild(l);
    const bar = tools.querySelector('.ct-bar');
    if (bar) {
      const n = lines.children.length;
      bar.querySelector('.ct-count').textContent = n;
      /* 摘要条只留纯文本观感：剥掉标题井号/加粗星号等装饰符（actText 已兜底，不会是 undefined） */
      const plain = String(act ? actText : txt || '').replace(/^#{1,6}\s+/gm, '').replace(/\*\*([^*]*)\*\*/g, '$1').replace(/[`>]/g, '');
      bar.querySelector('.ct-latest').textContent = plain.length > 90 ? plain.slice(0, 90) + '…' : plain;
      bar.title = plain;
    }
    if (tools.classList.contains('expanded')) { lines.scrollTop = lines.scrollHeight; }
    chatScroll(surface);
  };

  chat.controller = new AbortController();
  /* 静默超时兜底：后端 SSE 可能卡住（崩了/工具执行挂起）、既不发 done 也不发 error，
     TCP 连接还挂着不释放 → reader.read() 永远阻塞 → 状态行永远停在「思考中…」脉冲、
     chat.busy 永远 true、发送按钮永远是停止态。
     每收到一个 SSE event 就重置 watchdog；90 秒没动静就主动 abort 走 AbortError 清理 */
  const SILENT_TIMEOUT_MS = 90 * 1000;
  let watchdogTimer = null;
  let forcedTimeout = false;
  let settled = false; /* 本轮状态行是否已落到终态（done / error）——流没发 done 就断时靠它兜底 */
  const armWatchdog = () => {
    if (watchdogTimer) clearTimeout(watchdogTimer);
    watchdogTimer = setTimeout(() => {
      console.warn('[chat] SSE silent for', SILENT_TIMEOUT_MS, 'ms, forcing abort');
      forcedTimeout = true;
      chat.controller.abort();
    }, SILENT_TIMEOUT_MS);
  };
  /* 收尾静默期：正文吐完后，后端还要跑「长期记忆抽取 / 记忆合并」这类收尾活儿，
     期间一个 event 都不发（实测 20~140s），状态行却一直挂着「思考中…」——明明是收尾不是在想。
     静默超过 4 秒且最后一个是正文 token，就把状态行换成诚实的「收尾中」。
     ping 心跳不重置它（15s 一次会跟计时打架）；正文 token 再来则恢复「正在输出…」。 */
  const QUIET_MS = 4000;
  let quietTimer = null;
  let quietFired = false;
  const armQuiet = () => {
    if (quietTimer) clearTimeout(quietTimer);
    quietTimer = setTimeout(() => {
      quietTimer = null;
      if (settled || acc === '') return;
      quietFired = true;
      setStatus('<span class="cts-dot"></span><span class="cts-text">🐱 正文已出完，正在收尾（整理长期记忆 / 登记 INDEX）…</span>');
    }, QUIET_MS);
  };
  const clearQuiet = () => {
    if (quietTimer) clearTimeout(quietTimer);
    quietTimer = null;
    quietFired = false;
  };
  try {
    const res = await fetch(`/api/cats/${chat.catId}/chat/stream`, {
      method: 'POST',
      headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ sessionId: chatSessionId(chat.catId), message: text, pageContext: currentPageContext() }),
      signal: chat.controller.signal,
    });
    if (!res.ok || !res.body) {
      const err = await res.json().catch(() => ({}));
      throw new Error(err.error || ('请求失败 ' + res.status));
    }
    armWatchdog(); /* fetch 成功后开始计时 */
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        let ev = '';
        const data = [];
        for (const line of block.split('\n')) {
          if (line.startsWith(':')) continue;              /* SSE 注释 */
          if (line.startsWith('event:')) ev = line.slice(6).trim();
          else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        }
        const payload = data.join('\n');
        /* 每个 event 到达都重置 watchdog（done 例外，它会 break 循环直接走 finally） */
        if (ev !== 'done') armWatchdog();
        if (ev === 'token') {
          /* 正文又来了：收尾静默期结束，状态行恢复「正在输出…」 */
          if (acc === '' || quietFired) {
            quietFired = false;
            setStatus('<span class="cts-dot live"></span><span class="cts-text">正在输出…</span>');
          }
          acc += payload; upd();
          armQuiet();
        }
        else if (ev === 'tool') {
          /* 喵在想工具调用（思考中 + 工具名）→ 保留 busy 状态，工具足迹里会展示细节 */
          const t = parseToolCall(payload);
          clearQuiet();
          setStatus('<span class="cts-dot"></span><span class="cts-text">思考中 · ' + (t ? t.name : 'tool') + '()</span>');
          addToolLine('call', t ? `🔧 ${t.name}${t.args ? ' · ' + t.args : ''}` : `🔧 ${payload}`);
        }
        else if (ev === 'toolresult') {
          /* 工具执行完了 → 回到思考中（喵要消化结果再输出） */
          const a = parseWsAction(payload);
          clearQuiet();
          setStatus('<span class="cts-dot"></span><span class="cts-text">思考中…</span>');
          if (a) { addToolLine('result', '', a); handleWsAction(a); }
          else addToolLine('result', payload);
        }
        else if (ev === 'error') {
          acc += (acc ? '\n\n' : '') + '⚠️ ' + payload; upd();
          /* 后端出错/超时走的是 error + complete（不发 done）：状态行必须在这里就收口，
             否则气泡上的脉冲会一直停在「思考中…」 */
          settled = true;
          clearQuiet();
          setStatus('<span class="cts-dot ok"></span><span class="cts-text muted">⚠️ 出错了</span>');
        }
        else if (ev === 'done') {
          if (watchdogTimer) clearTimeout(watchdogTimer);
          settled = true;
          clearQuiet();
          setStatus('<span class="cts-dot ok"></span><span class="cts-text ok">✓ 本轮完成</span>');
          try { const d = JSON.parse(payload); if (d.mock) loadChatHeader(); } catch (ignored) { /* 忽略 */ }
        }
      }
    }
    /* 服务端没发 done 就关了流（error/timeout/连接被断/代理掐线）：补一个终止态 */
    if (!settled) setStatus('<span class="cts-dot ok"></span><span class="cts-text muted">⏹ 已结束</span>');
    if (!acc) { bubbleBody.innerHTML = '<span class="chat-typing"></span>'; }
    else { bubbleBody.innerHTML = mdFinal(acc); mmdHydrate(bubbleBody); chatScroll(surface); }
  } catch (e) {
    if (watchdogTimer) clearTimeout(watchdogTimer);
    clearQuiet();
    if (e.name === 'AbortError') {
      acc += acc ? (forcedTimeout ? '\n\n（连接超时，已自动断开）' : '\n\n（已停止）') : (forcedTimeout ? '（连接超时，已自动断开）' : '（已停止）');
      upd();
      setStatus(forcedTimeout
        ? '<span class="cts-dot ok"></span><span class="cts-text muted">⏰ 连接超时，已断开</span>'
        : '<span class="cts-dot ok"></span><span class="cts-text muted">⏹ 已停止</span>');
    } else {
      acc += (acc ? '\n\n' : '') + '⚠️ ' + e.message;
      upd();
      setStatus('<span class="cts-dot ok"></span><span class="cts-text muted">⚠️ 出错了</span>');
      toast(e.message, true);
    }
    /* 上面两次 upd() 重建了气泡 DOM，流程图要重新填一次（流式期间不重填，避免每 chunk 渲染一遍） */
    mmdHydrate(bubbleBody);
  } finally {
    if (watchdogTimer) clearTimeout(watchdogTimer);
    clearQuiet();
    chat.busy = false;
    setChatBusyUI(false);
    chat.controller = null;
  }
}

/* 会话忙碌态 UI：发送按钮变 ⏹ 停止（两个 surface 同步）、悬浮球亮起脉冲光环（最小化后也能看出喵在干活）、
   侧栏对应猫/专属工作区按钮亮起脉冲点（跨视图都能知道哪只猫在忙）。 */
function setChatBusyUI(on) {
  ['drawer', 'graph'].forEach((s) => {
    const b = $(CHAT_SURFACES[s].send);
    if (b) { b.disabled = false; b.classList.toggle('stop', on); b.textContent = on ? '⏹ 停止' : '发送'; }
  });
  const orb = $('wikiOrb');
  if (orb) orb.classList.toggle('busy', on);
  /* 侧栏忙碌徽标：来源决定亮哪一处——工作区会话亮「💼 专属工作区」，悬浮会话框亮「🐱 我养的喵」里那只猫，
     都是跨视图可见（离开当前页也还看得见在忙） */
  const fromWs = !!chat.fromWs;
  document.querySelectorAll('.shelf-book[data-graph-cat], .shelf-book[data-reading-cat]').forEach((el) => {
    const id = Number(el.dataset.graphCat || el.dataset.readingCat);
    el.classList.toggle('busy', on && !fromWs && id === chat.catId);
  });
  const ws = document.querySelector('.shelf-book.shelf-ws');
  if (ws) ws.classList.toggle('busy', on && fromWs);
}

/* ---- 记忆面板 ---- */

function fmtChars(n) {
  n = Number(n) || 0;
  if (n < 1000) return n + ' 字';
  if (n < 10000) return (n / 1000).toFixed(1) + 'k 字';
  return Math.round(n / 1000) + 'k 字';
}

async function refreshChatMemory() {
  /* 「🧠 记忆」tab 已下线（功能暂时不要）：保留函数占位避免旧引用炸掉 */
}

async function markChatCompress() {
  /* 记忆 tab 已下线 */
}

/* ---- 图谱会话栏「记忆状态」浮窗：native AI 记忆仪表盘，就地在会话栏内展开 ---- */

/* 上下文五区预算（字）：与设计稿一致，合计 1,000,000 */
const MEM_BUDGET = { total: 1000000, system: 100000, task: 50000, memory: 150000, tool: 400000, free: 300000 };

const nfNum = (n) => (Number(n) || 0).toLocaleString('en-US');

function toggleMemPopup() {
  const p = $('memPopup');
  if (!p) return;
  if (state.view === 'workspace') {
    /* 工作区：记忆区块内嵌在右栏里（无悬浮遮挡），🧠 按钮只做开合 */
    const open = !document.body.classList.contains('mem-open');
    document.body.classList.toggle('mem-open', open);
    if (open) renderMemPopup();
    return;
  }
  const show = p.style.display === 'none';
  p.style.display = show ? 'flex' : 'none';
  p.classList.remove('min');
  document.body.classList.toggle('mem-open', show); /* 工作区：同步聊天区右侧留白（展开 392px / 收起 24px） */
  if (show) {
    anchorMemPopup(p);
    renderMemPopup();
  }
}

/* 打开时把浮窗锚定到 🧠 按钮附近（悬浮窗开着用悬浮窗的按钮，否则用图谱常驻栏按钮；优先左侧，放不下则右侧） */
function anchorMemPopup(p) {
  const chatOpen = $('chatDrawer') && $('chatDrawer').classList.contains('open');
  const btn = (chatOpen && $('chatMemoryBtn')) ? $('chatMemoryBtn') : $('gcMemoryBtn');
  if (!btn) return;
  const b = btn.getBoundingClientRect();
  const r = p.getBoundingClientRect();
  const vw = document.documentElement.clientWidth, vh = document.documentElement.clientHeight;
  let left = b.left - r.width - 8;
  if (left < 8) left = b.right + 8; /* 左侧放不下改右侧 */
  if (left + r.width > vw - 8) left = vw - 8 - r.width;
  let top = b.top;
  if (top + r.height > vh - 8) top = Math.max(8, vh - 8 - r.height);
  p.style.left = Math.max(8, left) + 'px';
  p.style.top = Math.max(8, top) + 'px';
}

/* 内容渲染后把浮窗校正回视口内（锚定时按"加载中"高度定位，内容长高后底部可能溢出） */
function clampMemPopup() {
  const p = $('memPopup');
  if (!p || p.style.display === 'none') return;
  const r = p.getBoundingClientRect();
  const vw = document.documentElement.clientWidth, vh = document.documentElement.clientHeight;
  const maxL = Math.max(8, vw - 8 - r.width), maxT = Math.max(8, vh - 8 - r.height);
  p.style.left = Math.min(Math.max(8, r.left), maxL) + 'px';
  p.style.top = Math.min(Math.max(8, r.top), maxT) + 'px';
}

function closeMemPopup() {
  const p = $('memPopup');
  if (p) { p.style.display = 'none'; p.classList.remove('min'); }
}

/* 记忆浮窗拖动：fixed 定位，按住标题栏可全屏拖拽，限制不出视口（静态元素，只绑一次） */
function initMemPopupDrag() {
  const p = $('memPopup');
  const head = p && p.querySelector('.mem-popup-head');
  if (!p || !head || head.dataset.dragInit) return;
  head.dataset.dragInit = '1';
  head.addEventListener('mousedown', (e) => {
    if (document.body.classList.contains('ws-mode')) return; /* 工作区常驻态：CSS 固定布局，不拖拽 */
    if (e.target.closest('button')) return; /* 头部按钮不触发拖动 */
    const rect = p.getBoundingClientRect();
    const offX = e.clientX - rect.left, offY = e.clientY - rect.top;
    p.style.width = rect.width + 'px'; /* 固定宽度，避免拖动瞬间跳变 */
    head.style.cursor = 'grabbing';
    const move = (ev) => {
      const vw = document.documentElement.clientWidth, vh = document.documentElement.clientHeight;
      const x = Math.min(Math.max(8 - rect.width, ev.clientX - offX), vw - 8); /* 至少留 8px 标题栏在屏内 */
      const y = Math.min(Math.max(0, ev.clientY - offY), vh - head.offsetHeight); /* 标题栏保持完整可见 */
      p.style.left = x + 'px'; p.style.top = y + 'px';
    };
    const up = () => {
      head.style.cursor = 'grab';
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
    };
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
    e.preventDefault();
  });
}

async function renderMemPopup() {
  const body = $('memPopupBody'), btn = $('memCompressBtn');
  if (!body || chat.catId == null) return;
  body.innerHTML = '<div class="empty">加载中…</div>';
  try {
    const m = await api(`/api/cats/${chat.catId}/chat/memory?sessionId=${encodeURIComponent(chatSessionId(chat.catId))}`);
    if (btn) {
      btn.textContent = m.compressMarked ? '已标记（下轮生效）' : '标记压缩';
      btn.disabled = !!m.compressMarked;
    }
    const s = m.snapshot || {};
    const summaryChars = Number(s.summaryChars) || 0, recentChars = Number(s.recentChars) || 0;
    const toolChars = Number(s.toolChars) || 0, estChars = Number(s.estChars) || 0;
    const turns = Number(s.totalTurns) || 0, recentTurns = Number(s.recentTurns) || 0;
    /* MEMORY 占用 = 要旨 + 原文（折叠已并入要旨不独立计字） */
    const memUsed = summaryChars + recentChars;
    updateMemPctBadge(s);
    /* FREE 正常为 0，只有预估上下文超出总预算才被挤占 */
    const freeUsed = Math.max(0, estChars - MEM_BUDGET.total);
    const gistCount = summaryChars > 0 ? 1 : 0;
    const trail = m.foldTrail || [];
    const pctOf = (u, b) => (u / b * 100).toFixed(1) + '%';
    /* 占用为 0 时空轨道；有占用但占比过小时保底 2% 宽度，避免肉眼不可见 */
    const barW = (u, b) => (u <= 0 ? 0 : Math.max(2, Math.min(100, u / b * 100))).toFixed(2);
    const bar = (u, b) => `<div class="mem-bar"><i class="${u / b > 0.8 ? 'warn' : ''}" style="width:${barW(u, b)}%"></i></div>`;
    const zone = (name, label, used, budget) => `
      <div class="mem-zone">
        <div class="mem-zone-row"><b>${name}</b><span>${label} ${nfNum(used)}/${nfNum(budget)} 字 · ${pctOf(used, budget)}</span></div>
        ${bar(used, budget)}
      </div>`;

    let html = `
      <div class="mem-usage">
        <div class="mem-usage-top"><span>记忆占用</span><b>${pctOf(memUsed, MEM_BUDGET.memory)}</b></div>
        <div class="progress"><div class="progress-bar" style="width:${barW(memUsed, MEM_BUDGET.memory)}%"></div></div>
      </div>
      <div class="mem-sumline">会话 ${turns} 轮 · 已用 ${nfNum(estChars)} 字 · MEMORY 预算 ${nfNum(MEM_BUDGET.memory)} 字 (15%×1,000,000)</div>
      <div class="mem-sec-title">五区占用</div>
      ${zone('SYSTEM', '占用', Number(m.systemChars) || 0, MEM_BUDGET.system)}
      ${zone('TASK', '占用', Number(m.taskChars) || 0, MEM_BUDGET.task)}
      ${zone('MEMORY', '占用', memUsed, MEM_BUDGET.memory)}
      ${zone('TOOL', '占用', toolChars, MEM_BUDGET.tool)}
      ${zone('FREE', '被挤占', freeUsed, MEM_BUDGET.free)}
      <div class="mem-note">SYSTEM = persona + 工作区说明（每轮固定注入）；TASK = 会话记忆注入块；TOOL 为会话累计工具输出字符；FREE 为预留空闲区，正常应为 0，&gt;0 表示上下文即将超限</div>
      <div class="mem-mark">MEMORY 区展开 · 要旨+原文=MEMORY 占用（折叠已并入要旨）</div>
      <div class="mem-three-title">记忆三层</div>
      ${zone('早期要旨', '占用', summaryChars, MEM_BUDGET.memory)}
      <div class="mem-zone">
        <div class="mem-zone-row"><b>中段折叠</b><span>${s.folded ? '已折叠' : '未折叠'}</span></div>
        <div class="mem-fold-box">${trail.length
          ? esc(trail.map((t) => t.snippet || '').filter(Boolean).join('\n'))
          : (s.folded ? esc(s.summary || '折叠内容已并入要旨') : '○ 尚无折叠，全文保留')}</div>
      </div>
      ${zone('最近原文', `占用 · ${recentTurns} 条`, recentChars, MEM_BUDGET.memory)}
      <div class="mem-formula">要旨(${gistCount}) + 原文(${recentTurns}) = MEMORY 已用 ${nfNum(memUsed)} 字 · 折叠并入要旨不独立计字</div>
      <div class="mem-sec-title">压缩日志</div>`;
    if ((m.compressionLog || []).length) {
      html += m.compressionLog.map((r) => `<div class="mem-log">${new Date(r.ts).toLocaleString()} · ${fmtChars(r.memCharsBefore)} → ${fmtChars(r.memCharsAfter)}（目标 ${fmtChars(r.targetChars)}${r.achieved ? '，已达标' : '，未达标'}）</div>`).join('');
    } else {
      html += '<div class="mem-log">暂无压缩记录（预算充足未触发自收敛）</div>';
    }
    const curCat = ((state.shelf && state.shelf.cats) || []).find((c) => c.id === chat.catId);
    html += `<div class="mem-agent">当前 Agent：${curCat ? (curCat.icon || '🐱') + ' ' + curCat.name : '喵喵'}</div>`;
    body.innerHTML = html;
    if (!document.body.classList.contains('ws-mode')) {
      clampMemPopup(); /* 浮窗形态：内容长高后校正位置；工作区常驻态由 CSS 定位，不校正 */
    }
  } catch (e) {
    body.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
  }
}

async function memPopupCompress() {
  if (chat.catId == null) return;
  try {
    await api(`/api/cats/${chat.catId}/chat/compress`, { method: 'POST', body: { sessionId: chatSessionId(chat.catId) } });
    toast('已标记压缩，下一轮对话开始时自动收敛', false);
    renderMemPopup();
  } catch (e) { toast(e.message, true); }
}

async function clearChatMemory() {
  /* 记忆 tab 已下线 */
}

/* ---- 工作区文档面板 ---- */

async function refreshChatFiles() {
  /* 「📁 文档」tab 已下线（功能暂时不要）：保留函数占位避免旧引用炸掉 */
}

async function previewChatFile(path) {
  /* 文档 tab 已下线 */
}

/* 输入框：Enter 发送，Shift+Enter 换行（抽屉与常驻栏都支持）；
   isComposing/229 = 输入法拼音组合中（选字回车不上屏），不能当成发送 */
document.addEventListener('keydown', (e) => {
  if (e.isComposing || e.keyCode === 229) return;
  if (e.key === 'Enter' && !e.shiftKey && e.target) {
    if (e.target.id === 'chatInput') { e.preventDefault(); sendChat('drawer'); }
    else if (e.target.id === 'gcInput') { e.preventDefault(); sendChat('graph'); }
  }
});

