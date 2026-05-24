package dev.mrwick.gixxerbridge.nav

import dev.mrwick.gixxerbridge.protocol.NavFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Priority mux: prefer the Google Maps frame when present; fall back to the
 * idle clock generator otherwise.
 *
 * Caller is the BikeBridgeService heartbeat loop, which collects [frame]
 * and writes each emission to the bike at the desired a531 cadence.
 */
class NavMux(
    private val mapsFlow: Flow<NavFrame?>,
    private val idleClockFlow: Flow<NavFrame>,
) {
    /** Stream of frames; Maps wins when non-null, otherwise the idle clock. */
    val frame: Flow<NavFrame> = combine(mapsFlow, idleClockFlow) { maps, idle ->
        maps ?: idle
    }.distinctUntilChanged()
}
