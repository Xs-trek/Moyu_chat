package com.moyu.chat;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Chat-owned database. It never opens or attaches the Remote APK database. */
public final class ChatDatabase extends SQLiteOpenHelper {
    private static final String NAME = "moyu-chat.db";
    private static final int VERSION = 3;

    public static final class Session {
        public String id, title, providerId, model, state, updatedAt, draft, originSnapshotId, parentSessionId, preview;
    }

    public static final class Provider {
        public String id, displayName, protocol, baseUrl, model;
        public int contextTokens = ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        public int maxOutputTokens = ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
    }

    public static final class Snapshot {
        public String id, sourceType, sourceId, sourceTitle, sourceRuntime, createdAt, contentHash;
        public int itemCount, sourceItemCount;
        public boolean truncated;
    }

    public static final class Handoff {
        public String id, branchSessionId, state, summary, payload, createdAt;
    }

    ChatDatabase(Context context) { super(context, NAME, null, VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE providers(provider_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,protocol TEXT NOT NULL,base_url TEXT NOT NULL,model TEXT NOT NULL,context_tokens INTEGER NOT NULL DEFAULT 32000,max_output_tokens INTEGER NOT NULL DEFAULT 4096,updated_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,title TEXT NOT NULL,provider_id TEXT NOT NULL,model TEXT NOT NULL,state TEXT NOT NULL,updated_at TEXT NOT NULL,draft TEXT NOT NULL DEFAULT '',origin_snapshot_id TEXT,parent_session_id TEXT,preview TEXT)");
        db.execSQL("CREATE TABLE timeline(item_key TEXT PRIMARY KEY,session_id TEXT NOT NULL,local_seq INTEGER NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(session_id) REFERENCES sessions(local_id) ON DELETE CASCADE,UNIQUE(session_id,local_seq))");
        db.execSQL("CREATE INDEX timeline_session_seq ON timeline(session_id,local_seq)");
        db.execSQL("CREATE TABLE snapshots(snapshot_id TEXT PRIMARY KEY,source_type TEXT NOT NULL,source_id TEXT NOT NULL,source_title TEXT NOT NULL,source_runtime TEXT,created_at TEXT NOT NULL,item_count INTEGER NOT NULL,source_item_count INTEGER NOT NULL DEFAULT 0,truncated INTEGER NOT NULL DEFAULT 0,content_hash TEXT)");
        db.execSQL("CREATE TABLE snapshot_items(snapshot_id TEXT NOT NULL,ordinal INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(snapshot_id,ordinal),FOREIGN KEY(snapshot_id) REFERENCES snapshots(snapshot_id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE handoffs(handoff_id TEXT PRIMARY KEY,branch_session_id TEXT NOT NULL,state TEXT NOT NULL,summary TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(branch_session_id) REFERENCES sessions(local_id) ON DELETE CASCADE)");
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE providers ADD COLUMN context_tokens INTEGER NOT NULL DEFAULT 32000");
            db.execSQL("ALTER TABLE providers ADD COLUMN max_output_tokens INTEGER NOT NULL DEFAULT 4096");
            db.execSQL("ALTER TABLE sessions ADD COLUMN parent_session_id TEXT");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE snapshots ADD COLUMN source_item_count INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE snapshots ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE snapshots SET source_item_count=item_count WHERE source_item_count=0");
        }
    }

    public synchronized Session createSession(String providerId, String model, String title, String originSnapshotId) {
        return createSession(providerId, model, title, originSnapshotId, null);
    }

    public synchronized Session createSession(String providerId, String model, String title, String originSnapshotId, String parentSessionId) {
        Session s = new Session();
        s.id = UUID.randomUUID().toString();
        s.providerId = providerId;
        s.model = model;
        s.title = title == null || title.trim().isEmpty() ? "新 Chat" : title.trim();
        s.state = "idle";
        s.updatedAt = Instant.now().toString();
        s.draft = "";
        s.originSnapshotId = originSnapshotId;
        s.parentSessionId = parentSessionId;
        saveSession(s);
        return s;
    }

    public synchronized void saveSession(Session s) {
        ContentValues v = sessionValues(s);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("sessions", v, "local_id=?", new String[]{s.id});
        if (updated == 0) db.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_ABORT);
    }

    public synchronized Session getSession(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT local_id,title,provider_id,model,state,updated_at,draft,origin_snapshot_id,parent_session_id,preview FROM sessions WHERE local_id=?", new String[]{id})) {
            return c.moveToFirst() ? readSession(c) : null;
        }
    }

    public synchronized List<Session> listSessions() {
        ArrayList<Session> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT local_id,title,provider_id,model,state,updated_at,draft,origin_snapshot_id,parent_session_id,preview FROM sessions ORDER BY updated_at DESC", null)) {
            while (c.moveToNext()) out.add(readSession(c));
        }
        return out;
    }

    /** Process death cannot resume provider streams; remove stale streaming projections and mark those turns failed. */
    public synchronized void recoverInterruptedSessions() {
        SQLiteDatabase db = getWritableDatabase();
        ArrayList<String> running = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT local_id FROM sessions WHERE state='running'", null)) {
            while (c.moveToNext()) running.add(c.getString(0));
        }
        if (running.isEmpty()) return;
        db.beginTransaction();
        try {
            for (String sessionId : running) {
                ArrayList<String> streamingKeys = new ArrayList<>();
                try (Cursor c = db.rawQuery("SELECT item_key,body FROM timeline WHERE session_id=?", new String[]{sessionId})) {
                    while (c.moveToNext()) {
                        try {
                            JSONObject body = new JSONObject(c.getString(1));
                            if (body.optBoolean("streaming", false)) streamingKeys.add(c.getString(0));
                        } catch (Exception ignored) { }
                    }
                }
                for (String key : streamingKeys) db.delete("timeline", "session_id=? AND item_key=?", new String[]{sessionId, key});
                ContentValues state = new ContentValues(); state.put("state", "failed"); state.put("updated_at", Instant.now().toString());
                db.update("sessions", state, "local_id=?", new String[]{sessionId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void deleteSession(String id) { getWritableDatabase().delete("sessions", "local_id=?", new String[]{id}); }
    public synchronized void setDraft(String id, String draft) { ContentValues v = new ContentValues(); v.put("draft", draft == null ? "" : draft); getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id}); }

    public synchronized int appendTimeline(String sessionId, String itemKey, JSONObject body) {
        SQLiteDatabase db = getWritableDatabase();
        int seq;
        try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(local_seq),0)+1 FROM timeline WHERE session_id=?", new String[]{sessionId})) {
            c.moveToFirst(); seq = c.getInt(0);
        } catch (Exception error) { throw new IllegalStateException(error); }
        try { body.put("localSeq", seq); } catch (Exception ignored) { }
        ContentValues v = new ContentValues();
        v.put("item_key", itemKey); v.put("session_id", sessionId); v.put("local_seq", seq); v.put("body", body.toString());
        v.put("created_at", body.optString("createdAt", Instant.now().toString()));
        db.insertWithOnConflict("timeline", null, v, SQLiteDatabase.CONFLICT_ABORT);
        updatePreviewFromBody(db, sessionId, body);
        return seq;
    }

    public synchronized void replaceTimelineBody(String itemKey, JSONObject body) {
        SQLiteDatabase db = getWritableDatabase();
        String sessionId = null;
        try (Cursor c = db.rawQuery("SELECT session_id FROM timeline WHERE item_key=?", new String[]{itemKey})) {
            if (c.moveToFirst()) sessionId = c.getString(0);
        }
        ContentValues v = new ContentValues(); v.put("body", body.toString());
        if (db.update("timeline", v, "item_key=?", new String[]{itemKey}) == 1 && sessionId != null) updatePreviewFromBody(db, sessionId, body);
    }

    public synchronized void deleteTimelineItem(String itemKey) { getWritableDatabase().delete("timeline", "item_key=?", new String[]{itemKey}); }
    public synchronized JSONArray timeline(String sessionId) { return timelineThrough(sessionId, null); }

    public synchronized JSONArray timelineThrough(String sessionId, Integer throughSeq) {
        JSONArray out = new JSONArray();
        if (sessionId == null) return out;
        String sql = throughSeq == null
                ? "SELECT body FROM timeline WHERE session_id=? ORDER BY local_seq"
                : "SELECT body FROM timeline WHERE session_id=? AND local_seq<=? ORDER BY local_seq";
        String[] args = throughSeq == null ? new String[]{sessionId} : new String[]{sessionId, String.valueOf(throughSeq)};
        try (Cursor c = getReadableDatabase().rawQuery(sql, args)) {
            while (c.moveToNext()) try { out.put(new JSONObject(c.getString(0))); } catch (Exception ignored) { }
        }
        return out;
    }

    public synchronized void saveProvider(Provider p) {
        ContentValues v = providerValues(p);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("providers", v, "provider_id=?", new String[]{p.id});
        if (updated == 0) db.insertWithOnConflict("providers", null, v, SQLiteDatabase.CONFLICT_ABORT);
    }

    public synchronized Provider getProvider(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT provider_id,display_name,protocol,base_url,model,context_tokens,max_output_tokens FROM providers WHERE provider_id=?", new String[]{id})) {
            return c.moveToFirst() ? readProvider(c) : null;
        }
    }

    public synchronized List<Provider> listProviders() {
        ArrayList<Provider> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT provider_id,display_name,protocol,base_url,model,context_tokens,max_output_tokens FROM providers ORDER BY display_name", null)) {
            while (c.moveToNext()) out.add(readProvider(c));
        }
        return out;
    }

    public synchronized void saveSnapshot(Snapshot snapshot, JSONArray items) {
        if (snapshot == null || snapshot.id == null || items == null) throw new IllegalArgumentException("snapshot required");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues s = new ContentValues();
            s.put("snapshot_id", snapshot.id); s.put("source_type", snapshot.sourceType); s.put("source_id", snapshot.sourceId);
            s.put("source_title", snapshot.sourceTitle); s.put("source_runtime", snapshot.sourceRuntime); s.put("created_at", snapshot.createdAt);
            s.put("item_count", items.length()); s.put("source_item_count", Math.max(items.length(), snapshot.sourceItemCount)); s.put("truncated", snapshot.truncated ? 1 : 0); s.put("content_hash", snapshot.contentHash);
            db.insertWithOnConflict("snapshots", null, s, SQLiteDatabase.CONFLICT_ABORT);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i); if (item == null) continue;
                ContentValues row = new ContentValues(); row.put("snapshot_id", snapshot.id); row.put("ordinal", i); row.put("body", item.toString());
                db.insertWithOnConflict("snapshot_items", null, row, SQLiteDatabase.CONFLICT_ABORT);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized Snapshot getSnapshot(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT snapshot_id,source_type,source_id,source_title,source_runtime,created_at,item_count,source_item_count,truncated,content_hash FROM snapshots WHERE snapshot_id=?", new String[]{id})) {
            if (!c.moveToFirst()) return null;
            Snapshot s = new Snapshot(); s.id = c.getString(0); s.sourceType = c.getString(1); s.sourceId = c.getString(2); s.sourceTitle = c.getString(3);
            s.sourceRuntime = c.isNull(4) ? null : c.getString(4); s.createdAt = c.getString(5); s.itemCount = c.getInt(6); s.sourceItemCount = c.getInt(7); s.truncated = c.getInt(8) != 0; s.contentHash = c.isNull(9) ? null : c.getString(9); return s;
        }
    }

    public synchronized JSONArray snapshotItems(String id) {
        JSONArray out = new JSONArray();
        if (id == null) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM snapshot_items WHERE snapshot_id=? ORDER BY ordinal", new String[]{id})) {
            while (c.moveToNext()) try { out.put(new JSONObject(c.getString(0))); } catch (Exception ignored) { }
        }
        return out;
    }

    public synchronized void saveHandoff(Handoff h) {
        ContentValues v = handoffValues(h);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("handoffs", v, "handoff_id=?", new String[]{h.id});
        if (updated == 0) db.insertWithOnConflict("handoffs", null, v, SQLiteDatabase.CONFLICT_ABORT);
    }

    public synchronized Handoff getHandoff(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT handoff_id,branch_session_id,state,summary,payload,created_at FROM handoffs WHERE handoff_id=?", new String[]{id})) {
            if (!c.moveToFirst()) return null; Handoff h = new Handoff(); h.id = c.getString(0); h.branchSessionId = c.getString(1); h.state = c.getString(2); h.summary = c.getString(3); h.payload = c.getString(4); h.createdAt = c.getString(5); return h;
        }
    }

    public synchronized List<Handoff> listHandoffs() {
        ArrayList<Handoff> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT handoff_id,branch_session_id,state,summary,payload,created_at FROM handoffs ORDER BY created_at DESC", null)) {
            while (c.moveToNext()) { Handoff h = new Handoff(); h.id = c.getString(0); h.branchSessionId = c.getString(1); h.state = c.getString(2); h.summary = c.getString(3); h.payload = c.getString(4); h.createdAt = c.getString(5); out.add(h); }
        }
        return out;
    }

    public synchronized void putSetting(String key, String value) { ContentValues v = new ContentValues(); v.put("key", key); v.put("value", value); getWritableDatabase().insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE); }
    public synchronized String setting(String key, String fallback) { try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) { return c.moveToFirst() ? c.getString(0) : fallback; } }

    private static ContentValues sessionValues(Session s) {
        ContentValues v = new ContentValues();
        v.put("local_id", s.id); v.put("title", s.title); v.put("provider_id", s.providerId); v.put("model", s.model);
        v.put("state", s.state); v.put("updated_at", s.updatedAt); v.put("draft", s.draft == null ? "" : s.draft);
        v.put("origin_snapshot_id", s.originSnapshotId); v.put("parent_session_id", s.parentSessionId); v.put("preview", s.preview);
        return v;
    }

    private static ContentValues providerValues(Provider p) {
        ContentValues v = new ContentValues();
        v.put("provider_id", p.id); v.put("display_name", p.displayName); v.put("protocol", p.protocol); v.put("base_url", p.baseUrl); v.put("model", p.model);
        v.put("context_tokens", p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS);
        v.put("max_output_tokens", p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS);
        v.put("updated_at", Instant.now().toString());
        return v;
    }

    private static ContentValues handoffValues(Handoff h) {
        ContentValues v = new ContentValues(); v.put("handoff_id", h.id); v.put("branch_session_id", h.branchSessionId); v.put("state", h.state);
        v.put("summary", h.summary); v.put("payload", h.payload); v.put("created_at", h.createdAt); return v;
    }

    private static void updatePreviewFromBody(SQLiteDatabase db, String sessionId, JSONObject body) {
        if (!"message".equals(body.optString("kind")) || body.optBoolean("streaming", false)) return;
        String role = body.optString("role", ""); if (!("user".equals(role) || "assistant".equals(role))) return;
        String text = body.optString("text", "").trim(); if (text.isEmpty()) return;
        String preview = text.length() > 160 ? text.substring(0, 160) : text;
        ContentValues s = new ContentValues(); s.put("preview", preview); s.put("updated_at", Instant.now().toString());
        db.update("sessions", s, "local_id=?", new String[]{sessionId});
    }

    private static Session readSession(Cursor c) { Session s = new Session(); s.id = c.getString(0); s.title = c.getString(1); s.providerId = c.getString(2); s.model = c.getString(3); s.state = c.getString(4); s.updatedAt = c.getString(5); s.draft = c.getString(6); s.originSnapshotId = c.isNull(7) ? null : c.getString(7); s.parentSessionId = c.isNull(8) ? null : c.getString(8); s.preview = c.isNull(9) ? null : c.getString(9); return s; }
    private static Provider readProvider(Cursor c) { Provider p = new Provider(); p.id = c.getString(0); p.displayName = c.getString(1); p.protocol = c.getString(2); p.baseUrl = c.getString(3); p.model = c.getString(4); p.contextTokens = c.getInt(5); p.maxOutputTokens = c.getInt(6); return p; }
}
