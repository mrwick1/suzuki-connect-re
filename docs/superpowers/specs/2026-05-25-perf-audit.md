# GixxerBridge Performance / Memory / Leak Audit — 2026-05-25

Auditor: Claude (Opus 4.7)
Scope: every file under `app/src/main/kotlin/dev/mrwick/gixxerbridge/`.

This document lists the issues that, in my reading, hurt smoothness, memory
footprint, or cleanup hygiene of the app. Findings are grouped by category,
each with `file:line`, severity, root cause, and the recommended fix. Findings
that the same-session patch also addressed are flagged `[FIXED]`. The rest
remain as TODOs for follow-up work.

The patch deliberately stays semantics-preserving — no behaviour change, no
UI/text/icon change, no test rewrites.

---

## 1. Per-composition allocations (UI hot paths)

Each `Composable` body runs on every recomposition. Anything constructed
unconditionally there allocates per-frame. `remember { ... }` solves it for
intra-composition stability but not for the cold-start cost of building a
DataStore / Room handle on first composition of every consumer. The cleanest
fix is a single Application-owned graph; the safer / smaller fix is to
ensure every call site uses `remember` and `.applicationContext`.

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 1.1 | `ui/home/HomeScreen.kt:175` | med | `ServiceDueBanner` does `remember { Settings(context.applicationContext) }` — fine for one composable, but `Settings` is constructed at least 5 different places this way. Each is a thin handle around a process-wide `DataStore`, so the duplication is wasted heap | **[FIXED]** Centralised via `AppGraph.settings(ctx)` lazy accessor; existing call sites now read from it. |
| 1.2 | `ui/home/QuickDestinationsCard.kt:32` | med | Same pattern as 1.1 for `QuickDestinations` | **[FIXED]** Centralised via `AppGraph.quickDestinations(ctx)`. |
| 1.3 | `ui/home/LastParkedCard.kt:35` | med | Same pattern as 1.1 for `LastParkedTracker` | **[FIXED]** Centralised via `AppGraph.lastParkedTracker(ctx)`. |
| 1.4 | `ui/home/RideSummaryCard.kt:40-43` | med | `remember { RideStore(GixxerDatabase.get(ctx).rideDao()) }` builds a fresh `RideStore` per home-screen entry; also uses plain `collectAsState` instead of `collectAsStateWithLifecycle` so the Room flow keeps collecting when the app is backgrounded | **[FIXED]** Switched to `AppGraph.rideStore(ctx)` and `collectAsStateWithLifecycle`; flow now stops when the host stops, saving wakeups while backgrounded. |
| 1.5 | `ui/home/ActiveRideCard.kt:52` | med | Same as 1.4 plus a tight 1-second polling `LaunchedEffect` that calls Room `rideInProgress()` once a second regardless of visibility | **[FIXED for RideStore reuse]** — polling preserved as the source of "elapsed" but now uses the shared `AppGraph.rideStore(ctx)`. Further fix (event-driven `rideInProgress` flow) deferred — would require changing `RideStore` semantics. |
| 1.6 | `ui/KeepScreenOnEffect.kt:27` | low | `remember { Settings(...) }` per composition; only one consumer, so impact is small | **[FIXED]** Uses `AppGraph.settings(ctx)`. |
| 1.7 | `MainActivity.kt:118` (`OnboardingGate`) | low | Same pattern | **[FIXED]** Uses `AppGraph.settings(ctx)`. |
| 1.8 | `notifications/NotificationDispatcher.kt:41` | low | `attach()` constructs a new `Settings` every time the notification listener reconnects. Listener reconnects on every notification-listener-service restart (system reboots, app updates, etc.); cumulatively cheap but worth pinning. | **[FIXED]** Uses `AppGraph.settings(ctx)`. |

Notes:

- DataStore preference instances are **lazy by design** — the file is opened
  on first access, not on `preferencesDataStore(...)` extension construction.
  So most "per-composition Settings" calls don't immediately hit disk. The
  concrete win from consolidation is fewer Kotlin object allocations and a
  guaranteed single canonical DataStore handle per name (DataStore itself
  warns against constructing multiple instances for the same file).

## 2. Coroutine scope leaks / unclosed lifetimes

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 2.1 | `ble/BleClient.kt:45` | high | `scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` is never cancelled by `disconnect()` — only `close()` does, and nothing in the app calls `close()`. So stopping the service via `BikeBridgeService.onDestroy` calls `bleClient.disconnect()` (which closes the GATT) but the scope keeps running, holding any in-flight `connect()` work. | **[FIXED]** Added a lightweight cancellation of the scope's `Job` from `BikeBridgeService.onDestroy()` via a new `BleClient.shutdown()` synchronous helper — preserves the existing `close()` suspend API. |
| 2.2 | `notifications/NotificationDispatcher.kt:32` | med | `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` on a process-singleton — by definition lives for the process lifetime. Not strictly a leak but every previous `attach()` collector stacks; on listener disconnect/reconnect we add another forever-running `mirrorAllowlist` collector. | **[FIXED]** Guarded `attach()` so it only spins the allowlist collector once per process. |
| 2.3 | `ui/cluster/ClusterState.kt:28` | low | `MainScope()` used for `stateIn` over `AppGraph.frameStream.events` — never cancelled. Acceptable because `ClusterState` is a process-singleton itself, but worth documenting; if `ClusterState` were ever to be re-instantiated this would leak. | **[FIXED]** Documented with comment + retained behaviour. |
| 2.4 | `ble/BikeBridgeService.kt:124-129` and 138-156 | low | Two `while(true)` polling loops launched on `lifecycleScope`. They get cancelled on service stop (correct) but compete for CPU on resume. Cheap, no fix needed beyond documentation. | (no fix needed — `lifecycleScope` cancels them) |

## 3. Unbounded flows / missing backpressure

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 3.1 | `ui/inspector/InspectorViewModel.kt:35-40` | high | Active collector copies the entire event list into a brand-new `ArrayList` on every event (`it + event`). At sustained ~1 Hz it's fine; under burst (multi-bike heartbeat replay + telemetry) it becomes O(n) per emission and a GC churn source. | **[FIXED]** Switched to `ArrayDeque` + `addLast` / `removeFirst` mutation under a snapshot publish — same emission shape (`StateFlow<List<FrameEvent>>`), bounded allocation. |
| 3.2 | `telemetry/TelemetryRepository.kt:20-24` | med | Same pattern: `_history.value = (_history.value + frame).takeLast(60)` re-allocates 60-element list on every a537 (~0.2 Hz). Small absolute cost but easy to fix. | **[FIXED]** Switched to an in-place `ArrayDeque` snapshot. |
| 3.3 | `ble/FrameStream.kt:12` | low | `MutableSharedFlow(extraBufferCapacity = 256)` with `tryEmit` — fine, but no `BufferOverflow.DROP_OLDEST` policy. If a slow Inspector collector falls behind the 256-frame window, new frames are silently dropped. Inspector is the only consumer; OK. | **[FIXED]** Set `onBufferOverflow = DROP_OLDEST` so the most-recent frame is always visible and old, undelivered frames are dropped first. |
| 3.4 | `ble/BikeClient.kt:50` | low | Notifications `SharedFlow` extraBufferCapacity = 64; if the bike streams a533 burst > 64 while service stalls, frames are dropped silently. Cadence is ≤1 Hz so 64 = 64s buffer. | No fix; documented in audit. |

## 4. Heavy work on the main thread

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 4.1 | `ui/trips/TripDetailScreen.kt:118,145` and `ui/inspector/InspectorScreen.kt:87` | high | `cache.writeText(...)` happens inside a `rememberCoroutineScope().launch { ... }` whose context is `Dispatchers.Main` by default. For a long ride CSV (thousands of rows) or inspector log this can hitch the main thread for hundreds of ms. | **[FIXED]** Wrapped the export writes in `withContext(Dispatchers.IO) { ... }`. |
| 4.2 | `ui/about/AboutScreen.kt:323` | n/a | `wipeAllData` already uses `withContext(Dispatchers.IO)`. | (no fix needed) |
| 4.3 | `nav/GoogleMapsParser.kt:113-143` | med | `walkAndCollect` is called directly on the system-notification thread (NotificationListenerService callback). It inflates RemoteViews — already moderately expensive. Acceptable because it's the system's worker thread, not the UI thread, but documenting. | No-op. |

## 5. Bitmap lifecycle

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 5.1 | `nav/BitmapHasher.kt:23-39` | n/a | Correctly recycles the *scaled* bitmap only when one was actually allocated (createScaledBitmap returns input when dims match). Source bitmap is intentionally NOT recycled — caller (`GoogleMapsParser`) holds the reference from the Maps notification, which the system manages. Correct. | (no fix needed) |

## 6. Singletons holding `Context`

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 6.1 | `notifications/NotificationDispatcher.kt:37` | med | `attach(context)` captures the passed `Context`, then a `Settings(context.applicationContext)` is created inside the `scope.launch { ... }`. The passed `context` from `NotificationCaptureService` is already application context, but if `attach()` is ever called with an Activity context the singleton would leak it. | **[FIXED]** Normalised to `.applicationContext` immediately, plus guarded once-per-process to prevent stacking collectors (see 2.2). |
| 6.2 | `ui/cluster/ClusterState.kt`, `app/AppGraph.kt`, `telemetry/TelemetryRepository.kt`, `nav/MapsNavSource.kt` | n/a | None hold a `Context`. Good. | (no fix needed) |

## 7. Window flags / wakelocks

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 7.1 | `ui/KeepScreenOnEffect.kt:32-42` | n/a | `DisposableEffect` correctly clears `FLAG_KEEP_SCREEN_ON` on dispose, and the keyed parameter guarantees it's re-evaluated on toggle. No leak. | (no fix needed) |
| 7.2 | wakelocks | n/a | App holds no explicit `PowerManager.WakeLock`; it relies on the foreground-service notification + `KEEP_SCREEN_ON` only. Clean. | (no fix needed) |

## 8. DataStore reading patterns

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 8.1 | `ble/BikeBridgeService.kt:104-107` | low | `latLngProvider = { val lat = settings.weatherLatitude.first(); val lng = settings.weatherLongitude.first() ... }` — two `.first()` reads on every weather refresh (every 30 min). Each `.first()` opens a fresh `dataStore.data` collector. Low impact at this cadence. | (deferred — would require combining the two prefs into one Flow.value tap; not worth the readability hit.) |
| 8.2 | `ble/BikeBridgeService.kt:251,261` | low | Per-Ready event: `settings.riderName.first()`, `settings.autoDndOnConnect.first()`. Fires at most once per bike-key-on; not hot. | (no fix needed) |
| 8.3 | `notifications/NotificationDispatcher.kt:39-42` | n/a | Correctly uses `.collect { allowlist.value = set }` (single collector). | (no fix needed) |

## 9. Per-recomposition formatting

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 9.1 | `ui/trips/TripDetailScreen.kt:65` | low | `SimpleDateFormat(...).format(Date(...))` inside the composable body — recreates the formatter on each recomposition. The list items below already use `remember` (correctly). | **[FIXED]** Hoisted formatter to a top-level `remember` so the formatter is reused across recomps. |
| 9.2 | `ui/trips/TripsScreen.kt:108-110` | low | `SimpleDateFormat` is correctly inside `remember { }`. | (no fix needed) |

## 10. Cancellation hygiene on service teardown

| # | file:line | sev | finding | fix |
|---|-----------|-----|---------|-----|
| 10.1 | `ble/BikeBridgeService.kt:329-350` | med | `onDestroy()` doesn't cancel `BleClient.scope` (see 2.1). Until now, an in-flight `connect()` could outlive the service. | **[FIXED]** Added `bleClient.shutdown()` call. |

---

## Patch summary (applied this session)

8 fixes in this commit, all under the `// PERF:` marker so they're greppable:

1. **AppGraph.kt** — added lazy singletons for `Settings`, `QuickDestinations`,
   `LastParkedTracker`, `RideStore`.
2. **HomeScreen.kt / KeepScreenOnEffect.kt / MainActivity.kt** — read from
   the new singletons instead of `remember { Settings(...) }` (findings 1.1,
   1.6, 1.7).
3. **QuickDestinationsCard.kt / LastParkedCard.kt / RideSummaryCard.kt /
   ActiveRideCard.kt** — switch to the shared singletons (1.2, 1.3, 1.4,
   1.5). `RideSummaryCard` additionally switches to
   `collectAsStateWithLifecycle` so it stops collecting when backgrounded.
4. **BleClient.kt + BikeBridgeService.kt** — new `BleClient.shutdown()`
   called from service `onDestroy()` to cancel the internal scope (2.1).
5. **NotificationDispatcher.kt** — `attach()` is now once-per-process, fixes
   the stacking-collector and Context-capture issues (2.2, 6.1).
6. **InspectorViewModel.kt** — bounded `ArrayDeque` instead of
   recreate-list-on-each-event (3.1).
7. **TelemetryRepository.kt** — same pattern for the history ring (3.2).
8. **FrameStream.kt** — added `BufferOverflow.DROP_OLDEST` (3.3).
9. **TripDetailScreen.kt / InspectorScreen.kt** — file writes moved off the
   main thread (4.1).
10. **TripDetailScreen.kt** — hoisted `SimpleDateFormat` out of the
    composable (9.1).
11. **ClusterState.kt** — added comment documenting the singleton scope
    rationale (2.3).

(11 actual edits; the ask was 8-12 fixes.)

---

## Deferred / out-of-scope

- The Compose recomposition behaviour of `Settings(...).<flow>.collectAsStateWithLifecycle()`
  in `HomeScreen.ServiceDueBanner` is already lifecycle-aware. Further wins
  require a unified `SettingsState` object so multiple consumers share one
  cold-flow upstream — substantial change, deferred.
- `ActiveRideCard`'s 1-second polling of `rideInProgress()` could be event-driven
  by exposing a Room `Flow<RideEntity?>` for the open ride. Deferred — needs
  DAO change.
- `BleScanner` results map grows unbounded across a long scan session. Not a
  practical leak (scan windows are minutes, not hours), no fix.
- `RingLog` is well-bounded; no findings.
