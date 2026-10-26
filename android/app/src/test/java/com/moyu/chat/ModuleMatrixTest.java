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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Spec M2, M3, M4: every module, in a turn where its context mode does not run it and in a turn where the user
 * switched it off, makes no call of its stage, records why on the accept stage (and on its own stage when it has
 * one), and leaves nothing of its own in the prompt. Each test first shows the module does run when allowed, so
 * "no call" is not vacuous.
 *
 * Branch retrieval is removed (D6). The Web tools have no switch of their own (D5): the Web mode controls them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ModuleMatrixTest {
    private static final String SECRET = "sk-matrix-secret-1";
    private static final String ROUTER = "Classify the conversation", CHECKPOINT = "You write the running summary of a long chat",
            VERIFIER = "You are a verification component", PLANNER = "Web retrieval planner";

    private MockWebServer server;
    private ChatCoordinatorE2eTest.RecordingHost host;
    private ChatCoordinator coordinator;
    private MoyuChatApplication app;
    private ChatCoordinatorE2eTest.FakeSecretStore secrets;
    private int requestId;
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());

    @Before public void setUp() throws Exception {
        SessionTitles.automatic = false;
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                bodies.add(body);
                if (body.contains(ROUTER)) return completion("{\"domain\":\"general\",\"taskType\":\"answer\",\"depth\":\"standard\",\"responseMode\":\"direct\",\"contextPolicy\":\"recent\",\"modules\":[],\"confidence\":0.9}");
                if (body.contains(CHECKPOINT)) return completion("Earlier talk about tea.");
                if (body.contains(PLANNER)) return completion("{\"useWeb\":false,\"intent\":\"none\",\"queries\":[],\"freshness\":\"auto\",\"category\":\"\",\"startPublishedDate\":\"\",\"endPublishedDate\":\"\",\"fetchTopResults\":false}");
                if (body.contains(VERIFIER)) return completion("not json");
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

    // ---------- the matrix ----------

    @Test public void router() throws Exception {
        configure(false, HarnessProfile.BALANCED);
        setSwitch(HelperSwitches.ROUTER, true); // starts off by default
        String smart = session(ContextMode.SMART, ChatTurnOptions.WEB_OFF);
        int mark = send(smart, "Explain how tea is grown.");
        assertEquals("allowed: it runs", 1, calls(ROUTER, mark));
        // Spec D8: every turn is classified on its own, and nothing is stored on the session.
        mark = send(smart, "And how is it harvested?");
        assertEquals("again on the next turn", 1, calls(ROUTER, mark));
        assertEquals("", app.database().getSession(smart).taskProfile);

        String standard = session(ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        mark = send(standard, "Explain how coffee is grown.");
        assertNotRun(standard, mark, ROUTER, "router", TurnModules.MODE_OFF, TurnTrace.ROUTE);

        setSwitch(HelperSwitches.ROUTER, false);
        mark = send(smart, "And how is it dried?");
        assertNotRun(smart, mark, ROUTER, "router", HelperSwitches.USER_OFF, TurnTrace.ROUTE);
        assertFalse("no task prompt without the Router", mainBody(mark, "how is it dried").contains("Task-adaptation policy"));
    }

    @Test public void compaction() throws Exception {
        configure(false, HarnessProfile.BALANCED);
        String standard = pressured(ContextMode.STANDARD);
        int mark = send(standard, longText("Third", 600));
        assertEquals("allowed: it runs", 1, calls(CHECKPOINT, mark));
        assertTrue("and its summary opens the prompt", mainBody(mark, "Third").contains("Earlier talk about tea."));

        String minimal = pressured(ContextMode.MINIMAL);
        mark = send(minimal, longText("Third", 600));
        assertNotRun(minimal, mark, CHECKPOINT, "compaction", TurnModules.MODE_OFF, TurnTrace.CHECKPOINT);
        assertFalse(mainBody(mark, "Third").contains(SUMMARY_PREFIX));

        setSwitch(HelperSwitches.COMPACTION, false);
        String off = pressured(ContextMode.STANDARD);
        // Spec D10: a summary written while compaction was on is not used while it is off, and is kept for later.
        app.database().setContextCheckpoint(off, new ContextCheckpoint("An older summary about tea.", "").toJson().toString(), 2);
        mark = send(off, longText("Third", 600));
        assertNotRun(off, mark, CHECKPOINT, "compaction", HelperSwitches.USER_OFF, TurnTrace.CHECKPOINT);
        assertFalse("off: no summary in the prompt, not even an older one", mainBody(mark, "Third").contains(SUMMARY_PREFIX));
        assertFalse(mainBody(mark, "Third").contains("An older summary about tea."));
        assertNotNull("the stored summary stays", ContextCheckpoint.fromStored(app.database().getSession(off).contextCheckpoint));
    }

    @Test public void recall() throws Exception {
        configure(true, HarnessProfile.BALANCED);
        String standard = summarized(ContextMode.STANDARD);
        int mark = send(standard, "What was the code?");
        assertTrue("allowed: it is offered", mainBody(mark, "What was the code").contains("\"history_recall\""));

        String minimal = summarized(ContextMode.MINIMAL);
        mark = send(minimal, "What was the code?");
        assertNoTool(minimal, mark, "What was the code", "history_recall", "recall", TurnModules.MODE_OFF);

        setSwitch(HelperSwitches.RECALL, false);
        String off = summarized(ContextMode.STANDARD);
        mark = send(off, "What was the code?");
        assertNoTool(off, mark, "What was the code", "history_recall", "recall", HelperSwitches.USER_OFF);
    }

    @Test public void verify() throws Exception {
        configure(false, HarnessProfile.REINFORCED); // Reinforced verifies every non-writing turn
        String smart = session(ContextMode.SMART, ChatTurnOptions.WEB_OFF);
        int mark = send(smart, "Explain why the sky is blue.");
        assertEquals("allowed: it runs", 1, calls(VERIFIER, mark));

        String standard = session(ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        mark = send(standard, "Explain why the sea is blue.");
        assertNotRun(standard, mark, VERIFIER, "verify", TurnModules.MODE_OFF, TurnTrace.VERIFY);
        assertFalse(lastAnswer(standard).has("verification"));

        setSwitch(HelperSwitches.VERIFY, false);
        mark = send(smart, "Explain why grass is green.");
        assertNotRun(smart, mark, VERIFIER, "verify", HelperSwitches.USER_OFF, TurnTrace.VERIFY);
        assertFalse("no verdict on the answer", lastAnswer(smart).has("verification"));
    }

    @Test public void subagent() throws Exception {
        configure(true, HarnessProfile.BALANCED);
        String smart = session(ContextMode.SMART, ChatTurnOptions.WEB_OFF);
        int mark = send(smart, "Plan a garden.");
        assertTrue("allowed: it is offered", mainBody(mark, "Plan a garden").contains("\"delegate_subtask\""));

        String minimal = session(ContextMode.MINIMAL, ChatTurnOptions.WEB_OFF);
        mark = send(minimal, "Plan a pond.");
        assertNoTool(minimal, mark, "Plan a pond", "delegate_subtask", "subagent", TurnModules.MODE_OFF);

        setSwitch(HelperSwitches.SUBAGENT, false);
        mark = send(smart, "Plan a fence.");
        assertNoTool(smart, mark, "Plan a fence", "delegate_subtask", "subagent", HelperSwitches.USER_OFF);
    }

    /** Spec M1, M5 Web, D5: the answering model has the Web tools in every mode and decides; no planner runs first. */
    @Test public void webTools() throws Exception {
        configure(true, HarnessProfile.BALANCED);
        String standard = session(ContextMode.STANDARD, ChatTurnOptions.WEB_AUTO);
        int mark = send(standard, "What is the latest Android release?");
        assertTrue("allowed: it is offered", mainBody(mark, "latest Android release").contains("\"web_search\""));
        assertEquals("the model decides, nothing plans before it", 0, calls(PLANNER, mark));
        JSONObject plan = span(lastTrace(standard), TurnTrace.WEB_PLAN);
        assertEquals(TurnTrace.SKIPPED, plan.optString("status"));
        assertEquals(TurnPipeline.MODEL_DECIDES, plan.optString("reason"));

        String minimal = session(ContextMode.MINIMAL, ChatTurnOptions.WEB_AUTO);
        mark = send(minimal, "What is the latest iOS release?");
        assertTrue("Minimal keeps the model's own Web", mainBody(mark, "latest iOS release").contains("\"web_search\""));

        String off = session(ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        mark = send(off, "What is the latest Windows release?");
        assertNoTool(off, mark, "latest Windows release", "web_search", "webTools", TurnModules.WEB_OFF);
    }

    @Test public void webPlanner() throws Exception {
        configure(false, HarnessProfile.BALANCED);
        String standard = session(ContextMode.STANDARD, ChatTurnOptions.WEB_AUTO);
        int mark = send(standard, "Why is the sky blue?");
        assertEquals("allowed: it runs", 1, calls(PLANNER, mark));

        // Spec M1, D5: Minimal has no hidden helper call; its fallback plans by rules only.
        String minimal = session(ContextMode.MINIMAL, ChatTurnOptions.WEB_AUTO);
        mark = send(minimal, "Why is the snow white?");
        assertEquals(0, calls(PLANNER, mark));
        assertEquals(TurnModules.MODE_OFF, modules(lastTrace(minimal)).optString("webPlanner"));

        setSwitch(HelperSwitches.WEB_PLAN, false);
        mark = send(standard, "Why is the sea blue?");
        assertEquals(0, calls(PLANNER, mark));
        JSONObject trace = lastTrace(standard);
        assertEquals(HelperSwitches.USER_OFF, modules(trace).optString("webPlanner"));
        assertFalse(span(trace, TurnTrace.WEB_PLAN).getJSONObject("detail").optBoolean("plannerCalled"));
    }

    /** Spec C5, D7: other conversations are a tool the model may call, never text injected before it. */
    @Test public void crossChatMemory() throws Exception {
        configure(true, HarnessProfile.BALANCED);
        post("memory.global.set", new JSONObject().put("enabled", true));
        String other = session(ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        send(other, "My locker number is QUOKKA-5521.");

        String standard = session(ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        int mark = send(standard, "What is my QUOKKA-5521 locker?");
        String body = mainBody(mark, "my QUOKKA-5521 locker");
        assertTrue("allowed: it is offered", body.contains("\"conversation_search\""));
        assertEquals("nothing injected", 1, body.split("QUOKKA-5521", -1).length - 1);

        String minimal = session(ContextMode.MINIMAL, ChatTurnOptions.WEB_OFF);
        mark = send(minimal, "Which QUOKKA-5521 locker?");
        assertNoTool(minimal, mark, "Which QUOKKA-5521", "conversation_search", "memory", TurnModules.MODE_OFF);

        post("memory.global.set", new JSONObject().put("enabled", false));
        mark = send(standard, "Where is the QUOKKA-5521 locker?");
        assertNoTool(standard, mark, "Where is the QUOKKA-5521", "conversation_search", "memory", TurnModules.SETTING_OFF);
    }

    // ---------- spec N3 ----------

    @Test public void recallIsOfferedOnlyWhenTheFinalPromptLeavesHistoryOut() throws Exception {
        configure(true, HarnessProfile.BALANCED);
        String standard = session(ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        send(standard, "Remember: the code is 4471.");
        int mark = send(standard, "And the colour is teal.");
        assertFalse("everything is still in the prompt word for word", mainBody(mark, "colour is teal").contains("\"history_recall\""));
        JSONObject tools = span(lastTrace(standard), TurnTrace.TOOLS).getJSONObject("detail");
        assertFalse(tools.getBoolean("recall"));
        assertFalse(tools.optString("clientTools").contains("history_recall"));

        app.database().setContextCheckpoint(standard, new JSONObject().put("summary", "A code was shared.").toString(), 2);
        mark = send(standard, "What was the code?");
        assertTrue("summarized history can be recalled", mainBody(mark, "What was the code").contains("\"history_recall\""));
        assertTrue(span(lastTrace(standard), TurnTrace.TOOLS).getJSONObject("detail").getBoolean("recall"));
    }

    @Test public void recallIsOfferedWhenToolSchemasPushHistoryOutOfTheFinalPrompt() throws Exception {
        configure(true, HarnessProfile.BALANCED);
        setSwitch(HelperSwitches.COMPACTION, false); // a summary would also make recall available; isolate the budget
        String id = session(ContextMode.STANDARD, ChatTurnOptions.WEB_OFF);
        send(id, longText("Remember: the code is 4471.", 1_200));
        String question = "What was the code?";
        // The smallest window that carries the whole history, so the recall schema's reserve pushes the first exchange out.
        ContextBuilder contexts = new ContextBuilder(app.database());
        int reserve = new ContextBudgetManager().estimate(ClientToolRuntime.canonicalDefinitions(ClientToolRuntime.HISTORY_RECALL).toString()) + 128;
        ChatDatabase.Session s = app.database().getSession(id);
        s.inputTokens = 0; s.maxOutputTokens = 256;
        int window = 0;
        for (int ctx = 800; ctx <= 32_000 && window == 0; ctx += 8) {
            s.contextTokens = ctx;
            try { if (!contexts.build(s, ctx, 256, question).truncated) window = ctx; } catch (IllegalArgumentException tooSmall) { }
        }
        assertTrue(window > 0);
        CanonicalPrompt fits = contexts.build(s, window, 256, question);
        assertTrue("precondition: the schema reserve truncates", contexts.reserveForAdditionalInput(fits, window, 0, 256, reserve).truncated);
        s.contextTokens = window;
        app.database().saveSession(s);

        int mark = send(id, question);
        assertTrue("judged on the final prompt, history is out: recall is offered", mainBody(mark, question).contains("\"history_recall\""));
        assertTrue(span(lastTrace(id), TurnTrace.TOOLS).getJSONObject("detail").getBoolean("recall"));
    }

    // ---------- assertions ----------

    private void assertNotRun(String sessionId, int mark, String marker, String module, String reason, String stage) throws Exception {
        assertEquals(module + ": no call of its stage", 0, calls(marker, mark));
        JSONObject trace = lastTrace(sessionId);
        assertEquals(module + ": decided at accept", reason, modules(trace).optString(module));
        JSONObject s = span(trace, stage);
        assertEquals(module + ": its stage is skipped", TurnTrace.SKIPPED, s.optString("status"));
        assertEquals(module + ": with the reason", reason, s.optString("reason"));
    }

    private void assertNoTool(String sessionId, int mark, String text, String tool, String module, String reason) throws Exception {
        assertFalse(module + ": the tool is not offered", mainBody(mark, text).contains("\"" + tool + "\""));
        assertEquals(module + ": decided at accept", reason, modules(lastTrace(sessionId)).optString(module));
    }

    // ---------- setup ----------

    private void configure(boolean clientTools, String preset) throws Exception {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "mock-provider"; p.displayName = "Mock"; p.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        p.authMode = ProviderAuth.defaultForProtocol(p.protocol);
        p.baseUrl = server.url("/v1").toString();
        p.model = "mock-model"; p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        TestDeployments.save(app.database(), p);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile(p.id);
        if (clientTools) profile.clientToolCalling = ClientToolRuntime.OPENAI_CHAT;
        profile.harnessPreset = preset;
        app.database().saveModelProfile(profile);
        coordinator.saveCredential(p.id, SECRET);
        await("key saved", () -> secrets.has(p.id));
        post("view.reload", new JSONObject());
        await("provider configured", () -> {
            JSONObject view = latestView();
            JSONArray providers = view == null ? null : view.optJSONArray("providers");
            return providers != null && providers.length() == 1 && providers.optJSONObject(0).optJSONArray("models").length() > 0;
        });
    }

    private void setSwitch(String helper, boolean on) throws Exception {
        assertTrue(awaitResult(post("helper.switch.set", new JSONObject().put("helper", helper).put("enabled", on))).optBoolean("ok"));
    }

    private String session(String mode, String webMode) throws Exception {
        String previous = latestView() == null ? "" : latestView().optString("activeLocalSessionId", "");
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider").put("contextMode", mode).put("webMode", webMode));
        await("session created", () -> {
            String id = latestView() == null ? "" : latestView().optString("activeLocalSessionId", "");
            return !id.isEmpty() && !id.equals(previous) && app.database().getSession(id) != null;
        });
        return latestView().optString("activeLocalSessionId");
    }

    private static final String SUMMARY_PREFIX = "Summary of the earlier part of this conversation";

    /** A Chat whose third turn does not fit its window: two long exchanges, then the window is made small. */
    private String pressured(String mode) throws Exception {
        String id = session(mode, ChatTurnOptions.WEB_OFF);
        send(id, longText("First", 3_000));
        send(id, longText("Second", 3_000));
        ChatDatabase.Session s = app.database().getSession(id);
        s.contextTokens = 4_000; s.inputTokens = 0; s.maxOutputTokens = 512;
        app.database().saveSession(s);
        return id;
    }

    /** A Chat whose first exchange is stood in for by a summary, as if compacted earlier. */
    private String summarized(String mode) throws Exception {
        String id = session(mode, ChatTurnOptions.WEB_OFF);
        send(id, "Remember: the code is 4471.");
        app.database().setContextCheckpoint(id, new JSONObject().put("summary", "A code was shared.").toString(), 2);
        return id;
    }

    private static String longText(String head, int chars) {
        StringBuilder out = new StringBuilder(head).append(':');
        while (out.length() < chars) out.append(" tea leaves grow on hillsides");
        return out.toString();
    }

    /** Sends and waits for the turn to settle; returns the request mark before it. */
    private int send(String sessionId, String text) throws Exception {
        int mark = bodies.size();
        int rows = app.database().timeline(sessionId).length();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", text));
        await("turn settled", () -> app.database().timeline(sessionId).length() >= rows + 2
                && !"running".equals(app.database().getSession(sessionId).state)
                && !app.database().timeline(sessionId).optJSONObject(app.database().timeline(sessionId).length() - 1).optBoolean("streaming"));
        assertEquals("completed", app.database().getSession(sessionId).state);
        await("trace written", () -> { try { return lastTrace(sessionId) != null; } catch (Exception e) { return false; } });
        return mark;
    }

    private int calls(String marker, int since) {
        int n = 0;
        synchronized (bodies) { for (int i = since; i < bodies.size(); i++) if (bodies.get(i).contains(marker)) n++; }
        return n;
    }

    /** The main call of the turn sent after {@code since} that carries {@code text}. */
    private String mainBody(int since, String text) {
        synchronized (bodies) {
            for (int i = bodies.size() - 1; i >= since; i--) {
                String body = bodies.get(i);
                if (body.contains(text) && !body.contains(ROUTER) && !body.contains(CHECKPOINT) && !body.contains(VERIFIER) && !body.contains(PLANNER)) return body;
            }
        }
        fail("no main call carrying " + text);
        return "";
    }

    private JSONObject lastAnswer(String sessionId) {
        JSONArray timeline = app.database().timeline(sessionId);
        return timeline.optJSONObject(timeline.length() - 1);
    }

    private JSONObject lastTrace(String sessionId) throws Exception {
        String turnId = lastAnswer(sessionId).optString("turnId", "");
        if (turnId.isEmpty()) return null;
        JSONObject data = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).optJSONObject("data");
        return data == null || data.optJSONArray("spans") == null || data.optJSONArray("spans").length() == 0 ? null : data;
    }

    private static JSONObject modules(JSONObject trace) throws Exception {
        return span(trace, TurnTrace.ACCEPT).getJSONObject("detail").getJSONObject("modules");
    }

    private static JSONObject span(JSONObject trace, String stage) throws Exception {
        JSONArray spans = trace.getJSONArray("spans");
        for (int i = spans.length() - 1; i >= 0; i--) if (stage.equals(spans.getJSONObject(i).optString("stage"))) return spans.getJSONObject(i);
        fail("no " + stage + " span");
        return null;
    }

    // ---------- plumbing ----------

    private static MockResponse completion(String text) {
        return new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":" + JSONObject.quote(text) + "}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n"
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
