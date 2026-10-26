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
    private static final int MAX_BATCH_DELETE = 500;
    private static final int MAX_UI_INHERITED_CHARS = 512_000;
    private static final ContextBudgetManager CONTEXT_BUDGETS = new ContextBudgetManager();
    interface Host { void dispatch(JSONObject envelope); /** Native secure entry for a Provider's API Key: the key goes straight to ChatSecretStore, never through the WebView. */ default void openCredentialEditor(ChatDatabase.Provider provider, String hosts) { } default void openImagePicker(String sessionId) { } default void openExternalUrl(String url) { } default boolean setTurnRuntimeActive(String sessionId, boolean active) { return true; } void showMessage(String text); /** Native window chrome (system bars, window background) follows the resolved UI theme. */ default void applyAppearance(String resolvedTheme, String tone) { } /** A turn ended on its own; the host notifies only while no Activity is visible. */ default void turnSettled(String sessionId, String title, boolean failed) { } /** Asks for the Android 13+ notification permission; {@code onlyOnce} skips it when it was asked before. */ default void requestNotificationPermission(boolean onlyOnce) { } }

    private final ChatDatabase db;
    private final ChatSecretStore secrets;
    private final ChatMediaStore mediaStore;
    private final Host host;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // High-frequency provider deltas are coalesced before they reach the intent/DB authority queue.
    private final ScheduledExecutorService streamProjectionScheduler = Executors.newSingleThreadScheduledExecutor();
    // Blocking Router/Checkpoint/Web preparation must never occupy the UI/intent authority queue.
    private final ExecutorService preparationWorker = Executors.newFixedThreadPool(2);
    /** Account checks the user starts (key, model list, balance, probe); the front end allows one per target at a time. */
    static final int MAX_RUNNING_CHECKS = 3;
    private final ExecutorService guardWorker = Executors.newFixedThreadPool(MAX_RUNNING_CHECKS);
    /** Checks started and not yet finished; never more than the pool has threads, so no check waits in a queue. */
    private final java.util.concurrent.atomic.AtomicInteger runningChecks = new java.util.concurrent.atomic.AtomicInteger();
    /** Session naming, kept off guardWorker so a slow probe never delays a title and a title never queues a check. */
    private final ExecutorService titleWorker = Executors.newSingleThreadExecutor();
    // Client tools may run independently; the hard subagent fan-out is four and no child may recurse.
    private final ExecutorService clientToolWorker = Executors.newFixedThreadPool(ClientToolRuntime.MAX_PARALLEL_SUBAGENTS);
    private final ChatTransportRegistry transports = new ChatTransportRegistry();
    private final ContextBuilder contexts;
    private final SnapshotService snapshots;
    private final ProviderConfigService config;
    private final ProviderGuard guard;
    /** Turn stages (Router, checkpoint, Web, generation, verification) live in the pipeline; this class owns intents and views. */
    private final TurnPipeline pipeline;
    private final Map<String, ChatTransport.Turn> activeTurns = new ConcurrentHashMap<>();
    private final Map<String, TurnControl> turnControls = new ConcurrentHashMap<>();
    private final java.util.Set<String> startingSessions = ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> preparingSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> webPreparingStages = new ConcurrentHashMap<>();
    private final Map<String, String> postProcessingStages = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStreamViewNanos = new ConcurrentHashMap<>();
    /** The question (row key) a session's next turn answers instead of a new message: retry and re-answer (spec T7, H5). */
    private final Map<String, String> pendingAnswers = new ConcurrentHashMap<>();
    /** Retry link of a session's next accepted turn: {retryOf: the failed version's turn id, attempt: n}. */
    private final Map<String, JSONObject> pendingRetries = new ConcurrentHashMap<>();
    /** Sessions automatic naming already ran for in this process; a failed attempt is not repeated on its own. */
    private final java.util.Set<String> namedSessions = ConcurrentHashMap.newKeySet();
    private final Map<String, String> pendingKeptDrafts = new ConcurrentHashMap<>();

    private volatile boolean closed;
    private volatile boolean uiReady;
    private long revision;
    private volatile String route;
    private volatile String activeSessionId;
    private String theme;
    private String tone;
    private boolean delight;
    private final MascotSettings mascot;
    private final java.util.function.BooleanSupplier systemDark;
    private String usageTodayKey = "";
    private JSONObject usageTodayCache;
    private boolean showReasoning;
    private boolean crossChatMemoryEnabled;
    /** Read by turn threads when a turn settles. */
    private volatile boolean notifyInBackground;
    /** Global custom instruction (settings), frozen into each accepted turn's config. */
    private String customInstruction;
    /** Helper modules the user switched off (HelperSwitches ids, settings), frozen into each accepted turn's config. */
    private java.util.Set<String> helpersOff;
    /** Spec D15: the helper model and which helpers use it (settings), resolved into each accepted turn's config. */
    private HelperModel.Choice helperChoice;
    private boolean wireCapture;
    private String defaultContextMode;
    private String defaultWebMode;
    private String standardSubagentMode;

    ChatCoordinator(MoyuChatApplication app, Host host) { this(app, host, app.secrets()); }

    /** JVM tests inject an in-memory secret store. */
    ChatCoordinator(MoyuChatApplication app, Host host, ChatSecretStore secrets) {
        this.db = app.database(); this.secrets = secrets; this.mediaStore = app.mediaStore(); this.host = host;
        this.systemDark = () -> (app.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        this.contexts = new ContextBuilder(db, mediaStore); this.snapshots = new SnapshotService(db);
        this.config = new ProviderConfigService(db, secrets, transports);
        this.guard = new ProviderGuard(db, secrets, config, transports);
        this.pipeline = new TurnPipeline(new PipelineEnv(), db, contexts, mediaStore, transports);
        // Recovery runs on the same single worker as every intent, so it is ordered before any send.
        worker.execute(() -> { db.recoverInterruptedSessions(); dropBlankSessions(empty(db.setting("activeSessionId", ""))); pruneOrphans(); config.refreshCatalog(); emitView(); });
        this.route = validRoute(db.setting("route", "home"));
        this.activeSessionId = empty(db.setting("activeSessionId", ""));
        this.theme = validTheme(db.setting("theme", "system"));
        this.tone = validTone(db.setting("tone", "sage"));
        this.delight = !"false".equals(db.setting("delight", "true"));
        this.mascot = new MascotSettings(db);
        this.showReasoning = "true".equals(db.setting("showReasoning", "false"));
        this.crossChatMemoryEnabled = ContextBuilder.globalMemoryEnabled(db);
        this.wireCapture = !"false".equals(db.setting("wireCapture", "true"));
        this.notifyInBackground = !"false".equals(db.setting("notifyInBackground", "true"));
        this.customInstruction = db.setting("customInstruction", "");
        this.helpersOff = HelperSwitches.parse(db.setting(HelperSwitches.SETTING_KEY, HelperSwitches.DEFAULT_OFF));
        this.helperChoice = HelperModel.Choice.parse(db.setting(HelperModel.SETTING_KEY, ""));
        this.defaultContextMode = ContextMode.normalize(db.setting("defaultContextMode", ContextMode.STANDARD));
        this.defaultWebMode = ChatTurnOptions.normalizeWebMode(db.setting("defaultWebMode", ChatTurnOptions.WEB_AUTO));
        this.standardSubagentMode = HarnessPolicyResolver.normalizeSubagentMode(db.setting("standardSubagentMode", HarnessPolicyResolver.SUBAGENT_OFF));
    }

    /** Keys and images whose owners no longer exist (deleted rows, or a database recreated for a new layout) never outlive them. */
    private void pruneOrphans() {
        try {
            java.util.HashSet<String> providerIds = new java.util.HashSet<>();
            for (ChatDatabase.Provider p : db.listProviders()) providerIds.add(p.id);
            secrets.retainOnly(providerIds);
            mediaStore.pruneUnreferenced(db.referencedMediaIds());
        } catch (Exception error) { host.showMessage("启动清理失败：" + safe(error)); }
    }

    void postIntent(String raw) { if (raw == null || raw.length() > 1024 * 1024) return; worker.execute(() -> handle(raw)); }
    void refreshView() { worker.execute(this::emitView); }

    interface SaveCallback { void complete(String error); }

    /** Native API Key entry (the secure field in MainActivity). The key goes straight to the secure store. */
    void saveCredential(String providerId, String apiKey) { saveCredential(providerId, apiKey, null); }

    void saveCredential(String providerId, String apiKey, SaveCallback completion) {
        worker.execute(() -> {
            try {
                config.saveCredential(providerId, apiKey);
                emitView();
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
                case "ui.mascot.set": mascot.set(required(p, "key"), p.opt("value")); break;
                case "ui.delight.set": delight = p.optBoolean("enabled", true); db.putSetting("delight", delight ? "true" : "false"); break;
                case "chat.reasoning.visibility.set": showReasoning = p.optBoolean("show", false); db.putSetting("showReasoning", showReasoning ? "true" : "false"); break;
                case "inspector.capture.set": wireCapture = p.optBoolean("enabled", true); db.putSetting("wireCapture", wireCapture ? "true" : "false"); break;
                case "memory.global.set": crossChatMemoryEnabled = p.optBoolean("enabled", false); db.putSetting(ContextBuilder.MEMORY_SETTING, crossChatMemoryEnabled ? "true" : "false"); break;
                case "notify.background.set": notifyInBackground = p.optBoolean("enabled", true); db.putSetting("notifyInBackground", notifyInBackground ? "true" : "false"); if (notifyInBackground) host.requestNotificationPermission(false); break;
                case "instruction.global.set": {
                    String text = p.optString("text", "").trim();
                    if (text.length() > TurnConfig.MAX_CUSTOM_INSTRUCTION_CHARS) throw new IllegalArgumentException("自定义指令不能超过 " + TurnConfig.MAX_CUSTOM_INSTRUCTION_CHARS + " 字");
                    customInstruction = text; db.putSetting("customInstruction", text);
                    break;
                }
                case "helper.model.set": {
                    String profileId = p.optString("modelProfileId", "").trim();
                    if (!profileId.isEmpty()) {
                        ChatDatabase.ModelProfile chosen = db.getModelProfile(profileId);
                        if (chosen == null || !chosen.enabled) throw new IllegalArgumentException("这个模型不存在或已停用");
                    }
                    helperChoice = helperChoice.withProfile(profileId);
                    db.putSetting(HelperModel.SETTING_KEY, helperChoice.toJson().toString());
                    break;
                }
                case "helper.model.use.set": {
                    String helper = required(p, "helper");
                    if (!HelperModel.ALL.contains(helper)) throw new IllegalArgumentException("未知的辅助模块");
                    helperChoice = helperChoice.withUse(helper, p.optBoolean("enabled", true));
                    db.putSetting(HelperModel.SETTING_KEY, helperChoice.toJson().toString());
                    break;
                }
                case "helper.switch.set": {
                    String helper = required(p, "helper");
                    if (!HelperSwitches.known(helper)) throw new IllegalArgumentException("未知的辅助模块");
                    java.util.Set<String> next = new java.util.HashSet<>(helpersOff);
                    if (p.optBoolean("enabled", true)) next.remove(helper); else next.add(helper);
                    helpersOff = HelperSwitches.canonical(next);
                    db.putSetting(HelperSwitches.SETTING_KEY, HelperSwitches.toJson(helpersOff).toString());
                    break;
                }
                case "context.mode.default.set": defaultContextMode = ContextMode.normalize(required(p, "mode")); db.putSetting("defaultContextMode", defaultContextMode); break;
                case "web.mode.default.set": defaultWebMode = ChatTurnOptions.normalizeWebMode(required(p, "mode")); db.putSetting("defaultWebMode", defaultWebMode); break;
                case "subagent.standard.default.set": standardSubagentMode = HarnessPolicyResolver.normalizeSubagentMode(required(p, "mode")); db.putSetting("standardSubagentMode", standardSubagentMode); break;
                case "external.open": host.openExternalUrl(required(p, "url")); break;
                case "nav.open": route = validRoute(required(p, "route")); db.putSetting("route", route); break;
                case "chat.session.create": createSession(p); break;
                case "chat.session.open": openSession(required(p, "localSessionId")); break;
                case "chat.session.rename": renameSession(required(p, "localSessionId"), required(p, "title")); break;
                case "chat.session.title.auto": autoTitleNow(requestId, required(p, "localSessionId")); return;
                case "chat.session.model.set": setSessionModel(required(p, "localSessionId"), required(p, "modelProfileId")); break;
                case "chat.session.reasoning.set": setSessionReasoning(required(p, "localSessionId"), required(p, "reasoningEffort")); break;
                case "chat.session.web.set": setSessionWebMode(required(p, "localSessionId"), required(p, "webMode")); break;
                case "chat.session.send": {
                    String sessionId = required(p, "localSessionId");
                    String message = p.optString("text", "");
                    if (sessionBusy(sessionId)) {
                        if (!message.trim().isEmpty()) db.setDraft(sessionId, message);
                        throw new IllegalStateException("上一轮还没结束，消息已留在输入框");
                    }
                    if (message.trim().isEmpty() && db.draftMedia(sessionId).length() == 0) break;
                    startTurn(requestId, sessionId, message, p, "", message);
                    return;
                }
                case "chat.turn.retry": retryTurn(requestId, required(p, "localSessionId"), p.optBoolean("budgetConfirmed", false)); return;
                case "provider.key.verify": { String id = required(p, "providerId"); checkAsync(requestId, () -> guard.verifyKey(id)); return; }
                case "route.models.list": { String id = required(p, "routeId"); checkAsync(requestId, () -> guard.listModels(id)); return; }
                case "provider.balance.get": { String id = required(p, "providerId"); checkAsync(requestId, () -> guard.balance(id)); return; }
                case "model.probe": { String id = required(p, "modelProfileId"); checkAsync(requestId, () -> guard.probe(id)); return; }
                case "model.capabilities.probe": { String id = required(p, "modelProfileId"); checkAsync(requestId, () -> guard.probeCapabilities(id)); return; }
                // The composer already shows what was typed: saving it changes nothing on screen, so no view push.
                case "chat.session.saveDraft": saveDraft(required(p, "localSessionId"), p.optString("text", "")); result(requestId, true, null); return;
                case "chat.attachment.pick": requestImagePicker(required(p, "localSessionId")); break;
                case "chat.attachment.remove": {
                    db.removeDraftMedia(required(p, "localSessionId"), required(p, "mediaId"));
                    mediaStore.pruneUnreferenced(db.referencedMediaIds());
                    break;
                }
                case "chat.session.interrupt": interrupt(required(p, "localSessionId")); break;
                case "chat.session.delete": {
                    JSONArray many = p.optJSONArray("localSessionIds");
                    if (many == null) { deleteSession(required(p, "localSessionId")); break; }
                    result(requestId, true, null, deleteSessions(many)); emitView();
                    return;
                }
                case "chat.message.edit": editMessageInPlace(required(p, "localSessionId"), requiredSeq(p)); break;
                case "chat.message.rollback": returnToMessage(required(p, "localSessionId"), requiredSeq(p)); break;
                case "chat.message.version": selectVersion(required(p, "localSessionId"), requiredSeq(p)); break;
                // Configuration editors live in the WebView; only the API Key has a native entry. Saves push
                // the view before the result, so the editor's follow-up already sees the saved rows (ids).
                case "provider.save": { JSONObject saved = config.saveAccount(p); emitView(); result(requestId, true, null, saved); return; }
                case "provider.delete": config.deleteAccount(required(p, "providerId")); break;
                case "provider.credential.edit": {
                    ChatDatabase.Provider provider = db.getProvider(required(p, "providerId"));
                    if (provider == null) throw new IllegalArgumentException("账号不存在");
                    host.openCredentialEditor(provider, config.hostsOf(provider.id));
                    break;
                }
                case "route.host.confirm": config.confirmHost(required(p, "routeId")); break;
                case "model.save": { JSONObject saved = config.saveModel(p); emitView(); result(requestId, true, null, saved); return; }
                case "model.delete": config.deleteModel(required(p, "modelProfileId")); break;
                case "model.enabled.set": config.setModelEnabled(required(p, "modelProfileId"), p.optBoolean("enabled", true)); break;
                case "model.editor.options": result(requestId, true, null, ProviderConfigService.editorOptions()); return; // read-only
                case "model.preview": result(requestId, true, null, config.preview(p)); return; // read-only
                case "route.catalog": result(requestId, true, null, config.routeCatalog(required(p, "routeId"))); return; // read-only
                case "verification.feedback": recordVerificationFeedback(required(p, "localSessionId"), requiredSeq(p), required(p, "label")); break;
                case "branch.create.local": createLocalBranch(required(p, "localSessionId"), p.has("throughSeq") ? p.optInt("throughSeq") : null); break;
                case "turn.rate": {
                    int rating = p.optInt("rating", 0);
                    db.rateTurn(required(p, "localSessionId"), required(p, "turnId"), rating);
                    break;
                }
                case "turn.rerun": rerunTurn(requestId, required(p, "localSessionId"), requiredSeq(p),
                        p.optString("modelProfileId", "").trim(), p.optString("contextMode", "").trim(), p.optBoolean("budgetConfirmed", false)); return;
                case "turn.answers.get": {
                    JSONObject group = db.answerGroup(required(p, "localSessionId"), required(p, "turnId"));
                    if (group == null) throw new IllegalArgumentException("找不到这一轮的记录");
                    result(requestId, true, null, group);
                    return; // read-only: no view change
                }
                case "stats.get": {
                    int days = Math.max(1, Math.min(365, p.optInt("days", 7)));
                    String since = Instant.now().minus(java.time.Duration.ofDays(days)).toString();
                    String[] groupBy = TurnStats.grouping(p.optString("groupBy", ""));
                    result(requestId, true, null, new JSONObject().put("days", days).put("since", since).put("groupBy", String.join(",", groupBy)).put("includeArchived", p.optBoolean("includeArchived", true))
                            .put("rows", db.turnStats(since, groupBy, p.optBoolean("includeArchived", true))));
                    return; // read-only: no view change
                }
                case "turn.payload.get": {
                    if (!p.has("callIndex")) throw new IllegalArgumentException("missing callIndex");
                    JSONObject payload = db.turnPayload(required(p, "localSessionId"), required(p, "turnId"), p.optInt("callIndex", -1));
                    if (payload == null) throw new IllegalArgumentException("找不到这一轮的记录");
                    result(requestId, true, null, payload);
                    return; // read-only: no view change
                }
                case "turn.trace.get": {
                    JSONObject trace = db.turnTrace(required(p, "localSessionId"), required(p, "turnId"));
                    if (trace == null) throw new IllegalArgumentException("找不到这一轮的记录");
                    result(requestId, true, null, trace);
                    return; // read-only: no view change
                }
                default: throw new IllegalArgumentException("unsupported intent: " + type);
            }
            result(requestId, true, null); emitView();
        } catch (Exception error) { result(requestId, false, safe(error)); emitView(); }
    }

    /**
     * Starts a turn for {@code message} plus the session's draft images. {@code keptDraft} is what the composer
     * holds once the turn is accepted; a refused send puts {@code restoreOnReject} back as the draft.
     */
    private void startTurn(String requestId, String sessionId, String message, JSONObject p, String keptDraft, String restoreOnReject) {
        final String asyncRequestId = requestId;
        final TurnConfig turnConfig;
        final TurnControl turnControl = new TurnControl();
        try {
            // Send is self-contained with respect to the visible turn setting. A preceding
            // reasoning.set bridge message is still persisted normally, but Send must not rely
            // on cross-bridge scheduling order to know what the user selected for this turn.
            if (p.has("reasoningEffort")) setSessionReasoning(sessionId, p.optString("reasoningEffort", ReasoningPolicy.DEFAULT));
            if (p.has("webMode")) setSessionWebMode(sessionId, p.optString("webMode", ChatTurnOptions.WEB_AUTO));
            // All user-selected wire/session capabilities plus provider config/secret/credential epoch
            // are frozen while this serialized intent worker owns the turn. UI navigation or profile
            // edits cannot relabel an already accepted request.
            turnConfig = freezeTurnConfig(sessionId);
            if (!p.optBoolean("budgetConfirmed", false)) {
                JSONObject reached = config.budgetReached(turnConfig.providerId);
                if (reached != null) throw new BudgetReached(reached);
            }
            if (!host.setTurnRuntimeActive(sessionId, true)) throw new IllegalStateException("系统没有允许启动后台生成服务，请稍后再试");
            if (turnControls.putIfAbsent(sessionId, turnControl) != null) {
                host.setTurnRuntimeActive(sessionId, false);
                throw new IllegalStateException("这个会话正在生成，请等当前回合结束");
            }
        } catch (Exception rejected) { pendingAnswers.remove(sessionId); sendRejected(requestId, sessionId, restoreOnReject, rejected); return; }
        // The composer is cleared only after the runtime lease makes this an accepted turn.
        db.setDraft(sessionId, keptDraft);
        if (keptDraft.isEmpty()) pendingKeptDrafts.remove(sessionId); else pendingKeptDrafts.put(sessionId, keptDraft);
        String placeholder = SessionTitles.placeholder(message); // the default title gives way to the first message at once
        if (placeholder != null) db.replaceTitleIf(sessionId, SessionTitles.DEFAULT_TITLE, placeholder);
        JSONObject retry = p.optJSONObject("retry"); // set only after acceptance, so a refused retry never labels a later turn
        if (retry == null) pendingRetries.remove(sessionId); else pendingRetries.put(sessionId, retry);
        startingSessions.add(sessionId);
        // The first accepted turn is when a result notification starts to matter.
        if (notifyInBackground) host.requestNotificationPermission(true);
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
                    pipeline.abandon(sessionId, turnControl, turnControl.isCancelled() ? "cancelled" : "turn_internal_error");
                    try { db.failUnhandledTurn(sessionId, turnControl.isCancelled() ? "cancelled" : "turn_internal_error", safe(error)); } catch (Exception ignored) { }
                    if (turnControls.get(sessionId) == turnControl) notifySettled(sessionId, turnControl); // not yet released, so not yet notified
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
            host.setTurnRuntimeActive(sessionId, false);
            sendRejected(requestId, sessionId, restoreOnReject, schedulingError);
        }
    }

    private void requestImagePicker(String sessionId) {
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
        if (!ChatDatabase.supportsModality(session.inputModalities, "image")) throw new IllegalStateException("当前模型没有声明图片输入能力");
        if (db.draftMedia(sessionId).length() >= ChatMediaStore.MAX_DRAFT_IMAGES) throw new IllegalStateException("图片数量已达上限");
        host.openImagePicker(sessionId);
    }

    void attachImageFromUri(String sessionId, Uri uri, ContentResolver resolver) {
        worker.execute(() -> {
            try {
                ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
                if (!ChatDatabase.supportsModality(session.inputModalities, "image")) throw new IllegalStateException("当前模型没有声明图片输入能力");
                JSONObject descriptor = mediaStore.importImage(resolver, uri); db.addDraftMedia(sessionId, descriptor); emitView();
            } catch (Exception error) { host.showMessage(safe(error)); emitView(); }
        });
    }

    private void createSession(JSONObject p) {
        String providerId = required(p, "providerId");
        ChatDatabase.Provider provider = db.getProvider(providerId);
        if (provider == null) throw new IllegalStateException("账号未配置");
        ChatDatabase.ModelProfile profile = null;
        String profileId = p.optString("modelProfileId", "").trim();
        if (!profileId.isEmpty()) profile = db.getModelProfile(profileId);
        if (profile == null) {
            String requestedModel = p.optString("model", "").trim();
            profile = requestedModel.isEmpty() ? db.firstModelProfile(providerId) : db.findModelProfile(providerId, requestedModel);
        }
        if (profile == null || !providerId.equals(profile.providerId)) throw new IllegalStateException("模型未配置");
        if (!profile.enabled) throw new IllegalStateException("这个模型已停用");
        ChatDatabase.Route route = db.getRoute(profile.routeId);
        if (route == null) throw new IllegalStateException("模型使用的路线已删除");
        String requestedTitle = p.optString("title", "").trim();
        String title = requestedTitle.isEmpty() ? null : bounded(requestedTitle, 200, "标题");
        String effort = p.has("reasoningEffort") ? ReasoningPolicy.normalizeEffort(p.optString("reasoningEffort", ReasoningPolicy.DEFAULT)) : ReasoningPolicy.DEFAULT;
        ReasoningPolicy.validateEffortForProvider(ChatDatabase.deploymentProvider(provider, route, profile), profile.reasoningLevels, effort);
        String contextMode = ContextMode.normalize(p.optString("contextMode", defaultContextMode));
        if (p.has("contextMode")) { defaultContextMode = contextMode; db.putSetting("defaultContextMode", defaultContextMode); }
        String webMode = ChatTurnOptions.normalizeWebMode(p.optString("webMode", defaultWebMode));
        dropBlankSessions(null); // a new Chat replaces any unused blank one instead of piling up beside it
        ChatDatabase.Session s = db.createSession(profile, title, null, null, effort, contextMode, webMode);
        activate(s.id);
    }

    /** A session that never had a message, has nothing in its composer and is not starting a turn: nothing to keep. */
    private boolean disposable(ChatDatabase.Session s, java.util.Set<String> blanks) {
        return s != null && blanks.contains(s.id) && (s.draft == null || s.draft.trim().isEmpty())
                && (s.draftMedia == null || s.draftMedia.trim().isEmpty() || "[]".equals(s.draftMedia.trim())) && !sessionBusy(s.id);
    }

    /** Deletes unused blank sessions except {@code keep}; blank sessions with an unsent draft stay (listed as drafts). */
    private void dropBlankSessions(String keep) {
        try {
            java.util.Set<String> blanks = db.blankSessionIds();
            for (String id : blanks) {
                if (id.equals(keep)) continue;
                ChatDatabase.Session s = db.getSession(id);
                if (disposable(s, blanks)) deleteSession(id);
            }
        } catch (Exception error) { host.showMessage("清理空白会话失败：" + safe(error)); }
    }

    private void openSession(String id) { if (db.getSession(id) == null) throw new IllegalArgumentException("会话不存在"); activate(id); }
    private void renameSession(String id, String title) { db.renameSession(id, bounded(title, 200, "会话标题")); }
    private void activate(String id) {
        String left = activeSessionId;
        activeSessionId = id; db.putSetting("activeSessionId", id); route = "conversation"; db.putSetting("route", route);
        if (left != null && !left.equals(id)) {
            java.util.Set<String> blanks = db.blankSessionIds();
            if (blanks.contains(left) && disposable(db.getSession(left), blanks)) { db.deleteSession(left); mediaStore.pruneUnreferenced(db.referencedMediaIds()); }
        }
    }

    /** Copies what a session freezes from its model profile; switching models and applying an edited profile share it. */
    static void adoptProfile(ChatDatabase.Session session, ChatDatabase.ModelProfile profile) {
        session.providerId = profile.providerId;
        session.modelProfileId = profile.id;
        session.model = profile.modelId;
        session.contextTokens = profile.contextTokens;
        session.inputTokens = profile.inputTokens;
        session.maxOutputTokens = profile.maxOutputTokens;
        session.inputModalities = ChatDatabase.normalizeModalities(profile.inputModalities);
        session.outputModalities = ChatDatabase.normalizeModalities(profile.outputModalities);
        session.toolCapabilities = ChatTurnOptions.normalizeCapabilities(profile.toolCapabilities);
        ChatDatabase.freezeDeployment(session, profile);
        session.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode);
        session.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
        session.continuationMode = ProviderContinuationPolicy.normalize(profile.continuationMode);
        session.harnessPreset = HarnessProfile.normalize(profile.harnessPreset);
        session.clientToolCalling = ClientToolRuntime.normalizeCalling(profile.clientToolCalling);
        session.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
    }

    /** True when the session's frozen copy no longer matches its (enabled) model profile, i.e. the profile was edited. */
    static boolean profileChanged(ChatDatabase.Session session, ChatDatabase.ModelProfile profile) {
        if (session == null || profile == null || !profile.enabled || !profile.id.equals(session.modelProfileId)) return false;
        ChatDatabase.Session current = new ChatDatabase.Session();
        adoptProfile(current, profile);
        return !frozenSignature(current).equals(frozenSignature(session));
    }

    private static String frozenSignature(ChatDatabase.Session s) {
        return String.join("|", s.providerId, s.model, String.valueOf(s.contextTokens), String.valueOf(s.inputTokens), String.valueOf(s.maxOutputTokens),
                ChatDatabase.normalizeModalities(s.inputModalities), ChatDatabase.normalizeModalities(s.outputModalities), ChatTurnOptions.normalizeCapabilities(s.toolCapabilities),
                String.valueOf(s.routeId), ChatDatabase.normalizeProtocol(s.protocol), String.valueOf(s.reasoningWire), String.valueOf(s.serviceTier), String.valueOf(s.sendOutputCap),
                ReasoningPolicy.normalizeThinkingMode(s.thinkingMode), String.valueOf(Math.max(0, s.thinkingBudgetTokens)), ProviderContinuationPolicy.normalize(s.continuationMode),
                HarnessProfile.normalize(s.harnessPreset), ClientToolRuntime.normalizeCalling(s.clientToolCalling), ReasoningPolicy.normalizeLevels(s.reasoningLevels));
    }

    /** Model selection changes the configuration for future turns only; prior assistant rows keep per-turn metadata. */
    private void setSessionModel(String sessionId, String modelProfileId) {
        if (activeTurns.containsKey(sessionId) || startingSessions.contains(sessionId) || preparingSessions.contains(sessionId) || webPreparingStages.containsKey(sessionId) || postProcessingStages.containsKey(sessionId)) {
            throw new IllegalStateException("请等当前回合结束再切换模型");
        }
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
        ChatDatabase.ModelProfile profile = db.getModelProfile(modelProfileId); if (profile == null) throw new IllegalArgumentException("模型不存在");
        if (!profile.enabled) throw new IllegalStateException("这个模型已停用");
        ChatDatabase.Provider provider = db.getProvider(profile.providerId);
        if (provider == null || !secrets.has(profile.providerId)) throw new IllegalStateException("目标账号还没有填写 API Key");
        if (db.draftMedia(sessionId).length() > 0 && !ChatDatabase.supportsModality(profile.inputModalities, "image")) {
            throw new IllegalStateException("目标模型不支持图片，请先移除待发送的图片");
        }
        adoptProfile(session, profile);
        session.subagentMode = ContextMode.MINIMAL.equals(ContextMode.normalize(session.contextMode)) ? HarnessPolicyResolver.SUBAGENT_OFF
                : ContextMode.SMART.equals(ContextMode.normalize(session.contextMode)) ? HarnessPolicyResolver.SUBAGENT_AUTO
                : standardSubagentMode;
        ChatDatabase.Route route = db.getRoute(profile.routeId);
        if (route == null) throw new IllegalStateException("模型使用的路线已删除");
        ReasoningPolicy.validateLevelsForProvider(ChatDatabase.deploymentProvider(provider, route, session), session.reasoningLevels);
        String previousEffort = ReasoningPolicy.normalizeEffort(session.reasoningEffort);
        session.reasoningEffort = ReasoningPolicy.allows(session.reasoningLevels, previousEffort) ? previousEffort : ReasoningPolicy.DEFAULT;
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** Reasoning effort is a durable future-turn setting and is validated against the active model profile. */
    private void setSessionReasoning(String sessionId, String effort) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("请等当前回合结束再调整推理深度");
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
        ChatDatabase.Provider deployment = db.sessionDeployment(session);
        if (deployment == null) throw new IllegalStateException("账号不存在");
        String normalized = ReasoningPolicy.normalizeEffort(effort);
        ReasoningPolicy.validateEffortForProvider(deployment, session.reasoningLevels, normalized);
        session.reasoningEffort = normalized;
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** Web mode is a durable future-turn session setting; it never changes model capability declarations. */
    private void setSessionWebMode(String sessionId, String webMode) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("请等当前回合结束再调整联网模式");
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
        session.webMode = ChatTurnOptions.normalizeWebMode(webMode);
        session.updatedAt = Instant.now().toString();
        db.saveSession(session);
    }

    /** System back follows the same route state as UI navigation; only home exits the Activity. */
    void handleSystemBack(Runnable exitApp) {
        worker.execute(() -> {
            if (!"home".equals(route)) {
                route = ("providers".equals(route) || "settings".equals(route) || "stats".equals(route)) && activeSessionId != null && db.getSession(activeSessionId) != null ? "conversation" : "home";
                db.putSetting("route", route);
                emitView();
            } else if (exitApp != null) exitApp.run();
        });
    }

    private TurnConfig freezeTurnConfig(String sessionId) {
        ChatDatabase.Session session = db.getSession(sessionId);
        if (session == null) throw new IllegalArgumentException("会话不存在");
        ChatDatabase.Provider sharedProvider = db.getProvider(session.providerId);
        String key = secrets.get(session.providerId);
        if (sharedProvider == null || key == null) throw new IllegalStateException("这个账号还没有填写 API Key");
        ChatDatabase.ModelProfile profile = session.modelProfileId == null ? null : db.getModelProfile(session.modelProfileId);
        if (profile == null) throw new IllegalStateException("模型不存在");
        ChatDatabase.Route route = db.getRoute(session.routeId);
        if (route == null) throw new IllegalStateException("这个 Chat 使用的路线已删除，请切换模型");
        config.requireConfirmedHost(route);
        ChatDatabase.Provider provider = ChatDatabase.deploymentProvider(sharedProvider, route, session);
        if (transports.forProtocol(provider.protocol) == null) throw new IllegalStateException("不支持这个账号的协议");
        ProviderEndpoint.normalize(provider.baseUrl, provider.protocol);
        ReasoningPolicy.validateEffortForProvider(provider, session.reasoningLevels, session.reasoningEffort);
        return new TurnConfig(provider, key, ProviderContinuationIdentity.fingerprint(db, provider, session), session, profile, customInstruction, helpersOff,
                resolveHelper(session));
    }

    /**
     * Spec D15: the helper model's frozen deployment for a turn of {@code session}, or why it cannot be used (the helpers
     * then run on the session's model). Its key goes only to its own confirmed host; its account's budget is checked.
     */
    private HelperModel.Resolved resolveHelper(ChatDatabase.Session session) {
        HelperModel.Choice choice = helperChoice;
        if (choice == null || choice.profileId.isEmpty() || choice.profileId.equals(session.modelProfileId)) return HelperModel.NONE;
        ChatDatabase.ModelProfile profile = db.getModelProfile(choice.profileId);
        if (profile == null || !profile.enabled) return HelperModel.unavailable(choice.profileId, HelperModel.MISSING);
        ChatDatabase.Provider account = db.getProvider(profile.providerId);
        ChatDatabase.Route route = db.getRoute(profile.routeId);
        if (account == null || route == null) return HelperModel.unavailable(choice.profileId, HelperModel.MISSING);
        String key = secrets.get(profile.providerId);
        if (key == null) return HelperModel.unavailable(choice.profileId, HelperModel.NO_KEY);
        try { config.requireConfirmedHost(route); } catch (IllegalStateException unconfirmed) { return HelperModel.unavailable(choice.profileId, HelperModel.HOST_UNCONFIRMED); }
        try { if (config.budgetReached(profile.providerId) != null) return HelperModel.unavailable(choice.profileId, HelperModel.OVER_BUDGET); }
        catch (Exception unreadable) { return HelperModel.unavailable(choice.profileId, HelperModel.OVER_BUDGET); }
        try {
            ChatDatabase.Provider view = ChatDatabase.deploymentProvider(account, route, profile);
            if (transports.forProtocol(view.protocol) == null) return HelperModel.unavailable(choice.profileId, HelperModel.UNSUPPORTED);
            ProviderEndpoint.normalize(view.baseUrl, view.protocol);
            ChatDatabase.Session helperSession = ChatDatabase.deploymentSession(profile, ReasoningPolicy.DEFAULT);
            helperSession.contextMode = session.contextMode; helperSession.webMode = session.webMode; helperSession.subagentMode = session.subagentMode;
            TurnConfig helper = new TurnConfig(view, key, ProviderContinuationIdentity.fingerprint(db, view, helperSession), helperSession, profile,
                    customInstruction, helpersOff);
            return new HelperModel.Resolved(choice.profileId, helper, choice.use, "");
        } catch (RuntimeException unusable) {
            return HelperModel.unavailable(choice.profileId, HelperModel.UNSUPPORTED);
        }
    }

    /** Settings view of the helper model (spec D15). */
    private JSONObject helperModelView() throws org.json.JSONException {
        JSONArray use = new JSONArray();
        for (String id : helperChoice.use) use.put(id);
        JSONObject out = new JSONObject().put("modelProfileId", helperChoice.profileId).put("use", use);
        if (!helperChoice.profileId.isEmpty()) {
            ChatDatabase.ModelProfile profile = db.getModelProfile(helperChoice.profileId);
            if (profile == null || !profile.enabled) out.put("missing", true);
            else out.put("modelName", profile.displayName == null || profile.displayName.isEmpty() ? profile.modelId : profile.displayName);
        }
        return out;
    }

    private boolean sessionBusy(String id) {
        return turnControls.containsKey(id) || activeTurns.containsKey(id) || startingSessions.contains(id)
                || preparingSessions.contains(id) || webPreparingStages.containsKey(id) || postProcessingStages.containsKey(id);
    }

    /**
     * A send that was not accepted puts the exact submitted text back as the draft, and the result says so
     * ({@code draftRestored}), so the composer the WebView already cleared can show it again.
     */
    private void sendRejected(String requestId, String sessionId, String message, Exception error) {
        boolean restored = false;
        if (message != null && !message.trim().isEmpty()) {
            try { db.setDraft(sessionId, message); restored = true; } catch (Exception ignored) { }
        }
        JSONObject data = null;
        try {
            data = new JSONObject().put("draftRestored", restored);
            if (error instanceof BudgetReached) data.put("budget", ((BudgetReached) error).detail);
        } catch (Exception ignored) { }
        result(requestId, false, safe(error), data);
        emitView();
    }

    private void saveDraft(String id, String text) {
        if (db.getSession(id) == null) throw new IllegalArgumentException("会话不存在");
        // A running turn may coexist with the user's next-turn draft. Frontend draft generations
        // suppress stale pre-send debounce writes, so Native must not discard legitimate typing here.
        db.setDraft(id, text == null ? "" : text);
    }

    private void deleteSession(String id) {
        String busy = deleteBlocker(id);
        if (busy != null) throw new IllegalStateException(busy);
        if (db.getSession(id) == null) throw new IllegalArgumentException("会话不存在");
        removeSession(id);
        mediaStore.pruneUnreferenced(db.referencedMediaIds());
    }

    /** Multi-select delete: busy sessions are skipped (and reported), missing ones ignored; media is pruned once. */
    private JSONObject deleteSessions(JSONArray ids) throws Exception {
        if (ids.length() > MAX_BATCH_DELETE) throw new IllegalArgumentException("一次最多删除 " + MAX_BATCH_DELETE + " 个会话");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (int i = 0; i < ids.length(); i++) { String id = ids.optString(i, "").trim(); if (!id.isEmpty()) seen.add(id); }
        JSONArray skipped = new JSONArray();
        int deleted = 0;
        for (String id : seen) {
            if (db.getSession(id) == null) continue;
            if (deleteBlocker(id) != null) { skipped.put(id); continue; }
            removeSession(id);
            deleted++;
        }
        if (deleted > 0) mediaStore.pruneUnreferenced(db.referencedMediaIds());
        return new JSONObject().put("deleted", deleted).put("skipped", skipped);
    }

    private String deleteBlocker(String id) {
        if (startingSessions.contains(id) || preparingSessions.contains(id) || webPreparingStages.containsKey(id) || postProcessingStages.containsKey(id)) return "回合正在准备，请稍后再删除";
        if (activeTurns.containsKey(id)) return "请先停止当前回合再删除会话";
        return null;
    }

    private void removeSession(String id) {
        db.deleteSession(id);
        if (id.equals(activeSessionId)) { activeSessionId = null; db.putSetting("activeSessionId", ""); route = "home"; db.putSetting("route", route); }
    }

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
        @Override public boolean wireCaptureEnabled() { return wireCapture; }
        @Override public JSONObject takeRetryOf(String sessionId) { return pendingRetries.remove(sessionId); }
        @Override public String takeAnswerTo(String sessionId) { return pendingAnswers.remove(sessionId); }
        @Override public String takeKeptDraft(String sessionId) { String kept = pendingKeptDrafts.remove(sessionId); return kept == null ? "" : kept; }
        @Override public void releaseTurn(String id) {
            activeTurns.remove(id);
            TurnControl control = turnControls.remove(id);
            startingSessions.remove(id);
            preparingSessions.remove(id);
            webPreparingStages.remove(id);
            postProcessingStages.remove(id);
            lastStreamViewNanos.remove(id);
            host.setTurnRuntimeActive(id, false);
            notifySettled(id, control);
            if (control != null && !control.isCancelled()) worker.execute(() -> autoTitle(id));
            ChatCoordinator.this.emitView();
        }
    }

    /** The exchange a title is made from: the first question, the latest answered question and its answer. */
    private static final class Exchange {
        String firstQuestion = "", question = "", answer = "", turnId;
    }

    /** Null until the session has a finished, non-empty answer. */
    static Exchange latestExchange(JSONArray items) {
        Exchange out = new Exchange();
        String lastQuestion = "";
        boolean answered = false;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("kind")) || item.optBoolean("inherited", false)) continue;
            String text = item.optString("text", "");
            if ("user".equals(item.optString("role"))) {
                if (out.firstQuestion.isEmpty()) out.firstQuestion = text;
                lastQuestion = text;
            } else if ("assistant".equals(item.optString("role")) && !item.optBoolean("streaming", false)
                    && !item.optBoolean("incomplete", false) && !text.trim().isEmpty()) {
                out.question = lastQuestion; out.answer = text; out.turnId = item.optString("turnId", null); answered = true;
            }
        }
        return answered ? out : null;
    }

    /** After a turn completes: names the session while its title is still automatic (default or placeholder). */
    private void autoTitle(String sessionId) {
        try {
            ChatDatabase.Session s = db.getSession(sessionId);
            if (!SessionTitles.automatic || s == null || !"completed".equals(s.state) || namedSessions.contains(sessionId)) return;
            Exchange exchange = latestExchange(db.timeline(sessionId));
            if (exchange == null || !SessionTitles.isAutomatic(s.title, exchange.firstQuestion)) return;
            nameSession(null, sessionId, s.title, exchange);
        } catch (Exception ignored) { /* naming is best effort; the placeholder stays */ }
    }

    /** "重新命名（自动）": names the session now from its latest exchange, unless the title changes meanwhile. */
    private void autoTitleNow(String requestId, String sessionId) throws Exception {
        ChatDatabase.Session s = db.getSession(sessionId); if (s == null) throw new IllegalArgumentException("会话不存在");
        Exchange exchange = latestExchange(db.timeline(sessionId));
        if (exchange == null) throw new IllegalStateException("还没有完成的回答，无法生成标题");
        nameSession(requestId, sessionId, s.title, exchange);
    }

    /**
     * One hidden light call with the session's own model (its lowest reasoning level), off the intent worker.
     * The title is applied only if the session still has {@code expectedTitle}; the call is recorded as a
     * "title" stage on the answered turn and its tokens and cost are added to that turn's helper usage.
     */
    private void nameSession(String requestId, String sessionId, String expectedTitle, Exchange exchange) throws Exception {
        TurnConfig session = freezeTurnConfig(sessionId);
        boolean onHelper = session.helper.uses(HelperModel.TITLE);
        TurnConfig c = onHelper ? session.helper.config : session; // spec D15: naming uses the helper model by default
        TurnUsage.Billing billing = onHelper ? session.helper.billing() : null;
        ChatTransport transport = transports.forProtocol(c.provider.protocol);
        if (transport == null) throw new IllegalStateException("不支持这个账号的协议");
        CanonicalPrompt prompt = SessionTitles.prompt(exchange.firstQuestion, exchange.question, exchange.answer);
        namedSessions.add(sessionId);
        titleWorker.execute(() -> {
            TurnUsage usage = new TurnUsage();
            try { usage.setPricing(ModelPricing.normalize(new JSONObject(session.pricing))); } catch (Exception unpriced) { usage.setPricing(null); }
            long startedAt = System.currentTimeMillis(), started = System.nanoTime();
            String title = null;
            try {
                ModelPreflight.Output output = ModelPreflight.runLight(usage.track(transport, TurnUsage.Kind.AUXILIARY, SessionTitles.STAGE, billing),
                        c.provider, c.apiKey, c.model, prompt, SessionTitles.OUTPUT_CAP, SessionTitles.TIMEOUT_MS, null);
                title = output == null ? null : SessionTitles.clean(output.text);
            } catch (Exception ignored) { }
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            JSONObject delta = new JSONObject();
            try { usage.project(delta); } catch (Exception ignored) { }
            String failure = usage.stageFailure(SessionTitles.STAGE);
            String named = title;
            worker.execute(() -> {
                boolean applied = named != null && db.replaceTitleIf(sessionId, expectedTitle, named);
                try {
                    JSONObject spanUsage = new JSONObject().put("calls", Math.max(1, delta.optInt("auxiliaryCallCount", 1)));
                    if (delta.has("auxiliaryInputTokens")) spanUsage.put("inputTokens", delta.optLong("auxiliaryInputTokens"));
                    if (delta.has("auxiliaryOutputTokens")) spanUsage.put("outputTokens", delta.optLong("auxiliaryOutputTokens"));
                    JSONObject span = new JSONObject().put("stage", SessionTitles.STAGE).put("status", named != null ? TurnTrace.OK : TurnTrace.FAILED)
                            .put("startedAtMs", startedAt).put("durationMs", durationMs).put("usage", spanUsage)
                            .put("detail", new JSONObject().put("applied", applied).put("manual", requestId != null)
                                    .put("model", c.modelName).put("helperModel", onHelper));
                    if (named == null) span.put("reason", failure == null ? "no_title" : failure);
                    db.addHelperToTurn(exchange.turnId, span, delta);
                } catch (Exception ignored) { }
                emitView();
                if (requestId != null) result(requestId, applied, applied ? null : named == null ? "没能生成标题，请稍后再试" : "标题已被修改，未覆盖");
            });
        });
    }

    /** A turn that ended on its own (the user did not stop it) is reported to the host with its outcome. */
    private void notifySettled(String id, TurnControl control) {
        if (!notifyInBackground || control == null || control.isCancelled()) return;
        try {
            ChatDatabase.Session s = db.getSession(id);
            if (s != null) host.turnSettled(id, s.title, "failed".equals(s.state));
        } catch (Exception ignored) { }
    }

    /** A tapped result notification opens its session. */
    void openFromNotification(String sessionId) {
        worker.execute(() -> {
            if (sessionId != null && db.getSession(sessionId) != null) activate(sessionId);
            emitView();
        });
    }

    private void interrupt(String id) {
        TurnControl control = turnControls.get(id);
        if (control != null) control.cancel();
        ChatTransport.Turn turn = activeTurns.get(id);
        if (turn != null) try { turn.cancel(); } catch (Exception ignored) { }
        emitStreamingView(id);
    }

    /**
     * Re-answers one question as a new version under it, in this session (spec H5, D9): the chosen model and context
     * mode become the session's settings (shown in the header, used by later turns too), then the question is answered
     * again with only the history before it. Answers to the same question can then be switched and compared.
     */
    private void rerunTurn(String requestId, String sessionId, int userSeq, String modelProfileId, String contextMode, boolean budgetConfirmed) throws Exception {
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
        if (sessionBusy(sessionId)) throw new IllegalStateException("请等当前回合结束再重答");
        JSONObject question = messageAt(sessionId, userSeq);
        if (!"user".equals(question.optString("role")) || question.optBoolean("inherited", false)) throw new IllegalArgumentException("只能对本会话的提问重答");
        String questionKey = db.itemKeyAt(sessionId, userSeq);
        if (!modelProfileId.isEmpty() && !modelProfileId.equals(session.modelProfileId)) setSessionModel(sessionId, modelProfileId);
        if (!contextMode.isEmpty() && !ContextMode.normalize(contextMode).equals(ContextMode.normalize(session.contextMode))) {
            String mode = ContextMode.normalize(contextMode);
            db.setContextMode(sessionId, mode, ContextMode.MINIMAL.equals(mode) ? HarnessPolicyResolver.SUBAGENT_OFF
                    : ContextMode.SMART.equals(mode) ? HarnessPolicyResolver.SUBAGENT_AUTO : standardSubagentMode);
        }
        answerAgain(requestId, sessionId, questionKey, question.optString("text", ""), new JSONObject().put("budgetConfirmed", budgetConfirmed));
    }

    /** Starts a turn that answers an existing question (no new message, the composer untouched). */
    private void answerAgain(String requestId, String sessionId, String questionKey, String text, JSONObject send) {
        ChatDatabase.Session session = db.getSession(sessionId);
        String keptDraft = session == null || session.draft == null ? "" : session.draft;
        pendingAnswers.put(sessionId, questionKey);
        startTurn(requestId, sessionId, text, send, keptDraft, "");
    }

    private void createLocalBranch(String sourceSessionId, Integer throughSeq) throws Exception {
        ChatDatabase.Session source = db.getSession(sourceSessionId); if (source == null) throw new IllegalArgumentException("原会话不存在");
        if (sessionBusy(sourceSessionId)) throw new IllegalStateException("请等当前回合结束再创建分支");
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(sourceSessionId, throughSeq);
        ChatDatabase.Session branch = db.branchSession(source, "分支 · " + source.title, snapshot.id, sourceSessionId);
        inheritCheckpoint(source, branch.id);
        activate(branch.id);
    }

    /**
     * Spec H3, D13: the source's compaction summary comes along when everything it covers lies before the branch
     * point, i.e. the branch's history starts with exactly the items it covers; otherwise the branch compacts on its own.
     */
    private void inheritCheckpoint(ChatDatabase.Session source, String branchId) throws Exception {
        ContextCheckpoint checkpoint = ContextCheckpoint.fromStored(source.contextCheckpoint);
        if (checkpoint == null || checkpoint.coverage.isEmpty() || source.checkpointCoveredItems <= 0) return;
        ChatDatabase.Session branch = db.getSession(branchId); if (branch == null) return;
        JSONArray history = contexts.neutralHistory(branch);
        if (source.checkpointCoveredItems > history.length() || !checkpoint.covers(history, source.checkpointCoveredItems)) return;
        db.setContextCheckpoint(branchId, source.contextCheckpoint, source.checkpointCoveredItems);
    }

    /** Edit (spec H5): the path ends before the message and its text and images go back to the composer; sending adds its new version. */
    private void editMessageInPlace(String sessionId, int localSeq) throws Exception {
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
        if (sessionBusy(sessionId)) throw new IllegalStateException("请先停止当前回合再编辑历史");
        JSONObject selected = messageAt(sessionId, localSeq);
        if (!"user".equals(selected.optString("role"))) throw new IllegalArgumentException("只能编辑用户消息");
        String text = selected.optString("text", "");
        JSONArray attachments = selected.optJSONArray("attachments");
        db.moveLeafBefore(sessionId, localSeq);
        db.setDraft(sessionId, text);
        db.replaceDraftMedia(sessionId, attachments == null ? new JSONArray() : new JSONArray(attachments.toString()));
        activate(sessionId);
    }

    /**
     * Retry (spec T7): when the path's last turn failed or did not finish, its question is answered again with the
     * session's current settings, as a new version; the failed version stays and the new turn links to it. The
     * composer (text and images) is left alone.
     */
    private void retryTurn(String requestId, String sessionId, boolean budgetConfirmed) throws Exception {
        ChatDatabase.Session session = db.getSession(sessionId); if (session == null) throw new IllegalArgumentException("会话不存在");
        if (sessionBusy(sessionId)) throw new IllegalStateException("这个会话正在生成，请等当前回合结束");
        JSONObject question = retryTarget(db.timeline(sessionId));
        if (question == null) throw new IllegalStateException("最后一轮已经完成，不需要重试");
        int questionSeq = question.optInt("localSeq");
        JSONObject send = new JSONObject().put("budgetConfirmed", budgetConfirmed);
        String failedTurnId = db.turnAt(sessionId, questionSeq);
        if (failedTurnId != null) send.put("retry", new JSONObject().put("retryOf", failedTurnId).put("attempt", db.attemptsAt(sessionId, questionSeq) + 1));
        answerAgain(requestId, sessionId, db.itemKeyAt(sessionId, questionSeq), question.optString("text", ""), send);
    }

    /** The last local question when its turn failed or did not finish (error row, unfinished or missing answer), else null. */
    static JSONObject retryTarget(JSONArray items) {
        if (items == null) return null;
        for (int i = items.length() - 1; i >= 0; i--) {
            JSONObject item = items.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("kind")) || !"user".equals(item.optString("role"))) continue;
            if (item.optBoolean("inherited", false) || item.optInt("localSeq", -1) < 1) return null;
            boolean answered = false;
            for (int j = i + 1; j < items.length(); j++) {
                JSONObject after = items.optJSONObject(j);
                if (after == null) continue;
                if ("error".equals(after.optString("kind"))) return item;
                if ("message".equals(after.optString("kind")) && "assistant".equals(after.optString("role"))) {
                    if (after.optBoolean("incomplete", false) || after.optBoolean("streaming", false)) return item;
                    answered = true;
                }
            }
            return answered ? null : item;
        }
        return null;
    }

    /** A turn refused because the account reached a budget; the WebView asks and resends with budgetConfirmed. */
    static final class BudgetReached extends IllegalStateException {
        final JSONObject detail;
        BudgetReached(JSONObject detail) {
            super(budgetMessage(detail));
            this.detail = detail;
        }
    }

    static String budgetMessage(JSONObject d) {
        String sign = "CNY".equals(d.optString("currency")) ? "¥" : "$";
        return ("monthly".equals(d.optString("period")) ? "本月" : "今天") + "的估算花费已达预算（" + sign + String.format(java.util.Locale.ROOT, "%.2f", d.optDouble("spent"))
                + " / " + sign + String.format(java.util.Locale.ROOT, "%.2f", d.optDouble("limit")) + "）";
    }

    private interface Check { JSONObject run() throws Exception; }

    /**
     * Runs a network check off the intent worker; the result (and a fresh view) come back on the worker.
     * When every check thread is busy the request is refused at once instead of queued: a queued check could
     * start after the page has stopped waiting, so each check's deadline is only its own network timeout.
     */
    private void checkAsync(String requestId, Check check) {
        if (runningChecks.incrementAndGet() > MAX_RUNNING_CHECKS) {
            runningChecks.decrementAndGet();
            result(requestId, false, CHECKS_BUSY, null);
            return;
        }
        try {
            guardWorker.execute(() -> {
                JSONObject data = null; String error = null;
                try { data = check.run(); } catch (Exception failure) { error = safe(failure); }
                finally { runningChecks.decrementAndGet(); }
                JSONObject done = data; String failed = error;
                worker.execute(() -> { emitView(); result(requestId, failed == null, failed, done); });
            });
        } catch (RuntimeException rejected) {
            runningChecks.decrementAndGet();
            throw rejected;
        }
    }

    static final String CHECKS_BUSY = "已有 " + MAX_RUNNING_CHECKS + " 项检查在进行，请稍后再试";

    /**
     * "回到这里" (spec H5): on an answer, the path ends at it; on a question, before it. Nothing is removed: what came
     * after stays as another path, reachable through "回到最新" or the version switch.
     */
    private void returnToMessage(String sessionId, int localSeq) throws Exception {
        if (db.getSession(sessionId) == null) throw new IllegalArgumentException("会话不存在");
        if (sessionBusy(sessionId)) throw new IllegalStateException("请先停止当前回合");
        JSONObject message = messageAt(sessionId, localSeq);
        if ("user".equals(message.optString("role"))) db.moveLeafBefore(sessionId, localSeq); else db.moveLeafTo(sessionId, localSeq);
        activate(sessionId);
    }

    /** Version switch (spec G3): the path goes through the chosen version to its newest message. */
    private void selectVersion(String sessionId, int localSeq) {
        if (db.getSession(sessionId) == null) throw new IllegalArgumentException("会话不存在");
        if (sessionBusy(sessionId)) throw new IllegalStateException("请等当前回合结束再切换版本");
        db.selectVersion(sessionId, localSeq);
        activate(sessionId);
    }

    private JSONObject messageAt(String sessionId, int localSeq) {
        if (localSeq < 1) throw new IllegalArgumentException("invalid localSeq");
        JSONArray timeline = db.timeline(sessionId);
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject item = timeline.optJSONObject(i);
            if (item == null || item.optInt("localSeq", -1) != localSeq) continue;
            if (!"message".equals(item.optString("kind")) || item.optBoolean("streaming", false)) throw new IllegalArgumentException("这条消息还没有生成完");
            String role = item.optString("role", "");
            if (!("user".equals(role) || "assistant".equals(role))) throw new IllegalArgumentException("这条消息不能用作分支起点");
            return item;
        }
        throw new IllegalArgumentException("找不到这条消息");
    }

    private void recordVerificationFeedback(String sessionId, int localSeq, String label) {
        if (sessionBusy(sessionId)) throw new IllegalStateException("请等当前回合结束再评价校验");
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
        } catch (Exception error) { host.showMessage("流式更新失败：" + safe(error)); }
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
            view.put("providers", config.providersView());
            java.util.List<ChatDatabase.Session> sessionRows = db.listSessions();
            ViewLookups lookups = new ViewLookups();
            // A session that never had a message is not listed; one with an unsent draft is listed as that draft.
            java.util.Set<String> blanks = db.blankSessionIds();
            JSONArray sessions = new JSONArray();
            for (ChatDatabase.Session s : sessionRows) {
                if (!blanks.contains(s.id)) { sessions.put(sessionJson(s, false, lookups)); continue; }
                String draft = s.draft == null ? "" : s.draft.replaceAll("\\s+", " ").trim();
                boolean images = s.draftMedia != null && !s.draftMedia.trim().isEmpty() && !"[]".equals(s.draftMedia.trim());
                if (draft.isEmpty() && !images) continue;
                sessions.put(sessionJson(s, false, lookups).put("blank", true).put("draft", draft.length() > 80 ? draft.substring(0, 80) : draft));
            }
            view.put("sessions", sessions);
            view.put("usageToday", usageToday(sessionRows));
            if (activeSessionId != null) {
                ChatDatabase.Session active = db.getSession(activeSessionId);
                if (active != null) {
                    view.put("activeLocalSessionId", activeSessionId);
                    // Route-scoped projection: non-conversation screens never pay to serialize/bridge the full timeline.
                    if ("conversation".equals(route)) view.put("activeSession", sessionJson(active, true, lookups));
                }
            }
            view.put("ui", new JSONObject().put("pendingRequestIds", new JSONArray()).put("showReasoning", showReasoning)
                    .put("crossChatMemoryEnabled", crossChatMemoryEnabled).put("wireCapture", wireCapture).put("notifyInBackground", notifyInBackground).put("customInstruction", customInstruction)
                    .put("helpersOff", HelperSwitches.toJson(helpersOff)).put("helperModel", helperModelView())
                    .put("defaultContextMode", defaultContextMode).put("defaultWebMode", defaultWebMode)
                    .put("standardSubagentMode", standardSubagentMode).put("mascot", mascot.toJson()));
            host.dispatch(new JSONObject().put("version", 1).put("type", "view.full").put("revision", ++revision).put("view", view));
        } catch (Exception error) { host.showMessage("界面更新失败：" + safe(error)); }
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
            copy.remove(ToolHistory.FIELD); // model input (spec T3), not display: results can be large
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
        JSONArray local = withVersions(withTurnUsage(db.timeline(s.id), db.turns(s.id)), db.versions(s.id));
        for (int i = 0; i < local.length(); i++) {
            JSONObject item = local.optJSONObject(i); if (item == null) continue;
            JSONObject visible = new JSONObject(item.toString());
            // Opaque provider continuation can contain encrypted reasoning/signatures and is native-only.
            visible.remove("providerContinuation");
            visible.remove(ToolHistory.FIELD); // model input (spec T3), not display: results can be large
            out.put(visible);
        }
        return new DisplayTimeline(out, inheritedDisplay.omittedInheritedItems);
    }

    /**
     * Usage is stored on the turn record, not in the timeline. The view keeps its long-standing shape:
     * a {kind: usage} row right after the turn's assistant reply (or after the user row when the turn
     * produced no reply), so the frontend attaches it to the reply it belongs to.
     */
    static JSONArray withTurnUsage(JSONArray timeline, JSONArray turns) throws Exception {
        // Since v3 a question can have several turns (versions): a turn is found by its answer (turnId on the
        // answer row, or on the error row of a turn that left no answer), not by its question.
        java.util.HashMap<String, JSONObject> byTurn = new java.util.HashMap<>();
        for (int i = 0; turns != null && i < turns.length(); i++) { JSONObject turn = turns.optJSONObject(i); if (turn != null) byTurn.put(turn.optString("turnId"), turn); }
        java.util.HashSet<String> placed = new java.util.HashSet<>();
        JSONArray out = new JSONArray();
        for (int i = 0; timeline != null && i < timeline.length(); i++) {
            JSONObject item = timeline.optJSONObject(i); if (item == null) continue;
            boolean answer = "message".equals(item.optString("kind", "message")) && "assistant".equals(item.optString("role", ""));
            boolean error = "error".equals(item.optString("kind"));
            JSONObject turn = (answer || error) ? byTurn.get(item.optString("turnId", "")) : null;
            if (turn != null && answer) {
                item = new JSONObject(item.toString());
                if (turn.has("rating")) item.put("rating", turn.optInt("rating"));
                if (turn.optInt("answers", 1) > 1) item.put("answers", turn.optInt("answers"));
            }
            out.put(item);
            if (turn == null || placed.contains(turn.optString("turnId"))) continue;
            JSONObject usage = turn.optJSONObject("usage");
            if (usage == null || usage.length() == 0) continue;
            out.put(new JSONObject(usage.toString()).put("kind", "usage").put("turnId", turn.optString("turnId")));
            placed.add(turn.optString("turnId"));
        }
        return out;
    }

    /** Spec G3: "‹ i/n ›" on the messages that have other versions ({index, count, prevSeq?, nextSeq?}). */
    static JSONArray withVersions(JSONArray timeline, JSONObject versions) throws Exception {
        if (versions == null || versions.length() == 0) return timeline;
        JSONArray out = new JSONArray();
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject item = timeline.optJSONObject(i); if (item == null) continue;
            JSONObject version = versions.optJSONObject(String.valueOf(item.optInt("localSeq", -1)));
            if (version != null) item = new JSONObject(item.toString()).put("version", version);
            out.put(item);
        }
        return out;
    }

    /**
     * Rows read once per view build. Sessions share a handful of accounts, profiles and routes, so the session
     * list would otherwise re-read the same rows for every session on every view push.
     */
    private final class ViewLookups {
        private final java.util.Map<String, Object> rows = new java.util.HashMap<>();
        @SuppressWarnings("unchecked")
        private <T> T row(String kind, String id, java.util.function.Function<String, T> read) {
            if (id == null) return null;
            String key = kind + ':' + id;
            if (!rows.containsKey(key)) rows.put(key, read.apply(id));
            return (T) rows.get(key);
        }
        ChatDatabase.Provider provider(String id) { return row("provider", id, db::getProvider); }
        ChatDatabase.ModelProfile profile(String id) { return row("profile", id, db::getModelProfile); }
        ChatDatabase.Snapshot snapshot(String id) { return row("snapshot", id, db::getSnapshot); }
        /** Same result as {@link ChatDatabase#sessionDeployment}, from the shared rows. */
        ChatDatabase.Provider deployment(ChatDatabase.Session s) {
            ChatDatabase.Provider shared = provider(s.providerId);
            ChatDatabase.Route route = row("route", s.routeId, db::getRoute);
            if (shared == null || route == null) return null;
            try { return ChatDatabase.deploymentProvider(shared, route, s); }
            catch (RuntimeException invalid) { return null; }
        }
    }

    private JSONObject sessionJson(ChatDatabase.Session s, boolean detail, ViewLookups lookups) throws Exception {
        ChatDatabase.Provider p = lookups.provider(s.providerId);
        ChatDatabase.ModelProfile modelProfile = lookups.profile(s.modelProfileId);
        ChatDatabase.Provider deployment = p == null ? null : lookups.deployment(s);
        ChatDatabase.Snapshot originSnap = lookups.snapshot(s.originSnapshotId);
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
                .put("modelName", TurnConfig.describes(modelProfile, s) ? modelProfile.displayName : s.model)
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
        o.put("hasContextCheckpoint", ContextCheckpoint.fromStored(s.contextCheckpoint) != null);
        if (originSnap != null) o.put("origin", new JSONObject().put("snapshotId", originSnap.id).put("sourceSessionId", originSnap.sourceSessionId).put("sourceTitle", originSnap.sourceTitle).put("createdAt", originSnap.createdAt).put("itemCount", originSnap.itemCount).put("sourceItemCount", originSnap.sourceItemCount).put("truncated", originSnap.truncated));
        if (detail) {
            DisplayTimeline display = displayTimeline(s);
            o.put("messages", display.items).put("composerDraft", s.draft == null ? "" : s.draft)
                    .put("draftMedia", db.draftMedia(s.id)).put("canAttachImage", ChatDatabase.supportsModality(s.inputModalities, "image") && !active)
                    .put("canSend", p != null && secrets.has(s.providerId) && !active).put("canInterrupt", turnControls.containsKey(s.id))
                    .put("canConfigureTurn", !active).put("contextPreparing", preparing);
            if (display.omittedInheritedItems > 0) o.put("displayOmittedInheritedItems", display.omittedInheritedItems);
            Integer resume = db.resumeSeq(s.id);
            if (resume != null && !active) o.put("resumeLocalSeq", resume);
            if (!active) {
                JSONObject retry = retryTarget(display.items);
                if (retry != null) o.put("retryLocalSeq", retry.optInt("localSeq"));
                if (profileChanged(s, modelProfile)) o.put("profileChanged", true);
            }
            try { o.put("contextBudgetTokens", CONTEXT_BUDGETS.effectiveInputBudget(s.contextTokens, s.inputTokens, s.maxOutputTokens)); }
            catch (IllegalArgumentException unusable) { /* no usable input window: the send path reports it */ }
            if (webPreparing) o.put("webPreparingStage", webPreparingStage);
            if (postProcessing) o.put("postProcessingStage", postProcessingStage);
        }
        return o;
    }

    private void result(String requestId, boolean ok, String error) { result(requestId, ok, error, null); }
    private void result(String requestId, boolean ok, String error, JSONObject data) { if (requestId == null || requestId.isEmpty()) return; try { JSONObject e = new JSONObject().put("version", 1).put("type", "intent.result").put("requestId", requestId).put("ok", ok); if (error != null) e.put("error", new JSONObject().put("summary", error)); if (data != null) e.put("data", data); host.dispatch(e); } catch (Exception ignored) { } }
    private static String required(JSONObject p, String key) { String v = p.optString(key, "").trim(); if (v.isEmpty()) throw new IllegalArgumentException("missing " + key); return v; }
    private static int requiredSeq(JSONObject p) { if (!p.has("localSeq")) throw new IllegalArgumentException("missing localSeq"); int seq = p.optInt("localSeq", -1); if (seq < 1) throw new IllegalArgumentException("invalid localSeq"); return seq; }
    private static String safe(Exception e) { String m = e.getMessage(); return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m; }
    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String empty(String v) { return v == null || v.isEmpty() ? null : v; }
    private static String bounded(String value, int max, String label) { String v = value == null ? "" : value.trim(); if (v.isEmpty()) throw new IllegalArgumentException(label + "不能为空"); if (v.length() > max) throw new IllegalArgumentException(label + "不能超过 " + max + " 字"); return v; }
    private static String validRoute(String value) { String v = value == null ? "" : value.trim(); if (!("home".equals(v) || "conversation".equals(v) || "providers".equals(v) || "settings".equals(v) || "stats".equals(v))) return "home"; return v; }
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

    @Override public void close() { closed = true; for (TurnControl control : turnControls.values()) try { control.cancel(); } catch (Exception ignored) { } turnControls.clear(); for (ChatTransport.Turn t : activeTurns.values()) try { t.cancel(); } catch (Exception ignored) { } activeTurns.clear(); startingSessions.clear(); preparingSessions.clear(); webPreparingStages.clear(); postProcessingStages.clear(); lastStreamViewNanos.clear(); pipeline.webService.close(); pipeline.compactions.close(); transports.close(); streamProjectionScheduler.shutdownNow(); preparationWorker.shutdownNow(); clientToolWorker.shutdownNow(); guardWorker.shutdownNow(); titleWorker.shutdownNow(); worker.shutdownNow(); }
}
