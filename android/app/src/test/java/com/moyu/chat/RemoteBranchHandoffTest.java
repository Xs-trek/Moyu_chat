package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinator-level cross-APK flow with a fake bridge: import is a one-shot local copy, the branch
 * never dereferences the Remote source again, and handoff submission is explicit-only.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RemoteBranchHandoffTest {

    /** Minimal in-memory stand-in for the Remote ContentProvider bridge. */
    static final class FakeRemoteBridge implements RemoteDataBridge {
        final List<String> submitted = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger snapshotCalls = new AtomicInteger();
        volatile boolean available = true;
        String lastThroughSeqSource;

        @Override public Status status() { return new Status(available, available ? 1 : 0, available ? null : "disabled"); }

        @Override public JSONArray listSources() {
            JSONArray items = new JSONArray();
            try {
                items.put(new JSONObject().put("sourceId", "opaque-1").put("title", "PC session")
                        .put("runtime", "codex").put("updatedAt", Instant.now().toString())
                        .put("cachedThroughSeq", 120).put("complete", true));
            } catch (Exception ignored) { }
            return items;
        }

        @Override public JSONObject createSnapshot(String sourceId, Integer throughSeq) {
            snapshotCalls.incrementAndGet();
            lastThroughSeqSource = sourceId;
            try {
                return new JSONObject()
                        .put("snapshotId", "remote-export-1").put("sourceId", sourceId)
                        .put("sourceTitle", "PC session").put("sourceRuntime", "codex")
                        .put("createdAt", Instant.now().toString())
                        .put("items", new JSONArray()
                                .put(new JSONObject().put("kind", "message").put("role", "user").put("text", "pc question").put("createdAt", Instant.now().toString()))
                                .put(new JSONObject().put("kind", "message").put("role", "assistant").put("text", "pc answer").put("createdAt", Instant.now().toString())));
            } catch (Exception error) { throw new IllegalStateException(error); }
        }

        @Override public void submitHandoff(JSONObject handoff) { submitted.add(handoff.toString()); }
    }

    @Test public void remoteBranchImportsSnapshotLocallyAndNeverDereferencesRemoteAgain() throws Exception {
        MoyuChatApplication app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        app.deleteDatabase("moyu-chat.db");
        FakeRemoteBridge bridge = new FakeRemoteBridge();
        ChatCoordinatorE2eTest.RecordingHost host = new ChatCoordinatorE2eTest.RecordingHost();
        ChatCoordinatorE2eTest.FakeSecretStore secrets = new ChatCoordinatorE2eTest.FakeSecretStore();
        ChatCoordinator coordinator = new ChatCoordinator(app, host, secrets, bridge);
        try {
            coordinator.postIntent(intent("app.ready"));
            ChatDatabase.Provider provider = new ChatDatabase.Provider();
            provider.id = "p"; provider.displayName = "P"; provider.protocol = "openai-responses";
            provider.baseUrl = "https://api.example.com/v1"; provider.model = "m";
            coordinator.saveProvider(provider, "key");
            awaitTrue(() -> !app.database().listProviders().isEmpty());

            coordinator.postIntent(intent("branch.create.remote", new JSONObject().put("sourceId", "opaque-1")));
            awaitTrue(() -> app.database().listSessions().size() == 1);
            assertEquals(1, bridge.snapshotCalls.get());

            ChatDatabase.Session branch = app.database().listSessions().get(0);
            assertNotNull("branch is anchored to a local snapshot copy", branch.originSnapshotId);
            ChatDatabase.Snapshot snapshot = app.database().getSnapshot(branch.originSnapshotId);
            assertEquals("remote", snapshot.sourceType);
            assertEquals("codex", snapshot.sourceRuntime);
            JSONArray items = app.database().snapshotItems(snapshot.id);
            assertEquals(2, items.length());
            assertEquals("pc question", items.getJSONObject(0).optString("text"));

            // Bridge goes away: the imported branch must keep working purely from local state.
            bridge.available = false;
            ContextBuilder contexts = new ContextBuilder(app.database());
            CanonicalPrompt prompt = contexts.build(branch, 32_000, 4_096, "continue locally");
            assertEquals("pc question", prompt.messages.getJSONObject(0).optString("text"));
            assertEquals("continue locally", prompt.messages.getJSONObject(prompt.messages.length() - 1).optString("text"));
            assertTrue(prompt.instructions.contains("immutable historical snapshot"));

            // A second snapshot call must never happen for this branch again.
            assertEquals(1, bridge.snapshotCalls.get());
        } finally { coordinator.close(); }
    }

    @Test public void handoffSubmitsOnlyExplicitlyAndNeverAutoResends() throws Exception {
        MoyuChatApplication app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        app.deleteDatabase("moyu-chat.db");
        FakeRemoteBridge bridge = new FakeRemoteBridge();
        ChatCoordinatorE2eTest.RecordingHost host = new ChatCoordinatorE2eTest.RecordingHost();
        ChatCoordinatorE2eTest.FakeSecretStore secrets = new ChatCoordinatorE2eTest.FakeSecretStore();
        ChatDatabase db = app.database();
        db.saveProvider(provider());
        db.createSession("p", "m", null, null);
        ChatDatabase.Session session = db.listSessions().get(0);
        db.appendTimeline(session.id, "u", message("user", "question"));
        db.appendTimeline(session.id, "a", message("assistant", "conclusion text"));

        ChatCoordinator coordinator = new ChatCoordinator(app, host, secrets, bridge);
        try {
            coordinator.postIntent(intent("app.ready"));
            coordinator.postIntent(intent("handoff.create", new JSONObject().put("localSessionId", session.id)));
            awaitTrue(() -> db.listHandoffs().size() == 1);
            assertTrue("draft handoff is not sent until explicit submit", bridge.submitted.isEmpty());

            coordinator.postIntent(intent("handoff.submit", new JSONObject().put("handoffId", db.listHandoffs().get(0).id)));
            awaitTrue(() -> !bridge.submitted.isEmpty());
            assertEquals("submitted", db.listHandoffs().get(0).state);

            // Repeated app starts never re-submit an already-submitted handoff.
            ChatDatabase.Handoff h = db.listHandoffs().get(0);
            coordinator.postIntent(intent("handoff.submit", new JSONObject().put("handoffId", h.id)));
            Thread.sleep(300);
            assertEquals("no auto resend or duplication", 1, bridge.submitted.size());
        } finally { coordinator.close(); }
    }

    private static String intent(String type) throws Exception { return intent(type, new JSONObject()); }

    private static String intent(String type, JSONObject payload) throws Exception {
        return new JSONObject().put("version", 1).put("type", type).put("requestId", "r-" + Math.random()).put("payload", payload).toString();
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }

    private static ChatDatabase.Provider provider() {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.displayName = "P"; p.protocol = "openai-responses";
        p.baseUrl = "https://api.example.com/v1"; p.model = "m";
        return p;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("condition not met in time");
    }
}
