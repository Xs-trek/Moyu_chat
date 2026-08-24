package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;

/** Creates immutable provider-neutral snapshots from local or bridge-normalized visible history. */
final class SnapshotService {
    private static final int MAX_ITEMS = 2_000;
    private static final int MAX_TEXT_CHARS = 300_000;
    private static final int MAX_METADATA_CHARS = 512;
    private final ChatDatabase db;

    private static final class Normalized {
        final JSONArray items;
        final int sourceItemCount;
        final boolean truncated;
        Normalized(JSONArray items, int sourceItemCount, boolean truncated) {
            this.items = items; this.sourceItemCount = sourceItemCount; this.truncated = truncated;
        }
    }

    SnapshotService(ChatDatabase db) { this.db = db; }

    ChatDatabase.Snapshot createLocal(String sessionId, Integer throughSeq) throws Exception {
        ChatDatabase.Session session = db.getSession(sessionId);
        if (session == null) throw new IllegalArgumentException("source session not found");
        JSONArray source = new JSONArray();
        if (session.originSnapshotId != null) append(source, db.snapshotItems(session.originSnapshotId));
        append(source, db.timelineThrough(sessionId, throughSeq));
        Normalized normalized = normalize(source);
        return persist("chat", sessionId, bounded(session.title, "source title"), null, normalized,
                Instant.now().toString(), normalized.sourceItemCount, normalized.truncated);
    }

    ChatDatabase.Snapshot importRemote(JSONObject exported) throws Exception {
        if (exported == null) throw new IllegalArgumentException("remote snapshot missing");
        String sourceId = bounded(required(exported, "sourceId"), "source id");
        String title = bounded(required(exported, "sourceTitle"), "source title");
        String runtime = nullable(exported, "sourceRuntime");
        if (runtime != null) runtime = bounded(runtime, "source runtime");
        String createdAt = bounded(exported.optString("createdAt", Instant.now().toString()), "createdAt");
        Normalized normalized = normalize(exported.optJSONArray("items"));
        int advertisedSourceCount = Math.max(0, exported.optInt("sourceItemCount", 0));
        int sourceCount = Math.max(normalized.sourceItemCount, advertisedSourceCount);
        boolean truncated = normalized.truncated || exported.optBoolean("truncated", false) || sourceCount > normalized.items.length();
        return persist("remote", sourceId, title, runtime, normalized, createdAt, sourceCount, truncated);
    }

    private ChatDatabase.Snapshot persist(String sourceType, String sourceId, String title, String runtime,
                                          Normalized normalized, String createdAt, int sourceItemCount,
                                          boolean truncated) throws Exception {
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.id = UUID.randomUUID().toString(); snapshot.sourceType = sourceType; snapshot.sourceId = sourceId;
        snapshot.sourceTitle = title; snapshot.sourceRuntime = runtime; snapshot.createdAt = createdAt;
        snapshot.itemCount = normalized.items.length(); snapshot.sourceItemCount = Math.max(snapshot.itemCount, sourceItemCount);
        snapshot.truncated = truncated || snapshot.sourceItemCount > snapshot.itemCount;
        snapshot.contentHash = sha256(normalized.items.toString());
        db.saveSnapshot(snapshot, normalized.items);
        return snapshot;
    }

    /** Keeps one contiguous recent visible tail. Older content is either retained or explicitly marked omitted. */
    private static Normalized normalize(JSONArray raw) throws Exception {
        if (raw == null) return new Normalized(new JSONArray(), 0, false);
        int sourceVisible = 0;
        for (int i = 0; i < raw.length(); i++) if (visible(raw.optJSONObject(i))) sourceVisible++;

        ArrayList<JSONObject> reverse = new ArrayList<>();
        int chars = 0;
        boolean truncated = false;
        for (int i = raw.length() - 1; i >= 0; i--) {
            JSONObject item = raw.optJSONObject(i);
            if (!visible(item)) continue;
            String text = item.optString("text", "");
            if (text.length() > MAX_TEXT_CHARS && reverse.isEmpty()) throw new IllegalArgumentException("latest snapshot message exceeds snapshot limit");
            if (reverse.size() >= MAX_ITEMS || chars + text.length() > MAX_TEXT_CHARS) { truncated = true; break; }
            chars += text.length();
            JSONObject normalizedItem = new JSONObject().put("kind", "message").put("role", item.optString("role")).put("text", text)
                    .put("createdAt", bounded(item.optString("createdAt", Instant.now().toString()), "createdAt"));
            JSONArray attachments = normalizeAttachments(item.optJSONArray("attachments"));
            if (attachments.length() > 0) normalizedItem.put("attachments", attachments);
            reverse.add(normalizedItem);
        }
        Collections.reverse(reverse);
        JSONArray out = new JSONArray(); for (JSONObject item : reverse) out.put(item);
        return new Normalized(out, sourceVisible, truncated || out.length() < sourceVisible);
    }

    private static boolean visible(JSONObject item) {
        if (item == null || !"message".equals(item.optString("kind")) || item.optBoolean("streaming", false)) return false;
        String role = item.optString("role", "");
        return ("user".equals(role) || "assistant".equals(role)) && !item.optString("text", "").trim().isEmpty();
    }

    private static void append(JSONArray target, JSONArray source) {
        if (source == null) return;
        for (int i = 0; i < source.length(); i++) target.put(source.opt(i));
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }

    private static String required(JSONObject o, String key) { String value = o.optString(key, "").trim(); if (value.isEmpty()) throw new IllegalArgumentException("missing " + key); return value; }
    private static String nullable(JSONObject o, String key) { if (!o.has(key) || o.isNull(key)) return null; String value = o.optString(key, "").trim(); return value.isEmpty() ? null : value; }
    private static String bounded(String value, String label) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_METADATA_CHARS) throw new IllegalArgumentException(label + " too long");
        return clean;
    }
}
