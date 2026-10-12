package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * In-use statistics per model × context mode over a window of turns: how fast and how expensive the
 * turns were, how much of the cost went to helper stages, how often each helper actually acted, and
 * how the user rated the answers. Trends from real use, not a benchmark.
 */
final class TurnStats {
    /** Stages that exist only to help the main call (same set as the frontend's helper share). */
    static final java.util.Set<String> HELPER_STAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            TurnTrace.ROUTE, TurnTrace.CHECKPOINT, TurnTrace.WEB_PLAN, TurnTrace.VERIFY, TurnTrace.REPAIR, "subagent"));

    static final class Row {
        final String model, contextMode;
        int turns, ok, failed, cancelled, ratedUp, ratedDown;
        int firstSamples, totalSamples;
        long firstMsSum, totalMsSum;
        long mainTokens, helperTokens;
        int routed, checkpointed, webUsed, verified, repaired;
        Row(String model, String contextMode) { this.model = model; this.contextMode = contextMode; }

        JSONObject toJson() throws org.json.JSONException {
            long tokens = mainTokens + helperTokens;
            JSONObject out = new JSONObject().put("model", model).put("contextMode", contextMode)
                    .put("turns", turns).put("ok", ok).put("failed", failed).put("cancelled", cancelled)
                    .put("mainTokens", mainTokens).put("helperTokens", helperTokens)
                    .put("routed", routed).put("checkpointed", checkpointed).put("webUsed", webUsed)
                    .put("verified", verified).put("repaired", repaired)
                    .put("ratedUp", ratedUp).put("ratedDown", ratedDown);
            if (firstSamples > 0) out.put("avgFirstOutputMs", firstMsSum / firstSamples);
            if (totalSamples > 0) out.put("avgDurationMs", totalMsSum / totalSamples);
            if (turns > 0) out.put("avgTokens", tokens / turns);
            if (tokens > 0) out.put("helperShare", (double) helperTokens / tokens);
            return out;
        }
    }

    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();

    /** One finished turn: its stored config / usage / status / rating and its spans. */
    void add(JSONObject config, JSONObject usage, String status, Integer rating, List<JSONObject> spans) {
        String model = nonEmpty(config.optString("modelName", ""), config.optString("model", "unknown"));
        String mode = nonEmpty(config.optString("effectiveContextMode", ""), config.optString("contextMode", "standard"));
        Row row = rows.get(model + "\n" + mode);
        if (row == null) { row = new Row(model, mode); rows.put(model + "\n" + mode, row); }
        row.turns++;
        if ("ok".equals(status)) row.ok++;
        else if ("cancelled".equals(status)) row.cancelled++;
        else if (!"running".equals(status)) row.failed++;
        if (rating != null && rating > 0) row.ratedUp++;
        if (rating != null && rating < 0) row.ratedDown++;
        if (usage != null && usage.has("firstOutputLatencyMs")) { row.firstMsSum += usage.optLong("firstOutputLatencyMs"); row.firstSamples++; }
        if (usage != null && usage.has("requestDurationMs")) { row.totalMsSum += usage.optLong("requestDurationMs"); row.totalSamples++; }
        for (JSONObject span : spans) {
            String stage = span.optString("stage", "");
            String spanStatus = span.optString("status", "");
            long tokens = span.optLong("inputTokens", 0L) + span.optLong("outputTokens", 0L);
            if (HELPER_STAGES.contains(stage)) row.helperTokens += tokens; else row.mainTokens += tokens;
            if (!TurnTrace.OK.equals(spanStatus)) continue;
            JSONObject detail = span.optJSONObject("detail");
            if (TurnTrace.ROUTE.equals(stage)) row.routed++;
            else if (TurnTrace.CHECKPOINT.equals(stage) && detail != null && detail.optBoolean("updated", false)) row.checkpointed++;
            else if (TurnTrace.WEB_PLAN.equals(stage) && detail != null && detail.optBoolean("useWeb", false)) row.webUsed++;
            else if (TurnTrace.VERIFY.equals(stage)) { row.verified++; if (detail != null && detail.optBoolean("repaired", false)) row.repaired++; }
        }
    }

    /** Rows by turn count, most used first. */
    JSONArray toJson() throws org.json.JSONException {
        List<Row> sorted = new ArrayList<>(rows.values());
        sorted.sort((a, b) -> Integer.compare(b.turns, a.turns));
        JSONArray out = new JSONArray();
        for (Row row : sorted) out.put(row.toJson());
        return out;
    }

    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
}
