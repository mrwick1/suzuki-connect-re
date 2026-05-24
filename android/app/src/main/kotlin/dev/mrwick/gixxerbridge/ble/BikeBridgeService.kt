package dev.mrwick.gixxerbridge.ble

import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.mrwick.gixxerbridge.GixxerApp
import dev.mrwick.gixxerbridge.R
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.nav.IdleClockGenerator
import dev.mrwick.gixxerbridge.nav.MapsNavSource
import dev.mrwick.gixxerbridge.nav.NavMux
import dev.mrwick.gixxerbridge.nav.WelcomeFrame
import dev.mrwick.gixxerbridge.protocol.IdentityFrame
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.protocol.decodeFrame
import dev.mrwick.gixxerbridge.system.DndController
import dev.mrwick.gixxerbridge.telemetry.DemoTelemetrySource
import dev.mrwick.gixxerbridge.telemetry.PhoneState
import dev.mrwick.gixxerbridge.telemetry.RideLogger
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import dev.mrwick.gixxerbridge.weather.WeatherCache
import dev.mrwick.gixxerbridge.weather.WeatherProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
    private lateinit var navMux: NavMux
    private lateinit var dnd: DndController
    private lateinit var rideLogger: RideLogger
    private val demoSource = DemoTelemetrySource()

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        settings = Settings(applicationContext)
        bleClient = BleClient(applicationContext)
        frameWriter = FrameWriter()
        phoneState = PhoneState(applicationContext).also { it.start() }
        idleClock = IdleClockGenerator()
        dnd = DndController(applicationContext)
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

        // NavMux pulls Maps nav (from NotificationCaptureService → MapsNavSource) and falls back to idle.
        val idleFlow = MutableStateFlow<NavFrame?>(null)
        navMux = NavMux(MapsNavSource.frame, kotlinx.coroutines.flow.flow {
            while (true) { emit(idleClock.build(weatherCache.currentEncoded().first, /* tempCelsius */ tempFromByte(weatherCache.currentEncoded().second))) ; delay(1_000) }
        })

        AppGraph.bleClient = bleClient
        AppGraph.frameWriter = frameWriter
        AppGraph.navMux = navMux

        // Ride persistence: TelemetryRepository.latest -> Room via RideLogger.
        rideLogger = RideLogger(
            store = RideStore(GixxerDatabase.get(applicationContext).rideDao()),
            telemetry = TelemetryRepository.latest,
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

        // Publish connection state to AppGraph for UI
        lifecycleScope.launch {
            bleClient.state.collect { AppGraph.publishConnectionState(it) }
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
                    }

                    ConnectionState.Idle,
                    ConnectionState.Connecting,
                    ConnectionState.Discovering,
                    -> { /* no-op */ }
                }
            }
        }

        // Heartbeat producer -> writer queue
        lifecycleScope.launch {
            heartbeatLoop.run { hb ->
                val bytes = hb.encode()
                frameWriter.enqueue(FrameWriter.Entry(FrameWriter.Priority.HEARTBEAT, bytes, "hb"))
            }
        }

        // Nav producer -> writer queue (only when changed; FrameWriter dedupes by content)
        lifecycleScope.launch {
            navMux.frame.distinctUntilChanged().collect { nav ->
                val bytes = nav.encode()
                frameWriter.enqueue(FrameWriter.Entry(FrameWriter.Priority.NAV, bytes, "nav"))
            }
        }

        // Writer drain: forever take + send via BleClient.write
        lifecycleScope.launch {
            while (true) {
                val entry = frameWriter.take()
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
        TelemetryRepository.reset()
        super.onDestroy()
    }

    private fun startInForeground() {
        val notification: Notification = NotificationCompat.Builder(this, GixxerApp.CHANNEL_BIKE_SERVICE)
            .setContentTitle(getString(R.string.bike_service_notification_title))
            .setSmallIcon(R.drawable.ic_notification)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
            .apply { flags = flags or Notification.FLAG_ONGOING_EVENT }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
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
    }
}
