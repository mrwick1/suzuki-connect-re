package dev.mrwick.redline.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.data.Settings
import dev.mrwick.redline.ui.theme.GixxerTokens
import kotlinx.coroutines.launch

/**
 * Developer sub-screen: demo mode, frame inspector, diagnostics, restart
 * bike service, reset onboarding, auto-start on boot, keep screen on, app lock.
 * Route: settings/developer
 */
@Composable
fun DeveloperSettingsScreen(
    vm: SettingsViewModel,
    onOpenInspector: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenManeuverSweep: () -> Unit = {},
    onOpenWeatherSweep: () -> Unit = {},
) {
    val demoMode by vm.demoMode.collectAsStateWithLifecycle()
    val autoStart by vm.autoStartOnBoot.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val appLock by vm.appLockEnabled.collectAsStateWithLifecycle()
    // PARKED (2026-06-04): maneuver classifier shelved with Google Maps nav.
    // val maneuverSelfTrain by vm.maneuverSelfTrainEnabled.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // Range-on-cluster toggle: read directly from Settings (not yet in the VM).
    // ASSUMED (UNVERIFIED on bike): whether the cluster renders "RANGE / NNNN K"
    // in the distNext/eta slots — see plan caveats. Default off / experimental.
    val settingsForRange = remember(ctx) { Settings(ctx) }
    val rangeOnCluster by settingsForRange.rangeOnCluster.collectAsStateWithLifecycle(false)
    val rangeScope = rememberCoroutineScope()

    // Journey-detector thresholds: read directly from Settings (not in the VM),
    // same local pattern as the range toggle above. Edits write straight back.
    val journeyCfg by settingsForRange.journeyConfig.collectAsStateWithLifecycle(
        dev.mrwick.redline.analytics.JourneyConfig(),
    )

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSection("Developer") {
                DevSwitchRow(
                    "Demo mode (simulated bike telemetry)",
                    demoMode,
                    vm::setDemoMode,
                )
                // Range on cluster: experimental / UNVERIFIED on the physical bike.
                // Shows estimated km-remaining in the a531 idle rotation. Default off.
                DevSwitchRow(
                    "Range on cluster (experimental — unverified on bike)",
                    rangeOnCluster,
                ) { v -> rangeScope.launch { settingsForRange.setRangeOnCluster(v) } }
                // PARKED (2026-06-04): maneuver self-train shelved with Google Maps nav.
                // DevSwitchRow(
                //     "Maneuver self-train (bitmap hash from text)",
                //     maneuverSelfTrain,
                //     vm::setManeuverSelfTrainEnabled,
                // )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenInspector, modifier = Modifier.fillMaxWidth()) {
                    Text("Open frame inspector")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnostics / log viewer")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenManeuverSweep, modifier = Modifier.fillMaxWidth()) {
                    Text("Maneuver sweep (find blank icon byte)")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenWeatherSweep, modifier = Modifier.fillMaxWidth()) {
                    Text("Weather sweep (verify cluster icons)")
                }
                // On-bike LED test (transient, not persisted). Toggle each and
                // watch the white "i" LED + SMS/call icons on the cluster to map
                // a533 byte 14/15 -> LED and the N/Y on/off direction.
                var smsDbg by remember { mutableStateOf(AppGraph.debugSmsPending) }
                DevSwitchRow("LED test: a533 SMS byte (14) = pending", smsDbg) {
                    smsDbg = it; AppGraph.debugSmsPending = it
                }
                var callDbg by remember { mutableStateOf(AppGraph.debugCallPending) }
                DevSwitchRow("LED test: a533 Call byte (15) = pending", callDbg) {
                    callDbg = it; AppGraph.debugCallPending = it
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        ContextCompat.startForegroundService(
                            ctx,
                            android.content.Intent(
                                ctx,
                                dev.mrwick.redline.ble.BikeBridgeService::class.java,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Restart bike service") }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { vm.resetOnboarding() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Reset onboarding (replay wizard)")
                }
            }
        }
        item {
            SettingsSection("App behavior") {
                DevSwitchRow(
                    "Auto-start REDLINE after boot",
                    autoStart,
                    vm::setAutoStartOnBoot,
                )
                DevSwitchRow(
                    "Keep screen on while connected",
                    keepScreenOn,
                    vm::setKeepScreenOnWhileConnected,
                )
                DevSwitchRow(
                    "Require unlock on app launch",
                    appLock,
                    vm::setAppLockEnabled,
                )
            }
        }
        item {
            SettingsSection("Journey suggestions") {
                DevStepperRow(
                    label = "Max gap (min)",
                    value = journeyCfg.gapMaxMin,
                    step = 15,
                    range = 15..480,
                ) { v -> rangeScope.launch { settingsForRange.setJourneyGapMaxMin(v) } }
                DevStepperRow(
                    label = "Min segments",
                    value = journeyCfg.minSegments,
                    step = 1,
                    range = 2..10,
                ) { v -> rangeScope.launch { settingsForRange.setJourneyMinSegments(v) } }
                DevStepperRow(
                    label = "Min distance (km)",
                    value = journeyCfg.minTotalKm,
                    step = 10,
                    range = 10..500,
                ) { v -> rangeScope.launch { settingsForRange.setJourneyMinTotalKm(v) } }
            }
        }
    }
}

/**
 * Numeric stepper row: a label on the left, a [-] value [+] control on the
 * right. Mirrors [DevSwitchRow]'s layout/styling. Clamps to [range] in [step]s.
 */
@Composable
private fun DevStepperRow(
    label: String,
    value: Int,
    step: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        IconButton(
            onClick = { onChange((value - step).coerceIn(range.first, range.last)) },
            enabled = value > range.first,
            modifier = Modifier
                .size(32.dp)
                .border(1.dp, GixxerTokens.border, CircleShape),
        ) {
            Icon(
                Icons.Default.Remove,
                contentDescription = "Decrease $label",
                tint = GixxerTokens.textPrimary,
            )
        }
        Text(
            text = value.toString(),
            modifier = Modifier
                .widthIn(min = 44.dp)
                .padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.titleMedium,
            color = GixxerTokens.textPrimary,
        )
        IconButton(
            onClick = { onChange((value + step).coerceIn(range.first, range.last)) },
            enabled = value < range.last,
            modifier = Modifier
                .size(32.dp)
                .border(1.dp, GixxerTokens.border, CircleShape),
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = "Increase $label",
                tint = GixxerTokens.textPrimary,
            )
        }
    }
}

@Composable
private fun DevSwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(vertical = 8.dp),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = value,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = GixxerTokens.surfaceElevated,
                uncheckedThumbColor = GixxerTokens.textMuted,
                uncheckedBorderColor = GixxerTokens.border,
            ),
        )
    }
}
