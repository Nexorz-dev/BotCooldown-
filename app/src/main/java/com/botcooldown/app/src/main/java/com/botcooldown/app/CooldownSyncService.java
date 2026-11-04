package com.botcooldown.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CooldownSyncService extends Service {
    public static final String ACTION_COOLDOWN_SYNCED = "com.botcooldown.app.COOLDOWN_SYNCED";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_END_TIME = "endTime";
    public static final String EXTRA_DURATION = "duration";
    public static final String EXTRA_EVENT_ID = "eventId";
    private static final String TAG = "CooldownSync";
    private static final String SERVICE_CHANNEL = "cooldown_sync";
    private static final String ERROR_CHANNEL = "botcooldown_errors";
    private static final int SYNC_ERROR_NOTIFICATION_ID = 3201;
    private static final String EVENTS_URL = "https://cooldown-detector.onrender.com/events";
    private static final String LINK_PREFS = "botcooldown_link";
    private static final String PREF_API_TOKEN = "api_token";
    private static final String PREFS_NAME = "botcooldown_sync";
    private static final String COOLDOWN_PREFS = "botcooldown_preferences";
    private static final String PREF_SYNCED_TIMERS_PREFIX = "synced_timers_";
    private static final String PREFS_SEEN = "service_seen_events";
    private static final String PREF_ACCOUNT = "service_account";
    private ScheduledExecutorService executor;
    private SharedPreferences prefs;
    private SharedPreferences linkPrefs;
    private NotificationManager notificationManager;
    private final Set<String> seenEvents = ConcurrentHashMap.newKeySet();
    private volatile String activeAccount = "";

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        linkPrefs = getSharedPreferences(LINK_PREFS, MODE_PRIVATE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannels();
        String token = linkPrefs.getString(PREF_API_TOKEN, "");
        activeAccount = tokenHash(token);
        String savedAccount = prefs.getString(PREF_ACCOUNT, "");
        if (!activeAccount.equals(savedAccount)) {
            prefs.edit().putString(PREF_ACCOUNT, activeAccount).remove(PREFS_SEEN).apply();
        } else {
            Set<String> saved = prefs.getStringSet(PREFS_SEEN, new HashSet<>());
            if (saved != null) seenEvents.addAll(saved);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            n = new Notification.Builder(this, SERVICE_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown activo")
                    .setContentText("Sincronización de cooldowns")
                    .setOngoing(true).build();
        } else {
            n = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown activo")
                    .setContentText("Sincronización de cooldowns")
                    .setOngoing(true).build();
        }
        startForeground(2001, n);
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::checkEvents, 0, 15, TimeUnit.SECONDS);
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

    private void checkEvents() {
        HttpURLConnection connection = null;
        try {
            String token = linkPrefs.getString(PREF_API_TOKEN, "");
            if (token == null || token.trim().isEmpty()) return;
            String accountHash = tokenHash(token);
            if (!accountHash.equals(activeAccount)) {
                activeAccount = accountHash;
                seenEvents.clear();
                prefs.edit().putString(PREF_ACCOUNT, accountHash).remove(PREFS_SEEN).apply();
            }
            connection = (HttpURLConnection) new URL(EVENTS_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + token);
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                Log.w(TAG, "Token inválido o cuenta desvinculada.");
                return;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Render respondió HTTP " + code);
                notifySyncError("Render respondió con un error (HTTP "
                        + code + "). La sincronización se reintentará automáticamente.");
                return;
            }
            StringBuilder response = new StringBuilder();
            try (InputStream in = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
            }
            String currentToken = linkPrefs.getString(PREF_API_TOKEN, "");
            if (!token.equals(currentToken) || !accountHash.equals(activeAccount)) return;
            JSONArray events = new JSONArray(response.toString());
            clearSyncErrorNotification();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                String command = event.optString("command", "");
                String databaseId = event.optString("id", "");
                long seconds = event.optLong("duration", 0L);
                long startedAt = event.optLong("startedAt", 0L);
                if (command.trim().isEmpty() || databaseId.trim().isEmpty()
                        || seconds <= 0 || startedAt <= 0 || seconds > Long.MAX_VALUE / 1000L) continue;
                long duration = seconds * 1000L;
                if (startedAt > Long.MAX_VALUE - duration) continue;
                long endTime = startedAt + duration;
                String eventId = accountHash + ":" + databaseId;

                // Persist and schedule synced alarms inside the foreground service.
                // The Activity's dynamically registered receiver may not exist
                // while the app is in the background or its screen is closed.
                persistAndScheduleSyncedAlarm(command, databaseId, duration,
                        endTime, accountHash);

                if (seenEvents.add(eventId)) saveSeenEvents();
                Intent synced = new Intent(ACTION_COOLDOWN_SYNCED);
                synced.setPackage(getPackageName());
                synced.putExtra(EXTRA_COMMAND, command);
                synced.putExtra(EXTRA_END_TIME, endTime);
                synced.putExtra(EXTRA_DURATION, duration);
                synced.putExtra(EXTRA_EVENT_ID, databaseId);
                sendBroadcast(synced);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error al sincronizar con Render", e);
            notifySyncError("No se pudo sincronizar con Render. Comprueba tu conexión; BotCooldown volverá a intentarlo automáticamente.");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void persistAndScheduleSyncedAlarm(String command, String databaseId,
                                               long duration, long endTime,
                                               String accountHash) {
        if (command == null || command.trim().isEmpty()
                || databaseId == null || databaseId.trim().isEmpty()
                || accountHash == null || accountHash.isEmpty()
                || duration <= 0 || endTime <= System.currentTimeMillis()) {
            return;
        }

        int alarmId = 1000000000 + (databaseId.hashCode() & 0x3fffffff);
        SharedPreferences cooldownPrefs = getSharedPreferences(
                COOLDOWN_PREFS, MODE_PRIVATE);
        String key = PREF_SYNCED_TIMERS_PREFIX + accountHash;

        try {
            JSONArray timers = new JSONArray(cooldownPrefs.getString(key, "[]"));
            boolean found = false;
            for (int i = 0; i < timers.length(); i++) {
                JSONObject item = timers.optJSONObject(i);
                if (item != null && databaseId.equals(
                        item.optString("databaseId", ""))) {
                    item.put("command", command);
                    item.put("durationMillis", duration);
                    item.put("endTime", endTime);
                    item.put("alarmRequestCode", alarmId);
                    found = true;
                    break;
                }
            }
            if (!found) {
                JSONObject item = new JSONObject();
                item.put("command", command);
                item.put("databaseId", databaseId);
                item.put("durationMillis", duration);
                item.put("endTime", endTime);
                item.put("alarmRequestCode", alarmId);
                timers.put(item);
            }
            // The alarm receiver validates this record before posting the notification.
            // Commit synchronously so a process shutdown cannot leave the alarm
            // scheduled while its validation data is still only queued for disk.
            boolean persisted = cooldownPrefs.edit()
                    .putString(key, timers.toString())
                    .commit();
            if (!persisted) {
                Log.e(TAG, "No se pudo persistir el cooldown sincronizado " + databaseId);
                return;
            }

            Intent alarm = new Intent(this, CooldownAlarmReceiver.class);
            alarm.setAction(CooldownAlarmReceiver.ACTION_READY);
            alarm.putExtra(CooldownAlarmReceiver.EXTRA_COMMAND, command);
            alarm.putExtra(CooldownAlarmReceiver.EXTRA_END_TIME, endTime);
            alarm.putExtra(CooldownAlarmReceiver.EXTRA_ALARM_ID, alarmId);
            alarm.putExtra(CooldownAlarmReceiver.EXTRA_ACCOUNT_HASH, accountHash);
            alarm.putExtra(CooldownAlarmReceiver.EXTRA_EVENT_ID, databaseId);

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, alarmId, alarm, flags);
            AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (alarms == null) return;

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        && alarms.canScheduleExactAlarms()) {
                    alarms.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, endTime, pendingIntent);
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarms.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, endTime, pendingIntent);
                } else {
                    alarms.setExact(AlarmManager.RTC_WAKEUP, endTime, pendingIntent);
                }
            } catch (SecurityException denied) {
                alarms.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, endTime, pendingIntent);
                Log.w(TAG, "Permiso de alarma exacta no disponible; se programó alarma flexible.");
            }
        } catch (Exception e) {
            Log.e(TAG, "No se pudo guardar/programar el cooldown sincronizado " + databaseId, e);
        }
    }


    private void notifySyncError(String message) {
        if (notificationManager == null) return;
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences errorPrefs = getSharedPreferences("botcooldown_error_state", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastShown = errorPrefs.getLong("sync_error_last_shown", 0L);
        if (now - lastShown < 10 * 60 * 1000L) return;
        errorPrefs.edit().putLong("sync_error_last_shown", now).apply();
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, SYNC_ERROR_NOTIFICATION_ID,
                openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, ERROR_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("BotCooldown: error de sincronización")
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setContentIntent(contentIntent).setAutoCancel(true).build();
        } else {
            notification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("BotCooldown: error de sincronización")
                    .setContentText(message).setContentIntent(contentIntent)
                    .setAutoCancel(true).build();
        }
        notificationManager.notify(SYNC_ERROR_NOTIFICATION_ID, notification);
    }

    private void clearSyncErrorNotification() {
        if (notificationManager != null) notificationManager.cancel(SYNC_ERROR_NOTIFICATION_ID);
        getSharedPreferences("botcooldown_error_state", MODE_PRIVATE).edit()
                .putLong("sync_error_last_shown", 0L).apply();
    }

    private void saveSeenEvents() {
        prefs.edit().putStringSet(PREFS_SEEN, new HashSet<>(seenEvents)).apply();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    SERVICE_CHANNEL, "Servicio de sincronización",
                    NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(channel);
            NotificationChannel errorChannel = new NotificationChannel(
                    ERROR_CHANNEL, "Avisos de errores", NotificationManager.IMPORTANCE_HIGH);
            errorChannel.setDescription("Problemas de sincronización de BotCooldown");
            notificationManager.createNotificationChannel(errorChannel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
