package dev.mrwick.redline.ui.lock

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/** ViewModel tracking whether the app lock is enabled and whether the session is currently unlocked. */
class AppLockViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)

    val lockEnabled: StateFlow<Boolean> =
        settings.appLockEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    /**
     * Becomes true after the first DataStore emission has been delivered.
     *
     * Used by [AppLockGate] to avoid flashing app content before the lock state
     * is known. The gate renders a brand placeholder during the (typically < 50 ms)
     * window between app start and first DataStore read. No biometric logic here.
     */
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        // Flip isReady on the first emission from DataStore (SharingStarted.Eagerly
        // means the cold flow starts immediately, so this fires within one frame).
        settings.appLockEnabled
            .onEach { _isReady.value = true }
            .launchIn(viewModelScope)
    }

    fun markUnlocked() { _unlocked.value = true }
    fun reset() { _unlocked.value = false }
}
