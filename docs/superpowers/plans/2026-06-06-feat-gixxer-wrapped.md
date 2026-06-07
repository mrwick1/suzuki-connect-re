# Gixxer Wrapped — any-window shareable recap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A swipeable, shareable "Wrapped"-style recap over ANY time window — total km, saddle hours, ride count, longest ride, top speed (with date + ride name), busiest month, busiest weekday, best/worst tank (km/L), litres burnt, total spend (fuel + service), and longest streak. Each card is a full-screen pager page; a "Share" action exports the current card (or a summary card) as a PNG via the existing `SceneCapture` primitive.

**Architecture:** A new **pure** `WrappedAnalytics` object computes a single `WrappedRecap` data class from the four existing Room entity lists (`RideEntity`, `RideSampleEntity`, `FuelFillEntity`, `ServiceLogEntity`) filtered to a `[fromMillis, toMillis)` window. It reuses the *math* already in `RideAnalytics` / `MileageAnalytics` / `RideStreak` but adds window-scoping and the new aggregates those objects don't expose (busiest month/weekday by name, the *ride* that holds the top speed / longest distance, best/worst tank, total spend). All of it is deterministic JVM, fully unit-tested. The UI is a `HorizontalPager` modelled on the existing `PostRideSummary` (same dialog/pager/dots pattern), each page wrapped so a Share tap captures it to a bitmap. A new tiny `BitmapShare` helper writes an `ImageBitmap` to a cache PNG and fires the `ACTION_SEND` chooser (this does not exist yet — `PostRideSummary` hand-rolls raw `Bitmap`/`Canvas`, and `SceneCapture.capture()` returns an `ImageBitmap` with no writer).

**Tech Stack:** Kotlin, Jetpack Compose, Room (read-only over existing DAOs — **no schema change**), JUnit4 (`org.junit`), Gradle. Design system: `BentoTile`, `GixxerTokens`, `GixxerBrand`, `GixxerMono`, `HeroNumeral`. Capture primitive: `ui/components/ShareScene.kt` (`rememberSceneCapture` / `SceneCapture`).

**Research entry:** `docs/superpowers/research/2026-06-06-stats-and-features-research.md` — Top Picks #4 (value 4 / effort 3).

**No Room migration:** `GixxerDatabase` uses `fallbackToDestructiveMigration` (a schema bump wipes ride/fuel/service history). This feature is **100% recompute-on-read** over existing columns — it adds NO entity, NO column, NO DAO query that changes the schema. Window selection (default range) is transient UI state; if we later persist a "last window" it goes in DataStore (`Settings`), never Room.

---

## Open questions — UI placement / UX decisions for the human

These are NOT blockers for the pure-analytics tasks (1–3), but Task 5/6 need a decision. List them to Arjun before building the UI:

1. **Entry point.** Where does "Wrapped" launch from? Options:
   - (a) A new button at the bottom of `StatsScreen` (next to the existing "Add fuel fill / view true mileage" `OutlinedButton`) — lowest-friction, Stats is already the analytics home.
   - (b) A new bento tile on the Stats dashboard grid ("YOUR YEAR ›") that opens it.
   - (c) A toolbar/overflow action.
   - **Recommendation:** (b) a dashboard tile, because Wrapped is a hero artifact, not a utility button. Needs Arjun's call.
2. **Full screen vs in-tab.** `PostRideSummary` is a full-screen `Dialog`. Should Wrapped be the same full-screen `Dialog` overlay, or a pushed nav route (`composable("wrapped")` in `MainActivity`)? A nav route is cleaner for back-stack + deep-link and lets the window picker live on its own screen; a Dialog matches the existing Wrapped-style precedent. **Recommendation:** nav route `composable("wrapped")`, opened from Stats.
3. **Window picker UX.** How does the rider choose the window? Options: preset chips ("This year", "Last 12 months", "All time", "This month") vs a full date-range picker. **Recommendation:** ship presets first (This year / Last 12 mo / All time), defaulting to "This year"; a custom date-range picker is a deferred follow-up. Confirm the preset set.
4. **Which card is the share card?** Share the *currently-visible* pager page, or always render one composite "summary" card for sharing? **Recommendation:** share the currently-visible page (each page is already self-contained and on-brand), with a dedicated final "summary" page that's the nicest to share. Confirm.
5. **Empty / thin-data window.** If the chosen window has 0 rides (or < 2 fills so best/worst tank and litres are unavailable), show a friendly empty state and gray out the share button — confirm copy. (Honesty rule: never fabricate a tank/litres/spend figure when the data isn't there.)

---

## Feasibility caveats & honesty notes (no-assumptions rule)

- **Litres burnt is an ESTIMATE, not a measurement.** The bike exposes only a 6-bar gauge and a rider-resettable trip-average km/L (`fuelEconKmlV2`) that ticks during engine-off idle — it does NOT expose actual litres consumed. `WrappedAnalytics` computes litres burnt as `windowKm / kmPerL`, where `kmPerL` is the fill-measured average (`MileageAnalytics.averageKmPerL`) when ≥ 2 fills exist, else the bike's own economy, else a flagged fallback. **The card MUST label this "~X L (est.)"**, exactly as `PostRideSummary`/`StatsInsights` already do. Carry a `litresBurntSource`/`isEstimate` flag through `WrappedRecap` so the UI can't accidentally present it as fact.
- **Total spend is PARTIAL when prices are missing.** `FuelFillEntity.rupees` and `ServiceLogEntity.rupees` are nullable. Total spend must sum only non-null prices AND expose how many records were excluded, so the UI can disclose "spend covers N of M fills" rather than silently undercount.
- **Best/worst tank needs ≥ 2 fills IN OR SPANNING the window.** Per-tank km/L is computed from consecutive fill *pairs*; a window with < 2 fills yields no tank figure. Decide (open question 5) whether a tank pair that *straddles* the window boundary counts. **Recommendation, to encode in tests:** attribute a tank to the window by the *later* fill's `tMillis` (the leg "ends" at that fill), and include the immediately-preceding fill even if it's just before the window start, so a window's first tank isn't silently dropped. This is a modelling choice — write it as an explicit, tested rule, not an assumption.
- **"Saddle hours" is wall-clock per ride** (`endedAtMillis - startedAtMillis`), same as `RideAnalytics.totalsFor`. It includes traffic-light idle within a ride (the bike has no separate engine-on clock here). Not a defect — just label it "in the saddle", matching the existing Stats hero.
- **Busiest month/weekday is by START day in the local zone**, consistent with `RideAnalytics.weekdayKm`/`monthlyKm` (a ride crossing midnight counts on its start day). Tests pin a fixed `ZoneId` to stay deterministic.
- **Streak within a window**: `RideStreak.compute` runs on the full list and counts "current" relative to today. For Wrapped's "longest streak in this window" we need the *longest* run restricted to days inside the window — add a window-scoped longest-streak to `WrappedAnalytics` (don't reuse the today-relative `current`).
- **Top-speed accuracy is genuine** — `RideEntity.maxSpeedKmh` is from the a537 ECU speed (per the research report), not the stale a533 SharedPrefs value. Top speed + its ride date/name is solid ground truth.
- **On-bike-only verification:** essentially none. Every input is already-persisted Room history; the recap is pure math over it. The only on-device (not on-*bike*) checks are: (1) the pager renders/swipes, (2) `SceneCapture.capture()` produces a correct PNG and the share chooser fires — both verifiable in the emulator/phone with seeded DB data, no motorcycle required. `canVerifyLocally` is therefore **partly true**: all pure analytics are JVM-unit-testable with zero hardware; the share/render path needs a device or emulator but not the bike.

---

### Task 1: `WrappedAnalytics` — window-scoped recap math (pure) + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedAnalytics.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedAnalyticsTest.kt`

This is the heart of the feature and is 100% pure JVM (no Android, no Room runtime — only the plain entity data classes, same as `RideAnalyticsTest`).

- [ ] **Step 1: Write the failing test**

Create `WrappedAnalyticsTest.kt`. Mirror the construction style of `RideStreakTest` (fixed UTC zone, fixed `today`, small `ride(...)`/`fill(...)`/`service(...)` builders). Cover:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.data.ServiceLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Pure-JVM tests for [WrappedAnalytics.recap]. Fixed UTC zone for determinism. */
class WrappedAnalyticsTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val day = 86_400_000L
    // 2025-01-01T00:00Z .. used as window anchor.
    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun ride(
        id: Long, start: LocalDate, durMin: Long, km: Int, maxSpeed: Int, name: String? = null,
    ) = RideEntity(
        id = id,
        startedAtMillis = ms(start),
        endedAtMillis = ms(start) + durMin * 60_000L,
        startOdoKm = 1000,
        endOdoKm = 1000 + km,
        maxSpeedKmh = maxSpeed,
        avgSpeedKmh = 0.0,
        sampleCount = 0,
        fuelBarsStart = null,
        fuelBarsEnd = null,
        name = name,
    )

    private fun fill(id: Long, date: LocalDate, odo: Int, litres: Double, rupees: Double?) =
        FuelFillEntity(id = id, tMillis = ms(date), odometerKm = odo, litres = litres, rupees = rupees, note = null)

    private fun service(id: Long, date: LocalDate, rupees: Double?) =
        ServiceLogEntity(id = id, tMillis = ms(date), odometerKm = 5000, type = "Oil change", rupees = rupees, notes = null)

    // ---- empty window ----
    @Test fun emptyWindowYieldsZeroTotalsAndNullBests() {
        val r = WrappedAnalytics.recap(
            rides = emptyList(), samples = emptyList(), fills = emptyList(), services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals(0, r.totalKm)
        assertEquals(0, r.rideCount)
        assertEquals(0.0, r.saddleHours, 0.0001)
        assertNull(r.longestRide)
        assertNull(r.topSpeed)
        assertNull(r.bestTank)
        assertNull(r.worstTank)
        assertNull(r.litresBurnt)
        assertEquals(0.0, r.totalSpendRupees, 0.0001)
    }

    // ---- totals + window filtering (rides outside the window excluded) ----
    @Test fun totalsCountOnlyRidesInsideWindow() {
        val rides = listOf(
            ride(1, LocalDate.of(2024, 12, 31), durMin = 30, km = 10, maxSpeed = 60), // before window
            ride(2, LocalDate.of(2025, 3, 1), durMin = 60, km = 40, maxSpeed = 90),
            ride(3, LocalDate.of(2025, 6, 1), durMin = 120, km = 80, maxSpeed = 110),
            ride(4, LocalDate.of(2026, 2, 1), durMin = 30, km = 10, maxSpeed = 70), // after window
        )
        val r = WrappedAnalytics.recap(
            rides = rides, samples = emptyList(), fills = emptyList(), services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals(120, r.totalKm)       // 40 + 80
        assertEquals(2, r.rideCount)
        assertEquals(3.0, r.saddleHours, 0.0001) // 60 + 120 min
    }

    // ---- longest ride + top speed carry the ride id / date / name ----
    @Test fun longestRideAndTopSpeedIdentifyTheRide() {
        val rides = listOf(
            ride(2, LocalDate.of(2025, 3, 1), durMin = 60, km = 40, maxSpeed = 95, name = "City loop"),
            ride(3, LocalDate.of(2025, 6, 1), durMin = 120, km = 80, maxSpeed = 110, name = "Coast run"),
        )
        val r = WrappedAnalytics.recap(
            rides = rides, samples = emptyList(), fills = emptyList(), services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals(80, r.longestRide!!.km)
        assertEquals(3L, r.longestRide!!.rideId)
        assertEquals("Coast run", r.longestRide!!.name)
        assertEquals(110, r.topSpeed!!.kmh)
        assertEquals(3L, r.topSpeed!!.rideId)
    }

    // ---- busiest month / weekday by NAME ----
    @Test fun busiestMonthAndWeekday() {
        // Two rides in June, one in March. June 1 2025 is a Sunday.
        val rides = listOf(
            ride(1, LocalDate.of(2025, 3, 3), durMin = 30, km = 10, maxSpeed = 60), // Monday
            ride(2, LocalDate.of(2025, 6, 1), durMin = 30, km = 50, maxSpeed = 60), // Sunday
            ride(3, LocalDate.of(2025, 6, 8), durMin = 30, km = 50, maxSpeed = 60), // Sunday
        )
        val r = WrappedAnalytics.recap(
            rides = rides, samples = emptyList(), fills = emptyList(), services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals("June", r.busiestMonth!!.label)   // by km: June 100 vs March 10
        assertEquals(100, r.busiestMonth!!.km)
        assertEquals("Sunday", r.busiestWeekday!!.label)
        assertEquals(100, r.busiestWeekday!!.km)
    }

    // ---- best / worst tank (km/L) from consecutive fill pairs in window ----
    @Test fun bestAndWorstTank() {
        // 3 fills => 2 tanks. Tank A: 1000->1500 on 10L = 50 km/L. Tank B: 1500->1900 on 10L = 40 km/L.
        val fills = listOf(
            fill(1, LocalDate.of(2025, 2, 1), 1000, 10.0, 1000.0),
            fill(2, LocalDate.of(2025, 3, 1), 1500, 10.0, 1000.0),
            fill(3, LocalDate.of(2025, 4, 1), 1900, 10.0, 1000.0),
        )
        val r = WrappedAnalytics.recap(
            rides = emptyList(), samples = emptyList(), fills = fills, services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals(50.0, r.bestTank!!.kmPerL, 0.001)
        assertEquals(40.0, r.worstTank!!.kmPerL, 0.001)
    }

    @Test fun fewerThanTwoFillsHasNoTank() {
        val fills = listOf(fill(1, LocalDate.of(2025, 2, 1), 1000, 10.0, 1000.0))
        val r = WrappedAnalytics.recap(
            rides = emptyList(), samples = emptyList(), fills = fills, services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertNull(r.bestTank)
        assertNull(r.worstTank)
    }

    // ---- litres burnt is an estimate; flagged + sourced ----
    @Test fun litresBurntUsesFillAvgAndIsFlaggedEstimate() {
        // Two fills => avg 50 km/L (1000->1500 on 10L). 200 km in window => 4 L.
        val fills = listOf(
            fill(1, LocalDate.of(2025, 2, 1), 1000, 10.0, null),
            fill(2, LocalDate.of(2025, 3, 1), 1500, 10.0, null),
        )
        val rides = listOf(ride(1, LocalDate.of(2025, 5, 1), durMin = 60, km = 200, maxSpeed = 90))
        val r = WrappedAnalytics.recap(
            rides = rides, samples = emptyList(), fills = fills, services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals(4.0, r.litresBurnt!!, 0.001)
        assertEquals(FuelBurnSource.FILLS, r.litresBurntSource)
        assertTrue(r.isLitresEstimate)
    }

    // ---- total spend: nullable rupees excluded; coverage reported ----
    @Test fun totalSpendSumsNonNullFuelAndServiceAndReportsCoverage() {
        val fills = listOf(
            fill(1, LocalDate.of(2025, 2, 1), 1000, 10.0, 1000.0),
            fill(2, LocalDate.of(2025, 3, 1), 1500, 10.0, null), // price missing
        )
        val services = listOf(
            service(10, LocalDate.of(2025, 4, 1), 2500.0),
            service(11, LocalDate.of(2025, 5, 1), null), // price missing
        )
        val r = WrappedAnalytics.recap(
            rides = emptyList(), samples = emptyList(), fills = fills, services = services,
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals(3500.0, r.totalSpendRupees, 0.001) // 1000 + 2500
        assertEquals(2, r.spendRecordsWithPrice)        // 1 fill + 1 service
        assertEquals(4, r.spendRecordsTotal)            // 2 fills + 2 services
    }

    // ---- longest streak restricted to days inside the window ----
    @Test fun longestStreakWithinWindow() {
        val rides = listOf(
            ride(1, LocalDate.of(2025, 6, 1), 30, 10, 60),
            ride(2, LocalDate.of(2025, 6, 2), 30, 10, 60),
            ride(3, LocalDate.of(2025, 6, 3), 30, 10, 60), // 3-day run
            ride(4, LocalDate.of(2025, 8, 1), 30, 10, 60), // isolated
        )
        val r = WrappedAnalytics.recap(
            rides = rides, samples = emptyList(), fills = emptyList(), services = emptyList(),
            fromMillis = ms(LocalDate.of(2025, 1, 1)), toMillis = ms(LocalDate.of(2026, 1, 1)), zone = zone,
        )
        assertEquals(3, r.longestStreakDays)
    }
}
```

(Cover the half-open boundary explicitly too: a ride at exactly `toMillis` is excluded; a ride at exactly `fromMillis` is included — add one assertion for each.)

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.WrappedAnalyticsTest"`
Expected: FAIL — compilation error, `WrappedAnalytics` / `WrappedRecap` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `WrappedAnalytics.kt`. Keep it pure (only `java.time`, the entity classes, and the existing `MileageAnalytics` / `FuelBurnSource`). Reuse `RideAnalytics`'s odo-delta idiom `max(0, (end ?: start) - start)`.

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.data.ServiceLogEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max

/** A single ride distinguished as a "best" (longest / top-speed holder). */
data class WrappedRideMark(val rideId: Long, val date: Long, val name: String?, val km: Int, val kmh: Int)

/** A named bucket (month or weekday) with its km total. */
data class WrappedBucket(val label: String, val km: Int)

/** A best/worst tank: which fill closed it + its km/L. */
data class WrappedTank(val fillId: Long, val date: Long, val kmPerL: Double)

/** The full any-window recap. All "best" fields are null when the window has no data for them. */
data class WrappedRecap(
    val fromMillis: Long,
    val toMillis: Long,
    val totalKm: Int,
    val saddleHours: Double,
    val rideCount: Int,
    val longestRide: WrappedRideMark?,
    val topSpeed: WrappedRideMark?,
    val busiestMonth: WrappedBucket?,
    val busiestWeekday: WrappedBucket?,
    val bestTank: WrappedTank?,
    val worstTank: WrappedTank?,
    val litresBurnt: Double?,
    val litresBurntSource: FuelBurnSource?,
    val isLitresEstimate: Boolean,
    val totalSpendRupees: Double,
    val spendRecordsWithPrice: Int,
    val spendRecordsTotal: Int,
    val longestStreakDays: Int,
)

/**
 * Pure, deterministic any-window recap math. No Android imports; tested in
 * WrappedAnalyticsTest with hand-built entities.
 *
 * Window is half-open: [fromMillis, toMillis). A record belongs to the window by
 * its start/event millis. Buckets (month/weekday) use the local-zone start day,
 * consistent with [RideAnalytics.weekdayKm]/[RideAnalytics.monthlyKm].
 *
 * Litres burnt is an ESTIMATE: windowKm / kmPerL where kmPerL prefers the
 * fill-measured average ([MileageAnalytics.averageKmPerL]) over the bike's own
 * per-sample economy. [isLitresEstimate] is always true when a figure is present
 * — there is no measured-litres source on this bike.
 */
object WrappedAnalytics {

    private fun rideKm(r: RideEntity) = max(0, (r.endOdoKm ?: r.startOdoKm) - r.startOdoKm)

    fun recap(
        rides: List<RideEntity>,
        samples: List<RideSampleEntity>,
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        fromMillis: Long,
        toMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        fallbackKmPerL: Double = 45.0, // ASSUMED Gixxer SF 150; only used when no fills + no bike econ. Flagged.
    ): WrappedRecap {
        fun inWindow(t: Long) = t >= fromMillis && t < toMillis
        val wRides = rides.filter { inWindow(it.startedAtMillis) }
        val wFillsForSpend = fills.filter { inWindow(it.tMillis) }
        val wServices = services.filter { inWindow(it.tMillis) }

        val totalKm = wRides.sumOf { rideKm(it) }
        val saddleHours = wRides.sumOf {
            max(0L, (it.endedAtMillis ?: it.startedAtMillis) - it.startedAtMillis) / 3_600_000.0
        }

        val longest = wRides.maxByOrNull { rideKm(it) }?.let {
            WrappedRideMark(it.id, it.startedAtMillis, it.name, rideKm(it), it.maxSpeedKmh)
        }
        val top = wRides.maxByOrNull { it.maxSpeedKmh }?.let {
            WrappedRideMark(it.id, it.startedAtMillis, it.name, rideKm(it), it.maxSpeedKmh)
        }

        // Busiest month (by km), labelled with the month name of the winning bucket.
        val byMonth = LinkedHashMap<java.time.YearMonth, Int>()
        val byWeekday = IntArray(7)
        for (r in wRides) {
            val d = Instant.ofEpochMilli(r.startedAtMillis).atZone(zone).toLocalDate()
            byMonth[java.time.YearMonth.from(d)] = (byMonth[java.time.YearMonth.from(d)] ?: 0) + rideKm(r)
            byWeekday[d.dayOfWeek.value - 1] += rideKm(r)
        }
        val busiestMonth = byMonth.entries.maxByOrNull { it.value }?.let {
            WrappedBucket(it.key.month.getDisplayName(TextStyle.FULL, Locale.US), it.value)
        }
        val busiestWeekday = (0..6).maxByOrNull { byWeekday[it] }
            ?.takeIf { byWeekday[it] > 0 }
            ?.let {
                val name = java.time.DayOfWeek.of(it + 1).getDisplayName(TextStyle.FULL, Locale.US)
                WrappedBucket(name, byWeekday[it])
            }

        // Tanks: pair consecutive fills (chronological), attribute a tank to the
        // window by the LATER fill's tMillis. Include the immediately-preceding
        // fill even if it's just before fromMillis so the window's first tank isn't
        // dropped (see plan caveat).
        val ascFills = fills.sortedBy { it.tMillis }
        val tanks = mutableListOf<WrappedTank>()
        for (i in 1 until ascFills.size) {
            val prev = ascFills[i - 1]; val cur = ascFills[i]
            if (!inWindow(cur.tMillis)) continue
            val km = cur.odometerKm - prev.odometerKm
            if (km > 0 && cur.litres > 0.0) tanks += WrappedTank(cur.id, cur.tMillis, km / cur.litres)
        }
        val bestTank = tanks.maxByOrNull { it.kmPerL }
        val worstTank = tanks.minByOrNull { it.kmPerL }

        // Litres burnt (ESTIMATE). Prefer fill-measured avg over bike per-sample econ.
        // Use the same window-spanning fill set as tanks for the avg.
        val fillAvg = MileageAnalytics.averageKmPerL(fills.filter { it.tMillis < toMillis })
        val bikeAvg = RideAnalytics.avgBikeEcon(
            samples.filter { s -> wRides.any { it.id == s.rideId } }
        )
        val burn = RideAnalytics.fuelBurnt(distanceKm = totalKm, fillKmPerL = fillAvg, bikeKmPerL = bikeAvg)
        val litresBurnt: Double?
        val litresSource: FuelBurnSource?
        if (burn != null) {
            litresBurnt = burn.litres; litresSource = burn.source
        } else if (totalKm > 0) {
            litresBurnt = totalKm / fallbackKmPerL; litresSource = null // null source => pure fallback
        } else {
            litresBurnt = null; litresSource = null
        }

        // Spend: non-null rupees only; report coverage.
        val fuelPrices = wFillsForSpend.mapNotNull { it.rupees }
        val servicePrices = wServices.mapNotNull { it.rupees }
        val totalSpend = fuelPrices.sum() + servicePrices.sum()
        val withPrice = fuelPrices.size + servicePrices.size
        val totalRecords = wFillsForSpend.size + wServices.size

        // Longest streak among ride-days inside the window.
        val daysInWindow = wRides
            .map { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() }
            .toSortedSet()
        var longestStreak = 0; var run = 0; var prev: java.time.LocalDate? = null
        for (d in daysInWindow) {
            run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
            longestStreak = maxOf(longestStreak, run); prev = d
        }

        return WrappedRecap(
            fromMillis = fromMillis,
            toMillis = toMillis,
            totalKm = totalKm,
            saddleHours = saddleHours,
            rideCount = wRides.size,
            longestRide = longest,
            topSpeed = top,
            busiestMonth = busiestMonth,
            busiestWeekday = busiestWeekday,
            bestTank = bestTank,
            worstTank = worstTank,
            litresBurnt = litresBurnt,
            litresBurntSource = litresSource,
            isLitresEstimate = litresBurnt != null,
            totalSpendRupees = totalSpend,
            spendRecordsWithPrice = withPrice,
            spendRecordsTotal = totalRecords,
            longestStreakDays = longestStreak,
        )
    }
}
```

Note: `longestRide`/`topSpeed` are non-null when `wRides` is non-empty even with 0 km — that's intended (a ride exists). The "empty window" test passes only when `wRides` is empty. If Arjun wants "no distance ⇒ no longest ride", adjust to `takeIf { it.km > 0 }` and add a test first.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.WrappedAnalyticsTest"`
Expected: PASS (all tests, including the two boundary assertions).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedAnalytics.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedAnalyticsTest.kt
git commit -m "feat(wrapped): pure window-scoped WrappedAnalytics recap + tests"
```

---

### Task 2: Window presets helper (pure) + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedWindow.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedWindowTest.kt`

Keeps the date-window math (preset → `[fromMillis, toMillis)`) pure and testable, out of the UI. Depends on open question 3 (preset set) — implement the recommended presets; add/remove is trivial once confirmed.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.mrwick.gixxerbridge.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class WrappedWindowTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private val now = LocalDate.of(2025, 6, 15).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun thisYearStartsAtJan1() {
        val w = WrappedWindow.range(WrappedWindow.Preset.THIS_YEAR, now = now, zone = zone)
        val from = Instant.ofEpochMilli(w.first).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2025, 1, 1), from)
        assertTrue(w.second > now) // window end is in the future / end-of-day-now
    }

    @Test fun last12MonthsSpansAYear() {
        val w = WrappedWindow.range(WrappedWindow.Preset.LAST_12_MONTHS, now = now, zone = zone)
        val from = Instant.ofEpochMilli(w.first).atZone(zone).toLocalDate()
        assertEquals(LocalDate.of(2024, 6, 15), from)
    }

    @Test fun allTimeStartsAtEpoch() {
        val w = WrappedWindow.range(WrappedWindow.Preset.ALL_TIME, now = now, zone = zone)
        assertEquals(0L, w.first)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.WrappedWindowTest"`
Expected: FAIL — `WrappedWindow` unresolved.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package dev.mrwick.gixxerbridge.analytics

import java.time.Instant
import java.time.ZoneId

/** Preset time windows for Gixxer Wrapped, resolved to [fromMillis, toMillis). */
object WrappedWindow {
    enum class Preset(val label: String) {
        THIS_YEAR("This year"),
        LAST_12_MONTHS("Last 12 months"),
        THIS_MONTH("This month"),
        ALL_TIME("All time"),
    }

    /** Resolve [preset] to a half-open [from, to) millis pair. `to` is end-of-today. */
    fun range(
        preset: Preset,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<Long, Long> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val endExclusive = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val from = when (preset) {
            Preset.THIS_YEAR -> today.withDayOfYear(1)
            Preset.LAST_12_MONTHS -> today.minusYears(1)
            Preset.THIS_MONTH -> today.withDayOfMonth(1)
            Preset.ALL_TIME -> return 0L to endExclusive
        }.atStartOfDay(zone).toInstant().toEpochMilli()
        return from to endExclusive
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.WrappedWindowTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedWindow.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/WrappedWindowTest.kt
git commit -m "feat(wrapped): pure WrappedWindow preset -> millis-range helper + tests"
```

---

### Task 3: `BitmapShare` — write an `ImageBitmap` to PNG + share intent

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/export/BitmapShare.kt`

No unit test (pure Android I/O + Intent — verified on-device in Task 6). This is the missing glue between `SceneCapture.capture()` (returns `ImageBitmap`) and a share chooser. `PostRideSummary` hand-rolls its own raw-`Bitmap` share; this generalises the capture path so Wrapped pages can be shared without re-implementing Canvas drawing.

- [ ] **Step 1: Write the helper**

```kotlin
package dev.mrwick.gixxerbridge.export

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Writes a captured [ImageBitmap] (from [SceneCapture.capture]) to a cache PNG and
 * fires an ACTION_SEND chooser. Reuses the app's `${packageName}.fileprovider`
 * authority (already declared for [PostRideSummary]'s ride share).
 *
 * Call on a background dispatcher — bitmap compression + file I/O are blocking.
 */
object BitmapShare {
    fun shareImageBitmap(context: Context, image: ImageBitmap, fileName: String, chooserTitle: String) {
        val bmp = image.asAndroidBitmap()
        val dir = File(context.cacheDir, "wrapped").also { it.mkdirs() }
        val png = File(dir, fileName)
        png.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 95, out) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", png)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }
}
```

Confirm the FileProvider authority `${packageName}.fileprovider` and the cache-dir `<cache-path>`/`files-path` mapping already exist in the manifest + `file_paths.xml` (they do — `PostRideSummary` shares from `filesDir/rides`; verify a `cache-path` entry covers `cacheDir/wrapped`, and ADD one if missing). **If the path is not mapped, FileProvider throws `IllegalArgumentException` at share time** — this is the one thing to check during Step 2.

- [ ] **Step 2: Verify it compiles + check FileProvider paths**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Then read `app/src/main/res/xml/file_paths.xml` (or whatever the manifest `<meta-data android:name="android.support.FILE_PROVIDER_PATHS">` points at) and confirm a `<cache-path>` covering the `wrapped/` subdir. Add `<cache-path name="wrapped" path="wrapped/" />` if absent.
Expected: BUILD SUCCESSFUL; path entry present.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/export/BitmapShare.kt
# include file_paths.xml only if you edited it
git commit -m "feat(export): BitmapShare — captured ImageBitmap -> PNG share intent"
```

---

### Task 4: `WrappedViewModel` — load entities, expose `recap` for a window

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/wrapped/WrappedViewModel.kt`

No unit test (thin VM glue; the math is tested in Task 1/2). Verified by compile + the screen build in Task 5/6.

- [ ] **Step 1: Write the ViewModel**

Model it on `StatsViewModel` (AndroidViewModel, `GixxerDatabase.get(app)` stores, `stateIn`). Hold the selected `WrappedWindow.Preset` as `MutableStateFlow`, default `THIS_YEAR` (open question 3). Combine the four entity sources + the selected preset → `WrappedRecap?`. For samples, reuse the lazy-load pattern: only load samples for rides inside the window (the litres-burnt bike-econ fallback needs them) — or, since fill-avg is the primary source, load samples lazily / skip when fills suffice. Keep it simple first: load all samples via `store.getSamples` per in-window ride inside the `map` (bounded — single-rider history), matching `StatsViewModel.recentSamples`.

```kotlin
package dev.mrwick.gixxerbridge.ui.wrapped

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.analytics.WrappedAnalytics
import dev.mrwick.gixxerbridge.analytics.WrappedRecap
import dev.mrwick.gixxerbridge.analytics.WrappedWindow
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.data.ServiceLogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class WrappedViewModel(app: Application) : AndroidViewModel(app) {
    private val db = GixxerDatabase.get(app)
    private val rideStore = RideStore(db.rideDao())
    private val fuelStore = FuelStore(db.fuelFillDao())
    private val serviceStore = ServiceLogStore(db.serviceLogDao())

    val preset = MutableStateFlow(WrappedWindow.Preset.THIS_YEAR)

    val recap: StateFlow<WrappedRecap?> = combine(
        rideStore.observeRides(), fuelStore.observe(), serviceStore.observe(), preset,
    ) { rides, fills, services, p ->
        val (from, to) = WrappedWindow.range(p)
        val inWindow = rides.filter { it.startedAtMillis in from until to }
        val samples = inWindow.flatMap { rideStore.getSamples(it.id) }
        WrappedAnalytics.recap(
            rides = rides, samples = samples, fills = fills, services = services,
            fromMillis = from, toMillis = to,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setPreset(p: WrappedWindow.Preset) { preset.value = p }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/wrapped/WrappedViewModel.kt
git commit -m "feat(wrapped): WrappedViewModel — window preset -> recap StateFlow"
```

---

### Task 5: `WrappedScreen` — swipeable recap pager + share

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/wrapped/WrappedScreen.kt`

UI — verified by compile + on-device (Task 6). Model the pager/dots/close on `PostRideSummary`. Use `BentoTile`, `HeroNumeral`, `GixxerMono`, `GixxerBrand`/`GixxerTokens` for on-brand cards. Wrap each page (or a single shared scene) with `rememberSceneCapture().modifier`; the Share button calls `scene.capture()` then `BitmapShare.shareImageBitmap(...)` on a background coroutine.

- [ ] **Step 1: Build the screen scaffold**

Sketch (concise — fill in card composables to match the existing `StatCell`/`InsightCell` look):

```kotlin
@Composable
fun WrappedScreen(vm: WrappedViewModel, onClose: () -> Unit) {
    val recap by vm.recap.collectAsStateWithLifecycle()
    val preset by vm.preset.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scene = rememberSceneCapture()

    // Window preset chips (open question 3 -> presets first).
    // Empty/thin window guard (open question 5): if recap == null || recap.rideCount == 0,
    // show an EmptyState + disabled Share.
    // Otherwise a HorizontalPager of cards:
    //   0. Title/summary: window label + total km hero + ride count + saddle hours
    //   1. Longest ride (km + date + name)
    //   2. Top speed (km/h + date + name)  [genuine ECU value]
    //   3. Busiest month + busiest weekday
    //   4. Fuel: litres burnt (~est.) + best/worst tank km/L
    //   5. Money: total spend + "covers N of M records" disclosure
    //   6. Streak: longest streak in window
    // Dots row + close button + Share button (captures current scene).
}
```

Honesty requirements baked into the cards (do NOT skip):
- Litres card shows `~${"%.1f".format(litresBurnt)} L (est.)`; if `litresBurntSource == null` (pure fallback), append "· rough" or similar.
- Best/worst tank only render when `bestTank != null` (≥ 2 fills); otherwise show "Log 2+ fills to see your best tank".
- Spend card shows `₹${total}` plus, when `spendRecordsWithPrice < spendRecordsTotal`, a sub-line "covers $withPrice of $total records".

- [ ] **Step 2: Wire Share via capture**

```kotlin
Button(onClick = {
    scope.launch {
        val img = scene.capture()
        withContext(Dispatchers.IO) {
            BitmapShare.shareImageBitmap(
                context, img, fileName = "wrapped-${preset.name.lowercase()}.png",
                chooserTitle = "Share Gixxer Wrapped",
            )
        }
    }
}) { Text("Share") }
```

Ensure the captured scene includes a brand watermark (e.g. "• REDLINE" footer like `ShareCardRenderer`) so a shared screenshot is self-identifying.

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/wrapped/WrappedScreen.kt
git commit -m "feat(wrapped): swipeable recap pager with capture-to-PNG share"
```

---

### Task 6: Wire entry point + nav route, build, install, verify on device

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/MainActivity.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/stats/StatsScreen.kt`

Depends on open questions 1 + 2 (entry point + route-vs-dialog). The steps below assume the **recommended** answers: a nav route `composable("wrapped")` opened from a Stats dashboard tile. Adjust if Arjun decides otherwise.

- [ ] **Step 1: Add the nav route**

In `MainActivity.kt`, add a `composable("wrapped") { ... }` block (next to `composable("mileage")`), constructing `WrappedViewModel(app)` via the same anonymous `ViewModelProvider.Factory` pattern used for `StatsViewModel`/`MileageViewModel`, and pass `onClose = { nav.popBackStack() }`.

- [ ] **Step 2: Add the entry point on Stats**

Thread an `onOpenWrapped: () -> Unit = {}` param into `StatsScreen` (signature already takes `onOpenSettings`/`onOpenMileage`), wire it in `MainActivity`'s `StatsScreen(...)` call to `nav.navigate("wrapped")`, and add a `BentoTile` ("YOUR YEAR ›" / "GIXXER WRAPPED") to the Stats dashboard grid with `onClick = onOpenWrapped`. (If Arjun picks option (a), add an `OutlinedButton` next to the existing mileage button instead.)

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build, install, verify on device**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Then, with some ride/fuel/service history present:
  - Open Stats → tap the Wrapped entry → screen opens defaulting to "This year".
  - Swipe through all cards; numbers match the Stats screen for the same window (total km, top speed).
  - Switch the window preset → recap recomputes.
  - Tap Share → a chooser appears → pick (e.g.) "Save to Files" or a messaging app → a PNG of the current card is shared (verify the image is not blank and shows the brand watermark).
  - Choose an empty window (e.g. "This month" with no rides) → empty state shows, Share disabled.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/MainActivity.kt android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/stats/StatsScreen.kt
git commit -m "feat(wrapped): Stats entry point + nav route for Gixxer Wrapped"
```

---

## Self-Review

**Spec / research coverage (Top Pick #4):**
- total km, saddle hours, ride count → `WrappedRecap.totalKm/saddleHours/rideCount` (Task 1) ✓
- longest ride, top speed with date + name → `WrappedRideMark` (Task 1) ✓
- busiest month / weekday → `WrappedBucket` by name (Task 1) ✓
- best / worst tank → `WrappedTank` (Task 1) ✓
- litres burnt (est.) → `litresBurnt` + `isLitresEstimate` + source (Task 1) ✓
- total spend → `totalSpendRupees` + coverage counters (Task 1) ✓
- longest streak (window-scoped) → `longestStreakDays` (Task 1) ✓
- any time window → `WrappedWindow` presets + `[from,to)` (Task 2) ✓
- swipeable + shareable → `WrappedScreen` pager + `SceneCapture` + `BitmapShare` (Tasks 3, 5) ✓

**No-assumptions adherence:**
- Litres burnt + fallback km/L are explicitly flagged estimates (never presented as measured). ✓
- Spend discloses partial coverage; never silently undercounts. ✓
- Tank-window attribution rule is written down and unit-tested, not assumed. ✓
- Top speed sourced from ECU value (per research), labelled accurately. ✓

**No Room migration:** purely recompute-on-read over existing entities; no entity/column/schema change → `fallbackToDestructiveMigration` never triggers. ✓

**Testing:** all pure analytics (`WrappedAnalytics`, `WrappedWindow`) are JVM-unit-tested with JUnit4 (`org.junit`), failing-test-first. UI / VM / share are build + on-device verified (no Compose unit tests, consistent with the rest of the codebase). ✓

**Reuse:** leans on existing `MileageAnalytics.averageKmPerL`, `RideAnalytics.avgBikeEcon`/`fuelBurnt`/`FuelBurnSource`, `SceneCapture`/`rememberSceneCapture`, `BentoTile`/`HeroNumeral`/design tokens, and the `PostRideSummary` pager/dialog pattern. New code is confined to window-scoping math, window presets, the bitmap-share glue, the VM, and the screen. ✓

**Open questions surfaced (not silently decided):** entry point, dialog-vs-route, window-picker UX, share-current-vs-summary card, empty-window copy — all listed up top for Arjun. ✓

**Placeholder scan:** card-body composables in Task 5 are sketched (signature + content list + honesty rules) rather than fully written, because their exact layout depends on open questions 1–5; the load-bearing logic (Tasks 1–4) is concrete and tested. Flag this to the implementer: finalise card layouts after Arjun answers the open questions.
