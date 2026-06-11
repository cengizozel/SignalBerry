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
    private boolean isGroup;
    private String sendRecipient;   // number/uuid for DMs, "group.<token>" for groups
    private final java.util.Map<String, String> authorNames = new java.util.HashMap<>();
    // group roster {memberId, displayName}; pending @mentions {displayName, author}
    private final List<String[]> groupMembers = new ArrayList<>();
    private final List<String[]> pendingMentions = new ArrayList<>();

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
                resolveAuthors(fresh);
                runOnUiThread(() -> {
                    rawItems.clear();
                    rawItems.addAll(fresh);
                    rebuildDisplay();
                    advanceReadWatermark();
                    repo.queueReadReceipts(chatDbKey,
                            sendRecipient, rawItems);
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

        String groupKey = getIntent().getStringExtra("peer_group");
        isGroup = notEmpty(groupKey);
        if (!notEmpty(ipPref) || !notEmpty(myNumber)
                || (!isGroup && !notEmpty(peerNumber) && !notEmpty(peerUuid))) {
            Toast.makeText(this, "Missing server IP / my number / peer id", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        baseSignal = normalizeBase(ipPref);
        chatDbKey = isGroup ? groupKey : PeerKeys.get(this).resolve(peerNumber, peerUuid);
        // group send token = "group." + base64(internal id) — derivable, no lookup
        sendRecipient = isGroup
                ? "group." + android.util.Base64.encodeToString(
                        groupKey.substring("group:".length()).getBytes(), android.util.Base64.NO_WRAP)
                : (sendRecipient);
        openReadTs = prefs.getLong("read_ts_" + chatDbKey, 0);
        // self thread is "Note to Self"; a local alias overrides everything
        if (notEmpty(myNumber) && chatDbKey.equals(digits(myNumber))) peerName = "Note to Self";
        String alias = prefs.getString("alias_" + chatDbKey, "");
        if (notEmpty(alias)) peerName = alias;

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
        android.widget.TextView titleView = findViewById(R.id.title_name);
        loadChatAvatar();
        titleView.setText(peerName);
        findViewById(R.id.title_container).setOnClickListener(v -> {
            if (demoMode) return;
            if (isGroup) { showGroupInfo(); return; }
            openGallery();
        });
        if (isGroup) new Thread(this::loadGroupMembers).start();

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
        chatAdapter.setAudioUi(audioUi);
        RecyclerView.ItemAnimator anim = recycler.getItemAnimator();
        if (anim instanceof androidx.recyclerview.widget.SimpleItemAnimator)
            ((androidx.recyclerview.widget.SimpleItemAnimator) anim).setSupportsChangeAnimations(false);
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
            new Thread(() -> { // search needs the WHOLE history, not the 300-row page
                final List<MessageItem> full = repo.getThreadFull(chatDbKey);
                resolveAuthors(full);
                runOnUiThread(() -> {
                    rawItems.clear();
                    rawItems.addAll(full);
                    rebuildDisplay();
                });
            }).start();
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
                handler.removeCallbacks(searchRun);
                handler.postDelayed(searchRun, 250); // debounce keystrokes
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
            if (m.status == ST_FAILED && "me".equals(m.from)) { retryFailedSend(m); return; }
            if ("audio".equals(m.msgType)) handlePlayPause(m);
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

        ImageButton mic = findViewById(R.id.btn_mic);
        mic.setOnClickListener(v -> toggleRecording());
        mic.setOnLongClickListener(v -> {
            if (recorder != null) { stopRecording(false); return true; }
            return false;
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
            JSONArray mentions = buildMentions(text);
            pendingMentions.clear();
            input.setText("");
            handler.removeCallbacks(typingStopRun);
            if (typingSent) { typingSent = false; new Thread(this::sendTypingStop).start(); }

            sendText(text, replyItem, replyTs, mentions);
        });

        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                // a freshly typed "@" in a group opens the member picker
                if (isGroup && count == 1 && before == 0 && st < s.length()
                        && s.charAt(st) == '@') {
                    showMentionPicker(st);
                }
            }
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
        if (recorder != null) stopRecording(false);
        stopVoice();
        handler.removeCallbacks(typingStopRun);
        handler.removeCallbacks(typingHideRun);
        handler.removeCallbacks(reloadRun);
        reloadQueued = false;
        if (typingSent) { typingSent = false; new Thread(this::sendTypingStop).start(); }
        if (audioPlayer != null) { audioPlayer.release(); audioPlayer = null; }
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
        sendText(text, replyItem, replyTs, null);
    }

    private void sendText(String text, MessageItem replyItem, long replyTs, JSONArray mentions) {
        final String qt = replyItem == null ? null
                : (replyItem.type == MessageItem.TYPE_IMAGE ? "📷 Photo"
                   : (replyItem.text != null ? replyItem.text : ""));
        final String qa = replyItem == null ? null : replyItem.from;

        final long nonce = repo.beginSend(chatDbKey, "text", text, null, null, null,
                replyTs, qt, qa);
        if (nonce <= 0) { Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show(); return; }

        new Thread(() -> {
            String tsRaw = sendOnce(text, replyItem, replyTs, mentions);
            if (tsRaw != null) {
                repo.confirmSend(chatDbKey, nonce, tsRaw, "text", text, null, null,
                        replyTs, qt, qa);
            } else {
                repo.failSend(chatDbKey, nonce);
                final String err = MessageSender.lastError();
                runOnUiThread(() -> {
                    if (err != null && err.toLowerCase(java.util.Locale.US).contains("untrusted")) {
                        promptTrustIdentity();
                    } else {
                        Toast.makeText(Chat.this, "Send failed, tap the message to retry", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }

    /** The peer's safety number changed (new device/reinstall) — sends fail
     *  until the new identity is trusted. */
    private void promptTrustIdentity() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Safety number changed")
                .setMessage(peerName + "'s safety number has changed (they may have "
                        + "reinstalled Signal or switched devices). Trust the new "
                        + "identity to keep messaging?")
                .setPositiveButton("Trust", (d, w) -> new Thread(() -> {
                    try {
                        String peer = sendRecipient;
                        JSONObject body = new JSONObject();
                        body.put("trust_all_known_keys", true);
                        int code = httpPutJson(baseSignal + "/v1/identities/"
                                + URLEncoder.encode(myNumber, "UTF-8") + "/trust/"
                                + URLEncoder.encode(peer, "UTF-8"), body.toString());
                        runOnUiThread(() -> Toast.makeText(Chat.this,
                                code >= 200 && code < 300
                                        ? "Identity trusted, tap the failed message to resend"
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
        new androidx.appcompat.app.AlertDialog.Builder(this)
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
        final String recipient = sendRecipient;
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
        return sendOnce(text, replyTo, replyTs, null);
    }

    private String sendOnce(String text, MessageItem replyTo, long replyTs, JSONArray mentions) {
        try {
            MessageSender sender = new MessageSender(baseSignal, myNumber, sendRecipient);
            JSONObject body = sender.body(text);
            sender.addQuote(body, replyTo, replyTs, replyTo == null ? null : replyTo.author);
            MessageSender.addMentions(body, mentions);
            return sender.send(body, 8000);
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
        final String recipient = sendRecipient;

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
            sendStoredFile(stored, mime, kind, cap);
        }).start();
    }

    /** Send an attachment already copied into the store. Blocking — call off
     *  the main thread. Shared by the media picker and voice recording. */
    private void sendStoredFile(java.io.File stored, String mime, String kind, String caption) {
        final String recipient = sendRecipient;
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
        final long nonce = repo.beginSend(chatDbKey, kind, null, mime, caption, storedPath, 0, null, null);
        if (nonce <= 0) {
            runOnUiThread(() -> Toast.makeText(this, "Send failed", Toast.LENGTH_SHORT).show());
            return;
        }

        try {
            // 2. constant-memory streaming upload (REDESIGN §3.5)
            String tsRaw = AttachmentStore.sendStreaming(baseSignal, myNumber, recipient,
                    caption == null ? "" : caption, mime, stored, null, null, null);
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
    }

    // -------------------- VOICE MESSAGES --------------------

    private android.media.MediaRecorder recorder;
    private java.io.File recFile;

    private void toggleRecording() {
        if (recorder != null) { stopRecording(true); return; }
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(
                android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 102);
            return;
        }
        try {
            recFile = new java.io.File(getCacheDir(), "voice-" + System.currentTimeMillis() + ".m4a");
            recorder = new android.media.MediaRecorder();
            recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(44100);
            recorder.setAudioEncodingBitRate(64000);
            recorder.setOutputFile(recFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            ImageButton mic = findViewById(R.id.btn_mic);
            mic.setColorFilter(0xFFD32F2F);
            EditText input = findViewById(R.id.input_message);
            input.setHint("Recording… tap mic to send, hold to cancel");
        } catch (Exception e) {
            cleanupRecorder();
            Toast.makeText(this, "Cannot record audio here", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopRecording(boolean sendIt) {
        if (recorder == null) return;
        try { recorder.stop(); } catch (Exception e) { sendIt = false; }
        cleanupRecorder();
        final java.io.File f = recFile;
        recFile = null;
        if (f == null) return;
        if (!sendIt || f.length() < 1024) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!sendIt) Toast.makeText(this, "Recording discarded", Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, "Too short, not sent", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            AttachmentStore store = AttachmentStore.get(this);
            java.io.File stored = store.importLocal(android.net.Uri.fromFile(f),
                    "local-" + System.currentTimeMillis());
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (stored == null) {
                runOnUiThread(() -> Toast.makeText(this, "Could not save recording",
                        Toast.LENGTH_SHORT).show());
                return;
            }
            sendStoredFile(stored, "audio/mp4", "audio", null);
        }).start();
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        ImageButton mic = findViewById(R.id.btn_mic);
        if (mic != null) mic.clearColorFilter();
        EditText input = findViewById(R.id.input_message);
        if (input != null) input.setHint("Message");
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == 102 && grants.length > 0
                && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED)
            toggleRecording();
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
                if (mime.startsWith("audio/")) { playAudioInline(f); return; }
                try {
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            this, getPackageName() + ".files", f);
                    Intent view = new Intent(Intent.ACTION_VIEW);
                    view.setDataAndType(uri, mime);
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(view);
                } catch (Exception e) {
                    if (mime.startsWith("audio/")) playAudioInline(f);
                    else Toast.makeText(this, "No app can open this file", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** Peer avatar in the title bar: photo if the contact has one, initials
     *  otherwise, notepad for Note to Self. Same cache as the chat list. */
    /** Replace the just-typed "@" (at atPos) with "@Name " and record the span
     *  author so the send path can attach a proper mention. */
    private void showMentionPicker(int atPos) {
        List<String[]> members;
        synchronized (groupMembers) { members = new ArrayList<>(groupMembers); }
        if (members.isEmpty()) return;
        final List<String[]> mentionable = new ArrayList<>();
        for (String[] m : members) if (!"You".equals(m[1])) mentionable.add(m);
        if (mentionable.isEmpty()) return;
        final String[] labels = new String[mentionable.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = mentionable.get(i)[1];

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Mention")
                .setItems(labels, (d, which) -> {
                    String[] m = mentionable.get(which);
                    EditText input = findViewById(R.id.input_message);
                    android.text.Editable e = input.getText();
                    int end = Math.min(atPos + 1, e.length()); // the "@" we replace
                    String insert = "@" + m[1] + " ";
                    e.replace(atPos, end, insert);
                    input.setSelection(Math.min(atPos + insert.length(), input.length()));
                    pendingMentions.add(new String[]{m[1], m[0]}); // displayName, author
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Build the wire mentions array by locating each pending "@Name" in the
     *  final text. Tolerant of edits — unmatched pending mentions are dropped. */
    private JSONArray buildMentions(String text) {
        if (pendingMentions.isEmpty()) return null;
        JSONArray arr = new JSONArray();
        int cursor = 0;
        try {
            for (String[] pm : pendingMentions) {
                String tag = "@" + pm[0];
                int idx = text.indexOf(tag, cursor);
                if (idx < 0) continue;
                JSONObject o = new JSONObject();
                o.put("start", idx);
                o.put("length", tag.length());
                o.put("author", pm[1]);
                arr.put(o);
                cursor = idx + tag.length();
            }
        } catch (Exception ignored) {}
        return arr.length() > 0 ? arr : null;
    }

    private void openGallery() {
        Intent g = new Intent(Chat.this, MediaGalleryActivity.class);
        g.putExtra(MediaGalleryActivity.EXTRA_PEER_KEY, chatDbKey);
        g.putExtra(MediaGalleryActivity.EXTRA_PEER_NAME, peerName);
        startActivity(g);
    }

    /** Fetch the group roster once; resolve each member to a display name. */
    private void loadGroupMembers() {
        try {
            String gid = chatDbKey.substring("group:".length());
            JSONArray groups = new JSONArray(httpGet(baseSignal + "/v1/groups/"
                    + URLEncoder.encode(myNumber, "UTF-8")));
            for (int i = 0; i < groups.length(); i++) {
                JSONObject g = groups.optJSONObject(i);
                if (g == null || !gid.equals(g.optString("internal_id", ""))) continue;
                JSONArray mem = g.optJSONArray("members");
                List<String[]> out = new ArrayList<>();
                String selfKey = digits(myNumber);
                for (int j = 0; mem != null && j < mem.length(); j++) {
                    String id = mem.optString(j, "");
                    if (isEmpty(id)) continue;
                    String key = PeerKey.normalize(id);
                    String name;
                    if (key.equals(selfKey)) name = "You";
                    else {
                        name = prefs.getString("alias_" + key, "");
                        if (isEmpty(name)) name = prefs.getString("contact_name_" + key, "");
                        if (isEmpty(name)) name = id;
                    }
                    out.add(new String[]{id, name});
                }
                synchronized (groupMembers) {
                    groupMembers.clear();
                    groupMembers.addAll(out);
                }
                return;
            }
        } catch (Exception ignored) {}
    }

    private void showGroupInfo() {
        List<String[]> members;
        synchronized (groupMembers) { members = new ArrayList<>(groupMembers); }
        StringBuilder sb = new StringBuilder();
        if (members.isEmpty()) sb.append("Loading members…");
        else for (String[] m : members) sb.append("• ").append(m[1]).append("\n");

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(peerName)
                .setMessage(members.isEmpty() ? sb.toString()
                        : members.size() + " members\n\n" + sb.toString().trim())
                .setPositiveButton("Media & files", (d, w) -> openGallery())
                .setNegativeButton("Close", null)
                .show();
    }

    /** Group bubbles need "who said this": map author keys to display names. */
    private void resolveAuthors(List<MessageItem> items) {
        if (!isGroup) return;
        for (MessageItem it : items) {
            if (isEmpty(it.author) || !"peer".equals(it.from)) continue;
            String name = authorNames.get(it.author);
            if (name == null) {
                name = prefs.getString("alias_" + it.author, "");
                if (isEmpty(name)) name = prefs.getString("contact_name_" + it.author, "");
                if (isEmpty(name)) {
                    // uuid-keyed stranger: show a short stable handle
                    name = it.author.length() > 8 ? it.author.substring(0, 8) : it.author;
                }
                authorNames.put(it.author, name);
            }
            it.authorName = name;
        }
    }

    private void loadChatAvatar() {
        final android.widget.ImageView iv = findViewById(R.id.iv_chat_avatar);
        if (iv == null) return;
        final int size = (int) (30 * getResources().getDisplayMetrics().density + 0.5f);
        if (isGroup) {
            iv.setImageBitmap(MessagesAdapter.groupCircle(size));
            final String token = prefs.getString("group_sendid_" + chatDbKey, "");
            if (isEmpty(token)) return;
            new Thread(() -> {
                android.graphics.Bitmap raw = new AvatarCache(getCacheDir(), baseSignal, myNumber)
                        .fetchGroup(chatDbKey, token);
                if (raw == null) return;
                final android.graphics.Bitmap circle = MessagesAdapter.circleCrop(raw, size);
                runOnUiThread(() -> iv.setImageBitmap(circle));
            }).start();
            return;
        }
        if (notEmpty(myNumber) && chatDbKey.equals(digits(myNumber))) {
            iv.setImageBitmap(MessagesAdapter.noteToSelfCircle(size));
            return;
        }
        iv.setImageBitmap(MessagesAdapter.initialsCircle(peerName, size));
        if (demoMode) return;
        final String avatarUuid = prefs.getString("contact_avatar_" + chatDbKey, "");
        if (isEmpty(avatarUuid)) return;
        new Thread(() -> {
            android.graphics.Bitmap raw = new AvatarCache(getCacheDir(), baseSignal, myNumber)
                    .fetch(peerNumber, avatarUuid);
            if (raw == null) return;
            final android.graphics.Bitmap circle = MessagesAdapter.circleCrop(raw, size);
            runOnUiThread(() -> iv.setImageBitmap(circle));
        }).start();
    }

    // -------------------- VOICE NOTE PLAYER UI --------------------

    private VoiceNotePlayer voicePlayer;
    private long voiceActiveTs;
    private float voiceSpeed = 1f;
    private static final android.util.LruCache<String, float[]> WAVE_CACHE =
            new android.util.LruCache<>(64);
    private static final java.util.Map<String, Long> DUR_CACHE = new java.util.HashMap<>();
    private final java.util.Set<String> waveInFlight = new java.util.HashSet<>();
    private static final int WAVE_BARS = 24;

    private final Runnable voiceTick = new Runnable() {
        @Override public void run() {
            if (voicePlayer != null && voicePlayer.isAlive()) {
                notifyAudioItem(voiceActiveTs);
                if (voicePlayer.isPlaying()) handler.postDelayed(this, 400);
            }
        }
    };

    private java.io.File audioFileOf(MessageItem m) {
        if (m.localUri != null && m.localUri.startsWith("/")) {
            java.io.File f = new java.io.File(m.localUri);
            if (f.exists()) return f;
        }
        if (!isEmpty(m.attachmentId)) {
            AttachmentStore s = AttachmentStore.get(this);
            if (s.has(m.attachmentId)) return s.fileFor(m.attachmentId);
        }
        return null;
    }

    private void notifyAudioItem(long ts) {
        if (ts == 0) return;
        for (int i = 0; i < displayItems.size(); i++)
            if (displayItems.get(i).serverTs == ts) { chatAdapter.notifyItemChanged(i); return; }
    }

    private void stopVoice() {
        if (voicePlayer != null) { voicePlayer.release(); voicePlayer = null; }
        handler.removeCallbacks(voiceTick);
        long old = voiceActiveTs;
        voiceActiveTs = 0;
        notifyAudioItem(old);
    }

    private void handlePlayPause(MessageItem m) {
        if (voicePlayer != null && m.serverTs == voiceActiveTs && voicePlayer.isAlive()) {
            if (voicePlayer.isPlaying()) voicePlayer.pause();
            else { voicePlayer.resume(); handler.post(voiceTick); }
            notifyAudioItem(voiceActiveTs);
            return;
        }
        stopVoice();
        java.io.File f = audioFileOf(m);
        if (f == null) {
            if (isEmpty(m.attachmentId)) return;
            Toast.makeText(this, "Downloading\u2026", Toast.LENGTH_SHORT).show();
            final String attId = m.attachmentId;
            new Thread(() -> {
                java.io.File got = AttachmentStore.get(this).fetch(baseSignal, attId);
                runOnUiThread(() -> {
                    if (got == null) Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show();
                    else startVoice(m, got);
                });
            }).start();
            return;
        }
        startVoice(m, f);
    }

    private void startVoice(MessageItem m, java.io.File f) {
        voiceActiveTs = m.serverTs;
        voicePlayer = new VoiceNotePlayer(f, voiceSpeed, new VoiceNotePlayer.Listener() {
            @Override public void onCompleted() { stopVoice(); }
            @Override public void onError() {
                stopVoice();
                Toast.makeText(Chat.this, "Cannot play this audio", Toast.LENGTH_SHORT).show();
            }
        });
        voicePlayer.start();
        ensureWave(m, f);
        handler.post(voiceTick);
        notifyAudioItem(voiceActiveTs);
    }

    private void ensureWave(MessageItem m, java.io.File f) {
        final String key = f.getAbsolutePath();
        if (WAVE_CACHE.get(key) != null || waveInFlight.contains(key)) return;
        waveInFlight.add(key);
        final long ts = m.serverTs;
        new Thread(() -> {
            VoiceNotePlayer.WaveResult r = VoiceNotePlayer.waveform(f, WAVE_BARS);
            runOnUiThread(() -> {
                waveInFlight.remove(key);
                if (r != null) {
                    WAVE_CACHE.put(key, r.levels);
                    DUR_CACHE.put(key, r.durMs);
                    notifyAudioItem(ts);
                }
            });
        }).start();
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms) / 1000;
        return (s / 60) + ":" + String.format(java.util.Locale.US, "%02d", s % 60);
    }

    private static String speedText(float s) {
        return (s == (long) s ? String.valueOf((long) s) : String.valueOf(s)) + "\u00D7";
    }

    private final ChatAdapter.AudioUi audioUi = new ChatAdapter.AudioUi() {
        @Override public boolean isActive(MessageItem m) {
            return voicePlayer != null && m.serverTs == voiceActiveTs;
        }
        @Override public boolean isPlaying(MessageItem m) {
            return isActive(m) && voicePlayer.isPlaying();
        }
        @Override public float progress(MessageItem m) {
            if (!isActive(m) || voicePlayer.getDurMs() <= 0) return 0f;
            return voicePlayer.getPosMs() / (float) voicePlayer.getDurMs();
        }
        @Override public String speedLabel() { return speedText(voiceSpeed); }
        @Override public String durLabel(MessageItem m) {
            if (isActive(m) && voicePlayer.getDurMs() > 0)
                return clock(voicePlayer.getPosMs()) + " / " + clock(voicePlayer.getDurMs());
            java.io.File f = audioFileOf(m);
            if (f != null) {
                Long d = DUR_CACHE.get(f.getAbsolutePath());
                if (d != null) return clock(d);
                ensureWave(m, f);
            }
            return "Voice message";
        }
        @Override public float[] wave(MessageItem m) {
            java.io.File f = audioFileOf(m);
            if (f == null) return null;
            float[] w = WAVE_CACHE.get(f.getAbsolutePath());
            if (w == null) ensureWave(m, f);
            return w;
        }
        @Override public void onPlayPause(MessageItem m) { handlePlayPause(m); }
        @Override public void onSeek(MessageItem m, float fraction) {
            if (!isActive(m)) return;
            voicePlayer.seekTo(fraction);
            handler.removeCallbacks(voiceTick);
            handler.post(voiceTick);
        }
        @Override public void onCycleSpeed(MessageItem m) {
            voiceSpeed = voiceSpeed == 1f ? 1.5f : voiceSpeed == 1.5f ? 2f
                    : voiceSpeed == 2f ? 0.5f : 1f;
            if (voicePlayer != null) voicePlayer.setSpeed(voiceSpeed);
            notifyAudioItem(voiceActiveTs);
        }
    };

    private android.media.MediaPlayer audioPlayer;

    /** Voice notes must play even with no system audio app: tap toggles. */
    private void playAudioInline(java.io.File f) {
        try {
            if (audioPlayer != null) {
                audioPlayer.release();
                audioPlayer = null;
                Toast.makeText(this, "Stopped", Toast.LENGTH_SHORT).show();
                return;
            }
            audioPlayer = new android.media.MediaPlayer();
            java.io.FileInputStream fis = new java.io.FileInputStream(f);
            audioPlayer.setDataSource(fis.getFD());
            fis.close();
            audioPlayer.setOnCompletionListener(mp -> {
                mp.release();
                audioPlayer = null;
            });
            audioPlayer.prepare();
            audioPlayer.start();
            Toast.makeText(this, "Playing, tap again to stop", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            if (audioPlayer != null) { audioPlayer.release(); audioPlayer = null; }
            Toast.makeText(this, "Cannot play this audio", Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------- TYPING INDICATORS --------------------
    private void sendTypingStart() {
        if (isGroup) return;
        try {
            String peer = sendRecipient;
            if (peer == null) return;
            JSONObject body = new JSONObject();
            body.put("recipient", peer);
            httpPutJson(baseSignal + "/v1/typing-indicator/" + URLEncoder.encode(myNumber, "UTF-8"), body.toString());
        } catch (Exception ignored) {}
    }

    private void sendTypingStop() {
        if (isGroup) return;
        try {
            String peer = sendRecipient;
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
                displayItems.add(firstUnreadPos, new MessageItem(n + " unread", true));
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

    private final Runnable searchRun = () -> runSearch(chatSearchInput.getText().toString());

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
        boolean canEdit = "me".equals(m.from) && m.type == MessageItem.TYPE_TEXT && m.serverTs > 0;
        boolean selfThread = notEmpty(myNumber) && notEmpty(peerNumber)
                && digits(myNumber).equals(digits(peerNumber));
        boolean canRemoteDelete = "me".equals(m.from) && m.serverTs > 0 && !selfThread
                && System.currentTimeMillis() - m.serverTs < 24L * 3600 * 1000;
        String copyable = m.type == MessageItem.TYPE_TEXT ? m.text : m.caption;

        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(dpC(10), dpC(12), dpC(10), dpC(10));

        final androidx.appcompat.app.AlertDialog[] ref = {null};

        // pinned reactions (long-press a pin to swap it; "+" opens the full picker)
        String[] emojis = pinnedReactions();
        android.widget.LinearLayout emojiRow = new android.widget.LinearLayout(this);
        emojiRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        for (int i = 0; i < emojis.length; i++) {
            final String e = emojis[i];
            final int slot = i;
            android.widget.TextView tv = new android.widget.TextView(this);
            tv.setText(e);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
            tv.setGravity(android.view.Gravity.CENTER);
            tv.setPadding(0, dpC(8), 0, dpC(8));
            android.widget.LinearLayout.LayoutParams elp =
                    new android.widget.LinearLayout.LayoutParams(0,
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tv.setLayoutParams(elp);
            if (e.equals(myCurrentReaction)) {
                android.graphics.drawable.GradientDrawable sel =
                        new android.graphics.drawable.GradientDrawable();
                sel.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                sel.setColor(0x332196F3);
                tv.setBackground(sel);
            }
            tv.setOnClickListener(v -> {
                sendReaction(e, m, e.equals(myCurrentReaction));
                if (ref[0] != null) ref[0].dismiss();
            });
            tv.setOnLongClickListener(v -> {
                showEmojiPicker("Pin a reaction", picked -> {
                    String[] pins = pinnedReactions();
                    pins[slot] = picked;
                    prefs.edit().putString("pinned_reactions",
                            android.text.TextUtils.join(" ", pins)).apply();
                    Toast.makeText(Chat.this, "Pinned " + picked, Toast.LENGTH_SHORT).show();
                    if (ref[0] != null) ref[0].dismiss();
                    showMessageMenu(displayPos); // reopen with the new pin visible
                });
                return true;
            });
            emojiRow.addView(tv);
        }
        // "+" → react with any emoji
        android.widget.TextView plus = new android.widget.TextView(this);
        plus.setText("+");
        plus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22);
        plus.setTextColor(Utils.ACCENT);
        plus.setGravity(android.view.Gravity.CENTER);
        plus.setPadding(0, dpC(8), 0, dpC(8));
        plus.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        plus.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            showEmojiPicker("React", picked ->
                    sendReaction(picked, m, picked.equals(myCurrentReaction)));
        });
        emojiRow.addView(plus);
        container.addView(emojiRow);

        android.view.View div = new android.view.View(this);
        android.widget.LinearLayout.LayoutParams dlp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dpC(1));
        dlp.setMargins(dpC(4), dpC(10), dpC(4), dpC(6));
        div.setLayoutParams(dlp);
        div.setBackgroundColor(0x33808080);
        container.addView(div);

        addMenuRow(container, ref, "↩  Reply", Utils.ACCENT, () -> doReply(displayPos));
        if (notEmpty(copyable)) {
            final String toCopy = copyable;
            addMenuRow(container, ref, "📋  Copy", Utils.ACCENT, () -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("message", toCopy));
                    Toast.makeText(Chat.this, "Copied", Toast.LENGTH_SHORT).show();
                }
            });
        }
        if (canEdit) {
            addMenuRow(container, ref, "✏  Edit", Utils.ACCENT, () -> showEditDialog(m));
            if (m.editHistory != null)
                addMenuRow(container, ref, "≡  Edit history", Utils.ACCENT, () -> showEditHistory(m));
        }
        addMenuRow(container, ref, "✓  Select", Utils.ACCENT, () -> enterSelectionMode(m.serverTs));
        if (canRemoteDelete)
            addMenuRow(container, ref, "×  Delete for everyone", 0xFFFF9800,
                    () -> confirmRemoteDelete(m));
        addMenuRow(container, ref, "×  Delete", 0xFFD32F2F, () -> confirmDeleteSingle(m.serverTs));

        ref[0] = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setView(wrapMenuScroll(container))
                .create();
        ref[0].show();
    }

    private void addMenuRow(android.widget.LinearLayout parent,
                            androidx.appcompat.app.AlertDialog[] ref,
                            String label, int color, Runnable action) {
        android.widget.TextView tv = new android.widget.TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(color);
        tv.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tv.setPadding(dpC(14), dpC(12), dpC(14), dpC(12));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dpC(8));
        g.setColor(0x14808080);
        tv.setBackground(g);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dpC(2), dpC(3), dpC(2), dpC(3));
        tv.setLayoutParams(lp);
        tv.setOnClickListener(v -> {
            if (ref[0] != null) ref[0].dismiss();
            action.run();
        });
        parent.addView(tv);
    }

    private android.widget.ScrollView wrapMenuScroll(android.view.View child) {
        android.widget.ScrollView s = new android.widget.ScrollView(this);
        s.addView(child);
        return s;
    }

    interface EmojiSink { void onPick(String emoji); }

    private static final String DEFAULT_PINS = "👍 ❤️ 😂 😲 😢 👎 🙏 🎉";
    /** Extracted from the device's own AndroidEmoji.ttf cmap — every emoji
     *  this font can actually draw, and nothing it can't. */
    private static final String EMOJI_SET =
            "😁 😂 😃 😄 😅 😆 😉 😊 😋 😌 😍 😏 😒 😓 😔 😖 😘 😚 😜 😝 " +
            "😞 😠 😡 😢 😣 😤 😥 😨 😩 😪 😫 😭 😰 😱 😲 😳 😵 😷 😸 😹 " +
            "😺 😻 😼 😽 😾 😿 🙀 🙅 🙆 🙇 🙈 🙉 🙊 🙋 🙌 🙍 🙎 🙏 🌀 🌁 " +
            "🌂 🌃 🌄 🌅 🌆 🌇 🌈 🌉 🌊 🌋 🌌 🌏 🌑 🌓 🌔 🌕 🌙 🌛 🌟 🌠 " +
            "🌰 🌱 🌴 🌵 🌷 🌸 🌹 🌺 🌻 🌼 🌽 🌾 🌿 🍀 🍁 🍂 🍃 🍄 🍅 🍆 " +
            "🍇 🍈 🍉 🍊 🍌 🍍 🍎 🍏 🍑 🍒 🍓 🍔 🍕 🍖 🍗 🍘 🍙 🍚 🍛 🍜 " +
            "🍝 🍞 🍟 🍠 🍡 🍢 🍣 🍤 🍥 🍦 🍧 🍨 🍩 🍪 🍫 🍬 🍭 🍮 🍯 🍰 " +
            "🍱 🍲 🍳 🍴 🍵 🍶 🍷 🍸 🍹 🍺 🍻 🎀 🎁 🎂 🎃 🎄 🎅 🎆 🎇 🎈 " +
            "🎉 🎊 🎋 🎌 🎍 🎎 🎏 🎐 🎑 🎒 🎓 🎠 🎡 🎢 🎣 🎤 🎥 🎦 🎧 🎨 " +
            "🎩 🎪 🎫 🎬 🎭 🎮 🎯 🎰 🎱 🎲 🎳 🎴 🎵 🎶 🎷 🎸 🎹 🎺 🎻 🎼 " +
            "🎽 🎾 🎿 🏀 🏁 🏂 🏃 🏄 🏆 🏈 🏊 🏠 🏡 🏢 🏣 🏥 🏦 🏧 🏨 🏩 " +
            "🏪 🏫 🏬 🏭 🏮 🏯 🏰 🐌 🐍 🐎 🐑 🐒 🐔 🐗 🐘 🐙 🐚 🐛 🐜 🐝 " +
            "🐞 🐟 🐠 🐡 🐢 🐣 🐤 🐥 🐦 🐧 🐨 🐩 🐫 🐬 🐭 🐮 🐯 🐰 🐱 🐲 " +
            "🐳 🐴 🐵 🐶 🐷 🐸 🐹 🐺 🐻 🐼 🐽 🐾 👀 👂 👃 👄 👅 👆 👇 👈 " +
            "👉 👊 👋 👌 👍 👎 👏 👐 👑 👒 👓 👔 👕 👖 👗 👘 👙 👚 👛 👜 " +
            "👝 👞 👟 👠 👡 👢 👣 👤 👦 👧 👨 👩 👪 👫 👮 👯 👰 👴 👵 👶 " +
            "👷 👸 👹 👺 👻 👼 👽 👾 👿 💀 💁 💂 💃 💄 💅 💆 💇 💈 💉 💊 " +
            "💋 💌 💍 💎 💏 💐 💑 💒 💓 💔 💕 💖 💗 💘 💙 💚 💛 💜 💝 💞 " +
            "💟 💠 💡 💢 💣 💤 💥 💦 💧 💨 💩 💪 💫 💬 💮 💯 💰 💱 💲 💳 " +
            "💴 💵 💸 💹 💺 💻 💼 💽 💾 💿 📀 📁 📂 📃 📄 📅 📆 📇 📈 📉 " +
            "📊 📋 📌 📍 📎 📏 📐 📑 📒 📓 📔 📕 📖 📗 📘 📙 📚 📛 📜 📝 " +
            "📞 📟 📠 📡 📢 📣 📤 📥 📦 📧 📨 📩 📪 📫 📮 📰 📱 📲 📳 📴 " +
            "📶 📷 📹 📺 📻 📼 🔃 🔊 🔋 🔌 🔍 🔎 🔏 🔐 🔑 🔒 🔓 🔔 🔖 🔗 " +
            "🔘 🔙 🔚 🔛 🔜 🔝 🔞 🔟 🔠 🔡 🔢 🔣 🔤 🔥 🔦 🔧 🔨 🔩 🔪 🔫 " +
            "🔮 🔯 🔰 🔱 🔲 🔳 🔴 🔵 🔶 🔷 🔸 🔹 🔺 🔻 🔼 🔽 🕐 🕑 🕒 🕓 " +
            "🕔 🕕 🕖 🕗 🕘 🕙 🕚 🕛 🗻 🗼 🗽 🗾 🗿 🚀 🚃 🚄 🚅 🚇 🚉 🚌 " +
            "🚏 🚑 🚒 🚓 🚕 🚗 🚙 🚚 🚢 🚤 🚥 🚧 🚨 🚩 🚪 🚫 🚬 🚭 🚲 🚶 " +
            "🚹 🚺 🚻 🚼 🚽 🚾 🛀 ↔ ↕ ↖ ↗ ↘ ↙ ↩ ↪ ⌚ ⌛ ⏩ ⏪ ⏫ " +
            "⏬ ⏰ ⏳ Ⓜ ▪ ▫ ▶ ◀ ◊ ◻ ◼ ◽ ◾ ☀ ☁ ☺ ♈ ♉ ♊ ♋ " +
            "♌ ♍ ♎ ♏ ♐ ♑ ♒ ♓ ♠ ♣ ♥ ♦ ♨ ♻ ♿ ⚓ ⚠ ⚡ ⚪ ⚫ " +
            "⚽ ⚾ ⛄ ⛅ ⛎ ⛔ ⛪ ⛲ ⛳ ⛵ ⛺ ⛽ ✂ ✅ ✈ ✉ ✊ ✋ ✌ ✏ " +
            "✒ ✔ ✖ ✨ ✳ ✴ ❄ ❇ ❌ ❎ ❓ ❔ ❕ ❗ ❤ ➕ ➖ ➗ ➡ ➰ " +
            "➿ ⤴ ⤵ ⬅ ⬆ ⬇ ⬛ ⬜ ⭐ ⭕ 〰 〽 ㊗ ㊙ ﻿ ￼ � 🀄 🃏 🅰 " +
            "🅱 🅾 🅿 🆎 🆑 🆒 🆓 🆔 🆕 🆖 🆗 🆘 🆙 🆚 🈁 🈂 🈚 🈯 🈲 🈳 " +
            "🈴 🈵 🈶 🈷 🈸 🈹 🈺 🉐 🉑 ";

    private String[] pinnedReactions() {
        String[] pins = prefs.getString("pinned_reactions", DEFAULT_PINS).split(" ");
        return pins.length > 0 ? pins : DEFAULT_PINS.split(" ");
    }

    private void showEmojiPicker(String title, final EmojiSink sink) {
        final String[] all = EMOJI_SET.split("\\s+");
        android.widget.GridView grid = new android.widget.GridView(this);
        grid.setNumColumns(8);
        grid.setPadding(dpC(8), dpC(8), dpC(8), dpC(8));
        grid.setAdapter(new android.widget.BaseAdapter() {
            @Override public int getCount() { return all.length; }
            @Override public Object getItem(int i) { return all[i]; }
            @Override public long getItemId(int i) { return i; }
            @Override public android.view.View getView(int i, android.view.View cv,
                                                       android.view.ViewGroup parent) {
                android.widget.TextView tv = cv instanceof android.widget.TextView
                        ? (android.widget.TextView) cv : new android.widget.TextView(Chat.this);
                tv.setText(all[i]);
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 24);
                tv.setGravity(android.view.Gravity.CENTER);
                tv.setPadding(0, dpC(8), 0, dpC(8));
                return tv;
            }
        });
        final androidx.appcompat.app.AlertDialog d =
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle(title)
                        .setView(grid)
                        .setNegativeButton("Cancel", null)
                        .create();
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            d.dismiss();
            sink.onPick(all[pos]);
        });
        d.show();
    }

    private int dpC(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showEditDialog(MessageItem m) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setText(m.text);
        input.setSelection(m.text == null ? 0 : m.text.length());
        new androidx.appcompat.app.AlertDialog.Builder(this)
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
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Edit history")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show();
        } catch (Exception ignored) {}
    }

    private void sendEdit(MessageItem original, String newText) {
        new Thread(() -> {
            try {
                MessageSender sender = new MessageSender(baseSignal, myNumber, sendRecipient);
                JSONObject body = sender.body(newText);
                long editTs = original.lastEditTs > 0 ? original.lastEditTs : original.serverTs;
                MessageSender.asEdit(body, editTs);
                String tsRaw = sender.send(body, 8000);
                final long newEditTs = tsRaw == null ? 0 : parseLongSafe(tsRaw);
                final long prevTs = editTs;
                runOnUiThread(() -> {
                    if (tsRaw != null) {
                        repo.applyLocalEdit(chatDbKey, prevTs, newText, newEditTs);
                        repo.reportEdit(chatDbKey, prevTs, newText, newEditTs);
                    } else {
                        Toast.makeText(Chat.this, "Edit failed", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception ignored) {}
        }).start();
    }

    private void confirmRemoteDelete(MessageItem m) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Delete this message for everyone?")
                .setPositiveButton("Delete", (d, w) -> new Thread(() -> {
                    try {
                        String peer = sendRecipient;
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
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setMessage("Delete this message?")
                .setPositiveButton("Delete", (d, w) ->
                        repo.deleteLocal(chatDbKey, java.util.Collections.singletonList(ts)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteSelected() {
        int count = selectedTs.size();
        new androidx.appcompat.app.AlertDialog.Builder(this)
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
                String peer = sendRecipient;
                String targetAuthor;
                if ("me".equals(target.from)) {
                    targetAuthor = notEmpty(myNumber) ? myNumber : peer;
                } else if (isGroup && notEmpty(target.author)) {
                    // group rows know their author (digits key or uuid)
                    targetAuthor = target.author.matches("\\d+")
                            ? "+" + target.author : target.author;
                } else {
                    targetAuthor = peer;
                }
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
