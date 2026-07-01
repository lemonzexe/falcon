package com.falcon.app.beta;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ErrorReporter {

    public static void send(Context context, String tag, Throwable t) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;

            String errorMessage = t.getMessage() != null ? t.getMessage() : "No message";
            String errorKey = tag + "|" + t.getClass().getSimpleName() + "|" + errorMessage;

            SharedPreferences sp = context.getSharedPreferences("app_data", Context.MODE_PRIVATE);
            String lastErrorKey = sp.getString("last_error_key", "");
            long lastErrorTime = sp.getLong("last_error_time", 0);

            long now = System.currentTimeMillis();

            if (errorKey.equals(lastErrorKey) && (now - lastErrorTime) < (10 * 60 * 1000)) {
                return;
            }

            sp.edit()
                .putString("last_error_key", errorKey)
                .putLong("last_error_time", now)
                .apply();

            String dateTime = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US).format(new Date());

            String androidId;
            try {
                androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
                if (androidId == null) androidId = "unknown";
            } catch (Exception ignored) {
                androidId = "unknown";
            }

            String appVersion;
            try {
                appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
            } catch (Exception ignored) {
                appVersion = "unknown";
            }

            DatabaseReference ref = FirebaseDatabase.getInstance()
                    .getReference("errors")
                    .push();

            Map<String, Object> data = new HashMap<>();
            data.put("uid", user.getUid());
            data.put("dateTime", dateTime);
            data.put("type", t.getClass().getSimpleName());
            data.put("message", errorMessage);
            data.put("tag", tag);
            data.put("manufacturer", Build.MANUFACTURER);
            data.put("brand", Build.BRAND);
            data.put("model", Build.MODEL);
            data.put("androidVersion", Build.VERSION.RELEASE);
            data.put("androidId", androidId);
            data.put("package", context.getPackageName());
            data.put("appVersion", appVersion);

            ref.setValue(data);

        } catch (Throwable ignored) {}
    }
}