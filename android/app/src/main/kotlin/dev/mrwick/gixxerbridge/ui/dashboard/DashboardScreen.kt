package dev.mrwick.gixxerbridge.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame

/** Live telemetry dashboard: speed, fuel bars, trips, odometer from the bike's a537 stream. */
@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpeedCard(t)
        FuelCard(t)
        TripCard(t)
        OdometerCard(t)
        if (t == null) {
            Text(
                "Waiting for telemetry... (connect to bike + send identity to start a537 stream)",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Big "Speed" tile in km/h. */
@Composable
private fun SpeedCard(t: TelemetryFrame?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Speed", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = t?.speedKmh?.toString() ?: "—",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("km/h", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Six-bar fuel gauge (red <=1, amber <=2, green otherwise) + average km/L line. */
@Composable
private fun FuelCard(t: TelemetryFrame?) {
    val bars = t?.fuelBars ?: 0
    val econ = t?.fuelEconKmlV2
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Fuel", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(6) { i ->
                    val on = i < bars
                    val color = when {
                        !on -> Color(0xFF1F2937)
                        bars <= 1 -> Color(0xFFEF4444)
                        bars <= 2 -> Color(0xFFF59E0B)
                        else -> Color(0xFF10B981)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                econ?.let { "Avg fuel economy: ${"%.1f".format(it)} km/L" } ?: "Fuel economy unavailable",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Side-by-side Trip A / Trip B readouts in km (1 decimal). */
@Composable
private fun TripCard(t: TelemetryFrame?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Trips", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Trip A", style = MaterialTheme.typography.labelSmall)
                    Text(
                        t?.tripAKm?.let { "%.1f km".format(it) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Trip B", style = MaterialTheme.typography.labelSmall)
                    Text(
                        t?.tripBKm?.let { "%.1f km".format(it) } ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                }
            }
        }
    }
}

/** Lifetime odometer in km. */
@Composable
private fun OdometerCard(t: TelemetryFrame?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Odometer", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                t?.odometerKm?.let { "$it km" } ?: "—",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}
