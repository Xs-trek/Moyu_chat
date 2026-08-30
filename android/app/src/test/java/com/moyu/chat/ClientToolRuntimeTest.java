package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ClientToolRuntimeTest {
    @Test public void webFetchDefinitionRequiresOneBareHttpsUrl() throws Exception {
        JSONArray definitions = ClientToolRuntime.canonicalDefinitions(ClientToolRuntime.WEB_FETCH);
        assertEquals(1, definitions.length());
        JSONObject definition = definitions.getJSONObject(0);
        JSONObject url = definition.getJSONObject("parameters").getJSONObject("properties").getJSONObject("url");
        assertEquals("^https://[^\\s]+$", url.optString("pattern"));
        assertTrue(definition.optString("description").contains("no prose"));
        assertTrue(url.optString("description").contains("Do not copy the user's sentence"));
    }

    @Test public void webFetchRuntimeRejectsFreeFormPromptInsteadOfSalvagingUrl() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> MoyuWebService.validateToolFetchArgument("请抓取 https://example.com/page 并总结内容"));
        assertTrue(error.getMessage().contains("exactly one absolute HTTPS URL"));
        assertEquals("https://example.com/page", MoyuWebService.validateToolFetchArgument("https://example.com/page"));
    }

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
