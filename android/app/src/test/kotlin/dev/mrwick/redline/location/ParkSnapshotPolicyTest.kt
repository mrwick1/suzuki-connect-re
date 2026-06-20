package dev.mrwick.redline.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for [ParkSnapshotPolicy] — the decision of what to persist when
 * the bike disconnects, given the location we could (or couldn't) obtain.
 *
 * The core invariant under test: the park *time* always advances to "now", even
 * when no location is available. This is the fix for the stale "last parked N
 * days ago" bug, where a missing GPS fix at key-off left the timestamp frozen at
 * the previous successful snapshot.
 */
class ParkSnapshotPolicyTest {

    private val previous = LastParked(lat = 11.0, lng = 75.0, tMillis = 1_000L, locTMillis = 1_000L)

    @Test fun freshFixIsUsedWithItsOwnFixTime() {
        val w = ParkSnapshotPolicy.decide(
            now = 5_000L,
            fix = LocFix(lat = 12.0, lng = 76.0, atMillis = 4_900L),
            previous = previous,
        )!!
        assertEquals(12.0, w.lat, 0.0)
        assertEquals(76.0, w.lng, 0.0)
        assertEquals(5_000L, w.parkedAtMillis)
        assertEquals(4_900L, w.locAtMillis)
    }

    @Test fun noFixReusesPreviousCoordsButStillAdvancesParkTime() {
        val w = ParkSnapshotPolicy.decide(now = 9_000L, fix = null, previous = previous)!!
        // Old coordinates are reused (approximate spot)...
        assertEquals(11.0, w.lat, 0.0)
        assertEquals(75.0, w.lng, 0.0)
        // ...but the park time moves forward — this is the bug fix.
        assertEquals(9_000L, w.parkedAtMillis)
        // ...and the location's own age is preserved so the UI can flag it stale.
        assertEquals(1_000L, w.locAtMillis)
    }

    @Test fun noFixAndNoPreviousSnapshotIsNull() {
        assertNull(ParkSnapshotPolicy.decide(now = 9_000L, fix = null, previous = null))
    }

    @Test fun freshFixWithNoPreviousIsStillUsed() {
        val w = ParkSnapshotPolicy.decide(
            now = 5_000L,
            fix = LocFix(lat = 12.0, lng = 76.0, atMillis = 5_000L),
            previous = null,
        )!!
        assertEquals(12.0, w.lat, 0.0)
        assertEquals(5_000L, w.parkedAtMillis)
        assertEquals(5_000L, w.locAtMillis)
    }
}
