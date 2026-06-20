package dev.mrwick.redline.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide one-shot event bus for transient UI signals (snackbars, banners)
 * that any active screen can react to.
 *
 * Shape: a hot [MutableSharedFlow] with `replay = 0` so screens only see events
 * that happen WHILE they are subscribed — a snackbar that fired before the user
 * opened the app shouldn't replay on next foreground. `extraBufferCapacity = 8`
 * + `DROP_OLDEST` keeps emit() non-suspending even if the UI is briefly absent
 * (e.g. snackbar fires from the service while MainActivity is in onStop).
 *
 * Producers (service / background coroutines) call [emit]. Consumers (Compose
 * scaffolds) collect [events] and dispatch to snackbar/banner hosts.
 */
object AppEvents {

    // PERF: DROP_OLDEST chosen for the same reason as FrameStream — UI surface,
    // newest signal wins if the bus ever backs up. Realistically the buffer
    // never fills (events here are user-facing, sub-Hz).
    private val _events = MutableSharedFlow<AppEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    fun emit(event: AppEvent) {
        _events.tryEmit(event)
    }
}

/**
 * Discriminated union of one-shot UI signals. Add new variants here; the
 * Compose snackbar host in MainActivity exhaustively handles them.
 */
sealed interface AppEvent {
    /**
     * Fired once each time the bike service auto-disables Demo mode after
     * receiving a real a537 frame. The snackbar should offer "Undo" so the
     * rider can re-enable demo if it really was an accidental disable.
     */
    data object DemoModeAutoDisabled : AppEvent
}
