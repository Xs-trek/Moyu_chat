package com.moyu.chat;

/**
 * Test-only one-step setup. Production code saves a shared Provider and its Model Profiles
 * separately; most tests only need "one provider with one model", built from the provider's
 * request-local deployment fields.
 */
final class TestDeployments {
    private TestDeployments() { }

    /** Saves the provider row and, when {@code p.model} is set and the provider has no profile yet, one profile {@code <id>:default}. */
    static ChatDatabase.ModelProfile save(ChatDatabase db, ChatDatabase.Provider p) {
        db.saveProvider(p);
        if (p.model == null || p.model.trim().isEmpty() || !db.listModelProfiles(p.id).isEmpty()) return db.firstModelProfile(p.id);
        ChatDatabase.ModelProfile profile = profileFor(p, p.id + ":default", p.model.trim());
        db.saveModelProfile(profile);
        return profile;
    }

    static ChatDatabase.ModelProfile profileFor(ChatDatabase.Provider p, String id, String modelId) {
        ChatDatabase.ModelProfile m = new ChatDatabase.ModelProfile();
        m.id = id; m.providerId = p.id; m.modelId = modelId; m.displayName = modelId;
        m.contextTokens = p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        m.inputTokens = Math.max(0, p.inputTokens);
        m.maxOutputTokens = p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
        m.reasoningLevels = "";
        m.protocol = ChatDatabase.normalizeProtocol(p.protocol);
        m.authMode = ProviderAuth.normalize(p.authMode, m.protocol);
        m.reasoningWire = ReasoningPolicy.normalizeWire(p.reasoningWire, m.protocol);
        return m;
    }

    static ChatDatabase.Session session(ChatDatabase db, String providerId, String modelId, String title, String originSnapshotId) {
        return session(db, providerId, modelId, title, originSnapshotId, null);
    }

    /** Session on {@code modelId}; an unknown model gets its own profile copied from the provider's first one. */
    static ChatDatabase.Session session(ChatDatabase db, String providerId, String modelId, String title, String originSnapshotId, String parentSessionId) {
        ChatDatabase.ModelProfile profile = db.findModelProfile(providerId, modelId);
        if (profile == null) {
            ChatDatabase.ModelProfile template = db.firstModelProfile(providerId);
            if (template == null) throw new IllegalStateException("provider has no model profile: " + providerId);
            template.id = providerId + ":" + modelId; template.modelId = modelId; template.displayName = modelId;
            db.saveModelProfile(template);
            profile = template;
        }
        return db.createSession(profile, title, originSnapshotId, parentSessionId, ReasoningPolicy.DEFAULT);
    }
}
