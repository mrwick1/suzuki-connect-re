package dev.mrwick.redline.ui.dev

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.mrwick.redline.app.AppGraph
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.ui.theme.GixxerTokens
import dev.mrwick.redline.util.AppLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Empirical verification tool for the Suzuki cluster's byte→glyph table.
 *
 * Iterates cluster bytes 1..53 (1..52 is the OEM `A0.C()` output range; 43
 * and 53 are added as out-of-table probes — see
 * `docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md`).
 * For each byte, sends an a531 NavFrame at 1 Hz for [BURST_SECONDS] seconds
 * so the cluster's nav-mode latch has time to engage (single-shot writes
 * during 2026-05-25 sweep failed to update the cluster — see DISCOVERIES.md).
 *
 * **Use only when no real navigation is active.** A live NavMux will overwrite
 * the swept byte on its next emit.
 *
 * Observations: type the description of what the cluster actually rendered
 * into the row's text field. Observations persist to a TSV at
 * `Context.filesDir/cluster_byte_glyphs.tsv` so they survive process death.
 * Pull via `adb shell run-as dev.mrwick.redline.debug cat files/cluster_byte_glyphs.tsv`.
 */
@Composable
fun ManeuverSweepScreen() {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var lastSent by remember { mutableStateOf<String?>(null) }
    val glyphNotes = remember { mutableStateMapOf<Int, String>() }
    val tsvFile = remember { File(ctx.filesDir, GLYPH_TSV_FILENAME) }
    var activeBurstJob by remember { mutableStateOf<Job?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Cluster byte sweep",
            style = MaterialTheme.typography.titleLarge,
            color = GixxerTokens.textPrimary,
        )
        Text(
            "Sends each cluster byte 1..53 to a531 byte 2 at 1 Hz for ${BURST_SECONDS}s. " +
                "Watch the cluster; type what you see. Use ONLY when no nav is active.",
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(CLUSTER_BYTES, key = { it }) { byte ->
                ClusterByteRow(
                    byte = byte,
                    note = glyphNotes[byte] ?: "",
                    onNoteChange = { txt ->
                        glyphNotes[byte] = txt
                        runCatching {
                            tsvFile.appendText(
                                "$byte\t${System.currentTimeMillis()}\t${txt.replace('\t', ' ').replace('\n', ' ')}\n",
                            )
                        }
                    },
                    onSend = {
                        activeBurstJob?.cancel()
                        activeBurstJob = scope.launch {
                            val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                                .format(java.util.Date())
                            lastSent = "byte=$byte burst starting at $ts"
                            AppLog.i("ClusterSweep", "burst start byte=$byte for ${BURST_SECONDS}s")
                            repeat(BURST_SECONDS) {
                                val frame = NavFrame(
                                    maneuverId = byte,
                                    distNext = "0050",
                                    distNextUnit = "M",
                                    eta = "now   ",
                                    distTotal = "0050",
                                    distTotalUnit = "M",
                                    status = "1",
                                    continueFlag = "1",
                                )
                                val ok = AppGraph.sendFrame(frame.encode())
                                AppLog.d("ClusterSweep", "burst tick byte=$byte ok=$ok")
                                delay(1000L)
                            }
                            lastSent = "byte=$byte burst done"
                            AppLog.i("ClusterSweep", "burst done byte=$byte")
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ClusterByteRow(
    byte: Int,
    note: String,
    onNoteChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d".format(byte),
            style = MaterialTheme.typography.titleMedium,
            color = GixxerTokens.textPrimary,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(40.dp),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            placeholder = { Text("what does the cluster show?") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onSend,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
        ) { Text("Send") }
    }
}

private const val BURST_SECONDS = 5
private const val GLYPH_TSV_FILENAME = "cluster_byte_glyphs.tsv"

/** Cluster bytes the OEM emits in A0.C()'s default branch (1..52). Plus 43
 *  and 53 are included as "is the cluster ROM hiding something here?" checks. */
private val CLUSTER_BYTES: List<Int> = (1..53).toList()
