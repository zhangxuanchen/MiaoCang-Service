/* ================= 会话悬浮球（全站可拖动，点击展开当前猫的会话栏） ================= */

function initWikiOrb() {
  const orb = $('wikiOrb');
  if (!orb) return;
  /* 位置记忆：恢复上次拖到的地方（视口内裁剪） */
  const saved = JSON.parse(localStorage.getItem('mc-orb-pos') || 'null');
  if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
    const x = Math.max(6, Math.min(saved.x, window.innerWidth - orb.offsetWidth - 6));
    const y = Math.max(6, Math.min(saved.y, window.innerHeight - orb.offsetHeight - 6));
    orb.style.left = x + 'px'; orb.style.top = y + 'px';
    orb.style.right = 'auto'; orb.style.bottom = 'auto';
  }
  let drag = null, moved = false, justDragged = false;
  orb.addEventListener('pointerdown', (e) => {
    drag = { sx: e.clientX, sy: e.clientY, ox: orb.offsetLeft, oy: orb.offsetTop };
    moved = false;
    try { orb.setPointerCapture(e.pointerId); } catch (ignored) { /* 老浏览器忽略 */ }
  });
  orb.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const dx = e.clientX - drag.sx, dy = e.clientY - drag.sy;
    if (!moved && Math.hypot(dx, dy) > 6) { moved = true; orb.classList.add('dragging'); }
    if (!moved) return;
    const x = Math.max(6, Math.min(drag.ox + dx, window.innerWidth - orb.offsetWidth - 6));
    const y = Math.max(6, Math.min(drag.oy + dy, window.innerHeight - orb.offsetHeight - 6));
    orb.style.left = x + 'px'; orb.style.top = y + 'px';
    orb.style.right = 'auto'; orb.style.bottom = 'auto';
    /* 拖动中实时保存：即使 pointerup 丢失也不丢位置 */
    localStorage.setItem('mc-orb-pos', JSON.stringify({ x, y }));
  });
  orb.addEventListener('pointerup', () => {
    if (drag && moved) {
      /* 拖动落笔：保存位置；紧随其后的 click 事件不当作点击 */
      justDragged = true;
      localStorage.setItem('mc-orb-pos', JSON.stringify({ x: orb.offsetLeft, y: orb.offsetTop }));
      setTimeout(() => { justDragged = false; }, 80);
    }
    drag = null; moved = false;
  });
  orb.addEventListener('pointercancel', () => { drag = null; moved = false; });
  /* 点击逻辑挂在 click 上（真实点击 / 键盘 / 合成事件都可达），拖动后去抖忽略 */
  orb.addEventListener('click', () => {
    if (justDragged) return;
    if ($('chatDrawer').classList.contains('open')) closeChat();
    else openChat(state.graphCatId); /* 默认展开当前图谱猫的会话 */
  });
  orb.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openChat(state.graphCatId); }
  });
  /* 窗口缩放后把球拉回可视区 */
  window.addEventListener('resize', () => {
    const x = Math.max(6, Math.min(orb.offsetLeft, window.innerWidth - orb.offsetWidth - 6));
    const y = Math.max(6, Math.min(orb.offsetTop, window.innerHeight - orb.offsetHeight - 6));
    orb.style.left = x + 'px'; orb.style.top = y + 'px';
    orb.style.right = 'auto'; orb.style.bottom = 'auto';
  });
}

/* ================= 会话悬浮窗：拖动（标题栏手柄）+ 缩放/位置记忆 ================= */

/* 恢复悬浮窗上次拖拽的位置与尺寸（视口内裁剪）；初始化与退出工作区时共用 */
function restoreChatFloatPos() {
  const d = $('chatDrawer');
  if (!d) return;
  const saved = JSON.parse(localStorage.getItem('mc-chat-pos') || 'null');
  if (saved && Number.isFinite(saved.x) && Number.isFinite(saved.y)) {
    d.style.left = Math.max(6, Math.min(saved.x, window.innerWidth - 120)) + 'px';
    d.style.top = Math.max(6, Math.min(saved.y, window.innerHeight - 80)) + 'px';
  }
  const sz = JSON.parse(localStorage.getItem('mc-chat-size') || 'null');
  if (sz && Number.isFinite(sz.w) && Number.isFinite(sz.h) && sz.w >= 420 && sz.h >= 400) {
    d.style.width = sz.w + 'px'; d.style.height = sz.h + 'px';
  }
}

function ensureChatFloat() {
  const d = $('chatDrawer');
  if (!d || d.dataset.floatInit) return;
  d.dataset.floatInit = '1';

  /* 点外部关闭：专属会话框打开时，点击它以外的区域即关闭；
     悬浮球除外（它有自己的开合逻辑），记忆浮窗 / 各类弹窗也不算"外部"，避免操作子面板时误关 */
  document.addEventListener('pointerdown', (e) => {
    if (!d.classList.contains('open')) return;
    if (d.contains(e.target)) return;
    if (e.target.closest && e.target.closest('#wikiOrb, #memPopup, .modal-mask')) return;
    closeChat();
  });

  restoreChatFloatPos();

  /* 标题栏拖动手柄（下拉框 / 按钮不触发拖动）；拖动中节流落盘位置。
     工作区常驻面板是固定布局，不拖拽也不记忆位置 */
  const top = d.querySelector('.drawer-top');
  let drag = null;
  const savePos = () => localStorage.setItem('mc-chat-pos', JSON.stringify({ x: d.offsetLeft, y: d.offsetTop }));
  top.addEventListener('pointerdown', (e) => {
    if (document.body.classList.contains('ws-mode')) return;
    if (e.target.closest('select, button, input')) return;
    drag = { sx: e.clientX, sy: e.clientY, ox: d.offsetLeft, oy: d.offsetTop, lastSave: 0 };
    top.classList.add('dragging');
    try { top.setPointerCapture(e.pointerId); } catch (ignored) { /* 忽略 */ }
  });
  top.addEventListener('pointermove', (e) => {
    if (!drag) return;
    const x = Math.max(6, Math.min(drag.ox + e.clientX - drag.sx, window.innerWidth - 120));
    const y = Math.max(6, Math.min(drag.oy + e.clientY - drag.sy, window.innerHeight - 80));
    d.style.left = x + 'px'; d.style.top = y + 'px';
    const now = Date.now();
    if (now - drag.lastSave > 250) { drag.lastSave = now; savePos(); }
  });
  const end = () => {
    if (!drag) return;
    drag = null;
    top.classList.remove('dragging');
    savePos();
  };
  top.addEventListener('pointerup', end);
  top.addEventListener('pointercancel', end);

  /* 右下角原生缩放手柄：尺寸变化节流落盘 */
  if (window.ResizeObserver) {
    let rzT = null;
    new ResizeObserver(() => {
      if (!d.classList.contains('open')) return;
      clearTimeout(rzT);
      rzT = setTimeout(() => {
        localStorage.setItem('mc-chat-size', JSON.stringify({ w: d.offsetWidth, h: d.offsetHeight }));
      }, 300);
    }).observe(d);
  }
}

initWikiOrb();
initChatSubActs();
initMemPopupStatic();
/* —— 启动：先校验登录态（无 token / 过期跳登录页），再进书房 —— */
async function logout() {
  try { await api('/api/auth/logout', { method: 'POST' }); } catch (e) { /* token 已失效也照常登出 */ }
  localStorage.removeItem('mc.token');
  location.href = '/login.html';
}
