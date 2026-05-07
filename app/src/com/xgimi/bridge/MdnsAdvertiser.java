package com.xgimi.bridge;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

/** Advertise the bridge as xgimi.local + _http._tcp service via mDNS so iPhones can find it without IP. */
public class MdnsAdvertiser {
    private static final String TAG = "XgimiBridge";
    private static final String HOST = "xgimi";
    private static final String SERVICE_NAME = "Xgimi Bridge";
    private static final String SERVICE_TYPE = "_http._tcp.local.";

    private final Context ctx;
    private final int port;

    private WifiManager.MulticastLock multicastLock;
    private JmDNS jmdns;

    public MdnsAdvertiser(Context ctx, int port) {
        this.ctx = ctx;
        this.port = port;
    }

    public void start() {
        new Thread(new Runnable() { public void run() {
            try {
                acquireMulticastLock();
                InetAddress addr = pickIpv4();
                Log.i(TAG, "mDNS binding to " + addr.getHostAddress() + " as " + HOST + ".local");
                jmdns = JmDNS.create(addr, HOST);
                Map<String, String> txt = new HashMap<>();
                txt.put("path", "/");
                txt.put("type", "xgimi-bridge");
                ServiceInfo info = ServiceInfo.create(SERVICE_TYPE, SERVICE_NAME, port, 0, 0, txt);
                jmdns.registerService(info);
                Log.i(TAG, "mDNS registered: http://" + HOST + ".local:" + port + "/");
            } catch (Exception e) { Log.e(TAG, "mDNS start failed", e); }
        } }, "mdns-start").start();
    }

    public void stop() {
        new Thread(new Runnable() { public void run() {
            try {
                if (jmdns != null) { jmdns.unregisterAllServices(); jmdns.close(); jmdns = null; }
            } catch (Exception e) { Log.w(TAG, "mDNS stop", e); }
            try { if (multicastLock != null && multicastLock.isHeld()) multicastLock.release(); } catch (Exception e) {}
        } }, "mdns-stop").start();
    }

    private void acquireMulticastLock() {
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifi != null) {
            multicastLock = wifi.createMulticastLock("xgimi-bridge-mdns");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        }
    }

    private InetAddress pickIpv4() throws Exception {
        Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
        while (ifaces.hasMoreElements()) {
            NetworkInterface ni = ifaces.nextElement();
            if (ni.isLoopback() || !ni.isUp()) continue;
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(":") < 0) return a;
            }
        }
        return InetAddress.getLocalHost();
    }
}
