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

/**
 * Lightweight, local-only retrieval over user-explicit snapshot knowledge. It deliberately does not
 * search unrelated Chat sessions; cross-chat memory is a separate product/privacy decision.
 */
final class LocalKnowledgeStore {
    static final class Hit {
        final int ordinal;
        final String role;
        final String text;
        final int lexicalScore;
        Hit(int ordinal, String role, String text, int lexicalScore) {
            this.ordinal = ordinal; this.role = role; this.text = text; this.lexicalScore = lexicalScore;
        }
    }

    private static final int MAX_CANDIDATES = 32;
    private final ChatDatabase db;

    LocalKnowledgeStore(ChatDatabase db) { this.db = db; }

    List<Hit> searchSnapshot(String snapshotId, String query, int limit) {
        ArrayList<Hit> out = new ArrayList<>();
        if (snapshotId == null || snapshotId.trim().isEmpty() || limit <= 0) return out;
        String match = KnowledgeLexicon.matchQuery(query);
        if (match.isEmpty()) return out;
        try {
            ensureSnapshotIndexed(snapshotId);
            List<String> queryTerms = KnowledgeLexicon.terms(query);
            String scope = scope(snapshotId);
            try (Cursor c = db.getReadableDatabase().rawQuery(
                    "SELECT ordinal,role,text,terms FROM knowledge_fts WHERE scope_id=? AND terms MATCH ? LIMIT " + MAX_CANDIDATES,
                    new String[]{scope, match})) {
                while (c.moveToNext()) {
                    String terms = c.getString(3) == null ? "" : c.getString(3);
                    Set<String> indexed = new HashSet<>();
                    for (String term : terms.split(" ")) if (!term.isEmpty()) indexed.add(term);
                    int score = 0;
                    for (String term : queryTerms) if (indexed.contains(term)) score++;
                    if (score > 0) out.add(new Hit(c.getInt(0), c.getString(1), c.getString(2), score));
                }
            }
            out.sort(Comparator.comparingInt((Hit hit) -> hit.lexicalScore).reversed().thenComparingInt(hit -> -hit.ordinal));
            if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
            return out;
        } catch (Exception ignored) {
            // Retrieval is an optional context aid. A missing FTS module/index must never break Chat.
            return new ArrayList<>();
        }
    }

    private void ensureSnapshotIndexed(String snapshotId) {
        String scope = scope(snapshotId);
        SQLiteDatabase writable = db.getWritableDatabase();
        try (Cursor c = writable.rawQuery("SELECT 1 FROM knowledge_fts WHERE scope_id=? LIMIT 1", new String[]{scope})) {
            if (c.moveToFirst()) return;
        }
        JSONArray items = db.snapshotItems(snapshotId);
        writable.beginTransaction();
        try {
            writable.delete("knowledge_fts", "scope_id=?", new String[]{scope});
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                if (!"message".equals(item.optString("kind", "message"))) continue;
                String role = item.optString("role", "");
                if (!("user".equals(role) || "assistant".equals(role))) continue;
                String text = item.optString("text", "").trim();
                if (text.isEmpty()) continue;
                String terms = KnowledgeLexicon.indexTerms(text);
                if (terms.isEmpty()) continue;
                ContentValues row = new ContentValues();
                row.put("scope_id", scope); row.put("item_ref", snapshotId + ":" + i); row.put("ordinal", String.valueOf(i));
                row.put("role", role); row.put("text", text); row.put("terms", terms);
                writable.insert("knowledge_fts", null, row);
            }
            writable.setTransactionSuccessful();
        } finally { writable.endTransaction(); }
    }

    private static String scope(String snapshotId) { return "snapshot:" + snapshotId; }
}
