package dev.mrwick.gixxerbridge.nav

import android.graphics.Bitmap
import android.util.Log

/**
 * Glues [BitmapHasher] + [ManeuverMap] + the text-only fallback into a single
 * decision for [GoogleMapsParser].
 *
 * Strategy:
 *   1. If [bitmap] present: aHash; lookup via [ManeuverMap.fromBitmapHashNearest]
 *      with a Hamming tolerance of [HAMMING_TOLERANCE]. If found, return it.
 *   2. Else (or on no hash match): text fallback via [ManeuverMap.fromText].
 *   3. If we used the text fallback AND a bitmap was present AND the text guess
 *      isn't the generic fallback, opportunistically learn:
 *      register the hash -> text-derived id in [ManeuverMap]. Next time the
 *      same bitmap appears, the hash path resolves it directly.
 *
 * Self-training is gated on a non-default text result so we don't pollute the
 * table with "every unrecognized icon = generic arrow" entries.
 */
object ManeuverClassifier {
    private const val TAG = "ManeuverClassifier"

    /**
     * Hamming-distance cap for considering an aHash a match. 5 of 64 bits ≈ 8 %
     * — empirically generous enough to absorb anti-aliasing and color-variant
     * rendering differences without colliding across distinct turn icons.
     *
     * ASSUMED: 5 bits tolerance is correct for Maps' 64dp turn icons; revisit
     * once we have real captures to measure inter/intra-class distance.
     */
    private const val HAMMING_TOLERANCE = 5

    /**
     * Classify the maneuver based on the captured icon [bitmap] and/or the raw
     * [instruction] text. Returns a Mappls maneuver-id byte; never null.
     */
    fun classify(bitmap: Bitmap?, instruction: String?): Int {
        if (bitmap != null) {
            val hash = BitmapHasher.aHash64(bitmap)
            val byHash = ManeuverMap.fromBitmapHashNearest(hash, HAMMING_TOLERANCE)
            if (byHash != null) {
                return byHash
            }
            val textId = ManeuverMap.fromText(instruction)
            // Self-train: only learn from text matches that aren't the default fallback,
            // otherwise we pollute the table with "everything = generic arrow".
            if (textId != ManeuverMap.GENERIC_ARROW) {
                ManeuverMap.registerBitmapHash(hash, textId)
                Log.i(
                    TAG,
                    "Learned hash=0x${java.lang.Long.toHexString(hash)} -> maneuver=$textId" +
                        "  (from text: $instruction)",
                )
            } else {
                Log.d(
                    TAG,
                    "No hash match and text fell to GENERIC_ARROW; not learning. " +
                        "hash=0x${java.lang.Long.toHexString(hash)} text=$instruction",
                )
            }
            return textId
        }
        return ManeuverMap.fromText(instruction)
    }
}
