package dev.mrwick.redline.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Per-connect greeting strings shown on the cluster welcome a531 frame.
 *
 * Stored as a single newline-separated string in its own DataStore file so the
 * canonical [Settings] store stays focused on scalar prefs. The list always
 * contains at least one entry — on first read, [DEFAULT_GREETINGS] is returned.
 *
 * Each greeting may include the literal placeholder `{name}` which the welcome
 * frame builder substitutes with the rider name at send time.
 */
class Greetings(context: Context) {
    private val ds: DataStore<Preferences> = context.greetingsDataStore

    /** Current list of greeting templates; never empty. */
    val list: Flow<List<String>> =
        ds.data.map { prefs ->
            decodeGreetings(prefs[KEY])
        }

    /** Replace the full greeting list; empty/blank entries are dropped before save. */
    suspend fun setList(items: List<String>) {
        ds.edit { it[KEY] = encodeGreetings(items) }
    }

    companion object {
        /** Default greetings used the first time the app runs. `{name}` is substituted at send time. */
        val DEFAULT_GREETINGS: List<String> = listOf(
            "Hi {name}",
            "Ride safe",
            "Looking sharp",
            "Stay aware",
            "Have a good one",
        )

        internal val KEY = stringPreferencesKey("greetings")
    }
}

// ---------- Pure helpers (extracted so they can be unit tested) ----------

/** Serialize a greeting list to its DataStore representation (newline-joined, blanks dropped). */
internal fun encodeGreetings(items: List<String>): String =
    items.map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")

/** Deserialize a DataStore-stored greeting blob back to a list; null/blank falls back to defaults. */
internal fun decodeGreetings(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return Greetings.DEFAULT_GREETINGS
    val parsed = raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    return if (parsed.isEmpty()) Greetings.DEFAULT_GREETINGS else parsed
}

/** Resolve a greeting template to its final cluster-bound text (`{name}` -> rider name). */
internal fun renderGreeting(template: String, name: String): String =
    template.replace("{name}", name)

// ASSUMED: a separate DataStore file ("gixxer_greetings") keeps the list-shaped
// data out of the scalar settings store. Cheap, isolated, and easy to wipe.
private val Context.greetingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "gixxer_greetings")
