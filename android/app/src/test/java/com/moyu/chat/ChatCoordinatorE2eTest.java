package com.moyu.chat;

import static org.junit.Assert.*;

import android.database.Cursor;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * End-to-end coordinator flow on a real SQLite database with a local mock provider endpoint and
 * no Moyu Remote installed: standalone startup, provider config, streaming turns, interrupt,
 * error mapping, restart recovery, handoff drafts and API key isolation.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChatCoordinatorE2eTest {
    private static final String SECRET = "sk-SECRET-value-8f3a21";

    private MockWebServer server;
    private RecordingHost host;
    private ChatCoordinator coordinator;
    private MoyuChatApplication app;
    private FakeSecretStore secrets;
    private int requestId;

    static final class RecordingHost implements ChatCoordinator.Host {
        final List<JSONObject> envelopes = Collections.synchronizedList(new ArrayList<>());
        final List<String> toasts = Collections.synchronizedList(new ArrayList<>());
        volatile ChatDatabase.Provider editorProvider;
        volatile boolean editorOpened;
        @Override public void dispatch(JSONObject envelope) { envelopes.add(envelope); }
        @Override public void openProviderEditor(ChatDatabase.Provider existing) { editorProvider = existing; editorOpened = true; }
        @Override public void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing) { }
        @Override public void showMessage(String text) { toasts.add(text); }
    }

    /** JVM stand-in: same contract as the Keystore-backed store, which Robolectric cannot host. */
    static final class FakeSecretStore extends ChatSecretStore {
        final java.util.Map<String, String> values = new java.util.HashMap<>();
        FakeSecretStore() { super(RuntimeEnvironment.getApplication()); }
        @Override synchronized void put(String providerId, String value) { values.put(providerId, value); }
        @Override synchronized String get(String providerId) { return values.get(providerId); }
        @Override boolean has(String providerId) { return values.containsKey(providerId); }
    }

    @Before public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase("moyu-chat.db");
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"mock answer\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n");
            }
        });
        server.start();
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        host = new RecordingHost();
        secrets = new FakeSecretStore();
        coordinator = new ChatCoordinator(app, host, secrets, new UnavailableRemoteDataBridge());
        post("app.ready", new JSONObject());
    }

    @After public void tearDown() throws Exception {
        if (coordinator != null) coordinator.close();
        server.shutdown();
    }

    private static MockResponse sse(String body) { return new MockResponse().setBody(body); }

    private String post(String type, JSONObject payload) throws Exception {
        String id = "req-" + (++requestId);
        coordinator.postIntent(new JSONObject().put("version", 1).put("type", type).put("requestId", id).put("payload", payload).toString());
        return id;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("timed out waiting for: " + what);
    }

    private JSONObject latestView() {
        synchronized (host.envelopes) {
            for (int i = host.envelopes.size() - 1; i >= 0; i--) {
                JSONObject e = host.envelopes.get(i);
                if ("view.full".equals(e.optString("type"))) return e.optJSONObject("view");
            }
        }
        return null;
    }

    private JSONObject activeSession(JSONObject view) { return view == null ? null : view.optJSONObject("activeSession"); }

    private ChatDatabase.Provider mockProvider(String protocol) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "mock-provider"; p.displayName = "Mock"; p.protocol = protocol;
        p.authMode = ProviderAuth.defaultForProtocol(protocol);
        p.baseUrl = AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? server.url("/").toString() : server.url("/v1").toString();
        p.model = "mock-model";
        p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        return p;
    }

    private void configureProvider(String protocol) throws Exception {
        coordinator.saveProvider(mockProvider(protocol), SECRET);
        await("provider configured", () -> {
            JSONObject view = latestView();
            return view != null && view.optJSONArray("providers") != null && view.optJSONArray("providers").length() == 1;
        });
    }

    private String createSession() throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("session created", () -> latestView() != null && latestView().optString("activeLocalSessionId", "").length() > 0);
        return latestView().optString("activeLocalSessionId");
    }

    // ---------- standalone startup + key isolation ----------

    @Test public void worksFullyStandaloneAndNeverLeaksApiKey() throws Exception {
        await("initial view", () -> latestView() != null);
        JSONObject view = latestView();
        assertEquals("bridge absence must be a normal state, not a failure",
                false, view.optJSONObject("remoteBridge").optBoolean("available"));
        assertNotNull(view.optJSONArray("sessions"));
        assertNotNull(view.optJSONArray("providers"));

        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        String raw = "  你好 \n\n 世界 \t ";
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", raw));

        await("turn completes", () -> {
            JSONObject s = activeSession(latestView());
            if (s == null || s.optJSONArray("messages") == null) return false;
            JSONArray messages = s.optJSONArray("messages");
            if (!"completed".equals(s.optString("state")) || messages.length() < 3) return false;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if ("assistant".equals(row.optString("role")) && !row.optBoolean("streaming", false)) return true;
            }
            return false;
        });

        JSONObject session = activeSession(latestView());
        JSONArray timeline = session.optJSONArray("messages");
        JSONObject userRow = timeline.getJSONObject(0);
        assertEquals("user message must keep the raw string, trim is only an emptiness check",
                raw, userRow.optString("text"));
        boolean sawAssistant = false, sawUsage = false;
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if ("message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) sawAssistant = true;
            if ("usage".equals(row.optString("kind"))) sawUsage = true;
        }
        assertTrue("assistant answer recorded", sawAssistant);
        assertTrue("usage recorded from stream", sawUsage);
        assertTrue(session.optBoolean("canSend"));

        // API key isolation: never in any WebView envelope...
        synchronized (host.envelopes) {
            for (JSONObject envelope : host.envelopes) {
                assertFalse("api key must never enter a WebView view model", envelope.toString().contains(SECRET));
            }
        }
        // ...never in any database row...
        assertFalse("api key must never be stored in SQLite plaintext", scanDatabaseFor(SECRET));
        // ...and only ever held by the secret store, not by any persistence the coordinator touches.
        assertEquals(SECRET, secrets.get("mock-provider"));
        assertNull(app.getSharedPreferences("moyu_chat_secure", 0).getString("mock-provider", null));
    }

    private boolean scanDatabaseFor(String needle) throws Exception {
        android.database.sqlite.SQLiteDatabase raw = android.database.sqlite.SQLiteDatabase.openDatabase(
                RuntimeEnvironment.getApplication().getDatabasePath("moyu-chat.db").getPath(), null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        try (Cursor tables = raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            List<String> names = new ArrayList<>();
            while (tables.moveToNext()) names.add(tables.getString(0));
            for (String name : names) {
                if (name.startsWith("android_") || name.startsWith("sqlite_")) continue;
                try (Cursor rows = raw.rawQuery("SELECT * FROM \"" + name + "\"", null)) {
                    while (rows.moveToNext()) {
                        for (int c = 0; c < rows.getColumnCount(); c++) {
                            String value = rows.getString(c);
                            if (value != null && value.contains(needle)) return true;
                        }
                    }
                }
            }
        } finally { raw.close(); }
        return false;
    }

    // ---------- multi-turn + interrupt ----------

    @Test public void interruptKeepsPartialAnswerAndUnlocksSession() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"partial answer content\"}}]}\n\ndata: [DONE]\n\n")
                        .throttleBody(1, 50, TimeUnit.MILLISECONDS);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "slow question"));

        await("streaming starts", () -> {
            JSONObject s = activeSession(latestView());
            return s != null && s.optBoolean("canInterrupt");
        });
        await("partial text visible", () -> {
            JSONObject s = activeSession(latestView());
            if (s == null || s.optJSONArray("messages") == null) return false;
            JSONArray messages = s.optJSONArray("messages");
            for (int i = messages.length() - 1; i >= 0; i--) {
                JSONObject row = messages.optJSONObject(i);
                if ("assistant".equals(row.optString("role")) && row.optString("text").length() > 0) return true;
            }
            return false;
        });

        post("chat.session.interrupt", new JSONObject().put("localSessionId", sessionId));
        await("turn settled after interrupt", () -> {
            JSONObject s = activeSession(latestView());
            return s != null && !s.optBoolean("canInterrupt") && s.optBoolean("canSend")
                    && "completed".equals(s.optString("state"));
        });
        JSONObject session = activeSession(latestView());
        JSONArray timeline = session.optJSONArray("messages");
        JSONObject last = timeline.optJSONObject(timeline.length() - 1);
        assertEquals("assistant", last.optString("role"));
        assertFalse("partial answer is finalized, not left dangling as streaming", last.optBoolean("streaming", true));
        assertTrue("partial text is preserved", last.optString("text").length() > 0);

        // A follow-up turn must be allowed and complete normally.
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"next\"}}]}\n\ndata: [DONE]\n\n");
            }
        });
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "follow up"));
        await("second turn completes", () -> "completed".equals(activeSession(latestView()).optString("state"))
                && activeSession(latestView()).optBoolean("canSend"));
    }

    // ---------- provider error mapping ----------

    @Test public void providerHttpErrorMarksTurnFailedAndRecordsErrorRow() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(401).setBody("{}"); }
        });
        configureProvider(OpenAiCompatibleTransport.RESPONSES);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "will fail"));

        await("turn failed", () -> "failed".equals(activeSession(latestView()).optString("state")));
        JSONArray timeline = activeSession(latestView()).optJSONArray("messages");
        JSONObject errorRow = null;
        for (int i = timeline.length() - 1; i >= 0; i--) {
            JSONObject row = timeline.optJSONObject(i);
            if ("error".equals(row.optString("kind"))) { errorRow = row; break; }
        }
        assertNotNull("failed turns record an error row", errorRow);
        assertEquals("http_401", errorRow.optString("code"));
        assertTrue("the user message is kept even after failure",
                timeline.getJSONObject(0).optString("text").equals("will fail"));
        assertTrue("session accepts another turn after failure",
                activeSession(latestView()).optBoolean("canSend"));
    }

    // ---------- restart recovery of interrupted streams ----------

    @Test public void newCoordinatorRecoversStaleRunningTurnAtStartup() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        // Simulate process death mid-stream exactly as the durable layer would see it.
        ChatDatabase.Session running = app.database().getSession(sessionId);
        running.state = "running";
        app.database().saveSession(running);
        app.database().appendTimeline(sessionId, "assistant:live:" + sessionId, new JSONObject()
                .put("kind", "message").put("role", "assistant").put("text", "partial").put("streaming", true)
                .put("createdAt", java.time.Instant.now().toString()));
        coordinator.close();
        coordinator = null;

        host = new RecordingHost();
        ChatCoordinator restarted = new ChatCoordinator(app, host, secrets, new UnavailableRemoteDataBridge());
        try {
            restarted.postIntent(new JSONObject().put("version", 1).put("type", "app.ready").put("requestId", "boot").put("payload", new JSONObject()).toString());
            await("stale running session recovered to failed", () -> {
                ChatDatabase.Session s = app.database().getSession(sessionId);
                return s != null && "failed".equals(s.state);
            });
            JSONArray timeline = app.database().timeline(sessionId);
            for (int i = 0; i < timeline.length(); i++) {
                assertFalse("stale streaming projection removed on restart",
                        timeline.getJSONObject(i).optBoolean("streaming", false));
            }
        } finally { restarted.close(); }
    }

    // ---------- local branch + handoff draft stay local ----------

    @Test public void localBranchAndHandoffDraftWorkWithoutRemote() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "branch me"));
        await("turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));

        post("branch.create.local", new JSONObject().put("localSessionId", sessionId));
        await("branch session created", () -> {
            JSONObject view = latestView();
            return view != null && view.optJSONArray("sessions") != null && view.optJSONArray("sessions").length() == 2;
        });
        JSONObject branch = activeSession(latestView());
        assertNotNull(branch.optJSONObject("origin"));
        assertTrue("branch references a frozen snapshot", branch.optJSONObject("origin").optString("snapshotId").length() > 0);

        post("handoff.create", new JSONObject().put("localSessionId", sessionId));
        await("handoff draft created", () -> {
            JSONObject view = latestView();
            return view != null && view.optJSONArray("handoffs") != null && view.optJSONArray("handoffs").length() == 1;
        });
        assertEquals("draft", latestView().optJSONArray("handoffs").getJSONObject(0).optString("state"));

        post("handoff.submit", new JSONObject().put("handoffId", latestView().optJSONArray("handoffs").getJSONObject(0).optString("handoffId")));
        await("submit rejected without bridge", () -> !host.toasts.isEmpty() || host.envelopes.stream().anyMatch(e ->
                "intent.result".equals(e.optString("type")) && !e.optBoolean("ok")));
        assertEquals("failed submission stays a local draft",
                "draft", latestView().optJSONArray("handoffs").getJSONObject(0).optString("state"));
    }

    // ---------- raw input edge cases ----------

    @Test public void configureNewProviderIntentOpensBlankEditorWithoutError() throws Exception {
        // The WebView sends an empty payload for "new provider"; this used to bind null into SQLite.
        String requestId = post("provider.configure.open", new JSONObject());
        await("blank editor opened", () -> host.editorOpened);
        assertNull("new-provider editor receives a blank provider", host.editorProvider);
        synchronized (host.envelopes) {
            for (JSONObject e : host.envelopes) {
                if ("intent.result".equals(e.optString("type")) && requestId.equals(e.optString("requestId"))) {
                    assertTrue("configure open must not fail: " + e, e.optBoolean("ok"));
                }
            }
        }

        host.editorOpened = false;
        host.editorProvider = null;
        post("provider.configure.open", new JSONObject().put("providerId", "missing"));
        await("unknown provider id falls back to blank editor", () -> host.editorOpened);
        assertNull(host.editorProvider);
    }

    @Test public void whitespaceOnlyInputIsRejectedWithoutSideEffects() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "   \n\t  "));
        Thread.sleep(400);
        JSONArray timeline = app.database().timeline(sessionId);
        assertEquals("whitespace-only input must not create timeline rows", 0, timeline.length());
        assertEquals("idle", app.database().getSession(sessionId).state);
    }

    @Test public void anthropicProtocolEndToEnd() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":9,\"output_tokens\":1}}}\n\n"
                        + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n"
                        + "data: {\"type\":\"message_stop\"}\n\n");
            }
        });
        configureProvider(AnthropicMessagesTransport.PROTOCOL);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hi"));
        await("anthropic turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("anthropic-version header present", "2023-06-01", request.getHeader("anthropic-version"));
        assertTrue("claude system prompt is the canonical instruction",
                new JSONObject(request.getBody().readUtf8()).optString("system").contains("Moyu Chat"));
    }
}
