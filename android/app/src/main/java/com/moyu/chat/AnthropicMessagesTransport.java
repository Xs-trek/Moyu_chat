package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Minimal Anthropic Messages-compatible streaming transport. */
final class AnthropicMessagesTransport implements ChatTransport, AutoCloseable {
    static final String PROTOCOL = "anthropic-messages";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final long SEMANTIC_IDLE_SECONDS = 300L;
    static final long READ_TIMEOUT_SECONDS = 360L;
    private static final long MAX_SEMANTIC_IDLE_NANOS = TimeUnit.SECONDS.toNanos(SEMANTIC_IDLE_SECONDS);
    private static final int MAX_PAUSE_TURN_CONTINUATIONS = 4;

    private static final class MultiCallTurn implements Turn {
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicReference<Call> current = new java.util.concurrent.atomic.AtomicReference<>();
        void bind(Call call) { current.set(call); if (cancelled.get() && call != null) call.cancel(); }
        void clear(Call call) { if (call != null) current.compareAndSet(call, null); }
        boolean isCancelled() { return cancelled.get(); }
        @Override public void cancel() {
            cancelled.set(true);
            Call call = current.get(); if (call != null) call.cancel();
        }
    }

    /**
     * pause_turn is one logical provider round implemented by multiple HTTP requests. Coordinator
     * treats repeated usage callbacks within a provider round as cumulative updates, so expose
     * completed-request totals + the current request instead of resetting usage on each continuation.
     */
    private static final class UsageAccumulatingSink implements ChatTransport.Sink {
        private final ChatTransport.Sink delegate;
        private int completedInput, completedOutput, completedCached, completedCacheWrite, completedCacheBase, completedReasoning;
        private int roundInput, roundOutput, roundCached, roundCacheWrite, roundCacheBase, roundReasoning;
        UsageAccumulatingSink(ChatTransport.Sink delegate) { this.delegate = delegate; }
        void startRound() { roundInput = roundOutput = roundCached = roundCacheWrite = roundCacheBase = roundReasoning = 0; }
        void commitRound() {
            completedInput += roundInput; completedOutput += roundOutput; completedCached += roundCached;
            completedCacheWrite += roundCacheWrite; completedCacheBase += roundCacheBase; completedReasoning += roundReasoning;
            startRound();
        }
        private static Integer present(int value) { return value > 0 ? value : null; }
        private void emitUsage() {
            delegate.onUsageDetails(present(completedInput + roundInput), present(completedOutput + roundOutput),
                    present(completedCached + roundCached), present(completedCacheWrite + roundCacheWrite),
                    present(completedCacheBase + roundCacheBase), present(completedReasoning + roundReasoning));
        }
        @Override public void onStage(String stage) { delegate.onStage(stage); }
        @Override public void onReasoningDelta(String delta) { delegate.onReasoningDelta(delta); }
        @Override public void onProviderContinuation(JSONObject state) { delegate.onProviderContinuation(state); }
        @Override public void onTextDelta(String delta) { delegate.onTextDelta(delta); }
        @Override public void onImage(String mimeType, String base64, String fileName) { delegate.onImage(mimeType, base64, fileName); }
        @Override public void onToolActivity(String tool, String state) { delegate.onToolActivity(tool, state); }
        @Override public void onWebSource(String url, String title) { delegate.onWebSource(url, title); }
        @Override public void onClientToolBatch(JSONArray calls) { delegate.onClientToolBatch(calls); }
        @Override public void onUsage(Integer inputTokens, Integer outputTokens) {
            onUsageDetails(inputTokens, outputTokens, null, null, null, null);
        }
        @Override public void onUsageDetails(Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                                             Integer cacheWriteInputTokens, Integer cacheHitBaseTokens, Integer reasoningTokens) {
            if (inputTokens != null) roundInput = Math.max(roundInput, Math.max(0, inputTokens));
            if (outputTokens != null) roundOutput = Math.max(roundOutput, Math.max(0, outputTokens));
            if (cachedInputTokens != null) roundCached = Math.max(roundCached, Math.max(0, cachedInputTokens));
            if (cacheWriteInputTokens != null) roundCacheWrite = Math.max(roundCacheWrite, Math.max(0, cacheWriteInputTokens));
            if (cacheHitBaseTokens != null) roundCacheBase = Math.max(roundCacheBase, Math.max(0, cacheHitBaseTokens));
            if (reasoningTokens != null) roundReasoning = Math.max(roundReasoning, Math.max(0, reasoningTokens));
            emitUsage();
        }
        @Override public void onCompleted() { delegate.onCompleted(); }
        @Override public void onError(String code, String summary, boolean retryable) { delegate.onError(code, summary, retryable); }
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            .fastFallback(true)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort, CanonicalPrompt prompt, Sink sink) {
        return stream(provider, apiKey, model, reasoningEffort, prompt, ChatTurnOptions.NONE, sink);
    }

    @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort,
                                 CanonicalPrompt prompt, ChatTurnOptions options, Sink sink) {
        TerminalSink guarded = new TerminalSink(sink);
        if (provider == null || apiKey == null || apiKey.isEmpty()) { guarded.onError("provider_not_configured", "Provider 或 API Key 未配置", false); return () -> {}; }
        try {
            String base = ProviderEndpoint.normalize(provider.baseUrl, provider.protocol);
            ChatTurnOptions turnOptions = options == null ? ChatTurnOptions.NONE : options;
            JSONObject body = body(model, prompt, provider.maxOutputTokens);
            applyHostedTools(body, turnOptions);
            applyClientTools(body, turnOptions);
            ReasoningPolicy.apply(body, provider, reasoningEffort);
            MultiCallTurn turn = new MultiCallTurn();
            JSONObject requestBody = new JSONObject(body.toString());
            executor.execute(() -> run(base + "/messages", provider, apiKey, requestBody, guarded, turn));
            return turn;
        } catch (Exception error) { guarded.onError("bad_provider_config", safe(error), false); return () -> {}; }
    }

    /**
     * Wire encoding only; the canonical prompt stays provider-neutral. Anthropic requires the first
     * message to be a user turn; consecutive same-role turns are combined server-side (Messages API
     * reference), so no local merging is done. A leading assistant run can only come from budget
     * truncation or an imported snapshot edge; it is dropped, never promoted into the system prefix,
     * because conversation history is context, not application authority.
     */
    private static JSONObject body(String model, CanonicalPrompt prompt, int maxOutputTokens) throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < prompt.messages.length(); i++) {
            JSONObject item = prompt.messages.optJSONObject(i); if (item == null) continue;
            String role = item.optString("role"); if (!("user".equals(role) || "assistant".equals(role))) continue;
            if (messages.length() == 0 && !"user".equals(role)) continue; // drop the leading assistant run
            JSONArray attachments = item.optJSONArray("attachments");
            if ("user".equals(role) && attachments != null && attachments.length() > 0) {
                JSONArray content = new JSONArray(); String text = item.optString("text", "");
                if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
                appendImages(content, attachments);
                messages.put(new JSONObject().put("role", role).put("content", content));
            } else if ("assistant".equals(role) && prompt.allowProviderContinuation) {
                JSONArray nativeContent = anthropicAssistantContent(item);
                if (nativeContent != null) messages.put(new JSONObject().put("role", role).put("content", nativeContent));
                else messages.put(new JSONObject().put("role", role).put("content", item.optString("text", "")));
            } else {
                messages.put(new JSONObject().put("role", role).put("content", item.optString("text", "")));
            }
        }
        if (messages.length() == 0) throw new IllegalArgumentException("prompt must contain at least one user message");
        return new JSONObject().put("model", model).put("system", prompt.instructions).put("messages", messages)
                .put("max_tokens", maxOutputTokens > 0 ? maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS).put("stream", true);
    }

    private static JSONArray anthropicAssistantContent(JSONObject message) throws Exception {
        JSONObject state = message.optJSONObject("_providerContinuation");
        if (state == null || !PROTOCOL.equals(state.optString("protocol", ""))) return null;
        JSONArray items = state.optJSONArray("items"); if (items == null || items.length() == 0) return null;
        JSONArray content = new JSONArray();
        boolean hasTextBlock = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject block = items.optJSONObject(i); if (block == null) continue;
            String type = block.optString("type", "");
            if (!("text".equals(type) || "thinking".equals(type) || "redacted_thinking".equals(type)
                    || "tool_use".equals(type) || "server_tool_use".equals(type) || "web_search_tool_result".equals(type)
                    || "web_fetch_tool_result".equals(type))) continue;
            content.put(new JSONObject(block.toString()));
            if ("text".equals(type)) hasTextBlock = true;
        }
        // Older continuation rows may contain only thinking blocks. Keep provider-neutral visible text
        // as the fallback, but never duplicate it when the native block stream already retained text.
        String text = message.optString("text", "");
        if (!hasTextBlock && !text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
        return content.length() == 0 ? null : content;
    }

    private static void applyHostedTools(JSONObject body, ChatTurnOptions options) throws Exception {
        if (options == null || !options.webEnabled()) return;
        JSONArray tools = new JSONArray();
        JSONArray direct = new JSONArray().put("direct");
        String searchWire = options.wireType(ChatTurnOptions.WEB_SEARCH);
        if (!searchWire.isEmpty()) {
            tools.put(new JSONObject().put("type", searchWire).put("name", "web_search")
                    .put("max_uses", 4).put("allowed_callers", direct));
        }
        String fetchWire = options.wireType(ChatTurnOptions.WEB_FETCH);
        if (!fetchWire.isEmpty()) {
            tools.put(new JSONObject().put("type", fetchWire).put("name", "web_fetch")
                    .put("max_uses", 3).put("allowed_callers", new JSONArray().put("direct"))
                    .put("max_content_tokens", 20000).put("citations", new JSONObject().put("enabled", true)));
        }
        if (tools.length() > 0) body.put("tools", tools);
    }

    private static void applyClientTools(JSONObject body, ChatTurnOptions options) throws Exception {
        if (options == null) return;
        appendToolHistory(body, options.toolHistory, options.providerRoundHistory);
        if (!options.clientToolsEnabled(PROTOCOL)) return;
        JSONArray definitions = options.clientToolDefinitions();
        if (definitions.length() == 0) return;
        JSONArray tools = body.optJSONArray("tools");
        if (tools == null) { tools = new JSONArray(); body.put("tools", tools); }
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject canonical = definitions.optJSONObject(i); if (canonical == null) continue;
            tools.put(new JSONObject().put("name", canonical.optString("name"))
                    .put("description", canonical.optString("description"))
                    .put("input_schema", canonical.optJSONObject("parameters")).put("strict", true));
        }
        body.put("tool_choice", new JSONObject().put("type", "auto"));
    }

    private static void appendToolHistory(JSONObject body, JSONArray history, JSONArray providerRoundHistory) throws Exception {
        if ((history == null || history.length() == 0)
                && (providerRoundHistory == null || providerRoundHistory.length() == 0)) return;
        JSONArray messages = body.optJSONArray("messages"); if (messages == null) return;
        java.util.TreeMap<Integer, JSONArray> toolRounds = new java.util.TreeMap<>();
        if (history != null) for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i); if (entry == null) continue;
            int round = Math.max(0, entry.optInt("round", 0));
            JSONArray group = toolRounds.get(round); if (group == null) { group = new JSONArray(); toolRounds.put(round, group); }
            group.put(entry);
        }
        java.util.TreeMap<Integer, JSONArray> providerRounds = new java.util.TreeMap<>();
        if (providerRoundHistory != null) for (int i = 0; i < providerRoundHistory.length(); i++) {
            JSONObject entry = providerRoundHistory.optJSONObject(i); if (entry == null) continue;
            JSONObject item = entry.optJSONObject("item"); if (item == null) continue;
            int round = Math.max(0, entry.optInt("round", 0));
            JSONArray group = providerRounds.get(round); if (group == null) { group = new JSONArray(); providerRounds.put(round, group); }
            group.put(item);
        }
        java.util.TreeSet<Integer> roundIds = new java.util.TreeSet<>();
        roundIds.addAll(toolRounds.keySet()); roundIds.addAll(providerRounds.keySet());
        for (Integer round : roundIds) {
            JSONArray assistantContent = new JSONArray();
            java.util.HashSet<String> replayedToolIds = new java.util.HashSet<>();
            JSONArray providerItems = providerRounds.get(round);
            if (providerItems != null) for (int i = 0; i < providerItems.length(); i++) {
                JSONObject item = providerItems.optJSONObject(i); if (item == null) continue;
                JSONObject copy = new JSONObject(item.toString());
                assistantContent.put(copy);
                if ("tool_use".equals(copy.optString("type", ""))) {
                    String id = copy.optString("id", "").trim(); if (!id.isEmpty()) replayedToolIds.add(id);
                }
            }
            JSONArray entries = toolRounds.get(round);
            JSONArray userContent = new JSONArray();
            if (entries != null) for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                String toolId = entry.optString("id", "");
                if (!replayedToolIds.contains(toolId)) assistantContent.put(new JSONObject().put("type", "tool_use").put("id", toolId)
                        .put("name", entry.optString("name"))
                        .put("input", entry.optJSONObject("arguments") == null ? new JSONObject() : entry.optJSONObject("arguments")));
                String error = entry.optString("error", "").trim();
                JSONObject result = new JSONObject().put("type", "tool_result").put("tool_use_id", toolId)
                        .put("content", error.isEmpty() ? entry.optString("result", "") : "Tool error: " + error);
                if (!error.isEmpty()) result.put("is_error", true);
                userContent.put(result);
            }
            if (assistantContent.length() > 0) messages.put(new JSONObject().put("role", "assistant").put("content", assistantContent));
            if (userContent.length() > 0) messages.put(new JSONObject().put("role", "user").put("content", userContent));
        }
    }

    private static void appendImages(JSONArray content, JSONArray attachments) throws Exception {
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject image = attachments.optJSONObject(i); if (image == null || !"image".equals(image.optString("kind"))) continue;
            String dataUrl = image.optString("dataUrl", ""), mime = image.optString("mimeType", "");
            int comma = dataUrl.indexOf(','); if (comma < 0 || mime.isEmpty()) continue;
            String data = dataUrl.substring(comma + 1); if (data.isEmpty()) continue;
            content.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                    .put("type", "base64").put("media_type", mime).put("data", data)));
        }
    }

    private static final class AnthropicStopState {
        String reason = "";
        boolean messageStop;
    }

    private static final class AnthropicRoundResult {
        final String stopReason;
        final JSONArray assistantContent;
        final JSONArray clientCalls;
        AnthropicRoundResult(String stopReason, JSONArray assistantContent, JSONArray clientCalls) {
            this.stopReason = stopReason == null ? "" : stopReason.trim();
            this.assistantContent = assistantContent == null ? new JSONArray() : assistantContent;
            this.clientCalls = clientCalls == null ? new JSONArray() : clientCalls;
        }
    }

    private void run(String endpoint, ChatDatabase.Provider provider, String apiKey, JSONObject body,
                     ChatTransport.Sink sink, MultiCallTurn turn) {
        int pauseContinuations = 0;
        while (!turn.isCancelled()) {
            Call call = null;
            try {
                Request.Builder builder = new Request.Builder().url(endpoint)
                        .header("anthropic-version", "2023-06-01")
                        .header("Accept", "text/event-stream")
                        .post(RequestBody.create(JSON, body.toString()));
                ProviderAuth.apply(builder, provider.authMode, provider.protocol, apiKey);
                call = http.newCall(builder.build());
                turn.bind(call);
                AnthropicRoundResult round = runRound(call, sink);
                if (round == null) return; // transport/protocol error was already emitted.
                emitContinuationBlocks(round.assistantContent, sink);
                String reason = round.stopReason;
                if ("pause_turn".equals(reason)) {
                    if (++pauseContinuations > MAX_PAUSE_TURN_CONTINUATIONS) {
                        sink.onError("pause_turn_limit", "Provider pause_turn continuation limit exceeded", true);
                        return;
                    }
                    if (round.assistantContent.length() == 0) {
                        sink.onError("invalid_pause_turn", "Provider returned pause_turn without replayable assistant content", false);
                        return;
                    }
                    JSONArray messages = body.optJSONArray("messages");
                    if (messages == null) {
                        sink.onError("invalid_pause_turn", "Anthropic request has no messages array for pause_turn continuation", false);
                        return;
                    }
                    messages.put(new JSONObject().put("role", "assistant")
                            .put("content", new JSONArray(round.assistantContent.toString())));
                    continue;
                }
                if ("tool_use".equals(reason)) {
                    if (round.clientCalls.length() == 0) sink.onError("invalid_tool_call", "Provider stopped for tool_use without a valid client tool call", false);
                    else sink.onClientToolBatch(round.clientCalls);
                    return;
                }
                if ("max_tokens".equals(reason)) {
                    sink.onError("response_incomplete", "Anthropic response stopped at max_tokens", false);
                    return;
                }
                if ("model_context_window_exceeded".equals(reason)) {
                    sink.onError("response_incomplete", "Anthropic response stopped because the model context window was exhausted", false);
                    return;
                }
                if (reason.isEmpty() || "end_turn".equals(reason) || "stop_sequence".equals(reason) || "refusal".equals(reason)) {
                    sink.onCompleted();
                    return;
                }
                sink.onError("unknown_stop_reason", "Anthropic returned unsupported stop_reason: " + reason, false);
                return;
            } catch (Exception error) {
                if (turn.isCancelled()) sink.onError("cancelled", "已中断", false);
                else sink.onError("network_error", safe(error), true);
                return;
            } finally {
                turn.clear(call);
            }
        }
        sink.onError("cancelled", "已中断", false);
    }

    private AnthropicRoundResult runRound(Call call, ChatTransport.Sink sink) {
        boolean sawData = false;
        boolean sawUnexpectedPayload = false;
        boolean responseStarted = false;
        long lastSemantic = System.nanoTime();
        AnthropicContinuationCollector continuation = new AnthropicContinuationCollector(sink);
        AnthropicClientToolCollector clientTools = new AnthropicClientToolCollector();
        AnthropicStopState stopState = new AnthropicStopState();
        try (Response response = call.execute()) {
            responseStarted = true;
            if (!response.isSuccessful()) { sink.onError("http_" + response.code(), "Provider HTTP " + response.code(), response.code() == 429 || response.code() >= 500); return null; }
            sink.onStage("connected");
            if (response.body() == null) { sink.onError("empty_response", "Provider 返回空响应", false); return null; }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    long now = System.nanoTime();
                    if (!line.startsWith("data:")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith(":") && !trimmed.startsWith("event:")) sawUnexpectedPayload = true;
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return null; }
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return null; }
                        continue;
                    }
                    sawData = true;
                    JSONObject event;
                    try { event = new JSONObject(data); }
                    catch (Exception ignored) {
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return null; }
                        continue;
                    }
                    if (!event.optString("type", "").isEmpty()) lastSemantic = now;
                    else if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return null; }
                    if (parse(event, sink, continuation, clientTools, stopState, false)) {
                        if (stopState.messageStop) return new AnthropicRoundResult(stopState.reason, continuation.drain(), clientTools.calls());
                        return null;
                    }
                }
            }
            if (sawData) {
                emitContinuationBlocks(continuation.drain(), sink);
                JSONArray calls = clientTools.calls();
                if (calls.length() > 0) sink.onClientToolBatch(calls); else sink.onCompleted();
            }
            else if (sawUnexpectedPayload) sink.onError("unexpected_response_format", "Provider returned HTTP 200 but not an SSE data stream", false);
            else sink.onError("empty_stream", "Provider returned HTTP 200 with no stream data", true);
        } catch (IOException error) {
            if (call.isCanceled()) sink.onError("cancelled", "已中断", false);
            else if (error instanceof java.net.SocketTimeoutException) {
                if (!responseStarted) sink.onError("response_start_timeout", "Provider did not return HTTP response headers within " + READ_TIMEOUT_SECONDS + " seconds", true);
                else if (!sawData) sink.onError("stream_first_event_timeout", "Provider returned HTTP headers but no SSE data within " + READ_TIMEOUT_SECONDS + " seconds", true);
                else sink.onError("stream_timeout", "Provider stream stopped producing bytes for " + READ_TIMEOUT_SECONDS + " seconds", true);
            } else sink.onError("network_error", safe(error), true);
        }
        return null;
    }

    private static void emitContinuationBlocks(JSONArray blocks, ChatTransport.Sink sink) {
        if (blocks == null || sink == null) return;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i); if (block == null) continue;
            try { sink.onProviderContinuation(new JSONObject().put("protocol", PROTOCOL).put("item", new JSONObject(block.toString()))); }
            catch (Exception ignored) { }
        }
    }

    static boolean parse(JSONObject event, ChatTransport.Sink sink) {
        return parse(event, sink, null, new AnthropicClientToolCollector(), new AnthropicStopState(), true);
    }

    private static boolean parse(JSONObject event, ChatTransport.Sink sink, AnthropicContinuationCollector continuation,
                                 AnthropicClientToolCollector clientTools, AnthropicStopState stopState,
                                 boolean terminalOnMessageStop) {
        String type = event.optString("type");
        if ("content_block_start".equals(type)) {
            if (continuation != null) continuation.onStart(event);
            if (clientTools != null) clientTools.onStart(event);
            JSONObject block = event.optJSONObject("content_block");
            if (block != null) {
                String blockType = block.optString("type", "");
                String name = block.optString("name", "");
                if ("server_tool_use".equals(blockType) && ("web_search".equals(name) || "web_fetch".equals(name))) {
                    sink.onStage(name); sink.onToolActivity(name, "running");
                } else if ("web_search_tool_result".equals(blockType) || "web_fetch_tool_result".equals(blockType)) {
                    collectWebSources(block, sink);
                    sink.onToolActivity(blockType.startsWith("web_search") ? ChatTurnOptions.WEB_SEARCH : ChatTurnOptions.WEB_FETCH, "completed");
                }
            }
            return false;
        }
        if ("content_block_stop".equals(type)) {
            if (continuation != null) continuation.onStop(event);
            if (clientTools != null) clientTools.onStop(event);
            return false;
        }
        if ("content_block_delta".equals(type)) {
            if (continuation != null) continuation.onDelta(event);
            if (clientTools != null) clientTools.onDelta(event);
            JSONObject delta = event.optJSONObject("delta");
            if (delta != null && "citations_delta".equals(delta.optString("type"))) {
                JSONObject citation = delta.optJSONObject("citation"); if (citation != null) collectWebSources(citation, sink);
                return false;
            }
            if (delta != null && "thinking_delta".equals(delta.optString("type"))) {
                sink.onStage("reasoning");
                String thinking = delta.optString("thinking", ""); if (!thinking.isEmpty()) sink.onReasoningDelta(thinking);
                return false;
            }
            if (delta != null && "text_delta".equals(delta.optString("type"))) { String text = delta.optString("text", ""); if (!text.isEmpty()) { sink.onStage("output"); sink.onTextDelta(text); } }
            return false;
        }
        if ("message_start".equals(type)) {
            JSONObject message = event.optJSONObject("message"); JSONObject usage = message == null ? null : message.optJSONObject("usage");
            if (usage != null) {
                Integer uncached = usage.has("input_tokens") ? usage.optInt("input_tokens") : null;
                Integer output = usage.has("output_tokens") ? usage.optInt("output_tokens") : null;
                Integer cached = usage.has("cache_read_input_tokens") ? usage.optInt("cache_read_input_tokens") : null;
                Integer cacheWrite = usage.has("cache_creation_input_tokens") ? usage.optInt("cache_creation_input_tokens") : null;
                int total = Math.max(0, uncached == null ? 0 : uncached) + Math.max(0, cached == null ? 0 : cached) + Math.max(0, cacheWrite == null ? 0 : cacheWrite);
                sink.onUsageDetails(total > 0 ? total : uncached, output, cached, cacheWrite, total > 0 ? total : null, null);
            }
            return false;
        }
        if ("message_delta".equals(type)) {
            JSONObject usage = event.optJSONObject("usage");
            if (usage != null) sink.onUsage(null, usage.has("output_tokens") ? usage.optInt("output_tokens") : null);
            JSONObject delta = event.optJSONObject("delta");
            String stopReason = delta == null || delta.isNull("stop_reason") ? "" : delta.optString("stop_reason", "").trim();
            if (stopState != null && !stopReason.isEmpty()) stopState.reason = stopReason;
            return false;
        }
        if ("message_stop".equals(type)) {
            if (stopState != null) stopState.messageStop = true;
            if (!terminalOnMessageStop) return true;
            if (continuation != null) continuation.flush();
            JSONArray calls = clientTools == null ? new JSONArray() : clientTools.calls();
            String reason = stopState == null ? "" : stopState.reason;
            if ("tool_use".equals(reason)) {
                if (calls.length() > 0) sink.onClientToolBatch(calls); else sink.onError("invalid_tool_call", "Provider stopped for tool_use without a valid client tool call", false);
            } else if ("max_tokens".equals(reason) || "model_context_window_exceeded".equals(reason)) {
                sink.onError("response_incomplete", "Anthropic response incomplete: " + reason, false);
            } else if ("pause_turn".equals(reason)) {
                sink.onError("pause_turn_requires_transport_continuation", "pause_turn requires the streaming transport continuation loop", true);
            } else sink.onCompleted();
            return true;
        }
        if ("error".equals(type)) {
            JSONObject error = event.optJSONObject("error"); String code = error == null ? "provider_error" : error.optString("type", "provider_error");
            sink.onError(code, error == null ? "Provider error" : error.optString("message", "Provider error"), "overloaded_error".equals(code)); return true;
        }
        return false;
    }

    private static final class AnthropicClientToolCollector {
        private static final class Pending {
            final String id;
            final String name;
            final JSONObject initialInput;
            final StringBuilder inputJson = new StringBuilder();
            Pending(String id, String name, JSONObject initialInput) {
                this.id = id; this.name = name;
                this.initialInput = initialInput == null ? new JSONObject() : initialInput;
            }
        }
        private final Map<Integer, Pending> pending = new HashMap<>();
        private final java.util.TreeMap<Integer, JSONObject> completed = new java.util.TreeMap<>();

        void onStart(JSONObject event) {
            int index = event.optInt("index", -1); if (index < 0) return;
            JSONObject block = event.optJSONObject("content_block"); if (block == null || !"tool_use".equals(block.optString("type", ""))) return;
            String id = block.optString("id", "").trim(); String name = block.optString("name", "").trim();
            if (id.isEmpty() || name.isEmpty()) return;
            try { pending.put(index, new Pending(id, name, block.optJSONObject("input") == null ? new JSONObject() : new JSONObject(block.optJSONObject("input").toString()))); }
            catch (Exception ignored) { }
        }

        void onDelta(JSONObject event) {
            int index = event.optInt("index", -1); Pending item = pending.get(index); if (item == null) return;
            JSONObject delta = event.optJSONObject("delta"); if (delta == null || !"input_json_delta".equals(delta.optString("type", ""))) return;
            item.inputJson.append(delta.optString("partial_json", ""));
        }

        void onStop(JSONObject event) {
            int index = event.optInt("index", -1); Pending item = pending.remove(index); if (item == null) return;
            try {
                JSONObject call = new JSONObject().put("id", item.id).put("name", item.name);
                if (item.inputJson.length() == 0) call.put("arguments", new JSONObject(item.initialInput.toString()));
                else {
                    try { call.put("arguments", new JSONObject(item.inputJson.toString())); }
                    catch (Exception invalid) { call.put("arguments", new JSONObject()).put("invalidArguments", true); }
                }
                completed.put(index, call);
            } catch (Exception ignored) { }
        }

        JSONArray calls() {
            JSONArray out = new JSONArray();
            for (JSONObject call : completed.values()) try { out.put(new JSONObject(call.toString())); } catch (Exception ignored) { }
            completed.clear(); pending.clear();
            return out;
        }
    }

    private static final class AnthropicContinuationCollector {
        private final ChatTransport.Sink sink;
        private final Map<Integer, JSONObject> blocks = new HashMap<>();
        private final Map<Integer, StringBuilder> inputJson = new HashMap<>();
        private final java.util.TreeMap<Integer, JSONObject> completed = new java.util.TreeMap<>();
        AnthropicContinuationCollector(ChatTransport.Sink sink) { this.sink = sink; }

        void onStart(JSONObject event) {
            try {
                int index = event.optInt("index", -1); if (index < 0) return;
                JSONObject block = event.optJSONObject("content_block"); if (block == null) return;
                String type = block.optString("type", "");
                if (!("text".equals(type) || "thinking".equals(type) || "redacted_thinking".equals(type)
                        || "tool_use".equals(type) || "server_tool_use".equals(type) || "web_search_tool_result".equals(type)
                        || "web_fetch_tool_result".equals(type))) return;
                blocks.put(index, new JSONObject(block.toString()));
                if ("tool_use".equals(type) || "server_tool_use".equals(type)) inputJson.put(index, new StringBuilder());
            } catch (Exception ignored) { }
        }

        void onDelta(JSONObject event) {
            int index = event.optInt("index", -1); if (index < 0) return;
            JSONObject block = blocks.get(index); if (block == null) return;
            JSONObject delta = event.optJSONObject("delta"); if (delta == null) return;
            String type = delta.optString("type", "");
            try {
                if ("thinking_delta".equals(type)) {
                    block.put("thinking", block.optString("thinking", "") + delta.optString("thinking", ""));
                } else if ("signature_delta".equals(type)) {
                    block.put("signature", block.optString("signature", "") + delta.optString("signature", ""));
                } else if ("text_delta".equals(type)) {
                    block.put("text", block.optString("text", "") + delta.optString("text", ""));
                } else if ("citations_delta".equals(type)) {
                    JSONObject citation = delta.optJSONObject("citation");
                    if (citation != null) {
                        JSONArray citations = block.optJSONArray("citations");
                        if (citations == null) { citations = new JSONArray(); block.put("citations", citations); }
                        citations.put(new JSONObject(citation.toString()));
                    }
                } else if ("input_json_delta".equals(type)) {
                    StringBuilder partial = inputJson.get(index);
                    if (partial != null) partial.append(delta.optString("partial_json", ""));
                }
            } catch (Exception ignored) { }
        }

        void onStop(JSONObject event) {
            int index = event.optInt("index", -1); if (index < 0) return;
            JSONObject block = blocks.remove(index); if (block == null) return;
            String type = block.optString("type", "");
            try {
                if ("tool_use".equals(type) || "server_tool_use".equals(type)) {
                    StringBuilder partial = inputJson.remove(index);
                    if (partial != null && partial.length() > 0) block.put("input", new JSONObject(partial.toString()));
                    if (block.optString("id", "").isEmpty() || block.optString("name", "").isEmpty()) return;
                }
                if ("thinking".equals(type) && block.optString("signature", "").isEmpty()) return;
                if ("redacted_thinking".equals(type) && block.optString("data", "").isEmpty()) return;
                if (("web_search_tool_result".equals(type) || "web_fetch_tool_result".equals(type))
                        && block.optString("tool_use_id", "").isEmpty()) return;
                if ("text".equals(type) && block.optString("text", "").isEmpty() && block.optJSONArray("citations") == null) return;
                completed.put(index, new JSONObject(block.toString()));
            } catch (Exception ignored) {
                inputJson.remove(index); // invalid/incomplete streamed tool input is never replayed.
            }
        }

        JSONArray drain() {
            JSONArray out = new JSONArray();
            try {
                java.util.Set<String> serverToolIds = new java.util.HashSet<>();
                for (JSONObject block : completed.values()) {
                    if ("server_tool_use".equals(block.optString("type", ""))) {
                        String id = block.optString("id", ""); if (!id.isEmpty()) serverToolIds.add(id);
                    }
                }
                for (JSONObject block : completed.values()) {
                    String type = block.optString("type", "");
                    if (("web_search_tool_result".equals(type) || "web_fetch_tool_result".equals(type))
                            && !serverToolIds.contains(block.optString("tool_use_id", ""))) continue;
                    out.put(new JSONObject(block.toString()));
                }
            } catch (Exception ignored) { }
            completed.clear(); blocks.clear(); inputJson.clear();
            return out;
        }

        void flush() { emitContinuationBlocks(drain(), sink); }
    }

    private static void collectWebSources(Object value, ChatTransport.Sink sink) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String url = object.optString("url", "");
            if (url.startsWith("https://") || url.startsWith("http://")) {
                String title = object.optString("title", "");
                sink.onWebSource(url, title == null || title.trim().isEmpty() ? url : title.trim());
            }
            JSONArray names = object.names(); if (names == null) return;
            for (int i = 0; i < names.length(); i++) collectWebSources(object.opt(names.optString(i)), sink);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectWebSources(array.opt(i), sink);
        }
    }

    private static String safe(Exception error) { String message = error.getMessage(); return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message; }
    @Override public void close() { executor.shutdownNow(); }
}
