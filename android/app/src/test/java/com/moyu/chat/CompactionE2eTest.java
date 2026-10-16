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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Spec C3, T1 step 6, T8, H3 (D1, D2, D13): compaction starts after a turn whose real usage passes 75% of the window,
 * runs in the background, is applied while its history is unchanged, is waited for by a turn that does not fit, and
 * comes along into a new-session branch whose history starts with what it covers.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CompactionE2eTest {
    private static final String SECRET = "sk-compaction-secret-1";
    private static final String SUMMARY_CALL = "You write the running summary of a long chat";
    private static final String SUMMARY = "SUMMARY: the user asked about questions 0 and 1.";

    private MockWebServer server;
    private ChatCoordinatorE2eTest.RecordingHost host;
    private ChatCoordinator coordinator;
    private MoyuChatApplication app;
    private ChatCoordinatorE2eTest.FakeSecretStore secrets;
    private int requestId;
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    /** prompt_tokens the next main answer reports. */
    private final AtomicInteger reportedInput = new AtomicInteger(7);
    private final AtomicLong summaryDelayMs = new AtomicLong(0);
    private final AtomicLong summaryAnsweredAt = new AtomicLong(0);

    @Before public void setUp() throws Exception {
        SessionTitles.automatic = false;
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String body = request.getBody().clone().readUtf8();
                bodies.add(body);
                if (body.contains(SUMMARY_CALL)) {
                    Thread.sleep(summaryDelayMs.get());
                    summaryAnsweredAt.set(System.nanoTime());
                    return completion(SUMMARY, 7);
                }
                return completion("an answer", reportedInput.get());
            }
        });
        server.start();
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        host = new ChatCoordinatorE2eTest.RecordingHost();
        secrets = new ChatCoordinatorE2eTest.FakeSecretStore();
        coordinator = new ChatCoordinator(app, host, secrets);
        post("app.ready", new JSONObject());
        configure();
    }

    @After public void tearDown() throws Exception {
        SessionTitles.automatic = true;
        if (coordinator != null) coordinator.close();
        try { server.shutdown(); } catch (java.io.IOException ignored) { }
    }

    @Test public void aTurnPastThreeQuartersCompactsInTheBackgroundAndTheNextTurnUsesTheSummary() throws Exception {
        String id = session();
        for (int i = 0; i < 3; i++) send(id, "question " + i);
        assertEquals("small usage: no compaction", 0, calls(SUMMARY_CALL, 0));
        assertEquals("", app.database().getSession(id).contextCheckpoint);

        reportedInput.set(25_000); // of an input window of 32000 - 4096 - 1024 = 26880
        send(id, "question 3");
        await("background summary stored", () -> ContextCheckpoint.fromStored(app.database().getSession(id).contextCheckpoint) != null);
        assertEquals("all but the newest two of four rounds", 4, app.database().getSession(id).checkpointCoveredItems);
        String summaryBody = lastBody(SUMMARY_CALL);
        assertTrue(summaryBody.contains("question 1") && !summaryBody.contains("question 2"));
        JSONObject span = await3Span(id, "question 3");
        assertEquals("background", span.getJSONObject("detail").optString("mode"));
        assertTrue(span.getJSONObject("detail").getBoolean("applied"));
        assertTrue("its cost is on the turn that started it", span.getJSONObject("usage").optInt("calls") >= 1);

        reportedInput.set(7);
        int mark = bodies.size();
        send(id, "question 4");
        assertEquals("nothing to wait for or run", 0, calls(SUMMARY_CALL, mark));
        String main = mainBody(mark, "question 4");
        assertTrue(main.contains(ContextCheckpoint.PREFIX.substring(1, 40)) && main.contains("SUMMARY: the user asked"));
        assertFalse("summarized rounds are not sent word for word", main.contains("\"question 0\""));
        assertTrue(main.contains("question 2"));
    }

    @Test public void aTurnThatDoesNotFitWaitsForTheRunningCompaction() throws Exception {
        String id = session();
        for (int i = 0; i < 3; i++) send(id, longQuestion(i));
        summaryDelayMs.set(1_500);
        reportedInput.set(25_000);
        send(id, longQuestion(3));
        // The background call is now running for 1.5 s. Make the window too small for the raw history and ask again.
        ChatDatabase.Session s = app.database().getSession(id);
        s.contextTokens = 4_200; s.inputTokens = 0; s.maxOutputTokens = 512;
        app.database().saveSession(s);
        reportedInput.set(7);
        int mark = bodies.size();
        long sentAt = System.nanoTime();
        send(id, "short question 4");
        JSONObject checkpoint = span(lastTrace(id), TurnTrace.CHECKPOINT);
        assertEquals(TurnTrace.OK, checkpoint.optString("status"));
        assertEquals("waited", checkpoint.getJSONObject("detail").optString("mode"));
        assertTrue(checkpoint.getJSONObject("detail").getBoolean("applied"));
        assertTrue("it did wait", summaryAnsweredAt.get() > sentAt);
        assertEquals("no second summary call", 0, calls(SUMMARY_CALL, mark));
        assertTrue(mainBody(mark, "short question 4").contains("SUMMARY: the user asked"));
    }

    @Test public void aBranchTakesTheSummaryOnlyWhenItsHistoryStartsWithWhatItCovers() throws Exception {
        String id = session();
        for (int i = 0; i < 3; i++) send(id, "question " + i);
        reportedInput.set(25_000);
        send(id, "question 3");
        await("background summary stored", () -> ContextCheckpoint.fromStored(app.database().getSession(id).contextCheckpoint) != null);
        JSONArray rows = app.database().timeline(id);
        int lastSeq = rows.getJSONObject(rows.length() - 1).optInt("localSeq");
        int firstAnswerSeq = rows.getJSONObject(1).optInt("localSeq");

        String whole = branch(id, lastSeq);
        assertEquals(app.database().getSession(id).contextCheckpoint, app.database().getSession(whole).contextCheckpoint);
        assertEquals(4, app.database().getSession(whole).checkpointCoveredItems);

        String early = branch(id, firstAnswerSeq);
        assertEquals("the branch point is inside what the summary covers", "", app.database().getSession(early).contextCheckpoint);
    }

    // ---------- helpers ----------

    private String branch(String id, int throughSeq) throws Exception {
        String previous = latestView().optString("activeLocalSessionId", "");
        post("branch.create.local", new JSONObject().put("localSessionId", id).put("throughSeq", throughSeq));
        await("branch created", () -> {
            String now = latestView() == null ? "" : latestView().optString("activeLocalSessionId", "");
            return !now.isEmpty() && !now.equals(previous);
        });
        return latestView().optString("activeLocalSessionId");
    }

    private static String longQuestion(int i) {
        StringBuilder out = new StringBuilder("question ").append(i).append(':');
        while (out.length() < 2_400) out.append(" tides rise and fall twice a day");
        return out.toString();
    }

    /** The checkpoint span the background compaction added to the turn that asked {@code question}. */
    private JSONObject await3Span(String id, String question) throws Exception {
        JSONArray rows = app.database().timeline(id);
        String turnId = "";
        for (int i = 0; i < rows.length(); i++) {
            if (question.equals(rows.getJSONObject(i).optString("text")) && i + 1 < rows.length()) turnId = rows.getJSONObject(i + 1).optString("turnId");
        }
        String turn = turnId;
        JSONObject[] found = new JSONObject[1];
        await("checkpoint span on the turn", () -> {
            try {
                JSONArray spans = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", id).put("turnId", turn)))
                        .getJSONObject("data").getJSONArray("spans");
                for (int i = 0; i < spans.length(); i++) {
                    JSONObject s = spans.getJSONObject(i);
                    if (TurnTrace.CHECKPOINT.equals(s.optString("stage")) && s.has("detail")
                            && "background".equals(s.getJSONObject("detail").optString("mode"))) { found[0] = s; return true; }
                }
            } catch (Exception ignored) { }
            return false;
        });
        return found[0];
    }

    private void configure() throws Exception {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "mock-provider"; p.displayName = "Mock"; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        p.authMode = ProviderAuth.defaultForProtocol(p.protocol);
        p.baseUrl = server.url("/v1").toString();
        p.model = "mock-model"; p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        TestDeployments.save(app.database(), p);
        coordinator.saveCredential(p.id, SECRET);
        await("key saved", () -> secrets.has(p.id));
        post("view.reload", new JSONObject());
        await("provider configured", () -> {
            JSONObject view = latestView();
            JSONArray providers = view == null ? null : view.optJSONArray("providers");
            return providers != null && providers.length() == 1 && providers.optJSONObject(0).optJSONArray("models").length() > 0;
        });
    }

    private String session() throws Exception {
        String previous = latestView() == null ? "" : latestView().optString("activeLocalSessionId", "");
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider").put("contextMode", ContextMode.STANDARD)
                .put("webMode", ChatTurnOptions.WEB_OFF));
        await("session created", () -> {
            String id = latestView() == null ? "" : latestView().optString("activeLocalSessionId", "");
            return !id.isEmpty() && !id.equals(previous) && app.database().getSession(id) != null;
        });
        return latestView().optString("activeLocalSessionId");
    }

    private void send(String sessionId, String text) throws Exception {
        int rows = app.database().timeline(sessionId).length();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", text));
        await("turn settled", () -> app.database().timeline(sessionId).length() >= rows + 2
                && !"running".equals(app.database().getSession(sessionId).state)
                && !app.database().timeline(sessionId).optJSONObject(app.database().timeline(sessionId).length() - 1).optBoolean("streaming"));
        assertEquals("completed", app.database().getSession(sessionId).state);
        await("trace written", () -> { try { return lastTrace(sessionId) != null; } catch (Exception e) { return false; } });
    }

    private int calls(String marker, int since) {
        int n = 0;
        synchronized (bodies) { for (int i = since; i < bodies.size(); i++) if (bodies.get(i).contains(marker)) n++; }
        return n;
    }

    private String lastBody(String marker) {
        synchronized (bodies) { for (int i = bodies.size() - 1; i >= 0; i--) if (bodies.get(i).contains(marker)) return bodies.get(i); }
        fail("no body with " + marker);
        return "";
    }

    private String mainBody(int since, String text) {
        synchronized (bodies) {
            for (int i = bodies.size() - 1; i >= since; i--) {
                String body = bodies.get(i);
                if (body.contains(text) && !body.contains(SUMMARY_CALL)) return body;
            }
        }
        fail("no main call carrying " + text);
        return "";
    }

    private JSONObject lastTrace(String sessionId) throws Exception {
        JSONArray timeline = app.database().timeline(sessionId);
        String turnId = timeline.optJSONObject(timeline.length() - 1).optString("turnId", "");
        if (turnId.isEmpty()) return null;
        JSONObject data = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).optJSONObject("data");
        return data == null || data.optJSONArray("spans") == null || data.optJSONArray("spans").length() == 0 ? null : data;
    }

    private static JSONObject span(JSONObject trace, String stage) throws Exception {
        JSONArray spans = trace.getJSONArray("spans");
        for (int i = spans.length() - 1; i >= 0; i--) if (stage.equals(spans.getJSONObject(i).optString("stage"))) return spans.getJSONObject(i);
        fail("no " + stage + " span");
        return null;
    }

    private static MockResponse completion(String text, int promptTokens) {
        return new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":" + JSONObject.quote(text) + "}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" + promptTokens + ",\"completion_tokens\":2}}\n\n"
                + "data: [DONE]\n\n");
    }

    private String post(String type, JSONObject payload) throws Exception {
        String id = "req-" + (++requestId);
        coordinator.postIntent(new JSONObject().put("version", 1).put("type", type).put("requestId", id).put("payload", payload).toString());
        return id;
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(30);
        }
        fail("timed out waiting for: " + what);
    }

    private JSONObject resultOf(String id) {
        synchronized (host.envelopes) {
            for (JSONObject e : host.envelopes) if ("intent.result".equals(e.optString("type")) && id.equals(e.optString("requestId"))) return e;
        }
        return null;
    }

    private JSONObject awaitResult(String id) throws Exception {
        await("result " + id, () -> resultOf(id) != null);
        return resultOf(id);
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
}
