package dev.mrwick.redline.ui.trips

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.analytics.gapHintLabel
import dev.mrwick.redline.data.MergeResult
import dev.mrwick.redline.data.RideEntity
import dev.mrwick.redline.data.RideMeta
import dev.mrwick.redline.data.RideSampleEntity
import dev.mrwick.redline.ui.components.SkeletonCard
import dev.mrwick.redline.ui.home.components.EmptyState
import dev.mrwick.redline.ui.theme.GixxerBrand
import dev.mrwick.redline.ui.theme.GixxerTokens
import dev.mrwick.redline.ui.trips.components.GapConnector
import dev.mrwick.redline.ui.trips.components.JourneyBanner
import dev.mrwick.redline.ui.trips.components.RideRow
import dev.mrwick.redline.ui.trips.components.WeekSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Trips listing screen — Wave 3 premium redesign.
 *
 * Structure:
 *   - Left-aligned header "TRIPS" + month summary ("12 rides · 156 km this month")
 *   - Filter bar: "All" | "Favourites" | one chip per tag present in any ride
 *   - Rides grouped by week, with section headers: "THIS WEEK", "LAST WEEK",
 *     "WEEK OF 19 MAY"
 *   - Each row: 48dp date rail (day + month) | distance headline + subtitle |
 *     optional star + tag pill | 60×32dp speed sparkline (loaded lazily per row)
 *   - Loading: 3 skeleton cards for 250 ms grace window
 *   - Empty: single EmptyState with Route icon
 */
@Composable
fun TripsScreen(
    vm: TripsViewModel,
    onOpenRide: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenRoutes: () -> Unit = {},
) {
    val ridesWithMeta by vm.ridesWithMeta.collectAsStateWithLifecycle()
    val monthSummary by vm.monthSummary.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val allTags by vm.allTags.collectAsStateWithLifecycle()
    val suggestion by vm.journeySuggestion.collectAsStateWithLifecycle()
    val gapHintMax by vm.gapHintMaxMin.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Whole-bike "share everything for AI" — builds the full-archive .txt and
    // hands it to the system share sheet (mirrors the per-trip share).
    val shareAllForAi: () -> Unit = {
        scope.launch {
            val text = vm.buildFullExportText(
                zone = java.time.ZoneId.systemDefault(),
                now = System.currentTimeMillis(),
            )
            val uri = withContext(Dispatchers.IO) {
                val cache = java.io.File(context.cacheDir, "redline-full-export.txt")
                cache.writeText(text)
                androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", cache,
                )
            }
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share all bike data for AI"),
            )
        }
    }

    // Selection state: rideId set + whether we're in selection mode.
    val selected = remember { mutableStateListOf<Long>() }
    var selectionMode by remember { mutableStateOf(false) }

    fun exitSelection() { selectionMode = false; selected.clear() }
    fun toggle(id: Long) {
        if (selected.contains(id)) selected.remove(id) else selected.add(id)
        if (selected.isEmpty()) selectionMode = false
    }
    fun enterSelectionWith(ids: List<Long>) {
        selectionMode = true
        selected.clear(); selected.addAll(ids)
    }

    fun doCombine() {
        val ids = selected.toList()
        vm.merge(ids) { result ->
            when (result) {
                is MergeResult.Success -> {
                    exitSelection()
                    // A bridged recording gap isn't silent — surface it via Toast.
                    if (result.bridgedGapKm > 0) {
                        Toast.makeText(
                            context,
                            "Combined across a ${result.bridgedGapKm} km gap",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    scope.launch {
                        val r = snackbarHostState.showSnackbar(
                            message = "Trips combined", actionLabel = "Undo",
                        )
                        if (r == SnackbarResult.ActionPerformed) vm.split(result.parentId)
                    }
                }
                // Failures use a Toast, not a Snackbar: the success Snackbar (with
                // Undo) can still be visible, and Material3 queues Snackbars one at
                // a time, so a failure message could sit invisible behind it. A
                // Toast always shows and never hides the reason. Selection is kept
                // so the rider can adjust the picks and retry.
                is MergeResult.InvalidSelection ->
                    Toast.makeText(context, result.reason, Toast.LENGTH_LONG).show()
                MergeResult.TooFew ->
                    Toast.makeText(context, "Select at least two trips", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 250 ms grace window before showing the real empty state, so skeletons
    // appear instead of a flash of empty state when Room hasn't emitted yet.
    val bootDone by produceState(initialValue = false) {
        delay(250); value = true
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { exitSelection() }) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { doCombine() }, enabled = selected.size >= 2) {
                        Text("Combine ${selected.size}")
                    }
                }
            }
        },
    ) { innerPadding ->
        when {
            ridesWithMeta.isEmpty() && !bootDone -> {
                LazyColumn(
                    modifier = Modifier.padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(3) { SkeletonCard() }
                }
            }

            ridesWithMeta.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyState(
                        icon = Icons.Outlined.Route,
                        body = if (filter == TripsFilter.All) {
                            "No rides yet — take the bike for a spin."
                        } else {
                            "No rides match the current filter."
                        },
                        ctaLabel = null,
                        onCta = null,
                    )
                }
            }

            else -> {
                // Group rides into week buckets for section headers.
                val grouped = rememberWeekGroups(ridesWithMeta.map { it.ride })

                // Build a map of rideId → meta for fast lookup in each row.
                val metaByRideId = remember(ridesWithMeta) {
                    ridesWithMeta.associate { it.ride.id to it.meta }
                }

                LazyColumn(
                    modifier = Modifier.padding(innerPadding),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    // ── Journey suggestion banner ─────────────────────────────────
                    suggestion?.let { s ->
                        if (!selectionMode) item(key = "__journey_banner") {
                            JourneyBanner(
                                tripCount = s.rideIds.size,
                                dateLabel = formatJourneyDate(s.startMillis),
                                totalKm = s.totalKm,
                                onReview = { enterSelectionWith(s.rideIds) },
                                onDismiss = { vm.dismissSuggestion(s.startMillis) },
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }

                    // ── Screen header ─────────────────────────────────────────────
                    item(key = "__header") {
                        TripsScreenHeader(
                            rideCount = monthSummary.rideCount,
                            totalKm = monthSummary.totalKm,
                            onOpenRoutes = onOpenRoutes,
                            onShareAllForAi = shareAllForAi,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
                        )
                    }

                    // ── Filter bar ────────────────────────────────────────────────
                    item(key = "__filter") {
                        TripsFilterBar(
                            filter = filter,
                            allTags = allTags,
                            onFilter = { vm.setFilter(it) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }

                    // ── Week-grouped ride rows ────────────────────────────────────
                    grouped.forEach { (weekLabel, weekRides) ->
                        item(key = "header__$weekLabel") {
                            WeekSectionHeader(label = weekLabel)
                        }
                        itemsIndexed(weekRides, key = { _, r -> r.id }) { i, ride ->
                            val meta = metaByRideId[ride.id] ?: RideMeta()
                            Column {
                                RideRowWithSparkline(
                                    ride = ride,
                                    meta = meta,
                                    vm = vm,
                                    selectionMode = selectionMode,
                                    selected = selected.contains(ride.id),
                                    onOpen = {
                                        if (selectionMode) toggle(ride.id) else onOpenRide(ride.id)
                                    },
                                    onLongOpen = {
                                        if (!selectionMode) selectionMode = true
                                        toggle(ride.id)
                                    },
                                    onDelete = { vm.delete(ride.id) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                                // Gap connector to the older neighbour within this week group.
                                val older = weekRides.getOrNull(i + 1)
                                if (older != null) {
                                    val gapMin = (ride.startedAtMillis - (older.endedAtMillis ?: older.startedAtMillis)) / 60_000L
                                    val chains = older.endOdoKm != null && ride.startOdoKm == older.endOdoKm
                                    if (chains && gapMin in 0L..gapHintMax.toLong()) {
                                        GapConnector(label = gapHintLabel(gapMin))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Formats a journey start time as "Wed 12 Jun" for the suggestion banner. */
private fun formatJourneyDate(millis: Long): String {
    val fmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.US)
    return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(fmt)
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun TripsScreenHeader(
    rideCount: Int,
    totalKm: Int,
    onOpenRoutes: () -> Unit,
    onShareAllForAi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "TRIPS · THIS MONTH",
                style = MaterialTheme.typography.labelMedium,
                color = GixxerBrand.accent,
            )
            dev.mrwick.redline.ui.components.HeroNumeral(
                text = "$totalKm",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 72.sp,
            )
            Text(
                text = if (rideCount > 0) {
                    "KM · $rideCount ${if (rideCount == 1) "ride" else "rides"}"
                } else {
                    "KM · no rides yet"
                },
                style = MaterialTheme.typography.labelMedium,
                color = GixxerTokens.textMuted,
            )
        }
        Row {
            // Share everything for AI — whole-bike full-archive export.
            IconButton(
                onClick = onShareAllForAi,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = "Share all bike data for AI",
                    tint = GixxerTokens.textMuted,
                )
            }
            // Routes action — opens the route leaderboard screen.
            IconButton(
                onClick = onOpenRoutes,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Route,
                    contentDescription = "Routes",
                    tint = GixxerTokens.textMuted,
                )
            }
        }
    }
}

// ── Filter bar ────────────────────────────────────────────────────────────────

/**
 * Horizontally scrollable chip row for ride filtering.
 *
 * Chips: "All" | "★ Starred" | one chip per tag in [allTags] (sorted).
 * Tapping a chip sets the filter; tapping the active chip resets to All.
 */
@Composable
private fun TripsFilterBar(
    filter: TripsFilter,
    allTags: Set<String>,
    onFilter: (TripsFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // "All" chip
        FilterChip(
            selected = filter == TripsFilter.All,
            onClick = { onFilter(TripsFilter.All) },
            label = { Text("All") },
            colors = filterChipColors(),
        )

        // "★ Starred" chip
        FilterChip(
            selected = filter == TripsFilter.Favourites,
            onClick = {
                onFilter(
                    if (filter == TripsFilter.Favourites) TripsFilter.All
                    else TripsFilter.Favourites
                )
            },
            label = { Text("Starred") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                )
            },
            colors = filterChipColors(),
        )

        // One chip per tag, sorted alphabetically.
        allTags.sorted().forEach { tag ->
            val isSelected = filter is TripsFilter.ByTag && filter.tag == tag
            FilterChip(
                selected = isSelected,
                onClick = {
                    onFilter(if (isSelected) TripsFilter.All else TripsFilter.ByTag(tag))
                },
                label = { Text(tag) },
                colors = filterChipColors(),
            )
        }
    }
}

@Composable
private fun filterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = GixxerTokens.accent.copy(alpha = 0.15f),
    selectedLabelColor = GixxerTokens.accent,
    selectedLeadingIconColor = GixxerTokens.accent,
    labelColor = GixxerTokens.textMuted,
)

// ── Row with lazy sparkline loading ───────────────────────────────────────────

/**
 * Wraps [RideRow] and fetches sparkline samples lazily via [LaunchedEffect]
 * once the row enters composition (i.e., becomes visible in the LazyColumn).
 * Uses null to distinguish "not yet loaded" from "loaded but empty".
 */
@Composable
private fun RideRowWithSparkline(
    ride: RideEntity,
    meta: RideMeta,
    vm: TripsViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongOpen: () -> Unit = {},
) {
    // null = not loaded yet; emptyList = loaded but ride has no samples
    var samples by remember(ride.id) { mutableStateOf<List<RideSampleEntity>?>(null) }

    LaunchedEffect(ride.id) {
        samples = vm.samplesForSparkline(ride.id, limit = 60)
    }

    RideRow(
        ride = ride,
        meta = meta,
        sparklineSamples = samples,
        onClick = onOpen,
        onDelete = onDelete,
        selectionMode = selectionMode,
        selected = selected,
        onLongClick = onLongOpen,
        modifier = modifier,
    )
}

// ── Week grouping ─────────────────────────────────────────────────────────────

/**
 * Groups a newest-first list of rides by ISO week, returning an ordered list
 * of (weekLabel, rides) pairs. The first group is the most recent.
 *
 * Labels:
 *   - Current ISO week → "THIS WEEK"
 *   - Previous ISO week → "LAST WEEK"
 *   - Anything older → "WEEK OF {D} {MON}" (start-of-week Monday)
 */
@Composable
private fun rememberWeekGroups(
    rides: List<RideEntity>,
): List<Pair<String, List<RideEntity>>> = remember(rides) {
    computeWeekGroups(rides)
}

private fun computeWeekGroups(rides: List<RideEntity>): List<Pair<String, List<RideEntity>>> {
    val zone = ZoneId.systemDefault()
    // ISO week fields: Monday is first day of week; week 1 = week containing Thursday.
    val isoWeek = WeekFields.ISO
    val weekOfYear = isoWeek.weekOfWeekBasedYear()
    val weekBasedYear = isoWeek.weekBasedYear()

    val now = ZonedDateTime.now(zone)
    val thisWeekYear = now.get(weekBasedYear)
    val thisWeek = now.get(weekOfYear)

    // Derive "last week" by subtracting 7 days rather than `thisWeek - 1`, which
    // mishandles year boundaries: ISO years can have 53 weeks, so in week 1 the
    // previous week may be 52 OR 53. minusWeeks(1) gets both the number and the
    // week-based year right.
    val lastWeekDate = now.minusWeeks(1)
    val lastWeekNum = lastWeekDate.get(weekOfYear)
    val lastWeekYear = lastWeekDate.get(weekBasedYear)

    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    // Keep insertion order (rides are newest-first, so groups appear newest-first)
    val grouped = LinkedHashMap<String, MutableList<RideEntity>>()
    for (ride in rides) {
        val rdt = Instant.ofEpochMilli(ride.startedAtMillis).atZone(zone)
        val rWeekYear = rdt.get(weekBasedYear)
        val rWeek = rdt.get(weekOfYear)
        val label = when {
            rWeekYear == thisWeekYear && rWeek == thisWeek -> "THIS WEEK"
            rWeekYear == lastWeekYear && rWeek == lastWeekNum -> "LAST WEEK"
            else -> {
                // Monday of that week
                val monday = rdt.with(DayOfWeek.MONDAY)
                "WEEK OF ${monday.format(fmt).uppercase(Locale.US)}"
            }
        }
        grouped.getOrPut(label) { mutableListOf() }.add(ride)
    }
    return grouped.map { (k, v) -> k to v }
}
