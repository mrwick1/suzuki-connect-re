# Predicted refuel date + "fill before service" alert — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Translate the coarse fuel estimate into a human "refuel in ~2 days" coarse-bucket prediction, and — the genuinely high-value half — bundle it with an overdue/soon km-gated service into a single "fill before service" co-prompt on the Home screen. Both are derived, recompute-on-read, no new persisted data.

**Architecture:** A new pure object `RefuelPredictor` takes (a) the already-built `FuelEstimate.rangeKm` and (b) a daily-km pace, and returns a coarse "days-until-refuel" *bucket* (Today / ~1 day / ~2-3 days / This week / 1+ week / unknown) — never a precise day, honouring the 6-bar quantization + anecdotal-fallback caveats. A second pure function `RefuelPredictor.fillBeforeService(...)` takes the refuel-range and the worst service item's `kmRemaining` and decides whether a refuel and a service fall in the same near-term window, producing a co-prompt string. The Home VM feeds it `FuelEstimate` (existing flow), a daily-km pace from `RideAnalytics.totalsFor`, and the worst `ServiceItemHealth.kmRemaining` (already computed for `nextServiceDue`). The Home screen renders a single new line/sub-section.

**Tech Stack:** Kotlin, Jetpack Compose, Room (read-only here), DataStore (`Settings`, read-only here), JUnit4 (`org.junit`), Gradle. No Room schema change.

**Why the high-value half is the co-prompt:** the refuel-day number inherits 6-bar quantization (~50 km/bar) and an unverified ~45 km/L fallback, so it is intentionally shown as a coarse bucket. The fill-before-service co-prompt rides on `ServiceSchedule.kmRemaining`, which is exact odometer-gated arithmetic — that is the reliable, actionable nudge ("you're refuelling anyway, the oil service is also due — do both this trip").

**Grounding (verified file:line, no assumptions):**
- `FuelEstimate(litresLeft, percent, rangeKm, kmPerLUsed, isRough)` and `FuelTankEstimator.estimate(...)` exist — `analytics/FuelTankEstimator.kt:11-17,39`. `isRough=true` flags the pre-first-fill bars bootstrap.
- `HomeViewModel.fuelEstimate: StateFlow<FuelEstimate?>` already computed — `ui/home/HomeViewModel.kt:71-88`.
- `HomeViewModel.nextServiceDue` already computes the worst `ServiceItemHealth` via `ServiceSchedule.mostOverdue(...)` — `ui/home/HomeViewModel.kt:129-156`; `ServiceItemHealth.kmRemaining` is `km - used`, negative when overdue, null when no km gate / no baseline / unknown odo — `analytics/ServiceSchedule.kt:72-78`.
- Daily-km pace source: `RideAnalytics.totalsFor(rides, days, now).km` — `analytics/RideAnalytics.kt:28-44`. Pace = `totalsFor(rides, 30).km / 30.0` (rolling 30-day km ÷ 30).
- Settings exposes `serviceSchedule: Flow<Map<ServiceItem, ServiceItemState>>` — `data/Settings.kt:93`.
- Home screen card structure (`HomeContent`, `HealthTile`) — `ui/home/HomeScreen.kt:92-134,268-300`. Design system: `BentoTile`, `GixxerMono`, `GixxerBrand`, `MaterialTheme` already imported there.
- Test conventions: pure analytics tested with `org.junit` (`assertEquals`/`assertNull`/`assertTrue`), package `dev.mrwick.gixxerbridge.analytics`, hand-crafted inputs — see `app/src/test/.../ServiceScheduleTest.kt`, `RangeEstimatorTest.kt`.

**Spec:** none yet — this plan is the design of record. (If a spec is later wanted, mirror `docs/superpowers/specs/2026-06-06-fuel-tank-estimate-design.md`.)

**Deferred / out of scope:** push notifications / a dismissible banner; calendar-date arithmetic ("refuel on Tuesday 9th"); blending km/bar with fill-measured km/L (the research explicitly warns these aren't independent — we use only `FuelEstimate.rangeKm`, which already prefers fill-measured km/L); persisting a "snoozed" state; the cluster (a531 can't render this string — see Risks).

---

## OPEN QUESTIONS — human must decide before/while building (UI/UX)

These are **placement/UX decisions** the implementer should NOT guess. Default proposals are given so the build can proceed, but flag each for Arjun:

1. **Where does the co-prompt live?** Default proposal: a single new line appended inside the existing `HealthTile` on Home (it already shows the worst service item, so the "fill before service" bundle is contextually adjacent and needs no new tile). Alternative: a dedicated thin `BentoTile` between `HealthTile` and `TodayStrip`. **Decision needed: extend HealthTile vs new tile.**
2. **Refuel-bucket prominence.** Default: show the refuel bucket as small secondary text on the `FuelTile` ("Refuel in ~2-3 days") under the existing litres-left line. Alternative: only surface refuel timing when it co-fires with service (i.e. suppress the standalone refuel-day line entirely, keeping just the co-prompt). **Decision needed: always-show refuel bucket vs only-on-co-prompt.**
3. **What counts as "service is in the same window"?** Default: service `kmRemaining <= refuelRangeKm` OR service already overdue (`kmRemaining < 0`). This means "you'll likely hit the service gate before or around your next fill." **Decision needed: confirm the threshold (range-based) vs a fixed km horizon (e.g. within 500 km).**
4. **Which service items qualify?** Default: only the single worst km-gated item (matches `nextServiceDue`). Brake oil has no km gate (`defaultKm = null`) so it never co-prompts — that's intended. **Decision needed: worst-only vs all-overdue items.**
5. **Tone/wording** of the co-prompt string. Default: `"Refuel soon — oil service is due too. Do both this trip."` **Decision needed: confirm copy.**

Do not implement the UI tasks (3–4) until questions 1–2 (and ideally 5) are answered. Tasks 1–2 (pure logic + VM) are decision-independent and can proceed immediately.

---

### Task 1: `RefuelPredictor` pure object + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RefuelPredictor.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RefuelPredictorTest.kt`

- [ ] **Step 1: Write the failing test**

Create `RefuelPredictorTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [RefuelPredictor]. No Room, no Android, no DataStore. */
class RefuelPredictorTest {

    // ---- daysUntilRefuel: maps rangeKm + pace into a coarse bucket ----

    @Test fun noPaceIsUnknown() {
        // No recent rides -> divide-by-zero guard -> UNKNOWN, never a number.
        assertEquals(RefuelBucket.UNKNOWN, RefuelPredictor.daysUntilRefuel(rangeKm = 120.0, kmPerDay = 0.0))
        assertEquals(RefuelBucket.UNKNOWN, RefuelPredictor.daysUntilRefuel(rangeKm = 120.0, kmPerDay = -3.0))
    }

    @Test fun nullRangeIsUnknown() {
        assertEquals(RefuelBucket.UNKNOWN, RefuelPredictor.daysUntilRefuel(rangeKm = null, kmPerDay = 30.0))
    }

    @Test fun emptyTankIsToday() {
        // ~0 km of range -> refuel today regardless of pace.
        assertEquals(RefuelBucket.TODAY, RefuelPredictor.daysUntilRefuel(rangeKm = 5.0, kmPerDay = 30.0))
    }

    @Test fun aboutOneDay() {
        // 30 km range at 30 km/day = 1.0 day.
        assertEquals(RefuelBucket.ONE_DAY, RefuelPredictor.daysUntilRefuel(rangeKm = 30.0, kmPerDay = 30.0))
    }

    @Test fun twoToThreeDays() {
        // 75 km range at 30 km/day = 2.5 days.
        assertEquals(RefuelBucket.TWO_TO_THREE_DAYS, RefuelPredictor.daysUntilRefuel(rangeKm = 75.0, kmPerDay = 30.0))
    }

    @Test fun thisWeek() {
        // 150 km at 30 km/day = 5 days.
        assertEquals(RefuelBucket.THIS_WEEK, RefuelPredictor.daysUntilRefuel(rangeKm = 150.0, kmPerDay = 30.0))
    }

    @Test fun overAWeek() {
        // 300 km at 30 km/day = 10 days.
        assertEquals(RefuelBucket.OVER_A_WEEK, RefuelPredictor.daysUntilRefuel(rangeKm = 300.0, kmPerDay = 30.0))
    }

    @Test fun bucketLabelsAreCoarseNotExact() {
        // Never a precise day count in the label.
        assertEquals("today", RefuelBucket.TODAY.label)
        assertEquals("~1 day", RefuelBucket.ONE_DAY.label)
        assertEquals("~2-3 days", RefuelBucket.TWO_TO_THREE_DAYS.label)
        assertEquals("this week", RefuelBucket.THIS_WEEK.label)
        assertEquals("over a week", RefuelBucket.OVER_A_WEEK.label)
    }

    // ---- fillBeforeService: the high-value co-prompt ----

    @Test fun coPromptWhenServiceWithinRefuelRange() {
        // Service due in 80 km, refuel range 120 km => you'll hit the service gate
        // before your next likely fill => bundle them.
        val r = RefuelPredictor.fillBeforeService(refuelRangeKm = 120.0, serviceKmRemaining = 80)
        assertTrue(r.shouldBundle)
    }

    @Test fun coPromptWhenServiceOverdue() {
        // Service already overdue (negative) => always bundle, any range.
        val r = RefuelPredictor.fillBeforeService(refuelRangeKm = 200.0, serviceKmRemaining = -50)
        assertTrue(r.shouldBundle)
    }

    @Test fun noCoPromptWhenServiceFarOff() {
        // Service due in 2000 km, refuel range only 120 km => unrelated trips.
        val r = RefuelPredictor.fillBeforeService(refuelRangeKm = 120.0, serviceKmRemaining = 2000)
        assertFalse(r.shouldBundle)
    }

    @Test fun noCoPromptWhenNoServiceGate() {
        // Null kmRemaining (brake-oil days-only item, or no baseline) => no bundle.
        val r = RefuelPredictor.fillBeforeService(refuelRangeKm = 120.0, serviceKmRemaining = null)
        assertFalse(r.shouldBundle)
    }

    @Test fun noCoPromptWhenRangeUnknown() {
        val r = RefuelPredictor.fillBeforeService(refuelRangeKm = null, serviceKmRemaining = 80)
        assertFalse(r.shouldBundle)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/mrwick/coding/projects/suzuki-connect-re/android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RefuelPredictorTest"`
Expected: FAIL — compilation error, `RefuelPredictor` / `RefuelBucket` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `RefuelPredictor.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

/**
 * Coarse "when will I need to refuel" bucket. Deliberately NOT a precise day
 * count: the underlying range inherits the bike's 6-bar fuel quantization
 * (~50 km/bar) and, before two fills exist, an unverified ~45 km/L fallback
 * (see [FuelTankEstimator.DEFAULT_FALLBACK_KM_PER_L]). Showing "~2-3 days"
 * honours that uncertainty; showing "in 2.4 days" would lie about precision.
 */
enum class RefuelBucket(val label: String) {
    TODAY("today"),
    ONE_DAY("~1 day"),
    TWO_TO_THREE_DAYS("~2-3 days"),
    THIS_WEEK("this week"),
    OVER_A_WEEK("over a week"),
    UNKNOWN("unknown"),
}

/** Result of [RefuelPredictor.fillBeforeService]. */
data class FillBeforeService(
    /** True when a refuel and a km-gated service fall in the same near window. */
    val shouldBundle: Boolean,
)

/**
 * Turns the existing fuel range estimate + a daily-km pace into a coarse refuel
 * bucket, and decides whether to bundle the refuel with an upcoming/overdue
 * service.
 *
 * Pure JVM, deterministic, side-effect free — tested in RefuelPredictorTest.
 * Reads no Room/DataStore; the caller (HomeViewModel) supplies range, pace, and
 * the service km-remaining (all already computed elsewhere).
 */
object RefuelPredictor {

    /**
     * Map [rangeKm] (estimated km until empty) and [kmPerDay] (rider's recent
     * daily pace) into a coarse [RefuelBucket].
     *
     * Guards (no-assumptions rule): a null range or non-positive pace yields
     * [RefuelBucket.UNKNOWN] — no recent rides means no honest prediction, and
     * we never divide by zero. We never emit a numeric day count.
     */
    fun daysUntilRefuel(rangeKm: Double?, kmPerDay: Double): RefuelBucket {
        if (rangeKm == null || rangeKm.isNaN()) return RefuelBucket.UNKNOWN
        if (kmPerDay <= 0.0 || kmPerDay.isNaN()) return RefuelBucket.UNKNOWN
        val days = rangeKm / kmPerDay
        return when {
            days < 0.5 -> RefuelBucket.TODAY
            days < 1.5 -> RefuelBucket.ONE_DAY
            days < 3.5 -> RefuelBucket.TWO_TO_THREE_DAYS
            days < 7.5 -> RefuelBucket.THIS_WEEK
            else -> RefuelBucket.OVER_A_WEEK
        }
    }

    /**
     * Decide whether to co-prompt "fill before service". Bundles when a service
     * is overdue ([serviceKmRemaining] < 0) or its remaining km is within the
     * tank's current [refuelRangeKm] — i.e. the rider will likely hit the
     * service gate before or around the next fill, so both errands fit one trip.
     *
     * This half rides on the EXACT odometer-gated [serviceKmRemaining] (see
     * [ServiceSchedule.healthFor]); only the range side is soft. A null range or
     * null km-remaining (days-only item / no baseline / unknown odo) → no bundle.
     */
    fun fillBeforeService(refuelRangeKm: Double?, serviceKmRemaining: Int?): FillBeforeService {
        if (refuelRangeKm == null || refuelRangeKm.isNaN()) return FillBeforeService(false)
        val km = serviceKmRemaining ?: return FillBeforeService(false)
        val bundle = km < 0 || km <= refuelRangeKm
        return FillBeforeService(shouldBundle = bundle)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /home/mrwick/coding/projects/suzuki-connect-re/android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RefuelPredictorTest"`
Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RefuelPredictor.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RefuelPredictorTest.kt
git commit -m "feat(analytics): RefuelPredictor coarse refuel bucket + fill-before-service"
```

---

### Task 2: `HomeViewModel` — expose `refuelPrompt`

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt`

No new unit test for the VM itself (it is an `AndroidViewModel` with no existing VM test); the logic it wires is fully covered by Task 1. Verified by compile + on-device. The VM only assembles already-tested pieces.

- [ ] **Step 1: Add a small UI model**

Below the existing `NextServiceSummary` data class (~line 37), add:

```kotlin
/**
 * Refuel-timing + fill-before-service co-prompt for the Home screen.
 *
 * [refuelBucketLabel] is a coarse human bucket ("~2-3 days") or null when no
 * honest prediction is possible (no range or no recent rides). [bundleService]
 * is true when a km-gated service is due within the tank's range / already
 * overdue — the high-value "do both this trip" nudge. [serviceLabel] names that
 * item (e.g. "Periodic service (engine oil)") for the co-prompt copy.
 */
data class RefuelPromptUi(
    val refuelBucketLabel: String?,
    val bundleService: Boolean,
    val serviceLabel: String?,
)
```

- [ ] **Step 2: Add the `refuelPrompt` flow**

Add imports at the top (with the other `analytics` imports, ~lines 6-11):

```kotlin
import dev.mrwick.gixxerbridge.analytics.RefuelBucket
import dev.mrwick.gixxerbridge.analytics.RefuelPredictor
```

After the `nextServiceDue` flow (~line 156), add the new flow. It reuses `fuelEstimate` (already a StateFlow), `rideStore.observeRides()` for pace, and recomputes the worst service item (same call `nextServiceDue` makes) to get its exact `kmRemaining` + label:

```kotlin
    /**
     * Refuel timing (coarse bucket) + fill-before-service co-prompt. Combines the
     * existing [fuelEstimate] range with the rider's recent daily-km pace (rolling
     * 30-day km / 30) and the worst km-gated service item's remaining km.
     *
     * The refuel bucket is intentionally coarse (6-bar quantization + anecdotal
     * km/L fallback); the bundle decision rides on the exact odometer-gated
     * service km-remaining. Null bucket when no recent rides (pace 0) or no range.
     */
    val refuelPrompt: StateFlow<RefuelPromptUi?> =
        combine(
            fuelEstimate,
            rideStore.observeRides(),
            settings.serviceSchedule,
            TelemetryRepository.latest,
        ) { estimate, rides, schedule, latest ->
            val rangeKm = estimate?.rangeKm
            // Rolling 30-day km / 30 = average km/day. Zero when no recent rides.
            val kmPerDay = RideAnalytics.totalsFor(rides, days = 30L).km / 30.0
            val bucket = RefuelPredictor.daysUntilRefuel(rangeKm, kmPerDay)

            val odo = latest?.odometerKm
            val worst = ServiceSchedule.mostOverdue(schedule.values, odo).worst
            val bundle = RefuelPredictor
                .fillBeforeService(rangeKm, worst?.kmRemaining)
                .shouldBundle

            // Nothing useful to show: no honest bucket AND no bundle -> null.
            if (bucket == RefuelBucket.UNKNOWN && !bundle) return@combine null
            RefuelPromptUi(
                refuelBucketLabel = if (bucket == RefuelBucket.UNKNOWN) null else bucket.label,
                bundleService = bundle,
                serviceLabel = worst?.state?.item?.label,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

Note: this `combine` has 4 sources — a supported overload (the existing `fuelEstimate` already uses 5). `ServiceSchedule`, `RideAnalytics`, `TelemetryRepository` are already imported in this file.

- [ ] **Step 3: Verify it compiles**

Run: `cd /home/mrwick/coding/projects/suzuki-connect-re/android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (no UI consumer yet; the flow is unused but valid).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeViewModel.kt
git commit -m "feat(home): expose refuelPrompt (coarse bucket + fill-before-service)"
```

---

### Task 3: Home UI — surface the refuel bucket + co-prompt

> **BLOCKED on OPEN QUESTIONS 1, 2, 5.** Default proposal below assumes: (Q1) extend `HealthTile` with the co-prompt line; (Q2) show the refuel bucket as secondary text on `FuelTile`; (Q5) the default copy. Adjust per Arjun's answers before building.

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreen.kt`

UI — verified by compile + on-device visual check (no Compose unit test; consistent with the rest of this screen).

- [ ] **Step 1: Import + collect the flow**

Add import with the other `dev.mrwick.gixxerbridge` imports:

```kotlin
import dev.mrwick.gixxerbridge.ui.home.RefuelPromptUi
```

(Same package, so the import may be unnecessary — if so, skip it; the type is directly referenceable.)

In `HomeScreen` (stateful), after `val fuelEstimate by ...` (~line 71):

```kotlin
    val refuelPrompt by vm.refuelPrompt.collectAsStateWithLifecycle()
```

Add `refuelPrompt = refuelPrompt,` to the `HomeContent(...)` call.

- [ ] **Step 2: Thread it through `HomeContent`**

Add parameter `refuelPrompt: RefuelPromptUi?,` to `HomeContent` (after `fuelEstimate`).

Pass it to the fuel tile and the health tile:
- `FuelTile(fuelEstimate, refuelPrompt, Modifier.weight(1f), index = 1)`
- `HealthTile(nextService, refuelPrompt, onOpenMaintenance, index = 3)`

- [ ] **Step 3: Refuel bucket on `FuelTile` (Q2 default)**

Update `FuelTile` signature to `private fun FuelTile(estimate: FuelEstimate?, refuel: RefuelPromptUi?, modifier: Modifier, index: Int)`. In the non-null `estimate` branch, after the existing `"%.1f L left"` line, add a coarse refuel line (only when a bucket exists):

```kotlin
            refuel?.refuelBucketLabel?.let { bucket ->
                Spacer(Modifier.height(4.dp))
                Text(
                    "Refuel ${bucket}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
```

Keep this within the existing `178.dp` tile height; if it overflows, drop the existing "%.1f L left" line into the same row instead. Verify visually in Step 5.

- [ ] **Step 4: Co-prompt on `HealthTile` (Q1 + Q5 default)**

Update `HealthTile` signature to add `refuel: RefuelPromptUi?` after `nextService`. Inside the `Column`, after the existing due-text `Text(...)`, add the bundle line:

```kotlin
                if (refuel?.bundleService == true) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Refuel soon — service due too. Do both this trip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = GixxerBrand.accent,
                    )
                }
```

`GixxerBrand.accent` is already imported and used in this file. If the bundle line makes the `110.dp` tile clip, bump `HealthTile`'s height to `132.dp` (only when the co-prompt can appear — or unconditionally; confirm visually).

- [ ] **Step 5: Build, install, and visually verify on device**

Run: `cd /home/mrwick/coding/projects/suzuki-connect-re/android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Then open the app and check:
- With fills + recent rides: FUEL tile shows a coarse `Refuel ~N days` line (never a precise number); no line when no recent rides (pace 0) or no range.
- When a km-gated service is within range / overdue: the BIKE HEALTH tile shows the accent co-prompt line. Force this by logging a recent service whose `kmThreshold` minus used km is < current range (e.g. set last-service odo close to current odo on an item with a small remaining gate), or by making one item overdue.
- No clipping; tiles render within their heights.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/HomeScreen.kt
git commit -m "feat(home): refuel-bucket on fuel tile + fill-before-service co-prompt"
```

---

### Task 4: Docs — log the discovery + caveats

**Files:**
- Modify: `DISCOVERIES.md` (append) and/or `NOTES.md` per project convention.

- [ ] **Step 1: Append a dated entry**

Record: the refuel-day prediction is a coarse bucket because it inherits the 6-bar quantization and the unverified ~45 km/L fallback (mark these ASSUMED, not measured); the fill-before-service co-prompt is the reliable half because it rides on exact odometer-gated `kmRemaining`. Note the on-bike-only verification items (see Self-Review).

- [ ] **Step 2: Commit**

```bash
git add DISCOVERIES.md NOTES.md
git commit -m "docs: log refuel-predict caveats (coarse bucket; co-prompt is the solid half)"
```

---

## Self-Review

**Spec coverage:**
- Coarse "refuel in ~N days" bucket from `FuelEstimate.rangeKm` × daily-km pace → Task 1 (`daysUntilRefuel`) + Task 2 (VM pace) ✓
- Fill-before-service co-prompt on exact odometer-gated `kmRemaining` → Task 1 (`fillBeforeService`) + Task 2 (VM) ✓
- Prefer fill-measured km/L over km/bar blend → satisfied by reusing `FuelEstimate.rangeKm` (which already prefers fill-measured avg in `FuelTankEstimator`); no km/bar blending introduced ✓
- Show coarse range, not an exact day → enum buckets, no numeric day count; test `bucketLabelsAreCoarseNotExact` ✓
- Guard no-recent-rides → pace 0 ⇒ `RefuelBucket.UNKNOWN`; test `noPaceIsUnknown` ✓
- Pure analytics JVM-unit-tested (JUnit4, `org.junit`) → Task 1 ✓
- UI verified by build + on-device → Task 3 ✓
- No Room schema change → all read-only over existing flows; no new columns ✓

**No-assumptions compliance:** the ~45 km/L fallback and 6-bar quantization are surfaced as the reason for coarse buckets (comments + docs), not hidden. The co-prompt is explicitly flagged as the reliable half. The refuel bucket never claims false precision.

**Placeholder scan:** no TBD/TODO in code steps; Task 3 is explicitly gated on the OPEN QUESTIONS and says so.

**Type consistency:** `RefuelPredictor.daysUntilRefuel(rangeKm: Double?, kmPerDay: Double): RefuelBucket` and `fillBeforeService(refuelRangeKm: Double?, serviceKmRemaining: Int?): FillBeforeService` used identically in Task 1 (impl + test) and Task 2 (VM). `RefuelPromptUi(refuelBucketLabel, bundleService, serviceLabel)` consistent across Tasks 2 → 3. `ServiceItemHealth.kmRemaining` (Int?) and `.state.item.label` match `ServiceSchedule.kt`. `RideAnalytics.totalsFor(rides, days).km` (Int) matches `RideAnalytics.kt:28-44`.

**On-bike / physical-only verification (cannot be checked at the laptop):**
- Whether the daily-km pace and 6-bar-derived range produce a *sane* refuel bucket for real riding — needs a few days of real rides + a real fill logged.
- Co-prompt firing at the right time relative to a real service gate — needs real odometer advance toward a logged service threshold.
- The ~45 km/L fallback and ~50 km/bar are ASSUMED; only fill data over time can confirm the bucket isn't systematically early/late.
