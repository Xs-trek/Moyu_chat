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

/** Spec H1, H7, C3, D9: only the current path is history; other versions never reach the model. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MessageTreeTest {
    private ChatDatabase db;
    private ChatDatabase.Session session;

    @Before public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        db = ((MoyuChatApplication) RuntimeEnvironment.getApplication()).database();
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.id = "p"; provider.displayName = "P"; provider.baseUrl = "https://example.test/v1";
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; provider.model = "m";
        provider.contextTokens = 32_000; provider.maxOutputTokens = 4_096;
        ChatDatabase.ModelProfile profile = TestDeployments.save(db, provider);
        session = db.createSession(profile, "tree", null, null, ReasoningPolicy.DEFAULT);
        // Two versions of the second question: the old one names a PELICAN, the edited one an OTTER.
        append("u1", "user", "Plan a trip to the coast.");
        append("a1", "assistant", "Sure, which week?");
        append("u2", "user", "The week the PELICAN festival runs.");
        append("a2", "assistant", "PELICAN week it is.");
        db.moveLeafBefore(session.id, 3);
        append("u2b", "user", "The week the OTTER festival runs.");
        append("a2b", "assistant", "OTTER week it is.");
        session = db.getSession(session.id);
    }

    @Test public void theContextCarriesOnlyThePathsVersion() throws Exception {
        CanonicalPrompt prompt = new ContextBuilder(db).buildCommitted(session, 32_000, 0, 4_096, "");
        assertTrue(prompt.carries("OTTER festival"));
        assertFalse("the other version is not history", prompt.carries("PELICAN"));
        assertEquals(4, prompt.messages.length());
    }

    @Test public void recallDoesNotFindTheOtherVersion() throws Exception {
        TurnPipeline pipeline = new TurnPipeline(null, db, null, null, null);
        JSONArray carried = new JSONArray().put(new JSONObject().put("role", "user").put("text", "now"));
        CanonicalPrompt sent = new CanonicalPrompt("", carried, true, 0, 0, 1);
        assertTrue(pipeline.recallHistory(session.id, sent, "OTTER festival").contains("OTTER festival"));
        assertFalse("the other version is not recalled", pipeline.recallHistory(session.id, sent, "PELICAN festival").contains("PELICAN"));
    }

    @Test public void aSummaryOfOnePathIsNotUsedOnAnotherAndIsBackWhenSwitchedBack() throws Exception {
        ContextBuilder contexts = new ContextBuilder(db);
        JSONArray history = contexts.neutralHistory(session);
        db.setContextCheckpoint(session.id, new ContextCheckpoint("They planned an OTTER week.", ContextCheckpoint.coverageHash(history, 4)).toJson().toString(), 4);
        assertEquals(4, contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, "").summarizedItems);

        db.selectVersion(session.id, 3); // the PELICAN version
        assertEquals("another path: the summary does not describe it", 0, contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, "").summarizedItems);
        db.selectVersion(session.id, 5); // back to OTTER
        assertEquals("kept, so usable again", 4, contexts.buildCommitted(db.getSession(session.id), 32_000, 0, 4_096, "").summarizedItems);
    }

    @Test public void aBranchCopiesThePathOnly() throws Exception {
        ChatDatabase.Snapshot snapshot = new SnapshotService(db).createLocal(session.id, null);
        String copied = db.snapshotItems(snapshot.id).toString();
        assertTrue(copied.contains("OTTER"));
        assertFalse(copied.contains("PELICAN"));
    }

    private void append(String key, String role, String text) throws Exception {
        db.appendTimeline(session.id, session.id + ":" + key, new JSONObject().put("kind", "message").put("role", role).put("text", text)
                .put("createdAt", Instant.now().toString()));
    }
}
