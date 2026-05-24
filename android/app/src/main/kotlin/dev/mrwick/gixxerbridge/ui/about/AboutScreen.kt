package dev.mrwick.gixxerbridge.ui.about

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.BuildConfig

/**
 * Minimal "About" card: app version, build flavor, package, BLE protocol summary.
 * Long-press the version row to copy diagnostics to clipboard for bug reports.
 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GixxerBridge", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "An open replacement for Suzuki Connect — Google Maps nav on the cluster, " +
                "live telemetry on the phone, no cloud, no account.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                KeyVal("Version", BuildConfig.VERSION_NAME)
                KeyVal("Build", BuildConfig.BUILD_TYPE)
                KeyVal("Package", BuildConfig.APPLICATION_ID)
                KeyVal("Android", "API ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
                KeyVal("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Bike protocol", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Suzuki Connect BLE protocol — single service 0xFFF0 with write 0xFFF1 " +
                        "and notify 0xFFF2. 30-byte frames, 0xA5 header, 0x7F terminator, " +
                        "8-bit sum checksum. Phone -> bike: a531 nav, a532 call, a533 heartbeat, " +
                        "a534 missed call, a535 SMS, a536 identity. Bike -> phone: a537 telemetry " +
                        "(speed, odo, trip A/B, fuel, fuel economy).",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Credits", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Built by Arjun KR on a 2023 Suzuki Gixxer SF 150. Protocol decoded from " +
                        "decompiled Suzuki Connect APK + HCI snoop captures + a Frida-hooked " +
                        "ride session. No firmware modifications, no bypass of bike-side auth. " +
                        "Personal interoperability project.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun KeyVal(k: String, v: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
        Text(v, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
