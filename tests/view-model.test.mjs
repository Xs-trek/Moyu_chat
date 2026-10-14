import { test } from 'node:test';
import assert from 'node:assert/strict';
import { installDom } from './dom-shim.mjs';

installDom();
const vm = await import('../src/view-model.js');

test('message times show the clock today, the date before that and the year once it changes', () => {
  const now = new Date(2026, 9, 14, 15, 30);
  assert.equal(vm.messageTime(new Date(2026, 9, 14, 9, 5).toISOString(), now), '09:05');
  assert.equal(vm.messageTime(new Date(2026, 9, 12, 21, 7).toISOString(), now), '10月12日 21:07');
  assert.equal(vm.messageTime(new Date(2025, 11, 31, 23, 59).toISOString(), now), '2025年12月31日 23:59');
  assert.equal(vm.messageTime('', now), '');
  assert.equal(vm.messageTime('not a date', now), '');
});

test('the session preview is one plain line without Markdown marks', () => {
  assert.equal(vm.sessionPreview({ preview: '## 标题\n\n- **加粗** 的 `code`\n> 引用 [链接](https://x.test)' }), '标题 加粗 的 code 引用 链接');
  assert.equal(vm.sessionPreview({ preview: '```js\nconst a = 1;\n```' }), 'const a = 1;');
  assert.equal(vm.sessionPreview({}), '');
  const long = vm.sessionPreview({ preview: '字'.repeat(120) });
  assert.equal(long.length, 81);
  assert.ok(long.endsWith('…'));
});

test('models are grouped by account in view order, skipping accounts without a key and disabled models', () => {
  const view = { providers: [
    { providerId: 'a', displayName: 'A', hasCredential: true, models: [{ modelProfileId: 'a1' }, { modelProfileId: 'a2', enabled: false }] },
    { providerId: 'b', displayName: 'B', hasCredential: false, models: [{ modelProfileId: 'b1' }] },
    { providerId: 'c', displayName: 'C', hasCredential: true, models: [{ modelProfileId: 'c1' }, { modelProfileId: 'c2' }] },
  ] };
  assert.deepEqual(vm.modelGroups(view).map((g) => [g.provider.providerId, g.models.map((m) => m.modelProfileId)]), [['a', ['a1']], ['c', ['c1', 'c2']]]);
});

test('context fill uses the last main call against the usable budget and warns from 80%', () => {
  const usage = (extra) => ({ kind: 'usage', inputTokens: 90000, ...extra });
  const session = (messages, budget = 100000) => ({ contextBudgetTokens: budget, messages });
  assert.equal(vm.contextFill(session([])), null);
  assert.equal(vm.contextFill(session([usage({ lastMainInputTokens: 40000 })], 0)), null);
  const fill = vm.contextFill(session([usage({ lastMainInputTokens: 30000 }), usage({ lastMainInputTokens: 81000 })]));
  assert.deepEqual([fill.percent, fill.warn], [81, true]);
  const estimated = vm.contextFill(session([usage({ context: { estimatedInputTokens: 12000 } })]));
  assert.deepEqual([estimated.used, estimated.percent, estimated.warn], [12000, 12, false]);
  assert.equal(vm.contextFill(session([{ ...usage({ lastMainInputTokens: 5000 }), inherited: true }])), null, 'inherited usage belongs to another session');
});

test('retry is offered only for an idle session that can send', () => {
  assert.equal(vm.retrySeq({ retryLocalSeq: 3, canSend: true }), 3);
  assert.equal(vm.retrySeq({ retryLocalSeq: 3, canSend: false }), null);
  assert.equal(vm.retrySeq({ canSend: true }), null);
});
