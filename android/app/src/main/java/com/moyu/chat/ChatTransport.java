package com.moyu.chat;

interface ChatTransport {
    interface Sink {
        void onTextDelta(String delta);
        default void onImage(String mimeType, String base64, String fileName) { }
        void onUsage(Integer inputTokens, Integer outputTokens);
        void onCompleted();
        void onError(String code, String summary, boolean retryable);
    }
    interface Turn { void cancel(); }

    Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort, CanonicalPrompt prompt, Sink sink);

    default Turn stream(ChatDatabase.Provider provider, String apiKey, String model, CanonicalPrompt prompt, Sink sink) {
        return stream(provider, apiKey, model, ReasoningPolicy.DEFAULT, prompt, sink);
    }
}
