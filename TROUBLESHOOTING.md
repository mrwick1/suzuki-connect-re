# GixxerBridge — Morning Troubleshooting

> What to do when something doesn't work on first real-bike test.

## "App won't install"

```bash
adb shell pm uninstall dev.mrwick.gixxerbridge.debug
./android/MORNING_QUICKSTART.sh
```

If uninstall fails: Settings → Apps → GixxerBridge → Uninstall.

## "App crashes on launch"

```bash
adb logcat -d | grep -iE "fatal|androidruntime" | head -20
```

Common causes:
- DataStore corruption from previous install → `adb shell pm clear dev.mrwick.gixxerbridge.debug` then re-grant perms via the quickstart script
- Missing permission → check that all runtime perms are granted (Settings → Apps → GixxerBridge → Permissions)

## "Onboarding loops / can't dismiss"

```bash
# Manually mark onboarding done
adb shell run-as dev.mrwick.gixxerbridge.debug \
  sh -c "rm files/datastore/gixxer_settings.preferences_pb"
adb shell am force-stop dev.mrwick.gixxerbridge.debug
adb shell am start -n dev.mrwick.gixxerbridge.debug/dev.mrwick.gixxerbridge.MainActivity
# Wizard will replay; complete it via UI
```

Or from inside the app once you're past it: Settings → About → Reset all data.

## "Bike won't pair" / "Scan shows no bikes"

1. Confirm bike key is ON (cluster lit up)
2. Confirm Bluetooth ON on the phone
3. Try a manual scan: `python tools/gatt_walk.py --scan` from the laptop — confirms the bike is BLE-visible at all
4. If laptop sees the bike but phone doesn't: the phone may have a BLE cache issue. Toggle Bluetooth off+on on the phone.
5. If the phone shows the bike but tap-to-pair fails: the bike might require a Settings-level pair first. Go to Android Settings → Bluetooth → pair the SBM device → return to GixxerBridge

## "Connected but no telemetry (a537 not streaming)"

The bike won't notify until we write an a536 identity. Check Inspector:
- Do you see a TX a536? If not, the service may have started before the connection settled. Re-tap Start GixxerBridge.
- Do you see TX a536 but no RX a537 within 10 s? The bike isn't recognizing our identity. Try with the bike on for 30+ seconds before tapping Start.

```bash
adb logcat -s BikeBridge:* BleClient:* | head -50
```

## "Cluster shows 'Searching for network' even with Maps nav active"

Means a531 isn't reaching the bike OR is reaching with status='0' (degraded). Check Inspector:
- Are a531 frames being sent (TX)? If not: NotificationCaptureService didn't fire. Verify Notification Access is granted (Settings → top of screen "Notification access" should say "Granted" in green)
- If a531 frames ARE being sent but cluster ignores: probably the maneuver byte is wrong. Cluster cyan dot on TX line should match the turn type.

Run the empirical dumper:
```bash
cd ~/coding/projects/suzuki-connect-re
python tools/dump_maps_notification.py --watch
```
Then start a Maps nav. Watch what the dumper outputs — confirm Maps is actually posting nav notifications, and confirm the RemoteViews structure matches what GoogleMapsParser expects (`nav_title`, `nav_description`, `nav_time` entries).

## "Cluster shows wrong turn arrow"

Text fallback in ManeuverMap is winning over a wrong bitmap classification. Check logcat:
```bash
adb logcat -s ManeuverClassifier:I
```

If you see `Learned hash=0x... -> maneuver=X` lines: the bitmap classifier is self-training. Wait a couple of rides; accuracy will improve.

## "App drains battery"

- Confirm `Keep screen on while connected` is OFF in Settings (default off)
- Disable Demo mode if you accidentally enabled it
- Reduce notification listener traffic by trimming the allowlist (Settings → Notifications mirrored to bike → Edit allowlist → uncheck noisy apps)

## "Service notification gets dismissed by Android"

Modern Androids let users swipe foreground service notifications. Re-enable via Settings → Apps → GixxerBridge → Notifications → make sure the "Bike connection" channel is ON. The notification will come back next time the service is launched.

## "SMS / call mirroring doesn't fire"

NotificationListenerService events don't reach us if the listener was disabled. Re-grant in Settings → top of screen "Notification access" card.

Also check the dispatcher routing:
```bash
adb logcat -s NotifDispatcher:*
```

## "Maps disabled crash detection / Auto-DND on"

Both are opt-in. To verify state: Settings → Safety → Crash detection should be a toggle. Settings → Phone → Auto-DND should be a toggle. If both are off, no behavior change.

## Resetting everything

Nuclear option — wipe all app state including paired bike, onboarding, prefs, ride log:

```bash
adb shell pm clear dev.mrwick.gixxerbridge.debug
```

Then re-launch and walk through onboarding again.

## Capturing a full bug report

```bash
adb logcat -d > /tmp/gixxer-fail.log
adb shell screencap -p /sdcard/screen.png && adb pull /sdcard/screen.png /tmp/
adb shell dumpsys notification --noredact > /tmp/notif-dump.txt
adb shell dumpsys activity service dev.mrwick.gixxerbridge.debug/.ble.BikeBridgeService > /tmp/service-dump.txt
```

Share those four files.

## Reference logs

```bash
# Tail just our code
adb logcat -s BikeBridge:* BleClient:* GoogleMapsParser:* ManeuverClassifier:I NotifDispatcher:* RideLogger:* DemoTelemetry:* MapsNavSource:*

# All Bluetooth (verbose)
adb logcat -s BluetoothGatt:*

# Power & wake
adb logcat -s PowerManagerService:*
```
