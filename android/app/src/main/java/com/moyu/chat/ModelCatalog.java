package com.moyu.chat;

/**
 * Small reviewed v0.0.4 model/deployment catalog. Exact entries are deliberately conservative:
 * base-model behavior priors may match by exact model ID, while optional wire capabilities are only
 * enabled by a reviewed deployment overlay (for example an official endpoint or a specifically
 * verified third-party route). Unknown deployments remain usable through Advanced/manual config.
 */
final class ModelCatalog {
    static final String CONFIG_AUTO = "auto";
    static final String CONFIG_MANUAL = "manual";
    static final String CONFIG_LEGACY_MANUAL = "legacy-manual";
    static final String CATALOG_VERSION = "2026-08-28-v1";

    static final class Resolution {
        String catalogKey = "unknown";
        String source = "neutral-defaults";
        String harnessPreset = HarnessProfile.BALANCED;
        String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        String authMode = ProviderAuth.BEARER;
        String reasoningWire = ReasoningPolicy.WIRE_NONE;
        String reasoningLevels = "";
        String inputModalities = "text";
        String outputModalities = "text";
        String toolCapabilities = "";
        String thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
        int thinkingBudgetTokens = 0;
        String continuationMode = ProviderContinuationPolicy.NONE;
        String clientToolCalling = ClientToolRuntime.NONE;
        /** True only when endpoint + model matched a reviewed concrete deployment overlay. */
        boolean deploymentResolved;
        int contextTokens = ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        int inputTokens = 0;
        int maxOutputTokens = ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
    }

    private ModelCatalog() { }

    static String normalizeConfigMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (CONFIG_AUTO.equals(value) || CONFIG_MANUAL.equals(value) || CONFIG_LEGACY_MANUAL.equals(value)) return value;
        // Unknown/corrupt values must fail conservative: Auto may enable optional deployment capabilities.
        return CONFIG_MANUAL;
    }

    static Resolution resolve(ChatDatabase.Provider provider, String modelId) {
        Resolution out = new Resolution();
        String model = modelId == null ? "" : modelId.trim().toLowerCase(java.util.Locale.ROOT);
        String base = provider == null || provider.baseUrl == null ? "" : provider.baseUrl.trim().toLowerCase(java.util.Locale.ROOT);

        // Behavior priors may be model-family specific without asserting anything about a third-party wire.
        if ("gpt-5.6".equals(model) || "gpt-5.6-sol".equals(model) || "gpt-5.6-terra".equals(model)) {
            out.catalogKey = model;
            out.source = "moyu-base-catalog";
            out.harnessPreset = HarnessProfile.LEAN;
        } else if ("gpt-5.6-luna".equals(model)) {
            out.catalogKey = model;
            out.source = "moyu-base-catalog";
            out.harnessPreset = HarnessProfile.BALANCED;
        } else if ("claude-opus-5".equals(model) || "claude-sonnet-5".equals(model)) {
            out.catalogKey = model;
            out.source = "moyu-base-catalog";
            out.harnessPreset = HarnessProfile.LEAN;
        } else if ("glm-5.3-flash".equals(model)) {
            out.catalogKey = model;
            out.source = "moyu-base-catalog";
            out.harnessPreset = HarnessProfile.BALANCED;
        }

        // Context/input/output limits remain explicit deployment/user inputs in v0.0.4. The catalog
        // intentionally does not infer them from a base-model name, even on a reviewed wire overlay.

        // Official OpenAI v1 deployment: current public GPT-5.6 guidance explicitly recommends
        // Responses for reasoning/tool workflows and documents the complete effort ladder.
        if (base.equals("https://api.openai.com/v1")
                && (model.equals("gpt-5.6") || model.startsWith("gpt-5.6-"))) {
            out.source = "moyu-official-openai-overlay";
            out.protocol = OpenAiCompatibleTransport.RESPONSES;
            out.authMode = ProviderAuth.BEARER;
            out.reasoningWire = ReasoningPolicy.WIRE_OPENAI_RESPONSES;
            out.reasoningLevels = "none,low,medium,high,xhigh,max";
            out.inputModalities = "text,image";
            out.outputModalities = "text";
            out.toolCapabilities = ChatTurnOptions.WEB_SEARCH;
            out.continuationMode = ProviderContinuationPolicy.NATIVE;
            out.clientToolCalling = ClientToolRuntime.OPENAI_RESPONSES;
            out.deploymentResolved = true;
        }

        // Official Anthropic deployment. Tool/effort behavior is documented by the Messages API;
        // native private replay stays conservative because it is independent from basic tool support.
        if (base.equals("https://api.anthropic.com/v1")
                && (model.equals("claude-opus-5") || model.equals("claude-sonnet-5"))) {
            out.source = "moyu-official-anthropic-overlay";
            out.protocol = AnthropicMessagesTransport.PROTOCOL;
            out.authMode = ProviderAuth.X_API_KEY;
            out.reasoningWire = ReasoningPolicy.WIRE_ANTHROPIC_EFFORT;
            out.reasoningLevels = "low,medium,high,xhigh,max";
            out.inputModalities = "text,image";
            out.outputModalities = "text";
            out.thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
            out.clientToolCalling = ClientToolRuntime.ANTHROPIC_TOOLS;
            out.deploymentResolved = true;
        }

        // Verified OpenCode Go GLM-5.3-Flash route from the v0.0.3 compatibility investigation.
        // Only the fields that were actually established are enabled here.
        if (base.equals("https://opencode.ai/zen/go/v1") && model.equals("glm-5.3-flash")) {
            out.source = "moyu-opencode-go-overlay";
            out.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
            out.authMode = ProviderAuth.BEARER;
            out.reasoningWire = ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT;
            out.reasoningLevels = "low,high,max";
            out.inputModalities = "text";
            out.outputModalities = "text";
            out.toolCapabilities = "";
            out.continuationMode = ProviderContinuationPolicy.NONE;
            // Tool-call compatibility was not established by the prior endpoint tests; stay off.
            out.clientToolCalling = ClientToolRuntime.NONE;
            out.deploymentResolved = true;
        }
        return out;
    }

    static void applyAuto(ChatDatabase.Provider provider, ChatDatabase.ModelProfile profile,
                          int userContext, int userInput, int userOutput) {
        if (profile == null) throw new IllegalArgumentException("model profile required");
        Resolution r = resolve(provider, profile.modelId);
        if (!r.deploymentResolved) {
            throw new IllegalArgumentException("Auto catalog has no reviewed deployment overlay for this endpoint/model; use Advanced");
        }
        profile.catalogKey = r.catalogKey;
        profile.catalogVersion = CATALOG_VERSION;
        profile.catalogSource = r.source;
        profile.harnessPreset = r.harnessPreset;
        profile.protocol = r.protocol;
        profile.authMode = r.authMode;
        profile.reasoningWire = r.reasoningWire;
        profile.reasoningLevels = r.reasoningLevels;
        profile.inputModalities = r.inputModalities;
        profile.outputModalities = r.outputModalities;
        profile.toolCapabilities = r.toolCapabilities;
        profile.thinkingMode = r.thinkingMode;
        profile.thinkingBudgetTokens = r.thinkingBudgetTokens;
        profile.continuationMode = r.continuationMode;
        profile.clientToolCalling = r.clientToolCalling;
        profile.contextTokens = userContext > 0 ? userContext : r.contextTokens;
        profile.inputTokens = userInput > 0 ? userInput : r.inputTokens;
        profile.maxOutputTokens = userOutput > 0 ? userOutput : r.maxOutputTokens;
    }

    static String summary(ChatDatabase.Provider provider, String modelId) {
        Resolution r = resolve(provider, modelId);
        String reasoning = r.reasoningLevels.isEmpty() ? "Provider 默认/未声明" : r.reasoningLevels.replace(',', '/');
        String tools = ClientToolRuntime.NONE.equals(r.clientToolCalling) ? "未声明" : "支持";
        String web = r.toolCapabilities.isEmpty() ? "Moyu fallback" : r.toolCapabilities;
        return "来源: " + r.source + "\n"
                + "协议: " + r.protocol + "\n"
                + "推理: " + reasoning + "\n"
                + "输入/输出: " + r.inputModalities + " / " + r.outputModalities + "\n"
                + "Client Tools: " + tools + "\n"
                + "联网: " + web + "\n"
                + "Continuation: " + r.continuationMode + "\n"
                + "Harness: " + r.harnessPreset;
    }
}
