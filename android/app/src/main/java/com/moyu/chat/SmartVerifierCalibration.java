package com.moyu.chat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local verifier calibration. Cold-start thresholds are deliberately permissive; after enough
 * explicit verifier feedback a small regularized logistic head is blended in progressively.
 */
final class SmartVerifierCalibration {
    static final String POLICY_VERSION = "smart-verifier-v2";
    static final double COLD_ACCEPT_MAX_RISK = 0.45d;
    static final double COLD_REPAIR_MIN_RISK = 0.65d;
    static final int MIN_PERSONAL_LABELS = 30;
    static final int FULL_PERSONAL_LABELS = 100;
    private static final int MAX_LABELS = 200;
    private static final int FEATURE_COUNT = 8;

    static final String ACTION_ACCEPT = "accept";
    static final String ACTION_REPAIR = "repair";
    static final String ACTION_ABSTAIN = "abstain";

    static final class Decision {
        final double risk;
        final String action;
        final int calibrationLabels;
        final boolean learnedBlend;
        final JSONArray features;
        Decision(double risk, String action, int calibrationLabels, boolean learnedBlend, JSONArray features) {
            this.risk = risk; this.action = action; this.calibrationLabels = calibrationLabels;
            this.learnedBlend = learnedBlend; this.features = features;
        }
    }

    private SmartVerifierCalibration() { }

    static String calibrationKey(String providerId, String model) {
        return "verify:" + digest((providerId == null ? "" : providerId) + "\n" + (model == null ? "" : model) + "\n" + POLICY_VERSION);
    }

    static Decision decide(ChatDatabase db, String calibrationKey, SmartVerifierService.Result result) {
        JSONArray features = featureVector(result);
        if (result == null || !SmartVerifierService.STATUS_VALID.equals(result.status)) {
            return new Decision(0.50d, ACTION_ABSTAIN, labelCount(db, calibrationKey), false, features);
        }
        double cold = coldRisk(result, features);
        JSONArray labels = labels(db, calibrationKey);
        int count = labels.length();
        double risk = cold;
        boolean blended = false;
        if (count >= MIN_PERSONAL_LABELS) {
            double learned = learnedRisk(labels, features);
            double blend = Math.min(1d, Math.max(0d, (count - 20d) / (FULL_PERSONAL_LABELS - 20d)));
            risk = clamp(cold * (1d - blend) + learned * blend);
            blended = blend > 0d;
        }
        boolean hard = result.hasHardFailure();
        String action = hard || risk >= COLD_REPAIR_MIN_RISK ? ACTION_REPAIR
                : risk <= COLD_ACCEPT_MAX_RISK ? ACTION_ACCEPT : ACTION_ABSTAIN;
        return new Decision(risk, action, count, blended, features);
    }

    /**
     * Explicit verifier feedback only. `accurate` is accepted only for pass/fail verdicts because a
     * partial verdict does not establish whether the candidate itself was materially correct.
     */
    static boolean recordFeedback(ChatDatabase db, JSONObject verification, String label) {
        if (db == null || verification == null) return false;
        String key = verification.optString("calibrationKey", "");
        JSONArray features = verification.optJSONArray("features");
        String verdict = verification.optString("verdict", "");
        if (key.isEmpty() || features == null || features.length() != FEATURE_COUNT) return false;
        Integer y = null;
        if ("false_alarm".equals(label)) y = 0;
        else if ("missed_error".equals(label)) y = 1;
        else if ("accurate".equals(label)) {
            if (SmartVerifierService.VERDICT_FAIL.equals(verdict)) y = 1;
            else if (SmartVerifierService.VERDICT_PASS.equals(verdict)) y = 0;
        }
        if (y == null) return false;
        JSONArray all = labels(db, key);
        JSONObject sample = new JSONObject();
        try { sample.put("x", new JSONArray(features.toString())).put("y", y); }
        catch (Exception ignored) { return false; }
        all.put(sample);
        while (all.length() > MAX_LABELS) {
            JSONArray trimmed = new JSONArray();
            for (int i = all.length() - MAX_LABELS; i < all.length(); i++) trimmed.put(all.opt(i));
            all = trimmed;
        }
        db.putSetting(settingKey(key), all.toString());
        return true;
    }

    private static JSONArray featureVector(SmartVerifierService.Result result) {
        double failRatio = 0d, unknownRatio = 0d, severeFailRatio = 0d, evidenceCoverage = 0d;
        int checks = 0, fail = 0, unknown = 0, severe = 0, evidenced = 0;
        if (result != null && result.checks != null) {
            checks = result.checks.length();
            for (int i = 0; i < result.checks.length(); i++) {
                JSONObject check = result.checks.optJSONObject(i); if (check == null) continue;
                String state = check.optString("result", "");
                if ("fail".equals(state)) {
                    fail++;
                    String severity = check.optString("severity", "");
                    if ("hard".equals(severity) || "major".equals(severity)) severe++;
                } else if ("unknown".equals(state)) unknown++;
                JSONArray ids = check.optJSONArray("evidenceIds");
                if (ids != null && ids.length() > 0) evidenced++;
            }
        }
        if (checks > 0) {
            failRatio = fail / (double) checks;
            unknownRatio = unknown / (double) checks;
            severeFailRatio = severe / (double) checks;
            evidenceCoverage = evidenced / (double) checks;
        }
        JSONArray x = new JSONArray();
        // Use the Object overload: Android org.json declares checked JSONException for put(double)
        // even though all values below are finite by construction.
        x.put(Double.valueOf(1d)); // intercept
        x.put(Double.valueOf(result != null && SmartVerifierService.VERDICT_FAIL.equals(result.verdict) ? 1d : 0d));
        x.put(Double.valueOf(result != null && SmartVerifierService.VERDICT_PARTIAL.equals(result.verdict) ? 1d : 0d));
        x.put(Double.valueOf(failRatio));
        x.put(Double.valueOf(unknownRatio));
        x.put(Double.valueOf(severeFailRatio));
        x.put(Double.valueOf(result == null ? 1d : 1d - clamp(result.selfConfidence)));
        x.put(Double.valueOf(1d - evidenceCoverage));
        return x;
    }

    private static double coldRisk(SmartVerifierService.Result result, JSONArray x) {
        double base = SmartVerifierService.VERDICT_FAIL.equals(result.verdict) ? 0.74d
                : SmartVerifierService.VERDICT_PARTIAL.equals(result.verdict) ? 0.50d : 0.18d;
        double failRatio = x.optDouble(3, 0d), unknown = x.optDouble(4, 0d), severe = x.optDouble(5, 0d);
        double uncertainty = x.optDouble(6, 1d), evidenceGap = x.optDouble(7, 1d);
        return clamp(base + 0.10d * failRatio + 0.10d * severe + 0.05d * unknown
                + 0.035d * uncertainty + 0.025d * evidenceGap);
    }

    private static double learnedRisk(JSONArray samples, JSONArray target) {
        List<double[]> xs = new ArrayList<>();
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < samples.length(); i++) {
            JSONObject sample = samples.optJSONObject(i); if (sample == null) continue;
            JSONArray x = sample.optJSONArray("x"); if (x == null || x.length() != FEATURE_COUNT) continue;
            double[] row = new double[FEATURE_COUNT]; for (int j = 0; j < FEATURE_COUNT; j++) row[j] = x.optDouble(j, 0d);
            xs.add(row); ys.add(sample.optInt("y", 0) == 0 ? 0 : 1);
        }
        if (xs.size() < MIN_PERSONAL_LABELS) return 0.5d;
        double[] w = new double[FEATURE_COUNT];
        double mean = 0d; for (int y : ys) mean += y; mean = (mean + 1d) / (ys.size() + 2d);
        w[0] = Math.log(mean / Math.max(1e-6d, 1d - mean));
        final double learningRate = 0.06d;
        final double l2 = 0.12d;
        for (int iteration = 0; iteration < 220; iteration++) {
            double[] grad = new double[FEATURE_COUNT];
            for (int i = 0; i < xs.size(); i++) {
                double p = sigmoid(dot(w, xs.get(i)));
                double error = p - ys.get(i);
                for (int j = 0; j < FEATURE_COUNT; j++) grad[j] += error * xs.get(i)[j];
            }
            for (int j = 0; j < FEATURE_COUNT; j++) {
                double regularization = j == 0 ? 0d : l2 * w[j];
                w[j] -= learningRate * (grad[j] / xs.size() + regularization);
            }
        }
        double[] row = new double[FEATURE_COUNT]; for (int j = 0; j < FEATURE_COUNT; j++) row[j] = target.optDouble(j, 0d);
        return clamp(sigmoid(dot(w, row)));
    }

    private static int labelCount(ChatDatabase db, String key) { return labels(db, key).length(); }

    private static JSONArray labels(ChatDatabase db, String key) {
        if (db == null || key == null || key.isEmpty()) return new JSONArray();
        String raw = db.setting(settingKey(key), "[]");
        try { return new JSONArray(raw); } catch (Exception ignored) { return new JSONArray(); }
    }

    private static String settingKey(String key) { return "verifierCalibration:" + key; }
    private static double dot(double[] a, double[] b) { double v = 0d; for (int i = 0; i < Math.min(a.length, b.length); i++) v += a[i] * b[i]; return v; }
    private static double sigmoid(double value) { if (value > 30d) return 1d; if (value < -30d) return 0d; return 1d / (1d + Math.exp(-value)); }
    private static double clamp(double value) { return Math.max(0d, Math.min(1d, value)); }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 8; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); }
    }
}
