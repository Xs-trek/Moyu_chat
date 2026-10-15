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
        RuntimeEnvironment.getApplication().deleteDatabase(ChatDatabase.FILE_NAME);
        app = (MoyuChatApplication) RuntimeEnvironment.getApplication();
    }

    @Test public void retrievesRelevantEnglishAndCjkSnapshotExcerpts() throws Exception {
        ChatDatabase.Snapshot snapshot = snapshot("snapshot-rag");
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

    @Test public void deletingSessionRemovesPlaintextFtsRows() throws Exception {
        ChatDatabase db = app.database();
        ChatDatabase.ModelProfile profile = profile(db);
        ChatDatabase.Session source = db.createSession(profile, "source", null, null, ReasoningPolicy.DEFAULT);
        ChatDatabase.Session current = db.createSession(profile, "current", null, null, ReasoningPolicy.DEFAULT);
        db.appendTimeline(source.id, "u", message("user", "privacy-secret-allocator-token"));
        LocalKnowledgeStore store = new LocalKnowledgeStore(db);
        assertFalse(store.searchUserMemory(current.id, "privacy secret allocator", 4).isEmpty());
        assertTrue(countScope(LocalKnowledgeStore.chatScope(source.id)) > 0);
        assertFalse("the index marker lives on the session row", db.knowledgeIndexedAt(source.id).isEmpty());

        db.deleteSession(source.id);
        assertEquals(0, countScope(LocalKnowledgeStore.chatScope(source.id)));
    }

    @Test public void deletingLastBranchRemovesSnapshotAndItsFtsRows() throws Exception {
        ChatDatabase db = app.database();
        ChatDatabase.Snapshot origin = snapshot("branch-rag");
        JSONArray items = new JSONArray().put(message("user", "branched privacy token"));
        origin.sourceItemCount = items.length();
        db.saveSnapshot(origin, items);
        ChatDatabase.Session branch = db.createSession(profile(db), "branch", origin.id, null, ReasoningPolicy.DEFAULT);
        LocalKnowledgeStore store = new LocalKnowledgeStore(db);
        assertFalse(store.searchSnapshot(origin.id, "privacy token", 4).isEmpty());
        assertTrue(countScope(LocalKnowledgeStore.snapshotScope(origin.id)) > 0);
        db.deleteSession(branch.id);
        assertNull("unreferenced branch snapshot is collected", db.getSnapshot(origin.id));
        assertEquals(0, countScope(LocalKnowledgeStore.snapshotScope(origin.id)));
    }

    @Test public void theEndOfALongChineseAnswerIsFindableAndCommonCharactersAreNotMemory() throws Exception {
        ChatDatabase db = app.database();
        ChatDatabase.ModelProfile profile = profile(db);
        ChatDatabase.Session source = db.createSession(profile, "source", null, null, ReasoningPolicy.DEFAULT);
        ChatDatabase.Session other = db.createSession(profile, "other", null, null, ReasoningPolicy.DEFAULT);
        ChatDatabase.Session current = db.createSession(profile, "current", null, null, ReasoningPolicy.DEFAULT);
        StringBuilder answer = new StringBuilder();
        for (int i = 0; i < 40; i++) answer.append("这是一个关于日常安排的普通段落，没有什么特别的内容。\n");
        answer.append("最后补充：我家的猫叫阿橘，每天早上七点要喂罐头。");
        db.appendTimeline(source.id, "a", message("assistant", answer.toString()));
        db.appendTimeline(other.id, "b", message("user", "今天的天气是不是很好，我们是不是可以出去走走？"));
        LocalKnowledgeStore store = new LocalKnowledgeStore(db);

        List<LocalKnowledgeStore.Hit> cat = store.searchUserMemory(current.id, "我的猫叫什么名字", 4);
        assertFalse("text far past the first 32 characters is indexed", cat.isEmpty());
        assertTrue(cat.get(0).text.contains("阿橘"));
        assertTrue("a hit is a chunk, not the whole long answer", cat.get(0).text.length() <= KnowledgeLexicon.CHUNK_CHARS);
        for (LocalKnowledgeStore.Hit hit : store.searchUserMemory(current.id, "阿橘早上喂罐头了吗", 4)) {
            assertNotEquals("a chat sharing only filler words does not come back", "other", hit.sourceTitle);
        }
        assertTrue("an unrelated question sharing only single characters retrieves nothing", store.searchUserMemory(current.id, "量子计算机的原理", 4).isEmpty());
        assertFalse("a one-character question still finds its memory", store.searchUserMemory(current.id, "猫", 4).isEmpty());
    }

    @Test public void chatsAreIndexedIncrementallyAndUnfinishedAnswersWait() throws Exception {
        ChatDatabase db = app.database();
        ChatDatabase.ModelProfile profile = profile(db);
        ChatDatabase.Session source = db.createSession(profile, "source", null, null, ReasoningPolicy.DEFAULT);
        ChatDatabase.Session current = db.createSession(profile, "current", null, null, ReasoningPolicy.DEFAULT);
        db.appendTimeline(source.id, "u1", message("user", "first question about quaternion rotation"));
        db.appendTimeline(source.id, "a1", message("assistant", "streaming answer about gimbal lock").put("streaming", true));
        LocalKnowledgeStore store = new LocalKnowledgeStore(db);
        assertFalse(store.searchUserMemory(current.id, "quaternion rotation", 4).isEmpty());
        assertEquals("the marker stops before the unfinished answer", "v2:1", db.knowledgeIndexedAt(source.id));
        assertTrue(store.searchUserMemory(current.id, "gimbal lock", 4).isEmpty());

        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT item_key FROM timeline WHERE session_id=? AND local_seq=2", new String[]{source.id})) {
            assertTrue(c.moveToFirst());
            db.replaceTimelineBody(c.getString(0), message("assistant", "final answer about gimbal lock").put("localSeq", 2));
        }
        int before = countScope(LocalKnowledgeStore.chatScope(source.id));
        assertFalse("the finished answer is picked up", store.searchUserMemory(current.id, "gimbal lock", 4).isEmpty());
        assertEquals("only the new row was added", before + 1, countScope(LocalKnowledgeStore.chatScope(source.id)));
        assertEquals("v2:2", db.knowledgeIndexedAt(source.id));

        db.markKnowledgeIndexed(source.id, "2026-10-01T00:00:00Z"); // an index from the old format
        store.searchUserMemory(current.id, "quaternion", 4);
        assertEquals("an old-format index is rebuilt once, not duplicated", before + 1, countScope(LocalKnowledgeStore.chatScope(source.id)));
    }

    @Test public void historyRecallSearchesOnlyThisChatAndOnlyWhatThePromptNoLongerCarries() throws Exception {
        ChatDatabase db = app.database();
        ChatDatabase.ModelProfile profile = profile(db);
        ChatDatabase.Session current = db.createSession(profile, "current", null, null, ReasoningPolicy.DEFAULT);
        ChatDatabase.Session other = db.createSession(profile, "other", null, null, ReasoningPolicy.DEFAULT);
        db.appendTimeline(current.id, "u1", message("user", "The staging database password rotates every Tuesday at 09:30."));
        db.appendTimeline(current.id, "a1", message("assistant", "Noted: rotation on Tuesday 09:30."));
        db.appendTimeline(current.id, "u2", message("user", "Now the rotation window moves, what changes for the cron job?"));
        db.appendTimeline(other.id, "o1", message("user", "Rotation schedule of another project: Friday 18:00."));

        LocalKnowledgeStore store = new LocalKnowledgeStore(db);
        List<LocalKnowledgeStore.Hit> hits = store.searchSessionHistory(current.id, "rotation Tuesday", 6);
        assertFalse(hits.isEmpty());
        for (LocalKnowledgeStore.Hit hit : hits) assertEquals("never another Chat", current.id, hit.sourceId);

        TurnPipeline pipeline = new TurnPipeline(null, db, null, null, null);
        JSONArray carried = new JSONArray().put(new JSONObject().put("role", "user").put("text", "Now the rotation window moves, what changes for the cron job?"));
        CanonicalPrompt sent = new CanonicalPrompt("", carried, true, 0, 0, carried.length());
        String recalled = pipeline.recallHistory(current.id, sent, "rotation Tuesday 09:30");
        assertTrue(recalled.contains("every Tuesday at 09:30"));
        assertTrue(recalled.contains("[message #1, user]"));
        assertFalse("what the prompt already carries is not repeated", recalled.contains("cron job"));
        assertFalse(recalled.contains("Friday"));
        assertTrue(pipeline.recallHistory(current.id, sent, "quaternion").startsWith("No earlier message"));
    }

    @Test public void queryTermsKeepWordsAndBigramsAndChunksBreakNaturally() {
        List<String> terms = KnowledgeLexicon.queryTerms("DMA 中断所有权 猫");
        assertTrue(terms.contains("dma"));
        assertTrue("bigram", terms.contains("b" + Integer.toHexString('中') + "x" + Integer.toHexString('断')));
        assertFalse("no unigram for a character inside a run", terms.contains("u" + Integer.toHexString('中')));
        assertTrue("a lone character keeps its unigram", terms.contains("u" + Integer.toHexString('猫')));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 30; i++) text.append("第").append(i).append("句话写在这里。");
        List<String> chunks = KnowledgeLexicon.chunks(text.toString());
        assertTrue(chunks.size() >= 1);
        for (String chunk : chunks) assertTrue(chunk.length() <= KnowledgeLexicon.CHUNK_CHARS);
        assertEquals(text.toString(), String.join("", chunks));
    }

    private ChatDatabase.ModelProfile profile(ChatDatabase db) {
        ChatDatabase.Provider provider = new ChatDatabase.Provider();
        provider.id = "p"; provider.displayName = "P"; provider.baseUrl = "https://example.test/v1";
        provider.protocol = OpenAiCompatibleTransport.CHAT_COMPLETIONS; provider.model = "m";
        provider.contextTokens = 32000; provider.maxOutputTokens = 4096;
        return TestDeployments.save(db, provider);
    }

    private int countScope(String scope) {
        try (Cursor c = app.database().getReadableDatabase().rawQuery("SELECT COUNT(*) FROM knowledge_fts WHERE scope_id=?", new String[]{scope})) {
            assertTrue(c.moveToFirst()); return c.getInt(0);
        }
    }

    private static ChatDatabase.Snapshot snapshot(String id) {
        ChatDatabase.Snapshot snapshot = new ChatDatabase.Snapshot();
        snapshot.id = id; snapshot.sourceSessionId = "source-" + id; snapshot.contentHash = "hash-" + id;
        snapshot.sourceTitle = "history"; snapshot.createdAt = Instant.now().toString(); return snapshot;
    }

    private static JSONObject message(String role, String text) throws Exception {
        return new JSONObject().put("kind", "message").put("role", role).put("text", text).put("createdAt", Instant.now().toString());
    }
}
