#!/usr/bin/env bash
# GixxerBridge — one-command morning setup.
# Plug the K20 Pro in, run this, walk through SMOKE_TEST.md.
set -euo pipefail
cd "$(dirname "$0")"

JDK17=/usr/lib/jvm/java-17-openjdk
[ -x "$JDK17/bin/java" ] || { echo "Install JDK 17: sudo pacman -S jdk17-openjdk"; exit 1; }
export JAVA_HOME="$JDK17"

[ -d /home/mrwick/Android ] || { echo "Android SDK missing at ~/Android"; exit 1; }
export ANDROID_HOME=/home/mrwick/Android

echo "=== adb device check ==="
adb devices -l
device_count=$(adb devices | awk 'NR>1 && $0 ~ /device$/' | wc -l)
if [ "$device_count" -lt 1 ]; then
  echo "No adb device found — plug in the K20 Pro with USB debugging on."
  exit 1
fi

echo
echo "=== building fresh APK ==="
./gradlew :app:assembleDebug --console=plain --no-configuration-cache

echo
echo "=== installing on phone ==="
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo
echo "=== pre-granting runtime permissions (silences first-launch dialogs) ==="
adb shell pm grant dev.mrwick.redline.debug android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
adb shell pm grant dev.mrwick.redline.debug android.permission.BLUETOOTH_SCAN 2>/dev/null || true
adb shell pm grant dev.mrwick.redline.debug android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell pm grant dev.mrwick.redline.debug android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant dev.mrwick.redline.debug android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
adb shell pm grant dev.mrwick.redline.debug android.permission.SEND_SMS 2>/dev/null || true

echo
echo "=== granting notification listener access ==="
adb shell cmd notification allow_listener dev.mrwick.redline.debug/dev.mrwick.redline.notifications.NotificationCaptureService 2>/dev/null || true

echo
echo "=== keeping screen on while plugged in ==="
adb shell settings put global stay_on_while_plugged_in 7

echo
echo "=== force-stopping Suzuki Connect (so it doesn't fight for the BLE link) ==="
adb shell am force-stop suzuki.com.suzuki

echo
echo "=== launching app ==="
adb shell am start -n dev.mrwick.redline.debug/dev.mrwick.redline.MainActivity

echo
echo "Done. Open SMOKE_TEST.md in the project root and walk through the phases."
echo "If the bike is on, you can also start the service via:"
echo "  adb shell am start-foreground-service -n dev.mrwick.redline.debug/dev.mrwick.redline.ble.BikeBridgeService"
echo
echo "Live logs:"
echo "  adb logcat -s BikeBridge:* BleClient:* GoogleMapsParser:* ManeuverClassifier:I"
