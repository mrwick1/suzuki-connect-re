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
        maxSpeed: Int = 0, fuelStart: Int? = null, fuelEnd: Int? = null,
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
}
