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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.example.signalberry.Utils.*;

class MessageDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME    = "signalberry.db";
    private static final int    DB_VERSION = 2;
    private static final String T          = "messages";

    MessageDatabase(Context ctx) {
        super(ctx.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T + "(" +
                "id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "peer_key     TEXT    NOT NULL," +
                "dir          TEXT    NOT NULL," +       // 'in' | 'out'
                "msg_type     TEXT    NOT NULL DEFAULT 'text'," +
                "text         TEXT," +
                "att_id       TEXT," +
                "mime         TEXT," +
                "caption      TEXT," +
                "local_uri    TEXT," +
                "server_ts    INTEGER NOT NULL DEFAULT 0," +
                "status       INTEGER NOT NULL DEFAULT 1," +
                "quote_text   TEXT," +
                "quote_author TEXT" +
                ")");
        db.execSQL("CREATE INDEX idx_peer_ts ON " + T + "(peer_key, server_ts)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int old, int nw) {
        if (old < 2) {
            db.execSQL("ALTER TABLE " + T + " ADD COLUMN reactions TEXT");
        }
    }

    /**
     * Insert a message, deduplicating by:
     *   text: (peer_key, dir, server_ts, text) when server_ts > 0
     *   image: (att_id) when att_id is non-empty
     * Returns new row id, or -1 if duplicate / skipped.
     */
    long upsert(String peerKey, String dir, String msgType,
                String text, String attId, String mime, String caption, String localUri,
                long serverTs, int status, String quoteText, String quoteAuthor) {
        SQLiteDatabase db = getWritableDatabase();

        if ("text".equals(msgType) && serverTs > 0 && !isEmpty(text)) {
            Cursor c = db.rawQuery(
                    "SELECT id FROM " + T + " WHERE peer_key=? AND dir=? AND server_ts=? AND text=?",
                    new String[]{peerKey, dir, String.valueOf(serverTs), text});
            boolean exists = c.moveToFirst();
            c.close();
            if (exists) {
                // bump status upward only, but never resurrect a deleted row
                db.execSQL("UPDATE " + T + " SET status=MAX(status,?) WHERE peer_key=? AND dir=? AND server_ts=? AND status!=?",
                        new Object[]{status, peerKey, dir, serverTs, ST_DELETED});
                return -1;
            }
        } else if ("image".equals(msgType) && !isEmpty(attId)) {
            Cursor c = db.rawQuery("SELECT id FROM " + T + " WHERE att_id=?", new String[]{attId});
            boolean exists = c.moveToFirst();
            c.close();
            if (exists) return -1;
        }

        ContentValues v = new ContentValues();
        v.put("peer_key",     peerKey);
        v.put("dir",          dir);
        v.put("msg_type",     msgType);
        v.put("text",         text);
        v.put("att_id",       attId);
        v.put("mime",         mime);
        v.put("caption",      caption);
        v.put("local_uri",    localUri);
        v.put("server_ts",    serverTs);
        v.put("status",       status);
        v.put("quote_text",   quoteText);
        v.put("quote_author", quoteAuthor);
        return db.insert(T, null, v);
    }

    void updateStatusByText(String peerKey, String text, int newStatus) {
        if (isEmpty(text)) return;
        getWritableDatabase().execSQL(
                "UPDATE " + T + " SET status=MAX(status,?) WHERE peer_key=? AND dir='out' AND text=?",
                new Object[]{newStatus, peerKey, text});
    }

    void confirmPending(String peerKey, String text, long realTs, int newStatus) {
        ContentValues cv = new ContentValues();
        cv.put("server_ts", realTs);
        cv.put("status", newStatus);
        int rows = getWritableDatabase().update(T, cv,
                "peer_key=? AND dir='out' AND status=0 AND text=?",
                new String[]{peerKey, text});
        if (rows == 0) {
            upsert(peerKey, "out", "text", text, null, null, null, null, realTs, newStatus, null, null);
        }
    }

    void updateReaction(String peerKey, long serverTs, String authorKey, String emoji, boolean isRemove) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery(
                "SELECT id, reactions FROM " + T + " WHERE peer_key=? AND server_ts=?",
                new String[]{peerKey, String.valueOf(serverTs)});
        if (!c.moveToFirst()) { c.close(); return; }
        long rowId = c.getLong(0);
        String json = c.isNull(1) ? "{}" : c.getString(1);
        c.close();
        try {
            JSONObject map = new JSONObject(json);
            if (isRemove) map.remove(authorKey);
            else map.put(authorKey, emoji);
            db.execSQL("UPDATE " + T + " SET reactions=? WHERE id=?",
                    new Object[]{map.toString(), rowId});
        } catch (Exception ignored) {}
    }

    // -1 means locally deleted; row is kept so bridge re-delivery is deduped
    static final int ST_DELETED = -1;

    void deleteMessages(String peerKey, java.util.Collection<Long> timestamps) {
        if (timestamps.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        for (long ts : timestamps)
            db.execSQL("UPDATE " + T + " SET status=? WHERE peer_key=? AND server_ts=?",
                    new Object[]{ST_DELETED, peerKey, ts});
    }

    void deleteByServerTs(String peerKey, long serverTs) {
        getWritableDatabase().execSQL("UPDATE " + T + " SET status=? WHERE peer_key=? AND server_ts=?",
                new Object[]{ST_DELETED, peerKey, serverTs});
    }

    void upgradeAllOutStatus(String peerKey, int newStatus) {
        getWritableDatabase().execSQL(
                "UPDATE " + T + " SET status=MAX(status,?) WHERE peer_key=? AND dir='out'",
                new Object[]{newStatus, peerKey});
    }

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
                null, null, "server_ts ASC, id ASC");
        List<MessageItem> list = new ArrayList<>();
        while (c.moveToNext()) list.add(toItem(c));
        c.close();
        return list;
    }

    /** Latest message per peer, sorted newest first. Returns list of (peerKey, [snippet, timeStr, tsStr]). */
    List<Pair<String, String[]>> getConversationSummaries() {
        String sql =
                "SELECT m.peer_key, m.dir, m.text, m.att_id, m.server_ts" +
                " FROM " + T + " m" +
                " INNER JOIN (SELECT peer_key, MAX(server_ts) mts FROM " + T + " WHERE status!=" + ST_DELETED + " GROUP BY peer_key) x" +
                " ON m.peer_key=x.peer_key AND m.server_ts=x.mts" +
                " ORDER BY m.server_ts DESC";
        Cursor c = getReadableDatabase().rawQuery(sql, null);
        List<Pair<String, String[]>> out = new ArrayList<>();
        while (c.moveToNext()) {
            String pk   = c.getString(0);
            String dir  = c.getString(1);
            String txt  = c.getString(2);
            String att  = c.getString(3);
            long   ts   = c.getLong(4);
            String snip = !isEmpty(txt) ? txt : (!isEmpty(att) ? "📷 Photo" : "");
            if ("out".equals(dir)) snip = "You: " + snip;
            out.add(new Pair<>(pk, new String[]{snip, formatShortTime(ts), String.valueOf(ts)}));
        }
        c.close();
        return out;
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
        if ("image".equals(type)) {
            if (!isEmpty(locUri))
                item = new MessageItem(from, locUri, isEmpty(cap) ? null : cap, status, true);
            else
                item = new MessageItem(from, attId, mime, isEmpty(cap) ? null : cap, status);
        } else {
            item = new MessageItem(from, isEmpty(text) ? "" : text, status);
        }
        item.serverTs    = ts;
        item.quoteText   = isEmpty(qt) ? null : qt;
        item.quoteAuthor = isEmpty(qa) ? null : qa;
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
        return item;
    }
}
