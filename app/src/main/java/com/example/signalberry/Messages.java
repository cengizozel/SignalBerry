package com.example.signalberry;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.*;

import static com.example.signalberry.Utils.*;

/**
 * Conversation list. Pure Repo observer (REDESIGN §3.1): no WebSocket, no
 * per-contact bridge hydration — contacts come from /v1/contacts, messages
 * arrive via MessageService→Repo, and this screen just renders DB summaries.
 */
public class Messages extends AppCompatActivity {

    /** Master rows (every conversation). */
    private final List<Map<String, String>> all = new ArrayList<>();
    /** Rows currently shown (post-filter). The adapter is bound to THIS list
     *  permanently — the old code forked a new adapter per keystroke. */
    private final List<Map<String, String>> visible = new ArrayList<>();
    private MessagesAdapter adapter;
    private SharedPreferences prefs;
    private Repo repo;

    private EditText search;
    private boolean isLoading = false;

    private Handler handler;

    private String restBase;
    private String myNumber;

    private final Map<String,String> nameByPeerKey = new HashMap<>();

    private AvatarCache avatarCache;

    private android.widget.TextView debugLogView;
    private android.widget.ScrollView debugScrollView;
    private android.widget.LinearLayout debugPanel;
    private final DebugLog.Listener debugListener = line -> {
        if (debugLogView == null) return;
        debugLogView.append(line + "\n");
        debugScrollView.post(() -> debugScrollView.fullScroll(android.view.View.FOCUS_DOWN));
    };

    // Coalesced list rebuild on Repo events
    private boolean rebuildQueued = false;
    private final Runnable rebuildRun = new Runnable() {
        @Override public void run() {
            rebuildQueued = false;
            rebuildListFromDb();
        }
    };
    private final Repo.Listener repoListener = new Repo.Listener() {
        @Override public void onItemInserted(String peerKey) { scheduleRebuild(); }
        @Override public void onItemChanged(String peerKey, long serverTs) { scheduleRebuild(); }
        @Override public void onEphemeral(String peerKey, String kind) {}
    };

    private void scheduleRebuild() {
        if (rebuildQueued) return;
        rebuildQueued = true;
        handler.postDelayed(rebuildRun, 200);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.messages);
        setTitle("Messages");

        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        repo  = Repo.get(this);

        ListView list = findViewById(R.id.list_people);
        search = findViewById(R.id.search);
        ImageButton plus = findViewById(R.id.toolbar_add);

        list.setOnItemClickListener((parent, view, position, id) -> {
            @SuppressWarnings("unchecked")
            Map<String, String> item = (Map<String, String>) parent.getItemAtPosition(position);
            String peerName   = item.get("name");
            String peerNumber = item.get("number");
            String peerUuid   = item.get("uuid");

            if ((peerNumber == null || peerNumber.trim().isEmpty()) &&
                    (peerUuid   == null || peerUuid.trim().isEmpty())) {
                Toast.makeText(this, "No identifier for this contact", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean demoOn = prefs.getBoolean("demo_mode", false);
            android.content.Intent intent = new android.content.Intent(Messages.this, Chat.class)
                    .putExtra("peer_name",   demoOn ? DemoData.NAMES[position % DemoData.NAMES.length] : peerName)
                    .putExtra("peer_number", peerNumber)
                    .putExtra("peer_uuid",   peerUuid);
            if (demoOn) intent.putExtra("demo_index", position);
            startActivity(intent);
        });

        plus.setOnClickListener(v ->
                startActivity(new android.content.Intent(Messages.this, NewChat.class)));

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            @SuppressWarnings("unchecked")
            Map<String, String> item = (Map<String, String>) parent.getItemAtPosition(position);
            String key = PeerKeys.get(this).resolve(item.get("number"), item.get("uuid"));
            if (isEmpty(key)) return true;
            boolean muted = prefs.getBoolean("mute_" + key, false);
            boolean hasAlias = notEmpty(prefs.getString("alias_" + key, ""));
            new AlertDialog.Builder(this)
                    .setTitle(item.get("name"))
                    .setItems(new String[]{
                            "Set name…",
                            muted ? "Unmute" : "Mute notifications",
                            "Delete conversation (this device)",
                            "Purge conversation (device + bridge)…"
                    }, (d, w) -> {
                        if (w == 0) {
                            promptSetAlias(key, item.get("name"));
                        } else if (w == 1) {
                            prefs.edit().putBoolean("mute_" + key, !muted).apply();
                        } else if (w == 2) {
                            new AlertDialog.Builder(this)
                                    .setMessage("Delete all messages in this conversation from this device? "
                                            + "(The bridge and your phone keep their copies.)")
                                    .setPositiveButton("Delete", (dd, ww) -> new Thread(() -> {
                                        repo.deleteThread(key);
                                        runOnUiThread(this::rebuildListFromDb);
                                    }).start())
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        } else if (w == 3) {
                            new AlertDialog.Builder(this)
                                    .setMessage("Purge this conversation from this device AND the "
                                            + "bridge server? Your phone's copy is not affected.\n\n"
                                            + "This cannot be undone.")
                                    .setPositiveButton("Purge", (dd, ww) -> new Thread(() -> {
                                        String err = repo.purgeThread(key);
                                        runOnUiThread(() -> {
                                            Toast.makeText(Messages.this,
                                                    err == null ? "Conversation purged" : err,
                                                    Toast.LENGTH_LONG).show();
                                            rebuildListFromDb();
                                        });
                                    }).start())
                                    .setNegativeButton("Cancel", null)
                                    .show();
                        }
                    })
                    .show();
            return true;
        });

        String host = prefs.getString("ip", "");
        myNumber    = prefs.getString("number", "");
        restBase    = normalizeBase(host);

        if (isEmpty(host) || isEmpty(myNumber)) {
            Toast.makeText(this, "Missing server IP or number", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        avatarCache = new AvatarCache(getCacheDir(), restBase, myNumber);
        adapter = new MessagesAdapter(this, visible, avatarCache, prefs.getBoolean("demo_mode", false));
        list.setAdapter(adapter);

        rebuildListFromDb();   // instant paint from cache
        loadSelfAvatar();

        debugLogView    = findViewById(R.id.debug_log);
        debugScrollView = findViewById(R.id.debug_scroll);
        debugPanel      = findViewById(R.id.debug_panel);
        DebugLog.register(debugListener);
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", DebugLog.getAll()));
                android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            DebugLog.clear();
            debugLogView.setText("");
        });

        ImageView toolbarAvatar = findViewById(R.id.toolbar_avatar);
        toolbarAvatar.setOnClickListener(v -> showSettings());

        // Pull-to-refresh
        androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipe = findViewById(R.id.swipe_refresh);
        swipe.setOnRefreshListener(() -> {
            loadConversations();
            swipe.setRefreshing(false);
        });

        // Initial load
        loadConversations();

        // Search filter
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void updateDebugPanel() {
        boolean on = prefs.getBoolean("debug_log", false);
        debugPanel.setVisibility(on ? android.view.View.VISIBLE : android.view.View.GONE);
        if (on) {
            debugLogView.setText(DebugLog.getAll());
            debugScrollView.post(() -> debugScrollView.fullScroll(android.view.View.FOCUS_DOWN));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        updateDebugPanel();
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 200);
        }

        android.content.Intent svc = new android.content.Intent(this, MessageService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        repo.addListener(repoListener);
        rebuildListFromDb();   // returning from a chat: unread/snippets changed

        // a dark-mode toggle inside Settings recreates this activity (that's how
        // AppCompat applies the theme) and tears down the dialog — reopen it in
        // the new theme so the switch feels live. Time-bounded so a stale flag
        // can't pop Settings open on an unrelated later resume.
        if (reopenSettingsAt != 0 && System.currentTimeMillis() - reopenSettingsAt < 3000) {
            reopenSettingsAt = 0;
            handler.post(this::showSettings);
        } else {
            reopenSettingsAt = 0;
        }
    }

    /** Set just before a dark-mode-driven recreate; consumed in the next onResume. */
    private static long reopenSettingsAt = 0;

    @Override protected void onPause() {
        super.onPause();
        repo.removeListener(repoListener);
        handler.removeCallbacks(rebuildRun);
        rebuildQueued = false;
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        DebugLog.unregister(debugListener);
    }

    // ---------------- contacts refresh + bridge catch-up ----------------
    private void loadConversations() {
        if (isLoading) return;
        isLoading = true;

        new Thread(() -> {
            try {
                String contactsJson = httpGet(restBase + "/v1/contacts/" + URLEncoder.encode(myNumber, "UTF-8"));
                JSONArray contacts = new JSONArray(contactsJson);

                PeerKeys peerKeys = PeerKeys.get(this);
                SharedPreferences.Editor ed = prefs.edit();
                synchronized (nameByPeerKey) { nameByPeerKey.clear(); }

                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject c = contacts.getJSONObject(i);
                    String display = chooseDisplayName(c);
                    String num     = c.optString("number", "");
                    String uuid    = c.optString("uuid", "");
                    if (isEmpty(num) && isEmpty(uuid)) continue;

                    if (notEmpty(num) && notEmpty(uuid)) peerKeys.learn(uuid, num);
                    String key = peerKeys.resolve(num, uuid);

                    if (!isEmpty(display)) {
                        synchronized (nameByPeerKey) { nameByPeerKey.put(key, display); }
                        ed.putString("contact_name_" + key, display);
                    }
                    JSONObject prof = c.optJSONObject("profile");
                    boolean hasAvatar = prof != null && prof.optBoolean("has_avatar", false);
                    ed.putString("contact_num_"    + key, num);
                    ed.putString("contact_uuid_"   + key, uuid);
                    ed.putString("contact_avatar_" + key, hasAvatar ? uuid : "");
                }
                ed.apply();

                // one global catch-up replaces the per-contact bridge N+1
                repo.catchUp();

                runOnUiThread(this::rebuildListFromDb);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load chats", Toast.LENGTH_SHORT).show());
            } finally {
                isLoading = false;
            }
        }).start();
    }

    // ---------------- list building (DB is the single source of truth) ----------------

    private final java.util.concurrent.ExecutorService rebuildExec =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private void rebuildListFromDb() {
        rebuildExec.execute(() -> {
            List<android.util.Pair<String, String[]>> summaries = repo.db.getConversationSummaries();
            List<Map<String, String>> rows = new ArrayList<>();
            for (android.util.Pair<String, String[]> s : summaries) {
                String key     = s.first;
                String snippet = s.second[0];
                String time    = s.second[1];
                String ts      = s.second[2];
                // a user-set local alias wins over anything signal-cli knows —
                // some contacts have no name in signal-cli at all (no synced
                // contact name, no profile name) and fall back to their number
                String name = prefs.getString("alias_" + key, "");
                if (isEmpty(name)) synchronized (nameByPeerKey) { name = nameByPeerKey.get(key); }
                if (isEmpty(name)) name = prefs.getString("contact_name_" + key, key);
                String num    = prefs.getString("contact_num_" + key, "");
                String uuid   = prefs.getString("contact_uuid_" + key, "");
                String avatar = prefs.getString("contact_avatar_" + key, "");
                long readTs   = prefs.getLong("read_ts_" + key, 0);
                int unread    = repo.db.countUnread(key, readTs);
                Map<String, String> row = new HashMap<>();
                row.put("name",        name);
                row.put("snippet",     snippet);
                row.put("time",        time);
                row.put("ts",          ts);
                row.put("number",      num);
                row.put("uuid",        uuid);
                row.put("avatar_path", avatar);
                row.put("unread",      String.valueOf(unread));
                rows.add(row);
            }
            runOnUiThread(() -> {
                all.clear();
                all.addAll(rows);
                filter(search == null ? "" : search.getText().toString());
            });
        });
    }

    // ---------------- Search filter (mutates the bound adapter's list) ----------------
    private void filter(String q) {
        q = q.toLowerCase(Locale.US).trim();
        visible.clear();
        for (Map<String, String> m : all) {
            String name = m.get("name");
            String snip = m.get("snippet");
            if (q.isEmpty()
                    || (name != null && name.toLowerCase(Locale.US).contains(q))
                    || (snip != null && snip.toLowerCase(Locale.US).contains(q))) {
                visible.add(m);
            }
        }
        adapter.notifyDataSetChanged();
    }

    // ---------------- Self avatar ----------------
    // ── Settings: toggle grid + bottom actions (built in code, no Material) ──

    private void showSettings() {
        final int radius = dpI(10);
        final int primaryText = themeColor(android.R.attr.textColorPrimary);

        android.widget.LinearLayout root = new android.widget.LinearLayout(this);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setPadding(dpI(12), dpI(8), dpI(12), dpI(12));

        android.widget.LinearLayout row1 = gridRow();
        android.widget.LinearLayout row2 = gridRow();
        root.addView(row1);
        root.addView(row2);

        final android.app.AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(wrapScroll(root))
                .setNegativeButton("Close", null)
                .create();

        // icons stick to Unicode 6.0 emoji — the BlackBerry font renders those
        // (🌙🐞) but not 2018-era emoji (🧪🧹) nor obscure BMP symbols (⎋☾).
        addToggle(row1, "🌙", "Dark mode", "dark_mode", radius, primaryText, false, () -> {
            boolean d = prefs.getBoolean("dark_mode", false);
            reopenSettingsAt = System.currentTimeMillis();  // reopen after recreate
            AppCompatDelegate.setDefaultNightMode(d ? AppCompatDelegate.MODE_NIGHT_YES
                    : AppCompatDelegate.MODE_NIGHT_NO);
        });
        addToggle(row1, "✓✓", "Read receipts", "send_read_receipts", radius, primaryText, true, null);
        addToggle(row2, "▶", "Demo mode", "demo_mode", radius, primaryText, false, () -> {
            adapter.setDemoMode(prefs.getBoolean("demo_mode", false));
            filter(search.getText().toString());
        });
        addToggle(row2, "🐞", "Debug log", "debug_log", radius, primaryText, false, this::updateDebugPanel);

        root.addView(settingsDivider());
        root.addView(actionRow("🔥   Purge message history", 0xFFFF9800, radius,
                () -> { dlg.dismiss(); doPurgeAll(); }));
        root.addView(actionRow("🚪   Log out", 0xFFD32F2F, radius,
                () -> { dlg.dismiss(); doLogout(); }));

        dlg.show();
    }

    /** A square-ish toggle card with ON=accent / OFF=muted visual states. */
    private void addToggle(android.widget.LinearLayout row, String icon, String title,
                           String prefKey, int radius, int primaryText,
                           boolean confirmOnEnable, Runnable onChanged) {
        android.widget.LinearLayout cell = new android.widget.LinearLayout(this);
        cell.setOrientation(android.widget.LinearLayout.VERTICAL);
        cell.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(0, dpI(86), 1f);
        lp.setMargins(dpI(4), dpI(4), dpI(4), dpI(4));
        cell.setLayoutParams(lp);
        cell.setPadding(dpI(6), dpI(6), dpI(6), dpI(6));

        final android.widget.TextView ic = new android.widget.TextView(this);
        ic.setText(icon); ic.setTextSize(22); ic.setGravity(android.view.Gravity.CENTER);
        final android.widget.TextView tt = new android.widget.TextView(this);
        tt.setText(title); tt.setTextSize(13); tt.setGravity(android.view.Gravity.CENTER);
        final android.widget.TextView st = new android.widget.TextView(this);
        st.setTextSize(11); st.setGravity(android.view.Gravity.CENTER);
        cell.addView(ic); cell.addView(tt); cell.addView(st);

        final Runnable refresh = () -> {
            boolean on = prefs.getBoolean(prefKey, false);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setCornerRadius(radius);
            g.setColor(on ? 0xFF2196F3 : 0x22808080);
            cell.setBackground(g);
            int tc = on ? 0xFFFFFFFF : primaryText;
            ic.setTextColor(tc); tt.setTextColor(tc);
            st.setTextColor(on ? 0xCCFFFFFF : 0xFF888888);
            st.setText(on ? "ON" : "OFF");
        };
        refresh.run();

        cell.setOnClickListener(v -> {
            pulse(v);
            boolean cur = prefs.getBoolean(prefKey, false);
            if (!cur && confirmOnEnable) {
                confirmReadReceipts(refresh);
                return;
            }
            prefs.edit().putBoolean(prefKey, !cur).apply();
            refresh.run();
            if (onChanged != null) onChanged.run();
        });
        row.addView(cell);
    }

    private android.widget.LinearLayout gridRow() {
        android.widget.LinearLayout r = new android.widget.LinearLayout(this);
        r.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        r.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        return r;
    }

    private android.view.View settingsDivider() {
        android.view.View v = new android.view.View(this);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dpI(1));
        lp.setMargins(dpI(4), dpI(14), dpI(4), dpI(8));
        v.setLayoutParams(lp);
        v.setBackgroundColor(0x33808080);
        return v;
    }

    private android.view.View actionRow(String text, int color, int radius, Runnable onClick) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(text); tv.setTextSize(15); tv.setTextColor(color);
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tv.setPadding(dpI(16), dpI(14), dpI(16), dpI(14));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(radius); g.setColor(0x18808080);
        tv.setBackground(g);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dpI(4), dpI(4), dpI(4), dpI(4));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> { pulse(v); onClick.run(); });
        return tv;
    }

    private android.widget.ScrollView wrapScroll(android.view.View child) {
        android.widget.ScrollView s = new android.widget.ScrollView(this);
        s.addView(child);
        return s;
    }

    private static void pulse(android.view.View v) {
        v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(70)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(70).start())
                .start();
    }

    private int themeColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId != 0 ? getResources().getColor(tv.resourceId) : tv.data;
    }

    private int dpI(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void confirmReadReceipts(Runnable refresh) {
        new AlertDialog.Builder(this)
                .setTitle("Send read receipts?")
                .setMessage("Only enable this if read receipts are also enabled in Signal on "
                        + "your phone — otherwise contacts would see read status your account "
                        + "setting is meant to hide.")
                .setPositiveButton("Enable", (d, w) -> {
                    prefs.edit().putBoolean("send_read_receipts", true).apply();
                    refresh.run();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doPurgeAll() {
        new AlertDialog.Builder(this)
                .setTitle("Purge message history?")
                .setMessage("Deletes ALL messages and media from this device and from the bridge "
                        + "server. You stay logged in. Your phone's Signal history is not affected.\n\n"
                        + "This cannot be undone.")
                .setPositiveButton("Purge everything", (d, w) -> new Thread(() -> {
                    String err = repo.purgeAllData(Messages.this);
                    runOnUiThread(() -> {
                        Toast.makeText(Messages.this,
                                err == null ? "All message data purged" : err,
                                Toast.LENGTH_LONG).show();
                        rebuildListFromDb();
                    });
                }).start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doLogout() {
        new AlertDialog.Builder(this)
                .setMessage("Log out and wipe all local messages, media and caches from this device?")
                .setPositiveButton("Log out", (d, w) -> {
                    stopService(new Intent(Messages.this, MessageService.class));
                    Repo.reset();
                    android.app.NotificationManager nm = (android.app.NotificationManager)
                            getSystemService(NOTIFICATION_SERVICE);
                    if (nm != null) nm.cancelAll();
                    deleteDatabase("signalberry.db");
                    deleteRecursive(new java.io.File(getFilesDir(), "att"));
                    deleteRecursive(getCacheDir());
                    getSharedPreferences("peer_map", MODE_PRIVATE).edit().clear().apply();
                    prefs.edit().clear().apply();
                    startActivity(new Intent(Messages.this, ServerConnect.class));
                    finish();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Local rename: store a per-peer alias used everywhere the name is shown.
     *  Purely local — never sent to Signal or the contact. */
    private void promptSetAlias(String key, String current) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        String existing = prefs.getString("alias_" + key, "");
        input.setText(existing);
        input.setSelection(input.getText().length());
        input.setHint("Name for this contact");
        new AlertDialog.Builder(this)
                .setTitle("Set name")
                .setMessage("Shown only on this device. Not sent to anyone.")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String v = input.getText().toString().trim();
                    if (v.isEmpty()) prefs.edit().remove("alias_" + key).apply();
                    else prefs.edit().putString("alias_" + key, v).apply();
                    rebuildListFromDb();
                })
                .setNeutralButton(isEmpty(existing) ? null : "Clear",
                        isEmpty(existing) ? null : (d, w) -> {
                            prefs.edit().remove("alias_" + key).apply();
                            rebuildListFromDb();
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void loadSelfAvatar() {
        ImageView iv = findViewById(R.id.toolbar_avatar);
        int size = (int)(36 * getResources().getDisplayMetrics().density + 0.5f);
        iv.setImageBitmap(initialsCircle(myNumber, size));

        new Thread(() -> {
            try {
                String contactsJson = httpGet(restBase + "/v1/contacts/" + URLEncoder.encode(myNumber, "UTF-8"));
                org.json.JSONArray arr = new org.json.JSONArray(contactsJson);
                String selfUuid = null;
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject c = arr.getJSONObject(i);
                    String num = c.optString("number", "");
                    if (digits(num).equals(digits(myNumber))) {
                        selfUuid = c.optString("uuid", "");
                        break;
                    }
                }
                if (isEmpty(selfUuid)) return;
                prefs.edit().putString("self_uuid", selfUuid).apply();
                repo.setSelf(myNumber, selfUuid);
                Bitmap bm = avatarCache.fetch(myNumber, selfUuid);
                if (bm == null) return;
                final Bitmap circle = circleClip(bm, size);
                handler.post(() -> iv.setImageBitmap(circle));
            } catch (Exception ignored) {}
        }).start();
    }

    private static void deleteRecursive(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] kids = f.listFiles();
        if (kids != null) for (java.io.File k : kids) deleteRecursive(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private Bitmap initialsCircle(String label, int size) {
        int[] palette = {0xFF1565C0, 0xFF2E7D32, 0xFF6A1B9A, 0xFF00838F,
                         0xFFAD1457, 0xFF4527A0, 0xFF00695C, 0xFFE65100};
        int color = palette[Math.abs((label == null ? 0 : label.hashCode()) % palette.length)];
        String letter = (label != null && !label.isEmpty()) ? label.substring(0, 1).toUpperCase() : "?";
        Bitmap bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bm);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(color);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill);
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(Color.WHITE);
        text.setTextSize(size * 0.4f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText(letter, size / 2f, size / 2f - (text.ascent() + text.descent()) / 2f, text);
        return bm;
    }

    private static Bitmap circleClip(Bitmap src, int size) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, size, size, true);
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new android.graphics.BitmapShader(scaled,
                android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return out;
    }
}
