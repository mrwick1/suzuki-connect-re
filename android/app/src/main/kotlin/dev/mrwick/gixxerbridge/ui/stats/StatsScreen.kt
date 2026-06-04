package dev.mrwick.gixxerbridge.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.analytics.RideStreak
import dev.mrwick.gixxerbridge.analytics.StreakInfo
import dev.mrwick.gixxerbridge.ui.components.BentoTile
import dev.mrwick.gixxerbridge.ui.components.HeroNumeral
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.analytics.PersonalBests
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.analytics.WeeklyTotal
import dev.mrwick.gixxerbridge.ui.components.SkeletonBlock
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import dev.mrwick.gixxerbridge.ui.home.components.EmptyState
import dev.mrwick.gixxerbridge.ui.stats.charts.BarChart
import dev.mrwick.gixxerbridge.ui.stats.charts.CalendarHeatmap
import dev.mrwick.gixxerbridge.ui.stats.charts.HistogramChart
import dev.mrwick.gixxerbridge.ui.stats.charts.LineChart
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Analytics tab. Stacked vertically inside a scroll container:
 *   1. Three totals cards (week / month / year)
 *   2. Calendar heatmap (last 12 weeks)
 *   3. Fuel-economy line (last 10 rides)
 *   4. Avg-vs-max speed bar (last 10 rides)
 *   5. Speed-distribution histogram (last 30 days of samples)
 *   6. Personal bests card
 *
 * All derived data is wrapped in `remember(rides, samples)` so we only
 * re-compute on actual data changes, not on every recomposition.
 *
 * Chart colors:
 *   - Primary line/bar: GixxerTokens.accent
 *   - Grid lines/baseline: GixxerTokens.surfaceElevated
 *   - Axis labels: GixxerTokens.textMuted
 */
@Composable
fun StatsScreen(
    vm: StatsViewModel,
    onOpenSettings: () -> Unit = {},
    onOpenMileage: () -> Unit = {},
) {
    val rides by vm.rides.collectAsStateWithLifecycle()
    val recentSamples by vm.recentSamples.collectAsStateWithLifecycle()
    val lastNSamples by vm.lastNSamples.collectAsStateWithLifecycle()

    val weekly = remember(rides) { RideAnalytics.totalsFor(rides, days = 7) }
    val monthly = remember(rides) { RideAnalytics.totalsFor(rides, days = 30) }
    val yearly = remember(rides) { RideAnalytics.totalsFor(rides, days = 365) }
    val histogram = remember(recentSamples) { RideAnalytics.speedHistogram(recentSamples) }
    val calendar = remember(rides) { RideAnalytics.calendarMap(rides) }
    val bests = remember(rides, recentSamples) {
        RideAnalytics.personalBests(rides, recentSamples)
    }
    val recent10 = remember(rides, lastNSamples) {
        val byRide = lastNSamples.groupBy { it.rideId }
        rides.take(StatsViewModel.LAST_N).map { r ->
            RideAnalytics.summarize(r, byRide[r.id].orEmpty())
        }
    }
    val lifetime = remember(rides) { RideAnalytics.totalsFor(rides, days = 36_500) }
    val streak = remember(rides) { RideStreak.compute(rides) }
    val monthRides = monthly.rides
    val avgPerRideKm = if (lifetime.rides > 0) lifetime.km / lifetime.rides else 0
    val lifetimeAvgSpeed = remember(rides) {
        if (rides.isEmpty()) 0.0 else rides.map { it.avgSpeedKmh }.average()
    }

    // Distinguish initial-load from genuinely-empty: show skeletons during a
    // short grace window after composition. Real data within the window
    // dismisses skeletons immediately.
    val bootDone by produceState(initialValue = false) {
        delay(250); value = true
    }
    if (rides.isEmpty() && !bootDone) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkeletonCard(modifier = Modifier.weight(1f))
                SkeletonCard(modifier = Modifier.weight(1f))
                SkeletonCard(modifier = Modifier.weight(1f))
            }
            SkeletonBlock(height = 120.dp)
            SkeletonBlock(height = 160.dp)
            SkeletonBlock(height = 160.dp)
            SkeletonBlock(height = 160.dp)
            SkeletonCard()
        }
        return
    }
    if (rides.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.BarChart,
                body = "No ride data yet. Take a ride to see your stats.",
                ctaLabel = null,
                onCta = null,
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp)) {
            Text("STATS · THIS YEAR", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
            HeroNumeral(text = "${yearly.km}", color = MaterialTheme.colorScheme.onBackground, fontSize = 64.sp)
            Text("KM RIDDEN", style = MaterialTheme.typography.labelMedium, color = GixxerTokens.textMuted)
        }
        StatsOverview(lifetime, streak, bests, avgPerRideKm, lifetimeAvgSpeed, monthRides)
        TotalsRow(weekly, monthly, yearly)
        CalendarHeatmap(days = calendar)

        // Fuel econ chart: drop zeros so a flat line of nulls doesn't dominate the y-axis.
        val fuelValues = recent10.asReversed().map { it.fuelEconKml?.toFloat() ?: 0f }
        LineChart(
            values = fuelValues,
            label = "Fuel economy (km/L) — last ${recent10.size} rides",
            lineColor = GixxerTokens.accent,
            yLabelFormatter = { "%.1f km/L".format(it) },
        )

        val labels = recent10.asReversed().map { shortDate(it.date) }
        BarChart(
            seriesA = recent10.asReversed().map { it.maxSpeed.toFloat() },
            seriesB = recent10.asReversed().map { it.avgSpeed.toFloat() },
            labels = labels,
            title = "Avg vs Max speed (km/h) — last ${recent10.size} rides",
            colorA = GixxerTokens.surfaceElevated,
            colorB = GixxerTokens.accent,
        )

        HistogramChart(
            buckets = histogram,
            title = "Speed distribution (km/h) — last 30 days",
        )

        PersonalBestsCard(bests)

        OutlinedButton(
            onClick = onOpenMileage,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add fuel fill / view true mileage") }
    }
}

/** Data-rich vital-stats overview: lifetime, streaks, averages, personal bests. */
@Composable
fun StatsOverview(
    lifetime: WeeklyTotal,
    streak: StreakInfo,
    bests: PersonalBests,
    avgPerRideKm: Int,
    lifetimeAvgSpeed: Double,
    ridesThisMonth: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
            Text("LIFETIME", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
            Row(verticalAlignment = Alignment.Bottom) {
                HeroNumeral("${lifetime.km}", color = MaterialTheme.colorScheme.onBackground, fontSize = 64.sp)
                Spacer(Modifier.size(6.dp))
                Text(
                    "KM",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            Text(
                "${lifetime.rides} rides · ${"%.0f".format(lifetime.hours)} h in the saddle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell("CURRENT STREAK", "${streak.current}", "days", GixxerBrand.accent, Modifier.weight(1f))
            StatCell("BEST STREAK", "${streak.longest}", "days", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell("AVG / RIDE", "$avgPerRideKm", "km", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f))
            StatCell("AVG SPEED", "%.0f".format(lifetimeAvgSpeed), "km/h", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f))
            StatCell("THIS MONTH", "$ridesThisMonth", "rides", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell("LONGEST", bests.longestRideKm?.toString() ?: "—", "km", GixxerBrand.accent, Modifier.weight(1f))
            StatCell("TOP SPEED", bests.topSpeedKmh?.toString() ?: "—", "km/h", GixxerBrand.zoneHot, Modifier.weight(1f))
            StatCell("BEST KM/L", bests.bestFuelEconKml?.let { "%.0f".format(it) } ?: "—", "km/L", GixxerBrand.zoneCool, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    unit: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    BentoTile(modifier.height(92.dp), animateEntry = false) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = GixxerMono.headline, color = valueColor)
            Spacer(Modifier.size(3.dp))
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** Three side-by-side totals cards: week, month, year. */
@Composable
private fun TotalsRow(weekly: WeeklyTotal, monthly: WeeklyTotal, yearly: WeeklyTotal) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TotalCard("Week", weekly, GixxerTokens.accent, Modifier.weight(1f))
        TotalCard("Month", monthly, GixxerTokens.warning, Modifier.weight(1f))
        TotalCard("Year", yearly, GixxerTokens.success, Modifier.weight(1f))
    }
}

/** One totals card. Big km, then a faint "X rides · Y h" subtitle. */
@Composable
private fun TotalCard(
    label: String,
    t: WeeklyTotal,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(width = 4.dp, height = 14.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor),
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = GixxerTokens.textMuted,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "${t.km}",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = GixxerTokens.textPrimary,
            )
            Text(
                "km",
                style = MaterialTheme.typography.labelSmall,
                color = GixxerTokens.textMuted,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${t.rides} ride${if (t.rides == 1) "" else "s"} · ${"%.1f".format(t.hours)} h",
                style = MaterialTheme.typography.labelSmall,
                color = GixxerTokens.textMuted,
            )
        }
    }
}

/** Personal-bests grid. Three primary tiles + one secondary. */
@Composable
private fun PersonalBestsCard(b: PersonalBests) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Personal bests",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = GixxerTokens.textPrimary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BestTile(
                    label = "Longest",
                    value = b.longestRideKm?.let { "$it km" } ?: "—",
                    accentColor = GixxerTokens.accent,
                    modifier = Modifier.weight(1f),
                )
                BestTile(
                    label = "Top speed",
                    value = b.topSpeedKmh?.let { "$it km/h" } ?: "—",
                    accentColor = GixxerTokens.danger,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BestTile(
                    label = "Best fuel econ",
                    value = b.bestFuelEconKml?.let { "%.1f km/L".format(it) } ?: "—",
                    accentColor = GixxerTokens.success,
                    modifier = Modifier.weight(1f),
                )
                BestTile(
                    label = "Most in a day",
                    value = b.mostRidesInDay?.let { "$it rides" } ?: "—",
                    accentColor = GixxerTokens.warning,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One PB tile. Small accent stripe + value + caption. */
@Composable
private fun BestTile(
    label: String,
    value: String,
    accentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.18f),
                        androidx.compose.ui.graphics.Color.Transparent,
                    ),
                ),
            )
            .padding(12.dp),
    ) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = GixxerTokens.textMuted,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = GixxerTokens.textPrimary,
            )
        }
    }
}

private fun shortDate(epoch: Long): String =
    SimpleDateFormat("d/M", Locale.US).format(Date(epoch))
