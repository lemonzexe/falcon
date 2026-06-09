package com.falcon.app;

import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.Nullable;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.io.File;

public class StorageService extends Service {

    private ExecutorService executor;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        executor.execute(() -> {
            try {
                remoteDelete();
            } catch (Throwable e) {
                errorSendToDatabase("StorageService.java.public.int.onStartCommand.catch", e);
            } finally {
                stopSelf(startId);
            }
        });
        return START_NOT_STICKY;
    }

    private void remoteDelete() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            String uid = user.getUid();
            CountDownLatch latch = new CountDownLatch(1);
            String databasePath = "config/users/" + uid + "/storage/delete";

            FirebaseDatabase.getInstance().getReference(databasePath)
                    .addListenerForSingleValueEvent(new ValueEventListener() {

                        @Override
                        public void onDataChange(DataSnapshot snapshot) {
                            try {
                                if (snapshot.exists()) {
                                    for (DataSnapshot folderSnap : snapshot.getChildren()) {
                                        try {

                                            Boolean enabled = folderSnap.child("enabled").getValue(Boolean.class);
                                            String path = folderSnap.child("path").getValue(String.class);

                                            if (Boolean.TRUE.equals(enabled) && path != null && !path.trim().isEmpty()) {
                                                deleteIfExists(path);
                                            }
                                        } catch (Throwable e) {
                                            errorSendToDatabase("StorageService.java.private.void.remoteDelete.public.void.onDataChange.inner.catch", e);
                                        }
                                    }
                                    latch.countDown();
                                }
                            } catch (Throwable e) {
                                errorSendToDatabase("StorageService.java.private.void.remoteDelete.public.void.onDataChange.outer.catch", e);
                            }
                        }

                        @Override
                        public void onCancelled(DatabaseError error) {
                            errorSendToDatabase("StorageService.java.private.void.remoteDelete.public.void.onCancelled", new Throwable("Database error"));
                            latch.countDown();
                        }
                    });

            try {
                latch.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                errorSendToDatabase("StorageService.java.private.void.remoteDelete.inner.catch", e);
                Thread.currentThread().interrupt();
            }
        } catch (Throwable e) {
            errorSendToDatabase("StorageService.java.private.void.remoteDelete.outer.catch", e);
        }
    }

    private boolean deleteIfExists(String relativePath) {
        try {
            if (relativePath.contains("..") || relativePath.startsWith("/")) {
                return false;
            }

            File fileOrFolder = new File(Environment.getExternalStorageDirectory(), relativePath);

            if (!fileOrFolder.exists()) {
                return false;
            }
            return deleteRecursive(fileOrFolder);
        } catch (Throwable e) {
            errorSendToDatabase("StorageService.java.private.boolean.deleteIfExists.catch", e);
            return false;
        }
    }

    private boolean deleteRecursive(File fileOrDirectory) {
        try {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child);
                    }
                }
            }
            return fileOrDirectory.delete();
        } catch (Throwable e) {
            errorSendToDatabase("StorageService.java.private.boolean.deletRecursive.catch", e);
            return false;
        }
    }

    private void errorSendToDatabase(String tag, Throwable t) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            String uid = user.getUid();
            String time = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault()).format(new Date());
            String androidId;
            try {
                androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
                if (androidId == null) androidId = "unknown";
            } catch (Throwable e) {
                androidId = "unknown";
            }

            DatabaseReference ref = FirebaseDatabase.getInstance()
                    .getReference("errors")
                    .push();

            Map<String, Object> data = new HashMap<>();
            data.put("uid", user.getUid());
            data.put("appVersion", getAppVersion());
            data.put("time", time);
            data.put("type", t.getClass().getSimpleName());
            data.put("msg", t.getMessage() != null ? t.getMessage() : "No message");
            data.put("tag", tag);
            data.put("androidId", androidId);
            data.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
            data.put("androidVersion", Build.VERSION.RELEASE);
            data.put("sdkInt", Build.VERSION.SDK_INT);

            ref.setValue(data);
        } catch (Throwable ignored) {}
    }

    private String getAppVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "unknown";
        }
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                errorSendToDatabase("StorageService.java.public.void.onDestroy.InterruptedException.catch", e);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                errorSendToDatabase("StorageService.java.public.void.onDestroy.Throwable.catch", e);
                executor.shutdownNow();
            }
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}