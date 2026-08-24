package com.moyu.chat;

import org.json.JSONArray;

/** Local, reviewed prompt modules selected by an allowlisted TaskProfile. */
final class PromptModules {
    private PromptModules() { }

    static String render(TaskProfile profile) {
        if (profile == null) return "";
        StringBuilder out = new StringBuilder();
        appendDomain(out, profile.domain);
        if ("expert".equals(profile.depth)) out.append("\nUse expert-level terminology and depth where it materially improves correctness; define uncommon terms when needed.");
        else if ("concise".equals(profile.depth)) out.append("\nPrefer a compact answer that preserves the decisive reasoning and constraints.");

        JSONArray modules = profile.modules;
        for (int i = 0; i < modules.length(); i++) {
            switch (modules.optString(i)) {
                case "technical_precision": out.append("\nUse precise technical language. Separate verified facts, inference, uncertainty, and assumptions."); break;
                case "debugging": out.append("\nFor debugging, prioritize observed evidence, root cause, and discriminating checks before proposing changes."); break;
                case "research_synthesis": out.append("\nFor research synthesis, distinguish source-backed claims from interpretation and reconcile conflicting evidence explicitly."); break;
                case "teaching": out.append("\nFor teaching, connect prerequisites to mechanism and use concrete examples when they improve understanding."); break;
                case "planning": out.append("\nFor planning, make constraints, decisions, dependencies, risks, and next actions explicit."); break;
                case "creative": out.append("\nFor creative work, preserve the user's constraints while favoring coherent, specific choices over generic filler."); break;
                default: break;
            }
        }
        return out.toString();
    }

    private static void appendDomain(StringBuilder out, String domain) {
        if (domain == null) return;
        switch (domain) {
            case "software": out.append("\nTreat software behavior, interfaces, state transitions, and test evidence as first-class details."); break;
            case "embedded": out.append("\nFor embedded systems, account for hardware/software boundaries, timing, memory, concurrency, buses, and toolchain constraints when relevant."); break;
            case "research": out.append("\nFor research tasks, prefer falsifiable claims, provenance, and uncertainty-aware synthesis."); break;
            case "math": out.append("\nFor mathematical work, keep definitions, assumptions, derivations, and units explicit."); break;
            default: break;
        }
    }
}
