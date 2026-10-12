package com.moyu.chat;

import org.json.JSONObject;

interface ChatTransport {
    interface Sink {
        /** Coarse transport progress such as connected/reasoning/output. */
        default void onStage(String stage) { }
        /** Provider-emitted reasoning/thinking text, kept separate from visible answer text. */
        default void onReasoningDelta(String delta) { }
        /** Replayable provider-private reasoning/thinking object. Never expose this object to WebView/Snapshot. */
        default void onProviderContinuation(JSONObject state) { }
        void onTextDelta(String delta);
        default void onImage(String mimeType, String base64, String fileName) { }
        /** Hosted provider tool lifecycle; currently web_search / web_fetch. */
        default void onToolActivity(String tool, String state) { }
        /** A provider-attributed public web source. Raw fetched/search content is never persisted here. */
        default void onWebSource(String url, String title) { }
        /** Terminal for one provider round that requested one or more client-executed tools. */
        default void onClientToolBatch(org.json.JSONArray calls) { onError("unsupported_tool_call", "Provider requested a client tool but this caller has no tool runtime", false); }
        void onUsage(Integer inputTokens, Integer outputTokens);
        /**
         * Provider-neutral cache usage details. cacheHitBaseTokens is the logical denominator for
         * cache-hit percentage when the provider exposes enough information; null means unknown.
         */
        default void onUsageDetails(Integer inputTokens, Integer outputTokens, Integer cachedInputTokens,
                                    Integer cacheWriteInputTokens, Integer cacheHitBaseTokens, Integer reasoningTokens) {
            onUsage(inputTokens, outputTokens);
        }
        void onCompleted();
        void onError(String code, String summary, boolean retryable);
    }
    interface Turn { void cancel(); }

    Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort, CanonicalPrompt prompt, Sink sink);

    /** Main user turns may opt into provider-hosted web tools. Preflight callers use the options-free overload and therefore NONE. */
    default Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort,
                        CanonicalPrompt prompt, ChatTurnOptions options, Sink sink) {
        return stream(provider, apiKey, model, reasoningEffort, prompt, sink);
    }

    default Turn stream(ChatDatabase.Provider provider, String apiKey, String model, CanonicalPrompt prompt, Sink sink) {
        return stream(provider, apiKey, model, ReasoningPolicy.DEFAULT, prompt, sink);
    }
}
