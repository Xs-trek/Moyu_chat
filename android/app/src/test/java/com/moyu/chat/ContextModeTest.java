package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** Session context mode is deterministic local Harness policy. */
public class ContextModeTest {
    @Test public void explicitModesHaveStableHarnessStrength() {
        ChatDatabase.Session session = new ChatDatabase.Session();
        ChatDatabase.Snapshot branch = new ChatDatabase.Snapshot();

        session.contextMode = ContextMode.MINIMAL;
        ContextMode.Policy minimal = ContextMode.resolve(session, branch);
        assertFalse(minimal.routerEnabled); assertFalse(minimal.checkpointEnabled); assertFalse(minimal.retrievalEnabled); assertFalse(minimal.evidencePolicyEnabled); assertEquals("minimal", minimal.effectiveLabel);

        session.contextMode = ContextMode.STANDARD;
        ContextMode.Policy standard = ContextMode.resolve(session, branch);
        assertFalse(standard.routerEnabled); assertTrue(standard.checkpointEnabled); assertTrue(standard.retrievalEnabled); assertFalse(standard.evidencePolicyEnabled); assertEquals("standard", standard.effectiveLabel);

        session.contextMode = ContextMode.SMART;
        ContextMode.Policy smart = ContextMode.resolve(session, null);
        assertTrue(smart.routerEnabled); assertTrue(smart.checkpointEnabled); assertTrue(smart.retrievalEnabled); assertTrue(smart.evidencePolicyEnabled); assertEquals("smart", smart.effectiveLabel);
    }

    @Test public void unknownModeNormalizesToStandardWithoutOriginHeuristics() {
        assertEquals(ContextMode.STANDARD, ContextMode.normalize("auto"));
        ChatDatabase.Session session = new ChatDatabase.Session(); session.contextMode = "auto";
        ChatDatabase.Snapshot branch = new ChatDatabase.Snapshot();
        ContextMode.Policy policy = ContextMode.resolve(session, branch);
        assertFalse(policy.routerEnabled);
        assertTrue(policy.checkpointEnabled);
        assertTrue(policy.retrievalEnabled);
        assertEquals("standard", policy.effectiveLabel);
    }

    @Test public void balancedVerifiesCheckingRequestsWithoutARouterProfile() {
        ChatDatabase.Session session = new ChatDatabase.Session();
        session.contextMode = ContextMode.SMART; session.harnessPreset = HarnessProfile.BALANCED;
        assertTrue(HarnessPolicyResolver.shouldVerify(session, null, "帮我审查这段代码有没有问题", false, false, false));
        assertTrue(HarnessPolicyResolver.shouldVerify(session, null, "Please debug this crash", false, false, false));
        assertFalse(HarnessPolicyResolver.shouldVerify(session, null, "写一首关于秋天的诗", false, false, false));
        assertFalse("a word that merely contains one is not one", HarnessPolicyResolver.checkingRequest("the debutante arrived"));
        assertTrue("evidence still triggers", HarnessPolicyResolver.shouldVerify(session, null, "写诗", true, false, false));
    }

    @Test public void theRouterStartsOffOnceAndAChoiceToTurnItOnIsKept() {
        assertEquals(java.util.Collections.singleton(HelperSwitches.ROUTER),
                HelperSwitches.withDefaults(java.util.Collections.<String>emptySet(), 1));
        assertTrue(HelperSwitches.withDefaults(java.util.Collections.<String>emptySet(), HelperSwitches.DEFAULTS_REVISION).isEmpty());
        assertTrue(HelperSwitches.known(HelperSwitches.RECALL));
        assertFalse("spec D5: the Web mode controls the model's Web tools; a stored webTools switch is dropped",
                HelperSwitches.known("webTools") || HelperSwitches.parse("[\"webTools\",\"verify\"]").contains("webTools"));
    }
}
