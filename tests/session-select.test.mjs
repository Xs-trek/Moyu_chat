import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDom } from './dom-shim.mjs';

installDom();
const sel = await import('../src/session-select.js');

const sessions = [
  { localSessionId: 'a', state: 'completed' },
  { localSessionId: 'b', state: 'idle' },
  { localSessionId: 'c', state: 'running' },
];

test('a long press starts selecting with that session ticked; un-ticking the last one leaves selection mode', () => {
  let changes = 0;
  const off = sel.onSelectionChange(() => { changes += 1; });
  sel.startSelection('a');
  assert.ok(sel.selecting());
  assert.deepEqual(sel.selectedIds(), ['a']);
  sel.toggleSelected('b');
  assert.deepEqual(sel.selectedIds().sort(), ['a', 'b']);
  sel.toggleSelected('a');
  sel.toggleSelected('b');
  assert.equal(sel.selecting(), false);
  assert.equal(changes, 4);
  off();
});

test('select all skips sessions with a turn in flight, and Back-style clearing reports whether it closed anything', () => {
  sel.startSelection('a');
  sel.selectAll(sessions.filter(sel.selectable).map((s) => s.localSessionId));
  assert.deepEqual(sel.selectedIds().sort(), ['a', 'b']);
  assert.equal(sel.selectable(sessions[2]), false);
  assert.equal(sel.clearSelection(), true);
  assert.equal(sel.clearSelection(), false);
});

test('sessions that disappear from the view drop out of the selection', () => {
  sel.startSelection('a');
  sel.toggleSelected('b');
  sel.pruneSelection([{ localSessionId: 'b' }]);
  assert.deepEqual(sel.selectedIds(), ['b']);
  sel.pruneSelection([]);
  assert.equal(sel.selecting(), false);
});

test('the selection bar counts the ticked sessions and offers select-all until everything selectable is ticked', () => {
  sel.startSelection('a');
  let bar = sel.selectionBar(sessions);
  assert.equal(bar.querySelector('.select-count').textContent, '已选 1 项');
  assert.ok(bar.querySelector('[data-action="select-all"]'));
  sel.toggleSelected('b');
  bar = sel.selectionBar(sessions);
  assert.ok(bar.querySelector('[data-action="select-none"]'), 'a and b are all the selectable sessions');
  assert.equal(bar.querySelector('[data-action="select-delete"]').disabled, false);
  sel.clearSelection();
});
