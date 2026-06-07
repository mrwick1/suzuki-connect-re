package dev.mrwick.gixxerbridge.ble

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.mrwick.gixxerbridge.GixxerApp
import dev.mrwick.gixxerbridge.MainActivity
import dev.mrwick.gixxerbridge.R
import dev.mrwick.gixxerbridge.app.AppEvent
import dev.mrwick.gixxerbridge.app.AppEvents
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.analytics.RangeEstimator
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.Greetings
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.location.RideLocationTracker
import dev.mrwick.gixxerbridge.nav.IdleClockGenerator
// PARKED: Google Maps nav shelved — MapsNavSource no longer wired into NavMux.
// import dev.mrwick.gixxerbridge.nav.MapsNavSource
import dev.mrwick.gixxerbridge.nav.NavMux
import dev.mrwick.gixxerbridge.nav.WelcomeFrame
import dev.mrwick.gixxerbridge.notifications.NowPlayingProvider
import dev.mrwick.gixxerbridge.protocol.IdentityFrame
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.protocol.decodeFrame
import dev.mrwick.gixxerbridge.safety.CrashDetector
import dev.mrwick.gixxerbridge.safety.SosAlarmReceiver
import dev.mrwick.gixxerbridge.safety.SosController
import dev.mrwick.gixxerbridge.safety.SosScreen
import dev.mrwick.gixxerbridge.system.DndController
import dev.mrwick.gixxerbridge.telemetry.DemoTelemetrySource
import dev.mrwick.gixxerbridge.telemetry.PhoneState
import dev.mrwick.gixxerbridge.telemetry.RideLogger
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import dev.mrwick.gixxerbridge.weather.WeatherCache
import dev.mrwick.gixxerbridge.util.AppLog
import dev.mrwick.gixxerbridge.weather.WeatherProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service holding the BLE link to the bike.
 * Composition root for the runtime graph.
 *
 * Launch rules (assumptions log A14):
 *   - Started by user-tap in MainActivity, or by BootCompletedReceiver if user enabled auto-start.
 *   - Once started, [BleClient]'s autoConnect=true handles bike key-on / disappearance.
 */
class BikeBridgeService : LifecycleService() {

    private val tag = "BikeBridge"
    /** False until onCreate has gone foreground and built its collaborators.
     *  Gates onDestroy so the graceful bail-out path never touches lateinit
     *  fields that were never constructed. */
    private var fullyStarted = false
    private lateinit var settings: Settings
    private lateinit var greetings: Greetings
    private lateinit var bleClient: BleClient
    private lateinit var frameWriter: FrameWriter
    private lateinit var phoneState: PhoneState
    private lateinit var weatherCache: WeatherCache
    private lateinit var heartbeatLoop: HeartbeatLoop
    private lateinit var idleClock: IdleClockGenerator
    private lateinit var nowPlayingProvider: NowPlayingProvider
    private lateinit var navMux: NavMux
    private lateinit var dnd: DndController
    private lateinit var lastParked: dev.mrwick.gixxerbridge.location.LastParkedTracker
    private lateinit var rideLogger: RideLogger
    private lateinit var sos: SosController
    private lateinit var crashDetector: CrashDetector
    private var crashDetectorJob: Job? = null
    private val demoSource = DemoTelemetrySource()

    // PERF: This service is fully decoupled from the hosting Activity's lifecycle so
    // it survives MainActivity going to background (e.g. when the user hits "back" on
    // Home and we call moveTaskToBack(true) instead of finish()).
    //   - startInForeground() is called in onCreate() below, giving us a sticky
    //     foreground notification (NOTIFICATION_ID=1, type CONNECTED_DEVICE).
    //   - onStartCommand returns START_STICKY so the OS will restart us if it
    //     reclaims memory while we're backgrounded.
    //   - Every collaborator is constructed with applicationContext (settings,
    //     bleClient, phoneState, weatherCache, nowPlayingProvider, dnd, lastParked,
    //     rideLogger via Room, sos, crashDetector, lastParked.snapshotOnDisconnect).
    //     No Activity / Window / Composable references are captured here, so the
    //     UI process going to back doesn't leak or stall the service.
    //   - The only Activity ref is the PendingIntent target in buildNotification,
    //     which is just a launch intent — no live Activity reference is held.
    override fun onCreate() {
        super.onCreate()
        // Defense-in-depth: if we were started without the BLE runtime
        // permission (stale START_STICKY restart, an ungated caller, etc.),
        // going foreground as a connectedDevice service throws SecurityException
        // on Android 14+. Bail gracefully instead of crashing the process.
        if (!startInForeground()) {
            AppLog.w(tag, "BLE permission absent — not going foreground; stopping service")
            stopSelf()
            return
        }
        fullyStarted = true

        settings = Settings(applicationContext)
        greetings = Greetings(applicationContext)
        bleClient = BleClient(applicationContext)

        // Keep the foreground notification text in sync with the connection lifecycle so
        // the user can glance at the shade and know whether the bike link is live. Tapping
        // the notification opens MainActivity (wired in buildNotification via PendingIntent).
        lifecycleScope.launch {
            bleClient.state.collect { state ->
                NotificationManagerCompat.from(this@BikeBridgeService)
                    .notify(NOTIFICATION_ID, buildNotification(state))
            }
        }
        frameWriter = FrameWriter()
        phoneState = PhoneState(applicationContext).also { it.start() }
        idleClock = IdleClockGenerator()
        nowPlayingProvider = NowPlayingProvider(applicationContext)
        dnd = DndController(applicationContext)
        lastParked = dev.mrwick.gixxerbridge.location.LastParkedTracker(applicationContext)
        weatherCache = WeatherCache(
            provider = WeatherProvider(),
            latLngProvider = {
                val lat = settings.weatherLatitude.first()
                val lng = settings.weatherLongitude.first()
                if (lat != null && lng != null) lat to lng else DEFAULT_LATLNG
            },
        ).also { it.start(lifecycleScope) }

        heartbeatLoop = HeartbeatLoop(
            phoneBatteryProvider = { phoneState.batteryPercent.value to phoneState.isCharging.value },
            signalBarsProvider = { phoneState.signalBars.value },
            smsPendingProvider = { false },   // wired by NotificationDispatcher later if needed
            callPendingProvider = { false },
            weatherProvider = { weatherCache.currentEncoded() },
        )

        // Sample the active media session every 5s; cheap, idempotent, and only
        // does real work when a NotificationListenerService is enabled. The 5s
        // cadence matches the cycle alternation window below so a freshly-paused
        // track clears within one cycle.
        // ASSUMED: 5s sampling is responsive enough; revisit if rider notices lag
        // between hitting "play" and the cluster swap.
        // PERF (battery): only poll the active media session while the bike link is
        // Ready — that's the only consumer that matters (the cluster's now-playing
        // tile). Suspends with no MediaSession queries while disconnected.
        lifecycleScope.launch {
            while (isActive) {
                bleClient.state.first { it is ConnectionState.Ready }
                nowPlayingProvider.refresh()
                delay(5_000)
            }
        }

        // NavMux normally muxes Maps nav over the idle clock, but Maps nav is
        // PARKED (2026-06-04) — the maps slot is fed constant-null below, so only
        // the idle producer reaches the cluster.
        //
        // Idle producer rotates every CYCLE_SECONDS=5 ticks (= 5s @ 1Hz):
        //   slot CLOCK      -> clock + weather (always present)
        //   slot NOW_PLAYING -> now-playing (optional; gated on settings + active track)
        //   slot RANGE       -> estimated range remaining (optional; gated on settings)
        // Optional slots collapse out when their toggle is off / no data, so the
        // clock is always shown. With all three on, a full cycle is 15 s.
        // ASSUMED: 5-second alternation is the right cadence — short enough that the
        // rider sees the clock at a glance, long enough that they can read the info.
        // Revisit after first on-cluster trial.

        // km-per-fuel-bar derived from this rider's history (median over rides
        // with both fuelBarsStart/End). Null until enough history exists; falls
        // back to RangeEstimator.FALLBACK_KM_PER_BAR (ASSUMED ~50, flagged).
        val rideStoreForRange = RideStore(GixxerDatabase.get(applicationContext).rideDao())
        val kmPerBarFlow = rideStoreForRange.observeRides()
            .map { RangeEstimator.kmPerBar(it) }
            .stateIn(lifecycleScope, SharingStarted.Eagerly, null)

        val idleProducer = kotlinx.coroutines.flow.flow {
            var tick = 0
            while (true) {
                val track = nowPlayingProvider.track.value
                val wantNowPlaying = settings.nowPlayingOnCluster.first() && track != null
                val wantRange = settings.rangeOnCluster.first()

                // Rotation order, each CYCLE_SECONDS long: clock -> [now-playing] ->
                // [range]. Optional slots collapse out when their toggle is off /
                // no data, so the clock is always shown.
                val slots = buildList {
                    add(IdleSlot.CLOCK)
                    if (wantNowPlaying) add(IdleSlot.NOW_PLAYING)
                    if (wantRange) add(IdleSlot.RANGE)
                }
                val slot = slots[(tick / CYCLE_SECONDS) % slots.size]
                val (wcode, tbyte) = weatherCache.currentEncoded()
                val frame = when (slot) {
                    IdleSlot.NOW_PLAYING -> idleClock.buildNowPlaying(track!!.forCluster())
                    IdleSlot.RANGE -> {
                        val bars = TelemetryRepository.latest.value?.fuelBars
                        // Use real km/bar history; show "----" when no history yet
                        // rather than a number the rider might mistake for measured.
                        // FALLBACK_KM_PER_BAR (50.0) is ASSUMED/anecdotal — only
                        // used when kmPerBarFlow.value is non-null (it won't be
                        // until history exists; the null guard below returns null →
                        // buildRange emits "----" safely).
                        val kmPerBar = kmPerBarFlow.value
                        idleClock.buildRange(RangeEstimator.estimateRemainingKm(bars, kmPerBar))
                    }
                    IdleSlot.CLOCK -> idleClock.build(wcode, tempFromByte(tbyte))
                }
                emit(frame)
                delay(1_000)
                tick++
            }
        }
        // PARKED (2026-06-04): Google Maps navigation is shelved. The
        // notification-scrape -> guessed-Mappls-ID pipeline produced wrong
        // cluster arrows, so we feed NavMux a constant-null maps slot and it
        // always falls through to the idle clock (clock / weather / now-playing
        // are unaffected). To revive: pass `MapsNavSource.frame` here again and
        // re-enable the Maps branch in NotificationDispatcher. The planned
        // replacement drives nav from the Mappls Navigation SDK instead.
        navMux = NavMux(kotlinx.coroutines.flow.flowOf(null), idleProducer)

        AppGraph.bleClient = bleClient
        AppGraph.frameWriter = frameWriter
        AppGraph.navMux = navMux

        // Ride persistence: TelemetryRepository.latest -> Room via RideLogger.
        // GPS track recording starts/stops with the ride; tracker no-ops without
        // ACCESS_FINE_LOCATION so it's safe to always wire here.
        val rideLocationTracker = RideLocationTracker(applicationContext)
        rideLogger = RideLogger(
            store = RideStore(GixxerDatabase.get(applicationContext).rideDao()),
            telemetry = TelemetryRepository.latest,
            locationTracker = rideLocationTracker,
            onRideEnded = { rideId -> AppGraph.publishLastFinishedRideId(rideId) },
        )
        rideLogger.attach(lifecycleScope)

        // Demo mode: when ON, synthesise a fake a537 stream so the Dashboard /
        // Trips screens work without a bike. When OFF, stop the source — real
        // BLE telemetry (if any) takes over via the notifications collector below.
        lifecycleScope.launch {
            settings.demoMode.distinctUntilChanged().collect { on ->
                if (on) demoSource.start(lifecycleScope) else demoSource.stop()
            }
        }

        // Safety: SOS controller is always available (used by Settings test button
        // and by the auto-fire path below). CrashDetector is opt-in — only run while
        // settings.crashDetectionEnabled is true.
        sos = SosController(applicationContext)
        crashDetector = CrashDetector(
            context = applicationContext,
            // Prefer GPS ground speed (independent of BLE telemetry); fall back to
            // the bike's reported speed when no GPS fix is active.
            latestSpeedKmh = {
                rideLocationTracker.samples.value?.speedMps?.let { (it * 3.6f).toInt() }
                    ?: TelemetryRepository.latest.value?.speedKmh
            },
            onCrashSuspected = ::onCrashSuspected,
        )
        lifecycleScope.launch {
            settings.crashDetectionEnabled.distinctUntilChanged().collect { on ->
                crashDetectorJob?.cancel()
                crashDetectorJob = if (on) {
                    lifecycleScope.launch { crashDetector.run() }
                } else {
                    null
                }
            }
        }

        // Publish connection state to AppGraph for UI
        lifecycleScope.launch {
            bleClient.state.collect { AppGraph.publishConnectionState(it) }
        }

        // Publish Device Information Service snapshot once read by BleClient.
        lifecycleScope.launch {
            bleClient.bikeInfo.collect { info ->
                if (info != null) AppGraph.publishBikeInfo(info)
            }
        }

        // Telemetry sink: every notify byte[] -> try-decode -> TelemetryRepository
        lifecycleScope.launch {
            bleClient.notifications.collect { bytes ->
                AppGraph.frameStream.emit(FrameEvent(FrameEvent.Direction.RX, bytes))
                // Diag-log every notify so the user can see if real bike telemetry is
                // arriving (vs demo source). Log just the type byte + size to keep it
                // skim-friendly in the Diagnostics screen.
                val typeHex = if (bytes.size >= 2) "0x${"%02x".format(bytes[1].toInt() and 0xFF)}" else "??"
                AppLog.i(tag, "RX notify type=$typeHex size=${bytes.size}")
                // Real-bike-beats-demo: if an a537 arrives while demo is still ON,
                // flip demo off so the Dashboard stops mixing synthetic and real
                // values. We re-check demoMode every a537 (not once-per-session) so
                // a manual re-enable while connected gets overridden by the next
                // real frame — by design, see task spec.
                if (bytes.size >= 2 && (bytes[1].toInt() and 0xFF) == 0x37 && settings.demoMode.first()) {
                    settings.setDemoMode(false)
                    AppLog.i(tag, "auto-disabled Demo mode — first real a537 received")
                    AppEvents.emit(AppEvent.DemoModeAutoDisabled)
                }
                try {
                    val frame = decodeFrame(bytes)
                    if (frame is TelemetryFrame) TelemetryRepository.update(frame)
                } catch (t: Throwable) {
                    // Don't swallow silently: an undecoded notify may be a new/unknown
                    // bike frame type worth investigating (project rule — absence of
                    // evidence is not evidence of absence). Log type + size + reason.
                    AppLog.w(tag, "RX decode failed type=$typeHex size=${bytes.size}: ${t.message}")
                }
            }
        }

        // On Ready: send identity, fire one-shot welcome frame, flip DND.
        // On Disconnected: restore the user's previous DND filter.
        //
        // "Fresh" is defined as: this Ready transition wasn't immediately preceded
        // by another Ready (i.e. it's not a no-op re-emission). We treat every
        // Idle->...->Ready cycle as fresh; the auto-reconnect path also passes
        // through Disconnected/Connecting on the way back, so we'd consider those
        // fresh too. ASSUMED: this is acceptable — rider sees a "Hi" once per
        // bike-key-on event, which is the intended UX, not "only on first-ever".
        lifecycleScope.launch {
            var lastWasReady = false
            bleClient.state.collect { state ->
                when (state) {
                    is ConnectionState.Ready -> {
                        val isFresh = !lastWasReady
                        lastWasReady = true

                        val name = settings.riderName.first()
                        val identity = IdentityFrame(name = name, isFresh = isFresh).encode()
                        frameWriter.enqueue(
                            FrameWriter.Entry(FrameWriter.Priority.URGENT, identity, "identity"),
                        )

                        if (isFresh) {
                            // Welcome a531 — pick a random user-defined greeting
                            // (with {name} substitution). NavMux supersedes on
                            // the next tick.
                            val (wcode, tbyte) = weatherCache.currentEncoded()
                            val pool = greetings.list.first()
                            val welcome = WelcomeFrame.buildRandom(
                                name = name,
                                greetings = pool,
                                tempCelsius = tempFromByte(tbyte),
                                suzukiWeatherCode = wcode,
                            ).encode()
                            frameWriter.enqueue(
                                FrameWriter.Entry(FrameWriter.Priority.URGENT, welcome, "welcome"),
                            )

                            if (settings.autoDndOnConnect.first()) dnd.activate()
                        }
                    }

                    is ConnectionState.Disconnected,
                    is ConnectionState.Failed,
                    -> {
                        lastWasReady = false
                        dnd.restore()
                        // End any active ride; the watchdog would catch this in
                        // <= 10 min, but ending immediately on disconnect gives
                        // cleaner per-key-on ride boundaries.
                        rideLogger.endRide()
                        // Snapshot phone GPS as the bike's likely parking spot.
                        // Rider can later see "Bike was last parked here" on Home.
                        lifecycleScope.launch { lastParked.snapshotOnDisconnect() }
                    }

                    ConnectionState.Idle,
                    ConnectionState.Connecting,
                    ConnectionState.Discovering,
                    -> { /* no-op */ }
                }
            }
        }

        // Persist a last-telemetry snapshot (odo / bars / km-L) so the Home fuel
        // estimate works while the bike is disconnected. Throttled to odometer
        // changes - the odo advances ~once/km, so this writes a few times/min at
        // most, not at the 1 Hz telemetry cadence.
        lifecycleScope.launch {
            var lastPersistedOdo = -1
            TelemetryRepository.latest.collect { frame ->
                if (frame == null) return@collect
                if (frame.odometerKm != lastPersistedOdo) {
                    lastPersistedOdo = frame.odometerKm
                    settings.setLastTelemetry(
                        odometerKm = frame.odometerKm,
                        fuelBars = frame.fuelBars,
                        kmPerL = frame.fuelEconKmlV2,
                    )
                }
            }
        }

        // PERF: extended-disconnect weather pause. If the bike hasn't been Ready
        // for 24h, the user almost certainly isn't riding and the cluster isn't
        // looking at the cached forecast — stop the 30-min open-meteo poll
        // entirely. Resume immediately on the next Ready transition so the
        // welcome frame still has a fresh value.
        // ASSUMED: 24h is the right idle window — long enough to skip overnight
        // and "left phone at desk" gaps, short enough that the first ride of
        // the next day still gets a fresh forecast within the first poll cycle.
        lifecycleScope.launch {
            val idlePauseMs = 24 * 60 * 60 * 1000L
            var pauseJob: Job? = null
            bleClient.state.collect { state ->
                if (state is ConnectionState.Ready) {
                    pauseJob?.cancel()
                    pauseJob = null
                    weatherCache.resume()
                } else if (pauseJob == null) {
                    pauseJob = launch {
                        delay(idlePauseMs)
                        if (bleClient.state.value !is ConnectionState.Ready) {
                            weatherCache.pause()
                        }
                    }
                }
            }
        }

        // Heartbeat producer -> writer queue.
        // PERF (battery): fully idle-gate. The old code ran HeartbeatLoop at 1 Hz
        // unconditionally and only dropped frames at enqueue, so it kept building a
        // frame (phone-battery + weather reads) every second 24/7 even with no bike
        // paired. Now we suspend on the state flow until Ready — zero wakeups /
        // zero reads while disconnected — then beat at 1 Hz; the next Ready re-arms.
        lifecycleScope.launch {
            while (isActive) {
                bleClient.state.first { it is ConnectionState.Ready }
                val bytes = heartbeatLoop.buildFrame().encode()
                frameWriter.enqueue(FrameWriter.Entry(FrameWriter.Priority.HEARTBEAT, bytes, "hb"))
                delay(HeartbeatLoop.HEARTBEAT_INTERVAL_MS)
            }
        }

        // Nav producer -> writer queue (only when changed; FrameWriter dedupes by content)
        // PERF: gate the BLE write on Ready — NavMux keeps producing (idle clock
        // ticks forward locally), but we don't push to the radio when the link
        // can't deliver.
        // UX: always publish to FrameStream as a preview-TX event so the in-app
        // cluster preview / Inspector see what we WOULD send even when no bike
        // is connected. Without this the preview is silent on first launch
        // before pairing, which looks broken.
        lifecycleScope.launch {
            Log.i(tag, "nav producer collector starting")
            navMux.frame.distinctUntilChanged().collect { nav ->
                val bytes = nav.encode()
                val ready = bleClient.state.value is ConnectionState.Ready
                Log.i(tag, "nav frame: maneuver=${nav.maneuverId} dist=${nav.distNext}${nav.distNextUnit} ready=$ready")
                if (ready) {
                    frameWriter.enqueue(FrameWriter.Entry(FrameWriter.Priority.NAV, bytes, "nav"))
                } else {
                    AppGraph.frameStream.emit(
                        FrameEvent(FrameEvent.Direction.TX, bytes, note = "nav-preview"),
                    )
                }
            }
        }

        // Writer drain: forever take + send via BleClient.write
        // PERF: when the link isn't Ready, back off 100 ms before retrying so a
        // queue full of stale frames doesn't busy-loop hammering bleClient.write
        // (which returns false fast). Producers above are also gated, so this
        // mostly catches in-flight frames enqueued just before a disconnect.
        lifecycleScope.launch {
            while (true) {
                val entry = frameWriter.take()
                if (bleClient.state.value !is ConnectionState.Ready) {
                    // Hold the dequeued entry *locally* until Ready rather than
                    // re-enqueueing it: re-enqueue would re-run NAV dedup and could
                    // reorder this URGENT frame behind newer ones. Wait out brief
                    // non-Ready windows, but give up after a bound so a permanently
                    // down link doesn't wedge the drain — a stale frame is worthless
                    // once the link returns (identity is re-sent fresh on each Ready).
                    var waitedMs = 0
                    while (bleClient.state.value !is ConnectionState.Ready && waitedMs < 3_000) {
                        delay(100); waitedMs += 100
                    }
                    if (bleClient.state.value !is ConnectionState.Ready) {
                        AppLog.d(tag, "writer: dropping stale ${entry.note} (link not Ready after ${waitedMs}ms)")
                        continue
                    }
                }
                val ok = bleClient.write(entry.frame)
                AppGraph.frameStream.emit(FrameEvent(FrameEvent.Direction.TX, entry.frame, note = entry.note))
                if (!ok) AppLog.w(tag, "writer: write failed for ${entry.note} (type=0x${"%02x".format(entry.frame[1].toInt() and 0xFF)})")
                else AppLog.i(tag, "writer: TX ${entry.note} type=0x${"%02x".format(entry.frame[1].toInt() and 0xFF)}")
            }
        }

        // Observe paired-bike MAC. Reconnect whenever the user picks a (different) bike
        // from the pair wizard, or disconnect if they clear it. Previously this only
        // read .first() once at onCreate, so a fresh pair while the service was already
        // running was silently ignored — the MAC was saved to DataStore but nothing
        // ever connected, and the pair screen looked like a no-op to the user.
        lifecycleScope.launch {
            settings.bikeMac.distinctUntilChanged().collect { mac ->
                if (mac != null) {
                    AppLog.i(tag, "bikeMac=$mac → bleClient.connect()")
                    bleClient.connect(mac)
                } else {
                    AppLog.i(tag, "bikeMac cleared → bleClient.disconnect()")
                    bleClient.disconnect()
                }
            }
        }
        // Mirror BleClient state transitions to AppLog so the user can see the full
        // connect lifecycle in the Diagnostics screen even when off-tether from adb.
        lifecycleScope.launch {
            // StateFlow already deduplicates so no distinctUntilChanged() needed.
            bleClient.state.collect { s ->
                AppLog.i(tag, "ConnectionState → $s")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // PERF/reliability: START_STICKY tells the OS to re-create this service
        // with a null intent if it gets killed for memory. `onCreate` above is
        // safe to re-run on a fresh process — every collaborator is freshly
        // constructed there, no static singletons hold stale references (the
        // only cross-process state is on-disk DataStore / Room, and AppGraph
        // mutable refs which are re-published on each onCreate).
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // PERF/reliability: when the user swipes the app away from Recents the
        // system may kill our foreground service shortly after (vendor OEMs are
        // especially aggressive here). Schedule an exact alarm to re-launch
        // ourselves in ~1s so the bike connection stays up even when the app
        // task is dismissed.
        //
        // ASSUMED: SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM are granted (declared
        // in the manifest). On Android 12+ USE_EXACT_ALARM is auto-granted for
        // sideloaded apps; if neither is available the alarm scheduler will
        // throw SecurityException — we swallow it so the swipe-away itself
        // doesn't crash the dying process.
        try {
            val restart = Intent(this, BikeBridgeService::class.java).setPackage(packageName)
            val pi = PendingIntent.getForegroundService(
                this,
                0,
                restart,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            am.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + 1_000,
                pi,
            )
        } catch (t: Throwable) {
            Log.w(tag, "onTaskRemoved restart-alarm failed: ${t.message}")
        }
    }

    override fun onDestroy() {
        if (!fullyStarted) {
            // We bailed in onCreate before constructing collaborators; touching
            // the lateinit fields below would itself crash. Nothing to tear down.
            super.onDestroy()
            return
        }
        demoSource.stop()
        // Best-effort: close out any open ride before lifecycleScope is cancelled.
        if (::rideLogger.isInitialized) {
            lifecycleScope.launch { rideLogger.endRide() }
        }
        phoneState.stop()
        weatherCache.stop()
        bleClient.disconnect()
        // PERF: also cancel BleClient's internal scope so any in-flight
        // connect()/readDeviceInfo() coroutines are torn down instead of
        // leaking until process death (audit finding 2.1 / 10.1).
        bleClient.shutdown()
        // Make sure we never leave the phone stuck in PRIORITY-only DND if the
        // service is torn down without going through Disconnected first.
        if (::dnd.isInitialized) dnd.restore()
        AppGraph.bleClient = null
        AppGraph.frameWriter = null
        AppGraph.navMux = null
        // Reset the public connection-state flow so the Home screen's
        // start/stop button text reflects "service is gone" (Idle) instead of
        // sticking on the last observed state (e.g. Disconnected/Failed/Ready).
        AppGraph.publishConnectionState(ConnectionState.Idle)
        TelemetryRepository.reset()
        super.onDestroy()
    }

    /** Returns true if the foreground state was established. Returns false
     *  WITHOUT throwing when the BLE permission is missing or the OS rejects the
     *  start — the caller must then stop the service (we never crash). */
    private fun startInForeground(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.BLUETOOTH_CONNECT,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AppLog.w(tag, "BLUETOOTH_CONNECT not granted; skipping connectedDevice foreground")
            return false
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(ConnectionState.Idle),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
            true
        } catch (t: Throwable) {
            AppLog.e(tag, "startForeground(connectedDevice) failed", t)
            false
        }
    }

    /**
     * Build the foreground-service notification reflecting the current [ConnectionState].
     *
     * Title/body change with state so a glance at the shade tells the rider whether we're
     * connecting, connected, or auto-reconnecting after a drop. Tapping opens MainActivity.
     */
    private fun buildNotification(state: ConnectionState): Notification {
        val title = when (state) {
            ConnectionState.Idle,
            ConnectionState.Connecting,
            ConnectionState.Discovering,
            -> "Connecting to bike…"
            ConnectionState.Ready -> "Bike connected"
            is ConnectionState.Disconnected -> "Disconnected — auto-reconnecting"
            is ConnectionState.Failed -> "Connection failed"
        }
        val text = when (state) {
            ConnectionState.Ready -> "Tap to view dashboard"
            is ConnectionState.Failed -> state.reason
            else -> ""
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, GixxerApp.CHANNEL_BIKE_SERVICE)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
            .apply { flags = flags or Notification.FLAG_ONGOING_EVENT }
    }

    /**
     * Called by [CrashDetector] when an impact + stillness pattern is observed.
     *
     * Robustness changes:
     *  - Arms a *persisted* SOS countdown ([Settings.sosArmed]) and schedules an
     *    AlarmManager exact alarm at the deadline, so the SOS fires even if this
     *    process is killed during the countdown (the old in-process `delay` died
     *    with the process — the worst case in a real crash).
     *  - Shows the cancel UI via a HIGH-importance full-screen-intent notification
     *    (promotes from the background, where a bare `startActivity` is dropped).
     *  - The rider's "I'm OK" disarms [Settings.sosArmed]; the alarm then no-ops.
     */
    private fun onCrashSuspected() {
        SosScreen.okPressed = false
        lifecycleScope.launch { settings.setSosArmed(true) }

        // Schedule the firing alarm (survives process death).
        val firePending = PendingIntent.getBroadcast(
            applicationContext,
            SosAlarmReceiver.REQUEST_CODE,
            Intent(applicationContext, SosAlarmReceiver::class.java)
                .setAction(SosAlarmReceiver.ACTION_FIRE_SOS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val deadline = SystemClock.elapsedRealtime() + SosScreen.COUNTDOWN_SECONDS * 1_000L
        val am = getSystemService(AlarmManager::class.java)
        var alarmScheduled = false
        try {
            am?.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, firePending)
            alarmScheduled = am != null
        } catch (t: SecurityException) {
            AppLog.w(tag, "exact alarm denied — falling back to in-process timer: ${t.message}")
        }
        // Fallback in-process timer if exact alarms aren't permitted on this device.
        if (!alarmScheduled) {
            lifecycleScope.launch {
                delay(SosScreen.COUNTDOWN_SECONDS * 1_000L)
                if (settings.sosArmed.first()) {
                    sos.fire(settings.emergencyContactPhone.first(), sos.freshLocation())
                    settings.setSosArmed(false)
                }
            }
        }

        // Full-screen-intent prompt on the HIGH crash channel.
        val screenIntent = Intent(applicationContext, SosScreen::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val screenPending = PendingIntent.getActivity(
            applicationContext,
            1,
            screenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(applicationContext, GixxerApp.CHANNEL_CRASH_ALERT)
            .setContentTitle("Crash detected — tap if you're OK")
            .setContentText("SOS will fire in ${SosScreen.COUNTDOWN_SECONDS} seconds otherwise.")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(screenPending, true)
            .setAutoCancel(true)
            .setTimeoutAfter((SosScreen.COUNTDOWN_SECONDS + 2) * 1_000L)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SosController.NOTIF_ID_CRASH_PROMPT, n)

        // Direct launch too (covers the foregrounded-app case immediately).
        try {
            startActivity(screenIntent)
        } catch (t: Throwable) {
            Log.w(tag, "direct SosScreen launch failed (full-screen intent covers it): ${t.message}")
        }
    }

    /** Decode the a533 byte 22 encoding back to °C, for the idle clock generator. */
    private fun tempFromByte(b: Int): Double? {
        if (b == 0) return null
        val f = b - 115.0
        return (f - 32.0) * 5.0 / 9.0
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        // ASSUMED: Bangalore as default weather location; overridden by Settings.weatherLatitude/Longitude.
        private val DEFAULT_LATLNG = 12.97 to 77.59
        // ASSUMED: 5-second cycle window for clock / now-playing / range rotation.
        private const val CYCLE_SECONDS = 5
    }
}

/** Slots in the cluster idle-frame rotation. */
private enum class IdleSlot { CLOCK, NOW_PLAYING, RANGE }
