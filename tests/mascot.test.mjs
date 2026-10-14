import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDom } from './dom-shim.mjs';

installDom();
const sprite = await import('../src/mascot-sprite.js');
const director = await import('../src/mascot-director.js');
const vm = await import('../src/view-model.js');

const HEAD = [17, 11];

test('every frame of every pose composes on the canvas with known inks only', () => {
  for (const [id, pose] of Object.entries(sprite.POSES)) {
    assert.ok(pose.frames.length > 0, id);
    assert.ok(sprite.poseDuration(id) > 0, id);
    for (const season of ['', 'cny', 'moon']) for (const frame of pose.frames) {
      const grid = sprite.composeFrame(frame, season);
      assert.equal(grid.length, sprite.H);
      assert.ok(grid.every((row) => row.length === sprite.W));
      const inks = new Set(grid.flat().filter((c) => c !== '.'));
      for (const ink of inks) assert.ok(sprite.INK[ink], `${id}: unknown ink ${ink}`);
      assert.ok(grid.flat().filter((c) => c === 'a').length > 80, `${id}: the body must stay visible`);
    }
  }
});

test('accessories stay inside the frame canvas, so none is silently clipped', () => {
  for (const [id, pose] of Object.entries(sprite.POSES)) for (const frame of pose.frames) for (const [acc, dx, dy] of frame.acc || []) {
    const map = sprite.ACC[acc];
    assert.ok(map, `${id}: unknown accessory ${acc}`);
    const x = HEAD[0] + dx + (frame.x || 0);
    const y = HEAD[1] + dy + (frame.y || 0);
    assert.ok(x >= 0 && y >= 0 && x + Math.max(...map.map((r) => r.length)) <= sprite.W && y + map.length <= sprite.H, `${id}: ${acc} at ${x},${y}`);
  }
});

test('turning round and going belly up mirror around the body, not the canvas', () => {
  const box = (grid) => {
    let x0 = Infinity; let x1 = -1; let y0 = Infinity; let y1 = -1;
    grid.forEach((row, y) => row.forEach((c, x) => { if (c === 'a') { x0 = Math.min(x0, x); x1 = Math.max(x1, x); y0 = Math.min(y0, y); y1 = Math.max(y1, y); } }));
    return { cx: (x0 + x1) / 2, cy: (y0 + y1) / 2 };
  };
  const rest = box(sprite.composeFrame({ d: 0 }));
  const turned = box(sprite.composeFrame({ d: 0, mirror: true }));
  const flipped = box(sprite.composeFrame({ d: 0, flip: true }));
  assert.ok(Math.abs(rest.cx - turned.cx) <= 1, 'mirror keeps the body in place');
  assert.ok(Math.abs(rest.cy - flipped.cy) <= 1, 'belly up keeps the body in place');
});

test('a pose strip is one rect per horizontal run, with ink classes', () => {
  const strip = sprite.buildStrip('idle', '', 'moyu-strip-test');
  assert.ok(strip.children.length > 7 * 10);
  assert.ok([...strip.children].every((rect) => /^ink-[a-z]+$/.test(rect.getAttribute('class'))));
});

test('the perch follows the live stage and sweats through very long answers', () => {
  assert.equal(director.perchPose('write', 120), 'write');
  assert.equal(director.perchPose('write', 3200), 'sweat');
  assert.equal(director.perchPose('search', 0), 'search');
  assert.equal(director.perchPose('idle', 0), 'idle');
});

test('the koi is rolled once per launch and kept for the rest of the day', () => {
  const day = director.today(new Date(2026, 9, 14, 9));
  assert.equal(day, '2026-10-14');
  assert.equal(director.rollKoi('', new Date(2026, 9, 14, 9), () => 0), day, 'a lucky roll saves today');
  assert.equal(document.documentElement.dataset.koi, 'on');
  assert.equal(director.rollKoi('', new Date(2026, 9, 14, 9), () => 0), null, 'only one roll per launch');
});

test('mascot helpers: document names, running time, how a turn ended, the live stage', () => {
  assert.match(vm.docName('s-1'), /\.docx$/);
  assert.equal(vm.docName('s-1'), vm.docName('s-1'));
  assert.equal(vm.sessionTitle({ localSessionId: 's-1', title: '真实标题' }, true), vm.docName('s-1'));
  assert.deepEqual(vm.mascotState({}), { disguise: false, gameBest: 0, koiDay: '' });
  const now = Date.parse('2026-10-14T10:00:30Z');
  const running = { state: 'running', messages: [{ kind: 'message', role: 'user', createdAt: '2026-10-14T10:00:00Z' }, { kind: 'message', role: 'assistant', streaming: true, streamStage: 'output', text: 'abc' }] };
  assert.equal(vm.runningSeconds(running, now), 30);
  assert.equal(vm.runningSeconds({ ...running, state: 'completed' }, now), 0);
  assert.deepEqual(vm.liveStage(running), { pose: 'write', chars: 3 });
  assert.equal(vm.liveStage({ state: 'running', messages: [], webPreparingStage: 'web_search' }).pose, 'search');
  assert.equal(vm.settleKind({ state: 'completed', messages: [{ kind: 'message', role: 'assistant', incomplete: true, terminalCode: 'interrupted' }] }), 'interrupted');
  assert.equal(vm.settleKind({ state: 'failed', messages: [] }), 'failed');
  assert.equal(vm.settleKind({ state: 'completed', messages: [{ kind: 'message', role: 'assistant', text: 'ok' }] }), 'done');
});
