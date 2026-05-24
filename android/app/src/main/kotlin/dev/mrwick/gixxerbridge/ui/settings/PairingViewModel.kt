package dev.mrwick.gixxerbridge.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.ble.BleScanner
import dev.mrwick.gixxerbridge.ble.DiscoveredBike
import dev.mrwick.gixxerbridge.data.Settings
import kotlinx.coroutines.launch

/** ViewModel for [PairingScreen]; owns a [BleScanner] and writes the picked MAC into [Settings]. */
class PairingViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = BleScanner(app)
    private val settings = Settings(app)

    /** Live map of discovered bikes keyed by MAC (see [BleScanner.results]). */
    val results = scanner.results

    init {
        scanner.start()
    }

    override fun onCleared() {
        scanner.stop()
    }

    /** Persist the chosen bike's MAC, then invoke [onDone] on the main scope. */
    fun pickBike(bike: DiscoveredBike, onDone: () -> Unit) {
        viewModelScope.launch {
            settings.setBikeMac(bike.mac)
            onDone()
        }
    }
}
