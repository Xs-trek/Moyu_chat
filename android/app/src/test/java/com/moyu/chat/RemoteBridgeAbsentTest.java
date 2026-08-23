package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Bridge absence is a normal standalone state: no crash, available=false, no background retry. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class RemoteBridgeAbsentTest {

    @Test public void androidBridgeReportsUnavailableWhenRemoteIsNotInstalled() {
        AndroidRemoteDataBridge bridge = new AndroidRemoteDataBridge(RuntimeEnvironment.getApplication());
        RemoteDataBridge.Status status = bridge.status();
        assertFalse(status.available);
        assertEquals(0, status.contractVersion);
        assertNotNull(status.reason);
    }

    @Test public void bridgeOperationsFailClosedWithoutRemote() throws Exception {
        AndroidRemoteDataBridge bridge = new AndroidRemoteDataBridge(RuntimeEnvironment.getApplication());
        try { bridge.listSources(); fail("listSources must fail closed"); } catch (Exception expected) { }
        try { bridge.createSnapshot("whatever", null); fail("createSnapshot must fail closed"); } catch (Exception expected) { }
        try { bridge.submitHandoff(new JSONObject()); fail("submitHandoff must fail closed"); } catch (Exception expected) { }
    }

    @Test public void unavailableBridgeStubIsStableForTests() throws Exception {
        RemoteDataBridge bridge = new UnavailableRemoteDataBridge();
        assertFalse(bridge.status().available);
        assertEquals(0, bridge.listSources().length());
        try { bridge.createSnapshot("x", 1); fail(); } catch (IllegalStateException expected) { }
        try { bridge.submitHandoff(new JSONObject()); fail(); } catch (IllegalStateException expected) { }
    }

    @Test public void malformedSourceIdsAreRejectedBeforeAnyBridgeCall() {
        AndroidRemoteDataBridge bridge = new AndroidRemoteDataBridge(RuntimeEnvironment.getApplication());
        for (String bad : new String[]{null, "", "   ", "over-512"}) {
            try {
                String sourceId = "over-512".equals(bad) ? repeat('x', 513) : bad;
                bridge.createSnapshot(sourceId, null);
                fail("invalid source id must be rejected locally");
            } catch (Exception expected) { }
        }
        try { bridge.createSnapshot("ok", 0); fail("throughSeq must be positive"); } catch (Exception expected) { }
    }

    private static String repeat(char c, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(c);
        return out.toString();
    }
}
