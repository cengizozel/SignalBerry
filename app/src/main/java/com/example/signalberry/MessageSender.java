package com.example.signalberry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Single source of truth for outbound /v2/send requests. Centralises the
 * recipient/number/quote/mention body shape so every send path (text, edit,
 * media caption) speaks the exact same protocol — the group-recipient bugs in
 * mid-2026 came from four hand-rolled copies of this drifting apart.
 */
final class MessageSender {

    private final String base;
    private final String myNumber;
    private final String recipient;   // number, uuid, or "group.<token>"

    /** Body of the most recent non-2xx response (untrusted-identity sniffing). */
    private static volatile String lastError = "";

    MessageSender(String base, String myNumber, String recipient) {
        this.base = base;
        this.myNumber = myNumber;
        this.recipient = recipient;
    }

    static String lastError() { return lastError; }

    String recipient() { return recipient; }

    /** Bare body: message text + sender + single recipient. */
    JSONObject body(String text) throws Exception {
        JSONObject b = new JSONObject();
        b.put("message", text == null ? "" : text);
        b.put("number", myNumber);
        b.put("recipients", new JSONArray().put(recipient));
        return b;
    }

    /** Attach a reply quote. In groups the original author (not the group
     *  token) must be quoted, so callers pass the resolved author key. */
    void addQuote(JSONObject body, MessageItem replyTo, long replyTs, String peerAuthor)
            throws Exception {
        if (replyTo == null || replyTs <= 0) return;
        body.put("quote_timestamp", replyTs);
        String qAuthor = "me".equals(replyTo.from) ? myNumber
                : (peerAuthor != null && !peerAuthor.isEmpty() ? peerAuthor : recipient);
        body.put("quote_author", qAuthor == null ? "" : qAuthor);
        String qText = replyTo.type == MessageItem.TYPE_IMAGE ? "📷 Photo"
                : (replyTo.text != null ? replyTo.text : "");
        body.put("quote_message", qText);
    }

    /** Attach mention spans: each entry {start,length,author}. */
    static void addMentions(JSONObject body, JSONArray mentions) throws Exception {
        if (mentions != null && mentions.length() > 0) body.put("mentions", mentions);
    }

    /** Mark this body as an edit of an earlier message. */
    static void asEdit(JSONObject body, long editTs) throws Exception {
        body.put("edit_timestamp", editTs);
    }

    /** POST the body to /v2/send. Returns the raw "timestamp" field (string or
     *  number, never parsed here per M1) or null on failure. */
    String send(JSONObject body, int timeoutMs) {
        return post(base + "/v2/send", body.toString(), timeoutMs);
    }

    /** Shared JSON POST that extracts the "timestamp" field. */
    static String post(String url, String json, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            try (OutputStream os = new java.io.BufferedOutputStream(c.getOutputStream())) {
                os.write(json.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                lastError = readStream(c.getErrorStream());
                return null;
            }
            String resp = readStream(c.getInputStream());
            JSONObject o = new JSONObject(resp);
            Object ts = o.opt("timestamp");
            return ts == null ? null : String.valueOf(ts);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try (InputStream in = is; ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
