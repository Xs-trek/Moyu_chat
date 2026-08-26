import { bindHost, postIntent } from './bridge.js';
import { createRenderGate } from './render-gate.js';
import { openConfirmDialog, patchConversation, render, renderNotice } from './render.js';
import { noteTimelineScroll, setTimelineFollowLatest } from './scroll-anchor.js';
import { getState, setNotice, setView, subscribe } from './state.js';

const renderGate = createRenderGate(render);
subscribe((_state, change) => {
  if (change?.scope === 'toast') renderNotice();
  else if (!patchConversation()) renderGate.request();
});
bindHost((view, revision) => setView(view, revision));
window.addEventListener('moyu:notice', (event) => setNotice(event.detail));

let pointerMaySelectText = false;
function selectionScopeForNode(node) {
  const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  return element?.closest?.('[data-selection-scope]') || null;
}
document.addEventListener('pointerdown', (event) => {
  pointerMaySelectText = Boolean(selectionScopeForNode(event.target));
  renderGate.pointerDown(pointerMaySelectText);
}, true);
document.addEventListener('pointerup', () => {
  renderGate.pointerUp(pointerMaySelectText ? 160 : 0);
  pointerMaySelectText = false;
}, true);
document.addEventListener('pointercancel', () => {
  renderGate.pointerUp(0);
  pointerMaySelectText = false;
}, true);
document.addEventListener('compositionstart', () => renderGate.compositionStart(), true);
document.addEventListener('compositionend', () => renderGate.compositionEnd(), true);
document.addEventListener('focusout', () => setTimeout(() => renderGate.interactionEnded(), 0), true);

const systemTheme = window.matchMedia?.('(prefers-color-scheme: dark)');
systemTheme?.addEventListener?.('change', () => {
  if (getState().view?.appearance?.theme === 'system') renderGate.request();
});

let scrollFrame = 0;
let pendingScrollNode = null;
document.addEventListener('scroll', (event) => {
  const node = event.target;
  if (!(node instanceof Element) || !node.classList.contains('timeline-scroll')) return;
  pendingScrollNode = node;
  if (scrollFrame) return;
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0;
    const scroller = pendingScrollNode;
    pendingScrollNode = null;
    if (!scroller) return;
    const state = noteTimelineScroll(scroller);
    const jump = scroller.querySelector('.jump-latest');
    if (jump) jump.classList.toggle('is-hidden', state.nearBottom && state.followLatest);
  });
}, true);

document.addEventListener('click', (event) => {
  const clickedField = event.target.closest('.choice-field');
  if (!clickedField) document.querySelectorAll('.choice-field.open').forEach((node) => { node.classList.remove('open'); node.querySelector('.choice-trigger')?.setAttribute('aria-expanded', 'false'); });
  const target = event.target.closest('[data-action]');
  if (!target || target.disabled) return;
  const action = target.dataset.action;
  if (action === 'toggle-choice') {
    const field = target.closest('.choice-field');
    if (!field) return;
    const opening = !field.classList.contains('open');
    document.querySelectorAll('.choice-field.open').forEach((node) => { node.classList.remove('open'); node.querySelector('.choice-trigger')?.setAttribute('aria-expanded', 'false'); });
    if (opening) { field.classList.add('open'); target.setAttribute('aria-expanded', 'true'); }
  }
  else if (action === 'session-model-set') {
    target.closest('.choice-field')?.classList.remove('open');
    postIntent('chat.session.model.set', { localSessionId: target.dataset.sessionId, modelProfileId: target.dataset.modelProfileId });
  }
  else if (action === 'session-reasoning-set') {
    target.closest('.choice-field')?.classList.remove('open');
    postIntent('chat.session.reasoning.set', { localSessionId: target.dataset.sessionId, reasoningEffort: target.dataset.reasoningEffort });
  }
  else if (action === 'nav') postIntent('nav.open', { route: target.dataset.route });
  else if (action === 'theme') postIntent('appearance.set', { theme: target.dataset.theme });
  else if (action === 'toggle-reasoning') postIntent('chat.reasoning.visibility.set', { show: target.dataset.show === 'true' });
  else if (action === 'open-session') postIntent('chat.session.open', { localSessionId: target.dataset.sessionId });
  else if (action === 'delete-session') {
    const sessionId = target.dataset.sessionId;
    if (!sessionId) return;
    const title = target.dataset.sessionTitle || '此会话';
    openConfirmDialog({
      title: '删除本地会话',
      message: `删除“${title}”？原会话记录与未被其它分支引用的本地媒体将被删除，此操作不可恢复。`,
      confirmText: '删除', danger: true,
      onConfirm: () => postIntent('chat.session.delete', { localSessionId: sessionId }),
    });
  }
  else if (action === 'edit-message') {
    const localSeq = Number(target.dataset.localSeq);
    if (target.dataset.sessionId && Number.isInteger(localSeq) && localSeq > 0) {
      postIntent('chat.message.edit', { localSessionId: target.dataset.sessionId, localSeq });
    }
  }
  else if (action === 'rollback-message') {
    const localSeq = Number(target.dataset.localSeq);
    if (target.dataset.sessionId && Number.isInteger(localSeq) && localSeq > 0) {
      openConfirmDialog({
        title: '从这里继续',
        message: '将以这条消息为截止点创建一个新的本地分支。原会话不会被修改或删除。',
        confirmText: '创建回滚分支',
        onConfirm: () => postIntent('chat.message.rollback', { localSessionId: target.dataset.sessionId, localSeq }),
      });
    }
  }
  else if (action === 'new-chat') {
    const view = window.__moyuChatView;
    const provider = view?.providers?.find((item) => item.configured) || view?.providers?.find((item) => item.models?.length);
    const model = provider?.models?.[0];
    if (!provider || !model) return setNotice({ level: 'warning', text: '请先配置 Provider 和 Model' });
    postIntent('chat.session.create', { providerId: provider.providerId, modelProfileId: model.modelProfileId, contextMode: view?.ui?.defaultContextMode || 'auto', webMode: view?.ui?.defaultWebMode || 'auto' });
  } else if (action === 'new-chat-model') {
    const modelCard = target.closest('.model-card');
    const effort = modelCard?.querySelector('select[data-reasoning-select]')?.value || 'default';
    const contextMode = modelCard?.querySelector('select[data-context-mode-select]')?.value || window.__moyuChatView?.ui?.defaultContextMode || 'auto';
    const webMode = modelCard?.querySelector('select[data-web-mode-select]')?.value || window.__moyuChatView?.ui?.defaultWebMode || 'auto';
    postIntent('chat.session.create', { providerId: target.dataset.providerId, modelProfileId: target.dataset.modelProfileId, reasoningEffort: effort, contextMode, webMode });
  } else if (action === 'configure-provider') postIntent('provider.configure.open', target.dataset.providerId ? { providerId: target.dataset.providerId } : {});
  else if (action === 'configure-model') postIntent('model.configure.open', { providerId: target.dataset.providerId, ...(target.dataset.modelProfileId ? { modelProfileId: target.dataset.modelProfileId } : {}) });
  else if (action === 'pick-attachment') postIntent('chat.attachment.pick', { localSessionId: target.dataset.sessionId });
  else if (action === 'remove-attachment') postIntent('chat.attachment.remove', { localSessionId: target.dataset.sessionId, mediaId: target.dataset.mediaId });
  else if (action === 'refresh-sources') postIntent('branch.sources.refresh', {});
  else if (action === 'branch-remote') {
    const sourceCard = target.closest('.remote-source-card');
    const contextMode = sourceCard?.querySelector('select[data-context-mode-select]')?.value || window.__moyuChatView?.ui?.defaultContextMode || 'auto';
    const webMode = sourceCard?.querySelector('select[data-web-mode-select]')?.value || window.__moyuChatView?.ui?.defaultWebMode || 'auto';
    postIntent('branch.create.remote', { sourceId: target.dataset.sourceId, contextMode, webMode });
  }
  else if (action === 'branch-local') postIntent('branch.create.local', { localSessionId: target.dataset.sessionId });
  else if (action === 'interrupt') postIntent('chat.session.interrupt', { localSessionId: target.dataset.sessionId });
  else if (action === 'handoff') postIntent('handoff.create', { localSessionId: target.dataset.sessionId });
  else if (action === 'submit-handoff') postIntent('handoff.submit', { handoffId: target.dataset.handoffId });
  else if (action === 'open-source' && target.dataset.url) postIntent('external.open', { url: target.dataset.url });
  else if (action === 'jump-latest') {
    const node = document.querySelector('.timeline-scroll');
    if (node) {
      setTimelineFollowLatest(node.dataset.sessionId || '', true);
      node.scrollTop = node.scrollHeight;
      noteTimelineScroll(node);
      node.querySelector('.jump-latest')?.classList.add('is-hidden');
    }
  }
  else if (action === 'copy-message') {
    const seq = Number(target.dataset.localSeq);
    const item = window.__moyuChatView?.activeSession?.messages?.find((entry) => Number(entry.localSeq) === seq && entry.kind === 'message');
    const text = String(item?.text || '');
    if (!text) return;
    navigator.clipboard?.writeText(text).then(() => setNotice({ level: 'info', text: '已复制回答' })).catch(() => setNotice({ level: 'warning', text: '复制失败' }));
  }
  else if (action === 'copy-code') {
    const text = target.closest('.md-code-block')?.querySelector('code')?.textContent || '';
    if (!text) return;
    navigator.clipboard?.writeText(text).then(() => {
      const previous = target.textContent;
      target.textContent = '已复制';
      setTimeout(() => { if (target.isConnected) target.textContent = previous || '复制'; }, 1200);
    }).catch(() => setNotice({ level: 'warning', text: '复制代码失败' }));
  }
});

document.addEventListener('change', (event) => {
  if (event.target?.dataset?.defaultContextMode === '1') {
    postIntent('context.mode.default.set', { mode: event.target.value });
  } else if (event.target?.dataset?.defaultWebMode === '1') {
    postIntent('web.mode.default.set', { mode: event.target.value });
  }
});

let draftTimer = 0;

function resizeComposerTextarea(input) {
  if (!input || input.name !== 'message') return;
  input.style.height = 'auto';
  const max = 160;
  input.style.height = `${Math.min(max, Math.max(44, input.scrollHeight))}px`;
  input.style.overflowY = input.scrollHeight > max ? 'auto' : 'hidden';
}

document.addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.target;
  if (form.dataset.form !== 'composer') return;
  const input = form.elements.namedItem('message');
  const text = String(input?.value || '');
  const sessionId = form.dataset.sessionId;
  const hasDraftMedia = Boolean(window.__moyuChatView?.activeSession?.draftMedia?.length);
  if ((!text.trim() && !hasDraftMedia) || !sessionId) return;
  clearTimeout(draftTimer);
  draftTimer = 0;
  const requestId = postIntent('chat.session.send', { localSessionId: sessionId, text });
  if (requestId && input) {
    input.value = '';
    resizeComposerTextarea(input);
    input.blur();
    renderGate.interactionEnded();
  }
});

document.addEventListener('keydown', (event) => {
  if (event.target?.name !== 'message' || event.key !== 'Enter' || event.shiftKey || event.isComposing) return;
  // Mobile chat keyboards use Enter for a newline; the explicit send affordance owns submission.
  if (window.matchMedia?.('(pointer: coarse)')?.matches) return;
  event.preventDefault();
  event.target.closest('form[data-form="composer"]')?.requestSubmit();
});

document.addEventListener('input', (event) => {
  if (event.target.name !== 'message') return;
  resizeComposerTextarea(event.target);
  const form = event.target.closest('form[data-form="composer"]');
  if (!form) return;
  clearTimeout(draftTimer);
  const sessionId = form.dataset.sessionId;
  draftTimer = setTimeout(() => postIntent('chat.session.saveDraft', { localSessionId: sessionId, text: event.target.value }), 400);
});

document.addEventListener('focusin', (event) => {
  if (event.target?.name === 'message') resizeComposerTextarea(event.target);
});

postIntent('app.ready', { uiVersion: '0.0.2' });
