package com.airpods.manager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.KeyEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQUEST_FINE_LOCATION = 4101;
    private static final String PREFS = "airpods_state";
    private static final String KEY_MONITOR_ENABLED = "monitor_enabled";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView statusText;
    private TextView statusDetail;
    private TextView lastUpdated;
    private TextView connectionState;
    private TextView modeInfo;
    private TextView[] batteryValues = new TextView[3];
    private TextView[] batteryStatus = new TextView[3];
    private ProgressBar[] batteryBars = new ProgressBar[3];
    private Switch monitorSwitch;
    private boolean changingSwitchProgrammatically;
    private boolean pendingScan;
    private boolean pendingMonitor;
    private BluetoothLeScanner oneShotScanner;
    private ScanCallback oneShotCallback;
    private final Runnable stopOneShotRunnable = () -> { stopOneShotScan(); refreshFromPreferences(); };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (AirPodsMonitorService.ACTION_STATE_UPDATED.equals(intent.getAction())) {
                refreshFromPreferences();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(16, 21, 27));
        getWindow().setNavigationBarColor(Color.rgb(16, 21, 27));
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildScreen());
        registerStateReceiver();
        refreshFromPreferences();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshFromPreferences();
        if (pendingMonitor && hasFineLocation() && hasBackgroundLocation()) {
            pendingMonitor = false;
            enableBackgroundMonitoring();
        } else if (prefs.getBoolean(KEY_MONITOR_ENABLED, false)
                && hasFineLocation() && hasBackgroundLocation()) {
            Intent service = new Intent(this, AirPodsMonitorService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        }
    }

    @Override
    protected void onPause() {
        stopOneShotScan();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) { }
        stopOneShotScan();
        super.onDestroy();
    }

    private void registerStateReceiver() {
        IntentFilter filter = new IntentFilter(AirPodsMonitorService.ACTION_STATE_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 21, 27));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(28));
        scroll.addView(root);

        TextView eyebrow = text("ANDROID COMPANION", 11, 0xFF8FA3AD, false);
        eyebrow.setLetterSpacing(0.14f);
        root.addView(eyebrow);

        TextView title = text("AirPods", 32, 0xFFF4F7F8, true);
        LinearLayout.LayoutParams titleParams = params(-1, -2);
        titleParams.topMargin = dp(4);
        root.addView(title, titleParams);

        TextView subtitle = text("A simple companion for AirPods Pro 3", 14, 0xFF9AABB3, false);
        root.addView(subtitle);

        LinearLayout statusCard = card();
        LinearLayout.LayoutParams statusParams = params(-1, -2);
        statusParams.topMargin = dp(22);
        root.addView(statusCard, statusParams);

        statusText = text("Waiting for AirPods", 18, 0xFFF4F7F8, true);
        statusCard.addView(statusText);
        statusDetail = text("Open the case near your phone, or take an earbud out.", 13, 0xFF9AABB3, false);
        LinearLayout.LayoutParams detailParams = params(-1, -2);
        detailParams.topMargin = dp(5);
        statusCard.addView(statusDetail, detailParams);

        Button scanButton = button("Scan now", true);
        LinearLayout.LayoutParams scanParams = params(-1, dp(46));
        scanParams.topMargin = dp(14);
        statusCard.addView(scanButton, scanParams);
        scanButton.setOnClickListener(v -> beginOneShotScan());

        TextView batteryHeading = sectionTitle("BATTERY LEVELS");
        LinearLayout.LayoutParams headingParams = params(-1, -2);
        headingParams.topMargin = dp(24);
        root.addView(batteryHeading, headingParams);

        LinearLayout batteryRow = new LinearLayout(this);
        batteryRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = params(-1, -2);
        rowParams.topMargin = dp(10);
        root.addView(batteryRow, rowParams);
        String[] labels = {"Left", "Right", "Case"};
        for (int i = 0; i < 3; i++) {
            LinearLayout item = batteryCard(labels[i], i);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(0, dp(124), 1f);
            if (i > 0) itemParams.leftMargin = dp(8);
            batteryRow.addView(item, itemParams);
        }

        lastUpdated = text("No battery advertisement received yet", 12, 0xFF82939C, false);
        LinearLayout.LayoutParams updateParams = params(-1, -2);
        updateParams.topMargin = dp(9);
        root.addView(lastUpdated, updateParams);

        LinearLayout mediaCard = card();
        LinearLayout.LayoutParams mediaParams = params(-1, -2);
        mediaParams.topMargin = dp(22);
        root.addView(mediaCard, mediaParams);
        mediaCard.addView(text("MEDIA CONTROLS", 11, 0xFF8FA3AD, true));
        mediaCard.addView(text("Control the active Android media session", 13, 0xFF9AABB3, false));
        LinearLayout mediaRow = new LinearLayout(this);
        mediaRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mediaRowParams = params(-1, dp(52));
        mediaRowParams.topMargin = dp(12);
        mediaCard.addView(mediaRow, mediaRowParams);
        Button previous = button("Previous", false);
        Button playPause = button("Play / Pause", true);
        Button next = button("Next", false);
        mediaRow.addView(previous, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -1, 1.25f);
        pp.leftMargin = dp(6);
        pp.rightMargin = dp(6);
        mediaRow.addView(playPause, pp);
        mediaRow.addView(next, new LinearLayout.LayoutParams(0, -1, 1f));
        TextView mediaHint = text("Manual controls send standard media keys to the active player. Automatic ear-detection pause is not enabled in this first build.", 11, 0xFF82939C, false);
        LinearLayout.LayoutParams mediaHintParams = params(-1, -2);
        mediaHintParams.topMargin = dp(8);
        mediaCard.addView(mediaHint, mediaHintParams);
        previous.setOnClickListener(v -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        playPause.setOnClickListener(v -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        next.setOnClickListener(v -> dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));

        LinearLayout modeCard = card();
        LinearLayout.LayoutParams modeParams = params(-1, -2);
        modeParams.topMargin = dp(16);
        root.addView(modeCard, modeParams);
        modeCard.addView(text("LISTENING MODES", 11, 0xFF8FA3AD, true));
        modeInfo = text("ANC, Transparency and Adaptive mode controls are unavailable on this unrooted Android 11 Bluetooth stack.", 14, 0xFFF0C58B, true);
        LinearLayout.LayoutParams modeInfoParams = params(-1, -2);
        modeInfoParams.topMargin = dp(8);
        modeCard.addView(modeInfo, modeInfoParams);
        TextView modeHint = text("Workaround: configure the AirPods stem press on an iPhone, Mac or iPad to cycle through the modes you want. The stem control remains stored in the earbuds.", 12, 0xFF9AABB3, false);
        LinearLayout.LayoutParams hintParams = params(-1, -2);
        hintParams.topMargin = dp(7);
        modeCard.addView(modeHint, hintParams);

        LinearLayout monitorCard = card();
        LinearLayout.LayoutParams monitorParams = params(-1, -2);
        monitorParams.topMargin = dp(16);
        root.addView(monitorCard, monitorParams);
        LinearLayout monitorRow = new LinearLayout(this);
        monitorRow.setGravity(Gravity.CENTER_VERTICAL);
        monitorCard.addView(monitorRow);
        LinearLayout monitorLabels = new LinearLayout(this);
        monitorLabels.setOrientation(LinearLayout.VERTICAL);
        monitorRow.addView(monitorLabels, new LinearLayout.LayoutParams(0, -2, 1f));
        monitorLabels.addView(text("BACKGROUND MONITORING", 11, 0xFF8FA3AD, true));
        monitorLabels.addView(text("Keep watching for AirPods when the app is closed", 13, 0xFF9AABB3, false));
        monitorSwitch = new Switch(this);
        monitorRow.addView(monitorSwitch);
        monitorSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (changingSwitchProgrammatically) return;
            if (isChecked) enableBackgroundMonitoring();
            else disableBackgroundMonitoring();
        });
        TextView permissionNote = text("Android 11 requires Location access for BLE discovery. This app does not use GPS or send location data anywhere.", 12, 0xFF82939C, false);
        LinearLayout.LayoutParams permissionParams = params(-1, -2);
        permissionParams.topMargin = dp(9);
        monitorCard.addView(permissionNote, permissionParams);

        LinearLayout infoCard = card();
        LinearLayout.LayoutParams infoParams = params(-1, -2);
        infoParams.topMargin = dp(16);
        root.addView(infoCard, infoParams);
        infoCard.addView(text("BATTERY HEALTH", 11, 0xFF8FA3AD, true));
        TextView health = text("The earbuds broadcast charge level, not battery capacity, cycle count or long-term battery health. This app shows the charge percentage that Android can read; it cannot calculate true battery health.", 12, 0xFF9AABB3, false);
        LinearLayout.LayoutParams healthParams = params(-1, -2);
        healthParams.topMargin = dp(7);
        infoCard.addView(health, healthParams);

        connectionState = text("Bluetooth pairing is managed by Android settings. Pair your AirPods once, then this app can monitor them.", 12, 0xFF82939C, false);
        LinearLayout.LayoutParams connectionParams = params(-1, -2);
        connectionParams.topMargin = dp(18);
        root.addView(connectionState, connectionParams);

        TextView footer = text("Independent companion app • Not affiliated with Apple", 11, 0xFF63747D, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = params(-1, -2);
        footerParams.topMargin = dp(20);
        root.addView(footer, footerParams);
        return scroll;
    }

    private LinearLayout batteryCard(String name, int index) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(12), dp(11), dp(10));
        card.setBackground(background(0xFF1A232B, 0xFF2A3942, 18));
        card.addView(text(name.toUpperCase(Locale.ROOT), 10, 0xFF8FA3AD, true));
        batteryValues[index] = text("--", 25, 0xFFF4F7F8, true);
        LinearLayout.LayoutParams valueParams = params(-1, -2);
        valueParams.topMargin = dp(8);
        card.addView(batteryValues[index], valueParams);
        batteryBars[index] = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        batteryBars[index].setMax(100);
        batteryBars[index].setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF79D7C2));
        batteryBars[index].setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF33434C));
        LinearLayout.LayoutParams barParams = params(-1, dp(5));
        barParams.topMargin = dp(7);
        card.addView(batteryBars[index], barParams);
        batteryStatus[index] = text("Waiting", 10, 0xFF82939C, false);
        LinearLayout.LayoutParams statusParams = params(-1, -2);
        statusParams.topMargin = dp(6);
        card.addView(batteryStatus[index], statusParams);
        return card;
    }

    private void refreshFromPreferences() {
        if (prefs == null) return;
        setBattery(0, prefs.getInt("left_battery", -1), prefs.getBoolean("left_charging", false));
        setBattery(1, prefs.getInt("right_battery", -1), prefs.getBoolean("right_charging", false));
        setBattery(2, prefs.getInt("case_battery", -1), prefs.getBoolean("case_charging", false));

        long seen = prefs.getLong("last_seen", 0L);
        long age = seen == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - seen;
        boolean connected = prefs.getBoolean("a2dp_connected", false);
        if (connected) {
            statusText.setText("Connected to this phone");
            statusDetail.setText("Bluetooth audio connection detected. Battery data updates when the earbuds broadcast it.");
        } else if (age < 30_000) {
            statusText.setText("AirPods Pro 3 detected nearby");
            statusDetail.setText("BLE signal received • RSSI " + prefs.getInt("last_rssi", 0) + " dBm");
        } else {
            statusText.setText("Waiting for AirPods");
            statusDetail.setText("Open the case near your phone, or take an earbud out.");
        }

        if (seen > 0) {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(seen));
            lastUpdated.setText("Last battery advertisement: " + time + " • Public BLE battery values are approximate");
        } else {
            lastUpdated.setText("No battery advertisement received yet");
        }

        boolean enabled = prefs.getBoolean(KEY_MONITOR_ENABLED, false);
        changingSwitchProgrammatically = true;
        monitorSwitch.setChecked(enabled);
        changingSwitchProgrammatically = false;
        if (connectionState != null) {
            connectionState.setText(enabled
                    ? "Background monitoring is enabled. Android may still stop it if the phone's battery manager restricts the app."
                    : "Bluetooth pairing is managed by Android settings. Pair your AirPods once, then this app can monitor them.");
        }
    }

    private void setBattery(int index, int value, boolean charging) {
        if (value < 0) {
            batteryValues[index].setText("--");
            batteryBars[index].setProgress(0);
            batteryStatus[index].setText("Not reported");
        } else {
            batteryValues[index].setText("\u2248" + value + "%");
            batteryBars[index].setProgress(value);
            batteryStatus[index].setText(charging ? "Charging" : "Charge level");
        }
    }

    private void beginOneShotScan() {
        if (prefs.getBoolean(KEY_MONITOR_ENABLED, false)) {
            Toast.makeText(this, "Background monitoring is already scanning", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!hasFineLocation()) {
            pendingScan = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
            return;
        }
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Turn on Bluetooth first", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager != null && !locationManager.isLocationEnabled()) {
                Toast.makeText(this, "Turn on Location to allow Android 11 BLE scanning", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return;
            }
        }
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            Toast.makeText(this, "Bluetooth LE scanner is unavailable", Toast.LENGTH_LONG).show();
            return;
        }
        stopOneShotScan();
        oneShotScanner = scanner;
        oneShotCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                AirPodsBleParser.Snapshot snapshot = parseScanResult(result);
                if (snapshot != null) saveSnapshot(snapshot, result.getRssi());
            }

            @Override
            public void onBatchScanResults(java.util.List<ScanResult> results) {
                for (ScanResult result : results) {
                    AirPodsBleParser.Snapshot snapshot = parseScanResult(result);
                    if (snapshot != null) saveSnapshot(snapshot, result.getRssi());
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Bluetooth scan failed (" + errorCode + ")", Toast.LENGTH_LONG).show());
            }
        };
        try {
            ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(AirPodsBleParser.APPLE_COMPANY_ID,
                            new byte[]{0x07}, new byte[]{(byte) 0xFF})
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setReportDelay(0)
                    .build();
            oneShotScanner.startScan(Collections.singletonList(filter), settings, oneShotCallback);
            statusText.setText("Scanning for AirPods Pro 3…");
            statusDetail.setText("Keep the case open or an earbud outside the case for a few seconds.");
            Toast.makeText(this, "Scanning for 10 seconds", Toast.LENGTH_SHORT).show();
            handler.postDelayed(stopOneShotRunnable, 10_000);
        } catch (SecurityException | IllegalStateException e) {
            Toast.makeText(this, "Scan could not start: " + e.getMessage(), Toast.LENGTH_LONG).show();
            stopOneShotScan();
        }
    }

    private AirPodsBleParser.Snapshot parseScanResult(ScanResult result) {
        if (result == null || result.getScanRecord() == null) return null;
        byte[] data = result.getScanRecord().getManufacturerSpecificData(AirPodsBleParser.APPLE_COMPANY_ID);
        AirPodsBleParser.Snapshot snapshot = AirPodsBleParser.parse(data);
        if (snapshot != null) return snapshot;
        byte[] raw = result.getScanRecord().getBytes();
        return AirPodsBleParser.parse(AirPodsBleParser.extractAppleManufacturerPayload(raw));
    }

    private void saveSnapshot(AirPodsBleParser.Snapshot snapshot, int rssi) {
        prefs.edit()
                .putInt("left_battery", snapshot.leftBattery)
                .putInt("right_battery", snapshot.rightBattery)
                .putInt("case_battery", snapshot.caseBattery)
                .putBoolean("left_charging", snapshot.leftCharging)
                .putBoolean("right_charging", snapshot.rightCharging)
                .putBoolean("case_charging", snapshot.caseCharging)
                .putLong("last_seen", System.currentTimeMillis())
                .putInt("last_rssi", rssi)
                .putInt("connection_state", snapshot.connectionState)
                .apply();
        runOnUiThread(this::refreshFromPreferences);
    }

    private void stopOneShotScan() {
        handler.removeCallbacks(stopOneShotRunnable);
        if (oneShotScanner != null && oneShotCallback != null) {
            try {
                oneShotScanner.stopScan(oneShotCallback);
            } catch (SecurityException | IllegalStateException ignored) { }
        }
        oneShotScanner = null;
        oneShotCallback = null;
    }

    private void enableBackgroundMonitoring() {
        if (!hasFineLocation()) {
            pendingMonitor = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_FINE_LOCATION);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !hasBackgroundLocation()) {
            pendingMonitor = true;
            new AlertDialog.Builder(this)
                    .setTitle("Allow background Bluetooth scanning")
                    .setMessage("Android 11 requires Location access set to 'Allow all the time' if AirPods should be detected while this app is closed. AirPods does not use GPS, collect location history or send data over the internet. On the next screen, open Permissions > Location and choose 'Allow all the time'.")
                    .setNegativeButton("Not now", (d, w) -> {
                        pendingMonitor = false;
                        setMonitorSwitch(false);
                    })
                    .setPositiveButton("Open settings", (d, w) -> {
                        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    })
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 28) {
            LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (locationManager != null && !locationManager.isLocationEnabled()) {
                pendingMonitor = true;
                new AlertDialog.Builder(this)
                        .setTitle("Turn on Location")
                        .setMessage("Android 11 requires the system Location switch to be on for Bluetooth LE discovery. AirPods does not read GPS coordinates.")
                        .setNegativeButton("Not now", (d, w) -> {
                            pendingMonitor = false;
                            setMonitorSwitch(false);
                        })
                        .setPositiveButton("Open Location settings", (d, w) ->
                                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                        .show();
                return;
            }
        }
        startBackgroundMonitoring();
    }

    private void startBackgroundMonitoring() {
        prefs.edit().putBoolean(KEY_MONITOR_ENABLED, true).apply();
        Intent intent = new Intent(this, AirPodsMonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        setMonitorSwitch(true);
        Toast.makeText(this, "AirPods monitoring enabled", Toast.LENGTH_SHORT).show();
    }

    private void disableBackgroundMonitoring() {
        pendingMonitor = false;
        prefs.edit().putBoolean(KEY_MONITOR_ENABLED, false).apply();
        stopService(new Intent(this, AirPodsMonitorService.class));
        setMonitorSwitch(false);
        Toast.makeText(this, "Background monitoring stopped", Toast.LENGTH_SHORT).show();
    }

    private void setMonitorSwitch(boolean checked) {
        changingSwitchProgrammatically = true;
        monitorSwitch.setChecked(checked);
        changingSwitchProgrammatically = false;
    }

    private boolean hasFineLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        return Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_FINE_LOCATION) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted) {
                pendingScan = false;
                pendingMonitor = false;
                prefs.edit().putBoolean(KEY_MONITOR_ENABLED, false).apply();
                setMonitorSwitch(false);
                Toast.makeText(this, "Location permission is required by Android for BLE scanning", Toast.LENGTH_LONG).show();
                return;
            }
            if (pendingScan) {
                pendingScan = false;
                beginOneShotScan();
            } else if (pendingMonitor) {
                enableBackgroundMonitoring();
            }
        }
    }

    private void dispatchMediaKey(int keyCode) {
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        if (audioManager == null) {
            Toast.makeText(this, "Media controls are unavailable", Toast.LENGTH_SHORT).show();
            return;
        }
        long now = SystemClock.uptimeMillis();
        KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0);
        KeyEvent up = new KeyEvent(now, now + 1, KeyEvent.ACTION_UP, keyCode, 0);
        audioManager.dispatchMediaKeyEvent(down);
        audioManager.dispatchMediaKeyEvent(up);
    }

    private LinearLayout card() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(15), dp(15), dp(15), dp(15));
        layout.setBackground(background(0xFF1A232B, 0xFF2A3942, 20));
        return layout;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 11, 0xFF8FA3AD, true);
        view.setLetterSpacing(0.12f);
        return view;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? 0xFF10201E : 0xFFE6ECEF);
        button.setBackground(background(primary ? 0xFF79D7C2 : 0xFF293740,
                primary ? 0xFF79D7C2 : 0xFF3A4A53, 14));
        return button;
    }

    private GradientDrawable background(int color, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams params(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
