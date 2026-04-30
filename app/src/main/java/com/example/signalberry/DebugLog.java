package com.example.signalberry;

import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

class DebugLog {
    interface Listener { void onLine(String line); }

    private static final int MAX = 300;
    private static final List<String> lines = new ArrayList<>();
    private static final List<WeakReference<Listener>> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void log(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String line = ts + " " + msg;
        synchronized (lines) {
            lines.add(line);
            if (lines.size() > MAX) lines.remove(0);
        }
        main.post(() -> {
            Iterator<WeakReference<Listener>> it = listeners.iterator();
            while (it.hasNext()) {
                Listener l = it.next().get();
                if (l == null) it.remove();
                else l.onLine(line);
            }
        });
    }

    static void register(Listener l) {
        listeners.add(new WeakReference<>(l));
    }

    static void unregister(Listener l) {
        Iterator<WeakReference<Listener>> it = listeners.iterator();
        while (it.hasNext()) {
            if (it.next().get() == l) { it.remove(); break; }
        }
    }

    static String getAll() {
        synchronized (lines) {
            StringBuilder sb = new StringBuilder();
            for (String line : lines) sb.append(line).append("\n");
            return sb.toString();
        }
    }
}
