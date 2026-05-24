package dev.mrwick.gixxerbridge.nav

import dev.mrwick.gixxerbridge.protocol.NavFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide handoff: NotificationCaptureService writes when a Maps nav
 * notification arrives or is dismissed; BikeBridgeService.NavMux observes.
 */
object MapsNavSource : NavSource {
    private val _frame = MutableStateFlow<NavFrame?>(null)
    override val frame: StateFlow<NavFrame?> = _frame.asStateFlow()

    fun update(parsed: ParsedNavData?) {
        _frame.value = parsed?.toNavFrame()
    }

    fun clear() {
        _frame.value = null
    }
}
