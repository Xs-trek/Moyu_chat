import { captureTimelineAnchor, restoreTimelineAnchor } from './scroll-anchor.js';
import { renderMarkdownInto } from './markdown.js';
import { button, fmtTime, h, icon, iconButton } from './dom.js';
import { fish, flash, greeting, MILESTONES, setPose } from './mascot.js';
import { refreshSheet, renderDrawer, sessionList } from './overlays.js';
import { getState, setNotice } from './state.js';
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
    h('img', { src: item.url, alt: item.fileName || '图片', loading: 'lazy', width: item.width || undefined, height: item.height || undefined }),
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
    h('button', { type: 'button', class: 'fish-poke', 'aria-label': '小鱼', dataset: { action: 'poke-fish' } }, fish(poseOverride || hello.pose, 'lg')),
    title ? h('h2', { class: 'hero-title', text: title }) : null,
    subtitle ? h('p', { class: 'hero-sub', text: subtitle }) : null,
    children);
}

// ---------- Home / providers / settings ----------

function home(view) {
  const configured = (view.providers || []).some((p) => p.hasCredential && p.models?.length);
  let body;
  if (!configured) {
    body = emptyHero({ title: '先接入一个模型', subtitle: '添加 Provider（Base URL 与 API Key），再为它配置 Model Profile。API Key 只保存在 Android 安全存储里。',
      children: [button('添加 Provider', 'configure-provider', {}, 'btn primary')] });
  } else if (!(view.sessions || []).length) {
    body = emptyHero({ title: '还没有会话', subtitle: '会话与分支只保存在本机。', children: [button('新会话', 'new-chat', {}, 'btn primary')] });
  } else {
    body = h('div', { class: 'home-list' }, sessionList(view));
  }
  return h('main', { class: 'page page-home' },
    appHeader('Moyu Chat β', '会话与分支只保存在本机', iconButton('plus', '新会话', 'new-chat')),
    h('div', { class: 'page-body' }, body));
}

function reasoningCapability(model) {
  const levels = String(model.reasoningLevels || '').split(',').map((x) => x.trim()).filter(Boolean);
  return h('span', { class: 'chip quiet', text: levels.length ? `深度支持 · ${levels.join(' / ')}` : '深度支持 · Provider 默认' });
}

function contextModeControl(value = 'standard', dataset = { contextModeSelect: '1' }) {
  const normalized = value === 'auto' ? 'standard' : value;
  const labels = { minimal: '上下文 · 极简', standard: '上下文 · 标准', smart: '上下文 · Moyu 智能' };
  return h('select', { class: 'select context-mode-select', 'aria-label': '上下文模式', dataset }, ['minimal', 'standard', 'smart'].map((mode) => h('option', { value: mode, selected: mode === normalized, text: labels[mode] })));
}

function webModeControl(value = 'auto', dataset = { webModeSelect: '1' }) {
  const labels = { off: '联网 · 关闭', auto: '联网 · 自动', on: '联网 · 开启' };
  return h('select', { class: 'select web-mode-select', 'aria-label': '联网模式', dataset }, ['off', 'auto', 'on'].map((mode) => h('option', { value: mode, selected: mode === value, text: labels[mode] })));
}

function subagentModeControl(value = 'off', dataset = { standardSubagentMode: '1' }) {
  const normalized = value === 'auto' ? 'auto' : 'off';
  const labels = { off: '子 Agent · 关闭', auto: '子 Agent · 自动' };
  return h('select', { class: 'select subagent-mode-select', 'aria-label': '标准模式子 Agent', dataset }, ['off', 'auto'].map((mode) => h('option', { value: mode, selected: mode === normalized, text: labels[mode] })));
}

function modelFacts(model) {
  const facts = [
    ['协议', model.protocol || '未配置'], ['认证', model.authMode || '—'], ['Harness', model.harnessPreset || 'balanced'], ['工具调用', model.clientToolCalling || 'none'],
    ['推理 wire', model.reasoningWire || 'none'], ['上下文', model.contextTokens], ['输入上限', model.inputTokens || 'auto'], ['输出上限', model.maxOutputTokens],
    ['输入模态', model.inputModalities || 'text'], ['输出模态', model.outputModalities || 'text'], ['来源', model.catalogSource || 'manual'],
  ];
  if (model.thinkingMode && model.thinkingMode !== 'provider-default') facts.push(['thinking', `${model.thinkingMode}${model.thinkingBudgetTokens ? `:${model.thinkingBudgetTokens}` : ''}`]);
  if (model.continuationMode === 'native') facts.push(['续接', 'native']);
  if (model.toolCapabilities) facts.push(['原生联网', model.toolCapabilities]);
  return h('dl', { class: 'facts' }, facts.map(([k, v]) => [h('dt', { text: k }), h('dd', { text: String(v ?? '—') })]));
}

function providers(view) {
  return h('main', { class: 'page' },
    appHeader('Provider 与模型', null, button('添加', 'configure-provider', {}, 'btn small')),
    h('div', { class: 'page-body' },
      h('p', { class: 'page-note', text: 'Provider 只保存共享的 Base URL 与 API Key；协议、认证、推理 wire 和模型能力属于具体的 Model Profile。' }),
      view.providers.length ? h('div', { class: 'stack' }, view.providers.map((provider) => h('article', { class: 'card provider-card' },
        h('div', { class: 'row' }, h('strong', { class: 'grow truncate', text: provider.displayName }),
          h('span', { class: `chip ${provider.hasCredential ? 'good' : 'warn'}`, text: provider.hasCredential ? '凭据已配置' : '缺少凭据' })),
        h('code', { class: 'model-id', text: provider.baseUrl || '未配置 Base URL' }),
        h('div', { class: 'row' }, h('span', { class: 'grow' }), button('编辑', 'configure-provider', { providerId: provider.providerId }, 'btn small ghost'), button('添加模型', 'configure-model', { providerId: provider.providerId }, 'btn small')),
        (provider.models || []).map((model) => h('div', { class: 'model-card' },
          h('div', { class: 'row' }, h('strong', { class: 'grow truncate', text: model.displayName || model.modelId }),
            h('span', { class: `chip ${model.configMode === 'auto' ? 'good' : 'quiet'}`, text: model.configMode === 'auto' ? '自动配置' : '高级配置' })),
          h('code', { class: 'model-id', text: model.modelId }),
          h('div', { class: 'row' }, reasoningCapability(model)),
          h('details', { class: 'facts-wrap' }, h('summary', { text: '部署参数' }), modelFacts(model)),
          h('div', { class: 'row model-actions' }, contextModeControl(view.ui?.defaultContextMode || 'standard'), webModeControl(view.ui?.defaultWebMode || 'auto'),
            h('span', { class: 'grow' }), button('编辑', 'configure-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn small ghost'),
            button('新会话', 'new-chat-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn small primary')))),
      ))) : emptyHero({ title: '暂无 Provider', subtitle: '先添加一个 API endpoint。', children: [button('添加 Provider', 'configure-provider', {}, 'btn primary')] })));
}

function segmentedSetting(label, action, key, values, current) {
  return h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': label }, values.map(([value, text]) => h('button', {
    type: 'button', role: 'radio', 'aria-checked': value === current ? 'true' : 'false', class: value === current ? 'on' : '', dataset: { action, [key]: value }, text })));
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
  return h('main', { class: 'page settings-page' }, appHeader('设置', null),
    h('div', { class: 'page-body stack' },
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '外观' }),
        settingRow('主题', '跟随系统时，状态栏和导航栏一起切换。', segmentedSetting('主题', 'theme', 'theme', [['system', '跟随系统'], ['light', '浅色'], ['dark', '深色']], look.theme), true),
        settingRow('背景色调', null, segmentedSetting('背景色调', 'tone', 'tone', [['sage', '灰绿'], ['warm', '暖米'], ['neutral', '中性']], look.tone), true),
        settingRow('动效与彩蛋', '小鱼的动作、问候、校验小印以外的小彩蛋。关闭后界面完全静止。系统开启“减少动画”时动作也会停止。', switchControl('动效与彩蛋', look.delight, 'toggle-delight', { enabled: look.delight ? 'false' : 'true' }))),
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '对话' }),
        settingRow('显示模型推理', '只展示 endpoint 明确返回的 reasoning/thinking 文本。可见推理不会作为普通历史回灌；Provider-private continuation 只有在 Model Profile 明确声明 native 且标准/智能模式允许时才复用。', switchControl('显示模型推理', reasoningVisible, 'toggle-reasoning', { show: reasoningVisible ? 'false' : 'true' })),
        settingRow('跨 Chat 用户记忆', '总开关只决定用户级 Memory/RAG 是否允许使用。关闭时所有模式都不跨 Chat；开启时标准/智能可检索相关本地历史，极简没有 Memory 调用。', switchControl('跨 Chat 用户记忆', memoryEnabled, 'toggle-memory', { enabled: memoryEnabled ? 'false' : 'true' }))),
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '新会话默认' }),
        settingRow('上下文模式', '极简：薄提示词 + 原始完整轮次。标准：原始上下文优先，允许用户记忆，50% 主动压缩到约 30%。智能：标准全部能力 + Task Router、reviewed prompt modules、evidence policy 与同模型二次 verifier。', contextModeControl(view.ui?.defaultContextMode || 'standard', { defaultContextMode: '1' })),
        settingRow('联网', '自动：先由 Web Planner 判断是否需要联网，再按已声明能力选择 Provider hosted Web、Moyu Host 检索或 client Web tools。开启：本轮强制实际联网取证。', webModeControl(view.ui?.defaultWebMode || 'auto', { defaultWebMode: '1' })),
        settingRow('标准模式子 Agent', '自动：仅当 Model Profile 明确支持 client tool calling 时，允许模型委派最多 4 个隔离的同模型子任务。极简永远关闭，智能默认自动。', subagentModeControl(view.ui?.standardSubagentMode || 'off', { standardSubagentMode: '1' }))),
      h('section', { class: 'card' }, h('h2', { class: 'card-title', text: '数据边界' }),
        h('p', { class: 'setting-desc', text: 'Chat 使用自己的本地数据库；API Key 只保存在 Android 安全存储，不进入 WebView 或 SQLite。TaskProfile 与 Checkpoint 只是可重建的上下文控制状态。' }))));
}

// ---------- Conversation ----------

function conversationSubtitle(session) {
  return vm.busyLabel(session) || `${session.modelName || session.model} · ${vm.contextLabel(session.contextMode)}模式`;
}

function turnChip(session) {
  const [model, ...rest] = vm.turnSummary(session);
  return h('button', { type: 'button', class: 'turn-chip', disabled: !session.canConfigureTurn, 'aria-label': '本轮设置', dataset: { action: 'open-turn-settings', sessionId: session.localSessionId } },
    h('span', { class: 'turn-chip-model truncate', text: model }), h('span', { class: 'turn-chip-rest truncate', text: rest.length ? ` · ${rest.join(' · ')}` : '' }), icon('chevronDown', 'ico ico-xs'));
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
      h('div', { class: 'head-title grow conversation-title' }, h('strong', { class: 'truncate', text: session.title }), h('small', { class: 'truncate', text: conversationSubtitle(session) })),
      iconButton('more', '会话操作', 'conversation-menu', { sessionId: session.localSessionId, sessionTitle: session.title || '' })),
    turnNavigator(session),
    h('section', { class: 'timeline-scroll', dataset: { sessionId: session.localSessionId } },
      Number(session.displayOmittedInheritedItems || 0) > 0
        ? h('div', { class: 'history-omitted', text: `更早 ${Number(session.displayOmittedInheritedItems)} 条继承历史仍用于上下文和检索，这里不展开。` }) : null,
      h('div', { class: 'timeline' })),
    h('form', { class: 'composer', dataset: { form: 'composer', sessionId: session.localSessionId } },
      iconButton('down', '回到底部', 'jump-latest', {}, 'icon-btn jump-latest is-hidden'),
      draftMediaStrip(session.draftMedia, session.localSessionId),
      h('textarea', { name: 'message', rows: '1', value: session.composerDraft || '', dataset: { projectedDraft: session.composerDraft || '' }, placeholder: vm.composerPlaceholder(session), 'aria-label': '消息' }),
      h('div', { class: 'composer-actions' },
        h('button', { type: 'button', class: 'composer-icon attachment-btn', disabled: !session.canAttachImage, title: canImage ? '添加图片' : '当前模型未声明图片输入能力', 'aria-label': canImage ? '添加图片' : '当前模型不支持图片输入', dataset: { action: 'pick-attachment', sessionId: session.localSessionId } }, icon('plus')),
        turnChip(session),
        h('span', { class: 'grow' }),
        h('div', { class: 'composer-primary' }, primaryButton(session)))));
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
  return out;
}

function summaryFooter(item, ctx, canAct) {
  const key = vm.timelineKey(item);
  const expanded = expandedTurns.has(key);
  const verification = vm.verificationState(item.verification);
  const signature = JSON.stringify({ usage: item.__usage || null, v: item.verification || null, canAct, expanded, d: ctx.delight, inc: item.incomplete, src: item.sources?.length || 0, wb: item.webBackend || '' });
  return h('footer', { class: 'turn-foot', dataset: { footerSignature: signature } },
    h('div', { class: 'turn-sum' },
      h('button', { type: 'button', class: 'sum-toggle', 'aria-expanded': expanded ? 'true' : 'false', 'aria-label': expanded ? '收起用量详情' : '展开用量详情', dataset: { action: 'toggle-turn-details', itemKey: key } },
        summaryParts(item.__usage, item, ctx.delight), item.incomplete ? h('span', { class: 'chip warn', text: item.terminalCode === 'interrupted' ? '已中断' : '未完成' }) : null,
        icon(expanded ? 'chevronUp' : 'chevronDown', 'ico ico-xs')),
      verification?.seal ? h('span', { class: `seal${verification.seal === '存疑' ? ' doubt' : ''}`, title: verification.label, text: verification.seal }) : null,
      canAct ? iconButton('copy', '复制回答', 'copy-message', { sessionId: ctx.sessionId, localSeq: item.localSeq }, 'icon-btn mini') : null,
      canAct ? iconButton('more', '回答操作', 'message-menu', { sessionId: ctx.sessionId, localSeq: item.localSeq, role: 'assistant' }, 'icon-btn mini') : null),
    expanded ? detailRows(item, ctx.sessionId, canAct) : null);
}

function liveFooter(item) {
  const stage = vm.stageInfo(item.streamStage);
  const chars = String(item.text || '').length;
  return h('footer', { class: 'turn-foot live' },
    h('div', { class: 'turn-live', role: 'status' }, fish(stage.pose, 'sm'), h('span', { class: 'live-label', text: chars ? `${stage.label} · ${chars} 字` : stage.label })));
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
    streaming ? liveFooter(item) : summaryFooter(item, ctx, isFinal(item, ctx)));
}

function userNode(item, ctx) {
  const canAct = isFinal(item, ctx);
  return h('article', { class: `msg user${item.inherited ? ' inherited' : ''}`, dataset: { itemKey: vm.timelineKey(item), turnAnchor: 'user' } },
    item.text ? h('div', { class: 'message user-bubble', dataset: { messageBody: 'plain', selectionScope: 'message' }, text: item.text }) : null,
    mediaGallery(item.attachments),
    h('div', { class: 'msg-meta' }, item.inherited ? h('span', { class: 'chip quiet', text: '历史' }) : null, h('small', { text: fmtTime(item.createdAt) }),
      canAct ? iconButton('more', '消息操作', 'message-menu', { sessionId: ctx.sessionId, localSeq: item.localSeq, role: 'user' }, 'icon-btn mini') : null));
}

function errorNode(item) {
  return h('article', { class: 'msg error', dataset: { itemKey: vm.timelineKey(item) } },
    fish('fail', 'sm'), h('div', { class: 'grow' }, h('strong', { text: item.summary }), h('code', { text: item.code })));
}

function timelineItem(item, ctx) {
  if (item.kind === 'message' && item.role === 'assistant') return assistantNode(item, ctx);
  if (item.kind === 'message' && item.role === 'user') return userNode(item, ctx);
  if (item.kind === 'error') return errorNode(item);
  return null;
}

/** Updates an assistant reply in place: the Markdown body and the live mascot persist across patches. */
function patchAssistant(node, item, ctx) {
  const streaming = Boolean(item.streaming && ctx.running);
  const wasStreaming = node.dataset.streaming === '1';
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
    const stage = vm.stageInfo(item.streamStage);
    const live = footer?.classList.contains('live') ? footer : null;
    if (!live) { const next = liveFooter(item); if (footer) footer.replaceWith(next); else node.append(next); return; }
    setPose(live.querySelector('.fish'), stage.pose);
    const chars = String(item.text || '').length;
    const label = chars ? `${stage.label} · ${chars} 字` : stage.label;
    const labelNode = live.querySelector('.live-label');
    if (labelNode.textContent !== label) labelNode.textContent = label;
    return;
  }
  const desired = summaryFooter(item, ctx, isFinal(item, ctx));
  if (!footer) node.append(desired);
  else if (footer.dataset.footerSignature !== desired.dataset.footerSignature) footer.replaceWith(desired);
  if (wasStreaming && ctx.delight) {
    // A finished reply gets a one-time splash next to its summary.
    const splash = fish('done', 'sm');
    splash.classList.add('splash');
    desired.querySelector('.turn-sum')?.prepend(splash);
    flash(splash, 'done', 1100);
    setTimeout(() => splash.remove(), 1200);
  }
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
    if (node.textContent !== desired.textContent || Boolean(node.querySelector('[data-action="message-menu"]')) !== Boolean(desired.querySelector('[data-action="message-menu"]'))) { node.replaceWith(desired); return desired; }
    replaceBySignature(node, '.media-grid', mediaGallery(item.attachments), 'mediaSignature', node.querySelector('.msg-meta'));
    return node;
  }
  return node;
}

function reconcileTimeline(timeline, items, ctx) {
  const desiredItems = vm.timelineProjection(items);
  const existing = new Map([...timeline.children].map((node) => [node.dataset.itemKey, node]));
  const used = new Set();
  let reference = timeline.firstElementChild;
  const place = (key, build, patch) => {
    let node = existing.get(key);
    const replacingReference = node && node === reference;
    if (node && patch) node = patch(node);
    else if (!node) node = build();
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
  for (const [key, node] of existing) if (!used.has(key)) node.remove();
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
  if (crossed) setNotice({ level: 'info', text: `这条会话已经游过 ${crossed[1]} token。` });
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
  if (timeline && !timelineDeferred) reconcileTimeline(timeline, session.messages, ctx);
  reconcileTurnNavigator(layout, session);
  noteMilestones(view, session);

  const title = document.querySelector('.conversation-title strong');
  if (title) title.textContent = session.title || '';
  const subtitle = document.querySelector('.conversation-title small');
  if (subtitle) subtitle.textContent = conversationSubtitle(session);
  const menu = document.querySelector('.conversation-head [data-action="conversation-menu"]');
  if (menu) menu.dataset.sessionTitle = session.title || '';

  const candidate = document.querySelector('form[data-form="composer"]');
  const composer = candidate?.dataset.sessionId === session.localSessionId ? candidate : null;
  if (composer) {
    const currentDraftMedia = composer.querySelector('[data-draft-media]');
    const desiredDraftMedia = draftMediaStrip(session.draftMedia, session.localSessionId);
    if (currentDraftMedia && currentDraftMedia.dataset.mediaSignature !== desiredDraftMedia.dataset.mediaSignature) currentDraftMedia.replaceWith(desiredDraftMedia);
    else if (!currentDraftMedia) composer.prepend(desiredDraftMedia);
    const primary = composer.querySelector('.composer-primary');
    const isStop = Boolean(primary?.querySelector('[data-action="interrupt"]'));
    if (primary && isStop !== Boolean(session.canInterrupt)) primary.replaceChildren(primaryButton(session));
    else primary?.querySelector('.send-btn')?.toggleAttribute('disabled', !session.canSend);
    const attachment = composer.querySelector('.attachment-btn');
    if (attachment) attachment.disabled = !session.canAttachImage;
    const chip = composer.querySelector('.turn-chip');
    if (chip) chip.replaceWith(turnChip(session));
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
  requestAnimationFrame(() => {
    if (anchor) restoreTimelineAnchor(anchor, session.localSessionId);
    syncTurnNavigator(scroll);
  });
  return !timelineDeferred;
}

// ---------- Root ----------

function applyRoot(view) {
  const look = vm.appearance(view);
  const root = document.documentElement;
  root.dataset.theme = look.resolved;
  root.dataset.tone = look.tone;
  root.dataset.delight = look.delight ? 'on' : 'off';
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
  if (!notice) { toast.replaceChildren(); return; }
  toast.replaceChildren(h('div', { class: `toast ${notice.level || 'info'}`, role: notice.level === 'error' ? 'alert' : 'status', text: notice.text }));
  noticeTimer = setTimeout(() => { if (getState().notice === notice) setNotice(null); }, notice.level === 'error' ? 6000 : 3200);
}

export function render() {
  const state = getState();
  const root = document.querySelector('#app');
  if (!root) return;
  const view = state.view;
  if (!view) { root.replaceChildren(h('main', { class: 'loading' }, fish('idle', 'md'), h('p', { text: '正在加载本地 Chat…' }))); renderNotice(); return; }
  const anchor = captureTimelineAnchor();
  const sessionId = view.activeSession?.localSessionId || '';
  applyRoot(view);
  const pages = { home, conversation, providers, settings };
  root.replaceChildren((pages[view.route] || home)(view));
  if (view.route === 'conversation' && view.activeSession) {
    const timeline = root.querySelector('.timeline');
    if (timeline) reconcileTimeline(timeline, view.activeSession.messages, timelineContext(view, view.activeSession));
    noteMilestones(view, view.activeSession);
    root.querySelectorAll('textarea[name="message"]').forEach(resizeTextarea);
  }
  renderDrawer(view);
  refreshSheet(view);
  if (view.route === 'conversation' && sessionId) requestAnimationFrame(() => {
    // Opening a conversation from a non-conversation route has no prior anchor: default to the
    // newest turn. A same-session anchor still preserves an intentional historical reading position.
    restoreTimelineAnchor(anchor, sessionId);
    syncTurnNavigator(document.querySelector('.timeline-scroll'));
  });
  renderNotice();
}
