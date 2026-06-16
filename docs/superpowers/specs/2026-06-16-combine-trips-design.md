# Combine Trips — Design

**Date:** 2026-06-16
**Status:** Approved (brainstorm) — pending implementation plan
**Area:** Android app (`dev.mrwick.gixxerbridge`), ride history

## Problem

A single continuous journey gets cut into many separate trips because the app
ends a ride on key-off (10-min watchdog silence). On a 2026-06-14 long ride, one
~328 km direction was recorded as **22 separate segments** (rides id 73–95),
split by traffic-light key-offs and rest stops. The odometer chains perfectly
across them (each segment's `startOdoKm == previous endOdoKm`), so they are
provably one ride.

The rider needs to **combine selected trips into one stretch** — a single Trips
entry representing the whole journey — and be able to undo it later.

### Ground truth (pulled from device, 2026-06-16)

- Source: `gixxer.db` from `dev.mrwick.gixxerbridge.debug` via `run-as`.
- 2026-06-14: 22 rides, odometer 17355 → 17683 = **328 km**, all `odo_gap = 0`
  between consecutive segments (contiguous). Biggest moving stretches: seg 93
  (46 km, vmax 110), seg 94 (51 km, vmax 99).
- **Odometer contiguity is NOT a journey signal.** Verified across the whole DB:
  76 of 78 consecutive rides chain (`startOdoKm == previous endOdoKm`) because the
  bike's odometer simply accumulates — every ride continues where the last ended,
  even days apart. So contiguity alone cannot distinguish a long trip from ordinary
  daily riding. The discriminator is the **inter-ride time gap**: gaps cluster at
  < 30 min (62), 30–90 min (7, rest stops), then jump to 4–24 h (3) and > 24 h (2,
  separate days). A single-day journey is a run of consecutive rides separated only
  by short gaps. Odo-chaining is kept only as a sanity guard (catches a deleted
  middle segment).

## Decisions (from brainstorm)

1. **Merge model:** true merge — the selected segments become one ride entry.
2. **Undo:** persistent split-back (reversible days later), not just a snackbar.
3. **Merged indicator:** the merged trip's detail view shows it is merged and
   lists its child segments.
4. **Selection rule:** contiguous-only. Reject merges where the odometer doesn't
   chain.
5. **Entry point:** long-press a Trips row to enter multi-select mode.
6. **Row time:** each trip row shows its clock start time, plus a "gap hint"
   connector between consecutive rows of the same short-gap run (e.g.
   "⋮ 15 min later"), so segments of one journey read as a visually linked chain.
7. **Suggested merges:** the Trips list shows a dismissible banner when it detects
   a clear long journey — a run of ≥ 3 consecutive segments, each inter-ride gap
   under ~2 h, totalling ≥ 80 km. Tapping it opens multi-select with the run
   pre-ticked for one-tap confirmation. Thresholds are tunable in dev settings.

## Architecture

The merge is **true merge at the presentation layer, non-destructive at storage**.
A new *parent* ride row carries the journey's aggregates and is the only thing
visible in lists/stats. The original *child* segment rows — and their samples and
GPS tracks — stay in the database, just flagged as absorbed and hidden. This is
what makes split-back lossless and persistent without re-parenting sample data.

### Data model change — Room v4 → v5

Add two columns to the `rides` table (`RideEntity`):

| Column          | Type    | Meaning                                                        |
|-----------------|---------|----------------------------------------------------------------|
| `parentRideId`  | `Long?` | NULL for normal rides and for merged parents; = parent id for absorbed child segments. |
| `isMerged`      | `Bool`  | `true` marks a merged-parent row. Lets the detail view show the merged banner without an extra child-count query. |

```kotlin
data class RideEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
    val startOdoKm: Int,
    val endOdoKm: Int?,
    val maxSpeedKmh: Int,
    val avgSpeedKmh: Double,
    val sampleCount: Int,
    val fuelBarsStart: Int?,
    val fuelBarsEnd: Int?,
    val name: String? = null,
    val parentRideId: Long? = null,   // NEW
    val isMerged: Boolean = false,    // NEW
)
```

**Migration is mandatory and must be non-destructive.** `GixxerDatabase` currently
builds with `fallbackToDestructiveMigration()` (GixxerDatabase.kt:57), so a bare
version bump would *delete the rider's existing 328 km ride and all history*. The
implementation MUST add a real migration:

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE rides ADD COLUMN parentRideId INTEGER")
        db.execSQL("ALTER TABLE rides ADD COLUMN isMerged INTEGER NOT NULL DEFAULT 0")
    }
}
// builder: .addMigrations(MIGRATION_4_5).fallbackToDestructiveMigration()
```

`fallback` stays for any older/unforeseen jump, but the 4→5 path is explicit so
existing rows survive. Bump `@Database(version = 5)`.

### Merge operation — `RideStore.mergeRides(childIds: List<Long>): Long`

1. Load the selected rides. **Flatten any already-merged trip** back to its leaf
   segments (a selection may include a merged trip — this lets the rider add a
   missed segment, or combine two merged trips, without splitting first). Remember
   the superseded parent ids for deletion. The resulting leaf set is sorted
   ascending by `startedAtMillis`; a parent's children are always original leaf
   segments, never nested parents.
2. **Validate contiguity:** for each adjacent leaf pair require
   `next.startOdoKm == current.endOdoKm` and non-overlapping times. (A stray hidden
   child id is rejected; in-progress rides are rejected.) On failure, return a typed
   result the UI maps to an explanatory message; make no changes.
   On success the new parent is created and the superseded parent rows are deleted
   in the same transaction (leaves are re-parented before the old parents are
   dropped, so no leaf is orphaned — `parentRideId` is not a FK).
3. **Create the parent** `RideEntity`:
   - `startedAtMillis` = first.start, `endedAtMillis` = last.end
   - `startOdoKm` = first.startOdoKm, `endOdoKm` = last.endOdoKm
     (distance = last.endOdoKm − first.startOdoKm)
   - `maxSpeedKmh` = max across children
   - `avgSpeedKmh` = recomputed over **moving** samples (`speedKmh > 0`) unioned
     across all children — matches the existing `RideStore.endRide` convention
     (RideStore.kt:238-239), not a naive average of per-segment averages.
   - `sampleCount` = sum of children
   - `fuelBarsStart` = first.fuelBarsStart, `fuelBarsEnd` = last.fuelBarsEnd
   - `name` = auto-generated, editable (e.g. `"Sunday ride · 328 km"`)
   - `isMerged = true`, `parentRideId = null`
4. **Absorb children:** set `parentRideId = parent.id` on each selected ride. Their
   samples and GPS rows are untouched (stay attached to the child ids).
5. Return the new parent id (for the post-merge snackbar / navigation).

The whole operation runs in a single Room transaction.

### Split-back — `RideStore.splitMerge(parentId: Long)`

1. Set `parentRideId = NULL` on all children of `parentId`.
2. Delete the parent row (it owns no samples/locations of its own, so the cascade
   removes nothing of value).

The original segments reappear exactly as before. Reversible at any time.

### Hiding absorbed children

Change the two shared read queries to return top-level rows only:

```sql
SELECT * FROM rides WHERE parentRideId IS NULL ORDER BY startedAtMillis DESC
```

This applies to `RideDao.observeRides()` and `getAllRides()` (RideStore.kt:105,109).
Because every list/stat consumer goes through these — Trips list, Home totals,
Stats, Wrapped, month summary, route clustering (12 call sites verified) — the
absorbed children disappear everywhere at once with **no per-consumer edits and no
double-counting**. The merged parent (`parentRideId IS NULL`) shows normally and
carries the full journey distance.

Add a dedicated read for the detail/split paths:

```kotlin
@Query("SELECT * FROM rides WHERE parentRideId = :parentId ORDER BY startedAtMillis ASC")
suspend fun getChildren(parentId: Long): List<RideEntity>
```

### Parent detail view (`TripDetailScreen`)

When `ride.isMerged`:
- Show a banner: *"Merged from N segments"*.
- Show an expandable list of child segments (start time + km each), loaded via
  `getChildren`.
- Show a **"Split back into segments"** action calling `splitMerge`.
- Charts, GPX export, and CSV export aggregate the children: new store methods
  union samples / locations across `getChildren(parentId)` ordered by `tMillis`.
  (A merged parent has no samples under its own id.)

### UI entry point (`TripsScreen`)

- **Long-press** a trip row → enter multi-select mode (row checkboxes, a selection
  count in the app bar).
- A bottom action bar shows **Combine**, enabled only when ≥ 2 rows are selected.
- Tap Combine → call `mergeRides`. On contiguity failure show the explanatory
  message and stay in select mode. On success exit select mode and show a
  confirmation snackbar with a quick **Undo** (→ `splitMerge`). The durable
  reversal path is the detail-screen Split button (per decision 2).

### Trip row time + gap-hint connector (`RideRow` + `TripsScreen`)

Each `RideRow` gains a clock start time (`HH:mm`, device locale/zone) shown beside
the existing "duration · avg" subtitle.

Between rows, `TripsScreen` renders a thin **gap-hint connector** linking two
consecutive segments of the same run. The list is newest-first, so for each row the
"older neighbour" is the next item down. The screen computes, per ride, the gap to
the chronologically-previous ride (`thisRide.startedAtMillis − olderRide.endedAtMillis`)
and whether the odometer chains. When that gap is in `[0, GAP_HINT_MAX]` (default
2 h) and the odo chains, it renders a small connector item below the row reading
`"⋮ {n} min later"` (or `"{h} h {m} min later"`). Connectors only appear within a
week group (no connector spanning a `WeekSectionHeader`).

This is pure presentation — computed in the screen from the already-loaded
`ridesWithMeta`, no store or schema change.

### Suggested merges (`JourneyDetector` + banner)

A pure function detects "clear long journeys" from the top-level ride list:

```kotlin
data class JourneySuggestion(
    val rideIds: List<Long>,   // chronological, ≥ 3
    val totalKm: Int,
    val startMillis: Long,     // first segment start (also the dismissal key)
    val endMillis: Long,       // last segment end
)

object JourneyDetector {
    fun detect(rides: List<RideEntity>, cfg: JourneyConfig): List<JourneySuggestion>
}

data class JourneyConfig(
    val gapMaxMin: Int = 120,   // max inter-ride gap inside a run
    val minSegments: Int = 3,   // run must have at least this many segments
    val minTotalKm: Int = 80,   // run must cover at least this far
)
```

Algorithm: sort rides ascending by start; walk them accumulating a run while the
next ride's gap (`next.start − cur.end`) is `≤ gapMaxMin` **and** the odometer
chains (`next.startOdoKm == cur.endOdoKm`); otherwise close the run and start a new
one. Emit a `JourneySuggestion` for every closed run with `size ≥ minSegments` and
`totalKm ≥ minTotalKm`. `totalKm = last.endOdoKm − first.startOdoKm`.

**Banner + flow.** `TripsViewModel` exposes the most recent non-dismissed
suggestion. `TripsScreen` shows a dismissible banner ("🔗 N trips on {date} look
like one {km} km journey — Review & combine"). Tapping **Review & combine** enters
multi-select with that run's ride ids **pre-ticked**; the rider confirms via the
same Combine action as a manual merge (contiguity re-validated). Tapping **dismiss**
records the suggestion's `startMillis` so it won't reappear. After a successful
merge the run's children are hidden, so the suggestion naturally stops surfacing.

**Dismissal persistence.** A small DataStore set of dismissed `startMillis` longs
(`JourneyDismissStore`, same side-store pattern as `RideMeta`). Survives restarts;
not tied to Room.

**Dev-settings tunables.** `gapMaxMin`, `minSegments`, `minTotalKm` are added to the
`Settings` DataStore with the defaults above and exposed in `DeveloperSettingsScreen`
so the detector can be tuned without a rebuild. `GAP_HINT_MAX` for the row connector
reuses `gapMaxMin`.

### Metadata (favourite / tags / note)

`RideMeta` is keyed by `startedAtMillis` (RideMeta side-store, survives
migrations). The parent's `startedAtMillis` equals the first child's start, so they
share the same meta key. Since only one of {parent, first-child} is ever visible at
a time, the favourite/tags/note carry up to the merged view and return to the first
segment after split-back with no special handling.

## Testing

In-memory Room test (Robolectric, alongside existing `app/src/test`):
- Contiguity validation: accepts a chained set, rejects a set with an odometer gap.
- Merge aggregates: parent km / maxSpeed / sampleCount / fuel endpoints correct;
  `avgSpeedKmh` equals the moving-sample mean across children.
- Children hidden: after merge, `observeRides()` excludes children and includes the
  parent.
- Split-back: restores the exact child rows; `observeRides()` matches pre-merge.

Reuse a fixture mirroring the real 2026-06-14 chain (a few contiguous segments with
known odometers) so the test reflects the actual scenario.

Plain-JVM (`test/`) unit tests, no Room:
- `JourneyDetector`: detects the 2026-06-14-style run; ignores a short errand day
  (gaps small but total < `minTotalKm`); splits a run at a > `gapMaxMin` gap; respects
  `minSegments`; breaks a run where the odometer doesn't chain.
- Gap-hint formatting: `"15 min later"`, `"1 h 5 min later"`.
- Merged-ride auto-name format.

## Out of scope

- Merging across non-contiguous odometer (explicitly rejected — sanity guard only).
- Editing/trimming segment boundaries.
- Auto-*performing* a merge. Detection only suggests; the rider always confirms.
