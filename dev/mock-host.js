// Browser stand-in for MainActivity.MoyuHostBridge + ChatCoordinator. It speaks the same envelopes
// (view.full / view.patch / intent.result) so src/ runs unmodified. Preview only; never packaged.
import { fixtures, STREAM_SCRIPT, usage as usageRow, image } from './fixtures.js';
import { makeConfigIntents, projectProviders } from './mock-config.js';

const params = new URLSearchParams(location.search);
const fixtureKey = fixtures[params.get('f')] ? params.get('f') : 'reading';
const fixture = fixtures[fixtureKey];
const world = fixture.build();
// Accounts are kept raw; the view gets the same projection native emits (resolved values + sources).
Object.defineProperty(world, 'providers', { get: () => projectProviders(world.accounts) });
world.theme = ['system', 'light', 'dark'].includes(params.get('theme')) ? params.get('theme') : 'system';
world.tone = ['sage', 'warm', 'neutral'].includes(params.get('tone')) ? params.get('tone') : 'sage';
world.delight = params.get('delight') !== 'off';

// ?motion=reduce emulates Android "remove animations" (WebView reports prefers-reduced-motion: reduce):
// the media block's rules are forced on and matchMedia answers yes, so the shipped CSS and JS take that path.
if (params.get('motion') === 'reduce') {
  const query = '(prefers-reduced-motion: reduce)';
  const nativeMatch = window.matchMedia.bind(window);
  window.matchMedia = (q) => (q.replace(/\s+/g, ' ').trim() === query ? { matches: true, media: q, addEventListener() {}, removeEventListener() {} } : nativeMatch(q));
  const force = () => {
    for (const sheet of document.styleSheets) {
      let rules;
      try { rules = sheet.cssRules; } catch { continue; }
      for (const rule of rules) {
        if (!(rule instanceof CSSMediaRule) || !rule.conditionText.includes('prefers-reduced-motion: reduce')) continue;
        const forced = new CSSStyleSheet();
        forced.replaceSync([...rule.cssRules].map((r) => r.cssText).join('\n'));
        document.adoptedStyleSheets = [...document.adoptedStyleSheets, forced];
      }
    }
  };
  if (document.readyState === 'complete') force(); else window.addEventListener('load', force);
}
let revision = 0;
let stream = null;
const PATCH_MS = 50; // ChatCoordinator caps streaming views at 20 fps.

const now = () => new Date().toISOString();
const active = () => world.sessions.find((s) => s.localSessionId === world.activeSessionId) || null;
const findSession = (id) => world.sessions.find((s) => s.localSessionId === id) || null;
const nextSeq = (s) => s.messages.reduce((max, item) => Math.max(max, Number(item.localSeq) || 0), 0) + 1;

function dispatch(envelope) { window.dispatchEvent(new CustomEvent('moyu:view', { detail: JSON.parse(JSON.stringify(envelope)) })); }

function summary(s) {
  const { messages, composerDraft, draftMedia, ...rest } = s;
  return rest;
}

function busy(s) { return Boolean(stream && stream.sessionId === s.localSessionId); }

/** Same rule as ChatCoordinator.retryTarget. */
function retrySeq(messages) {
  for (let i = messages.length - 1; i >= 0; i -= 1) {
    const item = messages[i];
    if (item.kind !== 'message' || item.role !== 'user') continue;
    if (item.inherited) return undefined;
    let answered = false;
    for (const after of messages.slice(i + 1)) {
      if (after.kind === 'error') return item.localSeq;
      if (after.kind === 'message' && after.role === 'assistant') { if (after.incomplete) return item.localSeq; answered = true; }
    }
    return answered ? undefined : item.localSeq;
  }
  return undefined;
}

function detail(s) {
  const running = busy(s);
  return {
    ...s, state: running ? 'running' : s.state,
    canAttachImage: !running && s.inputModalities.split(',').includes('image'), canSend: !running, canInterrupt: running, canConfigureTurn: !running,
    contextPreparing: running && stream.stage === 'preparing', webPreparingStage: running && /^web_/.test(stream.stage) ? stream.stage : undefined,
    postProcessingStage: running && ['verifying', 'repairing'].includes(stream.stage) ? stream.stage : undefined,
    retryLocalSeq: running ? undefined : retrySeq(s.messages), profileChanged: !running && s.profileChanged ? true : undefined, contextBudgetTokens: s.contextBudgetTokens ?? 180000,
  };
}

const blank = (x) => !x.origin && !(x.messages || []).length;
const PLACEHOLDER_CHARS = 20;
const placeholderTitle = (text) => { const line = String(text || '').split(/\r?\n/).map((l) => l.trim()).find(Boolean) || ''; return line.length > PLACEHOLDER_CHARS ? `${line.slice(0, PLACEHOLDER_CHARS)}…` : line; };
/** Leaving an unused blank Chat (no message, empty composer) deletes it, as the native side does. */
function dropBlank(keep) { world.sessions = world.sessions.filter((x) => x.localSessionId === keep || !blank(x) || String(x.composerDraft || '').trim() || busy(x)); }

function emitView() {
  const s = active();
  const resolved = world.theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : world.theme;
  dispatch({ version: 1, type: 'view.full', revision: ++revision, view: {
    route: world.route, now: now(), appearance: { theme: world.theme, resolvedTheme: resolved, tone: world.tone, delight: world.delight }, providers: world.providers, usageToday: usageToday(),
    // Mirrors ChatCoordinator: a Chat that never had a message is listed only while it holds an unsent draft.
    sessions: [...world.sessions].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).filter((x) => !blank(x) || String(x.composerDraft || '').trim())
      .map((x) => ({ ...summary(x), state: busy(x) ? 'running' : x.state, ...(blank(x) ? { blank: true, draft: String(x.composerDraft).trim().slice(0, 80) } : {}) })),
    activeLocalSessionId: world.activeSessionId, activeSession: s ? detail(s) : undefined, ui: world.ui,
  } });
}

/** Mirrors ChatCoordinator.usageToday: usage rows created since local midnight. */
function usageToday() {
  const since = new Date(); since.setHours(0, 0, 0, 0);
  const out = { since: since.toISOString(), turns: 0, inputTokens: 0, outputTokens: 0, cachedInputTokens: 0, cacheHitBaseTokens: 0 };
  for (const s of world.sessions) for (const u of s.messages) {
    if (u.kind !== 'usage' || new Date(u.createdAt) < since) continue;
    out.turns += 1; out.inputTokens += u.totalInputTokens ?? u.inputTokens ?? 0; out.outputTokens += u.totalOutputTokens ?? u.outputTokens ?? 0;
    out.cachedInputTokens += u.cachedInputTokens ?? 0; out.cacheHitBaseTokens += u.cacheHitBaseTokens ?? 0;
  }
  return out;
}

function emitPatch(s, item) {
  const d = detail(s);
  dispatch({ version: 1, type: 'view.patch', revision: ++revision, patch: { scope: 'conversation.stream', localSessionId: s.localSessionId,
    session: { state: d.state, canSend: d.canSend, canInterrupt: d.canInterrupt, canConfigureTurn: d.canConfigureTurn, contextPreparing: d.contextPreparing,
      webPreparingStage: d.webPreparingStage ?? null, postProcessingStage: d.postProcessingStage ?? null }, item } });
}

function result(requestId, ok = true, summaryText = '', data = undefined) {
  setTimeout(() => dispatch({ version: 1, type: 'intent.result', requestId, ok, ...(ok ? {} : { error: { summary: summaryText } }), ...(data === undefined ? {} : { data }) }), 0);
}

/** A plausible stage trace for a finished reply, shaped like ChatDatabase.turnTrace(). */
function mockTrace(s, turnId) {
  const reply = s?.messages.find((x) => x.turnId === turnId);
  if (!reply) throw new Error('turn not found');
  const mode = s.contextMode || 'standard';
  const smart = mode === 'smart';
  const web = Boolean(reply.sources?.length);
  const skip = (stage, reason) => ({ stage, status: 'skipped', durationMs: 0, reason });
  const ok = (stage, durationMs, detail, usage) => ({ stage, status: 'ok', durationMs, detail, ...(usage ? { usage } : {}) });
  const spans = [
    ok('accept', 3, { contextMode: mode, effectiveContextMode: mode, harnessPreset: 'balanced', webMode: s.webMode }),
    ok('context', 21, { messages: s.messages.filter((x) => x.kind === 'message').length, truncated: false, compactionDue: false }),
    smart ? ok('route', 840, { routed: true, domain: 'writing', taskType: 'explain', depth: 'standard', confidence: 0.86 }, { calls: 1, inputTokens: 1650, outputTokens: 64 }) : skip('route', 'mode_off'),
    mode === 'minimal' ? skip('checkpoint', 'mode_off') : skip('checkpoint', 'within_budget'),
    ok('web_plan', web && smart ? 620 : 1, { webMode: s.webMode, useWeb: web, plannerCalled: web && smart, intent: web ? 'fresh_facts' : 'none', queries: web ? ['长文 阅读 排版 行宽', 'CJK line length readability'] : [], urls: 0 },
      web && smart ? { calls: 1, inputTokens: 980, outputTokens: 120 } : undefined),
    ok('tools', 2, { webExecution: web ? 'host' : 'none', clientTools: '', schemaTokens: 0, schemasDropped: false }),
    web ? ok('web_retrieve', 1640, { backend: reply.webBackend || 'Moyu Web · Exa + Fetch', sources: reply.sources.length, evidenceChars: 9400, evidenceCap: 24000 }) : skip('web_retrieve', 'web_not_needed'),
    ok('generate', 6200, { rounds: 1, clientToolBatches: 0, subagents: 0, usedClientWeb: false, sources: reply.sources?.length || 0, outputChars: reply.text.length }, { calls: 1, inputTokens: 18432, outputTokens: 1210 }),
    smart && reply.verification ? ok('verify', 1900, { status: 'valid', verdict: reply.verification.verdict || 'pass', risk: reply.verification.risk ?? 0.12, action: reply.verification.action || 'accept', repairAttempted: Boolean(reply.verification.repairAttempted), repaired: Boolean(reply.verification.repaired), attempts: reply.verification.repaired ? 2 : 1 }, { calls: reply.verification.repaired ? 2 : 1, inputTokens: 5200, outputTokens: 260 })
      : skip('verify', smart ? 'not_needed' : 'mode_off'),
  ];
  if (smart && reply.verification?.repairAttempted) spans.push(ok('repair', 3100, { repaired: Boolean(reply.verification.repaired) }, { calls: 1, inputTokens: 6100, outputTokens: 980 }));
  const calls = [];
  for (const span of spans) for (let i = 0; i < (span.usage?.calls || 0); i++) calls.push({ callIndex: calls.length, stage: span.stage, parts: 2, chars: 4200 });
  return { turnId, status: reply.incomplete ? 'cancelled' : 'ok', terminalCode: reply.incomplete ? (reply.terminalCode || 'cancelled') : '', config: { contextMode: mode, model: s.model }, usage: {}, spans, calls };
}

/** Redacted wire records shaped like ChatDatabase.turnPayload(): pretty enough to judge the inspector layout. */
function mockPayload(s, turnId, callIndex) {
  const trace = mockTrace(s, turnId);
  const call = trace.calls.find((c) => c.callIndex === Number(callIndex));
  if (!call) throw new Error('call not found');
  const reply = s.messages.find((x) => x.turnId === turnId);
  const request = { url: 'https://api.example.com/v1/chat/completions', body: { model: s.model, stream: true, stream_options: { include_usage: true },
    messages: [{ role: 'system', content: 'You are Moyu Chat… (stage: ' + call.stage + ')' }, { role: 'user', content: [{ type: 'text', text: '那暗色模式呢？截图里这两张哪个更好？' },
      { type: 'image_url', image_url: { url: 'data:image/png;base64,<48213 base64 chars omitted>' } }] }], max_tokens: 4096 } };
  const deltas = String(reply?.text || 'ok').match(/.{1,12}/gs) || [];
  const lines = deltas.map((d) => `data: ${JSON.stringify({ choices: [{ delta: { content: d } }] })}\n`);
  lines.push(`data: ${JSON.stringify({ choices: [{ delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: 18432, completion_tokens: 1210 } })}\n`, 'data: [DONE]\n');
  const response = { status: 200, truncated: false, text: lines.join('\n') };
  return { turnId, callIndex: call.callIndex, stage: call.stage, parts: [
    { part: 0, direction: 'request', createdAt: now(), body: JSON.stringify(request) },
    { part: 1, direction: 'response', createdAt: now(), body: JSON.stringify(response) }] };
}

/** Every answer to one question across sessions (original + re-answers), shaped like ChatDatabase.answerGroup(). */
function mockAnswers(turnId) {
  const replies = [];
  for (const s of world.sessions) for (const m of s.messages) if (m.turnId) replies.push({ s, m });
  const found = replies.find(({ m }) => m.turnId === turnId);
  if (!found) throw new Error('turn not found');
  const root = found.m.rerunOf || found.m.turnId;
  const group = replies.filter(({ m }) => m.turnId === root || m.rerunOf === root);
  const question = (() => {
    const origin = group.find(({ m }) => m.turnId === root) || group[0];
    const at = origin.s.messages.indexOf(origin.m);
    for (let i = at - 1; i >= 0; i--) if (origin.s.messages[i].role === 'user') return origin.s.messages[i].text;
    return '';
  })();
  return { rootTurnId: root, question, answers: group.map(({ s, m }) => ({ turnId: m.turnId, localSessionId: s.localSessionId, sessionTitle: s.title, status: m.incomplete ? 'cancelled' : 'ok',
    config: { modelName: m.modelName || s.modelName, contextMode: s.contextMode }, usage: usageRow({ outputTokens: Math.round((m.text || '').length / 1.6) }),
    rating: m.rating, original: m.turnId === root, text: m.text, verification: m.verification ? { verdict: m.verification.verdict, repaired: Boolean(m.verification.repaired) } : undefined })) };
}

/** Plausible per model × mode rows for the stats page. */
/** Same row shape as TurnStats: group + medians, cache rate and cost; grouping is approximated by relabelling. */
function mockStats(days, groupBy = 'model,mode', includeArchived = true) {
  const k = days / 7;
  const dims = String(groupBy || 'model,mode').split(',');
  const rows = [
    { model: 'Claude Sonnet 5.5', contextMode: 'smart', turns: Math.round(42 * k), ok: Math.round(40 * k), failed: 2, cancelled: 0, avgFirstOutputMs: 1850, avgDurationMs: 14200, avgTokens: 23800, helperShare: 0.27, routed: Math.round(6 * k), webUsed: Math.round(15 * k), verified: Math.round(18 * k), repaired: 3, ratedUp: 9, ratedDown: 2 },
    { model: 'GPT-5.2', contextMode: 'standard', turns: Math.round(31 * k), ok: Math.round(31 * k), failed: 0, cancelled: 1, avgFirstOutputMs: 950, avgDurationMs: 8100, avgTokens: 15400, helperShare: 0.06, routed: 0, webUsed: Math.round(9 * k), verified: 0, repaired: 0, ratedUp: 5, ratedDown: 1 },
    { model: 'DeepSeek Chat', contextMode: 'minimal', turns: Math.round(12 * k), ok: Math.round(12 * k), failed: 0, cancelled: 0, avgFirstOutputMs: 620, avgDurationMs: 5200, avgTokens: 9100, routed: 0, webUsed: 0, verified: 0, repaired: 0, ratedUp: 0, ratedDown: 0 },
  ].map((row, i) => {
    const full = { model: row.model, mode: row.contextMode, protocol: ['anthropic-messages', 'openai-responses', 'openai-chat-completions'][i], effort: ['high', 'default', 'max'][i], helpers: ['all_on', 'verify', 'router+webPlan'][i] };
    const group = Object.fromEntries(dims.map((d) => [d, full[d]]));
    const archived = includeArchived ? [3, 1, 0][i] : 0;
    return { ...row, turns: row.turns - (includeArchived ? 0 : [3, 1, 0][i]), archived, group, medianFirstOutputMs: row.avgFirstOutputMs, medianDurationMs: row.avgDurationMs, cacheHitRate: [0.82, 0.64, 0.91][i],
      cost: [{ USD: 1.84 * k }, { USD: 0.92 * k }, { CNY: 0.31 * k }][i], costedTurns: row.turns, partialCostTurns: i === 0 ? 1 : 0 };
  });
  return { days, groupBy, includeArchived, rows };
}

function notice(text) { window.dispatchEvent(new CustomEvent('moyu:notice', { detail: { level: 'info', text: `[mock] ${text}` } })); }

/** Simulates one turn: stages → reasoning deltas → answer deltas → final row + usage, at native patch cadence. */
function startStream(s, text, { web = s.webMode === 'on', fail = false, keepDraft = false } = {}) {
  const user = { localSeq: nextSeq(s), kind: 'message', role: 'user', text, createdAt: now() };
  s.messages.push(user);
  const rerunOf = world.pendingReruns?.[s.localSessionId];
  if (world.pendingReruns) delete world.pendingReruns[s.localSessionId];
  const reply = { localSeq: nextSeq(s), turnId: `turn-live-${Date.now()}`, ...(rerunOf ? { rerunOf } : {}), kind: 'message', role: 'assistant', text: '', reasoning: '', model: s.model, modelName: s.modelName, modelProfileId: s.modelProfileId,
    reasoningAvailable: Boolean(s.reasoningLevels), reasoningEffort: s.reasoningEffort, streaming: true, streamStage: 'preparing', createdAt: now() };
  s.messages.push(reply);
  if (!keepDraft) s.composerDraft = ''; // a retry keeps what is being typed, like native
  s.updatedAt = now();
  const stages = [['preparing', 500], ...(web ? [['web_planning', 500], ['web_search', 900], ['web_fetch', 700]] : []), ['connecting', 300], ['connected', 700]];
  stream = { sessionId: s.localSessionId, stage: 'preparing', reply, timer: 0, started: performance.now(), firstOutput: 0 };
  emitView();
  let stageIndex = 0;
  let reasoningAt = 0;
  let answerAt = 0;
  const tick = () => {
    if (!stream) return;
    if (stageIndex < stages.length) {
      const [stage, wait] = stages[stageIndex++];
      stream.stage = stage; reply.streamStage = stage;
      emitPatch(s, reply);
      stream.timer = setTimeout(tick, wait);
      return;
    }
    if (reasoningAt < STREAM_SCRIPT.reasoning.length) {
      stream.stage = 'reasoning'; reply.streamStage = 'reasoning';
      reasoningAt = Math.min(STREAM_SCRIPT.reasoning.length, reasoningAt + 6 + Math.floor(Math.random() * 10));
      reply.reasoning = STREAM_SCRIPT.reasoning.slice(0, reasoningAt);
      emitPatch(s, reply);
      stream.timer = setTimeout(tick, PATCH_MS);
      return;
    }
    if (fail && answerAt > 120) return finish(s, reply, { error: true });
    if (answerAt < STREAM_SCRIPT.answer.length) {
      if (!stream.firstOutput) stream.firstOutput = performance.now();
      stream.stage = 'output'; reply.streamStage = 'output';
      answerAt = Math.min(STREAM_SCRIPT.answer.length, answerAt + 4 + Math.floor(Math.random() * 14));
      reply.text = STREAM_SCRIPT.answer.slice(0, answerAt);
      emitPatch(s, reply);
      stream.timer = setTimeout(tick, PATCH_MS);
      return;
    }
    if (s.contextMode === 'smart' && stream.stage !== 'verifying') {
      stream.stage = 'verifying'; reply.streamStage = 'verifying';
      emitPatch(s, reply);
      stream.timer = setTimeout(tick, 1200);
      return;
    }
    finish(s, reply, { web });
  };
  stream.timer = setTimeout(tick, 0);
}

function finish(s, reply, { web = false, error = false, interrupted = false } = {}) {
  if (!stream) return;
  clearTimeout(stream.timer);
  const elapsed = performance.now() - stream.started;
  const firstOutput = stream.firstOutput ? stream.firstOutput - stream.started : undefined;
  stream = null;
  delete reply.streaming; delete reply.streamStage;
  if (interrupted) { reply.incomplete = true; reply.terminalCode = 'interrupted'; }
  if (web) { reply.sources = STREAM_SCRIPT.sources; reply.webBackend = s.effectiveWebBackend; }
  if (s.contextMode === 'smart' && !error && !interrupted) reply.verification = { status: 'valid', verdict: 'pass', coverageComplete: true, action: 'accept', selfConfidence: 0.8, risk: 0.1 };
  s.messages.push({ localSeq: nextSeq(s), kind: 'usage', ...usageRow({ outputTokens: Math.round(reply.text.length / 1.6), requestDurationMs: Math.round(elapsed),
    firstOutputLatencyMs: firstOutput === undefined ? undefined : Math.round(firstOutput) }), createdAt: now() });
  if (error) s.messages.push({ localSeq: nextSeq(s), kind: 'error', code: 'provider.stream_reset', summary: '模拟错误：流在输出中途被服务端重置。', createdAt: now() });
  s.state = error ? 'failed' : 'completed';
  s.preview = reply.text.slice(0, 60);
  const firstQuestion = s.messages.find((m) => m.role === 'user')?.text;
  if (!error && !interrupted && (s.title === '新 Chat' || s.title === placeholderTitle(firstQuestion))) setTimeout(() => { s.title = `${placeholderTitle(firstQuestion).slice(0, 8)}（已命名）`; emitView(); }, 900);
  if (reply.rerunOf) {
    const group = mockAnswers(reply.turnId);
    for (const answer of group.answers) for (const x of world.sessions) for (const m of x.messages) if (m.turnId === answer.turnId) m.answers = group.answers.length;
  }
  emitView();
}

function handle(type, p) {
  const s = p.localSessionId ? findSession(p.localSessionId) : null;
  switch (type) {
    case 'app.ready': case 'view.reload': emitView(); if (fixture.autoplay === 'stream' && type === 'app.ready') setTimeout(() => active() && startStream(active(), '再详细讲讲长文阅读排版，给出代码和表格。', { web: true }), 600); return;
    case 'nav.open': world.route = p.route; break;
    case 'appearance.set': if (p.theme) world.theme = p.theme; if (p.tone) world.tone = p.tone; break;
    case 'ui.delight.set': world.delight = Boolean(p.enabled); break;
    case 'ui.mascot.set': {
      const m = world.ui.mascot || (world.ui.mascot = { disguise: false, gameBest: 0, koiDay: '' });
      if (p.key === 'disguise') m.disguise = Boolean(p.value);
      else if (p.key === 'gameBest') m.gameBest = Math.max(m.gameBest, Number(p.value) || 0);
      else if (p.key === 'koiDay') m.koiDay = String(p.value || '');
      break;
    }
    case 'chat.reasoning.visibility.set': world.ui.showReasoning = Boolean(p.show); break;
    case 'memory.global.set': world.ui.crossChatMemoryEnabled = Boolean(p.enabled); break;
    case 'inspector.capture.set': world.ui.wireCapture = Boolean(p.enabled); break;
    case 'notify.background.set': world.ui.notifyInBackground = Boolean(p.enabled); break;
    case 'helper.switch.set': {
      const off = new Set(world.ui.helpersOff || []);
      if (p.enabled) off.delete(p.helper); else off.add(p.helper);
      world.ui.helpersOff = ['router', 'compaction', 'recall', 'webPlan', 'webTools', 'verify', 'subagent'].filter((id) => off.has(id));
      break;
    }
    case 'instruction.global.set': world.ui.customInstruction = String(p.text || '').trim(); break;
    case 'context.mode.default.set': world.ui.defaultContextMode = p.mode; break;
    case 'web.mode.default.set': world.ui.defaultWebMode = p.mode; break;
    case 'subagent.standard.default.set': world.ui.standardSubagentMode = p.mode; break;
    case 'chat.session.open': world.activeSessionId = p.localSessionId; world.route = 'conversation'; dropBlank(p.localSessionId); break;
    case 'chat.session.create': {
      dropBlank(null);
      const id = `s-${Date.now()}`;
      const provider = world.providers.find((x) => x.providerId === p.providerId);
      const m = provider?.models.find((x) => x.modelProfileId === p.modelProfileId) || provider?.models[0];
      const base = world.sessions[0] || {};
      world.sessions.unshift({ ...base, localSessionId: id, title: '新 Chat', providerId: provider.providerId, providerName: provider.displayName, modelProfileId: m.modelProfileId, model: m.modelId,
        modelName: m.displayName, reasoningLevels: m.reasoningLevels, reasoningEffort: 'default', inputModalities: m.inputModalities, contextMode: p.contextMode || 'standard',
        effectiveContextMode: p.contextMode || 'standard', webMode: p.webMode || 'auto', state: 'idle', origin: undefined, preview: '', messages: [], composerDraft: '', draftMedia: [], updatedAt: now() });
      world.activeSessionId = id; world.route = 'conversation'; break;
    }
    case 'chat.session.rename': if (s) s.title = p.title; break;
    case 'chat.session.delete': world.sessions = world.sessions.filter((x) => x !== s); if (world.activeSessionId === p.localSessionId) { world.activeSessionId = undefined; world.route = 'home'; } break;
    case 'chat.session.model.set': {
      for (const provider of world.providers) for (const m of provider.models) if (m.modelProfileId === p.modelProfileId && s) Object.assign(s, { providerId: provider.providerId,
        providerName: provider.displayName, modelProfileId: m.modelProfileId, model: m.modelId, modelName: m.displayName, reasoningLevels: m.reasoningLevels, reasoningEffort: 'default', inputModalities: m.inputModalities, profileChanged: false });
      break;
    }
    case 'chat.session.reasoning.set': if (s) s.reasoningEffort = p.reasoningEffort; break;
    case 'chat.session.web.set': if (s) s.webMode = p.webMode; break;
    case 'chat.session.saveDraft': if (s) s.composerDraft = p.text; return; // native does not re-emit for drafts
    case 'chat.session.send': if (s) { if (p.reasoningEffort) s.reasoningEffort = p.reasoningEffort; if (s.title === '新 Chat' && placeholderTitle(p.text)) s.title = placeholderTitle(p.text); startStream(s, p.text); } return;
    case 'chat.turn.retry': {
      const seq = s && retrySeq(s.messages);
      const question = seq && s.messages.find((x) => x.localSeq === seq);
      if (!question) throw new Error('最后一轮已经完成，不需要重试');
      s.messages = s.messages.filter((x) => Number(x.localSeq) < seq);
      startStream(s, question.text, { keepDraft: true });
      return;
    }
    case 'chat.session.interrupt': if (stream && s) finish(s, stream.reply, { interrupted: true }); return;
    case 'chat.attachment.pick': if (s) { s.draftMedia = [...(s.draftMedia || []), image(`d-${Date.now()}`, 800, 600, Math.floor(Math.random() * 360), '附件')]; } break;
    case 'chat.attachment.remove': if (s) s.draftMedia = s.draftMedia.filter((x) => x.mediaId !== p.mediaId); break;
    case 'chat.message.rollback': if (s) s.messages = s.messages.filter((x) => Number(x.localSeq) < p.localSeq); break;
    case 'chat.message.edit': if (s) { const item = s.messages.find((x) => x.localSeq === p.localSeq); s.composerDraft = item?.text || ''; s.messages = s.messages.filter((x) => Number(x.localSeq) < p.localSeq); } break;
    case 'branch.create.local': notice(`创建分支（through ${p.throughSeq ?? '末尾'}）`); return;
    case 'turn.rate': for (const x of world.sessions) for (const m of x.messages) if (m.turnId === p.turnId) { if (p.rating) m.rating = p.rating; else delete m.rating; } break;
    case 'verification.feedback': { const item = s?.messages.find((x) => x.localSeq === p.localSeq); if (item?.verification) item.verification = { ...item.verification, feedbackLabel: p.label }; break; }
    case 'external.open': notice(`打开外部链接 ${p.url}`); return;
    case 'provider.credential.edit': {
      // Native password dialog: not available in the preview, so pretend the key was saved.
      const account = world.accounts.find((x) => x.providerId === p.providerId);
      if (account) { account.hasCredential = true; for (const r of account.routes) r.confirmedHost = new URL(r.baseUrl).host; }
      notice('原生 API Key 输入框（预览中直接视为已保存）');
      break;
    }
    default: throw new Error(`unsupported intent: ${type}`);
  }
  emitView();
}

// Account / route / model editor intents (ProviderConfigService), including the read-only ones.
const EDITOR_INTENTS = makeConfigIntents(world);

window.MoyuHost = {
  postMessage(raw) {
    const message = JSON.parse(raw);
    if (EDITOR_INTENTS[message.type]) {
      setTimeout(() => {
        try { const data = EDITOR_INTENTS[message.type](message.payload || {}); emitView(); result(message.requestId, true, '', data); }
        catch (error) { result(message.requestId, false, error.message); }
      }, 160);
      return;
    }
    if (message.type === 'chat.session.delete' && Array.isArray(message.payload?.localSessionIds)) {
      const ids = new Set(message.payload.localSessionIds);
      const skipped = world.sessions.filter((x) => ids.has(x.localSessionId) && x.state === 'running').map((x) => x.localSessionId);
      const before = world.sessions.length;
      world.sessions = world.sessions.filter((x) => !ids.has(x.localSessionId) || skipped.includes(x.localSessionId));
      if (ids.has(world.activeSessionId) && !skipped.includes(world.activeSessionId)) { world.activeSessionId = undefined; world.route = 'home'; }
      setTimeout(() => { emitView(); result(message.requestId, true, '', { deleted: before - world.sessions.length, skipped }); }, 120);
      return;
    }
    if (message.type === 'chat.session.title.auto') {
      const s = findSession(message.payload?.localSessionId);
      setTimeout(() => { s.title = `模拟标题 ${Math.floor(Math.random() * 90 + 10)}`; emitView(); result(message.requestId, true, ''); }, 700);
      return;
    }
    if (message.type === 'turn.rerun') {
      try {
        const source = findSession(message.payload?.localSessionId);
        const seq = Number(message.payload?.localSeq);
        const question = source.messages.find((x) => x.localSeq === seq);
        const reply = source.messages.find((x) => x.role === 'assistant' && x.localSeq > seq && x.turnId);
        const provider = world.providers.find((x) => x.models.some((m) => m.modelProfileId === (message.payload.modelProfileId || source.modelProfileId)));
        const model = provider?.models.find((m) => m.modelProfileId === (message.payload.modelProfileId || source.modelProfileId));
        const id = `s-rerun-${Date.now()}`;
        const mode = message.payload.contextMode || source.contextMode;
        world.sessions.unshift({ ...source, localSessionId: id, title: `重答 · ${source.title}`, messages: source.messages.filter((x) => Number(x.localSeq) < seq).map((x) => ({ ...x })),
          contextMode: mode, effectiveContextMode: mode, ...(model ? { modelProfileId: model.modelProfileId, model: model.modelId, modelName: model.displayName, providerId: provider.providerId, providerName: provider.displayName } : {}),
          composerDraft: '', draftMedia: question?.attachments || [], state: 'idle', updatedAt: now() });
        world.pendingReruns = { ...(world.pendingReruns || {}), [id]: reply?.turnId };
        world.activeSessionId = id; world.route = 'conversation';
        emitView();
        result(message.requestId, true, '', { localSessionId: id, text: question?.text || '' });
      } catch (error) { result(message.requestId, false, error.message); }
      return;
    }
    if (message.type === 'turn.answers.get') {
      try { result(message.requestId, true, '', mockAnswers(message.payload?.turnId)); }
      catch (error) { result(message.requestId, false, error.message); }
      return;
    }
    if (message.type === 'stats.get') { result(message.requestId, true, '', mockStats(Number(message.payload?.days) || 7, message.payload?.groupBy, message.payload?.includeArchived !== false)); return; }
    // Account checks answer after a short delay, like a real network call.
    const later = (data) => setTimeout(() => { emitView(); result(message.requestId, true, '', data); }, 700);
    if (message.type === 'provider.key.verify') {
      const provider = world.providers.find((p) => p.providerId === message.payload?.providerId);
      const account = world.accounts.find((a) => a.providerId === message.payload?.providerId);
      if (account) { account.keyStatus = 'valid'; account.keyCheckedAt = now(); }
      later({ ok: true, keyStatus: 'valid', checkedAt: now(), routeLabel: provider?.routes?.[0]?.label || '', models: (provider?.models || []).map((m) => ({ id: m.modelId, contextTokens: m.contextTokens })) });
      return;
    }
    if (message.type === 'route.models.list') {
      const provider = world.providers.find((p) => (p.routes || []).some((r) => r.routeId === message.payload?.routeId));
      later({ ok: true, routeId: message.payload?.routeId, models: [...(provider?.models || []).map((m) => ({ id: m.modelId, contextTokens: m.contextTokens, maxOutputTokens: m.maxOutputTokens })), { id: 'mock-extra-model', contextTokens: 64000 }] });
      return;
    }
    if (message.type === 'provider.balance.get') { later({ ok: true, available: true, checkedAt: now(), balances: [{ currency: 'CNY', total: '86.40', granted: '0.00', toppedUp: '86.40' }] }); return; }
    if (message.type === 'model.probe') {
      const probe = { checkedAt: now(), ok: true, totalMs: 1630, firstOutputMs: 910, usageReported: true, reasoningSeen: false, reply: 'OK' };
      for (const a of world.accounts) for (const m of a.models || []) if (m.modelProfileId === message.payload?.modelProfileId) m.probe = probe;
      later(probe);
      return;
    }
    if (message.type === 'turn.payload.get') {
      try { result(message.requestId, true, '', mockPayload(findSession(message.payload?.localSessionId), message.payload?.turnId, message.payload?.callIndex)); }
      catch (error) { result(message.requestId, false, error.message); }
      return;
    }
    if (message.type === 'turn.trace.get') {
      try { result(message.requestId, true, '', mockTrace(findSession(message.payload?.localSessionId), message.payload?.turnId)); }
      catch (error) { result(message.requestId, false, error.message); }
      return;
    }
    try { handle(message.type, message.payload || {}); result(message.requestId); }
    catch (error) { result(message.requestId, false, error.message); }
  },
};

/** Console hooks, e.g. __mock.stream({ web: true }) or __mock.stream({ fail: true }). */
window.__mock = {
  world,
  stream(options = {}) { const s = active(); if (s && !stream) startStream(s, options.text || '模拟一次流式回复。', options); },
  stop() { const s = active(); if (s && stream) finish(s, stream.reply, { interrupted: true }); },
};

function devPanel() {
  const panel = document.createElement('div');
  panel.className = 'dev-panel';
  const options = Object.entries(fixtures).map(([key, f]) => `<option value="${key}"${key === fixtureKey ? ' selected' : ''}>${f.label}</option>`).join('');
  const themes = ['system', 'light', 'dark'].map((t) => `<option${t === world.theme ? ' selected' : ''}>${t}</option>`).join('');
  // Static markup authored here (no model or fixture text), so innerHTML is safe in this preview-only file.
  panel.innerHTML = `<button type="button" class="dev-panel-toggle">DEV</button><div class="dev-panel-body">
    <label>Fixture<select data-dev="fixture">${options}</select></label>
    <label>主题<select data-dev="theme">${themes}</select></label>
    <div class="dev-row"><button type="button" data-dev="stream">流式</button><button type="button" data-dev="stream-web">流式+联网</button>
    <button type="button" data-dev="stream-fail">中途出错</button><button type="button" data-dev="stop">中断</button></div>
    <label><input type="checkbox" data-dev="reduce"${params.get('motion') === 'reduce' ? ' checked' : ''}> 模拟系统“移除动画”</label>
    <small>窄屏预览：浏览器 DevTools 设备模式，或缩窄窗口到 360–430px。</small></div>`;
  panel.querySelector('[data-dev="reduce"]').addEventListener('change', (event) => { if (event.target.checked) params.set('motion', 'reduce'); else params.delete('motion'); location.search = params.toString(); });
  panel.querySelector('.dev-panel-toggle').addEventListener('click', () => panel.classList.toggle('open'));
  panel.querySelector('[data-dev="fixture"]').addEventListener('change', (event) => { params.set('f', event.target.value); location.search = params.toString(); });
  panel.querySelector('[data-dev="theme"]').addEventListener('change', (event) => { params.set('theme', event.target.value); history.replaceState(null, '', `?${params}`); world.theme = event.target.value; emitView(); });
  panel.querySelector('[data-dev="stream"]').addEventListener('click', () => window.__mock.stream());
  panel.querySelector('[data-dev="stream-web"]').addEventListener('click', () => window.__mock.stream({ web: true }));
  panel.querySelector('[data-dev="stream-fail"]').addEventListener('click', () => window.__mock.stream({ fail: true }));
  panel.querySelector('[data-dev="stop"]').addEventListener('click', () => window.__mock.stop());
  document.body.append(panel);
}
devPanel();
