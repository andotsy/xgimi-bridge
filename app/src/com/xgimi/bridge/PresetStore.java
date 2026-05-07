package com.xgimi.bridge;

import android.content.Context;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Persists picture presets as JSON in the app private files dir.
 * Each preset: { id, name, values:{wbRed, wbGreen, wbBlue, colorTemp, pictureMode, gameMode}, createdAt, updatedAt }
 * Any field in values can be null/missing -- that field is left unchanged on apply.
 */
public class PresetStore {
    private static final String TAG = "XgimiBridge";
    private static final String FILE = "presets.json";

    private final File path;
    private final AidlClient aidl;

    public PresetStore(Context ctx, AidlClient aidl) {
        this.path = new File(ctx.getFilesDir(), FILE);
        this.aidl = aidl;
    }

    public synchronized JSONArray load() {
        ensureBuiltins();
        if (!path.exists()) return new JSONArray();
        try (FileInputStream f = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = f.read(buf)) != -1) out.write(buf, 0, n);
            return new JSONArray(out.toString("UTF-8"));
        } catch (IOException | JSONException e) {
            Log.e(TAG, "preset load failed", e);
            return new JSONArray();
        }
    }

    private synchronized void save(JSONArray arr) throws IOException {
        File tmp = new File(path.getParent(), FILE + ".tmp");
        try (FileOutputStream f = new FileOutputStream(tmp)) {
            f.write(arr.toString().getBytes("UTF-8"));
        }
        if (!tmp.renameTo(path)) {
            // fallback
            tmp.delete();
            try (FileOutputStream f = new FileOutputStream(path)) {
                f.write(arr.toString().getBytes("UTF-8"));
            }
        }
    }


    private static final String BALANCED_ID = "balanced";

    /** Canonical values applied by the built-in Balanced preset. WB tint chosen by the user;
     *  picture values set to neutral (50) so tapping Balanced resets any prior tweaks. */
    private static JSONObject balancedValues() throws JSONException {
        return new JSONObject()
                .put("wbRed", 60).put("wbGreen", 50).put("wbBlue", 55)
                .put("brightness", 50).put("contrast", 50)
                .put("saturation", 50).put("sharpness", 50).put("hue", 50);
    }

    /** Ensure built-in Balanced preset exists with canonical values; migrate legacy Vivid preset if found. */
    private void ensureBuiltins() {
        try {
            JSONArray arr = readRaw();
            JSONObject balanced = null;
            int legacyIdx = -1;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                String id = p.optString("id");
                if (BALANCED_ID.equals(id)) { balanced = p; }
                else if ("vivid".equals(id) || "Vivid".equals(p.optString("name"))) { legacyIdx = i; }
            }
            boolean changed = false;
            if (balanced == null && legacyIdx >= 0) {
                JSONObject p = arr.getJSONObject(legacyIdx);
                p.put("id", BALANCED_ID);
                p.put("name", "Balanced");
                p.put("builtin", true);
                p.put("values", balancedValues());
                p.put("updatedAt", System.currentTimeMillis());
                Log.i(TAG, "migrated Vivid -> Balanced");
                changed = true;
            } else if (balanced == null) {
                long now = System.currentTimeMillis();
                JSONObject p = new JSONObject()
                    .put("id", BALANCED_ID)
                    .put("name", "Balanced")
                    .put("values", balancedValues())
                    .put("builtin", true)
                    .put("createdAt", now)
                    .put("updatedAt", now);
                arr.put(p);
                Log.i(TAG, "created Balanced built-in preset");
                changed = true;
            } else {
                JSONObject canonical = balancedValues();
                if (!sameValues(balanced.optJSONObject("values"), canonical)) {
                    balanced.put("values", canonical);
                    balanced.put("updatedAt", System.currentTimeMillis());
                    Log.i(TAG, "refreshed Balanced built-in preset values");
                    changed = true;
                }
                if (!balanced.optBoolean("builtin", false)) {
                    balanced.put("builtin", true);
                    changed = true;
                }
            }
            // Sanitize values on every preset (clean up any leaked diagnostic fields)
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                JSONObject v = p.optJSONObject("values");
                if (v != null) {
                    JSONObject clean = sanitizeValues(v);
                    if (clean.length() != v.length()) {
                        p.put("values", clean);
                        changed = true;
                    }
                }
            }
            if (changed) save(arr);
        } catch (Exception e) {
            Log.e(TAG, "ensureBuiltins failed", e);
        }
    }

    private JSONArray readRaw() {
        if (!path.exists()) return new JSONArray();
        try (FileInputStream f = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = f.read(buf)) != -1) out.write(buf, 0, n);
            return new JSONArray(out.toString("UTF-8"));
        } catch (Exception e) { return new JSONArray(); }
    }
    public JSONObject create(JSONObject body) throws IOException, JSONException {
        String name = body.optString("name", "Untitled").trim();
        if (name.isEmpty()) name = "Untitled";
        JSONObject values = sanitizeValues(body.optJSONObject("values"));
        if (values.length() == 0) values = captureCurrent();

        String id = uniqueIdFor(name);
        JSONObject p = new JSONObject();
        long now = System.currentTimeMillis();
        p.put("id", id);
        p.put("name", name);
        p.put("values", values);
        p.put("createdAt", now);
        p.put("updatedAt", now);

        JSONArray arr = load();
        arr.put(p);
        save(arr);
        return p;
    }

    public JSONObject update(String id, JSONObject body) throws IOException, JSONException {
        JSONArray arr = load();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (id.equals(p.getString("id"))) {
                if (body.has("name")) p.put("name", body.getString("name"));
                if (body.has("values")) p.put("values", sanitizeValues(body.optJSONObject("values")));
                p.put("updatedAt", System.currentTimeMillis());
                save(arr);
                return p;
            }
        }
        return null;
    }

    /** Update a presets values to current projector state (preserve id+name). */
    public JSONObject syncFromCurrent(String id) throws IOException, JSONException {
        JSONArray arr = load();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (id.equals(p.getString("id"))) {
                p.put("values", captureCurrent());
                p.put("updatedAt", System.currentTimeMillis());
                save(arr);
                return p;
            }
        }
        return null;
    }

    public static class BuiltinException extends RuntimeException { public BuiltinException(String s){super(s);} }

    public boolean delete(String id) throws IOException, JSONException {
        JSONArray arr = load();
        JSONArray out = new JSONArray();
        boolean removed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (id.equals(p.getString("id"))) { if (p.optBoolean("builtin", false)) throw new BuiltinException("cannot delete built-in preset"); removed = true; continue; }
            out.put(p);
        }
        if (removed) save(out);
        return removed;
    }

    public JSONObject apply(String id) throws RemoteException, JSONException {
        JSONArray arr = load();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.getJSONObject(i);
            if (id.equals(p.getString("id"))) {
                JSONObject v = p.optJSONObject("values");
                if (v != null) applyValues(v);
                JSONObject reply = new JSONObject();
                reply.put("ok", true).put("id", id).put("after", new JSONObject(aidl.snapshot()));
                return reply;
            }
        }
        return null;
    }

    private void applyValues(JSONObject v) throws RemoteException {
        // pictureMode + colorTemp first — they may switch the active slot, which affects WB writes.
        if (v.has("pictureMode")) writeAndVerify("pictureMode", AidlClient.TX_SET_PICTURE_MODE, AidlClient.TX_GET_PICTURE_MODE, v.optInt("pictureMode"));
        if (v.has("colorTemp"))   writeAndVerify("colorTemp",   AidlClient.TX_SET_COLOR_TEMP,   AidlClient.TX_GET_COLOR_TEMP,   v.optInt("colorTemp"));
        if (v.has("wbRed"))       writeAndVerify("wbRed",       AidlClient.TX_SET_WB_RED_GAIN,   AidlClient.TX_GET_WB_RED_GAIN,   v.optInt("wbRed"));
        if (v.has("wbGreen"))     writeAndVerify("wbGreen",     AidlClient.TX_SET_WB_GREEN_GAIN, AidlClient.TX_GET_WB_GREEN_GAIN, v.optInt("wbGreen"));
        if (v.has("wbBlue"))      writeAndVerify("wbBlue",      AidlClient.TX_SET_WB_BLUE_GAIN,  AidlClient.TX_GET_WB_BLUE_GAIN,  v.optInt("wbBlue"));
        if (v.has("gameMode"))    writeAndVerify("gameMode",    AidlClient.TX_SET_GAME_MODE_OPTION, AidlClient.TX_GET_GAME_MODE_OPTION, v.optInt("gameMode"));
        // Picture items: getter is unreliable on this firmware (returns slot-0 value, not active),
        // so verify+retry produces false failures. The setter itself works visibly (confirmed by sliders),
        // so just write once and space writes ~150ms apart so the native HAL can apply each one cleanly.
        if (v.has("brightness")) writePictureItem("brightness", 0, v.optInt("brightness"));
        if (v.has("contrast"))   writePictureItem("contrast",   1, v.optInt("contrast"));
        if (v.has("saturation")) writePictureItem("saturation", 2, v.optInt("saturation"));
        if (v.has("sharpness"))  writePictureItem("sharpness",  3, v.optInt("sharpness"));
        if (v.has("hue"))        writePictureItem("hue",        4, v.optInt("hue"));
    }

    private void writePictureItem(String label, int item, int target) throws RemoteException {
        boolean ok = aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, item, target);
        Log.i(TAG, label + "=" + target + " (set returned " + ok + ")");
        try { Thread.sleep(150); } catch (InterruptedException ie) {}
    }

    private void writePictureItemAndVerify(String label, int item, int target) throws RemoteException {
        for (int attempt = 0; attempt < 4; attempt++) {
            aidl.setIntInt(AidlClient.TX_SET_PICTURE_ITEM, item, target);
            try { Thread.sleep(60); } catch (InterruptedException ie) {}
            int got;
            try { got = aidl.getIntWithIntArg(AidlClient.TX_GET_PICTURE_ITEM, item); } catch (Throwable t) { got = -9999; }
            if (got == target) { Log.i(TAG, label + "=" + target + " ok (attempt " + (attempt + 1) + ")"); return; }
            Log.w(TAG, label + ": wrote " + target + " readback=" + got + " (retry " + (attempt + 1) + ")");
        }
        Log.e(TAG, label + ": failed to converge to " + target);
    }

    private void writeAndVerify(String label, int setCode, int getCode, int target) throws RemoteException {
        for (int attempt = 0; attempt < 4; attempt++) {
            aidl.setInt(setCode, target);
            try { Thread.sleep(60); } catch (InterruptedException ie) {}
            int got;
            try { got = aidl.getInt(getCode); } catch (Throwable t) { got = -9999; }
            if (got == target) {
                Log.i(TAG, label + "=" + target + " ok (attempt " + (attempt + 1) + ")");
                return;
            }
            Log.w(TAG, label + ": wrote " + target + " but readback=" + got + " (retry " + (attempt + 1) + ")");
        }
        Log.e(TAG, label + ": failed to converge to " + target + " after 4 attempts");
    }

    /** Whitelist of preset-storable fields (must match keys handled by applyValues). */
    private static boolean sameValues(JSONObject a, JSONObject b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        java.util.Iterator<String> it = b.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (!a.has(k)) return false;
            if (a.optInt(k, Integer.MIN_VALUE) != b.optInt(k, Integer.MIN_VALUE + 1)) return false;
        }
        return true;
    }

    private static final java.util.Set<String> WRITABLE = new java.util.HashSet<>(java.util.Arrays.asList(
        "wbRed", "wbGreen", "wbBlue", "colorTemp", "pictureMode", "gameMode",
        "brightness", "contrast", "saturation", "sharpness", "hue"
    ));

    private JSONObject captureCurrent() throws JSONException {
        JSONObject v = new JSONObject();
        for (java.util.Map.Entry<String,Object> e : aidl.snapshot().entrySet()) {
            if (WRITABLE.contains(e.getKey()) && e.getValue() instanceof Integer) {
                v.put(e.getKey(), e.getValue());
            }
        }
        return v;
    }

    /** Strip non-writable keys from any incoming/stored values blob. */
    private JSONObject sanitizeValues(JSONObject in) throws JSONException {
        JSONObject v = new JSONObject();
        if (in == null) return v;
        java.util.Iterator<String> it = in.keys();
        while (it.hasNext()) {
            String k = it.next();
            if (WRITABLE.contains(k)) v.put(k, in.opt(k));
        }
        return v;
    }

    private String uniqueIdFor(String name) {
        String base = name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "preset";
        JSONArray arr = load();
        String candidate = base; int n = 2;
        while (true) {
            boolean clash = false;
            for (int i = 0; i < arr.length(); i++) {
                if (candidate.equals(arr.optJSONObject(i).optString("id"))) { clash = true; break; }
            }
            if (!clash) return candidate;
            candidate = base + "-" + n++;
        }
    }
}
