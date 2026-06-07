package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.ServiceItem
import dev.mrwick.gixxerbridge.data.ServiceItemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ServiceEta.forecast], [ServiceEta.paceKmPerDay], and
 * [ServiceEta.formatRelative]. No Room, no Android, no DataStore.
 * "Now" is fixed so the projected dates are deterministic.
 */
class ServiceEtaTest {

    private val now = 1_750_000_000_000L
    private val msPerDay = 24L * 60L * 60L * 1000L

    private fun health(
        kmRemaining: Int?,
        daysRemaining: Int?,
    ) = ServiceItemHealth(
        state = ServiceItemState(
            item = ServiceItem.PERIODIC_SERVICE,
            kmThreshold = 3500,
            daysThreshold = 120,
            lastServiceDateMs = now,
            lastServiceOdoKm = 1000,
        ),
        daysRemaining = daysRemaining,
        kmRemaining = kmRemaining,
        remainingFraction = 1.0, // not used by the forecaster
    )

    // --- km-gated forecast, calendar still has slack -> km gate drives it ---
    @Test fun kmGateDrivesEtaWhenPaceIsSteady() {
        // 350 km left at 10 km/day => ~35 days. Calendar gate is 120 days, so km wins.
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = 120), kmPerDay = 10.0, now = now)!!
        assertEquals(35, f.daysAway)
        assertEquals(now + 35 * msPerDay, f.dueAtMillis)
        assertEquals(EtaGate.KM, f.gate)
    }

    // --- "calendar wins": projected km-date is later than the days gate ---
    @Test fun calendarWinsWhenKmProjectionIsLater() {
        // 1000 km left at 10 km/day => 100 days by km; but only 40 days left on the
        // calendar gate. min(100, 40) = 40 => calendar wins.
        val f = ServiceEta.forecast(health(kmRemaining = 1000, daysRemaining = 40), kmPerDay = 10.0, now = now)!!
        assertEquals(40, f.daysAway)
        assertEquals(now + 40 * msPerDay, f.dueAtMillis)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    // --- zero-km window: div-by-zero guard -> calendar-only ---
    @Test fun zeroPaceFallsBackToCalendarOnly() {
        // No riding in the window => km projection is undefined; use the days gate.
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = 55), kmPerDay = 0.0, now = now)!!
        assertEquals(55, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    @Test fun negativePaceTreatedAsZero() {
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = 55), kmPerDay = -3.0, now = now)!!
        assertEquals(55, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    // --- no calendar gate (e.g. odo-only baseline / null days): km gate only ---
    @Test fun kmOnlyWhenNoCalendarGate() {
        val f = ServiceEta.forecast(health(kmRemaining = 100, daysRemaining = null), kmPerDay = 10.0, now = now)!!
        assertEquals(10, f.daysAway)
        assertEquals(EtaGate.KM, f.gate)
    }

    // --- no km gate (brake-oil style days-only item): calendar gate only ---
    @Test fun calendarOnlyWhenNoKmGate() {
        val f = ServiceEta.forecast(health(kmRemaining = null, daysRemaining = 30), kmPerDay = 10.0, now = now)!!
        assertEquals(30, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    // --- nothing to forecast: no km gate, no days gate -> null ---
    @Test fun noGatesIsUnavailable() {
        assertNull(ServiceEta.forecast(health(kmRemaining = null, daysRemaining = null), kmPerDay = 10.0, now = now))
    }

    // --- zero pace AND no calendar gate -> nothing projectable -> null ---
    @Test fun zeroPaceWithKmOnlyIsUnavailable() {
        assertNull(ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = null), kmPerDay = 0.0, now = now))
    }

    // --- already overdue on km: clamp to "due now" (0 days), don't go negative ---
    @Test fun overdueKmClampsToZeroDays() {
        val f = ServiceEta.forecast(health(kmRemaining = -200, daysRemaining = 40), kmPerDay = 10.0, now = now)!!
        assertEquals(0, f.daysAway)
        assertEquals(EtaGate.KM, f.gate)
        assertTrue(f.isOverdue)
    }

    // --- already overdue on calendar: clamp to "due now" ---
    @Test fun overdueCalendarClampsToZeroDays() {
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = -5), kmPerDay = 10.0, now = now)!!
        assertEquals(0, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
        assertTrue(f.isOverdue)
    }

    // ---------- paceKmPerDay ----------

    @Test fun paceIsThirtyDayKmAverage() {
        // Two rides inside the 30-day window summing to 300 km => 300/30 = 10.0 km/day.
        val rides = listOf(
            rideOf(id = 1, startedDaysAgo = 2, startOdo = 1000, distanceKm = 200),
            rideOf(id = 2, startedDaysAgo = 10, startOdo = 1200, distanceKm = 100),
        )
        assertEquals(10.0, ServiceEta.paceKmPerDay(rides, now = now), 0.0001)
    }

    @Test fun paceIsZeroWhenNoRecentRides() {
        val stale = listOf(rideOf(id = 1, startedDaysAgo = 90, startOdo = 1000, distanceKm = 500))
        assertEquals(0.0, ServiceEta.paceKmPerDay(stale, now = now), 0.0001)
    }

    // ---------- formatRelative ----------

    @Test fun formatRelativeReadsDaysAway() {
        val f = ServiceEtaForecast(daysAway = 18, dueAtMillis = now, gate = EtaGate.KM, isOverdue = false)
        assertEquals("~18 days", ServiceEta.formatRelative(f))
    }

    @Test fun formatRelativeOverdueReadsDueNow() {
        val f = ServiceEtaForecast(daysAway = 0, dueAtMillis = now, gate = EtaGate.CALENDAR, isOverdue = true)
        assertEquals("due now", ServiceEta.formatRelative(f))
    }

    @Test fun formatRelativeSingularDay() {
        val f = ServiceEtaForecast(daysAway = 1, dueAtMillis = now, gate = EtaGate.KM, isOverdue = false)
        assertEquals("~1 day", ServiceEta.formatRelative(f))
    }

    // ---------- helpers ----------

    private fun rideOf(id: Long, startedDaysAgo: Long, startOdo: Int, distanceKm: Int) =
        dev.mrwick.gixxerbridge.data.RideEntity(
            id = id,
            startedAtMillis = now - startedDaysAgo * msPerDay,
            endedAtMillis = now - startedDaysAgo * msPerDay + 30 * 60_000L,
            startOdoKm = startOdo,
            endOdoKm = startOdo + distanceKm,
            maxSpeedKmh = 60,
            avgSpeedKmh = 35.0,
            sampleCount = 10,
            fuelBarsStart = 4,
            fuelBarsEnd = 3,
        )
}
