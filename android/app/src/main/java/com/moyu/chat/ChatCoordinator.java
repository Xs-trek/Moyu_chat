package com.moyu.chat;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Native-only domain coordinator. UI knows intents/view models; provider keys never cross the bridge. */
final class ChatCoordinator implements AutoCloseable {
    private static final int MAX_PROVIDER_CONTINUATION_CHARS = 1_048_576;
    private static final int MAX_CLIENT_TOOL_ROUNDS = 4;
    private static final int MAX_UI_INHERITED_ITEMS = 500;
    private static final int MAX_UI_INHERITED_CHARS = 512_000;
    private static final long STREAM_PROJECTION_INTERVAL_MS = 50L;
    interface Host { void dispatch(JSONObject envelope); void openProviderEditor(ChatDatabase.Provider existing); void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing); default void openImagePicker(String sessionId) { } default void openConversationImportPicker(String source) { } default void openExternalUrl(String url) { } default boolean setTurnRuntimeActive(String sessionId, boolean active) { return true; } void showMessage(String text); }

    /** Immutable user-selected/deployment turn configuration frozen on the serialized intent worker. */
    private static final class TurnConfig {
        final ChatDatabase.Provider provider;
        final String apiKey, deploymentFingerprint;
        final String providerId, modelProfileId, model, modelName, reasoningEffort;
        final String inputModalities, outputModalities, toolCapabilities, thinkingMode, continuationMode, contextMode, webMode;
        final String harnessPreset, clientToolCalling, subagentMode;
        final int thinkingBudgetTokens, contextTokens, inputTokens, maxOutputTokens;
        final boolean reasoningAvailable;

        TurnConfig(ChatDatabase.Provider provider, String apiKey, String deploymentFingerprint,
                   ChatDatabase.Session session, ChatDatabase.ModelProfile profile) {
            this.provider = provider;
            this.apiKey = apiKey;
            this.deploymentFingerprint = deploymentFingerprint;
            this.providerId = session.providerId;
            this.modelProfileId = session.modelProfileId;
            this.model = session.model;
            this.modelName = profile == null ? session.model : profile.displayName;
            this.reasoningEffort = ReasoningPolicy.normalizeEffort(session.reasoningEffort);
            this.inputModalities = ChatDatabase.normalizeModalities(session.inputModalities);
            this.outputModalities = ChatDatabase.normalizeModalities(session.outputModalities);
            this.toolCapabilities = ChatTurnOptions.normalizeCapabilities(session.toolCapabilities);
            this.thinkingMode = ReasoningPolicy.normalizeThinkingMode(session.thinkingMode);
            this.thinkingBudgetTokens = Math.max(0, session.thinkingBudgetTokens);
            this.continuationMode = ProviderContinuationPolicy.normalize(session.continuationMode);
            this.contextMode = ContextMode.normalize(session.contextMode);
            this.webMode = ChatTurnOptions.normalizeWebMode(session.webMode);
            this.harnessPreset = HarnessProfile.normalize(session.harnessPreset);
            this.clientToolCalling = ClientToolRuntime.normalizeCalling(session.clientToolCalling);
            this.subagentMode = HarnessPolicyResolver.normalizeSubagentMode(session.subagentMode);
            this.contextTokens = session.contextTokens;
            this.inputTokens = session.inputTokens;
            this.maxOutputTokens = session.maxOutputTokens;
            this.reasoningAvailable = profile != null && !ReasoningPolicy.normalizeLevels(profile.reasoningLevels).isEmpty();
        }
    }

    /** One cancellation owner spans accepted-send preparation, provider streaming and Smart post-processing. */
    private static final class TurnControl implements ClientToolRuntime.TurnCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicReference<Thread> taskThread = new AtomicReference<>();
        private final AtomicReference<ChatTransport.Turn> transportTurn = new AtomicReference<>();
        private final java.util.Set<okhttp3.Call> webCalls = ConcurrentHashMap.newKeySet();

        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public void bind(okhttp3.Call call) {
            if (call == null) return;
            webCalls.add(call);
            if (cancelled.get()) call.cancel();
        }
        @Override public void clear(okhttp3.Call call) { if (call != null) webCalls.remove(call); }
        void bindCurrentThread() {
            Thread current = Thread.currentThread();
            taskThread.set(current);
            if (cancelled.get()) current.interrupt();
        }
        void clearCurrentThread() {
            Thread current = Thread.currentThread();
            taskThread.compareAndSet(current, null);
            if (cancelled.get()) Thread.interrupted(); // do not leak cancellation into the pooled worker's next task.
        }
        void bindTransport(ChatTransport.Turn turn) {
            transportTurn.set(turn);
            if (turn != null && cancelled.get()) try { turn.cancel(); } catch (Exception ignored) { }
        }
        void clearTransport(ChatTransport.Turn turn) { if (turn != null) transportTurn.compareAndSet(turn, null); }
        void cancel() {
            cancelled.set(true);
            ChatTransport.Turn turn = transportTurn.get();
            if (turn != null) try { turn.cancel(); } catch (Exception ignored) { }
            for (okhttp3.Call call : webCalls) if (call != null) try { call.cancel(); } catch (Exception ignored) { }
            Thread thread = taskThread.get();
            if (thread != null) thread.interrupt();
        }
    }

    private final ChatDatabase db;
    private final ChatSecretStore secrets;
    private final ChatMediaStore mediaStore;
    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // High-frequency provider deltas are coalesced before they reach the intent/DB authority queue.
    private final ScheduledExecutorService streamProjectionScheduler = Executors.newSingleThreadScheduledExecutor();
    // Blocking Router/Checkpoint/Web preparation must never occupy the UI/intent authority queue.
    private final ExecutorService preparationWorker = Executors.newFixedThreadPool(2);
    // Client tools may run independently; the hard subagent fan-out is four and no child may recurse.
    private final ExecutorService clientToolWorker = Executors.newFixedThreadPool(ClientToolRuntime.MAX_PARALLEL_SUBAGENTS);
    private final ChatTransportRegistry transports = new ChatTransportRegistry();
    private final ContextBuilder contexts;
    private final SnapshotService snapshots;
    private final TaskRouter taskRouter = new TaskRouter();
    private final ContextCheckpointService checkpointService = new ContextCheckpointService();
    private final SmartVerifierService smartVerifier = new SmartVerifierService();
    private final FocusedSubagentService focusedSubagent = new FocusedSubagentService();
    private final ConversationImportService conversationImporter = new ConversationImportService();
    private final MoyuWebService webService = new MoyuWebService();
    private final RemoteDataBridge remoteBridge;
    private final Map<String, ChatTransport.Turn> activeTurns = new ConcurrentHashMap<>();
    private final Map<String, TurnControl> turnControls = new ConcurrentHashMap<>();
    private final java.util.Set<String> startingSessions = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> preparingSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> webPreparingStages = new ConcurrentHashMap<>();
    private final Map<String, String> postProcessingStages = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStreamViewNanos = new ConcurrentHashMap<>();
    private final AtomicBoolean importInProgress = new AtomicBoolean(false);

    private volatile boolean closed;
    private volatile boolean uiReady;
    private long revision;
    private volatile String route;
    private volatile String activeSessionId;
    private String theme;
    private boolean showReasoning;
    private boolean crossChatMemoryEnabled;
    private String defaultContextMode;
    private String defaultWebMode;
    private String standardSubagentMode;
    private JSONArray remoteSources = new JSONArray();

    ChatCoordinator(MoyuChatApplication app, Host host) { this(app, host, app.secrets(), new AndroidRemoteDataBridge(app)); }

    /** Production wires the ContentResolver bridge; JVM tests inject fakes for the optional Remote side. */
    ChatCoordinator(MoyuChatApplication app, Host host, ChatSecretStore secrets, RemoteDataBridge bridge) {
        this.db = app.database(); this.secrets = secrets; this.mediaStore = app.mediaStore(); this.host = host;
        this.contexts = new ContextBuilder(db, mediaStore); this.snapshots = new SnapshotService(db);
        this.remoteBridge = bridge;
        // Recovery runs on the same single worker as every intent, so it is ordered before any send.
        worker.execute(() -> { db.recoverInterruptedSessions(); emitView(); });
        this.route = validRoute(db.setting("route", "home"));
        this.activeSessionId = empty(db.setting("activeSessionId", ""));
        this.theme = validTheme(db.setting("theme", "system"));
        this.showReasoning = "true".equals(db.setting("showReasoning", "false"));
        this.crossChatMemoryEnabled = "true".equals(db.setting("crossChatMemoryEnabled", "false"));
        String legacySmart = db.setting("smartContextEnabled", "true");
        this.defaultContextMode = ContextMode.normalize(db.setting("defaultContextMode", "false".equals(legacySmart) ? ContextMode.MINIMAL : ContextMode.STANDARD));
        this.defaultWebMode = ChatTurnOptions.normalizeWebMode(db.setting("defaultWebMode", ChatTurnOptions.WEB_AUTO));
        this.standardSubagentMode = HarnessPolicyResolver.normalizeSubagentMode(db.setting("standardSubagentMode", HarnessPolicyResolver.SUBAGENT_OFF));
    }

    void postIntent(String raw) { if (raw == null || raw.length() > 1024 * 1024) return; worker.execute(() -> handle(raw)); }
    void refreshView() { worker.execute(this::emitView); }

    interface SaveCallback { void complete(String error); }

    void saveProvider(ChatDatabase.Provider provider, String apiKey) { saveProvider(provider, apiKey, null); }

    void saveProvider(ChatDatabase.Provider provider, String apiKey, SaveCallback completion) {
        worker.execute(() -> {
            try {
                if (provider == null || provider.id == null || provider.id.trim().isEmpty()) throw new IllegalArgumentException("provider id required");
                provider.id = bounded(provider.id, 128, "provider id");
                provider.displayName = bounded(provider.displayName, 128, "display name");
                // Provider is the shared account/connection boundary. Protocol/auth/reasoning wire are
                // model-deployment capabilities and are validated in saveModelProfile instead.
                provider.baseUrl = ProviderEndpoint.normalize(provider.baseUrl);
                provider.model = provider.model == null ? "" : provider.model.trim();
                db.saveProvider(provider);
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    secrets.put(provider.id, apiKey.trim());
                    // A replaced credential may point at a different account/tenant even when the URL is unchanged.
                    // Invalidate all previously captured opaque continuation without persisting any secret-derived value.
                    ProviderContinuationIdentity.bumpCredentialEpoch(db, provider.id);
                }
                emitView();
                if (completion != null) completion.complete(null);
            } catch (Exception error) {
                String message = safe(error);
                if (completion != null) completion.complete(message); else host.showMessage(message);
            }
        });
    }

    void saveModelProfile(ChatDatabase.ModelProfile profile) { saveModelProfile(profile, null); }

    void saveModelProfile(ChatDatabase.ModelProfile profile, SaveCallback completion) {
        worker.execute(() -> {
            try {
                ChatDatabase.Provider provider = profile == null ? null : db.getProvider(profile.providerId);
                if (profile == null || provider == null) throw new IllegalArgumentException("provider not found");
                profile.id = bounded(profile.id, 160, "model profile id");
                profile.providerId = bounded(profile.providerId, 128, "provider id");
                profile.modelId = bounded(profile.modelId, 256, "model id");
                profile.displayName = bounded(profile.displayName == null || profile.displayName.trim().isEmpty() ? profile.modelId : profile.displayName, 128, "model display name");
                profile.configMode = ModelCatalog.normalizeConfigMode(profile.configMode);
                if (ModelCatalog.CONFIG_AUTO.equals(profile.configMode)) {
                    // Auto is catalog-owned as one coherent deployment profile. Per-field overrides
                    // belong to Advanced/manual mode; mixing stale manual values into Auto would make
                    // catalog updates appear applied while silently retaining old limits/capabilities.
                    ModelCatalog.applyAuto(provider, profile, 0, 0, 0);
                }
                profile.harnessPreset = HarnessProfile.normalize(profile.harnessPreset);
                profile.clientToolCalling = ClientToolRuntime.normalizeCalling(profile.clientToolCalling);
                profile.protocol = ChatDatabase.normalizeProtocol(profile.protocol);
                if (!transports.supports(profile.protocol)) throw new IllegalArgumentException("unsupported protocol");
                if (!ClientToolRuntime.NONE.equals(profile.clientToolCalling)
                        && !ClientToolRuntime.compatible(profile.protocol, profile.clientToolCalling)) {
                    throw new IllegalArgumentException("client tool calling wire is incompatible with selected protocol");
                }
                profile.authMode = ProviderAuth.normalize(profile.authMode, profile.protocol);
                profile.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, profile.protocol);
                ProviderEndpoint.normalize(provider.baseUrl, profile.protocol);
                profile.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
                ReasoningPolicy.validateLevelsForProvider(ChatDatabase.deploymentProvider(provider, profile), profile.reasoningLevels);
                profile.defaultReasoning = ReasoningPolicy.DEFAULT;
                profile.inputModalities = ChatDatabase.normalizeModalities(profile.inputModalities);
                profile.outputModalities = ChatDatabase.normalizeModalities(profile.outputModalities);
                profile.toolCapabilities = ChatTurnOptions.normalizeCapabilities(profile.toolCapabilities);
                profile.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode);
                profile.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
                profile.continuationMode = ProviderContinuationPolicy.normalize(profile.continuationMode);
                db.saveModelProfile(profile); emitView();
                if (completion != null) completion.complete(null);
            } catch (Exception error) {
                String message = safe(error);
                if (completion != null) completion.complete(message); else host.showMessage(message);
            }
        });
    }

    private void handle(String raw) {
        String requestId = "";
        try {
            JSONObject intent = new JSONObject(raw);
            requestId = intent.optString("requestId", "");
            if (intent.optInt("version", -1) != 1 || requestId.isEmpty()) throw new IllegalArgumentException("bad intent envelope");
            String type = intent.optString("type", "");
            JSONObject p = intent.optJSONObject("payload"); if (p == null) p = new JSONObject();
            switch (type) {
                case "app.ready": uiReady = true; break;
                case "view.reload": break;
                case "appearance.set": theme = validTheme(required(p, "theme")); db.putSetting("theme", theme); break;
                case "chat.reasoning.visibility.set": showReasoning = p.optBoolean("show", false); db.putSetting("showReasoning", showReasoning ? "true" : "false"); break;
                case "memory.global.set": crossChatMemoryEnabled = p.optBoolean("enabled", false); db.putSetting("crossChatMemoryEnabled", crossChatMemoryEnabled ? "true" : "false"); break;
                case "context.mode.default.set": defaultContextMode = ContextMode.normalize(required(p, "mode")); db.putSetting("defaultContextMode", defaultContextMode); break;
                case "web.mode.default.set": defaultWebMode = ChatTurnOptions.normalizeWebMode(required(p, "mode")); db.putSetting("defaultWebMode", defaultWebMode); break;
                case "subagent.standard.default.set": standardSubagentMode = HarnessPolicyResolver.normalizeSubagentMode(required(p, "mode")); db.putSetting("standardSubagentMode", standardSubagentMode); break;
                case "external.open": host.openExternalUrl(required(p, "url")); break;
                // v0.0.2 migration compatibility for an older UI bundle: off -> minimal, on -> standard.
                case "context.intelligence.set": defaultContextMode = p.optBoolean("enabled", true) ? ContextMode.STANDARD : ContextMode.MINIMAL; db.putSetting("defaultContextMode", defaultContextMode); break;
                case "nav.open": route = validRoute(required(p, "route")); db.putSetting("route", route); break;
                case "chat.session.create": createSession(p); break;
                case "chat.session.open": openSession(required(p, "localSessionId")); break;
                case "chat.session.rename": renameSession(required(p, "localSessionId"), required(p, "title")); break;
                case "chat.session.model.set": setSessionModel(required(p, "localSessionId"), required(p, "modelProfileId")); break;
                case "chat.session.reasoning.set": setSessionReasoning(required(p, "localSessionId"), required(p, "reasoningEffort")); break;
                case "chat.session.send": {
                    final String sessionId = required(p, "localSessionId");
                    final String message = p.optString("text", "");
                    if (sessionBusy(sessionId)) throw new IllegalStateException("session busy");
                    if (message.trim().isEmpty() && db.draftMedia(sessionId).length() == 0) break;
                    // Send is self-contained with respect to the visible turn setting. A preceding
                    // reasoning.set bridge message is still persisted normally, but Send must not rely
                    // on cross-bridge scheduling order to know what the user selected for this turn.
                    if (p.has("reasoningEffort")) setSessionReasoning(sessionId, p.optString("reasoningEffort", ReasoningPolicy.DEFAULT));
                    final String asyncRequestId = requestId;
                    // All user-selected wire/session capabilities plus provider config/secret/credential epoch
                    // are frozen while this serialized intent worker owns the turn. UI navigation or profile
                    // edits cannot relabel an already accepted request.
                    final TurnConfig turnConfig = freezeTurnConfig(sessionId);
                    if (!host.setTurnRuntimeActive(sessionId, true)) {
                        db.setDraft(sessionId, message); // the turn was not accepted; restore the exact submitted text.
                        throw new IllegalStateException("unable to acquire foreground generation runtime");
                    }
                    final TurnControl turnControl = new TurnControl();
                    if (turnControls.putIfAbsent(sessionId, turnControl) != null) {
                        host.setTurnRuntimeActive(sessionId, false);
                        throw new IllegalStateException("session busy");
                    }
                    // The composer is cleared only after the runtime lease makes this an accepted turn.
                    db.setDraft(sessionId, "");
                    startingSessions.add(sessionId);
                    emitView(); // accepted turn is immediately visible and immediately interruptible.
                    try {
                        preparationWorker.execute(() -> {
                            turnControl.bindCurrentThread();
                            try {
                                send(sessionId, message, turnConfig, turnControl);
                                result(asyncRequestId, true, null);
                            } catch (Exception error) {
                                // Last-resort durable terminal guard: if beginTurn() already committed a live row,
                                // no escaping preparation exception may leave the DB stuck in running/streaming.
                                try { db.failUnhandledTurn(sessionId, turnControl.isCancelled() ? "cancelled" : "turn_internal_error", safe(error)); } catch (Exception ignored) { }
                                turnControls.remove(sessionId, turnControl);
                                startingSessions.remove(sessionId);
                                preparingSessions.remove(sessionId);
                                webPreparingStages.remove(sessionId);
                                postProcessingStages.remove(sessionId);
                                host.setTurnRuntimeActive(sessionId, false);
                                result(asyncRequestId, false, safe(error));
                            } finally {
                                turnControl.clearCurrentThread();
                                emitView();
                            }
                        });
                    } catch (Exception schedulingError) {
                        turnControls.remove(sessionId, turnControl);
                        startingSessions.remove(sessionId);
                        db.setDraft(sessionId, message);
                        host.setTurnRuntimeActive(sessionId, false);
                        throw schedulingError;
                    }
                    return;
                }
                case "chat.session.saveDraft": saveDraft(required(p, "localSessionId"), p.optString("text", "")); break;
                case "chat.attachment.pick": requestImagePicker(required(p, "localSessionId")); break;
                case "chat.attachment.remove": db.removeDraftMedia(required(p, "localSessionId"), required(p, "mediaId")); break;
                case "chat.session.interrupt": interrupt(required(p, "localSessionId")); break;
                case "chat.session.delete": deleteSession(required(p, "localSessionId")); break;
                case "chat.message.edit": editMessageInPlace(required(p, "localSessionId"), requiredSeq(p)); break;
                case "chat.message.rollback": rollbackMessageInPlace(required(p, "localSessionId"), requiredSeq(p)); break;
                // Absent providerId means "configure a new provider"; never bind null into the DB layer.
                case "provider.configure.open": {
                    String providerId = p.optString("providerId", "");
                    host.openProviderEditor(providerId.trim().isEmpty() ? null : db.getProvider(providerId));
                    break;
                }
                case "model.configure.open": {
                    String providerId = required(p, "providerId"); ChatDatabase.Provider provider = db.getProvider(providerId);
                    if (provider == null) throw new IllegalArgumentException("provider not found");
                    String profileId = p.optString("modelProfileId", "");
                    host.openModelEditor(provider, profileId.trim().isEmpty() ? null : db.getModelProfile(profileId));
                    break;
                }
                case "import.file.pick": host.openConversationImportPicker(importSource(required(p, "source"))); break;
                case "import.session.open": createImportedBranch(required(p, "snapshotId"), ContextMode.normalize(p.optString("contextMode", defaultContextMode)), ChatTurnOptions.normalizeWebMode(p.optString("webMode", defaultWebMode))); break;
                case "import.session.delete": db.deleteImportedSnapshot(required(p, "snapshotId")); break;
                case "verification.feedback": recordVerificationFeedback(required(p, "localSessionId"), requiredSeq(p), required(p, "label")); break;
                case "branch.sources.refresh": refreshSources(); break;
                case "branch.create.remote": createRemoteBranch(required(p, "sourceId"), p.has("throughSeq") ? p.optInt("throughSeq") : null, ContextMode.normalize(p.optString("contextMode", defaultContextMode)), ChatTurnOptions.normalizeWebMode(p.optString("webMode", defaultWebMode))); break;
                case "branch.create.local": createLocalBranch(required(p, "localSessionId"), p.has("throughSeq") ? p.optInt("throughSeq") : null); break;
                case "handoff.create": createHandoff(required(p, "localSessionId")); break;
                case "handoff.submit": submitHandoff(required(p, "handoffId")); break;
                default: throw new IllegalArgumentException("unsupported intent: " + type);
            }
            result(requestId, true, null); emitView();
        } catch (Exception error) { result(requestId, false, safe(error)); emitView(); }
    }

    private void requestImagePicker(String sessionId) {
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
        if (!ChatDatabase.supportsModality(session.inputModalities, "image")) throw new IllegalStateException("this model profile does not declare image input support");
        if (db.draftMedia(sessionId).length() >= ChatMediaStore.MAX_DRAFT_IMAGES) throw new IllegalStateException("image attachment limit reached");
        host.openImagePicker(sessionId);
    }

    void attachImageFromUri(String sessionId, Uri uri, ContentResolver resolver) {
        worker.execute(() -> {
            try {
                ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
                if (!ChatDatabase.supportsModality(session.inputModalities, "image")) throw new IllegalStateException("this model profile does not support image input");
                JSONObject descriptor = mediaStore.importImage(resolver, uri); db.addDraftMedia(sessionId, descriptor); emitView();
            } catch (Exception error) { host.showMessage(safe(error)); emitView(); }
        });
    }

    void importConversationFromUri(String source, Uri uri, ContentResolver resolver) {
        final String importSource = importSource(source);
        if (uri == null || resolver == null) { host.showMessage("导入文件不可用"); return; }
        if (!importInProgress.compareAndSet(false, true)) { host.showMessage("已有会话导入正在进行"); return; }
        emitView();
        preparationWorker.execute(() -> {
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("无法读取导入文件");
                String displayName = importDisplayName(resolver, uri);
                ConversationImportService.Report report = conversationImporter.importFile(importSource, displayName, input,
                        conversation -> snapshots.importExternal(importSource, conversation));
                host.showMessage(report.summary(importSource));
            } catch (ConversationImportService.PartialImportException partial) {
                host.showMessage("会话导入部分完成：" + partial.userSummary);
            } catch (Exception error) {
                host.showMessage("会话导入失败：" + safe(error));
            } finally {
                importInProgress.set(false);
                emitView();
            }
        });
    }

    private void createSession(JSONObject p) {
        String providerId = required(p, "providerId");
        ChatDatabase.Provider provider = db.getProvider(providerId);
        if (provider == null) throw new IllegalStateException("provider not configured");
        ChatDatabase.ModelProfile profile = null;
        String profileId = p.optString("modelProfileId", "").trim();
        if (!profileId.isEmpty()) profile = db.getModelProfile(profileId);
        if (profile == null) {
            String requestedModel = p.optString("model", "").trim();
            profile = requestedModel.isEmpty() ? db.firstModelProfile(providerId) : db.findModelProfile(providerId, requestedModel);
        }
        if (profile == null || !providerId.equals(profile.providerId)) throw new IllegalStateException("model profile not configured");
        String requestedTitle = p.optString("title", "").trim();
        String title = requestedTitle.isEmpty() ? null : bounded(requestedTitle, 200, "title");
        String effort = p.has("reasoningEffort") ? ReasoningPolicy.normalizeEffort(p.optString("reasoningEffort", ReasoningPolicy.DEFAULT)) : ReasoningPolicy.DEFAULT;
        ReasoningPolicy.validateEffortForProvider(ChatDatabase.deploymentProvider(provider, profile), profile.reasoningLevels, effort);
        String contextMode = ContextMode.normalize(p.optString("contextMode", defaultContextMode));
        if (p.has("contextMode")) { defaultContextMode = contextMode; db.putSetting("defaultContextMode", defaultContextMode); }
        String webMode = ChatTurnOptions.normalizeWebMode(p.optString("webMode", defaultWebMode));
        ChatDatabase.Session s = db.createSession(profile, title, null, null, effort, contextMode, webMode);
        activate(s.id);
    }

    private void openSession(String id) { if (db.getSession(id) == null) throw new IllegalArgumentException("session not found"); activate(id); }
    private void renameSession(String id, String title) { db.renameSession(id, bounded(title, 200, "session title")); }
    private void activate(String id) { activeSessionId = id; db.putSetting("activeSessionId", id); route = "conversation"; db.putSetting("route", route); }

    /** Model selection changes the configuration for future turns only; prior assistant rows keep per-turn metadata. */
    private void setSessionModel(String sessionId, String modelProfileId) {
        if (activeTurns.containsKey(sessionId) || startingSessions.contains(sessionId) || preparingSessions.contains(sessionId) || webPreparingStages.containsKey(sessionId) || postProcessingStages.containsKey(sessionId)) {
            throw new IllegalStateException("wait for the current turn before switching model");
        }
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
        ChatDatabase.ModelProfile profile = db.getModelProfile(modelProfileId); if (profile == null) throw new IllegalArgumentException("model profile not found");
        ChatDatabase.Provider provider = db.getProvider(profile.providerId);
        if (provider == null || !secrets.has(profile.providerId)) throw new IllegalStateException("target provider credential missing");
        if (db.draftMedia(sessionId).length() > 0 && !ChatDatabase.supportsModality(profile.inputModalities, "image")) {
            throw new IllegalStateException("remove image attachments before switching to a text-only model");
        }
        session.providerId = profile.providerId;
        session.modelProfileId = profile.id;
        session.model = profile.modelId;
        session.contextTokens = profile.contextTokens;
        session.inputTokens = profile.inputTokens;
        session.maxOutputTokens = profile.maxOutputTokens;
        session.inputModalities = ChatDatabase.normalizeModalities(profile.inputModalities);
        session.outputModalities = ChatDatabase.normalizeModalities(profile.outputModalities);
        session.toolCapabilities = ChatTurnOptions.normalizeCapabilities(profile.toolCapabilities);
        session.protocol = ChatDatabase.normalizeProtocol(profile.protocol);
        session.authMode = ProviderAuth.normalize(profile.authMode, session.protocol);
        session.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, session.protocol);
        session.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode);
        session.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
        session.continuationMode = ProviderContinuationPolicy.normalize(profile.continuationMode);
        session.harnessPreset = HarnessProfile.normalize(profile.harnessPreset);
        session.clientToolCalling = ClientToolRuntime.normalizeCalling(profile.clientToolCalling);
        session.subagentMode = ContextMode.MINIMAL.equals(ContextMode.normalize(session.contextMode)) ? HarnessPolicyResolver.SUBAGENT_OFF
                : ContextMode.SMART.equals(ContextMode.normalize(session.contextMode)) ? HarnessPolicyResolver.SUBAGENT_AUTO
                : standardSubagentMode;
        ReasoningPolicy.validateLevelsForProvider(ChatDatabase.deploymentProvider(provider, session), profile.reasoningLevels);
        String previousEffort = ReasoningPolicy.normalizeEffort(session.reasoningEffort);
        session.reasoningEffort = ReasoningPolicy.allows(profile.reasoningLevels, previousEffort) ? previousEffort : ReasoningPolicy.DEFAULT;
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** Reasoning effort is a durable future-turn setting and is validated against the active model profile. */
    private void setSessionReasoning(String sessionId, String effort) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("wait for the current turn before changing reasoning effort");
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
        ChatDatabase.ModelProfile profile = session.modelProfileId == null ? null : db.getModelProfile(session.modelProfileId);
        if (profile == null) throw new IllegalStateException("model profile not found");
        ChatDatabase.Provider provider = db.getProvider(session.providerId);
        if (provider == null) throw new IllegalStateException("provider not found");
        String normalized = ReasoningPolicy.normalizeEffort(effort);
        ReasoningPolicy.validateEffortForProvider(ChatDatabase.deploymentProvider(provider, session), profile.reasoningLevels, normalized);
        session.reasoningEffort = normalized;
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** System back follows the same route state as UI navigation; only home exits the Activity. */
    void handleSystemBack(Runnable exitApp) {
        worker.execute(() -> {
            if (!"home".equals(route)) {
                route = "home";
                db.putSetting("route", route);
                emitView();
            } else if (exitApp != null) exitApp.run();
        });
    }

    private TurnConfig freezeTurnConfig(String sessionId) {
        ChatDatabase.Session session = db.getSession(sessionId);
        if (session == null) throw new IllegalArgumentException("session not found");
        ChatDatabase.Provider sharedProvider = db.getProvider(session.providerId);
        String key = secrets.get(session.providerId);
        if (sharedProvider == null || key == null) throw new IllegalStateException("provider credential missing");
        ChatDatabase.ModelProfile profile = session.modelProfileId == null ? null : db.getModelProfile(session.modelProfileId);
        if (profile == null) throw new IllegalStateException("model profile not found");
        ChatDatabase.Provider provider = ChatDatabase.deploymentProvider(sharedProvider, session);
        if (transports.forProtocol(provider.protocol) == null) throw new IllegalStateException("provider protocol unsupported");
        ProviderEndpoint.normalize(provider.baseUrl, provider.protocol);
        ReasoningPolicy.validateEffortForProvider(provider, profile.reasoningLevels, session.reasoningEffort);
        return new TurnConfig(provider, key, ProviderContinuationIdentity.fingerprint(db, provider, session), session, profile);
    }

    private boolean sessionBusy(String id) {
        return turnControls.containsKey(id) || activeTurns.containsKey(id) || startingSessions.contains(id)
                || preparingSessions.contains(id) || webPreparingStages.containsKey(id) || postProcessingStages.containsKey(id);
    }

    private void saveDraft(String id, String text) {
        if (db.getSession(id) == null) throw new IllegalArgumentException("session not found");
        // Ignore stale IME/debounce writes from the just-submitted composer while a turn owns this session.
        if (sessionBusy(id)) return;
        db.setDraft(id, text == null ? "" : text);
    }

    private void deleteSession(String id) {
        if (startingSessions.contains(id) || preparingSessions.contains(id) || webPreparingStages.containsKey(id) || postProcessingStages.containsKey(id)) throw new IllegalStateException("wait for preparation before deleting this session");
        if (activeTurns.containsKey(id)) throw new IllegalStateException("interrupt the current turn before deleting this session");
        if (db.getSession(id) == null) throw new IllegalArgumentException("session not found");
        db.deleteSession(id);
        mediaStore.pruneUnreferenced(db.referencedMediaIds());
        if (id.equals(activeSessionId)) { activeSessionId = null; db.putSetting("activeSessionId", ""); route = "home"; db.putSetting("route", route); }
    }

    private void send(String id, String text, TurnConfig turnConfig, TurnControl turnControl) throws Exception {
        // trim is an emptiness check only; the stored/prompted/sent text stays byte-for-byte original.
        String input = text == null ? "" : text;
        ChatDatabase.Session session = db.getSession(id); if (session == null) throw new IllegalArgumentException("session not found");
        JSONArray pendingMedia = db.draftMedia(id);
        if (input.trim().isEmpty() && pendingMedia.length() == 0) return;
        if (turnConfig == null || turnConfig.provider == null || turnConfig.apiKey == null) throw new IllegalStateException("turn configuration missing");
        if (pendingMedia.length() > 0 && !ChatDatabase.supportsModality(turnConfig.inputModalities, "image")) throw new IllegalStateException("this session model does not support image input");
        if (activeTurns.containsKey(id)) throw new IllegalStateException("session busy");
        ChatDatabase.Provider provider = turnConfig.provider;
        String key = turnConfig.apiKey;
        ChatTransport transport = transports.forProtocol(provider.protocol); if (transport == null) throw new IllegalStateException("provider protocol unsupported");
        // Resolve only stable session policy before durable mutation. Once the user taps Send the
        // message belongs in the transcript and the composer draft must be cleared even if later
        // context preparation, Web retrieval, or provider startup fails.
        ChatDatabase.Snapshot originSnapshot = session.originSnapshotId == null ? null : db.getSnapshot(session.originSnapshotId);
        ContextMode.Policy contextPolicy = ContextMode.resolve(session, originSnapshot);
        HarnessPolicyResolver.Policy adaptivePolicy = HarnessPolicyResolver.resolve(session);
        TaskProfile profile = TaskProfile.fromStored(session.taskProfile);
        boolean needsRouter = contextPolicy.routerEnabled && profile == null;
        // Deterministic Standard/Smart compaction cannot be disabled by the model-authored TaskProfile.
        boolean checkpointAllowed = contextPolicy.checkpointEnabled;
        boolean mayPrepare = needsRouter || checkpointAllowed;

        // Request behavior comes from the immutable TurnConfig, not mutable UI/session/profile state.
        provider.thinkingMode = turnConfig.thinkingMode;
        provider.thinkingBudgetTokens = turnConfig.thinkingBudgetTokens;
        final boolean allowProviderContinuation = contextPolicy.providerContinuationEnabled
                && ProviderContinuationPolicy.enabled(turnConfig.continuationMode);
        JSONObject user = new JSONObject().put("kind", "message").put("role", "user").put("text", input).put("createdAt", Instant.now().toString());
        if (pendingMedia.length() > 0) user.put("attachments", new JSONArray(pendingMedia.toString()));
        String streamKey = "assistant:live:" + id + ":" + UUID.randomUUID();
        JSONObject live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "")
                .put("streaming", true).put("streamStage", mayPrepare ? "preparing" : "connecting").put("createdAt", Instant.now().toString())
                .put("providerId", turnConfig.providerId).put("providerName", provider.displayName)
                .put("modelProfileId", turnConfig.modelProfileId == null ? JSONObject.NULL : turnConfig.modelProfileId)
                .put("model", turnConfig.model)
                .put("modelName", turnConfig.modelName)
                .put("reasoningAvailable", turnConfig.reasoningAvailable)
                .put("reasoningEffort", turnConfig.reasoningEffort)
                .put("contextMode", turnConfig.contextMode)
                .put("effectiveContextMode", contextPolicy.effectiveLabel);
        db.beginTurn(id, "user:" + UUID.randomUUID(), user, streamKey, live);
        startingSessions.add(id);
        final long requestStartedNanos = System.nanoTime();
        emitView();
        if (turnControl != null && turnControl.isCancelled()) {
            finishTurn(id, streamKey, live, "", "", new JSONArray(), new LinkedHashMap<>(), "",
                    new String[]{"cancelled", "Generation stopped"});
            return;
        }

        CanonicalPrompt prompt;
        ContextBuilder.CompactionPlan compactionPlan;
        boolean needsCheckpoint;
        boolean preparing;
        try {
            prompt = contexts.buildCommitted(session, turnConfig.contextTokens, turnConfig.inputTokens, turnConfig.maxOutputTokens, "");
            compactionPlan = checkpointAllowed ? contexts.compactionPlan(session, turnConfig.contextTokens, turnConfig.inputTokens, turnConfig.maxOutputTokens) : null;
            needsCheckpoint = compactionPlan != null;
            preparing = needsRouter || needsCheckpoint;
        } catch (Exception contextError) {
            finishTurn(id, streamKey, live, "", "", new JSONArray(), new LinkedHashMap<>(), "",
                    new String[]{"context_prepare_failed", safe(contextError)});
            return;
        }
        if (!preparing && mayPrepare) {
            live.put("streamStage", "connecting");
            db.replaceTimelineBody(streamKey, live);
            emitStreamingView(id);
        }
        if (preparing) { preparingSessions.add(id); emitView(); }
        try {
            if (needsRouter) {
                TaskProfile routed = taskRouter.route(transport, provider, key, session.model, prompt);
                profile = routed != null ? routed : new TaskProfile("general", "answer", "standard", "direct", "checkpoint_recent", new JSONArray(), 0d);
                session.taskProfile = profile.toJson().toString();
                db.saveSession(session);
                prompt = contexts.buildCommitted(session, turnConfig.contextTokens, turnConfig.inputTokens, turnConfig.maxOutputTokens, "");
                compactionPlan = checkpointAllowed ? contexts.compactionPlan(session, turnConfig.contextTokens, turnConfig.inputTokens, turnConfig.maxOutputTokens) : null;
                needsCheckpoint = compactionPlan != null;
            }
            if (needsCheckpoint) {
                ContextCheckpointService.Update update = checkpointService.update(transport, provider, key, session.model, compactionPlan.baseline,
                        ContextCheckpoint.fromStored(session.contextCheckpoint), session.checkpointCoveredItems);
                if (update != null) {
                    session.contextCheckpoint = update.checkpoint.toJson().toString();
                    session.checkpointCoveredItems = update.coveredItems;
                    db.saveSession(session);
                    prompt = contexts.buildCommitted(session, turnConfig.contextTokens, turnConfig.inputTokens, turnConfig.maxOutputTokens, "");
                }
            }
        } catch (Exception preparationError) {
            finishTurn(id, streamKey, live, "", "", new JSONArray(), new LinkedHashMap<>(), "",
                    new String[]{"context_prepare_failed", safe(preparationError)});
            return;
        } finally {
            if (preparing) { preparingSessions.remove(id); emitStreamingView(id); }
        }
        if (turnControl != null && turnControl.isCancelled()) {
            finishTurn(id, streamKey, live, "", "", new JSONArray(), new LinkedHashMap<>(), "",
                    new String[]{"cancelled", "Generation stopped"});
            return;
        }

        // Web/tools are a separate capability plane. Hidden Router/Checkpoint calls above never receive them.
        String hostedCapabilities = ChatTurnOptions.effectiveCapabilities(provider.protocol, turnConfig.toolCapabilities);
        boolean clientCalling = ClientToolRuntime.compatible(provider.protocol, turnConfig.clientToolCalling);
        java.util.LinkedHashSet<String> clientToolNames = new java.util.LinkedHashSet<>();
        if (clientCalling && ChatTurnOptions.WEB_AUTO.equals(turnConfig.webMode)) {
            ChatTurnOptions hostedOnly = new ChatTurnOptions(hostedCapabilities, turnConfig.webMode);
            if (!hostedOnly.supports(ChatTurnOptions.WEB_SEARCH)) clientToolNames.add(ClientToolRuntime.WEB_SEARCH);
            if (!hostedOnly.supports(ChatTurnOptions.WEB_FETCH)) clientToolNames.add(ClientToolRuntime.WEB_FETCH);
        }
        if (clientCalling && HarnessPolicyResolver.SUBAGENT_AUTO.equals(adaptivePolicy.subagentMode)) {
            clientToolNames.add(ClientToolRuntime.DELEGATE_SUBTASK);
        }
        // Tool definitions are real model input. If even the schema cannot fit beside the mandatory
        // newest user-led turn, client tools are an optional enhancement and are disabled for this turn.
        int clientToolSchemaReserve = clientToolNames.isEmpty() ? 0
                : new ContextBudgetManager().estimate(ClientToolRuntime.canonicalDefinitions(String.join(",", clientToolNames)).toString()) + 128;
        if (clientToolSchemaReserve > 0) {
            try {
                prompt = contexts.reserveForAdditionalInput(prompt, turnConfig.contextTokens, turnConfig.inputTokens,
                        turnConfig.maxOutputTokens, clientToolSchemaReserve);
            } catch (IllegalArgumentException noToolRoom) {
                clientToolNames.clear();
                clientToolSchemaReserve = 0;
            }
        }
        final String clientToolCsv = String.join(",", clientToolNames);
        final int clientToolSchemaTokens = clientToolSchemaReserve;
        ChatTurnOptions webOptions = new ChatTurnOptions(hostedCapabilities, turnConfig.webMode,
                turnConfig.clientToolCalling, clientToolCsv, new JSONArray());
        MoyuWebService.Prepared preparedWeb = MoyuWebService.Prepared.none();
        // `web=on` keeps the existing deterministic forced fallback semantics. In auto mode a
        // deployment with client tools lets the model decide when to search/fetch instead.
        boolean typedClientWeb = clientToolNames.contains(ClientToolRuntime.WEB_SEARCH)
                || clientToolNames.contains(ClientToolRuntime.WEB_FETCH);
        if (webOptions.webEnabled() && (!typedClientWeb || ChatTurnOptions.WEB_ON.equals(turnConfig.webMode))) {
            try {
                preparedWeb = webService.prepare(input, webOptions, stage -> {
                    String webStage = stage == null || stage.trim().isEmpty() ? "web_search" : stage.trim();
                    webPreparingStages.put(id, webStage);
                    try { live.put("streamStage", webStage); db.replaceTimelineBody(streamKey, live); } catch (Exception ignored) { }
                    emitStreamingView(id);
                }, turnControl);
            } catch (Exception webError) {
                finishTurn(id, streamKey, live, "", "", new JSONArray(), new LinkedHashMap<>(), "",
                        turnControl != null && turnControl.isCancelled()
                                ? new String[]{"cancelled", "Generation stopped"}
                                : new String[]{"web_prepare_failed", safe(webError)});
                return;
            } finally {
                if (webPreparingStages.remove(id) != null) emitStreamingView(id);
            }
            if (turnControl != null && turnControl.isCancelled()) {
                finishTurn(id, streamKey, live, "", "", new JSONArray(), new LinkedHashMap<>(), "",
                        new String[]{"cancelled", "Generation stopped"});
                return;
            }
            if (preparedWeb.hasContext()) {
                // Rebuild only the disposable main prompt so Web material is budgeted but never persisted as user text.
                prompt = contexts.buildCommitted(session, turnConfig.contextTokens, turnConfig.inputTokens, turnConfig.maxOutputTokens,
                        preparedWeb.promptSuffix);
                if (clientToolSchemaTokens > 0) prompt = contexts.reserveForAdditionalInput(prompt,
                        turnConfig.contextTokens, turnConfig.inputTokens, turnConfig.maxOutputTokens, clientToolSchemaTokens);
            }
        }
        final MoyuWebService.Prepared webPrepared = preparedWeb;
        final CanonicalPrompt mainPrompt = prompt;
        final TaskProfile resolvedTaskProfile = profile;
        provider.maxOutputTokens = turnConfig.maxOutputTokens; // request-local provider copy; transport only needs the frozen wire output cap.
        LinkedHashMap<String, JSONObject> webSources = new LinkedHashMap<>();
        for (int i = 0; i < webPrepared.sources.length(); i++) {
            JSONObject source = webPrepared.sources.optJSONObject(i); if (source == null) continue;
            String url = source.optString("url", ""); if (!url.isEmpty()) webSources.put(url, new JSONObject(source.toString()));
        }
        String[] webBackend = new String[]{webPrepared.backend};
        live.put("streamStage", "connecting");
        if (!webSources.isEmpty()) live.put("sources", sourceArray(webSources));
        if (!webBackend[0].isEmpty()) live.put("webBackend", webBackend[0]);
        db.replaceTimelineBody(streamKey, live);
        emitStreamingView(id);
        final long[] firstOutputNanos = new long[]{0L};
        StringBuffer textOut = new StringBuffer();
        StringBuffer reasoningOut = new StringBuffer();
        AtomicReference<String> streamStage = new AtomicReference<>("connecting");
        AtomicBoolean streamProjectionScheduled = new AtomicBoolean(false);
        AtomicBoolean streamTerminal = new AtomicBoolean(false);
        AtomicLong streamProjectionRevision = new AtomicLong(0L);
        JSONArray outputMedia = new JSONArray();
        JSONArray providerContinuationItems = new JSONArray();
        // Exact provider output/content blocks from completed client-tool rounds. This is same-turn
        // protocol state only; it is not persisted as durable continuation unless the turn had no client tools.
        JSONArray providerRoundHistory = new JSONArray();
        java.util.Set<String> providerContinuationKeys = new java.util.HashSet<>();
        int[] providerContinuationChars = new int[]{0};
        boolean[] providerContinuationOverflow = new boolean[]{false};
        JSONArray clientToolHistory = new JSONArray();
        AtomicLong providerRoundGeneration = new AtomicLong(0L);
        AtomicLong clientToolBatchCount = new AtomicLong(0L);
        int[] childInputTokens = new int[]{0};
        int[] childOutputTokens = new int[]{0};
        int[] subagentCount = new int[]{0};
        boolean[] usedClientWeb = new boolean[]{false};
        StringBuilder clientEvidenceContext = new StringBuilder();
        AtomicReference<java.util.function.Consumer<ChatTurnOptions>> roundStarter = new AtomicReference<>();
        AtomicReference<ChatTurnOptions> activeRoundOptions = new AtomicReference<>(webOptions);
        AtomicReference<CanonicalPrompt> activeRoundPrompt = new AtomicReference<>(mainPrompt);
        int[] roundTextStart = new int[]{0};
        int[] roundReasoningStart = new int[]{0};
        int[] roundMediaStart = new int[]{0};
        final boolean allowImageOutput = ChatDatabase.supportsModality(turnConfig.outputModalities, "image");
        String usageKey = "usage:turn:" + UUID.randomUUID();
        JSONObject usageState = new JSONObject().put("kind", "usage").put("createdAt", Instant.now().toString()).put("context", prompt.diagnostics());
        boolean[] usagePersisted = new boolean[]{false};
        int[] roundInput = new int[]{0}, roundOutput = new int[]{0}, roundCached = new int[]{0}, roundCacheWrite = new int[]{0}, roundCacheBase = new int[]{0}, roundReasoning = new int[]{0};
        int[] mainInput = new int[]{0}, mainOutput = new int[]{0}, mainCached = new int[]{0}, mainCacheWrite = new int[]{0}, mainCacheBase = new int[]{0}, mainReasoning = new int[]{0};
        ChatTransport.Sink sink = new ChatTransport.Sink() {
            private void refreshUsageProjection() throws Exception {
                int currentInput = mainInput[0] + roundInput[0];
                int currentOutput = mainOutput[0] + roundOutput[0];
                int currentCached = mainCached[0] + roundCached[0];
                int currentCacheWrite = mainCacheWrite[0] + roundCacheWrite[0];
                int currentCacheBase = mainCacheBase[0] + roundCacheBase[0];
                int currentReasoning = mainReasoning[0] + roundReasoning[0];
                if (currentInput > 0) usageState.put("inputTokens", currentInput);
                if (currentOutput > 0) usageState.put("outputTokens", currentOutput);
                if (currentCached > 0) usageState.put("cachedInputTokens", currentCached);
                if (currentCacheWrite > 0) usageState.put("cacheWriteInputTokens", currentCacheWrite);
                if (currentCacheBase > 0) usageState.put("cacheHitBaseTokens", currentCacheBase);
                if (currentReasoning > 0) usageState.put("reasoningTokens", currentReasoning);
                if (childInputTokens[0] > 0) usageState.put("childInputTokens", childInputTokens[0]);
                if (childOutputTokens[0] > 0) usageState.put("childOutputTokens", childOutputTokens[0]);
                if (subagentCount[0] > 0) usageState.put("subagentCount", subagentCount[0]);
                if (currentInput > 0 || childInputTokens[0] > 0) usageState.put("totalInputTokens", currentInput + childInputTokens[0]);
                if (currentOutput > 0 || childOutputTokens[0] > 0) usageState.put("totalOutputTokens", currentOutput + childOutputTokens[0]);
            }
            private void persistUsageProjection() throws Exception {
                refreshUsageProjection();
                if (usagePersisted[0]) db.replaceTimelineBody(usageKey, usageState);
                else { db.appendTimeline(id, usageKey, usageState); usagePersisted[0] = true; }
            }
            private void commitRoundUsage() throws Exception {
                mainInput[0] += roundInput[0]; mainOutput[0] += roundOutput[0]; mainCached[0] += roundCached[0];
                mainCacheWrite[0] += roundCacheWrite[0]; mainCacheBase[0] += roundCacheBase[0]; mainReasoning[0] += roundReasoning[0];
                roundInput[0] = 0; roundOutput[0] = 0; roundCached[0] = 0; roundCacheWrite[0] = 0; roundCacheBase[0] = 0; roundReasoning[0] = 0;
                refreshUsageProjection();
            }
            private void clearIntermediateContinuation() {
                providerContinuationKeys.clear(); providerContinuationChars[0] = 0; providerContinuationOverflow[0] = false;
                while (providerContinuationItems.length() > 0) providerContinuationItems.remove(providerContinuationItems.length() - 1);
            }
            private JSONObject liveProjection() throws Exception {
                JSONObject next = new JSONObject(live.toString()).put("text", textOut.toString()).put("streaming", true).put("streamStage", streamStage.get());
                if (reasoningOut.length() > 0) next.put("reasoning", reasoningOut.toString());
                if (outputMedia.length() > 0) next.put("attachments", outputMedia);
                if (!webSources.isEmpty()) next.put("sources", sourceArray(webSources)); else next.remove("sources");
                if (!webBackend[0].isEmpty()) next.put("webBackend", webBackend[0]); else next.remove("webBackend");
                return next;
            }
            private void markProjectionDirty() {
                streamProjectionRevision.incrementAndGet();
                scheduleProjectionFlush();
            }
            private void scheduleProjectionFlush() {
                if (streamTerminal.get() || closed || !streamProjectionScheduled.compareAndSet(false, true)) return;
                try {
                    streamProjectionScheduler.schedule(() -> {
                        final long targetRevision = streamProjectionRevision.get();
                        worker.execute(() -> {
                            try {
                                if (!streamTerminal.get()) {
                                    JSONObject projection = liveProjection();
                                    db.replaceTimelineBody(streamKey, projection);
                                    emitStreamingPatch(id, projection);
                                }
                            } catch (Exception ignored) { }
                            finally {
                                streamProjectionScheduled.set(false);
                                if (!streamTerminal.get() && streamProjectionRevision.get() != targetRevision) scheduleProjectionFlush();
                            }
                        });
                    }, STREAM_PROJECTION_INTERVAL_MS, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    streamProjectionScheduled.set(false);
                }
            }
            @Override public void onStage(String stage) {
                if (stage == null || stage.trim().isEmpty() || streamTerminal.get()) return;
                streamStage.set(stage.trim());
                markProjectionDirty();
            }
            @Override public void onReasoningDelta(String delta) {
                if (delta == null || delta.isEmpty() || streamTerminal.get()) return;
                streamStage.set("reasoning");
                reasoningOut.append(delta);
                markProjectionDirty();
            }
            @Override public void onProviderContinuation(JSONObject state) {
                if (state == null) return;
                final int providerRound = (int) clientToolBatchCount.get() + 1;
                worker.execute(() -> { try {
                    if (!provider.protocol.equals(state.optString("protocol", "")) || providerContinuationOverflow[0]) return;
                    JSONObject item = state.optJSONObject("item"); if (item == null) return;
                    String serialized = item.toString();
                    String key = item.optString("id", "").trim();
                    if (key.isEmpty()) key = item.optString("type", "") + ":" + Integer.toHexString(serialized.hashCode());
                    if (!providerContinuationKeys.add(key)) return;
                    if (providerContinuationChars[0] + serialized.length() > MAX_PROVIDER_CONTINUATION_CHARS) {
                        providerContinuationOverflow[0] = true;
                        providerContinuationKeys.clear();
                        while (providerContinuationItems.length() > 0) providerContinuationItems.remove(providerContinuationItems.length() - 1);
                        return; // never persist/replay a partial opaque output-item set.
                    }
                    providerContinuationChars[0] += serialized.length();
                    JSONObject copy = new JSONObject(serialized);
                    // Same-turn protocol replay is required even when cross-turn native continuation is disabled.
                    providerRoundHistory.put(new JSONObject().put("round", providerRound).put("item", new JSONObject(serialized)));
                    if (allowProviderContinuation) providerContinuationItems.put(copy);
                } catch (Exception ignored) { } });
            }
            @Override public void onTextDelta(String delta) {
                if (delta == null || delta.isEmpty() || streamTerminal.get()) return;
                if (firstOutputNanos[0] == 0L) firstOutputNanos[0] = System.nanoTime();
                streamStage.set("output");
                textOut.append(delta);
                markProjectionDirty();
            }
            @Override public void onImage(String mimeType, String base64, String fileName) { if (!allowImageOutput) return; worker.execute(() -> { try { if (firstOutputNanos[0] == 0L) firstOutputNanos[0] = System.nanoTime(); streamStage.set("output"); JSONObject image = mediaStore.saveBase64Image(mimeType, base64, fileName); outputMedia.put(image); JSONObject projection = liveProjection(); db.replaceTimelineBody(streamKey, projection); emitStreamingPatch(id, projection); } catch (Exception error) { host.showMessage("image output ignored: " + safe(error)); } }); }
            @Override public void onToolActivity(String tool, String state) { worker.execute(() -> { try {
                if (ChatTurnOptions.WEB_SEARCH.equals(tool) || ChatTurnOptions.WEB_FETCH.equals(tool)) {
                    streamStage.set(tool);
                    if (webBackend[0].isEmpty() || "unavailable".equals(webBackend[0])) webBackend[0] = providerWebBackend(provider.protocol);
                    JSONObject projection = liveProjection(); db.replaceTimelineBody(streamKey, projection); emitStreamingPatch(id, projection);
                }
            } catch (Exception ignored) { } }); }
            @Override public void onWebSource(String url, String title) { worker.execute(() -> { try {
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
                if (!webSources.containsKey(url) && webSources.size() < 16) webSources.put(url, new JSONObject()
                        .put("url", url).put("title", title == null || title.trim().isEmpty() ? url : title.trim())
                        .put("backend", providerWebBackend(provider.protocol)));
                if (webBackend[0].isEmpty() || "unavailable".equals(webBackend[0])) webBackend[0] = providerWebBackend(provider.protocol);
                JSONObject projection = liveProjection(); db.replaceTimelineBody(streamKey, projection); emitStreamingPatch(id, projection);
            } catch (Exception ignored) { } }); }
            @Override public void onClientToolBatch(JSONArray calls) {
                if (streamTerminal.get()) return;
                ChatTurnOptions emittedOptions = activeRoundOptions.get();
                if (emittedOptions == null || !emittedOptions.clientToolsEnabled(provider.protocol)) {
                    streamTerminal.set(true);
                    providerRoundGeneration.incrementAndGet();
                    worker.execute(() -> {
                        settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                        finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0],
                                new String[]{"unexpected_tool_call", "Provider requested a client tool after tools were disabled"});
                    });
                    return;
                }
                JSONArray batchCalls = copyArray(calls);
                if (batchCalls.length() == 0) {
                    streamTerminal.set(true);
                    providerRoundGeneration.incrementAndGet();
                    worker.execute(() -> finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia,
                            webSources, webBackend[0], new String[]{"invalid_tool_call", "Provider emitted an empty client tool batch"}));
                    return;
                }
                if (batchCalls.length() > ClientToolRuntime.MAX_TOOL_CALLS_PER_BATCH) {
                    streamTerminal.set(true);
                    providerRoundGeneration.incrementAndGet();
                    worker.execute(() -> finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia,
                            webSources, webBackend[0], new String[]{"tool_batch_limit", "Client tool batch limit exceeded"}));
                    return;
                }

                // A tool batch is terminal for this provider round, not for the user turn. Invalidate
                // the just-finished round immediately so a very fast callback cannot re-bind its old Call.
                providerRoundGeneration.incrementAndGet();
                ChatTransport.Turn completedRound = activeTurns.remove(id);
                turnControl.clearTransport(completedRound);
                int batchNumber = (int) clientToolBatchCount.incrementAndGet();
                if (batchNumber > MAX_CLIENT_TOOL_ROUNDS) {
                    streamTerminal.set(true);
                    worker.execute(() -> finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia,
                            webSources, webBackend[0], new String[]{"tool_round_limit", "Client tool round limit exceeded"}));
                    return;
                }

                // Text/reasoning/images emitted only while deciding to call tools are intermediate
                // protocol state, not the final assistant answer shown in the transcript.
                if (textOut.length() > roundTextStart[0]) textOut.setLength(roundTextStart[0]);
                if (reasoningOut.length() > roundReasoningStart[0]) reasoningOut.setLength(roundReasoningStart[0]);
                while (outputMedia.length() > roundMediaStart[0]) outputMedia.remove(outputMedia.length() - 1);
                streamStage.set("tool");
                markProjectionDirty();

                worker.execute(() -> {
                    try {
                        commitRoundUsage();
                        if (providerContinuationOverflow[0]) {
                            streamTerminal.set(true);
                            settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                            finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia,
                                    webSources, webBackend[0], new String[]{"provider_round_state_overflow", "Provider round state is too large to replay safely"});
                            return;
                        }
                        clearIntermediateContinuation();
                        persistUsageProjection();
                    } catch (Exception ignored) { }
                    if (turnControl.isCancelled()) {
                        streamTerminal.set(true);
                        settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                        finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0],
                                new String[]{"cancelled", "Generation stopped"});
                        return;
                    }
                    try {
                        preparationWorker.execute(() -> {
                            turnControl.bindCurrentThread();
                            try {
                                ClientToolRuntime.BatchResult batch = ClientToolRuntime.executeBatch(batchCalls, batchNumber, clientToolCsv,
                                        clientToolWorker, webService, focusedSubagent, transport, provider, key, turnConfig.model,
                                        turnConfig.reasoningEffort, turnControl);
                                worker.execute(() -> {
                                    try {
                                        if (turnControl.isCancelled()) {
                                            streamTerminal.set(true);
                                            settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                                            finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia,
                                                    webSources, webBackend[0], new String[]{"cancelled", "Generation stopped"});
                                            return;
                                        }
                                        for (int i = 0; i < batch.historyEntries.length(); i++) {
                                            JSONObject entry = batch.historyEntries.optJSONObject(i); if (entry == null) continue;
                                            clientToolHistory.put(new JSONObject(entry.toString()));
                                            String toolName = entry.optString("name", "");
                                            if (ClientToolRuntime.WEB_SEARCH.equals(toolName) || ClientToolRuntime.WEB_FETCH.equals(toolName)) {
                                                usedClientWeb[0] = true;
                                                if (webBackend[0].isEmpty() || "unavailable".equals(webBackend[0])) webBackend[0] = "Moyu client Web";
                                                JSONArray sources = entry.optJSONArray("sources");
                                                if (sources != null) for (int j = 0; j < sources.length() && webSources.size() < 16; j++) {
                                                    JSONObject source = sources.optJSONObject(j); if (source == null) continue;
                                                    String sourceUrl = source.optString("url", "");
                                                    if (!sourceUrl.isEmpty() && !webSources.containsKey(sourceUrl)) webSources.put(sourceUrl, new JSONObject(source.toString()));
                                                }
                                            }
                                            String toolResult = entry.optString("result", "").trim();
                                            if (!toolResult.isEmpty() && clientEvidenceContext.length() < 24_000) {
                                                int remaining = 24_000 - clientEvidenceContext.length();
                                                String clipped = toolResult.length() > Math.min(6_000, remaining)
                                                        ? toolResult.substring(0, Math.min(6_000, remaining)) : toolResult;
                                                clientEvidenceContext.append("\n\n[").append(toolName).append(" result]\n").append(clipped);
                                            }
                                        }
                                        childInputTokens[0] += batch.childInputTokens;
                                        childOutputTokens[0] += batch.childOutputTokens;
                                        subagentCount[0] += batch.subagentCount;
                                        persistUsageProjection();
                                        ChatTurnOptions nextOptions = webOptions.withToolHistory(clientToolHistory);
                                        if (batchNumber >= MAX_CLIENT_TOOL_ROUNDS) nextOptions = nextOptions.withoutClientTools();
                                        streamStage.set("connecting");
                                        JSONObject projection = liveProjection();
                                        db.replaceTimelineBody(streamKey, projection);
                                        emitStreamingPatch(id, projection);
                                        java.util.function.Consumer<ChatTurnOptions> starter = roundStarter.get();
                                        if (starter == null) throw new IllegalStateException("provider round starter unavailable");
                                        starter.accept(nextOptions);
                                    } catch (Exception toolResultError) {
                                        streamTerminal.set(true);
                                        settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                                        finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia,
                                                webSources, webBackend[0], new String[]{"tool_result_failed", safe(toolResultError)});
                                    }
                                });
                            } finally { turnControl.clearCurrentThread(); }
                        });
                    } catch (Exception schedulingError) {
                        streamTerminal.set(true);
                        settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                        finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia,
                                webSources, webBackend[0], new String[]{"tool_execution_failed", safe(schedulingError)});
                    }
                });
            }
            @Override public void onUsage(Integer in, Integer out) { onUsageDetails(in, out, null, null, null, null); }
            @Override public void onUsageDetails(Integer in, Integer out, Integer cached, Integer cacheWrite, Integer cacheBase, Integer reasoningTokens) { worker.execute(() -> { try {
                if (in != null) roundInput[0] = Math.max(roundInput[0], Math.max(0, in));
                if (out != null) roundOutput[0] = Math.max(roundOutput[0], Math.max(0, out));
                if (cached != null) roundCached[0] = Math.max(roundCached[0], Math.max(0, cached));
                if (cacheWrite != null) roundCacheWrite[0] = Math.max(roundCacheWrite[0], Math.max(0, cacheWrite));
                if (cacheBase != null) roundCacheBase[0] = Math.max(roundCacheBase[0], Math.max(0, cacheBase));
                if (reasoningTokens != null) roundReasoning[0] = Math.max(roundReasoning[0], Math.max(0, reasoningTokens));
                persistUsageProjection();
            } catch (Exception ignored) { } }); }
            @Override public void onCompleted() {
                streamTerminal.set(true);
                providerRoundGeneration.incrementAndGet();
                worker.execute(() -> {
                try { commitRoundUsage(); persistUsageProjection(); } catch (Exception ignored) { }
                settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                boolean usedExternalEvidence = webPrepared.hasContext() || usedClientWeb[0] || !webSources.isEmpty();
                boolean shouldVerify = contextPolicy.verifierEnabled && adaptivePolicy.verifierAvailable
                        && HarnessPolicyResolver.shouldVerify(session, resolvedTaskProfile, usedExternalEvidence,
                        mainPrompt.truncated, subagentCount[0] > 0);
                if (!shouldVerify) {
                    try { attachProviderContinuation(live, turnConfig, providerContinuationItems); } catch (Exception ignored) { }
                    finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0], null);
                    return;
                }
                ChatTransport.Turn completedTurn = activeTurns.remove(id);
                turnControl.clearTransport(completedTurn);
                postProcessingStages.put(id, "verifying");
                try { live.put("streamStage", "verifying"); db.replaceTimelineBody(streamKey, live); } catch (Exception ignored) { }
                emitStreamingView(id);
                final String candidate = textOut.toString();
                final String candidateReasoning = reasoningOut.toString();
                final JSONArray candidateMedia = copyArray(outputMedia);
                final JSONArray continuation = copyArray(providerContinuationItems);
                final LinkedHashMap<String, JSONObject> candidateSources = copySources(webSources);
                final String candidateWebBackend = webBackend[0];
                final String verifierContext = (webPrepared.promptSuffix == null ? "" : webPrepared.promptSuffix)
                        + clientEvidenceContext.toString();
                preparationWorker.execute(() -> {
                    turnControl.bindCurrentThread();
                    try {
                        completeSmartTurn(id, streamKey, live, session, provider, key, transport,
                                mainPrompt, input, candidate, candidateReasoning, candidateMedia, candidateSources,
                                candidateWebBackend, continuation, verifierContext, turnConfig, turnControl);
                    } finally {
                        turnControl.clearCurrentThread();
                    }
                });
            }); }
            @Override public void onError(String code, String summary, boolean retryable) {
                streamTerminal.set(true);
                providerRoundGeneration.incrementAndGet();
                worker.execute(() -> {
                    try { commitRoundUsage(); persistUsageProjection(); } catch (Exception ignored) { }
                    settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                    finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0], new String[]{code, summary});
                });
            }
        };
        roundStarter.set(options -> {
            if (streamTerminal.get() || turnControls.get(id) != turnControl) return;
            ChatTurnOptions roundOptions = options == null ? ChatTurnOptions.NONE : options;
            activeRoundOptions.set(roundOptions);
            roundTextStart[0] = textOut.length();
            roundReasoningStart[0] = reasoningOut.length();
            roundMediaStart[0] = outputMedia.length();
            roundInput[0] = 0; roundOutput[0] = 0; roundCached[0] = 0;
            roundCacheWrite[0] = 0; roundCacheBase[0] = 0; roundReasoning[0] = 0;
            long generation = providerRoundGeneration.incrementAndGet();
            startingSessions.add(id);
            streamStage.set("connecting");
            ChatTransport.Turn turn;
            try {
                turn = transport.stream(provider, key, turnConfig.model, turnConfig.reasoningEffort, mainPrompt, roundOptions, sink);
            } catch (Exception startError) {
                streamTerminal.set(true);
                providerRoundGeneration.incrementAndGet();
                settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
                finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0],
                        new String[]{"transport_start_failed", safe(startError)});
                return;
            }
            // A terminal/tool callback can win before stream() returns. Generation protects against
            // resurrecting the just-finished provider round into activeTurns/TurnControl.
            if (streamTerminal.get() || turnControls.get(id) != turnControl || providerRoundGeneration.get() != generation) {
                try { turn.cancel(); } catch (Exception ignored) { }
                return;
            }
            turnControl.bindTransport(turn);
            if (turnControl.isCancelled()) {
                try { turn.cancel(); } catch (Exception ignored) { }
                return;
            }
            activeTurns.put(id, turn);
            startingSessions.remove(id);
            emitStreamingView(id);
        });
        roundStarter.get().accept(webOptions);
    }

    private void completeSmartTurn(String id, String streamKey, JSONObject seed, ChatDatabase.Session session,
                                   ChatDatabase.Provider provider, String apiKey, ChatTransport transport,
                                   CanonicalPrompt mainPrompt, String currentUserText, String candidate,
                                   String candidateReasoning, JSONArray candidateMedia,
                                   LinkedHashMap<String, JSONObject> webSources, String webBackend,
                                   JSONArray providerContinuationItems, String webContext,
                                   TurnConfig turnConfig, TurnControl turnControl) {
        String finalText = candidate == null ? "" : candidate;
        String finalReasoning = candidateReasoning == null ? "" : candidateReasoning;
        if (turnControl != null && turnControl.isCancelled()) {
            finishTurn(id, streamKey, seed, finalText, finalReasoning, candidateMedia, webSources, webBackend,
                    new String[]{"cancelled", "Generation stopped"});
            return;
        }
        boolean repairAttempted = false;
        boolean repaired = false;
        boolean repairLimitReached = false;
        String calibrationKey = SmartVerifierCalibration.calibrationKey(turnConfig.deploymentFingerprint);
        JSONArray evidence = SmartVerifierService.evidence(currentUserText, mainPrompt, sourceArray(webSources), webContext);
        JSONArray attempts = new JSONArray();
        SmartVerifierService.Result verification = smartVerifier.verify(transport, provider, apiKey, session,
                mainPrompt, currentUserText, finalText, evidence);
        if (turnControl != null && turnControl.isCancelled()) {
            finishTurn(id, streamKey, seed, finalText, finalReasoning, candidateMedia, webSources, webBackend,
                    new String[]{"cancelled", "Generation stopped"});
            return;
        }
        SmartVerifierCalibration.Decision decision = SmartVerifierCalibration.decide(db, calibrationKey, verification);
        attempts.put(verificationAttemptJson(verification, decision));
        String terminalAction = decision.action;

        if (SmartVerifierCalibration.ACTION_REPAIR.equals(decision.action)
                && SmartVerifierService.STATUS_VALID.equals(verification.status)) {
            repairAttempted = true;
            setPostProcessingStage(id, streamKey, seed, "repairing");
            String repairedText = smartVerifier.repair(transport, provider, apiKey, session,
                    currentUserText, finalText, verification, evidence);
            if (turnControl != null && turnControl.isCancelled()) {
                finishTurn(id, streamKey, seed, finalText, finalReasoning, candidateMedia, webSources, webBackend,
                        new String[]{"cancelled", "Generation stopped"});
                return;
            }
            if (repairedText != null && !repairedText.trim().isEmpty()) {
                repaired = true;
                finalText = repairedText;
                // The visible answer now comes from the repair call. First-pass visible reasoning and
                // native continuation no longer describe that answer and must not be presented/replayed.
                finalReasoning = "";
                setPostProcessingStage(id, streamKey, seed, "verifying");
                verification = smartVerifier.verify(transport, provider, apiKey, session,
                        mainPrompt, currentUserText, finalText, evidence);
                if (turnControl != null && turnControl.isCancelled()) {
                    finishTurn(id, streamKey, seed, finalText, finalReasoning, candidateMedia, webSources, webBackend,
                            new String[]{"cancelled", "Generation stopped"});
                    return;
                }
                decision = SmartVerifierCalibration.decide(db, calibrationKey, verification);
                attempts.put(verificationAttemptJson(verification, decision));
                if (SmartVerifierCalibration.ACTION_REPAIR.equals(decision.action)) {
                    repairLimitReached = true;
                    terminalAction = SmartVerifierCalibration.ACTION_ABSTAIN;
                } else terminalAction = decision.action;
            } else {
                terminalAction = SmartVerifierCalibration.ACTION_ABSTAIN;
            }
        }

        try {
            JSONObject meta = verification.toJson()
                    .put("risk", decision.risk)
                    .put("recommendedAction", decision.action)
                    .put("action", terminalAction)
                    .put("calibrationKey", calibrationKey)
                    .put("features", new JSONArray(decision.features.toString()))
                    .put("calibrationLabels", decision.calibrationLabels)
                    .put("learnedCalibration", decision.learnedBlend)
                    .put("repairAttempted", repairAttempted)
                    .put("repaired", repaired)
                    .put("repairLimitReached", repairLimitReached)
                    .put("attempts", attempts);
            seed.put("verification", meta);
            if (!repaired) attachProviderContinuation(seed, turnConfig, providerContinuationItems);
        } catch (Exception ignored) { }
        finishTurn(id, streamKey, seed, finalText, finalReasoning, candidateMedia, webSources, webBackend, null);
    }

    private void setPostProcessingStage(String id, String streamKey, JSONObject seed, String stage) {
        postProcessingStages.put(id, stage);
        try { seed.put("streamStage", stage); db.replaceTimelineBody(streamKey, seed); } catch (Exception ignored) { }
        emitStreamingView(id);
    }

    private static JSONObject verificationAttemptJson(SmartVerifierService.Result result,
                                                       SmartVerifierCalibration.Decision decision) {
        try {
            return result.toJson().put("risk", decision.risk).put("action", decision.action)
                    .put("features", new JSONArray(decision.features.toString()));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONArray copyArray(JSONArray source) {
        try { return source == null ? new JSONArray() : new JSONArray(source.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private static LinkedHashMap<String, JSONObject> copySources(Map<String, JSONObject> source) {
        LinkedHashMap<String, JSONObject> out = new LinkedHashMap<>();
        if (source == null) return out;
        for (Map.Entry<String, JSONObject> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            try { out.put(entry.getKey(), new JSONObject(entry.getValue().toString())); }
            catch (Exception ignored) { }
        }
        return out;
    }

    private void settleUsage(String id, String usageKey, JSONObject usageState, boolean[] usagePersisted,
                             long requestStartedNanos, long firstOutputNanos) {
        try {
            long finished = System.nanoTime();
            usageState.put("requestDurationMs", Math.max(0L, (finished - requestStartedNanos) / 1_000_000L));
            if (firstOutputNanos > 0L) {
                long outputDurationMs = Math.max(1L, (finished - firstOutputNanos) / 1_000_000L);
                usageState.put("firstOutputLatencyMs", Math.max(0L, (firstOutputNanos - requestStartedNanos) / 1_000_000L));
                usageState.put("outputDurationMs", outputDurationMs);
                if (usageState.has("outputTokens")) {
                    int totalOutput = Math.max(0, usageState.optInt("outputTokens", 0));
                    int reasoning = Math.max(0, usageState.optInt("reasoningTokens", 0));
                    int visibleOutput = Math.max(0, totalOutput - reasoning);
                    if (visibleOutput > 0) usageState.put("outputTokensPerSecond", visibleOutput * 1000.0d / outputDurationMs);
                }
            }
            if (usagePersisted[0]) db.replaceTimelineBody(usageKey, usageState);
            else { db.appendTimeline(id, usageKey, usageState); usagePersisted[0] = true; }
        } catch (Exception ignored) { }
    }

    private void finishTurn(String id, String streamKey, JSONObject seed, String text, String reasoning, JSONArray outputMedia,
                            Map<String, JSONObject> webSources, String webBackend, String[] error) {
        boolean hasVisibleOutput = !text.isEmpty() || (outputMedia != null && outputMedia.length() > 0);
        boolean hasProjection = hasVisibleOutput || (reasoning != null && !reasoning.isEmpty());
        String[] terminalError = error;
        if (terminalError == null && !hasVisibleOutput) {
            terminalError = new String[]{"empty_assistant_output", "Provider completed without visible text or image output"};
        }
        String finalState = terminalError == null || "cancelled".equals(terminalError[0]) ? "completed" : "failed";
        try {
            JSONObject finished = null;
            if (hasProjection) {
                finished = new JSONObject(seed.toString()).put("text", text).put("streaming", false);
                finished.remove("streamStage");
                if (terminalError != null) finished.put("incomplete", true).put("terminalCode", terminalError[0]);
                if (reasoning != null && !reasoning.isEmpty()) finished.put("reasoning", reasoning);
                if (outputMedia != null && outputMedia.length() > 0) finished.put("attachments", outputMedia);
                if (webSources != null && !webSources.isEmpty()) finished.put("sources", sourceArray(webSources));
                if (webBackend != null && !webBackend.trim().isEmpty()) finished.put("webBackend", webBackend.trim());
            }
            db.finalizeTurn(id, streamKey, finished,
                    terminalError == null ? null : terminalError[0], terminalError == null ? null : terminalError[1], finalState);
        } catch (Exception finalizeError) {
            // Runtime authority must never remain locked because a projection write failed.
            try { db.deleteTimelineItem(streamKey); } catch (Exception ignored) { }
            try {
                ChatDatabase.Session s = db.getSession(id);
                if (s != null) { s.state = "failed"; s.updatedAt = Instant.now().toString(); db.saveSession(s); }
            } catch (Exception ignored) { }
            host.showMessage("turn finalization failed: " + safe(finalizeError));
        } finally {
            activeTurns.remove(id);
            turnControls.remove(id);
            startingSessions.remove(id);
            preparingSessions.remove(id);
            webPreparingStages.remove(id);
            postProcessingStages.remove(id);
            lastStreamViewNanos.remove(id);
            host.setTurnRuntimeActive(id, false);
            emitView();
        }
    }

    private void interrupt(String id) {
        TurnControl control = turnControls.get(id);
        if (control != null) control.cancel();
        ChatTransport.Turn turn = activeTurns.get(id);
        if (turn != null) try { turn.cancel(); } catch (Exception ignored) { }
        emitStreamingView(id);
    }

    private void refreshSources() throws Exception { RemoteDataBridge.Status status = remoteBridge.status(); remoteSources = status.available ? remoteBridge.listSources() : new JSONArray(); }

    private void createLocalBranch(String sourceSessionId, Integer throughSeq) throws Exception {
        ChatDatabase.Session source = db.getSession(sourceSessionId); if (source == null) throw new IllegalArgumentException("source session not found");
        if (activeTurns.containsKey(sourceSessionId)) throw new IllegalStateException("interrupt the current turn before branching");
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(sourceSessionId, throughSeq);
        ChatDatabase.Session branch = db.branchSession(source, "分支 · " + source.title, snapshot.id, sourceSessionId);
        activate(branch.id);
    }

    /** Edit rewinds the current local session and restores the selected user input into the composer. */
    private void editMessageInPlace(String sessionId, int localSeq) throws Exception {
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
        if (sessionBusy(sessionId)) throw new IllegalStateException("interrupt the current turn before editing history");
        JSONObject selected = messageAt(sessionId, localSeq);
        if (!"user".equals(selected.optString("role"))) throw new IllegalArgumentException("only user messages can be edited");
        String text = selected.optString("text", "");
        JSONArray attachments = selected.optJSONArray("attachments");
        db.truncateTimelineFrom(sessionId, localSeq);
        db.setDraft(sessionId, text);
        db.replaceDraftMedia(sessionId, attachments == null ? new JSONArray() : new JSONArray(attachments.toString()));
        mediaStore.pruneUnreferenced(db.referencedMediaIds());
        activate(sessionId);
    }

    /** Rollback rewinds the current local session; branching is a separate explicit user action. */
    private void rollbackMessageInPlace(String sessionId, int localSeq) throws Exception {
        if (db.getSession(sessionId) == null) throw new IllegalArgumentException("session not found");
        if (sessionBusy(sessionId)) throw new IllegalStateException("interrupt the current turn before rolling back");
        messageAt(sessionId, localSeq);
        db.truncateTimelineFrom(sessionId, localSeq);
        mediaStore.pruneUnreferenced(db.referencedMediaIds());
        activate(sessionId);
    }

    private JSONObject messageAt(String sessionId, int localSeq) {
        if (localSeq < 1) throw new IllegalArgumentException("invalid localSeq");
        JSONArray timeline = db.timeline(sessionId);
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject item = timeline.optJSONObject(i);
            if (item == null || item.optInt("localSeq", -1) != localSeq) continue;
            if (!"message".equals(item.optString("kind")) || item.optBoolean("streaming", false)) throw new IllegalArgumentException("target is not a finalized message");
            String role = item.optString("role", "");
            if (!("user".equals(role) || "assistant".equals(role))) throw new IllegalArgumentException("target message role cannot be branched");
            return item;
        }
        throw new IllegalArgumentException("message not found");
    }

    private void createRemoteBranch(String sourceId, Integer throughSeq, String contextMode, String webMode) throws Exception {
        if (!remoteBridge.status().available) throw new IllegalStateException("RemoteDataBridge unavailable");
        ChatDatabase.Provider provider = preferredProvider(); if (provider == null) throw new IllegalStateException("configure a Chat provider before importing a Remote branch");
        ChatDatabase.ModelProfile profile = db.firstModelProfile(provider.id); if (profile == null) throw new IllegalStateException("configure a model before importing a Remote branch");
        ChatDatabase.Snapshot snapshot = snapshots.importRemote(remoteBridge.createSnapshot(sourceId, throughSeq));
        ChatDatabase.Session branch = db.createSession(profile, "分支 · " + snapshot.sourceTitle, snapshot.id, null, ReasoningPolicy.DEFAULT, contextMode, webMode);
        activate(branch.id);
    }

    private void recordVerificationFeedback(String sessionId, int localSeq, String label) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("wait for the current turn before rating verification");
        String normalized = label == null ? "" : label.trim().toLowerCase(java.util.Locale.ROOT);
        if (!("accurate".equals(normalized) || "false_alarm".equals(normalized) || "missed_error".equals(normalized))) {
            throw new IllegalArgumentException("unsupported verification feedback label");
        }
        db.verificationForFeedback(sessionId, localSeq, normalized);
        host.showMessage("校验反馈已记录");
    }

    private void createImportedBranch(String snapshotId, String contextMode, String webMode) throws Exception {
        ChatDatabase.Snapshot snapshot = db.getSnapshot(snapshotId);
        if (snapshot == null || !("import-chatgpt".equals(snapshot.sourceType) || "import-claude".equals(snapshot.sourceType))) {
            throw new IllegalArgumentException("imported conversation not found");
        }
        ChatDatabase.Provider provider = preferredProvider(); if (provider == null) throw new IllegalStateException("configure a Chat provider before opening an imported conversation");
        ChatDatabase.ModelProfile profile = db.firstModelProfile(provider.id); if (profile == null) throw new IllegalStateException("configure a model before opening an imported conversation");
        ChatDatabase.Session branch = db.createSession(profile, "导入 · " + snapshot.sourceTitle, snapshot.id, null,
                ReasoningPolicy.DEFAULT, contextMode, webMode);
        activate(branch.id);
    }

    private ChatDatabase.Provider preferredProvider() {
        for (ChatDatabase.Provider provider : db.listProviders()) if (secrets.has(provider.id)) return provider;
        return null;
    }

    private void createHandoff(String sessionId) throws Exception {
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
        JSONArray timeline = db.timeline(sessionId);
        String summary = latestAssistantText(timeline);
        if (summary.isEmpty()) throw new IllegalStateException("no assistant conclusion available");
        JSONObject payload = new JSONObject().put("handoffId", UUID.randomUUID().toString()).put("branchSessionId", sessionId)
                .put("sourceSnapshotId", session.originSnapshotId == null ? JSONObject.NULL : session.originSnapshotId)
                .put("summary", summary).put("selectedContext", new JSONArray()).put("createdAt", Instant.now().toString());
        ChatDatabase.Handoff h = new ChatDatabase.Handoff(); h.id = payload.getString("handoffId"); h.branchSessionId = sessionId; h.state = "draft"; h.summary = summary; h.payload = payload.toString(); h.createdAt = payload.getString("createdAt"); db.saveHandoff(h);
        route = "handoffs"; db.putSetting("route", route);
    }

    private void submitHandoff(String handoffId) throws Exception {
        ChatDatabase.Handoff h = db.getHandoff(handoffId); if (h == null) throw new IllegalArgumentException("handoff not found");
        if ("submitted".equals(h.state)) throw new IllegalStateException("handoff already submitted");
        if (!remoteBridge.status().available) throw new IllegalStateException("RemoteDataBridge unavailable; handoff remains local");
        remoteBridge.submitHandoff(new JSONObject(h.payload)); h.state = "submitted"; db.saveHandoff(h);
    }

    private static String latestAssistantText(JSONArray timeline) {
        for (int i = timeline.length() - 1; i >= 0; i--) { JSONObject item = timeline.optJSONObject(i); if (item != null && "message".equals(item.optString("kind")) && "assistant".equals(item.optString("role")) && !item.optBoolean("streaming", false)) { String text = item.optString("text", "").trim(); if (!text.isEmpty()) return text; } }
        return "";
    }

    private void attachProviderContinuation(JSONObject seed, TurnConfig turnConfig, JSONArray items) throws Exception {
        if (seed == null || turnConfig == null || turnConfig.provider == null || items == null || items.length() == 0
                || !ProviderContinuationPolicy.enabled(turnConfig.continuationMode)
                || turnConfig.deploymentFingerprint == null || turnConfig.deploymentFingerprint.trim().isEmpty()) return;
        JSONObject state = new JSONObject().put("protocol", turnConfig.provider.protocol).put("providerId", turnConfig.providerId)
                .put("modelProfileId", turnConfig.modelProfileId == null ? "" : turnConfig.modelProfileId)
                .put("model", turnConfig.model)
                .put("deploymentFingerprint", turnConfig.deploymentFingerprint)
                .put("items", new JSONArray(items.toString()));
        seed.put("providerContinuation", state);
    }

    private static JSONArray sourceArray(Map<String, JSONObject> sources) {
        JSONArray out = new JSONArray();
        if (sources == null) return out;
        for (JSONObject source : sources.values()) if (source != null) out.put(source);
        return out;
    }

    private static String providerWebBackend(String protocol) {
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return "Provider hosted Web · Responses";
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return "Provider hosted Web · Messages";
        return "Provider hosted Web";
    }

    private static String effectiveWebBackend(ChatDatabase.Session session, ChatDatabase.Provider provider) {
        if (session == null || ChatTurnOptions.WEB_OFF.equals(ChatTurnOptions.normalizeWebMode(session.webMode))) return "off";
        ChatTurnOptions nativeOptions = ChatTurnOptions.forSession(session, provider == null ? "" : provider.protocol);
        boolean nativeSearch = nativeOptions.supports(ChatTurnOptions.WEB_SEARCH);
        boolean nativeFetch = nativeOptions.supports(ChatTurnOptions.WEB_FETCH);
        if (nativeSearch && nativeFetch) return providerWebBackend(provider == null ? "" : provider.protocol);
        if (nativeSearch || nativeFetch) return providerWebBackend(provider == null ? "" : provider.protocol) + " + Moyu fallback";
        return "Moyu Web · Exa + Fetch";
    }

    /** High-frequency data-plane projection: never rebuild providers/session lists/history for a token delta. */
    private synchronized void emitStreamingPatch(String sessionId, JSONObject liveItem) {
        if (closed || !uiReady || sessionId == null || !sessionId.equals(activeSessionId) || !"conversation".equals(route) || liveItem == null) return;
        try {
            boolean active = sessionBusy(sessionId);
            JSONObject sessionPatch = new JSONObject()
                    .put("state", active ? "running" : nonEmpty(db.getSession(sessionId) == null ? null : db.getSession(sessionId).state, "failed"))
                    .put("canSend", !active)
                    .put("canInterrupt", turnControls.containsKey(sessionId))
                    .put("canConfigureTurn", !active)
                    .put("contextPreparing", preparingSessions.contains(sessionId));
            String webStage = webPreparingStages.get(sessionId);
            if (webStage != null) sessionPatch.put("webPreparingStage", webStage); else sessionPatch.put("webPreparingStage", JSONObject.NULL);
            String postStage = postProcessingStages.get(sessionId);
            if (postStage != null) sessionPatch.put("postProcessingStage", postStage); else sessionPatch.put("postProcessingStage", JSONObject.NULL);
            JSONObject item = new JSONObject(liveItem.toString());
            item.remove("providerContinuation");
            JSONObject patch = new JSONObject().put("scope", "conversation.stream").put("localSessionId", sessionId)
                    .put("session", sessionPatch).put("item", item);
            host.dispatch(new JSONObject().put("version", 1).put("type", "view.patch").put("revision", ++revision).put("patch", patch));
        } catch (Exception error) { host.showMessage("stream patch failed: " + safe(error)); }
    }

    private void emitStreamingView(String sessionId) {
        if (sessionId == null || !sessionId.equals(activeSessionId)) return;
        long now = System.nanoTime();
        Long previous = lastStreamViewNanos.get(sessionId);
        // 20 fps is sufficient for text streaming and avoids saturating WebView/JS with full projections.
        if (previous != null && now - previous < 50_000_000L) return;
        lastStreamViewNanos.put(sessionId, now);
        emitView();
    }

    private synchronized void emitView() {
        if (closed || !uiReady) return;
        try {
            JSONObject view = new JSONObject().put("route", route).put("now", Instant.now().toString()).put("appearance", new JSONObject().put("theme", theme).put("resolvedTheme", "dark".equals(theme) ? "dark" : "light"));
            JSONArray providers = new JSONArray();
            for (ChatDatabase.Provider p : db.listProviders()) {
                JSONArray models = new JSONArray();
                for (ChatDatabase.ModelProfile m : db.listModelProfiles(p.id)) models.put(new JSONObject()
                        .put("modelProfileId", m.id).put("modelId", m.modelId).put("displayName", m.displayName)
                        .put("protocol", ChatDatabase.normalizeProtocol(m.protocol))
                        .put("authMode", ProviderAuth.normalize(m.authMode, m.protocol))
                        .put("reasoningWire", ReasoningPolicy.normalizeWire(m.reasoningWire, m.protocol))
                        .put("contextTokens", m.contextTokens).put("inputTokens", m.inputTokens).put("maxOutputTokens", m.maxOutputTokens)
                        .put("reasoningLevels", m.reasoningLevels)
                        .put("thinkingMode", ReasoningPolicy.normalizeThinkingMode(m.thinkingMode)).put("thinkingBudgetTokens", Math.max(0, m.thinkingBudgetTokens))
                        .put("continuationMode", ProviderContinuationPolicy.normalize(m.continuationMode))
                        .put("configMode", ModelCatalog.normalizeConfigMode(m.configMode))
                        .put("catalogKey", m.catalogKey == null ? "unknown" : m.catalogKey)
                        .put("catalogVersion", m.catalogVersion == null ? "" : m.catalogVersion)
                        .put("catalogSource", m.catalogSource == null ? "" : m.catalogSource)
                        .put("harnessPreset", HarnessProfile.normalize(m.harnessPreset))
                        .put("clientToolCalling", ClientToolRuntime.normalizeCalling(m.clientToolCalling))
                        .put("inputModalities", m.inputModalities).put("outputModalities", m.outputModalities)
                        .put("toolCapabilities", ChatTurnOptions.normalizeCapabilities(m.toolCapabilities)));
                String defaultModel = models.length() > 0 ? models.optJSONObject(0).optString("modelId", p.model) : p.model;
                providers.put(new JSONObject().put("providerId", p.id).put("displayName", p.displayName).put("baseUrl", p.baseUrl)
                        .put("model", defaultModel).put("hasCredential", secrets.has(p.id))
                        .put("configured", secrets.has(p.id) && models.length() > 0).put("models", models));
            }
            view.put("providers", providers);
            JSONArray sessions = new JSONArray(); for (ChatDatabase.Session s : db.listSessions()) sessions.put(sessionJson(s, false)); view.put("sessions", sessions);
            if (activeSessionId != null) {
                ChatDatabase.Session active = db.getSession(activeSessionId);
                if (active != null) {
                    view.put("activeLocalSessionId", activeSessionId);
                    // Route-scoped projection: non-conversation screens never pay to serialize/bridge the full timeline.
                    if ("conversation".equals(route)) view.put("activeSession", sessionJson(active, true));
                }
            }
            RemoteDataBridge.Status rs = remoteBridge.status(); JSONObject remote = new JSONObject().put("available", rs.available); if (rs.contractVersion > 0) remote.put("contractVersion", rs.contractVersion); if (rs.reason != null) remote.put("reason", rs.reason);
            JSONArray importedSources = new JSONArray();
            for (ChatDatabase.Snapshot snapshot : db.listImportedSnapshots()) importedSources.put(new JSONObject()
                    .put("snapshotId", snapshot.id).put("sourceType", snapshot.sourceType).put("sourceTitle", snapshot.sourceTitle)
                    .put("sourceRuntime", snapshot.sourceRuntime == null ? JSONObject.NULL : snapshot.sourceRuntime)
                    .put("createdAt", snapshot.createdAt).put("itemCount", snapshot.itemCount)
                    .put("sourceItemCount", snapshot.sourceItemCount).put("truncated", snapshot.truncated));
            JSONArray handoffs = new JSONArray(); for (ChatDatabase.Handoff h : db.listHandoffs()) handoffs.put(new JSONObject().put("handoffId", h.id).put("branchSessionId", h.branchSessionId).put("state", h.state).put("createdAt", h.createdAt).put("summary", h.summary));
            view.put("remoteBridge", remote).put("remoteSources", remoteSources).put("importedSources", importedSources).put("handoffs", handoffs)
                    .put("ui", new JSONObject().put("pendingRequestIds", new JSONArray()).put("showReasoning", showReasoning)
                            .put("crossChatMemoryEnabled", crossChatMemoryEnabled).put("importInProgress", importInProgress.get())
                            .put("defaultContextMode", defaultContextMode).put("defaultWebMode", defaultWebMode)
                            .put("standardSubagentMode", standardSubagentMode));
            host.dispatch(new JSONObject().put("version", 1).put("type", "view.full").put("revision", ++revision).put("view", view));
        } catch (Exception error) { host.showMessage("view update failed: " + safe(error)); }
    }

    static final class DisplayTimeline {
        final JSONArray items;
        final int omittedInheritedItems;
        DisplayTimeline(JSONArray items, int omittedInheritedItems) {
            this.items = items; this.omittedInheritedItems = Math.max(0, omittedInheritedItems);
        }
    }

    /** UI-only bound: durable snapshot/model context stays intact while WebView receives a recent whole-turn tail. */
    static DisplayTimeline boundedInheritedDisplay(JSONArray inherited) throws Exception {
        JSONArray source = inherited == null ? new JSONArray() : inherited;
        java.util.ArrayList<java.util.ArrayList<JSONObject>> turns = new java.util.ArrayList<>();
        java.util.ArrayList<JSONObject> current = null;
        int visible = 0, structurallyOmitted = 0;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i); if (item == null) continue;
            String role = item.optString("role", "");
            if (!("user".equals(role) || "assistant".equals(role))) continue;
            visible++;
            JSONObject copy = new JSONObject(item.toString()).put("inherited", true);
            if ("user".equals(role)) {
                current = new java.util.ArrayList<>(); current.add(copy); turns.add(current);
            } else if (current != null) current.add(copy);
            else structurallyOmitted++;
        }
        java.util.ArrayList<java.util.ArrayList<JSONObject>> keptReverse = new java.util.ArrayList<>();
        int count = 0, chars = 0;
        for (int i = turns.size() - 1; i >= 0; i--) {
            java.util.ArrayList<JSONObject> turn = turns.get(i);
            int turnChars = 0;
            for (JSONObject item : turn) turnChars += item.optString("text", "").length();
            if (count + turn.size() > MAX_UI_INHERITED_ITEMS || chars + turnChars > MAX_UI_INHERITED_CHARS) break;
            keptReverse.add(turn); count += turn.size(); chars += turnChars;
        }
        java.util.Collections.reverse(keptReverse);
        JSONArray out = new JSONArray();
        for (java.util.ArrayList<JSONObject> turn : keptReverse) for (JSONObject item : turn) out.put(item);
        return new DisplayTimeline(out, Math.max(0, visible - count));
    }

    private DisplayTimeline displayTimeline(ChatDatabase.Session s) throws Exception {
        DisplayTimeline inheritedDisplay = s.originSnapshotId == null
                ? new DisplayTimeline(new JSONArray(), 0)
                : boundedInheritedDisplay(db.snapshotItems(s.originSnapshotId));
        JSONArray out = new JSONArray(inheritedDisplay.items.toString());
        JSONArray local = db.timeline(s.id);
        for (int i = 0; i < local.length(); i++) {
            JSONObject item = local.optJSONObject(i); if (item == null) continue;
            JSONObject visible = new JSONObject(item.toString());
            // Opaque provider continuation can contain encrypted reasoning/signatures and is native-only.
            visible.remove("providerContinuation");
            out.put(visible);
        }
        return new DisplayTimeline(out, inheritedDisplay.omittedInheritedItems);
    }

    private JSONObject sessionJson(ChatDatabase.Session s, boolean detail) throws Exception {
        ChatDatabase.Provider p = db.getProvider(s.providerId);
        ChatDatabase.ModelProfile modelProfile = s.modelProfileId == null ? null : db.getModelProfile(s.modelProfileId);
        ChatDatabase.Provider deployment = p == null ? null : ChatDatabase.deploymentProvider(p, s);
        ChatDatabase.Snapshot originSnap = s.originSnapshotId == null ? null : db.getSnapshot(s.originSnapshotId);
        boolean starting = startingSessions.contains(s.id);
        boolean preparing = preparingSessions.contains(s.id);
        String webPreparingStage = webPreparingStages.get(s.id);
        boolean webPreparing = webPreparingStage != null;
        String postProcessingStage = postProcessingStages.get(s.id);
        boolean postProcessing = postProcessingStage != null;
        boolean active = turnControls.containsKey(s.id) || activeTurns.containsKey(s.id) || starting || preparing || webPreparing || postProcessing;
        String visibleState = active ? "running" : ("running".equals(s.state) ? "failed" : s.state);
        JSONObject o = new JSONObject().put("localSessionId", s.id).put("title", s.title).put("providerId", s.providerId).put("providerName", p == null ? s.providerId : p.displayName)
                .put("modelProfileId", s.modelProfileId == null ? JSONObject.NULL : s.modelProfileId).put("model", s.model)
                .put("modelName", modelProfile == null ? s.model : modelProfile.displayName)
                .put("reasoningLevels", modelProfile == null ? "" : ReasoningPolicy.normalizeLevels(modelProfile.reasoningLevels))
                .put("reasoningEffort", s.reasoningEffort == null ? ReasoningPolicy.DEFAULT : s.reasoningEffort)
                .put("thinkingMode", ReasoningPolicy.normalizeThinkingMode(s.thinkingMode)).put("thinkingBudgetTokens", Math.max(0, s.thinkingBudgetTokens))
                .put("continuationMode", ProviderContinuationPolicy.normalize(s.continuationMode))
                .put("harnessPreset", HarnessProfile.normalize(s.harnessPreset))
                .put("clientToolCalling", ClientToolRuntime.normalizeCalling(s.clientToolCalling))
                .put("subagentMode", HarnessPolicyResolver.normalizeSubagentMode(s.subagentMode))
                .put("contextMode", ContextMode.normalize(s.contextMode)).put("effectiveContextMode", ContextMode.resolve(s, originSnap).effectiveLabel)
                .put("inputModalities", ChatDatabase.normalizeModalities(s.inputModalities)).put("outputModalities", ChatDatabase.normalizeModalities(s.outputModalities))
                .put("nativeWebCapabilities", ChatTurnOptions.normalizeCapabilities(s.toolCapabilities))
                .put("webMode", ChatTurnOptions.normalizeWebMode(s.webMode))
                .put("effectiveWebBackend", effectiveWebBackend(s, deployment))
                .put("state", visibleState).put("updatedAt", s.updatedAt);
        if (s.preview != null) o.put("preview", s.preview);
        TaskProfile taskProfile = TaskProfile.fromStored(s.taskProfile);
        if (taskProfile != null) o.put("taskProfile", taskProfile.toJson());
        o.put("hasContextCheckpoint", ContextCheckpoint.fromStored(s.contextCheckpoint) != null);
        if (originSnap != null) o.put("origin", new JSONObject().put("snapshotId", originSnap.id).put("sourceType", originSnap.sourceType).put("sourceTitle", originSnap.sourceTitle).put("sourceRuntime", originSnap.sourceRuntime == null ? JSONObject.NULL : originSnap.sourceRuntime).put("createdAt", originSnap.createdAt).put("itemCount", originSnap.itemCount).put("sourceItemCount", originSnap.sourceItemCount).put("truncated", originSnap.truncated));
        if (detail) {
            DisplayTimeline display = displayTimeline(s);
            o.put("messages", display.items).put("composerDraft", s.draft == null ? "" : s.draft)
                    .put("draftMedia", db.draftMedia(s.id)).put("canAttachImage", ChatDatabase.supportsModality(s.inputModalities, "image") && !active)
                    .put("canSend", p != null && secrets.has(s.providerId) && !active).put("canInterrupt", turnControls.containsKey(s.id))
                    .put("canConfigureTurn", !active).put("contextPreparing", preparing);
            if (display.omittedInheritedItems > 0) o.put("displayOmittedInheritedItems", display.omittedInheritedItems);
            if (webPreparing) o.put("webPreparingStage", webPreparingStage);
            if (postProcessing) o.put("postProcessingStage", postProcessingStage);
        }
        return o;
    }

    private void result(String requestId, boolean ok, String error) { if (requestId == null || requestId.isEmpty()) return; try { JSONObject e = new JSONObject().put("version", 1).put("type", "intent.result").put("requestId", requestId).put("ok", ok); if (error != null) e.put("error", new JSONObject().put("summary", error)); host.dispatch(e); } catch (Exception ignored) { } }
    private static String required(JSONObject p, String key) { String v = p.optString(key, "").trim(); if (v.isEmpty()) throw new IllegalArgumentException("missing " + key); return v; }
    private static int requiredSeq(JSONObject p) { if (!p.has("localSeq")) throw new IllegalArgumentException("missing localSeq"); int seq = p.optInt("localSeq", -1); if (seq < 1) throw new IllegalArgumentException("invalid localSeq"); return seq; }
    private static String safe(Exception e) { String m = e.getMessage(); return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m; }
    private static String importSource(String source) {
        String value = source == null ? "" : source.trim().toLowerCase(java.util.Locale.ROOT);
        if (ConversationImportService.SOURCE_CHATGPT.equals(value) || ConversationImportService.SOURCE_CLAUDE.equals(value)) return value;
        throw new IllegalArgumentException("unsupported import source");
    }
    private static String importDisplayName(ContentResolver resolver, Uri uri) {
        if (resolver == null || uri == null) return "conversations.json";
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String value = c.getString(0); if (value != null && !value.trim().isEmpty()) return value.trim(); }
        } catch (Exception ignored) { }
        String last = uri.getLastPathSegment(); return last == null || last.trim().isEmpty() ? "conversations.json" : last.trim();
    }
    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String empty(String v) { return v == null || v.isEmpty() ? null : v; }
    private static String bounded(String value, int max, String label) { String v = value == null ? "" : value.trim(); if (v.isEmpty()) throw new IllegalArgumentException(label + " required"); if (v.length() > max) throw new IllegalArgumentException(label + " too long"); return v; }
    private static String validRoute(String value) { String v = value == null ? "" : value.trim(); if (!("home".equals(v) || "conversation".equals(v) || "providers".equals(v) || "imports".equals(v) || "handoffs".equals(v) || "settings".equals(v))) return "home"; return v; }
    private static String validTheme(String value) { String v = value == null ? "" : value.trim(); if (!("system".equals(v) || "light".equals(v) || "dark".equals(v))) return "system"; return v; }

    @Override public void close() { closed = true; for (TurnControl control : turnControls.values()) try { control.cancel(); } catch (Exception ignored) { } turnControls.clear(); for (ChatTransport.Turn t : activeTurns.values()) try { t.cancel(); } catch (Exception ignored) { } activeTurns.clear(); startingSessions.clear(); preparingSessions.clear(); webPreparingStages.clear(); postProcessingStages.clear(); lastStreamViewNanos.clear(); webService.close(); transports.close(); streamProjectionScheduler.shutdownNow(); preparationWorker.shutdownNow(); clientToolWorker.shutdownNow(); worker.shutdownNow(); }
}
