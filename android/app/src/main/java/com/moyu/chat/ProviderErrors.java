package com.moyu.chat;

import java.io.IOException;
import java.util.Locale;

import okhttp3.Response;

/**
 * User-facing classification of provider failures. Error codes stay protocol-neutral
 * ({@code http_<status>}, {@code network_error}); the summary says what most likely happened and what
 * to check, and carries the provider's request id so a support ticket can quote it. The phone usually
 * reaches providers through a proxy, so network failures point at the network/proxy, not the key.
 */
final class ProviderErrors {
    private ProviderErrors() { }

    static String requestId(Response response) {
        if (response == null) return "";
        String id = response.header("x-request-id");
        if (id == null || id.trim().isEmpty()) id = response.header("request-id");
        if (id == null) return "";
        id = id.trim();
        return id.length() > 128 ? id.substring(0, 128) : id;
    }

    /** Longest wait honoured from a Retry-After header; a longer ask is treated as "not now". */
    static final long MAX_RETRY_AFTER_MS = 60_000L;

    /** Retry-After as milliseconds (delta-seconds or HTTP date), 0 when absent, unparsable or in the past. */
    static long retryAfterMillis(Response response) {
        String value = response == null ? null : response.header("retry-after");
        if (value == null || value.trim().isEmpty()) return 0L;
        String text = value.trim();
        try {
            double seconds = Double.parseDouble(text);
            return seconds > 0 ? Math.min(MAX_RETRY_AFTER_MS + 1, (long) Math.ceil(seconds * 1000)) : 0L;
        } catch (NumberFormatException notSeconds) {
            try {
                long at = java.time.ZonedDateTime.parse(text, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                long wait = at - System.currentTimeMillis();
                return wait > 0 ? Math.min(MAX_RETRY_AFTER_MS + 1, wait) : 0L;
            } catch (Exception unparsable) { return 0L; }
        }
    }

    static boolean retryable(int status, String detail) {
        if (status == 429) return !quota(detail);
        return status == 408 || status == 409 || status == 529 || status >= 500;
    }

    static String httpSummary(int status, String detail, String requestId) {
        String d = detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ').trim();
        if (d.length() > 400) d = d.substring(0, 400);
        StringBuilder out = new StringBuilder(meaning(status, d)).append("（HTTP ").append(status).append("）");
        if (!d.isEmpty()) out.append("：").append(d);
        if (requestId != null && !requestId.isEmpty()) out.append(" · 请求 ID ").append(requestId);
        return out.toString();
    }

    private static String meaning(int status, String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        switch (status) {
            case 400:
                if (lower.contains("context") && (lower.contains("length") || lower.contains("window") || lower.contains("exceed"))
                        || lower.contains("prompt is too long") || lower.contains("too many tokens")) return "上下文超出模型限制";
                return "请求被拒绝：这个端点不接受某个参数";
            case 401: return "API Key 无效或已失效";
            case 402: return "账户余额不足";
            case 403:
                if (region(lower)) return "当前网络出口地区不受该服务支持，请检查代理节点";
                return "没有权限访问这个模型或功能";
            case 404: return "端点或模型不存在：检查 Base URL 和 Model ID";
            case 408: return "请求超时";
            case 413: return "请求体过大";
            case 429: return quota(detail) ? "额度已用完或余额不足" : "请求过于频繁，被限流";
            case 529: return "服务过载，请稍后重试";
            default:
                if (status >= 500) return "服务端暂时出错";
                return "请求失败";
        }
    }

    private static boolean region(String lower) {
        return lower.contains("country") || lower.contains("region") || lower.contains("territory") || lower.contains("location is not supported");
    }

    private static boolean quota(String detail) {
        String lower = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        return lower.contains("insufficient_quota") || lower.contains("quota") || lower.contains("balance") || lower.contains("credit");
    }

    /** Transport-level failure before or during the response; never mentions the key. */
    static String networkSummary(IOException error) {
        if (error instanceof java.net.UnknownHostException) return "无法解析服务器域名：请检查网络或代理";
        if (error instanceof java.net.ConnectException || error instanceof java.net.NoRouteToHostException) return "连接服务器失败：请检查网络或代理";
        if (error instanceof javax.net.ssl.SSLException) return "HTTPS 握手失败：网络或代理可能在拦截加密连接（本应用只信任系统证书）";
        if (error instanceof java.net.SocketTimeoutException) return "连接超时：请检查网络或代理";
        String message = error.getMessage();
        return "网络错误：" + (message == null || message.isEmpty() ? error.getClass().getSimpleName() : message);
    }
}
