package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

/** Session context mode is deterministic local policy; Auto never calls a model to decide its mode. */
public class ContextModeTest {
    @Test public void explicitModesOverrideOrigin() {
        ChatDatabase.Session session = new ChatDatabase.Session();
        ChatDatabase.Snapshot remote = new ChatDatabase.Snapshot(); remote.sourceType = "remote";

        session.contextMode = ContextMode.MINIMAL;
        ContextMode.Policy minimal = ContextMode.resolve(session, remote);
        assertFalse(minimal.routerEnabled); assertFalse(minimal.checkpointEnabled); assertEquals("minimal", minimal.effectiveLabel);

        session.contextMode = ContextMode.SMART;
        ContextMode.Policy smart = ContextMode.resolve(session, null);
        assertTrue(smart.routerEnabled); assertTrue(smart.checkpointEnabled); assertEquals("smart", smart.effectiveLabel);
    }

    @Test public void autoUsesOnlySnapshotProvenance() {
        ChatDatabase.Session session = new ChatDatabase.Session(); session.contextMode = ContextMode.AUTO;
        ContextMode.Policy local = ContextMode.resolve(session, null);
        assertFalse(local.routerEnabled); assertTrue(local.checkpointEnabled); assertEquals("auto-minimal", local.effectiveLabel);

        ChatDatabase.Snapshot localSnapshot = new ChatDatabase.Snapshot(); localSnapshot.sourceType = "chat";
        ContextMode.Policy localBranch = ContextMode.resolve(session, localSnapshot);
        assertFalse(localBranch.routerEnabled); assertTrue(localBranch.checkpointEnabled); assertEquals("auto-minimal", localBranch.effectiveLabel);

        ChatDatabase.Snapshot remote = new ChatDatabase.Snapshot(); remote.sourceType = "remote";
        ContextMode.Policy remoteBranch = ContextMode.resolve(session, remote);
        assertTrue(remoteBranch.routerEnabled); assertTrue(remoteBranch.checkpointEnabled); assertEquals("auto-smart", remoteBranch.effectiveLabel);
    }
}
