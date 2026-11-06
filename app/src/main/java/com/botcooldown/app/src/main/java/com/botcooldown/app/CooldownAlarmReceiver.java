package com.botcooldown.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class CooldownAlarmReceiver extends BroadcastReceiver {
    public static final String ACTION_READY = "com.botcooldown.app.COOLDOWN_READY";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_END_TIME = "endTime";
    public static final String EXTRA_ALARM_ID = "alarmId";
    public static final String EXTRA_ACCOUNT_HASH = "accountHash";
    public static final String EXTRA_EVENT_ID = "syncedEventId";
    public static final String CHANNEL_ID = "botcooldown_ready";
    private static final String PREFS_NAME = "botcooldown_preferences";
    private static final String LINK_PREFS = "botcooldown_link";
    private static final String PREF_MANUAL_TIMERS = "manual_timers";
    private static final String PREF_API_TOKEN = "api_token";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_READY.equals(intent.getAction())) return;
        String command = intent.getStringExtra(EXTRA_COMMAND);
        long end = intent.getLongExtra(EXTRA_END_TIME, 0L);
        int id = intent.getIntExtra(EXTRA_ALARM_ID, -1);
        String accountHash = intent.getStringExtra(EXTRA_ACCOUNT_HASH);
        String eventId = intent.getStringExtra(EXTRA_EVENT_ID);
        if (command == null || command.trim().isEmpty() || end <= 0 || id <= 0
                || System.currentTimeMillis() < end) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean synced = accountHash != null && eventId != null;
        String dedupeKey = null;
        if (synced) {
            String token = context.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE)
                    .getString(PREF_API_TOKEN, "");
            if (!accountHash.equals(tokenHash(token))) return;
            if (!validSynced(prefs, accountHash, eventId, command, end, id)) return;
            dedupeKey = "notified_synced_" + accountHash + "_" + eventId + "_" + end;
            if (prefs.getBoolean(dedupeKey, false)) return;
        } else if (!validManual(prefs, command, end, id)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Cooldowns listos",
                    NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Avisos cuando un temporizador termina");
            manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent content = PendingIntent.getActivity(context, id, open, flags);
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown")
                    .setContentText(command + " ya está disponible")
                    .setContentIntent(content).setAutoCancel(true).build();
        } else {
            n = new Notification.Builder(context)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown")
                    .setContentText(command + " ya está disponible")
                    .setContentIntent(content).setAutoCancel(true).build();
        }
        manager.notify(id, n);
        if (synced && dedupeKey != null) prefs.edit().putBoolean(dedupeKey, true).apply();
    }

    private boolean validManual(SharedPreferences prefs, String command, long end, int id) {
        try {
            JSONArray timers = new JSONArray(prefs.getString(PREF_MANUAL_TIMERS, "[]"));
            for (int i = 0; i < timers.length(); i++) {
                JSONObject t = timers.optJSONObject(i);
                if (t != null && t.optInt("alarmRequestCode", -1) == id
                        && t.optLong("endTime", 0L) == end
                        && command.equals(t.optString("command", ""))) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private boolean validSynced(SharedPreferences prefs, String hash, String eventId,
                                String command, long end, int id) {
        try {
            JSONArray timers = new JSONArray(prefs.getString("synced_timers_" + hash, "[]"));
            for (int i = 0; i < timers.length(); i++) {
                JSONObject t = timers.optJSONObject(i);
                if (t == null) continue;
                String db = t.optString("databaseId", "");
                String savedCommand = t.optString("command", "");
                long savedEnd = t.optLong("endTime", 0L);
                int savedId = t.optInt("alarmRequestCode",
                        1000000000 + (db.hashCode() & 0x3fffffff));
                if (eventId.equals(db) && command.equals(savedCommand)
                        && end == savedEnd && id == savedId) return true;
            }
        } catch (Exception ignored) { }
        return false;
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
