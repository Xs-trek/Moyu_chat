package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class ContextCheckpointServiceTest {
    @Test public void sourceCharCapNeverMarksUnseenHistoryCovered() throws Exception {
        JSONArray projected = new JSONArray();
        for (int i = 0; i < 80; i++) {
            projected.put(new JSONObject().put("role", i % 2 == 0 ? "user" : "assistant")
                    .put("text", "item-" + i + " " + repeat('x', 900)));
        }
        CanonicalPrompt baseline = new CanonicalPrompt("SYSTEM", new JSONArray(), true, 0, 0, 0,
                projected.length(), new JSONArray(projected.toString()), false);
        CapturingTransport transport = new CapturingTransport();
        ChatDatabase.Provider provider = new ChatDatabase.Provider(); provider.id = "p"; provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS;
        ContextCheckpointService.Update update = new ContextCheckpointService().update(transport, provider, "key", "m", baseline, null, 0);
        assertNotNull(update);
        assertTrue("24K input cap should cover only a prefix, not all 80 messages", update.coveredItems > 0 && update.coveredItems < projected.length());
        String supplied = transport.lastPrompt.messages.getJSONObject(0).optString("text");
        assertTrue(supplied.contains("item-0"));
        assertTrue(supplied.contains("item-" + (update.coveredItems - 1)));
        assertFalse("first uncovered message must not be claimed as summarized", supplied.contains("item-" + update.coveredItems + " "));
    }

    private static final class CapturingTransport implements ChatTransport {
        CanonicalPrompt lastPrompt;
        @Override public Turn stream(ChatDatabase.Provider provider, String apiKey, String model, String reasoningEffort,
                                     CanonicalPrompt prompt, Sink sink) {
            lastPrompt = prompt;
            sink.onTextDelta("{\"summary\":\"bounded\",\"constraints\":[],\"decisions\":[],\"openQuestions\":[],\"criticalRefs\":[]}");
            sink.onCompleted();
            return () -> { };
        }
    }

    private static String repeat(char value, int count) { StringBuilder out = new StringBuilder(count); for (int i = 0; i < count; i++) out.append(value); return out.toString(); }
}
