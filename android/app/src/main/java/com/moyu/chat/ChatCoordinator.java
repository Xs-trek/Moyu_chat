package com.moyu.chat;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native-only domain coordinator. UI knows intents/view models; provider keys never cross the bridge. */
final class ChatCoordinator implements AutoCloseable {
    interface Host { void dispatch(JSONObject envelope); void openProviderEditor(ChatDatabase.Provider existing); void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing); void openImagePicker(String sessionId); void showMessage(String text); }

    private final ChatDatabase db;
    private final ChatSecretStore secrets;
    private final ChatMediaStore mediaStore;
    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ChatTransportRegistry transports = new ChatTransportRegistry();
    private final ContextBuilder contexts;
    private final SnapshotService snapshots;
    private final RemoteDataBridge remoteBridge;
    private final Map<String, ChatTransport.Turn> activeTurns = new HashMap<>();

    private volatile boolean closed;
    private boolean uiReady;
    private long revision;
    private String route;
    private String activeSessionId;
    private String theme;
    private JSONArray remoteSources = new JSONArray();

    ChatCoordinator(MoyuChatApplication app, Host host) { this(app, host, app.secrets(), new AndroidRemoteDataBridge(app)); }

    /** Production wires the ContentResolver bridge; JVM tests inject fakes for the optional Remote side. */
    ChatCoordinator(MoyuChatApplication app, Host host, ChatSecretStore secrets, RemoteDataBridge bridge) {
        this.db = app.database(); this.secrets = secrets; this.host = host;
        this.contexts = new ContextBuilder(db); this.snapshots = new SnapshotService(db);
        this.remoteBridge = bridge;
        // Recovery runs on the same single worker as every intent, so it is ordered before any send.
        worker.execute(() -> { db.recoverInterruptedSessions(); emitView(); });
        this.route = validRoute(db.setting("route", "home"));
        this.activeSessionId = empty(db.setting("activeSessionId", ""));
        this.theme = validTheme(db.setting("theme", "system"));
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
                case "nav.open": route = validRoute(required(p, "route")); db.putSetting("route", route); break;
                case "chat.session.create": createSession(p); break;
                case "chat.session.open": openSession(required(p, "localSessionId")); break;
                case "chat.session.send": send(required(p, "localSessionId"), p.optString("text", "")); break;
                case "chat.session.saveDraft": db.setDraft(required(p, "localSessionId"), p.optString("text", "")); break;
                case "chat.session.interrupt": interrupt(required(p, "localSessionId")); break;
                case "chat.session.delete": deleteSession(required(p, "localSessionId")); break;
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
                case "branch.create.remote": createRemoteBranch(required(p, "sourceId"), p.has("throughSeq") ? p.optInt("throughSeq") : null); break;
                case "branch.create.local": createLocalBranch(required(p, "localSessionId"), p.has("throughSeq") ? p.optInt("throughSeq") : null); break;
                case "handoff.create": createHandoff(required(p, "localSessionId")); break;
                case "handoff.submit": submitHandoff(required(p, "handoffId")); break;
                default: throw new IllegalArgumentException("unsupported intent: " + type);
            }
            result(requestId, true, null); emitView();
        } catch (Exception error) { result(requestId, false, safe(error)); emitView(); }
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
        ChatDatabase.Session s = db.createSession(profile, title, null, null, effort);
        activate(s.id);
    }

    private void openSession(String id) { if (db.getSession(id) == null) throw new IllegalArgumentException("session not found"); activate(id); }
    private void activate(String id) { activeSessionId = id; db.putSetting("activeSessionId", id); route = "conversation"; db.putSetting("route", route); }

    private void deleteSession(String id) {
        interrupt(id); db.deleteSession(id);
        if (id.equals(activeSessionId)) { activeSessionId = null; db.putSetting("activeSessionId", ""); route = "home"; db.putSetting("route", route); }
    }

    private void send(String id, String text) throws Exception {
        // trim is an emptiness check only; the stored/prompted/sent text stays byte-for-byte original.
        String input = text == null ? "" : text;
        if (input.trim().isEmpty()) return;
        ChatDatabase.Session session = db.getSession(id); if (session == null) throw new IllegalArgumentException("session not found");
        if (activeTurns.containsKey(id)) throw new IllegalStateException("session busy");
        ChatDatabase.Provider provider = db.getProvider(session.providerId); String key = secrets.get(session.providerId);
        if (provider == null || key == null) throw new IllegalStateException("provider credential missing");
        ChatTransport transport = transports.forProtocol(provider.protocol); if (transport == null) throw new IllegalStateException("provider protocol unsupported");

        // Preflight the exact prompt before mutating durable chat history. Session owns the resolved model limits.
        CanonicalPrompt prompt = contexts.build(session, session.contextTokens, session.inputTokens, session.maxOutputTokens, input);
        provider.maxOutputTokens = session.maxOutputTokens; // request-local provider copy; transport only needs the wire output cap.
        JSONObject user = new JSONObject().put("kind", "message").put("role", "user").put("text", input).put("createdAt", Instant.now().toString());
        db.appendTimeline(id, "user:" + UUID.randomUUID(), user); db.setDraft(id, "");
        session = db.getSession(id);
        session.state = "running"; session.updatedAt = Instant.now().toString(); db.saveSession(session);
        String streamKey = "assistant:live:" + id;
        JSONObject live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "").put("streaming", true).put("createdAt", Instant.now().toString());
        db.appendTimeline(id, streamKey, live);
        StringBuilder textOut = new StringBuilder();
        String usageKey = "usage:turn:" + UUID.randomUUID();
        JSONObject usageState = new JSONObject().put("kind", "usage").put("createdAt", Instant.now().toString()).put("context", prompt.diagnostics());
        boolean[] usagePersisted = new boolean[]{false};
        ChatTransport.Turn turn = transport.stream(provider, key, session.model, session.reasoningEffort, prompt, new ChatTransport.Sink() {
            @Override public void onTextDelta(String delta) { worker.execute(() -> { try { textOut.append(delta); db.replaceTimelineBody(streamKey, new JSONObject(live.toString()).put("text", textOut.toString()).put("streaming", true)); emitView(); } catch (Exception ignored) { } }); }
            @Override public void onUsage(Integer in, Integer out) { worker.execute(() -> { try { if (in != null) usageState.put("inputTokens", in); if (out != null) usageState.put("outputTokens", out); if (usagePersisted[0]) db.replaceTimelineBody(usageKey, usageState); else { db.appendTimeline(id, usageKey, usageState); usagePersisted[0] = true; } } catch (Exception ignored) { } }); }
            @Override public void onCompleted() { worker.execute(() -> finishTurn(id, streamKey, live, textOut.toString(), null)); }
            @Override public void onError(String code, String summary, boolean retryable) { worker.execute(() -> finishTurn(id, streamKey, live, textOut.toString(), new String[]{code, summary})); }
        });
        activeTurns.put(id, turn); emitView();
    }

    private void finishTurn(String id, String streamKey, JSONObject seed, String text, String[] error) {
        activeTurns.remove(id);
        try {
            if (!text.isEmpty()) db.replaceTimelineBody(streamKey, new JSONObject(seed.toString()).put("text", text).put("streaming", false)); else db.deleteTimelineItem(streamKey);
            if (error != null && !"cancelled".equals(error[0])) db.appendTimeline(id, "error:" + UUID.randomUUID(), new JSONObject().put("kind", "error").put("code", error[0]).put("summary", error[1]).put("createdAt", Instant.now().toString()));
            ChatDatabase.Session s = db.getSession(id); if (s != null) { s.state = error == null || "cancelled".equals(error[0]) ? "completed" : "failed"; s.updatedAt = Instant.now().toString(); db.saveSession(s); }
            emitView();
        } catch (Exception ignored) { }
    }

    private void interrupt(String id) { ChatTransport.Turn turn = activeTurns.get(id); if (turn != null) turn.cancel(); }

    private void refreshSources() throws Exception { RemoteDataBridge.Status status = remoteBridge.status(); remoteSources = status.available ? remoteBridge.listSources() : new JSONArray(); }

    private void createLocalBranch(String sourceSessionId, Integer throughSeq) throws Exception {
        ChatDatabase.Session source = db.getSession(sourceSessionId); if (source == null) throw new IllegalArgumentException("source session not found");
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(sourceSessionId, throughSeq);
        ChatDatabase.Session branch = db.branchSession(source, "分支 · " + source.title, snapshot.id, sourceSessionId);
        activate(branch.id);
    }

    private void createRemoteBranch(String sourceId, Integer throughSeq) throws Exception {
        if (!remoteBridge.status().available) throw new IllegalStateException("RemoteDataBridge unavailable");
        ChatDatabase.Provider provider = preferredProvider(); if (provider == null) throw new IllegalStateException("configure a Chat provider before importing a Remote branch");
        ChatDatabase.ModelProfile profile = db.firstModelProfile(provider.id); if (profile == null) throw new IllegalStateException("configure a model before importing a Remote branch");
        ChatDatabase.Snapshot snapshot = snapshots.importRemote(remoteBridge.createSnapshot(sourceId, throughSeq));
        ChatDatabase.Session branch = db.createSession(profile, "分支 · " + snapshot.sourceTitle, snapshot.id, null, profile.defaultReasoning);
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
                        .put("reasoningLevels", m.reasoningLevels).put("defaultReasoning", m.defaultReasoning));
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
            view.put("remoteBridge", remote).put("remoteSources", remoteSources).put("handoffs", handoffs).put("ui", new JSONObject().put("pendingRequestIds", new JSONArray()));
            host.dispatch(new JSONObject().put("version", 1).put("type", "view.full").put("revision", ++revision).put("view", view));
        } catch (Exception ignored) { }
    }

    private JSONObject sessionJson(ChatDatabase.Session s, boolean detail) throws Exception {
        ChatDatabase.Provider p = db.getProvider(s.providerId);
        JSONObject o = new JSONObject().put("localSessionId", s.id).put("title", s.title).put("providerId", s.providerId).put("providerName", p == null ? s.providerId : p.displayName)
                .put("modelProfileId", s.modelProfileId == null ? JSONObject.NULL : s.modelProfileId).put("model", s.model)
                .put("reasoningEffort", s.reasoningEffort == null ? ReasoningPolicy.DEFAULT : s.reasoningEffort)
                .put("state", s.state).put("updatedAt", s.updatedAt);
        if (s.preview != null) o.put("preview", s.preview);
        if (s.originSnapshotId != null) { ChatDatabase.Snapshot snap = db.getSnapshot(s.originSnapshotId); if (snap != null) o.put("origin", new JSONObject().put("snapshotId", snap.id).put("sourceType", snap.sourceType).put("sourceTitle", snap.sourceTitle).put("sourceRuntime", snap.sourceRuntime == null ? JSONObject.NULL : snap.sourceRuntime).put("createdAt", snap.createdAt).put("itemCount", snap.itemCount).put("sourceItemCount", snap.sourceItemCount).put("truncated", snap.truncated)); }
        if (detail) o.put("messages", db.timeline(s.id)).put("composerDraft", s.draft == null ? "" : s.draft).put("canSend", p != null && secrets.has(s.providerId) && !activeTurns.containsKey(s.id)).put("canInterrupt", activeTurns.containsKey(s.id));
        return o;
    }

    private void result(String requestId, boolean ok, String error) { if (requestId == null || requestId.isEmpty()) return; try { JSONObject e = new JSONObject().put("version", 1).put("type", "intent.result").put("requestId", requestId).put("ok", ok); if (error != null) e.put("error", new JSONObject().put("summary", error)); host.dispatch(e); } catch (Exception ignored) { } }
    private static String required(JSONObject p, String key) { String v = p.optString(key, "").trim(); if (v.isEmpty()) throw new IllegalArgumentException("missing " + key); return v; }
    private static String safe(Exception e) { String m = e.getMessage(); return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m; }
    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String empty(String v) { return v == null || v.isEmpty() ? null : v; }
    private static String bounded(String value, int max, String label) { String v = value == null ? "" : value.trim(); if (v.isEmpty()) throw new IllegalArgumentException(label + " required"); if (v.length() > max) throw new IllegalArgumentException(label + " too long"); return v; }
    private static String validRoute(String value) { String v = value == null ? "" : value.trim(); if (!("home".equals(v) || "conversation".equals(v) || "providers".equals(v) || "imports".equals(v) || "handoffs".equals(v) || "settings".equals(v))) return "home"; return v; }
    private static String validTheme(String value) { String v = value == null ? "" : value.trim(); if (!("system".equals(v) || "light".equals(v) || "dark".equals(v))) return "system"; return v; }

    @Override public void close() { closed = true; for (ChatTransport.Turn t : activeTurns.values()) try { t.cancel(); } catch (Exception ignored) { } activeTurns.clear(); transports.close(); worker.shutdownNow(); }
}
