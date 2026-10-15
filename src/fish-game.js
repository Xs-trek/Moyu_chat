// "等回答时摸会儿鱼": a tiny catch game for slow answers. Drag the fish left and right, catch bubbles, dodge the boss's
// hooks. When the answer arrives no more hooks fall, a banner says so and the sheet closes itself a few seconds later.
//
// This is the one place in the UI with a running animation loop (docs/ui-design.md §4 rule 6): requestAnimationFrame
// runs only while a round is in play inside the open sheet, and stops when the round ends or the sheet closes.
import { h } from './dom.js';
import { composeFrame, INK, POSES } from './mascot-sprite.js';

const VIEW_W = 160;
const VIEW_H = 120;
const INK_TOKENS = { 'ink-body': '--fish', 'ink-sheen': '--fish-light', 'ink-shade': '--fish-shade', 'ink-fin': '--fish-fin', 'ink-eye': '--fish-eye', 'ink-shine': '--fish-shine', 'ink-cheek': '--fish-cheek' };

function palette() {
  const style = getComputedStyle(document.documentElement);
  const read = (token, fallback) => style.getPropertyValue(token).trim() || fallback;
  const fish = {};
  for (const [letter, cls] of Object.entries(INK)) if (INK_TOKENS[cls]) fish[letter] = read(INK_TOKENS[cls], '#f2a541');
  return { fish, water: read('--inset', '#dce1d9'), wave: read('--line', '#c8d0c5'), bubble: read('--fx-bubble', '#7cc4d8'), hook: read('--ink', '#252a27'), text: read('--ink', '#252a27') };
}

// The fish drawn small: crop the 34×29 frame to its body box.
const SWIM = POSES.swim.frames.map((frame) => composeFrame(frame).slice(8, 23).map((row) => row.slice(7, 29)));
const BUBBLE = ['.QQ.', 'Q..Q', 'Q..Q', '.QQ.'];
const HOOK = ['.KK', 'K..', 'K..', 'K.K', '.K.'];

/**
 * best: the saved best score; onBest(score) saves a new one; onDone() asks the host sheet to close.
 * Returns { node, answerReady(), stop() }.
 */
export function createGame({ best = 0, onBest, onDone }) {
  const canvas = h('canvas', { class: 'fish-game', width: String(VIEW_W), height: String(VIEW_H), 'aria-label': '摸鱼小游戏：左右拖动小鱼接泡泡，躲开鱼钩' });
  // The score is page text over the canvas, so it is as sharp as any other text (the status line announces results).
  const scoreText = h('span', { class: 'fish-game-score', 'aria-hidden': 'true', text: '0' });
  const status = h('p', { class: 'fish-game-status', 'aria-live': 'polite', text: `点一下开始。最好成绩 ${best}。` });
  const node = h('div', { class: 'fish-game-wrap' }, h('div', { class: 'fish-game-stage' }, canvas, scoreText), status);
  const ctx = canvas.getContext?.('2d');
  let scale = 1;
  let shownScore = 0;
  const colors = palette();
  let state = 'ready';
  let raf = 0;
  let last = 0;
  let spawnIn = 0;
  let speed = 1;
  let score = 0;
  let items = [];
  let x = VIEW_W / 2;
  let answered = false;
  let closeTimer = 0;

  /**
   * Backing store at a whole multiple of the 160×120 world, at least the displayed size in device pixels: every world
   * pixel is an integer block of device pixels, so the sprites stay crisp instead of being stretched by the browser.
   */
  function fit() {
    if (!ctx) return;
    const width = canvas.clientWidth || 0;
    const next = Math.max(1, Math.min(8, Math.ceil((width * (window.devicePixelRatio || 1)) / VIEW_W)));
    if (next === scale && canvas.width === VIEW_W * next) return;
    scale = next;
    canvas.width = VIEW_W * scale;
    canvas.height = VIEW_H * scale;
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    ctx.imageSmoothingEnabled = false;
    if (state !== 'playing') paint(last || 0);
  }
  const resize = typeof ResizeObserver === 'function' ? new ResizeObserver(() => fit()) : null;
  resize?.observe(canvas);

  function showScore() {
    if (shownScore === score) return;
    shownScore = score;
    scoreText.textContent = String(score);
  }

  function drawGrid(grid, inks, left, top) {
    for (let j = 0; j < grid.length; j++) for (let i = 0; i < grid[j].length; i++) {
      const color = inks[grid[j][i]];
      if (color) { ctx.fillStyle = color; ctx.fillRect(Math.round(left) + i, Math.round(top) + j, 1, 1); }
    }
  }

  function paint(time) {
    ctx.fillStyle = colors.water;
    ctx.fillRect(0, 0, VIEW_W, VIEW_H);
    ctx.fillStyle = colors.wave;
    for (let k = 0; k < 5; k++) ctx.fillRect(Math.floor((k * 37 + time / 60) % VIEW_W), 18 + k * 20, 8, 1);
    const sprite = SWIM[Math.floor(time / 180) % SWIM.length];
    const fishTop = VIEW_H - sprite.length - 2;
    drawGrid(sprite, colors.fish, x - sprite[0].length / 2, fishTop);
    for (const item of items) {
      if (item.hook) { ctx.fillStyle = colors.hook; ctx.fillRect(Math.round(item.x) + 1, 0, 1, Math.round(item.y)); drawGrid(HOOK, { K: colors.hook }, item.x, item.y); }
      else drawGrid(BUBBLE, { Q: colors.bubble }, item.x - 2, item.y);
    }
    showScore();
    return { fishTop, fishW: sprite[0].length, fishH: sprite.length };
  }

  function frame(time) {
    raf = 0;
    if (state !== 'playing') return;
    const dt = Math.min(40, time - (last || time));
    last = time;
    speed += dt * 0.00002;
    spawnIn -= dt;
    if (spawnIn <= 0) {
      items.push({ x: 6 + Math.random() * (VIEW_W - 12), y: -6, hook: !answered && Math.random() < 0.28, v: (0.025 + Math.random() * 0.025) * speed });
      spawnIn = 560 / speed;
    }
    for (const item of items) item.y += item.v * dt;
    const { fishTop, fishW, fishH } = paint(time);
    for (const item of items) {
      const hit = Math.abs(item.x - x) < fishW * 0.4 && item.y + 3 > fishTop && item.y < fishTop + fishH * 0.7;
      if (hit) {
        item.gone = true;
        if (item.hook) { end(); return; }
        score += 1;
      }
      if (item.y > VIEW_H + 6) item.gone = true;
    }
    items = items.filter((item) => !item.gone);
    raf = requestAnimationFrame(frame);
  }

  function saveBest() { if (score > best) { best = score; onBest?.(score); } }

  function end() {
    state = 'over';
    saveBest();
    status.textContent = `被老板钓到了！这局 ${score} 个泡泡，最好 ${best}。点一下再来。`;
  }

  function start() {
    if (state === 'playing') return;
    state = 'playing';
    items = [];
    score = 0;
    speed = 1;
    last = 0;
    spawnIn = 0;
    status.textContent = answered ? '回答已经好了，随便玩玩。' : '接泡泡，躲鱼钩。';
    fit();
    raf = requestAnimationFrame(frame);
  }

  function steer(event) {
    const rect = canvas.getBoundingClientRect();
    if (rect.width) x = Math.max(8, Math.min(VIEW_W - 8, ((event.clientX - rect.left) / rect.width) * VIEW_W));
  }
  canvas.addEventListener('pointerdown', (event) => { steer(event); canvas.setPointerCapture?.(event.pointerId); if (state !== 'playing') start(); });
  canvas.addEventListener('pointermove', steer);
  if (ctx) { ctx.imageSmoothingEnabled = false; paint(0); }

  return {
    node,
    /** The answer finished: no more hooks, tell the player, close a few seconds later. */
    answerReady() {
      if (answered) return;
      answered = true;
      items = items.filter((item) => !item.hook);
      status.textContent = `回答好了，回去干活。${score ? `这局 ${score} 个泡泡。` : ''}`;
      closeTimer = setTimeout(() => onDone?.(), 3000);
    },
    stop() {
      clearTimeout(closeTimer);
      if (raf) cancelAnimationFrame(raf);
      raf = 0;
      resize?.disconnect();
      if (state === 'playing') saveBest();
      state = 'over';
    },
  };
}
