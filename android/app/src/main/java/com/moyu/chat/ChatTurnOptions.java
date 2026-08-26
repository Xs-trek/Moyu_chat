package com.moyu.chat;

import java.util.Arrays;
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

    private static final Set<String> ANTHROPIC_SEARCH_WIRES = new LinkedHashSet<>(Arrays.asList(
            "web_search_20250305", "web_search_20260209", "web_search_20260318"));
    private static final Set<String> ANTHROPIC_FETCH_WIRES = new LinkedHashSet<>(Arrays.asList(
            "web_fetch_20250910", "web_fetch_20260209", "web_fetch_20260309", "web_fetch_20260318"));

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
        for (String item : toolCapabilities.split(",")) {
            if (capability.equals(item)) return true;
            if (WEB_SEARCH.equals(capability) && ANTHROPIC_SEARCH_WIRES.contains(item)) return true;
            if (WEB_FETCH.equals(capability) && ANTHROPIC_FETCH_WIRES.contains(item)) return true;
        }
        return false;
    }

    /** Exact Messages tool wire when explicitly declared; otherwise use the transport's current default. */
    String wireType(String capability, String fallback) {
        for (String item : toolCapabilities.split(",")) {
            if (WEB_SEARCH.equals(capability) && ANTHROPIC_SEARCH_WIRES.contains(item)) return item;
            if (WEB_FETCH.equals(capability) && ANTHROPIC_FETCH_WIRES.contains(item)) return item;
        }
        return supports(capability) ? fallback : "";
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
            if (!(WEB_SEARCH.equals(value) || WEB_FETCH.equals(value)
                    || ANTHROPIC_SEARCH_WIRES.contains(value) || ANTHROPIC_FETCH_WIRES.contains(value))) {
                throw new IllegalArgumentException("native tool capabilities support web_search/web_fetch or a supported Messages web tool wire version");
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
