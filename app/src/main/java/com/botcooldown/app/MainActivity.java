
package com.botcooldown.app;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
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
    private static final int BG = Color.rgb(4, 9, 12);
    private static final int CARD = Color.rgb(10, 19, 22);
    private static final int GREEN = Color.rgb(57, 255, 116);
    private static final int GOLD = Color.rgb(255, 194, 48);
    private static final int MUTED = Color.rgb(151, 171, 169);
    private static final int WHITE = Color.rgb(239, 255, 245);

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

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        notificationManager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        createNotificationChannel();
        requestNotificationPermission();
        buildScreen();

        handler.post(tick);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int strokeColor, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (strokeColor != Color.TRANSPARENT) {
            d.setStroke(dp(1), strokeColor);
        }
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private void buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = vertical();
        root.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(root);
        setContentView(scroll);

        // Encabezado y moneda dorada
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView coin = text("K", 34, GOLD, true);
        coin.setGravity(Gravity.CENTER);
        coin.setBackground(shape(
                Color.rgb(44, 30, 7), GOLD, 50));
        coin.setElevation(dp(5));
        header.addView(coin, params(dp(62), dp(62)));

        LinearLayout brand = vertical();
        brand.setPadding(dp(14), 0, 0, 0);

        TextView title = text("BotCooldown", 27, WHITE, true);
        brand.addView(title);

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

        // Formulario
        LinearLayout form = vertical();
        form.setPadding(dp(16), dp(16), dp(16), dp(18));
        form.setBackground(shape(CARD, 0xFF17432A, 18));
        root.addView(form, params(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        form.addView(text("NUEVO COOLDOWN", 14, GREEN, true));

        commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setTextColor(WHITE);
        commandInput.setHintTextColor(MUTED);
        commandInput.setTextSize(15);
        commandInput.setHint("Nombre o comando, ej. k!daily");
        commandInput.setPadding(dp(13), dp(10), dp(13), dp(10));
        commandInput.setBackground(shape(BG, 0xFF28503A, 12));

        LinearLayout.LayoutParams inputParams = params(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        inputParams.topMargin = dp(14);
        form.addView(commandInput, inputParams);

        minutesInput = new EditText(this);
        minutesInput.setSingleLine(true);
        minutesInput.setInputType(2);
        minutesInput.setTextColor(WHITE);
        minutesInput.setHintTextColor(MUTED);
        minutesInput.setTextSize(15);
        minutesInput.setHint("Duración en minutos");
        minutesInput.setPadding(dp(13), dp(10), dp(13), dp(10));
        minutesInput.setBackground(shape(BG, 0xFF28503A, 12));

        LinearLayout.LayoutParams minutesParams = params(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        minutesParams.topMargin = dp(10);
        form.addView(minutesInput, minutesParams);

        Button startButton = new Button(this);
        startButton.setText("＋   INICIAR COOLDOWN");
        startButton.setTextColor(BG);
        startButton.setTextSize(14);
        startButton.setTypeface(null, Typeface.BOLD);
        startButton.setAllCaps(false);
        startButton.setBackground(shape(GREEN, GREEN, 14));

        LinearLayout.LayoutParams buttonParams = params(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        buttonParams.topMargin = dp(14);
        form.addView(startButton, buttonParams);

        startButton.setOnClickListener(v -> startCooldown());

        // Lista
        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.setPadding(0, dp(28), 0, dp(14));

        section.addView(text("TUS TEMPORIZADORES", 15, WHITE, true));

        TextView count = text("EN VIVO", 10, GREEN, true);
        count.setGravity(Gravity.CENTER);
        count.setPadding(dp(9), dp(5), dp(9), dp(5));
        count.setBackground(shape(0xFF10291A, 0xFF24643A, 20));

        LinearLayout.LayoutParams countParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        countParams.leftMargin = dp(10);
        section.addView(count, countParams);
        root.addView(section);

        timerList = vertical();
        root.addView(timerList, params(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView footer = text(
                "Mantén tus tiempos bajo control.", 12, MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(24), 0, 0);
        root.addView(footer);
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

            LinearLayout card = vertical();
            card.setPadding(dp(16), dp(15), dp(16), dp(15));
            card.setBackground(shape(CARD, 0xFF205034, 16));

            LinearLayout.LayoutParams cardParams = params(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            cardParams.bottomMargin = dp(12);

            TextView item = text("", 15, WHITE, true);
            item.setLineSpacing(dp(5), 1f);
            card.addView(item);

            TextView hint = text(
                    "NOTIFICACIÓN AL FINALIZAR ACTIVADA",
                    10, GREEN, true);
            hint.setPadding(0, dp(8), 0, 0);
            card.addView(hint);

            cooldown.view = item;
            cooldowns.add(cooldown);
            timerList.addView(card, cardParams);

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
                            "✓  " + cooldown.command + "\nDISPONIBLE");
                    cooldown.view.setTextColor(GREEN);

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
                "◉  " + cooldown.command + "\n" + time + "  ·  TIEMPO RESTANTE");
        cooldown.view.setTextColor(WHITE);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cooldowns listos",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription(
                    "Avisos cuando un comando esté disponible");
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
