package dev.mrwick.redline.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.ui.theme.ACCENT_PALETTE
import dev.mrwick.redline.ui.theme.GixxerTokens

/**
 * Cluster sub-screen: idle clock, now playing, active-ride metric picker,
 * theme accent, and cluster greetings.
 * Route: settings/cluster
 */
@Composable
fun ClusterSettingsScreen(vm: SettingsViewModel) {
    val idleClock by vm.idleClockEnabled.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlayingOnCluster.collectAsStateWithLifecycle()
    val activeRideMetric by vm.activeRideMetric.collectAsStateWithLifecycle()
    val themeAccent by vm.themeAccent.collectAsStateWithLifecycle()
    val greetings by vm.greetings.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSection("Cluster") {
                ClusterSwitchRow("Show clock + weather when nav idle", idleClock, vm::setIdleClockEnabled)
                ClusterSwitchRow("Show Now Playing scrolling text", nowPlaying, vm::setNowPlayingOnCluster)
                Spacer(modifier = Modifier.height(12.dp))
                ClusterActiveRideMetricPicker(
                    selected = activeRideMetric,
                    onSelect = vm::setActiveRideMetric,
                )
            }
        }
        // TODO: retire — accent picker is inert since MainActivity stopped reading themeAccent (component-kit plan)
        item {
            SettingsSection("Theme accent") {
                Text(
                    "Pick the highlight colour used across the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                ClusterAccentSwatchRow(
                    selected = themeAccent,
                    onSelect = vm::setThemeAccent,
                )
            }
        }
        item {
            SettingsSection("Cluster greetings") {
                Text(
                    "Sent once on each bike connect. {name} is replaced with your rider name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                ClusterGreetingsEditor(
                    items = greetings,
                    onChange = vm::setGreetings,
                )
            }
        }
    }
}

@Composable
private fun ClusterSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
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

@Composable
private fun ClusterAccentSwatchRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ACCENT_PALETTE.forEach { (name, color) ->
            val isSelected = name == selected
            val ring = if (isSelected) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(width = 2.dp, color = ring, shape = CircleShape)
                    .clickable { onSelect(name) }
                    .semantics { contentDescription = "Accent $name${if (isSelected) " (selected)" else ""}" },
            )
        }
    }
}

@Composable
private fun ClusterActiveRideMetricPicker(selected: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "trip-a" to "Trip A",
        "fuel" to "Fuel",
        "eta" to "ETA",
        "road-type" to "Road type",
    )
    Column {
        Text(
            "Active-ride bottom metric",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (key, label) ->
                FilterChip(
                    selected = key == selected,
                    onClick = { onSelect(key) },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}

@Composable
private fun ClusterGreetingsEditor(items: List<String>, onChange: (List<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { idx, text ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v ->
                        val updated = items.toMutableList().also { it[idx] = v.take(60) }
                        onChange(updated)
                    },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = { onChange(items.toMutableList().also { it.removeAt(idx) }) },
                    enabled = items.size > 1,
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete greeting")
                }
            }
        }
        OutlinedButton(
            onClick = { onChange(items + "New greeting") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add greeting") }
    }
}
