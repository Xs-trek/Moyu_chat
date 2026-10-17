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
            SETTING_OFF = "setting_off", NO_CLIENT_TOOLS = "no_client_tools", WEB_OFF = "web_off", NO_WEB_TOOLS = "no_web_tools";

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
     * @param hostedSearch  the deployment declares the provider's hosted Web search on this protocol
     */
    static TurnModules resolve(TurnConfig c, ContextMode.Policy mode, HarnessPolicyResolver.Policy harness,
                               boolean clientCalling, boolean globalMemory, boolean hostedSearch) {
        boolean minimal = ContextMode.MINIMAL.equals(mode.effectiveLabel);
        boolean smart = ContextMode.SMART.equals(mode.effectiveLabel);

        Decision router = !smart ? Decision.deny(MODE_OFF) : !mode.routerEnabled ? Decision.deny(HARNESS_OFF)
                : switched(c, HelperSwitches.ROUTER);
        // Compaction includes clearing earlier tool results (spec C2); off, neither runs and no summary is used (D10).
        Decision compaction = !mode.checkpointEnabled ? Decision.deny(MODE_OFF) : switched(c, HelperSwitches.COMPACTION);
        Decision recall = minimal ? Decision.deny(MODE_OFF) : !clientCalling ? Decision.deny(NO_CLIENT_TOOLS)
                : switched(c, HelperSwitches.RECALL);
        boolean webOff = ChatTurnOptions.WEB_OFF.equals(ChatTurnOptions.normalizeWebMode(c.webMode));
        // Spec M1, D5: the model's own Web tools are Web itself, in every mode, whenever the Web mode is not off and the
        // deployment has a way to search. The planner model only serves the host fallback and never runs in Minimal.
        Decision webTools = webOff ? Decision.deny(WEB_OFF) : !(clientCalling || hostedSearch) ? Decision.deny(NO_WEB_TOOLS) : Decision.allow();
        Decision webPlanner = webOff ? Decision.deny(WEB_OFF) : minimal ? Decision.deny(MODE_OFF) : switched(c, HelperSwitches.WEB_PLAN);
        Decision subagent = !clientCalling ? Decision.deny(NO_CLIENT_TOOLS)
                : !HarnessPolicyResolver.SUBAGENT_AUTO.equals(harness.subagentMode) ? Decision.deny(minimal ? MODE_OFF : SESSION_OFF)
                : switched(c, HelperSwitches.SUBAGENT);
        Decision verify = !mode.verifierEnabled ? Decision.deny(smart ? HARNESS_OFF : MODE_OFF)
                : !harness.verifierAvailable ? Decision.deny(HARNESS_OFF) : switched(c, HelperSwitches.VERIFY);
        return new TurnModules(router, compaction, recall, webPlanner, webTools, subagent, verify,
                memory(mode, globalMemory, clientCalling));
    }

    /** Cross-chat search (spec C5, D7): a tool, so the mode carries it, the global setting is on and the deployment calls tools. */
    static Decision memory(ContextMode.Policy mode, boolean globalMemory, boolean clientCalling) {
        return !mode.memoryCallerEnabled ? Decision.deny(MODE_OFF) : !globalMemory ? Decision.deny(SETTING_OFF)
                : !clientCalling ? Decision.deny(NO_CLIENT_TOOLS) : Decision.allow();
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
