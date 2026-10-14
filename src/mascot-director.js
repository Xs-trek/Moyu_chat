// Decides when the fish react, so the light easter eggs stay rare and never fight each other. Every reaction goes
// through `act` (role → fish, per-pose cooldown, delights / reduced motion respected by mascot.js). The timers here
// are sparse one-shot timeouts (ambient moves every 10–25 s, the 60 s slack-off check), never an animation loop, and
// they skip their turn while the page is hidden.
//
// Roles: 'hero' (the big fish on an empty page), 'perch' (sits on the composer of a conversation), 'drawer' (in the
// session drawer's header).
import { hold, lateNight, moving, randomQuote, react, release, showing } from './mascot.js';

const AMBIENT = ['yawn', 'turn', 'blow', 'look'];
const WORKING = new Set(['think', 'search', 'write', 'sweat', 'verify', 'compact']);
const SLACK_AFTER_MS = 60_000;
const lastPlayed = new Map();

export function fishByRole(role) {
  const node = document.querySelector(`.fish[data-role="${role}"]`);
  return node?.isConnected && node.getClientRects().length ? node : null;
}

/** Plays a reaction on the fish with this role, at most once per cooldown. */
export function act(role, pose, cooldownMs = 0) {
  const node = typeof role === 'string' ? fishByRole(role) : role;
  if (!node) return false;
  const key = `${node.dataset.role || ''}:${pose}`;
  const now = Date.now();
  if (cooldownMs && now - (lastPlayed.get(key) || 0) < cooldownMs) return false;
  if (!react(node, pose)) return false;
  lastPlayed.set(key, now);
  return true;
}

// ---------- Conversation events (perch) ----------

/** A message was sent. Late at night the fish yawns instead of hopping. */
export function sent(now = new Date()) {
  if (lateNight(now) && act('perch', 'yawn', 10 * 60_000)) return;
  act('perch', 'hop');
}

/** A turn ended: 'done' | 'interrupted' | 'failed'. */
export function settled(kind) {
  if (kind === 'interrupted') act('perch', 'cut', 1500);
  else if (kind === 'failed') act('perch', 'faint');
  else act('perch', 'done');
}

/** The user pressed stop. */
export function interrupted() { act('perch', 'cut', 1500); }

export function rated(rating) { if (rating > 0) act('perch', 'hop'); else if (rating < 0) act('perch', 'sad'); }

export function milestone() { act('perch', 'cheer'); }

/** Sessions were deleted from the multi-select bar. */
export function batchDeleted() { act('drawer', 'eat'); }

/** The perch fish's resting pose for a session (working poses follow the live stage). */
export function perchPose(stagePose, chars) {
  if (stagePose === 'write' && chars > 3000) return 'sweat';
  return WORKING.has(stagePose) ? stagePose : 'idle';
}

// ---------- Taps ----------

let pokes = [];
/** Tapping the big fish: a hop and a new line; seven quick taps earn a somersault. Returns the line to show. */
export function poke(node) {
  const now = Date.now();
  pokes = pokes.filter((t) => now - t < 4000).concat(now);
  if (!node || !moving(node)) return '';
  if (pokes.length >= 7) { pokes = []; react(node, 'flip'); return '看我翻个跟头。'; }
  react(node, 'hop');
  if (koi && !koiAnnounced) { koiAnnounced = true; return '今天遇到了锦鲤，运气不错。'; }
  return randomQuote();
}

let drawerTaps = [];
/** Five quick taps on the drawer's fish: it swims across the header and back. */
export function pokeDrawer(node) {
  const now = Date.now();
  drawerTaps = drawerTaps.filter((t) => now - t < 3000).concat(now);
  if (!node || !moving(node)) return;
  if (drawerTaps.length < 5) { react(node, 'hop'); return; }
  drawerTaps = [];
  swimAcross(node);
}

function swimAcross(node) {
  const head = node.closest('.drawer-head');
  const distance = Math.max(0, Math.floor(((head?.clientWidth || 0) - node.offsetWidth - 64) / 2) * 2);
  if (!distance || typeof node.animate !== 'function') { react(node, 'turn'); return; }
  // Whole sprite pixels only: the fish (2px per sprite pixel) moves in 2px steps.
  const leg = (from, to) => node.animate([{ transform: `translateX(${from}px)` }, { transform: `translateX(${to}px)` }], { duration: distance * 6, easing: `steps(${distance / 2})`, fill: 'forwards' });
  hold(node, 'swim');
  leg(0, distance).finished.then(() => {
    if (!node.isConnected) return null;
    hold(node, 'swimBack');
    return leg(distance, 0).finished;
  }).then((animation) => { if (animation) animation.cancel(); release(node); }).catch(() => release(node));
}

// ---------- Rare koi ----------

let koi = false;
let koiAnnounced = false;
let koiRolled = false;

export function today(date = new Date()) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/**
 * Once per launch: 1 in 200 chance of koi colours, which then last the rest of the day (the day is kept natively).
 * Returns the koiDay to save, or null when nothing changes.
 */
export function rollKoi(savedDay, date = new Date(), random = Math.random) {
  if (koiRolled) return null;
  koiRolled = true;
  const day = today(date);
  koi = savedDay === day || random() < 1 / 200;
  document.documentElement.dataset.koi = koi ? 'on' : 'off';
  return koi && savedDay !== day ? day : null;
}

// ---------- Ambient life (installed once by main.js) ----------

let slacker = null;
let slackTimer = 0;
let typingTimer = 0;
let ambientTimer = 0;

function scheduleAmbient() {
  clearTimeout(ambientTimer);
  ambientTimer = setTimeout(() => {
    const hero = fishByRole('hero');
    if (hero && !document.hidden && !hero.__reaction) react(hero, AMBIENT[Math.floor(Math.random() * AMBIENT.length)]);
    scheduleAmbient();
  }, 10_000 + Math.random() * 15_000);
}

function scheduleSlack() {
  clearTimeout(slackTimer);
  slackTimer = setTimeout(() => {
    if (document.hidden) { scheduleSlack(); return; }
    const node = fishByRole('perch') || fishByRole('hero');
    if (!node || node.__reaction || WORKING.has(node.dataset.base) || document.querySelector('#sheet-root.open')) { scheduleSlack(); return; }
    if (hold(node, 'slack')) slacker = node;
  }, SLACK_AFTER_MS);
}

/** Any interaction: a slacking fish is caught and scrambles; the slack-off clock restarts. */
function interacted() {
  if (slacker) {
    const node = slacker;
    slacker = null;
    if (node.isConnected && showing(node) === 'slack') { release(node); react(node, 'startle'); }
  }
  scheduleSlack();
}

let stroke = null;
let swallowPoke = false;
export function consumeStroke() { const swallowed = swallowPoke; swallowPoke = false; return swallowed; }

export function startAmbientLife() {
  for (const type of ['pointerdown', 'keydown', 'wheel', 'input']) document.addEventListener(type, interacted, { capture: true, passive: true });
  scheduleSlack();
  scheduleAmbient();

  // Watching you type on an empty page; looks up again two seconds after the last keystroke.
  document.addEventListener('input', (event) => {
    if (!event.target?.matches?.('textarea[name="message"]')) return;
    const hero = fishByRole('hero');
    if (!hero) return;
    if (showing(hero) !== 'read') hold(hero, 'read');
    clearTimeout(typingTimer);
    typingTimer = setTimeout(() => { if (showing(hero) === 'read') release(hero); }, 2000);
  }, true);

  // A slow stroke across the big fish (not a tap) makes it happy.
  document.addEventListener('pointerdown', (event) => {
    const button = event.target.closest?.('.fish-poke');
    swallowPoke = false;
    stroke = button ? { x: event.clientX, y: event.clientY, t: event.timeStamp, path: 0, done: false } : null;
  }, true);
  document.addEventListener('pointermove', (event) => {
    if (!stroke || stroke.done) return;
    stroke.path += Math.hypot(event.clientX - stroke.x, event.clientY - stroke.y);
    stroke.x = event.clientX;
    stroke.y = event.clientY;
    const elapsed = event.timeStamp - stroke.t;
    if (stroke.path > 48 && elapsed > 300 && stroke.path / elapsed < 0.6) {
      stroke.done = true;
      swallowPoke = true;
      act('hero', 'pet', 1500);
    }
  }, true);
  for (const type of ['pointerup', 'pointercancel']) document.addEventListener(type, () => { stroke = null; }, true);
}
