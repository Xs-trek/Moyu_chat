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

    /** Thinking modes a route can carry: Messages has its own field; Chat only through a declared dialect switch. */
    static String requireThinkingMode(String protocol, RouteDialect dialect, String raw, int budget, int maxOutput) {
        String mode;
        try { mode = ReasoningPolicy.normalizeThinkingMode(raw); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("思考模式无效：" + safe(raw) + "。可选值：provider-default / enabled / adaptive / disabled / manual");
        }
        RouteDialect d = dialect == null ? RouteDialect.defaults(protocol) : dialect;
        if (!d.carriesThinking(protocol, mode)) {
            if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) {
                throw new IllegalArgumentException("Messages 的思考模式只能是 provider-default / adaptive / disabled / manual；当前：" + mode);
            }
            if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol)) {
                throw new IllegalArgumentException("这条路线没有声明思考开关（thinking-type），思考模式只能是 provider-default；当前：" + mode);
            }
            throw new IllegalArgumentException(protocol + " 没有思考开关，思考模式只能是 provider-default；推理强度用推理档位控制");
        }
        if (ReasoningPolicy.THINKING_MANUAL.equals(mode)) {
            if (budget < 1024 || budget >= maxOutput) {
                throw new IllegalArgumentException("手动思考预算必须 >=1024 且小于输出上限；当前预算=" + budget + "，输出上限=" + maxOutput);
            }
        } else if (budget != 0) {
            throw new IllegalArgumentException("手动思考预算只在思考模式为 manual 时填写；其它模式必须为 0");
        }
        return mode;
    }

    /** Service tier vocabulary per protocol family; '' = do not send. */
    static String requireServiceTier(String protocol, String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) return "";
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) {
            if ("auto".equals(value) || "standard_only".equals(value)) return value;
            throw new IllegalArgumentException("Messages 的服务层级只能是 auto / standard_only；当前：" + value);
        }
        if ("auto".equals(value) || "default".equals(value) || "flex".equals(value) || "priority".equals(value)) return value;
        throw new IllegalArgumentException("服务层级只能是 auto / default / flex / priority；当前：" + value);
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

    /** A route on its own: protocol, endpoint convention, auth, dialect and headers. Normalizes in place. */
    static void validateRoute(ChatDatabase.Route route) {
        if (route == null) throw new IllegalArgumentException("路线不存在");
        String protocol;
        try { protocol = ChatDatabase.normalizeProtocol(route.protocol); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("协议无效：" + safe(route.protocol) + "。可选值：openai-chat-completions / openai-responses / anthropic-messages");
        }
        route.protocol = protocol;
        route.baseUrl = ProviderEndpoint.normalize(route.baseUrl, protocol);
        try { route.authMode = ProviderAuth.normalize(route.authMode, protocol); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("鉴权方式无效：" + safe(route.authMode) + "。可选值：bearer / x-api-key / api-key");
        }
        route.dialect = RouteDialect.parse(protocol, route.dialect).toJson(protocol).toString();
        route.headers = RouteHeaders.parse(route.headers).toString();
    }

    /** A resolved profile against its route. Normalizes in place; messages are user-facing. */
    static void validateResolvedProfile(ChatDatabase.Route route, ChatDatabase.ModelProfile profile) {
        if (route == null || profile == null) throw new IllegalArgumentException("路线或模型不存在");
        validateRoute(route);
        String protocol = route.protocol;
        RouteDialect dialect = RouteDialect.parse(protocol, route.dialect);
        try { profile.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, protocol); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("推理参数与协议不匹配：协议=" + protocol + "，推理参数=" + safe(profile.reasoningWire));
        }
        try { profile.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("推理档位包含不支持的值：" + safe(profile.reasoningLevels)); }
        ChatDatabase.Provider view = new ChatDatabase.Provider();
        view.protocol = protocol; view.reasoningWire = profile.reasoningWire;
        try { ReasoningPolicy.validateLevelsForProvider(view, profile.reasoningLevels); }
        catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(profile.reasoningLevels.isEmpty() ? error.getMessage()
                    : "推理档位需要先设置推理参数（reasoning wire）；Messages 的档位只能是 low 到 max");
        }

        validateLimits(profile.contextTokens, profile.inputTokens, profile.maxOutputTokens);
        profile.inputModalities = requireTextModality("输入模态", profile.inputModalities);
        profile.outputModalities = requireTextModality("输出模态", profile.outputModalities);
        profile.toolCapabilities = requireNativeWebCapabilities(protocol, profile.toolCapabilities);
        profile.harnessPreset = requireHarnessPreset(profile.harnessPreset);
        profile.clientToolCalling = requireClientToolCalling(profile.clientToolCalling, protocol);
        profile.thinkingMode = requireThinkingMode(protocol, dialect, profile.thinkingMode, profile.thinkingBudgetTokens, profile.maxOutputTokens);
        if (!ReasoningPolicy.THINKING_MANUAL.equals(profile.thinkingMode)) profile.thinkingBudgetTokens = 0;
        profile.continuationMode = requireContinuationMode(protocol, profile.continuationMode);
        profile.serviceTier = requireServiceTier(protocol, profile.serviceTier);
        profile.pricing = ModelPricing.normalize(DeploymentResolver.json(profile.pricing)).toString();
        profile.protocol = protocol;
    }

    private static String safe(String value) {
        String v = value == null ? "" : value.trim();
        return v.isEmpty() ? "（空）" : v;
    }
}
