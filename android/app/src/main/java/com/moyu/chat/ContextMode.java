package com.moyu.chat;

/** Session-scoped Harness strength chosen at creation time. */
final class ContextMode {
    static final String MINIMAL = "minimal";
    static final String STANDARD = "standard";
    static final String SMART = "smart";
    /** v0.0.2 compatibility only. New UI never emits auto. */
    static final String LEGACY_AUTO = "auto";

    static final class Policy {
        final boolean routerEnabled;
        final boolean checkpointEnabled;
        final boolean retrievalEnabled;
        final boolean evidencePolicyEnabled;
        final String effectiveLabel;
        Policy(boolean routerEnabled, boolean checkpointEnabled, boolean retrievalEnabled,
               boolean evidencePolicyEnabled, String effectiveLabel) {
            this.routerEnabled = routerEnabled;
            this.checkpointEnabled = checkpointEnabled;
            this.retrievalEnabled = retrievalEnabled;
            this.evidencePolicyEnabled = evidencePolicyEnabled;
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
     * Minimal keeps only deterministic context budgeting. Standard mirrors the publicly observable
     * mature-chat pattern: raw context first, retrieval/compaction only under real context pressure.
     * Smart adds model-assisted task classification and reviewed evidence/response policies.
     */
    static Policy resolve(ChatDatabase.Session session, ChatDatabase.Snapshot snapshot) {
        String mode = normalize(session == null ? null : session.contextMode);
        if (MINIMAL.equals(mode)) return new Policy(false, false, false, false, MINIMAL);
        if (SMART.equals(mode)) return new Policy(true, true, true, true, SMART);
        return new Policy(false, true, true, false, STANDARD);
    }
}
