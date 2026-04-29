package com.example.signalberry;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

class AvatarCache {

    interface Logger { void log(String msg); }

    private static final String TAG = "AvatarCache";
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    private static final Map<String, Bitmap> mem = new HashMap<>();

    private final File dir;
    private final String baseSignal;
    private final String myNumber;
    private Logger logger;

    AvatarCache(File cacheDir, String baseSignal, String myNumber) {
        this.dir = new File(cacheDir, "avatars");
        this.dir.mkdirs();
        this.baseSignal = baseSignal;
        this.myNumber = myNumber;
    }

    void setLogger(Logger l) { this.logger = l; }

    private void log(String msg) {
        Log.d(TAG, msg);
        if (logger != null) logger.log(msg);
    }

    /**
     * Fetch avatar for a contact by UUID.
     * avatarUuid must be non-empty; returns null if contact has no avatar.
     * May block on network — call from a background thread.
     */
    Bitmap fetch(String number, String avatarUuid) {
        String key = Utils.digits(number);
        if (key.isEmpty()) key = number;

        if (avatarUuid == null || avatarUuid.isEmpty()) {
            log("no avatar for " + key);
            return null;
        }

        synchronized (mem) {
            if (mem.containsKey(key)) return mem.get(key);
        }

        File f = new File(dir, key);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS) {
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm != null) {
                log("disk hit: " + key);
                synchronized (mem) { mem.put(key, bm); }
                return bm;
            }
        }

        try {
            String url = baseSignal
                    + "/v1/contacts/" + URLEncoder.encode(myNumber, "UTF-8")
                    + "/" + avatarUuid + "/avatar";
            log("GET " + url);
            byte[] bytes = getBytes(url);
            log("bytes: " + (bytes == null ? "null" : bytes.length));
            if (bytes == null || bytes.length == 0) return null;

            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            log("bitmap: " + bm);
            if (bm != null) synchronized (mem) { mem.put(key, bm); }
            return bm;
        } catch (Exception e) {
            log("ERROR: " + e);
            return null;
        }
    }

    void invalidate(String number) {
        String key = Utils.digits(number);
        if (key.isEmpty()) key = number;
        synchronized (mem) { mem.remove(key); }
        new File(dir, key).delete();
    }

    private static byte[] getBytes(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        if (code >= 400) { c.disconnect(); return null; }
        try (InputStream is = c.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            c.disconnect();
        }
    }
}
