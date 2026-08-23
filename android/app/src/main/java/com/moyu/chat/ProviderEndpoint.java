package com.moyu.chat;

import okhttp3.HttpUrl;

/** Validates user-configured API roots without coupling them to one provider vendor. */
final class ProviderEndpoint {
    private static final int MAX_URL_CHARS = 2048;
    private static final String HTTPS_PREFIX = "https://";

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_CHARS) throw new IllegalArgumentException("invalid provider base URL");
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || url.host().isEmpty() || !allowedScheme(url)) throw new IllegalArgumentException("provider base URL must use HTTPS (HTTP is only allowed for loopback local model servers)");
        if (!url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("provider base URL must not contain credentials, query, or fragment");
        }
        String normalized = url.toString();
        while (normalized.endsWith("/") && normalized.length() > HTTPS_PREFIX.length()) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    /** Cloud endpoints are HTTPS-only; plain HTTP is reserved for loopback local model servers (ARCHITECTURE.md §7). */
    private static boolean allowedScheme(HttpUrl url) {
        if ("https".equals(url.scheme())) return true;
        if (!"http".equals(url.scheme())) return false;
        String host = url.host();
        return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host);
    }

    private ProviderEndpoint() { }
}
