package dev.mrwick.redline.ui.safety

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.data.Settings
import dev.mrwick.redline.safety.SosController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Safety section of [dev.mrwick.redline.ui.settings.SettingsScreen].
 * Exposes the emergency-contact phone number and crash-detection toggle, plus a
 * "send test SOS" action that fires [SosController] immediately with the current
 * last-known location.
 */
class SafetyViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)
    private val sos = SosController(app.applicationContext)

    val emergencyContactPhone = settings.emergencyContactPhone
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val crashDetectionEnabled = settings.crashDetectionEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setEmergencyContactPhone(v: String?) {
        viewModelScope.launch { settings.setEmergencyContactPhone(v) }
    }

    fun setCrashDetectionEnabled(v: Boolean) {
        viewModelScope.launch { settings.setCrashDetectionEnabled(v) }
    }

    /**
     * Fire a test SOS using the currently-saved emergency contact and the most recent
     * last-known location. Returns silently if no contact is set; [SosController] posts
     * a notification with the result.
     */
    fun sendTestSos() {
        viewModelScope.launch {
            val contact = emergencyContactPhone.value
            sos.fire(contact, sos.lastKnownLocation())
        }
    }
}
