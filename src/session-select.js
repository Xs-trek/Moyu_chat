// Multi-select in the session lists (drawer and home page): a long press on a session starts selecting with
// that session ticked; taps then toggle sessions, and the list header turns into a selection bar (cancel ·
// count · select all · delete). Long press is used only for selection, never for a context menu.
import { h, icon } from './dom.js';

const LONG_PRESS_MS = 450;
const SLOP = 10;

let selected = null; // Set of localSessionIds while selecting, null otherwise
const listeners = new Set();

export function selecting() { return selected !== null; }
export function isSelected(id) { return Boolean(selected?.has(id)); }
export function selectedIds() { return selected ? [...selected] : []; }
export function onSelectionChange(callback) { listeners.add(callback); return () => listeners.delete(callback); }
function changed() { for (const callback of listeners) callback(); }

export function startSelection(id) {
  if (!selected) selected = new Set();
  if (id && !selected.has(id)) selected.add(id);
  changed();
}

/** Toggles one session; un-ticking the last one leaves selection mode. */
export function toggleSelected(id) {
  if (!selected || !id) return;
  if (selected.has(id)) selected.delete(id); else selected.add(id);
  if (!selected.size) selected = null;
  changed();
}

export function selectAll(ids) {
  if (!selected) return;
  selected = new Set(ids);
  if (!selected.size) selected = null;
  changed();
}

export function clearSelection() {
  if (!selected) return false;
  selected = null;
  changed();
  return true;
}

/** Drops ids the latest view no longer lists (deleted elsewhere); quiet, because the caller is rendering. */
export function pruneSelection(sessions) {
  if (!selected) return;
  const listed = new Set((sessions || []).map((s) => s.localSessionId));
  for (const id of [...selected]) if (!listed.has(id)) selected.delete(id);
  if (!selected.size) selected = null;
}

/** Sessions that can be ticked: a session with a turn in flight cannot be deleted. */
export function selectable(session) { return session?.state !== 'running'; }

export function selectionBar(sessions) {
  const ids = (sessions || []).filter(selectable).map((s) => s.localSessionId);
  const count = selected?.size || 0;
  const all = ids.length > 0 && ids.every((id) => selected?.has(id));
  return h('div', { class: 'select-bar', role: 'toolbar', 'aria-label': '多选操作', dataset: { key: 'select-bar' } },
    h('button', { type: 'button', class: 'icon-btn', title: '退出多选', 'aria-label': '退出多选', dataset: { action: 'select-cancel' } }, icon('close')),
    h('strong', { class: 'grow select-count', dataset: { swap: '' }, text: `已选 ${count} 项` }),
    h('button', { type: 'button', class: 'btn small ghost', dataset: { action: all ? 'select-none' : 'select-all' }, text: all ? '全不选' : '全选' }),
    h('button', { type: 'button', class: 'btn small danger', dataset: { action: 'select-delete' }, disabled: count === 0, text: '删除' }));
}

// ---------- Long press ----------

let press = null;

function cancelPress() {
  if (press) clearTimeout(press.timer);
  press = null;
}

// The click that ends a long press must not also open (or toggle) the session under the finger.
function swallowNextClick() {
  const swallow = (event) => { event.stopPropagation(); event.preventDefault(); };
  document.addEventListener('click', swallow, { capture: true, once: true });
  setTimeout(() => document.removeEventListener('click', swallow, { capture: true }), 600);
}

function begin(item) {
  const id = item?.dataset.key;
  if (!id || item.classList.contains('unselectable')) return false;
  startSelection(id);
  swallowNextClick();
  return true;
}

document.addEventListener('pointerdown', (event) => {
  cancelPress();
  if (!event.isPrimary || event.button > 0) return;
  const item = event.target.closest?.('.session-item');
  if (!item || event.target.closest('.session-more') || selecting()) return;
  press = { item, x: event.clientX, y: event.clientY, timer: setTimeout(() => { const { item: node } = press || {}; press = null; begin(node); }, LONG_PRESS_MS) };
}, true);
document.addEventListener('pointermove', (event) => {
  if (press && Math.hypot(event.clientX - press.x, event.clientY - press.y) > SLOP) cancelPress();
}, true);
['pointerup', 'pointercancel', 'scroll'].forEach((type) => document.addEventListener(type, cancelPress, true));

// Desktop right-click, and the WebView's own long-press gesture, arrive as contextmenu.
document.addEventListener('contextmenu', (event) => {
  const item = event.target.closest?.('.session-item');
  if (!item) return;
  event.preventDefault();
  cancelPress();
  if (!selecting()) begin(item);
});
