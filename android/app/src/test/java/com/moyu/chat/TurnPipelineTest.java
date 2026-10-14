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
        SessionTitles.automatic = false; // naming has its own tests; here it would add a request after every turn
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

    /** Persisted trace of the session's latest finished turn, read back from the database. */
    private static final class Trace {
        final JSONObject turn;
        Trace(JSONObject turn) { this.turn = turn; }
        String terminalStatus() { return turn.optString("status"); }
        JSONObject span(String stage) {
            JSONArray spans = turn.optJSONArray("spans");
            for (int i = spans.length() - 1; i >= 0; i--) if (stage.equals(spans.optJSONObject(i).optString("stage"))) return spans.optJSONObject(i);
            return null;
        }
        String status(String stage) { JSONObject s = span(stage); return s == null ? null : s.optString("status"); }
        String reason(String stage) { JSONObject s = span(stage); return s == null ? null : s.optString("reason"); }
        JSONObject usage(String stage) { JSONObject s = span(stage); return s == null ? null : s.optJSONObject("usage"); }
        java.util.List<String> stages(String status) {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            JSONArray spans = turn.optJSONArray("spans");
            for (int i = 0; i < spans.length(); i++) if (status.equals(spans.optJSONObject(i).optString("status"))) out.add(spans.optJSONObject(i).optString("stage"));
            return out;
        }
    }

    private Trace finishedTrace(String sessionId) {
        JSONArray turns = app.database().turns(sessionId);
        if (turns.length() == 0) return null;
        JSONObject last = turns.optJSONObject(turns.length() - 1);
        if ("running".equals(last.optString("status"))) return null;
        return new Trace(app.database().turnTrace(sessionId, last.optString("turnId")));
    }

    /** Sends one message in a fresh session and returns the finished turn's trace. */
    private Trace runTurn(String contextMode, String text) throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", "p").put("contextMode", contextMode).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty());
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", text));
        await("turn finished", () -> finishedTrace(sessionId) != null);
        return finishedTrace(sessionId);
    }

    @Test public void minimalModeRunsOnlyTheMainCallAndSaysWhyHelpersWereSkipped() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("minimal answer"); }
        });
        Trace trace = runTurn(ContextMode.MINIMAL, "hello");

        assertEquals(TurnTrace.OK, trace.terminalStatus());
        assertEquals(Arrays.asList(TurnTrace.ACCEPT, TurnTrace.CONTEXT, TurnTrace.WEB_PLAN, TurnTrace.TOOLS, TurnTrace.GENERATE), trace.stages(TurnTrace.OK));
        assertEquals("mode_off", trace.reason(TurnTrace.ROUTE));
        assertEquals("mode_off", trace.reason(TurnTrace.CHECKPOINT));
        assertEquals("web_not_needed", trace.reason(TurnTrace.WEB_RETRIEVE));
        assertEquals("mode_off", trace.reason(TurnTrace.VERIFY));
        assertEquals(1, trace.usage(TurnTrace.GENERATE).optInt("calls"));
        assertNull("no helper call was made", trace.usage(TurnTrace.ROUTE));
        assertEquals(1, server.getRequestCount());

        // The UI reads the same trace on demand; the full view never carries it.
        String sessionId = latestView().optString("activeLocalSessionId");
        String turnId = trace.turn.optString("turnId");
        post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId));
        String request = "r" + requestId;
        await("trace result", () -> resultFor(request) != null);
        JSONObject data = resultFor(request).optJSONObject("data");
        assertNotNull(data);
        assertEquals(turnId, data.optString("turnId"));
        assertTrue(data.getJSONArray("spans").length() >= 9);
        assertFalse("views never carry traces", latestView().toString().contains("\"spans\""));
        JSONObject usageRow = null;
        JSONArray messages = latestView().getJSONObject("activeSession").getJSONArray("messages");
        for (int i = 0; i < messages.length(); i++) if ("usage".equals(messages.getJSONObject(i).optString("kind"))) usageRow = messages.getJSONObject(i);
        assertNotNull("the view still shows the turn's usage row", usageRow);
        assertEquals(turnId, usageRow.optString("turnId"));
        assertEquals(100, usageRow.optInt("inputTokens"));
    }

    private JSONObject resultFor(String request) {
        synchronized (host.envelopes) {
            for (JSONObject envelope : host.envelopes) if ("intent.result".equals(envelope.optString("type")) && request.equals(envelope.optString("requestId"))) return envelope;
        }
        return null;
    }

    @Test public void inspectorRecordsRedactedWirePerCallAndCanBeTurnedOff() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("wire answer"); }
        });
        Trace trace = runTurn(ContextMode.MINIMAL, "hello wire");
        String sessionId = latestView().optString("activeLocalSessionId");
        String turnId = trace.turn.optString("turnId");
        JSONArray calls = trace.turn.getJSONArray("calls");
        assertEquals("one model call, one recorded call", 1, calls.length());
        assertEquals(TurnTrace.GENERATE, calls.getJSONObject(0).optString("stage"));

        post("turn.payload.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId).put("callIndex", calls.getJSONObject(0).getInt("callIndex")));
        String request = "r" + requestId;
        await("payload result", () -> resultFor(request) != null);
        JSONArray parts = resultFor(request).getJSONObject("data").getJSONArray("parts");
        assertEquals(2, parts.length());
        assertEquals("request", parts.getJSONObject(0).optString("direction"));
        JSONObject sent = new JSONObject(parts.getJSONObject(0).optString("body"));
        assertTrue(sent.getString("url").endsWith("/chat/completions"));
        assertEquals("m", sent.getJSONObject("body").optString("model"));
        assertEquals("response", parts.getJSONObject(1).optString("direction"));
        JSONObject received = new JSONObject(parts.getJSONObject(1).optString("body"));
        assertEquals(200, received.getInt("status"));
        assertTrue(received.getString("text").contains("wire answer"));
        for (int i = 0; i < parts.length(); i++) assertFalse("the key never reaches the inspector", parts.getJSONObject(i).optString("body").contains(SECRET));

        post("inspector.capture.set", new JSONObject().put("enabled", false));
        await("capture off", () -> latestView() != null && !latestView().optJSONObject("ui").optBoolean("wireCapture", true));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second"));
        await("second turn finished", () -> app.database().turns(sessionId).length() == 2 && !"running".equals(app.database().turns(sessionId).optJSONObject(1).optString("status")));
        String second = app.database().turns(sessionId).optJSONObject(1).optString("turnId");
        assertEquals("capture off records nothing", 0, app.database().turnTrace(sessionId, second).getJSONArray("calls").length());
    }

    @Test public void reAnswerWithAnotherModeIsLinkedRatedAndCounted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("answer " + calls.incrementAndGet()); }
        });
        Trace first = runTurn(ContextMode.MINIMAL, "same question");
        String sourceId = latestView().optString("activeLocalSessionId");
        String originalTurn = first.turn.optString("turnId");

        post("turn.rerun", new JSONObject().put("localSessionId", sourceId).put("localSeq", 1).put("contextMode", ContextMode.STANDARD));
        String rerunRequest = "r" + requestId;
        await("rerun result", () -> resultFor(rerunRequest) != null);
        JSONObject rerun = resultFor(rerunRequest).getJSONObject("data");
        String branchId = rerun.getString("localSessionId");
        assertNotEquals(sourceId, branchId);
        assertEquals("same question", rerun.getString("text"));
        assertEquals(ContextMode.STANDARD, app.database().getSession(branchId).contextMode);
        assertEquals("the branch starts before the re-asked message", 0, app.database().timeline(branchId).length());

        post("chat.session.send", new JSONObject().put("localSessionId", branchId).put("text", rerun.getString("text")));
        await("re-answer finished", () -> finishedTrace(branchId) != null);
        JSONObject group = app.database().answerGroup(branchId, finishedTrace(branchId).turn.optString("turnId"));
        assertEquals(originalTurn, group.optString("rootTurnId"));
        assertEquals("same question", group.optString("question"));
        assertEquals(2, group.getJSONArray("answers").length());
        assertTrue(group.getJSONArray("answers").getJSONObject(0).optBoolean("original"));
        assertEquals("answer 2", group.getJSONArray("answers").getJSONObject(1).optString("text"));

        post("turn.rate", new JSONObject().put("localSessionId", branchId).put("turnId", finishedTrace(branchId).turn.optString("turnId")).put("rating", 1));
        await("rating shown", () -> {
            JSONObject session = latestView() == null ? null : latestView().optJSONObject("activeSession");
            if (session == null || !branchId.equals(session.optString("localSessionId"))) return false;
            JSONArray messages = session.optJSONArray("messages");
            for (int i = 0; i < messages.length(); i++) {
                JSONObject item = messages.optJSONObject(i);
                if ("assistant".equals(item.optString("role")) && item.optInt("rating") == 1 && item.optInt("answers") == 2) return true;
            }
            return false;
        });

        post("stats.get", new JSONObject().put("days", 7));
        String statsRequest = "r" + requestId;
        await("stats", () -> resultFor(statsRequest) != null);
        JSONArray rows = resultFor(statsRequest).getJSONObject("data").getJSONArray("rows");
        assertEquals("one row per model x mode", 2, rows.length());
        int up = 0, turns = 0;
        for (int i = 0; i < rows.length(); i++) { up += rows.getJSONObject(i).optInt("ratedUp"); turns += rows.getJSONObject(i).optInt("turns"); }
        assertEquals(1, up);
        assertEquals(2, turns);
    }

    @Test public void standardModeWatchesTheBudgetButNeverRoutesOrVerifies() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return chat("standard answer"); }
        });
        Trace trace = runTurn(ContextMode.STANDARD, "hello");

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
        Trace trace = runTurn(ContextMode.SMART, "Find the root cause of this crash");

        assertEquals(TurnTrace.OK, trace.terminalStatus());
        assertEquals(TurnTrace.OK, trace.status(TurnTrace.ROUTE));
        assertEquals("a debug task under the balanced preset is verified", TurnTrace.OK, trace.status(TurnTrace.VERIFY));
        assertEquals(1, trace.usage(TurnTrace.ROUTE).optInt("calls"));
        assertEquals(1, trace.usage(TurnTrace.GENERATE).optInt("calls"));
        assertTrue(trace.usage(TurnTrace.VERIFY).optInt("calls") >= 1);
        assertEquals(100, trace.usage(TurnTrace.ROUTE).optInt("inputTokens"));

        JSONObject route = trace.span(TurnTrace.ROUTE);
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
        await("turn finished", () -> finishedTrace(sessionId) != null);

        Trace trace = finishedTrace(sessionId);
        assertNotEquals(TurnTrace.OK, trace.terminalStatus());
        assertNull("generation never started", trace.status(TurnTrace.GENERATE));
        assertEquals("only the router request reached the provider", 1, server.getRequestCount());
        await("session idle", () -> !"running".equals(app.database().getSession(sessionId).state));
    }
}
