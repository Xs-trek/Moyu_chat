package com.moyu.chat;

import org.json.JSONArray;

/**
 * The one place orchestration reads a session's history (spec H1, H2, R2). A session's history is the snapshot it
 * was branched from (which already holds the whole ancestor chain) followed by its own timeline rows. Context
 * assembly, compaction, recall indexing, snapshots and the Web follow-up all read it here; row operations (edit,
 * rewind, retry) and the display projection read the session's own rows directly. Rows are returned as stored:
 * callers keep only what they treat as visible.
 */
final class SessionHistory {
    private SessionHistory() { }

    static final class Parts {
        /** The origin snapshot's items, empty for a session that was not branched. */
        final JSONArray inherited;
        /** This session's own timeline rows. */
        final JSONArray local;
        Parts(JSONArray inherited, JSONArray local) { this.inherited = inherited; this.local = local; }
    }

    static Parts of(ChatDatabase db, ChatDatabase.Session session) {
        if (session == null) return new Parts(new JSONArray(), new JSONArray());
        return new Parts(inherited(db, session.originSnapshotId), db.timeline(session.id));
    }

    /** Inherited items, then local rows. */
    static JSONArray combined(ChatDatabase db, ChatDatabase.Session session) {
        Parts parts = of(db, session);
        JSONArray out = new JSONArray();
        for (int i = 0; i < parts.inherited.length(); i++) out.put(parts.inherited.opt(i));
        for (int i = 0; i < parts.local.length(); i++) out.put(parts.local.opt(i));
        return out;
    }

    /** The history up to and including local row {@code throughSeq} (null = all): what a new-session branch copies. */
    static JSONArray through(ChatDatabase db, ChatDatabase.Session session, Integer throughSeq) {
        JSONArray out = new JSONArray();
        if (session == null) return out;
        JSONArray inherited = inherited(db, session.originSnapshotId);
        for (int i = 0; i < inherited.length(); i++) out.put(inherited.opt(i));
        JSONArray local = db.timelineThrough(session.id, throughSeq);
        for (int i = 0; i < local.length(); i++) out.put(local.opt(i));
        return out;
    }

    /** A snapshot's items; empty when there is none. */
    static JSONArray inherited(ChatDatabase db, String snapshotId) {
        return snapshotId == null || snapshotId.trim().isEmpty() ? new JSONArray() : db.snapshotItems(snapshotId);
    }

    /** This session's own rows after {@code afterSeq}: incremental recall indexing. */
    static JSONArray localAfter(ChatDatabase db, String sessionId, int afterSeq) {
        return db.timelineAfter(sessionId, afterSeq);
    }
}
