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

/** Creates immutable provider-neutral snapshots of local visible history for branches. */
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
        if (session == null) throw new IllegalArgumentException("原会话不存在");
        JSONArray source = new JSONArray();
        append(source, SessionHistory.through(db, session, throughSeq));
        Normalized normalized = normalize(source);
        return persist(sessionId, bounded(session.title, "source title"), normalized);
    }

    private ChatDatabase.Snapshot persist(String sourceSessionId, String title, Normalized normalized) throws Exception {
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.id = UUID.randomUUID().toString(); snapshot.sourceSessionId = sourceSessionId;
        snapshot.sourceTitle = title; snapshot.createdAt = Instant.now().toString();
        snapshot.itemCount = normalized.items.length(); snapshot.sourceItemCount = Math.max(snapshot.itemCount, normalized.sourceItemCount);
        snapshot.truncated = normalized.truncated || snapshot.sourceItemCount > snapshot.itemCount;
        snapshot.contentHash = sha256(normalized.items.toString());
        db.saveSnapshot(snapshot, normalized.items);
        return snapshot;
    }

    /** Keeps one contiguous recent tail of complete user-led turns; orphan assistants are never snapshotted alone. */
    private static Normalized normalize(JSONArray raw) throws Exception {
        if (raw == null) return new Normalized(new JSONArray(), 0, false);
        int sourceVisible = 0;
        boolean structurallyOmitted = false;
        ArrayList<ArrayList<JSONObject>> turns = new ArrayList<>();
        ArrayList<JSONObject> current = null;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject item = raw.optJSONObject(i);
            if (!visible(item)) continue;
            sourceVisible++;
            JSONObject normalizedItem = normalizeVisibleItem(item);
            if ("user".equals(normalizedItem.optString("role"))) {
                current = new ArrayList<>(); current.add(normalizedItem); turns.add(current);
            } else if (current != null) {
                current.add(normalizedItem);
            } else {
                structurallyOmitted = true;
            }
        }

        ArrayList<ArrayList<JSONObject>> keptReverse = new ArrayList<>();
        int keptItems = 0, chars = 0;
        boolean truncated = structurallyOmitted;
        for (int i = turns.size() - 1; i >= 0; i--) {
            ArrayList<JSONObject> turn = turns.get(i);
            int turnChars = 0;
            for (JSONObject item : turn) turnChars += item.optString("text", "").length();
            if (keptReverse.isEmpty() && (turn.size() > MAX_ITEMS || turnChars > MAX_TEXT_CHARS)) {
                throw new IllegalArgumentException("最新一轮超出分支快照的大小上限");
            }
            if (keptItems + turn.size() > MAX_ITEMS || chars + turnChars > MAX_TEXT_CHARS) { truncated = true; break; }
            keptReverse.add(turn); keptItems += turn.size(); chars += turnChars;
        }
        Collections.reverse(keptReverse);
        JSONArray out = new JSONArray();
        for (ArrayList<JSONObject> turn : keptReverse) for (JSONObject item : turn) out.put(item);
        return new Normalized(out, sourceVisible, truncated || out.length() < sourceVisible);
    }

    private static JSONObject normalizeVisibleItem(JSONObject item) throws Exception {
        String text = item.optString("text", "");
        JSONObject normalizedItem = new JSONObject().put("kind", "message").put("role", item.optString("role")).put("text", text)
                .put("createdAt", bounded(item.optString("createdAt", Instant.EPOCH.toString()), "createdAt"));
        if ("assistant".equals(item.optString("role")) && item.optBoolean("incomplete", false)) {
            normalizedItem.put("incomplete", true).put("terminalCode", bounded(item.optString("terminalCode", "interrupted"), "terminal code"));
        }
        JSONArray attachments = normalizeAttachments(item.optJSONArray("attachments"));
        if (attachments.length() > 0) normalizedItem.put("attachments", attachments);
        return normalizedItem;
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

    private static String bounded(String value, String label) {
        String clean = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_METADATA_CHARS) throw new IllegalArgumentException(label + " too long");
        return clean;
    }
}
