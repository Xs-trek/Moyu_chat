package com.moyu.chat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes a bounded hidden model call used only for structured control/context preprocessing. */
final class ModelPreflight {
    static final class Output {
        final String text;
        final int inputTokens;
        final int outputTokens;
        Output(String text, int inputTokens, int outputTokens) {
            this.text = text == null ? "" : text;
            this.inputTokens = Math.max(0, inputTokens);
            this.outputTokens = Math.max(0, outputTokens);
        }
    }

    private ModelPreflight() { }

    static String run(ChatTransport transport, ChatDatabase.Provider source, String apiKey, String model,
                      CanonicalPrompt prompt, int outputCap, long timeoutMs) {
        if (transport == null || source == null || apiKey == null || apiKey.isEmpty()) return null;
        ChatDatabase.Provider provider = copy(source);
        provider.maxOutputTokens = Math.max(128, Math.min(2048, outputCap));
        StringBuilder text = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ChatTransport.Turn> turnRef = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        ChatTransport.Sink sink = new ChatTransport.Sink() {
            @Override public void onTextDelta(String delta) { if (delta != null && text.length() < 32_000) text.append(delta); }
            @Override public void onUsage(Integer inputTokens, Integer outputTokens) { }
            @Override public void onCompleted() { done.countDown(); }
            @Override public void onError(String code, String summary, boolean retryable) { failure.set(code == null ? "preflight_error" : code); done.countDown(); }
        };
        try {
            ChatTransport.Turn turn = transport.stream(provider, apiKey, model, ReasoningPolicy.DEFAULT, prompt, sink);
            turnRef.set(turn);
            if (!done.await(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS)) {
                if (turn != null) turn.cancel();
                return null;
            }
            return failure.get() == null && text.length() > 0 ? text.toString() : null;
        } catch (Exception ignored) {
            ChatTransport.Turn turn = turnRef.get(); if (turn != null) try { turn.cancel(); } catch (Exception ignored2) { }
            return null;
        }
    }

    /** Same-model hidden call that preserves the selected reasoning effort; used by Smart verifier/repair. */
    static String runWithEffort(ChatTransport transport, ChatDatabase.Provider source, String apiKey, String model,
                                String reasoningEffort, CanonicalPrompt prompt, int outputCap, long timeoutMs) {
        Output output = runOutputWithEffort(transport, source, apiKey, model, reasoningEffort, prompt, outputCap, timeoutMs, null);
        return output == null || output.text.isEmpty() ? null : output.text;
    }

    /** Same primitive with usage accounting and cooperative cancellation for isolated workers. */
    static Output runOutputWithEffort(ChatTransport transport, ChatDatabase.Provider source, String apiKey, String model,
                                      String reasoningEffort, CanonicalPrompt prompt, int outputCap, long timeoutMs,
                                      ClientToolRuntime.TurnCancellation cancellation) {
        if (transport == null || source == null || apiKey == null || apiKey.isEmpty()) return null;
        ChatDatabase.Provider provider = copy(source);
        provider.maxOutputTokens = Math.max(128, Math.min(8192, outputCap));
        StringBuilder text = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ChatTransport.Turn> turnRef = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        int[] usage = new int[]{0, 0};
        ChatTransport.Sink sink = new ChatTransport.Sink() {
            @Override public void onTextDelta(String delta) { if (delta != null && text.length() < 64_000) text.append(delta); }
            @Override public void onUsage(Integer inputTokens, Integer outputTokens) {
                if (inputTokens != null) usage[0] = Math.max(usage[0], Math.max(0, inputTokens));
                if (outputTokens != null) usage[1] = Math.max(usage[1], Math.max(0, outputTokens));
            }
            @Override public void onUsageDetails(Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                                                 Integer cacheWriteInputTokens, Integer cacheHitBaseTokens, Integer reasoningTokens) {
                onUsage(inputTokens, outputTokens);
            }
            @Override public void onCompleted() { done.countDown(); }
            @Override public void onError(String code, String summary, boolean retryable) { failure.set(code == null ? "preflight_error" : code); done.countDown(); }
            @Override public void onClientToolBatch(org.json.JSONArray calls) { failure.set("unexpected_tool_call"); done.countDown(); }
        };
        try {
            if (cancellation != null && cancellation.isCancelled()) return null;
            ChatTransport.Turn turn = transport.stream(provider, apiKey, model,
                    ReasoningPolicy.normalizeEffort(reasoningEffort), prompt, sink);
            turnRef.set(turn);
            long timeout = Math.max(1000, timeoutMs);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            while (done.getCount() > 0) {
                if (cancellation != null && cancellation.isCancelled()) {
                    if (turn != null) turn.cancel();
                    return null;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    if (turn != null) turn.cancel();
                    return null;
                }
                long waitMs = Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                done.await(waitMs, TimeUnit.MILLISECONDS);
            }
            return failure.get() == null && text.length() > 0 ? new Output(text.toString(), usage[0], usage[1]) : null;
        } catch (Exception ignored) {
            ChatTransport.Turn turn = turnRef.get(); if (turn != null) try { turn.cancel(); } catch (Exception ignored2) { }
            return null;
        }
    }

    private static ChatDatabase.Provider copy(ChatDatabase.Provider p) {
        ChatDatabase.Provider out = new ChatDatabase.Provider();
        out.id = p.id; out.displayName = p.displayName; out.protocol = p.protocol; out.baseUrl = p.baseUrl;
        out.authMode = p.authMode; out.reasoningWire = p.reasoningWire; out.model = p.model;
        out.thinkingMode = p.thinkingMode; out.thinkingBudgetTokens = p.thinkingBudgetTokens;
        out.contextTokens = p.contextTokens; out.inputTokens = p.inputTokens; out.maxOutputTokens = p.maxOutputTokens;
        return out;
    }
}
