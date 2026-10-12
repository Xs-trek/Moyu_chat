package com.moyu.chat;

import org.json.JSONArray;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Provider-neutral per-turn hosted-web + client-tool policy. Hidden preprocessing uses NONE. */
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
    final String clientToolCalling;
    final String clientToolNames;
    final JSONArray toolHistory;
    /** Exact provider output/content blocks from earlier client-tool rounds in this same user turn. */
    final JSONArray providerRoundHistory;
    /** Product-level orchestrator decision for provider-hosted Web on this concrete turn. */
    final boolean hostedWebAllowed;

    ChatTurnOptions(String toolCapabilities, String webMode) {
        this(toolCapabilities, webMode, ClientToolRuntime.NONE, "", new JSONArray(), new JSONArray(),
                !WEB_OFF.equals(normalizeWebMode(webMode)));
    }

    ChatTurnOptions(String toolCapabilities, String webMode, String clientToolCalling,
                    String clientToolNames, JSONArray toolHistory) {
        this(toolCapabilities, webMode, clientToolCalling, clientToolNames, toolHistory, new JSONArray(),
                !WEB_OFF.equals(normalizeWebMode(webMode)));
    }

    ChatTurnOptions(String toolCapabilities, String webMode, String clientToolCalling,
                    String clientToolNames, JSONArray toolHistory, JSONArray providerRoundHistory) {
        this(toolCapabilities, webMode, clientToolCalling, clientToolNames, toolHistory, providerRoundHistory,
                !WEB_OFF.equals(normalizeWebMode(webMode)));
    }

    ChatTurnOptions(String toolCapabilities, String webMode, String clientToolCalling,
                    String clientToolNames, JSONArray toolHistory, JSONArray providerRoundHistory,
                    boolean hostedWebAllowed) {
        this.toolCapabilities = normalizeCapabilities(toolCapabilities);
        this.webMode = normalizeWebMode(webMode);
        this.clientToolCalling = ClientToolRuntime.normalizeCalling(clientToolCalling);
        this.clientToolNames = normalizeClientToolNames(clientToolNames);
        this.hostedWebAllowed = hostedWebAllowed && !WEB_OFF.equals(this.webMode);
        try {
            this.toolHistory = toolHistory == null ? new JSONArray() : new JSONArray(toolHistory.toString());
            this.providerRoundHistory = providerRoundHistory == null ? new JSONArray() : new JSONArray(providerRoundHistory.toString());
        } catch (Exception ignored) { throw new IllegalArgumentException("invalid client tool/provider round history"); }
    }

    ChatTurnOptions withRoundHistory(JSONArray history, JSONArray roundHistory) {
        return new ChatTurnOptions(toolCapabilities, webMode, clientToolCalling, clientToolNames, history, roundHistory, hostedWebAllowed);
    }

    ChatTurnOptions withoutClientTools() {
        return new ChatTurnOptions(toolCapabilities, webMode, ClientToolRuntime.NONE, "", toolHistory, providerRoundHistory, hostedWebAllowed);
    }

    boolean webEnabled() { return hostedWebAllowed; }
    boolean clientToolsEnabled(String protocol) {
        return ClientToolRuntime.compatible(protocol, clientToolCalling) && !clientToolNames.isEmpty();
    }
    JSONArray clientToolDefinitions() { return ClientToolRuntime.canonicalDefinitions(clientToolNames); }

    boolean supports(String capability) {
        if (capability == null || capability.isEmpty()) return false;
        for (String item : toolCapabilities.split(",")) {
            if (capability.equals(item)) return true;
            if (WEB_SEARCH.equals(capability) && ANTHROPIC_SEARCH_WIRES.contains(item)) return true;
            if (WEB_FETCH.equals(capability) && ANTHROPIC_FETCH_WIRES.contains(item)) return true;
        }
        return false;
    }

    /** Exact Messages tool wire only when the concrete deployment explicitly declares one. */
    String wireType(String capability) {
        for (String item : toolCapabilities.split(",")) {
            if (WEB_SEARCH.equals(capability) && ANTHROPIC_SEARCH_WIRES.contains(item)) return item;
            if (WEB_FETCH.equals(capability) && ANTHROPIC_FETCH_WIRES.contains(item)) return item;
        }
        return "";
    }

    static String normalizeWebMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (WEB_OFF.equals(value) || WEB_AUTO.equals(value) || WEB_ON.equals(value)) return value;
        return WEB_AUTO;
    }

    static String normalizeClientToolNames(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (!(ClientToolRuntime.WEB_SEARCH.equals(value) || ClientToolRuntime.WEB_FETCH.equals(value)
                    || ClientToolRuntime.DELEGATE_SUBTASK.equals(value))) {
                throw new IllegalArgumentException("unsupported Moyu client tool: " + value);
            }
            out.add(value);
        }
        return String.join(",", out);
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
            else if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)
                    && (ANTHROPIC_SEARCH_WIRES.contains(item) || ANTHROPIC_FETCH_WIRES.contains(item))) out.add(item);
        }
        return String.join(",", out);
    }

    static ChatTurnOptions forSession(ChatDatabase.Session session, String protocol) {
        if (session == null) return NONE;
        return new ChatTurnOptions(effectiveCapabilities(protocol, session.toolCapabilities), session.webMode,
                session.clientToolCalling, "", new JSONArray());
    }
}
