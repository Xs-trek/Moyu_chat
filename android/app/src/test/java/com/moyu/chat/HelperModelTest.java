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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

/**
 * Spec D15: helper calls may run on the helper model. Naming, the Router and Web planning use it by default, the rest
 * stay on the session's model; its calls are billed to its own account; when it cannot be used the helpers fall back
 * to the session's model and the turn says why.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class HelperModelTest {
    private static final String ROUTER = "Classify the conversation", TITLE = "You name chat conversations";

    private MockWebServer server;
    private ChatCoordinatorE2eTest.RecordingHost host;
    private ChatCoordinator coordinator;
    private MoyuChatApplication app;
    private ChatCoordinatorE2eTest.FakeSecretStore secrets;
    private int requestId;
    /** "path body" of every request. */
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    @Before public void setUp() throws Exception {
        SessionTitles.automatic = false;
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                requests.add(request.getPath() + " " + body);
                if (body.contains(ROUTER)) return completion("{\"domain\":\"general\",\"taskType\":\"answer\",\"depth\":\"standard\",\"responseMode\":\"direct\",\"contextPolicy\":\"recent\",\"modules\":[],\"confidence\":0.9}");
                if (body.contains(TITLE)) return completion("Tea notes");
                return completion("an answer");
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

    @Test public void lightHelpersRunOnTheHelperModelAndAreBilledToItsAccount() throws Exception {
        account("main", "/main/v1", "main-model", true, null);
        String helperProfile = account("helper", "/helper/v1", "helper-model", true, "{\"currency\":\"USD\",\"input\":1000,\"output\":1000}");
        assertTrue(awaitResult(post("helper.switch.set", new JSONObject().put("helper", HelperSwitches.ROUTER).put("enabled", true))).optBoolean("ok"));
        assertTrue(awaitResult(post("helper.model.set", new JSONObject().put("modelProfileId", helperProfile))).optBoolean("ok"));
        JSONObject setting = latestView().getJSONObject("ui").getJSONObject("helperModel");
        assertEquals(helperProfile, setting.optString("modelProfileId"));
        assertEquals("[\"title\",\"router\",\"web_plan\"]", String.valueOf(setting.optJSONArray("use")));

        String sessionId = session("main", ContextMode.SMART);
        int mark = send(sessionId, "Tell me about tea.");
        assertEquals("the Router ran on the helper model", 1, count(mark, "/helper/", ROUTER));
        assertEquals(0, count(mark, "/main/", ROUTER));
        assertTrue("the answer is the session's", count(mark, "/main/", "Tell me about tea.") >= 1);

        JSONObject trace = trace(sessionId);
        JSONObject helper = trace.getJSONObject("config").getJSONObject("helperModel");
        assertEquals("helper-model", helper.optString("model"));
        assertFalse(helper.has("fallback"));
        JSONObject usage = trace.getJSONObject("usage");
        assertFalse("the main account's turn is unpriced", usage.has("cost"));
        assertEquals("helper", usage.getJSONArray("foreignCost").getJSONObject(0).optString("providerId"));
        assertTrue("budgets count the helper call where it is spent", app.database().spendSince("helper", "").optDouble("USD", 0d) > 0d);
        assertEquals(0d, app.database().spendSince("main", "").optDouble("USD", 0d), 0d);

        mark = requests.size();
        assertTrue(awaitResult(post("chat.session.title.auto", new JSONObject().put("localSessionId", sessionId))).optBoolean("ok"));
        assertEquals("naming uses the helper model too", 1, count(mark, "/helper/", TITLE));

        assertTrue(awaitResult(post("helper.model.use.set", new JSONObject().put("helper", HelperModel.ROUTER).put("enabled", false))).optBoolean("ok"));
        mark = send(sessionId, "And green tea?");
        assertEquals("a helper moved back runs on the session's model", 1, count(mark, "/main/", ROUTER));
    }

    @Test public void aHelperModelWithoutAKeyFallsBackToTheSessionModelAndSaysWhy() throws Exception {
        account("main", "/main/v1", "main-model", true, null);
        String helperProfile = account("helper", "/helper/v1", "helper-model", false, null);
        assertTrue(awaitResult(post("helper.switch.set", new JSONObject().put("helper", HelperSwitches.ROUTER).put("enabled", true))).optBoolean("ok"));
        assertTrue(awaitResult(post("helper.model.set", new JSONObject().put("modelProfileId", helperProfile))).optBoolean("ok"));
        String sessionId = session("main", ContextMode.SMART);
        int mark = send(sessionId, "Tell me about tea.");
        assertEquals(1, count(mark, "/main/", ROUTER));
        assertEquals("nothing goes to the helper's endpoint", 0, count(mark, "/helper/", ""));
        assertEquals(HelperModel.NO_KEY, trace(sessionId).getJSONObject("config").getJSONObject("helperModel").optString("fallback"));
    }

    @Test public void onlyAnEnabledModelCanBeTheHelperModel() throws Exception {
        String main = account("main", "/main/v1", "main-model", true, null);
        ChatDatabase.ModelProfile profile = app.database().getModelProfile(main);
        profile.enabled = false;
        app.database().saveModelProfile(profile);
        assertFalse(awaitResult(post("helper.model.set", new JSONObject().put("modelProfileId", main))).optBoolean("ok"));
        assertFalse(awaitResult(post("helper.model.set", new JSONObject().put("modelProfileId", "nope"))).optBoolean("ok"));
    }

    // ---------- plumbing ----------

    private String account(String id, String path, String model, boolean withKey, String pricing) throws Exception {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = id; p.displayName = id; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        p.authMode = ProviderAuth.defaultForProtocol(p.protocol);
        p.baseUrl = server.url(path).toString();
        p.model = model; p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        TestDeployments.save(app.database(), p);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile(p.id);
        if (pricing != null) { profile.pricing = pricing; app.database().saveModelProfile(profile); }
        if (withKey) {
            coordinator.saveCredential(p.id, "sk-" + id + "-secret");
            await("key saved", () -> secrets.has(p.id));
        }
        post("view.reload", new JSONObject());
        return profile.id;
    }

    private String session(String providerId, String mode) throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", providerId).put("contextMode", mode).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session created", () -> latestView() != null && !latestView().optString("activeLocalSessionId", "").isEmpty()
                && app.database().getSession(latestView().optString("activeLocalSessionId")) != null);
        return latestView().optString("activeLocalSessionId");
    }

    private int send(String sessionId, String text) throws Exception {
        int mark = requests.size();
        int rows = app.database().timeline(sessionId).length();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", text));
        await("turn settled", () -> app.database().timeline(sessionId).length() >= rows + 2
                && "completed".equals(app.database().getSession(sessionId).state)
                && !app.database().timeline(sessionId).optJSONObject(app.database().timeline(sessionId).length() - 1).optBoolean("streaming"));
        return mark;
    }

    private int count(int since, String path, String marker) {
        int n = 0;
        synchronized (requests) {
            for (int i = since; i < requests.size(); i++) {
                String r = requests.get(i);
                if (r.startsWith(path) && r.contains(marker)) n++;
            }
        }
        return n;
    }

    private JSONObject trace(String sessionId) throws Exception {
        JSONArray timeline = app.database().timeline(sessionId);
        String turnId = timeline.getJSONObject(timeline.length() - 1).optString("turnId");
        return awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).getJSONObject("data");
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
