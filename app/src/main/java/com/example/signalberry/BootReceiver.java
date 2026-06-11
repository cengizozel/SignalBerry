package com.example.signalberry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import static com.example.signalberry.Utils.isEmpty;

/** Restart the message listener after reboot — notifications shouldn't depend
 *  on the user remembering to open the app. */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        android.content.SharedPreferences prefs =
                ctx.getSharedPreferences("signalberry", Context.MODE_PRIVATE);
        if (isEmpty(prefs.getString("ip", "")) || isEmpty(prefs.getString("number", ""))) return;
        Intent svc = new Intent(ctx, MessageService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
            else ctx.startService(svc);
        } catch (Exception ignored) {
            // background-start restrictions on modern Android; the Q10 target is fine
        }
    }
}
