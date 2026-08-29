package com.moyu.chat;

/** User-facing validation for Provider/Model configuration before it reaches transport or SQLite. */
final class ConfigurationValidator {
    private ConfigurationValidator() { }

    static String requireHarnessPreset(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (HarnessProfile.LEAN.equals(value) || HarnessProfile.BALANCED.equals(value) || HarnessProfile.REINFORCED.equals(value)) return value;
        throw new IllegalArgumentException("Harness preset 无效：当前值“" + display(raw) + "”。可选值：lean / balanced / reinforced。");
    }

    static String requireClientToolCalling(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (ClientToolRuntime.NONE.equals(value) || ClientToolRuntime.OPENAI_CHAT.equals(value)
                || ClientToolRuntime.OPENAI_RESPONSES.equals(value) || ClientToolRuntime.ANTHROPIC_TOOLS.equals(value)) return value;
        throw new IllegalArgumentException("Client tool calling 无效：当前值“" + display(raw)
                + "”。可选值：none / openai-chat-tools / openai-responses-tools / anthropic-tools。");
    }

    static void validateProfile(ChatDatabase.Provider provider, ChatDatabase.ModelProfile profile) {
        if (provider == null) throw new IllegalArgumentException("Provider 不存在，请先保存 Provider。");
        if (profile == null) throw new IllegalArgumentException("Model Profile 为空。");
        String modelId = profile.modelId == null ? "" : profile.modelId.trim();
        if (modelId.isEmpty()) throw new IllegalArgumentException("Model ID 不能为空；请填写实际发送给 endpoint 的 model 字符串。");

        String protocol = ChatDatabase.normalizeProtocol(profile.protocol);
        ProviderEndpoint.normalize(provider.baseUrl, protocol);
        profile.authMode = ProviderAuth.normalize(profile.authMode, protocol);
        profile.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, protocol);
        profile.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
        ReasoningPolicy.validateLevelsForProvider(ChatDatabase.deploymentProvider(provider, profile), profile.reasoningLevels);

        int context = profile.contextTokens;
        int input = profile.inputTokens;
        int output = profile.maxOutputTokens;
        if (context < 2_048 || context > 4_000_000) {
            throw new IllegalArgumentException("Context tokens 必须是 2,048–4,000,000。当前值：" + context + "。");
        }
        if (input < 0 || input > context) {
            throw new IllegalArgumentException("Max input tokens 必须为 0（自动）或不大于 Context tokens。当前 input=" + input + "，context=" + context + "。");
        }
        if (output < 256 || output >= context) {
            throw new IllegalArgumentException("Max output tokens 必须至少为 256，且小于 Context tokens。当前 output=" + output + "，context=" + context + "。");
        }

        profile.inputModalities = ChatDatabase.normalizeModalities(profile.inputModalities);
        profile.outputModalities = ChatDatabase.normalizeModalities(profile.outputModalities);
        if (!ChatDatabase.supportsModality(profile.inputModalities, "text")) {
            throw new IllegalArgumentException("输入模态必须包含 text；当前 Moyu Chat 不支持仅 image 的输入配置。可用：text 或 text,image。");
        }
        if (!ChatDatabase.supportsModality(profile.outputModalities, "text")) {
            throw new IllegalArgumentException("输出模态必须包含 text；当前 Moyu Chat 不支持仅 image 的输出配置。可用：text 或 text,image。");
        }

        profile.toolCapabilities = ChatTurnOptions.normalizeCapabilities(profile.toolCapabilities);
        String effectiveCapabilities = ChatTurnOptions.effectiveCapabilities(protocol, profile.toolCapabilities);
        if (!profile.toolCapabilities.equals(effectiveCapabilities)) {
            if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol)) {
                throw new IllegalArgumentException("Provider-native Web 配置与协议不兼容：openai-chat-completions 没有 Moyu 已实现的 hosted Web wire。请留空并使用 Moyu Web fallback，或改用明确支持 hosted Web 的协议。");
            }
            if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) {
                throw new IllegalArgumentException("Provider-native Web 配置与 Responses 不兼容：当前仅支持 web_search。请删除 web_fetch 或 Messages 专用的版本化 wire。");
            }
            throw new IllegalArgumentException("Provider-native Web 配置与 anthropic-messages 不兼容：Messages 必须填写 endpoint 明确支持的版本化 wire，例如 web_search_20260209 / web_fetch_20250910；不能填写通用 web_search/web_fetch。");
        }

        profile.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode);
        profile.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
        if (!AnthropicMessagesTransport.PROTOCOL.equals(protocol)) {
            if (!ReasoningPolicy.THINKING_PROVIDER_DEFAULT.equals(profile.thinkingMode) || profile.thinkingBudgetTokens != 0) {
                throw new IllegalArgumentException("Messages thinking 仅用于 anthropic-messages。当前协议为 " + protocol
                        + "；请将 thinking mode 设为 provider-default，Manual thinking budget 设为 0。");
            }
        } else if (ReasoningPolicy.THINKING_MANUAL.equals(profile.thinkingMode)
                && (profile.thinkingBudgetTokens < 1024 || profile.thinkingBudgetTokens >= output)) {
            throw new IllegalArgumentException("Manual thinking budget 必须至少 1,024 且小于 Max output tokens。当前 budget="
                    + profile.thinkingBudgetTokens + "，output=" + output + "。");
        } else if (!ReasoningPolicy.THINKING_MANUAL.equals(profile.thinkingMode) && profile.thinkingBudgetTokens != 0) {
            throw new IllegalArgumentException("Manual thinking budget 仅在 thinking mode=manual 时有效；其它模式请填 0。");
        }

        profile.continuationMode = ProviderContinuationPolicy.normalize(profile.continuationMode);
        if (ProviderContinuationPolicy.enabled(profile.continuationMode)
                && !(OpenAiCompatibleTransport.RESPONSES.equals(protocol) || AnthropicMessagesTransport.PROTOCOL.equals(protocol))) {
            throw new IllegalArgumentException("Provider-private continuation=native 与当前协议不兼容："
                    + protocol + " 没有 Moyu 已实现的私有状态 replay。请使用 none。");
        }

        profile.harnessPreset = requireHarnessPreset(profile.harnessPreset);
        profile.clientToolCalling = requireClientToolCalling(profile.clientToolCalling);
        if (!ClientToolRuntime.NONE.equals(profile.clientToolCalling)
                && !ClientToolRuntime.compatible(protocol, profile.clientToolCalling)) {
            throw new IllegalArgumentException("Client tool calling 与协议不兼容：protocol=" + protocol
                    + "，clientToolCalling=" + profile.clientToolCalling + "。请选择与协议对应的 tool wire，或使用 none。");
        }
    }

    private static String display(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.isEmpty() ? "（空）" : value;
    }
}
