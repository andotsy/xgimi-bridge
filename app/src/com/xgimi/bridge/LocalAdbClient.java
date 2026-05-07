package com.xgimi.bridge;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * Pure-Java ADB client that talks to the projector's own adbd over loopback (127.0.0.1:5555).
 *
 * Why: shell uid has INJECT_EVENTS, untrusted_app does not. After a one-time on-screen Allow
 * prompt the bridge stays authorized and can run shell-uid commands at will.
 *
 * Two execution modes:
 *
 *   1. Persistent KeyDispatcher (used by injectKey) — opens one ADB shell stream that runs our
 *      {@link KeyDispatcher} class via {@code app_process}. The JVM cold-start (~200 ms) is
 *      paid once, then each keypress is just a stdin write + stdout read on an open stream:
 *      &lt;10 ms per key.
 *
 *   2. One-shot shell (used by runShell / authorize) — opens a fresh ADB connection, does the
 *      handshake, runs the command, closes. Slow (~300 ms) but rarely on the hot path.
 *
 * Wire format reference: AOSP {@code system/core/adb/protocol.txt}.
 */
public class LocalAdbClient {
    private static final String TAG = "XgimiBridge";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000001;
    private static final int MAX_PAYLOAD = 256 * 1024;

    private final Context ctx;
    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;

    /** Guards the keypair fields + on-disk key generation. Coarse but only contended on the
     *  very first call after a fresh install. */
    private final Object keyLock = new Object();

    /** Guards the persistent dispatcher socket + buffer. authorize() and isAuthorized() each
     *  open their own one-off sockets, so they do not contend on this lock — that's the whole
     *  point of separating it from a method-level synchronized: the PWA can poll status while
     *  authorize() is blocked waiting for the user to click Allow on the TV. */
    private final Object dispatcherLock = new Object();

    // Persistent connection + dispatcher stream.
    private Socket dispSock;
    private DataInputStream dispIn;
    private OutputStream dispOut;
    private int dispLocalId = -1;
    private int dispRemoteId = -1;
    /** Carry-over text from previous packets — adbd may bundle multiple {@code OK N\n} lines
     *  into one A_WRTE; without preserving the tail we'd return a stale OK on the next call. */
    private final StringBuilder dispBuf = new StringBuilder();

    public LocalAdbClient(Context ctx) {
        this.ctx = ctx;
    }

    /** Inject a key via the persistent KeyDispatcher. ~5 ms per call once warm. */
    public String injectKey(int keyCode) throws Exception {
        synchronized (dispatcherLock) {
            try {
                return injectKeyOnce(keyCode);
            } catch (Exception first) {
                Log.w(TAG, "Dispatcher fault (" + first.getMessage() + "), respawning");
                closeDispatcher();
                return injectKeyOnce(keyCode);
            }
        }
    }

    /** One-shot arbitrary shell command, on a fresh ADB connection. */
    public String runShell(String cmd) throws Exception {
        synchronized (keyLock) { ensureKey(); }
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setSoTimeout(30000);
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = s.getOutputStream();
            handshake(in, out);
            return openOneShotShell(in, out, cmd);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** Triggers the on-screen Allow prompt if needed; otherwise just verifies auth. */
    public String authorize() throws Exception {
        synchronized (keyLock) { ensureKey(); }
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setSoTimeout(30000);
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = s.getOutputStream();
            handshake(in, out, /*promptOnReject=*/true);
            return "authorized";
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Non-prompting probe: connect and offer our signature, but if adbd doesn't accept it
     * (key not in adb_keys yet) bail out without sending RSAPUBLICKEY. Returns true iff the
     * key is already authorized — i.e. we can call injectKey without surfacing a prompt.
     *
     * Deliberately does NOT lock {@link #dispatcherLock}: this is called by the PWA's polling
     * exactly while authorize() is blocked waiting for the user to click Allow on the TV.
     * Two parallel sockets to adbd is fine — adbd handles each as an independent connection.
     */
    public boolean isAuthorized() {
        try {
            synchronized (keyLock) { ensureKey(); }
        } catch (Exception e) {
            return false;
        }
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            s.setSoTimeout(2000);
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            OutputStream out = s.getOutputStream();
            handshake(in, out, /*promptOnReject=*/false);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) {}
        }
    }

    private String injectKeyOnce(int keyCode) throws Exception {
        ensureDispatcher();
        byte[] payload = (keyCode + "\n").getBytes("UTF-8");
        writePacket(dispOut, A_WRTE, dispLocalId, dispRemoteId, payload);
        // Be robust to dispatcher processes that survived a previous bridge run with
        // unread "OK N" lines still buffered in their stdout pipe — skip until we see
        // the response that actually matches what we just sent.
        String expected = "OK " + keyCode;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            String line = readDispatcherLine();
            if (expected.equals(line)) return line;
            if (line.startsWith("ERR ")) return line;
            Log.w(TAG, "Discarding stale dispatcher response '" + line + "' (expected '" + expected + "')");
        }
        throw new Exception("dispatcher response timeout (no matching '" + expected + "')");
    }

    private void ensureDispatcher() throws Exception {
        if (dispSock != null && !dispSock.isClosed() && dispLocalId > 0) return;

        ensureKey();
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 3000);
        s.setSoTimeout(30000);
        DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        OutputStream out = s.getOutputStream();
        handshake(in, out);

        // Run our KeyDispatcher class via app_process with this APK on the classpath.
        String apkPath = ctx.getPackageCodePath();
        String cmd = "CLASSPATH=" + apkPath + " exec app_process / com.xgimi.bridge.KeyDispatcher";

        int localId = 1;
        byte[] dest = ("shell:" + cmd + "\0").getBytes("UTF-8");
        writePacket(out, A_OPEN, localId, 0, dest);

        StringBuilder banner = new StringBuilder();
        long deadline = System.currentTimeMillis() + 10_000;
        int remoteId = -1;
        while (System.currentTimeMillis() < deadline) {
            Packet p = readPacket(in);
            if (p.cmd == A_OKAY && p.arg1 == localId) {
                remoteId = p.arg0;
            } else if (p.cmd == A_WRTE && p.arg1 == localId) {
                banner.append(new String(p.payload, "UTF-8"));
                writePacket(out, A_OKAY, localId, p.arg0, null);
                if (banner.indexOf("READY") >= 0 && remoteId > 0) {
                    dispSock = s;
                    dispIn = in;
                    dispOut = out;
                    dispLocalId = localId;
                    dispRemoteId = remoteId;
                    Log.i(TAG, "LocalAdbClient: KeyDispatcher READY (apk=" + apkPath + ")");
                    return;
                }
                if (banner.indexOf("FATAL") >= 0) {
                    try { s.close(); } catch (Exception ignored) {}
                    throw new Exception("dispatcher startup failed: " + banner);
                }
            } else if (p.cmd == A_CLSE) {
                try { s.close(); } catch (Exception ignored) {}
                throw new Exception("dispatcher stream closed before READY: " + banner);
            }
        }
        try { s.close(); } catch (Exception ignored) {}
        throw new Exception("dispatcher startup timeout: " + banner);
    }

    private String readDispatcherLine() throws Exception {
        // First, drain any line already pending in dispBuf from a previous packet.
        int nl = dispBuf.indexOf("\n");
        if (nl >= 0) {
            String line = dispBuf.substring(0, nl);
            dispBuf.delete(0, nl + 1);
            Log.d(TAG, "readDispatcherLine [pending]: '" + line + "' (rest=" + dispBuf.length() + ")");
            return line;
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            Packet p = readPacket(dispIn);
            String cn = p.cmd == A_WRTE ? "WRTE" : p.cmd == A_OKAY ? "OKAY" : p.cmd == A_CLSE ? "CLSE" : String.format("%08x", p.cmd);
            String preview = p.payload.length == 0 ? "" : new String(p.payload, "UTF-8").replace("\n", "\\n");
            Log.d(TAG, "readDispatcherLine recv: " + cn + " arg0=" + p.arg0 + " arg1=" + p.arg1 + " len=" + p.payload.length + " '" + preview + "'");
            if (p.cmd == A_WRTE && p.arg1 == dispLocalId) {
                dispBuf.append(new String(p.payload, "UTF-8"));
                writePacket(dispOut, A_OKAY, dispLocalId, p.arg0, null);
                nl = dispBuf.indexOf("\n");
                if (nl >= 0) {
                    String line = dispBuf.substring(0, nl);
                    dispBuf.delete(0, nl + 1);
                    Log.d(TAG, "readDispatcherLine [new]: '" + line + "' (rest=" + dispBuf.length() + ")");
                    return line;
                }
            } else if (p.cmd == A_OKAY) {
                // peer ack of our previous A_WRTE — flow control, ignore
            } else if (p.cmd == A_CLSE) {
                closeDispatcher();
                throw new Exception("dispatcher closed mid-call: " + dispBuf);
            }
        }
        throw new Exception("dispatcher response timeout");
    }

    private void closeDispatcher() {
        if (dispSock != null) try { dispSock.close(); } catch (Exception ignored) {}
        dispSock = null;
        dispIn = null;
        dispOut = null;
        dispLocalId = -1;
        dispRemoteId = -1;
        dispBuf.setLength(0);
    }

    // ---- Key management ----

    private void ensureKey() throws Exception {
        if (privateKey != null && publicKey != null) return;
        File priv = new File(ctx.getFilesDir(), "adb_key.pkcs8");
        File pub = new File(ctx.getFilesDir(), "adb_key.x509");
        if (priv.exists() && pub.exists()) {
            byte[] pkBytes = readFile(priv);
            byte[] pubBytes = readFile(pub);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privateKey = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(pkBytes));
            publicKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(pubBytes));
            return;
        }
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        privateKey = (RSAPrivateKey) kp.getPrivate();
        publicKey = (RSAPublicKey) kp.getPublic();
        writeFile(priv, privateKey.getEncoded());
        writeFile(pub, publicKey.getEncoded());
        Log.i(TAG, "LocalAdbClient: generated new RSA-2048 ADB key");
    }

    // ---- Wire ----

    private static class Packet {
        int cmd, arg0, arg1;
        byte[] payload;
    }

    private void writePacket(OutputStream out, int cmd, int arg0, int arg1, byte[] payload) throws Exception {
        int len = (payload == null) ? 0 : payload.length;
        int crc = 0;
        if (payload != null) for (byte b : payload) crc += (b & 0xff);
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd);
        h.putInt(arg0);
        h.putInt(arg1);
        h.putInt(len);
        h.putInt(crc);
        h.putInt(cmd ^ 0xffffffff);
        out.write(h.array());
        if (len > 0) out.write(payload);
        out.flush();
    }

    private Packet readPacket(DataInputStream in) throws Exception {
        byte[] hdr = new byte[24];
        in.readFully(hdr);
        ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
        Packet p = new Packet();
        p.cmd = b.getInt();
        p.arg0 = b.getInt();
        p.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // crc
        b.getInt(); // magic
        p.payload = new byte[len];
        if (len > 0) in.readFully(p.payload);
        return p;
    }

    private void handshake(DataInputStream in, OutputStream out) throws Exception {
        handshake(in, out, true);
    }

    private void handshake(DataInputStream in, OutputStream out, boolean promptOnReject) throws Exception {
        byte[] banner = "host::features=cmd,shell_v2\0".getBytes("UTF-8");
        writePacket(out, A_CNXN, VERSION, MAX_PAYLOAD, banner);

        Packet p = readPacket(in);
        if (p.cmd == A_CNXN) return;
        if (p.cmd != A_AUTH || p.arg0 != AUTH_TOKEN) {
            throw new Exception("unexpected response to CNXN: cmd=0x" + Integer.toHexString(p.cmd));
        }

        byte[] sig = signSha1Pkcs1(p.payload);
        writePacket(out, A_AUTH, AUTH_SIGNATURE, 0, sig);

        Packet p2 = readPacket(in);
        if (p2.cmd == A_CNXN) return;

        if (p2.cmd != A_AUTH || p2.arg0 != AUTH_TOKEN) {
            throw new Exception("unexpected after signature: cmd=0x" + Integer.toHexString(p2.cmd));
        }
        if (!promptOnReject) {
            // Caller is just probing auth — bail out without surfacing an Allow dialog to the
            // user. From their perspective the bridge stays silent until they explicitly ask.
            throw new Exception("not authorized");
        }
        Log.i(TAG, "LocalAdbClient: signature rejected → sending RSAPUBLICKEY (Allow prompt should appear)");
        byte[] pub = encodeAdbPublicKey(publicKey);
        writePacket(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, pub);

        Packet p3 = readPacket(in);
        if (p3.cmd != A_CNXN) {
            throw new Exception("Allow prompt rejected or timed out (cmd=0x" + Integer.toHexString(p3.cmd) + ")");
        }
    }

    private String openOneShotShell(DataInputStream in, OutputStream out, String cmd) throws Exception {
        int localId = 1;
        byte[] dest = ("shell:" + cmd + "\0").getBytes("UTF-8");
        writePacket(out, A_OPEN, localId, 0, dest);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        int remoteId = -1;
        while (true) {
            Packet p = readPacket(in);
            if (p.cmd == A_OKAY) {
                remoteId = p.arg0;
            } else if (p.cmd == A_WRTE) {
                stdout.write(p.payload);
                writePacket(out, A_OKAY, localId, p.arg0, null);
            } else if (p.cmd == A_CLSE) {
                if (remoteId >= 0) writePacket(out, A_CLSE, localId, p.arg0, null);
                break;
            } else {
                throw new Exception("unexpected stream packet: 0x" + Integer.toHexString(p.cmd));
            }
        }
        return stdout.toString("UTF-8");
    }

    // ---- Crypto helpers ----

    /** Sign the 20-byte AUTH token with PKCS#1 v1.5; the token is treated as a SHA-1 digest. */
    private byte[] signSha1Pkcs1(byte[] token) throws Exception {
        byte[] sha1DigestInfoPrefix = {
                (byte) 0x30, (byte) 0x21, (byte) 0x30, (byte) 0x09, (byte) 0x06, (byte) 0x05,
                (byte) 0x2b, (byte) 0x0e, (byte) 0x03, (byte) 0x02, (byte) 0x1a, (byte) 0x05,
                (byte) 0x00, (byte) 0x04, (byte) 0x14
        };
        byte[] message = new byte[sha1DigestInfoPrefix.length + token.length];
        System.arraycopy(sha1DigestInfoPrefix, 0, message, 0, sha1DigestInfoPrefix.length);
        System.arraycopy(token, 0, message, sha1DigestInfoPrefix.length, token.length);

        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, privateKey);
        return c.doFinal(message);
    }

    private static byte[] encodeAdbPublicKey(RSAPublicKey key) throws Exception {
        BigInteger n = key.getModulus();
        BigInteger e = key.getPublicExponent();
        if (n.bitLength() > 2048) throw new Exception("only 2048-bit RSA keys supported");

        int wordCount = 2048 / 32;
        int byteLen = 4 + 4 + wordCount * 4 + wordCount * 4 + 4;
        ByteBuffer buf = ByteBuffer.allocate(byteLen).order(ByteOrder.LITTLE_ENDIAN);

        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = n.mod(r32);
        BigInteger n0inv = n0.modInverse(r32).negate().mod(r32);

        BigInteger r = BigInteger.ONE.shiftLeft(2048 + 32);
        BigInteger rr = r.multiply(r).mod(n);

        buf.putInt(wordCount);
        buf.putInt(n0inv.intValue());
        writeLittleEndianWords(buf, n, wordCount);
        writeLittleEndianWords(buf, rr, wordCount);
        buf.putInt(e.intValue());

        String b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP);
        String wire = b64 + " unknown@xgimi-bridge\0";
        return wire.getBytes("UTF-8");
    }

    private static void writeLittleEndianWords(ByteBuffer buf, BigInteger value, int wordCount) {
        byte[] be = value.toByteArray();
        int byteLen = wordCount * 4;
        byte[] le = new byte[byteLen];
        for (int i = 0; i < be.length && i < byteLen; i++) {
            le[i] = be[be.length - 1 - i];
        }
        buf.put(le);
    }

    // ---- File helpers ----

    private static byte[] readFile(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }
}
