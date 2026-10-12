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

/**
 * Chat-owned database: providers, model profiles, sessions, timeline, branch snapshots, verifier
 * labels and app preferences. API keys never live here (see {@link ChatSecretStore}).
 */
public final class ChatDatabase extends SQLiteOpenHelper {
    private static final String NAME = "moyu-chat.db";
    /**
     * v17 is the beta baseline. The beta line keeps no pre-baseline data: any older (or newer) file is
     * dropped and recreated. Migrations after the baseline go forward from here in {@link #onUpgrade}.
     */
    static final int BASELINE_VERSION = 17;
    private static final int VERSION = 17;

    public static final class Session {
        public String id, title, providerId, modelProfileId, model, reasoningEffort, reasoningLevels, inputModalities, outputModalities, state, createdAt, updatedAt, draft, draftMedia, originSnapshotId, parentSessionId, preview;
        /** Session-scoped context policy plus provider-neutral derived control state. */
        public String contextMode, taskProfile, contextCheckpoint, toolCapabilities, webMode;
        /** Frozen model-behavior/tool orchestration policy. */
        public String harnessPreset = HarnessProfile.BALANCED;
        public String clientToolCalling = ClientToolRuntime.NONE;
        public String subagentMode = HarnessPolicyResolver.SUBAGENT_OFF;
        /** Session-frozen deployment capabilities; editing a ModelProfile affects new/model-switched turns only. */
        public String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        public String authMode = ProviderAuth.BEARER;
        public String reasoningWire = ReasoningPolicy.WIRE_NONE;
        public String thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
        public String continuationMode = ProviderContinuationPolicy.NONE;
        public int thinkingBudgetTokens;
        public int checkpointCoveredItems;
        public int contextTokens, inputTokens, maxOutputTokens;
    }

    /**
     * Shared account/endpoint. Only id, display name and base URL are persisted; the remaining fields
     * are a request-local deployment view filled by {@link #deploymentProvider} from a ModelProfile or
     * Session, so transports read one object.
     */
    public static final class Provider {
        public String id, displayName, baseUrl;
        /** Request-local deployment fields; not persisted in providers. */
        public String model;
        public String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        public String authMode = ProviderAuth.BEARER;
        public String reasoningWire = ReasoningPolicy.WIRE_NONE;
        public String thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
        public int thinkingBudgetTokens = 0;
        public int contextTokens = ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        public int inputTokens = 0;
        public int maxOutputTokens = ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
    }

    /** Model capabilities are scoped to one concrete provider deployment, not a global model name. */
    public static final class ModelProfile {
        public String id, providerId, modelId, displayName, reasoningLevels;
        /** Wire behavior belongs to the concrete model deployment, not the shared account/endpoint. */
        public String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        public String authMode = ProviderAuth.BEARER;
        public String reasoningWire = ReasoningPolicy.WIRE_NONE;
        public String inputModalities = "text", outputModalities = "text", toolCapabilities = "";
        /** Explicit thinking capability; provider-default avoids model-name guessing. */
        public String thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
        public String continuationMode = ProviderContinuationPolicy.NONE;
        /** Catalog + adaptive harness metadata. */
        // Programmatic callers are explicit/manual by default. The New Model UI opts into Auto
        // deliberately, so setting protocol/capability fields can never be silently overwritten.
        public String configMode = ModelCatalog.CONFIG_MANUAL;
        public String catalogKey = "unknown", catalogVersion = "", catalogSource = "manual";
        public String harnessPreset = HarnessProfile.BALANCED;
        public String clientToolCalling = ClientToolRuntime.NONE;
        public int thinkingBudgetTokens = 0;
        public int contextTokens, inputTokens, maxOutputTokens;
    }

    /** Immutable copy of a local session's transcript prefix; the origin of a branch. */
    public static final class Snapshot {
        public String id, sourceSessionId, sourceTitle, createdAt, contentHash;
        public int itemCount, sourceItemCount;
        public boolean truncated;
    }

    private static final String SESSION_COLUMNS = "local_id,title,provider_id,model_profile_id,model,context_tokens,input_tokens,max_output_tokens,reasoning_effort,input_modalities,output_modalities,tool_capabilities,web_mode,state,updated_at,draft,draft_media,origin_snapshot_id,parent_session_id,preview,context_mode,task_profile,context_checkpoint,checkpoint_covered_items,protocol,auth_mode,reasoning_wire,thinking_mode,thinking_budget_tokens,continuation_mode,harness_preset,client_tool_calling,subagent_mode,reasoning_levels,created_at";
    private static final String PROFILE_COLUMNS = "profile_id,provider_id,model_id,display_name,context_tokens,input_tokens,max_output_tokens,reasoning_levels,input_modalities,output_modalities,tool_capabilities,protocol,auth_mode,reasoning_wire,thinking_mode,thinking_budget_tokens,continuation_mode,config_mode,catalog_key,catalog_version,catalog_source,harness_preset,client_tool_calling";

    ChatDatabase(Context context) { super(context, NAME, null, VERSION); }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        // Provider: shared account/endpoint. credential_epoch changes the deployment identity whenever
        // the API key is replaced, so verifier calibration and provider continuation never cross keys.
        db.execSQL("CREATE TABLE providers(provider_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,base_url TEXT NOT NULL,"
                + "credential_epoch INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        // Model Profile: one concrete deployment. Wire protocol and every capability are declared here, never inferred.
        db.execSQL("CREATE TABLE model_profiles(profile_id TEXT PRIMARY KEY,"
                + "provider_id TEXT NOT NULL REFERENCES providers(provider_id) ON DELETE CASCADE,"
                + "model_id TEXT NOT NULL,display_name TEXT NOT NULL,"
                + "protocol TEXT NOT NULL,auth_mode TEXT NOT NULL,reasoning_wire TEXT NOT NULL,"
                + "context_tokens INTEGER NOT NULL,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL,"
                + "reasoning_levels TEXT NOT NULL DEFAULT '',thinking_mode TEXT NOT NULL,thinking_budget_tokens INTEGER NOT NULL DEFAULT 0,"
                + "continuation_mode TEXT NOT NULL,input_modalities TEXT NOT NULL,output_modalities TEXT NOT NULL,"
                + "tool_capabilities TEXT NOT NULL DEFAULT '',client_tool_calling TEXT NOT NULL,harness_preset TEXT NOT NULL,"
                + "config_mode TEXT NOT NULL,catalog_key TEXT NOT NULL,catalog_version TEXT NOT NULL DEFAULT '',catalog_source TEXT NOT NULL,"
                + "created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(provider_id,model_id))");
        // Session: identity, lineage, the deployment frozen at create/model switch, per-session choices,
        // and derived context state. Profile edits never relabel an existing Chat.
        db.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,title TEXT NOT NULL,state TEXT NOT NULL,"
                + "created_at TEXT NOT NULL,updated_at TEXT NOT NULL,preview TEXT,"
                + "draft TEXT NOT NULL DEFAULT '',draft_media TEXT NOT NULL DEFAULT '[]',"
                + "provider_id TEXT NOT NULL REFERENCES providers(provider_id),"
                + "model_profile_id TEXT REFERENCES model_profiles(profile_id) ON DELETE SET NULL,"
                + "parent_session_id TEXT REFERENCES sessions(local_id) ON DELETE SET NULL,"
                + "origin_snapshot_id TEXT REFERENCES snapshots(snapshot_id),"
                + "model TEXT NOT NULL,protocol TEXT NOT NULL,auth_mode TEXT NOT NULL,reasoning_wire TEXT NOT NULL,"
                + "context_tokens INTEGER NOT NULL,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL,"
                + "reasoning_levels TEXT NOT NULL DEFAULT '',thinking_mode TEXT NOT NULL,thinking_budget_tokens INTEGER NOT NULL DEFAULT 0,"
                + "continuation_mode TEXT NOT NULL,input_modalities TEXT NOT NULL,output_modalities TEXT NOT NULL,"
                + "tool_capabilities TEXT NOT NULL DEFAULT '',client_tool_calling TEXT NOT NULL,harness_preset TEXT NOT NULL,"
                + "reasoning_effort TEXT NOT NULL,web_mode TEXT NOT NULL,context_mode TEXT NOT NULL,subagent_mode TEXT NOT NULL,"
                + "task_profile TEXT NOT NULL DEFAULT '',context_checkpoint TEXT NOT NULL DEFAULT '',"
                + "checkpoint_covered_items INTEGER NOT NULL DEFAULT 0,knowledge_indexed_at TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE INDEX sessions_updated ON sessions(updated_at)");
        // Timeline: one JSON item per row (message / usage / error), ordered by local_seq. kind mirrors
        // body.kind so queries never pattern-match JSON. UNIQUE(session_id,local_seq) is the session index.
        db.execSQL("CREATE TABLE timeline(item_key TEXT PRIMARY KEY,"
                + "session_id TEXT NOT NULL REFERENCES sessions(local_id) ON DELETE CASCADE,"
                + "local_seq INTEGER NOT NULL,kind TEXT NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL,"
                + "UNIQUE(session_id,local_seq))");
        // Snapshot: immutable branch origin. The source session may be deleted later; the snapshot stays.
        db.execSQL("CREATE TABLE snapshots(snapshot_id TEXT PRIMARY KEY,source_session_id TEXT NOT NULL,source_title TEXT NOT NULL,"
                + "created_at TEXT NOT NULL,item_count INTEGER NOT NULL,source_item_count INTEGER NOT NULL,"
                + "truncated INTEGER NOT NULL DEFAULT 0,content_hash TEXT NOT NULL)");
        db.execSQL("CREATE TABLE snapshot_items(snapshot_id TEXT NOT NULL REFERENCES snapshots(snapshot_id) ON DELETE CASCADE,"
                + "ordinal INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(snapshot_id,ordinal))");
        // Lexical index for memory/retrieval. scope_id is chat:<session> or snapshot:<snapshot>.
        db.execSQL("CREATE VIRTUAL TABLE knowledge_fts USING fts4(scope_id,item_ref,ordinal,role,text,terms,tokenize=simple)");
        // Explicit verifier feedback, keyed by deployment calibration key; bounded per key in code.
        db.execSQL("CREATE TABLE verifier_labels(label_id INTEGER PRIMARY KEY AUTOINCREMENT,calibration_key TEXT NOT NULL,"
                + "features TEXT NOT NULL,label INTEGER NOT NULL CHECK(label IN (0,1)),created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX verifier_labels_key ON verifier_labels(calibration_key,label_id)");
        // App preferences only (theme, route, defaults). Per-entity state belongs to its own table.
        db.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < BASELINE_VERSION) { dropAll(db); onCreate(db); }
    }

    /** A beta build may meet a file from a newer experimental build; the beta line resets rather than guessing. */
    @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) { dropAll(db); onCreate(db); }

    /** Child tables first so foreign keys never block the drop (PRAGMA foreign_keys cannot change inside the upgrade transaction). */
    private static void dropAll(SQLiteDatabase db) {
        for (String table : new String[]{"knowledge_fts", "snapshot_items", "timeline", "handoffs", "sessions", "snapshots",
                "model_profiles", "providers", "verifier_labels", "settings"}) {
            db.execSQL("DROP TABLE IF EXISTS " + table);
        }
        ArrayList<String> rest = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name<>'android_metadata'", null)) {
            while (c.moveToNext()) rest.add(c.getString(0));
        }
        for (String table : rest) db.execSQL("DROP TABLE IF EXISTS \"" + table.replace("\"", "\"\"") + "\"");
    }

    public synchronized Session createSession(ModelProfile profile, String title, String originSnapshotId,
                                              String parentSessionId, String reasoningEffort) {
        return createSession(profile, title, originSnapshotId, parentSessionId, reasoningEffort, ContextMode.STANDARD, ChatTurnOptions.WEB_AUTO);
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
        s.protocol = normalizeProtocol(profile.protocol); s.authMode = ProviderAuth.normalize(profile.authMode, s.protocol);
        s.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, s.protocol);
        s.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode); s.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
        s.continuationMode = ProviderContinuationPolicy.normalize(profile.continuationMode);
        s.harnessPreset = HarnessProfile.normalize(profile.harnessPreset);
        s.clientToolCalling = ClientToolRuntime.normalizeCalling(profile.clientToolCalling);
        s.webMode = ChatTurnOptions.normalizeWebMode(webMode);
        s.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
        s.reasoningEffort = ReasoningPolicy.normalizeEffort(reasoningEffort == null ? ReasoningPolicy.DEFAULT : reasoningEffort);
        if (!ReasoningPolicy.allows(s.reasoningLevels, s.reasoningEffort)) throw new IllegalArgumentException("reasoning effort not supported by frozen session capability");
        s.title = title == null || title.trim().isEmpty() ? "新 Chat" : title.trim();
        s.state = "idle";
        s.updatedAt = Instant.now().toString();
        s.createdAt = s.updatedAt;
        s.draft = "";
        s.draftMedia = "[]";
        s.originSnapshotId = originSnapshotId;
        s.parentSessionId = parentSessionId;
        s.contextMode = ContextMode.normalize(contextMode);
        s.subagentMode = ContextMode.MINIMAL.equals(s.contextMode) ? HarnessPolicyResolver.SUBAGENT_OFF
                : ContextMode.SMART.equals(s.contextMode) ? HarnessPolicyResolver.SUBAGENT_AUTO
                : HarnessPolicyResolver.normalizeSubagentMode(setting("standardSubagentMode", HarnessPolicyResolver.SUBAGENT_OFF));
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
        s.protocol = normalizeProtocol(source.protocol); s.authMode = ProviderAuth.normalize(source.authMode, s.protocol);
        s.reasoningWire = ReasoningPolicy.normalizeWire(source.reasoningWire, s.protocol);
        s.thinkingMode = ReasoningPolicy.normalizeThinkingMode(source.thinkingMode); s.thinkingBudgetTokens = Math.max(0, source.thinkingBudgetTokens);
        s.continuationMode = ProviderContinuationPolicy.normalize(source.continuationMode);
        s.harnessPreset = HarnessProfile.normalize(source.harnessPreset);
        s.clientToolCalling = ClientToolRuntime.normalizeCalling(source.clientToolCalling);
        s.subagentMode = HarnessPolicyResolver.normalizeSubagentMode(source.subagentMode);
        s.reasoningLevels = ReasoningPolicy.normalizeLevels(source.reasoningLevels);
        s.reasoningEffort = source.reasoningEffort == null ? ReasoningPolicy.DEFAULT : source.reasoningEffort;
        s.title = title == null || title.trim().isEmpty() ? "新 Chat" : title.trim(); s.state = "idle"; s.updatedAt = Instant.now().toString(); s.createdAt = s.updatedAt; s.draft = ""; s.draftMedia = "[]";
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + SESSION_COLUMNS + " FROM sessions WHERE local_id=?", new String[]{id})) {
            return c.moveToFirst() ? readSession(c) : null;
        }
    }

    public synchronized List<Session> listSessions() {
        ArrayList<Session> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + SESSION_COLUMNS + " FROM sessions ORDER BY updated_at DESC", null)) {
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
        try (Cursor c = db.rawQuery("SELECT item_key,session_id,body FROM timeline WHERE instr(body,'\"streaming\":true')>0", null)) {
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

    /** Deletes one local session and all derived lexical-index data that belongs to it. */
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
            db.delete("knowledge_fts", "scope_id=?", new String[]{LocalKnowledgeStore.chatScope(id)});
            if (db.delete("sessions", "local_id=?", new String[]{id}) != 1) throw new IllegalStateException("session delete failed");
            if (originSnapshotId != null) {
                boolean referenced;
                try (Cursor c = db.rawQuery("SELECT 1 FROM sessions WHERE origin_snapshot_id=? LIMIT 1", new String[]{originSnapshotId})) {
                    referenced = c.moveToFirst();
                }
                if (!referenced) {
                    db.delete("knowledge_fts", "scope_id=?", new String[]{LocalKnowledgeStore.snapshotScope(originSnapshotId)});
                    db.delete("snapshots", "snapshot_id=?", new String[]{originSnapshotId});
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Return one finalized assistant verification object and persist one explicit calibration label. */
    public synchronized JSONObject verificationForFeedback(String sessionId, int localSeq, String label) {
        if (sessionId == null || localSeq < 1) throw new IllegalArgumentException("invalid verification target");
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor c = db.rawQuery("SELECT item_key,body FROM timeline WHERE session_id=? AND local_seq=? LIMIT 1",
                new String[]{sessionId, String.valueOf(localSeq)})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("assistant message not found");
            JSONObject body = new JSONObject(c.getString(1));
            if (!"message".equals(body.optString("kind")) || !"assistant".equals(body.optString("role")) || body.optBoolean("streaming", false)) {
                throw new IllegalArgumentException("verification feedback requires a finalized assistant message");
            }
            JSONObject verification = body.optJSONObject("verification");
            if (verification == null) throw new IllegalArgumentException("this answer has no Smart verification record");
            if (verification.has("feedbackLabel")) throw new IllegalStateException("verification feedback already recorded");
            if (!SmartVerifierCalibration.recordFeedback(this, verification, label)) throw new IllegalArgumentException("feedback label is not valid for this verifier verdict");
            verification.put("feedbackLabel", label).put("feedbackRecordedAt", Instant.now().toString());
            body.put("verification", verification);
            ContentValues row = new ContentValues(); row.put("body", body.toString());
            if (db.update("timeline", row, "item_key=?", new String[]{c.getString(0)}) != 1) throw new IllegalStateException("verification feedback update failed");
            return new JSONObject(verification.toString());
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    /** Media GC authority: every durable JSON location that can legitimately retain a media descriptor. */
    public synchronized java.util.Set<String> referencedMediaIds() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        collectJsonColumn(getReadableDatabase(), "SELECT draft_media FROM sessions", out);
        collectJsonColumn(getReadableDatabase(), "SELECT body FROM timeline", out);
        collectJsonColumn(getReadableDatabase(), "SELECT body FROM snapshot_items", out);
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

    public synchronized void renameSession(String id, String title) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("session id required");
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("session title required");
        if (value.length() > 200) throw new IllegalArgumentException("session title too long");
        ContentValues v = new ContentValues(); v.put("title", value);
        if (getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id}) != 1) throw new IllegalArgumentException("session not found");
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

    public synchronized void replaceDraftMedia(String id, JSONArray items) {
        ContentValues v = new ContentValues(); v.put("draft_media", items == null ? "[]" : items.toString());
        getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id});
    }

    /** Deletes the timeline item at localSeq and everything after it (edit/rollback). */
    public synchronized void truncateTimelineFrom(String sessionId, int localSeq) {
        if (sessionId == null || sessionId.trim().isEmpty() || localSeq < 1) throw new IllegalArgumentException("invalid rollback target");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("timeline", "session_id=? AND local_seq>=?", new String[]{sessionId, String.valueOf(localSeq)});
            db.delete("knowledge_fts", "scope_id=?", new String[]{LocalKnowledgeStore.chatScope(sessionId)});
            ContentValues state = new ContentValues();
            state.put("state", "idle"); state.put("updated_at", Instant.now().toString()); state.put("draft", ""); state.put("draft_media", "[]");
            state.put("task_profile", ""); state.put("context_checkpoint", ""); state.put("checkpoint_covered_items", 0); state.put("knowledge_indexed_at", "");
            state.putNull("preview");
            if (db.update("sessions", state, "local_id=?", new String[]{sessionId}) != 1) throw new IllegalArgumentException("session not found");
            try (Cursor c = db.rawQuery("SELECT body FROM timeline WHERE session_id=? ORDER BY local_seq DESC", new String[]{sessionId})) {
                while (c.moveToNext()) {
                    try {
                        JSONObject body = new JSONObject(c.getString(0));
                        if (!"message".equals(body.optString("kind")) || body.optBoolean("streaming", false)) continue;
                        String text = body.optString("text", "").trim(); if (text.isEmpty()) continue;
                        String preview = text.length() > 160 ? text.substring(0, 160) : text;
                        ContentValues p = new ContentValues(); p.put("preview", preview); db.update("sessions", p, "local_id=?", new String[]{sessionId});
                        break;
                    } catch (Exception ignored) { }
                }
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

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
            user.put("kind", kindOf(userBody)); user.put("body", userBody.toString()); user.put("created_at", userBody.optString("createdAt", Instant.now().toString()));
            db.insertWithOnConflict("timeline", null, user, SQLiteDatabase.CONFLICT_ABORT);
            updatePreviewFromBody(db, sessionId, userBody);

            liveBody.put("localSeq", seq + 1);
            ContentValues live = new ContentValues(); live.put("item_key", streamKey); live.put("session_id", sessionId); live.put("local_seq", seq + 1);
            live.put("kind", kindOf(liveBody)); live.put("body", liveBody.toString()); live.put("created_at", liveBody.optString("createdAt", Instant.now().toString()));
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
        v.put("item_key", itemKey); v.put("session_id", sessionId); v.put("local_seq", seq); v.put("kind", kindOf(body)); v.put("body", body.toString());
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
        ContentValues v = new ContentValues(); v.put("kind", kindOf(body)); v.put("body", body.toString());
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
                row.put("local_seq", seq); row.put("kind", "error"); row.put("body", error.toString()); row.put("created_at", error.optString("createdAt"));
                db.insertWithOnConflict("timeline", null, row, SQLiteDatabase.CONFLICT_ABORT);
            }
            ContentValues state = new ContentValues(); state.put("state", finalState); state.put("updated_at", Instant.now().toString());
            if (db.update("sessions", state, "local_id=?", new String[]{sessionId}) != 1) throw new IllegalStateException("session missing during turn finalization");
            db.setTransactionSuccessful();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        } finally { db.endTransaction(); }
    }

    /** Last-resort terminal guard for exceptions that escape after beginTurn() but before transport ownership. */
    public synchronized void failUnhandledTurn(String sessionId, String code, String summary) {
        if (sessionId == null || sessionId.trim().isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ArrayList<String> streamingKeys = new ArrayList<>();
            try (Cursor c = db.rawQuery("SELECT item_key,body FROM timeline WHERE session_id=? AND instr(body,'\"streaming\":true')>0", new String[]{sessionId})) {
                while (c.moveToNext()) {
                    try { if (new JSONObject(c.getString(1)).optBoolean("streaming", false)) streamingKeys.add(c.getString(0)); }
                    catch (Exception ignored) { }
                }
            }
            for (String key : streamingKeys) db.delete("timeline", "item_key=?", new String[]{key});
            Session session = getSession(sessionId);
            if (session != null && "running".equals(session.state)) {
                int seq;
                try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(local_seq),0)+1 FROM timeline WHERE session_id=?", new String[]{sessionId})) {
                    if (!c.moveToFirst()) throw new IllegalStateException("cannot allocate terminal error sequence");
                    seq = c.getInt(0);
                }
                JSONObject error = new JSONObject().put("kind", "error").put("code", code == null ? "turn_internal_error" : code)
                        .put("summary", summary == null ? "Turn failed before provider ownership" : summary)
                        .put("createdAt", Instant.now().toString()).put("localSeq", seq);
                ContentValues row = new ContentValues(); row.put("item_key", "error:" + UUID.randomUUID()); row.put("session_id", sessionId);
                row.put("local_seq", seq); row.put("kind", "error"); row.put("body", error.toString()); row.put("created_at", error.optString("createdAt"));
                db.insertWithOnConflict("timeline", null, row, SQLiteDatabase.CONFLICT_ABORT);
                ContentValues state = new ContentValues(); state.put("state", "failed"); state.put("updated_at", Instant.now().toString());
                db.update("sessions", state, "local_id=?", new String[]{sessionId});
            }
            db.setTransactionSuccessful();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        } finally { db.endTransaction(); }
    }

    public synchronized void deleteTimelineItem(String itemKey) { getWritableDatabase().delete("timeline", "item_key=?", new String[]{itemKey}); }
    public synchronized JSONArray timeline(String sessionId) { return timelineThrough(sessionId, null); }

    /** Usage rows created at or after {@code sinceIso}, limited to sessions updated since then (session index, not a table scan). */
    public synchronized JSONArray usageSince(String sinceIso) {
        JSONArray out = new JSONArray();
        if (sinceIso == null) return out;
        String sql = "SELECT t.body FROM sessions s JOIN timeline t ON t.session_id=s.local_id WHERE s.updated_at>=? AND t.kind='usage' AND t.created_at>=?";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{sinceIso, sinceIso})) {
            while (c.moveToNext()) try { out.put(new JSONObject(c.getString(0))); } catch (Exception ignored) { }
        }
        return out;
    }

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

    /** Saves the shared provider row only (no model profiles, no credential change). */
    public synchronized void saveProvider(Provider p) { saveProviderAndProfiles(p, null, false); }

    /** Atomically save one shared Provider plus every deployment profile affected by its endpoint change. */
    public synchronized void saveProviderAndProfiles(Provider p, List<ModelProfile> profiles, boolean bumpCredentialEpoch) {
        if (p == null || p.id == null || p.id.trim().isEmpty()) throw new IllegalArgumentException("provider required");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            upsert(db, "providers", "provider_id", p.id, providerValues(p));
            if (profiles != null) for (ModelProfile profile : profiles) {
                if (profile == null) continue;
                if (!p.id.equals(profile.providerId)) throw new IllegalArgumentException("model profile belongs to another provider");
                upsert(db, "model_profiles", "profile_id", profile.id, modelProfileValues(profile));
            }
            if (bumpCredentialEpoch) db.execSQL("UPDATE providers SET credential_epoch=credential_epoch+1 WHERE provider_id=?", new Object[]{p.id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Monotonic per-provider counter, bumped whenever a new API key is stored. */
    public synchronized long credentialEpoch(String providerId) {
        if (providerId == null) return 0L;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT credential_epoch FROM providers WHERE provider_id=?", new String[]{providerId})) {
            return c.moveToFirst() ? c.getLong(0) : 0L;
        }
    }

    public synchronized void bumpCredentialEpoch(String providerId) {
        getWritableDatabase().execSQL("UPDATE providers SET credential_epoch=credential_epoch+1 WHERE provider_id=?", new Object[]{providerId});
    }

    public synchronized Provider getProvider(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT provider_id,display_name,base_url FROM providers WHERE provider_id=?", new String[]{id})) {
            return c.moveToFirst() ? readProvider(c) : null;
        }
    }

    public synchronized List<Provider> listProviders() {
        ArrayList<Provider> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT provider_id,display_name,base_url FROM providers ORDER BY display_name", null)) {
            while (c.moveToNext()) out.add(readProvider(c));
        }
        return out;
    }

    public synchronized void saveModelProfile(ModelProfile profile) {
        if (profile == null) throw new IllegalArgumentException("model profile required");
        upsert(getWritableDatabase(), "model_profiles", "profile_id", profile.id, modelProfileValues(profile));
    }

    public synchronized ModelProfile getModelProfile(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + PROFILE_COLUMNS + " FROM model_profiles WHERE profile_id=?", new String[]{id})) {
            return c.moveToFirst() ? readModelProfile(c) : null;
        }
    }

    public synchronized ModelProfile findModelProfile(String providerId, String modelId) {
        if (providerId == null || modelId == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + PROFILE_COLUMNS + " FROM model_profiles WHERE provider_id=? AND model_id=?", new String[]{providerId, modelId})) {
            return c.moveToFirst() ? readModelProfile(c) : null;
        }
    }

    public synchronized List<ModelProfile> listModelProfiles(String providerId) {
        ArrayList<ModelProfile> out = new ArrayList<>();
        if (providerId == null) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + PROFILE_COLUMNS + " FROM model_profiles WHERE provider_id=? ORDER BY display_name,model_id", new String[]{providerId})) {
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
            s.put("snapshot_id", snapshot.id); s.put("source_session_id", snapshot.sourceSessionId);
            s.put("source_title", snapshot.sourceTitle); s.put("created_at", snapshot.createdAt);
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT snapshot_id,source_session_id,source_title,created_at,item_count,source_item_count,truncated,content_hash FROM snapshots WHERE snapshot_id=?", new String[]{id})) {
            return c.moveToFirst() ? readSnapshot(c) : null;
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

    /** updated_at of the session when its lexical index was last rebuilt; '' when stale. Never bumps updated_at. */
    public synchronized String knowledgeIndexedAt(String sessionId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT knowledge_indexed_at FROM sessions WHERE local_id=?", new String[]{sessionId})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    public synchronized void markKnowledgeIndexed(String sessionId, String marker) {
        ContentValues v = new ContentValues(); v.put("knowledge_indexed_at", marker == null ? "" : marker);
        getWritableDatabase().update("sessions", v, "local_id=?", new String[]{sessionId});
    }

    /** Appends one explicit verifier label and keeps only the newest {@code keep} rows for that calibration key. */
    public synchronized void addVerifierLabel(String calibrationKey, JSONArray features, int label, int keep) {
        if (calibrationKey == null || calibrationKey.isEmpty() || features == null) throw new IllegalArgumentException("verifier label requires key and features");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            v.put("calibration_key", calibrationKey); v.put("features", features.toString()); v.put("label", label == 0 ? 0 : 1); v.put("created_at", Instant.now().toString());
            db.insertOrThrow("verifier_labels", null, v);
            db.execSQL("DELETE FROM verifier_labels WHERE calibration_key=? AND label_id NOT IN (SELECT label_id FROM verifier_labels WHERE calibration_key=? ORDER BY label_id DESC LIMIT ?)",
                    new Object[]{calibrationKey, calibrationKey, Math.max(1, keep)});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Oldest-first {x, y} samples for one calibration key. */
    public synchronized JSONArray verifierLabels(String calibrationKey) {
        JSONArray out = new JSONArray();
        if (calibrationKey == null || calibrationKey.isEmpty()) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT features,label FROM verifier_labels WHERE calibration_key=? ORDER BY label_id", new String[]{calibrationKey})) {
            while (c.moveToNext()) try { out.put(new JSONObject().put("x", new JSONArray(c.getString(0))).put("y", c.getInt(1))); } catch (Exception ignored) { }
        }
        return out;
    }

    public synchronized void putSetting(String key, String value) { ContentValues v = new ContentValues(); v.put("key", key); v.put("value", value); getWritableDatabase().insertWithOnConflict("settings", null, v, SQLiteDatabase.CONFLICT_REPLACE); }
    public synchronized String setting(String key, String fallback) { try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) { return c.moveToFirst() ? c.getString(0) : fallback; } }

    private static ContentValues sessionValues(Session s) {
        ContentValues v = new ContentValues();
        v.put("local_id", s.id); v.put("title", s.title); v.put("created_at", s.createdAt == null ? s.updatedAt : s.createdAt); v.put("provider_id", s.providerId); v.put("model_profile_id", s.modelProfileId); v.put("model", s.model);
        v.put("context_tokens", s.contextTokens > 0 ? s.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS);
        v.put("input_tokens", Math.max(0, s.inputTokens));
        v.put("max_output_tokens", s.maxOutputTokens > 0 ? s.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS);
        v.put("reasoning_effort", ReasoningPolicy.normalizeEffort(s.reasoningEffort));
        v.put("reasoning_levels", ReasoningPolicy.normalizeLevels(s.reasoningLevels));
        v.put("input_modalities", normalizeModalities(s.inputModalities)); v.put("output_modalities", normalizeModalities(s.outputModalities));
        v.put("tool_capabilities", ChatTurnOptions.normalizeCapabilities(s.toolCapabilities));
        String protocol = normalizeProtocol(s.protocol);
        v.put("protocol", protocol); v.put("auth_mode", ProviderAuth.normalize(s.authMode, protocol));
        v.put("reasoning_wire", ReasoningPolicy.normalizeWire(s.reasoningWire, protocol));
        v.put("thinking_mode", ReasoningPolicy.normalizeThinkingMode(s.thinkingMode));
        v.put("thinking_budget_tokens", Math.max(0, s.thinkingBudgetTokens));
        v.put("continuation_mode", ProviderContinuationPolicy.normalize(s.continuationMode));
        v.put("harness_preset", HarnessProfile.normalize(s.harnessPreset));
        v.put("client_tool_calling", ClientToolRuntime.normalizeCalling(s.clientToolCalling));
        v.put("subagent_mode", HarnessPolicyResolver.normalizeSubagentMode(s.subagentMode));
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
        v.put("provider_id", p.id); v.put("display_name", p.displayName); v.put("base_url", p.baseUrl);
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
        String protocol = normalizeProtocol(p.protocol);
        String authMode = ProviderAuth.normalize(p.authMode, protocol);
        String reasoningWire = ReasoningPolicy.normalizeWire(p.reasoningWire, protocol);
        String thinkingMode = ReasoningPolicy.normalizeThinkingMode(p.thinkingMode);
        String continuationMode = ProviderContinuationPolicy.normalize(p.continuationMode);
        int thinkingBudget = Math.max(0, p.thinkingBudgetTokens);
        if (ReasoningPolicy.THINKING_MANUAL.equals(thinkingMode)) {
            if (thinkingBudget < 1024 || thinkingBudget >= output) throw new IllegalArgumentException("manual thinking budget must be >=1024 and < max output tokens");
        } else thinkingBudget = 0;
        ContentValues v = new ContentValues();
        v.put("profile_id", p.id); v.put("provider_id", p.providerId); v.put("model_id", modelId);
        v.put("display_name", p.displayName == null || p.displayName.trim().isEmpty() ? modelId : p.displayName.trim());
        v.put("context_tokens", context); v.put("input_tokens", input); v.put("max_output_tokens", output);
        v.put("reasoning_levels", levels);
        v.put("input_modalities", normalizeModalities(p.inputModalities)); v.put("output_modalities", normalizeModalities(p.outputModalities));
        v.put("tool_capabilities", ChatTurnOptions.normalizeCapabilities(p.toolCapabilities));
        v.put("protocol", protocol); v.put("auth_mode", authMode); v.put("reasoning_wire", reasoningWire);
        v.put("thinking_mode", thinkingMode); v.put("thinking_budget_tokens", thinkingBudget);
        v.put("continuation_mode", continuationMode);
        v.put("config_mode", ModelCatalog.normalizeConfigMode(p.configMode));
        v.put("catalog_key", p.catalogKey == null || p.catalogKey.trim().isEmpty() ? "unknown" : p.catalogKey.trim());
        v.put("catalog_version", p.catalogVersion == null ? "" : p.catalogVersion.trim());
        v.put("catalog_source", p.catalogSource == null || p.catalogSource.trim().isEmpty() ? "neutral-defaults" : p.catalogSource.trim());
        v.put("harness_preset", HarnessProfile.normalize(p.harnessPreset));
        v.put("client_tool_calling", ClientToolRuntime.normalizeCalling(p.clientToolCalling));
        v.put("updated_at", Instant.now().toString());
        return v;
    }

    /** Update-or-insert that stamps created_at only on insert. */
    private static void upsert(SQLiteDatabase db, String table, String keyColumn, String id, ContentValues values) {
        if (db.update(table, values, keyColumn + "=?", new String[]{id}) > 0) return;
        values.put("created_at", values.getAsString("updated_at") == null ? Instant.now().toString() : values.getAsString("updated_at"));
        db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_ABORT);
    }

    /** timeline.kind mirrors body.kind; items without one are messages (the knowledge index reads them the same way). */
    private static String kindOf(JSONObject body) {
        String kind = body == null ? "" : body.optString("kind", "").trim();
        return kind.isEmpty() ? "message" : kind;
    }

    static String normalizeProtocol(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (OpenAiCompatibleTransport.CHAT_COMPLETIONS.equals(value)
                || OpenAiCompatibleTransport.RESPONSES.equals(value)
                || AnthropicMessagesTransport.PROTOCOL.equals(value)) return value;
        throw new IllegalArgumentException("unsupported protocol");
    }

    /** Resolve one request-local deployment snapshot from shared account data plus model capabilities. */
    static Provider deploymentProvider(Provider shared, ModelProfile profile) {
        if (shared == null || profile == null) throw new IllegalArgumentException("provider/model profile required");
        Provider out = new Provider();
        out.id = shared.id; out.displayName = shared.displayName; out.baseUrl = shared.baseUrl; out.model = profile.modelId;
        out.protocol = normalizeProtocol(profile.protocol);
        out.authMode = ProviderAuth.normalize(profile.authMode, out.protocol);
        out.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, out.protocol);
        out.contextTokens = profile.contextTokens; out.inputTokens = profile.inputTokens; out.maxOutputTokens = profile.maxOutputTokens;
        out.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode); out.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
        return out;
    }

    /** Session form is authoritative after creation/model switch so later ModelProfile edits cannot relabel an old Chat. */
    static Provider deploymentProvider(Provider shared, Session session) {
        if (shared == null || session == null) throw new IllegalArgumentException("provider/session required");
        Provider out = new Provider();
        out.id = shared.id; out.displayName = shared.displayName; out.baseUrl = shared.baseUrl; out.model = session.model;
        out.protocol = normalizeProtocol(session.protocol);
        out.authMode = ProviderAuth.normalize(session.authMode, out.protocol);
        out.reasoningWire = ReasoningPolicy.normalizeWire(session.reasoningWire, out.protocol);
        out.contextTokens = session.contextTokens; out.inputTokens = session.inputTokens; out.maxOutputTokens = session.maxOutputTokens;
        out.thinkingMode = ReasoningPolicy.normalizeThinkingMode(session.thinkingMode); out.thinkingBudgetTokens = Math.max(0, session.thinkingBudgetTokens);
        return out;
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

    private static void updatePreviewFromBody(SQLiteDatabase db, String sessionId, JSONObject body) {
        if (!"message".equals(body.optString("kind")) || body.optBoolean("streaming", false)) return;
        String role = body.optString("role", ""); if (!("user".equals(role) || "assistant".equals(role))) return;
        String text = body.optString("text", "").trim(); if (text.isEmpty()) return;
        String preview = text.length() > 160 ? text.substring(0, 160) : text;
        ContentValues s = new ContentValues(); s.put("preview", preview); s.put("updated_at", Instant.now().toString());
        db.update("sessions", s, "local_id=?", new String[]{sessionId});
    }


    private static Snapshot readSnapshot(Cursor c) {
        Snapshot s = new Snapshot();
        s.id = c.getString(0); s.sourceSessionId = c.getString(1); s.sourceTitle = c.getString(2); s.createdAt = c.getString(3);
        s.itemCount = c.getInt(4); s.sourceItemCount = c.getInt(5); s.truncated = c.getInt(6) != 0; s.contentHash = c.getString(7);
        return s;
    }

    private static Session readSession(Cursor c) {
        Session s = new Session();
        s.id = c.getString(0); s.title = c.getString(1); s.providerId = c.getString(2); s.modelProfileId = c.isNull(3) ? null : c.getString(3); s.model = c.getString(4);
        s.contextTokens = c.getInt(5); s.inputTokens = c.getInt(6); s.maxOutputTokens = c.getInt(7); s.reasoningEffort = c.getString(8);
        s.inputModalities = c.getString(9); s.outputModalities = c.getString(10); s.toolCapabilities = ChatTurnOptions.normalizeCapabilities(c.getString(11));
        s.webMode = ChatTurnOptions.normalizeWebMode(c.getString(12)); s.state = c.getString(13); s.updatedAt = c.getString(14);
        s.draft = c.getString(15); s.draftMedia = c.getString(16); s.originSnapshotId = c.isNull(17) ? null : c.getString(17);
        s.parentSessionId = c.isNull(18) ? null : c.getString(18); s.preview = c.isNull(19) ? null : c.getString(19);
        s.contextMode = ContextMode.normalize(c.getString(20)); s.taskProfile = c.getString(21); s.contextCheckpoint = c.getString(22); s.checkpointCoveredItems = c.getInt(23);
        s.protocol = normalizeProtocol(c.getString(24)); s.authMode = ProviderAuth.normalize(c.getString(25), s.protocol);
        s.reasoningWire = ReasoningPolicy.normalizeWire(c.getString(26), s.protocol); s.thinkingMode = ReasoningPolicy.normalizeThinkingMode(c.getString(27));
        s.thinkingBudgetTokens = Math.max(0, c.getInt(28)); s.continuationMode = ProviderContinuationPolicy.normalize(c.getString(29));
        s.harnessPreset = HarnessProfile.normalize(c.getString(30)); s.clientToolCalling = ClientToolRuntime.normalizeCalling(c.getString(31));
        s.subagentMode = HarnessPolicyResolver.normalizeSubagentMode(c.getString(32)); s.reasoningLevels = ReasoningPolicy.normalizeLevels(c.getString(33));
        s.createdAt = c.getString(34);
        return s;
    }

    private static Provider readProvider(Cursor c) {
        Provider p = new Provider();
        p.id = c.getString(0); p.displayName = c.getString(1); p.baseUrl = c.getString(2);
        return p;
    }

    private static ModelProfile readModelProfile(Cursor c) {
        ModelProfile p = new ModelProfile();
        p.id = c.getString(0); p.providerId = c.getString(1); p.modelId = c.getString(2); p.displayName = c.getString(3);
        p.contextTokens = c.getInt(4); p.inputTokens = c.getInt(5); p.maxOutputTokens = c.getInt(6); p.reasoningLevels = c.getString(7);
        p.inputModalities = c.getString(8); p.outputModalities = c.getString(9); p.toolCapabilities = ChatTurnOptions.normalizeCapabilities(c.getString(10));
        p.protocol = normalizeProtocol(c.getString(11)); p.authMode = ProviderAuth.normalize(c.getString(12), p.protocol);
        p.reasoningWire = ReasoningPolicy.normalizeWire(c.getString(13), p.protocol); p.thinkingMode = ReasoningPolicy.normalizeThinkingMode(c.getString(14));
        p.thinkingBudgetTokens = Math.max(0, c.getInt(15)); p.continuationMode = ProviderContinuationPolicy.normalize(c.getString(16));
        p.configMode = ModelCatalog.normalizeConfigMode(c.getString(17)); p.catalogKey = c.getString(18); p.catalogVersion = c.getString(19); p.catalogSource = c.getString(20);
        p.harnessPreset = HarnessProfile.normalize(c.getString(21)); p.clientToolCalling = ClientToolRuntime.normalizeCalling(c.getString(22));
        return p;
    }
}
