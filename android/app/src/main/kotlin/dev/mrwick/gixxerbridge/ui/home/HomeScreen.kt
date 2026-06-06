package dev.mrwick.gixxerbridge.ui.home

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.location.LastParked
import dev.mrwick.gixxerbridge.protocol.TelemetryFrame
import dev.mrwick.gixxerbridge.ui.components.BentoTile
import dev.mrwick.gixxerbridge.ui.components.GixxerIcons
import dev.mrwick.gixxerbridge.ui.components.HealthRing
import dev.mrwick.gixxerbridge.ui.components.HealthState
import dev.mrwick.gixxerbridge.ui.components.HeroNumeral
import dev.mrwick.gixxerbridge.ui.components.OdometerNumber
import dev.mrwick.gixxerbridge.ui.components.Sweep
import dev.mrwick.gixxerbridge.ui.components.TraceChart
import dev.mrwick.gixxerbridge.ui.home.components.ConnectionDot
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono

/**
 * REDLINE PRESS Home — "Living Cover" (research: docs/superpowers/research/
 * 2026-06-04-screen-research.md). A STATIONARY companion read parked/stopped, so it's
 * a rich bento wall, not a glance-HUD. Two states by connection:
 *
 *  - CONNECTED (bike on, stopped beside it): live RANGE hero + fuel Sweep + odo/trip +
 *    health + today, from a537 telemetry.
 *  - PARKED/DISCONNECTED (away): "last parked Xh ago" hero (tap → maps) or today
 *    distance, + last-known fuel + service + today.
 */
@Composable
fun HomeScreen(
    onOpenPairing: () -> Unit,
    onStartRide: () -> Unit = {},
    onOpenNav: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    val connectionState by vm.connectionState.collectAsStateWithLifecycle(initialValue = ConnectionState.Idle)
    val riderName by vm.riderName.collectAsStateWithLifecycle()
    val todayKm by vm.todayDistanceKm.collectAsStateWithLifecycle()
    val streak by vm.rideStreakDays.collectAsStateWithLifecycle()
    val nextService by vm.nextServiceDue.collectAsStateWithLifecycle()
    val telemetry by vm.latestTelemetry.collectAsStateWithLifecycle()
    val rangeKm by vm.rangeKm.collectAsStateWithLifecycle()
    val lastParked by vm.lastParked.collectAsStateWithLifecycle()

    HomeContent(
        connectionState = connectionState,
        riderName = riderName,
        todayKm = todayKm,
        streak = streak,
        nextService = nextService,
        telemetry = telemetry,
        rangeKm = rangeKm,
        lastParked = lastParked,
        onOpenNav = onOpenNav,
        onStartRide = onStartRide,
        onOpenPairing = onOpenPairing,
        onOpenMaintenance = onOpenMaintenance,
    )
}

/** Stateless Home — all data as params, so it's previewable + screenshot-testable. */
@Composable
fun HomeContent(
    connectionState: ConnectionState,
    riderName: String,
    todayKm: Double?,
    streak: Int?,
    nextService: NextServiceSummary?,
    telemetry: TelemetryFrame?,
    rangeKm: Double?,
    lastParked: LastParked?,
    onOpenNav: () -> Unit = {},
    onStartRide: () -> Unit = {},
    onOpenPairing: () -> Unit = {},
    onOpenMaintenance: () -> Unit = {},
) {
    val live = connectionState is ConnectionState.Ready

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TopStatusZone(connectionState, riderName)

        if (live) {
            RangeHero(rangeKm = rangeKm, fuelBars = telemetry?.fuelBars, index = 0)
        } else {
            ParkedHero(lastParked = lastParked, todayKm = todayKm, index = 0)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FuelTile(telemetry, live, Modifier.weight(1f), index = 1)
            OdoTile(telemetry, Modifier.weight(1f), index = 2)
        }

        HealthTile(nextService, onOpenMaintenance, index = 3)

        TodayStrip(todayKm, streak, index = 4)

        QuickActions(onOpenNav, onStartRide, onOpenPairing)
    }
}

@Composable
private fun TopStatusZone(connectionState: ConnectionState, riderName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ConnectionDot(state = connectionState)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Hi, $riderName", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(stateLabel(connectionState), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RangeHero(rangeKm: Double?, fuelBars: Int?, index: Int) {
    BentoTile(Modifier.fillMaxWidth(), index = index) {
        Text("RANGE · ESTIMATED KM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                HeroNumeral(rangeKm?.let { "${it.toInt()}" } ?: "—", fontSize = 88.sp)
            }
            Sweep(
                progress = (fuelBars ?: 0) / 6f,
                modifier = Modifier.size(110.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${fuelBars ?: 0}/6", style = GixxerMono.body.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onBackground)
                    Text("FUEL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("at your recent km/bar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ParkedHero(lastParked: LastParked?, todayKm: Double?, index: Int) {
    val ctx = LocalContext.current
    if (lastParked != null) {
        BentoTile(
            Modifier.fillMaxWidth(),
            index = index,
            onClick = {
                runCatching {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(lastParked.mapsUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        ) {
            Text("LAST PARKED · TAP FOR MAP", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
            Spacer(Modifier.height(4.dp))
            Text(timeAgo(lastParked.tMillis), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            TraceChart(
                points = ROUTE_ART,
                lineBrush = androidx.compose.ui.graphics.SolidColor(GixxerBrand.accent),
                areaColor = GixxerBrand.accent,
                animateDraw = true,
                modifier = Modifier.fillMaxWidth().height(70.dp),
            )
        }
    } else {
        BentoTile(Modifier.fillMaxWidth(), index = index) {
            Text("TODAY · KM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HeroNumeral(todayKm?.let { "%.1f".format(it) } ?: "0.0", fontSize = 84.sp)
        }
    }
}

@Composable
private fun FuelTile(telemetry: TelemetryFrame?, live: Boolean, modifier: Modifier, index: Int) {
    BentoTile(modifier.height(178.dp), index = index, container = MaterialTheme.colorScheme.surfaceVariant) {
        Text(if (live) "FUEL ECONOMY" else "FUEL · LAST SEEN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        val kml = telemetry?.fuelEconKmlV2
        Row(verticalAlignment = Alignment.Bottom) {
            OdometerNumber(
                value = (kml ?: 0.0).toLong(),
                style = GixxerMono.display.copy(fontSize = 40.sp),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.width(4.dp))
            Text("km/L", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text("${telemetry?.fuelBars ?: 0} of 6 bars", style = MaterialTheme.typography.bodySmall, color = GixxerBrand.zoneCool)
    }
}

@Composable
private fun OdoTile(telemetry: TelemetryFrame?, modifier: Modifier, index: Int) {
    BentoTile(modifier.height(178.dp), index = index) {
        Text("ODOMETER · KM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        OdometerNumber(
            value = (telemetry?.odometerKm ?: 0).toLong(),
            style = GixxerMono.display.copy(fontSize = 36.sp),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text("TRIP A", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("%.1f km".format(telemetry?.tripAKm ?: 0.0), style = GixxerMono.body.copy(fontSize = 16.sp), color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun HealthTile(nextService: NextServiceSummary?, onOpenMaintenance: () -> Unit, index: Int) {
    // No service baseline recorded yet → "Caution" (needs setup), not a green
    // "All good" that falsely reassures when we simply have no data.
    val state = when {
        nextService == null -> HealthState.Caution
        nextService.overdue -> HealthState.Fault
        else -> HealthState.Good
    }
    BentoTile(Modifier.fillMaxWidth().height(110.dp), index = index, onClick = onOpenMaintenance) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HealthRing(state = state, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(16.dp))
            Column {
                Text("BIKE HEALTH", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (nextService == null) "No service data yet" else nextService.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state == HealthState.Fault) GixxerBrand.danger else MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    when {
                        nextService == null -> "Tap to log your last service"
                        nextService.overdue -> nextService.dueInText
                        else -> "Next in ${nextService.dueInText}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TodayStrip(todayKm: Double?, streak: Int?, index: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BentoTile(Modifier.weight(1f), index = index) {
            Text("TODAY · KM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.1f".format(todayKm ?: 0.0), style = GixxerMono.headline, color = GixxerBrand.accent)
        }
        BentoTile(Modifier.weight(1f), index = index + 1) {
            Text("STREAK · DAYS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${streak ?: 0}", style = GixxerMono.headline, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun QuickActions(onOpenNav: () -> Unit, onStartRide: () -> Unit, onOpenPairing: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionTile("NAV", GixxerIcons.ManeuverStraight, onOpenNav, Modifier.weight(1f))
        ActionTile("RIDE", GixxerIcons.LeanWedge, onStartRide, Modifier.weight(1f))
        ActionTile("PAIR", GixxerIcons.FuelDrop, onOpenPairing, Modifier.weight(1f))
    }
}

@Composable
private fun ActionTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier) {
    BentoTile(modifier.height(74.dp), animateEntry = false, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = GixxerBrand.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

private val ROUTE_ART = listOf(0.35f, 0.5f, 0.42f, 0.6f, 0.55f, 0.72f, 0.66f, 0.8f)

private fun timeAgo(tMillis: Long): String {
    val mins = ((System.currentTimeMillis() - tMillis) / 60000L).coerceAtLeast(0)
    return when {
        mins < 1 -> "just now"
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ${mins % 60}m ago"
        else -> "${mins / 1440}d ago"
    }
}

private fun stateLabel(s: ConnectionState): String = when (s) {
    is ConnectionState.Ready -> "Connected"
    is ConnectionState.Connecting -> "Connecting…"
    is ConnectionState.Discovering -> "Discovering services…"
    is ConnectionState.Disconnected -> "Waiting for bike"
    is ConnectionState.Failed -> "Failed — tap to retry"
    is ConnectionState.Idle -> "Idle"
}
