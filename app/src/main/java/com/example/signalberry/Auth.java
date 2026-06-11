package com.example.signalberry;

import android.content.SharedPreferences;

import java.net.HttpURLConnection;

/**
 * Central holder for remote-access credentials. Values come from the setup
 * screen (stored in prefs, never in source — the repo is public). Attached to
 * every outbound request so the same build works on the LAN (no creds → no
 * headers → unchanged behaviour) and through Cloudflare (creds → headers).
 *
 *   - Cloudflare Access service token (CF-Access-Client-Id/Secret): edge gate,
 *     required by BOTH backends since signal-cli-rest-api has no auth of its own.
 *   - Bridge bearer token: the bridge's own backstop; harmless to the REST API,
 *     which ignores the Authorization header.
 */
final class Auth {

    private static volatile String bearer = "";
    private static volatile String cfId = "";
    private static volatile String cfSecret = "";

    private Auth() {}

    static void load(SharedPreferences p) {
        bearer   = p.getString("bridge_token", "");
        cfId     = p.getString("cf_access_id", "");
        cfSecret = p.getString("cf_access_secret", "");
    }

    static void apply(HttpURLConnection c) {
        if (notEmpty(bearer))   c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (notEmpty(cfId))     c.setRequestProperty("CF-Access-Client-Id", cfId);
        if (notEmpty(cfSecret)) c.setRequestProperty("CF-Access-Client-Secret", cfSecret);
    }

    static okhttp3.Request.Builder apply(okhttp3.Request.Builder b) {
        if (notEmpty(bearer))   b.header("Authorization", "Bearer " + bearer);
        if (notEmpty(cfId))     b.header("CF-Access-Client-Id", cfId);
        if (notEmpty(cfSecret)) b.header("CF-Access-Client-Secret", cfSecret);
        return b;
    }

    private static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }
}
