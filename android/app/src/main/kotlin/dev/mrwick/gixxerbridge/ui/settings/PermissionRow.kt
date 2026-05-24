package dev.mrwick.gixxerbridge.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Generic "permission required" card: title + rationale on the left, either a
 * green "Granted" pill or a "Grant" button on the right. Re-checks [isGranted]
 * on every ON_RESUME so returning from system Settings flips the state without
 * a manual refresh.
 *
 * Used for any permission that we can't (or shouldn't) hide behind the standard
 * runtime perm dialog: notification listener, DND policy access, SEND_SMS.
 */
@Composable
fun PermissionRow(
    title: String,
    rationale: String,
    grantedRationale: String = rationale,
    isGranted: (Context) -> Boolean,
    onGrant: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var granted by remember { mutableStateOf(isGranted(context)) }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) granted = isGranted(context)
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
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (granted) grantedRationale else rationale,
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
                Button(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}

/** Whether the app has been granted DND ("notification policy") access. */
fun isDndAccessGranted(context: Context): Boolean {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    return nm.isNotificationPolicyAccessGranted
}

/** Open the system page where the user toggles DND access for our app. */
fun openDndAccessSettings(context: Context) {
    context.startActivity(
        Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

/** Whether SEND_SMS runtime permission has been granted. */
fun isSmsPermGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

/**
 * Convenience wrapper around [PermissionRow] for SEND_SMS. Needs a launcher,
 * which must be created at the call site via [rememberLauncherForActivityResult],
 * so this composable owns the launcher internally.
 */
@Composable
fun SendSmsPermissionRow() {
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Bump tick so PermissionRow's lifecycle observer re-checks on next resume;
        // also force a recheck immediately via key.
        refreshTick++
    }
    // Key by tick so the inner row's `remember { isGranted(context) }` re-runs
    // after the launcher result lands.
    androidx.compose.runtime.key(refreshTick) {
        PermissionRow(
            title = "SMS sending",
            rationale = "Required — without this, the SOS test and crash-alert can't send the emergency SMS.",
            grantedRationale = "Granted — emergency SMS will be delivered when you send a test SOS or a crash is detected.",
            isGranted = ::isSmsPermGranted,
            onGrant = { launcher.launch(Manifest.permission.SEND_SMS) },
        )
    }
}

/** Convenience wrapper around [PermissionRow] for DND policy access. */
@Composable
fun DndAccessPermissionRow() {
    val context = LocalContext.current
    PermissionRow(
        title = "DND access",
        rationale = "Required — without this, \"Auto-DND when bike connects\" is a silent no-op.",
        grantedRationale = "Granted — phone will switch to Do Not Disturb automatically when the bike connects.",
        isGranted = ::isDndAccessGranted,
        onGrant = { openDndAccessSettings(context) },
    )
}
