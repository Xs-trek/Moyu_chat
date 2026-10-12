package com.moyu.chat;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Lightweight local FTS retrieval. Retrieved text is always non-authoritative request data. */
final class LocalKnowledgeStore {
    static final class Hit {
        final String scopeId;
        final String sourceId;
        final String sourceTitle;
        final int ordinal;
        final String role;
        final String text;
        final int lexicalScore;
        Hit(String scopeId, String sourceId, String sourceTitle, int ordinal, String role, String text, int lexicalScore) {
            this.scopeId = scopeId; this.sourceId = sourceId; this.sourceTitle = sourceTitle;
            this.ordinal = ordinal; this.role = role; this.text = text; this.lexicalScore = lexicalScore;
        }
    }

    private static final int MAX_CANDIDATES = 96;
    private static final String INDEX_MARKER_PREFIX = "knowledgeIndex:";
    private final ChatDatabase db;

    LocalKnowledgeStore(ChatDatabase db) { this.db = db; }

    List<Hit> searchSnapshot(String snapshotId, String query, int limit) {
        ArrayList<Hit> out = new ArrayList<>();
        if (snapshotId == null || snapshotId.trim().isEmpty() || limit <= 0) return out;
        String match = KnowledgeLexicon.matchQuery(query);
        if (match.isEmpty()) return out;
        try {
            ensureSnapshotIndexed(snapshotId);
            ChatDatabase.Snapshot snapshot = db.getSnapshot(snapshotId);
            String title = snapshot == null ? "Snapshot" : snapshot.sourceTitle;
            collect(out, "scope_id=? AND terms MATCH ?", new String[]{snapshotScope(snapshotId), match},
                    snapshotId, title, KnowledgeLexicon.terms(query));
            return rank(out, limit);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /** User-memory search spans other local Chat timelines; the current session is excluded to avoid echoing visible history. */
    List<Hit> searchUserMemory(String excludeSessionId, String query, int limit) {
        ArrayList<Hit> out = new ArrayList<>();
        if (limit <= 0) return out;
        String match = KnowledgeLexicon.matchQuery(query);
        if (match.isEmpty()) return out;
        try {
            for (ChatDatabase.Session session : db.listSessions()) {
                if (session == null || session.id == null || session.id.equals(excludeSessionId)) continue;
                ensureSessionIndexed(session);
            }
            List<String> queryTerms = KnowledgeLexicon.terms(query);
            String excluded = chatScope(excludeSessionId == null ? "" : excludeSessionId);
            try (Cursor c = db.getReadableDatabase().rawQuery(
                    "SELECT scope_id,ordinal,role,text,terms FROM knowledge_fts WHERE scope_id LIKE 'chat:%' AND scope_id<>? AND terms MATCH ? LIMIT " + MAX_CANDIDATES,
                    new String[]{excluded, match})) {
                while (c.moveToNext()) {
                    String scope = c.getString(0);
                    String sourceId = scope.startsWith("chat:") ? scope.substring(5) : scope;
                    ChatDatabase.Session source = db.getSession(sourceId);
                    if (source == null) continue;
                    int score = lexicalScore(c.getString(4), queryTerms);
                    if (score > 0) out.add(new Hit(scope, sourceId, source.title, c.getInt(1), c.getString(2), c.getString(3), score));
                }
            }
            return rank(out, limit);
        } catch (Exception ignored) {
            // Memory/RAG is optional. FTS corruption/module absence must not break ordinary Chat.
            return new ArrayList<>();
        }
    }

    private void collect(List<Hit> out, String where, String[] args, String sourceId, String sourceTitle,
                         List<String> queryTerms) {
        try (Cursor c = db.getReadableDatabase().rawQuery(
                "SELECT scope_id,ordinal,role,text,terms FROM knowledge_fts WHERE " + where + " LIMIT " + MAX_CANDIDATES, args)) {
            while (c.moveToNext()) {
                int score = lexicalScore(c.getString(4), queryTerms);
                if (score > 0) out.add(new Hit(c.getString(0), sourceId, sourceTitle, c.getInt(1), c.getString(2), c.getString(3), score));
            }
        }
    }

    private static int lexicalScore(String terms, List<String> queryTerms) {
        Set<String> indexed = new HashSet<>();
        if (terms != null) for (String term : terms.split(" ")) if (!term.isEmpty()) indexed.add(term);
        int score = 0;
        for (String term : queryTerms) if (indexed.contains(term)) score++;
        return score;
    }

    private static List<Hit> rank(ArrayList<Hit> out, int limit) {
        out.sort(Comparator.comparingInt((Hit hit) -> hit.lexicalScore).reversed().thenComparingInt(hit -> -hit.ordinal));
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        return out;
    }

    private void ensureSnapshotIndexed(String snapshotId) {
        String scope = snapshotScope(snapshotId);
        SQLiteDatabase writable = db.getWritableDatabase();
        try (Cursor c = writable.rawQuery("SELECT 1 FROM knowledge_fts WHERE scope_id=? LIMIT 1", new String[]{scope})) {
            if (c.moveToFirst()) return;
        }
        JSONArray items = db.snapshotItems(snapshotId);
        writable.beginTransaction();
        try {
            writable.delete("knowledge_fts", "scope_id=?", new String[]{scope});
            for (int i = 0; i < items.length(); i++) indexMessage(writable, scope, snapshotId + ":" + i, i, items.optJSONObject(i));
            writable.setTransactionSuccessful();
        } finally { writable.endTransaction(); }
    }

    private void ensureSessionIndexed(ChatDatabase.Session session) {
        String markerKey = INDEX_MARKER_PREFIX + session.id;
        String marker = db.setting(markerKey, "");
        if (session.updatedAt != null && session.updatedAt.equals(marker)) return;
        String scope = chatScope(session.id);
        JSONArray items = db.timeline(session.id);
        SQLiteDatabase writable = db.getWritableDatabase();
        writable.beginTransaction();
        try {
            writable.delete("knowledge_fts", "scope_id=?", new String[]{scope});
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                int seq = item == null ? i : item.optInt("localSeq", i);
                indexMessage(writable, scope, session.id + ":" + seq, seq, item);
            }
            writable.setTransactionSuccessful();
        } finally { writable.endTransaction(); }
        db.putSetting(markerKey, session.updatedAt == null ? "" : session.updatedAt);
    }

    private static void indexMessage(SQLiteDatabase writable, String scope, String itemRef, int ordinal, JSONObject item) {
        if (item == null || !"message".equals(item.optString("kind", "message")) || item.optBoolean("streaming", false)) return;
        String role = item.optString("role", "");
        if (!("user".equals(role) || "assistant".equals(role))) return;
        String text = item.optString("text", "").trim();
        if (text.isEmpty()) return;
        String terms = KnowledgeLexicon.indexTerms(text);
        if (terms.isEmpty()) return;
        ContentValues row = new ContentValues();
        row.put("scope_id", scope); row.put("item_ref", itemRef); row.put("ordinal", String.valueOf(ordinal));
        row.put("role", role); row.put("text", text); row.put("terms", terms);
        writable.insert("knowledge_fts", null, row);
    }

    static String snapshotScope(String snapshotId) { return "snapshot:" + snapshotId; }
    static String chatScope(String sessionId) { return "chat:" + (sessionId == null ? "" : sessionId); }
    static String indexMarkerKey(String sessionId) { return INDEX_MARKER_PREFIX + sessionId; }
}
