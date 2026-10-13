package com.moyu.chat;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Extra, non-secret request headers of one route (OpenAI-Organization, OpenAI-Project, anthropic-beta,
 * gateway attribution). Values are stored in SQLite and shown in the page, so anything that looks like
 * a credential is refused here: the API Key has exactly one home, the native secure store.
 */
final class RouteHeaders {
    static final int MAX_HEADERS = 8;
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,63}");
    /** Transport-owned or credential-shaped names. Matching is on the lower-cased name. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "authorization|proxy-.*|cookie|set-cookie|host|content-.*|accept|accept-encoding|connection|transfer-encoding|te|upgrade"
                    + "|anthropic-version|x-api-key|api-key|.*(key|token|secret|password|passwd|auth|session|credential|signature).*");

    private RouteHeaders() { }

    /** Validated copy; throws with a user-facing message. */
    static JSONObject normalize(JSONObject raw) {
        JSONObject out = new JSONObject();
        if (raw == null) return out;
        if (raw.length() > MAX_HEADERS) throw new IllegalArgumentException("附加请求头最多 " + MAX_HEADERS + " 个");
        try {
            Iterator<String> names = raw.keys();
            while (names.hasNext()) {
                String name = names.next();
                String trimmed = name == null ? "" : name.trim();
                if (!NAME.matcher(trimmed).matches()) throw new IllegalArgumentException("请求头名称无效：" + trimmed);
                if (FORBIDDEN.matcher(trimmed.toLowerCase(Locale.ROOT)).matches()) {
                    throw new IllegalArgumentException("不能在附加请求头里设置 " + trimmed + "：密钥只能通过原生输入框保存，传输相关的头由应用管理");
                }
                String value = raw.optString(name, "").trim();
                if (value.isEmpty() || value.length() > 512) throw new IllegalArgumentException("请求头 " + trimmed + " 的值为空或过长");
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (c < 0x20 || c > 0x7e) throw new IllegalArgumentException("请求头 " + trimmed + " 的值只能是可见 ASCII 字符");
                }
                for (Iterator<String> it = out.keys(); it.hasNext(); ) {
                    if (it.next().equalsIgnoreCase(trimmed)) throw new IllegalArgumentException("请求头重复：" + trimmed);
                }
                out.put(trimmed, value);
            }
            return out;
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    static JSONObject parse(String json) {
        try { return normalize(json == null || json.trim().isEmpty() ? new JSONObject() : new JSONObject(json)); }
        catch (org.json.JSONException error) { throw new IllegalArgumentException("附加请求头不是有效的 JSON"); }
    }

    static void apply(okhttp3.Request.Builder request, JSONObject headers) {
        if (headers == null) return;
        Iterator<String> names = headers.keys();
        while (names.hasNext()) {
            String name = names.next();
            request.header(name, headers.optString(name, ""));
        }
    }
}
