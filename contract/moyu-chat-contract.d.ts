export type RequestId = string;
export type Theme = 'system' | 'light' | 'dark';
export type Route = 'home' | 'conversation' | 'providers' | 'imports' | 'handoffs' | 'settings';
export type SessionState = 'idle' | 'running' | 'completed' | 'failed';

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
  | UiIntent<'nav.open', { route: Route }>
  | UiIntent<'chat.session.create', { providerId: string; modelProfileId?: string; model?: string; reasoningEffort?: string; title?: string }>
  | UiIntent<'chat.session.open', { localSessionId: string }>
  | UiIntent<'chat.session.send', { localSessionId: string; text: string }>
  | UiIntent<'chat.session.saveDraft', { localSessionId: string; text: string }>
  | UiIntent<'chat.session.interrupt', { localSessionId: string }>
  | UiIntent<'chat.session.delete', { localSessionId: string }>
  | UiIntent<'provider.configure.open', { providerId?: string }>
  | UiIntent<'branch.sources.refresh', Record<string, never>>
  | UiIntent<'branch.create.remote', { sourceId: string; throughSeq?: number }>
  | UiIntent<'branch.create.local', { localSessionId: string; throughSeq?: number }>
  | UiIntent<'handoff.create', { localSessionId: string }>
  | UiIntent<'handoff.submit', { handoffId: string }>;

export interface ProviderView {
  providerId: string;
  displayName: string;
  protocol: 'openai-responses' | 'openai-chat-completions' | 'anthropic-messages' | string;
  authMode: 'bearer' | 'x-api-key' | 'api-key' | string;
  model: string;
  hasCredential: boolean;
  configured: boolean;
  contextTokens: number;
  maxOutputTokens: number;
}

export interface OriginView {
  snapshotId: string;
  sourceType: 'remote' | 'chat';
  sourceTitle: string;
  sourceRuntime?: string;
  createdAt: string;
  itemCount: number;
  sourceItemCount?: number;
  truncated?: boolean;
}

export interface SessionView {
  localSessionId: string;
  title: string;
  providerId: string;
  providerName: string;
  model: string;
  state: SessionState;
  updatedAt: string;
  preview?: string;
  origin?: OriginView;
}

export type TimelineItem =
  | { localSeq: number; kind: 'message'; role: 'user' | 'assistant' | 'system'; text: string; streaming?: boolean; createdAt: string }
  | { localSeq: number; kind: 'usage'; inputTokens?: number; outputTokens?: number; context?: { truncated?: boolean; estimatedInputTokens?: number; inheritedItemCount?: number; localItemCount?: number }; createdAt: string }
  | { localSeq: number; kind: 'error'; code: string; summary: string; createdAt: string };

export interface SessionDetailView extends SessionView {
  messages: TimelineItem[];
  composerDraft: string;
  canSend: boolean;
  canInterrupt: boolean;
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
  handoffs: HandoffView[];
  ui: { pendingRequestIds: string[]; banner?: { level: 'info' | 'warning' | 'error'; text: string } };
}

declare global {
  interface Window { MoyuHost?: { postMessage(message: string): void }; }
  interface WindowEventMap { 'moyu:view': CustomEvent<{ version: 1; type: string; revision?: number; view?: AppViewModel; requestId?: string; ok?: boolean; error?: { summary?: string } }> }
}
