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
import dev.mrwick.gixxerbridge.analytics.ServiceSchedule
import dev.mrwick.gixxerbridge.data.ServiceItem
import dev.mrwick.gixxerbridge.data.ServiceItemState
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
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
    onOpenDiagnostics: () -> Unit = {},
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
    val serviceSchedule by vm.serviceSchedule.collectAsStateWithLifecycle()
    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()
    val currentOdoKm = telemetry?.odometerKm?.takeIf { it > 0 }
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
                Button(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (bikeMac == null) "Pair a bike" else "Re-pair")
                }
                if (bikeMac != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    var confirm by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = { confirm = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Forget this bike") }
                    if (confirm) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { confirm = false },
                            title = { Text("Forget paired bike?") },
                            text = {
                                Text(
                                    "GixxerBridge will stop trying to auto-connect to " +
                                        "${bikeMac}. You can pair again from this screen.",
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    vm.forgetBike()
                                    confirm = false
                                }) { Text("Forget") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { confirm = false }) {
                                    Text("Cancel")
                                }
                            },
                        )
                    }
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
                Text(
                    "Five periodic-service items mirrored from the Suzuki Connect app " +
                        "(see DISCOVERIES.md 2026-05-25). Each has a km gate (when applicable) " +
                        "and a days gate — the bike has no oil sensor, so this is purely a reminder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                ServiceItem.entries.forEach { item ->
                    val state = serviceSchedule[item] ?: defaultStateFor(item)
                    ServiceItemEditor(
                        state = state,
                        currentOdoKm = currentOdoKm,
                        onUpdateThresholds = { km, days ->
                            vm.setServiceItemThresholds(item, km, days)
                        },
                        onMarkServiced = { vm.markServiceDone(item, currentOdoKm) },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Legacy single-gauge interval (used by the bike-health card until the per-item gauge replaces it).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                var t by remember(service) { mutableStateOf(service.toString()) }
                OutlinedTextField(
                    value = t,
                    onValueChange = { raw ->
                        t = raw.filter { c -> c.isDigit() }.take(6)
                        t.toIntOrNull()?.let { km -> vm.setServiceIntervalKm(km) }
                    },
                    label = { Text("Legacy service interval (km)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenMileage, modifier = Modifier.fillMaxWidth()) { Text("Fuel log / true mileage") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenServiceHistory, modifier = Modifier.fillMaxWidth()) { Text("Service history") }
            }
        }
        item { NotificationAccessRow() }
        item {
            Section("Notifications mirrored to bike") {
                Button(onClick = onOpenAllowlist, modifier = Modifier.fillMaxWidth()) { Text("Edit allowlist") }
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
                Button(onClick = onOpenInspector, modifier = Modifier.fillMaxWidth()) { Text("Open frame inspector") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Diagnostics / log viewer") }
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

/** Bare-default [ServiceItemState] used when the schedule map hasn't loaded yet. */
private fun defaultStateFor(item: ServiceItem): ServiceItemState =
    ServiceItemState(
        item = item,
        kmThreshold = item.defaultKm,
        daysThreshold = item.defaultDays,
        lastServiceDateMs = null,
        lastServiceOdoKm = null,
    )

/**
 * One service item's editor row: name + "next due" sub-text + km/days inputs +
 * "I just serviced this" button. Inputs are debounced via local state so each
 * keystroke doesn't fire a DataStore write; the actual save happens on every
 * change the same as the other settings rows (DataStore preference writes are
 * cheap and coalesced internally).
 */
@Composable
private fun ServiceItemEditor(
    state: ServiceItemState,
    currentOdoKm: Int?,
    onUpdateThresholds: (kmThreshold: Int?, daysThreshold: Int) -> Unit,
    onMarkServiced: () -> Unit,
) {
    val health = remember(state, currentOdoKm) {
        ServiceSchedule.healthFor(state, currentOdoKm)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(state.item.label, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = nextDueSubtext(health.daysRemaining, health.kmRemaining, state),
            style = MaterialTheme.typography.bodySmall,
            color = subtextColor(health.daysRemaining, health.kmRemaining),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.kmThreshold != null || state.item.defaultKm != null) {
                var kmText by remember(state.kmThreshold) {
                    mutableStateOf((state.kmThreshold ?: state.item.defaultKm ?: 0).toString())
                }
                OutlinedTextField(
                    value = kmText,
                    onValueChange = { raw ->
                        kmText = raw.filter { it.isDigit() }.take(6)
                        val parsed = kmText.toIntOrNull()
                        if (parsed != null && parsed > 0) {
                            onUpdateThresholds(parsed, state.daysThreshold)
                        }
                    },
                    label = { Text("km") },
                    modifier = Modifier.weight(1f),
                )
            } else {
                // Days-only item (brake oil) — keep layout balanced with a
                // disabled placeholder so the days field doesn't stretch.
                Text(
                    "Days-only (no km gate)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            var daysText by remember(state.daysThreshold) {
                mutableStateOf(state.daysThreshold.toString())
            }
            OutlinedTextField(
                value = daysText,
                onValueChange = { raw ->
                    daysText = raw.filter { it.isDigit() }.take(5)
                    val parsed = daysText.toIntOrNull()
                    if (parsed != null && parsed > 0) {
                        onUpdateThresholds(state.kmThreshold, parsed)
                    }
                },
                label = { Text("days") },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onMarkServiced, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (state.lastServiceDateMs == null) "I just serviced this"
                else "I just serviced this again",
            )
        }
    }
}

/** Render "Next due in X km / Y days" — or "Set a baseline" when nothing recorded. */
private fun nextDueSubtext(daysRemaining: Int?, kmRemaining: Int?, state: ServiceItemState): String {
    if (state.lastServiceDateMs == null && state.lastServiceOdoKm == null) {
        return "No baseline yet — tap below after your next service."
    }
    val parts = mutableListOf<String>()
    if (kmRemaining != null) parts += formatGate(kmRemaining, "km")
    if (daysRemaining != null) parts += formatGate(daysRemaining, "days")
    if (parts.isEmpty()) return "Baseline partial — set both date and odometer to enable the gauge."
    return "Next due in " + parts.joinToString(" / ")
}

/** "in 1234 km" → "1234 km left"; negative → "overdue by N km". */
private fun formatGate(remaining: Int, unit: String): String =
    if (remaining >= 0) "$remaining $unit" else "${-remaining} $unit overdue"

/** Red for overdue, amber for near-due, default for healthy. */
@Composable
private fun subtextColor(daysRemaining: Int?, kmRemaining: Int?): Color {
    val worst = listOfNotNull(daysRemaining, kmRemaining).minOrNull() ?: return MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        worst < 0 -> Color(0xFFEF4444)
        worst < 30 -> Color(0xFFFBBF24)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
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
