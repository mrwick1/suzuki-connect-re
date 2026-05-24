package dev.mrwick.gixxerbridge.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import kotlinx.coroutines.delay

/** Live "currently riding" banner with elapsed time, distance, avg/max speed; auto-hides when no ride. */
@Composable
fun ActiveRideCard() {
    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // PERF: shared RideStore from AppGraph (audit finding 1.5).
    val store = remember(context) { AppGraph.rideStore(context) }

    var rideStartMs by remember { mutableStateOf<Long?>(null) }
    var rideStartOdo by remember { mutableStateOf<Int?>(null) }
    var avgSpeedRunning by remember { mutableStateOf(0.0) }
    var maxSpeedRunning by remember { mutableStateOf(0) }
    var ticker by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            ticker = System.currentTimeMillis()
            store.rideInProgress()?.let {
                rideStartMs = it.startedAtMillis
                rideStartOdo = it.startOdoKm
                avgSpeedRunning = it.avgSpeedKmh
                maxSpeedRunning = it.maxSpeedKmh
            } ?: run {
                rideStartMs = null
                rideStartOdo = null
            }
            delay(1_000)
        }
    }

    val show = telemetry != null && rideStartMs != null
    AnimatedVisibility(visible = show) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(GixxerBrand.success)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "RIDING",
                        style = MaterialTheme.typography.labelLarge,
                        color = GixxerBrand.success,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val elapsed = rideStartMs?.let { (ticker - it) / 60_000 } ?: 0L
                    val distance = (telemetry?.odometerKm ?: 0) - (rideStartOdo ?: 0)
                    Stat("ELAPSED", "$elapsed min")
                    Stat("DISTANCE", "$distance km")
                    Stat("AVG", "${"%.0f".format(avgSpeedRunning)} km/h")
                    Stat("MAX", "$maxSpeedRunning km/h")
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = GixxerBrand.textSubtle)
        Text(value, style = GixxerMono.body, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PulsingDot(color: Color) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color.copy(alpha = alpha)))
}
