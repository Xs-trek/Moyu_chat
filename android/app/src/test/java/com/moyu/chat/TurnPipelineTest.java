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

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Stage-level behaviour of one turn: which stages each context mode runs, why the others are skipped,
 * which stage paid for each helper model call, and that a stop between stages never reaches the provider.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class TurnPipelineTest {
    private static final String SECRET = "sk-turn-pipeline";
    private MockWebServer server;
    private MoyuChatApplication app;
    private ChatCoordinator coordinator;
    private SmartContextV002Test.Host host;
    private int requestId;

    @Before public void setUp() throws Exception {
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        server = new MockWebServer(); server.start();
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        host = new SmartContextV002Test.Host();
        SmartContextV002Test.SecretStore secrets = new SmartContextV002Test.SecretStore();
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.displayName = "P"; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        p.baseUrl = server.url("/v1").toString(); p.model = "m"; p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        TestDeployments.save(app.database(), p); secrets.put("p", SECRET);
        coordinator = new ChatCoordinator(app, host, secrets);
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

    private static void await(String label, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < end) { if (condition.getAsBoolean()) return; Thread.sleep(30); }
        fail("timeout: " + label);
    }

    private static MockResponse chat(String text) {
        try {
            JSONObject delta = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", new JSONObject().put("content", text))));
            JSONObject stop = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("delta", new JSONObject()).put("finish_reason", "stop")))
                    .put("usage", new JSONObject().put("prompt_tokens", 100).put("completion_tokens", 10));
            return new MockResponse().setBody("data: " + delta + "\n\ndata: " + stop + "\n\n");
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Sends one message in a fresh session and returns the finished turn's trace. */
    private TurnTrace runTurn(String contextMode, String text) throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", "p").put("contextMode", contextMode).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty());
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", text));
        await("turn finished", () -> coordinator.lastTrace(sessionId) != null && !coordinator.lastTrace(sessionId).terminalStatus().isEmpty());
        return coordinator.lastTrace(sessionId);
    }

    @Test public void minimalModeRunsOnlyTheMainCallAndSaysWhyHelpersWereSkipped() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("minimal answer"); }
        });
        TurnTrace trace = runTurn(ContextMode.MINIMAL, "hello");

        assertEquals(TurnTrace.OK, trace.terminalStatus());
        assertEquals(Arrays.asList(TurnTrace.ACCEPT, TurnTrace.CONTEXT, TurnTrace.WEB_PLAN, TurnTrace.TOOLS, TurnTrace.GENERATE), trace.stages(TurnTrace.OK));
        assertEquals("mode_off", trace.reason(TurnTrace.ROUTE));
        assertEquals("mode_off", trace.reason(TurnTrace.CHECKPOINT));
        assertEquals("web_not_needed", trace.reason(TurnTrace.WEB_RETRIEVE));
        assertEquals("mode_off", trace.reason(TurnTrace.VERIFY));
        assertEquals(1, trace.usage(TurnTrace.GENERATE).optInt("calls"));
        assertNull("no helper call was made", trace.usage(TurnTrace.ROUTE));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void standardModeWatchesTheBudgetButNeverRoutesOrVerifies() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("standard answer"); }
        });
        TurnTrace trace = runTurn(ContextMode.STANDARD, "hello");

        assertEquals(TurnTrace.OK, trace.terminalStatus());
        assertEquals("mode_off", trace.reason(TurnTrace.ROUTE));
        assertEquals("compaction is enabled but a short chat fits", "within_budget", trace.reason(TurnTrace.CHECKPOINT));
        assertEquals("mode_off", trace.reason(TurnTrace.VERIFY));
        assertEquals(1, server.getRequestCount());
    }

    @Test public void smartModeAttributesRouterVerifierAndMainCallsToTheirStages() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                int n = calls.incrementAndGet();
                if (n == 1) return chat("{\"domain\":\"software\",\"taskType\":\"debug\",\"depth\":\"expert\",\"responseMode\":\"diagnostic\",\"contextPolicy\":\"checkpoint_recent\",\"modules\":[],\"confidence\":0.9}");
                if (n == 2) return chat("main answer");
                return chat("not a verifier verdict");
            }
        });
        TurnTrace trace = runTurn(ContextMode.SMART, "Find the root cause of this crash");

        assertEquals(TurnTrace.OK, trace.terminalStatus());
        assertEquals(TurnTrace.OK, trace.status(TurnTrace.ROUTE));
        assertEquals("a debug task under the balanced preset is verified", TurnTrace.OK, trace.status(TurnTrace.VERIFY));
        assertEquals(1, trace.usage(TurnTrace.ROUTE).optInt("calls"));
        assertEquals(1, trace.usage(TurnTrace.GENERATE).optInt("calls"));
        assertTrue(trace.usage(TurnTrace.VERIFY).optInt("calls") >= 1);
        assertEquals(100, trace.usage(TurnTrace.ROUTE).optInt("inputTokens"));

        JSONArray json = trace.toJson();
        JSONObject route = null;
        for (int i = 0; i < json.length(); i++) if (TurnTrace.ROUTE.equals(json.getJSONObject(i).optString("stage"))) route = json.getJSONObject(i);
        assertNotNull(route);
        assertEquals("debug", route.getJSONObject("detail").optString("taskType"));
        assertEquals(1, route.getJSONObject("usage").optInt("calls"));
    }

    @Test public void stopDuringPreparationNeverReachesTheMainCall() throws Exception {
        CountDownLatch routerHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                routerHeld.countDown();
                release.await(10, TimeUnit.SECONDS);
                return chat("{\"domain\":\"general\",\"taskType\":\"answer\",\"depth\":\"standard\",\"responseMode\":\"direct\",\"contextPolicy\":\"recent\",\"modules\":[],\"confidence\":0.5}");
            }
        });
        post("chat.session.create", new JSONObject().put("providerId", "p").put("contextMode", ContextMode.SMART).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty());
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hello"));
        assertTrue("router call started", routerHeld.await(10, TimeUnit.SECONDS));
        post("chat.session.interrupt", new JSONObject().put("localSessionId", sessionId));
        release.countDown();
        await("turn finished", () -> coordinator.lastTrace(sessionId) != null && !coordinator.lastTrace(sessionId).terminalStatus().isEmpty());

        TurnTrace trace = coordinator.lastTrace(sessionId);
        assertNotEquals(TurnTrace.OK, trace.terminalStatus());
        assertNull("generation never started", trace.status(TurnTrace.GENERATE));
        assertEquals("only the router request reached the provider", 1, server.getRequestCount());
        await("session idle", () -> !"running".equals(app.database().getSession(sessionId).state));
    }
}
