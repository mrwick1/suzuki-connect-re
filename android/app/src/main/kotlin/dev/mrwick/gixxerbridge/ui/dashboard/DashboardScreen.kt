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
import androidx.compose.material3.Surface
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
import dev.mrwick.gixxerbridge.analytics.RangeEstimator
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.ui.cluster.ClusterPreview

/** Live telemetry dashboard: speed, fuel bars, trips, odometer from the bike's a537 stream. */
@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val kmPerBar by vm.kmPerBar.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClusterPreview()
        SpeedCard(t)
        FuelCard(t)
        RangeCard(t, kmPerBar)
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

/**
 * Range estimate (km remaining) from current fuel bars and historical km/bar.
 * Also flashes a "Low fuel" badge when current bars <= 1.
 */
@Composable
private fun RangeCard(t: TelemetryFrame?, kmPerBar: Double?) {
    val km = RangeEstimator.estimateRemainingKm(t?.fuelBars, kmPerBar)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Range estimate", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(4.dp))
            if (km != null) {
                Text("~${km.toInt()} km", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${"%.1f".format(kmPerBar ?: 0.0)} km per fuel bar (from ride history)",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text("—", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Need rides with start/end fuel logged.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // ASSUMED: treat "fuel telemetry not yet received" (null) as not-low.
            // Default sentinel 99 keeps the badge hidden until we have a real reading.
            if ((t?.fuelBars ?: 99) <= 1) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color(0xFF7F1D1D),
                    contentColor = Color.White,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        "Low fuel — refuel soon",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
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
