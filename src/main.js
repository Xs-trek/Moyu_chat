import { bindHost, postIntent } from './bridge.js';
import { render } from './render.js';
import { setNotice, setView, subscribe } from './state.js';

subscribe(render);
bindHost((view, revision) => setView(view, revision));
window.addEventListener('moyu:notice', (event) => setNotice(event.detail));

document.addEventListener('click', (event) => {
  const target = event.target.closest('[data-action]');
  if (!target || target.disabled) return;
  const action = target.dataset.action;
  if (action === 'nav') postIntent('nav.open', { route: target.dataset.route });
  else if (action === 'theme') postIntent('appearance.set', { theme: target.dataset.theme });
  else if (action === 'open-session') postIntent('chat.session.open', { localSessionId: target.dataset.sessionId });
  else if (action === 'new-chat') {
    const view = window.__moyuChatView;
    const provider = view?.providers?.find((item) => item.configured) || view?.providers?.find((item) => item.models?.length);
    const model = provider?.models?.[0];
    if (!provider || !model) return setNotice({ level: 'warning', text: '请先配置 Provider 和 Model' });
    postIntent('chat.session.create', { providerId: provider.providerId, modelProfileId: model.modelProfileId });
  }
  else if (action === 'new-chat-model') postIntent('chat.session.create', { providerId: target.dataset.providerId, modelProfileId: target.dataset.modelProfileId });
  else if (action === 'configure-provider') postIntent('provider.configure.open', target.dataset.providerId ? { providerId: target.dataset.providerId } : {});
  else if (action === 'configure-model') postIntent('model.configure.open', { providerId: target.dataset.providerId, ...(target.dataset.modelProfileId ? { modelProfileId: target.dataset.modelProfileId } : {}) });
  else if (action === 'refresh-sources') postIntent('branch.sources.refresh', {});
  else if (action === 'branch-remote') postIntent('branch.create.remote', { sourceId: target.dataset.sourceId });
  else if (action === 'branch-local') postIntent('branch.create.local', { localSessionId: target.dataset.sessionId });
  else if (action === 'interrupt') postIntent('chat.session.interrupt', { localSessionId: target.dataset.sessionId });
  else if (action === 'handoff') postIntent('handoff.create', { localSessionId: target.dataset.sessionId });
  else if (action === 'submit-handoff') postIntent('handoff.submit', { handoffId: target.dataset.handoffId });
});

document.addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.target;
  if (form.dataset.form === 'composer') {
    const input = form.elements.namedItem('message');
    const text = String(input?.value || '');
    const sessionId = form.dataset.sessionId;
    if (!text.trim() || !sessionId) return;
    postIntent('chat.session.send', { localSessionId: sessionId, text });
    input.value = '';
  }
});

let draftTimer = 0;
document.addEventListener('input', (event) => {
  if (event.target.name !== 'message') return;
  const form = event.target.closest('form[data-form="composer"]');
  if (!form) return;
  clearTimeout(draftTimer);
  draftTimer = setTimeout(() => postIntent('chat.session.saveDraft', { localSessionId: form.dataset.sessionId, text: event.target.value }), 400);
});

postIntent('app.ready', { uiVersion: '0.0.1' });
