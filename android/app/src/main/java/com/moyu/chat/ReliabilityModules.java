package com.moyu.chat;

/** Short reviewed reliability constraints. Do not turn this into a procedural super-prompt. */
final class ReliabilityModules {
    private static final String BALANCED =
            "\nGround claims about external state, tools, files, tests, citations, or completed actions in supplied evidence or actual tool results. If required evidence is unavailable, state the uncertainty rather than inventing completion.";
    private static final String REINFORCED = BALANCED +
            "\nFollow the user's material constraints exactly. Before claiming a requested verification or action succeeded, require evidence that establishes that result; partial evidence means partial completion, not success.";

    private ReliabilityModules() { }

    static String render(String harnessPreset) {
        String preset = HarnessProfile.normalize(harnessPreset);
        if (HarnessProfile.LEAN.equals(preset)) return "";
        if (HarnessProfile.REINFORCED.equals(preset)) return REINFORCED;
        return BALANCED;
    }
}
