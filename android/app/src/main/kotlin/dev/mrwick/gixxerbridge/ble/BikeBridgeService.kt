package dev.mrwick.gixxerbridge.ble

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.mrwick.gixxerbridge.GixxerApp
import dev.mrwick.gixxerbridge.MainActivity
import dev.mrwick.gixxerbridge.R
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.location.RideLocationTracker
import dev.mrwick.gixxerbridge.nav.IdleClockGenerator
import dev.mrwick.gixxerbridge.nav.MapsNavSource
import dev.mrwick.gixxerbridge.nav.NavMux
import dev.mrwick.gixxerbridge.nav.WelcomeFrame
import dev.mrwick.gixxerbridge.notifications.NowPlayingProvider
import dev.mrwick.gixxerbridge.protocol.IdentityFrame
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.protocol.decodeFrame
import dev.mrwick.gixxerbridge.safety.CrashDetector
import dev.mrwick.gixxerbridge.safety.SosController
import dev.mrwick.gixxerbridge.safety.SosScreen
import dev.mrwick.gixxerbridge.system.DndController
import dev.mrwick.gixxerbridge.telemetry.DemoTelemetrySource
import dev.mrwick.gixxerbridge.telemetry.PhoneState
import dev.mrwick.gixxerbridge.telemetry.RideLogger
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import dev.mrwick.gixxerbridge.weather.WeatherCache
import dev.mrwick.gixxerbridge.weather.WeatherProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    private lateinit var settings: Settings
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
        startInForeground()

        settings = Settings(applicationContext)
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
        lifecycleScope.launch {
            while (isActive) {
                nowPlayingProvider.refresh()
                delay(5_000)
            }
        }

        // NavMux pulls Maps nav (from NotificationCaptureService → MapsNavSource) and falls back to idle.
        //
        // Idle producer alternates every CYCLE_SECONDS=5 ticks (= 5s @ 1Hz):
        //   ticks 0..4  -> clock + weather
        //   ticks 5..9  -> now-playing (if available AND settings.nowPlayingOnCluster)
        // If now-playing is disabled or no track is active, we stay on clock+weather.
        // ASSUMED: 5-second alternation is the right cadence — short enough that the
        // rider sees the clock at a glance, long enough that they can read the track
        // title. Revisit after first on-cluster trial.
        val idleProducer = kotlinx.coroutines.flow.flow {
            var tick = 0
            while (true) {
                val track = nowPlayingProvider.track.value
                val wantNowPlaying = settings.nowPlayingOnCluster.first() && track != null
                val showNowPlaying = wantNowPlaying && (tick % (CYCLE_SECONDS * 2)) >= CYCLE_SECONDS
                val (wcode, tbyte) = weatherCache.currentEncoded()
                val frame = if (showNowPlaying) {
                    idleClock.buildNowPlaying(track!!.forCluster())
                } else {
                    idleClock.build(wcode, tempFromByte(tbyte))
                }
                emit(frame)
                delay(1_000)
                tick++
            }
        }
        navMux = NavMux(MapsNavSource.frame, idleProducer)

        AppGraph.bleClient = bleClient
        AppGraph.frameWriter = frameWriter
        AppGraph.navMux = navMux

        // Ride persistence: TelemetryRepository.latest -> Room via RideLogger.
        // GPS track recording starts/stops with the ride; tracker no-ops without
        // ACCESS_FINE_LOCATION so it's safe to always wire here.
        rideLogger = RideLogger(
            store = RideStore(GixxerDatabase.get(applicationContext).rideDao()),
            telemetry = TelemetryRepository.latest,
            locationTracker = RideLocationTracker(applicationContext),
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
            history = TelemetryRepository.history,
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
                try {
                    val frame = decodeFrame(bytes)
                    if (frame is TelemetryFrame) TelemetryRepository.update(frame)
                } catch (_: Throwable) { /* ignore malformed */ }
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
                            // Welcome a531 — NavMux will supersede on the next tick.
                            val (wcode, tbyte) = weatherCache.currentEncoded()
                            val welcome = WelcomeFrame.build(
                                name = name,
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

        // Heartbeat producer -> writer queue
        // PERF: gate on Ready — when the bike is disconnected, writes always fail
        // and the encode+enqueue+drain churn wastes wakeups on the foreground
        // service. The HeartbeatLoop still ticks at 1 Hz internally, but we drop
        // its frames on the floor until the link is live again.
        lifecycleScope.launch {
            heartbeatLoop.run { hb ->
                if (bleClient.state.value is ConnectionState.Ready) {
                    val bytes = hb.encode()
                    frameWriter.enqueue(FrameWriter.Entry(FrameWriter.Priority.HEARTBEAT, bytes, "hb"))
                }
            }
        }

        // Nav producer -> writer queue (only when changed; FrameWriter dedupes by content)
        // PERF: gate on Ready — same reasoning as the heartbeat above. NavMux
        // keeps producing (idle clock ticks forward locally), but we don't
        // serialise + enqueue frames the link can't deliver.
        lifecycleScope.launch {
            navMux.frame.distinctUntilChanged().collect { nav ->
                if (bleClient.state.value is ConnectionState.Ready) {
                    val bytes = nav.encode()
                    frameWriter.enqueue(FrameWriter.Entry(FrameWriter.Priority.NAV, bytes, "nav"))
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
                    delay(100)
                    continue
                }
                val ok = bleClient.write(entry.frame)
                AppGraph.frameStream.emit(FrameEvent(FrameEvent.Direction.TX, entry.frame, note = entry.note))
                if (!ok) Log.w(tag, "write failed: ${entry.note}")
            }
        }

        // Kick off the BLE connection
        lifecycleScope.launch {
            val mac = settings.bikeMac.first()
            if (mac != null) bleClient.connect(mac)
            else Log.w(tag, "no bike MAC set; service idle until pairing wizard saves one")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onDestroy() {
        demoSource.stop()
        // Best-effort: close out any open ride before lifecycleScope is cancelled.
        if (::rideLogger.isInitialized) {
            lifecycleScope.launch { rideLogger.endRide() }
        }
        phoneState.stop()
        weatherCache.stop()
        bleClient.disconnect()
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

    private fun startInForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(ConnectionState.Idle),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
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
     * Called by [CrashDetector] when a sudden-deceleration pattern is observed.
     *
     * Posts a high-priority "Crash detected — tap if OK" notification, launches the
     * full-screen [SosScreen] prompt (gives the rider a 10-second window to cancel),
     * then 10s later checks [SosScreen.okPressed]. If the rider didn't dismiss it,
     * fires the SOS SMS.
     *
     * ASSUMED: SosScreen launches reliably from the service context. On modern
     * Android, full-screen intents from services without USE_FULL_SCREEN_INTENT or
     * a foreground activity may be downgraded to a heads-up notification — we still
     * post the notification as a fallback path so the rider can tap it.
     */
    private fun onCrashSuspected() {
        // Reset the "I'm OK" flag before arming a new prompt.
        SosScreen.okPressed = false

        val n = NotificationCompat.Builder(applicationContext, GixxerApp.CHANNEL_BIKE_SERVICE)
            .setContentTitle("Crash detected — tap if you're OK")
            .setContentText("SOS will fire in ${SosScreen.COUNTDOWN_SECONDS} seconds otherwise.")
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SosController.NOTIF_ID_CRASH_PROMPT, n)

        // Launch the full-screen prompt activity.
        val intent = Intent(applicationContext, SosScreen::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            Log.w(tag, "failed to launch SosScreen: ${t.message}")
        }

        lifecycleScope.launch {
            delay(SosScreen.COUNTDOWN_SECONDS * 1_000L)
            // Cancel the in-tray prompt either way.
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .cancel(SosController.NOTIF_ID_CRASH_PROMPT)
            if (!SosScreen.okPressed) {
                val contact = settings.emergencyContactPhone.first()
                sos.fire(contact, sos.lastKnownLocation())
            }
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
        // ASSUMED: 5-second cycle window for clock <-> now-playing alternation.
        private const val CYCLE_SECONDS = 5
    }
}
