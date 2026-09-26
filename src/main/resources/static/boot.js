(async function bootAuth() {
  /* 顶栏高度实测回写 CSS 变量：sticky 侧栏/吸附条的 top 依赖 --topbar-h，
     顶栏内容增减（如搜索条移除）导致实际高度变化时，硬编码值会让侧栏与顶栏之间露出缝隙 */
  const syncTopbarH = () => {
    const tb = document.querySelector('.topbar');
    if (tb) document.documentElement.style.setProperty('--topbar-h', tb.offsetHeight + 'px');
  };
  syncTopbarH();
  window.addEventListener('resize', syncTopbarH);
  if (!authToken()) { location.href = '/login.html'; return; }
  try {
    const me = await api('/api/auth/me');
    const box = $('userBox');
    if (box) box.innerHTML =
      `<span class="user-chip" title="当前登录：${esc(me.username)}">👤 ${esc(me.displayName || me.username)}</span>` +
      `<button class="btn ghost" onclick="logout()">登出</button>`;
    await loadShelf();
    renderWelcome();
  } catch (e) { /* 401 已跳登录；其余错误保留页面报错现场 */ }
})();
