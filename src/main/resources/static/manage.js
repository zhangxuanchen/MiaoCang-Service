/* ================= 移动 / 重分类 / 删除 ================= */

async function openMoveModal(id) {
  state.movingId = id;
  const c = await api('/api/contents/' + id);
  $('moveTitle').textContent = `移动「${c.title}」`;
  const shelf = state.shelf || await api('/api/shelf');
  $('moveBook').innerHTML = '<option value="">— 收集箱 —</option>' + shelf.types.map((t) =>
    `<optgroup label="${esc(t.icon + ' ' + t.name)}">` + t.books.map((b) =>
      `<option value="${b.id}" ${c.bookId === b.id ? 'selected' : ''}>${esc(b.title)}</option>`).join('') + '</optgroup>').join('');
  await refreshMoveCatalogs(c.catalogNodeId);
  $('moveBook').onchange = () => refreshMoveCatalogs(null);
  $('moveToInbox').checked = !c.bookId;
  closeModal('detailModal');
  $('moveModal').style.display = 'flex';
}

async function refreshMoveCatalogs(selectedId) {
  const bookId = $('moveBook').value;
  if (!bookId) { $('moveCatalog').innerHTML = '<option value="">（收集箱无需目录）</option>'; $('moveCatalog').disabled = true; return; }
  $('moveCatalog').disabled = false;
  const data = await api('/api/books/' + bookId);
  let html = '<option value="">（书根，暂不指定目录）</option>';
  data.sections.forEach((sec) => {
    html += `<option value="${sec.node.id}" ${selectedId === sec.node.id ? 'selected' : ''}>${esc(sec.node.name)}</option>`;
    sec.children.forEach((ch) => {
      html += `<option value="${ch.node.id}" ${selectedId === ch.node.id ? 'selected' : ''}>　└ ${esc(ch.node.name)}</option>`;
    });
  });
  $('moveCatalog').innerHTML = html;
}

async function submitMove() {
  const id = state.movingId;
  const toInbox = $('moveToInbox').checked;
  const bookId = toInbox ? null : ($('moveBook').value || null);
  const catalogNodeId = toInbox ? null : ($('moveCatalog').value || null);
  /* 谁归档算谁的：带上当前喵上下文（收集箱页=该喵，图谱页=该喵，其他视图不指定） */
  const catId = state.inboxCatId || state.graphCatId || null;
  try {
    const c = await api(`/api/contents/${id}/move`, { method: 'PUT', body: { bookId, catalogNodeId, catId } });
    closeModal('moveModal');
    toast(bookId ? '✅ 已移动' : '📥 已移回收集箱');
    await loadShelf();
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function confirmSuggestion(id, typeId) {
  /* 谁归档算谁的：收集箱/图谱上下文里的喵成为内容归属 */
  const catId = state.inboxCatId || state.graphCatId || null;
  try {
    const c = await api(`/api/contents/${id}/confirm-suggestion`, { method: 'POST', body: { typeId, catId } });
    closeModal('detailModal');
    toast(`✅ 已归入「${c.bookTitle || ''}」`);
    await loadShelf();
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function editContent(id) {
  const c = await api('/api/contents/' + id);
  const title = prompt('修改标题', c.title || '');
  if (title === null) return;
  const summary = prompt('修改摘要（留空则清除）', c.summary || '');
  if (summary === null) return;
  try {
    await api(`/api/contents/${id}/edit`, { method: 'PUT', body: { title, summary } });
    toast('✅ 已更新');
    closeModal('detailModal');
    await loadShelf();
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function reclassify(id) {
  try {
    const c = await api(`/api/contents/${id}/reclassify`, { method: 'POST' });
    closeModal('detailModal');
    toast(c.status === 'CLASSIFIED' ? `✅ 已重新归入「${c.bookTitle || ''}」` : '📥 引力不足，已回收集箱');
    await loadShelf();
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function deleteContent(id) {
  if (!confirm('确定删除这条内容？')) return;
  await api('/api/contents/' + id, { method: 'DELETE' });
  closeModal('detailModal');
  toast('已删除');
  await loadShelf();
  removeContentNode(id); /* 图谱画布就地摘掉叶子，别等全量重建 */
  refreshView();
}

/* ================= 猫 / 知识分类管理 ================= */

/* 领养新猫 / 编辑猫（id 为空 = 领养） */
function openCatModal(id) {
  /* 领养上限：最多 5 只（与后端 MAX_CATS 同源校验），满员时领养入口直接提示不弹窗 */
  if (!id && ((state.shelf || {}).cats || []).length >= 5) {
    toast('书房最多养 5 只喵，想再领养请先送养一只', true);
    return;
  }
  state.editingCat = id || null;
  $('catModalTitle').textContent = id ? '编辑猫' : '领养新猫';
  const c = id ? ((state.shelf || {}).cats || []).find((x) => x.id === id) : null;
  $('catName').value = c ? c.name : '';
  $('catIcon').value = c ? (c.icon || '🐱') : '🐱';
  $('catColor').value = c ? (c.color || '#F59E0B') : '#F59E0B';
  $('catDesc').value = c ? (c.description || '') : '';
  $('catDeleteBtn').style.display = id ? '' : 'none';
  $('catModal').style.display = 'flex';
}

async function submitCat() {
  const name = $('catName').value.trim();
  if (!name) return toast('给猫起个名字吧', true);
  const body = { name, icon: $('catIcon').value || '🐱', color: $('catColor').value, description: $('catDesc').value };
  try {
    const isEdit = !!state.editingCat;
    const saved = isEdit
      ? await api('/api/cats/' + state.editingCat, { method: 'PUT', body })
      : await api('/api/cats', { method: 'POST', body });
    closeModal('catModal');
    toast(isEdit ? `✏️ ${name} 的信息已更新` : `🐱 已领养「${name}」`);
    state.editingCat = null;
    await loadShelf();
    const c = shelfCat(saved.id) || { id: saved.id, name, icon: body.icon, color: body.color };
    const id = 'cat-' + c.id;
    /* 改资料：图谱里就地重绘；新建：本图能画就就地长出来，否则去总览图谱 */
    if (isEdit) {
      if (!await graphUpdateNode(id, catNodeData(c))) refreshView();
      return;
    }
    revealNode(id, 'cat', c.id, '', catNodeData(c));
  } catch (e) { toast(e.message, true); }
}

async function deleteCat(id) {
  if (!confirm('确定送养这只猫？（它名下不能还有知识分类）')) return;
  try {
    await api('/api/cats/' + id, { method: 'DELETE' });
    closeModal('catModal');
    state.editingCat = null;
    /* 送养后全面归位：清掉所有指向这只猫的视图状态，菜单栏随 loadShelf 刷新，界面不停在死猫上 */
    const numId = Number(id);
    if (state.graphCatId === numId) state.graphCatId = null;
    if (state.inboxCatId === numId) state.inboxCatId = null;
    if (state.readingCatId === numId) state.readingCatId = null;
    if (typeof chat !== 'undefined' && Number(chat.catId) === numId) chat.catId = null;
    toast('🐱 已送养');
    await loadShelf();
    /* 当前视图若是被删猫的整理页/收集箱/工作区，回书房总览（graph 视图由 openGraph(null) 自动落总览） */
    if (state.view === 'reading' || state.view === 'inbox' || state.view === 'workspace') state.view = 'home';
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function openTypeModal(id, fromCatId) {
  state.editingType = id || null;
  $('typeModalTitle').textContent = id ? '编辑知识分类' : '新建知识分类';
  const cats = (state.shelf && state.shelf.cats) || [];
  /* 新建 = 分类名下方的挂接下拉：选共享分类则挂接（不新建），不选则自建专属；编辑不显示挂接行 */
  $('tmAttachRow').style.display = id ? 'none' : '';
  fillTypePresetOptions();
  resetAttachPick();
  /* 所属猫不进弹窗：新建跟随入口上下文（右键某猫图谱带 fromCatId，否则默认第一只），编辑保持原归属 */
  if (id) {
    const t = (await api('/api/types')).find((x) => x.id === id);
    state.typeCatId = t.catId || (cats[0] ? cats[0].id : null);
    $('typeName').value = t.name; $('typeIcon').value = t.icon || '📘'; $('typeColor').value = t.color || '#3B82F6';
    $('typeDesc').value = t.description || ''; $('typeKeywords').value = t.gravityKeywords || '';
  } else {
    state.typeCatId = fromCatId || (cats[0] ? cats[0].id : null);
    $('typeName').value = ''; $('typeIcon').value = '💡'; $('typeColor').value = '#3B82F6';
    $('typeDesc').value = ''; $('typeKeywords').value = '';
  }
  $('typeModal').style.display = 'flex';
  renderSharedRef();
}

/* 共享分类引力词参考：弹窗内直接展示全部共享分类的关键词，点词条即复用到输入框（免去去书房总览翻找） */
let sharedKwCache = [];
function renderSharedRef() {
  const box = $('tmSharedList');
  sharedKwCache = [];
  const types = ((state.shelf && state.shelf.types) || []).filter((t) => t.shared);
  box.innerHTML = types.map((t) => {
    const kws = (t.gravityKeywords || '').split(',').map((s) => s.trim()).filter(Boolean);
    if (!kws.length) return '';
    const base = sharedKwCache.length;
    kws.forEach((k) => sharedKwCache.push(k));
    return `<div class="tsr-row"><span class="tsr-name">${esc((t.icon || '📘') + ' ' + t.name)}</span>` +
      kws.map((k, i) => `<button class="tsr-chip" onclick="reuseKeyword(${base + i})" title="点击加入下方关键词">${esc(k)}</button>`).join('') +
      `</div>`;
  }).join('') || '<div class="tm-hint">暂无共享分类词表</div>';
}
function reuseKeyword(i) {
  const k = sharedKwCache[i];
  if (!k) return;
  const cur = $('typeKeywords').value.split(',').map((s) => s.trim()).filter(Boolean);
  if (cur.includes(k)) return toast('该词已在关键词里');
  cur.push(k);
  $('typeKeywords').value = cur.join(', ');
  toast('已加入：' + k);
}

/* 共享分类下拉：首项 = 自建专属；其余 = 书房总览维护的 preset 分类（state.shelf.types 的 shared 行） */
function fillTypePresetOptions() {
  const presets = ((state.shelf && state.shelf.types) || []).filter((t) => t.shared);
  $('typePreset').innerHTML = '<option value="">✍️ 不挂接，自建专属分类</option>' + presets.map((t) =>
    `<option value="${t.id}">${esc((t.icon || '📘') + ' ' + t.name)}${t.contentCount ? ` · ${t.contentCount} 条内容` : ''}</option>`).join('');
}

/* 挂接下拉联动：选共享分类 → 分类名锁定为共享名、自建字段收起、按钮变「挂接」；改回自建 → 全部恢复 */
function onAttachPick() {
  const pid = Number($('typePreset').value) || null;
  if (!pid) { resetAttachPick(); return; }
  const t = ((state.shelf && state.shelf.types) || []).find((x) => x.id === pid);
  if (!t) { resetAttachPick(); return; }
  $('typeName').value = (t.icon || '📘') + ' ' + t.name;
  $('typeName').disabled = true;
  $('tmBuildRows').style.display = 'none';
  $('tmSubmitBtn').textContent = '🌐 挂接到图谱';
}
function resetAttachPick() {
  $('typePreset').value = '';
  $('typeName').disabled = false;
  $('typeName').value = '';
  $('tmBuildRows').style.display = '';
  $('tmSubmitBtn').textContent = '保存分类';
}

async function submitType() {
  /* 🌐 挂接共享分类：不新建数据，把书房总览的共享分类挂上当前喵的图谱 */
  if (!state.editingType && Number($('typePreset').value)) {
    const pid = Number($('typePreset').value);
    if (!pid) return toast('没有可选的共享分类，先去书房总览维护预设分类', true);
    if (!state.typeCatId) return toast('先领养一只猫，再挂接共享分类', true);
    try {
      await api(`/api/types/${pid}/attach?catId=${state.typeCatId}`, { method: 'POST' });
      closeModal('typeModal');
      toast('✅ 共享分类已挂接到图谱');
      await loadShelf();
      const t = shelfType(pid);
      if (t) revealNode('type-' + pid, 'type', state.typeCatId, 'cat-' + state.typeCatId, typeNodeData(t));
    } catch (e) { toast(e.message, true); }
    return;
  }
  const body = {
    catId: state.typeCatId || null,
    name: $('typeName').value.trim(), icon: $('typeIcon').value || '📘', color: $('typeColor').value,
    description: $('typeDesc').value, gravityKeywords: $('typeKeywords').value,
  };
  if (!body.name) return toast('请输入分类名', true);
  if (!body.catId) return toast('先领养一只猫，再给它建分类', true);
  try {
    const isEdit = !!state.editingType;
    const saved = isEdit
      ? await api('/api/types/' + state.editingType, { method: 'PUT', body })
      : await api('/api/types', { method: 'POST', body });
    closeModal('typeModal');
    toast('✅ 知识分类已保存');
    await loadShelf();
    const t = shelfType(saved.id) || saved;
    const id = 'type-' + t.id;
    /* 改属性：图谱里就地重绘（换了猫就退回整页刷新，节点得跟着换父节点）；
       新建：本图能画就就地长出来，否则跳去这只猫的图谱 */
    if (isEdit) {
      const sameGraph = g6Scope === 'overview' || g6Scope === 'cat-' + t.catId;
      if (!sameGraph || !await graphUpdateNode(id, typeNodeData(t))) refreshView();
      return;
    }
    revealNode(id, 'type', t.catId, 'cat-' + t.catId, typeNodeData(t));
  } catch (e) { toast(e.message, true); }
}

/* 解除挂接：该喵图谱摘掉共享分类节点（共享数据与其他喵不受影响） */
async function detachType(id, catId) {
  if (!confirm('解除挂接后，这个共享分类将从当前喵的图谱上摘除（分类数据与其余喵不受影响）。继续？')) return;
  try {
    await api(`/api/types/${id}/attach?catId=${catId}`, { method: 'DELETE' });
    toast('已解除挂接');
    await loadShelf();
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function deleteType(id) {
  if (!confirm('删除该知识分类？\n\n分类下的内容不会丢，会退回对应喵的收集箱。')) return;
  try {
    const r = await api('/api/types/' + id, { method: 'DELETE' });
    const n = (r && r.movedToInbox) || 0;
    toast('已删除' + (n ? '，' + n + ' 条内容已退回收集箱' : ''));
    await loadShelf();
    refreshView();
  } catch (e) { toast(e.message, true); }
}

/* ================= 书 / 目录 ================= */

async function openBookModal(typeId) {
  const types = await api('/api/types');
  $('bookType').innerHTML = types.map((t) =>
    `<option value="${t.id}" ${t.id === typeId ? 'selected' : ''}>${esc(t.icon + ' ' + t.name)}</option>`).join('');
  $('bookTitle').value = ''; $('bookDesc').value = '';
  $('bookModal').style.display = 'flex';
}

async function submitBook() {
  try {
    const typeId = +$('bookType').value;
    const title = $('bookTitle').value.trim();
    const created = await api('/api/books', {
      method: 'POST',
      body: { title, typeId, description: $('bookDesc').value },
    });
    closeModal('bookModal');
    toast(`✅ 已创建《${created.title || title}》`);
    await loadShelf();
    showNewBook(created, typeId);
  } catch (e) { toast(e.message, true); }
}

/* 新书不再上图（画布已删书层）：数据已刷新，目录里的计数就地更新即可 */
function showNewBook(book, typeId) {
  const t = shelfType(typeId);
  if (!t || !t.catId) return refreshView();
  if (state.view === 'graph' && state.graphCatId && graphAlive() && g6Scope === 'cat-' + state.graphCatId) {
    renderGraphToc();
  }
}

async function renameBook(id, oldTitle) {
  const title = prompt('新的书名', oldTitle);
  if (!title || title === oldTitle) return;
  try {
    await api('/api/books/' + id, { method: 'PUT', body: { title } });
    toast('✏️ 书名已更新');
    await loadShelf();
    /* 在图谱里就地改标签；抽屉正开着的话标题一起改，都不整页刷新 */
    const hit = shelfBook(id);
    const done = hit && await graphUpdateNode('book-' + id, bookNodeData(hit.book, hit.type));
    if (state.drawerBookId === id) {
      const dt = document.querySelector('#drawerBody .drawer-title');
      if (dt) dt.textContent = title;
    }
    if (!done) refreshView();
  } catch (e) { toast(e.message, true); }
}

async function deleteBook(id) {
  if (!confirm('删除本书？书内内容将退回收集箱。')) return;
  try {
    await api('/api/books/' + id, { method: 'DELETE' });
    toast('已删除，内容退回收集箱');
    closeDrawer();
    await loadShelf();
    /* 书已不上图：单猫图谱就地摘掉该书的内容叶子并刷新目录，其他视图全量刷新 */
    if (state.view === 'graph' && state.graphCatId && graphAlive() && g6Scope === 'cat-' + state.graphCatId) {
      removeGraphBook(id);
      renderGraphToc();
    } else {
      refreshView();
    }
  } catch (e) { toast(e.message, true); }
}

async function openCatalogModal(bookId, parentId) {
  state.pendingCatalogBook = bookId;
  state.pendingCatalogParent = parentId;
  $('catalogModalTitle').textContent = parentId ? '新建二级目录' : '新建一级目录';
  $('catalogParentHint').textContent = parentId ? '将在选定的一级目录下创建二级目录' : '将作为该书的一级目录（章）';
  $('catalogName').value = '';
  $('catalogModal').style.display = 'flex';
}

async function submitCatalog() {
  const name = $('catalogName').value.trim();
  const bookId = state.pendingCatalogBook || state.bookId;
  if (!name) return toast('请输入目录名', true);
  if (!bookId) return toast('请先打开一本书', true);
  try {
    await api(`/api/books/${bookId}/catalogs`, {
      method: 'POST',
      body: { name, parentId: state.pendingCatalogParent },
    });
    closeModal('catalogModal');
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function renameCatalog(id, oldName) {
  const name = prompt('新的目录名', oldName);
  if (!name || name === oldName) return;
  try {
    await api('/api/catalogs/' + id, { method: 'PUT', body: { name } });
    refreshView();
  } catch (e) { toast(e.message, true); }
}

async function deleteCatalog(id) {
  if (!confirm('删除该目录？（目录须为空）')) return;
  try {
    await api('/api/catalogs/' + id, { method: 'DELETE' });
    refreshView();
  } catch (e) { toast(e.message, true); }
}

