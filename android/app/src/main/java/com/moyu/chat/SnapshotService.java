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

/** Creates immutable provider-neutral snapshots from local, Remote, or explicitly imported visible history. */
final class SnapshotService {
    private static final int MAX_ITEMS = 2_000;
    private static final int MAX_TEXT_CHARS = 300_000;
    private static final int IMPORT_MAX_ITEMS = 10_000;
    private static final int IMPORT_MAX_TEXT_CHARS = 2_000_000;
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
        Normalized normalized = normalize(source, MAX_ITEMS, MAX_TEXT_CHARS);
        return persist("chat", sessionId, bounded(session.title, "source title"), null, normalized,
                Instant.now().toString(), normalized.sourceItemCount, normalized.truncated, false);
    }

    ChatDatabase.Snapshot importRemote(JSONObject exported) throws Exception {
        if (exported == null) throw new IllegalArgumentException("remote snapshot missing");
        String sourceId = bounded(required(exported, "sourceId"), "source id");
        String title = bounded(required(exported, "sourceTitle"), "source title");
        String runtime = nullable(exported, "sourceRuntime");
        if (runtime != null) runtime = bounded(runtime, "source runtime");
        String createdAt = bounded(exported.optString("createdAt", Instant.now().toString()), "createdAt");
        Normalized normalized = normalize(exported.optJSONArray("items"), MAX_ITEMS, MAX_TEXT_CHARS);
        int advertisedSourceCount = Math.max(0, exported.optInt("sourceItemCount", 0));
        int sourceCount = Math.max(normalized.sourceItemCount, advertisedSourceCount);
        boolean truncated = normalized.truncated || exported.optBoolean("truncated", false) || sourceCount > normalized.items.length();
        return persist("remote", sourceId, title, runtime, normalized, createdAt, sourceCount, truncated, false);
    }

    ChatDatabase.Snapshot importExternal(String source, ConversationImportService.ImportedConversation imported) throws Exception {
        if (imported == null) throw new IllegalArgumentException("imported conversation missing");
        String normalizedSource = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        String sourceType;
        String runtime;
        if (ConversationImportService.SOURCE_CHATGPT.equals(normalizedSource)) {
            sourceType = "import-chatgpt"; runtime = "ChatGPT export";
        } else if (ConversationImportService.SOURCE_CLAUDE.equals(normalizedSource)) {
            sourceType = "import-claude"; runtime = "Claude export";
        } else throw new IllegalArgumentException("unsupported import source");
        String sourceId = bounded(imported.sourceId, "source id");
        String title = bounded(imported.title, "source title");
        Normalized normalized = normalize(imported.items, IMPORT_MAX_ITEMS, IMPORT_MAX_TEXT_CHARS);
        int sourceCount = Math.max(normalized.sourceItemCount, imported.sourceMessageCount);
        boolean truncated = normalized.truncated || sourceCount > normalized.items.length();
        return persist(sourceType, sourceId, title, runtime, normalized,
                bounded(imported.createdAt, "createdAt"), sourceCount, truncated, true);
    }

    private ChatDatabase.Snapshot persist(String sourceType, String sourceId, String title, String runtime,
                                          Normalized normalized, String createdAt, int sourceItemCount,
                                          boolean truncated, boolean deduplicate) throws Exception {
        String contentHash = sha256(normalized.items.toString());
        if (deduplicate) {
            ChatDatabase.Snapshot existing = db.findSnapshotBySourceContent(sourceType, sourceId, contentHash);
            if (existing != null) return existing;
        }
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.id = UUID.randomUUID().toString(); snapshot.sourceType = sourceType; snapshot.sourceId = sourceId;
        snapshot.sourceTitle = title; snapshot.sourceRuntime = runtime; snapshot.createdAt = createdAt;
        snapshot.itemCount = normalized.items.length(); snapshot.sourceItemCount = Math.max(snapshot.itemCount, sourceItemCount);
        snapshot.truncated = truncated || snapshot.sourceItemCount > snapshot.itemCount;
        snapshot.contentHash = contentHash;
        db.saveSnapshot(snapshot, normalized.items);
        return snapshot;
    }

    /** Keeps one contiguous recent visible tail. Older content is either retained or explicitly marked omitted. */
    private static Normalized normalize(JSONArray raw, int maxItems, int maxTextChars) throws Exception {
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
            if (text.length() > maxTextChars && reverse.isEmpty()) throw new IllegalArgumentException("latest snapshot message exceeds snapshot limit");
            if (reverse.size() >= maxItems || chars + text.length() > maxTextChars) { truncated = true; break; }
            chars += text.length();
            JSONObject normalizedItem = new JSONObject().put("kind", "message").put("role", item.optString("role")).put("text", text)
                    .put("createdAt", bounded(item.optString("createdAt", Instant.EPOCH.toString()), "createdAt"));
            if ("assistant".equals(item.optString("role")) && item.optBoolean("incomplete", false)) {
                normalizedItem.put("incomplete", true).put("terminalCode", bounded(item.optString("terminalCode", "interrupted"), "terminal code"));
            }
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
        String role = item.optString("role", ""); if (!("user".equals(role) || "assistant".equals(role))) return false;
        return !item.optString("text", "").trim().isEmpty() || normalizeAttachments(item.optJSONArray("attachments")).length() > 0;
    }

    private static JSONArray normalizeAttachments(JSONArray source) {
        JSONArray out = new JSONArray(); if (source == null) return out;
        for (int i = 0; i < source.length() && out.length() < ChatMediaStore.MAX_DRAFT_IMAGES; i++) {
            JSONObject item = source.optJSONObject(i); if (item == null || !"image".equals(item.optString("kind"))) continue;
            String mediaId = item.optString("mediaId", "").trim(); String mime = item.optString("mimeType", "").trim();
            if (!mediaId.matches("[A-Za-z0-9_-]{1,128}") || !mime.startsWith("image/")) continue;
            try {
                out.put(new JSONObject().put("kind", "image").put("mediaId", mediaId).put("mimeType", mime)
                        .put("fileName", bounded(item.optString("fileName", "image"), "file name"))
                        .put("sizeBytes", Math.max(0, item.optLong("sizeBytes", 0))).put("width", Math.max(0, item.optInt("width", 0)))
                        .put("height", Math.max(0, item.optInt("height", 0))).put("url", "/media/" + mediaId));
            } catch (Exception ignored) { }
        }
        return out;
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
