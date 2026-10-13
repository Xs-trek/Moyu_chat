package com.moyu.chat;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Resolves a Model Profile's capabilities in layers and records where each value came from:
 * default &lt; discovered (the endpoint's own model list) &lt; catalog (reviewed entry for this exact
 * preset route + model) &lt; probe (a test the user ran) &lt; manual (the user's override). Nothing is ever
 * inferred from a model name or a protocol; an unknown deployment simply keeps the conservative defaults.
 * Overrides are stored apart from resolved values, so a catalog update never erases what the user set.
 */
final class DeploymentResolver {
    static final String SRC_DEFAULT = "default";
    static final String SRC_DISCOVERED = "discovered";
    static final String SRC_CATALOG = "catalog";
    static final String SRC_PROBE = "probe";
    static final String SRC_MANUAL = "manual";

    /** Every field a layer may set and the user may override. */
    static final String[] FIELDS = {
            "contextTokens", "inputTokens", "maxOutputTokens", "inputModalities", "outputModalities",
            "reasoningWire", "reasoningLevels", "thinkingMode", "thinkingBudgetTokens",
            "toolCapabilities", "clientToolCalling", "continuationMode", "harnessPreset", "serviceTier", "pricing"
    };
    private static final java.util.Set<String> INT_FIELDS = new java.util.HashSet<>(java.util.Arrays.asList(
            "contextTokens", "inputTokens", "maxOutputTokens", "thinkingBudgetTokens"));

    /** Default output reservation when nobody declared one; Messages must always send a cap. */
    static final int ANTHROPIC_DEFAULT_OUTPUT_TOKENS = 32_000;

    static final class Resolution {
        final JSONObject values = new JSONObject();
        final JSONObject sources = new JSONObject();
        ProviderPresets.CatalogModel catalog;
    }

    private DeploymentResolver() { }

    static boolean known(String field) {
        for (String f : FIELDS) if (f.equals(field)) return true;
        return false;
    }

    static Resolution resolve(ChatDatabase.Route route, String modelId, JSONObject overrides, JSONObject discovered, JSONObject probe) {
        if (route == null) throw new IllegalArgumentException("route required");
        Resolution out = new Resolution();
        try {
            defaults(out, route.protocol);
            layer(out, discovered, SRC_DISCOVERED);
            out.catalog = ProviderPresets.get().catalog(route, modelId);
            if (out.catalog != null) layer(out, catalogValues(out.catalog), SRC_CATALOG);
            layer(out, probe, SRC_PROBE);
            layer(out, normalizeOverrides(overrides), SRC_MANUAL);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        return out;
    }

    /** Resolves {@code profile} on {@code route} in place: capability fields, sources, catalog stamp, retention. */
    static void apply(ChatDatabase.Route route, ChatDatabase.ModelProfile profile) {
        Resolution r = resolve(route, profile.modelId, json(profile.overrides), json(profile.discovered), json(profile.probe));
        JSONObject v = r.values;
        profile.routeId = route.id;
        profile.protocol = ChatDatabase.normalizeProtocol(route.protocol);
        profile.contextTokens = v.optInt("contextTokens"); profile.inputTokens = v.optInt("inputTokens"); profile.maxOutputTokens = v.optInt("maxOutputTokens");
        profile.inputModalities = v.optString("inputModalities"); profile.outputModalities = v.optString("outputModalities");
        profile.reasoningWire = v.optString("reasoningWire"); profile.reasoningLevels = v.optString("reasoningLevels");
        profile.thinkingMode = v.optString("thinkingMode"); profile.thinkingBudgetTokens = v.optInt("thinkingBudgetTokens");
        profile.toolCapabilities = v.optString("toolCapabilities"); profile.clientToolCalling = v.optString("clientToolCalling");
        profile.continuationMode = v.optString("continuationMode"); profile.harnessPreset = v.optString("harnessPreset");
        profile.serviceTier = v.optString("serviceTier");
        JSONObject pricing = v.optJSONObject("pricing");
        profile.pricing = pricing == null ? "{}" : pricing.toString();
        profile.sources = r.sources.toString();
        profile.overrides = normalizeOverrides(json(profile.overrides)).toString();
        profile.catalogKey = r.catalog == null ? "" : r.catalog.preset + "/" + r.catalog.route + "/" + r.catalog.id;
        profile.catalogVersion = r.catalog == null ? "" : ProviderPresets.get().version;
        profile.retention = r.catalog == null ? "" : r.catalog.retention;
        if ((profile.displayName == null || profile.displayName.trim().isEmpty()) && r.catalog != null) profile.displayName = r.catalog.name;
        String allowed = r.catalog == null ? "" : r.catalog.raw.optString("thinkingModes", "");
        if (!allowed.isEmpty() && !ReasoningPolicy.THINKING_PROVIDER_DEFAULT.equals(profile.thinkingMode)
                && !java.util.Arrays.asList(allowed.split(",")).contains(profile.thinkingMode)) {
            throw new IllegalArgumentException("该部署不接受 thinking 模式 " + profile.thinkingMode + "（目录记录的可选值：" + allowed.replace(",", " / ") + "）");
        }
    }

    /** True when the output cap goes on the wire: always for Messages, otherwise only when someone declared the limit. */
    static boolean sendsOutputCap(String protocol, String sourcesJson) {
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return true;
        String source = json(sourcesJson).optString("maxOutputTokens", SRC_DEFAULT);
        return !SRC_DEFAULT.equals(source);
    }

    /** Accepts only known fields with the right JSON type; numbers may arrive as strings from a form. */
    static JSONObject normalizeOverrides(JSONObject raw) {
        JSONObject out = new JSONObject();
        if (raw == null) return out;
        try {
            Iterator<String> keys = raw.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!known(key)) throw new IllegalArgumentException("未知的模型配置项：" + key);
                Object value = raw.opt(key);
                if (value == null || value == JSONObject.NULL) continue;
                if (INT_FIELDS.contains(key)) {
                    String text = String.valueOf(value).trim();
                    if (text.isEmpty()) continue;
                    out.put(key, ModelProfileValidator.parseInteger(label(key), text));
                } else if ("pricing".equals(key)) {
                    JSONObject pricing = value instanceof JSONObject ? (JSONObject) value : new JSONObject(String.valueOf(value));
                    out.put(key, ModelPricing.normalize(pricing));
                } else {
                    out.put(key, String.valueOf(value).trim());
                }
            }
        } catch (org.json.JSONException error) { throw new IllegalArgumentException("模型配置项格式无效"); }
        return out;
    }

    static String label(String field) {
        switch (field) {
            case "contextTokens": return "上下文窗口";
            case "inputTokens": return "输入上限";
            case "maxOutputTokens": return "输出上限";
            case "inputModalities": return "输入模态";
            case "outputModalities": return "输出模态";
            case "reasoningWire": return "推理参数";
            case "reasoningLevels": return "推理档位";
            case "thinkingMode": return "思考模式";
            case "thinkingBudgetTokens": return "手动思考预算";
            case "toolCapabilities": return "厂商联网工具";
            case "clientToolCalling": return "工具调用";
            case "continuationMode": return "跨轮续接";
            case "harnessPreset": return "Harness 档位";
            case "serviceTier": return "服务层级";
            case "pricing": return "价格";
            default: return field;
        }
    }

    private static void defaults(Resolution out, String protocol) throws org.json.JSONException {
        put(out, "contextTokens", ContextBudgetManager.DEFAULT_CONTEXT_TOKENS, SRC_DEFAULT);
        put(out, "inputTokens", 0, SRC_DEFAULT);
        put(out, "maxOutputTokens", AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? ANTHROPIC_DEFAULT_OUTPUT_TOKENS : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS, SRC_DEFAULT);
        put(out, "inputModalities", "text", SRC_DEFAULT);
        put(out, "outputModalities", "text", SRC_DEFAULT);
        put(out, "reasoningWire", ReasoningPolicy.WIRE_NONE, SRC_DEFAULT);
        put(out, "reasoningLevels", "", SRC_DEFAULT);
        put(out, "thinkingMode", ReasoningPolicy.THINKING_PROVIDER_DEFAULT, SRC_DEFAULT);
        put(out, "thinkingBudgetTokens", 0, SRC_DEFAULT);
        put(out, "toolCapabilities", "", SRC_DEFAULT);
        put(out, "clientToolCalling", ClientToolRuntime.NONE, SRC_DEFAULT);
        put(out, "continuationMode", ProviderContinuationPolicy.NONE, SRC_DEFAULT);
        put(out, "harnessPreset", HarnessProfile.BALANCED, SRC_DEFAULT);
        put(out, "serviceTier", "", SRC_DEFAULT);
        put(out, "pricing", new JSONObject(), SRC_DEFAULT);
    }

    /** Catalog JSON → field values. Only declared keys appear; absent means "not declared". */
    static JSONObject catalogValues(ProviderPresets.CatalogModel m) throws org.json.JSONException {
        JSONObject raw = m.raw, out = new JSONObject();
        if (raw.has("context")) out.put("contextTokens", raw.getInt("context"));
        if (raw.has("maxOutput")) out.put("maxOutputTokens", raw.getInt("maxOutput"));
        if (raw.has("input")) out.put("inputModalities", raw.getString("input"));
        if (raw.has("output")) out.put("outputModalities", raw.getString("output"));
        JSONObject reasoning = raw.optJSONObject("reasoning");
        if (reasoning != null) {
            out.put("reasoningWire", reasoning.optString("wire", ReasoningPolicy.WIRE_NONE));
            out.put("reasoningLevels", reasoning.optString("levels", ""));
        }
        if (raw.has("nativeWeb")) out.put("toolCapabilities", raw.getString("nativeWeb"));
        if (raw.has("clientTools")) out.put("clientToolCalling", raw.getString("clientTools"));
        if (raw.has("continuation")) out.put("continuationMode", raw.getString("continuation"));
        if (!m.harness.isEmpty()) out.put("harnessPreset", m.harness);
        if (raw.has("pricing")) out.put("pricing", ModelPricing.normalize(raw.getJSONObject("pricing")));
        return out;
    }

    private static void layer(Resolution out, JSONObject values, String source) throws org.json.JSONException {
        if (values == null) return;
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!known(key)) continue;
            put(out, key, values.get(key), source);
        }
    }

    private static void put(Resolution out, String field, Object value, String source) throws org.json.JSONException {
        out.values.put(field, value);
        out.sources.put(field, source);
    }

    static JSONObject json(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); } catch (org.json.JSONException error) { return new JSONObject(); }
    }
}
