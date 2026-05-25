package dev.mrwick.gixxerbridge.nav

import android.graphics.Bitmap
import dev.mrwick.gixxerbridge.util.AppLog

/**
 * Glues [BitmapHasher] + [ManeuverMap] + the text-only fallback into a single
 * decision for [GoogleMapsParser].
 *
 * Strategy:
 *   1. If [bitmap] present: aHash; lookup via [ManeuverMap.fromBitmapHashNearest]
 *      with a Hamming tolerance of [HAMMING_TOLERANCE]. If found, that wins.
 *   2. Always compute the text-fallback id too (cheap), so we can compare.
 *   3. If both bitmap-hash AND text returned non-default ids AND they DISAGREE,
 *      emit a WARN to AppLog — the bitmap-hash table has likely-wrong entries.
 *   4. If we used the text fallback AND a bitmap was present AND the text guess
 *      isn't the generic fallback AND [selfTrainEnabled] is true: register the
 *      hash → text-derived id in [ManeuverMap]. Self-training is OFF by default
 *      (see DISCOVERIES.md 2026-05-25 nav-arrow diagnosis) because a single
 *      wrong text guess can poison the bitmap table indefinitely.
 *
 * Every classification logs to AppLog so future rides have a per-notification
 * trace of (title, bitmap-present, decision-path, id-returned).
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

    /** Provenance of the chosen maneuver id, for diagnostics. */
    enum class Source { HashMatch, TextFallback, SelfTrainedFromText, DefaultArrow }

    /** Full classification result for diagnostics. UI just needs [id]. */
    data class Result(val id: Int, val source: Source, val textId: Int, val hashId: Int?, val hash: Long?)

    /**
     * Classify the maneuver based on the captured icon [bitmap] and/or the raw
     * [instruction] text. Returns a Mappls maneuver-id byte; never null.
     *
     * @param selfTrainEnabled if true, register fresh hash→text mappings on
     *   text-fallback decisions. Default false — self-training without verified
     *   cluster-side id semantics has a runaway-pollution failure mode.
     */
    fun classify(bitmap: Bitmap?, instruction: String?, selfTrainEnabled: Boolean = false): Int {
        val result = classifyDetailed(bitmap, instruction, selfTrainEnabled)
        AppLog.i(
            TAG,
            "id=${result.id} src=${result.source} text=${result.textId} hash=${result.hashId ?: "-"} instr=\"${instruction?.take(60)}\"",
        )
        if (result.hashId != null &&
            result.hashId != ManeuverMap.GENERIC_ARROW &&
            result.textId != ManeuverMap.GENERIC_ARROW &&
            result.hashId != result.textId
        ) {
            AppLog.w(
                TAG,
                "DISAGREE hash=${result.hashId} text=${result.textId} chosen=${result.id} — bitmap table may have a wrong entry for hash=0x${java.lang.Long.toHexString(result.hash ?: 0L)}",
            )
        }
        return result.id
    }

    /** Same as [classify] but returns the full diagnostic record. */
    fun classifyDetailed(bitmap: Bitmap?, instruction: String?, selfTrainEnabled: Boolean): Result {
        val textId = ManeuverMap.fromText(instruction)
        if (bitmap == null) {
            return Result(
                id = textId,
                source = if (textId == ManeuverMap.GENERIC_ARROW) Source.DefaultArrow else Source.TextFallback,
                textId = textId,
                hashId = null,
                hash = null,
            )
        }
        val hash = BitmapHasher.aHash64(bitmap)
        val hashId = ManeuverMap.fromBitmapHashNearest(hash, HAMMING_TOLERANCE)
        if (hashId != null) {
            return Result(
                id = hashId,
                source = Source.HashMatch,
                textId = textId,
                hashId = hashId,
                hash = hash,
            )
        }
        if (selfTrainEnabled && textId != ManeuverMap.GENERIC_ARROW) {
            ManeuverMap.registerBitmapHash(hash, textId)
            AppLog.i(
                TAG,
                "self-trained hash=0x${java.lang.Long.toHexString(hash)} -> id=$textId (from text)",
            )
            return Result(
                id = textId,
                source = Source.SelfTrainedFromText,
                textId = textId,
                hashId = null,
                hash = hash,
            )
        }
        return Result(
            id = textId,
            source = if (textId == ManeuverMap.GENERIC_ARROW) Source.DefaultArrow else Source.TextFallback,
            textId = textId,
            hashId = null,
            hash = hash,
        )
    }
}
