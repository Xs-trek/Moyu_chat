import { captureTimelineAnchor, restoreTimelineAnchor } from './scroll-anchor.js';
import { renderMarkdownInto } from './markdown.js';
import { button, h, icon, iconButton, syncSegment } from './dom.js';
import { fish, greeting, MILESTONES, setPose } from './mascot.js';
import { milestone, perchPose } from './mascot-director.js';
import { pruneSelection, selecting, selectionBar } from './session-select.js';
import { refreshSheet, renderDrawer, sessionList } from './overlays.js';
import { getState, setNotice } from './state.js';
import { flip, morph, removeNode, swapIn, withFx } from './morph.js';
import { forgetRemote, loadRemote, loadTrace, peekTrace, peekRemote } from './remote.js';
import * as vm from './view-model.js';

export { openConfirmDialog, openTextDialog } from './overlays.js';

const TURN_NAV_MIN_TURNS = 6;
const expandedTurns = new Set();
const milestoneTotals = new Map();

/** Expanded usage details are a per-reply view preference that survives re-renders. */
export function toggleTurnDetails(key) { if (expandedTurns.has(key)) expandedTurns.delete(key); else expandedTurns.add(key); }

// ---------- Shared pieces ----------

function appHeader(title, subtitle, trailing = null, cls = 'app-head') {
  return h('header', { class: cls },
    iconButton('menu', '会话列表', 'drawer-open', {}, 'icon-btn nav-toggle'),
    h('div', { class: 'head-title grow' }, h('strong', { class: 'truncate', text: title }), subtitle ? h('small', { class: 'truncate', text: subtitle }) : null),
    trailing);
}

function mediaGallery(items) {
  const images = (items || []).filter((item) => item?.kind === 'image' && item.url);
  if (!images.length) return null;
  return h('div', { class: 'media-grid', dataset: { mediaSignature: JSON.stringify(images.map((i) => [i.mediaId, i.url])) } }, images.map((item) => h('figure', { class: 'media-item' },
    // Intrinsic size reserves the box before decode, so late image loads cannot move the reading position.
    h('img', { src: item.url, alt: item.fileName || '图片', loading: 'lazy', width: item.width || undefined, height: item.height || undefined, dataset: { action: 'view-image', src: item.url, name: item.fileName || '' } }),
    h('figcaption', { class: 'truncate', text: item.fileName || `${item.width || '?'}×${item.height || '?'}` }))));
}

function draftMediaStrip(items, sessionId = '') {
  const images = (items || []).filter((item) => item?.kind === 'image' && item.url);
  const signature = JSON.stringify(images.map((item) => [item.mediaId, item.url, item.fileName]));
  return h('div', { class: `draft-media-strip${images.length ? '' : ' is-empty'}`, dataset: { draftMedia: '1', mediaSignature: signature } },
    images.map((item) => h('div', { class: 'draft-media-item' },
      h('img', { src: item.url, alt: item.fileName || '待发送图片' }),
      h('button', { type: 'button', class: 'draft-media-remove', title: '移除图片', 'aria-label': `移除 ${item.fileName || '图片'}`, dataset: { action: 'remove-attachment', sessionId, mediaId: item.mediaId } }, icon('close')))));
}

function emptyHero({ title, subtitle, poseOverride, children = [] }) {
  const { delight } = vm.appearance(getState().view);
  const hello = greeting(new Date(), delight);
  return h('div', { class: 'hero', dataset: { itemKey: 'empty:hero' } },
    delight ? h('div', { class: 'fish-say', 'aria-live': 'polite', text: hello.text }) : null,
    h('button', { type: 'button', class: 'fish-poke', 'aria-label': '小鱼', dataset: { action: 'poke-fish' } }, fish(poseOverride || hello.pose, 'lg', { role: 'hero' })),
    title ? h('h2', { class: 'hero-title', text: title }) : null,
    subtitle ? h('p', { class: 'hero-sub', text: subtitle }) : null,
    children);
}

// ---------- Home / providers / settings ----------

function home(view) {
  const configured = (view.providers || []).some((p) => p.hasCredential && (p.models || []).some((m) => m.enabled !== false));
  let body;
  if (!configured) {
    body = emptyHero({ title: '先接入一个模型', subtitle: '选择服务商、填写 API Key，再添加模型。API Key 只保存在 Android 安全存储里。',
      children: [button('添加账号', 'configure-provider', {}, 'btn primary')] });
  } else if (!(view.sessions || []).length) {
    body = emptyHero({ title: '还没有会话', subtitle: '会话与分支只保存在本机。', children: [button('新会话', 'new-chat', {}, 'btn primary')] });
  } else {
    pruneSelection(view.sessions);
    body = h('div', { class: 'home-list' }, selecting() ? selectionBar(view.sessions) : null, sessionList(view));
  }
  return h('main', { class: 'page page-home' },
    vm.mascotState(view).disguise ? appHeader('文档', '最近打开', iconButton('plus', '新建', 'new-chat')) : appHeader('Moyu Chat', '会话与分支只保存在本机', iconButton('plus', '新会话', 'new-chat')),
    h('div', { class: 'page-body' }, body));
}

function reasoningCapability(model) {
  const levels = String(model.reasoningLevels || '').split(',').map((x) => x.trim()).filter(Boolean);
  return h('span', { class: 'chip quiet', text: levels.length ? `深度支持 · ${levels.join(' / ')}` : '深度支持 · Provider 默认' });
}


const PROTOCOL_SHORT = { 'openai-chat-completions': 'Chat', 'openai-responses': 'Responses', 'anthropic-messages': 'Messages' };
const RETENTION = { none: ['不保留数据', 'good'], '30d': ['保留 30 天', 'quiet'], training: ['可能用于训练', 'warn'] };

function priceText(p) {
  if (!p || p.input === undefined) return '未知';
  const cur = p.currency === 'CNY' ? '¥' : '$';
  return `${cur}${p.input} / ${cur}${p.output}${p.cacheRead !== undefined ? ` · 缓存 ${cur}${p.cacheRead}` : ''}`;
}

function modelFacts(model) {
  const sourced = (field, text) => (model.sources?.[field] === 'manual' ? `${text}（手动）` : text);
  const facts = [
    ['路线', `${model.routeLabel || '—'} · ${PROTOCOL_SHORT[model.protocol] || model.protocol}`],
    ['上下文', sourced('contextTokens', Number(model.contextTokens).toLocaleString('en-US'))],
    ['输出上限', sourced('maxOutputTokens', `${Number(model.maxOutputTokens).toLocaleString('en-US')}${model.protocol !== 'anthropic-messages' && (model.sources?.maxOutputTokens || 'default') === 'default' ? '（不发送）' : ''}`)],
    ['推理参数', model.reasoningWire || 'none'], ['工具调用', model.clientToolCalling || 'none'], ['Harness', model.harnessPreset || 'balanced'],
    ['输入 / 输出', `${model.inputModalities || 'text'} / ${model.outputModalities || 'text'}`],
    ['价格（百万 token）', priceText(model.pricing)],
  ];
  if (model.thinkingMode && model.thinkingMode !== 'provider-default') facts.push(['思考模式', `${model.thinkingMode}${model.thinkingBudgetTokens ? `:${model.thinkingBudgetTokens}` : ''}`]);
  if (model.continuationMode === 'native') facts.push(['跨轮续接', 'native']);
  if (model.toolCapabilities) facts.push(['厂商联网', model.toolCapabilities]);
  if (model.serviceTier) facts.push(['服务层级', model.serviceTier]);
  return h('dl', { class: 'facts' }, facts.map(([k, v]) => [h('dt', { text: k }), h('dd', { text: String(v ?? '—') })]));
}

function routeLine(route) {
  return h('div', { class: 'route-line', dataset: { key: route.routeId } },
    h('span', { class: 'chip', text: PROTOCOL_SHORT[route.protocol] || route.protocol }),
    h('span', { class: 'grow route-text' }, h('strong', { class: 'truncate', text: route.label }), h('small', { class: 'route-host truncate', text: route.host })),
    route.hostConfirmed === false
      ? button('确认域名', 'route-host-confirm', { routeId: route.routeId, host: route.host }, 'btn small')
      : h('span', { class: `chip ${route.reviewed ? 'good' : 'quiet'}`, text: route.reviewed ? '预设' : '自定义' }));
}

function probeRow(model) {
  const running = peekRemote(`probe:${model.modelProfileId}`)?.state === 'loading';
  const line = vm.probeLine(model);
  return h('div', { class: 'row probe-row', dataset: { probeSignature: JSON.stringify([model.probe || null, running]) } },
    h('small', { class: `grow probe-text${line?.tone === 'warn' ? ' warn' : ''}`, text: running ? '测试中…' : line ? line.text : '还没有测试过连接' }),
    button(running ? '测试中…' : '测试', 'model-probe', { modelProfileId: model.modelProfileId }, 'btn small ghost'));
}

function modelCard(provider, model) {
  const retention = RETENTION[model.retention];
  const source = model.catalogKey ? (model.catalogVerified ? ['目录', 'good'] : ['目录 · 未实测', 'quiet']) : ['手动声明', 'quiet'];
  const enabled = model.enabled !== false;
  return h('div', { class: `model-card${enabled ? '' : ' disabled'}`, dataset: { key: model.modelProfileId } },
    h('div', { class: 'row' }, h('strong', { class: 'grow truncate', text: model.displayName || model.modelId }),
      enabled ? null : h('span', { class: 'chip quiet', text: '已停用' }),
      h('span', { class: `chip ${source[1]}`, text: source[0] })),
    h('code', { class: 'model-id', text: `${model.modelId} · ${model.routeLabel || ''}` }),
    h('div', { class: 'row wrap' }, reasoningCapability(model), retention ? h('span', { class: `chip ${retention[1]}`, text: retention[0] }) : null),
    model.listed === false ? h('div', { class: 'notice-row warn' }, h('span', { class: 'grow', text: '端点的模型列表里没有这个 Model ID，请检查拼写或路线。' })) : null,
    h('details', { class: 'facts-wrap' }, h('summary', { text: '部署参数' }), modelFacts(model)),
    provider.hasCredential ? probeRow(model) : null,
    h('div', { class: 'row model-actions' },
      h('span', { class: 'grow' }), button('编辑', 'configure-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn small ghost'),
      enabled ? button('新会话', 'new-chat-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn small primary') : null));
}

function keyChip(provider) {
  const status = vm.keyStatus(provider);
  return h('button', { type: 'button', class: `chip chip-btn ${status.tone}`, title: provider.hasCredential ? '更换 API Key' : '填写 API Key', dataset: { action: 'provider-key', providerId: provider.providerId }, text: status.label });
}

/** Key check, balance and budget of one account. */
function guardRow(provider) {
  if (!provider.hasCredential) return null;
  const verifying = peekRemote(`verify:${provider.providerId}`)?.state === 'loading';
  const balance = peekRemote(`balance:${provider.providerId}`);
  const status = vm.keyStatus(provider);
  const budget = vm.budgetLine(provider);
  const lines = [];
  if (status.checkedAt) lines.push(h('small', { class: 'guard-text', text: `上次检查 ${vm.messageTime(status.checkedAt)}` }));
  if (balance) lines.push(h('small', { class: 'guard-text', text: balance.state === 'loading' ? '查询余额中…' : balance.state === 'error' ? `余额查询失败：${balance.error}` : vm.balanceText(balance.data) }));
  if (budget) lines.push(h('small', { class: `guard-text${budget.reached ? ' warn' : ''}`, text: budget.text }));
  return h('div', { class: 'guard-row' },
    h('div', { class: 'grow guard-lines' }, lines),
    button(verifying ? '检查中…' : '检查 Key', 'provider-verify', { providerId: provider.providerId }, 'btn small ghost'),
    provider.balanceSupported ? button('查余额', 'provider-balance', { providerId: provider.providerId }, 'btn small ghost') : null);
}

function providers(view) {
  return h('main', { class: 'page' },
    appHeader('账号与模型', null, button('添加', 'configure-provider', {}, 'btn small')),
    h('div', { class: 'page-body' },
      h('p', { class: 'page-note', text: '账号保存一把 API Key 和若干路线（协议 + 地址）；模型挂在某条路线上，每项能力都标明来自目录、默认值还是你的手动声明。' }),
      view.providers.length ? h('div', { class: 'stack' }, view.providers.map((provider) => h('article', { class: 'card provider-card', dataset: { key: provider.providerId } },
        h('div', { class: 'row' }, h('strong', { class: 'grow truncate', text: provider.displayName }),
          provider.presetName && provider.presetName !== provider.displayName ? h('span', { class: 'chip quiet', text: provider.presetName }) : null,
          keyChip(provider)),
        guardRow(provider),
        h('div', { class: 'route-lines' }, (provider.routes || []).map(routeLine)),
        h('div', { class: 'row' }, h('span', { class: 'grow' }), button('编辑账号', 'configure-provider', { providerId: provider.providerId }, 'btn small ghost'), button('添加模型', 'configure-model', { providerId: provider.providerId }, 'btn small')),
        (provider.models || []).map((model) => modelCard(provider, model)),
      ))) : emptyHero({ title: '还没有账号', subtitle: '选择一个服务商，填好 API Key，再添加模型。', children: [button('添加账号', 'configure-provider', {}, 'btn primary')] })));
}

function segmentedSetting(label, action, key, values, current) {
  return syncSegment(h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': label }, values.map(([value, text]) => h('button', {
    type: 'button', role: 'radio', 'aria-checked': value === current ? 'true' : 'false', class: value === current ? 'on' : '', dataset: { action, [key]: value }, text }))));
}

function switchControl(label, on, action, data) {
  return h('button', { type: 'button', role: 'switch', 'aria-checked': on ? 'true' : 'false', 'aria-label': label, class: `switch${on ? ' on' : ''}`, dataset: { action, ...data } }, h('span', { class: 'switch-knob' }));
}

function settingRow(title, description, control, stacked = false) {
  return h('div', { class: `setting-row${stacked ? ' stacked' : ''}` }, h('div', { class: 'grow' }, h('strong', { text: title }), description ? h('p', { class: 'setting-desc', text: description }) : null), control);
}

function settings(view) {
  const look = vm.appearance(view);
  const reasoningVisible = Boolean(view.ui?.showReasoning);
  const memoryEnabled = Boolean(view.ui?.crossChatMemoryEnabled);
  const wireCapture = view.ui?.wireCapture !== false;
  const notify = view.ui?.notifyInBackground !== false;
  const instruction = String(view.ui?.customInstruction || '').trim();
  return h('main', { class: 'page settings-page' }, appHeader('设置', null),
    h('div', { class: 'page-body stack' },
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '外观' }),
        settingRow('主题', '跟随系统时，状态栏和导航栏一起切换。', segmentedSetting('主题', 'theme', 'theme', [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']], look.theme), true),
        settingRow('背景色调', null, segmentedSetting('背景色调', 'tone', 'tone', [['sage', '灰绿'], ['warm', '暖米'], ['neutral', '中性']], look.tone), true),
        settingRow('动效与彩蛋', '小鱼的动作、问候、校验小印以外的小彩蛋。关闭后界面完全静止。系统开启“减少动画”时动作也会停止。', switchControl('动效与彩蛋', look.delight, 'toggle-delight', { enabled: look.delight ? 'false' : 'true' }))),
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '对话' }),
        settingRow('自定义指令', instruction ? `已设置 · ${instruction.length} 字：${instruction.length > 60 ? `${instruction.slice(0, 60)}…` : instruction}` : '对所有会话生效的指令，例如称呼、回答语言、格式偏好。未设置。',
          button(instruction ? '编辑' : '设置', 'edit-instruction', {}, 'btn small')),
        settingRow('显示模型推理', '只展示 endpoint 明确返回的 reasoning/thinking 文本。可见推理不会作为普通历史回灌；Provider-private continuation 只有在 Model Profile 明确声明 native 且标准/智能模式允许时才复用。', switchControl('显示模型推理', reasoningVisible, 'toggle-reasoning', { show: reasoningVisible ? 'false' : 'true' })),
        settingRow('后台完成通知', '应用在后台时，回答完成或失败会发一条系统通知，点开直接进入对应会话。你自己停止的回合不通知。Android 13 及以上需要允许通知权限。', switchControl('后台完成通知', notify, 'toggle-notify', { enabled: notify ? 'false' : 'true' })),
        settingRow('记录协议报文', '保存最近 30 个回合里每次模型调用的请求和响应原文，在回复详情的调用链里查看。不含请求头和 API Key，图片等长串只保留长度。', switchControl('记录协议报文', wireCapture, 'toggle-wire-capture', { enabled: wireCapture ? 'false' : 'true' })),
        settingRow('跨 Chat 用户记忆', '总开关只决定用户级 Memory/RAG 是否允许使用。关闭时所有模式都不跨 Chat；开启时标准/智能可检索相关本地历史，极简没有 Memory 调用。', switchControl('跨 Chat 用户记忆', memoryEnabled, 'toggle-memory', { enabled: memoryEnabled ? 'false' : 'true' }))),
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '辅助模块' }),
        h('p', { class: 'setting-desc', text: '对所有会话生效。关闭只会去掉当前模式本来会运行的辅助，不会给极简模式加功能；每轮都记下当时的开关，可在统计里按“辅助开关”对比。' }),
        helperModelRows(view),
        vm.helperSwitches(view).map((helper) => settingRow(helper.label, helper.description,
          switchControl(helper.label, helper.on, 'toggle-helper', { helper: helper.id, enabled: helper.on ? 'false' : 'true' })))),
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '新会话默认' }),
        settingRow('上下文模式', '极简：薄提示词 + 原始完整轮次。标准：原始上下文优先，允许用户记忆，50% 主动压缩到约 30%。智能：标准全部能力 + Task Router、reviewed prompt modules、evidence policy 与同模型二次 verifier。', segmentedSetting('上下文模式', 'default-context', 'mode', [['minimal', '极简'], ['standard', '标准'], ['smart', '智能']], view.ui?.defaultContextMode || 'standard'), true),
        settingRow('联网', '自动：先由 Web Planner 判断是否需要联网，再按已声明能力选择 Provider hosted Web、Moyu Host 检索或 client Web tools。开启：本轮强制实际联网取证。', segmentedSetting('联网', 'default-web', 'mode', [['off', '关闭'], ['auto', '自动'], ['on', '开启']], view.ui?.defaultWebMode || 'auto'), true),
        settingRow('标准模式子 Agent', '自动：仅当 Model Profile 明确支持 client tool calling 时，允许模型委派最多 4 个隔离的同模型子任务。极简永远关闭，智能默认自动。', segmentedSetting('标准模式子 Agent', 'default-subagent', 'mode', [['off', '关闭'], ['auto', '自动']], view.ui?.standardSubagentMode === 'auto' ? 'auto' : 'off'), true)),
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '数据边界' }),
        h('p', { class: 'setting-desc', text: 'Chat 使用自己的本地数据库；API Key 只保存在 Android 安全存储，不进入 WebView 或 SQLite。TaskProfile 与 Checkpoint 只是可重建的上下文控制状态。' }))));
}

/** Spec D15: the helper model and, once one is chosen, which helpers use it. */
function helperModelRows(view) {
  const helper = vm.helperModelView(view);
  return [settingRow('辅助模型', helper.id
    ? `${helper.missing ? '所选模型已删除或停用，辅助调用暂用会话的模型。' : ''}下面打开的辅助改用它；它不可用（Key、域名、预算）时退回会话的模型，调用链里写明原因。会话内容会发给它所在的服务商。`
    : '命名、路由这类杂活可以交给更便宜的模型。没选时，所有辅助调用都用会话的模型。',
  button(helper.id ? helper.name : '用会话的模型', 'open-helper-model', {}, 'btn small')),
  helper.id ? helper.uses.map((use) => settingRow(`${use.label}用辅助模型`, null,
    switchControl(`${use.label}用辅助模型`, use.on, 'toggle-helper-model-use', { helper: use.id, enabled: use.on ? 'false' : 'true' }))) : null];
}

// ---------- Stats ----------

const STATS_PERIODS = [[7, '7 天'], [30, '30 天'], [90, '90 天']];
let statsDays = 7;
let statsGroup = vm.STATS_GROUPINGS[0][0];
let statsArchived = true;
let lastStats = null;
export function setStatsDays(days) { statsDays = STATS_PERIODS.some(([d]) => d === Number(days)) ? Number(days) : 7; }
export function setStatsGroup(group) { statsGroup = vm.STATS_GROUPINGS.some(([g]) => g === group) ? group : vm.STATS_GROUPINGS[0][0]; }
export function setStatsArchived(include) { statsArchived = Boolean(include); }
/** Statistics are re-read every time the page is opened. */
export function forgetStats() {
  for (const [days] of STATS_PERIODS) for (const [group] of vm.STATS_GROUPINGS) for (const archived of [true, false]) forgetRemote(`stats:${days}:${group}:${archived}`);
}

function stats() {
  const entry = loadRemote(`stats:${statsDays}:${statsGroup}:${statsArchived}`, 'stats.get', { days: statsDays, groupBy: statsGroup, includeArchived: statsArchived });
  if (entry?.state === 'ready') lastStats = entry.data;
  const loading = !entry || entry.state === 'loading';
  let content;
  if (loading && !lastStats) content = h('p', { class: 'setting-desc', text: '读取中…' });
  else if (entry?.state === 'error') content = h('p', { class: 'setting-desc', text: `未能读取：${entry.error}` });
  else {
    const rows = vm.statsView(loading ? lastStats : entry.data);
    content = h('div', { class: `stack stats-rows${loading ? ' is-stale' : ''}`, 'aria-busy': loading ? 'true' : 'false' }, rows.length ? rows.map((row) => h('section', { class: 'card stats-card', dataset: { key: row.key } },
      h('div', { class: 'stats-head' }, h('strong', { class: 'grow truncate', text: row.title }), row.mode ? h('span', { class: 'chip quiet', text: row.mode }) : null),
      h('dl', { class: 'stats-grid' }, row.cells.map(([k, v]) => h('div', { class: 'stats-cell' }, h('dt', { text: k }), h('dd', { dataset: { swap: '' }, text: v }))))))
      : h('section', { class: 'card' }, h('p', { class: 'setting-desc', text: '这段时间还没有完成的回合。' })));
  }
  return h('main', { class: 'page stats-page' }, appHeader('统计', '真实使用中的速度、成本和辅助模块效果'),
    h('div', { class: 'page-body stack' },
      segmentedSetting('统计周期', 'stats-days', 'days', STATS_PERIODS.map(([d, label]) => [String(d), label]), String(statsDays)),
      h('div', { class: 'chip-set', role: 'radiogroup', 'aria-label': '分组' }, vm.STATS_GROUPINGS.map(([group, label]) => h('button', {
        type: 'button', role: 'radio', 'aria-checked': group === statsGroup ? 'true' : 'false', class: 'chip-toggle', dataset: { action: 'stats-group', group }, text: label }))),
      settingRow('包含已删除会话的回合', '删除会话时，它的回合记录仍保留在统计里。默认计入，它们同样花了时间和费用。', switchControl('包含已删除会话的回合', statsArchived, 'stats-archived', { enabled: statsArchived ? 'false' : 'true' })),
      h('p', { class: 'setting-desc', text: '耗时取中位数。费用按模型配置里的价格估算（含辅助调用），没有价格的模型不计；带“起”表示有调用没报用量，实际更高。辅助占比 = 路由、压缩、联网规划、校验、修正、子任务消耗的 token 占比；路由 / 联网 / 校验是实际执行的回合比例。' }),
      content));
}

// ---------- Conversation ----------

function conversationSubtitle(session) {
  const busy = vm.busyLabel(session);
  if (busy) return h('small', { class: 'truncate', dataset: { subtitle: busy }, text: busy });
  const fill = vm.contextFill(session);
  const base = `${session.modelName || session.model} · ${vm.contextLabel(session.contextMode)}模式`;
  // The model name gives way on narrow screens; the context fill stays readable.
  return h('small', { class: 'subtitle-line', dataset: { subtitle: JSON.stringify([base, fill?.percent ?? null, Boolean(fill?.warn)]) } }, h('span', { class: 'truncate', text: base }),
    fill ? h('span', { class: `ctx-fill${fill.warn ? ' warn' : ''}`, title: `上下文已用 ${vm.full(fill.used)} / ${vm.full(fill.budget)} token`, text: ` · 上下文 ${fill.percent}%` }) : null);
}

/** Shown while the session still runs on a copy of a model profile that has since been edited. */
function profileNotice(session) {
  if (!session.profileChanged || !session.canConfigureTurn || !session.modelProfileId) return null;
  return h('div', { class: 'profile-notice', dataset: { profileNotice: '1' } },
    h('span', { class: 'grow', text: '模型配置已更新，这个会话还在用旧配置' }),
    button('应用', 'apply-profile', { sessionId: session.localSessionId, modelProfileId: session.modelProfileId }, 'btn mini'));
}

function turnChip(session) {
  const [model, ...rest] = vm.turnSummary(session);
  return h('button', { type: 'button', class: 'turn-chip', disabled: !session.canConfigureTurn, 'aria-label': '本轮设置', dataset: { action: 'open-turn-settings', sessionId: session.localSessionId } },
    h('span', { class: 'turn-chip-model truncate', dataset: { swap: '' }, text: model }), h('span', { class: 'turn-chip-rest truncate', dataset: { swap: '' }, text: rest.length ? ` · ${rest.join(' · ')}` : '' }), icon('chevronDown', 'ico ico-xs'));
}

function primaryButton(session) {
  return session.canInterrupt
    ? h('button', { type: 'button', class: 'composer-send stop', 'aria-label': '停止生成', title: '停止生成', dataset: { action: 'interrupt', sessionId: session.localSessionId } }, icon('stop'))
    : h('button', { type: 'submit', class: 'composer-send send-btn', disabled: !session.canSend, 'aria-label': '发送消息', title: '发送' }, icon('send'));
}

function conversation(view) {
  const session = view.activeSession;
  if (!session) return home(view);
  const canImage = vm.imageInput(session);
  return h('main', { class: 'conversation-layout', dataset: { sessionId: session.localSessionId, configSignature: configSignature(session) } },
    h('header', { class: 'app-head conversation-head' },
      iconButton('menu', '会话列表', 'drawer-open', {}, 'icon-btn nav-toggle'),
      h('div', { class: 'head-title grow conversation-title' }, h('strong', { class: 'truncate', dataset: { swap: '' }, text: headTitle(view, session) }), conversationSubtitle(session)),
      iconButton('more', '会话操作', 'conversation-menu', { sessionId: session.localSessionId, sessionTitle: session.title || '' })),
    turnNavigator(session),
    h('section', { class: 'timeline-scroll', dataset: { sessionId: session.localSessionId } },
      Number(session.displayOmittedInheritedItems || 0) > 0
        ? h('div', { class: 'history-omitted', text: `更早 ${Number(session.displayOmittedInheritedItems)} 条继承历史仍用于上下文和检索，这里不展开。` }) : null,
      h('div', { class: 'timeline', dataset: { morph: 'own' } })),
    h('form', { class: 'composer', dataset: { form: 'composer', sessionId: session.localSessionId } },
      iconButton('down', '回到底部', 'jump-latest', {}, 'icon-btn jump-latest is-hidden'),
      perchNode(session),
      profileNotice(session),
      draftMediaStrip(session.draftMedia, session.localSessionId),
      h('textarea', { name: 'message', rows: '1', value: session.composerDraft || '', dataset: { projectedDraft: session.composerDraft || '' }, placeholder: vm.composerPlaceholder(session), 'aria-label': '消息' }),
      h('div', { class: 'composer-actions' },
        h('button', { type: 'button', class: 'composer-icon attachment-btn', disabled: !session.canAttachImage, title: canImage ? '添加图片' : '当前模型未声明图片输入能力', 'aria-label': canImage ? '添加图片' : '当前模型不支持图片输入', dataset: { action: 'pick-attachment', sessionId: session.localSessionId } }, icon('plus')),
        turnChip(session),
        h('span', { class: 'grow' }),
        h('div', { class: 'composer-primary' }, primaryButton(session)))));
}

/** The title bar text: the session title, or a document name under the boss key. */
function headTitle(view, session) { return vm.mascotState(view).disguise ? vm.docName(session.localSessionId) : (session.title || ''); }

/** The perch fish's base pose: the live stage while a turn runs, resting otherwise. */
function perchBase(session) {
  const live = vm.liveStage(session);
  return live ? perchPose(live.pose, live.chars) : 'idle';
}

/** The fish sitting on the composer once a conversation has messages (an empty one shows the big fish instead). */
function perchNode(session) {
  if (!(session.messages || []).length) return null;
  return h('button', { type: 'button', class: 'perch', 'aria-label': '小鱼', dataset: { action: 'perch-fish', key: 'perch' } }, fish(perchBase(session), 'sm', { role: 'perch' }));
}

function syncPerch(composer, session) {
  const perch = composer.querySelector('.perch');
  const wanted = (session.messages || []).length > 0;
  if (perch && !wanted) perch.remove();
  else if (!perch && wanted) composer.querySelector('.jump-latest')?.after(withFx(perchNode(session), 'pop'));
  else if (perch) setPose(perch.querySelector('.fish'), perchBase(session));
}

function configSignature(session) {
  return JSON.stringify({ modelProfileId: session.modelProfileId, inputModalities: session.inputModalities });
}

function turnNavigator(session, open = false) {
  const turns = vm.userTurns(session?.messages || []);
  if (turns.length < TURN_NAV_MIN_TURNS) return null;
  const signature = JSON.stringify(turns.map((turn) => [turn.key, turn.preview]));
  return h('aside', { class: `turn-nav${open ? ' open' : ''}`, dataset: { turnNav: '1', turnNavSignature: signature }, 'aria-label': '会话导航' },
    h('button', { type: 'button', class: 'turn-nav-toggle', dataset: { action: 'toggle-turn-nav' }, 'aria-expanded': open ? 'true' : 'false', 'aria-label': `会话导航，共 ${turns.length} 个用户回合`, title: `会话导航 · ${turns.length} 个用户回合` }, icon('list')),
    h('div', { class: 'turn-nav-list' }, turns.map((turn) => h('button', {
      type: 'button', class: 'turn-nav-item', dataset: { action: 'jump-turn', turnKey: turn.key }, title: `${String(turn.number).padStart(2, '0')} · ${turn.preview}`, 'aria-label': `第 ${turn.number} 个用户回合：${turn.preview}`,
    }, h('span', { class: 'turn-nav-index', text: String(turn.number).padStart(2, '0') }), h('span', { class: 'turn-nav-label', text: turn.preview })))));
}

function reconcileTurnNavigator(layout, session) {
  const existing = layout?.querySelector('.turn-nav[data-turn-nav]');
  const desired = turnNavigator(session, Boolean(existing?.classList.contains('open')));
  if (!desired) { existing?.remove(); return; }
  if (!existing) { layout.querySelector('.timeline-scroll')?.before(desired); return; }
  if (existing.dataset.turnNavSignature !== desired.dataset.turnNavSignature) existing.replaceWith(desired);
}

export function syncTurnNavigator(scroller = document.querySelector('.timeline-scroll')) {
  const nav = document.querySelector('.turn-nav[data-turn-nav]');
  if (!nav || !scroller) return false;
  const anchors = [...scroller.querySelectorAll('.timeline [data-turn-anchor="user"]')];
  if (!anchors.length) return false;
  const line = scroller.getBoundingClientRect().top + 12;
  let activeKey = anchors[0].dataset.itemKey || '';
  for (const anchor of anchors) {
    if (anchor.getBoundingClientRect().top <= line) activeKey = anchor.dataset.itemKey || activeKey;
    else break;
  }
  nav.querySelectorAll('.turn-nav-item').forEach((item) => {
    const active = item.dataset.turnKey === activeKey;
    item.classList.toggle('active', active);
    if (active) item.setAttribute('aria-current', 'true'); else item.removeAttribute('aria-current');
  });
  return true;
}

// ---------- Timeline items ----------

function sourceRow(sources = []) {
  const safe = (sources || []).filter((source) => source?.url?.startsWith('https://')).slice(0, 12);
  if (!safe.length) return null;
  return h('div', { class: 'sources', role: 'list', 'aria-label': '来源' }, safe.map((source, index) => {
    let host = '';
    try { host = new URL(source.url).hostname.replace(/^www\./, ''); } catch { host = ''; }
    return h('button', { type: 'button', role: 'listitem', class: 'source', title: source.url, dataset: { action: 'open-source', url: source.url } },
      h('i', { text: String(index + 1) }), h('span', { class: 'truncate', text: source.title || host || source.url }), host ? h('small', { text: host }) : null);
  }));
}

function summaryParts(usage, item, delight) {
  const u = vm.turnUsage(usage);
  const parts = [];
  if (!u) return [h('span', { class: 'muted', text: item.incomplete ? '' : '暂无用量' })];
  const totals = vm.turnTotals(u);
  parts.push(h('span', { text: `${vm.compact(totals.input)}→${vm.compact(totals.output)}` }));
  if (u.cacheRate !== null) {
    const pct = `${Math.round(u.cacheRate * 100)}%`;
    parts.push(delight && u.cacheRate >= 0.9 ? h('span', { class: 'flair', title: '缓存命中 ≥ 90%', text: `满塘 ${pct}` }) : h('span', { text: `缓存 ${pct}` }));
  }
  if (u.totalMs !== null) {
    parts.push(delight && u.firstMs !== null && u.firstMs < 1000
      ? h('span', { class: 'flair quick', title: `首字 ${vm.secs(u.firstMs)}`, text: `秒回 ${vm.secs(u.totalMs)}` })
      : h('span', { text: vm.secs(u.totalMs) }));
  }
  return parts;
}

function detailRows(item, sessionId, canAct) {
  const u = vm.turnUsage(item.__usage);
  const rows = [['模型', item.modelName || item.model || '—'], ['深度', item.reasoningAvailable === false ? '关闭' : vm.effortLabel(item.reasoningEffort)]];
  if (u) {
    rows.push(['主调用 入/出', `${u.input !== null ? vm.full(u.input) : u.estimatedInput !== null ? `≈${vm.full(u.estimatedInput)}` : '—'} / ${vm.full(u.output)}`]);
    if (u.cached !== null) rows.push(['缓存命中', `${vm.full(u.cached)}${u.cacheRate !== null ? ` · ${(u.cacheRate * 100).toFixed(1)}%` : ''}${u.cacheWrite ? ` · 写入 ${vm.full(u.cacheWrite)}` : ''}`]);
    if (u.reasoning) rows.push(['推理', `${vm.full(u.reasoning)} token`]);
    rows.push(['首字 / 总耗', `${vm.secs(u.firstMs)} / ${vm.secs(u.totalMs)}`]);
    if (u.speed) rows.push(['输出速度', `${u.speed.toFixed(1)} token/s`]);
    if (u.subagents) rows.push([`子任务 ×${u.subagents}`, `${vm.full(u.childInput)} / ${vm.full(u.childOutput)}`]);
    if (u.auxiliary) rows.push([`辅助调用 ×${u.auxiliary}`, `${vm.full(u.auxInput)} / ${vm.full(u.auxOutput)}`]);
    if (u.subagents || u.auxiliary) rows.push([u.incomplete ? '已报告合计' : '合计 入/出', `${vm.full(u.totalInput)} / ${vm.full(u.totalOutput)}`]);
    if (u.incomplete) rows.push(['用量', '部分请求未报告完整用量']);
    if (u.retries) rows.push(['自动重试', `${u.retries} 次（限流或服务繁忙）`]);
    if (u.cost) rows.push(['费用（估算）', `${vm.money(u.cost.amount, u.cost.currency)}${u.cost.partial ? ' 起' : ''}${u.cost.offPeak ? ' · 闲时价' : ''}`]);
  }
  if (item.webBackend) rows.push(['联网', `${item.webBackend}${item.sources?.length ? ` · ${item.sources.length} 来源` : ''}`]);
  const verification = vm.verificationState(item.verification);
  const out = [h('dl', { class: 'turn-details' }, rows.map(([k, v]) => [h('dt', { text: k }), h('dd', { text: v })]))];
  if (verification) {
    out.push(h('div', { class: 'verify-row' },
      h('span', { class: `chip ${verification.tone}`, text: verification.label }),
      verification.risk !== null ? h('small', { text: `风险 ${verification.risk.toFixed(2)}` }) : null,
      verification.coverageReason ? h('small', { class: 'grow', text: verification.coverageReason }) : h('span', { class: 'grow' }),
      verification.recorded ? h('span', { class: 'chip quiet', text: '反馈已记录' })
        : canAct && verification.feedbackEligible ? [
          button('校验正确', 'verification-feedback', { sessionId, localSeq: item.localSeq, label: 'accurate' }, 'btn small ghost'),
          verification.verdict === 'fail'
            ? button('误判', 'verification-feedback', { sessionId, localSeq: item.localSeq, label: 'false_alarm' }, 'btn small ghost')
            : button('漏错', 'verification-feedback', { sessionId, localSeq: item.localSeq, label: 'missed_error' }, 'btn small ghost'),
        ] : null));
  }
  if (verification?.preRepairText) {
    out.push(h('div', { class: 'compare-row' }, button('看修正前的回答', 'show-pre-repair', { sessionId, localSeq: item.localSeq }, 'btn small ghost')));
  }
  if (verification?.repairCandidateText) {
    out.push(h('div', { class: 'compare-row' }, button('看没采用的修正版', 'show-repair-candidate', { sessionId, localSeq: item.localSeq }, 'btn small ghost')));
  }
  if (item.turnId && !item.inherited && Number(item.answers) > 1) {
    out.push(h('div', { class: 'compare-row' }, button(`对比这个问题的 ${item.answers} 次回答`, 'open-answers', { sessionId, turnId: item.turnId }, 'btn small ghost')));
  }
  if (item.turnId && !item.inherited) out.push(traceSection(sessionId, item.turnId));
  return h('div', { class: 'turn-more fold fx-unfold', dataset: { exit: 'fold' } }, h('div', { class: 'fold-body' }, out));
}

/** Stage-by-stage view of the turn, fetched when the details are first expanded. */
function traceSection(sessionId, turnId) {
  const entry = loadTrace(sessionId, turnId);
  const section = h('section', { class: 'trace', 'aria-label': '调用链' });
  const view = entry?.state === 'ready' ? vm.traceView(entry.data) : null;
  section.append(h('div', { class: 'trace-head' }, h('span', { text: '调用链' }),
    view?.helperShare !== null && view?.helperShare !== undefined ? h('small', { text: `辅助调用占 ${Math.round(view.helperShare * 100)}% token` }) : null));
  if (!entry || entry.state === 'loading') { section.append(h('p', { class: 'trace-empty', text: '读取中…' })); return section; }
  if (entry.state === 'error' || !view) {
    section.append(h('p', { class: 'trace-empty' }, `未能读取：${entry.error || '没有记录'} `, button('重试', 'trace-retry', { turnId }, 'btn small ghost')));
    return section;
  }
  section.append(h('ol', { class: 'trace-list fx-fade' }, view.rows.map((row) => {
    const fill = h('span', { class: 'trace-fill fx-grow' });
    fill.style.width = `${Math.max(row.share > 0 ? 2 : 0, Math.round(row.share * 100))}%`;
    return h('li', { class: `trace-row ${row.status || ''}` },
      h('span', { class: 'trace-stage', text: row.label }),
      h('span', { class: 'trace-bar', 'aria-hidden': 'true' }, fill),
      h('span', { class: 'trace-ms', text: row.status === 'skipped' ? row.statusLabel : row.ms !== null ? vm.secs(row.ms) : row.statusLabel }),
      row.note || row.usage ? h('span', { class: 'trace-note', text: [row.status !== 'ok' && row.status !== 'skipped' ? row.statusLabel : '', row.note, row.usage].filter(Boolean).join(' · ') }) : null);
  })));
  if (view.terminalCode) section.append(h('p', { class: 'trace-empty', text: `结束：${view.statusLabel} · ${view.terminalCode}` }));
  for (const note of view.notes) section.append(h('p', { class: 'trace-empty', text: note }));
  const calls = vm.traceCalls(entry.data);
  if (calls.length) section.append(h('div', { class: 'trace-calls' }, h('span', { text: '报文' }),
    calls.map((call) => button(call.label, 'open-payload', { sessionId, turnId, callIndex: call.callIndex, title: `${call.label} · 协议报文` }, 'btn small ghost'))));
  return section;
}

function summaryFooter(item, ctx, canAct) {
  const key = vm.timelineKey(item);
  const expanded = expandedTurns.has(key);
  const verification = vm.verificationState(item.verification);
  const signature = JSON.stringify({ usage: item.__usage || null, v: item.verification || null, canAct, expanded, d: ctx.delight, inc: item.incomplete, src: item.sources?.length || 0, wb: item.webBackend || '', tr: expanded ? peekTrace(item.turnId)?.state || '' : '', r: item.rating || 0, a: item.answers || 0, ver: item.version || null, run: ctx.running });
  return h('footer', { class: 'turn-foot', dataset: { footerSignature: signature } },
    h('div', { class: 'turn-sum' },
      h('button', { type: 'button', class: 'sum-toggle', 'aria-expanded': expanded ? 'true' : 'false', 'aria-label': expanded ? '收起用量详情' : '展开用量详情', dataset: { action: 'toggle-turn-details', itemKey: key } },
        summaryParts(item.__usage, item, ctx.delight), item.incomplete ? h('span', { class: 'chip warn', text: item.terminalCode === 'interrupted' ? '已中断' : '未完成' }) : null,
        item.rating ? h('span', { class: `rate-mark ${item.rating > 0 ? 'up' : 'down'}`, title: item.rating > 0 ? '好答案' : '不好' }, icon(item.rating > 0 ? 'thumbUp' : 'thumbDown', 'ico ico-xs')) : null,
        Number(item.answers) > 1 ? h('span', { class: 'chip quiet', title: '这个问题有多次回答', text: `${item.answers} 答` }) : null,
        icon(expanded ? 'chevronUp' : 'chevronDown', 'ico ico-xs')),
      verification?.seal ? h('span', { class: `seal${verification.seal === '存疑' ? ' doubt' : ''}`, title: verification.label, text: verification.seal }) : null,
      versionSwitch(item, ctx),
      canAct ? iconButton('copy', '复制回答', 'copy-message', { sessionId: ctx.sessionId, localSeq: item.localSeq }, 'icon-btn mini') : null,
      canAct ? iconButton('more', '回答操作', 'message-menu', { sessionId: ctx.sessionId, localSeq: item.localSeq, role: 'assistant' }, 'icon-btn mini') : null),
    expanded ? detailRows(item, ctx.sessionId, canAct) : null);
}

/** Spec G3: "‹ i/n ›" between the versions of a message; switching waits for a running turn. */
function versionSwitch(item, ctx) {
  const version = vm.messageVersion(item);
  if (!version || item.inherited) return null;
  const step = (seq, name, label) => h('button', { type: 'button', class: 'icon-btn mini', 'aria-label': label, title: label, disabled: !seq || ctx.running,
    dataset: { action: 'version-select', sessionId: ctx.sessionId, localSeq: seq || '' } }, icon(name));
  return h('span', { class: 'version-switch' }, step(version.prevSeq, 'back', '上一个版本'),
    h('small', { class: 'version-label', text: version.label }), step(version.nextSeq, 'forward', '下一个版本'));
}

function liveLabel(item, ctx) {
  const stage = vm.stageInfo(item.streamStage);
  const chars = String(item.text || '').length;
  const label = chars ? `${stage.label} · ${chars} 字` : stage.label;
  return ctx.delight && vm.runningSeconds(ctx.session) >= 10 ? `${label} · 点小鱼摸会儿鱼` : label;
}

function liveFooter(item, ctx) {
  return h('footer', { class: 'turn-foot live' },
    h('div', { class: 'turn-live', role: 'status' }, h('span', { class: 'live-dot', 'aria-hidden': 'true' }), h('span', { class: 'live-label', text: liveLabel(item, ctx) })));
}

function isFinal(item, ctx) {
  return !item.streaming && !ctx.running && Number(item.localSeq) > 0 && !item.inherited;
}

function reasoningBlock(item, ctx) {
  const reasoning = String(item.reasoning || '');
  if (!reasoning || !ctx.showReasoning) return null;
  return h('details', { class: 'reasoning-block', open: Boolean(item.streaming && ctx.running) },
    h('summary', { text: item.streaming && ctx.running && !item.text ? '推理中…' : `推理 · ${reasoning.length} 字` }),
    h('pre', { class: 'reasoning-text', dataset: { selectionScope: 'reasoning' }, text: reasoning }));
}

function assistantNode(item, ctx) {
  const streaming = Boolean(item.streaming && ctx.running);
  const body = h('div', { class: 'message markdown-body', dataset: { messageBody: 'markdown', selectionScope: 'message' } });
  renderMarkdownInto(body, item.text || '', streaming);
  return h('article', { class: `msg assistant${item.inherited ? ' inherited' : ''}`, dataset: { itemKey: vm.timelineKey(item), streaming: streaming ? '1' : '0' } },
    item.inherited ? h('span', { class: 'chip quiet inherited-tag', text: '历史' }) : null,
    reasoningBlock(item, ctx), item.text ? body : null, mediaGallery(item.attachments), sourceRow(item.sources),
    streaming ? liveFooter(item, ctx) : summaryFooter(item, ctx, isFinal(item, ctx)));
}

function userNode(item, ctx) {
  const canAct = isFinal(item, ctx);
  return h('article', { class: `msg user${item.inherited ? ' inherited' : ''}`, dataset: { itemKey: vm.timelineKey(item), turnAnchor: 'user' } },
    item.text ? h('div', { class: 'message user-bubble', dataset: { messageBody: 'plain', selectionScope: 'message' }, text: item.text }) : null,
    mediaGallery(item.attachments),
    h('div', { class: 'msg-meta' }, item.inherited ? h('span', { class: 'chip quiet', text: '历史' }) : null, versionSwitch(item, ctx), h('small', { text: vm.messageTime(item.createdAt) }),
      canAct ? iconButton('more', '消息操作', 'message-menu', { sessionId: ctx.sessionId, localSeq: item.localSeq, role: 'user' }, 'icon-btn mini') : null));
}

function errorNode(item, ctx) {
  return h('article', { class: 'msg error', dataset: { itemKey: vm.timelineKey(item) } },
    fish('fail', 'sm', { static: '1' }), h('div', { class: 'grow' }, h('strong', { text: item.summary }), h('code', { text: item.code })),
    ctx ? versionSwitch(item, ctx) : null);
}

function timelineItem(item, ctx) {
  if (item.kind === 'message' && item.role === 'assistant') return assistantNode(item, ctx);
  if (item.kind === 'message' && item.role === 'user') return userNode(item, ctx);
  if (item.kind === 'error') return errorNode(item, ctx);
  return null;
}

/** Updates an assistant reply in place: the Markdown body and the live mascot persist across patches. */
function patchAssistant(node, item, ctx) {
  const streaming = Boolean(item.streaming && ctx.running);
  node.dataset.streaming = streaming ? '1' : '0';

  const reasoning = node.querySelector('.reasoning-block');
  const desiredReasoning = reasoningBlock(item, ctx);
  if (reasoning && desiredReasoning) {
    const text = reasoning.querySelector('.reasoning-text');
    if (text && text.textContent !== item.reasoning) text.textContent = item.reasoning;
    const summary = reasoning.querySelector('summary');
    const label = desiredReasoning.querySelector('summary').textContent;
    if (summary && summary.textContent !== label) summary.textContent = label;
  } else if (!reasoning && desiredReasoning) node.prepend(desiredReasoning);
  else if (reasoning && !desiredReasoning) reasoning.remove();

  let body = node.querySelector('.markdown-body');
  if (item.text) {
    if (!body) {
      body = h('div', { class: 'message markdown-body', dataset: { messageBody: 'markdown', selectionScope: 'message' } });
      const reasoningBlockNode = node.querySelector('.reasoning-block');
      // Visible output always sits below the reasoning block.
      if (reasoningBlockNode) reasoningBlockNode.after(body); else node.prepend(body);
    }
    renderMarkdownInto(body, item.text, streaming);
  } else body?.remove();

  replaceBySignature(node, '.media-grid', mediaGallery(item.attachments), 'mediaSignature', node.querySelector('.sources') || node.querySelector('.turn-foot'));
  const sources = node.querySelector('.sources');
  const desiredSources = sourceRow(item.sources);
  if (!sources && desiredSources) node.querySelector('.turn-foot')?.before(desiredSources);
  else if (sources && !desiredSources) sources.remove();
  else if (sources && desiredSources && sources.childElementCount !== desiredSources.childElementCount) sources.replaceWith(desiredSources);

  const footer = node.querySelector('.turn-foot');
  if (streaming) {
    const live = footer?.classList.contains('live') ? footer : null;
    if (!live) { const next = withFx(liveFooter(item, ctx), 'fade'); if (footer) footer.replaceWith(next); else node.append(next); return; }
    const label = liveLabel(item, ctx);
    const labelNode = live.querySelector('.live-label');
    if (labelNode.textContent !== label) labelNode.textContent = label;
    return;
  }
  const desired = summaryFooter(item, ctx, isFinal(item, ctx));
  if (!footer) node.append(desired);
  else if (footer.classList.contains('live')) footer.replaceWith(withFx(desired, 'fade'));
  else if (footer.dataset.footerSignature !== desired.dataset.footerSignature) morph(footer, desired);
}

function replaceBySignature(node, selector, desired, key, before) {
  const current = node.querySelector(selector);
  if (current && desired) { if (current.dataset[key] !== desired.dataset[key]) current.replaceWith(desired); }
  else if (!current && desired) { if (before) before.before(desired); else node.append(desired); }
  else if (current && !desired) current.remove();
}

function patchItem(node, item, ctx) {
  if (item.kind === 'message' && item.role === 'assistant' && node.classList.contains('assistant')) { patchAssistant(node, item, ctx); return node; }
  if (item.kind === 'message' && item.role === 'user' && node.classList.contains('user')) {
    const desired = userNode(item, ctx);
    if (node.textContent !== desired.textContent || Boolean(node.querySelector('[data-action="message-menu"]')) !== Boolean(desired.querySelector('[data-action="message-menu"]'))
        || node.querySelectorAll('.version-switch button:disabled').length !== desired.querySelectorAll('.version-switch button:disabled').length) return morph(node, desired);
    replaceBySignature(node, '.media-grid', mediaGallery(item.attachments), 'mediaSignature', node.querySelector('.msg-meta'));
    return node;
  }
  return node;
}

function reconcileTimeline(timeline, items, ctx, animate = false) {
  const desiredItems = vm.timelineProjection(items);
  const existing = new Map([...timeline.children].map((node) => [node.dataset.itemKey, node]));
  const used = new Set();
  let reference = timeline.firstElementChild;
  const place = (key, build, patch) => {
    let node = existing.get(key);
    const replacingReference = node && node === reference;
    if (node && patch) node = patch(node);
    else if (!node) { node = build(); if (animate) withFx(node, 'rise'); }
    if (!node) return;
    // A patch may replace the node in place; it then stands where the old reference stood.
    if (replacingReference || (reference && reference.parentNode !== timeline)) reference = node.parentNode === timeline ? node : null;
    node.dataset.itemKey = key;
    used.add(key);
    if (node !== reference) timeline.insertBefore(node, reference);
    reference = node.nextElementSibling;
  };
  if (!desiredItems.length) place('empty:hero', () => conversationHero(ctx.session), null);
  for (const item of desiredItems) {
    place(vm.timelineKey(item), () => timelineItem(item, ctx), item.kind === 'message' ? (node) => patchItem(node, item, ctx) : null);
  }
  const retry = vm.retrySeq(ctx.session);
  if (retry) place(`retry:${retry}`, () => retryRow(ctx), null);
  const resume = vm.resumeSeq(ctx.session);
  if (resume) place(`resume:${resume}`, () => resumeRow(ctx, resume), null);
  for (const [key, node] of existing) if (!used.has(key)) node.remove();
}

function retryRow(ctx) {
  return h('div', { class: 'retry-row' },
    h('small', { class: 'grow', text: '用当前设置重新回答，失败的版本保留' }),
    button('重试', 'retry-turn', { sessionId: ctx.sessionId }, 'btn mini'));
}

/** Spec H5: after "回到这里" or an unsent edit, the later messages are one tap away. */
function resumeRow(ctx, seq) {
  return h('div', { class: 'retry-row' },
    h('small', { class: 'grow', text: '后面还有内容，保留在另一个版本里' }),
    button('回到最新', 'version-select', { sessionId: ctx.sessionId, localSeq: seq }, 'btn mini'));
}

function conversationHero(session) {
  return emptyHero({ title: session.modelName || session.model, subtitle: `${session.providerName} · ${vm.contextLabel(session.contextMode)}模式 · 联网${vm.WEB_LABELS[session.webMode] || '自动'}`,
    children: [h('div', { class: 'caps' }, vm.capabilities(session).map((cap) => h('span', { class: `cap${cap.on ? '' : ' off'}`, text: cap.label }))),
      h('p', { class: 'hero-note', text: '能力来自这个部署的 Model Profile 声明' })] });
}

function timelineContext(view, session) {
  return { sessionId: session.localSessionId, session, running: session.state === 'running', showReasoning: Boolean(view.ui?.showReasoning), delight: vm.appearance(view).delight };
}

function selectionInside(node) {
  const selection = window.getSelection?.();
  if (!node || !selection || selection.isCollapsed || !selection.anchorNode || !selection.focusNode) return false;
  return node.contains(selection.anchorNode) || node.contains(selection.focusNode);
}

function resizeTextarea(input) {
  input.style.height = 'auto';
  const max = 160;
  input.style.height = `${Math.min(max, Math.max(44, input.scrollHeight))}px`;
  input.style.overflowY = input.scrollHeight > max ? 'auto' : 'hidden';
}
export { resizeTextarea };

function noteMilestones(view, session) {
  if (!vm.appearance(view).delight) return;
  const total = vm.sessionTokenTotal(session.messages);
  const previous = milestoneTotals.get(session.localSessionId);
  milestoneTotals.set(session.localSessionId, total);
  if (previous === undefined) return;
  const crossed = MILESTONES.filter(([limit]) => previous < limit && total >= limit).pop();
  if (crossed) { setNotice({ level: 'info', text: `这条会话已经游过 ${crossed[1]} token。` }); milestone(); }
}

/** Session fields a streaming patch carries; while they hold still, a patch changed only the live reply. */
const STREAM_SESSION_FIELDS = ['state', 'canSend', 'canInterrupt', 'canConfigureTurn', 'contextPreparing', 'webPreparingStage', 'postProcessingStage'];

/**
 * Fast path for a streaming patch (20 per second while a reply streams): when only the live reply changed,
 * patch that one node instead of reconciling the whole timeline, drawer, navigator and composer, so the
 * per-frame cost does not grow with the length of the conversation. Returns false when the caller must
 * fall back to {@link patchConversation}.
 */
export function patchStreamItem(change) {
  const view = getState().view;
  const session = view?.activeSession;
  const before = change?.previous?.activeSession;
  const item = change?.item;
  if (!session || !before || !item || view.route !== 'conversation' || before.localSessionId !== session.localSessionId) return false;
  if (item.kind !== 'message' || item.role !== 'assistant') return false;
  if (STREAM_SESSION_FIELDS.some((key) => (before[key] ?? null) !== (session[key] ?? null))) return false;
  const scroll = document.querySelector('.timeline-scroll');
  const timeline = scroll?.querySelector('.timeline');
  if (!timeline || scroll.dataset.sessionId !== session.localSessionId || selectionInside(timeline)) return false;
  const messages = Array.isArray(session.messages) ? session.messages : [];
  const index = messages.findIndex((entry) => Number(entry?.localSeq) === Number(item.localSeq));
  if (index < 0) return false;
  // Only rows up to the next user message can attach to this reply (its usage row), so project just those.
  let end = index + 1;
  while (end < messages.length && !(messages[end]?.kind === 'message' && messages[end]?.role === 'user')) end += 1;
  const projected = vm.timelineProjection(messages.slice(index, end))[0];
  const key = vm.timelineKey(projected);
  const node = timeline.children.length ? [...timeline.children].find((child) => child.dataset.itemKey === key) : null;
  if (!node || !node.classList.contains('assistant')) return false;
  const anchor = captureTimelineAnchor();
  patchAssistant(node, projected, timelineContext(view, session));
  const perch = document.querySelector('form[data-form="composer"] .perch .fish');
  if (perch) setPose(perch, perchBase(session));
  if (anchor) restoreTimelineAnchor(anchor, session.localSessionId);
  return true;
}

/** Patch the volatile conversation projection without replacing the focused composer/IME subtree. */
export function patchConversation() {
  const view = getState().view;
  const session = view?.activeSession;
  const scroll = document.querySelector('.timeline-scroll');
  const layout = document.querySelector('.conversation-layout');
  if (!session || view.route !== 'conversation' || !scroll || scroll.dataset.sessionId !== session.localSessionId || !layout) return false;
  if (layout.dataset.configSignature !== configSignature(session)) return false;
  applyRoot(view);
  renderDrawer(view);
  refreshSheet(view);
  const ctx = timelineContext(view, session);
  const timeline = scroll.querySelector('.timeline');
  const anchor = captureTimelineAnchor();
  const timelineDeferred = Boolean(timeline && selectionInside(timeline));
  if (timeline && !timelineDeferred) reconcileTimeline(timeline, session.messages, ctx, true);
  reconcileTurnNavigator(layout, session);
  noteMilestones(view, session);

  const title = document.querySelector('.conversation-title strong');
  const titleText = headTitle(view, session);
  if (title && title.textContent !== titleText) { title.textContent = titleText; swapIn(title); }
  const subtitle = document.querySelector('.conversation-title small');
  const nextSubtitle = conversationSubtitle(session);
  if (subtitle && subtitle.dataset.subtitle !== nextSubtitle.dataset.subtitle) swapIn(morph(subtitle, nextSubtitle));
  const menu = document.querySelector('.conversation-head [data-action="conversation-menu"]');
  if (menu) menu.dataset.sessionTitle = session.title || '';

  const candidate = document.querySelector('form[data-form="composer"]');
  const composer = candidate?.dataset.sessionId === session.localSessionId ? candidate : null;
  if (composer) {
    const notice = composer.querySelector('[data-profile-notice]');
    const desiredNotice = profileNotice(session);
    if (notice && !desiredNotice) notice.remove();
    else if (!notice && desiredNotice) composer.querySelector('[data-draft-media]')?.before(withFx(desiredNotice, 'fade'));
    const currentDraftMedia = composer.querySelector('[data-draft-media]');
    const desiredDraftMedia = draftMediaStrip(session.draftMedia, session.localSessionId);
    if (currentDraftMedia && currentDraftMedia.dataset.mediaSignature !== desiredDraftMedia.dataset.mediaSignature) currentDraftMedia.replaceWith(desiredDraftMedia);
    else if (!currentDraftMedia) composer.prepend(desiredDraftMedia);
    syncPerch(composer, session);
    const primary = composer.querySelector('.composer-primary');
    const isStop = Boolean(primary?.querySelector('[data-action="interrupt"]'));
    if (primary && isStop !== Boolean(session.canInterrupt)) primary.replaceChildren(withFx(primaryButton(session), 'pop'));
    else primary?.querySelector('.send-btn')?.toggleAttribute('disabled', !session.canSend);
    const attachment = composer.querySelector('.attachment-btn');
    if (attachment) attachment.disabled = !session.canAttachImage;
    const chip = composer.querySelector('.turn-chip');
    if (chip) morph(chip, turnChip(session));
    const input = composer.querySelector('textarea[name="message"]');
    if (input) {
      const nextDraft = String(session.composerDraft || '');
      const projectedDraft = String(input.dataset.projectedDraft || '');
      const safeToProjectDraft = input.value === projectedDraft || document.activeElement !== input;
      if (safeToProjectDraft && input.value !== nextDraft) { input.value = nextDraft; resizeTextarea(input); }
      if (safeToProjectDraft || input.value === nextDraft) input.dataset.projectedDraft = nextDraft;
      input.placeholder = vm.composerPlaceholder(session);
    }
  }
  // Restore before the browser paints: a deferred restore shows one frame at the wrong offset.
  if (anchor) restoreTimelineAnchor(anchor, session.localSessionId);
  requestAnimationFrame(() => syncTurnNavigator(scroll));
  return !timelineDeferred;
}

// ---------- Root ----------

function applyRoot(view) {
  const look = vm.appearance(view);
  const root = document.documentElement;
  root.dataset.theme = look.resolved;
  root.dataset.tone = look.tone;
  root.dataset.delight = look.delight ? 'on' : 'off';
  root.dataset.disguise = vm.mascotState(view).disguise ? 'on' : 'off';
  root.dataset.route = view.route || 'home';
}

let noticeTimer = 0;
let shownNotice = null;
export function renderNotice() {
  const toast = document.querySelector('#toast-root');
  if (!toast) return;
  const notice = getState().notice;
  if (notice === shownNotice) return;
  shownNotice = notice;
  clearTimeout(noticeTimer);
  if (!notice) { for (const node of [...toast.children]) removeNode(node); return; }
  toast.replaceChildren(h('div', { class: `toast ${notice.level || 'info'}`, role: notice.level === 'error' ? 'alert' : 'status', dataset: { exit: 'toast' }, text: notice.text }));
  noticeTimer = setTimeout(() => { if (getState().notice === notice) setNotice(null); }, notice.level === 'error' ? 6000 : 3200);
}

// Depth decides the slide direction: deeper pages enter from the right, Back returns from the left.
const ROUTE_DEPTH = { home: 0, conversation: 1, providers: 2, stats: 2, settings: 2 };
let shownPage = '';

function pageIdentity(view) {
  if (view.route === 'conversation') return view.activeSession ? `conversation:${view.activeSession.localSessionId}` : 'home';
  return ROUTE_DEPTH[view.route] === undefined ? 'home' : view.route;
}

function pageMotion(previous, next) {
  if (!previous) return 'enter-swap';
  const from = ROUTE_DEPTH[previous.split(':')[0]] ?? 0;
  const to = ROUTE_DEPTH[next.split(':')[0]] ?? 0;
  return to > from ? 'enter-forward' : to < from ? 'enter-back' : 'enter-swap';
}

let retireTimer = 0;
function swapPage(root, current, page, motion, scrollY) {
  for (const old of root.querySelectorAll(':scope > .page-old')) old.remove();
  clearTimeout(retireTimer);
  withFx(page, motion);
  if (!current || current.classList.contains('loading')) { root.replaceChildren(page); return; }
  const top = -scrollY;
  current.style.setProperty('--old-top', `${top}px`);
  current.style.setProperty('--old-h', `${current.offsetHeight}px`);
  current.classList.add('page-old', `fx-leave-${motion.replace('enter-', '')}`);
  current.setAttribute('data-leaving', '1');
  current.setAttribute('aria-hidden', 'true');
  current.inert = true;
  // The incoming page goes first in DOM order so every document query finds it, not the outgoing one.
  root.insertBefore(page, current);
  retireTimer = setTimeout(() => current.remove(), 520);
}

export function render() {
  const state = getState();
  const root = document.querySelector('#app');
  if (!root) return;
  const view = state.view;
  if (!view) { root.replaceChildren(h('main', { class: 'loading' }, fish('idle', 'md'), h('p', { text: '正在加载本地 Chat…' }))); renderNotice(); return; }
  const anchor = captureTimelineAnchor();
  const sessionId = view.activeSession?.localSessionId || '';
  // Read before applyRoot: a route change can switch the document scroller off.
  const pageScroll = window.scrollY;
  applyRoot(view);
  const pages = { home, conversation, providers, settings, stats };
  const page = (pages[view.route] || home)(view);
  const identity = pageIdentity(view);
  const current = [...root.children].find((node) => !node.classList.contains('page-old')) || null;
  const samePage = Boolean(current && identity === shownPage && !current.classList.contains('loading'));
  if (samePage) flip(view.route === 'home' ? current.querySelector('.home-list') : null, () => morph(current, page));
  else {
    swapPage(root, current, page, pageMotion(shownPage, identity), pageScroll);
    // Document-scrolled pages start at the top; the conversation keeps its own scroller.
    if (view.route !== 'conversation') window.scrollTo(0, 0);
  }
  shownPage = identity;
  if (view.route === 'conversation' && view.activeSession) {
    const timeline = root.querySelector('.timeline');
    if (timeline) reconcileTimeline(timeline, view.activeSession.messages, timelineContext(view, view.activeSession), samePage);
    noteMilestones(view, view.activeSession);
    root.querySelectorAll('textarea[name="message"]').forEach(resizeTextarea);
  }
  renderDrawer(view);
  refreshSheet(view);
  if (view.route === 'conversation' && sessionId) {
    // Opening a conversation from a non-conversation route has no prior anchor: default to the
    // newest turn. A same-session anchor still preserves an intentional historical reading position.
    // Restored synchronously so the first painted frame is already at the right offset.
    restoreTimelineAnchor(anchor, sessionId);
    requestAnimationFrame(() => syncTurnNavigator(document.querySelector('.timeline-scroll')));
  }
  renderNotice();
}
