package com.moyu.chat;

import static org.junit.Assert.*;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;

/**
 * Durable transcript invariants on a real (Robolectric) SQLite instance:
 * session saves never delete timeline rows, migrations preserve data, interrupted streams recover,
 * and snapshots are append-only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChatDatabaseTest {
    private Context context;
    private ChatDatabase db;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("moyu-chat.db");
        db = new ChatDatabase(context);
        db.getWritableDatabase(); // force schema creation
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }

    // ---------- session saves must never delete timeline ----------

    @Test public void saveSessionStateChangesPreserveTimeline() throws Exception {
        db.saveProvider(provider("p1"));
        ChatDatabase.Session s = db.createSession("p1", "m", null, null);
        db.appendTimeline(s.id, "u1", message("user", "hello"));
        db.appendTimeline(s.id, "a1", message("assistant", "hi"));
        db.setDraft(s.id, "draft text");

        // Mirror app usage: mutate the freshly reloaded row, never a stale in-memory copy.
        s = db.getSession(s.id);
        s.state = "running";
        db.saveSession(s);
        assertEquals(2, db.timeline(s.id).length());

        s = db.getSession(s.id);
        s.state = "completed";
        s.title = "renamed";
        db.saveSession(s);
        assertEquals("timeline must survive every session save",
                2, db.timeline(s.id).length());
        assertEquals("hello", db.timeline(s.id).getJSONObject(0).optString("text"));

        ChatDatabase.Session reloaded = db.getSession(s.id);
        assertEquals("draft text", reloaded.draft);
        assertEquals("renamed", reloaded.title);
    }

    // ---------- migrations ----------

    @Test public void upgradeFromV1PreservesRowsAndAddsColumns() throws Exception {
        db.close();
        context.deleteDatabase("moyu-chat.db");
        createLegacyDatabase(1);
        ChatDatabase upgraded = new ChatDatabase(context);
        upgraded.getWritableDatabase();

        ChatDatabase.Provider p = upgraded.getProvider("legacy-p");
        assertNotNull(p);
        assertEquals("Legacy", p.displayName);
        assertEquals("token columns must default after v1 upgrade",
                ContextBudgetManager.DEFAULT_CONTEXT_TOKENS, p.contextTokens);
        assertEquals(ContextBudgetManager.DEFAULT_OUTPUT_TOKENS, p.maxOutputTokens);
        assertEquals(ProviderAuth.BEARER, p.authMode);
        assertEquals(ProviderAuth.X_API_KEY, upgraded.getProvider("legacy-a").authMode);

        ChatDatabase.Session s = upgraded.getSession("legacy-s");
        assertNotNull("session row must survive migration", s);
        assertNull("parent_session_id must default to null", s.parentSessionId);

        assertEquals(2, upgraded.timeline("legacy-s").length());
        assertEquals("legacy user", upgraded.timeline("legacy-s").getJSONObject(0).optString("text"));

        ChatDatabase.Snapshot snap = upgraded.getSnapshot("legacy-snap");
        assertNotNull(snap);
        assertEquals("snapshot columns must default after upgrade", snap.itemCount, snap.sourceItemCount);
        assertFalse(snap.truncated);
        JSONArray items = upgraded.snapshotItems("legacy-snap");
        assertEquals(1, items.length());
        assertEquals("legacy assistant", items.getJSONObject(0).optString("text"));
    }

    @Test public void upgradeFromV2PreservesSnapshotData() throws Exception {
        db.close();
        context.deleteDatabase("moyu-chat.db");
        createLegacyDatabase(2);
        ChatDatabase upgraded = new ChatDatabase(context);
        upgraded.getWritableDatabase();
        ChatDatabase.Snapshot snap = upgraded.getSnapshot("legacy-snap");
        assertNotNull(snap);
        assertEquals(1, snap.itemCount);
        assertEquals("source_item_count must be backfilled", snap.itemCount, snap.sourceItemCount);
        assertFalse(snap.truncated);
        assertEquals(ProviderAuth.BEARER, upgraded.getProvider("legacy-p").authMode);
        assertEquals(ProviderAuth.X_API_KEY, upgraded.getProvider("legacy-a").authMode);
    }

    @Test public void upgradeFromV3PreservesProviderSessionTimelineAndMigratesAuth() throws Exception {
        db.close();
        context.deleteDatabase("moyu-chat.db");
        createLegacyDatabase(3);
        ChatDatabase upgraded = new ChatDatabase(context);
        upgraded.getWritableDatabase();

        ChatDatabase.Provider openAi = upgraded.getProvider("legacy-p");
        ChatDatabase.Provider anthropic = upgraded.getProvider("legacy-a");
        assertNotNull(openAi);
        assertNotNull(anthropic);
        assertEquals(ProviderAuth.BEARER, openAi.authMode);
        assertEquals(ProviderAuth.X_API_KEY, anthropic.authMode);
        assertNotNull(upgraded.getSession("legacy-s"));
        assertEquals(2, upgraded.timeline("legacy-s").length());
        assertEquals(1, upgraded.snapshotItems("legacy-snap").length());
    }

    @Test public void upgradeFromV4CreatesDeploymentScopedModelProfileAndSessionSnapshot() throws Exception {
        db.close(); context.deleteDatabase("moyu-chat.db"); createLegacyDatabase(4);
        ChatDatabase upgraded = new ChatDatabase(context); upgraded.getWritableDatabase();
        ChatDatabase.Provider provider = upgraded.getProvider("legacy-p");
        assertEquals(ReasoningPolicy.WIRE_OPENAI_RESPONSES, provider.reasoningWire);
        ChatDatabase.ModelProfile profile = upgraded.findModelProfile("legacy-p", "legacy-model");
        assertNotNull(profile); assertEquals(ContextBudgetManager.DEFAULT_CONTEXT_TOKENS, profile.contextTokens);
        ChatDatabase.Session session = upgraded.getSession("legacy-s");
        assertEquals(profile.id, session.modelProfileId); assertEquals(profile.contextTokens, session.contextTokens);
        assertEquals(profile.maxOutputTokens, session.maxOutputTokens); assertEquals(ReasoningPolicy.DEFAULT, session.reasoningEffort);
        assertEquals("text", profile.inputModalities); assertEquals("text", profile.outputModalities);
        assertEquals("text", session.inputModalities); assertEquals("text", session.outputModalities); assertEquals(0, upgraded.draftMedia(session.id).length());
    }

    @Test public void upgradeFromV4DoesNotMisbindSessionModelOverrideToProviderDefaultProfile() throws Exception {
        db.close(); context.deleteDatabase("moyu-chat.db"); createLegacyDatabase(4);
        android.database.sqlite.SQLiteDatabase raw = context.openOrCreateDatabase("moyu-chat.db", Context.MODE_PRIVATE, null);
        raw.execSQL("UPDATE sessions SET model='legacy-session-override' WHERE local_id='legacy-s'"); raw.close();
        ChatDatabase upgraded = new ChatDatabase(context); upgraded.getWritableDatabase();
        ChatDatabase.Session session = upgraded.getSession("legacy-s");
        assertEquals("legacy-session-override", session.model);
        assertNull("an old session-level model override must not point at the provider default model profile", session.modelProfileId);
        assertEquals(ContextBudgetManager.DEFAULT_CONTEXT_TOKENS, session.contextTokens);
        assertEquals(ContextBudgetManager.DEFAULT_OUTPUT_TOKENS, session.maxOutputTokens);
    }

    @Test public void oneProviderCanOwnMultipleModelsAndSessionSnapshotsCapabilities() {
        ChatDatabase.Provider provider = provider("p-models"); provider.model = "model-a"; db.saveProvider(provider);
        ChatDatabase.ModelProfile second = new ChatDatabase.ModelProfile();
        second.id = "profile-b"; second.providerId = provider.id; second.modelId = "model-b"; second.displayName = "Model B";
        second.contextTokens = 128_000; second.inputTokens = 96_000; second.maxOutputTokens = 8_192;
        second.reasoningLevels = "low,high,xhigh"; second.defaultReasoning = "high";
        second.inputModalities = "text,image"; second.outputModalities = "text,image"; db.saveModelProfile(second);
        assertEquals(2, db.listModelProfiles(provider.id).size());

        ChatDatabase.Session session = db.createSession(second, null, null, null, "xhigh");
        assertEquals("model-b", session.model); assertEquals(128_000, session.contextTokens); assertEquals(96_000, session.inputTokens);
        assertEquals(8_192, session.maxOutputTokens); assertEquals("xhigh", session.reasoningEffort);
        assertEquals("text,image", session.inputModalities); assertEquals("text,image", session.outputModalities);

        second.contextTokens = 64_000; second.inputTokens = 48_000; second.defaultReasoning = "low"; second.inputModalities = "text"; db.saveModelProfile(second);
        ChatDatabase.Session reloaded = db.getSession(session.id);
        assertEquals("session keeps resolved model limits after profile edits", 128_000, reloaded.contextTokens);
        assertEquals(96_000, reloaded.inputTokens); assertEquals("xhigh", reloaded.reasoningEffort);
        assertEquals("session keeps resolved modalities after profile edits", "text,image", reloaded.inputModalities);
    }

    /** Rebuilds the v1 schema (pre token budgets / parent ids / snapshot provenance) with seed rows. */
    private void createLegacyDatabase(int version) throws Exception {
        android.database.sqlite.SQLiteDatabase raw = context.openOrCreateDatabase("moyu-chat.db", Context.MODE_PRIVATE, null);
        String base =
                "CREATE TABLE providers(provider_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,protocol TEXT NOT NULL,base_url TEXT NOT NULL,model TEXT NOT NULL,updated_at TEXT NOT NULL);" +
                "CREATE TABLE sessions(local_id TEXT PRIMARY KEY,title TEXT NOT NULL,provider_id TEXT NOT NULL,model TEXT NOT NULL,state TEXT NOT NULL,updated_at TEXT NOT NULL,draft TEXT NOT NULL DEFAULT '',origin_snapshot_id TEXT,preview TEXT);" +
                "CREATE TABLE timeline(item_key TEXT PRIMARY KEY,session_id TEXT NOT NULL,local_seq INTEGER NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(session_id) REFERENCES sessions(local_id) ON DELETE CASCADE,UNIQUE(session_id,local_seq));" +
                "CREATE INDEX timeline_session_seq ON timeline(session_id,local_seq);" +
                "CREATE TABLE snapshots(snapshot_id TEXT PRIMARY KEY,source_type TEXT NOT NULL,source_id TEXT NOT NULL,source_title TEXT NOT NULL,source_runtime TEXT,created_at TEXT NOT NULL,item_count INTEGER NOT NULL,content_hash TEXT);" +
                "CREATE TABLE snapshot_items(snapshot_id TEXT NOT NULL,ordinal INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(snapshot_id,ordinal),FOREIGN KEY(snapshot_id) REFERENCES snapshots(snapshot_id) ON DELETE CASCADE);" +
                "CREATE TABLE handoffs(handoff_id TEXT PRIMARY KEY,branch_session_id TEXT NOT NULL,state TEXT NOT NULL,summary TEXT NOT NULL,payload TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(branch_session_id) REFERENCES sessions(local_id) ON DELETE CASCADE);" +
                "CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL);";
        for (String statement : base.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) raw.execSQL(trimmed);
        }
        raw.execSQL("INSERT INTO providers VALUES('legacy-p','Legacy','openai-responses','https://legacy.example/v1','legacy-model','2020-01-01T00:00:00Z')");
        raw.execSQL("INSERT INTO providers VALUES('legacy-a','Legacy Anthropic','anthropic-messages','https://legacy-anthropic.example','legacy-claude','2020-01-01T00:00:00Z')");
        raw.execSQL("INSERT INTO sessions VALUES('legacy-s','Legacy session','legacy-p','legacy-model','completed','2020-01-01T00:00:00Z','','legacy-snap',NULL)");
        raw.execSQL("INSERT INTO timeline VALUES('k1','legacy-s',1,'{\"kind\":\"message\",\"role\":\"user\",\"text\":\"legacy user\",\"createdAt\":\"2020-01-01T00:00:00Z\"}','2020-01-01T00:00:00Z')");
        raw.execSQL("INSERT INTO timeline VALUES('k2','legacy-s',2,'{\"kind\":\"message\",\"role\":\"assistant\",\"text\":\"legacy assistant\",\"createdAt\":\"2020-01-01T00:00:01Z\"}','2020-01-01T00:00:01Z')");
        raw.execSQL("INSERT INTO snapshots VALUES('legacy-snap','chat','legacy-s','Legacy session',NULL,'2020-01-01T00:00:00Z',1,'hash')");
        if (version >= 2) {
            raw.execSQL("ALTER TABLE providers ADD COLUMN context_tokens INTEGER NOT NULL DEFAULT 32000");
            raw.execSQL("ALTER TABLE providers ADD COLUMN max_output_tokens INTEGER NOT NULL DEFAULT 4096");
            raw.execSQL("ALTER TABLE sessions ADD COLUMN parent_session_id TEXT");
        }
        if (version >= 3) {
            raw.execSQL("ALTER TABLE snapshots ADD COLUMN source_item_count INTEGER NOT NULL DEFAULT 0");
            raw.execSQL("ALTER TABLE snapshots ADD COLUMN truncated INTEGER NOT NULL DEFAULT 0");
            raw.execSQL("UPDATE snapshots SET source_item_count=item_count WHERE source_item_count=0");
        }
        if (version >= 4) {
            raw.execSQL("ALTER TABLE providers ADD COLUMN auth_mode TEXT NOT NULL DEFAULT 'bearer'");
            raw.execSQL("UPDATE providers SET auth_mode='x-api-key' WHERE protocol='anthropic-messages'");
        }
        raw.execSQL("INSERT INTO snapshot_items VALUES('legacy-snap',0,'{\"kind\":\"message\",\"role\":\"assistant\",\"text\":\"legacy assistant\",\"createdAt\":\"2020-01-01T00:00:01Z\"}')");
        raw.execSQL("PRAGMA user_version = " + version);
        raw.close();
    }

    @Test public void newProviderDefaultsToChatCompletionsBearer() {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        assertEquals(OpenAiCompatibleTransport.CHAT_COMPLETIONS, p.protocol);
        assertEquals(ProviderAuth.BEARER, p.authMode);
    }

    // ---------- interrupted streaming recovery ----------

    @Test public void recoverInterruptedSessionsCleansStaleStreamingRowsOnly() throws Exception {
        db.saveProvider(provider("p1"));
        ChatDatabase.Session running = db.createSession("p1", "m", null, null);
        db.appendTimeline(running.id, "u1", message("user", "question"));
        db.appendTimeline(running.id, "live1", new JSONObject().put("kind", "message").put("role", "assistant").put("text", "partial").put("streaming", true).put("createdAt", Instant.now().toString()));
        running.state = "running";
        db.saveSession(running);

        ChatDatabase.Session healthy = db.createSession("p1", "m", null, null);
        db.appendTimeline(healthy.id, "u2", message("user", "done turn"));
        db.appendTimeline(healthy.id, "a2", message("assistant", "answer"));
        healthy.state = "completed";
        db.saveSession(healthy);

        db.recoverInterruptedSessions();

        assertEquals("stale running session must be marked failed", "failed", db.getSession(running.id).state);
        JSONArray timeline = db.timeline(running.id);
        assertEquals("stale streaming projection must be removed, user message kept", 1, timeline.length());
        assertEquals("question", timeline.getJSONObject(0).optString("text"));

        assertEquals("healthy sessions must be untouched", "completed", db.getSession(healthy.id).state);
        assertEquals(2, db.timeline(healthy.id).length());
    }

    // ---------- snapshot immutability ----------

    @Test public void snapshotsAreAppendOnly() throws Exception {
        ChatDatabase.Snapshot snapshot = snapshot("s1", 1, 1);
        JSONArray items = new JSONArray().put(message("user", "hello"));
        db.saveSnapshot(snapshot, items);
        assertEquals(1, db.snapshotItems("s1").length());

        try {
            db.saveSnapshot(snapshot, new JSONArray().put(message("user", "tampered")));
            fail("rewriting an existing snapshot must be rejected");
        } catch (android.database.sqlite.SQLiteConstraintException expected) { }
        assertEquals("original snapshot content must be intact", "hello", db.snapshotItems("s1").getJSONObject(0).optString("text"));
    }

    private static ChatDatabase.Snapshot snapshot(String id, int itemCount, int sourceItemCount) {
        ChatDatabase.Snapshot s = new ChatDatabase.Snapshot();
        s.id = id; s.sourceType = "chat"; s.sourceId = "src"; s.sourceTitle = "title";
        s.createdAt = Instant.now().toString(); s.itemCount = itemCount; s.sourceItemCount = sourceItemCount;
        s.truncated = false; s.contentHash = "hash";
        return s;
    }

    private static ChatDatabase.Provider provider(String id) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = id; p.displayName = "Provider " + id; p.protocol = "openai-responses";
        p.baseUrl = "https://api.example.com/v1"; p.model = "model";
        return p;
    }
}
