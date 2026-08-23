package com.moyu.chat;

import okhttp3.Request;

/** Small provider-level auth abstraction shared by protocol transports. */
final class ProviderAuth {
    static final String BEARER = "bearer";
    static final String X_API_KEY = "x-api-key";
    static final String API_KEY = "api-key";

    static String defaultForProtocol(String protocol) {
        return AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? X_API_KEY : BEARER;
    }

    static String normalize(String raw, String protocol) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = defaultForProtocol(protocol);
        if (!(BEARER.equals(value) || X_API_KEY.equals(value) || API_KEY.equals(value))) {
            throw new IllegalArgumentException("unsupported auth mode");
        }
        return value;
    }

    static void apply(Request.Builder request, String authMode, String protocol, String apiKey) {
        String mode = normalize(authMode, protocol);
        if (BEARER.equals(mode)) request.header("Authorization", "Bearer " + apiKey);
        else if (X_API_KEY.equals(mode)) request.header("x-api-key", apiKey);
        else request.header("api-key", apiKey);
    }

    private ProviderAuth() { }
}
