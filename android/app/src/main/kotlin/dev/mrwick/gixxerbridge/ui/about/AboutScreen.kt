package dev.mrwick.gixxerbridge.ui.about

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.BuildConfig
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.BikeInfo

/**
 * Minimal "About" card: app version, build flavor, package, BLE protocol summary.
 * Long-press the version row to copy diagnostics to clipboard for bug reports.
 */
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val bikeInfo by AppGraph.bikeInfo.collectAsStateWithLifecycle()
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

        // "Connected bike" card — only shown once BleClient has read the standard
        // 0x180A Device Information Service from the bike (happens on first connect).
        bikeInfo?.let { ConnectedBikeCard(it) }

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
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/mrwick/gixxer-bridge"),
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { context.startActivity(intent) }
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("View on GitHub", style = MaterialTheme.typography.labelMedium)
                }
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

@Composable
private fun ConnectedBikeCard(info: BikeInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Connected bike", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            info.manufacturer?.let { KeyVal("Manufacturer", it) }
            info.modelNumber?.let { KeyVal("Model", it) }
            info.serialNumber?.let { KeyVal("Serial", it) }
            info.firmwareRevision?.let { KeyVal("Firmware", it) }
            info.softwareRevision?.let { KeyVal("Software", it) }
            info.hardwareRevision?.let { KeyVal("Hardware", it) }
            info.systemId?.let { KeyVal("System ID", it) }
            info.pnpId?.let { KeyVal("PnP ID", it) }
            info.ieeeCert?.let { KeyVal("IEEE cert", it) }
        }
    }
}
