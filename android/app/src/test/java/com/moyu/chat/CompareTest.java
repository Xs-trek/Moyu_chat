package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Spec D16–D18: one question answered at once by several model × mode variants, as versions of that question; the
 * first is the path end until the user picks one (or a tie); blind groups hide the models until decided; outcomes
 * feed the statistics.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CompareTest {
    private MockWebServer server;
    private ChatCoordinatorE2eTest.RecordingHost host;
    private ChatCoordinator coordinator;
    private MoyuChatApplication app;
    private ChatCoordinatorE2eTest.FakeSecretStore secrets;
    private int requestId;
    /** Both answers' requests are in flight together before either is answered: they ran at the same time (D18). */
    private final CountDownLatch bothAsked = new CountDownLatch(2);
    private final AtomicBoolean overlapped = new AtomicBoolean(false);

    @Before public void setUp() throws Exception {
        SessionTitles.automatic = false;
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                String body = request.getBody().readUtf8();
                if (body.contains("Which bird")) {
                    bothAsked.countDown();
                    if (bothAsked.await(5, TimeUnit.SECONDS)) overlapped.set(true);
                }
                return completion("answer from " + request.getPath().split("/")[1]);
            }
        });
        server.start();
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
        host = new ChatCoordinatorE2eTest.RecordingHost();
        secrets = new ChatCoordinatorE2eTest.FakeSecretStore();
        coordinator = new ChatCoordinator(app, host, secrets);
        post("app.ready", new JSONObject());
    }

    @After public void tearDown() throws Exception {
        SessionTitles.automatic = true;
        if (coordinator != null) coordinator.close();
        try { server.shutdown(); } catch (java.io.IOException ignored) { }
    }

    @Test public void variantsAnswerOneQuestionAtOnceAndPickingOneMakesItTheConversation() throws Exception {
        String first = account("first", "/first/v1", "first-model");
        String second = account("second", "/second/v1", "second-model");
        String sessionId = session("first");
        JSONObject sent = awaitResult(post("chat.session.compare.send", new JSONObject().put("localSessionId", sessionId).put("text", "Which bird is fastest?")
                .put("variants", new JSONArray().put(variant(first, ContextMode.STANDARD)).put(variant(second, ContextMode.MINIMAL)))));
        assertTrue(sent.toString(), sent.optBoolean("ok"));
        awaitSettled(sessionId);
        assertTrue("the two answers ran at the same time", overlapped.get());

        JSONArray path = app.database().timeline(sessionId);
        assertEquals("one question and the first answer", 2, path.length());
        assertEquals("answer from first", path.getJSONObject(1).optString("text"));
        List<JSONObject> group = app.database().compareGroupAt(sessionId, path.getJSONObject(0).optInt("localSeq"));
        assertEquals(2, group.size());
        assertEquals(second, group.get(1).getJSONObject("config").optString("modelProfileId"));
        assertEquals(ContextMode.MINIMAL, group.get(1).getJSONObject("config").optString("contextMode"));
        assertEquals("the session keeps its model while comparing", first, app.database().getSession(sessionId).modelProfileId);

        JSONObject compare = activeSession().getJSONObject("compare");
        assertFalse(compare.optBoolean("blind"));
        assertEquals("second-model", compare.getJSONArray("answers").getJSONObject(1).optString("modelName"));

        String chosen = group.get(1).optString("turnId");
        assertTrue(awaitResult(post("chat.compare.decide", new JSONObject().put("localSessionId", sessionId).put("group", compare.optString("group")).put("choice", chosen))).optBoolean("ok"));
        path = app.database().timeline(sessionId);
        assertEquals("the picked answer is the conversation", "answer from second", path.getJSONObject(path.length() - 1).optString("text"));
        ChatDatabase.Session s = app.database().getSession(sessionId);
        assertEquals("the session takes its model", second, s.modelProfileId);
        assertEquals(ContextMode.MINIMAL, s.contextMode);
        await("the card closes", () -> activeSession() != null && !activeSession().has("compare"));
        JSONArray stats = app.database().turnStats("", new String[]{TurnStats.BY_MODEL});
        assertEquals(1, row(stats, "second-model").getJSONObject("compareOpen").optInt("wins"));
        assertEquals(1, row(stats, "first-model").getJSONObject("compareOpen").optInt("losses"));
        assertFalse("decided once", awaitResult(post("chat.compare.decide", new JSONObject().put("localSessionId", sessionId).put("group", compare.optString("group")).put("choice", "tie"))).optBoolean("ok"));
    }

    @Test public void aBlindGroupHidesTheModelsUntilATie() throws Exception {
        String first = account("first", "/first/v1", "first-model");
        String sessionId = session("first");
        assertTrue(awaitResult(post("chat.session.compare.send", new JSONObject().put("localSessionId", sessionId).put("text", "Which bird is fastest?").put("blind", true)
                .put("variants", new JSONArray().put(variant(first, ContextMode.STANDARD)).put(variant(first, ContextMode.SMART))))).optBoolean("ok"));
        awaitSettled(sessionId);
        JSONObject compare = activeSession().getJSONObject("compare");
        assertTrue(compare.optBoolean("blind"));
        for (int i = 0; i < 2; i++) {
            JSONObject a = compare.getJSONArray("answers").getJSONObject(i);
            assertFalse("no model name", a.has("modelName"));
            assertFalse("no usage", a.has("usage"));
            assertEquals(String.valueOf((char) ('A' + i)), a.optString("label"));
        }
        assertTrue(awaitResult(post("chat.compare.decide", new JSONObject().put("localSessionId", sessionId).put("group", compare.optString("group")).put("choice", "tie"))).optBoolean("ok"));
        assertEquals("a tie changes nothing about the session", ContextMode.STANDARD, app.database().getSession(sessionId).contextMode);
        JSONObject stats = row(app.database().turnStats("", new String[]{TurnStats.BY_MODEL}), "first-model");
        assertEquals(2, stats.getJSONObject("compareBlind").optInt("ties"));
    }

    @Test public void aGroupNeedsTwoToThreeVariants() throws Exception {
        String first = account("first", "/first/v1", "first-model");
        String sessionId = session("first");
        assertFalse(awaitResult(post("chat.session.compare.send", new JSONObject().put("localSessionId", sessionId).put("text", "q")
                .put("variants", new JSONArray().put(variant(first, ContextMode.STANDARD))))).optBoolean("ok"));
        JSONArray four = new JSONArray();
        for (int i = 0; i < 4; i++) four.put(variant(first, ContextMode.STANDARD));
        assertFalse(awaitResult(post("chat.session.compare.send", new JSONObject().put("localSessionId", sessionId).put("text", "q").put("variants", four))).optBoolean("ok"));
        assertEquals("nothing was asked", 0, app.database().timeline(sessionId).length());
    }

    // ---------- plumbing ----------

    private static JSONObject variant(String profileId, String mode) throws Exception {
        return new JSONObject().put("modelProfileId", profileId).put("contextMode", mode);
    }

    private static JSONObject row(JSONArray stats, String model) throws Exception {
        for (int i = 0; i < stats.length(); i++) if (model.equals(stats.getJSONObject(i).getJSONObject("group").optString("model"))) return stats.getJSONObject(i);
        fail("no stats row for " + model);
        return null;
    }

    private String account(String id, String path, String model) throws Exception {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = id; p.displayName = id; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        p.authMode = ProviderAuth.defaultForProtocol(p.protocol);
        p.baseUrl = server.url(path).toString();
        p.model = model; p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        TestDeployments.save(app.database(), p);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile(p.id);
        coordinator.saveCredential(p.id, "sk-" + id + "-secret");
        await("key saved", () -> secrets.has(p.id));
        post("view.reload", new JSONObject());
        return profile.id;
    }

    private String session(String providerId) throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", providerId).put("contextMode", ContextMode.STANDARD).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session created", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty()
                && app.database().getSession(latestView().optString("activeLocalSessionId")) != null);
        return latestView().optString("activeLocalSessionId");
    }

    private void awaitSettled(String sessionId) throws Exception {
        await("both answers settled", () -> {
            ChatDatabase.Session s = app.database().getSession(sessionId);
            JSONObject view = activeSession();
            return s != null && "completed".equals(s.state) && view != null && view.optBoolean("canSend") && view.has("compare")
                    && !view.optJSONObject("compare").optBoolean("running");
        });
    }

    private JSONObject activeSession() {
        JSONObject view = latestView();
        return view == null ? null : view.optJSONObject("activeSession");
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

    private static MockResponse completion(String text) {
        return new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":" + JSONObject.quote(text) + "}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n"
                + "data: [DONE]\n\n");
    }
}
