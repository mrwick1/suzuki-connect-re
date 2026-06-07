# Service ETA forecast (calendar-date until next service) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Translate a service item's static `kmRemaining` gate into a calendar-date ETA ("~18 days until oil change") using the rider's recent daily-km pace, shown alongside the existing km/days gate. **Calendar wins:** the displayed ETA is never later than the item's own `daysRemaining` gate. Guard the zero-km window (no recent rides) so the km gate can't divide by zero — fall back to the calendar (days) gate alone.

**Architecture:** A new pure object `ServiceEta` takes a `ServiceItemHealth` (existing — carries `kmRemaining` + `daysRemaining` from `ServiceSchedule.healthFor`) plus a `kmPerDay` pace figure and `now`, and returns a `ServiceEtaForecast?` with the projected due date in epoch-millis and the number of days away. The pace comes from existing `RideAnalytics.totalsFor(rides, days = 30).km / 30.0` (km/day over the rolling 30-day window) — no new analytics primitive needed for distance. The forecast is consumed read-only by (a) the per-item editor subtext on `MaintenanceSettingsScreen` and (b) the worst-item caption on `BikeHealthCard`. All math is pure JVM; no Room, no DataStore, **no schema change**.

**Why no schema change:** `kmRemaining`/`daysRemaining` are already recomputed on read by `ServiceSchedule.healthFor` from `ServiceItemState` (DataStore-persisted) + live odometer. Pace is recomputed on read from `RideEntity` rows already in Room. `GixxerDatabase` uses `fallbackToDestructiveMigration`, so adding a Room column would wipe ride history — this feature touches no entity and adds no column. (Verified: research report line 19, and `BikeHealthCard.kt` already computes `ServiceSchedule.mostOverdue(...)` on read.)

**Tech Stack:** Kotlin, Jetpack Compose, JUnit4 (`org.junit`), Gradle. Pure logic in `analytics/`, tested in `app/src/test`.

**Spec / research:** `docs/superpowers/research/2026-06-06-stats-and-features-research.md` (Top Picks #7, lines 63-67).

---

## Open questions for the human (UI/UX placement — decide before Task 3)

These are **product decisions**, not blockers for the pure-logic tasks (Tasks 1-2 can ship regardless). Tasks 3-4 assume answers; flag if you disagree.

1. **Which surface(s) show the ETA?** Three candidates exist, all reading the same `ServiceEta` output:
   - **A. Maintenance settings, per-item editor** (`MaintenanceSettingsScreen.maintenanceNextDueSubtext`) — append "≈ due <date>" to each item's "Next due in N km / N days" line. Most informative; lives on a screen the rider visits deliberately. **Plan assumes this (Task 3).**
   - **B. Home `BikeHealthCard` worst-item caption** (`captionFor`) — append the ETA to the single worst item shown on home. Highest visibility; one line, glanceable. **Plan assumes this too (Task 4).**
   - **C. A new standalone "Next service" home tile/card** — most prominent, but net-new surface and competes with the fuel tile / health card for home real estate. **Plan does NOT do this** — call out if you want it instead of B.
2. **Date format.** Plan assumes a relative phrase ("~18 days") plus an absolute date ("≈ 24 Jun") for the editor (B uses the relative phrase only, to fit one line). Confirm locale/format (the codebase has no shared date formatter for future dates that I found — `java.time` formatting is introduced fresh here).
3. **Pace window.** Plan uses a **rolling 30-day** km/day pace (`totalsFor(rides, 30)`). A commuter's 30-day average smooths weekend gaps; a 7-day window would react faster but is noisier for an occasional rider. Confirm 30 days, or specify.
4. **"Rides remaining" variant.** The research title also mentions "~3 rides until oil change." That needs an avg-km-per-ride figure (derivable from `totalsFor(...).km / totalsFor(...).rides`). Plan **defers** this as a second line — confirm if you want it in v1.

---

### Task 1: `ServiceEta` pure forecaster + tests

**Files:**
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEta.kt`
- Test: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEtaTest.kt`

- [ ] **Step 1: Write the failing test**

Create `ServiceEtaTest.kt`. Inputs are hand-built `ServiceItemHealth` (the same struct `ServiceSchedule.healthFor` returns) so the forecaster is exercised in isolation from the schedule math. `now` is fixed so dates don't depend on the wall clock.

```kotlin
package dev.mrwick.gixxerbridge.analytics

import dev.mrwick.gixxerbridge.data.ServiceItem
import dev.mrwick.gixxerbridge.data.ServiceItemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [ServiceEta.forecast]. No Room, no Android, no DataStore.
 * "Now" is fixed so the projected dates are deterministic.
 */
class ServiceEtaTest {

    private val now = 1_750_000_000_000L
    private val msPerDay = 24L * 60L * 60L * 1000L

    private fun health(
        kmRemaining: Int?,
        daysRemaining: Int?,
    ) = ServiceItemHealth(
        state = ServiceItemState(
            item = ServiceItem.PERIODIC_SERVICE,
            kmThreshold = 3500,
            daysThreshold = 120,
            lastServiceDateMs = now,
            lastServiceOdoKm = 1000,
        ),
        daysRemaining = daysRemaining,
        kmRemaining = kmRemaining,
        remainingFraction = 1.0, // not used by the forecaster
    )

    // --- km-gated forecast, calendar still has slack -> km gate drives it ---
    @Test fun kmGateDrivesEtaWhenPaceIsSteady() {
        // 350 km left at 10 km/day => ~35 days. Calendar gate is 120 days, so km wins.
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = 120), kmPerDay = 10.0, now = now)!!
        assertEquals(35, f.daysAway)
        assertEquals(now + 35 * msPerDay, f.dueAtMillis)
        assertEquals(EtaGate.KM, f.gate)
    }

    // --- "calendar wins": projected km-date is later than the days gate ---
    @Test fun calendarWinsWhenKmProjectionIsLater() {
        // 1000 km left at 10 km/day => 100 days by km; but only 40 days left on the
        // calendar gate. min(100, 40) = 40 => calendar wins.
        val f = ServiceEta.forecast(health(kmRemaining = 1000, daysRemaining = 40), kmPerDay = 10.0, now = now)!!
        assertEquals(40, f.daysAway)
        assertEquals(now + 40 * msPerDay, f.dueAtMillis)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    // --- zero-km window: div-by-zero guard -> calendar-only ---
    @Test fun zeroPaceFallsBackToCalendarOnly() {
        // No riding in the window => km projection is undefined; use the days gate.
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = 55), kmPerDay = 0.0, now = now)!!
        assertEquals(55, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    @Test fun negativePaceTreatedAsZero() {
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = 55), kmPerDay = -3.0, now = now)!!
        assertEquals(55, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    // --- no calendar gate (e.g. odo-only baseline / null days): km gate only ---
    @Test fun kmOnlyWhenNoCalendarGate() {
        val f = ServiceEta.forecast(health(kmRemaining = 100, daysRemaining = null), kmPerDay = 10.0, now = now)!!
        assertEquals(10, f.daysAway)
        assertEquals(EtaGate.KM, f.gate)
    }

    // --- no km gate (brake-oil style days-only item): calendar gate only ---
    @Test fun calendarOnlyWhenNoKmGate() {
        val f = ServiceEta.forecast(health(kmRemaining = null, daysRemaining = 30), kmPerDay = 10.0, now = now)!!
        assertEquals(30, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
    }

    // --- nothing to forecast: no km gate, no days gate -> null ---
    @Test fun noGatesIsUnavailable() {
        assertNull(ServiceEta.forecast(health(kmRemaining = null, daysRemaining = null), kmPerDay = 10.0, now = now))
    }

    // --- zero pace AND no calendar gate -> nothing projectable -> null ---
    @Test fun zeroPaceWithKmOnlyIsUnavailable() {
        assertNull(ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = null), kmPerDay = 0.0, now = now))
    }

    // --- already overdue on km: clamp to "due now" (0 days), don't go negative ---
    @Test fun overdueKmClampsToZeroDays() {
        val f = ServiceEta.forecast(health(kmRemaining = -200, daysRemaining = 40), kmPerDay = 10.0, now = now)!!
        assertEquals(0, f.daysAway)
        assertEquals(EtaGate.KM, f.gate)
        assertTrue(f.isOverdue)
    }

    // --- already overdue on calendar: clamp to "due now" ---
    @Test fun overdueCalendarClampsToZeroDays() {
        val f = ServiceEta.forecast(health(kmRemaining = 350, daysRemaining = -5), kmPerDay = 10.0, now = now)!!
        assertEquals(0, f.daysAway)
        assertEquals(EtaGate.CALENDAR, f.gate)
        assertTrue(f.isOverdue)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.ServiceEtaTest"`
Expected: FAIL — compilation error, `ServiceEta` / `ServiceEtaForecast` / `EtaGate` unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `ServiceEta.kt`. Note the model: km-projection days = `ceil(kmRemaining / kmPerDay)` (clamped ≥ 0); compare to the calendar `daysRemaining`; the **smaller** non-null candidate wins (calendar wins ties at equal days — see `<=` below); `gate` records which one. `isOverdue` is true when the winning candidate was ≤ 0 before clamping.

```kotlin
package dev.mrwick.gixxerbridge.analytics

import kotlin.math.ceil

/** Which gate produced the displayed ETA. */
enum class EtaGate { KM, CALENDAR }

/**
 * A projected service due-date for one item.
 *
 * [daysAway] is clamped to ≥ 0 (an overdue item is "due now", never negative);
 * [isOverdue] preserves the "already past" signal for the UI. [gate] says whether
 * the rider's km pace or the calendar threshold is the binding constraint.
 */
data class ServiceEtaForecast(
    val daysAway: Int,
    val dueAtMillis: Long,
    val gate: EtaGate,
    val isOverdue: Boolean,
)

/**
 * Projects a calendar ETA for a service item from its [ServiceItemHealth] gates
 * (km-remaining + days-remaining) and the rider's recent daily km pace.
 *
 * Model: the km gate is converted to days via the pace
 * (`days = ceil(kmRemaining / kmPerDay)`); the calendar gate is `daysRemaining`
 * as-is. The binding constraint is whichever falls **sooner** — "calendar wins"
 * means a slow rider's far-off km date is capped by the time-based threshold.
 *
 * Div-by-zero guard: when [kmPerDay] ≤ 0 (no riding in the pace window) the km
 * projection is undefined and we fall back to the calendar gate alone. If neither
 * gate is projectable (no km gate / no recent rides AND no days gate) returns null.
 *
 * Pure JVM, deterministic — tested in ServiceEtaTest.
 */
object ServiceEta {

    private const val MS_PER_DAY: Long = 24L * 60L * 60L * 1000L

    fun forecast(
        health: ServiceItemHealth,
        kmPerDay: Double,
        now: Long = System.currentTimeMillis(),
    ): ServiceEtaForecast? {
        // km candidate: days until the km gate is hit at the current pace.
        // Undefined when there's no km gate, or pace is non-positive / non-finite.
        val kmDays: Int? = health.kmRemaining?.let { kmLeft ->
            if (kmPerDay <= 0.0 || kmPerDay.isNaN() || kmPerDay.isInfinite()) return@let null
            ceil(kmLeft / kmPerDay).toInt()
        }
        val calDays: Int? = health.daysRemaining

        // Pick the sooner gate. Calendar wins ties (<=) and is the fallback when
        // km is undefined (zero-pace window).
        val (rawDays, gate) = when {
            kmDays != null && calDays != null ->
                if (calDays <= kmDays) calDays to EtaGate.CALENDAR else kmDays to EtaGate.KM
            kmDays != null -> kmDays to EtaGate.KM
            calDays != null -> calDays to EtaGate.CALENDAR
            else -> return null
        }

        val isOverdue = rawDays <= 0
        val daysAway = rawDays.coerceAtLeast(0)
        return ServiceEtaForecast(
            daysAway = daysAway,
            dueAtMillis = now + daysAway * MS_PER_DAY,
            gate = gate,
            isOverdue = isOverdue,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.ServiceEtaTest"`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEta.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEtaTest.kt
git commit -m "feat(service): pure ServiceEta forecaster (calendar-date ETA) with tests"
```

---

### Task 2: Pace helper + ETA formatter (pure) + tests

A small piece of glue both UI surfaces share: turn a `List<RideEntity>` into a km/day pace, and turn a `ServiceEtaForecast` into display strings. Keeping these pure means they are unit-tested rather than visually eyeballed.

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEta.kt` (add `paceKmPerDay` + format helpers)
- Modify: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEtaTest.kt` (add cases)

- [ ] **Step 1: Add failing tests for the pace helper + formatter**

Append to `ServiceEtaTest.kt` (inside the class):

```kotlin
    @Test fun paceIsThirtyDayKmAverage() {
        // RideAnalytics.totalsFor over 30 days = 300 km => 10.0 km/day.
        // Build two rides inside the window summing to 300 km.
        val rides = listOf(
            rideOf(id = 1, startedDaysAgo = 2, startOdo = 1000, distanceKm = 200),
            rideOf(id = 2, startedDaysAgo = 10, startOdo = 1200, distanceKm = 100),
        )
        assertEquals(10.0, ServiceEta.paceKmPerDay(rides, now = now), 0.0001)
    }

    @Test fun paceIsZeroWhenNoRecentRides() {
        val stale = listOf(rideOf(id = 1, startedDaysAgo = 90, startOdo = 1000, distanceKm = 500))
        assertEquals(0.0, ServiceEta.paceKmPerDay(stale, now = now), 0.0001)
    }

    @Test fun formatRelativeReadsDaysAway() {
        val f = ServiceEtaForecast(daysAway = 18, dueAtMillis = now, gate = EtaGate.KM, isOverdue = false)
        assertEquals("~18 days", ServiceEta.formatRelative(f))
    }

    @Test fun formatRelativeOverdueReadsDueNow() {
        val f = ServiceEtaForecast(daysAway = 0, dueAtMillis = now, gate = EtaGate.CALENDAR, isOverdue = true)
        assertEquals("due now", ServiceEta.formatRelative(f))
    }
```

Add this ride factory helper inside the class (mirrors `RideAnalyticsTest`'s `ride(...)`):

```kotlin
    private fun rideOf(id: Long, startedDaysAgo: Long, startOdo: Int, distanceKm: Int) =
        dev.mrwick.gixxerbridge.data.RideEntity(
            id = id,
            startedAtMillis = now - startedDaysAgo * msPerDay,
            endedAtMillis = now - startedDaysAgo * msPerDay + 30 * 60_000L,
            startOdoKm = startOdo,
            endOdoKm = startOdo + distanceKm,
            maxSpeedKmh = 60,
            avgSpeedKmh = 35.0,
            sampleCount = 10,
            fuelBarsStart = 4,
            fuelBarsEnd = 3,
        )
```

> Confirm `RideEntity`'s constructor params before implementing — they are taken from `RideAnalyticsTest.kt:34-46`. If the field set has drifted, match the real entity.

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.ServiceEtaTest"`
Expected: FAIL — `paceKmPerDay`, `formatRelative` unresolved.

- [ ] **Step 3: Implement the helpers in `ServiceEta.kt`**

Add an import at the top:

```kotlin
import dev.mrwick.gixxerbridge.data.RideEntity
```

Add inside `object ServiceEta`:

```kotlin
    /** Rolling [windowDays]-day km/day pace, from logged rides. 0.0 when none. */
    fun paceKmPerDay(
        rides: List<RideEntity>,
        windowDays: Long = DEFAULT_PACE_WINDOW_DAYS,
        now: Long = System.currentTimeMillis(),
    ): Double {
        if (windowDays <= 0L) return 0.0
        val km = RideAnalytics.totalsFor(rides, days = windowDays, now = now).km
        return km.toDouble() / windowDays.toDouble()
    }

    /** Relative phrase, e.g. "~18 days" or "due now" when overdue/at-zero. */
    fun formatRelative(f: ServiceEtaForecast): String =
        if (f.isOverdue || f.daysAway <= 0) "due now"
        else if (f.daysAway == 1) "~1 day"
        else "~${f.daysAway} days"

    /** Default pace window — see plan open-question #3 (30 days, user-tunable). */
    const val DEFAULT_PACE_WINDOW_DAYS: Long = 30L
```

> **Date formatting note:** an absolute-date formatter (e.g. "≈ 24 Jun") is intentionally **left to the UI layer** in Tasks 3-4 using `java.time` + the device locale, not added here — formatting a localized date is presentation, and keeping `ServiceEta` free of locale/zone makes it cleanly testable. If the human prefers a pure absolute-date formatter, add `formatDueDate(f, zone, locale)` here with its own test instead.

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.analytics.ServiceEtaTest"`
Expected: PASS (15 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEta.kt android/app/src/test/kotlin/dev/mrwick/gixxerbridge/analytics/ServiceEtaTest.kt
git commit -m "feat(service): pace-from-rides + ETA relative formatter with tests"
```

---

### Task 3: Surface the ETA in the per-item maintenance editor

**Decision dependency:** assumes open-question #1A (yes, show on the per-item editor) and #2 (relative + absolute date).

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/settings/MaintenanceSettingsScreen.kt`

No unit test (Compose). Verified by compile + on-device. The pure logic it calls is already covered by Task 1-2.

- [ ] **Step 1: Pull rides into the screen for pace**

`MaintenanceSettingsScreen` currently has no ride source. Add one via `AppGraph.rideStore` (the pattern `BikeHealthCard.kt:54` uses). Add imports:

```kotlin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import dev.mrwick.gixxerbridge.analytics.ServiceEta
import dev.mrwick.gixxerbridge.app.AppGraph
```

Near the top of the composable body (after `currentOdoKm`), add:

```kotlin
    val ctx = LocalContext.current
    val rideStore = remember(ctx) { AppGraph.rideStore(ctx) }
    val rides by rideStore.observeRides().collectAsStateWithLifecycle(initialValue = emptyList())
    val paceKmPerDay = remember(rides) { ServiceEta.paceKmPerDay(rides) }
```

- [ ] **Step 2: Thread pace into the per-item editor + compute the forecast**

Pass `paceKmPerDay` into `MaintenanceServiceItemEditor`:

```kotlin
                    MaintenanceServiceItemEditor(
                        state = state,
                        currentOdoKm = currentOdoKm,
                        paceKmPerDay = paceKmPerDay,
                        onUpdateThresholds = { km, days -> vm.setServiceItemThresholds(item, km, days) },
                        onMarkServiced = { vm.markServiceDone(item, currentOdoKm) },
                    )
```

In `MaintenanceServiceItemEditor`, add the `paceKmPerDay: Double` param, and after the existing `health` computation:

```kotlin
    val eta = remember(health, paceKmPerDay) {
        ServiceEta.forecast(health, paceKmPerDay)
    }
```

- [ ] **Step 3: Render the ETA line under the existing "Next due in …" subtext**

Below the existing subtext `Text(...)`, add an ETA line (only when an `eta` and a pace exist — no pace means the figure would be calendar-only, which the existing days subtext already shows, so suppress to avoid redundancy unless the gate is KM):

```kotlin
        if (eta != null && paceKmPerDay > 0.0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = etaLine(eta),
                style = MaterialTheme.typography.labelSmall,
                color = if (eta.isOverdue) GixxerTokens.danger
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
```

Add this file-private helper (absolute date via `java.time`, device default zone/locale):

```kotlin
private fun etaLine(eta: ServiceEtaForecast): String {
    if (eta.isOverdue) return "Forecast: due now"
    val date = Instant.ofEpochMilli(eta.dueAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))
    val gateNote = if (eta.gate == EtaGate.CALENDAR) " (calendar gate)" else " at your pace"
    return "Forecast: ${ServiceEta.formatRelative(eta)} ≈ $date$gateNote"
}
```

Add imports for the helper:

```kotlin
import dev.mrwick.gixxerbridge.analytics.EtaGate
import dev.mrwick.gixxerbridge.analytics.ServiceEtaForecast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
```

- [ ] **Step 4: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Build, install, visually verify**

Run: `cd android && ./gradlew :app:installDebug`
Expected: `Installed on 1 device.` Open Settings → Maintenance: each item with a baseline shows a "Forecast: ~N days ≈ <date>" line under its "Next due in …" subtext. Items where the calendar is the binding gate read "(calendar gate)"; km-driven items read "at your pace". With no recent rides the forecast line is hidden (the days subtext still shows).

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/settings/MaintenanceSettingsScreen.kt
git commit -m "feat(maintenance): per-item service ETA forecast line (calendar wins)"
```

---

### Task 4: Surface the ETA in the home `BikeHealthCard` worst-item caption

**Decision dependency:** assumes open-question #1B (yes, show on home for the worst item). Skip this task if the human chooses A-only.

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/BikeHealthCard.kt`

No unit test (Compose). Verified by compile + on-device + the existing `HomeScreenshotTest` continuing to pass.

- [ ] **Step 1: Compute pace + the worst-item forecast in `BikeHealthCard`**

`BikeHealthCard` already collects `rides` (line 56) and computes `scheduleHealth` (line 65). After `scheduleHealth`, add:

```kotlin
    val paceKmPerDay = remember(rides) { ServiceEta.paceKmPerDay(rides) }
    val worstEta = remember(scheduleHealth, paceKmPerDay) {
        scheduleHealth.worst?.let { ServiceEta.forecast(it, paceKmPerDay) }
    }
```

Add imports:

```kotlin
import dev.mrwick.gixxerbridge.analytics.ServiceEta
import dev.mrwick.gixxerbridge.analytics.ServiceEtaForecast
```

- [ ] **Step 2: Fold the ETA into the caption**

`captionFor(worst)` builds "Engine oil — 320 km left / 18 days left". Change the home call to also pass the forecast, and append the relative ETA when pace is known. Replace `val caption = captionFor(scheduleHealth.worst)` with:

```kotlin
    val caption = captionFor(scheduleHealth.worst, if (paceKmPerDay > 0.0) worstEta else null)
```

Update `captionFor`'s signature + body:

```kotlin
private fun captionFor(worst: ServiceItemHealth?, eta: ServiceEtaForecast?): String? {
    if (worst == null) return null
    val parts = mutableListOf<String>()
    worst.kmRemaining?.let { parts += if (it >= 0) "$it km left" else "${-it} km overdue" }
    worst.daysRemaining?.let { parts += if (it >= 0) "$it days left" else "${-it} days overdue" }
    if (parts.isEmpty()) return null
    val base = "${worst.state.item.label} — " + parts.joinToString(" / ")
    return if (eta != null) "$base · ${ServiceEta.formatRelative(eta)}" else base
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the home screenshot test (regression)**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "dev.mrwick.gixxerbridge.ui.home.HomeScreenshotTest"`
Expected: PASS, or a deliberate golden update if the caption now renders an ETA in the fixture. If the fixture has no rides, pace is 0 and the caption is unchanged (no ETA), so the golden should be unaffected — confirm before re-recording.

- [ ] **Step 5: Build, install, visually verify**

Run: `cd android && ./gradlew :app:installDebug`
Expected: home Bike-health card's worst-item caption reads e.g. "Engine oil — 320 km left / 60 days left · ~32 days" when there are recent rides; reverts to the plain caption with no rides.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/home/BikeHealthCard.kt
git commit -m "feat(home): append service ETA to bike-health worst-item caption"
```

---

## Self-Review

**Spec coverage (research Top Picks #7):**
- Static km-remaining → calendar-date ETA via daily-km pace → Task 1 (`forecast`) + Task 2 (`paceKmPerDay`) ✓
- "Calendar wins" (`min(km-projection, daysRemaining)`) → Task 1, `calendarWinsWhenKmProjectionIsLater` test ✓
- Zero-km-window div-by-zero guard → calendar-only → Task 1, `zeroPaceFallsBackToCalendarOnly` / `negativePaceTreatedAsZero` tests ✓
- Shown side-by-side with the existing gate → Task 3 (editor subtext) + Task 4 (home caption) ✓
- Pure analytics JVM-unit-tested → Tasks 1-2 (15 tests) ✓; UI verified by build + on-device ✓
- Deferred ("~3 rides") → open question #4, not built ✓

**No schema migration:** confirmed — no entity touched, no Room column added. Pace recomputes from existing `RideEntity` rows; gates recompute from `ServiceItemState` (DataStore) + live odo. `fallbackToDestructiveMigration` history-wipe risk avoided. ✓

**Placeholder scan:** No TBD/TODO in code; all steps have concrete code + commands. The two "confirm before implementing" notes (RideEntity ctor in Task 2, golden re-record in Task 4) are verification gates, not placeholders. ✓

**Type consistency:** `ServiceEta.forecast(health: ServiceItemHealth, kmPerDay: Double, now: Long): ServiceEtaForecast?` and `ServiceEtaForecast(daysAway, dueAtMillis, gate, isOverdue)` and `EtaGate{KM,CALENDAR}` used identically across Task 1 (impl + test), Task 3, Task 4. `ServiceItemHealth` fields (`kmRemaining`, `daysRemaining`, `state`, `remainingFraction`) match `analytics/ServiceSchedule.kt:9-22`. `RideAnalytics.totalsFor(rides, days, now).km` matches `RideAnalytics.kt:28` + `WeeklyTotal.km` (`AnalyticsModels.kt:14`). `AppGraph.rideStore(ctx)` + `observeRides()` match `BikeHealthCard.kt:54-56` / `RideStore.kt:276`.

**Honesty caveats (no-assumptions rule):**
- The ETA inherits the pace model's coarseness: a 30-day average km/day applied linearly. It will read confidently even when the rider's habits change; the "~" prefix and "at your pace" label keep it framed as an estimate, not a fact.
- `kmRemaining` itself depends on a **live or last-known odometer** (`ServiceSchedule.healthFor` returns null km when odo is unknown). On a bike never connected, the km gate is absent and the forecast is calendar-only — correct, but means the headline value only firms up after one BLE connection. **This is the only on-bike-dependent aspect; everything else is verifiable purely on the JVM.**
- The forecaster is fully testable without the physical bike. On-device steps (Tasks 3-4 install/visual) confirm placement/rendering only, not the arithmetic.
