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
        SmartVerifierService.Result result = SmartVerifierService.parse(raw, evidence(), true, "");
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
        SmartVerifierService.Result result = SmartVerifierService.parse(raw, evidence(), true, "");
        assertEquals(SmartVerifierService.STATUS_INVALID, result.status);
        assertEquals("fail_without_evidence", result.invalidReason);
    }

    @Test public void incompleteHarnessCoverageCannotPassWholeAnswer() {
        String raw = "{\"verdict\":\"pass\",\"selfConfidence\":0.9,\"checks\":[" +
                check("constraint_adherence","pass","minor","user:current") + "," +
                check("evidence_integrity","pass","minor","context:recent") + "," +
                check("completion_honesty","pass","minor","user:current") + "," +
                check("uncertainty_honesty","pass","minor","context:recent") + "," +
                check("material_factual_support","pass","minor","context:recent") + "]}";
        SmartVerifierService.Result result = SmartVerifierService.parse(raw, evidence(), false, "candidate_truncated");
        assertEquals(SmartVerifierService.STATUS_VALID, result.status);
        assertEquals(SmartVerifierService.VERDICT_PARTIAL, result.verdict);
        assertFalse(result.coverageComplete);
    }

    private static String check(String rule, String result, String severity, String evidenceId) {
        return "{\"rule\":\"" + rule + "\",\"result\":\"" + result + "\",\"severity\":\"" + severity +
                "\",\"evidenceIds\":[\"" + evidenceId + "\"],\"note\":\"ok\"}";
    }
}
