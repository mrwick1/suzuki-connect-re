package dev.mrwick.gixxerbridge.ui.lock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

/** ViewModel tracking whether the app lock is enabled and whether the session is currently unlocked. */
class AppLockViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    val lockEnabled: StateFlow<Boolean> =
        settings.appLockEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    fun markUnlocked() { _unlocked.value = true }
    fun reset() { _unlocked.value = false }
}
