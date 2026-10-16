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

/** Spec C1–C3, T3, H3 (D1, D2, D4, D10, D13): how the context is assembled from a session's history. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ContextMechanismsTest {
    private ChatDatabase db;
    private ChatDatabase.ModelProfile profile;
    private final ContextBudgetManager budgets = new ContextBudgetManager();

    @Before public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        db = ((MoyuChatApplication) RuntimeEnvironment.getApplication()).database();
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.id = "p"; provider.displayName = "P"; provider.baseUrl = "https://example.test/v1";
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; provider.model = "m";
        provider.contextTokens = 32_000; provider.maxOutputTokens = 4_096;
        profile = TestDeployments.save(db, provider);
    }

    // ---------- C2: clearing earlier tool results ----------

    @Test public void theOldestToolResultsAreClearedWhenTheyPassHalfTheWindowAndTheNewestThreeStay() throws Exception {
        ChatDatabase.Session s = session(ContextMode.STANDARD);
        for (int i = 0; i < 6; i++) {
            append(s, "u" + i, message("user", "Look up page " + i));
            append(s, "a" + i, message("assistant", "Summary of page " + i).put(ToolHistory.FIELD, new JSONArray()
                    .put(call("call_" + i, "web_fetch", new JSONObject().put("url", "https://example.com/" + i), "PAGE-" + i + " " + repeat("x", 3_000)))));
        }
        append(s, "now", message("user", "Compare them."));
        CanonicalPrompt prompt = new ContextBuilder(db).buildCommitted(db.getSession(s.id), 12_000, 0, 1_024, "");
        assertFalse("clearing made room, nothing was dropped", prompt.truncated);
        assertEquals(3, prompt.clearedToolResults);
        assertTrue(prompt.clearedToolTokens > 3_000);
        for (int i = 0; i < 6; i++) {
            JSONObject callSent = ToolHistory.of(prompt.messages.getJSONObject(2 * i + 1)).getJSONObject(0);
            assertEquals("the call itself stays", "https://example.com/" + i, callSent.getJSONObject("arguments").optString("url"));
            if (i < 3) assertEquals(ToolHistory.CLEARED, callSent.optString("result"));
            else assertTrue("the newest three keep their results", callSent.optString("result").startsWith("PAGE-" + i));
        }
        assertFalse("a cleared result is not seen", prompt.carries("PAGE-0"));
        assertTrue(prompt.carries("PAGE-5"));
        assertTrue("the stored results are untouched", ToolHistory.of(db.timeline(s.id).getJSONObject(1)).getJSONObject(0).optString("result").startsWith("PAGE-0"));
    }

    @Test public void minimalDoesNotClearToolResults() throws Exception {
        ChatDatabase.Session s = session(ContextMode.MINIMAL);
        for (int i = 0; i < 6; i++) {
            append(s, "u" + i, message("user", "Look up page " + i));
            append(s, "a" + i, message("assistant", "Summary " + i).put(ToolHistory.FIELD, new JSONArray()
                    .put(call("call_" + i, "web_fetch", new JSONObject().put("url", "https://example.com/" + i), repeat("y", 3_000)))));
        }
        append(s, "now", message("user", "Compare them."));
        CanonicalPrompt prompt = new ContextBuilder(db).buildCommitted(db.getSession(s.id), 10_000, 0, 1_024, "");
        assertEquals(0, prompt.clearedToolResults);
        assertTrue("Minimal only drops the oldest rounds", prompt.truncated);
    }

    // ---------- C3: the summary ----------

    @Test public void theSummaryOpensTheMessagesAndStandsInForWhatItCovers() throws Exception {
        ChatDatabase.Session s = rounds(ContextMode.STANDARD, 4);
        ContextBuilder contexts = new ContextBuilder(db);
        JSONArray history = contexts.neutralHistory(db.getSession(s.id));
        db.setContextCheckpoint(s.id, new ContextCheckpoint("They talked about rounds 0 and 1.", ContextCheckpoint.coverageHash(history, 4)).toJson().toString(), 4);
        CanonicalPrompt prompt = contexts.buildCommitted(db.getSession(s.id), 32_000, 0, 1_024, "");
        assertEquals(4, prompt.summarizedItems);
        String first = prompt.messages.getJSONObject(0).optString("text");
        assertTrue(first.startsWith(ContextCheckpoint.PREFIX));
        assertTrue(first.contains("They talked about rounds 0 and 1.") && first.endsWith("question 2"));
        assertFalse(prompt.carries("question 0"));
        assertFalse("not in the instructions any more", prompt.instructions.contains("They talked about"));
    }

    @Test public void aSummaryWhoseHistoryChangedIsNotUsed() throws Exception {
        ChatDatabase.Session s = rounds(ContextMode.STANDARD, 4);
        ContextBuilder contexts = new ContextBuilder(db);
        JSONArray history = contexts.neutralHistory(db.getSession(s.id));
        String hash = ContextCheckpoint.coverageHash(history, 4);
        history.getJSONObject(0).put("text", "question 0, edited");
        assertNotEquals("the hash follows the covered text", hash, ContextCheckpoint.coverageHash(history, 4));
        db.setContextCheckpoint(s.id, new ContextCheckpoint("Stale.", ContextCheckpoint.coverageHash(history, 4)).toJson().toString(), 4);
        CanonicalPrompt prompt = contexts.buildCommitted(db.getSession(s.id), 32_000, 0, 1_024, "");
        assertEquals(0, prompt.summarizedItems);
        assertTrue(prompt.carries("question 0"));
    }

    @Test public void compactionCoversAllButTheNewestTwoRoundsInWholeRounds() throws Exception {
        ChatDatabase.Session s = rounds(ContextMode.STANDARD, 5);
        ContextBuilder.CompactionSource source = new ContextBuilder(db).compactionSource(db.getSession(s.id));
        assertNotNull(source);
        assertEquals("rounds 0-2 of 5", 6, source.coverableItems);
        assertEquals(java.util.Arrays.asList(2, 4, 6), source.roundStarts);
        assertNull("two rounds: nothing to summarize", new ContextBuilder(db).compactionSource(db.getSession(rounds(ContextMode.STANDARD, 2).id)));
    }

    @Test public void aSummaryEndsOnARoundBoundaryWhenTheWindowIsSmall() throws Exception {
        ChatDatabase.Session s = session(ContextMode.STANDARD);
        for (int i = 0; i < 8; i++) {
            append(s, "u" + i, message("user", "question " + i + " " + repeat("q", 400)));
            append(s, "a" + i, message("assistant", "answer " + i + " " + repeat("a", 400)));
        }
        ContextBuilder.CompactionSource source = new ContextBuilder(db).compactionSource(db.getSession(s.id));
        CapturingTransport transport = new CapturingTransport("A short summary.");
        CompactionService.Result result = new CompactionService().summarize(transport, provider(), "key", "m", source, 1_200);
        assertTrue(result.ok());
        assertTrue("only part fits", result.coveredItems < source.coverableItems);
        assertEquals("ends where a round ends", 0, result.coveredItems % 2);
        String sent = transport.prompt.messages.getJSONObject(0).optString("text");
        assertTrue(sent.contains("question 0 "));
        assertFalse(sent.contains("question " + (result.coveredItems / 2) + " "));
        assertEquals(ContextCheckpoint.coverageHash(source.history, result.coveredItems), result.checkpoint.coverage);
    }

    @Test public void compactionIsDueWhenTheNextRequestPassesThreeQuartersOfTheWindow() {
        assertFalse(CompactionService.dueAfterTurn(7_500, 10_000));
        assertTrue(CompactionService.dueAfterTurn(7_501, 10_000));
    }

    // ---------- H3, D13: what a new-session branch copies ----------

    @Test public void aBranchCopiesToolCallsAndSourcesButNotReasoningOrReplayState() throws Exception {
        ChatDatabase.Session s = session(ContextMode.STANDARD);
        append(s, "u", message("user", "When is high tide?"));
        append(s, "a", message("assistant", "At 06:12.").put("reasoning", "private")
                .put("providerContinuation", new JSONObject().put("items", new JSONArray()))
                .put("verification", new JSONObject().put("verdict", "pass"))
                .put("sources", new JSONArray().put(new JSONObject().put("url", "https://tides.example").put("title", "Tides")))
                .put(ToolHistory.FIELD, new JSONArray().put(call("call_1", "web_search", new JSONObject().put("query", "tide"), "06:12"))));
        ChatDatabase.Snapshot snapshot = new SnapshotService(db).createLocal(s.id, null);
        JSONObject copied = db.snapshotItems(snapshot.id).getJSONObject(1);
        assertEquals("06:12", ToolHistory.of(copied).getJSONObject(0).optString("result"));
        assertEquals("https://tides.example", copied.getJSONArray("sources").getJSONObject(0).optString("url"));
        assertFalse(copied.has("reasoning"));
        assertFalse(copied.has("providerContinuation"));
        assertFalse(copied.has("verification"));
    }

    // ---------- helpers ----------

    private ChatDatabase.Session session(String mode) {
        ChatDatabase.Session s = db.createSession(profile, "s", null, null, ReasoningPolicy.DEFAULT);
        s.contextMode = mode;
        db.saveSession(s);
        return db.getSession(s.id);
    }

    /** {@code n} rounds "question i" / "answer i". */
    private ChatDatabase.Session rounds(String mode, int n) throws Exception {
        ChatDatabase.Session s = session(mode);
        for (int i = 0; i < n; i++) {
            append(s, "u" + i, message("user", "question " + i));
            append(s, "a" + i, message("assistant", "answer " + i));
        }
        return s;
    }

    private void append(ChatDatabase.Session s, String key, JSONObject body) { db.appendTimeline(s.id, s.id + ":" + key, body); }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }

    private static JSONObject call(String id, String name, JSONObject arguments, String result) throws Exception {
        return new JSONObject().put("round", 1).put("id", id).put("name", name).put("arguments", arguments).put("result", result);
    }

    private static ChatDatabase.Provider provider() {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        return p;
    }

    private static String repeat(String unit, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(unit);
        return out.toString();
    }

    private static final class CapturingTransport implements ChatTransport {
        final String reply;
        CanonicalPrompt prompt;
        CapturingTransport(String reply) { this.reply = reply; }
        @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort,
                                     CanonicalPrompt prompt, Sink sink) {
            this.prompt = prompt;
            sink.onTextDelta(reply);
            sink.onCompleted();
            return () -> { };
        }
    }
}
