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

test('a repair the re-check did not accept is offered beside the kept answer', () => {
  const state = vm.verificationState({ status: 'valid', verdict: 'fail', coverageComplete: true, action: 'abstain', repairRejected: true, repairCandidateText: '改写版' });
  assert.equal(state.label, '智能校验 · 修正未采用');
  assert.equal(state.repairCandidateText, '改写版');
  assert.equal(state.preRepairText, '');
  assert.equal(vm.verificationState({ status: 'valid', repairCandidateText: 'x' }).repairCandidateText, '');
  const view = vm.traceView({ status: 'ok', spans: [
    { stage: 'repair', status: 'ok', detail: { repaired: false, rejected: true } },
    { stage: 'verify', status: 'ok', detail: { verdict: 'fail', action: 'abstain', repairRejected: true } },
  ], config: {} });
  assert.equal(view.rows[0].note, '改写版没通过复查，保留原回答');
  assert.match(view.rows[1].note, /修正未采用/);
});

test('trace names on-demand Web, history recall and a Router call without a result', () => {
  const view = vm.traceView({ status: 'ok', spans: [
    { stage: 'tools', status: 'ok', detail: { webExecution: 'none', webOnDemand: true, recall: true, clientTools: 'web_search,web_fetch,history_recall' } },
    { stage: 'generate', status: 'ok', detail: { rounds: 2, clientToolBatches: 1, usedClientWeb: true, recalls: 1 } },
    { stage: 'route', status: 'ok', detail: { routed: false, stored: false } },
  ], config: {} });
  assert.match(view.rows[0].note, /按需联网 · 可回查原文/);
  assert.match(view.rows[1].note, /自行联网 · 回查 1 次/);
  assert.equal(view.rows[2].note, '路由没有结果，这一轮不分类');
  const modelWeb = vm.traceView({ status: 'ok', spans: [{ stage: 'tools', status: 'ok', detail: { webExecution: 'client_tools', modelWeb: true, conversationSearch: true } }], config: {} });
  assert.match(modelWeb.rows[0].note, /工具调用联网 · 模型自行决定联网 · 可搜其他会话/);
});

test('helper switches: on unless the native view lists them, labels for what a turn had off', () => {
  assert.deepEqual(vm.helperSwitches({ ui: {} }).map((h) => h.id), ['router', 'compaction', 'recall', 'webPlan', 'verify', 'subagent']);
  assert.deepEqual(vm.helperSwitches({ ui: { helpersOff: ['router'] } }).map((h) => h.on), [false, true, true, true, true, true]);
  const rows = vm.helperSwitches({ ui: { helpersOff: ['verify'] } });
  assert.equal(rows.find((h) => h.id === 'verify').on, false);
  assert.equal(vm.helpersOffLabel([]), '');
  assert.equal(vm.helpersOffLabel(['router', 'verify']), '已关闭：路由、校验与修正');
});

test('a retried turn names its attempt and the outcome of the one before', () => {
  const view = vm.traceView({
    status: 'ok', terminalCode: '', spans: [{ stage: 'verify', status: 'skipped', reason: 'user_off' }],
    config: { attempt: 2, retryOf: 't1', helpersOff: ['verify'] },
    previousAttempt: { turnId: 't1', status: 'failed', terminalCode: 'http_429', usage: { cost: { amount: 0.002, currency: 'USD' } } },
  });
  assert.equal(view.rows[0].note, '已在设置中关闭');
  assert.deepEqual(view.notes, ['第 2 次尝试，上一次：失败 · HTTP 429 · $0.0020', '已关闭：校验与修正']);
  assert.deepEqual(vm.traceView({ status: 'ok', spans: [], config: {} }).notes, []);
});

test('statistics show archived turns and helper-switch groups', () => {
  const [all, off] = vm.statsView({ rows: [
    { group: { helpers: 'all_on' }, turns: 3, ok: 2, failed: 1, cancelled: 0, archived: 1 },
    { group: { model: 'M', helpers: 'router+webPlan' }, turns: 1, ok: 1, failed: 0, cancelled: 0, archived: 0 },
  ] });
  assert.equal(all.title, '辅助全开');
  assert.equal(all.cells[0][1], '3 · 失败 1 · 已删除会话 1');
  assert.equal(off.title, 'M · 已关闭：路由、联网规划');
  assert.equal(off.cells[0][1], '1');
});

test('a never-sent Chat is listed by its draft; the naming stage is a helper', () => {
  assert.equal(vm.sessionTitle({ blank: true, draft: '半句话', title: '新 Chat' }), '草稿：半句话');
  assert.equal(vm.sessionTitle({ blank: true, draft: '' }), '草稿：图片');
  assert.equal(vm.sessionTitle({ title: '天空为何是蓝色' }), '天空为何是蓝色');
  const view = vm.traceView({ status: 'ok', config: {}, spans: [
    { stage: 'generate', status: 'ok', durationMs: 900, usage: { calls: 1, inputTokens: 90, outputTokens: 10 } },
    { stage: 'title', status: 'ok', durationMs: 300, usage: { calls: 1, inputTokens: 30, outputTokens: 6 }, detail: { applied: true, manual: false } },
    { stage: 'title', status: 'failed', durationMs: 200, reason: 'no_title' },
  ] });
  assert.equal(view.rows[1].label, '命名');
  assert.equal(view.rows[1].note, '已更新会话标题');
  assert.equal(view.rows[2].note, '没有给出可用的标题');
  assert.equal(Math.round(view.helperShare * 1000), Math.round((36 / 136) * 1000));
});

test('a turn waiting for compaction says it is tidying the context (spec C3)', () => {
  assert.deepEqual(vm.stageInfo('compacting'), { label: '正在整理上下文', pose: 'think' });
  assert.equal(vm.stageInfo('unknown-stage').label, '连接模型');
});

test('a verification stopped by the user reads as stopped, and compaction notes say how it went', () => {
  assert.equal(vm.verificationState({ status: 'cancelled' }).label, '智能校验 · 已停止');
  const trace = vm.traceView({ spans: [
    { stage: 'checkpoint', status: 'ok', durationMs: 900, detail: { mode: 'waited', applied: true, coveredItems: 4, waitedMs: 900 } },
    { stage: 'web_plan', status: 'skipped', reason: 'model_decides', durationMs: 0 },
  ] });
  assert.match(trace.rows[0].note, /等待后台压缩 · 覆盖 4 条/);
  assert.equal(trace.rows[1].note, '由回答的模型自己决定');
});

test('versions: "i/n" with the seqs to switch to, the resume target, and versions in the answer comparison (spec D9)', () => {
  assert.deepEqual(vm.messageVersion({ version: { index: 2, count: 3, prevSeq: 4, nextSeq: 9 } }), { label: '2/3', prevSeq: 4, nextSeq: 9 });
  assert.deepEqual(vm.messageVersion({ version: { index: 1, count: 2, nextSeq: 7 } }), { label: '1/2', prevSeq: null, nextSeq: 7 });
  assert.equal(vm.messageVersion({ version: { index: 1, count: 1 } }), null);
  assert.equal(vm.messageVersion({}), null);
  assert.equal(vm.resumeSeq({ canSend: true, resumeLocalSeq: 12 }), 12);
  assert.equal(vm.resumeSeq({ canSend: false, resumeLocalSeq: 12 }), null);
  const view = vm.answersView({ question: 'q', answers: [
    { turnId: 't1', original: true, answerSeq: 2, config: {}, usage: {} },
    { turnId: 't2', original: false, answerSeq: 6, config: {}, usage: {} },
  ] });
  assert.deepEqual(view.items.map((item) => [item.title, item.versionSeq]), [['第 1 次回答', 2], ['第 2 次回答', 6]]);
});

test('helper model: the setting rows and the trace note, including why it was not used (spec D15)', () => {
  const none = vm.helperModelView({ ui: { helperModel: { modelProfileId: '', use: ['title', 'router', 'web_plan'] } } });
  assert.equal(none.id, '');
  assert.deepEqual(none.uses.filter((u) => u.on).map((u) => u.id), ['title', 'router', 'web_plan']);
  const gone = vm.helperModelView({ ui: { helperModel: { modelProfileId: 'm1', missing: true, use: [] } } });
  assert.equal(gone.name, '已删除或停用');
  assert.equal(vm.helperModelNote({}), '');
  assert.equal(vm.helperModelNote({ helperModel: { profileId: 'm1', modelName: 'Flash', use: ['title', 'router'] } }), '辅助模型：Flash（会话命名、路由）');
  assert.equal(vm.helperModelNote({ helperModel: { profileId: 'm1', fallback: 'helper_model_no_key' } }), '辅助模型没用上（它的账号没有 API Key），辅助调用用了会话的模型');
});

test('capability probe line: each check with its verdict, warn when something could not be judged (spec D19)', () => {
  assert.equal(vm.capabilityProbeLine({}), null);
  const line = vm.capabilityProbeLine({ capabilityProbe: { checkedAt: new Date().toISOString(), checks: [{ field: 'inputModalities', verdict: 'no' }, { field: 'clientToolCalling', verdict: 'unknown' }] } });
  assert.ok(line.text.startsWith('实测 · 图片输入 不支持 · 工具调用 无法判断'));
  assert.equal(line.tone, 'warn');
});
