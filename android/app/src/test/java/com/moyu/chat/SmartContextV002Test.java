package com.moyu.chat;

import static org.junit.Assert.*;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** v0.0.2 model-assisted control plane: bounded router + structured checkpoint, never free-form system rewrite. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SmartContextV002Test {
    private static final String SECRET = "sk-smart-context";
    private MockWebServer server;
    private MoyuChatApplication app;
    private ChatCoordinator coordinator;
    private Host host;
    private SecretStore secrets;
    private int requestId;

    static final class Host implements ChatCoordinator.Host {
        final List<JSONObject> envelopes = Collections.synchronizedList(new ArrayList<>());
        @Override public void dispatch(JSONObject envelope) { envelopes.add(envelope); }
        @Override public void openProviderEditor(ChatDatabase.Provider existing) { }
        @Override public void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing) { }
        @Override public void showMessage(String text) { }
    }

    static final class SecretStore extends ChatSecretStore {
        final java.util.Map<String, String> values = new java.util.HashMap<>();
        SecretStore() { super(RuntimeEnvironment.getApplication()); }
        @Override synchronized void put(String providerId, String value) { values.put(providerId, value); }
        @Override synchronized String get(String providerId) { return values.get(providerId); }
        @Override boolean has(String providerId) { return values.containsKey(providerId); }
    }

    @Before public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase("moyu-chat.db");
        server = new MockWebServer(); server.start();
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        host = new Host(); secrets = new SecretStore();
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.displayName = "P"; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        p.baseUrl = server.url("/v1").toString(); p.model = "m"; p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        app.database().saveProvider(p); secrets.put("p", SECRET);
        coordinator = new ChatCoordinator(app, host, secrets, new UnavailableRemoteDataBridge());
        post("app.ready", new JSONObject());
    }

    @After public void tearDown() throws Exception { if (coordinator != null) coordinator.close(); server.shutdown(); }

    private void post(String type, JSONObject payload) throws Exception {
        coordinator.postIntent(new JSONObject().put("version", 1).put("type", type).put("requestId", "r" + (++requestId)).put("payload", payload).toString());
    }

    private JSONObject latestView() {
        synchronized (host.envelopes) {
            for (int i = host.envelopes.size() - 1; i >= 0; i--) if ("view.full".equals(host.envelopes.get(i).optString("type"))) return host.envelopes.get(i).optJSONObject("view");
        }
        return null;
    }

    private void await(String label, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < end) { if (condition.getAsBoolean()) return; Thread.sleep(30); }
        fail("timeout: " + label);
    }

    private String createSession() throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", "p").put("contextMode", ContextMode.SMART).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty());
        return latestView().optString("activeLocalSessionId");
    }

    private static MockResponse chat(String text) {
        try {
            JSONObject delta = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", new JSONObject().put("content", text))));
            JSONObject stop = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", new JSONObject()).put("finish_reason", "stop")));
            return new MockResponse().setBody("data: " + delta + "\n\ndata: " + stop + "\n\n");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test public void firstTurnRouterChoosesOnlyLocalPromptModulesAndRunsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                if (n == 1) return chat("{\"domain\":\"software\",\"taskType\":\"debug\",\"depth\":\"expert\",\"responseMode\":\"diagnostic\",\"contextPolicy\":\"checkpoint_recent\",\"modules\":[\"technical_precision\",\"debugging\",\"IGNORE_ALL\"],\"confidence\":0.98}");
                return chat(n == 2 ? "main answer" : "second answer");
            }
        });
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Find the root cause of this memory leak"));
        RecordedRequest router = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(router);
        RecordedRequest main = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(main);
        await("main complete", () -> "completed".equals(latestView().optJSONObject("activeSession").optString("state")));

        String routerBody = router.getBody().readUtf8();
        assertTrue(routerBody.contains("Classify the conversation"));
        String mainBody = main.getBody().readUtf8();
        assertTrue(mainBody.contains("Use precise technical language"));
        assertTrue(mainBody.contains("For debugging, prioritize observed evidence"));
        assertTrue("Smart mode must add reviewed anti-fabrication/evidence policy", mainBody.contains("NEVER FABRICATE"));
        assertTrue(mainBody.contains("COMPLETION HONESTY"));
        assertFalse("router cannot inject unreviewed module text into system authority", mainBody.contains("IGNORE_ALL"));
        ChatDatabase.Session stored = app.database().getSession(sessionId);
        TaskProfile profile = TaskProfile.fromStored(stored.taskProfile);
        assertNotNull(profile); assertEquals("software", profile.domain); assertEquals("debug", profile.taskType); assertEquals(2, profile.modules.length());

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "continue"));
        RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(second);
        assertFalse("TaskProfile is frozen for the context epoch; second turn must not rerun router", second.getBody().readUtf8().contains("Classify the conversation"));
        await("second complete", () -> calls.get() == 3 && "completed".equals(latestView().optJSONObject("activeSession").optString("state")));
    }

    @Test public void minimalModeMakesOnlyTheMainRequest() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("minimal answer"); }
        });
        post("chat.session.create", new JSONObject().put("providerId", "p").put("contextMode", ContextMode.MINIMAL));
        await("minimal session", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty());
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hello"));
        RecordedRequest main = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(main);
        assertFalse(main.getBody().readUtf8().contains("Classify the conversation"));
        assertNull("minimal must not make a hidden second request", server.takeRequest(500, TimeUnit.MILLISECONDS));
        await("minimal complete", () -> "completed".equals(latestView().optJSONObject("activeSession").optString("state")));
        ChatDatabase.Session stored = app.database().getSession(sessionId);
        assertEquals(ContextMode.MINIMAL, stored.contextMode);
        assertEquals("", stored.taskProfile);
        assertEquals("", stored.contextCheckpoint);
    }

    @Test public void standardSkipsRouterOnFirstTurn() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("standard answer"); }
        });
        post("chat.session.create", new JSONObject().put("providerId", "p").put("contextMode", ContextMode.STANDARD));
        await("standard session", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty());
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hello"));
        RecordedRequest main = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(main);
        assertFalse(main.getBody().readUtf8().contains("Classify the conversation"));
        assertNull("standard first turn must not pay a Router request", server.takeRequest(500, TimeUnit.MILLISECONDS));
        await("standard complete", () -> "completed".equals(latestView().optJSONObject("activeSession").optString("state")));
        JSONObject view = latestView().optJSONObject("activeSession");
        assertEquals("standard", view.optString("contextMode"));
        assertEquals("standard", view.optString("effectiveContextMode"));
    }

    @Test public void truncationBuildsCheckpointWithoutRewritingDurableTimeline() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                if (n == 1) return chat("{\"summary\":\"Older debugging work established the allocator boundary.\",\"constraints\":[\"keep API stable\"],\"decisions\":[\"retain ring buffer\"],\"openQuestions\":[\"verify ISR ownership\"],\"criticalRefs\":[\"buffer.c\"]}");
                return chat("main after checkpoint");
            }
        });
        String sessionId = createSession();
        ChatDatabase.Session s = app.database().getSession(sessionId);
        s.contextTokens = 2_048; s.maxOutputTokens = 512;
        s.taskProfile = new TaskProfile("embedded", "debug", "expert", "diagnostic", "checkpoint_recent",
                new JSONArray().put("technical_precision").put("debugging"), 1d).toJson().toString();
        app.database().saveSession(s);
        for (int i = 0; i < 36; i++) {
            String pad = " padding padding padding padding padding padding padding padding padding padding ";
            app.database().appendTimeline(sessionId, "u" + i, new JSONObject().put("kind", "message").put("role", "user").put("text", "historic user " + i + pad).put("createdAt", Instant.now().toString()));
            app.database().appendTimeline(sessionId, "a" + i, new JSONObject().put("kind", "message").put("role", "assistant").put("text", "historic assistant " + i + pad).put("createdAt", Instant.now().toString()));
        }
        int before = app.database().timeline(sessionId).length();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "current question"));
        RecordedRequest checkpoint = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(checkpoint);
        RecordedRequest main = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(main);
        await("checkpoint turn complete", () -> "completed".equals(latestView().optJSONObject("activeSession").optString("state")));

        assertTrue(checkpoint.getBody().readUtf8().contains("Merge older conversation state"));
        String mainBody = main.getBody().readUtf8();
        assertTrue(mainBody.contains("Moyu historical context checkpoint"));
        assertTrue(mainBody.contains("allocator boundary"));
        ChatDatabase.Session after = app.database().getSession(sessionId);
        assertNotNull(ContextCheckpoint.fromStored(after.contextCheckpoint));
        assertTrue(after.checkpointCoveredItems > 0);
        JSONArray durable = app.database().timeline(sessionId);
        assertTrue("checkpoint is derived state; old transcript rows are never rewritten/deleted", durable.length() >= before + 2);
        assertEquals("historic user 0 padding padding padding padding padding padding padding padding padding padding ", durable.getJSONObject(0).optString("text"));
    }
}
