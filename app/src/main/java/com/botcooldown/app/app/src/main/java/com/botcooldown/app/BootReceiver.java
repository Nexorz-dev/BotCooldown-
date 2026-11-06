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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS_NAME = "botcooldown_preferences";
    private static final String LINK_PREFS = "botcooldown_link";
    private static final String PREF_MANUAL_TIMERS = "manual_timers";
    private static final String PREF_API_TOKEN = "api_token";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        try {
            JSONArray manual = new JSONArray(prefs.getString(PREF_MANUAL_TIMERS, "[]"));
            for (int i = 0; i < manual.length(); i++) {
                JSONObject item = manual.optJSONObject(i);
                if (item == null) continue;
                String command = item.optString("command", "");
                long end = item.optLong("endTime", 0L);
                int id = item.optInt("alarmRequestCode", -1);
                if (command.trim().isEmpty() || end <= 0 || id <= 0) continue;
                schedule(context, manager, command, end, id, null, null);
            }
            String token = context.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE)
                    .getString(PREF_API_TOKEN, "");
            String hash = tokenHash(token);
            if (!hash.isEmpty()) {
                JSONArray synced = new JSONArray(prefs.getString("synced_timers_" + hash, "[]"));
                for (int i = 0; i < synced.length(); i++) {
                    JSONObject item = synced.optJSONObject(i);
                    if (item == null) continue;
                    String command = item.optString("command", "");
                    String databaseId = item.optString("databaseId", "");
                    long end = item.optLong("endTime", 0L);
                    long duration = item.optLong("durationMillis", 0L);
                    if (command.trim().isEmpty() || databaseId.trim().isEmpty()
                            || end <= 0 || duration <= 0) continue;
                    int id = 1000000000 + (databaseId.hashCode() & 0x3fffffff);
                    schedule(context, manager, command, end, id, hash, databaseId);
                }
            }
        } catch (Exception e) {
            Log.e("BotCooldown", "Error al restaurar alarmas tras reiniciar", e);
        }
    }
    private void schedule(Context context, AlarmManager manager, String command,
                          long end, int id, String accountHash, String eventId) {
        Intent alarm = new Intent(context, CooldownAlarmReceiver.class);
        alarm.setAction(CooldownAlarmReceiver.ACTION_READY);
        alarm.putExtra(CooldownAlarmReceiver.EXTRA_COMMAND, command);
        alarm.putExtra(CooldownAlarmReceiver.EXTRA_END_TIME, end);
        alarm.putExtra(CooldownAlarmReceiver.EXTRA_ALARM_ID, id);
        if (accountHash != null) {
            alarm.putExtra(CooldownAlarmReceiver.EXTRA_ACCOUNT_HASH, accountHash);
            alarm.putExtra(CooldownAlarmReceiver.EXTRA_EVENT_ID, eventId);
        }
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(context, id, alarm, flags);
        long trigger = Math.max(end, System.currentTimeMillis());
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (SecurityException e) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
        }
    }
    private String tokenHash(String token) {
        if (token == null || token.isEmpty()) return "";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { return ""; }
    }
}
