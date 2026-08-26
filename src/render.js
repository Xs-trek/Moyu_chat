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
  return h('div', { class: 'media-grid', dataset: { mediaSignature: JSON.stringify(images) } }, images.map((item) => h('figure', { class: 'media-item' },
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

function configuredModelChoices(view) {
  const choices = [];
  for (const provider of view.providers || []) {
    if (!provider?.hasCredential) continue;
    for (const model of provider.models || []) choices.push({ provider, model });
  }
  return choices;
}

function modelChoiceField(view, session) {
  const options = configuredModelChoices(view);
  const selected = options.find(({ model }) => model.modelProfileId === session.modelProfileId);
  const label = selected?.model?.displayName || session.modelName || session.model;
  return h('div', { class: 'choice-field', dataset: { choiceField: 'model' } },
    h('button', { type: 'button', class: 'choice-trigger', disabled: !session.canConfigureTurn, dataset: { action: 'toggle-choice', choice: 'model' }, 'aria-expanded': 'false', text: `模型 · ${label}` }),
    h('div', { class: 'choice-menu', dataset: { choiceMenu: 'model' } }, options.map(({ provider, model }) =>
      h('button', { type: 'button', class: `choice-option${model.modelProfileId === session.modelProfileId ? ' selected' : ''}`, dataset: { action: 'session-model-set', sessionId: session.localSessionId, modelProfileId: model.modelProfileId },
        text: `${model.displayName || model.modelId} · ${provider.displayName}` }))));
}

function reasoningChoiceField(session) {
  const levels = String(session.reasoningLevels || '').split(',').map((x) => x.trim()).filter(Boolean);
  if (!levels.length) return h('div', { class: 'choice-field disabled-choice' }, h('button', { type: 'button', class: 'choice-trigger', disabled: true, text: '推理 · 关闭' }));
  const values = ['default', ...levels.filter((x) => x !== 'default')];
  const display = (value) => value === 'none' ? '关闭' : value === 'default' ? '默认' : value;
  return h('div', { class: 'choice-field', dataset: { choiceField: 'reasoning' } },
    h('button', { type: 'button', class: 'choice-trigger', disabled: !session.canConfigureTurn, dataset: { action: 'toggle-choice', choice: 'reasoning' }, 'aria-expanded': 'false', text: `推理 · ${display(session.reasoningEffort || 'default')}` }),
    h('div', { class: 'choice-menu', dataset: { choiceMenu: 'reasoning' } }, values.map((value) =>
      h('button', { type: 'button', class: `choice-option${value === (session.reasoningEffort || 'default') ? ' selected' : ''}`, dataset: { action: 'session-reasoning-set', sessionId: session.localSessionId, reasoningEffort: value }, text: display(value) }))));
}

function conversation(view) {
  const session = view.activeSession;
  if (!session) return home(view);
  const imageInput = String(session.inputModalities || '').split(',').includes('image');
  const imageOutput = String(session.outputModalities || '').split(',').includes('image');
  const busyLabel = session.webPreparingStage === 'web_fetch' ? '读取网页' : session.webPreparingStage === 'web_search' ? '搜索网页' : session.contextPreparing ? '整理上下文' : session.state === 'running' ? '生成中' : '';
  return h('main', { class: 'conversation-layout' },
    h('header', { class: 'conversation-head' },
      button('←', 'nav', { route: 'home' }, 'btn small icon-btn'),
      h('div', { class: 'conversation-title grow' }, h('strong', { class: 'truncate-safe', text: session.title }), h('small', { class: 'muted truncate-safe', text: busyLabel || `${session.providerName} · ${session.modelName || session.model}` })),
      busyLabel ? badge(busyLabel, 'good') : null,
      button(view.ui?.showReasoning ? '隐藏推理' : '显示推理', 'toggle-reasoning', { show: view.ui?.showReasoning ? 'false' : 'true' }, 'btn small icon-action'),
      button('分支', 'branch-local', { sessionId: session.localSessionId }, 'btn small icon-action')),
    h('section', { class: 'timeline-scroll', dataset: { sessionId: session.localSessionId } },
      h('div', { class: 'timeline' }, timelineProjection(session.messages).map((item) => timelineItem(item, session.state === 'running', session.localSessionId, Boolean(view.ui?.showReasoning))).filter(Boolean)),
      button('↓ 回到底部', 'jump-latest', {}, 'btn small jump-latest is-hidden')),
    h('form', { class: 'composer', dataset: { form: 'composer', sessionId: session.localSessionId } },
      mediaGallery(session.draftMedia, true, session.localSessionId),
      h('div', { class: 'composer-tools' },
        modelChoiceField(view, session), reasoningChoiceField(session),
        h('span', { class: 'grow' }),
        imageInput ? badge('可读图', 'quiet') : null,
        imageOutput ? badge('可出图', 'quiet') : null,
        badge(`联网 ${session.webMode || 'auto'}`, 'quiet')),
      h('div', { class: 'composer-row' },
        h('button', { type: 'button', class: 'btn attachment-btn', disabled: !session.canAttachImage, title: imageInput ? '添加图片' : '当前模型未声明图片输入能力', dataset: { action: 'pick-attachment', sessionId: session.localSessionId }, text: '＋' }),
        h('textarea', { name: 'message', rows: '1', value: session.composerDraft || '', placeholder: session.canSend ? (imageInput ? '输入消息，或添加图片' : '输入消息') : session.contextPreparing ? '正在整理上下文' : session.webPreparingStage ? '正在联网检索' : '等待当前回合结束', 'aria-label': '消息' }),
        session.canInterrupt ? button('停止', 'interrupt', { sessionId: session.localSessionId }, 'btn danger') : null,
        h('button', { type: 'submit', class: 'btn primary send-btn', disabled: !session.canSend, text: session.contextPreparing ? '整理中' : session.webPreparingStage ? '搜索中' : session.state === 'running' ? '生成中' : '发送' })),
      h('small', { class: 'composer-hint muted', text: 'Enter 发送 · Shift+Enter 换行 · 模型与推理设置只影响后续回复' }))
  );
}

function streamStageLabel(stage) {
  if (stage === 'preparing') return '整理上下文';
  if (stage === 'web_search') return '搜索网页';
  if (stage === 'web_fetch') return '读取网页';
  if (stage === 'connected') return '已连接 · 等待模型';
  if (stage === 'reasoning') return '推理中';
  if (stage === 'output') return '输出中';
  return '连接模型';
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

function timelineKey(item) {
  const seq = Number(item?.localSeq);
  if (Number.isInteger(seq) && seq > 0) return `${item.kind}:${item.inherited ? 'inherited' : 'local'}:${seq}`;
  return `${item?.kind || 'item'}:${item?.role || ''}:${item?.createdAt || ''}`;
}

/** Usage is durable as its own row, but projected into the preceding assistant footer. */
function timelineProjection(items = []) {
  const out = [];
  for (const raw of items || []) {
    if (!raw) continue;
    if (raw.kind === 'usage') {
      for (let i = out.length - 1; i >= 0; i -= 1) {
        if (out[i]?.kind === 'message' && out[i]?.role === 'assistant') { out[i] = { ...out[i], __usage: raw }; break; }
      }
      continue;
    }
    out.push({ ...raw });
  }
  return out;
}

function messageBody(item) {
  if (!item.text) return null;
  if (item.role === 'assistant' && !item.streaming) {
    const body = h('div', { class: 'message markdown-body', dataset: { messageBody: 'markdown' } });
    body.append(renderSafeMarkdown(item.text));
    return body;
  }
  return h('pre', { class: 'message', dataset: { messageBody: 'plain' }, text: item.text });
}

function sourceList(sources = []) {
  const safeSources = (sources || []).filter((source) => source?.url?.startsWith('https://')).slice(0, 12);
  if (!safeSources.length) return null;
  return h('div', { class: 'message-sources' }, h('strong', { text: '来源' }), safeSources.map((source, index) =>
    button(`${index + 1}. ${source.title || source.url}`, 'open-source', { url: source.url }, 'source-link')));
}

function factValue(value) { return value === undefined || value === null || value === '' ? '—' : String(value); }
function tokenPair(usage) {
  const input = tokenCount(usage?.inputTokens ?? usage?.context?.estimatedInputTokens);
  const output = tokenCount(usage?.outputTokens);
  if (input === null && output === null) return '—';
  return `${input === null ? '—' : input.toLocaleString()} / ${output === null ? '—' : output.toLocaleString()}`;
}
function cacheFact(usage) {
  const cached = tokenCount(usage?.cachedInputTokens);
  if (cached === null) return '—';
  const base = tokenCount(usage?.cacheHitBaseTokens);
  return base && base > 0 ? `${cached.toLocaleString()} · ${Math.min(100, cached * 100 / base).toFixed(1)}%` : `${cached.toLocaleString()} token`;
}
function effortFact(item) {
  if (item.reasoningAvailable === false) return '关闭';
  const value = String(item.reasoningEffort || '').trim();
  if (!value) return '—';
  if (value === 'none') return '关闭';
  if (value === 'default') return '默认';
  return value;
}
function metric(label, value) {
  return h('div', { class: 'reply-metric' }, h('span', { class: 'reply-metric-label', text: label }), h('strong', { class: 'reply-metric-value truncate-safe', text: factValue(value) }));
}

function assistantFooter(item, finalized, sessionId) {
  if (item.role !== 'assistant') return null;
  const usage = item.__usage;
  const sourceNode = sourceList(item.sources);
  const backend = String(item.webBackend || '').trim();
  const canAct = finalized && Number(item.localSeq) > 0;
  const reasoningTokens = tokenCount(usage?.reasoningTokens);
  const speed = Number(usage?.outputTokensPerSecond);
  const facts = [
    ['模型', item.modelName || item.model || '—'],
    ['深度', effortFact(item)],
    ['入/出', tokenPair(usage)],
    ['缓存', cacheFact(usage)],
    ['推理', reasoningTokens === null ? '—' : `${reasoningTokens.toLocaleString()} token`],
    ['速度', Number.isFinite(speed) && speed > 0 ? `${speed.toFixed(1)} token/s` : '—'],
    ['首字', usage?.firstOutputLatencyMs === undefined ? '—' : seconds(usage.firstOutputLatencyMs)],
    ['总耗', usage?.requestDurationMs === undefined ? '—' : seconds(usage.requestDurationMs)],
  ];
  const signature = JSON.stringify({ sources: item.sources || [], backend, usage: usage || null, facts, canAct, localSeq: item.localSeq });
  return h('footer', { class: 'assistant-footer', dataset: { footerSignature: signature } },
    sourceNode,
    backend ? h('div', { class: 'assistant-web muted', text: `联网 · ${backend}` }) : null,
    h('div', { class: 'reply-meta' }, facts.map(([label, value]) => metric(label, value))),
    canAct ? h('div', { class: 'message-actions' },
      button('复制', 'copy-message', { sessionId, localSeq: item.localSeq }, 'btn small'),
      button('回滚到此处', 'rollback-message', { sessionId, localSeq: item.localSeq }, 'btn small')) : null);
}

function timelineItem(item, runtimeRunning = false, sessionId = '', showReasoning = false) {
  if (item.kind === 'message') {
    const finalized = !item.streaming && !runtimeRunning && Number(item.localSeq) > 0 && (item.role === 'user' || item.role === 'assistant');
    const reasoning = item.role === 'assistant' ? String(item.reasoning || '') : '';
    return h('article', { class: `bubble ${item.role}`, dataset: { itemKey: timelineKey(item), streaming: item.streaming ? '1' : '0', reasoningVisible: showReasoning ? '1' : '0' } },
      h('div', { class: 'bubble-head' }, h('strong', { text: item.role === 'user' ? '你' : item.role === 'assistant' ? 'Moyu Chat' : '系统' }), item.inherited ? badge('历史', 'quiet') : null, h('small', { text: fmt(item.createdAt) })),
      reasoning && showReasoning ? h('details', { class: 'reasoning-block', open: Boolean(item.streaming && runtimeRunning) }, h('summary', { text: '模型推理' }), h('pre', { class: 'reasoning-text', text: reasoning })) : null,
      messageBody(item),
      mediaGallery(item.attachments),
      item.streaming && runtimeRunning ? h('span', { class: 'badge good streaming-state', text: streamStageLabel(item.streamStage) }) : null,
      item.role === 'assistant' ? assistantFooter(item, finalized, sessionId) : null,
      finalized && item.role === 'user' ? h('div', { class: 'message-actions' },
        button('编辑', 'edit-message', { sessionId, localSeq: item.localSeq }, 'btn small'),
        button('回滚到此处', 'rollback-message', { sessionId, localSeq: item.localSeq }, 'btn small')) : null);
  }
  if (item.kind === 'error') return h('article', { class: 'error', dataset: { itemKey: timelineKey(item) } }, h('strong', { text: item.summary }), h('code', { text: item.code }));
  return null;
}

function patchMessageNode(node, item, runtimeRunning, sessionId, showReasoning) {
  const desired = timelineItem(item, runtimeRunning, sessionId, showReasoning);
  if (!desired) return null;
  if (node.dataset.streaming !== desired.dataset.streaming || node.dataset.reasoningVisible !== desired.dataset.reasoningVisible) {
    node.replaceWith(desired); return desired;
  }
  node.className = desired.className;
  const body = node.querySelector('.message');
  const desiredBody = desired.querySelector('.message');
  if (!body && desiredBody) node.querySelector('.bubble-head')?.after(desiredBody);
  else if (body && !desiredBody) body.remove();
  else if (body && desiredBody) {
    const sameMode = body.dataset.messageBody === desiredBody.dataset.messageBody;
    if (!sameMode) body.replaceWith(desiredBody);
    else if (body.dataset.messageBody === 'plain' && body.textContent !== desiredBody.textContent) body.textContent = desiredBody.textContent;
  }
  const reasoning = node.querySelector('.reasoning-text');
  const desiredReasoning = desired.querySelector('.reasoning-text');
  if (reasoning && desiredReasoning && reasoning.textContent !== desiredReasoning.textContent) reasoning.textContent = desiredReasoning.textContent;
  else if (!reasoning && desiredReasoning) node.querySelector('.bubble-head')?.after(desired.querySelector('.reasoning-block'));
  else if (reasoning && !desiredReasoning) reasoning.closest('.reasoning-block')?.remove();
  const media = node.querySelector('.media-grid');
  const desiredMedia = desired.querySelector('.media-grid');
  if (media && desiredMedia) {
    if (media.dataset.mediaSignature !== desiredMedia.dataset.mediaSignature) media.replaceWith(desiredMedia);
  } else if (!media && desiredMedia) {
    const bodyAnchor = node.querySelector('.message') || node.querySelector('.reasoning-block') || node.querySelector('.bubble-head');
    bodyAnchor?.after(desiredMedia);
  } else if (media && !desiredMedia) media.remove();
  const streamState = node.querySelector('.streaming-state');
  const desiredStreamState = desired.querySelector('.streaming-state');
  if (streamState && desiredStreamState) streamState.textContent = desiredStreamState.textContent;
  else if (!streamState && desiredStreamState) node.append(desiredStreamState);
  else if (streamState && !desiredStreamState) streamState.remove();
  const footer = node.querySelector('.assistant-footer');
  const desiredFooter = desired.querySelector('.assistant-footer');
  if (footer && desiredFooter) {
    if (footer.dataset.footerSignature !== desiredFooter.dataset.footerSignature) footer.replaceWith(desiredFooter);
  } else if (!footer && desiredFooter) node.append(desiredFooter);
  else if (footer && !desiredFooter) footer.remove();
  return node;
}

function reconcileTimeline(timeline, items, runtimeRunning, sessionId, showReasoning) {
  const desiredItems = timelineProjection(items);
  const existing = new Map([...timeline.children].map((node) => [node.dataset.itemKey, node]));
  const used = new Set();
  let reference = timeline.firstElementChild;
  for (const item of desiredItems) {
    const key = timelineKey(item);
    let node = existing.get(key);
    if (node && item.kind === 'message') node = patchMessageNode(node, item, runtimeRunning, sessionId, showReasoning);
    else if (!node) node = timelineItem(item, runtimeRunning, sessionId, showReasoning);
    if (!node) continue;
    node.dataset.itemKey = key;
    used.add(key);
    if (node !== reference) timeline.insertBefore(node, reference);
    reference = node.nextElementSibling;
  }
  for (const [key, node] of existing) if (!used.has(key)) node.remove();
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

function webModeControl(value = 'auto', dataset = { webModeSelect: '1' }) {
  const labels = { off: '联网 · 关闭', auto: '联网 · 自动', on: '联网 · 开启' };
  return h('select', { class: 'reasoning-select web-mode-select', dataset }, ['off','auto','on'].map((mode) => h('option', { value: mode, selected: mode === value, text: labels[mode] })));
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
        h('small', { class: 'model-meta muted', text: `context ${model.contextTokens} · input ${model.inputTokens || 'auto'} · output ${model.maxOutputTokens} · in[${model.inputModalities || 'text'}] · out[${model.outputModalities || 'text'}]${model.reasoningLevels ? ` · reasoning ${model.reasoningLevels}` : ''}${model.toolCapabilities ? ` · native web[${model.toolCapabilities}]` : ''}` }),
        h('div', { class: 'row' }, reasoningControl(model), contextModeControl(view.ui?.defaultContextMode || 'auto'), webModeControl(view.ui?.defaultWebMode || 'auto'), h('span', { class: 'grow' }), button('Chat', 'new-chat-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn primary small'), button('编辑', 'configure-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn small'))
      )))
    ))) : h('section', { class: 'empty', text: '暂无 Provider。先添加一个 API endpoint。' }));
}

function imports(view) {
  return h('main', { class: 'page' }, h('div', { class: 'section-head' }, h('div', { class: 'grow' }, h('h1', { text: '从 Remote 创建分支' }), h('p', { class: 'muted', text: '导入后生成不可变 Snapshot，之后不再依赖 Remote 数据。' })), button('刷新来源', 'refresh-sources', {}, 'btn')),
    !view.remoteBridge.available ? h('section', { class: 'empty', text: 'RemoteDataBridge 当前不可用。Local Chat 不受影响。' }) :
    h('section', { class: 'stack' }, view.remoteSources.map((source) => h('article', { class: 'card remote-source-card' }, h('div', { class: 'row' }, h('strong', { class: 'grow truncate-safe', text: source.title }), source.runtime ? badge(source.runtime) : null, badge(source.complete ? '本地缓存完整' : `缓存至 ${source.cachedThroughSeq}`, source.complete ? 'good' : 'warn')), h('small', { text: fmt(source.updatedAt) }), h('div', { class: 'row' }, contextModeControl(view.ui?.defaultContextMode || 'auto'), webModeControl(view.ui?.defaultWebMode || 'auto'), h('span', { class: 'grow' }), button('创建本地分支', 'branch-remote', { sourceId: source.sourceId }, 'btn primary small'))))));
}

function handoffs(view) {
  return h('main', { class: 'page' }, h('h1', { text: '回传草稿' }), h('p', { class: 'muted', text: '回传不是同步。只有用户显式提交后，Remote 才能收到 handoff package。' }),
    view.handoffs.length ? h('section', { class: 'stack' }, view.handoffs.map((item) => h('article', { class: 'card' }, h('div', { class: 'row' }, h('strong', { class: 'grow truncate-safe', text: item.summary }), badge(item.state)), h('small', { text: fmt(item.createdAt) }), item.state === 'draft' ? button('提交给 Remote', 'submit-handoff', { handoffId: item.handoffId }, 'btn primary small') : null))) : h('section', { class: 'empty', text: '暂无回传草稿。' }));
}

function settings(view) {
  const nextTheme = view.appearance.theme === 'dark' ? 'system' : view.appearance.theme === 'system' ? 'light' : 'dark';
  const reasoningVisible = Boolean(view.ui?.showReasoning);
  const defaultContextMode = view.ui?.defaultContextMode || 'auto';
  const defaultWebMode = view.ui?.defaultWebMode || 'auto';
  return h('main', { class: 'page' }, h('h1', { text: '设置' }),
    h('section', { class: 'card' }, h('div', { class: 'row' }, h('strong', { text: '主题' }), h('span', { class: 'grow' }), button(`${view.appearance.theme} → ${nextTheme}`, 'theme', { theme: nextTheme }, 'btn small'))),
    h('section', { class: 'card' }, h('div', { class: 'row' }, h('div', { class: 'grow' }, h('strong', { text: '新会话默认上下文模式' }), h('p', { class: 'muted', text: '自动：普通 Chat 不做首轮 Router，仅超窗时按需 Checkpoint；Remote 历史自动启用完整智能层。极简：零额外模型预处理。Moyu 智能：Task Router + Checkpoint。模式在创建后冻结并由本地规则解析。' })), contextModeControl(defaultContextMode, { defaultContextMode: '1' }))),
    h('section', { class: 'card' }, h('div', { class: 'row' }, h('div', { class: 'grow' }, h('strong', { text: '新会话默认联网模式' }), h('p', { class: 'muted', text: '关闭：本轮不使用 hosted Web 或 Moyu fallback；自动：按模型 native capability 与本地 freshness/URL 规则决定；开启：允许 Moyu fallback 强制搜索，但 Provider hosted tool 仍保持 provider-side auto。' })), webModeControl(defaultWebMode, { defaultWebMode: '1' }))),
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
    reconcileTimeline(timeline, session.messages, running, session.localSessionId, Boolean(view.ui?.showReasoning));
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
