package com.example.signalberry;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static com.example.signalberry.Utils.*;

/**
 * The single writer. Every envelope (from MessageService's WebSocket), every
 * bridge change-feed row, and every local send funnels through here — the
 * activities only read the DB and listen for granular change events.
 *
 * Envelope shapes are pinned from live capture 2026-06-11 (see REDESIGN.md M1):
 * /v2/send returns {"timestamp": "<string>"}; receiptMessage carries
 * timestamps[]; readMessages carries sender/senderNumber/senderUuid/timestamp;
 * reaction carries targetAuthor(+Number/Uuid)/targetSentTimestamp/isRemove.
 */
final class Repo {

    interface Listener {
        /** A new message row appeared in this thread. */
        void onItemInserted(String peerKey);
        /** An existing row changed (status/reaction/edit/delete). */
        void onItemChanged(String peerKey, long serverTs);
        /** Ephemeral, non-persisted event: "typing_started" | "typing_stopped". */
        void onEphemeral(String peerKey, String kind);
    }

    private static Repo instance;

    static synchronized Repo get(Context ctx) {
        if (instance == null) instance = new Repo(ctx.getApplicationContext());
        return instance;
    }

    /** Purge all message data: bridge first (abort everything if that fails),
     *  then the local DB, attachment store, and per-peer watermarks. Login,
     *  contacts, and settings survive; the phone's history is never touched.
     *  Runs blocking — call off the main thread. @return null on success,
     *  else a short error message. */
    String purgeAllData(Context ctx) {
        try {
            String base = prefs.getString("bridge", "");
            if (isEmpty(base)) return "No bridge configured";
            int code = httpPostJson(base + "/v2/purge", "{\"confirm\":\"purge\"}");
            if (code < 200 || code >= 300) return "Bridge purge failed (" + code + ")";
        } catch (Exception e) {
            return "Bridge unreachable, nothing deleted";
        }
        synchronized (writeLock) {
            db.getWritableDatabase().execSQL("DELETE FROM messages");
            // purge means the BYTES are gone, not just the rows — without
            // VACUUM the content lingers in SQLite free pages
            db.getWritableDatabase().execSQL("VACUUM");
        }
        java.io.File att = new java.io.File(ctx.getFilesDir(), "att");
        java.io.File[] files = att.listFiles();
        if (files != null) for (java.io.File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
        SharedPreferences.Editor ed = prefs.edit();
        for (String k : prefs.getAll().keySet())
            if (k.startsWith("read_ts_") || k.startsWith("notified_ts_")
                    || k.startsWith("receipted_ts_") || k.startsWith("thread_cleared_ts_")
                    || k.startsWith("notif_count_"))
                ed.remove(k);
        // cursor back to 0 is safe: the bridge preserves its mod_seq counter
        ed.putLong("bridge_seq", 0).putBoolean("reconcile_done", true).apply();
        android.app.NotificationManager nm = (android.app.NotificationManager)
                ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancelAll();
        main.post(() -> { synchronized (listeners) {
            for (Listener l : listeners) l.onItemInserted(""); } });
        return null;
    }

    /** Per-chat purge: bridge first (abort on failure), then the local thread.
     *  Blocking — call off the main thread. @return null on success. */
    String purgeThread(String peerKey) {
        try {
            String base = prefs.getString("bridge", "");
            if (isEmpty(base)) return "No bridge configured";
            JSONObject o = new JSONObject();
            o.put("confirm", "purge");
            o.put("peer", peerKey);
            int code = httpPostJson(base + "/v2/purge", o.toString());
            if (code < 200 || code >= 300) return "Bridge purge failed (" + code + ")";
        } catch (Exception e) {
            return "Bridge unreachable, nothing deleted";
        }
        deleteThread(peerKey);
        synchronized (writeLock) {
            db.getWritableDatabase().execSQL("VACUUM");
        }
        return null;
    }

    /** Logout: drop the singleton so re-login gets a fresh DB handle/identity. */
    static synchronized void reset() {
        if (instance != null) {
            try { instance.db.close(); } catch (Exception ignored) {}
            instance.io.shutdown();
            instance.reportIo.shutdown();
            instance = null;
        }
    }

    final MessageDatabase db;
    private final PeerKeys peerKeys;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    /** Data-integrity work (re-keys, init) — must never starve behind network calls. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Bridge report traffic (blocking HTTP with retries) — separate lane. */
    private final ExecutorService reportIo = Executors.newSingleThreadExecutor();
    private final AtomicInteger nonceCounter = new AtomicInteger();
    private final Object writeLock = new Object();
    private final Object catchUpLock = new Object();
    private final Object readTsLock = new Object();
    /** Receipts that raced ahead of their send confirmation (peer|ts → status). */
    private final java.util.Map<String, Integer> earlyReceipts = new HashMap<>();

    private String selfNumber = "";
    private String selfUuid = "";

    private Repo(Context ctx) {
        db = new MessageDatabase(ctx);
        peerKeys = PeerKeys.get(ctx);
        prefs = ctx.getSharedPreferences("signalberry", Context.MODE_PRIVATE);
        selfNumber = digits(prefs.getString("number", ""));
        selfUuid = PeerKeys.normalize(prefs.getString("self_uuid", ""));
        peerKeys.setListener(new PeerKeys.Listener() {
            @Override public void onMappingLearned(String uuidKey, String numberKey) {
                io.execute(() -> rekeyWithPrefs(uuidKey, numberKey));
            }
        });
        sweepExpiry();
        io.execute(() -> {
            synchronized (writeLock) { db.failStalePendings(10 * 60_000L); }
            // re-keys for mappings learned before this Repo (and its listener)
            // existed would otherwise be permanently lost — replay them all;
            // rekeyPeer is idempotent and cheap when nothing matches the old key
            for (Map.Entry<String, String> e : peerKeys.allMappings().entrySet())
                rekeyWithPrefs(e.getKey(), e.getValue());
            drainReportQueue();
        });
    }

    private void rekeyWithPrefs(String uuidKey, String numberKey) {
        synchronized (writeLock) { db.rekeyPeer(uuidKey, numberKey); }
        synchronized (readTsLock) {
            SharedPreferences.Editor ed = prefs.edit();
            for (String fam : new String[]{"read_ts_", "notified_ts_",
                    "thread_cleared_ts_", "receipted_ts_"}) {
                long u = prefs.getLong(fam + uuidKey, 0);
                if (u > prefs.getLong(fam + numberKey, 0)) ed.putLong(fam + numberKey, u);
                ed.remove(fam + uuidKey);
            }
            if (prefs.getBoolean("mute_" + uuidKey, false)) ed.putBoolean("mute_" + numberKey, true);
            ed.remove("mute_" + uuidKey);
            String alias = prefs.getString("alias_" + uuidKey, "");
            if (notEmpty(alias) && isEmpty(prefs.getString("alias_" + numberKey, "")))
                ed.putString("alias_" + numberKey, alias);
            ed.remove("alias_" + uuidKey);
            ed.apply();
        }
        notifyInserted(numberKey);
    }

    /** The only writer of read_ts_ prefs — three components used to race here. */
    void advanceReadTs(String peerKey, long ts) {
        if (isEmpty(peerKey) || ts <= 0) return;
        boolean advanced = false;
        synchronized (readTsLock) {
            if (ts > prefs.getLong("read_ts_" + peerKey, 0)) {
                prefs.edit().putLong("read_ts_" + peerKey, ts).apply();
                advanced = true;
            }
            // read implies notified — a reconnect must not re-notify read messages
            if (ts > prefs.getLong("notified_ts_" + peerKey, 0))
                prefs.edit().putLong("notified_ts_" + peerKey, ts).apply();
        }
        if (advanced) sweepExpiry();
    }

    void advanceNotifiedTs(String peerKey, long ts) {
        if (isEmpty(peerKey) || ts <= 0) return;
        synchronized (readTsLock) {
            if (ts > prefs.getLong("notified_ts_" + peerKey, 0))
                prefs.edit().putLong("notified_ts_" + peerKey, ts).apply();
        }
    }

    /** Honor disappearing-message timers (REDESIGN exception now closed app-side;
     *  the bridge's copy is a documented follow-up). */
    private volatile long lastSweepMs = 0;

    void sweepExpiry() {
        long now = System.currentTimeMillis();
        if (now - lastSweepMs < 30_000) return; // debounce: full-table scans are not free on a Q10
        lastSweepMs = now;
        io.execute(() -> {
            try {
                Map<String, Long> readTs = new HashMap<>();
                for (Map.Entry<String, ?> e : prefs.getAll().entrySet())
                    if (e.getKey().startsWith("read_ts_") && e.getValue() instanceof Long)
                        readTs.put(e.getKey().substring(8), (Long) e.getValue());
                java.util.Set<String> affected;
                synchronized (writeLock) { affected = db.sweepExpiry(readTs); }
                for (String pk : affected) notifyInserted(pk);
            } catch (Exception e) {
                DebugLog.log("expiry sweep: " + e);
            }
        });
    }

    void setSelf(String number, String uuid) {
        if (notEmpty(number)) selfNumber = digits(number);
        if (notEmpty(uuid))   selfUuid = PeerKeys.normalize(uuid);
    }

    // ── listeners ─────────────────────────────────────────────────────────────

    void addListener(Listener l)    { synchronized (listeners) { listeners.add(l); } }
    void removeListener(Listener l) { synchronized (listeners) { listeners.remove(l); } }

    private void notifyInserted(String peerKey) {
        main.post(() -> { synchronized (listeners) {
            for (Listener l : listeners) l.onItemInserted(peerKey); } });
    }

    private void notifyChanged(String peerKey, long serverTs) {
        main.post(() -> { synchronized (listeners) {
            for (Listener l : listeners) l.onItemChanged(peerKey, serverTs); } });
    }

    private void notifyEphemeral(String peerKey, String kind) {
        main.post(() -> { synchronized (listeners) {
            for (Listener l : listeners) l.onEphemeral(peerKey, kind); } });
    }

    // ── envelope ingestion (single parser — replaces Chat/Messages/Service triplication) ──

    /** What the service needs for its notification decision. */
    static final class IngestResult {
        final String peerKey;
        final String dir;        // 'in' | 'out'
        final boolean newMessage; // a visible message row was inserted
        final String snippet;
        IngestResult(String peerKey, String dir, boolean newMessage, String snippet) {
            this.peerKey = peerKey; this.dir = dir;
            this.newMessage = newMessage; this.snippet = snippet;
        }
    }

    /** @return result for message envelopes, null for everything else
     *  (receipts, typing, markers, reactions, deletes, edits). */
    IngestResult ingest(JSONObject envelope) {
        try {
            return ingestInner(envelope);
        } catch (Exception e) {
            DebugLog.log("repo ingest failed: " + e);
            return null;
        }
    }

    private IngestResult ingestInner(JSONObject env) throws Exception {
        String srcNum  = safeOptString(env, "sourceNumber");
        String srcUuid = safeOptString(env, "sourceUuid");
        long envTs = env.optLong("timestamp", 0);
        if (notEmpty(srcNum) && notEmpty(srcUuid)) peerKeys.learn(srcUuid, srcNum);

        JSONObject data = env.optJSONObject("dataMessage");
        JSONObject sync = env.optJSONObject("syncMessage");
        JSONObject receipt = env.optJSONObject("receiptMessage");
        JSONObject typing = env.optJSONObject("typingMessage");

        if (data != null) {
            JSONObject gi = data.optJSONObject("groupInfo");
            if (gi == null) gi = nestedGroupInfo(data);
            String gid = gi != null ? gi.optString("groupId", "") : "";
            if (gi != null && isEmpty(gid)) return null;
            String sender = peerKeys.resolve(srcNum, srcUuid);
            String peer = notEmpty(gid) ? "group:" + gid : sender;
            if (isEmpty(peer)) return null;
            return ingestDataMessage(peer, "in", data, envTs, /*selfAuthored*/ false,
                    notEmpty(gid) ? sender : "");
        }

        if (sync != null) {
            JSONObject sent = sync.optJSONObject("sentMessage");
            if (sent != null) {
                JSONObject sgi = sent.optJSONObject("groupInfo");
                if (sgi == null) sgi = nestedGroupInfo(sent);
                String sgid = sgi != null ? sgi.optString("groupId", "") : "";
                if (sgi != null && isEmpty(sgid)) return null;
                String destNum  = firstNonEmpty(safeOptString(sent, "destinationNumber"),
                                                safeOptString(sent, "destination"));
                String destUuid = safeOptString(sent, "destinationUuid");
                if (notEmpty(destNum) && notEmpty(destUuid)) peerKeys.learn(destUuid, destNum);
                String peer = notEmpty(sgid) ? "group:" + sgid : peerKeys.resolve(destNum, destUuid);
                if (isEmpty(peer)) return null;
                long sentTs = sent.optLong("timestamp", envTs);
                if (sentTs <= 0) return null;
                return ingestDataMessage(peer, "out", sent, sentTs, true, "");
            }
            JSONArray readMsgs = sync.optJSONArray("readMessages");
            if (readMsgs != null && readMsgs.length() > 0) {
                String lastPeer = "";
                for (int i = 0; i < readMsgs.length(); i++) {
                    JSONObject rm = readMsgs.optJSONObject(i);
                    if (rm == null) continue;
                    String peer = peerKeys.resolve(
                            firstNonEmpty(safeOptString(rm, "senderNumber"), safeOptString(rm, "sender")),
                            safeOptString(rm, "senderUuid"));
                    long ts = rm.optLong("timestamp", 0);
                    if (isEmpty(peer) || ts <= 0) continue;
                    advanceReadTs(peer, ts);
                    lastPeer = peer;
                }
                if (notEmpty(lastPeer)) notifyChanged(lastPeer, 0);
                return null; // marker only — never notification-worthy
            }
            return null;
        }

        if (receipt != null) {
            String peer = peerKeys.resolve(srcNum, srcUuid);
            int newStatus = receipt.optBoolean("isDelivery") ? MessageDatabase.ST_DELIVERED
                    : (receipt.optBoolean("isRead") || receipt.optBoolean("isViewed"))
                        ? MessageDatabase.ST_READ : 0;
            if (newStatus == 0 || isEmpty(peer)) return null;
            JSONArray tss = receipt.optJSONArray("timestamps");
            if (tss != null) {
                synchronized (writeLock) {
                    for (int i = 0; i < tss.length(); i++) {
                        long ts = tss.optLong(i, 0);
                        if (ts <= 0) continue;
                        if (!db.applyReceipt(peer, ts, newStatus)) {
                            // raced ahead of confirmSend — stash for replay
                            String k = peer + "|" + ts;
                            Integer prev = earlyReceipts.get(k);
                            if (prev == null || prev < newStatus) earlyReceipts.put(k, newStatus);
                        }
                    }
                }
                notifyChanged(peer, 0);
            }
            return null; // receipts are silent
        }

        if (typing != null) {
            String peer = peerKeys.resolve(srcNum, srcUuid);
            String tgid = typing.optString("groupId", "");
            if (notEmpty(tgid)) peer = "group:" + tgid;
            if (notEmpty(peer))
                notifyEphemeral(peer, "STARTED".equals(typing.optString("action"))
                        ? "typing_started" : "typing_stopped");
            return null;
        }
        return null;
    }

    /** Edit envelopes nest the group marker inside the new revision. */
    private static JSONObject nestedGroupInfo(JSONObject msg) {
        JSONObject em = msg.optJSONObject("editMessage");
        if (em == null) return null;
        JSONObject inner = em.optJSONObject("dataMessage");
        return inner != null ? inner.optJSONObject("groupInfo") : null;
    }

    /** Shared shaping for dataMessage and syncMessage.sentMessage. */
    private IngestResult ingestDataMessage(String peer, String dir, JSONObject msg,
                                           long ts, boolean selfAuthored) throws Exception {
        return ingestDataMessage(peer, dir, msg, ts, selfAuthored, "");
    }

    private IngestResult ingestDataMessage(String peer, String dir, JSONObject msg,
                                           long ts, boolean selfAuthored,
                                           String author) throws Exception {
        // reaction? ("me"/"peer" in 1:1 chats; "peer:<sender>" in groups so
        // multiple members' reactions don't clobber each other
        JSONObject reaction = msg.optJSONObject("reaction");
        if (reaction != null) {
            long targetTs = reaction.optLong("targetSentTimestamp", 0);
            String reactorKey = selfAuthored ? "me"
                    : notEmpty(author) ? "peer:" + author : "peer";
            synchronized (writeLock) {
                db.updateReaction(peer, targetTs, reactorKey,
                        reaction.optString("emoji", ""), reaction.optBoolean("isRemove"));
            }
            notifyChanged(peer, targetTs);
            return null;
        }

        // remote delete?
        JSONObject rd = msg.optJSONObject("remoteDelete");
        if (rd != null) {
            long targetTs = rd.optLong("timestamp", 0);
            if (targetTs > 0) {
                synchronized (writeLock) { db.remoteDeleteByServerTs(peer, targetTs); }
                notifyChanged(peer, targetTs);
            }
            return null;
        }

        // edit?
        JSONObject em = msg.optJSONObject("editMessage");
        if (em != null) {
            long targetTs = em.optLong("targetSentTimestamp", 0);
            JSONObject inner = em.optJSONObject("dataMessage");
            String newText = inner != null ? safeOptString(inner, "message") : "";
            if (targetTs > 0 && notEmpty(newText)) {
                synchronized (writeLock) { db.applyEdit(peer, targetTs, newText, ts); }
                notifyChanged(peer, targetTs);
            }
            return null;
        }

        String text = safeOptString(msg, "message");
        JSONArray mentions = msg.optJSONArray("mentions");
        if (mentions != null && mentions.length() > 0 && text.indexOf('\uFFFC') >= 0) {
            // bake mentions into readable text (cheapest Q10-safe rendering)
            StringBuilder sb = new StringBuilder(text);
            for (int i = mentions.length() - 1; i >= 0; i--) {
                JSONObject men = mentions.optJSONObject(i);
                if (men == null) continue;
                int start = men.optInt("start", -1);
                int len = men.optInt("length", 0);
                if (start < 0 || start + len > sb.length()) continue;
                String name = firstNonEmpty(men.optString("name", ""),
                        men.optString("number", ""), "mention");
                sb.replace(start, start + len, "@" + name);
            }
            text = sb.toString();
        }
        JSONArray atts = msg.optJSONArray("attachments");
        JSONObject quote = msg.optJSONObject("quote");
        long quoteTs = quote != null ? quote.optLong("id", 0) : 0;
        String quoteText = quote != null ? safeOptString(quote, "text") : null;
        String quoteAuthorRaw = quote != null ? PeerKeys.normalize(firstNonEmpty(
                quote.optString("authorNumber", ""), quote.optString("author", ""),
                quote.optString("authorUuid", ""))) : "";
        String quoteAuthor = isEmpty(quoteAuthorRaw) ? null
                : (quoteAuthorRaw.equals(selfNumber) || quoteAuthorRaw.equals(selfUuid)) ? "me" : "peer";

        int status = "in".equals(dir) ? MessageDatabase.ST_DELIVERED : MessageDatabase.ST_SENT;
        // Note-to-Self echoes are delivered BY DEFINITION (we just received one
        // on this device, and self-thread messages never get delivery receipts)
        if ("out".equals(dir) && peer.equals(selfNumber)) status = MessageDatabase.ST_DELIVERED;
        boolean inserted = false;
        String snippet = text;
        synchronized (writeLock) {
            if (atts != null && atts.length() > 0) {
                boolean first = true;
                for (int i = 0; i < atts.length(); i++) {
                    JSONObject att = atts.optJSONObject(i);
                    if (att == null) continue;
                    String attId = safeOptString(att, "id");
                    if (isEmpty(attId)) continue;
                    String mime = safeOptString(att, "contentType");
                    if (first && isEmpty(snippet)) snippet = mediaSnippet(kindFromMime(mime));
                    // a locally-sent attachment row may exist with att_id='' — adopt it
                    boolean adopted = "out".equals(dir) && adoptLocalAttachmentRow(peer, ts, attId, mime);
                    if (!adopted) {
                        long id = db.upsertByIdentity(peer, dir, kindFromMime(mime),
                                "", attId, mime, first ? emptyToNull(text) : null, null,
                                ts, status, quoteTs, quoteText, quoteAuthor, author);
                        inserted |= id != -1;
                    }
                    first = false;
                }
            } else if (notEmpty(text)) {
                long id = db.upsertByIdentity(peer, dir, "text", text, "", "", null, null,
                        ts, status, quoteTs, quoteText, quoteAuthor, author);
                inserted = id != -1;
            } else {
                return null; // nothing visible (e.g. expiration-timer update)
            }
        }
        int expireS = msg.optInt("expiresInSeconds", 0);
        if (expireS > 0) {
            synchronized (writeLock) { db.setExpiry(peer, dir, ts, expireS); }
        }
        if (inserted) notifyInserted(peer);
        else notifyChanged(peer, ts);
        return new IngestResult(peer, dir, inserted, snippet);
    }

    static String mediaSnippet(String kind) {
        if ("video".equals(kind)) return "🎥 Video";
        if ("audio".equals(kind)) return "🎤 Audio";
        if ("file".equals(kind))  return "📎 File";
        return "📷 Photo";
    }

    /** Sent-image identity merge (REDESIGN §3.2): our own confirmed send sits at
     *  (peer,out,ts,att_id='') with a local_uri; the phone-echo/bridge row for the
     *  same send carries the real att_id. Adopt it instead of inserting a twin. */
    private boolean adoptLocalAttachmentRow(String peer, long ts, String attId, String mime) {
        android.database.sqlite.SQLiteDatabase d = db.getWritableDatabase();
        android.database.Cursor c = d.rawQuery(
                "SELECT id, local_uri FROM messages WHERE peer_key=? AND dir='out' AND server_ts=? AND att_id='' " +
                "AND msg_type!='text'",
                new String[]{peer, String.valueOf(ts)});
        if (!c.moveToFirst()) { c.close(); return false; }
        long id = c.getLong(0);
        String localUri = c.isNull(1) ? null : c.getString(1);
        c.close();
        // occupancy guard: if the target identity already exists (redelivered
        // row inserted before we adopted), merge instead — a blind UPDATE here
        // would violate the UNIQUE identity index
        android.database.Cursor occ = d.rawQuery(
                "SELECT id FROM messages WHERE peer_key=? AND dir='out' AND server_ts=? AND att_id=?",
                new String[]{peer, String.valueOf(ts), attId});
        if (occ.moveToFirst()) {
            long occId = occ.getLong(0);
            occ.close();
            if (localUri != null)
                d.execSQL("UPDATE messages SET local_uri=COALESCE(local_uri,?) WHERE id=?",
                        new Object[]{localUri, occId});
            d.execSQL("DELETE FROM messages WHERE id=" + id);
        } else {
            occ.close();
            d.execSQL("UPDATE messages SET att_id=?, mime=? WHERE id=?",
                    new Object[]{attId, mime, id});
        }
        return true;
    }

    static String kindFromMime(String mime) {
        if (mime == null) return "file";
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("video/")) return "video";
        if (mime.startsWith("audio/")) return "audio";
        return "file";
    }

    private static String safeOptString(JSONObject o, String key) {
        return o == null || o.isNull(key) ? "" : o.optString(key, "");
    }

    private static String emptyToNull(String s) { return isEmpty(s) ? null : s; }

    // ── send pipeline ─────────────────────────────────────────────────────────

    interface SendCallback { void onResult(boolean ok, long serverTs); }

    long newNonce() {
        return (System.currentTimeMillis() << 8) | (nonceCounter.incrementAndGet() & 0xFF);
    }

    /** Insert the pending row and return its nonce; caller renders optimistically. */
    long beginSend(String peerKey, String msgType, String text, String mime,
                   String caption, String localUri,
                   long quoteTs, String quoteText, String quoteAuthor) {
        long nonce;
        synchronized (writeLock) {
            nonce = db.insertPending(peerKey, msgType, text, mime, caption, localUri,
                    newNonce(), quoteTs, quoteText, quoteAuthor);
        }
        notifyInserted(peerKey);
        return nonce;
    }

    /** Confirm with the /v2/send response ts (string-or-number, M1). */
    void confirmSend(String peerKey, long nonce, String tsRaw, String kind,
                     String body, String attId, String mime,
                     long quoteTs, String quoteText, String quoteAuthor) {
        long ts = parseLongSafe(tsRaw == null ? "" : tsRaw.replace("\"", "").trim());
        if (ts <= 0) { failSend(peerKey, nonce); return; }
        synchronized (writeLock) {
            db.confirmPendingByNonce(nonce, ts, MessageDatabase.ST_SENT);
            // a receipt may have arrived while the row was still pending
            Integer early = earlyReceipts.remove(peerKey + "|" + ts);
            if (early != null) db.applyReceipt(peerKey, ts, early);
        }
        notifyChanged(peerKey, ts);
        reportSent(peerKey, kind, body, ts, attId, mime, quoteTs, quoteText, quoteAuthor);
    }

    void failSend(String peerKey, long nonce) {
        synchronized (writeLock) { db.markFailedByNonce(nonce); }
        notifyChanged(peerKey, -nonce);
    }

    // ── bridge /v2/sent report queue (DB-backed via the reported flag) ────────

    private void reportSent(String peerKey, String kind, String body, long ts,
                            String attId, String mime,
                            long quoteTs, String quoteText, String quoteAuthor) {
        reportIo.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("peer", peerKey);
                o.put("kind", kind == null ? "text" : kind);
                o.put("body", body == null ? "" : body);
                o.put("server_ts", ts);
                if (notEmpty(attId)) { o.put("att_id", attId); o.put("mime", mime); }
                if (quoteTs > 0) {
                    o.put("quote_ts", quoteTs);
                    o.put("quote_text", quoteText);
                    o.put("quote_author", quoteAuthor);
                }
                String base = prefs.getString("bridge", "");
                if (isEmpty(base)) return;
                int code = httpPostJson(base + "/v2/sent", o.toString());
                if (code >= 200 && code < 300) {
                    synchronized (writeLock) { db.markReported(peerKey, ts); }
                }
            } catch (Exception e) {
                DebugLog.log("report-sent failed (will retry on init): " + e);
            }
        });
    }

    /** Re-report confirmed-but-unreported sends (Repo init + reconnect). */
    void drainReportQueue() {
        try {
            List<MessageItem> unreported = db.getUnreported();
            for (MessageItem it : unreported) {
                reportSent(it.peerKey, it.msgType,
                        it.type == MessageItem.TYPE_TEXT ? it.text : (it.caption == null ? "" : it.caption),
                        it.serverTs, it.attachmentId, it.mime,
                        it.quoteTs, it.quoteText, it.quoteAuthor);
            }
        } catch (Exception e) {
            DebugLog.log("drain report queue: " + e);
        }
    }

    // ── local actions from the UI (the activities never write the DB directly) ──

    void sendLocalReaction(String peerKey, long targetTs, String emoji, boolean isRemove) {
        synchronized (writeLock) {
            db.updateReaction(peerKey, targetTs, "me", emoji, isRemove);
        }
        notifyChanged(peerKey, targetTs);
    }

    void applyLocalEdit(String peerKey, long prevTs, String newText, long newEditTs) {
        synchronized (writeLock) {
            db.applyEdit(peerKey, prevTs, newText, newEditTs);
        }
        notifyChanged(peerKey, prevTs);
    }

    /** Own remote-delete (app-originated): placeholder locally + tell the bridge
     *  (no self-echo exists to carry it there). */
    void remoteDeleteLocal(String peerKey, long serverTs) {
        synchronized (writeLock) {
            db.remoteDeleteByServerTs(peerKey, serverTs);
        }
        notifyChanged(peerKey, serverTs);
        reportIo.execute(() -> {
            try {
                String base = prefs.getString("bridge", "");
                if (isEmpty(base)) return;
                JSONObject o = new JSONObject();
                o.put("peer", peerKey);
                o.put("server_ts", serverTs);
                o.put("deleted", 1);
                httpPostJson(base + "/v2/sent", o.toString());
            } catch (Exception e) {
                DebugLog.log("remote-delete report failed: " + e);
            }
        });
    }

    void deleteLocal(String peerKey, java.util.Collection<Long> timestamps) {
        synchronized (writeLock) {
            db.deleteMessages(peerKey, timestamps);
        }
        notifyChanged(peerKey, 0);
    }

    /** Send read receipts for newly-read incoming messages, via the bridge's
     *  server-side fan-out. Gated: settings toggle (default OFF — the user's
     *  Signal account has read receipts disabled), never for the self-thread.
     *  Tracks a per-peer high-water mark so each ts is receipted once. */
    void queueReadReceipts(String peerKey, String recipient, List<MessageItem> thread) {
        if (peerKey != null && peerKey.startsWith("group:")) return;
        if (!prefs.getBoolean("send_read_receipts", false)) return;
        if (isEmpty(recipient) || isEmpty(peerKey)) return;
        if (peerKey.equals(selfNumber) || PeerKeys.normalize(recipient).equals(selfNumber)) return;
        long mark = prefs.getLong("receipted_ts_" + peerKey, 0);
        final java.util.ArrayList<Long> ts = new java.util.ArrayList<>();
        long newMark = mark;
        for (MessageItem m : thread) {
            if (!"peer".equals(m.from) || m.serverTs <= mark) continue;
            ts.add(m.serverTs);
            if (m.serverTs > newMark) newMark = m.serverTs;
        }
        if (ts.isEmpty()) return;
        while (ts.size() > 25) ts.remove(0); // cap: newest 25
        final long markToStore = newMark;
        reportIo.execute(() -> {
            try {
                String base = prefs.getString("bridge", "");
                if (isEmpty(base)) return;
                JSONObject o = new JSONObject();
                o.put("peer", peerKey);
                o.put("recipient", recipient);
                o.put("timestamps", new JSONArray(ts));
                int code = httpPostJson(base + "/v2/read-receipts", o.toString());
                if (code >= 200 && code < 300) {
                    synchronized (readTsLock) {
                        if (markToStore > prefs.getLong("receipted_ts_" + peerKey, 0))
                            prefs.edit().putLong("receipted_ts_" + peerKey, markToStore).apply();
                    }
                }
            } catch (Exception e) {
                DebugLog.log("read-receipt queue failed: " + e);
            }
        });
    }

    List<MessageItem> getThread(String peerKey) { return db.getMessages(peerKey); }

    List<MessageItem> getThreadFull(String peerKey) { return db.getMessages(peerKey, 0); }

    /** App-originated edit: tell the bridge (no self-echo) so edit-revision
     *  receipts find their target and other clients of the feed see the text. */
    void reportEdit(String peerKey, long targetTs, String newText, long editTs) {
        reportIo.execute(() -> {
            try {
                String base = prefs.getString("bridge", "");
                if (isEmpty(base)) return;
                JSONObject o = new JSONObject();
                o.put("peer", peerKey);
                o.put("server_ts", targetTs);
                o.put("body", newText);
                o.put("edited_ts", editTs);
                httpPostJson(base + "/v2/sent", o.toString());
            } catch (Exception e) {
                DebugLog.log("edit report failed: " + e);
            }
        });
    }

    /** Device-local thread wipe: rows gone entirely (no tombstones — the next
     *  catch-up would re-deliver, so also pin the cursor watermark forward). */
    void deleteThread(String peerKey) {
        long maxTs;
        synchronized (writeLock) {
            maxTs = db.getLastTs(peerKey); // pin to DATA, not the device clock —
                                           // a fast clock would silently eat future messages
            db.getWritableDatabase().execSQL(
                    "DELETE FROM messages WHERE peer_key=?", new Object[]{peerKey});
        }
        if (maxTs > 0) {
            advanceReadTs(peerKey, maxTs);
            prefs.edit().putLong("thread_cleared_ts_" + peerKey, maxTs).apply();
        }
        notifyInserted(peerKey);
    }

    // ── bridge change-feed catch-up ───────────────────────────────────────────

    private static final int PAGE = 200;

    interface CatchUpNotifier { void onMissed(String peerKey, int count, String snippet); }

    void catchUp() { catchUp(null); }

    /** Warn once if the bridge speaks a different API version than this build
     *  expects — turns "silently missing messages" into a visible signal. */
    private void checkApiVersion(String base) {
        if (apiMismatchWarned) return;
        try {
            JSONObject h = new JSONObject(httpGet(base + "/health"));
            int got = h.optInt("api_version", EXPECTED_API_VERSION); // absent = old bridge, assume ok
            if (got != EXPECTED_API_VERSION) {
                apiMismatchWarned = true;
                DebugLog.log("BRIDGE API MISMATCH: bridge=" + got
                        + " app=" + EXPECTED_API_VERSION + ", update one to match");
                main.post(() -> {
                    for (Listener l : listenersSnapshot())
                        l.onEphemeral("", "api_mismatch:" + got);
                });
            }
        } catch (Exception ignored) {} // health unreachable: catch-up will fail loudly on its own
    }

    private java.util.List<Listener> listenersSnapshot() {
        synchronized (listeners) { return new ArrayList<>(listeners); }
    }

    /** Drain /v2/changes from the stored cursor. Runs on the caller's thread;
     *  single-flight (concurrent calls would race the cursor pref). The first
     *  drain after migration runs in reconcile mode (§3.6 step 7): legacy
     *  local-clock rows adopt the bridge's Signal ts instead of duplicating.
     *  With a notifier (the service passes one), missed incoming messages
     *  beyond each peer's notified watermark are reported after the drain. */
    /** Bumped in lockstep with bridge API_VERSION; a mismatch means /v2/changes
     *  rows may have a shape this build doesn't understand. */
    static final int EXPECTED_API_VERSION = 3;
    private boolean apiMismatchWarned = false;

    void catchUp(CatchUpNotifier notifier) {
        synchronized (catchUpLock) {
            String base = prefs.getString("bridge", "");
            if (isEmpty(base)) return;
            checkApiVersion(base);
            boolean reconcile = !prefs.getBoolean("reconcile_done", false);
            long cursor = reconcile ? 0 : prefs.getLong("bridge_seq", 0);
            long reconcileTarget = -1;
            try {
                reconcileConsumed.clear();
                if (reconcile) {
                    // snapshot S: the reconcile is complete only when the drain
                    // verifiably reaches it; interruption restarts from seq 0
                    JSONObject head = new JSONObject(
                            httpGet(base + "/v2/changes?since_seq=0&limit=1"));
                    reconcileTarget = head.optLong("max_seq", -1);
                    if (reconcileTarget < 0) return;
                }
                while (true) {
                    String resp = httpGet(base + "/v2/changes?since_seq=" + cursor + "&limit=" + PAGE);
                    JSONObject o = new JSONObject(resp);
                    JSONArray items = o.optJSONArray("items");
                    JSONArray markers = o.optJSONArray("markers");
                    JSONObject peerMap = o.optJSONObject("peerMap");
                    if (peerMap != null) {
                        java.util.Iterator<String> it = peerMap.keys();
                        while (it.hasNext()) { String u = it.next(); peerKeys.learn(u, peerMap.optString(u)); }
                    }
                    // The two streams are independently limited. Coverage below
                    // a bound is only guaranteed per-stream: a full page covers
                    // up to its last row; a short page covers everything. The
                    // safe cursor is the MIN of the two bounds.
                    long itemsBound = Long.MAX_VALUE, markersBound = Long.MAX_VALUE;
                    java.util.Set<String> touched = new java.util.HashSet<>();
                    if (missedIn == null) missedIn = new HashMap<>();
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject row = items.optJSONObject(i);
                            if (row == null) continue;
                            try {
                                String peer = ingestBridgeRow(row, reconcile);
                                if (notEmpty(peer)) touched.add(peer);
                                if (notEmpty(peer) && !reconcile && "in".equals(row.optString("dir"))
                                        && row.optLong("serverTs", 0) > Math.max(
                                                prefs.getLong("notified_ts_" + peer, 0),
                                                prefs.getLong("read_ts_" + peer, 0))) {
                                    Object[] cur = missedIn.get(peer);
                                    long ts = row.optLong("serverTs", 0);
                                    String snip = row.optString("body", "");
                                    if (cur == null || ts > (Long) cur[1])
                                        missedIn.put(peer, new Object[]{(cur == null ? 1 : (Integer) cur[0] + 1), ts, snip});
                                    else cur[0] = (Integer) cur[0] + 1;
                                }
                            } catch (Exception rowEx) {
                                DebugLog.log("poison feed row seq=" + row.optLong("modSeq", -1) + ": " + rowEx);
                            }
                        }
                        if (items.length() >= PAGE)
                            itemsBound = items.optJSONObject(items.length() - 1).optLong("modSeq", cursor);
                    }
                    if (markers != null) {
                        for (int i = 0; i < markers.length(); i++) {
                            JSONObject m = markers.optJSONObject(i);
                            if (m == null) continue;
                            String peer = m.optString("peer", "");
                            long ts = m.optLong("lastReadTs", 0);
                            if (notEmpty(peer) && ts > 0) {
                                advanceReadTs(peer, ts);
                                touched.add(peer);
                            }
                        }
                        if (markers.length() >= PAGE)
                            markersBound = markers.optJSONObject(markers.length() - 1).optLong("modSeq", cursor);
                    }
                    // §2.2: advance ONLY to the last RETURNED row's modSeq — max_seq
                    // is informational; the bridge reads items/markers/meta in
                    // separate snapshots, so trusting it can skip rows forever
                    long lastReturned = cursor;
                    if (items != null)
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject r2 = items.optJSONObject(i);
                            if (r2 != null) lastReturned = Math.max(lastReturned, r2.optLong("modSeq", 0));
                        }
                    if (markers != null)
                        for (int i = 0; i < markers.length(); i++) {
                            JSONObject m2 = markers.optJSONObject(i);
                            if (m2 != null) lastReturned = Math.max(lastReturned, m2.optLong("modSeq", 0));
                        }
                    long next = Math.min(Math.min(itemsBound, markersBound), lastReturned);
                    for (String p : touched) notifyInserted(p);
                    if (next <= cursor) break;
                    cursor = next;
                    if (!reconcile) prefs.edit().putLong("bridge_seq", cursor).apply();
                    if (itemsBound == Long.MAX_VALUE && markersBound == Long.MAX_VALUE) break;
                }
                if (reconcile && cursor >= reconcileTarget) {
                    prefs.edit().putBoolean("reconcile_done", true)
                            .putLong("bridge_seq", cursor).apply();
                    DebugLog.log("reconcile complete at seq " + cursor);
                }
                if (notifier != null && missedIn != null) {
                    String openPeer = prefs.getString("open_chat_peer", "");
                    for (Map.Entry<String, Object[]> e : missedIn.entrySet()) {
                        if (e.getKey().equals(openPeer)) continue;
                        Object[] v = e.getValue();
                        notifier.onMissed(e.getKey(), (Integer) v[0], v[2] == null ? "" : (String) v[2]);
                        prefs.edit().putLong("notified_ts_" + e.getKey(), (Long) v[1]).apply();
                    }
                }
                missedIn = null;
            } catch (Exception e) {
                DebugLog.log("catchUp failed: " + e);
            }
        }
    }

    private Map<String, Object[]> missedIn; // peer -> [count, maxTs, snippet]; guarded by catchUpLock
    private final java.util.Set<Long> reconcileConsumed = new java.util.HashSet<>(); // guarded by catchUpLock

    /** Apply one /v2/changes row. Steady-state rules (REDESIGN §3.6): identity
     *  upsert; status=MAX; bridge body/edits/deletes adopt; local-only fields
     *  untouched. In reconcile mode (first drain after migration), legacy
     *  local-clock rows adopt the bridge's Signal ts instead of duplicating.
     *  @return affected peerKey. */
    private String ingestBridgeRow(JSONObject row, boolean reconcile) {
        String peer = row.optString("peer", "");
        String dir = row.optString("dir", "in");
        if (isEmpty(peer)) return "";
        long ts = row.optLong("serverTs", 0);
        if (ts <= 0) return "";
        String kind = row.optString("kind", "text");
        String body = safeOptString(row, "body");
        String attId = safeOptString(row, "attId");
        String mime = safeOptString(row, "mime");
        int status = row.optInt("status", 1);
        if ("out".equals(dir) && peer.equals(selfNumber))
            status = Math.max(status, MessageDatabase.ST_DELIVERED); // self-thread: see ingestDataMessage
        long quoteTs = row.optLong("quoteTs", 0);
        String quoteText = row.isNull("quoteText") ? null : row.optString("quoteText", null);
        String quoteAuthorRaw = PeerKeys.normalize(row.optString("quoteAuthor", ""));
        String quoteAuthor = isEmpty(quoteAuthorRaw) ? null
                : (quoteAuthorRaw.equals(selfNumber) || quoteAuthorRaw.equals(selfUuid)) ? "me" : "peer";

        if (ts <= prefs.getLong("thread_cleared_ts_" + peer, 0)) return ""; // user wiped this thread
        boolean isText = "text".equals(kind);
        synchronized (writeLock) {
            if (row.optBoolean("deleted")) {
                db.remoteDeleteByServerTs(peer, ts);
                return peer;
            }
            // reconcile legs (§3.6 step 7): attachment first, then exact-text
            if (reconcile) {
                if (notEmpty(attId)) {
                    // attachment leg (§3.6.7.ii): re-timestamp a legacy media row
                    // found by att_id so the upsert below dedups instead of twinning
                    long existing = db.findByAttachment(peer, dir, attId);
                    if (existing >= 0) {
                        android.database.sqlite.SQLiteDatabase d = db.getWritableDatabase();
                        android.database.Cursor rc = d.rawQuery(
                                "SELECT server_ts FROM messages WHERE id=?",
                                new String[]{String.valueOf(existing)});
                        long curTs = rc.moveToFirst() ? rc.getLong(0) : ts;
                        rc.close();
                        if (curTs != ts) {
                            android.database.Cursor occ = d.rawQuery(
                                    "SELECT id FROM messages WHERE peer_key=? AND dir=? AND server_ts=? AND att_id=?",
                                    new String[]{peer, dir, String.valueOf(ts), attId});
                            if (occ.moveToFirst()) {
                                d.execSQL("DELETE FROM messages WHERE id=?", new Object[]{existing});
                            } else {
                                d.execSQL("UPDATE messages SET server_ts=? WHERE id=?",
                                        new Object[]{ts, existing});
                            }
                            occ.close();
                        }
                    }
                } else if (isText && notEmpty(body)) {
                    db.adoptTimestamp(peer, dir, body, ts, 30_000, reconcileConsumed);
                }
            }
            // sent-attachment merge: our confirmed row may sit at att_id=''
            if (!isText && notEmpty(attId) && "out".equals(dir)) {
                adoptLocalAttachmentRow(peer, ts, attId, mime);
                // fall through: identity now matches, upsert below raises status
            }
            db.upsertByIdentity(peer, dir, kind,
                    isText ? body : "", attId, mime,
                    isText ? null : emptyToNull(body), null,
                    ts, status, quoteTs, quoteText, quoteAuthor,
                    row.optString("author", ""));
            long editedTs = row.optLong("editedTs", 0);
            if (editedTs > 0 && isText) {
                // idempotent: skip if this edit was already applied
                java.util.List<MessageItem> cur = db.getMessages(peer);
                boolean applied = false;
                for (MessageItem it : cur)
                    if (it.serverTs == ts && it.lastEditTs >= editedTs) { applied = true; break; }
                if (!applied) db.applyEdit(peer, ts, body, editedTs);
            }
            JSONObject reactions = row.optJSONObject("reactions");
            if (reactions != null) {
                // bridge can't know reactions the app itself sent (F1: no echo,
                // not yet reported) — merge, preferring the local "me" entry
                try {
                    JSONObject merged = new JSONObject();
                    java.util.Iterator<String> it = reactions.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        // bridge keys: "me" | "peer:<key>" → app keys: "me" | "peer"
                        merged.put(k.startsWith("peer") ? "peer" : "me", reactions.optString(k));
                    }
                    JSONObject local = new JSONObject(db.getReactions(peer, ts));
                    if (local.has("me")) merged.put("me", local.optString("me"));
                    if (merged.length() > 0 || local.length() > 0)
                        db.setReactions(peer, ts, merged.toString());
                } catch (Exception ignored) {}
            }
        }
        return peer;
    }
}
