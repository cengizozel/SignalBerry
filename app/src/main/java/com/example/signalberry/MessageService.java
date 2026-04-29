package com.example.signalberry;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import android.util.Log;

import static com.example.signalberry.Utils.*;

public class MessageService extends Service {

    private static final int  NOTIF_ID_FG   = 1;
    private static final int  NOTIF_ID_MSG  = 2;
    private static final String CH_SERVICE   = "sb_service";
    private static final String CH_MESSAGES  = "sb_messages";

    private OkHttpClient client;
    private WebSocket ws;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retrySec = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannels();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID_FG, buildForegroundNotif(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID_FG, buildForegroundNotif());
            }
        } catch (Exception e) {
            // Android 12+ may deny if system considers app background at start time.
            // Run without foreground status — notifications will still work while app is alive.
        }
        connect();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (ws != null) { ws.cancel(); ws = null; }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ── WebSocket ────────────────────────────────────────────────────────────

    private void connect() {
        SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        String ip     = prefs.getString("ip", "");
        String number = prefs.getString("number", "");
        if (isEmpty(ip) || isEmpty(number)) return;

        if (client == null) {
            client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();
        }

        try {
            String base  = normalizeBase(ip);
            String wsUrl = toWs(base) + "/v1/receive/" + URLEncoder.encode(number, "UTF-8");
            Log.d("MsgService", "connecting: " + wsUrl);
            Request req  = new Request.Builder().url(wsUrl).build();
            ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket s, Response r)           { retrySec = 1; Log.d("MsgService", "ws open"); }
                @Override public void onMessage(WebSocket s, String text)       { Log.d("MsgService", "ws msg: " + text.substring(0, Math.min(120, text.length()))); handleEnvelope(text); }
                @Override public void onMessage(WebSocket s, ByteString bytes)  { handleEnvelope(bytes.utf8()); }
                @Override public void onClosed(WebSocket s, int c, String r)    { ws = null; Log.d("MsgService", "ws closed"); scheduleReconnect(); }
                @Override public void onFailure(WebSocket s, Throwable t, Response r) { ws = null; Log.e("MsgService", "ws fail: " + t); scheduleReconnect(); }
            });
        } catch (Exception e) { Log.e("MsgService", "connect ex: " + e); scheduleReconnect(); }
    }

    private void scheduleReconnect() {
        handler.postDelayed(this::connect, retrySec * 1000L);
        retrySec = Math.min(retrySec * 2, 60);
    }

    // ── Message parsing ──────────────────────────────────────────────────────

    private void handleEnvelope(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject env  = root.optJSONObject("envelope");
            if (env == null) return;

            String srcNum  = env.optString("sourceNumber", "");
            String srcUuid = env.optString("sourceUuid", "");

            JSONObject data = env.optJSONObject("dataMessage");
            if (data == null) return;

            String text  = data.optString("message", data.optString("text", "")).trim();
            JSONArray atts = data.optJSONArray("attachments");
            boolean hasAtt = atts != null && atts.length() > 0;

            if (text.isEmpty() && !hasAtt) return;

            // Don't notify if this chat is currently open
            SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
            String openPeer = prefs.getString("open_chat_peer", "");
            String senderKey = digits(srcNum).isEmpty() ? srcUuid : digits(srcNum);
            if (!openPeer.isEmpty() && openPeer.equals(senderKey)) return;

            // Resolve display name
            String name = prefs.getString("contact_name_" + senderKey, "");
            if (isEmpty(name)) name = isEmpty(srcNum) ? shortUuid(srcUuid) : srcNum;

            String body = !text.isEmpty() ? text : (hasAtt ? "📷 Photo" : "");
            showMessageNotif(name, body, srcNum, srcUuid);

        } catch (Exception ignored) {}
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private void showMessageNotif(String sender, String body, String number, String uuid) {
        Log.d("MsgService", "showing notif from=" + sender + " body=" + body);
        Intent open = new Intent(this, Chat.class)
                .putExtra("peer_name",   sender)
                .putExtra("peer_number", number)
                .putExtra("peer_uuid",   uuid)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), open, flags);

        Notification notif = new NotificationCompat.Builder(this, CH_MESSAGES)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(sender)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID_MSG, notif);
    }

    private Notification buildForegroundNotif() {
        Intent open = new Intent(this, Messages.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flags);

        return new NotificationCompat.Builder(this, CH_SERVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle("SignalBerry")
                .setContentText("Listening for messages")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pi)
                .build();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, "Background service", NotificationManager.IMPORTANCE_MIN);
        svc.setShowBadge(false);
        nm.createNotificationChannel(svc);

        NotificationChannel msg = new NotificationChannel(
                CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(msg);
    }
}
