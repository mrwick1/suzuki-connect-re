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
import dev.mrwick.gixxerbridge.analytics.ServiceItemHealth
import dev.mrwick.gixxerbridge.analytics.ServiceSchedule
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * Home-screen card: bike-health gauge + sub-scores + ride-streak line.
 *
 * The service sub-score and the headline caption come from the per-item
 * periodic-service schedule (mirrors the five Suzuki items — see
 * DISCOVERIES.md 2026-05-25). Fuel and connection sub-scores still come from
 * [BikeHealth.compute]; we feed it the worst-item baseline so the composite
 * total tracks the per-item gauge instead of the legacy single km interval.
 */
@Composable
fun BikeHealthCard() {
    val ctx = LocalContext.current
    val settings = remember(ctx) { AppGraph.settings(ctx) }
    val store = remember(ctx) { AppGraph.rideStore(ctx) }
    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()
    val rides by store.observeRides().collectAsStateWithLifecycle(initialValue = emptyList())
    val schedule by settings.serviceSchedule
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val legacyIntervalKm by settings.serviceIntervalKm
        .collectAsStateWithLifecycle(initialValue = Settings.DEFAULT_SERVICE_INTERVAL_KM)
    val legacyLastServiced by settings.lastServiceOdoKm
        .collectAsStateWithLifecycle(initialValue = 0)

    val currentOdo = telemetry?.odometerKm
    val scheduleHealth = remember(schedule, currentOdo) {
        ServiceSchedule.mostOverdue(schedule.values, currentOdo)
    }
    // Service sub-score: prefer the per-item worst when at least one item has
    // a recorded baseline. Falls back to the legacy single-interval calc when
    // nothing has been logged yet so first-run installs aren't stuck at 50.
    val perItemServiceScore: Int? = scheduleHealth.worst?.let { worstScoreFromFraction(it) }
    val baseScore = remember(telemetry, rides, legacyIntervalKm, legacyLastServiced) {
        BikeHealth.compute(
            currentOdo = currentOdo,
            lastServiceOdo = legacyLastServiced,
            serviceIntervalKm = legacyIntervalKm,
            fuelBars = telemetry?.fuelBars,
            rides = rides,
        )
    }
    val serviceScore = perItemServiceScore ?: baseScore.service
    val total = blendTotal(serviceScore, baseScore.fuel, baseScore.connection)
    val grade = gradeFor(total)
    val caption = captionFor(scheduleHealth.worst)

    val streak = remember(rides) { RideStreak.compute(rides) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthGauge(total, modifier = Modifier.size(80.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bike health", style = MaterialTheme.typography.labelLarge)
                    Text(
                        grade,
                        style = MaterialTheme.typography.titleLarge,
                        color = colorForScore(total),
                    )
                    Text(
                        "Service $serviceScore • Fuel ${baseScore.fuel} • Connection ${baseScore.connection}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    if (caption != null) {
                        Text(
                            caption,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

/**
 * Map a per-item remaining fraction (1.0 = freshly serviced, 0.0 = at/past
 * threshold) to the same 0-100 scale [BikeHealth] uses, so the sub-score
 * label keeps reading as a percentage.
 */
private fun worstScoreFromFraction(worst: ServiceItemHealth): Int {
    val frac = worst.remainingFraction ?: return 50
    return (frac * 100.0).toInt().coerceIn(0, 100)
}

/** Same fixed-weight blend as [BikeHealth.compute] (service 34%, fuel 33%, connection 33%). */
private fun blendTotal(service: Int, fuel: Int, connection: Int): Int =
    ((service * 0.34) + (fuel * 0.33) + (connection * 0.33)).toInt().coerceIn(0, 100)

/** Mirrors [dev.mrwick.gixxerbridge.analytics.BikeHealthScore.grade]'s bands. */
private fun gradeFor(total: Int): String = when {
    total >= 85 -> "Excellent"
    total >= 65 -> "Good"
    total >= 40 -> "Fair"
    else -> "Needs attention"
}

/** "Engine oil — next in 320 km", or null when no item has a baseline. */
private fun captionFor(worst: ServiceItemHealth?): String? {
    if (worst == null) return null
    val parts = mutableListOf<String>()
    worst.kmRemaining?.let { parts += if (it >= 0) "$it km left" else "${-it} km overdue" }
    worst.daysRemaining?.let { parts += if (it >= 0) "$it days left" else "${-it} days overdue" }
    if (parts.isEmpty()) return null
    return "${worst.state.item.label} — " + parts.joinToString(" / ")
}

/** Circular Canvas gauge: dim ring + coloured arc proportional to [score]/100. */
@Composable
private fun HealthGauge(score: Int, modifier: Modifier) {
    val color = colorForScore(score)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            drawArc(
                color = GixxerTokens.surfaceElevated, // token-mapped from 0xFF334155 (dim gauge ring)
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

/** Map a 0-100 score to the gauge / grade colour (green/accent/amber/red). */
private fun colorForScore(s: Int): Color = when {
    s >= 85 -> GixxerTokens.success         // token-mapped from 0xFF10B981
    s >= 65 -> GixxerTokens.accent          // token-mapped from 0xFF22D3EE (ClusterPreview mapping)
    s >= 40 -> GixxerTokens.warning         // token-mapped from 0xFFFBBF24
    else -> GixxerTokens.danger             // token-mapped from 0xFFEF4444
}
