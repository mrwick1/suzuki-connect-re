package dev.mrwick.redline.ui.onboarding

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.ble.BleScanner
import dev.mrwick.redline.ble.DiscoveredBike
import dev.mrwick.redline.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the first-run [OnboardingScreen] wizard.
 *
 * Owns a [BleScanner] and exposes a step index 0..3 (welcome → permissions →
 * pair → start). The scanner is started/stopped explicitly by the pair step so
 * we don't burn battery scanning during the welcome screen.
 */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val scanner = BleScanner(app)

    /** Live map of discovered bikes keyed by MAC (see [BleScanner.results]). */
    val scanResults = scanner.results

    /** Already-paired bike MAC (if any) — surfaced so the pair step can show "already paired". */
    val bikeMac: StateFlow<String?> =
        settings.bikeMac.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _step = MutableStateFlow(0)
    val step: StateFlow<Int> = _step.asStateFlow()

    /** Advance one step; clamps at the final step (3). */
    fun next() { _step.value = (_step.value + 1).coerceAtMost(MAX_STEP) }

    /** Go back one step; clamps at 0. */
    fun back() { _step.value = (_step.value - 1).coerceAtLeast(0) }

    /** Jump directly to a step. Used by the step indicator (no-op on out-of-range). */
    fun goTo(step: Int) {
        if (step in 0..MAX_STEP) _step.value = step
    }

    fun startScan() = scanner.start()
    fun stopScan() = scanner.stop()

    /** Persist the chosen bike's MAC, then advance to the final step. */
    fun pickBike(bike: DiscoveredBike) {
        viewModelScope.launch {
            settings.setBikeMac(bike.mac)
            scanner.stop()
            next()
        }
    }

    /** Mark the wizard as complete — MainActivity will then drop the overlay. */
    fun complete() {
        viewModelScope.launch { settings.setOnboardingComplete(true) }
    }

    override fun onCleared() {
        scanner.stop()
    }

    companion object {
        const val MAX_STEP = 3
    }
}
