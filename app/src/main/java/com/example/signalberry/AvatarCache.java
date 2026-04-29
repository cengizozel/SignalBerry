package com.example.signalberry;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

class AvatarCache {

    private static final String TAG = "AvatarCache";
    private static final long TTL_MS = 24L * 60 * 60 * 1000;
    private static final Map<String, Bitmap> mem = new HashMap<>();

    private final File dir;
    private final String baseSignal;

    AvatarCache(File cacheDir, String baseSignal) {
        this.dir = new File(cacheDir, "avatars");
        this.dir.mkdirs();
        this.baseSignal = baseSignal;
    }

    /** May block on network — call from a background thread. Returns null if no avatar. */
    Bitmap fetch(String number) {
        String key = Utils.digits(number);
        if (key.isEmpty()) key = number;

        synchronized (mem) {
            if (mem.containsKey(key)) return mem.get(key);
        }

        File f = new File(dir, key);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < TTL_MS) {
            Bitmap bm = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bm != null) {
                synchronized (mem) { mem.put(key, bm); }
                return bm;
            }
        }

        try {
            String profileUrl = baseSignal + "/v1/profiles/" + number;
            Log.d(TAG, "fetch profile: " + profileUrl);
            String json = Utils.httpGet(profileUrl);
            Log.d(TAG, "profile response: " + json);

            if (json.startsWith("[")) {
                Log.d(TAG, "profile returned array, skipping");
                return null;
            }

            JSONObject obj = new JSONObject(json);
            String avatar = obj.optString("avatar", "");
            Log.d(TAG, "avatar field: '" + avatar + "'");
            if (avatar.isEmpty()) return null;

            String attUrl = baseSignal + "/v1/attachments/" + avatar;
            Log.d(TAG, "fetch attachment: " + attUrl);
            byte[] bytes = getBytes(attUrl);
            Log.d(TAG, "attachment bytes: " + (bytes == null ? "null" : bytes.length));
            if (bytes == null || bytes.length == 0) return null;

            try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(bytes); }
            Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            Log.d(TAG, "decoded bitmap: " + bm);
            if (bm != null) synchronized (mem) { mem.put(key, bm); }
            return bm;
        } catch (Exception e) {
            Log.e(TAG, "fetch failed for " + number, e);
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
        Log.d(TAG, "getBytes " + urlStr + " -> " + code);
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
