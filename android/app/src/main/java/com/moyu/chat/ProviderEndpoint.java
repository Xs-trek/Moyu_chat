package com.moyu.chat;

import okhttp3.HttpUrl;

/** Validates user-configured API roots without coupling them to one provider vendor. */
final class ProviderEndpoint {
    private static final int MAX_URL_CHARS = 2048;
    private static final String HTTPS_PREFIX = "https://";

    static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Base URL 不能为空。请填写 API 根地址，例如 https://example.com/v1。");
        if (value.length() > MAX_URL_CHARS) throw new IllegalArgumentException("Base URL 过长，请检查是否误粘贴了请求路径、参数或其它内容。");
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || url.host().isEmpty()) throw new IllegalArgumentException("Base URL 格式无效。请填写完整地址，例如 https://example.com/v1。");
        if (!allowedScheme(url)) throw new IllegalArgumentException("Base URL 必须使用 HTTPS；仅 localhost / 127.0.0.1 / ::1 本地模型服务允许 HTTP。");
        if (!url.username().isEmpty() || !url.password().isEmpty()) {
            throw new IllegalArgumentException("Base URL 不应包含用户名或密码；API Key 请填写在独立的 API Key 字段。");
        }
        if (url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("Base URL 必须是 API 根地址，不能包含 ?query 或 #fragment。");
        }
        String normalized = url.toString();
        while (normalized.endsWith("/") && normalized.length() > HTTPS_PREFIX.length()) normalized = normalized.substring(0, normalized.length() - 1);
        String path = HttpUrl.parse(normalized).encodedPath();
        if (path != null && (path.endsWith("/chat/completions") || path.endsWith("/responses") || path.endsWith("/messages"))) {
            throw new IllegalArgumentException("Base URL 必须填写 API 根地址，不要包含 /chat/completions、/responses 或 /messages");
        }
        return normalized;
    }

    /**
     * Base URL as the protocol's official SDK takes it: OpenAI-style roots include the version segment
     * (https://api.openai.com/v1) and get /chat/completions or /responses; Anthropic-style roots do not
     * (https://api.anthropic.com) and get /v1/messages. Vendor documentation quotes these forms.
     */
    static String normalize(String raw, String protocol) {
        String normalized = normalize(raw);
        String path = HttpUrl.parse(normalized).encodedPath();
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol) && path != null && (path.endsWith("/v1") || path.endsWith("/v1/"))) {
            throw new IllegalArgumentException("Anthropic 格式的 Base URL 不含末尾的 /v1：应用会自动拼接 /v1/messages。例如 https://api.anthropic.com");
        }
        return normalized;
    }

    /** Full request URL for one generation call. */
    static String requestUrl(String base, String protocol) {
        String root = normalize(base, protocol);
        if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(protocol)) return root + "/chat/completions";
        if (OpenAiCompatibleTransport.RESPONSES.equals(protocol)) return root + "/responses";
        if (AnthropicMessagesTransport.PROTOCOL.equals(protocol)) return root + "/v1/messages";
        throw new IllegalArgumentException("unsupported protocol");
    }

    /** Lower-cased host of a stored route, for display and host-change checks. */
    static String host(String base) {
        HttpUrl url = base == null ? null : HttpUrl.parse(base.trim());
        return url == null ? "" : url.host();
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
