package com.xgimi.bridge;

import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Long-lived input-event injector. Started once per bridge lifetime by spawning
 * {@code app_process} over an ADB shell stream (so it runs as shell uid, which holds the
 * signature-protected INJECT_EVENTS permission). After ~200 ms of JVM startup it sits in a
 * read-line loop on stdin and dispatches a KEY_DOWN/KEY_UP pair per integer keycode it reads.
 *
 * Replaces {@code input keyevent N} which would re-pay the same JVM cold start every call.
 *
 * Invocation pattern (from the bridge over ADB shell):
 *   CLASSPATH=&lt;bridge.apk&gt; exec app_process / com.xgimi.bridge.KeyDispatcher
 *
 * Wire format:
 *   stdin:  "&lt;keycode&gt;\n"   (e.g. "20\n" for DPAD_DOWN)
 *   stdout: "OK &lt;keycode&gt;\n" on success, "ERR &lt;message&gt;\n" on failure
 */
public class KeyDispatcher {
    public static void main(String[] args) {
        try {
            // android.hardware.input.InputManager + injectInputEvent are hidden APIs.
            // Shell uid bypasses Android 10's hidden-API restrictions, so reflection works.
            Class<?> imClass = Class.forName("android.hardware.input.InputManager");
            Method getInstance = imClass.getMethod("getInstance");
            Object im = getInstance.invoke(null);
            Method injectInputEvent = imClass.getMethod(
                    "injectInputEvent",
                    Class.forName("android.view.InputEvent"),
                    int.class);
            int INJECT_MODE_ASYNC = 0;

            System.out.println("READY");
            System.out.flush();

            BufferedReader r = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    int code = Integer.parseInt(line);
                    long now = SystemClock.uptimeMillis();
                    KeyEvent down = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0,
                            0, KeyCharacterMapSource(), 0, 0,
                            android.view.InputDevice.SOURCE_KEYBOARD);
                    KeyEvent up = new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0,
                            0, KeyCharacterMapSource(), 0, 0,
                            android.view.InputDevice.SOURCE_KEYBOARD);
                    injectInputEvent.invoke(im, down, INJECT_MODE_ASYNC);
                    injectInputEvent.invoke(im, up, INJECT_MODE_ASYNC);
                    System.out.println("OK " + code);
                } catch (Exception e) {
                    System.out.println("ERR " + e.getClass().getSimpleName() + ": " + e.getMessage());
                }
                System.out.flush();
            }
        } catch (Throwable t) {
            System.out.println("FATAL " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            System.out.flush();
            System.exit(1);
        }
    }

    /** {@link KeyCharacterMap}'s "virtual" device id — use the same value {@code input} does. */
    private static int KeyCharacterMapSource() {
        return android.view.KeyCharacterMap.VIRTUAL_KEYBOARD;
    }
}
