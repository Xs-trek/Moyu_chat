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
 * session saves never delete timeline rows, pre-baseline files are reset, per-entity state stays on
 * its own rows, interrupted streams recover, and snapshots are append-only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChatDatabaseTest {
    private Context context;
    private ChatDatabase db;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(ChatDatabase.FILE_NAME);
        db = new ChatDatabase(context);
        db.getWritableDatabase(); // force schema creation
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }

    // ---------- session saves must never delete timeline ----------

    @Test public void saveSessionStateChangesPreserveTimeline() throws Exception {
        TestDeployments.save(db, provider("p1"));
        ChatDatabase.Session s = TestDeployments.session(db, "p1", "m", null, null);
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

    // ---------- baseline ----------

    @Test public void preBetaDatabaseFileIsDeletedNotMigrated() throws Exception {
        db.close();
        context.deleteDatabase(ChatDatabase.FILE_NAME);
        try (android.database.sqlite.SQLiteDatabase raw = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(ChatDatabase.LEGACY_FILE_NAME), null)) {
            raw.execSQL("CREATE TABLE providers(provider_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,protocol TEXT NOT NULL,base_url TEXT NOT NULL,model TEXT NOT NULL,updated_at TEXT NOT NULL)");
            raw.execSQL("CREATE TABLE sessions(local_id TEXT PRIMARY KEY,title TEXT NOT NULL,provider_id TEXT NOT NULL,model TEXT NOT NULL,state TEXT NOT NULL,updated_at TEXT NOT NULL)");
            raw.execSQL("CREATE TABLE timeline(item_key TEXT PRIMARY KEY,session_id TEXT NOT NULL REFERENCES sessions(local_id) ON DELETE CASCADE,local_seq INTEGER NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL)");
            raw.execSQL("CREATE TABLE handoffs(handoff_id TEXT PRIMARY KEY)");
            raw.execSQL("CREATE TABLE settings(key TEXT PRIMARY KEY,value TEXT NOT NULL)");
            raw.execSQL("INSERT INTO providers VALUES('old-p','Old','openai-responses','https://old.example/v1','m','2025-01-01T00:00:00Z')");
            raw.execSQL("INSERT INTO sessions VALUES('old-s','Old','old-p','m','idle','2025-01-01T00:00:00Z')");
            raw.execSQL("INSERT INTO timeline VALUES('u','old-s',1,'{}','2025-01-01T00:00:00Z')");
            raw.execSQL("INSERT INTO settings VALUES('knowledgeIndex:old-s','x')");
            raw.setVersion(16);
        }

        db = new ChatDatabase(context);
        assertTrue("no pre-baseline provider survives", db.listProviders().isEmpty());
        assertTrue("no pre-baseline session survives", db.listSessions().isEmpty());
        assertEquals("settings start empty", "", db.setting("knowledgeIndex:old-s", ""));
        assertEquals(ChatDatabase.VERSION, db.getReadableDatabase().getVersion());
        assertFalse("the pre-beta file is removed", context.getDatabasePath(ChatDatabase.LEGACY_FILE_NAME).exists());
        assertTrue(tableExists("verifier_labels"));
        assertFalse("removed Remote table must be gone", tableExists("handoffs"));
        assertTrue("timeline carries kind", columns("timeline").contains("kind"));
        assertFalse("providers no longer persist deployment fields", columns("providers").contains("protocol"));
    }

    @Test public void newerDatabaseIsResetInsteadOfGuessed() throws Exception {
        TestDeployments.save(db, provider("p-new"));
        db.getWritableDatabase().setVersion(99);
        db.close();
        db = new ChatDatabase(context);
        assertTrue(db.listProviders().isEmpty());
        assertEquals(ChatDatabase.VERSION, db.getReadableDatabase().getVersion());
    }

    // ---------- per-entity state ----------

    @Test public void timelineKindMirrorsBodyAndDrivesUsageQueries() throws Exception {
        TestDeployments.save(db, provider("p-kind"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-kind", "model", null, null);
        String since = Instant.now().minusSeconds(60).toString();
        db.appendTimeline(s.id, "u", message("user", "mentions \"kind\":\"usage\" in plain text"));
        db.appendTimeline(s.id, "usage", new JSONObject().put("kind", "usage").put("inputTokens", 12).put("createdAt", Instant.now().toString()));
        db.appendTimeline(s.id, "bare", new JSONObject().put("role", "assistant").put("text", "no kind").put("createdAt", Instant.now().toString()));
        assertEquals("message", kindOf("u"));
        assertEquals("usage", kindOf("usage"));
        assertEquals("items without kind are messages", "message", kindOf("bare"));
        JSONArray usage = db.usageSince(since);
        assertEquals("usage query matches the kind column, not JSON text", 1, usage.length());
        assertEquals(12, usage.getJSONObject(0).optInt("inputTokens"));
    }

    @Test public void credentialEpochLivesOnTheProviderRow() {
        ChatDatabase.Provider p = provider("p-epoch");
        db.saveProviderAndProfiles(p, null, true);
        assertEquals(1L, db.credentialEpoch(p.id));
        p.displayName = "Renamed";
        db.saveProvider(p);
        assertEquals("saving without a new key keeps the epoch", 1L, db.credentialEpoch(p.id));
        db.saveProviderAndProfiles(p, null, true);
        assertEquals(2L, db.credentialEpoch(p.id));
        assertEquals("Renamed", db.getProvider(p.id).displayName);
    }

    @Test public void knowledgeMarkerLivesOnTheSessionRow() throws Exception {
        TestDeployments.save(db, provider("p-know"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-know", "model", null, null);
        db.appendTimeline(s.id, "u", message("user", "hello"));
        s = db.getSession(s.id);
        db.markKnowledgeIndexed(s.id, s.updatedAt);
        assertEquals(s.updatedAt, db.knowledgeIndexedAt(s.id));
        assertEquals("marking the index never bumps updated_at", s.updatedAt, db.getSession(s.id).updatedAt);
        db.saveSession(db.getSession(s.id));
        assertEquals("session saves keep the marker", s.updatedAt, db.knowledgeIndexedAt(s.id));
        db.truncateTimelineFrom(s.id, 1);
        assertEquals("rollback invalidates the index", "", db.knowledgeIndexedAt(s.id));
    }

    @Test public void verifierLabelsAreBoundedPerCalibrationKey() throws Exception {
        for (int i = 0; i < 5; i++) db.addVerifierLabel("key-a", new JSONArray().put(i), i % 2, 3);
        db.addVerifierLabel("key-b", new JSONArray().put(9), 1, 3);
        JSONArray a = db.verifierLabels("key-a");
        assertEquals(3, a.length());
        assertEquals("oldest-first, newest kept", 2, a.getJSONObject(0).getJSONArray("x").getInt(0));
        assertEquals(4, a.getJSONObject(2).getJSONArray("x").getInt(0));
        assertEquals("other keys are untouched", 1, db.verifierLabels("key-b").length());
    }

    @Test public void sessionsKeepCreationTimeAndLineage() {
        TestDeployments.save(db, provider("p-line"));
        ChatDatabase.Session parent = TestDeployments.session(db, "p-line", "model", "parent", null);
        ChatDatabase.Session child = db.branchSession(parent, "child", null, parent.id);
        String created = db.getSession(child.id).createdAt;
        assertNotNull(created);
        ChatDatabase.Session edited = db.getSession(child.id); edited.updatedAt = Instant.now().plusSeconds(5).toString(); db.saveSession(edited);
        assertEquals("created_at never moves", created, db.getSession(child.id).createdAt);
        db.deleteSession(parent.id);
        assertNull("deleting the parent clears the child's lineage pointer", db.getSession(child.id).parentSessionId);
    }

    private boolean tableExists(String name) {
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{name})) { return c.moveToFirst(); }
    }

    private java.util.Set<String> columns(String table) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("PRAGMA table_info(" + table + ")", null)) { while (c.moveToNext()) out.add(c.getString(1)); }
        return out;
    }

    private String kindOf(String itemKey) {
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT kind FROM timeline WHERE item_key=?", new String[]{itemKey})) { return c.moveToFirst() ? c.getString(0) : null; }
    }

    // ---------- providers, profiles, sessions ----------

    @Test public void localBranchesInheritFrozenContextMode() {
        ChatDatabase.Provider provider = provider("p-mode"); TestDeployments.save(db, provider);
        ChatDatabase.ModelProfile profile = db.firstModelProfile(provider.id); assertNotNull(profile);
        ChatDatabase.Session source = db.createSession(profile, "source", null, null, ReasoningPolicy.DEFAULT, ContextMode.MINIMAL);
        ChatDatabase.Session branch = db.branchSession(source, "branch", null, source.id);
        assertEquals(ContextMode.MINIMAL, db.getSession(source.id).contextMode);
        assertEquals(ContextMode.MINIMAL, db.getSession(branch.id).contextMode);
    }

    @Test public void oneProviderCanOwnMultipleModelsAndSessionSnapshotsCapabilities() {
        ChatDatabase.Provider provider = provider("p-models"); provider.model = "model-a"; TestDeployments.save(db, provider);
        ChatDatabase.ModelProfile second = new ChatDatabase.ModelProfile();
        second.id = "profile-b"; second.providerId = provider.id; second.modelId = "model-b"; second.displayName = "Model B";
        second.contextTokens = 128_000; second.inputTokens = 96_000; second.maxOutputTokens = 8_192;
        second.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; second.authMode = ProviderAuth.BEARER;
        second.reasoningWire = ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT;
        second.reasoningLevels = "low,high,xhigh";
        second.inputModalities = "text,image"; second.outputModalities = "text,image"; db.saveModelProfile(second);
        assertEquals(2, db.listModelProfiles(provider.id).size());

        ChatDatabase.Session session = db.createSession(second, null, null, null, "xhigh");
        assertEquals("model-b", session.model); assertEquals(128_000, session.contextTokens); assertEquals(96_000, session.inputTokens);
        assertEquals(8_192, session.maxOutputTokens); assertEquals("xhigh", session.reasoningEffort);
        assertEquals("low,high,xhigh", session.reasoningLevels);
        assertEquals("text,image", session.inputModalities); assertEquals("text,image", session.outputModalities);
        assertEquals(OpenAiCompatibleTransport.CHAT_COMPLETIONS, session.protocol);
        assertEquals(ProviderAuth.BEARER, session.authMode);
        assertEquals(ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT, session.reasoningWire);

        second.contextTokens = 64_000; second.inputTokens = 48_000; second.reasoningLevels = "low"; second.inputModalities = "text";
        second.protocol = OpenAiCompatibleTransport.RESPONSES; second.authMode = ProviderAuth.API_KEY;
        second.reasoningWire = ReasoningPolicy.WIRE_OPENAI_RESPONSES; db.saveModelProfile(second);
        ChatDatabase.Session reloaded = db.getSession(session.id);
        assertEquals("session keeps resolved model limits after profile edits", 128_000, reloaded.contextTokens);
        assertEquals(96_000, reloaded.inputTokens); assertEquals("xhigh", reloaded.reasoningEffort);
        assertEquals("session keeps frozen reasoning capability after profile edits", "low,high,xhigh", reloaded.reasoningLevels);
        assertEquals("session keeps resolved modalities after profile edits", "text,image", reloaded.inputModalities);
        assertEquals("session keeps the accepted transport protocol after profile edits", OpenAiCompatibleTransport.CHAT_COMPLETIONS, reloaded.protocol);
        assertEquals(ProviderAuth.BEARER, reloaded.authMode);
        assertEquals(ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT, reloaded.reasoningWire);
    }

    @Test public void newProviderDefaultsToChatCompletionsBearer() {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        assertEquals(OpenAiCompatibleTransport.CHAT_COMPLETIONS, p.protocol);
        assertEquals(ProviderAuth.BEARER, p.authMode);
    }

    // ---------- interrupted streaming recovery ----------

    @Test public void recoverInterruptedSessionsCleansStaleStreamingRowsOnly() throws Exception {
        TestDeployments.save(db, provider("p1"));
        ChatDatabase.Session running = TestDeployments.session(db, "p1", "m", null, null);
        db.appendTimeline(running.id, "u1", message("user", "question"));
        db.appendTimeline(running.id, "live1", new JSONObject().put("kind", "message").put("role", "assistant").put("text", "partial").put("streaming", true).put("createdAt", Instant.now().toString()));
        running.state = "running";
        db.saveSession(running);

        ChatDatabase.Session healthy = TestDeployments.session(db, "p1", "m", null, null);
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

    @Test public void recoverInterruptedSessionsAlsoRepairsDivergedStreamingFlag() throws Exception {
        TestDeployments.save(db, provider("p-diverged"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-diverged", "m", null, null);
        db.appendTimeline(s.id, "u", message("user", "question"));
        db.appendTimeline(s.id, "live", new JSONObject().put("kind", "message").put("role", "assistant")
                .put("text", "stale").put("streaming", true).put("createdAt", Instant.now().toString()));
        s = db.getSession(s.id); s.state = "completed"; db.saveSession(s); // simulate a split state source

        db.recoverInterruptedSessions();

        assertEquals("a durable streaming row cannot survive cold start", "failed", db.getSession(s.id).state);
        JSONArray timeline = db.timeline(s.id);
        assertEquals(1, timeline.length());
        assertEquals("question", timeline.getJSONObject(0).optString("text"));
    }

    @Test public void finalizeTurnSettlesLiveProjectionAndSessionTogether() throws Exception {
        TestDeployments.save(db, provider("p-finalize"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-finalize", "m", null, null);
        JSONObject user = message("user", "question");
        JSONObject live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "").put("streaming", true).put("createdAt", Instant.now().toString());
        db.beginTurn(s.id, "u", user, "live", live);
        assertEquals("running", db.getSession(s.id).state);
        JSONObject done = new JSONObject(live.toString()).put("text", "answer").put("streaming", false);

        db.finalizeTurn(s.id, "live", done, null, null, "completed");

        assertEquals("completed", db.getSession(s.id).state);
        JSONArray timeline = db.timeline(s.id);
        assertEquals(2, timeline.length());
        assertEquals("answer", timeline.getJSONObject(1).optString("text"));
        assertFalse(timeline.getJSONObject(1).optBoolean("streaming", true));
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
        s.id = id; s.sourceSessionId = "src"; s.sourceTitle = "title";
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
