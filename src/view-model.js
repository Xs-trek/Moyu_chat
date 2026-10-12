// The one adapter between the native AppViewModel (contract/moyu-chat-contract.d.ts) and the renderers.
// Renderers never interpret raw native fields themselves; when the native projection changes (orchestration,
// usage accounting, verification), adapt it here and keep the renderers untouched.

export const CONTEXT_LABELS = { minimal: '极简', standard: '标准', auto: '标准', smart: '智能' };
export const WEB_LABELS = { off: '关闭', auto: '自动', on: '开启' };

const STAGES = {
  preparing: ['整理上下文', 'think'],
  connecting: ['连接模型', 'idle'],
  connected: ['已连接 · 等待模型', 'idle'],
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
    for (const model of provider.models || []) choices.push({ provider, model });
  }
  return choices;
}

/** Capabilities exactly as the session's frozen deployment declares them (protocol ≠ capability). */
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
const SKIP_REASONS = { mode_off: '当前模式未启用', task_profile_cached: '沿用本会话的路由结果', within_budget: '上下文未超预算', web_not_needed: '本轮不联网', provider_hosted: '由模型侧联网', client_tools: '由工具调用联网', harness_off: '当前预设未启用', not_needed: '本轮无需校验' };
const WEB_EXECUTION = { host: '本机检索', provider: '模型侧联网', client_tools: '工具调用联网', none: '不联网' };
const VERDICTS = { pass: '通过', fail: '不通过', partial: '部分通过' };
const ACTIONS = { accept: '采纳', repair: '需修正', abstain: '存疑' };
/** Stages that exist only to help the main call; their share of tokens is what "is this helper still worth it" weighs. */
const HELPER_STAGES = new Set(['route', 'checkpoint', 'web_plan', 'verify', 'repair', 'subagent']);

function stageNote(span) {
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
  return { rows, status: trace.status, statusLabel: STATUS_LABELS[trace.status] || trace.status, terminalCode: trace.terminalCode || '', helperShare: total > 0 && helperTokens > 0 ? helperTokens / total : null };
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
  return { seal, label, tone, verdict, feedbackEligible, recorded: verification.feedbackLabel || '', coverageReason: verification.coverageReason || '', risk: count(verification.risk) };
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
