package dev.mrwick.gixxerbridge.ui.dev

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.protocol.HeartbeatFrame
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.util.AppLog
import dev.mrwick.gixxerbridge.weather.SuzukiWeather
import dev.mrwick.gixxerbridge.weather.celsiusToTempByte
import kotlinx.coroutines.launch

/**
 * Weather code → cluster icon verification tool. Ships an a533 heartbeat
 * frame with the chosen suzukiWeatherCode (0-11) in byte 21; the rider
 * photographs what the cluster's idle-mode display actually renders.
 *
 * No phone-side icon preview — the Suzuki APK doesn't ship the cluster's
 * weather drawables (those are firmware-baked, unlike the ic_step_N
 * turn-arrows which the app's in-app nav strip reuses). This is just
 * the send-and-photograph half of the loop.
 *
 * To make the cluster's idle weather slot visible, the bike must be in
 * its idle screen — i.e. no Maps navigation active and a heartbeat is
 * being received per second. Each Send overwrites the in-flight
 * heartbeat with the chosen weather byte; the next regular heartbeat
 * (1 Hz) will overwrite it back, so the cluster only shows the test
 * value for a single 1-second tick. Take photos quickly or pause the
 * normal heartbeat loop while testing.
 */
@Composable
fun WeatherSweepScreen() {
    val scope = rememberCoroutineScope()
    var lastSent by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Weather sweep",
            style = MaterialTheme.typography.titleLarge,
            color = GixxerTokens.textPrimary,
        )
        Text(
            "Tap Send on each code, photograph the cluster's idle-mode weather slot. " +
                "The regular 1 Hz heartbeat overwrites the test value almost immediately " +
                "— shoot fast or stop Maps to keep idle screen up.",
            style = MaterialTheme.typography.bodySmall,
            color = GixxerTokens.textMuted,
        )
        Spacer(Modifier.height(12.dp))
        lastSent?.let {
            Text(
                "Last sent: $it",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.accent,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(8.dp))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(WEATHER_CODES, key = { it.code }) { entry ->
                WeatherRow(entry) {
                    scope.launch {
                        // Send a533 heartbeat with the chosen weather byte. All other
                        // fields are sane defaults so the heartbeat is valid. The
                        // temperature is held at 25 °C so the cluster has both fields
                        // populated for the test.
                        val frame = HeartbeatFrame(
                            batteryBucket = "5",
                            charging = "0",
                            speedStr = "000",
                            signalStatus = "1",
                            timeHhmmss = "120000",
                            smsPending = "0",
                            callPending = "0",
                            weather = entry.code,
                            tempFPlus115 = celsiusToTempByte(25.0),
                        )
                        val ok = AppGraph.sendFrame(frame.encode())
                        val ts = java.text.SimpleDateFormat("HH:mm:ss")
                            .format(java.util.Date())
                        lastSent = "code=${entry.code} (${entry.label}) at $ts -> ${if (ok) "ok" else "FAILED"}"
                        AppLog.i("WeatherSweep", lastSent ?: "")
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherRow(entry: WeatherEntry, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(entry.code),
            style = MaterialTheme.typography.titleMedium,
            color = GixxerTokens.textPrimary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            color = GixxerTokens.textMuted,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(
            onClick = onSend,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) { Text("Send") }
    }
}

private data class WeatherEntry(val code: Int, val label: String)

private val WEATHER_CODES: List<WeatherEntry> = listOf(
    WeatherEntry(SuzukiWeather.UNKNOWN, "Unknown / fallback (verify what cluster shows)"),
    WeatherEntry(SuzukiWeather.SUNNY, "Sunny / clear / mostly clear"),
    WeatherEntry(SuzukiWeather.CLOUDY, "Cloudy / hazy"),
    WeatherEntry(SuzukiWeather.FOG, "Fog / light fog"),
    WeatherEntry(SuzukiWeather.LIGHT_RAIN, "Light rain / showers"),
    WeatherEntry(SuzukiWeather.THUNDER, "Thunderstorm"),
    WeatherEntry(SuzukiWeather.RAIN, "Steady rain"),
    WeatherEntry(SuzukiWeather.SNOW, "Snow / flurries / ice"),
    WeatherEntry(SuzukiWeather.SLEET, "Sleet / hail / freezing rain"),
    WeatherEntry(SuzukiWeather.HOT, "Hot (verify icon on cluster)"),
    WeatherEntry(SuzukiWeather.COLD, "Cold (verify icon on cluster)"),
    WeatherEntry(SuzukiWeather.WINDY, "Windy"),
)
