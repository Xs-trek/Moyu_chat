package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** One user turn's reported usage, including hidden calls that fail, time out or are cancelled. */
final class TurnUsage {
    enum Kind { MAIN, AUXILIARY, SUBAGENT }

    /** Receives redacted wire records ({@link WireCapture}) with the stage and call index that produced them. */
    interface WireRecorder { void record(String stage, int callIndex, String direction, String text); }

    private volatile WireRecorder wireRecorder;
    void setWireRecorder(WireRecorder recorder) { wireRecorder = recorder; }

    private static final class RequestUsage {
        final Kind kind;
        /** Pipeline stage that issued the call (TurnTrace stage id), so helper cost is attributable. */
        final String stage;
        /** Order of the call within the turn, shared with the wire records. */
        final int index;
        Integer input, output, cached, cacheWrite;
        final java.time.Instant startedAt = java.time.Instant.now();
        boolean ended;
        /** Why the call produced no usable result: the provider's error code, or "timeout" / "cancelled". */
        String failure;
        RequestUsage(Kind kind, String stage, int index) { this.kind = kind; this.stage = stage; this.index = index; }
    }

    private final ArrayList<RequestUsage> requests = new ArrayList<>();
    /** Prices the turn was accepted with; null = no estimate. */
    private volatile JSONObject pricing;
    void setPricing(JSONObject value) { pricing = value == null || value.length() == 0 ? null : value; }
    synchronized boolean hasRequests() { return !requests.isEmpty(); }

    private synchronized RequestUsage begin(Kind kind, String stage) {
        RequestUsage request = new RequestUsage(kind, stage, requests.size());
        requests.add(request);
        return request;
    }

    private synchronized void report(RequestUsage request, Integer input, Integer output) {
        if (request.ended) return;
        // Usage is cumulative per stream invocation (including any internal pause_turn replay).
        // The same cumulative values may arrive through both usage callbacks.
        if (input != null) request.input = Math.max(request.input == null ? 0 : request.input, Math.max(0, input));
        if (output != null) request.output = Math.max(request.output == null ? 0 : request.output, Math.max(0, output));
    }

    private synchronized void reportCache(RequestUsage request, Integer cached, Integer cacheWrite) {
        if (request.ended) return;
        if (cached != null) request.cached = Math.max(request.cached == null ? 0 : request.cached, Math.max(0, cached));
        if (cacheWrite != null) request.cacheWrite = Math.max(request.cacheWrite == null ? 0 : request.cacheWrite, Math.max(0, cacheWrite));
    }

    private synchronized void end(RequestUsage request) { request.ended = true; }

    private synchronized void fail(RequestUsage request, String code) {
        if (request.failure == null && !request.ended) request.failure = code == null || code.isEmpty() ? "provider_error" : code;
    }

    /** The failure of the last call a stage made, or null when it succeeded (or made no call). */
    synchronized String stageFailure(String stage) {
        for (int i = requests.size() - 1; i >= 0; i--) {
            RequestUsage request = requests.get(i);
            if (request.stage.equals(stage)) return request.failure;
        }
        return null;
    }

    ChatTransport track(ChatTransport delegate, Kind kind) { return track(delegate, kind, kind.name().toLowerCase(java.util.Locale.ROOT)); }

    ChatTransport track(ChatTransport delegate, Kind kind, String stage) {
        return new ChatTransport() {
            @Override public Turn stream(ChatDatabase.Provider provider, String key, String model, String effort,
                                         CanonicalPrompt prompt, Sink sink) {
                return stream(provider, key, model, effort, prompt, ChatTurnOptions.NONE, sink);
            }

            @Override public Turn stream(ChatDatabase.Provider provider, String key, String model, String effort,
                                         CanonicalPrompt prompt, ChatTurnOptions options, Sink sink) {
                RequestUsage request = begin(kind, stage);
                Sink tracked = new Sink() {
                    @Override public void onStage(String stage) { sink.onStage(stage); }
                    @Override public void onReasoningDelta(String text) { sink.onReasoningDelta(text); }
                    @Override public void onProviderContinuation(JSONObject state) { sink.onProviderContinuation(state); }
                    @Override public void onTextDelta(String text) { sink.onTextDelta(text); }
                    @Override public void onImage(String mime, String base64, String name) { sink.onImage(mime, base64, name); }
                    @Override public void onToolActivity(String tool, String state) { sink.onToolActivity(tool, state); }
                    @Override public void onWebSource(String url, String title) { sink.onWebSource(url, title); }
                    @Override public void onUsage(Integer input, Integer output) {
                        report(request, input, output); sink.onUsage(input, output);
                    }
                    @Override public void onUsageDetails(Integer input, Integer output, Integer cached,
                                                         Integer cacheWrite, Integer cacheBase, Integer reasoning) {
                        report(request, input, output);
                        reportCache(request, cached, cacheWrite);
                        sink.onUsageDetails(input, output, cached, cacheWrite, cacheBase, reasoning);
                    }
                    @Override public void onClientToolBatch(JSONArray calls) { end(request); sink.onClientToolBatch(calls); }
                    @Override public void onCompleted() { end(request); sink.onCompleted(); }
                    @Override public void onRetryAfter(long millis) { sink.onRetryAfter(millis); }
                    @Override public void onWire(String direction, String text) {
                        WireRecorder recorder = wireRecorder;
                        if (recorder != null) try { recorder.record(request.stage, request.index, direction, text); } catch (Exception ignored) { }
                        sink.onWire(direction, text);
                    }
                    @Override public void onError(String code, String summary, boolean retryable) {
                        fail(request, code); end(request); sink.onError(code, summary, retryable);
                    }
                };
                try {
                    Turn turn = delegate.stream(provider, key, model, effort, prompt, options, tracked);
                    return new Turn() {
                        @Override public void cancel() { stop("cancelled"); }
                        @Override public void timeout() { stop("timeout"); }
                        private void stop(String reason) {
                            fail(request, reason);
                            try { if (turn != null) turn.cancel(); }
                            finally { end(request); }
                        }
                    };
                } catch (RuntimeException error) {
                    end(request);
                    throw error;
                }
            }
        };
    }

    /**
     * Per-stage call counts and reported tokens: {stage: {calls, inputTokens?, outputTokens?, incomplete}}.
     * Unknown usage stays absent, as in {@link #project}.
     */
    synchronized JSONObject stageUsage() throws org.json.JSONException {
        JSONObject out = new JSONObject();
        for (RequestUsage request : requests) {
            JSONObject stage = out.optJSONObject(request.stage);
            if (stage == null) { stage = new JSONObject().put("calls", 0).put("incomplete", false); out.put(request.stage, stage); }
            stage.put("calls", stage.optInt("calls") + 1);
            if (request.input != null) stage.put("inputTokens", stage.optLong("inputTokens", 0L) + request.input);
            if (request.output != null) stage.put("outputTokens", stage.optLong("outputTokens", 0L) + request.output);
            if (request.input == null || request.output == null) stage.put("incomplete", true);
        }
        return out;
    }

    /** Unknown usage stays unknown; zero is written only when a provider reported it. */
    synchronized void project(JSONObject target) throws org.json.JSONException {
        long[][] totals = new long[Kind.values().length][2];
        boolean[][] known = new boolean[Kind.values().length][2];
        int[] counts = new int[Kind.values().length];
        boolean incomplete = false;
        Integer lastMainInput = null;
        for (RequestUsage request : requests) {
            int kind = request.kind.ordinal();
            counts[kind]++;
            if (request.kind == Kind.MAIN && request.input != null) lastMainInput = request.input;
            if (request.input != null) { totals[kind][0] += request.input; known[kind][0] = true; }
            if (request.output != null) { totals[kind][1] += request.output; known[kind][1] = true; }
            incomplete |= request.input == null || request.output == null;
        }
        String[] prefixes = { "", "auxiliary", "child" };
        long input = 0, output = 0;
        boolean inputKnown = false, outputKnown = false;
        for (int i = 0; i < prefixes.length; i++) {
            if (known[i][0]) target.put(i == 0 ? "inputTokens" : prefixes[i] + "InputTokens", totals[i][0]);
            if (known[i][1]) target.put(i == 0 ? "outputTokens" : prefixes[i] + "OutputTokens", totals[i][1]);
            input += totals[i][0]; output += totals[i][1];
            inputKnown |= known[i][0]; outputKnown |= known[i][1];
        }
        if (inputKnown) target.put("totalInputTokens", input);
        if (outputKnown) target.put("totalOutputTokens", output);
        projectCost(target);
        // inputTokens sums every main call of a tool loop; context fill is what the last one actually sent.
        if (lastMainInput != null) target.put("lastMainInputTokens", lastMainInput.longValue());
        target.put("modelCallCount", requests.size());
        target.put("auxiliaryCallCount", counts[Kind.AUXILIARY.ordinal()]);
        target.put("subagentCallCount", counts[Kind.SUBAGENT.ordinal()]);
        target.put("usageIncomplete", incomplete);
    }

    /**
     * Estimated cost of every call of the turn (helpers and subagents run on the same deployment), each priced
     * by its own prompt size and time. {@code partial} when some call reported no usage, so the sum is low.
     */
    private void projectCost(JSONObject target) throws org.json.JSONException {
        JSONObject prices = pricing;
        if (prices == null) return;
        double amount = 0;
        String currency = null;
        boolean partial = false, offPeak = false, any = false;
        for (RequestUsage request : requests) {
            if (request.input == null && request.output == null) { partial = true; continue; }
            if (request.input == null || request.output == null) partial = true;
            JSONObject estimate = ModelPricing.estimate(prices, request.input == null ? 0 : request.input, request.output == null ? 0 : request.output,
                    request.cached == null ? 0 : request.cached, request.cacheWrite == null ? 0 : request.cacheWrite, request.startedAt);
            if (estimate == null) return;
            amount += estimate.optDouble("amount", 0);
            currency = estimate.optString("currency", "USD");
            offPeak |= estimate.optBoolean("offPeak", false);
            any = true;
        }
        if (!any) return;
        target.put("cost", new JSONObject().put("currency", currency).put("amount", amount).put("partial", partial).put("offPeak", offPeak));
    }
}
