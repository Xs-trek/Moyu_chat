// The one adapter between the native AppViewModel (contract/moyu-chat-contract.d.ts) and the renderers.
// Renderers never interpret raw native fields themselves; when the native projection changes (orchestration,
// usage accounting, verification), adapt it here and keep the renderers untouched.

export const CONTEXT_LABELS = { minimal: '极简', standard: '标准', auto: '标准', smart: '智能' };
export const WEB_LABELS = { off: '关闭', auto: '自动', on: '开启' };

const STAGES = {
  preparing: ['整理上下文', 'think'],
  connecting: ['连接模型', 'idle'],
  connected: ['已连接 · 等待模型', 'idle'],
  retrying: ['服务繁忙，稍后自动重试', 'idle'],
  queued: ['排队中 · 路线并发已满', 'idle'],
  web_planning: ['规划联网', 'think'],
  web_search: ['搜索网页', 'search'],
  web_fetch: ['读取网页', 'search'],
  tool: ['执行工具', 'search'],
  reasoning: ['推理中', 'think'],
  output: ['正在写', 'write'],
  verifying: ['校验回答', 'verify'],
  repairing: ['修正回答', 'verify'],
};

/** Label and mascot pose for a streaming stage. Unknown stages fall back to "connecting". */
export function stageInfo(stage) {
  const [label, pose] = STAGES[stage] || STAGES.connecting;
  return { label, pose };
}

/** Session-level activity label shown in the conversation header; empty when idle. */
export function busyLabel(session) {
  if (!session) return '';
  if (session.postProcessingStage === 'repairing') return '修正回答';
  if (session.postProcessingStage === 'verifying') return '校验回答';
  if (session.webPreparingStage === 'web_planning') return '规划联网';
  if (session.webPreparingStage === 'web_fetch') return '读取网页';
  if (session.webPreparingStage === 'web_search') return '搜索网页';
  if (session.contextPreparing) return '整理上下文';
  return session.state === 'running' ? '生成中' : '';
}

export function composerPlaceholder(session) {
  if (session.canSend) return imageInput(session) ? '输入消息，或添加图片' : '输入消息';
  if (session.contextPreparing) return '正在整理上下文';
  if (session.webPreparingStage) return '正在联网检索';
  return '等待当前回合结束';
}

/** Native resolves "system" from the Android night mode; the media query is only a pre-view fallback. */
export function resolvedTheme(view) {
  const configured = view?.appearance?.theme || 'system';
  if (configured === 'dark' || configured === 'light') return configured;
  const native = view?.appearance?.resolvedTheme;
  if (native === 'dark' || native === 'light') return native;
  return window.matchMedia?.('(prefers-color-scheme: dark)')?.matches ? 'dark' : 'light';
}

export function appearance(view) {
  const tone = ['sage', 'warm', 'neutral'].includes(view?.appearance?.tone) ? view.appearance.tone : 'sage';
  return { theme: view?.appearance?.theme || 'system', resolved: resolvedTheme(view), tone, delight: view?.appearance?.delight !== false };
}

export function contextLabel(mode) { return CONTEXT_LABELS[String(mode || 'standard').trim()] || String(mode || ''); }

function list(value) { return String(value || '').split(',').map((x) => x.trim()).filter(Boolean); }
export function imageInput(session) { return list(session?.inputModalities).includes('image'); }

export function effortLabel(value) {
  if (value === 'none') return '关闭';
  if (!value || value === 'default') return '默认';
  return { minimal: '最低', low: '低', medium: '中', high: '高', xhigh: '极高', max: '最高' }[value] || value;
}

/** Reasoning choices the deployment declares; 'default' always leads. Empty when reasoning is not declared. */
export function reasoningChoices(session) {
  const levels = list(session?.reasoningLevels);
  return levels.length ? ['default', ...levels.filter((x) => x !== 'default')] : [];
}

/** One-line description of the next turn's settings for the composer chip. */
export function turnSummary(session) {
  const parts = [shortModelName(session.modelName || session.model)];
  if (reasoningChoices(session).length) parts.push(effortLabel(session.reasoningEffort));
  parts.push(`联网${WEB_LABELS[session.webMode] || '自动'}`);
  return parts;
}

export function shortModelName(name) {
  const text = String(name || '').trim();
  return text.replace(/^Claude\s+/i, '').trim() || text;
}

export function modelChoices(view) {
  const choices = [];
  for (const provider of view.providers || []) {
    if (!provider?.hasCredential) continue;
    for (const model of provider.models || []) if (model.enabled !== false) choices.push({ provider, model });
  }
  return choices;
}

/** Capabilities exactly as the session's frozen deployment declares them (protocol ≠ capability). */
/** Enabled models grouped by account, in the order the native view lists them. */
export function modelGroups(view) {
  const groups = [];
  for (const choice of modelChoices(view)) {
    const last = groups.at(-1);
    if (last && last.provider === choice.provider) last.models.push(choice.model);
    else groups.push({ provider: choice.provider, models: [choice.model] });
  }
  return groups;
}

/** Native and dialog agree on this limit (ChatCoordinator.renameSession). */
export const SESSION_TITLE_MAX = 200;
/** Native limit (TurnConfig.MAX_CUSTOM_INSTRUCTION_CHARS). */
export const CUSTOM_INSTRUCTION_MAX = 4000;

/** Message time: just the clock today, the date before that, and the year once it is not this year. */
export function messageTime(value, now = new Date()) {
  const date = new Date(value);
  if (!value || Number.isNaN(date.getTime())) return '';
  const pad = (n) => String(n).padStart(2, '0');
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`;
  if (localDay(date) === localDay(now)) return clock;
  const day = `${date.getMonth() + 1}月${date.getDate()}日 ${clock}`;
  return date.getFullYear() === now.getFullYear() ? day : `${date.getFullYear()}年${day}`;
}

/** One plain line for the session list: Markdown marks dropped, whitespace collapsed. */
export function sessionPreview(session) {
  const text = String(session?.preview || '')
    .replace(/```[^\n]*\n?/g, ' ')
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^\s{0,3}(?:#{1,6}\s+|>\s?|[-*+]\s+(?:\[[ xX]\]\s+)?|\d{1,9}[.)]\s+)/gm, '')
    .replace(/(\*\*|__|~~|`)/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return text.length > 80 ? `${text.slice(0, 80)}…` : text;
}

/**
 * How full the context is: what the last main call actually sent (estimated when the provider reported nothing)
 * against the session's usable input budget. Null before the first reply.
 */
export function contextFill(session) {
  const budget = Number(session?.contextBudgetTokens);
  if (!(budget > 0)) return null;
  const items = session?.messages || [];
  for (let i = items.length - 1; i >= 0; i -= 1) {
    const item = items[i];
    if (item?.kind !== 'usage' || item.inherited) continue;
    const used = count(item.lastMainInputTokens) ?? count(item.context?.estimatedInputTokens);
    if (used === null) continue;
    const ratio = used / budget;
    return { used, budget, ratio, percent: Math.min(999, Math.round(ratio * 100)), warn: ratio >= 0.8 };
  }
  return null;
}

/** The failed or unfinished question the composer can send again, when the session is idle. */
export function retrySeq(session) {
  const seq = Number(session?.retryLocalSeq);
  return session?.canSend && Number.isInteger(seq) && seq > 0 ? seq : null;
}

const KEY_STATUS = {
  valid: ['Key 可用', 'good'], invalid: ['Key 无效', 'warn'], unverified: ['Key 未验证', 'quiet'], error: ['验证失败', 'warn'], unknown: ['Key 未验证', 'quiet'],
};

/** Account key chip: whether a key is stored and what the last check (or turn) said about it. */
export function keyStatus(provider) {
  if (!provider?.hasCredential) return { label: '填写 API Key', tone: 'warn', checkedAt: '' };
  const [label, tone] = KEY_STATUS[provider.keyStatus] || KEY_STATUS.unknown;
  return { label, tone, checkedAt: provider.keyCheckedAt || '' };
}

/** "今日 $0.12 / $5.00 · 本月 …"; null without a budget. Reached limits are flagged. */
export function budgetLine(provider) {
  const b = provider?.budget;
  if (!b || (b.daily === undefined && b.monthly === undefined)) return null;
  const parts = [];
  let reached = false;
  if (b.daily !== undefined) { parts.push(`今日 ${money(b.spentToday || 0, b.currency)} / ${money(b.daily, b.currency)}`); reached ||= (b.spentToday || 0) >= b.daily; }
  if (b.monthly !== undefined) { parts.push(`本月 ${money(b.spentMonth || 0, b.currency)} / ${money(b.monthly, b.currency)}`); reached ||= (b.spentMonth || 0) >= b.monthly; }
  return { text: `预算 · ${parts.join(' · ')}`, reached };
}

/** Last connection test of a model, as one line; null when never tested. */
export function probeLine(model) {
  const p = model?.probe;
  if (!p || !p.checkedAt) return null;
  const when = messageTime(p.checkedAt);
  if (!p.ok) return { text: `测试失败 · ${p.summary || p.code || '未知原因'} · ${when}`, tone: 'warn' };
  const parts = ['测试通过'];
  if (p.firstOutputMs !== undefined) parts.push(`首字 ${secs(p.firstOutputMs)}`);
  if (p.totalMs !== undefined) parts.push(`共 ${secs(p.totalMs)}`);
  parts.push(p.usageReported ? '有用量' : '没报用量');
  if (p.reasoningSeen) parts.push('有推理文本');
  parts.push(when);
  return { text: parts.join(' · '), tone: 'good' };
}

/** Balance result of provider.balance.get as one line. */
export function balanceText(data) {
  if (!data) return '';
  if (!data.ok) return `余额查询失败：${data.summary || data.code || ''}`;
  const list = (data.balances || []).map((b) => `${b.currency === 'CNY' ? '¥' : b.currency === 'USD' ? '$' : `${b.currency} `}${b.total}`);
  return `余额 ${list.length ? list.join(' · ') : '—'}${data.available === false ? '（不可用）' : ''}`;
}

export function capabilities(session) {
  const input = list(session?.inputModalities);
  const output = list(session?.outputModalities);
  const levels = list(session?.reasoningLevels).filter((x) => x !== 'default');
  return [
    { label: '图片输入', on: input.includes('image') },
    { label: levels.length ? `推理 ${levels.map(effortLabel).join('/')}` : '推理档位', on: levels.length > 0 },
    { label: '原生联网', on: list(session?.nativeWebCapabilities).length > 0 },
    { label: '工具调用', on: Boolean(session?.clientToolCalling) && session.clientToolCalling !== 'none' },
    { label: '续接', on: session?.continuationMode === 'native' },
    { label: '图片输出', on: output.includes('image') },
  ];
}

export function timelineKey(item) {
  const seq = Number(item?.localSeq);
  if (Number.isInteger(seq) && seq > 0) return `${item.kind}:${item.inherited ? 'inherited' : 'local'}:${seq}`;
  return `${item?.kind || 'item'}:${item?.role || ''}:${item?.createdAt || ''}`;
}

/** Usage is durable as its own row; the UI attaches it to the preceding assistant reply of the same turn. */
export function timelineProjection(items = []) {
  const out = [];
  for (const raw of items || []) {
    if (!raw) continue;
    if (raw.kind === 'usage') {
      for (let i = out.length - 1; i >= 0; i -= 1) {
        if (out[i]?.kind === 'message' && out[i]?.role === 'user') break;
        if (out[i]?.kind === 'message' && out[i]?.role === 'assistant') { out[i] = { ...out[i], __usage: raw }; break; }
      }
      continue;
    }
    out.push({ ...raw });
  }
  return out;
}

export function userTurns(items = []) {
  return timelineProjection(items)
    .filter((item) => item?.kind === 'message' && item?.role === 'user' && Number(item.localSeq) > 0)
    .map((item, index) => ({ key: timelineKey(item), number: index + 1, preview: promptPreview(item) }));
}

function promptPreview(item) {
  let text = String(item?.text || '').replace(/\s+/g, ' ').trim();
  if (!text && (item?.attachments || []).some((a) => a?.kind === 'image')) text = '图片消息';
  if (!text) text = '（空消息）';
  return text.length > 48 ? `${text.slice(0, 48)}…` : text;
}

function count(value) { return Number.isFinite(Number(value)) && value !== null && value !== '' ? Math.max(0, Number(value)) : null; }

export function compact(n) {
  if (n === null || n === undefined) return '—';
  if (n >= 1e6) return `${(n / 1e6).toFixed(n >= 1e7 ? 0 : 1)}M`;
  if (n >= 1e4) return `${(n / 1e3).toFixed(n >= 1e5 ? 0 : 1)}k`;
  if (n >= 1e3) return `${(n / 1e3).toFixed(1)}k`;
  return String(Math.round(n));
}
export function full(n) { return n === null || n === undefined ? '—' : Math.round(n).toLocaleString(); }
export function secs(ms) { return ms === null || ms === undefined ? '—' : `${(ms / 1000).toFixed(ms < 10000 ? 2 : 1)}s`; }

const STAGE_LABELS = { accept: '接收', context: '上下文', route: '路由', checkpoint: '压缩', web_plan: '联网规划', tools: '工具选择', web_retrieve: '联网检索', generate: '生成', verify: '校验', repair: '修正', subagent: '子任务' };
const STATUS_LABELS = { ok: '完成', skipped: '跳过', failed: '失败', cancelled: '已停止', open: '未结束' };
const SKIP_REASONS = { mode_off: '当前模式未启用', task_profile_cached: '沿用本会话的路由结果', within_budget: '上下文未超预算', web_not_needed: '本轮不联网', provider_hosted: '由模型侧联网', client_tools: '由工具调用联网', harness_off: '当前预设未启用', not_needed: '本轮无需校验', user_off: '已在设置中关闭' };
const WEB_EXECUTION = { host: '本机检索', provider: '模型侧联网', client_tools: '工具调用联网', none: '不联网' };
const VERDICTS = { pass: '通过', fail: '不通过', partial: '部分通过' };
const ACTIONS = { accept: '采纳', repair: '需修正', abstain: '存疑' };
/** Stages that exist only to help the main call; their share of tokens is what "is this helper still worth it" weighs. */
const HELPER_STAGES = new Set(['route', 'checkpoint', 'web_plan', 'verify', 'repair', 'subagent']);

const CALL_FAILURES = { timeout: '调用超时', cancelled: '调用被停止', response_incomplete: '输出达到上限', empty_stream: '没有返回内容', network_error: '网络错误', stream_timeout: '流中断' };

/** Why a helper's model call failed, as shown after the stage note. */
export function callFailureLabel(code) {
  const value = String(code || '');
  return value ? `调用失败：${failureCodeLabel(value)}` : '';
}

function failureCodeLabel(code) {
  const http = String(code).match(/^http_(\d{3})$/);
  return CALL_FAILURES[code] || (http ? `HTTP ${http[1]}` : String(code));
}

/** Helper modules the user can switch off globally (HelperSwitches on the native side), with settings copy. */
export const HELPER_SWITCHES = [
  ['router', '路由', '智能模式下，第一轮先判断任务类型，用来决定要不要校验。关闭后不分类，也不用这个会话之前的分类结果。'],
  ['compaction', '历史压缩', '标准 / 智能模式下，上下文用到一半时，让模型把较早的历史写成摘要。关闭后不再写新摘要（已有的继续用），超长时直接舍弃最早的历史。'],
  ['webPlan', '联网规划', '联网为“自动”时，让模型判断要不要搜、搜什么。关闭后只按规则判断，不调用模型。'],
  ['verify', '校验与修正', '智能模式下，回答后让模型再检查一遍，必要时改写。'],
  ['subagent', '子任务', '模型支持工具调用时，允许它把子问题交给同一模型的独立调用。'],
];
const HELPER_NAMES = Object.fromEntries(HELPER_SWITCHES.map(([id, name]) => [id, name]));

/** Settings rows for the helper switches: every helper is on unless the native view lists it as off. */
export function helperSwitches(view) {
  const off = new Set(Array.isArray(view?.ui?.helpersOff) ? view.ui.helpersOff : []);
  return HELPER_SWITCHES.map(([id, label, description]) => ({ id, label, description, on: !off.has(id) }));
}

/** "已关闭：路由、校验与修正" for a turn config's helpersOff; '' when every helper was on. */
export function helpersOffLabel(ids) {
  const names = (Array.isArray(ids) ? ids : []).map((id) => HELPER_NAMES[id] || id);
  return names.length ? `已关闭：${names.join('、')}` : '';
}

function stageNote(span) {
  const note = stageSummary(span);
  const failure = callFailureLabel(span.detail?.callFailure);
  return [note, failure].filter(Boolean).join(' · ');
}

function stageSummary(span) {
  const d = span.detail || {};
  switch (span.stage) {
    case 'accept': return [contextLabel(d.effectiveContextMode || d.contextMode), d.harnessPreset].filter(Boolean).join(' · ');
    case 'context': return `${d.messages ?? '—'} 条消息${d.truncated ? ' · 已截断' : ''}${d.compactionDue ? ' · 需要压缩' : ''}`;
    case 'route': return d.routed === false ? '路由未返回结果，按通用问答处理' : `${d.domain || '—'} / ${d.taskType || '—'}${Number.isFinite(d.confidence) ? ` · 置信 ${Number(d.confidence).toFixed(2)}` : ''}`;
    case 'checkpoint': return d.updated ? `已更新 · 覆盖 ${d.coveredItems} 条` : '未更新';
    case 'web_plan': {
      const queries = Array.isArray(d.queries) ? d.queries : [];
      return `${d.useWeb ? '联网' : '不联网'}${d.useWeb && queries.length ? ` · ${queries.length} 个查询` : ''}${d.plannerCalled ? ' · 模型规划' : ' · 规则判断'}`;
    }
    case 'tools': return [WEB_EXECUTION[d.webExecution] || '', d.clientTools ? `工具 ${d.clientTools}` : '', d.schemasDropped ? '工具说明放不下已省略' : ''].filter(Boolean).join(' · ');
    case 'web_retrieve': return `${d.sources ?? 0} 个来源 · ${full(d.evidenceChars ?? 0)} 字${d.backend ? ` · ${d.backend}` : ''}`;
    case 'generate': return `${d.rounds ?? 1} 轮${d.clientToolBatches ? ` · 工具 ${d.clientToolBatches} 次` : ''}${d.subagents ? ` · 子任务 ${d.subagents}` : ''}`;
    case 'verify': return [VERDICTS[d.verdict] || d.status || '', Number.isFinite(d.risk) ? `风险 ${Number(d.risk).toFixed(2)}` : '', d.repaired ? '已修正' : ACTIONS[d.action] || ''].filter(Boolean).join(' · ');
    case 'repair': return d.repaired ? '已改写回答' : '没有产出修正';
    default: return '';
  }
}

/**
 * One turn's pipeline as display rows: what ran, what was skipped and why, how long each stage took
 * relative to the slowest one, and the helpers' share of the turn's tokens.
 */
export function traceView(trace) {
  if (!trace || !Array.isArray(trace.spans)) return null;
  const durations = trace.spans.map((s) => Number(s.durationMs)).filter((ms) => Number.isFinite(ms) && ms > 0);
  const longest = Math.max(1, ...durations);
  let helperTokens = 0, mainTokens = 0;
  const rows = trace.spans.map((span) => {
    const ms = Number(span.durationMs);
    const usage = span.usage || null;
    const tokens = usage ? (count(usage.inputTokens) || 0) + (count(usage.outputTokens) || 0) : 0;
    if (HELPER_STAGES.has(span.stage)) helperTokens += tokens; else mainTokens += tokens;
    const note = span.status === 'skipped' ? (SKIP_REASONS[span.reason] || span.reason || '')
      : span.status === 'ok' ? stageNote(span) : (span.reason || '');
    const calls = usage?.calls || 0;
    return {
      stage: span.stage,
      label: STAGE_LABELS[span.stage] || span.stage,
      status: span.status,
      statusLabel: STATUS_LABELS[span.status] || span.status,
      ms: Number.isFinite(ms) && ms >= 0 ? ms : null,
      share: Number.isFinite(ms) && ms > 0 ? ms / longest : 0,
      usage: calls ? `${calls} 次调用 · ${compact(count(usage.inputTokens))}/${compact(count(usage.outputTokens))}${span.detail?.usageIncomplete ? ' · 未报告完整' : ''}` : '',
      note,
    };
  });
  const total = helperTokens + mainTokens;
  const notes = [];
  const attempt = Number(trace.config?.attempt);
  const previous = trace.previousAttempt;
  if (attempt > 1 || previous) {
    const cost = previous?.usage?.cost && Number.isFinite(Number(previous.usage.cost.amount)) ? money(previous.usage.cost.amount, previous.usage.cost.currency || 'USD') : '';
    const outcome = previous ? [STATUS_LABELS[previous.status] || previous.status, previous.terminalCode ? failureCodeLabel(previous.terminalCode) : '', cost].filter(Boolean).join(' · ') : '';
    notes.push(`第 ${attempt > 1 ? attempt : 2} 次尝试${outcome ? `，上一次：${outcome}` : ''}`);
  }
  const off = helpersOffLabel(trace.config?.helpersOff);
  if (off) notes.push(off);
  return { rows, notes, status: trace.status, statusLabel: STATUS_LABELS[trace.status] || trace.status, terminalCode: trace.terminalCode || '', helperShare: total > 0 && helperTokens > 0 ? helperTokens / total : null };
}

/** Recorded model calls of a traced turn, labelled by the stage that made them ("校验 2" when a stage called twice). */
export function traceCalls(trace) {
  const calls = Array.isArray(trace?.calls) ? trace.calls : [];
  const perStage = {};
  for (const call of calls) perStage[call.stage] = (perStage[call.stage] || 0) + 1;
  const seen = {};
  return calls.map((call) => {
    seen[call.stage] = (seen[call.stage] || 0) + 1;
    const label = `${STAGE_LABELS[call.stage] || call.stage}${perStage[call.stage] > 1 ? ` ${seen[call.stage]}` : ''}`;
    return { callIndex: call.callIndex, label };
  });
}

/** One recorded call as readable parts: pretty request JSON with its URL, raw response text with its HTTP status. */
export function payloadParts(data) {
  const parts = Array.isArray(data?.parts) ? data.parts : [];
  const requests = parts.filter((p) => p.direction === 'request').length;
  let sent = 0, received = 0;
  return parts.map((part) => {
    const request = part.direction === 'request';
    const n = request ? ++sent : ++received;
    let parsed = null;
    try { parsed = JSON.parse(part.body); } catch { parsed = null; }
    let text, meta;
    if (request) {
      meta = parsed?.url ? `POST ${parsed.url}` : '';
      text = parsed?.body ? JSON.stringify(parsed.body, null, 2) : parsed?.bodyText ? String(parsed.bodyText) : String(part.body || '');
    } else {
      meta = parsed && Number.isFinite(parsed.status) && parsed.status > 0 ? `HTTP ${parsed.status}` : '';
      text = parsed && typeof parsed.text === 'string' ? parsed.text : String(part.body || '');
    }
    if (parsed?.truncated) meta = `${meta ? `${meta} · ` : ''}已截断`;
    return { label: `${request ? '请求' : '响应'}${requests > 1 ? ` ${n}` : ''}`, meta, text };
  });
}

/** Every answer to one question, as comparable cards (original first, then re-answers in order). */
export function answersView(group) {
  // Original first, then re-answers in the order they were asked.
  const answers = (Array.isArray(group?.answers) ? group.answers : []).slice().sort((a, b) => Number(Boolean(b.original)) - Number(Boolean(a.original)));
  let rerun = 0;
  return {
    question: String(group?.question || ''),
    items: answers.map((answer) => {
      const config = answer.config || {};
      const u = turnUsage(answer.usage);
      const totals = turnTotals(u);
      const tokens = totals.input !== null || totals.output !== null ? `${compact(totals.input)} → ${compact(totals.output)}` : '';
      const verification = answer.verification || null;
      return {
        sessionId: answer.localSessionId,
        turnId: answer.turnId,
        title: answer.original ? '原回答' : `重答 ${++rerun}`,
        subtitle: [config.modelName || config.model, contextLabel(config.effectiveContextMode || config.contextMode)].filter(Boolean).join(' · '),
        metrics: [u?.firstMs !== null && u?.firstMs !== undefined ? `首字 ${secs(u.firstMs)}` : '', u?.totalMs !== null && u?.totalMs !== undefined ? `总耗 ${secs(u.totalMs)}` : '', tokens].filter(Boolean).join(' · '),
        verdict: verification ? `${VERDICTS[verification.verdict] || verification.verdict || '已校验'}${verification.repaired ? ' · 已修正' : ''}` : '',
        rating: Number(answer.rating) || 0,
        failed: answer.status === 'failed' || answer.status === 'cancelled',
        text: String(answer.text || ''),
      };
    }),
  };
}

/** Statistics rows as cards: one per model × context mode. */
export const PROTOCOL_LABELS = { 'openai-chat-completions': 'Chat Completions', 'openai-responses': 'Responses', 'anthropic-messages': 'Messages' };

/** Statistics groupings the native side accepts (TurnStats.GROUPINGS), with their picker labels. */
export const STATS_GROUPINGS = [['model,mode', '模型 × 模式'], ['model', '模型'], ['protocol', '协议'], ['effort', '推理深度'], ['mode', '模式'], ['model,effort', '模型 × 深度'], ['helpers', '辅助开关'], ['model,helpers', '模型 × 辅助开关']];

/** "$0.0123" / "¥1.20": enough digits that small turns are not rounded to zero. */
export function money(amount, currency = 'USD') {
  const value = Number(amount);
  if (!Number.isFinite(value)) return '—';
  const sign = currency === 'CNY' ? '¥' : currency === 'USD' ? '$' : `${currency} `;
  const digits = value >= 100 ? 0 : value >= 1 ? 2 : value >= 0.01 ? 3 : 4;
  return `${sign}${value.toFixed(digits)}`;
}

function costText(costs) {
  const entries = Object.entries(costs || {});
  return entries.length ? entries.map(([currency, amount]) => money(amount, currency)).join(' + ') : '—';
}

function groupTitle(group = {}) {
  const parts = [];
  if (group.model) parts.push(group.model);
  if (group.protocol) parts.push(PROTOCOL_LABELS[group.protocol] || group.protocol);
  if (group.effort) parts.push(`推理 ${effortLabel(group.effort)}`);
  if (group.helpers) parts.push(group.helpers === 'all_on' ? '辅助全开' : helpersOffLabel(group.helpers.split('+')));
  return parts.join(' · ');
}

export function statsView(data) {
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  const pct = (part, whole) => (whole > 0 ? `${Math.round((part / whole) * 100)}%` : '—');
  return rows.map((row) => {
    const group = row.group || { model: row.model, mode: row.contextMode };
    const mode = group.mode ? contextLabel(group.mode) : '';
    const cost = row.cost ? `${costText(row.cost)}${row.partialCostTurns ? ' 起' : ''}` : '—';
    return {
      key: JSON.stringify(group),
      title: groupTitle(group) || mode || '—',
      mode: groupTitle(group) ? mode : '',
      cells: [
        ['回合', `${row.turns}${row.failed ? ` · 失败 ${row.failed}` : ''}${row.cancelled ? ` · 停止 ${row.cancelled}` : ''}${row.archived ? ` · 已回退 ${row.archived}` : ''}`],
        ['首字中位', row.medianFirstOutputMs !== undefined ? secs(row.medianFirstOutputMs) : '—'],
        ['耗时中位', row.medianDurationMs !== undefined ? secs(row.medianDurationMs) : '—'],
        ['平均 token', row.avgTokens !== undefined ? compact(row.avgTokens) : '—'],
        ['缓存命中', row.cacheHitRate !== undefined ? `${Math.round(row.cacheHitRate * 100)}%` : '—'],
        ['费用（估算）', cost],
        ['辅助占比', row.helperShare !== undefined ? `${Math.round(row.helperShare * 100)}%` : '—'],
        ['路由', pct(row.routed, row.turns)],
        ['联网', pct(row.webUsed, row.turns)],
        ['校验 / 修正', row.verified ? `${pct(row.verified, row.turns)} / ${row.repaired} 次` : '—'],
        ['评价', row.ratedUp || row.ratedDown ? `好 ${row.ratedUp} · 差 ${row.ratedDown}` : '—'],
      ],
    };
  });
}

/** Normalised numbers for one turn's usage row (all optional; null means not reported). */
export function turnUsage(usage) {
  if (!usage) return null;
  const input = count(usage.inputTokens);
  const cached = count(usage.cachedInputTokens);
  const cacheBase = count(usage.cacheHitBaseTokens);
  const subagents = count(usage.subagentCallCount ?? usage.subagentCount);
  const auxiliary = count(usage.auxiliaryCallCount);
  return {
    input, estimatedInput: count(usage.context?.estimatedInputTokens), output: count(usage.outputTokens),
    cached, cacheBase, cacheRate: cached !== null && cacheBase ? Math.min(1, cached / cacheBase) : null, cacheWrite: count(usage.cacheWriteInputTokens),
    reasoning: count(usage.reasoningTokens), speed: count(usage.outputTokensPerSecond), firstMs: count(usage.firstOutputLatencyMs), totalMs: count(usage.requestDurationMs),
    subagents, childInput: count(usage.childInputTokens), childOutput: count(usage.childOutputTokens),
    auxiliary, auxInput: count(usage.auxiliaryInputTokens), auxOutput: count(usage.auxiliaryOutputTokens),
    totalInput: count(usage.totalInputTokens), totalOutput: count(usage.totalOutputTokens), incomplete: Boolean(usage.usageIncomplete),
    retries: count(usage.providerRetries),
    cost: usage.cost && Number.isFinite(Number(usage.cost.amount)) ? { amount: Number(usage.cost.amount), currency: usage.cost.currency || 'USD', partial: Boolean(usage.cost.partial), offPeak: Boolean(usage.cost.offPeak) } : null,
  };
}

/** Grand totals for a turn: the native total when reported, otherwise the main call. */
export function turnTotals(u) {
  if (!u) return { input: null, output: null };
  return { input: u.totalInput ?? u.input ?? u.estimatedInput, output: u.totalOutput ?? u.output };
}

export function verificationState(verification) {
  if (!verification) return null;
  const verdict = String(verification.verdict || '');
  let seal = null; let label = '智能校验 · 未裁决'; let tone = 'quiet';
  if (verification.status !== 'valid') { label = '智能校验 · 无效'; }
  else if (verification.coverageComplete === false) { seal = '存疑'; label = '智能校验 · 覆盖不足'; tone = 'warn'; }
  else if (verification.action === 'accept') { seal = '已校'; label = '智能校验 · 通过'; tone = 'good'; }
  else if (verification.action === 'repair') { seal = '已改'; label = '智能校验 · 已修正'; tone = 'warn'; }
  // Feedback trains calibration only from valid, fully covered pass/fail verdicts.
  const feedbackEligible = verification?.status === 'valid' && verification?.coverageComplete === true && (verdict === 'pass' || verdict === 'fail');
  return { seal, label, tone, verdict, feedbackEligible, recorded: verification.feedbackLabel || '', coverageReason: verification.coverageReason || '', risk: count(verification.risk),
    preRepairText: verification.repaired && typeof verification.preRepairText === 'string' ? verification.preRepairText : '' };
}

/** Cumulative reported tokens of the visible session, used for milestone notices. */
export function sessionTokenTotal(items = []) {
  let total = 0;
  for (const item of items || []) {
    if (item?.kind !== 'usage') continue;
    const u = turnUsage(item);
    const t = turnTotals(u);
    total += (t.input || 0) + (t.output || 0);
  }
  return total;
}

function localDay(value) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;
}

/** Drawer groups by local day of last activity. */
export function sessionGroups(sessions = [], now = new Date()) {
  const today = localDay(now);
  const yesterday = localDay(new Date(now.getTime() - 86400000));
  const groups = [['今天', []], ['昨天', []], ['更早', []]];
  for (const session of sessions) {
    const day = localDay(session.updatedAt);
    (day === today ? groups[0] : day === yesterday ? groups[1] : groups[2])[1].push(session);
  }
  return groups.filter(([, items]) => items.length);
}

export function usageToday(view) {
  const u = view?.usageToday;
  if (!u || !Number(u.turns)) return null;
  const base = Number(u.cacheHitBaseTokens) || 0;
  return { turns: Number(u.turns), tokens: (Number(u.inputTokens) || 0) + (Number(u.outputTokens) || 0), cacheRate: base ? Math.min(1, (Number(u.cachedInputTokens) || 0) / base) : null };
}
