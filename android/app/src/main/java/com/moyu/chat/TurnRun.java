package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;

/**
 * Everything one accepted user turn decides on its way to the provider. Preparation stages fill it in
 * order on the preparation pool; the provider round loop and verification read the settled values.
 */
final class TurnRun {
    /** The session (every database read and write). */
    final String id;
    /**
     * The runtime key (busy flags, stop handle, pending choices): the session id for an ordinary turn, "session#k" for
     * the k-th answer of a side-by-side group (spec D16, D18), so several turns of one session can run at once.
     */
    final String run;
    final String input;
    final TurnConfig config;
    final TurnControl control;
    final ChatDatabase.Provider provider;
    final String key;
    final ChatDatabase.Session session;
    final JSONArray pendingMedia;

    /** Route whose concurrency slot this turn holds (RouteGate); released exactly once when the turn ends. */
    final java.util.concurrent.atomic.AtomicReference<String> routeSlot = new java.util.concurrent.atomic.AtomicReference<>();

    /** Stage-tagged transport of the main call: usage is attributed to the stage that issued the call. */
    ChatTransport mainTransport;
    /** Helper stages: the helper model when it is used for that helper (spec D15), else the session's model; stage-tagged too. */
    HelperModel.Call routeCall, checkpointCall, webPlanCall, verifyCall, repairCall, subagentCall;

    ContextMode.Policy contextPolicy;
    HarnessPolicyResolver.Policy adaptivePolicy;
    TaskProfile taskProfile;
    /** Which modules may run this turn, decided once at accept (spec M4). */
    TurnModules modules;
    boolean needsRouter, mayPrepare, allowProviderContinuation;
    /** Spec D5: the answering model has a Web tool this turn (hosted search or client web tools) and decides whether to search. */
    boolean modelWeb;
    /** history_recall is offered: older turns of this Chat are out of the final prompt (dropped or summarized); see TurnPipeline.settleRecall. */
    boolean recallOffered;

    JSONObject live;
    String streamKey;
    long requestStartedNanos;

    CanonicalPrompt prompt;
    /** Compaction this turn waits for or runs before sending (spec C3): the prompt drops history the summary would cover. */
    boolean needsCheckpoint, preparing;
    /** This turn's client tool calls and results, stored on its answer (spec T3). */
    JSONArray toolCalls = new JSONArray();
    /**
     * Spec M6, D12: time spent in helper stages (Router, waiting for or running compaction, verify, repair, re-check);
     * the main call and tool rounds do not count. Past {@link #HELPER_BUDGET_MS}, repairs and re-checks not yet started are skipped.
     */
    static final long HELPER_BUDGET_MS = 90_000L;
    /** The budget in force; tests shorten it. */
    static volatile long helperBudgetMs = HELPER_BUDGET_MS;
    private long helperNanos;
    synchronized void addHelperTime(long startedNanos) { helperNanos += Math.max(0L, System.nanoTime() - startedNanos); }
    synchronized long helperMs() { return helperNanos / 1_000_000L; }
    boolean helperBudgetLeft() { return helperMs() < helperBudgetMs; }

    /** The last main call's reported input (with cache) and output tokens; 0 when the provider reported none (spec C3). */
    int lastCallInputTokens, lastCallOutputTokens;

    MoyuWebService.FollowUpContext webFollowUp;
    boolean webPlanning;
    WebOrchestrator.Plan webPlan;

    String hostedCapabilities;
    boolean nativeSearch, nativeFetch, clientCalling, hostManagedWeb, providerHostedThisTurn;
    final LinkedHashSet<String> clientToolNames = new LinkedHashSet<>();
    String clientToolCsv = "";
    int clientToolSchemaTokens;
    CanonicalPrompt preWebPrompt;
    ChatTurnOptions webOptions;
    MoyuWebService.Prepared preparedWeb = MoyuWebService.Prepared.none();

    TurnRun(String run, String input, TurnConfig config, TurnControl control, ChatDatabase.Session session, JSONArray pendingMedia) {
        this.run = run;
        this.id = sessionOf(run);
        this.input = input;
        this.config = config;
        this.control = control;
        this.provider = config.provider;
        this.key = config.apiKey;
        this.session = session;
        this.pendingMedia = pendingMedia;
    }

    TurnTrace trace() { return control.trace; }

    /** The session of a runtime key ("session" or "session#k"). */
    static String sessionOf(String run) {
        int hash = run == null ? -1 : run.indexOf('#');
        return hash < 0 ? run : run.substring(0, hash);
    }
}
