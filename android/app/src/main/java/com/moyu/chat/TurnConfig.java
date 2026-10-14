package com.moyu.chat;

/** Immutable user-selected/deployment turn configuration frozen on the serialized intent worker. */
final class TurnConfig {
    final ChatDatabase.Provider provider;
    final String apiKey, deploymentFingerprint;
    final String providerId, modelProfileId, model, modelName, reasoningEffort;
    final String inputModalities, outputModalities, toolCapabilities, thinkingMode, continuationMode, contextMode, webMode;
    final String harnessPreset, clientToolCalling, subagentMode;
    final int thinkingBudgetTokens, contextTokens, inputTokens, maxOutputTokens;
    final boolean reasoningAvailable;
    /** The profile's prices when the turn was accepted (ModelPricing shape), "{}" when unknown. */
    final String pricing;

    TurnConfig(ChatDatabase.Provider provider, String apiKey, String deploymentFingerprint,
               ChatDatabase.Session session, ChatDatabase.ModelProfile profile) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.deploymentFingerprint = deploymentFingerprint;
        this.providerId = session.providerId;
        this.modelProfileId = session.modelProfileId;
        this.model = session.model;
        this.modelName = profile == null ? session.model : profile.displayName;
        this.reasoningEffort = ReasoningPolicy.normalizeEffort(session.reasoningEffort);
        this.inputModalities = ChatDatabase.normalizeModalities(session.inputModalities);
        this.outputModalities = ChatDatabase.normalizeModalities(session.outputModalities);
        this.toolCapabilities = ChatTurnOptions.normalizeCapabilities(session.toolCapabilities);
        this.thinkingMode = ReasoningPolicy.normalizeThinkingMode(session.thinkingMode);
        this.thinkingBudgetTokens = Math.max(0, session.thinkingBudgetTokens);
        this.continuationMode = ProviderContinuationPolicy.normalize(session.continuationMode);
        this.contextMode = ContextMode.normalize(session.contextMode);
        this.webMode = ChatTurnOptions.normalizeWebMode(session.webMode);
        this.harnessPreset = HarnessProfile.normalize(session.harnessPreset);
        this.clientToolCalling = ClientToolRuntime.normalizeCalling(session.clientToolCalling);
        this.subagentMode = HarnessPolicyResolver.normalizeSubagentMode(session.subagentMode);
        this.contextTokens = session.contextTokens;
        this.inputTokens = session.inputTokens;
        this.maxOutputTokens = session.maxOutputTokens;
        this.reasoningAvailable = !ReasoningPolicy.normalizeLevels(session.reasoningLevels).isEmpty();
        this.pricing = profile == null || profile.pricing == null || profile.pricing.trim().isEmpty() ? "{}" : profile.pricing;
    }
}
