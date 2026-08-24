package com.moyu.chat;

/** Session-scoped context behavior chosen at creation time. */
final class ContextMode {
    static final String AUTO = "auto";
    static final String MINIMAL = "minimal";
    static final String SMART = "smart";

    static final class Policy {
        final boolean routerEnabled;
        final boolean checkpointEnabled;
        final String effectiveLabel;
        Policy(boolean routerEnabled, boolean checkpointEnabled, String effectiveLabel) {
            this.routerEnabled = routerEnabled;
            this.checkpointEnabled = checkpointEnabled;
            this.effectiveLabel = effectiveLabel;
        }
    }

    private ContextMode() { }

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (MINIMAL.equals(value) || SMART.equals(value) || AUTO.equals(value)) return value;
        return AUTO;
    }

    /**
     * Auto is deterministic and local: Remote-origin sessions use the full Smart layer; ordinary
     * local Chat avoids the Router but may build a checkpoint only when real context pressure occurs.
     */
    static Policy resolve(ChatDatabase.Session session, ChatDatabase.Snapshot snapshot) {
        String mode = normalize(session == null ? null : session.contextMode);
        if (MINIMAL.equals(mode)) return new Policy(false, false, MINIMAL);
        if (SMART.equals(mode)) return new Policy(true, true, SMART);
        boolean remote = snapshot != null && "remote".equals(snapshot.sourceType);
        return remote ? new Policy(true, true, "auto-smart") : new Policy(false, true, "auto-minimal");
    }
}
