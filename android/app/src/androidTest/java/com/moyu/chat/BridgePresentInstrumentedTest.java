package com.moyu.chat;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

/**
 * Run AFTER installing a same-signature Moyu Remote build that exports
 * com.moyu.remote.chatbridge (ChatBridgeProvider). Verifies the real cross-APK path:
 * signature permission grant, provider round-trip and the Remote-side inbox write.
 */
@RunWith(AndroidJUnit4.class)
public class BridgePresentInstrumentedTest {

    private final AndroidRemoteDataBridge bridge = new AndroidRemoteDataBridge(
            InstrumentationRegistry.getInstrumentation().getTargetContext());

    @Test public void statusSeesSignatureProtectedBridge() {
        RemoteDataBridge.Status status = bridge.status();
        assertTrue("Remote installed with matching signature; got: " + status.reason, status.available);
        assertEquals(1, status.contractVersion);
    }

    @Test public void listSourcesRoundTripsThroughRealProvider() throws Exception {
        assertTrue(bridge.status().available);
        // Empty is fine: no native history cached on a fresh Remote install; the call must succeed.
        bridge.listSources();
    }

    @Test public void handoffLandsInRemoteInbox() throws Exception {
        assertTrue(bridge.status().available);
        JSONObject handoff = new JSONObject()
                .put("handoffId", UUID.randomUUID().toString())
                .put("branchSessionId", UUID.randomUUID().toString())
                .put("sourceSnapshotId", JSONObject.NULL)
                .put("summary", "instrumented handoff smoke")
                .put("selectedContext", new org.json.JSONArray())
                .put("createdAt", java.time.Instant.now().toString());
        bridge.submitHandoff(handoff); // accepted into the Remote-side inbox; never auto-sent
    }

    @Test public void unknownSourceIsRejectedByTheProvider() {
        assertTrue(bridge.status().available);
        try {
            bridge.createSnapshot("definitely-not-a-source", null);
            fail("unknown sourceId must be rejected by Remote");
        } catch (Exception expected) { }
    }
}
