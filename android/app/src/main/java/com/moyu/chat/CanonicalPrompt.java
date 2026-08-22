package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/** Provider-neutral, disposable prompt built from durable Moyu state for one request. */
final class CanonicalPrompt {
    final String instructions;
    final JSONArray messages;
    final boolean truncated;
    final int estimatedInputTokens;
    final int inheritedItemCount;
    final int localItemCount;

    CanonicalPrompt(String instructions, JSONArray messages, boolean truncated,
                    int estimatedInputTokens, int inheritedItemCount, int localItemCount) {
        this.instructions = instructions;
        this.messages = messages;
        this.truncated = truncated;
        this.estimatedInputTokens = estimatedInputTokens;
        this.inheritedItemCount = inheritedItemCount;
        this.localItemCount = localItemCount;
    }

    JSONObject diagnostics() {
        try {
            return new JSONObject()
                    .put("truncated", truncated)
                    .put("estimatedInputTokens", estimatedInputTokens)
                    .put("inheritedItemCount", inheritedItemCount)
                    .put("localItemCount", localItemCount);
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
