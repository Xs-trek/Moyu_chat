package com.moyu.chat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Dependency-free lexical terms for the local FTS4 knowledge index. */
final class KnowledgeLexicon {
    private static final int MAX_TERMS = 64;
    private KnowledgeLexicon() { }

    static List<String> terms(String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) return new ArrayList<>();
        StringBuilder ascii = new StringBuilder();
        Integer previousNonAscii = null;
        for (int offset = 0; offset < text.length() && out.size() < MAX_TERMS; ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp < 128 && Character.isLetterOrDigit(cp)) {
                ascii.appendCodePoint(Character.toLowerCase(cp));
                previousNonAscii = null;
                continue;
            }
            flushAscii(ascii, out);
            if (Character.isLetterOrDigit(cp) && cp >= 128) {
                out.add("u" + Integer.toHexString(cp));
                if (previousNonAscii != null) out.add("b" + Integer.toHexString(previousNonAscii) + "x" + Integer.toHexString(cp));
                previousNonAscii = cp;
            } else previousNonAscii = null;
        }
        flushAscii(ascii, out);
        ArrayList<String> result = new ArrayList<>(out);
        if (result.size() > MAX_TERMS) return new ArrayList<>(result.subList(0, MAX_TERMS));
        return result;
    }

    static String indexTerms(String text) { return String.join(" ", terms(text)); }

    static String matchQuery(String text) {
        List<String> terms = terms(text);
        if (terms.isEmpty()) return "";
        return String.join(" OR ", terms);
    }

    private static void flushAscii(StringBuilder ascii, LinkedHashSet<String> out) {
        if (ascii.length() == 0 || out.size() >= MAX_TERMS) { ascii.setLength(0); return; }
        String word = ascii.toString().toLowerCase(Locale.ROOT);
        ascii.setLength(0);
        if (word.length() >= 2) out.add(word);
    }
}
