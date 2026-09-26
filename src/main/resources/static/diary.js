/* ==================== 页面一：喵喵记录（事件详情 → 日记流 + 事件表） ==================== */

async function renderDiaryRecords() {
  const a = state.diary.agent;
  if (!a) { $('main').innerHTML = '<div class="empty">还没有任何喵的日记：先去专属工作区和喵聊一轮吧。</div>'; return; }
  const r = state.diary.records;
  $('main').innerHTML = `<div class="diary-page">
    <div class="view-head graph-head">
      <div class="graph-head-left"><button class="btn back" onclick="goHome()">← 返回书房</button></div>
      <h2 class="diary-title">🐾 喵喵记录 · ${esc(diaryAgentName(a))}</h2>
    </div>
    <div class="diary-toolbar">
      ${diaryAgentSelect('diarySwitchAgent()')}
      <label class="diary-search">
        <span class="ds-ico">🔎</span>
        <input id="diaryKw" placeholder="搜索日记关键字…" value="${esc(r.keyword)}" onkeydown="if(event.key==='Enter')diaryQueryRecords()">
      </label>
      <button class="btn primary diary-go" onclick="diaryQueryRecords()">查询</button>
      <span id="diaryChip"></span>
      <div class="diary-tabs">
        <button class="diary-tab ${r.tab === 'diary' ? 'on' : ''}" onclick="diaryRecTab('diary')">📖 日记流</button>
        <button class="diary-tab ${r.tab === 'events' ? 'on' : ''}" onclick="diaryRecTab('events')">📃 事件表</button>
      </div>
    </div>
    <div class="diary-kpis" id="diaryKpis"><div class="diary-kpi"><div class="k">事件总数</div><div class="v">–</div></div><div class="diary-kpi"><div class="k">Token 统计</div><div class="v">–</div></div></div>
    <div id="diaryTokenChart"></div>
    <div id="diaryBody"><div class="empty">加载中…</div></div>
    <div class="diary-pager" id="diaryPager"></div>
  </div>`;
  /* 并行：KPI / 正文（日记流自己顺带画 Turn 柱状图，事件表另拉一页 Turn 画图） */
  const bodyP = r.tab === 'diary' ? diaryLoadTurns() : Promise.all([diaryLoadEvents(), diaryLoadChart()]);
  await Promise.all([diaryLoadKpi(), bodyP]);
  diarySyncChip();
}

/* 工具栏上的「已选会话」胶囊：只在点了柱子之后出现，点它或点空白处都能取消 */
function diarySyncChip() {
  const r = state.diary.records;
  const host = $('diaryChip');
  if (!host) return;
  host.innerHTML = r.sessionId
    ? `<button class="diary-chip" type="button" onclick="diaryPickTurn('','')" title="取消筛选">🗂 会话 ${esc(r.sessionId.slice(0, 8))}<span class="dc-x">✕</span></button>`
    : '';
}

function diarySwitchAgent() {
  state.diary.agent = $('main').querySelector('.diary-agent select').value;
  state.diary.records.offset = 0;
  state.diary.records.sessionId = ''; /* 换了喵，上一只喵的会话筛选自然作废 */
  state.diary.records.pickedTurn = '';
  renderDiaryRecords();
}

function diaryQueryRecords() {
  const r = state.diary.records;
  r.keyword = $('diaryKw').value.trim();
  r.offset = 0;
  diaryLoadKpi();
  if (r.tab === 'diary') diaryLoadTurns(); else Promise.all([diaryLoadEvents(), diaryLoadChart()]);
}

function diaryRecTab(tab) {
  state.diary.records.tab = tab;
  state.diary.records.offset = 0;
  renderDiaryRecords();
}

async function diaryLoadKpi() {
  const a = state.diary.agent;
  const r = state.diary.records;
  const q = new URLSearchParams({ keyword: r.keyword });
  if (r.sessionId) q.set('sessionId', r.sessionId);
  try {
    const d = await api('/api/v1/agents/' + diaryAgentPath(a) + '/events-stats?' + q);
    $('diaryKpis').innerHTML = `
      <div class="diary-kpi"><div class="k">事件总数</div><div class="v">${d.total}</div><div class="s">命中关键字的事件</div></div>
      <div class="diary-kpi"><div class="k">Token 统计</div><div class="v">${fmtTok(d.tokens)}</div><div class="s">token_count 求和</div></div>`;
  } catch (e) { /* KPI 失败不打断正文 */ }
}

/* 日记流：按 Turn 聚合的卡片（主人问 → 喵答 → 操作步骤链），顺带把 Turn 柱状图画出来 */
async function diaryLoadTurns() {
  const a = state.diary.agent;
  const r = state.diary.records;
  $('diaryBody').innerHTML = '<div class="empty">加载中…</div>';
  diaryLoadChart(); /* 柱子始终画「当前搜索命中的全部 Turn」，不跟着会话筛选缩水（被点中的那根高亮） */
  const q = new URLSearchParams({ q: r.keyword, limit: 20, offset: r.offset });
  if (r.sessionId) q.set('sessionId', r.sessionId);
  try {
    const d = await api('/api/v1/agents/' + diaryAgentPath(a) + '/turns?' + q);
    r.total = d.total || 0;
    const turns = d.turns || [];
    if (!turns.length) {
      $('diaryBody').innerHTML = `<div class="empty">${r.sessionId ? '这个会话在这一页没有日记' : r.keyword ? `没有匹配「${esc(r.keyword)}」的日记` : '这一页还是空白的：去「专属工作区」和喵聊一轮，日记就会写上来。'}</div>`;
      $('diaryPager').innerHTML = '';
      return;
    }
    $('diaryBody').innerHTML = `<div class="diary-turns">` + turns.map((t) => {
      const meta = t.metadata || {};
      const actions = t.actions || [];
      const steps = actions.slice(0, 8).map((ac, i) =>
        `<div class="diary-step"><span class="diary-step-idx">${i + 1}</span><span class="diary-step-txt">${esc((ac.metadata && ac.metadata.action_full) || ac.memorySummary || ac.memoryKey || '')}</span><span class="diary-step-meta">${layerBadge(ac.layer)}${ac.tokenCount ? fmtTok(ac.tokenCount) + ' tok' : ''}${ac.latencyMs ? ' · ' + fmtMs(ac.latencyMs) : ''}</span></div>`).join('');
      const more = actions.length > 8 ? `<div class="diary-step-more">…还有 ${actions.length - 8} 步</div>` : '';
      return `<div class="diary-turn">
        <div class="diary-turn-head">
          <span class="diary-turn-time">${esc((t.timestamp || '').slice(0, 19))}</span>
          <span class="diary-turn-sess">会话 ${esc((t.sessionId || '').slice(0, 8))}</span>
          <span class="diary-turn-stat">${fmtTok(t.tokenCount)} tok · ${fmtMs(t.latencyMs)}</span>
        </div>
        <div class="diary-turn-q">🙋 ${esc(meta.turn_user || '（无提问记录）')}</div>
        <div class="diary-turn-a">🐱 ${esc(meta.turn_outcome || t.memorySummary || '')}</div>
        ${steps ? `<div class="diary-turn-steps">${steps}${more}</div>` : ''}
      </div>`;
    }).join('') + '</div>';
    renderDiaryPager(d.total, 20, r.offset, 'diaryLoadTurns');
  } catch (e) {
    $('diaryBody').innerHTML = `<div class="empty">日记读取失败：${esc(e.message)}</div>`;
  }
}

/* Token 柱形图：每根柱 = 一轮 Turn 的 token 消耗，只画「当前搜索命中的 Turn」（第一页 20 轮）。
   有几轮摊几根，柱宽自适应平分整条图的宽度。点柱子 = 按这一轮的会话筛下方内容并高亮该柱。 */
function renderTurnBars(turns) {
  const host = $('diaryTokenChart');
  if (!host) return;
  if (!turns || !turns.length) { host.innerHTML = ''; return; }
  const picked = state.diary.records.pickedTurn || '';
  const max = Math.max(1, ...turns.map((t) => t.tokenCount || 0));
  const total = turns.reduce((sum, t) => sum + (t.tokenCount || 0), 0);
  const avg = Math.round(total / turns.length);
  host.innerHTML = `
    <div class="dtc-head">
      <span class="dtc-title">📊 Token 消耗 · 按轮 · ${turns.length} 轮 · 共 ${fmtTok(total)}</span>
      <span class="dtc-sub">峰值 ${fmtTok(max)} · 均值 ${fmtTok(avg)} tok · 点柱子按会话筛选</span>
    </div>
    <div class="dtc-chart">
      ${turns.map((t) => {
        const tok = t.tokenCount || 0;
        const h = Math.max(2, Math.round((tok / max) * 100));
        const at = esc((t.timestamp || '').slice(5, 16));
        const sess = esc((t.sessionId || '').slice(0, 8));
        const on = picked && t.eventId === picked ? ' on' : '';
        return `<div class="dtc-col${on}" onclick="diaryPickTurn('${esc(t.eventId || '')}','${esc(t.sessionId || '')}')" title="${at} · 会话 ${sess} · ${fmtTok(tok)} tok">
          <div class="dtc-top">${tok ? fmtTok(tok) : ''}</div>
          <div class="dtc-bar" style="height:${h}%"></div>
        </div>`;
      }).join('')}
    </div>`;
}

/* 点柱子 → 按这轮的会话筛下方正文 + 高亮该柱；再点同一根、点筛选胶囊、点空白处都取消 */
function diaryPickTurn(eventId, sessionId) {
  const r = state.diary.records;
  if (!r) return;
  const same = !!eventId && r.pickedTurn === eventId;
  r.pickedTurn = same ? '' : (eventId || '');
  r.sessionId = same ? '' : (sessionId || '');
  r.offset = 0;
  diarySyncChip();
  diaryLoadKpi();
  if (r.tab === 'diary') diaryLoadTurns(); else Promise.all([diaryLoadEvents(), diaryLoadChart()]);
}

/* 点柱子以外的地方 = 取消高亮与筛选（柱子/胶囊自己的 click 先跑，冒泡到这儿会被放过） */
document.addEventListener('click', (ev) => {
  const r = state.diary && state.diary.records;
  if (!r || !r.sessionId) return;
  if (!document.getElementById('diaryTokenChart')) return; /* 不在喵喵记录页 */
  if (ev.target && ev.target.closest && ev.target.closest('.dtc-col, .diary-chip')) return;
  diaryPickTurn('', '');
});

/* 事件表 tab 下的柱状图：同样只画当前搜索命中的 Turn（走首页，与翻页无关） */
async function diaryLoadChart() {
  const a = state.diary.agent;
  const r = state.diary.records;
  try {
    const d = await api('/api/v1/agents/' + diaryAgentPath(a) + '/turns?'
      + new URLSearchParams({ q: r.keyword, limit: 20, offset: 0 }));
    renderTurnBars(d.turns || []);
  } catch (e) { renderTurnBars([]); }
}

/* 事件表：原始事件一行一条，点行开详情抽屉 */
async function diaryLoadEvents() {
  const a = state.diary.agent;
  const r = state.diary.records;
  $('diaryBody').innerHTML = '<div class="empty">加载中…</div>';
  const q = new URLSearchParams({ keyword: r.keyword, limit: 50, offset: r.offset });
  if (r.sessionId) q.set('sessionId', r.sessionId);
  try {
    const d = await api('/api/v1/agents/' + diaryAgentPath(a) + '/events?' + q);
    r.total = d.total || 0;
    const evs = d.events || [];
    if (!evs.length) {
      $('diaryBody').innerHTML = `<div class="empty">${r.sessionId ? '这个会话在这一页没有事件。' : r.keyword ? `没有匹配「${esc(r.keyword)}」的事件。` : '没有事件记录。'}</div>`;
      $('diaryPager').innerHTML = ''; return;
    }
    $('diaryBody').innerHTML = `<div class="diary-etable">
      <div class="diary-ethead"><div>时间</div><div>操作</div><div>层</div><div>会话</div><div>memoryKey · 摘要</div><div class="num">Token</div><div class="num">延迟</div></div>
      ${evs.map((e2) => `<div class="diary-etrow" onclick="openEventDrawer('${esc(e2.eventId)}')">
        <div class="mono">${esc((e2.timestamp || '').slice(5, 19))}</div>
        <div>${opBadge(e2.operation)}</div>
        <div>${layerBadge(e2.layer)}</div>
        <div class="mono">${esc((e2.sessionId || '').slice(0, 8))}</div>
        <div class="diary-etsum"><b>${esc(e2.memoryKey || '–')}</b>${esc((e2.memorySummary || '').slice(0, 90))}</div>
        <div class="num">${e2.tokenCount ? fmtTok(e2.tokenCount) : '–'}</div>
        <div class="num">${e2.latencyMs ? fmtMs(e2.latencyMs) : '–'}</div>
      </div>`).join('')}
    </div>`;
    renderDiaryPager(d.total, 50, r.offset, 'diaryLoadEvents');
  } catch (e) {
    $('diaryBody').innerHTML = `<div class="empty">事件读取失败：${esc(e.message)}</div>`;
  }
}

function renderDiaryPager(total, limit, offset, fn) {
  const page = Math.floor(offset / limit) + 1;
  const pages = Math.max(1, Math.ceil(total / limit));
  $('diaryPager').innerHTML = `
    <button class="btn" ${offset <= 0 ? 'disabled' : ''} onclick="${fn}(${Math.max(0, offset - limit)})">‹ 上一页</button>
    <span class="diary-pager-info">${page} / ${pages} · 共 ${total} 条</span>
    <button class="btn" ${offset + limit >= total ? 'disabled' : ''} onclick="${fn}(${offset + limit})">下一页 ›</button>`;
}

/* 事件详情抽屉 */
async function openEventDrawer(eventId) {
  try {
    const e = await api('/api/v1/events/' + eventId);
    const meta = e.metadata || {};
    const actions = (e.actions || []).filter((x) => x.eventId !== eventId);
    $('diaryDrawerBody').innerHTML = `
      <div class="diary-meta-grid">
        <div><span>事件</span><b class="mono">${esc(e.eventId)}</b></div>
        <div><span>时间</span><b>${esc((e.timestamp || '').slice(0, 19))}</b></div>
        <div><span>操作 / 层</span><b>${opBadge(e.operation)} ${layerBadge(e.layer)}</b></div>
        <div><span>会话</span><b class="mono">${esc(e.sessionId || '–')}</b></div>
        <div><span>Token / 延迟</span><b>${fmtTok(e.tokenCount)} tok · ${fmtMs(e.latencyMs)}</b></div>
        <div><span>memoryKey</span><b class="mono">${esc(e.memoryKey || '–')}</b></div>
        ${meta.turn_message_id ? `<div><span>Turn</span><b class="mono">${esc(meta.turn_message_id)}</b></div>` : ''}
        ${meta.status ? `<div><span>状态</span><b class="${meta.status === 'failed' ? 'diary-failed' : ''}">${esc(meta.status)}</b></div>` : ''}
      </div>
      <div class="diary-drawer-sec">摘要</div>
      <pre class="diary-summary">${esc(e.memorySummary || '（无）')}</pre>
      ${meta.turn_actions ? `<div class="diary-drawer-sec">操作步骤</div><div class="diary-turn-steps">${JSON.parse(meta.turn_actions).map((s, i) => `<div class="diary-step"><span class="diary-step-idx">${i + 1}</span><span class="diary-step-txt">${esc(s)}</span></div>`).join('')}</div>` : ''}
      ${actions.length ? `<div class="diary-drawer-sec">同 Turn 事件（${actions.length}）</div><div class="diary-turn-steps">${actions.map((ac) => `<div class="diary-step"><span class="diary-step-idx">${layerBadge(ac.layer)}</span><span class="diary-step-txt">${esc(ac.memorySummary || ac.memoryKey || '')}</span><span class="diary-step-meta">${ac.tokenCount ? fmtTok(ac.tokenCount) + ' tok' : ''}${ac.latencyMs ? ' · ' + fmtMs(ac.latencyMs) : ''}</span></div>`).join('')}</div>` : ''}`;
    $('diaryDrawer').style.display = 'flex';
  } catch (e2) { toast(e2.message, true); }
}
function closeEventDrawer() { $('diaryDrawer').style.display = 'none'; }

/* ==================== 页面二：喵喵消耗（Token 分析） ==================== */

async function renderDiaryTokens() {
  const a = state.diary.agent;
  if (!a) { $('main').innerHTML = '<div class="empty">还没有任何喵的日记。</div>'; return; }
  $('main').innerHTML = `<div class="diary-page">
    <div class="view-head graph-head">
      <div class="graph-head-left"><button class="btn back" onclick="goHome()">← 返回书房</button></div>
      <h2 class="diary-title">🪙 喵喵消耗 · ${esc(diaryAgentName(a))}</h2>
    </div>
    <div class="diary-toolbar">
      ${diaryAgentSelect('diarySwitchAgentToken()')}
      <select class="diary-select" id="diaryTokenDays" onchange="diaryLoadTokens()">
        <option value="1" ${state.diary.tokens.days === '1' ? 'selected' : ''}>最近 24 小时</option>
        <option value="7" ${state.diary.tokens.days === '7' ? 'selected' : ''}>最近 7 天</option>
        <option value="30" ${state.diary.tokens.days === '30' ? 'selected' : ''}>最近 30 天</option>
        <option value="0" ${state.diary.tokens.days === '0' ? 'selected' : ''}>全部</option>
      </select>
    </div>
    <div id="diaryTokenBody"><div class="empty">加载中…</div></div>
  </div>`;
  diaryLoadTokens();
}

function diarySwitchAgentToken() {
  state.diary.agent = $('main').querySelector('.diary-agent select').value;
  renderDiaryTokens();
}

async function diaryLoadTokens() {
  const a = state.diary.agent;
  state.diary.tokens.days = $('diaryTokenDays') ? $('diaryTokenDays').value : '7';
  const days = state.diary.tokens.days;
  const from = days > 0 ? new Date(Date.now() - days * 86400000).toISOString() : '';
  $('diaryTokenBody').innerHTML = '<div class="empty">加载中…</div>';
  try {
    const [s, an] = await Promise.all([
      api(`/api/v1/agents/${diaryAgentPath(a)}/token-stats?from=${from}`),
      api(`/api/v1/agents/${diaryAgentPath(a)}/token-analytics?from=${from}`),
    ]);
    const layers = s.byLayer || {}, ops = s.byOp || {};
    const layerRows = Object.entries(layers).sort((x, y) => y[1] - x[1]);
    const opRows = Object.entries(ops).sort((x, y) => y[1] - x[1]);
    const maxL = Math.max(1, ...layerRows.map((x) => x[1]));
    const maxO = Math.max(1, ...opRows.map((x) => x[1]));
    const trend = s.trend || [];
    const maxT = Math.max(1, ...trend.map((x) => x.tokens || 0));
    const sessions = an.bySessionTop || [];
    const maxS = Math.max(1, ...sessions.map((x) => x.tokens || 0));
    const abnormal = (an.keyAbnormal || []).slice(0, 10);
    $('diaryTokenBody').innerHTML = `
      <div class="diary-kpis four">
        <div class="diary-kpi"><div class="k">总 Token</div><div class="v">${fmtTok(s.totalTokens)}</div><div class="s">窗口内 token 合计</div></div>
        <div class="diary-kpi"><div class="k">最近 1 小时</div><div class="v">${fmtTok((an.burnRate || {}).tokensLastHour)}</div><div class="s">燃烧速率</div></div>
        <div class="diary-kpi"><div class="k">Top 会话消耗</div><div class="v">${fmtTok(sessions.length ? sessions[0].tokens : 0)}</div><div class="s">${sessions.length ? esc(sessions[0].sessionId.slice(0, 8)) : '–'}</div></div>
        <div class="diary-kpi"><div class="k">异常 key</div><div class="v">${abnormal.length}</div><div class="s">超历史均值 4 倍</div></div>
      </div>
      <div class="diary-grid2">
        <div class="diary-card"><h3>按层消耗</h3>${barList(layerRows.map(([k, v]) => ({ label: LAYER_LABEL[k] || k, value: v })), maxL, 'tok')}</div>
        <div class="diary-card"><h3>按操作</h3>${barList(opRows.map(([k, v]) => ({ label: k, value: v })), maxO, 'tok')}</div>
      </div>
      <div class="diary-card"><h3>消耗趋势</h3>
        ${trend.length ? `<div class="diary-trend">${trend.map((x) => `<div class="diary-tcol" title="${esc(x.bucket)} · ${fmtTok(x.tokens)} tok"><div class="diary-tbar" style="height:${Math.max(2, Math.round((x.tokens || 0) / maxT * 100))}%"></div><span>${esc(String(x.bucket).slice(-5))}</span></div>`).join('')}</div>` : '<div class="empty">窗口内没有消耗</div>'}
      </div>
      <div class="diary-grid2">
        <div class="diary-card"><h3>Top 会话（Token）</h3>${sessions.length ? barList(sessions.map((x) => ({ label: (x.sessionId || '').slice(0, 12), value: x.tokens || 0 })), maxS, 'tok') : '<div class="empty">暂无</div>'}</div>
        <div class="diary-card"><h3>⚠️ 异常 key（膨胀信号）</h3>${abnormal.length ? abnormal.map((x) => `<div class="diary-abrow"><b class="mono">${esc(x.memoryKey)}</b><span>max ${fmtTok(x.maxTokens)} · avg ${fmtTok(x.avgTokens)} · ${x.count} 次</span></div>`).join('') : '<div class="empty">没有膨胀信号，喵吃得很健康</div>'}</div>
      </div>`;
  } catch (e) {
    $('diaryTokenBody').innerHTML = `<div class="empty">消耗数据读取失败：${esc(e.message)}</div>`;
  }
}

function barList(rows, max, unit) {
  if (!rows.length) return '<div class="empty">暂无数据</div>';
  return `<div class="diary-bars">` + rows.map((r) => `
    <div class="diary-bar"><span class="diary-bar-label">${esc(String(r.label))}</span>
      <div class="diary-bar-track"><div class="diary-bar-fill" style="width:${Math.max(1.5, Math.round(r.value / max * 100))}%"></div></div>
      <span class="diary-bar-val">${fmtTok(r.value)}${unit ? ' ' + unit : ''}</span></div>`).join('') + '</div>';
}

/* ==================== 页面三：喵喵问题（问题分析） ==================== */

async function renderDiaryProblems() {
  $('main').innerHTML = `<div class="diary-page">
    <div class="view-head graph-head">
      <div class="graph-head-left"><button class="btn back" onclick="goHome()">← 返回书房</button></div>
      <h2 class="diary-title">⚠️ 喵喵问题</h2>
    </div>
    <div class="diary-toolbar">
      <select class="diary-select" id="diaryProbDays" onchange="diaryLoadProblems()">
        <option value="1" ${state.diary.problems.days === '1' ? 'selected' : ''}>最近 24 小时</option>
        <option value="7" ${state.diary.problems.days === '7' ? 'selected' : ''}>最近 7 天</option>
        <option value="30" ${state.diary.problems.days === '30' ? 'selected' : ''}>最近 30 天</option>
        <option value="0" ${state.diary.problems.days === '0' ? 'selected' : ''}>全部</option>
      </select>
      <div class="diary-sev" role="group">
        <button type="button" class="on" data-sev="" onclick="diarySev('')">全部</button>
        <button type="button" data-sev="danger" onclick="diarySev('danger')">严重</button>
        <button type="button" data-sev="warn" onclick="diarySev('warn')">警告</button>
        <button type="button" data-sev="info" onclick="diarySev('info')">提示</button>
      </div>
    </div>
    <div id="diaryProbBody"><div class="empty">加载中…</div></div>
  </div>`;
  diaryLoadProblems();
}

function diarySev(sev) {
  state.diary.problems.sev = sev;
  document.querySelectorAll('.diary-sev button').forEach((b) => b.classList.toggle('on', b.dataset.sev === sev));
  if (state.diary.problems._last) renderProblemCards(state.diary.problems._last);
}

async function diaryLoadProblems() {
  state.diary.problems.days = $('diaryProbDays') ? $('diaryProbDays').value : '7';
  $('diaryProbBody').innerHTML = '<div class="empty">加载中…</div>';
  try {
    const d = await api('/api/v1/analytics/problems?days=' + state.diary.problems.days);
    state.diary.problems._last = d;
    renderProblemCards(d);
  } catch (e) {
    $('diaryProbBody').innerHTML = `<div class="empty">问题数据读取失败：${esc(e.message)}</div>`;
  }
}

function renderProblemCards(d) {
  const sev = state.diary.problems.sev;
  const problems = (d.problems || []).filter((p) => p.hitCount > 0 && (!sev || DIARY_SEV[p.key] === sev));
  const counts = { danger: 0, warn: 0, info: 0 };
  (d.problems || []).forEach((p) => { if (p.hitCount > 0) counts[DIARY_SEV[p.key] || 'info'] += p.hitCount; });
  if (!(d.problems || []).some((p) => p.hitCount > 0)) {
    $('diaryProbBody').innerHTML = '<div class="empty">🎉 窗口内没有发现问题，喵运转得很顺滑。</div>';
    return;
  }
  $('diaryProbBody').innerHTML = `<div class="diary-prob-summary">
      <span class="sev-chip danger">严重 ${counts.danger}</span><span class="sev-chip warn">警告 ${counts.warn}</span><span class="sev-chip info">提示 ${counts.info}</span>
    </div>
    <div class="diary-prob-grid">` + problems.map((p) => `
      <div class="diary-prob sev-${DIARY_SEV[p.key] || 'info'}">
        <div class="diary-prob-head"><b>${esc(p.title)}</b><span class="diary-prob-n">${p.hitCount}</span></div>
        <div class="diary-prob-th">判定阈值：${esc(p.threshold)}</div>
        <div class="diary-prob-hits">${p.hits.slice(0, 6).map((h) => {
          const who = h.turnUser ? `「${String(h.turnUser).slice(0, 30)}」` : h.memoryKey || h.turnId || h.sessionId || '';
          const val = h.metric ? `${h.metric === 'tokens' ? fmtTok(h.metricValue) + ' tok' : h.metric === 'events' || h.metric === 'toolEvents' ? h.metricValue + ' 次' : fmtMs(h.metricValue)}` : '';
          return `<div class="diary-hit"><span class="mono">${esc(String(h.sessionId || '').slice(0, 8))}</span><span class="diary-hit-who">${esc(String(who))}</span><b>${esc(val)}</b></div>`;
        }).join('')}${p.hits.length > 6 ? `<div class="diary-step-more">…还有 ${p.hits.length - 6} 条命中</div>` : ''}</div>
      </div>`).join('') + '</div>';
}
