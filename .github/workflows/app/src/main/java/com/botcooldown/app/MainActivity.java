
package com.botcooldown.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Cooldown> cooldowns = new ArrayList<>();
    private LinearLayout timerList;
    private EditText commandInput;
    private EditText minutesInput;
    private NotificationManager notificationManager;
    private static final String CHANNEL_ID = "botcooldown_ready";

    private static class Cooldown {
        String command;
        long endTime;
        boolean notified = false;
        TextView view;

        Cooldown(String command, long endTime) {
            this.command = command;
            this.endTime = endTime;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();
        requestNotificationPermission();
        buildScreen();

        handler.post(tick);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 36, 40, 32);
        root.setBackgroundColor(0xFFF5F6FA);

        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("BotCooldown");
        title.setTextSize(30);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF273469);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Controla los tiempos de tus comandos");
        subtitle.setTextSize(15);
        subtitle.setPadding(0, 8, 0, 24);
        root.addView(subtitle);

        commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setHint("Comando, ej. k!daily");
        root.addView(commandInput, matchWrap());

        minutesInput = new EditText(this);
        minutesInput.setSingleLine(true);
        minutesInput.setInputType(2);
        minutesInput.setHint("Cooldown en minutos");
        root.addView(minutesInput, matchWrap());

        Button startButton = new Button(this);
        startButton.setText("Iniciar cooldown");
        root.addView(startButton, matchWrap());

        TextView heading = new TextView(this);
        heading.setText("Tus temporizadores");
        heading.setTextSize(20);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, 28, 0, 12);
        root.addView(heading);

        timerList = new LinearLayout(this);
        timerList.setOrientation(LinearLayout.VERTICAL);
        root.addView(timerList, matchWrap());

        startButton.setOnClickListener(v -> startCooldown());
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private void startCooldown() {
        String command = commandInput.getText().toString().trim();
        String minutesText = minutesInput.getText().toString().trim();

        if (command.isEmpty() || minutesText.isEmpty()) {
            Toast.makeText(this,
                    "Escribe el comando y los minutos",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            long minutes = Long.parseLong(minutesText);

            if (minutes < 1 || minutes > 1000000) {
                throw new NumberFormatException();
            }

            Cooldown cooldown = new Cooldown(
                    command,
                    System.currentTimeMillis() + minutes * 60_000L
            );

            TextView item = new TextView(this);
            item.setTextSize(16);
            item.setTextColor(0xFF202333);
            item.setPadding(16, 18, 16, 18);
            item.setBackgroundColor(0xFFFFFFFF);

            cooldown.view = item;
            cooldowns.add(cooldown);
            timerList.addView(item, matchWrap());

            commandInput.setText("");
            minutesInput.setText("");

            updateCooldown(cooldown);

        } catch (NumberFormatException e) {
            Toast.makeText(this,
                    "Introduce minutos válidos (mínimo 1)",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();

            for (Cooldown cooldown : cooldowns) {
                if (now >= cooldown.endTime) {
                    cooldown.view.setText(
                            cooldown.command + "\n¡Disponible!"
                    );

                    if (!cooldown.notified) {
                        cooldown.notified = true;
                        sendReadyNotification(cooldown.command);
                    }
                } else {
                    updateCooldown(cooldown);
                }
            }

            handler.postDelayed(this, 1000);
        }
    };

    private void updateCooldown(Cooldown cooldown) {
        long remaining = Math.max(
                0, (cooldown.endTime - System.currentTimeMillis()) / 1000
        );

        long hours = remaining / 3600;
        long minutes = (remaining % 3600) / 60;
        long seconds = remaining % 60;

        String time = hours > 0
                ? String.format(Locale.getDefault(),
                    "%02d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.getDefault(),
                    "%02d:%02d", minutes, seconds);

        cooldown.view.setText(
                cooldown.command + "\nTiempo restante: " + time
        );
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cooldowns listos",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(
                    "Avisos cuando un comando esté disponible"
            );
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    100
            );
        }
    }

    private void sendReadyNotification(String command) {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown")
                    .setContentText(command + " ya está disponible")
                    .setAutoCancel(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle("BotCooldown")
                    .setContentText(command + " ya está disponible")
                    .setAutoCancel(true)
                    .build();
        }

        notificationManager.notify(
                (int) (System.currentTimeMillis() & 0x7fffffff),
                notification
        );
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
