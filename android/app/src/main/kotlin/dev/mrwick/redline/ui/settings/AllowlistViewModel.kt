package dev.mrwick.redline.ui.settings

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.redline.data.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lightweight package + label record for the allowlist UI. */
data class AppInfo(val pkg: String, val label: String)

/** ViewModel for [AllowlistScreen]; maintains the persisted notification-mirror allowlist. */
class AllowlistViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = Settings(app)

    /** Currently-persisted allowlist; defaults to [Settings.DEFAULT_ALLOWLIST]. */
    val allowed: StateFlow<Set<String>> = settings.mirrorAllowlist
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_ALLOWLIST)

    /** Snapshot of installed launcher apps (loaded once at construction). */
    val installed: StateFlow<List<AppInfo>> =
        MutableStateFlow(loadInstalled(app)).asStateFlow()

    /** Add or remove [pkg] from the allowlist and persist. */
    fun toggle(pkg: String) {
        viewModelScope.launch {
            val current = allowed.value
            val next = if (pkg in current) current - pkg else current + pkg
            settings.setMirrorAllowlist(next)
        }
    }

    private fun loadInstalled(app: Application): List<AppInfo> {
        val pm = app.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolved = pm.queryIntentActivities(mainIntent, 0)
        return resolved
            .map { ri -> AppInfo(ri.activityInfo.packageName, ri.loadLabel(pm).toString()) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }
}
