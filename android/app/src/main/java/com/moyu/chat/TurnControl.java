package com.moyu.chat;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One cancellation owner spans accepted-send preparation, provider streaming and Smart post-processing. */
final class TurnControl implements ClientToolRuntime.TurnCancellation {
    final TurnUsage usage = new TurnUsage();
    final TurnTrace trace = new TurnTrace();
    final String usageKey = "usage:turn:" + UUID.randomUUID();
    final boolean[] usagePersisted = new boolean[]{false};
    JSONObject usageState;
    long requestStartedNanos;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<Thread> taskThread = new AtomicReference<>();
    private final AtomicReference<ChatTransport.Turn> transportTurn = new AtomicReference<>();
    private final java.util.Set<okhttp3.Call> webCalls = ConcurrentHashMap.newKeySet();

    @Override public boolean isCancelled() { return cancelled.get(); }
    @Override public void bind(okhttp3.Call call) {
        if (call == null) return;
        webCalls.add(call);
        if (cancelled.get()) call.cancel();
    }
    @Override public void clear(okhttp3.Call call) { if (call != null) webCalls.remove(call); }
    void bindCurrentThread() {
        Thread current = Thread.currentThread();
        taskThread.set(current);
        if (cancelled.get()) current.interrupt();
    }
    void clearCurrentThread() {
        Thread current = Thread.currentThread();
        taskThread.compareAndSet(current, null);
        if (cancelled.get()) Thread.interrupted(); // do not leak cancellation into the pooled worker's next task.
    }
    void bindTransport(ChatTransport.Turn turn) {
        transportTurn.set(turn);
        if (turn != null && cancelled.get()) try { turn.cancel(); } catch (Exception ignored) { }
    }
    void clearTransport(ChatTransport.Turn turn) { if (turn != null) transportTurn.compareAndSet(turn, null); }
    void cancel() {
        cancelled.set(true);
        ChatTransport.Turn turn = transportTurn.get();
        if (turn != null) try { turn.cancel(); } catch (Exception ignored) { }
        for (okhttp3.Call call : webCalls) if (call != null) try { call.cancel(); } catch (Exception ignored) { }
        Thread thread = taskThread.get();
        if (thread != null) thread.interrupt();
    }
}
