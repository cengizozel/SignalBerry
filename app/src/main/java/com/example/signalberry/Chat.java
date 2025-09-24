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

    // message status (shown in your adapter as 🕒/✓/✓✓)
    static final int ST_PENDING   = 0;
    static final int ST_SENT      = 1;
    static final int ST_DELIVERED = 2;
    static final int ST_READ      = 3;

    private String baseSignal;   // http://IP:5000  (Signal REST)
    private String baseBridge;   // http://IP:9099  (your bridge)
    private String myNumber;
    private String peerNumber;   // preferred if present
    private String peerUuid;     // fallback
    private String peerName;

    // per-chat keys
    private SharedPreferences prefs;
    private String histKey;      // JSON array of messages (text+images)
    private String lastTsKey;    // last seen serverTs for bridge catch-up (bridge only)

    // UI
    private RecyclerView recycler;
    private ChatAdapter chatAdapter;
    private final List<MessageItem> items = new ArrayList<>();

    // Signal WebSocket
    private OkHttpClient wsClient;
    private WebSocket ws;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retrySec = 1;

    // Bridge polling (text-only catch-up)
    private static final long BRIDGE_POLL_MS = 3000L;
    private boolean bridgePolling = false;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.chat);
        setTitle("");

        prefs = getSharedPreferences("signalberry", MODE_PRIVATE);

        String ipPref     = prefs.getString("ip", "");
        String bridgePref = prefs.getString("bridge", "");
        myNumber   = prefs.getString("number", "");
        peerNumber = getIntent().getStringExtra("peer_number");
        peerUuid   = getIntent().getStringExtra("peer_uuid");
        peerName   = getIntent().getStringExtra("peer_name");
        if (peerName == null || peerName.isEmpty())
            peerName = notEmpty(peerNumber) ? peerNumber : notEmpty(peerUuid) ? peerUuid : "Chat";

        if (!notEmpty(ipPref) || !notEmpty(myNumber) || (!notEmpty(peerNumber) && !notEmpty(peerUuid))) {
            Toast.makeText(this, "Missing server IP / my number / peer id", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        baseSignal = normalizeBase(ipPref);
        baseBridge = normalizeBase(bridgePref.isEmpty() ? deriveBridgeBase(ipPref) : bridgePref);

        String chatKey = notEmpty(peerNumber) ? digits(peerNumber) : (peerUuid != null ? peerUuid : "peer");
        histKey   = "chat_hist_"    + chatKey;
        lastTsKey = "chat_last_ts_" + chatKey;

        // Top bar
        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());
        ((android.widget.TextView) findViewById(R.id.title_name)).setText(peerName);

        // RecyclerView
        recycler = findViewById(R.id.chat_list);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recycler.setLayoutManager(lm);
        chatAdapter = new ChatAdapter(items, baseSignal); // pass Signal REST base for attachments
        recycler.setAdapter(chatAdapter);

        // Composer
        EditText input = findViewById(R.id.input_message);
        ImageButton send = findViewById(R.id.btn_send);
        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;

            // optimistic pending bubble
            items.add(new MessageItem("me", text, ST_PENDING));
            chatAdapter.notifyItemInserted(items.size() - 1);
            recycler.scrollToPosition(items.size() - 1);
            saveHistory();

            input.setText("");
            send.setEnabled(false);
            new Thread(new Runnable() {
                @Override public void run() {
                    boolean ok = sendOnce(text);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            send.setEnabled(true);
                            if (ok) {
                                if (markNewestMyPendingTo(ST_SENT)) {
                                    chatAdapter.notifyDataSetChanged();
                                    recycler.scrollToPosition(items.size() - 1);
                                    saveHistory();
                                }
                            } else {
                                Toast.makeText(Chat.this, "Send failed", Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                }
            }).start();
        });

        // Load persisted history
        loadHistory();

        // WebSocket client
        wsClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
    }

    @Override protected void onResume() {
        super.onResume();
        openWebSocket();
        startBridgePolling();
        fetchFromBridgeOnce(); // quick catch-up
    }

    @Override protected void onPause() {
        super.onPause();
        closeWebSocket();
        stopBridgePolling();
    }

    // -------------------- SEND --------------------
    private boolean sendOnce(String text) {
        try {
            String url = baseSignal + "/v2/send";
            JSONObject body = new JSONObject();
            body.put("message", text);
            body.put("number", myNumber);
            JSONArray rcpts = new JSONArray();
            if (notEmpty(peerNumber)) rcpts.put(peerNumber); else rcpts.put(peerUuid);
            body.put("recipients", rcpts);
            int code = httpPostJson(url, body.toString());
            return code >= 200 && code < 300;
        } catch (Exception e) { return false; }
    }

    // -------------------- SIGNAL WS --------------------
    private void openWebSocket() {
        if (ws != null) return;
        try {
            String wsUrl = toWs(baseSignal) + "/v1/receive/" + URLEncoder.encode(myNumber, "UTF-8");
            Request req = new Request.Builder().url(wsUrl).build();
            ws = wsClient.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket webSocket, Response response) { retrySec = 1; }

                @Override public void onMessage(WebSocket webSocket, String text) {
                    handleWsPayload(text);
                }
                @Override public void onMessage(WebSocket webSocket, ByteString bytes) {
                    handleWsPayload(bytes.utf8());
                }

                @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                    ws = null; scheduleReconnect();
                }
                @Override public void onFailure(WebSocket webSocket, Throwable t, Response r) {
                    ws = null; scheduleReconnect();
                }
            });
        } catch (Exception e) {
            scheduleReconnect();
        }
    }
    private void scheduleReconnect() {
        if (isFinishing() || isDestroyed()) return;
        final int delay = Math.min(retrySec, 16);
        retrySec = Math.min(retrySec * 2, 16);
        handler.postDelayed(new Runnable() {
            @Override public void run() { openWebSocket(); }
        }, delay * 1000L);
    }
    private void closeWebSocket() {
        if (ws != null) { try { ws.close(1000, "bye"); } catch (Exception ignored) {} ws = null; }
        retrySec = 1;
    }

    private void handleWsPayload(String payload) {
        boolean changed = false;
        try {
            String p = payload.trim();
            if (p.startsWith("[")) {
                JSONArray arr = new JSONArray(p);
                for (int i = 0; i < arr.length(); i++) {
                    if (handleEnvelope(arr.getJSONObject(i))) changed = true;
                }
            } else {
                if (handleEnvelope(new JSONObject(p))) changed = true;
            }
        } catch (Exception ignored) {}
        if (changed) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    chatAdapter.notifyDataSetChanged();
                    if (!items.isEmpty()) recycler.scrollToPosition(items.size() - 1);
                    saveHistory();
                }
            });
        }
    }

    private boolean handleEnvelope(JSONObject obj) {
        boolean changed = false;
        JSONObject env = obj.optJSONObject("envelope");
        if (env == null) return false;

        String srcNum  = env.optString("sourceNumber", env.optString("source", ""));
        String srcUuid = env.optString("sourceUuid", "");

        // Incoming from this peer: text + images
        if (isFromPeer(srcNum, srcUuid)) {
            JSONObject data = env.optJSONObject("dataMessage");
            if (data != null) {
                long ts = env.optLong("timestamp", 0);

                // text
                String text = data.optString("message", data.optString("text", "")).trim();
                if (!isEmpty(text)) {
                    items.add(new MessageItem("peer", text, ST_DELIVERED));
                    changed = true;
                    bumpLastTs(ts);
                }

                // images
                JSONArray atts = data.optJSONArray("attachments");
                if (atts != null) {
                    for (int i = 0; i < atts.length(); i++) {
                        JSONObject a = atts.optJSONObject(i);
                        if (a == null) continue;
                        String cid  = a.optString("id", "");
                        String mime = a.optString("contentType", "");
                        if (!isEmpty(cid) && mime != null && mime.startsWith("image/")) {
                            items.add(new MessageItem("peer", cid, mime, ST_DELIVERED, true));
                            changed = true;
                            bumpLastTs(ts);
                        }
                    }
                }
            }
        }

        // Sync echo of our sends (sets SENT quickly)
        JSONObject sync = env.optJSONObject("syncMessage");
        if (sync != null) {
            JSONObject sent = sync.optJSONObject("sentMessage");
            if (sent != null) {
                String destNum  = sent.optString("destinationNumber", "");
                String destUuid = sent.optString("destinationUuid", "");
                if (isDestThisChat(destNum, destUuid)) {
                    String msg = sent.optString("message", "").trim();
                    if (!isEmpty(msg)) {
                        // match newest pending by text (best effort)
                        markNewestPendingWithTextTo(msg, ST_SENT);
                        changed = true;
                    }
                    // attachments (images) sent from other devices
                    JSONArray atts = sent.optJSONArray("attachments");
                    if (atts != null) {
                        for (int i = 0; i < atts.length(); i++) {
                            JSONObject a = atts.optJSONObject(i);
                            if (a == null) continue;
                            String cid  = a.optString("id", "");
                            String mime = a.optString("contentType", "");
                            if (!isEmpty(cid) && mime != null && mime.startsWith("image/")) {
                                items.add(new MessageItem("me", cid, mime, ST_SENT, true));
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        // Delivery / Read receipts — we don’t have serverTs on items,
        // so upgrade the newest "me" message with status < target (reasonable heuristic).
        JSONObject receipt = env.optJSONObject("receiptMessage");
        if (receipt != null) {
            String type = receipt.optString("type", "").toUpperCase();
            if ("DELIVERY".equals(type)) {
                if (upgradeNewestMyStatusToAtLeast(ST_DELIVERED)) changed = true;
            } else if ("READ".equals(type) || "VIEWED".equals(type)) {
                if (upgradeNewestMyStatusToAtLeast(ST_READ)) changed = true;
            }
        }
        return changed;
    }

    // -------------------- BRIDGE POLLING --------------------
    private void startBridgePolling() {
        if (bridgePolling) return;
        bridgePolling = true;
        handler.post(bridgePollRun);
    }
    private void stopBridgePolling() {
        bridgePolling = false;
        handler.removeCallbacks(bridgePollRun);
    }
    private final Runnable bridgePollRun = new Runnable() {
        @Override public void run() {
            if (!bridgePolling) return;
            fetchFromBridgeOnce();
            handler.postDelayed(this, BRIDGE_POLL_MS);
        }
    };

    private void fetchFromBridgeOnce() {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String peerKey = notEmpty(peerNumber) ? peerNumber : peerUuid;
                    long after = prefs.getLong(lastTsKey, 0L);
                    String url = baseBridge + "/messages?peer=" +
                            URLEncoder.encode(peerKey, "UTF-8") + "&after=" + after + "&limit=500";
                    String json = httpGet(url);
                    JSONObject root = new JSONObject(json);
                    JSONArray arr = root.optJSONArray("items");
                    if (arr == null) return;

                    boolean changed = false;
                    long maxTs = after;

                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject it = arr.getJSONObject(i);
                        String dir = it.optString("dir", "");
                        String text = it.optString("text", "");
                        long   ts   = it.optLong("serverTs", 0);
                        int    st   = it.optInt("status", ST_SENT);
                        if (ts > maxTs) maxTs = ts;

                        if (isEmpty(text)) continue; // bridge stores text only

                        if ("in".equals(dir)) {
                            // append if not already present (simple contains check)
                            if (!alreadyHasIncomingText(text)) {
                                items.add(new MessageItem("peer", text, Math.max(st, ST_DELIVERED)));
                                changed = true;
                            }
                        } else if ("out".equals(dir)) {
                            // try to find a matching pending/older "me" text to upgrade
                            if (!upgradeExistingMyTextTo(text, st)) {
                                // append (message sent on another device)
                                items.add(new MessageItem("me", text, st));
                                changed = true;
                            }
                        }
                    }

                    if (maxTs > after) prefs.edit().putLong(lastTsKey, maxTs).apply();

                    if (changed) runOnUiThread(new Runnable() {
                        @Override public void run() {
                            chatAdapter.notifyDataSetChanged();
                            if (!items.isEmpty()) recycler.scrollToPosition(items.size() - 1);
                            saveHistory();
                        }
                    });
                } catch (Exception ignored) { }
            }
        }).start();
    }

    // -------------------- helpers: item updates --------------------
    private void bumpLastTs(long ts) {
        if (ts <= 0) return;
        long cur = prefs.getLong(lastTsKey, 0L);
        if (ts > cur) prefs.edit().putLong(lastTsKey, ts).apply();
    }

    private boolean markNewestMyPendingTo(int newStatus) {
        for (int i = items.size() - 1; i >= 0; i--) {
            MessageItem m = items.get(i);
            if (!"me".equals(m.from)) continue;
            if (m.status == ST_PENDING) {
                m.status = newStatus;
                return true;
            }
        }
        return false;
    }

    private void markNewestPendingWithTextTo(String text, int newStatus) {
        if (isEmpty(text)) return;
        for (int i = items.size() - 1; i >= 0; i--) {
            MessageItem m = items.get(i);
            if (!"me".equals(m.from)) continue;
            if (m.status == ST_PENDING && text.equals(m.text)) {
                m.status = newStatus;
                return;
            }
        }
    }

    private boolean upgradeNewestMyStatusToAtLeast(int newStatus) {
        for (int i = items.size() - 1; i >= 0; i--) {
            MessageItem m = items.get(i);
            if (!"me".equals(m.from)) continue;
            if (m.status < newStatus) {
                m.status = newStatus;
                return true;
            }
        }
        return false;
    }

    private boolean alreadyHasIncomingText(String text) {
        if (isEmpty(text)) return true;
        for (int i = items.size() - 1; i >= 0 && i >= items.size() - 50; i--) {
            MessageItem m = items.get(i);
            if (!"peer".equals(m.from)) continue;
            if (m.type == MessageItem.TYPE_TEXT && text.equals(m.text)) return true;
        }
        return false;
    }

    private boolean upgradeExistingMyTextTo(String text, int newStatus) {
        if (isEmpty(text)) return false;
        for (int i = items.size() - 1; i >= 0; i--) {
            MessageItem m = items.get(i);
            if (!"me".equals(m.from)) continue;
            if (m.type == MessageItem.TYPE_TEXT && text.equals(m.text)) {
                if (newStatus > m.status) m.status = newStatus;
                return true;
            }
        }
        return false;
    }

    // -------------------- persistence --------------------
    private void loadHistory() {
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            items.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String from = o.optString("from", "peer");
                int status  = o.optInt("status", ST_DELIVERED);
                String type = o.optString("type", "text");
                if ("image".equals(type)) {
                    String cid  = o.optString("attId", "");
                    String mime = o.optString("mime", "");
                    items.add(new MessageItem(from, cid, mime, status, true));
                } else {
                    String text = o.optString("text", "");
                    items.add(new MessageItem(from, text, status));
                }
            }
            chatAdapter.notifyDataSetChanged();
            if (!items.isEmpty()) recycler.scrollToPosition(items.size() - 1);
        } catch (Exception ignored) { }
    }

    private void saveHistory() {
        try {
            JSONArray arr = new JSONArray();
            for (MessageItem m : items) {
                JSONObject o = new JSONObject();
                o.put("from", m.from);
                o.put("status", m.status);
                if (m.type == MessageItem.TYPE_IMAGE) {
                    o.put("type", "image");
                    o.put("attId", m.attachmentId);
                    o.put("mime",  m.mime == null ? "" : m.mime);
                } else {
                    o.put("type", "text");
                    o.put("text", m.text == null ? "" : m.text);
                }
                arr.put(o);
            }
            prefs.edit().putString(histKey, arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    // -------------------- utils --------------------
    private static String normalizeBase(String base) {
        String b = base == null ? "" : base.trim();
        if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;
        if (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
    private static String deriveBridgeBase(String ipOrBase) {
        String base = ipOrBase.trim();
        if (base.startsWith("http://"))  base = base.substring(7);
        else if (base.startsWith("https://")) base = base.substring(8);
        int c = base.indexOf(':');
        String host = (c > 0) ? base.substring(0, c) : base;
        return "http://" + host + ":9099";
    }
    private static String toWs(String httpBase) {
        if (httpBase.startsWith("https://")) return "wss://" + httpBase.substring(8);
        if (httpBase.startsWith("http://"))  return "ws://"  + httpBase.substring(7);
        return "ws://" + httpBase;
    }
    private static String httpGet(String urlStr) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                (code >= 400 ? c.getErrorStream() : c.getInputStream())))) {
            StringBuilder sb = new StringBuilder();
            String line; while ((line = br.readLine()) != null) sb.append(line);
            String out = sb.toString();
            return out.isEmpty() ? "{}" : out;
        } finally { c.disconnect(); }
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
    private static boolean isEmpty(String s) { return s == null || s.trim().isEmpty(); }
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
        boolean byNum  = notEmpty(peerNumber) && digits(srcNum).equals(digits(peerNumber));
        boolean byUuid = notEmpty(peerUuid)   && safeEq(srcUuid, peerUuid);
        return byNum || byUuid;
    }
    private boolean isDestThisChat(String destNum, String destUuid) {
        boolean numMatch  = notEmpty(destNum)  && notEmpty(peerNumber) && digits(destNum).equals(digits(peerNumber));
        boolean uuidMatch = notEmpty(destUuid) && notEmpty(peerUuid)   && safeEq(destUuid,  peerUuid);
        boolean bothEmpty = !notEmpty(destNum) && !notEmpty(destUuid);
        return numMatch || uuidMatch || bothEmpty;
    }
}
