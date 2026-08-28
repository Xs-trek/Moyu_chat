package com.moyu.chat;

/** Strict user-facing validation for editable deployment fields. Never silently downgrades bad input. */
final class ModelProfileValidator {
    private ModelProfileValidator() { }

    static int parseInteger(String label, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException error) { throw new IllegalArgumentException(label + "必须填写整数，当前值：" + value); }
    }

    static void validateLimits(int context, int input, int output) {
        if (context < 2_048 || context > 4_000_000) {
            throw new IllegalArgumentException("Context Window 必须为 2,048–4,000,000 token，当前值：" + context);
        }
        if (input < 0 || input > context) {
            throw new IllegalArgumentException("Max Input 必须为 0–Context Window，当前值：" + input + "，Context=" + context);
        }
        if (output < 256 || output >= context) {
            throw new IllegalArgumentException("Max Output 必须 >=256 且小于 Context Window，当前值：" + output + "，Context=" + context);
        }
    }

    static String requireHarnessPreset(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (HarnessProfile.LEAN.equals(value) || HarnessProfile.BALANCED.equals(value) || HarnessProfile.REINFORCED.equals(value)) return value;
        throw new IllegalArgumentException("Harness preset 无效：" + value + "。可选值：lean / balanced / reinforced");
    }

    static String requireClientToolCalling(String raw, String protocol) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!(ClientToolRuntime.NONE.equals(value) || ClientToolRuntime.OPENAI_CHAT.equals(value)
                || ClientToolRuntime.OPENAI_RESPONSES.equals(value) || ClientToolRuntime.ANTHROPIC_TOOLS.equals(value))) {
            throw new IllegalArgumentException("Client tool calling 无效：" + value
                    + "。可选值：none / openai-chat-tools / openai-responses-tools / anthropic-tools");
        }
        if (!ClientToolRuntime.NONE.equals(value) && !ClientToolRuntime.compatible(protocol, value)) {
            throw new IllegalArgumentException("Client tool calling 与协议不兼容：protocol=" + protocol + "，clientToolCalling=" + value);
        }
        return value;
    }

    static String requireTextModality(String label, String raw) {
        String normalized;
        try { normalized = ChatDatabase.normalizeModalities(raw); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException(label + " 无效。当前版本仅支持 text 或 text,image"); }
        if (!ChatDatabase.supportsModality(normalized, "text")) {
            throw new IllegalArgumentException(label + " 必须包含 text；当前版本不支持纯 image Chat。可选值：text / text,image");
        }
        return normalized;
    }

    static String requireNativeWebCapabilities(String protocol, String raw) {
        String normalized;
        try { normalized = ChatTurnOptions.normalizeCapabilities(raw); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Provider-native Web capabilities 无效：" + safe(raw)); }
        if (normalized.isEmpty()) return "";
        String effective = ChatTurnOptions.effectiveCapabilities(protocol, normalized);
        if (!normalized.equals(effective)) {
            if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) {
                throw new IllegalArgumentException("Responses 当前仅支持声明 provider-native web_search；不要填写 web_fetch 或 Messages wire");
            }
            if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) {
                throw new IllegalArgumentException("Anthropic Messages 必须填写明确的 Web tool wire version，例如 web_search_20260209 / web_fetch_20250910；不能填写通用 web_search/web_fetch");
            }
            throw new IllegalArgumentException("openai-chat-completions 当前没有 Moyu 已实现的 provider-native Web schema；请留空并使用 Moyu Web fallback");
        }
        return normalized;
    }

    static String requireThinkingMode(String protocol, String raw, int budget, int maxOutput) {
        String mode;
        try { mode = ReasoningPolicy.normalizeThinkingMode(raw); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Messages thinking mode 无效：" + safe(raw)
                    + "。可选值：provider-default / adaptive / disabled / manual");
        }
        if (!AnthropicMessagesTransport.PROTOCOL.equals(protocol)) {
            if (!ReasoningPolicy.THINKING_PROVIDER_DEFAULT.equals(mode) || budget != 0) {
                throw new IllegalArgumentException("Messages thinking 仅适用于 anthropic-messages；当前 protocol=" + protocol
                        + "，请使用 provider-default 且 Manual thinking budget=0");
            }
            return mode;
        }
        if (ReasoningPolicy.THINKING_MANUAL.equals(mode)) {
            if (budget < 1024 || budget >= maxOutput) {
                throw new IllegalArgumentException("Manual thinking budget 必须 >=1024 且小于 Max Output；当前 budget="
                        + budget + "，Max Output=" + maxOutput);
            }
        } else if (budget != 0) {
            throw new IllegalArgumentException("Manual thinking budget 仅在 thinkingMode=manual 时填写；其它模式必须为 0");
        }
        return mode;
    }

    static String requireContinuationMode(String protocol, String raw) {
        String mode;
        try { mode = ProviderContinuationPolicy.normalize(raw); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Provider-private continuation 无效：" + safe(raw) + "。可选值：none / native"); }
        if (ProviderContinuationPolicy.NATIVE.equals(mode)
                && !(OpenAiCompatibleTransport.RESPONSES.equals(protocol) || AnthropicMessagesTransport.PROTOCOL.equals(protocol))) {
            throw new IllegalArgumentException("当前 protocol=" + protocol + " 没有 Moyu 已实现的 provider-private native continuation；请选择 none");
        }
        return mode;
    }

    static void validateResolvedProfile(ChatDatabase.Provider provider, ChatDatabase.ModelProfile profile) {
        if (provider == null || profile == null) throw new IllegalArgumentException("Provider / Model Profile 不存在");
        String protocol;
        try { protocol = ChatDatabase.normalizeProtocol(profile.protocol); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Protocol 无效：" + safe(profile.protocol)
                    + "。可选值：openai-chat-completions / openai-responses / anthropic-messages");
        }
        try { ProviderEndpoint.normalize(provider.baseUrl, protocol); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Base URL 与当前协议不兼容：" + error.getMessage()); }
        try { profile.authMode = ProviderAuth.normalize(profile.authMode, protocol); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Auth mode 无效：" + safe(profile.authMode) + "。可选值：bearer / x-api-key / api-key");
        }
        try { profile.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, protocol); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Reasoning wire 与协议不兼容：protocol=" + protocol + "，reasoningWire=" + safe(profile.reasoningWire));
        }
        try { profile.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Reasoning levels 包含不支持的档位：" + safe(profile.reasoningLevels)); }
        try { ReasoningPolicy.validateLevelsForProvider(ChatDatabase.deploymentProvider(provider, profile), profile.reasoningLevels); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Reasoning levels 配置无效：" + error.getMessage()); }

        validateLimits(profile.contextTokens, profile.inputTokens, profile.maxOutputTokens);
        profile.inputModalities = requireTextModality("Input modalities", profile.inputModalities);
        profile.outputModalities = requireTextModality("Output modalities", profile.outputModalities);
        profile.toolCapabilities = requireNativeWebCapabilities(protocol, profile.toolCapabilities);
        profile.harnessPreset = requireHarnessPreset(profile.harnessPreset);
        profile.clientToolCalling = requireClientToolCalling(profile.clientToolCalling, protocol);
        profile.thinkingMode = requireThinkingMode(protocol, profile.thinkingMode, profile.thinkingBudgetTokens, profile.maxOutputTokens);
        if (!ReasoningPolicy.THINKING_MANUAL.equals(profile.thinkingMode)) profile.thinkingBudgetTokens = 0;
        profile.continuationMode = requireContinuationMode(protocol, profile.continuationMode);
        profile.protocol = protocol;
    }

    private static String safe(String value) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? "（空）" : v;
    }
}
