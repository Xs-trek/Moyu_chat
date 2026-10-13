package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** Route wire knobs and extra headers: strict vocabularies, and no way to smuggle a credential into the page. */
public class RouteDialectTest {
    private static final String CHAT = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
    private static final String RESPONSES = OpenAiCompatibleTransport.RESPONSES;
    private static final String MESSAGES = AnthropicMessagesTransport.PROTOCOL;

    @Test public void defaultsAreConservativePerProtocol() {
        RouteDialect chat = RouteDialect.defaults(CHAT);
        assertEquals(RouteDialect.CAP_MAX_TOKENS, chat.outputCap);
        assertTrue("usage is requested on compatible streams", chat.streamUsage);
        assertEquals(RouteDialect.THINKING_NONE, chat.thinkingParam);
        assertFalse(chat.replaysReasoning(true));
        assertFalse("Responses does not store unless asked", RouteDialect.defaults(RESPONSES).store);
        assertFalse("unknown Anthropic-compatible gateways do not get cache_control", RouteDialect.defaults(MESSAGES).autoCache);
    }

    @Test public void parseRoundTripsOnlyTheProtocolsOwnKeys() throws Exception {
        RouteDialect d = RouteDialect.parse(CHAT, new JSONObject("{\"outputCap\":\"omit\",\"thinkingParam\":\"thinking-type\",\"reasoningReplay\":\"always\"}"));
        JSONObject json = d.toJson(CHAT);
        assertEquals("omit", json.getString("outputCap"));
        assertEquals(RouteDialect.REPLAY_ALWAYS, json.getString("reasoningReplay"));
        assertFalse(json.has("store"));
        assertEquals(json.toString(), RouteDialect.parse(CHAT, json).toJson(CHAT).toString());
        assertTrue(RouteDialect.parse(MESSAGES, "{\"autoCache\":true}").autoCache);
        assertRejected(() -> RouteDialect.parse(MESSAGES, "{\"outputCap\":\"omit\"}"), "outputCap");
        assertRejected(() -> RouteDialect.parse(CHAT, "{\"reasoningReplay\":\"sometimes\"}"), "推理回传");
        assertRejected(() -> RouteDialect.parse(CHAT, "not json"), "JSON");
    }

    @Test public void thinkingModesFollowWhatTheRouteCanCarry() {
        RouteDialect plain = RouteDialect.defaults(CHAT);
        RouteDialect switched = RouteDialect.parse(CHAT, "{\"thinkingParam\":\"thinking-type\"}");
        assertTrue(plain.carriesThinking(CHAT, ReasoningPolicy.THINKING_PROVIDER_DEFAULT));
        assertFalse(plain.carriesThinking(CHAT, ReasoningPolicy.THINKING_ENABLED));
        assertTrue(switched.carriesThinking(CHAT, ReasoningPolicy.THINKING_DISABLED));
        assertFalse(switched.carriesThinking(CHAT, ReasoningPolicy.THINKING_MANUAL));
        assertTrue(RouteDialect.defaults(MESSAGES).carriesThinking(MESSAGES, ReasoningPolicy.THINKING_ADAPTIVE));
        assertFalse(RouteDialect.defaults(MESSAGES).carriesThinking(MESSAGES, ReasoningPolicy.THINKING_ENABLED));
        assertFalse(RouteDialect.defaults(RESPONSES).carriesThinking(RESPONSES, ReasoningPolicy.THINKING_DISABLED));
    }

    @Test public void headersRefuseCredentialsAndTransportOwnedNames() throws Exception {
        JSONObject ok = RouteHeaders.normalize(new JSONObject().put("OpenAI-Organization", "org_1").put(" anthropic-beta ", "x-2026"));
        assertEquals("org_1", ok.getString("OpenAI-Organization"));
        assertEquals("x-2026", ok.getString("anthropic-beta"));
        for (String name : new String[]{"Authorization", "x-api-key", "Api-Key", "X-Api-Token", "Cookie", "Proxy-Authorization",
                "My-Secret", "X-Session-Id", "anthropic-version", "Host", "Content-Type"}) {
            assertRejected(() -> RouteHeaders.normalize(obj(name, "v")), name);
        }
        assertRejected(() -> RouteHeaders.normalize(obj("X-Title", "摸鱼")), "ASCII");
        assertRejected(() -> RouteHeaders.normalize(obj("X-Title", "a\r\nInjected: 1")), "ASCII");
        assertRejected(() -> RouteHeaders.normalize(obj("Bad Name", "v")), "名称无效");
        JSONObject many = new JSONObject();
        for (int i = 0; i < RouteHeaders.MAX_HEADERS + 1; i++) many.put("X-H" + i, "v");
        assertRejected(() -> RouteHeaders.normalize(many), "最多");
    }

    @Test public void keysAreShapeCheckedWithoutEchoingThem() {
        assertEquals("sk-abcdefgh", ChatSecretStore.normalizeKey("  sk-abcdefgh \n"));
        assertRejected(() -> ChatSecretStore.normalizeKey("sk-abc defgh"), "空格");
        assertRejected(() -> ChatSecretStore.normalizeKey("sk-密钥abcdefgh"), "非 ASCII");
        assertRejected(() -> ChatSecretStore.normalizeKey("short"), "长度");
        assertRejected(() -> ChatSecretStore.normalizeKey(" "), "不能为空");
        try { ChatSecretStore.normalizeKey("sk-leak me-please"); fail(); }
        catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().contains("leak")); }
    }

    private static JSONObject obj(String name, String value) {
        try { return new JSONObject().put(name, value); } catch (org.json.JSONException e) { throw new AssertionError(e); }
    }

    private static void assertRejected(Runnable action, String part) {
        try { action.run(); fail("expected failure containing " + part); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains(part)); }
    }
}
