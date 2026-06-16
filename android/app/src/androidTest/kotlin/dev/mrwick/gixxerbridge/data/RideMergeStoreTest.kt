package dev.mrwick.gixxerbridge.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideMergeStoreTest {

    private lateinit var db: GixxerDatabase
    private lateinit var store: RideStore

    @Before fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, GixxerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RideStore(db.rideDao())
    }

    @After fun tearDown() { db.close() }

    /** Helper: insert a fully-ended ride and return its id. */
    private suspend fun seedRide(
        start: Long, end: Long, startOdo: Int, endOdo: Int,
        fuelStart: Int? = null, fuelEnd: Int? = null,
    ): Long {
        val id = store.startRide(startedAtMillis = start, startOdoKm = startOdo, fuelBars = fuelStart)
        store.endRide(id, endedAtMillis = end, endOdoKm = endOdo, fuelBarsEnd = fuelEnd)
        return id
    }

    @Test fun newRideDefaultsAreNotMerged() = runBlocking {
        val id = seedRide(1_000L, 2_000L, 100, 110)
        val ride = db.rideDao().getRide(id)!!
        assertNull("parentRideId defaults null", ride.parentRideId)
        assertFalse("isMerged defaults false", ride.isMerged)
    }

    @Test fun childrenAreHiddenFromGetAllRides() = runBlocking {
        val a = seedRide(1_000L, 2_000L, 100, 110)
        val b = seedRide(3_000L, 4_000L, 110, 120)
        // Manually mark b as a child of a (Task 3 will do this via mergeRides).
        db.rideDao().setParent(listOf(b), a)

        val top = store.getAllRides()
        assertEquals("only the non-child remains top-level", 1, top.size)
        assertEquals(a, top.first().id)

        val kids = store.getChildren(a)
        assertEquals(1, kids.size)
        assertEquals(b, kids.first().id)
    }

    @Test fun mergeContiguousRidesProducesParentAggregates() = runBlocking {
        // Three contiguous segments: odo 100→110→140→150. Appended sample speeds
        // (40/80/60) set each child's running max; merged parent max = 80.
        val a = seedRide(1_000L, 2_000L, 100, 110, fuelStart = 5, fuelEnd = 5)
        store.appendSample(a, 1_500L, speedKmh = 40, odometerKm = 105, tripA = 0.0, tripB = 0.0, fuelBars = 5, fuelEconKml = null)
        val b = seedRide(3_000L, 4_000L, 110, 140, fuelStart = 5, fuelEnd = 4)
        store.appendSample(b, 3_500L, speedKmh = 80, odometerKm = 125, tripA = 0.0, tripB = 0.0, fuelBars = 5, fuelEconKml = null)
        val c = seedRide(5_000L, 6_000L, 140, 150, fuelStart = 4, fuelEnd = 4)
        store.appendSample(c, 5_500L, speedKmh = 60, odometerKm = 145, tripA = 0.0, tripB = 0.0, fuelBars = 4, fuelEconKml = null)

        val result = store.mergeRides(listOf(c, a, b)) // deliberately unsorted
        assertTrue(result is MergeResult.Success)
        val parentId = (result as MergeResult.Success).parentId

        val parent = db.rideDao().getRide(parentId)!!
        assertTrue(parent.isMerged)
        assertNull(parent.parentRideId)
        assertEquals(1_000L, parent.startedAtMillis)
        assertEquals(6_000L, parent.endedAtMillis)
        assertEquals(100, parent.startOdoKm)
        assertEquals(150, parent.endOdoKm)
        assertEquals(80, parent.maxSpeedKmh)
        assertEquals(5, parent.fuelBarsStart)
        assertEquals(4, parent.fuelBarsEnd)
        // moving-sample avg = (40+80+60)/3 = 60.0
        assertEquals(60.0, parent.avgSpeedKmh, 0.0001)
        assertEquals(3, parent.sampleCount)

        // children hidden; parent visible
        val top = store.getAllRides()
        assertEquals(1, top.size)
        assertEquals(parentId, top.first().id)
    }

    @Test fun mergeRejectsNonContiguous() = runBlocking {
        val a = seedRide(1_000L, 2_000L, 100, 110)
        val b = seedRide(3_000L, 4_000L, 120, 130) // gap: 110 != 120
        val result = store.mergeRides(listOf(a, b))
        assertTrue(result is MergeResult.NotContiguous)
        assertEquals(2, store.getAllRides().size) // unchanged
    }

    @Test fun mergeRejectsFewerThanTwo() = runBlocking {
        val a = seedRide(1_000L, 2_000L, 100, 110)
        assertTrue(store.mergeRides(listOf(a)) is MergeResult.TooFew)
        assertTrue(store.mergeRides(listOf(a, a)) is MergeResult.TooFew) // distinct
    }

    @Test fun splitMergeRestoresChildren() = runBlocking {
        val a = seedRide(1_000L, 2_000L, 100, 110)
        val b = seedRide(3_000L, 4_000L, 110, 120)
        val parentId = (store.mergeRides(listOf(a, b)) as MergeResult.Success).parentId
        assertEquals(1, store.getAllRides().size)

        store.splitMerge(parentId)
        val top = store.getAllRides().map { it.id }.sorted()
        assertEquals(listOf(a, b).sorted(), top)
        assertNull("parent row deleted", db.rideDao().getRide(parentId))
    }

    @Test fun getSamplesForViewUnionsChildren() = runBlocking {
        val a = seedRide(1_000L, 2_000L, 100, 110)
        store.appendSample(a, 1_500L, speedKmh = 40, odometerKm = 105, tripA = 0.0, tripB = 0.0, fuelBars = null, fuelEconKml = null)
        val b = seedRide(3_000L, 4_000L, 110, 120)
        store.appendSample(b, 3_500L, speedKmh = 80, odometerKm = 115, tripA = 0.0, tripB = 0.0, fuelBars = null, fuelEconKml = null)
        val parentId = (store.mergeRides(listOf(a, b)) as MergeResult.Success).parentId

        val viewSamples = store.getSamplesForView(parentId)
        assertEquals(2, viewSamples.size)
        assertEquals(1_500L, viewSamples[0].tMillis) // chronological
        assertEquals(3_500L, viewSamples[1].tMillis)

        // a normal ride still returns just its own
        assertEquals(1, store.getSamplesForView(a).size)
    }
}
