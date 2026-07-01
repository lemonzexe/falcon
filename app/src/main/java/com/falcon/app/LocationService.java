package com.falcon.app;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Build;
import android.provider.Settings;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LocationService extends Service {

    private LocationManager locationManager;
    private LocationListener locationListener;
    private DatabaseReference databaseRef;
    private boolean isUsingGPS = false;
    
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            databaseRef = FirebaseDatabase.getInstance().getReference("users_data");

            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    try {
                        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
                        if (user == null) {
                            stopSelf();
                            return;
                        }

                        if (location == null) {
                            stopSelf();
                            return;
                        }

                        float accuracy = location.getAccuracy();

                        try {
                            if (!isUsingGPS && LocationManager.NETWORK_PROVIDER.equals(location.getProvider()) && accuracy > 100) {
                                isUsingGPS = true;
                                
                                if (ActivityCompat.checkSelfPermission(LocationService.this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                    locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, locationListener, null);
                                    return;
                                }
                            }
                        } catch (Throwable e) {
                            ErrorReporter.send(getApplicationContext(), "LocationService.java.public.void.onCreate.catch.public.void.onLocationChanged.inner.location.catch", e);
                        }

                        try {
                            String uid = user.getUid();
                            String dateTime = new SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.US).format(new Date());

                            Map<String, Object> data = new HashMap<>();
                            data.put("type", "current");
                            data.put("latitude", location.getLatitude());
                            data.put("longitude", location.getLongitude());
                            data.put("accuracy", accuracy);
                            data.put("provider", location.getProvider());
                            data.put("dateTime", dateTime);

                            databaseRef.child(uid)
                                    .child("locations")
                                    .push()
                                    .setValue(data)
                                    .addOnCompleteListener(task -> {
                                        try {
                                            if (task.isSuccessful()) {
                                                String lat = String.valueOf(location.getLatitude());
                                                String lon = String.valueOf(location.getLongitude());
                                                String latLon = lat + lon;

                                                SharedPreferences sp = getSharedPreferences("app_data", MODE_PRIVATE);
                                                sp.edit().putString("last_current_location_latlon", latLon).apply();
                                            }
                                        } catch (Throwable e) {
                                            ErrorReporter.send(getApplicationContext(), "LocationService.java.public.void.onLocationChanged.firebase.CompleteListener.saveSharedPrefs.catch", e);
                                        }
                                        stopSelf();
                                    })
                                    .addOnFailureListener(e -> {
                                        ErrorReporter.send(getApplicationContext(), "LocationService.java.public.void.onLocationChanged.firebase.FailureListener", e); 
                                        stopSelf();
                                    });

                        } catch (Throwable e) {
                            ErrorReporter.send(getApplicationContext(), "LocationService.java.public.void.onCreate.catch.public.void.onLocationChanged.inner.firebase.catch", e); 
                            stopSelf();
                        }

                    } catch (Throwable e) {
                        ErrorReporter.send(getApplicationContext(), "LocationService.java.public.void.onCreate.catch.public.void.onLocationChanged.outer.catch", e); 
                        stopSelf();
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}
                    
                @Override
                public void onProviderEnabled(String provider) {}
                    
                @Override
                public void onProviderDisabled(String provider) {
                    stopSelf();
                }
            };
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "LocationService.java.public.void.onCreate.catch", e); 
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopSelf();
                return START_NOT_STICKY;
            }

            if (locationManager != null) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, locationListener, null);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "LocationService.java.public.int.onStartCommand.catch", e); 
            stopSelf();
        }
        return START_NOT_STICKY;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (locationManager != null && locationListener != null) {
                locationManager.removeUpdates(locationListener);
            }
        } catch (Throwable e) {
            ErrorReporter.send(getApplicationContext(), "LocationService.java.public.void.onDestroy.catch", e); 
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
