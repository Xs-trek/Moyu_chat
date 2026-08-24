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
    final int omittedItemCount;
    /** Full provider-neutral projection before budgeting; internal only, never sent directly. */
    final JSONArray projectedMessages;

    CanonicalPrompt(String instructions, JSONArray messages, boolean truncated,
                    int estimatedInputTokens, int inheritedItemCount, int localItemCount) {
        this(instructions, messages, truncated, estimatedInputTokens, inheritedItemCount, localItemCount,
                Math.max(0, (inheritedItemCount + localItemCount) - (messages == null ? 0 : messages.length())), null);
    }

    CanonicalPrompt(String instructions, JSONArray messages, boolean truncated,
                    int estimatedInputTokens, int inheritedItemCount, int localItemCount,
                    int omittedItemCount, JSONArray projectedMessages) {
        this.instructions = instructions;
        this.messages = messages == null ? new JSONArray() : messages;
        this.truncated = truncated;
        this.estimatedInputTokens = estimatedInputTokens;
        this.inheritedItemCount = inheritedItemCount;
        this.localItemCount = localItemCount;
        this.omittedItemCount = Math.max(0, omittedItemCount);
        this.projectedMessages = projectedMessages;
    }

    JSONObject diagnostics() {
        try {
            return new JSONObject()
                    .put("truncated", truncated)
                    .put("estimatedInputTokens", estimatedInputTokens)
                    .put("inheritedItemCount", inheritedItemCount)
                    .put("localItemCount", localItemCount)
                    .put("omittedItemCount", omittedItemCount);
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
