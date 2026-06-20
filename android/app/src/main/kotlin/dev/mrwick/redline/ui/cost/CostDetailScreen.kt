package dev.mrwick.redline.ui.cost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CurrencyRupee
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mrwick.redline.analytics.CostStats
import dev.mrwick.redline.analytics.MonthSpend
import dev.mrwick.redline.analytics.RunningCost
import dev.mrwick.redline.ui.components.BentoTile
import dev.mrwick.redline.ui.components.HeroNumeral
import dev.mrwick.redline.ui.theme.GixxerBrand
import dev.mrwick.redline.ui.theme.GixxerMono
import dev.mrwick.redline.ui.theme.GixxerTokens

/**
 * Unified "Costs" drill-in for the Stats tab.
 *
 * Shows two sections:
 *   1. Fuel ₹/km (from CostAnalytics): rolling hero + lifetime sub + coverage note.
 *   2. Running cost (from RunningCostAnalytics): blended ₹/km hero, fuel-vs-service
 *      split bar, and monthly spend history for the last 6 months.
 *
 * Honesty: coverage is disclosed whenever data is partial (not all fills had a
 * price logged). Numbers are stated as observed from the fill log — no trend
 * assertions are made.
 *
 * Currency: ₹ throughout.
 */
@Composable
fun CostDetailScreen(
    costStats: CostStats?,
    runningCost: RunningCost?,
    monthlySpend: List<MonthSpend>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Section 1: Fuel ₹/km ────────────────────────────────────────────
        Text(
            "FUEL COST · ₹/KM",
            style = MaterialTheme.typography.labelMedium,
            color = GixxerBrand.accent,
        )

        if (costStats == null) {
            BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
                CostSectionHeader(Icons.Outlined.LocalGasStation, "FUEL ₹/KM")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Log at least two fills with a price to see ₹/km.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
                CostSectionHeader(Icons.Outlined.LocalGasStation, "FUEL ₹/KM")
                Spacer(Modifier.height(10.dp))

                // Hero: rolling ₹/km (last 5 priced tanks)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("₹", style = GixxerMono.body.copy(fontSize = 22.sp), color = GixxerBrand.accent, modifier = Modifier.padding(bottom = 12.dp))
                    Spacer(Modifier.size(2.dp))
                    HeroNumeral(
                        "%.2f".format(costStats.rollingRupeesPerKm),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 56.sp,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "/km",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    "rolling avg · last 5 priced tanks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(10.dp))

                // Sub: ₹/100 km (more intuitive fill-cost reference)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    CostSubStat(
                        label = "₹/100 KM",
                        value = "%.0f".format(costStats.rollingRupeesPer100Km),
                        unit = "₹",
                    )
                    CostSubStat(
                        label = "LIFETIME AVG",
                        value = "%.2f".format(costStats.lifetimeRupeesPerKm),
                        unit = "₹/km",
                    )
                }

                // Coverage disclosure — shown only when partial
                if (costStats.isPartialCoverage) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "over ${costStats.pricedIntervals} of ${costStats.totalIntervals} fill intervals — some fills had no price logged",
                        style = MaterialTheme.typography.labelSmall,
                        color = GixxerTokens.warning,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "over ${costStats.pricedIntervals} fill interval${if (costStats.pricedIntervals == 1) "" else "s"} · full coverage",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── Section 2: Running cost (blended fuel + service) ────────────────
        Spacer(Modifier.height(4.dp))
        Text(
            "RUNNING COST · FUEL + SERVICE",
            style = MaterialTheme.typography.labelMedium,
            color = GixxerBrand.accent,
        )

        if (runningCost == null) {
            BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
                CostSectionHeader(Icons.Outlined.CurrencyRupee, "BLENDED ₹/KM")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Log fuel fills (with prices) and service entries to see blended running cost.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
                CostSectionHeader(Icons.Outlined.CurrencyRupee, "BLENDED ₹/KM")
                Spacer(Modifier.height(10.dp))

                // Hero: blended ₹/km
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("₹", style = GixxerMono.body.copy(fontSize = 22.sp), color = GixxerTokens.zoneMid, modifier = Modifier.padding(bottom = 12.dp))
                    Spacer(Modifier.size(2.dp))
                    HeroNumeral(
                        "%.2f".format(runningCost.rupeesPerKm),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 56.sp,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "/km",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
                Text(
                    "fuel + service · over ${runningCost.distanceKm} km",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                // Sub stats
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CostStatCell(
                        label = "FUEL",
                        value = "₹%.0f".format(runningCost.fuelRupees),
                        sub = "%.0f%%".format(runningCost.fuelFraction * 100),
                        valueColor = GixxerBrand.zoneCool,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CostStatCell(
                        label = "SERVICE",
                        value = "₹%.0f".format(runningCost.serviceRupees),
                        sub = "%.0f%%".format(runningCost.serviceFraction * 100),
                        valueColor = GixxerTokens.zoneMid,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                    CostStatCell(
                        label = "TOTAL",
                        value = "₹%.0f".format(runningCost.totalRupees),
                        sub = "₹/100km: %.0f".format(runningCost.rupeesPer100Km),
                        valueColor = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Fuel-vs-service split bar
                FuelServiceSplitBar(runningCost.fuelFraction.toFloat())

                Spacer(Modifier.height(8.dp))

                // Coverage disclosure
                val fuelCoverageNote = if (runningCost.fuelFillsPriced < runningCost.fuelFillsTotal)
                    "${runningCost.fuelFillsPriced}/${runningCost.fuelFillsTotal} fills priced"
                else "${runningCost.fuelFillsTotal} fills"
                val svcCoverageNote = if (runningCost.servicesPriced < runningCost.servicesTotal)
                    "${runningCost.servicesPriced}/${runningCost.servicesTotal} services priced"
                else "${runningCost.servicesTotal} services"
                Text(
                    "$fuelCoverageNote · $svcCoverageNote",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (runningCost.fuelFillsPriced < runningCost.fuelFillsTotal ||
                        runningCost.servicesPriced < runningCost.servicesTotal)
                        GixxerTokens.warning
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ── Section 3: Monthly spend ─────────────────────────────────────────
        if (monthlySpend.isNotEmpty() && monthlySpend.any { it.totalRupees > 0.0 }) {
            Spacer(Modifier.height(4.dp))
            Text(
                "MONTHLY SPEND · LAST 6 MONTHS",
                style = MaterialTheme.typography.labelMedium,
                color = GixxerBrand.accent,
            )
            BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
                CostSectionHeader(Icons.Outlined.BarChart, "FUEL + SERVICE · BY MONTH")
                Spacer(Modifier.height(14.dp))
                MonthlySpendBars(monthlySpend)
            }
        }
    }
}

/** Section header: accent icon + all-caps label. */
@Composable
private fun CostSectionHeader(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = GixxerBrand.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Two-value sub-stat for the fuel ₹/km section. */
@Composable
private fun CostSubStat(label: String, value: String, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = GixxerMono.body.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.size(3.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** Compact tile used for the blended-cost sub-stats row. */
@Composable
private fun CostStatCell(
    label: String,
    value: String,
    sub: String,
    valueColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(10.dp),
    ) {
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, style = GixxerMono.body.copy(fontSize = 15.sp), color = valueColor)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Horizontal split bar: left = fuel fraction (zoneCool), right = service fraction (zoneMid). */
@Composable
private fun FuelServiceSplitBar(fuelFraction: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Fuel", style = MaterialTheme.typography.labelSmall, color = GixxerBrand.zoneCool)
            Text("Service", style = MaterialTheme.typography.labelSmall, color = GixxerTokens.zoneMid)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            val f = fuelFraction.coerceIn(0f, 1f)
            if (f > 0f) {
                Box(
                    Modifier
                        .weight(f)
                        .fillMaxHeight()
                        .background(GixxerBrand.zoneCool),
                )
            }
            if (f < 1f) {
                Box(
                    Modifier
                        .weight(1f - f)
                        .fillMaxHeight()
                        .background(GixxerTokens.zoneMid),
                )
            }
        }
    }
}

/** Stacked bar chart for monthly fuel + service spend. Max bar = total max across all months. */
@Composable
private fun MonthlySpendBars(months: List<MonthSpend>) {
    val maxTotal = months.maxOfOrNull { it.totalRupees }?.coerceAtLeast(1.0) ?: 1.0
    val barMaxHeight = 96f // dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height((barMaxHeight + 28f).dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        months.forEach { m ->
            val totalFraction = (m.totalRupees / maxTotal).toFloat().coerceIn(0f, 1f)
            val fuelFraction = if (m.totalRupees > 0.0) (m.fuelRupees / m.totalRupees).toFloat().coerceIn(0f, 1f) else 0f
            val totalBarDp = (4f + totalFraction * (barMaxHeight - 4f)).dp

            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                // Stacked bar: fuel (bottom, zoneCool) + service (top, zoneMid)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(totalBarDp)
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)),
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    if (m.serviceRupees > 0.0 && fuelFraction < 1f) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight((1f - fuelFraction).coerceAtLeast(0.01f))
                                .background(GixxerTokens.zoneMid),
                        )
                    }
                    if (m.fuelRupees > 0.0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(fuelFraction.coerceAtLeast(0.01f))
                                .background(GixxerBrand.zoneCool),
                        )
                    }
                    if (m.totalRupees == 0.0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Month label: "Jun" from "2026-06"
                Text(
                    monthLabel(m.month),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    // Legend
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot(GixxerBrand.zoneCool, "Fuel")
        LegendDot(GixxerTokens.zoneMid, "Service")
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Convert "2026-06" to a short month label like "Jun". */
private fun monthLabel(key: String): String {
    return try {
        val parts = key.split("-")
        val month = parts[1].toInt()
        val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        names.getOrElse(month - 1) { key }
    } catch (_: Exception) {
        key
    }
}
