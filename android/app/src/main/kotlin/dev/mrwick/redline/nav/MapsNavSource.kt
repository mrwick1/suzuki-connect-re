package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide handoff: NotificationCaptureService writes when a Maps nav
 * notification arrives or is dismissed; BikeBridgeService.NavMux observes.
 *
 * STALE NAV PROTECTION: Maps doesn't always dismiss its persistent navigation
 * notification when the user exits the app — sometimes it stays posted with the
 * last instruction. Each [update] schedules a watchdog that clears the frame if
 * no fresh update arrives within [STALE_AFTER_MS]. Without this the cluster
 * keeps showing the stale arrow forever after navigation ends.
 */
object MapsNavSource : NavSource {
    private const val TAG = "MapsNavSource"

    /** Auto-clear after this many ms without an [update]. Maps refreshes nav
     *  notifications every 2-5 s while active, so 60 s is a comfortable margin. */
    const val STALE_AFTER_MS: Long = 60_000

    private val _frame = MutableStateFlow<NavFrame?>(null)
    override val frame: StateFlow<NavFrame?> = _frame.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var staleJob: Job? = null

    fun update(parsed: ParsedNavData?) {
        _frame.value = parsed?.toNavFrame()
        // Reset the stale watchdog every time a fresh update arrives.
        staleJob?.cancel()
        if (_frame.value != null) {
            staleJob = scope.launch {
                delay(STALE_AFTER_MS)
                if (_frame.value != null) {
                    AppLog.i(TAG, "auto-clearing stale nav (no Maps update in ${STALE_AFTER_MS / 1000}s)")
                    _frame.value = null
                }
            }
        }
    }

    fun clear() {
        if (_frame.value != null) AppLog.i(TAG, "cleared (notification removed)")
        _frame.value = null
        staleJob?.cancel()
    }
}
