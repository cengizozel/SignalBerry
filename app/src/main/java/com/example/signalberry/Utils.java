package com.example.signalberry;

import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

final class Utils {
    private Utils() {}

    // ── Strings ───────────────────────────────────────────────────────────────

    static boolean isEmpty(String s)  { return s == null || s.trim().isEmpty(); }
    static boolean notEmpty(String s) { return !isEmpty(s); }
    static String  safeTrim(String s) { return s == null ? null : s.trim(); }

    static String digits(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
        }
        return out.toString();
    }

    static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    static String shortUuid(String uuid) {
        uuid = safeTrim(uuid);
        if (isEmpty(uuid)) return "";
        int cut = uuid.indexOf('-');
        if (cut > 0) return uuid.substring(0, cut);
        return (uuid.length() > 8) ? uuid.substring(0, 8) : uuid;
    }

    static String joinNames(String given, String family) {
        given  = safeTrim(given);
        family = safeTrim(family);
        if (!isEmpty(given) && !isEmpty(family)) return given + " " + family;
        if (!isEmpty(given))  return given;
        if (!isEmpty(family)) return family;
        return "";
    }

    static String peerKey(String number, String uuid) {
        String d = digits(number);
        return !isEmpty(d) ? d : (uuid == null ? "" : uuid);
    }

    static boolean safeEq(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    // ── Numbers / time ────────────────────────────────────────────────────────

    static long parseLongSafe(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    static int parseSafeInt(String s) {
        if (s == null) return 0;
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    static String formatShortTime(long tsMillis) {
        if (tsMillis <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar t   = Calendar.getInstance();
        t.setTimeInMillis(tsMillis);
        boolean sameDay = now.get(Calendar.YEAR)         == t.get(Calendar.YEAR)
                       && now.get(Calendar.DAY_OF_YEAR)  == t.get(Calendar.DAY_OF_YEAR);
        if (sameDay) return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(tsMillis));
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(tsMillis));
    }

    // ── Network / URL ─────────────────────────────────────────────────────────

    static String normalizeBase(String hostPort) {
        if (hostPort == null) hostPort = "";
        hostPort = hostPort.trim();
        if ("localhost:5000".equals(hostPort) || "127.0.0.1:5000".equals(hostPort))
            hostPort = "10.0.2.2:5000";
        if (!hostPort.startsWith("http://") && !hostPort.startsWith("https://"))
            hostPort = "http://" + hostPort;
        if (hostPort.endsWith("/")) hostPort = hostPort.substring(0, hostPort.length() - 1);
        return hostPort;
    }

    static String deriveBridgeBase(String ipOrBase) {
        String base = ipOrBase == null ? "" : ipOrBase.trim();
        String scheme = "http";
        if (base.startsWith("https://"))     { scheme = "https"; base = base.substring(8); }
        else if (base.startsWith("http://")) { scheme = "http";  base = base.substring(7); }
        if ("localhost:5000".equals(base) || "127.0.0.1:5000".equals(base)) base = "10.0.2.2:5000";
        int slash = base.indexOf('/');
        if (slash >= 0) base = base.substring(0, slash);
        String host = base;
        int colon = host.indexOf(':');
        if (colon > 0) host = host.substring(0, colon);
        boolean isIp = host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")
                || host.startsWith("[")
                || "localhost".equalsIgnoreCase(host)
                || "10.0.2.2".equals(host);
        return isIp ? "http://" + host + ":9099" : scheme + "://bridge-" + host;
    }

    static String toWs(String httpBase) {
        if (httpBase.startsWith("https://")) return "wss://" + httpBase.substring(8);
        if (httpBase.startsWith("http://"))  return "ws://"  + httpBase.substring(7);
        return "ws://" + httpBase;
    }

    static int httpCodeGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                code >= 400 ? c.getErrorStream() : c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            String out = sb.toString();
            return out.isEmpty() ? "[]" : out;
        } finally {
            c.disconnect();
        }
    }

    static int httpPostJson(String urlStr, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
            os.write(json.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    static int httpDeleteJson(String urlStr, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("DELETE");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) {
            os.write(json.getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    // ── Signal contact display name ───────────────────────────────────────────

    static String chooseDisplayName(JSONObject c) {
        JSONObject nick = c.optJSONObject("nickname");
        String nickName = firstNonEmpty(
                nick != null ? nick.optString("name", null) : null,
                joinNames(nick != null ? nick.optString("given_name", null) : null,
                          nick != null ? nick.optString("family_name", null) : null)
        );
        if (!isEmpty(nickName)) return nickName;

        String contactName = c.optString("name", "");
        if (!isEmpty(contactName)) return contactName;

        String profileName = c.optString("profile_name", "");
        if (!isEmpty(profileName)) return profileName;

        JSONObject profile = c.optJSONObject("profile");
        String profComposed = joinNames(
                profile != null ? profile.optString("given_name", null) : null,
                profile != null ? profile.optString("lastname",   null) : null
        );
        if (!isEmpty(profComposed)) return profComposed;

        String username = c.optString("username", "");
        if (!isEmpty(username)) return "@" + username;

        String number = c.optString("number", "");
        if (!isEmpty(number)) return number;

        String uuid = c.optString("uuid", "");
        if (!isEmpty(uuid)) return "Signal user " + shortUuid(uuid);

        return "Unknown";
    }
}
