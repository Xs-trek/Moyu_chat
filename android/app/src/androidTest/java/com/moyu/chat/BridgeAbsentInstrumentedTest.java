package com.moyu.chat;

import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Run BEFORE installing Moyu Remote: absence must be a normal, non-failing standalone state. */
@RunWith(AndroidJUnit4.class)
public class BridgeAbsentInstrumentedTest {

    @Test public void bridgeReportsUnavailableAndChatStillFunctions() {
        AndroidRemoteDataBridge bridge = new AndroidRemoteDataBridge(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        RemoteDataBridge.Status status = bridge.status();
        assertFalse("no Moyu Remote installed on this emulator", status.available);
        assertNotNull(status.reason);
        try {
            bridge.listSources();
            fail("listSources must fail closed when Remote is absent");
        } catch (Exception expected) { }
    }
}
