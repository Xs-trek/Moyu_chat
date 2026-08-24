package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/** Projects durable Moyu transcript/snapshot data into provider-neutral model-visible context. */
final class ContextBuilder {
    private final ChatDatabase db;
    private final ChatMediaStore mediaStore;
    private final PromptPolicy promptPolicy = new PromptPolicy();
    private final ContextBudgetManager budgets = new ContextBudgetManager();

    ContextBuilder(ChatDatabase db) { this(db, null); }
    ContextBuilder(ChatDatabase db, ChatMediaStore mediaStore) { this.db = db; this.mediaStore = mediaStore; }

    CanonicalPrompt build(ChatDatabase.Session session, int contextTokens, int outputTokens, String pendingUserText) throws Exception {
        return build(session, contextTokens, 0, outputTokens, pendingUserText, new JSONArray());
    }

    CanonicalPrompt build(ChatDatabase.Session session, int contextTokens, int inputTokens, int outputTokens, String pendingUserText) throws Exception {
        return build(session, contextTokens, inputTokens, outputTokens, pendingUserText, new JSONArray());
    }

    CanonicalPrompt build(ChatDatabase.Session session, int contextTokens, int inputTokens, int outputTokens,
                          String pendingUserText, JSONArray pendingMedia) throws Exception {
        ChatDatabase.Snapshot snapshot = session.originSnapshotId == null ? null : db.getSnapshot(session.originSnapshotId);
        JSONArray inherited = snapshot == null ? new JSONArray() : db.snapshotItems(snapshot.id);
        JSONArray local = db.timeline(session.id);
        JSONArray projected = new JSONArray();
        appendVisible(projected, inherited);
        int inheritedCount = projected.length();
        appendVisible(projected, local);
        String pending = pendingUserText == null ? "" : pendingUserText;
        JSONArray pendingImages = projectAttachments(pendingMedia);
        if (!pending.trim().isEmpty() || pendingImages.length() > 0) {
            JSONObject message = new JSONObject().put("role", "user").put("text", pending);
            if (pendingImages.length() > 0) message.put("attachments", pendingImages);
            projected.put(message);
        }
        int localCount = projected.length() - inheritedCount;

        String provisional = promptPolicy.instructions(session, snapshot, false);
        ContextBudgetManager.Result first = budgets.select(provisional, projected, contextTokens, inputTokens, outputTokens);
        String instructions = promptPolicy.instructions(session, snapshot, first.truncated);
        ContextBudgetManager.Result finalResult = first.truncated
                ? budgets.select(instructions, projected, contextTokens, inputTokens, outputTokens)
                : first;
        return new CanonicalPrompt(instructions, finalResult.messages, finalResult.truncated,
                finalResult.estimatedTokens, inheritedCount, localCount, finalResult.omittedMessageCount,
                new JSONArray(projected.toString()));
    }

    private void appendVisible(JSONArray out, JSONArray source) throws Exception {
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("kind")) || item.optBoolean("streaming", false)) continue;
            String role = item.optString("role", "");
            if (!("user".equals(role) || "assistant".equals(role))) continue;
            String text = item.optString("text", "");
            JSONArray attachments = projectAttachments(item.optJSONArray("attachments"));

            if ("assistant".equals(role) && item.optBoolean("incomplete", false)) {
                String code = item.optString("terminalCode", "interrupted");
                text = "[Previous assistant response was incomplete (" + code + "). Treat it as partial context, not a completed answer.]"
                        + (text.isEmpty() ? "" : "\n" + text);
            }
            // Provider-neutral v0.0.2 does not pretend assistant-generated image bytes can be replayed
            // uniformly across all three protocol families. Preserve explicit semantic awareness.
            if ("assistant".equals(role) && attachments.length() > 0) {
                text = text + (text.isEmpty() ? "" : "\n") + "[The assistant produced image output in this historical turn; the image bytes are not replayed in provider-neutral context.]";
                attachments = new JSONArray();
            }
            if (text.trim().isEmpty() && attachments.length() == 0) continue;
            JSONObject visible = new JSONObject().put("role", role).put("text", text);
            if (attachments.length() > 0) visible.put("attachments", attachments);
            out.put(visible);
        }
    }

    private JSONArray projectAttachments(JSONArray source) throws Exception {
        JSONArray out = new JSONArray();
        if (source == null) return out;
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i); if (item == null || !"image".equals(item.optString("kind"))) continue;
            String mediaId = item.optString("mediaId", "").trim(); String mime = item.optString("mimeType", "").trim();
            if (mediaId.isEmpty() || mime.isEmpty()) continue;
            if (mediaStore != null && !mediaStore.exists(mediaId)) continue;
            JSONObject projected = new JSONObject().put("kind", "image").put("mediaId", mediaId).put("mimeType", mime)
                    .put("fileName", item.optString("fileName", "image")).put("sizeBytes", item.optLong("sizeBytes", 0))
                    .put("width", item.optInt("width", 0)).put("height", item.optInt("height", 0));
            if (mediaStore != null) projected.put("dataUrl", mediaStore.dataUrl(item));
            out.put(projected);
        }
        return out;
    }
}
