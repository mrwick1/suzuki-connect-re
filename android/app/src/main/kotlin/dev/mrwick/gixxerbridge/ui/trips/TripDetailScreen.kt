package dev.mrwick.gixxerbridge.ui.trips

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.analytics.SpeedTrack
import dev.mrwick.gixxerbridge.analytics.SpeedTrackColors
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideLocationEntity
import dev.mrwick.gixxerbridge.data.RideMeta
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.export.CsvExporter
import dev.mrwick.gixxerbridge.export.GpxExporter
import dev.mrwick.gixxerbridge.export.ShareCardRenderer
import dev.mrwick.gixxerbridge.export.TripShareText
import dev.mrwick.gixxerbridge.ui.components.SkeletonBlock
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import dev.mrwick.gixxerbridge.ui.components.SkeletonLine
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/** Detail screen for one ride: hero card with aggregates + meta (fav/tags/note) + GPX export + per-sample log. */
@Composable
fun TripDetailScreen(rideId: Long, vm: TripsViewModel) {
    LaunchedEffect(rideId) { vm.loadSamples(rideId) }
    val samples by vm.selectedSamples.collectAsStateWithLifecycle()
    val rides by vm.rides.collectAsStateWithLifecycle()
    val metaMap by vm.metaMap.collectAsStateWithLifecycle()
    val fillKmPerL by vm.fillKmPerL.collectAsStateWithLifecycle()
    val ride: RideEntity? = remember(rides, rideId) { rides.firstOrNull { it.id == rideId } }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locations by remember(rideId) { mutableStateOf<List<RideLocationEntity>>(emptyList()) }
    LaunchedEffect(rideId) { locations = vm.locationsFor(rideId) }
    var children by remember(rideId) { mutableStateOf<List<RideEntity>>(emptyList()) }
    LaunchedEffect(rideId, ride?.isMerged) {
        children = if (ride?.isMerged == true) vm.childrenOf(rideId) else emptyList()
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
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

        // Resolve meta for this ride by its stable natural key.
        val meta: RideMeta = metaMap[ride.startedAtMillis] ?: RideMeta()

        var showRename by remember(ride.id) { mutableStateOf(false) }
        val dateString = remember(ride.startedAtMillis) {
            SimpleDateFormat("EEE, MMM d yyyy · HH:mm", Locale.US)
                .format(Date(ride.startedAtMillis))
        }
        val nameOrFallback = ride.name?.takeIf { it.isNotBlank() } ?: dateString
        val distance = max(0, (ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)
        val fuelBurn = RideAnalytics.fuelBurnt(
            distanceKm = distance,
            fillKmPerL = fillKmPerL,
            bikeKmPerL = RideAnalytics.avgBikeEcon(samples),
        )
        val fuelUsedText = fuelBurn?.let { "~${"%.2f".format(it.litres)} L (est.)" } ?: "—"
        // Mileage uses the rider's FILL-measured km/L, not the bike's BLE econ
        // field: the latter (fuelEconKmlV2 = byte25/2) over-reads ~30% (≈64 vs a
        // fill-measured ≈49 and bike-cluster ≈53 — see DISCOVERIES 2026-06-16).
        // Falls back to "—" rather than show the unreliable bike figure.
        val mileageText = fillKmPerL?.let { "${"%.1f".format(it)} km/L" } ?: "—"
        val (movingMin, idleMin) = RideAnalytics.movingIdleMinutes(samples)
        val fuelBarsText = if (ride.fuelBarsStart != null && ride.fuelBarsEnd != null)
            "${ride.fuelBarsStart} → ${ride.fuelBarsEnd}" else "—"
        val inProgress = ride.endedAtMillis == null
        val durationMin = ((ride.endedAtMillis ?: ride.startedAtMillis) - ride.startedAtMillis) / 60_000

        // --- Hero card: large distance + duration, Inter weight 600 ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                // ── Favourite star + label row ─────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "RIDE DISTANCE · KM",
                        style = MaterialTheme.typography.labelMedium,
                        color = GixxerBrand.accent,
                    )
                    // Star toggle — right-aligned, no extra padding needed.
                    IconButton(
                        onClick = { vm.setFavorite(ride.startedAtMillis, !meta.favorite) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (meta.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (meta.favorite) "Remove from favourites" else "Add to favourites",
                            tint = if (meta.favorite) GixxerTokens.zoneMid else GixxerTokens.textMuted,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                dev.mrwick.gixxerbridge.ui.components.HeroNumeral(
                    text = "$distance",
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 72.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Tap title to rename; always show the date below as unambiguous context.
                Text(
                    nameOrFallback,
                    style = MaterialTheme.typography.titleLarge,
                    color = GixxerTokens.textPrimary,
                    modifier = Modifier.clickable { showRename = true },
                )
                if (nameOrFallback != dateString) {
                    Text(
                        dateString,
                        style = MaterialTheme.typography.bodySmall,
                        color = GixxerTokens.textMuted,
                    )
                } else {
                    Text(
                        "Tap title to rename",
                        style = MaterialTheme.typography.labelSmall,
                        color = GixxerTokens.textMuted,
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                ) {
                    HeroStat(label = "Distance", value = "$distance km")
                    HeroStat(label = "Duration", value = if (inProgress) "—" else "$durationMin min")
                    HeroStat(label = "Avg speed", value = "${"%.1f".format(ride.avgSpeedKmh)} km/h")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    HeroStat(label = "Max speed", value = "${ride.maxSpeedKmh} km/h")
                    HeroStat(label = "Mileage", value = mileageText)
                    HeroStat(label = "Fuel used", value = fuelUsedText)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    HeroStat(label = "Moving", value = "$movingMin min")
                    HeroStat(label = "Idle", value = "$idleMin min")
                    HeroStat(label = "Fuel bars", value = fuelBarsText)
                }
            }
        }

        // ── Tags + Note section ───────────────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        RideMetaSection(
            meta = meta,
            onToggleTag = { tag ->
                val updated = if (tag in meta.tags) meta.tags - tag else meta.tags + tag
                vm.setTags(ride.startedAtMillis, updated)
            },
            onNoteChange = { vm.setNote(ride.startedAtMillis, it) },
        )

        if (samples.size >= 2) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "SPEED · THIS RIDE (KM/H)",
                        style = MaterialTheme.typography.labelMedium,
                        color = GixxerBrand.accent,
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    dev.mrwick.gixxerbridge.ui.components.TraceChart(
                        points = samples.map { (it.speedKmh.coerceIn(0, 120)) / 120f },
                        animateDraw = true,
                        strokeWidth = 3.dp,
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                }
            }
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

        Spacer(modifier = Modifier.height(12.dp))

        // --- GPX export button: outlined, full-width, with icon ---
        OutlinedButton(
            onClick = {
                scope.launch {
                    val rideLocations = vm.locationsFor(ride.id)
                    if (rideLocations.isEmpty()) {
                        Toast.makeText(
                            context,
                            "No GPS samples recorded for this ride",
                            Toast.LENGTH_SHORT,
                        ).show()
                        return@launch
                    }
                    val uri = withContext(Dispatchers.IO) {
                        val gpx = GpxExporter.toGpx(ride, rideLocations)
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
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Outlined.FileDownload,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("Export GPX")
        }

        // Secondary exports (CSV + share card) — kept but less prominent.
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = {
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
                },
                modifier = Modifier.weight(1f),
            ) { Text("Share CSV", color = GixxerTokens.textMuted) }
            TextButton(
                onClick = {
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
                },
                modifier = Modifier.weight(1f),
            ) { Text("Share card", color = GixxerTokens.textMuted) }
        }

        // Share for AI — fires a plain-text share sheet (ChatGPT / Gemini / Claude / any app).
        OutlinedButton(
            onClick = {
                scope.launch {
                    val rideSamples = vm.samplesFor(ride.id)
                    val rideLocations = vm.locationsFor(ride.id)
                    val text = withContext(Dispatchers.Default) {
                        TripShareText.build(
                            ride = ride,
                            samples = rideSamples,
                            locations = rideLocations,
                            zone = TimeZone.getDefault().id,
                        )
                    }
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share ride for AI analysis"))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text("Share for AI")
        }

        Spacer(modifier = Modifier.height(12.dp))
        RideTrackCard(locations = locations, samples = samples)
        Spacer(Modifier.height(12.dp))
        SpeedDistributionCard(samples)
        Spacer(Modifier.height(12.dp))
        FuelEconomyTrendCard(samples)
        Spacer(Modifier.height(12.dp))
        FuelLevelCard(samples)
        Spacer(Modifier.height(12.dp))
        // Raw per-sample log — tucked into a collapsible accordion (collapsed by
        // default) to keep the page clean. When open it's a fixed-height inner
        // scroller (legal nested scroll inside the outer verticalScroll) so the
        // thousands of rows stay lazy.
        if (samples.isNotEmpty()) {
            AccordionCard(title = "RAW TELEMETRY · ${samples.size} SAMPLES") {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(360.dp)) {
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
                            color = GixxerTokens.textMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        // ── Merged-ride segments (kept at the bottom): children + split-back ──
        if (ride.isMerged && children.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            MergedSegmentsCard(
                children = children,
                onSplit = {
                    vm.split(ride.id)
                    Toast.makeText(context, "Split back into segments", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }
}

// ── Meta section: tags + note ─────────────────────────────────────────────────

/**
 * Card section for editing ride metadata.
 *
 * Top row: horizontally-scrollable tag chips. Active tags are shown as
 * [InputChip]s with a close button (removes the tag). Preset tags not yet
 * applied appear as [SuggestionChip]s with a + icon. A free-text input
 * at the end lets the rider add a custom tag.
 *
 * Bottom: a multi-line [OutlinedTextField] for the ride note.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RideMetaSection(
    meta: RideMeta,
    onToggleTag: (String) -> Unit,
    onNoteChange: (String) -> Unit,
) {
    var noteText by remember(meta.note) { mutableStateOf(meta.note) }
    var customTagInput by remember { mutableStateOf("") }
    var showCustomTagField by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "TAGS & NOTES",
                style = MaterialTheme.typography.labelMedium,
                color = GixxerBrand.accent,
            )
            Spacer(modifier = Modifier.height(10.dp))

            // ── Tag area: FlowRow wraps when chips are wide ───────────────────
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Applied tags as InputChips (removable).
                meta.tags.sorted().forEach { tag ->
                    InputChip(
                        selected = true,
                        onClick = { onToggleTag(tag) },
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove $tag",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        colors = InputChipDefaults.inputChipColors(
                            selectedContainerColor = GixxerTokens.accent.copy(alpha = 0.15f),
                            selectedLabelColor = GixxerTokens.accent,
                            selectedTrailingIconColor = GixxerTokens.accent,
                        ),
                    )
                }

                // Preset tags not yet applied as SuggestionChips.
                PRESET_TAGS.filter { it !in meta.tags }.forEach { tag ->
                    SuggestionChip(
                        onClick = { onToggleTag(tag) },
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        icon = {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add $tag",
                                modifier = Modifier.size(14.dp),
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            labelColor = GixxerTokens.textMuted,
                            iconContentColor = GixxerTokens.textMuted,
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = GixxerTokens.border,
                        ),
                    )
                }

                // "+ custom" chip that reveals an inline text input.
                if (!showCustomTagField) {
                    SuggestionChip(
                        onClick = { showCustomTagField = true },
                        label = { Text("+ custom", style = MaterialTheme.typography.labelSmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            labelColor = GixxerTokens.textMuted,
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = GixxerTokens.border,
                        ),
                    )
                }
            }

            // Inline custom-tag input field.
            if (showCustomTagField) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = customTagInput,
                    onValueChange = { customTagInput = it.take(30) },
                    label = { Text("Custom tag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Row {
                            TextButton(
                                onClick = {
                                    val tag = customTagInput.trim()
                                    if (tag.isNotEmpty()) {
                                        onToggleTag(tag)
                                    }
                                    customTagInput = ""
                                    showCustomTagField = false
                                },
                            ) { Text("Add", color = GixxerTokens.accent) }
                            TextButton(
                                onClick = {
                                    customTagInput = ""
                                    showCustomTagField = false
                                },
                            ) { Text("Cancel", color = GixxerTokens.textMuted) }
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Notes field ────────────────────────────────────────────────────
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it.take(500) },
                label = { Text("Note") },
                placeholder = { Text("Anything memorable about this ride…", color = GixxerTokens.textMuted) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5,
                // Persist on every keystroke — DataStore writes are coalesced
                // internally; no explicit debounce needed for a 500-char field.
                // The ViewModel coroutine launch is cheap.
            )
            // Trigger the VM write when the text settles (after each recomposition
            // with a changed value). Since we remember(meta.note) above, initial
            // set of noteText won't trigger an unnecessary write.
            LaunchedEffect(noteText) {
                if (noteText != meta.note) {
                    onNoteChange(noteText)
                }
            }
        }
    }
}

// ── Existing private helpers ──────────────────────────────────────────────────

/** One hero stat: small label above large value, Inter weight 600. */
@Composable
private fun HeroStat(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.textMuted,
        )
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.W600,
            color = GixxerTokens.textPrimary,
        )
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

/**
 * Square Canvas card that plots the ride's GPS samples as a speed-colored polyline.
 *
 * When [samples] is empty the polyline falls back to a single accent color (the
 * [SpeedTrackColors.COLOR_COOL] teal), so the card still renders without telemetry.
 * Each segment is painted with the color matching the speed band at the GPS point.
 *
 * A three-chip legend below the map shows the zone thresholds.
 */
@Composable
private fun RideTrackCard(
    locations: List<RideLocationEntity>,
    samples: List<dev.mrwick.gixxerbridge.data.RideSampleEntity>,
) {
    if (locations.size < 2) return

    // Build speed-colored segments once; recompute only when inputs change.
    val segments = remember(locations, samples) {
        SpeedTrack.build(locations, samples)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Track",
                style = MaterialTheme.typography.titleMedium,
                color = GixxerTokens.textPrimary,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${locations.size} GPS samples",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
            )
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

                val bgColor = GixxerTokens.surfaceElevated
                drawRect(bgColor, size = size, style = Stroke(width = 1f))

                if (segments.isEmpty()) {
                    // Fallback: no segments (< 2 GPS points would have returned early;
                    // this path is reached when locations >= 2 but samples is empty and
                    // SpeedTrack returned an empty list — should not happen per contract,
                    // but be defensive).
                    val path = Path()
                    val first = project(locations[0].lat, locations[0].lng)
                    path.moveTo(first.x, first.y)
                    for (i in 1 until locations.size) {
                        val p = project(locations[i].lat, locations[i].lng)
                        path.lineTo(p.x, p.y)
                    }
                    drawPath(
                        path,
                        color = GixxerTokens.accent,
                        style = Stroke(width = 4f, cap = StrokeCap.Round),
                    )
                } else {
                    // Draw each segment with its zone color.
                    for (seg in segments) {
                        val start = project(seg.startLat, seg.startLng)
                        val end = project(seg.endLat, seg.endLng)
                        drawLine(
                            color = Color(seg.colorArgb),
                            start = start,
                            end = end,
                            strokeWidth = 5f,
                            cap = StrokeCap.Round,
                        )
                    }
                }

                // Start dot (green) + end dot (accent).
                val first = project(locations.first().lat, locations.first().lng)
                val last = project(locations.last().lat, locations.last().lng)
                drawCircle(GixxerTokens.success, radius = 8f, center = first)
                drawCircle(GixxerTokens.accent, radius = 8f, center = last)
            }

            // Speed zone legend — only shown when we have actual speed data.
            if (samples.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SpeedLegendChip(
                        color = Color(SpeedTrackColors.COLOR_COOL),
                        label = "< ${SpeedTrackColors.THRESHOLD_COOL_MID_KMH}",
                    )
                    SpeedLegendChip(
                        color = Color(SpeedTrackColors.COLOR_MID),
                        label = "${SpeedTrackColors.THRESHOLD_COOL_MID_KMH}–${SpeedTrackColors.THRESHOLD_MID_HOT_KMH}",
                    )
                    SpeedLegendChip(
                        color = Color(SpeedTrackColors.COLOR_HOT),
                        label = "≥ ${SpeedTrackColors.THRESHOLD_MID_HOT_KMH}",
                    )
                }
            }
        }
    }
}

/** One colored dot + label for the speed zone legend. */
@Composable
private fun SpeedLegendChip(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .then(Modifier),
        ) {
            Canvas(modifier = Modifier.matchParentSize()) {
                drawCircle(color = color)
            }
        }
        Text(
            text = "$label km/h",
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.textMuted,
        )
    }
}

/**
 * Collapsible card: a tappable title row with a chevron that shows/hides
 * [content]. Used to tuck long/secondary sections (raw log, merged segments)
 * away by default so the detail page stays clean.
 */
@Composable
private fun AccordionCard(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = GixxerTokens.textMuted,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

/**
 * Shown only for merged rides: a collapsible card listing the original child
 * segments (start time + distance) with a "Split back into segments" action
 * that reverses the merge via [onSplit].
 */
@Composable
private fun MergedSegmentsCard(
    children: List<RideEntity>,
    onSplit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeFmt = remember { SimpleDateFormat("d MMM HH:mm", Locale.US) }
    AccordionCard(title = "MERGED FROM ${children.size} SEGMENTS", modifier = modifier) {
        children.forEach { c ->
            val km = max(0, (c.endOdoKm ?: c.startOdoKm) - c.startOdoKm)
            Text(
                "${timeFmt.format(Date(c.startedAtMillis))} · $km km",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSplit) { Text("Split back into segments") }
    }
}

@Composable
private fun SpeedDistributionCard(samples: List<dev.mrwick.gixxerbridge.data.RideSampleEntity>) {
    if (samples.isEmpty()) return
    val buckets = RideAnalytics.speedHistogram(samples, bucketSizeKmh = 20, maxKmh = 120)
    val maxCount = (buckets.maxOfOrNull { it.sampleCount } ?: 0).coerceAtLeast(1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("SPEED DISTRIBUTION", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
            Spacer(Modifier.height(12.dp))
            buckets.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("${b.lowKmh}–${b.highKmh}", style = MaterialTheme.typography.bodySmall,
                        color = GixxerTokens.textMuted, modifier = Modifier.width(64.dp))
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(b.sampleCount.toFloat() / maxCount)
                                .height(14.dp)
                                .background(GixxerTokens.accent.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FuelEconomyTrendCard(samples: List<dev.mrwick.gixxerbridge.data.RideSampleEntity>) {
    val econ = samples.mapNotNull { it.fuelEconKml }.filter { it > 0.0 }
    if (econ.size < 2) return
    val maxE = (econ.maxOrNull() ?: 1.0).coerceAtLeast(1.0)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("FUEL ECONOMY TREND", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
            Spacer(Modifier.height(4.dp))
            Text("Bike-reported km/L over the ride (trend only)", style = MaterialTheme.typography.bodySmall, color = GixxerTokens.textMuted)
            Spacer(Modifier.height(12.dp))
            dev.mrwick.gixxerbridge.ui.components.TraceChart(
                points = econ.map { (it / maxE).toFloat() },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )
        }
    }
}

@Composable
private fun FuelLevelCard(samples: List<dev.mrwick.gixxerbridge.data.RideSampleEntity>) {
    val bars = samples.mapNotNull { it.fuelBars }
    if (bars.size < 2) return
    val maxB = (bars.maxOrNull() ?: 1).coerceAtLeast(1)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("FUEL LEVEL", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
            Spacer(Modifier.height(12.dp))
            dev.mrwick.gixxerbridge.ui.components.TraceChart(
                points = bars.map { it.toFloat() / maxB },
                modifier = Modifier.fillMaxWidth().height(80.dp),
            )
        }
    }
}
