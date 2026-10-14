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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chat-owned database: providers, model profiles, sessions, timeline, turns with their stage traces,
 * branch snapshots, verifier labels and app preferences. API keys never live here (see {@link ChatSecretStore}).
 */
public final class ChatDatabase extends SQLiteOpenHelper {
    /**
     * The beta line restarts schema numbering at 1 in its own file. The pre-beta file (schema 1..16 of
     * the original line) is deleted, never migrated, so the two numbering schemes can never collide.
     */
    static final String FILE_NAME = "moyu-beta.db";
    static final String LEGACY_FILE_NAME = "moyu-chat.db";
    /** 1 = frozen at layout revision 2 (installed on devices); 2 = turn_archive. Steps live in {@link #onUpgrade}. */
    static final int VERSION = 2;
    /**
     * Pre-release layout of schema 1, stamped in PRAGMA application_id. A file with a different stamp is an
     * older pre-release v1 layout and is reset (dropped and recreated). Schema 1 froze at revision 2 (accounts
     * with routes); every later change is a forward migration in onUpgrade and keeps this stamp.
     */
    static final int LAYOUT_REVISION = 2;

    public static final class Session {
        public String id, title, providerId, modelProfileId, model, reasoningEffort, reasoningLevels, inputModalities, outputModalities, state, createdAt, updatedAt, draft, draftMedia, originSnapshotId, parentSessionId, preview;
        /** Session-scoped context policy plus provider-neutral derived control state. */
        public String contextMode, taskProfile, contextCheckpoint, toolCapabilities, webMode;
        /** Frozen model-behavior/tool orchestration policy. */
        public String harnessPreset = HarnessProfile.BALANCED;
        public String clientToolCalling = ClientToolRuntime.NONE;
        public String subagentMode = HarnessPolicyResolver.SUBAGENT_OFF;
        /**
         * Session-frozen deployment capabilities; editing a ModelProfile affects new/model-switched turns only.
         * The route (Base URL, auth, dialect, headers) is followed live: it describes the endpoint, not the Chat.
         */
        public String routeId;
        public String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        public String reasoningWire = ReasoningPolicy.WIRE_NONE;
        public String serviceTier = "";
        /** False when the output limit was only a local default: then the request leaves the cap to the server. */
        public boolean sendOutputCap;
        public String thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
        public String continuationMode = ProviderContinuationPolicy.NONE;
        public int thinkingBudgetTokens;
        public int checkpointCoveredItems;
        public int contextTokens, inputTokens, maxOutputTokens;
    }

    /**
     * Account: one API Key (native secure store) shared by its routes. Persisted: id, display name, preset,
     * key status and budget. The remaining fields are a request-local deployment view filled by
     * {@link #deploymentProvider} from a Route plus a ModelProfile or Session, so transports read one object.
     */
    public static final class Provider {
        public String id, displayName;
        public String presetId = ProviderPresets.CUSTOM_OPENAI;
        public String keyStatus = "unknown", keyCheckedAt = "", budget = "{}";
        /** Request-local deployment fields; not persisted in providers. */
        public String model, routeId;
        public String baseUrl;
        public String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        public String authMode = ProviderAuth.BEARER;
        public RouteDialect dialect;
        public org.json.JSONObject headers;
        public String reasoningWire = ReasoningPolicy.WIRE_NONE;
        public String thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
        /** Request-local: the effort levels the deployment declares ('' = no effort selector). */
        public String reasoningLevels = "";
        public String serviceTier = "";
        public int thinkingBudgetTokens = 0;
        public int contextTokens = ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        public int inputTokens = 0;
        public int maxOutputTokens = ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
        /** Whether maxOutputTokens goes on the wire (Messages always sends it; the protocol requires one). */
        public boolean sendOutputCap = true;
    }

    /** One endpoint of an account: protocol + Base URL + auth + wire dialect + extra headers. Protocol never changes after creation. */
    public static final class Route {
        public String id, providerId, label, protocol, baseUrl, authMode;
        /** Copied from the account on read; presetRouteId names the preset route this was created from ('' = custom). */
        public String presetId = ProviderPresets.CUSTOM_OPENAI, presetRouteId = "";
        public String dialect = "{}", headers = "{}";
    }

    /** Model capabilities are scoped to one concrete deployment (account + route + model id), not a global model name. */
    public static final class ModelProfile {
        public String id, providerId, routeId, modelId, displayName, reasoningLevels;
        public boolean enabled = true;
        /** Read-only mirror of the route's protocol (filled on read); wire identity belongs to the route. */
        public String protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        public String reasoningWire = ReasoningPolicy.WIRE_NONE;
        public String inputModalities = "text", outputModalities = "text", toolCapabilities = "";
        /** Explicit thinking capability; provider-default avoids model-name guessing. */
        public String thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT;
        public String continuationMode = ProviderContinuationPolicy.NONE;
        public String harnessPreset = HarnessProfile.BALANCED;
        public String clientToolCalling = ClientToolRuntime.NONE;
        public String serviceTier = "";
        public int thinkingBudgetTokens = 0;
        public int contextTokens, inputTokens, maxOutputTokens;
        /**
         * Resolution bookkeeping (see DeploymentResolver): overrides are the user's explicit values,
         * sources name where each resolved field came from; discovered/probe hold endpoint evidence.
         */
        public String overrides = "{}", sources = "{}", discovered = "{}", probe = "{}", pricing = "{}";
        public String catalogKey = "", catalogVersion = "", retention = "";
    }

    /** Immutable copy of a local session's transcript prefix; the origin of a branch. */
    public static final class Snapshot {
        public String id, sourceSessionId, sourceTitle, createdAt, contentHash;
        public int itemCount, sourceItemCount;
        public boolean truncated;
    }

    private static final String SESSION_COLUMNS = "local_id,title,provider_id,model_profile_id,model,context_tokens,input_tokens,max_output_tokens,reasoning_effort,input_modalities,output_modalities,tool_capabilities,web_mode,state,updated_at,draft,draft_media,origin_snapshot_id,parent_session_id,preview,context_mode,task_profile,context_checkpoint,checkpoint_covered_items,protocol,route_id,reasoning_wire,thinking_mode,thinking_budget_tokens,continuation_mode,harness_preset,client_tool_calling,subagent_mode,reasoning_levels,created_at,send_output_cap,service_tier";
    private static final String PROVIDER_COLUMNS = "provider_id,display_name,preset_id,key_status,key_checked_at,budget";
    private static final String ROUTE_COLUMNS = "r.route_id,r.provider_id,r.preset_route,r.label,r.protocol,r.base_url,r.auth_mode,r.dialect,r.headers,p.preset_id";
    /** Profiles are always read with their route so the protocol mirror is filled. */
    private static final String PROFILE_SELECT = "SELECT m.profile_id,m.provider_id,m.route_id,m.model_id,m.display_name,m.enabled,m.context_tokens,m.input_tokens,m.max_output_tokens,"
            + "m.reasoning_levels,m.input_modalities,m.output_modalities,m.tool_capabilities,r.protocol,m.reasoning_wire,m.thinking_mode,m.thinking_budget_tokens,"
            + "m.continuation_mode,m.harness_preset,m.client_tool_calling,m.service_tier,m.pricing,m.retention,m.overrides,m.sources,m.discovered,m.probe,"
            + "m.catalog_key,m.catalog_version FROM model_profiles m JOIN provider_routes r ON r.route_id=m.route_id";

    ChatDatabase(Context context) {
        super(context, FILE_NAME, null, VERSION);
        context.deleteDatabase(LEGACY_FILE_NAME);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    /** Resets an older pre-release v1 layout (see {@link #LAYOUT_REVISION}); API keys of vanished accounts are dropped at startup. */
    @Override public void onOpen(SQLiteDatabase db) {
        if (db.isReadOnly()) return;
        long stamp = android.database.DatabaseUtils.longForQuery(db, "PRAGMA application_id", null);
        if (stamp == LAYOUT_REVISION) return;
        db.setForeignKeyConstraintsEnabled(false);
        db.beginTransaction();
        try { dropAll(db); onCreate(db); db.setTransactionSuccessful(); }
        finally { db.endTransaction(); db.setForeignKeyConstraintsEnabled(true); }
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("PRAGMA application_id=" + LAYOUT_REVISION);
        // Provider (account): one API Key in the native secure store. credential_epoch changes the deployment
        // identity whenever the key is replaced, so verifier calibration and continuation never cross keys.
        // preset_id is fixed at creation; key_status / budget are maintained by the key checks and spend guard.
        db.execSQL("CREATE TABLE providers(provider_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,preset_id TEXT NOT NULL,"
                + "credential_epoch INTEGER NOT NULL DEFAULT 0,key_status TEXT NOT NULL DEFAULT 'unknown',key_checked_at TEXT NOT NULL DEFAULT '',"
                + "budget TEXT NOT NULL DEFAULT '{}',created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        // Route: one endpoint of an account. dialect / headers are validated JSON (RouteDialect / RouteHeaders).
        // confirmed_host is the host the stored key was last confirmed for.
        db.execSQL("CREATE TABLE provider_routes(route_id TEXT PRIMARY KEY,"
                + "provider_id TEXT NOT NULL REFERENCES providers(provider_id) ON DELETE CASCADE,"
                + "preset_route TEXT NOT NULL DEFAULT '',label TEXT NOT NULL,protocol TEXT NOT NULL,base_url TEXT NOT NULL,auth_mode TEXT NOT NULL,"
                + "dialect TEXT NOT NULL DEFAULT '{}',headers TEXT NOT NULL DEFAULT '{}',confirmed_host TEXT NOT NULL DEFAULT '',"
                + "created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(provider_id,protocol,base_url))");
        // Model Profile: one concrete deployment (account + route + model id). Every capability column is the
        // resolved value; overrides / sources record which values the user set and where the rest came from.
        db.execSQL("CREATE TABLE model_profiles(profile_id TEXT PRIMARY KEY,"
                + "provider_id TEXT NOT NULL REFERENCES providers(provider_id) ON DELETE CASCADE,"
                + "route_id TEXT NOT NULL REFERENCES provider_routes(route_id) ON DELETE CASCADE,"
                + "model_id TEXT NOT NULL,display_name TEXT NOT NULL,enabled INTEGER NOT NULL DEFAULT 1,"
                + "reasoning_wire TEXT NOT NULL,"
                + "context_tokens INTEGER NOT NULL,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL,"
                + "reasoning_levels TEXT NOT NULL DEFAULT '',thinking_mode TEXT NOT NULL,thinking_budget_tokens INTEGER NOT NULL DEFAULT 0,"
                + "continuation_mode TEXT NOT NULL,input_modalities TEXT NOT NULL,output_modalities TEXT NOT NULL,"
                + "tool_capabilities TEXT NOT NULL DEFAULT '',client_tool_calling TEXT NOT NULL,harness_preset TEXT NOT NULL,"
                + "service_tier TEXT NOT NULL DEFAULT '',pricing TEXT NOT NULL DEFAULT '{}',retention TEXT NOT NULL DEFAULT '',"
                + "overrides TEXT NOT NULL DEFAULT '{}',sources TEXT NOT NULL DEFAULT '{}',discovered TEXT NOT NULL DEFAULT '{}',probe TEXT NOT NULL DEFAULT '{}',"
                + "catalog_key TEXT NOT NULL DEFAULT '',catalog_version TEXT NOT NULL DEFAULT '',"
                + "created_at TEXT NOT NULL,updated_at TEXT NOT NULL,UNIQUE(route_id,model_id))");
        // Session: identity, lineage, the deployment frozen at create/model switch, per-session choices,
        // and derived context state. Profile edits never relabel an existing Chat. provider_id / route_id
        // are plain references: deleting an account leaves its Chats readable (they must switch model to continue).
        db.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,title TEXT NOT NULL,state TEXT NOT NULL,"
                + "created_at TEXT NOT NULL,updated_at TEXT NOT NULL,preview TEXT,"
                + "draft TEXT NOT NULL DEFAULT '',draft_media TEXT NOT NULL DEFAULT '[]',"
                + "provider_id TEXT NOT NULL,route_id TEXT NOT NULL,"
                + "model_profile_id TEXT REFERENCES model_profiles(profile_id) ON DELETE SET NULL,"
                + "parent_session_id TEXT REFERENCES sessions(local_id) ON DELETE SET NULL,"
                + "origin_snapshot_id TEXT REFERENCES snapshots(snapshot_id),"
                + "model TEXT NOT NULL,protocol TEXT NOT NULL,reasoning_wire TEXT NOT NULL,"
                + "context_tokens INTEGER NOT NULL,input_tokens INTEGER NOT NULL DEFAULT 0,max_output_tokens INTEGER NOT NULL,"
                + "send_output_cap INTEGER NOT NULL DEFAULT 1,service_tier TEXT NOT NULL DEFAULT '',"
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
        // Turn: one accepted user turn. Holds the frozen turn configuration and the usage summary (the
        // timeline holds only what the user sees). Rows are owned by the session and rewound with it.
        db.execSQL("CREATE TABLE turns(turn_id TEXT PRIMARY KEY,"
                + "session_id TEXT NOT NULL REFERENCES sessions(local_id) ON DELETE CASCADE,"
                + "user_seq INTEGER NOT NULL,assistant_key TEXT NOT NULL,started_at TEXT NOT NULL,ended_at TEXT,"
                + "status TEXT NOT NULL,terminal_code TEXT NOT NULL DEFAULT '',config TEXT NOT NULL,usage TEXT NOT NULL DEFAULT '{}',"
                + "rating INTEGER CHECK(rating IN (-1,1)),rerun_of TEXT,"
                + "UNIQUE(session_id,user_seq))");
        // A re-answer points at the turn it re-asks (any session); the original may be deleted later.
        db.execSQL("CREATE INDEX turns_rerun ON turns(rerun_of)");
        db.execSQL("CREATE INDEX turns_started ON turns(started_at)");
        // Turn span: one pipeline stage of a turn (ran / skipped and why, duration, decision, helper usage).
        db.execSQL("CREATE TABLE turn_spans(turn_id TEXT NOT NULL REFERENCES turns(turn_id) ON DELETE CASCADE,"
                + "ordinal INTEGER NOT NULL,stage TEXT NOT NULL,status TEXT NOT NULL,started_at_ms INTEGER NOT NULL,duration_ms INTEGER NOT NULL,"
                + "reason TEXT NOT NULL DEFAULT '',calls INTEGER NOT NULL DEFAULT 0,input_tokens INTEGER,output_tokens INTEGER,"
                + "detail TEXT NOT NULL DEFAULT '{}',PRIMARY KEY(turn_id,ordinal))");
        // Turn payload: redacted request / response text of one model call (protocol inspector). No headers,
        // no key; bounded per record and kept only for the most recent turns.
        db.execSQL("CREATE TABLE turn_payloads(turn_id TEXT NOT NULL REFERENCES turns(turn_id) ON DELETE CASCADE,"
                + "call_index INTEGER NOT NULL,part INTEGER NOT NULL,stage TEXT NOT NULL,direction TEXT NOT NULL,"
                + "created_at TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(turn_id,call_index,part))");
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
        createTurnArchive(db);
    }

    /** Forward migrations from v1: one reviewed step per version, each with a static-check assertion. */
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) { createTurnArchive(db); migrateSpendCarry(db); }
    }

    /**
     * v2. Turn archive: the record of a turn removed from its Chat (retry, edit / rollback, deleting the Chat).
     * Statistics and budgets keep counting it; it holds no message text and no custom instruction (see
     * {@link #archiveTurns}). No foreign keys: the session is usually gone. provider_id is the budget filter.
     */
    private static void createTurnArchive(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE turn_archive(turn_id TEXT PRIMARY KEY,session_id TEXT NOT NULL,provider_id TEXT NOT NULL,"
                + "started_at TEXT NOT NULL,ended_at TEXT,status TEXT NOT NULL,terminal_code TEXT NOT NULL DEFAULT '',"
                + "config TEXT NOT NULL,usage TEXT NOT NULL DEFAULT '{}',spans TEXT NOT NULL DEFAULT '[]',rating INTEGER,"
                + "reason TEXT NOT NULL,archived_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX turn_archive_started ON turn_archive(started_at)");
    }

    /** v1 kept removed turns' spend as a per-account, per-day carry in settings; v2 turns each entry into an archive row. */
    private static void migrateSpendCarry(SQLiteDatabase db) {
        JSONObject carry;
        try { carry = new JSONObject(settingIn(db, LEGACY_SPEND_CARRY_KEY, "{}")); } catch (Exception broken) { carry = new JSONObject(); }
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        String now = Instant.now().toString();
        for (java.util.Iterator<String> accounts = carry.keys(); accounts.hasNext(); ) {
            String providerId = accounts.next();
            JSONObject days = carry.optJSONObject(providerId);
            if (days == null) continue;
            for (java.util.Iterator<String> dayKeys = days.keys(); dayKeys.hasNext(); ) {
                String day = dayKeys.next();
                JSONObject amounts = days.optJSONObject(day);
                if (amounts == null) continue;
                for (java.util.Iterator<String> currencies = amounts.keys(); currencies.hasNext(); ) {
                    String currency = currencies.next();
                    try {
                        ContentValues v = new ContentValues();
                        v.put("turn_id", "carry:" + providerId + ":" + day + ":" + currency);
                        v.put("session_id", ""); v.put("provider_id", providerId);
                        v.put("started_at", java.time.LocalDate.parse(day).atStartOfDay(zone).toInstant().toString());
                        v.put("status", ARCHIVE_CARRIED); v.put("reason", ARCHIVE_CARRIED); v.put("archived_at", now);
                        v.put("config", new JSONObject().put("providerId", providerId).toString());
                        v.put("usage", new JSONObject().put("cost", new JSONObject().put("amount", amounts.optDouble(currency, 0d)).put("currency", currency)).toString());
                        db.insertWithOnConflict("turn_archive", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                    } catch (Exception skipped) { /* a damaged entry is dropped; it only ever held an estimate */ }
                }
            }
        }
        db.delete("settings", "key=?", new String[]{LEGACY_SPEND_CARRY_KEY});
    }

    /** A beta build may meet a file from a newer experimental build; the beta line resets rather than guessing. */
    @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) { dropAll(db); onCreate(db); }

    /** Child tables first so foreign keys never block the drop (PRAGMA foreign_keys cannot change inside the upgrade transaction). */
    private static void dropAll(SQLiteDatabase db) {
        for (String table : new String[]{"turn_archive", "knowledge_fts", "snapshot_items", "turn_payloads", "turn_spans", "turns", "timeline", "handoffs", "sessions", "snapshots",
                "model_profiles", "provider_routes", "providers", "verifier_labels", "settings"}) {
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
        freezeDeployment(s, profile);
        s.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode); s.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
        s.continuationMode = ProviderContinuationPolicy.normalize(profile.continuationMode);
        s.harnessPreset = HarnessProfile.normalize(profile.harnessPreset);
        s.clientToolCalling = ClientToolRuntime.normalizeCalling(profile.clientToolCalling);
        s.webMode = ChatTurnOptions.normalizeWebMode(webMode);
        s.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
        s.reasoningEffort = ReasoningPolicy.normalizeEffort(reasoningEffort == null ? ReasoningPolicy.DEFAULT : reasoningEffort);
        if (!ReasoningPolicy.allows(s.reasoningLevels, s.reasoningEffort)) throw new IllegalArgumentException("这个会话的模型不支持所选推理深度");
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
        s.routeId = source.routeId; s.protocol = normalizeProtocol(source.protocol);
        s.reasoningWire = ReasoningPolicy.normalizeWire(source.reasoningWire, s.protocol);
        s.serviceTier = source.serviceTier == null ? "" : source.serviceTier; s.sendOutputCap = source.sendOutputCap;
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

    static final String PROCESS_DIED = "process_died";
    static final String PROCESS_DIED_SUMMARY = "应用在生成过程中被关闭，回答没有完成";

    /**
     * Cold-start recovery: no provider turn can survive process death, so every durable streaming row is stale.
     * A row that already shows text, reasoning or images is kept as an incomplete answer (the same shape
     * {@code finalizeTurn} gives a failed turn); an empty one is removed. Each affected session gets an error row.
     */
    public synchronized void recoverInterruptedSessions() {
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE turns SET status='failed',terminal_code='process_died',ended_at=? WHERE status='running'", new Object[]{Instant.now().toString()});
        java.util.LinkedHashSet<String> affected = new java.util.LinkedHashSet<>();
        try (Cursor c = db.rawQuery("SELECT local_id FROM sessions WHERE state='running'", null)) {
            while (c.moveToNext()) affected.add(c.getString(0));
        }
        ArrayList<String[]> staleRows = new ArrayList<>(); // {item_key, session_id, kept body or null}
        try (Cursor c = db.rawQuery("SELECT item_key,session_id,body FROM timeline WHERE instr(body,'\"streaming\":true')>0", null)) {
            while (c.moveToNext()) {
                try {
                    JSONObject body = new JSONObject(c.getString(2));
                    if (!body.optBoolean("streaming", false)) continue;
                    staleRows.add(new String[]{c.getString(0), c.getString(1), partialAnswer(body)});
                    affected.add(c.getString(1));
                } catch (Exception ignored) { }
            }
        }
        if (affected.isEmpty()) return;
        db.beginTransaction();
        try {
            for (String[] stale : staleRows) {
                if (stale[2] == null) { db.delete("timeline", "item_key=?", new String[]{stale[0]}); continue; }
                ContentValues row = new ContentValues(); row.put("body", stale[2]);
                db.update("timeline", row, "item_key=?", new String[]{stale[0]});
                try { updatePreviewFromBody(db, stale[1], new JSONObject(stale[2])); } catch (Exception ignored) { }
            }
            String now = Instant.now().toString();
            for (String sessionId : affected) {
                int seq;
                try (Cursor c = db.rawQuery("SELECT COALESCE(MAX(local_seq),0)+1 FROM timeline WHERE session_id=?", new String[]{sessionId})) {
                    seq = c.moveToFirst() ? c.getInt(0) : 1;
                }
                JSONObject error = new JSONObject().put("kind", "error").put("code", PROCESS_DIED)
                        .put("summary", PROCESS_DIED_SUMMARY).put("createdAt", now).put("localSeq", seq);
                ContentValues errorRow = new ContentValues(); errorRow.put("item_key", "error:" + UUID.randomUUID()); errorRow.put("session_id", sessionId);
                errorRow.put("local_seq", seq); errorRow.put("kind", "error"); errorRow.put("body", error.toString()); errorRow.put("created_at", now);
                db.insertWithOnConflict("timeline", null, errorRow, SQLiteDatabase.CONFLICT_ABORT);
                ContentValues state = new ContentValues(); state.put("state", "failed"); state.put("updated_at", now);
                db.update("sessions", state, "local_id=?", new String[]{sessionId});
            }
            db.setTransactionSuccessful();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        } finally { db.endTransaction(); }
    }

    /** The settled body for a stale streaming row that already shows something, or null when it is empty. */
    private static String partialAnswer(JSONObject body) throws org.json.JSONException {
        JSONArray media = body.optJSONArray("attachments");
        boolean shows = !body.optString("text", "").isEmpty() || !body.optString("reasoning", "").isEmpty()
                || (media != null && media.length() > 0);
        if (!shows || !"assistant".equals(body.optString("role", ""))) return null;
        JSONObject kept = new JSONObject(body.toString()).put("streaming", false).put("incomplete", true).put("terminalCode", PROCESS_DIED);
        kept.remove("streamStage");
        return kept.toString();
    }

    /** Deletes one local session and all derived lexical-index data that belongs to it. */
    public synchronized void deleteSession(String id) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("session id required");
        SQLiteDatabase db = getWritableDatabase();
        String originSnapshotId = null;
        try (Cursor c = db.rawQuery("SELECT origin_snapshot_id FROM sessions WHERE local_id=?", new String[]{id})) {
            if (!c.moveToFirst()) throw new IllegalArgumentException("会话不存在");
            if (!c.isNull(0)) originSnapshotId = c.getString(0);
        }
        db.beginTransaction();
        try {
            db.delete("knowledge_fts", "scope_id=?", new String[]{LocalKnowledgeStore.chatScope(id)});
            archiveTurns(db, "session_id=?", new String[]{id}, ARCHIVE_DELETED); // its turns go with it (ON DELETE CASCADE); their record stays
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
            if (!c.moveToFirst()) throw new IllegalArgumentException("找不到这条回答");
            JSONObject body = new JSONObject(c.getString(1));
            if (!"message".equals(body.optString("kind")) || !"assistant".equals(body.optString("role")) || body.optBoolean("streaming", false)) {
                throw new IllegalArgumentException("只能对已完成的回答反馈校验结果");
            }
            JSONObject verification = body.optJSONObject("verification");
            if (verification == null) throw new IllegalArgumentException("这条回答没有智能校验记录");
            if (verification.has("feedbackLabel")) throw new IllegalStateException("已经记录过这条回答的校验反馈");
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
        if (value.isEmpty()) throw new IllegalArgumentException("会话标题不能为空");
        if (value.length() > 200) throw new IllegalArgumentException("会话标题不能超过 200 字");
        ContentValues v = new ContentValues(); v.put("title", value);
        if (getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id}) != 1) throw new IllegalArgumentException("会话不存在");
    }

    public synchronized void setDraft(String id, String draft) { ContentValues v = new ContentValues(); v.put("draft", draft == null ? "" : draft); getWritableDatabase().update("sessions", v, "local_id=?", new String[]{id}); }

    public synchronized JSONArray draftMedia(String id) {
        Session s = getSession(id); if (s == null || s.draftMedia == null || s.draftMedia.trim().isEmpty()) return new JSONArray();
        try { return new JSONArray(s.draftMedia); } catch (Exception ignored) { return new JSONArray(); }
    }

    public synchronized void addDraftMedia(String id, JSONObject descriptor) {
        if (descriptor == null || descriptor.optString("mediaId", "").trim().isEmpty()) throw new IllegalArgumentException("image descriptor required");
        JSONArray items = draftMedia(id);
        if (items.length() >= ChatMediaStore.MAX_DRAFT_IMAGES) throw new IllegalArgumentException("每条消息最多 " + ChatMediaStore.MAX_DRAFT_IMAGES + " 张图片");
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
    public synchronized void truncateTimelineFrom(String sessionId, int localSeq) { truncateTimelineFrom(sessionId, localSeq, ARCHIVE_REWIND); }

    /**
     * Deletes the timeline item at localSeq and everything after it; the removed turns move to the archive
     * with {@code reason}. Returns the id of the first removed turn (the one being retried), or null.
     */
    public synchronized String truncateTimelineFrom(String sessionId, int localSeq, String reason) {
        if (sessionId == null || sessionId.trim().isEmpty() || localSeq < 1) throw new IllegalArgumentException("invalid rollback target");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // A turn's rows run from its user row to the next user row; rewinding into a turn removes that turn's record too.
            Integer firstRemovedTurn = null;
            try (Cursor c = db.rawQuery("SELECT MAX(user_seq) FROM turns WHERE session_id=? AND user_seq<=?", new String[]{sessionId, String.valueOf(localSeq)})) {
                if (c.moveToFirst() && !c.isNull(0)) firstRemovedTurn = c.getInt(0);
            }
            String[] removed = {sessionId, String.valueOf(firstRemovedTurn == null ? localSeq : firstRemovedTurn)};
            String firstTurnId = null;
            try (Cursor c = db.rawQuery("SELECT turn_id FROM turns WHERE session_id=? AND user_seq=?", removed)) { if (c.moveToFirst()) firstTurnId = c.getString(0); }
            archiveTurns(db, "session_id=? AND user_seq>=?", removed, reason);
            db.delete("turns", "session_id=? AND user_seq>=?", removed);
            db.delete("timeline", "session_id=? AND local_seq>=?", new String[]{sessionId, String.valueOf(localSeq)});
            db.delete("knowledge_fts", "scope_id=?", new String[]{LocalKnowledgeStore.chatScope(sessionId)});
            ContentValues state = new ContentValues();
            state.put("state", "idle"); state.put("updated_at", Instant.now().toString()); state.put("draft", ""); state.put("draft_media", "[]");
            state.put("task_profile", ""); state.put("context_checkpoint", ""); state.put("checkpoint_covered_items", 0); state.put("knowledge_indexed_at", "");
            state.putNull("preview");
            if (db.update("sessions", state, "local_id=?", new String[]{sessionId}) != 1) throw new IllegalArgumentException("会话不存在");
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
            return firstTurnId;
        } finally { db.endTransaction(); }
    }

    /** Atomically commits the user's turn, one unique live assistant projection and the turn record before network streaming starts. */
    public synchronized void beginTurn(String sessionId, String userKey, JSONObject userBody, String streamKey, JSONObject liveBody,
                                       String turnId, JSONObject config) {
        beginTurn(sessionId, userKey, userBody, streamKey, liveBody, turnId, config, "");
    }

    /** As above; the composer is left holding {@code keptDraft} instead of being cleared. */
    public synchronized void beginTurn(String sessionId, String userKey, JSONObject userBody, String streamKey, JSONObject liveBody,
                                       String turnId, JSONObject config, String keptDraft) {
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

            ContentValues turn = new ContentValues(); turn.put("turn_id", turnId); turn.put("session_id", sessionId); turn.put("user_seq", seq);
            turn.put("assistant_key", streamKey); turn.put("started_at", Instant.now().toString()); turn.put("status", "running");
            turn.put("config", config == null ? "{}" : config.toString());
            String rerunOf = config == null ? "" : config.optString("rerunOf", "");
            if (!rerunOf.isEmpty()) turn.put("rerun_of", rerunOf);
            db.insertWithOnConflict("turns", null, turn, SQLiteDatabase.CONFLICT_ABORT);

            ContentValues state = new ContentValues(); state.put("state", "running"); state.put("updated_at", Instant.now().toString());
            state.put("draft", keptDraft == null ? "" : keptDraft); state.put("draft_media", "[]");
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
                        .put("summary", errorSummary == null ? "模型服务出错" : errorSummary)
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
                        .put("summary", summary == null ? "回合在请求发出前失败" : summary)
                        .put("createdAt", Instant.now().toString()).put("localSeq", seq);
                ContentValues row = new ContentValues(); row.put("item_key", "error:" + UUID.randomUUID()); row.put("session_id", sessionId);
                row.put("local_seq", seq); row.put("kind", "error"); row.put("body", error.toString()); row.put("created_at", error.optString("createdAt"));
                db.insertWithOnConflict("timeline", null, row, SQLiteDatabase.CONFLICT_ABORT);
                ContentValues state = new ContentValues(); state.put("state", "failed"); state.put("updated_at", Instant.now().toString());
                db.update("sessions", state, "local_id=?", new String[]{sessionId});
            }
            ContentValues turn = new ContentValues(); turn.put("status", "failed"); turn.put("terminal_code", code == null ? "turn_internal_error" : code);
            turn.put("ended_at", Instant.now().toString());
            db.update("turns", turn, "session_id=? AND status='running'", new String[]{sessionId});
            db.setTransactionSuccessful();
        } catch (org.json.JSONException error) {
            throw new IllegalStateException(error);
        } finally { db.endTransaction(); }
    }

    public synchronized void deleteTimelineItem(String itemKey) { getWritableDatabase().delete("timeline", "item_key=?", new String[]{itemKey}); }
    public synchronized JSONArray timeline(String sessionId) { return timelineThrough(sessionId, null); }

    /** Usage summaries of turns started at or after {@code sinceIso} (turns_started index). Turns without model calls are left out. */
    public synchronized JSONArray usageSince(String sinceIso) {
        JSONArray out = new JSONArray();
        if (sinceIso == null) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT usage FROM turns WHERE started_at>=? AND usage<>'{}'", new String[]{sinceIso})) {
            while (c.moveToNext()) try { out.put(new JSONObject(c.getString(0))); } catch (Exception ignored) { }
        }
        return out;
    }

    /** Live usage summary of a running or finished turn. */
    public synchronized void updateTurnUsage(String turnId, JSONObject usage) {
        if (turnId == null || usage == null) return;
        ContentValues v = new ContentValues(); v.put("usage", usage.toString());
        getWritableDatabase().update("turns", v, "turn_id=?", new String[]{turnId});
    }

    /** Settles a turn record: terminal status, final usage and the stage trace ({@link TurnTrace#toJson()}). */
    public synchronized void completeTurn(String turnId, String status, String terminalCode, JSONObject usage, JSONArray spans) {
        if (turnId == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues turn = new ContentValues();
            turn.put("status", status == null || status.isEmpty() ? "failed" : status);
            turn.put("terminal_code", terminalCode == null ? "" : terminalCode);
            turn.put("ended_at", Instant.now().toString());
            if (usage != null) turn.put("usage", usage.toString());
            if (db.update("turns", turn, "turn_id=?", new String[]{turnId}) != 1) { db.setTransactionSuccessful(); return; }
            db.execSQL("DELETE FROM turn_payloads WHERE turn_id NOT IN (SELECT turn_id FROM turns ORDER BY started_at DESC LIMIT " + PAYLOAD_TURNS_KEPT + ")");
            db.delete("turn_spans", "turn_id=?", new String[]{turnId});
            if (spans != null) for (int i = 0; i < spans.length(); i++) {
                JSONObject span = spans.optJSONObject(i); if (span == null) continue;
                JSONObject spanUsage = span.optJSONObject("usage");
                ContentValues row = new ContentValues();
                row.put("turn_id", turnId); row.put("ordinal", i); row.put("stage", span.optString("stage", ""));
                row.put("status", span.optString("status", "")); row.put("started_at_ms", span.optLong("startedAtMs", 0L));
                row.put("duration_ms", span.optLong("durationMs", -1L)); row.put("reason", span.optString("reason", ""));
                row.put("calls", spanUsage == null ? 0 : spanUsage.optInt("calls", 0));
                if (spanUsage != null && spanUsage.has("inputTokens")) row.put("input_tokens", spanUsage.optLong("inputTokens"));
                if (spanUsage != null && spanUsage.has("outputTokens")) row.put("output_tokens", spanUsage.optLong("outputTokens"));
                JSONObject detail = span.optJSONObject("detail");
                if (spanUsage != null && spanUsage.optBoolean("incomplete", false)) {
                    try { detail = (detail == null ? new JSONObject() : detail).put("usageIncomplete", true); } catch (Exception ignored) { }
                }
                row.put("detail", detail == null ? "{}" : detail.toString());
                db.insertWithOnConflict("turn_spans", null, row, SQLiteDatabase.CONFLICT_ABORT);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Turns whose wire payloads are kept for the protocol inspector. */
    static final int PAYLOAD_TURNS_KEPT = 30;

    /** Appends one redacted wire record; parts number the records of one call in arrival order. */
    public synchronized void addTurnPayload(String turnId, int callIndex, String stage, String direction, String body) {
        if (turnId == null || body == null) return;
        SQLiteDatabase db = getWritableDatabase();
        int part;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM turn_payloads WHERE turn_id=? AND call_index=?", new String[]{turnId, String.valueOf(callIndex)})) {
            part = c.moveToFirst() ? c.getInt(0) : 0;
        }
        ContentValues v = new ContentValues();
        v.put("turn_id", turnId); v.put("call_index", callIndex); v.put("part", part); v.put("stage", stage == null ? "" : stage);
        v.put("direction", direction == null ? "" : direction); v.put("created_at", Instant.now().toString()); v.put("body", body);
        // A late response for a turn that was rewound or deleted has no owner any more: drop it.
        try { db.insertOrThrow("turn_payloads", null, v); } catch (android.database.SQLException ignored) { }
    }

    /** Payload calls of a turn (metadata only): [{callIndex, stage, parts, chars}]. */
    private static JSONArray payloadCalls(SQLiteDatabase db, String turnId) throws org.json.JSONException {
        JSONArray out = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT call_index,MIN(stage),COUNT(*),SUM(LENGTH(body)) FROM turn_payloads WHERE turn_id=? GROUP BY call_index ORDER BY call_index", new String[]{turnId})) {
            while (c.moveToNext()) out.put(new JSONObject().put("callIndex", c.getInt(0)).put("stage", c.getString(1)).put("parts", c.getInt(2)).put("chars", c.getLong(3)));
        }
        return out;
    }

    /** The redacted wire records of one call, in arrival order; null when the turn does not belong to the session. */
    public synchronized JSONObject turnPayload(String sessionId, String turnId, int callIndex) {
        if (sessionId == null || turnId == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor owner = db.rawQuery("SELECT 1 FROM turns WHERE session_id=? AND turn_id=?", new String[]{sessionId, turnId})) {
            if (!owner.moveToFirst()) return null;
        }
        try {
            JSONArray parts = new JSONArray();
            String stage = "";
            try (Cursor c = db.rawQuery("SELECT part,stage,direction,created_at,body FROM turn_payloads WHERE turn_id=? AND call_index=? ORDER BY part", new String[]{turnId, String.valueOf(callIndex)})) {
                while (c.moveToNext()) {
                    stage = c.getString(1);
                    parts.put(new JSONObject().put("part", c.getInt(0)).put("direction", c.getString(2)).put("createdAt", c.getString(3)).put("body", c.getString(4)));
                }
            }
            return new JSONObject().put("turnId", turnId).put("callIndex", callIndex).put("stage", stage).put("parts", parts);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    /** Turn records of a session in user-turn order: {turnId, userSeq, status, usage}. */
    public synchronized JSONArray turns(String sessionId) {
        JSONArray out = new JSONArray();
        if (sessionId == null) return out;
        // answers = this question's answers across re-answers (the original turn plus every turn that re-asks it).
        String sql = "SELECT t.turn_id,t.user_seq,t.status,t.usage,t.rating,"
                + "(SELECT COUNT(*) FROM turns g WHERE g.turn_id=COALESCE(t.rerun_of,t.turn_id) OR g.rerun_of=COALESCE(t.rerun_of,t.turn_id))"
                + " FROM turns t WHERE t.session_id=? ORDER BY t.user_seq";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{sessionId})) {
            while (c.moveToNext()) try {
                JSONObject turn = new JSONObject().put("turnId", c.getString(0)).put("userSeq", c.getInt(1)).put("status", c.getString(2))
                        .put("usage", new JSONObject(c.getString(3))).put("answers", c.getInt(5));
                if (!c.isNull(4)) turn.put("rating", c.getInt(4));
                out.put(turn);
            } catch (Exception ignored) { }
        }
        return out;
    }

    /** The turn record that started at the given user row, or null. */
    public synchronized String turnAt(String sessionId, int userSeq) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT turn_id FROM turns WHERE session_id=? AND user_seq=?", new String[]{sessionId, String.valueOf(userSeq)})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** 1 = good answer, -1 = bad answer, 0 clears the rating. */
    public synchronized void rateTurn(String sessionId, String turnId, int rating) {
        if (!(rating == 1 || rating == -1 || rating == 0)) throw new IllegalArgumentException("rating must be 1, -1 or 0");
        ContentValues v = new ContentValues();
        if (rating == 0) v.putNull("rating"); else v.put("rating", rating);
        if (getWritableDatabase().update("turns", v, "session_id=? AND turn_id=?", new String[]{sessionId, turnId}) != 1) throw new IllegalArgumentException("找不到这一轮的记录");
    }

    /**
     * Every answer to one question: the original turn and all turns that re-ask it, oldest first, with
     * the session, the turn's config and usage, its rating, verification summary and answer text.
     */
    public synchronized JSONObject answerGroup(String sessionId, String turnId) {
        SQLiteDatabase db = getReadableDatabase();
        try {
            String root;
            try (Cursor c = db.rawQuery("SELECT COALESCE(rerun_of,turn_id) FROM turns WHERE session_id=? AND turn_id=?", new String[]{sessionId, turnId})) {
                if (!c.moveToFirst()) return null;
                root = c.getString(0);
            }
            JSONArray answers = new JSONArray();
            String question = "";
            String sql = "SELECT t.turn_id,t.session_id,s.title,t.user_seq,t.assistant_key,t.status,t.terminal_code,t.config,t.usage,t.rating,t.started_at"
                    + " FROM turns t JOIN sessions s ON s.local_id=t.session_id WHERE t.turn_id=? OR t.rerun_of=? ORDER BY t.started_at";
            try (Cursor c = db.rawQuery(sql, new String[]{root, root})) {
                while (c.moveToNext()) {
                    JSONObject answer = new JSONObject().put("turnId", c.getString(0)).put("localSessionId", c.getString(1)).put("sessionTitle", c.getString(2))
                            .put("userSeq", c.getInt(3)).put("status", c.getString(5)).put("terminalCode", c.getString(6))
                            .put("config", new JSONObject(c.getString(7))).put("usage", new JSONObject(c.getString(8))).put("startedAt", c.getString(10))
                            .put("original", c.getString(0).equals(root));
                    if (!c.isNull(9)) answer.put("rating", c.getInt(9));
                    try (Cursor body = db.rawQuery("SELECT body FROM timeline WHERE item_key=?", new String[]{c.getString(4)})) {
                        if (body.moveToFirst()) {
                            JSONObject item = new JSONObject(body.getString(0));
                            answer.put("text", item.optString("text", ""));
                            JSONObject verification = item.optJSONObject("verification");
                            if (verification != null) answer.put("verification", new JSONObject().put("verdict", verification.optString("verdict", ""))
                                    .put("action", verification.optString("action", "")).put("repaired", verification.optBoolean("repaired", false)));
                        }
                    }
                    if (question.isEmpty()) try (Cursor user = db.rawQuery("SELECT body FROM timeline WHERE session_id=? AND local_seq=?", new String[]{c.getString(1), String.valueOf(c.getInt(3))})) {
                        if (user.moveToFirst()) question = new JSONObject(user.getString(0)).optString("text", "");
                    }
                    answers.put(answer);
                }
            }
            return new JSONObject().put("rootTurnId", root).put("question", question).put("answers", answers);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    /** Per model × context mode statistics of turns started at or after {@code sinceIso}. */
    public synchronized JSONArray turnStats(String sinceIso) { return turnStats(sinceIso, null); }

    /** Finished turns since {@code sinceIso}, grouped by {@code dims} (see TurnStats.GROUPINGS), archived turns included. */
    public synchronized JSONArray turnStats(String sinceIso, String[] dims) { return turnStats(sinceIso, dims, true); }

    /** Finished turns since {@code sinceIso}, grouped by {@code dims}; with {@code includeArchived}, also turns since retried, rewound or deleted. */
    public synchronized JSONArray turnStats(String sinceIso, String[] dims, boolean includeArchived) {
        TurnStats stats = new TurnStats(dims);
        SQLiteDatabase db = getReadableDatabase();
        String sql = "SELECT t.turn_id,t.config,t.usage,t.status,t.rating,s.stage,s.status,s.input_tokens,s.output_tokens,s.detail"
                + " FROM turns t LEFT JOIN turn_spans s ON s.turn_id=t.turn_id WHERE t.started_at>=? AND t.status<>'running' ORDER BY t.turn_id,s.ordinal";
        try (Cursor c = db.rawQuery(sql, new String[]{sinceIso == null ? "" : sinceIso})) {
            String current = null; JSONObject config = null, usage = null; String status = null; Integer rating = null;
            java.util.ArrayList<JSONObject> spans = new java.util.ArrayList<>();
            while (c.moveToNext()) {
                String turnId = c.getString(0);
                if (!turnId.equals(current)) {
                    if (current != null) stats.add(config, usage, status, rating, spans);
                    current = turnId; spans = new java.util.ArrayList<>();
                    config = new JSONObject(c.getString(1)); usage = new JSONObject(c.getString(2)); status = c.getString(3);
                    rating = c.isNull(4) ? null : c.getInt(4);
                }
                if (c.isNull(5)) continue;
                JSONObject span = new JSONObject().put("stage", c.getString(5)).put("status", c.getString(6)).put("detail", new JSONObject(c.getString(9)));
                if (!c.isNull(7)) span.put("inputTokens", c.getLong(7));
                if (!c.isNull(8)) span.put("outputTokens", c.getLong(8));
                spans.add(span);
            }
            if (current != null) stats.add(config, usage, status, rating, spans);
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        if (includeArchived) {
            String archived = "SELECT config,usage,status,rating,spans FROM turn_archive WHERE started_at>=? AND reason<>? AND status<>'running'";
            try (Cursor c = db.rawQuery(archived, new String[]{sinceIso == null ? "" : sinceIso, ARCHIVE_CARRIED})) {
                while (c.moveToNext()) {
                    try {
                        JSONArray stored = new JSONArray(c.getString(4));
                        java.util.ArrayList<JSONObject> spans = new java.util.ArrayList<>();
                        for (int i = 0; i < stored.length(); i++) { JSONObject span = stored.optJSONObject(i); if (span != null) spans.add(span); }
                        stats.addArchived(new JSONObject(c.getString(0)), new JSONObject(c.getString(1)), c.getString(2), c.isNull(3) ? null : c.getInt(3), spans);
                    } catch (org.json.JSONException skipped) { }
                }
            }
        }
        try {
            return stats.toJson();
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    /** One turn with its configuration, usage and ordered stage spans; null when the turn does not belong to the session. */
    public synchronized JSONObject turnTrace(String sessionId, String turnId) {
        if (sessionId == null || turnId == null) return null;
        SQLiteDatabase db = getReadableDatabase();
        try {
            JSONObject turn;
            try (Cursor c = db.rawQuery("SELECT turn_id,user_seq,started_at,ended_at,status,terminal_code,config,usage FROM turns WHERE session_id=? AND turn_id=?", new String[]{sessionId, turnId})) {
                if (!c.moveToFirst()) return null;
                turn = new JSONObject().put("turnId", c.getString(0)).put("userSeq", c.getInt(1)).put("startedAt", c.getString(2))
                        .put("endedAt", c.isNull(3) ? JSONObject.NULL : c.getString(3)).put("status", c.getString(4))
                        .put("terminalCode", c.getString(5)).put("config", new JSONObject(c.getString(6))).put("usage", new JSONObject(c.getString(7)));
            }
            String retryOf = turn.getJSONObject("config").optString("retryOf", "");
            JSONObject previous = retryOf.isEmpty() ? null : archivedTurn(retryOf);
            if (previous != null) turn.put("previousAttempt", previous);
            JSONArray spans = new JSONArray();
            try (Cursor c = db.rawQuery("SELECT stage,status,started_at_ms,duration_ms,reason,calls,input_tokens,output_tokens,detail FROM turn_spans WHERE turn_id=? ORDER BY ordinal", new String[]{turnId})) {
                while (c.moveToNext()) {
                    JSONObject span = new JSONObject().put("stage", c.getString(0)).put("status", c.getString(1))
                            .put("startedAtMs", c.getLong(2)).put("durationMs", c.getLong(3));
                    if (!c.getString(4).isEmpty()) span.put("reason", c.getString(4));
                    if (c.getInt(5) > 0) {
                        JSONObject usage = new JSONObject().put("calls", c.getInt(5));
                        if (!c.isNull(6)) usage.put("inputTokens", c.getLong(6));
                        if (!c.isNull(7)) usage.put("outputTokens", c.getLong(7));
                        span.put("usage", usage);
                    }
                    JSONObject detail = new JSONObject(c.getString(8));
                    if (detail.length() > 0) span.put("detail", detail);
                    spans.put(span);
                }
            }
            return turn.put("spans", spans).put("calls", payloadCalls(db, turnId));
        } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
    }

    /** Timeline rows after {@code afterSeq}, in order (incremental knowledge indexing). */
    public synchronized JSONArray timelineAfter(String sessionId, int afterSeq) {
        JSONArray out = new JSONArray();
        if (sessionId == null) return out;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT body FROM timeline WHERE session_id=? AND local_seq>? ORDER BY local_seq",
                new String[]{sessionId, String.valueOf(afterSeq)})) {
            while (c.moveToNext()) try { out.put(new JSONObject(c.getString(0))); } catch (Exception ignored) { }
        }
        return out;
    }

    /** Newest timeline sequence of every session that has one. */
    public synchronized Map<String, Integer> latestTimelineSeqs() {
        Map<String, Integer> out = new HashMap<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT session_id,MAX(local_seq) FROM timeline GROUP BY session_id", null)) {
            while (c.moveToNext()) out.put(c.getString(0), c.getInt(1));
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

    /** Records the outcome of a key check or of a turn; returns false when nothing changed. */
    public synchronized boolean setKeyStatus(String providerId, String status, String checkedAt) {
        if (providerId == null || status == null) return false;
        Provider current = getProvider(providerId);
        if (current == null) return false;
        if (status.equals(current.keyStatus) && (checkedAt == null || checkedAt.equals(current.keyCheckedAt))) return false;
        ContentValues v = new ContentValues(); v.put("key_status", status);
        if (checkedAt != null) v.put("key_checked_at", checkedAt);
        return getWritableDatabase().update("providers", v, "provider_id=?", new String[]{providerId}) == 1;
    }

    /** Settings key of the v1 spend carry, migrated into turn_archive by the v2 step and then removed. */
    static final String LEGACY_SPEND_CARRY_KEY = "spendCarry";
    /** Why a turn was archived: retried, rewound by an edit / rollback, its Chat deleted, or a migrated v1 spend carry. */
    static final String ARCHIVE_RETRY = "retry", ARCHIVE_REWIND = "rewind", ARCHIVE_DELETED = "session_deleted", ARCHIVE_CARRIED = "carried";
    /** Archive rows kept beyond the budget window; older rows past this count are dropped, oldest first. */
    static final int ARCHIVE_KEEP_ROWS = 5000;
    /** Rows this recent are never dropped: enough for the monthly budget window. */
    static final int ARCHIVE_BUDGET_DAYS = 62;

    /**
     * Moves the record of the turns matching {@code where} into turn_archive before they are deleted, so statistics
     * and budgets still count them. Only attribution and measurements are kept: the custom instruction becomes its
     * length, and each stage keeps its status, duration, token counts, a reason code and numeric / yes-no details.
     */
    private void archiveTurns(SQLiteDatabase db, String where, String[] args, String reason) {
        String now = Instant.now().toString();
        List<ContentValues> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT turn_id,session_id,started_at,ended_at,status,terminal_code,config,usage,rating FROM turns WHERE " + where, args)) {
            while (c.moveToNext()) {
                try {
                    JSONObject config = archivedConfig(new JSONObject(c.getString(6)));
                    ContentValues v = new ContentValues();
                    v.put("turn_id", c.getString(0)); v.put("session_id", c.getString(1));
                    v.put("provider_id", config.optString("providerId", ""));
                    v.put("started_at", c.getString(2));
                    if (!c.isNull(3)) v.put("ended_at", c.getString(3));
                    v.put("status", c.getString(4)); v.put("terminal_code", c.getString(5));
                    v.put("config", config.toString()); v.put("usage", c.getString(7));
                    if (!c.isNull(8)) v.put("rating", c.getInt(8));
                    v.put("reason", reason); v.put("archived_at", now);
                    rows.add(v);
                } catch (Exception skipped) { /* an unreadable record cannot be attributed; it goes with its turn */ }
            }
        }
        for (ContentValues v : rows) {
            v.put("spans", archivedSpans(db, v.getAsString("turn_id")).toString());
            db.insertWithOnConflict("turn_archive", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        if (rows.isEmpty()) return;
        String keepSince = Instant.now().minus(java.time.Duration.ofDays(ARCHIVE_BUDGET_DAYS)).toString();
        db.execSQL("DELETE FROM turn_archive WHERE started_at<? AND turn_id IN (SELECT turn_id FROM turn_archive ORDER BY started_at DESC LIMIT -1 OFFSET ?)",
                new Object[]{keepSince, ARCHIVE_KEEP_ROWS});
    }

    /** The turn config without the custom instruction text (kept as its length). */
    static JSONObject archivedConfig(JSONObject config) throws org.json.JSONException {
        JSONObject out = new JSONObject(config.toString());
        String instruction = out.optString("customInstruction", "");
        out.remove("customInstruction");
        if (!instruction.isEmpty()) out.put("customInstructionChars", instruction.length());
        return out;
    }

    private static final java.util.regex.Pattern REASON_CODE = java.util.regex.Pattern.compile("[a-z0-9_]{1,64}");

    /** Stage summaries of a turn: no free text (queries, judgements, error messages) survives, only codes and numbers. */
    private static JSONArray archivedSpans(SQLiteDatabase db, String turnId) {
        JSONArray out = new JSONArray();
        try (Cursor c = db.rawQuery("SELECT stage,status,duration_ms,reason,calls,input_tokens,output_tokens,detail FROM turn_spans WHERE turn_id=? ORDER BY ordinal", new String[]{turnId})) {
            while (c.moveToNext()) {
                try {
                    JSONObject span = new JSONObject().put("stage", c.getString(0)).put("status", c.getString(1)).put("durationMs", c.getLong(2));
                    if (REASON_CODE.matcher(c.getString(3)).matches()) span.put("reason", c.getString(3));
                    if (c.getInt(4) > 0) span.put("calls", c.getInt(4));
                    if (!c.isNull(5)) span.put("inputTokens", c.getLong(5));
                    if (!c.isNull(6)) span.put("outputTokens", c.getLong(6));
                    JSONObject detail = new JSONObject(c.getString(7)), kept = new JSONObject();
                    for (java.util.Iterator<String> keys = detail.keys(); keys.hasNext(); ) {
                        String key = keys.next(); Object value = detail.opt(key);
                        if (value instanceof Boolean || value instanceof Number) kept.put(key, value);
                    }
                    span.put("detail", kept);
                    out.put(span);
                } catch (Exception skipped) { }
            }
        }
        return out;
    }

    private static String settingIn(SQLiteDatabase db, String key, String fallback) {
        try (Cursor c = db.rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) { return c.moveToFirst() ? c.getString(0) : fallback; }
    }

    /** An archived turn's outcome for the trace of the turn that retried it; null when unknown. */
    public synchronized JSONObject archivedTurn(String turnId) {
        if (turnId == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT turn_id,started_at,ended_at,status,terminal_code,config,usage,reason FROM turn_archive WHERE turn_id=?", new String[]{turnId})) {
            if (!c.moveToFirst()) return null;
            JSONObject config = new JSONObject(c.getString(5));
            return new JSONObject().put("turnId", c.getString(0)).put("startedAt", c.getString(1))
                    .put("endedAt", c.isNull(2) ? JSONObject.NULL : c.getString(2)).put("status", c.getString(3))
                    .put("terminalCode", c.getString(4)).put("attempt", Math.max(1, config.optInt("attempt", 1)))
                    .put("usage", new JSONObject(c.getString(6))).put("reason", c.getString(7));
        } catch (org.json.JSONException error) { return null; }
    }

    /**
     * Estimated spend of an account's turns started at or after {@code sinceIso}, per currency: live turns
     * (turns.config names the account; turns.usage carries the estimate) and archived ones. Turns without a
     * price add nothing.
     */
    public synchronized JSONObject spendSince(String providerId, String sinceIso) {
        JSONObject out = new JSONObject();
        if (providerId == null || sinceIso == null) return out;
        String sql = "SELECT config,usage FROM turns WHERE started_at>=? AND instr(usage,'\"cost\"')>0"
                + " UNION ALL SELECT config,usage FROM turn_archive WHERE started_at>=? AND provider_id=? AND instr(usage,'\"cost\"')>0";
        try (Cursor c = getReadableDatabase().rawQuery(sql, new String[]{sinceIso, sinceIso, providerId})) {
            while (c.moveToNext()) {
                try {
                    if (!providerId.equals(new JSONObject(c.getString(0)).optString("providerId", ""))) continue;
                    JSONObject cost = new JSONObject(c.getString(1)).optJSONObject("cost");
                    if (cost == null || !cost.has("amount")) continue;
                    String currency = cost.optString("currency", "USD");
                    out.put(currency, out.optDouble(currency, 0d) + cost.optDouble("amount", 0d));
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    /** Saves the account row only (routes and models untouched). */
    public synchronized void saveProvider(Provider p) {
        if (p == null || p.id == null || p.id.trim().isEmpty()) throw new IllegalArgumentException("provider required");
        upsert(getWritableDatabase(), "providers", "provider_id", p.id, providerValues(p));
    }

    /**
     * Atomically saves an account, its complete route set and the profiles re-resolved for it. Routes
     * missing from {@code routes} are deleted together with their models (ON DELETE CASCADE) before the
     * set is written, so a route may take over a removed route's protocol + Base URL.
     */
    public synchronized void saveProviderRoutes(Provider p, List<Route> routes, List<ModelProfile> profiles) {
        if (p == null || p.id == null || p.id.trim().isEmpty()) throw new IllegalArgumentException("provider required");
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            upsert(db, "providers", "provider_id", p.id, providerValues(p));
            java.util.HashSet<String> keep = new java.util.HashSet<>();
            if (routes != null) for (Route route : routes) {
                if (!p.id.equals(route.providerId)) throw new IllegalArgumentException("route belongs to another provider");
                keep.add(route.id);
            }
            if (routes != null) for (Route existing : listRoutes(db, p.id)) {
                if (!keep.contains(existing.id)) db.delete("provider_routes", "route_id=?", new String[]{existing.id});
            }
            if (routes != null) for (Route route : routes) upsert(db, "provider_routes", "route_id", route.id, routeValues(route));
            if (profiles != null) for (ModelProfile profile : profiles) {
                if (profile == null || !keep.contains(profile.routeId)) continue;
                if (!p.id.equals(profile.providerId)) throw new IllegalArgumentException("model profile belongs to another provider");
                upsert(db, "model_profiles", "profile_id", profile.id, modelProfileValues(profile));
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Deletes an account with its routes and models. Chats stay (readable; they switch model to continue). */
    public synchronized void deleteProvider(String providerId) {
        if (providerId == null) return;
        getWritableDatabase().delete("providers", "provider_id=?", new String[]{providerId});
    }

    public synchronized void deleteModelProfile(String profileId) {
        if (profileId == null) return;
        getWritableDatabase().delete("model_profiles", "profile_id=?", new String[]{profileId});
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
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + PROVIDER_COLUMNS + " FROM providers WHERE provider_id=?", new String[]{id})) {
            return c.moveToFirst() ? readProvider(c) : null;
        }
    }

    public synchronized List<Provider> listProviders() {
        ArrayList<Provider> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + PROVIDER_COLUMNS + " FROM providers ORDER BY created_at,display_name", null)) {
            while (c.moveToNext()) out.add(readProvider(c));
        }
        return out;
    }

    public synchronized Route getRoute(String routeId) {
        if (routeId == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT " + ROUTE_COLUMNS + " FROM provider_routes r JOIN providers p ON p.provider_id=r.provider_id WHERE r.route_id=?", new String[]{routeId})) {
            return c.moveToFirst() ? readRoute(c) : null;
        }
    }

    public synchronized List<Route> listRoutes(String providerId) { return listRoutes(getReadableDatabase(), providerId); }

    private static List<Route> listRoutes(SQLiteDatabase db, String providerId) {
        ArrayList<Route> out = new ArrayList<>();
        if (providerId == null) return out;
        try (Cursor c = db.rawQuery("SELECT " + ROUTE_COLUMNS + " FROM provider_routes r JOIN providers p ON p.provider_id=r.provider_id WHERE r.provider_id=? ORDER BY r.created_at,r.rowid", new String[]{providerId})) {
            while (c.moveToNext()) out.add(readRoute(c));
        }
        return out;
    }

    /** Records the host the account's key was confirmed for on this route. */
    public synchronized void confirmRouteHost(String routeId, String host) {
        ContentValues v = new ContentValues(); v.put("confirmed_host", host == null ? "" : host);
        getWritableDatabase().update("provider_routes", v, "route_id=?", new String[]{routeId});
    }

    public synchronized String confirmedHost(String routeId) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT confirmed_host FROM provider_routes WHERE route_id=?", new String[]{routeId})) {
            return c.moveToFirst() ? c.getString(0) : "";
        }
    }

    public synchronized void saveModelProfile(ModelProfile profile) {
        if (profile == null) throw new IllegalArgumentException("model profile required");
        upsert(getWritableDatabase(), "model_profiles", "profile_id", profile.id, modelProfileValues(profile));
    }

    public synchronized ModelProfile getModelProfile(String id) {
        if (id == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery(PROFILE_SELECT + " WHERE m.profile_id=?", new String[]{id})) {
            return c.moveToFirst() ? readModelProfile(c) : null;
        }
    }

    /** The profile for {@code modelId} on the account's first route that serves it. */
    public synchronized ModelProfile findModelProfile(String providerId, String modelId) {
        if (providerId == null || modelId == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery(PROFILE_SELECT + " WHERE m.provider_id=? AND m.model_id=? ORDER BY r.created_at", new String[]{providerId, modelId})) {
            return c.moveToFirst() ? readModelProfile(c) : null;
        }
    }

    public synchronized ModelProfile findModelProfileOnRoute(String routeId, String modelId) {
        if (routeId == null || modelId == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery(PROFILE_SELECT + " WHERE m.route_id=? AND m.model_id=?", new String[]{routeId, modelId})) {
            return c.moveToFirst() ? readModelProfile(c) : null;
        }
    }

    public synchronized List<ModelProfile> listModelProfiles(String providerId) {
        ArrayList<ModelProfile> out = new ArrayList<>();
        if (providerId == null) return out;
        try (Cursor c = getReadableDatabase().rawQuery(PROFILE_SELECT + " WHERE m.provider_id=? ORDER BY m.display_name,m.model_id", new String[]{providerId})) {
            while (c.moveToNext()) out.add(readModelProfile(c));
        }
        return out;
    }

    public synchronized List<ModelProfile> listAllModelProfiles() {
        ArrayList<ModelProfile> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(PROFILE_SELECT + " ORDER BY m.provider_id,m.display_name", null)) {
            while (c.moveToNext()) out.add(readModelProfile(c));
        }
        return out;
    }

    /** First enabled profile of the account (the default when a Chat is created without a model). */
    public synchronized ModelProfile firstModelProfile(String providerId) {
        for (ModelProfile profile : listModelProfiles(providerId)) if (profile.enabled) return profile;
        return null;
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
        if (s.routeId == null || s.routeId.trim().isEmpty()) throw new IllegalArgumentException("session route required");
        v.put("route_id", s.routeId);
        v.put("context_tokens", s.contextTokens > 0 ? s.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS);
        v.put("input_tokens", Math.max(0, s.inputTokens));
        v.put("max_output_tokens", s.maxOutputTokens > 0 ? s.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS);
        v.put("send_output_cap", s.sendOutputCap ? 1 : 0);
        v.put("service_tier", s.serviceTier == null ? "" : s.serviceTier);
        v.put("reasoning_effort", ReasoningPolicy.normalizeEffort(s.reasoningEffort));
        v.put("reasoning_levels", ReasoningPolicy.normalizeLevels(s.reasoningLevels));
        v.put("input_modalities", normalizeModalities(s.inputModalities)); v.put("output_modalities", normalizeModalities(s.outputModalities));
        v.put("tool_capabilities", ChatTurnOptions.normalizeCapabilities(s.toolCapabilities));
        String protocol = normalizeProtocol(s.protocol);
        v.put("protocol", protocol);
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
        v.put("provider_id", p.id); v.put("display_name", p.displayName);
        v.put("preset_id", p.presetId == null || p.presetId.trim().isEmpty() ? ProviderPresets.CUSTOM_OPENAI : p.presetId.trim());
        v.put("key_status", p.keyStatus == null ? "unknown" : p.keyStatus);
        v.put("key_checked_at", p.keyCheckedAt == null ? "" : p.keyCheckedAt);
        v.put("budget", p.budget == null || p.budget.trim().isEmpty() ? "{}" : p.budget);
        v.put("updated_at", Instant.now().toString());
        return v;
    }

    private static ContentValues routeValues(Route r) {
        if (r.id == null || r.id.trim().isEmpty() || r.providerId == null) throw new IllegalArgumentException("route id/provider required");
        String protocol = normalizeProtocol(r.protocol);
        ContentValues v = new ContentValues();
        v.put("route_id", r.id); v.put("provider_id", r.providerId);
        v.put("preset_route", r.presetRouteId == null ? "" : r.presetRouteId);
        v.put("label", r.label == null || r.label.trim().isEmpty() ? protocol : r.label.trim());
        v.put("protocol", protocol);
        v.put("base_url", ProviderEndpoint.normalize(r.baseUrl, protocol));
        v.put("auth_mode", ProviderAuth.normalize(r.authMode, protocol));
        v.put("dialect", RouteDialect.parse(protocol, r.dialect).toJson(protocol).toString());
        v.put("headers", RouteHeaders.parse(r.headers).toString());
        v.put("updated_at", Instant.now().toString());
        return v;
    }

    private static ContentValues modelProfileValues(ModelProfile p) {
        if (p.id == null || p.id.trim().isEmpty() || p.providerId == null || p.providerId.trim().isEmpty()) throw new IllegalArgumentException("model profile id/provider required");
        if (p.routeId == null || p.routeId.trim().isEmpty()) throw new IllegalArgumentException("model profile route required");
        String modelId = p.modelId == null ? "" : p.modelId.trim(); if (modelId.isEmpty()) throw new IllegalArgumentException("model id required");
        int context = p.contextTokens > 0 ? p.contextTokens : ContextBudgetManager.DEFAULT_CONTEXT_TOKENS;
        int input = Math.max(0, p.inputTokens);
        int output = p.maxOutputTokens > 0 ? p.maxOutputTokens : ContextBudgetManager.DEFAULT_OUTPUT_TOKENS;
        if (context < 2_048 || context > 4_000_000) throw new IllegalArgumentException("context tokens out of range");
        if (input > context) throw new IllegalArgumentException("input tokens out of range");
        if (output < 256 || output >= context) throw new IllegalArgumentException("max output tokens out of range");
        String levels = ReasoningPolicy.normalizeLevels(p.reasoningLevels);
        String protocol = normalizeProtocol(p.protocol);
        String reasoningWire = ReasoningPolicy.normalizeWire(p.reasoningWire, protocol);
        String thinkingMode = ReasoningPolicy.normalizeThinkingMode(p.thinkingMode);
        String continuationMode = ProviderContinuationPolicy.normalize(p.continuationMode);
        int thinkingBudget = Math.max(0, p.thinkingBudgetTokens);
        if (ReasoningPolicy.THINKING_MANUAL.equals(thinkingMode)) {
            if (thinkingBudget < 1024 || thinkingBudget >= output) throw new IllegalArgumentException("手动思考预算必须 ≥1024 且小于最大输出 token");
        } else thinkingBudget = 0;
        ContentValues v = new ContentValues();
        v.put("profile_id", p.id); v.put("provider_id", p.providerId); v.put("route_id", p.routeId); v.put("model_id", modelId);
        v.put("display_name", p.displayName == null || p.displayName.trim().isEmpty() ? modelId : p.displayName.trim());
        v.put("enabled", p.enabled ? 1 : 0);
        v.put("context_tokens", context); v.put("input_tokens", input); v.put("max_output_tokens", output);
        v.put("reasoning_levels", levels);
        v.put("input_modalities", normalizeModalities(p.inputModalities)); v.put("output_modalities", normalizeModalities(p.outputModalities));
        v.put("tool_capabilities", ChatTurnOptions.normalizeCapabilities(p.toolCapabilities));
        v.put("reasoning_wire", reasoningWire);
        v.put("thinking_mode", thinkingMode); v.put("thinking_budget_tokens", thinkingBudget);
        v.put("continuation_mode", continuationMode);
        v.put("harness_preset", HarnessProfile.normalize(p.harnessPreset));
        v.put("client_tool_calling", ClientToolRuntime.normalizeCalling(p.clientToolCalling));
        v.put("service_tier", p.serviceTier == null ? "" : p.serviceTier.trim());
        v.put("pricing", jsonText(p.pricing)); v.put("retention", p.retention == null ? "" : p.retention.trim());
        v.put("overrides", jsonText(p.overrides)); v.put("sources", jsonText(p.sources));
        v.put("discovered", jsonText(p.discovered)); v.put("probe", jsonText(p.probe));
        v.put("catalog_key", p.catalogKey == null ? "" : p.catalogKey.trim());
        v.put("catalog_version", p.catalogVersion == null ? "" : p.catalogVersion.trim());
        v.put("updated_at", Instant.now().toString());
        return v;
    }

    /** JSON object columns: stored canonical, never free text. */
    private static String jsonText(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "{}";
        try { return new JSONObject(raw).toString(); }
        catch (org.json.JSONException error) { throw new IllegalArgumentException("invalid JSON column"); }
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

    /** Resolve one request-local deployment snapshot from the account, its route and a model profile. */
    static Provider deploymentProvider(Provider shared, Route route, ModelProfile profile) {
        if (shared == null || route == null || profile == null) throw new IllegalArgumentException("provider/route/model profile required");
        if (!route.id.equals(profile.routeId)) throw new IllegalArgumentException("model profile belongs to another route");
        Provider out = routeView(shared, route);
        out.model = profile.modelId;
        out.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, out.protocol);
        out.contextTokens = profile.contextTokens; out.inputTokens = profile.inputTokens; out.maxOutputTokens = profile.maxOutputTokens;
        out.thinkingMode = ReasoningPolicy.normalizeThinkingMode(profile.thinkingMode); out.thinkingBudgetTokens = Math.max(0, profile.thinkingBudgetTokens);
        out.reasoningLevels = ReasoningPolicy.normalizeLevels(profile.reasoningLevels);
        out.serviceTier = profile.serviceTier == null ? "" : profile.serviceTier;
        out.sendOutputCap = DeploymentResolver.sendsOutputCap(out.protocol, profile.sources);
        return out;
    }

    /**
     * Session form is authoritative after creation/model switch so later ModelProfile edits cannot relabel
     * an old Chat; the route (endpoint) is read live. A route whose protocol no longer matches is refused.
     */
    static Provider deploymentProvider(Provider shared, Route route, Session session) {
        if (shared == null || route == null || session == null) throw new IllegalArgumentException("provider/route/session required");
        if (!normalizeProtocol(session.protocol).equals(normalizeProtocol(route.protocol))) throw new IllegalStateException("route protocol changed");
        Provider out = routeView(shared, route);
        out.model = session.model;
        out.reasoningWire = ReasoningPolicy.normalizeWire(session.reasoningWire, out.protocol);
        out.contextTokens = session.contextTokens; out.inputTokens = session.inputTokens; out.maxOutputTokens = session.maxOutputTokens;
        out.thinkingMode = ReasoningPolicy.normalizeThinkingMode(session.thinkingMode); out.thinkingBudgetTokens = Math.max(0, session.thinkingBudgetTokens);
        out.reasoningLevels = ReasoningPolicy.normalizeLevels(session.reasoningLevels);
        out.serviceTier = session.serviceTier == null ? "" : session.serviceTier;
        out.sendOutputCap = session.sendOutputCap || AnthropicMessagesTransport.PROTOCOL.equals(out.protocol);
        return out;
    }

    private static Provider routeView(Provider shared, Route route) {
        Provider out = new Provider();
        out.id = shared.id; out.displayName = shared.displayName; out.presetId = shared.presetId;
        out.routeId = route.id; out.baseUrl = route.baseUrl;
        out.protocol = normalizeProtocol(route.protocol);
        out.authMode = ProviderAuth.normalize(route.authMode, out.protocol);
        out.dialect = RouteDialect.parse(out.protocol, route.dialect);
        out.headers = RouteHeaders.parse(route.headers);
        return out;
    }

    /** The session's request view (account + live route + frozen deployment), or null when the account or route is gone. */
    synchronized Provider sessionDeployment(Session session) {
        if (session == null) return null;
        Provider shared = getProvider(session.providerId);
        Route route = getRoute(session.routeId);
        if (shared == null || route == null) return null;
        try { return deploymentProvider(shared, route, session); }
        catch (RuntimeException invalid) { return null; }
    }

    /** Copies the deployment fields a Chat freezes from its profile (create and model switch). */
    static void freezeDeployment(Session s, ModelProfile profile) {
        s.routeId = profile.routeId;
        s.protocol = normalizeProtocol(profile.protocol);
        s.reasoningWire = ReasoningPolicy.normalizeWire(profile.reasoningWire, s.protocol);
        s.serviceTier = profile.serviceTier == null ? "" : profile.serviceTier;
        s.sendOutputCap = DeploymentResolver.sendsOutputCap(s.protocol, profile.sources);
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
        s.protocol = normalizeProtocol(c.getString(24)); s.routeId = c.getString(25);
        s.reasoningWire = ReasoningPolicy.normalizeWire(c.getString(26), s.protocol); s.thinkingMode = ReasoningPolicy.normalizeThinkingMode(c.getString(27));
        s.thinkingBudgetTokens = Math.max(0, c.getInt(28)); s.continuationMode = ProviderContinuationPolicy.normalize(c.getString(29));
        s.harnessPreset = HarnessProfile.normalize(c.getString(30)); s.clientToolCalling = ClientToolRuntime.normalizeCalling(c.getString(31));
        s.subagentMode = HarnessPolicyResolver.normalizeSubagentMode(c.getString(32)); s.reasoningLevels = ReasoningPolicy.normalizeLevels(c.getString(33));
        s.createdAt = c.getString(34); s.sendOutputCap = c.getInt(35) != 0; s.serviceTier = c.getString(36);
        return s;
    }

    private static Provider readProvider(Cursor c) {
        Provider p = new Provider();
        p.id = c.getString(0); p.displayName = c.getString(1); p.presetId = c.getString(2);
        p.keyStatus = c.getString(3); p.keyCheckedAt = c.getString(4); p.budget = c.getString(5);
        return p;
    }

    private static Route readRoute(Cursor c) {
        Route r = new Route();
        r.id = c.getString(0); r.providerId = c.getString(1); r.presetRouteId = c.getString(2); r.label = c.getString(3);
        r.protocol = normalizeProtocol(c.getString(4)); r.baseUrl = c.getString(5); r.authMode = ProviderAuth.normalize(c.getString(6), r.protocol);
        r.dialect = c.getString(7); r.headers = c.getString(8); r.presetId = c.getString(9);
        return r;
    }

    private static ModelProfile readModelProfile(Cursor c) {
        ModelProfile p = new ModelProfile();
        p.id = c.getString(0); p.providerId = c.getString(1); p.routeId = c.getString(2); p.modelId = c.getString(3); p.displayName = c.getString(4);
        p.enabled = c.getInt(5) != 0;
        p.contextTokens = c.getInt(6); p.inputTokens = c.getInt(7); p.maxOutputTokens = c.getInt(8); p.reasoningLevels = c.getString(9);
        p.inputModalities = c.getString(10); p.outputModalities = c.getString(11); p.toolCapabilities = ChatTurnOptions.normalizeCapabilities(c.getString(12));
        p.protocol = normalizeProtocol(c.getString(13));
        p.reasoningWire = ReasoningPolicy.normalizeWire(c.getString(14), p.protocol); p.thinkingMode = ReasoningPolicy.normalizeThinkingMode(c.getString(15));
        p.thinkingBudgetTokens = Math.max(0, c.getInt(16)); p.continuationMode = ProviderContinuationPolicy.normalize(c.getString(17));
        p.harnessPreset = HarnessProfile.normalize(c.getString(18)); p.clientToolCalling = ClientToolRuntime.normalizeCalling(c.getString(19));
        p.serviceTier = c.getString(20); p.pricing = c.getString(21); p.retention = c.getString(22);
        p.overrides = c.getString(23); p.sources = c.getString(24); p.discovered = c.getString(25); p.probe = c.getString(26);
        p.catalogKey = c.getString(27); p.catalogVersion = c.getString(28);
        return p;
    }
}
