package dev.mrwick.gixxerbridge.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Encodes a set of dismissed journey-suggestion keys (first-segment start millis)
 * as a comma-separated string. A plain CSV (rather than JSON) keeps the codec
 * dependency-free so it stays unit-testable on the JVM — Android's `org.json`
 * is only a stub there. Tolerates legacy "[]" / blank values as empty.
 */
object JourneyDismissCodec {
    fun encode(keys: Set<Long>): String = keys.sorted().joinToString(",")

    fun decode(raw: String): Set<Long> =
        raw.split(",").mapNotNull { it.trim().toLongOrNull() }.toSet()
}

private val Context.journeyDismissDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "journey_dismiss")

/** Persists which journey suggestions the rider has dismissed so they don't
 *  reappear. Side-store (DataStore), independent of Room. */
class JourneyDismissStore(private val context: Context) {
    private val key = stringPreferencesKey("dismissed_keys")

    fun observe(): Flow<Set<Long>> = context.journeyDismissDataStore.data
        .map { prefs -> JourneyDismissCodec.decode(prefs[key] ?: "") }

    suspend fun dismiss(startMillis: Long) {
        context.journeyDismissDataStore.edit { prefs ->
            val cur = JourneyDismissCodec.decode(prefs[key] ?: "")
            prefs[key] = JourneyDismissCodec.encode(cur + startMillis)
        }
    }
}
