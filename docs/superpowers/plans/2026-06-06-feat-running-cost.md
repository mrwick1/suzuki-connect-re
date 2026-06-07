# Running-cost dashboard (₹/km + monthly spend) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface the blended cost of ownership — `(fuel ₹ + service ₹) ÷ km` — plus a fuel-vs-maintenance split and a monthly-spend bar, computed entirely from data the app already stores. No new data capture, no schema change.

**Architecture:** A new pure analytics object, `RunningCostAnalytics`, takes the fuel-fill log (`FuelFillEntity`), the service log (`ServiceLogEntity`), and a km denominator, and produces a `RunningCost` result (blended ₹/km, fuel ₹/km, service ₹/km, the fuel/maintenance split fractions, total spend, and a **coverage figure** because `rupees` is nullable on both entities). A second function, `monthlySpend`, buckets fuel + service spend by calendar month, cloning the exact `RideAnalytics.monthlyKm` bucketing (`RideAnalytics.kt:195-217`). The km denominator is derived from fuel-fill odometer deltas (first→last fill odo), which captures distance even on rides the app never logged — falling back to the ride-history odometer span when there are fewer than 2 fills. All of this is JVM-pure and unit-tested; a thin VM exposes it to a Compose card/section.

**Tech Stack:** Kotlin, Jetpack Compose, Room (`FuelFillEntity` + `ServiceLogEntity`, read-only), JUnit4 (`org.junit`), Gradle. Design system: `GixxerTokens` / `GixxerBrand` / `GixxerMono` / `BentoTile`.

**Research source:** `docs/superpowers/research/2026-06-06-stats-and-features-research.md` — Top Picks #3 ("Running cost dashboard: cost-per-km + monthly spend", value 4 / effort 2).

**Schema migration:** NONE. Both `FuelFillEntity.rupees` and `ServiceLogEntity.rupees` columns already exist (`FuelFill.kt:27`, `ServiceLog.kt:28`). `GixxerDatabase` uses `fallbackToDestructiveMigration()` (`GixxerDatabase.kt:57`) — any new Room column would WIPE all ride/fuel/service history. This feature is deliberately built as **recompute-on-read** over existing columns so it touches no schema.

---

## Open questions for the human (UI placement / UX) — DECIDE BEFORE TASK 4/5

These are genuine product decisions, not implementation details. The pure analytics (Tasks 1–2) are unaffected; only the UI tasks depend on the answers.

1. **Which screen does the running-cost view live on?** Candidates observed in the codebase:
   - **(a) A new drill-in section on the Stats tab** (`StatsScreen.kt`), reached from a new dashboard `BentoTile` (the screen already uses a `StatsDetail` enum + tap-to-drill pattern — `StatsScreen.kt:190-245,352-358`). This is the lowest-friction home for it and matches the existing "bento dashboard → focused detail" model. **Recommended default if no answer.**
   - **(b) A section appended to the Mileage screen** (`MileageScreen.kt`), since cost is conceptually adjacent to mileage and the fuel-fill log already lives there.
   - **(c) A new top-level screen** with its own nav entry. Heaviest; probably overkill for one card-cluster.
2. **New `StatsDetail` entry vs. inline card?** If (a): add a 5th `StatsDetail` value (e.g. `COST("RUNNING COST")`) with its own dashboard tile, OR fold a compact ₹/km figure into the existing FOR-FUN / INSIGHTS detail (which already shows a hand-wavy `~₹cost` from a 45 km/L estimate at `StatsScreen.kt:489-490,520`). A dedicated detail is cleaner and lets us show the split + monthly bar; folding-in risks two competing cost numbers.
3. **Headline metric: ₹/km or ₹/100km?** Research #2 suggests both. ₹/km is small (~₹2-3/km) and may read awkwardly; ₹/100km (~₹250) is more legible. Pick one for the hero, show the other as a sub-label. (Plan implements both in the data model so the UI can choose freely.)
4. **Coverage disclosure wording.** `rupees` is nullable; when only some fills/services have a price, the figure undercounts. How should it read — e.g. "based on 4 of 6 fills priced" or a "% priced" chip? (Plan computes the coverage fraction; the exact copy is a UI call.)

> If the human is unavailable, proceed with **1(a) + 2 (new `COST` detail) + 3 (₹/km hero, ₹/100km sub)** and leave a `// UI-DECISION:` comment so it's easy to revisit. Do NOT block Tasks 1–3 on these.

---

### Task 1: `RunningCostAnalytics.cost()` — blended ₹/km + split + coverage (pure + tests)

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalytics.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalyticsTest.kt`

- [ ] **Step 1: Write the failing test**

Mirror the `MileageAnalyticsTest` style (`fill(...)` helper, fixed `t0`/`day`, `org.junit` asserts). Create `RunningCostAnalyticsTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.ServiceLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [RunningCostAnalytics]. No Room, no Android. */
class RunningCostAnalyticsTest {

    private val day = 86_400_000L
    private val t0 = 1_750_000_000_000L

    private fun fill(id: Long, daysAfter: Long, odo: Int, litres: Double, rupees: Double?) =
        FuelFillEntity(id = id, tMillis = t0 + daysAfter * day, odometerKm = odo, litres = litres, rupees = rupees, note = null)

    private fun svc(id: Long, daysAfter: Long, odo: Int, rupees: Double?, type: String = "Oil change") =
        ServiceLogEntity(id = id, tMillis = t0 + daysAfter * day, odometerKm = odo, type = type, rupees = rupees, notes = null)

    @Test fun blendedCostFromFuelAndService() {
        // Distance denominator = first->last fill odo = 1000 -> 2000 = 1000 km.
        // Fuel ₹ = 400 + 400 = 800. Service ₹ = 200. Total = 1000 over 1000 km => ₹1.0/km.
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0), fill(2, 10, 2000, 10.0, 400.0))
        val svcs = listOf(svc(1, 5, 1500, 200.0))
        val c = RunningCostAnalytics.cost(fills, svcs)!!
        assertEquals(1000, c.distanceKm)
        assertEquals(800.0, c.fuelRupees, 0.001)
        assertEquals(200.0, c.serviceRupees, 0.001)
        assertEquals(1.0, c.rupeesPerKm, 0.001)
        assertEquals(100.0, c.rupeesPer100Km, 0.001)
        assertEquals(0.8, c.fuelFraction, 0.001)
        assertEquals(0.2, c.serviceFraction, 0.001)
    }

    @Test fun nullRupeesExcludedAndDisclosedViaCoverage() {
        // Fill 2 has no price -> excluded from fuel ₹ but still counts for distance.
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0), fill(2, 10, 2000, 10.0, null))
        val c = RunningCostAnalytics.cost(fills, emptyList())!!
        assertEquals(400.0, c.fuelRupees, 0.001)
        assertEquals(1, c.fuelFillsPriced)
        assertEquals(2, c.fuelFillsTotal)
        assertEquals(0.5, c.fuelPricedFraction, 0.001) // 1 of 2 priced
    }

    @Test fun serviceFractionIsOneWhenNoFuelSpend() {
        val svcs = listOf(svc(1, 0, 1000, 500.0))
        // Need a distance denominator; with <2 fills, fall back to ride span (passed in).
        val c = RunningCostAnalytics.cost(emptyList(), svcs, fallbackDistanceKm = 1000)!!
        assertEquals(0.0, c.fuelRupees, 0.001)
        assertEquals(500.0, c.serviceRupees, 0.001)
        assertEquals(1.0, c.serviceFraction, 0.001)
        assertEquals(0.0, c.fuelFraction, 0.001)
        assertEquals(0.5, c.rupeesPerKm, 0.001)
    }

    @Test fun fallbackDistanceUsedWhenFewerThanTwoFills() {
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0))
        val c = RunningCostAnalytics.cost(fills, emptyList(), fallbackDistanceKm = 500)!!
        assertEquals(500, c.distanceKm) // single fill gives no odo delta -> fallback
    }

    @Test fun nullWhenNoDistanceAndNoFallback() {
        val fills = listOf(fill(1, 0, 1000, 10.0, 400.0)) // single fill, no delta
        assertNull(RunningCostAnalytics.cost(fills, emptyList(), fallbackDistanceKm = null))
    }

    @Test fun nullWhenNoSpendAtAll() {
        // Distance exists but every rupee field is null -> nothing to cost.
        val fills = listOf(fill(1, 0, 1000, 10.0, null), fill(2, 5, 1500, 10.0, null))
        assertNull(RunningCostAnalytics.cost(fills, emptyList()))
    }

    @Test fun distanceFromFillOdoDeltaIgnoresInputOrder() {
        val fills = listOf(
            fill(2, 10, 2000, 10.0, 400.0),
            fill(1, 0, 1000, 10.0, 400.0),
        )
        val c = RunningCostAnalytics.cost(fills, emptyList())!!
        assertEquals(1000, c.distanceKm)
    }

    @Test fun negativeOdoDeltaFallsBack() {
        // Backwards odo (data-entry error): max-min still positive, so guard on
        // last-min vs first using sorted order; ensure non-negative.
        val fills = listOf(fill(1, 0, 2000, 10.0, 400.0), fill(2, 5, 1000, 10.0, 400.0))
        val c = RunningCostAnalytics.cost(fills, emptyList(), fallbackDistanceKm = 300)!!
        assertTrue(c.distanceKm >= 0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RunningCostAnalyticsTest"`
Expected: FAIL — compilation error, `RunningCostAnalytics` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `RunningCostAnalytics.kt`. Distance denominator = (max fill odo − min fill odo) when ≥ 2 fills and positive, else `fallbackDistanceKm`. Sum `rupees` excluding nulls; track priced-vs-total counts for coverage. Return null when there is no usable distance OR no spend at all.

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import dev.mrwick.gixxerbridge.data.ServiceLogEntity

/**
 * Blended running cost of ownership over the fuel + service ledgers.
 *
 * [rupeesPerKm] / [rupeesPer100Km] : total spend / distance.
 * [fuelFraction] / [serviceFraction] : split of total spend (sum to 1.0, or 0/0
 *   when total spend is 0 — but [cost] returns null in that case anyway).
 * [fuelPricedFraction] : fraction of fills that carried a price — disclosed so the
 *   UI can warn the figure undercounts (rupees is nullable on both entities).
 *
 * Pure JVM, deterministic — tested in RunningCostAnalyticsTest.
 */
data class RunningCost(
    val distanceKm: Int,
    val fuelRupees: Double,
    val serviceRupees: Double,
    val totalRupees: Double,
    val rupeesPerKm: Double,
    val rupeesPer100Km: Double,
    val fuelFraction: Double,
    val serviceFraction: Double,
    val fuelFillsPriced: Int,
    val fuelFillsTotal: Int,
    val servicesPriced: Int,
    val servicesTotal: Int,
    val fuelPricedFraction: Double,
)

object RunningCostAnalytics {

    /**
     * Compute blended running cost. [fallbackDistanceKm] is used when fewer than
     * two fills exist (no odo delta) — pass the ride-history odometer span there
     * (see [RunningCostViewModel]). Returns null when no distance is available or
     * when no rupee figure was ever logged.
     */
    fun cost(
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        fallbackDistanceKm: Int? = null,
    ): RunningCost? {
        val odos = fills.map { it.odometerKm }
        val fillDelta = if (odos.size >= 2) (odos.max() - odos.min()) else 0
        val distanceKm = (if (fillDelta > 0) fillDelta else fallbackDistanceKm ?: 0)
            .coerceAtLeast(0)
        if (distanceKm <= 0) return null

        val fuelPriced = fills.mapNotNull { it.rupees }.filter { it >= 0.0 }
        val svcPriced = services.mapNotNull { it.rupees }.filter { it >= 0.0 }
        val fuelRupees = fuelPriced.sum()
        val serviceRupees = svcPriced.sum()
        val total = fuelRupees + serviceRupees
        if (total <= 0.0) return null

        return RunningCost(
            distanceKm = distanceKm,
            fuelRupees = fuelRupees,
            serviceRupees = serviceRupees,
            totalRupees = total,
            rupeesPerKm = total / distanceKm,
            rupeesPer100Km = total / distanceKm * 100.0,
            fuelFraction = fuelRupees / total,
            serviceFraction = serviceRupees / total,
            fuelFillsPriced = fuelPriced.size,
            fuelFillsTotal = fills.size,
            servicesPriced = svcPriced.size,
            servicesTotal = services.size,
            fuelPricedFraction = if (fills.isEmpty()) 0.0 else fuelPriced.size.toDouble() / fills.size,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RunningCostAnalyticsTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalytics.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalyticsTest.kt
git commit -m "feat(analytics): RunningCostAnalytics blended cost-per-km + coverage with tests"
```

---

### Task 2: `RunningCostAnalytics.monthlySpend()` — monthly fuel + service ₹ bars (pure + tests)

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalytics.kt`
- Modify: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalyticsTest.kt`

This clones the calendar-month bucketing of `RideAnalytics.monthlyKm` (`RideAnalytics.kt:195-217`) — same `firstMonth`/`LinkedHashMap`/`"%04d-%02d"` key approach — but sums `rupees` instead of km, and keeps fuel and service spend as separate sub-totals per month so the UI can stack them.

- [ ] **Step 1: Add the failing test**

Append to `RunningCostAnalyticsTest.kt`:

```kotlin
    @Test fun monthlySpendBucketsFuelAndServiceByCalendarMonth() {
        // now = t0; build entries in two distinct months relative to a fixed clock.
        val now = t0
        val fills = listOf(
            fill(1, 0, 1000, 10.0, 400.0),    // this month
            fill(2, -40, 500, 10.0, 300.0),   // ~prev month
        )
        val svcs = listOf(svc(1, 0, 1000, 200.0)) // this month
        val months = RunningCostAnalytics.monthlySpend(fills, svcs, months = 3, now = now)
        assertEquals(3, months.size)            // always exactly `months` buckets
        // Buckets are oldest-first and contiguous.
        val last = months.last()
        assertEquals(400.0, last.fuelRupees, 0.001)
        assertEquals(200.0, last.serviceRupees, 0.001)
        assertEquals(600.0, last.totalRupees, 0.001)
    }

    @Test fun monthlySpendIgnoresNullPricesAndOutOfWindowEntries() {
        val now = t0
        val fills = listOf(
            fill(1, 0, 1000, 10.0, null),     // priced null -> 0
            fill(2, -400, 100, 10.0, 999.0),  // far outside 3-month window -> dropped
        )
        val months = RunningCostAnalytics.monthlySpend(fills, emptyList(), months = 3, now = now)
        assertTrue(months.all { it.totalRupees == 0.0 })
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RunningCostAnalyticsTest"`
Expected: FAIL — `monthlySpend` / `MonthSpend` unresolved.

- [ ] **Step 3: Write the implementation**

Add to `RunningCostAnalytics.kt`:

```kotlin
import java.time.Instant
import java.time.ZoneId

/** Fuel + service spend in one calendar month ("yyyy-MM"). */
data class MonthSpend(
    val month: String,
    val fuelRupees: Double,
    val serviceRupees: Double,
) {
    val totalRupees: Double get() = fuelRupees + serviceRupees
}
```

And inside `object RunningCostAnalytics`:

```kotlin
    /**
     * Fuel + service spend per calendar month for the last [months] months,
     * oldest-first. Buckets exactly like [RideAnalytics.monthlyKm]. Null prices
     * contribute 0; entries outside the window are dropped.
     */
    fun monthlySpend(
        fills: List<FuelFillEntity>,
        services: List<ServiceLogEntity>,
        months: Int = 6,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthSpend> {
        val firstMonth = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .withDayOfMonth(1).minusMonths((months - 1).toLong())
        val fuel = LinkedHashMap<String, Double>()
        val svc = LinkedHashMap<String, Double>()
        for (i in 0 until months) {
            val m = firstMonth.plusMonths(i.toLong())
            val key = "%04d-%02d".format(m.year, m.monthValue)
            fuel[key] = 0.0
            svc[key] = 0.0
        }
        fun bucket(tMillis: Long): String? {
            val d = Instant.ofEpochMilli(tMillis).atZone(zone).toLocalDate()
            val key = "%04d-%02d".format(d.year, d.monthValue)
            return if (fuel.containsKey(key)) key else null
        }
        for (f in fills) {
            val key = bucket(f.tMillis) ?: continue
            fuel[key] = (fuel[key] ?: 0.0) + (f.rupees ?: 0.0).coerceAtLeast(0.0)
        }
        for (s in services) {
            val key = bucket(s.tMillis) ?: continue
            svc[key] = (svc[key] ?: 0.0) + (s.rupees ?: 0.0).coerceAtLeast(0.0)
        }
        return fuel.keys.map { MonthSpend(it, fuel[it] ?: 0.0, svc[it] ?: 0.0) }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RunningCostAnalyticsTest"`
Expected: PASS (10 tests total).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalytics.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RunningCostAnalyticsTest.kt
git commit -m "feat(analytics): monthly fuel+service spend bucketing with tests"
```

---

### Task 3: `RunningCostViewModel` — wire fills + services + ride-span denominator

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cost/RunningCostViewModel.kt`

No unit test (AndroidViewModel — the testable logic is in Task 1/2). Verified by compile + on-device. Pattern mirrors `MileageViewModel` (FuelStore) and `StatsViewModel`/`ServiceHistoryViewModel` (RideStore / ServiceLogStore).

- [ ] **Step 1: Create the VM**

```kotlin
package dev.mrwick.gixxerbridge.ui.cost

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.analytics.MonthSpend
import dev.mrwick.gixxerbridge.analytics.RunningCost
import dev.mrwick.gixxerbridge.analytics.RunningCostAnalytics
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.data.ServiceLogStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * Backs the running-cost view. Combines the fuel-fill ledger, the service log,
 * and the ride list (for a distance fallback when there are < 2 fills) into a
 * [RunningCost] + monthly-spend series. All numerics are pure
 * [RunningCostAnalytics] calls so they're unit-tested off-device.
 */
class RunningCostViewModel(app: Application) : AndroidViewModel(app) {

    private val fuelStore = FuelStore(GixxerDatabase.get(app).fuelFillDao())
    private val serviceStore = ServiceLogStore(GixxerDatabase.get(app).serviceLogDao())
    private val rideStore = RideStore(GixxerDatabase.get(app).rideDao())

    val cost: StateFlow<RunningCost?> =
        combine(
            fuelStore.observe(),
            serviceStore.observe(),
            rideStore.observeRides(),
        ) { fills, services, rides ->
            // Ride-history odometer span as the denominator fallback (covers the
            // < 2-fills window). max end - min start across all rides.
            val rideSpan = if (rides.isEmpty()) null else {
                val maxOdo = rides.maxOf { it.endOdoKm ?: it.startOdoKm }
                val minOdo = rides.minOf { it.startOdoKm }
                (maxOdo - minOdo).takeIf { it > 0 }
            }
            RunningCostAnalytics.cost(fills, services, fallbackDistanceKm = rideSpan)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val monthlySpend: StateFlow<List<MonthSpend>> =
        combine(fuelStore.observe(), serviceStore.observe()) { fills, services ->
            RunningCostAnalytics.monthlySpend(fills, services, months = 6)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
```

> NOTE: confirm `RideStore` exposes `observeRides()` (it does — `RideStore.kt:276`) and `RideEntity` has `startOdoKm`/`endOdoKm` (it does — used throughout `RideAnalytics.kt`).

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cost/RunningCostViewModel.kt
git commit -m "feat(cost): RunningCostViewModel wiring fuel+service+ride-span denominator"
```

---

### Task 4: Running-cost UI section (BentoTile cluster)

> **BLOCKED ON OPEN QUESTION #1/#2.** Default if unanswered: a new `COST` detail on the Stats tab (option 1a + a new `StatsDetail.COST`). The composables below are screen-agnostic — they take `RunningCost?` + `List<MonthSpend>` and can drop into either `StatsScreen` (detail) or `MileageScreen` (appended section).

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cost/RunningCostSection.kt`

UI — verified by compile + on-device visual check (no Compose unit test, matching the project's convention for `StatsScreen`/`HomeScreen`).

- [ ] **Step 1: Build the section composables**

A `RunningCostSection(cost: RunningCost?, monthly: List<MonthSpend>)` that renders:
  1. A hero `BentoTile` with `GixxerMono` ₹/km (hero) + ₹/100km sub-label (per open Q#3). Empty state ("Log fuel/service prices to see running cost") when `cost == null`.
  2. A fuel-vs-maintenance split bar — reuse the inline `Box`+`clip`+`background` stacked-bar idiom from `StatsScreen.InsightProgress` (`StatsScreen.kt:549-556`) and `TimeOfDayRow` (`StatsScreen.kt:589-626`); colour fuel with `GixxerBrand.zoneMid` and service with `GixxerBrand.zoneCool` (the same brand palette `StatsScreen` already uses).
  3. A monthly-spend bar row cloned from `WeekdayBars` (`StatsScreen.kt:629-664`) — same `weight(1f)` columns, `(6f + frac*76f).dp` heights, `GixxerBrand.accent` for non-zero months — with month labels.
  4. A coverage caption: e.g. `"${cost.fuelFillsPriced}/${cost.fuelFillsTotal} fills priced"` styled like the existing `bodySmall`/`labelSmall` muted captions, satisfying the research's "disclose coverage or it undercounts" requirement.

Follow the existing private-composable structure in `StatsScreen.kt` (e.g. `InsightHeader`, `StatCell`, `MiniRecord`) — same `MaterialTheme.typography` labels, `onSurfaceVariant` muted text, `GixxerMono.headline`/`GixxerMono.display` for numerals.

> Do not hardcode a ₹/L price like the existing `StatsInsights` "for fun" estimate (`StatsScreen.kt:489-490`) — this card uses *real logged* rupees only, which is the entire point of the feature.

- [ ] **Step 2: Wire into the chosen screen (per open Q#1)**

If default (Stats tab):
- Add `COST("RUNNING COST")` to the `StatsDetail` enum (`StatsScreen.kt:352-358`).
- Add a dashboard `BentoTile` (mirroring the RECORDS/DISTANCE-TREND tiles, `StatsScreen.kt:290-343`) with `onClick = { detail = StatsDetail.COST }`, showing a compact ₹/km figure.
- In the `when (activeDetail)` block (`StatsScreen.kt:202-242`), add a `StatsDetail.COST -> RunningCostSection(...)` branch.
- `StatsScreen` will need the cost data: either give `StatsViewModel` the cost/monthly flows (it already wraps `RideStore`; add `FuelStore`+`ServiceLogStore` there following `MileageViewModel`'s pattern) OR pass a separate `RunningCostViewModel` into `StatsScreen`. **Prefer extending `StatsViewModel`** to avoid threading a second VM through the existing call site — but this is a wiring choice to confirm when the screen decision lands.

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Build, install, and visually verify on device**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Open the app → navigate to the running-cost section. With real logged fills/services it shows ₹/km + ₹/100km, a fuel/service split bar, a 6-month spend bar row, and the coverage caption. With no priced entries it shows the empty state. Numbers match a hand calculation against the logged fills/services.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/cost/RunningCostSection.kt android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/stats/StatsScreen.kt android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/stats/StatsViewModel.kt
git commit -m "feat(cost): running-cost dashboard section (₹/km, split, monthly spend)"
```

---

## Self-Review

**Spec coverage (research Top Picks #3):**
- Blended `(fuel ₹ + service ₹) ÷ km` → Task 1 (`RunningCostAnalytics.cost`) ✓
- Fuel-vs-maintenance split → Task 1 (`fuelFraction`/`serviceFraction`) + Task 4 (split bar) ✓
- Monthly spend bar → Task 2 (`monthlySpend`) + Task 4 (bar row) ✓
- `rupees` nullable → disclose coverage → Task 1 (`fuelPricedFraction`/priced counts) + Task 4 (coverage caption) ✓
- Reuse `monthlyKm` bucketing (`RideAnalytics.kt:195-217`) → Task 2 (cloned `firstMonth`/`LinkedHashMap`/`"%04d-%02d"`) ✓
- Robust km denominator = fuel-fill odo deltas, ride-span fallback → Task 1 + Task 3 ✓
- No schema change → entire plan is recompute-on-read over existing `rupees` columns ✓

**No-assumptions compliance:** No ₹/L or km/L constant is invented — every rupee figure is the rider's own logged value; null prices are excluded and the exclusion is surfaced as coverage, not silently averaged. The only modelled quantity is the *distance denominator* (fill odo delta vs ride span), and both sources are real odometer values, not estimates.

**Placeholder scan:** No TBD/TODO in the pure-logic tasks (1–3). Task 4 is intentionally gated on the human UI decisions listed at top; its default path is fully specified.

**Type consistency:** `RunningCost(distanceKm, fuelRupees, serviceRupees, totalRupees, rupeesPerKm, rupeesPer100Km, fuelFraction, serviceFraction, fuelFillsPriced, fuelFillsTotal, servicesPriced, servicesTotal, fuelPricedFraction)` and `RunningCostAnalytics.cost(fills, services, fallbackDistanceKm)` used identically in Task 1 (impl + test) and Task 3 (VM). `MonthSpend(month, fuelRupees, serviceRupees)` + `monthlySpend(fills, services, months, now, zone)` consistent across Task 2 → 3 → 4. `FuelStore.observe()` / `ServiceLogStore.observe()` / `RideStore.observeRides()` match their declarations (`FuelFill.kt:74`, `ServiceLog.kt:78`, `RideStore.kt:276`).
