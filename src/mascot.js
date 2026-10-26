// Moyu's pixel fish on the page. The art and the pose table live in src/mascot-sprite.js; here a fish is a small
// SVG that shows one frame of a pose strip, and a pose plays as a single Web Animation stepping the strip by whole
// frames (transform only, compositor-driven, no JS loop).
//
// A fish has a base pose (`data-base`, set by the renderer and kept in sync by morph) and may play a one-shot
// reaction on top (react / hold); when the reaction ends it returns to whatever the base is by then. Playback state
// lives in JS properties, not attributes, so a morph never interrupts it. With delights off, the system "remove
// animations" setting, or `data-static`, a fish shows the first frame of its pose and never moves.
import { h, svg } from './dom.js';
import { buildStrip, CORE, H, POSES, poseDuration, W } from './mascot-sprite.js';

export { POSES };

let defs = null;
function stripId(pose, season) {
  const id = `moyu-strip-${pose}${season ? `-${season}` : ''}`;
  if (!defs || !defs.isConnected) {
    defs = svg('defs');
    document.body.append(svg('svg', { id: 'moyu-fish-sprite', class: 'sprite-defs', width: 0, height: 0, 'aria-hidden': 'true' }, defs));
  }
  if (!document.getElementById(id)) defs.append(buildStrip(pose, season, id));
  return id;
}

const known = (pose) => (Object.prototype.hasOwnProperty.call(POSES, pose) ? pose : 'idle');
const reduced = () => Boolean(window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches);
/** Whether this fish may animate at all. */
export function moving(node) { return document.documentElement.dataset.delight !== 'off' && !reduced() && !node?.dataset.static; }

/**
 * A fish element. size: 'xs' (inline in list rows), 'sm' (perch, cards), 'md', 'lg' (empty pages).
 * extra: dataset fields, e.g. { role: 'perch' } (the director finds fish by role) or { static: '1' }.
 */
export function fish(pose = 'idle', size = 'sm', extra = {}) {
  const season = seasonal(new Date()) || '';
  const base = known(pose);
  return h('span', { class: `fish ${size}`, dataset: { base, season, morph: 'own', ...extra } },
    svg('svg', { class: 'fish-art', viewBox: `${CORE.x} ${CORE.y} ${CORE.w} ${CORE.h}`, overflow: 'visible', 'aria-hidden': 'true' },
      svg('svg', { x: 0, y: 0, width: W, height: H, overflow: 'hidden' },
        svg('use', { class: 'fish-strip', href: `#${stripId(base, season)}` }))));
}

function play(node, pose, once) {
  const id = known(pose);
  const strip = node.querySelector('.fish-strip');
  if (!strip) return;
  node.__pose = id;
  const href = `#${stripId(id, node.dataset.season || '')}`;
  if (strip.getAttribute('href') !== href) strip.setAttribute('href', href);
  node.__anim?.cancel();
  node.__anim = null;
  if (!moving(node) || typeof strip.animate !== 'function') return;
  const frames = POSES[id].frames;
  const total = poseDuration(id);
  let at = 0;
  const keyframes = frames.map((frame, k) => { const step = { offset: at / total, transform: `translateX(${-k * W}px)`, easing: 'step-end' }; at += frame.d; return step; });
  keyframes.push({ offset: 1, transform: `translateX(${-(frames.length - 1) * W}px)` });
  node.__anim = strip.animate(keyframes, { duration: total, iterations: POSES[id].loop && !once ? Infinity : 1, fill: 'forwards' });
}

/** Brings a fish's playback in line with its base pose, unless a reaction is showing. */
function sync(node) {
  if (!node.isConnected || node.__reaction) return;
  const base = known(node.dataset.base || 'idle');
  if (node.__pose === base && Boolean(node.__anim) === moving(node)) return;
  play(node, base, false);
}

/** Sets the pose a fish rests in; a running reaction finishes first. */
export function setPose(node, pose) {
  if (!node) return;
  const base = known(pose);
  if (node.dataset.base !== base) node.dataset.base = base;
  else sync(node);
}

/** Plays a one-shot pose, then returns to the base. Returns false when this fish may not move. */
export function react(node, pose, holdMs = 0) {
  if (!node?.isConnected || !moving(node) || !POSES[pose]) return false;
  clearTimeout(node.__reactionTimer);
  node.__reaction = pose;
  play(node, pose, true);
  const length = POSES[pose].loop ? holdMs || 2400 : Math.max(holdMs, poseDuration(pose));
  node.__reactionTimer = setTimeout(() => release(node), length);
  return true;
}

/** Holds a pose (looping) until release(); used for states that last, like slacking off or watching you type. */
export function hold(node, pose) {
  if (!node?.isConnected || !moving(node) || !POSES[pose]) return false;
  clearTimeout(node.__reactionTimer);
  node.__reaction = pose;
  play(node, pose, false);
  return true;
}

export function release(node) {
  if (!node) return;
  clearTimeout(node.__reactionTimer);
  node.__reaction = null;
  if (node.isConnected) play(node, node.dataset.base || 'idle', false);
}

/** What a fish is showing right now (a reaction or its base). */
export function showing(node) { return node?.__reaction || node?.dataset.base || ''; }

// New fish start playing when they are attached; a changed base (morph or setPose) or a delight / theme switch
// re-syncs them. MutationObserver is event-driven: no polling.
function syncAll() {
  document.querySelectorAll('.fish').forEach((node) => {
    if (!moving(node)) { clearTimeout(node.__reactionTimer); node.__reaction = null; }
    node.__pose = null;
    sync(node);
  });
}
if (typeof MutationObserver === 'function' && typeof document !== 'undefined' && document.documentElement) {
  new MutationObserver((records) => {
    for (const record of records) {
      if (record.type === 'attributes') {
        // Re-setting an attribute to the same value (applyRoot runs on every patch) still queues a record.
        if (record.oldValue === record.target.getAttribute(record.attributeName)) continue;
        if (record.target === document.documentElement) { syncAll(); return; }
        if (record.target.classList?.contains('fish')) sync(record.target);
        continue;
      }
      for (const node of record.addedNodes) {
        if (node.nodeType !== 1) continue;
        if (node.classList.contains('fish')) sync(node);
        else if (node.firstElementChild) node.querySelectorAll('.fish').forEach(sync);
      }
    }
  }).observe(document.documentElement, { subtree: true, childList: true, attributes: true, attributeOldValue: true, attributeFilter: ['data-base', 'data-delight'] });
  window.matchMedia?.('(prefers-reduced-motion: reduce)')?.addEventListener?.('change', syncAll);
}

// Chinese New Year (eve → day 6) and Mid-Autumn (±1 day), 2026–2030.
const CNY = ['2026-02-17', '2027-02-06', '2028-01-26', '2029-02-13', '2030-02-03'];
const MOON = ['2026-09-25', '2027-09-15', '2028-10-03', '2029-09-22', '2030-09-12'];
function daysFrom(date, iso) { const [y, m, d] = iso.split('-').map(Number); return Math.round((new Date(date.getFullYear(), date.getMonth(), date.getDate()) - new Date(y, m - 1, d)) / 86400000); }
export function seasonal(date) {
  if (CNY.some((iso) => { const n = daysFrom(date, iso); return n >= -1 && n <= 6; })) return 'cny';
  if (MOON.some((iso) => Math.abs(daysFrom(date, iso)) <= 1)) return 'moon';
  return null;
}

/** Late night (01:00–05:00): the fish yawns at messages and the empty page tells you to sleep. */
export function lateNight(date = new Date()) { const hour = date.getHours(); return hour >= 1 && hour < 5; }

/** Greeting for an empty conversation; plain when delights are off. */
export function greeting(date = new Date(), delight = true) {
  if (!delight) return { text: '开始新的对话', pose: 'still' };
  const hour = date.getHours();
  const day = date.getDay();
  const season = seasonal(date);
  if (season === 'cny') return { text: '新年快乐，今年也要好好摸鱼。', pose: 'idle' };
  if (season === 'moon') return { text: '中秋快乐，月亮也在摸鱼。', pose: 'idle' };
  if (lateNight(date)) return { text: '该睡了，明天再问也不迟。', pose: 'sleep' };
  if (hour >= 23 || hour < 5) return { text: '夜深了，小鱼先睡了。', pose: 'sleep' };
  if (day === 5 && hour >= 14) return { text: '周五下午，适合摸鱼。', pose: 'idle' };
  if (day === 1 && hour < 11) return { text: '周一早上，先喝口水。', pose: 'idle' };
  if (hour < 9) return { text: '早，今天想问点什么？', pose: 'idle' };
  if (hour >= 12 && hour < 14) return { text: '午饭后容易犯困，问个轻松的？', pose: 'idle' };
  if (hour >= 19) return { text: '晚上好，慢慢聊。', pose: 'idle' };
  return { text: '今天想问点什么？', pose: 'idle' };
}

export const QUOTES = [
  '摸鱼不是偷懒，是给脑子换气。', '先把问题写清楚，答案就出来一半了。', '缓存命中率高的时候，我游得特别快。', '上下文太长？我帮你记着，别急。',
  '温度 0.7，刚刚好。', 'token 是水，省着点游。', '我是团子鱼，圆的那种。', '回退不丢人，分支更优雅。', '校验通过的时候，我会盖个小印。',
  '联网的时候我会举放大镜，看起来很专业。', '推理的时候我在吐泡泡，其实是在想事情。', '你戳我七下，我就翻个跟头。', '别看我小，我见过上百万 token。',
  '今天的鱼塘很平静。', '同一个问题换个模型问问，也许有惊喜。', '协议相同，能力未必相同。', '先跑通，再优化。', '读完再改，改完再验。',
  '深夜别写生产代码。', '多喝水，少熬夜。', '摸鱼五分钟，专注两小时。', '这个回答我也看不懂，但它看起来很自信。', '把需求拆小一点，我能游得更远。',
  '错误信息是朋友，认真读读它。', '有时候问题本身就是答案。', '保存草稿是好习惯。', '我不会联网偷看你的 API Key。', '慢一点也没关系。', '咕噜咕噜。', '……（吐了个泡泡）',
  '慢慢摸摸我，会冒爱心。', '回答慢的时候，点点我。',
];

let lastQuote = -1;
export function randomQuote() {
  let index = Math.floor(Math.random() * QUOTES.length);
  if (index === lastQuote) index = (index + 1) % QUOTES.length;
  lastQuote = index;
  return QUOTES[index];
}

/** Milestones (cumulative tokens of one conversation) that earn a one-time notice when crossed live. */
export const MILESTONES = [[1e5, '10 万'], [1e6, '100 万'], [1e7, '1000 万']];
