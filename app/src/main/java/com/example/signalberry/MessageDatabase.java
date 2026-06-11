package com.example.signalberry;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Pair;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.example.signalberry.Utils.*;

/**
 * v6: canonical message identity (peer_key, dir, server_ts, att_id) enforced by
 * a UNIQUE index (att_id normalized to '' — SQLite treats NULLs as distinct in
 * UNIQUE indexes, which would disable the constraint for text rows).
 *
 * Pending sends carry server_ts = -nonce (negative ⇒ never collides with real
 * Signal timestamps); confirm rewrites to the /v2/send response ts. Receipts
 * never modify server_ts. See docs/REDESIGN.md §3.2/§3.6.
 *
 * All writes go through Repo (single writer); activities only read.
 */
class MessageDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME    = "signalberry.db";
    private static final int    DB_VERSION = 6;
    private static final String T          = "messages";

    // status values
    static final int ST_REMOTE_DELETED = -3; // peer/own remote delete; renders a placeholder
    static final int ST_FAILED   = -2; // pending that can no longer confirm; tap-to-retry
    static final int ST_DELETED  = -1; // local-only tombstone; hidden entirely
    static final int ST_PENDING  = 0;
    static final int ST_SENT     = 1;
    static final int ST_DELIVERED= 2;
    static final int ST_READ     = 3;

    MessageDatabase(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + "(" +
                "id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "peer_key     TEXT    NOT NULL," +
                "dir          TEXT    NOT NULL," +       // 'in' | 'out'
                "msg_type     TEXT    NOT NULL DEFAULT 'text'," + // text|image|video|audio|file
                "text         TEXT    NOT NULL DEFAULT ''," +
                "att_id       TEXT    NOT NULL DEFAULT ''," +
                "mime         TEXT    NOT NULL DEFAULT ''," +
                "caption      TEXT," +
                "local_uri    TEXT," +
                "server_ts    INTEGER NOT NULL DEFAULT 0," +
                "status       INTEGER NOT NULL DEFAULT 1," +
                "quote_ts     INTEGER NOT NULL DEFAULT 0," +
                "quote_text   TEXT," +
                "quote_author TEXT," +
                "reactions    TEXT," +
                "edit_history TEXT," +
                "last_edit_ts INTEGER NOT NULL DEFAULT 0," +
                "client_nonce INTEGER NOT NULL DEFAULT 0," +
                "reported     INTEGER NOT NULL DEFAULT 1" + // only app-confirmed sends start at 0
                ")");
        db.execSQL("CREATE INDEX idx_peer_ts ON " + T + "(peer_key, server_ts)");
        createIdentityIndex(db);
    }

    private static void createIdentityIndex(SQLiteDatabase db) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_identity ON " + T +
                "(peer_key, dir, server_ts, att_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int old, int nw) {
        if (old < 2) db.execSQL("ALTER TABLE " + T + " ADD COLUMN reactions TEXT");
        if (old < 3) db.execSQL("ALTER TABLE " + T + " ADD COLUMN edit_history TEXT");
        if (old < 4) db.execSQL("ALTER TABLE " + T + " ADD COLUMN last_edit_ts INTEGER NOT NULL DEFAULT 0");
        if (old < 5) db.execSQL("DELETE FROM " + T + " WHERE msg_type='text' AND text='null'");
        if (old < 6) migrateV6(db);
    }

    /** v5→v6: REDESIGN.md §3.6 steps 1-6 (step 7, the bridge reconcile, runs in
     *  Repo after this — Repo sees the reconcile_pending flag via empty cursor). */
    private static void migrateV6(SQLiteDatabase db) {
        // 1. new columns (reactions handled by the old<2 branch for real v1 DBs;
        //    fresh-v5 installs were created WITHOUT it — the confirmed bug)
        if (!hasColumn(db, "reactions"))
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN reactions TEXT");
        db.execSQL("ALTER TABLE " + T + " ADD COLUMN quote_ts INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + T + " ADD COLUMN client_nonce INTEGER NOT NULL DEFAULT 0");
        db.execSQL("ALTER TABLE " + T + " ADD COLUMN reported INTEGER NOT NULL DEFAULT 1");

        // 2. normalize NULLs in identity/text columns
        db.execSQL("UPDATE " + T + " SET att_id='' WHERE att_id IS NULL");
        db.execSQL("UPDATE " + T + " SET text=''   WHERE text   IS NULL");
        db.execSQL("UPDATE " + T + " SET mime=''   WHERE mime   IS NULL");

        // 3. purge literal-"null" artifacts; re-kind by mime; strand-proof pendings
        db.execSQL("DELETE FROM " + T + " WHERE msg_type='text' AND text='null'");
        db.execSQL("UPDATE " + T + " SET quote_text=NULL WHERE quote_text='null'");
        db.execSQL("UPDATE " + T + " SET msg_type='video' WHERE mime LIKE 'video/%'");
        db.execSQL("UPDATE " + T + " SET msg_type='audio' WHERE mime LIKE 'audio/%'");
        db.execSQL("UPDATE " + T + " SET msg_type='file' WHERE msg_type='image' AND mime!='' " +
                "AND mime NOT LIKE 'image/%' AND mime NOT LIKE 'video/%' AND mime NOT LIKE 'audio/%'");
        // legacy positive-ts pendings can never confirm (the confirm path matched
        // by text against status=0; after migration nothing will ever do that)
        db.execSQL("UPDATE " + T + " SET status=" + ST_FAILED +
                " WHERE status=" + ST_PENDING + " AND server_ts > 0");

        // 4/5. dedupe so the UNIQUE index can be created
        dedupeExact(db);
        mergeImageTwins(db);

        // 6.
        createIdentityIndex(db);
    }

    private static boolean hasColumn(SQLiteDatabase db, String col) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + T + ")", null);
        try {
            while (c.moveToNext()) if (col.equals(c.getString(1))) return true;
            return false;
        } finally { c.close(); }
    }

    /** §3.6 step 5a: collapse exact-identity duplicates. Survivor: tombstone wins
     *  outright, else max(status); per-field coalesce of the descriptive fields. */
    private static void dedupeExact(SQLiteDatabase db) {
        Cursor groups = db.rawQuery(
                "SELECT peer_key, dir, server_ts, att_id, COUNT(*) FROM " + T +
                " GROUP BY peer_key, dir, server_ts, att_id HAVING COUNT(*) > 1", null);
        List<String[]> keys = new ArrayList<>();
        while (groups.moveToNext())
            keys.add(new String[]{groups.getString(0), groups.getString(1),
                    groups.getString(2), groups.getString(3)});
        groups.close();

        for (String[] k : keys) {
            Cursor c = db.rawQuery(
                    "SELECT id, status, caption, local_uri, quote_text, quote_author, reactions " +
                    "FROM " + T + " WHERE peer_key=? AND dir=? AND server_ts=? AND att_id=? " +
                    "ORDER BY id ASC",
                    new String[]{k[0], k[1], k[2], k[3]});
            long survivorId = -1;
            int bestStatus = Integer.MIN_VALUE;
            boolean tombstone = false;
            String caption = null, localUri = null, quoteText = null, quoteAuthor = null, reactions = null;
            List<Long> ids = new ArrayList<>();
            while (c.moveToNext()) {
                long id = c.getLong(0);
                int st = c.getInt(1);
                ids.add(id);
                if (st == ST_DELETED) tombstone = true;
                if (survivorId < 0 || st > bestStatus) { survivorId = id; bestStatus = st; }
                if (caption     == null && !c.isNull(2)) caption     = c.getString(2);
                if (localUri    == null && !c.isNull(3)) localUri    = c.getString(3);
                if (quoteText   == null && !c.isNull(4)) quoteText   = c.getString(4);
                if (quoteAuthor == null && !c.isNull(5)) quoteAuthor = c.getString(5);
                if (reactions   == null && !c.isNull(6)) reactions   = c.getString(6);
            }
            c.close();
            ContentValues v = new ContentValues();
            v.put("status", tombstone ? ST_DELETED : bestStatus);
            v.put("caption", caption);
            v.put("local_uri", localUri);
            v.put("quote_text", quoteText);
            v.put("quote_author", quoteAuthor);
            v.put("reactions", reactions);
            db.update(T, v, "id=?", new String[]{String.valueOf(survivorId)});
            for (long id : ids)
                if (id != survivorId)
                    db.execSQL("DELETE FROM " + T + " WHERE id=" + id);
        }
    }

    /** §3.6 step 5b: a sent image historically produced TWO rows — the local one
     *  (wall-clock ts, local_uri, no att_id) and the sync-echo one (Signal ts,
     *  att_id, no local_uri). They never share a group key, so pair them by
     *  nearest |Δts| ≤ 120s, each row consumed at most once; merge into the
     *  att_id row, keeping local_uri as a cache hint. */
    private static void mergeImageTwins(SQLiteDatabase db) {
        Cursor c = db.rawQuery(
                "SELECT id, peer_key, server_ts, status, local_uri, att_id, caption FROM " + T +
                " WHERE dir='out' AND msg_type='image' ORDER BY peer_key, server_ts", null);
        List<Object[]> locals = new ArrayList<>();   // [id, peer, ts, status, local_uri, caption]
        List<Object[]> echoes = new ArrayList<>();   // [id, peer, ts, status, caption]
        while (c.moveToNext()) {
            boolean hasLocal = !c.isNull(4) && !c.getString(4).isEmpty();
            boolean hasAtt   = !c.getString(5).isEmpty();
            if (hasLocal && !hasAtt)
                locals.add(new Object[]{c.getLong(0), c.getString(1), c.getLong(2),
                        c.getInt(3), c.getString(4), c.isNull(6) ? null : c.getString(6)});
            else if (hasAtt && !hasLocal)
                echoes.add(new Object[]{c.getLong(0), c.getString(1), c.getLong(2),
                        c.getInt(3), c.isNull(6) ? null : c.getString(6)});
        }
        c.close();

        Set<Long> consumedEchoes = new HashSet<>();
        for (Object[] loc : locals) {
            Object[] best = null;
            long bestDelta = Long.MAX_VALUE;
            for (Object[] echo : echoes) {
                if (consumedEchoes.contains(echo[0])) continue;
                if (!loc[1].equals(echo[1])) continue;
                long delta = Math.abs((Long) loc[2] - (Long) echo[2]);
                if (delta <= 120_000 && delta < bestDelta) { best = echo; bestDelta = delta; }
            }
            if (best == null) continue;
            consumedEchoes.add((Long) best[0]);
            ContentValues v = new ContentValues();
            v.put("local_uri", (String) loc[4]);
            v.put("status", Math.max((Integer) loc[3], (Integer) best[3]));
            if (best[4] == null && loc[5] != null) v.put("caption", (String) loc[5]);
            db.update(T, v, "id=?", new String[]{String.valueOf(best[0])});
            db.execSQL("DELETE FROM " + T + " WHERE id=" + loc[0]);
        }
    }

    // ── v6 write API (called only by Repo) ────────────────────────────────────

    /** Identity upsert: INSERT (ignored on conflict) + status-raise UPDATE.
     *  Returns the row id if inserted, -1 if it merged into an existing row. */
    long upsertByIdentity(String peerKey, String dir, String msgType, String text,
                          String attId, String mime, String caption, String localUri,
                          long serverTs, int status,
                          long quoteTs, String quoteText, String quoteAuthor) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("peer_key", peerKey);
        v.put("dir", dir);
        v.put("msg_type", msgType);
        v.put("text", text == null ? "" : text);
        v.put("att_id", attId == null ? "" : attId);
        v.put("mime", mime == null ? "" : mime);
        v.put("caption", caption);
        v.put("local_uri", localUri);
        v.put("server_ts", serverTs);
        v.put("status", status);
        v.put("quote_ts", quoteTs);
        v.put("quote_text", quoteText);
        v.put("quote_author", quoteAuthor);
        long id = db.insertWithOnConflict(T, null, v, SQLiteDatabase.CONFLICT_IGNORE);
        if (id == -1) {
            // never resurrect tombstones, never downgrade status
            db.execSQL("UPDATE " + T + " SET status=MAX(status,?) " +
                    "WHERE peer_key=? AND dir=? AND server_ts=? AND att_id=? AND status>=0",
                    new Object[]{status, peerKey, dir, serverTs,
                            attId == null ? "" : attId});
        }
        return id;
    }

    /** Insert a pending outgoing row at server_ts = -nonce.
     *  @return the nonce actually used (bumped if the slot was taken). */
    long insertPending(String peerKey, String msgType, String text, String mime,
                       String caption, String localUri, long nonce,
                       long quoteTs, String quoteText, String quoteAuthor) {
        SQLiteDatabase db = getWritableDatabase();
        for (int attempt = 0; attempt < 8; attempt++) {
            ContentValues v = new ContentValues();
            v.put("peer_key", peerKey);
            v.put("dir", "out");
            v.put("msg_type", msgType);
            v.put("text", text == null ? "" : text);
            v.put("att_id", "");
            v.put("mime", mime == null ? "" : mime);
            v.put("caption", caption);
            v.put("local_uri", localUri);
            v.put("server_ts", -nonce);
            v.put("status", ST_PENDING);
            v.put("client_nonce", nonce);
            v.put("quote_ts", quoteTs);
            v.put("quote_text", quoteText);
            v.put("quote_author", quoteAuthor);
            v.put("reported", 0);
            long id = db.insertWithOnConflict(T, null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (id != -1) return nonce;
            nonce++; // identity slot taken — a silently dropped send is never acceptable
        }
        return -1;
    }

    /** Confirm a pending row at the real Signal ts. If a row already exists at
     *  the target identity (echo/bridge won the race), merge local-only fields
     *  into it and drop the pending row. Returns the surviving row id. */
    long confirmPendingByNonce(long nonce, long realTs, int status) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery(
                    "SELECT id, peer_key, local_uri, caption, quote_ts, quote_text, quote_author " +
                    "FROM " + T + " WHERE client_nonce=? AND status=" + ST_PENDING,
                    new String[]{String.valueOf(nonce)});
            if (!c.moveToFirst()) { c.close(); return -1; }
            long pendingId = c.getLong(0);
            String peerKey = c.getString(1);
            String localUri = c.isNull(2) ? null : c.getString(2);
            String caption  = c.isNull(3) ? null : c.getString(3);
            long quoteTs = c.getLong(4);
            String quoteText   = c.isNull(5) ? null : c.getString(5);
            String quoteAuthor = c.isNull(6) ? null : c.getString(6);
            c.close();

            long survivor;
            Cursor occ = db.rawQuery(
                    "SELECT id FROM " + T + " WHERE peer_key=? AND dir='out' AND server_ts=?",
                    new String[]{peerKey, String.valueOf(realTs)});
            if (occ.moveToFirst()) {
                survivor = occ.getLong(0);
                occ.close();
                ContentValues v = new ContentValues();
                if (localUri != null) v.put("local_uri", localUri);
                if (caption  != null) v.put("caption", caption);
                if (quoteTs > 0) { v.put("quote_ts", quoteTs);
                    v.put("quote_text", quoteText); v.put("quote_author", quoteAuthor); }
                v.put("reported", 0); // we own this send; report it to the bridge
                db.update(T, v, "id=?", new String[]{String.valueOf(survivor)});
                db.execSQL("DELETE FROM " + T + " WHERE id=" + pendingId);
            } else {
                occ.close();
                ContentValues v = new ContentValues();
                v.put("server_ts", realTs);
                v.put("status", status);
                v.put("reported", 0);
                db.update(T, v, "id=?", new String[]{String.valueOf(pendingId)});
                survivor = pendingId;
            }
            db.setTransactionSuccessful();
            return survivor;
        } finally {
            db.endTransaction();
        }
    }

    void markFailedByNonce(long nonce) {
        getWritableDatabase().execSQL(
                "UPDATE " + T + " SET status=" + ST_FAILED +
                " WHERE client_nonce=? AND status=" + ST_PENDING,
                new Object[]{nonce});
    }

    /** Repo init: stale pendings → FAILED; returns confirmed-but-unreported rows. */
    void failStalePendings(long olderThanMs) {
        long cutoffNonce = (System.currentTimeMillis() - olderThanMs) << 8;
        getWritableDatabase().execSQL(
                "UPDATE " + T + " SET status=" + ST_FAILED +
                " WHERE status=" + ST_PENDING + " AND server_ts<0 AND client_nonce < ?",
                new Object[]{cutoffNonce});
    }

    /** Confirmed-but-unreported sends, with att_id/mime read straight from the
     *  row (toItem prefers local_uri rendering, which would drop them and make
     *  the bridge mint a second identity for echo-adopted media). */
    List<MessageItem> getUnreported() {
        Cursor c = getReadableDatabase().query(T, null,
                "dir='out' AND reported=0 AND server_ts>0 AND status>=" + ST_SENT,
                null, null, null, "server_ts ASC", "50");
        List<MessageItem> out = new ArrayList<>();
        while (c.moveToNext()) {
            String attId = c.getString(c.getColumnIndexOrThrow("att_id"));
            String mime  = c.getString(c.getColumnIndexOrThrow("mime"));
            String type  = c.getString(c.getColumnIndexOrThrow("msg_type"));
            String cap   = c.getString(c.getColumnIndexOrThrow("caption"));
            String dir   = c.getString(c.getColumnIndexOrThrow("dir"));
            MessageItem it;
            if (!"text".equals(type)) {
                it = new MessageItem("me", isEmpty(attId) ? "" : attId, mime,
                        isEmpty(cap) ? null : cap, c.getInt(c.getColumnIndexOrThrow("status")));
                it.msgType = type;
            } else {
                it = toItem(c);
            }
            it.serverTs = c.getLong(c.getColumnIndexOrThrow("server_ts"));
            it.quoteTs = c.getLong(c.getColumnIndexOrThrow("quote_ts"));
            it.quoteText = c.getString(c.getColumnIndexOrThrow("quote_text"));
            it.quoteAuthor = c.getString(c.getColumnIndexOrThrow("quote_author"));
            it.peerKey = c.getString(c.getColumnIndexOrThrow("peer_key"));
            out.add(it);
        }
        c.close();
        return out;
    }

    void markReported(String peerKey, long serverTs) {
        getWritableDatabase().execSQL(
                "UPDATE " + T + " SET reported=1 WHERE peer_key=? AND dir='out' AND server_ts=?",
                new Object[]{peerKey, serverTs});
    }

    /** Exact per-timestamp receipt: raise status on the ts-group (server_ts or
     *  last_edit_ts match), never downgrade, never touch tombstones. */
    boolean applyReceipt(String peerKey, long ts, int newStatus) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM " + T +
                " WHERE peer_key=? AND dir='out' AND (server_ts=? OR last_edit_ts=?) AND status>=0",
                new String[]{peerKey, String.valueOf(ts), String.valueOf(ts)});
        boolean any = c.moveToFirst() && c.getInt(0) > 0;
        c.close();
        if (!any) return false;
        db.execSQL("UPDATE " + T + " SET status=MAX(status,?) " +
                "WHERE peer_key=? AND dir='out' AND (server_ts=? OR last_edit_ts=?) AND status>=0",
                new Object[]{newStatus, peerKey, ts, ts});
        return true;
    }

    /** Re-key rows from a uuid key to its learned number key (ongoing, idempotent).
     *  Collisions merge into the occupant (max status, first-non-null fields). */
    void rekeyPeer(String uuidKey, String numberKey) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT id, dir, server_ts, att_id, status FROM " + T +
                    " WHERE peer_key=?", new String[]{uuidKey});
            List<Object[]> rows = new ArrayList<>();
            while (c.moveToNext())
                rows.add(new Object[]{c.getLong(0), c.getString(1), c.getLong(2),
                        c.getString(3), c.getInt(4)});
            c.close();
            for (Object[] r : rows) {
                Cursor occ = db.rawQuery("SELECT id FROM " + T +
                        " WHERE peer_key=? AND dir=? AND server_ts=? AND att_id=?",
                        new String[]{numberKey, (String) r[1], String.valueOf(r[2]), (String) r[3]});
                if (occ.moveToFirst()) {
                    long occId = occ.getLong(0);
                    occ.close();
                    // coalesce descriptive fields from the uuid row before dropping it
                    db.execSQL("UPDATE " + T + " SET status=MAX(status,?)," +
                            " reactions=COALESCE(reactions,(SELECT reactions FROM " + T + " WHERE id=?))," +
                            " local_uri=COALESCE(local_uri,(SELECT local_uri FROM " + T + " WHERE id=?))," +
                            " caption=COALESCE(caption,(SELECT caption FROM " + T + " WHERE id=?))," +
                            " quote_text=COALESCE(quote_text,(SELECT quote_text FROM " + T + " WHERE id=?))" +
                            " WHERE id=? AND status!=" + ST_DELETED,
                            new Object[]{r[4], r[0], r[0], r[0], r[0], occId});
                    db.execSQL("DELETE FROM " + T + " WHERE id=" + r[0]);
                } else {
                    occ.close();
                    db.execSQL("UPDATE " + T + " SET peer_key=? WHERE id=?",
                            new Object[]{numberKey, r[0]});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        dedupeExact(db);
        mergeImageTwins(db);
    }

    /** One-shot reconcile (§3.6 step 7): adopt the bridge Signal ts for a legacy
     *  local-clock row matched by exact text within the window. The exact-identity
     *  and attachment legs are handled by upsertByIdentity / attachment lookup in
     *  Repo. On adopt-collision, merge into the occupant. Returns true if adopted. */
    boolean adoptTimestamp(String peerKey, String dir, String text, long bridgeTs, long windowMs) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, server_ts, status FROM " + T +
                " WHERE peer_key=? AND dir=? AND text=? AND att_id='' AND server_ts>0 AND server_ts!=? " +
                " ORDER BY ABS(server_ts - ?) ASC LIMIT 1",
                new String[]{peerKey, dir, text == null ? "" : text,
                        String.valueOf(bridgeTs), String.valueOf(bridgeTs)});
        if (!c.moveToFirst()) { c.close(); return false; }
        long id = c.getLong(0);
        long ts = c.getLong(1);
        int status = c.getInt(2);
        c.close();
        if (Math.abs(ts - bridgeTs) > windowMs) return false;
        db.beginTransaction();
        try {
            Cursor occ = db.rawQuery("SELECT id FROM " + T +
                    " WHERE peer_key=? AND dir=? AND server_ts=? AND att_id=''",
                    new String[]{peerKey, dir, String.valueOf(bridgeTs)});
            if (occ.moveToFirst()) {
                long occId = occ.getLong(0);
                occ.close();
                db.execSQL("UPDATE " + T + " SET status=MAX(status,?) WHERE id=? AND status!=" + ST_DELETED,
                        new Object[]{status, occId});
                db.execSQL("DELETE FROM " + T + " WHERE id=" + id);
            } else {
                occ.close();
                db.execSQL("UPDATE " + T + " SET server_ts=? WHERE id=?",
                        new Object[]{bridgeTs, id});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    /** Attachment row lookup for the reconcile attachment leg + sent-image merge. */
    long findByAttachment(String peerKey, String dir, String attId) {
        if (isEmpty(attId)) return -1;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM " + T + " WHERE peer_key=? AND dir=? AND att_id=?",
                new String[]{peerKey, dir, attId});
        long id = c.moveToFirst() ? c.getLong(0) : -1;
        c.close();
        return id;
    }

    void setReactions(String peerKey, long serverTs, String reactionsJson) {
        getWritableDatabase().execSQL(
                "UPDATE " + T + " SET reactions=? WHERE peer_key=? AND (server_ts=? OR last_edit_ts=?)",
                new Object[]{reactionsJson, peerKey, serverTs, serverTs});
    }

    String getReactions(String peerKey, long serverTs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT reactions FROM " + T + " WHERE peer_key=? AND (server_ts=? OR last_edit_ts=?)",
                new String[]{peerKey, String.valueOf(serverTs), String.valueOf(serverTs)});
        String json = (c.moveToFirst() && !c.isNull(0)) ? c.getString(0) : "{}";
        c.close();
        return json;
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    long getLastTs(String peerKey) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(server_ts) FROM " + T + " WHERE peer_key=?",
                new String[]{peerKey});
        long ts = 0;
        if (c.moveToFirst() && !c.isNull(0)) ts = c.getLong(0);
        c.close();
        return ts;
    }

    List<MessageItem> getMessages(String peerKey) {
        Cursor c = getReadableDatabase().query(T, null,
                "peer_key=? AND status!=" + ST_DELETED, new String[]{peerKey},
                null, null, "CASE WHEN server_ts<0 THEN -server_ts ELSE server_ts END ASC, id ASC");
        List<MessageItem> list = new ArrayList<>();
        while (c.moveToNext()) list.add(toItem(c));
        c.close();
        return list;
    }

    /** Latest message per peer, sorted newest first. Returns list of (peerKey, [snippet, timeStr, tsStr]).
     *  Pending rows hold -nonce where nonce=(millis<<8)|counter — the display
     *  time must decode back to millis or pendings show a far-future date. */
    List<Pair<String, String[]>> getConversationSummaries() {
        String dispTs = "(CASE WHEN m.server_ts<0 THEN ((-m.server_ts)>>8) ELSE m.server_ts END)";
        String dispTsBare = "(CASE WHEN server_ts<0 THEN ((-server_ts)>>8) ELSE server_ts END)";
        String sql =
                "SELECT m.peer_key, m.dir, m.text, m.att_id, m.msg_type, " + dispTs + " dts, m.status" +
                " FROM " + T + " m" +
                " INNER JOIN (SELECT peer_key, MAX(" + dispTsBare + ") mts" +
                "   FROM " + T + " WHERE status!=" + ST_DELETED + " GROUP BY peer_key) x" +
                " ON m.peer_key=x.peer_key AND " + dispTs + "=x.mts" +
                " ORDER BY x.mts DESC";
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        List<Pair<String, String[]>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        while (c.moveToNext()) {
            String pk = c.getString(0);
            if (!seen.add(pk)) continue;
            String dir  = c.getString(1);
            String txt  = c.getString(2);
            String att  = c.getString(3);
            String type = c.getString(4);
            long   ts   = c.getLong(5);
            int rowSt = c.getInt(6);
            String snip = rowSt == ST_REMOTE_DELETED ? "Message deleted"
                    : (!isEmpty(txt) ? txt : snippetFor(type, att));
            if ("out".equals(dir) && rowSt != ST_REMOTE_DELETED) snip = "You: " + snip;
            out.add(new Pair<>(pk, new String[]{snip, formatShortTime(ts), String.valueOf(ts)}));
        }
        c.close();
        return out;
    }

    private static String snippetFor(String type, String attId) {
        if ("video".equals(type)) return "🎥 Video";
        if ("audio".equals(type)) return "🎤 Audio";
        if ("file".equals(type))  return "📎 File";
        if (!isEmpty(attId) || "image".equals(type)) return "📷 Photo";
        return "";
    }

    int countUnread(String peerKey, long readTs) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + T + " WHERE peer_key=? AND dir='in' AND server_ts>? AND status!=" + ST_DELETED,
                new String[]{peerKey, String.valueOf(readTs)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    void deleteByServerTs(String peerKey, long serverTs) {
        getWritableDatabase().execSQL("UPDATE " + T + " SET status=? WHERE peer_key=? AND (server_ts=? OR last_edit_ts=?)",
                new Object[]{ST_DELETED, peerKey, serverTs, serverTs});
    }

    /** Remote delete: official clients show a placeholder, not a vanished bubble. */
    void remoteDeleteByServerTs(String peerKey, long serverTs) {
        getWritableDatabase().execSQL(
                "UPDATE " + T + " SET status=?, text='', caption=NULL, att_id='', local_uri=NULL, " +
                "reactions=NULL, quote_text=NULL WHERE peer_key=? AND (server_ts=? OR last_edit_ts=?) " +
                "AND status!=" + ST_DELETED,
                new Object[]{ST_REMOTE_DELETED, peerKey, serverTs, serverTs});
    }

    void deleteMessages(String peerKey, java.util.Collection<Long> timestamps) {
        for (long ts : timestamps) deleteByServerTs(peerKey, ts);
    }

    void applyEdit(String peerKey, long prevTs, String newText, long newEditTs) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery(
                "SELECT text, edit_history FROM " + T +
                " WHERE peer_key=? AND (server_ts=? OR last_edit_ts=?)",
                new String[]{peerKey, String.valueOf(prevTs), String.valueOf(prevTs)});
        if (!c.moveToFirst()) { c.close(); return; }
        String oldText  = c.isNull(0) ? "" : c.getString(0);
        String histJson = c.isNull(1) ? "[]" : c.getString(1);
        c.close();
        try {
            org.json.JSONArray hist = new org.json.JSONArray(histJson);
            hist.put(oldText);
            ContentValues cv = new ContentValues();
            cv.put("text", newText);
            cv.put("edit_history", hist.toString());
            if (newEditTs > 0) cv.put("last_edit_ts", newEditTs);
            db.update(T, cv, "peer_key=? AND (server_ts=? OR last_edit_ts=?)",
                    new String[]{peerKey, String.valueOf(prevTs), String.valueOf(prevTs)});
        } catch (Exception ignored) {}
    }

    private static MessageItem toItem(Cursor c) {
        String dir    = c.getString(c.getColumnIndexOrThrow("dir"));
        String type   = c.getString(c.getColumnIndexOrThrow("msg_type"));
        String text   = c.getString(c.getColumnIndexOrThrow("text"));
        String attId  = c.getString(c.getColumnIndexOrThrow("att_id"));
        String mime   = c.getString(c.getColumnIndexOrThrow("mime"));
        String cap    = c.getString(c.getColumnIndexOrThrow("caption"));
        String locUri = c.getString(c.getColumnIndexOrThrow("local_uri"));
        long   ts     = c.getLong(c.getColumnIndexOrThrow("server_ts"));
        int    status = c.getInt(c.getColumnIndexOrThrow("status"));
        String qt     = c.getString(c.getColumnIndexOrThrow("quote_text"));
        String qa     = c.getString(c.getColumnIndexOrThrow("quote_author"));
        String from   = "out".equals(dir) ? "me" : "peer";

        MessageItem item;
        if (status == ST_REMOTE_DELETED) {
            item = new MessageItem(from, "", status);
            item.serverTs = ts;
            return item;
        }
        boolean isMedia = !"text".equals(type);
        if (isMedia) {
            if (!isEmpty(locUri))
                item = new MessageItem(from, locUri, isEmpty(cap) ? null : cap, status, true);
            else
                item = new MessageItem(from, attId, mime, isEmpty(cap) ? null : cap, status);
            item.msgType = type;
        } else {
            item = new MessageItem(from, isEmpty(text) ? "" : text, status);
        }
        item.serverTs    = ts;
        item.quoteText   = isEmpty(qt) ? null : qt;
        item.quoteAuthor = isEmpty(qa) ? null : qa;
        int qtsIdx = c.getColumnIndex("quote_ts");
        if (qtsIdx >= 0) item.quoteTs = c.getLong(qtsIdx);
        int nonceIdx = c.getColumnIndex("client_nonce");
        if (nonceIdx >= 0) item.clientNonce = c.getLong(nonceIdx);
        int ri = c.getColumnIndex("reactions");
        if (ri >= 0 && !c.isNull(ri)) {
            try {
                JSONObject ro = new JSONObject(c.getString(ri));
                Map<String, String> reactions = new HashMap<>();
                Iterator<String> keys = ro.keys();
                while (keys.hasNext()) { String k = keys.next(); reactions.put(k, ro.getString(k)); }
                if (!reactions.isEmpty()) item.reactions = reactions;
            } catch (Exception ignored) {}
        }
        int ehi = c.getColumnIndex("edit_history");
        if (ehi >= 0 && !c.isNull(ehi)) {
            String eh = c.getString(ehi);
            if (eh != null && !eh.equals("[]")) item.editHistory = eh;
        }
        int leti = c.getColumnIndex("last_edit_ts");
        if (leti >= 0) item.lastEditTs = c.getLong(leti);
        return item;
    }

    // ── legacy transitional API (callers move to Repo, then these die) ────────

    long upsert(String peerKey, String dir, String msgType,
                String text, String attId, String mime, String caption, String localUri,
                long serverTs, int status, String quoteText, String quoteAuthor) {
        return upsertByIdentity(peerKey, dir, msgType, text, attId, mime, caption,
                localUri, serverTs, status, 0, quoteText, quoteAuthor);
    }

    void updateReaction(String peerKey, long serverTs, String authorKey, String emoji, boolean isRemove) {
        try {
            JSONObject map = new JSONObject(getReactions(peerKey, serverTs));
            if (isRemove) map.remove(authorKey);
            else map.put(authorKey, emoji);
            setReactions(peerKey, serverTs, map.toString());
        } catch (Exception ignored) {}
    }

}
