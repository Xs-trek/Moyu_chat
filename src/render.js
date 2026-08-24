import { captureTimelineAnchor, restoreTimelineAnchor } from './scroll-anchor.js';
import { renderSafeMarkdown } from './markdown.js';
import { getState } from './state.js';

function h(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = String(value);
    else if (key === 'disabled') node.disabled = Boolean(value);
    else if (key === 'value') node.value = value;
    else if (key === 'dataset') for (const [name, item] of Object.entries(value)) node.dataset[name] = String(item);
    else node.setAttribute(key, String(value));
  }
  for (const child of children.flat(Infinity)) if (child !== null && child !== undefined && child !== false) node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  return node;
}

function button(text, action, data = {}, cls = 'btn') { return h('button', { type: 'button', class: cls, dataset: { action, ...data } }, text); }
function badge(text, cls = '') { return h('span', { class: `badge ${cls}`, text }); }
function fmt(value) { try { return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value)); } catch { return value || ''; } }

function mediaGallery(items, removable = false, sessionId = '') {
  const images = (items || []).filter((item) => item?.kind === 'image' && item.url);
  if (!images.length) return null;
  return h('div', { class: 'media-grid' }, images.map((item) => h('figure', { class: 'media-item' },
    h('img', { src: item.url, alt: item.fileName || '图片', loading: 'lazy' }),
    h('figcaption', {}, h('span', { text: item.fileName || `${item.width || '?'}×${item.height || '?'}` }), removable ? button('移除', 'remove-attachment', { sessionId, mediaId: item.mediaId }, 'btn small') : null)
  )));
}

/** App-owned confirmation UI. Do not depend on window.confirm/WebChromeClient behavior. */
export function openConfirmDialog({ title, message, confirmText = '确认', danger = false, onConfirm }) {
  document.querySelector('.modal-backdrop[data-moyu-modal]')?.remove();
  const cancel = button('取消', 'modal-cancel', {}, 'btn');
  const confirm = button(confirmText, 'modal-confirm', {}, `btn ${danger ? 'danger' : 'primary'}`);
  const overlay = h('div', { class: 'modal-backdrop', dataset: { moyuModal: '1' } },
    h('section', { class: 'modal-card', role: 'dialog', 'aria-modal': 'true', 'aria-label': title || '确认操作' },
      h('h2', { text: title || '确认操作' }), h('p', { text: message || '' }), h('div', { class: 'modal-actions' }, cancel, confirm)));
  const close = () => overlay.remove();
  cancel.addEventListener('click', close);
  confirm.addEventListener('click', () => { close(); onConfirm?.(); });
  overlay.addEventListener('click', (event) => { if (event.target === overlay) close(); });
  document.body.append(overlay);
  confirm.focus();
}

function shell(view, body) {
  const routes = [['home','Chat'],['imports','分支'],['providers','Provider'],['handoffs','回传'],['settings','设置']];
  const conversationActive = view.route === 'conversation' && view.activeSession;
  return h('div', { class: `app-shell${conversationActive ? ' conversation-active' : ''}` },
    h('header', { class: 'topbar' }, h('strong', { class: 'brand', text: '摸鱼 · Chat' }), h('span', { class: 'grow' }), badge(view.remoteBridge.available ? 'Remote 可导入' : '独立模式', view.remoteBridge.available ? 'good' : 'quiet')),
    body,
    h('nav', { class: 'nav' }, routes.map(([route, label]) => button(label, 'nav', { route }, `nav-item ${view.route === route ? 'active' : ''}`)))
  );
}

function home(view) {
  return h('main', { class: 'page' },
    h('section', { class: 'hero card' }, h('div', { class: 'grow' }, h('small', { text: 'LOCAL CHAT' }), h('h1', { text: '不依赖 PC 的 Moyu 补充会话' }), h('p', { text: '会话、分支与 Provider 均由本机维护。Remote 只作为可选的只读来源。' })), button('＋ 新建 Chat', 'new-chat', {}, 'btn primary')),
    h('section', { class: 'section-head' }, h('h2', { text: '最近会话' })),
    view.sessions.length ? h('section', { class: 'stack' }, view.sessions.map((session) => h('article', { class: 'card session-card' },
      h('div', { class: 'row' }, h('strong', { class: 'truncate-safe', text: session.title }), badge(session.state)),
      h('p', { class: 'muted truncate-safe', text: session.preview || '暂无内容' }),
      h('div', { class: 'row session-meta' }, h('small', { class: 'grow truncate-safe', text: `${session.providerName} · ${session.model} · ${fmt(session.updatedAt)}` }), session.origin ? badge(`分支 · ${session.origin.sourceTitle}`, 'branch') : null, session.state !== 'running' ? button('删除', 'delete-session', { sessionId: session.localSessionId, sessionTitle: session.title }, 'btn small danger') : null, button('打开', 'open-session', { sessionId: session.localSessionId }, 'btn small'))))) : h('section', { class: 'empty', text: '还没有本地 Chat，会话可在没有 Remote 的情况下创建。' })
  );
}

function conversation(view) {
  const session = view.activeSession;
  if (!session) return home(view);
  const imageInput = String(session.inputModalities || '').split(',').includes('image');
  return h('main', { class: 'conversation-layout' },
    h('header', { class: 'conversation-head' },
      button('←', 'nav', { route: 'home' }, 'btn small icon-btn'),
      h('div', { class: 'conversation-title grow' }, h('strong', { class: 'truncate-safe', text: session.title }), h('small', { class: 'muted truncate-safe', text: `${session.providerName} · ${session.model} · reasoning ${session.reasoningEffort || 'default'}` })),
      imageInput ? badge('图片输入', 'good') : null,
      session.origin ? badge(`来自 ${session.origin.sourceTitle}`, 'branch') : null,
      badge(`上下文 · ${session.contextMode || 'auto'}${session.contextMode === 'auto' && session.effectiveContextMode ? ` → ${session.effectiveContextMode}` : ''}`, 'quiet'),
      session.taskProfile ? badge(`${session.taskProfile.domain} · ${session.taskProfile.taskType}`, 'quiet') : null,
      session.hasContextCheckpoint ? badge('Checkpoint', 'branch') : null,
      button(view.ui?.showReasoning ? '隐藏推理' : '显示推理', 'toggle-reasoning', { show: view.ui?.showReasoning ? 'false' : 'true' }, 'btn small'),
      button('分支', 'branch-local', { sessionId: session.localSessionId }, 'btn small'),
      button('回传', 'handoff', { sessionId: session.localSessionId }, 'btn small'),
      !session.canInterrupt && !session.contextPreparing ? button('删除', 'delete-session', { sessionId: session.localSessionId, sessionTitle: session.title }, 'btn small danger') : null),
    h('section', { class: 'timeline-scroll', dataset: { sessionId: session.localSessionId } },
      h('div', { class: 'timeline' }, session.messages.map((item) => timelineItem(item, session.state === 'running', session.localSessionId, Boolean(view.ui?.showReasoning))))),
    h('form', { class: 'composer', dataset: { form: 'composer', sessionId: session.localSessionId } },
      mediaGallery(session.draftMedia, true, session.localSessionId),
      h('div', { class: 'composer-row' },
        session.canAttachImage ? button('＋ 图片', 'pick-attachment', { sessionId: session.localSessionId }, 'btn attachment-btn') : null,
        h('textarea', { name: 'message', rows: '1', value: session.composerDraft || '', placeholder: session.canSend ? (imageInput ? '输入消息，或仅发送图片' : '输入消息') : session.contextPreparing ? '正在识别任务 / 整理上下文' : '等待当前回合结束', 'aria-label': '消息' }),
        session.canInterrupt ? button('中断', 'interrupt', { sessionId: session.localSessionId }, 'btn danger') : null,
        h('button', { type: 'submit', class: 'btn primary send-btn', disabled: !session.canSend, text: session.contextPreparing ? '整理上下文' : session.state === 'running' ? '发送中' : '发送' })), 
      h('small', { class: 'composer-hint muted', text: 'Enter 发送 · Shift+Enter 换行' }))
  );
}

function streamStageLabel(stage) {
  if (stage === 'connected') return '已连接 · 等待模型';
  if (stage === 'reasoning') return '推理中';
  if (stage === 'output') return '输出中';
  return '等待响应';
}

function tokenCount(value) { return Number.isFinite(Number(value)) ? Math.max(0, Number(value)) : null; }
function seconds(ms) { return `${(Math.max(0, Number(ms) || 0) / 1000).toFixed(2)}s`; }

function usageSummary(item) {
  const parts = [];
  const input = tokenCount(item.inputTokens);
  const output = tokenCount(item.outputTokens);
  const estimated = tokenCount(item.context?.estimatedInputTokens);
  if (input !== null) parts.push(`输入 ${input.toLocaleString()} token`);
  else if (estimated !== null) parts.push(`输入 ≈${estimated.toLocaleString()} token`);
  if (output !== null) parts.push(`输出 ${output.toLocaleString()} token`);
  const cached = tokenCount(item.cachedInputTokens);
  const cacheBase = tokenCount(item.cacheHitBaseTokens);
  if (cached !== null) {
    const rate = cacheBase && cacheBase > 0 ? ` (${Math.min(100, cached * 100 / cacheBase).toFixed(1)}%)` : '';
    parts.push(`缓存命中 ${cached.toLocaleString()} token${rate}`);
  }
  const cacheWrite = tokenCount(item.cacheWriteInputTokens);
  if (cacheWrite !== null && cacheWrite > 0) parts.push(`缓存写入 ${cacheWrite.toLocaleString()} token`);
  const reasoningTokens = tokenCount(item.reasoningTokens);
  if (reasoningTokens !== null && reasoningTokens > 0) parts.push(`推理 ${reasoningTokens.toLocaleString()} token`);
  const speed = Number(item.outputTokensPerSecond);
  if (Number.isFinite(speed) && speed > 0) parts.push(`输出 ${speed.toFixed(1)} token/s`);
  if (item.firstOutputLatencyMs !== undefined) parts.push(`首输出 ${seconds(item.firstOutputLatencyMs)}`);
  if (item.requestDurationMs !== undefined) parts.push(`总耗时 ${seconds(item.requestDurationMs)}`);
  return parts.length ? parts.join(' · ') : '暂无 token usage';
}

function timelineItem(item, runtimeRunning = false, sessionId = '', showReasoning = false) {
  if (item.kind === 'message') {
    const finalized = !item.streaming && !runtimeRunning && Number(item.localSeq) > 0 && (item.role === 'user' || item.role === 'assistant');
    const reasoning = item.role === 'assistant' ? String(item.reasoning || '') : '';
    return h('article', { class: `bubble ${item.role}` },
      h('div', { class: 'bubble-head' }, h('strong', { text: item.role === 'user' ? '你' : item.role === 'assistant' ? 'Moyu Chat' : '系统' }), item.inherited ? badge('历史', 'quiet') : null, h('small', { text: fmt(item.createdAt) })),
      reasoning && showReasoning ? h('details', { class: 'reasoning-block', open: Boolean(item.streaming && runtimeRunning) }, h('summary', { text: '模型推理' }), h('pre', { class: 'reasoning-text', text: reasoning })) : null,
      item.text ? h('pre', { class: 'message', text: item.text }) : null,
      mediaGallery(item.attachments),
      item.streaming && runtimeRunning ? h('span', { class: 'badge good streaming-state', text: streamStageLabel(item.streamStage) }) : null,
      finalized ? h('div', { class: 'message-actions' },
        item.role === 'user' ? button('编辑', 'edit-message', { sessionId, localSeq: item.localSeq }, 'btn small') : null,
        button('回滚到此处', 'rollback-message', { sessionId, localSeq: item.localSeq }, 'btn small')) : null);
  }
  if (item.kind === 'usage') return h('article', { class: 'usage', text: usageSummary(item) });
  if (item.kind === 'error') return h('article', { class: 'error' }, h('strong', { text: item.summary }), h('code', { text: item.code }));
  return null;
}

function reasoningControl(model) {
  const levels = String(model.reasoningLevels || '').split(',').map((x) => x.trim()).filter(Boolean);
  if (!levels.length) return badge('reasoning · provider default', 'quiet');
  const values = ['default', ...levels.filter((x) => x !== 'default')];
  return h('select', { class: 'reasoning-select', dataset: { reasoningSelect: '1' } }, values.map((level) => h('option', { value: level, selected: level === (model.defaultReasoning || 'default'), text: level })));
}

function contextModeControl(value = 'auto', dataset = { contextModeSelect: '1' }) {
  const labels = { auto: '上下文 · 自动', minimal: '上下文 · 极简', smart: '上下文 · Moyu 智能' };
  return h('select', { class: 'reasoning-select context-mode-select', dataset }, ['auto','minimal','smart'].map((mode) => h('option', { value: mode, selected: mode === value, text: labels[mode] })));
}

function providers(view) {
  return h('main', { class: 'page' },
    h('div', { class: 'section-head' }, h('div', { class: 'grow' }, h('h1', { text: 'Provider / Models' }), h('p', { class: 'muted', text: 'Endpoint/Auth 属于 Provider；上下文、输入输出模态与推理能力属于该 endpoint 下的 Model Profile。' })), button('＋ Provider', 'configure-provider', {}, 'btn primary')),
    view.providers.length ? h('section', { class: 'stack' }, view.providers.map((provider) => h('article', { class: 'card provider-card' },
      h('div', { class: 'row' }, h('strong', { class: 'grow truncate-safe', text: provider.displayName }), badge(provider.protocol), badge(provider.hasCredential ? '凭据已配置' : '缺少凭据', provider.hasCredential ? 'good' : 'warn')),
      h('div', { class: 'provider-actions row' }, badge(`reasoning · ${provider.reasoningWire || 'none'}`, 'quiet'), h('span', { class: 'grow' }), button('编辑 Provider', 'configure-provider', { providerId: provider.providerId }, 'btn small'), button('＋ 模型', 'configure-model', { providerId: provider.providerId }, 'btn small')),
      h('section', { class: 'stack model-stack' }, (provider.models || []).map((model) => h('div', { class: 'model-card' },
        h('div', { class: 'row' }, h('strong', { class: 'grow truncate-safe', text: model.displayName || model.modelId }), badge(model.defaultReasoning || 'default')),
        h('code', { class: 'model-id', text: model.modelId }),
        h('small', { class: 'model-meta muted', text: `context ${model.contextTokens} · input ${model.inputTokens || 'auto'} · output ${model.maxOutputTokens} · in[${model.inputModalities || 'text'}] · out[${model.outputModalities || 'text'}]${model.reasoningLevels ? ` · reasoning ${model.reasoningLevels}` : ''}` }),
        h('div', { class: 'row' }, reasoningControl(model), contextModeControl(view.ui?.defaultContextMode || 'auto'), h('span', { class: 'grow' }), button('Chat', 'new-chat-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn primary small'), button('编辑', 'configure-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn small'))
      )))
    ))) : h('section', { class: 'empty', text: '暂无 Provider。先添加一个 API endpoint。' }));
}

function imports(view) {
  return h('main', { class: 'page' }, h('div', { class: 'section-head' }, h('div', { class: 'grow' }, h('h1', { text: '从 Remote 创建分支' }), h('p', { class: 'muted', text: '导入后生成不可变 Snapshot，之后不再依赖 Remote 数据。' })), button('刷新来源', 'refresh-sources', {}, 'btn')),
    !view.remoteBridge.available ? h('section', { class: 'empty', text: 'RemoteDataBridge 当前不可用。Local Chat 不受影响。' }) :
    h('section', { class: 'stack' }, view.remoteSources.map((source) => h('article', { class: 'card remote-source-card' }, h('div', { class: 'row' }, h('strong', { class: 'grow truncate-safe', text: source.title }), source.runtime ? badge(source.runtime) : null, badge(source.complete ? '本地缓存完整' : `缓存至 ${source.cachedThroughSeq}`, source.complete ? 'good' : 'warn')), h('small', { text: fmt(source.updatedAt) }), h('div', { class: 'row' }, contextModeControl(view.ui?.defaultContextMode || 'auto'), h('span', { class: 'grow' }), button('创建本地分支', 'branch-remote', { sourceId: source.sourceId }, 'btn primary small'))))));
}

function handoffs(view) {
  return h('main', { class: 'page' }, h('h1', { text: '回传草稿' }), h('p', { class: 'muted', text: '回传不是同步。只有用户显式提交后，Remote 才能收到 handoff package。' }),
    view.handoffs.length ? h('section', { class: 'stack' }, view.handoffs.map((item) => h('article', { class: 'card' }, h('div', { class: 'row' }, h('strong', { class: 'grow truncate-safe', text: item.summary }), badge(item.state)), h('small', { text: fmt(item.createdAt) }), item.state === 'draft' ? button('提交给 Remote', 'submit-handoff', { handoffId: item.handoffId }, 'btn primary small') : null))) : h('section', { class: 'empty', text: '暂无回传草稿。' }));
}

function settings(view) {
  const nextTheme = view.appearance.theme === 'dark' ? 'system' : view.appearance.theme === 'system' ? 'light' : 'dark';
  const reasoningVisible = Boolean(view.ui?.showReasoning);
  const defaultContextMode = view.ui?.defaultContextMode || 'auto';
  return h('main', { class: 'page' }, h('h1', { text: '设置' }),
    h('section', { class: 'card' }, h('div', { class: 'row' }, h('strong', { text: '主题' }), h('span', { class: 'grow' }), button(`${view.appearance.theme} → ${nextTheme}`, 'theme', { theme: nextTheme }, 'btn small'))),
    h('section', { class: 'card' }, h('div', { class: 'row' }, h('div', { class: 'grow' }, h('strong', { text: '新会话默认上下文模式' }), h('p', { class: 'muted', text: '自动：普通 Chat 不做首轮 Router，仅超窗时按需 Checkpoint；Remote 历史自动启用完整智能层。极简：零额外模型预处理。Moyu 智能：Task Router + Checkpoint。模式在创建后冻结并由本地规则解析。' })), contextModeControl(defaultContextMode, { defaultContextMode: '1' }))),
    h('section', { class: 'card' }, h('div', { class: 'row' }, h('div', { class: 'grow' }, h('strong', { text: '显示模型推理' }), h('p', { class: 'muted', text: '仅展示 provider 明确返回的 reasoning/thinking 内容；不会进入下一轮 provider-neutral 上下文。' })), button(reasoningVisible ? '已显示' : '已隐藏', 'toggle-reasoning', { show: reasoningVisible ? 'false' : 'true' }, 'btn small'))),
    h('section', { class: 'card' }, h('strong', { text: '数据边界' }), h('p', { class: 'muted', text: 'Chat 使用自己的数据库。RemoteDataBridge 只提供 Snapshot/Handoff，不共享 SQLite 文件。TaskProfile 与 Checkpoint 只是可重建的上下文控制状态。' })));
}

function selectionInside(node) {
  const selection = window.getSelection?.();
  if (!node || !selection || selection.isCollapsed || !selection.anchorNode || !selection.focusNode) return false;
  return node.contains(selection.anchorNode) || node.contains(selection.focusNode);
}

/** Patch the volatile conversation projection without replacing the focused composer/IME subtree. */
export function patchConversation() {
  const view = getState().view;
  const session = view?.activeSession;
  const scroll = document.querySelector('.timeline-scroll');
  if (!session || view.route !== 'conversation' || !scroll || scroll.dataset.sessionId !== session.localSessionId) return false;
  window.__moyuChatView = view;
  const running = session.state === 'running';
  const contextPreparing = Boolean(session.contextPreparing);
  const timeline = scroll.querySelector('.timeline');
  const anchor = captureTimelineAnchor();
  if (timeline && !selectionInside(timeline)) {
    timeline.replaceChildren(...session.messages.map((item) => timelineItem(item, running, session.localSessionId, Boolean(view.ui?.showReasoning))).filter(Boolean));
  } else if (!running && timeline) {
    timeline.querySelectorAll('.streaming-state').forEach((node) => node.remove());
  }

  const candidateComposer = document.querySelector('form[data-form="composer"]');
  const composer = candidateComposer?.dataset.sessionId === session.localSessionId ? candidateComposer : null;
  const send = composer?.querySelector('.send-btn');
  if (send) { send.disabled = !session.canSend; send.textContent = contextPreparing ? '整理上下文' : running ? '发送中' : '发送'; }
  const input = composer?.querySelector('textarea[name="message"]');
  if (input) input.placeholder = session.canSend ? (String(session.inputModalities || '').split(',').includes('image') ? '输入消息，或仅发送图片' : '输入消息') : contextPreparing ? '正在识别任务 / 整理上下文' : '等待当前回合结束';
  const existingInterrupt = composer?.querySelector('[data-action="interrupt"]');
  if (!session.canInterrupt && existingInterrupt) existingInterrupt.remove();
  else if (session.canInterrupt && !existingInterrupt && send) send.before(button('中断', 'interrupt', { sessionId: session.localSessionId }, 'btn danger'));
  if (anchor) requestAnimationFrame(() => restoreTimelineAnchor(anchor, session.localSessionId));
  return true;
}

function resolvedTheme(view) {
  const configured = view?.appearance?.theme || 'system';
  if (configured === 'dark' || configured === 'light') return configured;
  return window.matchMedia?.('(prefers-color-scheme: dark)')?.matches ? 'dark' : 'light';
}

export function renderNotice() {
  const toast = document.querySelector('#toast-root');
  if (!toast) return;
  const notice = getState().notice;
  if (notice) toast.replaceChildren(h('div', { class: `toast ${notice.level || 'info'}`, text: notice.text }));
  else toast.replaceChildren();
}

export function render() {
  const state = getState();
  const root = document.querySelector('#app');
  if (!root) return;
  const view = state.view;
  if (!view) { root.replaceChildren(h('main', { class: 'loading', text: '正在加载本地 Chat…' })); renderNotice(); return; }
  const anchor = captureTimelineAnchor();
  const sessionId = view.activeSession?.localSessionId || '';
  window.__moyuChatView = view;
  const pages = { home, conversation, providers, imports, handoffs, settings };
  const body = (pages[view.route] || home)(view);
  document.documentElement.dataset.theme = resolvedTheme(view);
  document.documentElement.dataset.route = view.route || 'home';
  root.replaceChildren(shell(view, body));
  if (view.route === 'conversation' && sessionId) requestAnimationFrame(() => restoreTimelineAnchor(anchor, sessionId));
  renderNotice();
}
