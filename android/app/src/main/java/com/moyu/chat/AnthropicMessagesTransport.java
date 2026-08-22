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
    private final OkHttpClient http = new OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, CanonicalPrompt prompt, Sink sink) {
        TerminalSink guarded = new TerminalSink(sink);
        if (provider == null || apiKey == null || apiKey.isEmpty()) { guarded.onError("provider_not_configured", "Provider 或 API Key 未配置", false); return () -> {}; }
        try {
            String base = ProviderEndpoint.normalize(provider.baseUrl);
            JSONObject body = body(model, prompt, provider.maxOutputTokens);
            Request request = new Request.Builder().url(base + "/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(JSON, body.toString())).build();
            Call call = http.newCall(request); executor.execute(() -> run(call, guarded)); return call::cancel;
        } catch (Exception error) { guarded.onError("bad_provider_config", safe(error), false); return () -> {}; }
    }

    private static JSONObject body(String model, CanonicalPrompt prompt, int maxOutputTokens) throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < prompt.messages.length(); i++) {
            JSONObject item = prompt.messages.optJSONObject(i); if (item == null) continue;
            String role = item.optString("role"); if (!("user".equals(role) || "assistant".equals(role))) continue;
            messages.put(new JSONObject().put("role", role).put("content", item.optString("text", "")));
        }
        return new JSONObject().put("model", model).put("system", prompt.instructions).put("messages", messages)
                .put("max_tokens", maxOutputTokens > 0 ? maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS).put("stream", true);
    }

    private void run(Call call, ChatTransport.Sink sink) {
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) { sink.onError("http_" + response.code(), "Provider HTTP " + response.code(), response.code() == 429 || response.code() >= 500); return; }
            if (response.body() == null) { sink.onError("empty_response", "Provider 返回空响应", false); return; }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) {
                    if (!line.startsWith("data:")) continue;
                    String data = line.substring(5).trim(); if (data.isEmpty()) continue;
                    JSONObject event; try { event = new JSONObject(data); } catch (Exception ignored) { continue; }
                    parse(event, sink);
                }
            }
            sink.onCompleted();
        } catch (IOException error) {
            if (call.isCanceled()) sink.onError("cancelled", "已中断", false); else sink.onError("network_error", safe(error), true);
        }
    }

    private static void parse(JSONObject event, ChatTransport.Sink sink) {
        String type = event.optString("type");
        if ("content_block_delta".equals(type)) {
            JSONObject delta = event.optJSONObject("delta");
            if (delta != null && "text_delta".equals(delta.optString("type"))) { String text = delta.optString("text", ""); if (!text.isEmpty()) sink.onTextDelta(text); }
            return;
        }
        if ("message_start".equals(type)) {
            JSONObject message = event.optJSONObject("message"); JSONObject usage = message == null ? null : message.optJSONObject("usage");
            if (usage != null) sink.onUsage(usage.has("input_tokens") ? usage.optInt("input_tokens") : null, usage.has("output_tokens") ? usage.optInt("output_tokens") : null);
            return;
        }
        if ("message_delta".equals(type)) {
            JSONObject usage = event.optJSONObject("usage"); if (usage != null) sink.onUsage(null, usage.has("output_tokens") ? usage.optInt("output_tokens") : null); return;
        }
        if ("message_stop".equals(type)) { sink.onCompleted(); return; }
        if ("error".equals(type)) {
            JSONObject error = event.optJSONObject("error"); String code = error == null ? "provider_error" : error.optString("type", "provider_error");
            sink.onError(code, error == null ? "Provider error" : error.optString("message", "Provider error"), "overloaded_error".equals(code));
        }
    }

    private static String safe(Exception error) { String message = error.getMessage(); return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message; }
    @Override public void close() { executor.shutdownNow(); }
}
