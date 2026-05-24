package dev.mrwick.gixxerbridge.nav

import dev.mrwick.gixxerbridge.data.renderGreeting
import dev.mrwick.gixxerbridge.protocol.NavFrame

/**
 * One-shot greeting frame for the cluster on a fresh connect.
 *
 * Reuses the same a531 layout that [IdleClockGenerator] writes when no nav is
 * active. The text slots are repurposed:
 *   - distNext  ("4 chars")     → "Hi" + first 2 chars of rider name, padded to 4.
 *   - eta       ("6 chars")     → literal "WELCOM" (drops the trailing E to fit).
 *   - distTotal ("4 chars")     → current temperature in °C, zero-padded.
 *
 * Lives for one heartbeat tick and is then superseded by [NavMux] (either Maps
 * directions or the rolling idle clock).
 *
 * ASSUMED: every text slot is rendered verbatim by the cluster — already shown
 * to be true for printable ASCII via tools/forge_display.py and IdleClockGenerator.
 * ASSUMED: maneuverId=1 is a friendly "default arrow" icon for the greeting.
 * The actual icon mapping is empirical (see [ManeuverMap]); 1 is inside the
 * "safe" 0-8 range so it will render *something* — verify on the cluster.
 */
object WelcomeFrame {

    /** ASSUMED: greeting maneuver icon — picked from the safe 0-8 range. */
    private const val GREETING_MANEUVER_ID: Int = 1

    /**
     * Build the welcome [NavFrame].
     *
     * @param name             rider display name (e.g. "Arjun")
     * @param tempCelsius      current temperature; null → "0000"
     * @param suzukiWeatherCode currently unused (the welcome frame has no slot
     *                          for it), accepted only so callers can pass the
     *                          same tuple as the idle clock for symmetry.
     */
    @Suppress("UNUSED_PARAMETER")
    fun build(name: String, tempCelsius: Double?, suzukiWeatherCode: Int): NavFrame {
        // "Hi" + name → take 4, pad to 4. e.g. "Arjun" → "HiAr", "" → "Hi  ".
        val hiText = ("Hi" + name).take(4).padEnd(4, ' ')
        return composeFrame(distNextText = hiText, tempCelsius = tempCelsius)
    }

    /**
     * Build a welcome frame from a user-defined [greetings] pool.
     *
     * Picks one entry at random, runs `{name}` substitution, then fits the result
     * into the 4-char distNext slot (taking the first 4 chars, padding with
     * spaces). The rest of the layout matches [build]. Falls back to [build] when
     * [greetings] is empty.
     */
    @Suppress("UNUSED_PARAMETER")
    fun buildRandom(
        name: String,
        greetings: List<String>,
        tempCelsius: Double?,
        suzukiWeatherCode: Int,
        random: kotlin.random.Random = kotlin.random.Random.Default,
    ): NavFrame {
        if (greetings.isEmpty()) return build(name, tempCelsius, suzukiWeatherCode)
        val picked = greetings.random(random)
        val rendered = renderGreeting(picked, name)
        val text = rendered.take(4).padEnd(4, ' ')
        return composeFrame(distNextText = text, tempCelsius = tempCelsius)
    }

    /** Shared layout helper — turns a 4-char welcome text + temp into the canonical a531 frame. */
    private fun composeFrame(distNextText: String, tempCelsius: Double?): NavFrame {
        val tempStr = tempCelsius?.let {
            val n = it.toInt().coerceIn(-99, 9999)
            if (n < 0) "-%03d".format(-n) else "%04d".format(n)
        } ?: "0000"
        return NavFrame(
            maneuverId = GREETING_MANEUVER_ID,
            distNext = distNextText,
            distNextUnit = " ",        // ASSUMED: blank unit reads cleanly next to "Hi…".
            eta = "WELCOM",            // 6 ASCII chars — fits eta slot exactly.
            distTotal = tempStr,
            distTotalUnit = "C",
            status = "1",              // '1' = render maneuverId properly (not degraded).
            continueFlag = "1",
        )
    }
}
