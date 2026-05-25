package dev.mrwick.gixxerbridge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TwoWheeler
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.about.AboutCardLink

/**
 * Settings landing page — 5 sub-section cards + About.
 *
 * Each row: 24 dp Material Symbol icon on the left, label, chevron on the right.
 * Tapping navigates to the sub-route registered in MainActivity.
 */
@Composable
fun SettingsScreen(
    onOpenBike: () -> Unit,
    onOpenCluster: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenMaintenance: () -> Unit,
    onOpenDeveloper: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SettingsNavRow(
                icon = Icons.Outlined.TwoWheeler,
                label = "Bike",
                description = "Rider name, pairing, safety",
                onClick = onOpenBike,
            )
        }
        item {
            SettingsNavRow(
                icon = Icons.Outlined.Settings,
                label = "Cluster",
                description = "Idle clock, now playing, theme accent, greetings",
                onClick = onOpenCluster,
            )
        }
        item {
            SettingsNavRow(
                icon = Icons.Outlined.Notifications,
                label = "Notifications",
                description = "DND, notification access, SMS, allowlist",
                onClick = onOpenNotifications,
            )
        }
        item {
            SettingsNavRow(
                icon = Icons.Outlined.Build,
                label = "Maintenance",
                description = "Service schedule, fuel log, service history",
                onClick = onOpenMaintenance,
            )
        }
        item {
            SettingsNavRow(
                icon = Icons.Outlined.Code,
                label = "Developer",
                description = "Demo mode, inspector, diagnostics, app behavior",
                onClick = onOpenDeveloper,
            )
        }
        item {
            AboutCardLink(onClick = onOpenAbout)
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
