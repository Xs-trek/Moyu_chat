package com.moyu.chat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes a bounded hidden model call. Two kinds:
 * <ul>
 * <li>light — short structured control output (router, checkpoint, web planner): the lowest declared effort and
 * thinking off where the deployment's wire can say so, since these calls only fill a small JSON object;</li>
 * <li>with effort — calls whose quality matters (verifier, repair, subagent): the session's effort is kept.</li>
 * </ul>
 * Both reserve output room for reasoning tokens, so a reasoning model does not spend the whole cap before it
 * writes the answer. Failures surface through the usage tracker ({@link TurnUsage#stageFailure}).
 */
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

    /** One finished attempt: text and usage, or the failure code. */
    private static final class Attempt {
        final Output output;
        final String failure;
        Attempt(Output output, String failure) { this.output = output; this.failure = failure; }
        boolean ok() { return failure == null && output != null && !output.text.isEmpty(); }
    }

    private static final int MAX_CAP = 8192;

    private ModelPreflight() { }

    /** Light structured call (router, checkpoint); returns the text or null. */
    static String run(ChatTransport transport, ChatDatabase.Provider source, String apiKey, String model,
                      CanonicalPrompt prompt, int outputCap, long timeoutMs) {
        Output output = runLight(transport, source, apiKey, model, prompt, outputCap, timeoutMs, null);
        return output == null || output.text.isEmpty() ? null : output.text;
    }

    /**
     * Light structured call with usage and cooperative cancellation (web planner). If the lightened request is
     * refused as invalid (a gateway that rejects the thinking switch or the effort level), it is retried once
     * with the deployment's own reasoning configuration.
     */
    static Output runLight(ChatTransport transport, ChatDatabase.Provider source, String apiKey, String model,
                           CanonicalPrompt prompt, int outputCap, long timeoutMs,
                           ClientToolRuntime.TurnCancellation cancellation) {
        if (transport == null || source == null || apiKey == null || apiKey.isEmpty()) return null;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, timeoutMs));
        ChatDatabase.Provider light = copy(source);
        light.thinkingMode = ReasoningPolicy.lightThinkingMode(source);
        String effort = ReasoningPolicy.lightestEffort(source.reasoningLevels);
        Attempt attempt = execute(transport, light, apiKey, model, effort, prompt, outputCap, deadline, cancellation, 32_000);
        boolean lightened = !light.thinkingMode.equals(ReasoningPolicy.normalizeThinkingMode(source.thinkingMode))
                || !ReasoningPolicy.DEFAULT.equals(effort);
        if (!attempt.ok() && lightened && refusedAsInvalid(attempt.failure)) {
            attempt = execute(transport, copy(source), apiKey, model, ReasoningPolicy.DEFAULT, prompt, outputCap, deadline, cancellation, 32_000);
        }
        return attempt.ok() ? attempt.output : null;
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
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, timeoutMs));
        Attempt attempt = execute(transport, copy(source), apiKey, model, ReasoningPolicy.normalizeEffort(reasoningEffort),
                prompt, outputCap, deadline, cancellation, 64_000);
        return attempt.ok() ? attempt.output : null;
    }

    /** 400 / 422: the request shape was refused, so a request without the lightening may still succeed. */
    private static boolean refusedAsInvalid(String failure) {
        return "http_400".equals(failure) || "http_422".equals(failure);
    }

    private static Attempt execute(ChatTransport transport, ChatDatabase.Provider provider, String apiKey, String model,
                                   String effort, CanonicalPrompt prompt, int answerTokens, long deadline,
                                   ClientToolRuntime.TurnCancellation cancellation, int textLimit) {
        int cap = ReasoningPolicy.helperOutputCap(provider, effort, Math.max(128, answerTokens));
        // A declared deployment ceiling wins over headroom, but never below the requested answer room.
        if (provider.maxOutputTokens > 0) cap = Math.min(cap, Math.max(answerTokens, provider.maxOutputTokens));
        // A kept manual thinking budget must stay below the cap, even past the usual ceiling.
        int ceiling = ReasoningPolicy.THINKING_MANUAL.equals(ReasoningPolicy.normalizeThinkingMode(provider.thinkingMode))
                ? Math.max(MAX_CAP, provider.thinkingBudgetTokens + 1) : MAX_CAP;
        provider.maxOutputTokens = Math.max(128, Math.min(ceiling, cap));
        StringBuilder text = new StringBuilder();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<ChatTransport.Turn> turnRef = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        int[] usage = new int[]{0, 0};
        ChatTransport.Sink sink = new ChatTransport.Sink() {
            @Override public void onTextDelta(String delta) { if (delta != null && text.length() < textLimit) text.append(delta); }
            @Override public void onUsage(Integer inputTokens, Integer outputTokens) {
                if (inputTokens != null) usage[0] = Math.max(usage[0], Math.max(0, inputTokens));
                if (outputTokens != null) usage[1] = Math.max(usage[1], Math.max(0, outputTokens));
            }
            @Override public void onUsageDetails(Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                                                 Integer cacheWriteInputTokens, Integer cacheHitBaseTokens, Integer reasoningTokens) {
                onUsage(inputTokens, outputTokens);
            }
            @Override public void onCompleted() { done.countDown(); }
            @Override public void onError(String code, String summary, boolean retryable) { failure.compareAndSet(null, code == null ? "preflight_error" : code); done.countDown(); }
            @Override public void onClientToolBatch(org.json.JSONArray calls) { failure.compareAndSet(null, "unexpected_tool_call"); done.countDown(); }
        };
        try {
            if (cancellation != null && cancellation.isCancelled()) return new Attempt(null, "cancelled");
            ChatTransport.Turn turn = transport.stream(provider, apiKey, model, effort, prompt, sink);
            turnRef.set(turn);
            while (done.getCount() > 0) {
                if (cancellation != null && cancellation.isCancelled()) {
                    if (turn != null) turn.cancel();
                    return new Attempt(null, "cancelled");
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    if (turn != null) turn.timeout();
                    return new Attempt(null, "timeout");
                }
                long waitMs = Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                done.await(waitMs, TimeUnit.MILLISECONDS);
            }
            if (failure.get() != null) return new Attempt(null, failure.get());
            return text.length() == 0 ? new Attempt(null, "empty_output") : new Attempt(new Output(text.toString(), usage[0], usage[1]), null);
        } catch (Exception error) {
            ChatTransport.Turn turn = turnRef.get(); if (turn != null) try { turn.cancel(); } catch (Exception ignored) { }
            return new Attempt(null, "preflight_exception");
        }
    }

    private static ChatDatabase.Provider copy(ChatDatabase.Provider p) {
        ChatDatabase.Provider out = new ChatDatabase.Provider();
        out.id = p.id; out.displayName = p.displayName; out.protocol = p.protocol; out.baseUrl = p.baseUrl;
        out.authMode = p.authMode; out.reasoningWire = p.reasoningWire; out.model = p.model;
        out.thinkingMode = p.thinkingMode; out.thinkingBudgetTokens = p.thinkingBudgetTokens;
        out.reasoningLevels = p.reasoningLevels;
        out.contextTokens = p.contextTokens; out.inputTokens = p.inputTokens; out.maxOutputTokens = p.maxOutputTokens;
        out.presetId = p.presetId; out.routeId = p.routeId; out.dialect = p.dialect; out.headers = p.headers;
        out.serviceTier = p.serviceTier;
        // Hidden calls set their own small cap, which must reach the wire whatever the deployment declared.
        out.sendOutputCap = true;
        return out;
    }
}
