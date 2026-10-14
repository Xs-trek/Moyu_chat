package com.moyu.chat;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lightweight local FTS retrieval. Retrieved text is always non-authoritative request data.
 *
 * Messages are indexed in chunks (KnowledgeLexicon.chunks) so every part of a long answer is findable.
 * A query keeps its words and CJK bigrams; candidates come from its rarest terms that occur at all, and
 * are ranked by the inverse document frequency of every query term they contain, so a shared rare word
 * outweighs many common characters. Chat timelines are indexed incrementally: the session row remembers
 * the last timeline sequence indexed ("v2:<seq>"), and only rows after it are read.
 */
final class LocalKnowledgeStore {
    static final class Hit {
        final String scopeId;
        final String sourceId;
        final String sourceTitle;
        final int ordinal;
        final String role;
        final String text;
        final double score;
        Hit(String scopeId, String sourceId, String sourceTitle, int ordinal, String role, String text, double score) {
            this.scopeId = scopeId; this.sourceId = sourceId; this.sourceTitle = sourceTitle;
            this.ordinal = ordinal; this.role = role; this.text = text; this.score = score;
        }
    }

    /** Index format marker on sessions.knowledge_indexed_at; anything else is an older index and is rebuilt once. */
    static final String MARKER_PREFIX = "v2:";
    /** Chunk refs end in "#c<n>"; a snapshot indexed without them predates chunking and is rebuilt. */
    private static final String CHUNK_SUFFIX = "#c";
    private static final int MAX_CANDIDATES = 400;
    /** Rarest query terms used to fetch candidates; ranking still uses every query term. */
    private static final int CANDIDATE_TERMS = 8;
    private final ChatDatabase db;

    LocalKnowledgeStore(ChatDatabase db) { this.db = db; }

    List<Hit> searchSnapshot(String snapshotId, String query, int limit) {
        if (snapshotId == null || snapshotId.trim().isEmpty() || limit <= 0) return new ArrayList<>();
        try {
            ensureSnapshotIndexed(snapshotId);
            ChatDatabase.Snapshot snapshot = db.getSnapshot(snapshotId);
            String title = snapshot == null ? "Snapshot" : snapshot.sourceTitle;
            return search("scope_id=?", new String[]{snapshotScope(snapshotId)}, query, limit, scope -> new String[]{snapshotId, title});
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    /** User-memory search spans other local Chat timelines; the current session is excluded to avoid echoing visible history. */
    List<Hit> searchUserMemory(String excludeSessionId, String query, int limit) {
        if (limit <= 0) return new ArrayList<>();
        try {
            ensureChatsIndexed(excludeSessionId);
            Map<String, String> titles = new HashMap<>();
            return search("scope_id LIKE 'chat:%' AND scope_id<>?", new String[]{chatScope(excludeSessionId == null ? "" : excludeSessionId)}, query, limit, scope -> {
                String sourceId = scope.startsWith("chat:") ? scope.substring(5) : scope;
                String title = titles.get(sourceId);
                if (title == null) {
                    ChatDatabase.Session source = db.getSession(sourceId);
                    if (source == null) return null;
                    title = source.title;
                    titles.put(sourceId, title);
                }
                return new String[]{sourceId, title};
            });
        } catch (Exception ignored) {
            // Memory/RAG is optional. FTS corruption/module absence must not break ordinary Chat.
            return new ArrayList<>();
        }
    }

    private interface Source { String[] of(String scope); }

    private List<Hit> search(String scopeWhere, String[] scopeArgs, String query, int limit, Source source) {
        List<String> queryTerms = KnowledgeLexicon.queryTerms(query);
        if (queryTerms.isEmpty()) return new ArrayList<>();
        SQLiteDatabase readable = db.getReadableDatabase();
        long total = Math.max(1, android.database.DatabaseUtils.queryNumEntries(readable, "knowledge_fts"));
        Map<String, Double> idf = new HashMap<>();
        List<String> present = new ArrayList<>();
        for (String term : queryTerms) {
            long df = android.database.DatabaseUtils.longForQuery(readable, "SELECT count(*) FROM knowledge_fts WHERE terms MATCH ?", new String[]{term});
            if (df <= 0) continue;
            idf.put(term, Math.log(1d + (total - df + 0.5d) / (df + 0.5d)));
            present.add(term);
        }
        if (present.isEmpty()) return new ArrayList<>();
        present.sort(Comparator.comparingDouble((String term) -> idf.get(term)).reversed());
        String match = String.join(" OR ", present.subList(0, Math.min(CANDIDATE_TERMS, present.size())));
        boolean onlyUnigrams = true;
        for (String term : queryTerms) if (!KnowledgeLexicon.isUnigram(term)) { onlyUnigrams = false; break; }
        double maxScore = 0;
        for (String term : present) maxScore += idf.get(term);

        String[] args = new String[scopeArgs.length + 1];
        System.arraycopy(scopeArgs, 0, args, 0, scopeArgs.length);
        args[scopeArgs.length] = match;
        ArrayList<Hit> out = new ArrayList<>();
        try (Cursor c = readable.rawQuery("SELECT scope_id,ordinal,role,text,terms FROM knowledge_fts WHERE " + scopeWhere
                + " AND terms MATCH ? LIMIT " + MAX_CANDIDATES, args)) {
            while (c.moveToNext()) {
                Set<String> indexed = new HashSet<>();
                String terms = c.getString(4);
                if (terms != null) for (String term : terms.split(" ")) if (!term.isEmpty()) indexed.add(term);
                double score = 0;
                boolean specific = onlyUnigrams;
                for (String term : present) {
                    if (!indexed.contains(term)) continue;
                    score += idf.get(term);
                    if (!KnowledgeLexicon.isUnigram(term)) specific = true;
                }
                // A chunk that shares only common single characters with the question is noise, not memory.
                if (!specific || score < 0.2d * maxScore) continue;
                String[] origin = source.of(c.getString(0));
                if (origin == null) continue;
                out.add(new Hit(c.getString(0), origin[0], origin[1], parseOrdinal(c.getString(1)), c.getString(2), c.getString(3), score));
            }
        }
        out.sort(Comparator.comparingDouble((Hit hit) -> hit.score).reversed().thenComparingInt(hit -> -hit.ordinal));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    private static int parseOrdinal(String raw) {
        try { return Integer.parseInt(raw); } catch (Exception ignored) { return 0; }
    }

    private void ensureSnapshotIndexed(String snapshotId) {
        String scope = snapshotScope(snapshotId);
        SQLiteDatabase writable = db.getWritableDatabase();
        try (Cursor c = writable.rawQuery("SELECT item_ref FROM knowledge_fts WHERE scope_id=? LIMIT 1", new String[]{scope})) {
            if (c.moveToFirst() && c.getString(0) != null && c.getString(0).contains(CHUNK_SUFFIX)) return;
        }
        JSONArray items = db.snapshotItems(snapshotId);
        writable.beginTransaction();
        try {
            writable.delete("knowledge_fts", "scope_id=?", new String[]{scope});
            for (int i = 0; i < items.length(); i++) indexMessage(writable, scope, snapshotId + ":" + i, i, items.optJSONObject(i));
            writable.setTransactionSuccessful();
        } finally { writable.endTransaction(); }
    }

    /** Brings every Chat's index up to its latest finished row; sessions without new rows cost one map lookup. */
    private void ensureChatsIndexed(String excludeSessionId) {
        Map<String, Integer> latest = db.latestTimelineSeqs();
        for (ChatDatabase.Session session : db.listSessions()) {
            if (session == null || session.id == null || session.id.equals(excludeSessionId)) continue;
            Integer newest = latest.get(session.id);
            if (newest == null) continue;
            String marker = db.knowledgeIndexedAt(session.id);
            int indexed = indexedThrough(marker);
            if (indexed >= newest) continue;
            indexSession(session.id, indexed);
        }
    }

    /** Last indexed sequence of a v2 marker; -1 (rebuild from scratch) for an empty or older marker. */
    static int indexedThrough(String marker) {
        if (marker == null || !marker.startsWith(MARKER_PREFIX)) return -1;
        try { return Integer.parseInt(marker.substring(MARKER_PREFIX.length())); } catch (NumberFormatException e) { return -1; }
    }

    /**
     * Indexes the rows after {@code after} (-1 = rebuild). Indexing stops before a row still streaming, so the
     * marker never passes an answer that is not finished; that row is picked up next time.
     */
    private void indexSession(String sessionId, int after) {
        String scope = chatScope(sessionId);
        JSONArray items = db.timelineAfter(sessionId, Math.max(0, after));
        SQLiteDatabase writable = db.getWritableDatabase();
        int through = Math.max(0, after);
        writable.beginTransaction();
        try {
            if (after < 0) writable.delete("knowledge_fts", "scope_id=?", new String[]{scope});
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                if (item.optBoolean("streaming", false)) break;
                int seq = item.optInt("localSeq", through + 1);
                indexMessage(writable, scope, sessionId + ":" + seq, seq, item);
                through = Math.max(through, seq);
            }
            writable.setTransactionSuccessful();
        } finally { writable.endTransaction(); }
        db.markKnowledgeIndexed(sessionId, MARKER_PREFIX + through);
    }

    private static void indexMessage(SQLiteDatabase writable, String scope, String itemRef, int ordinal, JSONObject item) {
        if (item == null || !"message".equals(item.optString("kind", "message")) || item.optBoolean("streaming", false)) return;
        String role = item.optString("role", "");
        if (!("user".equals(role) || "assistant".equals(role))) return;
        List<String> chunks = KnowledgeLexicon.chunks(item.optString("text", ""));
        for (int k = 0; k < chunks.size(); k++) {
            String chunk = chunks.get(k);
            String terms = KnowledgeLexicon.indexTerms(chunk);
            if (terms.isEmpty()) continue;
            ContentValues row = new ContentValues();
            row.put("scope_id", scope); row.put("item_ref", itemRef + CHUNK_SUFFIX + k); row.put("ordinal", String.valueOf(ordinal));
            row.put("role", role); row.put("text", chunk); row.put("terms", terms);
            writable.insert("knowledge_fts", null, row);
        }
    }

    static String snapshotScope(String snapshotId) { return "snapshot:" + snapshotId; }
    static String chatScope(String sessionId) { return "chat:" + (sessionId == null ? "" : sessionId); }
}
