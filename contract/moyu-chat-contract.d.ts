export type RequestId = string;
export type Theme = 'system' | 'light' | 'dark';
export type Route = 'home' | 'conversation' | 'providers' | 'imports' | 'handoffs' | 'settings';
export type SessionState = 'idle' | 'running' | 'completed' | 'failed';
export type ContextMode = 'minimal' | 'standard' | 'smart';
export type WebMode = 'off' | 'auto' | 'on';
export type ImportSource = 'chatgpt' | 'claude';

export interface UiIntent<T extends string = string, P = unknown> {
  version: 1;
  type: T;
  requestId: RequestId;
  payload: P;
}

export type MoyuChatIntent =
  | UiIntent<'app.ready', { uiVersion: string }>
  | UiIntent<'view.reload', Record<string, never>>
  | UiIntent<'appearance.set', { theme: Theme }>
  | UiIntent<'chat.reasoning.visibility.set', { show: boolean }>
  | UiIntent<'memory.global.set', { enabled: boolean }>
  | UiIntent<'context.mode.default.set', { mode: ContextMode }>
  | UiIntent<'web.mode.default.set', { mode: WebMode }>
  | UiIntent<'external.open', { url: string }>
  | UiIntent<'context.intelligence.set', { enabled: boolean }>
  | UiIntent<'nav.open', { route: Route }>
  | UiIntent<'chat.session.create', { providerId: string; modelProfileId?: string; model?: string; reasoningEffort?: string; contextMode?: ContextMode; webMode?: WebMode; title?: string }>
  | UiIntent<'chat.session.open', { localSessionId: string }>
  | UiIntent<'chat.session.model.set', { localSessionId: string; modelProfileId: string }>
  | UiIntent<'chat.session.reasoning.set', { localSessionId: string; reasoningEffort: string }>
  | UiIntent<'chat.session.send', { localSessionId: string; text: string }>
  | UiIntent<'chat.session.saveDraft', { localSessionId: string; text: string }>
  | UiIntent<'chat.attachment.pick', { localSessionId: string }>
  | UiIntent<'chat.attachment.remove', { localSessionId: string; mediaId: string }>
  | UiIntent<'chat.session.interrupt', { localSessionId: string }>
  | UiIntent<'chat.session.delete', { localSessionId: string }>
  | UiIntent<'chat.message.edit', { localSessionId: string; localSeq: number }>
  | UiIntent<'chat.message.rollback', { localSessionId: string; localSeq: number }>
  | UiIntent<'provider.configure.open', { providerId?: string }>
  | UiIntent<'model.configure.open', { providerId: string; modelProfileId?: string }>
  | UiIntent<'import.file.pick', { source: ImportSource }>
  | UiIntent<'import.session.open', { snapshotId: string; contextMode?: ContextMode; webMode?: WebMode }>
  | UiIntent<'import.session.delete', { snapshotId: string }>
  | UiIntent<'verification.feedback', { localSessionId: string; localSeq: number; label: 'accurate' | 'false_alarm' | 'missed_error' }>
  | UiIntent<'branch.sources.refresh', Record<string, never>>
  | UiIntent<'branch.create.remote', { sourceId: string; throughSeq?: number; contextMode?: ContextMode; webMode?: WebMode }>
  | UiIntent<'branch.create.local', { localSessionId: string; throughSeq?: number }>
  | UiIntent<'handoff.create', { localSessionId: string }>
  | UiIntent<'handoff.submit', { handoffId: string }>;

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
  contextTokens: number;
  inputTokens: number;
  maxOutputTokens: number;
  reasoningLevels: string;
  defaultReasoning: string;
  inputModalities: string;
  outputModalities: string;
  toolCapabilities: string;
}

export interface ProviderView {
  providerId: string;
  displayName: string;
  protocol: 'openai-responses' | 'openai-chat-completions' | 'anthropic-messages' | string;
  authMode: 'bearer' | 'x-api-key' | 'api-key' | string;
  reasoningWire: 'none' | 'openai-responses' | 'openai-chat-effort' | 'anthropic-effort' | string;
  model: string;
  models: ModelProfileView[];
  hasCredential: boolean;
  configured: boolean;
}

export interface OriginView {
  snapshotId: string;
  sourceType: 'remote' | 'chat' | 'import-chatgpt' | 'import-claude';
  sourceTitle: string;
  sourceRuntime?: string;
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

export interface SessionView {
  localSessionId: string;
  title: string;
  providerId: string;
  providerName: string;
  modelProfileId?: string;
  model: string;
  modelName: string;
  reasoningLevels: string;
  defaultReasoning: string;
  reasoningEffort: string;
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
  | { localSeq?: number; kind: 'message'; role: 'user' | 'assistant' | 'system'; text: string; reasoning?: string; attachments?: MediaView[]; sources?: WebSourceView[]; webBackend?: string; providerId?: string; providerName?: string; modelProfileId?: string; model?: string; modelName?: string; reasoningAvailable?: boolean; reasoningEffort?: string; contextMode?: ContextMode; effectiveContextMode?: 'minimal' | 'standard' | 'smart'; inherited?: boolean; incomplete?: boolean; terminalCode?: string; streaming?: boolean; streamStage?: 'preparing' | 'connecting' | 'connected' | 'reasoning' | 'output' | 'web_search' | 'web_fetch' | 'verifying' | 'repairing'; createdAt: string }
  | { localSeq: number; kind: 'usage'; inputTokens?: number; outputTokens?: number; cachedInputTokens?: number; cacheWriteInputTokens?: number; cacheHitBaseTokens?: number; reasoningTokens?: number; outputTokensPerSecond?: number; firstOutputLatencyMs?: number; outputDurationMs?: number; requestDurationMs?: number; context?: { truncated?: boolean; estimatedInputTokens?: number; inheritedItemCount?: number; localItemCount?: number; omittedItemCount?: number }; createdAt: string }
  | { localSeq: number; kind: 'error'; code: string; summary: string; createdAt: string };

export interface SessionDetailView extends SessionView {
  messages: TimelineItem[];
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

export interface ImportedSourceView {
  snapshotId: string;
  sourceType: 'import-chatgpt' | 'import-claude';
  sourceTitle: string;
  sourceRuntime?: string;
  createdAt: string;
  itemCount: number;
  sourceItemCount: number;
  truncated: boolean;
}

export interface RemoteBridgeView {
  available: boolean;
  contractVersion?: number;
  reason?: string;
}

export interface RemoteSourceView {
  sourceId: string;
  title: string;
  runtime?: string;
  updatedAt: string;
  cachedThroughSeq: number;
  complete: boolean;
}

export interface HandoffView {
  handoffId: string;
  branchSessionId: string;
  state: 'draft' | 'submitted' | 'failed';
  createdAt: string;
  summary: string;
}

export interface AppViewModel {
  route: Route;
  now: string;
  appearance: { theme: Theme; resolvedTheme: 'light' | 'dark' };
  providers: ProviderView[];
  sessions: SessionView[];
  activeLocalSessionId?: string;
  activeSession?: SessionDetailView;
  remoteBridge: RemoteBridgeView;
  remoteSources: RemoteSourceView[];
  importedSources: ImportedSourceView[];
  handoffs: HandoffView[];
  ui: { pendingRequestIds: string[]; showReasoning: boolean; crossChatMemoryEnabled: boolean; importInProgress: boolean; defaultContextMode: ContextMode; defaultWebMode: WebMode; banner?: { level: 'info' | 'warning' | 'error'; text: string } };
}

declare global {
  interface Window { MoyuHost?: { postMessage(message: string): void }; }
  interface WindowEventMap { 'moyu:view': CustomEvent<{ version: 1; type: string; revision?: number; view?: AppViewModel; requestId?: string; ok?: boolean; error?: { summary?: string } }> }
}
