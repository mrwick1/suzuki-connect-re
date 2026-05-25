# Morning Findings — 2026-05-25 ~08:00 IST

Live testing on Arjun's K20 Pro (Android 16, LineageOS) with Maps active.

## Critical finding 1: Maps uses `Notification.extras`, NOT RemoteViews

The R1 research from 2026-05-24 (based on 3v1n0/GMapsParser from ~2020) said
Maps uses a custom RemoteViews layout for nav, with `Notification.extras`
empty. **This is no longer true for current Maps.**

Live dump (`captures/maps-notification-dump-20260525-075607.md`):

```
android.title       = "Head toward Mavathumpadi-Cherukara Rd"
android.subText     = "Arrive 8:14 am"
android.text        = null
android.progress    = 0
android.progressMax = 8734           # meters to next maneuver
android.largeIcon   = Icon BITMAP 132x132   # turn-arrow bitmap
android.template    = "android.app.Notification$ProgressStyle"
uses_RemoteViews    = False
```

`bigContentView`, `contentView`, `headsUpContentView` all `null`.

### Fix applied

`GoogleMapsParser` now tries **extras path first**:
- `android.title` → instruction string (parsed for inline "In X m / In X km")
- `android.subText` → ETA + maybe total distance
- `(progressMax - progress)` meters → distNext when no inline distance in title
- `android.largeIcon` → Bitmap for `ManeuverClassifier`

Falls back to RemoteViews-walk for older Maps versions that still ship the
custom layout.

**Verification**: `ManeuverClassifier` log line confirms it fires on every
Maps notification post:
```
ManeuverClassifier: No hash match and text fell to GENERIC_ARROW;
  not learning. hash=0x243c1800 text=Head toward Mavathumpadi-Cherukara Rd
```

Text falls to GENERIC_ARROW because "Head toward" is not a turn pattern — that's
correct; the cluster gets the generic forward arrow until the rider hits the
first actual turn.

### Assumptions still to verify

- `progressMax - progress` is the right distance metric. It might be progress-to-completion
  rather than progress-into-segment. We assume the former (matches what cluster wants).
- `android.largeIcon` is the turn arrow on every Maps update. Confirmed once in the
  dump (132×132 bitmap); needs verification across "in 200 m turn left" / "u-turn" etc.

## Critical finding 2: `onListenerConnected` doesn't replay active notifications

The NotificationListenerService only fires `onNotificationPosted` for **new**
posts. The active Maps nav notification that was posted before our listener
bound was being silently ignored — so on app-reinstall or first-grant, the
cluster never saw the existing nav until Maps posted an update.

### Fix applied

`NotificationCaptureService.onListenerConnected` now iterates
`activeNotifications` and dispatches each through `NotificationDispatcher.onPosted`,
catching the pre-bound nav notif within the first second of listener attach.

## Bug 3: Per-composition `Settings(ctx)` (audit Q1.x) — fully fixed

`AppGraph.settings(ctx)`, `AppGraph.quickDestinations(ctx)`,
`AppGraph.lastParkedTracker(ctx)`, `AppGraph.rideStore(ctx)` are now process
singletons. Composables that previously did `remember { Settings(ctx) }` now
all use the AppGraph accessor. Saves redundant DataStore handle allocation.

## Bug 4: UI bug — "Pair a bike" button width

User flagged the Pair card button wasn't full-width. Now wrapped in
`Modifier.fillMaxWidth()` matching all other Settings buttons (Mileage,
Service history, Allowlist, Inspector, etc.).

## Bug 5 (open): cluster preview shows "no a531 frames yet" even when service is producing them

Logs prove the nav producer is firing (`BikeBridge: nav frame: maneuver=8
dist=08.7K ready=false`) and `FrameStream.emit` is being called with
`Direction.TX` + 30 bytes + bytes[1]=0x31. ClusterState filters TX a531 from
`FrameStream.events` and should pick them up.

Hypothesis: race condition where ClusterState init happens AFTER frames have
been emitted, and SharedFlow only delivers to active subscribers. Tracing
added (`adb logcat -s ClusterState:V FrameStream:V`) to confirm. Likely fix:
either add `replay=8` to FrameStream's SharedFlow so a newly-subscribed
ClusterState sees recent frames, or hoist `latestNav` to a per-process
StateFlow that BikeBridgeService writes to directly.

## Data sources (clarification for user)

| Card on Home | Source while DEMO mode ON (default in dev) | Source on real bike |
|---|---|---|
| RIDING elapsed/distance/avg/max | DemoTelemetrySource sine-sweep | a537 from bike |
| Bike health gauge | computed from demo odo + fuel + ride list | computed from real telemetry + ride list |
| 🔥 ride streak | counted from Room rides created by demo source | real rides logged from a537 |
| Riding summary (today/week) | same | real rides |
| Quick destinations | user-set address strings | user-set address strings |

Demo mode toggle: Settings → Developer → Demo mode. Off by default in production;
left ON in dev so the UI is non-empty.

All bike-side data flows: bike → 0xFFF2 notify → a537 byte stream → 
`BleClient.notifications` → `TelemetryFrame.decode()` → `TelemetryRepository.update(frame)`
→ observed by Dashboard / Stats / RideLogger.
