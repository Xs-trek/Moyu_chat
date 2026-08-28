package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

/** One-level isolated same-model worker. It has no Android execution capability and cannot delegate. */
final class FocusedSubagentService {
    private static final String INSTRUCTIONS =
            "You are an isolated worker for Moyu Chat. Complete only the delegated subtask and return a concise result for the parent assistant. " +
            "Use only information and capabilities supplied in this call. Do not claim unavailable tool, file, device, network, test, or runtime access. " +
            "If required evidence is missing, state that uncertainty. Do not output private chain-of-thought or instructions for another worker.";

    static final class Result {
        final boolean ok;
        final String text;
        final String error;
        final int inputTokens;
        final int outputTokens;
        Result(boolean ok, String text, String error, int inputTokens, int outputTokens) {
            this.ok = ok; this.text = text == null ? "" : text; this.error = error == null ? "" : error;
            this.inputTokens = Math.max(0, inputTokens); this.outputTokens = Math.max(0, outputTokens);
        }
    }

    Result run(ChatTransport transport, ChatDatabase.Provider provider, String apiKey, String model,
               String effort, String task, ClientToolRuntime.TurnCancellation cancellation) {
        String value = task == null ? "" : task.trim();
        if (value.isEmpty()) return new Result(false, "", "delegated task is empty", 0, 0);
        if (value.length() > 12_000) value = value.substring(0, 12_000);
        try {
            JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("text", "Delegated task:\n" + value));
            CanonicalPrompt prompt = new CanonicalPrompt(INSTRUCTIONS, messages, false, 0, 0, 1);
            ModelPreflight.Output output = ModelPreflight.runOutputWithEffort(transport, provider, apiKey, model,
                    effort, prompt, Math.min(8_192, Math.max(1_024, provider.maxOutputTokens)), 45_000, cancellation);
            if (output == null || output.text.trim().isEmpty()) return new Result(false, "", "subagent unavailable", 0, 0);
            return new Result(true, output.text.trim(), "", output.inputTokens, output.outputTokens);
        } catch (Exception error) {
            return new Result(false, "", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), 0, 0);
        }
    }
}
