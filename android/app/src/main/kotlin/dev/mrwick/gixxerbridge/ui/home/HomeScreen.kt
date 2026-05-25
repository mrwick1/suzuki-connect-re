package dev.mrwick.gixxerbridge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ui.home.components.ConnectionDot
import dev.mrwick.gixxerbridge.ui.home.components.QuickActionsRow
import dev.mrwick.gixxerbridge.ui.home.components.TodayHeroCard
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * Wave 1 Home — three zones:
 *   1. Top: ConnectionDot + rider name + connection state label
 *   2. Today: single TodayHeroCard
 *   3. Actions: row of 3 outlined icon buttons
 *
 * No hardcoded hex anywhere — HardcodedHexLintTest enforces this.
 */
@Composable
fun HomeScreen(
    onOpenPairing: () -> Unit,
    onStartRide: () -> Unit = {},
    onOpenNav: () -> Unit = {},
    vm: HomeViewModel = viewModel(),
) {
    val connectionState by vm.connectionState.collectAsStateWithLifecycle(initialValue = ConnectionState.Idle)
    val riderName by vm.riderName.collectAsStateWithLifecycle()
    val todayKm by vm.todayDistanceKm.collectAsStateWithLifecycle()
    val streak by vm.rideStreakDays.collectAsStateWithLifecycle()
    val nextService by vm.nextServiceDue.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TopStatusZone(
            connectionState = connectionState,
            riderName = riderName,
        )

        TodayHeroCard(
            todayKm = todayKm,
            streakDays = streak,
            nextServiceLabel = nextService?.label,
            nextServiceDueIn = nextService?.dueInText,
            nextServiceOverdue = nextService?.overdue == true,
        )

        QuickActionsRow(
            onStartRide = onStartRide,
            onOpenNav = onOpenNav,
            onOpenPairing = onOpenPairing,
        )
    }
}

@Composable
private fun TopStatusZone(connectionState: ConnectionState, riderName: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ConnectionDot(state = connectionState)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "Hi, $riderName",
                style = MaterialTheme.typography.titleLarge,
                color = GixxerTokens.textPrimary,
            )
            Text(
                text = stateLabel(connectionState),
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
            )
        }
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
