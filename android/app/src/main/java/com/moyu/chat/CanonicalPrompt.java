package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/** Provider-neutral disposable prompt plus a transport-only private continuation permission bit. */
final class CanonicalPrompt {
    final String instructions;
    final JSONArray messages;
    final boolean truncated;
    final int estimatedInputTokens;
    final int inheritedItemCount;
    final int localItemCount;
    final int omittedItemCount;
    /** Full durable semantic projection before budgeting; internal only, never sent directly. */
    final JSONArray projectedMessages;
    /** Standard/Smart may replay matching provider-private state attached to selected assistant messages. */
    final boolean allowProviderContinuation;

    CanonicalPrompt(String instructions, JSONArray messages, boolean truncated,
                    int estimatedInputTokens, int inheritedItemCount, int localItemCount) {
        this(instructions, messages, truncated, estimatedInputTokens, inheritedItemCount, localItemCount,
                Math.max(0, (inheritedItemCount + localItemCount) - (messages == null ? 0 : messages.length())), null, false);
    }

    CanonicalPrompt(String instructions, JSONArray messages, boolean truncated,
                    int estimatedInputTokens, int inheritedItemCount, int localItemCount,
                    int omittedItemCount, JSONArray projectedMessages) {
        this(instructions, messages, truncated, estimatedInputTokens, inheritedItemCount, localItemCount,
                omittedItemCount, projectedMessages, false);
    }

    CanonicalPrompt(String instructions, JSONArray messages, boolean truncated,
                    int estimatedInputTokens, int inheritedItemCount, int localItemCount,
                    int omittedItemCount, JSONArray projectedMessages, boolean allowProviderContinuation) {
        this.instructions = instructions;
        this.messages = messages == null ? new JSONArray() : messages;
        this.truncated = truncated;
        this.estimatedInputTokens = estimatedInputTokens;
        this.inheritedItemCount = inheritedItemCount;
        this.localItemCount = localItemCount;
        this.omittedItemCount = Math.max(0, omittedItemCount);
        this.projectedMessages = projectedMessages;
        this.allowProviderContinuation = allowProviderContinuation;
    }

    JSONObject diagnostics() {
        try {
            return new JSONObject()
                    .put("truncated", truncated)
                    .put("estimatedInputTokens", estimatedInputTokens)
                    .put("inheritedItemCount", inheritedItemCount)
                    .put("localItemCount", localItemCount)
                    .put("omittedItemCount", omittedItemCount)
                    .put("providerContinuation", allowProviderContinuation);
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
