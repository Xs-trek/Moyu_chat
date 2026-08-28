package com.moyu.chat;

/** Session-scoped Harness strength chosen at creation time. */
final class ContextMode {
    static final String MINIMAL = "minimal";
    static final String STANDARD = "standard";
    static final String SMART = "smart";
    /** v0.0.2 compatibility only. New UI never emits auto. */
    static final String LEGACY_AUTO = "auto";
    /** Source-compatibility alias for older tests/callers; persisted value is standard. */
    @Deprecated static final String AUTO = STANDARD;

    static final class Policy {
        final boolean routerEnabled;
        final boolean checkpointEnabled;
        final boolean retrievalEnabled;
        final boolean evidencePolicyEnabled;
        final boolean memoryCallerEnabled;
        final boolean providerContinuationEnabled;
        final boolean verifierEnabled;
        final String effectiveLabel;
        Policy(boolean routerEnabled, boolean checkpointEnabled, boolean retrievalEnabled,
               boolean evidencePolicyEnabled, boolean memoryCallerEnabled,
               boolean providerContinuationEnabled, boolean verifierEnabled, String effectiveLabel) {
            this.routerEnabled = routerEnabled;
            this.checkpointEnabled = checkpointEnabled;
            this.retrievalEnabled = retrievalEnabled;
            this.evidencePolicyEnabled = evidencePolicyEnabled;
            this.memoryCallerEnabled = memoryCallerEnabled;
            this.providerContinuationEnabled = providerContinuationEnabled;
            this.verifierEnabled = verifierEnabled;
            this.effectiveLabel = effectiveLabel;
        }
    }

    private ContextMode() { }

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (MINIMAL.equals(value) || STANDARD.equals(value) || SMART.equals(value)) return value;
        if (LEGACY_AUTO.equals(value)) return STANDARD;
        return STANDARD;
    }

    /**
     * Minimal deliberately has no caller capability for Memory/RAG or provider-private continuation.
     * Standard keeps mature-chat context/retrieval/compaction plus native same-provider continuation.
     * Smart adds bounded routing, evidence policy and the second-pass verifier.
     */
    static Policy resolve(ChatDatabase.Session session, ChatDatabase.Snapshot snapshot) {
        String mode = normalize(session == null ? null : session.contextMode);
        if (MINIMAL.equals(mode)) return new Policy(false, false, false, false, false, false, false, MINIMAL);
        if (SMART.equals(mode)) {
            HarnessPolicyResolver.Policy adaptive = HarnessPolicyResolver.resolve(session);
            boolean evidencePrompt = !HarnessProfile.LEAN.equals(adaptive.harnessPreset);
            return new Policy(adaptive.routerEnabled, true, true, evidencePrompt, true, true, adaptive.verifierAvailable, SMART);
        }
        return new Policy(false, true, true, false, true, true, false, STANDARD);
    }
}
