package com.moyu.chat;

import static org.junit.Assert.*;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** What a route's dialect, headers and safety rules put on (and keep off) the wire. */
public class RouteWireTest {
    private static final String CHAT = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
    private static final String RESPONSES = OpenAiCompatibleTransport.RESPONSES;
    private static final String MESSAGES = AnthropicMessagesTransport.PROTOCOL;
    private static final String DONE = "data: [DONE]\n\n";
    private static final String MESSAGE_STOP = "data: {\"type\":\"message_stop\"}\n\n";

    private MockWebServer server;
    private final OpenAiCompatibleTransport openAi = new OpenAiCompatibleTransport();
    private final AnthropicMessagesTransport anthropic = new AnthropicMessagesTransport();

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { openAi.close(); anthropic.close(); server.shutdown(); }

    private static final class Sink implements ChatTransport.Sink {
        final CountDownLatch terminal = new CountDownLatch(1);
        final StringBuffer reasoning = new StringBuffer();
        final JSONArray continuation = new JSONArray();
        volatile String errorCode, errorSummary; volatile boolean retryable, completed;
        @Override public void onTextDelta(String delta) { }
        @Override public void onReasoningDelta(String delta) { reasoning.append(delta); }
        @Override public void onProviderContinuation(JSONObject state) { continuation.put(state); }
        @Override public void onUsage(Integer inputTokens, Integer outputTokens) { }
        @Override public void onCompleted() { completed = true; terminal.countDown(); }
        @Override public void onError(String code, String summary, boolean canRetry) { errorCode = code; errorSummary = summary; retryable = canRetry; terminal.countDown(); }
        void await() throws InterruptedException { assertTrue("no terminal callback", terminal.await(10, TimeUnit.SECONDS)); }
    }

    private ChatDatabase.Provider provider(String protocol) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.displayName = "p"; p.protocol = protocol; p.authMode = ProviderAuth.defaultForProtocol(protocol);
        p.baseUrl = MESSAGES.equals(protocol) ? server.url("/").toString() : server.url("/v1").toString();
        p.model = "m"; p.contextTokens = 32_000; p.maxOutputTokens = 1_024; p.sendOutputCap = true;
        return p;
    }

    private static CanonicalPrompt prompt(JSONArray messages) {
        return new CanonicalPrompt("SYSTEM", messages, false, 10, 0, messages.length(), 0, null, true);
    }

    private static CanonicalPrompt hi() throws Exception { return prompt(new JSONArray().put(new JSONObject().put("role", "user").put("text", "hi"))); }

    private Sink run(ChatDatabase.Provider p, CanonicalPrompt prompt) throws Exception {
        Sink sink = new Sink();
        if (MESSAGES.equals(p.protocol)) anthropic.stream(p, "sk-test-key", "m", ReasoningPolicy.DEFAULT, prompt, ChatTurnOptions.NONE, sink);
        else openAi.stream(p, "sk-test-key", "m", ReasoningPolicy.DEFAULT, prompt, ChatTurnOptions.NONE, sink);
        sink.await();
        return sink;
    }

    private RecordedRequest taken() throws Exception {
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    // ---------- Chat Completions dialect ----------

    @Test public void chatOmitsAnUndeclaredOutputCapAndAsksForStreamUsage() throws Exception {
        server.enqueue(new MockResponse().setBody(DONE));
        ChatDatabase.Provider p = provider(CHAT); p.sendOutputCap = false;
        run(p, hi());
        JSONObject body = new JSONObject(taken().getBody().readUtf8());
        assertFalse(body.has("max_tokens")); assertFalse(body.has("max_completion_tokens"));
        assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"));
    }

    @Test public void chatDialectNamesTheOutputCapAndCarriesTheThinkingSwitch() throws Exception {
        server.enqueue(new MockResponse().setBody(DONE));
        ChatDatabase.Provider p = provider(CHAT);
        p.dialect = RouteDialect.parse(CHAT, "{\"outputCap\":\"max_completion_tokens\",\"streamUsage\":false,\"thinkingParam\":\"thinking-type\"}");
        p.thinkingMode = ReasoningPolicy.THINKING_DISABLED;
        run(p, hi());
        JSONObject body = new JSONObject(taken().getBody().readUtf8());
        assertEquals(1_024, body.getInt("max_completion_tokens"));
        assertFalse(body.has("max_tokens"));
        assertFalse(body.has("stream_options"));
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"));
    }

    @Test public void chatThinkingSwitchIsRefusedWhenTheRouteDoesNotDeclareIt() throws Exception {
        ChatDatabase.Provider p = provider(CHAT);
        p.thinkingMode = ReasoningPolicy.THINKING_ENABLED;
        Sink sink = run(p, hi());
        assertEquals("bad_provider_config", sink.errorCode);
        assertEquals("nothing is sent with a field the route cannot carry", 0, server.getRequestCount());
    }

    @Test public void chatReadsBothReasoningFieldsAndKeepsTheRoundsReasoningForReplay() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"reasoning\":\"think-\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"more\"}}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"answer\"},\"finish_reason\":\"stop\"}]}\n\n" + DONE));
        Sink sink = run(provider(CHAT), hi());
        assertTrue(sink.completed);
        assertEquals("think-more", sink.reasoning.toString());
        assertEquals(1, sink.continuation.length());
        JSONObject state = sink.continuation.getJSONObject(0);
        assertEquals(CHAT, state.getString("protocol"));
        assertEquals(OpenAiCompatibleTransport.REASONING_ITEM, state.getJSONObject("item").getString("type"));
        assertEquals("think-more", state.getJSONObject("item").getString("text"));
    }

    @Test public void chatReplaysAssistantReasoningOnlyWhenTheRouteRequiresIt() throws Exception {
        JSONObject kept = new JSONObject().put("protocol", CHAT).put("items", new JSONArray()
                .put(new JSONObject().put("type", OpenAiCompatibleTransport.REASONING_ITEM).put("text", "prior thought")));
        JSONArray history = new JSONArray()
                .put(new JSONObject().put("role", "user").put("text", "q1"))
                .put(new JSONObject().put("role", "assistant").put("text", "a1").put("_providerContinuation", kept))
                .put(new JSONObject().put("role", "user").put("text", "q2"));
        server.enqueue(new MockResponse().setBody(DONE));
        server.enqueue(new MockResponse().setBody(DONE));
        ChatDatabase.Provider always = provider(CHAT);
        always.dialect = RouteDialect.parse(CHAT, "{\"reasoningReplay\":\"always\"}");
        run(always, prompt(history));
        JSONObject assistant = new JSONObject(taken().getBody().readUtf8()).getJSONArray("messages").getJSONObject(2);
        assertEquals("assistant", assistant.getString("role"));
        assertEquals("prior thought", assistant.getString("reasoning_content"));

        ChatDatabase.Provider withTools = provider(CHAT);
        withTools.dialect = RouteDialect.parse(CHAT, "{\"reasoningReplay\":\"with-tools\"}");
        run(withTools, prompt(history));
        assertFalse("a request without tools does not need it", new JSONObject(taken().getBody().readUtf8())
                .getJSONArray("messages").getJSONObject(2).has("reasoning_content"));
    }

    @Test public void chatToolRoundsCarryTheirReasoningWhenTheRouteRequiresIt() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\n" + DONE));
        JSONArray toolHistory = new JSONArray().put(new JSONObject().put("round", 1).put("id", "call_1")
                .put("name", "web_search").put("arguments", new JSONObject().put("query", "q")).put("result", "found"));
        JSONArray roundHistory = new JSONArray().put(new JSONObject().put("round", 1).put("item",
                new JSONObject().put("type", OpenAiCompatibleTransport.REASONING_ITEM).put("text", "plan the search")));
        ChatTurnOptions roundTwo = new ChatTurnOptions("", ChatTurnOptions.WEB_OFF, ClientToolRuntime.OPENAI_CHAT,
                ClientToolRuntime.WEB_SEARCH, toolHistory, roundHistory);
        ChatDatabase.Provider p = provider(CHAT);
        p.dialect = RouteDialect.parse(CHAT, "{\"reasoningReplay\":\"with-tools\"}");
        Sink sink = new Sink();
        openAi.stream(p, "sk-test-key", "m", ReasoningPolicy.DEFAULT, hi(), roundTwo, sink);
        sink.await();
        JSONArray messages = new JSONObject(taken().getBody().readUtf8()).getJSONArray("messages");
        JSONObject call = null;
        for (int i = 0; i < messages.length(); i++) if (messages.getJSONObject(i).has("tool_calls")) call = messages.getJSONObject(i);
        assertNotNull(call);
        assertEquals("DeepSeek-style routes get the round's reasoning back with its tool call", "plan the search", call.getString("reasoning_content"));
    }

    // ---------- Responses and Messages ----------

    @Test public void responsesDoesNotStoreUnlessTheRouteOptsIn() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        ChatDatabase.Provider p = provider(RESPONSES); p.sendOutputCap = false;
        run(p, hi());
        JSONObject first = new JSONObject(taken().getBody().readUtf8());
        assertFalse(first.getBoolean("store"));
        assertFalse(first.has("max_output_tokens"));
        p.dialect = RouteDialect.parse(RESPONSES, "{\"store\":true}"); p.sendOutputCap = true;
        run(p, hi());
        JSONObject second = new JSONObject(taken().getBody().readUtf8());
        assertTrue(second.getBoolean("store"));
        assertEquals(1_024, second.getInt("max_output_tokens"));
    }

    @Test public void serviceTierAndExtraHeadersReachTheWireWithoutTouchingAuth() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        ChatDatabase.Provider p = provider(RESPONSES);
        p.serviceTier = "flex";
        p.headers = RouteHeaders.normalize(new JSONObject().put("OpenAI-Project", "proj_1"));
        run(p, hi());
        RecordedRequest request = taken();
        assertEquals("proj_1", request.getHeader("OpenAI-Project"));
        assertEquals("Bearer sk-test-key", request.getHeader("Authorization"));
        assertEquals("flex", new JSONObject(request.getBody().readUtf8()).getString("service_tier"));
    }

    @Test public void anthropicAutoCacheAddsOneTopLevelMarkerOnlyWhenDeclared() throws Exception {
        server.enqueue(new MockResponse().setBody(MESSAGE_STOP));
        server.enqueue(new MockResponse().setBody(MESSAGE_STOP));
        ChatDatabase.Provider p = provider(MESSAGES);
        run(p, hi());
        RecordedRequest plain = taken();
        assertEquals("/v1/messages", plain.getPath());
        assertFalse(new JSONObject(plain.getBody().readUtf8()).has("cache_control"));
        p.dialect = RouteDialect.parse(MESSAGES, "{\"autoCache\":true}");
        p.serviceTier = "standard_only";
        p.headers = RouteHeaders.normalize(new JSONObject().put("anthropic-beta", "inline-tools-2026-09-15"));
        run(p, hi());
        RecordedRequest cached = taken();
        JSONObject body = new JSONObject(cached.getBody().readUtf8());
        assertEquals("ephemeral", body.getJSONObject("cache_control").getString("type"));
        assertEquals("standard_only", body.getString("service_tier"));
        assertEquals("inline-tools-2026-09-15", cached.getHeader("anthropic-beta"));
        assertEquals("2023-06-01", cached.getHeader("anthropic-version"));
    }

    // ---------- Safety ----------

    @Test public void redirectsAreNeverFollowedSoKeysAndBodiesStayWithTheConfiguredHost() throws Exception {
        String elsewhere = server.url("/elsewhere").toString();
        server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", elsewhere));
        server.enqueue(new MockResponse().setResponseCode(308).setHeader("Location", elsewhere));
        ChatDatabase.Provider chat = provider(CHAT); chat.authMode = ProviderAuth.X_API_KEY;
        Sink first = run(chat, hi());
        assertEquals("http_307", first.errorCode);
        Sink second = run(provider(MESSAGES), hi());
        assertEquals("http_308", second.errorCode);
        assertEquals("the redirect target never received a request", 2, server.getRequestCount());
    }

    @Test public void httpFailuresAreClassifiedWithTheProvidersRequestId() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setHeader("x-request-id", "req_123")
                .setBody("{\"error\":{\"message\":\"Incorrect API key provided\"}}"));
        Sink auth = run(provider(CHAT), hi());
        assertEquals("http_401", auth.errorCode);
        assertTrue(auth.errorSummary, auth.errorSummary.contains("API Key 无效"));
        assertTrue(auth.errorSummary.contains("req_123"));
        assertFalse(auth.retryable);

        server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"error\":{\"code\":\"unsupported_country_region_territory\",\"message\":\"Country, region, or territory not supported\"}}"));
        assertTrue(run(provider(RESPONSES), hi()).errorSummary.contains("代理"));

        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"You exceeded your current quota\"}}"));
        Sink quota = run(provider(CHAT), hi());
        assertTrue(quota.errorSummary.contains("额度"));
        assertFalse("an empty balance does not get better by retrying", quota.retryable);

        server.enqueue(new MockResponse().setResponseCode(529).setHeader("request-id", "req_a")
                .setBody("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}"));
        Sink overloaded = run(provider(MESSAGES), hi());
        assertTrue(overloaded.errorSummary.contains("过载"));
        assertTrue(overloaded.errorSummary.contains("req_a"));
        assertTrue(overloaded.retryable);
    }

    @Test public void networkFailuresPointAtTheNetworkOrProxy() throws Exception {
        ChatDatabase.Provider p = provider(CHAT);
        p.baseUrl = "https://unresolvable.invalid/v1";
        Sink sink = run(p, hi());
        assertEquals("network_error", sink.errorCode);
        assertTrue(sink.errorSummary, sink.errorSummary.contains("代理"));
        assertFalse(sink.errorSummary.contains("sk-test-key"));
    }
}
