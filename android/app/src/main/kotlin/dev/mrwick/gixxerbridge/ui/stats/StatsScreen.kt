package dev.mrwick.gixxerbridge.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
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
import dev.mrwick.gixxerbridge.ui.components.TraceChart
import dev.mrwick.gixxerbridge.ui.cost.CostDetailScreen
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
    onOpenWrapped: () -> Unit = {},
) {
    val rides by vm.rides.collectAsStateWithLifecycle()
    val recentSamples by vm.recentSamples.collectAsStateWithLifecycle()
    val lastNSamples by vm.lastNSamples.collectAsStateWithLifecycle()
    val bestFuelEcon by vm.bestFuelEcon.collectAsStateWithLifecycle()
    val costStats by vm.costStats.collectAsStateWithLifecycle()
    val runningCost by vm.runningCost.collectAsStateWithLifecycle()
    val monthlySpend by vm.monthlySpend.collectAsStateWithLifecycle()

    val weekly = remember(rides) { RideAnalytics.totalsFor(rides, days = 7) }
    val monthly = remember(rides) { RideAnalytics.totalsFor(rides, days = 30) }
    val yearly = remember(rides) { RideAnalytics.totalsFor(rides, days = 365) }
    val histogram = remember(recentSamples) { RideAnalytics.speedHistogram(recentSamples) }
    val calendar = remember(rides) { RideAnalytics.calendarMap(rides) }
    val bests = remember(rides, recentSamples, bestFuelEcon) {
        // bestFuelEconKml from personalBests only sees the 30-day sample window;
        // override it with the lifetime MAX() aggregate when available.
        val base = RideAnalytics.personalBests(rides, recentSamples)
        base.copy(bestFuelEconKml = bestFuelEcon ?: base.bestFuelEconKml)
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
    val avgPerRideKm = if (lifetime.rides > 0) lifetime.km.toDouble() / lifetime.rides else 0.0
    val lifetimeAvgSpeed = remember(rides) {
        if (rides.isEmpty()) 0.0 else rides.map { it.avgSpeedKmh }.average()
    }
    val weekdayKm = remember(rides) { RideAnalytics.weekdayKm(rides) }
    val monthlyKm = remember(rides) { RideAnalytics.monthlyKm(rides, months = 6) }
    val timeOfDayKm = remember(rides) { RideAnalytics.timeOfDayKm(rides) }
    val currentOdo = remember(rides) { rides.maxOfOrNull { it.endOdoKm ?: it.startOdoKm } ?: 0 }
    val cruiseKmh = remember(histogram) {
        histogram.filter { it.sampleCount > 0 }.maxByOrNull { it.sampleCount }
            ?.let { (it.lowKmh + it.highKmh) / 2 } ?: 0
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

    // The Stats tab is a single-screen bento dashboard, not an endless scroll.
    // Tapping a tile drills into a focused detail view (the heavy charts live
    // there). `detail == null` shows the dashboard.
    var detail by remember { mutableStateOf<StatsDetail?>(null) }

    val activeDetail = detail
    if (activeDetail != null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DetailHeader(activeDetail.title) { detail = null }
            when (activeDetail) {
                StatsDetail.TRENDS -> {
                    TotalsRow(weekly, monthly, yearly)
                    LineChart(
                        values = monthlyKm.map { it.km.toFloat() },
                        label = "Distance by month (km) — last 6 months",
                        lineColor = GixxerTokens.accent,
                        yLabelFormatter = { "${it.toInt()} km" },
                    )
                    val fuelValues = recent10.asReversed().map { it.fuelEconKml?.toFloat() ?: 0f }
                    LineChart(
                        values = fuelValues,
                        label = "Fuel economy (km/L) — last ${recent10.size} rides",
                        lineColor = GixxerTokens.accent,
                        yLabelFormatter = { "%.1f km/L".format(it) },
                    )
                    WeekdayBars(weekdayKm)
                    CalendarHeatmap(days = calendar)
                }
                StatsDetail.SPEED -> {
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
                }
                StatsDetail.INSIGHTS ->
                    StatsInsights(lifetime, timeOfDayKm, cruiseKmh, bests.topSpeedKmh ?: 0, currentOdo)
                StatsDetail.RECORDS -> {
                    StatsOverview(lifetime, streak, bests, avgPerRideKm, lifetimeAvgSpeed, monthRides)
                    PersonalBestsCard(bests)
                }
                StatsDetail.COSTS ->
                    CostDetailScreen(
                        costStats = costStats,
                        runningCost = runningCost,
                        monthlySpend = monthlySpend,
                    )
            }
        }
        return
    }

    val aroundWorldPct = lifetime.km / 40_075.0 * 100
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "STATS",
            style = MaterialTheme.typography.labelMedium,
            color = GixxerBrand.accent,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        // Hero: lifetime distance.
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

        // Quick vitals.
        Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCell("THIS YEAR", "${yearly.km}", "km", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f).fillMaxHeight())
            StatCell("STREAK", "${streak.current}", "days", GixxerBrand.accent, Modifier.weight(1f).fillMaxHeight())
            StatCell("AVG / RIDE", "%.1f".format(avgPerRideKm), "km", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f).fillMaxHeight())
        }

        // Distance trend — tap for the full charts.
        BentoTile(Modifier.fillMaxWidth(), animateEntry = false, onClick = { detail = StatsDetail.TRENDS }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InsightHeader(Icons.Outlined.TrendingUp, "DISTANCE TREND")
                Text("LAST 6 MO ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            MiniSparkline(monthlyKm.map { it.km })
        }

        // Speed + For-fun — both drill in.
        Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BentoTile(Modifier.weight(1f).fillMaxHeight(), animateEntry = false, onClick = { detail = StatsDetail.SPEED }) {
                InsightHeader(Icons.Outlined.Speed, "SPEED")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    HeroNumeral("${bests.topSpeedKmh ?: 0}", color = GixxerBrand.zoneHot, fontSize = 40.sp)
                    Spacer(Modifier.size(3.dp))
                    Text("km/h", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text("cruise $cruiseKmh · tap to explore", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BentoTile(Modifier.weight(1f).fillMaxHeight(), animateEntry = false, onClick = { detail = StatsDetail.INSIGHTS }) {
                InsightHeader(Icons.Outlined.Public, "FOR FUN")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    HeroNumeral("%.1f".format(aroundWorldPct), color = GixxerBrand.accent, fontSize = 40.sp)
                    Spacer(Modifier.size(3.dp))
                    Text("%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp))
                }
                Text("around the world · tap ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Records — tap for the full vitals + personal bests.
        BentoTile(Modifier.fillMaxWidth(), animateEntry = false, onClick = { detail = StatsDetail.RECORDS }) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InsightHeader(Icons.Outlined.EmojiEvents, "RECORDS")
                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MiniRecord("LONGEST", bests.longestRideKm?.toString() ?: "—", "km", GixxerBrand.accent, Modifier.weight(1f))
                MiniRecord("TOP", bests.topSpeedKmh?.toString() ?: "—", "km/h", GixxerBrand.zoneHot, Modifier.weight(1f))
                MiniRecord("BEST KM/L", bests.bestFuelEconKml?.let { "%.0f".format(it) } ?: "—", "km/L", GixxerBrand.zoneCool, Modifier.weight(1f))
            }
        }

        // Costs + Wrapped — side-by-side half-width tiles.
        Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Costs tile: ₹/km hero, drills into unified cost view.
            BentoTile(Modifier.weight(1f).fillMaxHeight(), animateEntry = false, onClick = { detail = StatsDetail.COSTS }) {
                InsightHeader(Icons.Outlined.CurrencyRupee, "COSTS")
                Spacer(Modifier.height(8.dp))
                if (costStats != null) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("₹", style = GixxerMono.body.copy(fontSize = 14.sp), color = GixxerBrand.accent, modifier = Modifier.padding(bottom = 6.dp))
                        HeroNumeral("%.2f".format(costStats!!.rollingRupeesPerKm), color = GixxerBrand.accent, fontSize = 32.sp)
                    }
                    Text("/km · tap to explore", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("No cost data yet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("tap to explore ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Wrapped tile: entry point for Gixxer Wrapped.
            BentoTile(Modifier.weight(1f).fillMaxHeight(), animateEntry = false, onClick = onOpenWrapped) {
                InsightHeader(Icons.Outlined.CalendarMonth, "YOUR YEAR")
                Spacer(Modifier.height(8.dp))
                Text(
                    "YOUR YEAR ›",
                    style = MaterialTheme.typography.labelLarge,
                    color = GixxerBrand.accent,
                )
                Text(
                    "Gixxer Wrapped",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        OutlinedButton(
            onClick = onOpenMileage,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add fuel fill / view true mileage") }
    }
}

/** The drill-in sections reachable from the dashboard tiles. */
private enum class StatsDetail(val title: String) {
    TRENDS("DISTANCE TRENDS"),
    SPEED("SPEED"),
    INSIGHTS("FOR FUN"),
    RECORDS("RECORDS"),
    COSTS("COSTS"),
}

@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Back", tint = MaterialTheme.colorScheme.onBackground)
        }
        Spacer(Modifier.width(4.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Compact, label-free distance sparkline for the dashboard trend tile. */
@Composable
private fun MiniSparkline(values: List<Int>) {
    val max = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    val points = if (values.size >= 2) values.map { it.toFloat() / max } else listOf(0f, 0f)
    TraceChart(
        points = points,
        animateDraw = true,
        strokeWidth = 3.dp,
        modifier = Modifier.fillMaxWidth().height(56.dp),
    )
}

/** One record figure inside the dashboard RECORDS tile (no nested tile background). */
@Composable
private fun MiniRecord(
    label: String,
    value: String,
    unit: String,
    color: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = GixxerMono.headline.copy(fontSize = 22.sp), color = color)
            Spacer(Modifier.size(2.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/** Data-rich vital-stats overview: lifetime, streaks, averages, personal bests. */
@Composable
fun StatsOverview(
    lifetime: WeeklyTotal,
    streak: StreakInfo,
    bests: PersonalBests,
    avgPerRideKm: Double,
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
            StatCell("AVG / RIDE", "%.1f".format(avgPerRideKm), "km", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f))
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

/**
 * Playful "for fun" insights derived from real ride data — distances reframed as
 * around-the-world %, fuel ₹/CO₂, saddle time, time-of-day rhythm, cruise speed,
 * top-speed-vs-claimed, route equivalents, odometer milestone. Whimsical figures
 * (45 km/L, ₹100/L) are estimates, shown with "~".
 */
@Composable
private fun StatsInsights(
    lifetime: WeeklyTotal,
    timeOfDayKm: List<Int>,
    cruiseKmh: Int,
    topSpeedKmh: Int,
    currentOdoKm: Int,
) {
    val km = lifetime.km
    val litres = km / 45.0
    val cost = (litres * 100).toInt()
    // Display litres/CO₂ with one decimal so they stay consistent with the ₹ cost:
    // e.g. 6 km → 0.1 L burned must not read as "0 L" next to "₹13".
    val litresStr = "%.1f".format(litres)
    val co2Str = "%.1f".format(litres * 2.31)
    val aroundWorld = km / 40_075.0
    val toMumbaiDelhi = km / 1400.0
    val saddleDays = (lifetime.hours / 24).toInt()
    val saddleHrs = (lifetime.hours % 24).toInt()
    val nextMilestone = ((currentOdoKm / 1000) + 1) * 1000
    val milestoneProgress = (currentOdoKm % 1000) / 1000f
    val overClaimed = topSpeedKmh - 110

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("INSIGHTS · FOR FUN", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)

        BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
            InsightHeader(Icons.Outlined.Public, "AROUND THE WORLD")
            Row(verticalAlignment = Alignment.Bottom) {
                HeroNumeral("%.1f".format(aroundWorld * 100), color = MaterialTheme.colorScheme.onBackground, fontSize = 56.sp)
                Text("%", style = MaterialTheme.typography.titleLarge, color = GixxerBrand.accent, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            InsightProgress(aroundWorld.toFloat())
            Spacer(Modifier.height(6.dp))
            Text("$km km of Earth's 40,075 km lap", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InsightCell(Icons.Outlined.Schedule, "SADDLE TIME", "${saddleDays}d ${saddleHrs}h", "on the bike", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f).fillMaxHeight())
            InsightCell(Icons.Outlined.LocalGasStation, "FUEL BURNED", "~$litresStr L", "≈ ₹$cost", GixxerBrand.zoneMid, Modifier.weight(1f).fillMaxHeight())
        }

        TimeOfDayRow(timeOfDayKm)

        Row(modifier = Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InsightCell(Icons.Outlined.Speed, "YOU CRUISE AT", "$cruiseKmh", "km/h most-ridden", MaterialTheme.colorScheme.onBackground, Modifier.weight(1f).fillMaxHeight())
            InsightCell(Icons.Outlined.SportsScore, "TOP SPEED", "$topSpeedKmh", if (overClaimed > 0) "+$overClaimed over claimed!" else "km/h", GixxerBrand.zoneHot, Modifier.weight(1f).fillMaxHeight())
        }

        BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
            InsightHeader(Icons.Outlined.AltRoute, "THAT'S LIKE RIDING")
            Row(verticalAlignment = Alignment.Bottom) {
                HeroNumeral("%.1f".format(toMumbaiDelhi), color = GixxerBrand.accent, fontSize = 48.sp)
                Text("× Mumbai → Delhi", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            }
            Text("~$co2Str kg CO₂ released along the way", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
            InsightHeader(Icons.Outlined.Flag, "NEXT MILESTONE")
            Spacer(Modifier.height(6.dp))
            Text("$currentOdoKm → $nextMilestone km", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(8.dp))
            InsightProgress(milestoneProgress)
        }
    }
}

@Composable
private fun InsightProgress(fraction: Float) {
    Box(
        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(GixxerBrand.accent))
    }
}

/** Section label for an insight tile: a brand-tinted icon followed by an all-caps title. */
@Composable
private fun InsightHeader(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = GixxerBrand.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InsightCell(
    icon: ImageVector,
    label: String,
    value: String,
    sub: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
) {
    BentoTile(modifier.defaultMinSize(minHeight = 112.dp), animateEntry = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = GixxerBrand.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Text(value, style = GixxerMono.headline, color = valueColor)
        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimeOfDayRow(timeOfDayKm: List<Int>) {
    val maxKm = (timeOfDayKm.maxOrNull() ?: 0).coerceAtLeast(1)
    val items = listOf(
        Icons.Outlined.WbTwilight to "MORN",
        Icons.Outlined.WbSunny to "NOON",
        Icons.Outlined.Brightness4 to "EVE",
        Icons.Outlined.DarkMode to "NIGHT",
    )
    BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
        InsightHeader(Icons.Outlined.Schedule, "WHEN YOU RIDE · TIME OF DAY")
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            timeOfDayKm.forEachIndexed { i, kmv ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Icon(items[i].first, contentDescription = items[i].second, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier.fillMaxWidth()
                            .height((6f + (kmv.toFloat() / maxKm) * 52f).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (kmv > 0) GixxerBrand.accent else MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(items[i].second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "When you ride" — total km per weekday as a mini bar row (Mon→Sun). */
@Composable
private fun WeekdayBars(weekdayKm: List<Int>) {
    val maxKm = (weekdayKm.maxOrNull() ?: 0).coerceAtLeast(1)
    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
    BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
        Text(
            "WHEN YOU RIDE · KM BY WEEKDAY",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.fillMaxWidth().height(110.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            weekdayKm.forEachIndexed { i, km ->
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((6f + (km.toFloat() / maxKm) * 76f).dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (km > 0) GixxerBrand.accent else MaterialTheme.colorScheme.surfaceVariant),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(labels[i], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
    BentoTile(modifier, animateEntry = false, contentPadding = PaddingValues(12.dp)) {
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

/** Personal-bests grid. Three primary tiles + one secondary. */
@Composable
private fun PersonalBestsCard(b: PersonalBests) {
    BentoTile(Modifier.fillMaxWidth(), animateEntry = false, contentPadding = PaddingValues(12.dp)) {
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
