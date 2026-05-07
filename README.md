# Xgimi Bridge

<p align="center"><img src="docs/demo.gif" alt="Xgimi Bridge web app in action" width="300"></p>

Background-only Android service for an **Xgimi Horizon Pro** projector that exposes a local-LAN HTTP API and a small web app. Together they replace the physical remote: every button on the original Xgimi remote is reachable from your phone's browser, plus picture-quality controls and named presets.

- HTTP API on port **8080**
- Web UI on the same port — works in any modern mobile or desktop browser; add to your phone's home screen (iOS Safari "Add to Home Screen", Android Chrome "Install app") for a standalone-app feel. The bridge sets long `Cache-Control` headers on the hashed asset bundle so the OS keeps the shell on disk after first load. A "Disconnected" badge surfaces in the header when the bridge is unreachable.
- mDNS hostname **`xgimi.local`** so the IP can rotate
- No app icon, no apps-screen presence on the projector, survives reboot via persisted `JobScheduler`

> **Note on offline behaviour**: the bridge serves over plain HTTP, so a service worker can't register (browsers require HTTPS for SW registration). Offline-shell caching relies on the browser's home-screen / installed-app cache plus our `Cache-Control: immutable` headers. Effective for the typical "projector asleep / Wi-Fi blip" case, less so when the OS evicts the cache. If you want a true SW-backed offline shell, terminate the bridge behind HTTPS yourself (self-signed cert + per-device trust install).

> **Tested device**: Xgimi Horizon Pro on **Android 10** (MT9669, kernel 4.19, security patch 2022-09-05). Other Xgimi models or firmware revisions probably need tweaks — keycodes for power / gear / focus, the `com.xgimi.misckey` and `com.xgimi.xhplayer` package names, and the `com.xgimi.xgimiservice` AIDL surface are all derived from this specific firmware. The web client itself is browser-based and not tied to any particular phone OS.

## What works

- D-pad / OK / back / home / menu — full remote navigation
- Volume up / down / mute
- Power (display on/off, same toggle as the physical remote)
- Settings / gear (opens Xgimi's misckey panel)
- Auto-focus
- Google Assistant key
- Source switch (Apps / HDMI 1 / HDMI 2)
- White balance R/G/B (slot-0 — visible in HDMI + game-mode combos)
- Brightness / contrast / saturation / sharpness / hue
- Named picture presets — built-in **Balanced** preset is non-removable

## How it works

The interesting design decision is how key events get injected. The straightforward path — talking to Google's `com.google.android.tv.remote.service` over its mTLS pair / control protocol — is **broken on this device**: the control acceptor at port 6466 has a same-host filter (`isLoopbackAddress() || NetworkInterface.getByInetAddress(peer) != null` returns true → connection closed), so any client running on the projector itself gets dropped. Verified by decompiling the service across versions 2.1 → 6.7 — the filter is identical and present in every shipping build.

Instead, the bridge becomes its own ADB client over loopback:

```
Web client  ──HTTP──▶  Bridge (untrusted_app)
                       │
                       │  loopback TCP
                       ▼
                127.0.0.1:5555  (adbd, listening for ADB-over-TCP)
                       │
                       │  ADB protocol, RSA-2048 auth
                       ▼
                shell-uid session  ──spawns──▶  app_process running KeyDispatcher
                                                          │
                                                          │  IInputManager.injectInputEvent
                                                          │  (shell uid has INJECT_EVENTS)
                                                          ▼
                                                    KeyEvent to active window
```

On first run the bridge generates an RSA-2048 keypair, opens a TCP connection to `127.0.0.1:5555`, and speaks the ADB protocol. The server-side acceptance flow surfaces the standard "Allow USB debugging from this computer?" prompt on the TV — the user clicks Allow + Always **once**, with the physical remote. From that point on the bridge has shell-uid execution.

`KeyDispatcher` is a small Java class loaded into a long-lived `app_process` JVM via the bridge's own APK on the classpath. It loops on stdin reading keycode integers and calls `IInputManager.injectInputEvent` reflectively (it's a hidden API; shell uid bypasses the hidden-API restriction). One JVM cold-start per bridge run, then ~5 ms per keypress.

A handful of keys do not go through the dispatcher because they are intercepted by Xgimi's `PhoneWindowManager.interceptKeyBeforeDispatching` and route to specific services:

- **Power (KEYCODE_XGIMI_POWER = 2100)** — toggles display sleep/wake via `XgimiActionCommon.doAction → GmTvManager.getWakeUpSource`. Going through `injectInputEvent` triggers this hook the same way a physical button does.
- **Settings / gear (KEYCODE_XGIMI_MISCKEY = 2101)** — opens `com.xgimi.misckey` panel via `XgimiActionCommon.doAction → startService(misckey)`.
- **Auto-focus (KEYCODE_XGIMI_AUTOFOCUS = 2099)** — invokes `IProjectorFocusManager.Send AUTO_FOCUS`.

These all just go through the normal `injectKey` path; the Xgimi WindowManager catches them upstream of normal dispatch.

Picture-quality knobs and source switching go through Xgimi's system-uid AIDL service (`com.xgimi.xgimiservice`), which exposes a number of binder methods to any caller without permission gates. `AidlClient.java` speaks raw Binder transactions to it.

## Layout

```
bridge/
├── app/                              Android app (plain Android SDK build, no Gradle)
│   ├── AndroidManifest.xml
│   ├── build.sh                      Builds bridge.apk via javac + d8 + aapt2 + apksigner
│   ├── install.sh                    Installs + grants whitelist/appops + kicks the service
│   ├── debug.keystore                Self-signed key (auto-generated if missing)
│   ├── lib/                          NanoHTTPD 2.3.1, JmDNS 3.4.1
│   ├── src/com/xgimi/bridge/
│   │   ├── AidlClient.java           Raw Binder to com.xgimi.xgimiservice (system uid)
│   │   ├── BridgeService.java        Foreground service: HTTP + mDNS lifecycle
│   │   ├── BridgeJobService.java     Persisted JobScheduler job for boot auto-start
│   │   ├── HttpServer.java           NanoHTTPD subclass with /api/* routes
│   │   ├── KeyDispatcher.java        Long-lived input-event injector (runs as shell uid)
│   │   ├── LauncherActivity.java     Hidden helper activity (not in apps drawer)
│   │   ├── LocalAdbClient.java       Pure-Java ADB client → loopback adbd
│   │   ├── MdnsAdvertiser.java       JmDNS advertisement of xgimi.local + _http._tcp
│   │   └── PresetStore.java          JSON-backed preset CRUD + apply
│   ├── assets/web/                   Built web bundle (overwritten by web/ build)
│   └── build/bridge.apk              Signed APK output
└── web/                              React + Vite web app (writes to ../app/assets/web)
    ├── src/App.jsx
    ├── src/index.css
    ├── src/main.jsx
    ├── public/icon-{180,192,512}.png
    └── vite.config.js
```

## Download

Prebuilt APKs are attached to each [GitHub Release](https://github.com/andotsy/xgimi-bridge/releases). Grab the latest `bridge.apk` and skip ahead to the install steps below — you only need the build prerequisites if you want to compile from source.

## Prerequisites

- Android SDK with `build-tools/34.0.0` and `platforms/android-29`. Set `ANDROID_SDK_ROOT` (defaults to `/opt/android-sdk`).
- JDK 17.
- Node + npm (for the web build).
- `adb` reachable, paired with the projector over Wi-Fi (port 5555). Projector ADB confirmation already accepted on the device.
- ADB-over-TCP must remain enabled on the projector at runtime — the bridge reaches `127.0.0.1:5555` on the device itself. On most Xgimi firmware this is enabled by default and survives reboot; if not, `setprop service.adb.tcp.port 5555` then restart `adbd`.

## Build & install

```bash
# 1) Build the web app (writes the bundle into app/assets/web/)
cd web && npm install && npm run build

# 2) Build the APK
cd ../app && ./build.sh        # output: build/bridge.apk

# 3) Install on the projector and grant runtime exemptions
./install.sh                   # auto-picks the only connected ADB device
# or pass the host explicitly:
PROJECTOR=xgimi.local ./install.sh
PROJECTOR=192.168.0.55 ./install.sh
```

If no device is connected, pair first:

```bash
adb connect <projector-ip>:5555
```

`install.sh` does these things, in order:

1. `adb install -r build/bridge.apk`
2. `dumpsys deviceidle whitelist +com.xgimi.bridge`
3. `appops set` for `SYSTEM_ALERT_WINDOW`, `START_FOREGROUND`, `RUN_IN_BACKGROUND`, `RUN_ANY_IN_BACKGROUND`
4. `am start -n com.xgimi.bridge/.LauncherActivity` (kicks the service + schedules the persistent job)
5. `cmd jobscheduler run com.xgimi.bridge 7331` (force-fires the job once)

After this, the bridge is reachable at `http://xgimi.local:8080/` from any phone on the same Wi-Fi.

## First-run authorization

When you open the web app the first time after install, the d-pad area is replaced by an **Authorize** card. Tap it. Within ~1 s an "Allow USB debugging from this computer?" dialog appears on the projector. Use the physical remote to navigate to **Always allow from this computer** + **OK**. The card flips to the d-pad within ~1 s of approval. The authorization is persisted on the projector (`/data/misc/adb/adb_keys`) and survives reboot — you only do this once.

## Boot auto-start

Boot is handled entirely by a `JobScheduler` job with `setPersisted(true)` (`BridgeJobService`, id `7331`):

- Scheduled with `(minLatency=30s, deadline=90s)`.
- `setPersisted(true)` survives reboot — once the device has been off longer than the deadline window, the persisted deadline is already in the past, so the job fires almost immediately on next boot.
- `onStartJob` re-arms with the same 30–90 s window. A tighter window infinite-loops (the framework re-fires before `onStartJob` returns); a longer window means the bridge waits minutes after boot to come up.

`setPersisted(true)` requires `RECEIVE_BOOT_COMPLETED` or the framework throws — that's the only reason that permission is in the manifest. The `BOOT_COMPLETED` broadcast itself is silently dropped on this firmware for non-system apps.

Worst-case latency from boot to `xgimi.local:8080` answering: ~90 seconds.

## HTTP API

State / picture / sources:

- `GET  /api/state`                  `{ ready, port, ts }`
- `GET  /api/picture`                full picture-quality snapshot
- `GET  /api/inputs`                 current `TvInputManager` listing with CEC-derived names
- `POST /api/picture/<knob>`         `{ "value": <int> }` — knobs: `wb-red`, `wb-green`, `wb-blue`, `color-temp`, `picture-mode`, `game-mode`, `brightness`, `contrast`, `saturation`, `sharpness`, `hue`
- `POST /api/picture/reset`          `{ "mode": <int>, "force": <bool> }`
- `POST /api/input`                  `{ "to": "HDMI1" | "HDMI2" | "STORAGE" }`

Presets (persist to `/data/data/com.xgimi.bridge/files/presets.json`):

- `GET    /api/presets`
- `POST   /api/presets`              `{ "name": "..." }` snapshots the current state
- `POST   /api/presets/<id>/apply`
- `POST   /api/presets/<id>/sync`    refresh from current state
- `PUT    /api/presets/<id>`         rename / replace
- `DELETE /api/presets/<id>`         (built-ins refuse)

Power:

- `POST /api/power/restart`          reboot the projector

Remote (ADB-loopback / KeyDispatcher):

- `GET  /api/adb/status`             `{ "authorized": bool }` — non-prompting probe
- `POST /api/adb/authorize`          surfaces the on-screen Allow prompt; returns when accepted
- `POST /api/adb/keyevent/<n>`       inject `KEYCODE_<n>` (Android keycode int)

## Notes / known quirks

- **WB sliders only visibly affect the picture on color-temp slot 0.** HDMI + game mode is the typical combination that lands there. Other combinations write to slot 0 successfully but you won't see the change.
- **AIDL `switchToSignalSource` is a stub** on this firmware. The bridge instead fires `Intent("com.xgimi.action.hdmiPlayer")` at `com.xgimi.xhplayer/.InputActivity` with `extra sw=HDMI1|HDMI2|HDMI3`. Apps/Storage uses `tvlauncher.MainActivity`.
- **BAL bypass for the input switch** requires the `SYSTEM_ALERT_WINDOW` appop (granted by `install.sh`). The grant survives until reboot; on a fresh boot you'd need to re-run `install.sh` only if you want the cold-start input switch to work — the rest of the bridge functions normally.
- **`xgimi.local` resolution** uses mDNS / Bonjour. iOS, macOS, and most Android builds with NSD resolve it natively in browsers. Some shell tools can't resolve `.local` via `getaddrinfo` — use the IP directly, or query mDNS explicitly (`dns-sd -G v4 xgimi.local` on macOS, `avahi-resolve -n xgimi.local` on Linux).
- **The bridge needs ADB-over-TCP enabled on the projector** to authorize itself. Anyone else on the same LAN who can reach port 5555 already has full ADB access — this isn't worse than what ADB-over-TCP already gives you.

## Rebuild only the web app

```bash
cd web && npm run build && cd ../app && ./build.sh && ./install.sh
```

The Vite build writes directly into `app/assets/web/`, which `build.sh` zips into the APK.

## Custom keycodes

The web client uses standard Android keycodes for most buttons (`KEYCODE_DPAD_*`, `KEYCODE_BACK`, `KEYCODE_VOLUME_*`, etc.) and a few Xgimi-custom ones:

| Keycode | Constant                  | Triggered by                              |
|---------|---------------------------|-------------------------------------------|
| 2099    | `KEYCODE_XGIMI_AUTOFOCUS` | Physical focus button                     |
| 2100    | `KEYCODE_XGIMI_POWER`     | Physical power button                     |
| 2101    | `KEYCODE_XGIMI_MISCKEY`   | Physical gear / settings button           |

These pass through `injectInputEvent` and Xgimi's `PhoneWindowManager` intercept routes them the same way the physical remote does.
