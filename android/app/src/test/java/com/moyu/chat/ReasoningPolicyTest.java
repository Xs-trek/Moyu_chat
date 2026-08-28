package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReasoningPolicyTest {
    @Test public void openAiCompatibleCapabilityRangeAcceptsMinimalWhenExplicitlyEnabled() {
        ChatDatabase.Provider provider = provider(OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.WIRE_OPENAI_RESPONSES);
        assertEquals(ReasoningPolicy.MINIMAL, ReasoningPolicy.normalizeEffort("minimal"));
        ReasoningPolicy.validateLevelsForProvider(provider, "minimal,low,high");
        ReasoningPolicy.validateEffortForProvider(provider, "minimal,low,high", "minimal");
    }

    @Test public void reasoningWireMustMatchSelectedProtocol() {
        assertRejected(() -> ReasoningPolicy.normalizeWire(ReasoningPolicy.WIRE_OPENAI_CHAT_EFFORT, OpenAiCompatibleTransport.RESPONSES));
        assertRejected(() -> ReasoningPolicy.normalizeWire(ReasoningPolicy.WIRE_ANTHROPIC_EFFORT, OpenAiCompatibleTransport.CHAT_COMPLETIONS));
        assertRejected(() -> ReasoningPolicy.normalizeWire(ReasoningPolicy.WIRE_OPENAI_RESPONSES, AnthropicMessagesTransport.PROTOCOL));
    }

    @Test public void advertisedReasoningLevelsRequireAnEnabledWire() {
        ChatDatabase.Provider provider = provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS, ReasoningPolicy.WIRE_NONE);
        assertRejected(() -> ReasoningPolicy.validateLevelsForProvider(provider, "low,high"));
        ReasoningPolicy.validateLevelsForProvider(provider, "");
    }

    @Test public void anthropicEffortRangeDoesNotConflateThinkingWithNoneOrMinimal() {
        ChatDatabase.Provider provider = provider(AnthropicMessagesTransport.PROTOCOL, ReasoningPolicy.WIRE_ANTHROPIC_EFFORT);
        ReasoningPolicy.validateLevelsForProvider(provider, "low,medium,high,xhigh,max");
        ReasoningPolicy.validateEffortForProvider(provider, "low,medium,high,xhigh,max", "high");
        assertRejected(() -> ReasoningPolicy.validateLevelsForProvider(provider, "none,high"));
        assertRejected(() -> ReasoningPolicy.validateLevelsForProvider(provider, "minimal,high"));
    }

    private static ChatDatabase.Provider provider(String protocol, String reasoningWire) {
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.protocol = protocol;
        provider.reasoningWire = reasoningWire;
        return provider;
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("expected rejection");
        } catch (IllegalArgumentException expected) { }
    }
}
