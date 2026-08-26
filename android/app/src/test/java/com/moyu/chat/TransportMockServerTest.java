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
        volatile String imageMime, imageBase64, imageFileName;
        final StringBuffer toolActivity = new StringBuffer();
        final StringBuffer webSources = new StringBuffer();
        final JSONArray continuationItems = new JSONArray();
        final CountDownLatch terminal = new CountDownLatch(1);
        @Override public void onTextDelta(String delta) { text.append(delta); }
        @Override public void onProviderContinuation(JSONObject state) {
            if (state == null) return;
            JSONObject item = state.optJSONObject("item");
            if (item != null) try { continuationItems.put(new JSONObject(item.toString())); } catch (Exception ignored) { }
        }
        @Override public void onImage(String mimeType, String base64, String fileName) { imageMime = mimeType; imageBase64 = base64; imageFileName = fileName; }
        @Override public void onToolActivity(String tool, String state) { if (toolActivity.length() > 0) toolActivity.append('|'); toolActivity.append(tool).append(':').append(state); }
        @Override public void onWebSource(String url, String title) { if (webSources.length() > 0) webSources.append('|'); webSources.append(url).append('#').append(title); }
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
        p.authMode = ProviderAuth.defaultForProtocol(protocol);
        p.baseUrl = AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? server.url("/").toString() : server.url("/v1").toString();
        p.model = "test-model";
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

    private static CanonicalPrompt imagePrompt() throws Exception {
        JSONObject image = new JSONObject().put("kind", "image").put("mimeType", "image/png")
                .put("dataUrl", "data:image/png;base64,QUJD").put("width", 1).put("height", 1);
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("text", "describe")
                .put("attachments", new JSONArray().put(image)));
        return new CanonicalPrompt("SYSTEM-INSTRUCTIONS", messages, false, 100, 0, 1);
    }

    private String bodyOf() throws Exception {
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request);
        return request.getBody().readUtf8();
    }

    // ---------- OpenAI Chat Completions ----------

    @Test public void openCodeGoVisionUsesDocumentedEndpointBearerAndBareModelId() throws Exception {
        server.enqueue(new MockResponse().setBody("data: [DONE]\n\n"));
        ChatDatabase.Provider p = provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        p.baseUrl = server.url("/zen/go/v1").toString();
        p.authMode = ProviderAuth.BEARER;
        RecordingSink sink = new RecordingSink();
        openAi.stream(p, "sk-go-test", "deepseek-v4-flash-vision-exp", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/zen/go/v1/chat/completions", request.getPath());
        assertEquals("Bearer sk-go-test", request.getHeader("Authorization"));
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("deepseek-v4-flash-vision-exp", body.optString("model"));
        assertTrue(body.optBoolean("stream"));
    }

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
        assertEquals(1_024, body.optInt("max_tokens"));
    }

    @Test public void chatCompletionsEncodesImageInputAsImageUrlPart() throws Exception {
        server.enqueue(new MockResponse().setBody("data: [DONE]\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "vision-model", imagePrompt(), sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        JSONArray content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).optString("type"));
        assertEquals("describe", content.getJSONObject(0).optString("text"));
        assertEquals("image_url", content.getJSONObject(1).optString("type"));
        assertEquals("data:image/png;base64,QUJD", content.getJSONObject(1).getJSONObject("image_url").optString("url"));
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

    @Test public void chatFinishReasonIsSemanticTerminalWithoutDoneMarker() throws Exception {
        RecordingSink sink = new RecordingSink();
        JSONObject event = new JSONObject("{\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}");
        assertTrue(OpenAiCompatibleTransport.parseChat(event, sink));
        assertEquals("done", sink.text.toString());
        assertEquals(3, sink.inputTokens);
        assertEquals(1, sink.outputTokens);
        assertNull(sink.errorCode);
    }

    @Test public void chatTrailingCostChunkIsSemanticTerminal() throws Exception {
        RecordingSink sink = new RecordingSink();
        assertTrue(OpenAiCompatibleTransport.parseChat(new JSONObject("{\"choices\":[],\"cost\":\"0\"}"), sink));
        assertFalse(OpenAiCompatibleTransport.parseChat(new JSONObject("{\"choices\":[]}"), sink));
    }

    @Test public void chatFailureFinishReasonsMapToErrors() throws Exception {
        RecordingSink network = new RecordingSink();
        assertTrue(OpenAiCompatibleTransport.parseChat(new JSONObject("{\"choices\":[{\"delta\":{},\"finish_reason\":\"network_error\"}]}"), network));
        assertEquals("network_error", network.errorCode); assertTrue(network.retryable);

        RecordingSink tool = new RecordingSink();
        assertTrue(OpenAiCompatibleTransport.parseChat(new JSONObject("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"), tool));
        assertEquals("unsupported_tool_call", tool.errorCode); assertFalse(tool.retryable);
    }

    // ---------- OpenAI Responses ----------

    @Test public void responsesHostedWebSearchIsExplicitAndExtractsSources() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.web_search_call.in_progress\"}\n\n" +
                "data: {\"type\":\"response.web_search_call.completed\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"web_search_call\",\"action\":{\"sources\":[{\"url\":\"https://example.com/a\",\"title\":\"Example A\"}]}},{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"answer\",\"annotations\":[{\"type\":\"url_citation\",\"url\":\"https://example.com/b\",\"title\":\"Example B\"}]}]}]}}\n\n"));
        RecordingSink sink = new RecordingSink();
        ChatTurnOptions options = new ChatTurnOptions("web_search", "auto");
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "test-model", ReasoningPolicy.DEFAULT, prompt("user", "latest"), options, sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        assertEquals("web_search", body.getJSONArray("tools").getJSONObject(0).optString("type"));
        assertEquals("auto", body.optString("tool_choice"));
        assertEquals("web_search_call.action.sources", body.getJSONArray("include").optString(0));
        assertEquals("web_search:running|web_search:completed", sink.toolActivity.toString());
        assertTrue(sink.webSources.toString().contains("https://example.com/a#Example A"));
        assertTrue(sink.webSources.toString().contains("https://example.com/b#Example B"));
    }

    @Test public void chatCompletionsNeverInjectsHostedWebTools() throws Exception {
        server.enqueue(new MockResponse().setBody("data: [DONE]\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "test-model", ReasoningPolicy.DEFAULT,
                prompt("user", "latest"), new ChatTurnOptions("web_search", "on"), sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        assertFalse(body.has("tools"));
        assertFalse(body.has("tool_choice"));
        assertFalse(body.has("include"));
    }

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

    @Test public void responsesEncodesImageInputAsInputImagePart() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "vision-model", imagePrompt(), sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        JSONArray content = body.getJSONArray("input").getJSONObject(0).getJSONArray("content");
        assertEquals("input_text", content.getJSONObject(0).optString("type"));
        assertEquals("describe", content.getJSONObject(0).optString("text"));
        assertEquals("input_image", content.getJSONObject(1).optString("type"));
        assertEquals("data:image/png;base64,QUJD", content.getJSONObject(1).optString("image_url"));
    }

    @Test public void responsesEmitsCompletedImageGenerationResult() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"img-1\",\"type\":\"image_generation_call\",\"output_format\":\"png\",\"result\":\"QUJD\"}}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"id\":\"img-1\",\"type\":\"image_generation_call\",\"output_format\":\"png\",\"result\":\"QUJD\"}]}}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "image-model", prompt("user", "draw"), sink);
        sink.awaitTerminal();
        assertTrue(sink.completed);
        assertEquals("image/png", sink.imageMime);
        assertEquals("QUJD", sink.imageBase64);
        assertEquals("generated.png", sink.imageFileName);
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

    @Test public void responsesTerminalEventSignalsReaderExit() throws Exception {
        RecordingSink sink = new RecordingSink();
        assertTrue(OpenAiCompatibleTransport.parseResponses(
                new JSONObject("{\"type\":\"response.completed\",\"response\":{}}"), sink, new java.util.HashSet<>()));
        assertTrue(sink.completed);
        assertEquals(1, sink.terminals.get());
    }

    @Test public void nonSseHttp200IsNotMisreportedAsEmptySuccess() throws Exception {
        server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"not streamed\"}}]}"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("unexpected_response_format", sink.errorCode);
        assertFalse(sink.completed);
    }

    // ---------- Anthropic Messages ----------

    @Test public void anthropicHostedWebToolsAreBoundedAndExtractSources() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"server_tool_use\",\"name\":\"web_search\"}}\n\n" +
                "data: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"web_search_tool_result\",\"content\":[{\"url\":\"https://example.com/search\",\"title\":\"Search source\"}]}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"url\":\"https://example.com/cite\",\"title\":\"Citation\"}}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"));
        RecordingSink sink = new RecordingSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "claude-test", ReasoningPolicy.DEFAULT,
                prompt("user", "latest"), new ChatTurnOptions("web_search,web_fetch", "auto"), sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        JSONArray tools = body.getJSONArray("tools");
        assertEquals(2, tools.length());
        JSONObject search = tools.getJSONObject(0), fetch = tools.getJSONObject(1);
        assertEquals("web_search_20260318", search.optString("type"));
        assertEquals(4, search.optInt("max_uses"));
        assertEquals("direct", search.getJSONArray("allowed_callers").optString(0));
        assertEquals("web_fetch_20260318", fetch.optString("type"));
        assertEquals(3, fetch.optInt("max_uses"));
        assertEquals(20000, fetch.optInt("max_content_tokens"));
        assertTrue(fetch.getJSONObject("citations").optBoolean("enabled"));
        assertEquals("web_search:running|web_search:completed", sink.toolActivity.toString());
        assertTrue(sink.webSources.toString().contains("https://example.com/search#Search source"));
        assertTrue(sink.webSources.toString().contains("https://example.com/cite#Citation"));
    }

    @Test public void anthropicNativeContinuationPreservesThinkingTextAndHostedWebBlocks() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"plan\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig\"}}\n\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "data: {\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtoolu_1\",\"name\":\"web_search\",\"input\":{}}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"latest\\\"}\"}}\n\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":1}\n\n" +
                "data: {\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtoolu_1\",\"content\":[{\"type\":\"web_search_result\",\"url\":\"https://example.com\",\"title\":\"E\",\"encrypted_content\":\"enc\"}]}}\n\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":2}\n\n" +
                "data: {\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":3}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"));
        RecordingSink sink = new RecordingSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "native-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals(4, sink.continuationItems.length());
        assertEquals("thinking", sink.continuationItems.getJSONObject(0).optString("type"));
        assertEquals("sig", sink.continuationItems.getJSONObject(0).optString("signature"));
        assertEquals("server_tool_use", sink.continuationItems.getJSONObject(1).optString("type"));
        assertEquals("latest", sink.continuationItems.getJSONObject(1).getJSONObject("input").optString("query"));
        JSONObject result = sink.continuationItems.getJSONObject(2);
        assertEquals("web_search_tool_result", result.optString("type"));
        assertEquals("enc", result.getJSONArray("content").getJSONObject(0).optString("encrypted_content"));
        assertEquals("text", sink.continuationItems.getJSONObject(3).optString("type"));
        assertEquals("answer", sink.continuationItems.getJSONObject(3).optString("text"));
    }

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

    @Test public void anthropicMessageStopSignalsReaderExit() throws Exception {
        RecordingSink sink = new RecordingSink();
        assertTrue(AnthropicMessagesTransport.parse(new JSONObject("{\"type\":\"message_stop\"}"), sink));
        assertTrue(sink.completed);
        assertEquals(1, sink.terminals.get());
    }

    @Test public void anthropicEncodesImageInputAsBase64SourceBlock() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"));
        RecordingSink sink = new RecordingSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "vision-model", imagePrompt(), sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        JSONArray content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content");
        assertEquals("text", content.getJSONObject(0).optString("type"));
        JSONObject image = content.getJSONObject(1);
        assertEquals("image", image.optString("type"));
        assertEquals("base64", image.getJSONObject("source").optString("type"));
        assertEquals("image/png", image.getJSONObject("source").optString("media_type"));
        assertEquals("QUJD", image.getJSONObject("source").optString("data"));
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

    @Test public void reasoningWireFormatsMapSemanticEffortWithoutArbitraryJson() throws Exception {
        server.enqueue(new MockResponse().setBody("data: [DONE]\n\n"));
        ChatDatabase.Provider responses = provider(OpenAiCompatibleTransport.RESPONSES);
        responses.reasoningWire = ReasoningPolicy.WIRE_OPENAI_RESPONSES;
        RecordingSink r1 = new RecordingSink();
        openAi.stream(responses, "test-key", "test-model", ReasoningPolicy.HIGH, prompt("user", "hi"), r1); r1.awaitTerminal();
        JSONObject b1 = new JSONObject(bodyOf());
        assertEquals("high", b1.getJSONObject("reasoning").optString("effort"));

        server.enqueue(new MockResponse().setBody("data: [DONE]\n\n"));
        ChatDatabase.Provider chat = provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        chat.reasoningWire = ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT;
        RecordingSink r2 = new RecordingSink();
        openAi.stream(chat, "test-key", "test-model", ReasoningPolicy.MEDIUM, prompt("user", "hi"), r2); r2.awaitTerminal();
        JSONObject b2 = new JSONObject(bodyOf());
        assertEquals("medium", b2.optString("reasoning_effort"));

        server.enqueue(new MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"));
        ChatDatabase.Provider claude = provider(AnthropicMessagesTransport.PROTOCOL);
        claude.reasoningWire = ReasoningPolicy.WIRE_ANTHROPIC_EFFORT;
        RecordingSink r3 = new RecordingSink();
        anthropic.stream(claude, "test-key", "claude-test", ReasoningPolicy.XHIGH, prompt("user", "hi"), r3); r3.awaitTerminal();
        JSONObject b3 = new JSONObject(bodyOf());
        assertEquals("xhigh", b3.getJSONObject("output_config").optString("effort"));
    }

    @Test public void thirdPartyMessagesCanEnableThinkingWithoutEffortExtension() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"));
        ChatDatabase.Provider gateway = provider(AnthropicMessagesTransport.PROTOCOL);
        gateway.reasoningWire = ReasoningPolicy.WIRE_NONE;
        gateway.thinkingMode = ReasoningPolicy.THINKING_ADAPTIVE;
        RecordingSink sink = new RecordingSink();
        anthropic.stream(gateway, "test-key", "third-party-model", ReasoningPolicy.DEFAULT, prompt("user", "hi"), sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        assertEquals("adaptive", body.getJSONObject("thinking").optString("type"));
        assertFalse("Messages thinking support does not imply output_config.effort support", body.has("output_config"));
    }

    @Test public void thirdPartyMessagesNoneEffortDoesNotEncodeInvalidNoneValue() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"));
        ChatDatabase.Provider gateway = provider(AnthropicMessagesTransport.PROTOCOL);
        gateway.reasoningWire = ReasoningPolicy.WIRE_ANTHROPIC_EFFORT;
        gateway.thinkingMode = ReasoningPolicy.THINKING_DISABLED;
        RecordingSink sink = new RecordingSink();
        anthropic.stream(gateway, "test-key", "third-party-model", ReasoningPolicy.NONE_LEVEL, prompt("user", "hi"), sink);
        sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        assertEquals("disabled", body.getJSONObject("thinking").optString("type"));
        assertFalse(body.has("output_config"));
    }

    @Test public void responsesContinuationItemsCrossTerminalSinkWrapper() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"encrypted_content\":\"cipher\"}}\n\n" +
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        RecordingSink sink = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "native-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        assertEquals("continuation callbacks must survive TerminalSink", 2, sink.continuationItems.length());
        assertEquals("reasoning", sink.continuationItems.getJSONObject(0).optString("type"));
        assertEquals("cipher", sink.continuationItems.getJSONObject(0).optString("encrypted_content"));
        assertEquals("message", sink.continuationItems.getJSONObject(1).optString("type"));
    }

    @Test public void responsesContinuationIncludeRequiresExplicitPromptCapability() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        RecordingSink first = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "third-party-model", prompt("user", "hi"), first);
        first.awaitTerminal();
        JSONObject noNative = new JSONObject(bodyOf());
        assertFalse("Responses protocol alone must not request private continuation", noNative.has("include"));

        server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("text", "hi"));
        CanonicalPrompt nativePrompt = new CanonicalPrompt("SYSTEM-INSTRUCTIONS", messages, false, 10, 0, 1, 0,
                new JSONArray(messages.toString()), true);
        RecordingSink second = new RecordingSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "test-key", "native-deployment", nativePrompt, second);
        second.awaitTerminal();
        JSONObject nativeBody = new JSONObject(bodyOf());
        assertEquals("reasoning.encrypted_content", nativeBody.getJSONArray("include").optString(0));
    }

    @Test public void defaultReasoningLeavesProviderNativeDefaultUntouched() throws Exception {
        server.enqueue(new MockResponse().setBody("data: [DONE]\n\n"));
        ChatDatabase.Provider responses = provider(OpenAiCompatibleTransport.RESPONSES);
        responses.reasoningWire = ReasoningPolicy.WIRE_OPENAI_RESPONSES;
        RecordingSink sink = new RecordingSink();
        openAi.stream(responses, "test-key", "test-model", ReasoningPolicy.DEFAULT, prompt("user", "hi"), sink); sink.awaitTerminal();
        JSONObject body = new JSONObject(bodyOf());
        assertFalse(body.has("reasoning"));
        assertFalse(body.has("reasoning_effort"));
        assertFalse(body.has("output_config"));
    }

    @Test public void authModesMapToExpectedHeadersAndNeverEnterBody() throws Exception {
        assertAuthMode(ProviderAuth.BEARER, "Authorization", "Bearer test-key");
        assertAuthMode(ProviderAuth.X_API_KEY, "x-api-key", "test-key");
        assertAuthMode(ProviderAuth.API_KEY, "api-key", "test-key");
    }

    private void assertAuthMode(String authMode, String expectedHeader, String expectedValue) throws Exception {
        server.enqueue(new MockResponse().setBody("data: [DONE]\n\n"));
        ChatDatabase.Provider p = provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        p.authMode = authMode;
        RecordingSink sink = new RecordingSink();
        openAi.stream(p, "test-key", "test-model", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(expectedValue, request.getHeader(expectedHeader));
        if (!"Authorization".equals(expectedHeader)) assertNull(request.getHeader("Authorization"));
        if (!"x-api-key".equals(expectedHeader)) assertNull(request.getHeader("x-api-key"));
        if (!"api-key".equals(expectedHeader)) assertNull(request.getHeader("api-key"));
        assertFalse("credential must never enter request JSON", request.getBody().readUtf8().contains("test-key"));
    }

    @Test public void anthropicUsesRootPlusV1MessagesPath() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"));
        RecordingSink sink = new RecordingSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "test-key", "claude-test", prompt("user", "hi"), sink);
        sink.awaitTerminal();
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/v1/messages", request.getPath());
        assertEquals("test-key", request.getHeader("x-api-key"));
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
