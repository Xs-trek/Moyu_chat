package com.moyu.chat;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Provider-neutral, per-turn hosted-tool policy. Hidden preprocessing always uses NONE. */
final class ChatTurnOptions {
    static final String WEB_SEARCH = "web_search";
    static final String WEB_FETCH = "web_fetch";
    static final String WEB_OFF = "off";
    static final String WEB_AUTO = "auto";
    static final ChatTurnOptions NONE = new ChatTurnOptions("", WEB_OFF);

    final String toolCapabilities;
    final String webMode;

    ChatTurnOptions(String toolCapabilities, String webMode) {
        this.toolCapabilities = normalizeCapabilities(toolCapabilities);
        this.webMode = normalizeWebMode(webMode);
    }

    boolean webEnabled() { return WEB_AUTO.equals(webMode); }
    boolean supports(String capability) {
        if (capability == null || capability.isEmpty()) return false;
        for (String item : toolCapabilities.split(",")) if (capability.equals(item)) return true;
        return false;
    }

    static String normalizeWebMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (WEB_OFF.equals(value) || WEB_AUTO.equals(value)) return value;
        return WEB_AUTO;
    }

    static String normalizeCapabilities(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (!(WEB_SEARCH.equals(value) || WEB_FETCH.equals(value))) {
                throw new IllegalArgumentException("tool capabilities only support web_search,web_fetch in this build");
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

    static boolean protocolCanUseWeb(String protocol) {
        return OpenAiCompatibleTransport.RESPONSES.equals(protocol) || AnthropicMessagesTransport.PROTOCOL.equals(protocol);
    }

    static ChatTurnOptions forSession(ChatDatabase.Session session, String protocol) {
        if (session == null) return NONE;
        return new ChatTurnOptions(effectiveCapabilities(protocol, session.toolCapabilities), session.webMode);
    }
}
