package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Which stages of one turn ran, which were skipped and why, how long each took and what it decided.
 * The trace answers "is this helper still worth it for this model?", so a skipped stage is recorded
 * with its reason instead of being left out.
 */
final class TurnTrace {
    static final String ACCEPT = "accept", CONTEXT = "context", ROUTE = "route", CHECKPOINT = "checkpoint",
            WEB_PLAN = "web_plan", TOOLS = "tools", WEB_RETRIEVE = "web_retrieve", GENERATE = "generate",
            VERIFY = "verify", REPAIR = "repair";
    static final String OK = "ok", SKIPPED = "skipped", FAILED = "failed", CANCELLED = "cancelled";

    static final class Span {
        final String stage;
        final long startedAtMs;
        private final long startedNanos;
        long durationMs = -1L;
        String status;
        String reason = "";
        JSONObject detail = new JSONObject();

        private Span(String stage) {
            this.stage = stage;
            this.startedAtMs = System.currentTimeMillis();
            this.startedNanos = System.nanoTime();
        }
    }

    private final ArrayList<Span> spans = new ArrayList<>();
    private JSONObject stageUsage = new JSONObject();
    private String terminalStatus = "";
    private String terminalCode = "";

    synchronized Span begin(String stage) {
        Span span = new Span(stage);
        spans.add(span);
        return span;
    }

    /** Ends an open span once; later calls (a racing terminal path) are ignored. */
    synchronized void end(Span span, String status, JSONObject detail) {
        if (span == null || span.status != null) return;
        span.status = status;
        span.durationMs = Math.max(0L, (System.nanoTime() - span.startedNanos) / 1_000_000L);
        if (detail != null) span.detail = detail;
    }

    synchronized void fail(Span span, String code) {
        if (span == null || span.status != null) return;
        end(span, FAILED, null);
        span.reason = code == null ? "" : code;
    }

    synchronized void skip(String stage, String reason) {
        Span span = new Span(stage);
        span.status = SKIPPED;
        span.durationMs = 0L;
        span.reason = reason == null ? "" : reason;
        spans.add(span);
    }

    /** Terminal: spans a failure or cancellation never reached the normal end of are closed with it. */
    synchronized void terminate(String[] error) {
        boolean ok = error == null;
        boolean cancelled = !ok && "cancelled".equals(error[0]);
        terminalStatus = ok ? OK : cancelled ? CANCELLED : FAILED;
        terminalCode = ok ? "" : error[0];
        for (Span span : spans) {
            if (span.status != null) continue;
            end(span, terminalStatus, null);
            span.reason = terminalCode;
        }
    }

    synchronized String status(String stage) {
        for (int i = spans.size() - 1; i >= 0; i--) if (spans.get(i).stage.equals(stage)) return spans.get(i).status;
        return null;
    }

    synchronized String reason(String stage) {
        for (int i = spans.size() - 1; i >= 0; i--) if (spans.get(i).stage.equals(stage)) return spans.get(i).reason;
        return null;
    }

    synchronized List<String> stages(String status) {
        ArrayList<String> out = new ArrayList<>();
        for (Span span : spans) if (status.equals(span.status) && !out.contains(span.stage)) out.add(span.stage);
        return out;
    }

    synchronized String terminalStatus() { return terminalStatus; }

    /** Model-call usage per stage from {@link TurnUsage#stageUsage()}, attached when the turn finishes. */
    synchronized void attachUsage(JSONObject usage) { stageUsage = usage == null ? new JSONObject() : usage; }

    synchronized JSONObject usage(String stage) { return stageUsage.optJSONObject(stage); }

    /** Spans in start order with their stage usage; calls without a span (subagents) are listed after. */
    synchronized JSONArray toJson() throws org.json.JSONException {
        JSONArray out = new JSONArray();
        ArrayList<String> merged = new ArrayList<>();
        for (Span span : spans) {
            JSONObject item = new JSONObject().put("stage", span.stage).put("status", span.status == null ? "open" : span.status)
                    .put("startedAtMs", span.startedAtMs).put("durationMs", span.durationMs);
            if (!span.reason.isEmpty()) item.put("reason", span.reason);
            if (span.detail.length() > 0) item.put("detail", new JSONObject(span.detail.toString()));
            JSONObject usage = stageUsage.optJSONObject(span.stage);
            if (usage != null && !merged.contains(span.stage)) { item.put("usage", usage); merged.add(span.stage); }
            out.put(item);
        }
        // Calls without a span of their own (subagents run inside client-tool rounds) still show their cost.
        JSONArray names = stageUsage.names();
        if (names != null) for (int i = 0; i < names.length(); i++) {
            String stage = names.optString(i);
            if (merged.contains(stage)) continue;
            out.put(new JSONObject().put("stage", stage).put("status", OK).put("durationMs", -1L).put("usage", stageUsage.optJSONObject(stage)));
        }
        return out;
    }
}
