package dev.mrwick.gixxerbridge.ui.trips

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.export.CsvExporter
import dev.mrwick.gixxerbridge.export.GpxExporter
import dev.mrwick.gixxerbridge.export.ShareCardRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.mrwick.gixxerbridge.ui.components.SkeletonBlock
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import dev.mrwick.gixxerbridge.ui.components.SkeletonLine
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Detail screen for one ride: header with aggregates + monospaced per-sample list. */
@Composable
fun TripDetailScreen(rideId: Long, vm: TripsViewModel) {
    LaunchedEffect(rideId) { vm.loadSamples(rideId) }
    val samples by vm.selectedSamples.collectAsStateWithLifecycle()
    val rides by vm.rides.collectAsStateWithLifecycle()
    val ride: RideEntity? = remember(rides, rideId) { rides.firstOrNull { it.id == rideId } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locations by remember(rideId) { mutableStateOf<List<RideLocationEntity>>(emptyList()) }
    LaunchedEffect(rideId) { locations = vm.locationsFor(rideId) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (ride == null) {
            // rides flow seeds with emptyList(); show a skeleton header + map +
            // a few sample rows while Room's first emission lands.
            SkeletonLine(widthFraction = 0.7f, height = 24.dp)
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonLine(widthFraction = 0.4f, height = 14.dp)
            Spacer(modifier = Modifier.height(4.dp))
            SkeletonLine(widthFraction = 0.4f, height = 14.dp)
            Spacer(modifier = Modifier.height(4.dp))
            SkeletonLine(widthFraction = 0.3f, height = 14.dp)
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonBlock(height = 200.dp)
            Spacer(modifier = Modifier.height(12.dp))
            repeat(3) {
                SkeletonCard()
                Spacer(modifier = Modifier.height(8.dp))
            }
            return@Column
        }
        if (ride != null) {
            var showRename by remember(ride.id) { mutableStateOf(false) }
            val dateString = remember(ride.startedAtMillis) {
                SimpleDateFormat("EEE, MMM d yyyy · HH:mm", Locale.US)
                    .format(Date(ride.startedAtMillis))
            }
            val nameOrFallback = ride.name?.takeIf { it.isNotBlank() } ?: dateString
            // Tap the title to rename. Always show the date below as
            // unambiguous context, even when a name is set.
            Text(
                nameOrFallback,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.clickable { showRename = true },
            )
            if (nameOrFallback != dateString) {
                Text(
                    dateString,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF94A3B8),
                )
            } else {
                Text(
                    "Tap title to rename",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                )
            }
            if (showRename) {
                RenameRideDialog(
                    current = ride.name.orEmpty(),
                    onDismiss = { showRename = false },
                    onConfirm = { newName ->
                        vm.rename(ride.id, newName)
                        showRename = false
                    },
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Max speed: ${ride.maxSpeedKmh} km/h",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Avg speed: ${"%.1f".format(ride.avgSpeedKmh)} km/h",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Samples: ${ride.sampleCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val locations = vm.locationsFor(ride.id)
                        if (locations.isEmpty()) {
                            Toast.makeText(
                                context,
                                "No GPS samples recorded for this ride",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }
                        // PERF: GPX serialisation + file write off the main
                        // thread (audit finding 4.1). For long rides this can
                        // be tens of ms; chooser launch is back on Main after.
                        val uri = withContext(Dispatchers.IO) {
                            val gpx = GpxExporter.toGpx(ride, locations)
                            val cache = File(context.cacheDir, "ride-${ride.id}.gpx")
                            cache.writeText(gpx)
                            FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                cache,
                            )
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/gpx+xml"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share ride"))
                    }
                }) { Text("Share GPX") }
                Button(onClick = {
                    scope.launch {
                        val rideSamples = vm.samplesFor(ride.id)
                        if (rideSamples.isEmpty()) {
                            Toast.makeText(
                                context,
                                "No telemetry samples recorded for this ride",
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@launch
                        }
                        // PERF: CSV serialisation + file write off the main
                        // thread (audit finding 4.1). Long rides can have
                        // thousands of sample rows.
                        val uri = withContext(Dispatchers.IO) {
                            val csv = CsvExporter.rideSamplesToCsv(ride, rideSamples)
                            val cache = File(context.cacheDir, "ride-${ride.id}.csv")
                            cache.writeText(csv)
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
                        context.startActivity(Intent.createChooser(intent, "Share ride CSV"))
                    }
                }) { Text("Share CSV") }
                Button(onClick = {
                    scope.launch {
                        val rideLocations = vm.locationsFor(ride.id)
                        val cardFile = withContext(Dispatchers.IO) {
                            ShareCardRenderer.render(context, ride, rideLocations)
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cardFile,
                        )
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share ride card"))
                    }
                }) { Text("Share card") }
            }
            Spacer(modifier = Modifier.height(12.dp))
            RideTrackCard(locations)
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(samples, key = { it.id }) { s ->
                Text(
                    String.format(
                        Locale.US,
                        "%tT  %3d km/h  odo=%6d  A=%.1f  B=%.1f  fuel=%s  km/L=%s",
                        Date(s.tMillis),
                        s.speedKmh,
                        s.odometerKm,
                        s.tripAKm,
                        s.tripBKm,
                        s.fuelBars?.toString() ?: "-",
                        s.fuelEconKml?.let { "%.1f".format(it) } ?: "-",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * Modal dialog for renaming a ride. Submitting an empty string clears the
 * name (falls back to date in the row). 60-char cap mirrors typical chat
 * title limits — long enough for "Sunday morning coffee run with the boys"
 * but short enough not to overflow the row.
 */
@Composable
private fun RenameRideDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ride") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim().ifBlank { null }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Square Canvas card that plots the ride's GPS samples as an equal-aspect polyline. */
@Composable
private fun RideTrackCard(locations: List<RideLocationEntity>) {
    if (locations.size < 2) return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Track", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Text("${locations.size} GPS samples", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(12.dp))
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                val lats = locations.map { it.lat }
                val lngs = locations.map { it.lng }
                val minLat = lats.min(); val maxLat = lats.max()
                val minLng = lngs.min(); val maxLng = lngs.max()
                val latSpan = (maxLat - minLat).coerceAtLeast(0.0001)
                val lngSpan = (maxLng - minLng).coerceAtLeast(0.0001)
                val scale = (size.width / lngSpan).coerceAtMost(size.height / latSpan)
                val xOffset = (size.width - lngSpan * scale) / 2
                val yOffset = (size.height - latSpan * scale) / 2
                fun project(lat: Double, lng: Double) = Offset(
                    x = ((lng - minLng) * scale + xOffset).toFloat(),
                    y = (size.height - ((lat - minLat) * scale + yOffset)).toFloat(),
                )
                val cyan = Color(0xFF22D3EE)
                val dim = Color(0xFF334155)
                drawRect(dim, size = size, style = Stroke(width = 1f))
                val path = Path()
                val first = project(locations[0].lat, locations[0].lng)
                path.moveTo(first.x, first.y)
                for (i in 1 until locations.size) {
                    val p = project(locations[i].lat, locations[i].lng)
                    path.lineTo(p.x, p.y)
                }
                drawPath(path, color = cyan, style = Stroke(width = 4f, cap = StrokeCap.Round))
                drawCircle(Color(0xFF10B981), radius = 8f, center = first)
                val last = project(locations.last().lat, locations.last().lng)
                drawCircle(cyan, radius = 8f, center = last)
            }
        }
    }
}
