# Fuel-tank estimate (litres-left + range) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show estimated litres remaining and tank range on the Home fuel tile, computed from logged full fills + distance ridden, and fix the two existing fuel-tile bugs (in-memory-only "last seen" reading 0; economy mislabeled as fuel level).

**Architecture:** A pure `FuelTankEstimator` computes `litres_left = (capacity − km_since_last_fill ÷ km_per_L)` clamped to `[0, capacity]`, with `range = litres × km/L`. The rider always fills to full, so each fill re-anchors to 12 L. A persisted last-telemetry snapshot (DataStore) supplies the odometer offline. The Home VM combines fills + odo + measured avg km/L + capacity into a `FuelEstimate`; the tile and the connected RANGE hero both read it.

**Tech Stack:** Kotlin, Jetpack Compose, Room (`FuelFillEntity`), DataStore Preferences (`Settings`), JUnit4 (`org.junit`), Gradle.

**Spec:** `docs/superpowers/specs/2026-06-06-fuel-tank-estimate-design.md`

**Deferred (not in this plan):** A capacity-edit UI field. Default 12 L is user-confirmed; `Settings.fuelCapacityL` is added now so an edit field is a trivial follow-up.

---

### Task 1: `FuelTankEstimator` pure estimator + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/FuelTankEstimator.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/FuelTankEstimatorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `FuelTankEstimatorTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [FuelTankEstimator]. No Room, no Android. */
class FuelTankEstimatorTest {

    private val t0 = 1_750_000_000_000L
    private val day = 86_400_000L

    private fun fill(id: Long, daysAfter: Long, odo: Int, litres: Double) =
        FuelFillEntity(id = id, tMillis = t0 + daysAfter * day, odometerKm = odo, litres = litres, rupees = null, note = null)

    @Test fun justFilledReadsFullTank() {
        val fills = listOf(fill(1, 0, 1000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1000, avgKmPerL = null,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(12.0, e.litresLeft, 0.001)
        assertEquals(1.0, e.percent, 0.001)
        assertEquals(540.0, e.rangeKm, 0.001) // 12 * 45
        assertFalse(e.isRough)
    }

    @Test fun midTankUsesMeasuredAvgKmPerL() {
        // Two fills: 1000 -> 1450 on 10 L => 45 km/L measured.
        val fills = listOf(fill(1, 0, 1000, 10.0), fill(2, 5, 1450, 10.0))
        val avg = MileageAnalytics.averageKmPerL(fills)
        assertEquals(45.0, avg!!, 0.001)
        // Ridden 90 km past the last fill => 2 L used => 10 L left.
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1540, avgKmPerL = avg,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(10.0, e.litresLeft, 0.001)
        assertEquals(450.0, e.rangeKm, 0.001) // 10 * 45
        assertEquals(45.0, e.kmPerLUsed, 0.001)
    }

    @Test fun emptyTankClampsToZero() {
        val fills = listOf(fill(1, 0, 1000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1000 + 100_000, avgKmPerL = 45.0,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0,
        )!!
        assertEquals(0.0, e.litresLeft, 0.001)
        assertEquals(0.0, e.rangeKm, 0.001)
    }

    @Test fun negativeKmSinceFillGuardsToFull() {
        val fills = listOf(fill(1, 0, 2000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1500, avgKmPerL = 45.0,
            bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0,
        )!!
        assertEquals(12.0, e.litresLeft, 0.001)
    }

    @Test fun coldStartUsesBarsBootstrap() {
        val e = FuelTankEstimator.estimate(
            fills = emptyList(), currentOdometerKm = null, avgKmPerL = null,
            bikeLiveKmPerL = null, bikeFuelBars = 3, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(6.0, e.litresLeft, 0.001) // 3/6 * 12
        assertTrue(e.isRough)
        assertEquals(270.0, e.rangeKm, 0.001) // 6 * 45
    }

    @Test fun coldStartNoBarsIsUnavailable() {
        assertNull(
            FuelTankEstimator.estimate(
                fills = emptyList(), currentOdometerKm = null, avgKmPerL = null,
                bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 12.0,
            )
        )
    }

    @Test fun fallbackOrderingPrefersAvgThenBikeLive() {
        val fills = listOf(fill(1, 0, 1000, 12.0))
        val e = FuelTankEstimator.estimate(
            fills = fills, currentOdometerKm = 1000, avgKmPerL = null,
            bikeLiveKmPerL = 50.0, bikeFuelBars = null, capacityL = 12.0, fallbackKmPerL = 45.0,
        )!!
        assertEquals(50.0, e.kmPerLUsed, 0.001) // bike-live used over fallback
    }

    @Test fun zeroCapacityIsUnavailable() {
        assertNull(
            FuelTankEstimator.estimate(
                fills = listOf(fill(1, 0, 1000, 12.0)), currentOdometerKm = 1000, avgKmPerL = 45.0,
                bikeLiveKmPerL = null, bikeFuelBars = null, capacityL = 0.0,
            )
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.FuelTankEstimatorTest"`
Expected: FAIL — compilation error, `FuelTankEstimator` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `FuelTankEstimator.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.FuelFillEntity

/**
 * Estimated tank state: litres remaining, fill fraction, and resulting range.
 *
 * [isRough] is true for the pre-first-fill bootstrap (derived from the bike's
 * coarse 6-bar gauge rather than the fill ledger).
 */
data class FuelEstimate(
    val litresLeft: Double,
    val percent: Double, // 0.0..1.0
    val rangeKm: Double,
    val kmPerLUsed: Double,
    val isRough: Boolean,
)

/**
 * Estimates litres remaining in the tank + range, from the manual fuel-fill log
 * and the current odometer.
 *
 * Model: the rider always fills to full, so the most-recent fill re-anchors the
 * tank to [capacityL]. We subtract litres consumed since that fill, where
 * consumed = (current odo − fill odo) / km-per-L. The km/L used is the measured
 * fill-to-fill average ([MileageAnalytics.averageKmPerL]); callers pass bike-live
 * and fixed-default fallbacks for the window before 2 fills exist.
 *
 * Pure JVM, deterministic — tested in FuelTankEstimatorTest.
 */
object FuelTankEstimator {

    // ASSUMED: ~45 km/L for the Gixxer SF 150 — unverified; only used for the
    // pre-first-fill bootstrap when the bike isn't live. Tune once measured.
    const val DEFAULT_FALLBACK_KM_PER_L: Double = 45.0

    private const val FUEL_BARS_MAX = 6.0

    fun estimate(
        fills: List<FuelFillEntity>,
        currentOdometerKm: Int?,
        avgKmPerL: Double?,
        bikeLiveKmPerL: Double?,
        bikeFuelBars: Int?,
        capacityL: Double,
        fallbackKmPerL: Double = DEFAULT_FALLBACK_KM_PER_L,
    ): FuelEstimate? {
        if (capacityL <= 0.0) return null
        val kmPerL = listOfNotNull(avgKmPerL, bikeLiveKmPerL).firstOrNull { it > 0.0 } ?: fallbackKmPerL
        if (kmPerL <= 0.0 || kmPerL.isNaN()) return null

        val anchor = fills.maxByOrNull { it.tMillis }
        if (anchor != null && currentOdometerKm != null) {
            val kmSince = (currentOdometerKm - anchor.odometerKm).coerceAtLeast(0)
            val litres = (capacityL - kmSince / kmPerL).coerceIn(0.0, capacityL)
            return FuelEstimate(litres, litres / capacityL, litres * kmPerL, kmPerL, isRough = false)
        }

        // Cold-start: no fills (or no odometer yet) → rough bars→litres bootstrap.
        val bars = bikeFuelBars ?: return null
        if (bars < 0) return null
        val litres = ((bars / FUEL_BARS_MAX) * capacityL).coerceIn(0.0, capacityL)
        return FuelEstimate(litres, litres / capacityL, litres * kmPerL, kmPerL, isRough = true)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.FuelTankEstimatorTest"`
Expected: PASS (8 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/FuelTankEstimator.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/FuelTankEstimatorTest.kt
git commit -m "feat(fuel): pure FuelTankEstimator (litres-left + range) with tests"
```

---

### Task 2: `Settings` — fuel capacity + last-telemetry snapshot

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/Settings.kt`

No unit test: `Settings` is DataStore-backed (Android) and has no existing unit test; the new flows reuse the already-tested `encodeNullable*`/`decodeNullable*` helpers. Verified by compile + the VM test path.

- [ ] **Step 1: Add the `LastTelemetry` holder + capacity/snapshot flows**

In `Settings.kt`, add a top-level data class just below the `class Settings(...)` declaration's closing brace is NOT required — place it above the `// ---------- Conversion helpers` comment block near the bottom of the file:

```kotlin
/**
 * Last telemetry frame persisted so the Home fuel estimate works while the bike
 * is disconnected. Written (throttled to odometer changes) by BikeBridgeService.
 */
data class LastTelemetry(
    val odometerKm: Int,
    val fuelBars: Int?,
    val kmPerL: Double?,
    val tMillis: Long,
)
```

Inside the `Settings` class, add these read flows (place them after the `activeRideMetric` flow, ~line 149):

```kotlin
    /** User-set fuel-tank capacity in litres; defaults to [DEFAULT_FUEL_CAPACITY_L]. */
    val fuelCapacityL: Flow<Double> =
        ds.data.map { it[Keys.FUEL_CAPACITY_L] ?: DEFAULT_FUEL_CAPACITY_L }

    /** Last persisted telemetry snapshot (odo/bars/km-L); null until first frame. */
    val lastTelemetry: Flow<LastTelemetry?> =
        ds.data.map { p ->
            val odo = p[Keys.LAST_TELEM_ODO] ?: return@map null
            LastTelemetry(
                odometerKm = odo,
                fuelBars = decodeNullableInt(p[Keys.LAST_TELEM_BARS] ?: -1),
                kmPerL = decodeNullableDouble(p[Keys.LAST_TELEM_KMPL]),
                tMillis = p[Keys.LAST_TELEM_TMS] ?: 0L,
            )
        }
```

- [ ] **Step 2: Add the setters**

After `setActiveRideMetric` (~line 288), add:

```kotlin
    /** Set the fuel-tank capacity in litres. */
    suspend fun setFuelCapacityL(litres: Double) {
        ds.edit { it[Keys.FUEL_CAPACITY_L] = litres }
    }

    /** Persist the latest telemetry snapshot for the offline fuel estimate. */
    suspend fun setLastTelemetry(
        odometerKm: Int,
        fuelBars: Int?,
        kmPerL: Double?,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        ds.edit {
            it[Keys.LAST_TELEM_ODO] = odometerKm
            it[Keys.LAST_TELEM_BARS] = encodeNullableInt(fuelBars)
            it[Keys.LAST_TELEM_KMPL] = encodeNullableDouble(kmPerL)
            it[Keys.LAST_TELEM_TMS] = atMillis
        }
    }
```

- [ ] **Step 3: Add the preference keys + default constant**

In the `Keys` object (after `ACTIVE_RIDE_METRIC`, ~line 312):

```kotlin
        val FUEL_CAPACITY_L = doublePreferencesKey("fuel_capacity_l")
        val LAST_TELEM_ODO = intPreferencesKey("last_telem_odo_km")
        val LAST_TELEM_BARS = intPreferencesKey("last_telem_fuel_bars")
        val LAST_TELEM_KMPL = doublePreferencesKey("last_telem_kmpl")
        val LAST_TELEM_TMS = longPreferencesKey("last_telem_tmillis")
```

In the `companion object` (after `DEFAULT_ACTIVE_RIDE_METRIC`, ~line 349):

```kotlin
        /** Default fuel-tank capacity (litres) — Gixxer SF 150, user-editable. */
        const val DEFAULT_FUEL_CAPACITY_L: Double = 12.0
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/Settings.kt
git commit -m "feat(settings): fuel capacity + persisted last-telemetry snapshot"
```

---

### Task 3: Persist the telemetry snapshot from `BikeBridgeService`

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ble/BikeBridgeService.kt`

No unit test (Android service). Verified by compile + on-device: ride, kill app, reopen disconnected → fuel tile still shows an estimate.

- [ ] **Step 1: Add a throttled persistence collector**

In `onCreate`, immediately AFTER the `lifecycleScope.launch { ... bleClient.state.collect { state -> when(state) { ... } } }` block that handles `Ready`/`Disconnected`/`Failed` (the block ending at ~line 378), add a new collector. It reuses the existing `settings` field and `TelemetryRepository`:

```kotlin
        // Persist a last-telemetry snapshot (odo / bars / km-L) so the Home fuel
        // estimate works while the bike is disconnected. Throttled to odometer
        // changes — the odo advances ~once/km, so this writes a few times/min at
        // most, not at the 1 Hz telemetry cadence.
        lifecycleScope.launch {
            var lastPersistedOdo = -1
            TelemetryRepository.latest.collect { frame ->
                if (frame == null) return@collect
                if (frame.odometerKm != lastPersistedOdo) {
                    lastPersistedOdo = frame.odometerKm
                    settings.setLastTelemetry(
                        odometerKm = frame.odometerKm,
                        fuelBars = frame.fuelBars,
                        kmPerL = frame.fuelEconKmlV2,
                    )
                }
            }
        }
```

Confirm `TelemetryRepository` is imported (it is used at `BikeBridgeService.kt:305`). If `frame.fuelEconKmlV2` is unresolved, confirm the import for `TelemetryFrame` is present (it is — used in the notify handler).

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ble/BikeBridgeService.kt
git commit -m "feat(ble): persist last-telemetry snapshot for offline fuel estimate"
```

---

### Task 4: `HomeViewModel` — expose `fuelEstimate`, drop old `rangeKm`

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt`

- [ ] **Step 1: Add a `FuelStore` handle**

Add imports at the top of `HomeViewModel.kt`:

```kotlin
import dev.mrwick.gixxerbridge.analytics.FuelEstimate
import dev.mrwick.gixxerbridge.analytics.FuelTankEstimator
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
```

After `private val rideStore = AppGraph.rideStore(app)` (~line 47), add:

```kotlin
    private val fuelStore = FuelStore(GixxerDatabase.get(app).fuelFillDao())
```

- [ ] **Step 2: Add the `fuelEstimate` flow**

Replace the existing `rangeKm` flow (lines ~62-68) with the `fuelEstimate` flow below. (We remove `rangeKm`; the RANGE hero will read `fuelEstimate.rangeKm` in Task 5.)

```kotlin
    /**
     * Estimated tank state (litres left, %, range) from the fuel-fill ledger +
     * current odometer. Uses the measured fill-to-fill avg km/L, falling back to
     * the bike's live economy then a fixed default. Works offline via the
     * persisted last-telemetry snapshot. Null when no estimate is possible
     * (no fills and no fuel-bar reading).
     */
    val fuelEstimate: StateFlow<FuelEstimate?> =
        combine(
            fuelStore.observe(),
            TelemetryRepository.latest,
            settings.lastTelemetry,
            settings.fuelCapacityL,
        ) { fills, latest, lastTelem, capacity ->
            FuelTankEstimator.estimate(
                fills = fills,
                currentOdometerKm = latest?.odometerKm ?: lastTelem?.odometerKm,
                avgKmPerL = MileageAnalytics.averageKmPerL(fills),
                bikeLiveKmPerL = latest?.fuelEconKmlV2 ?: lastTelem?.kmPerL,
                bikeFuelBars = latest?.fuelBars ?: lastTelem?.fuelBars,
                capacityL = capacity,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

Note: this `combine` has 4 sources (a supported overload). Keep the existing `latestTelemetry` flow as-is — `OdoTile` and the hero's fuel ring still use it.

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: FAIL — `HomeScreen.kt` still references the removed `vm.rangeKm` / `RangeHero(rangeKm = ...)`. That is fixed in Task 5; this failure is expected here.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt
git commit -m "feat(home): expose fuelEstimate from fill ledger; drop bars-based rangeKm"
```

---

### Task 5: Home UI — redesign `FuelTile`, point `RangeHero` at the ledger

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreen.kt`

UI — verified by compile + on-device visual check (no Compose unit test).

- [ ] **Step 1: Import `FuelEstimate` and collect it**

Add import near the other `dev.mrwick.gixxerbridge` imports:

```kotlin
import dev.mrwick.gixxerbridge.analytics.FuelEstimate
```

In `HomeScreen` (the stateful composable), replace the line:

```kotlin
    val rangeKm by vm.rangeKm.collectAsStateWithLifecycle()
```

with:

```kotlin
    val fuelEstimate by vm.fuelEstimate.collectAsStateWithLifecycle()
```

And in the `HomeContent(...)` call, replace `rangeKm = rangeKm,` with `fuelEstimate = fuelEstimate,`.

- [ ] **Step 2: Update `HomeContent` signature + wiring**

In `HomeContent`, replace the parameter `rangeKm: Double?,` with `fuelEstimate: FuelEstimate?,`.

Replace the hero call:

```kotlin
        if (live) {
            RangeHero(rangeKm = rangeKm, fuelBars = telemetry?.fuelBars, index = 0)
        } else {
            ParkedHero(lastParked = lastParked, todayKm = todayKm, index = 0)
        }
```

with (only the `rangeKm` source changes — now the ledger):

```kotlin
        if (live) {
            RangeHero(rangeKm = fuelEstimate?.rangeKm, fuelBars = telemetry?.fuelBars, index = 0)
        } else {
            ParkedHero(lastParked = lastParked, todayKm = todayKm, index = 0)
        }
```

Replace the fuel/odo row:

```kotlin
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FuelTile(telemetry, live, Modifier.weight(1f), index = 1)
            OdoTile(telemetry, Modifier.weight(1f), index = 2)
        }
```

with:

```kotlin
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FuelTile(fuelEstimate, Modifier.weight(1f), index = 1)
            OdoTile(telemetry, Modifier.weight(1f), index = 2)
        }
```

- [ ] **Step 3: Replace the `FuelTile` composable**

Replace the entire existing `FuelTile` function (lines ~203-221) with:

```kotlin
@Composable
private fun FuelTile(estimate: FuelEstimate?, modifier: Modifier, index: Int) {
    BentoTile(modifier.height(178.dp), index = index, container = MaterialTheme.colorScheme.surfaceVariant) {
        Text("FUEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        if (estimate == null) {
            Text(
                "—",
                style = GixxerMono.display.copy(fontSize = 40.sp),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Log a fill to estimate",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%.1f".format(estimate.litresLeft),
                    style = GixxerMono.display.copy(fontSize = 40.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "L",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(estimate.percent * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = GixxerBrand.zoneCool,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Range ≈ ${estimate.rangeKm.toInt()} km",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerBrand.zoneCool,
            )
        }
    }
}
```

This drops the bike's bar count and the "FUEL · LAST SEEN"/economy display, per the design. `OdometerNumber` import may become unused if no longer referenced elsewhere in the file — leave other usages (OdoTile still uses it). Do NOT remove the `OdometerNumber` import unless the compiler flags it unused.

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Task 4 + Task 5 together resolve the `rangeKm` removal).

- [ ] **Step 5: Build, install, and visually verify on device**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Then open the app: the FUEL tile shows `<litres> L · <pct>%` and `Range ≈ <km> km` (or `—` / "Log a fill to estimate" if no fills + no fuel data). No bar count, no "last seen". When connected, the RANGE hero number matches `litres × km/L`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreen.kt
git commit -m "feat(home): fuel tile shows litres-left + range; RANGE hero uses ledger"
```

---

## Self-Review

**Spec coverage:**
- `FuelTankEstimator` (formula, fallbacks, clamp, cold-start, `isRough`) → Task 1 ✓
- Persistence of last-telemetry snapshot (fixes "last seen = 0") → Task 2 (Settings) + Task 3 (service) ✓
- Capacity in Settings (default 12) → Task 2 ✓ (edit UI explicitly deferred)
- Tile shows litres/% + range, no bars, no "last seen", mislabel fixed → Task 5 ✓
- RANGE hero unified onto ledger → Task 5 ✓
- `[0, capacity]` clamp kills below-zero/0-of-6 bug → Task 1 (estimator) ✓
- Tests → Task 1 ✓
- Out-of-scope (partial fills, reserve, forgotten-fill detection, blending) → not implemented ✓

**Placeholder scan:** No TBD/TODO; all steps have concrete code + commands.

**Type consistency:** `FuelEstimate(litresLeft, percent, rangeKm, kmPerLUsed, isRough)` and `FuelTankEstimator.estimate(fills, currentOdometerKm, avgKmPerL, bikeLiveKmPerL, bikeFuelBars, capacityL, fallbackKmPerL)` used identically in Task 1 (impl + test) and Task 4 (VM). `Settings.fuelCapacityL` / `lastTelemetry` / `LastTelemetry(odometerKm, fuelBars, kmPerL, tMillis)` consistent across Tasks 2 → 4. `FuelStore.observe()` matches `FuelFill.kt`.
