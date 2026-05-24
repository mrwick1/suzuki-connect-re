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
import kotlinx.coroutines.flow.stateIn

/**
 * Hot state derived from the TX frame stream: the latest a531 (NavFrame) the
 * app sent to the bike. UI observes [latestNav] as a StateFlow.
 *
 * PERF: switched from SharingStarted.Eagerly to WhileSubscribed(5_000) — the
 * upstream is the frame inspector / mirror used by debug screens, and the
 * filter+decode runs on every TX frame (1 Hz heartbeat + nav). When the app
 * is backgrounded and no Composable observes, keeping the collector hot just
 * burns CPU. The 5s grace window keeps it warm across screen rotations and
 * brief tab swaps so the cluster preview doesn't flash empty on return.
 * ASSUMED: losing the "captured before any UI observed" guarantee is fine —
 * the cluster screens always re-observe on entry, and the source-of-truth
 * for what was sent is the bike, not this derived state.
 */
object ClusterState {
    val latestNav: StateFlow<NavFrame?> = AppGraph.frameStream.events
        .filter { it.direction == FrameEvent.Direction.TX && it.bytes.size == 30 && it.bytes[1].toInt() == 0x31 }
        .mapNotNull { ev ->
            try { decodeFrame(ev.bytes) as? NavFrame } catch (_: Throwable) { null }
        }
        .stateIn(
            scope = MainScope(),
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null,
        )
}
