package com.xgimi.bridge;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/**
 * Advertise the bridge as xgimi.local + _http._tcp via mDNS.
 *
 * The boot-time path is unreliable: BridgeService comes up before Wi-Fi has finished associating,
 * so jmdns either binds to a link-local 169.254 address that no one queries, or fails outright.
 * Re-register if the active LAN address changes, and retry on every failure until we get a
 * non-link-local IPv4.
 */
public class MdnsAdvertiser {
    private static final String TAG = "XgimiBridge";
    private static final String HOST = "xgimi";
    private static final String SERVICE_NAME = "Xgimi Bridge";
    private static final String SERVICE_TYPE = "_http._tcp.local.";

    private static final long RETRY_INTERVAL_MS = 2_000L;
    private static final long WATCHDOG_INTERVAL_MS = 60_000L;

    private final Context ctx;
    private final int port;

    private WifiManager.MulticastLock multicastLock;
    private volatile JmDNS jmdns;
    private volatile InetAddress boundAddr;
    private volatile boolean stopped;
    private HandlerThread workerThread;
    private Handler worker;

    public MdnsAdvertiser(Context ctx, int port) {
        this.ctx = ctx;
        this.port = port;
    }

    private final Runnable registerTask = new Runnable() {
        @Override public void run() { registerOrRetry(); }
    };

    private final Runnable watchdogTask = new Runnable() {
        @Override public void run() { watchdog(); }
    };

    private final Runnable tearDownTask = new Runnable() {
        @Override public void run() { tearDown(); }
    };

    public void start() {
        stopped = false;
        workerThread = new HandlerThread("mdns-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        worker.post(registerTask);
    }

    public void stop() {
        stopped = true;
        if (worker != null) worker.post(tearDownTask);
        if (workerThread != null) workerThread.quitSafely();
    }

    private void registerOrRetry() {
        if (stopped) return;
        try {
            InetAddress addr = pickUsableIpv4();
            if (addr == null) {
                Log.i(TAG, "mDNS waiting for network...");
                worker.postDelayed(registerTask, RETRY_INTERVAL_MS);
                return;
            }
            acquireMulticastLock();
            Log.i(TAG, "mDNS binding to " + addr.getHostAddress() + " as " + HOST + ".local");
            JmDNS j = JmDNS.create(addr, HOST);
            Map<String, String> txt = new HashMap<>();
            txt.put("path", "/");
            txt.put("type", "xgimi-bridge");
            ServiceInfo info = ServiceInfo.create(SERVICE_TYPE, SERVICE_NAME, port, 0, 0, txt);
            j.registerService(info);
            jmdns = j;
            boundAddr = addr;
            Log.i(TAG, "mDNS registered: http://" + HOST + ".local:" + port + "/");
            worker.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
        } catch (Exception e) {
            Log.e(TAG, "mDNS start failed; retrying", e);
            worker.postDelayed(registerTask, RETRY_INTERVAL_MS);
        }
    }

    private void watchdog() {
        if (stopped) return;
        try {
            InetAddress now = pickUsableIpv4();
            boolean addrChanged = now != null && boundAddr != null
                    && !now.getHostAddress().equals(boundAddr.getHostAddress());
            if (now == null || jmdns == null || addrChanged) {
                Log.i(TAG, "mDNS watchdog: re-registering (addr=" + (now == null ? "null" : now.getHostAddress())
                        + " bound=" + (boundAddr == null ? "null" : boundAddr.getHostAddress()) + ")");
                tearDown();
                registerOrRetry();
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "mDNS watchdog error", e);
        }
        worker.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
    }

    private void tearDown() {
        try {
            if (jmdns != null) {
                jmdns.unregisterAllServices();
                jmdns.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "mDNS teardown", e);
        } finally {
            jmdns = null;
            boundAddr = null;
        }
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    private void acquireMulticastLock() {
        if (multicastLock != null && multicastLock.isHeld()) return;
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("xgimi-bridge-mdns");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }
    }

    private InetAddress pickUsableIpv4() throws Exception {
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces.hasMoreElements()) {
            NetworkInterface ni = ifaces.nextElement();
            if (ni.isLoopback() || !ni.isUp()) continue;
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (!(a instanceof Inet4Address)) continue;
                if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) continue;
                return a;
            }
        }
        return null;
    }
}
