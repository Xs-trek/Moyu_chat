package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/** One user turn's reported usage, including hidden calls that fail, time out or are cancelled. */
final class TurnUsage {
    enum Kind { MAIN, AUXILIARY, SUBAGENT }

    private static final class RequestUsage {
        final Kind kind;
        Integer input, output;
        boolean ended;
        RequestUsage(Kind kind) { this.kind = kind; }
    }

    private final ArrayList<RequestUsage> requests = new ArrayList<>();
    synchronized boolean hasRequests() { return !requests.isEmpty(); }

    private synchronized RequestUsage begin(Kind kind) {
        RequestUsage request = new RequestUsage(kind);
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

    private synchronized void end(RequestUsage request) { request.ended = true; }

    ChatTransport track(ChatTransport delegate, Kind kind) {
        return new ChatTransport() {
            @Override public Turn stream(ChatDatabase.Provider provider, String key, String model, String effort,
                                         CanonicalPrompt prompt, Sink sink) {
                return stream(provider, key, model, effort, prompt, ChatTurnOptions.NONE, sink);
            }

            @Override public Turn stream(ChatDatabase.Provider provider, String key, String model, String effort,
                                         CanonicalPrompt prompt, ChatTurnOptions options, Sink sink) {
                RequestUsage request = begin(kind);
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
                        sink.onUsageDetails(input, output, cached, cacheWrite, cacheBase, reasoning);
                    }
                    @Override public void onClientToolBatch(JSONArray calls) { end(request); sink.onClientToolBatch(calls); }
                    @Override public void onCompleted() { end(request); sink.onCompleted(); }
                    @Override public void onError(String code, String summary, boolean retryable) {
                        end(request); sink.onError(code, summary, retryable);
                    }
                };
                try {
                    Turn turn = delegate.stream(provider, key, model, effort, prompt, options, tracked);
                    return () -> {
                        try { if (turn != null) turn.cancel(); }
                        finally { end(request); }
                    };
                } catch (RuntimeException error) {
                    end(request);
                    throw error;
                }
            }
        };
    }

    /** Unknown usage stays unknown; zero is written only when a provider reported it. */
    synchronized void project(JSONObject target) throws org.json.JSONException {
        long[][] totals = new long[Kind.values().length][2];
        boolean[][] known = new boolean[Kind.values().length][2];
        int[] counts = new int[Kind.values().length];
        boolean incomplete = false;
        for (RequestUsage request : requests) {
            int kind = request.kind.ordinal();
            counts[kind]++;
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
        target.put("modelCallCount", requests.size());
        target.put("auxiliaryCallCount", counts[Kind.AUXILIARY.ordinal()]);
        target.put("subagentCallCount", counts[Kind.SUBAGENT.ordinal()]);
        target.put("usageIncomplete", incomplete);
    }
}
