package com.moyu.chat;

import static org.junit.Assert.*;

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
 * Snapshot lifecycle: immutable, normalized, bounded; local branches inherit snapshot context and
 * imported Remote history never leaks non-visible kinds or source prompts.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SnapshotBranchContextTest {
    private ChatDatabase db;
    private SnapshotService snapshots;
    private ContextBuilder contexts;

    @Before public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("moyu-chat.db");
        db = new ChatDatabase(RuntimeEnvironment.getApplication());
        db.getWritableDatabase();
        snapshots = new SnapshotService(db);
        contexts = new ContextBuilder(db);
        db.saveProvider(provider("p1"));
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }

    private static ChatDatabase.Provider provider(String id) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = id; p.displayName = "P"; p.protocol = "openai-responses";
        p.baseUrl = "https://api.example.com/v1"; p.model = "model";
        return p;
    }

    private ChatDatabase.Session sessionWithTurns(int turns) throws Exception {
        ChatDatabase.Session s = db.createSession("p1", "model", null, null);
        for (int i = 1; i <= turns; i++) {
            db.appendTimeline(s.id, "u" + i, message("user", "user turn " + i));
            db.appendTimeline(s.id, "a" + i, message("assistant", "assistant turn " + i));
        }
        return db.getSession(s.id);
    }

    // ---------- local snapshot creation ----------

    @Test public void createLocalFreezesVisibleHistoryThroughSeq() throws Exception {
        ChatDatabase.Session source = sessionWithTurns(3); // u1 a1 u2 a2 u3 a3
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(source.id, 2);

        assertEquals(2, snapshot.itemCount);
        JSONArray items = db.snapshotItems(snapshot.id);
        assertEquals("user turn 1", items.getJSONObject(0).optString("text"));
        assertEquals("assistant turn 1", items.getJSONObject(1).optString("text"));
        assertFalse(snapshot.truncated);

        // Appending to the source timeline afterwards must not change the frozen snapshot.
        db.appendTimeline(source.id, "late", message("user", "after freeze"));
        assertEquals(2, db.snapshotItems(snapshot.id).length());
    }

    @Test public void createLocalDropsNonVisibleKinds() throws Exception {
        ChatDatabase.Session s = db.createSession("p1", "model", null, null);
        db.appendTimeline(s.id, "usage", new JSONObject().put("kind", "usage").put("createdAt", Instant.now().toString()));
        db.appendTimeline(s.id, "err", new JSONObject().put("kind", "error").put("code", "x").put("createdAt", Instant.now().toString()));
        db.appendTimeline(s.id, "live", new JSONObject().put("kind", "message").put("role", "assistant").put("text", "partial").put("streaming", true).put("createdAt", Instant.now().toString()));
        db.appendTimeline(s.id, "sys", new JSONObject().put("kind", "system").put("text", "source system").put("createdAt", Instant.now().toString()));
        db.appendTimeline(s.id, "u1", message("user", "kept"));
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(s.id, null);
        JSONArray items = db.snapshotItems(snapshot.id);
        assertEquals("only visible user/assistant items are frozen", 1, items.length());
        assertEquals("kept", items.getJSONObject(0).optString("text"));
    }

    @Test public void oversizedHistoryKeepsNewestTailAndMarksTruncated() throws Exception {
        ChatDatabase.Session s = db.createSession("p1", "model", null, null);
        int total = 2_500;
        for (int i = 1; i <= total; i++) db.appendTimeline(s.id, "u" + i, message("user", "m" + i));
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(s.id, null);
        assertTrue(snapshot.truncated);
        assertEquals(total, snapshot.sourceItemCount);
        assertEquals(2_000, snapshot.itemCount);
        JSONArray items = db.snapshotItems(snapshot.id);
        assertEquals("newest tail is kept: m" + total, "m" + total, items.getJSONObject(items.length() - 1).optString("text"));
        assertEquals("oldest kept item is m501", "m501", items.getJSONObject(0).optString("text"));
    }

    // ---------- remote import ----------

    @Test public void importRemoteNormalizesAndLocalizes() throws Exception {
        JSONObject exported = new JSONObject()
                .put("snapshotId", "remote-1")
                .put("sourceId", "opaque-id")
                .put("sourceTitle", "PC coding session")
                .put("sourceRuntime", "codex")
                .put("createdAt", "2026-08-01T00:00:00Z")
                .put("items", new JSONArray()
                        .put(new JSONObject().put("kind", "system").put("text", "source system prompt"))
                        .put(new JSONObject().put("kind", "message").put("role", "user").put("text", "pc user"))
                        .put(new JSONObject().put("kind", "message").put("role", "assistant").put("text", "pc assistant"))
                        .put(new JSONObject().put("kind", "tool_result").put("text", "tool output")));
        ChatDatabase.Snapshot snapshot = snapshots.importRemote(exported);

        assertEquals("remote", snapshot.sourceType);
        assertEquals("codex", snapshot.sourceRuntime);
        JSONArray items = db.snapshotItems(snapshot.id);
        assertEquals("system prompts and tool results must not be imported", 2, items.length());
        assertEquals("pc user", items.getJSONObject(0).optString("text"));
        assertEquals("pc assistant", items.getJSONObject(1).optString("text"));

        // Branch from it and verify the branch no longer references anything remote-shaped.
        ChatDatabase.Session branch = db.createSession("p1", "model", "branch", snapshot.id, null);
        CanonicalPrompt prompt = contexts.build(branch, 32_000, 4_096, "continue here");
        assertTrue(prompt.instructions.contains("immutable historical snapshot"));
        assertEquals("inherited visible items only", 2, prompt.inheritedItemCount);
        assertEquals("pc user", prompt.messages.getJSONObject(0).optString("text"));
        assertEquals("continue here", prompt.messages.getJSONObject(prompt.messages.length() - 1).optString("text"));
        assertFalse(prompt.instructions.contains("source system prompt"));
    }

    @Test public void importRemoteRejectsMalformedPayloads() throws Exception {
        try { snapshots.importRemote(new JSONObject().put("sourceId", "x")); fail("missing sourceTitle"); } catch (IllegalArgumentException expected) { }
        try { snapshots.importRemote(null); fail("null payload"); } catch (IllegalArgumentException expected) { }
    }

    // ---------- local branch + context projection ----------

    @Test public void localBranchContextCombinesSnapshotTimelineAndPendingInput() throws Exception {
        ChatDatabase.Session source = sessionWithTurns(2); // u1 a1 u2 a2
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(source.id, 4);
        ChatDatabase.Session branch = db.createSession("p1", "model", "branch", snapshot.id, source.id);
        db.appendTimeline(branch.id, "b-u1", message("user", "branch question"));
        db.appendTimeline(branch.id, "b-a1", message("assistant", "branch answer"));

        CanonicalPrompt prompt = contexts.build(branch, 32_000, 4_096, "next question");
        assertEquals(4, prompt.inheritedItemCount);
        JSONArray roles = prompt.messages;
        assertEquals("user turn 1", roles.getJSONObject(0).optString("text"));
        assertEquals("branch answer", roles.getJSONObject(roles.length() - 2).optString("text"));
        assertEquals("next question", roles.getJSONObject(roles.length() - 1).optString("text"));
        assertFalse(prompt.truncated);
        assertTrue(prompt.instructions.contains("immutable historical snapshot"));
    }

    @Test public void branchContextBudgetTrimsInheritedHistoryButKeepsPendingInput() throws Exception {
        ChatDatabase.Session source = db.createSession("p1", "model", null, null);
        for (int i = 1; i <= 40; i++) db.appendTimeline(source.id, "u" + i, message("user", "historic filler content number " + i + " padding"));
        ChatDatabase.Snapshot snapshot = snapshots.createLocal(source.id, null);
        ChatDatabase.Session branch = db.createSession("p1", "model", "branch", snapshot.id, null);

        CanonicalPrompt prompt = contexts.build(branch, 2_000, 512, "CURRENT question that must survive");
        assertTrue(prompt.truncated);
        assertEquals("CURRENT question that must survive",
                prompt.messages.getJSONObject(prompt.messages.length() - 1).optString("text"));
        assertTrue("older inherited history was dropped", prompt.messages.length() < 40);
    }

    @Test public void currentInputAloneOverBudgetFailsInsteadOfSilentDrop() throws Exception {
        ChatDatabase.Session s = sessionWithTurns(1);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 200_000; i++) huge.append('x');
        try {
            contexts.build(s, 2_048, 512, huge.toString());
            fail("must reject, never silently trim the current user input");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("current input"));
        }
        // The rejected send must not have mutated the durable transcript.
        assertEquals(2, db.timeline(s.id).length());
    }
}
