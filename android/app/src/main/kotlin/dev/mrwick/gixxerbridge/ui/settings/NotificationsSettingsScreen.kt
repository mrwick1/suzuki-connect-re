package dev.mrwick.gixxerbridge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * Notifications sub-screen: DND auto-toggle, notification access row,
 * SMS permission row, and allowlist link.
 * Route: settings/notifications
 */
@Composable
fun NotificationsSettingsScreen(
    vm: SettingsViewModel,
    onOpenAllowlist: () -> Unit,
) {
    val dnd by vm.autoDndOnConnect.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSection("Do Not Disturb") {
                NotifSwitchRow(
                    label = "Auto-DND when bike connects",
                    value = dnd,
                    onChange = vm::setAutoDndOnConnect,
                )
            }
        }
        item { DndAccessPermissionRow() }
        item { NotificationAccessRow() }
        item {
            SettingsSection("Notifications mirrored to bike") {
                Button(onClick = onOpenAllowlist, modifier = Modifier.fillMaxWidth()) {
                    Text("Edit allowlist")
                }
            }
        }
        item { SendSmsPermissionRow() }
    }
}

@Composable
private fun NotifSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = GixxerTokens.surfaceElevated,
                uncheckedThumbColor = GixxerTokens.textMuted,
                uncheckedBorderColor = GixxerTokens.border,
            ),
        )
    }
}
