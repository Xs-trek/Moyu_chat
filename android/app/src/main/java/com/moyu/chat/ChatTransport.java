package com.moyu.chat;

interface ChatTransport {
    interface Sink {
        /** Coarse transport progress such as connected/reasoning/output. */
        default void onStage(String stage) { }
        /** Provider-emitted reasoning/thinking text, kept separate from visible answer text. */
        default void onReasoningDelta(String delta) { }
        void onTextDelta(String delta);
        default void onImage(String mimeType, String base64, String fileName) { }
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

    default Turn stream(ChatDatabase.Provider provider, String apiKey, String model, CanonicalPrompt prompt, Sink sink) {
        return stream(provider, apiKey, model, ReasoningPolicy.DEFAULT, prompt, sink);
    }
}
