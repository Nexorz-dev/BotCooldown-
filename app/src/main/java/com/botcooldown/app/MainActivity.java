package com.botcooldown.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Cooldown> cooldowns = new ArrayList<>();
    private final Set<String> syncedIds = new HashSet<>();

    private LinearLayout timerList;
    private EditText commandInput;
    private EditText minutesInput;
    private EditText secondsInput;

    private TextView discordStatusView;
    private Button linkButton;
    private Button unlinkButton;

    private LinearLayout bottomBar;
    private float touchStartY;
    private float touchStartX;
    private boolean bottomBarVisible = false;

    private NotificationManager notificationManager;
    private AlarmManager alarmManager;
    private SharedPreferences preferences;
    private SharedPreferences linkPreferences;

    private static int nextAlarmId = 1000;
    private boolean requestingExactAlarmPermission = false;
    private boolean pollingForLink = false;
    private boolean backgroundSetupDialogShowing = false;

    private String currentLinkCode = "";
    private long linkExpiresAt = 0L;
    private String syncedAccount = "";

    private final Handler linkHandler = new Handler(Looper.getMainLooper());

    private static final String CHANNEL_ID = "botcooldown_ready";

    private static final String API_URL =
            "https://cooldown-detector.onrender.com";

    private static final String EVENTS_URL = API_URL + "/events";
    private static final String LINK_REQUEST_URL = API_URL + "/link/request";
    private static final String LINK_STATUS_URL = API_URL + "/link/status";
    private static final String LINK_UNLINK_URL = API_URL + "/link/unlink";

    private static final String PREFS_NAME = "botcooldown_preferences";
    private static final String PREF_MANUAL_TIMERS = "manual_timers";
    private static final String PREF_SYNCED_IDS = "synced_event_ids_";
    private static final String PREF_ACCENT_COLOR = "accent_color";

    private static final String PREF_BACKGROUND_SETUP_CONFIRMED =
            "background_setup_confirmed";

    private static final String LINK_PREFS = "botcooldown_link";
    private static final String PREF_INSTALLATION_ID = "installation_id";
    private static final String PREF_INSTALLATION_SECRET = "installation_secret";
    private static final String PREF_API_TOKEN = "api_token";

    private static final int MAX_SAVED_SYNCED_IDS = 500;

    private static final String BG = "#04090C";
    private static final int DEFAULT_GREEN = 0xFF39FF74;

    private static final int CARD = 0xFF0A1316;
    private int GREEN = DEFAULT_GREEN;

    private static final int GOLD = 0xFFFFC230;
    private static final int MUTED = 0xFF97ABA9;
    private static final int WHITE = 0xFFEFFFF5;

    private int selectedAccentColor = DEFAULT_GREEN;

    private AlertDialog colorDialog;
    private TextView colorPreview;
    private SeekBar hueSeekBar;
    private SeekBar saturationSeekBar;
    private SeekBar brightnessSeekBar;

    private float selectedHue = 135f;
    private float selectedSaturation = 1f;
    private float selectedBrightness = 1f;

    private static class Cooldown {
        String command;
        String eventId;
        String serverEventId;
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
            String databaseId = intent.getStringExtra(
                    CooldownSyncService.EXTRA_EVENT_ID);

            addSyncedCooldown(
                    command, endTime, duration, databaseId);
        }
    };

    private final Runnable linkPoll = new Runnable() {
        @Override
        public void run() {
            if (!pollingForLink) {
                return;
            }

            if (linkExpiresAt > 0
                    && System.currentTimeMillis() >= linkExpiresAt) {
                pollingForLink = false;
                currentLinkCode = "";
                updateDiscordStatus(false);

                Toast.makeText(
                        MainActivity.this,
                        "El código de vinculación ha expirado",
                        Toast.LENGTH_LONG).show();
                return;
            }

            checkLinkStatus(true);
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

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        linkPreferences = getSharedPreferences(LINK_PREFS, MODE_PRIVATE);

        loadAccentColor();

        getOrCreateInstallationCredentials();

        restoreSyncedIds();

        createNotificationChannel();
        requestNotificationPermission();
        buildScreen();

        restoreManualCooldowns();

        IntentFilter filter = new IntentFilter(
                CooldownSyncService.ACTION_COOLDOWN_SYNCED);

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                    syncReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(syncReceiver, filter);
        }

        handler.post(tick);

        checkLinkStatus(false);

        handler.post(this::showBackgroundSetupDialogIfNeeded);
    }

    // =========================================================
    // PERSONALIZACIÓN DE COLORES
    // =========================================================

    private void loadAccentColor() {
        if (preferences == null) {
            return;
        }

        selectedAccentColor = preferences.getInt(
                PREF_ACCENT_COLOR, DEFAULT_GREEN);

        GREEN = selectedAccentColor;

        float[] hsv = new float[3];
        Color.colorToHSV(selectedAccentColor, hsv);

        selectedHue = hsv[0];
        selectedSaturation = hsv[1];
        selectedBrightness = hsv[2];
    }

    private void saveAccentColor(int color) {
        selectedAccentColor = color;
        GREEN = color;

        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        selectedHue = hsv[0];
        selectedSaturation = hsv[1];
        selectedBrightness = hsv[2];

        preferences.edit()
                .putInt(PREF_ACCENT_COLOR, color)
                .apply();
    }

    /*
     * Reconstruye la interfaz sin reiniciar MainActivity.
     * Los objetos Cooldown permanecen en memoria, por lo que
     * conservan sus tiempos, identificadores y estados.
     */
    private void refreshScreenWithNewAccent() {
        // La barra inferior vuelve a su estado inicial.
        bottomBarVisible = false;

        // Reconstruye la pantalla con el nuevo color.
        buildScreen();

        // Vuelve a dibujar todos los temporizadores existentes.
        for (Cooldown c : cooldowns) {
            createCooldownCard(c);

            if (c.notified || c.endTime <= System.currentTimeMillis()) {
                c.notified = true;
                c.view.setText("✓  DISPONIBLE");
                c.view.setTextColor(GREEN);
            } else {
                updateCooldown(c);
            }
        }

        // Mantiene actualizado el estado de Discord.
        updateDiscordStatus(!getApiToken().isEmpty());
    }

    private int getAccentShade(float saturationFactor,
                               float brightnessFactor) {

        float saturation = Math.max(
                0f, Math.min(1f, selectedSaturation * saturationFactor));

        float brightness = Math.max(
                0f, Math.min(1f, selectedBrightness * brightnessFactor));

        return Color.HSVToColor(new float[]{
                selectedHue,
                saturation,
                brightness
        });
    }

    private int themedColor(int originalColor) {

        if (originalColor == DEFAULT_GREEN) {
            return GREEN;
        }

        if (originalColor == CARD) {
            return getAccentShade(0.35f, 0.075f);
        }

        if (originalColor == 0xFF17432A) {
            return getAccentShade(0.65f, 0.30f);
        }

        if (originalColor == 0xFF24643A) {
            return getAccentShade(0.85f, 0.55f);
        }

        if (originalColor == 0xFF102219) {
            return getAccentShade(0.55f, 0.18f);
        }

        if (originalColor == 0xFF28503A) {
            return getAccentShade(0.65f, 0.36f);
        }

        if (originalColor == 0xFF205034) {
            return getAccentShade(0.75f, 0.32f);
        }

        if (originalColor == 0xFF08130D) {
            return getAccentShade(0.45f, 0.09f);
        }

        if (originalColor == 0xFF10291A) {
            return getAccentShade(0.65f, 0.20f);
        }

        return originalColor;
    }

    private void showColorPalette() {

        LinearLayout layout = vertical();
        layout.setPadding(dp(20), dp(18), dp(20), dp(20));
        layout.setBackground(shape(
                Color.parseColor(BG), 0xFF17432A, 18));

        TextView description = text(
                "Personaliza el color principal de BotCooldown.",
                13, MUTED, false);

        layout.addView(description);

        TextView previewLabel = text(
                "VISTA PREVIA", 11, GREEN, true);

        LinearLayout.LayoutParams previewLabelParams = params(-1, -2);
        previewLabelParams.topMargin = dp(20);
        layout.addView(previewLabel, previewLabelParams);

        colorPreview = text(
                "BotCooldown  ✦  12:45",
                23, GREEN, true);

        colorPreview.setGravity(Gravity.CENTER);
        colorPreview.setPadding(dp(12), dp(22), dp(12), dp(22));
        colorPreview.setBackground(shape(
                getAccentShade(0.35f, 0.12f), GREEN, 14));

        LinearLayout.LayoutParams previewParams = params(-1, -2);
        previewParams.topMargin = dp(10);
        layout.addView(colorPreview, previewParams);

        TextView presetTitle = text(
                "COLORES PREDEFINIDOS", 11, MUTED, true);

        LinearLayout.LayoutParams presetTitleParams = params(-1, -2);
        presetTitleParams.topMargin = dp(22);
        layout.addView(presetTitle, presetTitleParams);

        int[] presetColors = {
                0xFF39FF74,
                0xFF00E5FF,
                0xFF2979FF,
                0xFF9D4EDD,
                0xFFE040FB,
                0xFFFF4081,
                0xFFFF5252,
                0xFFFF9100,
                0xFFFFD740,
                0xFFFFFFFF,
                0xFF00E676,
                0xFF18FFFF
        };

        LinearLayout presetGrid = vertical();

        for (int row = 0; row < 3; row++) {
            LinearLayout colorRow = new LinearLayout(this);
            colorRow.setGravity(Gravity.CENTER);
            colorRow.setOrientation(LinearLayout.HORIZONTAL);

            for (int column = 0; column < 4; column++) {
                int index = row * 4 + column;
                int color = presetColors[index];

                View swatch = new View(this);
                swatch.setBackground(shape(color, WHITE, 50));

                LinearLayout.LayoutParams swatchParams =
                        new LinearLayout.LayoutParams(
                                0, dp(42), 1);

                swatchParams.setMargins(
                        dp(5), dp(7), dp(5), dp(7));

                colorRow.addView(swatch, swatchParams);

                swatch.setOnClickListener(v -> {
                    updatePalettePreview(color);

                    float[] hsv = new float[3];
                    Color.colorToHSV(color, hsv);

                    selectedHue = hsv[0];
                    selectedSaturation = hsv[1];
                    selectedBrightness = hsv[2];

                    if (hueSeekBar != null) {
                        hueSeekBar.setProgress(
                                Math.round(selectedHue));
                    }

                    if (saturationSeekBar != null) {
                        saturationSeekBar.setProgress(
                                Math.round(selectedSaturation * 100));
                    }

                    if (brightnessSeekBar != null) {
                        brightnessSeekBar.setProgress(
                                Math.round(selectedBrightness * 100));
                    }
                });
            }

            presetGrid.addView(colorRow);
        }

        layout.addView(presetGrid);

        TextView customTitle = text(
                "COLOR PERSONALIZADO", 11, MUTED, true);

        LinearLayout.LayoutParams customTitleParams = params(-1, -2);
        customTitleParams.topMargin = dp(18);
        layout.addView(customTitle, customTitleParams);

        hueSeekBar = createColorSeekBar(360);
        saturationSeekBar = createColorSeekBar(100);
        brightnessSeekBar = createColorSeekBar(100);

        addColorControl(
                layout, "Tono", hueSeekBar,
                Math.round(selectedHue));

        addColorControl(
                layout, "Saturación", saturationSeekBar,
                Math.round(selectedSaturation * 100));

        addColorControl(
                layout, "Brillo", brightnessSeekBar,
                Math.round(selectedBrightness * 100));

        hueSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar, int progress,
                            boolean fromUser) {

                        selectedHue = progress;
                        updateCustomColorPreview();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

        saturationSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar, int progress,
                            boolean fromUser) {

                        selectedSaturation = progress / 100f;
                        updateCustomColorPreview();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

        brightnessSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar, int progress,
                            boolean fromUser) {

                        selectedBrightness = progress / 100f;
                        updateCustomColorPreview();
                    }

                    @Override
                    public void onStartTrackingTouch(SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                    }
                });

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.addView(layout);

        colorDialog = new AlertDialog.Builder(this)
                .setTitle("✦  Personalizar colores")
                .setView(scrollView)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Aplicar color", null)
                .create();

        colorDialog.setOnShowListener(dialog -> {

            int titleId = getResources().getIdentifier(
                    "alertTitle", "id", "android");

            TextView title = colorDialog.findViewById(titleId);

            if (title != null) {
                title.setTextColor(GREEN);
            }

            Button applyButton = colorDialog.getButton(
                    AlertDialog.BUTTON_POSITIVE);

            Button cancelButton = colorDialog.getButton(
                    AlertDialog.BUTTON_NEGATIVE);

            if (applyButton != null) {
                applyButton.setTextColor(GREEN);

                applyButton.setOnClickListener(v -> {
                    int newColor = Color.HSVToColor(new float[]{
                            selectedHue,
                            selectedSaturation,
                            selectedBrightness
                    });

                    saveAccentColor(newColor);

                    colorDialog.dismiss();

                    // Actualiza la interfaz sin reiniciar la actividad.
                    refreshScreenWithNewAccent();
                });
            }

            if (cancelButton != null) {
                cancelButton.setTextColor(GOLD);
            }

            if (colorDialog.getWindow() != null) {
                colorDialog.getWindow().setBackgroundDrawable(
                        shape(CARD, GREEN, 18));
            }
        });

        colorDialog.show();
    }

    private SeekBar createColorSeekBar(int max) {
        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max);
        seekBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setThumbTintList(
                android.content.res.ColorStateList.valueOf(GREEN));
        return seekBar;
    }

    private void addColorControl(
            LinearLayout parent,
            String label,
            SeekBar seekBar,
            int progress) {

        TextView title = text(label, 12, WHITE, true);

        LinearLayout.LayoutParams titleParams = params(-1, -2);
        titleParams.topMargin = dp(12);
        parent.addView(title, titleParams);

        seekBar.setProgress(progress);

        LinearLayout.LayoutParams seekParams = params(-1, dp(38));
        seekParams.topMargin = dp(3);
        parent.addView(seekBar, seekParams);
    }

    private void updatePalettePreview(int color) {
        if (colorPreview == null) {
            return;
        }

        colorPreview.setTextColor(color);
        colorPreview.setBackground(shape(
                darkenColor(color, 0.13f), color, 14));
    }

    private void updateCustomColorPreview() {
        int color = Color.HSVToColor(new float[]{
                selectedHue,
                selectedSaturation,
                selectedBrightness
        });

        updatePalettePreview(color);
    }

    private int darkenColor(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);

        hsv[2] = Math.max(0f, Math.min(1f, hsv[2] * factor));

        return Color.HSVToColor(hsv);
    }

    // =========================================================
    // BARRA INFERIOR
    // =========================================================

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            touchStartY = event.getY();
            touchStartX = event.getX();

        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            float deltaY = event.getY() - touchStartY;
            float deltaX = event.getX() - touchStartX;

            int threshold = dp(65);

            if (Math.abs(deltaY) > threshold
                    && Math.abs(deltaY) > Math.abs(deltaX) * 1.3f) {

                if (deltaY > 0) {
                    showBottomBar();
                } else {
                    hideBottomBar();
                }
            }
        }

        return super.dispatchTouchEvent(event);
    }

    private void showBottomBar() {
        if (bottomBar == null || bottomBarVisible) {
            return;
        }

        bottomBarVisible = true;

        bottomBar.animate().cancel();
        bottomBar.setVisibility(View.VISIBLE);
        bottomBar.setAlpha(0f);
        bottomBar.setTranslationY(dp(55));

        bottomBar.animate()
                .translationY(0)
                .alpha(1f)
                .setDuration(220)
                .start();
    }

    private void hideBottomBar() {
        if (bottomBar == null || !bottomBarVisible) {
            return;
        }

        bottomBarVisible = false;

        bottomBar.animate().cancel();

        bottomBar.animate()
                .translationY(dp(55))
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    if (!bottomBarVisible && bottomBar != null) {
                        bottomBar.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    private Button createBottomBarButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextColor(GREEN);
        button.setTextSize(13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(shape(0xFF102219, 0xFF24643A, 12));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (alarmManager != null && canScheduleExactAlarms()) {
            requestingExactAlarmPermission = false;

            for (Cooldown c : cooldowns) {
                if (c.notifyWhenDone
                        && c.endTime > System.currentTimeMillis()) {
                    scheduleManualAlarm(c);
                }
            }
        }

        checkLinkStatus(false);

        showBackgroundSetupDialogIfNeeded();
    }

    private void showBackgroundSetupDialogIfNeeded() {
        if (preferences == null
                || preferences.getBoolean(
                        PREF_BACKGROUND_SETUP_CONFIRMED, false)
                || backgroundSetupDialogShowing
                || isFinishing()
                || isDestroyed()) {
            return;
        }

        backgroundSetupDialogShowing = true;

        LinearLayout layout = vertical();
        layout.setPadding(dp(22), dp(18), dp(22), dp(18));
        layout.setBackground(shape(
                Color.parseColor(BG), 0xFF17432A, 16));

        TextView message = text(
                "Para que BotCooldown funcione correctamente, "
                        + "necesita poder mantenerse activa en segundo plano.\n\n"
                        + "Esto permite que la aplicación siga controlando "
                        + "los tiempos de espera y pueda mostrar las "
                        + "notificaciones cuando un cooldown termine, "
                        + "incluso si no estás utilizando la aplicación "
                        + "o tienes la pantalla apagada.\n\n"
                        + "Si restringes esta actividad, algunas "
                        + "notificaciones podrían retrasarse o no llegar "
                        + "a tiempo.\n\n"
                        + "Para disfrutar de todas las funciones de "
                        + "BotCooldown, permite la actividad en segundo "
                        + "plano desde los ajustes de batería de tu dispositivo.",
                14, WHITE, false);

        layout.addView(message);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Actividad en segundo plano")
                .setView(layout)
                .setPositiveButton("Configurar ahora", null)
                .setNegativeButton("Ya lo activé", null)
                .create();

        dialog.setCancelable(false);

        dialog.setOnDismissListener(d ->
                backgroundSetupDialogShowing = false);

        dialog.setOnShowListener(d -> {
            int titleId = getResources().getIdentifier(
                    "alertTitle", "id", "android");

            TextView title = dialog.findViewById(titleId);

            if (title != null) {
                title.setTextColor(GREEN);
            }

            Button configure = dialog.getButton(
                    AlertDialog.BUTTON_POSITIVE);

            Button confirmed = dialog.getButton(
                    AlertDialog.BUTTON_NEGATIVE);

            if (configure != null) {
                configure.setTextColor(GREEN);

                configure.setOnClickListener(v -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS);

                        intent.setData(Uri.parse(
                                "package:" + getPackageName()));

                        dialog.dismiss();
                        startActivity(intent);

                    } catch (Exception e) {
                        dialog.dismiss();

                        Toast.makeText(
                                MainActivity.this,
                                "No se pudieron abrir los ajustes de la aplicación",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

            if (confirmed != null) {
                confirmed.setTextColor(GOLD);

                confirmed.setOnClickListener(v -> {
                    preferences.edit()
                            .putBoolean(
                                    PREF_BACKGROUND_SETUP_CONFIRMED,
                                    true)
                            .apply();

                    dialog.dismiss();

                    Toast.makeText(
                            MainActivity.this,
                            "Configuración confirmada",
                            Toast.LENGTH_SHORT).show();
                });
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    shape(CARD, GREEN, 18));
        }
    }

    // =========================================================
    // VINCULACIÓN DISCORD
    // =========================================================

    private void getOrCreateInstallationCredentials() {
        String installationId = linkPreferences.getString(
                PREF_INSTALLATION_ID, null);

        String installationSecret = linkPreferences.getString(
                PREF_INSTALLATION_SECRET, null);

        if (installationId == null || installationSecret == null) {
            installationId = UUID.randomUUID().toString();

            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);

            StringBuilder hex = new StringBuilder();

            for (byte b : bytes) {
                hex.append(String.format(Locale.US, "%02x", b & 0xff));
            }

            installationSecret = hex.toString();

            linkPreferences.edit()
                    .putString(PREF_INSTALLATION_ID, installationId)
                    .putString(PREF_INSTALLATION_SECRET, installationSecret)
                    .apply();
        }
    }

    private String getApiToken() {
        return linkPreferences.getString(PREF_API_TOKEN, "");
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
                result.append(String.format(Locale.US, "%02x", b & 0xff));
            }

            return result.toString();

        } catch (Exception e) {
            return "";
        }
    }

    private String getSyncedIdsPreferenceKey() {
        return PREF_SYNCED_IDS + tokenHash(getApiToken());
    }

    private void reloadSyncedIdsForCurrentAccount() {
        String account = tokenHash(getApiToken());

        if (!account.equals(syncedAccount)) {
            syncedAccount = account;
            restoreSyncedIds();
        }
    }

    private JSONObject postJson(String endpoint, JSONObject body, String token)
            throws Exception {

        HttpURLConnection connection = null;

        try {
            URL url = new URL(endpoint);
            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);

            connection.setRequestProperty(
                    "Content-Type", "application/json; charset=UTF-8");
            connection.setRequestProperty("Accept", "application/json");

            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty(
                        "Authorization", "Bearer " + token);
            }

            byte[] data = body.toString().getBytes("UTF-8");

            try (OutputStream output = connection.getOutputStream()) {
                output.write(data);
                output.flush();
            }

            int responseCode = connection.getResponseCode();

            InputStream input = responseCode >= 200 && responseCode < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            StringBuilder result = new StringBuilder();

            if (input != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, "UTF-8"))) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                }
            }

            JSONObject response = result.length() > 0
                    ? new JSONObject(result.toString())
                    : new JSONObject();

            response.put("_httpCode", responseCode);

            return response;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean deleteSyncedEvent(String databaseId, String token)
            throws Exception {

        if (databaseId == null || databaseId.trim().isEmpty()
                || token == null || token.trim().isEmpty()) {
            return false;
        }

        HttpURLConnection connection = null;

        try {
            connection = (HttpURLConnection)
                    new URL(EVENTS_URL + "/" + databaseId).openConnection();

            connection.setRequestMethod("DELETE");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty(
                    "Authorization", "Bearer " + token);

            int responseCode = connection.getResponseCode();

            return responseCode >= 200 && responseCode < 300;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private JSONObject createInstallationJson() throws Exception {
        JSONObject body = new JSONObject();

        body.put(
                "installationId",
                linkPreferences.getString(PREF_INSTALLATION_ID, ""));

        body.put(
                "installationSecret",
                linkPreferences.getString(PREF_INSTALLATION_SECRET, ""));

        return body;
    }

    private void requestDiscordLink() {
        if (linkButton != null) {
            linkButton.setEnabled(false);
        }

        new Thread(() -> {
            try {
                JSONObject body = createInstallationJson();

                JSONObject response = postJson(
                        LINK_REQUEST_URL, body, null);

                int code = response.optInt("_httpCode", 0);

                if (code < 200 || code >= 300) {
                    throw new Exception(
                            response.optString("error",
                                    "No se pudo generar el código"));
                }

                String linkCode = response.optString("code", "");

                if (linkCode.isEmpty()) {
                    throw new Exception("El servidor no entregó un código");
                }

                String expiresAt = response.optString("expiresAt", "");

                long expiration = 0L;

                if (!expiresAt.isEmpty()) {
                    try {
                        expiration = java.time.Instant.parse(
                                expiresAt).toEpochMilli();
                    } catch (Exception ignored) {
                    }
                }

                final long finalExpiration = expiration;

                handler.post(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }

                    if (linkButton != null) {
                        linkButton.setEnabled(true);
                    }

                    currentLinkCode = linkCode;
                    linkExpiresAt = finalExpiration;

                    showLinkCodeDialog(linkCode);

                    pollingForLink = true;
                    linkHandler.removeCallbacks(linkPoll);
                    linkHandler.postDelayed(linkPoll, 3000);
                });

            } catch (Exception e) {
                handler.post(() -> {
                    if (linkButton != null) {
                        linkButton.setEnabled(true);
                    }

                    Toast.makeText(
                            MainActivity.this,
                            "Error al solicitar vinculación: "
                                    + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void showLinkCodeDialog(String code) {
        LinearLayout layout = vertical();
        layout.setPadding(dp(22), dp(18), dp(22), dp(18));
        layout.setBackground(shape(
                Color.parseColor(BG), 0xFF17432A, 16));

        TextView instructions = text(
                "Envía estos mensajes al bot por Discord:\n\n"
                        + "1. Escribe b!register\n"
                        + "2. Después envía el código que aparece abajo.\n\n"
                        + "La vinculación se confirmará automáticamente.",
                14, WHITE, false);

        layout.addView(instructions);

        TextView codeView = text(code, 27, GREEN, true);
        codeView.setGravity(Gravity.CENTER);
        codeView.setPadding(dp(10), dp(18), dp(10), dp(18));
        codeView.setBackground(shape(CARD, GREEN, 12));

        LinearLayout.LayoutParams codeParams = params(-1, -2);
        codeParams.topMargin = dp(18);
        layout.addView(codeView, codeParams);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Vincular Discord")
                .setView(layout)
                .setNegativeButton("Cerrar", (d, w) -> d.dismiss())
                .setNeutralButton("Copiar código", null)
                .create();

        dialog.setOnShowListener(d -> {
            int titleId = getResources().getIdentifier(
                    "alertTitle", "id", "android");

            TextView title = dialog.findViewById(titleId);

            if (title != null) {
                title.setTextColor(GREEN);
            }

            Button copy = dialog.getButton(
                    AlertDialog.BUTTON_NEUTRAL);

            Button close = dialog.getButton(
                    AlertDialog.BUTTON_NEGATIVE);

            if (copy != null) {
                copy.setTextColor(GREEN);

                copy.setOnClickListener(v -> {
                    ClipboardManager clipboard =
                            (ClipboardManager) getSystemService(
                                    CLIPBOARD_SERVICE);

                    clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                    "Código BotCooldown", code));

                    Toast.makeText(
                            this,
                            "Código copiado",
                            Toast.LENGTH_SHORT).show();
                });
            }

            if (close != null) {
                close.setTextColor(GOLD);
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    shape(CARD, GREEN, 18));
        }
    }

    private void showUnlinkConfirmationDialog() {
        LinearLayout layout = vertical();
        layout.setPadding(dp(22), dp(18), dp(22), dp(18));
        layout.setBackground(shape(
                Color.parseColor(BG), 0xFF17432A, 16));

        TextView message = text(
                "¿Seguro que quieres desvincular esta cuenta?\n\n"
                        + "Los temporizadores manuales seguirán funcionando.",
                14, WHITE, false);

        layout.addView(message);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Desvincular Discord")
                .setView(layout)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Desvincular",
                        (d, w) -> unlinkDiscord())
                .create();

        dialog.setOnShowListener(d -> {
            int titleId = getResources().getIdentifier(
                    "alertTitle", "id", "android");

            TextView title = dialog.findViewById(titleId);

            if (title != null) {
                title.setTextColor(GREEN);
            }

            Button cancel = dialog.getButton(
                    AlertDialog.BUTTON_NEGATIVE);

            Button unlink = dialog.getButton(
                    AlertDialog.BUTTON_POSITIVE);

            if (cancel != null) {
                cancel.setTextColor(GOLD);
            }

            if (unlink != null) {
                unlink.setTextColor(0xFFFF8585);
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    shape(CARD, GREEN, 18));
        }
    }

    private void checkLinkStatus(boolean fromPolling) {
        new Thread(() -> {
            try {
                JSONObject body = createInstallationJson();

                JSONObject response = postJson(
                        LINK_STATUS_URL, body, null);

                int code = response.optInt("_httpCode", 0);

                if (code < 200 || code >= 300) {
                    return;
                }

                boolean linked = response.optBoolean("linked", false);
                String token = response.optString("token", "");

                if (linked && !token.isEmpty()) {
                    String previousToken = getApiToken();

                    linkPreferences.edit()
                            .putString(PREF_API_TOKEN, token)
                            .apply();

                    boolean accountChanged = !previousToken.equals(token);

                    handler.post(() -> {
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }

                        if (accountChanged) {
                            reloadSyncedIdsForCurrentAccount();
                        }

                        pollingForLink = false;
                        linkHandler.removeCallbacks(linkPoll);
                        currentLinkCode = "";

                        updateDiscordStatus(true);
                        startCooldownSyncService();

                        if (fromPolling) {
                            Toast.makeText(
                                    MainActivity.this,
                                    "¡Discord vinculado correctamente!",
                                    Toast.LENGTH_LONG).show();
                        }
                    });

                } else {
                    String previousToken = getApiToken();

                    if (!previousToken.isEmpty()) {
                        linkPreferences.edit()
                                .remove(PREF_API_TOKEN)
                                .apply();

                        handler.post(() -> {
                            reloadSyncedIdsForCurrentAccount();
                            updateDiscordStatus(false);
                            stopService(new Intent(
                                    MainActivity.this,
                                    CooldownSyncService.class));

                            if (fromPolling) {
                                Toast.makeText(
                                        MainActivity.this,
                                        "La vinculación no está activa",
                                        Toast.LENGTH_SHORT).show();
                            }
                        });
                    } else {
                        handler.post(() -> updateDiscordStatus(false));
                    }
                }

            } catch (Exception ignored) {
            }
        }).start();
    }

    private void unlinkDiscord() {
        String token = getApiToken();

        if (token.isEmpty()) {
            updateDiscordStatus(false);
            return;
        }

        unlinkButton.setEnabled(false);

        new Thread(() -> {
            boolean success = false;

            try {
                JSONObject response = postJson(
                        LINK_UNLINK_URL, new JSONObject(), token);

                int code = response.optInt("_httpCode", 0);

                success = code >= 200 && code < 300
                        || code == HttpURLConnection.HTTP_UNAUTHORIZED;

            } catch (Exception ignored) {
            }

            final boolean result = success;

            handler.post(() -> {
                unlinkButton.setEnabled(true);

                if (result) {
                    linkPreferences.edit()
                            .remove(PREF_API_TOKEN)
                            .apply();

                    reloadSyncedIdsForCurrentAccount();

                    pollingForLink = false;
                    linkHandler.removeCallbacks(linkPoll);

                    stopService(new Intent(
                            MainActivity.this,
                            CooldownSyncService.class));

                    updateDiscordStatus(false);

                    Toast.makeText(
                            MainActivity.this,
                            "Discord desvinculado",
                            Toast.LENGTH_LONG).show();

                } else {
                    Toast.makeText(
                            MainActivity.this,
                            "No se pudo desvincular. Inténtalo nuevamente.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void updateDiscordStatus(boolean linked) {
        if (discordStatusView == null) {
            return;
        }

        if (linked) {
            discordStatusView.setText("●  CUENTA DISCORD VINCULADA");
            discordStatusView.setTextColor(GREEN);

            if (linkButton != null) {
                linkButton.setEnabled(false);
                linkButton.setText("✓  DISCORD VINCULADO");
            }

            if (unlinkButton != null) {
                unlinkButton.setVisibility(View.VISIBLE);
            }

        } else {
            discordStatusView.setText("●  DISCORD NO VINCULADO");
            discordStatusView.setTextColor(GOLD);

            if (linkButton != null) {
                linkButton.setEnabled(true);
                linkButton.setText("＋  VINCULAR DISCORD");
            }

            if (unlinkButton != null) {
                unlinkButton.setVisibility(View.GONE);
            }
        }
    }

    // =========================================================
    // SINCRONIZACIÓN
    // =========================================================

    private void restoreSyncedIds() {
        syncedAccount = tokenHash(getApiToken());

        Set<String> saved = preferences.getStringSet(
                getSyncedIdsPreferenceKey(), new HashSet<>());

        syncedIds.clear();

        if (saved != null) {
            syncedIds.addAll(saved);
        }
    }

    private void saveSyncedIds() {
        if (preferences == null) {
            return;
        }

        preferences.edit()
                .putStringSet(
                        getSyncedIdsPreferenceKey(),
                        new HashSet<>(syncedIds))
                .apply();
    }

    private void addSyncedCooldown(
            String command,
            long endTime,
            long duration,
            String databaseId) {

        if (command == null || command.trim().isEmpty()
                || databaseId == null || databaseId.trim().isEmpty()
                || endTime <= System.currentTimeMillis()
                || duration <= 0) {
            return;
        }

        reloadSyncedIdsForCurrentAccount();

        String eventId = tokenHash(getApiToken()) + ":" + databaseId;

        for (Cooldown existing : cooldowns) {
            if (eventId.equals(existing.eventId)) {
                return;
            }
        }

        if (syncedIds.contains(eventId)) {
            return;
        }

        syncedIds.add(eventId);

        if (syncedIds.size() > MAX_SAVED_SYNCED_IDS) {
            Set<String> limited = new HashSet<>();
            int count = 0;

            for (String id : syncedIds) {
                if (count >= MAX_SAVED_SYNCED_IDS) {
                    break;
                }

                limited.add(id);
                count++;
            }

            syncedIds.clear();
            syncedIds.addAll(limited);
        }

        saveSyncedIds();

        Cooldown c = new Cooldown(command, duration);
        c.endTime = endTime;
        c.eventId = eventId;
        c.serverEventId = databaseId;
        c.notifyWhenDone = false;

        createCooldownCard(c);
        cooldowns.add(c);
        updateCooldown(c);
    }

    private void removeCooldownFromScreen(Cooldown c) {
        cancelManualAlarm(c);
        cooldowns.remove(c);

        if (c.eventId != null) {
            syncedIds.remove(c.eventId);
            saveSyncedIds();
        }

        timerList.removeView(c.card);
        saveManualCooldowns();
    }

    private void deleteSyncedCooldown(Cooldown c, Button deleteButton) {
        String token = getApiToken();

        if (token.isEmpty() || c.serverEventId == null
                || c.serverEventId.trim().isEmpty()) {
            Toast.makeText(
                    this,
                    "No se pudo identificar el evento en el servidor.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        deleteButton.setEnabled(false);
        deleteButton.setText("Eliminando...");

        new Thread(() -> {
            boolean success = false;

            try {
                success = deleteSyncedEvent(c.serverEventId, token);
            } catch (Exception e) {
                android.util.Log.e(
                        "BotCooldown",
                        "Error al eliminar evento sincronizado", e);
            }

            final boolean result = success;

            handler.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (result) {
                    removeCooldownFromScreen(c);

                    Toast.makeText(
                            MainActivity.this,
                            "Cooldown eliminado",
                            Toast.LENGTH_SHORT).show();

                } else {
                    deleteButton.setEnabled(true);
                    deleteButton.setText("Eliminar");

                    Toast.makeText(
                            MainActivity.this,
                            "No se pudo eliminar el evento del servidor. Inténtalo nuevamente.",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void startCooldownSyncService() {
        if (getApiToken().isEmpty()) {
            return;
        }

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

    // =========================================================
    // ALARMAS Y NOTIFICACIONES
    // =========================================================

    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarmManager != null
                    && alarmManager.canScheduleExactAlarms();
        }

        return true;
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || requestingExactAlarmPermission) {
            return;
        }

        requestingExactAlarmPermission = true;

        Toast.makeText(
                this,
                "Activa el permiso de alarmas exactas para mayor precisión",
                Toast.LENGTH_LONG).show();

        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            requestingExactAlarmPermission = false;

            Toast.makeText(
                    this,
                    "Activa las alarmas exactas en los ajustes de la app",
                    Toast.LENGTH_LONG).show();
        }
    }

    private PendingIntent getAlarmPendingIntent(Cooldown c) {
        Intent intent = new Intent(this, CooldownAlarmReceiver.class);
        intent.setAction(CooldownAlarmReceiver.ACTION_READY);
        intent.putExtra(
                CooldownAlarmReceiver.EXTRA_COMMAND, c.command);
        intent.putExtra(
                CooldownAlarmReceiver.EXTRA_END_TIME, c.endTime);
        intent.putExtra(
                CooldownAlarmReceiver.EXTRA_ALARM_ID, c.alarmRequestCode);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(
                this, c.alarmRequestCode, intent, flags);
    }

    private void scheduleManualAlarm(Cooldown c) {
        if (!c.notifyWhenDone || alarmManager == null
                || c.endTime <= System.currentTimeMillis()) {
            return;
        }

        try {
            PendingIntent pendingIntent = getAlarmPendingIntent(c);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && canScheduleExactAlarms()) {

                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        c.endTime,
                        pendingIntent);

            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        c.endTime,
                        pendingIntent);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestExactAlarmPermission();
                }

            } else {
                alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        c.endTime,
                        pendingIntent);
            }

        } catch (SecurityException e) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                requestExactAlarmPermission();
            }

            try {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        c.endTime,
                        getAlarmPendingIntent(c));
            } catch (Exception ignored) {
                Toast.makeText(
                        this,
                        "No se pudo programar el aviso",
                        Toast.LENGTH_SHORT).show();
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

    // =========================================================
    // GUARDADO DE TEMPORIZADORES
    // =========================================================

    private void saveManualCooldowns() {
        if (preferences == null) {
            return;
        }

        JSONArray array = new JSONArray();

        try {
            for (Cooldown c : cooldowns) {
                if (!c.notifyWhenDone) {
                    continue;
                }

                JSONObject item = new JSONObject();
                item.put("command", c.command);
                item.put("durationMillis", c.durationMillis);
                item.put("endTime", c.endTime);
                item.put("alarmRequestCode", c.alarmRequestCode);

                array.put(item);
            }

            preferences.edit()
                    .putString(PREF_MANUAL_TIMERS, array.toString())
                    .apply();

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudieron guardar los temporizadores",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreManualCooldowns() {
        String saved = preferences.getString(PREF_MANUAL_TIMERS, "[]");

        try {
            JSONArray array = new JSONArray(saved);

            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);

                String command = item.optString("command", "");
                long duration = item.optLong("durationMillis", 0L);
                long endTime = item.optLong("endTime", 0L);
                int alarmId = item.optInt("alarmRequestCode", 0);

                if (command.trim().isEmpty()
                        || duration <= 0
                        || endTime <= 0
                        || alarmId <= 0) {
                    continue;
                }

                Cooldown c = new Cooldown(command, duration);
                c.endTime = endTime;
                c.alarmRequestCode = alarmId;
                c.notifyWhenDone = true;

                if (alarmId >= nextAlarmId && alarmId < 2000000000) {
                    nextAlarmId = alarmId + 1;
                }

                createCooldownCard(c);
                cooldowns.add(c);

                if (endTime <= System.currentTimeMillis()) {
                    c.notified = true;
                    c.view.setText("✓  DISPONIBLE");
                    c.view.setTextColor(GREEN);
                } else {
                    updateCooldown(c);
                    scheduleManualAlarm(c);
                }
            }

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudieron recuperar algunos temporizadores",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================
    // ELEMENTOS VISUALES
    // =========================================================

    private int dp(float value) {
        return (int) (value
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();

        d.setColor(themedColor(color));
        d.setCornerRadius(dp(radius));

        if (stroke != Color.TRANSPARENT) {
            d.setStroke(dp(1), themedColor(stroke));
        }

        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(themedColor(color));

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

    // =========================================================
    // CONSTRUCCIÓN DE LA PANTALLA
    // =========================================================

    private void buildScreen() {
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Color.parseColor(BG));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.parseColor(BG));

        LinearLayout root = vertical();
        root.setPadding(dp(22), dp(24), dp(22), dp(110));
        scroll.addView(root);

        container.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottomBar.setBackground(shape(0xFF08130D, 0xFF24643A, 18));
        bottomBar.setElevation(dp(12));
        bottomBar.setAlpha(0f);
        bottomBar.setVisibility(View.GONE);

        Button colorsButton = createBottomBarButton("✦  Colores");
        Button backgroundsButton = createBottomBarButton("▧  Fondos");

        LinearLayout.LayoutParams colorParams =
                new LinearLayout.LayoutParams(0, dp(46), 1);

        LinearLayout.LayoutParams backgroundParams =
                new LinearLayout.LayoutParams(0, dp(46), 1);

        backgroundParams.leftMargin = dp(8);

        bottomBar.addView(colorsButton, colorParams);
        bottomBar.addView(backgroundsButton, backgroundParams);

        colorsButton.setOnClickListener(v -> showColorPalette());

        backgroundsButton.setOnClickListener(v ->
                Toast.makeText(
                        MainActivity.this,
                        "La sección Fondos estará disponible próximamente.",
                        Toast.LENGTH_SHORT).show());

        FrameLayout.LayoutParams barParams =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);

        barParams.leftMargin = dp(22);
        barParams.rightMargin = dp(22);
        barParams.bottomMargin = dp(16);

        container.addView(bottomBar, barParams);

        setContentView(container);

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

        LinearLayout discordCard = vertical();
        discordCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        discordCard.setBackground(shape(CARD, 0xFF17432A, 18));

        root.addView(discordCard, params(-1, -2));

        discordCard.addView(
                text("CUENTA DISCORD", 14, GOLD, true));

        discordStatusView = text(
                "●  COMPROBANDO VINCULACIÓN...", 12, MUTED, true);

        LinearLayout.LayoutParams statusParams = params(-1, -2);
        statusParams.topMargin = dp(12);
        discordCard.addView(discordStatusView, statusParams);

        linkButton = new Button(this);
        linkButton.setText("＋  VINCULAR DISCORD");
        linkButton.setTextColor(Color.rgb(4, 9, 12));
        linkButton.setTextSize(13);
        linkButton.setTypeface(null, Typeface.BOLD);
        linkButton.setAllCaps(false);
        linkButton.setBackground(shape(GREEN, GREEN, 12));

        LinearLayout.LayoutParams linkParams = params(-1, dp(48));
        linkParams.topMargin = dp(14);
        discordCard.addView(linkButton, linkParams);

        linkButton.setOnClickListener(v -> requestDiscordLink());

        unlinkButton = new Button(this);
        unlinkButton.setText("DESVINCULAR DISCORD");
        unlinkButton.setTextColor(0xFFFF8585);
        unlinkButton.setTextSize(12);
        unlinkButton.setAllCaps(false);
        unlinkButton.setBackground(shape(
                0xFF291416, 0xFF71343A, 12));

        LinearLayout.LayoutParams unlinkParams = params(-1, dp(46));
        unlinkParams.topMargin = dp(8);
        discordCard.addView(unlinkButton, unlinkParams);

        unlinkButton.setOnClickListener(v ->
                showUnlinkConfirmationDialog());

        updateDiscordStatus(!getApiToken().isEmpty());

        LinearLayout form = vertical();
        form.setPadding(dp(16), dp(16), dp(16), dp(18));
        form.setBackground(shape(CARD, 0xFF17432A, 18));

        LinearLayout.LayoutParams formParams = params(-1, -2);
        formParams.topMargin = dp(22);
        root.addView(form, formParams);

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

    // =========================================================
    // TEMPORIZADORES
    // =========================================================

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

            saveManualCooldowns();
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

            saveManualCooldowns();
            scheduleManualAlarm(c);
            updateCooldown(c);
        });

        delete.setOnClickListener(v -> {
            if (c.serverEventId != null) {
                deleteSyncedCooldown(c, delete);
            } else {
                removeCooldownFromScreen(c);
            }
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

    // =========================================================
    // NOTIFICACIONES
    // =========================================================

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
        linkHandler.removeCallbacks(linkPoll);

        try {
            unregisterReceiver(syncReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        super.onDestroy();
    }
}
