/* 喵藏 Wiki 前端逻辑（原生 JS，无依赖） */

const $ = (id) => document.getElementById(id);
const state = { shelf: null, bookId: null, view: 'home', query: '', editingType: null, editingCat: null, movingId: null, pendingCatalogParent: null, pendingCatalogBook: null, drawerBookId: null, graphCatId: null, focusNodeId: null, readingCatId: null, readingRunCat: null, readingTimer: null, readingCards: [] };
const TYPE_ICONS = { URL: '🔗', WORD: '📄', EXCEL: '📊', TEXT: '📝' };

/* 前端异常自动上报：卡死/白屏类问题的现场取证通道（同文案 10s 内去重防风暴） */
const __lastReported = new Map();
function reportFrontError(kind, msg, extra) {
  try {
    const key = kind + '|' + msg;
    const now = Date.now();
    if (now - (__lastReported.get(key) || 0) < 10000) return;
    __lastReported.set(key, now);
    fetch('/api/front-log', { method: 'POST', headers: authHeaders({ 'Content-Type': 'application/json' }),
      body: JSON.stringify({ kind, msg: String(msg).slice(0, 500), extra: String(extra || '').slice(0, 1800), url: location.href.slice(0, 200) }) }).catch(() => {});
  } catch (e) { /* 上报自身失败静默 */ }
}
window.addEventListener('error', (e) => reportFrontError('error', e.message, (e.filename || '') + ':' + (e.lineno || '') + '\n' + ((e.error && e.error.stack) || '')));
window.addEventListener('unhandledrejection', (e) => reportFrontError('promise', (e.reason && (e.reason.stack || e.reason.message)) || String(e.reason), ''));

/* —— 登录态：Bearer Token（localStorage 持久），401 统一跳登录页 —— */
function authToken() { return localStorage.getItem('mc.token') || ''; }
function authHeaders(extra) {
  const h = extra ? { ...extra } : {};
  const t = authToken();
  if (t) h['Authorization'] = 'Bearer ' + t;
  return h;
}
function gotoLogin() {
  localStorage.removeItem('mc.token');
  if (!location.pathname.endsWith('/login.html')) location.href = '/login.html';
}

async function api(path, opts = {}) {
  const hasBody = opts.body !== undefined;
  const res = await fetch(path, {
    ...opts,
    headers: authHeaders(hasBody && !(opts.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
    body: !hasBody ? undefined
      : (opts.body instanceof FormData || typeof opts.body === 'string' ? opts.body : JSON.stringify(opts.body)),
  });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401) { gotoLogin(); throw new Error(data.error || '登录已过期'); }
  if (!res.ok) throw new Error(data.error || ('请求失败 ' + res.status));
  return data;
}

function toast(msg, isError = false) {
  const t = $('toast');
  t.textContent = msg;
  t.className = 'toast' + (isError ? ' error' : '');
  t.style.display = 'block';
  clearTimeout(t._timer);
  t._timer = setTimeout(() => (t.style.display = 'none'), 3200);
}

function esc(s) {
  return (s == null ? '' : String(s)).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtDate(s) { return s ? s.replace('T', ' ').slice(0, 16) : ''; }

/* ================= misc ================= */

function closeModal(id) {
  /* 关弹窗时顺手清掉里面可能残留的内嵌浏览器覆盖层，避免下次打开还挂着 */
  if (typeof closeWebBrowse === 'function') {
    if (id === 'fsPrevModal') closeWebBrowse($('fsPrevBody'));
    if (id === 'detailModal') closeWebBrowse($('detailBody'));
  }
  $(id).style.display = 'none';
}
document.querySelectorAll('.modal-mask').forEach((m) =>
  m.addEventListener('click', (e) => { if (e.target === m) m.style.display = 'none'; }));

/* 点击图谱右键菜单外部：关闭菜单（用 pointerdown，早于 click 触发，交互更跟手） */
document.addEventListener('pointerdown', (ev) => {
  const menu = $('g6NodeMenu');
  if (menu && !menu.contains(ev.target)) closeNodeMenu();
});

