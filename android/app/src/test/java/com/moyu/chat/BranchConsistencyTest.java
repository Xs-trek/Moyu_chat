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
 * Spec H1, H2: a session's history is its own whether it was typed there or inherited by a new-session branch. A branch
 * and an ordinary Chat with the same messages get the same context messages and the same recalled excerpts; only the
 * excerpt labels say where a message came from.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class BranchConsistencyTest {
    private static final String[][] EARLIER = {
            {"user", "The staging database password rotates every Tuesday at 09:30."},
            {"assistant", "Noted: rotation on Tuesday 09:30, owned by the platform team."},
            {"user", "The release branch is called hotfix-maple and freezes on Friday."},
            {"assistant", "Release branch hotfix-maple noted; freeze on Friday."},
    };
    private static final String CURRENT = "Which of these dates affect the cron job?";

    private ChatDatabase db;
    private ChatDatabase.Session plain, branch;

    @Before public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        db = ((MoyuChatApplication) RuntimeEnvironment.getApplication()).database();
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.id = "p"; provider.displayName = "P"; provider.baseUrl = "https://example.test/v1";
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; provider.model = "m";
        provider.contextTokens = 32000; provider.maxOutputTokens = 4096;
        ChatDatabase.ModelProfile profile = TestDeployments.save(db, provider);

        plain = db.createSession(profile, "plain", null, null, ReasoningPolicy.DEFAULT);
        ChatDatabase.Session root = db.createSession(profile, "root", null, null, ReasoningPolicy.DEFAULT);
        for (int i = 0; i < EARLIER.length; i++) {
            db.appendTimeline(plain.id, "p" + i, message(EARLIER[i][0], EARLIER[i][1]));
            db.appendTimeline(root.id, "r" + i, message(EARLIER[i][0], EARLIER[i][1]));
        }
        branch = db.createSession(profile, "branch", new SnapshotService(db).createLocal(root.id, null).id, root.id, ReasoningPolicy.DEFAULT);
        db.appendTimeline(plain.id, "pc", message("user", CURRENT));
        db.appendTimeline(branch.id, "bc", message("user", CURRENT));
        plain = db.getSession(plain.id);
        branch = db.getSession(branch.id);
    }

    @Test public void theHistoryIsTheSameMessages() throws Exception {
        assertEquals(visibleTexts(SessionHistory.combined(db, plain)), visibleTexts(SessionHistory.combined(db, branch)));
    }

    @Test public void theContextCarriesTheSameMessages() throws Exception {
        ContextBuilder contexts = new ContextBuilder(db);
        CanonicalPrompt a = contexts.buildCommitted(plain, 32_000, 0, 4_096, "");
        CanonicalPrompt b = contexts.buildCommitted(branch, 32_000, 0, 4_096, "");
        assertEquals(EARLIER.length + 1, a.messages.length());
        assertEquals(texts(a.messages), texts(b.messages));
        assertEquals(a.inheritedItemCount + a.localItemCount, b.inheritedItemCount + b.localItemCount);
    }

    @Test public void recallFindsTheSameExcerpts() throws Exception {
        TurnPipeline pipeline = new TurnPipeline(null, db, null, null, null);
        JSONArray carried = new JSONArray().put(new JSONObject().put("role", "user").put("text", CURRENT));
        CanonicalPrompt sent = new CanonicalPrompt("", carried, true, 0, 0, carried.length());
        for (String query : new String[]{"rotation Tuesday", "hotfix maple Friday", "quaternion"}) {
            String a = pipeline.recallHistory(plain.id, sent, query), b = pipeline.recallHistory(branch.id, sent, query);
            assertEquals(query, excerpts(a), excerpts(b));
        }
        assertTrue(pipeline.recallHistory(branch.id, sent, "rotation Tuesday").contains("[before this branch, message 1, user]"));
    }

    /** The recalled text without the labels that say where each excerpt came from. */
    private static String excerpts(String recalled) {
        StringBuilder out = new StringBuilder();
        for (String line : recalled.split("\n")) if (!line.startsWith("[")) out.append(line).append('\n');
        return out.toString();
    }

    private static String visibleTexts(JSONArray rows) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !"message".equals(row.optString("kind", "message"))) continue;
            out.append(row.optString("role")).append(": ").append(row.optString("text")).append('\n');
        }
        return out.toString();
    }

    private static String texts(JSONArray messages) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            out.append(m.optString("role")).append(": ").append(m.optString("text")).append('\n');
        }
        return out.toString();
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }
}
