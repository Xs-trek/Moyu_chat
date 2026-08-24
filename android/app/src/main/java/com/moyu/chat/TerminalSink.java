package com.moyu.chat;

import java.util.concurrent.atomic.AtomicBoolean;

/** Ensures one provider stream produces at most one terminal callback. */
final class TerminalSink implements ChatTransport.Sink {
    private final ChatTransport.Sink delegate;
    private final AtomicBoolean terminal = new AtomicBoolean(false);

    TerminalSink(ChatTransport.Sink delegate) { this.delegate = delegate; }

    @Override public void onStage(String stage) { if (!terminal.get()) delegate.onStage(stage); }
    @Override public void onReasoningDelta(String delta) { if (!terminal.get()) delegate.onReasoningDelta(delta); }
    @Override public void onTextDelta(String delta) { if (!terminal.get()) delegate.onTextDelta(delta); }
    @Override public void onImage(String mimeType, String base64, String fileName) { if (!terminal.get()) delegate.onImage(mimeType, base64, fileName); }
    @Override public void onUsage(Integer inputTokens, Integer outputTokens) { if (!terminal.get()) delegate.onUsage(inputTokens, outputTokens); }
    @Override public void onUsageDetails(Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                                         Integer cacheWriteInputTokens, Integer cacheHitBaseTokens, Integer reasoningTokens) {
        if (!terminal.get()) delegate.onUsageDetails(inputTokens, outputTokens, cachedInputTokens, cacheWriteInputTokens, cacheHitBaseTokens, reasoningTokens);
    }
    @Override public void onCompleted() { if (terminal.compareAndSet(false, true)) delegate.onCompleted(); }
    @Override public void onError(String code, String summary, boolean retryable) { if (terminal.compareAndSet(false, true)) delegate.onError(code, summary, retryable); }
}
