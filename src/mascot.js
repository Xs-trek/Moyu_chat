// Moyu's pixel fish. One shared SVG sprite (≈30 rects) is injected once; every fish is three <use>
// references plus a few accessory nodes. Poses are CSS classes animating transform/opacity only, so a
// pose change never rebuilds DOM and a looping pose costs a handful of compositor frames per second.
import { h, svg } from './dom.js';

// 16×9 grid. a body, b belly, f fin, t tail (own group, wags), e eye (own group, blinks).
const MAP = [
  '.....ffff.......',
  '....aaaaaaa.....',
  't..aaaaaaaaaa...',
  'tt.aaaaaaaeaaa..',
  'ttaaaaaaaaaaaaa.',
  'ttaabbbbbbbbbaa.',
  'tt.abbbbbbbbba..',
  't...abbbbbbba...',
  '......ff........',
];
const FILL = { a: 'var(--fish)', b: 'var(--fish-belly)', f: 'var(--fish-fin)', t: 'var(--fish-fin)', e: 'var(--fish-eye)' };
const GROUP = { a: 'body', b: 'body', f: 'body', t: 'tail', e: 'eye' };
export const POSES = ['idle', 'think', 'search', 'write', 'verify', 'done', 'fail', 'cut', 'sleep', 'still', 'flip'];

function ensureSprite() {
  if (document.getElementById('moyu-fish-sprite')) return;
  const groups = { body: [], tail: [], eye: [] };
  MAP.forEach((row, y) => {
    let x = 0;
    while (x < row.length) {
      const c = row[x];
      let end = x + 1;
      while (end < row.length && row[end] === c) end += 1;
      if (c !== '.') groups[GROUP[c]].push(svg('rect', { x, y, width: end - x, height: 1, fill: FILL[c] }));
      x = end;
    }
  });
  const hat = [svg('rect', { x: 5, y: -1, width: 5, height: 1, fill: '#c8372d' }), svg('rect', { x: 6, y: -2, width: 3, height: 1, fill: '#c8372d' }), svg('rect', { x: 7, y: -3, width: 1, height: 1, fill: '#f2c94c' })];
  const sprite = svg('svg', { id: 'moyu-fish-sprite', width: 0, height: 0, 'aria-hidden': 'true', class: 'sprite-defs' },
    svg('symbol', { id: 'moyu-fish-body', viewBox: '0 0 16 9', overflow: 'visible' }, groups.body),
    svg('symbol', { id: 'moyu-fish-tail', viewBox: '0 0 16 9', overflow: 'visible' }, groups.tail),
    svg('symbol', { id: 'moyu-fish-eye', viewBox: '0 0 16 9', overflow: 'visible' }, groups.eye),
    svg('symbol', { id: 'moyu-fish-xeye', viewBox: '0 0 16 9', overflow: 'visible' }, svg('path', { d: 'M9.6 2.6l1.8 1.8M11.4 2.6l-1.8 1.8', stroke: 'var(--fish-eye)', 'stroke-width': '.6' })),
    svg('symbol', { id: 'moyu-fish-hat', viewBox: '0 0 16 9', overflow: 'visible' }, hat));
  document.body.append(sprite);
}

function use(id, cls) { return svg('use', { href: `#${id}`, class: cls, width: 16, height: 9 }); }

/** A fish element. size: 'sm' (status lines), 'md', 'lg' (empty states). */
export function fish(pose = 'idle', size = 'sm', extra = {}) {
  ensureSprite();
  const season = seasonal(new Date());
  const art = svg('svg', { viewBox: '0 0 16 9', class: 'fish-art', 'aria-hidden': 'true' },
    use('moyu-fish-body', 'fish-body'), use('moyu-fish-tail', 'fish-tail'), use('moyu-fish-eye', 'fish-eye'), use('moyu-fish-xeye', 'fish-xeye'),
    season === 'cny' ? use('moyu-fish-hat', 'fish-hat') : null);
  const node = h('span', { class: `fish ${size}`, dataset: { pose: POSES.includes(pose) ? pose : 'idle', ...extra } },
    art,
    h('span', { class: 'fish-acc fish-bubbles', 'aria-hidden': 'true' }, h('i'), h('i'), h('i')),
    h('span', { class: 'fish-acc fish-drops', 'aria-hidden': 'true' }, h('i'), h('i'), h('i')),
    h('span', { class: 'fish-acc fish-zzz', 'aria-hidden': 'true' }, h('i', { text: 'z' }), h('i', { text: 'z' })),
    h('span', { class: 'fish-acc fish-lens', 'aria-hidden': 'true' }),
    h('span', { class: 'fish-acc fish-glasses', 'aria-hidden': 'true' }),
    season === 'moon' ? h('span', { class: 'fish-acc fish-moon', 'aria-hidden': 'true' }) : null);
  return node;
}

/** Changes pose in place; the node (and any running animation of an unchanged pose) is kept. */
export function setPose(node, pose) {
  if (node && node.dataset.pose !== pose) node.dataset.pose = POSES.includes(pose) ? pose : 'idle';
}

/** One-shot pose that falls back to a resting pose; used for "done" splashes and the poke flip. */
export function flash(node, pose, ms = 900, rest = 'still') {
  if (!node) return;
  setPose(node, pose);
  clearTimeout(node.__flashTimer);
  node.__flashTimer = setTimeout(() => { if (node.isConnected) setPose(node, rest); }, ms);
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

/** Greeting for an empty conversation; plain when delights are off. */
export function greeting(date = new Date(), delight = true) {
  if (!delight) return { text: '开始新的对话', pose: 'still' };
  const hour = date.getHours();
  const day = date.getDay();
  const season = seasonal(date);
  if (season === 'cny') return { text: '新年快乐，今年也要好好摸鱼。', pose: 'idle' };
  if (season === 'moon') return { text: '中秋快乐，月亮也在摸鱼。', pose: 'idle' };
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
  '温度 0.7，刚刚好。', 'token 是水，省着点游。', '我只有 16×9 个像素，但我很努力。', '回退不丢人，分支更优雅。', '校验通过的时候，我会盖个小印。',
  '联网的时候我会举放大镜，看起来很专业。', '推理的时候我在吐泡泡，其实是在想事情。', '你戳我七下，我就翻个跟头。', '别看我小，我见过上百万 token。',
  '今天的鱼塘很平静。', '同一个问题换个模型问问，也许有惊喜。', '协议相同，能力未必相同。', '先跑通，再优化。', '读完再改，改完再验。',
  '深夜别写生产代码。', '多喝水，少熬夜。', '摸鱼五分钟，专注两小时。', '这个回答我也看不懂，但它看起来很自信。', '把需求拆小一点，我能游得更远。',
  '错误信息是朋友，认真读读它。', '有时候问题本身就是答案。', '保存草稿是好习惯。', '我不会联网偷看你的 API Key。', '慢一点也没关系。', '咕噜咕噜。', '……（吐了个泡泡）',
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
