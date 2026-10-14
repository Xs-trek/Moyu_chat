package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-use statistics over a window of turns, grouped by any of model / route protocol / reasoning effort /
 * context mode: how fast (medians) and how expensive the turns were, how much went to helper stages and
 * cache hits, how often each helper actually acted, and how the user rated the answers. Trends from real
 * use, not a benchmark.
 */
final class TurnStats {
    /** Stages that exist only to help the main call (same set as the frontend's helper share). */
    static final java.util.Set<String> HELPER_STAGES = new java.util.HashSet<>(java.util.Arrays.asList(
            TurnTrace.ROUTE, TurnTrace.CHECKPOINT, TurnTrace.WEB_PLAN, TurnTrace.VERIFY, TurnTrace.REPAIR, "subagent"));

    static final String BY_MODEL = "model", BY_PROTOCOL = "protocol", BY_EFFORT = "effort", BY_MODE = "mode", BY_HELPERS = "helpers";
    /** Helper-switch group of a turn with every helper on (or recorded before the switches existed). */
    static final String ALL_HELPERS_ON = "all_on";
    /** Grouping keys a request may ask for; the default is model × context mode. */
    static final String[][] GROUPINGS = {
            {BY_MODEL, BY_MODE}, {BY_MODEL}, {BY_PROTOCOL}, {BY_EFFORT}, {BY_MODE}, {BY_MODEL, BY_EFFORT}, {BY_HELPERS}, {BY_MODEL, BY_HELPERS}};

    /** "model,mode" → the matching grouping; anything else falls back to model × mode. */
    static String[] grouping(String raw) {
        String value = raw == null ? "" : raw.trim();
        for (String[] g : GROUPINGS) if (String.join(",", g).equals(value)) return g;
        return GROUPINGS[0];
    }

    static final class Row {
        final JSONObject group;
        int turns, ok, failed, cancelled, ratedUp, ratedDown;
        final List<Long> firstMs = new ArrayList<>(), totalMs = new ArrayList<>();
        long mainTokens, helperTokens, cachedInput, cacheBase;
        final Map<String, Double> cost = new LinkedHashMap<>();
        int costedTurns, partialCostTurns;
        int routed, checkpointed, webUsed, verified, repaired;
        /** Turns counted here that have since been retried, rewound or deleted from their Chat. */
        int archived;
        Row(JSONObject group) { this.group = group; }

        JSONObject toJson() throws org.json.JSONException {
            long tokens = mainTokens + helperTokens;
            JSONObject out = new JSONObject().put("group", group)
                    .put("turns", turns).put("ok", ok).put("failed", failed).put("cancelled", cancelled)
                    .put("mainTokens", mainTokens).put("helperTokens", helperTokens)
                    .put("routed", routed).put("checkpointed", checkpointed).put("webUsed", webUsed)
                    .put("verified", verified).put("repaired", repaired)
                    .put("ratedUp", ratedUp).put("ratedDown", ratedDown).put("archived", archived);
            // Old field names stay for the current UI bundle while it switches to `group`.
            if (group.has(BY_MODEL)) out.put("model", group.optString(BY_MODEL));
            if (group.has(BY_MODE)) out.put("contextMode", group.optString(BY_MODE));
            Long first = median(firstMs), total = median(totalMs);
            if (first != null) out.put("medianFirstOutputMs", first);
            if (total != null) out.put("medianDurationMs", total);
            if (turns > 0) out.put("avgTokens", tokens / turns);
            if (tokens > 0) out.put("helperShare", (double) helperTokens / tokens);
            if (cacheBase > 0) out.put("cacheHitRate", Math.min(1d, (double) cachedInput / cacheBase));
            if (!cost.isEmpty()) {
                JSONObject amounts = new JSONObject();
                for (Map.Entry<String, Double> e : cost.entrySet()) amounts.put(e.getKey(), e.getValue());
                out.put("cost", amounts).put("costedTurns", costedTurns).put("partialCostTurns", partialCostTurns);
            }
            return out;
        }
    }

    static Long median(List<Long> values) {
        if (values.isEmpty()) return null;
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2;
    }

    private final String[] dims;
    private final LinkedHashMap<String, Row> rows = new LinkedHashMap<>();

    TurnStats() { this(GROUPINGS[0]); }
    TurnStats(String[] dims) { this.dims = dims == null || dims.length == 0 ? GROUPINGS[0] : dims; }

    private static String dimension(JSONObject config, String dim) {
        switch (dim) {
            case BY_MODEL: return nonEmpty(config.optString("modelName", ""), config.optString("model", "unknown"));
            case BY_PROTOCOL: return nonEmpty(config.optString("protocol", ""), "unknown");
            case BY_EFFORT: return ReasoningPolicy.normalizeEffort(config.optString("reasoningEffort", ReasoningPolicy.DEFAULT));
            case BY_HELPERS: {
                JSONArray off = config.optJSONArray("helpersOff");
                StringBuilder ids = new StringBuilder();
                if (off != null) for (int i = 0; i < off.length(); i++) { if (ids.length() > 0) ids.append('+'); ids.append(off.optString(i)); }
                return ids.length() == 0 ? ALL_HELPERS_ON : ids.toString();
            }
            default: return nonEmpty(config.optString("effectiveContextMode", ""), config.optString("contextMode", "standard"));
        }
    }

    /** One finished turn: its stored config / usage / status / rating and its spans. */
    void add(JSONObject config, JSONObject usage, String status, Integer rating, List<JSONObject> spans) throws org.json.JSONException {
        row(config, usage, status, rating, spans);
    }

    /** A turn since removed from its Chat (turn_archive): counted like any other, and also as archived. */
    void addArchived(JSONObject config, JSONObject usage, String status, Integer rating, List<JSONObject> spans) throws org.json.JSONException {
        row(config, usage, status, rating, spans).archived++;
    }

    private Row row(JSONObject config, JSONObject usage, String status, Integer rating, List<JSONObject> spans) throws org.json.JSONException {
        JSONObject group = new JSONObject();
        StringBuilder key = new StringBuilder();
        for (String dim : dims) { String value = dimension(config, dim); group.put(dim, value); key.append(value).append('\n'); }
        Row row = rows.get(key.toString());
        if (row == null) { row = new Row(group); rows.put(key.toString(), row); }
        row.turns++;
        if ("ok".equals(status)) row.ok++;
        else if ("cancelled".equals(status)) row.cancelled++;
        else if (!"running".equals(status)) row.failed++;
        if (rating != null && rating > 0) row.ratedUp++;
        if (rating != null && rating < 0) row.ratedDown++;
        if (usage != null) {
            if (usage.has("firstOutputLatencyMs")) row.firstMs.add(usage.optLong("firstOutputLatencyMs"));
            if (usage.has("requestDurationMs")) row.totalMs.add(usage.optLong("requestDurationMs"));
            if (usage.has("cacheHitBaseTokens")) { row.cacheBase += usage.optLong("cacheHitBaseTokens"); row.cachedInput += usage.optLong("cachedInputTokens", 0L); }
            JSONObject cost = usage.optJSONObject("cost");
            if (cost != null && cost.has("amount")) {
                String currency = cost.optString("currency", "USD");
                row.cost.put(currency, row.cost.getOrDefault(currency, 0d) + cost.optDouble("amount", 0));
                row.costedTurns++;
                if (cost.optBoolean("partial", false)) row.partialCostTurns++;
            }
        }
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
        return row;
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
