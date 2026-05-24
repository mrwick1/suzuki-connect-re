package dev.mrwick.gixxerbridge.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.mrwick.gixxerbridge.notifications.NotificationCaptureService

/**
 * One-tap fix for the most common confusion: "the app doesn't see my Maps
 * notifications". Opens the system Notification access page when the listener
 * hasn't been granted yet; shows a green "Granted" pill otherwise.
 *
 * Re-checks the grant state every time the screen resumes (after the user
 * returns from system Settings).
 */
@Composable
fun NotificationAccessRow() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(isListenerGranted(context)) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) granted = isListenerGranted(context)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Notification access", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (granted) "Granted — Maps directions, calls and SMS will reach the bike."
                    else "Required — without this we can't read Google Maps directions or forward calls/SMS to the cluster.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            if (granted) {
                Surface(
                    color = Color(0xFF064E3B),
                    contentColor = Color(0xFFA7F3D0),
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "Granted",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else {
                Button(onClick = { openListenerSettings(context) }) {
                    Text("Grant")
                }
            }
        }
    }
}

private fun isListenerGranted(context: Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        ?: return false
    val component = ComponentName(context, NotificationCaptureService::class.java).flattenToString()
    return enabled.split(':').any { it == component }
}

private fun openListenerSettings(context: Context) {
    val intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
