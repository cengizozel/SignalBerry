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
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.example.signalberry.Utils.*;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class Messages extends AppCompatActivity {

    private final List<Map<String, String>> all = new ArrayList<>();
    private MessagesAdapter adapter;
    private SharedPreferences prefs;

    private EditText search;
    private boolean isLoading = false;

    // Real-time bits
    private OkHttpClient wsClient;
    private WebSocket ws;
    private int retrySec = 1;
    private Handler handler;

    // Bases / prefs
    private String restBase;    // http://IP:PORT (Signal REST)
    private String bridgeBase;  // http://IP:9099    (Bridge)
    private String myNumber;

    // Contact display name cache
    private final Map<String,String> nameByPeerKey = new HashMap<>();

    private AvatarCache avatarCache;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.messages);
        setTitle("Messages");

        handler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences("signalberry", MODE_PRIVATE);

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

            startActivity(new android.content.Intent(Messages.this, Chat.class)
                    .putExtra("peer_name",   peerName)
                    .putExtra("peer_number", peerNumber)
                    .putExtra("peer_uuid",   peerUuid));
        });

        plus.setOnClickListener(v ->
                startActivity(new android.content.Intent(Messages.this, NewChat.class)));

        // read prefs / build bases
        SharedPreferences sp = getSharedPreferences("signalberry", MODE_PRIVATE);
        String host       = sp.getString("ip", "");
        String bridgePref = sp.getString("bridge", ""); // set by ServerConnect
        myNumber          = sp.getString("number", "");

        restBase   = normalizeBase(host);
        bridgeBase = normalizeBase(isEmpty(bridgePref) ? deriveBridgeBase(host) : bridgePref);

        if (isEmpty(host) || isEmpty(myNumber)) {
            Toast.makeText(this, "Missing server IP or number", Toast.LENGTH_SHORT).show();
            return;
        }

        avatarCache = new AvatarCache(getCacheDir(), restBase, myNumber);
        adapter = new MessagesAdapter(this, all, avatarCache);
        list.setAdapter(adapter);

        // service start moved to onResume so activity is guaranteed visible

        loadConversationCache();
        loadSelfAvatar();

        ImageView toolbarAvatar = findViewById(R.id.toolbar_avatar);
        toolbarAvatar.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Settings")
                    .setItems(new String[]{"Log out"}, (dialog, which) -> {
                        getSharedPreferences("signalberry", MODE_PRIVATE).edit().clear().apply();
                        startActivity(new Intent(Messages.this, ServerConnect.class));
                        finish();
                    })
                    .show();
        });

        // Initial load
        loadConversations();

        // Search filter
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // WS client
        wsClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
    }

    @Override protected void onResume() {
        super.onResume();
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
        openWebSocket();
        refreshUnreadFromLocal();
        applyThreadHintIfAny();
    }

    @Override protected void onPause() {
        super.onPause();
        closeWebSocket();
    }

    // ---------------- Initial load (contacts + latest message per peer) ----------------
    private void loadConversations() {
        if (isLoading) return;
        isLoading = true;

        new Thread(() -> {
            try {
                String contactsJson = httpGet(restBase + "/v1/contacts/" + URLEncoder.encode(myNumber, "UTF-8"));
                JSONArray contacts = new JSONArray(contactsJson);

                List<Map<String, String>> rows = new ArrayList<>();
                nameByPeerKey.clear();

                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject c = contacts.getJSONObject(i);


                    String display = chooseDisplayName(c);
                    String num     = c.optString("number", "");
                    String uuid    = c.optString("uuid", "");
                    if (isEmpty(num) && isEmpty(uuid)) continue;

                    String key = peerKey(num, uuid);
                    if (!isEmpty(display)) {
                        nameByPeerKey.put(key, display);
                        prefs.edit().putString("contact_name_" + key, display).apply();
                    }

                    JSONObject prof = c.optJSONObject("profile");
                    boolean hasAvatar = prof != null && prof.optBoolean("has_avatar", false);
                    String avatarPath = hasAvatar ? uuid : "";

                    Map<String, String> row = new HashMap<>();
                    row.put("name",        display);
                    row.put("snippet",     "");
                    row.put("time",        "");
                    row.put("number",      num);
                    row.put("uuid",        uuid);
                    row.put("ts",          "0");
                    row.put("avatar_path", avatarPath);
                    rows.add(row);
                }

                // hydrate last message + unread count from bridge
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, String> row = rows.get(i);
                    String num  = row.get("number");
                    String uuid = row.get("uuid");
                    String peer = !isEmpty(num) ? num : uuid;
                    if (isEmpty(peer)) continue;

                    String chatKey = !isEmpty(num) ? digits(num) : (uuid != null ? uuid : "");
                    long readTs = prefs.getLong("read_ts_" + chatKey, 0);

                    try {
                        String url = bridgeBase + "/messages?peer=" + URLEncoder.encode(peer, "UTF-8")
                                + "&after=0&limit=50";
                        JSONObject obj = new JSONObject(httpGet(url));
                        JSONArray items = obj.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            JSONObject last = items.getJSONObject(items.length() - 1);
                            String dir   = last.optString("dir", "in");
                            String text  = last.optString("text", "");
                            String attId = last.optString("attId", "");
                            long ts      = last.optLong("serverTs", 0);
                            String prefix  = "out".equals(dir) ? "You: " : "";
                            String display = (!text.isEmpty()) ? text : (!attId.isEmpty() ? "📷 Photo" : "");
                            row.put("snippet", prefix + display);
                            row.put("time", formatShortTime(ts));
                            row.put("ts", String.valueOf(ts));

                            int unread = 0;
                            for (int j = 0; j < items.length(); j++) {
                                JSONObject it = items.getJSONObject(j);
                                if ("in".equals(it.optString("dir")) && it.optLong("serverTs", 0) > readTs)
                                    unread++;
                            }
                            row.put("unread", String.valueOf(unread));
                        }
                    } catch (Exception ignored) {}
                }

                // keep only threads with messages; sort newest first
                List<Map<String, String>> withMsg = new ArrayList<>();
                for (Map<String, String> r : rows) {
                    long ts = parseLongSafe(r.get("ts"));
                    if (ts > 0) withMsg.add(r);
                }
                sortByTsDesc(withMsg);

                saveConversationCache(withMsg);
                runOnUiThread(() -> {
                    all.clear();
                    all.addAll(withMsg);
                    adapter.notifyDataSetChanged();
                    filter(search.getText().toString());
                });

            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Failed to load chats", Toast.LENGTH_SHORT).show());
            } finally {
                isLoading = false;
            }
        }).start();
    }

    // ---------------- WebSocket: envelopes -> optimistic update + delayed reconcile ----------------
    private void openWebSocket() {
        if (ws != null) return;
        try {
            String wsUrl = toWs(restBase) + "/v1/receive/" + URLEncoder.encode(myNumber, "UTF-8");
            Request req = new Request.Builder().url(wsUrl).build();
            ws = wsClient.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) { retrySec = 1; }
                @Override public void onMessage(WebSocket webSocket, String text) { handleWsPayload(text); }
                @Override public void onMessage(WebSocket webSocket, ByteString bytes) { handleWsPayload(bytes.utf8()); }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) { ws = null; scheduleReconnect(); }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response r) { ws = null; scheduleReconnect(); }
            });
        } catch (Exception e) { scheduleReconnect(); }
    }

    private void scheduleReconnect() {
        if (isFinishing() || isDestroyed()) return;
        final int delay = Math.min(retrySec, 16);
        retrySec = Math.min(retrySec * 2, 16);
        handler.postDelayed(new Runnable() { @Override public void run() { openWebSocket(); } }, delay * 1000L);
    }

    private void closeWebSocket() {
        if (ws != null) { try { ws.close(1000, "leaving"); } catch (Exception ignored) {} ws = null; }
        retrySec = 1;
    }

    private void handleWsPayload(String payload) {
        try {
            if (payload.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(payload);
                for (int i = 0; i < arr.length(); i++) handleEnvelope(arr.getJSONObject(i));
            } else {
                handleEnvelope(new JSONObject(payload));
            }
        } catch (Exception ignored) {}
    }

    private void handleEnvelope(JSONObject obj) {
        JSONObject env = obj.optJSONObject("envelope");
        if (env == null) return;

        // Incoming message → optimistic update
        String srcNum  = env.optString("sourceNumber", env.optString("source", ""));
        String srcUuid = env.optString("sourceUuid", "");
        JSONObject data = env.optJSONObject("dataMessage");
        if (data != null) {
            String text = data.optString("message", data.optString("text", "")).trim();
            long ts = env.optLong("timestamp", System.currentTimeMillis());
            if (!isEmpty(text)) {
                runOnUiThread(() ->
                        updateRowImmediateUI(srcNum, srcUuid, /*outgoing=*/false, text, ts));
                // delayed reconcile (bridge write may lag)
                handler.postDelayed(new Runnable() {
                    @Override public void run() { refreshOnePeer(srcNum, srcUuid); }
                }, 800);
            }
        }

        // Sent echo (you sent from another device) → optimistic update
        JSONObject sync = env.optJSONObject("syncMessage");
        if (sync != null) {
            JSONObject sent = sync.optJSONObject("sentMessage");
            if (sent != null) {
                String destNum  = sent.optString("destinationNumber", "");
                String destUuid = sent.optString("destinationUuid", "");
                String text     = sent.optString("message", "").trim();
                long ts         = sent.optLong("timestamp", System.currentTimeMillis());
                if (!isEmpty(text)) {
                    runOnUiThread(() ->
                            updateRowImmediateUI(destNum, destUuid, /*outgoing=*/true, text, ts));
                    handler.postDelayed(new Runnable() {
                        @Override public void run() { refreshOnePeer(destNum, destUuid); }
                    }, 800);
                }
            }

            // Read sync: another linked device read a conversation
            try {
                org.json.JSONArray readMsgs = sync.optJSONArray("readMessages");
                if (readMsgs != null) {
                    for (int i = 0; i < readMsgs.length(); i++) {
                        JSONObject rm = readMsgs.getJSONObject(i);
                        String sNum  = rm.optString("senderNumber", "");
                        String sUuid = rm.optString("senderUuid", "");
                        long ts      = rm.optLong("timestamp", 0);
                        if (ts <= 0) continue;
                        String chatKey = !isEmpty(sNum) ? digits(sNum) : safeTrim(sUuid);
                        if (isEmpty(chatKey)) continue;
                        long curTs = prefs.getLong("read_ts_" + chatKey, 0);
                        if (ts > curTs) prefs.edit().putLong("read_ts_" + chatKey, ts).apply();
                        final String ck = chatKey;
                        runOnUiThread(() -> clearUnreadForKey(ck));
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // Optimistic UI update (call on UI thread)
    private void updateRowImmediateUI(String number, String uuid, boolean outgoing, String text, long ts) {
        final String peer = !isEmpty(number) ? number : uuid;
        if (isEmpty(peer)) return;

        String key = peerKey(number, uuid);
        String display = nameByPeerKey.get(key);
        if (isEmpty(display)) {
            display = !isEmpty(number) ? number
                    : (!isEmpty(uuid) ? ("Signal user " + shortUuid(uuid)) : "Unknown");
            nameByPeerKey.put(key, display);
        }

        String snippet = (outgoing ? "You: " : "") + text;
        String time = formatShortTime(ts);

        int idx = findRowIndex(number, uuid);
        if (idx >= 0) {
            Map<String, String> row = all.get(idx);
            row.put("name", display);
            row.put("number", number == null ? "" : number);
            row.put("uuid",   uuid   == null ? "" : uuid);
            row.put("snippet", snippet);
            row.put("time", time);
            row.put("ts", String.valueOf(ts));
            if (!outgoing) {
                int cur = parseSafeInt(row.get("unread"));
                row.put("unread", String.valueOf(cur + 1));
            }
        } else {
            Map<String, String> row = new HashMap<>();
            row.put("name", display);
            row.put("number", number == null ? "" : number);
            row.put("uuid",   uuid   == null ? "" : uuid);
            row.put("snippet", snippet);
            row.put("time", time);
            row.put("ts", String.valueOf(ts));
            row.put("unread", outgoing ? "0" : "1");
            all.add(row);
        }
        sortByTsDesc(all);
        adapter.notifyDataSetChanged();
        filter(search.getText().toString());
    }

    private void refreshOnePeer(String number, String uuid) {
        final String peer = !isEmpty(number) ? number : uuid;
        if (isEmpty(peer)) return;

        new Thread(() -> {
            try {
                String url = bridgeBase + "/messages?peer=" + URLEncoder.encode(peer, "UTF-8") + "&after=0&limit=50";
                JSONObject obj = new JSONObject(httpGet(url));
                JSONArray items = obj.optJSONArray("items");
                if (items == null || items.length() == 0) return;

                JSONObject last = items.getJSONObject(items.length() - 1);
                String dir  = last.optString("dir", "in");
                String text = last.optString("text", "");
                long ts     = last.optLong("serverTs", 0);

                final String snippet  = ("out".equals(dir) ? "You: " : "") + text;
                final String time     = formatShortTime(ts);
                final long   tsFinal  = ts;

                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        int idx = findRowIndex(number, uuid);
                        if (idx >= 0) {
                            Map<String, String> row = all.get(idx);
                            long curTs = parseLongSafe(row.get("ts"));   // <- existing ts in UI
                            if (tsFinal >= curTs) {                      // <- DO NOT DOWNGRADE
                                row.put("snippet", snippet);
                                row.put("time", time);
                                row.put("ts", String.valueOf(tsFinal));
                                sortByTsDesc(all);
                                adapter.notifyDataSetChanged();
                                filter(search.getText().toString());
                            }
                            // else: ignore older bridge result
                        } else {
                            // row doesn’t exist yet – add it
                            Map<String, String> row = new HashMap<String, String>();
                            row.put("name", nameByPeerKey.get(peerKey(number, uuid)));
                            row.put("number", number == null ? "" : number);
                            row.put("uuid",   uuid   == null ? "" : uuid);
                            row.put("snippet", snippet);
                            row.put("time", time);
                            row.put("ts", String.valueOf(tsFinal));
                            all.add(row);
                            sortByTsDesc(all);
                            adapter.notifyDataSetChanged();
                            filter(search.getText().toString());
                        }
                    }
                });

            } catch (Exception ignored) {}
        }).start();
    }

    private int findRowIndex(String number, String uuid) {
        String wantNum = digits(number);
        String wantUuid = safeTrim(uuid);
        for (int i = 0; i < all.size(); i++) {
            Map<String, String> row = all.get(i);
            String rNum  = digits(row.get("number"));
            String rUuid = safeTrim(row.get("uuid"));
            boolean numMatch  = !isEmpty(wantNum)  && wantNum.equals(rNum);
            boolean uuidMatch = !isEmpty(wantUuid) && wantUuid.equalsIgnoreCase(rUuid);
            if (numMatch || uuidMatch) return i;
        }
        return -1;
    }

    private void clearUnreadForKey(String chatKey) {
        for (Map<String, String> row : all) {
            String num  = row.get("number");
            String uuid = row.get("uuid");
            String rKey = !isEmpty(num) ? digits(num) : safeTrim(uuid);
            if (chatKey.equals(rKey)) {
                row.put("unread", "0");
                break;
            }
        }
        adapter.notifyDataSetChanged();
        filter(search.getText().toString());
    }

    // ---------------- Unread counts (local, no network) ----------------
    private void refreshUnreadFromLocal() {
        String lastReadPeer = prefs.getString("last_read_peer", "");
        if (lastReadPeer.isEmpty()) return;
        for (Map<String, String> row : all) {
            String num  = row.get("number");
            String uuid = row.get("uuid");
            String chatKey = !isEmpty(num) ? digits(num) : safeTrim(uuid);
            if (lastReadPeer.equals(chatKey)) {
                row.put("unread", "0");
                break;
            }
        }
        prefs.edit().remove("last_read_peer").apply();
        adapter.notifyDataSetChanged();
        filter(search.getText().toString());
    }

    // ---------------- Search filter ----------------
    private void filter(String q) {
        q = q.toLowerCase(Locale.US).trim();
        List<Map<String, String>> filtered = new ArrayList<>();
        for (Map<String, String> m : all) {
            String name = m.get("name");
            String snip = m.get("snippet");
            if ((name != null && name.toLowerCase(Locale.US).contains(q)) ||
                    (snip != null && snip.toLowerCase(Locale.US).contains(q))) {
                filtered.add(m);
            }
        }
        ((ListView) findViewById(R.id.list_people)).setAdapter(
                new MessagesAdapter(this, filtered, avatarCache));
    }

    // ---------------- Conversation cache ----------------
    private static final String PREF_CONV_CACHE = "conv_cache";

    private void saveConversationCache(List<Map<String, String>> rows) {
        try {
            JSONArray arr = new JSONArray();
            for (Map<String, String> row : rows) {
                JSONObject o = new JSONObject();
                for (Map.Entry<String, String> e : row.entrySet())
                    if (e.getValue() != null) o.put(e.getKey(), e.getValue());
                arr.put(o);
            }
            prefs.edit().putString(PREF_CONV_CACHE, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void loadConversationCache() {
        try {
            String raw = prefs.getString(PREF_CONV_CACHE, "[]");
            JSONArray arr = new JSONArray(raw);
            if (arr.length() == 0) return;
            List<Map<String, String>> cached = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Map<String, String> row = new HashMap<>();
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    row.put(k, o.optString(k, ""));
                }
                cached.add(row);
            }
            all.clear();
            all.addAll(cached);
            adapter.notifyDataSetChanged();
        } catch (Exception ignored) {}
    }

    // ---------------- Self avatar ----------------
    private void loadSelfAvatar() {
        ImageView iv = findViewById(R.id.toolbar_avatar);
        int size = (int)(36 * getResources().getDisplayMetrics().density + 0.5f);
        iv.setImageBitmap(initialsCircle(myNumber, size));

        new Thread(() -> {
            try {
                // Look up own UUID from the accounts list — try /v1/accounts first
                String json = httpGet(restBase + "/v1/accounts");
                // accounts is a JSON array of numbers; no UUID exposed there.
                // Instead use the contacts endpoint with own number to find self entry.
                // Fallback: try /v1/contacts/{myNumber} and look for self in list.
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
                Bitmap bm = avatarCache.fetch(myNumber, selfUuid);
                if (bm == null) return;
                final Bitmap circle = circleClip(bm, size);
                handler.post(() -> iv.setImageBitmap(circle));
            } catch (Exception ignored) {}
        }).start();
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

    // ---------------- Utils ----------------
    private static void sortByTsDesc(List<Map<String,String>> rows) {
        Collections.sort(rows, new Comparator<Map<String, String>>() {
            @Override public int compare(Map<String, String> a, Map<String, String> b) {
                long ta = parseLongSafe(a.get("ts"));
                long tb = parseLongSafe(b.get("ts"));
                if (tb == ta) return 0;
                return (tb > ta) ? 1 : -1;
            }
        });
    }

    private void applyThreadHintIfAny() {
        try {
            String raw = getSharedPreferences("signalberry", MODE_PRIVATE)
                    .getString("thread_hint", null);
            if (raw == null || raw.length() == 0) return;

            JSONObject h = new JSONObject(raw);
            String num  = h.optString("peer_number", "");
            String uuid = h.optString("peer_uuid", "");
            String text = h.optString("text", "");
            long   ts   = h.optLong("ts", System.currentTimeMillis());

            // Optimistic row update: adds new convo if missing, bumps to top, shows "You: ..."
            updateRowImmediateUI(num, uuid, /*outgoing=*/true, text, ts);

            // clear the hint so it doesn't re-apply
            getSharedPreferences("signalberry", MODE_PRIVATE)
                    .edit().remove("thread_hint").apply();

            // optional: schedule a tiny reconcile to pull the last line from the bridge
            handler.postDelayed(new Runnable() {
                @Override public void run() { refreshOnePeer(num, uuid); }
            }, 800);
        } catch (Exception ignored) {}
    }

}
