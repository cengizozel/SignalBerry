package com.example.signalberry;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.example.signalberry.Utils.*;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class Chat extends AppCompatActivity {

    static final int ST_PENDING   = 0;
    static final int ST_SENT      = 1;
    static final int ST_DELIVERED = 2;
    static final int ST_READ      = 3;

    private String baseSignal;
    private String baseBridge;
    private String myNumber;
    private String peerNumber;
    private String peerUuid;
    private String peerName;

    private SharedPreferences prefs;
    private String chatDbKey;   // DB peer_key = digits(peerNumber) or peerUuid
    private String lastTsKey;   // kept only for read_ts tracking (onResume)

    // Reply state
    private MessageItem replyToItem = null;
    private long        replyToTs   = 0;

    private RecyclerView recycler;
    private ChatAdapter chatAdapter;
    private final List<MessageItem> rawItems     = new ArrayList<>(); // business logic, no headers
    private final List<MessageItem> displayItems = new ArrayList<>(); // adapter list, with date headers

    private MessageDatabase msgDb;

    private OkHttpClient wsClient;
    private WebSocket ws;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retrySec = 1;

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

        chatDbKey = notEmpty(peerNumber) ? digits(peerNumber) : (peerUuid != null ? peerUuid : "peer");
        lastTsKey = "chat_last_ts_" + chatDbKey;

        msgDb = new MessageDatabase(this);

        // Top bar
        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());
        ((android.widget.TextView) findViewById(R.id.title_name)).setText(peerName);

        // RecyclerView
        recycler = findViewById(R.id.chat_list);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recycler.setLayoutManager(lm);
        chatAdapter = new ChatAdapter(displayItems, baseSignal, this);
        chatAdapter.setOnImageClickListener(pos -> {
            ArrayList<String> sources = new ArrayList<>();
            int viewerPos = 0;
            int imgIndex = 0;
            for (int i = 0; i < displayItems.size(); i++) {
                MessageItem m = displayItems.get(i);
                if (m.type == MessageItem.TYPE_IMAGE) {
                    String src = m.localUri != null ? m.localUri
                            : (m.attachmentId != null ? baseSignal + "/v1/attachments/" + m.attachmentId : null);
                    if (src != null) {
                        if (i == pos) viewerPos = imgIndex;
                        sources.add(src);
                        imgIndex++;
                    }
                }
            }
            if (sources.isEmpty()) return;
            android.content.Intent intent = new android.content.Intent(Chat.this, ImageViewerActivity.class);
            intent.putStringArrayListExtra(ImageViewerActivity.EXTRA_SOURCES, sources);
            intent.putExtra(ImageViewerActivity.EXTRA_POSITION, viewerPos);
            startActivity(intent);
        });
        recycler.setAdapter(chatAdapter);

        // Reply bar
        android.widget.LinearLayout replyPreview = findViewById(R.id.reply_preview);
        android.widget.TextView tvReplyPreview   = findViewById(R.id.tv_reply_preview);
        android.widget.ImageButton btnCancelReply = findViewById(R.id.btn_cancel_reply);
        chatAdapter.setOnReplyListener(pos -> {
            if (pos < 0 || pos >= displayItems.size()) return;
            MessageItem tapped = displayItems.get(pos);
            if (tapped.type == MessageItem.TYPE_DATE_HEADER) return;
            replyToItem = tapped;
            replyToTs   = replyToItem.serverTs;
            String preview = replyToItem.type == MessageItem.TYPE_IMAGE
                    ? "📷 Photo" : (replyToItem.text != null ? replyToItem.text : "");
            String label = "me".equals(replyToItem.from) ? "You: " + preview : preview;
            tvReplyPreview.setText(label);
            replyPreview.setVisibility(android.view.View.VISIBLE);
        });
        btnCancelReply.setOnClickListener(v -> {
            replyToItem = null;
            replyToTs   = 0;
            replyPreview.setVisibility(android.view.View.GONE);
        });

        // Composer
        EditText input    = findViewById(R.id.input_message);
        ImageButton attach = findViewById(R.id.btn_attach);
        ImageButton send   = findViewById(R.id.btn_send);

        attach.setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.setType("*/*");
            pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(Intent.createChooser(pick, "Select photo or video"), 101);
        });

        send.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;

            long now = System.currentTimeMillis();
            pushThreadHint(text, now);

            final MessageItem replyItem = replyToItem;
            final long        replyTs   = replyToTs;
            replyToItem = null;
            replyToTs   = 0;
            replyPreview.setVisibility(android.view.View.GONE);

            MessageItem pending = new MessageItem("me", text, ST_PENDING);
            pending.serverTs = now;
            if (replyItem != null) {
                pending.quoteText   = replyItem.type == MessageItem.TYPE_IMAGE ? "📷 Photo"
                        : (replyItem.text != null ? replyItem.text : "");
                pending.quoteAuthor = replyItem.from;
            }
            rawItems.add(pending);
            rebuildDisplay();

            input.setText("");
            send.setEnabled(false);
            new Thread(() -> {
                boolean ok = sendOnce(text, replyItem, replyTs);
                runOnUiThread(() -> {
                    send.setEnabled(true);
                    if (ok) {
                        markNewestMyPendingTo(ST_SENT);
                        rebuildDisplay();
                    } else {
                        Toast.makeText(Chat.this, "Send failed", Toast.LENGTH_SHORT).show();
                    }
                });
            }).start();
        });

        loadHistory();

        wsClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(25, TimeUnit.SECONDS)
                .build();
    }

    @Override protected void onResume() {
        super.onResume();
        openWebSocket();
        startBridgePolling();
        fetchFromBridgeOnce();
        String chatKey = chatDbKey;
        prefs.edit()
                .putLong("read_ts_" + chatKey, System.currentTimeMillis())
                .putString("last_read_peer", chatKey)
                .putString("open_chat_peer", chatKey)
                .apply();
    }

    @Override protected void onPause() {
        super.onPause();
        closeWebSocket();
        stopBridgePolling();
        prefs.edit().remove("open_chat_peer").apply();
    }

    // -------------------- ATTACHMENT PICK --------------------
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 101 || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;

        EditText input = findViewById(R.id.input_message);
        final String caption = input.getText().toString().trim();
        input.setText("");

        new Thread(() -> {
            try {
                String mimeRaw = getContentResolver().getType(uri);
                final String mime = (mimeRaw != null) ? mimeRaw : "application/octet-stream";

                byte[] bytes;
                try (InputStream is = getContentResolver().openInputStream(uri);
                     ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    bytes = bos.toByteArray();
                }

                String b64 = "data:" + mime + ";base64," + Base64.encodeToString(bytes, Base64.NO_WRAP);
                boolean ok = sendAttachment(b64, caption);

                final String uriStr = uri.toString();
                final long   now    = System.currentTimeMillis();
                runOnUiThread(() -> {
                    if (ok) {
                        String cap = caption.isEmpty() ? null : caption;
                        MessageItem img = new MessageItem("me", uriStr, cap, ST_SENT, true);
                        img.serverTs = now;
                        rawItems.add(img);
                        rebuildDisplay();
                        // write image to DB immediately (bridge doesn't store images)
                        msgDb.upsert(chatDbKey, "out", "image",
                                null, null, mime, cap, uriStr, now, ST_SENT, null, null);
                    } else {
                        Toast.makeText(this, "Failed to send attachment", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private boolean sendAttachment(String base64Data, String caption) {
        try {
            String url = baseSignal + "/v2/send";
            JSONObject body = new JSONObject();
            body.put("message", caption == null ? "" : caption);
            body.put("number", myNumber);
            JSONArray rcpts = new JSONArray();
            if (notEmpty(peerNumber)) rcpts.put(peerNumber); else rcpts.put(peerUuid);
            body.put("recipients", rcpts);
            JSONArray atts = new JSONArray();
            atts.put(base64Data);
            body.put("base64_attachments", atts);
            int code = httpPostJson(url, body.toString());
            return code >= 200 && code < 300;
        } catch (Exception e) { return false; }
    }

    // -------------------- SEND --------------------
    private boolean sendOnce(String text, MessageItem replyTo, long replyTs) {
        try {
            String url = baseSignal + "/v2/send";
            JSONObject body = new JSONObject();
            body.put("message", text);
            body.put("number", myNumber);
            JSONArray rcpts = new JSONArray();
            if (notEmpty(peerNumber)) rcpts.put(peerNumber); else rcpts.put(peerUuid);
            body.put("recipients", rcpts);
            if (replyTo != null && replyTs > 0) {
                body.put("quote_timestamp", replyTs);
                String qAuthor = "me".equals(replyTo.from) ? myNumber
                        : (notEmpty(peerNumber) ? peerNumber : peerUuid);
                body.put("quote_author", qAuthor == null ? "" : qAuthor);
                String qText = replyTo.type == MessageItem.TYPE_IMAGE ? "📷 Photo"
                        : (replyTo.text != null ? replyTo.text : "");
                body.put("quote_message", qText);
            }
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
                @Override public void onOpen(WebSocket s, Response r) { retrySec = 1; }
                @Override public void onMessage(WebSocket s, String text) { handleWsPayload(text); }
                @Override public void onMessage(WebSocket s, ByteString b)  { handleWsPayload(b.utf8()); }
                @Override public void onClosed(WebSocket s, int c, String r) { ws = null; scheduleReconnect(); }
                @Override public void onFailure(WebSocket s, Throwable t, Response r) { ws = null; scheduleReconnect(); }
            });
        } catch (Exception e) { scheduleReconnect(); }
    }
    private void scheduleReconnect() {
        if (isFinishing() || isDestroyed()) return;
        final int d = Math.min(retrySec, 16);
        retrySec = Math.min(retrySec * 2, 16);
        handler.postDelayed(this::openWebSocket, d * 1000L);
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
                for (int i = 0; i < arr.length(); i++)
                    if (handleEnvelope(arr.getJSONObject(i))) changed = true;
            } else {
                if (handleEnvelope(new JSONObject(p))) changed = true;
            }
        } catch (Exception ignored) {}
        if (changed) runOnUiThread(this::rebuildDisplay);
    }

    private boolean handleEnvelope(JSONObject obj) {
        boolean changed = false;
        JSONObject env = obj.optJSONObject("envelope");
        if (env == null) return false;

        String srcNum  = env.optString("sourceNumber", env.optString("source", ""));
        String srcUuid = env.optString("sourceUuid", "");

        if (isFromPeer(srcNum, srcUuid)) {
            JSONObject data = env.optJSONObject("dataMessage");
            if (data != null) {
                long ts = env.optLong("timestamp", 0);

                JSONObject rd = data.optJSONObject("remoteDelete");
                if (rd != null) {
                    long targetTs = rd.optLong("timestamp", 0);
                    if (targetTs > 0) deleteMessage(targetTs);
                    return true;
                }

                String text = data.optString("message", data.optString("text", "")).trim();
                JSONArray atts = data.optJSONArray("attachments");
                boolean hasImage = atts != null && atts.length() > 0;

                String quoteText = null, quoteAuthor = null;
                JSONObject quote = data.optJSONObject("quote");
                if (quote != null) {
                    quoteText = quote.optString("text", "");
                    String qNum = quote.optString("authorNumber", quote.optString("author", ""));
                    boolean qFromMe = notEmpty(myNumber) && digits(qNum).equals(digits(myNumber));
                    quoteAuthor = qFromMe ? "me" : "peer";
                    if (quoteText.isEmpty()) quoteText = "📷 Photo";
                }

                if (!isEmpty(text) && !hasImage) {
                    MessageItem item = new MessageItem("peer", text, ST_DELIVERED);
                    item.serverTs    = ts;
                    item.quoteText   = quoteText;
                    item.quoteAuthor = quoteAuthor;
                    rawItems.add(item);
                    msgDb.upsert(chatDbKey, "in", "text", text, null, null, null, null,
                            ts, ST_DELIVERED, quoteText, quoteAuthor);
                    changed = true;
                    bumpLastTs(ts);
                }

                if (hasImage) {
                    for (int i = 0; i < atts.length(); i++) {
                        JSONObject a = atts.optJSONObject(i);
                        if (a == null) continue;
                        String cid  = a.optString("id", "");
                        String mime = a.optString("contentType", "");
                        if (!isEmpty(cid) && mime != null && mime.startsWith("image/")) {
                            MessageItem item = new MessageItem("peer", cid, mime,
                                    isEmpty(text) ? null : text, ST_DELIVERED);
                            item.serverTs    = ts;
                            item.quoteText   = quoteText;
                            item.quoteAuthor = quoteAuthor;
                            rawItems.add(item);
                            msgDb.upsert(chatDbKey, "in", "image", null, cid, mime,
                                    isEmpty(text) ? null : text, null,
                                    ts, ST_DELIVERED, quoteText, quoteAuthor);
                            changed = true;
                            bumpLastTs(ts);
                        }
                    }
                }
            }
        }

        JSONObject sync = env.optJSONObject("syncMessage");
        if (sync != null) {
            JSONObject sent = sync.optJSONObject("sentMessage");
            if (sent != null) {
                String destNum  = sent.optString("destinationNumber", "");
                String destUuid = sent.optString("destinationUuid", "");
                if (isDestThisChat(destNum, destUuid)) {
                    JSONObject rdSync = sent.optJSONObject("remoteDelete");
                    if (rdSync != null) {
                        long targetTs = rdSync.optLong("timestamp", 0);
                        if (targetTs > 0) deleteMessage(targetTs);
                        return true;
                    }

                    String msg = sent.optString("message", "").trim();
                    long   ts  = sent.optLong("timestamp", 0);
                    if (!isEmpty(msg)) {
                        markNewestPendingWithTextTo(msg, ST_SENT);
                        // write to DB with real Signal ts
                        msgDb.upsert(chatDbKey, "out", "text", msg, null, null, null, null,
                                ts, ST_SENT, null, null);
                        changed = true;
                    }
                    JSONArray atts = sent.optJSONArray("attachments");
                    if (atts != null) {
                        for (int i = 0; i < atts.length(); i++) {
                            JSONObject a = atts.optJSONObject(i);
                            if (a == null) continue;
                            String cid  = a.optString("id", "");
                            String mime = a.optString("contentType", "");
                            if (!isEmpty(cid) && mime != null && mime.startsWith("image/")) {
                                MessageItem item = new MessageItem("me", cid, mime, null, ST_SENT);
                                item.serverTs = ts;
                                rawItems.add(item);
                                msgDb.upsert(chatDbKey, "out", "image", null, cid, mime,
                                        null, null, ts, ST_SENT, null, null);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        JSONObject receipt = env.optJSONObject("receiptMessage");
        if (receipt != null) {
            String type = receipt.optString("type", "").toUpperCase();
            if ("DELIVERY".equals(type)) {
                if (upgradeNewestMyStatusToAtLeast(ST_DELIVERED)) {
                    msgDb.upgradeAllOutStatus(chatDbKey, ST_DELIVERED);
                    changed = true;
                }
            } else if ("READ".equals(type) || "VIEWED".equals(type)) {
                if (upgradeNewestMyStatusToAtLeast(ST_READ)) {
                    msgDb.upgradeAllOutStatus(chatDbKey, ST_READ);
                    changed = true;
                }
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
        new Thread(() -> {
            try {
                String peer  = notEmpty(peerNumber) ? peerNumber : peerUuid;
                long   after = msgDb.getLastTs(chatDbKey);
                String url   = baseBridge + "/messages?peer=" +
                        URLEncoder.encode(peer, "UTF-8") + "&after=" + after + "&limit=500";
                String json  = httpGet(url);
                JSONObject root = new JSONObject(json);
                JSONArray arr   = root.optJSONArray("items");
                if (arr == null) return;

                boolean anyNew = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject it  = arr.getJSONObject(i);
                    String dir     = it.optString("dir", "");
                    String text    = it.optString("text", "");
                    long   ts      = it.optLong("serverTs", 0);
                    int    st      = it.optInt("status", ST_SENT);

                    if (isEmpty(text) || isEmpty(dir)) continue;

                    int finalSt = "in".equals(dir) ? Math.max(st, ST_DELIVERED) : st;
                    long id = msgDb.upsert(chatDbKey, dir, "text", text, null, null, null, null,
                            ts, finalSt, null, null);
                    if (id > 0) anyNew = true;
                    else if (ts > 0) backfillServerTs("in".equals(dir) ? "peer" : "me", text, ts);
                }

                if (anyNew) {
                    // reload items from DB so memory and DB are in sync
                    List<MessageItem> fresh = msgDb.getMessages(chatDbKey);
                    runOnUiThread(() -> {
                        // preserve in-memory PENDING items (not yet in DB)
                        List<MessageItem> pending = new ArrayList<>();
                        for (MessageItem m : rawItems)
                            if (m.status == ST_PENDING) pending.add(m);

                        rawItems.clear();
                        rawItems.addAll(fresh);
                        rawItems.addAll(pending);
                        rebuildDisplay();
                    });
                }
            } catch (Exception ignored) {}
        }).start();
    }

    // -------------------- display rebuild (inserts date headers) --------------------
    private void rebuildDisplay() {
        displayItems.clear();
        String lastDayKey = null;
        for (MessageItem m : rawItems) {
            long ts = m.serverTs > 0 ? m.serverTs : System.currentTimeMillis();
            String dk = dayKey(ts);
            if (!dk.equals(lastDayKey)) {
                displayItems.add(new MessageItem(dateLabel(ts), true));
                lastDayKey = dk;
            }
            displayItems.add(m);
        }
        chatAdapter.notifyDataSetChanged();
        if (!displayItems.isEmpty()) recycler.scrollToPosition(displayItems.size() - 1);
    }

    private static String dayKey(long ts) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ts);
        return c.get(java.util.Calendar.YEAR) + "-" + c.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private static String dateLabel(long ts) {
        java.util.Calendar msg  = java.util.Calendar.getInstance();
        java.util.Calendar now  = java.util.Calendar.getInstance();
        java.util.Calendar yday = java.util.Calendar.getInstance();
        msg.setTimeInMillis(ts);
        yday.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(msg, now))  return "Today";
        if (sameDay(msg, yday)) return "Yesterday";
        java.util.Calendar week = java.util.Calendar.getInstance();
        week.add(java.util.Calendar.DAY_OF_YEAR, -6);
        if (msg.after(week))
            return new java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(new java.util.Date(ts));
        if (msg.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR))
            return new java.text.SimpleDateFormat("MMMM d", java.util.Locale.getDefault()).format(new java.util.Date(ts));
        return new java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault()).format(new java.util.Date(ts));
    }

    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    // -------------------- helpers --------------------
    private void deleteMessage(long targetTs) {
        msgDb.deleteByServerTs(chatDbKey, targetTs);
        for (int i = rawItems.size() - 1; i >= 0; i--) {
            if (rawItems.get(i).serverTs == targetTs) {
                rawItems.remove(i);
                break;
            }
        }
        runOnUiThread(this::rebuildDisplay);
    }

    private void bumpLastTs(long ts) {
        // no-op: DB getLastTs() is now the source of truth
    }

    private boolean markNewestMyPendingTo(int newStatus) {
        for (int i = rawItems.size() - 1; i >= 0; i--) {
            MessageItem m = rawItems.get(i);
            if ("me".equals(m.from) && m.status == ST_PENDING) {
                m.status = newStatus;
                return true;
            }
        }
        return false;
    }

    private void markNewestPendingWithTextTo(String text, int newStatus) {
        if (isEmpty(text)) return;
        for (int i = rawItems.size() - 1; i >= 0; i--) {
            MessageItem m = rawItems.get(i);
            if ("me".equals(m.from) && m.status == ST_PENDING && text.equals(m.text)) {
                m.status = newStatus;
                return;
            }
        }
    }

    private boolean upgradeNewestMyStatusToAtLeast(int newStatus) {
        for (int i = rawItems.size() - 1; i >= 0; i--) {
            MessageItem m = rawItems.get(i);
            if ("me".equals(m.from) && m.status < newStatus) {
                m.status = newStatus;
                return true;
            }
        }
        return false;
    }

    private void backfillServerTs(String from, String text, long ts) {
        if (isEmpty(text) || ts <= 0) return;
        int limit = "peer".equals(from) ? Math.max(0, rawItems.size() - 50) : 0;
        for (int i = rawItems.size() - 1; i >= limit; i--) {
            MessageItem m = rawItems.get(i);
            if (from.equals(m.from) && m.type == MessageItem.TYPE_TEXT
                    && text.equals(m.text) && m.serverTs == 0) {
                m.serverTs = ts;
                return;
            }
        }
    }

    // -------------------- persistence --------------------
    private void loadHistory() {
        // Load from DB
        List<MessageItem> stored = msgDb.getMessages(chatDbKey);
        if (!stored.isEmpty()) {
            rawItems.clear();
            rawItems.addAll(stored);
            rebuildDisplay();
            return;
        }

        // DB empty — migrate from old SharedPreferences JSON
        String histKey = "chat_hist_" + chatDbKey;
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            if (arr.length() == 0) return;
            rawItems.clear();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o   = arr.getJSONObject(i);
                String from    = o.optString("from", "peer");
                int status     = o.optInt("status", ST_DELIVERED);
                String type    = o.optString("type", "text");
                long   sTs     = o.optLong("serverTs", 0);
                String qt      = o.optString("quoteText", "");
                String qa      = o.optString("quoteAuthor", "peer");
                String dir     = "me".equals(from) ? "out" : "in";
                MessageItem item;
                if ("image".equals(type)) {
                    String cid     = o.optString("attId", "");
                    String mime    = o.optString("mime", "");
                    String caption = o.optString("caption", "");
                    String locUri  = o.optString("localUri", "");
                    if (!locUri.isEmpty()) {
                        item = new MessageItem(from, locUri, caption.isEmpty() ? null : caption, status, true);
                        msgDb.upsert(chatDbKey, dir, "image", null, null, mime,
                                caption.isEmpty() ? null : caption, locUri,
                                sTs, status, qt.isEmpty() ? null : qt, qa.isEmpty() ? null : qa);
                    } else {
                        item = new MessageItem(from, cid, mime, caption.isEmpty() ? null : caption, status);
                        msgDb.upsert(chatDbKey, dir, "image", null, cid, mime,
                                caption.isEmpty() ? null : caption, null,
                                sTs, status, qt.isEmpty() ? null : qt, qa.isEmpty() ? null : qa);
                    }
                } else {
                    String text = o.optString("text", "");
                    item = new MessageItem(from, text, status);
                    msgDb.upsert(chatDbKey, dir, "text", text, null, null, null, null,
                            sTs, status, qt.isEmpty() ? null : qt, qa.isEmpty() ? null : qa);
                }
                item.serverTs    = sTs;
                item.quoteText   = qt.isEmpty() ? null : qt;
                item.quoteAuthor = qa.isEmpty() ? null : qa;
                rawItems.add(item);
            }
            rebuildDisplay();
            // clean up old pref after migration
            prefs.edit().remove(histKey).apply();
        } catch (Exception ignored) {}
    }

    private boolean isFromPeer(String srcNum, String srcUuid) {
        boolean byNum  = notEmpty(peerNumber) && digits(srcNum).equals(digits(peerNumber));
        boolean byUuid = notEmpty(peerUuid)   && safeEq(srcUuid, peerUuid);
        return byNum || byUuid;
    }
    private boolean isDestThisChat(String destNum, String destUuid) {
        boolean numMatch  = notEmpty(destNum)  && notEmpty(peerNumber) && digits(destNum).equals(digits(peerNumber));
        boolean uuidMatch = notEmpty(destUuid) && notEmpty(peerUuid)   && safeEq(destUuid, peerUuid);
        boolean bothEmpty = !notEmpty(destNum) && !notEmpty(destUuid);
        return numMatch || uuidMatch || bothEmpty;
    }
    private void pushThreadHint(String text, long ts) {
        try {
            JSONObject h = new JSONObject();
            h.put("peer_number", peerNumber == null ? "" : peerNumber);
            h.put("peer_uuid",   peerUuid   == null ? "" : peerUuid);
            h.put("peer_name",   peerName   == null ? "" : peerName);
            h.put("text",        text == null ? "" : text);
            h.put("ts",          ts);
            prefs.edit().putString("thread_hint", h.toString()).apply();
        } catch (Exception ignored) {}
    }
}
