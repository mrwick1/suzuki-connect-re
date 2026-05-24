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
 * Process-wide singleton — the underlying flow is started Eagerly so the first
 * TX frame after process start is captured even before any Composable observes.
 */
object ClusterState {
    val latestNav: StateFlow<NavFrame?> = AppGraph.frameStream.events
        .filter { it.direction == FrameEvent.Direction.TX && it.bytes.size == 30 && it.bytes[1].toInt() == 0x31 }
        .mapNotNull { ev ->
            try { decodeFrame(ev.bytes) as? NavFrame } catch (_: Throwable) { null }
        }
        .stateIn(
            scope = MainScope(),
            started = SharingStarted.Eagerly,
            initialValue = null,
        )
}
