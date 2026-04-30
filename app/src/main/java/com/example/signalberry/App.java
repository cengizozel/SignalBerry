package com.example.signalberry;

import android.app.Application;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        AppCompatDelegate.setDefaultNightMode(
                prefs.getBoolean("dark_mode", false)
                        ? AppCompatDelegate.MODE_NIGHT_YES
                        : AppCompatDelegate.MODE_NIGHT_NO);
    }
}
