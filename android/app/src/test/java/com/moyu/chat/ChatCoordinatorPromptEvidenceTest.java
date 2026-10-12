package com.moyu.chat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ChatCoordinatorPromptEvidenceTest {
    @Test public void onlyLatestUserSuffixCountsAsAppliedEvidence() throws Exception {
        String suffix = "\n\n[Moyu Web Context]\nverified material";
        JSONArray appliedMessages = new JSONArray()
                .put(new JSONObject().put("role", "user").put("text", "old" + suffix))
                .put(new JSONObject().put("role", "assistant").put("text", "answer"))
                .put(new JSONObject().put("role", "user").put("text", "current" + suffix));
        CanonicalPrompt applied = new CanonicalPrompt("system", appliedMessages, false, 100, 0, 3);
        assertTrue(TurnPipeline.promptContainsLatestUserSuffix(applied, suffix));

        JSONArray omittedMessages = new JSONArray()
                .put(new JSONObject().put("role", "user").put("text", "old" + suffix))
                .put(new JSONObject().put("role", "assistant").put("text", "answer"))
                .put(new JSONObject().put("role", "user").put("text", "current"));
        CanonicalPrompt omitted = new CanonicalPrompt("system", omittedMessages, true, 100, 0, 3);
        assertFalse("historical occurrence must not make omitted optional Web look applied",
                TurnPipeline.promptContainsLatestUserSuffix(omitted, suffix));
    }
}
