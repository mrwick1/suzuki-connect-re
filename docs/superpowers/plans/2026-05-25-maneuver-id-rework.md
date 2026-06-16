# Maneuver-ID Rework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every BLE NavFrame write a *cluster byte*, not a Mappls maneuver ID, by porting the OEM `A0.C()` translation table verbatim.

**Architecture:** Two stages. Stage 1 (`MapplsIdGuesser`, our heuristic) maps Google Maps text → Mappls ID 0..75. Stage 2 (`ManeuverMap.mapplsIdToClusterByte`, OEM-ported) maps Mappls ID → cluster byte 1..52. Translation applied at the `GoogleMapsParser → ParsedNavData` boundary so everything downstream is cluster bytes.

**Tech Stack:** Kotlin / Android. Gradle. JUnit 4 unit tests. Jetpack Compose for the dev sweep UI.

**Spec:** `docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md`

## Parallel Execution Plan

Three batches; tasks within a batch are independent and can run in parallel subagents:

- **Batch 1** (3-way parallel): Task 1 (Stage 2 + tests), Task 2 (docs), Task 3 (sweep rework)
- **Batch 2** (single): Task 4 (pipeline rewire + cleanup) — depends on Task 1

---

## Task 1: Stage 2 translation (Mappls ID → cluster byte) + unit tests

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMap.kt`
- Modify: `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMapTest.kt`

This task **adds new code alongside the existing code**. The existing `fromText` heuristic and `GENERIC_ARROW = 7` constant remain untouched so this task can land without breaking the current pipeline. Task 4 does the actual cleanup.

- [ ] **Step 1: Write failing tests for the OEM default-branch table**

Replace the entire contents of `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMapTest.kt` with:

```kotlin
package dev.mrwick.gixxerbridge.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the OEM-ported Mappls-ID -> cluster-byte translation in
 * [ManeuverMap.mapplsIdToClusterByte]. Source of truth: A0.C() in the
 * decompiled Suzuki Connect APK (see docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md).
 *
 * The "default" branch is what our Gixxer SF 150 hits; the "Burgman" branch
 * is preserved for correctness on the small set of bikes the OEM special-cases.
 */
class ManeuverMapTest {

    @Test
    fun `default branch table — straight maneuvers (0..7)`() {
        assertEquals(1, ManeuverMap.mapplsIdToClusterByte(0, null))
        assertEquals(2, ManeuverMap.mapplsIdToClusterByte(1, null))
        assertEquals(3, ManeuverMap.mapplsIdToClusterByte(2, null))
        assertEquals(4, ManeuverMap.mapplsIdToClusterByte(3, null))
        assertEquals(5, ManeuverMap.mapplsIdToClusterByte(4, null))
        assertEquals(6, ManeuverMap.mapplsIdToClusterByte(5, null))
        assertEquals(7, ManeuverMap.mapplsIdToClusterByte(6, null))
        assertEquals(8, ManeuverMap.mapplsIdToClusterByte(7, null))
    }

    @Test
    fun `default branch — 8,9,10 collapse to cluster byte 9`() {
        assertEquals(9, ManeuverMap.mapplsIdToClusterByte(8, null))
        assertEquals(9, ManeuverMap.mapplsIdToClusterByte(9, null))
        assertEquals(9, ManeuverMap.mapplsIdToClusterByte(10, null))
    }

    @Test
    fun `default branch — 26,27,28 collapse to 31`() {
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(26, null))
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(27, null))
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(28, null))
    }

    @Test
    fun `default branch — 30,31 collapse to 32`() {
        assertEquals(32, ManeuverMap.mapplsIdToClusterByte(30, null))
        assertEquals(32, ManeuverMap.mapplsIdToClusterByte(31, null))
    }

    @Test
    fun `default branch — keep-lane and turn variants`() {
        assertEquals(11, ManeuverMap.mapplsIdToClusterByte(11, null))
        assertEquals(12, ManeuverMap.mapplsIdToClusterByte(12, null))
        assertEquals(13, ManeuverMap.mapplsIdToClusterByte(13, null))
        assertEquals(14, ManeuverMap.mapplsIdToClusterByte(14, null))
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(15, null))
        assertEquals(32, ManeuverMap.mapplsIdToClusterByte(16, null))
        assertEquals(29, ManeuverMap.mapplsIdToClusterByte(17, null))
        assertEquals(30, ManeuverMap.mapplsIdToClusterByte(18, null))
        assertEquals(27, ManeuverMap.mapplsIdToClusterByte(19, null))
        assertEquals(28, ManeuverMap.mapplsIdToClusterByte(20, null))
        assertEquals(33, ManeuverMap.mapplsIdToClusterByte(21, null))
        assertEquals(34, ManeuverMap.mapplsIdToClusterByte(22, null))
        assertEquals(35, ManeuverMap.mapplsIdToClusterByte(23, null))
        assertEquals(36, ManeuverMap.mapplsIdToClusterByte(24, null))
        assertEquals(37, ManeuverMap.mapplsIdToClusterByte(25, null))
    }

    @Test
    fun `default branch — compass departures 50-57`() {
        assertEquals(40, ManeuverMap.mapplsIdToClusterByte(50, null))
        assertEquals(41, ManeuverMap.mapplsIdToClusterByte(51, null))
        assertEquals(42, ManeuverMap.mapplsIdToClusterByte(52, null))
        assertEquals(15, ManeuverMap.mapplsIdToClusterByte(53, null))
        assertEquals(16, ManeuverMap.mapplsIdToClusterByte(54, null))
        assertEquals(17, ManeuverMap.mapplsIdToClusterByte(55, null))
        assertEquals(18, ManeuverMap.mapplsIdToClusterByte(56, null))
        assertEquals(19, ManeuverMap.mapplsIdToClusterByte(57, null))
    }

    @Test
    fun `default branch — roundabouts 58-72`() {
        assertEquals(46, ManeuverMap.mapplsIdToClusterByte(58, null))
        assertEquals(47, ManeuverMap.mapplsIdToClusterByte(59, null))
        assertEquals(48, ManeuverMap.mapplsIdToClusterByte(60, null))
        assertEquals(49, ManeuverMap.mapplsIdToClusterByte(61, null))
        assertEquals(50, ManeuverMap.mapplsIdToClusterByte(62, null))
        assertEquals(51, ManeuverMap.mapplsIdToClusterByte(63, null))
        assertEquals(52, ManeuverMap.mapplsIdToClusterByte(64, null))
        assertEquals(20, ManeuverMap.mapplsIdToClusterByte(65, null))
        assertEquals(21, ManeuverMap.mapplsIdToClusterByte(66, null))
        assertEquals(22, ManeuverMap.mapplsIdToClusterByte(67, null))
        assertEquals(23, ManeuverMap.mapplsIdToClusterByte(68, null))
        assertEquals(24, ManeuverMap.mapplsIdToClusterByte(69, null))
        assertEquals(25, ManeuverMap.mapplsIdToClusterByte(70, null))
        assertEquals(26, ManeuverMap.mapplsIdToClusterByte(71, null))
        assertEquals(45, ManeuverMap.mapplsIdToClusterByte(72, null))
    }

    @Test
    fun `default branch — motorway exits 73,74,75 and u-turn 41`() {
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(73, null))
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(74, null))
        assertEquals(10, ManeuverMap.mapplsIdToClusterByte(75, null))
        assertEquals(39, ManeuverMap.mapplsIdToClusterByte(41, null))
    }

    @Test
    fun `Burgman branch — 58 and 74 diverge from default`() {
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "Burgman Street-TFT Edition"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "Burgman Street-TFT Edition"))

        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "e-ACCESS"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "e-ACCESS"))

        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "Access-TFT Edition"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "Access-TFT Edition"))

        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "Access"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "Access"))
    }

    @Test
    fun `Burgman branch — all other rows match default`() {
        assertEquals(1, ManeuverMap.mapplsIdToClusterByte(0, "Burgman Street-TFT Edition"))
        assertEquals(4, ManeuverMap.mapplsIdToClusterByte(3, "Burgman Street-TFT Edition"))
        assertEquals(45, ManeuverMap.mapplsIdToClusterByte(72, "Burgman Street-TFT Edition"))
        assertEquals(10, ManeuverMap.mapplsIdToClusterByte(75, "Burgman Street-TFT Edition"))
    }

    @Test
    fun `unmapped Mappls IDs return null (cluster keeps previous glyph)`() {
        assertNull(ManeuverMap.mapplsIdToClusterByte(29, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(32, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(33, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(40, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(42, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(45, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(76, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(255, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(-1, null))
    }

    @Test
    fun `Mappls 36 (ferry) returns null — OEM leaves e0 untouched`() {
        assertNull(ManeuverMap.mapplsIdToClusterByte(36, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(36, "Burgman Street-TFT Edition"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'dev.mrwick.gixxerbridge.nav.ManeuverMapTest'`
Expected: FAIL with "Unresolved reference: mapplsIdToClusterByte" — function doesn't exist yet.

- [ ] **Step 3: Implement the table**

Append to `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMap.kt`, **before the closing `}` of `object ManeuverMap`**:

```kotlin
    // ------------------------------------------------------------------
    // Stage 2: Mappls maneuver ID -> Suzuki cluster byte.
    //
    // Ported verbatim from the if-chain in A0.C() at jadx-retry/.../A0.java:458.
    // The Mappls SDK populates an internal maneuverID (0..75) into AdviseInfo.f;
    // A0.C() then translates that to the byte that is actually written to a531
    // byte 2. The two integers are not the same — see the design spec for
    // background.
    //
    // Default branch covers all bikes except {e-ACCESS, Access-TFT Edition,
    // Burgman Street-TFT Edition, Access} and anything whose BTID contains
    // "SBS51". Our Gixxer SF 150 falls in the default branch.
    // ------------------------------------------------------------------

    private val BURGMAN_LIKE_MODELS = setOf(
        "e-ACCESS",
        "Access-TFT Edition",
        "Burgman Street-TFT Edition",
        "Access",
    )

    /**
     * Translate a Mappls maneuver ID to the cluster byte that goes in a531 byte 2.
     *
     * @param mapplsId the Mappls maneuver ID (typically 0..75) from
     *     [com.mappls.sdk.navigation.model.a.f]
     * @param vehicleModel the bike's vehicle_name (as the OEM stored it after
     *     pairing); null means use the default branch (Gixxer behavior).
     * @return cluster byte 1..52, or null if the Mappls ID has no defined
     *     translation. Null means "leave the cluster showing whatever glyph it
     *     was last sent" — matches the OEM behavior of leaving e0 untouched in
     *     the fallthrough branches.
     */
    fun mapplsIdToClusterByte(mapplsId: Int, vehicleModel: String?): Int? {
        val isBurgmanLike = vehicleModel != null && vehicleModel in BURGMAN_LIKE_MODELS
        return when (mapplsId) {
            0 -> 1
            1 -> 2
            2 -> 3
            3 -> 4
            4 -> 5
            5 -> 6
            6 -> 7
            7 -> 8
            8, 9, 10 -> 9
            11 -> 11
            12 -> 12
            13 -> 13
            14 -> 14
            15 -> 31
            16 -> 32
            17 -> 29
            18 -> 30
            19 -> 27
            20 -> 28
            21 -> 33
            22 -> 34
            23 -> 35
            24 -> 36
            25 -> 37
            26, 27, 28 -> 31
            30, 31 -> 32
            41 -> 39
            50 -> 40
            51 -> 41
            52 -> 42
            53 -> 15
            54 -> 16
            55 -> 17
            56 -> 18
            57 -> 19
            58 -> if (isBurgmanLike) 44 else 46
            59 -> 47
            60 -> 48
            61 -> 49
            62 -> 50
            63 -> 51
            64 -> 52
            65 -> 20
            66 -> 21
            67 -> 22
            68 -> 23
            69 -> 24
            70 -> 25
            71 -> 26
            72 -> 45
            73 -> 38
            74 -> if (isBurgmanLike) 38 else 44
            75 -> 10
            // Mappls 36 (ferry) and 29 / 32..35 / 37..40 / 42..49 / 76+:
            // OEM leaves e0 at its prior value. We return null so the caller
            // can decide (typically: don't update the maneuver byte on this tick).
            else -> null
        }
    }
```

- [ ] **Step 4: Run tests — all pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'dev.mrwick.gixxerbridge.nav.ManeuverMapTest'`
Expected: PASS (12 tests).

- [ ] **Step 5: Run the full unit test suite to confirm nothing else broke**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL with all tests passing.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMap.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMapTest.kt
git commit -m "feat(nav): add Stage 2 Mappls→cluster-byte translation table"
```

---

## Task 2: Documentation updates (rename + new file + discovery log)

**Files:**
- Rename: `docs/maneuver-id-table.md` → `docs/mappls-id-icons.md`
- Create: `docs/cluster-byte-glyphs.md`
- Modify: `DISCOVERIES.md`

No code changes. Fully independent of Tasks 1, 3, 4.

- [ ] **Step 1: Rename the existing doc**

Run: `git mv docs/maneuver-id-table.md docs/mappls-id-icons.md`

- [ ] **Step 2: Reframe the prose at the top of the renamed file**

Edit `docs/mappls-id-icons.md`. Replace the title and intro paragraphs (lines 1-13) with:

```markdown
# Mappls maneuver-ID → phone-side step icon table

Source: `apk/base.apk:res/drawable-nodpi-v4/ic_step_*.xml`.

**⚠️ These drawables are NOT what the bike's cluster renders.** They are the
phone-side nav-strip icons that the Suzuki Connect app draws inside its own
turn-by-turn widget (see `C0897z.java:62-64`, `imageView.setImageResource(...)`).
The cluster has its own icon ROM keyed by a *cluster byte*, which is produced
by translating the Mappls ID via `A0.C()` in the OEM bytecode. See
`docs/cluster-byte-glyphs.md` for the cluster-byte table.

The translation Mappls-ID → cluster-byte is implemented in
`ManeuverMap.mapplsIdToClusterByte` and verified against the OEM if-chain at
`decompiled/jadx-retry/.../A0.java:458`.

Decoded with: androguard 4.1.3 (`androguard.core.axml.AXMLPrinter`).
Rendered with: rsvg-convert (librsvg) from hand-converted SVG.
PNG outputs at `/tmp/step-icons/png/ic_step_N.png` (not checked in).
Analysis date: 2026-05-25.
```

Leave the rest of the file (icon table, confidence notes) intact — its content
is correct under the corrected framing.

- [ ] **Step 3: Create the empty cluster-byte glyph table**

Create `docs/cluster-byte-glyphs.md` with this content:

```markdown
# Suzuki cluster byte → on-cluster glyph table

This table is filled in **empirically** by running the dev Maneuver Sweep
(`ManeuverSweepScreen`) on the bike with the cluster powered on, sending each
byte in turn, and recording what the cluster ROM actually renders.

**Authoritative source:** the rendered cluster glyph itself. Photo
documentation lives at `captures/cluster-glyphs/byte-NN.jpg` (gitignored).

**OEM translation source:** `A0.C()` at
`decompiled/jadx-retry/sources/com/suzuki/application/fragment/A0.java:458`
produces these cluster bytes from Mappls maneuver IDs. See also
`docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md`.

## Coverage so far

| Cluster byte | OEM-known Mappls source IDs | Cluster renders | Notes |
|-------------:|:----------------------------|:----------------|:------|
| 1            | Mappls 0 (turn left 90°)    | _empty_         | _to fill in_ |
| 2            | Mappls 1 (slight left)      | _empty_         | _to fill in_ |
| 3            | Mappls 2 (sharp left)       | _empty_         | _to fill in_ |
| 4            | Mappls 3 (turn right 90°)   | _empty_         | _to fill in_ |
| 5            | Mappls 4 (slight right)     | _empty_         | _to fill in_ |
| 6            | Mappls 5 (sharp right)      | _empty_         | _to fill in_ |
| 7            | Mappls 6 (u-turn)           | _empty_         | _to fill in_ |
| 8            | Mappls 7 (straight/head)    | _empty_         | _to fill in_ |
| 9            | Mappls 8,9,10 (destination/arrival cluster) | _empty_ | _to fill in_ |
| 10           | Mappls 75 (highway exit right) | _empty_      | _to fill in_ |
| 11           | Mappls 11 (keep left)       | _empty_         | _to fill in_ |
| 12           | Mappls 12 (keep right)      | _empty_         | _to fill in_ |
| 13           | Mappls 13 (T-junction left) | _empty_         | _to fill in_ |
| 14           | Mappls 14 (T-junction right) | _empty_        | _to fill in_ |
| 15           | Mappls 53 (compass SE)      | _empty_         | _to fill in_ |
| 16           | Mappls 54 (compass S)       | _empty_         | _to fill in_ |
| 17           | Mappls 55 (compass SW)      | _empty_         | _to fill in_ |
| 18           | Mappls 56 (compass W)       | _empty_         | _to fill in_ |
| 19           | Mappls 57 (compass NW)      | _empty_         | _to fill in_ |
| 20           | Mappls 65 (roundabout SE CCW) | _empty_       | _to fill in_ |
| 21           | Mappls 66 (roundabout W CCW)  | _empty_       | _to fill in_ |
| 22           | Mappls 67 (roundabout NE CCW) | _empty_       | _to fill in_ |
| 23           | Mappls 68 (roundabout N CCW)  | _empty_       | _to fill in_ |
| 24           | Mappls 69 (roundabout NE high CCW) | _empty_  | _to fill in_ |
| 25           | Mappls 70 (roundabout E CCW)  | _empty_       | _to fill in_ |
| 26           | Mappls 71 (roundabout SE CCW alt) | _empty_   | _to fill in_ |
| 27           | Mappls 19 (merge left)        | _empty_       | _to fill in_ |
| 28           | Mappls 20 (merge right)       | _empty_       | _to fill in_ |
| 29           | Mappls 17 (exit left)         | _empty_       | _to fill in_ |
| 30           | Mappls 18 (exit right)        | _empty_       | _to fill in_ |
| 31           | Mappls 15, 26-28 (fork left) | _empty_        | _to fill in_ |
| 32           | Mappls 16, 30, 31 (fork right) | _empty_      | _to fill in_ |
| 33           | Mappls 21 (straight w/ crossbar) | _empty_    | _to fill in_ |
| 34           | Mappls 22 (u-turn wide left)  | _empty_       | _to fill in_ |
| 35           | Mappls 23 (slight left ramp)  | _empty_       | _to fill in_ |
| 36           | Mappls 24 (u-turn wide right) | _empty_       | _to fill in_ |
| 37           | Mappls 25 (slight right ramp) | _empty_       | _to fill in_ |
| 38           | Mappls 73 (motorway exit left) — Burgman: also 74 | _empty_ | _to fill in_ |
| 39           | Mappls 41 (u-turn right)      | _empty_       | _to fill in_ |
| 40           | Mappls 50 (depart N)          | _empty_       | _to fill in_ |
| 41           | Mappls 51 (depart NE)         | _empty_       | _to fill in_ |
| 42           | Mappls 52 (depart E)          | _empty_       | _to fill in_ |
| 44           | Mappls 74 (motorway exit right) — Burgman: 58 also | _empty_ | _to fill in_ |
| 45           | Mappls 72 (roundabout generic) | _empty_      | _to fill in_ |
| 46           | Mappls 58 (roundabout SE CW)  | _empty_       | _to fill in_ |
| 47           | Mappls 59 (roundabout E CW)   | _empty_       | _to fill in_ |
| 48           | Mappls 60 (roundabout NE high CW) | _empty_   | _to fill in_ |
| 49           | Mappls 61 (roundabout N CW)   | _empty_       | _to fill in_ |
| 50           | Mappls 62 (roundabout W CW)   | _empty_       | _to fill in_ |
| 51           | Mappls 63 (roundabout SW CW)  | _empty_       | _to fill in_ |
| 52           | Mappls 64 (roundabout SW CW alt) | _empty_    | _to fill in_ |

Cluster bytes 43 and 53+ are not produced by any Mappls ID in the OEM default
branch. Sweep them too; they may render distinct glyphs (cluster ROM has its
own slots independent of Mappls coverage).
```

- [ ] **Step 4: Add the discovery log entry**

Append to `DISCOVERIES.md`:

```markdown

## 2026-05-25 — Mappls maneuver ID ≠ Suzuki cluster byte

**Wrong assumption:** The Mappls SDK's maneuver ID (the integer that drives
the phone-side nav-strip widget) is the same integer that the bike's cluster
ROM uses to look up its turn-arrow glyph.

**Reality:** They are different integers. The OEM Suzuki Connect app runs a
hand-rolled translation in `A0.C()` (`com.suzuki.application.fragment.A0`)
that maps Mappls IDs (0..75) to a separate cluster-byte space (1..52). The
cluster has its own icon ROM indexed by the translated byte; the
`ic_step_N.xml` drawables in `apk/base.apk` are *not* what the cluster shows
— those are phone-side widgets rendered inside the Suzuki Connect UI.

**Symptom that surfaced the bug:** During the 2026-05-25 18:48 ride with
GixxerBridge driving the BLE, every turn arrow on the cluster pointed the
wrong way. Separately, the in-app `ManeuverSweep` dev tool sent NavFrames
(confirmed via writer log `BikeBridge: writer: TX composer type=0x31`) but
the cluster never changed across the swept IDs.

**Evidence that established the truth:** Decompiled the OEM app with
`jadx --no-imports --use-dx --deobf` (jadx-retry output). Read
`decompiled/jadx-retry/sources/com/suzuki/application/fragment/A0.java:458`
(`public final void C(com.mappls.sdk.navigation.model.a aVar)`) — a 200-line
if-chain that switches on `aVar.f` (the Mappls maneuver ID per the
`model.a.toString()` label `maneuverID=`) and assigns `this.e0` (the byte
that ends up in NavFrame byte 2 via `A0.D(int i, ...)` at A0.java:455).

**Resolution:** See spec
`docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md` and the
implementation plan
`docs/superpowers/plans/2026-05-25-maneuver-id-rework.md`. Stage 2
translation added at `ManeuverMap.mapplsIdToClusterByte`; pipeline rewired
to apply it at the `GoogleMapsParser → ParsedNavData` boundary so everything
downstream is cluster bytes. The `docs/maneuver-id-table.md` file is renamed
to `docs/mappls-id-icons.md` to reflect what it actually describes.

**Lesson:** Wireshark labels mislabeled UUIDs in M0. APK drawables mislabeled
themselves as cluster icons in M5. In both cases, the No-Assumptions rule
applies: tool-derived labels are heuristic until you trace them to source.
"`ic_step_N.xml` exists therefore the cluster renders it" was an inference,
not an observation.
```

- [ ] **Step 5: Commit**

```bash
git add docs/mappls-id-icons.md docs/cluster-byte-glyphs.md DISCOVERIES.md
git commit -m "docs: rename Mappls-icon table + cluster-byte template + discovery log"
```

---

## Task 3: ManeuverSweepScreen rework — cluster bytes + burst + free-text

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/dev/ManeuverSweepScreen.kt`

Independent of Tasks 1, 2, 4. The sweep operates over cluster bytes 1..52
directly (no Mappls translation needed — the sweep IS testing what cluster
bytes look like).

- [ ] **Step 1: Replace the entire file**

Overwrite `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/dev/ManeuverSweepScreen.kt` with:

```kotlin
package dev.mrwick.gixxerbridge.ui.dev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Empirical verification tool for the Suzuki cluster's byte→glyph table.
 *
 * Iterates cluster bytes 1..52 (the output range of `A0.C()` in the OEM
 * decompile; see `docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md`).
 * For each byte, sends an a531 NavFrame at 1 Hz for [BURST_SECONDS] seconds
 * so the cluster's nav-mode latch has time to engage (single-shot writes
 * during 2026-05-25 sweep failed to update the cluster — see DISCOVERIES.md).
 *
 * **Use only when no real navigation is active.** A live NavMux will overwrite
 * the swept byte on its next emit.
 *
 * Observations: type the description of what the cluster actually rendered
 * into the row's text field. Observations persist to a TSV at
 * `Context.filesDir/cluster_byte_glyphs.tsv` so they survive process death.
 * Pull via `adb shell run-as dev.mrwick.gixxerbridge.debug cat files/cluster_byte_glyphs.tsv`.
 */
@Composable
fun ManeuverSweepScreen() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var lastSent by remember { mutableStateOf<String?>(null) }
    val glyphNotes = remember { mutableStateMapOf<Int, String>() }
    val tsvFile = remember { File(ctx.filesDir, GLYPH_TSV_FILENAME) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Cluster byte sweep",
            style = MaterialTheme.typography.titleLarge,
            color = GixxerTokens.textPrimary,
        )
        Text(
            "Sends each cluster byte 1..52 to a531 byte 2 at 1 Hz for ${BURST_SECONDS}s. " +
                "Watch the cluster; type what you see. Use ONLY when no nav is active.",
            style = MaterialTheme.typography.bodySmall,
            color = GixxerTokens.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        lastSent?.let {
            Text(
                "Last sent: $it",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.accent,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(CLUSTER_BYTES, key = { it }) { byte ->
                ClusterByteRow(
                    byte = byte,
                    note = glyphNotes[byte] ?: "",
                    onNoteChange = { txt ->
                        glyphNotes[byte] = txt
                        // Persist last-write-wins per byte. Append a line — loader
                        // (future tool) takes the last entry for each byte.
                        runCatching {
                            tsvFile.appendText(
                                "$byte\t${System.currentTimeMillis()}\t${txt.replace('\t', ' ').replace('\n', ' ')}\n",
                            )
                        }
                    },
                    onSend = {
                        scope.launch {
                            val ts = java.text.SimpleDateFormat("HH:mm:ss")
                                .format(java.util.Date())
                            lastSent = "byte=$byte burst starting at $ts"
                            AppLog.i("ClusterSweep", "burst start byte=$byte for ${BURST_SECONDS}s")
                            repeat(BURST_SECONDS) {
                                val frame = NavFrame(
                                    maneuverId = byte,
                                    distNext = "0050",
                                    distNextUnit = "M",
                                    eta = "now   ",
                                    distTotal = "0050",
                                    distTotalUnit = "M",
                                    status = "1",
                                    continueFlag = "1",
                                )
                                val ok = AppGraph.sendFrame(frame.encode())
                                AppLog.d("ClusterSweep", "burst tick byte=$byte ok=$ok")
                                delay(1000L)
                            }
                            lastSent = "byte=$byte burst done"
                            AppLog.i("ClusterSweep", "burst done byte=$byte")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ClusterByteRow(
    byte: Int,
    note: String,
    onNoteChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(byte),
            style = MaterialTheme.typography.titleMedium,
            color = GixxerTokens.textPrimary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            placeholder = { Text("what does the cluster show?") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onSend,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) { Text("Send") }
    }
}

private const val BURST_SECONDS = 5
private const val GLYPH_TSV_FILENAME = "cluster_byte_glyphs.tsv"

/** Cluster bytes the OEM emits in A0.C()'s default branch (1..52). Plus 43
 *  and 53 are included as "is the cluster ROM hiding something here?" checks. */
private val CLUSTER_BYTES: List<Int> = (1..53).toList()
```

- [ ] **Step 2: Build and verify**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the unit test suite to confirm nothing broke**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/dev/ManeuverSweepScreen.kt
git commit -m "feat(dev): cluster-byte sweep — 1..53 burst send + free-text glyph capture"
```

---

## Task 4: Pipeline rewire + cleanup

**Files:**
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/GoogleMapsParser.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ParsedNavData.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGenerator.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/WelcomeFrame.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMap.kt`
- Modify: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverClassifier.kt`
- Create: `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/MapplsIdGuesser.kt`

**Depends on Task 1** (`ManeuverMap.mapplsIdToClusterByte` must already exist).

This task moves the OEM "vehicle name" assumption into a single source of truth
(`null` → default branch, hardcoded for the Gixxer; can be made a setting later
if needed). The classifier still operates in Mappls-ID space internally (the
hash table is keyed by Mappls IDs); translation happens once at the
`GoogleMapsParser → ParsedNavData` boundary.

- [ ] **Step 1: Create MapplsIdGuesser.kt with the text heuristic**

Create `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/MapplsIdGuesser.kt`:

```kotlin
package dev.mrwick.gixxerbridge.nav

/**
 * Stage 1 of the maneuver pipeline: Google Maps instruction text → Mappls
 * maneuver ID (0..75).
 *
 * This is *our* heuristic — the Mappls SDK isn't on the phone, so we infer
 * what Mappls would have emitted from the human-readable text. Stage 2
 * ([ManeuverMap.mapplsIdToClusterByte]) then converts the Mappls ID to the
 * cluster byte the bike expects.
 *
 * IDs come from the verified table in `docs/mappls-id-icons.md`. Patterns are
 * matched longest-first so e.g. "slight right" wins over "right".
 */
object MapplsIdGuesser {

    /** Mappls ID for "straight / head / continue" — the safe default. */
    const val DEFAULT_MAPPLS_ID = 7

    /**
     * Heuristic text → Mappls ID. Returns [DEFAULT_MAPPLS_ID] for null, empty,
     * or unrecognized input. Matches longest / most-specific pattern first.
     */
    fun fromText(instruction: String?): Int {
        if (instruction.isNullOrBlank()) return DEFAULT_MAPPLS_ID
        val s = instruction.lowercase()
        return when {
            // U-turn: Mappls 6 = U-turn left, 41 = U-turn right. Google Maps
            // says "make a U-turn" without a side — default to 6.
            "u-turn" in s || "u turn" in s || "make a u" in s -> 6

            // Roundabouts: Mappls 72 = generic three-arrow.
            "roundabout" in s -> 72

            // Motorway exits.
            "exit" in s && "left" in s -> 73
            "exit" in s && "right" in s -> 75
            "exit" in s -> 75

            // Slight / sharp.
            "slight right" in s || "bear right" in s -> 4
            "slight left" in s || "bear left" in s -> 1
            "sharp right" in s -> 5
            "sharp left" in s -> 2

            // Keep-lane.
            "keep right" in s -> 12
            "keep left" in s -> 11

            // Merges.
            "merge" in s && "left" in s -> 19
            "merge" in s -> 20

            // Plain turns.
            "turn right" in s || "right onto" in s || "right on " in s -> 3
            "turn left" in s || "left onto" in s || "left on " in s -> 0

            // Continue / straight / head.
            "continue" in s || "straight" in s || "head " in s -> DEFAULT_MAPPLS_ID

            // Departure compass.
            "head north" in s -> 50
            "head northeast" in s || "head north-east" in s -> 51
            "head east" in s -> 52
            "head southeast" in s || "head south-east" in s -> 53
            "head south" in s -> 54
            "head southwest" in s || "head south-west" in s -> 55
            "head west" in s -> 56
            "head northwest" in s || "head north-west" in s -> 57

            // Ferry / tunnel.
            "ferry" in s || "take ferry" in s -> 36
            "tunnel" in s -> 37

            // Arrival.
            "arrive" in s || "destination" in s || "your destination" in s -> 40

            else -> DEFAULT_MAPPLS_ID
        }
    }
}
```

- [ ] **Step 2: Strip the text heuristic from ManeuverMap.kt and add DEFAULT_CLUSTER_BYTE**

In `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMap.kt`, find the existing block starting with the KDoc above `const val GENERIC_ARROW = 7` and the entire `fun fromText(...)` method. Replace from line 23 (`object ManeuverMap {`) up to the end of `fun fromText` (around line 119) with:

```kotlin
object ManeuverMap {

    /**
     * Default cluster byte for "show a forward arrow / generic". Equal to the
     * cluster byte the OEM produces for Mappls ID 7 (straight/head). Used by
     * downstream consumers ([IdleClockGenerator], [WelcomeFrame]) that don't
     * have a Mappls ID and just need a renderable cluster byte.
     */
    const val DEFAULT_CLUSTER_BYTE = 8
```

Leave the rest of the file (`mapplsIdToClusterByte`, `BURGMAN_LIKE_MODELS`,
the bitmap-hash persistence section, all internals) intact.

Note: the old `GENERIC_ARROW = 7` constant is **gone** after this step.
References to it elsewhere are fixed in Step 4.

- [ ] **Step 3: Update ManeuverClassifier to use MapplsIdGuesser**

In `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverClassifier.kt`:

Replace line 73 (`val textId = ManeuverMap.fromText(instruction)`) with:

```kotlin
        val textId = MapplsIdGuesser.fromText(instruction)
```

Replace every occurrence of `ManeuverMap.GENERIC_ARROW` in this file (lines 59, 60, 77, 94, 110) with `MapplsIdGuesser.DEFAULT_MAPPLS_ID`.

Update the KDoc on the file (lines 6-24): change references like "Mappls maneuver-id byte" to clarify the classifier returns a **Mappls ID**, not a cluster byte. Specifically:

Replace the KDoc at line 46-51 with:

```kotlin
    /**
     * Classify the maneuver based on the captured icon [bitmap] and/or the raw
     * [instruction] text. Returns a Mappls maneuver-id (0..75); never null.
     * Callers must apply [ManeuverMap.mapplsIdToClusterByte] before writing
     * the result into a NavFrame.
     *
     * @param selfTrainEnabled if true, register fresh hash→text mappings on
     *   text-fallback decisions. Default false — self-training without verified
     *   cluster-side id semantics has a runaway-pollution failure mode.
     */
```

- [ ] **Step 4: Update IdleClockGenerator + WelcomeFrame defaults**

In `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGenerator.kt`:

Replace both occurrences of `ManeuverMap.GENERIC_ARROW` (lines 49 and 86) with `ManeuverMap.DEFAULT_CLUSTER_BYTE`.

Update the KDoc references at lines 11 and 66 from `[ManeuverMap.GENERIC_ARROW]` to `[ManeuverMap.DEFAULT_CLUSTER_BYTE]`. Also at line 66, change `(no real maneuver)` documentation comment from `(8) — generic, no turn arrow` to `(8) — cluster byte for straight/forward (OEM-translated from Mappls 7)`.

In `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/WelcomeFrame.kt`:

Look at line 75 (`maneuverId = GREETING_MANEUVER_ID`) and the local constant. Change the constant value to `ManeuverMap.DEFAULT_CLUSTER_BYTE` (8) if it isn't already 8; e.g. find the `private const val GREETING_MANEUVER_ID = 1` (or similar literal) and replace it with:

```kotlin
    // Was hardcoded 1 (Mappls-ID space, assumed). After the rework, this is
    // a cluster byte: 8 = the cluster's "straight/forward" glyph (per
    // ManeuverMap.DEFAULT_CLUSTER_BYTE).
    private const val GREETING_MANEUVER_ID = 8
```

Update the assumption comment at line 20 to match:

```kotlin
 * ASSUMED: cluster byte 8 (DEFAULT_CLUSTER_BYTE) is the friendly "default
 * arrow" glyph for the greeting. Verify in the next sweep.
```

- [ ] **Step 5: Update ParsedNavData.maneuverId comment**

In `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ParsedNavData.kt`:

Replace line 18:

```kotlin
    val maneuverId: Int,            // Mappls maneuver id; 8 = generic arrow
```

with:

```kotlin
    val maneuverId: Int,            // Suzuki cluster byte (1..52). 8 = straight/forward.
                                    // Translated from Mappls ID by GoogleMapsParser
                                    // via ManeuverMap.mapplsIdToClusterByte.
```

- [ ] **Step 6: Apply Stage 2 in GoogleMapsParser**

In `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/GoogleMapsParser.kt`, find line 87:

```kotlin
        val maneuverId = ManeuverClassifier.classify(maneuverBitmap, instruction, selfTrain)
```

Replace with:

```kotlin
        val mapplsId = ManeuverClassifier.classify(maneuverBitmap, instruction, selfTrain)
        // Stage 2: translate Mappls ID -> cluster byte. vehicleModel=null means
        // use the default OEM branch (correct for Gixxer SF 150); if/when we
        // need Burgman/Access support, plumb the vehicle_name from settings.
        val maneuverId = ManeuverMap.mapplsIdToClusterByte(mapplsId, null)
            ?: ManeuverMap.DEFAULT_CLUSTER_BYTE
```

Then find line 177 (the other `classify` call):

```kotlin
        val maneuverId = ManeuverClassifier.classify(maneuverBitmap, instruction)
```

Replace with:

```kotlin
        val mapplsId = ManeuverClassifier.classify(maneuverBitmap, instruction)
        val maneuverId = ManeuverMap.mapplsIdToClusterByte(mapplsId, null)
            ?: ManeuverMap.DEFAULT_CLUSTER_BYTE
```

Also update the log line at line 90 (and analogous one near 180 if present)
to show both values for diagnostics. Replace line 90:

```kotlin
            "notif title=\"${title?.take(60)}\" bitmap=${maneuverBitmap != null} -> id=$maneuverId",
```

with:

```kotlin
            "notif title=\"${title?.take(60)}\" bitmap=${maneuverBitmap != null} -> mapplsId=$mapplsId clusterByte=$maneuverId",
```

- [ ] **Step 7: Update IdleClockGeneratorTest if it references GENERIC_ARROW**

Run: `grep -n GENERIC_ARROW android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGeneratorTest.kt 2>/dev/null`

If any matches, replace `ManeuverMap.GENERIC_ARROW` with `ManeuverMap.DEFAULT_CLUSTER_BYTE` (the constant's value changed from 7 to 8 — update any literal `7` expectations to `8` accordingly, since the idle clock should now emit cluster byte 8 for the "no real maneuver" tick).

If there are no matches, this step is a no-op.

- [ ] **Step 8: Build to confirm everything compiles**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Run the full unit test suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL with all tests passing (ManeuverMapTest from Task 1 + any existing tests).

- [ ] **Step 10: Add an end-to-end classifier integration test**

Append to `android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMapTest.kt` (before the closing `}` of the class):

```kotlin
    // ----------------------------------------------------------------
    // End-to-end Stage 1 → Stage 2 spot checks. Confirms our text
    // heuristic + OEM table together produce the expected cluster byte
    // for a small set of canonical Google Maps phrases observed in real
    // ride logs (captures/ride-20260525-190341.log).
    // ----------------------------------------------------------------

    @Test
    fun `end-to-end — turn right text yields cluster byte 4`() {
        val mapplsId = MapplsIdGuesser.fromText("700 m · Turn right toward Nallalam Rd")
        assertEquals(3, mapplsId)
        assertEquals(4, ManeuverMap.mapplsIdToClusterByte(mapplsId, null))
    }

    @Test
    fun `end-to-end — turn left yields cluster byte 1`() {
        val mapplsId = MapplsIdGuesser.fromText("400 m · Turn left onto Beach Road")
        assertEquals(0, mapplsId)
        assertEquals(1, ManeuverMap.mapplsIdToClusterByte(mapplsId, null))
    }

    @Test
    fun `end-to-end — straight yields cluster byte 8 (DEFAULT_CLUSTER_BYTE)`() {
        val mapplsId = MapplsIdGuesser.fromText("Head toward MG Road")
        assertEquals(MapplsIdGuesser.DEFAULT_MAPPLS_ID, mapplsId)
        assertEquals(
            ManeuverMap.DEFAULT_CLUSTER_BYTE,
            ManeuverMap.mapplsIdToClusterByte(mapplsId, null),
        )
    }

    @Test
    fun `end-to-end — u-turn yields cluster byte 7`() {
        val mapplsId = MapplsIdGuesser.fromText("Make a U-turn at the next light")
        assertEquals(6, mapplsId)
        assertEquals(7, ManeuverMap.mapplsIdToClusterByte(mapplsId, null))
    }

    @Test
    fun `end-to-end — roundabout yields cluster byte 45`() {
        val mapplsId = MapplsIdGuesser.fromText("Enter the roundabout")
        assertEquals(72, mapplsId)
        assertEquals(45, ManeuverMap.mapplsIdToClusterByte(mapplsId, null))
    }
```

- [ ] **Step 11: Run the augmented test suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests 'dev.mrwick.gixxerbridge.nav.ManeuverMapTest'`
Expected: PASS (all original tests + 5 new end-to-end tests).

- [ ] **Step 12: Final full-suite check**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 13: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/MapplsIdGuesser.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMap.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverClassifier.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/IdleClockGenerator.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/WelcomeFrame.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ParsedNavData.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/GoogleMapsParser.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMapTest.kt
git commit -m "feat(nav): apply Stage 2 at GoogleMapsParser boundary + extract MapplsIdGuesser"
```

---

## Acceptance

- All unit tests pass (`./gradlew :app:testDebugUnitTest`).
- `assembleDebug` builds cleanly.
- After installing on the phone and starting a real Google Maps navigation:
  the cluster arrow matches the spoken instruction for at least one of each of
  {turn left, turn right, slight left, slight right, u-turn, roundabout,
  straight}. (Verified manually post-merge — not gated by this plan.)
- `cluster_byte_glyphs.tsv` is populated after the first full Maneuver Sweep
  run on the bike. (Manual; not gated by this plan.)
