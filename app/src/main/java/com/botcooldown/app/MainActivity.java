package com.botcooldown.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
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
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
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
    private TextView renderStatusView;
    private volatile boolean renderCheckInProgress = false;
    private int renderConnectionState = 0;
    private TextView exactAlarmStatusView;
    private TextView batteryOptimizationStatusView;
    private Button linkButton;
    private Button unlinkButton;

    private LinearLayout bottomBar;
    private float touchStartY;
    private float touchStartX;
    private boolean bottomBarVisible = false;

    private ImageView backgroundImageView;
    private View backgroundDimView;

    private static final int REQUEST_PICK_BACKGROUND = 2001;
    private static final String PREF_BACKGROUND_IMAGE_PATH =
            "background_image_path";

    private static final String BACKGROUND_FILE_NAME =
            "botcooldown_background_image";

    private static final String BACKGROUND_TEMP_FILE_NAME =
            "botcooldown_background_image.tmp";

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

    private final Runnable renderHealthPoll = new Runnable() {
        @Override public void run() {
            checkRenderConnection();
            handler.postDelayed(this, 15000);
        }
    };

    private static final String CHANNEL_ID = "botcooldown_ready";

    private static final String API_URL =
            "https://cooldown-detector.onrender.com";
    private static final String HEALTH_URL = API_URL + "/health";

    private static final String EVENTS_URL = API_URL + "/events";
    private static final String LINK_REQUEST_URL = API_URL + "/link/request";
    private static final String LINK_STATUS_URL = API_URL + "/link/status";
    private static final String LINK_UNLINK_URL = API_URL + "/link/unlink";

    private static final String PREFS_NAME = "botcooldown_preferences";
    private static final String PREF_MANUAL_TIMERS = "manual_timers";
    private static final String PREF_SYNCED_IDS = "synced_event_ids_";
    private static final String PREF_SYNCED_TIMERS = "synced_timers_";
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

        restoreSyncedCooldowns();
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

    private void refreshScreenWithNewAccent() {
        bottomBarVisible = false;
        buildScreen();

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

    /*
     * Crea tarjetas con transparencia sin cambiar sus colores originales.
     * El valor alpha controla cuánto se transparenta el fondo.
     */
    private GradientDrawable transparentShape(
            int color, int stroke, int radius, int alpha) {

        GradientDrawable drawable = new GradientDrawable();

        int backgroundColor = themedColor(color);
        int borderColor = themedColor(stroke);

        drawable.setColor(Color.argb(
                alpha,
                Color.red(backgroundColor),
                Color.green(backgroundColor),
                Color.blue(backgroundColor)));

        drawable.setCornerRadius(dp(radius));

        if (stroke != Color.TRANSPARENT) {
            drawable.setStroke(
                    dp(1),
                    Color.argb(
                            Math.min(230, alpha + 35),
                            Color.red(borderColor),
                            Color.green(borderColor),
                            Color.blue(borderColor)));
        }

        return drawable;
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
                ColorStateList.valueOf(GREEN));
        seekBar.setThumbTintList(
                ColorStateList.valueOf(GREEN));
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
    // FONDOS PERSONALIZADOS
    // =========================================================

    private void showBackgroundOptions() {

        LinearLayout layout = vertical();
        layout.setPadding(dp(20), dp(12), dp(20), dp(12));

        layout.setBackgroundColor(Color.TRANSPARENT);

        TextView chooseImage = text(
                "Elegir imagen de la galería",
                17, WHITE, true);

        chooseImage.setGravity(Gravity.CENTER_VERTICAL);
        chooseImage.setPadding(dp(12), dp(17), dp(12), dp(17));
        chooseImage.setBackground(transparentShape(
                0xFF102219, 0xFF24643A, 12, 210));

        TextView restoreBackground = text(
                "Quitar imagen y restaurar fondo original",
                17, WHITE, true);

        restoreBackground.setGravity(Gravity.CENTER_VERTICAL);
        restoreBackground.setPadding(
                dp(12), dp(17), dp(12), dp(17));

        restoreBackground.setBackground(transparentShape(
                0xFF102219, 0xFF24643A, 12, 210));

        LinearLayout.LayoutParams chooseParams = params(-1, -2);
        chooseParams.bottomMargin = dp(12);

        layout.addView(chooseImage, chooseParams);
        layout.addView(restoreBackground, params(-1, -2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("▧  Fondos personalizados")
                .setView(layout)
                .setNegativeButton("Cerrar", null)
                .create();

        chooseImage.setOnClickListener(v -> {
            dialog.dismiss();
            openBackgroundPicker();
        });

        restoreBackground.setOnClickListener(v -> {
            dialog.dismiss();
            removeBackgroundImage();
        });

        dialog.setOnShowListener(d -> {

            int titleId = getResources().getIdentifier(
                    "alertTitle", "id", "android");

            TextView title = dialog.findViewById(titleId);

            if (title != null) {
                title.setTextColor(GREEN);
                title.setTextSize(19);
            }

            Button close = dialog.getButton(
                    AlertDialog.BUTTON_NEGATIVE);

            if (close != null) {
                close.setTextColor(GOLD);
                close.setTextSize(15);
            }

            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(
                        shape(CARD, GREEN, 18));
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    shape(CARD, GREEN, 18));
        }
    }

    private void openBackgroundPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivityForResult(
                    intent, REQUEST_PICK_BACKGROUND);

        } catch (Exception e) {
            Toast.makeText(
                    this,
                    "No se pudo abrir la galería",
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_PICK_BACKGROUND
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {

            saveBackgroundImage(data.getData());
        }
    }

    private void saveBackgroundImage(Uri imageUri) {
        File temporaryFile = new File(
                getFilesDir(), BACKGROUND_TEMP_FILE_NAME);

        File destinationFile = new File(
                getFilesDir(), BACKGROUND_FILE_NAME);

        try {
            if (temporaryFile.exists()) {
                temporaryFile.delete();
            }

            try (InputStream input =
                         getContentResolver().openInputStream(imageUri);
                 FileOutputStream output =
                         new FileOutputStream(temporaryFile)) {

                if (input == null) {
                    throw new Exception("No se pudo leer la imagen");
                }

                byte[] buffer = new byte[8192];
                int bytesRead;

                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                }

                output.flush();
                output.getFD().sync();
            }

            BitmapFactory.Options checkOptions =
                    new BitmapFactory.Options();

            checkOptions.inJustDecodeBounds = true;

            BitmapFactory.decodeFile(
                    temporaryFile.getAbsolutePath(), checkOptions);

            if (checkOptions.outWidth <= 0
                    || checkOptions.outHeight <= 0) {
                throw new Exception("El archivo no es una imagen válida");
            }

            File replacementFile = new File(
                    getFilesDir(), BACKGROUND_FILE_NAME + ".new");

            if (replacementFile.exists()) {
                replacementFile.delete();
            }

            if (!temporaryFile.renameTo(replacementFile)) {
                throw new Exception("No se pudo preparar la imagen");
            }

            if (destinationFile.exists()
                    && !destinationFile.delete()) {
                replacementFile.delete();
                throw new Exception("No se pudo reemplazar el fondo anterior");
            }

            if (!replacementFile.renameTo(destinationFile)) {
                replacementFile.delete();
                throw new Exception("No se pudo guardar el nuevo fondo");
            }

            preferences.edit()
                    .putString(
                            PREF_BACKGROUND_IMAGE_PATH,
                            destinationFile.getAbsolutePath())
                    .apply();

            applySavedBackground();

            Toast.makeText(
                    this,
                    "Fondo personalizado aplicado",
                    Toast.LENGTH_SHORT).show();

        } catch (Exception e) {
            temporaryFile.delete();

            android.util.Log.e(
                    "BotCooldown",
                    "Error al guardar el fondo personalizado",
                    e);

            Toast.makeText(
                    this,
                    "No se pudo guardar la imagen",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void applySavedBackground() {
        if (backgroundImageView == null || preferences == null) {
            return;
        }

        String path = preferences.getString(
                PREF_BACKGROUND_IMAGE_PATH, "");

        if (path == null || path.trim().isEmpty()) {
            backgroundImageView.setImageDrawable(null);
            backgroundImageView.setVisibility(View.GONE);

            if (backgroundDimView != null) {
                backgroundDimView.setVisibility(View.GONE);
            }

            return;
        }

        File imageFile = new File(path);

        if (!imageFile.exists() || !imageFile.isFile()) {
            backgroundImageView.setImageDrawable(null);
            backgroundImageView.setVisibility(View.GONE);

            if (backgroundDimView != null) {
                backgroundDimView.setVisibility(View.GONE);
            }

            return;
        }

        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;

            BitmapFactory.decodeFile(path, options);

            if (options.outWidth <= 0 || options.outHeight <= 0) {
                throw new Exception("Imagen no válida");
            }

            int targetWidth = getResources()
                    .getDisplayMetrics().widthPixels;

            int targetHeight = getResources()
                    .getDisplayMetrics().heightPixels;

            options.inSampleSize = calculateInSampleSize(
                    options,
                    targetWidth,
                    targetHeight);

            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bitmap = BitmapFactory.decodeFile(path, options);

            if (bitmap == null) {
                throw new Exception("No se pudo cargar la imagen");
            }

            backgroundImageView.setImageBitmap(bitmap);
            backgroundImageView.setVisibility(View.VISIBLE);

            if (backgroundDimView != null) {
                backgroundDimView.setVisibility(View.VISIBLE);
            }

        } catch (OutOfMemoryError | Exception e) {
            android.util.Log.e(
                    "BotCooldown",
                    "No se pudo aplicar el fondo personalizado",
                    e);

            backgroundImageView.setImageDrawable(null);
            backgroundImageView.setVisibility(View.GONE);

            if (backgroundDimView != null) {
                backgroundDimView.setVisibility(View.GONE);
            }
        }
    }

    private int calculateInSampleSize(
            BitmapFactory.Options options,
            int requestedWidth,
            int requestedHeight) {

        int height = options.outHeight;
        int width = options.outWidth;

        int sampleSize = 1;

        if (height > requestedHeight || width > requestedWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / sampleSize) >= requestedHeight
                    && (halfWidth / sampleSize) >= requestedWidth) {
                sampleSize *= 2;
            }
        }

        return Math.max(1, sampleSize);
    }

    private void removeBackgroundImage() {
        if (preferences == null) {
            return;
        }

        String path = preferences.getString(
                PREF_BACKGROUND_IMAGE_PATH, "");

        if (path != null && !path.trim().isEmpty()) {
            File imageFile = new File(path);

            if (imageFile.exists()) {
                imageFile.delete();
            }
        }

        File temporaryFile = new File(
                getFilesDir(), BACKGROUND_TEMP_FILE_NAME);

        if (temporaryFile.exists()) {
            temporaryFile.delete();
        }

        preferences.edit()
                .remove(PREF_BACKGROUND_IMAGE_PATH)
                .apply();

        if (backgroundImageView != null) {
            backgroundImageView.setImageDrawable(null);
            backgroundImageView.setVisibility(View.GONE);
        }

        if (backgroundDimView != null) {
            backgroundDimView.setVisibility(View.GONE);
        }

        Toast.makeText(
                this,
                "Se restauró el fondo original",
                Toast.LENGTH_SHORT).show();
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
        button.setBackground(transparentShape(
                0xFF102219, 0xFF24643A, 12, 215));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    @Override
    protected void onResume() {
        super.onResume();

        reloadSyncedIdsForCurrentAccount();

        if (alarmManager != null) {
            requestingExactAlarmPermission = false;

            for (Cooldown c : cooldowns) {
                if (c.notifyWhenDone && !c.notified) {
                    scheduleManualAlarm(c);
                }
            }
        }

        updateExactAlarmStatus();
        updateBatteryOptimizationStatus();
        checkLinkStatus(false);
        handler.removeCallbacks(renderHealthPoll);
        handler.post(renderHealthPoll);

        showBackgroundSetupDialogIfNeeded();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(renderHealthPoll);
        super.onPause();
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
                .setNegativeButton("Entendido", null)
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
                            "Recuerda mantener permitida la actividad en segundo plano.",
                            Toast.LENGTH_LONG).show();
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

    private String getSyncedTimersPreferenceKey() {
        return PREF_SYNCED_TIMERS + tokenHash(getApiToken());
    }

    private void reloadSyncedIdsForCurrentAccount() {
        String account = tokenHash(getApiToken());

        if (!account.equals(syncedAccount)) {
            ArrayList<Cooldown> previousSynced = new ArrayList<>();

            for (Cooldown c : cooldowns) {
                if (c.serverEventId != null) {
                    previousSynced.add(c);
                }
            }

            for (Cooldown c : previousSynced) {
                cancelManualAlarm(c);
                cooldowns.remove(c);

                if (timerList != null && c.card != null) {
                    timerList.removeView(c.card);
                }
            }

            syncedAccount = account;
            restoreSyncedIds();

            if (!account.isEmpty() && timerList != null) {
                restoreSyncedCooldowns();
            }
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

                    notifyAppError("vinculacion", "No se pudo solicitar el código de Discord. Comprueba la conexión e inténtalo nuevamente.");
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

            } catch (Exception error) {
                if (fromPolling) {
                    handler.post(() -> notifyAppError("vinculacion", "No se pudo comprobar la vinculación con Discord. La aplicación volverá a intentarlo."));
                }
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
        if (preferences == null) {
            return;
        }

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

    private void saveSyncedCooldowns() {
        if (preferences == null) return;
        String token = getApiToken();
        if (token == null || token.trim().isEmpty()) return;
        JSONArray array = new JSONArray();
        try {
            for (Cooldown c : cooldowns) {
                if (c.serverEventId == null || c.serverEventId.trim().isEmpty()) continue;
                JSONObject item = new JSONObject();
                item.put("command", c.command);
                item.put("databaseId", c.serverEventId);
                item.put("durationMillis", c.durationMillis);
                item.put("endTime", c.endTime);
                item.put("alarmRequestCode", c.alarmRequestCode);
                array.put(item);
            }
            preferences.edit().putString(getSyncedTimersPreferenceKey(), array.toString()).apply();
        } catch (Exception e) {
            android.util.Log.e("BotCooldown", "No se pudieron guardar los temporizadores sincronizados", e);
        }
    }

    private void restoreSyncedCooldowns() {
        if (preferences == null || timerList == null) return;
        String token = getApiToken();
        if (token == null || token.trim().isEmpty()) return;
        String accountHash = tokenHash(token);
        if (accountHash.isEmpty()) return;
        String saved = preferences.getString(PREF_SYNCED_TIMERS + accountHash, "[]");
        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String command = item.optString("command", "");
                String databaseId = item.optString("databaseId", "");
                long duration = item.optLong("durationMillis", 0L);
                long endTime = item.optLong("endTime", 0L);
                if (command.trim().isEmpty() || databaseId.trim().isEmpty()
                        || duration <= 0 || endTime <= 0) continue;
                String eventId = accountHash + ":" + databaseId;
                boolean exists = false;
                for (Cooldown existing : cooldowns) {
                    if (eventId.equals(existing.eventId)) { exists = true; break; }
                }
                if (exists) continue;
                Cooldown c = new Cooldown(command, duration);
                c.endTime = endTime;
                c.eventId = eventId;
                c.serverEventId = databaseId;
                c.alarmRequestCode = syncedAlarmId(databaseId);
                c.notifyWhenDone = true;
                createCooldownCard(c);
                cooldowns.add(c);
                syncedIds.add(eventId);
                if (endTime <= System.currentTimeMillis()) {
                    c.notified = true;
                    c.view.setText("✓  DISPONIBLE");
                    c.view.setTextColor(GREEN);
                } else {
                    updateCooldown(c);
                    scheduleManualAlarm(c);
                }
            }
            saveSyncedIds();
            saveSyncedCooldowns();
        } catch (Exception e) {
            android.util.Log.e("BotCooldown", "Error al restaurar temporizadores sincronizados", e);
        }
    }

    private int syncedAlarmId(String databaseId) {
        return 1000000000 + (databaseId.hashCode() & 0x3fffffff);
    }

    private void addSyncedCooldown(
            String command, long endTime, long duration, String databaseId) {
        if (command == null || command.trim().isEmpty()
                || databaseId == null || databaseId.trim().isEmpty()
                || endTime <= 0 || duration <= 0
                || getApiToken().trim().isEmpty()) return;

        reloadSyncedIdsForCurrentAccount();
        String accountHash = tokenHash(getApiToken());
        if (accountHash.isEmpty()) return;
        String eventId = accountHash + ":" + databaseId;

        for (Cooldown existing : cooldowns) {
            if (eventId.equals(existing.eventId)) {
                boolean changed = existing.endTime != endTime
                        || existing.durationMillis != duration
                        || !existing.command.equals(command);
                existing.command = command;
                existing.endTime = endTime;
                existing.durationMillis = duration;
                existing.notified = endTime <= System.currentTimeMillis();
                existing.notifyWhenDone = true;
                if (changed) {
                    cancelManualAlarm(existing);
                    if (!existing.notified) scheduleManualAlarm(existing);
                    updateCooldown(existing);
                    saveSyncedCooldowns();
                }
                return;
            }
        }

        Cooldown c = new Cooldown(command, duration);
        c.endTime = endTime;
        c.eventId = eventId;
        c.serverEventId = databaseId;
        c.alarmRequestCode = syncedAlarmId(databaseId);
        c.notifyWhenDone = true;
        c.notified = endTime <= System.currentTimeMillis();
        createCooldownCard(c);
        cooldowns.add(c);
        syncedIds.add(eventId);
        if (syncedIds.size() > MAX_SAVED_SYNCED_IDS) {
            Set<String> limited = new HashSet<>();
            int count = 0;
            for (String id : syncedIds) {
                if (count++ >= MAX_SAVED_SYNCED_IDS) break;
                limited.add(id);
            }
            syncedIds.clear();
            syncedIds.addAll(limited);
        }
        saveSyncedIds();
        saveSyncedCooldowns();
        if (c.notified) {
            c.view.setText("✓  DISPONIBLE");
            c.view.setTextColor(GREEN);
        } else {
            updateCooldown(c);
            scheduleManualAlarm(c);
        }
    }

    private void removeCooldownFromScreen(Cooldown c) {
        cancelManualAlarm(c);
        cooldowns.remove(c);

        if (c.serverEventId != null) {
            if (c.eventId != null) {
                syncedIds.remove(c.eventId);
            }

            saveSyncedIds();
            saveSyncedCooldowns();
        } else {
            saveManualCooldowns();
        }

        if (timerList != null && c.card != null) {
            timerList.removeView(c.card);
        }
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

    private void updateExactAlarmStatus() {
        if (exactAlarmStatusView == null) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            exactAlarmStatusView.setText("✓ Alarmas exactas: disponibles en este Android");
            exactAlarmStatusView.setTextColor(GREEN);
            return;
        }

        boolean allowed = canScheduleExactAlarms();
        exactAlarmStatusView.setText(allowed
                ? "✓ Alarmas exactas: disponibles"
                : "! Alarmas exactas: acceso pendiente");
        exactAlarmStatusView.setTextColor(allowed ? GREEN : GOLD);
    }

    private void updateBatteryOptimizationStatus() {
        if (batteryOptimizationStatusView == null) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            batteryOptimizationStatusView.setText(
                    "✓ Optimización de batería: no aplica en este Android");
            batteryOptimizationStatusView.setTextColor(GREEN);
            return;
        }

        PowerManager powerManager =
                (PowerManager) getSystemService(POWER_SERVICE);
        boolean exempt = powerManager != null
                && powerManager.isIgnoringBatteryOptimizations(getPackageName());

        batteryOptimizationStatusView.setText(exempt
                ? "✓ Exención de optimización de batería: activa"
                : "! Exención de optimización de batería: no activa");
        batteryOptimizationStatusView.setTextColor(exempt ? GREEN : GOLD);
    }

    private void openExactAlarmSettingsFromButton() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Toast.makeText(this, "Este Android no requiere ese permiso especial.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        // Abrimos la pantalla aunque Android permita alarmas por una
        // exención de batería: esa capacidad no demuestra por sí sola
        // que el acceso especial visible al usuario esté concedido.
        Uri appUri = Uri.parse("package:" + getPackageName());
        try {
            Intent requestIntent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            requestIntent.setData(appUri);
            startActivity(requestIntent);
        } catch (Exception exactAlarmError) {
            android.util.Log.w("BotCooldown",
                    "No se pudo abrir el permiso de alarmas exactas",
                    exactAlarmError);
            try {
                Intent appSettingsIntent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                appSettingsIntent.setData(appUri);
                startActivity(appSettingsIntent);
            } catch (Exception appSettingsError) {
                android.util.Log.e("BotCooldown",
                        "No se pudieron abrir los ajustes de la aplicación",
                        appSettingsError);
                Toast.makeText(this,
                        "No se pudieron abrir los ajustes. Busca BotCooldown en Ajustes > Aplicaciones.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    private boolean canScheduleExactAlarms() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return alarmManager != null
                    && alarmManager.canScheduleExactAlarms();
        }

        return true;
    }

    private void requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || canScheduleExactAlarms()
                || requestingExactAlarmPermission) {
            return;
        }

        requestingExactAlarmPermission = true;

        Toast.makeText(
                this,
                "BotCooldown necesita permiso de alarmas exactas. Actívalo para recibir avisos puntuales.",
                Toast.LENGTH_LONG).show();

        Uri appUri = Uri.parse("package:" + getPackageName());

        try {
            Intent exactAlarmIntent = new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            exactAlarmIntent.setData(appUri);
            startActivity(exactAlarmIntent);
            return;
        } catch (Exception exactAlarmError) {
            android.util.Log.w(
                    "BotCooldown",
                    "No se pudo abrir el ajuste de alarmas exactas",
                    exactAlarmError);
        }

        // Algunos fabricantes no implementan la pantalla específica.
        // Como alternativa, abrir los ajustes generales de esta aplicación.
        try {
            Intent appSettingsIntent = new Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            appSettingsIntent.setData(appUri);
            startActivity(appSettingsIntent);
        } catch (Exception appSettingsError) {
            requestingExactAlarmPermission = false;
            android.util.Log.e(
                    "BotCooldown",
                    "No se pudieron abrir los ajustes de la aplicación",
                    appSettingsError);
            Toast.makeText(
                    this,
                    "Abre Ajustes > Aplicaciones > BotCooldown y busca Alarmas y recordatorios.",
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
        if (c.serverEventId != null && c.eventId != null) {
            intent.putExtra(CooldownAlarmReceiver.EXTRA_ACCOUNT_HASH, tokenHash(getApiToken()));
            intent.putExtra(CooldownAlarmReceiver.EXTRA_EVENT_ID, c.serverEventId);
        }

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        return PendingIntent.getBroadcast(
                this, c.alarmRequestCode, intent, flags);
    }

    private void scheduleManualAlarm(Cooldown c) {
        if (!c.notifyWhenDone || alarmManager == null
                || c.endTime <= 0 || c.notified) {
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
        if (alarmManager == null || (!c.notifyWhenDone && c.serverEventId == null)) {
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

        backgroundImageView = new ImageView(this);
        backgroundImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backgroundImageView.setVisibility(View.GONE);

        container.addView(backgroundImageView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        backgroundDimView = new View(this);
        backgroundDimView.setBackgroundColor(0x9904090C);
        backgroundDimView.setVisibility(View.GONE);

        container.addView(backgroundDimView,
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = vertical();
        root.setPadding(dp(22), dp(24), dp(22), dp(110));
        root.setBackgroundColor(Color.TRANSPARENT);

        scroll.addView(root);

        container.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(10), dp(8), dp(10), dp(8));
        bottomBar.setBackground(transparentShape(
                0xFF08130D, 0xFF24643A, 18, 215));
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
                showBackgroundOptions());

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

        applySavedBackground();

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
        discordCard.setBackground(transparentShape(
                CARD, 0xFF17432A, 18, 190));

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
        form.setBackground(transparentShape(
                CARD, 0xFF17432A, 18, 190));

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

        exactAlarmStatusView = text("Comprobando permiso de alarmas exactas...",
                12, MUTED, true);
        LinearLayout.LayoutParams alarmStatusParams = params(-1, -2);
        alarmStatusParams.topMargin = dp(14);
        form.addView(exactAlarmStatusView, alarmStatusParams);

        batteryOptimizationStatusView = text(
                "Comprobando optimización de batería...",
                12, MUTED, true);
        LinearLayout.LayoutParams batteryStatusParams = params(-1, -2);
        batteryStatusParams.topMargin = dp(10);
        form.addView(batteryOptimizationStatusView, batteryStatusParams);

        TextView backgroundHelp = text(
                "IMPORTANTE: permite la actividad en segundo plano en los ajustes de batería de tu OPPO. "
                        + "Android no ofrece una comprobación universal para ese ajuste del fabricante.",
                12, MUTED, false);
        LinearLayout.LayoutParams backgroundHelpParams = params(-1, -2);
        backgroundHelpParams.topMargin = dp(10);
        form.addView(backgroundHelp, backgroundHelpParams);

        Button backgroundSettingsButton = new Button(this);
        backgroundSettingsButton.setText("CONFIGURAR ACTIVIDAD EN SEGUNDO PLANO");
        backgroundSettingsButton.setTextColor(GREEN);
        backgroundSettingsButton.setTextSize(12);
        backgroundSettingsButton.setAllCaps(false);
        backgroundSettingsButton.setBackground(transparentShape(
                0xFF102219, 0xFF24643A, 12, 215));
        LinearLayout.LayoutParams backgroundButtonParams = params(-1, dp(44));
        backgroundButtonParams.topMargin = dp(8);
        form.addView(backgroundSettingsButton, backgroundButtonParams);
        backgroundSettingsButton.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this,
                        "Abre Ajustes > Aplicaciones > BotCooldown.",
                        Toast.LENGTH_LONG).show();
            }
        });

        Button exactAlarmSettingsButton = new Button(this);
        exactAlarmSettingsButton.setText("VERIFICAR / CONFIGURAR ALARMAS");
        exactAlarmSettingsButton.setTextColor(GREEN);
        exactAlarmSettingsButton.setTextSize(12);
        exactAlarmSettingsButton.setAllCaps(false);
        exactAlarmSettingsButton.setBackground(transparentShape(
                0xFF102219, 0xFF24643A, 12, 215));
        LinearLayout.LayoutParams exactAlarmButtonParams = params(-1, dp(44));
        exactAlarmButtonParams.topMargin = dp(8);
        form.addView(exactAlarmSettingsButton, exactAlarmButtonParams);
        exactAlarmSettingsButton.setOnClickListener(v ->
                openExactAlarmSettingsFromButton());

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
        e.setBackground(transparentShape(
                Color.parseColor(BG), 0xFF28503A, 12, 195));
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
        card.setBackground(transparentShape(
                CARD, 0xFF205034, 16, 185));

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
        reset.setBackground(transparentShape(
                0xFF10291A, 0xFF24643A, 12, 205));
        actions.addView(reset, new LinearLayout.LayoutParams(
                0, dp(44), 1));

        Button delete = new Button(this);
        delete.setText("Eliminar");
        delete.setAllCaps(false);
        delete.setTextColor(0xFFFF8585);
        delete.setTextSize(12);
        delete.setBackground(transparentShape(
                0xFF291416, 0xFF71343A, 12, 205));

        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                0, dp(44), 1);
        dp2.leftMargin = dp(10);
        actions.addView(delete, dp2);

        reset.setOnClickListener(v -> {
            cancelManualAlarm(c);
            c.endTime = System.currentTimeMillis() + c.durationMillis;
            c.notified = false;

            if (c.serverEventId != null) {
                c.notifyWhenDone = false;
                saveSyncedCooldowns();
            } else {
                saveManualCooldowns();
                scheduleManualAlarm(c);
            }

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
            ArrayList<Cooldown> expiredSynced = new ArrayList<>();

            for (Cooldown c : cooldowns) {
                if (now >= c.endTime) {
                    if (c.serverEventId != null) {
                        expiredSynced.add(c);
                    } else if (!c.notified) {
                        c.notified = true;
                        c.view.setText("✓  DISPONIBLE");
                        c.view.setTextColor(GREEN);
                    }
                } else {
                    updateCooldown(c);
                }
            }

            if (!expiredSynced.isEmpty()) {
                for (Cooldown c : expiredSynced) {
                    cooldowns.remove(c);

                    if (timerList != null && c.card != null) {
                        timerList.removeView(c.card);
                    }

                    if (c.eventId != null) {
                        syncedIds.remove(c.eventId);
                    }
                }

                saveSyncedIds();
                saveSyncedCooldowns();
            }

            handler.postDelayed(this, 1000);
        }
    };

    private void updateCooldown(Cooldown c) {
        if (c.view == null) {
            return;
        }

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
