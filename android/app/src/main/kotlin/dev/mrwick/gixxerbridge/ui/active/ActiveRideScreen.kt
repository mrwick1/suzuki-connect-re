package dev.mrwick.gixxerbridge.ui.active

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import dev.mrwick.gixxerbridge.ui.components.OdometerNumber
import dev.mrwick.gixxerbridge.ui.components.Sweep
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.telemetry.TelemetryRepository
import dev.mrwick.gixxerbridge.ui.home.components.SpeedDisplay
import dev.mrwick.gixxerbridge.ui.home.components.SpeedState
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ble.ConnectionState

/**
 * Full-screen active-ride overlay. Shown on top of the normal app shell when
 * [ActiveRideController.isActive] is true.
 *
 * Layout:
 *  - Upper two-thirds: [SpeedDisplay] at 144 sp tabular Geist Mono.
 *  - Lower third: one metric line (chosen in Settings → Cluster).
 *
 * Single tap anywhere dismisses (calls [ActiveRideController.dismiss]).
 * Auto-exits when the controller flips isActive back to false.
 *
 * The overlay is added on top of the normal Scaffold content via [ActiveRideLayer]
 * in MainActivity's AppShell so the underlying UI stays composed for fast return.
 */
@Composable
fun ActiveRideScreen(
    metric: String,
    onDismiss: () -> Unit,
) {
    val connState by AppGraph.connectionState.collectAsStateWithLifecycle()
    val speedState = remember(connState) {
        when (connState) {
            is ConnectionState.Ready -> SpeedState.Connected
            is ConnectionState.Connecting, is ConnectionState.Discovering -> SpeedState.Connecting
            else -> SpeedState.Disconnected
        }
    }

    val telemetry by TelemetryRepository.latest.collectAsStateWithLifecycle()

    val lastUpdateMs by produceState(initialValue = 0L, key1 = telemetry) {
        if (telemetry != null) value = System.currentTimeMillis()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GixxerTokens.bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.SpaceAround,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Upper two-thirds: speed display
            Box(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Sweep(
                    progress = (telemetry?.speedKmh ?: 0) / 120f,
                    modifier = Modifier.size(330.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OdometerNumber(
                            value = (telemetry?.speedKmh ?: 0).toLong(),
                            style = GixxerMono.display.copy(fontSize = 120.sp),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            if (speedState == SpeedState.Connected) "KM / H" else "KM / H · ${speedState.name.uppercase()}",
                            style = MaterialTheme.typography.labelLarge,
                            color = GixxerBrand.accent,
                        )
                    }
                }
            }

            // Lower third: one rider-chosen contextual metric
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                ActiveRideMetricLine(metric = metric, telemetry = telemetry)
            }
        }
    }
}

/**
 * Renders one line of the rider-chosen metric for the active-ride lower third.
 *
 * [metric] maps to the value stored in [Settings.activeRideMetric]:
 *  - "trip-a": Trip A distance in km
 *  - "fuel": Fuel bars (1-6) / 6
 *  - "eta": Placeholder — ETA nav integration is not yet implemented (no nav source in this build)
 *  - "road-type": Placeholder — road type classification is out of scope for Wave 2
 */
@Composable
private fun ActiveRideMetricLine(metric: String, telemetry: dev.mrwick.gixxerbridge.protocol.TelemetryFrame?) {
    val (label, value) = when (metric) {
        "trip-a" -> Pair(
            "TRIP A",
            telemetry?.tripAKm?.let { "%.1f km".format(it) } ?: "—",
        )
        "fuel" -> Pair(
            "FUEL",
            telemetry?.fuelBars?.let { "$it / 6 bars" } ?: "—",
        )
        "eta" -> Pair(
            "ETA",
            "—",  // ETA nav integration deferred; no nav source in Wave 2
        )
        "road-type" -> Pair(
            "ROAD TYPE",
            "—",  // Road-type classification deferred out of scope for Wave 2
        )
        else -> Pair("TRIP A", telemetry?.tripAKm?.let { "%.1f km".format(it) } ?: "—")
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = GixxerTokens.textMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            color = GixxerTokens.textPrimary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Wraps [content] in a [Box] and overlays [ActiveRideScreen] using
 * [AnimatedVisibility] with spring physics when [ActiveRideController.isActive]
 * is true and the app is past the gate checks (onboarding + lock both cleared,
 * which is guaranteed by the call site in AppShell).
 *
 * The underlying content stays composed behind the overlay for fast return.
 */
@Composable
fun ActiveRideLayer(
    metric: String,
    content: @Composable () -> Unit,
) {
    val isActive by ActiveRideController.isActive.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        content()

        AnimatedVisibility(
            visible = isActive,
            enter = fadeIn(animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f)) +
                slideInVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                    initialOffsetY = { it },
                ),
            exit = fadeOut(animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f)) +
                slideOutVertically(
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 400f),
                    targetOffsetY = { it },
                ),
        ) {
            ActiveRideScreen(
                metric = metric,
                onDismiss = { ActiveRideController.dismiss() },
            )
        }
    }
}
