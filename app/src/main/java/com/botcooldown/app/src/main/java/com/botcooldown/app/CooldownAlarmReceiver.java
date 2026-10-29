
package com.botcooldown.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public class CooldownAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_READY =
            "com.botcooldown.app.COOLDOWN_READY";
    public static final String EXTRA_COMMAND = "command";
    public static final String CHANNEL_ID = "botcooldown_ready";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_READY.equals(intent.getAction())) {
            return;
        }

        String command = intent.getStringExtra(EXTRA_COMMAND);

        if (command == null || command.trim().isEmpty()) {
            command = "Tu cooldown";
        }

        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager manager =
                (NotificationManager) context.getSystemService(
                        Context.NOTIFICATION_SERVICE);

        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cooldowns listos",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(
                    "Avisos cuando un temporizador termina");
            manager.createNotificationChannel(channel);
        }

        Notification notification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(
                            android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown")
                    .setContentText(command + " ya está disponible")
                    .setAutoCancel(true)
                    .build();
        } else {
            notification = new Notification.Builder(context)
                    .setSmallIcon(
                            android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown")
                    .setContentText(command + " ya está disponible")
                    .setAutoCancel(true)
                    .build();
        }

        manager.notify(
                (int) (System.currentTimeMillis() & 0x7fffffff),
                notification);
    }
}
