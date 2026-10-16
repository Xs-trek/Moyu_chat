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

/** v0.0.4 typed client-tool wire coverage for all supported transport protocols. */
public class ClientToolTransportTest {
    private MockWebServer server;
    private final OpenAiCompatibleTransport openAi = new OpenAiCompatibleTransport();
    private final AnthropicMessagesTransport anthropic = new AnthropicMessagesTransport();

    @Before public void start() throws Exception { server = new MockWebServer(); server.start(); }
    @After public void stop() throws Exception { openAi.close(); anthropic.close(); server.shutdown(); }

    private static final class ToolSink implements ChatTransport.Sink {
        final CountDownLatch terminal = new CountDownLatch(1);
        volatile JSONArray calls;
        volatile String error;
        volatile boolean completed;
        @Override public void onTextDelta(String delta) { }
        @Override public void onUsage(Integer inputTokens, Integer outputTokens) { }
        @Override public void onClientToolBatch(JSONArray calls) {
            try { this.calls = calls == null ? new JSONArray() : new JSONArray(calls.toString()); }
            catch (Exception ignored) { this.calls = new JSONArray(); }
            terminal.countDown();
        }
        @Override public void onCompleted() { completed = true; terminal.countDown(); }
        @Override public void onError(String code, String summary, boolean retryable) { error = code; terminal.countDown(); }
        void await() throws Exception { assertTrue("no tool/terminal callback", terminal.await(10, TimeUnit.SECONDS)); }
    }

    private ChatDatabase.Provider provider(String protocol) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.displayName = "p"; p.protocol = protocol;
        p.baseUrl = AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? server.url("/").toString() : server.url("/v1").toString();
        p.authMode = ProviderAuth.defaultForProtocol(protocol); p.model = "model"; p.contextTokens = 32000; p.maxOutputTokens = 1024;
        return p;
    }

    private static CanonicalPrompt prompt() throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("text", "do the task"));
        return new CanonicalPrompt("SYSTEM", messages, false, 10, 0, 1);
    }

    private static ChatTurnOptions options(String calling, String names) {
        return new ChatTurnOptions("", ChatTurnOptions.WEB_OFF, calling, names, new JSONArray());
    }

    private JSONObject requestBody() throws Exception {
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull(request);
        return new JSONObject(request.getBody().readUtf8());
    }

    @Test public void chatCompletionsEmitsTypedToolBatch() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"web_search\",\"arguments\":\"{\\\"query\\\":\\\"latest\\\"}\"}}]}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n"));
        ToolSink sink = new ToolSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "key", "model", ReasoningPolicy.DEFAULT,
                prompt(), options(ClientToolRuntime.OPENAI_CHAT, ClientToolRuntime.WEB_SEARCH), sink);
        sink.await();
        assertNull(sink.error); assertFalse(sink.completed); assertNotNull(sink.calls); assertEquals(1, sink.calls.length());
        assertEquals("call_1", sink.calls.getJSONObject(0).optString("id"));
        assertEquals("latest", sink.calls.getJSONObject(0).getJSONObject("arguments").optString("query"));
        JSONObject body = requestBody();
        JSONObject function = body.getJSONArray("tools").getJSONObject(0).getJSONObject("function");
        assertEquals("web_search", function.optString("name"));
        assertTrue(function.optBoolean("strict"));
    }

    @Test public void responsesEmitsFunctionCallBatch() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"delegate_subtask\",\"arguments\":\"{\\\"task\\\":\\\"inspect A\\\"}\"}}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"delegate_subtask\",\"arguments\":\"{\\\"task\\\":\\\"inspect A\\\"}\"}]}}\n\n"));
        ToolSink sink = new ToolSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "key", "model", ReasoningPolicy.DEFAULT,
                prompt(), options(ClientToolRuntime.OPENAI_RESPONSES, ClientToolRuntime.DELEGATE_SUBTASK), sink);
        sink.await();
        assertNull(sink.error); assertNotNull(sink.calls); assertEquals(1, sink.calls.length());
        assertEquals("inspect A", sink.calls.getJSONObject(0).getJSONObject("arguments").optString("task"));
        JSONObject body = requestBody();
        JSONObject tool = body.getJSONArray("tools").getJSONObject(0);
        assertEquals("function", tool.optString("type"));
        assertEquals("delegate_subtask", tool.optString("name"));
        JSONArray include = body.optJSONArray("include");
        assertNotNull("Responses client tools need replayable reasoning even when cross-turn continuation is off", include);
        boolean encryptedReasoning = false;
        for (int i = 0; i < include.length(); i++) if ("reasoning.encrypted_content".equals(include.optString(i))) encryptedReasoning = true;
        assertTrue(encryptedReasoning);
    }

    @Test public void anthropicEmitsStreamedToolUseBatch() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"web_fetch\",\"input\":{}}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"url\\\":\\\"https://example.com\\\"}\"}}\n\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":12}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"));
        ToolSink sink = new ToolSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "key", "model", ReasoningPolicy.DEFAULT,
                prompt(), options(ClientToolRuntime.ANTHROPIC_TOOLS, ClientToolRuntime.WEB_FETCH), sink);
        sink.await();
        assertNull(sink.error); assertNotNull(sink.calls); assertEquals(1, sink.calls.length());
        assertEquals("https://example.com", sink.calls.getJSONObject(0).getJSONObject("arguments").optString("url"));
        JSONObject tool = requestBody().getJSONArray("tools").getJSONObject(0);
        assertEquals("web_fetch", tool.optString("name"));
        assertTrue(tool.optBoolean("strict"));
        JSONObject inputSchema = tool.getJSONObject("input_schema");
        assertEquals("object", inputSchema.optString("type"));
        assertEquals("^https://[^\\s]+$", inputSchema.getJSONObject("properties").getJSONObject("url").optString("pattern"));
    }

    @Test public void responsesSecondRoundReplaysExactReasoningAndFunctionCallBeforeToolOutput() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"done\"}\n\n" +
                "data: {\"type\":\"response.completed\",\"response\":{}}\n\n"));
        JSONArray toolHistory = new JSONArray().put(new JSONObject().put("round", 1).put("id", "call_2")
                .put("name", "delegate_subtask").put("arguments", new JSONObject().put("task", "inspect A"))
                .put("result", "child finding"));
        JSONArray providerHistory = new JSONArray()
                .put(new JSONObject().put("round", 1).put("item", new JSONObject()
                        .put("id", "rs_1").put("type", "reasoning").put("encrypted_content", "cipher-1")))
                .put(new JSONObject().put("round", 1).put("item", new JSONObject()
                        .put("type", "function_call").put("call_id", "call_2").put("name", "delegate_subtask")
                        .put("arguments", "{\"task\":\"inspect A\"}")));
        ChatTurnOptions roundTwo = new ChatTurnOptions("", ChatTurnOptions.WEB_OFF,
                ClientToolRuntime.OPENAI_RESPONSES, ClientToolRuntime.DELEGATE_SUBTASK, toolHistory, providerHistory);
        ToolSink sink = new ToolSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "key", "model", ReasoningPolicy.DEFAULT,
                prompt(), roundTwo, sink);
        sink.await();
        assertNull(sink.error); assertTrue(sink.completed);
        JSONArray input = requestBody().getJSONArray("input");
        int reasoningIndex = -1, callIndex = -1, outputIndex = -1, callCount = 0;
        for (int i = 0; i < input.length(); i++) {
            JSONObject item = input.optJSONObject(i); if (item == null) continue;
            String type = item.optString("type", "");
            if ("reasoning".equals(type) && "rs_1".equals(item.optString("id"))) reasoningIndex = i;
            if ("function_call".equals(type) && "call_2".equals(item.optString("call_id"))) { callIndex = i; callCount++; }
            if ("function_call_output".equals(type) && "call_2".equals(item.optString("call_id"))) {
                outputIndex = i; assertEquals("child finding", item.optString("output"));
            }
        }
        assertTrue(reasoningIndex >= 0); assertTrue(callIndex > reasoningIndex); assertTrue(outputIndex > callIndex);
        assertEquals("original function_call must not be duplicated", 1, callCount);
    }

    @Test public void anthropicSecondRoundReplaysThinkingAndToolUseBeforeToolResult() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"done\"}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"));
        JSONArray toolHistory = new JSONArray().put(new JSONObject().put("round", 1).put("id", "toolu_1")
                .put("name", "web_fetch").put("arguments", new JSONObject().put("url", "https://example.com"))
                .put("result", "page text"));
        JSONArray providerHistory = new JSONArray()
                .put(new JSONObject().put("round", 1).put("item", new JSONObject()
                        .put("type", "thinking").put("thinking", "private thought").put("signature", "sig-1")))
                .put(new JSONObject().put("round", 1).put("item", new JSONObject()
                        .put("type", "tool_use").put("id", "toolu_1").put("name", "web_fetch")
                        .put("input", new JSONObject().put("url", "https://example.com"))));
        ChatTurnOptions roundTwo = new ChatTurnOptions("", ChatTurnOptions.WEB_OFF,
                ClientToolRuntime.ANTHROPIC_TOOLS, ClientToolRuntime.WEB_FETCH, toolHistory, providerHistory);
        ToolSink sink = new ToolSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "key", "model", ReasoningPolicy.DEFAULT,
                prompt(), roundTwo, sink);
        sink.await();
        assertNull(sink.error); assertTrue(sink.completed);
        JSONArray messages = requestBody().getJSONArray("messages");
        assertTrue(messages.length() >= 3);
        JSONObject assistant = messages.getJSONObject(messages.length() - 2);
        JSONObject user = messages.getJSONObject(messages.length() - 1);
        JSONArray assistantContent = assistant.getJSONArray("content");
        assertEquals("thinking", assistantContent.getJSONObject(0).optString("type"));
        assertEquals("sig-1", assistantContent.getJSONObject(0).optString("signature"));
        assertEquals("tool_use", assistantContent.getJSONObject(1).optString("type"));
        assertEquals("toolu_1", assistantContent.getJSONObject(1).optString("id"));
        JSONArray userContent = user.getJSONArray("content");
        assertEquals("tool_result", userContent.getJSONObject(0).optString("type"));
        assertEquals("toolu_1", userContent.getJSONObject(0).optString("tool_use_id"));
        assertEquals("page text", userContent.getJSONObject(0).optString("content"));
    }

    // ---------- spec T3: earlier turns' tool calls ----------

    /** Two earlier turns: the first answered after a search, the second plainly; then the current question. */
    private static CanonicalPrompt promptWithEarlierToolCalls() throws Exception {
        JSONArray calls = new JSONArray().put(new JSONObject().put("round", 1).put("id", "call_old").put("name", "web_search")
                .put("arguments", new JSONObject().put("query", "tide tables")).put("result", "High tide 06:12."));
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user").put("text", "When is high tide?"))
                .put(new JSONObject().put("role", "assistant").put("text", "At 06:12.").put(ToolHistory.FIELD, calls))
                .put(new JSONObject().put("role", "user").put("text", "And low tide?"));
        return new CanonicalPrompt("SYSTEM", messages, false, 10, 0, 3);
    }

    private static MockResponse chatDone() {
        return new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n");
    }

    @Test public void chatCompletionsReplaysAnEarlierAnswersToolCallsBeforeIt() throws Exception {
        server.enqueue(chatDone());
        ToolSink sink = new ToolSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "key", "model", ReasoningPolicy.DEFAULT,
                promptWithEarlierToolCalls(), options(ClientToolRuntime.OPENAI_CHAT, ClientToolRuntime.WEB_SEARCH), sink);
        sink.await();
        JSONArray messages = requestBody().getJSONArray("messages");
        // system, user, assistant(tool_calls), tool, assistant(answer), user
        assertEquals(6, messages.length());
        assertEquals("call_old", messages.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0).optString("id"));
        assertEquals("tool", messages.getJSONObject(3).optString("role"));
        assertEquals("High tide 06:12.", messages.getJSONObject(3).optString("content"));
        assertEquals("At 06:12.", messages.getJSONObject(4).optString("content"));
    }

    @Test public void withoutClientToolsAnEarlierAnswersToolCallsGoAsText() throws Exception {
        server.enqueue(chatDone());
        ToolSink sink = new ToolSink();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "key", "model", ReasoningPolicy.DEFAULT,
                promptWithEarlierToolCalls(), ChatTurnOptions.NONE, sink);
        sink.await();
        JSONObject body = requestBody();
        assertFalse("no tool messages for tools this request does not define", body.toString().contains("\"role\":\"tool\""));
        JSONArray messages = body.getJSONArray("messages");
        assertEquals(4, messages.length());
        String answer = messages.getJSONObject(2).optString("content");
        assertTrue(answer.contains("web_search") && answer.contains("tide tables") && answer.contains("High tide 06:12."));
        assertTrue(answer.endsWith("At 06:12."));
    }

    @Test public void theLastRoundKeepsReplayingEarlierToolCallsAsToolMessages() throws Exception {
        server.enqueue(chatDone());
        ToolSink sink = new ToolSink();
        ChatTurnOptions lastRound = options(ClientToolRuntime.OPENAI_CHAT, ClientToolRuntime.WEB_SEARCH).withoutClientTools();
        openAi.stream(provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS), "key", "model", ReasoningPolicy.DEFAULT,
                promptWithEarlierToolCalls(), lastRound, sink);
        sink.await();
        assertTrue("one form for the whole turn", requestBody().toString().contains("\"role\":\"tool\""));
    }

    @Test public void responsesReplaysAnEarlierAnswersToolCallsBeforeIt() throws Exception {
        server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"));
        ToolSink sink = new ToolSink();
        openAi.stream(provider(OpenAiCompatibleTransport.RESPONSES), "key", "model", ReasoningPolicy.DEFAULT,
                promptWithEarlierToolCalls(), options(ClientToolRuntime.OPENAI_RESPONSES, ClientToolRuntime.WEB_SEARCH), sink);
        sink.await();
        JSONArray input = requestBody().getJSONArray("input");
        assertEquals("function_call", input.getJSONObject(1).optString("type"));
        assertEquals("call_old", input.getJSONObject(1).optString("call_id"));
        assertEquals("function_call_output", input.getJSONObject(2).optString("type"));
        assertEquals("High tide 06:12.", input.getJSONObject(2).optString("output"));
        assertEquals("assistant", input.getJSONObject(3).optString("role"));
    }

    @Test public void anthropicReplaysAnEarlierAnswersToolCallsBeforeIt() throws Exception {
        server.enqueue(new MockResponse().setBody("event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"));
        ToolSink sink = new ToolSink();
        anthropic.stream(provider(AnthropicMessagesTransport.PROTOCOL), "key", "model", ReasoningPolicy.DEFAULT,
                promptWithEarlierToolCalls(), options(ClientToolRuntime.ANTHROPIC_TOOLS, ClientToolRuntime.WEB_SEARCH), sink);
        sink.await();
        JSONArray messages = requestBody().getJSONArray("messages");
        // user, assistant(tool_use), user(tool_result), assistant(answer), user
        assertEquals(5, messages.length());
        assertEquals("tool_use", messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).optString("type"));
        JSONObject result = messages.getJSONObject(2).getJSONArray("content").getJSONObject(0);
        assertEquals("call_old", result.optString("tool_use_id"));
        assertEquals("High tide 06:12.", result.optString("content"));
        assertEquals("At 06:12.", messages.getJSONObject(3).optString("content"));
    }
}
