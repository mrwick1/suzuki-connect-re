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
 * "Home" + "Work" address slots for the Quick destinations card.
 * Each slot stores a free-text address that Google Maps' geo: URI handles.
 *
 * Two slots is intentional — more than two becomes a shortcut grid that the
 * rider has to read, not tap. Two fits the use case (commute and back).
 */
class QuickDestinations(context: Context) {
    private val ds: DataStore<Preferences> = context.quickDest

    val home: Flow<String?> = ds.data.map { it[KEY_HOME]?.takeIf { s -> s.isNotBlank() } }
    val work: Flow<String?> = ds.data.map { it[KEY_WORK]?.takeIf { s -> s.isNotBlank() } }

    suspend fun set(slot: Slot, address: String?) {
        ds.edit {
            val k = if (slot == Slot.HOME) KEY_HOME else KEY_WORK
            if (address.isNullOrBlank()) it.remove(k) else it[k] = address
        }
    }

    enum class Slot { HOME, WORK }

    companion object {
        private val KEY_HOME = stringPreferencesKey("quick_dest_home")
        private val KEY_WORK = stringPreferencesKey("quick_dest_work")
    }
}

private val Context.quickDest: DataStore<Preferences> by preferencesDataStore(name = "quick_destinations")
