// Browser stand-in for MainActivity.MoyuHostBridge + ChatCoordinator. It speaks the same envelopes
// (view.full / view.patch / intent.result) so src/ runs unmodified. Preview only; never packaged.
import { fixtures, STREAM_SCRIPT, usage as usageRow, image } from './fixtures.js';

const params = new URLSearchParams(location.search);
const fixtureKey = fixtures[params.get('f')] ? params.get('f') : 'reading';
const fixture = fixtures[fixtureKey];
const world = fixture.build();
world.theme = ['system', 'light', 'dark'].includes(params.get('theme')) ? params.get('theme') : 'system';
world.tone = ['sage', 'warm', 'neutral'].includes(params.get('tone')) ? params.get('tone') : 'sage';
world.delight = params.get('delight') !== 'off';
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

function detail(s) {
  const running = busy(s);
  return {
    ...s, state: running ? 'running' : s.state,
    canAttachImage: !running && s.inputModalities.split(',').includes('image'), canSend: !running, canInterrupt: running, canConfigureTurn: !running,
    contextPreparing: running && stream.stage === 'preparing', webPreparingStage: running && /^web_/.test(stream.stage) ? stream.stage : undefined,
    postProcessingStage: running && ['verifying', 'repairing'].includes(stream.stage) ? stream.stage : undefined,
  };
}

function emitView() {
  const s = active();
  const resolved = world.theme === 'system' ? (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light') : world.theme;
  dispatch({ version: 1, type: 'view.full', revision: ++revision, view: {
    route: world.route, now: now(), appearance: { theme: world.theme, resolvedTheme: resolved, tone: world.tone, delight: world.delight }, providers: world.providers, usageToday: usageToday(),
    sessions: [...world.sessions].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)).map((x) => ({ ...summary(x), state: busy(x) ? 'running' : x.state })),
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

function result(requestId, ok = true, summaryText = '') {
  setTimeout(() => dispatch({ version: 1, type: 'intent.result', requestId, ok, ...(ok ? {} : { error: { summary: summaryText } }) }), 0);
}

function notice(text) { window.dispatchEvent(new CustomEvent('moyu:notice', { detail: { level: 'info', text: `[mock] ${text}` } })); }

/** Simulates one turn: stages → reasoning deltas → answer deltas → final row + usage, at native patch cadence. */
function startStream(s, text, { web = s.webMode === 'on', fail = false } = {}) {
  const user = { localSeq: nextSeq(s), kind: 'message', role: 'user', text, createdAt: now() };
  s.messages.push(user);
  const reply = { localSeq: nextSeq(s), kind: 'message', role: 'assistant', text: '', reasoning: '', model: s.model, modelName: s.modelName, modelProfileId: s.modelProfileId,
    reasoningAvailable: Boolean(s.reasoningLevels), reasoningEffort: s.reasoningEffort, streaming: true, streamStage: 'preparing', createdAt: now() };
  s.messages.push(reply);
  s.composerDraft = '';
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
  emitView();
}

function handle(type, p) {
  const s = p.localSessionId ? findSession(p.localSessionId) : null;
  switch (type) {
    case 'app.ready': case 'view.reload': emitView(); if (fixture.autoplay === 'stream' && type === 'app.ready') setTimeout(() => active() && startStream(active(), '再详细讲讲长文阅读排版，给出代码和表格。', { web: true }), 600); return;
    case 'nav.open': world.route = p.route; break;
    case 'appearance.set': if (p.theme) world.theme = p.theme; if (p.tone) world.tone = p.tone; break;
    case 'ui.delight.set': world.delight = Boolean(p.enabled); break;
    case 'chat.reasoning.visibility.set': world.ui.showReasoning = Boolean(p.show); break;
    case 'memory.global.set': world.ui.crossChatMemoryEnabled = Boolean(p.enabled); break;
    case 'context.mode.default.set': world.ui.defaultContextMode = p.mode; break;
    case 'web.mode.default.set': world.ui.defaultWebMode = p.mode; break;
    case 'subagent.standard.default.set': world.ui.standardSubagentMode = p.mode; break;
    case 'chat.session.open': world.activeSessionId = p.localSessionId; world.route = 'conversation'; break;
    case 'chat.session.create': {
      const id = `s-${Date.now()}`;
      const provider = world.providers.find((x) => x.providerId === p.providerId);
      const m = provider?.models.find((x) => x.modelProfileId === p.modelProfileId) || provider?.models[0];
      const base = world.sessions[0] || {};
      world.sessions.unshift({ ...base, localSessionId: id, title: '新会话', providerId: provider.providerId, providerName: provider.displayName, modelProfileId: m.modelProfileId, model: m.modelId,
        modelName: m.displayName, reasoningLevels: m.reasoningLevels, reasoningEffort: 'default', inputModalities: m.inputModalities, contextMode: p.contextMode || 'standard',
        effectiveContextMode: p.contextMode || 'standard', webMode: p.webMode || 'auto', state: 'idle', origin: undefined, preview: '', messages: [], composerDraft: '', draftMedia: [], updatedAt: now() });
      world.activeSessionId = id; world.route = 'conversation'; break;
    }
    case 'chat.session.rename': if (s) s.title = p.title; break;
    case 'chat.session.delete': world.sessions = world.sessions.filter((x) => x !== s); if (world.activeSessionId === p.localSessionId) { world.activeSessionId = undefined; world.route = 'home'; } break;
    case 'chat.session.model.set': {
      for (const provider of world.providers) for (const m of provider.models) if (m.modelProfileId === p.modelProfileId && s) Object.assign(s, { providerId: provider.providerId,
        providerName: provider.displayName, modelProfileId: m.modelProfileId, model: m.modelId, modelName: m.displayName, reasoningLevels: m.reasoningLevels, reasoningEffort: 'default', inputModalities: m.inputModalities });
      break;
    }
    case 'chat.session.reasoning.set': if (s) s.reasoningEffort = p.reasoningEffort; break;
    case 'chat.session.web.set': if (s) s.webMode = p.webMode; break;
    case 'chat.session.saveDraft': if (s) s.composerDraft = p.text; return; // native does not re-emit for drafts
    case 'chat.session.send': if (s) { if (p.reasoningEffort) s.reasoningEffort = p.reasoningEffort; startStream(s, p.text); } return;
    case 'chat.session.interrupt': if (stream && s) finish(s, stream.reply, { interrupted: true }); return;
    case 'chat.attachment.pick': if (s) { s.draftMedia = [...(s.draftMedia || []), image(`d-${Date.now()}`, 800, 600, Math.floor(Math.random() * 360), '附件')]; } break;
    case 'chat.attachment.remove': if (s) s.draftMedia = s.draftMedia.filter((x) => x.mediaId !== p.mediaId); break;
    case 'chat.message.rollback': if (s) s.messages = s.messages.filter((x) => Number(x.localSeq) < p.localSeq); break;
    case 'chat.message.edit': if (s) { const item = s.messages.find((x) => x.localSeq === p.localSeq); s.composerDraft = item?.text || ''; s.messages = s.messages.filter((x) => Number(x.localSeq) < p.localSeq); } break;
    case 'branch.create.local': notice(`创建分支（through ${p.throughSeq ?? '末尾'}）`); return;
    case 'verification.feedback': { const item = s?.messages.find((x) => x.localSeq === p.localSeq); if (item?.verification) item.verification = { ...item.verification, feedbackLabel: p.label }; break; }
    case 'external.open': notice(`打开外部链接 ${p.url}`); return;
    case 'provider.configure.open': case 'model.configure.open': notice('原生配置对话框（预览中不可用）'); return;
    default: throw new Error(`unsupported intent: ${type}`);
  }
  emitView();
}

window.MoyuHost = {
  postMessage(raw) {
    const message = JSON.parse(raw);
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
    <small>窄屏预览：浏览器 DevTools 设备模式，或缩窄窗口到 360–430px。</small></div>`;
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
