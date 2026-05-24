package dev.mrwick.gixxerbridge.ble

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Hot stream of every TX + RX byte array that crosses BLE.
 * Consumers: InspectorScreen (live view), RingLog (bug report).
 */
class FrameStream {
    // PERF: explicit DROP_OLDEST overflow policy. Inspector is the only
    // consumer and it's a UI surface — if it ever falls behind, we want the
    // newest frames to win rather than freezing on stale data. With the
    // default (SUSPEND) and `tryEmit`, slow consumers silently lose new
    // frames; DROP_OLDEST drops the back of the buffer first so the most
    // recent traffic stays visible (audit finding 3.3).
    private val _events = MutableSharedFlow<FrameEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
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
