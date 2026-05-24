package dev.mrwick.gixxerbridge.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.analytics.BikeHealth
import dev.mrwick.gixxerbridge.analytics.RideStreak
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository

/** Home-screen card: bike health gauge + sub-scores + ride-streak line. */
@Composable
fun BikeHealthCard() {
    val ctx = LocalContext.current
    val settings = remember(ctx) { AppGraph.settings(ctx) }
    val store = remember(ctx) { AppGraph.rideStore(ctx) }
    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()
    val rides by store.observeRides().collectAsStateWithLifecycle(initialValue = emptyList())
    val intervalKm by settings.serviceIntervalKm
        .collectAsStateWithLifecycle(initialValue = Settings.DEFAULT_SERVICE_INTERVAL_KM)
    val lastServiced by settings.lastServiceOdoKm.collectAsStateWithLifecycle(initialValue = 0)

    val score = remember(telemetry, rides, intervalKm, lastServiced) {
        BikeHealth.compute(
            currentOdo = telemetry?.odometerKm,
            lastServiceOdo = lastServiced,
            serviceIntervalKm = intervalKm,
            fuelBars = telemetry?.fuelBars,
            rides = rides,
        )
    }
    val streak = remember(rides) { RideStreak.compute(rides) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthGauge(score.total, modifier = Modifier.size(80.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bike health", style = MaterialTheme.typography.labelLarge)
                    Text(
                        score.grade,
                        style = MaterialTheme.typography.titleLarge,
                        color = colorForScore(score.total),
                    )
                    Text(
                        "Service ${score.service} • Fuel ${score.fuel} • Connection ${score.connection}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (streak.current > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "🔥 ${streak.current}-day ride streak (best ${streak.longest})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Circular Canvas gauge: dim ring + coloured arc proportional to [score]/100. */
@Composable
private fun HealthGauge(score: Int, modifier: Modifier) {
    val color = colorForScore(score)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            drawArc(
                color = Color(0xFF334155),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = (score.coerceIn(0, 100) / 100f) * 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text("$score", style = MaterialTheme.typography.titleLarge, color = color)
    }
}

/** Map a 0-100 score to the gauge / grade colour (green/cyan/amber/red). */
private fun colorForScore(s: Int): Color = when {
    s >= 85 -> Color(0xFF10B981)
    s >= 65 -> Color(0xFF22D3EE)
    s >= 40 -> Color(0xFFFBBF24)
    else -> Color(0xFFEF4444)
}
