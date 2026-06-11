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

import static com.example.signalberry.Utils.*;

/**
 * Chat screen. Pure Repo observer (REDESIGN §3.1): no WebSocket, no polling,
 * no envelope parsing here — MessageService owns the socket, Repo owns the DB,
 * this activity renders Repo.getThread() and reacts to listener events.
 */
public class Chat extends AppCompatActivity {

    static final int ST_REMOTE_DELETED = MessageDatabase.ST_REMOTE_DELETED;
    static final int ST_FAILED    = MessageDatabase.ST_FAILED;
    static final int ST_PENDING   = MessageDatabase.ST_PENDING;
    static final int ST_SENT      = MessageDatabase.ST_SENT;
    static final int ST_DELIVERED = MessageDatabase.ST_DELIVERED;
    static final int ST_READ      = MessageDatabase.ST_READ;

    private String baseSignal;
    private String myNumber;
    private String peerNumber;
    private String peerUuid;
    private String peerName;

    private SharedPreferences prefs;
    private Repo repo;
    private boolean demoMode;
    private int demoIndex = -1;
    private String chatDbKey;

    // Reply state
    private MessageItem replyToItem = null;
    private long        replyToTs   = 0;
    private android.widget.LinearLayout replyPreviewBar;
    private android.widget.TextView tvReplyPreviewText;

    // Selection state
    private final java.util.Set<Long> selectedTs = new java.util.HashSet<>();
    private boolean selectionMode = false;
    private android.widget.LinearLayout selectionBar;
    private android.widget.TextView tvSelectionCount;

    private RecyclerView recycler;
    private ChatAdapter chatAdapter;
    private final List<MessageItem> rawItems     = new ArrayList<>(); // business logic, no headers
    private final List<MessageItem> displayItems = new ArrayList<>(); // adapter list, with date headers

    private android.widget.TextView debugLogView;
    private android.widget.ScrollView debugScrollView;
    private android.widget.LinearLayout debugPanel;
    private final DebugLog.Listener debugListener = line -> {
        if (debugLogView == null) return;
        debugLogView.append(line + "\n");
        debugScrollView.post(() -> debugScrollView.fullScroll(android.view.View.FOCUS_DOWN));
    };

    // Typing indicator
    private android.widget.TextView tvTyping;
    private boolean typingSent = false;
    private static final long TYPING_DEBOUNCE_MS = 5000L;
    private static final long TYPING_HIDE_MS     = 6000L;
    private final Runnable typingStopRun = () -> {
        typingSent = false;
        new Thread(this::sendTypingStop).start();
    };
    private final Runnable typingHideRun = () ->
            runOnUiThread(() -> { if (tvTyping != null) tvTyping.setVisibility(android.view.View.GONE); });

    private final Handler handler = new Handler(Looper.getMainLooper());

    // Scroll state: only auto-scroll when the user is already at the bottom
    private boolean atBottom = true;
    private long lastSeenTs = 0;      // max serverTs rendered while at bottom
    private long openReadTs = 0;      // watermark when the chat was opened
    private boolean firstLoad = true;
    private android.widget.FrameLayout btnJumpBottom;
    private android.widget.TextView tvJumpUnread;

    // In-chat search
    private android.widget.LinearLayout chatSearchBar;
    private EditText chatSearchInput;
    private android.widget.TextView chatSearchCount;
    private final List<Integer> searchMatches = new ArrayList<>(); // displayItems positions
    private int searchIndex = -1;

    // Coalesced thread reload: listener events can burst during catch-up
    private boolean reloadQueued = false;
    private final Runnable reloadRun = new Runnable() {
        @Override public void run() {
            reloadQueued = false;
            new Thread(() -> {
                final List<MessageItem> fresh = repo.getThread(chatDbKey);
                runOnUiThread(() -> {
                    rawItems.clear();
                    rawItems.addAll(fresh);
                    rebuildDisplay();
                    advanceReadWatermark();
                    repo.queueReadReceipts(chatDbKey,
                            notEmpty(peerNumber) ? peerNumber : peerUuid, rawItems);
                });
            }).start();
        }
    };

    private final Repo.Listener repoListener = new Repo.Listener() {
        @Override public void onItemInserted(String peerKey) {
            if (chatDbKey.equals(peerKey)) scheduleReload();
        }
        @Override public void onItemChanged(String peerKey, long serverTs) {
            if (chatDbKey.equals(peerKey)) scheduleReload();
        }
        @Override public void onEphemeral(String peerKey, String kind) {
            if (!chatDbKey.equals(peerKey) || tvTyping == null) return;
            handler.removeCallbacks(typingHideRun);
            if ("typing_started".equals(kind)) {
                tvTyping.setText(peerName + " is typing…");
                tvTyping.setVisibility(android.view.View.VISIBLE);
                handler.postDelayed(typingHideRun, TYPING_HIDE_MS);
            } else {
                tvTyping.setVisibility(android.view.View.GONE);
            }
        }
    };

    private void scheduleReload() {
        if (reloadQueued) return;
        reloadQueued = true;
        handler.postDelayed(reloadRun, 150);
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.chat);
        setTitle("");

        prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        repo  = Repo.get(this);

        String ipPref = prefs.getString("ip", "");
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
        chatDbKey = PeerKeys.get(this).resolve(peerNumber, peerUuid);
        openReadTs = prefs.getLong("read_ts_" + chatDbKey, 0);

        // Debug log
        debugLogView    = findViewById(R.id.debug_log);
        debugScrollView = findViewById(R.id.debug_scroll);
        debugPanel      = findViewById(R.id.debug_panel);
        DebugLog.register(debugListener);
        findViewById(R.id.btn_copy_log).setOnClickListener(v -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", DebugLog.getAll()));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.btn_clear_log).setOnClickListener(v -> {
            DebugLog.clear();
            debugLogView.setText("");
        });

        // Typing indicator
        tvTyping = findViewById(R.id.tv_typing);

        // Top bar
        ImageButton back = findViewById(R.id.btn_back);
        back.setOnClickListener(v -> finish());
        ((android.widget.TextView) findViewById(R.id.title_name)).setText(peerName);

        // RecyclerView
        recycler = findViewById(R.id.chat_list);
        final LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        recycler.setLayoutManager(lm);
        demoMode = prefs.getBoolean("demo_mode", false);
        if (demoMode) {
            demoIndex = getIntent().getIntExtra("demo_index", 0);
            peerName = DemoData.NAMES[demoIndex % DemoData.NAMES.length];
            ((android.widget.TextView) findViewById(R.id.title_name)).setText(peerName);
        }
        chatAdapter = new ChatAdapter(displayItems, baseSignal, this);
        chatAdapter.setOnImageClickListener(pos -> {
            if (pos < 0 || pos >= displayItems.size()) return;
            MessageItem tapped = displayItems.get(pos);
            if ("video".equals(tapped.msgType)) { openVideo(tapped); return; }
            if ("audio".equals(tapped.msgType) || "file".equals(tapped.msgType)) {
                openExternally(tapped);
                return;
            }
            ArrayList<String> sources = new ArrayList<>();
            int viewerPos = 0;
            int imgIndex = 0;
            for (int i = 0; i < displayItems.size(); i++) {
                MessageItem m = displayItems.get(i);
                if (m.type == MessageItem.TYPE_IMAGE && "image".equals(m.msgType)) {
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

        btnJumpBottom = findViewById(R.id.btn_jump_bottom);
        tvJumpUnread  = findViewById(R.id.tv_jump_unread);
        btnJumpBottom.setOnClickListener(v -> {
            recycler.scrollToPosition(Math.max(0, displayItems.size() - 1));
            setAtBottom(true);
        });
        recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                int last = lm.findLastVisibleItemPosition();
                setAtBottom(last >= displayItems.size() - 2);
            }
        });

        // In-chat search
        chatSearchBar   = findViewById(R.id.chat_search_bar);
        chatSearchInput = findViewById(R.id.chat_search_input);
        chatSearchCount = findViewById(R.id.chat_search_count);
        findViewById(R.id.btn_chat_search).setOnClickListener(v -> {
            chatSearchBar.setVisibility(android.view.View.VISIBLE);
            chatSearchInput.requestFocus();
        });
        findViewById(R.id.btn_search_close).setOnClickListener(v -> closeSearch());
        findViewById(R.id.btn_search_up).setOnClickListener(v -> stepSearch(-1));
        findViewById(R.id.btn_search_down).setOnClickListener(v -> stepSearch(1));
        chatSearchInput.setOnEditorActionListener((v, actionId, event) -> {
            runSearch(chatSearchInput.getText().toString());
            return true;
        });
        chatSearchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                runSearch(s.toString());
            }
        });

        // Reply bar
        replyPreviewBar   = findViewById(R.id.reply_preview);
        tvReplyPreviewText = findViewById(R.id.tv_reply_preview);
        android.widget.ImageButton btnCancelReply = findViewById(R.id.btn_cancel_reply);
        chatAdapter.setOnLongPressListener(this::showMessageMenu);
        chatAdapter.setOnItemClickListener(pos -> {
            if (pos < 0 || pos >= displayItems.size()) return;
            MessageItem m = displayItems.get(pos);
            if (selectionMode) {
                if (m.type == MessageItem.TYPE_DATE_HEADER || m.serverTs == 0) return;
                if (selectedTs.contains(m.serverTs)) selectedTs.remove(m.serverTs);
                else selectedTs.add(m.serverTs);
                if (selectedTs.isEmpty()) exitSelectionMode();
                else updateSelectionBar();
                chatAdapter.notifyDataSetChanged();
                return;
            }
            if (m.status == ST_FAILED && "me".equals(m.from)) retryFailedSend(m);
        });

        // Selection bar
        selectionBar     = findViewById(R.id.selection_bar);
        tvSelectionCount = findViewById(R.id.tv_selection_count);
        findViewById(R.id.btn_cancel_selection).setOnClickListener(v -> exitSelectionMode());
        findViewById(R.id.btn_delete_selected).setOnClickListener(v -> confirmDeleteSelected());

        btnCancelReply.setOnClickListener(v -> {
            replyToItem = null;
            replyToTs   = 0;
            replyPreviewBar.setVisibility(android.view.View.GONE);
        });

        // Composer
        EditText input    = findViewById(R.id.input_message);
        ImageButton attach = findViewById(R.id.btn_attach);
        ImageButton send   = findViewById(R.id.btn_send);

        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                send.performClick();
                return true;
            }
            return false;
        });
        // Q10 hardware keyboard: Enter sends, Alt/Shift+Enter inserts a newline
        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode != android.view.KeyEvent.KEYCODE_ENTER) return false;
            if (event.isAltPressed() || event.isShiftPressed()) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_DOWN) send.performClick();
            return true; // consume both DOWN and UP
        });

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

            final MessageItem replyItem = replyToItem;
            final long        replyTs   = replyToTs;
            replyToItem = null;
            replyToTs   = 0;
            replyPreviewBar.setVisibility(android.view.View.GONE);
            input.setText("");
            handler.removeCallbacks(typingStopRun);
            if (typingSent) { typingSent = false; new Thread(this::sendTypingStop).start(); }

            sendText(text, replyItem, replyTs);
        });

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                handler.removeCallbacks(typingStopRun);
                if (s.length() > 0) {
                    if (!typingSent) {
                        typingSent = true;
                        new Thread(Chat.this::sendTypingStart).start();
                    }
                    handler.postDelayed(typingStopRun, TYPING_DEBOUNCE_MS);
                } else if (typingSent) {
                    typingSent = false;
                    new Thread(Chat.this::sendTypingStop).start();
                }
            }
        });

        loadHistory();
        input.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean dbgOn = prefs.getBoolean("debug_log", false);
        debugPanel.setVisibility(dbgOn ? android.view.View.VISIBLE : android.view.View.GONE);
        if (dbgOn) {
            debugLogView.setText(DebugLog.getAll());
            debugScrollView.post(() -> debugScrollView.fullScroll(android.view.View.FOCUS_DOWN));
        }
        if (!demoMode) {
            repo.addListener(repoListener);
            // service owns the socket; we just ask for a catch-up on open
            new Thread(repo::catchUp, "chat-catchup").start();
            scheduleReload();
        }
        if (!selectionMode) {
            EditText inputField = findViewById(R.id.input_message);
            inputField.requestFocus();
        }
        prefs.edit()
                .putString("last_read_peer", chatDbKey)
                .putString("open_chat_peer", chatDbKey)
                .apply();
        MessageService.clearNotification(this, chatDbKey);
        advanceReadWatermark();
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(typingStopRun);
        handler.removeCallbacks(typingHideRun);
        handler.removeCallbacks(reloadRun);
        reloadQueued = false;
        if (typingSent) { typingSent = false; new Thread(this::sendTypingStop).start(); }
        if (!demoMode) repo.removeListener(repoListener);
        advanceReadWatermark();
        prefs.edit().remove("open_chat_peer").apply();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        DebugLog.unregister(debugListener);
    }

    /** Read watermark = max server_ts actually SEEN (at bottom) — never the wall
     *  clock, and never burned just by opening the chat with 40 unread. */
    private void advanceReadWatermark() {
        if (!atBottom) return;
        long max = 0;
        for (MessageItem m : rawItems)
            if (m.serverTs > max) max = m.serverTs;
        if (max > 0) {
            repo.advanceReadTs(chatDbKey, max);
            lastSeenTs = max;
        }
    }

    // -------------------- SEND --------------------

    private void sendText(String text, MessageItem replyItem, long replyTs) {
        final String qt = replyItem == null ? null
                : (replyItem.type == MessageItem.TYPE_IMAGE ? "📷 Photo"
                   : (replyItem.text != null ? replyItem.text : ""));
        final String qa = replyItem == null ? null : replyItem.from;

        final long nonce = repo.beginSend(chatDbKey, "text", text, null, null, null,
                replyTs, qt, qa);
        if (nonce <= 0) { Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show(); return; }

        new Thread(() -> {
            String tsRaw = sendOnce(text, replyItem, replyTs);
            if (tsRaw != null) {
                repo.confirmSend(chatDbKey, nonce, tsRaw, "text", text, null, null,
                        replyTs, qt, qa);
            } else {
                repo.failSend(chatDbKey, nonce);
                final String err = lastSendError;
                runOnUiThread(() -> {
                    if (err != null && err.toLowerCase(java.util.Locale.US).contains("untrusted")) {
                        promptTrustIdentity();
                    } else {
                        Toast.makeText(Chat.this, "Send failed — tap the message to retry", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /** The peer's safety number changed (new device/reinstall) — sends fail
     *  until the new identity is trusted. */
    private void promptTrustIdentity() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Safety number changed")
                .setMessage(peerName + "'s safety number has changed (they may have "
                        + "reinstalled Signal or switched devices). Trust the new "
                        + "identity to keep messaging?")
                .setPositiveButton("Trust", (d, w) -> new Thread(() -> {
                    try {
                        String peer = notEmpty(peerNumber) ? peerNumber : peerUuid;
                        JSONObject body = new JSONObject();
                        body.put("trust_all_known_keys", true);
                        int code = httpPutJson(baseSignal + "/v1/identities/"
                                + URLEncoder.encode(myNumber, "UTF-8") + "/trust/"
                                + URLEncoder.encode(peer, "UTF-8"), body.toString());
                        runOnUiThread(() -> Toast.makeText(Chat.this,
                                code >= 200 && code < 300
                                        ? "Identity trusted — tap the failed message to resend"
                                        : "Trust failed (" + code + ")",
                                Toast.LENGTH_LONG).show());
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(Chat.this,
                                "Trust failed", Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void retryFailedSend(MessageItem failed) {
        final long oldKey = failed.serverTs; // -nonce
        final boolean isText = failed.type == MessageItem.TYPE_TEXT;
        if (isText && isEmpty(failed.text)) return;
        if (!isText && isEmpty(failed.localUri)) return; // media gone from store
        new android.app.AlertDialog.Builder(this)
                .setMessage("Resend this message?")
                .setPositiveButton("Resend", (d, w) -> {
                    repo.deleteLocal(chatDbKey, java.util.Collections.singletonList(oldKey));
                    if (isText) {
                        sendText(failed.text, null, 0);
                    } else {
                        resendMedia(failed);
                    }
                })
                .setNegativeButton("Delete", (d, w) ->
                        repo.deleteLocal(chatDbKey, java.util.Collections.singletonList(oldKey)))
                .setNeutralButton("Cancel", null)
                .show();
    }

    /** Re-run the streaming send from the imported copy still in the store. */
    private void resendMedia(MessageItem failed) {
        final String path = failed.localUri;
        final String mime = isEmpty(failed.mime) ? "application/octet-stream" : failed.mime;
        final String kind = failed.msgType;
        final String cap  = failed.caption;
        final String recipient = notEmpty(peerNumber) ? peerNumber : peerUuid;
        final java.io.File f = new java.io.File(path.startsWith("file://") ? path.substring(7) : path);
        if (!f.exists()) { Toast.makeText(this, "Original file no longer available", Toast.LENGTH_SHORT).show(); return; }
        final long nonce = repo.beginSend(chatDbKey, kind, null, mime, cap, path, 0, null, null);
        if (nonce <= 0) return;
        new Thread(() -> {
            try {
                String tsRaw = AttachmentStore.sendStreaming(baseSignal, myNumber, recipient,
                        cap, mime, f, null, null, null);
                if (tsRaw != null) repo.confirmSend(chatDbKey, nonce, tsRaw, kind, cap, null, mime, 0, null, null);
                else repo.failSend(chatDbKey, nonce);
            } catch (Exception e) {
                repo.failSend(chatDbKey, nonce);
            }
        }).start();
    }

    /** POST /v2/send; returns the raw timestamp string from the response
     *  (string or number per M1 — never parsed here), or null on failure. */
    private String sendOnce(String text, MessageItem replyTo, long replyTs) {
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
            return postForTimestamp(url, body.toString(), 8000);
        } catch (Exception e) { return null; }
    }

    /** Last send-failure response body (for untrusted-identity detection). */
    private static volatile String lastSendError = "";

    /** Shared POST helper: returns raw "timestamp" field as string, or null. */
    private static String postForTimestamp(String url, String json, int timeoutMs) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000); c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setDoOutput(true);
            try (java.io.OutputStream os = new java.io.BufferedOutputStream(c.getOutputStream())) {
                os.write(json.getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                try (java.io.InputStream es = c.getErrorStream();
                     java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    if (es != null) {
                        byte[] buf = new byte[1024]; int n;
                        while ((n = es.read(buf)) != -1) bos.write(buf, 0, n);
                    }
                    lastSendError = bos.toString("UTF-8");
                } catch (Exception ignored) { lastSendError = ""; }
                c.disconnect();
                return null;
            }
            lastSendError = "";
            String tsRaw;
            try (java.io.InputStream is = c.getInputStream();
                 java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[1024]; int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                tsRaw = new JSONObject(bos.toString("UTF-8")).optString("timestamp", "");
            }
            c.disconnect();
            return tsRaw.isEmpty() ? null : tsRaw;
        } catch (Exception e) { return null; }
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

        String mimeRaw = getContentResolver().getType(uri);
        final String mime = (mimeRaw != null) ? mimeRaw : "application/octet-stream";
        final String kind = Repo.kindFromMime(mime);
        final String cap  = caption.isEmpty() ? null : caption;
        final String recipient = notEmpty(peerNumber) ? peerNumber : peerUuid;

        new Thread(() -> {
            // 1. copy into the store FIRST — content:// permissions die with the
            //    picker; the old code persisted the transient URI (blank-after-
            //    restart bug). Local key promoted to the real att_id if learned.
            final AttachmentStore store = AttachmentStore.get(this);
            final String localKey = "local-" + System.currentTimeMillis();
            java.io.File stored = store.importLocal(uri, localKey);
            if (stored == null) {
                runOnUiThread(() -> Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show());
                return;
            }
            long cap_bytes = "image".equals(kind)
                    ? AttachmentStore.MAX_IMAGE_BYTES : AttachmentStore.MAX_MEDIA_BYTES;
            if (stored.length() > cap_bytes) {
                final String limit = (cap_bytes / (1024 * 1024)) + " MB";
                //noinspection ResultOfMethodCallIgnored
                stored.delete();
                runOnUiThread(() -> Toast.makeText(this,
                        "File too large (limit " + limit + ")", Toast.LENGTH_LONG).show());
                return;
            }

            final String storedPath = stored.getAbsolutePath();
            final long nonce = repo.beginSend(chatDbKey, kind, null, mime, cap, storedPath, 0, null, null);
            if (nonce <= 0) {
                runOnUiThread(() -> Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show());
                return;
            }

            try {
                // 2. constant-memory streaming upload (REDESIGN §3.5)
                String tsRaw = AttachmentStore.sendStreaming(baseSignal, myNumber, recipient,
                        caption, mime, stored, null, null, null);
                if (tsRaw != null) {
                    repo.confirmSend(chatDbKey, nonce, tsRaw, kind, caption, null, mime, 0, null, null);
                } else {
                    repo.failSend(chatDbKey, nonce);
                    runOnUiThread(() ->
                            Toast.makeText(this, "Failed to send attachment", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                repo.failSend(chatDbKey, nonce);
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // -------------------- MEDIA OPEN --------------------

    /** Tap on a video bubble: play if cached/local, else size-gated download. */
    private void openVideo(MessageItem m) {
        if (m.localUri != null) {
            Intent play = new Intent(this, PlayerActivity.class);
            if (m.localUri.startsWith("/"))
                play.putExtra(PlayerActivity.EXTRA_FILE, m.localUri);
            else
                play.putExtra(PlayerActivity.EXTRA_URI, m.localUri);
            startActivity(play);
            return;
        }
        if (isEmpty(m.attachmentId)) return;
        final String attId = m.attachmentId;
        final AttachmentStore store = AttachmentStore.get(this);
        if (store.has(attId)) {
            Intent play = new Intent(this, PlayerActivity.class);
            play.putExtra(PlayerActivity.EXTRA_FILE, store.fileFor(attId).getAbsolutePath());
            startActivity(play);
            return;
        }
        Toast.makeText(this, "Downloading video…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.io.File f = store.fetch(baseSignal, attId);
            runOnUiThread(() -> {
                if (f == null) {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
                    return;
                }
                scheduleReload(); // thumbnail can render now
                Intent play = new Intent(this, PlayerActivity.class);
                play.putExtra(PlayerActivity.EXTRA_FILE, f.getAbsolutePath());
                startActivity(play);
            });
        }).start();
    }

    /** Audio/files: download to the store, hand off via FileProvider. */
    private void openExternally(MessageItem m) {
        if (isEmpty(m.attachmentId)) return;
        final String attId = m.attachmentId;
        final String mime = isEmpty(m.mime) ? "*/*" : m.mime;
        new Thread(() -> {
            java.io.File f = AttachmentStore.get(this).fetch(baseSignal, attId);
            runOnUiThread(() -> {
                if (f == null) {
                    Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".files", f);
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(uri, mime);
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(view);
                } catch (Exception e) {
                    Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    // -------------------- TYPING INDICATORS --------------------
    private void sendTypingStart() {
        try {
            String peer = notEmpty(peerNumber) ? peerNumber : peerUuid;
            if (peer == null) return;
            JSONObject body = new JSONObject();
            body.put("recipient", peer);
            httpPutJson(baseSignal + "/v1/typing-indicator/" + URLEncoder.encode(myNumber, "UTF-8"), body.toString());
        } catch (Exception ignored) {}
    }

    private void sendTypingStop() {
        try {
            String peer = notEmpty(peerNumber) ? peerNumber : peerUuid;
            if (peer == null) return;
            JSONObject body = new JSONObject();
            body.put("recipient", peer);
            httpDeleteJson(baseSignal + "/v1/typing-indicator/" + URLEncoder.encode(myNumber, "UTF-8"), body.toString());
        } catch (Exception ignored) {}
    }

    // -------------------- display rebuild (inserts date headers) --------------------
    private void rebuildDisplay() {
        displayItems.clear();
        String lastDayKey = null;
        for (MessageItem m : rawItems) {
            long ts = m.serverTs > 0 ? m.serverTs : m.displayTs();
            if (ts <= 0) ts = System.currentTimeMillis();
            String dk = dayKey(ts);
            if (!dk.equals(lastDayKey)) {
                displayItems.add(new MessageItem(dateLabel(ts), true));
                lastDayKey = dk;
            }
            displayItems.add(m);
        }
        // first open with unread history: divider + land on first unread
        int firstUnreadPos = -1;
        if (firstLoad && openReadTs > 0) {
            for (int i = 0; i < displayItems.size(); i++) {
                MessageItem m = displayItems.get(i);
                if (m.type != MessageItem.TYPE_DATE_HEADER && "peer".equals(m.from)
                        && m.serverTs > openReadTs) { firstUnreadPos = i; break; }
            }
            if (firstUnreadPos > 0) {
                int n = 0;
                for (MessageItem m : displayItems)
                    if (m.type != MessageItem.TYPE_DATE_HEADER && "peer".equals(m.from)
                            && m.serverTs > openReadTs) n++;
                displayItems.add(firstUnreadPos, new MessageItem("— " + n + " unread —", true));
            }
        }
        chatAdapter.notifyDataSetChanged();
        if (displayItems.isEmpty()) { firstLoad = false; return; }
        if (firstLoad && firstUnreadPos > 0) {
            atBottom = false;
            recycler.scrollToPosition(firstUnreadPos);
            updateJumpButton();
        } else if (atBottom) {
            recycler.scrollToPosition(displayItems.size() - 1);
        } else {
            updateJumpButton();
        }
        firstLoad = false;
        if (chatSearchBar != null && chatSearchBar.getVisibility() == android.view.View.VISIBLE)
            runSearch(chatSearchInput.getText().toString());
    }

    private void setAtBottom(boolean value) {
        if (atBottom == value) return;
        atBottom = value;
        if (atBottom) advanceReadWatermark();
        updateJumpButton();
    }

    private void updateJumpButton() {
        if (btnJumpBottom == null) return;
        btnJumpBottom.setVisibility(atBottom ? android.view.View.GONE : android.view.View.VISIBLE);
        int unseen = 0;
        if (!atBottom) {
            long basis = Math.max(lastSeenTs, openReadTs);
            for (MessageItem m : rawItems)
                if ("peer".equals(m.from) && m.serverTs > basis) unseen++;
        }
        if (unseen > 0) {
            tvJumpUnread.setText(String.valueOf(unseen));
            tvJumpUnread.setVisibility(android.view.View.VISIBLE);
        } else {
            tvJumpUnread.setVisibility(android.view.View.GONE);
        }
    }

    // -------------------- in-chat search --------------------

    private void closeSearch() {
        chatSearchBar.setVisibility(android.view.View.GONE);
        chatSearchInput.setText("");
        searchMatches.clear();
        searchIndex = -1;
        chatAdapter.setHighlightTs(0);
        chatAdapter.notifyDataSetChanged();
    }

    private void runSearch(String q) {
        searchMatches.clear();
        searchIndex = -1;
        String ql = q.toLowerCase(java.util.Locale.US).trim();
        if (ql.length() >= 2) {
            for (int i = 0; i < displayItems.size(); i++) {
                MessageItem m = displayItems.get(i);
                if (m.type == MessageItem.TYPE_DATE_HEADER || m.status == ST_REMOTE_DELETED) continue;
                String hay = m.text != null ? m.text : m.caption;
                if (hay != null && hay.toLowerCase(java.util.Locale.US).contains(ql))
                    searchMatches.add(i);
            }
        }
        if (searchMatches.isEmpty()) {
            chatSearchCount.setText(ql.length() >= 2 ? "0" : "");
            chatAdapter.setHighlightTs(0);
            chatAdapter.notifyDataSetChanged();
        } else {
            searchIndex = searchMatches.size() - 1; // newest match first
            showSearchMatch();
        }
    }

    private void stepSearch(int delta) {
        if (searchMatches.isEmpty()) return;
        searchIndex = (searchIndex + delta + searchMatches.size()) % searchMatches.size();
        showSearchMatch();
    }

    private void showSearchMatch() {
        int pos = searchMatches.get(searchIndex);
        chatSearchCount.setText((searchIndex + 1) + "/" + searchMatches.size());
        MessageItem m = displayItems.get(pos);
        chatAdapter.setHighlightTs(m.serverTs);
        chatAdapter.notifyDataSetChanged();
        recycler.scrollToPosition(pos);
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

    // -------------------- message menu / actions --------------------

    private void showMessageMenu(int displayPos) {
        if (displayPos < 0 || displayPos >= displayItems.size()) return;
        MessageItem m = displayItems.get(displayPos);
        if (m.type == MessageItem.TYPE_DATE_HEADER || m.serverTs == 0) return;
        if (m.status == ST_REMOTE_DELETED) return; // placeholder: no actions

        if (selectionMode) {
            if (selectedTs.contains(m.serverTs)) selectedTs.remove(m.serverTs);
            else selectedTs.add(m.serverTs);
            if (selectedTs.isEmpty()) exitSelectionMode();
            else updateSelectionBar();
            chatAdapter.notifyDataSetChanged();
            return;
        }

        String myCurrentReaction = (m.reactions != null) ? m.reactions.get("me") : null;

        String[] emojis = {"👍", "❤️", "😂", "😮", "😢", "👎", "🙏", "🎉"};
        android.widget.LinearLayout emojiRow = new android.widget.LinearLayout(this);
        emojiRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        emojiRow.setGravity(android.view.Gravity.CENTER);
        emojiRow.setPadding(16, 24, 16, 8);

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.addView(emojiRow);

        boolean canEdit = "me".equals(m.from) && m.type == MessageItem.TYPE_TEXT && m.serverTs > 0;
        boolean selfThread = notEmpty(myNumber) && notEmpty(peerNumber)
                && digits(myNumber).equals(digits(peerNumber));
        boolean canRemoteDelete = "me".equals(m.from) && m.serverTs > 0 && !selfThread
                && System.currentTimeMillis() - m.serverTs < 24L * 3600 * 1000;

        android.app.AlertDialog[] ref = {null};
        for (String e : emojis) {
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(e);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 28);
            tv.setPadding(12, 8, 12, 8);
            if (e.equals(myCurrentReaction)) tv.setBackgroundColor(0x33000000);
            tv.setOnClickListener(v -> {
                boolean remove = e.equals(myCurrentReaction);
                sendReaction(e, m, remove);
                if (ref[0] != null) ref[0].dismiss();
            });
            emojiRow.addView(tv);
        }

        if (canEdit || canRemoteDelete) {
            android.widget.LinearLayout actionRow = new android.widget.LinearLayout(this);
            actionRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            actionRow.setGravity(android.view.Gravity.CENTER);
            actionRow.setPadding(16, 4, 16, 16);

            if (canEdit) {
                android.widget.Button btnEdit = new android.widget.Button(this);
                btnEdit.setText("✏ Edit");
                btnEdit.setOnClickListener(v -> {
                    if (ref[0] != null) ref[0].dismiss();
                    showEditDialog(m);
                });
                actionRow.addView(btnEdit);
            }

            if (canEdit && m.editHistory != null) {
                android.widget.Button btnHist = new android.widget.Button(this);
                btnHist.setText("📋 History");
                btnHist.setOnClickListener(v -> {
                    if (ref[0] != null) ref[0].dismiss();
                    showEditHistory(m);
                });
                actionRow.addView(btnHist);
            }
            if (canRemoteDelete) {
                android.widget.Button btnRd = new android.widget.Button(this);
                btnRd.setText("🗑 Everyone");
                btnRd.setOnClickListener(v -> {
                    if (ref[0] != null) ref[0].dismiss();
                    confirmRemoteDelete(m);
                });
                actionRow.addView(btnRd);
            }
            container.addView(actionRow);
        }

        String copyable = m.type == MessageItem.TYPE_TEXT ? m.text : m.caption;
        if (notEmpty(copyable)) {
            android.widget.Button btnCopy = new android.widget.Button(this);
            btnCopy.setText("📄 Copy");
            btnCopy.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", copyable));
                    Toast.makeText(Chat.this, "Copied", Toast.LENGTH_SHORT).show();
                }
                if (ref[0] != null) ref[0].dismiss();
            });
            android.widget.LinearLayout copyRow = new android.widget.LinearLayout(this);
            copyRow.setGravity(android.view.Gravity.CENTER);
            copyRow.addView(btnCopy);
            container.addView(copyRow);
        }

        ref[0] = new android.app.AlertDialog.Builder(this)
                .setView(container)
                .setPositiveButton("Delete", (d, w) -> confirmDeleteSingle(m.serverTs))
                .setNeutralButton("Reply", (d, w) -> doReply(displayPos))
                .setNegativeButton("Select", (d, w) -> enterSelectionMode(m.serverTs))
                .show();
        ref[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setTextColor(0xFFD32F2F);
    }

    private void showEditDialog(MessageItem m) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(m.text);
        input.setSelection(m.text == null ? 0 : m.text.length());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Edit message")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    String newText = input.getText().toString().trim();
                    if (!newText.isEmpty() && !newText.equals(m.text)) sendEdit(m, newText);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showEditHistory(MessageItem m) {
        try {
            org.json.JSONArray hist = new org.json.JSONArray(m.editHistory);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hist.length(); i++) {
                sb.append("v").append(i + 1).append(": ").append(hist.getString(i));
                if (i < hist.length() - 1) sb.append("\n\n");
            }
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Edit history")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {}
    }

    private void sendEdit(MessageItem original, String newText) {
        new Thread(() -> {
            try {
                String url = baseSignal + "/v2/send";
                JSONObject body = new JSONObject();
                body.put("message", newText);
                body.put("number", myNumber);
                JSONArray rcpts = new JSONArray();
                if (notEmpty(peerNumber)) rcpts.put(peerNumber); else rcpts.put(peerUuid);
                body.put("recipients", rcpts);
                long editTs = original.lastEditTs > 0 ? original.lastEditTs : original.serverTs;
                body.put("edit_timestamp", editTs);
                String tsRaw = postForTimestamp(url, body.toString(), 8000);
                final long newEditTs = tsRaw == null ? 0 : parseLongSafe(tsRaw);
                final long prevTs = editTs;
                runOnUiThread(() -> {
                    if (tsRaw != null) {
                        repo.applyLocalEdit(chatDbKey, prevTs, newText, newEditTs);
                    } else {
                        Toast.makeText(Chat.this, "Edit failed", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private void confirmRemoteDelete(MessageItem m) {
        new android.app.AlertDialog.Builder(this)
                .setMessage("Delete this message for everyone?")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    try {
                        String peer = notEmpty(peerNumber) ? peerNumber : peerUuid;
                        JSONObject body = new JSONObject();
                        body.put("recipient", peer);
                        body.put("timestamp", m.serverTs);
                        int code = httpDeleteJson(baseSignal + "/v1/remote-delete/"
                                + URLEncoder.encode(myNumber, "UTF-8"), body.toString());
                        if (code >= 200 && code < 300) {
                            repo.remoteDeleteLocal(chatDbKey, m.serverTs);
                        } else {
                            runOnUiThread(() -> Toast.makeText(Chat.this,
                                    "Delete failed (too old?)", Toast.LENGTH_SHORT).show());
                        }
                    } catch (Exception e) {
                        runOnUiThread(() -> Toast.makeText(Chat.this,
                                "Delete failed", Toast.LENGTH_SHORT).show());
                    }
                }).start())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void enterSelectionMode(long firstTs) {
        selectionMode = true;
        selectedTs.clear();
        selectedTs.add(firstTs);
        chatAdapter.setSelectedTs(selectedTs);
        updateSelectionBar();
        chatAdapter.notifyDataSetChanged();
    }

    private void exitSelectionMode() {
        selectionMode = false;
        selectedTs.clear();
        chatAdapter.setSelectedTs(selectedTs);
        selectionBar.setVisibility(android.view.View.GONE);
        chatAdapter.notifyDataSetChanged();
    }

    private void updateSelectionBar() {
        selectionBar.setVisibility(android.view.View.VISIBLE);
        tvSelectionCount.setText(selectedTs.size() + " selected");
    }

    private void confirmDeleteSingle(long ts) {
        new android.app.AlertDialog.Builder(this)
                .setMessage("Delete this message?")
                .setPositiveButton("Delete", (d, w) ->
                        repo.deleteLocal(chatDbKey, java.util.Collections.singletonList(ts)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteSelected() {
        int count = selectedTs.size();
        new android.app.AlertDialog.Builder(this)
                .setMessage("Delete " + count + " message" + (count == 1 ? "" : "s") + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    repo.deleteLocal(chatDbKey, new ArrayList<>(selectedTs));
                    exitSelectionMode();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void doReply(int displayPos) {
        if (displayPos < 0 || displayPos >= displayItems.size()) return;
        MessageItem tapped = displayItems.get(displayPos);
        if (tapped.type == MessageItem.TYPE_DATE_HEADER) return;
        replyToItem = tapped;
        replyToTs   = replyToItem.serverTs;
        String preview = replyToItem.type == MessageItem.TYPE_IMAGE
                ? "📷 Photo" : (replyToItem.text != null ? replyToItem.text : "");
        String label = "me".equals(replyToItem.from) ? "You: " + preview : preview;
        tvReplyPreviewText.setText(label);
        replyPreviewBar.setVisibility(android.view.View.VISIBLE);
    }

    private void sendReaction(String emoji, MessageItem target, boolean isRemove) {
        repo.sendLocalReaction(chatDbKey, target.serverTs, emoji, isRemove);
        new Thread(() -> {
            try {
                String peer = notEmpty(peerNumber) ? peerNumber : peerUuid;
                String targetAuthor = "me".equals(target.from)
                        ? (notEmpty(myNumber) ? myNumber : peer) : peer;
                JSONObject body = new JSONObject();
                body.put("recipient", peer);
                body.put("reaction", emoji);
                body.put("target_author", targetAuthor);
                body.put("timestamp", target.serverTs);
                String url = baseSignal + "/v1/reactions/" + URLEncoder.encode(myNumber, "UTF-8");
                int code = isRemove ? httpDeleteJson(url, body.toString())
                                   : httpPostJson(url, body.toString());
                if (code < 200 || code >= 300)
                    runOnUiThread(() ->
                            Toast.makeText(Chat.this, "Reaction failed", Toast.LENGTH_SHORT).show());
            } catch (Exception ignored) {}
        }).start();
    }

    // -------------------- history --------------------
    private void loadHistory() {
        if (demoMode) {
            rawItems.clear();
            rawItems.addAll(DemoData.getFakeMessages(demoIndex));
            rebuildDisplay();
            return;
        }
        List<MessageItem> stored = repo.getThread(chatDbKey);
        if (!stored.isEmpty()) {
            rawItems.clear();
            rawItems.addAll(stored);
            rebuildDisplay();
            return;
        }

        // DB empty — migrate from old SharedPreferences JSON (pre-DB builds)
        String histKey = "chat_hist_" + chatDbKey;
        try {
            String raw = prefs.getString(histKey, "[]");
            JSONArray arr = new JSONArray(raw);
            if (arr.length() == 0) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o   = arr.getJSONObject(i);
                String from    = o.optString("from", "peer");
                int status     = o.optInt("status", ST_DELIVERED);
                String type    = o.optString("type", "text");
                long   sTs     = o.optLong("serverTs", 0);
                String qt      = o.optString("quoteText", "");
                String qa      = o.optString("quoteAuthor", "peer");
                String dir     = "me".equals(from) ? "out" : "in";
                if ("image".equals(type)) {
                    String cid     = o.optString("attId", "");
                    String mime    = o.optString("mime", "");
                    String caption = o.optString("caption", "");
                    String locUri  = o.optString("localUri", "");
                    repo.db.upsert(chatDbKey, dir, "image", null,
                            cid.isEmpty() ? null : cid, mime,
                            caption.isEmpty() ? null : caption,
                            locUri.isEmpty() ? null : locUri,
                            sTs, status, qt.isEmpty() ? null : qt, qa.isEmpty() ? null : qa);
                } else {
                    String text = o.optString("text", "");
                    repo.db.upsert(chatDbKey, dir, "text", text, null, null, null, null,
                            sTs, status, qt.isEmpty() ? null : qt, qa.isEmpty() ? null : qa);
                }
            }
            prefs.edit().remove(histKey).apply();
            rawItems.clear();
            rawItems.addAll(repo.getThread(chatDbKey));
            rebuildDisplay();
        } catch (Exception ignored) {}
    }
}
