#!/usr/bin/env bash
# Install the bridge APK to the projector and grant runtime exemptions it needs.
#
# Target selection (in order):
#   1. PROJECTOR=<host[:port]> ./install.sh   — explicit, e.g. PROJECTOR=xgimi.local
#   2. Auto-pick from `adb devices` when exactly one device is connected.
#   3. Otherwise fail with instructions.
set -e
PROJ="$(cd "$(dirname "$0")" && pwd)"
APK="${APK:-$PROJ/build/bridge.apk}"
PKG=com.xgimi.bridge

if [ -n "$PROJECTOR" ]; then
    P="$PROJECTOR"
else
    devices=$(adb devices | awk 'NR>1 && $2=="device" {print $1}')
    count=$(printf '%s\n' "$devices" | grep -c .)
    if [ "$count" = "1" ]; then
        P="$devices"
        echo "Using auto-detected device: $P"
    elif [ "$count" = "0" ]; then
        echo "No ADB device connected." >&2
        echo "Pair over Wi-Fi first, then re-run:" >&2
        echo "    adb connect <projector-ip>:5555" >&2
        echo "Or set PROJECTOR=<host[:port]> ./install.sh" >&2
        exit 1
    else
        echo "Multiple ADB devices connected — pick one:" >&2
        printf '%s\n' "$devices" | sed 's/^/    /' >&2
        echo "Re-run with: PROJECTOR=<host[:port]> ./install.sh" >&2
        exit 1
    fi
fi

# Add :5555 if a bare IP/host was given without a port.
case "$P" in
    *:*) ;;
    *) P="$P:5555" ;;
esac

adb -s "$P" install -r "$APK"

# Doze whitelist — keeps the persisted JobScheduler reliable across boots
adb -s "$P" shell "dumpsys deviceidle whitelist +$PKG"

# AppOps — allow background activity launch + background run
adb -s "$P" shell "appops set $PKG SYSTEM_ALERT_WINDOW allow"
adb -s "$P" shell "appops set $PKG START_FOREGROUND allow"
adb -s "$P" shell "appops set $PKG RUN_IN_BACKGROUND allow"
adb -s "$P" shell "appops set $PKG RUN_ANY_IN_BACKGROUND allow"

# Bridge has no LAUNCHER icon — start the helper activity by explicit component
# (it schedules the persistent JobScheduler job and starts BridgeService).
adb -s "$P" shell "am force-stop $PKG"
adb -s "$P" shell "am start -n $PKG/.LauncherActivity"
sleep 2
adb -s "$P" shell "cmd jobscheduler run $PKG 7331" >/dev/null 2>&1 || true
echo "Installed + whitelisted + job scheduled (persists across reboot)."
