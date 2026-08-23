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

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Streaming parser coverage against a local mock SSE server for all three v0.0.1 protocols:
 * completed / error / cancel terminal mapping and single-terminal guarantees.
 */
public class TransportMockServerTest {
    private MockWebServer server;
    private final OpenAiCompatibleTransport openAi = new OpenAiCompatibleTransport();
    private final AnthropicMessagesTransport anthropic = new AnthropicMessagesTransport();

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception {
        openAi.close();
        anthropic.close();
        try { server.shutdown(); }
        catch (IOException tolerated) {
            // A deliberately hung streaming response (cancel test) can outlive shutdown's wait.
        }
    }

    private static final class RecordingSink implements ChatTransport.Sink {
        final StringBuffer text = new StringBuffer();
        final AtomicInteger terminals = new AtomicInteger();
        volatile int inputTokens = -1, outputTokens = -1;
        volatile String errorCode, errorSummary; volatile Boolean retryable; volatile boolean completed;
        final CountDownLatch terminal = new CountDownLatch(1);
        @Override public void onTextDelta(String delta) { text.append(delta); }
        @Override public void onUsage(Integer in, Integer out) { if (in != null) inputTokens = in; if (out != null) outputTokens = out; }
        @Override public void onCompleted() { if (terminals.incrementAndGet() == 1) completed = true; terminal.countDown(); }
        @Override public void onError(String code, String summary, boolean canRetry) {
            if (terminals.incrementAndGet() == 1) { errorCode = code; errorSummary = summary; retryable = canRetry; }
            terminal.countDown();
        }
        void awaitTerminal() throws InterruptedException { assertTrue("no terminal callback", terminal.await(20, TimeUnit.SECONDS)); }
    }

    private ChatDatabase.Provider provider(String protocol) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "test"; p.displayName = "test"; p.protocol = protocol;
        p.baseUrl = server.url("/v1").toString(); p.model = "test-model";
        p.contextTokens = 32_000; p.maxOutputTokens = 1_024;
        return p;
    }

    private static CanonicalPrompt prompt(String... pairs) throws Exception {
        JSONArray messages = new JSONArray();
        for (int i = 0; i < pairs.length; i += 2) {
            messages.put(new JSONObject().put("role", pairs[i]).put("text", pairs[i + 1]));
        }
        return new CanonicalPrompt("SYSTEM-INSTRUCTIONS", messages, false, 10, 0, messages.length());
    }

    private String bodyOf() throws Exception {
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request);
        return request.getBody().readUtf8();
    }

    // ---------- OpenAI Chat Completions ----------

    @Test public void chatCompletionsAssemblesDeltasAndCompletesOnDone() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":2}}\n\n" +
                "data: [DONE]\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("你好", sink.text.toString());
        assertTrue(sink.completed);
        assertEquals(1, sink.terminals.get());
        assertEquals(11, sink.inputTokens);
        assertEquals(2, sink.outputTokens);
        JSONObject body = new JSONObject(bodyOf());
        assertEquals("test-model", body.optString("model"));
        assertTrue(body.optBoolean("stream"));
        assertEquals("system", body.getJSONArray("messages").getJSONObject(0).optString("role"));
        assertEquals("SYSTEM-INSTRUCTIONS", body.getJSONArray("messages").getJSONObject(0).optString("content"));
        assertEquals("user", body.getJSONArray("messages").getJSONObject(1).optString("role"));
    }

    @Test public void chatCompletionsErrorEventIsTerminalAndSingle() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"par\"}}]}\n\n" +
                "data: {\"error\":{\"code\":\"invalid_api_key\",\"message\":\"bad key\"}}\n\n" +
                "data: [DONE]\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("invalid_api_key", sink.errorCode);
        assertEquals(1, sink.terminals.get());
        assertFalse(sink.completed);
    }

    @Test public void chatCompletionsDoneStopsParsingEarly() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: [DONE]\n\n" +
                "data: {\"error\":{\"code\":\"late\",\"message\":\"after done\"}}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertTrue(sink.completed);
        assertEquals(1, sink.terminals.get());
        assertNull(sink.errorCode);
    }

    @Test public void streamEndWithoutDoneMarkerStillCompletes() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertTrue(sink.completed);
        assertEquals("x", sink.text.toString());
    }

    // ---------- OpenAI Responses ----------

    @Test public void responsesAssemblesDeltasAndCompletesWithUsage() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}\n\n" +
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\" world\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":21,\"output_tokens\":7}}}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("Hello world", sink.text.toString());
        assertTrue(sink.completed);
        assertEquals(21, sink.inputTokens);
        assertEquals(7, sink.outputTokens);
        JSONObject body = new JSONObject(bodyOf());
        assertEquals("SYSTEM-INSTRUCTIONS", body.optString("instructions"));
        assertEquals("user", body.getJSONArray("input").getJSONObject(0).optString("role"));
        assertEquals("hi", body.getJSONArray("input").getJSONObject(0).optString("content"));
        assertEquals(1_024, body.optInt("max_output_tokens"));
        assertTrue(body.optBoolean("stream"));
    }

    @Test public void responsesFailedMapsToSingleTerminalError() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}\n\n" +
                "data: {\"type\":\"response.completed\"}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("server_error", sink.errorCode);
        assertTrue(sink.retryable);
        assertEquals(1, sink.terminals.get());
    }

    @Test public void responsesIncompleteMapsToError() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("response_incomplete", sink.errorCode);
        assertTrue(sink.errorSummary.contains("max_output_tokens"));
    }

    @Test public void responsesStreamErrorEventMapsToError() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"error\",\"error\":{\"code\":\"context_length_exceeded\",\"message\":\"too long\"}}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("context_length_exceeded", sink.errorCode);
        assertFalse(sink.retryable);
    }

    // ---------- Anthropic Messages ----------

    @Test public void anthropicAssemblesStreamAndCompletesOnMessageStop() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":31,\"output_tokens\":1}}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"!\"}}\n\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"output_tokens\":9}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n" +
                "data: {\"type\":\"error\",\"error\":{\"type\":\"late_error\"}}\n\n"));
        RecordingSink sink = new RecordingSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "claude-test", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("你好!", sink.text.toString());
        assertTrue(sink.completed);
        assertEquals(1, sink.terminals.get());
        assertEquals(31, sink.inputTokens);
        assertEquals(9, sink.outputTokens);
        JSONObject body = new JSONObject(bodyOf());
        assertEquals("SYSTEM-INSTRUCTIONS", body.optString("system"));
        assertEquals("claude-test", body.optString("model"));
        assertTrue(body.optInt("max_tokens") > 0);
        assertTrue(body.optBoolean("stream"));
    }

    @Test public void anthropicErrorEventMapsToError() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"overloaded\"}}\n\n"));
        RecordingSink sink = new RecordingSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "claude-test", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("overloaded_error", sink.errorCode);
        assertTrue(sink.retryable);
    }

    @Test public void anthropicDropsLeadingAssistantAndNeverPromotesHistoryToSystem() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"));
        RecordingSink sink = new RecordingSink();
        CanonicalPrompt p = prompt(
                "assistant", "A1-lead",
                "user", "U1",
                "assistant", "A2",
                "assistant", "A3",
                "user", "U2");
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "claude-test", p, sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        // Leading assistant run is dropped, never promoted into system authority.
        JSONArray messages = body.getJSONArray("messages");
        assertEquals("user", messages.getJSONObject(0).optString("role"));
        assertEquals("U1", messages.getJSONObject(0).optString("content"));
        assertEquals("assistant", messages.getJSONObject(1).optString("role"));
        assertEquals("A2", messages.getJSONObject(1).optString("content"));
        // Consecutive same-role turns stay as-is: the official API combines them server-side.
        assertEquals("assistant", messages.getJSONObject(2).optString("role"));
        assertEquals("A3", messages.getJSONObject(2).optString("content"));
        assertEquals("user", messages.getJSONObject(3).optString("role"));
        assertEquals("U2", messages.getJSONObject(3).optString("content"));
        assertEquals("SYSTEM-INSTRUCTIONS", body.optString("system"));
        assertFalse(body.optString("system").contains("A1-lead"));
    }

    // ---------- HTTP error mapping ----------

    @Test public void httpErrorsMapToCodeAndRetryability() throws Exception {
        assertHttpMapping(401, false);
        assertHttpMapping(429, true);
        assertHttpMapping(500, true);
    }

    private void assertHttpMapping(int code, boolean retryable) throws Exception {
        server.enqueue(new MockResponse().setResponseCode(code).setBody("{}"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("http_" + code, sink.errorCode);
        assertEquals(retryable, sink.retryable.booleanValue());
    }

    // ---------- cancellation ----------

    @Test public void cancelMidStreamMapsToCancelledTerminal() throws Exception {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n")
                        .setBodyDelay(120, TimeUnit.SECONDS);
            }
        });
        RecordingSink sink = new RecordingSink();
        ChatTransport.Turn turn = anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "claude-test", prompt("user", "hi"), sink);
        Thread.sleep(1_000); // let the request reach the hung body
        turn.cancel();
        sink.awaitTerminal();
        assertEquals("cancelled", sink.errorCode);
        assertEquals(1, sink.terminals.get());
        assertFalse(sink.completed);
    }

    @Test public void missingCredentialFailsFastWithoutNetwork() throws Exception {
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), null, "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("provider_not_configured", sink.errorCode);
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNull("no network call may happen without a key", request);
    }
}
