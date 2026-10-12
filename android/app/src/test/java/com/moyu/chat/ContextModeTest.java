package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** Session context mode is deterministic local Harness policy. */
public class ContextModeTest {
    @Test public void explicitModesHaveStableHarnessStrength() {
        ChatDatabase.Session session = new ChatDatabase.Session();
        ChatDatabase.Snapshot branch = new ChatDatabase.Snapshot(); branch.sourceType = SnapshotService.SOURCE_CHAT;

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

    @Test public void legacyAutoNormalizesToStandardWithoutOriginHeuristics() {
        assertEquals(ContextMode.STANDARD, ContextMode.normalize("auto"));
        ChatDatabase.Session session = new ChatDatabase.Session(); session.contextMode = "auto";
        ChatDatabase.Snapshot branch = new ChatDatabase.Snapshot(); branch.sourceType = SnapshotService.SOURCE_CHAT;
        ContextMode.Policy policy = ContextMode.resolve(session, branch);
        assertFalse(policy.routerEnabled);
        assertTrue(policy.checkpointEnabled);
        assertTrue(policy.retrievalEnabled);
        assertEquals("standard", policy.effectiveLabel);
    }
}
