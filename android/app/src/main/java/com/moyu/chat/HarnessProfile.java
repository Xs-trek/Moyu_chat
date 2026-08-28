package com.moyu.chat;

/** Model-behavior prior: how much application harness assistance this deployment should receive. */
final class HarnessProfile {
    static final String LEAN = "lean";
    static final String BALANCED = "balanced";
    static final String REINFORCED = "reinforced";

    private HarnessProfile() { }

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (LEAN.equals(value) || BALANCED.equals(value) || REINFORCED.equals(value)) return value;
        return BALANCED;
    }
}
