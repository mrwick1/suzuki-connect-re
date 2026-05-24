package dev.mrwick.gixxerbridge.app

import android.content.Context
import dev.mrwick.gixxerbridge.ble.BikeInfo
import dev.mrwick.gixxerbridge.ble.BleClient
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ble.FrameStream
import dev.mrwick.gixxerbridge.ble.FrameWriter
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.QuickDestinations
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.location.LastParkedTracker
import dev.mrwick.gixxerbridge.nav.NavMux
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide singletons accessed by Compose UI + the foreground service.
 * Populated by `BikeBridgeService.onCreate()`; until then the stubs serve UI work
 * (Inspector + Dashboard render fine with no data).
 */
object AppGraph {

    /** TX + RX stream — Inspector observes. Always present (real or stub). */
    val frameStream: FrameStream = FrameStream()

    /** Live BLE connection state. UI may show "Connecting / Ready" etc. */
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** Wired by BikeBridgeService when it constructs the real client. */
    @Volatile var bleClient: BleClient? = null
    @Volatile var frameWriter: FrameWriter? = null
    @Volatile var navMux: NavMux? = null

    fun publishConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    /** Standard BLE Device Information Service snapshot — populated by BleClient on first connect. */
    private val _bikeInfo = MutableStateFlow<BikeInfo?>(null)
    val bikeInfo: StateFlow<BikeInfo?> = _bikeInfo.asStateFlow()

    fun publishBikeInfo(info: BikeInfo) {
        _bikeInfo.value = info
    }

    /**
     * Composer / dev tools call this. Returns false if no BLE client is wired yet.
     * Real send goes through FrameWriter so it respects pacing + priorities.
     */
    suspend fun sendFrame(bytes: ByteArray): Boolean {
        if (bytes.size != 30) return false
        val writer = frameWriter
        return if (writer != null) {
            writer.enqueue(FrameWriter.Entry(FrameWriter.Priority.URGENT, bytes, note = "composer"))
            true
        } else {
            bleClient?.write(bytes) ?: false
        }
    }

    // PERF: process-wide singletons for DataStore/Room handles. Constructing a
    // fresh `Settings(ctx)` (or RideStore / QuickDestinations / LastParkedTracker)
    // inside every consuming Composable allocated a wrapper per call site and
    // duplicated the DataStore-handle bookkeeping. Centralising here keeps each
    // backing store referenced exactly once per process while preserving every
    // existing call shape — consumers just go through these accessors instead
    // of `remember { Settings(...) }`.
    @Volatile private var _settings: Settings? = null
    @Volatile private var _quickDestinations: QuickDestinations? = null
    @Volatile private var _lastParkedTracker: LastParkedTracker? = null
    @Volatile private var _rideStore: RideStore? = null

    /** Process-wide [Settings] handle. Safe to call from any thread / composable. */
    fun settings(context: Context): Settings {
        val cached = _settings
        if (cached != null) return cached
        return synchronized(this) {
            _settings ?: Settings(context.applicationContext).also { _settings = it }
        }
    }

    /** Process-wide [QuickDestinations] handle. */
    fun quickDestinations(context: Context): QuickDestinations {
        val cached = _quickDestinations
        if (cached != null) return cached
        return synchronized(this) {
            _quickDestinations ?: QuickDestinations(context.applicationContext)
                .also { _quickDestinations = it }
        }
    }

    /** Process-wide [LastParkedTracker] handle. */
    fun lastParkedTracker(context: Context): LastParkedTracker {
        val cached = _lastParkedTracker
        if (cached != null) return cached
        return synchronized(this) {
            _lastParkedTracker ?: LastParkedTracker(context.applicationContext)
                .also { _lastParkedTracker = it }
        }
    }

    /** Process-wide [RideStore] handle (Room DAO wrapper). */
    fun rideStore(context: Context): RideStore {
        val cached = _rideStore
        if (cached != null) return cached
        return synchronized(this) {
            _rideStore ?: RideStore(GixxerDatabase.get(context.applicationContext).rideDao())
                .also { _rideStore = it }
        }
    }
}
