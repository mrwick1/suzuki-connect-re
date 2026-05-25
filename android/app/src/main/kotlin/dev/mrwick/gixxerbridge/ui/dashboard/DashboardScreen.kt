package dev.mrwick.gixxerbridge.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.analytics.RangeEstimator
import dev.mrwick.gixxerbridge.ble.BikeBridgeService
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.ui.cluster.ClusterPreview
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono

@Composable
fun DashboardScreen(vm: DashboardViewModel) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val kmPerBar by vm.kmPerBar.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ClusterPreview()
        SpeedHeroCard(t)
        // IntrinsicSize.Min + fillMaxHeight on each card → both cards in the row
        // grow to the tallest sibling's intrinsic height, so Fuel (which has bars +
        // range text + optional LOW FUEL pill) and Odometer (just two text lines)
        // end up the same height. Was: each card sized to its own content, so the
        // Odometer card looked stubby next to the taller Fuel card.
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            FuelCard(t, kmPerBar, modifier = Modifier.weight(1f).fillMaxHeight())
            OdoCard(t, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        ) {
            TripCard(label = "Trip A", value = t?.tripAKm, modifier = Modifier.weight(1f).fillMaxHeight())
            TripCard(label = "Trip B", value = t?.tripBKm, modifier = Modifier.weight(1f).fillMaxHeight())
        }
        FuelEconomyCard(t)
    }
}

@Composable
private fun SpeedHeroCard(t: TelemetryFrame?) {
    val speed = t?.speedKmh ?: 0
    val animatedSpeed by animateFloatAsState(
        targetValue = speed.toFloat(),
        animationSpec = spring(stiffness = 200f),
        label = "speed",
    )
    val accent by animateColorAsState(
        targetValue = when {
            speed >= 80 -> GixxerBrand.danger
            speed >= 60 -> GixxerBrand.warning
            speed > 0 -> GixxerBrand.accent
            else -> GixxerBrand.textSubtle
        },
        label = "accent",
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, contentDescription = null, tint = accent)
                Spacer(Modifier.width(8.dp))
                Text("CURRENT SPEED", style = MaterialTheme.typography.labelMedium, color = accent)
            }
            Spacer(Modifier.height(12.dp))
            if (t?.speedKmh != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = animatedSpeed.toInt().toString().padStart(3, ' '),
                        style = GixxerMono.display,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "km/h",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            } else {
                // Pulse the placeholder dash so it's visually obvious we're
                // waiting on a value (vs. the bike reporting a literal "—").
                val infinite = rememberInfiniteTransition(label = "speedShimmer")
                val pulseAlpha by infinite.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.85f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "speedShimmerAlpha",
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "—",
                        style = GixxerMono.display,
                        color = accent.copy(alpha = pulseAlpha),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Waiting for bike telemetry",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FuelCard(t: TelemetryFrame?, kmPerBar: Double?, modifier: Modifier = Modifier) {
    val bars = t?.fuelBars ?: 0
    val rangeKm = RangeEstimator.estimateRemainingKm(t?.fuelBars, kmPerBar)
    val ctx = LocalContext.current
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardLabel(Icons.Default.LocalGasStation, "FUEL")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(6) { i ->
                    val on = i < bars
                    val color = when {
                        !on -> MaterialTheme.colorScheme.surfaceContainerLow
                        bars <= 1 -> GixxerBrand.danger
                        bars <= 2 -> GixxerBrand.warning
                        else -> GixxerBrand.success
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(color),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            if (t?.fuelBars == null) {
                Text(
                    "Start the service and turn the bike key on to see fuel data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerBrand.textSubtle,
                )
                TextButton(
                    onClick = {
                        val intent = Intent(ctx, BikeBridgeService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(ctx, intent)
                        } else {
                            ctx.startService(intent)
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("Start service", style = MaterialTheme.typography.labelMedium)
                }
            } else if (rangeKm != null) {
                Text(
                    "~${rangeKm.toInt()} km range",
                    style = GixxerMono.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text("Range building…", style = MaterialTheme.typography.bodySmall, color = GixxerBrand.textSubtle)
            }
            if (bars in 1..1) {
                Spacer(Modifier.height(8.dp))
                LowFuelPill()
            }
        }
    }
}

@Composable
private fun LowFuelPill() {
    Surface(
        color = GixxerBrand.danger.copy(alpha = 0.18f),
        contentColor = GixxerBrand.danger,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "LOW FUEL",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun OdoCard(t: TelemetryFrame?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardLabel(Icons.Default.Straighten, "ODOMETER")
            Spacer(Modifier.height(8.dp))
            if (t?.odometerKm != null) {
                Text(
                    text = "${t.odometerKm}",
                    style = GixxerMono.headline,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("km", style = MaterialTheme.typography.bodySmall, color = GixxerBrand.textSubtle)
            } else {
                Text(
                    "Connect to bike to read odometer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerBrand.textSubtle,
                )
            }
        }
    }
}

@Composable
private fun TripCard(label: String, value: Double?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardLabel(Icons.Default.Route, label.uppercase())
            Spacer(Modifier.height(8.dp))
            if (value != null) {
                Text(
                    text = "%.1f".format(value),
                    style = GixxerMono.headline,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("km", style = MaterialTheme.typography.bodySmall, color = GixxerBrand.textSubtle)
            } else {
                Text(
                    "No trip data — connect to bike to populate.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerBrand.textSubtle,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun FuelEconomyCard(t: TelemetryFrame?) {
    val econ = t?.fuelEconKmlV2
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardLabel(Icons.Default.LocalGasStation, "FUEL ECONOMY (TRIP AVG)")
            Spacer(Modifier.height(8.dp))
            if (econ != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%.1f".format(econ),
                        style = GixxerMono.headline,
                        color = GixxerBrand.accent,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "km/L",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GixxerBrand.textSubtle,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            } else {
                Text(
                    "Bike reports this once the engine has run for a bit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerBrand.textSubtle,
                )
            }
        }
    }
}

@Composable
private fun CardLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = GixxerBrand.textSubtle,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = GixxerBrand.textSubtle)
    }
}
