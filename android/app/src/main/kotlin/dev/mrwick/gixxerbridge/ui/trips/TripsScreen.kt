package dev.mrwick.gixxerbridge.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import dev.mrwick.gixxerbridge.ui.home.components.EmptyState
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.trips.components.RideRow
import dev.mrwick.gixxerbridge.ui.trips.components.WeekSectionHeader
import kotlinx.coroutines.delay
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
 *   - Rides grouped by week, with section headers: "THIS WEEK", "LAST WEEK",
 *     "WEEK OF 19 MAY"
 *   - Each row: 48dp date rail (day + month) | distance headline + subtitle |
 *     60×32dp speed sparkline (loaded lazily per row)
 *   - Loading: 3 skeleton cards for 250 ms grace window
 *   - Empty: single EmptyState with Route icon
 */
@Composable
fun TripsScreen(
    vm: TripsViewModel,
    onOpenRide: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val rides by vm.rides.collectAsStateWithLifecycle()
    val monthSummary by vm.monthSummary.collectAsStateWithLifecycle()

    // 250 ms grace window before showing the real empty state, so skeletons
    // appear instead of a flash of empty state when Room hasn't emitted yet.
    val bootDone by produceState(initialValue = false) {
        delay(250); value = true
    }

    if (rides.isEmpty() && !bootDone) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(3) { SkeletonCard() }
        }
        return
    }

    if (rides.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.Route,
                body = "No rides yet — take the bike for a spin.",
                ctaLabel = null,
                onCta = null,
            )
        }
        return
    }

    // Group rides into week buckets for section headers.
    val grouped = rememberWeekGroups(rides)

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ── Screen header ─────────────────────────────────────────────────────
        item(key = "__header") {
            TripsScreenHeader(
                rideCount = monthSummary.rideCount,
                totalKm = monthSummary.totalKm,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )
        }

        // ── Week-grouped ride rows ─────────────────────────────────────────────
        grouped.forEach { (weekLabel, weekRides) ->
            item(key = "header__$weekLabel") {
                WeekSectionHeader(label = weekLabel)
            }
            items(weekRides, key = { it.id }) { ride ->
                RideRowWithSparkline(
                    ride = ride,
                    vm = vm,
                    onOpen = { onOpenRide(ride.id) },
                    onDelete = { vm.delete(ride.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
private fun TripsScreenHeader(
    rideCount: Int,
    totalKm: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "TRIPS · THIS MONTH",
            style = MaterialTheme.typography.labelMedium,
            color = GixxerBrand.accent,
        )
        dev.mrwick.gixxerbridge.ui.components.HeroNumeral(
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
}

// ── Row with lazy sparkline loading ───────────────────────────────────────────

/**
 * Wraps [RideRow] and fetches sparkline samples lazily via [LaunchedEffect]
 * once the row enters composition (i.e., becomes visible in the LazyColumn).
 * Uses null to distinguish "not yet loaded" from "loaded but empty".
 */
@Composable
private fun RideRowWithSparkline(
    ride: RideEntity,
    vm: TripsViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // null = not loaded yet; emptyList = loaded but ride has no samples
    var samples by remember(ride.id) { mutableStateOf<List<RideSampleEntity>?>(null) }

    LaunchedEffect(ride.id) {
        samples = vm.samplesForSparkline(ride.id, limit = 60)
    }

    RideRow(
        ride = ride,
        sparklineSamples = samples,
        onClick = onOpen,
        onDelete = onDelete,
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
