package com.moyu.chat;

import static org.junit.Assert.*;
import android.util.Base64;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ChatMediaStoreTest {
    @Test public void smallImageRemainsDurableAndHasDimensions() throws Exception {
        ChatMediaStore store = new ChatMediaStore(RuntimeEnvironment.getApplication());
        JSONObject image = store.saveBase64Image("image/gif",
                "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", "tiny.gif");
        assertEquals(1, image.getInt("width"));
        assertEquals(1, image.getInt("height"));
        assertTrue(store.exists(image.getString("mediaId")));
    }

    @Test public void importSizingLimitsTheLongEdgeAndNeverUpscales() {
        assertArrayEquals(new int[]{2048, 1536}, ChatMediaStore.scaledSize(4000, 3000, 2048));
        assertArrayEquals(new int[]{1152, 2048}, ChatMediaStore.scaledSize(2250, 4000, 2048));
        assertArrayEquals(new int[]{800, 600}, ChatMediaStore.scaledSize(800, 600, 2048));
        assertArrayEquals(new int[]{2048, 1}, ChatMediaStore.scaledSize(8192, 1, 2048));
        // Subsampling decodes no smaller than the target long edge.
        assertEquals(1, ChatMediaStore.sampleSize(4000, 3000, 2048));
        assertEquals(2, ChatMediaStore.sampleSize(4096, 3072, 2048));
        assertEquals(4, ChatMediaStore.sampleSize(8192, 6000, 2048));
        assertEquals(1, ChatMediaStore.sampleSize(1000, 1000, 2048));
    }

    @Test public void excessivePixelAreaAndLongThinImageAreRejected() {
        ChatMediaStore.validateDimensions(4096, 4096);
        ChatMediaStore.validateDimensions(8192, 1);
        for (int[] size : new int[][]{{5000, 4000}, {9000, 1}, {Integer.MAX_VALUE, Integer.MAX_VALUE}, {0, 10}}) {
            try {
                ChatMediaStore.validateDimensions(size[0], size[1]);
                fail("unsafe dimensions accepted");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void compressedOversizeImageIsRejectedBeforeCreatingMediaFile() throws Exception {
        ChatMediaStore store = new ChatMediaStore(RuntimeEnvironment.getApplication());
        // GIF logical-screen bounds: small encoded file, excessive decoded pixel area.
        byte[] gif = Base64.decode("R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw==", Base64.DEFAULT);
        gif[6] = (byte) 0x88; gif[7] = 0x13; // 5000
        gif[8] = (byte) 0xa0; gif[9] = 0x0f; // 4000
        gif[24] = (byte) 0x88; gif[25] = 0x13; // frame width
        gif[26] = (byte) 0xa0; gif[27] = 0x0f; // frame height
        File root = new File(RuntimeEnvironment.getApplication().getFilesDir(), "chat-media");
        int before = root.list().length;
        try {
            store.saveBase64Image("image/gif", Base64.encodeToString(gif, Base64.NO_WRAP), "oversize.gif");
            fail("encoded image with excessive bounds accepted");
        } catch (IllegalArgumentException expected) { }
        assertEquals(before, root.list().length);
    }
}
