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
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.util.AppLog
import kotlinx.coroutines.launch

/**
 * Empirical verification tool for the Mappls maneuver-id → cluster-icon
 * table (see `docs/maneuver-id-table.md`). Each row sends an a531 NavFrame
 * with the chosen maneuverId; user observes what the bike's cluster
 * actually renders and confirms/corrects the table.
 *
 * All 55 IDs that exist as `ic_step_N.xml` in the Suzuki APK are listed.
 * Distance / ETA / total fields are dummies so the cluster shows the icon
 * with non-distracting filler around it.
 */
@Composable
fun ManeuverSweepScreen() {
    val scope = rememberCoroutineScope()
    var lastSent by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Maneuver sweep",
            style = MaterialTheme.typography.titleLarge,
            color = GixxerTokens.textPrimary,
        )
        Text(
            "Tap Send on each row, photograph what the bike cluster actually renders, " +
                "and compare against the description. Verified table source: " +
                "docs/maneuver-id-table.md.",
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
            items(MANEUVER_IDS, key = { it.id }) { entry ->
                ManeuverRow(
                    entry = entry,
                    onSend = {
                        scope.launch {
                            val frame = NavFrame(
                                maneuverId = entry.id,
                                distNext = "0050",
                                distNextUnit = "M",
                                eta = "now   ",
                                distTotal = "0050",
                                distTotalUnit = "M",
                                status = "1",
                                continueFlag = " ",
                            )
                            val ok = AppGraph.sendFrame(frame.encode())
                            val ts = java.text.SimpleDateFormat("HH:mm:ss")
                                .format(java.util.Date())
                            lastSent = "id=${entry.id} (${entry.label}) at $ts -> ${if (ok) "ok" else "FAILED"}"
                            AppLog.i("ManeuverSweep", lastSent ?: "")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ManeuverRow(entry: ManeuverEntry, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(entry.id),
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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        ) { Text("Send") }
    }
}

private data class ManeuverEntry(val id: Int, val label: String)

private val MANEUVER_IDS: List<ManeuverEntry> = listOf(
    ManeuverEntry(0, "turn left (90°)"),
    ManeuverEntry(1, "slight left / bear left"),
    ManeuverEntry(2, "sharp left"),
    ManeuverEntry(3, "turn right (90°)"),
    ManeuverEntry(4, "slight right"),
    ManeuverEntry(5, "sharp right"),
    ManeuverEntry(6, "u-turn (left loop)"),
    ManeuverEntry(7, "straight / GENERIC_ARROW"),
    ManeuverEntry(8, "hollow circle (position marker)"),
    ManeuverEntry(10, "straight w/ crossbar variant"),
    ManeuverEntry(11, "keep left"),
    ManeuverEntry(12, "keep right"),
    ManeuverEntry(13, "icon 13"),
    ManeuverEntry(14, "icon 14"),
    ManeuverEntry(15, "icon 15"),
    ManeuverEntry(16, "icon 16"),
    ManeuverEntry(17, "icon 17"),
    ManeuverEntry(18, "icon 18"),
    ManeuverEntry(19, "icon 19"),
    ManeuverEntry(20, "merge right"),
    ManeuverEntry(21, "straight w/ crossbar"),
    ManeuverEntry(22, "icon 22"),
    ManeuverEntry(23, "fork (slight-left w/ ramp tail)"),
    ManeuverEntry(24, "icon 24"),
    ManeuverEntry(25, "fork (curved tail variant)"),
    ManeuverEntry(36, "ferry"),
    ManeuverEntry(37, "tunnel"),
    ManeuverEntry(40, "waypoint circle"),
    ManeuverEntry(41, "u-turn (right loop)"),
    ManeuverEntry(50, "depart heading north"),
    ManeuverEntry(51, "depart NE"),
    ManeuverEntry(52, "depart east"),
    ManeuverEntry(53, "depart SE"),
    ManeuverEntry(54, "depart south"),
    ManeuverEntry(55, "depart SW"),
    ManeuverEntry(56, "depart west"),
    ManeuverEntry(57, "depart NW"),
    ManeuverEntry(58, "roundabout 1st exit CCW"),
    ManeuverEntry(59, "roundabout 2nd exit CCW"),
    ManeuverEntry(60, "roundabout 3rd exit CCW"),
    ManeuverEntry(61, "roundabout 4th exit CCW"),
    ManeuverEntry(62, "roundabout 5th exit CCW"),
    ManeuverEntry(63, "roundabout 6th exit CCW (dup of 64)"),
    ManeuverEntry(64, "roundabout 6th exit CCW (dup of 63)"),
    ManeuverEntry(65, "roundabout 7th exit CCW"),
    ManeuverEntry(66, "roundabout 1st exit CW"),
    ManeuverEntry(67, "roundabout 2nd exit CW"),
    ManeuverEntry(68, "roundabout 3rd exit CW"),
    ManeuverEntry(69, "roundabout 4th exit CW"),
    ManeuverEntry(70, "roundabout 5th exit CW"),
    ManeuverEntry(71, "roundabout 6th exit CW"),
    ManeuverEntry(72, "roundabout (3-arrow generic)"),
    ManeuverEntry(73, "roundabout variant (dup of 74)"),
    ManeuverEntry(74, "roundabout variant (dup of 73)"),
    ManeuverEntry(75, "roundabout variant"),
)
