package dev.mrwick.gixxerbridge.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.ui.about.AboutCardLink
import dev.mrwick.gixxerbridge.ui.safety.SafetySection
import dev.mrwick.gixxerbridge.ui.safety.SafetyViewModel
import dev.mrwick.gixxerbridge.ui.theme.ACCENT_PALETTE

/** Settings screen: rider identity, bike pairing, cluster toggles, phone behavior, maintenance. */
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    safetyVm: SafetyViewModel,
    onOpenPairing: () -> Unit,
    onOpenAllowlist: () -> Unit,
    onOpenInspector: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenMileage: () -> Unit = {},
    onOpenServiceHistory: () -> Unit = {},
) {
    val riderName by vm.riderName.collectAsStateWithLifecycle()
    val bikeMac by vm.bikeMac.collectAsStateWithLifecycle()
    val autoStart by vm.autoStartOnBoot.collectAsStateWithLifecycle()
    val appLock by vm.appLockEnabled.collectAsStateWithLifecycle()
    val idleClock by vm.idleClockEnabled.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlayingOnCluster.collectAsStateWithLifecycle()
    val dnd by vm.autoDndOnConnect.collectAsStateWithLifecycle()
    val service by vm.serviceIntervalKm.collectAsStateWithLifecycle()
    val demoMode by vm.demoMode.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val themeAccent by vm.themeAccent.collectAsStateWithLifecycle()
    val greetings by vm.greetings.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Section("Identity") {
                var name by remember(riderName) { mutableStateOf(riderName) }
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it.take(20)
                        vm.setRiderName(name)
                    },
                    label = { Text("Rider name (20 chars max)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Section("Bike") {
                Text(
                    "MAC: ${bikeMac ?: "(none paired)"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenPairing) {
                    Text(if (bikeMac == null) "Pair a bike" else "Re-pair")
                }
            }
        }
        item {
            Section("Cluster") {
                SwitchRow("Show clock + weather when nav idle", idleClock, vm::setIdleClockEnabled)
                SwitchRow("Show Now Playing scrolling text", nowPlaying, vm::setNowPlayingOnCluster)
            }
        }
        item {
            Section("Theme accent") {
                Text(
                    "Pick the highlight colour used across the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                AccentSwatchRow(
                    selected = themeAccent,
                    onSelect = vm::setThemeAccent,
                )
            }
        }
        item {
            Section("Cluster greetings") {
                Text(
                    "Sent once on each bike connect. {name} is replaced with your rider name.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                GreetingsEditor(
                    items = greetings,
                    onChange = vm::setGreetings,
                )
            }
        }
        item {
            Section("Phone") {
                SwitchRow("Auto-start GixxerBridge after boot", autoStart, vm::setAutoStartOnBoot)
                SwitchRow("Auto-DND when bike connects", dnd, vm::setAutoDndOnConnect)
                SwitchRow("Require unlock on app launch", appLock, vm::setAppLockEnabled)
                SwitchRow("Keep screen on while connected", keepScreenOn, vm::setKeepScreenOnWhileConnected)
            }
        }
        item { DndAccessPermissionRow() }
        item {
            Section("Maintenance") {
                var t by remember(service) { mutableStateOf(service.toString()) }
                OutlinedTextField(
                    value = t,
                    onValueChange = { raw ->
                        t = raw.filter { c -> c.isDigit() }.take(6)
                        t.toIntOrNull()?.let { km -> vm.setServiceIntervalKm(km) }
                    },
                    label = { Text("Service interval (km)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenMileage) { Text("Fuel log / true mileage") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenServiceHistory) { Text("Service history") }
            }
        }
        item { NotificationAccessRow() }
        item {
            Section("Notifications mirrored to bike") {
                Button(onClick = onOpenAllowlist) { Text("Edit allowlist") }
            }
        }
        item {
            SafetySection(safetyVm)
        }
        item { SendSmsPermissionRow() }
        item {
            Section("Developer") {
                SwitchRow("Demo mode (simulated bike telemetry)", demoMode, vm::setDemoMode)
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenInspector) { Text("Open frame inspector") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { vm.resetOnboarding() }) {
                    Text("Reset onboarding (replay wizard)")
                }
            }
        }
        item { AboutCardLink(onClick = onOpenAbout) }
    }
}

/** Titled Material3 card grouping related settings rows. */
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

/** A label + Material3 [Switch] aligned on a single row.
 *  Off-state colors are explicitly brightened so the track stays visible
 *  against the dark `surfaceContainerHigh` card background (default M3 dark
 *  gray blends into the card). The whole row is clickable so the touch
 *  target is the full width, not just the ~50dp Switch. */
@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
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
                uncheckedTrackColor = Color(0xFF334155),
                uncheckedThumbColor = Color(0xFF94A3B8),
                uncheckedBorderColor = Color(0xFF475569),
            ),
        )
    }
}

/** Row of accent swatches; selected swatch gets an outline ring. */
@Composable
private fun AccentSwatchRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ACCENT_PALETTE.forEach { (name, color) ->
            val isSelected = name == selected
            val ring = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent
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

/** Editable list of greeting templates with per-row delete and an add button. */
@Composable
private fun GreetingsEditor(items: List<String>, onChange: (List<String>) -> Unit) {
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
                    // Keep at least one greeting around so the welcome frame
                    // always has something to pick.
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
