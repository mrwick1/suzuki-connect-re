package dev.mrwick.gixxerbridge.ui.home

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.analytics.ServiceItemHealth
import dev.mrwick.gixxerbridge.analytics.ServiceSchedule
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.BikeBridgeService
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import dev.mrwick.gixxerbridge.ui.cluster.ClusterPreview
import dev.mrwick.gixxerbridge.ui.home.LastParkedCard
import kotlinx.coroutines.launch

/** Landing screen: connection status, big start/stop button, links to pairing. */
@Composable
fun HomeScreen(onOpenPairing: () -> Unit) {
    val state by AppGraph.connectionState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("GixxerBridge", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)

        ServiceDueBanner()

        ClusterPreview()

        ActiveRideCard()

        BikeHealthCard()

        LastParkedCard()

        RideSummaryCard()

        QuickDestinationsCard()

        val isFailed = state is ConnectionState.Failed
        val isIdle = state is ConnectionState.Idle
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (isFailed) it.clickable {
                    val intent = Intent(ctx, BikeBridgeService::class.java)
                    ctx.stopService(intent)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(ctx, intent)
                    } else {
                        ctx.startService(intent)
                    }
                } else it },
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Connection", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFailed) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = stateLabel(state),
                        style = MaterialTheme.typography.headlineMedium,
                        color = stateColor(state),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (isIdle) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap Start GixxerBridge below to connect.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF94A3B8),
                    )
                } else if (isFailed) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Tap to retry",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFEF4444),
                    )
                }
            }
        }

        val isRunning = state !is ConnectionState.Idle
        Button(
            onClick = {
                val intent = Intent(ctx, BikeBridgeService::class.java)
                if (isRunning) {
                    ctx.stopService(intent)
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(ctx, intent)
                    } else {
                        ctx.startService(intent)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isRunning) "Stop GixxerBridge" else "Start GixxerBridge")
        }

        OutlinedButton(onClick = onOpenPairing, modifier = Modifier.fillMaxWidth()) {
            Text("Pair / re-pair bike")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Quick check", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Make sure you've granted Notification access in system Settings " +
                        "(Settings -> Apps -> Special access -> Notification access) so we can " +
                        "forward Google Maps directions.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun stateLabel(s: ConnectionState): String = when (s) {
    ConnectionState.Idle -> "Idle"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Discovering -> "Discovering services…"
    ConnectionState.Ready -> "Connected"
    is ConnectionState.Disconnected -> "Disconnected (status=${s.status})"
    is ConnectionState.Failed -> "Failed: ${s.reason}"
}

private fun stateColor(s: ConnectionState): Color = when (s) {
    ConnectionState.Ready -> Color(0xFF10B981)
    is ConnectionState.Failed -> Color(0xFFEF4444)
    ConnectionState.Idle -> Color(0xFF94A3B8)
    else -> Color(0xFFFCD34D)
}

/**
 * Red banner that appears when at least one periodic-service item is overdue
 * (km gate negative OR days gate negative). The shown item is the worst —
 * the one with the smallest "fraction of life remaining" across all five.
 *
 * Tap "Mark serviced" to stamp this item's baseline to "now" + current odo.
 * Items without a baseline are excluded so a fresh install doesn't false-flag.
 *
 * Service-detection model documented in DISCOVERIES.md 2026-05-25.
 */
@Composable
private fun ServiceDueBanner() {
    val context = LocalContext.current
    val settings = remember(context) { AppGraph.settings(context) }
    val schedule by settings.serviceSchedule
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()
    val currentOdo = telemetry?.odometerKm
    val worst: ServiceItemHealth? = remember(schedule, currentOdo) {
        ServiceSchedule.mostOverdue(schedule.values, currentOdo).worst
    }
    val isOverdue = worst != null && (
        (worst.kmRemaining != null && worst.kmRemaining < 0) ||
        (worst.daysRemaining != null && worst.daysRemaining < 0)
    )
    if (!isOverdue) return
    val item = worst!!

    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Service due: ${item.state.item.label}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                buildOverdueDetail(item),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                scope.launch { settings.markServiceDone(item.state.item, currentOdo) }
            }) {
                Text("Mark serviced")
            }
        }
    }
}

/** "Overdue by 320 km / 14 days." (one or both clauses, depending on what's negative). */
private fun buildOverdueDetail(h: ServiceItemHealth): String {
    val parts = mutableListOf<String>()
    h.kmRemaining?.takeIf { it < 0 }?.let { parts += "${-it} km" }
    h.daysRemaining?.takeIf { it < 0 }?.let { parts += "${-it} days" }
    return "Overdue by " + parts.joinToString(" / ") + "."
}
