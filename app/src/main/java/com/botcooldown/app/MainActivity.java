
package com.botcooldown.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Cooldown> cooldowns = new ArrayList<>();
    private final Set<String> syncedIds = new HashSet<>();

    private LinearLayout timerList;
    private EditText commandInput;
    private EditText minutesInput;
    private EditText secondsInput;
    private NotificationManager notificationManager;
    private AlarmManager alarmManager;

    private static int nextAlarmId = 1000;

    private static final String CHANNEL_ID = "botcooldown_ready";
    private static final String EVENTS_URL =
            "https://cooldown-detector.onrender.com/events";

    private static final String BG = "#04090C";
    private static final int CARD = 0xFF0A1316;
    private static final int GREEN = 0xFF39FF74;
    private static final int GOLD = 0xFFFFC230;
    private static final int MUTED = 0xFF97ABA9;
    private static final int WHITE = 0xFFEFFFF5;

    private static class Cooldown {
        String command;
        String eventId;
        long endTime;
        long durationMillis;
        int alarmRequestCode;
        boolean notified = false;
        boolean notifyWhenDone = true;
        TextView view;
        LinearLayout card;

        Cooldown(String command, long durationMillis) {
            this.command = command;
            this.durationMillis = durationMillis;
            this.endTime = System.currentTimeMillis() + durationMillis;

            synchronized (MainActivity.class) {
                this.alarmRequestCode = nextAlarmId++;
                if (nextAlarmId > 2000000000) {
                    nextAlarmId = 1000;
                }
            }
        }
    }

    private final BroadcastReceiver syncReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CooldownSyncService.ACTION_COOLDOWN_SYNCED.equals(
                    intent.getAction())) {
                return;
            }

            String command = intent.getStringExtra(
                    CooldownSyncService.EXTRA_COMMAND);
            long endTime = intent.getLongExtra(
                    CooldownSyncService.EXTRA_END_TIME, 0L);
            long duration = intent.getLongExtra(
                    CooldownSyncService.EXTRA_DURATION, 0L);

            addSyncedCooldown(command, endTime, duration);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(4, 9, 12));
        getWindow().setNavigationBarColor(Color.rgb(4, 9, 12));

        notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        alarmManager =
                (AlarmManager) getSystemService(ALARM_SERVICE);

        createNotificationChannel();
        requestNotificationPermission();
        buildScreen();

        IntentFilter filter = new IntentFilter(
                CooldownSyncService.ACTION_COOLDOWN_SYNCED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, filter);
        }

        handler.post(tick);
        fetchActiveCooldowns();
        startCooldownSyncService();
    }

    private void addSyncedCooldown(
            String command, long endTime, long duration) {

        if (command == null || command.trim().isEmpty()
                || endTime <= System.currentTimeMillis()
                || duration <= 0) {
            return;
        }

        String eventId = command + ":" + endTime;

        if (!syncedIds.add(eventId)) {
            return;
        }

        Cooldown c = new Cooldown(command, duration);
        c.endTime = endTime;
        c.eventId = eventId;
        c.notifyWhenDone = false;

        createCooldownCard(c);
        cooldowns.add(c);
        updateCooldown(c);
    }

    private void fetchActiveCooldowns() {
        Thread thread = new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(EVENTS_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("Accept", "application/json");

                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return;
                }

                StringBuilder result = new StringBuilder();

                try (InputStream input = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                }

                JSONArray events = new JSONArray(result.toString());

                for (int i = 0; i < events.length(); i++) {
                    JSONObject event = events.getJSONObject(i);

                    String command = event.optString("command", "");
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

                    if (endTime <= System.currentTimeMillis()) {
                        continue;
                    }

                    final String savedCommand = command;
                    final long savedEndTime = endTime;
                    final long savedDuration = durationMillis;

                    handler.post(() -> {
                        if (!isFinishing() && !isDestroyed()) {
                            addSyncedCooldown(
                                    savedCommand,
                                    savedEndTime,
                                    savedDuration);
                        }
                    });
                }

            } catch (Exception e) {
                handler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(
                                MainActivity.this,
                                "No se pudieron recuperar los cooldowns",
                                Toast.LENGTH_SHORT).show();
                    }
                });
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        });

        thread.setDaemon(true);
        thread.start();
    }

    private void startCooldownSyncService() {
        Intent intent = new Intent(this, CooldownSyncService.class);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Toast.makeText(this,
                    "No se pudo iniciar la sincronización",
                    Toast.LENGTH_LONG).show();
        }
    }

    private PendingIntent getAlarmPendingIntent(Cooldown c) {
        Intent intent = new Intent(this, CooldownAlarmReceiver.class);
        intent.setAction(CooldownAlarmReceiver.ACTION_READY);
        intent.putExtra(CooldownAlarmReceiver.EXTRA_COMMAND, c.command);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(
                this, c.alarmRequestCode, intent, flags);
    }

    private void scheduleManualAlarm(Cooldown c) {
        if (!c.notifyWhenDone || alarmManager == null) {
            return;
        }

        try {
            PendingIntent pendingIntent = getAlarmPendingIntent(c);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        c.endTime,
                        pendingIntent);
            } else {
                alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        c.endTime,
                        pendingIntent);
            }
        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudo programar el aviso",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void cancelManualAlarm(Cooldown c) {
        if (!c.notifyWhenDone || alarmManager == null) {
            return;
        }

        try {
            PendingIntent pendingIntent = getAlarmPendingIntent(c);
            alarmManager.cancel(pendingIntent);
            pendingIntent.cancel();
        } catch (Exception ignored) {
        }
    }

    private int dp(float value) {
        return (int) (value
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));

        if (stroke != Color.TRANSPARENT) {
            d.setStroke(dp(1), stroke);
        }

        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);

        if (bold) {
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams params(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(BG));

        LinearLayout root = vertical();
        root.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView coin = text("K", 34, GOLD, true);
        coin.setGravity(Gravity.CENTER);
        coin.setBackground(shape(0xFF2C1E07, GOLD, 50));
        header.addView(coin, params(dp(62), dp(62)));

        LinearLayout brand = vertical();
        brand.setPadding(dp(14), 0, 0, 0);
        brand.addView(text("BotCooldown", 27, WHITE, true));

        TextView tagline = text(
                "Controla. Espera. Automatiza.", 12, MUTED, false);
        tagline.setPadding(0, dp(4), 0, 0);
        brand.addView(tagline);

        header.addView(brand, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(header);

        TextView status = text(
                "●  SISTEMA DE TEMPORIZADORES", 11, GREEN, true);
        status.setPadding(0, dp(30), 0, dp(12));
        root.addView(status);

        LinearLayout form = vertical();
        form.setPadding(dp(16), dp(16), dp(16), dp(18));
        form.setBackground(shape(CARD, 0xFF17432A, 18));
        root.addView(form, params(-1, -2));

        form.addView(text("NUEVO COOLDOWN", 14, GREEN, true));

        commandInput = makeInput("Comando, ej. k!drop", false);
        addInput(form, commandInput);

        minutesInput = makeInput("Minutos", true);
        addInput(form, minutesInput);

        secondsInput = makeInput("Segundos (0 a 59)", true);
        addInput(form, secondsInput);

        Button start = new Button(this);
        start.setText("＋   INICIAR COOLDOWN");
        start.setTextColor(Color.rgb(4, 9, 12));
        start.setTextSize(14);
        start.setTypeface(null, Typeface.BOLD);
        start.setAllCaps(false);
        start.setBackground(shape(GREEN, GREEN, 14));

        LinearLayout.LayoutParams bp = params(-1, dp(54));
        bp.topMargin = dp(14);
        form.addView(start, bp);
        start.setOnClickListener(v -> startCooldown());

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.setPadding(0, dp(28), 0, dp(14));
        section.addView(text("TUS TEMPORIZADORES", 15, WHITE, true));
        root.addView(section);

        timerList = vertical();
        root.addView(timerList, params(-1, -2));

        TextView footer = text(
                "Los cooldowns activos se recuperan desde Render al abrir la aplicación.",
                12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, 0);
        root.addView(footer);
    }

    private EditText makeInput(String hint, boolean numeric) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextColor(WHITE);
        e.setHintTextColor(MUTED);
        e.setTextSize(15);
        e.setHint(hint);
        e.setInputType(numeric ? 2 : 1);
        e.setPadding(dp(13), dp(10), dp(13), dp(10));
        e.setBackground(shape(Color.parseColor(BG), 0xFF28503A, 12));
        return e;
    }

    private void addInput(LinearLayout form, EditText input) {
        LinearLayout.LayoutParams p = params(-1, dp(52));
        p.topMargin = dp(10);
        form.addView(input, p);
    }

    private void startCooldown() {
        String command = commandInput.getText().toString().trim();
        String minText = minutesInput.getText().toString().trim();
        String secText = secondsInput.getText().toString().trim();

        if (command.isEmpty()
                || (minText.isEmpty() && secText.isEmpty())) {
            Toast.makeText(this,
                    "Escribe el comando y la duración",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            long minutes = minText.isEmpty()
                    ? 0 : Long.parseLong(minText);
            long seconds = secText.isEmpty()
                    ? 0 : Long.parseLong(secText);

            if (minutes < 0 || seconds < 0 || seconds > 59
                    || minutes > 1000000
                    || (minutes == 0 && seconds == 0)) {
                throw new NumberFormatException();
            }

            long duration = minutes * 60000L + seconds * 1000L;
            Cooldown c = new Cooldown(command, duration);

            createCooldownCard(c);
            cooldowns.add(c);
            scheduleManualAlarm(c);
            updateCooldown(c);

            commandInput.setText("");
            minutesInput.setText("");
            secondsInput.setText("");

        } catch (NumberFormatException e) {
            Toast.makeText(this,
                    "Minutos válidos y segundos entre 0 y 59",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void createCooldownCard(Cooldown c) {
        LinearLayout card = vertical();
        card.setPadding(dp(16), dp(15), dp(16), dp(14));
        card.setBackground(shape(CARD, 0xFF205034, 16));

        LinearLayout.LayoutParams cp = params(-1, -2);
        cp.bottomMargin = dp(12);
        timerList.addView(card, cp);
        c.card = card;

        card.addView(text(c.command, 16, WHITE, true));

        c.view = text("", 22, GREEN, true);
        c.view.setPadding(0, dp(10), 0, dp(5));
        card.addView(c.view);

        TextView hint = text(
                c.notifyWhenDone
                        ? "NOTIFICACIÓN AL FINALIZAR"
                        : "SINCRONIZADO DESDE RENDER",
                10, MUTED, true);
        hint.setPadding(0, 0, 0, dp(12));
        card.addView(hint);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(actions);

        Button reset = new Button(this);
        reset.setText("↻ Reiniciar");
        reset.setAllCaps(false);
        reset.setTextColor(GREEN);
        reset.setTextSize(12);
        reset.setBackground(shape(0xFF10291A, 0xFF24643A, 12));
        actions.addView(reset, new LinearLayout.LayoutParams(
                0, dp(44), 1));

        Button delete = new Button(this);
        delete.setText("Eliminar");
        delete.setAllCaps(false);
        delete.setTextColor(0xFFFF8585);
        delete.setTextSize(12);
        delete.setBackground(shape(0xFF291416, 0xFF71343A, 12));

        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                0, dp(44), 1);
        dp2.leftMargin = dp(10);
        actions.addView(delete, dp2);

        reset.setOnClickListener(v -> {
            cancelManualAlarm(c);
            c.endTime = System.currentTimeMillis() + c.durationMillis;
            c.notified = false;
            scheduleManualAlarm(c);
            updateCooldown(c);
        });

        delete.setOnClickListener(v -> {
            cancelManualAlarm(c);
            cooldowns.remove(c);

            if (c.eventId != null) {
                syncedIds.remove(c.eventId);
            }

            timerList.removeView(c.card);
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();

            for (Cooldown c : cooldowns) {
                if (now >= c.endTime) {
                    if (!c.notified) {
                        c.notified = true;
                        c.view.setText("✓  DISPONIBLE");
                        c.view.setTextColor(GREEN);
                    }
                } else {
                    updateCooldown(c);
                }
            }

            handler.postDelayed(this, 1000);
        }
    };

    private void updateCooldown(Cooldown c) {
        long remaining = Math.max(
                0, (c.endTime - System.currentTimeMillis()) / 1000);

        long hours = remaining / 3600;
        long minutes = (remaining % 3600) / 60;
        long seconds = remaining % 60;

        String time;

        if (hours > 0) {
            time = String.format(Locale.getDefault(),
                    "%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            time = String.format(Locale.getDefault(),
                    "%02d:%02d", minutes, seconds);
        }

        c.view.setText(time);
        c.view.setTextColor(GREEN);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cooldowns listos",
                    NotificationManager.IMPORTANCE_DEFAULT);

            channel.setDescription(
                    "Avisos cuando un comando esté disponible");

            notificationManager.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    100);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);

        try {
            unregisterReceiver(syncReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        super.onDestroy();
    }
}
