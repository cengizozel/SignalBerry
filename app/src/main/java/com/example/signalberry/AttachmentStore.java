package com.example.signalberry;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Disk cache for attachments: getFilesDir()/att/<sanitized-id>.
 * Byte-bounded LRU by file mtime — images 48MB, video/other 64MB, with the
 * most-recently-touched file always kept. Sent media is copied in BEFORE the
 * upload so the message never depends on a transient content:// permission
 * (the old "image turns blank after restart" bug).
 *
 * All methods are blocking — call off the main thread.
 */
final class AttachmentStore {

    private static final long IMAGE_BUDGET = 48L * 1024 * 1024;
    private static final long MEDIA_BUDGET = 64L * 1024 * 1024;

    private static AttachmentStore instance;

    static synchronized AttachmentStore get(Context ctx) {
        if (instance == null) instance = new AttachmentStore(ctx.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final File dir;

    private AttachmentStore(Context ctx) {
        this.ctx = ctx;
        this.dir = new File(ctx.getFilesDir(), "att");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    private static String sanitize(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            sb.append((Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        return sb.toString();
    }

    File fileFor(String attId) {
        return new File(dir, sanitize(attId));
    }

    boolean has(String attId) {
        File f = fileFor(attId);
        return f.exists() && f.length() > 0;
    }

    /** Cached file, downloading from signal-api on miss. Null on failure. */
    File fetch(String baseSignal, String attId) {
        File f = fileFor(attId);
        if (f.exists() && f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis()); // LRU touch
            return f;
        }
        File tmp = new File(dir, sanitize(attId) + ".part");
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(baseSignal + "/v1/attachments/" + Uri.encode(attId)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(60000);
            if (c.getResponseCode() != 200) { c.disconnect(); return null; }
            try (InputStream is = c.getInputStream();
                 OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            if (!tmp.renameTo(f)) return null;
            evictIfNeeded();
            return f;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return null;
        }
    }

    /** HEAD-equivalent size probe (signal-api supports GET only; use Content-Length). */
    long remoteSize(String baseSignal, String attId) {
        try {
            HttpURLConnection c = (HttpURLConnection)
                    new URL(baseSignal + "/v1/attachments/" + Uri.encode(attId)).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestMethod("GET");
            c.connect();
            long len = -1;
            if (c.getResponseCode() == 200) {
                String h = c.getHeaderField("Content-Length");
                if (h != null) try { len = Long.parseLong(h); } catch (NumberFormatException ignored) {}
            }
            c.disconnect();
            return len;
        } catch (Exception e) { return -1; }
    }

    /** Copy a picked content:// (or file://) into the store under a local key.
     *  Returns the stored file, or null. Key form: "local-<nonce>". */
    File importLocal(Uri src, String key) {
        File f = fileFor(key);
        try (InputStream is = ctx.getContentResolver().openInputStream(src);
             OutputStream os = new FileOutputStream(f)) {
            if (is == null) return null;
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
            evictIfNeeded();
            return f;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
    }

    /** Rename a local-key entry to its real attachment id once learned. */
    void promote(String localKey, String attId) {
        File from = fileFor(localKey);
        if (from.exists()) {
            //noinspection ResultOfMethodCallIgnored
            from.renameTo(fileFor(attId));
        }
    }

    private void evictIfNeeded() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        long budget = IMAGE_BUDGET + MEDIA_BUDGET;
        if (total <= budget) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (int i = 0; i < files.length - 1 && total > budget; i++) {
            long len = files[i].length();
            if (files[i].delete()) total -= len;
        }
    }

    // ── streaming upload (REDESIGN §3.5) ─────────────────────────────────────

    /** Client-side caps: a Q10 radio + signal-cli decode time make bigger
     *  uploads time out long before Signal's own limits matter. */
    static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    static final long MAX_MEDIA_BYTES = 50L * 1024 * 1024;

    /**
     * POST /v2/send with the attachment streamed as base64 — constant memory
     * (~32KB) instead of the old 6-8x-filesize heap spike. Returns the raw
     * response timestamp string, or null.
     */
    static String sendStreaming(String baseSignal, String myNumber, String recipient,
                                String caption, String mime, File file,
                                Long quoteTs, String quoteAuthor, String quoteText)
            throws IOException {
        String prefixJson;
        try {
            org.json.JSONObject body = new org.json.JSONObject();
            body.put("message", caption == null ? "" : caption);
            body.put("number", myNumber);
            body.put("recipients", new org.json.JSONArray().put(recipient));
            if (quoteTs != null && quoteTs > 0) {
                body.put("quote_timestamp", (long) quoteTs);
                body.put("quote_author", quoteAuthor == null ? "" : quoteAuthor);
                body.put("quote_message", quoteText == null ? "" : quoteText);
            }
            String s = body.toString();
            // open the base64_attachments array and the data-URI string, leaving
            // the closing quote/bracket/brace for the suffix
            prefixJson = s.substring(0, s.length() - 1)
                    + ",\"base64_attachments\":[\"data:" + mime + ";base64,";
        } catch (Exception e) {
            throw new IOException("body build failed", e);
        }

        HttpURLConnection c = (HttpURLConnection)
                new URL(baseSignal + "/v2/send").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(120_000); // signal-cli decodes + dispatches before replying
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        c.setChunkedStreamingMode(0);
        try {
            OutputStream raw = c.getOutputStream();
            raw.write(prefixJson.getBytes("UTF-8"));
            // NO_WRAP is load-bearing: the default flag inserts raw newlines,
            // which are illegal inside a JSON string literal
            android.util.Base64OutputStream b64 = new android.util.Base64OutputStream(
                    new NonClosingStream(raw), android.util.Base64.NO_WRAP);
            try (InputStream is = new java.io.FileInputStream(file)) {
                byte[] buf = new byte[24 * 1024 * 3]; // multiple of 3: no mid-stream padding
                int n;
                while ((n = is.read(buf)) != -1) b64.write(buf, 0, n);
            }
            b64.close(); // flushes final base64 block into raw (kept open)
            raw.write("\"]}".getBytes("UTF-8"));
            raw.close();

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) return null;
            try (InputStream is = c.getInputStream();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                String ts = new org.json.JSONObject(bos.toString("UTF-8"))
                        .optString("timestamp", "");
                return ts.isEmpty() ? null : ts;
            } catch (org.json.JSONException e) {
                return null;
            }
        } finally {
            c.disconnect();
        }
    }

    /** Base64OutputStream.close() writes padding then closes the wrapped stream;
     *  we still need to append the JSON suffix afterwards. */
    private static final class NonClosingStream extends java.io.FilterOutputStream {
        NonClosingStream(OutputStream out) { super(out); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }
        @Override public void close() throws IOException { out.flush(); }
    }
}
