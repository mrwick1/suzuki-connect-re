package dev.mrwick.gixxerbridge.ui.routes

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mrwick.gixxerbridge.analytics.RouteStats
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import dev.mrwick.gixxerbridge.ui.home.components.EmptyState
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import kotlinx.coroutines.delay

/**
 * Route leaderboard screen.
 *
 * Lists routes by how many times the rider has ridden them, with median/best/worst
 * time and distance per cluster. Fuel/cost figures are tagged "(est.)" because they
 * are computed from km/L averages, not a measured litres counter.
 *
 * Contract (MainActivity wires this later):
 *   [vm]            — created by viewModel() using the default AndroidViewModelFactory; no extra args needed
 *   [onOpenRide]    — called with the representative ride id when a route row is tapped
 *   [onBack]        — called when the back arrow is tapped
 */
@Composable
fun RouteRepeatScreen(
    vm: RouteRepeatViewModel = viewModel(),
    onOpenRide: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val routes by vm.routes.collectAsStateWithLifecycle()
    val clusterRideIds by vm.clusterRideIds.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()

    // 300 ms grace window before showing the empty state, so the spinner appears
    // on first paint while the clustering coroutine runs.
    val bootDone by produceState(initialValue = false) {
        delay(300); value = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ── Screen header with back arrow ─────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = GixxerTokens.textPrimary,
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = "ROUTES",
                    style = MaterialTheme.typography.labelMedium,
                    color = GixxerBrand.accent,
                )
                Text(
                    text = "Repeat routes ranked by runs",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
            }
        }

        // ── Body ──────────────────────────────────────────────────────────────
        when {
            loading && !bootDone -> {
                // Skeleton until the grace window expires.
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(4) { SkeletonCard() }
                }
            }

            loading -> {
                // Still computing after the grace window: show a centered spinner.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = GixxerBrand.accent)
                }
            }

            routes.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.Route,
                        body = "No repeated routes yet. Ride the same road a few times to see clusters here.",
                        ctaLabel = null,
                        onCta = null,
                    )
                }
            }

            else -> {
                val trackedRoutes = routes.filter { !it.isUntracked }
                val untrackedRoute = routes.firstOrNull { it.isUntracked }

                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(trackedRoutes) { index, route ->
                        // Pick the first (oldest/chronological) ride in the cluster as the
                        // representative tap target. RouteClustering assigns cluster ids
                        // in chronological order; rideIds are oldest-first within each cluster.
                        val representativeRideId: Long? =
                            clusterRideIds[route.clusterId]?.firstOrNull()

                        RouteCard(
                            route = route,
                            rank = index + 1,
                            representativeRideId = representativeRideId,
                            onOpenRide = onOpenRide,
                        )
                    }

                    if (untrackedRoute != null && untrackedRoute.runCount > 0) {
                        item(key = "untracked") {
                            UntrackedRouteCard(route = untrackedRoute)
                        }
                    }
                }
            }
        }
    }
}

// ── Route card ────────────────────────────────────────────────────────────────

/**
 * Card for one tracked route cluster. Tapping opens the representative ride
 * (the oldest / first ride in the cluster) via [onOpenRide].
 */
@Composable
private fun RouteCard(
    route: RouteStats,
    rank: Int,
    representativeRideId: Long?,
    onOpenRide: (Long) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = representativeRideId != null) {
                representativeRideId?.let(onOpenRide)
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surfaceElevated),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ROUTE $rank",
                        style = MaterialTheme.typography.labelMedium,
                        color = GixxerBrand.accent,
                    )
                    Text(
                        text = "${route.runCount} ${if (route.runCount == 1) "run" else "runs"}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.W600,
                        color = GixxerTokens.textPrimary,
                        fontSize = 28.sp,
                    )
                    if (route.medianDistanceKm != null) {
                        Text(
                            text = "~${route.medianDistanceKm} km median",
                            style = MaterialTheme.typography.bodySmall,
                            color = GixxerTokens.textMuted,
                        )
                    }
                }
                // Total distance badge
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${route.totalDistanceKm} km",
                        style = MaterialTheme.typography.labelSmall,
                        color = GixxerTokens.textMuted,
                    )
                    Text(
                        text = "total",
                        style = MaterialTheme.typography.labelSmall,
                        color = GixxerTokens.textMuted,
                    )
                }
            }

            // Time stats row
            if (route.medianDurationSec != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    DurationStat(label = "Median", valueSec = route.medianDurationSec)
                    if (route.bestDurationSec != null) {
                        DurationStat(
                            label = "Best",
                            valueSec = route.bestDurationSec,
                            color = GixxerTokens.zoneCool,
                        )
                    }
                    if (route.worstDurationSec != null) {
                        DurationStat(
                            label = "Worst",
                            valueSec = route.worstDurationSec,
                            color = GixxerTokens.zoneMid,
                        )
                    }
                }
            }

            // Fuel/cost estimate — always tagged "(est.)" per the no-assumptions rule
            if (route.medianEstCostRs != null || route.medianEstLitres != null) {
                Spacer(modifier = Modifier.height(8.dp))
                val fuelText = buildString {
                    if (route.medianEstLitres != null) {
                        append("~${"%.2f".format(route.medianEstLitres)} L")
                    }
                    if (route.medianEstCostRs != null) {
                        if (isNotEmpty()) append("  ·  ")
                        append("₹${"%.0f".format(route.medianEstCostRs)}")
                    }
                    append(" (est.)")
                }
                Text(
                    text = fuelText,
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
            }

            // Consistency chip — only meaningful for ≥ 2 completed runs
            if (route.durationCvPct != null && route.runCount >= 2) {
                Spacer(modifier = Modifier.height(4.dp))
                val consistencyLabel = when {
                    route.durationCvPct < 15.0 -> "Very consistent"
                    route.durationCvPct < 30.0 -> "Fairly consistent"
                    else -> "Variable"
                }
                Text(
                    text = "$consistencyLabel · ${"%.0f".format(route.durationCvPct)}% CV",
                    style = MaterialTheme.typography.labelSmall,
                    color = GixxerTokens.textMuted,
                )
            }
        }
    }
}

/** Small column showing a duration in mm:ss or h mm format. */
@Composable
private fun DurationStat(
    label: String,
    valueSec: Long,
    color: Color = GixxerTokens.textPrimary,
) {
    val h = valueSec / 3600
    val m = (valueSec % 3600) / 60
    val s = valueSec % 60
    val text = if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.textMuted,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.W600,
            color = color,
        )
    }
}

/** Collapsed card for the untracked cluster (rides with too few GPS points). */
@Composable
private fun UntrackedRouteCard(route: RouteStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Route,
                contentDescription = null,
                tint = GixxerTokens.textMuted,
            )
            Column {
                Text(
                    text = "${route.runCount} rides without GPS tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
                Text(
                    text = "Not enough GPS points to assign a route",
                    style = MaterialTheme.typography.labelSmall,
                    color = GixxerTokens.textMuted,
                )
            }
        }
    }
}
