package com.moyu.chat;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native-only domain coordinator. UI knows intents/view models; provider keys never cross the bridge. */
final class ChatCoordinator implements AutoCloseable {
    interface Host { void dispatch(JSONObject envelope); void openProviderEditor(ChatDatabase.Provider existing); void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing); default void openImagePicker(String sessionId) { } default void openExternalUrl(String url) { } void showMessage(String text); }

    private final ChatDatabase db;
    private final ChatSecretStore secrets;
    private final ChatMediaStore mediaStore;
    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ChatTransportRegistry transports = new ChatTransportRegistry();
    private final ContextBuilder contexts;
    private final SnapshotService snapshots;
    private final TaskRouter taskRouter = new TaskRouter();
    private final ContextCheckpointService checkpointService = new ContextCheckpointService();
    private final MoyuWebService webService = new MoyuWebService();
    private final RemoteDataBridge remoteBridge;
    private final Map<String, ChatTransport.Turn> activeTurns = new HashMap<>();
    private final java.util.Set<String> preparingSessions = new java.util.HashSet<>();
    private final Map<String, String> webPreparingStages = new HashMap<>();

    private volatile boolean closed;
    private boolean uiReady;
    private long revision;
    private String route;
    private String activeSessionId;
    private String theme;
    private boolean showReasoning;
    private String defaultContextMode;
    private String defaultWebMode;
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
        String legacySmart = db.setting("smartContextEnabled", "true");
        this.defaultContextMode = ContextMode.normalize(db.setting("defaultContextMode", "false".equals(legacySmart) ? ContextMode.MINIMAL : ContextMode.AUTO));
        this.defaultWebMode = ChatTurnOptions.normalizeWebMode(db.setting("defaultWebMode", ChatTurnOptions.WEB_AUTO));
    }

    void postIntent(String raw) { if (raw == null || raw.length() > 1024 * 1024) return; worker.execute(() -> handle(raw)); }

    void saveProvider(ChatDatabase.Provider provider, String apiKey) {
        worker.execute(() -> {
            try {
                if (provider == null || provider.id == null || provider.id.trim().isEmpty()) throw new IllegalArgumentException("provider id required");
                provider.id = bounded(provider.id, 128, "provider id");
                provider.displayName = bounded(provider.displayName, 128, "display name");
                provider.protocol = bounded(provider.protocol, 64, "protocol");
                if (!transports.supports(provider.protocol)) throw new IllegalArgumentException("unsupported protocol");
                provider.authMode = ProviderAuth.normalize(provider.authMode, provider.protocol);
                provider.reasoningWire = ReasoningPolicy.normalizeWire(provider.reasoningWire, provider.protocol);
                provider.baseUrl = ProviderEndpoint.normalize(provider.baseUrl);
                provider.model = bounded(provider.model, 256, "model");
                if (provider.contextTokens < 2048 || provider.contextTokens > 4_000_000) throw new IllegalArgumentException("context tokens out of range");
                if (provider.inputTokens < 0 || provider.inputTokens > provider.contextTokens) throw new IllegalArgumentException("input tokens out of range");
                if (provider.maxOutputTokens < 256 || provider.maxOutputTokens >= provider.contextTokens) throw new IllegalArgumentException("max output tokens out of range");
                db.saveProvider(provider);
                if (apiKey != null && !apiKey.trim().isEmpty()) secrets.put(provider.id, apiKey.trim());
                emitView();
            } catch (Exception error) { host.showMessage(safe(error)); }
        });
    }

    void saveModelProfile(ChatDatabase.ModelProfile profile) {
        worker.execute(() -> {
            try {
                if (profile == null || db.getProvider(profile.providerId) == null) throw new IllegalArgumentException("provider not found");
                profile.id = bounded(profile.id, 160, "model profile id");
                profile.providerId = bounded(profile.providerId, 128, "provider id");
                profile.modelId = bounded(profile.modelId, 256, "model id");
                profile.displayName = bounded(profile.displayName == null || profile.displayName.trim().isEmpty() ? profile.modelId : profile.displayName, 128, "model display name");
                profile.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
                profile.defaultReasoning = ReasoningPolicy.normalizeEffort(profile.defaultReasoning);
                profile.inputModalities = ChatDatabase.normalizeModalities(profile.inputModalities);
                profile.outputModalities = ChatDatabase.normalizeModalities(profile.outputModalities);
                profile.toolCapabilities = ChatTurnOptions.normalizeCapabilities(profile.toolCapabilities);
                if (!ReasoningPolicy.allows(profile.reasoningLevels, profile.defaultReasoning)) throw new IllegalArgumentException("default reasoning not supported by model profile");
                db.saveModelProfile(profile); emitView();
            } catch (Exception error) { host.showMessage(safe(error)); }
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
                case "context.mode.default.set": defaultContextMode = ContextMode.normalize(required(p, "mode")); db.putSetting("defaultContextMode", defaultContextMode); break;
                case "web.mode.default.set": defaultWebMode = ChatTurnOptions.normalizeWebMode(required(p, "mode")); db.putSetting("defaultWebMode", defaultWebMode); break;
                case "external.open": host.openExternalUrl(required(p, "url")); break;
                // v0.0.2 migration compatibility for an older UI bundle: off -> minimal, on -> auto.
                case "context.intelligence.set": defaultContextMode = p.optBoolean("enabled", true) ? ContextMode.AUTO : ContextMode.MINIMAL; db.putSetting("defaultContextMode", defaultContextMode); break;
                case "nav.open": route = validRoute(required(p, "route")); db.putSetting("route", route); break;
                case "chat.session.create": createSession(p); break;
                case "chat.session.open": openSession(required(p, "localSessionId")); break;
                case "chat.session.send": send(required(p, "localSessionId"), p.optString("text", "")); break;
                case "chat.session.saveDraft": db.setDraft(required(p, "localSessionId"), p.optString("text", "")); break;
                case "chat.attachment.pick": requestImagePicker(required(p, "localSessionId")); break;
                case "chat.attachment.remove": db.removeDraftMedia(required(p, "localSessionId"), required(p, "mediaId")); break;
                case "chat.session.interrupt": interrupt(required(p, "localSessionId")); break;
                case "chat.session.delete": deleteSession(required(p, "localSessionId")); break;
                case "chat.message.edit": editMessageBranch(required(p, "localSessionId"), requiredSeq(p)); break;
                case "chat.message.rollback": rollbackMessageBranch(required(p, "localSessionId"), requiredSeq(p)); break;
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
        String effort = p.optString("reasoningEffort", profile.defaultReasoning);
        String contextMode = ContextMode.normalize(p.optString("contextMode", defaultContextMode));
        String webMode = ChatTurnOptions.normalizeWebMode(p.optString("webMode", defaultWebMode));
        ChatDatabase.Session s = db.createSession(profile, title, null, null, effort, contextMode, webMode);
        activate(s.id);
    }

    private void openSession(String id) { if (db.getSession(id) == null) throw new IllegalArgumentException("session not found"); activate(id); }
    private void activate(String id) { activeSessionId = id; db.putSetting("activeSessionId", id); route = "conversation"; db.putSetting("route", route); }

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

    private void deleteSession(String id) {
        if (preparingSessions.contains(id) || webPreparingStages.containsKey(id)) throw new IllegalStateException("wait for preparation before deleting this session");
        if (activeTurns.containsKey(id)) throw new IllegalStateException("interrupt the current turn before deleting this session");
        if (db.getSession(id) == null) throw new IllegalArgumentException("session not found");
        db.deleteSession(id);
        mediaStore.pruneUnreferenced(db.referencedMediaIds());
        if (id.equals(activeSessionId)) { activeSessionId = null; db.putSetting("activeSessionId", ""); route = "home"; db.putSetting("route", route); }
    }

    private void send(String id, String text) throws Exception {
        // trim is an emptiness check only; the stored/prompted/sent text stays byte-for-byte original.
        String input = text == null ? "" : text;
        ChatDatabase.Session session = db.getSession(id); if (session == null) throw new IllegalArgumentException("session not found");
        JSONArray pendingMedia = db.draftMedia(id);
        if (input.trim().isEmpty() && pendingMedia.length() == 0) return;
        if (pendingMedia.length() > 0 && !ChatDatabase.supportsModality(session.inputModalities, "image")) throw new IllegalStateException("this session model does not support image input");
        if (activeTurns.containsKey(id)) throw new IllegalStateException("session busy");
        ChatDatabase.Provider provider = db.getProvider(session.providerId); String key = secrets.get(session.providerId);
        if (provider == null || key == null) throw new IllegalStateException("provider credential missing");
        ChatTransport transport = transports.forProtocol(provider.protocol); if (transport == null) throw new IllegalStateException("provider protocol unsupported");

        // Preflight the exact text+image prompt before mutating durable chat history. Session owns resolved capabilities.
        CanonicalPrompt prompt = contexts.build(session, session.contextTokens, session.inputTokens, session.maxOutputTokens, input, pendingMedia);
        ChatDatabase.Snapshot originSnapshot = session.originSnapshotId == null ? null : db.getSnapshot(session.originSnapshotId);
        ContextMode.Policy contextPolicy = ContextMode.resolve(session, originSnapshot);
        TaskProfile profile = TaskProfile.fromStored(session.taskProfile);
        boolean needsRouter = contextPolicy.routerEnabled && profile == null;
        boolean checkpointAllowed = contextPolicy.checkpointEnabled && (profile == null || !"recent".equals(profile.contextPolicy));
        boolean needsCheckpoint = checkpointAllowed && prompt.truncated && prompt.omittedItemCount > session.checkpointCoveredItems;
        boolean preparing = needsRouter || needsCheckpoint;
        if (preparing) { preparingSessions.add(id); emitView(); }
        try {
            if (needsRouter) {
                TaskProfile routed = taskRouter.route(transport, provider, key, session.model, prompt);
                profile = routed != null ? routed : new TaskProfile("general", "answer", "standard", "direct", "checkpoint_recent", new JSONArray(), 0d);
                session.taskProfile = profile.toJson().toString();
                db.saveSession(session);
                prompt = contexts.build(session, session.contextTokens, session.inputTokens, session.maxOutputTokens, input, pendingMedia);
                checkpointAllowed = contextPolicy.checkpointEnabled && !"recent".equals(profile.contextPolicy);
                needsCheckpoint = checkpointAllowed && prompt.truncated && prompt.omittedItemCount > session.checkpointCoveredItems;
            }
            if (needsCheckpoint) {
                ContextCheckpointService.Update update = checkpointService.update(transport, provider, key, session.model, prompt,
                        ContextCheckpoint.fromStored(session.contextCheckpoint), session.checkpointCoveredItems);
                if (update != null) {
                    session.contextCheckpoint = update.checkpoint.toJson().toString();
                    session.checkpointCoveredItems = update.coveredItems;
                    db.saveSession(session);
                    prompt = contexts.build(session, session.contextTokens, session.inputTokens, session.maxOutputTokens, input, pendingMedia);
                }
            }
        } finally {
            if (preparing) { preparingSessions.remove(id); emitView(); }
        }

        // Web is a separate capability plane. Hidden Router/Checkpoint calls above never receive it.
        ChatTurnOptions webOptions = ChatTurnOptions.forSession(session, provider.protocol);
        MoyuWebService.Prepared preparedWeb = MoyuWebService.Prepared.none();
        if (webOptions.webEnabled()) {
            try {
                preparedWeb = webService.prepare(input, webOptions, stage -> {
                    webPreparingStages.put(id, stage == null ? "web_search" : stage);
                    emitView();
                });
            } finally {
                if (webPreparingStages.remove(id) != null) emitView();
            }
            if (preparedWeb.hasContext()) {
                // Rebuild only the disposable main prompt so Web material is budgeted but never persisted as user text.
                prompt = contexts.build(session, session.contextTokens, session.inputTokens, session.maxOutputTokens,
                        input + preparedWeb.promptSuffix, pendingMedia);
            }
        }
        final MoyuWebService.Prepared webPrepared = preparedWeb;
        provider.maxOutputTokens = session.maxOutputTokens; // request-local provider copy; transport only needs the wire output cap.
        JSONObject user = new JSONObject().put("kind", "message").put("role", "user").put("text", input).put("createdAt", Instant.now().toString());
        if (pendingMedia.length() > 0) user.put("attachments", new JSONArray(pendingMedia.toString()));
        LinkedHashMap<String, JSONObject> webSources = new LinkedHashMap<>();
        for (int i = 0; i < webPrepared.sources.length(); i++) {
            JSONObject source = webPrepared.sources.optJSONObject(i); if (source == null) continue;
            String url = source.optString("url", ""); if (!url.isEmpty()) webSources.put(url, new JSONObject(source.toString()));
        }
        String[] webBackend = new String[]{webPrepared.backend};
        String streamKey = "assistant:live:" + id + ":" + UUID.randomUUID();
        JSONObject live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "").put("streaming", true).put("streamStage", "connecting").put("createdAt", Instant.now().toString());
        if (!webSources.isEmpty()) live.put("sources", sourceArray(webSources));
        if (!webBackend[0].isEmpty()) live.put("webBackend", webBackend[0]);
        db.beginTurn(id, "user:" + UUID.randomUUID(), user, streamKey, live);
        final long requestStartedNanos = System.nanoTime();
        final long[] firstOutputNanos = new long[]{0L};
        StringBuilder textOut = new StringBuilder();
        StringBuilder reasoningOut = new StringBuilder();
        String[] streamStage = new String[]{"connecting"};
        JSONArray outputMedia = new JSONArray();
        final boolean allowImageOutput = ChatDatabase.supportsModality(session.outputModalities, "image");
        String usageKey = "usage:turn:" + UUID.randomUUID();
        JSONObject usageState = new JSONObject().put("kind", "usage").put("createdAt", Instant.now().toString()).put("context", prompt.diagnostics());
        boolean[] usagePersisted = new boolean[]{false};
        ChatTransport.Sink sink = new ChatTransport.Sink() {
            private JSONObject liveProjection() throws Exception {
                JSONObject next = new JSONObject(live.toString()).put("text", textOut.toString()).put("streaming", true).put("streamStage", streamStage[0]);
                if (reasoningOut.length() > 0) next.put("reasoning", reasoningOut.toString());
                if (outputMedia.length() > 0) next.put("attachments", outputMedia);
                if (!webSources.isEmpty()) next.put("sources", sourceArray(webSources)); else next.remove("sources");
                if (!webBackend[0].isEmpty()) next.put("webBackend", webBackend[0]); else next.remove("webBackend");
                return next;
            }
            @Override public void onStage(String stage) { worker.execute(() -> { try { if (stage == null || stage.trim().isEmpty()) return; streamStage[0] = stage.trim(); db.replaceTimelineBody(streamKey, liveProjection()); emitView(); } catch (Exception ignored) { } }); }
            @Override public void onReasoningDelta(String delta) { if (delta == null || delta.isEmpty()) return; worker.execute(() -> { try { streamStage[0] = "reasoning"; reasoningOut.append(delta); db.replaceTimelineBody(streamKey, liveProjection()); emitView(); } catch (Exception ignored) { } }); }
            @Override public void onTextDelta(String delta) { if (delta == null || delta.isEmpty()) return; worker.execute(() -> { try { if (firstOutputNanos[0] == 0L) firstOutputNanos[0] = System.nanoTime(); streamStage[0] = "output"; textOut.append(delta); db.replaceTimelineBody(streamKey, liveProjection()); emitView(); } catch (Exception ignored) { } }); }
            @Override public void onImage(String mimeType, String base64, String fileName) { if (!allowImageOutput) return; worker.execute(() -> { try { if (firstOutputNanos[0] == 0L) firstOutputNanos[0] = System.nanoTime(); streamStage[0] = "output"; JSONObject image = mediaStore.saveBase64Image(mimeType, base64, fileName); outputMedia.put(image); db.replaceTimelineBody(streamKey, liveProjection()); emitView(); } catch (Exception error) { host.showMessage("image output ignored: " + safe(error)); } }); }
            @Override public void onToolActivity(String tool, String state) { worker.execute(() -> { try {
                if (ChatTurnOptions.WEB_SEARCH.equals(tool) || ChatTurnOptions.WEB_FETCH.equals(tool)) {
                    streamStage[0] = tool;
                    if (webBackend[0].isEmpty() || "unavailable".equals(webBackend[0])) webBackend[0] = providerWebBackend(provider.protocol);
                    db.replaceTimelineBody(streamKey, liveProjection()); emitView();
                }
            } catch (Exception ignored) { } }); }
            @Override public void onWebSource(String url, String title) { worker.execute(() -> { try {
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
                if (!webSources.containsKey(url) && webSources.size() < 16) webSources.put(url, new JSONObject()
                        .put("url", url).put("title", title == null || title.trim().isEmpty() ? url : title.trim())
                        .put("backend", providerWebBackend(provider.protocol)));
                if (webBackend[0].isEmpty() || "unavailable".equals(webBackend[0])) webBackend[0] = providerWebBackend(provider.protocol);
                db.replaceTimelineBody(streamKey, liveProjection()); emitView();
            } catch (Exception ignored) { } }); }
            @Override public void onUsage(Integer in, Integer out) { onUsageDetails(in, out, null, null, null, null); }
            @Override public void onUsageDetails(Integer in, Integer out, Integer cached, Integer cacheWrite, Integer cacheBase, Integer reasoningTokens) { worker.execute(() -> { try {
                if (in != null) usageState.put("inputTokens", Math.max(0, in));
                if (out != null) usageState.put("outputTokens", Math.max(0, out));
                if (cached != null) usageState.put("cachedInputTokens", Math.max(0, cached));
                if (cacheWrite != null) usageState.put("cacheWriteInputTokens", Math.max(0, cacheWrite));
                if (cacheBase != null) usageState.put("cacheHitBaseTokens", Math.max(0, cacheBase));
                if (reasoningTokens != null) usageState.put("reasoningTokens", Math.max(0, reasoningTokens));
                if (usagePersisted[0]) db.replaceTimelineBody(usageKey, usageState); else { db.appendTimeline(id, usageKey, usageState); usagePersisted[0] = true; }
            } catch (Exception ignored) { } }); }
            @Override public void onCompleted() { worker.execute(() -> { settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]); finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0], null); }); }
            @Override public void onError(String code, String summary, boolean retryable) { worker.execute(() -> { settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]); finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0], new String[]{code, summary}); }); }
        };
        ChatTransport.Turn turn;
        try {
            turn = transport.stream(provider, key, session.model, session.reasoningEffort, prompt, webOptions, sink);
        } catch (Exception startError) {
            settleUsage(id, usageKey, usageState, usagePersisted, requestStartedNanos, firstOutputNanos[0]);
            finishTurn(id, streamKey, live, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0],
                    new String[]{"transport_start_failed", safe(startError)});
            return;
        }
        activeTurns.put(id, turn); emitView();
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
            emitView();
        }
    }

    private void interrupt(String id) { ChatTransport.Turn turn = activeTurns.get(id); if (turn != null) turn.cancel(); }

    private void refreshSources() throws Exception { RemoteDataBridge.Status status = remoteBridge.status(); remoteSources = status.available ? remoteBridge.listSources() : new JSONArray(); }

    private void createLocalBranch(String sourceSessionId, Integer throughSeq) throws Exception {
        ChatDatabase.Session source = db.getSession(sourceSessionId); if (source == null) throw new IllegalArgumentException("source session not found");
        if (activeTurns.containsKey(sourceSessionId)) throw new IllegalStateException("interrupt the current turn before branching");
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(sourceSessionId, throughSeq);
        ChatDatabase.Session branch = db.branchSession(source, "分支 · " + source.title, snapshot.id, sourceSessionId);
        activate(branch.id);
    }

    /**
     * Standard chat-style edit without destructive history mutation: fork immediately BEFORE the
     * selected user message, then preload that message into the new branch composer for editing.
     */
    private void editMessageBranch(String sourceSessionId, int localSeq) throws Exception {
        ChatDatabase.Session source = db.getSession(sourceSessionId); if (source == null) throw new IllegalArgumentException("source session not found");
        if (activeTurns.containsKey(sourceSessionId)) throw new IllegalStateException("interrupt the current turn before editing history");
        JSONObject selected = messageAt(sourceSessionId, localSeq);
        if (!"user".equals(selected.optString("role"))) throw new IllegalArgumentException("only user messages can be edited");
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(sourceSessionId, Math.max(0, localSeq - 1));
        ChatDatabase.Session branch = db.branchSession(source, "编辑 · " + source.title, snapshot.id, sourceSessionId);
        db.setDraft(branch.id, selected.optString("text", ""));
        JSONArray attachments = selected.optJSONArray("attachments");
        if (attachments != null) for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i); if (item != null) db.addDraftMedia(branch.id, new JSONObject(item.toString()));
        }
        activate(branch.id);
    }

    /** Fork THROUGH the selected visible message so the original transcript remains reversible. */
    private void rollbackMessageBranch(String sourceSessionId, int localSeq) throws Exception {
        ChatDatabase.Session source = db.getSession(sourceSessionId); if (source == null) throw new IllegalArgumentException("source session not found");
        if (activeTurns.containsKey(sourceSessionId)) throw new IllegalStateException("interrupt the current turn before rolling back");
        messageAt(sourceSessionId, localSeq); // validates that the target is a finalized visible message
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(sourceSessionId, localSeq);
        ChatDatabase.Session branch = db.branchSession(source, "回滚 · " + source.title, snapshot.id, sourceSessionId);
        activate(branch.id);
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
        ChatDatabase.Session branch = db.createSession(profile, "分支 · " + snapshot.sourceTitle, snapshot.id, null, profile.defaultReasoning, contextMode, webMode);
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

    private static JSONArray sourceArray(Map<String, JSONObject> sources) {
        JSONArray out = new JSONArray();
        if (sources == null) return out;
        for (JSONObject source : sources.values()) if (source != null) out.put(source);
        return out;
    }

    private static String providerWebBackend(String protocol) {
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return "OpenAI hosted Web";
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return "Anthropic hosted Web";
        return "Provider hosted Web";
    }

    private void emitView() {
        if (closed || !uiReady) return;
        try {
            JSONObject view = new JSONObject().put("route", route).put("now", Instant.now().toString()).put("appearance", new JSONObject().put("theme", theme).put("resolvedTheme", "dark".equals(theme) ? "dark" : "light"));
            JSONArray providers = new JSONArray();
            for (ChatDatabase.Provider p : db.listProviders()) {
                JSONArray models = new JSONArray();
                for (ChatDatabase.ModelProfile m : db.listModelProfiles(p.id)) models.put(new JSONObject()
                        .put("modelProfileId", m.id).put("modelId", m.modelId).put("displayName", m.displayName)
                        .put("contextTokens", m.contextTokens).put("inputTokens", m.inputTokens).put("maxOutputTokens", m.maxOutputTokens)
                        .put("reasoningLevels", m.reasoningLevels).put("defaultReasoning", m.defaultReasoning)
                        .put("inputModalities", m.inputModalities).put("outputModalities", m.outputModalities)
                        .put("toolCapabilities", ChatTurnOptions.normalizeCapabilities(m.toolCapabilities)));
                String defaultModel = models.length() > 0 ? models.optJSONObject(0).optString("modelId", p.model) : p.model;
                providers.put(new JSONObject().put("providerId", p.id).put("displayName", p.displayName).put("protocol", p.protocol)
                        .put("authMode", p.authMode).put("reasoningWire", p.reasoningWire).put("model", defaultModel)
                        .put("hasCredential", secrets.has(p.id)).put("configured", secrets.has(p.id) && models.length() > 0).put("models", models));
            }
            view.put("providers", providers);
            JSONArray sessions = new JSONArray(); for (ChatDatabase.Session s : db.listSessions()) sessions.put(sessionJson(s, false)); view.put("sessions", sessions);
            if (activeSessionId != null) { ChatDatabase.Session active = db.getSession(activeSessionId); if (active != null) view.put("activeLocalSessionId", activeSessionId).put("activeSession", sessionJson(active, true)); }
            RemoteDataBridge.Status rs = remoteBridge.status(); JSONObject remote = new JSONObject().put("available", rs.available); if (rs.contractVersion > 0) remote.put("contractVersion", rs.contractVersion); if (rs.reason != null) remote.put("reason", rs.reason);
            JSONArray handoffs = new JSONArray(); for (ChatDatabase.Handoff h : db.listHandoffs()) handoffs.put(new JSONObject().put("handoffId", h.id).put("branchSessionId", h.branchSessionId).put("state", h.state).put("createdAt", h.createdAt).put("summary", h.summary));
            view.put("remoteBridge", remote).put("remoteSources", remoteSources).put("handoffs", handoffs)
                    .put("ui", new JSONObject().put("pendingRequestIds", new JSONArray()).put("showReasoning", showReasoning)
                            .put("defaultContextMode", defaultContextMode).put("defaultWebMode", defaultWebMode));
            host.dispatch(new JSONObject().put("version", 1).put("type", "view.full").put("revision", ++revision).put("view", view));
        } catch (Exception error) { host.showMessage("view update failed: " + safe(error)); }
    }

    private JSONArray displayTimeline(ChatDatabase.Session s) throws Exception {
        JSONArray out = new JSONArray();
        if (s.originSnapshotId != null) {
            JSONArray inherited = db.snapshotItems(s.originSnapshotId);
            for (int i = 0; i < inherited.length(); i++) {
                JSONObject item = inherited.optJSONObject(i); if (item == null) continue;
                out.put(new JSONObject(item.toString()).put("inherited", true));
            }
        }
        JSONArray local = db.timeline(s.id);
        for (int i = 0; i < local.length(); i++) { JSONObject item = local.optJSONObject(i); if (item != null) out.put(item); }
        return out;
    }

    private JSONObject sessionJson(ChatDatabase.Session s, boolean detail) throws Exception {
        ChatDatabase.Provider p = db.getProvider(s.providerId);
        ChatDatabase.Snapshot originSnap = s.originSnapshotId == null ? null : db.getSnapshot(s.originSnapshotId);
        boolean preparing = preparingSessions.contains(s.id);
        String webPreparingStage = webPreparingStages.get(s.id);
        boolean webPreparing = webPreparingStage != null;
        boolean active = activeTurns.containsKey(s.id) || preparing || webPreparing;
        String visibleState = active ? "running" : ("running".equals(s.state) ? "failed" : s.state);
        JSONObject o = new JSONObject().put("localSessionId", s.id).put("title", s.title).put("providerId", s.providerId).put("providerName", p == null ? s.providerId : p.displayName)
                .put("modelProfileId", s.modelProfileId == null ? JSONObject.NULL : s.modelProfileId).put("model", s.model)
                .put("reasoningEffort", s.reasoningEffort == null ? ReasoningPolicy.DEFAULT : s.reasoningEffort)
                .put("contextMode", ContextMode.normalize(s.contextMode)).put("effectiveContextMode", ContextMode.resolve(s, originSnap).effectiveLabel)
                .put("inputModalities", ChatDatabase.normalizeModalities(s.inputModalities)).put("outputModalities", ChatDatabase.normalizeModalities(s.outputModalities))
                .put("state", visibleState).put("updatedAt", s.updatedAt);
        if (s.preview != null) o.put("preview", s.preview);
        TaskProfile taskProfile = TaskProfile.fromStored(s.taskProfile);
        if (taskProfile != null) o.put("taskProfile", taskProfile.toJson());
        o.put("hasContextCheckpoint", ContextCheckpoint.fromStored(s.contextCheckpoint) != null);
        if (originSnap != null) o.put("origin", new JSONObject().put("snapshotId", originSnap.id).put("sourceType", originSnap.sourceType).put("sourceTitle", originSnap.sourceTitle).put("sourceRuntime", originSnap.sourceRuntime == null ? JSONObject.NULL : originSnap.sourceRuntime).put("createdAt", originSnap.createdAt).put("itemCount", originSnap.itemCount).put("sourceItemCount", originSnap.sourceItemCount).put("truncated", originSnap.truncated));
        if (detail) o.put("messages", displayTimeline(s)).put("composerDraft", s.draft == null ? "" : s.draft)
                .put("draftMedia", db.draftMedia(s.id)).put("canAttachImage", ChatDatabase.supportsModality(s.inputModalities, "image") && !active)
                .put("canSend", p != null && secrets.has(s.providerId) && !active).put("canInterrupt", activeTurns.containsKey(s.id))
                .put("contextPreparing", preparing);
        return o;
    }

    private void result(String requestId, boolean ok, String error) { if (requestId == null || requestId.isEmpty()) return; try { JSONObject e = new JSONObject().put("version", 1).put("type", "intent.result").put("requestId", requestId).put("ok", ok); if (error != null) e.put("error", new JSONObject().put("summary", error)); host.dispatch(e); } catch (Exception ignored) { } }
    private static String required(JSONObject p, String key) { String v = p.optString(key, "").trim(); if (v.isEmpty()) throw new IllegalArgumentException("missing " + key); return v; }
    private static int requiredSeq(JSONObject p) { if (!p.has("localSeq")) throw new IllegalArgumentException("missing localSeq"); int seq = p.optInt("localSeq", -1); if (seq < 1) throw new IllegalArgumentException("invalid localSeq"); return seq; }
    private static String safe(Exception e) { String m = e.getMessage(); return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m; }
    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String empty(String v) { return v == null || v.isEmpty() ? null : v; }
    private static String bounded(String value, int max, String label) { String v = value == null ? "" : value.trim(); if (v.isEmpty()) throw new IllegalArgumentException(label + " required"); if (v.length() > max) throw new IllegalArgumentException(label + " too long"); return v; }
    private static String validRoute(String value) { String v = value == null ? "" : value.trim(); if (!("home".equals(v) || "conversation".equals(v) || "providers".equals(v) || "imports".equals(v) || "handoffs".equals(v) || "settings".equals(v))) return "home"; return v; }
    private static String validTheme(String value) { String v = value == null ? "" : value.trim(); if (!("system".equals(v) || "light".equals(v) || "dark".equals(v))) return "system"; return v; }

    @Override public void close() { closed = true; for (ChatTransport.Turn t : activeTurns.values()) try { t.cancel(); } catch (Exception ignored) { } activeTurns.clear(); transports.close(); worker.shutdownNow(); }
}
