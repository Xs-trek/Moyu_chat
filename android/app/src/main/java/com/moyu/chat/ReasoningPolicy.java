package com.moyu.chat;

import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Small provider-neutral reasoning vocabulary plus a few explicit wire encodings. */
final class ReasoningPolicy {
    static final String DEFAULT = "default";
    static final String NONE_LEVEL = "none";
    static final String LOW = "low";
    static final String MEDIUM = "medium";
    static final String HIGH = "high";
    static final String XHIGH = "xhigh";
    static final String MAX = "max";

    static final String WIRE_NONE = "none";
    static final String WIRE_OPENAI_RESPONSES = "openai-responses";
    static final String WIRE_OPENAI_CHAT_EFFORT = "openai-chat-effort";
    static final String WIRE_ANTHROPIC_EFFORT = "anthropic-effort";

    private ReasoningPolicy() { }

    static String defaultWireForProtocol(String protocol) {
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return WIRE_OPENAI_RESPONSES;
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return WIRE_ANTHROPIC_EFFORT;
        return WIRE_NONE;
    }

    static String normalizeWire(String value, String protocol) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return defaultWireForProtocol(protocol);
        if (WIRE_NONE.equals(v) || WIRE_OPENAI_RESPONSES.equals(v)
                || WIRE_OPENAI_CHAT_EFFORT.equals(v) || WIRE_ANTHROPIC_EFFORT.equals(v)) return v;
        throw new IllegalArgumentException("unsupported reasoning wire format");
    }

    static String normalizeEffort(String value) {
        String v = value == null ? "" : value.trim().toLowerCase();
        if (v.isEmpty()) return DEFAULT;
        if (DEFAULT.equals(v) || NONE_LEVEL.equals(v) || LOW.equals(v) || MEDIUM.equals(v)
                || HIGH.equals(v) || XHIGH.equals(v) || MAX.equals(v)) return v;
        throw new IllegalArgumentException("unsupported reasoning effort");
    }

    /** Empty means this deployment/model does not advertise an effort selector. */
    static String normalizeLevels(String csv) {
        if (csv == null || csv.trim().isEmpty()) return "";
        Set<String> out = new LinkedHashSet<>();
        for (String raw : csv.split(",")) {
            String level = normalizeEffort(raw);
            if (DEFAULT.equals(level)) continue;
            out.add(level);
        }
        return String.join(",", out);
    }

    static boolean allows(String levels, String effort) {
        String normalized = normalizeEffort(effort);
        if (DEFAULT.equals(normalized)) return true;
        String allowed = normalizeLevels(levels);
        if (allowed.isEmpty()) return false;
        for (String item : allowed.split(",")) if (normalized.equals(item)) return true;
        return false;
    }

    static void apply(JSONObject body, String wireFormat, String effort) throws Exception {
        String level = normalizeEffort(effort);
        if (DEFAULT.equals(level)) return; // provider/model default; preserve compatibility and cache semantics.
        String wire = normalizeWire(wireFormat, null);
        if (WIRE_NONE.equals(wire)) throw new IllegalArgumentException("reasoning effort is not supported by this provider configuration");
        if (WIRE_OPENAI_RESPONSES.equals(wire)) {
            body.put("reasoning", new JSONObject().put("effort", level));
        } else if (WIRE_OPENAI_CHAT_EFFORT.equals(wire)) {
            body.put("reasoning_effort", level);
        } else if (WIRE_ANTHROPIC_EFFORT.equals(wire)) {
            body.put("output_config", new JSONObject().put("effort", level));
        }
    }
}
