package com.moyu.chat;

import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Explicit-source importer for official ChatGPT/Claude data exports.
 *
 * Source format is chosen by the user before file selection; this class never guesses provider from
 * message contents. Only user-visible user/assistant text is normalized. System/developer/tool
 * authority, hidden context, thinking/reasoning and tool state are deliberately discarded.
 */
final class ConversationImportService {
    static final String SOURCE_CHATGPT = "chatgpt";
    static final String SOURCE_CLAUDE = "claude";

    private static final int MAX_CONVERSATIONS = 20_000;
    private static final int MAX_NODES_PER_CONVERSATION = 20_000;
    private static final int MAX_TEXT_CHARS_PER_MESSAGE = 200_000;
    private static final int MAX_TITLE_CHARS = 512;

    interface Consumer { void accept(ImportedConversation conversation) throws Exception; }

    static final class ImportedConversation {
        final String sourceId;
        final String title;
        final String createdAt;
        final String updatedAt;
        final JSONArray items;
        final int sourceMessageCount;
        final int skippedMessageCount;

        ImportedConversation(String sourceId, String title, String createdAt, String updatedAt,
                             JSONArray items, int sourceMessageCount, int skippedMessageCount) {
            this.sourceId = sourceId;
            this.title = title;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.items = items == null ? new JSONArray() : items;
            this.sourceMessageCount = Math.max(0, sourceMessageCount);
            this.skippedMessageCount = Math.max(0, skippedMessageCount);
        }
    }

    static final class Report {
        int importedConversations;
        int skippedConversations;
        int skippedMessages;
        int jsonEntries;
        String lastSkipReason = "";

        String summary(String source) {
            String name = SOURCE_CLAUDE.equals(source) ? "Claude" : "ChatGPT";
            StringBuilder out = new StringBuilder(name).append(" 导入完成：")
                    .append(importedConversations).append(" 个会话");
            if (skippedConversations > 0) out.append("，跳过 ").append(skippedConversations).append(" 个无法确定的会话");
            if (skippedMessages > 0) out.append("，清洗 ").append(skippedMessages).append(" 条非 Chat 内容");
            if (!lastSkipReason.isEmpty()) out.append("。最近跳过原因：").append(lastSkipReason);
            return out.toString();
        }
    }

    private static final class ImportSkip extends Exception {
        ImportSkip(String message) { super(message); }
    }

    private static final class MessageNode {
        String id = "";
        String parent = "";
        String role = "";
        String text = "";
        String createdAt = "";
        String contentType = "";
        String recipient = "";
        boolean hidden;
        boolean zeroWeight;
        int ordinal;
    }

    private static final class ClaudeMessage {
        String id = "";
        String parent = "";
        String sender = "";
        String text = "";
        String createdAt = "";
        int index = -1;
        int ordinal;
    }

    Report importFile(String source, String displayName, InputStream sourceStream, Consumer consumer) throws Exception {
        String normalizedSource = normalizeSource(source);
        if (sourceStream == null) throw new IllegalArgumentException("import file missing");
        if (consumer == null) throw new IllegalArgumentException("import consumer missing");
        Report report = new Report();
        BufferedInputStream buffered = sourceStream instanceof BufferedInputStream
                ? (BufferedInputStream) sourceStream : new BufferedInputStream(sourceStream, 64 * 1024);
        boolean zip = isZip(displayName, buffered);
        if (zip) importZip(normalizedSource, buffered, consumer, report);
        else importJsonArray(normalizedSource, buffered, consumer, report);
        if (report.jsonEntries == 0) throw new IllegalArgumentException("未找到官方 conversations JSON 数据");
        return report;
    }

    private void importZip(String source, InputStream input, Consumer consumer, Report report) throws Exception {
        ZipInputStream zip = new ZipInputStream(input, StandardCharsets.UTF_8);
        boolean found = false;
        for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
            if (entry.isDirectory()) { zip.closeEntry(); continue; }
            String base = baseName(entry.getName()).toLowerCase(Locale.ROOT);
            boolean conversationJson = SOURCE_CHATGPT.equals(source)
                    ? base.startsWith("conversations") && base.endsWith(".json")
                    : "conversations.json".equals(base);
            if (!conversationJson) { zip.closeEntry(); continue; }
            found = true;
            importJsonArray(source, zip, consumer, report);
            zip.closeEntry();
        }
        if (!found) throw new IllegalArgumentException("ZIP 中没有找到 " + (SOURCE_CLAUDE.equals(source) ? "conversations.json" : "conversations*.json"));
    }

    private void importJsonArray(String source, InputStream input, Consumer consumer, Report report) throws Exception {
        JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        reader.setLenient(false);
        if (reader.peek() != JsonToken.BEGIN_ARRAY) throw new IllegalArgumentException("官方 conversations JSON 顶层应为数组");
        report.jsonEntries++;
        reader.beginArray();
        while (reader.hasNext()) {
            if (report.importedConversations + report.skippedConversations >= MAX_CONVERSATIONS) {
                throw new IllegalArgumentException("导出包含过多会话，超过本地安全上限 " + MAX_CONVERSATIONS);
            }
            try {
                ImportedConversation conversation = SOURCE_CHATGPT.equals(source)
                        ? readChatGptConversation(reader) : readClaudeConversation(reader);
                if (conversation.items.length() == 0) throw new ImportSkip("清洗后没有可用的用户/助手正文");
                consumer.accept(conversation);
                report.importedConversations++;
                report.skippedMessages += conversation.skippedMessageCount;
            } catch (ImportSkip skipped) {
                report.skippedConversations++;
                report.lastSkipReason = safeReason(skipped.getMessage());
            }
        }
        reader.endArray();
    }

    private ImportedConversation readChatGptConversation(JsonReader reader) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); throw new ImportSkip("ChatGPT conversation 不是对象"); }
        String title = "";
        String sourceId = "";
        String currentNode = "";
        String createdAt = "";
        String updatedAt = "";
        Map<String, MessageNode> nodes = new LinkedHashMap<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "title": title = readString(reader); break;
                case "id": case "conversation_id": if (sourceId.isEmpty()) sourceId = readString(reader); else reader.skipValue(); break;
                case "current_node": currentNode = readString(reader); break;
                case "create_time": createdAt = readEpochInstant(reader); break;
                case "update_time": updatedAt = readEpochInstant(reader); break;
                case "mapping": readChatGptMapping(reader, nodes); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        if (nodes.isEmpty()) throw new ImportSkip("ChatGPT mapping 为空");
        List<MessageNode> path = activePath(nodes, currentNode, "ChatGPT current_node");
        JSONArray items = new JSONArray();
        int skipped = 0;
        for (MessageNode node : path) {
            if (!visibleChatGpt(node)) { if (node.role != null && !node.role.isEmpty()) skipped++; continue; }
            String at = !node.createdAt.isEmpty() ? node.createdAt : !createdAt.isEmpty() ? createdAt : Instant.EPOCH.toString();
            items.put(new JSONObject().put("kind", "message").put("role", node.role).put("text", node.text).put("createdAt", at));
        }
        String stableId = !sourceId.isEmpty() ? sourceId : !currentNode.isEmpty() ? currentNode : digest(title + "\n" + createdAt + "\n" + nodes.size());
        return new ImportedConversation(stableId, cleanTitle(title, "ChatGPT 导入会话"),
                emptyInstant(createdAt), emptyInstant(updatedAt), items, path.size(), skipped);
    }

    private void readChatGptMapping(JsonReader reader, Map<String, MessageNode> nodes) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return; }
        reader.beginObject();
        int ordinal = 0;
        while (reader.hasNext()) {
            if (nodes.size() >= MAX_NODES_PER_CONVERSATION) throw new ImportSkip("单个 ChatGPT 会话节点过多");
            String key = reader.nextName();
            MessageNode node = readChatGptNode(reader, key, ordinal++);
            nodes.put(node.id.isEmpty() ? key : node.id, node);
        }
        reader.endObject();
    }

    private MessageNode readChatGptNode(JsonReader reader, String key, int ordinal) throws Exception {
        MessageNode out = new MessageNode(); out.id = key; out.ordinal = ordinal;
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return out; }
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("id".equals(name)) { String id = readString(reader); if (!id.isEmpty()) out.id = id; }
            else if ("parent".equals(name)) out.parent = readString(reader);
            else if ("message".equals(name)) readChatGptMessage(reader, out);
            else reader.skipValue();
        }
        reader.endObject();
        return out;
    }

    private void readChatGptMessage(JsonReader reader, MessageNode out) throws Exception {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return; }
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return; }
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "author": out.role = readAuthorRole(reader); break;
                case "content": readChatGptContent(reader, out); break;
                case "create_time": out.createdAt = readEpochInstant(reader); break;
                case "recipient": out.recipient = readString(reader); break;
                case "weight": out.zeroWeight = readDouble(reader, 1d) == 0d; break;
                case "metadata": out.hidden = readHiddenMetadata(reader); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
    }

    private static String readAuthorRole(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return ""; }
        String role = "";
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("role".equals(name)) role = readString(reader); else reader.skipValue();
        }
        reader.endObject();
        return role;
    }

    private void readChatGptContent(JsonReader reader, MessageNode out) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return; }
        ArrayList<String> parts = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("content_type".equals(name)) out.contentType = readString(reader);
            else if ("parts".equals(name)) readVisibleParts(reader, parts);
            else if ("text".equals(name) && reader.peek() == JsonToken.STRING) parts.add(reader.nextString());
            else reader.skipValue();
        }
        reader.endObject();
        out.text = joinText(parts);
    }

    private void readVisibleParts(JsonReader reader, List<String> out) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return; }
        reader.beginArray();
        while (reader.hasNext()) {
            JsonToken token = reader.peek();
            if (token == JsonToken.STRING) out.add(reader.nextString());
            else if (token == JsonToken.BEGIN_OBJECT) {
                String type = "", text = "";
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if ("type".equals(name)) type = readString(reader);
                    else if ("text".equals(name) && reader.peek() == JsonToken.STRING) text = reader.nextString();
                    else reader.skipValue();
                }
                reader.endObject();
                if (!text.isEmpty() && (type.isEmpty() || "text".equals(type) || "input_text".equals(type) || "output_text".equals(type))) out.add(text);
            } else reader.skipValue();
        }
        reader.endArray();
    }

    private static boolean readHiddenMetadata(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return false; }
        boolean hidden = false;
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("is_visually_hidden_from_conversation".equals(name)) hidden = readBoolean(reader, false);
            else reader.skipValue();
        }
        reader.endObject();
        return hidden;
    }

    private static boolean visibleChatGpt(MessageNode node) {
        if (node == null || node.hidden || node.zeroWeight) return false;
        if (!("user".equals(node.role) || "assistant".equals(node.role))) return false;
        if (!node.recipient.isEmpty() && !"all".equals(node.recipient)) return false;
        String type = node.contentType == null ? "" : node.contentType.trim().toLowerCase(Locale.ROOT);
        if (!(type.isEmpty() || "text".equals(type) || "multimodal_text".equals(type))) return false;
        return node.text != null && !node.text.trim().isEmpty();
    }

    private ImportedConversation readClaudeConversation(JsonReader reader) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); throw new ImportSkip("Claude conversation 不是对象"); }
        String title = "", sourceId = "", currentLeaf = "", createdAt = "", updatedAt = "";
        ArrayList<ClaudeMessage> messages = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "uuid": sourceId = readString(reader); break;
                case "name": title = readString(reader); break;
                case "current_leaf_message_uuid": currentLeaf = readString(reader); break;
                case "created_at": createdAt = readString(reader); break;
                case "updated_at": updatedAt = readString(reader); break;
                case "chat_messages": readClaudeMessages(reader, messages); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        if (messages.isEmpty()) throw new ImportSkip("Claude chat_messages 为空");
        List<ClaudeMessage> path = claudeActivePath(messages, currentLeaf);
        JSONArray items = new JSONArray();
        int skipped = 0;
        for (ClaudeMessage message : path) {
            String role = "human".equals(message.sender) || "user".equals(message.sender) ? "user"
                    : "assistant".equals(message.sender) ? "assistant" : "";
            if (role.isEmpty() || message.text == null || message.text.trim().isEmpty()) { skipped++; continue; }
            String at = !message.createdAt.isEmpty() ? message.createdAt : !createdAt.isEmpty() ? createdAt : Instant.EPOCH.toString();
            items.put(new JSONObject().put("kind", "message").put("role", role).put("text", message.text).put("createdAt", at));
        }
        String stableId = !sourceId.isEmpty() ? sourceId : !currentLeaf.isEmpty() ? currentLeaf : digest(title + "\n" + createdAt + "\n" + messages.size());
        return new ImportedConversation(stableId, cleanTitle(title, "Claude 导入会话"),
                emptyInstant(createdAt), emptyInstant(updatedAt), items, path.size(), skipped);
    }

    private void readClaudeMessages(JsonReader reader, List<ClaudeMessage> out) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return; }
        reader.beginArray(); int ordinal = 0;
        while (reader.hasNext()) {
            if (out.size() >= MAX_NODES_PER_CONVERSATION) throw new ImportSkip("单个 Claude 会话消息过多");
            out.add(readClaudeMessage(reader, ordinal++));
        }
        reader.endArray();
    }

    private ClaudeMessage readClaudeMessage(JsonReader reader, int ordinal) throws Exception {
        ClaudeMessage out = new ClaudeMessage(); out.ordinal = ordinal;
        if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); return out; }
        String fallbackText = "";
        boolean sawContent = false;
        ArrayList<String> contentText = new ArrayList<>();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "uuid": out.id = readString(reader); break;
                case "parent_message_uuid": out.parent = readString(reader); break;
                case "sender": case "role": out.sender = readString(reader); break;
                case "created_at": out.createdAt = readString(reader); break;
                case "index": out.index = readInt(reader, -1); break;
                case "text": fallbackText = readString(reader); break;
                case "content": sawContent = true; readClaudeContent(reader, contentText); break;
                default: reader.skipValue();
            }
        }
        reader.endObject();
        out.text = sawContent ? joinText(contentText) : clipText(fallbackText);
        return out;
    }

    private void readClaudeContent(JsonReader reader, List<String> out) throws Exception {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) { reader.skipValue(); return; }
        reader.beginArray();
        while (reader.hasNext()) {
            if (reader.peek() != JsonToken.BEGIN_OBJECT) { reader.skipValue(); continue; }
            String type = "", text = "";
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("type".equals(name)) type = readString(reader);
                else if ("text".equals(name) && reader.peek() == JsonToken.STRING) text = reader.nextString();
                else reader.skipValue();
            }
            reader.endObject();
            // thinking/tool_use/tool_result/image/artifact blocks are intentionally excluded.
            if ("text".equals(type) && !text.isEmpty()) out.add(text);
        }
        reader.endArray();
    }

    private static List<ClaudeMessage> claudeActivePath(List<ClaudeMessage> messages, String currentLeaf) throws ImportSkip {
        Map<String, ClaudeMessage> byId = new HashMap<>();
        boolean hasParentGraph = false;
        for (ClaudeMessage message : messages) {
            if (message.id != null && !message.id.isEmpty()) byId.put(message.id, message);
            if (message.parent != null && !message.parent.isEmpty()) hasParentGraph = true;
        }
        if (hasParentGraph) {
            String leaf = currentLeaf != null && byId.containsKey(currentLeaf) ? currentLeaf : uniqueClaudeLeaf(messages, byId);
            if (leaf == null || leaf.isEmpty()) throw new ImportSkip("Claude 导出包含多个分支但缺少 current_leaf_message_uuid");
            ArrayList<ClaudeMessage> reverse = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            ClaudeMessage current = byId.get(leaf);
            while (current != null) {
                if (!current.id.isEmpty() && !seen.add(current.id)) throw new ImportSkip("Claude 消息父链存在循环");
                reverse.add(current);
                current = current.parent == null || current.parent.isEmpty() ? null : byId.get(current.parent);
            }
            Collections.reverse(reverse);
            return reverse;
        }
        // Legacy exports are flat; retain source order. If explicit indexes exist, use them only as a deterministic refinement.
        ArrayList<ClaudeMessage> flat = new ArrayList<>(messages);
        boolean allIndexed = !flat.isEmpty(); for (ClaudeMessage message : flat) if (message.index < 0) { allIndexed = false; break; }
        if (allIndexed) flat.sort(Comparator.comparingInt(message -> message.index));
        return flat;
    }

    private static String uniqueClaudeLeaf(List<ClaudeMessage> messages, Map<String, ClaudeMessage> byId) {
        Set<String> parents = new HashSet<>();
        for (ClaudeMessage message : messages) if (message.parent != null && !message.parent.isEmpty()) parents.add(message.parent);
        String leaf = null; int leaves = 0;
        for (ClaudeMessage message : messages) {
            if (message.id == null || message.id.isEmpty() || !byId.containsKey(message.id) || parents.contains(message.id)) continue;
            leaf = message.id; leaves++;
            if (leaves > 1) return null;
        }
        return leaves == 1 ? leaf : null;
    }

    private static List<MessageNode> activePath(Map<String, MessageNode> nodes, String requestedLeaf, String label) throws ImportSkip {
        String leaf = requestedLeaf != null && nodes.containsKey(requestedLeaf) ? requestedLeaf : uniqueLeaf(nodes);
        if (leaf == null || leaf.isEmpty()) throw new ImportSkip(label + " 缺失且存在多个分支");
        ArrayList<MessageNode> reverse = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        MessageNode current = nodes.get(leaf);
        while (current != null) {
            if (!current.id.isEmpty() && !seen.add(current.id)) throw new ImportSkip("ChatGPT mapping 父链存在循环");
            reverse.add(current);
            current = current.parent == null || current.parent.isEmpty() ? null : nodes.get(current.parent);
        }
        Collections.reverse(reverse);
        return reverse;
    }

    private static String uniqueLeaf(Map<String, MessageNode> nodes) {
        Set<String> parents = new HashSet<>();
        for (MessageNode node : nodes.values()) if (node.parent != null && !node.parent.isEmpty()) parents.add(node.parent);
        String leaf = null; int count = 0;
        for (String id : nodes.keySet()) {
            if (parents.contains(id)) continue;
            leaf = id; count++;
            if (count > 1) return null;
        }
        return count == 1 ? leaf : null;
    }

    private static boolean isZip(String displayName, BufferedInputStream input) throws IOException {
        String name = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) return true;
        input.mark(8);
        int a = input.read(), b = input.read(), c = input.read(), d = input.read();
        input.reset();
        return a == 'P' && b == 'K' && ((c == 3 && d == 4) || (c == 5 && d == 6) || (c == 7 && d == 8));
    }

    private static String normalizeSource(String source) {
        String value = source == null ? "" : source.trim().toLowerCase(Locale.ROOT);
        if (SOURCE_CHATGPT.equals(value) || SOURCE_CLAUDE.equals(value)) return value;
        throw new IllegalArgumentException("unsupported import source");
    }

    private static String readString(JsonReader reader) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.NULL) { reader.nextNull(); return ""; }
        if (token == JsonToken.STRING) return reader.nextString();
        if (token == JsonToken.NUMBER) return reader.nextString();
        reader.skipValue(); return "";
    }

    private static int readInt(JsonReader reader, int fallback) throws IOException {
        try {
            JsonToken token = reader.peek();
            if (token == JsonToken.NUMBER) return reader.nextInt();
            if (token == JsonToken.STRING) return Integer.parseInt(reader.nextString());
            if (token == JsonToken.NULL) reader.nextNull(); else reader.skipValue();
        } catch (Exception ignored) { }
        return fallback;
    }

    private static double readDouble(JsonReader reader, double fallback) throws IOException {
        try {
            JsonToken token = reader.peek();
            if (token == JsonToken.NUMBER) return reader.nextDouble();
            if (token == JsonToken.STRING) return Double.parseDouble(reader.nextString());
            if (token == JsonToken.NULL) reader.nextNull(); else reader.skipValue();
        } catch (Exception ignored) { }
        return fallback;
    }

    private static boolean readBoolean(JsonReader reader, boolean fallback) throws IOException {
        JsonToken token = reader.peek();
        if (token == JsonToken.BOOLEAN) return reader.nextBoolean();
        if (token == JsonToken.STRING) return Boolean.parseBoolean(reader.nextString());
        if (token == JsonToken.NULL) reader.nextNull(); else reader.skipValue();
        return fallback;
    }

    private static String readEpochInstant(JsonReader reader) throws IOException {
        double value = readDouble(reader, Double.NaN);
        if (Double.isNaN(value) || value <= 0d) return "";
        try { return Instant.ofEpochMilli((long) (value * 1000d)).toString(); }
        catch (Exception ignored) { return ""; }
    }

    private static String joinText(List<String> parts) {
        StringBuilder out = new StringBuilder();
        for (String raw : parts) {
            String text = clipText(raw);
            if (text.trim().isEmpty()) continue;
            if (out.length() > 0) out.append("\n\n");
            out.append(text);
            if (out.length() >= MAX_TEXT_CHARS_PER_MESSAGE) break;
        }
        return clipText(out.toString()).trim();
    }

    private static String clipText(String value) {
        String text = value == null ? "" : value;
        return text.length() <= MAX_TEXT_CHARS_PER_MESSAGE ? text : text.substring(0, MAX_TEXT_CHARS_PER_MESSAGE) + "…";
    }

    private static String cleanTitle(String value, String fallback) {
        String title = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        if (title.isEmpty()) title = fallback;
        return title.length() <= MAX_TITLE_CHARS ? title : title.substring(0, MAX_TITLE_CHARS);
    }

    private static String emptyInstant(String value) { return value == null || value.trim().isEmpty() ? Instant.EPOCH.toString() : value.trim(); }
    private static String baseName(String path) { if (path == null) return ""; int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')); return slash >= 0 ? path.substring(slash + 1) : path; }
    private static String safeReason(String value) { String text = value == null ? "unknown" : value.replace('\n', ' ').replace('\r', ' ').trim(); return text.length() <= 180 ? text : text.substring(0, 180); }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < 16; i++) out.append(String.format(Locale.ROOT, "%02x", bytes[i] & 255));
            return out.toString();
        } catch (Exception ignored) { return Integer.toHexString(value.hashCode()); }
    }
}
