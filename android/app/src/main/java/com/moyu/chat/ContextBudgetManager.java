package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

/** Conservative tokenizer-free budget. It affects only derived prompt state, never stored history. */
final class ContextBudgetManager {
    static final int DEFAULT_CONTEXT_TOKENS = 32_000;
    static final int DEFAULT_OUTPUT_TOKENS = 4_096;
    private static final int MAX_SAFETY_TOKENS = 1_024;
    private static final int MIN_INPUT_BUDGET = 512;
    private static final int MESSAGE_OVERHEAD_TOKENS = 12;

    static final class Result {
        final JSONArray messages;
        final boolean truncated;
        final int estimatedTokens;
        final int omittedMessageCount;

        Result(JSONArray messages, boolean truncated, int estimatedTokens, int omittedMessageCount) {
            this.messages = messages;
            this.truncated = truncated;
            this.estimatedTokens = estimatedTokens;
            this.omittedMessageCount = Math.max(0, omittedMessageCount);
        }
    }

    Result select(String instructions, JSONArray chronological, int contextTokens, int outputTokens) {
        return select(instructions, chronological, contextTokens, 0, outputTokens);
    }

    /**
     * Select complete user-led turns newest-first. A turn begins at a user message and includes the
     * following assistant messages until the next user. Orphan leading assistants are never sent.
     */
    Result select(String instructions, JSONArray chronological, int contextTokens, int inputTokens, int outputTokens) {
        int total = contextTokens > 0 ? contextTokens : DEFAULT_CONTEXT_TOKENS;
        int output = outputTokens > 0 ? outputTokens : DEFAULT_OUTPUT_TOKENS;
        int safety = Math.min(MAX_SAFETY_TOKENS, Math.max(256, total / 16));
        int totalInput = total - output;
        int declaredInput = inputTokens > 0 ? inputTokens : totalInput;
        int budget = Math.min(totalInput, declaredInput) - safety;
        if (budget < MIN_INPUT_BUDGET) throw new IllegalArgumentException("context/output budget leaves no usable input window");

        int used = estimate(instructions) + MESSAGE_OVERHEAD_TOKENS;
        if (used >= budget) throw new IllegalArgumentException("Moyu instructions exceed the configured input budget");

        ArrayList<ArrayList<JSONObject>> groups = groupTurns(chronological);
        ArrayList<ArrayList<JSONObject>> keptReverse = new ArrayList<>();
        int keptMessages = 0;
        boolean truncated = false;
        for (int i = groups.size() - 1; i >= 0; i--) {
            ArrayList<JSONObject> group = groups.get(i);
            if (group.isEmpty() || !"user".equals(group.get(0).optString("role"))) { truncated = true; continue; }
            int cost = 0;
            for (JSONObject item : group) cost += messageCost(item);
            if (used + cost > budget) {
                if (keptReverse.isEmpty()) throw new IllegalArgumentException("current input exceeds the configured context budget");
                truncated = true;
                break;
            }
            keptReverse.add(group);
            keptMessages += group.size();
            used += cost;
        }

        Collections.reverse(keptReverse);
        JSONArray out = new JSONArray();
        for (ArrayList<JSONObject> group : keptReverse) for (JSONObject item : group) out.put(item);
        int original = chronological == null ? 0 : chronological.length();
        int omitted = Math.max(0, original - keptMessages);
        return new Result(out, truncated || omitted > 0, used, omitted);
    }

    private ArrayList<ArrayList<JSONObject>> groupTurns(JSONArray chronological) {
        ArrayList<ArrayList<JSONObject>> groups = new ArrayList<>();
        if (chronological == null) return groups;
        ArrayList<JSONObject> current = null;
        for (int i = 0; i < chronological.length(); i++) {
            JSONObject item = chronological.optJSONObject(i); if (item == null) continue;
            String role = item.optString("role", "");
            if ("user".equals(role)) {
                current = new ArrayList<>(); current.add(item); groups.add(current);
            } else if ("assistant".equals(role)) {
                if (current == null) { current = new ArrayList<>(); current.add(item); groups.add(current); }
                else current.add(item);
            }
        }
        return groups;
    }

    private int messageCost(JSONObject item) {
        return estimate(item.optString("text", "")) + estimateMedia(item.optJSONArray("attachments")) + MESSAGE_OVERHEAD_TOKENS;
    }

    /** Image tokenization is provider-specific; reserve a conservative dimension-based budget per image. */
    private int estimateMedia(JSONArray attachments) {
        if (attachments == null) return 0;
        int total = 0;
        for (int i = 0; i < attachments.length(); i++) {
            JSONObject item = attachments.optJSONObject(i); if (item == null || !"image".equals(item.optString("kind"))) continue;
            long width = Math.max(1, item.optLong("width", 1024)); long height = Math.max(1, item.optLong("height", 1024));
            long tiles = Math.max(1, ((width + 511) / 512) * ((height + 511) / 512));
            total += (int) Math.min(8_192, 768 + tiles * 384);
        }
        return total;
    }

    /** Deliberately conservative across Latin/CJK without shipping a model-specific tokenizer. */
    int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        int codePoints = text.codePointCount(0, text.length());
        int bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max((codePoints + 1) / 2, (bytes + 2) / 3);
    }
}
