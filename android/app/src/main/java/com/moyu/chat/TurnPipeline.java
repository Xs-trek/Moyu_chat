package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * One accepted user turn as explicit stages:
 * <pre>
 * accept → context → route → checkpoint → web plan → tools → web retrieve → generate → verify/repair → finish
 * </pre>
 * Preparation stages run synchronously on the preparation pool. Generation advances through provider
 * callbacks ({@link ProviderRoundLoop}). Verification runs on the preparation pool after the last
 * round. Every stage records a {@link TurnTrace} span, including the reason it was skipped, and every
 * helper model call is attributed to its stage in {@link TurnUsage}.
 */
final class TurnPipeline {
    /** Coordinator-owned runtime the pipeline may touch: executors, busy flags and view emission. */
    interface Env {
        ExecutorService authority();
        ExecutorService preparation();
        ExecutorService clientTools();
        ScheduledExecutorService projections();
        boolean isClosed();
        void showMessage(String text);
        void emitView();
        void emitStreamingView(String sessionId);
        void emitStreamingPatch(String sessionId, JSONObject liveItem);
        void setStarting(String sessionId, boolean starting);
        void setPreparing(String sessionId, boolean preparing);
        void setWebStage(String sessionId, String stage);
        /** @return whether a Web stage was showing. */
        boolean clearWebStage(String sessionId);
        void setPostStage(String sessionId, String stage);
        boolean hasActiveTurn(String sessionId);
        void putActiveTurn(String sessionId, ChatTransport.Turn turn);
        ChatTransport.Turn removeActiveTurn(String sessionId);
        boolean ownsTurn(String sessionId, TurnControl control);
        /** The turn this session's next turn re-answers (from turn.rerun), or null; consumed on read. */
        String takeRerunOf(String sessionId);
        /** What the composer keeps once this turn starts (a retry keeps text typed meanwhile), or ""; consumed on read. */
        default String takeKeptDraft(String sessionId) { return ""; }
        /** Whether redacted request / response text is recorded for the protocol inspector. */
        boolean wireCaptureEnabled();
        /** Clears every busy flag for the session, releases the runtime lease and emits a full view. */
        void releaseTurn(String sessionId);
    }

    final Env env;
    final ChatDatabase db;
    final ContextBuilder contexts;
    final ChatMediaStore mediaStore;
    private final ChatTransportRegistry transports;
    private final TaskRouter taskRouter = new TaskRouter();
    private final ContextCheckpointService checkpointService = new ContextCheckpointService();
    private final SmartVerifierService smartVerifier = new SmartVerifierService();
    final FocusedSubagentService focusedSubagent = new FocusedSubagentService();
    private final WebOrchestrator webOrchestrator = new WebOrchestrator();
    final MoyuWebService webService = new MoyuWebService();

    TurnPipeline(Env env, ChatDatabase db, ContextBuilder contexts, ChatMediaStore mediaStore, ChatTransportRegistry transports) {
        this.env = env; this.db = db; this.contexts = contexts; this.mediaStore = mediaStore; this.transports = transports;
    }

    /** Runs the preparation stages and starts generation; terminal states are written through {@link #finish}. */
    void run(String id, String text, TurnConfig turnConfig, TurnControl turnControl) throws Exception {
        TurnRun r = accept(id, text, turnConfig, turnControl);
        if (r == null) return;
        if (!buildContext(r) || !prepareContext(r) || !planWeb(r) || !selectTools(r) || !retrieveWeb(r)) return;
        new ProviderRoundLoop(this, r).start();
    }

    // ---------- accept ----------

    private TurnRun accept(String id, String text, TurnConfig turnConfig, TurnControl turnControl) throws Exception {
        // trim is an emptiness check only; the stored/prompted/sent text stays byte-for-byte original.
        String input = text == null ? "" : text;
        ChatDatabase.Session session = db.getSession(id); if (session == null) throw new IllegalArgumentException("会话不存在");
        JSONArray pendingMedia = db.draftMedia(id);
        if (input.trim().isEmpty() && pendingMedia.length() == 0) return null;
        if (turnConfig == null || turnConfig.provider == null || turnConfig.apiKey == null) throw new IllegalStateException("turn configuration missing");
        if (pendingMedia.length() > 0 && !ChatDatabase.supportsModality(turnConfig.inputModalities, "image")) throw new IllegalStateException("当前模型没有声明图片输入能力");
        if (env.hasActiveTurn(id)) throw new IllegalStateException("这个会话正在生成，请等当前回合结束");
        TurnRun r = new TurnRun(id, input, turnConfig, turnControl, session, pendingMedia);
        TurnTrace.Span span = r.trace().begin(TurnTrace.ACCEPT);
        ChatDatabase.Provider provider = r.provider;
        ChatTransport rawTransport = transports.forProtocol(provider.protocol); if (rawTransport == null) throw new IllegalStateException("不支持这个账号的协议");
        TurnUsage usage = turnControl.usage;
        r.mainTransport = usage.track(rawTransport, TurnUsage.Kind.MAIN, TurnTrace.GENERATE);
        r.routeTransport = usage.track(rawTransport, TurnUsage.Kind.AUXILIARY, TurnTrace.ROUTE);
        r.checkpointTransport = usage.track(rawTransport, TurnUsage.Kind.AUXILIARY, TurnTrace.CHECKPOINT);
        r.webPlanTransport = usage.track(rawTransport, TurnUsage.Kind.AUXILIARY, TurnTrace.WEB_PLAN);
        r.verifyTransport = usage.track(rawTransport, TurnUsage.Kind.AUXILIARY, TurnTrace.VERIFY);
        r.repairTransport = usage.track(rawTransport, TurnUsage.Kind.AUXILIARY, TurnTrace.REPAIR);
        r.subagentTransport = usage.track(rawTransport, TurnUsage.Kind.SUBAGENT, "subagent");
        // Resolve only stable session policy before durable mutation. Once the user taps Send the
        // message belongs in the transcript and the composer draft must be cleared even if later
        // context preparation, Web retrieval, or provider startup fails.
        ChatDatabase.Snapshot originSnapshot = session.originSnapshotId == null ? null : db.getSnapshot(session.originSnapshotId);
        r.contextPolicy = ContextMode.resolve(session, originSnapshot);
        r.adaptivePolicy = HarnessPolicyResolver.resolve(session);
        r.taskProfile = TaskProfile.fromStored(session.taskProfile);
        r.needsRouter = r.contextPolicy.routerEnabled && r.taskProfile == null;
        // Deterministic Standard/Smart compaction cannot be disabled by the model-authored TaskProfile.
        r.checkpointAllowed = r.contextPolicy.checkpointEnabled;
        r.mayPrepare = r.needsRouter || r.checkpointAllowed;

        // Request behavior comes from the immutable TurnConfig, not mutable UI/session/profile state.
        provider.thinkingMode = turnConfig.thinkingMode;
        provider.thinkingBudgetTokens = turnConfig.thinkingBudgetTokens;
        r.allowProviderContinuation = ProviderContinuationPolicy.replayAllowed(r.contextPolicy, session, provider);
        JSONObject user = new JSONObject().put("kind", "message").put("role", "user").put("text", input).put("createdAt", Instant.now().toString());
        if (pendingMedia.length() > 0) user.put("attachments", new JSONArray(pendingMedia.toString()));
        r.streamKey = "assistant:live:" + id + ":" + UUID.randomUUID();
        r.live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "")
                .put("streaming", true).put("streamStage", r.mayPrepare ? "preparing" : "connecting").put("createdAt", Instant.now().toString())
                .put("providerId", turnConfig.providerId).put("providerName", provider.displayName)
                .put("modelProfileId", turnConfig.modelProfileId == null ? JSONObject.NULL : turnConfig.modelProfileId)
                .put("model", turnConfig.model)
                .put("modelName", turnConfig.modelName)
                .put("reasoningAvailable", turnConfig.reasoningAvailable)
                .put("reasoningEffort", turnConfig.reasoningEffort)
                .put("contextMode", turnConfig.contextMode)
                .put("effectiveContextMode", r.contextPolicy.effectiveLabel);
        String turnId = UUID.randomUUID().toString();
        turnControl.turnId = turnId;
        if (env.wireCaptureEnabled()) usage.setWireRecorder((stage, callIndex, direction, wireText) -> db.addTurnPayload(turnId, callIndex, stage, direction, wireText));
        r.live.put("turnId", turnId);
        JSONObject config = turnConfigJson(r);
        String rerunOf = env.takeRerunOf(id);
        if (rerunOf != null) config.put("rerunOf", rerunOf);
        db.beginTurn(id, "user:" + UUID.randomUUID(), user, r.streamKey, r.live, turnId, config, env.takeKeptDraft(id));
        turnControl.usageState = new JSONObject().put("createdAt", Instant.now().toString());
        env.setStarting(id, true);
        r.requestStartedNanos = System.nanoTime();
        turnControl.requestStartedNanos = r.requestStartedNanos;
        r.trace().end(span, TurnTrace.OK, new JSONObject().put("contextMode", turnConfig.contextMode)
                .put("effectiveContextMode", r.contextPolicy.effectiveLabel).put("harnessPreset", turnConfig.harnessPreset)
                .put("webMode", turnConfig.webMode).put("subagentMode", r.adaptivePolicy.subagentMode)
                .put("attachments", pendingMedia.length()));
        env.emitView();
        if (cancelled(r)) return null;
        return r;
    }

    /** What this turn was configured to do; stored with the turn so results stay attributable after settings change. */
    private static JSONObject turnConfigJson(TurnRun r) throws org.json.JSONException {
        TurnConfig c = r.config;
        return new JSONObject().put("providerId", c.providerId).put("modelProfileId", c.modelProfileId == null ? "" : c.modelProfileId)
                .put("model", c.model).put("modelName", c.modelName).put("protocol", r.provider.protocol)
                .put("contextMode", c.contextMode).put("effectiveContextMode", r.contextPolicy.effectiveLabel)
                .put("harnessPreset", c.harnessPreset).put("webMode", c.webMode).put("subagentMode", r.adaptivePolicy.subagentMode)
                .put("reasoningEffort", c.reasoningEffort).put("thinkingMode", c.thinkingMode).put("thinkingBudgetTokens", c.thinkingBudgetTokens)
                .put("continuationMode", c.continuationMode).put("clientToolCalling", c.clientToolCalling)
                .put("contextTokens", c.contextTokens).put("inputTokens", c.inputTokens).put("maxOutputTokens", c.maxOutputTokens);
    }

    // ---------- context ----------

    private boolean buildContext(TurnRun r) throws Exception {
        TurnTrace.Span span = r.trace().begin(TurnTrace.CONTEXT);
        TurnConfig c = r.config;
        try {
            r.prompt = contexts.buildCommitted(r.session, c.contextTokens, c.inputTokens, c.maxOutputTokens, "");
            r.compactionPlan = r.checkpointAllowed ? contexts.compactionPlan(r.session, c.contextTokens, c.inputTokens, c.maxOutputTokens) : null;
            r.needsCheckpoint = r.compactionPlan != null;
            r.preparing = r.needsRouter || r.needsCheckpoint;
        } catch (Exception contextError) {
            r.trace().fail(span, "context_prepare_failed");
            finishEarly(r, "context_prepare_failed", safe(contextError));
            return false;
        }
        r.trace().end(span, TurnTrace.OK, new JSONObject().put("messages", r.prompt.messages.length())
                .put("truncated", r.prompt.truncated).put("compactionDue", r.needsCheckpoint));
        if (!r.preparing && r.mayPrepare) {
            r.live.put("streamStage", "connecting");
            db.replaceTimelineBody(r.streamKey, r.live);
            env.emitStreamingView(r.id);
        }
        if (r.preparing) { env.setPreparing(r.id, true); env.emitView(); }
        return true;
    }

    /** Router (Smart, once per session) and checkpoint compaction (Standard/Smart, when the window is under pressure). */
    private boolean prepareContext(TurnRun r) throws Exception {
        TurnConfig c = r.config;
        ChatDatabase.Session session = r.session;
        if (!r.needsRouter) r.trace().skip(TurnTrace.ROUTE, r.contextPolicy.routerEnabled ? "task_profile_cached" : "mode_off");
        TurnTrace.Span stage = null;
        try {
            if (r.needsRouter) {
                stage = r.trace().begin(TurnTrace.ROUTE);
                TaskProfile routed = taskRouter.route(r.routeTransport, r.provider, r.key, session.model, r.prompt);
                r.taskProfile = routed != null ? routed : new TaskProfile("general", "answer", "standard", "direct", "checkpoint_recent", new JSONArray(), 0d);
                session.taskProfile = r.taskProfile.toJson().toString();
                db.saveSession(session);
                r.prompt = contexts.buildCommitted(session, c.contextTokens, c.inputTokens, c.maxOutputTokens, "");
                r.compactionPlan = r.checkpointAllowed ? contexts.compactionPlan(session, c.contextTokens, c.inputTokens, c.maxOutputTokens) : null;
                r.needsCheckpoint = r.compactionPlan != null;
                r.trace().end(stage, TurnTrace.OK, withCallFailure(r, TurnTrace.ROUTE, new JSONObject().put("routed", routed != null)
                        .put("domain", r.taskProfile.domain).put("taskType", r.taskProfile.taskType)
                        .put("depth", r.taskProfile.depth).put("confidence", r.taskProfile.confidence)));
            }
            if (!r.needsCheckpoint) r.trace().skip(TurnTrace.CHECKPOINT, r.checkpointAllowed ? "within_budget" : "mode_off");
            if (r.needsCheckpoint) {
                stage = r.trace().begin(TurnTrace.CHECKPOINT);
                ContextCheckpointService.Update update = checkpointService.update(r.checkpointTransport, r.provider, r.key, session.model, r.compactionPlan.baseline,
                        ContextCheckpoint.fromStored(session.contextCheckpoint), session.checkpointCoveredItems);
                if (update != null) {
                    session.contextCheckpoint = update.checkpoint.toJson().toString();
                    session.checkpointCoveredItems = update.coveredItems;
                    db.saveSession(session);
                    r.prompt = contexts.buildCommitted(session, c.contextTokens, c.inputTokens, c.maxOutputTokens, "");
                }
                r.trace().end(stage, TurnTrace.OK, withCallFailure(r, TurnTrace.CHECKPOINT, new JSONObject().put("updated", update != null)
                        .put("coveredItems", update == null ? session.checkpointCoveredItems : update.coveredItems)));
            }
        } catch (Exception preparationError) {
            r.trace().fail(stage, "context_prepare_failed");
            finishEarly(r, "context_prepare_failed", safe(preparationError));
            return false;
        } finally {
            if (r.preparing) { env.setPreparing(r.id, false); env.emitStreamingView(r.id); }
        }
        return !cancelled(r);
    }

    // ---------- Web plan ----------

    private boolean planWeb(TurnRun r) throws Exception {
        // Web is a product-level policy plane. First decide whether this user request needs external
        // information; only then choose the execution wire. Provider capability controls HOW Web runs,
        // never WHETHER a semantically fresh/current request deserves Web.
        r.webFollowUp = webFollowUpContext(db.timeline(r.id));
        r.webPlanning = webOrchestrator.shouldRunPlanner(r.config.webMode, r.input, r.webFollowUp);
        if (r.webPlanning) {
            env.setWebStage(r.id, "web_planning");
            try { r.live.put("streamStage", "web_planning"); db.replaceTimelineBody(r.streamKey, r.live); } catch (Exception ignored) { }
            env.emitStreamingView(r.id);
        }
        TurnTrace.Span span = r.trace().begin(TurnTrace.WEB_PLAN);
        try {
            r.webPlan = webOrchestrator.plan(r.config.webMode, r.input, r.prompt, r.webPlanTransport, r.provider, r.key,
                    r.config.model, r.control, r.webFollowUp);
        } finally {
            if (r.webPlanning && env.clearWebStage(r.id)) env.emitStreamingView(r.id);
        }
        WebOrchestrator.Plan plan = r.webPlan;
        r.trace().end(span, TurnTrace.OK, withCallFailure(r, TurnTrace.WEB_PLAN, new JSONObject().put("webMode", r.config.webMode).put("useWeb", plan.useWeb)
                .put("plannerCalled", plan.plannerAttempted).put("intent", plan.intent)
                .put("queries", new JSONArray(plan.queries)).put("urls", plan.urls.size())
                .put("followUp", r.webFollowUp != null && r.webFollowUp.previousTurnUsedWeb)));
        return !cancelled(r);
    }

    // ---------- tools ----------

    /** Chooses who runs Web this turn (host, provider-hosted, or client tools), adds guidance and reserves tool-schema room. */
    private boolean selectTools(TurnRun r) throws Exception {
        TurnTrace.Span span = r.trace().begin(TurnTrace.TOOLS);
        TurnConfig c = r.config;
        ChatDatabase.Provider provider = r.provider;
        WebOrchestrator.Plan webPlan = r.webPlan;
        r.hostedCapabilities = ChatTurnOptions.effectiveCapabilities(provider.protocol, c.toolCapabilities);
        ChatTurnOptions declaredHosted = new ChatTurnOptions(r.hostedCapabilities, ChatTurnOptions.WEB_ON);
        r.nativeSearch = declaredHosted.supports(ChatTurnOptions.WEB_SEARCH);
        r.nativeFetch = declaredHosted.supports(ChatTurnOptions.WEB_FETCH);
        r.clientCalling = ClientToolRuntime.compatible(provider.protocol, c.clientToolCalling);
        // Auto prefers a reviewed provider-native search loop when explicitly declared. All other
        // deployments get host-managed retrieval as a guaranteed grounding baseline. Web=on also uses
        // the host path so "force Web" cannot silently become provider-side tool_choice=auto with no call.
        r.hostManagedWeb = webPlan.useWeb && (ChatTurnOptions.WEB_ON.equals(c.webMode)
                || webPlan.hasExplicitUrls() || !r.nativeSearch);
        r.providerHostedThisTurn = webPlan.useWeb && r.nativeSearch && !r.hostManagedWeb;

        if (r.clientCalling && webPlan.useWeb) {
            if (!r.nativeSearch) r.clientToolNames.add(ClientToolRuntime.WEB_SEARCH);
            if (!r.nativeFetch) r.clientToolNames.add(ClientToolRuntime.WEB_FETCH);
        }
        if (r.clientCalling && HarnessPolicyResolver.SUBAGENT_AUTO.equals(r.adaptivePolicy.subagentMode)) {
            r.clientToolNames.add(ClientToolRuntime.DELEGATE_SUBTASK);
        }

        // Make the product decision explicit to the answering model. Tool schemas below advertise any
        // concrete client tools; the guidance prevents "I have no Web" claims when host/native Web is active.
        String webGuidance = webOrchestrator.guidance(webPlan, r.providerHostedThisTurn, false, false, r.hostManagedWeb);
        if (!webGuidance.isEmpty()) {
            try {
                r.prompt = contexts.appendInstructionSuffix(r.prompt, c.contextTokens, c.inputTokens,
                        c.maxOutputTokens, webGuidance);
            } catch (IllegalArgumentException noGuidanceRoom) {
                r.trace().fail(span, "web_context_budget");
                finishEarly(r, "web_context_budget", "Web orchestration instructions do not fit the configured model input window");
                return false;
            }
        }

        // Tool definitions are real model input. They are optional because host-managed Web guarantees
        // grounding for non-native deployments; dropping a client schema must never drop required Web.
        int clientToolSchemaReserve = r.clientToolNames.isEmpty() ? 0
                : new ContextBudgetManager().estimate(ClientToolRuntime.canonicalDefinitions(String.join(",", r.clientToolNames)).toString()) + 128;
        boolean schemasDropped = false;
        if (clientToolSchemaReserve > 0) {
            try {
                r.prompt = contexts.reserveForAdditionalInput(r.prompt, c.contextTokens, c.inputTokens,
                        c.maxOutputTokens, clientToolSchemaReserve);
            } catch (IllegalArgumentException noToolRoom) {
                r.clientToolNames.clear();
                clientToolSchemaReserve = 0;
                schemasDropped = true;
            }
        }
        r.clientToolCsv = String.join(",", r.clientToolNames);
        r.clientToolSchemaTokens = clientToolSchemaReserve;
        r.preWebPrompt = r.prompt;
        r.webOptions = new ChatTurnOptions(r.hostedCapabilities, c.webMode,
                c.clientToolCalling, r.clientToolCsv, new JSONArray(), new JSONArray(), r.providerHostedThisTurn);
        r.trace().end(span, TurnTrace.OK, new JSONObject().put("webExecution", r.hostManagedWeb ? "host" : r.providerHostedThisTurn ? "provider" : webPlan.useWeb ? "client_tools" : "none")
                .put("clientTools", r.clientToolCsv).put("schemaTokens", r.clientToolSchemaTokens).put("schemasDropped", schemasDropped)
                .put("guidance", !webGuidance.isEmpty()));
        return true;
    }

    // ---------- Web retrieve ----------

    private boolean retrieveWeb(TurnRun r) throws Exception {
        if (!r.hostManagedWeb) {
            r.trace().skip(TurnTrace.WEB_RETRIEVE, !r.webPlan.useWeb ? "web_not_needed" : r.providerHostedThisTurn ? "provider_hosted" : "client_tools");
            return true;
        }
        TurnTrace.Span span = r.trace().begin(TurnTrace.WEB_RETRIEVE);
        TurnConfig c = r.config;
        String id = r.id;
        MoyuWebService.Prepared preparedWeb;
        try {
            preparedWeb = webService.prepare(r.webPlan, stage -> {
                String webStage = stage == null || stage.trim().isEmpty() ? "web_search" : stage.trim();
                env.setWebStage(id, webStage);
                try { r.live.put("streamStage", webStage); db.replaceTimelineBody(r.streamKey, r.live); } catch (Exception ignored) { }
                env.emitStreamingView(id);
            }, r.control, r.webFollowUp);
        } catch (Exception webError) {
            boolean cancelled = r.control.isCancelled();
            r.trace().fail(span, cancelled ? "cancelled" : "web_prepare_failed");
            finishEarly(r, cancelled ? "cancelled" : "web_prepare_failed", cancelled ? "已停止生成" : safe(webError));
            return false;
        } finally {
            if (env.clearWebStage(id)) env.emitStreamingView(id);
        }
        if (cancelled(r)) return false;

        // A Web plan selected by the product is required grounding, not optional RAG. Shrink only
        // the not-yet-sent evidence body; never silently fall back to stale model weights merely
        // because old conversation turns consumed the window.
        int evidenceCap = 0;
        if (preparedWeb.hasContext()) {
            MoyuWebService.Prepared appliedWeb = null;
            CanonicalPrompt appliedPrompt = null;
            int[] evidenceCaps = new int[]{24_000, 12_000, 6_000, 3_000, 1_500, 750, 512};
            for (int cap : evidenceCaps) {
                MoyuWebService.Prepared candidate = preparedWeb.clipped(cap);
                try {
                    CanonicalPrompt webPrompt = contexts.appendRequiredUserSuffix(r.preWebPrompt,
                            c.contextTokens, c.inputTokens, c.maxOutputTokens,
                            candidate.promptSuffix);
                    if (r.clientToolSchemaTokens > 0) {
                        webPrompt = contexts.reserveForAdditionalInput(webPrompt, c.contextTokens,
                                c.inputTokens, c.maxOutputTokens, r.clientToolSchemaTokens);
                    }
                    if (!promptContainsLatestUserSuffix(webPrompt, candidate.promptSuffix)) continue;
                    appliedWeb = candidate;
                    appliedPrompt = webPrompt;
                    evidenceCap = cap;
                    break;
                } catch (IllegalArgumentException noEvidenceRoom) {
                    // Try a smaller not-yet-sent evidence body. The current user turn stays mandatory.
                }
            }
            if (appliedWeb == null || appliedPrompt == null) {
                boolean alternateWeb = r.providerHostedThisTurn
                        || r.clientToolNames.contains(ClientToolRuntime.WEB_SEARCH)
                        || r.clientToolNames.contains(ClientToolRuntime.WEB_FETCH);
                if (!alternateWeb) {
                    r.trace().fail(span, "web_context_budget");
                    finishEarly(r, "web_context_budget", "Required Web evidence does not fit the configured model input window");
                    return false;
                }
                preparedWeb = MoyuWebService.Prepared.none();
                r.prompt = r.preWebPrompt;
            } else {
                preparedWeb = appliedWeb;
                r.prompt = appliedPrompt;
            }
        } else if (!preparedWeb.attempted
                && !r.providerHostedThisTurn
                && !r.clientToolNames.contains(ClientToolRuntime.WEB_SEARCH)
                && !r.clientToolNames.contains(ClientToolRuntime.WEB_FETCH)) {
            r.trace().fail(span, "web_retrieval_unavailable");
            finishEarly(r, "web_retrieval_unavailable", "Web was required for this turn but no retrieval path produced evidence or diagnostics");
            return false;
        }
        r.preparedWeb = preparedWeb;
        r.trace().end(span, TurnTrace.OK, new JSONObject().put("backend", preparedWeb.backend).put("sources", preparedWeb.sources.length())
                .put("evidenceChars", preparedWeb.promptSuffix.length()).put("evidenceCap", evidenceCap));
        return true;
    }

    // ---------- verify / repair ----------

    /** Smart post-processing: verify the candidate, repair once when calibrated risk says so, re-verify the repair. */
    void verify(TurnRun r, CanonicalPrompt mainPrompt, String candidate, String candidateReasoning, JSONArray candidateMedia,
                LinkedHashMap<String, JSONObject> webSources, String webBackend, JSONArray providerContinuationItems, String webContext) {
        String id = r.id;
        JSONObject seed = r.live;
        ChatDatabase.Session session = r.session;
        String currentUserText = r.input;
        String finalText = candidate == null ? "" : candidate;
        String finalReasoning = candidateReasoning == null ? "" : candidateReasoning;
        if (r.control.isCancelled()) {
            finish(r, finalText, finalReasoning, candidateMedia, webSources, webBackend, new String[]{"cancelled", "已停止生成"});
            return;
        }
        TurnTrace.Span verifySpan = r.trace().begin(TurnTrace.VERIFY);
        TurnTrace.Span repairSpan = null;
        boolean repairAttempted = false;
        boolean repaired = false;
        boolean repairLimitReached = false;
        String calibrationKey = SmartVerifierCalibration.calibrationKey(r.config.deploymentFingerprint);
        JSONArray evidence = SmartVerifierService.evidence(currentUserText, mainPrompt, sourceArray(webSources), webContext);
        JSONArray attempts = new JSONArray();
        SmartVerifierService.Result verification = smartVerifier.verify(r.verifyTransport, r.provider, r.key, session,
                mainPrompt, currentUserText, finalText, evidence);
        if (r.control.isCancelled()) {
            finish(r, finalText, finalReasoning, candidateMedia, webSources, webBackend, new String[]{"cancelled", "已停止生成"});
            return;
        }
        SmartVerifierCalibration.Decision decision = SmartVerifierCalibration.decide(db, calibrationKey, verification);
        attempts.put(verificationAttemptJson(verification, decision));
        String terminalAction = decision.action;

        if (SmartVerifierCalibration.ACTION_REPAIR.equals(decision.action)
                && SmartVerifierService.STATUS_VALID.equals(verification.status)) {
            repairAttempted = true;
            setPostProcessingStage(r, "repairing");
            repairSpan = r.trace().begin(TurnTrace.REPAIR);
            String repairedText = smartVerifier.repair(r.repairTransport, r.provider, r.key, session,
                    currentUserText, finalText, verification, evidence);
            if (r.control.isCancelled()) {
                finish(r, finalText, finalReasoning, candidateMedia, webSources, webBackend, new String[]{"cancelled", "已停止生成"});
                return;
            }
            if (repairedText != null && !repairedText.trim().isEmpty()) {
                repaired = true;
                finalText = repairedText;
                // The visible answer now comes from the repair call. First-pass visible reasoning and
                // native continuation no longer describe that answer and must not be presented/replayed.
                finalReasoning = "";
                setPostProcessingStage(r, "verifying");
                verification = smartVerifier.verify(r.verifyTransport, r.provider, r.key, session,
                        mainPrompt, currentUserText, finalText, evidence);
                if (r.control.isCancelled()) {
                    finish(r, finalText, finalReasoning, candidateMedia, webSources, webBackend, new String[]{"cancelled", "已停止生成"});
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
            if (!repaired) attachProviderContinuation(seed, r.config, providerContinuationItems);
        } catch (Exception ignored) { }
        try {
            r.trace().end(repairSpan, TurnTrace.OK, withCallFailure(r, TurnTrace.REPAIR, new JSONObject().put("repaired", repaired)));
            r.trace().end(verifySpan, TurnTrace.OK, withCallFailure(r, TurnTrace.VERIFY, new JSONObject().put("status", verification.status).put("verdict", verification.verdict)
                    .put("risk", decision.risk).put("action", terminalAction).put("repairAttempted", repairAttempted)
                    .put("repaired", repaired).put("repairLimitReached", repairLimitReached).put("attempts", attempts.length())));
        } catch (Exception ignored) { }
        finish(r, finalText, finalReasoning, candidateMedia, webSources, webBackend, null);
    }

    void setPostProcessingStage(TurnRun r, String stage) {
        env.setPostStage(r.id, stage);
        try { r.live.put("streamStage", stage); db.replaceTimelineBody(r.streamKey, r.live); } catch (Exception ignored) { }
        env.emitStreamingView(r.id);
    }

    private static JSONObject verificationAttemptJson(SmartVerifierService.Result result,
                                                      SmartVerifierCalibration.Decision decision) {
        try {
            return result.toJson().put("risk", decision.risk).put("action", decision.action)
                    .put("features", new JSONArray(decision.features.toString()));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    // ---------- finish ----------

    /** Cancellation check at a stage boundary; a cancelled turn is finished here. */
    private boolean cancelled(TurnRun r) {
        if (!r.control.isCancelled()) return false;
        finishEarly(r, "cancelled", "已停止生成");
        return true;
    }

    private void finishEarly(TurnRun r, String code, String summary) {
        finish(r, "", "", new JSONArray(), new LinkedHashMap<>(), "", new String[]{code, summary});
    }

    /** Adds why the stage's last helper call failed (provider error code, "timeout" or "cancelled"), when it did. */
    private static JSONObject withCallFailure(TurnRun r, String stage, JSONObject detail) throws org.json.JSONException {
        String failure = r.control.usage.stageFailure(stage);
        if (failure != null) detail.put("callFailure", failure);
        return detail;
    }

    void settleUsage(TurnRun r, long firstOutputNanos) {
        TurnControl control = r.control;
        JSONObject usageState = control.usageState;
        try {
            if (env.ownsTurn(r.id, control)) control.usage.project(usageState);
            long finished = control.durationNow();
            usageState.put("requestDurationMs", Math.max(0L, (finished - r.requestStartedNanos) / 1_000_000L));
            if (firstOutputNanos > 0L) {
                long outputDurationMs = Math.max(1L, (finished - firstOutputNanos) / 1_000_000L);
                usageState.put("firstOutputLatencyMs", Math.max(0L, (firstOutputNanos - r.requestStartedNanos) / 1_000_000L));
                usageState.put("outputDurationMs", outputDurationMs);
                if (usageState.has("outputTokens")) {
                    int totalOutput = Math.max(0, usageState.optInt("outputTokens", 0));
                    int reasoning = Math.max(0, usageState.optInt("reasoningTokens", 0));
                    int visibleOutput = Math.max(0, totalOutput - reasoning);
                    if (visibleOutput > 0) usageState.put("outputTokensPerSecond", visibleOutput * 1000.0d / outputDurationMs);
                }
            }
            db.updateTurnUsage(control.turnId, usageState);
        } catch (Exception ignored) { }
    }

    void persistFinalUsage(String id, TurnControl control) {
        if (control != null && control.usageState != null && control.usage.hasRequests()) {
            try {
                control.usage.project(control.usageState);
                control.usageState.put("requestDurationMs", Math.max(0L,
                        (control.durationNow() - control.requestStartedNanos) / 1_000_000L));
                db.updateTurnUsage(control.turnId, control.usageState);
            } catch (Exception ignored) { }
        }
    }

    void finish(TurnRun r, String text, String reasoning, JSONArray outputMedia,
                Map<String, JSONObject> webSources, String webBackend, String[] error) {
        String id = r.id;
        // Preparation or verification can be the last (or only) billed request of a turn.
        persistFinalUsage(id, env.ownsTurn(id, r.control) ? r.control : null);
        boolean hasVisibleOutput = !text.isEmpty() || (outputMedia != null && outputMedia.length() > 0);
        boolean hasProjection = hasVisibleOutput || (reasoning != null && !reasoning.isEmpty());
        String[] terminalError = error;
        if (terminalError == null && !hasVisibleOutput) {
            terminalError = new String[]{"empty_assistant_output", "模型结束了回答，但没有输出文字或图片"};
        }
        String finalState = terminalError == null || "cancelled".equals(terminalError[0]) ? "completed" : "failed";
        try {
            JSONObject finished = null;
            if (hasProjection) {
                finished = new JSONObject(r.live.toString()).put("text", text).put("streaming", false);
                finished.remove("streamStage");
                if (terminalError != null) finished.put("incomplete", true).put("terminalCode", terminalError[0]);
                if (reasoning != null && !reasoning.isEmpty()) finished.put("reasoning", reasoning);
                if (outputMedia != null && outputMedia.length() > 0) finished.put("attachments", outputMedia);
                if (webSources != null && !webSources.isEmpty()) finished.put("sources", sourceArray(webSources));
                if (webBackend != null && !webBackend.trim().isEmpty()) finished.put("webBackend", webBackend.trim());
            }
            db.finalizeTurn(id, r.streamKey, finished,
                    terminalError == null ? null : terminalError[0], terminalError == null ? null : terminalError[1], finalState);
        } catch (Exception finalizeError) {
            // Runtime authority must never remain locked because a projection write failed.
            try { db.deleteTimelineItem(r.streamKey); } catch (Exception ignored) { }
            try {
                ChatDatabase.Session s = db.getSession(id);
                if (s != null) { s.state = "failed"; s.updatedAt = Instant.now().toString(); db.saveSession(s); }
            } catch (Exception ignored) { }
            env.showMessage("回合收尾失败：" + safe(finalizeError));
        } finally {
            // The turn record is settled last: once it reads as finished, its reply text is already final.
            recordTrace(r.control, terminalError);
            env.releaseTurn(id);
        }
    }

    /** Settles the turn record with its terminal status, final usage and stage trace. */
    private void recordTrace(TurnControl control, String[] terminalError) {
        control.trace.terminate(terminalError);
        try {
            control.trace.attachUsage(control.usage.stageUsage());
            // A turn that never reached a model keeps no usage summary, so it never shows as a usage row.
            db.completeTurn(control.turnId, control.trace.terminalStatus(), terminalError == null ? "" : terminalError[0],
                    control.usage.hasRequests() ? control.usageState : null, control.trace.toJson());
        } catch (Exception error) { env.showMessage("调用链没有保存：" + safe(error)); }
    }

    /** Last-resort path for an exception that escaped the stages: keep whatever usage and trace the turn produced. */
    void abandon(String id, TurnControl control, String code) {
        if (control == null || control.turnId == null) return;
        persistFinalUsage(id, control);
        recordTrace(control, new String[]{code, code});
    }

    void attachProviderContinuation(JSONObject seed, TurnConfig turnConfig, JSONArray items) throws Exception {
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

    // ---------- helpers ----------

    /**
     * Deterministic Web follow-up context comes only from the immediately preceding finalized
     * assistant turn. The current accepted user + live assistant rows are ignored, and an intervening
     * non-Web assistant deliberately breaks inheritance so old search state cannot revive later.
     */
    static MoyuWebService.FollowUpContext webFollowUpContext(JSONArray timeline) {
        if (timeline == null || timeline.length() == 0) return MoyuWebService.FollowUpContext.none();
        JSONObject previousAssistant = null;
        int assistantIndex = -1;
        for (int i = timeline.length() - 1; i >= 0; i--) {
            JSONObject item = timeline.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("kind", ""))) continue;
            if (!"assistant".equals(item.optString("role", "")) || item.optBoolean("streaming", false)) continue;
            previousAssistant = item;
            assistantIndex = i;
            break;
        }
        if (previousAssistant == null) return MoyuWebService.FollowUpContext.none();
        JSONArray sources = previousAssistant.optJSONArray("sources");
        String backend = previousAssistant.optString("webBackend", "").trim();
        boolean usedWeb = (sources != null && sources.length() > 0) || !backend.isEmpty();
        if (!usedWeb) return MoyuWebService.FollowUpContext.none();
        String previousUserText = "";
        for (int i = assistantIndex - 1; i >= 0; i--) {
            JSONObject item = timeline.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("kind", "")) || item.optBoolean("streaming", false)) continue;
            if ("user".equals(item.optString("role", ""))) {
                previousUserText = item.optString("text", "");
                break;
            }
        }
        return new MoyuWebService.FollowUpContext(previousUserText,
                sources == null ? new JSONArray() : sources, true);
    }

    static boolean promptContainsLatestUserSuffix(CanonicalPrompt prompt, String suffix) {
        if (prompt == null || suffix == null || suffix.isEmpty()) return false;
        JSONArray messages = prompt.messages;
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject item = messages.optJSONObject(i);
            if (item == null || !"user".equals(item.optString("role", ""))) continue;
            return item.optString("text", "").endsWith(suffix);
        }
        return false;
    }

    static JSONArray copyArray(JSONArray source) {
        try { return source == null ? new JSONArray() : new JSONArray(source.toString()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static LinkedHashMap<String, JSONObject> copySources(Map<String, JSONObject> source) {
        LinkedHashMap<String, JSONObject> out = new LinkedHashMap<>();
        if (source == null) return out;
        for (Map.Entry<String, JSONObject> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            try { out.put(entry.getKey(), new JSONObject(entry.getValue().toString())); }
            catch (Exception ignored) { }
        }
        return out;
    }

    static JSONArray sourceArray(Map<String, JSONObject> sources) {
        JSONArray out = new JSONArray();
        if (sources == null) return out;
        for (JSONObject source : sources.values()) if (source != null) out.put(source);
        return out;
    }

    static String providerWebBackend(String protocol) {
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return "Provider hosted Web · Responses";
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return "Provider hosted Web · Messages";
        return "Provider hosted Web";
    }

    static String safe(Exception e) { String m = e.getMessage(); return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m; }
}
