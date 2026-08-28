import { bindHost, postIntent } from './bridge.js';
import { createRenderGate } from './render-gate.js';
import { openConfirmDialog, openTextDialog, patchConversation, render, renderNotice, syncTurnNavigator } from './render.js';
import { noteTimelineScroll, setTimelineFollowLatest } from './scroll-anchor.js';
import { getState, setNotice, setOptimisticReasoningEffort, setView, subscribe } from './state.js';

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
    syncTurnNavigator(scroller);
  });
}, true);

function closeChoiceField(field) {
  if (!field) return;
  field.classList.remove('open', 'sheet');
  field.querySelector('.choice-trigger')?.setAttribute('aria-expanded', 'false');
  const menu = field.querySelector('.choice-menu');
  if (menu) {
    menu.style.left = '';
    menu.style.top = '';
    menu.style.bottom = '';
    menu.style.width = '';
    menu.style.maxHeight = '';
  }
  if (!document.querySelector('.choice-field.open.sheet')) document.querySelector('[data-choice-scrim]')?.remove();
}

function closeChoiceMenus(except = null) {
  document.querySelectorAll('.choice-field.open').forEach((field) => { if (field !== except) closeChoiceField(field); });
}

function positionChoiceMenu(field) {
  const trigger = field?.querySelector('.choice-trigger');
  const menu = field?.querySelector('.choice-menu');
  if (!trigger || !menu) return;
  const viewport = window.visualViewport;
  const viewportLeft = viewport?.offsetLeft || 0;
  const viewportTop = viewport?.offsetTop || 0;
  const viewportWidth = viewport?.width || window.innerWidth;
  const viewportHeight = viewport?.height || window.innerHeight;
  const margin = 10;
  const rect = trigger.getBoundingClientRect();
  const mobileSheet = viewportWidth <= 640 && Boolean(window.matchMedia?.('(pointer: coarse)')?.matches);

  if (mobileSheet) {
    field.classList.add('sheet');
    if (!document.querySelector('[data-choice-scrim]')) {
      const scrim = document.createElement('div');
      scrim.className = 'choice-scrim';
      scrim.dataset.choiceScrim = '1';
      document.body.append(scrim);
    }
    const width = Math.max(280, viewportWidth - margin * 2);
    const maxHeight = Math.min(420, Math.max(180, viewportHeight * 0.58));
    menu.style.left = `${Math.round(viewportLeft + margin)}px`;
    menu.style.width = `${Math.round(width)}px`;
    menu.style.maxHeight = `${Math.round(maxHeight)}px`;
    const renderedHeight = Math.min(maxHeight, Math.max(120, menu.scrollHeight));
    menu.style.top = `${Math.round(viewportTop + viewportHeight - renderedHeight - margin)}px`;
    menu.style.bottom = 'auto';
    return;
  }

  field.classList.remove('sheet');
  document.querySelector('[data-choice-scrim]')?.remove();
  const width = Math.min(340, Math.max(rect.width, Math.min(340, viewportWidth - margin * 2)));
  const maxLeft = viewportLeft + viewportWidth - width - margin;
  const left = Math.max(viewportLeft + margin, Math.min(rect.right - width, maxLeft));
  const availableAbove = Math.max(120, rect.top - viewportTop - margin * 2);
  const maxHeight = Math.min(360, availableAbove);
  const top = Math.max(viewportTop + margin, rect.top - maxHeight - 7);
  menu.style.left = `${Math.round(left)}px`;
  menu.style.top = `${Math.round(top)}px`;
  menu.style.bottom = 'auto';
  menu.style.width = `${Math.round(width)}px`;
  menu.style.maxHeight = `${Math.round(maxHeight)}px`;
}

function repositionOpenChoiceMenu() {
  const field = document.querySelector('.choice-field.open');
  if (field) positionChoiceMenu(field);
}
window.visualViewport?.addEventListener?.('resize', repositionOpenChoiceMenu, { passive: true });
window.visualViewport?.addEventListener?.('scroll', repositionOpenChoiceMenu, { passive: true });
window.addEventListener('resize', repositionOpenChoiceMenu, { passive: true });

document.addEventListener('click', (event) => {
  const openSheetField = document.querySelector('.choice-field.open.sheet');
  const clickedField = event.target.closest('.choice-field');
  // The mobile scrim sits below the composer stacking context so the sheet remains touchable.
  // A tap elsewhere in the composer must still be consumed as "close sheet" rather than
  // accidentally sending a message or changing another turn control underneath it.
  if (openSheetField && !openSheetField.contains(event.target)) {
    closeChoiceMenus();
    return;
  }
  if (!clickedField) closeChoiceMenus();
  const clickedTurnNav = event.target.closest('.turn-nav');
  if (!clickedTurnNav) {
    const nav = document.querySelector('.turn-nav.open');
    if (nav) {
      nav.classList.remove('open');
      nav.querySelector('.turn-nav-toggle')?.setAttribute('aria-expanded', 'false');
    }
  }
  const target = event.target.closest('[data-action]');
  if (!target || target.disabled) return;
  const action = target.dataset.action;
  if (action === 'toggle-choice') {
    const field = target.closest('.choice-field');
    if (!field) return;
    const opening = !field.classList.contains('open');
    closeChoiceMenus(field);
    if (opening) {
      field.classList.add('open');
      target.setAttribute('aria-expanded', 'true');
      requestAnimationFrame(() => positionChoiceMenu(field));
    } else closeChoiceField(field);
  }
  else if (action === 'toggle-turn-nav') {
    const nav = target.closest('.turn-nav');
    if (!nav) return;
    const opening = !nav.classList.contains('open');
    nav.classList.toggle('open', opening);
    target.setAttribute('aria-expanded', opening ? 'true' : 'false');
    syncTurnNavigator(document.querySelector('.timeline-scroll'));
  }
  else if (action === 'jump-turn') {
    const scroller = document.querySelector('.timeline-scroll');
    const turnKey = target.dataset.turnKey;
    const node = scroller && [...scroller.querySelectorAll('.timeline [data-item-key]')]
      .find((entry) => entry.dataset.itemKey === turnKey);
    if (!scroller || !node) return;
    setTimelineFollowLatest(scroller.dataset.sessionId || '', false);
    const scrollerRect = scroller.getBoundingClientRect();
    const nodeRect = node.getBoundingClientRect();
    const nextTop = Math.max(0, scroller.scrollTop + nodeRect.top - scrollerRect.top - 8);
    const reduceMotion = Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches);
    scroller.scrollTo({ top: nextTop, behavior: reduceMotion ? 'auto' : 'smooth' });
    noteTimelineScroll(scroller);
    if (window.matchMedia?.('(pointer: coarse)')?.matches) {
      const nav = target.closest('.turn-nav');
      nav?.classList.remove('open');
      nav?.querySelector('.turn-nav-toggle')?.setAttribute('aria-expanded', 'false');
    }
    requestAnimationFrame(() => syncTurnNavigator(scroller));
  }
  else if (action === 'session-model-set') {
    closeChoiceField(target.closest('.choice-field'));
    postIntent('chat.session.model.set', { localSessionId: target.dataset.sessionId, modelProfileId: target.dataset.modelProfileId });
  }
  else if (action === 'session-reasoning-set') {
    closeChoiceField(target.closest('.choice-field'));
    const sessionId = target.dataset.sessionId;
    const reasoningEffort = target.dataset.reasoningEffort;
    // Reflect the user's turn setting immediately. Native persistence remains authoritative, while
    // the subsequent Send also carries this selection so bridge scheduling cannot revert the turn.
    setOptimisticReasoningEffort(sessionId, reasoningEffort);
    postIntent('chat.session.reasoning.set', { localSessionId: sessionId, reasoningEffort });
  }
  else if (action === 'nav') postIntent('nav.open', { route: target.dataset.route });
  else if (action === 'theme') postIntent('appearance.set', { theme: target.dataset.theme });
  else if (action === 'toggle-reasoning') postIntent('chat.reasoning.visibility.set', { show: target.dataset.show === 'true' });
  else if (action === 'toggle-memory') postIntent('memory.global.set', { enabled: target.dataset.enabled === 'true' });
  else if (action === 'open-session') postIntent('chat.session.open', { localSessionId: target.dataset.sessionId });
  else if (action === 'rename-session') {
    const sessionId = target.dataset.sessionId;
    const title = target.dataset.sessionTitle || window.__moyuChatView?.activeSession?.title || '';
    if (!sessionId) return;
    openTextDialog({ title: '重命名会话', value: title, onConfirm: (nextTitle) => postIntent('chat.session.rename', { localSessionId: sessionId, title: nextTitle }) });
  }
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
        title: '回退当前会话',
        message: '这条消息及其后的本地消息将从当前会话撤销，不会自动创建分支。若要保留两条路线，请先使用“分支”。',
        confirmText: '回退', danger: true,
        onConfirm: () => postIntent('chat.message.rollback', { localSessionId: target.dataset.sessionId, localSeq }),
      });
    }
  }
  else if (action === 'branch-message') {
    const localSeq = Number(target.dataset.localSeq);
    if (target.dataset.sessionId && Number.isInteger(localSeq) && localSeq > 0) {
      postIntent('branch.create.local', { localSessionId: target.dataset.sessionId, throughSeq: localSeq });
    }
  }
  else if (action === 'new-chat') {
    const view = window.__moyuChatView;
    const provider = view?.providers?.find((item) => item.configured) || view?.providers?.find((item) => item.models?.length);
    const model = provider?.models?.[0];
    if (!provider || !model) return setNotice({ level: 'warning', text: '请先配置 Provider 和 Model' });
    postIntent('chat.session.create', { providerId: provider.providerId, modelProfileId: model.modelProfileId, contextMode: view?.ui?.defaultContextMode || 'standard', webMode: view?.ui?.defaultWebMode || 'auto' });
  } else if (action === 'new-chat-model') {
    const modelCard = target.closest('.model-card');
    const contextMode = modelCard?.querySelector('select[data-context-mode-select]')?.value || window.__moyuChatView?.ui?.defaultContextMode || 'standard';
    const webMode = modelCard?.querySelector('select[data-web-mode-select]')?.value || window.__moyuChatView?.ui?.defaultWebMode || 'auto';
    postIntent('chat.session.create', { providerId: target.dataset.providerId, modelProfileId: target.dataset.modelProfileId, contextMode, webMode });
  } else if (action === 'configure-provider') postIntent('provider.configure.open', target.dataset.providerId ? { providerId: target.dataset.providerId } : {});
  else if (action === 'configure-model') postIntent('model.configure.open', { providerId: target.dataset.providerId, ...(target.dataset.modelProfileId ? { modelProfileId: target.dataset.modelProfileId } : {}) });
  else if (action === 'pick-attachment') postIntent('chat.attachment.pick', { localSessionId: target.dataset.sessionId });
  else if (action === 'remove-attachment') postIntent('chat.attachment.remove', { localSessionId: target.dataset.sessionId, mediaId: target.dataset.mediaId });
  else if (action === 'import-file-pick') postIntent('import.file.pick', { source: target.dataset.source });
  else if (action === 'import-open') {
    const sourceCard = target.closest('.import-source-card');
    const contextMode = sourceCard?.querySelector('select[data-context-mode-select]')?.value || window.__moyuChatView?.ui?.defaultContextMode || 'standard';
    const webMode = sourceCard?.querySelector('select[data-web-mode-select]')?.value || window.__moyuChatView?.ui?.defaultWebMode || 'auto';
    postIntent('import.session.open', { snapshotId: target.dataset.snapshotId, contextMode, webMode });
  }
  else if (action === 'import-delete') {
    const snapshotId = target.dataset.snapshotId;
    const title = target.dataset.sourceTitle || '此导入会话';
    if (!snapshotId) return;
    openConfirmDialog({
      title: '删除导入源',
      message: `删除“${title}”的本地清洗副本？如果仍有 Chat 使用它作为来源，Moyu 会拒绝删除。`,
      confirmText: '删除', danger: true,
      onConfirm: () => postIntent('import.session.delete', { snapshotId }),
    });
  }
  else if (action === 'verification-feedback') {
    const localSeq = Number(target.dataset.localSeq);
    if (target.dataset.sessionId && Number.isInteger(localSeq) && localSeq > 0 && target.dataset.label) {
      postIntent('verification.feedback', { localSessionId: target.dataset.sessionId, localSeq, label: target.dataset.label });
    }
  }
  else if (action === 'refresh-sources') postIntent('branch.sources.refresh', {});
  else if (action === 'branch-remote') {
    const sourceCard = target.closest('.remote-source-card');
    const contextMode = sourceCard?.querySelector('select[data-context-mode-select]')?.value || window.__moyuChatView?.ui?.defaultContextMode || 'standard';
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
const draftGenerations = new Map();
function draftGeneration(sessionId) { return draftGenerations.get(sessionId) || 0; }
function advanceDraftGeneration(sessionId) {
  const next = draftGeneration(sessionId) + 1;
  draftGenerations.set(sessionId, next);
  return next;
}

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
  advanceDraftGeneration(sessionId);
  const reasoningEffort = getState().view?.activeSession?.localSessionId === sessionId
    ? getState().view.activeSession.reasoningEffort
    : undefined;
  const requestId = postIntent('chat.session.send', { localSessionId: sessionId, text, ...(reasoningEffort ? { reasoningEffort } : {}) });
  if (requestId && input) {
    input.value = '';
    resizeComposerTextarea(input);
    // Keep focus after send so a stale pre-send full render cannot repopulate the submitted draft.
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
  const generation = draftGeneration(sessionId);
  const text = event.target.value;
  draftTimer = setTimeout(() => {
    if (draftGeneration(sessionId) !== generation) return;
    postIntent('chat.session.saveDraft', { localSessionId: sessionId, text });
  }, 400);
});

document.addEventListener('focusin', (event) => {
  if (event.target?.name === 'message') resizeComposerTextarea(event.target);
});

postIntent('app.ready', { uiVersion: '0.0.3' });
