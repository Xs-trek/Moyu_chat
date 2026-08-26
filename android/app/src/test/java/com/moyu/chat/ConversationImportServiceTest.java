package com.moyu.chat;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ConversationImportServiceTest {
    private final ConversationImportService importer = new ConversationImportService();

    @Test public void chatGptUsesCurrentNodeAndCleansHiddenToolAndSystemState() throws Exception {
        String json = "[{\"title\":\"Branch test\",\"id\":\"conv-1\",\"current_node\":\"a2\",\"create_time\":1," +
                "\"mapping\":{" +
                "\"root\":{\"id\":\"root\",\"parent\":null,\"message\":{\"author\":{\"role\":\"system\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"You are a PC coding runtime\"]}}}," +
                "\"u1\":{\"id\":\"u1\",\"parent\":\"root\",\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"hello\"]}}}," +
                "\"call\":{\"id\":\"call\",\"parent\":\"u1\",\"message\":{\"author\":{\"role\":\"assistant\"},\"recipient\":\"browser\",\"content\":{\"content_type\":\"text\",\"parts\":[\"tool call\"]}}}," +
                "\"tool\":{\"id\":\"tool\",\"parent\":\"call\",\"message\":{\"author\":{\"role\":\"tool\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"tool result\"]}}}," +
                "\"hidden\":{\"id\":\"hidden\",\"parent\":\"tool\",\"message\":{\"author\":{\"role\":\"user\"},\"metadata\":{\"is_visually_hidden_from_conversation\":true},\"content\":{\"content_type\":\"model_editable_context\",\"parts\":[\"private profile\"]}}}," +
                "\"a1\":{\"id\":\"a1\",\"parent\":\"hidden\",\"message\":{\"author\":{\"role\":\"assistant\"},\"recipient\":\"all\",\"content\":{\"content_type\":\"text\",\"parts\":[\"visible answer\"]}}}," +
                "\"u2\":{\"id\":\"u2\",\"parent\":\"a1\",\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"continue\"]}}}," +
                "\"a2\":{\"id\":\"a2\",\"parent\":\"u2\",\"message\":{\"author\":{\"role\":\"assistant\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"current branch\"]}}}," +
                "\"alt\":{\"id\":\"alt\",\"parent\":\"u1\",\"message\":{\"author\":{\"role\":\"assistant\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"abandoned branch\"]}}}" +
                "}}]";
        List<ConversationImportService.ImportedConversation> out = new ArrayList<>();
        ConversationImportService.Report report = importer.importFile("chatgpt", "conversations.json",
                bytes(json), out::add);
        assertEquals(1, report.importedConversations);
        assertEquals(1, out.size());
        assertEquals(4, out.get(0).items.length());
        assertEquals("hello", out.get(0).items.getJSONObject(0).optString("text"));
        assertEquals("visible answer", out.get(0).items.getJSONObject(1).optString("text"));
        assertEquals("continue", out.get(0).items.getJSONObject(2).optString("text"));
        assertEquals("current branch", out.get(0).items.getJSONObject(3).optString("text"));
        String serialized = out.get(0).items.toString();
        assertFalse(serialized.contains("PC coding runtime"));
        assertFalse(serialized.contains("tool call"));
        assertFalse(serialized.contains("tool result"));
        assertFalse(serialized.contains("private profile"));
        assertFalse(serialized.contains("abandoned branch"));
    }

    @Test public void claudeUsesCurrentLeafAndKeepsOnlyTextBlocks() throws Exception {
        String json = "[{\"uuid\":\"c\",\"name\":\"Claude tree\",\"current_leaf_message_uuid\":\"a2\",\"created_at\":\"2026-08-01T00:00:00Z\",\"chat_messages\":[" +
                "{\"uuid\":\"u1\",\"parent_message_uuid\":null,\"sender\":\"human\",\"content\":[{\"type\":\"text\",\"text\":\"question\"}]}," +
                "{\"uuid\":\"a1\",\"parent_message_uuid\":\"u1\",\"sender\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"secret chain\"},{\"type\":\"tool_use\",\"name\":\"bash\"},{\"type\":\"text\",\"text\":\"visible\"}]}," +
                "{\"uuid\":\"u2\",\"parent_message_uuid\":\"a1\",\"sender\":\"human\",\"content\":[{\"type\":\"tool_result\",\"content\":\"runtime\"},{\"type\":\"text\",\"text\":\"next\"}]}," +
                "{\"uuid\":\"a2\",\"parent_message_uuid\":\"u2\",\"sender\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"current\"}]}," +
                "{\"uuid\":\"alt\",\"parent_message_uuid\":\"u2\",\"sender\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"alternate\"}]}]}]";
        List<ConversationImportService.ImportedConversation> out = new ArrayList<>();
        importer.importFile("claude", "conversations.json", bytes(json), out::add);
        assertEquals(1, out.size());
        assertEquals(4, out.get(0).items.length());
        assertEquals("question", out.get(0).items.getJSONObject(0).optString("text"));
        assertEquals("visible", out.get(0).items.getJSONObject(1).optString("text"));
        assertEquals("next", out.get(0).items.getJSONObject(2).optString("text"));
        assertEquals("current", out.get(0).items.getJSONObject(3).optString("text"));
        String serialized = out.get(0).items.toString();
        assertFalse(serialized.contains("secret chain"));
        assertFalse(serialized.contains("runtime"));
        assertFalse(serialized.contains("alternate"));
    }

    @Test public void ambiguousClaudeBranchesWithoutLeafAreSkippedInsteadOfGuessed() throws Exception {
        String json = "[{\"uuid\":\"c\",\"name\":\"ambiguous\",\"chat_messages\":[" +
                "{\"uuid\":\"u\",\"parent_message_uuid\":null,\"sender\":\"human\",\"text\":\"q\"}," +
                "{\"uuid\":\"a\",\"parent_message_uuid\":\"u\",\"sender\":\"assistant\",\"text\":\"one\"}," +
                "{\"uuid\":\"b\",\"parent_message_uuid\":\"u\",\"sender\":\"assistant\",\"text\":\"two\"}]}]";
        List<ConversationImportService.ImportedConversation> out = new ArrayList<>();
        ConversationImportService.Report report = importer.importFile("claude", "conversations.json", bytes(json), out::add);
        assertTrue(out.isEmpty());
        assertEquals(0, report.importedConversations);
        assertEquals(1, report.skippedConversations);
    }

    @Test public void zipContainerIsAcceptedButProviderSourceIsStillExplicit() throws Exception {
        String json = "[{\"title\":\"zip\",\"id\":\"z\",\"current_node\":\"u\",\"mapping\":{\"u\":{\"id\":\"u\",\"parent\":null,\"message\":{\"author\":{\"role\":\"user\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"hello\"]}}}}}]";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("conversations.json"));
            zip.write(json.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        List<ConversationImportService.ImportedConversation> out = new ArrayList<>();
        importer.importFile("chatgpt", "export.zip", new ByteArrayInputStream(bytes.toByteArray()), out::add);
        assertEquals(1, out.size());
        try {
            importer.importFile("auto", "export.zip", new ByteArrayInputStream(bytes.toByteArray()), ignored -> {});
            fail("source auto-detection must not be accepted");
        } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("source")); }
    }

    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
