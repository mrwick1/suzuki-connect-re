package dev.mrwick.gixxerbridge.export

import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JUnit4 unit tests for [TripShareText.build].
 *
 * All entity timestamps reference 2023-11-14T22:13:20Z (1_700_000_000_000 ms)
 * so assertions on formatted dates are stable regardless of when the test runs.
 *
 * Zone is always "UTC" for determinism.
 */
class TripShareTextTest {

    // ---------- shared fixtures ----------

    private val rideComplete = RideEntity(
        id = 7L,
        startedAtMillis = 1_700_000_000_000L,   // 2023-11-14 22:13:20 UTC
        endedAtMillis   = 1_700_001_800_000L,   // +30 min
        startOdoKm      = 10_000,
        endOdoKm        = 10_025,               // 25 km
        maxSpeedKmh     = 92,
        avgSpeedKmh     = 48.5,
        sampleCount     = 1800,
        fuelBarsStart   = 10,
        fuelBarsEnd     = 8,
        name            = "Evening commute",
    )

    private fun makeSample(
        id: Long,
        tMillis: Long,
        speedKmh: Int,
        odometerKm: Int = 10_000 + id.toInt(),
        tripAKm: Double = id * 0.1,
        tripBKm: Double = id * 0.05,
        fuelBars: Int? = 9,
        fuelEconKml: Double? = 42.0,
    ) = RideSampleEntity(
        id         = id,
        rideId     = 7L,
        tMillis    = tMillis,
        speedKmh   = speedKmh,
        odometerKm = odometerKm,
        tripAKm    = tripAKm,
        tripBKm    = tripBKm,
        fuelBars   = fuelBars,
        fuelEconKml = fuelEconKml,
    )

    /** Three samples spread across city / highway / fast speeds. */
    private val threesamples = listOf(
        makeSample(1L, 1_700_000_000_000L, speedKmh = 25),  // 0–29 bucket
        makeSample(2L, 1_700_000_060_000L, speedKmh = 55),  // 30–59 bucket
        makeSample(3L, 1_700_000_120_000L, speedKmh = 75),  // 60–89 bucket
    )

    private val twoLocations = listOf(
        RideLocationEntity(
            id = 1L, rideId = 7L,
            tMillis   = 1_700_000_000_000L,
            lat       = 12.9716,
            lng       = 77.5946,
            altitudeM = 900.0,
            accuracyM = 5.0f,
        ),
        RideLocationEntity(
            id = 2L, rideId = 7L,
            tMillis   = 1_700_000_900_000L,
            lat       = 12.9760,
            lng       = 77.6000,
            altitudeM = 920.0,
            accuracyM = 6.0f,
        ),
    )

    // ---------- preamble ----------

    @Test fun build_containsPreamble() {
        val out = TripShareText.build(rideComplete, threesamples, emptyList(), "UTC")
        assertTrue(
            "output should open with the preamble instruction",
            out.contains("Analyse this motorcycle ride"),
        )
    }

    // ---------- ride name ----------

    @Test fun build_containsRideName() {
        val out = TripShareText.build(rideComplete, threesamples, emptyList(), "UTC")
        assertTrue("ride name should appear", out.contains("Evening commute"))
    }

    @Test fun build_fallsBackToRideId_whenNameNull() {
        val ride = rideComplete.copy(name = null)
        val out = TripShareText.build(ride, emptyList(), emptyList(), "UTC")
        assertTrue("should fall back to Ride #7", out.contains("Ride #7"))
    }

    // ---------- timestamps ----------

    @Test fun build_containsStartDate() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertTrue("start date 2023-11-14 should appear", out.contains("2023-11-14"))
    }

    @Test fun build_containsStartTime() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertTrue("start time 22:13:20 should appear", out.contains("22:13:20"))
    }

    // ---------- duration ----------

    @Test fun build_formatsDuration_30min() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        // 1 800 s = 30 m 00 s
        assertTrue("30-minute duration should appear; got:\n$out", out.contains("30m 00s"))
    }

    @Test fun build_formatsDuration_overOneHour() {
        val ride = rideComplete.copy(endedAtMillis = rideComplete.startedAtMillis + 3_725_000L) // 1h 02m 05s
        val out = TripShareText.build(ride, emptyList(), emptyList(), "UTC")
        assertTrue("1h duration should appear; got:\n$out", out.contains("1h 02m 05s"))
    }

    // ---------- distance ----------

    @Test fun build_containsDistance() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertTrue("25 km distance should appear", out.contains("25 km"))
    }

    @Test fun build_containsStartAndEndOdo() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertTrue("start odo 10000 should appear", out.contains("10000"))
        assertTrue("end odo 10025 should appear", out.contains("10025"))
    }

    @Test fun build_noEndOdo_showsNotRecorded() {
        val ride = rideComplete.copy(endOdoKm = null, endedAtMillis = null)
        val out = TripShareText.build(ride, emptyList(), emptyList(), "UTC")
        assertTrue("missing end odo should be noted", out.contains("not recorded"))
    }

    // ---------- speed ----------

    @Test fun build_containsMaxAndAvgSpeed() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertTrue("max speed 92 should appear", out.contains("92"))
        assertTrue("avg speed 48.5 should appear", out.contains("48.5"))
    }

    @Test fun build_speedProfile_bucketsPresent() {
        val out = TripShareText.build(rideComplete, threesamples, emptyList(), "UTC")
        // Each of the three speed buckets should appear in the profile line
        assertTrue("0–29 bucket should appear", out.contains("0–29"))
        assertTrue("30–59 bucket should appear", out.contains("30–59"))
        assertTrue("60–89 bucket should appear", out.contains("60–89"))
    }

    @Test fun build_speedProfile_120plusBucket_appearsWhenPresent() {
        val fastSamples = listOf(makeSample(1L, 1_700_000_000_000L, speedKmh = 125))
        val out = TripShareText.build(rideComplete, fastSamples, emptyList(), "UTC")
        assertTrue("120+ bucket should appear for 125 km/h sample", out.contains("120+"))
    }

    // ---------- fuel ----------

    @Test fun build_containsFuelBars() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertTrue("start bars 10 should appear", out.contains("10 bars"))
        assertTrue("end bars 8 should appear", out.contains("8 bars"))
        assertTrue("fuel used 2 bar(s) should appear", out.contains("2 bar"))
    }

    @Test fun build_containsAvgEcon_fromSamples() {
        val out = TripShareText.build(rideComplete, threesamples, emptyList(), "UTC")
        // All three samples have fuelEconKml = 42.0 → average = 42.0 km/L
        assertTrue("avg econ 42.0 km/L should appear; got:\n$out", out.contains("42.0 km/L"))
    }

    @Test fun build_fuelBurntEst_labelledEstimated() {
        val out = TripShareText.build(rideComplete, threesamples, emptyList(), "UTC")
        // Fuel burnt estimate must carry the "(est.)" label per no-assumptions rule
        assertTrue("fuel burnt estimate should be labelled (est.); got:\n$out", out.contains("(est.)"))
        assertTrue("litres label should appear", out.contains(" L (est.)"))
    }

    @Test fun build_noFuelEcon_noFuelBurntLine() {
        val samplesNoEcon = threesamples.map { it.copy(fuelEconKml = null) }
        val out = TripShareText.build(rideComplete, samplesNoEcon, emptyList(), "UTC")
        assertFalse("no fuel-burnt line when no economy data", out.contains("Fuel burnt"))
    }

    // ---------- telemetry section ----------

    @Test fun build_containsSampleCount() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        // sampleCount on the entity is 1800
        assertTrue("sample count 1800 should appear", out.contains("1800"))
    }

    @Test fun build_tripAAndB_fromLastSample() {
        val out = TripShareText.build(rideComplete, threesamples, emptyList(), "UTC")
        // Last sample: id=3, tripAKm = 3*0.1 = 0.30, tripBKm = 3*0.05 = 0.15
        assertTrue("trip A 0.30 should appear; got:\n$out", out.contains("0.30"))
        assertTrue("trip B 0.15 should appear; got:\n$out", out.contains("0.15"))
    }

    // ---------- GPS section ----------

    @Test fun build_gpsSection_presentWhenLocationsNonEmpty() {
        val out = TripShareText.build(rideComplete, emptyList(), twoLocations, "UTC")
        assertTrue("GPS section header should appear", out.contains("GPS TRACK"))
        assertTrue("GPS point count 2 should appear", out.contains("2"))
    }

    @Test fun build_gpsSection_absentWhenEmpty() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertFalse("GPS section should be absent when no locations", out.contains("GPS TRACK"))
    }

    @Test fun build_boundingBox_estLabel() {
        val out = TripShareText.build(rideComplete, emptyList(), twoLocations, "UTC")
        assertTrue("bounding box estimate should be labelled (est.)", out.contains("Bounding box"))
        assertTrue("bounding box km label should appear", out.contains("km] (est.)"))
    }

    @Test fun build_altitude_gain_labelled_est() {
        val out = TripShareText.build(rideComplete, emptyList(), twoLocations, "UTC")
        // locations rise from 900 m to 920 m → gain = 20 m
        assertTrue("altitude section should appear", out.contains("Altitude"))
        assertTrue("altitude gain estimate should be labelled (est.)", out.contains("gain ≈"))
    }

    @Test fun build_altitude_absentWhenAllNull() {
        val locsNoAlt = twoLocations.map { it.copy(altitudeM = null) }
        val out = TripShareText.build(rideComplete, emptyList(), locsNoAlt, "UTC")
        assertFalse("altitude line should be absent when all altitudes null", out.contains("Altitude"))
    }

    // ---------- edge cases ----------

    @Test fun build_emptySamplesAndLocations_doesNotThrow() {
        // Minimal in-progress ride — no end times, no end odo, no samples, no GPS.
        val minimal = RideEntity(
            id              = 99L,
            startedAtMillis = 1_700_000_000_000L,
            endedAtMillis   = null,
            startOdoKm      = 5_000,
            endOdoKm        = null,
            maxSpeedKmh     = 0,
            avgSpeedKmh     = 0.0,
            sampleCount     = 0,
            fuelBarsStart   = null,
            fuelBarsEnd     = null,
            name            = null,
        )
        val out = TripShareText.build(minimal, emptyList(), emptyList(), "UTC")
        assertTrue("preamble should still be present for minimal ride", out.contains("Analyse this motorcycle ride"))
        assertTrue("in-progress label should appear", out.contains("in progress"))
        assertFalse("fuel burnt line should not appear with no data", out.contains("Fuel burnt"))
        assertFalse("GPS section should not appear with no data", out.contains("GPS TRACK"))
    }

    @Test fun build_zeroBarsUsed_notNegative() {
        // fuelBarsEnd == fuelBarsStart → 0 bars used, not negative
        val ride = rideComplete.copy(fuelBarsStart = 8, fuelBarsEnd = 8)
        val out = TripShareText.build(ride, emptyList(), emptyList(), "UTC")
        assertTrue("0 bar(s) used should appear", out.contains("0 bar"))
        assertFalse("negative bars should not appear", Regex("-\\d+ bar").containsMatchIn(out))
    }

    @Test fun build_outputIsDeterministic() {
        // Same inputs → byte-identical output (no System.currentTimeMillis calls).
        val out1 = TripShareText.build(rideComplete, threesamples, twoLocations, "UTC")
        val out2 = TripShareText.build(rideComplete, threesamples, twoLocations, "UTC")
        assertTrue("output must be deterministic", out1 == out2)
    }

    @Test fun build_sourceFooterPresent() {
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "UTC")
        assertTrue("source footer should appear", out.contains("REDLINE BLE logger"))
    }

    @Test fun build_timezone_reflected_in_times() {
        // Asia/Kolkata is UTC+5:30 → 22:13:20Z becomes 03:43:20 IST next day
        val out = TripShareText.build(rideComplete, emptyList(), emptyList(), "Asia/Kolkata")
        assertTrue("IST timezone label should appear", out.contains("IST"))
    }
}
