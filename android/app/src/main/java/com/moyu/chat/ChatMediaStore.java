package com.moyu.chat;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
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
    static final long MAX_IMAGE_PIXELS = 16L * 1024 * 1024;
    static final int MAX_IMAGE_DIMENSION = 8192;
    static final int MAX_DRAFT_IMAGES = 4;
    private final File root;

    ChatMediaStore(android.content.Context context) {
        root = new File(context.getFilesDir(), "chat-media");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("无法创建图片目录");
    }

    JSONObject importImage(ContentResolver resolver, Uri uri) throws Exception {
        if (resolver == null || uri == null) throw new IllegalArgumentException("没有选择图片");
        String mime = normalizeMime(resolver.getType(uri));
        String name = displayName(resolver, uri);
        byte[] bytes;
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException("无法打开所选图片");
            bytes = readBounded(in);
        }
        Encoded encoded = prepareImport(bytes, mime);
        return persist(encoded.bytes, encoded.mime, renamed(name, mime, encoded.mime));
    }

    /** Long edge of an imported image. Larger photos cost upload and tokens on every later turn that resends them. */
    static final int IMPORT_LONG_EDGE = 2048;
    static final int IMPORT_JPEG_QUALITY = 85;

    private static final class Encoded {
        final byte[] bytes; final String mime;
        Encoded(byte[] bytes, String mime) { this.bytes = bytes; this.mime = mime; }
    }

    /**
     * Imported images are re-encoded once: EXIF orientation is applied and all metadata (location, device) is
     * dropped, the long edge is limited to {@link #IMPORT_LONG_EDGE}, photos become JPEG, and PNG stays PNG so
     * screenshot text stays sharp. GIF is kept as is (re-encoding would drop its animation; it has no EXIF).
     */
    private static Encoded prepareImport(byte[] bytes, String mime) throws Exception {
        if ("image/gif".equals(mime)) return new Encoded(bytes, mime);
        BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, bounds);
        validateDimensions(bounds.outWidth, bounds.outHeight);
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, IMPORT_LONG_EDGE);
        Bitmap source = BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, decode);
        if (source == null) throw new IllegalArgumentException("无法读取这张图片");
        Bitmap oriented = null;
        try {
            int[] size = scaledSize(source.getWidth(), source.getHeight(), IMPORT_LONG_EDGE);
            Matrix matrix = orientation("image/jpeg".equals(mime) || "image/webp".equals(mime) ? bytes : null);
            matrix.postScale(size[0] / (float) source.getWidth(), size[1] / (float) source.getHeight());
            oriented = matrix.isIdentity() ? source
                    : Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            boolean png = "image/png".equals(mime) || ("image/webp".equals(mime) && oriented.hasAlpha());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!oriented.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, IMPORT_JPEG_QUALITY, out)) {
                throw new IllegalArgumentException("无法处理这张图片");
            }
            return new Encoded(out.toByteArray(), png ? "image/png" : "image/jpeg");
        } finally {
            if (oriented != null && oriented != source) oriented.recycle();
            source.recycle();
        }
    }

    /** Largest power-of-two subsampling that still decodes at least {@code longEdge} on the long side. */
    static int sampleSize(int width, int height, int longEdge) {
        int sample = 1;
        while (Math.max(width, height) / (sample * 2) >= longEdge) sample *= 2;
        return sample;
    }

    /** Size with the long edge limited to {@code longEdge}, aspect kept, never upscaled. */
    static int[] scaledSize(int width, int height, int longEdge) {
        int longest = Math.max(width, height);
        if (longest <= longEdge) return new int[]{width, height};
        double scale = longEdge / (double) longest;
        return new int[]{Math.max(1, (int) Math.round(width * scale)), Math.max(1, (int) Math.round(height * scale))};
    }

    /**
     * The rotation/mirroring an EXIF orientation tag asks for (identity when there is none). The framework
     * ExifInterface is enough here: minSdk 26 is past the old-platform bugs the androidx copy exists for.
     */
    @android.annotation.SuppressLint("ExifInterface")
    private static Matrix orientation(byte[] jpegOrWebp) {
        Matrix matrix = new Matrix();
        if (jpegOrWebp == null) return matrix;
        int value;
        try { value = new android.media.ExifInterface(new ByteArrayInputStream(jpegOrWebp)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL); }
        catch (Exception unreadable) { return matrix; }
        switch (value) {
            case android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.setScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_180: matrix.setRotate(180); break;
            case android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.setScale(1, -1); break;
            case android.media.ExifInterface.ORIENTATION_TRANSPOSE: matrix.setRotate(90); matrix.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_90: matrix.setRotate(90); break;
            case android.media.ExifInterface.ORIENTATION_TRANSVERSE: matrix.setRotate(-90); matrix.postScale(-1, 1); break;
            case android.media.ExifInterface.ORIENTATION_ROTATE_270: matrix.setRotate(-90); break;
            default: break;
        }
        return matrix;
    }

    /** Keeps the picked file's name, with the extension of the format it was re-encoded to. */
    private static String renamed(String name, String fromMime, String toMime) {
        if (name == null || fromMime.equals(toMime)) return name;
        int dot = name.lastIndexOf('.');
        return (dot > 0 ? name.substring(0, dot) : name) + extension(toMime);
    }

    JSONObject saveBase64Image(String mimeType, String base64, String fileName) throws Exception {
        String mime = normalizeMime(mimeType);
        if (base64 == null || base64.trim().isEmpty()) throw new IllegalArgumentException("empty image payload");
        if (base64.length() > (MAX_IMAGE_BYTES * 4L / 3L) + 1024L) throw new IllegalArgumentException("图片超过本地大小上限");
        byte[] bytes;
        try { bytes = Base64.decode(base64, Base64.DEFAULT); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("invalid image payload"); }
        if (bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片超过本地大小上限");
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

    /** Best-effort GC for private media files that are no longer referenced by durable Chat state. */
    void pruneUnreferenced(java.util.Set<String> referencedIds) {
        java.util.Set<String> keep = referencedIds == null ? java.util.Collections.emptySet() : referencedIds;
        File[] files = root.listFiles(); if (files == null) return;
        for (File file : files) {
            if (file != null && file.isFile() && !keep.contains(file.getName())) {
                try { file.delete(); } catch (Exception ignored) { }
            }
        }
    }

    private JSONObject persist(byte[] bytes, String mime, String requestedName) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片超过本地大小上限");
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeStream(new ByteArrayInputStream(bytes), null, options);
        validateDimensions(options.outWidth, options.outHeight);
        String id = UUID.randomUUID().toString();
        File target = new File(root, id);
        try (FileOutputStream out = new FileOutputStream(target)) { out.write(bytes); }
        String cleanName = sanitizeName(requestedName, mime);
        return new JSONObject().put("mediaId", id).put("kind", "image").put("mimeType", mime)
                .put("fileName", cleanName).put("sizeBytes", bytes.length).put("width", options.outWidth).put("height", options.outHeight)
                .put("sha256", sha256(bytes)).put("createdAt", Instant.now().toString()).put("url", "/media/" + id);
    }

    static void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("图片无法解析或格式不受支持");
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("图片尺寸过大，请缩小至 16777216 像素以内，且单边不超过 8192 像素");
        }
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
            total += n; if (total > MAX_IMAGE_BYTES) throw new IllegalArgumentException("图片超过 12 MiB 本地上限");
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
            throw new IllegalArgumentException("只支持 JPEG / PNG / WebP / GIF 图片");
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
