package com.moyu.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Dependency-free lexical terms for the local FTS4 knowledge index. ASCII text gives lower-cased words;
 * any other letter or digit gives a unigram ("u" + code point) and, with the previous one, a bigram
 * ("b" + a + "x" + b). Chinese has no spaces, so bigrams carry most of the meaning: queries use them,
 * and fall back to unigrams only for a lone character. Long messages are indexed in chunks so every
 * part of an answer stays findable.
 */
final class KnowledgeLexicon {
    /** Terms kept per indexed chunk; a chunk is short enough that this never truncates it in practice. */
    static final int MAX_INDEX_TERMS = 1024;
    /** Query terms considered; long questions keep their first terms. */
    static final int MAX_QUERY_TERMS = 24;
    /** Characters per indexed chunk, and how far a chunk may reach back for a natural break. */
    static final int CHUNK_CHARS = 600;
    private static final int CHUNK_SLACK = 120;

    private KnowledgeLexicon() { }

    /** All terms of {@code text} (unigrams and bigrams), for the index. */
    static List<String> terms(String text) { return collect(text, MAX_INDEX_TERMS, false); }

    static String indexTerms(String text) { return String.join(" ", terms(text)); }

    /** Terms that identify {@code text} as a query: words and bigrams; a lone non-ASCII character keeps its unigram. */
    static List<String> queryTerms(String text) { return collect(text, MAX_QUERY_TERMS, true); }

    static boolean isUnigram(String term) { return term.startsWith("u") && term.length() > 1 && isHex(term.substring(1)); }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.digit(s.charAt(i), 16) < 0) return false;
        return !s.isEmpty();
    }

    private static List<String> collect(String text, int cap, boolean query) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        int previous = -1;
        int runLength = 0;
        int runStart = -1;
        for (int offset = 0; offset < text.length() && out.size() < cap; ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp < 128 && Character.isLetterOrDigit(cp)) {
                ascii.appendCodePoint(Character.toLowerCase(cp));
                if (query && runLength == 1) out.add("u" + Integer.toHexString(runStart));
                previous = -1; runLength = 0;
                continue;
            }
            flushAscii(ascii, out, cap);
            if (cp >= 128 && Character.isLetterOrDigit(cp)) {
                if (!query) out.add("u" + Integer.toHexString(cp));
                if (previous >= 0 && out.size() < cap) out.add("b" + Integer.toHexString(previous) + "x" + Integer.toHexString(cp));
                if (runLength == 0) runStart = cp;
                runLength++;
                previous = cp;
            } else {
                if (query && runLength == 1) out.add("u" + Integer.toHexString(runStart));
                previous = -1; runLength = 0;
            }
        }
        flushAscii(ascii, out, cap);
        if (query && runLength == 1 && out.size() < cap) out.add("u" + Integer.toHexString(runStart));
        ArrayList<String> result = new ArrayList<>(out);
        return result.size() > cap ? new ArrayList<>(result.subList(0, cap)) : result;
    }

    private static void flushAscii(StringBuilder ascii, LinkedHashSet<String> out, int cap) {
        if (ascii.length() == 0 || out.size() >= cap) { ascii.setLength(0); return; }
        String word = ascii.toString().toLowerCase(Locale.ROOT);
        ascii.setLength(0);
        if (word.length() >= 2) out.add(word);
    }

    /**
     * Splits a message into chunks of about CHUNK_CHARS, preferring to end at a paragraph, sentence or space
     * near the limit. Chunks do not overlap; a bigram split across a boundary is the only thing lost.
     */
    static List<String> chunks(String text) {
        ArrayList<String> out = new ArrayList<>();
        String value = text == null ? "" : text.trim();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + CHUNK_CHARS);
            if (end < value.length()) {
                int best = -1;
                for (int i = end; i > end - CHUNK_SLACK && i > start; i--) {
                    char c = value.charAt(i - 1);
                    if (c == '\n') { best = i; break; }
                    if (best < 0 && (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?' || c == '；' || c == ';' || c == ' ')) best = i;
                }
                if (best > start) end = best;
            }
            String chunk = value.substring(start, end).trim();
            if (!chunk.isEmpty()) out.add(chunk);
            start = end;
        }
        return out;
    }
}
