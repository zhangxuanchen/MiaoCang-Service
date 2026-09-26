/* ================= Markdown 导出：多选 → 排队生成 → JSZip 打包下载 ================= */
const EXPORT = { items: [], running: false };
/* 文件名安全化：去非法字符与空白，截断防超长 */
function exportSanitize(s) {
  return String(s || '').replace(/[\\/:*?"<>|\r\n\t]+/g, ' ').replace(/\s+/g, '-').replace(/^-+|-+$/g, '').slice(0, 50) || 'untitled';
}
function downloadText(name, text) {
  const blob = new Blob([text], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = name; a.click();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
}
/* 原文件下载：/api/files/{id} 需要登录态——<a href> 直链导航不带 Bearer 头会 401 跳登录页，
   改为 fetch 带 token 拿 blob 后触发下载；文件名从 Content-Disposition（后端 URL 编码）解析 */
async function downloadRawFile(id) {
  try {
    const r = await fetch(`/api/files/${id}`, { headers: authHeaders() });
    if (!r.ok) {
      const err = await r.json().catch(() => ({}));
      throw new Error(err.error || ('HTTP ' + r.status));
    }
    const cd = r.headers.get('Content-Disposition') || '';
    const m = cd.match(/filename\*=UTF-8''([^;]+)/);
    const name = m ? decodeURIComponent(m[1]) : '原文件';
    const blob = await r.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = name;
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
    toast('已开始下载「' + name + '」', false);
  } catch (e) { toast('下载失败: ' + e.message, true); }
}
/* —— 生成器：理解卡片 → md —— */
function exportCardMd(c) {
  const km = KIND_META[c.kind] || KIND_META.FACT;
  let md = `# ${c.point || c.topic || '未命名知识点'}\n\n`;
  md += `- **编号**：${c.no || '-'}\n- **类型**：${km.icon} ${km.label}\n- **主题**：${c.topic || '-'}\n- **所属书**：${c.bookTitle || '-'}\n`;
  if (c.chapter || c.section) md += `- **所在章节**：${[c.chapter, c.section].filter(Boolean).join(' / ')}\n`;
  if ((c.usage || []).length) md += `- **使用场景**：${c.usage.join('、')}\n`;
  if ((c.links || []).length) md += `- **关联知识点**：${c.links.join('、')}\n`;
  if ((c.sources || []).length) md += `- **来源文章**：${c.sources.map((s) => s.title).join('、')}\n`;
  md += '\n';
  if (c.detail) md += `## 展开\n\n${c.detail}\n`;
  return md;
}
/* —— 生成器：内容原文 → md —— */
function exportContentMd(d) {
  let md = `# ${d.title || '未命名'}\n\n`;
  md += `- **摘要**：${d.summary || '-'}\n- **类型**：${d.contentType || '-'}\n`;
  if (d.bookTitle) md += `- **归属**：${d.bookTitle}\n`;
  md += '\n---\n\n';
  md += (d.rawText || d.summary || '（无正文）') + '\n';
  return md;
}
/* —— 生成器：分类节点 → md —— */
function exportTypeMd(t, contents) {
  let md = `# ${t.icon || ''} ${t.name}\n\n`;
  if (t.description) md += `${t.description}\n\n`;
  md += `- **分类性质**：${t.preset ? '预设置分类' : '自定义分类'}${t.shared ? '（全体喵共享）' : ''}\n`;
  md += `- **本喵名下内容**：${contents.length} 条\n\n`;
  if (contents.length) {
    md += '## 内容清单\n\n';
    contents.forEach((c) => { md += `- ${c.title}\n`; });
  } else {
    md += '这个分类在本喵名下暂无内容。\n';
  }
  return md;
}
/* —— 入口①：喵的整理（卡片 + 书） —— */
function openExportReading() {
  const catId = state.readingCatId;
  const items = [];
  (state.readingBooks || []).forEach((b) => {
    if (!(state.readingCards || []).some((c) => c.file === b.file)) return; /* 没卡的书不上架也不导出 */
    items.push({
      group: '整理书', key: 'book:' + b.file, label: `📖 《${b.title}》`,
      name: 'reading/' + exportSanitize(b.title) + '.md',
      run: async () => (await api(`/api/cats/${catId}/reading/file?name=${encodeURIComponent(b.file)}`)).markdown,
    });
  });
  (state.readingCards || []).forEach((c) => items.push({
    group: '理解卡片', key: 'card:' + (c.no || c.id),
    label: `🧩 [${(KIND_META[c.kind] || KIND_META.FACT).label}] ${String(c.point || '').slice(0, 38)}`,
    name: 'cards/' + exportSanitize((c.no || 'card') + '-' + c.point) + '.md',
    run: () => exportCardMd(c),
  }));
  openExportModal(items, '⬇ 导出喵的整理');
}
/* —— 入口②：我养的喵（分类节点 + 内容原文） —— */
function openExportGraph() {
  const items = [];
  (state.shelf.types || []).forEach((t) => {
    const gc = (state.graphContents || []).filter((c) => (t.books || []).some((b) => b.id === c.bookId));
    items.push({
      group: '分类节点', key: 'type:' + t.id,
      label: `${t.shared ? '🌐 ' : ''}${t.icon || ''} ${t.name}（${gc.length} 条内容）`,
      name: 'graph/' + exportSanitize(t.name) + '.md',
      run: () => exportTypeMd(t, gc),
    });
  });
  (state.graphContents || []).forEach((c) => items.push({
    group: '内容原文', key: 'content:' + c.id, label: `📄 ${c.title}`,
    name: 'contents/' + exportSanitize(c.title) + '.md',
    run: async () => exportContentMd(await api('/api/contents/' + c.id)),
  }));
  openExportModal(items, '⬇ 导出知识图谱');
}
/* —— 通用弹窗：分组多选 + 数量提示 —— */
function openExportModal(items, title) {
  if (!items.length) { toast('没有可导出的内容', true); return; }
  EXPORT.items = items;
  EXPORT.running = false;
  const groups = {};
  items.forEach((it) => { (groups[it.group] = groups[it.group] || []).push(it); });
  let gi = 0;
  $('exportGroups').innerHTML = Object.keys(groups).map((g) => {
    gi++;
    return `<div class="export-group">
      <label class="export-check all"><input type="checkbox" checked onchange="toggleExportGroup(${gi}, this.checked)"> ${esc(g)} <span class="ex-gcount">(${groups[g].length})</span></label>
      ${groups[g].map((it) => `<label class="export-check"><input type="checkbox" class="ex-item" data-g="${gi}" checked value="${esc(it.key)}" onchange="updateExportSummary()"> <span class="ex-label">${esc(it.label)}</span></label>`).join('')}
    </div>`;
  }).join('');
  $('exportModalTitle').textContent = title || '⬇ 导出 Markdown';
  updateExportSummary();
  $('exportHint').textContent = '';
  $('exportModal').style.display = 'flex';
}
function updateExportSummary() {
  const boxes = [...document.querySelectorAll('#exportGroups .ex-item')];
  const n = boxes.filter((b) => b.checked).length;
  $('exportSummary').innerHTML = `将导出 <b>${n}</b> 个 Markdown 文件，排队生成后打包为 <b>1</b> 个 zip 下载`;
  $('exportSubmitBtn').disabled = !n || EXPORT.running;
}
function toggleExportGroup(gi, on) {
  document.querySelectorAll(`#exportGroups .ex-item[data-g="${gi}"]`).forEach((b) => { b.checked = on; });
  updateExportSummary();
}
/* —— 排队引擎：逐项生成（原文/书文件需逐个请求），失败跳过不中断，最后 zip 下载 —— */
async function runExportQueue() {
  const keys = new Set([...document.querySelectorAll('#exportGroups .ex-item')].filter((b) => b.checked).map((b) => b.value));
  const picked = EXPORT.items.filter((it) => keys.has(it.key));
  if (!picked.length || EXPORT.running) return;
  EXPORT.running = true;
  const btn = $('exportSubmitBtn');
  btn.disabled = true;
  const zip = new JSZip();
  const used = {};
  let ok = 0, fail = 0;
  for (let i = 0; i < picked.length; i++) {
    const it = picked[i];
    $('exportHint').textContent = `排队导出中 ${i + 1}/${picked.length}：${it.label}`;
    await new Promise((r) => setTimeout(r, 0)); /* 让进度先渲染再干活 */
    try {
      const md = await it.run();
      let name = it.name;
      if (used[name]) name = name.replace(/\.md$/, `-${used[name] + 1}.md`); /* 重名追加序号 */
      used[it.name] = (used[it.name] || 0) + 1;
      zip.file(name, md);
      ok++;
    } catch (e) { fail++; }
  }
  $('exportHint').textContent = '打包 zip 中…';
  const blob = await zip.generateAsync({ type: 'blob' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const ts = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '');
  a.href = url;
  a.download = `miaocang-export-${ts}.zip`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 3000);
  toast(fail ? `⚠️ 已导出 ${ok} 个文件，${fail} 个失败` : `✅ 已导出 ${ok} 个文件，zip 已开始下载`);
  EXPORT.running = false;
  closeModal('exportModal');
}
/* —— 单条原文导出（内容详情弹窗） —— */
async function exportContentRaw(id) {
  try {
    const d = await api('/api/contents/' + id);
    downloadText(exportSanitize(d.title) + '.md', exportContentMd(d));
    toast('✅ 原文 Markdown 已开始下载');
  } catch (e) { toast(e.message, true); }
}

/* ============================================================
   喵的日记（照 Memory-Observatory 三页面移植：喵喵记录/喵喵消耗/喵喵问题）
   数据源：专属工作区 Agent 会话管线的旁路埋点 → /api/v1/*
   ============================================================ */

/* 喵的日记共享状态：选中喵 + 各页面自己的筛选/分页 */
state.diary = { agents: null, agent: '', records: { tab: 'diary', offset: 0, total: 0, keyword: '', sessionId: '', pickedTurn: '' }, tokens: { days: 7 }, problems: { days: 7, sev: '' } };

const DIARY_SEV = {
  'skill-error': 'danger', 'turn-token': 'danger', 'skill-repeat': 'danger',
  'turn-latency': 'warn', 'turn-count': 'warn', 'turn-tool': 'warn', 'tool-token': 'warn',
  'skill-slow': 'warn', 'model-slow': 'warn', 'session-token': 'warn', 'mem-churn': 'info'
};

/* 进入喵的日记某页（records/tokens/problems），左侧菜单高亮 + 主区域渲染 */
async function openDiary(page) {
  state.view = 'diary-' + page;
  setNav();
  window.scrollTo(0, 0);
  $('main').innerHTML = '<div class="reading-loading">📓 正在翻看喵的日记…</div>';
  try { await ensureDiaryAgents(); } catch (e) { toast(e.message, true); }
  if (page === 'records') renderDiaryRecords();
  else if (page === 'tokens') renderDiaryTokens();
  else renderDiaryProblems();
}

/* 拉一次 Agent 列表（= 各工作区，带猫信息），默认选最近活跃的一只 */
async function ensureDiaryAgents() {
  if (state.diary.agents) return;
  const d = await api('/api/v1/agents');
  state.diary.agents = d.agents || [];
  if (!state.diary.agent && state.diary.agents.length) {
    state.diary.agent = state.diary.agents[0].agentId;
  }
}

/* 喵选择下拉（胶囊样式） */
function diaryAgentSelect(onchange) {
  const agents = state.diary.agents || [];
  return `<label class="diary-agent"><span>喵</span><select onchange="${onchange}">` +
    (agents.length ? agents.map((a) => `<option value="${esc(a.agentId)}" ${a.agentId === state.diary.agent ? 'selected' : ''}>${esc(a.catIcon || '🐱')} ${esc(a.catName || a.agentId)}</option>`).join('') : '<option value="">暂无数据</option>') +
    `</select></label>`;
}

function diaryAgentName(id) {
  const a = (state.diary.agents || []).find((x) => x.agentId === id);
  return a ? `${a.catIcon || '🐱'} ${a.catName || id}` : id;
}

/* agentId（工作区路径，如 admin/cat-1）拼 URL 时 / 换成 ~（后端 decAgent 还原） */
function diaryAgentPath(id) { return String(id).split('/').join('~'); }

function fmtTok(n) {
  n = Number(n) || 0;
  if (n >= 1000000) return (n / 1000000).toFixed(1) + 'M';
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k';
  return String(n);
}

function fmtMs(n) {
  n = Number(n) || 0;
  if (n >= 1000) return (n / 1000).toFixed(1) + 's';
  return Math.round(n) + 'ms';
}

const LAYER_LABEL = { model: '🧠 模型', text: '💬 正文', skill: '🔧 工具', session: '📖 轮次', provider: '☁️ 提供方', agent: '🤖 Agent', control: '🎚 控制', hitl: '🤝 协同' };

function layerBadge(l) { return `<span class="diary-layer layer-${esc(l)}">${LAYER_LABEL[l] || esc(l)}</span>`; }
function opBadge(o) { return `<span class="diary-op op-${esc(o)}">${esc(o)}</span>`; }

