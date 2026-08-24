package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/** Provider-neutral, bounded model-generated summary of older conversational state. */
final class ContextCheckpoint {
    static final int MAX_SUMMARY = 3000;
    static final int MAX_ITEM = 400;
    static final int MAX_LIST = 10;

    final String summary;
    final JSONArray constraints;
    final JSONArray decisions;
    final JSONArray openQuestions;
    final JSONArray criticalRefs;

    ContextCheckpoint(String summary, JSONArray constraints, JSONArray decisions, JSONArray openQuestions, JSONArray criticalRefs) {
        this.summary = bounded(summary, MAX_SUMMARY);
        this.constraints = list(constraints);
        this.decisions = list(decisions);
        this.openQuestions = list(openQuestions);
        this.criticalRefs = list(criticalRefs);
    }

    static ContextCheckpoint parseModelOutput(String raw) {
        try {
            JSONObject o = extract(raw); if (o == null) return null;
            ContextCheckpoint checkpoint = new ContextCheckpoint(o.optString("summary"), o.optJSONArray("constraints"),
                    o.optJSONArray("decisions"), o.optJSONArray("openQuestions"), o.optJSONArray("criticalRefs"));
            return checkpoint.summary.isEmpty() && checkpoint.constraints.length() == 0 && checkpoint.decisions.length() == 0
                    && checkpoint.openQuestions.length() == 0 && checkpoint.criticalRefs.length() == 0 ? null : checkpoint;
        } catch (Exception ignored) { return null; }
    }

    static ContextCheckpoint fromStored(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return new ContextCheckpoint(o.optString("summary"), o.optJSONArray("constraints"), o.optJSONArray("decisions"),
                    o.optJSONArray("openQuestions"), o.optJSONArray("criticalRefs"));
        } catch (Exception ignored) { return null; }
    }

    JSONObject toJson() {
        try {
            return new JSONObject().put("version", 1).put("summary", summary)
                    .put("constraints", new JSONArray(constraints.toString())).put("decisions", new JSONArray(decisions.toString()))
                    .put("openQuestions", new JSONArray(openQuestions.toString())).put("criticalRefs", new JSONArray(criticalRefs.toString()));
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONArray list(JSONArray raw) {
        JSONArray out = new JSONArray(); if (raw == null) return out;
        for (int i = 0; i < raw.length() && out.length() < MAX_LIST; i++) {
            String value = bounded(raw.optString(i), MAX_ITEM); if (!value.isEmpty()) out.put(value);
        }
        return out;
    }

    private static String bounded(String raw, int max) {
        String value = raw == null ? "" : raw.replace('\u0000', ' ').trim();
        return value.length() > max ? value.substring(0, max) : value;
    }

    private static JSONObject extract(String raw) {
        if (raw == null) return null;
        int first = raw.indexOf('{'), last = raw.lastIndexOf('}');
        if (first < 0 || last <= first) return null;
        try { return new JSONObject(raw.substring(first, last + 1)); }
        catch (Exception ignored) { return null; }
    }
}
