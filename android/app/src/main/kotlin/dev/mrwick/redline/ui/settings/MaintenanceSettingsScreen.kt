package dev.mrwick.redline.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.analytics.EtaGate
import dev.mrwick.redline.analytics.ServiceEta
import dev.mrwick.redline.analytics.ServiceEtaForecast
import dev.mrwick.redline.analytics.ServiceSchedule
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.data.ServiceItem
import dev.mrwick.redline.data.ServiceItemState
import dev.mrwick.redline.telemetry.TelemetryRepository
import dev.mrwick.redline.ui.theme.GixxerTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Maintenance sub-screen: five service-item editors, legacy interval field,
 * fuel log link, and service history link.
 * Route: settings/maintenance
 */
@Composable
fun MaintenanceSettingsScreen(
    vm: SettingsViewModel,
    onOpenMileage: () -> Unit,
    onOpenServiceHistory: () -> Unit,
) {
    val serviceSchedule by vm.serviceSchedule.collectAsStateWithLifecycle()
    val service by vm.serviceIntervalKm.collectAsStateWithLifecycle()
    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()
    val currentOdoKm = telemetry?.odometerKm?.takeIf { it > 0 }

    val ctx = LocalContext.current
    val rideStore = remember(ctx) { AppGraph.rideStore(ctx) }
    val rides by rideStore.observeRides().collectAsStateWithLifecycle(initialValue = emptyList())
    val paceKmPerDay = remember(rides) { ServiceEta.paceKmPerDay(rides) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSection("Service schedule") {
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
                    MaintenanceServiceItemEditor(
                        state = state,
                        currentOdoKm = currentOdoKm,
                        paceKmPerDay = paceKmPerDay,
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
                Button(onClick = onOpenMileage, modifier = Modifier.fillMaxWidth()) {
                    Text("Fuel log / true mileage")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenServiceHistory, modifier = Modifier.fillMaxWidth()) {
                    Text("Service history")
                }
            }
        }
    }
}

private fun defaultStateFor(item: ServiceItem): ServiceItemState =
    ServiceItemState(
        item = item,
        kmThreshold = item.defaultKm,
        daysThreshold = item.defaultDays,
        lastServiceDateMs = null,
        lastServiceOdoKm = null,
    )

@Composable
private fun MaintenanceServiceItemEditor(
    state: ServiceItemState,
    currentOdoKm: Int?,
    paceKmPerDay: Double,
    onUpdateThresholds: (kmThreshold: Int?, daysThreshold: Int) -> Unit,
    onMarkServiced: () -> Unit,
) {
    val health = remember(state, currentOdoKm) {
        ServiceSchedule.healthFor(state, currentOdoKm)
    }
    val eta = remember(health, paceKmPerDay) {
        ServiceEta.forecast(health, paceKmPerDay)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(state.item.label, style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = maintenanceNextDueSubtext(health.daysRemaining, health.kmRemaining, state),
            style = MaterialTheme.typography.bodySmall,
            color = maintenanceSubtextColor(health.daysRemaining, health.kmRemaining),
        )
        // Service ETA forecast line — only when pace is known (recent rides exist)
        // AND a forecast can be computed from at least one gate. Suppressed when
        // pace is zero to avoid a calendar-only repeat of the days subtext above.
        if (eta != null && paceKmPerDay > 0.0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = etaLine(eta),
                style = MaterialTheme.typography.labelSmall,
                color = if (eta.isOverdue) GixxerTokens.danger
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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

private fun maintenanceNextDueSubtext(
    daysRemaining: Int?,
    kmRemaining: Int?,
    state: ServiceItemState,
): String {
    if (state.lastServiceDateMs == null && state.lastServiceOdoKm == null) {
        return "No baseline yet — tap below after your next service."
    }
    val parts = mutableListOf<String>()
    if (kmRemaining != null) parts += maintenanceFormatGate(kmRemaining, "km")
    if (daysRemaining != null) parts += maintenanceFormatGate(daysRemaining, "days")
    if (parts.isEmpty()) return "Baseline partial — set both date and odometer to enable the gauge."
    return "Next due in " + parts.joinToString(" / ")
}

private fun maintenanceFormatGate(remaining: Int, unit: String): String =
    if (remaining >= 0) "$remaining $unit" else "${-remaining} $unit overdue"

@Composable
private fun maintenanceSubtextColor(
    daysRemaining: Int?,
    kmRemaining: Int?,
): androidx.compose.ui.graphics.Color {
    val worst = listOfNotNull(daysRemaining, kmRemaining).minOrNull()
        ?: return MaterialTheme.colorScheme.onSurfaceVariant
    return when {
        worst < 0 -> GixxerTokens.danger
        worst < 30 -> GixxerTokens.warning
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/**
 * Format a service ETA forecast as a single-line "Forecast: ~18 days ≈ 24 Jun at your pace"
 * string for the per-item maintenance editor. Uses the device's default time zone and locale
 * so the date renders in the user's local format. Pure Kotlin (java.time) — no Android.
 */
private fun etaLine(eta: ServiceEtaForecast): String {
    if (eta.isOverdue) return "Forecast: due now"
    val date = Instant.ofEpochMilli(eta.dueAtMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM"))
    val gateNote = if (eta.gate == EtaGate.CALENDAR) " (calendar gate)" else " at your pace"
    return "Forecast: ${ServiceEta.formatRelative(eta)} ≈ $date$gateNote"
}
