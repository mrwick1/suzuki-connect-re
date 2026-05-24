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
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.nav.IdleClockGenerator
import dev.mrwick.gixxerbridge.nav.MapsNavSource
import dev.mrwick.gixxerbridge.nav.NavMux
import dev.mrwick.gixxerbridge.protocol.IdentityFrame
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.protocol.decodeFrame
import dev.mrwick.gixxerbridge.telemetry.PhoneState
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

    override fun onCreate() {
        super.onCreate()
        startInForeground()

        settings = Settings(applicationContext)
        bleClient = BleClient(applicationContext)
        frameWriter = FrameWriter()
        phoneState = PhoneState(applicationContext).also { it.start() }
        idleClock = IdleClockGenerator()
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

        // On Ready: send identity once, then start heartbeat + nav drains
        lifecycleScope.launch {
            bleClient.state.collect { state ->
                if (state is ConnectionState.Ready) {
                    val name = settings.riderName.first()
                    val identity = IdentityFrame(name = name, isFresh = true).encode()
                    frameWriter.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, identity, "identity"))
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
        phoneState.stop()
        weatherCache.stop()
        bleClient.disconnect()
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
