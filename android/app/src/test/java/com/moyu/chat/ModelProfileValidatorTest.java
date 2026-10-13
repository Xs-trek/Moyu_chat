package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** User-editable deployment fields must fail loudly instead of silently degrading at runtime. */
public class ModelProfileValidatorTest {
    private static ChatDatabase.Route route(String protocol, String dialect) {
        ChatDatabase.Route r = new ChatDatabase.Route();
        r.id = "r"; r.providerId = "p"; r.label = "R"; r.protocol = protocol;
        r.baseUrl = AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? "https://example.com" : "https://example.com/v1";
        r.authMode = ProviderAuth.defaultForProtocol(protocol); r.dialect = dialect;
        return r;
    }

    private static ChatDatabase.ModelProfile profile(String protocol) {
        ChatDatabase.ModelProfile p = new ChatDatabase.ModelProfile();
        p.id = "m"; p.providerId = "p"; p.modelId = "model"; p.displayName = "Model";
        p.protocol = protocol; p.routeId = "r";
        p.reasoningWire = ReasoningPolicy.WIRE_NONE;
        p.reasoningLevels = "";
        p.contextTokens = 32_000; p.inputTokens = 0; p.maxOutputTokens = 4_096;
        p.inputModalities = "text"; p.outputModalities = "text";
        p.toolCapabilities = "";
        p.thinkingMode = ReasoningPolicy.THINKING_PROVIDER_DEFAULT; p.thinkingBudgetTokens = 0;
        p.continuationMode = ProviderContinuationPolicy.NONE;
        p.harnessPreset = HarnessProfile.BALANCED;
        p.clientToolCalling = ClientToolRuntime.NONE;
        return p;
    }

    @Test public void rejectsTyposInsteadOfSilentlyDowngradingHarnessAndClientTools() {
        assertRejected(() -> ModelProfileValidator.requireHarnessPreset("balnced"), "lean / balanced / reinforced");
        assertRejected(() -> ModelProfileValidator.requireClientToolCalling("openai-response-tools",
                OpenAiCompatibleTransport.RESPONSES), "openai-responses-tools");
    }

    @Test public void clientToolCallingMustMatchProtocol() {
        assertRejected(() -> ModelProfileValidator.requireClientToolCalling(ClientToolRuntime.OPENAI_RESPONSES,
                OpenAiCompatibleTransport.CHAT_COMPLETIONS), "不兼容");
        assertEquals(ClientToolRuntime.OPENAI_CHAT, ModelProfileValidator.requireClientToolCalling(
                ClientToolRuntime.OPENAI_CHAT, OpenAiCompatibleTransport.CHAT_COMPLETIONS));
    }

    @Test public void nativeWebCapabilitiesAreProtocolSpecific() {
        assertRejected(() -> ModelProfileValidator.requireNativeWebCapabilities(
                AnthropicMessagesTransport.PROTOCOL, "web_search"), "wire version");
        assertEquals("web_search_20260209,web_fetch_20250910", ModelProfileValidator.requireNativeWebCapabilities(
                AnthropicMessagesTransport.PROTOCOL, "web_search_20260209,web_fetch_20250910"));
        assertRejected(() -> ModelProfileValidator.requireNativeWebCapabilities(
                OpenAiCompatibleTransport.RESPONSES, "web_fetch"), "web_search");
        assertRejected(() -> ModelProfileValidator.requireNativeWebCapabilities(
                OpenAiCompatibleTransport.CHAT_COMPLETIONS, "web_search"), "fallback");
    }

    @Test public void thinkingAndContinuationAreRejectedWhenProtocolCannotUseThem() {
        String chat = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        assertRejected(() -> ModelProfileValidator.requireThinkingMode(
                chat, RouteDialect.defaults(chat), "manual", 1024, 4096), "provider-default");
        assertRejected(() -> ModelProfileValidator.requireThinkingMode(
                chat, RouteDialect.defaults(chat), ReasoningPolicy.THINKING_ENABLED, 0, 4096), "thinking-type");
        RouteDialect thinkingSwitch = RouteDialect.parse(chat, "{\"thinkingParam\":\"thinking-type\"}");
        assertEquals(ReasoningPolicy.THINKING_ENABLED, ModelProfileValidator.requireThinkingMode(
                chat, thinkingSwitch, ReasoningPolicy.THINKING_ENABLED, 0, 4096));
        assertRejected(() -> ModelProfileValidator.requireThinkingMode(
                OpenAiCompatibleTransport.RESPONSES, null, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, 1024, 4096), "必须为 0");
        assertRejected(() -> ModelProfileValidator.requireThinkingMode(
                AnthropicMessagesTransport.PROTOCOL, null, ReasoningPolicy.THINKING_ENABLED, 0, 4096), "Messages");
        assertRejected(() -> ModelProfileValidator.requireContinuationMode(
                OpenAiCompatibleTransport.CHAT_COMPLETIONS, ProviderContinuationPolicy.NATIVE), "请选择 none");
        assertEquals(ProviderContinuationPolicy.NATIVE, ModelProfileValidator.requireContinuationMode(
                OpenAiCompatibleTransport.RESPONSES, ProviderContinuationPolicy.NATIVE));
    }

    @Test public void imageOnlyModalitiesAndBadLimitsAreRejectedClearly() {
        assertRejected(() -> ModelProfileValidator.requireTextModality("Input modalities", "image"), "必须包含 text");
        assertEquals("text,image", ModelProfileValidator.requireTextModality("Input modalities", "image,text"));
        assertRejected(() -> ModelProfileValidator.validateLimits(32_000, 40_000, 4_096), "Max Input");
        assertRejected(() -> ModelProfileValidator.validateLimits(32_000, 0, 32_000), "Max Output");
        assertRejected(() -> ModelProfileValidator.parseInteger("Context Window", "32k"), "必须填写整数");
    }

    @Test public void fullManualProfileValidationNormalizesOnlyValidValues() {
        ChatDatabase.ModelProfile p = profile(OpenAiCompatibleTransport.RESPONSES);
        p.reasoningWire = ReasoningPolicy.WIRE_OPENAI_RESPONSES;
        p.reasoningLevels = "low,high";
        p.inputModalities = "image,text";
        p.toolCapabilities = "web_search";
        p.continuationMode = ProviderContinuationPolicy.NATIVE;
        p.clientToolCalling = ClientToolRuntime.OPENAI_RESPONSES;
        ModelProfileValidator.validateResolvedProfile(route(OpenAiCompatibleTransport.RESPONSES, "{}"), p);
        assertEquals("text,image", p.inputModalities);
        assertEquals("web_search", p.toolCapabilities);
        assertEquals(HarnessProfile.BALANCED, p.harnessPreset);
    }

    @Test public void serviceTierVocabularyIsProtocolSpecific() {
        assertEquals("flex", ModelProfileValidator.requireServiceTier(OpenAiCompatibleTransport.RESPONSES, "Flex"));
        assertEquals("", ModelProfileValidator.requireServiceTier(AnthropicMessagesTransport.PROTOCOL, " "));
        assertEquals("standard_only", ModelProfileValidator.requireServiceTier(AnthropicMessagesTransport.PROTOCOL, "standard_only"));
        assertRejected(() -> ModelProfileValidator.requireServiceTier(AnthropicMessagesTransport.PROTOCOL, "flex"), "auto / standard_only");
        assertRejected(() -> ModelProfileValidator.requireServiceTier(OpenAiCompatibleTransport.CHAT_COMPLETIONS, "turbo"), "priority");
    }

    @Test public void anthropicRoutesTakeTheSdkRootWithoutV1() {
        ChatDatabase.Route r = route(AnthropicMessagesTransport.PROTOCOL, "{}");
        r.baseUrl = "https://example.com/v1";
        assertRejected(() -> ModelProfileValidator.validateRoute(r), "不含末尾的 /v1");
        r.baseUrl = "https://example.com/anthropic/";
        ModelProfileValidator.validateRoute(r);
        assertEquals("https://example.com/anthropic", r.baseUrl);
        assertEquals("https://example.com/anthropic/v1/messages", ProviderEndpoint.requestUrl(r.baseUrl, r.protocol));
    }

    @Test public void routeDialectRejectsKeysOfAnotherProtocol() {
        ChatDatabase.Route r = route(OpenAiCompatibleTransport.RESPONSES, "{\"streamUsage\":true}");
        assertRejected(() -> ModelProfileValidator.validateRoute(r), "streamUsage");
        ChatDatabase.Route chat = route(OpenAiCompatibleTransport.CHAT_COMPLETIONS, "{\"outputCap\":\"max_output_tokens\"}");
        assertRejected(() -> ModelProfileValidator.validateRoute(chat), "max_completion_tokens");
    }

    private static void assertRejected(Runnable action, String messagePart) {
        try {
            action.run();
            fail("expected validation failure containing: " + messagePart);
        } catch (IllegalArgumentException expected) {
            assertTrue("unexpected message: " + expected.getMessage(), expected.getMessage().contains(messagePart));
        }
    }
}
