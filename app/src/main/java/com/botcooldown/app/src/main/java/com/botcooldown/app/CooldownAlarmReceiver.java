
package com.botcooldown.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class CooldownAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_READY =
            "com.botcooldown.app.COOLDOWN_READY";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_END_TIME = "endTime";
    public static final String EXTRA_ALARM_ID = "alarmId";
    public static final String CHANNEL_ID = "botcooldown_ready";

    private static final String PREFS_NAME = "botcooldown_preferences";
    private static final String PREF_MANUAL_TIMERS = "manual_timers";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !ACTION_READY.equals(intent.getAction())) {
            return;
        }

        String command = intent.getStringExtra(EXTRA_COMMAND);
        long endTime = intent.getLongExtra(EXTRA_END_TIME, 0L);
        int alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);

        if (command == null || command.trim().isEmpty()
                || endTime <= 0 || alarmId <= 0) {
            return;
        }

        if (System.currentTimeMillis() < endTime) {
            return;
        }

        if (!isCurrentTimer(context, command, endTime, alarmId)) {
            Log.d("BotCooldown", "Alarma antigua ignorada: " + alarmId);
            return;
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

        manager.notify(alarmId, notification);
    }

    private boolean isCurrentTimer(
            Context context,
            String command,
            long endTime,
            int alarmId) {

        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);

        String savedTimers = preferences.getString(
                PREF_MANUAL_TIMERS, "[]");

        try {
            JSONArray timers = new JSONArray(savedTimers);

            for (int i = 0; i < timers.length(); i++) {
                JSONObject timer = timers.getJSONObject(i);

                String savedCommand = timer.optString("command", "");
                long savedEndTime = timer.optLong("endTime", 0L);
                int savedAlarmId = timer.optInt("alarmRequestCode", -1);

                if (savedAlarmId == alarmId
                        && savedEndTime == endTime
                        && command.equals(savedCommand)) {
                    return true;
                }
            }
        } catch (Exception e) {
            Log.e("BotCooldown", "Error validando temporizador", e);
        }

        return false;
    }
}
