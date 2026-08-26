package com.moyu.chat;

import static org.junit.Assert.*;

import android.database.Cursor;

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

/** Local FTS4 retrieval is language-neutral and derived index data follows source lifecycle. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LocalKnowledgeStoreTest {
    private MoyuChatApplication app;

    @Before public void setUp() {
        RuntimeEnvironment.getApplication().deleteDatabase("moyu-chat.db");
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
    }

    @Test public void retrievesRelevantEnglishAndCjkSnapshotExcerpts() throws Exception {
        ChatDatabase.Snapshot snapshot = snapshot("snapshot-rag", "chat");
        JSONArray items = new JSONArray()
                .put(message("user", "The allocator boundary is inside ring_buffer.c and must remain ABI compatible."))
                .put(message("assistant", "缓存失效问题最终定位在中断处理与 DMA 所有权切换。"))
                .put(message("assistant", "Unrelated discussion about UI spacing."));
        snapshot.sourceItemCount = items.length();
        app.database().saveSnapshot(snapshot, items);

        LocalKnowledgeStore store = new LocalKnowledgeStore(app.database());
        List<LocalKnowledgeStore.Hit> english = store.searchSnapshot(snapshot.id, "allocator ring buffer", 4);
        assertFalse(english.isEmpty());
        assertTrue(english.get(0).text.contains("allocator boundary"));

        List<LocalKnowledgeStore.Hit> chinese = store.searchSnapshot(snapshot.id, "DMA 中断所有权", 4);
        assertFalse(chinese.isEmpty());
        assertTrue(chinese.get(0).text.contains("DMA 所有权"));
    }

    @Test public void deletingSessionRemovesPlaintextFtsRowsAndMarker() throws Exception {
        ChatDatabase db = app.database();
        ChatDatabase.ModelProfile profile = profile(db);
        ChatDatabase.Session source = db.createSession(profile, "source", null, null, ReasoningPolicy.DEFAULT);
        ChatDatabase.Session current = db.createSession(profile, "current", null, null, ReasoningPolicy.DEFAULT);
        db.appendTimeline(source.id, "u", message("user", "privacy-secret-allocator-token"));
        LocalKnowledgeStore store = new LocalKnowledgeStore(db);
        assertFalse(store.searchUserMemory(current.id, null, "privacy secret allocator", 4, true).isEmpty());
        assertTrue(countScope(LocalKnowledgeStore.chatScope(source.id)) > 0);
        assertFalse(db.setting(LocalKnowledgeStore.indexMarkerKey(source.id), "").isEmpty());

        db.deleteSession(source.id);
        assertEquals(0, countScope(LocalKnowledgeStore.chatScope(source.id)));
        assertEquals("", db.setting(LocalKnowledgeStore.indexMarkerKey(source.id), ""));
    }

    @Test public void deletingReusableImportRemovesSnapshotFtsRows() throws Exception {
        ChatDatabase db = app.database();
        ChatDatabase.Snapshot imported = snapshot("import-rag", "import-chatgpt");
        JSONArray items = new JSONArray().put(message("user", "imported privacy token"));
        imported.sourceItemCount = items.length();
        db.saveSnapshot(imported, items);
        LocalKnowledgeStore store = new LocalKnowledgeStore(db);
        assertFalse(store.searchSnapshot(imported.id, "privacy token", 4).isEmpty());
        assertTrue(countScope(LocalKnowledgeStore.snapshotScope(imported.id)) > 0);
        db.deleteImportedSnapshot(imported.id);
        assertEquals(0, countScope(LocalKnowledgeStore.snapshotScope(imported.id)));
    }

    private ChatDatabase.ModelProfile profile(ChatDatabase db) {
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.id = "p"; provider.displayName = "P"; provider.baseUrl = "https://example.test/v1";
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; provider.model = "m";
        provider.contextTokens = 32000; provider.maxOutputTokens = 4096; db.saveProvider(provider);
        ChatDatabase.ModelProfile profile = db.firstModelProfile(provider.id);
        if (profile == null) throw new IllegalStateException("legacy model profile was not created");
        return profile;
    }

    private int countScope(String scope) {
        try (Cursor c = app.database().getReadableDatabase().rawQuery("SELECT COUNT(*) FROM knowledge_fts WHERE scope_id=?", new String[]{scope})) {
            assertTrue(c.moveToFirst()); return c.getInt(0);
        }
    }

    private static ChatDatabase.Snapshot snapshot(String id, String sourceType) {
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.id = id; snapshot.sourceType = sourceType; snapshot.sourceId = "source-" + id;
        snapshot.sourceTitle = "history"; snapshot.createdAt = Instant.now().toString(); return snapshot;
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }
}
