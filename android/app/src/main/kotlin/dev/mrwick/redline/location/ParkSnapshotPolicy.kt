package dev.mrwick.redline.location

/** A location fix obtained at park time: coordinates plus when that fix was taken. */
data class LocFix(val lat: Double, val lng: Double, val atMillis: Long)

/** The values to persist for a park event. */
data class ParkWrite(
    val lat: Double,
    val lng: Double,
    /** When the bike was parked (always "now" at key-off). */
    val parkedAtMillis: Long,
    /** When the location fix backing [lat]/[lng] was taken — may be old if reused. */
    val locAtMillis: Long,
)

/**
 * Decides what to persist when the bike disconnects, given the location we could
 * (or couldn't) obtain. Pure and deterministic — no Android, no I/O — so it is
 * unit-tested directly in `src/test`.
 *
 * The park *time* always advances to "now", even with no location. This is the
 * fix for the stale "last parked N days ago" bug: previously a missing GPS fix
 * at key-off made the whole snapshot a no-op, freezing the timestamp at the last
 * successful (often days-old) snapshot.
 */
object ParkSnapshotPolicy {

    /**
     * @param now the disconnect time (becomes the park time).
     * @param fix a location obtained this disconnect — a fresh fix if available,
     *   else the system's last-known location, else null.
     * @param previous the last persisted snapshot, if any.
     *
     * - fix available -> new coords, [ParkWrite.locAtMillis] = the fix's time.
     * - no fix but a [previous] exists -> reuse its (approximate) coords and
     *   keep its location time so the UI can flag the spot as stale; park time
     *   still advances to [now].
     * - no fix and no previous -> null (nothing worth showing yet).
     */
    fun decide(now: Long, fix: LocFix?, previous: LastParked?): ParkWrite? = when {
        fix != null -> ParkWrite(fix.lat, fix.lng, parkedAtMillis = now, locAtMillis = fix.atMillis)
        previous != null -> ParkWrite(previous.lat, previous.lng, parkedAtMillis = now, locAtMillis = previous.locTMillis)
        else -> null
    }
}
