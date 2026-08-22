package com.moyu.chat;

interface ChatTransport {
    interface Sink {
        void onTextDelta(String delta);
        void onUsage(Integer inputTokens, Integer outputTokens);
        void onCompleted();
        void onError(String code, String summary, boolean retryable);
    }
    interface Turn { void cancel(); }

    Turn stream(ChatDatabase.Provider provider, String apiKey, String model, CanonicalPrompt prompt, Sink sink);
}
