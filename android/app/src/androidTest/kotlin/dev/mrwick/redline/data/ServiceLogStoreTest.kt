package dev.mrwick.redline.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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
 * Instrumented test for [ServiceLogStore] and the underlying Room schema.
 *
 * Lives in androidTest/ for the same reason as [RideStoreTest]: Room's
 * in-memory builder needs a real [Context].
 */
@RunWith(AndroidJUnit4::class)
class ServiceLogStoreTest {

    private lateinit var db: GixxerDatabase
    private lateinit var store: ServiceLogStore

    @Before fun setUp() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, GixxerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = ServiceLogStore(db.serviceLogDao())
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun addInsertsRowAndReturnsPositiveId() = runBlocking {
        val id = store.add(odo = 5000, type = "Oil change", rupees = 850.0, notes = "Synthetic")
        assertTrue("inserted id must be positive", id > 0L)
        val all = store.all()
        assertEquals(1, all.size)
        val e = all[0]
        assertEquals(id, e.id)
        assertEquals(5000, e.odometerKm)
        assertEquals("Oil change", e.type)
        assertEquals(850.0, e.rupees!!, 0.0001)
        assertEquals("Synthetic", e.notes)
    }

    @Test fun nullableRupeesAndNotesArePersisted() = runBlocking {
        val id = store.add(odo = 12_000, type = "Brake pads", rupees = null, notes = null)
        val row = store.all().first { it.id == id }
        assertNull(row.rupees)
        assertNull(row.notes)
    }

    @Test fun deleteRemovesById() = runBlocking {
        val id1 = store.add(0, "Oil change", null, null)
        val id2 = store.add(100, "Chain lube", null, null)
        assertEquals(2, store.all().size)

        store.delete(id1)
        val remaining = store.all()
        assertEquals(1, remaining.size)
        assertEquals(id2, remaining[0].id)
    }

    @Test fun observeReturnsRowsNewestFirst() = runBlocking {
        // Insertion order: A then B. We can't directly set tMillis (the store
        // captures System.currentTimeMillis()), so the most-recently-inserted
        // row should come first.
        val idA = store.add(0, "A", null, null)
        Thread.sleep(5) // ensure distinct tMillis on fast machines
        val idB = store.add(0, "B", null, null)

        val snapshot = store.observe().first()
        assertEquals(2, snapshot.size)
        assertNotNull(snapshot.find { it.id == idA })
        assertNotNull(snapshot.find { it.id == idB })
        // Newest first: B should come before A.
        assertEquals(idB, snapshot[0].id)
        assertEquals(idA, snapshot[1].id)
    }
}
