package dev.mrwick.gixxerbridge.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.ConnectionState

/**
 * Adds/removes FLAG_KEEP_SCREEN_ON on the current Activity's window based on
 * [Settings.keepScreenOnWhileConnected] AND the live connection state.
 *
 * No-ops if the surrounding context is not an [Activity].
 */
@Composable
fun KeepScreenOnEffect() {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    // ASSUMED: cheap to construct a Settings handle per composition — the
    // backing DataStore is process-wide. Matches the pattern in HomeScreen.
    val settings = remember { Settings(context.applicationContext) }
    val pref by settings.keepScreenOnWhileConnected.collectAsStateWithLifecycle(initialValue = false)
    val state by AppGraph.connectionState.collectAsStateWithLifecycle()
    val keepOn = pref && state is ConnectionState.Ready

    DisposableEffect(keepOn) {
        if (keepOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
