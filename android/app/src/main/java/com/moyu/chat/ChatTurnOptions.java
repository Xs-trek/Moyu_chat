package com.moyu.chat;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Provider-neutral per-turn native hosted-web policy. Hidden preprocessing always uses NONE. */
final class ChatTurnOptions {
    static final String WEB_SEARCH = "web_search";
    static final String WEB_FETCH = "web_fetch";
    static final String WEB_OFF = "off";
    static final String WEB_AUTO = "auto";
    static final String WEB_ON = "on";
    static final ChatTurnOptions NONE = new ChatTurnOptions("", WEB_OFF);

    /** Capabilities declared by the concrete model deployment and supported by this transport. */
    final String toolCapabilities;
    final String webMode;

    ChatTurnOptions(String toolCapabilities, String webMode) {
        this.toolCapabilities = normalizeCapabilities(toolCapabilities);
        this.webMode = normalizeWebMode(webMode);
    }

    boolean webEnabled() { return !WEB_OFF.equals(webMode); }
    boolean forceFallback() { return WEB_ON.equals(webMode); }
    boolean supports(String capability) {
        if (capability == null || capability.isEmpty()) return false;
        for (String item : toolCapabilities.split(",")) if (capability.equals(item)) return true;
        return false;
    }

    static String normalizeWebMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (WEB_OFF.equals(value) || WEB_AUTO.equals(value) || WEB_ON.equals(value)) return value;
        return WEB_AUTO;
    }

    /** These values mean provider-native hosted capabilities, not Moyu fallback availability. */
    static String normalizeCapabilities(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (!(WEB_SEARCH.equals(value) || WEB_FETCH.equals(value))) {
                throw new IllegalArgumentException("native tool capabilities only support web_search,web_fetch in this build");
            }
            out.add(value);
        }
        return String.join(",", out);
    }

    static String effectiveCapabilities(String protocol, String declared) {
        String normalized = normalizeCapabilities(declared);
        Set<String> out = new LinkedHashSet<>();
        for (String item : normalized.split(",")) {
            if (item.isEmpty()) continue;
            if (OpenAiCompatibleTransport.RESPONSES.equals(protocol) && WEB_SEARCH.equals(item)) out.add(item);
            else if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) out.add(item);
        }
        return String.join(",", out);
    }

    static ChatTurnOptions forSession(ChatDatabase.Session session, String protocol) {
        if (session == null) return NONE;
        return new ChatTurnOptions(effectiveCapabilities(protocol, session.toolCapabilities), session.webMode);
    }
}
