/* ================= 分类维护（全层级一棵树） ================= */

/* 类型 → 书 → 一级目录 → 二级目录，增删改名都在这页完成 */
async function openTaxonomy() {
  state.view = 'taxonomy';
  state.drawerBookId = null;
  setNav('taxonomy');
  const s = state.shelf || await api('/api/shelf');
  const details = await Promise.all(s.types.flatMap((t) => t.books.map((b) => api('/api/books/' + b.id))));
  const byBook = {};
  details.forEach((d) => { byBook[d.book.id] = d; });

  let html = `
    ${backBar()}
    <div class="type-bar">
      <div>
        <div class="kicker">Taxonomy · 全层级维护</div>
        <h2>分类维护 <small style="color:var(--muted);font-size:13px;font-weight:400">类型 → 书 → 一级 / 二级目录，一棵树上增删改名</small></h2>
      </div>
      <div style="display:flex;gap:8px">
        <button class="btn" onclick="openBookModal()">＋ 新建书</button>
        <button class="btn primary" onclick="openTypeModal()">＋ 新建类型</button>
      </div>
    </div>`;
  html += s.types.map((t) => `
    <div class="tax-type" style="--tc:${esc(t.color || '#FFC93C')}">
      <div class="tax-type-head">
        <span class="tax-type-name">${esc(t.icon || '')} ${esc(t.name)}</span>
        <span class="tax-count">${t.bookCount} 本书</span>
        <span class="spacer"></span>
        <button class="btn sm" onclick="openTypeModal(${t.id})">编辑</button>
        ${t.preset ? '' : `<button class="btn sm danger" onclick="deleteType(${t.id})">删除</button>`}
      </div>
      ${t.books.length ? t.books.map((b) => {
        const d = byBook[b.id] || { sections: [] };
        return `
        <div class="tax-book">
          <div class="tax-book-head">
            <span class="tax-book-title">📖 ${esc(b.title)}</span>
            <span class="tax-count">${b.contentCount} 条</span>
            <span class="spacer"></span>
            <button class="btn sm" onclick="openBook(${b.id})">打开</button>
            <button class="btn sm" onclick="renameBook(${b.id}, '${esc(b.title).replace(/'/g, "\\'")}')">改名</button>
            <button class="btn sm" onclick="openCatalogModal(${b.id}, null)">＋ 一级目录</button>
            <button class="btn sm danger" onclick="deleteBook(${b.id})">删除</button>
          </div>
          <div class="tax-cats">
            ${d.sections.length ? d.sections.map((sec) => `
              <div class="tax-cat l1">
                <span class="tax-cat-name">▸ ${esc(sec.node.name)}</span>
                <span class="tax-count">${sec.contents.length + sec.children.reduce((a, c) => a + c.contents.length, 0)} 条</span>
                <span class="spacer"></span>
                <button class="btn sm" onclick="openCatalogModal(${b.id}, ${sec.node.id})">＋ 二级</button>
                <button class="btn sm" onclick="renameCatalog(${sec.node.id}, '${esc(sec.node.name).replace(/'/g, "\\'")}')">改名</button>
                <button class="btn sm danger" onclick="deleteCatalog(${sec.node.id})">删除</button>
              </div>
              ${sec.children.map((ch) => `
                <div class="tax-cat l2">
                  <span class="tax-cat-name">└ ${esc(ch.node.name)}</span>
                  <span class="tax-count">${ch.node.contentCount} 条</span>
                  <span class="spacer"></span>
                  <button class="btn sm" onclick="renameCatalog(${ch.node.id}, '${esc(ch.node.name).replace(/'/g, "\\'")}')">改名</button>
                  <button class="btn sm danger" onclick="deleteCatalog(${ch.node.id})">删除</button>
                </div>`).join('')}
            `).join('') : '<div class="tax-empty">这本书还没有目录</div>'}
          </div>
        </div>`;
      }).join('') : '<div class="tax-empty">该类型下还没有书</div>'}
    </div>`).join('');
  if (!s.types.length) html += '<div class="empty">还没有类型，点右上角「＋ 新建类型」开始</div>';
  $('main').innerHTML = html;
}

/* ================= 内容卡片 ================= */

function cardHtml(c) {
  const tags = (c.matchedTags || '').split(',').filter(Boolean).slice(0, 4)
    .map((t) => `<span class="tag">#${esc(t)}</span>`).join('');
  const path = c.bookTitle
    ? `<span class="content-path">📍 ${esc(c.bookTitle)}${c.parentCatalogName ? ' · ' + esc(c.parentCatalogName) : ''}${c.catalogName ? ' · ' + esc(c.catalogName) : ''}</span>` : '';
  const fileLink = (c.contentType === 'WORD' || c.contentType === 'EXCEL')
    ? `<a class="tag" style="cursor:pointer" onclick="event.stopPropagation();downloadRawFile(${c.id})">⬇ 原文件</a>` : '';
  const sugg = (c.suggestions || []).slice(0, 3);
  const quick = sugg.length ? `
    <div class="quick-actions">
      <span class="qa-label">引力推荐 · 一键归档</span>
      <div class="qa-btns">
        ${sugg.map((s, i) => `
          <button class="qa-btn ${i === 0 ? 'primary' : ''}"
            onclick="confirmSuggestion(${c.id}, ${s.typeId})"
            title="${s.client ? '喵藏客户端的第一层分类' : '场强 ' + s.score}">
            ${i === 0 ? '★ ' : ''}${esc(s.icon || '📘')} 归入「${esc(s.name)}」${s.client ? ' ·客户端' : ''}
          </button>`).join('')}
        <button class="qa-btn ghost" onclick="openDetail(${c.id})">其他去处…</button>
      </div>
    </div>` : '';
  return `
    <div class="content-card">
      <div class="content-icon">${TYPE_ICONS[c.contentType] || '📝'}</div>
      <div class="content-main">
        <div class="content-title" onclick="openDetail(${c.id})">${esc(c.title)}</div>
        <div class="content-summary md-body">${mdLite(c.summary || '')}</div>
        <div class="content-meta">${tags}${path}${fileLink}<span class="content-path">${fmtDate(c.createdAt)}</span></div>
        ${c.status === 'PENDING' ? quick : ''}
      </div>
      <div class="content-actions">
        <button class="btn sm" onclick="openMoveModal(${c.id})">移动</button>
        <button class="btn sm" onclick="reclassify(${c.id})">重新分类</button>
        <button class="btn sm danger" onclick="deleteContent(${c.id})">删除</button>
      </div>
    </div>`;
}

/* ================= 详情 ================= */

async function openDetail(id) {
  /* 打开新详情前清掉可能残留的内嵌浏览器覆盖层 */
  if (typeof closeWebBrowse === 'function') closeWebBrowse($('detailBody'));
  state.detailId = id;
  const c = await api('/api/contents/' + id);
  $('detailTitle').textContent = c.title;
  const sugg = (c.suggestions || []).slice(0, 5).map((s) =>
    `<span class="suggest-chip" onclick="confirmSuggestion(${c.id}, ${s.typeId})">${esc(s.icon || '')} ${esc(s.name)}（${s.client ? '客户端' : s.score}）</span>`).join('');
  $('detailBody').innerHTML = `
    <div class="detail-meta">
      <span class="type-chip" style="background:#8b8375">${TYPE_ICONS[c.contentType] || ''} ${esc(c.contentType)}</span>
      ${c.bookTitle ? `<span class="tag">📍 ${esc(c.bookTitle)}${c.catalogName ? ' · ' + esc(c.catalogName) : ''}</span>` : '<span class="tag">收集箱</span>'}
      ${(c.matchedTags || '').split(',').filter(Boolean).map((t) => `<span class="tag">#${esc(t)}</span>`).join('')}
      <span class="content-path">${fmtDate(c.createdAt)}</span>
    </div>
    ${c.contentType === 'URL' && c.source ? `<div class="detail-source">🔗 <a href="${esc(c.source)}" target="_blank" rel="noopener">${esc(c.source)}</a></div><br>` : ''}
    <div class="detail-text md-body">${mdFinal(c.rawText || c.summary || '（无正文）')}</div>
    ${sugg ? `<label style="margin-top:12px">引力波建议（按场强排序）</label><div>${sugg}</div>` : ''}
    ${extractBlock(c)}`;
  $('detailActions').innerHTML = `
    <button class="btn sm" onclick="editContent(${c.id})">✏️ 编辑</button>
    <button class="btn sm" onclick="closeModal('detailModal');openMoveModal(${c.id})">移动</button>
    <button class="btn sm" onclick="reclassify(${c.id})">重新分类</button>
    ${c.hasRawFile
      ? `<a class="btn sm" style="text-decoration:none;cursor:pointer" onclick="downloadRawFile(${c.id})" title="下载上传时的原始文件">⬇ 源文件</a>`
      : `<button class="btn sm" onclick="exportContentRaw(${c.id})" title="把标题+正文导出为 Markdown 文件">⬇ 导出 MD</button>`}
    <button class="btn sm danger" onclick="deleteContent(${c.id})">删除</button>`;
  $('detailModal').style.display = 'flex';
  /* 正文里的相对路径图片按内容在书库里的落点解析（wsPath/wsCatId 由 /api/contents/{id} 下发）：
     md 里的路径相对 md 所在目录，浏览器直接解析必然 404，这里逐张取 base64 回填；
     同时把 mermaid 代码块填成图（mdFinal 只放图位，渲染是异步的） */
  const dText = $('detailBody').querySelector('.detail-text');
  if (dText) {
    if (c.wsPath) wsInlineImages(dText, c.wsPath, c.wsCatId);
    mmdHydrate(dText);
  }
}

/* ================= 喵喵内容提取 ================= */

/* 内容详情弹窗的「喵喵提取」区：喵喵提取 = 喵喵整理（读厚产卡）。
   c.tidy 由后端指纹判定（/api/contents/{id}）——这篇文章被整理管线读过且反追踪出卡片时，
   展示「喵喵整理 · 已读厚 N 张卡片」（所属整理书 + 主题行，点击弹卡片详情），不再显示「让喵喵提取」按钮；
   没被整理过才出现提取按钮。extract 成果（LLM 摘要）存在时照常展示 */
function extractBlock(c) {
  const ex = c.extract;
  const tidy = c.tidy || {};
  const tidyCards = tidy.cards || [];
  let html = '';
  if (tidyCards.length) {
    html += `<label style="margin-top:12px">🐾 喵喵整理 <span class="de-count">已读厚 ${tidyCards.length} 张卡片</span></label>
      <div class="extract-box tidy-rows">${tidyCards.map((tc) => `
        <div class="de-row" onclick="openPointTidyCard(${tc.catId}, '${esc(tc.no || '')}')" title="点击查看卡片详情">
          ${tc.bookTitle ? `<span class="de-book">📚 《${esc(tc.bookTitle)}》</span>` : ''}
          <span class="de-topic">${esc(tc.topic || '未命名卡')}</span>
          ${tc.no ? `<span class="de-no">${esc(tc.no)}</span>` : ''}
        </div>`).join('')}</div>`;
  } else if (tidy.tidied) {
    html += `<label style="margin-top:12px">🐾 喵喵整理</label>
      <div class="extract-box muted">喵已读过这篇内容，但还没沉淀出理解卡片</div>`;
  }
  if (ex && ex.summary) {
    html += extractResultBlock(c, ex);
  } else if (!html) {
    html += `<label style="margin-top:12px">🐱 喵喵提取</label>
      <div class="extract-box muted">${c.extractRunning
        ? '🐾 喵喵正在读这条内容…（提取完成后重新打开详情即可看到）'
        : `<button class="btn sm" onclick="reextract(${c.id})">🐾 让喵喵提取这条内容</button>`}</div>`;
  }
  return html;
}

/* 详情弹窗「喵喵整理」卡片行点击：确保该喵卡集已就位（openPointDetail 的关联跳转依赖），再弹卡片详情 */
async function openPointTidyCard(catId, no) {
  if (!state.readingCards || state.readingCatId !== catId) {
    state.readingCards = await api('/api/cats/' + catId + '/reading/cards').catch(() => []);
    state.readingCatId = catId;
  }
  openPointDetail(catId, no);
}

/* 喵喵提取的 LLM 成果区（摘要/要点/金句/标签/建议/相关卡） */
function extractResultBlock(c, ex) {
  const keyPoints = (ex.keyPoints || []).map((k) => `<li>${esc(k)}</li>`).join('');
  const quotes = (ex.quotes || []).map((q) => `<div class="extract-quote">❝ ${esc(q)} ❞</div>`).join('');
  const tags = (ex.tags || []).map((t) => `<span class="tag">#${esc(t)}</span>`).join('');
  const sg = ex.suggest || {};
  const suggestHtml = sg.reason
    ? `<div class="extract-suggest">📌 ${sg.bookId && sg.bookTitle ? `建议归入 <b>《${esc(sg.bookTitle)}》</b>` : '建议保持现状'}：${esc(sg.reason)}</div>`
    : '';
  const related = (ex.related || []).map((r) =>
    `<span class="suggest-chip" onclick="openDetail(${r.contentId})">📎 ${esc(r.title)}</span>`).join('');
  const applied = ex.applied ? '<span class="tag ok">✅ 已采纳归类</span>' : '';
  return `<label style="margin-top:12px">🐱 喵喵提取 <span class="content-path">${esc(ex.mode === 'live' ? '真实模型' : '模拟输出')} · ${esc(ex.extractedAt || '')}</span></label>
    <div class="extract-box">
      <div class="extract-summary">${esc(ex.summary)}</div>
      ${keyPoints ? `<ul class="extract-points">${keyPoints}</ul>` : ''}
      ${quotes}
      ${tags ? `<div class="extract-tags">${tags}</div>` : ''}
      ${suggestHtml}
      ${related ? `<div style="margin-top:6px">${related}</div>` : ''}
      <div class="extract-actions">
        ${sg.bookId && !ex.applied ? `<button class="btn sm primary" onclick="applyExtract(${c.id})">✅ 采纳归类建议</button>` : ''}
        ${(ex.tags || []).length && !ex.applied ? `<button class="btn sm" onclick="acceptExtractTags(${c.id})">🏷 采纳标签</button>` : ''}
        <button class="btn sm" onclick="ignoreExtract(${c.id})">🙈 忽略</button>
        <button class="btn sm danger" onclick="clearExtractData(${c.id})">清空</button>
        ${applied}
      </div>
    </div>`;
}

async function reextract(id) {
  try {
    toast('🐾 喵喵开始提取…（网页链接会先抓取文章，可能需要十几秒）');
    await api('/api/agent/extract/' + id, { method: 'POST' });
    toast('✅ 提取完成');
    loadShelf();
    openDetail(id);
  } catch (e) { toast(e.message, true); }
}

async function applyExtract(id) {
  try {
    const r = await api(`/api/agent/extract/${id}/apply`, { method: 'POST' });
    toast(`✅ 已归入《${r.bookTitle}》`);
    loadShelf();
    openDetail(id);
  } catch (e) { toast(e.message, true); }
}

async function acceptExtractTags(id) {
  try {
    await api(`/api/agent/extract/${id}/accept-tags`, { method: 'POST' });
    toast('✅ 已合入标签');
    loadShelf();
    openDetail(id);
  } catch (e) { toast(e.message, true); }
}

async function ignoreExtract(id) {
  try {
    await api(`/api/agent/extract/${id}/feedback`, { method: 'POST', body: { action: 'ignore' } });
    toast('已记录：主人忽略了这次提取（喵喵会记住）');
  } catch (e) { toast(e.message, true); }
}

async function clearExtractData(id) {
  try {
    await api('/api/agent/extract/' + id, { method: 'DELETE' });
    toast('已清空提取结果');
    openDetail(id);
  } catch (e) { toast(e.message, true); }
}

/* ================= 喵喵技能（每只猫的预设配置文件） ================= */

let skillsCache = [];
let skillsCatId = null;

async function openSkills(catId) {
  skillsCatId = Number(catId);
  const cat = (state.shelf?.cats || []).find((c) => c.id === skillsCatId);
  $('skillsCatName').textContent = cat ? '· ' + cat.name : '';
  $('skillsModal').style.display = 'flex';
  await renderSkills();
}

async function renderSkills() {
  skillsCache = await api(`/api/cats/${skillsCatId}/skills`);
  const kindLabel = { EXTRACT: '规矩', PREFERENCE: '喜好' };
  const srcLabel = { preset: '内置', miaomiao: '喵喵生成', user: '主人手写' };
  $('skillList').innerHTML = skillsCache.map((s) => `
    <div class="skill-card ${s.enabled ? '' : 'off'}">
      <div class="skill-head">
        <b>${kindLabel[s.kind] || s.kind} · ${esc(s.name)}</b>
        <span class="tag">${srcLabel[s.source] || s.source}</span>
        <span class="content-path">用过 ${s.usageCount} 次</span>
      </div>
      ${s.description ? `<div class="skill-desc">${esc(s.description)}</div>` : ''}
      <div class="skill-content">${esc(s.content)}</div>
      <div class="extract-actions">
        <button class="btn sm" onclick="toggleSkill(${s.id})">${s.enabled ? '⏸ 停用' : '▶ 启用'}</button>
        <button class="btn sm" onclick="editSkill(${s.id})">✏️ 编辑</button>
        <button class="btn sm danger" onclick="removeSkill(${s.id})">删除</button>
      </div>
    </div>`).join('') || '<div class="empty">这只猫还没有技能，先在下面加一条吧</div>';
}

let editingSkillId = null;

function editSkill(id) {
  const s = skillsCache.find((x) => x.id === id);
  if (!s) return;
  editingSkillId = id;
  $('skillKind').value = s.kind;
  $('skillName').value = s.name;
  $('skillContent').value = s.content || '';
  $('skillDescription').value = s.description || '';
  toast('编辑模式：修改后点「保存技能」');
}

async function toggleSkill(id) {
  try { await api(`/api/cats/${skillsCatId}/skills/${id}/toggle`, { method: 'POST' }); renderSkills(); }
  catch (e) { toast(e.message, true); }
}

async function removeSkill(id) {
  try { await api(`/api/cats/${skillsCatId}/skills/${id}`, { method: 'DELETE' }); renderSkills(); }
  catch (e) { toast(e.message, true); }
}

async function submitSkill() {
  const name = $('skillName').value.trim();
  const content = $('skillContent').value.trim();
  if (!name || !content) return toast('技能名和内容都要填', true);
  const body = {
    kind: $('skillKind').value, name, content,
    description: $('skillDescription').value.trim() || null,
  };
  if (editingSkillId) body.id = editingSkillId;
  try {
    await api(`/api/cats/${skillsCatId}/skills`, { method: 'POST', body });
    editingSkillId = null;
    $('skillName').value = ''; $('skillContent').value = ''; $('skillDescription').value = '';
    toast('✅ 技能已保存，下次提取就会生效');
    renderSkills();
  } catch (e) { toast(e.message, true); }
}

/* ================= 采集 ================= */

function openCollect() {
  /* 投入内容入口都在图谱视图：带上当前喵（谁投的算谁的，引力也只在该喵可用分类内打分） */
  state.collectCatId = state.graphCatId || null;
  $('collectModal').style.display = 'flex'; $('collectHint').textContent = '';
}
function switchCollectTab(tab) {
  document.querySelectorAll('#collectModal .tab').forEach((t) => t.classList.toggle('active', t.dataset.tab === tab));
  ['url', 'text', 'file'].forEach((k) => ($('pane-' + k).style.display = k === tab ? '' : 'none'));
}

async function submitCollect() {
  const tab = document.querySelector('#collectModal .tab.active').dataset.tab;
  try {
    let c;
    if (tab === 'url') {
      const url = $('collectUrl').value.trim();
      if (!url) return toast('请输入链接', true);
      $('collectHint').textContent = '正在抓取网页并引力归档…';
      c = await api('/api/collect/url', { method: 'POST', body: { url, catId: state.collectCatId || '' } });
    } else {
      const text = $('collectTextBody').value;
      if (!text.trim()) return toast('请输入文本', true);
      c = await api('/api/collect/text', { method: 'POST', body: { title: $('collectTextTitle').value, text, catId: state.collectCatId || '' } });
    }
    afterCollect(c);
  } catch (e) { toast(e.message, true); $('collectHint').textContent = ''; }
}

async function collectFileNow(input) {
  if (!input.files.length) return;
  const fd = new FormData();
    fd.append('file', input.files[0]);
    if (state.collectCatId) fd.append('catId', state.collectCatId);
  $('collectHint').textContent = '正在解析文件并引力归档…';
  try {
    const c = await api('/api/collect/file', { method: 'POST', body: fd });
    input.value = '';
    afterCollect(c);
  } catch (e) { toast(e.message, true); $('collectHint').textContent = ''; }
}

function afterCollect(c) {
  $('collectHint').textContent = '';
  closeModal('collectModal');
  $('collectUrl').value = ''; $('collectTextBody').value = ''; $('collectTextTitle').value = '';
  if (c.status === 'CLASSIFIED') {
    const path = [c.bookTitle, c.parentCatalogName, c.catalogName].filter(Boolean).join(' · ');
    toast(`✅ 已归入 ${path}`);
  } else {
    toast('📥 引力不足，已放入收集箱，可手动归档');
  }
  loadShelf();
  /* 投完留在原来那张图上：必须把当前喵带回去，不然 openGraph() 会把 graphCatId 清空、跳回全库总览 */
  if (state.view === 'graph') { if (state.drawerBookId) openDrawerBook(state.drawerBookId); else openGraph(state.graphCatId); }
  else if (state.view === 'book' && c.bookId === state.bookId) openBook(state.bookId);
  else if (state.view === 'inbox') openInbox();
}

