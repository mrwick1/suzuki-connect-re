package dev.mrwick.gixxerbridge.ui.safety

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Settings "Safety" card: emergency-contact phone number, crash-detection opt-in toggle,
 * and a "SEND TEST SOS" button that exercises the SMS path end-to-end (without waiting
 * for an actual crash).
 *
 * Designed to be embedded as a single LazyColumn item in
 * [dev.mrwick.gixxerbridge.ui.settings.SettingsScreen].
 */
@Composable
fun SafetySection(vm: SafetyViewModel) {
    val contact by vm.emergencyContactPhone.collectAsStateWithLifecycle()
    val crashEnabled by vm.crashDetectionEnabled.collectAsStateWithLifecycle()

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Safety", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            var phone by remember(contact) { mutableStateOf(contact ?: "") }
            OutlinedTextField(
                value = phone,
                onValueChange = {
                    phone = it.filter { c -> c.isDigit() || c == '+' }.take(16)
                    vm.setEmergencyContactPhone(phone.ifBlank { null })
                },
                label = { Text("Emergency contact phone") },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))
            SwitchRow(
                label = "Crash detection (alert on sudden deceleration)",
                value = crashEnabled,
                onChange = vm::setCrashDetectionEnabled,
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = vm::sendTestSos,
                enabled = !phone.isBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = GixxerTokens.dangerWarm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("SEND TEST SOS")
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Tip: SEND_SMS permission must be granted. The test sends a real SMS.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Local copy of the SwitchRow helper from SettingsScreen.kt (private there). */
@Composable
private fun ColumnScope.SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = value, onCheckedChange = onChange)
    }
}
