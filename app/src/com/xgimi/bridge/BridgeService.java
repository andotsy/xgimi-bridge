package com.xgimi.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.IOException;

public class BridgeService extends Service {
    private static final String TAG = "XgimiBridge";
    private static final String CHANNEL_ID = "bridge";
    private static final int NOTIF_ID = 1;
    private static final int HTTP_PORT = 8080;

    private HttpServer server;
    private AidlClient aidl;
    private PresetStore presets;
    private MdnsAdvertiser mdns;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "service onCreate");
        startInForeground();
        acquireLocks();

        aidl = new AidlClient(this);
        aidl.connect();

        presets = new PresetStore(this, aidl);

        try {
            server = new HttpServer(this, HTTP_PORT, aidl, presets);
            server.start();
            Log.i(TAG, "HTTP server started on :" + HTTP_PORT);
            mdns = new MdnsAdvertiser(this, HTTP_PORT);
            mdns.start();
        } catch (IOException e) {
            Log.e(TAG, "failed to start http", e);
        }
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }
    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service onDestroy");
        if (server != null) server.stop();
        if (mdns != null) mdns.stop();
        releaseLocks();
        super.onDestroy();
    }

    /**
     * Hold a partial CPU wake lock and a high-perf Wi-Fi lock for the lifetime of the service.
     * Without these, the projector's standby state suspends the CPU and powers down the radio,
     * killing the HTTP socket and dropping the mDNS advertisement until the next boot/wake.
     */
    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XgimiBridge:cpu");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "XgimiBridge:wifi");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
            Log.i(TAG, "wake+wifi locks acquired");
        } catch (Throwable t) {
            Log.w(TAG, "failed to acquire locks", t);
        }
    }

    private void releaseLocks() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable t) {}
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Throwable t) {}
    }

    private void startInForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Bridge", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Xgimi Bridge")
                .setContentText("HTTP on port " + HTTP_PORT)
                .setOngoing(true)
                .build();
        } else {
            n = new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("Xgimi Bridge")
                .setContentText("HTTP on port " + HTTP_PORT)
                .setOngoing(true)
                .build();
        }
        startForeground(NOTIF_ID, n);
    }
}
