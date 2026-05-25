package dev.mrwick.gixxerbridge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

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
) {
    val demoMode by vm.demoMode.collectAsStateWithLifecycle()
    val autoStart by vm.autoStartOnBoot.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val appLock by vm.appLockEnabled.collectAsStateWithLifecycle()
    val maneuverSelfTrain by vm.maneuverSelfTrainEnabled.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

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
                DevSwitchRow(
                    "Maneuver self-train (bitmap hash from text)",
                    maneuverSelfTrain,
                    vm::setManeuverSelfTrainEnabled,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenInspector, modifier = Modifier.fillMaxWidth()) {
                    Text("Open frame inspector")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Text("Diagnostics / log viewer")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        ContextCompat.startForegroundService(
                            ctx,
                            android.content.Intent(
                                ctx,
                                dev.mrwick.gixxerbridge.ble.BikeBridgeService::class.java,
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
                    "Auto-start GixxerBridge after boot",
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
