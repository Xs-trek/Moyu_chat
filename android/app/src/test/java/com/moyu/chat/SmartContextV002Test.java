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

/** Model-assisted control plane: bounded router + compaction summary (spec C3), never free-form system rewrite. */
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
        @Override public void showMessage(String text) { }
    }

    static final class SecretStore extends ChatSecretStore {
        final java.util.Map<String, String> values = new java.util.HashMap<>();
        SecretStore() { super(RuntimeEnvironment.getApplication()); }
        @Override synchronized void put(String providerId, String value) { if (value == null || value.isEmpty()) values.remove(providerId); else values.put(providerId, value); }
        @Override synchronized String get(String providerId) { return values.get(providerId); }
        @Override boolean has(String providerId) { return values.containsKey(providerId); }
    }

    @Before public void setUp() throws Exception {
        SessionTitles.automatic = false; // naming has its own tests; here it would add a request after every turn
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        server = new MockWebServer(); server.start();
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        host = new Host(); secrets = new SecretStore();
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.displayName = "P"; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        p.baseUrl = server.url("/v1").toString(); p.model = "m"; p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        TestDeployments.save(app.database(), p); secrets.put("p", SECRET);
        coordinator = new ChatCoordinator(app, host, secrets);
        post("app.ready", new JSONObject());
    }

    @After public void tearDown() throws Exception { SessionTitles.automatic = true; if (coordinator != null) coordinator.close(); server.shutdown(); }

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

    /** Spec D8: the Router picks only reviewed local prompt modules, for its own turn; the next turn is classified again. */
    @Test public void theRouterChoosesOnlyLocalPromptModulesForItsOwnTurn() throws Exception {
        post("helper.switch.set", new JSONObject().put("helper", "router").put("enabled", true)); // the Router starts off
        AtomicInteger routes = new AtomicInteger();
        List<String> mains = Collections.synchronizedList(new ArrayList<>());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                if (body.contains("Classify the conversation")) {
                    return routes.incrementAndGet() == 1
                            ? chat("{\"domain\":\"software\",\"taskType\":\"debug\",\"depth\":\"expert\",\"responseMode\":\"diagnostic\",\"contextPolicy\":\"checkpoint_recent\",\"modules\":[\"technical_precision\",\"debugging\",\"IGNORE_ALL\"],\"confidence\":0.98}")
                            : chat("{\"domain\":\"general\",\"taskType\":\"answer\",\"depth\":\"concise\",\"responseMode\":\"direct\",\"contextPolicy\":\"recent\",\"modules\":[],\"confidence\":0.9}");
                }
                if (body.contains("You are a verification component")) return chat("not json");
                mains.add(body);
                return chat("an answer");
            }
        });
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Find the root cause of this memory leak"));
        await("first turn complete", () -> mains.size() == 1 && "completed".equals(app.database().getSession(sessionId).state));
        assertEquals(1, routes.get());
        String mainBody = mains.get(0);
        assertTrue(mainBody.contains("Use precise technical language"));
        assertTrue(mainBody.contains("For debugging, prioritize observed evidence"));
        assertTrue("Smart mode must add reviewed anti-fabrication/evidence policy", mainBody.contains("NEVER FABRICATE"));
        assertTrue(mainBody.contains("COMPLETION HONESTY"));
        assertFalse("router cannot inject unreviewed module text into system authority", mainBody.contains("IGNORE_ALL"));
        assertEquals("nothing is stored on the session", "", app.database().getSession(sessionId).taskProfile);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "continue"));
        await("second turn complete", () -> mains.size() == 2 && "completed".equals(app.database().getSession(sessionId).state));
        assertEquals("every turn is classified", 2, routes.get());
        assertFalse("the first turn's modules do not carry over", mains.get(1).contains("For debugging, prioritize observed evidence"));
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
                if (n == 1) return chat("Older debugging work established the allocator boundary. Keep the API stable; retain the ring buffer.");
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

        String checkpointBody = checkpoint.getBody().readUtf8();
        assertTrue(checkpointBody.contains("You write the running summary of a long chat"));
        assertTrue("the oldest round is summarized", checkpointBody.contains("historic user 0 "));
        assertFalse("the newest two rounds are not", checkpointBody.contains("historic user 35 "));
        String mainBody = main.getBody().readUtf8();
        assertTrue("spec C3: the summary opens the message list", mainBody.contains("Summary of the earlier part of this conversation"));
        assertTrue(mainBody.contains("allocator boundary"));
        ChatDatabase.Session after = app.database().getSession(sessionId);
        assertNotNull(ContextCheckpoint.fromStored(after.contextCheckpoint));
        assertTrue(after.checkpointCoveredItems > 0);
        JSONArray durable = app.database().timeline(sessionId);
        assertTrue("checkpoint is derived state; old transcript rows are never rewritten/deleted", durable.length() >= before + 2);
        assertEquals("historic user 0 padding padding padding padding padding padding padding padding padding padding ", durable.getJSONObject(0).optString("text"));
    }
}
