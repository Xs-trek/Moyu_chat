import { bindHost, postIntent } from './bridge.js';
import { flash, randomQuote } from './mascot.js';
import { closeSheet, handleBack, openActions, openConfirmDialog, openTextDialog, openTurnSettings, setDrawer } from './overlays.js';
import { createRenderGate } from './render-gate.js';
import { patchConversation, render, renderNotice, resizeTextarea, syncTurnNavigator, toggleTurnDetails } from './render.js';
import { noteTimelineScroll, setTimelineFollowLatest } from './scroll-anchor.js';
import { getState, setNotice, setOptimisticReasoningEffort, setOptimisticWebMode, setView, subscribe } from './state.js';
import * as vm from './view-model.js';

const renderGate = createRenderGate(render);
subscribe((_state, change) => {
  if (change?.scope === 'toast') renderNotice();
  else if (!patchConversation()) renderGate.request();
});
bindHost((view, revision) => setView(view, revision));
window.addEventListener('moyu:notice', (event) => setNotice(event.detail));
// Native Back asks the page first, so the drawer, sheets and dialogs close before the route changes.
window.moyuHandleBack = () => handleBack();

let pointerMaySelectText = false;
function selectionScopeForNode(node) {
  const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  return element?.closest?.('[data-selection-scope]') || null;
}
function editableTextControlForNode(node) {
  const element = node?.nodeType === Node.ELEMENT_NODE ? node : node?.parentElement;
  return element?.closest?.('textarea,input') || null;
}
// Native chat chrome is not document content. Only conversation text/reasoning and editable text
// controls may start a selection; badges, metrics, headers and buttons remain interaction-only.
document.addEventListener('selectstart', (event) => {
  if (selectionScopeForNode(event.target) || editableTextControlForNode(event.target)) return;
  event.preventDefault();
}, true);
document.addEventListener('pointerdown', (event) => {
  pointerMaySelectText = Boolean(selectionScopeForNode(event.target) || editableTextControlForNode(event.target));
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
document.addEventListener('selectionchange', () => renderGate.interactionEnded(), true);
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
    document.querySelector('.jump-latest')?.classList.toggle('is-hidden', state.nearBottom && state.followLatest);
    syncTurnNavigator(scroller);
  });
}, true);

function currentView() { return getState().view; }
function seqOf(target) { const n = Number(target.dataset.localSeq); return Number.isInteger(n) && n > 0 ? n : 0; }
function messageText(seq) {
  const item = currentView()?.activeSession?.messages?.find((entry) => Number(entry.localSeq) === seq && entry.kind === 'message');
  return String(item?.text || '');
}
function closeDrawerIfOverlay() { if (!window.matchMedia?.('(min-width: 960px)')?.matches) setDrawer(false); }

function copyText(text, target, okText) {
  if (!text) return;
  navigator.clipboard?.writeText(text).then(() => {
    if (vm.appearance(currentView()).delight && target?.isConnected) {
      target.classList.remove('bubbling');
      void target.offsetWidth;
      target.classList.add('bubbling');
      setTimeout(() => target.classList.remove('bubbling'), 900);
    } else setNotice({ level: 'info', text: okText });
  }).catch(() => setNotice({ level: 'warning', text: '复制失败' }));
}

let pokes = [];
function pokeFish(target) {
  const fishNode = target.querySelector('.fish');
  const now = Date.now();
  pokes = pokes.filter((t) => now - t < 4000).concat(now);
  if (!vm.appearance(currentView()).delight) return;
  const say = target.parentElement?.querySelector('.fish-say');
  if (say) say.textContent = randomQuote();
  if (pokes.length >= 7) { pokes = []; flash(fishNode, 'flip', 900, 'idle'); }
  else flash(fishNode, 'done', 520, 'idle');
}

function sessionMenu(target) {
  const sessionId = target.dataset.sessionId;
  const title = target.dataset.sessionTitle || '会话';
  const running = target.dataset.running === '1';
  openActions(title, [
    { label: '重命名', icon: 'rename', action: 'rename-session', data: { sessionId, sessionTitle: title }, disabled: running },
    { label: '删除', icon: 'trash', action: 'delete-session', data: { sessionId, sessionTitle: title }, danger: true, disabled: running, hint: running ? '回合进行中' : '' },
  ]);
}

function conversationMenu(target) {
  const view = currentView();
  const session = view?.activeSession;
  if (!session) return;
  const running = session.state === 'running';
  const showing = Boolean(view.ui?.showReasoning);
  openActions(session.title || '会话', [
    { label: '重命名', icon: 'rename', action: 'rename-session', data: { sessionId: session.localSessionId, sessionTitle: session.title || '' } },
    { label: '从当前位置分支', icon: 'branch', action: 'branch-local', data: { sessionId: session.localSessionId }, disabled: running, hint: running ? '等当前回合结束' : '复制到这里为止的历史，开一个新会话' },
    { label: showing ? '隐藏模型推理' : '显示模型推理', icon: 'eye', action: 'toggle-reasoning', data: { show: showing ? 'false' : 'true' } },
    { label: '删除会话', icon: 'trash', action: 'delete-session', data: { sessionId: session.localSessionId, sessionTitle: session.title || '' }, danger: true, disabled: running },
  ]);
  void target;
}

function messageMenu(target) {
  const sessionId = target.dataset.sessionId;
  const localSeq = String(seqOf(target));
  const user = target.dataset.role === 'user';
  openActions(user ? '这条提问' : '这条回答', [
    user ? { label: '编辑后重发', icon: 'edit', action: 'edit-message', data: { sessionId, localSeq } } : null,
    { label: '复制', icon: 'copy', action: 'copy-message', data: { sessionId, localSeq } },
    { label: '从这里分支', icon: 'branch', action: 'branch-message', data: { sessionId, localSeq }, hint: '保留原会话，另开一条路线' },
    { label: '回退到这里之前', icon: 'undo', action: 'rollback-message', data: { sessionId, localSeq }, danger: true, hint: '撤销这条及之后的消息' },
  ]);
}

function newChat(view) {
  const configured = (p) => p?.hasCredential && p.models?.length;
  const active = view?.activeSession;
  const activeProvider = view?.providers?.find((p) => p.providerId === active?.providerId);
  const activeModel = activeProvider?.models?.find((m) => m.modelProfileId === active?.modelProfileId);
  const provider = configured(activeProvider) && activeModel ? activeProvider : view?.providers?.find((p) => p.configured) || view?.providers?.find(configured);
  const model = provider === activeProvider && activeModel ? activeModel : provider?.models?.[0];
  if (!provider || !model) return setNotice({ level: 'warning', text: '请先配置 Provider 和 Model' });
  postIntent('chat.session.create', { providerId: provider.providerId, modelProfileId: model.modelProfileId, contextMode: view?.ui?.defaultContextMode || 'standard', webMode: view?.ui?.defaultWebMode || 'auto' });
}

document.addEventListener('click', (event) => {
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
  const sessionId = target.dataset.sessionId;
  if (target.dataset.closeSheet === '1') closeSheet();

  if (action === 'drawer-open') setDrawer(true);
  else if (action === 'drawer-close') setDrawer(false);
  else if (action === 'sheet-close') closeSheet();
  else if (action === 'open-turn-settings') openTurnSettings(sessionId);
  else if (action === 'conversation-menu') conversationMenu(target);
  else if (action === 'session-menu') sessionMenu(target);
  else if (action === 'message-menu') messageMenu(target);
  else if (action === 'toggle-turn-details') { toggleTurnDetails(target.dataset.itemKey); if (!patchConversation()) renderGate.request(); }
  else if (action === 'poke-fish') pokeFish(target);
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
  else if (action === 'session-model-set') postIntent('chat.session.model.set', { localSessionId: sessionId, modelProfileId: target.dataset.modelProfileId });
  else if (action === 'session-reasoning-set') {
    const reasoningEffort = target.dataset.reasoningEffort;
    // Reflect the user's turn setting immediately. Native persistence remains authoritative, while
    // the subsequent Send also carries this selection so bridge scheduling cannot revert the turn.
    setOptimisticReasoningEffort(sessionId, reasoningEffort);
    postIntent('chat.session.reasoning.set', { localSessionId: sessionId, reasoningEffort });
  }
  else if (action === 'session-web-set') {
    const webMode = target.dataset.webMode;
    setOptimisticWebMode(sessionId, webMode);
    postIntent('chat.session.web.set', { localSessionId: sessionId, webMode });
  }
  else if (action === 'nav') { closeDrawerIfOverlay(); postIntent('nav.open', { route: target.dataset.route }); }
  else if (action === 'theme') postIntent('appearance.set', { theme: target.dataset.theme });
  else if (action === 'tone') postIntent('appearance.set', { tone: target.dataset.tone });
  else if (action === 'toggle-delight') postIntent('ui.delight.set', { enabled: target.dataset.enabled === 'true' });
  else if (action === 'toggle-reasoning') postIntent('chat.reasoning.visibility.set', { show: target.dataset.show === 'true' });
  else if (action === 'toggle-memory') postIntent('memory.global.set', { enabled: target.dataset.enabled === 'true' });
  else if (action === 'open-session') { closeDrawerIfOverlay(); postIntent('chat.session.open', { localSessionId: sessionId }); }
  else if (action === 'rename-session') {
    const title = target.dataset.sessionTitle || currentView()?.activeSession?.title || '';
    if (!sessionId) return;
    openTextDialog({ title: '重命名会话', value: title, onConfirm: (nextTitle) => postIntent('chat.session.rename', { localSessionId: sessionId, title: nextTitle }) });
  }
  else if (action === 'delete-session') {
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
    const localSeq = seqOf(target);
    if (sessionId && localSeq) postIntent('chat.message.edit', { localSessionId: sessionId, localSeq });
  }
  else if (action === 'rollback-message') {
    const localSeq = seqOf(target);
    if (sessionId && localSeq) {
      openConfirmDialog({
        title: '回退当前会话',
        message: '这条消息及其后的本地消息将从当前会话撤销，不会自动创建分支。若要保留两条路线，请先使用“分支”。',
        confirmText: '回退', danger: true,
        onConfirm: () => postIntent('chat.message.rollback', { localSessionId: sessionId, localSeq }),
      });
    }
  }
  else if (action === 'branch-message') {
    const localSeq = seqOf(target);
    if (sessionId && localSeq) postIntent('branch.create.local', { localSessionId: sessionId, throughSeq: localSeq });
  }
  else if (action === 'new-chat') { closeDrawerIfOverlay(); newChat(currentView()); }
  else if (action === 'new-chat-model') {
    const modelCard = target.closest('.model-card');
    const contextMode = modelCard?.querySelector('select[data-context-mode-select]')?.value || currentView()?.ui?.defaultContextMode || 'standard';
    const webMode = modelCard?.querySelector('select[data-web-mode-select]')?.value || currentView()?.ui?.defaultWebMode || 'auto';
    postIntent('chat.session.create', { providerId: target.dataset.providerId, modelProfileId: target.dataset.modelProfileId, contextMode, webMode });
  }
  else if (action === 'configure-provider') postIntent('provider.configure.open', target.dataset.providerId ? { providerId: target.dataset.providerId } : {});
  else if (action === 'configure-model') postIntent('model.configure.open', { providerId: target.dataset.providerId, ...(target.dataset.modelProfileId ? { modelProfileId: target.dataset.modelProfileId } : {}) });
  else if (action === 'pick-attachment') postIntent('chat.attachment.pick', { localSessionId: sessionId });
  else if (action === 'remove-attachment') postIntent('chat.attachment.remove', { localSessionId: sessionId, mediaId: target.dataset.mediaId });
  else if (action === 'verification-feedback') {
    const localSeq = seqOf(target);
    if (sessionId && localSeq && target.dataset.label) postIntent('verification.feedback', { localSessionId: sessionId, localSeq, label: target.dataset.label });
  }
  else if (action === 'branch-local') postIntent('branch.create.local', { localSessionId: sessionId });
  else if (action === 'interrupt') postIntent('chat.session.interrupt', { localSessionId: sessionId });
  else if (action === 'open-source' && target.dataset.url) postIntent('external.open', { url: target.dataset.url });
  else if (action === 'jump-latest') {
    const node = document.querySelector('.timeline-scroll');
    if (node) {
      setTimelineFollowLatest(node.dataset.sessionId || '', true);
      node.scrollTop = node.scrollHeight;
      noteTimelineScroll(node);
      target.classList.add('is-hidden');
    }
  }
  else if (action === 'copy-message') copyText(messageText(seqOf(target)), target.closest('.sheet') ? null : target, '已复制回答');
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
  } else if (event.target?.dataset?.standardSubagentMode === '1') {
    postIntent('subagent.standard.default.set', { mode: event.target.value });
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
  resizeTextarea(input);
}

document.addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.target;
  if (form.dataset.form !== 'composer') return;
  const input = form.elements.namedItem('message');
  const text = String(input?.value || '');
  const sessionId = form.dataset.sessionId;
  const hasDraftMedia = Boolean(currentView()?.activeSession?.draftMedia?.length);
  if ((!text.trim() && !hasDraftMedia) || !sessionId) return;
  clearTimeout(draftTimer);
  draftTimer = 0;
  advanceDraftGeneration(sessionId);
  const activeSession = getState().view?.activeSession?.localSessionId === sessionId ? getState().view.activeSession : null;
  if (activeSession && !activeSession.canSend) {
    postIntent('chat.session.saveDraft', { localSessionId: sessionId, text });
    setNotice({ level: 'info', text: '当前回合仍在进行，内容已保留为下一条草稿' });
    return;
  }
  const reasoningEffort = activeSession?.reasoningEffort;
  const webMode = activeSession?.webMode;
  const requestId = postIntent('chat.session.send', { localSessionId: sessionId, text,
    ...(reasoningEffort ? { reasoningEffort } : {}), ...(webMode ? { webMode } : {}) });
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

postIntent('app.ready', { uiVersion: '0.0.6-beta' });
