
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
import java.security.MessageDigest;
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

    private static final String LINK_PREFS = "botcooldown_link";
    private static final String PREF_API_TOKEN = "api_token";

    private static final String PREFS_NAME = "botcooldown_sync";
    private static final String PREFS_SEEN = "service_seen_events";
    private static final String PREF_ACCOUNT = "service_account";

    private ScheduledExecutorService executor;
    private SharedPreferences prefs;
    private SharedPreferences linkPrefs;
    private NotificationManager notificationManager;

    private final Set<String> seenEvents =
            ConcurrentHashMap.newKeySet();

    private final Set<String> scheduledEvents =
            ConcurrentHashMap.newKeySet();

    private volatile String activeAccount = "";

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        linkPrefs = getSharedPreferences(LINK_PREFS, MODE_PRIVATE);

        notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createChannels();

        String token = linkPrefs.getString(PREF_API_TOKEN, "");
        activeAccount = tokenHash(token);

        String savedAccount = prefs.getString(PREF_ACCOUNT, "");

        if (!activeAccount.equals(savedAccount)) {
            prefs.edit()
                    .putString(PREF_ACCOUNT, activeAccount)
                    .remove(PREFS_SEEN)
                    .apply();
        } else {
            Set<String> saved = prefs.getStringSet(
                    PREFS_SEEN, new HashSet<>());

            if (saved != null) {
                seenEvents.addAll(saved);
            }
        }

        Notification foregroundNotification;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            foregroundNotification =
                    new Notification.Builder(this, SERVICE_CHANNEL)
                            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle("BotCooldown activo")
                            .setContentText("Sincronización de cooldowns")
                            .setOngoing(true)
                            .build();
        } else {
            foregroundNotification =
                    new Notification.Builder(this)
                            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                            .setContentTitle("BotCooldown activo")
                            .setContentText("Sincronización de cooldowns")
                            .setOngoing(true)
                            .build();
        }

        startForeground(2001, foregroundNotification);

        executor = Executors.newSingleThreadScheduledExecutor();

        executor.scheduleWithFixedDelay(
                this::checkEvents, 0, 15, TimeUnit.SECONDS);
    }

    private String tokenHash(String token) {
        if (token == null || token.isEmpty()) {
            return "";
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    token.getBytes(StandardCharsets.UTF_8));

            StringBuilder result = new StringBuilder();

            for (byte b : bytes) {
                result.append(String.format("%02x", b));
            }

            return result.toString();

        } catch (Exception e) {
            return "";
        }
    }

    private void checkEvents() {
        HttpURLConnection connection = null;

        try {
            String token = linkPrefs.getString(PREF_API_TOKEN, "");

            // Sin vinculación no se consultan eventos.
            if (token == null || token.trim().isEmpty()) {
                return;
            }

            String accountHash = tokenHash(token);

            // Detectar si cambió la cuenta vinculada.
            if (!accountHash.equals(activeAccount)) {
                activeAccount = accountHash;
                seenEvents.clear();
                scheduledEvents.clear();

                prefs.edit()
                        .putString(PREF_ACCOUNT, accountHash)
                        .remove(PREFS_SEEN)
                        .apply();
            }

            connection = (HttpURLConnection)
                    new URL(EVENTS_URL).openConnection();

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty(
                    "Accept", "application/json");
            connection.setRequestProperty(
                    "Authorization", "Bearer " + token);

            int responseCode = connection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                Log.w(TAG, "Token inválido o cuenta desvinculada.");
                return;
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Render respondió HTTP " + responseCode);
                return;
            }

            StringBuilder response = new StringBuilder();

            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(
                                 input, StandardCharsets.UTF_8))) {

                String line;

                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            // Comprobar que la cuenta no cambió durante la petición.
            String currentToken =
                    linkPrefs.getString(PREF_API_TOKEN, "");

            if (!token.equals(currentToken)) {
                return;
            }

            JSONArray events = new JSONArray(response.toString());

            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);

                String command = event.optString("command", "");
                String databaseId = event.optString("id", "");
                long durationSeconds = event.optLong("duration", 0L);
                long startedAt = event.optLong("startedAt", 0L);

                if (command.trim().isEmpty()
                        || durationSeconds <= 0
                        || startedAt <= 0
                        || durationSeconds > Long.MAX_VALUE / 1000L) {
                    continue;
                }

                long durationMillis = durationSeconds * 1000L;

                if (startedAt > Long.MAX_VALUE - durationMillis) {
                    continue;
                }

                long endTime = startedAt + durationMillis;
                long remainingMillis =
                        endTime - System.currentTimeMillis();

                String eventId = !databaseId.isEmpty()
                        ? accountHash + ":" + databaseId
                        : accountHash + ":" + command + ":" + startedAt;

                if (seenEvents.add(eventId)) {
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
                            eventId,
                            command,
                            remainingMillis,
                            accountHash);
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
            String eventId,
            String command,
            long delayMillis,
            String accountHash) {

        if (executor == null || executor.isShutdown()) {
            return;
        }

        if (!scheduledEvents.add(eventId)) {
            return;
        }

        executor.schedule(() -> {
            try {
                String currentToken =
                        linkPrefs.getString(PREF_API_TOKEN, "");

                // No mostrar avisos de una cuenta anterior.
                if (currentToken == null
                        || currentToken.isEmpty()
                        || !accountHash.equals(tokenHash(currentToken))) {
                    return;
                }

                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(
                                android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    return;
                }

                Notification notification;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    notification =
                            new Notification.Builder(this, READY_CHANNEL)
                                    .setSmallIcon(
                                            android.R.drawable.ic_lock_idle_alarm)
                                    .setContentTitle("BotCooldown")
                                    .setContentText(
                                            command + " ya está disponible")
                                    .setAutoCancel(true)
                                    .build();
                } else {
                    notification =
                            new Notification.Builder(this)
                                    .setSmallIcon(
                                            android.R.drawable.ic_lock_idle_alarm)
                                    .setContentTitle("BotCooldown")
                                    .setContentText(
                                            command + " ya está disponible")
                                    .setAutoCancel(true)
                                    .build();
                }

                int notificationId =
                        eventId.hashCode() & 0x7fffffff;

                notificationManager.notify(
                        notificationId, notification);

            } catch (Exception e) {
                Log.e(TAG,
                        "No se pudo enviar la notificación", e);
            }

        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private void saveSeenEvents() {
        prefs.edit()
                .putStringSet(
                        PREFS_SEEN,
                        new HashSet<>(seenEvents))
                .apply();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel =
                    new NotificationChannel(
                            SERVICE_CHANNEL,
                            "Servicio de sincronización",
                            NotificationManager.IMPORTANCE_LOW);

            NotificationChannel readyChannel =
                    new NotificationChannel(
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
