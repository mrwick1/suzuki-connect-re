# Maneuver-ID rework: Mappls ID vs cluster byte

**Date:** 2026-05-25
**Status:** design, ready for plan

## Problem

During the 18:48 ride on 2026-05-25, the cluster rendered turn arrows but
all of them were wrong — left when the route called for right, etc.
Separately, the in-app Maneuver Sweep dev tool sent NavFrames that left
the wire successfully (`BikeBridge: writer: TX composer type=0x31` per
`captures/ride-20260525-190341.log:1851+`) but the cluster did not change.

Root cause, established by reading `decompiled/jadx-retry/.../A0.java:458`
(`A0.C(com.mappls.sdk.navigation.model.a aVar)`):

> The Mappls maneuver-ID and the byte the cluster expects are **not the
> same integer**. The OEM app runs a hand-rolled translation table in
> `A0.C()` that maps `aVar.f` (Mappls ID 0–75) → `this.e0` (cluster byte
> 1–52) before assembling the a531 frame.

Our `ManeuverMap.kt` skipped that translation entirely. It output Mappls
IDs and wrote them directly into NavFrame byte 2. The cluster therefore
rendered whatever its ROM has at that *Mappls-numbered* slot, which is
not the glyph we intended.

The `ic_step_N.xml` drawables in `apk/base.apk` — the source of
`docs/maneuver-id-table.md` — are Mappls-indexed and are rendered by the
**phone-side nav strip widget** (`C0897z.java:62-64`), *not* by the
cluster ROM. Calling that table "the cluster's icon set" was an
unverified assumption (No-Assumptions rule violation; recording in
DISCOVERIES.md).

## Goal

Make `gixxerbridge` produce the *cluster bytes* the cluster ROM expects,
by porting the OEM translation table verbatim and applying it between
our text classifier and the wire.

## Non-goals

- `f0` blink animation (alert flash). Not part of the wrong-arrow pain.
- `g0` next-step preview byte. Secondary UX.
- `k0`. Confirmed to be a weather code, not nav-related.

## Architecture

Two-stage pipeline. Stage 1 stays ours (heuristic); Stage 2 is the
OEM-verified table.

```
Google Maps text
   │
   ▼
[Stage 1] MapplsIdGuesser.fromText(s) → Int  // Mappls ID 0..75 (heuristic)
   │
   ▼
[Stage 2] ManeuverMap.mapplsIdToClusterByte(id, vehicleModel) → Int  // cluster byte 1..52
   │
   ▼
NavFrame byte 2  → cluster
```

Rationale:
- Stage 1 may need tuning over time (Maps text varies); Stage 2 is the
  OEM's authoritative table and rarely changes.
- Mirrors the OEM internal structure, which makes future captures
  diffable against ours.
- Sweeping cluster bytes for the dev tool only exercises Stage 2.

### Stage 2 — translation table (verbatim from `A0.C()`)

For vehicles **not** in `{e-ACCESS, Access-TFT Edition, Burgman
Street-TFT Edition, Access}` and `BTID` not containing `SBS51` — i.e.
the default branch the Gixxer SF 150 falls under:

| Mappls aVar.f | Cluster e0 |
|--------------:|-----------:|
| 0             | 1          |
| 1             | 2          |
| 2             | 3          |
| 3             | 4          |
| 4             | 5          |
| 5             | 6          |
| 6             | 7          |
| 7             | 8          |
| 8, 9, 10      | 9          |
| 11            | 11         |
| 12            | 12         |
| 13            | 13         |
| 14            | 14         |
| 15            | 31         |
| 16            | 32         |
| 17            | 29         |
| 18            | 30         |
| 19            | 27         |
| 20            | 28         |
| 21            | 33         |
| 22            | 34         |
| 23            | 35         |
| 24            | 36         |
| 25            | 37         |
| 26, 27, 28    | 31         |
| 30, 31        | 32         |
| 36            | *no-op*   (e0 retains previous value) |
| 41            | 39         |
| 50            | 40         |
| 51            | 41         |
| 52            | 42         |
| 53            | 15         |
| 54            | 16         |
| 55            | 17         |
| 56            | 18         |
| 57            | 19         |
| 58            | 46         |
| 59            | 47         |
| 60            | 48         |
| 61            | 49         |
| 62            | 50         |
| 63            | 51         |
| 64            | 52         |
| 65            | 20         |
| 66            | 21         |
| 67            | 22         |
| 68            | 23         |
| 69            | 24         |
| 70            | 25         |
| 71            | 26         |
| 72            | 45         |
| 73            | 38         |
| 74            | 44         |
| 75            | 10         |

For the e-ACCESS / Burgman / Access / SBS51 branch (preserved for
correctness, *not* the Gixxer's path):

| Mappls aVar.f | Cluster e0 |
|--------------:|-----------:|
| 58            | 44         |
| 74            | 38         |

…with all other rows identical to the default branch.

Unmapped Mappls IDs (e.g. 29, 32–35, 37–40, 42–49) — leave the cluster
byte at its previous value (same semantics as OEM, which does nothing in
the else fallthrough). The pure function returns `null` (or a sentinel
the caller treats as "no change") for those.

## Components

### `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/MapplsIdGuesser.kt` (new)

Extracted from the current `ManeuverMap.fromText`. Pure function:
`fun fromText(instruction: String?): Int` returning Mappls ID 0..75.
No semantic changes — just a rename + file move so Stage 1 has its
own home.

### `android/app/src/main/kotlin/dev/mrwick/gixxerbridge/nav/ManeuverMap.kt` (rewritten)

Now holds Stage 2 only:

```kotlin
object ManeuverMap {
    const val DEFAULT_CLUSTER_BYTE = 8  // OEM translates Mappls 7 (straight/head)
                                        // to cluster 8, so this is what the cluster
                                        // expects when we want "just show a forward
                                        // arrow"; the actual glyph for byte 8 is
                                        // confirmed by the empirical sweep.

    /** Cluster byte (1..52) for the given Mappls ID + vehicle model.
     *  Returns null when the Mappls ID has no cluster mapping (cluster
     *  keeps showing its previous glyph; matches OEM behavior). */
    fun mapplsIdToClusterByte(mapplsId: Int, vehicleModel: String?): Int? { ... }

    /** Convenience: text → cluster byte in one call. Returns DEFAULT_CLUSTER_BYTE
     *  on unmapped input so callers always get a renderable byte. */
    fun fromText(instruction: String?, vehicleModel: String?): Int { ... }
}
```

The bitmap-hash persistence (`registerBitmapHash` / `fromBitmapHash` /
`initPersistence`) currently in `ManeuverMap.kt` is unchanged — it lives
alongside the new mapping function. The hash table is independent of
the byte translation.

### `ManeuverClassifier.kt`

Single behavioral change: where it currently emits a Mappls ID into the
navMux, it now calls `ManeuverMap.mapplsIdToClusterByte(id, vehicleModel)`
first and emits the cluster byte. `vehicleModel` is read once from
settings/store at classifier construction; default is `null` → falls
into the default branch of the table.

### `ManeuverSweepScreen.kt` (reworked)

- Iterate cluster bytes **1–52**, not Mappls IDs 0–75.
- **Remove** the in-app `Icon(painter = painterResource(ic_step_N))` —
  those drawables are Mappls-indexed and would be misleading next to a
  cluster byte. Replace with just the byte number and a free-text
  description field that the user fills in based on what the cluster
  actually shows.
- **Send the byte at 1 Hz for a configurable burst window** (default 5s)
  instead of one-shot. The ride proves continuous nav frames work; the
  earlier one-shot URGENT may not be enough for the cluster's nav-mode
  latch to engage. Burst window stops on its own, so the user can move
  to the next row immediately.
- Persist user-typed glyph descriptions to
  `Context.filesDir/cluster_byte_glyphs.tsv`, append-only, one line per
  byte tested: `<cluster_byte>\t<timestamp>\t<description>`.

### Documentation

- **Rename** `docs/maneuver-id-table.md` → `docs/mappls-id-icons.md`.
  Reframe the prose: this describes Mappls-indexed *phone-side*
  drawables, not cluster icons. Existing table content is correct under
  that framing.
- **New empty** `docs/cluster-byte-glyphs.md`. Will be populated from
  the empirical sweep output as a markdown table.
- **`DISCOVERIES.md`** — new entry: "2026-05-25 Mappls-ID ≠ cluster
  byte. Assumed APK ic_step_N drawables = cluster glyphs. Wrong: those
  are phone-strip widget drawables; cluster has its own ROM. Discovered
  by tracing A0.C() in jadx-retry. Evidence: byte translation if-chain
  at A0.java:458."

### Tests

`ManeuverMapTest.kt` rewritten:
- One assertion per OEM branch (default + e-ACCESS/Burgman) for each
  Mappls ID in the table.
- One end-to-end assertion (`fromText("Turn right toward X", null) == 4`)
  matching what the OEM produced in the ride log.
- Null-input + unknown-Mappls-ID cases.

## Open verifications (do not block landing this change)

1. **Gixxer vehicle_name** — assumed not in the e-ACCESS/Burgman list,
   not containing SBS51. One frida hook on `suzuki.com.suzuki`:

   ```js
   Java.use("okhttp3.internal.platform.d").I.implementation = function () {
     const v = this.I.apply(this, arguments);
     console.log("d.I() =", v);
     return v;
   };
   ```

   Reopens the OEM app after pairing once, logs once, done.

2. **Nav-mode latch** — empirically determine the minimum burst length
   the cluster needs to render a swept byte. Sweep with 5s default; if
   visible, try shrinking; if not, try lengthening.

3. **Cluster byte coverage** — sweep produces one line per byte 1..52.
   Some may be visually identical (cluster collapses; OEM table already
   shows 8/9/10 → 9 collapses, so this is expected). Record what's
   distinct vs. collapsed.

## Risks

- **Vehicle-name assumption wrong** — if the Gixxer actually identifies
  as something in the e-ACCESS/Burgman list, the wrong branch fires for
  IDs 58 and 74. Mitigated by frida check before completing the sweep.
- **Burst send racing the live nav pipeline** — if a real navigation is
  active during a sweep, navMux will overwrite the swept byte on its
  next emit. Sweep should only be used when nav is idle (note this in
  the dev screen prose).

## Acceptance

- A real ride with Google Maps navigating, post-fix: the cluster arrow
  matches the spoken instruction for each of {turn left, turn right,
  slight left, slight right, sharp left, sharp right, u-turn,
  roundabout, straight} at least once.
- Sweep: every byte 1..52 produces an entry in
  `cluster_byte_glyphs.tsv`, and a glyph table in
  `docs/cluster-byte-glyphs.md` is populated from it.
- Unit tests cover both OEM branches for every Mappls ID.

## Out of scope (future work)

- f0 blink alternation during cluster alerts.
- g0 next-step preview byte.
- Adding bytes 53+ to the table (none observed in OEM bytecode for the
  default branch; revisit if a sweep surfaces unmapped cluster glyphs).
- Migrating the existing bitmap-hash classifier
  (`registerBitmapHash`/`fromBitmapHashNearest`) — that machinery is
  preserved as-is, unaffected by this change.
