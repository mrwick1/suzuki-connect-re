package dev.mrwick.gixxerbridge.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.data.Greetings
import dev.mrwick.gixxerbridge.data.ServiceItem
import dev.mrwick.gixxerbridge.data.ServiceItemState
import dev.mrwick.gixxerbridge.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ViewModel for [SettingsScreen]; exposes DataStore-backed prefs and suspend setters. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val greetingsStore = Greetings(app)

    val riderName = settings.riderName
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_RIDER_NAME)
    val bikeMac = settings.bikeMac
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val autoStartOnBoot = settings.autoStartOnBoot
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val appLockEnabled = settings.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val idleClockEnabled = settings.idleClockEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val nowPlayingOnCluster = settings.nowPlayingOnCluster
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val autoDndOnConnect = settings.autoDndOnConnect
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val serviceIntervalKm = settings.serviceIntervalKm
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_SERVICE_INTERVAL_KM)

    /** Per-item periodic-service state for all five Suzuki items; seeded with bare defaults. */
    val serviceSchedule = settings.serviceSchedule
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ServiceItem.entries.associateWith {
                ServiceItemState(
                    item = it,
                    kmThreshold = it.defaultKm,
                    daysThreshold = it.defaultDays,
                    lastServiceDateMs = null,
                    lastServiceOdoKm = null,
                )
            },
        )
    val demoMode = settings.demoMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val keepScreenOn = settings.keepScreenOnWhileConnected
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val themeAccent = settings.themeAccent
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_ACCENT)
    val activeRideMetric = settings.activeRideMetric
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_ACTIVE_RIDE_METRIC)
    val greetings = greetingsStore.list
        .stateIn(viewModelScope, SharingStarted.Eagerly, Greetings.DEFAULT_GREETINGS)

    fun setRiderName(name: String) {
        viewModelScope.launch { settings.setRiderName(name) }
    }

    fun setAutoStartOnBoot(v: Boolean) {
        viewModelScope.launch { settings.setAutoStartOnBoot(v) }
    }

    fun setAppLockEnabled(v: Boolean) {
        viewModelScope.launch { settings.setAppLockEnabled(v) }
    }

    fun setIdleClockEnabled(v: Boolean) {
        viewModelScope.launch { settings.setIdleClockEnabled(v) }
    }

    fun setNowPlayingOnCluster(v: Boolean) {
        viewModelScope.launch { settings.setNowPlayingOnCluster(v) }
    }

    fun setAutoDndOnConnect(v: Boolean) {
        viewModelScope.launch { settings.setAutoDndOnConnect(v) }
    }

    fun setServiceIntervalKm(km: Int) {
        viewModelScope.launch { settings.setServiceIntervalKm(km) }
    }

    /** Update a single service item's km / days thresholds. */
    fun setServiceItemThresholds(item: ServiceItem, kmThreshold: Int?, daysThreshold: Int) {
        viewModelScope.launch { settings.setServiceItemThresholds(item, kmThreshold, daysThreshold) }
    }

    /**
     * Record "I just serviced this" for one item — stamps now as the date
     * baseline and pins [currentOdoKm] (nullable; pass null when the bike has
     * never connected) as the km baseline.
     */
    fun markServiceDone(item: ServiceItem, currentOdoKm: Int?) {
        viewModelScope.launch { settings.markServiceDone(item, currentOdoKm) }
    }

    fun setDemoMode(v: Boolean) {
        viewModelScope.launch { settings.setDemoMode(v) }
    }

    /** Toggle the "keep screen on while connected" preference. */
    fun setKeepScreenOnWhileConnected(v: Boolean) {
        viewModelScope.launch { settings.setKeepScreenOnWhileConnected(v) }
    }

    /** Clear the onboarding-complete flag so the first-run wizard replays on next app launch. */
    fun resetOnboarding() {
        viewModelScope.launch { settings.setOnboardingComplete(false) }
    }

    /** Set the named theme accent (must be a key in `ACCENT_PALETTE`). */
    fun setThemeAccent(name: String) {
        viewModelScope.launch { settings.setThemeAccent(name) }
    }

    /** Set the active-ride bottom-metric choice ("trip-a", "fuel", "eta", "road-type"). */
    fun setActiveRideMetric(name: String) {
        viewModelScope.launch { settings.setActiveRideMetric(name) }
    }

    /** Replace the full cluster-greeting pool. */
    fun setGreetings(items: List<String>) {
        viewModelScope.launch { greetingsStore.setList(items) }
    }

    /** Forget the currently-paired bike. Clears the MAC from DataStore; the
     *  BikeBridgeService observes the change and calls bleClient.disconnect(). */
    fun forgetBike() {
        viewModelScope.launch { settings.setBikeMac(null) }
    }
}
