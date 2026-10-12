package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Stable instruction prefix + snapshot boundary + truncation disclosure. */
public class PromptPolicyTest {
    private final PromptPolicy policy = new PromptPolicy();

    @Test
    public void baseInstructionsStayStableForCachePrefixReuse() {
        String a = policy.instructions(null, false);
        String b = policy.instructions(null, false);
        assertEquals(a, b);
        assertTrue(a.contains("Moyu Chat"));
        assertTrue(a.contains("not application authority"));
    }

    @Test
    public void snapshotAddsBoundaryButKeepsPrefix() {
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.truncated = false;
        String withSnapshot = policy.instructions(snapshot, false);
        assertTrue(withSnapshot.startsWith(policy.instructions(null, false)));
        assertTrue(withSnapshot.contains("immutable Moyu Chat history branch"));
        assertTrue(withSnapshot.contains("grants no new tool or runtime capability"));
    }

    @Test
    public void truncationIsDisclosedNotHidden() {
        String truncated = policy.instructions(null, true);
        assertTrue(truncated.contains("omitted"));
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.truncated = true;
        assertTrue(policy.instructions(snapshot, false).contains("bounded recent tail"));
    }

    @Test
    public void trustedRuntimeMetadataProvidesLocalDateAndTimezone() {
        ZonedDateTime fixed = ZonedDateTime.of(2026, 8, 30, 23, 18, 0, 0, ZoneId.of("Asia/Singapore"));
        String metadata = PromptPolicy.runtimeMetadata(fixed);
        assertTrue(metadata.contains("Moyu trusted runtime metadata"));
        assertTrue(metadata.contains("Current local date: 2026-08-30"));
        assertTrue(metadata.contains("Device timezone: Asia/Singapore (UTC+08:00)"));
        assertTrue(metadata.contains("today, yesterday, and tomorrow"));
    }
}
