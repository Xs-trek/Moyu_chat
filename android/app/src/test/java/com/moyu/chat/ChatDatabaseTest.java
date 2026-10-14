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
        assertFalse("accounts no longer own a single Base URL", columns("providers").contains("base_url"));
        assertTrue("routes carry the endpoint", columns("provider_routes").containsAll(java.util.Arrays.asList("protocol", "base_url", "auth_mode", "dialect", "headers", "confirmed_host")));
    }

    @Test public void olderLayoutOfSchemaOneIsResetNotGuessed() throws Exception {
        TestDeployments.save(db, provider("p-layout"));
        db.getWritableDatabase().execSQL("PRAGMA application_id=1");
        db.close();
        db = new ChatDatabase(context);
        assertTrue("a pre-route v1 file starts over", db.listProviders().isEmpty());
        assertEquals(ChatDatabase.VERSION, db.getReadableDatabase().getVersion());
        assertTrue(tableExists("provider_routes"));
        TestDeployments.save(db, provider("p-after"));
        db.close();
        db = new ChatDatabase(context);
        assertEquals("the current layout is kept across opens", 1, db.listProviders().size());
    }

    @Test public void versionOneUpgradesInPlaceAndItsSpendCarryBecomesArchiveRows() throws Exception {
        TestDeployments.save(db, provider("p-v1"));
        String today = java.time.LocalDate.now().toString();
        db.putSetting(ChatDatabase.LEGACY_SPEND_CARRY_KEY, new JSONObject().put("p-v1", new JSONObject().put(today, new JSONObject().put("USD", 0.25))).toString());
        db.getWritableDatabase().execSQL("DROP TABLE turn_archive");
        db.getWritableDatabase().setVersion(1);
        db.close();
        db = new ChatDatabase(context);
        assertEquals(ChatDatabase.VERSION, db.getReadableDatabase().getVersion());
        assertEquals("a v1 file is migrated, not reset", 1, db.listProviders().size());
        assertTrue(tableExists("turn_archive"));
        assertEquals("", db.setting(ChatDatabase.LEGACY_SPEND_CARRY_KEY, ""));
        String startOfToday = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toString();
        assertEquals("the carried spend still counts", 0.25, db.spendSince("p-v1", startOfToday).optDouble("USD"), 1e-9);
        assertEquals("a carry has nothing to show in statistics", 0, db.turnStats("").length());
    }

    @Test public void rewoundTurnsMoveToTheArchiveWithoutTheirInstructionText() throws Exception {
        TestDeployments.save(db, provider("p-archive"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-archive", "model", null, null);
        String turnId = "turn-archive";
        JSONObject live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "").put("streaming", true).put("createdAt", Instant.now().toString());
        db.beginTurn(s.id, "u:" + turnId, message("user", "question"), "live:" + turnId, live, turnId,
                new JSONObject().put("providerId", "p-archive").put("contextMode", "standard").put("customInstruction", "secret style"));
        db.completeTurn(turnId, "ok", "", new JSONObject().put("cost", new JSONObject().put("amount", 0.5).put("currency", "USD")), new JSONArray());
        db.finalizeTurn(s.id, "live:" + turnId, message("assistant", "answer"), null, null, "completed");
        assertEquals(turnId, db.truncateTimelineFrom(s.id, 1, ChatDatabase.ARCHIVE_REWIND));
        assertEquals(0, db.turns(s.id).length());
        JSONObject archived = db.archivedTurn(turnId);
        assertEquals(ChatDatabase.ARCHIVE_REWIND, archived.optString("reason"));
        assertEquals(1, archived.optInt("attempt"));
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery("SELECT config FROM turn_archive WHERE turn_id=?", new String[]{turnId})) {
            assertTrue(c.moveToFirst());
            assertFalse(c.getString(0).contains("secret style"));
            assertEquals("secret style".length(), new JSONObject(c.getString(0)).optInt("customInstructionChars"));
        }
        assertEquals(0.5, db.spendSince("p-archive", "").optDouble("USD"), 1e-9);
        assertEquals(1, db.turnStats("").getJSONObject(0).getInt("archived"));
        assertEquals(0, db.turnStats("", null, false).length());
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

    @Test public void timelineKindMirrorsBody() throws Exception {
        TestDeployments.save(db, provider("p-kind"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-kind", "model", null, null);
        db.appendTimeline(s.id, "u", message("user", "hello"));
        db.appendTimeline(s.id, "bare", new JSONObject().put("role", "assistant").put("text", "no kind").put("createdAt", Instant.now().toString()));
        assertEquals("message", kindOf("u"));
        assertEquals("items without kind are messages", "message", kindOf("bare"));
    }

    // ---------- turns ----------

    private String startTurn(ChatDatabase.Session s, String text) throws Exception {
        String turnId = java.util.UUID.randomUUID().toString();
        JSONObject live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "").put("streaming", true).put("createdAt", Instant.now().toString());
        db.beginTurn(s.id, "u:" + turnId, message("user", text), "live:" + turnId, live, turnId, new JSONObject().put("contextMode", "minimal"));
        return turnId;
    }

    @Test public void turnRecordCarriesConfigUsageAndOrderedSpans() throws Exception {
        TestDeployments.save(db, provider("p-turn"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-turn", "model", null, null);
        String since = Instant.now().minusSeconds(60).toString();
        String turnId = startTurn(s, "question");
        assertEquals("running", db.turns(s.id).getJSONObject(0).optString("status"));
        assertEquals("a turn without model calls has no usage", 0, db.usageSince(since).length());

        db.updateTurnUsage(turnId, new JSONObject().put("inputTokens", 12).put("outputTokens", 3));
        JSONArray spans = new JSONArray()
                .put(new JSONObject().put("stage", "route").put("status", "skipped").put("reason", "mode_off").put("durationMs", 0))
                .put(new JSONObject().put("stage", "generate").put("status", "ok").put("durationMs", 40)
                        .put("usage", new JSONObject().put("calls", 1).put("inputTokens", 12).put("outputTokens", 3))
                        .put("detail", new JSONObject().put("rounds", 1)));
        db.completeTurn(turnId, "ok", "", new JSONObject().put("inputTokens", 12).put("outputTokens", 3), spans);

        JSONObject trace = db.turnTrace(s.id, turnId);
        assertEquals("ok", trace.optString("status"));
        assertEquals("minimal", trace.getJSONObject("config").optString("contextMode"));
        assertEquals(12, trace.getJSONObject("usage").optInt("inputTokens"));
        JSONArray stored = trace.getJSONArray("spans");
        assertEquals(2, stored.length());
        assertEquals("mode_off", stored.getJSONObject(0).optString("reason"));
        assertEquals(1, stored.getJSONObject(1).getJSONObject("usage").optInt("calls"));
        assertEquals(1, stored.getJSONObject(1).getJSONObject("detail").optInt("rounds"));
        assertNull("a turn is only readable through its own session", db.turnTrace("other-session", turnId));
        assertEquals(1, db.usageSince(since).length());
        for (int i = 0; i < db.timeline(s.id).length(); i++) assertNotEquals("usage", db.timeline(s.id).getJSONObject(i).optString("kind"));
    }

    @Test public void rewindingIntoATurnRemovesItsRecordAndColdStartFailsRunningTurns() throws Exception {
        TestDeployments.save(db, provider("p-rewind"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-rewind", "model", null, null);
        String first = startTurn(s, "first");
        db.finalizeTurn(s.id, "live:" + first, new JSONObject().put("kind", "message").put("role", "assistant").put("text", "one").put("createdAt", Instant.now().toString()), null, null, "completed");
        String second = startTurn(s, "second");
        db.recoverInterruptedSessions();
        assertEquals("process_died", db.turnTrace(s.id, second).optString("terminalCode"));
        assertEquals("failed", db.turnTrace(s.id, second).optString("status"));

        db.truncateTimelineFrom(s.id, 2); // the first turn's assistant row
        assertEquals("rewinding into a turn removes that turn and every later one", 0, db.turns(s.id).length());
        assertNull(db.turnTrace(s.id, first));
    }

    @Test public void wirePayloadsAreKeptOnlyForRecentTurns() throws Exception {
        TestDeployments.save(db, provider("p-wire"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-wire", "model", null, null);
        String first = null;
        for (int i = 0; i < ChatDatabase.PAYLOAD_TURNS_KEPT + 2; i++) {
            String turnId = startTurn(s, "q" + i);
            if (first == null) first = turnId;
            db.addTurnPayload(turnId, 0, "generate", "request", "{\"url\":\"u\"}");
            db.addTurnPayload(turnId, 0, "generate", "response", "{\"status\":200}");
            db.completeTurn(turnId, "ok", "", null, new JSONArray());
            db.finalizeTurn(s.id, "live:" + turnId, null, null, null, "completed");
        }
        assertEquals("old turns keep their trace but lose payloads", 0, db.turnTrace(s.id, first).getJSONArray("calls").length());
        String last = db.turns(s.id).optJSONObject(db.turns(s.id).length() - 1).optString("turnId");
        assertEquals(2, db.turnPayload(s.id, last, 0).getJSONArray("parts").length());
        assertNull("payloads are only readable through their own session", db.turnPayload("other", last, 0));
        db.addTurnPayload("no-such-turn", 0, "generate", "response", "{}"); // late record without an owner is dropped, not thrown
    }

    @Test public void viewProjectsTurnUsageRightAfterItsReply() throws Exception {
        JSONArray timeline = new JSONArray()
                .put(new JSONObject().put("kind", "message").put("role", "user").put("localSeq", 1))
                .put(new JSONObject().put("kind", "message").put("role", "assistant").put("localSeq", 2))
                .put(new JSONObject().put("kind", "message").put("role", "user").put("localSeq", 3))
                .put(new JSONObject().put("kind", "error").put("localSeq", 4));
        JSONArray turns = new JSONArray()
                .put(new JSONObject().put("turnId", "t1").put("userSeq", 1).put("usage", new JSONObject().put("inputTokens", 5)))
                .put(new JSONObject().put("turnId", "t2").put("userSeq", 3).put("usage", new JSONObject().put("inputTokens", 7)));
        JSONArray view = ChatCoordinator.withTurnUsage(timeline, turns);
        assertEquals(6, view.length());
        assertEquals("usage", view.getJSONObject(2).optString("kind"));
        assertEquals("t1", view.getJSONObject(2).optString("turnId"));
        assertEquals("error", view.getJSONObject(4).optString("kind"));
        assertEquals("a turn without a reply keeps its usage after its own rows", 7, view.getJSONObject(5).optInt("inputTokens"));
    }

    @Test public void credentialEpochLivesOnTheProviderRow() {
        ChatDatabase.Provider p = provider("p-epoch");
        db.saveProvider(p); db.bumpCredentialEpoch(p.id);
        assertEquals(1L, db.credentialEpoch(p.id));
        p.displayName = "Renamed";
        db.saveProvider(p);
        assertEquals("saving without a new key keeps the epoch", 1L, db.credentialEpoch(p.id));
        db.bumpCredentialEpoch(p.id);
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
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; TestDeployments.ensureRoute(db, provider);
        second.id = "profile-b"; second.providerId = provider.id; second.routeId = TestDeployments.routeId(provider);
        second.modelId = "model-b"; second.displayName = "Model B";
        second.contextTokens = 128_000; second.inputTokens = 96_000; second.maxOutputTokens = 8_192;
        second.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
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
        assertEquals(TestDeployments.routeId(provider), session.routeId);
        assertEquals(ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT, session.reasoningWire);
        assertFalse("an output limit nobody declared stays off the wire", session.sendOutputCap);

        second.contextTokens = 64_000; second.inputTokens = 48_000; second.reasoningLevels = "low"; second.inputModalities = "text";
        second.reasoningWire = ReasoningPolicy.WIRE_NONE; db.saveModelProfile(second);
        ChatDatabase.Session reloaded = db.getSession(session.id);
        assertEquals("session keeps resolved model limits after profile edits", 128_000, reloaded.contextTokens);
        assertEquals(96_000, reloaded.inputTokens); assertEquals("xhigh", reloaded.reasoningEffort);
        assertEquals("session keeps frozen reasoning capability after profile edits", "low,high,xhigh", reloaded.reasoningLevels);
        assertEquals("session keeps resolved modalities after profile edits", "text,image", reloaded.inputModalities);
        assertEquals("session keeps the accepted transport protocol after profile edits", OpenAiCompatibleTransport.CHAT_COMPLETIONS, reloaded.protocol);
        assertEquals(ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT, reloaded.reasoningWire);
    }

    @Test public void newProviderDefaultsToChatCompletionsBearer() {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        assertEquals(OpenAiCompatibleTransport.CHAT_COMPLETIONS, p.protocol);
        assertEquals(ProviderAuth.BEARER, p.authMode);
    }

    @Test public void routesFollowLiveWhileSessionsKeepTheirFrozenProtocol() {
        ChatDatabase.Provider provider = provider("p-live"); TestDeployments.save(db, provider);
        ChatDatabase.Session session = TestDeployments.session(db, provider.id, "model", null, null);
        ChatDatabase.Route route = db.getRoute(session.routeId);
        route.baseUrl = "https://moved.example/v1"; route.dialect = "{\"store\":true}";
        db.saveProviderRoutes(provider, java.util.Collections.singletonList(route), null);
        ChatDatabase.Provider view = db.sessionDeployment(db.getSession(session.id));
        assertEquals("the endpoint is followed live", "https://moved.example/v1", view.baseUrl);
        assertTrue(view.dialect.store);
        assertEquals(OpenAiCompatibleTransport.RESPONSES, view.protocol);
    }

    @Test public void deletingAnAccountRemovesRoutesAndModelsButKeepsChats() {
        ChatDatabase.Provider provider = provider("p-del"); TestDeployments.save(db, provider);
        ChatDatabase.Session session = TestDeployments.session(db, provider.id, "model", null, null);
        db.deleteProvider(provider.id);
        assertNull(db.getProvider(provider.id));
        assertTrue(db.listRoutes(provider.id).isEmpty());
        assertTrue(db.listModelProfiles(provider.id).isEmpty());
        ChatDatabase.Session kept = db.getSession(session.id);
        assertNotNull("the Chat stays readable", kept);
        assertNull("its profile pointer is cleared", kept.modelProfileId);
        assertNull("and it has no request view until it switches model", db.sessionDeployment(kept));
    }

    @Test public void routeSetSaveDeletesOmittedRoutesWithTheirModels() {
        ChatDatabase.Provider provider = provider("p-set"); TestDeployments.save(db, provider);
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; TestDeployments.ensureRoute(db, provider);
        assertEquals(2, db.listRoutes(provider.id).size());
        ChatDatabase.Route keep = db.getRoute(TestDeployments.routeId(provider));
        db.saveProviderRoutes(provider, java.util.Collections.singletonList(keep), null);
        assertEquals(1, db.listRoutes(provider.id).size());
        assertTrue("the Responses model went with its route", db.listModelProfiles(provider.id).isEmpty());
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
        assertEquals("question, the kept partial answer and an error row", 3, timeline.length());
        assertEquals("question", timeline.getJSONObject(0).optString("text"));
        JSONObject partial = timeline.getJSONObject(1);
        assertEquals("partial", partial.optString("text"));
        assertFalse(partial.optBoolean("streaming"));
        assertTrue(partial.optBoolean("incomplete"));
        assertEquals(ChatDatabase.PROCESS_DIED, partial.optString("terminalCode"));
        assertFalse(partial.has("streamStage"));
        assertEquals("error", timeline.getJSONObject(2).optString("kind"));
        assertEquals(ChatDatabase.PROCESS_DIED, timeline.getJSONObject(2).optString("code"));

        assertEquals("healthy sessions must be untouched", "completed", db.getSession(healthy.id).state);
        assertEquals(2, db.timeline(healthy.id).length());
    }

    @Test public void recoverInterruptedSessionsRemovesEmptyStreamingRows() throws Exception {
        TestDeployments.save(db, provider("p-empty"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-empty", "m", null, null);
        db.appendTimeline(s.id, "u", message("user", "question"));
        db.appendTimeline(s.id, "live", new JSONObject().put("kind", "message").put("role", "assistant")
                .put("text", "").put("streaming", true).put("streamStage", "connecting").put("createdAt", Instant.now().toString()));
        s = db.getSession(s.id); s.state = "running"; db.saveSession(s);

        db.recoverInterruptedSessions();

        JSONArray timeline = db.timeline(s.id);
        assertEquals("an answer that showed nothing is removed; the error row says why", 2, timeline.length());
        assertEquals("question", timeline.getJSONObject(0).optString("text"));
        assertEquals("error", timeline.getJSONObject(1).optString("kind"));
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
        assertEquals(3, timeline.length());
        assertFalse("no row stays streaming", timeline.getJSONObject(1).optBoolean("streaming"));
        assertTrue(timeline.getJSONObject(1).optBoolean("incomplete"));
    }

    @Test public void finalizeTurnSettlesLiveProjectionAndSessionTogether() throws Exception {
        TestDeployments.save(db, provider("p-finalize"));
        ChatDatabase.Session s = TestDeployments.session(db, "p-finalize", "m", null, null);
        JSONObject user = message("user", "question");
        JSONObject live = new JSONObject().put("kind", "message").put("role", "assistant").put("text", "").put("streaming", true).put("createdAt", Instant.now().toString());
        db.beginTurn(s.id, "u", user, "live", live, "turn-finalize", new JSONObject());
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
