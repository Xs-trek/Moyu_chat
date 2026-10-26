export type RequestId = string;
export type Theme = 'system' | 'light' | 'dark';
/** Background tone of the soft palette; colours themselves live in src/styles.css and MainActivity.canvasColor. */
export type Tone = 'sage' | 'warm' | 'neutral';
export type Route = 'home' | 'conversation' | 'providers' | 'settings' | 'stats';
export type SessionState = 'idle' | 'running' | 'completed' | 'failed';
export type ContextMode = 'minimal' | 'standard' | 'smart';
export type WebMode = 'off' | 'auto' | 'on';
export type SubagentMode = 'off' | 'auto';

export interface UiIntent<T extends string = string, P = unknown> {
  version: 1;
  type: T;
  requestId: RequestId;
  payload: P;
}

export type MoyuChatIntent =
  | UiIntent<'app.ready', { uiVersion: string }>
  | UiIntent<'view.reload', Record<string, never>>
  /** At least one of theme / tone. */
  | UiIntent<'appearance.set', { theme?: Theme; tone?: Tone }>
  /** Mascot motion and easter eggs; verification seals stay visible either way. */
  | UiIntent<'ui.delight.set', { enabled: boolean }>
  | UiIntent<'chat.reasoning.visibility.set', { show: boolean }>
  | UiIntent<'memory.global.set', { enabled: boolean }>
  | UiIntent<'context.mode.default.set', { mode: ContextMode }>
  | UiIntent<'web.mode.default.set', { mode: WebMode }>
  | UiIntent<'subagent.standard.default.set', { mode: SubagentMode }>
  | UiIntent<'external.open', { url: string }>
  | UiIntent<'nav.open', { route: Route }>
  | UiIntent<'chat.session.create', { providerId: string; modelProfileId?: string; model?: string; reasoningEffort?: string; contextMode?: ContextMode; webMode?: WebMode; title?: string }>
  | UiIntent<'chat.session.open', { localSessionId: string }>
  | UiIntent<'chat.session.rename', { localSessionId: string; title: string }>
  /**
   * Name the session now from its latest exchange with its own model (hidden light call, recorded as a "title" stage
   * on that turn). Result ok = the title was replaced. Automatic naming runs on its own after the first answer while
   * the title is still the default or the first-message placeholder; a user's rename is never overwritten.
   */
  | UiIntent<'chat.session.title.auto', { localSessionId: string }>
  | UiIntent<'chat.session.model.set', { localSessionId: string; modelProfileId: string }>
  | UiIntent<'chat.session.reasoning.set', { localSessionId: string; reasoningEffort: string }>
  | UiIntent<'chat.session.web.set', { localSessionId: string; webMode: WebMode }>
  /**
   * A send that is not accepted fails at once with `data: { draftRestored: boolean; budget?: BudgetReachedView }`: the submitted
   * text is the draft again. With `budget` the account reached a budget; sending again with `budgetConfirmed` goes ahead.
   */
  | UiIntent<'chat.session.send', { localSessionId: string; text: string; reasoningEffort?: string; webMode?: WebMode; budgetConfirmed?: boolean }>
  | UiIntent<'chat.session.saveDraft', { localSessionId: string; text: string }>
  | UiIntent<'chat.attachment.pick', { localSessionId: string }>
  | UiIntent<'chat.attachment.remove', { localSessionId: string; mediaId: string }>
  | UiIntent<'chat.session.interrupt', { localSessionId: string }>
  /** One session (fails when busy), or a multi-select batch: busy sessions are skipped and reported, missing ones ignored. Batch result data: { deleted: number; skipped: string[] }. */
  /** Mascot state kept natively (the WebView has no DOM storage). Whitelisted keys only; gameBest only rises; koiDay is YYYY-MM-DD or ''. */
  | UiIntent<'ui.mascot.set', { key: 'disguise'; value: boolean } | { key: 'gameBest'; value: number } | { key: 'koiDay'; value: string }>
  | UiIntent<'chat.session.delete', { localSessionId: string } | { localSessionIds: string[] }>
  /** Edit (spec H5): the path ends before the question, its text and images go to the composer; sending adds its new version. */
  | UiIntent<'chat.message.edit', { localSessionId: string; localSeq: number }>
  /** "回到这里" (spec H5): the path ends at this answer (before this question); nothing is removed. */
  | UiIntent<'chat.message.rollback', { localSessionId: string; localSeq: number }>
  /** Version switch (spec G3): the path goes through this message to its newest message. Also "回到最新" with `resumeLocalSeq`. */
  | UiIntent<'chat.message.version', { localSessionId: string; localSeq: number }>
  /**
   * Retry (spec T7): when the path's last turn failed or did not finish (`SessionDetailView.retryLocalSeq`), its
   * question is answered again with the session's current settings as a new version; the failed version stays and the
   * composer is left alone.
   */
  | UiIntent<'chat.turn.retry', { localSessionId: string; budgetConfirmed?: boolean }>
  /**
   * Account editor: name, preset (creation only) and, when present, the complete route set (omitted routes are
   * deleted with their models; a new route identical to a stored one reuses it). Native pushes the view before
   * the result. Result `data: { providerId, hasCredential, routeIds }`, `routeIds` in the submitted order. Never carries a key.
   */
  | UiIntent<'provider.save', AccountForm>
  | UiIntent<'provider.delete', { providerId: string }>
  /** Opens the native password dialog (it lists the hosts the key will go to); the key never reaches the WebView. */
  | UiIntent<'provider.credential.edit', { providerId: string }>
  /** The user allows the account's key to be sent to this route's current host (after a Base URL change). */
  | UiIntent<'route.host.confirm', { routeId: string }>
  /** Model editor; result `data: { modelProfileId }`, or a failed result carrying the validation message. */
  | UiIntent<'model.save', ModelForm>
  | UiIntent<'model.delete', { modelProfileId: string }>
  | UiIntent<'model.enabled.set', { modelProfileId: string; enabled: boolean }>
  /** Read-only; result `data: ModelEditorOptions` (presets and the validators' own vocabulary). */
  | UiIntent<'model.editor.options', {}>
  /** Read-only; result `data: ModelPreview`: what saving would resolve, field by field, with sources. */
  | UiIntent<'model.preview', { providerId: string; routeId: string; modelId: string; modelProfileId?: string; overrides?: ModelOverrides }>
  /** Read-only; result `data: { routeId, version, models: CatalogModelView[] }`: reviewed models for a pristine preset route. */
  | UiIntent<'route.catalog', { routeId: string }>
  /** Checks the account key with the endpoint's model list; result `data: ModelListView & { keyStatus; checkedAt; routeLabel }`. Also runs after a key is saved. */
  | UiIntent<'provider.key.verify', { providerId: string }>
  /** The route's own model list (GET …/models); limits it reports become the "discovered" source of matching models. */
  | UiIntent<'route.models.list', { routeId: string }>
  /** Vendor balance where the preset declares an endpoint (`ProviderView.balanceSupported`); result `data: BalanceView`. */
  | UiIntent<'provider.balance.get', { providerId: string }>
  /** One tiny request on the model's deployment; result `data: ProbeResultView`, also stored as `ModelProfileView.probe`. */
  | UiIntent<'model.probe', { modelProfileId: string }>
  | UiIntent<'verification.feedback', { localSessionId: string; localSeq: number; label: 'accurate' | 'false_alarm' | 'missed_error' }>
  | UiIntent<'branch.create.local', { localSessionId: string; throughSeq?: number }>
  /** Read-only; the intent.result carries `data: TurnTraceView`. Traces are never pushed with the view. */
  | UiIntent<'turn.trace.get', { localSessionId: string; turnId: string }>
  /** Read-only; the intent.result carries `data: TurnPayloadView`. */
  | UiIntent<'turn.payload.get', { localSessionId: string; turnId: string; callIndex: number }>
  /** Protocol inspector on/off: record redacted request / response text of each model call (recent turns only). */
  | UiIntent<'inspector.capture.set', { enabled: boolean }>
  /** Result notification while the app is in the background (completed or failed turns; not ones the user stopped). */
  | UiIntent<'notify.background.set', { enabled: boolean }>
  /** Global custom instruction (≤ 4000 chars, '' clears): appended to every turn's system instructions and given to Smart verify/repair; recorded in the turn config. */
  | UiIntent<'instruction.global.set', { text: string }>
  /** Global helper switch (settings). Off removes a helper the context mode would run; never adds one. Frozen into each turn's config.helpersOff. */
  | UiIntent<'helper.switch.set', { helper: HelperId; enabled: boolean }>
  /** Spec D15: the helper model ('' = the session's model); must be an enabled Model Profile. */
  | UiIntent<'helper.model.set', { modelProfileId: string }>
  /** Spec D16–D18: the composer's question (and draft images) answered at once by 2–3 model × mode variants, as versions of one question. */
  | UiIntent<'chat.session.compare.send', { localSessionId: string; text: string; variants: { modelProfileId: string; contextMode: ContextMode }[]; blind?: boolean; budgetConfirmed?: boolean }>
  /** Spec D16, D17: pick an answer of the open side-by-side group (its turnId) or call it a 'tie'. */
  | UiIntent<'chat.compare.decide', { localSessionId: string; group: string; choice: string }>
  /** Spec D19: a few minimal requests (image input, client tools, hosted search); result data: CapabilityProbeView. Conclusive results feed the probe layer. */
  | UiIntent<'model.capabilities.probe', { modelProfileId: string }>
  /** Spec D15: whether one helper uses the helper model (defaults: title, router, web_plan). */
  | UiIntent<'helper.model.use.set', { helper: HelperModelUse; enabled: boolean }>
  /** 1 = good answer, -1 = bad answer, 0 clears. */
  | UiIntent<'turn.rate', { localSessionId: string; turnId: string; rating: 1 | -1 | 0 }>
  /** Re-ask the user message at localSeq in a new branch; result `data: { localSessionId, text }`, then send `text` there. */
  /** Re-answer (spec H5): a new answer version under this question; the model and mode become the session's settings. */
  | UiIntent<'turn.rerun', { localSessionId: string; localSeq: number; modelProfileId?: string; contextMode?: ContextMode; budgetConfirmed?: boolean }>
  /** Read-only; result `data: AnswerGroupView`. */
  | UiIntent<'turn.answers.get', { localSessionId: string; turnId: string }>
  /** Read-only; result `data: { days, since, groupBy, includeArchived, rows: StatsRowView[] }`. */
  /** groupBy: 'model,mode' (default) | 'model' | 'protocol' | 'effort' | 'mode' | 'model,effort' | 'helpers' | 'model,helpers'. */
  /** includeArchived (default true): also count the turns of deleted Chats (turn_archive). */
  | UiIntent<'stats.get', { days: number; groupBy?: string; includeArchived?: boolean }>;

/**
 * Helper modules with a global on/off switch. recall = history_recall tool when older turns are out of the prompt;
 * webPlan = the planner model of the host Web fallback (spec C8). There is no webTools switch (spec D5): the Web mode
 * controls the model's own Web tools. The Router starts off (default revision 2).
 */
export type HelperId = 'router' | 'compaction' | 'recall' | 'webPlan' | 'verify' | 'subagent';

export interface MediaView {
  mediaId: string;
  kind: 'image';
  mimeType: string;
  fileName: string;
  sizeBytes: number;
  width: number;
  height: number;
  sha256?: string;
  createdAt?: string;
  url: string;
}

/** Where a resolved model value came from: default < discovered (endpoint) < catalog < probe < manual. */
export type ValueSource = 'default' | 'discovered' | 'catalog' | 'probe' | 'manual';
export type ModelField = 'contextTokens' | 'inputTokens' | 'maxOutputTokens' | 'inputModalities' | 'outputModalities'
  | 'reasoningWire' | 'reasoningLevels' | 'thinkingMode' | 'thinkingBudgetTokens' | 'toolCapabilities'
  | 'clientToolCalling' | 'continuationMode' | 'harnessPreset' | 'serviceTier' | 'pricing';

/** Per-million-token prices in the vendor's billing currency (never converted). */
export interface PricingView {
  currency?: 'USD' | 'CNY';
  input?: number; output?: number; cacheRead?: number; cacheWrite?: number;
  tiers?: { above: number; input?: number; output?: number; cacheRead?: number; cacheWrite?: number }[];
  offPeak?: { multiplier: number; peakUtc: string[]; weekdaysOnly: boolean };
}

export interface ModelProfileView {
  modelProfileId: string;
  routeId: string;
  routeLabel: string;
  modelId: string;
  displayName: string;
  /** Disabled models stay configured but are hidden from pickers. */
  enabled: boolean;
  /** Mirror of the route's protocol. */
  protocol: 'openai-responses' | 'openai-chat-completions' | 'anthropic-messages' | string;
  reasoningWire: 'none' | 'openai-responses' | 'openai-chat-effort' | 'anthropic-effort' | string;
  contextTokens: number;
  inputTokens: number;
  maxOutputTokens: number;
  reasoningLevels: string;
  thinkingMode: 'provider-default' | 'enabled' | 'adaptive' | 'disabled' | 'manual';
  thinkingBudgetTokens: number;
  continuationMode: 'none' | 'native';
  harnessPreset: 'lean' | 'balanced' | 'reinforced';
  clientToolCalling: 'none' | 'openai-chat-tools' | 'openai-responses-tools' | 'anthropic-tools';
  inputModalities: string;
  outputModalities: string;
  toolCapabilities: string;
  serviceTier: string;
  pricing: PricingView;
  /** Data retention of this deployment per the catalog: none / 30d / training / '' (unknown). */
  retention: '' | 'none' | '30d' | 'training';
  sources: Partial<Record<ModelField, ValueSource>>;
  overrides: ModelOverrides;
  /** '<preset>/<route>/<model>' when a reviewed catalog entry applies, else ''. */
  catalogKey: string;
  catalogVerified: boolean;
  /** Last connection test (model.probe), or null. It records what happened; it changes no capability. */
  probe: ProbeResultView | null;
  /** Last capability probe (model.capabilities.probe, spec D19), or null. Its conclusive results are the probe layer of the resolved values. */
  capabilityProbe: CapabilityProbeView | null;
  /** Whether the route's model list (last read) contains this Model ID; null when never read. */
  listed: boolean | null;
}

export interface ProbeResultView {
  checkedAt: string; ok: boolean; code?: string; summary?: string;
  totalMs: number; firstOutputMs?: number; usageReported: boolean; reasoningSeen: boolean; reply: string;
}

export interface ModelListView {
  routeId: string; ok: boolean; status?: number; code?: string; summary?: string;
  models?: { id: string; contextTokens?: number; maxOutputTokens?: number; inputModalities?: string }[];
}

export interface BalanceView {
  checkedAt: string; ok: boolean; available?: boolean; summary?: string;
  balances?: { currency: string; total: string; granted: string; toppedUp: string }[];
}

/** Estimated spend guard of an account, with the spend so far in each window that has a limit. */
export interface BudgetView { currency?: 'USD' | 'CNY'; daily?: number; monthly?: number; spentToday?: number; spentMonth?: number }

export interface BudgetReachedView { period: 'daily' | 'monthly'; limit: number; spent: number; currency: 'USD' | 'CNY' }

/** One endpoint of an account. Protocol is fixed after creation. */
export interface RouteView {
  routeId: string;
  /** Preset route this was created from ('' = custom). */
  presetRouteId: string;
  label: string;
  protocol: 'openai-responses' | 'openai-chat-completions' | 'anthropic-messages' | string;
  baseUrl: string;
  authMode: 'bearer' | 'x-api-key' | 'api-key' | string;
  host: string;
  /** Still exactly the reviewed preset endpoint (catalog applies). */
  reviewed: boolean;
  /** False when a key exists but was never confirmed for this host: nothing is sent until the user confirms. */
  hostConfirmed: boolean;
  dialect: RouteDialectView;
  headers: Record<string, string>;
}

export interface RouteDialectView {
  outputCap?: 'max_tokens' | 'max_completion_tokens' | 'max_output_tokens' | 'omit';
  streamUsage?: boolean;
  thinkingParam?: 'none' | 'thinking-type';
  reasoningReplay?: 'never' | 'with-tools' | 'always';
  store?: boolean;
  autoCache?: boolean;
  /** Concurrent main generation calls on this route (any protocol); absent = no cap. Extra turns queue (stream stage 'queued'). */
  maxConcurrent?: number;
}

/** An account: one API Key (native secure store) shared by its routes. */
export interface ProviderView {
  providerId: string;
  displayName: string;
  presetId: string;
  presetName: string;
  presetKind: 'vendor' | 'gateway' | 'custom';
  /** modelId of the first enabled Model Profile, or '' when none is configured. */
  model: string;
  routes: RouteView[];
  models: ModelProfileView[];
  hasCredential: boolean;
  /** Last key check or turn outcome: valid / invalid (401) / unverified (endpoint has no model list) / error / unknown. */
  keyStatus: 'unknown' | 'valid' | 'invalid' | 'unverified' | 'error' | string;
  keyCheckedAt: string;
  budget: BudgetView;
  /** The preset has a balance endpoint (provider.balance.get). */
  balanceSupported: boolean;
  configured: boolean;
}


  snapshotId: string;
  /** Local session the branch was cut from; it may have been deleted since. */
  sourceSessionId: string;
  sourceTitle: string;
  createdAt: string;
  itemCount: number;
  sourceItemCount?: number;
  truncated?: boolean;
}

export interface WebSourceView {
  url: string;
  title: string;
  backend?: string;
}

export interface VerificationCheckView {
  rule: 'constraint_adherence' | 'evidence_integrity' | 'completion_honesty' | 'uncertainty_honesty' | 'material_factual_support';
  result: 'pass' | 'fail' | 'unknown';
  severity: 'hard' | 'major' | 'minor';
  evidenceIds: string[];
  note: string;
}

export interface VerificationAttemptView {
  /** cancelled: stopped by the user while verifying or repairing; the answer stands (spec D11). */
  status: 'valid' | 'invalid' | 'unavailable' | 'cancelled';
  verdict?: 'pass' | 'fail' | 'partial';
  selfConfidence?: number;
  checks?: VerificationCheckView[];
  coverageComplete: boolean;
  coverageReason?: string;
  invalidReason?: string;
  policyVersion?: string;
  risk?: number;
  action?: 'accept' | 'repair' | 'abstain';
  features?: number[];
}

export interface VerificationView {
  status: 'valid' | 'invalid' | 'unavailable';
  verdict?: 'pass' | 'fail' | 'partial';
  selfConfidence?: number;
  checks?: VerificationCheckView[];
  coverageComplete: boolean;
  coverageReason?: string;
  invalidReason?: string;
  policyVersion?: string;
  risk?: number;
  recommendedAction?: 'accept' | 'repair' | 'abstain';
  action?: 'accept' | 'repair' | 'abstain';
  calibrationKey?: string;
  calibrationLabels?: number;
  learnedCalibration?: boolean;
  features?: number[];
  repaired?: boolean;
  /** The first answer, kept when a repair replaced it, so the two can be compared. */
  preRepairText?: string;
  attempts?: VerificationAttemptView[];
  repairAttempted?: boolean;
  repaired?: boolean;
  /** A repair ran but its re-check did not accept it: the first answer is shown and keeps its own verdict. */
  repairRejected?: boolean;
  /** The rejected repair, kept so the two versions can be compared. */
  repairCandidateText?: string;
  repairLimitReached?: boolean;
  feedbackLabel?: 'accurate' | 'false_alarm' | 'missed_error';
  feedbackRecordedAt?: string;
}

export interface SessionView {
  localSessionId: string;
  title: string;
  /**
   * List rows only: a session that never had a message. Such sessions are listed only while they hold an unsent
   * draft (`draft`, ≤ 80 chars); unused ones are deleted when left or on start, and "new Chat" replaces them.
   */
  blank?: boolean;
  draft?: string;
  providerId: string;
  providerName: string;
  modelProfileId?: string;
  model: string;
  modelName: string;
  reasoningLevels: string;
  reasoningEffort: string;
  thinkingMode: 'provider-default' | 'adaptive' | 'disabled' | 'manual';
  thinkingBudgetTokens: number;
  continuationMode: 'none' | 'native';
  harnessPreset: 'lean' | 'balanced' | 'reinforced';
  clientToolCalling: 'none' | 'openai-chat-tools' | 'openai-responses-tools' | 'anthropic-tools';
  subagentMode: SubagentMode;
  contextMode: ContextMode;
  effectiveContextMode: 'minimal' | 'standard' | 'smart';
  inputModalities: string;
  outputModalities: string;
  nativeWebCapabilities: string;
  webMode: WebMode;
  effectiveWebBackend: string;
  state: SessionState;
  updatedAt: string;
  preview?: string;
  origin?: OriginView;
  hasContextCheckpoint?: boolean;
}

export type TimelineItem =
  | { localSeq?: number; kind: 'message'; role: 'user' | 'assistant' | 'system'; turnId?: string; /** 1 / -1 when rated. */ rating?: 1 | -1; /** Answers to this question (its versions), when more than one. */ answers?: number; /** Spec G3: position among the message's versions, when it has others. */ version?: { index: number; count: number; prevSeq?: number; nextSeq?: number }; text: string; reasoning?: string; verification?: VerificationView; attachments?: MediaView[]; sources?: WebSourceView[]; webBackend?: string; providerId?: string; providerName?: string; modelProfileId?: string; model?: string; modelName?: string; reasoningAvailable?: boolean; reasoningEffort?: string; contextMode?: ContextMode; effectiveContextMode?: 'minimal' | 'standard' | 'smart'; inherited?: boolean; incomplete?: boolean; terminalCode?: string; streaming?: boolean; streamStage?: 'preparing' | 'compacting' | 'connecting' | 'connected' | 'retrying' | 'queued' | 'reasoning' | 'output' | 'tool' | 'web_planning' | 'web_search' | 'web_fetch' | 'verifying' | 'repairing'; createdAt: string }
  /** Projected from the turn record (not a stored timeline row): placed right after the turn's reply, or after its user row when there is none. */
  | { localSeq?: number; kind: 'usage'; turnId?: string; inputTokens?: number; outputTokens?: number; cachedInputTokens?: number; cacheWriteInputTokens?: number; cacheHitBaseTokens?: number; reasoningTokens?: number; childInputTokens?: number; childOutputTokens?: number; subagentCount?: number; subagentCallCount?: number; auxiliaryCallCount?: number; modelCallCount?: number; auxiliaryInputTokens?: number; auxiliaryOutputTokens?: number; usageIncomplete?: boolean; totalInputTokens?: number; totalOutputTokens?: number; /** Input the last main call sent (inputTokens sums every main call of a tool loop). */ lastMainInputTokens?: number; /** Rate-limit / overload retries before output. */ providerRetries?: number; /** Estimated from the profile's prices, all calls of the turn. */ cost?: { currency: 'USD' | 'CNY'; amount: number; partial: boolean; offPeak: boolean }; outputTokensPerSecond?: number; firstOutputLatencyMs?: number; outputDurationMs?: number; requestDurationMs?: number; context?: { truncated?: boolean; estimatedInputTokens?: number; inheritedItemCount?: number; localItemCount?: number; omittedItemCount?: number }; createdAt: string }
  | { localSeq: number; kind: 'error'; code: string; summary: string; createdAt: string };

/** One pipeline stage of a turn. Skipped stages say why (`reason`); `usage` is the stage's own model calls. */
export interface TurnSpanView {
  stage: 'accept' | 'context' | 'route' | 'checkpoint' | 'web_plan' | 'tools' | 'web_retrieve' | 'generate' | 'verify' | 'repair' | 'subagent' | string;
  status: 'ok' | 'skipped' | 'failed' | 'cancelled' | 'open';
  startedAtMs?: number;
  /** -1 when the stage has no duration of its own (subagent calls inside client-tool rounds). */
  durationMs: number;
  reason?: string;
  usage?: { calls: number; inputTokens?: number; outputTokens?: number };
  /**
   * Small decision summary, e.g. route {domain, taskType, confidence}, web_plan {useWeb, queries}, verify {verdict, risk, action, repaired}.
   * Helper stages add `callFailure` (provider error code, "timeout" or "cancelled") when their last model call failed.
   */
  detail?: Record<string, unknown>;
}

export interface TurnTraceView {
  turnId: string;
  userSeq: number;
  startedAt: string;
  endedAt: string | null;
  status: 'running' | 'ok' | 'failed' | 'cancelled';
  terminalCode: string;
  /**
   * The turn's frozen configuration: model, protocol, context mode, harness preset, Web mode, effort, limits,
   * helpersOff (HelperId[]), and for a retry retryOf (the archived failed turn) and attempt (2, 3, ...).
   */
  config: Record<string, unknown>;
  /** For a retry: the outcome of the attempt it replaced (from turn_archive). */
  previousAttempt?: { turnId: string; startedAt: string; endedAt: string | null; status: string; terminalCode: string; attempt: number; usage: Record<string, unknown>; reason: string };
  usage: Record<string, unknown>;
  spans: TurnSpanView[];
  /** Model calls with recorded wire payloads (metadata only); empty when capture was off or the turn is old. */
  calls: { callIndex: number; stage: string; parts: number; chars: number }[];
}

/**
 * Redacted wire records of one model call, in arrival order (a pause_turn continuation adds parts).
 * request body: JSON {url, body} or {url, truncated, bodyText}; response body: JSON {status, truncated, text}.
 * Request headers and the API key are never recorded; inline base64 is replaced by its length.
 */
export interface TurnPayloadView {
  turnId: string;
  callIndex: number;
  stage: string;
  parts: { part: number; direction: 'request' | 'response'; createdAt: string; body: string }[];
}

/** Every answer to one question: its versions, first answer first (spec D9). */
export interface AnswerGroupView {
  userSeq: number;
  question: string;
  answers: { turnId: string; status: string; terminalCode: string; config: Record<string, unknown>; usage: Record<string, unknown>; rating?: 1 | -1; original: boolean; startedAt: string; /** The answer's row: switching to it shows this version. */ answerSeq?: number; text?: string; verification?: { verdict: string; action: string; repaired: boolean } }[];
}

/** In-use statistics of finished turns in the window, grouped by stats.get's groupBy. */
export interface StatsRowView {
  /** The grouping values of this row: any of model / protocol / effort / mode / helpers ('all_on' or switched-off ids joined by '+'). */
  group: { model?: string; protocol?: string; effort?: string; mode?: string; helpers?: string };
  turns: number; ok: number; failed: number; cancelled: number;
  medianFirstOutputMs?: number; medianDurationMs?: number; avgTokens?: number;
  /** cachedInputTokens / cacheHitBaseTokens over the turns that reported both. */
  cacheHitRate?: number;
  /** Estimated cost per currency; partialCostTurns had calls without usage (the real cost is higher). */
  cost?: Partial<Record<'USD' | 'CNY', number>>; costedTurns?: number; partialCostTurns?: number;
  mainTokens: number; helperTokens: number; helperShare?: number;
  routed: number; checkpointed: number; webUsed: number; verified: number; repaired: number;
  ratedUp: number; ratedDown: number;
  /** Of `turns`, how many have since been retried, rewound or deleted from their Chat. */
  archived: number;
}

export interface SessionDetailView extends SessionView {
  messages: TimelineItem[];
  displayOmittedInheritedItems?: number;
  composerDraft: string;
  draftMedia: MediaView[];
  canAttachImage: boolean;
  canSend: boolean;
  canInterrupt: boolean;
  canConfigureTurn: boolean;
  contextPreparing?: boolean;
  webPreparingStage?: 'web_search' | 'web_fetch' | string;
  postProcessingStage?: 'verifying' | 'repairing' | string;
  /** localSeq of the question a retry would send again; present only while idle after a failed or unfinished last turn. */
  retryLocalSeq?: number;
  /** Spec H5: the path's end has more after it; `chat.message.version` with this seq returns to the newest message there. */
  resumeLocalSeq?: number;
  /** The session's frozen copy differs from its (enabled) model profile, which was edited; `chat.session.model.set` with the same id applies it. */
  profileChanged?: boolean;
  /** Usable input window (context − output − safety margin), the same budget the context builder fills. */
  contextBudgetTokens?: number;
}

export interface ConversationStreamPatch {
  scope: 'conversation.stream';
  localSessionId: string;
  session: Partial<Pick<SessionDetailView, 'state' | 'canSend' | 'canInterrupt' | 'canConfigureTurn' | 'contextPreparing'>> & {
    webPreparingStage?: string | null;
    postProcessingStage?: string | null;
  };
  item: Extract<TimelineItem, { kind: 'message' }>;
}

export interface UsageTodayView {
  since: string;
  turns: number;
  inputTokens: number;
  outputTokens: number;
  cachedInputTokens: number;
  cacheHitBaseTokens: number;
}

export interface AppViewModel {
  route: Route;
  now: string;
  /** resolvedTheme is computed natively from the Android night mode when theme is 'system'. */
  appearance: { theme: Theme; resolvedTheme: 'light' | 'dark'; tone: Tone; delight: boolean };
  /** Local-day usage across sessions (turns started since local midnight). */
  usageToday?: UsageTodayView;
  providers: ProviderView[];
  sessions: SessionView[];
  activeLocalSessionId?: string;
  activeSession?: SessionDetailView;
  ui: { pendingRequestIds: string[]; showReasoning: boolean; crossChatMemoryEnabled: boolean; wireCapture: boolean; notifyInBackground: boolean; customInstruction: string; helpersOff: HelperId[]; helperModel: HelperModelView; defaultContextMode: ContextMode; defaultWebMode: WebMode; standardSubagentMode: SubagentMode; mascot: MascotState; banner?: { level: 'info' | 'warning' | 'error'; text: string } };
}

declare global {
  interface Window {
    MoyuHost?: { postMessage(message: string): void };
    /** Called by MainActivity on Android Back; returns true when a drawer, sheet or dialog was closed. */
    moyuHandleBack?: () => boolean;
  }
  interface WindowEventMap { 'moyu:view': CustomEvent<{ version: 1; type: string; revision?: number; view?: AppViewModel; patch?: ConversationStreamPatch; requestId?: string; ok?: boolean; error?: { summary?: string }; data?: unknown }> }
}

/** Account editor payload. `routes`, when present, is the complete set. */
export interface AccountForm {
  providerId?: string;
  /** Creation only; fixed afterwards. */
  presetId?: string;
  displayName?: string;
  /** Replaces the account budget; {} clears it. */
  budget?: { currency?: 'USD' | 'CNY'; daily?: number | string; monthly?: number | string };
  routes?: {
    routeId?: string;
    presetRouteId?: string;
    label?: string;
    /** Required for a new route; must match an existing one. */
    protocol?: string;
    baseUrl?: string;
    authMode?: string;
    dialect?: RouteDialectView;
    /** Non-secret extra headers; credential-shaped names are refused natively. */
    headers?: Record<string, string>;
  }[];
}

/** Values the user set explicitly; numbers may travel as strings and are parsed natively. */
export type ModelOverrides = Partial<Record<ModelField, string | number | PricingView>>;

export interface ModelForm {
  providerId: string;
  routeId: string;
  modelProfileId?: string;
  modelId: string;
  displayName?: string;
  enabled?: boolean;
  /** The complete override set (replaces the stored one). */
  overrides?: ModelOverrides;
}

export interface ModelEditorOption { value: string; hint: string; protocols?: string[] }
export interface PresetView {
  id: string; name: string; kind: 'vendor' | 'gateway' | 'custom'; note: string; console: string; pricingUrl: string;
  suggestedHeaders: string[];
  routes: { id: string; label: string; protocol: string; baseUrl: string; authMode: string; dialect: RouteDialectView }[];
}
export interface ModelEditorOptions {
  catalogVersion: string;
  presets: PresetView[];
  protocol: ModelEditorOption[];
  authMode: ModelEditorOption[];
  outputCap: ModelEditorOption[];
  thinkingParam: ModelEditorOption[];
  reasoningReplay: ModelEditorOption[];
  reasoningWire: ModelEditorOption[];
  thinkingMode: ModelEditorOption[];
  clientToolCalling: ModelEditorOption[];
  continuationMode: ModelEditorOption[];
  serviceTier: ModelEditorOption[];
  harnessPreset: ModelEditorOption[];
  reasoningLevels: string[];
  /** Display names of value sources. */
  sources: Record<ValueSource, string>;
}

export interface CatalogModelView { modelId: string; name: string; verified: boolean; retention: string; pricing?: PricingView; added?: boolean }
export interface ModelPreview {
  fields: { field: ModelField; label: string; value: unknown; source: ValueSource }[];
  /** '' when saving would succeed. */
  error: string;
  catalog: CatalogModelView | null;
  /** Whether the output limit goes on the wire (undeclared limits are left to the server, except on Messages). */
  sendsOutputCap: boolean;
}

/** Native → WebView message shown in the page's toast (replaces Android Toasts). */
export interface NoticeEnvelope { version: 1; type: 'notice'; level: 'info' | 'warning' | 'error'; text: string }

/** Boss-key disguise, the waiting mini-game's best score and the day the rare koi colours were rolled. */
export interface MascotState { disguise: boolean; gameBest: number; koiDay: string }

/** Spec D15: helpers that can run on the helper model. */
export type HelperModelUse = 'title' | 'router' | 'web_plan' | 'compaction' | 'verify' | 'subagent';

/** Spec D15: the helper model setting. modelName is absent when none is chosen; missing when the chosen profile is gone or disabled. */
export interface HelperModelView { modelProfileId: string; modelName?: string; missing?: boolean; use: HelperModelUse[] }

/** Spec D19: the last capability probe of a model (ModelProfileView.capabilityProbe). */
export interface CapabilityProbeView { checkedAt: string; host: string; checks: { field: 'inputModalities' | 'clientToolCalling' | 'toolCapabilities'; verdict: 'yes' | 'no' | 'unknown'; code?: string; summary?: string }[] }

/** Spec D16, D17: the open side-by-side group of the conversation shown (ActiveSessionView.compare). Blind: no modelName, contextMode or usage until decided; shuffled, labelled A/B/C. */
export interface CompareView {
  group: string; questionSeq: number; blind: boolean; running: boolean;
  answers: { turnId: string; label: string; status: string; localSeq?: number; text: string; streaming?: boolean; streamStage?: string; incomplete?: boolean; failed?: boolean; terminalCode?: string; modelName?: string; contextMode?: string; usage?: Record<string, unknown> }[];
}
