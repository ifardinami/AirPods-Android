package com.airpods.manager;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
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
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AirPodsMonitorService extends Service {
    public static final String ACTION_STATE_UPDATED = "com.airpods.manager.STATE_UPDATED";
    private static final String CHANNEL_ID = "airpods_monitor";
    private static final int NOTIFICATION_ID = 7301;
    private static final String PREFS = "airpods_state";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private BluetoothProfile a2dpProfile;
    private boolean scanning;
    private boolean receiverRegistered;

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            processResult(result);
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            for (ScanResult result : results) processResult(result);
        }

        @Override
        public void onScanFailed(int errorCode) {
            prefs.edit().putString("scan_error", "Bluetooth scan failed: " + errorCode).apply();
            broadcastUpdate();
            updateNotification("Bluetooth scan unavailable");
        }
    };

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) startBleScan();
                else if (state == BluetoothAdapter.STATE_OFF) {
                    stopBleScan();
                    prefs.edit().putBoolean("a2dp_connected", false).apply();
                    broadcastUpdate();
                    updateNotification("Bluetooth is turned off");
                }
            } else {
                refreshA2dpConnection();
            }
        }
    };

    private final Runnable periodicUpdate = new Runnable() {
        @Override
        public void run() {
            refreshA2dpConnection();
            if (!scanning) startBleScan();
            broadcastUpdate();
            handler.postDelayed(this, 12_000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        registerBluetoothReceiver();
        connectA2dpProfile();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!hasRequiredPermissions()) {
            prefs.edit().putBoolean("monitor_enabled", false)
                    .putString("scan_error", "Location permission is required for background BLE scanning").apply();
            broadcastUpdate();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("Starting AirPods monitor"));
        startBleScan();
        refreshA2dpConnection();
        handler.removeCallbacks(periodicUpdate);
        handler.post(periodicUpdate);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(periodicUpdate);
        stopBleScan();
        if (receiverRegistered) {
            try { unregisterReceiver(bluetoothReceiver); } catch (IllegalArgumentException ignored) { }
            receiverRegistered = false;
        }
        if (bluetoothAdapter != null && a2dpProfile != null) {
            try { bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, a2dpProfile); }
            catch (RuntimeException ignored) { }
        }
        stopForeground(true);
        super.onDestroy();
    }

    private boolean hasRequiredPermissions() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        if (Build.VERSION.SDK_INT >= 29
                && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void registerBluetoothReceiver() {
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(bluetoothReceiver, filter);
        receiverRegistered = true;
    }

    private void connectA2dpProfile() {
        if (bluetoothAdapter == null) return;
        try {
            bluetoothAdapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProfile = proxy;
                        refreshA2dpConnection();
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProfile = null;
                        prefs.edit().putBoolean("a2dp_connected", false).apply();
                        broadcastUpdate();
                    }
                }
            }, BluetoothProfile.A2DP);
        } catch (RuntimeException ignored) { }
    }

    private void refreshA2dpConnection() {
        boolean connected = false;
        String connectedName = "";
        if (a2dpProfile != null) {
            try {
                List<BluetoothDevice> devices = a2dpProfile.getConnectedDevices();
                if (devices != null) {
                    for (BluetoothDevice device : devices) {
                        String name = device.getName();
                        if (name != null && name.toLowerCase(Locale.ROOT).contains("airpods")) {
                            connected = true;
                            connectedName = name;
                            break;
                        }
                    }
                }
            } catch (RuntimeException ignored) { }
        }
        prefs.edit()
                .putBoolean("a2dp_connected", connected)
                .putString("connected_name", connectedName)
                .apply();
        updateNotification(connected ? "Connected to " + connectedName : batteryNotificationText());
    }

    private void startBleScan() {
        if (scanning || bluetoothAdapter == null) return;
        try {
            if (!bluetoothAdapter.isEnabled()) {
                updateNotification("Turn on Bluetooth to monitor AirPods");
                return;
            }
            if (Build.VERSION.SDK_INT >= 28) {
                LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (locationManager != null && !locationManager.isLocationEnabled()) {
                    updateNotification("Turn on Location for BLE discovery");
                    return;
                }
            }
            bleScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (bleScanner == null) {
                updateNotification("Bluetooth LE scanner unavailable");
                return;
            }
            ScanFilter filter = new ScanFilter.Builder()
                    .setManufacturerData(AirPodsBleParser.APPLE_COMPANY_ID,
                            new byte[]{0x07}, new byte[]{(byte) 0xFF})
                    .build();
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setReportDelay(0)
                    .build();
            bleScanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanning = true;
            prefs.edit().remove("scan_error").apply();
            updateNotification("Monitoring nearby AirPods Pro 3");
        } catch (SecurityException | IllegalStateException e) {
            prefs.edit().putString("scan_error", e.getMessage() == null ? "Unable to start BLE scan" : e.getMessage()).apply();
            broadcastUpdate();
        }
    }

    private void stopBleScan() {
        if (bleScanner != null && scanning) {
            try { bleScanner.stopScan(scanCallback); }
            catch (SecurityException | IllegalStateException ignored) { }
        }
        scanning = false;
        bleScanner = null;
    }

    private void processResult(ScanResult result) {
        if (result == null || result.getScanRecord() == null) return;
        byte[] manufacturer = result.getScanRecord().getManufacturerSpecificData(AirPodsBleParser.APPLE_COMPANY_ID);
        AirPodsBleParser.Snapshot snapshot = AirPodsBleParser.parse(manufacturer);
        if (snapshot == null) {
            byte[] raw = result.getScanRecord().getBytes();
            snapshot = AirPodsBleParser.parse(AirPodsBleParser.extractAppleManufacturerPayload(raw));
        }
        if (snapshot == null) return;

        prefs.edit()
                .putInt("left_battery", snapshot.leftBattery)
                .putInt("right_battery", snapshot.rightBattery)
                .putInt("case_battery", snapshot.caseBattery)
                .putBoolean("left_charging", snapshot.leftCharging)
                .putBoolean("right_charging", snapshot.rightCharging)
                .putBoolean("case_charging", snapshot.caseCharging)
                .putBoolean("primary_left", snapshot.primaryLeft)
                .putBoolean("lid_closed", snapshot.lidClosed)
                .putInt("connection_state", snapshot.connectionState)
                .putInt("raw_status", snapshot.rawStatus)
                .putLong("last_seen", System.currentTimeMillis())
                .putInt("last_rssi", result.getRssi())
                .remove("scan_error")
                .apply();
        broadcastUpdate();
        updateNotification(batteryNotificationText());
    }

    private String batteryNotificationText() {
        int left = prefs.getInt("left_battery", -1);
        int right = prefs.getInt("right_battery", -1);
        int box = prefs.getInt("case_battery", -1);
        return "L " + format(left) + " • R " + format(right) + " • Case " + format(box);
    }

    private String format(int value) {
        return value < 0 ? "--" : value + "%";
    }

    private void broadcastUpdate() {
        sendBroadcast(new Intent(ACTION_STATE_UPDATED).setPackage(getPackageName()));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "AirPods background monitoring", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Bluetooth battery monitoring for your AirPods");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("AirPods")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
