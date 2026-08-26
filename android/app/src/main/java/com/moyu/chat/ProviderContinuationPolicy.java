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
}
