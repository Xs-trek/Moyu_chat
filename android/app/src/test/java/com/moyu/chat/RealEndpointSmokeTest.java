package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional REAL-endpoint smoke. Skipped unless SMOKE_REAL=1 and the matching key is set.
 * These are real network tests against the configured provider endpoint; when skipped they
 * count as NOT verified — never as passed. Controlled-endpoint coverage lives in
 * TransportMockServerTest and always runs.
 *
 *   SMOKE_REAL=1
 *   # OpenAI Responses and/or Chat Completions (both run unless SMOKE_OPENAI_PROTOCOL selects one)
 *   SMOKE_OPENAI_API_KEY=...            SMOKE_OPENAI_BASE_URL=https://api.openai.com/v1
 *   SMOKE_OPENAI_MODEL=gpt-4o-mini      SMOKE_OPENAI_PROTOCOL=openai-responses|openai-chat-completions
 *   SMOKE_OPENAI_AUTH_MODE=bearer       # bearer | x-api-key | api-key
 *   # Anthropic Messages (transport appends /messages to the configured API base)
 *   SMOKE_ANTHROPIC_API_KEY=...         SMOKE_ANTHROPIC_BASE_URL=https://api.anthropic.com/v1
 *   SMOKE_ANTHROPIC_MODEL=claude-3-5-haiku-latest
 *   SMOKE_ANTHROPIC_AUTH_MODE=x-api-key # bearer | x-api-key | api-key
 */
public class RealEndpointSmokeTest {

    private static final class LatchSink implements ChatTransport.Sink {
        final StringBuffer text = new StringBuffer();
        final CountDownLatch terminal = new CountDownLatch(1);
        final AtomicInteger terminals = new AtomicInteger();
        volatile boolean completed; volatile String error;
        @Override public void onTextDelta(String delta) { text.append(delta); }
        @Override public void onUsage(Integer in, Integer out) { }
        @Override public void onCompleted() { if (terminals.incrementAndGet() == 1) completed = true; terminal.countDown(); }
        @Override public void onError(String code, String summary, boolean retryable) { if (terminals.incrementAndGet() == 1) error = code + ": " + summary; terminal.countDown(); }
    }

    private static CanonicalPrompt oneTurn() throws Exception {
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "user").put("text", "Reply with exactly the word: pong"));
        return new CanonicalPrompt("You are a smoke test.", messages, false, 10, 0, 1);
    }

    private static ChatDatabase.Provider provider(String protocol, String baseUrl, String model, String authMode) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "smoke"; p.displayName = "smoke"; p.protocol = protocol;
        p.authMode = ProviderAuth.normalize(authMode, protocol);
        p.baseUrl = baseUrl; p.model = model;
        p.contextTokens = 32_000; p.maxOutputTokens = 512;
        return p;
    }

    private static void runTurn(ChatTransport transport, ChatDatabase.Provider provider, String apiKey, String label) throws Exception {
        LatchSink sink = new LatchSink();
        transport.stream(provider, apiKey, provider.model, oneTurn(), sink);
        assertTrue(label + " timed out", sink.terminal.await(60, TimeUnit.SECONDS));
        assertNull(label + " failed: " + sink.error, sink.error);
        assertTrue(label + " completed", sink.completed);
        assertTrue(label + " produced text", sink.text.toString().trim().length() > 0);
        assertEquals(label + " must have exactly one terminal", 1, sink.terminals.get());
    }

    /** SMOKE_OPENAI_PROTOCOL, when set, selects a single OpenAI-protocol smoke (full or legacy short id). */
    private static boolean openAiProtocolSelected(String protocol) {
        String want = System.getenv("SMOKE_OPENAI_PROTOCOL");
        if (want == null || want.trim().isEmpty()) return true;
        return want.trim().equals(protocol) || want.trim().equals(protocol.replace("openai-", ""));
    }

    @Test public void openAiResponsesEndpoint() throws Exception {
        Assume.assumeTrue("set SMOKE_REAL=1 + SMOKE_OPENAI_API_KEY to run",
                "1".equals(System.getenv("SMOKE_REAL")) && System.getenv("SMOKE_OPENAI_API_KEY") != null
                        && openAiProtocolSelected(OpenAiCompatibleTransport.RESPONSES));
        try (OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport()) {
            runTurn(transport,
                    provider(OpenAiCompatibleTransport.RESPONSES,
                            System.getenv().getOrDefault("SMOKE_OPENAI_BASE_URL", "https://api.openai.com/v1"),
                            System.getenv().getOrDefault("SMOKE_OPENAI_MODEL", "gpt-4o-mini"),
                            System.getenv().getOrDefault("SMOKE_OPENAI_AUTH_MODE", ProviderAuth.BEARER)),
                    System.getenv("SMOKE_OPENAI_API_KEY"), "openai-responses");
        }
    }

    @Test public void openAiChatCompletionsEndpoint() throws Exception {
        Assume.assumeTrue("set SMOKE_REAL=1 + SMOKE_OPENAI_API_KEY to run",
                "1".equals(System.getenv("SMOKE_REAL")) && System.getenv("SMOKE_OPENAI_API_KEY") != null
                        && openAiProtocolSelected(OpenAiCompatibleTransport.CHAT_COMPLETIONS));
        try (OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport()) {
            runTurn(transport,
                    provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS,
                            System.getenv().getOrDefault("SMOKE_OPENAI_BASE_URL", "https://api.openai.com/v1"),
                            System.getenv().getOrDefault("SMOKE_OPENAI_MODEL", "gpt-4o-mini"),
                            System.getenv().getOrDefault("SMOKE_OPENAI_AUTH_MODE", ProviderAuth.BEARER)),
                    System.getenv("SMOKE_OPENAI_API_KEY"), "openai-chat-completions");
        }
    }

    @Test public void anthropicMessagesEndpoint() throws Exception {
        Assume.assumeTrue("set SMOKE_REAL=1 + SMOKE_ANTHROPIC_API_KEY to run",
                "1".equals(System.getenv("SMOKE_REAL")) && System.getenv("SMOKE_ANTHROPIC_API_KEY") != null);
        try (AnthropicMessagesTransport transport = new AnthropicMessagesTransport()) {
            runTurn(transport,
                    provider(AnthropicMessagesTransport.PROTOCOL,
                            System.getenv().getOrDefault("SMOKE_ANTHROPIC_BASE_URL", "https://api.anthropic.com/v1"),
                            System.getenv().getOrDefault("SMOKE_ANTHROPIC_MODEL", "claude-3-5-haiku-latest"),
                            System.getenv().getOrDefault("SMOKE_ANTHROPIC_AUTH_MODE", ProviderAuth.X_API_KEY)),
                    System.getenv("SMOKE_ANTHROPIC_API_KEY"), "anthropic-messages");
        }
    }
}
