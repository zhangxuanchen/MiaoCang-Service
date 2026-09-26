/* ================= 喵喵 Agent（写作 / 日记 / 设置） ================= */

const PROVIDER_PRESETS = {
  deepseek: { baseUrl: 'https://api.deepseek.com/v1', model: 'deepseek-chat' },
  zhipu: { baseUrl: 'https://open.bigmodel.cn/api/paas/v4', model: 'glm-4-flash' },
  moonshot: { baseUrl: 'https://api.moonshot.cn/v1', model: 'moonshot-v1-8k' },
  dashscope: { baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model: 'qwen-plus' },
  volces: { baseUrl: 'https://ark.cn-beijing.volces.com/api/v3', model: 'deepseek-v4-flash-ga-260731' },
  openai: { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini' },
};

let agentInitialHasKey = false;
let agentCfgCatId = null;

async function openAgentConfig(catId) {
  agentCfgCatId = catId == null ? null : Number(catId);
  const isDefault = agentCfgCatId == null;
  const cat = isDefault ? null : (state.shelf?.cats || []).find((c) => c.id === agentCfgCatId);
  $('agentCatName').textContent = isDefault ? '· 全局默认' : (cat ? '· ' + cat.name : '');
  $('agentModal').style.display = 'flex';
  $('agentHint').textContent = isDefault ? '全局默认：所有未做专属配置的喵共用这份配置' : '';
  try {
    const c = isDefault
      ? await api('/api/agent/default-config')
      : await api(`/api/cats/${agentCfgCatId}/agent-config`);
    $('agentProvider').value = c.provider;
    $('agentBaseUrl').value = c.baseUrl || '';
    $('agentModel').value = c.model || '';
    agentInitialHasKey = !!c.hasKey;
    $('agentKey').value = '';
    $('agentKey').placeholder = c.hasKey ? '已保存（留空不修改；输入新值即覆盖）' : 'sk-…';
    if (isDefault) {
      $('agentModeHint').textContent = c.hasKey ? '已配置 AK（真实模型可用）' : '未配置 AK（喵喵以模拟输出运行）';
    } else {
      $('agentModeHint').textContent = c.mode === 'live' ? `当前模式：真实模型 · ${c.agentName}` : '当前模式：模拟输出（未接入模型）';
      if (c.ownConfig === false) $('agentHint').textContent = '这只猫还没有专属配置，下面预填的是全局默认；点「保存」后即成为它的专属配置';
    }
    onProviderChange();
  } catch (e) { toast(e.message, true); }
}

function onProviderChange() {
  const p = $('agentProvider').value;
  const isMock = p === 'mock';
  ['agentBaseUrl', 'agentModel', 'agentKey'].forEach((id) => { $(id).disabled = isMock; });
  const preset = PROVIDER_PRESETS[p];
  if (preset) {
    $('agentBaseUrl').value = preset.baseUrl;
    $('agentModel').value = preset.model;
  }
  $('agentModeHint').textContent = isMock
    ? '当前模式：模拟输出（本地拼装，无需任何 Key）'
    : p === 'openai-compat'
      ? '当前模式：自定义端点（agentscope-harness 起的 /v1 端点也可以填这里）'
      : '当前模式：真实模型（自动填充官方 Base URL 与模型名，可改）';
}

async function submitAgentConfig() {
  const keyVal = $('agentKey').value.trim();
  const body = {
    provider: $('agentProvider').value,
    baseUrl: $('agentBaseUrl').value.trim(),
    model: $('agentModel').value.trim(),
  };
  /* Key 语义：输入了=覆盖；留空且原本没有=清除；留空且原本有=不改（不发字段） */
  if (keyVal) body.apiKey = keyVal;
  else if (!agentInitialHasKey) body.apiKey = '';
  try {
    const r = agentCfgCatId == null
      ? await api('/api/agent/default-config', { method: 'PUT', body })
      : await api(`/api/cats/${agentCfgCatId}/agent-config`, { method: 'PUT', body });
    toast(r.message, false);
    closeModal('agentModal');
    if (agentCfgCatId == null) renderAkStateFromModal(); /* 书房状态灯同步 */
  } catch (e) { toast(e.message, true); }
}

/* 保存全局默认后，按弹窗里的最新状态刷新书房总览页 AK 状态灯（不发请求） */
function renderAkStateFromModal() {
  const has = agentInitialHasKey || !!$('agentKey').value.trim();
  const dot = $('homeAkDot'), st = $('homeAkState');
  if (dot) dot.style.background = has ? '#0F6E56' : '#bbb';
  if (st) st.textContent = has ? '已配置' : '未配置';
}

/* ================= 猫专属会话区（agentscope-harness · 四层压缩记忆） ================= */

const chat = { catId: null, tab: 'chat', busy: false, fromWs: false, sessions: {}, controller: null, surface: 'drawer' };

/* 双容器：抽屉（非图谱视图）与图谱右侧常驻栏共享同一套对话逻辑 */
const CHAT_SURFACES = {
  drawer: { msgs: 'chatMsgs', input: 'chatInput', send: 'chatSendBtn', tools: 'chatTools' },
  graph: { msgs: 'gcMsgs', input: 'gcInput', send: 'gcSendBtn', tools: 'gcTools' },
};
function chatSurface() {
  if ($('chatDrawer').classList.contains('open')) return 'drawer';
  return $('gcMsgs') ? 'graph' : 'drawer';
}

/* 每猫一个会话 id：客户端生成、服务端认账；localStorage 持久化，切页/刷新后接回同一会话 */
function chatSessionId(catId) {
  if (!chat.sessions[catId]) {
    const k = 'mc-chat-sid-' + catId;
    chat.sessions[catId] = localStorage.getItem(k) || ('s' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6));
    localStorage.setItem(k, chat.sessions[catId]);
  }
  return chat.sessions[catId];
}

/* 清空会话后重开：丢掉旧 id，下次对话生成新会话 */
function resetChatSession(catId) {
  delete chat.sessions[catId];
  localStorage.removeItem('mc-chat-sid-' + catId);
}

async function openChat(catId, tab) {
  const cats = (state.shelf && state.shelf.cats) || [];
  if (!cats.length) { toast('先领养一只猫再来聊吧', true); return; }
  ensureChatFloat();
  $('chatDrawer').classList.add('open');
  /* 会话对象只能是自己的喵（Wiki 管理员全库会话已下线） */
  const sel = $('chatCatSelect');
  sel.innerHTML = cats.map((c) => `<option value="${c.id}">${esc(c.icon || '🐱')} ${esc(c.name)}</option>`).join('');
  const target = (cats.find((c) => c.id === catId) || cats.find((c) => c.id === state.graphCatId) || cats[0]).id;
  sel.value = String(target);
  const orb = $('wikiOrb');
  if (orb) orb.classList.add('hidden'); /* 抽屉打开时藏起悬浮球，避免遮挡 */
  /* 同一只喵且消息区有内容（含进行中的回复流）→ 原样重开，不清空不打断 */
  if (chat.catId === Number(target) && $('chatMsgs') && $('chatMsgs').children.length) {
    await loadChatHeader();
    return;
  }
  await onChatCatChange('drawer');
}

/* 关闭 = 最小化到悬浮球：不中断进行中的回复流（后台继续收 token，重开原样续看）；停止用发送按钮（busy 时变 ⏹） */
function closeChat() {
  $('chatDrawer').classList.remove('open');
  const orb = $('wikiOrb');
  if (orb) orb.classList.remove('hidden');
}

/* ================= 专属工作区：菜单常驻入口，会话 + 记忆状态展开成主区域面板 ================= */

/* 技能/设置/记忆三按钮随形态搬移：工作区常驻顶栏右侧动作组，悬浮窗回副行（事件按 ID 绑定，搬移不丢） */
function layoutChatActions() {
  const host = document.body.classList.contains('ws-mode')
    ? document.querySelector('.chat-top-acts')
    : document.querySelector('.chat-sub-acts');
  if (!host) return;
  ['chatSkillsBtn', 'chatCfgBtn', 'chatMemoryBtn'].forEach((id) => {
    const b = $(id);
    if (b && b.parentElement !== host) host.appendChild(b);
  });
}

/* 进入工作区：body.ws-mode 切三栏平铺布局——🗂 文件树（1/4）｜📄 文件预览（中间）｜💬 会话（1/4 可拖宽），
   复用全部会话 DOM；高度全部自适应视口 */
async function openWorkspace() {
  const cats = (state.shelf && state.shelf.cats) || [];
  if (!cats.length) { toast('先领养一只喵，再进专属工作区', true); return; }
  state.view = 'workspace';
  setNav();
  window.scrollTo(0, 0);
  document.body.classList.add('ws-mode');
  initWsDivider(); /* 先绑拖拽再装会话：openChat 异常时绑定也已就位（元素是静态 DOM） */
  layoutChatActions();
  const badge = document.querySelector('#chatDrawer .drawer-badge');
  if (badge) badge.textContent = '💼 专属工作区';
  $('main').innerHTML = ''; /* 底板清空：面板覆盖整个主区域，切走时按菜单重新渲染 */
  /* 点击专属工作区：优先恢复上次用过的喵（记录在 localStorage），没有记录才默认第一只 */
  const remembered = Number(localStorage.getItem('wsLastCat')) || 0;
  const target = cats.find((c) => c.id === remembered) || cats[0];
  await openChat(target.id);
  /* 常驻面板布局完全交给 ws-mode CSS：openChat 里 ensureChatFloat 首跑会写入浮窗 inline
     left/top（悬浮位置记忆），必须在它之后清掉，否则 inline 压过 right:0 把面板顶离右缘 */
  const d = $('chatDrawer');
  if (d) { d.style.left = ''; d.style.top = ''; d.style.width = ''; d.style.height = ''; }
  /* 记忆面板留 body 层：工作区下 CSS 悬浮在文件预览区右缘（🧠 开合），默认收起 */
  const p = $('memPopup');
  if (p) { p.style.left = ''; p.style.top = ''; p.style.width = ''; p.classList.remove('min'); }
  /* 会话栏 / 文件树宽度记忆恢复（两条分隔条各自独立；parseFloat 兼容旧联动版的表达式值） + 文件树装载 */
  const savedChat = parseFloat(localStorage.getItem('wsChatW'));
  if (Number.isFinite(savedChat)) document.body.style.setProperty('--ws-chat-w', savedChat + 'px');
  const savedTree = parseFloat(localStorage.getItem('wsTreeW'));
  if (Number.isFinite(savedTree)) document.body.style.setProperty('--ws-tree-w', savedTree + 'px');
  loadWsTree(target.id);
}

/* 离开工作区：任何非 workspace 视图渲染时调用——恢复浮窗形态与悬浮球 */
function exitWorkspace() {
  if (!document.body.classList.contains('ws-mode')) return;
  document.body.classList.remove('ws-mode');
  document.body.classList.remove('mem-open');
  stopWsTreeWatch(); /* 离开工作区不再轮询文件清单 */
  layoutChatActions();
  const badge = document.querySelector('#chatDrawer .drawer-badge');
  if (badge) badge.textContent = '💬 专属会话区';
  closeMemPopup();
  closeChat();
  restoreChatFloatPos(); /* 回到悬浮窗形态：恢复上次拖拽的位置与尺寸 */
}

