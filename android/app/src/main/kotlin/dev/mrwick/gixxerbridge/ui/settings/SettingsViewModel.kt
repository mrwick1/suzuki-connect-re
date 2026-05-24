package dev.mrwick.gixxerbridge.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.data.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** ViewModel for [SettingsScreen]; exposes DataStore-backed prefs and suspend setters. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

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
    val demoMode = settings.demoMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val keepScreenOn = settings.keepScreenOnWhileConnected
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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
}
