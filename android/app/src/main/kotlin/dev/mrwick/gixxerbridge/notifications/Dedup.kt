package dev.mrwick.gixxerbridge.notifications

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks recently-seen notifications so the same WhatsApp / SMS / call doesn't
 * spam the bike cluster multiple times per second when Android re-posts the
 * same StatusBarNotification on update.
 *
 * Caller computes a "fingerprint" from package + extras + small timestamp bucket;
 * Dedup returns true on first sight, false on repeats within the cooldown.
 */
object Dedup {
    private const val COOLDOWN_MS = 8_000L

    private val seen = ConcurrentHashMap<String, Long>()

    /** Returns true if this fingerprint is fresh (not seen recently); false otherwise. */
    fun firstTime(fingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        val prior = seen[fingerprint]
        if (prior != null && now - prior < COOLDOWN_MS) return false
        seen[fingerprint] = now
        // Periodic prune to keep the map bounded.
        if (seen.size > 128) {
            val cutoff = now - COOLDOWN_MS * 4
            seen.entries.removeIf { it.value < cutoff }
        }
        return true
    }

    /** Test-only reset. */
    internal fun clearForTest() { seen.clear() }
}
