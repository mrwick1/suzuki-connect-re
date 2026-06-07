package dev.mrwick.gixxerbridge.analytics

/**
 * Maps a speed value (km/h) to a brand zone color expressed as an ARGB Int
 * (fully opaque: alpha = 0xFF).
 *
 * This object is intentionally Compose-free so it can be exercised from plain
 * JUnit4 JVM tests without an Android runtime. The hex literals below are
 * intentional *duplicates* of the corresponding [dev.mrwick.gixxerbridge.ui.theme.GixxerTokens]
 * constants; [SpeedTrackColorsTest] pins them so any divergence from the live
 * token file becomes a test failure.
 *
 * Speed band thresholds are named constants flagged ASSUMED because the Gixxer
 * SF 150 cluster does not expose a defined "zone" protocol — the values below
 * are reasonable for the bike's power band but have not been validated against
 * official documentation or rider feedback.
 */
object SpeedTrackColors {

    // --- Band thresholds (km/h) -------------------------------------------

    /**
     * Speeds strictly below this value are "cool" (cruise / eco zone).
     * ASSUMED: 60 km/h is the approximate transition from city/cruise riding
     * to spirited riding on a 150 cc commuter.
     */
    const val THRESHOLD_COOL_MID_KMH: Int = 60

    /**
     * Speeds at or above [THRESHOLD_COOL_MID_KMH] and strictly below this
     * value are "mid" (caution / transitional zone).
     * ASSUMED: 100 km/h is the approximate transition to near-redline territory
     * for the Gixxer SF 150 (max power ~13.5 hp arrives around 9 500 rpm;
     * 100 km/h in 6th gear is already a sustained high-rpm condition).
     */
    const val THRESHOLD_MID_HOT_KMH: Int = 100

    /**
     * Hard ceiling used for normalising segment display; speeds at or above
     * this value are treated as if they equal this cap for rendering purposes.
     * The bike's official top speed is ~125 km/h (ASSUMED from Suzuki India
     * product literature — not independently verified).
     * ASSUMED: 130 km/h is a round number that sits above the claimed top speed
     * and gives the "hot" band a visible upper bound.
     */
    const val CEILING_KMH: Int = 130

    // --- Zone color ARGB ints (mirror of GixxerTokens telemetry spectrum) ---

    /**
     * Cool zone color — mirrors [dev.mrwick.gixxerbridge.ui.theme.GixxerTokens.zoneCool]
     * (`Color(0xFF10D9C4)`). ARGB = 0xFF10D9C4.
     */
    const val COLOR_COOL: Int = 0xFF10D9C4.toInt()

    /**
     * Mid zone color — mirrors [dev.mrwick.gixxerbridge.ui.theme.GixxerTokens.zoneMid]
     * (`Color(0xFFF5A524)`). ARGB = 0xFFF5A524.
     */
    const val COLOR_MID: Int = 0xFFF5A524.toInt()

    /**
     * Hot zone color — mirrors [dev.mrwick.gixxerbridge.ui.theme.GixxerTokens.zoneHot]
     * (`Color(0xFFFF2D78)`). ARGB = 0xFFFF2D78.
     */
    const val COLOR_HOT: Int = 0xFFFF2D78.toInt()

    // -----------------------------------------------------------------------

    /**
     * Return the ARGB Int color for the given [speedKmh].
     *
     * - [speedKmh] < [THRESHOLD_COOL_MID_KMH] → [COLOR_COOL]
     * - [THRESHOLD_COOL_MID_KMH] ≤ [speedKmh] < [THRESHOLD_MID_HOT_KMH] → [COLOR_MID]
     * - [speedKmh] ≥ [THRESHOLD_MID_HOT_KMH] → [COLOR_HOT]
     *
     * Negative speeds are clamped to [COLOR_COOL] (treated as 0 km/h).
     */
    fun colorFor(speedKmh: Int): Int = when {
        speedKmh < THRESHOLD_COOL_MID_KMH -> COLOR_COOL
        speedKmh < THRESHOLD_MID_HOT_KMH  -> COLOR_MID
        else                               -> COLOR_HOT
    }
}
