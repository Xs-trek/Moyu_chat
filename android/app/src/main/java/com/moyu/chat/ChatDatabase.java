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
    private static final int VERSION = 9;

    public static final class Session {
        public String id, title, providerId, modelProfileId, model, reasoningEffort, inputModalities, outputModalities, state, updatedAt, draft, draftMedia, originSnapshotId, parentSessionId, preview;
        /** v0.0.2 session-scoped context policy plus provider-neutral derived control state. */
        public String contextMode, taskProfile, contextCheckpoint, toolCapabilities, webMode;
        public int checkpointCoveredItems;
        public int contextTokens, inputTokens, maxOutputTokens;
    }

    /** Endpoint/deployment configuration. Legacy model/budget fields remain only for v4 migration and one-step provider creation. */
    public static final class Provider {
        public String id, displayName, baseUrl, model;
        public String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        public String authMode = ProviderAuth.BEARER;
        public String reasoningWire = ReasoningPolicy.WIRE_NONE;
        public int contextTokens = ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        public int inputTokens = 0;
        public int maxOutputTokens = ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
    }

    /** Model capabilities are scoped to one concrete provider deployment, not a global model name. */
    public static final class ModelProfile {
        public String id, providerId, modelId, displayName, reasoningLevels, defaultReasoning;
        public String inputModalities = "text", outputModalities = "text", toolCapabilities = "";
        public int contextTokens, inputTokens, maxOutputTokens;
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
        db.execSQL("CREATE TABLE providers(provider_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,protocol TEXT NOT NULL,base_url TEXT NOT NULL,auth_mode TEXT NOT NULL DEFAULT 'bearer',reasoning_wire TEXT NOT NULL DEFAULT 'none',model TEXT NOT NULL,context_tokens INTEGER NOT NULL DEFAULT 32000,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL DEFAULT 4096,updated_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE model_profiles(profile_id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,model_id TEXT NOT NULL,display_name TEXT NOT NULL,context_tokens INTEGER NOT NULL DEFAULT 32000,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL DEFAULT 4096,reasoning_levels TEXT NOT NULL DEFAULT '',default_reasoning TEXT NOT NULL DEFAULT 'default',input_modalities TEXT NOT NULL DEFAULT 'text',output_modalities TEXT NOT NULL DEFAULT 'text',tool_capabilities TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL,FOREIGN KEY(provider_id) REFERENCES providers(provider_id) ON DELETE CASCADE,UNIQUE(provider_id,model_id))");
        db.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,title TEXT NOT NULL,provider_id TEXT NOT NULL,model_profile_id TEXT,model TEXT NOT NULL,context_tokens INTEGER NOT NULL DEFAULT 32000,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL DEFAULT 4096,reasoning_effort TEXT NOT NULL DEFAULT 'default',input_modalities TEXT NOT NULL DEFAULT 'text',output_modalities TEXT NOT NULL DEFAULT 'text',tool_capabilities TEXT NOT NULL DEFAULT '',web_mode TEXT NOT NULL DEFAULT 'auto',state TEXT NOT NULL,updated_at TEXT NOT NULL,draft TEXT NOT NULL DEFAULT '',draft_media TEXT NOT NULL DEFAULT '[]',origin_snapshot_id TEXT,parent_session_id TEXT,preview TEXT,context_mode TEXT NOT NULL DEFAULT 'auto',task_profile TEXT NOT NULL DEFAULT '',context_checkpoint TEXT NOT NULL DEFAULT '',checkpoint_covered_items INTEGER NOT NULL DEFAULT 0)");
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
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE providers ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'bearer'");
            db.execSQL("UPDATE providers SET auth_mode='x-api-key' WHERE protocol='anthropic-messages'");
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE providers ADD COLUMN reasoning_wire TEXT NOT NULL DEFAULT 'none'");
            db.execSQL("ALTER TABLE providers ADD COLUMN input_tokens INTEGER NOT NULL DEFAULT 0");
            db.execSQL("UPDATE providers SET reasoning_wire='openai-responses' WHERE protocol='openai-responses'");
            db.execSQL("UPDATE providers SET reasoning_wire='anthropic-effort' WHERE protocol='anthropic-messages'");
            db.execSQL("CREATE TABLE model_profiles(profile_id TEXT PRIMARY KEY,provider_id TEXT NOT NULL,model_id TEXT NOT NULL,display_name TEXT NOT NULL,context_tokens INTEGER NOT NULL DEFAULT 32000,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL DEFAULT 4096,reasoning_levels TEXT NOT NULL DEFAULT '',default_reasoning TEXT NOT NULL DEFAULT 'default',updated_at TEXT NOT NULL,FOREIGN KEY(provider_id) REFERENCES providers(provider_id) ON DELETE CASCADE,UNIQUE(provider_id,model_id))");
            db.execSQL("INSERT INTO model_profiles(profile_id,provider_id,model_id,display_name,context_tokens,input_tokens,max_output_tokens,reasoning_levels,default_reasoning,updated_at) SELECT provider_id || ':default',provider_id,model,model,context_tokens,input_tokens,max_output_tokens,'','default',updated_at FROM providers WHERE model<>''");
            db.execSQL("ALTER TABLE sessions ADD COLUMN model_profile_id TEXT");
            db.execSQL("ALTER TABLE sessions ADD COLUMN context_tokens INTEGER NOT NULL DEFAULT 32000");
            db.execSQL("ALTER TABLE sessions ADD COLUMN input_tokens INTEGER NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE sessions ADD COLUMN max_output_tokens INTEGER NOT NULL DEFAULT 4096");
            db.execSQL("ALTER TABLE sessions ADD COLUMN reasoning_effort TEXT NOT NULL DEFAULT 'default'");
            db.execSQL("UPDATE sessions SET model_profile_id=provider_id || ':default' WHERE model=(SELECT model FROM providers WHERE providers.provider_id=sessions.provider_id)");
            db.execSQL("UPDATE sessions SET context_tokens=COALESCE((SELECT context_tokens FROM providers WHERE providers.provider_id=sessions.provider_id),32000), input_tokens=COALESCE((SELECT input_tokens FROM providers WHERE providers.provider_id=sessions.provider_id),0), max_output_tokens=COALESCE((SELECT max_output_tokens FROM providers WHERE providers.provider_id=sessions.provider_id),4096)");
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE model_profiles ADD COLUMN input_modalities TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE model_profiles ADD COLUMN output_modalities TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE sessions ADD COLUMN input_modalities TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE sessions ADD COLUMN output_modalities TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE sessions ADD COLUMN draft_media TEXT NOT NULL DEFAULT '[]'");
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN task_profile TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE sessions ADD COLUMN context_checkpoint TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE sessions ADD COLUMN checkpoint_covered_items INTEGER NOT NULL DEFAULT 0");
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN context_mode TEXT NOT NULL DEFAULT 'auto'");
        }
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE model_profiles ADD COLUMN tool_capabilities TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE sessions ADD COLUMN tool_capabilities TEXT NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE sessions ADD COLUMN web_mode TEXT NOT NULL DEFAULT 'auto'");
            db.execSQL("UPDATE sessions SET tool_capabilities=COALESCE((SELECT tool_capabilities FROM model_profiles WHERE profile_id=sessions.model_profile_id),'')");
        }
    }

    public synchronized Session createSession(String providerId, String model, String title, String originSnapshotId) {
        return createSession(providerId, model, title, originSnapshotId, null);
    }

    /** Compatibility entry point: resolve the deployment-scoped model profile, creating one from legacy provider defaults when needed. */
    public synchronized Session createSession(String providerId, String model, String title, String originSnapshotId, String parentSessionId) {
        ModelProfile profile = findModelProfile(providerId, model);
        if (profile == null) {
            Provider provider = getProvider(providerId);
            if (provider == null) throw new IllegalArgumentException("provider not found");
            profile = legacyProfile(provider, model == null || model.trim().isEmpty() ? provider.model : model.trim());
            saveModelProfile(profile);
        }
        return createSession(profile, title, originSnapshotId, parentSessionId, profile.defaultReasoning);
    }

    public synchronized Session createSession(ModelProfile profile, String title, String originSnapshotId,
                                              String parentSessionId, String reasoningEffort) {
        return createSession(profile, title, originSnapshotId, parentSessionId, reasoningEffort, ContextMode.AUTO, ChatTurnOptions.WEB_AUTO);
    }

    public synchronized Session createSession(ModelProfile profile, String title, String originSnapshotId,
                                              String parentSessionId, String reasoningEffort, String contextMode) {
        return createSession(profile, title, originSnapshotId, parentSessionId, reasoningEffort, contextMode, ChatTurnOptions.WEB_AUTO);
    }

    public synchronized Session createSession(ModelProfile profile, String title, String originSnapshotId,
                                              String parentSessionId, String reasoningEffort, String contextMode, String webMode) {
        if (profile == null) throw new IllegalArgumentException("model profile required");
        Session s = new Session();
        s.id = UUID.randomUUID().toString();
        s.providerId = profile.providerId;
        s.modelProfileId = profile.id;
        s.model = profile.modelId;
        s.contextTokens = profile.contextTokens;
        s.inputTokens = profile.inputTokens;
        s.maxOutputTokens = profile.maxOutputTokens;
        s.inputModalities = normalizeModalities(profile.inputModalities); s.outputModalities = normalizeModalities(profile.outputModalities);
        s.toolCapabilities = ChatTurnOptions.normalizeCapabilities(profile.toolCapabilities);
        s.webMode = ChatTurnOptions.normalizeWebMode(webMode);
        s.reasoningEffort = ReasoningPolicy.normalizeEffort(reasoningEffort == null ? profile.defaultReasoning : reasoningEffort);
        if (!ReasoningPolicy.allows(profile.reasoningLevels, s.reasoningEffort)) throw new IllegalArgumentException("reasoning effort not supported by model profile");
        s.title = title == null || title.trim().isEmpty() ? "新 Chat" : title.trim();
        s.state = "idle";
        s.updatedAt = Instant.now().toString();
        s.draft = "";
        s.draftMedia = "[]";
        s.originSnapshotId = originSnapshotId;
        s.parentSessionId = parentSessionId;
        s.contextMode = ContextMode.normalize(contextMode);
        s.taskProfile = "";
        s.contextCheckpoint = "";
        s.checkpointCoveredItems = 0;
        saveSession(s);
        return s;
    }

    /** Branches preserve the source session's resolved model limits and effort even if the profile is edited later. */
    public synchronized Session branchSession(Session source, String title, String originSnapshotId, String parentSessionId) {
        if (source == null) throw new IllegalArgumentException("source session required");
        Session s = new Session();
        s.id = UUID.randomUUID().toString(); s.providerId = source.providerId; s.modelProfileId = source.modelProfileId; s.model = source.model;
        s.contextTokens = source.contextTokens; s.inputTokens = source.inputTokens; s.maxOutputTokens = source.maxOutputTokens;
        s.inputModalities = normalizeModalities(source.inputModalities); s.outputModalities = normalizeModalities(source.outputModalities);
        s.toolCapabilities = ChatTurnOptions.normalizeCapabilities(source.toolCapabilities); s.webMode = ChatTurnOptions.normalizeWebMode(source.webMode);
        s.reasoningEffort = source.reasoningEffort == null ? ReasoningPolicy.DEFAULT : source.reasoningEffort;
        s.title = title == null || title.trim().isEmpty() ? "新 Chat" : title.trim(); s.state = "idle"; s.updatedAt = Instant.now().toString(); s.draft = ""; s.draftMedia = "[]";
        s.originSnapshotId = originSnapshotId; s.parentSessionId = parentSessionId;
        s.contextMode = ContextMode.normalize(source.contextMode);
        s.taskProfile = source.taskProfile == null ? "" : source.taskProfile;
        s.contextCheckpoint = ""; s.checkpointCoveredItems = 0;
        saveSession(s); return s;
    }

    public synchronized void saveSession(Session s) {
        ContentValues v = sessionValues(s);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("sessions", v, "local_id=?", new String[]{s.id});
        if (updated == 0) db.insertWithOnConflict("sessions", null, v, SQLiteDatabase.CONFLICT_ABORT);
    }

    public synchronized Session getSession(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT local_id,title,provider_id,model_profile_id,model,context_tokens,input_tokens,max_output_tokens,reasoning_effort,input_modalities,output_modalities,tool_capabilities,web_mode,state,updated_at,draft,draft_media,origin_snapshot_id,parent_session_id,preview,context_mode,task_profile,context_checkpoint,checkpoint_covered_items FROM sessions WHERE local_id=?", new String[]{id})) {
            return c.moveToFirst() ? readSession(c) : null;
        }
    }

    public synchronized List<Session> listSessions() {
        ArrayList<Session> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT local_id,title,provider_id,model_profile_id,model,context_tokens,input_tokens,max_output_tokens,reasoning_effort,input_modalities,output_modalities,tool_capabilities,web_mode,state,updated_at,draft,draft_media,origin_snapshot_id,parent_session_id,preview,context_mode,task_profile,context_checkpoint,checkpoint_covered_items FROM sessions ORDER BY updated_at DESC", null)) {
            while (c.moveToNext()) out.add(readSession(c));
        }
        return out;
    }

    /** Cold-start recovery: no provider turn can survive process death, so every durable streaming row is stale. */
    public synchronized void recoverInterruptedSessions() {
        SQLiteDatabase db = getWritableDatabase();
        java.util.LinkedHashSet<String> affected = new java.util.LinkedHashSet<>();
        try (Cursor c = db.rawQuery("SELECT local_id FROM sessions WHERE state='running'", null)) {
            while (c.moveToNext()) affected.add(c.getString(0));
        }
        ArrayList<String> staleKeys = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT item_key,session_id,body FROM timeline", null)) {
            while (c.moveToNext()) {
                try {
                    JSONObject body = new JSONObject(c.getString(2));
                    if (body.optBoolean("streaming", false)) { staleKeys.add(c.getString(0)); affected.add(c.getString(1)); }
                } catch (Exception ignored) { }
            }
        }
        if (affected.isEmpty() && staleKeys.isEmpty()) return;
        db.beginTransaction();
        try {
            for (String key : staleKeys) db.delete("timeline", "item_key=?", new String[]{key});
            String now = Instant.now().toString();
            for (String sessionId : affected) {
                ContentValues state = new ContentValues(); state.put("state", "failed"); state.put("updated_at", now);
                db.update("sessions", state, "local_id=?", new String[]{sessionId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Deletes one local session and its now-unreferenced origin snapshot; timeline/handoffs cascade by FK. */
    public synchronized void deleteSession(String id) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("session id required");
        SQLiteDatabase db = getWritableDatabase();
        String originSnapshotId = null;
        try (Cursor c = db.rawQuery("SELECT origin_snapshot_id FROM sessions WHERE local_id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("session not found");
            if (!c.isNull(0)) originSnapshotId = c.getString(0);
        }
        db.beginTransaction();
        try {
            if (db.delete("sessions", "local_id=?", new String[]{id}) != 1) throw new IllegalStateException("session delete failed");
            if (originSnapshotId != null) {
                boolean referenced;
                try (Cursor c = db.rawQuery("SELECT 1 FROM sessions WHERE origin_snapshot_id=? LIMIT 1", new String[]{originSnapshotId})) {
                    referenced = c.moveToFirst();
                }
                if (!referenced) db.delete("snapshots", "snapshot_id=?", new String[]{originSnapshotId});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Media GC authority: every durable JSON location that can legitimately retain a media descriptor. */
    public synchronized java.util.Set<String> referencedMediaIds() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        collectJsonColumn(getReadableDatabase(), "SELECT draft_media FROM sessions", out);
        collectJsonColumn(getReadableDatabase(), "SELECT body FROM timeline", out);
        collectJsonColumn(getReadableDatabase(), "SELECT body FROM snapshot_items", out);
        collectJsonColumn(getReadableDatabase(), "SELECT payload FROM handoffs", out);
        return out;
    }

    private static void collectJsonColumn(SQLiteDatabase db, String sql, java.util.Set<String> out) {
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String raw = c.getString(0); if (raw == null || raw.trim().isEmpty()) continue;
                try {
                    String trimmed = raw.trim();
                    Object value = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONObject(trimmed);
                    collectMediaIds(value, out);
                } catch (Exception ignored) { }
            }
        }
    }

    private static void collectMediaIds(Object value, java.util.Set<String> out) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String mediaId = object.optString("mediaId", "").trim(); if (!mediaId.isEmpty()) out.add(mediaId);
            JSONArray names = object.names(); if (names == null) return;
            for (int i = 0; i < names.length(); i++) collectMediaIds(object.opt(names.optString(i)), out);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) collectMediaIds(array.opt(i), out);
        }
    }

    public synchronized void setDraft(String id, String draft) { ContentValues v = new ContentValues(); v.put("draft", draft == null ? "" : draft); getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id}); }

    public synchronized JSONArray draftMedia(String id) {
        Session s = getSession(id); if (s == null || s.draftMedia == null || s.draftMedia.trim().isEmpty()) return new JSONArray();
        try { return new JSONArray(s.draftMedia); } catch (Exception ignored) { return new JSONArray(); }
    }

    public synchronized void addDraftMedia(String id, JSONObject descriptor) {
        if (descriptor == null || descriptor.optString("mediaId", "").trim().isEmpty()) throw new IllegalArgumentException("image descriptor required");
        JSONArray items = draftMedia(id);
        if (items.length() >= ChatMediaStore.MAX_DRAFT_IMAGES) throw new IllegalArgumentException("at most " + ChatMediaStore.MAX_DRAFT_IMAGES + " images per message");
        items.put(descriptor);
        ContentValues v = new ContentValues(); v.put("draft_media", items.toString()); getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id});
    }

    public synchronized void removeDraftMedia(String id, String mediaId) {
        JSONArray source = draftMedia(id), out = new JSONArray();
        for (int i = 0; i < source.length(); i++) { JSONObject item = source.optJSONObject(i); if (item != null && !mediaId.equals(item.optString("mediaId"))) out.put(item); }
        ContentValues v = new ContentValues(); v.put("draft_media", out.toString()); getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id});
    }

    public synchronized void clearDraftMedia(String id) { ContentValues v = new ContentValues(); v.put("draft_media", "[]"); getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id}); }

    /** Atomically commits the user's turn and one unique live assistant projection before network streaming starts. */
    public synchronized void beginTurn(String sessionId, String userKey, JSONObject userBody, String streamKey, JSONObject liveBody) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int seq;
            try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(local_seq),0)+1 FROM timeline WHERE session_id=?", new String[]{sessionId})) {
                if (!c.moveToFirst()) throw new IllegalStateException("cannot allocate user timeline sequence");
                seq = c.getInt(0);
            }
            userBody.put("localSeq", seq);
            ContentValues user = new ContentValues(); user.put("item_key", userKey); user.put("session_id", sessionId); user.put("local_seq", seq);
            user.put("body", userBody.toString()); user.put("created_at", userBody.optString("createdAt", Instant.now().toString()));
            db.insertWithOnConflict("timeline", null, user, SQLiteDatabase.CONFLICT_ABORT);
            updatePreviewFromBody(db, sessionId, userBody);

            liveBody.put("localSeq", seq + 1);
            ContentValues live = new ContentValues(); live.put("item_key", streamKey); live.put("session_id", sessionId); live.put("local_seq", seq + 1);
            live.put("body", liveBody.toString()); live.put("created_at", liveBody.optString("createdAt", Instant.now().toString()));
            db.insertWithOnConflict("timeline", null, live, SQLiteDatabase.CONFLICT_ABORT);

            ContentValues state = new ContentValues(); state.put("state", "running"); state.put("updated_at", Instant.now().toString());
            state.put("draft", ""); state.put("draft_media", "[]");
            if (db.update("sessions", state, "local_id=?", new String[]{sessionId}) != 1) throw new IllegalStateException("session missing while starting turn");
            db.setTransactionSuccessful();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        } finally { db.endTransaction(); }
    }

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

    /** Atomically settles the durable live projection and session state for one provider turn. */
    public synchronized void finalizeTurn(String sessionId, String streamKey, JSONObject finalBody,
                                          String errorCode, String errorSummary, String finalState) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (finalBody == null) {
                db.delete("timeline", "session_id=? AND item_key=?", new String[]{sessionId, streamKey});
            } else {
                ContentValues row = new ContentValues(); row.put("body", finalBody.toString());
                if (db.update("timeline", row, "session_id=? AND item_key=?", new String[]{sessionId, streamKey}) != 1) {
                    throw new IllegalStateException("live assistant row missing during turn finalization");
                }
                updatePreviewFromBody(db, sessionId, finalBody);
            }
            if (errorCode != null && !"cancelled".equals(errorCode)) {
                int seq;
                try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(local_seq),0)+1 FROM timeline WHERE session_id=?", new String[]{sessionId})) {
                    if (!c.moveToFirst()) throw new IllegalStateException("cannot allocate error timeline sequence");
                    seq = c.getInt(0);
                }
                JSONObject error = new JSONObject().put("kind", "error").put("code", errorCode)
                        .put("summary", errorSummary == null ? "Provider error" : errorSummary)
                        .put("createdAt", Instant.now().toString()).put("localSeq", seq);
                ContentValues row = new ContentValues(); row.put("item_key", "error:" + UUID.randomUUID()); row.put("session_id", sessionId);
                row.put("local_seq", seq); row.put("body", error.toString()); row.put("created_at", error.optString("createdAt"));
                db.insertWithOnConflict("timeline", null, row, SQLiteDatabase.CONFLICT_ABORT);
            }
            ContentValues state = new ContentValues(); state.put("state", finalState); state.put("updated_at", Instant.now().toString());
            if (db.update("sessions", state, "local_id=?", new String[]{sessionId}) != 1) throw new IllegalStateException("session missing during turn finalization");
            db.setTransactionSuccessful();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        } finally { db.endTransaction(); }
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
        if (listModelProfiles(p.id).isEmpty() && p.model != null && !p.model.trim().isEmpty()) saveModelProfile(legacyProfile(p, p.model.trim()));
    }

    public synchronized Provider getProvider(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT provider_id,display_name,protocol,base_url,auth_mode,reasoning_wire,model,context_tokens,input_tokens,max_output_tokens FROM providers WHERE provider_id=?", new String[]{id})) {
            return c.moveToFirst() ? readProvider(c) : null;
        }
    }

    public synchronized List<Provider> listProviders() {
        ArrayList<Provider> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT provider_id,display_name,protocol,base_url,auth_mode,reasoning_wire,model,context_tokens,input_tokens,max_output_tokens FROM providers ORDER BY display_name", null)) {
            while (c.moveToNext()) out.add(readProvider(c));
        }
        return out;
    }

    public synchronized void saveModelProfile(ModelProfile profile) {
        if (profile == null) throw new IllegalArgumentException("model profile required");
        ContentValues v = modelProfileValues(profile);
        SQLiteDatabase db = getWritableDatabase();
        int updated = db.update("model_profiles", v, "profile_id=?", new String[]{profile.id});
        if (updated == 0) db.insertWithOnConflict("model_profiles", null, v, SQLiteDatabase.CONFLICT_ABORT);
    }

    public synchronized ModelProfile getModelProfile(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT profile_id,provider_id,model_id,display_name,context_tokens,input_tokens,max_output_tokens,reasoning_levels,default_reasoning,input_modalities,output_modalities FROM model_profiles WHERE profile_id=?", new String[]{id})) {
            return c.moveToFirst() ? readModelProfile(c) : null;
        }
    }

    public synchronized ModelProfile findModelProfile(String providerId, String modelId) {
        if (providerId == null || modelId == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT profile_id,provider_id,model_id,display_name,context_tokens,input_tokens,max_output_tokens,reasoning_levels,default_reasoning,input_modalities,output_modalities FROM model_profiles WHERE provider_id=? AND model_id=?", new String[]{providerId, modelId})) {
            return c.moveToFirst() ? readModelProfile(c) : null;
        }
    }

    public synchronized List<ModelProfile> listModelProfiles(String providerId) {
        ArrayList<ModelProfile> out = new ArrayList<>();
        if (providerId == null) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT profile_id,provider_id,model_id,display_name,context_tokens,input_tokens,max_output_tokens,reasoning_levels,default_reasoning,input_modalities,output_modalities FROM model_profiles WHERE provider_id=? ORDER BY display_name,model_id", new String[]{providerId})) {
            while (c.moveToNext()) out.add(readModelProfile(c));
        }
        return out;
    }

    public synchronized ModelProfile firstModelProfile(String providerId) {
        List<ModelProfile> profiles = listModelProfiles(providerId);
        return profiles.isEmpty() ? null : profiles.get(0);
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
        v.put("local_id", s.id); v.put("title", s.title); v.put("provider_id", s.providerId); v.put("model_profile_id", s.modelProfileId); v.put("model", s.model);
        v.put("context_tokens", s.contextTokens > 0 ? s.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS);
        v.put("input_tokens", Math.max(0, s.inputTokens));
        v.put("max_output_tokens", s.maxOutputTokens > 0 ? s.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS);
        v.put("reasoning_effort", ReasoningPolicy.normalizeEffort(s.reasoningEffort));
        v.put("input_modalities", normalizeModalities(s.inputModalities)); v.put("output_modalities", normalizeModalities(s.outputModalities));
        v.put("tool_capabilities", ChatTurnOptions.normalizeCapabilities(s.toolCapabilities));
        v.put("web_mode", ChatTurnOptions.normalizeWebMode(s.webMode));
        v.put("state", s.state); v.put("updated_at", s.updatedAt); v.put("draft", s.draft == null ? "" : s.draft);
        v.put("draft_media", s.draftMedia == null || s.draftMedia.trim().isEmpty() ? "[]" : s.draftMedia);
        v.put("origin_snapshot_id", s.originSnapshotId); v.put("parent_session_id", s.parentSessionId); v.put("preview", s.preview);
        v.put("context_mode", ContextMode.normalize(s.contextMode));
        v.put("task_profile", s.taskProfile == null ? "" : s.taskProfile);
        v.put("context_checkpoint", s.contextCheckpoint == null ? "" : s.contextCheckpoint);
        v.put("checkpoint_covered_items", Math.max(0, s.checkpointCoveredItems));
        return v;
    }

    private static ContentValues providerValues(Provider p) {
        ContentValues v = new ContentValues();
        v.put("provider_id", p.id); v.put("display_name", p.displayName); v.put("protocol", p.protocol); v.put("base_url", p.baseUrl);
        v.put("auth_mode", ProviderAuth.normalize(p.authMode, p.protocol));
        v.put("reasoning_wire", ReasoningPolicy.normalizeWire(p.reasoningWire, p.protocol));
        v.put("model", p.model == null ? "" : p.model);
        v.put("context_tokens", p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS);
        v.put("input_tokens", Math.max(0, p.inputTokens));
        v.put("max_output_tokens", p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS);
        v.put("updated_at", Instant.now().toString());
        return v;
    }

    private static ContentValues modelProfileValues(ModelProfile p) {
        if (p.id == null || p.id.trim().isEmpty() || p.providerId == null || p.providerId.trim().isEmpty()) throw new IllegalArgumentException("model profile id/provider required");
        String modelId = p.modelId == null ? "" : p.modelId.trim(); if (modelId.isEmpty()) throw new IllegalArgumentException("model id required");
        int context = p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        int input = Math.max(0, p.inputTokens);
        int output = p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
        if (context < 2_048 || context > 4_000_000) throw new IllegalArgumentException("context tokens out of range");
        if (input > context) throw new IllegalArgumentException("input tokens out of range");
        if (output < 256 || output >= context) throw new IllegalArgumentException("max output tokens out of range");
        String levels = ReasoningPolicy.normalizeLevels(p.reasoningLevels);
        String defaultReasoning = ReasoningPolicy.normalizeEffort(p.defaultReasoning);
        if (!ReasoningPolicy.allows(levels, defaultReasoning)) throw new IllegalArgumentException("default reasoning not allowed by model profile");
        ContentValues v = new ContentValues();
        v.put("profile_id", p.id); v.put("provider_id", p.providerId); v.put("model_id", modelId);
        v.put("display_name", p.displayName == null || p.displayName.trim().isEmpty() ? modelId : p.displayName.trim());
        v.put("context_tokens", context); v.put("input_tokens", input); v.put("max_output_tokens", output);
        v.put("reasoning_levels", levels); v.put("default_reasoning", defaultReasoning);
        v.put("input_modalities", normalizeModalities(p.inputModalities)); v.put("output_modalities", normalizeModalities(p.outputModalities));
        v.put("tool_capabilities", ChatTurnOptions.normalizeCapabilities(p.toolCapabilities));
        v.put("updated_at", Instant.now().toString());
        return v;
    }

    private static ModelProfile legacyProfile(Provider provider, String model) {
        ModelProfile p = new ModelProfile(); p.id = provider.id + ":default"; p.providerId = provider.id;
        p.modelId = model == null ? "" : model.trim(); p.displayName = p.modelId;
        p.contextTokens = provider.contextTokens > 0 ? provider.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        p.inputTokens = Math.max(0, provider.inputTokens);
        p.maxOutputTokens = provider.maxOutputTokens > 0 ? provider.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
        p.reasoningLevels = ""; p.defaultReasoning = ReasoningPolicy.DEFAULT; p.inputModalities = "text"; p.outputModalities = "text"; return p;
    }

    static String normalizeModalities(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty()) return "text";
        boolean text = false, image = false;
        for (String part : value.split(",")) {
            String item = part.trim(); if (item.isEmpty()) continue;
            if ("text".equals(item)) text = true;
            else if ("image".equals(item)) image = true;
            else throw new IllegalArgumentException("modalities only support text,image in this build");
        }
        if (!text && !image) return "text";
        return text && image ? "text,image" : image ? "image" : "text";
    }

    static boolean supportsModality(String modalities, String target) {
        String normalized = normalizeModalities(modalities);
        for (String part : normalized.split(",")) if (target.equals(part.trim())) return true;
        return false;
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

    private static Session readSession(Cursor c) { Session s = new Session(); s.id = c.getString(0); s.title = c.getString(1); s.providerId = c.getString(2); s.modelProfileId = c.isNull(3) ? null : c.getString(3); s.model = c.getString(4); s.contextTokens = c.getInt(5); s.inputTokens = c.getInt(6); s.maxOutputTokens = c.getInt(7); s.reasoningEffort = c.getString(8); s.inputModalities = c.getString(9); s.outputModalities = c.getString(10); s.state = c.getString(11); s.updatedAt = c.getString(12); s.draft = c.getString(13); s.draftMedia = c.getString(14); s.originSnapshotId = c.isNull(15) ? null : c.getString(15); s.parentSessionId = c.isNull(16) ? null : c.getString(16); s.preview = c.isNull(17) ? null : c.getString(17); s.contextMode = c.isNull(18) ? ContextMode.AUTO : ContextMode.normalize(c.getString(18)); s.taskProfile = c.isNull(19) ? "" : c.getString(19); s.contextCheckpoint = c.isNull(20) ? "" : c.getString(20); s.checkpointCoveredItems = c.getInt(21); return s; }
    private static Provider readProvider(Cursor c) { Provider p = new Provider(); p.id = c.getString(0); p.displayName = c.getString(1); p.protocol = c.getString(2); p.baseUrl = c.getString(3); p.authMode = c.getString(4); p.reasoningWire = c.getString(5); p.model = c.getString(6); p.contextTokens = c.getInt(7); p.inputTokens = c.getInt(8); p.maxOutputTokens = c.getInt(9); return p; }
    private static ModelProfile readModelProfile(Cursor c) { ModelProfile p = new ModelProfile(); p.id = c.getString(0); p.providerId = c.getString(1); p.modelId = c.getString(2); p.displayName = c.getString(3); p.contextTokens = c.getInt(4); p.inputTokens = c.getInt(5); p.maxOutputTokens = c.getInt(6); p.reasoningLevels = c.getString(7); p.defaultReasoning = c.getString(8); p.inputModalities = c.getString(9); p.outputModalities = c.getString(10); return p; }
}
