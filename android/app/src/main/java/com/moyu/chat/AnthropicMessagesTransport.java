package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
        TerminalSink guarded = new TerminalSink(sink);
        if (provider == null || apiKey == null || apiKey.isEmpty()) { guarded.onError("provider_not_configured", "Provider 或 API Key 未配置", false); return () -> {}; }
        try {
            String base = ProviderEndpoint.normalize(provider.baseUrl);
            JSONObject body = body(model, prompt, provider.maxOutputTokens);
            ReasoningPolicy.apply(body, provider.reasoningWire, reasoningEffort);
            Request.Builder builder = new Request.Builder().url(base + "/v1/messages")
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
            } else {
                messages.put(new JSONObject().put("role", role).put("content", item.optString("text", "")));
            }
        }
        if (messages.length() == 0) throw new IllegalArgumentException("prompt must contain at least one user message");
        return new JSONObject().put("model", model).put("system", prompt.instructions).put("messages", messages)
                .put("max_tokens", maxOutputTokens > 0 ? maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS).put("stream", true);
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
                    if (parse(event, sink)) return;
                }
            }
            if (sawData) sink.onCompleted();
            else if (sawUnexpectedPayload) sink.onError("unexpected_response_format", "Provider returned HTTP 200 but not an SSE data stream", false);
            else sink.onError("empty_stream", "Provider returned HTTP 200 with no stream data", true);
        } catch (IOException error) {
            if (call.isCanceled()) sink.onError("cancelled", "已中断", false);
            else if (error instanceof java.net.SocketTimeoutException) sink.onError("stream_timeout", "Provider stream timed out while waiting for data", true);
            else sink.onError("network_error", safe(error), true);
        }
    }

    static boolean parse(JSONObject event, ChatTransport.Sink sink) {
        String type = event.optString("type");
        if ("content_block_delta".equals(type)) {
            JSONObject delta = event.optJSONObject("delta");
            if (delta != null && "thinking_delta".equals(delta.optString("type"))) { sink.onStage("reasoning"); return false; }
            if (delta != null && "text_delta".equals(delta.optString("type"))) { String text = delta.optString("text", ""); if (!text.isEmpty()) { sink.onStage("output"); sink.onTextDelta(text); } }
            return false;
        }
        if ("message_start".equals(type)) {
            JSONObject message = event.optJSONObject("message"); JSONObject usage = message == null ? null : message.optJSONObject("usage");
            if (usage != null) sink.onUsage(usage.has("input_tokens") ? usage.optInt("input_tokens") : null, usage.has("output_tokens") ? usage.optInt("output_tokens") : null);
            return false;
        }
        if ("message_delta".equals(type)) {
            JSONObject usage = event.optJSONObject("usage"); if (usage != null) sink.onUsage(null, usage.has("output_tokens") ? usage.optInt("output_tokens") : null); return false;
        }
        if ("message_stop".equals(type)) { sink.onCompleted(); return true; }
        if ("error".equals(type)) {
            JSONObject error = event.optJSONObject("error"); String code = error == null ? "provider_error" : error.optString("type", "provider_error");
            sink.onError(code, error == null ? "Provider error" : error.optString("message", "Provider error"), "overloaded_error".equals(code)); return true;
        }
        return false;
    }

    private static String safe(Exception error) { String message = error.getMessage(); return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message; }
    @Override public void close() { executor.shutdownNow(); }
}
