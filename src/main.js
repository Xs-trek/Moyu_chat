import { bindHost, postIntent, requestIntent } from './bridge.js';
import { flash, randomQuote } from './mascot.js';
import { closeImageViewer, closeSheet, handleBack, newChatChoice, openActions, openAnswers, openConfirmDialog, openImageViewer, openNewChat, openPayload, openRerun, openTextDialog, openTextSheet, openTurnSettings, payloadText, pickChoice, rerunChoice, selectPayloadPart, setDrawer, setNewChatChoice, setRerunChoice } from './overlays.js';
import { openModelEditor, openProviderEditor } from './editors.js';
import { syncSegment } from './dom.js';
import { reducedMotion } from './morph.js';
import { createRenderGate } from './render-gate.js';
import { forgetStats, patchConversation, patchStreamItem, render, renderNotice, resizeTextarea, setStatsDays, setStatsGroup, syncTurnNavigator, toggleTurnDetails } from './render.js';
import { noteTimelineScroll, setTimelineFollowLatest } from './scroll-anchor.js';
import { getState, setNotice, setOptimisticReasoningEffort, setOptimisticWebMode, setView, subscribe } from './state.js';
import { forgetRemote, onRemoteChange, refreshRemote, traceKey } from './remote.js';
import * as vm from './view-model.js';
import './drawer-gesture.js';

const renderGate = createRenderGate(render);
// A requested turn trace arrived: refresh the expanded footer in place.
onRemoteChange(() => { if (!patchConversation()) renderGate.request(); });
subscribe((_state, change) => {
  if (change?.scope !== 'stream' && change?.scope !== 'toast') verifyNewKeys(currentView());
  if (change?.scope === 'toast') renderNotice();
  else if (change?.scope === 'stream' && change.item && patchStreamItem(change)) return;
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

// Settings controls answer the tap at once; the native view that follows confirms (or corrects) them.
function optimisticSwitch(target) {
  const on = !target.classList.contains('on');
  target.classList.toggle('on', on);
  target.setAttribute('aria-checked', on ? 'true' : 'false');
  for (const key of ['enabled', 'show']) if (key in target.dataset) target.dataset[key] = on ? 'false' : 'true';
}
function optimisticSegment(target) {
  for (const item of target.parentElement?.children || []) {
    const on = item === target;
    item.classList.toggle('on', on);
    item.setAttribute('aria-checked', on ? 'true' : 'false');
  }
  if (target.parentElement?.classList.contains('segmented')) syncSegment(target.parentElement);
}
function optimisticChoice(target) {
  for (const item of target.parentElement?.children || []) {
    const on = item === target;
    item.classList.toggle('selected', on);
    item.setAttribute('aria-checked', on ? 'true' : 'false');
  }
}

// Touch ripple on primary actions; the press scale itself is CSS (:active).
document.addEventListener('pointerdown', (event) => {
  const host = event.target.closest?.('.btn.primary, .composer-send, .drawer-new');
  if (!host || host.disabled || reducedMotion()) return;
  const rect = host.getBoundingClientRect();
  const size = Math.max(rect.width, rect.height) * 2.2;
  const dot = document.createElement('span');
  dot.className = 'ripple';
  dot.style.width = dot.style.height = `${size}px`;
  dot.style.left = `${event.clientX - rect.left - size / 2}px`;
  dot.style.top = `${event.clientY - rect.top - size / 2}px`;
  host.append(dot);
  setTimeout(() => dot.remove(), 600);
}, true);

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

/** Re-answer: create the branch natively, then send the same message there. */
function rerun() {
  const choice = rerunChoice();
  if (!choice) return;
  closeSheet();
  requestIntent('turn.rerun', { localSessionId: choice.sessionId, localSeq: choice.localSeq, modelProfileId: choice.modelProfileId, contextMode: choice.contextMode })
    .then((data) => { if (data?.localSessionId) postIntent('chat.session.send', { localSessionId: data.localSessionId, text: String(data.text || '') }); })
    .catch((error) => setNotice({ level: 'error', text: `重答失败：${error?.message || error}` }));
}

function messageItem(seq) {
  return currentView()?.activeSession?.messages?.find((entry) => Number(entry.localSeq) === seq && entry.kind === 'message') || null;
}

function messageMenu(target) {
  const sessionId = target.dataset.sessionId;
  const localSeq = String(seqOf(target));
  const user = target.dataset.role === 'user';
  const item = messageItem(seqOf(target));
  const turnId = !user && item?.turnId && !item.inherited ? item.turnId : '';
  const rating = Number(item?.rating) || 0;
  openActions(user ? '这条提问' : '这条回答', [
    user ? { label: '编辑后重发', icon: 'edit', action: 'edit-message', data: { sessionId, localSeq } } : null,
    user && !item?.inherited ? { label: '换个模型或模式重答', icon: 'retry', action: 'rerun-open', data: { sessionId, localSeq }, hint: '在新分支里重新发送，原会话不变' } : null,
    turnId ? { label: rating > 0 ? '取消“好答案”' : '好答案', icon: 'thumbUp', action: 'rate-turn', data: { sessionId, turnId, rating: rating > 0 ? '0' : '1' } } : null,
    turnId ? { label: rating < 0 ? '取消“不好”' : '不好', icon: 'thumbDown', action: 'rate-turn', data: { sessionId, turnId, rating: rating < 0 ? '0' : '-1' } } : null,
    turnId && Number(item?.answers) > 1 ? { label: `对比 ${item.answers} 次回答`, icon: 'compare', action: 'open-answers', data: { sessionId, turnId } } : null,
    { label: '复制', icon: 'copy', action: 'copy-message', data: { sessionId, localSeq } },
    { label: '从这里分支', icon: 'branch', action: 'branch-message', data: { sessionId, localSeq }, hint: '保留原会话，另开一条路线' },
    { label: '回退到这里之前', icon: 'undo', action: 'rollback-message', data: { sessionId, localSeq }, danger: true, hint: '撤销这条及之后的消息' },
  ]);
}

function newChat(view) {
  const usable = (p) => (p?.models || []).filter((m) => m.enabled !== false);
  const configured = (p) => p?.hasCredential && usable(p).length;
  const active = view?.activeSession;
  const activeProvider = view?.providers?.find((p) => p.providerId === active?.providerId);
  const activeModel = usable(activeProvider).find((m) => m.modelProfileId === active?.modelProfileId);
  const provider = configured(activeProvider) && activeModel ? activeProvider : view?.providers?.find((p) => p.configured) || view?.providers?.find(configured);
  const model = provider === activeProvider && activeModel ? activeModel : usable(provider)[0];
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
  else if (action === 'toggle-turn-details') {
    const key = target.dataset.itemKey;
    toggleTurnDetails(key);
    if (!patchConversation()) renderGate.request();
    // Details opened at the bottom of the timeline would unfold out of sight: bring them into view once open.
    const more = [...document.querySelectorAll('.timeline [data-item-key]')].find((node) => node.dataset.itemKey === key)?.querySelector('.turn-more:not([data-leaving])');
    if (more) {
      const reveal = () => more.isConnected && more.scrollIntoView({ block: 'nearest', behavior: reducedMotion() ? 'auto' : 'smooth' });
      if (reducedMotion()) reveal(); else more.addEventListener('animationend', reveal, { once: true });
    }
  }
  else if (action === 'open-payload') openPayload(target.dataset.sessionId, target.dataset.turnId, target.dataset.callIndex, target.dataset.title);
  else if (action === 'payload-part') { optimisticSegment(target); selectPayloadPart(target.dataset.part); }
  else if (action === 'payload-copy') copyText(payloadText(), target, '已复制报文');
  else if (action === 'trace-retry') { forgetRemote(traceKey(target.dataset.turnId)); if (!patchConversation()) renderGate.request(); }
  else if (action === 'rate-turn') postIntent('turn.rate', { localSessionId: sessionId, turnId: target.dataset.turnId, rating: Number(target.dataset.rating) || 0 });
  else if (action === 'open-answers') openAnswers(sessionId, target.dataset.turnId);
  else if (action === 'rerun-open') openRerun(sessionId, target.dataset.localSeq);
  else if (action === 'rerun-model') { optimisticChoice(target); setRerunChoice({ modelProfileId: target.dataset.modelProfileId }); }
  else if (action === 'rerun-mode') { optimisticSegment(target); setRerunChoice({ contextMode: target.dataset.contextMode }); }
  else if (action === 'rerun-confirm') rerun();
  else if (action === 'stats-days') { optimisticSegment(target); setStatsDays(target.dataset.days); renderGate.request(); }
  else if (action === 'stats-group') { setStatsGroup(target.dataset.group); renderGate.request(); }
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
  else if (action === 'session-model-set') { optimisticChoice(target); postIntent('chat.session.model.set', { localSessionId: sessionId, modelProfileId: target.dataset.modelProfileId }); }
  else if (action === 'choice-pick') { optimisticChoice(target); pickChoice(target.dataset.value); }
  else if (action === 'default-context') { optimisticSegment(target); postIntent('context.mode.default.set', { mode: target.dataset.mode }); }
  else if (action === 'default-web') { optimisticSegment(target); postIntent('web.mode.default.set', { mode: target.dataset.mode }); }
  else if (action === 'default-subagent') { optimisticSegment(target); postIntent('subagent.standard.default.set', { mode: target.dataset.mode }); }
  else if (action === 'newchat-mode') { optimisticSegment(target); setNewChatChoice({ contextMode: target.dataset.contextMode }); }
  else if (action === 'newchat-web') { optimisticSegment(target); setNewChatChoice({ webMode: target.dataset.webMode }); }
  else if (action === 'newchat-confirm') {
    const choice = newChatChoice();
    closeSheet();
    if (choice) postIntent('chat.session.create', { providerId: choice.providerId, modelProfileId: choice.modelProfileId, contextMode: choice.contextMode, webMode: choice.webMode });
  }
  else if (action === 'provider-key') postIntent('provider.credential.edit', { providerId: target.dataset.providerId });
  else if (action === 'provider-verify') guardCheck(`verify:${target.dataset.providerId}`, 'provider.key.verify', { providerId: target.dataset.providerId }, (data) => {
    const text = { valid: 'Key 可用', invalid: 'Key 无效', unverified: '这个端点没有模型列表接口，发送一次消息即可确认 Key', error: `检查失败：${data.summary || ''}` }[data.keyStatus] || '已检查';
    return { level: data.keyStatus === 'valid' ? 'info' : data.keyStatus === 'unverified' ? 'info' : 'error', text };
  });
  else if (action === 'provider-balance') guardCheck(`balance:${target.dataset.providerId}`, 'provider.balance.get', { providerId: target.dataset.providerId });
  else if (action === 'model-probe') guardCheck(`probe:${target.dataset.modelProfileId}`, 'model.probe', { modelProfileId: target.dataset.modelProfileId },
    (data) => ({ level: data.ok ? 'info' : 'error', text: data.ok ? `连接正常${data.reply ? `：${data.reply}` : ''}` : `测试失败：${data.summary || data.code || ''}` }));
  else if (action === 'route-host-confirm') {
    const routeId = target.dataset.routeId;
    openConfirmDialog({ title: '确认新的域名', confirmText: '确认发送',
      message: `这个账号的 API Key 将发往 ${target.dataset.host}。只在你信任这个域名时确认；确认前，这条路线不会发出任何请求。`,
      onConfirm: () => postIntent('route.host.confirm', { routeId }) });
  }
  else if (action === 'session-reasoning-set') {
    optimisticSegment(target);
    const reasoningEffort = target.dataset.reasoningEffort;
    // Reflect the user's turn setting immediately. Native persistence remains authoritative, while
    // the subsequent Send also carries this selection so bridge scheduling cannot revert the turn.
    setOptimisticReasoningEffort(sessionId, reasoningEffort);
    postIntent('chat.session.reasoning.set', { localSessionId: sessionId, reasoningEffort });
  }
  else if (action === 'session-web-set') {
    optimisticSegment(target);
    const webMode = target.dataset.webMode;
    setOptimisticWebMode(sessionId, webMode);
    postIntent('chat.session.web.set', { localSessionId: sessionId, webMode });
  }
  else if (action === 'nav') { closeDrawerIfOverlay(); if (target.dataset.route === 'stats') forgetStats(); postIntent('nav.open', { route: target.dataset.route }); }
  else if (action === 'theme') { optimisticSegment(target); postIntent('appearance.set', { theme: target.dataset.theme }); }
  else if (action === 'tone') { optimisticSegment(target); postIntent('appearance.set', { tone: target.dataset.tone }); }
  else if (action === 'toggle-delight') { postIntent('ui.delight.set', { enabled: target.dataset.enabled === 'true' }); optimisticSwitch(target); }
  else if (action === 'toggle-reasoning') { postIntent('chat.reasoning.visibility.set', { show: target.dataset.show === 'true' }); if (target.classList.contains('switch')) optimisticSwitch(target); }
  else if (action === 'toggle-memory') { postIntent('memory.global.set', { enabled: target.dataset.enabled === 'true' }); optimisticSwitch(target); }
  else if (action === 'toggle-wire-capture') { postIntent('inspector.capture.set', { enabled: target.dataset.enabled === 'true' }); optimisticSwitch(target); }
  else if (action === 'toggle-notify') { postIntent('notify.background.set', { enabled: target.dataset.enabled === 'true' }); optimisticSwitch(target); }
  else if (action === 'open-session') {
    document.querySelectorAll('.session-item.active').forEach((item) => item.classList.remove('active'));
    target.closest('.session-item')?.classList.add('active');
    closeDrawerIfOverlay();
    postIntent('chat.session.open', { localSessionId: sessionId });
  }
  else if (action === 'rename-session') {
    const title = target.dataset.sessionTitle || currentView()?.activeSession?.title || '';
    if (!sessionId) return;
    openTextDialog({ title: '重命名会话', value: title, maxLength: vm.SESSION_TITLE_MAX, onConfirm: (nextTitle) => postIntent('chat.session.rename', { localSessionId: sessionId, title: nextTitle }) });
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
    const view = currentView();
    const model = view?.providers?.find((p) => p.providerId === target.dataset.providerId)?.models?.find((m) => m.modelProfileId === target.dataset.modelProfileId);
    openNewChat({ providerId: target.dataset.providerId, modelProfileId: target.dataset.modelProfileId, title: model?.displayName || model?.modelId || '这个模型',
      contextMode: view?.ui?.defaultContextMode || 'standard', webMode: view?.ui?.defaultWebMode || 'auto' });
  }
  else if (action === 'configure-provider') { closeDrawerIfOverlay(); openProviderEditor(target.dataset.providerId || ''); }
  else if (action === 'configure-model') openModelEditor(target.dataset.providerId, target.dataset.modelProfileId || '');
  else if (action === 'pick-attachment') postIntent('chat.attachment.pick', { localSessionId: sessionId });
  else if (action === 'remove-attachment') postIntent('chat.attachment.remove', { localSessionId: sessionId, mediaId: target.dataset.mediaId });
  else if (action === 'verification-feedback') {
    const localSeq = seqOf(target);
    if (sessionId && localSeq && target.dataset.label) postIntent('verification.feedback', { localSessionId: sessionId, localSeq, label: target.dataset.label });
  }
  else if (action === 'branch-local') postIntent('branch.create.local', { localSessionId: sessionId });
  else if (action === 'interrupt') postIntent('chat.session.interrupt', { localSessionId: sessionId });
  else if (action === 'retry-turn') retryTurn(sessionId);
  else if (action === 'apply-profile') postIntent('chat.session.model.set', { localSessionId: sessionId, modelProfileId: target.dataset.modelProfileId });
  else if (action === 'view-image') openImageViewer(target.dataset.src, target.dataset.name);
  else if (action === 'show-pre-repair') {
    const item = (currentView()?.activeSession?.messages || []).find((m) => Number(m.localSeq) === Number(target.dataset.localSeq));
    const text = vm.verificationState(item?.verification)?.preRepairText;
    if (text) openTextSheet({ title: '修正前的回答', note: '智能校验认为这一版有问题，修正后换成了现在显示的回答。', markdown: text });
  }
  else if (action === 'viewer-close') closeImageViewer();
  else if (action === 'open-source' && target.dataset.url) postIntent('external.open', { url: target.dataset.url });
  else if (action === 'jump-latest') {
    const node = document.querySelector('.timeline-scroll');
    if (node) {
      setTimelineFollowLatest(node.dataset.sessionId || '', true);
      const reduceMotion = Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches);
      node.scrollTo({ top: node.scrollHeight, behavior: reduceMotion ? 'auto' : 'smooth' });
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

/** Account checks: the remote cache shows "running" on the page; the outcome comes back as a notice. */
function guardCheck(key, type, payload, notice) {
  refreshRemote(key, type, payload)
    .then((data) => { if (notice) setNotice(notice(data)); })
    .catch((error) => setNotice({ level: 'error', text: String(error?.message || error || '') }));
  renderGate.request();
}

/**
 * Sends one turn. The result of an accepted send arrives when the turn ends; a rejected send fails at once
 * and Native has put the submitted text back as the draft, so the cleared composer shows it again. A send
 * refused because the account reached its budget asks first and goes again with budgetConfirmed.
 */
function sendTurn(sessionId, text, activeSession, budgetConfirmed) {
  const reasoningEffort = activeSession?.reasoningEffort;
  const webMode = activeSession?.webMode;
  return postIntent('chat.session.send', { localSessionId: sessionId, text, ...(budgetConfirmed ? { budgetConfirmed: true } : {}),
    ...(reasoningEffort ? { reasoningEffort } : {}), ...(webMode ? { webMode } : {}) }, {
    resolve() {},
    reject(error) {
      if (error?.data?.draftRestored) restoreComposerText(sessionId, text);
      if (error?.data?.budget && !budgetConfirmed) {
        confirmOverBudget(error.message, () => {
          const form = document.querySelector('form[data-form="composer"]');
          const input = form?.dataset.sessionId === sessionId ? form.elements.namedItem('message') : null;
          if (input && input.value === text) { input.value = ''; resizeComposerTextarea(input); }
          advanceDraftGeneration(sessionId);
          sendTurn(sessionId, text, currentView()?.activeSession, true);
        });
        return;
      }
      setNotice({ level: 'error', text: error?.message || '发送失败' });
    },
  });
}

function confirmOverBudget(message, onConfirm) {
  openConfirmDialog({ title: '已达到预算', message: `${message || '这个账号的估算花费已达预算'}。仍然发送这一轮吗？`, confirmText: '仍然发送', onConfirm });
}

/** A key just saved (status still unknown, never checked) is checked once with the endpoint's free model list. */
const keyChecks = new Set();
function verifyNewKeys(view) {
  for (const provider of view?.providers || []) {
    const fresh = provider.hasCredential && (provider.keyStatus || 'unknown') === 'unknown' && !provider.keyCheckedAt;
    if (!fresh) { keyChecks.delete(provider.providerId); continue; }
    if (keyChecks.has(provider.providerId)) continue;
    keyChecks.add(provider.providerId);
    refreshRemote(`verify:${provider.providerId}`, 'provider.key.verify', { providerId: provider.providerId }).catch(() => {});
  }
}

/** Retry rewinds the failed turn natively; text being typed is saved first so it stays in the composer. */
function retryTurn(sessionId) {
  if (!sessionId) return;
  const form = document.querySelector('form[data-form="composer"]');
  const input = form?.dataset.sessionId === sessionId ? form.elements.namedItem('message') : null;
  if (input) {
    clearTimeout(draftTimer);
    draftTimer = 0;
    advanceDraftGeneration(sessionId);
    postIntent('chat.session.saveDraft', { localSessionId: sessionId, text: String(input.value || '') });
  }
  postIntent('chat.turn.retry', { localSessionId: sessionId }, {
    resolve() {},
    reject(error) {
      if (error?.data?.budget) confirmOverBudget(error.message, () => postIntent('chat.turn.retry', { localSessionId: sessionId, budgetConfirmed: true }));
      else setNotice({ level: 'error', text: error?.message || '重试失败' });
    },
  });
}

/** Put a rejected message back into a composer the user has not typed into since. */
function restoreComposerText(sessionId, text) {
  const form = document.querySelector('form[data-form="composer"]');
  const input = form?.dataset.sessionId === sessionId ? form.elements.namedItem('message') : null;
  if (!input || input.value) return;
  input.value = text;
  input.dataset.projectedDraft = text;
  resizeComposerTextarea(input);
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
  const requestId = sendTurn(sessionId, text, activeSession, false);
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
