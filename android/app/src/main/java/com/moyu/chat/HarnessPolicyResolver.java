package com.moyu.chat;

/** Resolves bounded per-turn harness behavior from ContextMode + frozen model behavior prior. */
final class HarnessPolicyResolver {
    static final String SUBAGENT_OFF = "off";
    static final String SUBAGENT_AUTO = "auto";

    static final class Policy {
        final String harnessPreset;
        final boolean routerEnabled;
        final boolean verifierAvailable;
        final String reliabilityInstructions;
        final String subagentMode;

        Policy(String harnessPreset, boolean routerEnabled, boolean verifierAvailable,
               String reliabilityInstructions, String subagentMode) {
            this.harnessPreset = harnessPreset;
            this.routerEnabled = routerEnabled;
            this.verifierAvailable = verifierAvailable;
            this.reliabilityInstructions = reliabilityInstructions == null ? "" : reliabilityInstructions;
            this.subagentMode = subagentMode;
        }
    }

    private HarnessPolicyResolver() { }

    static String normalizeSubagentMode(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        return SUBAGENT_AUTO.equals(value) ? SUBAGENT_AUTO : SUBAGENT_OFF;
    }

    static Policy resolve(ChatDatabase.Session session) {
        String mode = ContextMode.normalize(session == null ? null : session.contextMode);
        String preset = HarnessProfile.normalize(session == null ? null : session.harnessPreset);
        if (ContextMode.MINIMAL.equals(mode)) {
            return new Policy(preset, false, false, "", SUBAGENT_OFF);
        }
        String reliability = ReliabilityModules.render(preset);
        if (ContextMode.STANDARD.equals(mode)) {
            return new Policy(preset, false, false, reliability,
                    normalizeSubagentMode(session == null ? null : session.subagentMode));
        }
        // Strong/lean models should not pay for the Smart TaskRouter by default. They still retain
        // risk-triggered verification and optional isolated delegation.
        boolean router = !HarnessProfile.LEAN.equals(preset);
        return new Policy(preset, router, true, reliability, SUBAGENT_AUTO);
    }

    /**
     * Requests whose wording asks for checking work (review, debug, compare, analyze, research, verify). Read from
     * this turn's own text, so it never outlives the turn the way a stored first-turn TaskProfile does; no model call.
     */
    private static final java.util.regex.Pattern CHECKING_REQUEST = java.util.regex.Pattern.compile(
            "(?iu)审查|审核|检查|校对|核对|核实|查证|调试|排查|报错|异常|对比|比较|区别|分析|评估|调研|研究|论证|"
                    + "\\b(?:review|audit|debug\\w*|bugs?|troubleshoot\\w*|diagnos\\w*|compare|comparison|analy[sz]\\w*|research|verify|fact-?check)\\b");

    static boolean checkingRequest(String userText) {
        return userText != null && CHECKING_REQUEST.matcher(userText).find();
    }

    /**
     * Smart verification is selective for lean models and broader for models that need more help.
     * All inputs are harness-observed state; no extra classifier call is introduced. Without a TaskProfile
     * (the Router is off by default) Balanced reads the task kind from this turn's request.
     */
    static boolean shouldVerify(ChatDatabase.Session session, TaskProfile taskProfile, String userText,
                                boolean usedExternalEvidence, boolean contextTruncated,
                                boolean usedSubagent) {
        if (session == null || !ContextMode.SMART.equals(ContextMode.normalize(session.contextMode))) return false;
        String preset = HarnessProfile.normalize(session.harnessPreset);
        if (HarnessProfile.REINFORCED.equals(preset)) {
            if (taskProfile == null) return true;
            String type = taskProfile.taskType == null ? "" : taskProfile.taskType;
            return !("write".equals(type) || "creative".equals(taskProfile.domain));
        }
        if (HarnessProfile.BALANCED.equals(preset)) {
            if (usedExternalEvidence || contextTruncated || usedSubagent) return true;
            if (taskProfile == null) return checkingRequest(userText);
            String type = taskProfile.taskType == null ? "" : taskProfile.taskType;
            return "research".equals(type) || "review".equals(type) || "debug".equals(type)
                    || "compare".equals(type) || "analyze".equals(type);
        }
        return usedExternalEvidence || contextTruncated || usedSubagent;
    }
}
