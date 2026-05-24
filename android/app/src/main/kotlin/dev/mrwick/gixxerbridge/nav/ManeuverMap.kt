package dev.mrwick.gixxerbridge.nav

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Maps Google Maps direction text -> Mappls maneuver IDs (the byte that
 * drives the cluster icon).
 *
 * Icon IDs come from the Suzuki app's available drawable set
 * (NOTES.md "Maneuver-ID -> arrow-icon mapping"):
 * safe IDs: 0,1,2,3,4,5,6,7,8,10-25,36,37,40,41,50-75.
 * Avoid 9, 26-35, 38-39, 42-49 — those gaps render blank/unknown on the cluster.
 *
 * Phase 1 (this file): pattern-match the English instruction text from Maps'
 * nav_description field. The specific Mappls IDs below are educated guesses
 * cross-referenced against `C0897z.java` (the app's adapter remap table) and
 * NOTES.md's enumeration of icons. Each `// ASSUMED:` flags one we couldn't
 * verify directly against a labeled Mappls reference.
 *
 * Phase 2 (later): perceptual-hash the maneuver Bitmap, lookup table built
 * empirically. See [registerBitmapHash] / [fromBitmapHash] stubs.
 */
object ManeuverMap {

    /** Default when nothing matches. */
    const val GENERIC_ARROW = 8

    /**
     * Heuristic text -> maneuver id. Matches longest / most-specific pattern first.
     *
     * Returns [GENERIC_ARROW] for null, empty, or unrecognized input.
     */
    fun fromText(instruction: String?): Int {
        if (instruction.isNullOrBlank()) return GENERIC_ARROW
        val s = instruction.lowercase()
        // Priority-ordered (most specific first).
        return when {
            // U-turn: maneuver 23 is the U-shape icon in the app's available set.
            // ASSUMED: 23 = u-turn — picked from the safe range; verify on cluster.
            "u-turn" in s || "u turn" in s || "make a u" in s -> 23

            // Roundabouts: 71 = generic roundabout (per C0897z.java line 156 fallback);
            // 72 is the Mappls "roundabout" ID itself but C0897z remaps 72→71 unless
            // a specific exit count is set. We use 71 as the only safe text-derived value.
            // ASSUMED: "exit roundabout" text from Maps maps to the same 71.
            "roundabout" in s && "exit" in s -> 71
            "roundabout" in s -> 71

            // Highway exits: 24/25 chosen from the safe range (24-25 cluster).
            // ASSUMED: 24 = take exit left, 25 = take exit right.
            "exit" in s && "right" in s -> 25
            "exit" in s && "left" in s -> 24
            "exit" in s -> 25

            // Slight / sharp turns. ASSUMED: 4-7 are the four diagonal arrows;
            // commonly indexed sharp-left, sharp-right, slight-left, slight-right.
            "slight right" in s -> 7
            "slight left" in s -> 6
            "sharp right" in s -> 5
            "sharp left" in s -> 4

            // Keep-lane variants. ASSUMED: 20/21 are "keep left/right" (lane stay
            // without a turn). These IDs are in the safe band 20-25.
            "keep right" in s -> 21
            "keep left" in s -> 20

            // Plain turn left/right. Per NOTES.md, ic_step_2 and ic_step_3 are the
            // primary turn-left/turn-right arrows used by the app's adapter.
            // ASSUMED: 2 = left, 3 = right (matches typical Mappls convention).
            "turn right" in s || "right onto" in s || "right on " in s -> 3
            "turn left" in s || "left onto" in s || "left on " in s -> 2

            // Continue / straight / head — generic straight arrow.
            "continue" in s || "straight" in s || "head " in s -> GENERIC_ARROW

            // Destination reached. ASSUMED: 50 = "arrive at destination" (flag icon);
            // 50 is the first ID in the 50-75 cluster which contains destination
            // and lane-guidance icons per the app's drawable set.
            "arrive" in s || "destination" in s -> 50

            // Highway merge. ASSUMED: 11 = "merge".
            "merge" in s -> 11

            else -> GENERIC_ARROW
        }
    }

    // -----------------------------------------------------------------------
    // Perceptual-hash table — empirically populated at runtime by
    // [ManeuverClassifier]. Survives process restarts via [initPersistence].
    // -----------------------------------------------------------------------

    private const val TAG = "ManeuverMap"

    /**
     * Filename inside `Context.filesDir` used to persist the hash table.
     * One entry per line: `<hex-hash> <maneuver-id>`. Newest-write-wins.
     */
    internal const val PERSIST_FILENAME = "maneuver_hash_table.tsv"

    // Synchronized via the object's monitor (all accesses go through the object).
    private val bitmapHashToManeuver: MutableMap<Long, Int> = HashMap()

    // File handle becomes non-null after initPersistence(); writes are best-effort
    // append-only so the table survives process death. Null in unit tests.
    @Volatile
    private var persistFile: File? = null

    /**
     * Wire the persisted hash-table file. Idempotent. Safe to call once per
     * application lifetime — e.g. from `Application.onCreate` or the service's
     * `onCreate`. Loads any previously-saved entries into memory.
     *
     * No-op (with a debug log) if persistence was already initialized to the
     * same directory.
     *
     * ASSUMED: `Context.filesDir` is writable and persistent; this matches all
     * Android documentation but a few mis-configured ROMs disagree.
     */
    @Synchronized
    fun initPersistence(context: Context) {
        val file = File(context.filesDir, PERSIST_FILENAME)
        if (persistFile?.absolutePath == file.absolutePath) {
            Log.d(TAG, "initPersistence: already wired to ${file.absolutePath}")
            return
        }
        persistFile = file
        loadFromDisk(file)
    }

    /**
     * Register a perceptual hash -> maneuver-id mapping. Persists the new
     * entry to disk if [initPersistence] has been called.
     *
     * Same hash registered with a different id overwrites the prior mapping
     * in-memory and appends a new line on disk (loader uses last-write-wins).
     */
    @Synchronized
    fun registerBitmapHash(hash: Long, maneuverId: Int) {
        val prior = bitmapHashToManeuver.put(hash, maneuverId)
        if (prior == maneuverId) return
        appendToDisk(hash, maneuverId)
    }

    /** Look up a perceptual hash; returns `null` when no mapping is known. */
    @Synchronized
    fun fromBitmapHash(hash: Long): Int? = bitmapHashToManeuver[hash]

    /**
     * Hamming-tolerant nearest-neighbour lookup. Scans the full table
     * (O(n), n bounded by the small set of distinct Maps turn icons —
     * couple dozen at most), returns the id of the closest hash whose
     * distance is `<= tolerance`, or null if nothing is close enough.
     *
     * Used by [ManeuverClassifier] so anti-aliased / color-variant renders
     * of the same icon still resolve.
     */
    @Synchronized
    fun fromBitmapHashNearest(hash: Long, tolerance: Int): Int? {
        if (bitmapHashToManeuver.isEmpty()) return null
        var bestId: Int? = null
        var bestDist = Int.MAX_VALUE
        for ((k, v) in bitmapHashToManeuver) {
            val d = BitmapHasher.hammingDistance(hash, k)
            if (d < bestDist) {
                bestDist = d
                bestId = v
            }
        }
        return if (bestDist <= tolerance) bestId else null
    }

    /** Test-only: drop in-memory table and detach persistence. */
    @Synchronized
    internal fun resetForTest() {
        bitmapHashToManeuver.clear()
        persistFile = null
    }

    /** Test-only: snapshot of the current in-memory table size. */
    @Synchronized
    internal fun bitmapHashTableSizeForTest(): Int = bitmapHashToManeuver.size

    // ---- persistence internals -------------------------------------------

    private fun loadFromDisk(file: File) {
        if (!file.exists()) return
        try {
            file.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val parts = line.split('\t', ' ', limit = 2)
                    if (parts.size != 2) continue
                    val hash = parts[0].toLongOrNull(16) ?: continue
                    val id = parts[1].trim().toIntOrNull() ?: continue
                    // Last-write-wins: later lines for the same hash overwrite earlier ones.
                    bitmapHashToManeuver[hash] = id
                }
            }
            Log.i(TAG, "Loaded ${bitmapHashToManeuver.size} bitmap-hash entries from ${file.name}")
        } catch (t: Throwable) {
            // Persistence is best-effort; never fail the app over a corrupt cache file.
            Log.w(TAG, "Failed to load hash table from ${file.name}: $t")
        }
    }

    private fun appendToDisk(hash: Long, maneuverId: Int) {
        val file = persistFile ?: return
        try {
            file.appendText("${java.lang.Long.toHexString(hash)}\t$maneuverId\n")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to persist hash entry: $t")
        }
    }
}
