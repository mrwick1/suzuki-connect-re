package dev.mrwick.redline.export

import dev.mrwick.redline.data.FuelFillEntity
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideLocationEntity
import dev.mrwick.redline.data.RideSampleEntity
import dev.mrwick.redline.data.ServiceItem
import dev.mrwick.redline.data.ServiceItemState
import dev.mrwick.redline.data.ServiceLogEntity
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllBikeExporterTest {

    private val zone = ZoneId.of("Asia/Kolkata")
    // Fixed clock: deterministic.
    private val now = 1_781_000_000_000L

    private fun ride(
        id: Long, start: Long, end: Long?, startOdo: Int, endOdo: Int?,
        maxS: Int = 60, avgS: Double = 30.0, n: Int = 2,
        parent: Long? = null, merged: Boolean = false, name: String? = null,
    ) = RideEntity(
        id = id, startedAtMillis = start, endedAtMillis = end,
        startOdoKm = startOdo, endOdoKm = endOdo, maxSpeedKmh = maxS,
        avgSpeedKmh = avgS, sampleCount = n, fuelBarsStart = 6, fuelBarsEnd = 5,
        name = name, parentRideId = parent, isMerged = merged,
    )

    private fun sample(rid: Long, t: Long, speed: Int, odo: Int) =
        RideSampleEntity(
            id = 0, rideId = rid, tMillis = t, speedKmh = speed,
            odometerKm = odo, tripAKm = 1.0, tripBKm = 2.0,
            fuelBars = 5, fuelEconKml = 45.0,
        )

    private fun fixture(): String {
        val normal = ride(1, now - 86_400_000L, now - 86_000_000L, 16882, 16887)
        val parent = ride(98, now - 70_000_000L, now - 60_000_000L, 17355, 17683, merged = true)
        val child1 = ride(73, now - 70_000_000L, now - 69_000_000L, 17355, 17357, parent = 98)
        val child2 = ride(74, now - 69_000_000L, now - 60_000_000L, 17357, 17683, parent = 98)
        val rides = listOf(normal, parent, child1, child2)

        val samplesByRide = mapOf(
            1L to listOf(sample(1, now - 86_400_000L, 0, 16882), sample(1, now - 86_300_000L, 40, 16885)),
            73L to listOf(sample(73, now - 70_000_000L, 30, 17355)),
            74L to listOf(sample(74, now - 69_000_000L, 55, 17360)),
        )
        val locationsByRide = mapOf(
            74L to listOf(
                RideLocationEntity(0, 74, now - 69_000_000L, 11.2176, 75.8299, 12.0, 8f),
                RideLocationEntity(0, 74, now - 68_000_000L, 11.2148, 75.8290, 13.0, 7f),
            ),
        )
        val fills = listOf(
            FuelFillEntity(1, now - 1_200_000_000L, 16891, 9.49, 1098.56, null),
            FuelFillEntity(2, now - 600_000_000L, 17271, 7.73, 892.35, null),
            FuelFillEntity(3, now - 100_000_000L, 17749, 9.18, 1044.68, null),
        )
        val services = listOf(
            ServiceLogEntity(1, now - 2_000_000_000L, 15000, "Oil change", 450.0, "synthetic"),
        )
        val schedule = mapOf(
            ServiceItem.PERIODIC_SERVICE to ServiceItemState(
                item = ServiceItem.PERIODIC_SERVICE, kmThreshold = 3500,
                daysThreshold = 120, lastServiceDateMs = now - 2_000_000_000L,
                lastServiceOdoKm = 15000,
            ),
        )
        return AllBikeExporter.build(
            rides = rides,
            samplesByRide = samplesByRide,
            locationsByRide = locationsByRide,
            fills = fills,
            services = services,
            currentOdoKm = 17763,
            currentFuelBars = 5,
            tankCapacityL = 12.0,
            serviceIntervalKm = 3500,
            serviceSchedule = schedule,
            bikeMac = "AA:BB:CC:DD:EE:FF",
            bikeInfo = null,
            riderName = "Arjun",
            zone = zone,
            now = now,
        )
    }

    @Test fun `has all top-level sections`() {
        val out = fixture()
        listOf(
            "=== DATA QUALITY NOTES ===", "=== BIKE PROFILE ===", "=== HEALTH ===",
            "=== FUEL & MILEAGE ===", "=== RUNNING COST ===", "=== SERVICE ===",
            "=== PERFORMANCE ===", "=== HABITS ===", "=== PER-RIDE SUMMARY",
            "=== FUEL FILLS (CSV) ===", "=== SERVICE LOGS (CSV) ===",
            "=== GPS TRACKS (CSV) ===", "=== FULL TELEMETRY",
        ).forEach { assertTrue("missing section: $it", out.contains(it)) }
    }

    @Test fun `data quality notes warn about BLE econ over-read`() {
        assertTrue(fixture().contains("over-read", ignoreCase = true))
    }

    @Test fun `per-ride table counts top-level rides only (no children double-count)`() {
        // 1 normal + 1 merged parent = 2 top-level rides; children 73/74 excluded.
        val out = fixture()
        val tableHeader = out.substringAfter("=== PER-RIDE SUMMARY")
        assertTrue("expected 2 top-level rides in header", tableHeader.contains("(2)"))
    }

    @Test fun `telemetry CSV includes child samples but not merged parent`() {
        val out = fixture()
        val tele = out.substringAfter("=== FULL TELEMETRY")
        assertTrue("child 73 telemetry present", tele.contains("ride 73"))
        assertTrue("child 74 telemetry present", tele.contains("ride 74"))
        assertFalse("merged parent 98 must be skipped (0 samples)", tele.contains("ride 98"))
    }

    @Test fun `fuel fills CSV has one row per fill`() {
        val out = fixture()
        val block = out.substringAfter("=== FUEL FILLS (CSV) ===").substringBefore("===")
        // 3 fills => 3 data rows (+1 header line)
        assertTrue(block.trim().lines().count { it.contains(",") } >= 4)
    }
}
