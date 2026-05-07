package com.xgimi.bridge;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {
    private static final String TAG = "XgimiBridge";
    private final Context ctx;
    private final AidlClient aidl;
    private final PresetStore presets;
    private final LocalAdbClient adb;

    public HttpServer(Context ctx, int port, AidlClient aidl, PresetStore presets) {
        super(port);
        this.ctx = ctx;
        this.aidl = aidl;
        this.presets = presets;
        this.adb = new LocalAdbClient(ctx);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Log.i(TAG, session.getMethod() + " " + uri);

        Response r;
        try {
            if (uri.startsWith("/api/")) {
                r = handleApi(session, uri);
            } else {
                r = serveAsset(uri);
            }
        } catch (Throwable t) {
            Log.e(TAG, "handler crash", t);
            r = json(Response.Status.INTERNAL_ERROR, errJson(t.getMessage()));
        }
        r.addHeader("Access-Control-Allow-Origin", "*");
        r.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");
        return r;
    }

    private Response handleApi(IHTTPSession session, String uri) throws IOException {
        Method m = session.getMethod();
        if (m == Method.OPTIONS) return newFixedLengthResponse(Response.Status.NO_CONTENT, "text/plain", "");

        if (uri.equals("/api/state") && m == Method.GET) {
            JSONObject o = new JSONObject();
            try { o.put("ready", aidl.isReady()).put("port", 8080).put("ts", System.currentTimeMillis()); } catch (JSONException e) {}
            return json(Response.Status.OK, o.toString());
        }
        if (uri.equals("/api/inputs") && m == Method.GET) {
            try {
                android.media.tv.TvInputManager tim = (android.media.tv.TvInputManager)
                    ctx.getSystemService(android.content.Context.TV_INPUT_SERVICE);
                JSONArray arr = new JSONArray();
                if (tim != null) {
                    for (android.media.tv.TvInputInfo info : tim.getTvInputList()) {
                        JSONObject o = new JSONObject();
                        try {
                            o.put("id", info.getId());
                            o.put("type", info.getType());
                            CharSequence label = info.loadLabel(ctx);
                            o.put("label", label != null ? label.toString() : null);
                            CharSequence custom = info.loadCustomLabel(ctx);
                            o.put("customLabel", custom != null ? custom.toString() : null);
                            // Reflectively access HdmiDeviceInfo (hidden API on Android 10)
                            try {
                                java.lang.reflect.Method m1 = info.getClass().getMethod("getHdmiDeviceInfo");
                                Object hdi = m1.invoke(info);
                                if (hdi != null) {
                                    java.lang.reflect.Method getDisplayName = hdi.getClass().getMethod("getDisplayName");
                                    Object dn = getDisplayName.invoke(hdi);
                                    if (dn != null) o.put("displayName", dn.toString());
                                    java.lang.reflect.Method getPortId = hdi.getClass().getMethod("getPortId");
                                    Object pid = getPortId.invoke(hdi);
                                    if (pid != null) o.put("hdmiPortId", ((Integer) pid).intValue());
                                }
                            } catch (Throwable ignore) {}
                        } catch (JSONException ignore) {}
                        arr.put(o);
                    }
                }
                return json(Response.Status.OK, arr.toString());
            } catch (Throwable t) {
                Log.e(TAG, "inputs enum failed", t);
                return json(Response.Status.INTERNAL_ERROR, errJson(t.getMessage()));
            }
        }
        if (uri.equals("/api/picture") && m == Method.GET) {
            java.util.Map<String,Object> snap = aidl.snapshot();
            // wrap nested maps as JSONObjects so JSONObject does not toString() them
            for (java.util.Map.Entry<String,Object> e : snap.entrySet()) {
                if (e.getValue() instanceof java.util.Map) e.setValue(new JSONObject((java.util.Map) e.getValue()));
            }
            return json(Response.Status.OK, new JSONObject(snap).toString());
        }

        if (m == Method.POST && uri.startsWith("/api/picture/")) return handlePictureSet(session, uri);
        if (m == Method.POST && uri.startsWith("/api/input")) return handleInputSwitch(session, uri);
        if (uri.startsWith("/api/presets")) return handlePresets(session, uri);
        if (m == Method.POST && uri.startsWith("/api/power/")) return handlePower(uri);
        if (uri.startsWith("/api/adb/")) return handleAdb(uri, m);

        return json(Response.Status.NOT_FOUND, errJson("no such endpoint"));
    }

    /**
     * ADB-loopback shell-uid bridge. Replaces the broken-by-design same-host TLS path to
     * Google's tv.remote.service. Lifecycle:
     *
     *   POST /api/adb/authorize         — first-run only. Triggers the on-screen "Allow USB
     *                                     debugging from this computer?" prompt. User clicks
     *                                     Allow + Always with the physical remote, once.
     *   POST /api/adb/keyevent/<n>      — runs `input keyevent N` as shell uid (works because
     *                                     shell has INJECT_EVENTS).
     */
    private Response handleAdb(String uri, Method m) {
        try {
            if (uri.equals("/api/adb/status") && m == Method.GET) {
                boolean ok = adb.isAuthorized();
                return json(Response.Status.OK, "{\"authorized\":" + ok + "}");
            }
            if (uri.equals("/api/adb/authorize") && m == Method.POST) {
                String r = adb.authorize();
                return json(Response.Status.OK, "{\"ok\":true,\"state\":\"" + r + "\"}");
            }
            if (uri.startsWith("/api/adb/keyevent/") && m == Method.POST) {
                int code = Integer.parseInt(uri.substring("/api/adb/keyevent/".length()));
                String out = adb.injectKey(code);
                return json(Response.Status.OK, "{\"ok\":true,\"key\":" + code + ",\"out\":" + jsonString(out) + "}");
            }
        } catch (Exception e) {
            Log.e(TAG, "adb endpoint " + uri + " failed", e);
            return json(Response.Status.INTERNAL_ERROR, errJson("adb: " + e.getMessage()));
        }
        return json(Response.Status.NOT_FOUND, errJson("no such adb endpoint"));
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 32) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * One power action — restart — that doesn't have a keyevent equivalent. Display off / on
     * is now handled via the regular keyevent path (KEYCODE_XGIMI_POWER = 2100).
     *
     *   restart — sys.powerctl=reboot via the system-uid AIDL setSystemProperties broker call.
     */
    private Response handlePower(String uri) {
        String action = uri.substring("/api/power/".length());
        try {
            switch (action) {
                case "restart":
                    aidl.setSystemProperty("sys.powerctl", "reboot");
                    return json(Response.Status.OK, "{\"ok\":true,\"action\":\"restart\"}");
                default:
                    return json(Response.Status.NOT_FOUND, errJson("unknown power action: " + action));
            }
        } catch (Throwable t) {
            Log.e("XgimiBridge", "power " + action + " failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson("power " + action + " failed: " + t.getMessage()));
        }
    }

    private Response handlePictureSet(IHTTPSession session, String uri) throws IOException {
        JSONObject body = readJsonBody(session);
        String knob = uri.substring("/api/picture/".length());
        try {
            boolean ok;
            int value = body.optInt("value", Integer.MIN_VALUE);
            switch (knob) {
                case "wb-red":      ok = aidl.setInt(AidlClient.TX_SET_WB_RED_GAIN, value); break;
                case "wb-green":    ok = aidl.setInt(AidlClient.TX_SET_WB_GREEN_GAIN, value); break;
                case "wb-blue":     ok = aidl.setInt(AidlClient.TX_SET_WB_BLUE_GAIN, value); break;
                case "color-temp":  ok = aidl.setInt(AidlClient.TX_SET_COLOR_TEMP, value); break;
                case "picture-mode": ok = aidl.setInt(AidlClient.TX_SET_PICTURE_MODE, value); break;
                case "game-mode":   ok = aidl.setInt(AidlClient.TX_SET_GAME_MODE_OPTION, value); break;
                case "brightness":  ok = aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, 0, value); break;
                case "contrast":    ok = aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, 1, value); break;
                case "saturation":  ok = aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, 2, value); break;
                case "sharpness":   ok = aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, 3, value); break;
                case "hue":         ok = aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, 4, value); break;
                case "reset":
                    // Explicit reset: write 50/50/50 with verify (resetPictureMode alone is unreliable for WB)
                    aidl.setInt(AidlClient.TX_SET_WB_RED_GAIN, 50);
                    Thread.sleep(60);
                    aidl.setInt(AidlClient.TX_SET_WB_GREEN_GAIN, 50);
                    Thread.sleep(60);
                    aidl.setInt(AidlClient.TX_SET_WB_BLUE_GAIN, 50);
                    Thread.sleep(60);
                    aidl.resetPictureMode(body.optInt("mode", 0), body.optBoolean("force", false));
                    ok = true; break;
                default:
                    return json(Response.Status.NOT_FOUND, errJson("unknown knob: " + knob));
            }
            JSONObject reply = new JSONObject();
            try { reply.put("ok", ok).put("knob", knob).put("value", value); } catch (JSONException e) {}
            return json(Response.Status.OK, reply.toString());
        } catch (RemoteException re) {
            return json(Response.Status.INTERNAL_ERROR, errJson("remote: " + re.getMessage()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return json(Response.Status.INTERNAL_ERROR, errJson("interrupted"));
        }
    }


    private Response handleInputSwitch(IHTTPSession session, String uri) throws IOException {
        JSONObject body = readJsonBody(session);
        String to = body.optString("to", "").toUpperCase();

        // /api/input/picker or empty body -> show picker
        if (uri.equals("/api/input/picker") || to.isEmpty()) {
            try {
                android.content.Intent it = new android.content.Intent();
                it.setComponent(new android.content.ComponentName(
                    "com.google.android.tvlauncher",
                    "com.google.android.tvlauncher.inputs.InputsPanelActivity"));
                it.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(it);
                return json(Response.Status.OK, "{\"ok\":true,\"opened\":\"picker\"}");
            } catch (Throwable t) {
                Log.e(TAG, "picker launch failed", t);
                return json(Response.Status.INTERNAL_ERROR, errJson(t.getMessage()));
            }
        }

        try {
            if (to.equals("STORAGE") || to.equals("HOME") || to.equals("APPS")) {
                // Kill the HDMI playback activity (otherwise its TvView keeps the pipeline)
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE);
                    am.killBackgroundProcesses("com.xgimi.xhplayer");
                } catch (Throwable ignore) {}
                // Bring launcher MainActivity to front
                android.content.Intent home = new android.content.Intent();
                home.setComponent(new android.content.ComponentName(
                    "com.google.android.tvlauncher",
                    "com.google.android.tvlauncher.MainActivity"));
                home.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_TASK_ON_HOME);
                ctx.startActivity(home);
                return json(Response.Status.OK, "{\"ok\":true,\"to\":\"STORAGE\"}");
            }
            if (!to.matches("HDMI[1-3]")) {
                return json(Response.Status.BAD_REQUEST, errJson("invalid to: " + to));
            }
            android.content.Intent it = new android.content.Intent("com.xgimi.action.hdmiPlayer");
            it.setComponent(new android.content.ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity"));
            it.putExtra("sw", to);
            it.setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            ctx.startActivity(it);
            return json(Response.Status.OK, "{\"ok\":true,\"to\":\"" + to + "\"}");
        } catch (Throwable t) {
            Log.e(TAG, "input switch failed", t);
            return json(Response.Status.INTERNAL_ERROR, errJson(t.getMessage()));
        }
    }

    private Response handlePresets(IHTTPSession session, String uri) throws IOException {
        Method m = session.getMethod();
        // Routes:
        //   GET    /api/presets
        //   POST   /api/presets                    (create)
        //   PUT    /api/presets/{id}               (rename / replace values)
        //   POST   /api/presets/{id}/sync          (capture current as new values)
        //   POST   /api/presets/{id}/apply         (apply to projector)
        //   DELETE /api/presets/{id}
        try {
            if (uri.equals("/api/presets") && m == Method.GET) {
                return json(Response.Status.OK, presets.load().toString());
            }
            if (uri.equals("/api/presets") && m == Method.POST) {
                JSONObject body = readJsonBody(session);
                JSONObject created = presets.create(body);
                return json(Response.Status.OK, created.toString());
            }
            String rest = uri.substring("/api/presets/".length());
            String id, sub = "";
            int slash = rest.indexOf('/');
            if (slash < 0) { id = rest; } else { id = rest.substring(0, slash); sub = rest.substring(slash + 1); }
            if (id.isEmpty()) return json(Response.Status.NOT_FOUND, errJson("preset id missing"));

            if (sub.isEmpty() && m == Method.PUT) {
                JSONObject body = readJsonBody(session);
                JSONObject p = presets.update(id, body);
                if (p == null) return json(Response.Status.NOT_FOUND, errJson("preset not found"));
                return json(Response.Status.OK, p.toString());
            }
            if (sub.isEmpty() && m == Method.DELETE) {
                try {
                    boolean ok = presets.delete(id);
                    JSONObject r = new JSONObject();
                    r.put("ok", ok);
                    return json(Response.Status.OK, r.toString());
                } catch (PresetStore.BuiltinException be) {
                    return json(Response.Status.FORBIDDEN, errJson(be.getMessage()));
                }
            }
            if (sub.equals("sync") && m == Method.POST) {
                JSONObject p = presets.syncFromCurrent(id);
                if (p == null) return json(Response.Status.NOT_FOUND, errJson("preset not found"));
                return json(Response.Status.OK, p.toString());
            }
            if (sub.equals("apply") && m == Method.POST) {
                JSONObject reply = presets.apply(id);
                if (reply == null) return json(Response.Status.NOT_FOUND, errJson("preset not found"));
                return json(Response.Status.OK, reply.toString());
            }
            return json(Response.Status.METHOD_NOT_ALLOWED, errJson("method not supported on " + uri));
        } catch (JSONException | RemoteException e) {
            return json(Response.Status.INTERNAL_ERROR, errJson(e.getMessage()));
        }
    }

    private JSONObject readJsonBody(IHTTPSession session) {
        String ct = session.getHeaders().get("content-type");
        boolean isJson = ct != null && ct.toLowerCase().contains("application/json");
        String raw = null;

        if (isJson) {
            // Read raw -- DO NOT call parseBody (it consumes the stream and discards the body
            // for unrecognized content-types or for PUT in some versions of NanoHTTPD).
            try {
                String len = session.getHeaders().get("content-length");
                int n = (len != null) ? Integer.parseInt(len) : 0;
                if (n > 0) {
                    byte[] buf = new byte[n];
                    int read = 0;
                    while (read < n) {
                        int got = session.getInputStream().read(buf, read, n - read);
                        if (got < 0) break;
                        read += got;
                    }
                    raw = new String(buf, 0, read, "UTF-8");
                }
            } catch (Exception e) { Log.w(TAG, "raw body read failed", e); }
        } else {
            // Form-encoded etc -- use parseBody.
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
                raw = files.get("postData");
            } catch (Exception e) { /* ignore */ }
        }

        Log.i(TAG, "body bytes=" + (raw == null ? 0 : raw.length()) + " ct=" + ct);
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); } catch (JSONException e) {
            Log.w(TAG, "json parse failed: " + raw);
            return new JSONObject();
        }
    }

    private Response json(Response.Status st, String body) {
        return newFixedLengthResponse(st, "application/json; charset=utf-8", body);
    }
    private String errJson(String msg) {
        try { return new JSONObject().put("error", msg == null ? "" : msg).toString(); } catch (JSONException e) { return "{\"error\":\"\"}"; }
    }

    private Response serveAsset(String uri) {
        String path = uri.equals("/") ? "/index.html" : uri;
        if (path.startsWith("/")) path = path.substring(1);
        String assetPath = "web/" + path;
        AssetManager am = ctx.getAssets();
        try (InputStream is = am.open(assetPath)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            byte[] data = out.toByteArray();
            Response r = newFixedLengthResponse(Response.Status.OK, mimeFor(path),
                new ByteArrayInputStream(data), data.length);
            // Aggressive HTTP-cache so iOS PWAs (added to Home Screen) keep the shell on
            // disk and survive a flat connection. Hashed assets under /assets/ have the
            // bundle hash baked into their filename (e.g. index-Bf70jWtl.js), so they
            // can be `immutable` for a year. index.html / manifest / icons rotate on
            // rebuild but their content hash is stable per build, so a one-day max-age
            // gives a fresh check on the next cold cache day without thrashing.
            if (path.startsWith("assets/")) {
                r.addHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else if (path.equals("index.html") || path.endsWith(".webmanifest")
                    || path.endsWith(".png") || path.endsWith(".svg")) {
                r.addHeader("Cache-Control", "public, max-age=86400");
            }
            return r;
        } catch (IOException e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
    }

    private String mimeFor(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json") || path.endsWith(".webmanifest")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }
}
