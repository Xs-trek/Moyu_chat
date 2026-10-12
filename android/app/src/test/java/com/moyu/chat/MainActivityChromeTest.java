package com.moyu.chat;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Window chrome helpers that do not need a running Activity. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class MainActivityChromeTest {
    @Test public void webTextFollowsTheSystemFontSizeWithinBounds() {
        assertEquals(100, MainActivity.textZoomFor(1f));
        assertEquals(115, MainActivity.textZoomFor(1.15f));
        assertEquals(130, MainActivity.textZoomFor(1.3f));
        assertEquals("very large system fonts are capped so controls keep their layout", 160, MainActivity.textZoomFor(2f));
        assertEquals(85, MainActivity.textZoomFor(0.8f));
        assertEquals("a broken configuration falls back to 100%", 100, MainActivity.textZoomFor(0f));
    }

    @Test public void windowCanvasMirrorsTheCssTokens() {
        assertEquals(0xFFE5E9E3, MainActivity.canvasColor(false, "sage"));
        assertEquals(0xFF15181A, MainActivity.canvasColor(true, "sage"));
    }
}
