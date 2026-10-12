package com.moyu.chat;

import static org.junit.Assert.*;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SmartVerifierCalibrationTest {
    private ChatDatabase db;
    private ChatDatabase.Provider provider;
    private ChatDatabase.Session session;

    @Before public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        context.deleteDatabase(ChatDatabase.FILE_NAME);
        db = new ChatDatabase(context);
        db.getWritableDatabase();
        provider = new ChatDatabase.Provider();
        provider.id = "cal-provider";
        provider.displayName = "Calibration Provider";
        provider.protocol = OpenAiCompatibleTransport.RESPONSES;
        provider.baseUrl = "https://one.example/v1";
        provider.model = "model-a";
        TestDeployments.save(db, provider);
        session = TestDeployments.session(db, provider.id, provider.model, "calibration", null);
    }

    @Test public void deploymentChangesProduceNewCalibrationEpochs() {
        String first = SmartVerifierCalibration.calibrationKey(db, provider, session);
        provider.baseUrl = "https://two.example/v1";
        db.saveProvider(provider);
        String second = SmartVerifierCalibration.calibrationKey(db, provider, session);
        assertNotEquals("base URL belongs to deployment identity", first, second);

        ProviderContinuationIdentity.bumpCredentialEpoch(db, provider.id);
        String third = SmartVerifierCalibration.calibrationKey(db, provider, session);
        assertNotEquals("credential/account replacement must isolate calibration", second, third);
    }

    @Test public void frozenRequestDeploymentKeepsItsCalibrationEpochAfterCredentialRotation() {
        String frozenFingerprint = ProviderContinuationIdentity.fingerprint(db, provider, session);
        String frozenKey = SmartVerifierCalibration.calibrationKey(frozenFingerprint);

        ProviderContinuationIdentity.bumpCredentialEpoch(db, provider.id);
        String currentKey = SmartVerifierCalibration.calibrationKey(db, provider, session);

        assertNotEquals("rotated credentials must use a new calibration bucket", frozenKey, currentKey);
        assertEquals("an already-running request must retain the bucket of the deployment that actually served it",
                frozenKey, SmartVerifierCalibration.calibrationKey(frozenFingerprint));
    }

    @Test public void invalidOrIncompleteVerifierCannotTrainCalibration() throws Exception {
        String key = SmartVerifierCalibration.calibrationKey(db, provider, session);
        JSONObject invalid = verification(key, SmartVerifierService.STATUS_INVALID, false, SmartVerifierService.VERDICT_FAIL);
        assertFalse(SmartVerifierCalibration.recordFeedback(db, invalid, "false_alarm"));

        JSONObject incomplete = verification(key, SmartVerifierService.STATUS_VALID, false, SmartVerifierService.VERDICT_PASS);
        assertFalse(SmartVerifierCalibration.recordFeedback(db, incomplete, "missed_error"));

        JSONObject partial = verification(key, SmartVerifierService.STATUS_VALID, true, SmartVerifierService.VERDICT_PARTIAL);
        assertFalse(SmartVerifierCalibration.recordFeedback(db, partial, "accurate"));
    }

    @Test public void feedbackLabelsMustMatchValidatedPassOrFailVerdict() throws Exception {
        String key = SmartVerifierCalibration.calibrationKey(db, provider, session);
        JSONObject fail = verification(key, SmartVerifierService.STATUS_VALID, true, SmartVerifierService.VERDICT_FAIL);
        assertTrue(SmartVerifierCalibration.recordFeedback(db, fail, "false_alarm"));
        assertFalse("missed_error only describes a verifier PASS that missed an error",
                SmartVerifierCalibration.recordFeedback(db, fail, "missed_error"));

        JSONObject pass = verification(key, SmartVerifierService.STATUS_VALID, true, SmartVerifierService.VERDICT_PASS);
        assertTrue(SmartVerifierCalibration.recordFeedback(db, pass, "missed_error"));
        assertFalse("false_alarm only describes a verifier FAIL",
                SmartVerifierCalibration.recordFeedback(db, pass, "false_alarm"));
    }

    private static JSONObject verification(String key, String status, boolean coverageComplete, String verdict) throws Exception {
        JSONArray features = new JSONArray();
        for (int i = 0; i < 8; i++) features.put(Double.valueOf(i == 0 ? 1d : 0d));
        return new JSONObject()
                .put("status", status)
                .put("coverageComplete", coverageComplete)
                .put("policyVersion", SmartVerifierCalibration.POLICY_VERSION)
                .put("calibrationKey", key)
                .put("features", features)
                .put("verdict", verdict);
    }
}
