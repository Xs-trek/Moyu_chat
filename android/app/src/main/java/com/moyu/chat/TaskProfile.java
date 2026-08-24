package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Narrow, model-assisted task classification. The model may choose only allowlisted labels/modules;
 * it never produces application-authority prompt text.
 */
final class TaskProfile {
    static final String[] DOMAINS = {"general", "software", "embedded", "research", "writing", "planning", "math", "creative"};
    static final String[] TASK_TYPES = {"answer", "explain", "debug", "review", "research", "plan", "write", "compare", "analyze"};
    static final String[] DEPTHS = {"concise", "standard", "expert"};
    static final String[] RESPONSE_MODES = {"direct", "structured", "tutorial", "diagnostic"};
    static final String[] CONTEXT_POLICIES = {"recent", "checkpoint_recent"};
    static final String[] MODULES = {"technical_precision", "debugging", "research_synthesis", "teaching", "planning", "creative"};

    final String domain;
    final String taskType;
    final String depth;
    final String responseMode;
    final String contextPolicy;
    final JSONArray modules;
    final double confidence;

    TaskProfile(String domain, String taskType, String depth, String responseMode,
                String contextPolicy, JSONArray modules, double confidence) {
        this.domain = allow(domain, DOMAINS, "general");
        this.taskType = allow(taskType, TASK_TYPES, "answer");
        this.depth = allow(depth, DEPTHS, "standard");
        this.responseMode = allow(responseMode, RESPONSE_MODES, "direct");
        this.contextPolicy = allow(contextPolicy, CONTEXT_POLICIES, "checkpoint_recent");
        this.modules = normalizeModules(modules);
        this.confidence = Math.max(0d, Math.min(1d, confidence));
    }

    static TaskProfile parseModelOutput(String raw) {
        try {
            JSONObject o = extractObject(raw);
            if (o == null) return null;
            return new TaskProfile(o.optString("domain"), o.optString("taskType"), o.optString("depth"),
                    o.optString("responseMode"), o.optString("contextPolicy"), o.optJSONArray("modules"),
                    o.optDouble("confidence", 0d));
        } catch (Exception ignored) { return null; }
    }

    static TaskProfile fromStored(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(raw);
            return new TaskProfile(o.optString("domain"), o.optString("taskType"), o.optString("depth"),
                    o.optString("responseMode"), o.optString("contextPolicy"), o.optJSONArray("modules"),
                    o.optDouble("confidence", 0d));
        } catch (Exception ignored) { return null; }
    }

    JSONObject toJson() {
        try {
            return new JSONObject().put("version", 1).put("domain", domain).put("taskType", taskType)
                    .put("depth", depth).put("responseMode", responseMode).put("contextPolicy", contextPolicy)
                    .put("modules", new JSONArray(modules.toString())).put("confidence", confidence);
        } catch (Exception ignored) { return new JSONObject(); }
    }

    private static JSONArray normalizeModules(JSONArray raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) for (int i = 0; i < raw.length() && out.size() < 3; i++) {
            String value = allow(raw.optString(i), MODULES, "");
            if (!value.isEmpty()) out.add(value);
        }
        JSONArray result = new JSONArray(); for (String value : out) result.put(value); return result;
    }

    private static String allow(String raw, String[] allowed, String fallback) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        for (String item : allowed) if (item.equals(value)) return item;
        return fallback;
    }

    private static JSONObject extractObject(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        int first = text.indexOf('{'), last = text.lastIndexOf('}');
        if (first < 0 || last <= first) return null;
        try { return new JSONObject(text.substring(first, last + 1)); }
        catch (Exception ignored) { return null; }
    }
}
