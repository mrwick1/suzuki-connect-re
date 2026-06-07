package dev.mrwick.gixxerbridge.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Pure-JVM tests for [WrappedWindow] factories and validation. */
class WrappedWindowTest {

    private val utc = ZoneOffset.UTC

    // -------------------------------------------------------------------------
    // ofCalendarYear
    // -------------------------------------------------------------------------

    @Test fun ofCalendarYearStartsOnJan1() {
        val w = WrappedWindow.ofCalendarYear(2024, utc)
        assertEquals(LocalDate.of(2024, 1, 1), w.startInclusive)
    }

    @Test fun ofCalendarYearEndsOnDec31() {
        val w = WrappedWindow.ofCalendarYear(2024, utc)
        assertEquals(LocalDate.of(2024, 12, 31), w.endInclusive)
    }

    @Test fun ofCalendarYearLeapYear() {
        val w = WrappedWindow.ofCalendarYear(2024, utc)
        // 2024 is a leap year — Feb 29 must be inside the window
        assertTrue(
            !LocalDate.of(2024, 2, 29).isBefore(w.startInclusive) &&
                !LocalDate.of(2024, 2, 29).isAfter(w.endInclusive)
        )
    }

    @Test fun ofCalendarYearSetsZone() {
        val w = WrappedWindow.ofCalendarYear(2025, utc)
        assertEquals(utc, w.zone)
    }

    // -------------------------------------------------------------------------
    // ofRollingDays
    // -------------------------------------------------------------------------

    @Test fun ofRollingDaysEndIsToday() {
        val today = LocalDate.of(2025, 6, 7)
        val w = WrappedWindow.ofRollingDays(days = 30, today = today, zone = utc)
        assertEquals(today, w.endInclusive)
    }

    @Test fun ofRollingDaysStartIsDaysMinus1Before() {
        val today = LocalDate.of(2025, 6, 7)
        val w = WrappedWindow.ofRollingDays(days = 30, today = today, zone = utc)
        // 30-day window: today minus 29 days = start
        assertEquals(today.minusDays(29), w.startInclusive)
    }

    @Test fun ofRollingDaysSingleDay() {
        val today = LocalDate.of(2025, 1, 1)
        val w = WrappedWindow.ofRollingDays(days = 1, today = today, zone = utc)
        assertEquals(today, w.startInclusive)
        assertEquals(today, w.endInclusive)
    }

    @Test fun ofRollingDaysFullYear365Days() {
        val today = LocalDate.of(2025, 6, 7)
        val w = WrappedWindow.ofRollingDays(days = 365, today = today, zone = utc)
        assertEquals(today.minusDays(364), w.startInclusive)
        assertEquals(today, w.endInclusive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ofRollingDaysZeroDaysThrows() {
        WrappedWindow.ofRollingDays(days = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun ofRollingDaysNegativeDaysThrows() {
        WrappedWindow.ofRollingDays(days = -1)
    }

    // -------------------------------------------------------------------------
    // Constructor validation
    // -------------------------------------------------------------------------

    @Test fun sameDayWindowIsValid() {
        val d = LocalDate.of(2025, 3, 15)
        val w = WrappedWindow(startInclusive = d, endInclusive = d, zone = utc)
        assertEquals(d, w.startInclusive)
        assertEquals(d, w.endInclusive)
    }

    @Test(expected = IllegalArgumentException::class)
    fun endBeforeStartThrows() {
        WrappedWindow(
            startInclusive = LocalDate.of(2025, 6, 7),
            endInclusive = LocalDate.of(2025, 6, 6),
            zone = utc,
        )
    }

    // -------------------------------------------------------------------------
    // Boundary membership (used by WrappedAnalytics filtering)
    // -------------------------------------------------------------------------

    @Test fun startDateIsInclusive() {
        val w = WrappedWindow.ofCalendarYear(2024, utc)
        assertFalse(LocalDate.of(2024, 1, 1).isBefore(w.startInclusive))
        assertFalse(LocalDate.of(2024, 1, 1).isAfter(w.endInclusive))
    }

    @Test fun endDateIsInclusive() {
        val w = WrappedWindow.ofCalendarYear(2024, utc)
        assertFalse(LocalDate.of(2024, 12, 31).isBefore(w.startInclusive))
        assertFalse(LocalDate.of(2024, 12, 31).isAfter(w.endInclusive))
    }

    @Test fun dayBeforeStartIsOutside() {
        val w = WrappedWindow.ofCalendarYear(2024, utc)
        assertTrue(LocalDate.of(2023, 12, 31).isBefore(w.startInclusive))
    }

    @Test fun dayAfterEndIsOutside() {
        val w = WrappedWindow.ofCalendarYear(2024, utc)
        assertTrue(LocalDate.of(2025, 1, 1).isAfter(w.endInclusive))
    }
}
