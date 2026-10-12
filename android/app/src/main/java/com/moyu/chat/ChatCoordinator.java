package com.moyu.chat;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** Native-only domain coordinator. UI knows intents/view models; provider keys never cross the bridge. */
final class ChatCoordinator implements AutoCloseable {
    private static final int MAX_UI_INHERITED_ITEMS = 500;
    private static final int MAX_UI_INHERITED_CHARS = 512_000;
    interface Host { void dispatch(JSONObject envelope); void openProviderEditor(ChatDatabase.Provider existing); void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing); default void openImagePicker(String sessionId) { } default void openExternalUrl(String url) { } default boolean setTurnRuntimeActive(String sessionId, boolean active) { return true; } void showMessage(String text); /** Native window chrome (system bars, window background) follows the resolved UI theme. */ default void applyAppearance(String resolvedTheme, String tone) { } }

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
    /** Turn stages (Router, checkpoint, Web, generation, verification) live in the pipeline; this class owns intents and views. */
    private final TurnPipeline pipeline;
    private final Map<String, ChatTransport.Turn> activeTurns = new ConcurrentHashMap<>();
    private final Map<String, TurnControl> turnControls = new ConcurrentHashMap<>();
    private final java.util.Set<String> startingSessions = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> preparingSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> webPreparingStages = new ConcurrentHashMap<>();
    private final Map<String, String> postProcessingStages = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStreamViewNanos = new ConcurrentHashMap<>();

    private volatile boolean closed;
    private volatile boolean uiReady;
    private long revision;
    private volatile String route;
    private volatile String activeSessionId;
    private String theme;
    private String tone;
    private boolean delight;
    private final java.util.function.BooleanSupplier systemDark;
    private String usageTodayKey = "";
    private JSONObject usageTodayCache;
    private boolean showReasoning;
    private boolean crossChatMemoryEnabled;
    private String defaultContextMode;
    private String defaultWebMode;
    private String standardSubagentMode;

    ChatCoordinator(MoyuChatApplication app, Host host) { this(app, host, app.secrets()); }

    /** JVM tests inject an in-memory secret store. */
    ChatCoordinator(MoyuChatApplication app, Host host, ChatSecretStore secrets) {
        this.db = app.database(); this.secrets = secrets; this.mediaStore = app.mediaStore(); this.host = host;
        this.systemDark = () -> (app.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        this.contexts = new ContextBuilder(db, mediaStore); this.snapshots = new SnapshotService(db);
        this.pipeline = new TurnPipeline(new PipelineEnv(), db, contexts, mediaStore, transports);
        // Recovery runs on the same single worker as every intent, so it is ordered before any send.
        worker.execute(() -> { db.recoverInterruptedSessions(); pruneOrphans(); emitView(); });
        this.route = validRoute(db.setting("route", "home"));
        this.activeSessionId = empty(db.setting("activeSessionId", ""));
        this.theme = validTheme(db.setting("theme", "system"));
        this.tone = validTone(db.setting("tone", "sage"));
        this.delight = !"false".equals(db.setting("delight", "true"));
        this.showReasoning = "true".equals(db.setting("showReasoning", "false"));
        this.crossChatMemoryEnabled = "true".equals(db.setting("crossChatMemoryEnabled", "false"));
        this.defaultContextMode = ContextMode.normalize(db.setting("defaultContextMode", ContextMode.STANDARD));
        this.defaultWebMode = ChatTurnOptions.normalizeWebMode(db.setting("defaultWebMode", ChatTurnOptions.WEB_AUTO));
        this.standardSubagentMode = HarnessPolicyResolver.normalizeSubagentMode(db.setting("standardSubagentMode", HarnessPolicyResolver.SUBAGENT_OFF));
    }

    /** Keys and images whose owners no longer exist (deleted rows, or a beta baseline reset) never outlive them. */
    private void pruneOrphans() {
        try {
            java.util.HashSet<String> providerIds = new java.util.HashSet<>();
            for (ChatDatabase.Provider p : db.listProviders()) providerIds.add(p.id);
            secrets.retainOnly(providerIds);
            mediaStore.pruneUnreferenced(db.referencedMediaIds());
        } catch (Exception error) { host.showMessage("startup cleanup failed: " + safe(error)); }
    }

    void postIntent(String raw) { if (raw == null || raw.length() > 1024 * 1024) return; worker.execute(() -> handle(raw)); }
    void refreshView() { worker.execute(this::emitView); }

    interface SaveCallback { void complete(String error); }

    void saveProvider(ChatDatabase.Provider provider, String apiKey) { saveProvider(provider, apiKey, null); }

    void saveProvider(ChatDatabase.Provider provider, String apiKey, SaveCallback completion) {
        worker.execute(() -> {
            String previousSecret = null;
            boolean secretWritten = false;
            try {
                if (provider == null || provider.id == null || provider.id.trim().isEmpty()) throw new IllegalArgumentException("provider id required");
                provider.id = bounded(provider.id, 128, "provider id");
                provider.displayName = bounded(provider.displayName, 128, "display name");
                // Provider is the shared account/connection boundary. Protocol/auth/reasoning wire are
                // model-deployment capabilities and are validated per Model Profile below.
                provider.baseUrl = ProviderEndpoint.normalize(provider.baseUrl);

                java.util.List<ChatDatabase.ModelProfile> affectedProfiles = db.listModelProfiles(provider.id);
                for (ChatDatabase.ModelProfile profile : affectedProfiles) {
                    try {
                        if (ModelCatalog.CONFIG_AUTO.equals(ModelCatalog.normalizeConfigMode(profile.configMode))) {
                            // Endpoint identity is part of deployment authority. Re-resolve Auto whenever the
                            // shared endpoint changes so stale official/native capabilities cannot follow it.
                            ModelCatalog.applyAuto(provider, profile,
                                    profile.contextTokens, profile.inputTokens, profile.maxOutputTokens);
                        }
                        ModelProfileValidator.validateResolvedProfile(provider, profile);
                        if (!transports.supports(profile.protocol)) throw new IllegalArgumentException("当前 Protocol 暂不受支持：" + profile.protocol);
                    } catch (IllegalArgumentException profileError) {
                        throw new IllegalArgumentException("Model “" + profile.modelId + "” 配置无效：" + profileError.getMessage());
                    }
                }

                String nextSecret = apiKey == null ? "" : apiKey.trim();
                boolean credentialChanged = !nextSecret.isEmpty();
                if (credentialChanged) {
                    previousSecret = secrets.get(provider.id);
                    secrets.put(provider.id, nextSecret);
                    secretWritten = true;
                }
                try {
                    db.saveProviderAndProfiles(provider, affectedProfiles, credentialChanged);
                } catch (Exception databaseError) {
                    if (secretWritten) {
                        try { secrets.put(provider.id, previousSecret == null ? "" : previousSecret); }
                        catch (Exception restoreError) { throw new IllegalStateException("provider save failed and previous credential could not be restored", databaseError); }
                    }
                    throw databaseError;
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
                    ModelCatalog.applyAuto(provider, profile,
                            profile.contextTokens, profile.inputTokens, profile.maxOutputTokens);
                }
                ChatDatabase.ModelProfile duplicate = db.findModelProfile(profile.providerId, profile.modelId);
                if (duplicate != null && !profile.id.equals(duplicate.id)) {
                    throw new IllegalArgumentException("此 Provider 已存在 Model ID “" + profile.modelId + "”。请编辑已有模型，或填写不同的 Model ID。");
                }
                ModelProfileValidator.validateResolvedProfile(provider, profile);
                if (!transports.supports(profile.protocol)) throw new IllegalArgumentException("当前 Protocol 暂不受支持：" + profile.protocol);
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
                case "appearance.set": {
                    if (!p.has("theme") && !p.has("tone")) throw new IllegalArgumentException("appearance.set requires theme or tone");
                    if (p.has("theme")) { theme = validTheme(required(p, "theme")); db.putSetting("theme", theme); }
                    if (p.has("tone")) { tone = validTone(required(p, "tone")); db.putSetting("tone", tone); }
                    break;
                }
                case "ui.delight.set": delight = p.optBoolean("enabled", true); db.putSetting("delight", delight ? "true" : "false"); break;
                case "chat.reasoning.visibility.set": showReasoning = p.optBoolean("show", false); db.putSetting("showReasoning", showReasoning ? "true" : "false"); break;
                case "memory.global.set": crossChatMemoryEnabled = p.optBoolean("enabled", false); db.putSetting("crossChatMemoryEnabled", crossChatMemoryEnabled ? "true" : "false"); break;
                case "context.mode.default.set": defaultContextMode = ContextMode.normalize(required(p, "mode")); db.putSetting("defaultContextMode", defaultContextMode); break;
                case "web.mode.default.set": defaultWebMode = ChatTurnOptions.normalizeWebMode(required(p, "mode")); db.putSetting("defaultWebMode", defaultWebMode); break;
                case "subagent.standard.default.set": standardSubagentMode = HarnessPolicyResolver.normalizeSubagentMode(required(p, "mode")); db.putSetting("standardSubagentMode", standardSubagentMode); break;
                case "external.open": host.openExternalUrl(required(p, "url")); break;
                // v0.0.2 migration compatibility for an older UI bundle: off -> minimal, on -> standard.
                case "nav.open": route = validRoute(required(p, "route")); db.putSetting("route", route); break;
                case "chat.session.create": createSession(p); break;
                case "chat.session.open": openSession(required(p, "localSessionId")); break;
                case "chat.session.rename": renameSession(required(p, "localSessionId"), required(p, "title")); break;
                case "chat.session.model.set": setSessionModel(required(p, "localSessionId"), required(p, "modelProfileId")); break;
                case "chat.session.reasoning.set": setSessionReasoning(required(p, "localSessionId"), required(p, "reasoningEffort")); break;
                case "chat.session.web.set": setSessionWebMode(required(p, "localSessionId"), required(p, "webMode")); break;
                case "chat.session.send": {
                    final String sessionId = required(p, "localSessionId");
                    final String message = p.optString("text", "");
                    if (sessionBusy(sessionId)) {
                        if (!message.trim().isEmpty()) db.setDraft(sessionId, message);
                        throw new IllegalStateException("session busy; message kept as next draft");
                    }
                    if (message.trim().isEmpty() && db.draftMedia(sessionId).length() == 0) break;
                    // Send is self-contained with respect to the visible turn setting. A preceding
                    // reasoning.set bridge message is still persisted normally, but Send must not rely
                    // on cross-bridge scheduling order to know what the user selected for this turn.
                    if (p.has("reasoningEffort")) setSessionReasoning(sessionId, p.optString("reasoningEffort", ReasoningPolicy.DEFAULT));
                    if (p.has("webMode")) setSessionWebMode(sessionId, p.optString("webMode", ChatTurnOptions.WEB_AUTO));
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
                                pipeline.run(sessionId, message, turnConfig, turnControl);
                                result(asyncRequestId, true, null);
                            } catch (Exception error) {
                                // Last-resort durable terminal guard: if beginTurn() already committed a live row,
                                // no escaping preparation exception may leave the DB stuck in running/streaming.
                                pipeline.persistFinalUsage(sessionId, turnControl);
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
                case "chat.attachment.remove": {
                    db.removeDraftMedia(required(p, "localSessionId"), required(p, "mediaId"));
                    mediaStore.pruneUnreferenced(db.referencedMediaIds());
                    break;
                }
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
                case "verification.feedback": recordVerificationFeedback(required(p, "localSessionId"), requiredSeq(p), required(p, "label")); break;
                case "branch.create.local": createLocalBranch(required(p, "localSessionId"), p.has("throughSeq") ? p.optInt("throughSeq") : null); break;
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
        session.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
        session.subagentMode = ContextMode.MINIMAL.equals(ContextMode.normalize(session.contextMode)) ? HarnessPolicyResolver.SUBAGENT_OFF
                : ContextMode.SMART.equals(ContextMode.normalize(session.contextMode)) ? HarnessPolicyResolver.SUBAGENT_AUTO
                : standardSubagentMode;
        ReasoningPolicy.validateLevelsForProvider(ChatDatabase.deploymentProvider(provider, session), session.reasoningLevels);
        String previousEffort = ReasoningPolicy.normalizeEffort(session.reasoningEffort);
        session.reasoningEffort = ReasoningPolicy.allows(session.reasoningLevels, previousEffort) ? previousEffort : ReasoningPolicy.DEFAULT;
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** Reasoning effort is a durable future-turn setting and is validated against the active model profile. */
    private void setSessionReasoning(String sessionId, String effort) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("wait for the current turn before changing reasoning effort");
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
        ChatDatabase.Provider provider = db.getProvider(session.providerId);
        if (provider == null) throw new IllegalStateException("provider not found");
        String normalized = ReasoningPolicy.normalizeEffort(effort);
        ReasoningPolicy.validateEffortForProvider(ChatDatabase.deploymentProvider(provider, session), session.reasoningLevels, normalized);
        session.reasoningEffort = normalized;
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** Web mode is a durable future-turn session setting; it never changes model capability declarations. */
    private void setSessionWebMode(String sessionId, String webMode) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("wait for the current turn before changing Web mode");
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("session not found");
        session.webMode = ChatTurnOptions.normalizeWebMode(webMode);
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** System back follows the same route state as UI navigation; only home exits the Activity. */
    void handleSystemBack(Runnable exitApp) {
        worker.execute(() -> {
            if (!"home".equals(route)) {
                route = ("providers".equals(route) || "settings".equals(route)) && activeSessionId != null && db.getSession(activeSessionId) != null ? "conversation" : "home";
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
        ReasoningPolicy.validateEffortForProvider(provider, session.reasoningLevels, session.reasoningEffort);
        return new TurnConfig(provider, key, ProviderContinuationIdentity.fingerprint(db, provider, session), session, profile);
    }

    private boolean sessionBusy(String id) {
        return turnControls.containsKey(id) || activeTurns.containsKey(id) || startingSessions.contains(id)
                || preparingSessions.contains(id) || webPreparingStages.containsKey(id) || postProcessingStages.containsKey(id);
    }

    private void saveDraft(String id, String text) {
        if (db.getSession(id) == null) throw new IllegalArgumentException("session not found");
        // A running turn may coexist with the user's next-turn draft. Frontend draft generations
        // suppress stale pre-send debounce writes, so Native must not discard legitimate typing here.
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

    /** Trace of the session's last finished turn (stages run / skipped and why, durations, helper usage). */
    TurnTrace lastTrace(String sessionId) { return pipeline.lastTrace(sessionId); }

    /** The pipeline's view of coordinator runtime: executors, busy flags and view emission. */
    private final class PipelineEnv implements TurnPipeline.Env {
        @Override public ExecutorService authority() { return worker; }
        @Override public ExecutorService preparation() { return preparationWorker; }
        @Override public ExecutorService clientTools() { return clientToolWorker; }
        @Override public ScheduledExecutorService projections() { return streamProjectionScheduler; }
        @Override public boolean isClosed() { return closed; }
        @Override public void showMessage(String text) { host.showMessage(text); }
        @Override public void emitView() { ChatCoordinator.this.emitView(); }
        @Override public void emitStreamingView(String sessionId) { ChatCoordinator.this.emitStreamingView(sessionId); }
        @Override public void emitStreamingPatch(String sessionId, JSONObject liveItem) { ChatCoordinator.this.emitStreamingPatch(sessionId, liveItem); }
        @Override public void setStarting(String sessionId, boolean starting) { if (starting) startingSessions.add(sessionId); else startingSessions.remove(sessionId); }
        @Override public void setPreparing(String sessionId, boolean preparing) { if (preparing) preparingSessions.add(sessionId); else preparingSessions.remove(sessionId); }
        @Override public void setWebStage(String sessionId, String stage) { webPreparingStages.put(sessionId, stage); }
        @Override public boolean clearWebStage(String sessionId) { return webPreparingStages.remove(sessionId) != null; }
        @Override public void setPostStage(String sessionId, String stage) { postProcessingStages.put(sessionId, stage); }
        @Override public boolean hasActiveTurn(String sessionId) { return activeTurns.containsKey(sessionId); }
        @Override public void putActiveTurn(String sessionId, ChatTransport.Turn turn) { activeTurns.put(sessionId, turn); }
        @Override public ChatTransport.Turn removeActiveTurn(String sessionId) { return activeTurns.remove(sessionId); }
        @Override public boolean ownsTurn(String sessionId, TurnControl control) { return turnControls.get(sessionId) == control; }
        @Override public void releaseTurn(String id) {
            activeTurns.remove(id);
            turnControls.remove(id);
            startingSessions.remove(id);
            preparingSessions.remove(id);
            webPreparingStages.remove(id);
            postProcessingStages.remove(id);
            lastStreamViewNanos.remove(id);
            host.setTurnRuntimeActive(id, false);
            ChatCoordinator.this.emitView();
        }
    }

    private void interrupt(String id) {
        TurnControl control = turnControls.get(id);
        if (control != null) control.cancel();
        ChatTransport.Turn turn = activeTurns.get(id);
        if (turn != null) try { turn.cancel(); } catch (Exception ignored) { }
        emitStreamingView(id);
    }

    private void createLocalBranch(String sourceSessionId, Integer throughSeq) throws Exception {
        ChatDatabase.Session source = db.getSession(sourceSessionId); if (source == null) throw new IllegalArgumentException("source session not found");
        if (sessionBusy(sourceSessionId)) throw new IllegalStateException("wait for the current turn before branching");
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

    private void recordVerificationFeedback(String sessionId, int localSeq, String label) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("wait for the current turn before rating verification");
        String normalized = label == null ? "" : label.trim().toLowerCase(java.util.Locale.ROOT);
        if (!("accurate".equals(normalized) || "false_alarm".equals(normalized) || "missed_error".equals(normalized))) {
            throw new IllegalArgumentException("unsupported verification feedback label");
        }
        db.verificationForFeedback(sessionId, localSeq, normalized);
        host.showMessage("校验反馈已记录");
    }

    private static String effectiveWebBackend(ChatDatabase.Session session, ChatDatabase.Provider provider) {
        if (session == null || ChatTurnOptions.WEB_OFF.equals(ChatTurnOptions.normalizeWebMode(session.webMode))) return "off";
        ChatTurnOptions nativeOptions = ChatTurnOptions.forSession(session, provider == null ? "" : provider.protocol);
        boolean nativeSearch = nativeOptions.supports(ChatTurnOptions.WEB_SEARCH);
        boolean nativeFetch = nativeOptions.supports(ChatTurnOptions.WEB_FETCH);
        if (nativeSearch && nativeFetch) return TurnPipeline.providerWebBackend(provider == null ? "" : provider.protocol);
        if (nativeSearch || nativeFetch) return TurnPipeline.providerWebBackend(provider == null ? "" : provider.protocol) + " + Moyu fallback";
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
            String resolvedTheme = resolvedTheme();
            host.applyAppearance(resolvedTheme, tone);
            JSONObject view = new JSONObject().put("route", route).put("now", Instant.now().toString())
                    .put("appearance", new JSONObject().put("theme", theme).put("resolvedTheme", resolvedTheme).put("tone", tone).put("delight", delight));
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
                String defaultModel = models.length() > 0 ? models.optJSONObject(0).optString("modelId", "") : "";
                providers.put(new JSONObject().put("providerId", p.id).put("displayName", p.displayName).put("baseUrl", p.baseUrl)
                        .put("model", defaultModel).put("hasCredential", secrets.has(p.id))
                        .put("configured", secrets.has(p.id) && models.length() > 0).put("models", models));
            }
            view.put("providers", providers);
            java.util.List<ChatDatabase.Session> sessionRows = db.listSessions();
            JSONArray sessions = new JSONArray(); for (ChatDatabase.Session s : sessionRows) sessions.put(sessionJson(s, false)); view.put("sessions", sessions);
            view.put("usageToday", usageToday(sessionRows));
            if (activeSessionId != null) {
                ChatDatabase.Session active = db.getSession(activeSessionId);
                if (active != null) {
                    view.put("activeLocalSessionId", activeSessionId);
                    // Route-scoped projection: non-conversation screens never pay to serialize/bridge the full timeline.
                    if ("conversation".equals(route)) view.put("activeSession", sessionJson(active, true));
                }
            }
            view.put("ui", new JSONObject().put("pendingRequestIds", new JSONArray()).put("showReasoning", showReasoning)
                    .put("crossChatMemoryEnabled", crossChatMemoryEnabled)
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
                .put("reasoningLevels", ReasoningPolicy.normalizeLevels(s.reasoningLevels))
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
        if (originSnap != null) o.put("origin", new JSONObject().put("snapshotId", originSnap.id).put("sourceSessionId", originSnap.sourceSessionId).put("sourceTitle", originSnap.sourceTitle).put("createdAt", originSnap.createdAt).put("itemCount", originSnap.itemCount).put("sourceItemCount", originSnap.sourceItemCount).put("truncated", originSnap.truncated));
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
    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String empty(String v) { return v == null || v.isEmpty() ? null : v; }
    private static String bounded(String value, int max, String label) { String v = value == null ? "" : value.trim(); if (v.isEmpty()) throw new IllegalArgumentException(label + " required"); if (v.length() > max) throw new IllegalArgumentException(label + " too long"); return v; }
    private static String validRoute(String value) { String v = value == null ? "" : value.trim(); if (!("home".equals(v) || "conversation".equals(v) || "providers".equals(v) || "settings".equals(v))) return "home"; return v; }
    /** "system" follows the Android night mode; the WebView never has to guess from prefers-color-scheme. */
    private String resolvedTheme() {
        if (!"system".equals(theme)) return theme;
        try { return systemDark.getAsBoolean() ? "dark" : "light"; } catch (RuntimeException error) { return "light"; }
    }

    /**
     * Local-day usage across all sessions, recomputed only when a session changes or the day rolls over.
     * Reads only rows of sessions touched today through the session index; never scans the whole timeline.
     */
    private JSONObject usageToday(java.util.List<ChatDatabase.Session> sessionRows) throws Exception {
        String since = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toString();
        String latest = "";
        for (ChatDatabase.Session s : sessionRows) if (s.updatedAt != null && s.updatedAt.compareTo(latest) > 0) latest = s.updatedAt;
        String key = since + "|" + latest + "|" + sessionRows.size();
        if (key.equals(usageTodayKey) && usageTodayCache != null) return usageTodayCache;
        long turns = 0, input = 0, output = 0, cached = 0, cacheBase = 0;
        JSONArray rows = db.usageSince(since);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject u = rows.optJSONObject(i); if (u == null) continue;
            turns++;
            input += Math.max(0L, u.optLong("totalInputTokens", u.optLong("inputTokens", 0L)));
            output += Math.max(0L, u.optLong("totalOutputTokens", u.optLong("outputTokens", 0L)));
            cached += Math.max(0L, u.optLong("cachedInputTokens", 0L));
            cacheBase += Math.max(0L, u.optLong("cacheHitBaseTokens", 0L));
        }
        usageTodayCache = new JSONObject().put("since", since).put("turns", turns).put("inputTokens", input).put("outputTokens", output)
                .put("cachedInputTokens", cached).put("cacheHitBaseTokens", cacheBase);
        usageTodayKey = key;
        return usageTodayCache;
    }

    private static String validTone(String value) { String v = value == null ? "" : value.trim(); return "warm".equals(v) || "neutral".equals(v) ? v : "sage"; }

    private static String validTheme(String value) { String v = value == null ? "" : value.trim(); if (!("system".equals(v) || "light".equals(v) || "dark".equals(v))) return "system"; return v; }

    @Override public void close() { closed = true; for (TurnControl control : turnControls.values()) try { control.cancel(); } catch (Exception ignored) { } turnControls.clear(); for (ChatTransport.Turn t : activeTurns.values()) try { t.cancel(); } catch (Exception ignored) { } activeTurns.clear(); startingSessions.clear(); preparingSessions.clear(); webPreparingStages.clear(); postProcessingStages.clear(); lastStreamViewNanos.clear(); pipeline.webService.close(); transports.close(); streamProjectionScheduler.shutdownNow(); preparationWorker.shutdownNow(); clientToolWorker.shutdownNow(); worker.shutdownNow(); }
}
