package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;

public class SessionTitlesTest {
    @Test public void thePlaceholderIsTheFirstLineCutToTwentyCharacters() {
        assertEquals("如何配置路由器", SessionTitles.placeholder("\n## 如何配置路由器\n第二行"));
        assertEquals("一二三四五六七八九十一二三四五六七八九十…", SessionTitles.placeholder("一二三四五六七八九十一二三四五六七八九十多出来的"));
        assertEquals("fix the bug", SessionTitles.placeholder("- **fix** the   `bug`"));
        assertNull("an image-only message keeps the default title", SessionTitles.placeholder("  \n "));
    }

    @Test public void onlyTheDefaultAndThePlaceholderAreAutomatic() {
        assertTrue(SessionTitles.isAutomatic(SessionTitles.DEFAULT_TITLE, "hello"));
        assertTrue(SessionTitles.isAutomatic("hello", "hello"));
        assertFalse("a rename is never replaced", SessionTitles.isAutomatic("My chat", "hello"));
    }

    @Test public void modelOutputIsCleanedIntoOneShortLine() {
        assertEquals("天空为何是蓝色", SessionTitles.clean("标题：“天空为何是蓝色”。\n解释……"));
        assertEquals("Rust lifetimes explained", SessionTitles.clean("**\"Rust lifetimes explained.\"**"));
        assertNull(SessionTitles.clean("  \"\"  "));
        assertEquals(SessionTitles.MAX_TITLE_CHARS, SessionTitles.clean(new String(new char[50]).replace('\0', '长')).length());
    }
}
