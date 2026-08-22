package com.moyu.chat;

import okhttp3.HttpUrl;

/** Validates user-configured API roots without coupling them to one provider vendor. */
final class ProviderEndpoint {
    private static final int MAX_URL_CHARS = 2048;

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_CHARS) throw new IllegalArgumentException("invalid provider base URL");
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !"https".equals(url.scheme()) || url.host().isEmpty()) throw new IllegalArgumentException("provider base URL must use HTTPS");
        if (!url.username().isEmpty() || !url.password().isEmpty() || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("provider base URL must not contain credentials, query, or fragment");
        }
        String normalized = url.toString();
        while (normalized.endsWith("/") && normalized.length() > "https://x/".length()) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private ProviderEndpoint() { }
}
