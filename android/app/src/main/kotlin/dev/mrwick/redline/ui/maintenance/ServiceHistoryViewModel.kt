package dev.mrwick.redline.ui.maintenance

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.data.GixxerDatabase
import dev.mrwick.redline.data.ServiceLogEntity
import dev.mrwick.redline.data.ServiceLogStore
import dev.mrwick.redline.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs [ServiceHistoryScreen]. Wraps the [ServiceLogStore] and also bumps
 * the home-screen's "last serviced at" datastore key when a new entry is
 * added, so the service-due banner clears without a separate user action.
 */
class ServiceHistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val store: ServiceLogStore =
        ServiceLogStore(GixxerDatabase.get(app).serviceLogDao())
    private val settings = Settings(app)

    /** All service entries, newest-first. */
    val entries: StateFlow<List<ServiceLogEntity>> = store.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Record a new service entry and bump `lastServiceOdoKm` so the home
     * banner clears. [type] is free-form; the screen offers Oil change /
     * Brake pads / General service shortcuts plus a custom entry.
     */
    fun add(odo: Int, type: String, rupees: Double?, notes: String?) {
        viewModelScope.launch {
            store.add(
                odo = odo,
                type = type,
                rupees = rupees,
                notes = notes?.trim()?.ifBlank { null },
            )
            // Side-effect: clear the service-due banner. We use this entry's
            // odometer as the new baseline, on the assumption a "general
            // service" resets the clock. A more nuanced model (oil change vs
            // chain only) would need separate baselines per service type —
            // out of scope for now.
            settings.setLastServiceOdoKm(odo)
        }
    }

    /** Delete one service entry by id. */
    fun delete(id: Long) {
        viewModelScope.launch { store.delete(id) }
    }

    companion object {
        /** Canonical service types offered as quick-pick chips in the add dialog. */
        val CANONICAL_TYPES: List<String> = listOf(
            "Oil change",
            "Brake pads",
            "General service",
            "Chain & sprocket",
            "Tyre change",
            "Coolant",
            "Spark plug",
            "Other",
        )
    }
}
