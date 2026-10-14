package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** Hidden helper calls: light calls lower reasoning, every call keeps room for reasoning tokens, failures are recorded. */
public class ModelPreflightTest {
    private static final class Call {
        final String thinkingMode, effort; final int cap;
        Call(ChatDatabase.Provider provider, String effort) { thinkingMode = provider.thinkingMode; cap = provider.maxOutputTokens; this.effort = effort; }
    }

    /** Answers each request from a script: an error code, "hang" (never ends), or the text to stream. */
    private static final class ScriptedTransport implements ChatTransport {
        final List<Call> calls = new ArrayList<>();
        final String[] script;
        ScriptedTransport(String... script) { this.script = script; }
        @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String effort, CanonicalPrompt prompt, Sink sink) {
            String step = script[Math.min(calls.size(), script.length - 1)];
            calls.add(new Call(provider, effort));
            if ("hang".equals(step)) return () -> { };
            if (step.startsWith("http_")) sink.onError(step, "refused", false);
            else { sink.onTextDelta(step); sink.onCompleted(); }
            return () -> { };
        }
    }

    private static ChatDatabase.Provider provider(String protocol, String thinkingMode, String levels) {
        ChatDatabase.Provider p = new ChatDatabase.Provider();
        p.id = "p"; p.protocol = protocol; p.baseUrl = "https://api.example.com"; p.model = "m";
        p.reasoningWire = AnthropicMessagesTransport.PROTOCOL.equals(protocol) ? ReasoningPolicy.WIRE_ANTHROPIC_EFFORT : ReasoningPolicy.WIRE_OPENAI_RESPONSES;
        p.thinkingMode = thinkingMode; p.reasoningLevels = levels; p.maxOutputTokens = 32_000;
        return p;
    }

    private static CanonicalPrompt prompt() throws Exception {
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("text", "route this"));
        return new CanonicalPrompt("instructions", messages, false, 0, 0, 1);
    }

    @Test public void lightCallTurnsThinkingOffAndUsesTheLowestDeclaredEffort() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("{\"ok\":true}");
        ChatDatabase.Provider source = provider(AnthropicMessagesTransport.PROTOCOL, ReasoningPolicy.THINKING_MANUAL, "low,medium,high");
        source.thinkingBudgetTokens = 4_000;
        assertEquals("{\"ok\":true}", ModelPreflight.run(transport, source, "key", "m", prompt(), 320, 5_000));
        Call call = transport.calls.get(0);
        assertEquals(ReasoningPolicy.THINKING_DISABLED, call.thinkingMode);
        assertEquals(ReasoningPolicy.LOW, call.effort);
        assertEquals("thinking off: the answer room is enough", 320, call.cap);
        assertEquals("the session's own configuration is untouched", ReasoningPolicy.THINKING_MANUAL, source.thinkingMode);
    }

    @Test public void lightCallKeepsHeadroomWhenReasoningCannotBeSwitchedOff() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("{}");
        ModelPreflight.run(transport, provider(OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, "minimal,low,high"),
                "key", "m", prompt(), 320, 5_000);
        Call call = transport.calls.get(0);
        assertEquals(ReasoningPolicy.MINIMAL, call.effort);
        assertEquals(320 + ReasoningPolicy.HELPER_REASONING_HEADROOM, call.cap);
    }

    @Test public void lightCallWithoutDeclaredLevelsSendsNoEffort() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("{}");
        ModelPreflight.run(transport, provider(OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, ""),
                "key", "m", prompt(), 700, 5_000);
        assertEquals(ReasoningPolicy.DEFAULT, transport.calls.get(0).effort);
    }

    @Test public void refusedLightCallIsRetriedOnceWithTheDeploymentsOwnConfiguration() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("http_400", "{\"retried\":true}");
        ChatDatabase.Provider source = provider(AnthropicMessagesTransport.PROTOCOL, ReasoningPolicy.THINKING_ADAPTIVE, "low,high");
        assertEquals("{\"retried\":true}", ModelPreflight.run(transport, source, "key", "m", prompt(), 320, 5_000));
        assertEquals(2, transport.calls.size());
        assertEquals(ReasoningPolicy.THINKING_ADAPTIVE, transport.calls.get(1).thinkingMode);
        assertEquals(ReasoningPolicy.DEFAULT, transport.calls.get(1).effort);
    }

    @Test public void otherFailuresAreNotRetried() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("http_429", "{}");
        assertNull(ModelPreflight.run(transport, provider(AnthropicMessagesTransport.PROTOCOL, ReasoningPolicy.THINKING_ADAPTIVE, "low"),
                "key", "m", prompt(), 320, 5_000));
        assertEquals(1, transport.calls.size());
    }

    @Test public void effortCallKeepsTheSessionsReasoningAndAManualBudgetBelowTheCap() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("verdict");
        ChatDatabase.Provider source = provider(AnthropicMessagesTransport.PROTOCOL, ReasoningPolicy.THINKING_MANUAL, "low,high");
        source.thinkingBudgetTokens = 6_000;
        assertEquals("verdict", ModelPreflight.runWithEffort(transport, source, "key", "m", ReasoningPolicy.HIGH, prompt(), 2_000, 5_000));
        Call call = transport.calls.get(0);
        assertEquals(ReasoningPolicy.THINKING_MANUAL, call.thinkingMode);
        assertEquals(ReasoningPolicy.HIGH, call.effort);
        assertEquals(8_000, call.cap);
        ChatDatabase.Provider check = provider(AnthropicMessagesTransport.PROTOCOL, ReasoningPolicy.THINKING_MANUAL, "low,high");
        check.thinkingBudgetTokens = 6_000; check.maxOutputTokens = call.cap;
        ReasoningPolicy.apply(new JSONObject(), check, ReasoningPolicy.HIGH); // the wire accepts budget < cap
    }

    @Test public void capNeverExceedsTheDeclaredDeploymentCeiling() throws Exception {
        ScriptedTransport transport = new ScriptedTransport("{}");
        ChatDatabase.Provider source = provider(OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, "low");
        source.maxOutputTokens = 1_000;
        ModelPreflight.run(transport, source, "key", "m", prompt(), 320, 5_000);
        assertEquals(1_000, transport.calls.get(0).cap);
    }

    @Test public void helperFailuresAreRecordedPerStage() throws Exception {
        TurnUsage usage = new TurnUsage();
        ChatTransport refused = usage.track(new ScriptedTransport("http_429"), TurnUsage.Kind.AUXILIARY, TurnTrace.ROUTE);
        assertNull(ModelPreflight.run(refused, provider(OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, ""),
                "key", "m", prompt(), 320, 5_000));
        assertEquals("http_429", usage.stageFailure(TurnTrace.ROUTE));

        ChatTransport hanging = usage.track(new ScriptedTransport("hang"), TurnUsage.Kind.AUXILIARY, TurnTrace.CHECKPOINT);
        assertNull(ModelPreflight.run(hanging, provider(OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, ""),
                "key", "m", prompt(), 320, 1_000));
        assertEquals("timeout", usage.stageFailure(TurnTrace.CHECKPOINT));

        ChatTransport fine = usage.track(new ScriptedTransport("{}"), TurnUsage.Kind.AUXILIARY, TurnTrace.WEB_PLAN);
        assertNotNull(ModelPreflight.run(fine, provider(OpenAiCompatibleTransport.RESPONSES, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, ""),
                "key", "m", prompt(), 320, 5_000));
        assertNull(usage.stageFailure(TurnTrace.WEB_PLAN));
    }

    @Test public void lightestEffortFollowsTheEffortOrder() {
        assertEquals(ReasoningPolicy.NONE_LEVEL, ReasoningPolicy.lightestEffort("high,none,low"));
        assertEquals(ReasoningPolicy.LOW, ReasoningPolicy.lightestEffort("max,low"));
        assertEquals(ReasoningPolicy.DEFAULT, ReasoningPolicy.lightestEffort(""));
    }

    @Test public void lightThinkingOnlySwitchesWhatTheRouteCanCarry() {
        ChatDatabase.Provider chat = provider(OpenAiCompatibleTransport.CHAT_COMPLETIONS, ReasoningPolicy.THINKING_ENABLED, "");
        chat.dialect = RouteDialect.defaults(OpenAiCompatibleTransport.CHAT_COMPLETIONS);
        assertEquals("a route without the thinking switch keeps the declared mode", ReasoningPolicy.THINKING_ENABLED, ReasoningPolicy.lightThinkingMode(chat));
        ChatDatabase.Provider preset = provider(AnthropicMessagesTransport.PROTOCOL, ReasoningPolicy.THINKING_PROVIDER_DEFAULT, "");
        assertEquals(ReasoningPolicy.THINKING_PROVIDER_DEFAULT, ReasoningPolicy.lightThinkingMode(preset));
    }
}
