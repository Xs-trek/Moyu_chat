package com.moyu.chat;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Ensures one provider stream produces at most one terminal callback, and that a failure summary never carries
 * the API key (a misconfigured endpoint may echo request headers back in its error body).
 */
final class TerminalSink implements ChatTransport.Sink {
    private final ChatTransport.Sink delegate;
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    private final String apiKey;

    TerminalSink(ChatTransport.Sink delegate) { this(delegate, null); }
    TerminalSink(ChatTransport.Sink delegate, String apiKey) { this.delegate = delegate; this.apiKey = apiKey; }

    @Override public void onStage(String stage) { if (!terminal.get()) delegate.onStage(stage); }
    @Override public void onReasoningDelta(String delta) { if (!terminal.get()) delegate.onReasoningDelta(delta); }
    @Override public void onProviderContinuation(JSONObject state) { if (!terminal.get()) delegate.onProviderContinuation(state); }
    @Override public void onTextDelta(String delta) { if (!terminal.get()) delegate.onTextDelta(delta); }
    @Override public void onImage(String mimeType, String base64, String fileName) { if (!terminal.get()) delegate.onImage(mimeType, base64, fileName); }
    @Override public void onToolActivity(String tool, String state) { if (!terminal.get()) delegate.onToolActivity(tool, state); }
    @Override public void onWebSource(String url, String title) { if (!terminal.get()) delegate.onWebSource(url, title); }
    @Override public void onClientToolBatch(org.json.JSONArray calls) { if (terminal.compareAndSet(false, true)) delegate.onClientToolBatch(calls); }
    @Override public void onUsage(Integer inputTokens, Integer outputTokens) { if (!terminal.get()) delegate.onUsage(inputTokens, outputTokens); }
    @Override public void onUsageDetails(Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                                         Integer cacheWriteInputTokens, Integer cacheHitBaseTokens, Integer reasoningTokens) {
        if (!terminal.get()) delegate.onUsageDetails(inputTokens, outputTokens, cachedInputTokens, cacheWriteInputTokens, cacheHitBaseTokens, reasoningTokens);
    }
    @Override public void onRetryAfter(long millis) { if (!terminal.get()) delegate.onRetryAfter(millis); }
    @Override public void onCompleted() { if (terminal.compareAndSet(false, true)) delegate.onCompleted(); }
    @Override public void onError(String code, String summary, boolean retryable) { if (terminal.compareAndSet(false, true)) delegate.onError(code, WireCapture.scrub(summary, apiKey), retryable); }
    /** Wire records are diagnostics, not stream semantics: they pass even after the terminal callback. */
    @Override public void onWire(String direction, String text) { delegate.onWire(direction, text); }
}
