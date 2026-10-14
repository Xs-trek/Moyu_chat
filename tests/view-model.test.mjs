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

test('statistics rows title by their grouping and show medians, cache rate and cost per currency', () => {
  const [byProtocol] = vm.statsView({ rows: [{ group: { protocol: 'anthropic-messages' }, turns: 3, failed: 0, cancelled: 0, medianDurationMs: 2500, cacheHitRate: 0.5,
    cost: { USD: 0.0123, CNY: 2 }, partialCostTurns: 1, routed: 0, webUsed: 0, verified: 0, repaired: 0, ratedUp: 0, ratedDown: 0 }] });
  assert.equal(byProtocol.title, 'Messages');
  assert.equal(byProtocol.mode, '');
  const cells = Object.fromEntries(byProtocol.cells);
  assert.equal(cells['耗时中位'], '2.50s');
  assert.equal(cells['缓存命中'], '50%');
  assert.equal(cells['费用（估算）'], '$0.012 + ¥2.00 起');
  const [legacy] = vm.statsView({ rows: [{ model: 'M', contextMode: 'smart', turns: 1 }] });
  assert.deepEqual([legacy.title, legacy.mode], ['M', '智能']);
});

test('money keeps small amounts visible', () => {
  assert.equal(vm.money(0.00042), '$0.0004');
  assert.equal(vm.money(1.5, 'CNY'), '¥1.50');
  assert.equal(vm.money(250, 'USD'), '$250');
});

test('account key, budget, balance and probe lines', () => {
  assert.equal(vm.keyStatus({ hasCredential: false }).label, '填写 API Key');
  assert.deepEqual([vm.keyStatus({ hasCredential: true, keyStatus: 'invalid' }).label, vm.keyStatus({ hasCredential: true, keyStatus: 'invalid' }).tone], ['Key 无效', 'warn']);
  assert.equal(vm.keyStatus({ hasCredential: true }).label, 'Key 未验证');
  assert.equal(vm.budgetLine({ budget: {} }), null);
  const budget = vm.budgetLine({ budget: { currency: 'USD', daily: 1, spentToday: 1.2, monthly: 20, spentMonth: 3 } });
  assert.equal(budget.text, '预算 · 今日 $1.20 / $1.00 · 本月 $3.00 / $20.00');
  assert.equal(budget.reached, true);
  assert.equal(vm.balanceText({ ok: true, balances: [{ currency: 'CNY', total: '86.40' }] }), '余额 ¥86.40');
  assert.match(vm.balanceText({ ok: false, summary: 'API Key 无效' }), /余额查询失败：API Key 无效/);
  assert.equal(vm.probeLine({}), null);
  const now = new Date().toISOString();
  assert.match(vm.probeLine({ probe: { checkedAt: now, ok: true, firstOutputMs: 900, totalMs: 1500, usageReported: false, reasoningSeen: true } }).text, /^测试通过 · 首字 0\.90s · 共 1\.50s · 没报用量 · 有推理文本/);
  assert.equal(vm.probeLine({ probe: { checkedAt: now, ok: false, summary: '连接超时' } }).tone, 'warn');
});

test('a repaired answer keeps the first version for comparison', () => {
  assert.equal(vm.verificationState({ status: 'valid', repaired: true, preRepairText: '旧答案' }).preRepairText, '旧答案');
  assert.equal(vm.verificationState({ status: 'valid', repaired: false, preRepairText: 'x' }).preRepairText, '');
});
