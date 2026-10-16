package com.moyu.chat;

import org.json.JSONObject;

/**
 * Whether each module may run in this turn, decided once at accept time (spec M4) from the context mode, the
 * harness preset, the user's helper switches, the deployment's tool calling and the Web mode. Later stages only
 * read it; conditions known only while running (over a threshold, something omitted, a plan) are checked by the
 * module's own stage, behind its "allowed" here. Every "no" carries a fixed reason code for the trace (spec R5).
 */
final class TurnModules {
    /** Reason codes of a module that does not run. */
    static final String MODE_OFF = "mode_off", HARNESS_OFF = "harness_off", SESSION_OFF = "session_off",
            SETTING_OFF = "setting_off", NO_CLIENT_TOOLS = "no_client_tools", WEB_NOT_AUTO = "web_not_auto";

    static final class Decision {
        final boolean on;
        /** Empty when on. */
        final String reason;
        private Decision(boolean on, String reason) { this.on = on; this.reason = on ? "" : reason; }
        static Decision allow() { return new Decision(true, ""); }
        static Decision deny(String reason) { return new Decision(false, reason); }
    }

    final Decision router, compaction, recall, webPlanner, webTools, subagent, verify, memory;

    private TurnModules(Decision router, Decision compaction, Decision recall, Decision webPlanner, Decision webTools,
                        Decision subagent, Decision verify, Decision memory) {
        this.router = router; this.compaction = compaction; this.recall = recall; this.webPlanner = webPlanner;
        this.webTools = webTools; this.subagent = subagent; this.verify = verify; this.memory = memory;
    }

    /**
     * @param clientCalling the deployment declares client tool calling on this protocol
     * @param globalMemory  the cross-chat memory setting is on
     */
    static TurnModules resolve(TurnConfig c, ContextMode.Policy mode, HarnessPolicyResolver.Policy harness,
                               boolean clientCalling, boolean globalMemory) {
        boolean minimal = ContextMode.MINIMAL.equals(mode.effectiveLabel);
        boolean smart = ContextMode.SMART.equals(mode.effectiveLabel);

        Decision router = !smart ? Decision.deny(MODE_OFF) : !mode.routerEnabled ? Decision.deny(HARNESS_OFF)
                : switched(c, HelperSwitches.ROUTER);
        // Compaction includes clearing earlier tool results (spec C2); off, neither runs and no summary is used (D10).
        Decision compaction = !mode.checkpointEnabled ? Decision.deny(MODE_OFF) : switched(c, HelperSwitches.COMPACTION);
        Decision recall = minimal ? Decision.deny(MODE_OFF) : !clientCalling ? Decision.deny(NO_CLIENT_TOOLS)
                : switched(c, HelperSwitches.RECALL);
        // Current behavior (spec M1 changes it with D5): the planner switch alone decides; the plan decides whether to call.
        Decision webPlanner = switched(c, HelperSwitches.WEB_PLAN);
        Decision webTools = minimal ? Decision.deny(MODE_OFF) : !clientCalling ? Decision.deny(NO_CLIENT_TOOLS)
                : !ChatTurnOptions.WEB_AUTO.equals(ChatTurnOptions.normalizeWebMode(c.webMode)) ? Decision.deny(WEB_NOT_AUTO)
                : switched(c, HelperSwitches.WEB_TOOLS);
        Decision subagent = !clientCalling ? Decision.deny(NO_CLIENT_TOOLS)
                : !HarnessPolicyResolver.SUBAGENT_AUTO.equals(harness.subagentMode) ? Decision.deny(minimal ? MODE_OFF : SESSION_OFF)
                : switched(c, HelperSwitches.SUBAGENT);
        Decision verify = !mode.verifierEnabled ? Decision.deny(smart ? HARNESS_OFF : MODE_OFF)
                : !harness.verifierAvailable ? Decision.deny(HARNESS_OFF) : switched(c, HelperSwitches.VERIFY);
        return new TurnModules(router, compaction, recall, webPlanner, webTools, subagent, verify,
                memory(mode, globalMemory));
    }

    /** Cross-chat memory injection: the mode carries it and the global setting is on. */
    static Decision memory(ContextMode.Policy mode, boolean globalMemory) {
        return !mode.memoryCallerEnabled ? Decision.deny(MODE_OFF) : !globalMemory ? Decision.deny(SETTING_OFF) : Decision.allow();
    }

    private static Decision switched(TurnConfig c, String helper) {
        return c.helperOn(helper) ? Decision.allow() : Decision.deny(HelperSwitches.USER_OFF);
    }

    /** {module: true} or {module: reason}: recorded on the accept stage of the trace. */
    JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            put(out, "router", router); put(out, "compaction", compaction); put(out, "recall", recall);
            put(out, "webPlanner", webPlanner); put(out, "webTools", webTools); put(out, "subagent", subagent);
            put(out, "verify", verify); put(out, "memory", memory);
        } catch (Exception ignored) { }
        return out;
    }

    private static void put(JSONObject out, String name, Decision d) throws org.json.JSONException {
        if (d.on) out.put(name, true); else out.put(name, d.reason);
    }
}
