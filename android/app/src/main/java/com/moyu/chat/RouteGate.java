package com.moyu.chat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-route cap on concurrent main generation calls (route dialect {@code maxConcurrent}; 0 = no cap).
 * Non-blocking: a turn that finds the route full is queued and started when a slot frees, so no worker
 * thread waits. Helper calls are not gated; they are short and belong to turns already holding a slot.
 */
final class RouteGate {
    private final Map<String, Integer> active = new HashMap<>();
    private final Map<String, Integer> limits = new HashMap<>();
    private final Map<String, ArrayDeque<Runnable>> waiting = new HashMap<>();

    /**
     * Takes a slot and returns true, or queues {@code whenFree} (run later with the slot already taken) and
     * returns false. A limit of 0 or less never queues and takes no slot.
     */
    synchronized boolean enter(String routeId, int limit, Runnable whenFree) {
        if (routeId == null || limit <= 0) return true;
        limits.put(routeId, limit);
        int used = active.getOrDefault(routeId, 0);
        if (used < limit) { active.put(routeId, used + 1); return true; }
        waiting.computeIfAbsent(routeId, k -> new ArrayDeque<>()).add(whenFree);
        return false;
    }

    /** Drops a queued turn that was stopped before it got a slot. */
    synchronized boolean withdraw(String routeId, Runnable whenFree) {
        ArrayDeque<Runnable> queue = waiting.get(routeId);
        return queue != null && queue.remove(whenFree);
    }

    /** Frees a slot taken by {@link #enter}; the next queued turn, if any, starts with it. */
    void leave(String routeId) {
        if (routeId == null) return;
        Runnable next = null;
        synchronized (this) {
            int used = Math.max(0, active.getOrDefault(routeId, 0) - 1);
            ArrayDeque<Runnable> queue = waiting.get(routeId);
            if (queue != null && !queue.isEmpty() && used < limits.getOrDefault(routeId, 1)) { next = queue.poll(); used++; }
            if (used == 0) active.remove(routeId); else active.put(routeId, used);
        }
        if (next != null) next.run();
    }

    synchronized int active(String routeId) { return active.getOrDefault(routeId, 0); }
    synchronized int queued(String routeId) { ArrayDeque<Runnable> q = waiting.get(routeId); return q == null ? 0 : q.size(); }
}
