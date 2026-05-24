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
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.BikeBridgeService
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.data.Settings
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
 * Red banner that appears when the bike's odo has clocked past the user's
 * service interval. Tap "Mark serviced" to bump [Settings.lastServiceOdoKm]
 * to the current reading.
 *
 * ASSUMED: it's OK to construct a [Settings] instance per composition — the
 * underlying DataStore is process-wide so this is just a thin handle. Matches
 * the existing pattern elsewhere in the UI layer.
 */
@Composable
private fun ServiceDueBanner() {
    val context = LocalContext.current
    val settings = remember { Settings(context.applicationContext) }
    val intervalKm by settings.serviceIntervalKm.collectAsStateWithLifecycle(
        initialValue = Settings.DEFAULT_SERVICE_INTERVAL_KM,
    )
    val lastServiced by settings.lastServiceOdoKm.collectAsStateWithLifecycle(initialValue = 0)
    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()
    val currentOdo = telemetry?.odometerKm ?: 0

    if (currentOdo <= 0 || (currentOdo - lastServiced) < intervalKm) return

    val nextServiceOdo = lastServiced + intervalKm
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Service due!",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Logged $currentOdo km, last serviced at $lastServiced km " +
                    "(next service was due at $nextServiceOdo km).",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                scope.launch { settings.setLastServiceOdoKm(currentOdo) }
            }) {
                Text("Mark serviced")
            }
        }
    }
}
