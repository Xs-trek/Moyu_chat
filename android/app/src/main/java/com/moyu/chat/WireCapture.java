package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Redacted provider wire text for the protocol inspector. Request headers are never recorded (the
 * credential travels only there), the API key is additionally scrubbed from every recorded text,
 * inline base64 payloads (images) and other long opaque strings are replaced by a size note, and
 * each record is bounded.
 */
final class WireCapture {
    static final int MAX_CHARS = 256 * 1024;
    /** Strings longer than this that look like base64 are opaque payloads, not readable protocol. */
    private static final int OPAQUE_MIN_CHARS = 1024;

    private WireCapture() { }

    /** {"url", "body"} with the request body redacted; {"url", "truncated", "bodyText"} when too large. */
    static String request(String url, JSONObject body, String apiKey) {
        try {
            JSONObject redacted = (JSONObject) redact(body == null ? new JSONObject() : new JSONObject(body.toString()));
            JSONObject out = new JSONObject().put("url", url == null ? "" : url).put("body", redacted);
            String text = scrub(out.toString(), apiKey);
            if (text.length() <= MAX_CHARS) return text;
            String bodyText = scrub(redacted.toString(), apiKey);
            return new JSONObject().put("url", url == null ? "" : url).put("truncated", true)
                    .put("bodyText", bodyText.substring(0, Math.min(bodyText.length(), MAX_CHARS))).toString();
        } catch (Exception error) { return "{\"error\":\"request not recordable\"}"; }
    }

    /** Raw response text (SSE lines or an error summary) with the HTTP status, bounded. */
    static final class Response {
        private final StringBuilder text = new StringBuilder();
        private int status;
        private boolean truncated;

        void status(int code) { status = code; }
        void line(String line) { append(line == null ? "" : line); append("\n"); }
        void append(String value) {
            if (value == null || truncated) return;
            int room = MAX_CHARS - text.length();
            if (value.length() > room) { text.append(value, 0, Math.max(0, room)); truncated = true; }
            else text.append(value);
        }
        String finish(String apiKey) {
            try {
                return new JSONObject().put("status", status).put("truncated", truncated).put("text", scrub(redactLines(text.toString()), apiKey)).toString();
            } catch (Exception error) { return "{\"error\":\"response not recordable\"}"; }
        }
    }

    static String scrub(String text, String apiKey) {
        if (text == null) return "";
        if (apiKey == null || apiKey.trim().length() < 4) return text;
        return text.replace(apiKey.trim(), "[redacted-key]");
    }

    static Object redact(Object value) throws org.json.JSONException {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            JSONArray names = object.names();
            if (names != null) for (int i = 0; i < names.length(); i++) {
                String name = names.optString(i);
                object.put(name, redact(object.opt(name)));
            }
            return object;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) array.put(i, redact(array.opt(i)));
            return array;
        }
        if (value instanceof String) return redactString((String) value);
        return value;
    }

    /** Streamed lines can carry inline base64 too (image output); each data line is redacted as JSON when it parses. */
    private static String redactLines(String raw) {
        if (raw.length() < OPAQUE_MIN_CHARS) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        for (String line : raw.split("\n", -1)) {
            if (line.length() >= OPAQUE_MIN_CHARS && line.startsWith("data:")) {
                String data = line.substring(5).trim();
                try { line = "data: " + redact(new JSONObject(data)); } catch (Exception ignored) { line = "data: " + redactString(data); }
            }
            out.append(line).append('\n');
        }
        if (out.length() > 0) out.setLength(out.length() - 1);
        return out.toString();
    }

    private static String redactString(String value) {
        if (value.startsWith("data:") && value.contains(";base64,")) {
            int comma = value.indexOf(',');
            return value.substring(0, comma + 1) + "<" + (value.length() - comma - 1) + " base64 chars omitted>";
        }
        if (value.length() >= OPAQUE_MIN_CHARS && looksOpaque(value)) return "<" + value.length() + " opaque chars omitted>";
        return value;
    }

    private static boolean looksOpaque(String value) {
        int sample = Math.min(value.length(), 2048);
        for (int i = 0; i < sample; i++) {
            char c = value.charAt(i);
            boolean base64 = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/' || c == '=' || c == '-' || c == '_';
            if (!base64) return false;
        }
        return true;
    }
}
