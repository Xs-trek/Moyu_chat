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
    /** History items the compaction summary at the start stands in for (spec C3); 0 when no summary is used. */
    int summarizedItems;
    /** Earlier tool results cleared to save room (spec C2) and the estimated tokens that freed. */
    int clearedToolResults, clearedToolTokens;

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

    /**
     * Whether the model already sees {@code text} in this prompt (spec T4): judged on the prompt actually sent, by
     * containment, because a retrieved excerpt is a chunk of one message and counts as seen when that message was sent.
     */
    boolean carries(String text) { return carries(messages, text); }

    /** The one "already seen" check (spec T4, R2); see {@link #carries(String)}. */
    static boolean carries(JSONArray messages, String text) {
        if (messages == null || text == null || text.isEmpty()) return false;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            if (message.optString("text", "").contains(text)) return true;
            JSONArray calls = ToolHistory.of(message); // tool results are history too (spec T3); a cleared one is not seen
            for (int j = 0; j < calls.length(); j++) {
                JSONObject call = calls.optJSONObject(j);
                if (call != null && !call.optBoolean("cleared", false) && ToolHistory.resultText(call).contains(text)) return true;
            }
        }
        return false;
    }

    /** Copies what the context assembly established onto a re-budgeted copy of this prompt. */
    CanonicalPrompt withFactsOf(CanonicalPrompt source) {
        if (source != null) { summarizedItems = source.summarizedItems; clearedToolResults = source.clearedToolResults; clearedToolTokens = source.clearedToolTokens; }
        return this;
    }

    JSONObject diagnostics() {
        try {
            return new JSONObject()
                    .put("truncated", truncated)
                    .put("estimatedInputTokens", estimatedInputTokens)
                    .put("inheritedItemCount", inheritedItemCount)
                    .put("localItemCount", localItemCount)
                    .put("omittedItemCount", omittedItemCount)
                    .put("summarizedItems", summarizedItems)
                    .put("clearedToolResults", clearedToolResults)
                    .put("providerContinuation", allowProviderContinuation);
        } catch (Exception ignored) { return new JSONObject(); }
    }
}
