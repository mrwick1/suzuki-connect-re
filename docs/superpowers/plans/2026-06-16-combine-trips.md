# Combine Trips Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the rider combine consecutive key-off-cut trips into one journey entry (reversible), with start-time + gap hints on rows and a banner that suggests merging clear long journeys.

**Architecture:** True merge at the presentation layer, non-destructive at storage — a new *parent* `RideEntity` carries the journey aggregates while the original *child* rows (and their samples/GPS) stay in the DB, flagged with `parentRideId` and hidden from every list/stat query. Split-back clears the flag and deletes the parent. Detection (`JourneyDetector`) and row gap-hints are pure functions over the already-loaded ride list.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room (v4→v5 with a real migration), DataStore (Preferences) side-stores, JUnit/AndroidJUnit4 instrumented tests + plain-JVM unit tests.

---

## Background for the implementing engineer

Read these before starting — they are the files you will touch and the patterns to follow:

- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt` — `RideEntity`, `RideSampleEntity`, `RideLocationEntity`, `RideDao`, `RideStore`. Aggregates are maintained in `RideStore.appendSample`; the *final* moving-average is recomputed in `endRide` (lines 232–251) — your merge aggregate must match that convention.
- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/GixxerDatabase.kt` — Room DB, currently `version = 4` with `.fallbackToDestructiveMigration()`. **A bare version bump will wipe the rider's real history. You MUST add a real migration.**
- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideMeta.kt` — the side-store pattern (`preferencesDataStore`, JSON codec keyed by `startedAtMillis`). `JourneyDismissStore` copies this pattern.
- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsViewModel.kt` — exposes rides + meta to the screens.
- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsScreen.kt` — the list (week grouping, rows). No Scaffold/SnackbarHost yet.
- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/components/RideRow.kt` — one row. Uses `.clickable`; you will switch to `.combinedClickable`.
- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripDetailScreen.kt` — detail screen (header region lines 92–130).
- `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/Settings.kt` + `ui/settings/DeveloperSettingsScreen.kt` — where the three detector thresholds live.
- Test pattern for Room: `android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideStoreTest.kt` (in-memory DB, `AndroidJUnit4`, `runBlocking`). Plain-JVM tests live in `app/src/test/...`.

**Build/test commands** (run from `android/`):
- Unit (JVM) tests: `./gradlew :app:testDebugUnitTest`
- A single JVM test class: `./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.JourneyDetectorTest"`
- Instrumented tests (needs a connected device/emulator — `adb devices` shows one): `./gradlew :app:connectedDebugAndroidTest`
- A single instrumented class: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mrwick.gixxerbridge.data.RideMergeStoreTest`
- Compile only: `./gradlew :app:compileDebugKotlin`

Commit after every task with the personal identity (`-c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com"`), message ending with the `Co-Authored-By` trailer.

---

## Task 1: Schema v4→v5 — add `parentRideId` + `isMerged`, real migration

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt:18-37` (RideEntity)
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/GixxerDatabase.kt`
- Test: `android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt` (new)

- [ ] **Step 1: Add the two columns to `RideEntity`.** Add imports `androidx.room.ColumnInfo`. Append to the data class (after `name`):

```kotlin
@Entity(tableName = "rides")
@Immutable
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val startOdoKm: Int,
    val endOdoKm: Int?,
    val maxSpeedKmh: Int,
    val avgSpeedKmh: Double,
    val sampleCount: Int,
    val fuelBarsStart: Int?,
    val fuelBarsEnd: Int?,
    val name: String? = null,
    /** Set to the merged-parent ride's id when this row is an absorbed child
     *  segment; NULL for normal rides and for merged parents. Children are
     *  hidden from every list/stat query (see [RideDao.observeRides]). */
    val parentRideId: Long? = null,
    /** True only on a merged-parent row. Lets the detail view show the merged
     *  banner without a child-count query. Stored as INTEGER NOT NULL DEFAULT 0;
     *  the explicit defaultValue keeps Room's schema check happy after migration. */
    @ColumnInfo(defaultValue = "0") val isMerged: Boolean = false,
)
```

- [ ] **Step 2: Add the migration + bump version in `GixxerDatabase.kt`.** Add imports `androidx.room.migration.Migration` and `androidx.sqlite.db.SupportSQLiteDatabase`. Change `version = 4` to `version = 5`. Define the migration and register it:

```kotlin
        /**
         * v4→v5: add merge columns. ALTER ... ADD COLUMN is non-destructive, so
         * existing ride history survives (unlike the destructive fallback).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rides ADD COLUMN parentRideId INTEGER")
                db.execSQL("ALTER TABLE rides ADD COLUMN isMerged INTEGER NOT NULL DEFAULT 0")
            }
        }
```

In the builder chain insert `.addMigrations(MIGRATION_4_5)` before `.fallbackToDestructiveMigration()`:

```kotlin
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                GixxerDatabase::class.java,
                "gixxer.db",
            )
                .addMigrations(MIGRATION_4_5)
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
```

Also update the class KDoc version note from "Schema version is 4" to "5 (v5 adds RideEntity.parentRideId + isMerged for trip merging)".

- [ ] **Step 3: Write the failing test** (new file `RideMergeStoreTest.kt`) proving fresh rows default correctly. Mirror `RideStoreTest.kt` setup exactly.

```kotlin
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
}
```

- [ ] **Step 4: Run the test, expect PASS** (it validates the new schema compiles + defaults).

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mrwick.gixxerbridge.data.RideMergeStoreTest`
Expected: PASS.

- [ ] **Step 5: Verify the migration against the REAL pulled DB (no data loss).** A v4 copy of the rider's actual DB is at `captures/ride-pull/gixxer.db` (22 rides for 2026-06-14). Confirm the migration SQL applies cleanly and preserves rows:

Run:
```bash
cd /home/mrwick/coding/projects/suzuki-connect-re
cp captures/ride-pull/gixxer.db /tmp/mig_check.db
sqlite3 /tmp/mig_check.db "ALTER TABLE rides ADD COLUMN parentRideId INTEGER; ALTER TABLE rides ADD COLUMN isMerged INTEGER NOT NULL DEFAULT 0;"
sqlite3 /tmp/mig_check.db "SELECT count(*) AS rides, sum(isMerged) AS merged, count(parentRideId) AS non_null_parents FROM rides;"
```
Expected: `rides` ≥ 78 (all history intact), `merged` = 0, `non_null_parents` = 0. No SQL error. (This mirrors exactly what `MIGRATION_4_5` runs on-device.)

- [ ] **Step 6: Commit.**

```bash
cd /home/mrwick/coding/projects/suzuki-connect-re
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/GixxerDatabase.kt \
        android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): add ride merge columns + Room v4->v5 migration

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: DAO — hide children, add child + merge-aware reads

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt` (RideDao + RideStore)
- Test: `android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt`

- [ ] **Step 1: Filter children out of the shared list reads, and add the new DAO queries.** In `RideDao`, change the four "list / latest" queries to exclude children, and add `getChildren` + `setParent`/`clearParent` + the transactional helpers:

Replace the existing query annotations/bodies:

```kotlin
    /** Observe all top-level rides (excludes absorbed merge children), newest-first. */
    @Query("SELECT * FROM rides WHERE parentRideId IS NULL ORDER BY startedAtMillis DESC")
    fun observeRides(): Flow<List<RideEntity>>

    /** Snapshot of all top-level rides (excludes absorbed children), newest-first. */
    @Query("SELECT * FROM rides WHERE parentRideId IS NULL ORDER BY startedAtMillis DESC")
    suspend fun getAllRides(): List<RideEntity>

    /** Most-recent in-progress top-level ride, or null. */
    @Query("SELECT * FROM rides WHERE endedAtMillis IS NULL AND parentRideId IS NULL ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun getRideInProgress(): RideEntity?

    /** Most recently-ended top-level ride (has an end odometer), or null. */
    @Query("SELECT * FROM rides WHERE endOdoKm IS NOT NULL AND parentRideId IS NULL ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun getLastEndedRide(): RideEntity?
```

Add these new members to `RideDao`:

```kotlin
    /** Child segments of a merged parent, chronological. */
    @Query("SELECT * FROM rides WHERE parentRideId = :parentId ORDER BY startedAtMillis ASC")
    suspend fun getChildren(parentId: Long): List<RideEntity>

    /** Stamp a set of rides as absorbed children of [parentId]. */
    @Query("UPDATE rides SET parentRideId = :parentId WHERE id IN (:childIds)")
    suspend fun setParent(childIds: List<Long>, parentId: Long)

    /** Release all children of [parentId] back to top-level. */
    @Query("UPDATE rides SET parentRideId = NULL WHERE parentRideId = :parentId")
    suspend fun clearParent(parentId: Long)

    /** Atomically create the parent row and absorb [childIds]; returns parent id. */
    @androidx.room.Transaction
    suspend fun absorbIntoParent(parent: RideEntity, childIds: List<Long>): Long {
        val parentId = insertRide(parent)
        setParent(childIds, parentId)
        return parentId
    }

    /** Atomically release children and delete the parent row. */
    @androidx.room.Transaction
    suspend fun releaseChildrenAndDeleteParent(parentId: Long) {
        clearParent(parentId)
        deleteRide(parentId)
    }
```

- [ ] **Step 2: Expose `getChildren` on `RideStore`.** Add to `RideStore`:

```kotlin
    /** Child segments of a merged parent, chronological. */
    suspend fun getChildren(parentId: Long): List<RideEntity> = dao.getChildren(parentId)
```

- [ ] **Step 3: Write the failing test** for child-hiding. Add to `RideMergeStoreTest.kt`:

```kotlin
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
```

- [ ] **Step 4: Run, expect PASS.**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mrwick.gixxerbridge.data.RideMergeStoreTest`
Expected: PASS (both tests).

- [ ] **Step 5: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt \
        android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): hide merge children from ride lists; add child DAO queries

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Merged-ride auto-name helper

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideMergeNaming.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/data/RideMergeNamingTest.kt` (new, plain JVM)

- [ ] **Step 1: Write the failing test.**

```kotlin
package dev.mrwick.gixxerbridge.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class RideMergeNamingTest {
    // 2026-06-14 is a Sunday. Fix the zone so the day-of-week is deterministic.
    private val zone = ZoneId.of("Asia/Kolkata")
    // 2026-06-14 14:40 IST → epoch millis.
    private val sundayMillis = 1_780_390_200_000L

    @Test fun namesByDayAndDistance() {
        assertEquals("Sunday ride · 328 km", mergedRideName(sundayMillis, 328, zone))
    }

    @Test fun singularKmStillReadsKm() {
        assertEquals("Sunday ride · 1 km", mergedRideName(sundayMillis, 1, zone))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** ("unresolved reference: mergedRideName").

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.data.RideMergeNamingTest"`
Expected: compile failure / FAIL.

- [ ] **Step 3: Implement `RideMergeNaming.kt`.**

```kotlin
package dev.mrwick.gixxerbridge.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Auto-generated, editable name for a merged journey ride, e.g.
 * "Sunday ride · 328 km". The rider can override it from TripDetailScreen.
 */
fun mergedRideName(
    startedAtMillis: Long,
    distanceKm: Int,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val day = Instant.ofEpochMilli(startedAtMillis).atZone(zone).dayOfWeek
        .getDisplayName(TextStyle.FULL, Locale.US)
    return "$day ride · $distanceKm km"
}
```

- [ ] **Step 4: Run, expect PASS.**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.data.RideMergeNamingTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideMergeNaming.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/data/RideMergeNamingTest.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): merged-ride auto-name helper

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `RideStore.mergeRides` + `splitMerge`

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt`
- Test: `android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt`

- [ ] **Step 1: Add the `MergeResult` type + `mergeRides`/`splitMerge` to `RideStore.kt`.** Place `MergeResult` at file top-level (near the entities) and the methods inside `RideStore`:

```kotlin
/** Outcome of [RideStore.mergeRides]. */
sealed interface MergeResult {
    /** Merge succeeded; [parentId] is the new journey ride. */
    data class Success(val parentId: Long) : MergeResult
    /** Fewer than two distinct rides were selected. */
    data object TooFew : MergeResult
    /** A selected id was missing, in-progress, or already a merged/child row. */
    data class InvalidSelection(val reason: String) : MergeResult
    /** Selected segments don't chain on the odometer (a segment is missing). */
    data class NotContiguous(val reason: String) : MergeResult
}
```

```kotlin
    /**
     * Merge [rideIds] into one parent journey ride. The selected rides become
     * hidden children; a new parent row carries the combined aggregates. Samples
     * and GPS stay attached to the children (so [splitMerge] is lossless).
     *
     * Validation: ≥ 2 ids; every id is an existing, ended, top-level
     * (non-merged, non-child) ride; sorted by start they chain on the odometer.
     */
    suspend fun mergeRides(rideIds: List<Long>): MergeResult {
        val distinct = rideIds.distinct()
        if (distinct.size < 2) return MergeResult.TooFew

        val rides = distinct.mapNotNull { dao.getRide(it) }
        if (rides.size != distinct.size) {
            return MergeResult.InvalidSelection("One or more selected rides no longer exist.")
        }
        if (rides.any { it.endedAtMillis == null || it.endOdoKm == null }) {
            return MergeResult.InvalidSelection("Can't merge a ride that's still in progress.")
        }
        if (rides.any { it.isMerged || it.parentRideId != null }) {
            return MergeResult.InvalidSelection("Can't merge a ride that's already part of a merge.")
        }

        val sorted = rides.sortedBy { it.startedAtMillis }
        for (i in 1 until sorted.size) {
            if (sorted[i].startOdoKm != sorted[i - 1].endOdoKm) {
                return MergeResult.NotContiguous(
                    "These trips don't join up — there's a gap in the odometer between them."
                )
            }
        }

        val first = sorted.first()
        val last = sorted.last()
        val startOdo = first.startOdoKm
        val endOdo = last.endOdoKm!!
        val distanceKm = (endOdo - startOdo).coerceAtLeast(0)

        // Recompute average over MOVING samples across all children — matches the
        // endRide convention (RideStore.endRide). Falls back to the segment-count-
        // weighted average if no moving samples exist.
        val childSamples = sorted.flatMap { dao.getSamples(it.id) }
        val movingSpeeds = childSamples.map { it.speedKmh }.filter { it > 0 }
        val totalSamples = sorted.sumOf { it.sampleCount }
        val avg = when {
            movingSpeeds.isNotEmpty() -> movingSpeeds.average()
            totalSamples > 0 -> sorted.sumOf { it.avgSpeedKmh * it.sampleCount } / totalSamples
            else -> 0.0
        }

        val parent = RideEntity(
            startedAtMillis = first.startedAtMillis,
            endedAtMillis = last.endedAtMillis,
            startOdoKm = startOdo,
            endOdoKm = endOdo,
            maxSpeedKmh = sorted.maxOf { it.maxSpeedKmh },
            avgSpeedKmh = avg,
            sampleCount = totalSamples,
            fuelBarsStart = first.fuelBarsStart,
            fuelBarsEnd = last.fuelBarsEnd,
            name = mergedRideName(first.startedAtMillis, distanceKm),
            parentRideId = null,
            isMerged = true,
        )
        val parentId = dao.absorbIntoParent(parent, sorted.map { it.id })
        return MergeResult.Success(parentId)
    }

    /** Reverse a merge: release the children and delete the parent. No-op if the
     *  parent is gone or isn't a merged ride. */
    suspend fun splitMerge(parentId: Long) {
        val parent = dao.getRide(parentId) ?: return
        if (!parent.isMerged) return
        dao.releaseChildrenAndDeleteParent(parentId)
    }
```

- [ ] **Step 2: Write the failing tests.** Add to `RideMergeStoreTest.kt`. The fixture mirrors three contiguous 2026-06-14 segments.

```kotlin
    @Test fun mergeContiguousRidesProducesParentAggregates() = runBlocking {
        // Three contiguous segments: odo 100→110→140→150, speeds give max 99.
        val a = seedRide(1_000L, 2_000L, 100, 110, maxSpeed = 42, fuelStart = 5, fuelEnd = 5)
        store.appendSample(a, 1_500L, speedKmh = 40, odometerKm = 105, tripA = 0.0, tripB = 0.0, fuelBars = 5, fuelEconKml = null)
        val b = seedRide(3_000L, 4_000L, 110, 140, maxSpeed = 99, fuelStart = 5, fuelEnd = 4)
        store.appendSample(b, 3_500L, speedKmh = 80, odometerKm = 125, tripA = 0.0, tripB = 0.0, fuelBars = 5, fuelEconKml = null)
        val c = seedRide(5_000L, 6_000L, 140, 150, maxSpeed = 60, fuelStart = 4, fuelEnd = 4)
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
        assertEquals(99, parent.maxSpeedKmh)
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
```

- [ ] **Step 3: Run, expect PASS.**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mrwick.gixxerbridge.data.RideMergeStoreTest`
Expected: PASS (all tests).

- [ ] **Step 4: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt \
        android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): mergeRides + splitMerge with contiguity validation

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Merge-aware sample/location reads for the detail view

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt`
- Test: `android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt`

A merged parent owns no samples/locations of its own — they stay on the children. The detail view, GPX/CSV export, and sparkline must union the children. Add view-aware variants that dispatch on `isMerged`.

- [ ] **Step 1: Add view-aware reads to `RideStore`.**

```kotlin
    /** Samples for display: a merged parent unions its children's samples
     *  (chronological); a normal ride returns its own. */
    suspend fun getSamplesForView(rideId: Long): List<RideSampleEntity> {
        val ride = dao.getRide(rideId) ?: return emptyList()
        return if (ride.isMerged) {
            dao.getChildren(rideId).flatMap { dao.getSamples(it.id) }.sortedBy { it.tMillis }
        } else {
            dao.getSamples(rideId)
        }
    }

    /** GPS locations for display: merged parent unions children's tracks. */
    suspend fun getLocationsForView(rideId: Long): List<RideLocationEntity> {
        val ride = dao.getRide(rideId) ?: return emptyList()
        return if (ride.isMerged) {
            dao.getChildren(rideId).flatMap { dao.getLocations(it.id) }.sortedBy { it.tMillis }
        } else {
            dao.getLocations(rideId)
        }
    }
```

- [ ] **Step 2: Write the failing test.** Add to `RideMergeStoreTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run, expect PASS.**

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mrwick.gixxerbridge.data.RideMergeStoreTest`
Expected: PASS.

- [ ] **Step 4: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt \
        android/app/src/androidTest/kotlin/dev/mrwick/gixxerbridge/data/RideMergeStoreTest.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): merge-aware sample/location reads for detail view

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `JourneyDetector` + gap-hint formatting (pure functions)

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/JourneyDetector.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/JourneyDetectorTest.kt` (new, plain JVM)

- [ ] **Step 1: Write the failing test.**

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyDetectorTest {

    private val cfg = JourneyConfig(gapMaxMin = 120, minSegments = 3, minTotalKm = 80)

    /** Build an ended ride. Times in minutes-from-zero for readability. */
    private fun ride(id: Long, startMin: Long, endMin: Long, startOdo: Int, endOdo: Int) =
        RideEntity(
            id = id,
            startedAtMillis = startMin * 60_000L,
            endedAtMillis = endMin * 60_000L,
            startOdoKm = startOdo, endOdoKm = endOdo,
            maxSpeedKmh = 0, avgSpeedKmh = 0.0, sampleCount = 0,
            fuelBarsStart = null, fuelBarsEnd = null,
        )

    @Test fun detectsLongContiguousJourney() {
        // 4 segments, gaps 15/15/15 min, odo 0→120 (120 km)
        val rides = listOf(
            ride(1, 0, 30, 0, 40),
            ride(2, 45, 75, 40, 70),
            ride(3, 90, 120, 70, 100),
            ride(4, 135, 165, 100, 120),
        )
        val s = JourneyDetector.detect(rides, cfg)
        assertEquals(1, s.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), s[0].rideIds)
        assertEquals(120, s[0].totalKm)
        assertEquals(0L, s[0].startMillis)
    }

    @Test fun ignoresShortErrandDay() {
        // 4 segments, small gaps but only 30 km total → below minTotalKm
        val rides = listOf(
            ride(1, 0, 10, 0, 8),
            ride(2, 20, 30, 8, 15),
            ride(3, 40, 50, 15, 22),
            ride(4, 60, 70, 22, 30),
        )
        assertTrue(JourneyDetector.detect(rides, cfg).isEmpty())
    }

    @Test fun splitsRunAtLargeGap() {
        // first 3 chain (0→90), then a 3h gap, then 2 more — only the first run
        // qualifies (90km, 3 segs); second run is 2 segs → rejected by minSegments
        val rides = listOf(
            ride(1, 0, 30, 0, 30),
            ride(2, 45, 75, 30, 60),
            ride(3, 90, 120, 60, 90),
            ride(4, 300, 330, 90, 120),
            ride(5, 345, 375, 120, 150),
        )
        val s = JourneyDetector.detect(rides, cfg)
        assertEquals(1, s.size)
        assertEquals(listOf(1L, 2L, 3L), s[0].rideIds)
    }

    @Test fun breaksRunWhenOdometerDoesNotChain() {
        // gap in odometer between seg 2 and 3 (60 != 65) splits the run
        val rides = listOf(
            ride(1, 0, 30, 0, 30),
            ride(2, 45, 75, 30, 60),
            ride(3, 90, 120, 65, 95),
            ride(4, 135, 165, 95, 125),
        )
        // run1 = {1,2} (2 segs, rejected); run2 = {3,4} (2 segs, rejected)
        assertTrue(JourneyDetector.detect(rides, cfg).isEmpty())
    }

    @Test fun gapHintFormatting() {
        assertEquals("15 min later", gapHintLabel(15))
        assertEquals("1 h 5 min later", gapHintLabel(65))
        assertEquals("2 h later", gapHintLabel(120))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** (unresolved references).

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.JourneyDetectorTest"`
Expected: compile failure / FAIL.

- [ ] **Step 3: Implement `JourneyDetector.kt`.**

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideEntity

/** Tunable thresholds for [JourneyDetector]. Defaults mirror the spec. */
data class JourneyConfig(
    val gapMaxMin: Int = 120,
    val minSegments: Int = 3,
    val minTotalKm: Int = 80,
)

/** A detected run of consecutive segments that look like one long journey. */
data class JourneySuggestion(
    val rideIds: List<Long>,
    val totalKm: Int,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Detects "clear long journeys": maximal runs of consecutive ended rides where
 * each inter-ride gap is ≤ [JourneyConfig.gapMaxMin] AND the odometer chains.
 * Odo-chaining alone is monotonic across all history, so the time gap is the real
 * journey discriminator (see design doc). A run qualifies when it has at least
 * [JourneyConfig.minSegments] segments and covers ≥ [JourneyConfig.minTotalKm].
 */
object JourneyDetector {
    fun detect(rides: List<RideEntity>, cfg: JourneyConfig): List<JourneySuggestion> {
        // Only fully-ended rides participate; sort chronologically.
        val ended = rides.filter { it.endedAtMillis != null && it.endOdoKm != null }
            .sortedBy { it.startedAtMillis }
        if (ended.isEmpty()) return emptyList()

        val out = mutableListOf<JourneySuggestion>()
        var run = mutableListOf(ended.first())

        fun closeRun() {
            if (run.size >= cfg.minSegments) {
                val first = run.first()
                val last = run.last()
                val km = (last.endOdoKm!! - first.startOdoKm).coerceAtLeast(0)
                if (km >= cfg.minTotalKm) {
                    out += JourneySuggestion(
                        rideIds = run.map { it.id },
                        totalKm = km,
                        startMillis = first.startedAtMillis,
                        endMillis = last.endedAtMillis!!,
                    )
                }
            }
        }

        for (i in 1 until ended.size) {
            val prev = run.last()
            val cur = ended[i]
            val gapMin = (cur.startedAtMillis - prev.endedAtMillis!!) / 60_000L
            val chains = cur.startOdoKm == prev.endOdoKm
            if (gapMin in 0..cfg.gapMaxMin.toLong() && chains) {
                run.add(cur)
            } else {
                closeRun()
                run = mutableListOf(cur)
            }
        }
        closeRun()
        return out
    }
}

/** Human label for the inter-ride gap connector, e.g. "15 min later",
 *  "1 h 5 min later", "2 h later". */
fun gapHintLabel(gapMin: Long): String {
    val h = gapMin / 60
    val m = gapMin % 60
    return when {
        h == 0L -> "$m min later"
        m == 0L -> "$h h later"
        else -> "$h h $m min later"
    }
}
```

- [ ] **Step 4: Run, expect PASS.**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.JourneyDetectorTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/JourneyDetector.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/JourneyDetectorTest.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): JourneyDetector + gap-hint formatting

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: `JourneyDismissStore` side-store (dismissed suggestions)

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/JourneyDismissStore.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/data/JourneyDismissCodecTest.kt` (new, plain JVM)

Follow the `RideMeta.kt` side-store pattern (a `preferencesDataStore`, one JSON key). Persisting only the `startMillis` longs of dismissed suggestions keeps it tiny.

- [ ] **Step 1: Write the failing codec test** (pure, no Android — test the encode/decode of the long-set).

```kotlin
package dev.mrwick.gixxerbridge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyDismissCodecTest {
    @Test fun roundTrips() {
        val set = setOf(1_780_390_200_000L, 1_780_476_600_000L)
        assertEquals(set, JourneyDismissCodec.decode(JourneyDismissCodec.encode(set)))
    }

    @Test fun decodeBlankIsEmpty() {
        assertEquals(emptySet<Long>(), JourneyDismissCodec.decode(""))
        assertEquals(emptySet<Long>(), JourneyDismissCodec.decode("[]"))
    }
}
```

- [ ] **Step 2: Run, expect FAIL.**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.data.JourneyDismissCodecTest"`
Expected: FAIL.

- [ ] **Step 3: Implement `JourneyDismissStore.kt`** (mirror `RideMeta.kt` structure: a codec object + a store class over a `preferencesDataStore`).

```kotlin
package dev.mrwick.gixxerbridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray

/** Encodes a set of dismissed journey-suggestion keys (first-segment start millis)
 *  as a JSON array string. Kept trivially small. */
object JourneyDismissCodec {
    fun encode(keys: Set<Long>): String {
        val arr = JSONArray()
        keys.sorted().forEach { arr.put(it) }
        return arr.toString()
    }

    fun decode(raw: String): Set<Long> {
        if (raw.isBlank()) return emptySet()
        val arr = JSONArray(raw)
        return buildSet { for (i in 0 until arr.length()) add(arr.getLong(i)) }
    }
}

private val Context.journeyDismissDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "journey_dismiss")

/** Persists which journey suggestions the rider has dismissed so they don't
 *  reappear. Side-store (DataStore), independent of Room. */
class JourneyDismissStore(private val context: Context) {
    private val key = stringPreferencesKey("dismissed_keys")

    fun observe(): Flow<Set<Long>> = context.journeyDismissDataStore.data
        .map { prefs -> JourneyDismissCodec.decode(prefs[key] ?: "") }

    suspend fun dismiss(startMillis: Long) {
        context.journeyDismissDataStore.edit { prefs ->
            val cur = JourneyDismissCodec.decode(prefs[key] ?: "")
            prefs[key] = JourneyDismissCodec.encode(cur + startMillis)
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS.**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.data.JourneyDismissCodecTest"`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/JourneyDismissStore.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/data/JourneyDismissCodecTest.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): JourneyDismissStore for dismissed merge suggestions

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: Detector thresholds in Settings + Developer settings

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/Settings.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/settings/DeveloperSettingsScreen.kt`

> Read both files first — match the existing key/flow/setter pattern in `Settings.kt` and the existing control style (sliders/steppers/switches) in `DeveloperSettingsScreen.kt`. The snippets below are the shape to follow; adapt names to the file's conventions.

- [ ] **Step 1: Add three int settings to `Settings.kt`** following the existing pattern (one `intPreferencesKey`, one exposed flow, one setter per value). Defaults: `journeyGapMaxMin = 120`, `journeyMinSegments = 3`, `journeyMinTotalKm = 80`. Expose a combined accessor that builds a `JourneyConfig`:

```kotlin
// keys
private val JOURNEY_GAP_MAX_MIN = intPreferencesKey("journey_gap_max_min")
private val JOURNEY_MIN_SEGMENTS = intPreferencesKey("journey_min_segments")
private val JOURNEY_MIN_TOTAL_KM = intPreferencesKey("journey_min_total_km")

// in the settings flow mapping (alongside existing fields):
val journeyConfig: Flow<JourneyConfig> = dataStore.data.map { p ->
    JourneyConfig(
        gapMaxMin = p[JOURNEY_GAP_MAX_MIN] ?: 120,
        minSegments = p[JOURNEY_MIN_SEGMENTS] ?: 3,
        minTotalKm = p[JOURNEY_MIN_TOTAL_KM] ?: 80,
    )
}

suspend fun setJourneyGapMaxMin(v: Int) = dataStore.edit { it[JOURNEY_GAP_MAX_MIN] = v }
suspend fun setJourneyMinSegments(v: Int) = dataStore.edit { it[JOURNEY_MIN_SEGMENTS] = v }
suspend fun setJourneyMinTotalKm(v: Int) = dataStore.edit { it[JOURNEY_MIN_TOTAL_KM] = v }
```

Import `dev.mrwick.gixxerbridge.analytics.JourneyConfig`. (If `Settings.kt` uses a single immutable data class snapshot instead of per-field flows, add the three fields there with the same defaults and a `journeyConfig` convenience getter on that class — follow whichever pattern the file already uses.)

- [ ] **Step 2: Add a "Journey suggestions" section to `DeveloperSettingsScreen.kt`** with three numeric controls (reuse the screen's existing stepper/slider component) bound to the setters above, labelled "Max gap (min)", "Min segments", "Min distance (km)".

- [ ] **Step 3: Compile.**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/Settings.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/settings/DeveloperSettingsScreen.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): expose journey-detector thresholds in dev settings

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 9: `TripsViewModel` — merge/split ops + suggestion stream

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsViewModel.kt`

- [ ] **Step 1: Wire the new stores + ops into `TripsViewModel`.** Add fields and methods. Use `getSamplesForView`/`getLocationsForView` so merged parents render their children's data.

Add imports: `dev.mrwick.gixxerbridge.analytics.JourneyConfig`, `dev.mrwick.gixxerbridge.analytics.JourneyDetector`, `dev.mrwick.gixxerbridge.analytics.JourneySuggestion`, `dev.mrwick.gixxerbridge.data.JourneyDismissStore`, `dev.mrwick.gixxerbridge.data.MergeResult`, `dev.mrwick.gixxerbridge.data.Settings` (or the existing settings accessor used elsewhere in the app), `kotlinx.coroutines.flow.combine`.

```kotlin
    private val dismissStore = JourneyDismissStore(context)
    // Reuse the app's existing Settings accessor pattern; this assumes a
    // Settings(context) exposing journeyConfig: Flow<JourneyConfig>.
    private val settings = Settings(context)

    /**
     * The single most-recent non-dismissed journey suggestion, or null. Combines
     * the live top-level rides, the dismissed-key set, and the tunable config.
     */
    val journeySuggestion: StateFlow<JourneySuggestion?> =
        combine(store.observeRides(), dismissStore.observe(), settings.journeyConfig) { rides, dismissed, cfg ->
            JourneyDetector.detect(rides, cfg)
                .filter { it.startMillis !in dismissed }
                .maxByOrNull { it.startMillis }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Dismiss a suggestion so it won't resurface. */
    fun dismissSuggestion(startMillis: Long) {
        viewModelScope.launch { dismissStore.dismiss(startMillis) }
    }

    /** Merge [rideIds]; [onResult] runs on the main scope with the outcome
     *  (so the screen can show a snackbar / clear selection). */
    fun merge(rideIds: List<Long>, onResult: (MergeResult) -> Unit) {
        viewModelScope.launch { onResult(store.mergeRides(rideIds)) }
    }

    /** Reverse a merge by parent id. */
    fun split(parentId: Long) {
        viewModelScope.launch { store.splitMerge(parentId) }
    }
```

- [ ] **Step 2: Switch the detail/export reads to the view-aware variants.** Change the three existing methods to use the merge-aware store reads:

```kotlin
    fun loadSamples(rideId: Long) {
        viewModelScope.launch { _selectedSamples.value = store.getSamplesForView(rideId) }
    }

    suspend fun locationsFor(rideId: Long): List<RideLocationEntity> =
        store.getLocationsForView(rideId)

    suspend fun samplesFor(rideId: Long): List<RideSampleEntity> =
        store.getSamplesForView(rideId)
```

(Leave `samplesForSparkline` as-is — a merged parent has no own samples, so its row sparkline is simply empty, which is acceptable. Optional enhancement out of scope.)

- [ ] **Step 3: Compile.**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If `Settings(context)` differs from the real accessor, adjust to the pattern other ViewModels use (grep `Settings(` under `ui/`).

- [ ] **Step 4: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsViewModel.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): merge/split ops + journey suggestion stream in TripsViewModel

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 10: `RideRow` — start time, selection support, long-press

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/components/RideRow.kt`

- [ ] **Step 1: Add params + start time + selection visuals.** Add `@OptIn(ExperimentalFoundationApi::class)` and imports: `androidx.compose.foundation.ExperimentalFoundationApi`, `androidx.compose.foundation.combinedClickable`, `androidx.compose.material.icons.filled.CheckCircle`, `androidx.compose.material.icons.outlined.RadioButtonUnchecked`, `androidx.compose.material3.MaterialTheme` (already present). Update the signature and click handling:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RideRow(
    ride: RideEntity,
    meta: RideMeta,
    sparklineSamples: List<RideSampleEntity>?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
```

Add a time formatter near the existing `dayFmt`/`monthFmt`:

```kotlin
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val startTimeStr = timeFmt.format(date)
```

Change the `Card` modifier from `.clickable(onClick = onClick)` to:

```kotlin
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
```

When `selected`, tint the card container. Replace the `colors =` line:

```kotlin
        colors = CardDefaults.cardColors(
            containerColor = if (selected) GixxerTokens.accent.copy(alpha = 0.12f)
                             else GixxerTokens.surface,
        ),
```

- [ ] **Step 2: Show the start time in the subtitle line.** Replace the existing subtitle `Text` (the `"${durationMin} min · ..."` one) so it leads with the clock start:

```kotlin
                Text(
                    text = if (inProgress) "In progress…"
                           else "$startTimeStr · ${durationMin} min · ${"%.0f".format(avgSpeed)} km/h avg",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (inProgress) GixxerTokens.accent else GixxerTokens.textMuted,
                )
```

- [ ] **Step 3: Swap the trailing control in selection mode.** Where the delete `IconButton` is rendered, show a select indicator instead when `selectionMode`:

```kotlin
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle
                                  else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) GixxerTokens.accent else GixxerTokens.textMuted,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete ride",
                        tint = GixxerTokens.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
```

- [ ] **Step 4: Compile.**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/components/RideRow.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): ride row start time + multi-select visuals

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 11: `TripsScreen` — selection mode, gap hints, suggestion banner, combine bar

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsScreen.kt`
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/components/JourneyBanner.kt`
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/components/GapConnector.kt`

This is the largest task — break it into the three new pieces of UI and the wiring. Read the current `TripsScreen.kt` (it returns a `LazyColumn` directly, no Scaffold).

- [ ] **Step 1: Create `GapConnector.kt`** — the thin "⋮ 15 min later" row between two segments of a run.

```kotlin
package dev.mrwick.gixxerbridge.ui.trips.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/** Subtle connector shown between two consecutive trip rows of the same
 *  short-gap run, e.g. "⋮ 15 min later". */
@Composable
fun GapConnector(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⋮", style = MaterialTheme.typography.bodySmall, color = GixxerTokens.accent)
        Text(label, style = MaterialTheme.typography.labelSmall, color = GixxerTokens.textMuted)
    }
}
```

- [ ] **Step 2: Create `JourneyBanner.kt`** — the dismissible suggestion banner.

```kotlin
package dev.mrwick.gixxerbridge.ui.trips.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/** Banner suggesting a detected long journey be combined. */
@Composable
fun JourneyBanner(
    tripCount: Int,
    dateLabel: String,
    totalKm: Int,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.accent.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "🔗 $tripCount trips on $dateLabel look like one $totalKm km journey",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GixxerTokens.textPrimary,
                )
                TextButton(onClick = onReview) { Text("Review & combine") }
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = GixxerTokens.textMuted)
            }
        }
    }
}
```

- [ ] **Step 3: Add selection + snackbar scaffolding to `TripsScreen`.** Wrap the existing content. Hoist selection state and collect the suggestion. Add imports: `androidx.compose.foundation.layout.Box`, `androidx.compose.material3.Button`, `androidx.compose.material3.Scaffold`, `androidx.compose.material3.SnackbarHost`, `androidx.compose.material3.SnackbarHostState`, `androidx.compose.material3.SnackbarResult`, `androidx.compose.material3.Text`, `androidx.compose.runtime.*` (mutableStateMapOf / rememberCoroutineScope), `androidx.compose.ui.Alignment`, `dev.mrwick.gixxerbridge.data.MergeResult`, `dev.mrwick.gixxerbridge.ui.trips.components.GapConnector`, `dev.mrwick.gixxerbridge.ui.trips.components.JourneyBanner`, `dev.mrwick.gixxerbridge.analytics.gapHintLabel`, `kotlinx.coroutines.launch`.

At the top of `TripsScreen`, after the existing `collectAsState` calls:

```kotlin
    val suggestion by vm.journeySuggestion.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Selection state: rideId set + whether we're in selection mode.
    val selected = remember { mutableStateListOf<Long>() }
    var selectionMode by remember { mutableStateOf(false) }

    fun exitSelection() { selectionMode = false; selected.clear() }
    fun toggle(id: Long) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        if (selected.isEmpty()) selectionMode = false
    }
    fun enterSelectionWith(ids: List<Long>) {
        selectionMode = true
        selected.clear(); selected.addAll(ids)
    }

    fun doCombine() {
        val ids = selected.toList()
        vm.merge(ids) { result ->
            when (result) {
                is MergeResult.Success -> {
                    exitSelection()
                    scope.launch {
                        val r = snackbarHostState.showSnackbar(
                            message = "Trips combined", actionLabel = "Undo",
                        )
                        if (r == SnackbarResult.ActionPerformed) vm.split(result.parentId)
                    }
                }
                is MergeResult.NotContiguous ->
                    scope.launch { snackbarHostState.showSnackbar(result.reason) }
                is MergeResult.InvalidSelection ->
                    scope.launch { snackbarHostState.showSnackbar(result.reason) }
                MergeResult.TooFew ->
                    scope.launch { snackbarHostState.showSnackbar("Select at least two trips") }
            }
        }
    }
```

Wrap the whole screen body in a `Scaffold` with the snackbar host and a bottom combine bar that appears in selection mode:

```kotlin
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { exitSelection() }) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { doCombine() }, enabled = selected.size >= 2) {
                        Text("Combine ${selected.size}")
                    }
                }
            }
        },
    ) { innerPadding ->
        // existing LazyColumn body goes here, with contentPadding merged with innerPadding
    }
```

Move the existing empty-state / skeleton / `LazyColumn` blocks inside the `Scaffold` content lambda. Apply `Modifier.padding(innerPadding)` to the outer container.

- [ ] **Step 4: Insert the banner as the first list item** (only when a suggestion exists and we're not already selecting). Inside the `LazyColumn`, before the `__header` item:

```kotlin
        suggestion?.let { s ->
            if (!selectionMode) item(key = "__journey_banner") {
                JourneyBanner(
                    tripCount = s.rideIds.size,
                    dateLabel = formatJourneyDate(s.startMillis),
                    totalKm = s.totalKm,
                    onReview = { enterSelectionWith(s.rideIds) },
                    onDismiss = { vm.dismissSuggestion(s.startMillis) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
```

Add a small date formatter helper at file scope:

```kotlin
private fun formatJourneyDate(millis: Long): String {
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fmt)
}
```

- [ ] **Step 5: Render rows with selection + gap connectors.** Replace the `items(weekRides, ...)` block so each row is selection-aware and a `GapConnector` is emitted between consecutive rows of the same short-gap run. Because the list is newest-first, the "older neighbour" of `weekRides[i]` is `weekRides[i + 1]`:

```kotlin
            itemsIndexed(weekRides, key = { _, r -> r.id }) { i, ride ->
                val meta = metaByRideId[ride.id] ?: RideMeta()
                Column {
                    RideRowWithSparkline(
                        ride = ride,
                        meta = meta,
                        vm = vm,
                        selectionMode = selectionMode,
                        selected = selected.contains(ride.id),
                        onOpen = {
                            if (selectionMode) toggle(ride.id) else onOpenRide(ride.id)
                        },
                        onLongOpen = {
                            if (!selectionMode) selectionMode = true
                            toggle(ride.id)
                        },
                        onDelete = { vm.delete(ride.id) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    // Gap connector to the older neighbour within this week group.
                    val older = weekRides.getOrNull(i + 1)
                    if (older != null) {
                        val gapMin = (ride.startedAtMillis - (older.endedAtMillis ?: older.startedAtMillis)) / 60_000L
                        val chains = older.endOdoKm != null && ride.startOdoKm == older.endOdoKm
                        if (chains && gapMin in 0..120) {
                            GapConnector(label = gapHintLabel(gapMin))
                        }
                    }
                }
            }
```

Use `androidx.compose.foundation.lazy.itemsIndexed` (add import; drop the old `items` import if now unused).

- [ ] **Step 6: Update `RideRowWithSparkline`** to forward the new params:

```kotlin
@Composable
private fun RideRowWithSparkline(
    ride: RideEntity,
    meta: RideMeta,
    vm: TripsViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongOpen: () -> Unit = {},
) {
    var samples by remember(ride.id) { mutableStateOf<List<RideSampleEntity>?>(null) }
    LaunchedEffect(ride.id) { samples = vm.samplesForSparkline(ride.id, limit = 60) }
    RideRow(
        ride = ride,
        meta = meta,
        sparklineSamples = samples,
        onClick = onOpen,
        onDelete = onDelete,
        selectionMode = selectionMode,
        selected = selected,
        onLongClick = onLongOpen,
        modifier = modifier,
    )
}
```

- [ ] **Step 7: Compile.**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Fix any unused-import / experimental-API opt-in warnings.

- [ ] **Step 8: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsScreen.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/components/JourneyBanner.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/components/GapConnector.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): multi-select combine, gap hints, journey suggestion banner

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 12: `TripDetailScreen` — merged banner + child list + split-back

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripDetailScreen.kt`

- [ ] **Step 1: Load children when the ride is merged.** Near the existing `locations` state (around line 103), add:

```kotlin
    var children by remember(rideId) { mutableStateOf<List<RideEntity>>(emptyList()) }
    LaunchedEffect(rideId, ride?.isMerged) {
        children = if (ride?.isMerged == true) vm.childrenOf(rideId) else emptyList()
    }
```

Add `childrenOf` to `TripsViewModel` (and the underlying store call already exists as `getChildren`):

```kotlin
    /** Child segments of a merged ride, chronological. */
    suspend fun childrenOf(parentId: Long): List<RideEntity> = store.getChildren(parentId)
```

- [ ] **Step 2: Render a merged section** in the detail body (place it right after the meta resolution / hero card, before the per-sample log). Build a small composable inline or as a private function:

```kotlin
        if (ride.isMerged) {
            MergedSegmentsCard(
                children = children,
                onSplit = {
                    vm.split(ride.id)
                    Toast.makeText(context, "Split back into segments", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
```

Add the private composable at the bottom of the file:

```kotlin
@Composable
private fun MergedSegmentsCard(
    children: List<RideEntity>,
    onSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.US) }
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Merged from ${children.size} segments",
                style = MaterialTheme.typography.titleMedium,
                color = GixxerTokens.textPrimary,
            )
            Spacer(Modifier.height(8.dp))
            children.forEach { c ->
                val km = max(0, (c.endOdoKm ?: c.startOdoKm) - c.startOdoKm)
                Text(
                    "${timeFmt.format(Date(c.startedAtMillis))} · $km km",
                    style = MaterialTheme.typography.bodyMedium,
                    color = GixxerTokens.textMuted,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSplit) { Text("Split back into segments") }
        }
    }
}
```

(`OutlinedButton`, `Card`, `Spacer`, `max`, `SimpleDateFormat`, `Date`, `Locale` are already imported in this file.)

- [ ] **Step 2b: Add a `RideEntity` import if needed** — the file already imports `dev.mrwick.gixxerbridge.data.RideEntity` (line 69), so the new state and composable compile.

- [ ] **Step 3: Compile.**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit.**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripDetailScreen.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsViewModel.kt
git -c user.name="Arjun KR" -c user.email="arjunkrishnaraj123@gmail.com" commit -m "feat(trips): merged-trip banner + split-back in detail screen

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 13: Full build + test sweep + manual smoke

**Files:** none (verification only)

- [ ] **Step 1: Run all JVM unit tests.**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; `JourneyDetectorTest`, `RideMergeNamingTest`, `JourneyDismissCodecTest` all pass, no regressions.

- [ ] **Step 2: Run instrumented store tests** (device connected — `adb devices` lists one).

Run: `cd android && ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.mrwick.gixxerbridge.data.RideMergeStoreTest`
Expected: all pass.

- [ ] **Step 3: Build + install the debug APK.**

Run: `cd android && ./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL; app updates on device. **Confirm the existing 2026-06-14 rides are still present** (migration preserved data — open Trips).

- [ ] **Step 4: Manual smoke (on device).** Verify in order:
  1. Trips list shows start times on rows and "⋮ N min later" connectors between the June 14 segments.
  2. A journey banner appears for the June 14 run ("🔗 8 trips … one 328 km journey" — count depends on which segments are same-week/grouped).
  3. Long-press a row → selection mode; select the run's segments → "Combine N" → one merged "Sunday ride · 328 km" entry appears; segments vanish from the list; Home/Stats totals unchanged (no double count).
  4. Snackbar "Undo" restores the segments.
  5. Re-merge → open the merged trip → "Merged from N segments" card lists them → "Split back into segments" restores them.
  6. Tapping the banner's dismiss (✕) makes it not reappear after app restart.

- [ ] **Step 5: Final no-op commit / branch check.** Ensure all work is committed:

Run: `cd /home/mrwick/coding/projects/suzuki-connect-re && git status --short`
Expected: clean (all changes committed across Tasks 1–12).

---

## Self-review notes (for the implementer)

- **Spec coverage:** schema+migration (T1), hide children (T2), naming (T3), merge/split + contiguity (T4), merge-aware reads (T5), detector + gap label (T6), dismiss persistence (T7), dev-settings tunables (T8), VM wiring + suggestion stream (T9), row start-time + selection (T10), list selection/banner/connectors/combine bar (T11), detail merged banner + split (T12), verification (T13). All design sections map to a task.
- **Type consistency:** `MergeResult` (T4) is consumed in T9/T11. `JourneyConfig`/`JourneySuggestion` (T6) flow through Settings (T8) and VM (T9). `getSamplesForView`/`getLocationsForView` (T5) used in T9. `getChildren` (T2) used in T5/T9/T12. `gapHintLabel` (T6) used in T11. `mergedRideName` (T3) used in T4.
- **Watch-outs:** (1) the `@ColumnInfo(defaultValue = "0")` on `isMerged` is required so Room's post-migration schema check passes — do not drop it. (2) `Settings(context)`/`journeyConfig` in T8/T9 must match the file's actual accessor shape — grep before assuming. (3) `combinedClickable` needs the `ExperimentalFoundationApi` opt-in.
