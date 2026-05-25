package dev.mrwick.gixxerbridge.ui.cluster

import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.FrameEvent
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.protocol.decodeFrame
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Hot state derived from the TX frame stream: the latest a531 (NavFrame) the
 * app sent (or would have sent — nav-preview events are also recorded).
 * UI observes [latestNav] as a StateFlow.
 *
 * PERF: SharingStarted.Eagerly — the collector runs as long as the process is
 * alive. Previously WhileSubscribed(5_000) but that dropped the cached value
 * when the user tab-switched away for >5s, so the cluster preview re-appeared
 * blank ("no a531 frames yet") even though the producer was still ticking.
 * The filter+decode is cheap (one byte check + 30-byte decode) and only fires
 * on TX a531 frames, which are 1-3 Hz at peak — negligible CPU.
 * The whole singleton dies with the process.
 */
object ClusterState {
    private const val TAG = "ClusterState"
    init { android.util.Log.i(TAG, "ClusterState init") }
    val latestNav: StateFlow<NavFrame?> = AppGraph.frameStream.events
        .onEach { ev ->
            android.util.Log.d(TAG, "saw FrameEvent dir=${ev.direction} size=${ev.bytes.size} type=0x${if (ev.bytes.size>=2) "%02x".format(ev.bytes[1].toInt() and 0xFF) else "??"} note=${ev.note}")
        }
        .filter { it.direction == FrameEvent.Direction.TX && it.bytes.size == 30 && (it.bytes[1].toInt() and 0xFF) == 0x31 }
        .mapNotNull { ev ->
            try { (decodeFrame(ev.bytes) as? NavFrame).also { android.util.Log.i(TAG, "decoded NavFrame maneuver=${it?.maneuverId} dist=${it?.distNext}") } } catch (t: Throwable) { android.util.Log.w(TAG, "decode failed: $t"); null }
        }
        .stateIn(
            scope = MainScope(),
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
}
