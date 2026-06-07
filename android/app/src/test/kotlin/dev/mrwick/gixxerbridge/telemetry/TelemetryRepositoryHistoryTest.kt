package dev.mrwick.gixxerbridge.telemetry

import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R5 — verifies that [TelemetryRepository.history] publishes a snapshot only
 * when at least one collector is active, while [TelemetryRepository.latest]
 * always updates unconditionally.
 *
 * Thread-safety note: [TelemetryRepository.update] takes `synchronized(this)`
 * internally; tests call it from the single test-coroutine thread so no
 * additional synchronisation is needed here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryRepositoryHistoryTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // Minimal TelemetryFrame fixture — fields beyond speedKmh are irrelevant here.
    private fun frame(speed: Int) = TelemetryFrame(
        speedKmh = speed,
        odometerKm = 1000,
        tripAKm = 0.0,
        tripBKm = 0.0,
        fuelBars = null,
        fuelEconKml = null,
    )

    @Before
    fun resetRepository() {
        TelemetryRepository.reset()
    }

    @After
    fun cleanupRepository() {
        TelemetryRepository.reset()
    }

    // -------------------------------------------------------------------------
    // R5-A: history stays empty while there are no collectors
    // -------------------------------------------------------------------------

    @Test
    fun `history stays empty with no collector`() = runTest(dispatcher) {
        // No collector attached — subscriptionCount should be 0.
        repeat(5) { i -> TelemetryRepository.update(frame(i * 10)) }

        // history.value must still be empty because nobody is subscribed.
        assertTrue(
            "Expected empty history with no collector, got ${TelemetryRepository.history.value}",
            TelemetryRepository.history.value.isEmpty(),
        )
    }

    // -------------------------------------------------------------------------
    // R5-B: latest always updates unconditionally (no regression)
    // -------------------------------------------------------------------------

    @Test
    fun `latest updates unconditionally regardless of history observers`() = runTest(dispatcher) {
        TelemetryRepository.update(frame(30))
        TelemetryRepository.update(frame(60))

        assertEquals(60, TelemetryRepository.latest.value?.speedKmh)
    }

    // -------------------------------------------------------------------------
    // R5-C: history publishes once a collector is active
    // -------------------------------------------------------------------------

    @Test
    fun `history publishes frames once a collector is attached`() = runTest(dispatcher) {
        // Pump a few frames with no observer — these go into the ArrayDeque but
        // history.value stays empty (R5-A already covers this; here they seed the buffer).
        TelemetryRepository.update(frame(10))
        TelemetryRepository.update(frame(20))

        // Attach a collector. UnconfinedTestDispatcher means the launch body
        // starts immediately and the first `collect` subscription is registered
        // before the next update() call below.
        val collectedSpeeds = mutableListOf<Int>()
        val job = launch {
            TelemetryRepository.history.collect { list ->
                list.forEach { collectedSpeeds += it.speedKmh }
            }
        }

        // Give the collector one frame and verify it appears in history.
        TelemetryRepository.update(frame(30))

        // history.value must now contain the newly published snapshot.
        val snapshot = TelemetryRepository.history.value
        assertTrue(
            "Expected history to contain frame 30, got $snapshot",
            snapshot.any { it.speedKmh == 30 },
        )

        job.cancelAndJoin()
    }

    // -------------------------------------------------------------------------
    // R5-D: after collector detaches, subsequent updates stop publishing again
    // -------------------------------------------------------------------------

    @Test
    fun `history stops publishing after collector detaches`() = runTest(dispatcher) {
        val job = launch {
            // Collect until cancelled — keeps subscriptionCount == 1 while alive.
            TelemetryRepository.history.collect { }
        }

        TelemetryRepository.update(frame(40))
        val snapshotWhileSubscribed = TelemetryRepository.history.value
        assertTrue(snapshotWhileSubscribed.isNotEmpty())

        // Cancel collector and pump another frame — history.value must not change.
        job.cancelAndJoin()
        val lastSnapshot = TelemetryRepository.history.value

        TelemetryRepository.update(frame(99))
        assertEquals(
            "history.value should not change after collector detaches",
            lastSnapshot,
            TelemetryRepository.history.value,
        )
    }
}
