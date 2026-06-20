package dev.mrwick.redline.util

/**
 * Bounded, thread-safe in-memory log; drops the oldest entry when [capacity] is exceeded.
 *
 * Used both for app-level logging (for bug report export) and for the frame
 * inspector's TX/RX timeline. Backed by a fixed-size circular buffer.
 */
class RingLog<T>(val capacity: Int = 1000) {

    init {
        require(capacity >= 1) { "capacity must be >= 1, got $capacity" }
    }

    private val lock = Any()
    private val buffer: Array<Any?> = arrayOfNulls(capacity)

    // `head` is the index of the oldest entry; `count` is how many entries are live.
    private var head: Int = 0
    private var count: Int = 0

    /** Number of entries currently held (0..[capacity]). */
    val size: Int
        get() = synchronized(lock) { count }

    /** Append [entry]; if already at [capacity], the oldest entry is dropped. */
    fun add(entry: T) {
        synchronized(lock) {
            if (count < capacity) {
                val writeIdx = (head + count) % capacity
                buffer[writeIdx] = entry
                count++
            } else {
                // Full: overwrite the current head (the oldest entry) and advance head.
                buffer[head] = entry
                head = (head + 1) % capacity
            }
        }
    }

    /** Defensive copy of all live entries in oldest -> newest order. */
    fun snapshot(): List<T> {
        synchronized(lock) {
            if (count == 0) return emptyList()
            val out = ArrayList<T>(count)
            for (i in 0 until count) {
                @Suppress("UNCHECKED_CAST")
                out.add(buffer[(head + i) % capacity] as T)
            }
            return out
        }
    }

    /** Remove all entries. */
    fun clear() {
        synchronized(lock) {
            // Null out so we don't retain references to dropped entries.
            for (i in 0 until capacity) buffer[i] = null
            head = 0
            count = 0
        }
    }

    /** True when the ring holds [capacity] entries. */
    fun isFull(): Boolean = synchronized(lock) { count == capacity }
}
