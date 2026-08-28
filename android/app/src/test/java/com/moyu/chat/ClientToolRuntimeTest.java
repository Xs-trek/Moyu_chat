package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientToolRuntimeTest {
    @Test public void failedDelegationDoesNotCountAsSuccessfulSubagent() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            JSONArray calls = new JSONArray().put(new JSONObject()
                    .put("id", "call-1")
                    .put("name", ClientToolRuntime.DELEGATE_SUBTASK)
                    .put("arguments", new JSONObject().put("task", "work")));
            ClientToolRuntime.BatchResult result = ClientToolRuntime.executeBatch(
                    calls, 1, "", executor, null, null, null, null, "", "", "", null);
            assertEquals(1, result.historyEntries.length());
            assertTrue(result.historyEntries.getJSONObject(0).has("error"));
            assertEquals(0, result.subagentCount);
            assertEquals(0, result.childInputTokens);
            assertEquals(0, result.childOutputTokens);
        } finally {
            executor.shutdownNow();
        }
    }
}
