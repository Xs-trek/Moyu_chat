package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The generate stage: one or more provider rounds for a single user turn. A round ends in text
 * completion, an error, or a client-tool batch; a batch is executed off the authority queue and its
 * bounded results start the next round, up to {@link #MAX_CLIENT_TOOL_ROUNDS}. Streaming output is
 * coalesced into a 20 fps live projection. After the last round the turn goes to Smart verification
 * or straight to {@link TurnPipeline#finish}.
 */
final class ProviderRoundLoop {
    static final int MAX_PROVIDER_CONTINUATION_CHARS = 1_048_576;
    static final int MAX_CLIENT_TOOL_ROUNDS = 4;
    static final long STREAM_PROJECTION_INTERVAL_MS = 50L;

    private final TurnPipeline p;
    private final TurnPipeline.Env env;
    private final TurnRun r;
    private final String id;
    private final TurnControl turnControl;
    private final TurnTrace.Span span;

    private final CanonicalPrompt mainPrompt;
    private final MoyuWebService.Prepared webPrepared;
    private final boolean allowImageOutput;
    private final LinkedHashMap<String, JSONObject> webSources = new LinkedHashMap<>();
    private final String[] webBackend;
    private volatile long firstOutputNanos = 0L;
    /** When the current round's provider sent its finish marker (0 = not yet); see OpenAiCompatibleTransport.FINISH_GRACE_MS. */
    private volatile long roundFinishNanos = 0L;
    private final StringBuffer textOut = new StringBuffer();
    private final StringBuffer reasoningOut = new StringBuffer();
    private final AtomicReference<String> streamStage = new AtomicReference<>("connecting");
    private final AtomicBoolean streamProjectionScheduled = new AtomicBoolean(false);
    private final AtomicBoolean streamTerminal = new AtomicBoolean(false);
    private final AtomicLong streamProjectionRevision = new AtomicLong(0L);
    private final JSONArray outputMedia = new JSONArray();
    private final JSONArray providerContinuationItems = new JSONArray();
    // Exact provider output/content blocks from completed client-tool rounds. This is same-turn
    // protocol state only; it is not persisted as durable continuation unless the turn had no client tools.
    private final JSONArray providerRoundHistory = new JSONArray();
    private final java.util.Set<String> providerContinuationKeys = new java.util.HashSet<>();
    private int providerContinuationChars = 0;
    private boolean providerContinuationOverflow = false;
    private final JSONArray clientToolHistory = new JSONArray();
    private final AtomicLong providerRoundGeneration = new AtomicLong(0L);
    private final AtomicLong clientToolBatchCount = new AtomicLong(0L);
    private int childInputTokens = 0, childOutputTokens = 0, subagentCount = 0;
    private boolean usedClientWeb = false;
    private final StringBuilder clientEvidenceContext = new StringBuilder();
    private final AtomicReference<ChatTurnOptions> activeRoundOptions;
    private final AtomicReference<CanonicalPrompt> activeRoundPrompt;
    private int roundTextStart = 0, roundReasoningStart = 0, roundMediaStart = 0;
    private final JSONObject usageState;
    private int roundInput, roundOutput, roundCached, roundCacheWrite, roundCacheBase, roundReasoning;
    private int mainInput, mainOutput, mainCached, mainCacheWrite, mainCacheBase, mainReasoning;
    private final ChatTransport.Sink sink = new RoundSink();

    ProviderRoundLoop(TurnPipeline pipeline, TurnRun run) throws Exception {
        this.p = pipeline;
        this.env = pipeline.env;
        this.r = run;
        this.id = run.id;
        this.turnControl = run.control;
        this.mainPrompt = run.prompt;
        this.webPrepared = run.preparedWeb;
        this.allowImageOutput = ChatDatabase.supportsModality(run.config.outputModalities, "image");
        this.activeRoundOptions = new AtomicReference<>(run.webOptions);
        this.activeRoundPrompt = new AtomicReference<>(mainPrompt);
        run.provider.maxOutputTokens = run.config.maxOutputTokens; // request-local provider copy; transport only needs the frozen wire output cap.
        for (int i = 0; i < webPrepared.sources.length(); i++) {
            JSONObject source = webPrepared.sources.optJSONObject(i); if (source == null) continue;
            String url = source.optString("url", ""); if (!url.isEmpty()) webSources.put(url, new JSONObject(source.toString()));
        }
        this.webBackend = new String[]{webPrepared.backend};
        this.usageState = turnControl.usageState.put("context", mainPrompt.diagnostics());
        this.span = run.trace().begin(TurnTrace.GENERATE);
    }

    void start() throws Exception {
        r.live.put("streamStage", "connecting");
        if (!webSources.isEmpty()) r.live.put("sources", TurnPipeline.sourceArray(webSources));
        if (!webBackend[0].isEmpty()) r.live.put("webBackend", webBackend[0]);
        p.db.replaceTimelineBody(r.streamKey, r.live);
        env.emitStreamingView(id);
        startRound(r.webOptions);
    }

    private void settle() { p.settleUsage(r, firstOutputNanos); }

    private void finish(String[] error) {
        p.finish(r, textOut.toString(), reasoningOut.toString(), outputMedia, webSources, webBackend[0], error);
    }

    private void refreshUsageProjection() throws Exception {
        int currentInput = mainInput + roundInput;
        int currentOutput = mainOutput + roundOutput;
        int currentCached = mainCached + roundCached;
        int currentCacheWrite = mainCacheWrite + roundCacheWrite;
        int currentCacheBase = mainCacheBase + roundCacheBase;
        int currentReasoning = mainReasoning + roundReasoning;
        if (currentInput > 0) usageState.put("inputTokens", currentInput);
        if (currentOutput > 0) usageState.put("outputTokens", currentOutput);
        if (currentCached > 0) usageState.put("cachedInputTokens", currentCached);
        if (currentCacheWrite > 0) usageState.put("cacheWriteInputTokens", currentCacheWrite);
        if (currentCacheBase > 0) usageState.put("cacheHitBaseTokens", currentCacheBase);
        if (currentReasoning > 0) usageState.put("reasoningTokens", currentReasoning);
        if (childInputTokens > 0) usageState.put("childInputTokens", childInputTokens);
        if (childOutputTokens > 0) usageState.put("childOutputTokens", childOutputTokens);
        if (subagentCount > 0) usageState.put("subagentCount", subagentCount);
        if (currentInput > 0 || childInputTokens > 0) usageState.put("totalInputTokens", currentInput + childInputTokens);
        if (currentOutput > 0 || childOutputTokens > 0) usageState.put("totalOutputTokens", currentOutput + childOutputTokens);
        turnControl.usage.project(usageState);
    }

    private void persistUsageProjection() throws Exception {
        refreshUsageProjection();
        p.db.updateTurnUsage(turnControl.turnId, usageState);
    }

    private void commitRoundUsage() throws Exception {
        mainInput += roundInput; mainOutput += roundOutput; mainCached += roundCached;
        mainCacheWrite += roundCacheWrite; mainCacheBase += roundCacheBase; mainReasoning += roundReasoning;
        roundInput = 0; roundOutput = 0; roundCached = 0; roundCacheWrite = 0; roundCacheBase = 0; roundReasoning = 0;
        refreshUsageProjection();
    }

    private void clearIntermediateContinuation() {
        providerContinuationKeys.clear(); providerContinuationChars = 0; providerContinuationOverflow = false;
        while (providerContinuationItems.length() > 0) providerContinuationItems.remove(providerContinuationItems.length() - 1);
    }

    private JSONObject liveProjection() throws Exception {
        JSONObject next = new JSONObject(r.live.toString()).put("text", textOut.toString()).put("streaming", true).put("streamStage", streamStage.get());
        if (reasoningOut.length() > 0) next.put("reasoning", reasoningOut.toString());
        if (outputMedia.length() > 0) next.put("attachments", outputMedia);
        if (!webSources.isEmpty()) next.put("sources", TurnPipeline.sourceArray(webSources)); else next.remove("sources");
        if (!webBackend[0].isEmpty()) next.put("webBackend", webBackend[0]); else next.remove("webBackend");
        return next;
    }

    private void markProjectionDirty() {
        streamProjectionRevision.incrementAndGet();
        scheduleProjectionFlush();
    }

    private void scheduleProjectionFlush() {
        if (streamTerminal.get() || env.isClosed() || !streamProjectionScheduled.compareAndSet(false, true)) return;
        try {
            env.projections().schedule(() -> {
                final long targetRevision = streamProjectionRevision.get();
                env.authority().execute(() -> {
                    try {
                        if (!streamTerminal.get()) {
                            JSONObject projection = liveProjection();
                            p.db.replaceTimelineBody(r.streamKey, projection);
                            env.emitStreamingPatch(id, projection);
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

    private void projectNow() throws Exception {
        JSONObject projection = liveProjection(); p.db.replaceTimelineBody(r.streamKey, projection); env.emitStreamingPatch(id, projection);
    }

    private JSONObject generateDetail() throws Exception {
        return new JSONObject().put("rounds", clientToolBatchCount.get() + 1).put("clientToolBatches", clientToolBatchCount.get())
                .put("subagents", subagentCount).put("usedClientWeb", usedClientWeb).put("sources", webSources.size())
                .put("outputChars", textOut.length()).put("reasoningChars", reasoningOut.length());
    }

    /** The wait between a round's finish marker and its terminal callback is not turn time. */
    private void endFinishGrace() {
        long finished = roundFinishNanos;
        roundFinishNanos = 0L;
        if (finished > 0L) turnControl.finishGraceNanos.addAndGet(Math.max(0L, System.nanoTime() - finished));
    }

    private final class RoundSink implements ChatTransport.Sink {
        @Override public void onStage(String stage) {
            if (stage == null || stage.trim().isEmpty() || streamTerminal.get()) return;
            if (OpenAiCompatibleTransport.STAGE_FINISHED.equals(stage)) { if (roundFinishNanos == 0L) roundFinishNanos = System.nanoTime(); return; }
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
            env.authority().execute(() -> { try {
                if (!r.provider.protocol.equals(state.optString("protocol", "")) || providerContinuationOverflow) return;
                JSONObject item = state.optJSONObject("item"); if (item == null) return;
                String serialized = item.toString();
                String key = item.optString("id", "").trim();
                if (key.isEmpty()) key = item.optString("type", "") + ":" + Integer.toHexString(serialized.hashCode());
                if (!providerContinuationKeys.add(key)) return;
                if (providerContinuationChars + serialized.length() > MAX_PROVIDER_CONTINUATION_CHARS) {
                    providerContinuationOverflow = true;
                    providerContinuationKeys.clear();
                    while (providerContinuationItems.length() > 0) providerContinuationItems.remove(providerContinuationItems.length() - 1);
                    return; // never persist/replay a partial opaque output-item set.
                }
                providerContinuationChars += serialized.length();
                JSONObject copy = new JSONObject(serialized);
                // Same-turn protocol replay is required even when cross-turn native continuation is disabled.
                providerRoundHistory.put(new JSONObject().put("round", providerRound).put("item", new JSONObject(serialized)));
                if (r.allowProviderContinuation) providerContinuationItems.put(copy);
            } catch (Exception ignored) { } });
        }
        @Override public void onTextDelta(String delta) {
            if (delta == null || delta.isEmpty() || streamTerminal.get()) return;
            if (firstOutputNanos == 0L) firstOutputNanos = turnControl.durationNow();
            streamStage.set("output");
            textOut.append(delta);
            markProjectionDirty();
        }
        @Override public void onImage(String mimeType, String base64, String fileName) {
            if (!allowImageOutput) return;
            env.authority().execute(() -> {
                try {
                    if (firstOutputNanos == 0L) firstOutputNanos = turnControl.durationNow();
                    streamStage.set("output");
                    JSONObject image = p.mediaStore.saveBase64Image(mimeType, base64, fileName);
                    outputMedia.put(image);
                    projectNow();
                } catch (Exception error) { env.showMessage("image output ignored: " + TurnPipeline.safe(error)); }
            });
        }
        @Override public void onToolActivity(String tool, String state) { env.authority().execute(() -> { try {
            if (ChatTurnOptions.WEB_SEARCH.equals(tool) || ChatTurnOptions.WEB_FETCH.equals(tool)) {
                streamStage.set(tool);
                if (webBackend[0].isEmpty() || "unavailable".equals(webBackend[0])) webBackend[0] = TurnPipeline.providerWebBackend(r.provider.protocol);
                projectNow();
            }
        } catch (Exception ignored) { } }); }
        @Override public void onWebSource(String url, String title) { env.authority().execute(() -> { try {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
            if (!webSources.containsKey(url) && webSources.size() < 16) webSources.put(url, new JSONObject()
                    .put("url", url).put("title", title == null || title.trim().isEmpty() ? url : title.trim())
                    .put("backend", TurnPipeline.providerWebBackend(r.provider.protocol)));
            if (webBackend[0].isEmpty() || "unavailable".equals(webBackend[0])) webBackend[0] = TurnPipeline.providerWebBackend(r.provider.protocol);
            projectNow();
        } catch (Exception ignored) { } }); }
        @Override public void onClientToolBatch(JSONArray calls) { endFinishGrace(); handleToolBatch(calls); }
        @Override public void onUsage(Integer in, Integer out) { onUsageDetails(in, out, null, null, null, null); }
        @Override public void onUsageDetails(Integer in, Integer out, Integer cached, Integer cacheWrite, Integer cacheBase, Integer reasoningTokens) { env.authority().execute(() -> { try {
            if (in != null) roundInput = Math.max(roundInput, Math.max(0, in));
            if (out != null) roundOutput = Math.max(roundOutput, Math.max(0, out));
            if (cached != null) roundCached = Math.max(roundCached, Math.max(0, cached));
            if (cacheWrite != null) roundCacheWrite = Math.max(roundCacheWrite, Math.max(0, cacheWrite));
            if (cacheBase != null) roundCacheBase = Math.max(roundCacheBase, Math.max(0, cacheBase));
            if (reasoningTokens != null) roundReasoning = Math.max(roundReasoning, Math.max(0, reasoningTokens));
            persistUsageProjection();
        } catch (Exception ignored) { } }); }
        @Override public void onCompleted() {
            endFinishGrace();
            streamTerminal.set(true);
            providerRoundGeneration.incrementAndGet();
            env.authority().execute(ProviderRoundLoop.this::completeGeneration);
        }
        @Override public void onError(String code, String summary, boolean retryable) {
            endFinishGrace();
            streamTerminal.set(true);
            providerRoundGeneration.incrementAndGet();
            env.authority().execute(() -> {
                try { commitRoundUsage(); persistUsageProjection(); } catch (Exception ignored) { }
                settle();
                finish(new String[]{code, summary});
            });
        }
    }

    private void handleToolBatch(JSONArray calls) {
        if (streamTerminal.get()) return;
        ChatTurnOptions emittedOptions = activeRoundOptions.get();
        if (emittedOptions == null || !emittedOptions.clientToolsEnabled(r.provider.protocol)) {
            streamTerminal.set(true);
            providerRoundGeneration.incrementAndGet();
            env.authority().execute(() -> {
                settle();
                finish(new String[]{"unexpected_tool_call", "Provider requested a client tool after tools were disabled"});
            });
            return;
        }
        JSONArray batchCalls = TurnPipeline.copyArray(calls);
        if (batchCalls.length() == 0) {
            streamTerminal.set(true);
            providerRoundGeneration.incrementAndGet();
            env.authority().execute(() -> finish(new String[]{"invalid_tool_call", "Provider emitted an empty client tool batch"}));
            return;
        }
        if (batchCalls.length() > ClientToolRuntime.MAX_TOOL_CALLS_PER_BATCH) {
            streamTerminal.set(true);
            providerRoundGeneration.incrementAndGet();
            env.authority().execute(() -> finish(new String[]{"tool_batch_limit", "Client tool batch limit exceeded"}));
            return;
        }

        // A tool batch is terminal for this provider round, not for the user turn. Invalidate
        // the just-finished round immediately so a very fast callback cannot re-bind its old Call.
        providerRoundGeneration.incrementAndGet();
        ChatTransport.Turn completedRound = env.removeActiveTurn(id);
        turnControl.clearTransport(completedRound);
        int batchNumber = (int) clientToolBatchCount.incrementAndGet();
        if (batchNumber > MAX_CLIENT_TOOL_ROUNDS) {
            streamTerminal.set(true);
            env.authority().execute(() -> finish(new String[]{"tool_round_limit", "Client tool round limit exceeded"}));
            return;
        }

        // Text/reasoning/images emitted only while deciding to call tools are intermediate
        // protocol state, not the final assistant answer shown in the transcript.
        if (textOut.length() > roundTextStart) textOut.setLength(roundTextStart);
        if (reasoningOut.length() > roundReasoningStart) reasoningOut.setLength(roundReasoningStart);
        while (outputMedia.length() > roundMediaStart) outputMedia.remove(outputMedia.length() - 1);
        streamStage.set("tool");
        markProjectionDirty();

        env.authority().execute(() -> {
            try {
                commitRoundUsage();
                if (providerContinuationOverflow) {
                    streamTerminal.set(true);
                    settle();
                    finish(new String[]{"provider_round_state_overflow", "Provider round state is too large to replay safely"});
                    return;
                }
                clearIntermediateContinuation();
                persistUsageProjection();
            } catch (Exception ignored) { }
            if (turnControl.isCancelled()) {
                streamTerminal.set(true);
                settle();
                finish(new String[]{"cancelled", "Generation stopped"});
                return;
            }
            try {
                env.preparation().execute(() -> {
                    turnControl.bindCurrentThread();
                    try {
                        ClientToolRuntime.BatchResult batch = ClientToolRuntime.executeBatch(batchCalls, batchNumber, r.clientToolCsv,
                                env.clientTools(), p.webService, p.focusedSubagent, r.subagentTransport, r.provider, r.key, r.config.model,
                                r.config.reasoningEffort, turnControl);
                        env.authority().execute(() -> applyToolBatch(batch, batchNumber));
                    } finally { turnControl.clearCurrentThread(); }
                });
            } catch (Exception schedulingError) {
                streamTerminal.set(true);
                settle();
                finish(new String[]{"tool_execution_failed", TurnPipeline.safe(schedulingError)});
            }
        });
    }

    private void applyToolBatch(ClientToolRuntime.BatchResult batch, int batchNumber) {
        try {
            if (turnControl.isCancelled()) {
                streamTerminal.set(true);
                settle();
                finish(new String[]{"cancelled", "Generation stopped"});
                return;
            }
            TurnConfig c = r.config;
            // Tool results have not been sent yet, so only this new batch may be clipped.
            // Prior tool results and exact provider round items are immutable once replayed.
            JSONArray boundedBatch = null;
            CanonicalPrompt nextPrompt = null;
            int nextSchemaReserve = batchNumber >= MAX_CLIENT_TOOL_ROUNDS ? 0 : r.clientToolSchemaTokens;
            int[] resultCaps = new int[]{65_536, 12_000, 6_000, 3_000, 1_500, 750, 384, 192, 128};
            for (int cap : resultCaps) {
                JSONArray attemptBatch = TurnPipeline.copyArray(batch.historyEntries);
                ClientToolRuntime.clipBatchResults(attemptBatch, cap);
                JSONArray attemptHistory = TurnPipeline.copyArray(clientToolHistory);
                for (int i = 0; i < attemptBatch.length(); i++) {
                    JSONObject entry = attemptBatch.optJSONObject(i); if (entry != null) attemptHistory.put(new JSONObject(entry.toString()));
                }
                int reserve = nextSchemaReserve + ClientToolRuntime.estimateWireHistoryTokens(attemptHistory, providerRoundHistory);
                try {
                    nextPrompt = p.contexts.reserveForAdditionalInput(mainPrompt, c.contextTokens,
                            c.inputTokens, c.maxOutputTokens, reserve);
                    boundedBatch = attemptBatch;
                    break;
                } catch (IllegalArgumentException noRoom) {
                    // Try a smaller not-yet-sent result payload. Exact provider state is never truncated.
                }
            }
            if (boundedBatch == null || nextPrompt == null) {
                throw new IllegalStateException("client tool/provider history exceeds the configured input context budget");
            }
            for (int i = 0; i < boundedBatch.length(); i++) {
                JSONObject entry = boundedBatch.optJSONObject(i); if (entry == null) continue;
                clientToolHistory.put(new JSONObject(entry.toString()));
                String toolName = entry.optString("name", "");
                if (ClientToolRuntime.WEB_SEARCH.equals(toolName) || ClientToolRuntime.WEB_FETCH.equals(toolName)) {
                    usedClientWeb = true;
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
            childInputTokens += batch.childInputTokens;
            childOutputTokens += batch.childOutputTokens;
            subagentCount += batch.subagentCount;
            persistUsageProjection();
            activeRoundPrompt.set(nextPrompt);
            ChatTurnOptions nextOptions = r.webOptions.withRoundHistory(clientToolHistory, providerRoundHistory);
            if (batchNumber >= MAX_CLIENT_TOOL_ROUNDS) nextOptions = nextOptions.withoutClientTools();
            streamStage.set("connecting");
            projectNow();
            startRound(nextOptions);
        } catch (Exception toolResultError) {
            streamTerminal.set(true);
            settle();
            finish(new String[]{"tool_result_failed", TurnPipeline.safe(toolResultError)});
        }
    }

    /** Last round completed: decide whether Smart verification runs, otherwise finish the turn. */
    private void completeGeneration() {
        try { commitRoundUsage(); persistUsageProjection(); } catch (Exception ignored) { }
        settle();
        try { r.trace().end(span, TurnTrace.OK, generateDetail()); } catch (Exception ignored) { }
        boolean usedExternalEvidence = webPrepared.hasContext() || usedClientWeb || !webSources.isEmpty();
        boolean shouldVerify = r.contextPolicy.verifierEnabled && r.adaptivePolicy.verifierAvailable
                && HarnessPolicyResolver.shouldVerify(r.session, r.taskProfile, usedExternalEvidence,
                mainPrompt.truncated, subagentCount > 0);
        if (!shouldVerify) {
            r.trace().skip(TurnTrace.VERIFY, !r.contextPolicy.verifierEnabled ? "mode_off"
                    : !r.adaptivePolicy.verifierAvailable ? "harness_off" : "not_needed");
            // Client-tool turns depend on hidden tool_call/tool_result history that is intentionally
            // not durable Chat history. Do not persist partial native continuation across user turns.
            if (clientToolBatchCount.get() == 0) try { p.attachProviderContinuation(r.live, r.config, providerContinuationItems); } catch (Exception ignored) { }
            finish(null);
            return;
        }
        ChatTransport.Turn completedTurn = env.removeActiveTurn(id);
        turnControl.clearTransport(completedTurn);
        env.setPostStage(id, "verifying");
        try { r.live.put("streamStage", "verifying"); p.db.replaceTimelineBody(r.streamKey, r.live); } catch (Exception ignored) { }
        env.emitStreamingView(id);
        final String candidate = textOut.toString();
        final String candidateReasoning = reasoningOut.toString();
        final JSONArray candidateMedia = TurnPipeline.copyArray(outputMedia);
        final JSONArray continuation = clientToolBatchCount.get() == 0
                ? TurnPipeline.copyArray(providerContinuationItems) : new JSONArray();
        final LinkedHashMap<String, JSONObject> candidateSources = TurnPipeline.copySources(webSources);
        final String candidateWebBackend = webBackend[0];
        final String verifierContext = (webPrepared.promptSuffix == null ? "" : webPrepared.promptSuffix)
                + clientEvidenceContext.toString();
        env.preparation().execute(() -> {
            turnControl.bindCurrentThread();
            try {
                p.verify(r, mainPrompt, candidate, candidateReasoning, candidateMedia, candidateSources,
                        candidateWebBackend, continuation, verifierContext);
            } finally {
                turnControl.clearCurrentThread();
            }
        });
    }

    /** Starts one provider round; a terminal/tool callback that wins the start race must not resurrect it. */
    private void startRound(ChatTurnOptions options) {
        if (streamTerminal.get() || !env.ownsTurn(id, turnControl)) return;
        ChatTurnOptions roundOptions = options == null ? ChatTurnOptions.NONE : options;
        activeRoundOptions.set(roundOptions);
        roundTextStart = textOut.length();
        roundReasoningStart = reasoningOut.length();
        roundMediaStart = outputMedia.length();
        roundInput = 0; roundOutput = 0; roundCached = 0;
        roundCacheWrite = 0; roundCacheBase = 0; roundReasoning = 0;
        long generation = providerRoundGeneration.incrementAndGet();
        env.setStarting(id, true);
        streamStage.set("connecting");
        ChatTransport.Turn turn;
        try {
            CanonicalPrompt roundPrompt = activeRoundPrompt.get();
            if (roundPrompt == null) throw new IllegalStateException("provider round prompt unavailable");
            turn = r.mainTransport.stream(r.provider, r.key, r.config.model, r.config.reasoningEffort, roundPrompt, roundOptions, sink);
        } catch (Exception startError) {
            streamTerminal.set(true);
            providerRoundGeneration.incrementAndGet();
            settle();
            finish(new String[]{"transport_start_failed", TurnPipeline.safe(startError)});
            return;
        }
        // A terminal/tool callback can win before stream() returns. Generation protects against
        // resurrecting the just-finished provider round into activeTurns/TurnControl.
        if (streamTerminal.get() || !env.ownsTurn(id, turnControl) || providerRoundGeneration.get() != generation) {
            try { turn.cancel(); } catch (Exception ignored) { }
            return;
        }
        turnControl.bindTransport(turn);
        if (turnControl.isCancelled()) {
            try { turn.cancel(); } catch (Exception ignored) { }
            return;
        }
        env.putActiveTurn(id, turn);
        env.setStarting(id, false);
        env.emitStreamingView(id);
    }
}
