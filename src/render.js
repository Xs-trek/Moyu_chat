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

function shell(view, body) {
  const routes = [['home','Chat'],['imports','分支'],['providers','Provider'],['handoffs','回传'],['settings','设置']];
  return h('div', { class: 'app-shell' },
    h('header', { class: 'topbar' }, h('strong', { class: 'brand', text: '摸鱼 · Chat' }), badge(view.remoteBridge.available ? 'Remote 可导入' : '独立模式', view.remoteBridge.available ? 'good' : 'quiet')),
    body,
    h('nav', { class: 'nav' }, routes.map(([route, label]) => button(label, 'nav', { route }, `nav-item ${view.route === route ? 'active' : ''}`)))
  );
}

function home(view) {
  return h('main', { class: 'page' },
    h('section', { class: 'hero card' }, h('div', {}, h('small', { text: 'LOCAL CHAT' }), h('h1', { text: '不依赖 PC 的 Moyu 补充会话' }), h('p', { text: '会话、分支与 Provider 均由本机维护。Remote 只作为可选的只读来源。' })), button('＋ 新建 Chat', 'new-chat', {}, 'btn primary')),
    h('section', { class: 'section-head' }, h('h2', { text: '最近会话' })),
    view.sessions.length ? h('section', { class: 'stack' }, view.sessions.map((session) => h('article', { class: 'card session-card' },
      h('div', { class: 'row' }, h('strong', { text: session.title }), badge(session.state)),
      h('p', { class: 'muted', text: session.preview || '暂无内容' }),
      h('div', { class: 'row' }, h('small', { text: `${session.providerName} · ${session.model} · ${fmt(session.updatedAt)}` }), h('span', { class: 'grow' }), session.origin ? badge(`分支 · ${session.origin.sourceTitle}`, 'branch') : null, button('打开', 'open-session', { sessionId: session.localSessionId }, 'btn small'))))) : h('section', { class: 'empty', text: '还没有本地 Chat，会话可在没有 Remote 的情况下创建。' })
  );
}

function conversation(view) {
  const session = view.activeSession;
  if (!session) return home(view);
  const imageInput = String(session.inputModalities || '').split(',').includes('image');
  return h('main', { class: 'conversation' },
    h('header', { class: 'conversation-head' }, button('←', 'nav', { route: 'home' }, 'btn small'), h('div', { class: 'grow' }, h('strong', { text: session.title }), h('small', { text: `${session.providerName} · ${session.model} · reasoning ${session.reasoningEffort || 'default'}` })), imageInput ? badge('图片输入', 'good') : null, session.origin ? badge(`来自 ${session.origin.sourceTitle}`, 'branch') : null, button('分支', 'branch-local', { sessionId: session.localSessionId }, 'btn small'), button('回传', 'handoff', { sessionId: session.localSessionId }, 'btn small')),
    h('section', { class: 'timeline' }, session.messages.map((item) => timelineItem(item))),
    h('form', { class: 'composer', dataset: { form: 'composer', sessionId: session.localSessionId } },
      mediaGallery(session.draftMedia, true, session.localSessionId),
      session.canAttachImage ? button('＋ 图片', 'pick-attachment', { sessionId: session.localSessionId }, 'btn') : null,
      h('textarea', { name: 'message', rows: '2', value: session.composerDraft || '', placeholder: session.canSend ? (imageInput ? '输入消息，或仅发送图片' : '输入消息') : '请先配置 Provider 或等待当前回合结束' }),
      session.canInterrupt ? button('中断', 'interrupt', { sessionId: session.localSessionId }, 'btn danger') : null,
      h('button', { type: 'submit', class: 'btn primary', disabled: !session.canSend, text: session.state === 'running' ? '运行中' : '发送' }))
  );
}

function timelineItem(item) {
  if (item.kind === 'message') return h('article', { class: `bubble ${item.role}` }, h('div', { class: 'bubble-head' }, h('strong', { text: item.role === 'user' ? '你' : item.role === 'assistant' ? 'Moyu Chat' : '系统' }), h('small', { text: fmt(item.createdAt) })), item.text ? h('pre', { class: 'message', text: item.text }) : null, mediaGallery(item.attachments), item.streaming ? badge('streaming', 'good') : null);
  if (item.kind === 'usage') return h('article', { class: 'usage', text: `输入 ${item.inputTokens || 0} · 输出 ${item.outputTokens || 0}` });
  if (item.kind === 'error') return h('article', { class: 'error' }, h('strong', { text: item.summary }), h('code', { text: item.code }));
  return null;
}

function reasoningControl(model) {
  const levels = String(model.reasoningLevels || '').split(',').map((x) => x.trim()).filter(Boolean);
  if (!levels.length) return badge('reasoning · provider default', 'quiet');
  const values = ['default', ...levels.filter((x) => x !== 'default')];
  return h('select', { class: 'reasoning-select', dataset: { reasoningSelect: '1' } }, values.map((level) => h('option', { value: level, selected: level === (model.defaultReasoning || 'default'), text: level })));
}

function providers(view) {
  return h('main', { class: 'page' }, h('div', { class: 'section-head' }, h('div', {}, h('h1', { text: 'Provider / Models' }), h('p', { class: 'muted', text: 'Endpoint/Auth 属于 Provider；context/input/output/reasoning 能力属于该 endpoint 下的 Model Profile。' })), button('＋ Provider', 'configure-provider', {}, 'btn primary')),
    h('section', { class: 'stack' }, view.providers.map((provider) => h('article', { class: 'card' },
      h('div', { class: 'row' }, h('strong', { text: provider.displayName }), badge(provider.protocol), badge(provider.reasoningWire || 'none'), h('span', { class: 'grow' }), badge(provider.hasCredential ? '凭据已配置' : '缺少凭据', provider.hasCredential ? 'good' : 'warn')),
      h('div', { class: 'row' }, button('编辑 Provider', 'configure-provider', { providerId: provider.providerId }, 'btn small'), button('＋ 模型', 'configure-model', { providerId: provider.providerId }, 'btn small')),
      h('section', { class: 'stack' }, (provider.models || []).map((model) => h('div', { class: 'card' },
        h('div', { class: 'row' }, h('strong', { text: model.displayName || model.modelId }), h('code', { text: model.modelId }), h('span', { class: 'grow' }), badge(model.defaultReasoning || 'default')),
        h('small', { class: 'muted', text: `context ${model.contextTokens} · input ${model.inputTokens || 'auto'} · output ${model.maxOutputTokens}${model.reasoningLevels ? ` · reasoning ${model.reasoningLevels}` : ''}` }),
        h('div', { class: 'row' }, reasoningControl(model), h('span', { class: 'grow' }), button('Chat', 'new-chat-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn primary small'), button('编辑模型', 'configure-model', { providerId: provider.providerId, modelProfileId: model.modelProfileId }, 'btn small'))
      )))
    ))));
}

function imports(view) {
  return h('main', { class: 'page' }, h('div', { class: 'section-head' }, h('div', {}, h('h1', { text: '从 Remote 创建分支' }), h('p', { class: 'muted', text: '导入后生成不可变 Snapshot，之后不再依赖 Remote 数据。' })), button('刷新来源', 'refresh-sources', {}, 'btn')),
    !view.remoteBridge.available ? h('section', { class: 'empty', text: 'RemoteDataBridge 当前不可用。Local Chat 不受影响。' }) :
    h('section', { class: 'stack' }, view.remoteSources.map((source) => h('article', { class: 'card' }, h('div', { class: 'row' }, h('strong', { text: source.title }), source.runtime ? badge(source.runtime) : null, h('span', { class: 'grow' }), badge(source.complete ? '本地缓存完整' : `缓存至 ${source.cachedThroughSeq}`, source.complete ? 'good' : 'warn')), h('small', { text: fmt(source.updatedAt) }), button('创建本地分支', 'branch-remote', { sourceId: source.sourceId }, 'btn primary small')))));
}

function handoffs(view) {
  return h('main', { class: 'page' }, h('h1', { text: '回传草稿' }), h('p', { class: 'muted', text: '回传不是同步。只有用户显式提交后，Remote 才能收到 handoff package。' }),
    view.handoffs.length ? h('section', { class: 'stack' }, view.handoffs.map((item) => h('article', { class: 'card' }, h('div', { class: 'row' }, h('strong', { text: item.summary }), badge(item.state)), h('small', { text: fmt(item.createdAt) }), item.state === 'draft' ? button('提交给 Remote', 'submit-handoff', { handoffId: item.handoffId }, 'btn primary small') : null))) : h('section', { class: 'empty', text: '暂无回传草稿。' }));
}

function settings(view) {
  const nextTheme = view.appearance.theme === 'dark' ? 'system' : view.appearance.theme === 'system' ? 'light' : 'dark';
  return h('main', { class: 'page' }, h('h1', { text: '设置' }), h('section', { class: 'card' }, h('div', { class: 'row' }, h('strong', { text: '主题' }), h('span', { class: 'grow' }), button(`${view.appearance.theme} → ${nextTheme}`, 'theme', { theme: nextTheme }, 'btn small'))), h('section', { class: 'card' }, h('strong', { text: '数据边界' }), h('p', { class: 'muted', text: 'Chat 使用自己的数据库。RemoteDataBridge 只提供 Snapshot/Handoff，不共享 SQLite 文件。' })));
}

export function render() {
  const state = getState();
  const root = document.querySelector('#app');
  if (!root) return;
  const view = state.view;
  if (!view) { root.replaceChildren(h('main', { class: 'loading', text: '正在加载本地 Chat…' })); return; }
  window.__moyuChatView = view;
  const pages = { home, conversation, providers, imports, handoffs, settings };
  const body = (pages[view.route] || home)(view);
  document.documentElement.dataset.theme = view.appearance.resolvedTheme;
  root.replaceChildren(shell(view, body));
  const toast = document.querySelector('#toast-root');
  toast.replaceChildren(state.notice ? h('div', { class: `toast ${state.notice.level || 'info'}`, text: state.notice.text }) : null);
}
