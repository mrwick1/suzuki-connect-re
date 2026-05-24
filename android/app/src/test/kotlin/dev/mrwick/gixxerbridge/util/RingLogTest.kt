package dev.mrwick.gixxerbridge.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RingLogTest {

    @Test fun rejects_zero_or_negative_capacity() {
        assertThrows(IllegalArgumentException::class.java) { RingLog<Int>(0) }
        assertThrows(IllegalArgumentException::class.java) { RingLog<Int>(-5) }
    }

    @Test fun new_log_is_empty() {
        val log = RingLog<String>(10)
        assertEquals(0, log.size)
        assertFalse(log.isFull())
        assertEquals(emptyList<String>(), log.snapshot())
    }

    @Test fun add_under_capacity_preserves_order() {
        val log = RingLog<Int>(5)
        listOf(1, 2, 3).forEach(log::add)
        assertEquals(3, log.size)
        assertFalse(log.isFull())
        assertEquals(listOf(1, 2, 3), log.snapshot())
    }

    @Test fun add_up_to_capacity_marks_full() {
        val log = RingLog<Int>(3)
        log.add(10); log.add(20); log.add(30)
        assertEquals(3, log.size)
        assertTrue(log.isFull())
        assertEquals(listOf(10, 20, 30), log.snapshot())
    }

    @Test fun add_past_capacity_drops_oldest() {
        val log = RingLog<Int>(3)
        // Add 5 entries to a capacity-3 log; oldest two should be evicted.
        for (i in 1..5) log.add(i)
        assertEquals(3, log.size)
        assertTrue(log.isFull())
        assertEquals(listOf(3, 4, 5), log.snapshot())
    }

    @Test fun add_far_past_capacity_keeps_only_newest() {
        val log = RingLog<Int>(4)
        for (i in 1..100) log.add(i)
        assertEquals(4, log.size)
        assertEquals(listOf(97, 98, 99, 100), log.snapshot())
    }

    @Test fun snapshot_is_defensive_copy() {
        val log = RingLog<Int>(5)
        log.add(1); log.add(2)
        val snap = log.snapshot()
        // Mutating the log after taking a snapshot must not change the snapshot.
        log.add(3); log.add(4); log.add(5); log.add(6) // evicts 1
        assertEquals(listOf(1, 2), snap)
        assertEquals(listOf(2, 3, 4, 5, 6), log.snapshot())
    }

    @Test fun clear_empties_log() {
        val log = RingLog<Int>(3)
        log.add(1); log.add(2); log.add(3)
        assertTrue(log.isFull())
        log.clear()
        assertEquals(0, log.size)
        assertFalse(log.isFull())
        assertEquals(emptyList<Int>(), log.snapshot())
        // Still usable after clear.
        log.add(99)
        assertEquals(listOf(99), log.snapshot())
    }

    @Test fun capacity_one_overwrites_each_time() {
        val log = RingLog<String>(1)
        log.add("a"); assertEquals(listOf("a"), log.snapshot())
        log.add("b"); assertEquals(listOf("b"), log.snapshot())
        log.add("c"); assertEquals(listOf("c"), log.snapshot())
        assertTrue(log.isFull())
    }

    @Test fun concurrent_adds_dont_lose_entries() {
        // 4 threads x 100 entries = 400 total, capacity 1000 (no eviction).
        val log = RingLog<Int>(1000)
        val threadCount = 4
        val perThread = 100
        val ready = CountDownLatch(threadCount)
        val go = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val pool = Executors.newFixedThreadPool(threadCount)

        try {
            for (t in 0 until threadCount) {
                pool.submit {
                    ready.countDown()
                    go.await()
                    for (i in 0 until perThread) {
                        log.add(t * 1000 + i)
                    }
                    done.countDown()
                }
            }
            // Wait for all threads to be parked at the gate, then release them simultaneously.
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            go.countDown()
            assertTrue(done.await(10, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threadCount * perThread, log.size)
        val snap = log.snapshot()
        assertEquals(threadCount * perThread, snap.size)
        // Every entry from every thread is present exactly once.
        val expected = (0 until threadCount).flatMap { t -> (0 until perThread).map { i -> t * 1000 + i } }.toSet()
        assertEquals(expected, snap.toSet())
    }
}
