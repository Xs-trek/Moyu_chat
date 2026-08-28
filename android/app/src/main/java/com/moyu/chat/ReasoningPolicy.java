package com.moyu.chat;

import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Provider-neutral effort vocabulary plus explicit provider thinking-mode configuration. */
final class ReasoningPolicy {
    static final String DEFAULT = "default";
    static final String NONE_LEVEL = "none";
    static final String MINIMAL = "minimal";
    static final String LOW = "low";
    static final String MEDIUM = "medium";
    static final String HIGH = "high";
    static final String XHIGH = "xhigh";
    static final String MAX = "max";

    static final String WIRE_NONE = "none";
    static final String WIRE_OPENAI_RESPONSES = "openai-responses";
    static final String WIRE_OPENAI_CHAT_EFFORT = "openai-chat-effort";
    static final String WIRE_ANTHROPIC_EFFORT = "anthropic-effort";

    static final String THINKING_PROVIDER_DEFAULT = "provider-default";
    static final String THINKING_ADAPTIVE = "adaptive";
    static final String THINKING_DISABLED = "disabled";
    static final String THINKING_MANUAL = "manual";

    private ReasoningPolicy() { }

    static String defaultWireForProtocol(String protocol) {
        // Protocol compatibility does not prove optional reasoning-effort compatibility. Third-party
        // gateways are the primary deployment target, so blank means no optional effort field.
        return WIRE_NONE;
    }

    static String normalizeWire(String value, String protocol) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) v = defaultWireForProtocol(protocol);
        if (!(WIRE_NONE.equals(v) || WIRE_OPENAI_RESPONSES.equals(v)
                || WIRE_OPENAI_CHAT_EFFORT.equals(v) || WIRE_ANTHROPIC_EFFORT.equals(v))) {
            throw new IllegalArgumentException("unsupported reasoning wire format");
        }
        if (WIRE_NONE.equals(v)) return v;
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol) && WIRE_OPENAI_RESPONSES.equals(v)) return v;
        if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol) && WIRE_OPENAI_CHAT_EFFORT.equals(v)) return v;
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol) && WIRE_ANTHROPIC_EFFORT.equals(v)) return v;
        throw new IllegalArgumentException("reasoning wire is incompatible with selected protocol");
    }

    static String normalizeThinkingMode(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) return THINKING_PROVIDER_DEFAULT;
        if (THINKING_PROVIDER_DEFAULT.equals(v) || THINKING_ADAPTIVE.equals(v)
                || THINKING_DISABLED.equals(v) || THINKING_MANUAL.equals(v)) return v;
        throw new IllegalArgumentException("unsupported thinking mode");
    }

    static String normalizeEffort(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.isEmpty()) return DEFAULT;
        if (DEFAULT.equals(v) || NONE_LEVEL.equals(v) || MINIMAL.equals(v) || LOW.equals(v) || MEDIUM.equals(v)
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

    static void validateLevelsForProvider(ChatDatabase.Provider provider, String levels) {
        if (provider == null) throw new IllegalArgumentException("provider required");
        String normalized = normalizeLevels(levels);
        if (normalized.isEmpty()) return;
        String wire = normalizeWire(provider.reasoningWire, provider.protocol);
        if (WIRE_NONE.equals(wire)) throw new IllegalArgumentException("reasoning levels require an enabled reasoning wire");
        for (String item : normalized.split(",")) {
            if (AnthropicMessagesTransport.PROTOCOL.equals(provider.protocol)
                    && (NONE_LEVEL.equals(item) || MINIMAL.equals(item))) {
                throw new IllegalArgumentException("anthropic-messages effort supports low/medium/high/xhigh/max; thinking on/off is configured separately");
            }
        }
    }

    static void validateEffortForProvider(ChatDatabase.Provider provider, String levels, String effort) {
        String normalized = normalizeEffort(effort);
        if (!allows(levels, normalized)) throw new IllegalArgumentException("reasoning effort not supported by model profile");
        if (DEFAULT.equals(normalized)) return;
        validateLevelsForProvider(provider, levels);
        if (AnthropicMessagesTransport.PROTOCOL.equals(provider.protocol) && NONE_LEVEL.equals(normalized)) {
            throw new IllegalArgumentException("anthropic-messages does not use effort=none; configure thinking separately");
        }
    }

    /** Current transport entry point: thinking mode is independent from effort on Anthropic. */
    static void apply(JSONObject body, ChatDatabase.Provider provider, String effort) throws Exception {
        if (provider == null) throw new IllegalArgumentException("provider required");
        String level = normalizeEffort(effort);
        String wire = normalizeWire(provider.reasoningWire, provider.protocol);

        // Messages-protocol thinking is a model/deployment capability, independent from whether the
        // endpoint also implements Anthropic's optional output_config.effort field. This matters for
        // third-party Anthropic-compatible gateways that implement thinking but not effort.
        if (AnthropicMessagesTransport.PROTOCOL.equals(provider.protocol)) applyAnthropicThinking(body, provider);

        if (DEFAULT.equals(level)) return;
        if (AnthropicMessagesTransport.PROTOCOL.equals(provider.protocol)) {
            // Anthropic-compatible Messages has no effort="none". `none` means no effort override;
            // thinking on/off is controlled only by the explicit thinkingMode above.
            if (NONE_LEVEL.equals(level)) return;
            if (WIRE_NONE.equals(wire)) throw new IllegalArgumentException("reasoning effort is not enabled for this Messages endpoint");
            if (!WIRE_ANTHROPIC_EFFORT.equals(wire)) throw new IllegalArgumentException("reasoning wire is incompatible with anthropic-messages");
            body.put("output_config", new JSONObject().put("effort", level));
            return;
        }

        if (WIRE_NONE.equals(wire)) throw new IllegalArgumentException("reasoning effort is not supported by this provider configuration");
        if (WIRE_OPENAI_RESPONSES.equals(wire)) {
            body.put("reasoning", new JSONObject().put("effort", level));
        } else if (WIRE_OPENAI_CHAT_EFFORT.equals(wire)) {
            body.put("reasoning_effort", level);
        }
    }

    private static void applyAnthropicThinking(JSONObject body, ChatDatabase.Provider provider) throws Exception {
        String mode = normalizeThinkingMode(provider.thinkingMode);
        if (THINKING_PROVIDER_DEFAULT.equals(mode)) return;
        if (THINKING_ADAPTIVE.equals(mode)) {
            body.put("thinking", new JSONObject().put("type", "adaptive"));
            return;
        }
        if (THINKING_DISABLED.equals(mode)) {
            body.put("thinking", new JSONObject().put("type", "disabled"));
            return;
        }
        int budget = Math.max(0, provider.thinkingBudgetTokens);
        int maxOutput = provider.maxOutputTokens > 0 ? provider.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
        if (budget < 1024 || budget >= maxOutput) throw new IllegalArgumentException("manual thinking budget must be >=1024 and < max output tokens");
        body.put("thinking", new JSONObject().put("type", "enabled").put("budget_tokens", budget));
    }

    /** Compatibility overload for tests/older callers that have no explicit thinking configuration. */
    static void apply(JSONObject body, String wireFormat, String effort) throws Exception {
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.reasoningWire = wireFormat;
        provider.thinkingMode = THINKING_PROVIDER_DEFAULT;
        apply(body, provider, effort);
    }
}
