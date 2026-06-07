# Cost-per-km tracker (₹/km + ₹/100km) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface true running cost from the fuel-fill ledger — rolling ₹/km (and ₹/100km) over the trailing 5 tanks, shown next to a lifetime figure — so the rider sees, in rupees, what the bike actually costs to ride and whether that cost is climbing or falling. `FuelFillEntity.rupees` is logged today but has zero analytics; this is its first consumer.

**Architecture:** A pure `CostAnalytics` object mirrors `MileageAnalytics.perTankKmPerL`'s consecutive-pair logic exactly — but instead of `km / litres` it computes `rupees / km` per fill-to-fill interval. An interval contributes **only** when both its `km > 0` (data-entry guard) **and** the closing fill's `rupees` is non-null and `> 0` (price-less intervals are excluded, never zero-filled). The object returns a small `CostStats` value (rolling ₹/km over the last N tanks, lifetime ₹/km, and a coverage count of how many intervals had a price vs total) so the UI can disclose partial coverage. The `MileageScreen`/`MileageViewModel` (which already own the fill domain and render `AverageCard`) gain a second card mirroring `AverageCard`. No new Room column, no schema bump.

**Tech Stack:** Kotlin, Jetpack Compose, Room (`FuelFillEntity` — read only), JUnit4 (`org.junit`), Gradle.

**Research entry:** `docs/superpowers/research/2026-06-06-stats-and-features-research.md` → Top Picks #2 ("Cost-per-km tracker").

**No-assumptions notes (per project CLAUDE.md):**
- ₹/km is **rider-recorded ground truth** (fuel spend ÷ odometer delta), not a modelled estimate — this is its strength over the km/bar range work. The only honesty caveat is **coverage**: intervals whose closing fill has a null `rupees` are excluded, so a sparse-price ledger yields a figure over fewer tanks than the rider may assume. The card discloses this ("over N of M tanks").
- "₹/km is rising/falling" framing is **deferred** — a trend claim needs more thought about noise (fuel-price moves vs riding-style changes are conflated). This plan ships the two numbers (rolling vs lifetime) and lets the rider eyeball the gap; it does **not** assert a direction.
- The `AddFillDialog` already requires `Total price (₹) > 0` to submit (`MileageScreen.kt:252-253`), so fills logged through the app's current UI always carry a price. The null-`rupees` path still matters because `FuelFillEntity.rupees` is nullable in the schema and older/imported/programmatic fills may lack it — the analytics must not assume every fill is priced. **To verify on real data:** whether Arjun's existing ledger has any null-price rows.

**Deferred (not in this plan):**
- A "running cost dashboard" that *blends* fuel + service ₹ (research Top Pick #3) — separate, larger feature.
- Monthly-spend bars / projected month-end total.
- Any ₹/km trend line or "cost is rising" callout.
- A Stats-tab surface for cost (see Open Questions — placement decision needed).

---

## Open questions for the human (decide before/at Task 3)

These are UX/placement calls the plan cannot make unilaterally:

1. **Where does the cost card live?** This plan's default is the **Mileage screen** (`MileageScreen`), directly under the existing `AverageCard`, because that screen already owns the fuel-fill domain, already loads the fills flow, and `AverageCard` is the exact pattern to mirror. Alternative: a tile on the **Stats** dashboard (`StatsScreen`) next to RECORDS, or a row in the FOR-FUN insights. **Mileage is the low-risk default; confirm or redirect.**
2. **Headline unit: ₹/km or ₹/100km?** ₹/km on a ~45 km/L bike is a small number (~₹2.2/km), which reads awkwardly. ₹/100km (~₹220) is more legible as a headline. Default in this plan: **₹/km as the hero numeral, ₹/100km as the sub-line** (mirrors how `AverageCard` shows one hero + one caption). Confirm or flip.
3. **Coverage disclosure wording.** Default caption: `"₹X.XX/100km · last N tanks"` with a secondary `"over N of M priced tanks"` only when coverage is partial. Confirm tone/wording.
4. **Should lifetime be shown at all, or only the rolling figure?** Default: show **rolling as hero, lifetime as a small comparison** so the rider sees the gap. If you'd rather keep the card minimal (rolling only), drop the lifetime line — Task 1 still computes it (cheap) but Task 3 just won't render it.

The plan below implements the **defaults**. If the human picks differently, only Task 3 (UI) changes; Tasks 1–2 (pure logic + VM) are placement-agnostic.

---

### Task 1: `CostAnalytics` pure analytics + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/CostAnalytics.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/CostAnalyticsTest.kt`

This is the only task with pure logic; it is **TDD — failing test first**.

- [ ] **Step 1: Write the failing test**

Create `CostAnalyticsTest.kt`. The `fill(...)` helper and the `t0`/`day` constants are copied verbatim from `MileageAnalyticsTest.kt` so the two suites read identically.

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [CostAnalytics]. No Room, no Android — hand-crafted
 * [FuelFillEntity] inputs. Mirrors MileageAnalyticsTest's structure: same
 * consecutive-pair logic, but rupees/km instead of km/litres.
 */
class CostAnalyticsTest {

    private val day: Long = 86_400_000L
    private val t0: Long = 1_750_000_000_000L

    private fun fill(
        id: Long,
        daysAfterT0: Long,
        odometerKm: Int,
        litres: Double,
        rupees: Double? = null,
        note: String? = null,
    ): FuelFillEntity = FuelFillEntity(
        id = id,
        tMillis = t0 + daysAfterT0 * day,
        odometerKm = odometerKm,
        litres = litres,
        rupees = rupees,
        note = note,
    )

    // ---------- perTankRupeesPerKm ----------

    @Test fun perTankEmptyIsEmpty() {
        assertTrue(CostAnalytics.perTankRupeesPerKm(emptyList()).isEmpty())
    }

    @Test fun perTankSingleFillIsEmpty() {
        val one = listOf(fill(1, 0, 1000, 8.0, rupees = 800.0))
        assertTrue(CostAnalytics.perTankRupeesPerKm(one).isEmpty())
    }

    @Test fun perTankComputesRupeesPerKm() {
        // 1000 -> 1320 km (320 km), closing fill cost ₹640 -> 2.0 ₹/km
        // 1320 -> 1620 km (300 km), closing fill cost ₹600 -> 2.0 ₹/km
        val fills = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = 640.0),
            fill(3, 10, 1620, 6.0, rupees = 600.0),
        )
        val pairs = CostAnalytics.perTankRupeesPerKm(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(2.0, pairs[0].second, 0.001)
        assertEquals(3L, pairs[1].first)
        assertEquals(2.0, pairs[1].second, 0.001)
    }

    @Test fun perTankSortsByTimestampRegardlessOfInputOrder() {
        val fills = listOf(
            fill(3, 10, 1620, 6.0, rupees = 600.0),
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = 640.0),
        )
        val pairs = CostAnalytics.perTankRupeesPerKm(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(3L, pairs[1].first)
    }

    @Test fun perTankExcludesNullPriceInterval() {
        // Closing fill of the only interval has no price -> excluded entirely.
        val fills = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = null),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(fills).isEmpty())
    }

    @Test fun perTankExcludesZeroOrNegativePrice() {
        val zero = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 5, 1320, 8.0, rupees = 0.0),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(zero).isEmpty())
    }

    @Test fun perTankRejectsNonPositiveKm() {
        val sameOdo = listOf(
            fill(1, 0, 1000, 8.0, rupees = 800.0),
            fill(2, 1, 1000, 2.0, rupees = 200.0),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(sameOdo).isEmpty())

        val backwards = listOf(
            fill(1, 0, 1500, 8.0, rupees = 800.0),
            fill(2, 1, 1400, 4.0, rupees = 400.0),
        )
        assertTrue(CostAnalytics.perTankRupeesPerKm(backwards).isEmpty())
    }

    @Test fun perTankKeepsPricedDropsUnpricedInMixedLedger() {
        // priced -> priced (valid), priced -> unpriced (excluded), unpriced -> priced (valid).
        // Note: km is measured purely from odometer deltas, independent of price
        // on the *previous* fill, so an unpriced opener does NOT poison the next interval.
        val fills = listOf(
            fill(1, 0, 0, 1.0, rupees = 100.0),   // odo 0
            fill(2, 1, 100, 1.0, rupees = 200.0), // 100 km, ₹200 -> 2.0 ₹/km  (kept)
            fill(3, 2, 220, 1.0, rupees = null),  // 120 km, no price          (dropped)
            fill(4, 3, 320, 1.0, rupees = 300.0), // 100 km, ₹300 -> 3.0 ₹/km  (kept)
        )
        val pairs = CostAnalytics.perTankRupeesPerKm(fills)
        assertEquals(2, pairs.size)
        assertEquals(2L, pairs[0].first)
        assertEquals(2.0, pairs[0].second, 0.001)
        assertEquals(4L, pairs[1].first)
        assertEquals(3.0, pairs[1].second, 0.001)
    }

    // ---------- stats ----------

    @Test fun statsNullWhenNoPricedIntervals() {
        val fills = listOf(
            fill(1, 0, 1000, 8.0, rupees = null),
            fill(2, 5, 1320, 8.0, rupees = null),
        )
        assertNull(CostAnalytics.stats(fills))
    }

    @Test fun statsRollingAndLifetimeAndCoverage() {
        // 4 priced intervals: 1.0, 2.0, 3.0, 4.0 ₹/km. count=2 -> rolling avg(3,4)=3.5.
        // lifetime avg(1,2,3,4)=2.5. coverage 4 of 4.
        val fills = listOf(
            fill(1, 0, 0, 1.0, rupees = 0.0).copy(rupees = null), // anchor, no closing role
            fill(2, 1, 100, 1.0, rupees = 100.0), // 1.0
            fill(3, 2, 200, 1.0, rupees = 200.0), // 2.0
            fill(4, 3, 300, 1.0, rupees = 300.0), // 3.0
            fill(5, 4, 400, 1.0, rupees = 400.0), // 4.0
        )
        val s = CostAnalytics.stats(fills, count = 2)!!
        assertEquals(3.5, s.rollingRupeesPerKm, 0.001)
        assertEquals(2.5, s.lifetimeRupeesPerKm, 0.001)
        assertEquals(4, s.pricedIntervals)
        assertEquals(4, s.totalIntervals)
        // ₹/100km is just ×100.
        assertEquals(350.0, s.rollingRupeesPer100Km, 0.001)
        assertEquals(250.0, s.lifetimeRupeesPer100Km, 0.001)
    }

    @Test fun statsCoverageReportsPartial() {
        // 2 intervals total, only 1 priced.
        val fills = listOf(
            fill(1, 0, 0, 1.0, rupees = 100.0),
            fill(2, 1, 100, 1.0, rupees = 200.0), // priced 2.0 ₹/km
            fill(3, 2, 200, 1.0, rupees = null),  // unpriced
        )
        val s = CostAnalytics.stats(fills)!!
        assertEquals(2.0, s.rollingRupeesPerKm, 0.001)
        assertEquals(1, s.pricedIntervals)
        assertEquals(2, s.totalIntervals)
        assertTrue(s.isPartialCoverage)
    }
}
```

> Note on the anchor row in `statsRollingAndLifetimeAndCoverage`: the first chronological fill never plays the "closing" role, so its price is irrelevant; it is written `null` to make that explicit. `totalIntervals` counts consecutive *pairs* (4 here), matching `perTankKmPerL`'s "first fill omitted" rule.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.CostAnalyticsTest"`
Expected: FAIL — compilation error, `CostAnalytics` / `CostStats` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `CostAnalytics.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity

/**
 * Rolling and lifetime running cost, with coverage disclosure.
 *
 * [rollingRupeesPerKm] averages the last N priced tanks; [lifetimeRupeesPerKm]
 * averages all priced tanks. [pricedIntervals]/[totalIntervals] expose how many
 * fill-to-fill intervals actually had a price, so the UI can flag a partial
 * figure when the rider logged some price-less fills.
 */
data class CostStats(
    val rollingRupeesPerKm: Double,
    val lifetimeRupeesPerKm: Double,
    val pricedIntervals: Int,
    val totalIntervals: Int,
) {
    val rollingRupeesPer100Km: Double get() = rollingRupeesPerKm * 100.0
    val lifetimeRupeesPer100Km: Double get() = lifetimeRupeesPerKm * 100.0
    /** True when some intervals were excluded for lacking a price. */
    val isPartialCoverage: Boolean get() = pricedIntervals < totalIntervals
}

/**
 * Pure analytics over the manual fuel-fill log's [FuelFillEntity.rupees].
 *
 * Mirrors [MileageAnalytics.perTankKmPerL] exactly — same consecutive-pair,
 * sort-by-timestamp, km>0 guard — but divides the closing fill's *rupees* by the
 * interval's km to get ₹/km (true rider-recorded running cost) instead of km/L.
 *
 * Price-less intervals (closing fill's [FuelFillEntity.rupees] null or <= 0) are
 * **excluded**, never zero-filled — a missing price must not drag the average to
 * zero. km is measured from odometer deltas only, so an unpriced *opening* fill
 * does not invalidate the following interval.
 *
 * Pure-JVM, deterministic, side-effect free — tested in CostAnalyticsTest.
 */
object CostAnalytics {

    /**
     * Count of consecutive valid-km pairs (denominator for coverage). Mirrors
     * the pair count from [MileageAnalytics.perTankKmPerL]'s km>0 guard, but does
     * NOT apply the price filter — this is the "how many tanks could have had a
     * cost" total.
     */
    fun totalIntervals(fills: List<FuelFillEntity>): Int {
        val asc = fills.sortedBy { it.tMillis }
        var n = 0
        for (i in 1 until asc.size) {
            if (asc[i].odometerKm - asc[i - 1].odometerKm > 0) n++
        }
        return n
    }

    /**
     * `(fillId, rupeesPerKm)` for each consecutive pair whose km>0 AND whose
     * closing fill has a positive price. Ordered chronologically (oldest first).
     */
    fun perTankRupeesPerKm(fills: List<FuelFillEntity>): List<Pair<Long, Double>> {
        val asc = fills.sortedBy { it.tMillis }
        val out = mutableListOf<Pair<Long, Double>>()
        for (i in 1 until asc.size) {
            val km = asc[i].odometerKm - asc[i - 1].odometerKm
            val rupees = asc[i].rupees
            if (km > 0 && rupees != null && rupees > 0.0) {
                out += asc[i].id to (rupees / km)
            }
        }
        return out
    }

    /**
     * Rolling (last [count] priced tanks) + lifetime ₹/km, with coverage. Returns
     * null when no interval has a usable price (so the UI shows an em-dash).
     */
    fun stats(fills: List<FuelFillEntity>, count: Int = 5): CostStats? {
        val priced = perTankRupeesPerKm(fills)
        if (priced.isEmpty()) return null
        val rolling = priced.takeLast(count).map { it.second }.average()
        val lifetime = priced.map { it.second }.average()
        return CostStats(
            rollingRupeesPerKm = rolling,
            lifetimeRupeesPerKm = lifetime,
            pricedIntervals = priced.size,
            totalIntervals = totalIntervals(fills),
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.CostAnalyticsTest"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/CostAnalytics.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/CostAnalyticsTest.kt
git commit -m "feat(analytics): pure CostAnalytics (rolling+lifetime rupee/km) with tests"
```

---

### Task 2: `MileageViewModel` — expose `costStats`

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageViewModel.kt`

No new unit test: the VM is a thin `stateIn(map { CostAnalytics.stats(...) })` over the already-tested pure object, exactly like the existing `averageKmPerL`/`perTank` flows. Verified by compile + the screen.

- [ ] **Step 1: Add the import**

Add near the existing `import dev.mrwick.gixxerbridge.analytics.MileageAnalytics`:

```kotlin
import dev.mrwick.gixxerbridge.analytics.CostAnalytics
import dev.mrwick.gixxerbridge.analytics.CostStats
```

- [ ] **Step 2: Add the `costStats` flow**

Immediately after the `averageKmPerL` flow (`MileageViewModel.kt:37-39`), add:

```kotlin
    /**
     * Rolling (last [TRAILING_COUNT] tanks) + lifetime running cost in ₹/km,
     * plus price coverage. Null when no fill interval has a usable price.
     * Mirrors [averageKmPerL] — same trailing window, same pure-object backing.
     */
    val costStats: StateFlow<CostStats?> = fills
        .map { CostAnalytics.stats(it, count = TRAILING_COUNT) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageViewModel.kt
git commit -m "feat(mileage): expose costStats (rupee/km) from fuel ledger"
```

---

### Task 3: `MileageScreen` — `CostCard` mirroring `AverageCard`

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageScreen.kt`

UI — verified by compile + on-device visual check (no Compose unit test for this card; the existing `MileageScreen` has none either).

> **Placement default** (see Open Questions #1): the `CostCard` renders directly under `AverageCard` in the `MileageScreen` `Column`. If the human chose Stats instead, skip this task and instead add the card to `StatsScreen` — the VM flow from Task 2 is reusable, but `StatsViewModel` would need its own `costStats` flow over `FuelStore` (it does not currently load fills; `StatsViewModel` only reads rides/samples — confirmed by grep). That alternative is a larger change and is **not** detailed here.

- [ ] **Step 1: Collect `costStats` and the import**

Add the import near the other `dev.mrwick.gixxerbridge.analytics` usages (there is none yet in this file; add the data-class import):

```kotlin
import dev.mrwick.gixxerbridge.analytics.CostStats
```

In `MileageScreen`, after `val avg by vm.averageKmPerL.collectAsStateWithLifecycle()` (`MileageScreen.kt:69`), add:

```kotlin
    val cost by vm.costStats.collectAsStateWithLifecycle()
```

- [ ] **Step 2: Render `CostCard` under `AverageCard`**

After the `AverageCard(avg)` call (`MileageScreen.kt:101`), add:

```kotlin
            CostCard(cost)
```

- [ ] **Step 3: Add the `CostCard` composable**

Add a new private composable directly below the existing `AverageCard` function (after `MileageScreen.kt:178`). It mirrors `AverageCard`'s `BentoTile` + label + `HeroNumeral` + caption structure. Default UI per Open Questions #2/#3/#4: hero = ₹/km, sub = ₹/100km + window, plus a lifetime comparison and a partial-coverage note.

```kotlin
/**
 * Rolling running-cost card. Hero is ₹/km over the last
 * [MileageViewModel.TRAILING_COUNT] priced tanks; sub-line gives ₹/100km and the
 * lifetime figure for comparison. Renders an em-dash when no fill has a price.
 *
 * Honesty: when some intervals lacked a price, a coverage note discloses the
 * figure is over fewer tanks than logged. We do NOT assert a cost trend
 * direction — the rider eyeballs rolling-vs-lifetime themselves.
 */
@Composable
private fun CostCard(cost: CostStats?) {
    BentoTile(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        animateEntry = false,
        contentPadding = PaddingValues(20.dp),
    ) {
        Text(
            "LAST ${MileageViewModel.TRAILING_COUNT} TANKS · ₹/KM",
            style = MaterialTheme.typography.labelMedium,
            color = GixxerTokens.accent,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HeroNumeral(
            text = cost?.let { "₹%.2f".format(it.rollingRupeesPerKm) } ?: "—",
            color = GixxerTokens.onSurface,
            fontSize = 56.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (cost == null) {
            Text(
                "Log fills with a price to see running cost",
                style = MaterialTheme.typography.labelSmall,
                color = GixxerTokens.onSurfaceDim,
            )
        } else {
            Text(
                "≈ ₹${"%.0f".format(cost.rollingRupeesPer100Km)} / 100 km · " +
                    "lifetime ₹${"%.2f".format(cost.lifetimeRupeesPerKm)}/km",
                style = MaterialTheme.typography.labelSmall,
                color = GixxerTokens.onSurfaceDim,
            )
            if (cost.isPartialCoverage) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "over ${cost.pricedIntervals} of ${cost.totalIntervals} priced tanks",
                    style = MaterialTheme.typography.labelSmall,
                    color = GixxerTokens.onSurfaceDim,
                )
            }
        }
    }
}
```

All identifiers used (`BentoTile`, `HeroNumeral`, `GixxerTokens.accent/onSurface/onSurfaceDim`, `PaddingValues`, `Spacer`, `Text`, `Modifier`, `dp`, `sp`) are already imported in `MileageScreen.kt` (verified against the existing `AverageCard`).

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Build, install, and visually verify on device**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Open the app → Mileage tab:
- With ≥2 priced fills: the new card shows `₹X.XX` hero, `≈ ₹YYY / 100 km · lifetime ₹Z.ZZ/km` sub-line under the existing km/L card.
- With <2 fills or no prices: hero shows `—` and the "Log fills with a price…" caption.
- If the ledger mixes priced and price-less fills: the "over N of M priced tanks" note appears.

(No bike connection required — this reads only the local fill ledger. See Caveats for the one real-data check.)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageScreen.kt
git commit -m "feat(mileage): running-cost card (rupee/km + rupee/100km) under true-mileage"
```

---

## Self-Review

**Spec / research coverage:**
- True ₹/km and ₹/100km from fuel spend ÷ km → Task 1 (`CostStats`) ✓
- Trailing-5-tank rolling vs lifetime → Task 1 (`rolling` uses `count`, `lifetime` uses all) ✓
- Mirrors `MileageAnalytics.perTankKmPerL` consecutive-pair logic → Task 1 (same sort/guard, rupees÷km) ✓
- `rupees` nullable → exclude price-less intervals, never zero-fill → Task 1 (`rupees != null && rupees > 0`) + coverage disclosure ✓
- One small pure object + tests + one card mirroring `AverageCard` → Tasks 1 + 3 ✓
- Partial-coverage figure flagged → `CostStats.isPartialCoverage` + UI note ✓

**No-assumptions compliance:** ₹/km is presented as rider-recorded ground truth (it is — spend ÷ odo delta); the only hedge is coverage, which is disclosed numerically, not asserted. No trend-direction claim is made. The km/L "45" estimate used elsewhere in the app is **not** reused here — cost comes entirely from logged values.

**Schema / migration:** **No Room migration.** Reads existing `FuelFillEntity.rupees`/`odometerKm`/`tMillis` only; adds no columns/entities. This honours `GixxerDatabase`'s `fallbackToDestructiveMigration` (any new column would wipe history) — the feature is recompute-on-read, no persistence.

**Placeholder scan:** No TBD/TODO; every step has concrete code + a command. The only deliberately-open items are the UX decisions in Open Questions, which the defaults resolve and the human can override (touching only Task 3).

**Type consistency:** `CostStats(rollingRupeesPerKm, lifetimeRupeesPerKm, pricedIntervals, totalIntervals)` + derived `rollingRupeesPer100Km`/`lifetimeRupeesPer100Km`/`isPartialCoverage`, and `CostAnalytics.stats(fills, count)` / `perTankRupeesPerKm(fills)` / `totalIntervals(fills)` are used identically in Task 1 (impl + test), Task 2 (VM), Task 3 (UI). `MileageViewModel.TRAILING_COUNT` reused for the window + label.

**Test discipline:** Pure analytics (`CostAnalytics`) is JVM-unit-tested with JUnit4 (`org.junit`), failing-test-first (Task 1 steps 1→4). VM and UI are verified by build + on-device, matching the existing `MileageViewModel`/`MileageScreen` (neither has a unit/Compose test today).
