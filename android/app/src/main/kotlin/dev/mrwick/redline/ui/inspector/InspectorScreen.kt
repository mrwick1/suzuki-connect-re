package dev.mrwick.redline.ui.inspector

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.mrwick.redline.ui.theme.GixxerTokens
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.ble.FrameEvent
import dev.mrwick.redline.export.CsvExporter
import dev.mrwick.redline.protocol.FrameType
import dev.mrwick.redline.protocol.decodeFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live scrolling timeline of every TX/RX BLE frame with hex bytes + decoded fields. */
@Composable
fun InspectorScreen(vm: InspectorViewModel) {
    val events by vm.events.collectAsStateWithLifecycle()
    val paused by vm.paused.collectAsStateWithLifecycle()
    val typeFilter by vm.typeFilter.collectAsStateWithLifecycle()

    val filtered = remember(events, typeFilter) {
        if (typeFilter.isEmpty()) events else events.filter {
            it.bytes.size >= 2 && (it.bytes[1].toInt() and 0xFF) in typeFilter
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size) {
        if (!paused && filtered.isNotEmpty()) listState.animateScrollToItem(filtered.size - 1)
    }

    val context = LocalContext.current
    // PERF: scope for offloading CSV serialisation + file IO off the main
    // thread (audit finding 4.1). Prior code did CsvExporter.framesToCsv +
    // File.writeText directly inside an IconButton onClick, which runs on
    // Main; a packed inspector buffer (500 frames) can stall the UI for tens
    // of ms.
    val ioScope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("BLE Frame Inspector") },
            actions = {
                IconButton(onClick = {
                    val snapshot = events
                    if (snapshot.isEmpty()) {
                        Toast.makeText(context, "Nothing to save — frame buffer is empty", Toast.LENGTH_SHORT).show()
                        return@IconButton
                    }
                    ioScope.launch {
                        val uri = withContext(Dispatchers.IO) {
                            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                            val cache = File(context.cacheDir, "inspector-$stamp.csv")
                            cache.writeText(CsvExporter.framesToCsv(snapshot))
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                cache,
                            )
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Save inspector log"))
                    }
                }) {
                    Icon(Icons.Default.Download, contentDescription = "Save log")
                }
                IconButton(onClick = { vm.togglePause() }) {
                    Icon(
                        imageVector = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (paused) "Resume" else "Pause",
                    )
                }
                IconButton(onClick = { vm.clear() }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear")
                }
            },
        )
        FilterChipsRow(typeFilter, vm::toggleTypeFilter)
        if (filtered.isEmpty()) {
            EmptyState(hasFilter = typeFilter.isNotEmpty(), hasAnyEvents = events.isNotEmpty())
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { "${it.tMillis}-${it.bytes.contentHashCode()}" }) { event ->
                    FrameRow(event)
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Friendly placeholder shown when there are no frames to display.
 *
 * Distinguishes "filter hides everything" from "no frames at all" so the user can
 * tell whether to clear the filter or check the service/bike state.
 */
@Composable
private fun EmptyState(hasFilter: Boolean, hasAnyEvents: Boolean) {
    val message = when {
        hasAnyEvents && hasFilter ->
            "No frames match the current filter — toggle a chip above to widen the view."
        else ->
            "Frame stream is empty — start the service & make sure the bike is on."
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Horizontal scrolling row of one [FilterChip] per known [FrameType]. */
@Composable
private fun FilterChipsRow(selected: Set<Int>, onToggle: (Int) -> Unit) {
    val types = listOf(
        FrameType.NAV to "a531 NAV",
        FrameType.CALL to "a532 CALL",
        FrameType.HEARTBEAT to "a533 HB",
        FrameType.MISSED_CALL to "a534 MISS",
        FrameType.SMS to "a535 SMS",
        FrameType.IDENTITY to "a536 ID",
        FrameType.TELEMETRY to "a537 TX",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        types.forEach { (type, label) ->
            val byteValue = type.code.toInt() and 0xFF
            FilterChip(
                selected = byteValue in selected,
                onClick = { onToggle(byteValue) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
            )
        }
    }
}

/** Single timestamp + direction + hex + decoded summary row for one [FrameEvent]. */
@Composable
private fun FrameRow(event: FrameEvent) {
    val time = remember(event.tMillis) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(event.tMillis))
    }
    val decoded = remember(event.bytes) {
        if (event.bytes.size == 30) {
            try {
                decodeFrame(event.bytes).toString()
            } catch (_: Throwable) {
                "(undecoded)"
            }
        } else {
            "(len=${event.bytes.size})"
        }
    }
    val dirColor = if (event.direction == FrameEvent.Direction.TX) GixxerTokens.zoneCool else GixxerTokens.zoneMid
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.width(72.dp)) {
            Text(time, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text(event.direction.name, color = dirColor, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                colorizedHexLine(event.bytes),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Text(decoded, style = MaterialTheme.typography.bodyMedium)
            if (event.note != null) {
                Text(event.note, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Render [bytes] as space-separated lowercase hex with the type byte (index 1) tinted
 * per frame-type so the eye can sniff out a531/a533/etc at a glance while scrolling.
 *
 * Header byte (0) and terminator byte (29) are dimmed; everything else uses default text color.
 */
private fun colorizedHexLine(bytes: ByteArray): AnnotatedString = buildAnnotatedString {
    for ((i, b) in bytes.withIndex()) {
        val color = when (i) {
            1 -> when (b.toInt() and 0xFF) {
                0x31 -> GixxerTokens.zoneCool            // a531 NAV: cyan
                0x32, 0x34 -> GixxerTokens.zoneMid      // a532 CALL / a534 MISSED: amber
                0x33 -> GixxerTokens.onSurfaceDim            // a533 HEARTBEAT: subtle gray
                0x35 -> GixxerTokens.lushGreen            // a535 SMS: green
                0x36 -> GixxerTokens.zoneHot            // a536 IDENTITY: magenta-ish
                0x37 -> GixxerTokens.zoneCool            // a537 TELEMETRY: bright cyan
                else -> GixxerTokens.onSurfaceDim
            }
            0, 29 -> GixxerTokens.onSurfaceDim               // header + terminator dimmed
            else -> Color.Unspecified
        }
        withStyle(SpanStyle(color = color)) {
            append("%02x".format(b))
        }
        if (i < bytes.size - 1) append(" ")
    }
}
