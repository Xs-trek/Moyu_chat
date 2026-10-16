package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;

/**
 * Everything one accepted user turn decides on its way to the provider. Preparation stages fill it in
 * order on the preparation pool; the provider round loop and verification read the settled values.
 */
final class TurnRun {
    final String id;
    final String input;
    final TurnConfig config;
    final TurnControl control;
    final ChatDatabase.Provider provider;
    final String key;
    final ChatDatabase.Session session;
    final JSONArray pendingMedia;

    /** Route whose concurrency slot this turn holds (RouteGate); released exactly once when the turn ends. */
    final java.util.concurrent.atomic.AtomicReference<String> routeSlot = new java.util.concurrent.atomic.AtomicReference<>();

    /** Stage-tagged transports: usage is attributed to the stage that issued the call. */
    ChatTransport mainTransport, routeTransport, checkpointTransport, webPlanTransport, verifyTransport, repairTransport, subagentTransport;

    ContextMode.Policy contextPolicy;
    HarnessPolicyResolver.Policy adaptivePolicy;
    TaskProfile taskProfile;
    /** Which modules may run this turn, decided once at accept (spec M4). */
    TurnModules modules;
    boolean needsRouter, mayPrepare, allowProviderContinuation;
    /** Client web tools offered although the Web plan said no (Auto, on demand); the model decides whether to search. */
    boolean webOnDemand;
    /** history_recall is offered: older turns of this Chat are out of the final prompt (dropped or summarized); see TurnPipeline.settleRecall. */
    boolean recallOffered;

    JSONObject live;
    String streamKey;
    long requestStartedNanos;

    CanonicalPrompt prompt;
    ContextBuilder.CompactionPlan compactionPlan;
    boolean needsCheckpoint, preparing;

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

    TurnRun(String id, String input, TurnConfig config, TurnControl control, ChatDatabase.Session session, JSONArray pendingMedia) {
        this.id = id;
        this.input = input;
        this.config = config;
        this.control = control;
        this.provider = config.provider;
        this.key = config.apiKey;
        this.session = session;
        this.pendingMedia = pendingMedia;
    }

    TurnTrace trace() { return control.trace; }
}
