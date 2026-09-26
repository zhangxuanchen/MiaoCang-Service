/* ================= 视图 ================= */

function goHome() { renderWelcome(); window.scrollTo(0, 0); }

/* 视图顶部返回条：每个内容页（菜单栏右侧详情区）均有 */
function backBar() {
  return `<div class="view-back"><button class="btn back" onclick="goHome()">← 返回书房</button></div>`;
}

/* 猫维度切换 tabs（收集箱 / 搜索结果页共用） */
function catTabs(activeId, fn) {
  const cats = (state.shelf && state.shelf.cats) || [];
  if (!cats.length) return '';
  return `<div class="cat-tabs">${cats.map((c) =>
    `<button class="cat-tab ${c.id === activeId ? 'active' : ''}" onclick="${fn}(${c.id})">${esc(c.icon || '🐱')} ${esc(c.name)}</button>`).join('')}</div>`;
}

/* 侧栏猫名单 active 同步：增量切换类名（DOM 不重建），消除视图切换时菜单栏的闪动与高度跳变 */
function setNav() {
  /* 非 workspace 视图渲染 = 离开专属工作区：恢复悬浮球/浮窗形态、收起常驻记忆栏 */
  if (state.view !== 'workspace') exitWorkspace();
  const tree = $('shelfTree');
  if (!tree || !tree.querySelector('.shelf-book')) { renderShelf(); return; }
  tree.querySelectorAll('.shelf-book').forEach((el) => {
    const on = (state.view === 'workspace' && el.dataset.ws)
      || (state.view === 'graph' && el.dataset.graphCat && state.graphCatId === Number(el.dataset.graphCat))
      || (state.view === 'reading' && el.dataset.readingCat && state.readingCatId === Number(el.dataset.readingCat))
      || (state.view && state.view.startsWith('diary-') && el.dataset.diary && state.view === 'diary-' + el.dataset.diary);
    el.classList.toggle('active', !!on);
  });
}

/* 操作后按当前视图刷新（书 / 收集箱 / 搜索 / 图谱抽屉 / 分类树） */
function refreshView() {
  if (state.view === 'graph') {
    if (state.drawerBookId) return openDrawerBook(state.drawerBookId);
    if (state.drawerTypeId) return openDrawerType(state.drawerTypeId);
    if (state.drawerFolder) return openDrawerFolder(state.drawerFolder.catId, state.drawerFolder.folder);
    return openGraph(state.graphCatId);
  }
  if (state.view === 'reading') return openReading(state.readingCatId);
  if (state.view === 'book') return openBook(state.bookId);
  if (state.view === 'inbox') return openInbox();
  if (state.view === 'taxonomy') return openTaxonomy();
  return renderWelcome();
}

function renderWelcome() {
  state.view = 'home';
  setNav(null);
  const s = state.shelf;
  const cats = s ? (s.cats || []) : [];
  const types = s ? s.types : [];
  const books = types.reduce((a, t) => a + t.books.filter((b) => b.contentCount > 0 || !b.defaultBook).length, 0);
  const contents = types.reduce((a, t) => a + t.books.reduce((x, b) => x + b.contentCount, 0), 0);
  const pending = s ? s.inboxCount : 0;
  const presets = (types || []).filter((t) => t.preset === true);
  $('main').innerHTML = `
    <div class="home-hero">
      <div class="kicker">书房总览 · OVERVIEW</div>
      <h1>把每一条投入，<br>都<em class="acc">归入</em>它的书。</h1>
      <p class="lead">网页、Word、Excel、一段话——丢进来，引力波会把它送进该去的书与章节。
      ${pending > 0 ? `现在有 <em class="acc">${pending} 条</em>内容在各喵的收集箱等你裁决（进喵的图谱页右上角 📥）。` : '所有内容都已被引力安放妥当。'}</p>
    </div>
    <div class="stat-row">
      <div class="stat click" onclick="openGraph(${(cats[0] && cats[0].id) || 0})" title="进入第一只喵的知识图谱">
        <div class="num">${cats.length}<small>只</small></div>
        <div class="cap">我养的喵</div>
        <div class="sub">可用 ${types.length} 个知识分类 · 点击进入第一只喵的图谱</div>
      </div>
      <div class="stat">
        <div class="num">${books}<small>本</small></div>
        <div class="cap">正在成长的书</div>
        <div class="sub">每本都有二级目录</div>
      </div>
      <div class="stat">
        <div class="num">${contents}<small>条</small></div>
        <div class="cap">已归档内容</div>
        <div class="sub">被引力安放进书里</div>
      </div>
      <div class="stat">
        <div class="num" style="${pending ? '' : 'color:var(--muted)'}">${pending}<small>条</small></div>
        <div class="cap">待你裁决</div>
        <div class="sub">收集箱 · 在喵的图谱页右上角 📥 里</div>
      </div>
    </div>
    <div class="home-manage">
      <div class="manage-card wide">
        <div class="manage-head">
          <span class="manage-title">🗂 预设置分类</span>
          <div class="manage-actions">
            <button class="btn sm" onclick="openTypeModal()">＋ 新建</button>
          </div>
        </div>
        <div class="preset-chips">${presets.length ? presets.map((t) => {
          return `<span class="preset-chip" title="${esc(t.name)}（预设置分类 · 全体喵共享）">
            <span class="dot" style="background:${esc(t.color || '#888')}"></span>${esc(t.icon || '🗂')} ${esc(t.name)}<i>🌐 共享</i>
            <button class="pc-x" type="button" data-name="${esc(t.name)}" onclick="presetTypeDelete(${t.id}, this)" title="删除「${esc(t.name)}」">✕</button></span>`;
        }).join('') : '<span class="preset-empty">暂无预设置分类，点「＋ 新建」创建</span>'}</div>
      </div>
      <div class="manage-card">
        <div class="manage-head">
          <span class="manage-title">🔑 默认 AK</span>
          <button class="btn sm" onclick="openAgentConfig(null)">管理</button>
        </div>
        <div class="ak-line">
          <span class="ak-dot" id="homeAkDot" style="background:#bbb"></span>
          <span class="ak-state" id="homeAkState">检测中…</span>
          <span class="ak-sub">所有未做专属配置的喵共用这份模型配置</span>
        </div>
      </div>
    </div>`;
  /* 默认 AK 状态灯：绿=已配置（真实模型可用）、灰=未配置 */
  api('/api/agent/default-config').then((c) => {
    const has = !!c.hasKey;
    const dot = $('homeAkDot'), st = $('homeAkState');
    if (dot) dot.style.background = has ? '#0F6E56' : '#bbb';
    if (st) st.textContent = has ? '已配置' : '未配置';
  }).catch(() => { /* 接口异常时保持中性显示 */ });
}

/* 预设置分类的 ✕：确认后直接删（不再走「点开编辑」那条路，分类一旦建好就不改了）。
   分类下的内容不丢：后端会把它们整批退回对应喵的收集箱，等重新裁决。 */
async function presetTypeDelete(id, btn) {
  const name = (btn && btn.dataset && btn.dataset.name) || '该分类';
  if (!confirm('删除预设置分类「' + name + '」？\n\n它是全体喵共享的，删掉后所有喵的图谱里都会消失。\n分类下的内容不会丢，会退回对应喵的收集箱。')) return;
  try {
    const r = await api('/api/types/' + id, { method: 'DELETE' });
    const n = (r && r.movedToInbox) || 0;
    toast('已删除「' + name + '」' + (n ? '，' + n + ' 条内容已退回收集箱' : ''));
    await loadShelf();
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function openBook(id) {
  const data = await api('/api/books/' + id);
  state.view = 'book';
  state.bookId = id;
  setNav(null);
  const b = data.book, t = data.type;
  let html = `
    ${backBar()}
    <div class="book-head">
      <div class="book-kicker"><i style="width:8px;height:8px;border-radius:50%;background:${esc(t?.color || '#888')};display:inline-block"></i>${esc(t?.icon || '')} ${esc(t?.name || '')} · COLLECTION</div>
      <div class="book-title">${esc(b.title)}</div>
      <div class="book-desc">${esc(b.description || '')}</div>
      <div class="book-meta">
        <span>共 ${b.contentCount} 条内容</span>
        <span>${data.sections.length} 个一级目录</span>
        <button class="btn sm danger" onclick="deleteBook(${b.id})">删除本书</button>
        <button class="btn sm" onclick="openCatalogModal(${b.id}, null)">＋ 一级目录</button>
      </div>
    </div>`;

  if (!data.sections.length && !data.loose.length) {
    html += `<div class="empty">这本书还没有内容，去「采集内容」投入第一条吧</div>`;
  }

  data.sections.forEach((sec, i) => {
    const n = sec.node;
    const subCount = sec.contents.length + sec.children.reduce((a, c) => a + c.contents.length, 0);
    html += `
      <div class="section">
        <div class="section-head">
          <span class="section-index">第${'一二三四五六七八九十'[i] || i + 1}章</span>
          <span class="section-name">${esc(n.name)}</span>
          <span class="section-count">${subCount} 条</span>
          <span class="spacer"></span>
          <button class="btn sm" onclick="openCatalogModal(${b.id}, ${n.id})">＋ 二级目录</button>
          <button class="btn sm" onclick="renameCatalog(${n.id}, '${esc(n.name).replace(/'/g, "\\'")}')">重命名</button>
          <button class="btn sm danger" onclick="deleteCatalog(${n.id})">删除</button>
        </div>
        ${sec.contents.length ? `<div class="content-list">${sec.contents.map(cardHtml).join('')}</div>` : ''}
        ${sec.children.map((ch) => `
          <div class="subsection">
            <div class="subsection-name">${esc(ch.node.name)} <span style="font-weight:400;color:var(--muted)">${ch.contents.length}</span>
              <span class="spacer" style="flex:1"></span>
              <button class="btn sm" onclick="renameCatalog(${ch.node.id}, '${esc(ch.node.name).replace(/'/g, "\\'")}')">重命名</button>
              <button class="btn sm danger" onclick="deleteCatalog(${ch.node.id})">删除</button>
            </div>
            ${ch.contents.length ? `<div class="content-list">${ch.contents.map(cardHtml).join('')}</div>` : '<div class="empty" style="padding:10px">暂无内容</div>'}
          </div>`).join('')}
      </div>`;
  });

  if (data.loose.length) {
    html += `<div class="section"><div class="section-head"><span class="section-name">未入目录</span></div>
      <div class="content-list">${data.loose.map(cardHtml).join('')}</div></div>`;
  }
  $('main').innerHTML = html;
  renderShelf();
}

async function openInbox(catId) {
  const cid = catId || state.inboxCatId || state.graphCatId || (((state.shelf && state.shelf.cats) || [])[0] || {}).id;
  state.view = 'inbox';
  state.inboxCatId = cid;
  setNav(null);
  await loadShelf(); /* 刷新书房概况与默认 AK 状态灯 */
  const cat = ((state.shelf && state.shelf.cats) || []).find((c) => c.id === cid);
  const items = await api('/api/inbox' + (cid ? '?catId=' + cid : ''));
  let html = `
    <div class="view-back"><button class="btn back" onclick="${cid ? `openGraph(${cid})` : 'goHome()'}">← 返回${cat ? ' ' + esc((cat.icon || '🐱') + ' ' + cat.name) : ''}的图谱</button></div>
    <div class="view-head">
      <div>
        <div class="kicker">Inbox · 双向裁决</div>
        <h2>${cat ? esc((cat.icon || '🐱') + ' ' + cat.name) + ' · ' : ''}收集箱 <small style="color:var(--muted);font-size:13px;font-weight:400">这只猫的引力未达标内容在此等待，点击建议一键归档</small></h2>
      </div>
    </div>
    ${catTabs(cid, 'openInbox')}`;
  html += items.length
    ? `<div class="content-list">${items.map(cardHtml).join('')}</div>`
    : '<div class="empty">这只猫的收集箱是空的，所有内容都已被引力归档 🎉</div>';
  $('main').innerHTML = html;
}

/* ============ 专属会话区全库搜索：输入 /关键词 或「搜 关键词」即搜当前用户全部喵的领地 ============ */
const SEARCH_TYPE_META = { point: { icon: '🧠', label: '知识点' }, book: { icon: '📚', label: '书' }, learn: { icon: '🎴', label: '学习卡' } };
function parseSearchQuery(text) {
  if (text.startsWith('/')) return text.slice(1).trim();
  const m = text.match(/^搜[索]?\s+(.+)$/);
  return m ? m[1].trim() : null;
}
async function chatSearch(surface, q) {
  const catId = chat.catId; /* 全库搜索跨当前用户所有喵（all=true），与具体喵无关但走它的归属校验 */
  chatAppendMsg('user', '🔍 全库搜索『' + q + '」', surface);
  const bubble = chatAppendMsg('assistant', '', surface);
  const body = bubble.querySelector('.chat-bubble');
  body.innerHTML = '<span class="chat-typing"></span>';
  chatScroll(surface);
  try {
    const rows = await api(`/api/cats/${catId}/study/search?all=true&limit=12&q=` + encodeURIComponent(q));
    if (!rows.length) { body.innerHTML = '<div class="cs-empty">没搜到与「' + esc(q) + '」相关的内容（试试换个词）</div>'; chatScroll(surface); return; }
    const catName = (id) => { const c = ((state.shelf && state.shelf.cats) || []).find((x) => x.id === id); return c ? c.name : '喵 #' + id; };
    body.innerHTML = '<div class="cs-head">🔎 共命中 ' + rows.length + ' 条（点击条目展开原文）</div>' + rows.map((r) => {
      const meta = SEARCH_TYPE_META[r.type] || { icon: '📄', label: r.type };
      const frag = (r.body || '').replace(/[#*`>\-\[\]()!]/g, '').replace(/\s+/g, ' ').trim().slice(0, 80);
      return `<div class="cs-item" onclick="this.classList.toggle('open');chatScroll('${surface}')">` +
        `<div class="cs-line"><span class="cs-badge">${meta.icon} ${meta.label}</span>` +
        `<span class="cs-title">${esc(r.title)}</span>` +
        `<span class="cs-cat">@${esc(catName(r.catId))}</span>` +
        `<span class="cs-score">${r.score}</span></div>` +
        `<div class="cs-frag">${esc(frag)}…</div>` +
        `<div class="cs-body md-body">${mdLite(r.body || '')}</div>` +
        `</div>`;
    }).join('');
  } catch (e) {
    body.innerHTML = '<span style="color:#b91c1c">搜索失败：' + esc(e.message) + '</span>';
  }
  chatScroll(surface);
}

/* ============ 会话区内搜索已移除（用户不需要搜索栏；搜索已融入会话输入：/关键词 触发全库搜索） ============ */

