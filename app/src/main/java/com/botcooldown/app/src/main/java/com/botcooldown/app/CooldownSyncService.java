package com.botcooldown.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class CooldownSyncService extends Service {

    private static final String TAG = "CooldownSync";
    private static final String CHANNEL_ID = "cooldown_sync";
    private static final String EVENTS_URL =
            "https://cooldown-detector.onrender.com/events";

    private ScheduledExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Cooldown activo")
                .setContentText("Sincronizando con el bot")
                .setOngoing(true)
                .build();

        startForeground(2001, notification);

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

            JSONArray events = new JSONArray(response.toString());
            Log.d(TAG, "Eventos recibidos: " + events.length());

            // En el siguiente paso conectaremos los eventos
            // con los temporizadores de la aplicación.

        } catch (Exception e) {
            Log.e(TAG, "Error al consultar Render", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Sincronización de Cooldown",
                    NotificationManager.IMPORTANCE_LOW);

            NotificationManager manager =
                    getSystemService(NotificationManager.class);

            manager.createNotificationChannel(channel);
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
