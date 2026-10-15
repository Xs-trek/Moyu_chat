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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * End-to-end coordinator flow on a real SQLite database with a local mock provider endpoint:
 * standalone startup, provider config, streaming turns, interrupt,
 * error mapping, restart recovery, local branches and API key isolation.
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
        volatile String appearance = "";
        @Override public void dispatch(JSONObject envelope) { envelopes.add(envelope); }
        volatile String editorHosts = "";
        @Override public void openCredentialEditor(ChatDatabase.Provider provider, String hosts) { editorProvider = provider; editorHosts = hosts; editorOpened = true; }
        @Override public boolean setTurnRuntimeActive(String sessionId, boolean active) { return !active || runtimeLeaseAllowed; }
        @Override public void showMessage(String text) { toasts.add(text); }
        @Override public void applyAppearance(String resolvedTheme, String tone) { appearance = resolvedTheme + ":" + tone; }
        final List<String> settled = Collections.synchronizedList(new ArrayList<>());
        final List<Boolean> permissionAsks = Collections.synchronizedList(new ArrayList<>());
        @Override public void turnSettled(String sessionId, String title, boolean failed) { settled.add(sessionId + (failed ? ":failed" : ":completed")); }
        @Override public void requestNotificationPermission(boolean onlyOnce) { permissionAsks.add(onlyOnce); }
    }

    /** JVM stand-in: same contract as the Keystore-backed store, which Robolectric cannot host. */
    static final class FakeSecretStore extends ChatSecretStore {
        final java.util.Map<String, String> values = new java.util.HashMap<>();
        FakeSecretStore() { super(RuntimeEnvironment.getApplication()); }
        @Override synchronized void put(String providerId, String value) { if (value == null || value.isEmpty()) values.remove(providerId); else values.put(providerId, value); }
        @Override synchronized String get(String providerId) { return values.get(providerId); }
        @Override boolean has(String providerId) { return values.containsKey(providerId); }
    }

    @Before public void setUp() throws Exception {
        SessionTitles.automatic = false; // naming has its own tests; here it would add a request after every turn
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
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
        coordinator = new ChatCoordinator(app, host, secrets);
        post("app.ready", new JSONObject());
        // Transport/state E2Es assert one provider request per user turn. Smart-context preflight has
        // dedicated tests and is disabled here so these remain protocol-isolation tests.
        post("context.mode.default.set", new JSONObject().put("mode", ContextMode.MINIMAL));
    }

    @After public void tearDown() throws Exception { SessionTitles.automatic = true;
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

    private JSONObject activeSession(JSONObject view) { return view == null ? null : view.optJSONObject("activeSession"); }

    private ChatDatabase.Provider mockProvider(String protocol) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "mock-provider"; p.displayName = "Mock"; p.protocol = protocol;
        p.authMode = ProviderAuth.defaultForProtocol(protocol);
        // OpenAI-style roots carry the version segment; Anthropic-style roots are the SDK root (…/v1/messages is appended).
        p.baseUrl = AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? server.url("/").toString() : server.url("/v1").toString();
        p.model = "mock-model";
        p.contextTokens = 32_000; p.maxOutputTokens = 4_096;
        return p;
    }

    private void configureProvider(String protocol) throws Exception {
        saveDeployment(mockProvider(protocol));
    }

    /** Provider + key through the coordinator (as the UI does), then the single default Model Profile these tests run on. */
    private void saveDeployment(ChatDatabase.Provider provider) throws Exception {
        TestDeployments.save(app.database(), provider);
        coordinator.saveCredential(provider.id, SECRET);
        await("key saved", () -> secrets.has(provider.id));
        post("view.reload", new JSONObject());
        await("provider configured", () -> {
            JSONObject view = latestView();
            JSONArray providers = view == null ? null : view.optJSONArray("providers");
            return providers != null && providers.length() == 1 && providers.optJSONObject(0).optJSONArray("models").length() > 0;
        });
    }

    private String createSession() throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("session created", () -> latestView() != null && latestView().optString("activeLocalSessionId", "").length() > 0);
        return latestView().optString("activeLocalSessionId");
    }

    private String createSessionAfter(String previous) throws Exception {
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("another session created", () -> { String id = latestView().optString("activeLocalSessionId", ""); return !id.isEmpty() && !id.equals(previous); });
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
        assertNull("home projection must not carry the full active conversation timeline", latestView().optJSONObject("activeSession"));
        assertFalse("first back from an internal route must not exit", exitRequested.get());
        coordinator.handleSystemBack(() -> exitRequested.set(true));
        await("second back from home requests Activity exit", exitRequested::get);
    }

    private JSONObject intentResult(String requestId) {
        synchronized (host.envelopes) {
            for (JSONObject e : host.envelopes) if ("intent.result".equals(e.optString("type")) && requestId.equals(e.optString("requestId"))) return e;
        }
        return null;
    }

    @Test public void appearanceAndTodayUsageReachTheViewAndWindowChrome() throws Exception {
        await("initial view", () -> latestView() != null);
        JSONObject appearance = latestView().getJSONObject("appearance");
        assertEquals("system", appearance.optString("theme"));
        assertEquals("sage", appearance.optString("tone"));
        assertTrue(appearance.optBoolean("delight"));
        assertEquals("system theme resolves natively from the (day) uiMode", "light", appearance.optString("resolvedTheme"));
        assertEquals("light:sage", host.appearance);

        post("appearance.set", new JSONObject().put("tone", "warm"));
        post("ui.delight.set", new JSONObject().put("enabled", false));
        post("appearance.set", new JSONObject().put("theme", "dark"));
        await("appearance applied", () -> {
            JSONObject a = latestView().optJSONObject("appearance");
            return a != null && "warm".equals(a.optString("tone")) && !a.optBoolean("delight", true) && "dark".equals(a.optString("resolvedTheme"));
        });
        assertEquals("window chrome follows the resolved theme and tone", "dark:warm", host.appearance);
        String empty = post("appearance.set", new JSONObject());
        await("empty appearance rejected", () -> intentResult(empty) != null);
        assertFalse(intentResult(empty).optBoolean("ok", true));

        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hi"));
        await("today usage counts the finished turn", () -> {
            JSONObject today = latestView().optJSONObject("usageToday");
            return today != null && today.optLong("turns") == 1 && today.optLong("inputTokens") == 7 && today.optLong("outputTokens") == 2;
        });

        post("nav.open", new JSONObject().put("route", "settings"));
        await("settings route", () -> "settings".equals(latestView().optString("route")));
        coordinator.handleSystemBack(() -> { });
        await("back from settings returns to the open conversation", () -> "conversation".equals(latestView().optString("route")));
    }

    @Test public void worksFullyStandaloneAndNeverLeaksApiKey() throws Exception {
        await("initial view", () -> latestView() != null);
        JSONObject view = latestView();
        assertFalse("removed Remote/import projections must not reappear",
                view.has("remoteBridge") || view.has("remoteSources") || view.has("importedSources") || view.has("handoffs"));
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
        }
        sawUsage = lastTurnUsage(sessionId) != null;
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
        java.io.File file = RuntimeEnvironment.getApplication().getDatabasePath(ChatDatabase.FILE_NAME);
        assertTrue("the scan must read the live database file", file.isFile());
        android.database.sqlite.SQLiteDatabase raw = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.getPath(), null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
        try (Cursor tables = raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)) {
            List<String> names = new ArrayList<>();
            while (tables.moveToNext()) names.add(tables.getString(0));
            assertTrue("the scan must see the chat tables", names.contains("providers") && names.contains("timeline"));
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

    @Test public void multiSelectDeleteRemovesIdleSessionsAndSkipsARunningOne() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                MockResponse response = completion("answer", 12, 4);
                return body.contains("take your time") ? response.setBodyDelay(1500, TimeUnit.MILLISECONDS) : response;
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String first = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", first).put("text", "one"));
        await("first answered", () -> "completed".equals(app.database().getSession(first).state));
        String second = createSessionAfter(first);
        post("chat.session.send", new JSONObject().put("localSessionId", second).put("text", "two"));
        await("second answered", () -> "completed".equals(app.database().getSession(second).state));
        String running = createSessionAfter(second);
        post("chat.session.send", new JSONObject().put("localSessionId", running).put("text", "take your time"));
        await("third running", () -> "running".equals(activeSession(latestView()).optString("state")));

        JSONObject outcome = awaitResult(post("chat.session.delete", new JSONObject().put("localSessionIds", new JSONArray().put(first).put(second).put(running).put("no-such-session")))).getJSONObject("data");
        assertEquals(outcome.toString(), 2, outcome.optInt("deleted"));
        assertEquals(1, outcome.getJSONArray("skipped").length());
        assertEquals(running, outcome.getJSONArray("skipped").optString(0));
        assertNull(app.database().getSession(first));
        assertNull(app.database().getSession(second));
        assertNotNull("a session with a turn in flight is kept", app.database().getSession(running));
        assertEquals(running, latestView().optString("activeLocalSessionId"));

        await("third answered", () -> "completed".equals(app.database().getSession(running).state));
        JSONObject last = awaitResult(post("chat.session.delete", new JSONObject().put("localSessionIds", new JSONArray().put(running)))).getJSONObject("data");
        assertEquals(1, last.optInt("deleted"));
        await("deleting the open session returns home", () -> "home".equals(latestView().optString("route")) && latestView().optJSONArray("sessions").length() == 0);
    }

    @Test public void mascotStateIsAWhitelistKeptNativelyAndTheBestScoreOnlyRises() throws Exception {
        post("view.reload", new JSONObject());
        await("first view", () -> latestView() != null);
        JSONObject initial = latestView().getJSONObject("ui").getJSONObject("mascot");
        assertFalse(initial.optBoolean("disguise"));
        assertEquals(0, initial.optInt("gameBest"));
        assertEquals("", initial.optString("koiDay"));

        assertTrue(awaitResult(post("ui.mascot.set", new JSONObject().put("key", "disguise").put("value", true))).optBoolean("ok"));
        assertTrue(awaitResult(post("ui.mascot.set", new JSONObject().put("key", "gameBest").put("value", 42))).optBoolean("ok"));
        assertTrue(awaitResult(post("ui.mascot.set", new JSONObject().put("key", "gameBest").put("value", 7))).optBoolean("ok"));
        assertTrue(awaitResult(post("ui.mascot.set", new JSONObject().put("key", "koiDay").put("value", "2026-10-14"))).optBoolean("ok"));
        assertFalse("a malformed day is refused", awaitResult(post("ui.mascot.set", new JSONObject().put("key", "koiDay").put("value", "today"))).optBoolean("ok"));
        assertFalse("only whitelisted keys are writable", awaitResult(post("ui.mascot.set", new JSONObject().put("key", "theme").put("value", "dark"))).optBoolean("ok"));

        JSONObject mascot = latestView().getJSONObject("ui").getJSONObject("mascot");
        assertTrue(mascot.optBoolean("disguise"));
        assertEquals("a lower score never replaces the best", 42, mascot.optInt("gameBest"));
        assertEquals("2026-10-14", mascot.optString("koiDay"));
        assertEquals("kept across restarts in the settings table", "true", app.database().setting("mascot.disguise", "false"));
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
        await("second turn completes", () -> {
            ChatDatabase.Session current = app.database().getSession(sourceId);
            if (current == null || !"completed".equals(current.state)) return false;
            JSONArray rows = app.database().timeline(sourceId);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject item = rows.optJSONObject(i);
                if (item != null && "message".equals(item.optString("kind")) && "assistant".equals(item.optString("role")) && !item.optBoolean("streaming", false)) return true;
            }
            return false;
        });
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
        saveDeployment(provider);

        // Create and complete the first turn before another profile exists so the baseline model is deterministic.
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first model"));
        await("first model turn completes", () -> "completed".equals(activeSession(latestView()).optString("state")));
        RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(firstRequest);
        assertEquals("mock-model", new JSONObject(firstRequest.getBody().readUtf8()).optString("model"));

        ChatDatabase.ModelProfile second = new ChatDatabase.ModelProfile();
        second.id = "mock-model-b-profile"; second.providerId = provider.id; second.routeId = TestDeployments.routeId(provider);
        second.modelId = "mock-model-b"; second.displayName = "Mock Model B";
        second.contextTokens = 64_000; second.inputTokens = 0; second.maxOutputTokens = 4_096;
        second.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        second.reasoningWire = ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT;
        second.inputModalities = "text,image"; second.outputModalities = "text";
        second.reasoningLevels = "none,low,high";
        app.database().saveModelProfile(second);
        post("view.reload", new JSONObject());
        await("second model available", () -> {
            JSONObject view = latestView();
            JSONObject providerView = view == null || view.optJSONArray("providers") == null ? null : view.optJSONArray("providers").optJSONObject(0);
            return providerView != null && providerView.optJSONArray("models") != null && providerView.optJSONArray("models").length() == 2;
        });

        post("chat.session.model.set", new JSONObject().put("localSessionId", sessionId).put("modelProfileId", second.id));
        await("model switched", () -> second.id.equals(activeSession(latestView()).optString("modelProfileId")));
        assertEquals("Mock Model B", activeSession(latestView()).optString("modelName"));
        assertEquals("none,low,high", activeSession(latestView()).optString("reasoningLevels"));
        assertEquals("model switch must not apply a hidden Model Profile default", "default", activeSession(latestView()).optString("reasoningEffort"));
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

    @Test public void oneSharedProviderRoutesModelsThroughDifferentProtocolsAndReasoningWires() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String path = request.getPath() == null ? "" : request.getPath();
                if (path.endsWith("/responses")) {
                    return sse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"responses answer\"}\n\n"
                            + "data: {\"type\":\"response.completed\",\"response\":{}}\n\n");
                }
                if (path.endsWith("/messages")) {
                    return sse("data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"messages answer\"}}\n\n"
                            + "data: {\"type\":\"message_stop\"}\n\n");
                }
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"chat answer\"},\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n");
            }
        });

        JSONObject saved = awaitResult(post("provider.save", new JSONObject().put("presetId", ProviderPresets.CUSTOM_OPENAI).put("displayName", "Shared gateway")
                .put("routes", new JSONArray()
                        .put(new JSONObject().put("label", "chat").put("protocol", OpenAiCompatibleTransport.CHAT_COMPLETIONS).put("baseUrl", server.url("/v1").toString()))
                        .put(new JSONObject().put("label", "responses").put("protocol", OpenAiCompatibleTransport.RESPONSES).put("baseUrl", server.url("/v1").toString()))
                        .put(new JSONObject().put("label", "messages").put("protocol", AnthropicMessagesTransport.PROTOCOL).put("baseUrl", server.url("/").toString())
                                .put("authMode", ProviderAuth.X_API_KEY)))));
        assertTrue("account save must succeed: " + saved, saved.optBoolean("ok"));
        String sharedId = saved.getJSONObject("data").getString("providerId");
        coordinator.saveCredential(sharedId, SECRET);
        await("key stored", () -> secrets.has(sharedId));
        String chatId = saveModel(sharedId, routeFor(sharedId, OpenAiCompatibleTransport.CHAT_COMPLETIONS), "deepseek-v4-flash-vision-exp", "DS V4 Flash Vision",
                new JSONObject().put("reasoningWire", ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT).put("reasoningLevels", "low,high,max")
                        .put("contextTokens", 128_000).put("maxOutputTokens", 8_192).put("inputModalities", "text,image"));
        String responsesId = saveModel(sharedId, routeFor(sharedId, OpenAiCompatibleTransport.RESPONSES), "gpt-5.6-luna", "Luna",
                new JSONObject().put("reasoningWire", ReasoningPolicy.WIRE_OPENAI_RESPONSES).put("reasoningLevels", "high,xhigh")
                        .put("contextTokens", 128_000).put("maxOutputTokens", 8_192));
        String messagesId = saveModel(sharedId, routeFor(sharedId, AnthropicMessagesTransport.PROTOCOL), "qwen3.8-max", "Qwen",
                new JSONObject().put("reasoningWire", ReasoningPolicy.WIRE_ANTHROPIC_EFFORT).put("reasoningLevels", "low,high")
                        .put("contextTokens", 128_000).put("maxOutputTokens", 8_192));

        await("three model routes projected", () -> {
            JSONObject view = latestView();
            JSONObject providerView = view == null ? null : view.optJSONArray("providers").optJSONObject(0);
            return providerView != null && providerView.optJSONArray("models") != null && providerView.optJSONArray("models").length() == 3;
        });

        post("chat.session.create", new JSONObject().put("providerId", sharedId).put("modelProfileId", chatId)
                .put("contextMode", ContextMode.MINIMAL).put("webMode", ChatTurnOptions.WEB_OFF));
        await("chat route session created", () -> activeSession(latestView()) != null && chatId.equals(activeSession(latestView()).optString("modelProfileId")));
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.reasoning.set", new JSONObject().put("localSessionId", sessionId).put("reasoningEffort", "high"));
        await("chat high selected", () -> "high".equals(activeSession(latestView()).optString("reasoningEffort")));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "chat"));
        RecordedRequest chatRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(chatRequest);
        assertEquals("/v1/chat/completions", chatRequest.getPath());
        assertEquals("Bearer " + SECRET, chatRequest.getHeader("Authorization"));
        assertEquals("high", new JSONObject(chatRequest.getBody().readUtf8()).optString("reasoning_effort"));
        await("chat route completes", () -> "completed".equals(app.database().getSession(sessionId).state));

        post("chat.session.model.set", new JSONObject().put("localSessionId", sessionId).put("modelProfileId", responsesId));
        await("responses model selected", () -> responsesId.equals(activeSession(latestView()).optString("modelProfileId")));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "responses"));
        RecordedRequest responsesRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(responsesRequest);
        assertEquals("/v1/responses", responsesRequest.getPath());
        assertEquals("Bearer " + SECRET, responsesRequest.getHeader("Authorization"));
        assertEquals("high", new JSONObject(responsesRequest.getBody().readUtf8()).getJSONObject("reasoning").optString("effort"));
        await("responses route completes", () -> "completed".equals(app.database().getSession(sessionId).state));

        post("chat.session.model.set", new JSONObject().put("localSessionId", sessionId).put("modelProfileId", messagesId));
        await("messages model selected", () -> messagesId.equals(activeSession(latestView()).optString("modelProfileId")));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "messages"));
        RecordedRequest messagesRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(messagesRequest);
        assertEquals("/v1/messages", messagesRequest.getPath());
        assertEquals(SECRET, messagesRequest.getHeader("x-api-key"));
        assertEquals("high", new JSONObject(messagesRequest.getBody().readUtf8()).getJSONObject("output_config").optString("effort"));
        await("messages route completes", () -> "completed".equals(app.database().getSession(sessionId).state));
    }

    private String routeFor(String providerId, String protocol) {
        for (ChatDatabase.Route route : app.database().listRoutes(providerId)) if (protocol.equals(route.protocol)) return route.id;
        throw new AssertionError("no route for " + protocol);
    }

    private String saveModel(String providerId, String routeId, String modelId, String name, JSONObject overrides) throws Exception {
        JSONObject result = awaitResult(post("model.save", new JSONObject().put("providerId", providerId).put("routeId", routeId)
                .put("modelId", modelId).put("displayName", name).put("overrides", overrides)));
        assertTrue("model save must succeed: " + result, result.optBoolean("ok"));
        return result.getJSONObject("data").getString("modelProfileId");
    }

    @Test public void newChatContextModeRemembersLastExplicitSelection() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider").put("contextMode", ContextMode.SMART));
        await("smart session created", () -> activeSession(latestView()) != null && ContextMode.SMART.equals(activeSession(latestView()).optString("contextMode")));
        assertEquals("explicit new-chat selection becomes the next new-chat preference", ContextMode.SMART,
                latestView().optJSONObject("ui").optString("defaultContextMode"));
        post("nav.open", new JSONObject().put("route", "home"));
        await("home opened", () -> "home".equals(latestView().optString("route")));
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("next session inherits remembered context mode", () -> activeSession(latestView()) != null
                && ContextMode.SMART.equals(activeSession(latestView()).optString("contextMode")));
    }

    @Test public void sendPayloadPersistsSelectedReasoningBeforeFreezeAndNavigation() throws Exception {
        ChatDatabase.Provider provider = mockProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        provider.reasoningWire = ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT;
        saveDeployment(provider);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile(provider.id);
        assertNotNull(profile);
        profile.reasoningLevels = "low,high";
        app.database().saveModelProfile(profile);

        post("chat.session.create", new JSONObject().put("providerId", provider.id).put("contextMode", ContextMode.MINIMAL).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        assertEquals("default", activeSession(latestView()).optString("reasoningEffort"));

        // Deliberately do NOT send chat.session.reasoning.set first. Send must be self-contained with
        // the visible selector value so Native persistence/freeze cannot depend on two bridge intents.
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "use high")
                .put("reasoningEffort", "high"));
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("high", new JSONObject(request.getBody().readUtf8()).optString("reasoning_effort"));
        await("high turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));
        assertEquals("Send must persist the selector value before freezing TurnConfig", "high", app.database().getSession(sessionId).reasoningEffort);

        post("nav.open", new JSONObject().put("route", "home"));
        await("home opened", () -> "home".equals(latestView().optString("route")));
        post("chat.session.open", new JSONObject().put("localSessionId", sessionId));
        await("same session reopened", () -> activeSession(latestView()) != null && sessionId.equals(activeSession(latestView()).optString("localSessionId")));
        assertEquals("same conversation must still display High after leaving and reopening", "high", activeSession(latestView()).optString("reasoningEffort"));

        JSONObject assistant = null;
        JSONArray rows = app.database().timeline(sessionId);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && "message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) assistant = row;
        }
        assertNotNull(assistant);
        assertEquals("high", assistant.optString("reasoningEffort"));
    }

    // ---------- multi-turn + interrupt ----------

    @Test public void interruptIsAvailableDuringSmartPreparationAndPreventsMainProviderTurn() throws Exception {
        post("helper.switch.set", new JSONObject().put("helper", "router").put("enabled", true)); // the Router starts off
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"domain\\\":\\\"general\\\",\\\"taskType\\\":\\\"answer\\\",\\\"depth\\\":\\\"standard\\\",\\\"responseMode\\\":\\\"direct\\\",\\\"contextPolicy\\\":\\\"recent\\\",\\\"modules\\\":[],\\\"confidence\\\":0.9}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
                        .setBodyDelay(10, TimeUnit.SECONDS);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider").put("contextMode", ContextMode.SMART).put("webMode", ChatTurnOptions.WEB_OFF));
        await("smart session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "cancel while routing"));
        await("accepted Smart turn is immediately interruptible", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && active.optBoolean("canInterrupt") && "running".equals(active.optString("state"));
        });
        RecordedRequest preflight = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("Smart router preflight must have started", preflight);

        post("chat.session.interrupt", new JSONObject().put("localSessionId", sessionId));
        await("preparation cancellation settles the accepted turn", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && !active.optBoolean("canInterrupt") && active.optBoolean("canSend")
                    && "completed".equals(active.optString("state"));
        });
        assertNull("interrupt during preflight must prevent the main provider request", server.takeRequest(800, TimeUnit.MILLISECONDS));
        JSONArray rows = app.database().timeline(sessionId);
        assertEquals("cancelled-before-main keeps the user row and removes the empty live assistant", 1, rows.length());
        assertEquals("user", rows.getJSONObject(0).optString("role"));
        assertEquals("cancel while routing", rows.getJSONObject(0).optString("text"));
        JSONObject usage = lastTurnUsage(sessionId);
        assertNotNull("the router call is still accounted on the turn", usage);
        assertEquals(1, usage.getInt("auxiliaryCallCount"));
        assertTrue(usage.getBoolean("usageIncomplete"));
        assertFalse("cancelled request did not report usage; do not invent zero", usage.has("totalInputTokens"));
    }

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

    @Test public void webFollowUpContextUsesOnlyImmediatelyPreviousWebBackedAssistant() throws Exception {
        JSONArray timeline = new JSONArray()
                .put(new JSONObject().put("kind", "message").put("role", "user").put("text", "检索主题"))
                .put(new JSONObject().put("kind", "message").put("role", "assistant").put("text", "result")
                        .put("webBackend", "Exa").put("sources", new JSONArray().put(new JSONObject()
                                .put("url", "https://example.com/a").put("title", "A"))))
                .put(new JSONObject().put("kind", "usage"))
                .put(new JSONObject().put("kind", "message").put("role", "user").put("text", "继续看看"))
                .put(new JSONObject().put("kind", "message").put("role", "assistant").put("streaming", true));
        MoyuWebService.FollowUpContext previous = TurnPipeline.webFollowUpContext(timeline);
        assertTrue(previous.previousTurnUsedWeb);
        assertEquals("检索主题", previous.previousUserText);
        assertEquals(1, previous.sources.length());

        timeline.put(new JSONObject().put("kind", "message").put("role", "assistant").put("text", "plain answer"));
        MoyuWebService.FollowUpContext broken = TurnPipeline.webFollowUpContext(timeline);
        assertFalse("an intervening non-Web assistant must break inheritance", broken.previousTurnUsedWeb);
        assertEquals(0, broken.sources.length());
    }

    @Test public void sessionWebModeCanSwitchFromOffToAutoAndActivateDeterministicFetchFallback() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.MINIMAL).put("webMode", ChatTurnOptions.WEB_OFF));
        await("web-off session created", () -> activeSession(latestView()) != null
                && ChatTurnOptions.WEB_OFF.equals(activeSession(latestView()).optString("webMode")));
        String sessionId = latestView().optString("activeLocalSessionId");

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId)
                .put("text", "read https://test.local/private").put("webMode", ChatTurnOptions.WEB_OFF));
        RecordedRequest offRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(offRequest);
        assertFalse("Web off must not inject Moyu Web context", offRequest.getBody().readUtf8().contains("Moyu Web Context"));
        await("web-off turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));

        post("chat.session.web.set", new JSONObject().put("localSessionId", sessionId).put("webMode", ChatTurnOptions.WEB_AUTO));
        await("session Web mode switched to auto", () -> ChatTurnOptions.WEB_AUTO.equals(activeSession(latestView()).optString("webMode")));
        assertEquals(ChatTurnOptions.WEB_AUTO, app.database().getSession(sessionId).webMode);

        post("nav.open", new JSONObject().put("route", "home"));
        await("home opened", () -> "home".equals(latestView().optString("route")));
        post("chat.session.open", new JSONObject().put("localSessionId", sessionId));
        await("same session reopened with Web auto", () -> activeSession(latestView()) != null
                && ChatTurnOptions.WEB_AUTO.equals(activeSession(latestView()).optString("webMode")));

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId)
                .put("text", "read https://test.local/private").put("webMode", ChatTurnOptions.WEB_AUTO));
        RecordedRequest autoRequest = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(autoRequest);
        String autoBody = autoRequest.getBody().readUtf8();
        assertTrue("Web auto must activate deterministic URL fetch fallback without client tool calling",
                autoBody.contains("Moyu Web Context"));
        assertFalse("Host Web fallback must work even when this deployment has no client-tool wire",
                new JSONObject(autoBody).has("tools"));
        await("web-auto turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));
    }

    @Test public void autoWebToolsAreTurnScopedByProductPlanInsteadOfAlwaysExposed() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        assertNotNull(profile);
        profile.clientToolCalling = ClientToolRuntime.OPENAI_CHAT;
        app.database().saveModelProfile(profile);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.MINIMAL).put("webMode", ChatTurnOptions.WEB_AUTO));
        await("client-tool-capable session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first ordinary turn"));
        RecordedRequest first = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(first);
        JSONObject firstBody = new JSONObject(first.getBody().readUtf8());
        assertFalse("Auto must not advertise Web tools on a stable turn merely because the deployment supports function calling",
                firstBody.has("tools"));
        await("first ordinary turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second ordinary turn"));
        RecordedRequest second = server.takeRequest(5, TimeUnit.SECONDS); assertNotNull(second);
        JSONObject secondBody = new JSONObject(second.getBody().readUtf8());
        assertFalse("client Web capability remains frozen in the session, but exposure is a per-turn product decision",
                secondBody.has("tools"));
        assertEquals(ClientToolRuntime.OPENAI_CHAT, app.database().getSession(sessionId).clientToolCalling);
        await("second ordinary turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));
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
        JSONObject assistant = null, usage = lastTurnUsage(sessionId);
        for (int i = 0; i < timeline.length(); i++) {
            JSONObject row = timeline.optJSONObject(i);
            if (row == null) continue;
            if ("message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) assistant = row;
        }
        assertNotNull(assistant); assertEquals("visible answer", assistant.optString("text")); assertEquals("private thought", assistant.optString("reasoning"));
        assertNotNull(usage); assertEquals(10, usage.optInt("inputTokens")); assertEquals(4, usage.optInt("cachedInputTokens"));
        assertEquals(10, usage.optInt("cacheHitBaseTokens")); assertEquals(1, usage.optInt("reasoningTokens"));
        assertTrue(usage.has("requestDurationMs")); assertTrue(usage.has("outputTokensPerSecond"));
        assertFalse("reasoning display defaults off", latestView().optJSONObject("ui").optBoolean("showReasoning"));
        post("chat.reasoning.visibility.set", new JSONObject().put("show", true));
        await("reasoning visibility enabled", () -> latestView().optJSONObject("ui").optBoolean("showReasoning"));
        post("chat.reasoning.visibility.set", new JSONObject().put("show", false));
        await("reasoning visibility disabled", () -> !latestView().optJSONObject("ui").optBoolean("showReasoning"));
        post("chat.reasoning.visibility.set", new JSONObject().put("show", true));
        await("reasoning visibility can be enabled again", () -> latestView().optJSONObject("ui").optBoolean("showReasoning"));

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
        ChatCoordinator restarted = new ChatCoordinator(app, host, secrets);
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

    // ---------- local branch ----------

    @Test public void localBranchFreezesSnapshotAndRejectsRemovedRemoteIntents() throws Exception {
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
        await("removed handoff intent is rejected", () -> host.envelopes.stream().anyMatch(e ->
                "intent.result".equals(e.optString("type")) && !e.optBoolean("ok")
                        && e.optJSONObject("error") != null && e.optJSONObject("error").optString("summary").contains("unsupported intent")));
    }

    // ---------- raw input edge cases ----------

    @Test public void sessionRenameIsDurableAndVisibleAfterNavigation() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hello"));
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state));
        post("chat.session.rename", new JSONObject().put("localSessionId", sessionId).put("title", "新的会话名称"));
        await("renamed title persisted", () -> "新的会话名称".equals(app.database().getSession(sessionId).title));
        await("renamed title projected to the active conversation", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && "新的会话名称".equals(active.optString("title"));
        });
        post("nav.open", new JSONObject().put("route", "home"));
        await("home shows renamed session", () -> {
            JSONObject view = latestView();
            if (view == null || !"home".equals(view.optString("route"))) return false;
            JSONArray sessions = view.optJSONArray("sessions");
            return sessions != null && sessions.length() == 1 && "新的会话名称".equals(sessions.optJSONObject(0).optString("title"));
        });
    }

    @Test public void webEditorsSaveAccountRoutesAndModelsWhileTheKeyStaysNative() throws Exception {
        // New account: name + routes only. The WebView never sends or receives a key.
        JSONObject saved = awaitResult(post("provider.save", new JSONObject().put("presetId", ProviderPresets.CUSTOM_OPENAI).put("displayName", "Web Provider")
                .put("routes", new JSONArray().put(new JSONObject().put("protocol", OpenAiCompatibleTransport.CHAT_COMPLETIONS).put("baseUrl", server.url("/v1").toString())))));
        assertTrue("account save must succeed: " + saved, saved.optBoolean("ok"));
        String providerId = saved.getJSONObject("data").getString("providerId");
        assertFalse(saved.getJSONObject("data").getBoolean("hasCredential"));
        String routeId = saved.getJSONObject("data").getJSONArray("routeIds").getString(0);

        // Validation errors come back as a failed result for the form, not as a native toast.
        JSONObject bad = awaitResult(post("provider.save", new JSONObject().put("providerId", providerId)
                .put("routes", new JSONArray().put(new JSONObject().put("routeId", routeId).put("baseUrl", "http://evil.example/v1")))));
        assertFalse(bad.optBoolean("ok"));
        assertTrue(bad.getJSONObject("error").getString("summary").contains("HTTPS"));
        JSONObject secretHeader = awaitResult(post("provider.save", new JSONObject().put("providerId", providerId)
                .put("routes", new JSONArray().put(new JSONObject().put("routeId", routeId).put("headers", new JSONObject().put("X-Api-Token", "sk-leak"))))));
        assertFalse("credential-shaped headers are refused", secretHeader.optBoolean("ok"));

        // The key is entered natively; the dialog says where it will be sent.
        post("provider.credential.edit", new JSONObject().put("providerId", providerId));
        await("native key editor opened", () -> host.editorOpened);
        assertEquals(providerId, host.editorProvider.id);
        assertEquals(java.net.URI.create(server.url("/").toString()).getHost(), host.editorHosts);
        java.util.concurrent.atomic.AtomicReference<String> pasteError = new java.util.concurrent.atomic.AtomicReference<>();
        coordinator.saveCredential(providerId, "sk-web editor", error -> pasteError.set(error == null ? "ok" : error));
        await("bad paste rejected", () -> pasteError.get() != null);
        assertTrue(pasteError.get().contains("空格"));
        assertFalse(pasteError.get().contains("sk-web"));
        coordinator.saveCredential(providerId, "  sk-web-editor-secret\n");
        await("key stored", () -> secrets.has(providerId));
        assertEquals("surrounding whitespace is trimmed", "sk-web-editor-secret", secrets.get(providerId));

        JSONObject preview = awaitResult(post("model.preview", new JSONObject().put("providerId", providerId).put("routeId", routeId).put("modelId", "mock-model")));
        assertTrue(preview.optBoolean("ok"));
        assertEquals(DeploymentResolver.FIELDS.length, preview.getJSONObject("data").getJSONArray("fields").length());
        assertEquals("", preview.getJSONObject("data").getString("error"));

        JSONObject manual = new JSONObject().put("providerId", providerId).put("routeId", routeId).put("modelId", "mock-model").put("displayName", "Mock")
                .put("overrides", new JSONObject().put("contextTokens", "32000").put("maxOutputTokens", "4096"));
        JSONObject model = awaitResult(post("model.save", manual));
        assertTrue("model save must succeed: " + model, model.optBoolean("ok"));
        String profileId = model.getJSONObject("data").getString("modelProfileId");
        ChatDatabase.ModelProfile stored = app.database().getModelProfile(profileId);
        assertEquals(32000, stored.contextTokens);
        assertEquals(DeploymentResolver.SRC_MANUAL, new JSONObject(stored.sources).getString("contextTokens"));
        assertEquals("nothing else was declared", DeploymentResolver.SRC_DEFAULT, new JSONObject(stored.sources).getString("clientToolCalling"));

        JSONObject badLimits = awaitResult(post("model.save", new JSONObject(manual.toString()).put("overrides", new JSONObject().put("contextTokens", "abc"))));
        assertFalse("bad limits must fail with a readable error", badLimits.optBoolean("ok"));

        JSONObject edited = awaitResult(post("model.save", new JSONObject(manual.toString()).put("modelProfileId", profileId).put("displayName", "Renamed")));
        assertTrue(edited.optBoolean("ok"));
        assertEquals("Renamed", app.database().getModelProfile(profileId).displayName);

        JSONObject disabled = awaitResult(post("model.enabled.set", new JSONObject().put("modelProfileId", profileId).put("enabled", false)));
        assertTrue(disabled.optBoolean("ok"));
        assertFalse(app.database().getModelProfile(profileId).enabled);

        synchronized (host.envelopes) {
            for (JSONObject e : host.envelopes) assertFalse("the API key must never reach the WebView", e.toString().contains("sk-web-editor-secret"));
        }

        assertTrue(awaitResult(post("provider.delete", new JSONObject().put("providerId", providerId))).optBoolean("ok"));
        assertNull(app.database().getProvider(providerId));
        assertFalse("deleting an account deletes its key", secrets.has(providerId));
    }

    @Test public void presetAccountsResolveFromTheCatalogAndAnEditedEndpointDropsIt() throws Exception {
        JSONObject saved = awaitResult(post("provider.save", new JSONObject().put("presetId", "openai")));
        assertTrue("preset account must save: " + saved, saved.optBoolean("ok"));
        String providerId = saved.getJSONObject("data").getString("providerId");
        assertEquals("the preset's routes come along", 2, app.database().listRoutes(providerId).size());
        assertEquals("OpenAI", app.database().getProvider(providerId).displayName);
        String responsesRoute = routeFor(providerId, OpenAiCompatibleTransport.RESPONSES);
        coordinator.saveCredential(providerId, SECRET);
        await("key stored", () -> secrets.has(providerId));

        String profileId = saveModel(providerId, responsesRoute, "gpt-6.1-sol", "", new JSONObject().put("contextTokens", 200_000));
        ChatDatabase.ModelProfile m = app.database().getModelProfile(profileId);
        assertEquals("catalog name fills an empty display name", "GPT-6.1 Sol", m.displayName);
        assertEquals(ClientToolRuntime.OPENAI_RESPONSES, m.clientToolCalling);
        assertEquals("low,medium,high,xhigh,max", m.reasoningLevels);
        assertEquals(200_000, m.contextTokens);
        assertEquals(128_000, m.maxOutputTokens);
        JSONObject sources = new JSONObject(m.sources);
        assertEquals(DeploymentResolver.SRC_MANUAL, sources.getString("contextTokens"));
        assertEquals(DeploymentResolver.SRC_CATALOG, sources.getString("clientToolCalling"));
        assertEquals(2.0, new JSONObject(m.pricing).getDouble("input"), 0.0001);

        // Moving the route to an unreviewed host drops every catalog claim but keeps what the user set.
        JSONObject moved = awaitResult(post("provider.save", new JSONObject().put("providerId", providerId).put("routes", new JSONArray()
                .put(new JSONObject().put("routeId", responsesRoute).put("protocol", OpenAiCompatibleTransport.RESPONSES).put("baseUrl", "https://relay.example/v1")))));
        assertTrue("route edit must save: " + moved, moved.optBoolean("ok"));
        assertEquals("the omitted Chat Completions route is removed", 1, app.database().listRoutes(providerId).size());
        ChatDatabase.ModelProfile after = app.database().getModelProfile(profileId);
        assertEquals(ClientToolRuntime.NONE, after.clientToolCalling);
        assertEquals("", after.reasoningLevels);
        assertEquals(200_000, after.contextTokens);
        assertEquals(DeploymentResolver.SRC_DEFAULT, new JSONObject(after.sources).getString("clientToolCalling"));

        // The key is not sent to the new host until the user confirms it.
        post("view.reload", new JSONObject());
        await("host awaits confirmation", () -> !routeView(providerId, responsesRoute).optBoolean("hostConfirmed", true));
        post("chat.session.create", new JSONObject().put("providerId", providerId).put("modelProfileId", profileId)
                .put("contextMode", ContextMode.MINIMAL).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session on moved route", () -> activeSession(latestView()) != null && profileId.equals(activeSession(latestView()).optString("modelProfileId")));
        String sessionId = latestView().optString("activeLocalSessionId");
        JSONObject refused = awaitResult(post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hi")));
        assertFalse("an unconfirmed host never receives the key", refused.optBoolean("ok"));
        assertTrue(refused.getJSONObject("error").getString("summary").contains("确认"));
        assertEquals("nothing reached the network", 0, server.getRequestCount());
        assertTrue(awaitResult(post("route.host.confirm", new JSONObject().put("routeId", responsesRoute))).optBoolean("ok"));
        await("host confirmed", () -> routeView(providerId, responsesRoute).optBoolean("hostConfirmed", false));
    }

    @Test public void savingTheSameRouteFormTwiceUpdatesInsteadOfDuplicating() throws Exception {
        String createId = post("provider.save", new JSONObject().put("presetId", "deepseek"));
        JSONObject created = awaitResult(createId);
        assertTrue("preset account must save: " + created, created.optBoolean("ok"));
        String providerId = created.getJSONObject("data").getString("providerId");
        java.util.List<ChatDatabase.Route> routes = app.database().listRoutes(providerId);
        assertEquals(3, routes.size());
        assertEquals("routes keep the preset order; Responses is the default", OpenAiCompatibleTransport.RESPONSES, routes.get(0).protocol);
        // The editor reads the saved account from the view when the result arrives, so the view comes first.
        synchronized (host.envelopes) {
            int result = -1, view = -1;
            for (int i = 0; i < host.envelopes.size(); i++) {
                JSONObject e = host.envelopes.get(i);
                if ("intent.result".equals(e.optString("type")) && createId.equals(e.optString("requestId"))) result = i;
                if ("view.full".equals(e.optString("type")) && e.optJSONObject("view").optJSONArray("providers").length() == 1 && result < 0) view = i;
            }
            assertTrue("view with the new account precedes the result", view >= 0 && view < result);
        }
        String profileId = saveModel(providerId, routes.get(0).id, "deepseek-flash", "", new JSONObject());

        // A stale form resubmits the preset routes without ids: they are the stored routes, not new ones.
        JSONArray resubmit = new JSONArray();
        for (ChatDatabase.Route r : routes) resubmit.put(new JSONObject().put("presetRouteId", r.presetRouteId)
                .put("label", r.label).put("protocol", r.protocol).put("baseUrl", r.baseUrl).put("authMode", r.authMode));
        JSONObject again = awaitResult(post("provider.save", new JSONObject().put("providerId", providerId).put("routes", resubmit)));
        assertTrue("resubmitting must not hit the unique index: " + again, again.optBoolean("ok"));
        JSONArray ids = again.getJSONObject("data").getJSONArray("routeIds");
        for (int i = 0; i < routes.size(); i++) assertEquals("ids come back in submitted order", routes.get(i).id, ids.getString(i));
        assertEquals(3, app.database().listRoutes(providerId).size());
        assertNotNull("the model on the reused route survives", app.database().getModelProfile(profileId));

        // Removing a route and adding an identical one in the same save also reuses it.
        JSONArray swapped = new JSONArray().put(new JSONObject().put("routeId", routes.get(1).id).put("protocol", routes.get(1).protocol).put("baseUrl", routes.get(1).baseUrl))
                .put(new JSONObject().put("protocol", routes.get(0).protocol).put("baseUrl", routes.get(0).baseUrl));
        JSONObject swap = awaitResult(post("provider.save", new JSONObject().put("providerId", providerId).put("routes", swapped)));
        assertTrue("swap must save: " + swap, swap.optBoolean("ok"));
        assertEquals(2, app.database().listRoutes(providerId).size());
        assertNotNull(app.database().getModelProfile(profileId));
    }

    private JSONObject routeView(String providerId, String routeId) {
        JSONObject view = latestView();
        JSONArray providers = view == null ? null : view.optJSONArray("providers");
        if (providers != null) for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null || !providerId.equals(p.optString("providerId"))) continue;
            JSONArray routes = p.optJSONArray("routes");
            for (int j = 0; routes != null && j < routes.length(); j++) if (routeId.equals(routes.optJSONObject(j).optString("routeId"))) return routes.optJSONObject(j);
        }
        return new JSONObject();
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

    @Test public void sendRefusedBeforeAcceptanceRestoresDraftAndSaysSo() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        app.database().setDraft(sessionId, "stale half-typed draft");
        secrets.values.clear(); // freezeTurnConfig refuses: credential missing
        String submitted = "the message the composer already cleared";

        String id = post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", submitted));
        JSONObject result = awaitResult(id);

        assertFalse(result.optBoolean("ok"));
        assertTrue("the result tells the WebView the text is the draft again", result.optJSONObject("data").optBoolean("draftRestored"));
        assertEquals(submitted, app.database().getSession(sessionId).draft);
        assertEquals("nothing was accepted", 0, app.database().timeline(sessionId).length());
    }

    @Test public void savingADraftPushesNoView() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        int before = host.envelopes.size();
        String id = post("chat.session.saveDraft", new JSONObject().put("localSessionId", sessionId).put("text", "typing"));
        assertTrue(awaitResult(id).optBoolean("ok"));
        assertEquals("typing", app.database().getSession(sessionId).draft);
        synchronized (host.envelopes) {
            for (JSONObject e : host.envelopes.subList(before, host.envelopes.size())) assertNotEquals("view.full", e.optString("type"));
        }
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
            // The new Chat is blank, so only the running one is listed until it gets a message.
            return view != null && view.optJSONArray("sessions").length() == 1
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
        saveDeployment(provider);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile(provider.id);
        assertNotNull(profile);
        profile.reasoningLevels = "low,high";
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
                for (int i = baselineEnvelopeCount; i < host.envelopes.size(); i++) {
                    JSONObject envelope = host.envelopes.get(i);
                    if (!"view.patch".equals(envelope.optString("type"))) continue;
                    JSONObject item = envelope.optJSONObject("patch") == null ? null : envelope.optJSONObject("patch").optJSONObject("item");
                    if (item != null && item.optInt("localSeq", 0) > 0) return true;
                }
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
        String requestFingerprint = ProviderContinuationIdentity.fingerprint(app.database(), app.database().sessionDeployment(sessionBefore), sessionBefore);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        RecordedRequest firstRequest = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull("first request must start before credential rotation", firstRequest);

        coordinator.saveCredential("mock-provider", rotatedSecret);
        await("credential rotation applied", () -> rotatedSecret.equals(secrets.get("mock-provider")));
        ChatDatabase.Session sessionNow = app.database().getSession(sessionId);
        String currentFingerprint = ProviderContinuationIdentity.fingerprint(app.database(), app.database().sessionDeployment(sessionNow), sessionNow);
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

    private static MockResponse completion(String text, int input, int output) {
        return sse("data: {\"choices\":[{\"delta\":{\"content\":" + JSONObject.quote(text) + "}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":"
                + input + ",\"completion_tokens\":" + output + "}}\n\n");
    }

    private JSONObject turnUsage(String sessionId) throws Exception {
        JSONArray turns = app.database().turns(sessionId);
        assertEquals("a user turn has one durable turn record", 1, turns.length());
        for (int i = 0; i < app.database().timeline(sessionId).length(); i++) {
            assertNotEquals("usage is never a timeline row", "usage", app.database().timeline(sessionId).getJSONObject(i).optString("kind"));
        }
        return turns.getJSONObject(0).getJSONObject("usage");
    }

    /** Usage summary of the session's latest turn that made model calls, or null. */
    private JSONObject lastTurnUsage(String sessionId) {
        JSONArray turns = app.database().turns(sessionId);
        for (int i = turns.length() - 1; i >= 0; i--) {
            JSONObject usage = turns.optJSONObject(i).optJSONObject("usage");
            if (usage != null && usage.length() > 0) return usage;
        }
        return null;
    }

    @Test public void minimalWebPlannerUsageIsIncludedEvenWhenItChoosesNoRetrieval() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return request.getBody().clone().readUtf8().contains("Web retrieval planner")
                        ? completion("{\"useWeb\":false,\"intent\":\"none\",\"queries\":[]}", 11, 3)
                        : completion("Shakespeare.", 7, 2);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.MINIMAL).put("webMode", ChatTurnOptions.WEB_AUTO));
        await("minimal session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Who wrote Hamlet?"));
        await("planner and answer complete", () -> "completed".equals(app.database().getSession(sessionId).state));
        JSONObject usage = turnUsage(sessionId);
        assertEquals(2, server.getRequestCount());
        assertEquals(1, usage.getInt("auxiliaryCallCount"));
        assertEquals(11, usage.getInt("auxiliaryInputTokens"));
        assertEquals(7, usage.getInt("inputTokens"));
        assertEquals(18, usage.getInt("totalInputTokens"));
        assertEquals(5, usage.getInt("totalOutputTokens"));
        assertFalse(usage.getBoolean("usageIncomplete"));
    }

    @Test public void smartRouterVerificationRepairAndRecheckSettleIntoSameUsageRow() throws Exception {
        post("helper.switch.set", new JSONObject().put("helper", "router").put("enabled", true)); // the Router starts off
        AtomicInteger verifications = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                if (body.contains("Classify the conversation")) return completion("invalid router JSON", 6, 2);
                if (body.contains("Repair the candidate answer")) return completion("fixed answer", 13, 4);
                if (body.contains("You are a verification component")) {
                    boolean fail = verifications.incrementAndGet() == 1;
                    try {
                        JSONArray checks = new JSONArray();
                        for (String rule : new String[]{"constraint_adherence", "evidence_integrity", "completion_honesty",
                                "uncertainty_honesty", "material_factual_support"}) {
                            boolean violation = fail && "constraint_adherence".equals(rule);
                            checks.put(new JSONObject().put("rule", rule).put("result", violation ? "fail" : "pass")
                                    .put("severity", violation ? "hard" : "minor")
                                    .put("evidenceIds", new JSONArray().put("user:current")).put("note", "fixture"));
                        }
                        return completion(new JSONObject().put("verdict", fail ? "fail" : "pass")
                                .put("selfConfidence", 0.9).put("checks", checks).toString(), 11, 3);
                    } catch (Exception error) { throw new AssertionError(error); }
                }
                return completion("candidate answer", 7, 2);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.harnessPreset = HarnessProfile.REINFORCED;
        app.database().saveModelProfile(profile);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.SMART).put("webMode", ChatTurnOptions.WEB_OFF));
        await("smart session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Reply with fixed answer."));
        await("smart repair completes", () -> "completed".equals(app.database().getSession(sessionId).state));
        JSONObject usage = turnUsage(sessionId);
        assertEquals(5, server.getRequestCount());
        assertEquals(2, verifications.get());
        assertEquals(4, usage.getInt("auxiliaryCallCount"));
        assertEquals(41, usage.getInt("auxiliaryInputTokens"));
        assertEquals(12, usage.getInt("auxiliaryOutputTokens"));
        assertEquals(7, usage.getInt("inputTokens"));
        assertEquals(48, usage.getInt("totalInputTokens"));
        assertEquals(14, usage.getInt("totalOutputTokens"));
        assertFalse(usage.getBoolean("usageIncomplete"));
        assertTrue(app.database().timeline(sessionId).toString().contains("fixed answer"));
    }

    /** A verifier reply: every rule passes, or constraint_adherence fails hard. */
    private static String verdict(boolean fail) {
        try {
            JSONArray checks = new JSONArray();
            for (String rule : new String[]{"constraint_adherence", "evidence_integrity", "completion_honesty",
                    "uncertainty_honesty", "material_factual_support"}) {
                boolean violation = fail && "constraint_adherence".equals(rule);
                checks.put(new JSONObject().put("rule", rule).put("result", violation ? "fail" : "pass")
                        .put("severity", violation ? "hard" : "minor")
                        .put("evidenceIds", new JSONArray().put("user:current")).put("note", "fixture"));
            }
            return new JSONObject().put("verdict", fail ? "fail" : "pass").put("selfConfidence", 0.9).put("checks", checks).toString();
        } catch (Exception error) { throw new AssertionError(error); }
    }

    @Test public void aRouterCallOutlastingEditsNeverPutsTheOldSessionBack() throws Exception {
        java.util.concurrent.CountDownLatch routing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                if (request.getBody().clone().readUtf8().contains("Classify the conversation")) {
                    routing.countDown();
                    release.await(10, TimeUnit.SECONDS);
                    return completion("{\"domain\":\"software\",\"taskType\":\"answer\",\"depth\":\"standard\",\"responseMode\":\"direct\",\"contextPolicy\":\"recent\",\"modules\":[],\"confidence\":0.9}", 5, 2);
                }
                return completion("plain answer", 7, 2);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        post("helper.switch.set", new JSONObject().put("helper", "router").put("enabled", true));
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.SMART).put("webMode", ChatTurnOptions.WEB_OFF));
        await("smart session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first question"));
        assertTrue("router call started", routing.await(10, TimeUnit.SECONDS));
        assertTrue(awaitResult(post("chat.session.saveDraft", new JSONObject().put("localSessionId", sessionId).put("text", "typed while routing"))).optBoolean("ok"));
        assertTrue(awaitResult(post("chat.session.rename", new JSONObject().put("localSessionId", sessionId).put("title", "Renamed meanwhile"))).optBoolean("ok"));
        release.countDown();
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);

        ChatDatabase.Session stored = app.database().getSession(sessionId);
        assertEquals("typed while routing", stored.draft);
        assertEquals("Renamed meanwhile", stored.title);
        assertEquals(0, app.database().draftMedia(sessionId).length());
        assertEquals("software", TaskProfile.fromStored(stored.taskProfile).domain);
    }

    @Test public void aRepairTheRecheckDoesNotAcceptIsKeptBesideTheFirstAnswer() throws Exception {
        AtomicInteger verifications = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                if (body.contains("Repair the candidate answer")) return completion("fixed answer", 13, 4);
                if (body.contains("You are a verification component")) { verifications.incrementAndGet(); return completion(verdict(true), 11, 3); }
                return completion("candidate answer", 7, 2);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.harnessPreset = HarnessProfile.REINFORCED;
        app.database().saveModelProfile(profile);
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.SMART).put("webMode", ChatTurnOptions.WEB_OFF));
        await("smart session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Reply with fixed answer."));
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);

        assertEquals(2, verifications.get());
        JSONObject answer = app.database().timeline(sessionId).getJSONObject(1);
        assertEquals("the first answer stays", "candidate answer", answer.getString("text"));
        JSONObject verification = answer.getJSONObject("verification");
        assertFalse(verification.getBoolean("repaired"));
        assertTrue(verification.getBoolean("repairRejected"));
        assertEquals("fixed answer", verification.getString("repairCandidateText"));
        assertEquals("its own verdict stays on it", "fail", verification.getString("verdict"));
        assertEquals(SmartVerifierCalibration.ACTION_ABSTAIN, verification.getString("action"));
        assertEquals(2, verification.getJSONArray("attempts").length());
    }

    @Test public void autoWebOffersTheAnsweringModelWebToolsWhenThePlanSaysNo() throws Exception {
        List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                bodies.add(request.getBody().clone().readUtf8());
                return completion("a poem", 7, 2);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.clientToolCalling = ClientToolRuntime.OPENAI_CHAT;
        app.database().saveModelProfile(profile);
        post("helper.switch.set", new JSONObject().put("helper", "webPlan").put("enabled", false)); // rules only: no planner call
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.STANDARD).put("webMode", ChatTurnOptions.WEB_AUTO));
        await("session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Write a short poem about tea."));
        await("first turn", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        String first = null;
        for (String body : new ArrayList<>(bodies)) if (body.contains("poem about tea") && !body.contains("You name chat conversations")) first = body;
        assertNotNull(first);
        assertTrue("the tools are offered", first.contains("\"web_search\"") && first.contains("\"web_fetch\""));
        assertTrue(first.contains("No Web search was run in advance"));
        String turnId = app.database().timeline(sessionId).getJSONObject(1).optString("turnId");
        JSONArray spans = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).getJSONObject("data").getJSONArray("spans");
        boolean seen = false;
        for (int i = 0; i < spans.length(); i++) if (TurnTrace.TOOLS.equals(spans.getJSONObject(i).optString("stage"))) {
            assertTrue(spans.getJSONObject(i).getJSONObject("detail").getBoolean("webOnDemand"));
            seen = true;
        }
        assertTrue(seen);

        post("helper.switch.set", new JSONObject().put("helper", "webTools").put("enabled", false));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Another one about coffee."));
        await("second turn", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 4);
        String second = null;
        for (String body : new ArrayList<>(bodies)) if (body.contains("about coffee") && !body.contains("You name chat conversations")) second = body;
        assertNotNull(second);
        assertFalse("switched off: no tools, no guidance", second.contains("web_search"));
    }

    @Test public void theRouterStartsSwitchedOff() throws Exception {
        await("view", () -> latestView() != null && latestView().optJSONObject("ui") != null);
        assertEquals("[\"router\"]", String.valueOf(latestView().getJSONObject("ui").optJSONArray("helpersOff")));
        assertEquals(String.valueOf(HelperSwitches.DEFAULTS_REVISION), app.database().setting(HelperSwitches.DEFAULTS_KEY, ""));
    }

    @Test public void historyRecallReturnsTheVerbatimTurnsTheCheckpointReplaced() throws Exception {
        List<String> toolRounds = Collections.synchronizedList(new ArrayList<>());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                if (body.contains("\"role\":\"tool\"")) {
                    toolRounds.add(body);
                    return completion("It was 4471.", 11, 3);
                }
                if (body.contains("What was the vault code")) {
                    return sse("data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_recall\",\"type\":\"function\",\"function\":{\"name\":\"history_recall\",\"arguments\":\"{\\\"query\\\":\\\"vault code\\\"}\"}}]}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":1}}\n\n");
                }
                return completion("Noted.", 5, 1);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.clientToolCalling = ClientToolRuntime.OPENAI_CHAT;
        app.database().saveModelProfile(profile);
        post("helper.switch.set", new JSONObject().put("helper", "compaction").put("enabled", false));
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.STANDARD).put("webMode", ChatTurnOptions.WEB_OFF));
        await("session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Remember this: the vault code is 4471."));
        await("first turn", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        // As if an earlier compaction had summarized the first exchange: it is no longer in the prompt word for word.
        app.database().setContextCheckpoint(sessionId, new JSONObject().put("summary", "The user shared a code earlier.").toString(), 2);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "What was the vault code exactly?"));
        await("second turn", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 4);
        assertEquals("It was 4471.", app.database().timeline(sessionId).getJSONObject(3).getString("text"));
        assertEquals(1, toolRounds.size());
        assertTrue("the excerpt is verbatim, with its message number", toolRounds.get(0).contains("the vault code is 4471.") && toolRounds.get(0).contains("[message #1, user]"));
        String turnId = app.database().timeline(sessionId).getJSONObject(3).optString("turnId");
        JSONArray spans = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).getJSONObject("data").getJSONArray("spans");
        for (int i = 0; i < spans.length(); i++) {
            JSONObject span = spans.getJSONObject(i);
            if (TurnTrace.TOOLS.equals(span.optString("stage"))) assertTrue(span.getJSONObject("detail").getBoolean("recall"));
            if (TurnTrace.GENERATE.equals(span.optString("stage"))) assertEquals(1, span.getJSONObject("detail").getInt("recalls"));
        }
    }

    @Test public void standardSubagentToolLoopReturnsOneFinalParentAnswerAndAggregatesChildUsage() throws Exception {
        AtomicInteger parentRounds = new AtomicInteger();
        List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                requestBodies.add(body);
                if (body.contains("You are an isolated worker for Moyu Chat")) {
                    return sse("data: {\"choices\":[{\"delta\":{\"content\":\"child finding\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}\n\n");
                }
                if (body.contains("\"role\":\"tool\"")) {
                    parentRounds.incrementAndGet();
                    return sse("data: {\"choices\":[{\"delta\":{\"content\":\"final from child\"}}]}\n\n"
                            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":3}}\n\n");
                }
                parentRounds.incrementAndGet();
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"intermediate must disappear\",\"tool_calls\":[{\"index\":0,\"id\":\"call_delegate\",\"type\":\"function\",\"function\":{\"name\":\"delegate_subtask\",\"arguments\":\"{\\\"task\\\":\\\"inspect isolated fact\\\"}\"}}]}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":1}}\n\n");
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        assertNotNull(profile);
        profile.clientToolCalling = ClientToolRuntime.OPENAI_CHAT;
        profile.harnessPreset = HarnessProfile.BALANCED;
        app.database().saveModelProfile(profile);
        post("subagent.standard.default.set", new JSONObject().put("mode", HarnessPolicyResolver.SUBAGENT_AUTO));
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.STANDARD).put("webMode", ChatTurnOptions.WEB_OFF));
        await("standard subagent session created", () -> activeSession(latestView()) != null
                && HarnessPolicyResolver.SUBAGENT_AUTO.equals(activeSession(latestView()).optString("subagentMode")));
        String sessionId = latestView().optString("activeLocalSessionId");

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "use an isolated subtask"));
        await("tool-loop turn completes", () -> "completed".equals(app.database().getSession(sessionId).state));
        assertEquals("parent must run exactly two provider rounds", 2, parentRounds.get());
        assertEquals("parent + child + parent should make three provider requests", 3, requestBodies.size());
        assertTrue("first parent round must expose delegate_subtask", requestBodies.get(0).contains("delegate_subtask"));
        assertTrue("isolated child call must not receive client tools", requestBodies.stream().anyMatch(body -> body.contains("isolated worker") && !body.contains("\"tools\"")));
        assertTrue("second parent round must receive child tool result", requestBodies.stream().anyMatch(body -> body.contains("\"role\":\"tool\"") && body.contains("child finding")));

        JSONArray rows = app.database().timeline(sessionId);
        JSONObject assistant = null, usage = lastTurnUsage(sessionId);
        int assistantCount = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            if ("message".equals(row.optString("kind")) && "assistant".equals(row.optString("role"))) { assistantCount++; assistant = row; }
        }
        assertEquals("tool decision must not create an extra visible assistant turn", 1, assistantCount);
        assertNotNull(assistant);
        assertEquals("final from child", assistant.optString("text"));
        assertFalse("intermediate tool-decision text must be removed", assistant.optString("text").contains("intermediate must disappear"));
        assertNotNull(usage);
        assertEquals(1, usage.optInt("subagentCount"));
        assertEquals(5, usage.optInt("childInputTokens"));
        assertEquals(2, usage.optInt("childOutputTokens"));
        assertEquals(18, usage.optInt("inputTokens"));
        assertEquals(4, usage.optInt("outputTokens"));
        assertEquals(23, usage.optInt("totalInputTokens"));
        assertEquals(6, usage.optInt("totalOutputTokens"));
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

    // ---------- Retry, result notifications, edited profiles, context budget ----------

    private void failFirstRequest() {
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                // 400 is not retryable, so the turn fails (5xx would be retried automatically).
                if (calls.getAndIncrement() == 0) return new MockResponse().setResponseCode(400).setBody("{\"error\":{\"message\":\"boom\"}}");
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"mock answer\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n"
                        + "data: [DONE]\n\n");
            }
        });
    }

    @Test public void retryRewindsTheFailedTurnSendsTheQuestionAgainAndKeepsTheComposer() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        failFirstRequest();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "the question"));
        await("turn failed", () -> "failed".equals(app.database().getSession(sessionId).state));
        await("idle view offers a retry", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && active.optInt("retryLocalSeq") == 1 && active.optBoolean("canSend");
        });
        assertTrue("a failed turn is reported to the host", host.settled.contains(sessionId + ":failed"));

        app.database().setDraft(sessionId, "something new being typed");
        post("chat.turn.retry", new JSONObject().put("localSessionId", sessionId));
        await("retried turn completed", () -> {
            ChatDatabase.Session s = app.database().getSession(sessionId);
            return "completed".equals(s.state) && app.database().timeline(sessionId).length() == 2;
        });
        JSONArray rows = app.database().timeline(sessionId);
        assertEquals("the question", rows.getJSONObject(0).optString("text"));
        assertEquals("mock answer", rows.getJSONObject(1).optString("text"));
        for (int i = 0; i < rows.length(); i++) assertNotEquals("the failed turn's error row is gone", "error", rows.getJSONObject(i).optString("kind"));
        assertEquals("text typed meanwhile stays in the composer", "something new being typed", app.database().getSession(sessionId).draft);
        await("no retry once the turn completed", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && "completed".equals(active.optString("state")) && !active.has("retryLocalSeq");
        });
        assertTrue(host.settled.contains(sessionId + ":completed"));
    }

    @Test public void retryIsRefusedWhenTheLastTurnCompleted() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "fine"));
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        JSONObject refused = awaitResult(post("chat.turn.retry", new JSONObject().put("localSessionId", sessionId)));
        assertFalse(refused.optBoolean("ok"));
        assertEquals("最后一轮已经完成，不需要重试", refused.optJSONObject("error").optString("summary"));
        assertEquals(2, app.database().timeline(sessionId).length());

        JSONArray failedTurn = new JSONArray().put(new JSONObject().put("kind", "message").put("role", "user").put("localSeq", 3).put("text", "q"))
                .put(new JSONObject().put("kind", "error").put("localSeq", 4));
        assertNotNull(ChatCoordinator.retryTarget(failedTurn));
        JSONArray interrupted = new JSONArray().put(new JSONObject().put("kind", "message").put("role", "user").put("localSeq", 3))
                .put(new JSONObject().put("kind", "message").put("role", "assistant").put("localSeq", 4).put("incomplete", true));
        assertNotNull("an unfinished answer can be retried", ChatCoordinator.retryTarget(interrupted));
        JSONArray unanswered = new JSONArray().put(new JSONObject().put("kind", "message").put("role", "user").put("localSeq", 3));
        assertNotNull("a question whose empty answer was dropped can be retried", ChatCoordinator.retryTarget(unanswered));
        JSONArray inherited = new JSONArray().put(new JSONObject().put("kind", "message").put("role", "user").put("localSeq", 3).put("inherited", true));
        assertNull("history inherited from another session is not this session's to rewind", ChatCoordinator.retryTarget(inherited));
    }

    @Test public void resultNotificationsSkipStoppedTurnsAndFollowTheSetting() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        await("completed turn reported", () -> host.settled.contains(sessionId + ":completed"));
        assertEquals("the first accepted turn asks once for the notification permission", Boolean.TRUE, host.permissionAsks.get(0));

        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return sse("data: {\"choices\":[{\"delta\":{\"content\":\"slow\"}}]}\n\n").setBodyDelay(10, TimeUnit.SECONDS);
            }
        });
        host.settled.clear();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "stop me"));
        await("turn running", () -> { JSONObject a = activeSession(latestView()); return a != null && a.optBoolean("canInterrupt"); });
        post("chat.session.interrupt", new JSONObject().put("localSessionId", sessionId));
        await("stopped turn settled", () -> { JSONObject a = activeSession(latestView()); return a != null && a.optBoolean("canSend") && !a.optBoolean("canInterrupt"); });
        assertTrue("a turn the user stopped is not notified", host.settled.isEmpty());

        post("notify.background.set", new JSONObject().put("enabled", false));
        await("setting projected", () -> latestView() != null && !latestView().optJSONObject("ui").optBoolean("notifyInBackground", true));
        failFirstRequest();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "quiet failure"));
        await("turn failed", () -> "failed".equals(app.database().getSession(sessionId).state));
        assertTrue("notifications switched off", host.settled.isEmpty());
        post("notify.background.set", new JSONObject().put("enabled", true));
        await("turning it on asks for the permission again", () -> host.permissionAsks.contains(Boolean.FALSE));
    }

    @Test public void anEditedModelProfileIsFlaggedUntilTheSessionAppliesIt() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        ChatDatabase.ModelProfile profile = app.database().getModelProfile(app.database().getSession(sessionId).modelProfileId);
        assertFalse(ChatCoordinator.profileChanged(app.database().getSession(sessionId), profile));
        JSONObject first = activeSession(latestView());
        assertFalse(first.has("profileChanged"));
        ChatDatabase.Session frozen = app.database().getSession(sessionId);
        assertEquals("the view carries the budget the context builder fills",
                new ContextBudgetManager().effectiveInputBudget(frozen.contextTokens, frozen.inputTokens, frozen.maxOutputTokens), first.optInt("contextBudgetTokens"));

        profile.maxOutputTokens = profile.maxOutputTokens / 2;
        app.database().saveModelProfile(profile);
        post("view.reload", new JSONObject());
        await("edited profile flagged", () -> activeSession(latestView()).optBoolean("profileChanged"));

        post("chat.session.model.set", new JSONObject().put("localSessionId", sessionId).put("modelProfileId", profile.id));
        await("applying it clears the flag", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && !active.has("profileChanged");
        });
        assertEquals(profile.maxOutputTokens, app.database().getSession(sessionId).maxOutputTokens);

        profile.enabled = false;
        profile.maxOutputTokens = profile.maxOutputTokens / 2;
        assertFalse("a disabled profile is not offered", ChatCoordinator.profileChanged(app.database().getSession(sessionId), profile));
    }

    // ---------- Provider guard: key check, model list, probe, budget, retries, route concurrency ----------

    private static final String SSE_OK = "data: {\"choices\":[{\"delta\":{\"content\":\"mock answer\"}}]}\n\n"
            + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}\n\n"
            + "data: [DONE]\n\n";

    @Test public void keyCheckReadsTheModelListAndFeedsDiscoveredLimits() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().endsWith("/models")) return new MockResponse().setBody("{\"data\":[{\"id\":\"mock-model\",\"context_length\":64000,\"max_output_tokens\":8000},{\"id\":\"other\"}]}");
                return sse(SSE_OK);
            }
        });
        JSONObject result = awaitResult(post("provider.key.verify", new JSONObject().put("providerId", "mock-provider")));
        assertTrue(result.toString(), result.optBoolean("ok"));
        JSONObject data = result.getJSONObject("data");
        assertEquals("valid", data.getString("keyStatus"));
        assertEquals(2, data.getJSONArray("models").length());
        RecordedRequest listed = null;
        for (RecordedRequest r = server.takeRequest(2, TimeUnit.SECONDS); r != null; r = server.takeRequest(200, TimeUnit.MILLISECONDS)) if (r.getPath().endsWith("/models")) listed = r;
        assertNotNull(listed);
        assertEquals("GET", listed.getMethod());
        assertEquals("/v1/models", listed.getPath());
        assertEquals("Bearer " + SECRET, listed.getHeader("Authorization"));
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        assertEquals("the endpoint's own limit becomes the discovered layer", 64_000, profile.contextTokens);
        assertEquals("discovered", DeploymentResolver.json(profile.sources).optString("contextTokens"));
        await("view shows the checked key", () -> {
            JSONObject provider = latestView().optJSONArray("providers").optJSONObject(0);
            return "valid".equals(provider.optString("keyStatus")) && !provider.optString("keyCheckedAt").isEmpty()
                    && provider.optJSONArray("models").optJSONObject(0).optBoolean("listed");
        });
        assertFalse("no key in any view", latestView().toString().contains(SECRET));
    }

    @Test public void aRefusedKeyIsInvalidAndARedirectIsNeverFollowed() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        server.setDispatcher(new Dispatcher() {
            // A misconfigured endpoint may echo the key back; it must never reach a summary.
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key " + SECRET + "\"}}"); }
        });
        JSONObject refused = awaitResult(post("provider.key.verify", new JSONObject().put("providerId", "mock-provider"))).getJSONObject("data");
        assertEquals("invalid", refused.getString("keyStatus"));
        assertFalse(refused.optBoolean("ok"));
        assertTrue(refused.optString("summary").contains("API Key 无效"));
        assertFalse("the echoed key is scrubbed", refused.toString().contains(SECRET));
        assertEquals("the target host is shown", server.getHostName(), refused.optString("host"));
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hi"));
        await("turn failed", () -> "failed".equals(app.database().getSession(sessionId).state));
        assertFalse("nor does the timeline error", app.database().timeline(sessionId).toString().contains(SECRET));
        await("a 401 turn marks the key invalid", () -> "invalid".equals(app.database().getProvider("mock-provider").keyStatus));

        int before = server.getRequestCount();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { return new MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/v1/models"); }
        });
        JSONObject redirected = awaitResult(post("provider.key.verify", new JSONObject().put("providerId", "mock-provider"))).getJSONObject("data");
        assertEquals("redirect", redirected.getString("code"));
        assertEquals("error", redirected.getString("keyStatus"));
        assertEquals("exactly one request: the redirect was not followed", before + 1, server.getRequestCount());
    }

    @Test public void aConnectionTestIsStoredWithTheModelAndChangesNoCapability() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile before = app.database().firstModelProfile("mock-provider");
        JSONObject result = awaitResult(post("model.probe", new JSONObject().put("modelProfileId", before.id))).getJSONObject("data");
        assertTrue(result.toString(), result.getBoolean("ok"));
        assertTrue(result.getBoolean("usageReported"));
        assertEquals("mock answer", result.getString("reply"));
        assertTrue(result.has("firstOutputMs"));
        ChatDatabase.ModelProfile after = app.database().getModelProfile(before.id);
        assertTrue(DeploymentResolver.json(after.probe).getJSONObject("result").getBoolean("ok"));
        assertEquals(before.contextTokens, after.contextTokens);
        assertEquals(before.maxOutputTokens, after.maxOutputTokens);
        await("probe in the view", () -> latestView().optJSONArray("providers").optJSONObject(0).optJSONArray("models").optJSONObject(0).optJSONObject("probe") != null);
    }

    @Test public void aReachedBudgetAsksBeforeEachTurnAndConfirmationGoesAhead() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.pricing = "{\"currency\":\"USD\",\"input\":1000,\"output\":1000}";
        app.database().saveModelProfile(profile);
        ChatDatabase.Provider account = app.database().getProvider("mock-provider");
        account.budget = "{\"currency\":\"USD\",\"daily\":0.001}";
        app.database().saveProvider(account);
        String sessionId = createSession();

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        await("first turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        JSONObject cost = lastTurnUsage(sessionId).getJSONObject("cost");
        assertEquals("(7 + 2) tokens at $1000 per million", 0.009, cost.getDouble("amount"), 1e-9);

        JSONObject refused = awaitResult(post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second")));
        assertFalse(refused.optBoolean("ok"));
        JSONObject budget = refused.getJSONObject("data").getJSONObject("budget");
        assertEquals("daily", budget.getString("period"));
        assertTrue(refused.getJSONObject("data").getBoolean("draftRestored"));
        assertEquals("second", app.database().getSession(sessionId).draft);
        assertEquals(2, app.database().timeline(sessionId).length());

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "second").put("budgetConfirmed", true));
        await("confirmed turn completed", () -> app.database().timeline(sessionId).length() == 4 && "completed".equals(app.database().getSession(sessionId).state));
        await("budget spend in the view", () -> latestView().optJSONArray("providers").optJSONObject(0).optJSONObject("budget").optDouble("spentToday", 0) > 0.01);
    }

    @Test public void aRateLimitBeforeAnyOutputIsRetriedAfterRetryAfter() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (calls.getAndIncrement() == 0) return new MockResponse().setResponseCode(429).setHeader("Retry-After", "0.2").setBody("{\"error\":{\"message\":\"slow down\"}}");
                return sse(SSE_OK);
            }
        });
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "please"));
        await("turn completed after one retry", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        JSONArray rows = app.database().timeline(sessionId);
        assertEquals("mock answer", rows.getJSONObject(1).optString("text"));
        assertFalse(rows.getJSONObject(1).optBoolean("incomplete"));
        assertEquals(2, calls.get());
        assertEquals(1, lastTurnUsage(sessionId).optInt("providerRetries"));
    }

    @Test public void aFullRouteQueuesTheNextTurnUntilASlotFrees() throws Exception {
        ChatDatabase.Provider provider = mockProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        provider.dialect = RouteDialect.parse(OpenAiCompatibleTransport.CHAT_COMPLETIONS, new JSONObject().put("maxConcurrent", 1));
        saveDeployment(provider);
        String first = createSession();
        post("chat.session.saveDraft", new JSONObject().put("localSessionId", first).put("text", "kept")); // a blank Chat with a draft is not dropped
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("second session created", () -> !first.equals(latestView().optString("activeLocalSessionId")));
        String second = latestView().optString("activeLocalSessionId");
        java.util.List<Long> arrivals = Collections.synchronizedList(new ArrayList<>());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                arrivals.add(System.nanoTime());
                return sse(SSE_OK).setBodyDelay(arrivals.size() == 1 ? 1200 : 0, TimeUnit.MILLISECONDS);
            }
        });
        post("chat.session.send", new JSONObject().put("localSessionId", first).put("text", "slow one"));
        await("first request sent", () -> arrivals.size() == 1);
        post("chat.session.send", new JSONObject().put("localSessionId", second).put("text", "waits"));
        await("second turn shows it is queued", () -> {
            JSONArray rows = app.database().timeline(second);
            return rows.length() == 2 && "queued".equals(rows.optJSONObject(1).optString("streamStage"));
        });
        assertEquals("the second request waits for the slot", 1, arrivals.size());
        await("both completed", () -> "completed".equals(app.database().getSession(first).state) && "completed".equals(app.database().getSession(second).state)
                && app.database().timeline(second).length() == 2 && !app.database().timeline(second).optJSONObject(1).optBoolean("streaming"));
        assertEquals(2, arrivals.size());
        assertEquals("mock answer", app.database().timeline(second).getJSONObject(1).optString("text"));
    }

    // ---------- Review fixes: queued stop, kept spend, endpoint evidence, frozen pricing; custom instruction ----------

    @Test public void stoppingAQueuedTurnEndsItWithoutARequestAndFreesNoOneElsesSlot() throws Exception {
        ChatDatabase.Provider provider = mockProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        provider.dialect = RouteDialect.parse(OpenAiCompatibleTransport.CHAT_COMPLETIONS, new JSONObject().put("maxConcurrent", 1));
        saveDeployment(provider);
        String first = createSession();
        post("chat.session.saveDraft", new JSONObject().put("localSessionId", first).put("text", "kept")); // a blank Chat with a draft is not dropped
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("second session created", () -> !first.equals(latestView().optString("activeLocalSessionId")));
        String second = latestView().optString("activeLocalSessionId");
        java.util.List<String> bodies = Collections.synchronizedList(new ArrayList<>());
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                bodies.add(request.getBody().readUtf8());
                return sse(SSE_OK).setBodyDelay(bodies.size() == 1 ? 1500 : 0, TimeUnit.MILLISECONDS);
            }
        });
        post("chat.session.send", new JSONObject().put("localSessionId", first).put("text", "slow one"));
        await("first request sent", () -> bodies.size() == 1);
        post("chat.session.send", new JSONObject().put("localSessionId", second).put("text", "never sent"));
        await("second queued", () -> {
            JSONArray rows = app.database().timeline(second);
            return rows.length() == 2 && "queued".equals(rows.optJSONObject(1).optString("streamStage"));
        });
        post("chat.session.interrupt", new JSONObject().put("localSessionId", second));
        await("stopped while queued settles at once", () -> {
            JSONObject active = activeSession(latestView());
            return active != null && second.equals(active.optString("localSessionId")) && active.optBoolean("canSend") && !active.optBoolean("canInterrupt");
        });
        assertEquals("the first turn is still the only request", 1, bodies.size());
        await("first completes", () -> "completed".equals(app.database().getSession(first).state) && app.database().timeline(first).length() == 2);
        Thread.sleep(300);
        assertEquals("the stopped turn was never sent after the slot freed", 1, bodies.size());
        for (String body : bodies) assertFalse(body.contains("never sent"));

        post("chat.session.send", new JSONObject().put("localSessionId", second).put("text", "now it goes"));
        await("the route slot is free again", () -> bodies.size() == 2 && "completed".equals(app.database().getSession(second).state));
    }

    @Test public void spendOfRewoundTurnsStillCountsAgainstTheBudget() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.pricing = "{\"currency\":\"USD\",\"input\":1000,\"output\":1000}";
        app.database().saveModelProfile(profile);
        ChatDatabase.Provider account = app.database().getProvider("mock-provider");
        account.budget = "{\"currency\":\"USD\",\"daily\":0.001}";
        app.database().saveProvider(account);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "first"));
        await("first turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        String since = ProviderConfigService.budgetWindows(java.time.ZonedDateTime.now())[0];
        double spent = app.database().spendSince("mock-provider", since).optDouble("USD");
        assertEquals(0.009, spent, 1e-9);

        assertTrue(awaitResult(post("chat.message.rollback", new JSONObject().put("localSessionId", sessionId).put("localSeq", 1))).optBoolean("ok"));
        assertEquals("the turn record is gone", 0, app.database().timeline(sessionId).length());
        assertEquals("its spend is not", spent, app.database().spendSince("mock-provider", since).optDouble("USD"), 1e-9);
        JSONObject refused = awaitResult(post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "again")));
        assertNotNull("the budget still applies after the rewind", refused.optJSONObject("data").optJSONObject("budget"));

        assertTrue(awaitResult(post("chat.session.delete", new JSONObject().put("localSessionId", sessionId))).optBoolean("ok"));
        assertEquals("deleting the Chat keeps its spend too", spent, app.database().spendSince("mock-provider", since).optDouble("USD"), 1e-9);
    }

    @Test public void pointingARouteAtAnotherEndpointDropsTheOldEndpointsEvidence() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath().endsWith("/models")) return new MockResponse().setBody("{\"data\":[{\"id\":\"mock-model\",\"context_length\":64000}]}");
                return sse(SSE_OK);
            }
        });
        assertTrue(awaitResult(post("provider.key.verify", new JSONObject().put("providerId", "mock-provider"))).optBoolean("ok"));
        ChatDatabase.ModelProfile listed = app.database().firstModelProfile("mock-provider");
        assertEquals(64_000, listed.contextTokens);
        assertTrue(awaitResult(post("model.probe", new JSONObject().put("modelProfileId", listed.id))).optBoolean("ok"));
        ChatDatabase.Route route = app.database().getRoute(listed.routeId);

        JSONObject moved = new JSONObject().put("routeId", route.id).put("label", route.label).put("protocol", route.protocol)
                .put("baseUrl", server.url("/v2").toString()).put("authMode", route.authMode);
        JSONObject saved = awaitResult(post("provider.save", new JSONObject().put("providerId", "mock-provider").put("routes", new JSONArray().put(moved))));
        assertTrue(saved.toString(), saved.optBoolean("ok"));
        ChatDatabase.ModelProfile after = app.database().getModelProfile(listed.id);
        assertEquals("no discovered limit from the old endpoint", "{}", after.discovered);
        assertEquals("no probe result from the old endpoint", "{}", after.probe);
        assertNotEquals("discovered", DeploymentResolver.json(after.sources).optString("contextTokens"));
        JSONObject preview = awaitResult(post("model.preview", new JSONObject().put("providerId", "mock-provider").put("routeId", route.id).put("modelId", "mock-model"))).getJSONObject("data");
        for (int i = 0; i < preview.getJSONArray("fields").length(); i++) {
            assertNotEquals("the cached list of the old endpoint is not reused", "discovered", preview.getJSONArray("fields").getJSONObject(i).optString("source"));
        }
    }

    @Test public void aTurnOnAProfileSwitchedToAnotherModelIsNotRelabelledOrRepriced() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.modelId = "different-model"; profile.displayName = "Different";
        profile.pricing = "{\"currency\":\"USD\",\"input\":1000,\"output\":1000}";
        app.database().saveModelProfile(profile);
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hello"));
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertTrue("the frozen model is still the one called", request.getBody().readUtf8().contains("\"mock-model\""));
        JSONObject usage = lastTurnUsage(sessionId);
        assertFalse("the other model's prices are not used", usage.has("cost"));
        assertEquals("mock-model", app.database().timeline(sessionId).getJSONObject(1).optString("modelName"));
        assertEquals("mock-model", activeSession(latestView()).optString("modelName"));
        await("the session is told the profile changed", () -> activeSession(latestView()).optBoolean("profileChanged"));
    }

    @Test public void theGlobalCustomInstructionReachesTheModelAndTheTurnRecord() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        assertTrue(awaitResult(post("instruction.global.set", new JSONObject().put("text", "  Always answer in exactly three lines.  "))).optBoolean("ok"));
        await("instruction in the view", () -> "Always answer in exactly three lines.".equals(latestView().optJSONObject("ui").optString("customInstruction")));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hello"));
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        String body = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
        assertTrue(body, body.contains("Always answer in exactly three lines."));
        String turnId = app.database().timeline(sessionId).getJSONObject(1).optString("turnId");
        JSONObject trace = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).getJSONObject("data");
        assertEquals("Always answer in exactly three lines.", trace.getJSONObject("config").optString("customInstruction"));

        assertTrue(awaitResult(post("instruction.global.set", new JSONObject().put("text", ""))).optBoolean("ok"));
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "again"));
        await("second turn completed", () -> app.database().timeline(sessionId).length() == 4 && "completed".equals(app.database().getSession(sessionId).state));
        assertFalse(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8().contains("three lines"));
        JSONObject tooLong = awaitResult(post("instruction.global.set", new JSONObject().put("text", new String(new char[TurnConfig.MAX_CUSTOM_INSTRUCTION_CHARS + 1]).replace('\0', 'x'))));
        assertFalse(tooLong.optBoolean("ok"));
    }

    @Test public void switchedOffHelpersDoNotRunAndTheTurnRecordsWhichWereOff() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) { calls.incrementAndGet(); return completion("plain answer", 7, 2); }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        ChatDatabase.ModelProfile profile = app.database().firstModelProfile("mock-provider");
        profile.harnessPreset = HarnessProfile.REINFORCED; // Smart would route and verify this turn
        app.database().saveModelProfile(profile);
        assertTrue(awaitResult(post("helper.switch.set", new JSONObject().put("helper", "verify").put("enabled", false))).optBoolean("ok"));
        assertTrue(awaitResult(post("helper.switch.set", new JSONObject().put("helper", "router").put("enabled", false))).optBoolean("ok"));
        assertFalse(awaitResult(post("helper.switch.set", new JSONObject().put("helper", "anything").put("enabled", false))).optBoolean("ok"));
        await("switches in the view", () -> "[\"router\",\"verify\"]".equals(String.valueOf(latestView().optJSONObject("ui").optJSONArray("helpersOff"))));
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider")
                .put("contextMode", ContextMode.SMART).put("webMode", ChatTurnOptions.WEB_OFF));
        await("smart session created", () -> activeSession(latestView()) != null);
        String sessionId = latestView().optString("activeLocalSessionId");
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "Explain why the sky is blue."));
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);
        assertEquals("only the main call: no Router, no verifier", 1, calls.get());

        String turnId = app.database().timeline(sessionId).getJSONObject(1).optString("turnId");
        JSONObject trace = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).getJSONObject("data");
        assertEquals("[\"router\",\"verify\"]", trace.getJSONObject("config").getJSONArray("helpersOff").toString());
        JSONArray spans = trace.getJSONArray("spans");
        for (String stage : new String[]{TurnTrace.ROUTE, TurnTrace.VERIFY}) {
            JSONObject span = null;
            for (int i = 0; i < spans.length(); i++) if (stage.equals(spans.getJSONObject(i).optString("stage"))) span = spans.getJSONObject(i);
            assertNotNull(stage, span);
            assertEquals(stage, HelperSwitches.USER_OFF, span.optString("reason"));
        }
        JSONObject stats = awaitResult(post("stats.get", new JSONObject().put("days", 7).put("groupBy", "helpers"))).getJSONObject("data");
        assertEquals("router+verify", stats.getJSONArray("rows").getJSONObject(0).getJSONObject("group").optString("helpers"));

        assertTrue(awaitResult(post("helper.switch.set", new JSONObject().put("helper", "router").put("enabled", true))).optBoolean("ok"));
        assertEquals("[\"verify\"]", app.database().setting(HelperSwitches.SETTING_KEY, ""));
    }

    @Test public void aRetriedTurnIsArchivedLinkedFromItsRetryAndStillCounted() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        assertTrue(awaitResult(post("instruction.global.set", new JSONObject().put("text", "private preference text"))).optBoolean("ok"));
        String sessionId = createSession();
        failFirstRequest();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "the question"));
        await("turn failed", () -> "failed".equals(app.database().getSession(sessionId).state));
        await("retry offered", () -> { JSONObject a = activeSession(latestView()); return a != null && a.optInt("retryLocalSeq") == 1 && a.optBoolean("canSend"); });
        post("chat.turn.retry", new JSONObject().put("localSessionId", sessionId));
        await("retried turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 2);

        String turnId = app.database().timeline(sessionId).getJSONObject(1).optString("turnId");
        JSONObject trace = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).getJSONObject("data");
        assertEquals(2, trace.getJSONObject("config").optInt("attempt"));
        JSONObject previous = trace.getJSONObject("previousAttempt");
        assertEquals(trace.getJSONObject("config").optString("retryOf"), previous.optString("turnId"));
        assertEquals("failed", previous.optString("status"));
        assertEquals(ChatDatabase.ARCHIVE_RETRY, previous.optString("reason"));

        try (android.database.Cursor c = app.database().getReadableDatabase().rawQuery("SELECT config,spans FROM turn_archive WHERE turn_id=?", new String[]{previous.optString("turnId")})) {
            assertTrue(c.moveToFirst());
            JSONObject config = new JSONObject(c.getString(0));
            assertFalse("the instruction text is not archived", c.getString(0).contains("private preference text"));
            assertEquals("private preference text".length(), config.optInt("customInstructionChars"));
            JSONArray spans = new JSONArray(c.getString(1));
            assertTrue(spans.length() > 0);
            for (int i = 0; i < spans.length(); i++) {
                JSONObject detail = spans.getJSONObject(i).getJSONObject("detail");
                for (java.util.Iterator<String> keys = detail.keys(); keys.hasNext(); ) assertFalse("no free text in archived details", detail.get(keys.next()) instanceof String);
            }
        }
        JSONObject stats = awaitResult(post("stats.get", new JSONObject().put("days", 7).put("groupBy", "model"))).getJSONObject("data");
        JSONObject row = stats.getJSONArray("rows").getJSONObject(0);
        assertEquals(2, row.getInt("turns"));
        assertEquals(1, row.getInt("failed"));
        assertEquals(1, row.getInt("archived"));
        JSONObject live = awaitResult(post("stats.get", new JSONObject().put("days", 7).put("groupBy", "model").put("includeArchived", false))).getJSONObject("data");
        assertEquals(1, live.getJSONArray("rows").getJSONObject(0).getInt("turns"));
        assertEquals(0, live.getJSONArray("rows").getJSONObject(0).getInt("archived"));
    }

    @Test public void aNewChatIsListedOnlyOnceItHasAMessageOrADraftAndUnusedOnesDoNotPileUp() throws Exception {
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String first = createSession();
        assertEquals("a blank Chat is not listed", 0, latestView().optJSONArray("sessions").length());
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("second new Chat active", () -> !first.equals(latestView().optString("activeLocalSessionId")));
        String second = latestView().optString("activeLocalSessionId");
        assertNull("the unused blank Chat was replaced, not kept", app.database().getSession(first));
        assertEquals(1, app.database().listSessions().size());

        post("chat.session.saveDraft", new JSONObject().put("localSessionId", second).put("text", "half a thought"));
        post("chat.session.create", new JSONObject().put("providerId", "mock-provider"));
        await("third new Chat active", () -> { String id = latestView().optString("activeLocalSessionId"); return !id.isEmpty() && !second.equals(id); });
        String third = latestView().optString("activeLocalSessionId");
        JSONArray listed = latestView().optJSONArray("sessions");
        assertEquals("a blank Chat with an unsent draft stays, listed as that draft", 1, listed.length());
        assertEquals(second, listed.getJSONObject(0).optString("localSessionId"));
        assertTrue(listed.getJSONObject(0).optBoolean("blank"));
        assertEquals("half a thought", listed.getJSONObject(0).optString("draft"));

        post("chat.session.open", new JSONObject().put("localSessionId", second));
        await("draft Chat opened", () -> second.equals(latestView().optString("activeLocalSessionId")));
        assertNull("leaving an unused blank Chat deletes it", app.database().getSession(third));
    }

    @Test public void theFirstMessageTitlesTheChatAndTheFirstAnswerRenamesItOnce() throws Exception {
        SessionTitles.automatic = true;
        AtomicInteger namingCalls = new AtomicInteger();
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                if (body.contains("You name chat conversations")) { namingCalls.incrementAndGet(); return completion("标题：“天空为何是蓝色”。", 30, 6); }
                return completion("Rayleigh scattering.", 12, 4);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        assertEquals(SessionTitles.DEFAULT_TITLE, app.database().getSession(sessionId).title);
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "为什么天空是蓝色的？请从光的散射讲起，越详细越好"));
        await("named by the model", () -> "天空为何是蓝色".equals(app.database().getSession(sessionId).title));
        assertEquals(1, namingCalls.get());
        await("the list shows the new title", () -> "天空为何是蓝色".equals(latestView().optJSONArray("sessions").optJSONObject(0).optString("title")));

        String turnId = app.database().timeline(sessionId).getJSONObject(1).optString("turnId");
        JSONObject trace = awaitResult(post("turn.trace.get", new JSONObject().put("localSessionId", sessionId).put("turnId", turnId))).getJSONObject("data");
        JSONObject span = null;
        for (int i = 0; i < trace.getJSONArray("spans").length(); i++) if (SessionTitles.STAGE.equals(trace.getJSONArray("spans").getJSONObject(i).optString("stage"))) span = trace.getJSONArray("spans").getJSONObject(i);
        assertNotNull("the naming call is a stage of the turn it named", span);
        assertTrue(span.getJSONObject("detail").optBoolean("applied"));
        assertEquals(36, span.getJSONObject("usage").optInt("inputTokens") + span.getJSONObject("usage").optInt("outputTokens"));
        assertTrue("its tokens count as helper usage of the turn", trace.getJSONObject("usage").optInt("auxiliaryOutputTokens") >= 6);

        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "再说说日落"));
        await("second turn completed", () -> "completed".equals(app.database().getSession(sessionId).state) && app.database().timeline(sessionId).length() == 4);
        Thread.sleep(300);
        assertEquals("a model-written title is not automatic any more", 1, namingCalls.get());
    }

    @Test public void aRenameMadeWhileNamingRunsIsNotOverwrittenButRenamingOnRequestIs() throws Exception {
        SessionTitles.automatic = true;
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                String body = request.getBody().clone().readUtf8();
                if (body.contains("You name chat conversations")) return completion("模型起的标题", 30, 6).setBodyDelay(800, TimeUnit.MILLISECONDS);
                return completion("answer", 12, 4);
            }
        });
        configureProvider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        String sessionId = createSession();
        post("chat.session.send", new JSONObject().put("localSessionId", sessionId).put("text", "hello there"));
        await("placeholder title", () -> "hello there".equals(app.database().getSession(sessionId).title));
        await("turn completed", () -> "completed".equals(app.database().getSession(sessionId).state));
        post("chat.session.rename", new JSONObject().put("localSessionId", sessionId).put("title", "我的标题"));
        String turnId = app.database().timeline(sessionId).getJSONObject(1).optString("turnId");
        await("naming finished", () -> app.database().turnTrace(sessionId, turnId).toString().contains("\"stage\":\"title\""));
        assertEquals("the user's rename wins", "我的标题", app.database().getSession(sessionId).title);

        JSONObject manual = awaitResult(post("chat.session.title.auto", new JSONObject().put("localSessionId", sessionId)));
        assertTrue(manual.toString(), manual.optBoolean("ok"));
        assertEquals("asking for a title replaces it", "模型起的标题", app.database().getSession(sessionId).title);
    }
}
