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
import java.util.concurrent.TimeUnit;

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
        String protocol = provider.protocol;
        if (!(CHAT_COMPLETIONS.equals(protocol) || RESPONSES.equals(protocol))) { guarded.onError("unsupported_protocol", "当前 Provider 协议不受支持", false); return () -> {}; }
        try {
            String base = ProviderEndpoint.normalize(provider.baseUrl, provider.protocol);
            String path = CHAT_COMPLETIONS.equals(protocol) ? "/chat/completions" : "/responses";
            ChatTurnOptions turnOptions = options == null ? ChatTurnOptions.NONE : options;
            JSONObject body = CHAT_COMPLETIONS.equals(protocol) ? chatBody(model, prompt, provider.maxOutputTokens) : responsesBody(model, prompt, provider.maxOutputTokens);
            applyHostedTools(body, protocol, turnOptions);
            applyClientTools(body, protocol, turnOptions);
            applyContinuationRequest(body, protocol, prompt);
            ReasoningPolicy.apply(body, provider, reasoningEffort);
            Request.Builder builder = new Request.Builder().url(base + path)
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(JSON, body.toString()));
            ProviderAuth.apply(builder, provider.authMode, provider.protocol, apiKey);
            Request request = builder.build();
            Call call = http.newCall(request);
            executor.execute(() -> run(call, protocol, guarded));
            return call::cancel;
        } catch (Exception error) { guarded.onError("bad_provider_config", safe(error), false); return () -> {}; }
    }

    private void run(Call call, String protocol, ChatTransport.Sink sink) {
        Set<String> emittedImages = new HashSet<>();
        Set<String> emittedContinuation = new HashSet<>();
        ChatToolCollector chatToolCollector = new ChatToolCollector();
        JSONArray responseToolCalls = new JSONArray();
        boolean sawData = false;
        boolean sawUnexpectedPayload = false;
        boolean responseStarted = false;
        long lastSemantic = System.nanoTime();
        try (Response response = call.execute()) {
            responseStarted = true;
            if (!response.isSuccessful()) { sink.onError("http_" + response.code(), httpErrorSummary(response), response.code() == 429 || response.code() >= 500); return; }
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
                    if ("[DONE]".equals(data)) { sink.onCompleted(); return; }
                    JSONObject event;
                    try { event = new JSONObject(data); }
                    catch (Exception ignored) {
                        if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return; }
                        continue;
                    }
                    if (isSemanticEvent(protocol, event)) lastSemantic = now;
                    else if (now - lastSemantic > MAX_SEMANTIC_IDLE_NANOS) { sink.onError("stream_stalled", "Provider stream produced no semantic data for 5 minutes", true); return; }
                    if (CHAT_COMPLETIONS.equals(protocol)) {
                        if (parseChat(event, sink, chatToolCollector)) { sink.onCompleted(); return; }
                    } else if (parseResponses(event, sink, emittedImages, emittedContinuation, responseToolCalls)) return;
                }
            }
            if (sawData) sink.onCompleted();
            else if (sawUnexpectedPayload) sink.onError("unexpected_response_format", "Provider returned HTTP 200 but not an SSE data stream", false);
            else sink.onError("empty_stream", "Provider returned HTTP 200 with no stream data", true);
        } catch (IOException error) {
            if (call.isCanceled()) sink.onError("cancelled", "已中断", false);
            else if (error instanceof java.net.SocketTimeoutException) {
                if (!responseStarted) sink.onError("response_start_timeout", "Provider did not return HTTP response headers within 120 seconds", true);
                else if (!sawData) sink.onError("stream_first_event_timeout", "Provider returned HTTP headers but no SSE data within 120 seconds", true);
                else sink.onError("stream_timeout", "Provider stream stopped producing bytes for 120 seconds", true);
            } else sink.onError("network_error", safe(error), true);
        }
    }

    private static boolean isSemanticEvent(String protocol, JSONObject event) {
        if (CHAT_COMPLETIONS.equals(protocol)) {
            JSONArray choices = event.optJSONArray("choices");
            return event.optJSONObject("error") != null || (choices != null && choices.length() > 0)
                    || event.optJSONObject("usage") != null || event.has("cost");
        }
        return !event.optString("type", "").isEmpty();
    }

    /** OpenAI-compatible multimodal input: user text + image_url data URLs; assistant history remains text. */
    private static JSONObject chatBody(String model, CanonicalPrompt prompt, int maxOutputTokens) throws Exception {
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
                messages.put(new JSONObject().put("role", role).put("content", item.optString("text", "")));
            }
        }
        JSONObject body = new JSONObject().put("model", model).put("messages", messages).put("stream", true);
        if (maxOutputTokens > 0) body.put("max_tokens", maxOutputTokens);
        return body;
    }

    /** Responses multimodal input uses input_text/input_image content parts. */
    private static JSONObject responsesBody(String model, CanonicalPrompt prompt, int maxOutputTokens) throws Exception {
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
        JSONObject body = new JSONObject().put("model", model).put("instructions", prompt.instructions).put("input", input).put("stream", true);
        if (maxOutputTokens > 0) body.put("max_output_tokens", maxOutputTokens);
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
    private static void applyClientTools(JSONObject body, String protocol, ChatTurnOptions options) throws Exception {
        if (options == null) return;
        appendToolHistory(body, protocol, options.toolHistory);
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

    private static void appendToolHistory(JSONObject body, String protocol, JSONArray history) throws Exception {
        if (history == null || history.length() == 0) return;
        if (CHAT_COMPLETIONS.equals(protocol)) {
            JSONArray messages = body.optJSONArray("messages"); if (messages == null) return;
            java.util.TreeMap<Integer, JSONArray> rounds = groupToolHistory(history);
            for (JSONArray entries : rounds.values()) {
                JSONArray calls = new JSONArray();
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                    calls.put(new JSONObject().put("id", entry.optString("id")).put("type", "function")
                            .put("function", new JSONObject().put("name", entry.optString("name"))
                                    .put("arguments", entry.optJSONObject("arguments") == null ? "{}" : entry.optJSONObject("arguments").toString())));
                }
                if (calls.length() > 0) messages.put(new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls));
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i); if (entry == null) continue;
                    messages.put(new JSONObject().put("role", "tool").put("tool_call_id", entry.optString("id"))
                            .put("content", toolResultText(entry)));
                }
            }
        } else if (RESPONSES.equals(protocol)) {
            JSONArray input = body.optJSONArray("input"); if (input == null) return;
            for (int i = 0; i < history.length(); i++) {
                JSONObject entry = history.optJSONObject(i); if (entry == null) continue;
                input.put(new JSONObject().put("type", "function_call").put("call_id", entry.optString("id"))
                        .put("name", entry.optString("name"))
                        .put("arguments", entry.optJSONObject("arguments") == null ? "{}" : entry.optJSONObject("arguments").toString()));
                input.put(new JSONObject().put("type", "function_call_output").put("call_id", entry.optString("id"))
                        .put("output", toolResultText(entry)));
            }
        }
    }

    private static java.util.TreeMap<Integer, JSONArray> groupToolHistory(JSONArray history) {
        java.util.TreeMap<Integer, JSONArray> rounds = new java.util.TreeMap<>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject entry = history.optJSONObject(i); if (entry == null) continue;
            int round = Math.max(0, entry.optInt("round", 0));
            JSONArray group = rounds.get(round); if (group == null) { group = new JSONArray(); rounds.put(round, group); }
            group.put(entry);
        }
        return rounds;
    }

    private static String toolResultText(JSONObject entry) {
        if (entry == null) return "Tool result unavailable";
        String error = entry.optString("error", "").trim();
        if (!error.isEmpty()) return "Tool error: " + error;
        return entry.optString("result", "");
    }

    private static void applyContinuationRequest(JSONObject body, String protocol, CanonicalPrompt prompt) throws Exception {
        if (!RESPONSES.equals(protocol) || prompt == null || !prompt.allowProviderContinuation) return;
        // Stateless/manual-history continuation needs replayable encrypted reasoning when the provider supports it.
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
     * Returns true when a Chat-Completions-compatible stream has emitted a semantic terminal chunk.
     * This deliberately accepts more than [DONE]: compatible gateways may keep SSE open after a
     * finish_reason or emit one final empty-choices usage/cost chunk.
     */
    static boolean parseChat(JSONObject event, ChatTransport.Sink sink) {
        return parseChat(event, sink, new ChatToolCollector());
    }

    private static boolean parseChat(JSONObject event, ChatTransport.Sink sink, ChatToolCollector toolCollector) {
        JSONObject error = event.optJSONObject("error");
        if (error != null) { sink.onError(error.optString("code", "provider_error"), error.optString("message", "Provider error"), false); return true; }
        JSONArray choices = event.optJSONArray("choices");
        String finishReason = "";
        if (choices != null && choices.length() > 0) {
            JSONObject first = choices.optJSONObject(0); JSONObject delta = first == null ? null : first.optJSONObject("delta");
            if (delta != null) {
                String reasoning = delta.optString("reasoning_content", "");
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

        if (!finishReason.isEmpty()) {
            if ("length".equals(finishReason)) sink.onError("response_incomplete", "Response stopped at max output tokens", false);
            else if ("content_filter".equals(finishReason)) sink.onError("content_filter", "Provider stopped the response due to content filtering", false);
            else if ("tool_calls".equals(finishReason) || "function_call".equals(finishReason)) sink.onError("unsupported_tool_call", "Provider requested a tool call, but Moyu Chat has no tool runtime", false);
            else if ("network_error".equals(finishReason) || "insufficient_system_resource".equals(finishReason)) sink.onError(finishReason, "Provider terminated the stream: " + finishReason, true);
            return true;
        }
        return choices != null && choices.length() == 0 && (usage != null || event.has("cost"));
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
        return parseResponses(event, sink, emittedImages, new HashSet<>());
    }

    private static boolean parseResponses(JSONObject event, ChatTransport.Sink sink, Set<String> emittedImages,
                                          Set<String> emittedContinuation) {
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
        if ("response.output_item.done".equals(type)) {
            JSONObject item = event.optJSONObject("item");
            emitProviderContinuationItem(item, sink, emittedContinuation);
            emitImageItem(item, sink, emittedImages); return false;
        }
        if ("response.completed".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            emitResponseContinuationItems(response, sink, emittedContinuation);
            emitResponseImages(response, sink, emittedImages); emitResponseWebSources(response, sink); emitResponseUsage(response, sink); sink.onCompleted(); return true;
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

    private static String httpErrorSummary(Response response) {
        String prefix = "Provider HTTP " + response.code();
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
            if (detail.length() > 512) detail = detail.substring(0, 512);
            return detail.isEmpty() ? prefix : prefix + ": " + detail;
        } catch (Exception ignored) { return prefix; }
    }

    private static String safe(Exception error) { String message = error.getMessage(); return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message; }
    @Override public void close() { executor.shutdownNow(); }
}
