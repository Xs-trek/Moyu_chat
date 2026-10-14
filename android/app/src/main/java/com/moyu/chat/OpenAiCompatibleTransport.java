package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Protocol transport for OpenAI-compatible Chat Completions and Responses APIs. */
final class OpenAiCompatibleTransport implements ChatTransport, AutoCloseable {
    static final String CHAT_COMPLETIONS = "openai-chat-completions";
    static final String RESPONSES = "openai-responses";
    /** Continuation item type for Chat Completions reasoning kept for routes that must replay it. */
    static final String REASONING_ITEM = "reasoning_content";
    private static final int MAX_REPLAY_REASONING_CHARS = 200_000;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static final long SEMANTIC_IDLE_SECONDS = 300L;
    static final long READ_TIMEOUT_SECONDS = 360L;
    private static final long MAX_SEMANTIC_IDLE_NANOS = TimeUnit.SECONDS.toNanos(SEMANTIC_IDLE_SECONDS);
    private static final String STALLED = "Provider stream produced no semantic data for 5 minutes";
    /**
     * After a Chat Completions finish_reason the stream is still read until [DONE], EOF or a trailing usage
     * chunk: stream_options.include_usage sends usage in its own choices:[] chunk after the finish chunk.
     * A gateway that holds the connection open past the finish is cut off after this grace.
     */
    static final long FINISH_GRACE_MS = 2_000L;
    /** onStage value for the provider's semantic end of output; the grace wait after it is not turn time. */
    static final String STAGE_FINISHED = "finished";
    private final OkHttpClient http = new OkHttpClient.Builder()
            .fastFallback(true)
            .retryOnConnectionFailure(false)
            // Never follow redirects: OkHttp strips only Authorization on a cross-host hop, so x-api-key /
            // api-key and the whole conversation body would be replayed to wherever a 307/308 points.
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.MINUTES)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ScheduledExecutorService graceTimer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "moyu-chat-finish-grace"); thread.setDaemon(true); return thread;
    });

    @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort, CanonicalPrompt prompt, Sink sink) {
        return stream(provider, apiKey, model, reasoningEffort, prompt, ChatTurnOptions.NONE, sink);
    }

    @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort,
                                 CanonicalPrompt prompt, ChatTurnOptions options, Sink sink) {
        TerminalSink guarded = new TerminalSink(sink, apiKey);
        if (provider == null || apiKey == null || apiKey.isEmpty()) { guarded.onError("provider_not_configured", "Provider 或 API Key 未配置", false); return () -> {}; }
        String protocol = provider.protocol;
        if (!(CHAT_COMPLETIONS.equals(protocol) || RESPONSES.equals(protocol))) { guarded.onError("unsupported_protocol", "当前 Provider 协议不受支持", false); return () -> {}; }
        try {
            String url = ProviderEndpoint.requestUrl(provider.baseUrl, protocol);
            RouteDialect dialect = provider.dialect == null ? RouteDialect.defaults(protocol) : provider.dialect;
            ChatTurnOptions turnOptions = options == null ? ChatTurnOptions.NONE : options;
            boolean replayReasoning = CHAT_COMPLETIONS.equals(protocol) && dialect.replaysReasoning(turnOptions.clientToolsEnabled(protocol));
            JSONObject body = CHAT_COMPLETIONS.equals(protocol) ? chatBody(model, prompt, provider, dialect, replayReasoning) : responsesBody(model, prompt, provider, dialect);
            applyHostedTools(body, protocol, turnOptions);
            applyClientTools(body, protocol, turnOptions, replayReasoning);
            applyContinuationRequest(body, protocol, prompt, turnOptions);
            ReasoningPolicy.apply(body, provider, reasoningEffort);
            if (provider.serviceTier != null && !provider.serviceTier.isEmpty()) body.put("service_tier", provider.serviceTier);
            Request.Builder builder = new Request.Builder().url(url)
                    .post(RequestBody.create(JSON, body.toString()));
            RouteHeaders.apply(builder, provider.headers);
            builder.header("Accept", "text/event-stream");
            ProviderAuth.apply(builder, provider.authMode, provider.protocol, apiKey);
            Request request = builder.build();
            Call call = http.newCall(request);
            guarded.onWire("request", WireCapture.request(url, body, apiKey));
            executor.execute(() -> run(call, protocol, guarded, apiKey));
            return call::cancel;
        } catch (Exception error) { guarded.onError("bad_provider_config", safe(error), false); return () -> {}; }
    }

    private void run(Call call, String protocol, ChatTransport.Sink sink, String apiKey) {
        WireCapture.Response wire = new WireCapture.Response();
        try { runStream(call, protocol, CHAT_COMPLETIONS.equals(protocol) ? new ChatReasoningSink(sink) : sink, wire); }
        finally { sink.onWire("response", wire.finish(apiKey)); }
    }

    private void runStream(Call call, String protocol, ChatTransport.Sink sink, WireCapture.Response wire) {
        Set<String> emittedImages = new HashSet<>();
        Set<String> emittedContinuation = new HashSet<>();
        Set<String> emittedRefusals = new HashSet<>();
        ChatToolCollector chatToolCollector = new ChatToolCollector();
        JSONArray responseToolCalls = new JSONArray();
        boolean sawData = false;
        boolean sawUnexpectedPayload = false;
        boolean responseStarted = false;
        long lastSemantic = System.nanoTime();
        // Chat Completions: the outcome of finish_reason, held until [DONE] / EOF / trailing usage / grace expiry.
        Runnable finishOutcome = null;
        AtomicBoolean graceExpired = new AtomicBoolean(false);
        ScheduledFuture<?> grace = null;
        try (Response response = call.execute()) {
            responseStarted = true;
            wire.status(response.code());
            if (!response.isSuccessful()) {
                String detail = httpErrorDetail(response);
                String summary = ProviderErrors.httpSummary(response.code(), detail, ProviderErrors.requestId(response));
                wire.append(summary);
                long wait = ProviderErrors.retryAfterMillis(response);
                if (wait > 0) sink.onRetryAfter(wait);
                sink.onError("http_" + response.code(), summary, ProviderErrors.retryable(response.code(), detail));
                return;
            }
            sink.onStage("connected");
            if (response.body() == null) { sink.onError("empty_response", "Provider 返回空响应", false); return; }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    wire.line(line);
                    long now = System.nanoTime();
                    if (!line.startsWith("data:")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith(":") && !trimmed.startsWith("event:")) sawUnexpectedPayload = true;
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", STALLED, true); return; }
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", STALLED, true); return; }
                        continue;
                    }
                    sawData = true;
                    if ("[DONE]".equals(data)) { if (finishOutcome != null) finishOutcome.run(); else sink.onCompleted(); return; }
                    JSONObject event;
                    try { event = new JSONObject(data); }
                    catch (Exception ignored) {
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", STALLED, true); return; }
                        continue;
                    }
                    if (isSemanticEvent(protocol, event)) lastSemantic = now;
                    else if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", STALLED, true); return; }
                    if (CHAT_COMPLETIONS.equals(protocol)) {
                        ChatEnd end = parseChatEvent(event, sink, chatToolCollector);
                        if (end == null) continue;
                        if (end.finish == null) { (finishOutcome != null ? finishOutcome : end.immediate).run(); return; }
                        if (finishOutcome == null) {
                            finishOutcome = end.finish;
                            sink.onStage(STAGE_FINISHED);
                            grace = scheduleGrace(call, graceExpired);
                        }
                    } else if (parseResponses(event, sink, emittedImages, emittedContinuation, responseToolCalls, emittedRefusals)) return;
                }
            }
            if (finishOutcome != null) finishOutcome.run();
            else if (sawData) sink.onCompleted();
            else if (sawUnexpectedPayload) sink.onError("unexpected_response_format", "Provider returned HTTP 200 but not an SSE data stream", false);
            else sink.onError("empty_stream", "Provider returned HTTP 200 with no stream data", true);
        } catch (IOException error) {
            if (finishOutcome != null && graceExpired.get()) finishOutcome.run();
            else if (call.isCanceled()) sink.onError("cancelled", "已中断", false);
            else if (finishOutcome != null) finishOutcome.run(); // the answer was complete; a dropped tail loses only usage.
            else if (error instanceof java.net.SocketTimeoutException) {
                if (!responseStarted) sink.onError("response_start_timeout", "Provider did not return HTTP response headers within " + READ_TIMEOUT_SECONDS + " seconds", true);
                else if (!sawData) sink.onError("stream_first_event_timeout", "Provider returned HTTP headers but no SSE data within " + READ_TIMEOUT_SECONDS + " seconds", true);
                else sink.onError("stream_timeout", "Provider stream stopped producing bytes for " + READ_TIMEOUT_SECONDS + " seconds", true);
            } else sink.onError("network_error", ProviderErrors.networkSummary(error), true);
        } finally {
            if (grace != null) grace.cancel(false);
        }
    }

    private ScheduledFuture<?> scheduleGrace(Call call, AtomicBoolean expired) {
        try { return graceTimer.schedule(() -> { expired.set(true); call.cancel(); }, FINISH_GRACE_MS, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.RejectedExecutionException closed) { return null; }
    }

    private static boolean isSemanticEvent(String protocol, JSONObject event) {
        if (CHAT_COMPLETIONS.equals(protocol)) {
            JSONArray choices = event.optJSONArray("choices");
            return event.optJSONObject("error") != null || (choices != null && choices.length() > 0)
                    || event.optJSONObject("usage") != null || event.has("cost");
        }
        return !event.optString("type", "").isEmpty();
    }

    /**
     * OpenAI-compatible multimodal input: user text + image_url data URLs; assistant history is text, plus
     * its reasoning_content when the route requires reasoning to ride along (see RouteDialect).
     */
    private static JSONObject chatBody(String model, CanonicalPrompt prompt, ChatDatabase.Provider provider, RouteDialect dialect,
                                       boolean replayReasoning) throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", prompt.instructions));
        for (int i = 0; i < prompt.messages.length(); i++) {
            JSONObject item = prompt.messages.optJSONObject(i); if (item == null) continue;
            String role = item.optString("role"); if (!("user".equals(role) || "assistant".equals(role))) continue;
            JSONArray attachments = item.optJSONArray("attachments");
            if ("user".equals(role) && attachments != null && attachments.length() > 0) {
                JSONArray content = new JSONArray(); String text = item.optString("text", "");
                if (!text.isEmpty()) content.put(new JSONObject().put("type", "text").put("text", text));
                appendChatImages(content, attachments);
                messages.put(new JSONObject().put("role", role).put("content", content));
            } else {
                JSONObject message = new JSONObject().put("role", role).put("content", item.optString("text", ""));
                if ("assistant".equals(role) && replayReasoning) {
                    String reasoning = chatReasoning(item.optJSONObject("_providerContinuation"));
                    if (!reasoning.isEmpty()) message.put("reasoning_content", reasoning);
                }
                messages.put(message);
            }
        }
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("stream", true);
        if (dialect.streamUsage) body.put("stream_options", new JSONObject().put("include_usage", true));
        if (provider.sendOutputCap && provider.maxOutputTokens > 0 && !RouteDialect.CAP_OMIT.equals(dialect.outputCap)) {
            body.put(dialect.outputCap, provider.maxOutputTokens);
        }
        return body;
    }

    /** Reasoning text a Chat Completions turn kept for replay ('' when none). */
    private static String chatReasoning(JSONObject state) {
        if (state == null || !CHAT_COMPLETIONS.equals(state.optString("protocol", ""))) return "";
        JSONArray items = state.optJSONArray("items"); if (items == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null && REASONING_ITEM.equals(item.optString("type", ""))) out.append(item.optString("text", ""));
        }
        return out.toString();
    }

    /** Responses multimodal input uses input_text/input_image content parts. */
    private static JSONObject responsesBody(String model, CanonicalPrompt prompt, ChatDatabase.Provider provider, RouteDialect dialect) throws Exception {
        JSONArray input = new JSONArray();
        for (int i = 0; i < prompt.messages.length(); i++) {
            JSONObject item = prompt.messages.optJSONObject(i); if (item == null) continue;
            String role = item.optString("role"); if (!("user".equals(role) || "assistant".equals(role))) continue;
            JSONArray attachments = item.optJSONArray("attachments");
            if ("user".equals(role) && attachments != null && attachments.length() > 0) {
                JSONArray content = new JSONArray(); String text = item.optString("text", "");
                if (!text.isEmpty()) content.put(new JSONObject().put("type", "input_text").put("text", text));
                appendResponseImages(content, attachments);
                input.put(new JSONObject().put("role", role).put("content", content));
            } else {
                boolean replayedAssistantMessage = "assistant".equals(role) && prompt.allowProviderContinuation
                        && appendOpenAiContinuation(input, item);
                // A complete Responses output-item replay already contains the assistant message item.
                // Only synthesize provider-neutral text when no replayable message item exists.
                if (!replayedAssistantMessage) input.put(new JSONObject().put("role", role).put("content", item.optString("text", "")));
            }
        }
        // store=false unless the route opts in: replay uses encrypted reasoning items, never server-side state.
        JSONObject body = new JSONObject().put("model", model).put("instructions", prompt.instructions).put("input", input)
                .put("stream", true).put("store", dialect.store);
        if (provider.sendOutputCap && provider.maxOutputTokens > 0 && !RouteDialect.CAP_OMIT.equals(dialect.outputCap)) {
            body.put("max_output_tokens", provider.maxOutputTokens);
        }
        return body;
    }

    private static boolean appendOpenAiContinuation(JSONArray input, JSONObject message) throws Exception {
        JSONObject state = message.optJSONObject("_providerContinuation");
        if (state == null || !RESPONSES.equals(state.optString("protocol", ""))) return false;
        JSONArray items = state.optJSONArray("items"); if (items == null) return false;
        boolean replayedAssistantMessage = false;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i); if (item == null) continue;
            String type = item.optString("type", "").trim();
            if (type.isEmpty()) continue;
            input.put(new JSONObject(item.toString()));
            if ("message".equals(type)) replayedAssistantMessage = true;
        }
        return replayedAssistantMessage;
    }

    private static void applyHostedTools(JSONObject body, String protocol, ChatTurnOptions options) throws Exception {
        if (!OpenAiCompatibleTransport.RESPONSES.equals(protocol) || options == null || !options.webEnabled()) return;
        if (!options.supports(ChatTurnOptions.WEB_SEARCH)) return;
        body.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));
        body.put("tool_choice", "auto");
        ensureInclude(body, "web_search_call.action.sources");
    }

    /** Add reviewed Moyu client tools and replay only tool-call/result items from prior rounds. */
    private static void applyClientTools(JSONObject body, String protocol, ChatTurnOptions options, boolean replayReasoning) throws Exception {
        if (options == null) return;
        appendToolHistory(body, protocol, options.toolHistory, options.providerRoundHistory, replayReasoning);
        if (!options.clientToolsEnabled(protocol)) return;
        JSONArray definitions = options.clientToolDefinitions();
        if (definitions.length() == 0) return;
        JSONArray tools = body.optJSONArray("tools");
        if (tools == null) { tools = new JSONArray(); body.put("tools", tools); }
        for (int i = 0; i < definitions.length(); i++) {
            JSONObject canonical = definitions.optJSONObject(i); if (canonical == null) continue;
            JSONObject tool;
            if (CHAT_COMPLETIONS.equals(protocol)) {
                tool = new JSONObject().put("type", "function").put("function", new JSONObject()
                        .put("name", canonical.optString("name")).put("description", canonical.optString("description"))
                        .put("parameters", canonical.optJSONObject("parameters")).put("strict", true));
            } else {
                tool = new JSONObject().put("type", "function")
                        .put("name", canonical.optString("name")).put("description", canonical.optString("description"))
                        .put("parameters", canonical.optJSONObject("parameters")).put("strict", true);
            }
            tools.put(tool);
        }
        body.put("tool_choice", "auto").put("parallel_tool_calls", true);
    }

    private static void appendToolHistory(JSONObject body, String protocol, JSONArray history,
                                          JSONArray providerRoundHistory, boolean replayReasoning) throws Exception {
        if ((history == null || history.length() == 0)
                && (providerRoundHistory == null || providerRoundHistory.length() == 0)) return;
        if (CHAT_COMPLETIONS.equals(protocol)) {
            if (history == null || history.length() == 0) return;
            JSONArray messages = body.optJSONArray("messages"); if (messages == null) return;
            java.util.TreeMap<Integer, JSONArray> rounds = groupToolHistory(history);
            java.util.TreeMap<Integer, JSONArray> reasoningRounds = groupProviderRoundHistory(providerRoundHistory);
            for (java.util.Map.Entry<Integer, JSONArray> round : rounds.entrySet()) {
                JSONArray entries = round.getValue();
                JSONArray calls = new JSONArray();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                    calls.put(new JSONObject().put("id", entry.optString("id")).put("type", "function")
                            .put("function", new JSONObject().put("name", entry.optString("name"))
                                    .put("arguments", entry.optJSONObject("arguments") == null ? "{}" : entry.optJSONObject("arguments").toString())));
                }
                if (calls.length() > 0) {
                    JSONObject assistant = new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls);
                    if (replayReasoning) {
                        String reasoning = chatReasoning(new JSONObject().put("protocol", CHAT_COMPLETIONS)
                                .put("items", reasoningRounds.getOrDefault(round.getKey(), new JSONArray())));
                        if (!reasoning.isEmpty()) assistant.put("reasoning_content", reasoning);
                    }
                    messages.put(assistant);
                }
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                    messages.put(new JSONObject().put("role", "tool").put("tool_call_id", entry.optString("id"))
                            .put("content", toolResultText(entry)));
                }
            }
        } else if (RESPONSES.equals(protocol)) {
            JSONArray input = body.optJSONArray("input"); if (input == null) return;
            java.util.TreeMap<Integer, JSONArray> toolRounds = groupToolHistory(history == null ? new JSONArray() : history);
            java.util.TreeMap<Integer, JSONArray> providerRounds = groupProviderRoundHistory(providerRoundHistory);
            java.util.TreeSet<Integer> roundIds = new java.util.TreeSet<>();
            roundIds.addAll(toolRounds.keySet()); roundIds.addAll(providerRounds.keySet());
            for (Integer round : roundIds) {
                java.util.HashSet<String> replayedCalls = new java.util.HashSet<>();
                JSONArray providerItems = providerRounds.get(round);
                if (providerItems != null) for (int i = 0; i < providerItems.length(); i++) {
                    JSONObject item = providerItems.optJSONObject(i); if (item == null) continue;
                    JSONObject copy = new JSONObject(item.toString());
                    input.put(copy);
                    if ("function_call".equals(copy.optString("type", ""))) {
                        String callId = copy.optString("call_id", "").trim(); if (!callId.isEmpty()) replayedCalls.add(callId);
                    }
                }
                JSONArray entries = toolRounds.get(round);
                if (entries == null) continue;
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                    String callId = entry.optString("id", "");
                    // Compatible gateways should expose the original function_call output item. Fallback
                    // synthesis keeps simpler gateways usable without duplicating a call that was replayed.
                    if (!replayedCalls.contains(callId)) input.put(new JSONObject().put("type", "function_call").put("call_id", callId)
                            .put("name", entry.optString("name"))
                            .put("arguments", entry.optJSONObject("arguments") == null ? "{}" : entry.optJSONObject("arguments").toString()));
                    input.put(new JSONObject().put("type", "function_call_output").put("call_id", callId)
                            .put("output", toolResultText(entry)));
                }
            }
        }
    }

    private static java.util.TreeMap<Integer, JSONArray> groupToolHistory(JSONArray history) {
        java.util.TreeMap<Integer, JSONArray> rounds = new java.util.TreeMap<>();
        if (history == null) return rounds;
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i); if (entry == null) continue;
            int round = Math.max(0, entry.optInt("round", 0));
            JSONArray group = rounds.get(round); if (group == null) { group = new JSONArray(); rounds.put(round, group); }
            group.put(entry);
        }
        return rounds;
    }

    private static java.util.TreeMap<Integer, JSONArray> groupProviderRoundHistory(JSONArray history) {
        java.util.TreeMap<Integer, JSONArray> rounds = new java.util.TreeMap<>();
        if (history == null) return rounds;
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i); if (entry == null) continue;
            JSONObject item = entry.optJSONObject("item"); if (item == null) continue;
            int round = Math.max(0, entry.optInt("round", 0));
            JSONArray group = rounds.get(round); if (group == null) { group = new JSONArray(); rounds.put(round, group); }
            group.put(item);
        }
        return rounds;
    }

    private static String toolResultText(JSONObject entry) {
        if (entry == null) return "Tool result unavailable";
        String error = entry.optString("error", "").trim();
        if (!error.isEmpty()) return "Tool error: " + error;
        return entry.optString("result", "");
    }

    private static void applyContinuationRequest(JSONObject body, String protocol, CanonicalPrompt prompt,
                                                 ChatTurnOptions options) throws Exception {
        if (!RESPONSES.equals(protocol) || prompt == null) return;
        boolean sameTurnToolReplay = options != null && options.clientToolsEnabled(RESPONSES);
        if (!prompt.allowProviderContinuation && !sameTurnToolReplay) return;
        // Stateless/manual-history replay needs encrypted reasoning both for optional cross-user-turn
        // continuation and for a client-tool loop inside this same user turn. These are separate policies.
        ensureInclude(body, "reasoning.encrypted_content");
    }

    private static void ensureInclude(JSONObject body, String value) throws Exception {
        JSONArray include = body.optJSONArray("include");
        if (include == null) { include = new JSONArray(); body.put("include", include); }
        for (int i = 0; i < include.length(); i++) if (value.equals(include.optString(i))) return;
        include.put(value);
    }

    private static void appendChatImages(JSONArray content, JSONArray attachments) throws Exception {
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject image = attachments.optJSONObject(i); if (image == null || !"image".equals(image.optString("kind"))) continue;
            String dataUrl = image.optString("dataUrl", ""); if (dataUrl.isEmpty()) continue;
            content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", dataUrl)));
        }
    }

    private static void appendResponseImages(JSONArray content, JSONArray attachments) throws Exception {
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject image = attachments.optJSONObject(i); if (image == null || !"image".equals(image.optString("kind"))) continue;
            String dataUrl = image.optString("dataUrl", ""); if (dataUrl.isEmpty()) continue;
            content.put(new JSONObject().put("type", "input_image").put("image_url", dataUrl));
        }
    }

    /**
     * How a Chat Completions event ends the stream. {@code finish} is the deferred outcome of a finish_reason
     * (reading continues for a trailing usage chunk); {@code immediate} ends it now: an error event, or a
     * choices:[] usage/cost chunk, which is the last semantic chunk compatible gateways send.
     */
    static final class ChatEnd {
        final Runnable finish;
        final Runnable immediate;
        private ChatEnd(Runnable finish, Runnable immediate) { this.finish = finish; this.immediate = immediate; }
    }

    /** True when the event ends the stream; a finish outcome is delivered at once (unit-test entry). */
    static boolean parseChat(JSONObject event, ChatTransport.Sink sink) {
        ChatEnd end = parseChatEvent(event, sink, new ChatToolCollector());
        if (end == null) return false;
        (end.finish != null ? end.finish : end.immediate).run();
        return true;
    }

    private static ChatEnd parseChatEvent(JSONObject event, ChatTransport.Sink sink, ChatToolCollector toolCollector) {
        JSONObject error = event.optJSONObject("error");
        if (error != null) {
            String code = error.optString("code", "provider_error"), message = error.optString("message", "Provider error");
            return new ChatEnd(null, () -> sink.onError(code, message, false));
        }
        JSONArray choices = event.optJSONArray("choices");
        String finishReason = "";
        if (choices != null && choices.length() > 0) {
            JSONObject first = choices.optJSONObject(0); JSONObject delta = first == null ? null : first.optJSONObject("delta");
            if (delta != null) {
                // DeepSeek / Kimi / GLM use reasoning_content; OpenRouter-style gateways use reasoning.
                String reasoning = delta.optString("reasoning_content", "");
                if (reasoning.isEmpty() && delta.opt("reasoning") instanceof String) reasoning = delta.optString("reasoning", "");
                if (!reasoning.isEmpty()) { sink.onStage("reasoning"); sink.onReasoningDelta(reasoning); }
                Object content = delta.opt("content");
                if (content instanceof String) {
                    String text = (String) content;
                    if (!text.isEmpty()) { sink.onStage("output"); sink.onTextDelta(text); }
                } else if (content instanceof JSONArray) parseChatContentParts((JSONArray) content, sink);
                JSONArray toolCalls = delta.optJSONArray("tool_calls");
                if (toolCalls != null && toolCalls.length() > 0) {
                    if (toolCollector != null) toolCollector.onDelta(toolCalls);
                    sink.onStage("tool");
                }
            }
            if (first != null && !first.isNull("finish_reason")) finishReason = first.optString("finish_reason", "").trim();
        }
        JSONObject usage = event.optJSONObject("usage");
        if (usage != null) {
            Integer input = usage.has("prompt_tokens") ? usage.optInt("prompt_tokens") : null;
            Integer output = usage.has("completion_tokens") ? usage.optInt("completion_tokens") : null;
            JSONObject details = usage.optJSONObject("prompt_tokens_details");
            Integer cached = details != null && details.has("cached_tokens") ? details.optInt("cached_tokens") : null;
            Integer cacheWrite = details != null && details.has("cache_write_tokens") ? details.optInt("cache_write_tokens") : null;
            JSONObject outputDetails = usage.optJSONObject("completion_tokens_details");
            Integer reasoningTokens = outputDetails != null && outputDetails.has("reasoning_tokens") ? outputDetails.optInt("reasoning_tokens") : null;
            sink.onUsageDetails(input, output, cached, cacheWrite, input, reasoningTokens);
        }

        if (!finishReason.isEmpty()) return new ChatEnd(finishOutcome(finishReason, sink, toolCollector), null);
        if (choices != null && choices.length() == 0 && (usage != null || event.has("cost"))) return new ChatEnd(null, sink::onCompleted);
        return null;
    }

    private static Runnable finishOutcome(String finishReason, ChatTransport.Sink sink, ChatToolCollector toolCollector) {
        switch (finishReason) {
            case "length": return () -> sink.onError("response_incomplete", "Response stopped at max output tokens", false);
            case "content_filter": return () -> sink.onError("content_filter", "Provider stopped the response due to content filtering", false);
            case "tool_calls": return () -> {
                JSONArray calls = toolCollector == null ? new JSONArray() : toolCollector.calls();
                if (calls.length() == 0) sink.onError("invalid_tool_call", "Provider ended with tool_calls but emitted no valid function calls", false);
                else sink.onClientToolBatch(calls);
            };
            case "function_call": return () -> sink.onError("unsupported_tool_call", "Legacy function_call is not enabled; use tools/tool_calls", false);
            case "network_error":
            case "insufficient_system_resource": return () -> sink.onError(finishReason, "Provider terminated the stream: " + finishReason, true);
            default: return sink::onCompleted;
        }
    }

    /**
     * Collects one Chat Completions round's reasoning and hands it to the round loop as a continuation item
     * just before the round ends, so routes that require reasoning_content on later requests can replay it.
     */
    private static final class ChatReasoningSink implements ChatTransport.Sink {
        private final ChatTransport.Sink d;
        private final StringBuilder reasoning = new StringBuilder();
        private boolean overflow;
        ChatReasoningSink(ChatTransport.Sink delegate) { d = delegate; }
        private void flush() {
            if (reasoning.length() > 0 && !overflow) {
                try { d.onProviderContinuation(new JSONObject().put("protocol", CHAT_COMPLETIONS)
                        .put("item", new JSONObject().put("type", REASONING_ITEM).put("text", reasoning.toString()))); }
                catch (Exception ignored) { }
            }
            reasoning.setLength(0); overflow = false;
        }
        @Override public void onStage(String stage) { d.onStage(stage); }
        @Override public void onReasoningDelta(String delta) {
            if (delta != null && !overflow) {
                if (reasoning.length() + delta.length() > MAX_REPLAY_REASONING_CHARS) { overflow = true; reasoning.setLength(0); }
                else reasoning.append(delta);
            }
            d.onReasoningDelta(delta);
        }
        @Override public void onProviderContinuation(JSONObject state) { d.onProviderContinuation(state); }
        @Override public void onRetryAfter(long millis) { d.onRetryAfter(millis); }
        @Override public void onTextDelta(String delta) { d.onTextDelta(delta); }
        @Override public void onImage(String mimeType, String base64, String fileName) { d.onImage(mimeType, base64, fileName); }
        @Override public void onToolActivity(String tool, String state) { d.onToolActivity(tool, state); }
        @Override public void onWebSource(String url, String title) { d.onWebSource(url, title); }
        @Override public void onClientToolBatch(JSONArray calls) { flush(); d.onClientToolBatch(calls); }
        @Override public void onUsage(Integer inputTokens, Integer outputTokens) { d.onUsage(inputTokens, outputTokens); }
        @Override public void onUsageDetails(Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                                             Integer cacheWriteInputTokens, Integer cacheHitBaseTokens, Integer reasoningTokens) {
            d.onUsageDetails(inputTokens, outputTokens, cachedInputTokens, cacheWriteInputTokens, cacheHitBaseTokens, reasoningTokens);
        }
        @Override public void onCompleted() { flush(); d.onCompleted(); }
        @Override public void onError(String code, String summary, boolean retryable) { d.onError(code, summary, retryable); }
        @Override public void onWire(String direction, String text) { d.onWire(direction, text); }
    }

    private static final class ChatToolCollector {
        private static final class Pending {
            String id = "";
            String name = "";
            final StringBuilder arguments = new StringBuilder();
        }
        private final java.util.TreeMap<Integer, Pending> pending = new java.util.TreeMap<>();

        void onDelta(JSONArray deltas) {
            for (int i = 0; i < deltas.length(); i++) {
                JSONObject delta = deltas.optJSONObject(i); if (delta == null) continue;
                int index = Math.max(0, delta.optInt("index", i));
                Pending item = pending.get(index); if (item == null) { item = new Pending(); pending.put(index, item); }
                String id = delta.optString("id", ""); if (!id.isEmpty()) item.id = id;
                JSONObject function = delta.optJSONObject("function"); if (function == null) continue;
                String name = function.optString("name", ""); if (!name.isEmpty()) item.name = name;
                String args = function.optString("arguments", ""); if (!args.isEmpty()) item.arguments.append(args);
            }
        }

        JSONArray calls() {
            JSONArray out = new JSONArray();
            for (Pending item : pending.values()) {
                if (item.id.isEmpty() || item.name.isEmpty()) continue;
                try {
                    JSONObject call = new JSONObject().put("id", item.id).put("name", item.name);
                    String raw = item.arguments.length() == 0 ? "{}" : item.arguments.toString();
                    try { call.put("arguments", new JSONObject(raw)); }
                    catch (Exception invalid) { call.put("arguments", new JSONObject()).put("invalidArguments", true); }
                    out.put(call);
                } catch (Exception ignored) { }
            }
            return out;
        }
    }

    private static void parseChatContentParts(JSONArray parts, ChatTransport.Sink sink) {
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i); if (part == null) continue;
            String type = part.optString("type", "");
            if ("text".equals(type)) { String text = part.optString("text", ""); if (!text.isEmpty()) { sink.onStage("output"); sink.onTextDelta(text); } }
            else if ("image_url".equals(type)) {
                JSONObject image = part.optJSONObject("image_url"); if (image != null) emitDataUrl(image.optString("url", ""), sink, "chat-output");
            }
        }
    }

    static boolean parseResponses(JSONObject event, ChatTransport.Sink sink, Set<String> emittedImages) {
        return parseResponses(event, sink, emittedImages, new HashSet<>(), new JSONArray(), new HashSet<>());
    }

    private static boolean parseResponses(JSONObject event, ChatTransport.Sink sink, Set<String> emittedImages,
                                          Set<String> emittedContinuation, JSONArray pendingToolCalls,
                                          Set<String> emittedRefusals) {
        String type = event.optString("type");
        if (type.startsWith("response.web_search_call.")) {
            String state = type.endsWith("completed") ? "completed" : "running";
            sink.onStage("web_search");
            sink.onToolActivity(ChatTurnOptions.WEB_SEARCH, state);
            return false;
        }
        if (type.startsWith("response.reasoning")) {
            sink.onStage("reasoning");
            String delta = event.optString("delta", "");
            if (!delta.isEmpty() && (type.contains("summary_text") || type.contains("reasoning_text"))) sink.onReasoningDelta(delta);
            return false;
        }
        if ("response.output_text.delta".equals(type)) {
            String delta = event.optString("delta", ""); if (!delta.isEmpty()) { sink.onStage("output"); sink.onTextDelta(delta); } return false;
        }
        if ("response.refusal.delta".equals(type)) {
            String delta = event.optString("delta", "");
            if (!delta.isEmpty()) {
                if (emittedRefusals != null) emittedRefusals.add(refusalEventKey(event));
                sink.onStage("output"); sink.onTextDelta(delta);
            }
            return false;
        }
        if ("response.refusal.done".equals(type)) {
            String refusal = event.optString("refusal", "");
            String key = refusalEventKey(event);
            if (!refusal.isEmpty() && (emittedRefusals == null || emittedRefusals.add(key))) {
                sink.onStage("output"); sink.onTextDelta(refusal);
            }
            return false;
        }
        if ("response.output_item.done".equals(type)) {
            JSONObject item = event.optJSONObject("item");
            collectResponseToolCall(item, pendingToolCalls);
            emitProviderContinuationItem(item, sink, emittedContinuation);
            emitRefusalItem(item, sink, emittedRefusals);
            emitImageItem(item, sink, emittedImages); return false;
        }
        if ("response.completed".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            collectResponseToolCalls(response, pendingToolCalls);
            emitResponseContinuationItems(response, sink, emittedContinuation);
            emitResponseRefusals(response, sink, emittedRefusals);
            emitResponseImages(response, sink, emittedImages); emitResponseWebSources(response, sink); emitResponseUsage(response, sink);
            if (pendingToolCalls != null && pendingToolCalls.length() > 0) sink.onClientToolBatch(pendingToolCalls);
            else sink.onCompleted();
            return true;
        }
        if ("response.incomplete".equals(type)) {
            JSONObject response = event.optJSONObject("response"); emitResponseImages(response, sink, emittedImages); emitResponseUsage(response, sink);
            JSONObject details = response == null ? null : response.optJSONObject("incomplete_details");
            String reason = details == null ? "unknown" : details.optString("reason", "unknown");
            sink.onError("response_incomplete", "Response incomplete: " + reason, false); return true;
        }
        if ("response.failed".equals(type)) {
            JSONObject response = event.optJSONObject("response"); JSONObject error = response == null ? null : response.optJSONObject("error");
            sink.onError(error == null ? "response_failed" : error.optString("code", "response_failed"), error == null ? "Response failed" : error.optString("message", "Response failed"), true); return true;
        }
        if ("error".equals(type)) {
            JSONObject nested = event.optJSONObject("error");
            String code = nested == null ? event.optString("code", "provider_error") : nested.optString("code", nested.optString("type", "provider_error"));
            String message = nested == null ? event.optString("message", "Provider error") : nested.optString("message", "Provider error");
            sink.onError(code, message, false); return true;
        }
        return false;
    }

    private static void collectResponseToolCalls(JSONObject response, JSONArray out) {
        JSONArray output = response == null ? null : response.optJSONArray("output"); if (output == null) return;
        for (int i = 0; i < output.length(); i++) collectResponseToolCall(output.optJSONObject(i), out);
    }

    private static void collectResponseToolCall(JSONObject item, JSONArray out) {
        if (item == null || out == null || !"function_call".equals(item.optString("type", ""))) return;
        String callId = item.optString("call_id", "").trim();
        String name = item.optString("name", "").trim();
        if (callId.isEmpty() || name.isEmpty()) return;
        for (int i = 0; i < out.length(); i++) {
            JSONObject existing = out.optJSONObject(i); if (existing != null && callId.equals(existing.optString("id", ""))) return;
        }
        try {
            JSONObject call = new JSONObject().put("id", callId).put("name", name);
            String raw = item.optString("arguments", "{}");
            try { call.put("arguments", new JSONObject(raw)); }
            catch (Exception invalid) { call.put("arguments", new JSONObject()).put("invalidArguments", true); }
            out.put(call);
        } catch (Exception ignored) { }
    }

    private static String refusalEventKey(JSONObject event) {
        String itemId = event == null ? "" : event.optString("item_id", "").trim();
        int outputIndex = event == null ? -1 : event.optInt("output_index", -1);
        int contentIndex = event == null ? -1 : event.optInt("content_index", -1);
        return itemId.isEmpty()
                ? "refusal:index:" + outputIndex + ":" + contentIndex
                : "refusal:item:" + itemId + ":" + contentIndex;
    }

    private static void emitResponseRefusals(JSONObject response, ChatTransport.Sink sink, Set<String> emitted) {
        JSONArray output = response == null ? null : response.optJSONArray("output"); if (output == null) return;
        for (int i = 0; i < output.length(); i++) emitRefusalItem(output.optJSONObject(i), sink, emitted);
    }

    private static void emitRefusalItem(JSONObject item, ChatTransport.Sink sink, Set<String> emitted) {
        if (item == null || !"message".equals(item.optString("type", ""))) return;
        String itemId = item.optString("id", "").trim();
        JSONArray content = item.optJSONArray("content"); if (content == null) return;
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i); if (part == null || !"refusal".equals(part.optString("type", ""))) continue;
            String refusal = part.optString("refusal", ""); if (refusal.isEmpty()) continue;
            String key = itemId.isEmpty()
                    ? "refusal:text:" + i + ":" + Integer.toHexString(refusal.hashCode())
                    : "refusal:item:" + itemId + ":" + i;
            if (emitted != null && !emitted.add(key)) continue;
            sink.onStage("output"); sink.onTextDelta(refusal);
        }
    }

    private static void emitResponseImages(JSONObject response, ChatTransport.Sink sink, Set<String> emittedImages) {
        JSONArray output = response == null ? null : response.optJSONArray("output"); if (output == null) return;
        for (int i = 0; i < output.length(); i++) emitImageItem(output.optJSONObject(i), sink, emittedImages);
    }

    private static void emitResponseWebSources(JSONObject response, ChatTransport.Sink sink) {
        JSONArray output = response == null ? null : response.optJSONArray("output"); if (output == null) return;
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i); if (item == null) continue;
            if ("web_search_call".equals(item.optString("type"))) {
                JSONObject action = item.optJSONObject("action"); JSONArray sources = action == null ? null : action.optJSONArray("sources");
                emitSourceArray(sources, sink);
            }
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j); if (part == null) continue;
                JSONArray annotations = part.optJSONArray("annotations");
                if (annotations == null) continue;
                for (int k = 0; k < annotations.length(); k++) {
                    JSONObject annotation = annotations.optJSONObject(k); if (annotation == null) continue;
                    if (!"url_citation".equals(annotation.optString("type"))) continue;
                    emitSource(annotation.optString("url", ""), annotation.optString("title", ""), sink);
                }
            }
        }
    }

    private static void emitSourceArray(JSONArray sources, ChatTransport.Sink sink) {
        if (sources == null) return;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i); if (source == null) continue;
            emitSource(source.optString("url", ""), source.optString("title", ""), sink);
        }
    }

    private static void emitSource(String url, String title, ChatTransport.Sink sink) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
        sink.onWebSource(url, title == null || title.trim().isEmpty() ? url : title.trim());
    }

    private static void emitResponseContinuationItems(JSONObject response, ChatTransport.Sink sink, Set<String> emitted) {
        JSONArray output = response == null ? null : response.optJSONArray("output"); if (output == null) return;
        for (int i = 0; i < output.length(); i++) emitProviderContinuationItem(output.optJSONObject(i), sink, emitted);
    }

    private static void emitProviderContinuationItem(JSONObject item, ChatTransport.Sink sink, Set<String> emitted) {
        if (item == null || item.optString("type", "").trim().isEmpty()) return;
        try {
            String serialized = item.toString();
            String key = item.optString("id", "").trim();
            if (key.isEmpty()) key = item.optString("type", "") + ":" + Integer.toHexString(serialized.hashCode());
            if (emitted != null && !emitted.add(key)) return;
            // Manual Responses history must replay every response output item, not only reasoning.
            sink.onProviderContinuation(new JSONObject().put("protocol", RESPONSES).put("item", new JSONObject(serialized)));
        } catch (Exception ignored) { }
    }

    private static void emitImageItem(JSONObject item, ChatTransport.Sink sink, Set<String> emittedImages) {
        if (item == null || !"image_generation_call".equals(item.optString("type"))) return;
        String result = item.optString("result", ""); if (result.isEmpty()) return;
        String id = item.optString("id", ""); String key = id.isEmpty() ? Integer.toHexString(result.hashCode()) : id;
        if (!emittedImages.add(key)) return;
        String format = item.optString("output_format", "png"); String mime = "jpeg".equals(format) || "jpg".equals(format) ? "image/jpeg" : "webp".equals(format) ? "image/webp" : "image/png";
        sink.onImage(mime, result, "generated." + ("image/jpeg".equals(mime) ? "jpg" : "image/webp".equals(mime) ? "webp" : "png"));
    }

    private static void emitDataUrl(String dataUrl, ChatTransport.Sink sink, String fileName) {
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) return;
        int semi = dataUrl.indexOf(';'), comma = dataUrl.indexOf(','); if (semi < 5 || comma < semi) return;
        String mime = dataUrl.substring(5, semi); String base64 = dataUrl.substring(comma + 1);
        if (!base64.isEmpty()) sink.onImage(mime, base64, fileName);
    }

    private static void emitResponseUsage(JSONObject response, ChatTransport.Sink sink) {
        JSONObject usage = response == null ? null : response.optJSONObject("usage");
        if (usage == null) return;
        Integer input = usage.has("input_tokens") ? usage.optInt("input_tokens") : null;
        Integer output = usage.has("output_tokens") ? usage.optInt("output_tokens") : null;
        JSONObject details = usage.optJSONObject("input_tokens_details");
        Integer cached = details != null && details.has("cached_tokens") ? details.optInt("cached_tokens") : null;
        Integer cacheWrite = details != null && details.has("cache_write_tokens") ? details.optInt("cache_write_tokens") : null;
        JSONObject outputDetails = usage.optJSONObject("output_tokens_details");
        Integer reasoningTokens = outputDetails != null && outputDetails.has("reasoning_tokens") ? outputDetails.optInt("reasoning_tokens") : null;
        sink.onUsageDetails(input, output, cached, cacheWrite, input, reasoningTokens);
    }

    /** The provider's own error message, if the body carries one. */
    private static String httpErrorDetail(Response response) {
        String prefix = "";
        try {
            if (response.body() == null) return prefix;
            String raw = response.body().string();
            if (raw == null || raw.trim().isEmpty()) return prefix;
            String detail = raw.trim();
            try {
                JSONObject body = new JSONObject(detail);
                JSONObject error = body.optJSONObject("error");
                if (error != null) detail = error.optString("message", detail);
                else detail = body.optString("message", detail);
            } catch (Exception ignored) { }
            detail = detail.replace('\n', ' ').replace('\r', ' ').trim();
            return detail.length() > 512 ? detail.substring(0, 512) : detail;
        } catch (Exception ignored) { return prefix; }
    }

    private static String safe(Exception error) { String message = error.getMessage(); return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message; }
    @Override public void close() { executor.shutdownNow(); graceTimer.shutdownNow(); }
}
