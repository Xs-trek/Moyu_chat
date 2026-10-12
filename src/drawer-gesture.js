// Drag the session drawer with a finger: swipe right over the page to pull it open, swipe left on the
// drawer or its scrim to push it closed. The drawer follows the finger, then settles by position and
// release velocity. Only touch input, only while the drawer overlays the page (< 960px).
//
// Opening is accepted anywhere on the page rather than only at the screen edge: Android gesture
// navigation reserves the left edge for Back. Regions that scroll sideways or hold text input keep
// their own horizontal gestures.
import { isDrawerOpen, setDrawer } from './overlays.js';

const NO_SWIPE = '.md-code, .md-table-wrap, .sources, .draft-media-strip, .wire, .answer-text, .turn-nav-list, .segmented, textarea, input, select';
const SLOP = 10;          // px before a gesture is classified
const LONG_PRESS = 400;   // ms; a slower start is a press / text selection, not a swipe
const FLING = 0.35;       // px/ms

let track = null;

const docked = () => Boolean(window.matchMedia?.('(min-width: 960px)')?.matches);
const root = () => document.getElementById('drawer-root');
const blockedByOverlay = () => Boolean(document.querySelector('#sheet-root.open, .modal-backdrop[data-moyu-modal]'));
const selecting = () => { const s = window.getSelection?.(); return Boolean(s && !s.isCollapsed && String(s).trim()); };

function place(offset, width) {
  const node = root();
  if (!node) return;
  node.style.setProperty('--drawer-x', `${offset}px`);
  node.style.setProperty('--drawer-progress', String(Math.max(0, Math.min(1, 1 + offset / width))));
}

function finish(open) {
  const node = root();
  track = null;
  if (!node) return;
  node.classList.remove('dragging');
  setDrawer(open);
}

// A drag must not also click whatever the finger started on.
function swallowNextClick() {
  const swallow = (event) => { event.stopPropagation(); event.preventDefault(); };
  document.addEventListener('click', swallow, { capture: true, once: true });
  setTimeout(() => document.removeEventListener('click', swallow, { capture: true }), 400);
}

document.addEventListener('pointerdown', (event) => {
  track = null;
  if (event.pointerType !== 'touch' || !event.isPrimary || docked() || blockedByOverlay() || selecting()) return;
  const open = isDrawerOpen();
  const inDrawer = Boolean(event.target.closest?.('#drawer-root'));
  if (open ? !inDrawer : !event.target.closest?.('#app')) return;
  if (!open && event.target.closest?.(NO_SWIPE)) return;
  track = { id: event.pointerId, open, x0: event.clientX, y0: event.clientY, t0: event.timeStamp, dragging: false, width: 0, lastX: event.clientX, lastT: event.timeStamp, velocity: 0 };
}, true);

document.addEventListener('pointermove', (event) => {
  if (!track || event.pointerId !== track.id) return;
  const dx = event.clientX - track.x0;
  const dy = event.clientY - track.y0;
  if (!track.dragging) {
    if (Math.abs(dx) < SLOP && Math.abs(dy) < SLOP) return;
    const sideways = Math.abs(dx) > Math.abs(dy) * 1.2;
    const towardsTarget = track.open ? dx < 0 : dx > 0;
    if (!sideways || !towardsTarget || event.timeStamp - track.t0 > LONG_PRESS) { track = null; return; }
    const drawer = root()?.querySelector('.drawer');
    if (!drawer) { track = null; return; }
    track.dragging = true;
    track.width = drawer.getBoundingClientRect().width || 300;
    root().classList.add('dragging');
  }
  const offset = track.open ? Math.min(0, Math.max(-track.width, dx)) : Math.min(0, Math.max(-track.width, dx - track.width));
  place(offset, track.width);
  const dt = event.timeStamp - track.lastT;
  if (dt > 0) track.velocity = 0.6 * ((event.clientX - track.lastX) / dt) + 0.4 * track.velocity;
  track.lastX = event.clientX;
  track.lastT = event.timeStamp;
}, true);

document.addEventListener('pointerup', (event) => {
  if (!track || event.pointerId !== track.id) return;
  if (!track.dragging) { track = null; return; }
  const progress = Number(root()?.style.getPropertyValue('--drawer-progress')) || 0;
  const open = track.velocity > FLING ? true : track.velocity < -FLING ? false : progress > 0.5;
  swallowNextClick();
  finish(open);
}, true);

document.addEventListener('pointercancel', (event) => {
  if (!track || event.pointerId !== track.id) return;
  if (track.dragging) finish(track.open);
  else track = null;
}, true);
