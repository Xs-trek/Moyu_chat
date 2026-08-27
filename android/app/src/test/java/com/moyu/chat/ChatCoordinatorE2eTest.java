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
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String TINY_GIF = "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==";

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
        volatile boolean runtimeLeaseAllowed = true;
        @Override public void dispatch(JSONObject envelope) { envelopes.add(envelope); }
        @Override public void openProviderEditor(ChatDatabase.Provider existing) { editorProvider = existing; editorOpened = true; }
        @Override public void openModelEditor(ChatDatabase.Provider provider, ChatDatabase.ModelProfile existing) { }
        @Override public boolean setTurnRuntimeActive(String sessionId, boolean active) { return !active || runtimeLeaseAllowed; }
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
        // Legacy transport/state E2Es assert one provider request per user turn. v0.0.2 smart-context
        // preflight has dedicated tests and is disabled here so these remain protocol-isolation tests.
        post("context.intelligence.set", new JSONObject().put("enabled", false));
    }

    @After public void tearDown() throws Exception {
        if (coordinator != null) coordinator.close();
        try { server.shutdown(); } catch (java.io.IOException ignored) { }
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

    private String createResponsesSession(String continuationMode) throws Exception {
        configureProvider(OpenAiCompatibleTransport.RESPONSES);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        assertNotNull(profile);
        profile.continuationMode = continuationMode;
        app.database().saveModelProfile(profile);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider").put("contextMode", ContextMode.STANDARD));
        await("Responses session created", () -> latestView() != null && latestView().optString("activeLocalSessionId", "").length() > 0);
        return latestView().optString("activeLocalSessionId");
    }

    // ---------- standalone startup + key isolation ----------

    @Test public void systemBackUsesInternalRoutesBeforeExitingActivity() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        createSession();
        assertEquals("conversation", latestView().optString("route"));
        AtomicBoolean exitRequested = new AtomicBoolean(false);
        coordinator.handleSystemBack(() -> exitRequested.set(true));
        await("system back navigates conversation to home", () -> "home".equals(latestView().optString("route")));
        assertFalse("first back from an internal route must not exit", exitRequested.get());
        coordinator.handleSystemBack(() -> exitRequested.set(true));
        await("second back from home requests Activity exit", exitRequested::get);
    }

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

    @Test public void imageOnlyTurnPersistsPrivateReferenceAndSendsMultimodalBody() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        assertNotNull(profile); profile.inputModalities = "text,image"; app.database().saveModelProfile(profile);
        String sessionId = createSession();
        JSONObject image = app.mediaStore().saveBase64Image("image/gif", TINY_GIF, "tiny.gif");
        app.database().addDraftMedia(sessionId, image);
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", ""));
        await("image-only turn completes", () -> {
            JSONObject s = activeSession(latestView()); return s != null && "completed".equals(s.optString("state"));
        });
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(request);
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        JSONArray content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals("image_url", content.getJSONObject(0).optString("type"));
        assertTrue(content.getJSONObject(0).getJSONObject("image_url").optString("url").startsWith("data:image/gif;base64,"));
        JSONArray timeline = app.database().timeline(sessionId);
        JSONObject user = timeline.getJSONObject(0); assertEquals(1, user.getJSONArray("attachments").length());
        assertFalse("base64 image bytes must not be stored in SQLite", scanDatabaseFor(TINY_GIF));
        assertEquals(0, app.database().draftMedia(sessionId).length());
    }

    @Test public void providerImageOutputPersistsAsAssistantAttachment() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String event = "{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/gif;base64," + TINY_GIF + "\"}}]},\"finish_reason\":\"stop\"}]}";
                return sse("data: " + event + "\n\ndata: [DONE]\n\n");
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        assertNotNull(profile); profile.outputModalities = "text,image"; app.database().saveModelProfile(profile);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "return image"));
        await("image-output turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
        JSONArray timeline = app.database().timeline(sessionId);
        JSONObject assistant = null;
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) assistant = row;
        }
        assertNotNull(assistant);
        JSONArray attachments = assistant.optJSONArray("attachments");
        assertNotNull(attachments); assertEquals(1, attachments.length());
        JSONObject image = attachments.getJSONObject(0);
        assertEquals("image", image.optString("kind"));
        assertEquals("image/gif", image.optString("mimeType"));
        assertTrue(app.mediaStore().exists(image.optString("mediaId")));
        assertFalse("assistant image base64 must stay out of SQLite", scanDatabaseFor(TINY_GIF));
    }

    @Test public void deleteSessionRemovesLocalStateAndUnreferencedMedia() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        JSONObject image = app.mediaStore().saveBase64Image("image/gif", TINY_GIF, "delete-me.gif");
        String mediaId = image.optString("mediaId");
        app.database().addDraftMedia(sessionId, image);
        assertTrue(app.mediaStore().exists(mediaId));

        post("chat.session.delete", new JSONObject().put("localSessionId", sessionId));
        await("session deleted", () -> app.database().getSession(sessionId) == null);
        await("active route returns home", () -> {
            JSONObject view = latestView(); return view != null && "home".equals(view.optString("route")) && view.optJSONArray("sessions").length() == 0;
        });
        assertFalse("unreferenced private media should be garbage-collected", app.mediaStore().exists(mediaId));
    }

    @Test public void editAndRollbackRewindCurrentSessionWhileBranchingStaysExplicit() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sourceId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sourceId).put("text", "original prompt"));
        await("source turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
        JSONArray sourceTimeline = app.database().timeline(sourceId);
        int userSeq = -1, assistantSeq = -1;
        for (int i = 0; i < sourceTimeline.length(); i++) {
            JSONObject item = sourceTimeline.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("kind"))) continue;
            if ("user".equals(item.optString("role"))) userSeq = item.optInt("localSeq", -1);
            if ("assistant".equals(item.optString("role")) && !item.optBoolean("streaming", false)) assistantSeq = item.optInt("localSeq", -1);
        }
        assertTrue(userSeq > 0); assertTrue(assistantSeq > userSeq);

        post("chat.message.edit", new JSONObject().put("localSessionId", sourceId).put("localSeq", userSeq));
        await("edit rewinds current session", () -> {
            ChatDatabase.Session s = app.database().getSession(sourceId);
            return s != null && "original prompt".equals(s.draft) && app.database().timeline(sourceId).length() == 0;
        });
        assertEquals("editing must stay in the same session", sourceId, latestView().optString("activeLocalSessionId"));
        assertEquals("edit must not silently create a branch", 1, latestView().optJSONArray("sessions").length());

        // Re-send so rollback can be verified independently.
        app.database().setDraft(sourceId, "");
        post("chat.session.send", new JSONObject().put("localSessionId", sourceId).put("text", "second prompt"));
        await("second turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
        JSONArray secondTimeline = app.database().timeline(sourceId);
        int secondAssistantSeq = -1;
        for (int i = 0; i < secondTimeline.length(); i++) {
            JSONObject item = secondTimeline.optJSONObject(i);
            if (item != null && "message".equals(item.optString("kind")) && "assistant".equals(item.optString("role"))) secondAssistantSeq = item.optInt("localSeq", -1);
        }
        assertTrue(secondAssistantSeq > 0);
        post("chat.message.rollback", new JSONObject().put("localSessionId", sourceId).put("localSeq", secondAssistantSeq));
        await("rollback removes selected assistant and later rows", () -> {
            JSONArray rows = app.database().timeline(sourceId);
            if (rows.length() != 1) return false;
            JSONObject only = rows.optJSONObject(0);
            return only != null && "user".equals(only.optString("role")) && "second prompt".equals(only.optString("text"));
        });
        assertEquals("rollback must stay in the same session", sourceId, latestView().optString("activeLocalSessionId"));
        assertEquals("rollback must not silently create a branch", 1, latestView().optJSONArray("sessions").length());

        // Branching is still available, but only through the explicit branch intent.
        post("branch.create.local", new JSONObject().put("localSessionId", sourceId));
        await("explicit branch created", () -> latestView().optJSONArray("sessions").length() == 2);
        assertNotEquals(sourceId, latestView().optString("activeLocalSessionId"));
        assertNotNull(activeSession(latestView()).optJSONObject("origin"));
    }

    @Test public void acceptedTurnIsVisibleBeforeProviderCompletes() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"late answer\"},\"finish_reason\":\"stop\"}]}\n\n")
                        .setBodyDelay(5, TimeUnit.SECONDS);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "visible immediately"));
        await("accepted user/live rows committed before provider completion", () -> {
            JSONArray rows = app.database().timeline(sessionId);
            if (rows.length() < 2) return false;
            JSONObject user = rows.optJSONObject(0), live = rows.optJSONObject(1);
            return user != null && live != null && "visible immediately".equals(user.optString("text"))
                    && live.optBoolean("streaming", false) && !"completed".equals(app.database().getSession(sessionId).state);
        });
        JSONArray rows = app.database().timeline(sessionId);
        JSONObject live = rows.getJSONObject(1);
        assertEquals("mock-model", live.optString("model"));
        assertEquals("mock-model", live.optString("modelName"));
        assertEquals("minimal", live.optString("contextMode"));
        assertEquals("minimal", live.optString("effectiveContextMode"));
        assertEquals("default", live.optString("reasoningEffort"));
        assertFalse(live.optBoolean("reasoningAvailable", true));
    }

    @Test public void modelAndReasoningSwitchAffectFutureTurnsAndFreezeAssistantMetadata() throws Exception {
        ChatDatabase.Provider provider = mockProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        provider.reasoningWire = ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT;
        coordinator.saveProvider(provider, SECRET);
        await("provider configured", () -> latestView() != null && latestView().optJSONArray("providers").length() == 1);

        // Create and complete the first turn before another profile exists so the baseline model is deterministic.
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first model"));
        await("first model turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
        RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(firstRequest);
        assertEquals("mock-model", new JSONObject(firstRequest.getBody().readUtf8()).optString("model"));

        ChatDatabase.ModelProfile second = new ChatDatabase.ModelProfile();
        second.id = "mock-model-b-profile"; second.providerId = provider.id; second.modelId = "mock-model-b"; second.displayName = "Mock Model B";
        second.contextTokens = 64_000; second.inputTokens = 0; second.maxOutputTokens = 4_096;
        second.inputModalities = "text,image"; second.outputModalities = "text";
        second.reasoningLevels = "none,low,high"; second.defaultReasoning = "none";
        coordinator.saveModelProfile(second);
        await("second model available", () -> {
            JSONObject view = latestView();
            JSONObject providerView = view == null || view.optJSONArray("providers") == null ? null : view.optJSONArray("providers").optJSONObject(0);
            return providerView != null && providerView.optJSONArray("models") != null && providerView.optJSONArray("models").length() == 2;
        });

        post("chat.session.model.set", new JSONObject().put("localSessionId", sessionId).put("modelProfileId", second.id));
        await("model switched", () -> second.id.equals(activeSession(latestView()).optString("modelProfileId")));
        assertEquals("Mock Model B", activeSession(latestView()).optString("modelName"));
        assertEquals("none,low,high", activeSession(latestView()).optString("reasoningLevels"));
        assertEquals("none", activeSession(latestView()).optString("reasoningEffort"));
        assertTrue(activeSession(latestView()).optBoolean("canAttachImage"));

        post("chat.session.reasoning.set", new JSONObject().put("localSessionId", sessionId).put("reasoningEffort", "high"));
        await("reasoning switched", () -> "high".equals(activeSession(latestView()).optString("reasoningEffort")));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second model"));
        RecordedRequest secondRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(secondRequest);
        JSONObject secondBody = new JSONObject(secondRequest.getBody().readUtf8());
        assertEquals("mock-model-b", secondBody.optString("model"));
        assertEquals("high", secondBody.optString("reasoning_effort"));
        await("second model turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));

        JSONArray timeline = app.database().timeline(sessionId);
        ArrayList<JSONObject> assistants = new ArrayList<>();
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) assistants.add(row);
        }
        assertEquals(2, assistants.size());
        assertEquals("mock-model", assistants.get(0).optString("model"));
        assertEquals("default", assistants.get(0).optString("reasoningEffort"));
        assertEquals("mock-model-b", assistants.get(1).optString("model"));
        assertEquals("Mock Model B", assistants.get(1).optString("modelName"));
        assertEquals("high", assistants.get(1).optString("reasoningEffort"));
        assertTrue(assistants.get(1).optBoolean("reasoningAvailable"));
    }

    // ---------- multi-turn + interrupt ----------

    @Test public void plainSecondTurnUsesStandardRoleHistoryWithoutToolPayload() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(first);
        await("first normal turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second"));
        RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(second);
        JSONObject body = new JSONObject(second.getBody().readUtf8());
        assertFalse("plain Chat must not send tools", body.has("tools"));
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(4, messages.length());
        assertEquals("system", messages.getJSONObject(0).optString("role"));
        assertEquals("user", messages.getJSONObject(1).optString("role"));
        assertEquals("first", messages.getJSONObject(1).optString("content"));
        assertEquals("assistant", messages.getJSONObject(2).optString("role"));
        assertEquals("mock answer", messages.getJSONObject(2).optString("content"));
        assertEquals("user", messages.getJSONObject(3).optString("role"));
        assertEquals("second", messages.getJSONObject(3).optString("content"));
        await("second normal turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
    }

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
        JSONObject finalizedAssistant = null;
        for (int i = timeline.length() - 1; i >= 0; i--) {
            JSONObject row = timeline.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role")) && !row.optBoolean("streaming", false)) { finalizedAssistant = row; break; }
        }
        assertNotNull(finalizedAssistant);
        assertFalse("partial answer is finalized, not left dangling as streaming", finalizedAssistant.optBoolean("streaming", true));
        assertTrue("partial text is preserved", finalizedAssistant.optString("text").length() > 0);

        // Drain and prove the first request really reached the provider before testing a second turn.
        RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);

        // A follow-up turn must create a NEW live row/request and a second finalized assistant message.
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"next\"}}]}\n\ndata: [DONE]\n\n");
            }
        });
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "follow up"));
        RecordedRequest secondRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("second turn must actually reach the provider", secondRequest);
        assertTrue(secondRequest.getBody().readUtf8().contains("follow up"));
        await("second turn completes with a second assistant row", () -> {
            JSONObject s = activeSession(latestView());
            if (s == null || !"completed".equals(s.optString("state")) || !s.optBoolean("canSend")) return false;
            JSONArray messages = s.optJSONArray("messages"); if (messages == null) return false;
            int assistants = 0; boolean sawNext = false;
            for (int i = 0; i < messages.length(); i++) {
                JSONObject row = messages.optJSONObject(i);
                if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role")) && !row.optBoolean("streaming", false)) {
                    assistants++; if (row.optString("text", "").contains("next")) sawNext = true;
                }
            }
            return assistants >= 2 && sawNext;
        });
    }

    @Test public void providerReasoningIsOptionalDisplayMetadataAndNeverReentersPrompt() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"private thought\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"visible answer\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,\"prompt_tokens_details\":{\"cached_tokens\":4},\"completion_tokens_details\":{\"reasoning_tokens\":1}}}\n\n");
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(first);
        await("reasoning turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));

        JSONArray timeline = app.database().timeline(sessionId);
        JSONObject assistant = null, usage = null;
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row == null) continue;
            if ("message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) assistant = row;
            if ("usage".equals(row.optString("kind"))) usage = row;
        }
        assertNotNull(assistant); assertEquals("visible answer", assistant.optString("text")); assertEquals("private thought", assistant.optString("reasoning"));
        assertNotNull(usage); assertEquals(10, usage.optInt("inputTokens")); assertEquals(4, usage.optInt("cachedInputTokens"));
        assertEquals(10, usage.optInt("cacheHitBaseTokens")); assertEquals(1, usage.optInt("reasoningTokens"));
        assertTrue(usage.has("requestDurationMs")); assertTrue(usage.has("outputTokensPerSecond"));
        assertFalse("reasoning display defaults off", latestView().optJSONObject("ui").optBoolean("showReasoning"));
        post("chat.reasoning.visibility.set", new JSONObject().put("show", true));
        await("reasoning visibility toggled", () -> latestView().optJSONObject("ui").optBoolean("showReasoning"));

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second"));
        RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(second);
        String secondBody = second.getBody().readUtf8();
        assertFalse("provider reasoning must never re-enter canonical prompt", secondBody.contains("private thought"));
        assertTrue(secondBody.contains("visible answer"));
        await("second reasoning turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
    }

    @Test public void reasoningOnlyCompletionBecomesExplicitEmptyOutputFailure() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"hidden thinking\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "reason"));
        await("reasoning-only turn fails explicitly", () -> "failed".equals(activeSession(latestView()).optString("state")));
        JSONArray timeline = activeSession(latestView()).optJSONArray("messages");
        boolean sawEmptyOutput = false;
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row != null && "error".equals(row.optString("kind")) && "empty_assistant_output".equals(row.optString("code"))) sawEmptyOutput = true;
            assertFalse("hidden reasoning must not become visible transcript", row != null && row.optString("text", "").contains("hidden thinking"));
        }
        assertTrue("empty visible completion must be explicit", sawEmptyOutput);
        assertTrue(activeSession(latestView()).optBoolean("canSend"));
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

    @Test public void rejectedForegroundLeaseDoesNotAcceptTurnAndRestoresExactDraft() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        host.runtimeLeaseAllowed = false;
        String submitted = "  preserve exact input \n after rejected lease  ";

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", submitted));
        await("foreground lease rejection restores draft", () -> submitted.equals(app.database().getSession(sessionId).draft));

        ChatDatabase.Session session = app.database().getSession(sessionId);
        assertEquals("turn must remain unaccepted when no foreground runtime can be acquired", "idle", session.state);
        assertEquals("rejected turn must not create user/live timeline rows", 0, app.database().timeline(sessionId).length());
        assertNull("rejected turn must never reach the provider", server.takeRequest(400, TimeUnit.MILLISECONDS));
        await("view shows restored draft", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && submitted.equals(active.optString("composerDraft")) && active.optBoolean("canSend");
        });
    }

    @Test public void submittedTextStaysOutOfComposerWhenContextPreparationFails() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        ChatDatabase.Session constrained = app.database().getSession(sessionId);
        constrained.contextTokens = 2_048;
        constrained.inputTokens = 0;
        constrained.maxOutputTokens = 1_024;
        app.database().saveSession(constrained);
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 6_000; i++) huge.append("oversized input ");
        String submitted = huge.toString();
        app.database().setDraft(sessionId, submitted);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", submitted));
        await("context-preparation failure settles turn", () -> "failed".equals(app.database().getSession(sessionId).state));

        assertEquals("submitted text must not be restored to the composer after failure", "", app.database().getSession(sessionId).draft);
        JSONArray rows = app.database().timeline(sessionId);
        assertTrue("accepted user input remains visible in history", rows.length() >= 2);
        assertEquals("user", rows.getJSONObject(0).optString("role"));
        assertEquals(submitted, rows.getJSONObject(0).optString("text"));
        boolean sawContextFailure = false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && "error".equals(row.optString("kind")) && "context_prepare_failed".equals(row.optString("code"))) sawContextFailure = true;
        }
        assertTrue("preflight failure must become a durable turn error", sawContextFailure);
        await("failed turn view refreshes", () -> {
            JSONObject s = activeSession(latestView());
            return s != null && "failed".equals(s.optString("state")) && "".equals(s.optString("composerDraft"));
        });
    }

    @Test public void runningSessionDoesNotBlockOpeningAnotherConversation() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"slow answer\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .setBodyDelay(8, TimeUnit.SECONDS);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String runningId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", runningId).put("text", "slow"));
        await("first session starts", () -> "running".equals(app.database().getSession(runningId).state));

        long started = System.nanoTime();
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("second conversation opens while first remains running", () -> {
            JSONObject view = latestView();
            return view != null && view.optJSONArray("sessions").length() == 2
                    && !runningId.equals(view.optString("activeLocalSessionId"));
        });
        post("appearance.set", new JSONObject().put("theme", "dark"));
        await("unrelated UI intent remains responsive", () -> "dark".equals(latestView().optJSONObject("appearance").optString("theme")));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertTrue("UI intents must not wait for the 8-second provider body", elapsedMs < 4_000);
        assertEquals("the original turn must still be running independently", "running", app.database().getSession(runningId).state);
        post("chat.session.interrupt", new JSONObject().put("localSessionId", runningId));
        await("slow turn cancels cleanly before teardown", () -> !"running".equals(app.database().getSession(runningId).state));
    }

    @Test public void highReasoningSurvivesNavigationDuringDeltaFloodAndStreamingUsesBoundedPatches() throws Exception {
        ChatDatabase.Provider provider = mockProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        provider.reasoningWire = ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT;
        coordinator.saveProvider(provider, SECRET);
        await("provider configured", () -> latestView() != null && latestView().optJSONArray("providers").length() == 1);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile(provider.id);
        assertNotNull(profile);
        profile.reasoningLevels = "low,high";
        profile.defaultReasoning = "default";
        app.database().saveModelProfile(profile);

        post("chat.session.create", new JSONObject().put("providerId", provider.id).put("webMode", ChatTurnOptions.WEB_OFF).put("contextMode", ContextMode.MINIMAL));
        await("session created", () -> latestView() != null && latestView().optString("activeLocalSessionId", "").length() > 0);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.reasoning.set", new JSONObject().put("localSessionId", sessionId).put("reasoningEffort", "high"));
        await("high selected", () -> "high".equals(activeSession(latestView()).optString("reasoningEffort")));

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                StringBuilder body = new StringBuilder();
                for (int i = 0; i < 400; i++) body.append("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"r\"},\"finish_reason\":null}]}\n\n");
                for (int i = 0; i < 400; i++) body.append("data: {\"choices\":[{\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n");
                body.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
                return sse(body.toString()).throttleBody(128, 1, TimeUnit.MILLISECONDS);
            }
        });
        final int baselineEnvelopeCount = host.envelopes.size();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "flood"));
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(request);
        assertEquals("high", new JSONObject(request.getBody().readUtf8()).optString("reasoning_effort"));
        await("at least one bounded stream patch", () -> {
            synchronized (host.envelopes) {
                for (int i = baselineEnvelopeCount; i < host.envelopes.size(); i++) if ("view.patch".equals(host.envelopes.get(i).optString("type"))) return true;
            }
            return false;
        });

        long navStarted = System.nanoTime();
        post("nav.open", new JSONObject().put("route", "home"));
        await("home opens during delta flood", () -> "home".equals(latestView().optString("route")));
        long navMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - navStarted);
        assertTrue("control-plane navigation must not sit behind token callbacks", navMs < 2_000);
        assertEquals("same durable session keeps High while hidden", "high", app.database().getSession(sessionId).reasoningEffort);

        post("chat.session.open", new JSONObject().put("localSessionId", sessionId));
        await("same running conversation reopens", () -> sessionId.equals(latestView().optString("activeLocalSessionId")) && "conversation".equals(latestView().optString("route")));
        assertEquals("High must survive leaving and reopening the same conversation", "high", activeSession(latestView()).optString("reasoningEffort"));
        await("flooded turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));

        JSONObject assistant = null;
        JSONArray timeline = app.database().timeline(sessionId);
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role")) && !row.optBoolean("streaming", false)) assistant = row;
        }
        assertNotNull(assistant);
        assertEquals("assistant metadata must come from the same frozen TurnConfig as the request wire", "high", assistant.optString("reasoningEffort"));

        int patches = 0, fullViews = 0;
        synchronized (host.envelopes) {
            for (int i = baselineEnvelopeCount; i < host.envelopes.size(); i++) {
                String type = host.envelopes.get(i).optString("type");
                if ("view.patch".equals(type)) patches++;
                else if ("view.full".equals(type)) fullViews++;
            }
        }
        assertTrue("streaming must emit bounded patches", patches > 0 && patches < 80);
        assertTrue("800 deltas must not produce token-proportional full application snapshots", fullViews < 30);
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

    @Test public void nativeContinuationPersistsAndReplaysOnTheNextResponsesTurn() throws Exception {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (firstCall.getAndSet(false)) {
                    return sse("data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"rs_native\",\"type\":\"reasoning\",\"encrypted_content\":\"native-cipher\"}}\n\n"
                            + "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"msg_native\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"native answer\"}]}}\n\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"native answer\"}\n\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");
                }
                return sse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"second answer\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");
            }
        });
        String sessionId = createResponsesSession(ProviderContinuationPolicy.NATIVE);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertTrue("native continuation request should ask for replayable encrypted reasoning",
                firstRequest.getBody().readUtf8().contains("reasoning.encrypted_content"));
        await("first native turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));

        JSONObject assistant = null;
        JSONArray timeline = app.database().timeline(sessionId);
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) assistant = row;
        }
        assertNotNull(assistant);
        JSONObject stored = assistant.optJSONObject("providerContinuation");
        assertNotNull("native continuation must be durably attached to the finalized assistant turn", stored);
        assertEquals(2, stored.getJSONArray("items").length());

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second"));
        RecordedRequest secondRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(secondRequest);
        String secondBody = secondRequest.getBody().readUtf8();
        assertTrue("next Responses turn must replay stored reasoning item", secondBody.contains("rs_native"));
        assertTrue("next Responses turn must replay encrypted reasoning", secondBody.contains("native-cipher"));
        assertTrue("next Responses turn must replay the stored assistant message item", secondBody.contains("msg_native"));
        await("second native turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));
    }

    @Test public void continuationModeNoneNeitherPersistsNorReplaysResponsesOpaqueState() throws Exception {
        AtomicBoolean firstCall = new AtomicBoolean(true);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (firstCall.getAndSet(false)) {
                    return sse("data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"rs_none\",\"type\":\"reasoning\",\"encrypted_content\":\"none-cipher\"}}\n\n"
                            + "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"msg_none\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"plain answer\"}]}}\n\n"
                            + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"plain answer\"}\n\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");
                }
                return sse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"second plain\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");
            }
        });
        String sessionId = createResponsesSession(ProviderContinuationPolicy.NONE);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(firstRequest);
        assertFalse("continuationMode=none must not request encrypted private continuation",
                firstRequest.getBody().readUtf8().contains("reasoning.encrypted_content"));
        await("first non-native turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));
        JSONArray timeline = app.database().timeline(sessionId);
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) {
                assertFalse("continuationMode=none must not persist provider-private state", row.has("providerContinuation"));
            }
        }

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second"));
        RecordedRequest secondRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(secondRequest);
        String secondBody = secondRequest.getBody().readUtf8();
        assertFalse("continuationMode=none must not replay opaque reasoning items", secondBody.contains("rs_none"));
        assertFalse("continuationMode=none must not replay opaque encrypted content", secondBody.contains("none-cipher"));
        assertFalse("continuationMode=none must not replay opaque assistant output items", secondBody.contains("msg_none"));
        await("second non-native turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));
    }

    @Test public void credentialRotationDuringRunningTurnCannotRelabelOldNativeContinuation() throws Exception {
        final String rotatedSecret = "sk-ROTATED-value-93b4";
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"rs_old\",\"type\":\"reasoning\",\"encrypted_content\":\"old-cipher\"}}\n\n"
                        + "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"msg_old\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"old answer\"}]}}\n\n"
                        + "data: {\"type\":\"response.output_text.delta\",\"delta\":\"old answer\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":8,\"output_tokens\":3}}}\n\n")
                        .setBodyDelay(2, TimeUnit.SECONDS);
            }
        });
        configureProvider(OpenAiCompatibleTransport.RESPONSES);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        assertNotNull(profile);
        profile.continuationMode = ProviderContinuationPolicy.NATIVE;
        app.database().saveModelProfile(profile);

        post("chat.session.create", new JSONObject().put("providerId", "mock-provider").put("contextMode", ContextMode.STANDARD));
        await("native session created", () -> latestView() != null && latestView().optString("activeLocalSessionId", "").length() > 0);
        String sessionId = latestView().optString("activeLocalSessionId");
        ChatDatabase.Session sessionBefore = app.database().getSession(sessionId);
        ChatDatabase.Provider providerBefore = app.database().getProvider("mock-provider");
        String requestFingerprint = ProviderContinuationIdentity.fingerprint(app.database(), providerBefore, sessionBefore);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("first request must start before credential rotation", firstRequest);

        coordinator.saveProvider(mockProvider(OpenAiCompatibleTransport.RESPONSES), rotatedSecret);
        await("credential rotation applied", () -> rotatedSecret.equals(secrets.get("mock-provider")));
        String currentFingerprint = ProviderContinuationIdentity.fingerprint(app.database(), app.database().getProvider("mock-provider"), app.database().getSession(sessionId));
        assertNotEquals("credential epoch must change deployment identity", requestFingerprint, currentFingerprint);

        await("old request completes after rotation", () -> "completed".equals(app.database().getSession(sessionId).state));
        JSONObject oldAssistant = null;
        JSONArray firstTimeline = app.database().timeline(sessionId);
        for (int i = 0; i < firstTimeline.length(); i++) {
            JSONObject row = firstTimeline.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) oldAssistant = row;
        }
        assertNotNull(oldAssistant);
        JSONObject oldState = oldAssistant.optJSONObject("providerContinuation");
        assertNotNull("native output from the first request should be retained under its original deployment", oldState);
        assertEquals("completion must preserve the request-start deployment identity", requestFingerprint, oldState.optString("deploymentFingerprint"));
        assertNotEquals("old continuation must not be relabeled with the rotated credential epoch", currentFingerprint, oldState.optString("deploymentFingerprint"));

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"new answer\"}\n\n"
                        + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");
            }
        });
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second"));
        RecordedRequest secondRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(secondRequest);
        String secondBody = secondRequest.getBody().readUtf8();
        assertFalse("old encrypted reasoning must not replay after credential rotation", secondBody.contains("old-cipher"));
        assertFalse("old provider output item must not replay after credential rotation", secondBody.contains("rs_old"));
        await("second request completes", () -> "completed".equals(app.database().getSession(sessionId).state));
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
