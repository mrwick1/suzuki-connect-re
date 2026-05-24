package dev.mrwick.gixxerbridge.app

import dev.mrwick.gixxerbridge.ble.BleClient
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ble.FrameStream
import dev.mrwick.gixxerbridge.ble.FrameWriter
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
}
