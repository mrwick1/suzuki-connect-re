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
            "u-turn" in s || "u turn" in s || "make a u" in s -> 6
            "roundabout" in s -> 72
            "exit" in s && "left" in s -> 73
            "exit" in s && "right" in s -> 75
            "exit" in s -> 75
            "slight right" in s || "bear right" in s -> 4
            "slight left" in s || "bear left" in s -> 1
            "sharp right" in s -> 5
            "sharp left" in s -> 2
            "keep right" in s -> 12
            "keep left" in s -> 11
            "merge" in s && "left" in s -> 19
            "merge" in s -> 20
            "turn right" in s || "right onto" in s || "right on " in s -> 3
            "turn left" in s || "left onto" in s || "left on " in s -> 0
            // Compass-rose departures: ORDER MATTERS — these must precede the
            // generic "head " catch-all below, otherwise they are dead code.
            "head northeast" in s || "head north-east" in s -> 51
            "head northwest" in s || "head north-west" in s -> 57
            "head southeast" in s || "head south-east" in s -> 53
            "head southwest" in s || "head south-west" in s -> 55
            "head north" in s -> 50
            "head south" in s -> 54
            "head east" in s -> 52
            "head west" in s -> 56
            // Generic continue/straight/head — safe because the compass
            // variants above already matched first.
            "continue" in s || "straight" in s || "head " in s -> DEFAULT_MAPPLS_ID
            "ferry" in s || "take ferry" in s -> 36
            "tunnel" in s -> 37
            "arrive" in s || "destination" in s || "your destination" in s -> 40
            else -> DEFAULT_MAPPLS_ID
        }
    }
}
