package dev.mrwick.gixxerbridge.nav

import android.content.Context
import android.util.Log
import java.io.File

object ManeuverMap {

    /**
     * Default cluster byte for "show a forward arrow / generic". Equal to the
     * cluster byte the OEM produces for Mappls ID 7 (straight/head). Used by
     * downstream consumers ([IdleClockGenerator], [WelcomeFrame]) that don't
     * have a Mappls ID and just need a renderable cluster byte.
     */
    const val DEFAULT_CLUSTER_BYTE = 8

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
     * @param btid the bike's Bluetooth device ID (as the OEM stored it after
     *     pairing); null means it doesn't trigger the SBS51 branch. The OEM
     *     treats any BTID containing "SBS51" the same as the Burgman-like
     *     vehicle-name set for Mappls IDs 58 and 74.
     * @return cluster byte 1..52, or null if the Mappls ID has no defined
     *     translation. Null means "leave the cluster showing whatever glyph it
     *     was last sent" — matches the OEM behavior of leaving e0 untouched in
     *     the fallthrough branches.
     */
    fun mapplsIdToClusterByte(mapplsId: Int, vehicleModel: String?, btid: String? = null): Int? {
        // OEM checks BOTH conditions (A0.java:646 + :660): vehicleModel in the
        // special-cased set OR BTID containing "SBS51". The two are independent
        // — some bikes match one but not the other.
        val isBurgmanLike = (vehicleModel != null && vehicleModel in BURGMAN_LIKE_MODELS) ||
            (btid != null && btid.contains("SBS51"))
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
