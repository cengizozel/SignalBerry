package com.example.signalberry;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        // bring up modern TLS (TLS 1.3) so HTTPS to Cloudflare works on the
        // 2013-era runtime; harmless on LAN (plain http never touches it)
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1);
        } catch (Throwable t) {
            DebugLog.log("Conscrypt unavailable, using platform TLS: " + t);
        }
        SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        Auth.load(prefs);
        AppCompatDelegate.setDefaultNightMode(
                prefs.getBoolean("dark_mode", false)
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO);
    }
}
