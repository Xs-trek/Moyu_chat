package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/** Projects durable Moyu transcript/snapshot data into provider-neutral model-visible context. */
final class ContextBuilder {
    private final ChatDatabase db;
    private final PromptPolicy promptPolicy = new PromptPolicy();
    private final ContextBudgetManager budgets = new ContextBudgetManager();

    ContextBuilder(ChatDatabase db) { this.db = db; }

    CanonicalPrompt build(ChatDatabase.Session session, int contextTokens, int outputTokens, String pendingUserText) throws Exception {
        return build(session, contextTokens, 0, outputTokens, pendingUserText);
    }

    CanonicalPrompt build(ChatDatabase.Session session, int contextTokens, int inputTokens, int outputTokens, String pendingUserText) throws Exception {
        ChatDatabase.Snapshot snapshot = session.originSnapshotId == null ? null : db.getSnapshot(session.originSnapshotId);
        JSONArray inherited = snapshot == null ? new JSONArray() : db.snapshotItems(snapshot.id);
        JSONArray local = db.timeline(session.id);
        JSONArray projected = new JSONArray();
        appendVisible(projected, inherited);
        int inheritedCount = projected.length();
        appendVisible(projected, local);
        String pending = pendingUserText == null ? "" : pendingUserText;
        if (!pending.trim().isEmpty()) projected.put(new JSONObject().put("role", "user").put("text", pending));
        int localCount = projected.length() - inheritedCount;

        String provisional = promptPolicy.instructions(snapshot, false);
        ContextBudgetManager.Result first = budgets.select(provisional, projected, contextTokens, inputTokens, outputTokens);
        String instructions = promptPolicy.instructions(snapshot, first.truncated);
        ContextBudgetManager.Result finalResult = first.truncated
                ? budgets.select(instructions, projected, contextTokens, inputTokens, outputTokens)
                : first;
        return new CanonicalPrompt(instructions, finalResult.messages, finalResult.truncated,
                finalResult.estimatedTokens, inheritedCount, localCount);
    }

    private static void appendVisible(JSONArray out, JSONArray source) throws Exception {
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("kind")) || item.optBoolean("streaming", false)) continue;
            String role = item.optString("role", "");
            if (!("user".equals(role) || "assistant".equals(role))) continue;
            String text = item.optString("text", "");
            if (text.trim().isEmpty()) continue;
            out.put(new JSONObject().put("role", role).put("text", text));
        }
    }
}
