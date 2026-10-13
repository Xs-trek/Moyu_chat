package com.moyu.chat;

/** Explicit deployment capability for replaying provider-private state across turns. */
final class ProviderContinuationPolicy {
    static final String NONE = "none";
    static final String NATIVE = "native";

    private ProviderContinuationPolicy() { }

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || NONE.equals(value)) return NONE;
        if (NATIVE.equals(value)) return NATIVE;
        throw new IllegalArgumentException("unsupported provider continuation mode");
    }

    static boolean enabled(String raw) { return NATIVE.equals(normalize(raw)); }

    /**
     * Whether assistant turns keep and replay provider state. Native continuation is an optional,
     * mode-gated optimization; Chat Completions reasoning replay is different: a route that requires
     * reasoning_content on later requests (RouteDialect) gets it in every mode, or the request fails.
     */
    static boolean replayAllowed(ContextMode.Policy mode, ChatDatabase.Session session, ChatDatabase.Provider view) {
        if (session == null) return false;
        if (mode != null && mode.providerContinuationEnabled && enabled(session.continuationMode)) return true;
        return view != null && OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(view.protocol) && view.dialect != null
                && view.dialect.replaysReasoning(!ClientToolRuntime.NONE.equals(ClientToolRuntime.normalizeCalling(session.clientToolCalling)));
    }
}
