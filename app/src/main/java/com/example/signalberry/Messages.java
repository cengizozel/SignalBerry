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
            new AlertDialog.Builder(this)
                    .setTitle(item.get("name"))
                    .setItems(new String[]{
                            muted ? "Unmute" : "Mute notifications",
                            "Delete conversation"
                    }, (d, w) -> {
                        if (w == 0) {
                            prefs.edit().putBoolean("mute_" + key, !muted).apply();
                        } else {
                            new AlertDialog.Builder(this)
                                    .setMessage("Delete all messages in this conversation from this device? "
                                            + "(Your phone keeps its copy.)")
                                    .setPositiveButton("Delete", (dd, ww) -> new Thread(() -> {
                                        repo.deleteThread(key);
                                        runOnUiThread(this::rebuildListFromDb);
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
        toolbarAvatar.setOnClickListener(v -> {
            boolean dbgOn  = prefs.getBoolean("debug_log", false);
            boolean darkOn = prefs.getBoolean("dark_mode", false);
            boolean demoOn = prefs.getBoolean("demo_mode", false);
            boolean rrOn   = prefs.getBoolean("send_read_receipts", false);
            new AlertDialog.Builder(this)
                    .setTitle("Settings")
                    .setItems(new String[]{
                            "Log out",
                            "Debug log: " + (dbgOn ? "ON ✓" : "OFF"),
                            "Dark mode: " + (darkOn ? "ON ✓" : "OFF"),
                            "Demo mode: " + (demoOn ? "ON ✓" : "OFF"),
                            "Send read receipts: " + (rrOn ? "ON ✓" : "OFF")
                    }, (dialog, which) -> {
                        if (which == 0) {
                            new AlertDialog.Builder(Messages.this)
                                    .setMessage("Log out and wipe all local messages, media and caches from this device?")
                                    .setPositiveButton("Log out", (dd, ww) -> {
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
                        } else if (which == 1) {
                            boolean newVal = !prefs.getBoolean("debug_log", false);
                            prefs.edit().putBoolean("debug_log", newVal).apply();
                            updateDebugPanel();
                        } else if (which == 2) {
                            boolean newDark = !prefs.getBoolean("dark_mode", false);
                            prefs.edit().putBoolean("dark_mode", newDark).apply();
                            AppCompatDelegate.setDefaultNightMode(
                                    newDark ? AppCompatDelegate.MODE_NIGHT_YES
                                            : AppCompatDelegate.MODE_NIGHT_NO);
                        } else if (which == 3) {
                            boolean newDemo = !prefs.getBoolean("demo_mode", false);
                            prefs.edit().putBoolean("demo_mode", newDemo).apply();
                            adapter.setDemoMode(newDemo);
                            filter(search.getText().toString());
                        } else {
                            boolean newRr = !prefs.getBoolean("send_read_receipts", false);
                            if (newRr) {
                                new AlertDialog.Builder(Messages.this)
                                        .setTitle("Send read receipts?")
                                        .setMessage("Only enable this if read receipts are also "
                                                + "enabled in Signal on your phone — otherwise "
                                                + "contacts would see read status your account "
                                                + "setting is meant to hide.")
                                        .setPositiveButton("Enable", (dd, ww) ->
                                                prefs.edit().putBoolean("send_read_receipts", true).apply())
                                        .setNegativeButton("Cancel", null)
                                        .show();
                            } else {
                                prefs.edit().putBoolean("send_read_receipts", false).apply();
                            }
                        }
                    })
                    .show();
        });

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
    }

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

    private void rebuildListFromDb() {
        new Thread(() -> {
            List<android.util.Pair<String, String[]>> summaries = repo.db.getConversationSummaries();
            List<Map<String, String>> rows = new ArrayList<>();
            for (android.util.Pair<String, String[]> s : summaries) {
                String key     = s.first;
                String snippet = s.second[0];
                String time    = s.second[1];
                String ts      = s.second[2];
                String name;
                synchronized (nameByPeerKey) { name = nameByPeerKey.get(key); }
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
        }).start();
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
