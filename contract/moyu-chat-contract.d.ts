export type RequestId = string;
export type Theme = 'system' | 'light' | 'dark';
/** Background tone of the soft palette; colours themselves live in src/styles.css and MainActivity.canvasColor. */
export type Tone = 'sage' | 'warm' | 'neutral';
export type Route = 'home' | 'conversation' | 'providers' | 'settings';
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
  | UiIntent<'chat.session.model.set', { localSessionId: string; modelProfileId: string }>
  | UiIntent<'chat.session.reasoning.set', { localSessionId: string; reasoningEffort: string }>
  | UiIntent<'chat.session.web.set', { localSessionId: string; webMode: WebMode }>
  | UiIntent<'chat.session.send', { localSessionId: string; text: string; reasoningEffort?: string; webMode?: WebMode }>
  | UiIntent<'chat.session.saveDraft', { localSessionId: string; text: string }>
  | UiIntent<'chat.attachment.pick', { localSessionId: string }>
  | UiIntent<'chat.attachment.remove', { localSessionId: string; mediaId: string }>
  | UiIntent<'chat.session.interrupt', { localSessionId: string }>
  | UiIntent<'chat.session.delete', { localSessionId: string }>
  | UiIntent<'chat.message.edit', { localSessionId: string; localSeq: number }>
  | UiIntent<'chat.message.rollback', { localSessionId: string; localSeq: number }>
  | UiIntent<'provider.configure.open', { providerId?: string }>
  | UiIntent<'model.configure.open', { providerId: string; modelProfileId?: string }>
  | UiIntent<'verification.feedback', { localSessionId: string; localSeq: number; label: 'accurate' | 'false_alarm' | 'missed_error' }>
  | UiIntent<'branch.create.local', { localSessionId: string; throughSeq?: number }>
  /** Read-only; the intent.result carries `data: TurnTraceView`. Traces are never pushed with the view. */
  | UiIntent<'turn.trace.get', { localSessionId: string; turnId: string }>;

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

export interface ModelProfileView {
  modelProfileId: string;
  modelId: string;
  displayName: string;
  protocol: 'openai-responses' | 'openai-chat-completions' | 'anthropic-messages' | string;
  authMode: 'bearer' | 'x-api-key' | 'api-key' | string;
  reasoningWire: 'none' | 'openai-responses' | 'openai-chat-effort' | 'anthropic-effort' | string;
  contextTokens: number;
  inputTokens: number;
  maxOutputTokens: number;
  reasoningLevels: string;
  thinkingMode: 'provider-default' | 'adaptive' | 'disabled' | 'manual';
  thinkingBudgetTokens: number;
  continuationMode: 'none' | 'native';
  configMode: 'auto' | 'manual';
  catalogKey: string;
  catalogVersion: string;
  catalogSource: string;
  harnessPreset: 'lean' | 'balanced' | 'reinforced';
  clientToolCalling: 'none' | 'openai-chat-tools' | 'openai-responses-tools' | 'anthropic-tools';
  inputModalities: string;
  outputModalities: string;
  toolCapabilities: string;
}

export interface ProviderView {
  providerId: string;
  displayName: string;
  baseUrl: string;
  /** modelId of the first Model Profile, or '' when none is configured. */
  model: string;
  models: ModelProfileView[];
  hasCredential: boolean;
  configured: boolean;
}

export interface OriginView {
  snapshotId: string;
  /** Local session the branch was cut from; it may have been deleted since. */
  sourceSessionId: string;
  sourceTitle: string;
  createdAt: string;
  itemCount: number;
  sourceItemCount?: number;
  truncated?: boolean;
}

export interface TaskProfileView {
  version: 1;
  domain: 'general' | 'software' | 'embedded' | 'research' | 'writing' | 'planning' | 'math' | 'creative';
  taskType: 'answer' | 'explain' | 'debug' | 'review' | 'research' | 'plan' | 'write' | 'compare' | 'analyze';
  depth: 'concise' | 'standard' | 'expert';
  responseMode: 'direct' | 'structured' | 'tutorial' | 'diagnostic';
  contextPolicy: 'recent' | 'checkpoint_recent';
  modules: string[];
  confidence: number;
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
  status: 'valid' | 'invalid' | 'unavailable';
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
  attempts?: VerificationAttemptView[];
  repairAttempted?: boolean;
  repaired?: boolean;
  repairLimitReached?: boolean;
  feedbackLabel?: 'accurate' | 'false_alarm' | 'missed_error';
  feedbackRecordedAt?: string;
}

export interface SessionView {
  localSessionId: string;
  title: string;
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
  taskProfile?: TaskProfileView;
  hasContextCheckpoint?: boolean;
}

export type TimelineItem =
  | { localSeq?: number; kind: 'message'; role: 'user' | 'assistant' | 'system'; turnId?: string; text: string; reasoning?: string; verification?: VerificationView; attachments?: MediaView[]; sources?: WebSourceView[]; webBackend?: string; providerId?: string; providerName?: string; modelProfileId?: string; model?: string; modelName?: string; reasoningAvailable?: boolean; reasoningEffort?: string; contextMode?: ContextMode; effectiveContextMode?: 'minimal' | 'standard' | 'smart'; inherited?: boolean; incomplete?: boolean; terminalCode?: string; streaming?: boolean; streamStage?: 'preparing' | 'connecting' | 'connected' | 'reasoning' | 'output' | 'tool' | 'web_planning' | 'web_search' | 'web_fetch' | 'verifying' | 'repairing'; createdAt: string }
  /** Projected from the turn record (not a stored timeline row): placed right after the turn's reply, or after its user row when there is none. */
  | { localSeq?: number; kind: 'usage'; turnId?: string; inputTokens?: number; outputTokens?: number; cachedInputTokens?: number; cacheWriteInputTokens?: number; cacheHitBaseTokens?: number; reasoningTokens?: number; childInputTokens?: number; childOutputTokens?: number; subagentCount?: number; subagentCallCount?: number; auxiliaryCallCount?: number; modelCallCount?: number; auxiliaryInputTokens?: number; auxiliaryOutputTokens?: number; usageIncomplete?: boolean; totalInputTokens?: number; totalOutputTokens?: number; outputTokensPerSecond?: number; firstOutputLatencyMs?: number; outputDurationMs?: number; requestDurationMs?: number; context?: { truncated?: boolean; estimatedInputTokens?: number; inheritedItemCount?: number; localItemCount?: number; omittedItemCount?: number }; createdAt: string }
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
  /** Small decision summary, e.g. route {domain, taskType, confidence}, web_plan {useWeb, queries}, verify {verdict, risk, action, repaired}. */
  detail?: Record<string, unknown>;
}

export interface TurnTraceView {
  turnId: string;
  userSeq: number;
  startedAt: string;
  endedAt: string | null;
  status: 'running' | 'ok' | 'failed' | 'cancelled';
  terminalCode: string;
  /** The turn's frozen configuration: model, protocol, context mode, harness preset, Web mode, effort, limits. */
  config: Record<string, unknown>;
  usage: Record<string, unknown>;
  spans: TurnSpanView[];
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
  ui: { pendingRequestIds: string[]; showReasoning: boolean; crossChatMemoryEnabled: boolean; defaultContextMode: ContextMode; defaultWebMode: WebMode; standardSubagentMode: SubagentMode; banner?: { level: 'info' | 'warning' | 'error'; text: string } };
}

declare global {
  interface Window {
    MoyuHost?: { postMessage(message: string): void };
    /** Called by MainActivity on Android Back; returns true when a drawer, sheet or dialog was closed. */
    moyuHandleBack?: () => boolean;
  }
  interface WindowEventMap { 'moyu:view': CustomEvent<{ version: 1; type: string; revision?: number; view?: AppViewModel; patch?: ConversationStreamPatch; requestId?: string; ok?: boolean; error?: { summary?: string }; data?: unknown }> }
}
