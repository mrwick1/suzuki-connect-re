package dev.mrwick.gixxerbridge.ble

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hot stream of every TX + RX byte array that crosses BLE.
 * Consumers: InspectorScreen (live view), RingLog (bug report).
 */
class FrameStream {
    private val _events = MutableSharedFlow<FrameEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<FrameEvent> = _events.asSharedFlow()

    fun emit(event: FrameEvent) {
        _events.tryEmit(event)
    }
}

/** A single TX or RX BLE event with its raw bytes and timestamp. */
data class FrameEvent(
    val direction: Direction,
    val bytes: ByteArray,
    val tMillis: Long = System.currentTimeMillis(),
    val note: String? = null,
) {
    enum class Direction { TX, RX }

    override fun equals(other: Any?): Boolean =
        other is FrameEvent &&
            direction == other.direction &&
            bytes.contentEquals(other.bytes) &&
            tMillis == other.tMillis &&
            note == other.note

    override fun hashCode(): Int =
        ((direction.hashCode() * 31 + bytes.contentHashCode()) * 31 + tMillis.hashCode()) * 31 + (note?.hashCode() ?: 0)
}
