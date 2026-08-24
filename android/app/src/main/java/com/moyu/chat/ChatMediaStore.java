package com.moyu.chat;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Private durable image store. Timeline rows keep only bounded metadata/media IDs, never base64 blobs. */
final class ChatMediaStore {
    static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    static final int MAX_DRAFT_IMAGES = 4;
    private final File root;

    ChatMediaStore(android.content.Context context) {
        root = new File(context.getFilesDir(), "chat-media");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("cannot create chat media directory");
    }

    JSONObject importImage(ContentResolver resolver, Uri uri) throws Exception {
        if (resolver == null || uri == null) throw new IllegalArgumentException("image required");
        String mime = normalizeMime(resolver.getType(uri));
        String name = displayName(resolver, uri);
        byte[] bytes;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("cannot open selected image");
            bytes = readBounded(in);
        }
        return persist(bytes, mime, name);
    }

    JSONObject saveBase64Image(String mimeType, String base64, String fileName) throws Exception {
        String mime = normalizeMime(mimeType);
        if (base64 == null || base64.trim().isEmpty()) throw new IllegalArgumentException("empty image payload");
        byte[] bytes;
        try { bytes = Base64.decode(base64, Base64.DEFAULT); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid image payload"); }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("image exceeds local size limit");
        return persist(bytes, mime, fileName);
    }

    InputStream open(String mediaId) throws Exception { return new FileInputStream(fileFor(mediaId)); }

    String mimeType(String mediaId) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream in = open(mediaId)) { BitmapFactory.decodeStream(in, null, options); }
        return normalizeMime(options.outMimeType);
    }

    String dataUrl(JSONObject descriptor) throws Exception {
        if (descriptor == null) throw new IllegalArgumentException("image descriptor missing");
        String id = descriptor.optString("mediaId", "");
        String mime = normalizeMime(descriptor.optString("mimeType", ""));
        byte[] bytes;
        try (InputStream in = open(id)) { bytes = readBounded(in); }
        return "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    boolean exists(String mediaId) {
        try { return fileFor(mediaId).isFile(); } catch (Exception ignored) { return false; }
    }

    private JSONObject persist(byte[] bytes, String mime, String requestedName) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("image exceeds local size limit");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IllegalArgumentException("unsupported or invalid image");
        String id = UUID.randomUUID().toString();
        File target = new File(root, id);
        try (FileOutputStream out = new FileOutputStream(target)) { out.write(bytes); }
        String cleanName = sanitizeName(requestedName, mime);
        return new JSONObject().put("mediaId", id).put("kind", "image").put("mimeType", mime)
                .put("fileName", cleanName).put("sizeBytes", bytes.length).put("width", options.outWidth).put("height", options.outHeight)
                .put("sha256", sha256(bytes)).put("createdAt", Instant.now().toString()).put("url", "/media/" + id);
    }

    private File fileFor(String mediaId) {
        String id = mediaId == null ? "" : mediaId.trim();
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("invalid media id");
        File file = new File(root, id);
        try {
            if (!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())) throw new IllegalArgumentException("invalid media path");
        } catch (java.io.IOException error) { throw new IllegalArgumentException("invalid media path"); }
        return file;
    }

    private static byte[] readBounded(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        for (int n; (n = in.read(buffer)) >= 0;) {
            if (n == 0) continue;
            total += n; if (total > MAX_IMAGE_BYTES) throw new IllegalArgumentException("image exceeds 12 MiB local limit");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) { String value = c.getString(0); if (value != null && !value.trim().isEmpty()) return value; }
        } catch (Exception ignored) { }
        return "image";
    }

    private static String normalizeMime(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(value)) value = "image/jpeg";
        if (!("image/jpeg".equals(value) || "image/png".equals(value) || "image/webp".equals(value) || "image/gif".equals(value))) {
            throw new IllegalArgumentException("supported image types: JPEG / PNG / WebP / GIF");
        }
        return value;
    }

    private static String sanitizeName(String raw, String mime) {
        String value = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (value.isEmpty()) value = "image" + extension(mime);
        if (value.length() > 180) value = value.substring(0, 180);
        return value;
    }

    private static String extension(String mime) {
        if ("image/png".equals(mime)) return ".png";
        if ("image/webp".equals(mime)) return ".webp";
        if ("image/gif".equals(mime)) return ".gif";
        return ".jpg";
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64); for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255)); return out.toString();
    }
}
