package dev.mrwick.redline.nav

import dev.mrwick.redline.protocol.NavFrame
import kotlinx.coroutines.flow.Flow

/**
 * Anything that produces [NavFrame]s over time.
 *
 * A `null` value on the flow means "this source has nothing to say right
 * now" — typically used by upstream parsers (e.g. Google Maps notification
 * listener) when nav is inactive, so a mux can fall through to a lower-
 * priority source like the idle clock generator.
 */
interface NavSource {
    /** Stream of frames; `null` signals "no nav from this source, fall through". */
    val frame: Flow<NavFrame?>
}
