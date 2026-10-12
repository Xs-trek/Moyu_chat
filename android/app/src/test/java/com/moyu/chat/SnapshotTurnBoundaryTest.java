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
import java.util.Arrays;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SnapshotTurnBoundaryTest {
    private ChatDatabase db;
    private SnapshotService snapshots;
    private ChatDatabase.Session session;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("moyu-chat.db");
        db = new ChatDatabase(context);
        db.getWritableDatabase();
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.id = "snapshot-provider";
        provider.displayName = "Snapshot Provider";
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        provider.baseUrl = "https://snapshot.example/v1";
        provider.model = "model";
        TestDeployments.save(db, provider);
        session = TestDeployments.session(db, provider.id, provider.model, "snapshot", null);
        snapshots = new SnapshotService(db);
    }

    @Test public void capacityTruncationKeepsWholeNewestUserLedTurns() throws Exception {
        String oldUser = "U1-" + repeat('u', 79_000);
        String oldAssistant = "A1-" + repeat('a', 79_000);
        String newUser = "U2-" + repeat('x', 79_000);
        String newAssistant = "A2-" + repeat('y', 79_000);
        db.appendTimeline(session.id, "u1", message("user", oldUser));
        db.appendTimeline(session.id, "a1", message("assistant", oldAssistant));
        db.appendTimeline(session.id, "u2", message("user", newUser));
        db.appendTimeline(session.id, "a2", message("assistant", newAssistant));

        ChatDatabase.Snapshot snapshot = snapshots.createLocal(session.id, null);
        JSONArray items = db.snapshotItems(snapshot.id);

        assertTrue(snapshot.truncated);
        assertEquals(4, snapshot.sourceItemCount);
        assertEquals("old turn must be dropped as a whole instead of keeping its assistant half", 2, items.length());
        assertEquals("user", items.getJSONObject(0).optString("role"));
        assertTrue(items.getJSONObject(0).optString("text").startsWith("U2-"));
        assertEquals("assistant", items.getJSONObject(1).optString("role"));
        assertTrue(items.getJSONObject(1).optString("text").startsWith("A2-"));
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text)
                .put("createdAt", Instant.now().toString());
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }
}
