package dev.mrwick.gixxerbridge.ui.home

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

/** Landing screen: connection status, big start/stop button, links to pairing. */
@Composable
fun HomeScreen(onOpenPairing: () -> Unit) {
    val state by AppGraph.connectionState.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("GixxerBridge", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Connection", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stateLabel(state),
                    style = MaterialTheme.typography.headlineMedium,
                    color = stateColor(state),
                    fontWeight = FontWeight.SemiBold,
                )
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
