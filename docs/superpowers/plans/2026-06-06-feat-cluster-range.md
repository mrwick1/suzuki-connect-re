# Range-remaining on the cluster idle frame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a personalized "~140KM" remaining estimate on the bike's idle cluster frame (a531), derived from the already-built-but-unwired `RangeEstimator` (km/bar from ride history) × the live `fuelBars` reading from a537 telemetry. The number joins the existing idle-frame rotation (clock+weather, now-playing) behind a rider toggle.

**Architecture:** A pure formatter (`ClusterRangeFormatter`) turns a `Double?` range estimate into the short ASCII slot strings the a531 layout can carry (no free-text channel exists — only `distNext` 4 chars, `eta` 6 chars, `distTotal` 4 chars, plus unit bytes). `IdleClockGenerator` gains a `buildRange(...)` builder mirroring the existing `build()` / `buildNowPlaying()` pattern. `BikeBridgeService`'s idle producer already alternates frame types per tick; we add a third rotation slot fed by `RangeEstimator.kmPerBar(observeRides()) × latest fuelBars`, gated on a new `Settings.rangeOnCluster` toggle. All numeric/format logic is pure JVM and unit-tested; the cluster rendering itself is verified only on the physical bike.

**Tech Stack:** Kotlin, Jetpack Compose (settings toggle only), DataStore Preferences (`Settings`), Kotlin coroutines `Flow`, JUnit4 (`org.junit`), Gradle.

**Grounding (verified from code, 2026-06-06):**
- `RangeEstimator.kmPerBar(List<RideEntity>): Double?` and `estimateRemainingKm(currentFuelBars: Int?, kmPerBar: Double?): Double?` already exist and are tested (`RangeEstimatorTest.kt`, 9 tests). `FALLBACK_KM_PER_BAR = 50.0` is flagged ASSUMED. Comment confirms "Not currently wired into the UI."
- `DashboardViewModel.kt:31` already wires `store.observeRides().map { RangeEstimator.kmPerBar(it) }` into a `StateFlow<Double?>` — reuse this exact pattern in the service.
- `IdleClockGenerator` (nav/IdleClockGenerator.kt) builds a531 `NavFrame`s. `build(weatherCode, temp)` and `buildNowPlaying(title)` are the two existing builders; both repurpose `distNext`/`eta`/`distTotal` as text positions. Both carry an explicit ASSUMED caveat that the cluster renders non-Mappls text in those positions — proven for printable ASCII via `tools/forge_display.py` but the specific per-builder layout is "NOT proven on the bike."
- `NavFrame` (protocol/NavFrame.kt) slots: `distNext` (bytes 4-7, 4 ASCII), `distNextUnit` (byte 8), `eta` (bytes 9-14, 6 ASCII), `distTotal` (bytes 18-21, 4 ASCII), `distTotalUnit` (byte 22). `writeAsciiLeftPadZero` left-pads/truncates each. `status='1'` required for the bike to honour the frame.
- `BikeBridgeService` onCreate has an `idleProducer = flow { ... }` (lines ~187-212) that ticks at 1 Hz, reads `settings.idleClockEnabled.first()` per tick, and alternates clock+weather vs now-playing via `tick % (CYCLE_SECONDS*2)` where `CYCLE_SECONDS=5`. `settings` (field, line 119), `weatherCache`, `nowPlayingProvider`, `idleClock` are all in scope. A `setLastTelemetry` collector already reads `TelemetryRepository.latest` for `fuelBars`/odo.
- `TelemetryFrame.fuelBars: Int?` (a537 byte 24), `odometerKm: Int`. `TelemetryRepository.latest: StateFlow<TelemetryFrame?>`.
- `Settings` toggles follow `val idleClockEnabled = ds.data.map { it[Keys.IDLE_CLOCK_ENABLED] ?: true }` + a `Keys.X = booleanPreferencesKey(...)` + a `suspend fun setX(...)`. DataStore only — no Room.
- `GixxerDatabase` uses `fallbackToDestructiveMigration` → **no new Room columns** (would wipe ride history). This feature needs none: ride history (`RideEntity.fuelBarsStart/End`) and `fuelBars` already exist; the toggle lives in DataStore.

**Deferred / out of scope:** A "range" tile on a phone screen (DashboardScreen already shows a range readout at line 130 — this plan is cluster-only); choosing a non-anecdotal `FALLBACK_KM_PER_BAR`; reserve-bar handling; blending bike-live km/L with km/bar.

---

### Task 1: `ClusterRangeFormatter` pure formatter + tests

Turns a `Double?` km estimate into the short ASCII strings a531 slots can carry, plus a "rough/no-data" sentinel. Pure JVM, no Android, no `NavFrame` — so it is trivially unit-testable and the (untestable-off-bike) frame assembly stays a thin adapter in Task 2.

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ClusterRangeFormatter.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/ClusterRangeFormatterTest.kt`

- [ ] **Step 1: Write the failing test**

Create `ClusterRangeFormatterTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [ClusterRangeFormatter]. No Android, no NavFrame. */
class ClusterRangeFormatterTest {

    @Test fun `formats a normal range into 4-char km slot`() {
        val r = ClusterRangeFormatter.format(140.0)
        // distNext carries the number (right-aligned, <=4 chars), unit 'K'.
        assertEquals("140", r.kmText)
        assertEquals("K", r.kmUnit)
        assertEquals(false, r.isUnavailable)
    }

    @Test fun `rounds to nearest km`() {
        assertEquals("141", ClusterRangeFormatter.format(140.6).kmText)
        assertEquals("140", ClusterRangeFormatter.format(140.4).kmText)
    }

    @Test fun `clamps a 4-plus-digit range to the 4-char slot`() {
        // 12345 km can't fit a 4-char slot; cap at 9999 rather than truncate-garbage.
        assertEquals("9999", ClusterRangeFormatter.format(12_345.0).kmText)
    }

    @Test fun `zero and negative collapse to zero`() {
        assertEquals("0", ClusterRangeFormatter.format(0.0).kmText)
        assertEquals("0", ClusterRangeFormatter.format(-5.0).kmText)
    }

    @Test fun `null range is unavailable`() {
        val r = ClusterRangeFormatter.format(null)
        assertEquals(true, r.isUnavailable)
        assertEquals("----", r.kmText)
    }

    @Test fun `label is the fixed RANGE marker`() {
        assertEquals("RANGE", ClusterRangeFormatter.LABEL)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.nav.ClusterRangeFormatterTest"`
Expected: FAIL — compilation error, `ClusterRangeFormatter` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `ClusterRangeFormatter.kt`:

```kotlin
package dev.mrwick.gixxerbridge.nav

import kotlin.math.roundToInt

/**
 * Pre-rendered range strings for the a531 cluster slots. The cluster has no
 * free-text channel — only short numeric/text slots ([NavFrame.distNext] 4
 * chars, [NavFrame.eta] 6 chars, [NavFrame.distTotal] 4 chars). This holder
 * is what [IdleClockGenerator.buildRange] writes into those slots.
 */
data class ClusterRange(
    val kmText: String,   // fits distNext (<=4 chars)
    val kmUnit: String,   // distNextUnit, e.g. "K"
    val isUnavailable: Boolean,
)

/**
 * Formats an estimated km-remaining [Double] into the short ASCII the a531
 * layout can carry. Pure JVM, deterministic — tested in
 * ClusterRangeFormatterTest. The actual on-cluster rendering of these strings
 * is UNVERIFIED on the bike (see plan caveats).
 */
object ClusterRangeFormatter {

    /** Fixed 6-char-or-less marker shown in the eta slot. */
    const val LABEL: String = "RANGE"

    private const val MAX_KM = 9999 // 4-char distNext ceiling

    fun format(rangeKm: Double?): ClusterRange {
        if (rangeKm == null || rangeKm.isNaN()) {
            return ClusterRange(kmText = "----", kmUnit = "K", isUnavailable = true)
        }
        val km = rangeKm.roundToInt().coerceIn(0, MAX_KM)
        return ClusterRange(kmText = km.toString(), kmUnit = "K", isUnavailable = false)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.nav.ClusterRangeFormatterTest"`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ClusterRangeFormatter.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/ClusterRangeFormatterTest.kt
git commit -m "feat(nav): pure ClusterRangeFormatter for a531 range slots + tests"
```

---

### Task 2: `IdleClockGenerator.buildRange(...)` builder + tests

Adds a third a531 builder mirroring the existing `build()` / `buildNowPlaying()`. Pure (deterministic frame assembly), so it IS unit-testable on the JVM via `NavFrame` field assertions — the only thing that stays unverifiable off-bike is whether the cluster *renders* it.

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGenerator.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGeneratorRangeTest.kt`

- [ ] **Step 1: Write the failing test**

Create `IdleClockGeneratorRangeTest.kt` (asserts the chosen slot layout — this pins the design):

```kotlin
package dev.mrwick.gixxerbridge.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [IdleClockGenerator.buildRange]. No Android. */
class IdleClockGeneratorRangeTest {

    private val gen = IdleClockGenerator()

    @Test fun `range frame puts km in distNext and RANGE in eta`() {
        val f = gen.buildRange(140.0)
        assertEquals("RANGE", f.eta)
        assertEquals("140", f.distNext)
        assertEquals("K", f.distNextUnit)
        assertEquals("1", f.status)         // bike honours frame only at status '1'
        assertEquals(ManeuverMap.NO_MANEUVER_BYTE, f.maneuverId) // no turn arrow
    }

    @Test fun `null range frame shows the dash sentinel`() {
        val f = gen.buildRange(null)
        assertEquals("RANGE", f.eta)
        assertEquals("----", f.distNext)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.nav.IdleClockGeneratorRangeTest"`
Expected: FAIL — `buildRange` unresolved.

- [ ] **Step 3: Add the builder**

In `IdleClockGenerator.kt`, after `buildNowPlaying(...)` (before the closing brace of the class), add:

```kotlin
    /**
     * Build an a531 frame that surfaces estimated range remaining on the
     * cluster's text positions.
     *
     * Layout (one a531 frame):
     *   - maneuverId    = [ManeuverMap.NO_MANEUVER_BYTE] (no turn arrow)
     *   - eta           = "RANGE" (fixed marker, fits the 6-char eta slot)
     *   - distNext      = km number, right-aligned in 4 chars (e.g. "140"), or
     *                     "----" when no estimate is available
     *   - distNextUnit  = "K"
     *   - distTotal     = "0000" / distTotalUnit = " " (unused here)
     *   - status / continueFlag = "1" / "1"
     *
     * ASSUMED (UNVERIFIED on bike): the cluster renders the "RANGE" marker in
     * the eta slot and a bare number+'K' in the distNext slot as legible text.
     * Same creative-text-positions assumption as [build] / [buildNowPlaying];
     * the specific "RANGE / NNNN K" layout has NOT been confirmed on the
     * cluster. Revisit after first on-bike trial.
     */
    fun buildRange(rangeKm: Double?): NavFrame {
        val r = ClusterRangeFormatter.format(rangeKm)
        return NavFrame(
            maneuverId = ManeuverMap.NO_MANEUVER_BYTE,
            distNext = r.kmText,
            distNextUnit = r.kmUnit,
            eta = ClusterRangeFormatter.LABEL,
            distTotal = "0000",
            distTotalUnit = " ",
            status = "1",
            continueFlag = "1",
        )
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.nav.IdleClockGeneratorRangeTest"`
Expected: PASS (2 tests). (`distNext`'s 4-char left-pad/truncate happens at `NavFrame.encode()` time, not in the `NavFrame` data fields, so asserting `"140"` here is correct.)

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGenerator.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGeneratorRangeTest.kt
git commit -m "feat(nav): IdleClockGenerator.buildRange a531 builder + tests"
```

---

### Task 3: `Settings.rangeOnCluster` toggle

DataStore-backed boolean, mirroring `idleClockEnabled` / `nowPlayingOnCluster`. No Room, no migration.

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/Settings.kt`

No unit test: DataStore-backed (Android), consistent with the existing un-tested toggles. Verified by compile + the on-device toggle.

- [ ] **Step 1: Add the read flow**

After the `nowPlayingOnCluster` flow (~line 61) add:

```kotlin
    /** When true, estimated range-remaining joins the cluster idle rotation. Off by default. */
    val rangeOnCluster: Flow<Boolean> =
        ds.data.map { it[Keys.RANGE_ON_CLUSTER] ?: false }
```

- [ ] **Step 2: Add the setter**

Near the other toggle setters (e.g. after the `nowPlayingOnCluster` setter; grep for `NOW_PLAYING_ON_CLUSTER` to locate it), add:

```kotlin
    /** Toggle range-remaining on the cluster idle rotation. */
    suspend fun setRangeOnCluster(enabled: Boolean) {
        ds.edit { it[Keys.RANGE_ON_CLUSTER] = enabled }
    }
```

- [ ] **Step 3: Add the preference key**

In the `Keys` object, next to `NOW_PLAYING_ON_CLUSTER`:

```kotlin
        val RANGE_ON_CLUSTER = booleanPreferencesKey("range_on_cluster")
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/data/Settings.kt
git commit -m "feat(settings): rangeOnCluster toggle (DataStore, off by default)"
```

---

### Task 4: Wire range into `BikeBridgeService` idle producer

Add a third rotation slot to the existing `idleProducer` flow, fed by `RangeEstimator.kmPerBar(observeRides()) × latest fuelBars`, gated on `settings.rangeOnCluster`. Reuses the `DashboardViewModel.kt:31` pattern.

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ble/BikeBridgeService.kt`

No unit test (Android service). The pure logic it calls (`RangeEstimator`, `ClusterRangeFormatter`, `buildRange`) is already covered by Tasks 1-2 + the existing `RangeEstimatorTest`. Verified by compile + on-device.

- [ ] **Step 1: Add imports**

Near the other `dev.mrwick.gixxerbridge` imports:

```kotlin
import dev.mrwick.gixxerbridge.analytics.RangeEstimator
```

(`RideStore`, `GixxerDatabase`, `TelemetryRepository` are already imported. `IdleClockGenerator` is already imported.)

- [ ] **Step 2: Add a km/bar StateFlow before the idle producer**

Just above the `val idleProducer = ...` declaration (~line 187), add a cheap derived flow off the ride table — same as `DashboardViewModel`:

```kotlin
        // km-per-fuel-bar derived from this rider's history (median over rides
        // with both fuelBarsStart/End). Null until enough history; service falls
        // back to RangeEstimator.FALLBACK_KM_PER_BAR (ASSUMED ~50, flagged).
        val rideStoreForRange = RideStore(GixxerDatabase.get(applicationContext).rideDao())
        val kmPerBarFlow = rideStoreForRange.observeRides()
            .map { RangeEstimator.kmPerBar(it) }
            .stateIn(lifecycleScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
```

Confirm `import kotlinx.coroutines.flow.map` and `import kotlinx.coroutines.flow.stateIn` are present; add them if the compiler flags them missing. (`map` is commonly already imported via the `distinctUntilChanged` neighbours — verify.)

- [ ] **Step 3: Extend the rotation in the idle producer**

The current producer alternates two states via `tick % (CYCLE_SECONDS * 2)`. Replace that two-way branch with a three-way rotation that includes range when enabled. Inside the `flow { ... while(true) { ... } }`, after the `idleClockEnabled` early-continue guard, replace the block:

```kotlin
                val track = nowPlayingProvider.track.value
                val wantNowPlaying = settings.nowPlayingOnCluster.first() && track != null
                val showNowPlaying = wantNowPlaying && (tick % (CYCLE_SECONDS * 2)) >= CYCLE_SECONDS
                val (wcode, tbyte) = weatherCache.currentEncoded()
                val frame = if (showNowPlaying) {
                    idleClock.buildNowPlaying(track!!.forCluster())
                } else {
                    idleClock.build(wcode, tempFromByte(tbyte))
                }
                emit(frame)
```

with:

```kotlin
                val track = nowPlayingProvider.track.value
                val wantNowPlaying = settings.nowPlayingOnCluster.first() && track != null
                val wantRange = settings.rangeOnCluster.first()

                // Rotation order, each CYCLE_SECONDS long: clock -> [now-playing] ->
                // [range]. Optional slots collapse out when their toggle is off /
                // no data, so the clock is always shown.
                val slots = buildList {
                    add(IdleSlot.CLOCK)
                    if (wantNowPlaying) add(IdleSlot.NOW_PLAYING)
                    if (wantRange) add(IdleSlot.RANGE)
                }
                val slot = slots[(tick / CYCLE_SECONDS) % slots.size]
                val (wcode, tbyte) = weatherCache.currentEncoded()
                val frame = when (slot) {
                    IdleSlot.NOW_PLAYING -> idleClock.buildNowPlaying(track!!.forCluster())
                    IdleSlot.RANGE -> {
                        val bars = TelemetryRepository.latest.value?.fuelBars
                        val kmPerBar = kmPerBarFlow.value ?: RangeEstimator.FALLBACK_KM_PER_BAR
                        idleClock.buildRange(RangeEstimator.estimateRemainingKm(bars, kmPerBar))
                    }
                    IdleSlot.CLOCK -> idleClock.build(wcode, tempFromByte(tbyte))
                }
                emit(frame)
```

Add a private enum at the top level of the file (or as a private nested enum):

```kotlin
private enum class IdleSlot { CLOCK, NOW_PLAYING, RANGE }
```

Note: this refactor preserves the existing clock+now-playing behaviour exactly when `rangeOnCluster` is off (slots = [CLOCK, NOW_PLAYING], `(tick/CYCLE_SECONDS) % 2` alternates identically). When no fuelBars/history exist, `buildRange` emits the "----" sentinel rather than a wrong number.

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full unit suite (no regressions in nav/analytics)**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS, including the new `ClusterRangeFormatterTest` + `IdleClockGeneratorRangeTest` and the existing `RangeEstimatorTest`.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ble/BikeBridgeService.kt
git commit -m "feat(ble): range-remaining joins the cluster idle rotation (gated)"
```

---

### Task 5: Developer-settings toggle + on-bike verification

Expose `rangeOnCluster` in `DeveloperSettingsScreen` next to the existing cluster toggles, then verify on the physical bike (the only oracle for whether the cluster renders this layout).

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/settings/DeveloperSettingsScreen.kt`

UI — verified by compile + on-device. No Compose unit test (consistent with project).

- [ ] **Step 1: Add the toggle row**

Locate the existing cluster toggles (grep `nowPlayingOnCluster` / `idleClockEnabled` in `DeveloperSettingsScreen.kt` and any backing VM). Mirror that exact wiring — `collectAsStateWithLifecycle()` on `settings.rangeOnCluster`, a `Switch` row labelled e.g. "Range on cluster (idle)", `onCheckedChange` calling `setRangeOnCluster(...)` in a coroutine scope (or via the screen's existing VM/settings handle — match whatever the neighbouring toggles use; do not invent a new pattern).

Include a short caption noting it is experimental / unverified on the cluster, consistent with the developer-settings tone.

- [ ] **Step 2: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build + install**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 4: On-bike verification (PHYSICAL BIKE REQUIRED — cannot be done locally)**

This is the **only** acceptable verification that the cluster renders the range layout. With the bike on and paired:
1. Enable "Range on cluster" in Developer Settings.
2. Ensure the bike is parked/idle (no active nav) so the idle producer is driving a531.
3. Watch the cluster through one full rotation (`CYCLE_SECONDS=5` per slot). Confirm a "RANGE" + number+"K" frame appears alongside clock+weather (and now-playing if enabled).
4. Record on `DISCOVERIES.md`: whether the cluster renders "RANGE" in the eta position and the number in the distNext position as legible text, or whether the layout needs adjustment (e.g. number moved to a different slot, marker shortened to 4 chars). Cross-check the actual TX bytes in the in-app Inspector / FrameStream against what the cluster shows.

If the cluster does NOT render this layout (e.g. eta slot won't show "RANGE"): STOP, log the observed behaviour in `DISCOVERIES.md`, and treat the slot layout as the open design question (see Task 2 — the layout is asserted in tests, so a layout change is a test+builder edit, not a logic rewrite).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/settings/DeveloperSettingsScreen.kt
git commit -m "feat(settings): developer toggle for range-on-cluster"
```

---

## Open questions (need a human decision)

1. **Slot layout for the cluster.** Chosen: `eta` = "RANGE" marker, `distNext` = number + "K". The eta slot is 6 chars (fits "RANGE"); distNext is 4 chars (fits up to 9999). **Alternative** if the cluster rejects letters in eta: put the number in distNext and drop the marker (rider infers "range" from context), or abuse distNextUnit. This is the single biggest unknown and is UNVERIFIED on-bike (Task 5 Step 4 decides it). The tests pin the *current* choice so a change is localized.
2. **Default on or off?** Plan defaults `rangeOnCluster = false` (experimental, unverified rendering). Decide whether to flip to on-by-default once Task 5 confirms it renders.
3. **Rotation position / cadence.** Range is appended after now-playing in the rotation at `CYCLE_SECONDS=5`/slot. With all three on, a full cycle is 15 s and range shows ~1/3 of the time. Acceptable, or should range get a longer/shorter dwell, or sit before now-playing?
4. **Where does the toggle live?** Plan puts it in `DeveloperSettingsScreen` next to the other cluster toggles (it is experimental). If this graduates to a user-facing feature, it should move to the main cluster-settings section — needs a decision.
5. **Fallback km/bar honesty.** When there is no ride history, the cluster shows a number derived from `FALLBACK_KM_PER_BAR=50.0` (flagged ASSUMED/anecdotal). Options: (a) show it anyway (current plan), (b) show "----" until real history exists so the cluster never displays an unverified number. Recommend (b) if the rider would mistake the fallback for a measured value — but that is a UX call.

## Feasibility caveats / on-bike-only verification

- **Whether the cluster renders this at all is UNVERIFIED.** The a531 idle-text repurposing is proven only for printable ASCII via `tools/forge_display.py` and the existing clock/now-playing builders carry the same "NOT proven on the bike" caveat in their own docstrings. The specific "RANGE / NNNN K" layout has never been on the cluster. Task 5 Step 4 is the only valid oracle — no local check substitutes for it.
- **Inherits 6-bar quantization.** `fuelBars` is 0-6 (coarse). km/bar × bars yields ~50 km granularity, so "~140KM" jumps in big steps and is soft by design. Present it as approximate (the leading "~"/marker), not a precise figure — consistent with the no-assumptions rule.
- **`FALLBACK_KM_PER_BAR=50.0` is anecdotal/ASSUMED** (Gixxer SF 150, 12 L, ~45 km/L). Any cold-start number leans on it until the rider accumulates rides with start+end fuel bars. See Open Question 5.
- **km/bar needs history with both `fuelBarsStart` and `fuelBarsEnd`.** Rides that began or ended exactly on a bar boundary inflate/deflate the per-ride sample; `RangeEstimator` already uses the median to blunt this, but a new rider sees only the fallback.
- **Idle slots are already shared.** Range joins clock+weather+now-playing in a timed rotation; it does not get a permanent surface. If the rider wants range always-visible, a different design (displacing weather permanently) is needed — out of scope here.

## Room schema migration

**None required, and none should be added.** `GixxerDatabase` uses `fallbackToDestructiveMigration`, so any new Room column would wipe ride/fuel/service history. This feature needs no new persisted columns: it reads existing `RideEntity.fuelBarsStart/End` (km/bar history) and live `TelemetryFrame.fuelBars`, and the only new persisted state — the `rangeOnCluster` toggle — lives in **DataStore** (`Settings`), not Room. Recompute-on-read is used for km/bar (derived per ride-table emission, never stored).

## Self-Review

**Spec/feature coverage:**
- Pure formatter for a531 range slots (format, clamp, round, null/unavailable) → Task 1 ✓ (JVM unit-tested)
- a531 `buildRange` builder mirroring existing builders → Task 2 ✓ (JVM unit-tested)
- Toggle in DataStore (no Room) → Task 3 ✓
- Wire `RangeEstimator.kmPerBar(observeRides()) × live fuelBars` into the idle rotation, gated → Task 4 ✓ (reuses verified `DashboardViewModel` pattern)
- Developer toggle + on-bike verification step → Task 5 ✓
- Range hero/phone-side range readout → explicitly out of scope (already exists in DashboardScreen) ✓

**No-assumptions compliance:** Every unverified claim (cluster renders the layout; fallback km/bar value; bar quantization softness) is flagged in caveats and in code docstrings, and the on-bike step is the named oracle. No unverified fact is stated as measured.

**Pure logic is unit-tested:** `ClusterRangeFormatter` (Task 1) and `IdleClockGenerator.buildRange` (Task 2) both have JUnit4 tests run before implementation. UI/service paths are compile + on-device only, consistent with the existing plan.

**Placeholder scan:** No TBD/TODO in steps; every step has concrete code or an explicit "match the neighbouring pattern" instruction (Task 5 Step 1, where the exact toggle wiring must mirror existing rows rather than be guessed).

**Type consistency:** `ClusterRange(kmText, kmUnit, isUnavailable)` and `ClusterRangeFormatter.format(Double?)` / `.LABEL` used identically in Tasks 1-2. `IdleClockGenerator.buildRange(Double?): NavFrame` used identically in Task 2 (impl+test) and Task 4 (service). `RangeEstimator.kmPerBar` / `.estimateRemainingKm` / `.FALLBACK_KM_PER_BAR` signatures match the verified source. `Settings.rangeOnCluster: Flow<Boolean>` / `setRangeOnCluster` consistent across Tasks 3-5.
