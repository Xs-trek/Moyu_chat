package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Budget selection is the last line of defense for "never drop the current input silently". */
public class ContextBudgetManagerTest {
    private static final String INSTRUCTIONS = "instructions";

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("text", text);
    }

    private static JSONArray messages(Object... pairs) throws Exception {
        JSONArray out = new JSONArray();
        for (int i = 0; i < pairs.length; i += 2) out.put(message((String) pairs[i], (String) pairs[i + 1]));
        return out;
    }

    @Test
    public void keepsEverythingWhenBudgetIsSufficient() throws Exception {
        ContextBudgetManager manager = new ContextBudgetManager();
        JSONArray all = messages("user", "hello", "assistant", "hi", "user", "again");
        ContextBudgetManager.Result result = manager.select(INSTRUCTIONS, all, 32_000, 4_096);
        assertFalse(result.truncated);
        assertEquals(3, result.messages.length());
        assertEquals("again", result.messages.getJSONObject(2).optString("text"));
    }

    @Test
    public void truncatesOldestFirstAndKeepsChronologicalOrder() throws Exception {
        ContextBudgetManager manager = new ContextBudgetManager();
        JSONArray all = new JSONArray();
        for (int i = 1; i <= 30; i++) all.put(message(i % 2 == 1 ? "user" : "assistant", "message number " + i + " with padding"));
        // 1200 - 256 output - 256 safety ~= 688 token input budget; each message costs ~27 tokens.
        ContextBudgetManager.Result result = manager.select(INSTRUCTIONS, all, 1_200, 256);
        assertTrue(result.truncated);
        assertTrue(result.messages.length() < all.length());
        // Newest tail must survive, in original chronological order.
        String last = result.messages.getJSONObject(result.messages.length() - 1).optString("text");
        assertEquals("message number 30 with padding", last);
        for (int i = 1; i < result.messages.length(); i++) {
            int prev = number(result.messages.getJSONObject(i - 1).optString("text"));
            int curr = number(result.messages.getJSONObject(i).optString("text"));
            assertEquals(prev + 1, curr);
        }
        // And the selection is a contiguous newest tail.
        assertEquals(30, number(last));
    }

    @Test
    public void neverDropsPendingInputWhenHistoryOverflows() throws Exception {
        ContextBudgetManager manager = new ContextBudgetManager();
        JSONArray all = new JSONArray();
        for (int i = 0; i < 40; i++) all.put(message("user", "old context filler " + i));
        all.put(message("user", "CURRENT QUESTION"));
        ContextBudgetManager.Result result = manager.select(INSTRUCTIONS, all, 1_200, 256);
        assertTrue(result.truncated);
        assertTrue("pending input is kept and only old history is dropped", result.messages.length() < all.length());
        String last = result.messages.getJSONObject(result.messages.length() - 1).optString("text");
        assertEquals("CURRENT QUESTION", last);
    }

    @Test
    public void failsLoudlyWhenCurrentInputAloneExceedsBudget() {
        ContextBudgetManager manager = new ContextBudgetManager();
        JSONArray only = new JSONArray();
        JSONObject huge = new JSONObject();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200_000; i++) text.append('x');
        try { huge.put("role", "user"); huge.put("text", text.toString()); } catch (Exception ignored) { }
        only.put(huge);
        try {
            manager.select(INSTRUCTIONS, only, 2_048, 512);
            fail("oversized current input must be rejected, not silently dropped");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("current input"));
        }
    }

    @Test
    public void explicitInputLimitCapsPromptEvenWhenTotalContextIsLarge() throws Exception {
        ContextBudgetManager manager = new ContextBudgetManager();
        JSONArray all = new JSONArray();
        for (int i = 0; i < 120; i++) all.put(message(i % 2 == 0 ? "user" : "assistant", "bounded input message " + i + " with enough padding to consume tokens"));
        ContextBudgetManager.Result result = manager.select(INSTRUCTIONS, all, 128_000, 4_000, 4_096);
        assertTrue("deployment max-input must constrain active prompt independently of total context", result.truncated);
        assertTrue(result.messages.length() < all.length());
        assertEquals("bounded input message 119 with enough padding to consume tokens", result.messages.getJSONObject(result.messages.length() - 1).optString("text"));
    }

    @Test
    public void estimatorIsConservativeForCjkAndLatin() {
        ContextBudgetManager manager = new ContextBudgetManager();
        // 10 CJK chars = 30 UTF-8 bytes -> at least 10 tokens (bytes/3), never below code points.
        assertTrue(manager.estimate("你好你好你好你好你好") >= 10);
        // 30 ASCII chars -> at least 10 tokens (bytes/3).
        assertTrue(manager.estimate("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa") >= 10);
        assertEquals(0, manager.estimate(""));
        assertEquals(0, manager.estimate(null));
    }

    private static int number(String text) {
        String[] parts = text.split(" ");
        return Integer.parseInt(parts[2]);
    }
}
