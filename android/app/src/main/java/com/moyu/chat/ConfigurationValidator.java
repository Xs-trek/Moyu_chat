package com.moyu.chat;

/** User-facing validation for Provider/Model configuration before it reaches transport or SQLite. */
final class ConfigurationValidator {
    private ConfigurationValidator() { }

    static String requireHarnessPreset(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (HarnessProfile.LEAN.equals(value) || HarnessProfile.BALANCED.equals(value) || HarnessProfile.REINFORCED.equals(value)) return value;
        throw new IllegalArgumentException("Harness preset 无效：当前值“" + display(raw) + "”。可选值：lean / balanced / reinforced。");
    }

    static String requireClientToolCalling(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (ClientToolRuntime.NONE.equals(value) || ClientToolRuntime.OPENAI_CHAT.equals(value)
                || ClientToolRuntime.OPENAI_RESPONSES.equals(value) || ClientToolRuntime.ANTHROPIC_TOOLS.equals(value)) return value;
        throw new IllegalArgumentException("Client tool calling 无效：当前值“" + display(raw)
                + "”。可选值：none / openai-chat-tools / openai-responses-tools / anthropic-tools。");
    }

    private static String display(String raw) {
        String value = raw == null ? "" : raw.trim();
        return value.isEmpty() ? "（空）" : value;
    }
}
