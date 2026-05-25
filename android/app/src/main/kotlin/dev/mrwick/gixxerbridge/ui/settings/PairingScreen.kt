package dev.mrwick.gixxerbridge.ui.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.ble.DiscoveredBike
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import kotlinx.coroutines.delay

/** First-run + re-pair wizard: scans for nearby BLE devices and saves the picked MAC.
 *  No service-UUID filter (Suzuki cluster doesn't advertise 0xFFF0 on all firmwares),
 *  so the list is "everything nearby" — Suzuki-looking devices are pinned to the top
 *  with a green dot, the rest are shown below as a fallback. */
@Composable
fun PairingScreen(vm: PairingViewModel, onPaired: () -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    val pairState by vm.pairState.collectAsStateWithLifecycle()
    val pairedMac by vm.pairedMac.collectAsStateWithLifecycle()
    val pairedConn by vm.pairedConnectionState.collectAsStateWithLifecycle()
    val sorted = results.values.sortedByDescending { it.rssi }
    // Don't double-show the currently-paired bike in the scan list — it already
    // gets its own "Currently paired" card with live state.
    val bonded = sorted.filter { it.bonded && it.mac != pairedMac }
    val unbonded = sorted.filter { !it.bonded && it.mac != pairedMac }
    val (likely, others) = unbonded.partition { isLikelySuzuki(it) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
        ) {
            Text(
                "Scanning for bikes…",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val hint = when {
                bonded.isEmpty() && likely.isEmpty() && others.isEmpty() ->
                    "Make sure the bike's key is ON and your phone's Bluetooth is enabled."
                likely.isNotEmpty() -> "Tap your bike to pair."
                bonded.isNotEmpty() ->
                    "Your bike isn't advertising right now, but it's previously paired — tap it below to reconnect."
                else ->
                    "${others.size} BLE device${if (others.size == 1) "" else "s"} nearby — none look like a Suzuki. " +
                        "If your bike is on, tap it below anyway."
            }
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pairedMac?.let { mac ->
                    item { SectionLabel("Currently paired", count = 1) }
                    item {
                        CurrentlyPairedRow(
                            mac = mac,
                            state = pairedConn,
                            onForget = { vm.forgetPairedBike() },
                        )
                    }
                }
                if (bonded.isNotEmpty()) {
                    item { SectionLabel("Previously paired", count = bonded.size) }
                    items(bonded, key = { "b-${it.mac}" }) { bike ->
                        BikeRow(bike, likely = isLikelySuzuki(bike), onPick = { vm.pickBike(bike, onPaired) })
                    }
                }
                if (likely.isNotEmpty()) {
                    item { SectionLabel("Likely your bike", count = likely.size) }
                    items(likely, key = { "l-${it.mac}" }) { bike ->
                        BikeRow(bike, likely = true, onPick = { vm.pickBike(bike, onPaired) })
                    }
                }
                if (others.isNotEmpty()) {
                    item { SectionLabel("Other nearby BLE devices", count = others.size) }
                    items(others, key = { "o-${it.mac}" }) { bike ->
                        BikeRow(bike, likely = false, onPick = { vm.pickBike(bike, onPaired) })
                    }
                }
            }
        }

        // Status overlay — only visible while pairing/connecting. Without this, the
        // user got zero feedback after tapping a bike: the DataStore write finishes
        // in <50 ms and popBackStack runs, but the *service* takes a few seconds to
        // GATT-connect. This was reported as "tap does nothing".
        if (pairState !is PairingViewModel.PairUiState.Idle) {
            PairOverlay(state = pairState, onDismiss = { vm.clearPairState() })
        }
    }
}

@Composable
private fun PairOverlay(
    state: PairingViewModel.PairUiState,
    onDismiss: () -> Unit,
) {
    // Auto-dismiss on success after a beat so the user sees the green checkmark.
    LaunchedEffect(state) {
        if (state is PairingViewModel.PairUiState.Connected) {
            delay(900)
            onDismiss()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
            ) {
                when (state) {
                    is PairingViewModel.PairUiState.Saving -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Saving…", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(state.bike.mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is PairingViewModel.PairUiState.Connecting -> {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(state.phase, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(state.bike.displayName, style = MaterialTheme.typography.bodySmall)
                        Text(state.bike.mac, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    is PairingViewModel.PairUiState.Connected -> {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = GixxerTokens.success,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Connected!", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(state.bike.displayName, style = MaterialTheme.typography.bodySmall)
                    }
                    is PairingViewModel.PairUiState.Failed -> {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Couldn't connect", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(state.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        TextButton(onClick = onDismiss) { Text("Try another bike") }
                    }
                    PairingViewModel.PairUiState.Idle -> Unit
                }
            }
        }
    }
}

@Composable
private fun CurrentlyPairedRow(
    mac: String,
    state: dev.mrwick.gixxerbridge.ble.ConnectionState,
    onForget: () -> Unit,
) {
    val (label, dotColor) = when (state) {
        is dev.mrwick.gixxerbridge.ble.ConnectionState.Ready ->
            "Connected" to GixxerTokens.success
        is dev.mrwick.gixxerbridge.ble.ConnectionState.Connecting ->
            "Connecting…" to GixxerTokens.warning
        is dev.mrwick.gixxerbridge.ble.ConnectionState.Discovering ->
            "Discovering services…" to GixxerTokens.warning
        is dev.mrwick.gixxerbridge.ble.ConnectionState.Disconnected ->
            "Waiting for bike key-on" to GixxerTokens.textMuted
        is dev.mrwick.gixxerbridge.ble.ConnectionState.Failed ->
            "Failed: ${state.reason}" to MaterialTheme.colorScheme.error
        dev.mrwick.gixxerbridge.ble.ConnectionState.Idle ->
            "Idle" to GixxerTokens.textMuted
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color = dotColor)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Your bike",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "$mac  ·  $label",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "An active GATT connection isn't shown in scan results. " +
                    "Tap Forget to clear this and scan for a different bike.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedButton(
                onClick = onForget,
                modifier = Modifier.fillMaxWidth(),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    GixxerTokens.danger,
                ),
            ) {
                Text("Forget this bike", color = GixxerTokens.danger)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun BikeRow(bike: DiscoveredBike, likely: Boolean, onPick: () -> Unit) {
    val containerColor = if (likely) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    // M3 Card(onClick=) gives a proper ripple + correct accessibility role,
    // unlike adding a separate .clickable modifier (which can be eaten by
    // the Card's own touch handling depending on AGP/compose-material3 version).
    Card(
        onClick = onPick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(14.dp),
        ) {
            Dot(color = if (likely) GixxerTokens.success else GixxerTokens.surfaceElevated)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                // Title: actual BT name if present, else vendor label, else "(no name)".
                Text(
                    bike.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (likely) FontWeight.SemiBold else FontWeight.Normal,
                )
                // Subtitle: MAC, then signal/RSSI or "paired" badge for bonded sentinels,
                // and a vendor caption if we have one in addition to a real name.
                val rssiText = if (bike.rssi == Int.MIN_VALUE) "saved" else "RSSI ${bike.rssi}"
                Text(
                    "${bike.mac}  ·  $rssiText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // If we have BOTH a real name and a vendor hint, show vendor as a third line.
                if (bike.name != null && bike.vendor != null) {
                    Text(
                        bike.vendor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            if (bike.rssi != Int.MIN_VALUE) SignalBars(bike.rssi)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun SignalBars(rssi: Int) {
    val bars = when {
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 1..4) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((i * 4).dp)
                    .background(
                        if (i <= bars) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                    ),
            )
        }
    }
}

/** Heuristic: is this BLE device likely the Suzuki cluster?
 *  - Name starts with SBM (Suzuki advertises bike model number)
 *  - Or MAC OUI is 74:B8:39 (Texas Instruments BLE SoC, what Suzuki uses)
 *  - Or vendor label flagged Suzuki / Texas Instruments
 */
private fun isLikelySuzuki(bike: DiscoveredBike): Boolean {
    val name = bike.name?.uppercase() ?: ""
    if (name.startsWith("SBM")) return true
    if (bike.mac.uppercase().startsWith("74:B8:39")) return true
    val v = bike.vendor?.lowercase() ?: ""
    if ("suzuki" in v) return true
    return false
}
