package dev.mrwick.gixxerbridge.ui.diagnostics

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.util.AppLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live in-app log viewer. Renders [AppLog.entries] with level + tag filters and a
 * "Share log" action that exports the on-disk rolling log via FileProvider.
 */
@Composable
fun DiagnosticsScreen() {
    val ctx = LocalContext.current
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var minLevel by remember { mutableStateOf(AppLog.Level.D) }
    var autoscroll by remember { mutableStateOf(true) }

    val filtered = remember(entries, query, minLevel) {
        entries.filter { e ->
            e.level.ordinal >= minLevel.ordinal &&
                (query.isBlank() ||
                    e.tag.contains(query, ignoreCase = true) ||
                    e.message.contains(query, ignoreCase = true))
        }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(filtered.size, autoscroll) {
        if (autoscroll && filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Text("Diagnostics", style = MaterialTheme.typography.titleLarge)
        Text(
            "${entries.size} entries (showing ${filtered.size}) — written to filesDir/diag/app.log",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter (tag or message)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            LevelChip("D", AppLog.Level.D, minLevel) { minLevel = it }
            LevelChip("I", AppLog.Level.I, minLevel) { minLevel = it }
            LevelChip("W", AppLog.Level.W, minLevel) { minLevel = it }
            LevelChip("E", AppLog.Level.E, minLevel) { minLevel = it }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { autoscroll = !autoscroll }) {
                Text(if (autoscroll) "AutoScroll ON" else "AutoScroll OFF")
            }
        }
        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { shareLog(ctx) }) { Text("Share log") }
            TextButton(onClick = { AppLog.clear() }) { Text("Clear") }
        }
        Spacer(Modifier.height(4.dp))

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.tMillis.toString() + "-" + it.message.hashCode() }) { e ->
                    LogRow(e)
                }
            }
        }
    }
}

@Composable
private fun LevelChip(
    label: String,
    level: AppLog.Level,
    current: AppLog.Level,
    onSelect: (AppLog.Level) -> Unit,
) {
    val selected = current == level
    AssistChip(
        onClick = { onSelect(level) },
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun LogRow(e: AppLog.Entry) {
    val ts = remember(e.tMillis) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(e.tMillis))
    }
    val color = when (e.level) {
        AppLog.Level.D -> MaterialTheme.colorScheme.onSurfaceVariant
        AppLog.Level.I -> MaterialTheme.colorScheme.onSurface
        AppLog.Level.W -> Color(0xFFFBBF24)
        AppLog.Level.E -> MaterialTheme.colorScheme.error
    }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            ts,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            e.level.tag,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            e.tag,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                e.message,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
            )
            if (e.throwable != null) {
                Text(
                    e.throwable.lineSequence().take(8).joinToString("\n"),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun shareLog(ctx: Context) {
    val file = AppLog.activeLogFile() ?: return
    if (!file.exists()) {
        AppLog.i("Diagnostics", "share requested but log file is empty")
        return
    }
    val uri = FileProvider.getUriForFile(
        ctx,
        ctx.packageName + ".fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "Share log").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
