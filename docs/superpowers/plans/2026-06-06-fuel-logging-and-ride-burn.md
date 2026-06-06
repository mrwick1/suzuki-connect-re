# Fuel logging + per-ride fuel burnt + phantom-ride fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make fuel logging one-tap (auto-odometer), show an estimated fuel-burnt per ride, and stop phantom (never-moved) rides from polluting the log.

**Architecture:** Pure, JVM-testable analytics in `RideAnalytics` compute the burn estimate (fill-measured km/L preferred, bike econ fallback). The fuel-add screen pre-fills the odometer from live telemetry (`TelemetryRepository.latest`) or last-known ride history. The phantom-ride decision becomes a pure function in `RideLogger`. UI surfaces (fuel dialog, trip detail, post-ride summary) consume these.

**Tech Stack:** Kotlin, Jetpack Compose, Room, JUnit4 unit tests (`testDebugUnitTest`). Spec: `docs/superpowers/specs/2026-06-06-fuel-logging-and-ride-burn-design.md`.

**Working dir for all commands:** `/home/mrwick/coding/projects/suzuki-connect-re/android`

**Note on uncommitted tree:** the working tree already contains parked cluster/LED harness changes + an idle-clock-toggle bug fix from the same session. Each task below commits ONLY its own files (explicit `git add` paths) so those parked changes stay out of these commits.

---

### Task 1: Fuel-burn pure functions in RideAnalytics

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RideAnalytics.kt`
- Test: `app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RideAnalyticsTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these tests inside the `RideAnalyticsTest` class (it already has a `sample(rideId, speed, fuelEcon)` helper and imports `assertEquals` / `assertNull`):

```kotlin
    // ---------- avgBikeEcon ----------

    @Test fun avgBikeEconIgnoresNullAndNonPositive() {
        val samples = listOf(
            sample(1, 40, fuelEcon = 30.0),
            sample(1, 50, fuelEcon = null),
            sample(1, 0, fuelEcon = 0.0),
            sample(1, 60, fuelEcon = 50.0),
        )
        assertEquals(40.0, RideAnalytics.avgBikeEcon(samples)!!, 1e-9)
    }

    @Test fun avgBikeEconNullWhenNoUsableReadings() {
        assertNull(RideAnalytics.avgBikeEcon(listOf(sample(1, 40, fuelEcon = null))))
        assertNull(RideAnalytics.avgBikeEcon(emptyList()))
    }

    // ---------- fuelBurnt ----------

    @Test fun fuelBurntPrefersFillsOverBike() {
        val burn = RideAnalytics.fuelBurnt(distanceKm = 100, fillKmPerL = 50.0, bikeKmPerL = 40.0)!!
        assertEquals(2.0, burn.litres, 1e-9)
        assertEquals(FuelBurnSource.FILLS, burn.source)
    }

    @Test fun fuelBurntFallsBackToBikeWhenNoFills() {
        val burn = RideAnalytics.fuelBurnt(distanceKm = 80, fillKmPerL = null, bikeKmPerL = 40.0)!!
        assertEquals(2.0, burn.litres, 1e-9)
        assertEquals(FuelBurnSource.BIKE, burn.source)
    }

    @Test fun fuelBurntNullWhenNoSourceOrNoDistance() {
        assertNull(RideAnalytics.fuelBurnt(100, null, null))
        assertNull(RideAnalytics.fuelBurnt(0, 50.0, 40.0))
        assertNull(RideAnalytics.fuelBurnt(100, 0.0, 0.0))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RideAnalyticsTest"`
Expected: FAIL — unresolved references `avgBikeEcon`, `fuelBurnt`, `FuelBurnSource`, `FuelBurn`.

- [ ] **Step 3: Implement the functions + types**

Add these two functions inside the `object RideAnalytics { ... }` body (e.g. after `totalsFor`):

```kotlin
    /**
     * Average of the bike's own per-sample economy readings (km/L) over a ride.
     * Ignores null / non-positive readings. Null when no usable reading exists.
     */
    fun avgBikeEcon(samples: List<RideSampleEntity>): Double? {
        val vals = samples.mapNotNull { it.fuelEconKml }.filter { it > 0.0 }
        return if (vals.isEmpty()) null else vals.average()
    }

    /**
     * Estimate litres burnt over [distanceKm] given a km/L figure. Prefers the
     * rider's fill-measured mileage [fillKmPerL]; falls back to the bike's own
     * economy [bikeKmPerL]. Returns null when neither source is usable or the
     * ride covered no distance. [FuelBurn.source] records which figure was used
     * so the UI can label the estimate.
     */
    fun fuelBurnt(distanceKm: Int, fillKmPerL: Double?, bikeKmPerL: Double?): FuelBurn? {
        if (distanceKm <= 0) return null
        val fill = fillKmPerL?.takeIf { it > 0.0 }
        val bike = bikeKmPerL?.takeIf { it > 0.0 }
        val kmPerL = fill ?: bike ?: return null
        val source = if (fill != null) FuelBurnSource.FILLS else FuelBurnSource.BIKE
        return FuelBurn(litres = distanceKm / kmPerL, source = source)
    }
```

Add these top-level declarations at the END of the same file (after the closing `}` of `object RideAnalytics`):

```kotlin
/** Which km/L figure produced a [FuelBurn] estimate. */
enum class FuelBurnSource { FILLS, BIKE }

/** Estimated litres burnt over a ride + the source of the km/L used. */
data class FuelBurn(val litres: Double, val source: FuelBurnSource)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.RideAnalyticsTest"`
Expected: PASS (all RideAnalyticsTest tests green).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/RideAnalytics.kt \
        app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/RideAnalyticsTest.kt
git commit -m "feat(analytics): estimate per-ride fuel burnt (fills-preferred, bike-econ fallback)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Phantom-ride discard fix in RideLogger

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/telemetry/RideLogger.kt`
- Test: `app/src/test/kotlin/dev/mrwick/gixxerbridge/telemetry/RideLoggerNamingTest.kt`

- [ ] **Step 1: Write the failing tests**

Append to the `RideLoggerNamingTest` class. Ensure these imports exist at the top of the file: `import org.junit.Assert.assertTrue` and `import org.junit.Assert.assertFalse` (add if missing).

```kotlin
    // ---------- shouldDiscard ----------

    @Test fun discardsRideThatNeverMoved() {
        // Never moved → always noise, regardless of distance/duration.
        assertTrue(RideLogger.shouldDiscard(everMoved = false, distanceKm = 0, durationMs = 600_000L))
        assertTrue(RideLogger.shouldDiscard(everMoved = false, distanceKm = 5, durationMs = 600_000L))
    }

    @Test fun keepsRealRideThatMovedAndCoveredDistance() {
        assertFalse(RideLogger.shouldDiscard(everMoved = true, distanceKm = 5, durationMs = 600_000L))
    }

    @Test fun discardsShortBlipEvenIfItMoved() {
        assertTrue(RideLogger.shouldDiscard(everMoved = true, distanceKm = 0, durationMs = 10_000L))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.telemetry.RideLoggerNamingTest"`
Expected: FAIL — unresolved reference `shouldDiscard`.

- [ ] **Step 3: Add the pure decision function (companion object)**

In `RideLogger.kt`, inside `companion object { ... }` (next to `autoName`), add:

```kotlin
        /**
         * Whether a finishing ride is noise that should be dropped instead of
         * persisted. Discarded when it never moved (no sample ever reported
         * speed > 0) — this is what let the 2026-06-06 phantom (max speed 0)
         * leak in — or when it covered < 1 km in under [MIN_RIDE_DURATION_MS]
         * (a key-on/off blip).
         */
        fun shouldDiscard(everMoved: Boolean, distanceKm: Int, durationMs: Long): Boolean {
            if (!everMoved) return true
            return distanceKm < 1 && durationMs < MIN_RIDE_DURATION_MS
        }
```

- [ ] **Step 4: Wire it into the logger**

In `RideLogger`, add the field next to the other per-ride state (near `private var lastFuel: Int? = null`):

```kotlin
    // Set true once any sample reports speed > 0. A ride that never moved is
    // discarded on end (parked key-on capture), regardless of duration.
    private var everMoved: Boolean = false
```

In `onSample`, inside the `mutex.withLock { ... }` block, after the `store.appendSample(...)` call and before `lastSampleMillis = now`, add:

```kotlin
            if (frame.speedKmh > 0) everMoved = true
```

In `endRideInternal`, replace this line:

```kotlin
            val shouldDiscard = distance < 1 && durationMs < MIN_RIDE_DURATION_MS
            if (shouldDiscard) {
```

with:

```kotlin
            val discard = shouldDiscard(everMoved, distance, durationMs)
            if (discard) {
```

Still in `endRideInternal`, in the reset block at the end (where `rideId = null` and `connectOdo = null` are set), add:

```kotlin
            everMoved = false
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.telemetry.RideLoggerNamingTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/dev/mrwick/gixxerbridge/telemetry/RideLogger.kt \
        app/src/test/kotlin/dev/mrwick/gixxerbridge/telemetry/RideLoggerNamingTest.kt
git commit -m "fix(rides): discard rides that never moved (phantom key-on captures)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Last-known-odometer accessor (RideDao + RideStore)

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt`

- [ ] **Step 1: Add the DAO query**

In `interface RideDao`, add (e.g. after `getRideInProgress()`):

```kotlin
    /** The most recently-ended ride (has an end odometer), or null. */
    @Query("SELECT * FROM rides WHERE endOdoKm IS NOT NULL ORDER BY startedAtMillis DESC LIMIT 1")
    suspend fun getLastEndedRide(): RideEntity?
```

- [ ] **Step 2: Add the store wrapper**

In `class RideStore`, add (e.g. after `rideInProgress()`):

```kotlin
    /**
     * Best guess at the bike's current odometer from history: the end-odo of the
     * most recently-ended ride, or null if no ride has ended yet. Used to
     * pre-fill the fuel-fill odometer when live telemetry isn't available.
     */
    suspend fun lastKnownOdometer(): Int? = dao.getLastEndedRide()?.endOdoKm
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (Room annotation processor accepts the new query).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/mrwick/gixxerbridge/data/RideStore.kt
git commit -m "feat(rides): expose last-known odometer from ride history

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: currentOdometer() in MileageViewModel

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageViewModel.kt`

- [ ] **Step 1: Add imports**

Add to the import block:

```kotlin
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
```

- [ ] **Step 2: Add a RideStore handle + currentOdometer()**

After the existing `private val store: FuelStore = ...` line, add:

```kotlin
    private val rideStore: RideStore = RideStore(GixxerDatabase.get(app).rideDao())
```

Add this method to the class (e.g. after `addFill`):

```kotlin
    /**
     * Best odometer to pre-fill the fill form with at the moment "Fill up" is
     * tapped: the live telemetry value if the bike is connected, else the
     * last-known odometer from ride history, else null (rider types it in).
     */
    suspend fun currentOdometer(): Int? =
        TelemetryRepository.latest.value?.odometerKm ?: rideStore.lastKnownOdometer()
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageViewModel.kt
git commit -m "feat(mileage): currentOdometer() from live telemetry or last ride

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Fuel-add UI — auto-odometer + relabel (MileageScreen)

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageScreen.kt`

No unit test (Compose UI); verify by build + manual check.

- [ ] **Step 1: Rename the action to "Fill up"**

Replace the FAB text line:

```kotlin
                text = { Text("Add fill") },
```

with:

```kotlin
                text = { Text("Fill up") },
```

And the empty-state body:

```kotlin
                        body = "No fills logged yet — tap \"Add fill\" after your next pump visit.",
```

with:

```kotlin
                        body = "No fills logged yet — tap \"Fill up\" after your next pump visit.",
```

- [ ] **Step 2: Pre-fill the odometer when the dialog opens**

Replace this block:

```kotlin
    if (showAdd) {
        AddFillDialog(
            onDismiss = { showAdd = false },
            onConfirm = { odo, litres, rupees, note ->
                vm.addFill(odo, litres, rupees, note)
                showAdd = false
            },
        )
    }
```

with:

```kotlin
    if (showAdd) {
        // Fetch the best odometer (live telemetry, else last ride) once when the
        // dialog opens; null until it resolves, then the field populates.
        val initialOdo by produceState<Int?>(initialValue = null) { value = vm.currentOdometer() }
        AddFillDialog(
            initialOdo = initialOdo,
            onDismiss = { showAdd = false },
            onConfirm = { odo, litres, rupees, note ->
                vm.addFill(odo, litres, rupees, note)
                showAdd = false
            },
        )
    }
```

(`produceState` is already imported — it's used for `bootDone`.)

- [ ] **Step 3: Rework AddFillDialog — pre-fill odo, reorder, require price**

Replace the entire `AddFillDialog` composable (the `@Composable private fun AddFillDialog(...) { ... }` block) with:

```kotlin
/**
 * Modal dialog for logging a fuel fill. Odometer is pre-filled from
 * [initialOdo] (live telemetry or last ride) and stays editable. Litres, total
 * price, and odometer are required; note is optional. Submit enables once
 * odometer >= 0, litres > 0, and total price > 0.
 */
@Composable
private fun AddFillDialog(
    initialOdo: Int?,
    onDismiss: () -> Unit,
    onConfirm: (odometerKm: Int, litres: Double, rupees: Double?, note: String?) -> Unit,
) {
    // Re-key on initialOdo so the field populates when the async lookup resolves.
    var odoText by remember(initialOdo) { mutableStateOf(initialOdo?.toString() ?: "") }
    var litresText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val odo = odoText.toIntOrNull()
    val litres = litresText.toDoubleOrNull()
    val price = priceText.toDoubleOrNull()
    val canSubmit = odo != null && odo >= 0 && litres != null && litres > 0.0 &&
        price != null && price > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log fuel fill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = litresText,
                    onValueChange = { raw -> litresText = sanitizeDecimal(raw) },
                    label = { Text("Litres") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { raw -> priceText = sanitizeDecimal(raw) },
                    label = { Text("Total price (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = odoText,
                    onValueChange = { raw -> odoText = raw.filter { it.isDigit() }.take(7) },
                    label = { Text("Odometer (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("Note (optional)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConfirm(odo!!, litres!!, price, note.ifBlank { null }) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
```

(Keep the existing `sanitizeDecimal` function as-is.)

- [ ] **Step 4: Build + install, verify manually**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, "Installed on 1 device."

Manual check (Mileage screen → "Fill up"): odometer field is pre-filled (live value if connected, else last ride's end-odo), Litres + Total price are required (Save disabled until both > 0), Save persists the fill.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/mileage/MileageScreen.kt
git commit -m "feat(mileage): one-tap Fill up — auto odometer, litres + total price

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Per-ride fuel burnt on Trip Detail

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsViewModel.kt`
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripDetailScreen.kt`

- [ ] **Step 1: Expose fill-measured km/L from TripsViewModel**

In `TripsViewModel.kt`, add imports:

```kotlin
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.data.FuelStore
```

Add a `FuelStore` handle next to the existing `store`:

```kotlin
    private val fuelStore: FuelStore = FuelStore(GixxerDatabase.get(context).fuelFillDao())
```

Add a derived flow (after the `rides` property):

```kotlin
    /**
     * Rider's fill-measured trailing-average km/L, or null until enough fuel
     * fills exist. When present it's the calibrated source for per-ride fuel
     * burnt; otherwise the UI falls back to the bike's logged economy.
     */
    val fillKmPerL: StateFlow<Double?> = fuelStore.observe()
        .map { MileageAnalytics.averageKmPerL(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

- [ ] **Step 2: Add the "Fuel used" stat to TripDetailScreen**

In `TripDetailScreen.kt`, add import:

```kotlin
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
```

In `fun TripDetailScreen(...)`, after the existing `val rides by ...` / `val samples by ...` collectors near the top, add:

```kotlin
    val fillKmPerL by vm.fillKmPerL.collectAsStateWithLifecycle()
```

After `val distance = max(0, (ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)`, add:

```kotlin
        val fuelBurn = RideAnalytics.fuelBurnt(
            distanceKm = distance,
            fillKmPerL = fillKmPerL,
            bikeKmPerL = RideAnalytics.avgBikeEcon(samples),
        )
        val fuelUsedText = fuelBurn?.let { "~${"%.2f".format(it.litres)} L" } ?: "—"
```

Replace the second hero-stat row:

```kotlin
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    HeroStat(label = "Max speed", value = "${ride.maxSpeedKmh} km/h")
                    HeroStat(label = "Samples", value = "${ride.sampleCount}")
                }
```

with:

```kotlin
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    HeroStat(label = "Max speed", value = "${ride.maxSpeedKmh} km/h")
                    HeroStat(label = "Samples", value = "${ride.sampleCount}")
                    HeroStat(label = "Fuel used", value = fuelUsedText)
                }
```

- [ ] **Step 3: Build + install, verify manually**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL.

Manual check: open a finished ride in Trips → detail shows a "Fuel used ~X.XX L" stat. With no fuel fills it uses the bike econ; after logging fills it switches to the fill-based figure.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripsViewModel.kt \
        app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/TripDetailScreen.kt
git commit -m "feat(trips): show estimated fuel used on trip detail

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Per-ride fuel burnt on Post-Ride Summary

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/PostRideSummary.kt`

- [ ] **Step 1: Load fills + compute burn in the host/content**

In `PostRideSummary.kt`, add imports:

```kotlin
import dev.mrwick.gixxerbridge.analytics.MileageAnalytics
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.data.FuelStore
import dev.mrwick.gixxerbridge.data.GixxerDatabase
```

In `PostRideSummaryHost`, alongside where `ride`/`samples`/`locations` are loaded, add a fill-calibrated km/L lookup (place near the other `produceState`/load logic):

```kotlin
    val fillKmPerL by produceState<Double?>(initialValue = null) {
        value = MileageAnalytics.averageKmPerL(
            FuelStore(GixxerDatabase.get(ctx).fuelFillDao()).all()
        )
    }
```

If a `Context` named `ctx` is not already in scope in the host, get one: `val ctx = LocalContext.current` (import `androidx.compose.ui.platform.LocalContext`).

Pass it into `PostRideSummaryContent(...)` by adding a `fillKmPerL = fillKmPerL` argument to the existing call.

- [ ] **Step 2: Thread it through Content → DistanceDurationCard**

Add `fillKmPerL: Double?,` to the `PostRideSummaryContent` parameter list.

In `PostRideSummaryContent`, before the pager, compute the burn (it has `ride` + `samples` in scope):

```kotlin
    val distanceForBurn = ride?.let { max(0, (it.endOdoKm ?: it.startOdoKm) - it.startOdoKm) } ?: 0
    val fuelBurntL = RideAnalytics.fuelBurnt(
        distanceKm = distanceForBurn,
        fillKmPerL = fillKmPerL,
        bikeKmPerL = RideAnalytics.avgBikeEcon(samples),
    )?.litres
```

Change the page-0 card call from `0 -> DistanceDurationCard(ride)` to:

```kotlin
                    0 -> DistanceDurationCard(ride, fuelBurntL)
```

- [ ] **Step 3: Show it on the distance card**

Change the `DistanceDurationCard` signature:

```kotlin
private fun DistanceDurationCard(ride: RideEntity?) {
```

to:

```kotlin
private fun DistanceDurationCard(ride: RideEntity?, fuelBurntL: Double?) {
```

Replace the caption `Text`:

```kotlin
        Text(
            "km · $durationMin min",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.W600,
            color = GixxerTokens.textMuted,
            textAlign = TextAlign.Center,
        )
```

with:

```kotlin
        Text(
            "km · $durationMin min" +
                (fuelBurntL?.let { " · ~${"%.2f".format(it)} L" } ?: ""),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.W600,
            color = GixxerTokens.textMuted,
            textAlign = TextAlign.Center,
        )
```

- [ ] **Step 4: Build + install, verify manually**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL.

Manual check: finish a ride (or use Demo mode) → the post-ride summary's first card shows `… km · N min · ~X.XX L`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/trips/PostRideSummary.kt
git commit -m "feat(trips): show estimated fuel used on post-ride summary

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Final verification

- [ ] Run the full unit suite: `./gradlew :app:testDebugUnitTest`
  Expected: BUILD SUCCESSFUL (RideAnalyticsTest + RideLoggerNamingTest green, no regressions).
- [ ] `./gradlew :app:installDebug` and smoke-test: log a fill (auto-odo), open a trip detail (fuel used), trigger a post-ride summary (fuel used). Confirm the phantom-discard didn't break normal ride logging (a real Demo-mode ride still persists).

## Notes / deviations from spec

- The spec mentioned a dedicated `RideDao` `AVG(fuelEconKml)` query for per-ride bike econ. This plan instead reuses the already-loaded `samples` via `RideAnalytics.avgBikeEcon`, which is DRY and avoids a redundant query — same result, no schema change.
- `FuelFillEntity.rupees` stays nullable at the DB layer (no migration); the UI now requires total price. `fuel_fills` is currently empty, so there's no backfill concern.
