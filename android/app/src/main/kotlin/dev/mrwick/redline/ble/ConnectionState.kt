package dev.mrwick.redline.ble

import androidx.compose.runtime.Immutable

/** Lifecycle of our connection to the bike. */
@Immutable
sealed interface ConnectionState {
    /** No connection attempt active. */
    @Immutable
    data object Idle : ConnectionState
    /** connectGatt issued; waiting for STATE_CONNECTED. */
    @Immutable
    data object Connecting : ConnectionState
    /** Connected at the link layer; discovering services. */
    @Immutable
    data object Discovering : ConnectionState
    /** Service discovery complete, notify subscription active, ready for writes. */
    @Immutable
    data object Ready : ConnectionState
    /** Bike disconnected; auto-reconnect (if enabled) will fire when it comes back. */
    @Immutable
    data class Disconnected(val status: Int) : ConnectionState
    /** Unrecoverable error — usually means we need a fresh pairing or the bike is gone. */
    @Immutable
    data class Failed(val reason: String) : ConnectionState
}
