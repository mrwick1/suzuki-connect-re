package dev.mrwick.gixxerbridge.nav

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Maps Google Maps direction text -> Mappls maneuver IDs (the byte that
 * drives the cluster icon).
 *
 * Icon IDs come from enumerating every `ic_step_N.xml` drawable in
 * `apk/base.apk`. `C0897z.java:81` resolves `step_<N>` via getIdentifier at
 * runtime — whatever file is in the APK is what the cluster renders.
 * Safe IDs: 0-8, 10-25, 36, 37, 40, 41, 50-75.
 * Avoid 9, 26-35, 38-39, 42-49 — those gaps have no drawable.
 *
 * Every assignment below is verified against the decoded+rendered PNG of the
 * corresponding drawable (see docs/maneuver-id-table.md, 2026-05-25).
 *
 * Phase 2 (later): perceptual-hash the maneuver Bitmap, lookup table built
 * empirically. See [registerBitmapHash] / [fromBitmapHash] stubs.
 */
object ManeuverMap {

    /**
     * Default when nothing matches.
     *
     * ID 7 is a plain vertical up-arrow — verified vs ic_step_7.png 2026-05-25.
     * NOTE: The old code used GENERIC_ARROW = 8, but ID 8 is a hollow circle
     * (position marker), NOT a forward arrow. 7 is the correct straight-ahead icon.
     */
    const val GENERIC_ARROW = 7

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
            // U-turn: 6 = U-turn left (downward loop curving left), 41 = U-turn right.
            // Google Maps always says "make a U-turn" without a side — use 6 (left)
            // as the default since most U-turns on Indian roads swing left.
            // verified vs ic_step_6.png + ic_step_41.png 2026-05-25.
            "u-turn" in s || "u turn" in s || "make a u" in s -> 6

            // Roundabouts: 72 = generic three-arrow roundabout symbol (clearest icon).
            // IDs 58-71 are directional (by exit count/angle) but we cannot derive that
            // from text alone. 72 is the unambiguous "roundabout" glyph.
            // verified vs ic_step_72.png 2026-05-25.
            "roundabout" in s -> 72

            // Motorway/highway exits (dual-carriageway off-ramp icons):
            // 73/74 = left exit (two vertical road lines + diagonal-left arrow at top).
            // 75 = right exit (mirror). Better than 17/18 for highway context.
            // verified vs ic_step_73.png + ic_step_75.png 2026-05-25.
            "exit" in s && "left" in s -> 73
            "exit" in s && "right" in s -> 75
            "exit" in s -> 75

            // Slight / sharp turns — verified geometry from rendered PNGs 2026-05-25:
            // 1 = slight left (diagonal lower-left hook), 4 = slight right (lower-right hook).
            // 2 = sharp left (diagonal upper-left, hard corner), 5 = sharp right (upper-right).
            "slight right" in s || "bear right" in s -> 4
            "slight left" in s || "bear left" in s -> 1
            "sharp right" in s -> 5
            "sharp left" in s -> 2

            // Keep-lane variants (no turn, just lane discipline):
            // 11 = keep left (horizontal left arrow + right-side vertical bar).
            // 12 = keep right (horizontal right arrow + left-side vertical bar).
            // verified vs ic_step_11.png + ic_step_12.png 2026-05-25.
            "keep right" in s -> 12
            "keep left" in s -> 11

            // Merge onto highway:
            // 19 = merge left (diagonal upper-left into vertical), 20 = merge right.
            // verified vs ic_step_19.png + ic_step_20.png 2026-05-25.
            "merge" in s && "left" in s -> 19
            "merge" in s -> 20

            // Plain turn left/right:
            // 0 = turn left (L-arrow stem-right → up-left), 3 = turn right (L-arrow stem-left → up-right).
            // verified vs ic_step_0.png + ic_step_3.png 2026-05-25.
            "turn right" in s || "right onto" in s || "right on " in s -> 3
            "turn left" in s || "left onto" in s || "left on " in s -> 0

            // Continue / straight / head — straight up-arrow (ID 7).
            // verified vs ic_step_7.png 2026-05-25.
            "continue" in s || "straight" in s || "head " in s -> GENERIC_ARROW

            // Departure compass directions (IDs 50-57 = compass rose + directional arrow).
            // Only fired when Google Maps emits explicit cardinal-direction text.
            // verified vs ic_step_50-57.png 2026-05-25.
            "head north" in s -> 50
            "head northeast" in s || "head north-east" in s -> 51
            "head east" in s -> 52
            "head southeast" in s || "head south-east" in s -> 53
            "head south" in s -> 54
            "head southwest" in s || "head south-west" in s -> 55
            "head west" in s -> 56
            "head northwest" in s || "head north-west" in s -> 57

            // Ferry / tunnel (verified vs ic_step_36.png + ic_step_37.png 2026-05-25).
            "ferry" in s || "take ferry" in s -> 36
            "tunnel" in s -> 37

            // Destination / arrival.
            // ID 40 = waypoint circle (via-point); no dedicated "destination flag" exists
            // in the APK's ic_step_* set. 40 is the closest unambiguous stop-point icon.
            // verified vs ic_step_40.png 2026-05-25.
            "arrive" in s || "destination" in s || "your destination" in s -> 40

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
            else -> null
        }
    }
}
