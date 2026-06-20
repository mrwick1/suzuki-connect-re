package dev.mrwick.redline.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RefuelPredictor]. No Android, no Room.
 *
 * Bucket thresholds (private to the object) are tested by driving inputs that
 * land just below and just above each boundary, not by reading private constants.
 */
class RefuelPredictorTest {

    // A nominal daily pace used throughout: 50 km/day.
    private val pace = 50.0

    // -------------------------------------------------------------------------
    // UNKNOWN guard cases
    // -------------------------------------------------------------------------

    @Test fun `null rangeKm returns UNKNOWN`() {
        val r = RefuelPredictor.predict(rangeKm = null, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.UNKNOWN, r.bucket)
        assertFalse(r.fillBeforeService)
    }

    @Test fun `negative rangeKm returns UNKNOWN`() {
        val r = RefuelPredictor.predict(rangeKm = -10.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.UNKNOWN, r.bucket)
        assertFalse(r.fillBeforeService)
    }

    @Test fun `null dailyKmPace returns UNKNOWN`() {
        val r = RefuelPredictor.predict(rangeKm = 100.0, dailyKmPace = null, serviceKmRemaining = null)
        assertEquals(RefuelBucket.UNKNOWN, r.bucket)
        assertFalse(r.fillBeforeService)
    }

    @Test fun `zero dailyKmPace returns UNKNOWN`() {
        val r = RefuelPredictor.predict(rangeKm = 100.0, dailyKmPace = 0.0, serviceKmRemaining = null)
        assertEquals(RefuelBucket.UNKNOWN, r.bucket)
        assertFalse(r.fillBeforeService)
    }

    @Test fun `negative dailyKmPace returns UNKNOWN`() {
        val r = RefuelPredictor.predict(rangeKm = 100.0, dailyKmPace = -1.0, serviceKmRemaining = null)
        assertEquals(RefuelBucket.UNKNOWN, r.bucket)
        assertFalse(r.fillBeforeService)
    }

    // -------------------------------------------------------------------------
    // Bucket boundary tests — 50 km/day pace
    // rangeKm < 50  => < 1 day => TODAY
    // 50 <= rangeKm < 100 => [1,2) days => ONE_DAY
    // 100 <= rangeKm < 200 => [2,4) days => TWO_THREE_DAYS
    // 200 <= rangeKm < 350 => [4,7) days => THIS_WEEK
    // rangeKm >= 350 => >= 7 days => OVER_A_WEEK
    // -------------------------------------------------------------------------

    @Test fun `zero range is TODAY`() {
        val r = RefuelPredictor.predict(rangeKm = 0.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.TODAY, r.bucket)
    }

    @Test fun `range just below 1-day threshold is TODAY`() {
        // 49 km at 50 km/day = 0.98 days < 1
        val r = RefuelPredictor.predict(rangeKm = 49.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.TODAY, r.bucket)
    }

    @Test fun `range exactly at 1-day threshold is ONE_DAY`() {
        // 50 km / 50 km/day = 1.0 day — first bucket above TODAY
        val r = RefuelPredictor.predict(rangeKm = 50.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.ONE_DAY, r.bucket)
    }

    @Test fun `range just below 2-day threshold is ONE_DAY`() {
        // 99 km / 50 = 1.98 days
        val r = RefuelPredictor.predict(rangeKm = 99.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.ONE_DAY, r.bucket)
    }

    @Test fun `range exactly at 2-day threshold is TWO_THREE_DAYS`() {
        // 100 km / 50 = 2.0 days
        val r = RefuelPredictor.predict(rangeKm = 100.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.TWO_THREE_DAYS, r.bucket)
    }

    @Test fun `range in middle of TWO_THREE_DAYS bucket`() {
        // 150 km / 50 = 3.0 days
        val r = RefuelPredictor.predict(rangeKm = 150.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.TWO_THREE_DAYS, r.bucket)
    }

    @Test fun `range just below 4-day threshold is TWO_THREE_DAYS`() {
        // 199 km / 50 = 3.98 days
        val r = RefuelPredictor.predict(rangeKm = 199.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.TWO_THREE_DAYS, r.bucket)
    }

    @Test fun `range exactly at 4-day threshold is THIS_WEEK`() {
        // 200 km / 50 = 4.0 days
        val r = RefuelPredictor.predict(rangeKm = 200.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.THIS_WEEK, r.bucket)
    }

    @Test fun `range just below 7-day threshold is THIS_WEEK`() {
        // 349 km / 50 = 6.98 days
        val r = RefuelPredictor.predict(rangeKm = 349.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.THIS_WEEK, r.bucket)
    }

    @Test fun `range exactly at 7-day threshold is OVER_A_WEEK`() {
        // 350 km / 50 = 7.0 days
        val r = RefuelPredictor.predict(rangeKm = 350.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.OVER_A_WEEK, r.bucket)
    }

    @Test fun `range well above week is OVER_A_WEEK`() {
        val r = RefuelPredictor.predict(rangeKm = 1000.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertEquals(RefuelBucket.OVER_A_WEEK, r.bucket)
    }

    // -------------------------------------------------------------------------
    // fillBeforeService co-prompt tests
    // -------------------------------------------------------------------------

    @Test fun `null serviceKmRemaining never triggers fillBeforeService`() {
        // Even with nearly empty tank, no service km baseline => no co-prompt.
        val r = RefuelPredictor.predict(rangeKm = 10.0, dailyKmPace = pace, serviceKmRemaining = null)
        assertFalse(r.fillBeforeService)
    }

    @Test fun `overdue service triggers fillBeforeService`() {
        // serviceKmRemaining negative => overdue => always fill before service.
        val r = RefuelPredictor.predict(rangeKm = 200.0, dailyKmPace = pace, serviceKmRemaining = -50)
        assertTrue(r.fillBeforeService)
    }

    @Test fun `service exactly at zero km remaining triggers fillBeforeService`() {
        val r = RefuelPredictor.predict(rangeKm = 200.0, dailyKmPace = pace, serviceKmRemaining = 0)
        assertTrue(r.fillBeforeService)
    }

    @Test fun `service within range triggers fillBeforeService`() {
        // rangeKm = 200, serviceKmRemaining = 150 => service falls inside the refuel window.
        val r = RefuelPredictor.predict(rangeKm = 200.0, dailyKmPace = pace, serviceKmRemaining = 150)
        assertTrue(r.fillBeforeService)
    }

    @Test fun `service at edge of range plus buffer triggers fillBeforeService`() {
        // rangeKm = 200, buffer = 200 km => threshold = 400; service at 399 => triggers.
        val r = RefuelPredictor.predict(rangeKm = 200.0, dailyKmPace = pace, serviceKmRemaining = 399)
        assertTrue(r.fillBeforeService)
    }

    @Test fun `service well beyond range plus buffer does not trigger fillBeforeService`() {
        // rangeKm = 200, buffer 200 km => threshold = 400; service at 1000 => no co-prompt.
        val r = RefuelPredictor.predict(rangeKm = 200.0, dailyKmPace = pace, serviceKmRemaining = 1000)
        assertFalse(r.fillBeforeService)
    }

    @Test fun `UNKNOWN bucket never triggers fillBeforeService even if service overdue`() {
        // No pace => UNKNOWN; co-prompt must be suppressed.
        val r = RefuelPredictor.predict(rangeKm = null, dailyKmPace = null, serviceKmRemaining = -500)
        assertEquals(RefuelBucket.UNKNOWN, r.bucket)
        assertFalse(r.fillBeforeService)
    }

    // -------------------------------------------------------------------------
    // dailyKmPaceFromRides helper
    // -------------------------------------------------------------------------

    @Test fun `dailyKmPaceFromRides empty list returns null`() {
        assertNull(RefuelPredictor.dailyKmPaceFromRides(emptyList()))
    }

    @Test fun `dailyKmPaceFromRides all-zero-km rides returns null`() {
        assertNull(
            RefuelPredictor.dailyKmPaceFromRides(listOf(0 to 3_600_000L, 0 to 7_200_000L))
        )
    }

    @Test fun `dailyKmPaceFromRides computes km per day correctly`() {
        // 50 km over exactly 1 day (86400000 ms) => 50 km/day
        val msPerDay = 86_400_000L
        val result = RefuelPredictor.dailyKmPaceFromRides(listOf(50 to msPerDay))
        assertEquals(50.0, result!!, 0.001)
    }

    @Test fun `dailyKmPaceFromRides aggregates multiple rides`() {
        // Two rides: 30 km / 0.5 day + 70 km / 1.5 day = 100 km / 2 days = 50 km/day
        val halfDay = 43_200_000L
        val dayHalf = 129_600_000L
        val result = RefuelPredictor.dailyKmPaceFromRides(listOf(30 to halfDay, 70 to dayHalf))
        assertEquals(50.0, result!!, 0.1)
    }

    @Test fun `dailyKmPaceFromRides skips zero-distance rides`() {
        // Phantom ride (0 km) must not count toward pace.
        val msPerDay = 86_400_000L
        val result = RefuelPredictor.dailyKmPaceFromRides(
            listOf(0 to 3_600_000L, 50 to msPerDay)
        )
        assertEquals(50.0, result!!, 0.001)
    }

    @Test fun `dailyKmPaceFromRides skips negative-duration rides`() {
        val msPerDay = 86_400_000L
        // Ride with negative duration should be skipped for the days denominator.
        val result = RefuelPredictor.dailyKmPaceFromRides(
            listOf(50 to msPerDay, 100 to -1L)
        )
        // Only the first ride is valid: 50 km / 1 day = 50.
        assertEquals(50.0, result!!, 0.001)
    }
}
