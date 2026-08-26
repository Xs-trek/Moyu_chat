package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.util.List;

/** Local FTS4 retrieval is snapshot-scoped and language-neutral without a bundled SQLite dependency. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalKnowledgeStoreTest {
    private MoyuChatApplication app;

    @Before public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("moyu-chat.db");
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
    }

    @Test public void retrievesRelevantEnglishAndCjkSnapshotExcerpts() throws Exception {
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.id = "snapshot-rag"; snapshot.sourceType = "chat"; snapshot.sourceId = "source";
        snapshot.sourceTitle = "history"; snapshot.createdAt = Instant.now().toString(); snapshot.sourceItemCount = 3;
        JSONArray items = new JSONArray()
                .put(message("user", "The allocator boundary is inside ring_buffer.c and must remain ABI compatible."))
                .put(message("assistant", "缓存失效问题最终定位在中断处理与 DMA 所有权切换。"))
                .put(message("assistant", "Unrelated discussion about UI spacing."));
        app.database().saveSnapshot(snapshot, items);

        LocalKnowledgeStore store = new LocalKnowledgeStore(app.database());
        List<LocalKnowledgeStore.Hit> english = store.searchSnapshot(snapshot.id, "allocator ring buffer", 4);
        assertFalse(english.isEmpty());
        assertTrue(english.get(0).text.contains("allocator boundary"));

        List<LocalKnowledgeStore.Hit> chinese = store.searchSnapshot(snapshot.id, "DMA 中断所有权", 4);
        assertFalse(chinese.isEmpty());
        assertTrue(chinese.get(0).text.contains("DMA 所有权"));
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }
}
