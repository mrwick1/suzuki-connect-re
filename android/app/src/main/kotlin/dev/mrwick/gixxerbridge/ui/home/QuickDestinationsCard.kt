package dev.mrwick.gixxerbridge.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.QuickDestinations
import kotlinx.coroutines.launch

/**
 * Quick-launch destinations on Home. Tap "Home" or "Work" row → fires a Google Maps
 * navigation intent to that saved address. Solves the daily-commute pain point:
 * no more typing the same destination every morning.
 *
 * Tap the pencil icon to set/clear an entry.
 */
@Composable
fun QuickDestinationsCard() {
    val context = LocalContext.current
    // PERF: process-wide singleton instead of per-composition allocation
    // (audit finding 1.2). DataStore handles must be reused across the process.
    val store = remember(context) { AppGraph.quickDestinations(context) }
    val home by store.home.collectAsStateWithLifecycle(initialValue = null)
    val work by store.work.collectAsStateWithLifecycle(initialValue = null)
    var editTarget by remember { mutableStateOf<QuickDestinations.Slot?>(null) }
    val scope = rememberCoroutineScope()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Quick destinations", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(12.dp))
            DestRow(
                icon = Icons.Default.Home,
                label = "Home",
                value = home,
                onTap = {
                    home?.let { launchMaps(context, it) }
                        ?: run { editTarget = QuickDestinations.Slot.HOME }
                },
                onEdit = { editTarget = QuickDestinations.Slot.HOME },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            DestRow(
                icon = Icons.Default.Business,
                label = "Work",
                value = work,
                onTap = {
                    work?.let { launchMaps(context, it) }
                        ?: run { editTarget = QuickDestinations.Slot.WORK }
                },
                onEdit = { editTarget = QuickDestinations.Slot.WORK },
            )
        }
    }

    val target = editTarget
    if (target != null) {
        EditDialog(
            label = if (target == QuickDestinations.Slot.HOME) "Home" else "Work",
            initial = if (target == QuickDestinations.Slot.HOME) home else work,
            onDismiss = { editTarget = null },
            onSave = { addr ->
                scope.launch { store.set(target, addr) }
                editTarget = null
            },
            onClear = {
                scope.launch { store.set(target, null) }
                editTarget = null
            },
        )
    }
}

@Composable
private fun DestRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    onTap: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onTap() }.padding(vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(
                value?.take(40) ?: "Tap to set address",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Default.Edit, contentDescription = "Edit $label")
        }
    }
}

@Composable
private fun EditDialog(label: String, initial: String?, onDismiss: () -> Unit, onSave: (String) -> Unit, onClear: () -> Unit) {
    var text by remember(initial) { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$label address") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = false,
                label = { Text("Address or place name") },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private fun launchMaps(context: android.content.Context, address: String) {
    // ASSUMED: every Android device with Maps installed handles the geo: + q= URI.
    // Fallback to the browser if Maps isn't there.
    val uri = Uri.parse("google.navigation:q=${Uri.encode(address)}&mode=d")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        setPackage("com.google.android.apps.maps")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        val fallback = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(address)}&travelmode=driving"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(fallback)
    }
}
