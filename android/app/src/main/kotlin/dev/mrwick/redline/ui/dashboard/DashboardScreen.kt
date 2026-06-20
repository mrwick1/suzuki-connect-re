package dev.mrwick.redline.ui.dashboard

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.analytics.RangeEstimator
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.ble.ConnectionState
import dev.mrwick.redline.protocol.TelemetryFrame
import dev.mrwick.redline.ui.components.BentoTile
import dev.mrwick.redline.ui.components.OdometerNumber
import dev.mrwick.redline.ui.components.Sweep
import dev.mrwick.redline.ui.components.TraceChart
import dev.mrwick.redline.ui.theme.GixxerBrand
import dev.mrwick.redline.ui.theme.GixxerMono

private const val MAX_SPEED = 120f

/**
 * REDLINE PRESS Dashboard — the live telemetry cockpit (research:
 * docs/superpowers/research/2026-06-04-screen-research.md). The Sweep is the speed
 * hero; bento tiles carry fuel/odo/trip/economy; a TraceChart shows recent speed.
 * Read parked/stopped, so it's a rich cockpit, not a glance HUD.
 */
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    onOpenPairing: () -> Unit = {},
) {
    val t by vm.telemetry.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val kmPerBar by vm.kmPerBar.collectAsStateWithLifecycle()
    val connState by AppGraph.connectionState.collectAsStateWithLifecycle()
    val live = connState is ConnectionState.Ready

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SpeedHero(speed = t?.speedKmh, live = live, connLabel = stateLabel(connState))

        if (t == null) {
            BentoTile(Modifier.fillMaxWidth(), animateEntry = false, onClick = onOpenPairing) {
                Text("NO TELEMETRY YET", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Connect to your bike to see live speed, fuel, odometer and trip data. Tap to pair.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FuelTile(t, kmPerBar, Modifier.weight(1f))
                StatTile("ODOMETER · KM", (t?.odometerKm ?: 0).toLong(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TripTile("TRIP A", t?.tripAKm, Modifier.weight(1f))
                TripTile("TRIP B", t?.tripBKm, Modifier.weight(1f))
            }
            FuelEconomyTile(t)
            if (history.size >= 2) SpeedHistoryTile(history)
        }
    }
}

@Composable
private fun SpeedHero(speed: Int?, live: Boolean, connLabel: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Speed ${speed ?: 0} kilometres per hour" +
                    if (live) ", live" else ", $connLabel"
            },
        contentAlignment = Alignment.Center,
    ) {
        Sweep(
            progress = (speed ?: 0) / MAX_SPEED,
            modifier = Modifier.size(280.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OdometerNumber(
                    value = (speed ?: 0).toLong(),
                    style = GixxerMono.display.copy(fontSize = 96.sp),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text("KM / H", style = MaterialTheme.typography.labelLarge, color = GixxerBrand.accent)
                Text(
                    if (live) "LIVE" else connLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun FuelTile(t: TelemetryFrame?, kmPerBar: Double?, modifier: Modifier) {
    val bars = t?.fuelBars ?: 0
    val range = RangeEstimator.estimateRemainingKm(t?.fuelBars, kmPerBar ?: RangeEstimator.FALLBACK_KM_PER_BAR)
    BentoTile(modifier.height(168.dp), container = MaterialTheme.colorScheme.surfaceVariant) {
        Text("FUEL · RANGE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = "Fuel $bars of 6 bars" +
                        (range?.let { ", range ${it.toInt()} kilometres" } ?: "")
                },
            contentAlignment = Alignment.Center,
        ) {
            Sweep(progress = bars / 6f, modifier = Modifier.size(108.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$bars/6", style = GixxerMono.body.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onBackground)
                    Text(range?.let { "${it.toInt()} km" } ?: "—", style = MaterialTheme.typography.labelSmall, color = GixxerBrand.zoneCool)
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: Long, modifier: Modifier) {
    BentoTile(modifier.height(168.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        OdometerNumber(value = value, style = GixxerMono.display.copy(fontSize = 40.sp), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun TripTile(label: String, value: Double?, modifier: Modifier) {
    BentoTile(modifier.height(96.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Text("%.1f km".format(value ?: 0.0), style = GixxerMono.headline, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun FuelEconomyTile(t: TelemetryFrame?) {
    val econ = t?.fuelEconKmlV2
    BentoTile(Modifier.fillMaxWidth().height(110.dp)) {
        Text("FUEL ECONOMY · TRIP AVG", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            OdometerNumber(value = (econ ?: 0.0).toLong(), style = GixxerMono.display.copy(fontSize = 44.sp), color = GixxerBrand.accent)
            Spacer(Modifier.width(6.dp))
            Text("km/L", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun SpeedHistoryTile(history: List<TelemetryFrame>) {
    val points = history.map { (it.speedKmh.coerceIn(0, MAX_SPEED.toInt())) / MAX_SPEED }
    BentoTile(Modifier.fillMaxWidth().height(150.dp)) {
        Text("SPEED · LAST FEW MINUTES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        TraceChart(points = points, animateDraw = true, strokeWidth = 3.dp, modifier = Modifier.fillMaxWidth().height(90.dp))
    }
}

private fun stateLabel(s: ConnectionState): String = when (s) {
    is ConnectionState.Ready -> "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Discovering -> "Discovering…"
    is ConnectionState.Disconnected -> "Waiting"
    is ConnectionState.Failed -> "Failed"
    is ConnectionState.Idle -> "Idle"
}
