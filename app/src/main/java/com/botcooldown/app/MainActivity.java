package com.botcooldown.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class BootReceiver extends BroadcastReceiver {

    private static final String PREFS_NAME = "botcooldown_preferences";
    private static final String PREF_MANUAL_TIMERS = "manual_timers";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null ||
                !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        SharedPreferences preferences =
                context.getSharedPreferences(
                        PREFS_NAME, Context.MODE_PRIVATE);

        String savedTimers = preferences.getString(
                PREF_MANUAL_TIMERS, "[]");

        try {
            JSONArray timers = new JSONArray(savedTimers);

            AlarmManager alarmManager =
                    (AlarmManager) context.getSystemService(
                            Context.ALARM_SERVICE);

            if (alarmManager == null) {
                return;
            }

            for (int i = 0; i < timers.length(); i++) {
                JSONObject timer = timers.getJSONObject(i);

                String command = timer.optString("command", "");
                long endTime = timer.optLong("endTime", 0L);
                int requestCode = timer.optInt(
                        "alarmRequestCode", -1);

                if (command.trim().isEmpty()
                        || endTime <= System.currentTimeMillis()
                        || requestCode <= 0) {
                    continue;
                }

                Intent alarmIntent = new Intent(
                        context, CooldownAlarmReceiver.class);

                alarmIntent.setAction(
                        CooldownAlarmReceiver.ACTION_READY);

                alarmIntent.putExtra(
                        CooldownAlarmReceiver.EXTRA_COMMAND, command);
                alarmIntent.putExtra(
                        CooldownAlarmReceiver.EXTRA_END_TIME, endTime);
                alarmIntent.putExtra(
                        CooldownAlarmReceiver.EXTRA_ALARM_ID, requestCode);

                PendingIntent pendingIntent = PendingIntent.getBroadcast(
                        context,
                        requestCode,
                        alarmIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && !alarmManager.canScheduleExactAlarms()) {

                    alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            endTime,
                            pendingIntent);

                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                    alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            endTime,
                            pendingIntent);

                } else {
                    alarmManager.setExact(
                            AlarmManager.RTC_WAKEUP,
                            endTime,
                            pendingIntent);
                }
            }

        } catch (Exception e) {
            Log.e(
                    "BotCooldown",
                    "Error al restaurar alarmas tras reiniciar",
                    e
            );
        }
    }
}
