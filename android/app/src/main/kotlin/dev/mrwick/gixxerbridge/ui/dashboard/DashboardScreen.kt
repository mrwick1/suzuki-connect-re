package dev.mrwick.gixxerbridge.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.analytics.RangeEstimator
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.ui.home.components.EmptyState
import dev.mrwick.gixxerbridge.ui.home.components.SpeedDisplay
import dev.mrwick.gixxerbridge.ui.home.components.SpeedState
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onOpenPairing: () -> Unit = {},
) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val kmPerBar by vm.kmPerBar.collectAsStateWithLifecycle()

    // Derive SpeedState from AppGraph.connectionState (the live BLE state)
    val connState by AppGraph.connectionState.collectAsStateWithLifecycle()
    val speedState = remember(connState) {
        when (connState) {
            is ConnectionState.Ready -> SpeedState.Connected
            is ConnectionState.Connecting, is ConnectionState.Discovering -> SpeedState.Connecting
            else -> SpeedState.Disconnected
        }
    }

    // Track timestamp of latest non-null telemetry frame using produceState
    // so we capture System.currentTimeMillis() on each new non-null value.
    val lastUpdateMs by produceState(initialValue = 0L, key1 = t) {
        if (t != null) value = System.currentTimeMillis()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(GixxerTokens.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // SpeedDisplay hero — always shown at the top
        SpeedDisplay(
            speedKmh = t?.speedKmh,
            state = speedState,
            lastUpdateMs = lastUpdateMs,
        )

        // When there's no telemetry at all (disconnected), collapse to EmptyState
        if (t == null) {
            EmptyState(
                icon = Icons.Outlined.Speed,
                body = "No telemetry yet. Connect to your bike to see speed, fuel, and trip data.",
                ctaLabel = "Open pair",
                onCta = onOpenPairing,
            )
        } else {
            // Fuel + Odo row — IntrinsicSize.Min so both cards match heights
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                FuelCard(t, kmPerBar, modifier = Modifier.weight(1f).fillMaxHeight())
                OdoCard(t, modifier = Modifier.weight(1f).fillMaxHeight())
            }

            // Trip A + Trip B row — IntrinsicSize.Min so both cards match heights
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                TripCard(
                    label = "Trip A",
                    value = t?.tripAKm,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                TripCard(
                    label = "Trip B",
                    value = t?.tripBKm,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }

            FuelEconomyCard(t)
        }
    }
}

@Composable
private fun FuelCard(t: TelemetryFrame?, kmPerBar: Double?, modifier: Modifier = Modifier) {
    val bars = t?.fuelBars ?: 0
    val rangeKm = RangeEstimator.estimateRemainingKm(t?.fuelBars, kmPerBar)
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardLabel(Icons.Outlined.LocalGasStation, "FUEL")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(6) { i ->
                    val on = i < bars
                    val color = when {
                        !on -> MaterialTheme.colorScheme.surfaceContainerLow
                        bars <= 1 -> GixxerTokens.danger
                        bars <= 2 -> GixxerTokens.warning
                        else -> GixxerTokens.success
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
                    "Fuel data will appear once the bike reports telemetry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
            } else if (rangeKm != null) {
                Text(
                    "~${rangeKm.toInt()} km range",
                    style = GixxerMono.body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Text(
                    "Range building…",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
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
        color = GixxerTokens.danger.copy(alpha = 0.18f),
        contentColor = GixxerTokens.danger,
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
            CardLabel(Icons.Outlined.Straighten, "ODOMETER")
            Spacer(Modifier.height(8.dp))
            if (t?.odometerKm != null) {
                Text(
                    text = "${t.odometerKm}",
                    style = GixxerMono.headline,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("km", style = MaterialTheme.typography.bodySmall, color = GixxerTokens.textMuted)
            } else {
                Text(
                    "Odometer data not yet received.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
            }
        }
    }
}

@Composable
private fun TripCard(label: String, value: Double?, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            CardLabel(Icons.Outlined.Route, label.uppercase())
            Spacer(Modifier.height(8.dp))
            if (value != null) {
                Text(
                    text = "%.1f".format(value),
                    style = GixxerMono.headline,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text("km", style = MaterialTheme.typography.bodySmall, color = GixxerTokens.textMuted)
            } else {
                Text(
                    "No trip data yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
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
            CardLabel(Icons.Outlined.LocalGasStation, "FUEL ECONOMY (TRIP AVG)")
            Spacer(Modifier.height(8.dp))
            if (econ != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "%.1f".format(econ),
                        style = GixxerMono.headline,
                        color = GixxerTokens.accent,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "km/L",
                        style = MaterialTheme.typography.bodyMedium,
                        color = GixxerTokens.textMuted,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            } else {
                Text(
                    "Bike reports this once the engine has run for a bit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
            }
        }
    }
}

@Composable
private fun CardLabel(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = GixxerTokens.textMuted,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = GixxerTokens.textMuted)
    }
}
