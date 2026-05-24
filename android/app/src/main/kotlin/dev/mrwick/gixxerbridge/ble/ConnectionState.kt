package dev.mrwick.gixxerbridge.ble

/** Lifecycle of our connection to the bike. */
sealed interface ConnectionState {
    /** No connection attempt active. */
    data object Idle : ConnectionState
    /** connectGatt issued; waiting for STATE_CONNECTED. */
    data object Connecting : ConnectionState
    /** Connected at the link layer; discovering services. */
    data object Discovering : ConnectionState
    /** Service discovery complete, notify subscription active, ready for writes. */
    data object Ready : ConnectionState
    /** Bike disconnected; auto-reconnect (if enabled) will fire when it comes back. */
    data class Disconnected(val status: Int) : ConnectionState
    /** Unrecoverable error — usually means we need a fresh pairing or the bike is gone. */
    data class Failed(val reason: String) : ConnectionState
}
