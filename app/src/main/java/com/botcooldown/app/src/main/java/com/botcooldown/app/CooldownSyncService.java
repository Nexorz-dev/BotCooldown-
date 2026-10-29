
package com.botcooldown.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
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
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CooldownSyncService extends Service {

    public static final String ACTION_COOLDOWN_SYNCED =
            "com.botcooldown.app.COOLDOWN_SYNCED";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_END_TIME = "endTime";
    public static final String EXTRA_DURATION = "duration";

    private static final String TAG = "CooldownSync";
    private static final String SERVICE_CHANNEL = "cooldown_sync";
    private static final String READY_CHANNEL = "botcooldown_ready";
    private static final String EVENTS_URL =
            "https://cooldown-detector.onrender.com/events";
    private static final String PREFS_NAME = "botcooldown_sync";
    private static final String PREFS_SEEN = "service_seen_events";

    private ScheduledExecutorService executor;
    private SharedPreferences prefs;
    private NotificationManager notificationManager;

    private final Set<String> seenEvents = ConcurrentHashMap.newKeySet();
    private final Set<String> scheduledEvents = ConcurrentHashMap.newKeySet();

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Set<String> saved = prefs.getStringSet(PREFS_SEEN, new HashSet<>());
        if (saved != null) {
            seenEvents.addAll(saved);
        }

        createChannels();

        Notification foregroundNotification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            foregroundNotification = new Notification.Builder(this, SERVICE_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown activo")
                    .setContentText("Sincronizando cooldowns")
                    .setOngoing(true)
                    .build();
        } else {
            foregroundNotification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown activo")
                    .setContentText("Sincronizando cooldowns")
                    .setOngoing(true)
                    .build();
        }

        startForeground(2001, foregroundNotification);

        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(
                this::checkEvents, 0, 15, TimeUnit.SECONDS);
    }

    private void checkEvents() {
        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(EVENTS_URL).openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Render respondió con un error HTTP");
                return;
            }

            StringBuilder response = new StringBuilder();

            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            JSONArray events = new JSONArray(response.toString());

            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);

                String command = event.getString("command");
                long durationSeconds = event.getLong("duration");
                long startedAt = event.getLong("startedAt");

                if (durationSeconds <= 0) {
                    continue;
                }

                String eventId = command + ":" + startedAt;
                long durationMillis = durationSeconds * 1000L;
                long endTime = startedAt + durationMillis;
                long remainingMillis = endTime - System.currentTimeMillis();

                if (!seenEvents.contains(eventId)) {
                    seenEvents.add(eventId);
                    saveSeenEvents();

                    Intent synced = new Intent(ACTION_COOLDOWN_SYNCED);
                    synced.setPackage(getPackageName());
                    synced.putExtra(EXTRA_COMMAND, command);
                    synced.putExtra(EXTRA_END_TIME, endTime);
                    synced.putExtra(EXTRA_DURATION, durationMillis);
                    sendBroadcast(synced);
                }

                if (remainingMillis > 0) {
                    scheduleReadyNotification(
                            eventId, command, remainingMillis);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "Error al sincronizar con Render", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void scheduleReadyNotification(
            String eventId, String command, long delayMillis) {

        if (executor == null || executor.isShutdown()) {
            return;
        }

        if (!scheduledEvents.add(eventId)) {
            return;
        }

        executor.schedule(() -> {
            try {
                if (Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(
                                android.Manifest.permission.POST_NOTIFICATIONS)
                                != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                Notification notification;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notification = new Notification.Builder(this, READY_CHANNEL)
                            .setSmallIcon(
                                    android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle("BotCooldown")
                            .setContentText(command + " ya está disponible")
                            .setAutoCancel(true)
                            .build();
                } else {
                    notification = new Notification.Builder(this)
                            .setSmallIcon(
                                    android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle("BotCooldown")
                            .setContentText(command + " ya está disponible")
                            .setAutoCancel(true)
                            .build();
                }

                int notificationId = eventId.hashCode() & 0x7fffffff;
                notificationManager.notify(notificationId, notification);

            } catch (Exception e) {
                Log.e(TAG, "No se pudo enviar la notificación", e);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void saveSeenEvents() {
        prefs.edit()
                .putStringSet(PREFS_SEEN, new HashSet<>(seenEvents))
                .apply();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    SERVICE_CHANNEL,
                    "Servicio de sincronización",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationChannel readyChannel = new NotificationChannel(
                    READY_CHANNEL,
                    "Cooldowns listos",
                    NotificationManager.IMPORTANCE_DEFAULT);

            notificationManager.createNotificationChannel(serviceChannel);
            notificationManager.createNotificationChannel(readyChannel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
