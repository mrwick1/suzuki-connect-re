package dev.mrwick.gixxerbridge.ui.inspector

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.ble.FrameEvent
import dev.mrwick.gixxerbridge.protocol.FrameType
import dev.mrwick.gixxerbridge.protocol.decodeFrame
import dev.mrwick.gixxerbridge.util.Hex
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

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("BLE Frame Inspector") },
            actions = {
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
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(filtered, key = { "${it.tMillis}-${it.bytes.contentHashCode()}" }) { event ->
                FrameRow(event)
                HorizontalDivider()
            }
        }
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
    val dirColor = if (event.direction == FrameEvent.Direction.TX) Color(0xFF22D3EE) else Color(0xFFFCD34D)
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
                Hex.encode(event.bytes),
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
