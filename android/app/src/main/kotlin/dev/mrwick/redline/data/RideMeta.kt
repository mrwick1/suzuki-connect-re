package dev.mrwick.redline.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Per-ride metadata stored OUTSIDE Room.
 *
 * Keyed by [startedAtMillis] (the ride's stable natural key) so the data
 * survives Room's [fallbackToDestructiveMigration] id resets. Never key by
 * [RideEntity.id] — that resets to 1 after every destructive migration.
 *
 * @param favorite  True when the rider has starred this ride.
 * @param tags      Free-form tag labels (e.g. "commute", "twisties").
 * @param note      Long-form plain-text note (multi-line OK; 500-char soft cap
 *                  enforced by the UI, not enforced here so old data is readable).
 */
data class RideMeta(
    val favorite: Boolean = false,
    val tags: Set<String> = emptySet(),
    val note: String = "",
)

// ---------- Codec (pure; testable without Android) ---------------------------

/**
 * Serialization codec for `Map<Long, RideMeta>` → single JSON string stored
 * in one DataStore key.
 *
 * Wire format: a JSON array of [RideMetaEntry] objects. Array chosen over an
 * object-keyed map so Long keys round-trip without JSON-spec-undefined behaviour
 * (JSON object keys must be strings; parsing `"1234567890": {...}` works in
 * practice but is fragile). An array of {key, ...fields} is unambiguous.
 *
 * Example:
 * ```json
 * [
 *   {"k":1748000000000,"fav":true,"tags":["commute"],"note":"Rainy morning"},
 *   {"k":1748086400000,"fav":false,"tags":[],"note":""}
 * ]
 * ```
 */
object RideMetaCodec {

    @Serializable
    internal data class RideMetaEntry(
        val k: Long,                   // startedAtMillis
        val fav: Boolean = false,
        val tags: List<String> = emptyList(),
        val note: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Encode a full meta map to a JSON string. Returns `"[]"` for an empty map. */
    fun encode(map: Map<Long, RideMeta>): String {
        val entries = map.map { (k, v) ->
            RideMetaEntry(k = k, fav = v.favorite, tags = v.tags.toList(), note = v.note)
        }
        return json.encodeToString(entries)
    }

    /**
     * Decode a JSON string back to a meta map.
     *
     * On any parse error (corrupt store, format version mismatch) returns an
     * empty map — losing annotations is less bad than crashing.
     */
    fun decode(raw: String): Map<Long, RideMeta> {
        if (raw.isBlank() || raw == "[]") return emptyMap()
        return try {
            val entries = json.decodeFromString<List<RideMetaEntry>>(raw)
            entries.associate { e ->
                e.k to RideMeta(
                    favorite = e.fav,
                    tags = e.tags.toSet(),
                    note = e.note,
                )
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

// ---------- Store ------------------------------------------------------------

/**
 * Persistent per-ride metadata backed by its own Preferences DataStore file
 * (`ride_meta`). Entirely separate from `gixxer_settings` so a Settings wipe
 * doesn't erase ride annotations.
 *
 * One instance per process — obtain via [AppGraph.rideMetaStore] which
 * follows the same double-checked-locking pattern as other store accessors.
 *
 * All writes are atomic at the DataStore level (single key → single JSON blob).
 * Race condition: two simultaneous callers each `read → modify → write`; the
 * last write wins. Acceptable for a single-user app where the rider is the
 * only writer.
 */
class RideMetaStore(context: Context) {

    private val ds: DataStore<Preferences> = context.rideMetaDataStore

    /** Observe the full meta map, keyed by [startedAtMillis]. Emits immediately on subscribe. */
    fun observe(): Flow<Map<Long, RideMeta>> =
        ds.data.map { prefs ->
            RideMetaCodec.decode(prefs[KEY_MAP].orEmpty())
        }

    /**
     * Toggle the favourite flag for the ride with the given [startedAtMillis].
     * No-op if [startedAtMillis] doesn't exist in the map yet — the entry is
     * created with default values and [favorite] applied.
     */
    suspend fun setFavorite(startedAtMillis: Long, favorite: Boolean) {
        ds.edit { prefs ->
            val map = RideMetaCodec.decode(prefs[KEY_MAP].orEmpty()).toMutableMap()
            map[startedAtMillis] = (map[startedAtMillis] ?: RideMeta()).copy(favorite = favorite)
            prefs[KEY_MAP] = RideMetaCodec.encode(map)
        }
    }

    /**
     * Replace the tag set for [startedAtMillis].
     * Empty set removes all tags but keeps the entry (favorite / note preserved).
     */
    suspend fun setTags(startedAtMillis: Long, tags: Set<String>) {
        ds.edit { prefs ->
            val map = RideMetaCodec.decode(prefs[KEY_MAP].orEmpty()).toMutableMap()
            map[startedAtMillis] = (map[startedAtMillis] ?: RideMeta()).copy(tags = tags)
            prefs[KEY_MAP] = RideMetaCodec.encode(map)
        }
    }

    /**
     * Set the free-text note for [startedAtMillis].
     * Passing an empty string clears the note but keeps the entry.
     */
    suspend fun setNote(startedAtMillis: Long, note: String) {
        ds.edit { prefs ->
            val map = RideMetaCodec.decode(prefs[KEY_MAP].orEmpty()).toMutableMap()
            map[startedAtMillis] = (map[startedAtMillis] ?: RideMeta()).copy(note = note)
            prefs[KEY_MAP] = RideMetaCodec.encode(map)
        }
    }

    private companion object {
        val KEY_MAP = stringPreferencesKey("ride_meta_map")
    }
}

private val Context.rideMetaDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "ride_meta")
