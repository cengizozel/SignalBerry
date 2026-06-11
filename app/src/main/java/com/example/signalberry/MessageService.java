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

import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

import static com.example.signalberry.Utils.*;

/**
 * Owns the app's ONLY WebSocket to signal-api (REDESIGN §3.1). Every envelope
 * funnels into Repo.ingest(); this class only decides notifications. Activities
 * never open sockets — they observe Repo.
 *
 * Sticky: keeps running in background for notifications (API 18 has no Doze).
 */
public class MessageService extends Service {

    private static final int  NOTIF_ID_FG   = 1;
    private static final int  NOTIF_ID_MSG  = 2;
    private static final String CH_SERVICE   = "sb_service";
    private static final String CH_MESSAGES  = "sb_messages";

    private OkHttpClient client;
    private WebSocket ws;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int retrySec = 1;
    private boolean destroyed = false;
    private int wsGeneration = 0; // stale callbacks from a replaced socket are ignored

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
            // Android 12+ denies foreground starts from the background; running
            // on as a started-but-not-foreground service gets the process killed
            // with "did not call startForeground" — stop cleanly instead.
            if (Build.VERSION.SDK_INT >= 26) { stopSelf(); return START_NOT_STICKY; }
        }
        connect();
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        destroyed = true;
        wsGeneration++;
        if (ws != null) { ws.cancel(); ws = null; }
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ── WebSocket (single owner) ─────────────────────────────────────────────

    private void connect() {
        if (destroyed) return;
        SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        String ip     = prefs.getString("ip", "");
        String number = prefs.getString("number", "");
        if (isEmpty(ip) || isEmpty(number)) return;

        // never leak the previous socket (the old code opened a new one per
        // onStartCommand and kept the old reader thread alive)
        if (ws != null) { ws.cancel(); ws = null; }
        final int gen = ++wsGeneration;

        if (client == null) {
            client = new OkHttpClient.Builder()
                    .readTimeout(0, TimeUnit.MILLISECONDS)
                    .pingInterval(30, TimeUnit.SECONDS)
                    .build();
        }

        try {
            String base  = normalizeBase(ip);
            String wsUrl = toWs(base) + "/v1/receive/" + URLEncoder.encode(number, "UTF-8");
            Request req  = Auth.apply(new Request.Builder().url(wsUrl)).build();
            ws = client.newWebSocket(req, new WebSocketListener() {
                @Override public void onOpen(WebSocket s, Response r) {
                    if (gen != wsGeneration) { s.cancel(); return; }
                    retrySec = 1;
                    // reconnect = catch up what the socket missed + retry unreported sends
                    new Thread(() -> {
                        Repo repo = Repo.get(MessageService.this);
                        repo.catchUp((peerKey, count, snippet) -> {
                            SharedPreferences p2 = getSharedPreferences("signalberry", MODE_PRIVATE);
                            if (p2.getBoolean("mute_" + peerKey, false)) return;
                            String name = p2.getString("contact_name_" + peerKey, peerKey);
                            String num  = p2.getString("contact_num_" + peerKey, "");
                            String uuid = p2.getString("contact_uuid_" + peerKey, "");
                            showMessageNotif(peerKey, name,
                                    count > 1 ? count + " new messages" : snippet, num, uuid);
                        });
                        repo.drainReportQueue();
                    }, "ws-catchup").start();
                }
                @Override public void onMessage(WebSocket s, String text) {
                    if (gen == wsGeneration) handleFrame(text);
                }
                @Override public void onMessage(WebSocket s, ByteString bytes) {
                    if (gen == wsGeneration) handleFrame(bytes.utf8());
                }
                @Override public void onClosed(WebSocket s, int c, String r) {
                    if (gen != wsGeneration) return;
                    ws = null; scheduleReconnect();
                }
                @Override public void onFailure(WebSocket s, Throwable t, Response r) {
                    if (gen != wsGeneration) return;
                    ws = null; scheduleReconnect();
                }
            });
        } catch (Exception e) { scheduleReconnect(); }
    }

    private void scheduleReconnect() {
        if (destroyed) return;
        handler.postDelayed(this::connect, retrySec * 1000L);
        retrySec = Math.min(retrySec * 2, 60);
    }

    // ── envelope handling: parse → Repo → maybe notify ──────────────────────

    private void handleFrame(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject env  = root.optJSONObject("envelope");
            if (env == null) return;

            Repo.IngestResult r = Repo.get(this).ingest(env);
            if (r == null || !r.newMessage || !"in".equals(r.dir)) return;

            SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
            String openPeer = prefs.getString("open_chat_peer", "");
            if (r.peerKey.equals(openPeer)) return;
            if (prefs.getBoolean("mute_" + r.peerKey, false)) return;

            String name = prefs.getString("contact_name_" + r.peerKey, "");
            String srcNum  = env.optString("sourceNumber", "");
            String srcUuid = env.optString("sourceUuid", "");
            if (isEmpty(name)) name = isEmpty(srcNum) ? shortUuid(srcUuid) : srcNum;

            showMessageNotif(r.peerKey, name, r.snippet, srcNum, srcUuid);
            Repo.get(this).advanceNotifiedTs(r.peerKey, env.optLong("timestamp", 0));
        } catch (Exception ignored) {}
    }

    // ── Notifications ────────────────────────────────────────────────────────

    private void showMessageNotif(String peerKey, String sender, String body,
                                  String number, String uuid) {
        SharedPreferences prefs = getSharedPreferences("signalberry", MODE_PRIVATE);
        int count = prefs.getInt("notif_count_" + peerKey, 0) + 1;
        prefs.edit().putInt("notif_count_" + peerKey, count).apply();

        // back stack: Messages behind Chat, so back from a cold-start
        // notification lands on the conversation list, not the launcher
        Intent open = new Intent(this, Chat.class)
                .putExtra("peer_name",   sender)
                .putExtra("peer_number", number)
                .putExtra("peer_uuid",   uuid);
        androidx.core.app.TaskStackBuilder tsb = androidx.core.app.TaskStackBuilder.create(this)
                .addNextIntent(new Intent(this, Messages.class))
                .addNextIntent(open);
        int flags = Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = tsb.getPendingIntent(peerKey.hashCode(), flags);

        Notification notif = new NotificationCompat.Builder(this, CH_MESSAGES)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(count > 1 ? sender + " (" + count + ")" : sender)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(peerKey.hashCode(), notif);
    }

    /** Chat calls this (via Repo prefs) when a thread is opened. */
    static void clearNotification(Context ctx, String peerKey) {
        ctx.getSharedPreferences("signalberry", Context.MODE_PRIVATE)
                .edit().remove("notif_count_" + peerKey).apply();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(peerKey.hashCode());
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
