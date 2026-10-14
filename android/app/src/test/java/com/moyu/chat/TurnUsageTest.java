package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class TurnUsageTest {
    private static final ChatTransport.Sink DISCARD = new ChatTransport.Sink() {
        @Override public void onTextDelta(String text) { }
        @Override public void onUsage(Integer input, Integer output) { }
        @Override public void onCompleted() { }
        @Override public void onError(String code, String summary, boolean retryable) { }
        @Override public void onClientToolBatch(JSONArray calls) { }
    };

    private static ChatTransport fake(Consumer<ChatTransport.Sink> action) {
        return (provider, key, model, effort, prompt, sink) -> { action.accept(sink); return () -> {}; };
    }

    private static void request(TurnUsage usage, TurnUsage.Kind kind, Consumer<ChatTransport.Sink> action) {
        usage.track(fake(action), kind).stream(null, "", "", "", null, DISCARD);
    }

    @Test public void cumulativeCallbacksAndSeparateRoundsAreCountedExactlyOnce() throws Exception {
        TurnUsage usage = new TurnUsage();
        request(usage, TurnUsage.Kind.MAIN, sink -> {
            sink.onUsage(7, 1);
            sink.onUsageDetails(7, 2, 3, null, 7, 1);
            sink.onUsage(7, 2);
            sink.onClientToolBatch(new JSONArray());
        });
        request(usage, TurnUsage.Kind.SUBAGENT, sink -> { sink.onUsage(5, 2); sink.onCompleted(); });
        request(usage, TurnUsage.Kind.MAIN, sink -> { sink.onUsage(11, 3); sink.onCompleted(); });
        request(usage, TurnUsage.Kind.AUXILIARY, sink -> { sink.onUsage(13, 4); sink.onCompleted(); });
        JSONObject result = new JSONObject(); usage.project(result);
        assertEquals(18, result.getInt("inputTokens"));
        assertEquals(5, result.getInt("outputTokens"));
        assertEquals(36, result.getInt("totalInputTokens"));
        assertEquals("context fill is what the last main call sent, not the sum", 11, result.getInt("lastMainInputTokens"));
        assertEquals(11, result.getInt("totalOutputTokens"));
        assertEquals(4, result.getInt("modelCallCount"));
        assertEquals(1, result.getInt("auxiliaryCallCount"));
        assertEquals(1, result.getInt("subagentCallCount"));
        assertFalse(result.getBoolean("usageIncomplete"));
    }

    @Test public void failedOrEmptyPreflightKeepsReportedUsageWithoutInventingMissingOutput() throws Exception {
        TurnUsage usage = new TurnUsage();
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        ChatTransport transport = usage.track(fake(sink -> {
            sink.onUsage(19, null); sink.onError("failed", "failed after usage", false);
        }), TurnUsage.Kind.AUXILIARY);
        assertNull(ModelPreflight.run(transport, provider, "test", "mock", null, 128, 1000));
        request(usage, TurnUsage.Kind.MAIN, sink -> { sink.onUsage(7, 0); sink.onCompleted(); });
        JSONObject result = new JSONObject(); usage.project(result);
        assertEquals(26, result.getInt("totalInputTokens"));
        assertEquals(0, result.getInt("totalOutputTokens"));
        assertEquals(19, result.getInt("auxiliaryInputTokens"));
        assertFalse(result.has("auxiliaryOutputTokens"));
        assertTrue(result.getBoolean("usageIncomplete"));
    }

    @Test public void cancellationAndTimeoutKeepUsageAndIgnoreLateCallbacks() throws Exception {
        TurnUsage usage = new TurnUsage();
        ChatTransport.Sink[] callback = new ChatTransport.Sink[1];
        AtomicBoolean cancelled = new AtomicBoolean();
        ChatTransport delegate = (provider, key, model, effort, prompt, sink) -> {
            callback[0] = sink; sink.onUsage(9, 1);
            return () -> cancelled.set(true);
        };
        assertNull(ModelPreflight.run(usage.track(delegate, TurnUsage.Kind.AUXILIARY),
                new ChatDatabase.Provider(), "test", "mock", null, 128, 1000));
        assertTrue(cancelled.get());
        callback[0].onUsage(999, 999);
        JSONObject result = new JSONObject(); usage.project(result);
        assertEquals(9, result.getInt("totalInputTokens"));
        assertEquals(1, result.getInt("totalOutputTokens"));
        assertFalse(result.getBoolean("usageIncomplete"));
    }

    @Test public void unreportedAndSynchronousFailureAreVisibleAsUnknownCalls() throws Exception {
        TurnUsage usage = new TurnUsage();
        request(usage, TurnUsage.Kind.SUBAGENT, ChatTransport.Sink::onCompleted);
        try {
            request(usage, TurnUsage.Kind.AUXILIARY, sink -> { throw new IllegalStateException("start failed"); });
            fail("start failure must still propagate");
        } catch (IllegalStateException expected) { }
        JSONObject result = new JSONObject(); usage.project(result);
        assertEquals(2, result.getInt("modelCallCount"));
        assertFalse(result.has("totalInputTokens"));
        assertFalse(result.has("totalOutputTokens"));
        assertTrue(result.getBoolean("usageIncomplete"));
    }
}
