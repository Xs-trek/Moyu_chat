package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** User-editable deployment fields must fail loudly instead of silently degrading at runtime. */
public class ModelProfileValidatorTest {
    private static ChatDatabase.Provider provider() {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.displayName = "P"; p.baseUrl = "https://example.com/v1";
        return p;
    }

    private static ChatDatabase.ModelProfile profile(String protocol) {
        ChatDatabase.ModelProfile p = new ChatDatabase.ModelProfile();
        p.id = "m"; p.providerId = "p"; p.modelId = "model"; p.displayName = "Model";
        p.protocol = protocol;
        p.authMode = ProviderAuth.defaultForProtocol(protocol);
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
        assertRejected(() -> ModelProfileValidator.requireThinkingMode(
                OpenAiCompatibleTransport.CHAT_COMPLETIONS, "manual", 1024, 4096), "仅适用于 anthropic-messages");
        assertRejected(() -> ModelProfileValidator.requireThinkingMode(
                OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, 1024, 4096), "budget=0");
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
        ModelProfileValidator.validateResolvedProfile(provider(), p);
        assertEquals("text,image", p.inputModalities);
        assertEquals("web_search", p.toolCapabilities);
        assertEquals(HarnessProfile.BALANCED, p.harnessPreset);
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
