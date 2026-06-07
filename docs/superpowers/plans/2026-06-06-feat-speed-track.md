# Speed-colored ride track (heat polyline) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Recolor the existing `RideTrackCard` GPS polyline by speed — dim/cool for stop-and-go, bright/hot for fast stretches — so traffic and open road read off one picture, on the Trip Detail screen.

**Architecture:** A pure `SpeedTrack` analytics object does two JVM-testable jobs: (1) a **nearest-time join** of each `RideLocationEntity` to the `RideSampleEntity.speedKmh` whose `tMillis` is closest (both lists are already loaded oldest-first in `TripDetailScreen`); (2) **normalization** of each segment speed to a `0f..1f` fraction over a fixed display ceiling, plus a pure fraction→zone mapping (cool/mid/hot) returning the three brand zone colors as ARGB `Int`s (no Compose import in the analytics layer, so it unit-tests cleanly). `RideTrackCard` then replaces its single `drawPath` with N `drawLine` calls, one per segment, colored by the joined speed; it falls back to the existing flat-color path when samples are empty or counts don't line up.

**Tech Stack:** Kotlin, Jetpack Compose Canvas, Room entities (`RideSampleEntity`, `RideLocationEntity` — read-only), JUnit4 (`org.junit`), Gradle. **No Room schema change** (read-only over existing columns — see "Schema migration" below).

**Research entry:** `docs/superpowers/research/2026-06-06-stats-and-features-research.md` → Top Picks #6 "Speed-colored ride track (heat polyline)" (value 3 / effort 2).

**Schema migration:** **None required, and none allowed.** This feature is pure recompute-on-read over `RideLocationEntity` + `RideSampleEntity`, both of which already exist and are already loaded in the `TripDetailScreen` scope. `GixxerDatabase` uses `fallbackToDestructiveMigration`, so any new Room column would wipe the user's ride history — we deliberately add zero columns.

---

## Open questions (human to decide before/at build) — UI/UX

These are placement/UX calls the implementer should NOT silently make. None block writing the pure-logic tasks (1–2); they only affect the UI task (3+).

1. **Speed ceiling for the color ramp.** The plan defaults the "hot" end to **80 km/h** (the existing speed TraceChart on this same screen clamps to 120; 80 better spreads city-riding contrast for a 150cc commuter). Pick one: keep 80, reuse 120 to match the trace chart, or make it per-ride adaptive (max segment speed = hot). Adaptive looks best per-ride but loses cross-ride comparability. **Default chosen: fixed 80 km/h; flagged ASSUMED.**
2. **Two zones or three?** Plan uses three brand zones (cool/mid/hot at zoneCool/zoneMid/zoneHot). Could be a continuous `lerp` gradient instead of three discrete buckets. Discrete is cheaper, reads as distinct "traffic vs open" bands, and is trivially testable; continuous is prettier. **Default chosen: three discrete zones.**
3. **Legend.** Should the card show a small "slow → fast" color legend strip + the bucket thresholds, or stay minimal (just the colored line, keep the existing "N GPS samples" subtitle)? **Default chosen: add a one-line legend so the colors are self-explaining.** Confirm wording.
4. **Start/end dots.** Currently green start dot + accent end dot. With a speed gradient those accent colors may clash. **Default chosen: keep the dots but switch the end dot to a neutral token (`textPrimary`) so it doesn't read as a speed zone.** Confirm.

---

### Task 1: `SpeedTrack` nearest-time join + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrack.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrackTest.kt`

This task delivers ONLY the pure join + normalization (no color). Color mapping is Task 2 so each stays small and independently tested.

- [ ] **Step 1: Write the failing test**

Create `SpeedTrackTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [SpeedTrack]. No Room runtime, no Android, no Compose. */
class SpeedTrackTest {

    private val t0 = 1_750_000_000_000L

    private fun loc(t: Long, lat: Double = 0.0, lng: Double = 0.0) =
        RideLocationEntity(id = 0, rideId = 1, tMillis = t, lat = lat, lng = lng, altitudeM = null, accuracyM = null)

    private fun sample(t: Long, speed: Int) =
        RideSampleEntity(id = 0, rideId = 1, tMillis = t, speedKmh = speed, odometerKm = 0, tripAKm = 0.0, tripBKm = 0.0, fuelBars = null, fuelEconKml = null)

    @Test fun joinsEachLocationToNearestSampleByTime() {
        // samples at t0, t0+5s, t0+10s with speeds 10/40/80
        val samples = listOf(sample(t0, 10), sample(t0 + 5_000, 40), sample(t0 + 10_000, 80))
        // locations land between sample ticks (GPS ~0.2 Hz vs telemetry ~5 s)
        val locs = listOf(
            loc(t0 + 500),      // nearest -> t0 (10)
            loc(t0 + 3_000),    // nearest -> t0+5s (40)  (3000 closer to 5000 than 0)
            loc(t0 + 9_900),    // nearest -> t0+10s (80)
        )
        val speeds = SpeedTrack.speedAtLocations(locs, samples)
        assertEquals(listOf(10, 40, 80), speeds)
    }

    @Test fun emptySamplesYieldsEmptyList() {
        val locs = listOf(loc(t0), loc(t0 + 1_000))
        assertTrue(SpeedTrack.speedAtLocations(locs, emptyList()).isEmpty())
    }

    @Test fun emptyLocationsYieldsEmptyList() {
        val samples = listOf(sample(t0, 30))
        assertTrue(SpeedTrack.speedAtLocations(emptyList(), samples).isEmpty())
    }

    @Test fun unsortedSamplesAreHandled() {
        // Join must not assume samples are pre-sorted.
        val samples = listOf(sample(t0 + 10_000, 80), sample(t0, 10), sample(t0 + 5_000, 40))
        val locs = listOf(loc(t0 + 200), loc(t0 + 9_000))
        assertEquals(listOf(10, 80), SpeedTrack.speedAtLocations(locs, samples))
    }

    @Test fun tieBreaksToEarlierSample() {
        // location exactly between two samples -> deterministic (earlier wins).
        val samples = listOf(sample(t0, 20), sample(t0 + 10_000, 60))
        val speeds = SpeedTrack.speedAtLocations(listOf(loc(t0 + 5_000)), samples)
        assertEquals(listOf(20), speeds)
    }

    @Test fun normalizeClampsAndScalesToCeiling() {
        // fraction = speed / ceiling, clamped to 0f..1f
        assertEquals(0.0f, SpeedTrack.fraction(0, ceilingKmh = 80), 0.0001f)
        assertEquals(0.5f, SpeedTrack.fraction(40, ceilingKmh = 80), 0.0001f)
        assertEquals(1.0f, SpeedTrack.fraction(80, ceilingKmh = 80), 0.0001f)
        assertEquals(1.0f, SpeedTrack.fraction(200, ceilingKmh = 80), 0.0001f) // clamp
        assertEquals(0.0f, SpeedTrack.fraction(-5, ceilingKmh = 80), 0.0001f) // clamp
    }

    @Test fun zeroCeilingDoesNotDivideByZero() {
        assertEquals(0.0f, SpeedTrack.fraction(40, ceilingKmh = 0), 0.0001f)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.SpeedTrackTest"`
Expected: FAIL — compilation error, `SpeedTrack` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `SpeedTrack.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import kotlin.math.abs

/**
 * Joins the phone's GPS track ([RideLocationEntity]) to the bike's per-sample ECU
 * speed ([RideSampleEntity.speedKmh]) so the ride polyline can be colored by speed.
 *
 * GPS samples (~0.2 Hz, phone) and telemetry samples (~5 s cadence, bike) arrive on
 * independent clocks, so we match each location to the telemetry sample whose
 * [RideSampleEntity.tMillis] is nearest in time. Speed is the bike's real a537 ECU
 * value — genuine ground truth, whole-km/h resolution.
 *
 * Pure JVM, deterministic, no Compose/Android — tested in SpeedTrackTest. Color
 * mapping lives in [SpeedTrackColors] (kept separate so this stays Compose-free).
 */
object SpeedTrack {

    /**
     * For each location (in input order) return the [RideSampleEntity.speedKmh] of
     * the nearest-in-time telemetry sample. Returns an empty list if either input is
     * empty. Ties (equidistant) resolve to the earlier sample for determinism.
     *
     * O(L log S + L log S) — sorts samples once, then binary-searches per location.
     */
    fun speedAtLocations(
        locations: List<RideLocationEntity>,
        samples: List<RideSampleEntity>,
    ): List<Int> {
        if (locations.isEmpty() || samples.isEmpty()) return emptyList()
        val sorted = samples.sortedBy { it.tMillis }
        val times = LongArray(sorted.size) { sorted[it].tMillis }
        return locations.map { loc ->
            sorted[nearestIndex(times, loc.tMillis)].speedKmh
        }
    }

    /** Index into [times] (ascending) whose value is closest to [target]; earlier wins ties. */
    private fun nearestIndex(times: LongArray, target: Long): Int {
        var lo = 0
        var hi = times.size - 1
        if (target <= times[lo]) return lo
        if (target >= times[hi]) return hi
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                times[mid] == target -> return mid
                times[mid] < target -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        // lo is the first index > target, hi == lo - 1 is the last <= target.
        val before = hi
        val after = lo
        val dBefore = abs(target - times[before])
        val dAfter = abs(times[after] - target)
        return if (dBefore <= dAfter) before else after // earlier wins ties
    }

    /** Normalize a speed to 0f..1f over [ceilingKmh]; clamps out-of-range, guards zero ceiling. */
    fun fraction(speedKmh: Int, ceilingKmh: Int): Float {
        if (ceilingKmh <= 0) return 0f
        return (speedKmh.toFloat() / ceilingKmh).coerceIn(0f, 1f)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.SpeedTrackTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrack.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrackTest.kt
git commit -m "feat(trips): pure SpeedTrack nearest-time join + speed normalization with tests"
```

---

### Task 2: `SpeedTrackColors` — fraction → zone color (ARGB Int), tested

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrackColors.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrackColorsTest.kt`

The mapping returns ARGB `Int` (not Compose `Color`) so it is pure JVM and unit-testable. The brand zone colors are duplicated here as `0xFF……L.toInt()` constants matching `GixxerTokens.zone*` (cool `0xFF10D9C4`, mid `0xFFF5A524`, hot `0xFFFF2D78`). The UI wraps them with `Color(argb)`.

> NOTE for implementer: keep these three constants in sync with `GixxerTokens` (zoneCool/zoneMid/zoneHot). A test asserts the exact hex so a silent drift fails loudly.

- [ ] **Step 1: Write the failing test**

Create `SpeedTrackColorsTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [SpeedTrackColors]. Asserts exact ARGB so brand drift fails loudly. */
class SpeedTrackColorsTest {

    @Test fun lowFractionIsCool() {
        assertEquals(0xFF10D9C4.toInt(), SpeedTrackColors.zoneColor(0.0f))
        assertEquals(0xFF10D9C4.toInt(), SpeedTrackColors.zoneColor(0.32f))
    }

    @Test fun midFractionIsMid() {
        assertEquals(0xFFF5A524.toInt(), SpeedTrackColors.zoneColor(0.34f))
        assertEquals(0xFFF5A524.toInt(), SpeedTrackColors.zoneColor(0.65f))
    }

    @Test fun highFractionIsHot() {
        assertEquals(0xFFFF2D78.toInt(), SpeedTrackColors.zoneColor(0.67f))
        assertEquals(0xFFFF2D78.toInt(), SpeedTrackColors.zoneColor(1.0f))
    }

    @Test fun outOfRangeFractionsClamp() {
        assertEquals(0xFF10D9C4.toInt(), SpeedTrackColors.zoneColor(-1.0f))
        assertEquals(0xFFFF2D78.toInt(), SpeedTrackColors.zoneColor(5.0f))
    }

    @Test fun thresholdsAreOneThirdAndTwoThirds() {
        // boundary behavior is documented: < 1/3 cool, < 2/3 mid, else hot.
        assertEquals(0xFF10D9C4.toInt(), SpeedTrackColors.zoneColor(0.333f))
        assertEquals(0xFFF5A524.toInt(), SpeedTrackColors.zoneColor(0.334f))
        assertEquals(0xFFF5A524.toInt(), SpeedTrackColors.zoneColor(0.666f))
        assertEquals(0xFFFF2D78.toInt(), SpeedTrackColors.zoneColor(0.667f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.SpeedTrackColorsTest"`
Expected: FAIL — `SpeedTrackColors` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `SpeedTrackColors.kt`:

```kotlin
package dev.mrwick.gixxerbridge.analytics

/**
 * Maps a normalized speed fraction (0f..1f from [SpeedTrack.fraction]) to one of the
 * three brand "zone" colors, as packed ARGB [Int].
 *
 * Discrete three-band mapping (cool / mid / hot) rather than a continuous gradient:
 * the goal is "where was I stuck vs flowing", which three distinct bands convey at a
 * glance and which is trivially testable. Boundaries: fraction < 1/3 -> cool,
 * < 2/3 -> mid, else hot.
 *
 * Constants MUST mirror GixxerTokens.zone{Cool,Mid,Hot}; a test asserts the exact hex.
 * The UI layer wraps the result in androidx.compose.ui.graphics.Color(argb).
 *
 * Pure JVM, no Compose — tested in SpeedTrackColorsTest.
 */
object SpeedTrackColors {

    const val ZONE_COOL: Int = 0xFF10D9C4.toInt() // cruise / stop-and-go (slow)
    const val ZONE_MID: Int = 0xFFF5A524.toInt()  // transitional (mid)
    const val ZONE_HOT: Int = 0xFFFF2D78.toInt()  // open road (fast)

    private const val LOW = 1.0f / 3.0f
    private const val HIGH = 2.0f / 3.0f

    /** ARGB color for a 0f..1f speed fraction; out-of-range values clamp to the ends. */
    fun zoneColor(fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        return when {
            f < LOW -> ZONE_COOL
            f < HIGH -> ZONE_MID
            else -> ZONE_HOT
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.SpeedTrackColorsTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrackColors.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/SpeedTrackColorsTest.kt
git commit -m "feat(trips): pure fraction->zone-color mapping (ARGB) with tests"
```

---

### Task 3: `RideTrackCard` — colored per-segment polyline

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripDetailScreen.kt`

UI — verified by compile + on-device visual check (no Compose unit test). All speed/color logic is already unit-tested in Tasks 1–2; this task only does Canvas plumbing.

- [ ] **Step 1: Pass samples into `RideTrackCard`**

`RideTrackCard(locations)` is currently called at `TripDetailScreen.kt:317`. The `samples` list (`vm.selectedSamples`, oldest-first) is already collected at the top of `TripDetailScreen` (`TripDetailScreen.kt:73`). Change the call site to:

```kotlin
        RideTrackCard(locations = locations, samples = samples)
```

- [ ] **Step 2: Add imports**

Add these to the import block (near the other `androidx.compose.ui.graphics` / `dev.mrwick.gixxerbridge` imports):

```kotlin
import androidx.compose.ui.graphics.Color
import dev.mrwick.gixxerbridge.analytics.SpeedTrack
import dev.mrwick.gixxerbridge.analytics.SpeedTrackColors
import dev.mrwick.gixxerbridge.data.RideSampleEntity
```

(`RideSampleEntity` may already be importable via the VM type; confirm the explicit import compiles — if the file already imports it transitively the compiler will flag a redundant import to remove.)

- [ ] **Step 3: Rewrite `RideTrackCard` to accept samples + draw colored segments**

Replace the entire `RideTrackCard` composable (currently `TripDetailScreen.kt:400-459`) with:

```kotlin
/**
 * Square Canvas card plotting the ride's GPS track as an equal-aspect polyline,
 * colored per segment by the bike's ECU speed at that point (cool = slow/stop-and-go,
 * hot = fast/open road). Falls back to a flat accent line when no telemetry samples
 * are available to join against.
 */
@Composable
private fun RideTrackCard(
    locations: List<RideLocationEntity>,
    samples: List<RideSampleEntity>,
) {
    if (locations.size < 2) return

    // ASSUMED ceiling: 80 km/h spreads city-riding contrast for this 150cc commuter.
    // See Open Question #1 — confirm/tune. The on-screen speed trace clamps to 120.
    val speedCeilingKmh = 80

    // Nearest-time join: speed per location (same order as `locations`). Empty when
    // there are no telemetry samples -> we draw the original flat-color track.
    val speedsPerLoc = remember(locations, samples) {
        SpeedTrack.speedAtLocations(locations, samples)
    }
    val colored = speedsPerLoc.size == locations.size

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Track",
                style = MaterialTheme.typography.titleMedium,
                color = GixxerTokens.textPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                if (colored) "${locations.size} GPS samples · colored by speed"
                else "${locations.size} GPS samples",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                val lats = locations.map { it.lat }
                val lngs = locations.map { it.lng }
                val minLat = lats.min(); val maxLat = lats.max()
                val minLng = lngs.min(); val maxLng = lngs.max()
                val latSpan = (maxLat - minLat).coerceAtLeast(0.0001)
                val lngSpan = (maxLng - minLng).coerceAtLeast(0.0001)
                val scale = (size.width / lngSpan).coerceAtMost(size.height / latSpan)
                val xOffset = (size.width - lngSpan * scale) / 2
                val yOffset = (size.height - latSpan * scale) / 2
                fun project(lat: Double, lng: Double) = Offset(
                    x = ((lng - minLng) * scale + xOffset).toFloat(),
                    y = (size.height - ((lat - minLat) * scale + yOffset)).toFloat(),
                )
                val bgColor = GixxerTokens.surfaceElevated
                drawRect(bgColor, size = size, style = Stroke(width = 1f))

                if (colored) {
                    // One drawLine per segment; segment color = the higher-index point's
                    // speed (the speed you were doing arriving at that point).
                    for (i in 1 until locations.size) {
                        val a = project(locations[i - 1].lat, locations[i - 1].lng)
                        val b = project(locations[i].lat, locations[i].lng)
                        val frac = SpeedTrack.fraction(speedsPerLoc[i], speedCeilingKmh)
                        drawLine(
                            color = Color(SpeedTrackColors.zoneColor(frac)),
                            start = a,
                            end = b,
                            strokeWidth = 4f,
                            cap = StrokeCap.Round,
                        )
                    }
                } else {
                    // Fallback: original single flat-accent path.
                    val path = Path()
                    val firstP = project(locations[0].lat, locations[0].lng)
                    path.moveTo(firstP.x, firstP.y)
                    for (i in 1 until locations.size) {
                        val p = project(locations[i].lat, locations[i].lng)
                        path.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path,
                        color = GixxerTokens.accent,
                        style = Stroke(width = 4f, cap = StrokeCap.Round),
                    )
                }

                // Start = green, end = neutral so it doesn't read as a speed zone (OQ #4).
                val first = project(locations[0].lat, locations[0].lng)
                val last = project(locations.last().lat, locations.last().lng)
                drawCircle(GixxerTokens.success, radius = 8f, center = first)
                drawCircle(GixxerTokens.textPrimary, radius = 8f, center = last)
            }
            // Legend (Open Question #3): one-line cool->hot key. Remove if OQ #3 says minimal.
            if (colored) {
                Spacer(modifier = Modifier.height(10.dp))
                SpeedLegend(ceilingKmh = speedCeilingKmh)
            }
        }
    }
}

/** Compact "slow -> fast" color key for the speed-track polyline. */
@Composable
private fun SpeedLegend(ceilingKmh: Int) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LegendSwatch(Color(SpeedTrackColors.ZONE_COOL), "slow")
        LegendSwatch(Color(SpeedTrackColors.ZONE_MID), "mid")
        LegendSwatch(Color(SpeedTrackColors.ZONE_HOT), "fast")
        Text(
            "(0–$ceilingKmh km/h)",
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.textMuted,
        )
    }
}

@Composable
private fun LegendSwatch(color: Color, label: String) {
    Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Canvas(modifier = Modifier.height(10.dp).width(14.dp)) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2),
                end = Offset(size.width, size.height / 2),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = GixxerTokens.textMuted)
    }
}
```

Notes for implementer:
- `drawLine`, `drawCircle`, `drawRect` are all `DrawScope` members already in scope inside `Canvas {}`. `drawLine` is already used elsewhere in the codebase (`ui/trips/PostRideSummary.kt:324`).
- Add the `width` layout import if not present: `import androidx.compose.foundation.layout.width`. (`height`, `Row`, `Arrangement`, `Spacer` are already imported in this file.)
- `androidx.compose.ui.Alignment` is referenced fully-qualified above to avoid a new import; the implementer may add `import androidx.compose.ui.Alignment` and shorten if preferred.
- If `SpeedLegend`/`LegendSwatch` are dropped per Open Question #3, also drop the `width`/`Alignment` additions.

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If a redundant-import warning appears for `RideSampleEntity` or `Color`, remove the duplicate import.)

- [ ] **Step 5: Run the full unit suite (regression guard)**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: PASS — Tasks 1–2 tests plus all existing analytics tests still green.

- [ ] **Step 6: Build, install, and visually verify on device**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Then open a ride with both GPS + telemetry samples (a real recorded ride) → Trip Detail → the Track card line is now multi-colored: cool where you were stopped/slow, hot on fast stretches; subtitle reads "N GPS samples · colored by speed"; legend strip shows slow/mid/fast. Open a ride with GPS but no telemetry samples (rare/older ride) → line is the old flat accent color, subtitle "N GPS samples", no legend.

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripDetailScreen.kt
git commit -m "feat(trips): color ride track polyline by ECU speed (heat polyline)"
```

---

## Self-Review

**Spec / research coverage:**
- Nearest-`tMillis` join of `RideLocationEntity` → `RideSampleEntity.speedKmh` → Task 1 ✓
- Replace one `drawPath` with N colored `drawLine` calls → Task 3 ✓
- Fall back to flat color when samples empty / count mismatch → Task 3 (`colored` guard) ✓
- No schema bump (read-only over existing columns) → confirmed; zero Room changes ✓
- Pure analytics JVM-unit-tested (JUnit4 / `org.junit`) → Tasks 1 (7 tests) + 2 (5 tests) ✓
- UI verified by build + on-device → Task 3 steps 4–6 ✓

**No-assumptions rule:**
- Speed is genuine a537 ECU ground truth (per research entry) — stated as fact, sourced.
- The 80 km/h ceiling and the three-band thresholds are ASSUMED/UX choices, flagged in code comments AND raised as Open Questions, not silently baked as truth.
- The nearest-time join quality depends on GPS (~0.2 Hz) vs telemetry (~5 s) cadence alignment — see Risks; segment color is approximate, surfaced honestly in the subtitle ("colored by speed", not "exact").

**Placeholder scan:** No TBD/TODO; every step has concrete code + a runnable command.

**Type consistency:** `SpeedTrack.speedAtLocations(locations, samples): List<Int>` and `SpeedTrack.fraction(speedKmh, ceilingKmh): Float` used identically in Task 1 (impl + test) and Task 3 (UI). `SpeedTrackColors.zoneColor(fraction): Int` + `ZONE_*` constants used identically in Task 2 (impl + test) and Task 3. `RideTrackCard(locations, samples)` call site (step 1) matches the new signature (step 3).
