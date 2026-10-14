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
    /** The user's global custom instruction (settings) when the turn was accepted; "" when none. */
    final String customInstruction;
    static final int MAX_CUSTOM_INSTRUCTION_CHARS = 4000;

    TurnConfig(ChatDatabase.Provider provider, String apiKey, String deploymentFingerprint,
               ChatDatabase.Session session, ChatDatabase.ModelProfile profile) {
        this(provider, apiKey, deploymentFingerprint, session, profile, "");
    }

    TurnConfig(ChatDatabase.Provider provider, String apiKey, String deploymentFingerprint,
               ChatDatabase.Session session, ChatDatabase.ModelProfile profile, String customInstruction) {
        String instruction = customInstruction == null ? "" : customInstruction.trim();
        this.customInstruction = instruction.length() > MAX_CUSTOM_INSTRUCTION_CHARS ? instruction.substring(0, MAX_CUSTOM_INSTRUCTION_CHARS) : instruction;
        this.provider = provider;
        this.apiKey = apiKey;
        this.deploymentFingerprint = deploymentFingerprint;
        this.providerId = session.providerId;
        this.modelProfileId = session.modelProfileId;
        this.model = session.model;
        // Name and prices describe the profile only while it still matches what this session froze; a profile since
        // switched to another model or route must not relabel or re-price the model this turn actually calls.
        boolean sameDeployment = describes(profile, session);
        this.modelName = sameDeployment ? profile.displayName : session.model;
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
        this.pricing = !sameDeployment || profile.pricing == null || profile.pricing.trim().isEmpty() ? "{}" : profile.pricing;
    }

    /** The profile still names the model id and route the session froze. */
    static boolean describes(ChatDatabase.ModelProfile profile, ChatDatabase.Session session) {
        return profile != null && session != null && profile.modelId != null && profile.modelId.equals(session.model)
                && profile.routeId != null && profile.routeId.equals(session.routeId);
    }
}
