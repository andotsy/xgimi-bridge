package com.xgimi.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Raw-Binder client for com.xgimi.xgimiservice. Verified working against IGimiVideo
 * on this projector firmware. No AIDL generation needed.
 */
public class AidlClient {
    private static final String TAG = "XgimiBridge";
    private static final String DESC_SVC = "com.xgimi.aidl.IXgimiService";
    private static final String DESC_VIDEO = "com.xgimi.aidl.IGimiVideo";
    private static final String DESC_COMMON = "com.xgimi.aidl.IGimiCommon";
    private static final String DESC_PROJECTOR = "com.xgimi.aidl.IGimiProjector";
    private static final String BIND_ACTION = "com.xgimi.xgimiservice.services.XgimiServices";
    private static final String BIND_PKG = "com.xgimi.xgimiservice";

    // IXgimiService broker transactions
    static final int TX_GET_GIMI_COMMON = 0x1;
    static final int TX_GET_GIMI_PROJECTOR = 0x4;
    static final int TX_GET_GIMI_VIDEO = 0x8;

    // IGimiCommon transactions
    static final int TX_COMMON_SET_SYSTEM_PROPERTIES = 0x12;

    // IGimiProjector transactions
    static final int TX_PROJECTOR_SET_LED_ON = 0x2;

    // IGimiVideo
    static final int TX_GET_PICTURE_MODE = 0x1;
    static final int TX_SET_PICTURE_MODE = 0x2;
    static final int TX_GET_COLOR_TEMP = 0x3;
    static final int TX_SET_COLOR_TEMP = 0x4;
    static final int TX_SET_WB_RED_GAIN = 0x5;
    static final int TX_SET_WB_GREEN_GAIN = 0x6;
    static final int TX_SET_WB_BLUE_GAIN = 0x7;
    static final int TX_GET_WB_RED_GAIN = 0x8;
    static final int TX_GET_WB_GREEN_GAIN = 0x9;
    static final int TX_GET_WB_BLUE_GAIN = 0xa;
    static final int TX_RESET_PICTURE_MODE = 0x13;
    static final int TX_SWITCH_TO_SIGNAL_SOURCE = 0x20;
    static final int TX_SET_GAME_MODE_OPTION = 0x24;
    static final int TX_GET_GAME_MODE_OPTION = 0x25;
    static final int TX_SET_GAME_MODE_OPTION_FOR_SOURCE = 0x26;
    static final int TX_GET_GAME_MODE_OPTION_FOR_SOURCE = 0x27;
    static final int TX_GET_CURRENT_INPUT_SOURCE = 0x1e;
    static final int TX_GET_PICTURE_ITEM = 0xd;
    static final int TX_SET_PICTURE_ITEM = 0xe;

    private final Context ctx;
    private final AtomicReference<IBinder> svcBinder = new AtomicReference<>();
    private final AtomicReference<IBinder> videoBinder = new AtomicReference<>();
    private final AtomicReference<IBinder> commonBinder = new AtomicReference<>();
    private final AtomicReference<IBinder> projectorBinder = new AtomicReference<>();
    private boolean connected = false;

    public AidlClient(Context ctx) { this.ctx = ctx; }

    public void connect() {
        Intent i = new Intent(BIND_ACTION).setPackage(BIND_PKG);
        boolean ok = ctx.bindService(i, conn, Context.BIND_AUTO_CREATE);
        Log.i(TAG, "AidlClient.bindService -> " + ok);
    }

    public boolean isReady() { return connected && videoBinder.get() != null; }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "AidlClient connected: " + name);
            svcBinder.set(binder);
            try {
                IBinder video = getSubBinder(binder, TX_GET_GIMI_VIDEO);
                videoBinder.set(video);
                connected = true;
                Log.i(TAG, "AidlClient: video binder = " + video);
            } catch (Throwable t) {
                Log.e(TAG, "could not get video binder", t);
            }
            try {
                IBinder common = getSubBinder(binder, TX_GET_GIMI_COMMON);
                commonBinder.set(common);
                Log.i(TAG, "AidlClient: common binder = " + common);
            } catch (Throwable t) {
                Log.w(TAG, "could not get common binder", t);
            }
            try {
                IBinder projector = getSubBinder(binder, TX_GET_GIMI_PROJECTOR);
                projectorBinder.set(projector);
                Log.i(TAG, "AidlClient: projector binder = " + projector);
            } catch (Throwable t) {
                Log.w(TAG, "could not get projector binder", t);
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "AidlClient disconnected: " + name);
            connected = false;
            svcBinder.set(null);
            videoBinder.set(null);
            commonBinder.set(null);
            projectorBinder.set(null);
        }
    };

    private IBinder getSubBinder(IBinder svc, int code) throws RemoteException {
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_SVC);
            svc.transact(code, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Turn the projector lamp on/off via IGimiProjector.setLedOn(boolean) — Android keeps running. */
    public void setLedOn(boolean on) throws RemoteException {
        IBinder b = projectorBinder.get();
        if (b == null) throw new RemoteException("projector binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_PROJECTOR);
            data.writeInt(on ? 1 : 0);
            b.transact(TX_PROJECTOR_SET_LED_ON, data, reply, 0);
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Set a system property via the system-uid xgimiservice — used for sys.powerctl etc. */
    public void setSystemProperty(String key, String value) throws RemoteException {
        IBinder b = commonBinder.get();
        if (b == null) throw new RemoteException("common binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_COMMON);
            data.writeString(key);
            data.writeString(value);
            b.transact(TX_COMMON_SET_SYSTEM_PROPERTIES, data, reply, 0);
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    // ---------- video calls ----------

    public int getInt(int code) throws RemoteException {
        IBinder b = videoBinder.get();
        if (b == null) throw new RemoteException("video binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_VIDEO);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }


    public String getString(int code) throws RemoteException {
        IBinder b = videoBinder.get();
        if (b == null) throw new RemoteException("video binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_VIDEO);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.readString();
        } finally { data.recycle(); reply.recycle(); }
    }

    public int getIntWithIntArg(int code, int arg) throws RemoteException {
        IBinder b = videoBinder.get();
        if (b == null) throw new RemoteException("video binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_VIDEO);
            data.writeInt(arg);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }

    public boolean setInt(int code, int value) throws RemoteException {
        IBinder b = videoBinder.get();
        if (b == null) throw new RemoteException("video binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_VIDEO);
            data.writeInt(value);
            b.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally { data.recycle(); reply.recycle(); }
    }

    public boolean setIntInt(int code, int a, int b) throws RemoteException {
        IBinder vb = videoBinder.get();
        if (vb == null) throw new RemoteException("video binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_VIDEO);
            data.writeInt(a);
            data.writeInt(b);
            vb.transact(code, data, reply, 0);
            reply.readException();
            return reply.readInt() != 0;
        } finally { data.recycle(); reply.recycle(); }
    }

    public void resetPictureMode(int mode, boolean force) throws RemoteException {
        IBinder b = videoBinder.get();
        if (b == null) throw new RemoteException("video binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_VIDEO);
            data.writeInt(mode);
            data.writeInt(force ? 1 : 0);
            b.transact(TX_RESET_PICTURE_MODE, data, reply, 0);
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    public void switchToSignalSource(String s1, String s2) throws RemoteException {
        IBinder b = videoBinder.get();
        if (b == null) throw new RemoteException("video binder not ready");
        Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESC_VIDEO);
            data.writeString(s1);
            data.writeString(s2);
            b.transact(TX_SWITCH_TO_SIGNAL_SOURCE, data, reply, 0);
            reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }

    /** Snapshot of all interesting picture state. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        try { m.put("pictureMode", getInt(TX_GET_PICTURE_MODE)); } catch (Throwable t) { m.put("pictureMode", null); }
        try { m.put("colorTemp", getInt(TX_GET_COLOR_TEMP)); } catch (Throwable t) { m.put("colorTemp", null); }
        try { m.put("wbRed", getInt(TX_GET_WB_RED_GAIN)); } catch (Throwable t) { m.put("wbRed", null); }
        try { m.put("wbGreen", getInt(TX_GET_WB_GREEN_GAIN)); } catch (Throwable t) { m.put("wbGreen", null); }
        try { m.put("wbBlue", getInt(TX_GET_WB_BLUE_GAIN)); } catch (Throwable t) { m.put("wbBlue", null); }
        try { m.put("gameMode", getInt(TX_GET_GAME_MODE_OPTION)); } catch (Throwable t) { m.put("gameMode", null); }

        // Per-source detail
        String src = null; int srcId = -1;
        try { src = getString(TX_GET_CURRENT_INPUT_SOURCE); } catch (Throwable t) {}
        m.put("currentInputSource", src);
        if (src != null) {
            if (src.equals("E_INPUT_SOURCE_STORAGE")) srcId = 0;
            else if (src.startsWith("E_INPUT_SOURCE_HDMI")) {
                try { srcId = Integer.parseInt(src.substring("E_INPUT_SOURCE_HDMI".length())); } catch (Throwable t) {}
            }
        }
        m.put("currentSourceId", srcId);
        try {
            if (srcId >= 0) m.put("gameModeCurrent", getIntWithIntArg(TX_GET_GAME_MODE_OPTION_FOR_SOURCE, srcId));
            else m.put("gameModeCurrent", null);
        } catch (Throwable t) { m.put("gameModeCurrent", null); }

        // Bonus: dump per-source game mode for sources 0..3 (storage + 3 HDMI)
        java.util.LinkedHashMap<String,Object> per = new java.util.LinkedHashMap<>();
        for (int i = 0; i <= 3; i++) {
            try { per.put(Integer.toString(i), getIntWithIntArg(TX_GET_GAME_MODE_OPTION_FOR_SOURCE, i)); }
            catch (Throwable t) { per.put(Integer.toString(i), null); }
        }
        m.put("gameModePerSource", per);

        // Picture items: 0=brightness, 1=contrast, 2=saturation, 3=sharpness, 4=hue
        try { m.put("brightness", getIntWithIntArg(TX_GET_PICTURE_ITEM, 0)); } catch (Throwable t) { m.put("brightness", null); }
        try { m.put("contrast",   getIntWithIntArg(TX_GET_PICTURE_ITEM, 1)); } catch (Throwable t) { m.put("contrast", null); }
        try { m.put("saturation", getIntWithIntArg(TX_GET_PICTURE_ITEM, 2)); } catch (Throwable t) { m.put("saturation", null); }
        try { m.put("sharpness",  getIntWithIntArg(TX_GET_PICTURE_ITEM, 3)); } catch (Throwable t) { m.put("sharpness", null); }
        try { m.put("hue",        getIntWithIntArg(TX_GET_PICTURE_ITEM, 4)); } catch (Throwable t) { m.put("hue", null); }
        return m;
    }
}
