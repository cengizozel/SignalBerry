package com.example.signalberry;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class Chat extends AppCompatActivity {

    // Expose to adapter (package-private used above)
    static final int ST_PENDING   = 0;
    static final int ST_SENT      = 1;
    static final int ST_DELIVERED = 2;
    static final int ST_READ      = 3;

    private String base;
    private String myNumber;
    private String peerNumber;
    private String peerUuid;
    private String peerName;

    private SharedPreferences prefs;
    private String histKey;

    // RecyclerView
    private RecyclerView recycler;
    private ChatAdapter chatAdapter;
    private final List<MessageItem> items = new ArrayList<>();

    // WebSocket
    private OkHttpClient wsClient;
    private WebSocket ws;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retrySec = 1;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.chat);
        setTitle("");

        prefs = getSharedPreferences("signalberry", MODE_PRIVATE);

        String host = prefs.getString("ip", "");
        myNumber   = prefs.getString("number", "");
        peerNumber = getIntent().getStringExtra("peer_number");
        peerUuid   = getIntent().getStringExtra("peer_uuid");
        peerName   = getIntent().getStringExtra("peer_name");
        if (peerName == null || peerName.isEmpty())
            peerName = (notEmpty(peerNumber) ? peerNumber : notEmpty(peerUuid) ? peerUuid : "Chat");

        if (!notEmpty(host) || !notEmpty(myNumber) || (!notEmpty(peerNumber) && !notEmpty(peerUuid))) {
            Toast.makeText(this, "Missing server IP / my number / peer id", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        base = normalizeBase(host);
        histKey = "chat_hist_" + (notEmpty(peerNumber) ? digits(peerNumber) : peerUuid);

        // Top bar
        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());
        ((android.widget.TextView) findViewById(R.id.title_name)).setText(peerName);

        // RecyclerView setup
        recycler = findViewById(R.id.chat_list);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true); // start from bottom
        recycler.setLayoutManager(lm);
        chatAdapter = new ChatAdapter(items);
        recycler.setAdapter(chatAdapter);

        // Composer
        EditText input = findViewById(R.id.input_message);
        ImageButton send = findViewById(R.id.btn_send);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;

            long localId = System.currentTimeMillis();
            addToHistory("me", text, localId, ST_PENDING, -1, true);
            input.setText("");

            send.setEnabled(false);
            new Thread(() -> {
                boolean ok = sendOnce(text);
                runOnUiThread(() -> send.setEnabled(true));
                if (ok) {
                    if (markNewestMyPendingTo(ST_SENT)) runOnUiThread(this::reloadUIFromPrefs);
                } else {
                    runOnUiThread(() ->
                            Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show()
                    );
                }
            }).start();
        });

        // Load history
        loadHistory();

        // WebSocket client
        wsClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
    }

    @Override protected void onResume() {
        super.onResume();
        openWebSocket();
    }

    @Override protected void onPause() {
        super.onPause();
        closeWebSocket();
    }

    // --- WebSocket bits (same logic as before) ---
    private void openWebSocket() {
        if (ws != null) return;
        try {
            String wsUrl = toWs(base) + "/v1/receive/" + URLEncoder.encode(myNumber, "UTF-8");
            Request req = new Request.Builder().url(wsUrl).build();
            ws = wsClient.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) { retrySec = 1; }
                @Override public void onMessage(WebSocket webSocket, String text) { handleWsPayload(text); }
                @Override public void onMessage(WebSocket webSocket, ByteString bytes) { handleWsPayload(bytes.utf8()); }
                @Override public void onClosed(WebSocket webSocket, int code, String reason) { ws = null; scheduleReconnect(); }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response r) { ws = null; scheduleReconnect(); }
            });
        } catch (Exception e) {
            scheduleReconnect();
        }
    }
    private void scheduleReconnect() {
        if (isFinishing() || isDestroyed()) return;
        final int delay = Math.min(retrySec, 16);
        retrySec = Math.min(retrySec * 2, 16);
        handler.postDelayed(this::openWebSocket, delay * 1000L);
    }
    private void closeWebSocket() {
        if (ws != null) { try { ws.close(1000, "leaving"); } catch (Exception ignored) {} ws = null; }
        retrySec = 1;
        handler.removeCallbacksAndMessages(null);
    }

    private void handleWsPayload(String payload) {
        boolean changed = false;
        try {
            if (payload.trim().startsWith("[")) {
                JSONArray arr = new JSONArray(payload);
                for (int i = 0; i < arr.length(); i++) if (handleEnvelope(arr.getJSONObject(i))) changed = true;
            } else {
                if (handleEnvelope(new JSONObject(payload))) changed = true;
            }
        } catch (Exception ignored) {}
        if (changed) runOnUiThread(this::reloadUIFromPrefs);
    }

    private boolean handleEnvelope(JSONObject obj) {
        boolean changed = false;
        JSONObject env = obj.optJSONObject("envelope");
        if (env == null) return false;

        // Incoming
        String srcNum  = env.optString("sourceNumber", env.optString("source", ""));
        String srcUuid = env.optString("sourceUuid", "");
        if (isFromPeer(srcNum, srcUuid)) {
            JSONObject data = env.optJSONObject("dataMessage");
            if (data != null) {
                String text = data.optString("message", data.optString("text", "")).trim();
                long ts = env.optLong("timestamp", System.currentTimeMillis());
                if (!text.isEmpty()) {
                    addToHistory("peer", text, ts, ST_DELIVERED, -1, true);
                    changed = true;
                }
            }
        }

        // Sent echo
        JSONObject sync = env.optJSONObject("syncMessage");
        if (sync != null) {
            JSONObject sent = sync.optJSONObject("sentMessage");
            if (sent != null) {
                long serverTs = sent.optLong("timestamp", 0);
                String destNum  = sent.optString("destinationNumber", "");
                String destUuid = sent.optString("destinationUuid", "");
                if (isDestThisChat(destNum, destUuid)) {
                    boolean a = attachServerTimestampToNewestPending(serverTs);
                    boolean b = (serverTs > 0) && upgradeStatusByServerTs(serverTs, ST_SENT);
                    if (a || b) changed = true;
                }
            }
        }

        // Receipts
        JSONObject receipt = env.optJSONObject("receiptMessage");
        if (receipt != null) {
            String type = receipt.optString("type", "").toUpperCase();
            JSONArray tsArr = receipt.optJSONArray("timestamps");
            if (tsArr != null) {
                for (int j = 0; j < tsArr.length(); j++) {
                    long ts = tsArr.optLong(j, 0);
                    if (ts == 0) continue;
                    if ("DELIVERY".equals(type))      { if (upgradeStatusByServerTs(ts, ST_DELIVERED)) changed = true; }
                    else if ("READ".equals(type) ||
                            "VIEWED".equals(type))   { if (upgradeStatusByServerTs(ts, ST_READ))      changed = true; }
                }
            }
        }
        return changed;
    }

    // --- Send over HTTP (unchanged) ---
    private boolean sendOnce(String text) {
        try {
            String url = base + "/v2/send";
            JSONObject body = new JSONObject();
            body.put("message", text);
            body.put("number", myNumber);
            JSONArray rcpts = new JSONArray();
            if (notEmpty(peerNumber)) rcpts.put(peerNumber); else if (notEmpty(peerUuid)) rcpts.put(peerUuid);
            body.put("recipients", rcpts);
            int code = httpPostJson(url, body.toString());
            return code >= 200 && code < 300;
        } catch (Exception e) { return false; }
    }

    // --- History <-> UI glue ---
    private void loadHistory() {
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            items.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                String from = m.optString("from", "peer");
                String text = m.optString("text", "");
                int status  = m.optInt("status", ST_DELIVERED);
                items.add(new MessageItem(from, text, status));
            }
            chatAdapter.notifyDataSetChanged();
            if (!items.isEmpty()) recycler.scrollToPosition(items.size() - 1);
        } catch (Exception ignored) { }
    }
    private void reloadUIFromPrefs() { loadHistory(); }

    private void addToHistory(String from, String text, long localTs, int status, long serverTs, boolean updateUI) {
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            if (arr.length() > 400) {
                JSONArray newer = new JSONArray();
                for (int i = 50; i < arr.length(); i++) newer.put(arr.get(i));
                arr = newer;
            }
            JSONObject m = new JSONObject();
            m.put("from", from); m.put("text", text);
            m.put("ts", localTs); m.put("status", status); m.put("serverTs", serverTs);
            arr.put(m);
            prefs.edit().putString(histKey, arr.toString()).apply();

            if (updateUI) {
                items.add(new MessageItem(from, text, status));
                chatAdapter.notifyItemInserted(items.size() - 1);
                recycler.scrollToPosition(items.size() - 1);
            }
        } catch (Exception ignored) { }
    }

    private boolean markNewestMyPendingTo(int newStatus) {
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject m = arr.getJSONObject(i);
                if (!"me".equals(m.optString("from"))) continue;
                if (m.optInt("status", ST_PENDING) != ST_PENDING) continue;
                m.put("status", newStatus);
                prefs.edit().putString(histKey, arr.toString()).apply();
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private boolean attachServerTimestampToNewestPending(long serverTs) {
        if (serverTs <= 0) return false;
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject m = arr.getJSONObject(i);
                if (!"me".equals(m.optString("from"))) continue;
                if (m.optLong("serverTs", -1) > 0) continue;
                int st = m.optInt("status", ST_PENDING);
                if (st != ST_PENDING && st != ST_SENT) continue;
                m.put("serverTs", serverTs);
                prefs.edit().putString(histKey, arr.toString()).apply();
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private boolean upgradeStatusByServerTs(long serverTs, int newStatus) {
        boolean changed = false;
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject m = arr.getJSONObject(i);
                if (!"me".equals(m.optString("from"))) continue;
                if (m.optLong("serverTs", -1) != serverTs) continue;
                int cur = m.optInt("status", ST_SENT);
                if (newStatus > cur) { m.put("status", newStatus); changed = true; }
            }
            if (changed) prefs.edit().putString(histKey, arr.toString()).apply();
        } catch (Exception ignored) { }
        return changed;
    }

    // --- HTTP helpers / matching ---
    private static String normalizeBase(String hostPort) {
        if ("localhost:5000".equals(hostPort) || "127.0.0.1:5000".equals(hostPort)) hostPort = "10.0.2.2:5000";
        if (!hostPort.startsWith("http://") && !hostPort.startsWith("https://")) hostPort = "http://" + hostPort;
        if (hostPort.endsWith("/")) hostPort = hostPort.substring(0, hostPort.length() - 1);
        return hostPort;
    }
    private static String toWs(String httpBase) {
        if (httpBase.startsWith("https://")) return "wss://" + httpBase.substring("https://".length());
        if (httpBase.startsWith("http://"))  return "ws://"  + httpBase.substring("http://".length());
        return "ws://" + httpBase;
    }
    private static int httpPostJson(String urlStr, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000); c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setDoOutput(true);
        try (OutputStream os = new BufferedOutputStream(c.getOutputStream())) { os.write(json.getBytes("UTF-8")); }
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }
    private static boolean notEmpty(String s) { return s != null && !s.trim().isEmpty(); }
    private static boolean digitsEq(String a, String b) { return digits(a).equals(digits(b)); }
    private static String digits(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') out.append(ch);
        }
        return out.toString();
    }
    private static boolean safeEq(String a, String b) { return a != null && b != null && a.equalsIgnoreCase(b); }
    private boolean isFromPeer(String srcNum, String srcUuid) {
        boolean byNum  = notEmpty(peerNumber) && digitsEq(srcNum, peerNumber);
        boolean byUuid = notEmpty(peerUuid)   && safeEq(srcUuid, peerUuid);
        return byNum || byUuid;
    }
    private boolean isDestThisChat(String destNum, String destUuid) {
        boolean numMatch  = notEmpty(destNum)  && notEmpty(peerNumber) && digitsEq(destNum,  peerNumber);
        boolean uuidMatch = notEmpty(destUuid) && notEmpty(peerUuid)   && safeEq(destUuid,  peerUuid);
        boolean bothEmpty = !notEmpty(destNum) && !notEmpty(destUuid);
        return numMatch || uuidMatch || bothEmpty;
    }
}
