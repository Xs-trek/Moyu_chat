// Moyu's pixel fish ("团子鱼") as data. Every part is a character map; a pose is a table of frames, and a frame
// says which part variants to stamp and how far to shift them in whole sprite pixels. Frames are composed into
// grids here; src/mascot.js lays a pose's frames side by side as one SVG strip and steps through it with a single
// Web Animation (transform only), so nothing redraws per frame and no JS loop runs.
//
// Rules (docs/ui-design.md §6): move only in whole sprite pixels; turn round and go belly up with mirrors, never
// an arbitrary rotation; looping poses start and end on the same frame; colours are classes mapped to --fish-* tokens.
import { svg } from './dom.js';

/** Frame canvas in sprite pixels. */
export const W = 34;
export const H = 29;
/** The body box layouts size around; accessories (bubbles, hearts, z) spill outside it. */
export const CORE = { x: 7, y: 8, w: 22, h: 15 };
const OX = 10;
const OY = 11;
/** Accessory anchor: top of the head, face side. */
const HEAD = [17, 11];

// a body · h sheen · b shade · f fins and tail · e eye · w eye shine · p cheek · m mouth · k sunglasses/phone · n phone screen · r hat
// Accessories use capitals: Q bubble · Z sleep · X alert · H heart · L lens rim · G lens glass · Y spark · N crumb · K hook
export const INK = {
  a: 'ink-body', h: 'ink-sheen', b: 'ink-shade', f: 'ink-fin', e: 'ink-eye', w: 'ink-shine', p: 'ink-cheek', m: 'ink-eye', k: 'ink-eye', n: 'ink-bubble', r: 'ink-hat',
  Q: 'ink-bubble', Z: 'ink-sleep', X: 'ink-alert', H: 'ink-heart', L: 'ink-lens', G: 'ink-glass', Y: 'ink-spark', N: 'ink-crumb', K: 'ink-lens',
};

const BODY = [
  '....aaaaaaa....',
  '..aaaaaaaaaaa..',
  '.aaahhaaaaaaaa.',
  '.aahaaaaaaaaaa.',
  'aaaaaaaaaaaaaaa',
  'aaaaaaaaaaaaaaa',
  'aaaaaaaaaaaaaaa',
  'aaaaaaaaaaaaaaa',
  'aaaaaaaaaaaaaab',
  '.aaaaaaaaaaaab.',
  '..bbaaaaaaabb..',
  '....bbbbbbb....',
];
const TAIL = [
  ['...ff', '..fff', '.fff.', 'fff..', 'fff..', '.fff.', '..fff', '...ff'],
  ['....', '..ff', '.fff', 'fff.', 'fff.', '.fff', '..ff', '....'],
];
const TOP = ['...ff', '..ff.', '.fff.'];
const HAT = ['..Y..', '.rrr.', 'rrrrr'];
const MOON = ['.YY.', 'YYYY', 'YYYY', '.YY.'];
const EYES = {
  open: ['we', 'ee', 'ee'], blink: ['..', '..', 'ee'], closed: ['..', '..', 'ee'], happy: ['...', '.e.', 'e.e'],
  wide: ['we', 'ee', 'ee', 'ee'], squint: ['..', 'ee'], x: ['e.e', '.e.', 'e.e'], heart: ['H.H', 'HHH', '.H.'],
};
const WIDE_EYES = new Set(['x', 'heart', 'happy']);
const MOUTHS = { o: ['mm', 'mm'], smile: ['m..m', '.mm.'], yawn: ['mm', 'mm', 'mm'] };
// [map, dx from body left, dy from body top]; the right fin mirrors the left one.
const FINS = {
  rest: [['.f', 'ff', 'f.'], -2, 7], up: [['f.', 'ff', '.f'], -2, 3], chin: [['ff', 'ff'], 1, 8],
  type1: [['ff', 'ff'], -1, 9], type2: [['ff', 'ff'], -1, 8], hold: [['fff', 'ff.'], -3, 6], down: [['.f', '.f', 'ff'], -2, 8],
};
const RIGHT_FIN = { chin: 'rest', hold: 'rest', type1: 'type2', type2: 'type1' };
const SHADES = ['kkkkkkkkkkkkk', '.kkkk..kkkk..', '..kk....kk...'];
const PHONE = ['kkk', 'knk', 'knk', 'kkk'];

export const ACC = {
  bub1: ['.Q.', 'Q.Q', '.Q.'],
  bub2: ['.QQ.', 'Q..Q', 'Q..Q', '.QQ.'],
  z1: ['ZZZ', '..Z', '.Z.', 'ZZZ'],
  z2: ['ZZZZ', '...Z', '..Z.', '.Z..', 'ZZZZ'],
  bang: ['X', 'X', 'X', '.', 'X'],
  heart: ['HH.HH', 'HHHHH', '.HHH.', '..H..'],
  heartS: ['H.H', 'HHH', '.H.'],
  lens: ['.LLL.', 'LGGGL', 'LGGGL', 'LGGGL', '.LLL.', '....L', '.....L'],
  sweat: ['.Q', 'QQ', 'QQ'],
  glint: ['.Y.', 'YYY', '.Y.'],
  spark: ['Y.Y', '.Y.', 'Y.Y'],
  sparkR: ['X.X', '.X.', 'X.X'],
  star: ['Y'],
  drop: ['Q'],
  crumb: ['N.', '.N'],
};

const T = { fast: 60, base: 100, slow: 160 };

/**
 * Pose table. loop: repeats while it is the fish's pose; otherwise a one-shot that returns to the fish's base pose.
 * Frame fields: d (ms), x/y (whole-sprite shift), gx/gy (gaze), eye, mouth, fin/finR, tail (0/1), body ('sq' | 'st'),
 * acc ([id, dx, dy] from the head anchor), shades, phone, flip (belly up), mirror (turned round).
 */
export const POSES = {
  idle: { loop: true, frames: [{ d: 520 }, { d: 520, y: 1, tail: 1 }, { d: 520 }, { d: 520, y: 1, tail: 1 }, { d: 520 }, { d: 110, eye: 'blink' }, { d: 520, y: 1, tail: 1 }] },
  still: { loop: true, frames: [{ d: 2600 }, { d: 120, eye: 'blink' }] },
  think: { loop: true, frames: [
    { d: 320, gy: -1, gx: -1, fin: 'chin', acc: [['bub1', -2, -4]] },
    { d: 320, gy: -1, gx: -1, fin: 'chin', tail: 1, acc: [['bub1', -2, -6]] },
    { d: 320, gy: -1, fin: 'chin', acc: [['bub1', -2, -8], ['bub2', 1, -5]] },
    { d: 320, gy: -1, fin: 'chin', tail: 1, acc: [['bub2', 1, -7]] },
    { d: 320, gy: -1, gx: -1, fin: 'chin', acc: [['bub2', 1, -9]] }] },
  search: { loop: true, frames: [
    { d: 240, gx: -1, fin: 'hold', acc: [['lens', -15, 2]] }, { d: 240, gx: -1, fin: 'hold', tail: 1, acc: [['lens', -16, 2]] },
    { d: 240, fin: 'hold', acc: [['lens', -15, 2]] }, { d: 240, gx: 1, fin: 'hold', tail: 1, acc: [['lens', -14, 2]] }] },
  write: { loop: true, frames: [{ d: 140, gy: 1, fin: 'type1', tail: 1 }, { d: 140, gy: 1, fin: 'type2' }, { d: 140, gy: 1, fin: 'type1', tail: 1 }, { d: 140, gy: 1, fin: 'type2' }] },
  sweat: { loop: true, frames: [
    { d: 140, gy: 1, fin: 'type1', tail: 1, acc: [['sweat', -6, 1]] }, { d: 140, gy: 1, fin: 'type2', acc: [['sweat', -6, 1]] },
    { d: 140, gy: 1, fin: 'type1', tail: 1, acc: [['sweat', -6, 2]] }, { d: 140, gy: 1, fin: 'type2', acc: [['sweat', -6, 3]] }] },
  verify: { loop: true, frames: [{ d: 420, eye: 'squint', fin: 'chin' }, { d: 120, eye: 'squint', fin: 'chin', acc: [['glint', -9, 2]] }, { d: 420, eye: 'squint', fin: 'chin' }, { d: 300, fin: 'rest', y: 1 }] },
  compact: { loop: true, frames: [{ d: 220, body: 'sq', eye: 'closed', y: 1 }, { d: 220, body: 'sq', eye: 'closed', y: 1, acc: [['bub1', 2, -2]] }, { d: 260 }, { d: 220 }] },
  hop: { frames: [{ d: T.slow, body: 'sq', y: 1 }, { d: T.base, y: -2, fin: 'up', eye: 'happy' }, { d: T.base, y: -3, fin: 'up', eye: 'happy' }, { d: T.base, y: -1, eye: 'happy' }, { d: 300, eye: 'happy' }] },
  done: { frames: [
    { d: T.slow, body: 'sq', y: 1, eye: 'happy' }, { d: T.base, y: -3, body: 'st', fin: 'up', eye: 'happy', acc: [['star', -4, -2], ['star', 4, -3]] },
    { d: T.base, y: -4, fin: 'up', eye: 'happy', mouth: 'smile', acc: [['star', -5, -4], ['star', 5, -5], ['drop', 0, -6]] }, { d: T.base, y: -2, fin: 'up', eye: 'happy', mouth: 'smile' },
    { d: T.slow, body: 'sq', y: 1, eye: 'happy' }, { d: 600, eye: 'happy', mouth: 'smile' }] },
  fail: { loop: true, frames: [{ d: 600, eye: 'x', fin: 'down', flip: true, y: 1 }, { d: 600, eye: 'x', fin: 'down', flip: true, y: 2 }] },
  faint: { frames: [{ d: 160, eye: 'wide' }, { d: 500, eye: 'x', fin: 'down', flip: true, y: 1 }, { d: 500, eye: 'x', fin: 'down', flip: true, y: 2 }, { d: 500, eye: 'x', fin: 'down', flip: true, y: 1 }, { d: 300, eye: 'blink' }] },
  cut: { frames: [{ d: 80, x: 1, eye: 'wide' }, { d: 80, x: -1, eye: 'wide' }, { d: 80, x: 1, eye: 'wide' }, { d: 900, eye: 'wide', fin: 'up', acc: [['bang', -1, -8]] }] },
  sleep: { loop: true, frames: [
    { d: 700, eye: 'closed', y: 2, acc: [['z1', 2, -2]] }, { d: 700, eye: 'closed', y: 3, tail: 1, acc: [['z1', 3, -4], ['z2', 5, -6]] },
    { d: 700, eye: 'closed', y: 2, acc: [['z2', 6, -9]] }, { d: 700, eye: 'closed', y: 3, tail: 1 }] },
  slack: { loop: true, frames: [
    { d: 900, shades: true, phone: true, fin: 'chin', y: 1 }, { d: 900, shades: true, phone: true, fin: 'chin', y: 2, tail: 1 },
    { d: 160, shades: true, phone: true, fin: 'chin', y: 1, acc: [['heartS', 6, -3]] }, { d: 900, shades: true, phone: true, fin: 'chin', y: 2, tail: 1 }] },
  startle: { frames: [{ d: 90, eye: 'wide', y: -2, fin: 'up', shades: true, acc: [['bang', -1, -8]] }, { d: 90, eye: 'wide', y: -1, fin: 'up', acc: [['bang', -1, -8]] }, { d: 360, eye: 'wide' }, { d: 200, eye: 'blink' }] },
  turn: { frames: [{ d: 300 }, { d: T.base, body: 'sq' }, { d: 900, mirror: true, tail: 1 }, { d: 300, mirror: true }, { d: T.base, mirror: true, body: 'sq' }, { d: 400 }] },
  swim: { loop: true, frames: [{ d: 90, tail: 1, fin: 'up' }, { d: 90 }] },
  swimBack: { loop: true, frames: [{ d: 90, tail: 1, fin: 'up', mirror: true }, { d: 90, mirror: true }] },
  pet: { frames: [{ d: 200, eye: 'happy', y: 1 }, { d: 250, eye: 'happy', mouth: 'smile', acc: [['heartS', 2, -4]] }, { d: 250, eye: 'heart', acc: [['heart', 1, -8]] }, { d: 600, eye: 'happy', mouth: 'smile', acc: [['heart', 1, -10]] }] },
  eat: { frames: [{ d: 200, mouth: 'o', body: 'st', y: -1 }, { d: 120, mouth: 'o', acc: [['crumb', -8, 6]] }, { d: 160, body: 'sq', y: 1, eye: 'closed' }, { d: 400, eye: 'happy', acc: [['bub1', -3, -3]] }] },
  yawn: { frames: [{ d: 300, eye: 'closed', body: 'st', y: -1 }, { d: 700, eye: 'closed', mouth: 'yawn', body: 'st', y: -1, fin: 'up' }, { d: 200, eye: 'blink' }, { d: 400 }] },
  sad: { frames: [{ d: 120, body: 'sq', y: 1, eye: 'closed', fin: 'down' }, { d: 900, body: 'sq', y: 1, eye: 'closed', fin: 'down', acc: [['drop', -5, 3]] }, { d: 200, eye: 'blink' }] },
  look: { frames: [{ d: 200, eye: 'wide' }, { d: 700 }, { d: 110, eye: 'blink' }, { d: 400, eye: 'happy' }] },
  blow: { frames: [{ d: 200, mouth: 'o' }, { d: 240, mouth: 'o', acc: [['bub1', -2, -3]] }, { d: 240, acc: [['bub1', -2, -5]] }, { d: 240, acc: [['bub2', -3, -8]] }, { d: 200 }] },
  read: { loop: true, frames: [{ d: 900, gy: 1, gx: -1 }, { d: 120, gy: 1, gx: -1, eye: 'blink' }, { d: 900, gy: 1, gx: -1, y: 1, tail: 1 }] },
  flip: { frames: [
    { d: T.slow, body: 'sq', y: 1 }, { d: T.base, y: -3 }, { d: T.base, y: -5, flip: true, eye: 'wide' }, { d: T.base, y: -3, flip: true },
    { d: T.base, y: -1 }, { d: T.slow, body: 'sq', y: 1, eye: 'happy' }, { d: 400, eye: 'happy' }] },
  cheer: { frames: [
    { d: 120, fin: 'up', eye: 'happy', acc: [['star', -8, -4], ['star', 8, -5]] },
    { d: 140, y: -2, fin: 'up', eye: 'happy', acc: [['glint', -9, -6], ['glint', 8, -7], ['star', 0, -8]] },
    { d: 180, y: -3, fin: 'up', eye: 'happy', mouth: 'smile', acc: [['spark', -11, -5], ['sparkR', 8, -6], ['glint', -2, -7]] },
    { d: 180, y: -1, eye: 'happy', mouth: 'smile', acc: [['sparkR', -12, -8], ['spark', 10, -9], ['star', -4, -10], ['star', 3, -10]] },
    { d: 500, eye: 'happy', mouth: 'smile' }] },
};

/** Total length of one pass through a pose. */
export function poseDuration(pose) { return (POSES[pose] || POSES.idle).frames.reduce((sum, frame) => sum + frame.d, 0); }

const blank = () => Array.from({ length: H }, () => Array(W).fill('.'));
const mirrorRows = (rows) => rows.map((row) => [...row].reverse().join(''));

function stamp(grid, rows, x, y) {
  for (let j = 0; j < rows.length; j++) for (let i = 0; i < rows[j].length; i++) {
    const c = rows[j][i];
    if (c === '.') continue;
    const X = x + i;
    const Y = y + j;
    if (Y >= 0 && Y < H && X >= 0 && X < W) grid[Y][X] = c;
  }
}

/** One frame as a grid of ink letters ('.' is empty). season: 'cny' (hat) | 'moon' | ''. */
export function composeFrame(frame, season = '') {
  const g = blank();
  const ox = OX + (frame.x || 0);
  const oy = OY + (frame.y || 0);
  stamp(g, TAIL[frame.tail ? 1 : 0], ox + 14, oy + 2);
  const body = BODY.slice();
  let top = oy;
  if (frame.body === 'sq') { body.splice(5, 1); top = oy + 1; }
  if (frame.body === 'st') { body.splice(5, 0, body[5]); top = oy - 1; }
  stamp(g, season === 'cny' ? HAT : TOP, ox + 6 - (season === 'cny' ? 1 : 0), top - 3);
  stamp(g, body, ox, top);
  const kind = frame.eye || 'open';
  const eye = EYES[kind];
  const ey = top + 4 + (frame.gy || 0) + (kind === 'wide' ? -1 : 0);
  const ex = frame.gx || 0;
  stamp(g, eye, ox + 3 + ex, ey);
  stamp(g, eye, ox + (WIDE_EYES.has(kind) ? 8 : 9) + ex, ey);
  if (kind !== 'x') { stamp(g, ['pp'], ox + 1, top + 7); stamp(g, ['pp'], ox + 11, top + 7); }
  if (frame.mouth) stamp(g, MOUTHS[frame.mouth], ox + (frame.mouth === 'smile' ? 5 : 6), top + 8);
  const left = FINS[frame.fin || 'rest'];
  stamp(g, left[0], ox + left[1], top + left[2]);
  const right = FINS[frame.finR || RIGHT_FIN[frame.fin] || frame.fin || 'rest'];
  stamp(g, mirrorRows(right[0]), ox + 15 - right[1] - right[0][0].length, top + right[2]);
  if (frame.shades) stamp(g, SHADES, ox + 1, top + 4);
  if (frame.phone) stamp(g, PHONE, ox + 6, top + 8);
  if (season === 'moon') stamp(g, MOON, 28, 4);
  for (const [id, dx, dy] of frame.acc || []) stamp(g, ACC[id], HEAD[0] + dx + (frame.x || 0), HEAD[1] + dy + (frame.y || 0));
  let out = g;
  if (frame.flip) {
    // Belly up: a vertical mirror around the body's own centre (y 16.5).
    const flipped = blank();
    for (let y = 0; y < H; y++) { const Y = 33 - y; if (Y >= 0 && Y < H) flipped[Y] = out[y]; }
    out = flipped;
  }
  if (frame.mirror) {
    // Turn round the body's own centre (x 17), not the canvas centre, so the fish does not jump sideways.
    const turned = blank();
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) { const X = 34 - x; if (X >= 0 && X < W && out[y][x] !== '.') turned[y][X] = out[y][x]; }
    out = turned;
  }
  return out;
}

/**
 * Builds one pose's strip: frame k drawn at x = k·W, each horizontal run of one ink a single <rect>.
 * Returned as a <g> to place in the shared hidden <defs>.
 */
export function buildStrip(pose, season, id) {
  const frames = (POSES[pose] || POSES.idle).frames;
  const group = svg('g', { id });
  frames.forEach((frame, k) => {
    const grid = composeFrame(frame, season);
    for (let y = 0; y < H; y++) {
      let x = 0;
      while (x < W) {
        const c = grid[y][x];
        let end = x + 1;
        while (end < W && grid[y][end] === c) end += 1;
        if (c !== '.') group.append(svg('rect', { x: k * W + x, y, width: end - x, height: 1, class: INK[c] }));
        x = end;
      }
    }
  });
  return group;
}
