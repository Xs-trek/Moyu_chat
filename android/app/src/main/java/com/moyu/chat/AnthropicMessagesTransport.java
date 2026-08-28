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
    private static final long MAX_SEMANTIC_IDLE_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final OkHttpClient http = new OkHttpClient.Builder()
            .fastFallback(true)
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
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
            Request.Builder builder = new Request.Builder().url(base + "/messages")
                    .header("anthropic-version", "2023-06-01")
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(JSON, body.toString()));
            ProviderAuth.apply(builder, provider.authMode, provider.protocol, apiKey);
            Request request = builder.build();
            Call call = http.newCall(request); executor.execute(() -> run(call, guarded)); return call::cancel;
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

    private void run(Call call, ChatTransport.Sink sink) {
        boolean sawData = false;
        boolean sawUnexpectedPayload = false;
        long lastSemantic = System.nanoTime();
        AnthropicContinuationCollector continuation = new AnthropicContinuationCollector(sink);
        AnthropicClientToolCollector clientTools = new AnthropicClientToolCollector();
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) { sink.onError("http_" + response.code(), "Provider HTTP " + response.code(), response.code() == 429 || response.code() >= 500); return; }
            sink.onStage("connected");
            if (response.body() == null) { sink.onError("empty_response", "Provider 返回空响应", false); return; }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    long now = System.nanoTime();
                    if (!line.startsWith("data:")) {
                        String trimmed = line.trim();
                        if (!trimmed.isEmpty() && !trimmed.startsWith(":") && !trimmed.startsWith("event:")) sawUnexpectedPayload = true;
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return; }
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return; }
                        continue;
                    }
                    sawData = true;
                    JSONObject event;
                    try { event = new JSONObject(data); }
                    catch (Exception ignored) {
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return; }
                        continue;
                    }
                    if (!event.optString("type", "").isEmpty()) lastSemantic = now;
                    else if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return; }
                    if (parse(event, sink, continuation, clientTools)) return;
                }
            }
            if (sawData) {
                continuation.flush();
                JSONArray calls = clientTools.calls();
                if (calls.length() > 0) sink.onClientToolBatch(calls); else sink.onCompleted();
            }
            else if (sawUnexpectedPayload) sink.onError("unexpected_response_format", "Provider returned HTTP 200 but not an SSE data stream", false);
            else sink.onError("empty_stream", "Provider returned HTTP 200 with no stream data", true);
        } catch (IOException error) {
            if (call.isCanceled()) sink.onError("cancelled", "已中断", false);
            else if (error instanceof java.net.SocketTimeoutException) sink.onError("stream_timeout", "Provider stream timed out while waiting for data", true);
            else sink.onError("network_error", safe(error), true);
        }
    }

    static boolean parse(JSONObject event, ChatTransport.Sink sink) {
        return parse(event, sink, null, new AnthropicClientToolCollector());
    }

    private static boolean parse(JSONObject event, ChatTransport.Sink sink, AnthropicContinuationCollector continuation,
                                 AnthropicClientToolCollector clientTools) {
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
            JSONObject usage = event.optJSONObject("usage"); if (usage != null) sink.onUsage(null, usage.has("output_tokens") ? usage.optInt("output_tokens") : null); return false;
        }
        if ("message_stop".equals(type)) {
            if (continuation != null) continuation.flush();
            JSONArray calls = clientTools == null ? new JSONArray() : clientTools.calls();
            if (calls.length() > 0) sink.onClientToolBatch(calls); else sink.onCompleted();
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
                if ("server_tool_use".equals(type)) {
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

        void flush() {
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
                    sink.onProviderContinuation(new JSONObject().put("protocol", PROTOCOL)
                            .put("item", new JSONObject(block.toString())));
                }
            } catch (Exception ignored) { }
            completed.clear(); blocks.clear(); inputJson.clear();
        }
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
