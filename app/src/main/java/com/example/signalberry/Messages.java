package com.example.signalberry;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class Messages extends AppCompatActivity {

    private final List<Map<String, String>> all = new ArrayList<>();
    private android.widget.SimpleAdapter adapter;

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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.messages);
        setTitle("Messages");

        handler = new Handler(Looper.getMainLooper());

        ListView list = findViewById(R.id.list_people);
        search = findViewById(R.id.search);
        ImageButton plus = findViewById(R.id.toolbar_add);

        adapter = new android.widget.SimpleAdapter(
                this, all, R.layout.row_chat,
                new String[]{"name", "snippet", "time"},
                new int[]{R.id.name, R.id.snippet, R.id.time});
        list.setAdapter(adapter);

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
        String host = getSharedPreferences("signalberry", MODE_PRIVATE).getString("ip", "");
        myNumber    = getSharedPreferences("signalberry", MODE_PRIVATE).getString("number", "");
        restBase    = normalizeBase(host);
        bridgeBase  = makeBridgeBase(host);

        if (isEmpty(host) || isEmpty(myNumber)) {
            Toast.makeText(this, "Missing server IP or number", Toast.LENGTH_SHORT).show();
            return;
        }

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
        openWebSocket();
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
                    if (!isEmpty(display)) nameByPeerKey.put(key, display);

                    Map<String, String> row = new HashMap<>();
                    row.put("name", display);
                    row.put("snippet", "");
                    row.put("time", "");
                    row.put("number", num);
                    row.put("uuid",   uuid);
                    row.put("ts",     "0");
                    rows.add(row);
                }

                // hydrate last message from bridge
                for (int i = 0; i < rows.size(); i++) {
                    Map<String, String> row = rows.get(i);
                    String peer = !isEmpty(row.get("number")) ? row.get("number") : row.get("uuid");
                    if (isEmpty(peer)) continue;

                    try {
                        String url = bridgeBase + "/messages?peer=" + URLEncoder.encode(peer, "UTF-8")
                                + "&after=0&limit=50";
                        JSONObject obj = new JSONObject(httpGet(url));
                        JSONArray items = obj.optJSONArray("items");
                        if (items != null && items.length() > 0) {
                            JSONObject last = items.getJSONObject(items.length() - 1);
                            String dir  = last.optString("dir", "in");
                            String text = last.optString("text", "");
                            long ts     = last.optLong("serverTs", 0);
                            String prefix = "out".equals(dir) ? "You: " : "";
                            row.put("snippet", prefix + text);
                            row.put("time", formatShortTime(ts));
                            row.put("ts", String.valueOf(ts));
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
                }, 400);
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
                    }, 400);
                }
            }
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
        } else {
            Map<String, String> row = new HashMap<>();
            row.put("name", display);
            row.put("number", number == null ? "" : number);
            row.put("uuid",   uuid   == null ? "" : uuid);
            row.put("snippet", snippet);
            row.put("time", time);
            row.put("ts", String.valueOf(ts));
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

    // ---------------- Naming priority ----------------
    private String chooseDisplayName(JSONObject c) {
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

    private static String joinNames(String given, String family) {
        given = safeTrim(given);
        family = safeTrim(family);
        if (!isEmpty(given) && !isEmpty(family)) return given + " " + family;
        if (!isEmpty(given)) return given;
        if (!isEmpty(family)) return family;
        return "";
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
        android.widget.SimpleAdapter newAdapter = new android.widget.SimpleAdapter(
                this, filtered, R.layout.row_chat,
                new String[]{"name", "snippet", "time"},
                new int[]{R.id.name, R.id.snippet, R.id.time});
        ((ListView) findViewById(R.id.list_people)).setAdapter(newAdapter);
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

    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    private static String formatShortTime(long tsMillis) {
        if (tsMillis <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(tsMillis);
        boolean sameDay = now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR);
        if (sameDay) return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(tsMillis));
        return new SimpleDateFormat("MMM d", Locale.getDefault()).format(new Date(tsMillis));
    }

    private static String normalizeBase(String hostPort) {
        if ("localhost:5000".equals(hostPort) || "127.0.0.1:5000".equals(hostPort))
            hostPort = "10.0.2.2:5000";
        if (!hostPort.startsWith("http://") && !hostPort.startsWith("https://"))
            hostPort = "http://" + hostPort;
        if (hostPort.endsWith("/")) hostPort = hostPort.substring(0, hostPort.length() - 1);
        return hostPort;
    }

    private static String toWs(String httpBase) {
        if (httpBase.startsWith("https://")) return "wss://" + httpBase.substring("https://".length());
        if (httpBase.startsWith("http://"))  return "ws://"  + httpBase.substring("http://".length());
        return "ws://" + httpBase;
    }

    private static String makeBridgeBase(String hostPort) {
        String hostOnly = hostPort;
        if (hostOnly.startsWith("http://"))  hostOnly = hostOnly.substring(7);
        if (hostOnly.startsWith("https://")) hostOnly = hostOnly.substring(8);
        if ("localhost:5000".equals(hostOnly) || "127.0.0.1:5000".equals(hostOnly)) hostOnly = "10.0.2.2:5000";
        int colon = hostOnly.indexOf(':');
        if (colon > 0) hostOnly = hostOnly.substring(0, colon);
        return "http://" + hostOnly + ":9099";
    }

    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(7000);
        c.setReadTimeout(7000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                (code >= 400 ? c.getErrorStream() : c.getInputStream())))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line);
            String out = sb.toString();
            return out.isEmpty() ? "[]" : out;
        } finally { c.disconnect(); }
    }

    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
    private static String safeTrim(String s) { return s == null ? null : s.trim(); }
    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
        }
        return out.toString();
    }
    private static String peerKey(String number, String uuid) {
        String d = digits(number);
        return !isEmpty(d) ? d : (uuid == null ? "" : uuid);
    }
    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }
    private static String shortUuid(String uuid) {
        uuid = safeTrim(uuid);
        if (isEmpty(uuid)) return "";
        int cut = uuid.indexOf('-');
        if (cut > 0) return uuid.substring(0, cut);
        return (uuid.length() > 8) ? uuid.substring(0, 8) : uuid;
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
            }, 300);
        } catch (Exception ignored) {}
    }

}
