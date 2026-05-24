package dev.mrwick.gixxerbridge.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for [RideStore] and the underlying Room schema.
 *
 * NOTE: this lives under `androidTest/` rather than `test/` because
 * [Room.inMemoryDatabaseBuilder] needs a real [Context]. Running under
 * Robolectric in plain JVM is possible but adds a heavyweight dep — the
 * pragmatic choice is the standard AndroidJUnit4 runner against a connected
 * emulator/device. Schema + aggregate logic is what we care about here, not
 * Android-specific behaviour.
 */
@RunWith(AndroidJUnit4::class)
class RideStoreTest {

    private lateinit var db: GixxerDatabase
    private lateinit var store: RideStore

    @Before fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, GixxerDatabase::class.java)
            .allowMainThreadQueries() // tests use runBlocking on main thread
            .build()
        store = RideStore(db.rideDao())
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun startAppendEndRideRoundTrip() = runBlocking {
        val rideId = store.startRide(
            startedAtMillis = 1_000L,
            startOdoKm = 12_345,
            fuelBars = 8,
        )
        assertTrue("rideId must be positive", rideId > 0L)

        // Three samples with known speeds: max should be 60, avg = (40+60+50)/3 = 50.0
        store.appendSample(rideId, tMillis = 1_100L, speedKmh = 40, odometerKm = 12_345, tripA = 0.0, tripB = 0.0, fuelBars = 8, fuelEconKml = 45.0)
        store.appendSample(rideId, tMillis = 1_200L, speedKmh = 60, odometerKm = 12_346, tripA = 1.0, tripB = 1.0, fuelBars = 8, fuelEconKml = 44.5)
        store.appendSample(rideId, tMillis = 1_300L, speedKmh = 50, odometerKm = 12_347, tripA = 2.0, tripB = 2.0, fuelBars = 7, fuelEconKml = 44.0)

        store.endRide(rideId, endedAtMillis = 2_000L, endOdoKm = 12_347, fuelBarsEnd = 7)

        val ride = db.rideDao().getRide(rideId)
        assertNotNull("ride should still exist after endRide", ride)
        ride!!
        assertEquals(1_000L, ride.startedAtMillis)
        assertEquals(2_000L, ride.endedAtMillis)
        assertEquals(12_345, ride.startOdoKm)
        assertEquals(12_347, ride.endOdoKm)
        assertEquals(60, ride.maxSpeedKmh)
        assertEquals(50.0, ride.avgSpeedKmh, 0.0001)
        assertEquals(3, ride.sampleCount)
        assertEquals(8, ride.fuelBarsStart)
        assertEquals(7, ride.fuelBarsEnd)

        val samples = store.getSamples(rideId)
        assertEquals(3, samples.size)
        // Ordered by tMillis ASC
        assertEquals(40, samples[0].speedKmh)
        assertEquals(60, samples[1].speedKmh)
        assertEquals(50, samples[2].speedKmh)
        assertEquals(1_100L, samples[0].tMillis)
        assertEquals(1_300L, samples[2].tMillis)
    }

    @Test fun rideInProgressReturnsActiveRide() = runBlocking {
        assertNull("no rides yet", store.rideInProgress())

        val id = store.startRide(startedAtMillis = 5_000L, startOdoKm = 100, fuelBars = null)
        val active = store.rideInProgress()
        assertNotNull(active)
        assertEquals(id, active!!.id)
        assertNull(active.endedAtMillis)

        store.endRide(id, endedAtMillis = 6_000L, endOdoKm = 101, fuelBarsEnd = null)
        assertNull("ended ride should no longer be in-progress", store.rideInProgress())
    }

    @Test fun deleteRideCascadesToSamples() = runBlocking {
        val id = store.startRide(startedAtMillis = 0L, startOdoKm = 0, fuelBars = null)
        store.appendSample(id, tMillis = 1L, speedKmh = 10, odometerKm = 0, tripA = 0.0, tripB = 0.0, fuelBars = null, fuelEconKml = null)
        store.appendSample(id, tMillis = 2L, speedKmh = 20, odometerKm = 0, tripA = 0.0, tripB = 0.0, fuelBars = null, fuelEconKml = null)
        assertEquals(2, store.getSamples(id).size)

        store.deleteRide(id)
        assertNull(db.rideDao().getRide(id))
        // Foreign key with ON DELETE CASCADE should remove samples too.
        assertEquals(0, store.getSamples(id).size)
    }

    @Test fun appendSampleUpdatesRunningAggregatesIncrementally() = runBlocking {
        val id = store.startRide(startedAtMillis = 0L, startOdoKm = 0, fuelBars = null)

        store.appendSample(id, tMillis = 1L, speedKmh = 30, odometerKm = 0, tripA = 0.0, tripB = 0.0, fuelBars = null, fuelEconKml = null)
        var r = db.rideDao().getRide(id)!!
        assertEquals(1, r.sampleCount)
        assertEquals(30, r.maxSpeedKmh)
        assertEquals(30.0, r.avgSpeedKmh, 0.0001)

        store.appendSample(id, tMillis = 2L, speedKmh = 70, odometerKm = 0, tripA = 0.0, tripB = 0.0, fuelBars = null, fuelEconKml = null)
        r = db.rideDao().getRide(id)!!
        assertEquals(2, r.sampleCount)
        assertEquals(70, r.maxSpeedKmh)
        assertEquals(50.0, r.avgSpeedKmh, 0.0001)

        // A slower sample must NOT lower max but must update avg.
        store.appendSample(id, tMillis = 3L, speedKmh = 20, odometerKm = 0, tripA = 0.0, tripB = 0.0, fuelBars = null, fuelEconKml = null)
        r = db.rideDao().getRide(id)!!
        assertEquals(3, r.sampleCount)
        assertEquals(70, r.maxSpeedKmh)
        assertEquals(40.0, r.avgSpeedKmh, 0.0001) // (30+70+20)/3
    }
}
