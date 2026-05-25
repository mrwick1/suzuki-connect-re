package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.ServiceItem
import dev.mrwick.gixxerbridge.data.ServiceItemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ServiceSchedule.mostOverdue] / [ServiceSchedule.healthFor].
 * No Android, no DataStore — service-schedule math is intentionally a pure
 * function so it can be exercised here.
 */
class ServiceScheduleTest {

    private val now = 1_750_000_000_000L
    private val msPerDay = 24L * 60L * 60L * 1000L

    private fun state(
        item: ServiceItem = ServiceItem.PERIODIC_SERVICE,
        kmThreshold: Int? = item.defaultKm,
        daysThreshold: Int = item.defaultDays,
        lastDate: Long? = null,
        lastOdo: Int? = null,
    ) = ServiceItemState(
        item = item,
        kmThreshold = kmThreshold,
        daysThreshold = daysThreshold,
        lastServiceDateMs = lastDate,
        lastServiceOdoKm = lastOdo,
    )

    @Test fun noBaselineMeansNoFraction() {
        val h = ServiceSchedule.healthFor(state(), currentOdoKm = 5000, now = now)
        assertNull("days remaining undefined without date baseline", h.daysRemaining)
        assertNull("km remaining undefined without odo baseline", h.kmRemaining)
        assertNull("no baseline => no overall fraction", h.remainingFraction)
    }

    @Test fun freshlyServicedTodayScoresOne() {
        val h = ServiceSchedule.healthFor(
            state(lastDate = now, lastOdo = 5000),
            currentOdoKm = 5000,
            now = now,
        )
        // both gates at their full threshold => remaining fraction = 1.0
        assertEquals(1.0, h.remainingFraction!!, 0.001)
        assertEquals(ServiceItem.PERIODIC_SERVICE.defaultDays, h.daysRemaining)
        assertEquals(ServiceItem.PERIODIC_SERVICE.defaultKm, h.kmRemaining)
    }

    @Test fun exactlyAtKmThresholdScoresZero() {
        // Used == threshold => remaining 0 km => fraction 0.0
        val h = ServiceSchedule.healthFor(
            state(lastDate = now, lastOdo = 1000),  // periodic-service default 3500 km
            currentOdoKm = 1000 + 3500,
            now = now,
        )
        assertEquals(0, h.kmRemaining)
        assertEquals(0.0, h.remainingFraction!!, 0.001)
    }

    @Test fun pastKmThresholdRemainsAtZeroFractionButNegativeRemaining() {
        // The "remaining" stays negative for the UI to show "overdue by N",
        // but the fraction clamps at 0.0 so the gauge can't go negative.
        val h = ServiceSchedule.healthFor(
            state(lastDate = now, lastOdo = 1000),
            currentOdoKm = 1000 + 3500 + 200,
            now = now,
        )
        assertEquals(-200, h.kmRemaining)
        assertEquals(0.0, h.remainingFraction!!, 0.001)
    }

    @Test fun daysElapseLinearly() {
        // 60 days into a 120-day threshold => 60 days remaining => 0.5
        val h = ServiceSchedule.healthFor(
            state(lastDate = now - 60 * msPerDay, lastOdo = 1000),
            currentOdoKm = 1000,  // km gate fresh
            now = now,
        )
        assertEquals(60, h.daysRemaining)
        // km remaining is the full 3500 (fraction 1.0); days fraction is 0.5
        // worst = min(1.0, 0.5) = 0.5
        assertEquals(0.5, h.remainingFraction!!, 0.01)
    }

    @Test fun takesMinimumOfKmAndDaysGates() {
        // 30 days in (90 days remaining of 120 -> 0.75 fraction),
        // 3000 km in (500 km remaining of 3500 -> 0.143 fraction). km wins.
        val h = ServiceSchedule.healthFor(
            state(lastDate = now - 30 * msPerDay, lastOdo = 1000),
            currentOdoKm = 4000,
            now = now,
        )
        assertEquals(90, h.daysRemaining)
        assertEquals(500, h.kmRemaining)
        assertEquals(500.0 / 3500.0, h.remainingFraction!!, 0.001)
    }

    @Test fun brakeOilHasNoKmGate() {
        // BRAKE_OIL.defaultKm is null — even with an odo baseline, km gate stays absent.
        val h = ServiceSchedule.healthFor(
            state(item = ServiceItem.BRAKE_OIL, lastDate = now, lastOdo = 1000),
            currentOdoKm = 99_999_999,
            now = now,
        )
        assertNull("brake oil never has a km remaining", h.kmRemaining)
        assertEquals(ServiceItem.BRAKE_OIL.defaultDays, h.daysRemaining)
        // fraction drives off days alone
        assertEquals(1.0, h.remainingFraction!!, 0.001)
    }

    @Test fun mostOverduePicksTheItemWithSmallestFraction() {
        // engine oil at 50% / air filter at 90% / spark plug at 10% / brake oil 70% / battery untouched
        val items = listOf(
            state(item = ServiceItem.PERIODIC_SERVICE, lastDate = now - 60 * msPerDay, lastOdo = 0),  // 0.5 days, 1.0 km -> 0.5
            state(item = ServiceItem.AIR_FILTER, lastDate = now - 36 * msPerDay, lastOdo = 0),        // ~0.9 days, 1.0 km -> 0.9
            state(item = ServiceItem.SPARK_PLUG, lastDate = now - 216 * msPerDay, lastOdo = 0),       // ~0.1 days
            state(item = ServiceItem.BRAKE_OIL, lastDate = now - 219 * msPerDay, lastOdo = null),     // 0.7 days
            state(item = ServiceItem.BATTERY_CHECKUP),                                                // no baseline
        )
        val result = ServiceSchedule.mostOverdue(items, currentOdoKm = 0, now = now)
        assertEquals(5, result.perItem.size)
        assertNotNull(result.worst)
        // Spark plug wins — smallest fraction.
        assertSame(ServiceItem.SPARK_PLUG, result.worst!!.state.item)
    }

    @Test fun mostOverdueExcludesItemsWithoutBaseline() {
        // Only one item has a baseline — it must be the worst by default.
        val items = listOf(
            state(item = ServiceItem.PERIODIC_SERVICE),  // no baseline
            state(item = ServiceItem.AIR_FILTER),        // no baseline
            state(item = ServiceItem.BRAKE_OIL, lastDate = now, lastOdo = null),  // fresh
        )
        val result = ServiceSchedule.mostOverdue(items, currentOdoKm = 0, now = now)
        assertNotNull(result.worst)
        assertSame(ServiceItem.BRAKE_OIL, result.worst!!.state.item)
    }

    @Test fun mostOverdueReturnsNullWorstWhenNoItemHasBaseline() {
        val items = ServiceItem.entries.map { state(item = it) }
        val result = ServiceSchedule.mostOverdue(items, currentOdoKm = null, now = now)
        assertEquals(ServiceItem.entries.size, result.perItem.size)
        assertNull("nothing to compare => no worst", result.worst)
    }

    @Test fun unknownOdoSkipsKmGateButKeepsDaysGate() {
        // Bike never connected, but we have a date baseline.
        val h = ServiceSchedule.healthFor(
            state(lastDate = now - 30 * msPerDay, lastOdo = 1000),
            currentOdoKm = null,
            now = now,
        )
        assertNull("km gate inactive when current odo unknown", h.kmRemaining)
        assertEquals(90, h.daysRemaining)
        // fraction drives off days alone
        assertTrue(h.remainingFraction!! > 0.0 && h.remainingFraction!! < 1.0)
    }

    @Test fun defaultsMatchSuzukiTable() {
        // Mirrors decompiled PeriodicVehicleServiceActivity (DISCOVERIES 2026-05-25).
        // 3500-4000 ranged items use the lower (conservative) bound 3500.
        assertEquals(120, ServiceItem.PERIODIC_SERVICE.defaultDays)
        assertEquals(3500, ServiceItem.PERIODIC_SERVICE.defaultKm)

        assertEquals(365, ServiceItem.AIR_FILTER.defaultDays)
        assertEquals(12000, ServiceItem.AIR_FILTER.defaultKm)

        assertEquals(240, ServiceItem.SPARK_PLUG.defaultDays)
        assertEquals(8000, ServiceItem.SPARK_PLUG.defaultKm)

        assertEquals(730, ServiceItem.BRAKE_OIL.defaultDays)
        assertNull("brake oil is days-only — no km gate", ServiceItem.BRAKE_OIL.defaultKm)

        assertEquals(120, ServiceItem.BATTERY_CHECKUP.defaultDays)
        assertEquals(3500, ServiceItem.BATTERY_CHECKUP.defaultKm)
    }
}
