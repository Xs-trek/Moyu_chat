package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class SmartVerifierServiceTest {
    private static Set<String> evidence() {
        Set<String> ids = new HashSet<>(); ids.add("user:current"); ids.add("context:recent"); return ids;
    }

    @Test public void harnessDerivesFailEvenWhenModelClaimsPass() {
        String raw = "{\"verdict\":\"pass\",\"selfConfidence\":0.99,\"checks\":[" +
                check("constraint_adherence","pass","minor","user:current") + "," +
                check("evidence_integrity","pass","minor","context:recent") + "," +
                check("completion_honesty","pass","minor","user:current") + "," +
                check("uncertainty_honesty","pass","minor","context:recent") + "," +
                check("material_factual_support","fail","major","context:recent") + "]}";
        SmartVerifierService.Result result = SmartVerifierService.parse(raw, evidence());
        assertEquals(SmartVerifierService.STATUS_VALID, result.status);
        assertEquals("model verdict is advisory; harness checks own the verdict", SmartVerifierService.VERDICT_FAIL, result.verdict);
    }

    @Test public void failingCheckWithoutEvidenceIsInvalid() {
        String raw = "{\"verdict\":\"fail\",\"selfConfidence\":0.8,\"checks\":[" +
                check("constraint_adherence","pass","minor","user:current") + "," +
                "{\"rule\":\"evidence_integrity\",\"result\":\"fail\",\"severity\":\"hard\",\"evidenceIds\":[],\"note\":\"fabricated\"}," +
                check("completion_honesty","pass","minor","user:current") + "," +
                check("uncertainty_honesty","pass","minor","context:recent") + "," +
                check("material_factual_support","pass","minor","context:recent") + "]}";
        SmartVerifierService.Result result = SmartVerifierService.parse(raw, evidence());
        assertEquals(SmartVerifierService.STATUS_INVALID, result.status);
        assertEquals("fail_without_evidence", result.invalidReason);
    }

    @Test public void incompleteHarnessCoverageForcesAbstainEvenIfChecksPass() throws Exception {
        JSONArray checks = new JSONArray();
        for (String rule : new String[]{"constraint_adherence","evidence_integrity","completion_honesty","uncertainty_honesty","material_factual_support"}) {
            checks.put(new org.json.JSONObject().put("rule", rule).put("result", "pass").put("severity", "minor")
                    .put("evidenceIds", new JSONArray().put("user:current")).put("note", "ok"));
        }
        SmartVerifierService.Result result = new SmartVerifierService.Result(
                SmartVerifierService.STATUS_VALID, SmartVerifierService.VERDICT_PASS, 0.9d, checks, "", false,
                "candidate_truncated_for_verification");
        SmartVerifierCalibration.Decision decision = SmartVerifierCalibration.decide(null, "test", result);
        assertEquals(SmartVerifierCalibration.ACTION_ABSTAIN, decision.action);
    }

    private static String check(String rule, String result, String severity, String evidenceId) {
        return "{\"rule\":\"" + rule + "\",\"result\":\"" + result + "\",\"severity\":\"" + severity +
                "\",\"evidenceIds\":[\"" + evidenceId + "\"],\"note\":\"ok\"}";
    }

    /** Spec M7, N5: each tool result is labelled by what it is, and a result that had to be cut makes coverage incomplete. */
    @Test public void toolResultsAreLabelledByTheirToolAndACutOneMakesTheLedgerIncomplete() throws Exception {
        JSONArray tools = new JSONArray()
                .put(new org.json.JSONObject().put("tool", ClientToolRuntime.WEB_SEARCH).put("text", "search hits"))
                .put(new org.json.JSONObject().put("tool", ClientToolRuntime.HISTORY_RECALL).put("text", "[message #1, user] the code is 4471"))
                .put(new org.json.JSONObject().put("tool", ClientToolRuntime.DELEGATE_SUBTASK).put("text", "subtask answer"));
        JSONArray ledger = SmartVerifierService.evidence("q", null, null, "", null, null, tools);
        java.util.Map<String, String> types = new java.util.HashMap<>();
        for (int i = 0; i < ledger.length(); i++) types.put(ledger.getJSONObject(i).getString("id"), ledger.getJSONObject(i).getString("type"));
        assertEquals("retrieved_web_context", types.get("tool:1"));
        assertEquals("a recalled message is not Web content", "conversation_recall", types.get("tool:2"));
        assertEquals("subtask_result", types.get("tool:3"));
        assertTrue(SmartVerifierService.ledgerComplete(ledger));

        tools.getJSONObject(2).put("clipped", true);
        assertFalse("a cut result is incomplete coverage", SmartVerifierService.ledgerComplete(
                SmartVerifierService.evidence("q", null, null, "", null, null, tools)));
    }

    @Test public void theEvidenceLedgerCarriesTheCheckpointAndTheUserInstructionsTheMainCallSaw() throws Exception {
        ContextCheckpoint checkpoint = new ContextCheckpoint("Earlier the user fixed the budget at 300 USD.", new JSONArray().put("budget <= 300 USD"),
                new JSONArray(), new JSONArray(), new JSONArray());
        JSONArray ledger = SmartVerifierService.evidence("What can I buy?", null, null, null, checkpoint, "Answer in English.");
        java.util.Map<String, String> byId = new java.util.HashMap<>();
        for (int i = 0; i < ledger.length(); i++) byId.put(ledger.getJSONObject(i).getString("id"), ledger.getJSONObject(i).optString("text"));
        assertTrue(byId.get("context:checkpoint").contains("300 USD"));
        assertEquals("Answer in English.", byId.get("user:instructions"));
        JSONArray plain = SmartVerifierService.evidence("What can I buy?", null, null, null);
        assertEquals("no checkpoint, no instructions: only the request", 1, plain.length());
    }
}
