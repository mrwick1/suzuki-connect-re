package dev.mrwick.redline.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mrwick.redline.analytics.BusiestPeriod
import dev.mrwick.redline.analytics.LongestRide
import dev.mrwick.redline.analytics.TankResult
import dev.mrwick.redline.analytics.TopSpeedEntry
import dev.mrwick.redline.analytics.WrappedResult
import dev.mrwick.redline.export.BitmapShare
import dev.mrwick.redline.ui.components.HeroNumeral
import dev.mrwick.redline.ui.components.rememberSceneCapture
import dev.mrwick.redline.ui.theme.GixxerMono
import dev.mrwick.redline.ui.theme.GixxerTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.TextStyle
import java.util.Locale

// ---------------------------------------------------------------------------
// Page count: summary, longest ride, top speed, busiest period,
//             fuel & tanks, streak.  Total = 6 pages.
// ---------------------------------------------------------------------------
private const val PAGE_COUNT = 6
private const val PAGE_SUMMARY = 0
private const val PAGE_LONGEST = 1
private const val PAGE_TOP_SPEED = 2
private const val PAGE_BUSIEST = 3
private const val PAGE_FUEL = 4
private const val PAGE_STREAK = 5

/**
 * Gixxer Wrapped — full-screen swipeable recap over a selectable time window.
 *
 * Screen contract (matches the plan): `@Composable fun WrappedScreen(vm, onClose)`.
 * MainActivity wires this via a nav route; the ViewModel is supplied externally so
 * a factory can inject the Application. Default [viewModel] call is provided for
 * convenience when launched from composable contexts that already have an
 * Application ambient.
 *
 * Honesty:
 * - Litres burnt is always labelled "~X L (est.)" — never presented as measured.
 * - Spend is labelled with partial-coverage disclosure when not all fills have prices.
 * - Best/worst tank only renders when ≥ 2 fills exist; otherwise an explicit
 *   "Log 2+ fills to see your best tank" prompt is shown.
 * - If the window has no rides, an empty state is shown and Share is disabled.
 */
@Composable
fun WrappedScreen(
    vm: WrappedViewModel = viewModel(
        factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(
            LocalContext.current.applicationContext as android.app.Application,
        ),
    ),
    onClose: () -> Unit,
) {
    val recap by vm.recap.collectAsStateWithLifecycle()
    val preset by vm.preset.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scene = rememberSceneCapture()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GixxerTokens.bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ---- Window preset chips (top bar) --------------------------------
            WindowChipRow(
                selected = preset,
                onSelect = { vm.setPreset(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // ---- Content: empty state OR pager --------------------------------
            if (recap == null) {
                EmptyState(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            } else {
                val r = recap!!
                val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(scene.modifier), // scene capture wraps the full pager
                ) { page ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (page) {
                            PAGE_SUMMARY -> SummaryCard(r)
                            PAGE_LONGEST -> LongestRideCard(r.longestRide)
                            PAGE_TOP_SPEED -> TopSpeedCard(r.topSpeed)
                            PAGE_BUSIEST -> BusiestCard(r.busiestMonth, r.busiestWeekday)
                            PAGE_FUEL -> FuelCard(r)
                            PAGE_STREAK -> StreakCard(r.longestStreak)
                            else -> Spacer(Modifier)
                        }
                    }
                }

                // ---- Page indicator dots ------------------------------------
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    // watermark — appears below pager, before dots
                    // (also repeated inside the captured scene via SummaryCard footer)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    repeat(PAGE_COUNT) { idx ->
                        Box(
                            modifier = Modifier
                                .size(if (pagerState.currentPage == idx) 10.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (pagerState.currentPage == idx) GixxerTokens.accent
                                    else GixxerTokens.textMuted.copy(alpha = 0.4f),
                                ),
                        )
                    }
                }

                // ---- Share button -------------------------------------------
                Button(
                    onClick = {
                        scope.launch {
                            val img = scene.capture()
                            withContext(Dispatchers.IO) {
                                BitmapShare.shareImageBitmap(
                                    context = context,
                                    image = img,
                                    fileName = "wrapped-${preset.name.lowercase()}.png",
                                    chooserTitle = "Share Gixxer Wrapped",
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = GixxerTokens.accent,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        tint = GixxerTokens.bg,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Share", color = GixxerTokens.bg, fontWeight = FontWeight.W700)
                }
            }
        }

        // ---- Swipe hint + close button (overlaid, always visible) ----------
        Text(
            text = if (recap != null) "Swipe to explore" else "",
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.textMuted,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp), // below the chips
        )
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Close",
                tint = GixxerTokens.textMuted,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Window chip row
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowChipRow(
    selected: WrappedPreset,
    onSelect: (WrappedPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WrappedPreset.entries.forEach { p ->
            FilterChip(
                selected = p == selected,
                onClick = { onSelect(p) },
                label = {
                    Text(
                        p.label,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = GixxerTokens.accent,
                    selectedLabelColor = GixxerTokens.bg,
                    containerColor = GixxerTokens.surface,
                    labelColor = GixxerTokens.textMuted,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Empty state
// ---------------------------------------------------------------------------

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                "No rides in this window",
                style = MaterialTheme.typography.titleMedium,
                color = GixxerTokens.textPrimary,
                textAlign = TextAlign.Center,
            )
            Text(
                "Select a different window\nor go for a ride.",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Card 0: Summary — total km + ride count + saddle hours + brand watermark
// ---------------------------------------------------------------------------

@Composable
private fun SummaryCard(r: WrappedResult) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val yearLabel = buildString {
            val start = r.window.startInclusive
            val end = r.window.endInclusive
            if (start.year == end.year) append(start.year.toString())
            else append("${start.year}–${end.year}")
        }
        Text(
            text = yearLabel,
            style = GixxerMono.label,
            color = GixxerTokens.textMuted,
            letterSpacing = 2.sp,
        )
        HeroNumeral(
            text = "${r.totalKm}",
            fontSize = 120.sp,
            color = GixxerTokens.accent,
        )
        Text(
            "km ridden",
            style = MaterialTheme.typography.headlineSmall,
            color = GixxerTokens.textPrimary,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatPair(label = "RIDES", value = "${r.rideCount}")
            StatPair(
                label = "IN THE SADDLE",
                value = "${"%.1f".format(r.saddleHours)} h",
            )
        }
        Spacer(Modifier.height(16.dp))
        // Brand watermark — always visible on the summary (shareable) card.
        Text(
            "• REDLINE",
            style = GixxerMono.label,
            color = GixxerTokens.textMuted.copy(alpha = 0.5f),
            letterSpacing = 3.sp,
        )
    }
}

// ---------------------------------------------------------------------------
// Card 1: Longest ride
// ---------------------------------------------------------------------------

@Composable
private fun LongestRideCard(ride: LongestRide?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CardLabel("LONGEST RIDE")
        if (ride != null) {
            HeroNumeral(
                text = "${ride.km}",
                fontSize = 120.sp,
                color = GixxerTokens.accent,
            )
            Text("km", style = MaterialTheme.typography.headlineSmall, color = GixxerTokens.textPrimary)
            val dateStr = ride.date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
            Text(dateStr, style = GixxerMono.body, color = GixxerTokens.textMuted)
            if (!ride.name.isNullOrBlank()) {
                Text(
                    ride.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GixxerTokens.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            NoDataText()
        }
    }
}

// ---------------------------------------------------------------------------
// Card 2: Top speed
// ---------------------------------------------------------------------------

@Composable
private fun TopSpeedCard(entry: TopSpeedEntry?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CardLabel("TOP SPEED")
        if (entry != null) {
            HeroNumeral(
                text = "${entry.speedKmh}",
                fontSize = 120.sp,
                color = GixxerTokens.zoneHot,
            )
            Text("km/h", style = MaterialTheme.typography.headlineSmall, color = GixxerTokens.textPrimary)
            val dateStr = entry.date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
            Text(dateStr, style = GixxerMono.body, color = GixxerTokens.textMuted)
            if (!entry.rideName.isNullOrBlank()) {
                Text(
                    entry.rideName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = GixxerTokens.textMuted,
                    textAlign = TextAlign.Center,
                )
            }
            // Top speed is from the ECU (a537) — a genuine measured value, not an estimate.
            Text(
                "genuine ECU value",
                style = MaterialTheme.typography.labelSmall,
                color = GixxerTokens.textMuted.copy(alpha = 0.5f),
            )
        } else {
            NoDataText()
        }
    }
}

// ---------------------------------------------------------------------------
// Card 3: Busiest month + weekday
// ---------------------------------------------------------------------------

@Composable
private fun BusiestCard(month: BusiestPeriod?, weekday: BusiestPeriod?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CardLabel("YOUR BUSIEST")
        if (month != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Month", style = MaterialTheme.typography.labelLarge, color = GixxerTokens.textMuted)
                Text(
                    formatMonthLabel(month.label),
                    style = MaterialTheme.typography.displaySmall,
                    color = GixxerTokens.accent,
                )
                Text("${month.totalKm} km", style = GixxerMono.body, color = GixxerTokens.textMuted)
            }
        }
        if (weekday != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Day", style = MaterialTheme.typography.labelLarge, color = GixxerTokens.textMuted)
                Text(
                    weekday.label, // already "Mon"…"Sun" from WrappedAnalytics
                    style = MaterialTheme.typography.displaySmall,
                    color = GixxerTokens.accent,
                )
                Text("${weekday.totalKm} km", style = GixxerMono.body, color = GixxerTokens.textMuted)
            }
        }
        if (month == null && weekday == null) {
            NoDataText()
        }
    }
}

/**
 * Convert a "yyyy-MM" label from [BusiestPeriod] to a human-readable string like
 * "June 2025". Falls back to the raw label if parsing fails.
 */
private fun formatMonthLabel(label: String): String {
    return try {
        val ym = java.time.YearMonth.parse(label)
        "${ym.month.getDisplayName(TextStyle.FULL, Locale.US)} ${ym.year}"
    } catch (_: Exception) {
        label
    }
}

// ---------------------------------------------------------------------------
// Card 4: Fuel — litres (est.) + best/worst tank
// ---------------------------------------------------------------------------

@Composable
private fun FuelCard(r: WrappedResult) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CardLabel("FUEL")

        // Litres burnt — ALWAYS labelled as an estimate; never presented as measured.
        val litres = r.litresBurnt
        if (litres != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                HeroNumeral(
                    text = "~${"%.1f".format(litres.litres)}",
                    fontSize = 80.sp,
                    color = GixxerTokens.zoneMid,
                )
                Text(
                    "L (est.)",
                    style = MaterialTheme.typography.headlineSmall,
                    color = GixxerTokens.textPrimary,
                )
                Text(
                    "estimated from fill-ledger avg km/L",
                    style = MaterialTheme.typography.labelSmall,
                    color = GixxerTokens.textMuted.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            Text(
                "Not enough fill data to estimate litres",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
                textAlign = TextAlign.Center,
            )
        }

        // Best / worst tank — null when < 2 fills in window.
        val best = r.bestTank
        val worst = r.worstTank
        if (best != null && worst != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TankStat(label = "BEST TANK", tank = best)
                TankStat(label = "WORST TANK", tank = worst)
            }
        } else {
            Text(
                "Log 2+ fills to see your best tank",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
                textAlign = TextAlign.Center,
            )
        }

        // Spend — with partial-coverage disclosure when applicable.
        val spend = r.totalSpend
        if (spend.totalFills > 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "₹${"%.0f".format(spend.rupees)}",
                    style = GixxerMono.headline,
                    color = GixxerTokens.textPrimary,
                )
                if (!spend.isComplete) {
                    Text(
                        "covers ${spend.coveredFills} of ${spend.totalFills} fills",
                        style = MaterialTheme.typography.labelSmall,
                        color = GixxerTokens.textMuted.copy(alpha = 0.6f),
                    )
                } else {
                    Text(
                        "fuel spend",
                        style = MaterialTheme.typography.labelSmall,
                        color = GixxerTokens.textMuted.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
private fun TankStat(label: String, tank: TankResult) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GixxerTokens.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = GixxerTokens.textMuted)
        Text(
            "${"%.1f".format(tank.kmPerL)} km/L",
            style = GixxerMono.headline,
            color = GixxerTokens.textPrimary,
        )
    }
}

// ---------------------------------------------------------------------------
// Card 5: Streak
// ---------------------------------------------------------------------------

@Composable
private fun StreakCard(longestStreak: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        CardLabel("LONGEST STREAK")
        HeroNumeral(
            text = "$longestStreak",
            fontSize = 120.sp,
            color = GixxerTokens.zoneCool,
        )
        Text(
            if (longestStreak == 1) "day in a row" else "days in a row",
            style = MaterialTheme.typography.headlineSmall,
            color = GixxerTokens.textPrimary,
        )
        if (longestStreak == 0) {
            Text(
                "No consecutive riding days in this window.",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

@Composable
private fun CardLabel(text: String) {
    Text(
        text = text,
        style = GixxerMono.label,
        color = GixxerTokens.textMuted,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun StatPair(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = GixxerTokens.textMuted)
        Text(value, style = GixxerMono.headline, color = GixxerTokens.textPrimary)
    }
}

@Composable
private fun NoDataText() {
    Text(
        "Not enough data in this window",
        style = MaterialTheme.typography.bodyMedium,
        color = GixxerTokens.textMuted,
        textAlign = TextAlign.Center,
    )
}
