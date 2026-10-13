// Transient surfaces that live outside #app so full renders never tear them down: the session drawer,
// bottom sheets (action lists, pickers, per-turn settings), full-screen panels (configuration editors) and
// app-owned dialogs. Android Back closes the top one.
import { requestIntent } from './bridge.js';
import { button, h, icon, iconButton, syncSegment } from './dom.js';
import { renderMarkdownInto } from './markdown.js';
import { fish } from './mascot.js';
import { getState } from './state.js';
import { EASE_STANDARD, flip, morphInto, reducedMotion } from './morph.js';
import * as vm from './view-model.js';

let drawerOpen = false;
let drawerSignature = '';
let sheet = null;

// ---------- Session list (drawer and home page share it) ----------

export function sessionItem(session, activeId) {
  const running = session.state === 'running';
  const active = session.localSessionId === activeId;
  const meta = [];
  if (running) meta.push(fish('write', 'sm'), h('span', { text: '生成中' }));
  else if (session.state === 'failed') meta.push(h('span', { class: 'meta-warn', text: '失败' }));
  if (session.origin) meta.push(h('span', { class: 'meta-branch', title: `分支自 ${session.origin.sourceTitle || ''}` }, icon('branch', 'ico ico-xs'), '分支'));
  meta.push(h('span', { class: 'truncate', text: session.modelName || session.model || '' }));
  meta.push(h('span', { class: `mode-tag ${session.contextMode || 'standard'}`, title: `上下文模式：${vm.contextLabel(session.contextMode)}`, text: vm.contextLabel(session.contextMode) }));
  return h('div', { class: `session-item${active ? ' active' : ''}${running ? ' running' : ''}`, dataset: { key: session.localSessionId, exit: 'collapse' } },
    h('button', { type: 'button', class: 'session-open', dataset: { action: 'open-session', sessionId: session.localSessionId } },
      h('b', { class: 'session-title', text: session.title || '新会话' }),
      h('small', { class: 'session-meta' }, meta)),
    iconButton('more', `${session.title || '会话'} 的操作`, 'session-menu', { sessionId: session.localSessionId, sessionTitle: session.title || '', running: running ? '1' : '' }, 'icon-btn session-more'));
}

export function sessionList(view) {
  const activeId = view.activeLocalSessionId || '';
  return vm.sessionGroups(view.sessions || []).map(([label, items]) =>
    h('section', { class: 'session-group', dataset: { key: `group:${label}` } }, h('h3', { class: 'group-label', text: label }), items.map((s) => sessionItem(s, activeId))));
}

function todayStrip(view) {
  const today = vm.usageToday(view);
  if (!today) return null;
  return h('div', { class: 'today', title: '今天所有会话的用量' },
    '今日 ', h('b', { dataset: { swap: '' }, text: String(today.turns) }), ' 轮 · ', h('b', { dataset: { swap: '' }, text: vm.compact(today.tokens) }), ' token',
    today.cacheRate === null ? null : [' · 缓存 ', h('b', { dataset: { swap: '' }, text: `${Math.round(today.cacheRate * 100)}%` })]);
}

export function renderDrawer(view) {
  const root = document.getElementById('drawer-root');
  if (!root || !view) return;
  const signature = JSON.stringify([view.sessions, view.activeLocalSessionId, view.usageToday, view.route, view.appearance?.delight]);
  if (signature === drawerSignature && root.firstChild) return;
  drawerSignature = signature;
  flip(drawerOpen ? root.querySelector('.drawer-list') : null, () => morphInto(root,
    h('div', { class: 'drawer-scrim', dataset: { action: 'drawer-close' } }),
    h('nav', { class: 'drawer', 'aria-label': '会话' },
      h('header', { class: 'drawer-head' }, fish('still', 'sm'), h('strong', { class: 'grow', text: 'Moyu Chat β' }), iconButton('close', '关闭', 'drawer-close', {}, 'icon-btn drawer-close')),
      h('button', { type: 'button', class: 'drawer-new', dataset: { action: 'new-chat' } }, icon('plus'), '新会话'),
      todayStrip(view),
      h('div', { class: 'drawer-list' }, (view.sessions || []).length ? sessionList(view) : h('p', { class: 'drawer-empty', text: '还没有会话' })),
      h('footer', { class: 'drawer-foot' },
        h('button', { type: 'button', class: `drawer-link${view.route === 'providers' ? ' active' : ''}`, dataset: { action: 'nav', route: 'providers' } }, icon('sliders'), '账号与模型'),
        h('button', { type: 'button', class: `drawer-link${view.route === 'stats' ? ' active' : ''}`, dataset: { action: 'nav', route: 'stats' } }, icon('chart'), '统计'),
        h('button', { type: 'button', class: `drawer-link${view.route === 'settings' ? ' active' : ''}`, dataset: { action: 'nav', route: 'settings' } }, icon('gear'), '设置')))));
  root.classList.toggle('open', drawerOpen);
}

export function isDrawerOpen() { return drawerOpen; }

export function setDrawer(open) {
  drawerOpen = Boolean(open);
  document.getElementById('drawer-root')?.classList.toggle('open', drawerOpen);
  document.documentElement.dataset.drawer = drawerOpen ? 'open' : 'closed';
}

// ---------- Bottom sheets ----------

/** items: [{ label, icon, action, data, danger, disabled, hint }]. Every item closes the sheet after dispatch. */
export function openActions(title, items) { sheet = { kind: 'actions', title, items }; paintSheet(null); }
export function openTurnSettings(sessionId) { sheet = { kind: 'turn', sessionId }; paintSheet(getState().view); }

let choiceHandler = null;
/** App-owned picker: options [{ value, label, hint }]. onPick runs at once; the sheet closes after the selection shows. */
export function openChoice({ title, note = '', options, value, onPick }) {
  choiceHandler = onPick;
  sheet = { kind: 'choice', title, note, options, value };
  paintSheet(null);
}
export function pickChoice(value) {
  if (sheet?.kind !== 'choice') return;
  const handler = choiceHandler;
  choiceHandler = null;
  sheet = { ...sheet, value };
  paintSheet(null);
  handler?.(value);
  setTimeout(() => { if (sheet?.kind === 'choice') closeSheet(); }, reducedMotion() ? 0 : 160);
}

function choiceSheet(state) {
  return [h('h2', { class: 'sheet-title', text: state.title }),
    state.note ? h('p', { class: 'sheet-note', text: state.note }) : null,
    h('div', { class: 'choice-list', role: 'radiogroup', 'aria-label': state.title }, state.options.map((option) => {
      const selected = option.value === state.value;
      return h('button', { type: 'button', role: 'radio', 'aria-checked': selected ? 'true' : 'false', class: `choice-row${selected ? ' selected' : ''}`, dataset: { action: 'choice-pick', value: option.value } },
        h('span', { class: 'radio' }), h('span', { class: 'grow' }, option.label, option.hint ? h('small', { class: 'choice-hint', text: option.hint }) : null));
    }))];
}

/** Start a conversation with one Model Profile, choosing its context and Web modes first. */
export function openNewChat({ providerId, modelProfileId, title, contextMode, webMode }) {
  sheet = { kind: 'newchat', providerId, modelProfileId, title, contextMode, webMode };
  paintSheet(null);
}
export function setNewChatChoice(choice) {
  if (sheet?.kind !== 'newchat') return;
  sheet = { ...sheet, ...choice };
  paintSheet(null);
}
export function newChatChoice() { return sheet?.kind === 'newchat' ? { ...sheet } : null; }

function newChatSheet(state) {
  return [h('h2', { class: 'sheet-title', text: `用 ${state.title} 开新会话` }),
    segmented('上下文模式', ['minimal', 'standard', 'smart'], state.contextMode, (value, on) => h('button', { type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false', class: on ? 'on' : '',
      dataset: { action: 'newchat-mode', contextMode: value }, text: vm.contextLabel(value) })),
    segmented('联网', ['off', 'auto', 'on'], state.webMode, (value, on) => h('button', { type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false', class: on ? 'on' : '',
      dataset: { action: 'newchat-web', webMode: value }, text: vm.WEB_LABELS[value] })),
    h('div', { class: 'sheet-foot' }, button('开始', 'newchat-confirm', {}, 'btn primary'))];
}

/** Re-answer one user message in a new branch, optionally with another model and/or context mode. */
export function openRerun(sessionId, localSeq) {
  const session = getState().view?.activeSession;
  if (!session || session.localSessionId !== sessionId) return;
  sheet = { kind: 'rerun', sessionId, localSeq: Number(localSeq), modelProfileId: session.modelProfileId, contextMode: session.contextMode || 'standard' };
  paintSheet(getState().view);
}

export function setRerunChoice(choice) {
  if (sheet?.kind !== 'rerun') return;
  sheet = { ...sheet, ...choice };
  paintSheet(getState().view);
}

/** The pending re-answer choice, or null when the sheet is not open. */
export function rerunChoice() { return sheet?.kind === 'rerun' ? { ...sheet } : null; }

function rerunSheet(view, state) {
  const choices = vm.modelChoices(view);
  return [h('h2', { class: 'sheet-title', text: '换个方式重答' }),
    h('p', { class: 'sheet-note', text: '在新分支里重新发送这条消息，只带它之前的对话；原会话不变，两次回答可以放在一起对比。' }),
    h('div', { class: 'field' }, h('span', { class: 'field-label', text: '模型' }),
      h('div', { class: 'choice-list', role: 'radiogroup', 'aria-label': '模型' }, choices.map(({ provider, model }) => {
        const selected = model.modelProfileId === state.modelProfileId;
        return h('button', { type: 'button', role: 'radio', 'aria-checked': selected ? 'true' : 'false', class: `choice-row${selected ? ' selected' : ''}`,
          dataset: { action: 'rerun-model', modelProfileId: model.modelProfileId } },
        h('span', { class: 'radio' }), h('span', { class: 'grow truncate', text: model.displayName || model.modelId }), h('small', { class: 'truncate', text: provider.displayName }));
      }))),
    segmented('上下文模式', ['minimal', 'standard', 'smart'], state.contextMode, (value, on) => h('button', { type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false', class: on ? 'on' : '',
      dataset: { action: 'rerun-mode', contextMode: value }, text: vm.contextLabel(value) })),
    h('div', { class: 'sheet-foot' }, button('重答', 'rerun-confirm', {}, 'btn primary'))];
}

/** Every answer to one question side by side (stacked on phones). */
export function openAnswers(sessionId, turnId) {
  const opened = { kind: 'answers', state: 'loading' };
  sheet = opened;
  paintSheet(null);
  requestIntent('turn.answers.get', { localSessionId: sessionId, turnId })
    .then((data) => { if (sheet === opened) { sheet = { ...opened, state: 'ready', data }; paintSheet(null); } })
    .catch((error) => { if (sheet === opened) { sheet = { ...opened, state: 'error', error: String(error?.message || error || '') }; paintSheet(null); } });
}

function answerText(text) {
  const box = h('div', { class: 'answer-text markdown-body' });
  if (text) renderMarkdownInto(box, text); else box.textContent = '（没有回答）';
  return box;
}

function answersSheet(state) {
  const head = h('h2', { class: 'sheet-title', text: '对比回答' });
  if (state.state === 'loading') return [head, h('p', { class: 'sheet-note', text: '读取中…' })];
  if (state.state === 'error') return [head, h('p', { class: 'sheet-note', text: `未能读取：${state.error || '没有记录'}` })];
  const view = vm.answersView(state.data);
  return [head,
    view.question ? h('p', { class: 'answers-question', text: view.question }) : null,
    h('div', { class: 'answers' }, view.items.map((item) => h('article', { class: `answer-card${item.failed ? ' failed' : ''}` },
      h('header', { class: 'answer-head' }, h('strong', { text: item.title }), h('small', { class: 'grow truncate', text: item.subtitle }),
        item.rating ? h('span', { class: `rate-mark ${item.rating > 0 ? 'up' : 'down'}`, title: item.rating > 0 ? '好答案' : '不好' }, icon(item.rating > 0 ? 'thumbUp' : 'thumbDown', 'ico ico-xs')) : null),
      item.metrics || item.verdict ? h('p', { class: 'answer-metrics', text: [item.metrics, item.verdict].filter(Boolean).join(' · ') }) : null,
      answerText(item.text),
      h('div', { class: 'answer-foot' }, button('打开会话', 'open-session', { sessionId: item.sessionId, closeSheet: '1' }, 'btn small ghost')))))];
}

/** Protocol inspector: the redacted request / response text of one recorded model call. */
export function openPayload(sessionId, turnId, callIndex, title) {
  const opened = { kind: 'payload', title: title || '协议报文', state: 'loading', part: 0 };
  sheet = opened;
  paintSheet(null);
  requestIntent('turn.payload.get', { localSessionId: sessionId, turnId, callIndex: Number(callIndex) })
    .then((data) => { if (sheet === opened) { sheet = { ...opened, state: 'ready', data }; paintSheet(null); } })
    .catch((error) => { if (sheet === opened) { sheet = { ...opened, state: 'error', error: String(error?.message || error || '') }; paintSheet(null); } });
}

export function selectPayloadPart(index) {
  if (sheet?.kind !== 'payload') return;
  sheet = { ...sheet, part: Math.max(0, Number(index) || 0) };
  paintSheet(null);
}

/** Text of the payload part on screen, for copying. */
export function payloadText() {
  if (sheet?.kind !== 'payload' || sheet.state !== 'ready') return '';
  const part = vm.payloadParts(sheet.data)[sheet.part];
  return part ? [part.meta, part.text].filter(Boolean).join('\n\n') : '';
}

function payloadSheet(state) {
  const head = h('h2', { class: 'sheet-title', text: state.title });
  if (state.state === 'loading') return [head, h('p', { class: 'sheet-note', text: '读取中…' })];
  if (state.state === 'error') return [head, h('p', { class: 'sheet-note', text: `未能读取：${state.error || '没有记录'}` })];
  const parts = vm.payloadParts(state.data);
  if (!parts.length) return [head, h('p', { class: 'sheet-note', text: '这次调用没有记录报文。' })];
  const index = Math.min(state.part, parts.length - 1);
  const current = parts[index];
  return [head,
    syncSegment(h('div', { class: 'segmented', role: 'tablist', 'aria-label': '报文' }, parts.map((part, i) => h('button', {
      type: 'button', role: 'tab', 'aria-selected': i === index ? 'true' : 'false', class: i === index ? 'on' : '', dataset: { action: 'payload-part', part: i }, text: part.label })))),
    current.meta ? h('p', { class: 'sheet-note wire-meta', text: current.meta }) : null,
    h('pre', { class: 'wire' }, h('code', { text: current.text || '（空）' })),
    h('div', { class: 'wire-foot' }, h('small', { class: 'grow', text: '不含请求头和 API Key；图片等长串只保留长度。' }), button('复制', 'payload-copy', {}, 'btn small'))];
}

let sheetCloseTimer = 0;
export function closeSheet() {
  sheet = null;
  const root = document.getElementById('sheet-root');
  if (!root || !root.classList.contains('open')) return;
  root.classList.remove('open');
  delete root.dataset.kind;
  choiceHandler = null;
  if (!root.firstChild) return;
  root.classList.add('closing');
  clearTimeout(sheetCloseTimer);
  sheetCloseTimer = setTimeout(() => { if (!sheet) { root.classList.remove('closing'); root.replaceChildren(); } }, reducedMotion() ? 140 : 220);
}

/** Re-render a stateful sheet (turn settings) after the native view changed. */
export function refreshSheet(view) { if (sheet?.kind === 'turn') paintSheet(view); }

function paintSheet(view) {
  const root = document.getElementById('sheet-root');
  if (!root || !sheet) return;
  let body;
  if (sheet.kind === 'actions') {
    body = [h('h2', { class: 'sheet-title', text: sheet.title || '' }),
      h('div', { class: 'sheet-actions' }, sheet.items.filter(Boolean).map((item) => h('button', {
        type: 'button', class: `sheet-item${item.danger ? ' danger' : ''}`, disabled: item.disabled, dataset: { action: item.action, closeSheet: '1', ...(item.data || {}) },
      }, item.icon ? icon(item.icon) : null, h('span', { class: 'grow' }, item.label, item.hint ? h('small', { text: item.hint }) : null))))];
  } else if (sheet.kind === 'payload') {
    body = payloadSheet(sheet);
  } else if (sheet.kind === 'answers') {
    body = answersSheet(sheet);
  } else if (sheet.kind === 'rerun') {
    body = rerunSheet(view || getState().view, sheet);
  } else if (sheet.kind === 'choice') {
    body = choiceSheet(sheet);
  } else if (sheet.kind === 'newchat') {
    body = newChatSheet(sheet);
  } else {
    const session = view?.activeSession;
    if (!session || session.localSessionId !== sheet.sessionId) { closeSheet(); return; }
    body = turnSettings(view, session);
  }
  const parts = [h('div', { class: 'sheet-scrim', dataset: { action: 'sheet-close' } }),
    h('section', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': sheet.kind === 'turn' ? '本轮设置' : (sheet.title || '操作') }, h('div', { class: 'sheet-grip' }), body)];
  clearTimeout(sheetCloseTimer);
  if (root.classList.contains('open') && root.dataset.kind === sheet.kind) {
    // Same sheet, new content (loaded data, a new selection): morph in place and ease its height.
    const panel = root.querySelector('.sheet');
    const before = panel?.offsetHeight || 0;
    morphInto(root, parts);
    const after = panel?.offsetHeight || 0;
    if (panel?.animate && !reducedMotion() && Math.abs(after - before) > 8) panel.animate([{ height: `${before}px` }, { height: `${after}px` }], { duration: 260, easing: EASE_STANDARD });
  } else { root.classList.remove('closing'); root.replaceChildren(...parts); }
  root.dataset.kind = sheet.kind;
  root.classList.add('open');
}

function segmented(label, values, current, render) {
  return h('div', { class: 'field' }, h('span', { class: 'field-label', text: label }),
    syncSegment(h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': label }, values.map((value) => render(value, value === current)))));
}

function turnSettings(view, session) {
  const locked = !session.canConfigureTurn;
  const choices = vm.modelChoices(view);
  const levels = vm.reasoningChoices(session);
  const effort = session.reasoningEffort || 'default';
  const web = ['off', 'auto', 'on'].includes(session.webMode) ? session.webMode : 'auto';
  const backend = String(session.effectiveWebBackend || '').trim();
  return [
    h('h2', { class: 'sheet-title', text: '本轮设置' }),
    locked ? h('p', { class: 'sheet-note', text: '当前回合进行中，结束后可以修改。' }) : null,
    h('div', { class: 'field' }, h('span', { class: 'field-label', text: '模型' }),
      h('div', { class: 'choice-list', role: 'radiogroup', 'aria-label': '模型' }, choices.map(({ provider, model }) => {
        const selected = model.modelProfileId === session.modelProfileId;
        return h('button', { type: 'button', role: 'radio', 'aria-checked': selected ? 'true' : 'false', class: `choice-row${selected ? ' selected' : ''}`, disabled: locked,
          dataset: { action: 'session-model-set', sessionId: session.localSessionId, modelProfileId: model.modelProfileId } },
        h('span', { class: 'radio' }), h('span', { class: 'grow truncate', text: model.displayName || model.modelId }), h('small', { class: 'truncate', text: provider.displayName }));
      }))),
    levels.length
      ? segmented('推理深度', levels, effort, (value, on) => h('button', { type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false', class: on ? 'on' : '', disabled: locked,
        dataset: { action: 'session-reasoning-set', sessionId: session.localSessionId, reasoningEffort: value }, text: vm.effortLabel(value) }))
      : h('div', { class: 'field' }, h('span', { class: 'field-label', text: '推理深度' }), h('p', { class: 'sheet-note', text: '这个部署没有声明推理档位。' })),
    segmented('联网', ['off', 'auto', 'on'], web, (value, on) => h('button', { type: 'button', role: 'radio', 'aria-checked': on ? 'true' : 'false', class: on ? 'on' : '', disabled: locked,
      dataset: { action: 'session-web-set', sessionId: session.localSessionId, webMode: value }, text: vm.WEB_LABELS[value] })),
    backend ? h('p', { class: 'sheet-note', text: `联网后端：${backend}` }) : null,
  ];
}

// ---------- Full-screen panels (configuration editors) ----------

let panel = null;
let panelTimer = 0;
export function panelOpen() { return Boolean(panel); }
export function openPanel(kind, content, onClose = null) {
  const root = document.getElementById('panel-root');
  if (!root) return null;
  clearTimeout(panelTimer);
  panel = { kind, onClose };
  const node = h('section', { class: 'panel', role: 'dialog', 'aria-modal': 'true', dataset: { panel: kind } }, content);
  root.classList.remove('closing');
  root.replaceChildren(node);
  root.classList.add('open');
  document.documentElement.dataset.panel = 'open';
  return node;
}
export function closePanel() {
  const root = document.getElementById('panel-root');
  if (!panel || !root) return;
  const { onClose } = panel;
  panel = null;
  document.documentElement.dataset.panel = 'closed';
  root.classList.remove('open');
  root.classList.add('closing');
  clearTimeout(panelTimer);
  panelTimer = setTimeout(() => { if (!panel) { root.classList.remove('closing'); root.replaceChildren(); } }, reducedMotion() ? 140 : 260);
  onClose?.();
}

// ---------- Dialogs (app-owned; never window.confirm) ----------

function closeDialog(immediate = false) {
  const node = document.querySelector('.modal-backdrop[data-moyu-modal]');
  if (!node) return;
  node.removeAttribute('data-moyu-modal');
  if (immediate) { node.remove(); return; }
  node.classList.add('closing');
  setTimeout(() => node.remove(), 180);
}

export function openConfirmDialog({ title, message, confirmText = '确认', danger = false, onConfirm }) {
  closeDialog(true);
  const cancel = button('取消', 'modal-cancel', {}, 'btn');
  const confirm = button(confirmText, 'modal-confirm', {}, `btn ${danger ? 'danger' : 'primary'}`);
  const overlay = h('div', { class: 'modal-backdrop', dataset: { moyuModal: '1' } },
    h('section', { class: 'modal-card', role: 'dialog', 'aria-modal': 'true', 'aria-label': title || '确认操作' },
      h('h2', { text: title || '确认操作' }), h('p', { text: message || '' }), h('div', { class: 'modal-actions' }, cancel, confirm)));
  cancel.addEventListener('click', () => closeDialog());
  confirm.addEventListener('click', () => { closeDialog(); onConfirm?.(); });
  overlay.addEventListener('click', (event) => { if (event.target === overlay) closeDialog(); });
  document.body.append(overlay);
  confirm.focus();
}

export function openTextDialog({ title, value = '', confirmText = '保存', note = '', inputmode = '', onConfirm }) {
  closeDialog(true);
  const input = h('input', { class: 'modal-input', type: 'text', value, maxlength: '400', inputmode: inputmode || undefined, autocomplete: 'off', autocapitalize: 'off', spellcheck: 'false', 'aria-label': title || '输入内容' });
  const cancel = button('取消', 'modal-cancel', {}, 'btn');
  const confirm = button(confirmText, 'modal-confirm', {}, 'btn primary');
  const overlay = h('div', { class: 'modal-backdrop', dataset: { moyuModal: '1' } },
    h('section', { class: 'modal-card', role: 'dialog', 'aria-modal': 'true', 'aria-label': title || '编辑' },
      h('h2', { text: title || '编辑' }), note ? h('p', { class: 'modal-note', text: note }) : null, input, h('div', { class: 'modal-actions' }, cancel, confirm)));
  const submit = () => {
    const next = String(input.value || '').trim();
    if (!next) { input.focus(); return; }
    closeDialog(); onConfirm?.(next);
  };
  cancel.addEventListener('click', () => closeDialog());
  confirm.addEventListener('click', submit);
  input.addEventListener('keydown', (event) => { if (event.key === 'Enter' && !event.isComposing) { event.preventDefault(); submit(); } });
  overlay.addEventListener('click', (event) => { if (event.target === overlay) closeDialog(); });
  document.body.append(overlay);
  input.focus(); input.select();
}

/** Android Back: close the top-most transient surface. Returns true when something was closed. */
export function handleBack() {
  if (document.querySelector('.modal-backdrop[data-moyu-modal]')) { closeDialog(); return true; }
  if (sheet) { closeSheet(); return true; }
  if (panel) { closePanel(); return true; }
  const nav = document.querySelector('.turn-nav.open');
  if (nav) { nav.classList.remove('open'); nav.querySelector('.turn-nav-toggle')?.setAttribute('aria-expanded', 'false'); return true; }
  if (drawerOpen && !window.matchMedia?.('(min-width: 960px)')?.matches) { setDrawer(false); return true; }
  return false;
}

