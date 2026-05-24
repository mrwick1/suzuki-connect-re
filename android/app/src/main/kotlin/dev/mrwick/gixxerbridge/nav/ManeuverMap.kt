package dev.mrwick.gixxerbridge.nav

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

    // Stub for bitmap classification — empty table for now; production builds it empirically.
    private val bitmapHashToManeuver: MutableMap<Long, Int> = HashMap()

    /** Register a perceptual hash -> maneuver-id mapping (used by classifier seed). */
    fun registerBitmapHash(hash: Long, maneuverId: Int) {
        bitmapHashToManeuver[hash] = maneuverId
    }

    /** Look up a perceptual hash; returns `null` when no mapping is known. */
    fun fromBitmapHash(hash: Long): Int? = bitmapHashToManeuver[hash]
}
