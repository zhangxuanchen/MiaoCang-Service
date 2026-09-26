/* ================= 我的学习 ================= */

/* 搜索/主题页命中条目的类型元数据 */
const STUDY_TYPE_META = { point: ['🃏', '知识点'], book: ['📚', '书'], learn: ['🎓', '学习卡'], content: ['📄', '文章'] };

/* 当前猫 id：优先阅读/图谱上下文，兜底第一只猫 */
function studyCatId() {
  return state.readingCatId || state.graphCatId || ((state.shelf && state.shelf.cats[0]) || {}).id;
}

/* 主题页：已收录条目（钉选）+ 关键词实时检索聚合 */
async function openStudyTopic(id) {
  state.view = 'study';
  state.studyTopicId = id;
  setNav(null);
  const [d, cards] = await Promise.all([
    api('/api/study/topics/' + id + '/page').catch((e) => { toast(e.message, true); return null; }),
    api('/api/cats/' + studyCatId() + '/reading/cards').catch(() => []),
  ]);
  if (!d) return;
  state.readingCards = cards;
  $('main').innerHTML = renderStudyPage(d);
}

function renderStudyPage(d) {
  const t = d.topic;
  state.studyPageTopic = t;
  const kws = (t.keywords || '').split(/[，,]/).filter((x) => x.trim());
  const pinned = d.pinned || [];
  const hits = d.hits || [];
  return `<div class="reading-page">
    <div class="view-head graph-head">
      <div class="graph-head-left"><button class="btn back" onclick="goHome()">← 返回书房</button>
        <div class="kicker">Topic · 我的专题</div></div>
      <div class="head-actions">
        <button class="btn" onclick="openStudyModal('edit', ${t.catId}, ${t.id})">✏️ 编辑主题</button>
      </div>
    </div>
    <div class="reading-hero">
      <div class="kicker">一个主题 · 一把检索的网</div>
      <h2>${esc(t.icon || '🧠')} ${esc(t.name)}</h2>
      ${t.description ? `<p class="tp-desc">${esc(t.description)}</p>` : ''}
      <p class="lead">「已收录」是主人亲手钉下的条目；「检索聚合」按下面关键词从全库（知识点/书/学习卡）实时捞回相关内容。</p>
      <div class="study-kws">
        ${kws.map((k) => `<span class="uc-chip">🔎 ${esc(k.trim())}</span>`).join('') || '<span class="study-kw-empty">还没设关键词</span>'}
        <button class="btn sm" onclick="openStudyModal('edit', ${t.catId}, ${t.id})">✏️ 改关键词</button>
      </div>
    </div>
    <h3 class="study-sec-title">📌 已收录 · ${pinned.length}</h3>
    ${pinned.length ? `<div class="study-hits">${pinned.map((p) => `
      <div class="study-hit">
        <span class="sh-type">${(STUDY_TYPE_META[p.itemType] || ['📄', p.itemType]).join('')}</span>
        <span class="sh-title" onclick="studyJump('${p.itemType}', '${p.itemType}:${esc(p.itemKey)}')">${esc(p.title || p.itemKey)}${p.pinnedNote ? ` <small>${esc(p.pinnedNote)}</small>` : ''}</span>
        <button class="btn sm danger" onclick="studyUnpin(${t.id}, '${p.itemType}', '${esc(p.itemKey)}')" title="移出主题">✕</button>
      </div>`).join('')}</div>` : '<div class="empty">还没有收录条目。在知识点详情或搜索结果里点「＋ 收录」。</div>'}
    <h3 class="study-sec-title">🔎 检索聚合 · ${hits.length}${d.indexed ? '' : '（索引未就绪）'}</h3>
    ${hits.length ? `<div class="study-hits">${hits.map((h) => `
      <div class="study-hit">
        <span class="sh-type">${(STUDY_TYPE_META[h.type] || ['📄', h.type]).join('')}</span>
        <span class="sh-title" onclick="studyJump('${h.type}', '${esc(String(h.key))}')">${esc(h.title)}</span>
        <span class="sh-score">${h.score}</span>
        <button class="btn sm" onclick="collectToStudy('${h.type}', '${esc(String(h.key))}', '${esc(h.title)}')">＋ 收录</button>
      </div>`).join('')}</div>` : '<div class="empty">关键词没有命中的内容——改改关键词，或等喵读进更多东西。</div>'}
  </div>`;
}

/* 命中条目跳转：point → 知识点详情；book → 书 md 弹窗 */
async function studyJump(type, key, hitCatId) {
  const k = key.includes(':') ? key.split(':').slice(1).join(':') : key;
  /* 结果携带所属猫 id（全库搜索时跨猫）：优先用结果猫，缺省退回当前猫 */
  const catId = hitCatId || studyCatId();
  if (type === 'point') {
    /* 确保目标猫的卡片就绪：readingCatId 是整理页视图状态不能动，另用按猫缓存池；
       openPointDetail 的关联跳转依赖 state.readingCards 指向同一猫的卡集，故就地指向 */
    state.pointCardsCache = state.pointCardsCache || {};
    const cached = (state.readingCatId === catId && state.readingCards && state.readingCards.length)
      ? state.readingCards : state.pointCardsCache[catId];
    if (cached && cached.length) state.readingCards = cached;
    else {
      state.readingCards = await api('/api/cats/' + catId + '/reading/cards').catch(() => []);
      state.pointCardsCache[catId] = state.readingCards;
    }
    openPointDetail(catId, k);
  } else if (type === 'book') {
    openReadingMd(catId, k);
  } else if (type === 'learn') {
    toast('学习卡在「喵的整理 · 学透」页签复习 🎓');
  } else {
    toast('文章在知识图谱里看 📄');
  }
}

/* 收录到学习主题：弹出当前猫的主题选择 */
async function collectToStudy(type, key, title) {
  const catId = studyCatId();
  const k = key.includes(':') ? key.split(':').slice(1).join(':') : key;
  const topics = ((state.shelf && state.shelf.cats.find((c) => c.id === catId)) || {}).studyTopics || [];
  if (!topics.length) {
    if (confirm('还没有专题，先建一个？')) openStudyModal('create', catId);
    return;
  }
  state.collectPending = { type, key: k, title };
  $('studyModalTitle').innerHTML = '＋ 收录到专题';
  $('studyModalBody').innerHTML = `<div class="st-pick-list">${topics.map((t) => `
    <button class="study-pick" onclick="pickStudyCollect(${t.id})">
      <span class="sp-icon">${esc(t.icon || '🧠')}</span>
      <span class="sp-name">${esc(t.name)}</span>
      ${t.keywords ? `<span class="sp-kw">${esc(t.keywords.split(/[，,]/).filter((x) => x.trim()).slice(0, 2).join(' · '))}</span>` : ''}
    </button>`).join('')}</div>`;
  $('studyModal').style.display = 'flex';
}

async function pickStudyCollect(topicId) {
  const p = state.collectPending;
  if (!p) return;
  try {
    await api('/api/study/topics/' + topicId + '/items', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ itemType: p.type, itemKey: p.key, title: p.title }) });
    toast('已收录 ✓ 记忆 +1');
    closeModal('studyModal');
    if (state.view === 'study') openStudyTopic(state.studyTopicId);
  } catch (e) { toast(e.message, true); }
}

async function studyUnpin(topicId, type, key) {
  const k = key.includes(':') ? key.split(':').slice(1).join(':') : key;
  try {
    await api('/api/study/topics/' + topicId + '/items/' + type + '/' + encodeURIComponent(k), { method: 'DELETE' });
    openStudyTopic(topicId);
  } catch (e) { toast(e.message, true); }
}

/* 新建 / 编辑学习主题（弹窗：emoji 点选 + 分组表单） */
const STUDY_ICONS = ['🧠', '🌐', '🤖', '📚', '💡', '🎯', '🧩', '🛠️', '📈', '🧪', '🔒', '⚙️'];

function pickStudyEmoji(e, btn) {
  state.studyIconPick = e;
  document.querySelectorAll('#stEmojiGrid .st-emoji').forEach((b) => b.classList.remove('on'));
  if (btn) btn.classList.add('on');
}

function stField(label, sub, inner) {
  return `<div class="st-field"><div class="st-label">${label}${sub ? `<small>${sub}</small>` : ''}</div>${inner}</div>`;
}

function stFormHTML(icon, name, kw, desc) {
  return `<div class="st-form">
    ${stField('图标', '点选一个代表这个话题的 emoji',
      `<div class="st-emoji-grid" id="stEmojiGrid">${STUDY_ICONS.map((e) =>
        `<button type="button" class="st-emoji ${e === (icon || '🧠') ? 'on' : ''}" onclick="pickStudyEmoji('${e}', this)">${e}</button>`).join('')}</div>`)}
    ${stField('主题名', '', `<input id="stName" class="st-input" placeholder="给这个话题起个名，如：分布式一致性" maxlength="30" value="${esc(name || '')}">`)}
    ${stField('简介', '一句话说清这个主题想解决什么（类似 skill 的 description，会写进主题 md）',
      `<textarea id="stDesc" class="st-input" rows="2" placeholder="如：围绕读多写少场景，沉淀缓存一致性、穿透击穿与幂等设计的核心结论。">${esc(desc || '')}</textarea>`)}
    ${stField('检索关键词', '逗号分隔，打开主题时按这些词实时聚合全库内容',
      `<input id="stKw" class="st-input" placeholder="如：一致性哈希, Raft, 幂等" value="${esc(kw || '')}">`)}
    <div class="st-hint">💡 简介与关键词随时可改——检索聚合实时跟变，已收录条目不受影响。</div>
  </div>`;
}

function openStudyModal(mode, catId, topicId) {
  if (mode === 'create') {
    state.studyEdit = { mode, catId, topic: null };
    state.studyIconPick = '🧠';
    $('studyModalTitle').innerHTML = '＋ 新建专题';
    $('studyModalBody').innerHTML = stFormHTML('🧠', '', '', '') +
      `<div class="modal-actions"><button class="btn" onclick="closeModal('studyModal')">取消</button><button class="btn primary" onclick="saveStudyModal()">✨ 创建主题</button></div>`;
    setTimeout(() => { const el = $('stName'); if (el) el.focus(); }, 60);
  } else {
    /* 数据源优先级：主题页接口数据（state.studyPageTopic）> 侧栏 shelf */
    const cats = (state.shelf && state.shelf.cats) || [];
    let t = (state.studyPageTopic && state.studyPageTopic.id === topicId) ? state.studyPageTopic : null;
    if (!t) for (const c of cats) { const f = (c.studyTopics || []).find((x) => x.id === topicId); if (f) { t = f; break; } }
    if (!t) return;
    state.studyEdit = { mode, catId, topic: t };
    state.studyIconPick = t.icon || '🧠';
    $('studyModalTitle').innerHTML = '✏️ 编辑专题';
    $('studyModalBody').innerHTML = stFormHTML(t.icon, t.name, t.keywords, t.description) +
      `<div class="modal-actions">
        <button class="btn danger" onclick="deleteStudyTopic(${topicId})">🗑 删除主题</button>
        <span style="flex:1"></span>
        <button class="btn" onclick="closeModal('studyModal')">取消</button>
        <button class="btn primary" onclick="saveStudyModal()">✓ 保存</button>
      </div>`;
  }
  $('studyModal').style.display = 'flex';
}

async function saveStudyModal() {
  const e = state.studyEdit;
  const body = { icon: state.studyIconPick || '🧠', name: $('stName').value.trim(), description: $('stDesc').value.trim(), keywords: $('stKw').value.trim() };
  if (!body.name) { toast('主题名不能为空', true); $('stName').focus(); return; }
  try {
    if (e.mode === 'create') {
      await api('/api/cats/' + e.catId + '/study/topics', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    } else {
      await api('/api/study/topics/' + e.topic.id, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    }
    closeModal('studyModal');
    await refreshShelf();
    if (e.mode === 'edit' && state.view === 'study' && state.studyTopicId === e.topic.id) openStudyTopic(e.topic.id);
    toast('已保存 ✓');
  } catch (err) { toast(err.message, true); }
}

async function deleteStudyTopic(topicId) {
  if (!confirm('删除这个专题？（只删专题与收录关系，不动知识点和书）')) return;
  try {
    await api('/api/study/topics/' + topicId, { method: 'DELETE' });
    closeModal('studyModal');
    state.studyTopicId = null; state.view = 'welcome';
    await refreshShelf();
    goHome();
  } catch (e) { toast(e.message, true); }
}

/* 合并视图：书包裹卡片——每本书一个分组（书头点开书全文），组内平铺该书的理解卡片；
   不属于任何书的卡片进「📦 待归位」。卡片关键信息全保留，书归属由所在分组表达。
   最右侧吸附「卡片目录」：书 → 卡两级树，点击快捷跳到对应卡片。 */
function renderReadingWall(catId, books, cards) {
  if (!books.length && !cards.length) {
    return `<div class="empty">书架还是空的。点「✨ 整理大纲」，喵会精读书库里的每篇文章，把理解沉淀成一张张卡片、归纳进一本本书。</div>`;
  }
  const byFile = {};
  const loose = [];
  (cards || []).forEach((c) => {
    /* 分组键 = 书 md 文件名（卡片归属真值）：大纲书只有 file 没有 id，卡片的 file 指向所属书 md */
    if (c.file && books.some((b) => b.file === c.file)) (byFile[c.file] = byFile[c.file] || []).push(c);
    else loose.push(c);
  });
  let html = books.map((b) => {
    const list = byFile[b.file] || [];
    if (!list.length) return ''; /* 没卡的书不上架：摆空书只会让书架显得杂乱 */
    return `
    <section class="wall-book">
      <header class="wb-head" onclick="openReadingMd(${catId}, '${esc(b.file)}')" title="打开这本书的全文">
        <div class="wb-title">《${esc(b.title)}》<span class="wb-count">${list.length} 卡</span></div>
        ${b.description ? `<div class="wb-desc">${esc(b.description)}</div>` : ''}
        <div class="wb-file">📄 ${esc(b.file)}</div>
      </header>
      <div class="cards-wall">${list.map((c) => ucCard(catId, c)).join('')}</div>
    </section>`;
  }).join('');
  if (loose.length) {
    html += `
    <section class="wall-book loose">
      <header class="wb-head static"><div class="wb-title">📦 待归位 <span class="wb-count">${loose.length} 卡</span></div></header>
      <div class="cards-wall">${loose.map((c) => ucCard(catId, c)).join('')}</div>
    </section>`;
  }
  if (!html) {
    return `<div class="empty">${books.length ? '书架上的书都还没有卡片。点「✨ 增量归纳」，喵会补读归位。' : '还没有书。点「✨ 整理大纲」，喵会精读库里的每篇文章，把理解归纳成一本本书。'}</div>`;
  }
  return `<div class="reading-tidy-wrap"><div class="reading-tidy-main">${html}</div>${renderCardToc(books, cards)}</div>`;
}

/* 卡片目录（右侧吸附）：书 → 卡两级树，点击跳到卡片并高亮；滚动时自动跟随当前视口卡片 */
function renderCardToc(books, cards) {
  const byFile = {};
  const loose = [];
  (cards || []).forEach((c) => {
    if (c.file && books.some((b) => b.file === c.file)) (byFile[c.file] = byFile[c.file] || []).push(c);
    else loose.push(c);
  });
  const item = (c) => `<button class="toc-item" data-toc-card="${c.id}" onclick="jumpToCard(${c.id})">${c.no ? `<b>${esc(c.no)}</b>` : ''}${esc(c.topic || '未命名卡')}</button>`;
  const groups = books.map((b) => {
    const list = byFile[b.file] || [];
    if (!list.length) return '';
    return `<details class="toc-book" open><summary title="${esc(b.title)}">《${esc(b.title)}》<span>${list.length}</span></summary>${list.map(item).join('')}</details>`;
  }).join('');
  const looseGrp = loose.length
    ? `<details class="toc-book loose"><summary>📦 待归位<span>${loose.length}</span></summary>${loose.map(item).join('')}</details>`
    : '';
  return `<aside class="card-toc" id="cardToc"><div class="toc-head">📑 卡片目录</div>${groups}${looseGrp}</aside>`;
}

/* 跳转到某张卡：滚动定位 + 闪烁高亮 + 目录条目标记为当前 */
function jumpToCard(cardId) {
  const el = document.getElementById('uc-' + cardId);
  if (!el) return;
  el.scrollIntoView({ behavior: 'smooth', block: 'center' });
  el.classList.remove('flash');
  void el.offsetWidth; /* 重置动画 */
  el.classList.add('flash');
  setTimeout(() => el.classList.remove('flash'), 1600);
  markTocActive(cardId);
}
function markTocActive(cardId) {
  document.querySelectorAll('.toc-item.active').forEach((x) => x.classList.remove('active'));
  const t = document.querySelector(`.toc-item[data-toc-card="${cardId}"]`);
  if (t) {
    t.classList.add('active');
    t.scrollIntoView({ block: 'nearest' });
    const d = t.closest('details');
    if (d) d.open = true;
  }
}
/* 滚动跟随：视口内最靠上的可见卡 = 当前卡（渲染后调用，页面切换后自动重绑） */
let tocObserver = null;
function bindTocObserver() {
  if (tocObserver) tocObserver.disconnect();
  const cards = document.querySelectorAll('.reading-tidy-main .uc-card[id^="uc-"]');
  if (!cards.length) return;
  tocObserver = new IntersectionObserver((entries) => {
    let top = null;
    entries.forEach((e) => {
      if (e.isIntersecting && (top === null || e.target.getBoundingClientRect().top < top.getBoundingClientRect().top)) top = e.target;
    });
    if (top) markTocActive(top.id.replace('uc-', ''));
  }, { rootMargin: '-70px 0px -55% 0px', threshold: 0 });
  cards.forEach((c) => tocObserver.observe(c));
}

/* 单张理解卡片：类别徽标 / 编号 / 主题 / 要点 / 章节归属 / 关联 / 已读厚，点卡开详情弹窗 */
function ucCard(catId, c) {
  const km = kindMeta(c.kind);
  const linkCount = (c.links || []).length;
  return `
  <div class="uc-card kp-card" id="uc-${c.id}" onclick="openPointDetail(${catId}, '${esc(c.no || '')}')">
    <div class="kp-top"><span class="kp-kind ${km.cls}">${km.icon} ${km.label}</span>${c.no ? `<span class="kp-no">${esc(c.no)}</span>` : ''}</div>
    <div class="uc-topic">${esc(c.topic)}</div>
    <div class="uc-point">${esc(c.point)}</div>
    <div class="uc-meta">
      ${c.section ? `<span class="uc-chip" title="所在章节">🗂 ${esc([c.chapter, c.section].filter(Boolean).join(' / '))}</span>` : ''}
      ${linkCount ? `<span class="uc-chip kp-link" title="同一本书里的关联知识点">⇄ 关联 ${linkCount}</span>` : ''}
      ${c.enriched ? `<span class="kp-done" title="喵已为这一节补充了展开正文">✦ 已读厚</span>` : ''}
    </div>
  </div>`;
}

/* 知识点四类徽标：关键 / 重点 / 难点 / 知识点（后端已归一化英文枚举；旧数据无 kind 默认 FACT） */
const KIND_META = {
  KEY:       { icon: '🔑', label: '关键', cls: 'k-key' },
  FOCUS:     { icon: '⭐', label: '重点', cls: 'k-focus' },
  DIFFICULT: { icon: '🧗', label: '难点', cls: 'k-diff' },
  FACT:      { icon: '💡', label: '知识点', cls: 'k-fact' },
};
function kindMeta(k) { return KIND_META[k] || KIND_META.FACT; }

/* 页签 · 学习卡片：今日到期卡流（翻转 + 自评走真数据 SM-2 调度，每日 ≤10 张封顶，⏱ 可切 3 分钟轻量版） */
function renderLearn() {
  if (!state.learnDemo) state.learnDemo = { i: 0, flipped: false, stats: { ok: 0, soso: 0, forgot: 0 } };
  const L = state.learnDemo;
  const learn = state.learn;
  const total = learn ? learn.totalCount : 0;
  const mastered = learn ? learn.masteredCount : 0;
  const cards = learn && learn.cards ? learn.cards : [];
  if (L.i >= cards.length) {
    const s = L.stats;
    const reviewed = s.ok + s.soso + s.forgot;
    const rest = learn && learn.dueTotal > cards.length ? learn.dueTotal - cards.length : 0;
    return `<div class="learn-wrap"><div class="learn-done">
      ${reviewed ? `<div class="fc-text">🎉 本轮复习结束</div>
      <p class="fc-hint">记住 ${s.ok} · 模糊 ${s.soso} · 忘记 ${s.forgot}。喵已按记忆曲线安排好下次出现：忘记的明天再来，记住的隔得更久，连续记住 3 次的卡休眠毕业。</p>`
      : `<div class="fc-text">${total ? '🎉 今日没有到期的卡片' : '🎓 还没有学习卡片'}</div>
      <p class="fc-hint">${total ? '共 ' + total + ' 张，已掌握 ' + mastered + ' 张' + (rest ? '，还有 ' + rest + ' 张明日顺延——永远不让复习变成焦虑源。' : '。') + '也可以点「✨ 重新整理大纲」出新的学习卡。' : '点「✨ 整理大纲」，喵读完后会为每本书提炼最值得记住的学习卡（每书 ≤ 5 张）。'}</p>`}
    </div></div>`;
  }
  const c = cards[L.i];
  return `<div class="learn-wrap">
    <div class="learn-top"><span class="fc-kicker">⏱ 今日到期 · ${L.i + 1}/${cards.length}${learn && learn.capped ? ' · 超出封顶自动顺延' : ''}</span>
      <button type="button" class="fc-light ${state.learnLight ? 'on' : ''}" onclick="toggleLearnLight()">⏱ ${state.learnLight ? '切回完整 10 张' : '今日 3 分钟'}</button>
      <span class="fc-dots">${cards.map((_, i) => `<i class="${i < L.i ? 'past' : i === L.i ? 'now' : ''}"></i>`).join('')}</span></div>
    <div class="flip-card ${L.flipped ? 'flipped' : ''}" onclick="flipLearnCard()">
      <div class="fc-inner">
        <div class="fc-face fc-front">
          <div class="fc-kicker">${c.kind === 'feynman' ? 'FEYNMAN · 费曼复述' : c.kind === 'socratic' ? 'SOCRATIC · 反问' : 'RECALL · 提问'}</div>
          <div class="fc-text">${esc(c.front)}</div>
          <div class="fc-hint">先在脑子里回忆，再点击卡片翻面对答案</div>
        </div>
        <div class="fc-face fc-back">
          <div class="fc-kicker">ANSWER · 答案</div>
          <div class="fc-text fc-detail">${esc(c.back)}</div>
          ${(() => {
            const srcCard = c.understandingCardId ? (state.readingCards || []).find((x) => x.id === c.understandingCardId) : null;
            const arts = srcCard && srcCard.sources ? srcCard.sources.map((s) => s.title) : [];
            return `${arts.length ? `<div class="fc-src">📄 信息来源：${arts.map((t) => esc(t)).join('、')}</div>` : ''}`;
          })()}
          ${c.bookTitle ? `<div class="fc-hint">出处：《${esc(c.bookTitle)}》—— 厚薄互链，随时回书</div>` : ''}
        </div>
      </div>
    </div>
    <div class="rate-btns">
      <button class="rate-btn rate-forgot" onclick="rateLearnCard('forgot')">😮‍💨 忘记</button>
      <button class="rate-btn rate-soso" onclick="rateLearnCard('soso')">🤔 模糊</button>
      <button class="rate-btn rate-ok" onclick="rateLearnCard('ok')">✅ 记住</button>
    </div>
  </div>`;
}
function flipLearnCard() { state.learnDemo.flipped = !state.learnDemo.flipped; rerenderLearnDemo(); }
/* 自评 → 后端简化 SM-2 调度（忘记归 1 天 / 模糊不变 / 记住 ×ease；连续 3 忘记降频周检，连续 3 记住休眠） */
async function rateLearnCard(kind) {
  const L = state.learnDemo;
  if (!L) return;
  L.stats[kind]++;
  const card = state.learn && state.learn.cards ? state.learn.cards[L.i] : null;
  const grade = kind === 'forgot' ? 'forget' : kind === 'soso' ? 'vague' : 'remember';
  L.i++; L.flipped = false;
  rerenderLearnDemo();
  if (!card) return;
  const r = await api('/api/cats/' + state.readingCatId + '/learn/cards/' + card.id + '/review?grade=' + grade, { method: 'POST' }).catch(() => null);
  if (r && state.learn) { state.learn.masteredCount = r.masteredCount; state.learn.dueLeft = r.dueLeft; }
}
/* 「今日 3 分钟」轻入口：只推 3 张最高价值卡，对抗一想到要复习 30 张就不打开 */
function toggleLearnLight() {
  state.learnLight = !state.learnLight;
  state.learnDemo = null;
  openReading(state.readingCatId);
}
/* 翻卡/自评只重渲染学习卡片区域，不重新拉接口 */
function rerenderLearnDemo() { const box = $('learnDemoBox'); if (box) box.innerHTML = renderLearn(); }

/* 运行中把流水线四节点点亮到当前阶段（read→读厚 thin→读薄 learn→学透）+ 顶部实时进度条幅 */
const RUN_STAGE_IDX = { read: 1, thin: 2, learn: 3 };
function paintRunStage(task) {
  if (state.readingCatId !== state.readingRunCat) return; // 已切到别的猫/页面，不画
  const strip = document.querySelector('.flow-strip');
  if (strip && task && task.stage) {
    const idx = RUN_STAGE_IDX[task.stage] ?? 1;
    strip.querySelectorAll('.flow-circle').forEach((el, i) => {
      el.classList.toggle('running', i === idx);
      if (i <= idx) el.classList.add('done');
    });
    strip.querySelectorAll('.flow-label').forEach((el, i) => { if (i <= idx) el.classList.remove('pending'); });
  }
  const live = $('runLive');
  if (live) {
    if (task && task.status === 'running') {
      live.style.display = 'flex';
      live.innerHTML = `<span class="rl-dot"></span><span>🐱 ${esc(task.current || '处理中…')}</span>${task.stageTotal ? `<span class="rl-count">${task.stageDone || 0}/${task.stageTotal}</span>` : ''}`;
    } else { live.style.display = 'none'; }
  }
}

/* 启动整理任务并轮询进度：默认自动增量（只读新内容+有修改的文章、新卡归位进已有书）；mode=full 手动全量重归纳。
   触发前先拉 plan 预估，把「本次处理哪些文档」列给主人确认；运行中轮询点亮流水线阶段。 */
async function generateReading(catId, mode) {
  const btn = mode === 'full' ? $('readingFullBtn') : $('readingGenBtn');
  if (!btn || btn.disabled) return;
  /* 归纳前预估：本次会处理哪些文档（增量=未读过+有修改；全量=全部），列给主人确认 */
  const plan = await api('/api/cats/' + catId + '/reading/plan').catch(() => null);
  const tlist = (a) => a.slice(0, 6).map((p) => '《' + p.title + '》').join('、') + (a.length > 6 ? ' 等' : '');
  if (mode === 'full') {
    const mods = plan ? plan.pending.filter((p) => p.reason === 'updated') : [];
    const msg = plan
      ? `♻️ 全量重归纳将重新处理全部 ${plan.total} 篇文档（其中 ${mods.length} 篇有修改）${plan.chatTurns ? ' + 回味 ' + plan.chatTurns + ' 轮会话' : ''}。\n\n✏️ 有修改：${mods.length ? tlist(mods) : '无'}\n\n已有的卡片、书、学习卡会清空重建。确定继续？`
      : '♻️ 全量重归纳会清空已有的理解卡片、学习卡和书，重新逐篇精读生成。确定继续？';
    if (!confirm(msg)) return;
  } else if (plan) {
    const pend = plan.pending || [];
    const fresh = pend.filter((p) => p.reason === 'new');
    const mods = pend.filter((p) => p.reason === 'updated');
    const msg = `✨ 增量归纳将处理 ${pend.length} 篇文档：\n\n🆕 新增 ${fresh.length} 篇：${fresh.length ? tlist(fresh) : '无'}\n✏️ 有修改 ${mods.length} 篇：${mods.length ? tlist(mods) : '无'}\n${plan.chatTurns ? '💬 回味 ' + plan.chatTurns + ' 轮会话\n' : ''}\n已处理 ${plan.processedCount} 篇不变。确定继续？`;
    if (!confirm(msg)) return;
  }
  const original = btn.textContent;
  btn.disabled = true;
  btn.textContent = '🐱 喵正在逐篇精读…';
  try {
    const t = await api('/api/cats/' + catId + '/reading/generate' + (mode ? '?mode=' + mode : ''), { method: 'POST' });
    state.readingRunCat = catId;
    if (state.readingTimer) clearInterval(state.readingTimer);
    state.readingTimer = setInterval(async () => {
      const task = await api('/api/reading/tasks/' + t.id).catch(() => null);
      if (!task) return;
      if (task.status === 'done') {
        clearInterval(state.readingTimer); state.readingTimer = null;
        paintRunStage(task);
        toast('阅读完成：' + task.cardCount + ' 张理解卡片归纳成 ' + task.bookCount + ' 本书');
        openReading(catId);
      } else if (task.status === 'error') {
        clearInterval(state.readingTimer); state.readingTimer = null;
        paintRunStage(task);
        toast(task.error || '整理失败', true);
        openReading(catId);
      } else {
        btn.textContent = '🐱 ' + (task.current || '精读中…');
        paintRunStage(task);
      }
    }, 1200);
  } catch (e) {
    toast(e.message, true);
    btn.disabled = false;
    btn.textContent = original;
  }
}

/* 弹窗查看某本书的 md 原文（就像查看一个 skill 文件） */
async function openReadingMd(catId, file) {
  const d = await api('/api/cats/' + catId + '/reading/file?name=' + encodeURIComponent(file))
    .catch((e) => { toast(e.message, true); return null; });
  if (!d) return;
  $('readingMdTitle').textContent = file;
  $('readingMdPre').innerHTML = mdLite(d.markdown || '（空文件）');
  $('readingMdModal').style.display = 'flex';
}

/* 弹窗查看知识点详情：简介 + 什么时候使用 + 读厚展开 + 同书关联点互跳（数据来自 openReading 存的 state.readingCards） */
function openPointDetail(catId, no) {
  const cards = state.readingCards || [];
  const c = cards.find((x) => x.no === no);
  if (!c) { toast('没有找到这个知识点'); return; }
  const km = kindMeta(c.kind);
  const usage = c.usage || [];
  const links = c.links || [];
  $('pdBadge').innerHTML = `${km.icon} ${km.label}${c.no ? ' · ' + esc(c.no) : ''}`;
  $('pdBody').innerHTML = `
    <div class="pd-topic">${esc(c.topic)}</div>
    <div class="pd-sec"><div class="pd-label">简介</div><div class="pd-text md-body">${mdLite(c.point)}</div></div>
    ${usage.length ? `<div class="pd-sec"><div class="pd-label">什么时候使用</div><ul class="pd-usage">${usage.map((u) => `<li>${esc(u)}</li>`).join('')}</ul></div>` : ''}
    <div class="pd-sec"><div class="pd-label">${c.enriched ? '展开 · 读厚' : '展开'}</div>
      ${c.detail ? `<div class="pd-detail md-body">${mdLite(c.detail)}</div>` : `<p class="pd-pending">这只喵还在消化这一节……读厚完成后，这里会出现展开正文。</p>`}</div>
    ${links.length ? `<div class="pd-sec"><div class="pd-label">同一本书里的关联知识点</div><div class="pd-links">${links.map((ln) => {
      const t = cards.find((x) => x.no === ln);
      return `<span class="uc-chip pd-link-chip" onclick="closeModal('pointDetailModal');openPointDetail(${catId}, '${esc(ln)}')">⇄ ${esc(ln)}${t ? ' · ' + esc(t.topic) : ''}</span>`;
    }).join('')}</div></div>` : ''}
    <div class="pd-sec"><div class="pd-label">出处</div><div class="uc-meta">
      ${c.bookTitle && c.file ? `<span class="uc-chip" title="打开这本书的 md 原文" onclick="closeModal('pointDetailModal');openReadingMd(${catId}, '${esc(c.file)}')">📚 《${esc(c.bookTitle)}》</span>` : ''}
      ${(c.sources || []).map((s) => `<span class="uc-chip" title="这个知识点融合了这篇文章的理解">📄 ${esc(s.title)}</span>`).join('')}
    </div></div>`;
  $('pointDetailModal').style.display = 'flex';
}

