package dev.mrwick.gixxerbridge.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RideMergeNamingTest {
    // 2026-06-14 is a Sunday. Fix the zone so the day-of-week is deterministic.
    private val zone = ZoneId.of("Asia/Kolkata")
    // Derive the epoch millis from the real date so the constant can't drift.
    private val sundayMillis =
        LocalDate.of(2026, 6, 14).atTime(14, 40).atZone(zone).toInstant().toEpochMilli()

    @Test fun namesByDayAndDistance() {
        assertEquals("Sunday ride · 328 km", mergedRideName(sundayMillis, 328, zone))
    }

    @Test fun singularKmStillReadsKm() {
        assertEquals("Sunday ride · 1 km", mergedRideName(sundayMillis, 1, zone))
    }
}
