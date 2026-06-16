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

## Decisions (from brainstorm)

1. **Merge model:** true merge — the selected segments become one ride entry.
2. **Undo:** persistent split-back (reversible days later), not just a snackbar.
3. **Merged indicator:** the merged trip's detail view shows it is merged and
   lists its child segments.
4. **Selection rule:** contiguous-only. Reject merges where the odometer doesn't
   chain.
5. **Entry point:** long-press a Trips row to enter multi-select mode.

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

1. Load the selected rides; sort ascending by `startedAtMillis`.
2. **Validate contiguity:** for each adjacent pair require
   `next.startOdoKm == current.endOdoKm` and non-overlapping times. Also require
   every selected ride to be a normal (non-merged, non-child) ride. On failure,
   throw a typed result the UI maps to an explanatory message; make no changes.
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

## Out of scope

- Auto-suggesting merges (detecting a journey automatically). Manual only for now.
- Merging across non-contiguous odometer (explicitly rejected).
- Editing/trimming segment boundaries.
