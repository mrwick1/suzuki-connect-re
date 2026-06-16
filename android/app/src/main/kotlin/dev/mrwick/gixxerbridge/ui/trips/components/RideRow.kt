package dev.mrwick.gixxerbridge.ui.trips.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.data.RideMeta
import dev.mrwick.gixxerbridge.data.RideSampleEntity
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Premium ride row for the Trips list.
 *
 * Layout:
 *   [date rail 48dp] | [distance headline + subtitle + meta badge row] | [sparkline 60x32dp] | [icons]
 *
 * Date rail: day number in GixxerMono.headline weight 700, month label below in
 * labelSmall textMuted.
 *
 * Center: distance in headlineMedium textPrimary, second line "duration · avg speed"
 * in bodyMedium textMuted. When the ride is starred or has tags a badge row appears:
 * a gold star icon (starred) and up to one tag pill (the first tag, truncated).
 *
 * Sparkline: 60×32dp Canvas polyline of speed-over-time samples. Hidden if
 * [sparklineSamples] is null (not yet loaded) or empty (no samples recorded).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RideRow(
    ride: RideEntity,
    meta: RideMeta,
    sparklineSamples: List<RideSampleEntity>?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    val dayFmt = remember { SimpleDateFormat("d", Locale.US) }
    val monthFmt = remember { SimpleDateFormat("MMM", Locale.US) }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val date = Date(ride.startedAtMillis)
    val dayStr = dayFmt.format(date).uppercase(Locale.US)
    val monthStr = monthFmt.format(date).uppercase(Locale.US)
    val startTimeStr = timeFmt.format(date)

    val inProgress = ride.endedAtMillis == null
    val durationMin = (((ride.endedAtMillis ?: ride.startedAtMillis) - ride.startedAtMillis) / 60_000).coerceAtLeast(0)
    val distanceKm = max(0, (ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)
    val avgSpeed = ride.avgSpeedKmh

    val accentColor = GixxerTokens.accent
    val hasMeta = meta.favorite || meta.tags.isNotEmpty()

    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = MaterialTheme.shapes.large,            // 18dp per GixxerShapes.large
        colors = CardDefaults.cardColors(
            containerColor = if (selected) GixxerTokens.accent.copy(alpha = 0.12f)
                             else GixxerTokens.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── Date rail ─────────────────────────────────────────────────────
            Column(
                modifier = Modifier.width(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = dayStr,
                    style = GixxerMono.headline.copy(fontWeight = FontWeight.W700),
                    color = GixxerTokens.textPrimary,
                )
                Text(
                    text = monthStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = GixxerTokens.textMuted,
                )
            }

            Spacer(Modifier.width(16.dp))

            // ── Center content ────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$distanceKm km",
                    style = MaterialTheme.typography.headlineMedium,
                    color = GixxerTokens.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (inProgress) "In progress…"
                           else "$startTimeStr · ${durationMin} min · ${"%.0f".format(avgSpeed)} km/h avg",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (inProgress) GixxerTokens.accent else GixxerTokens.textMuted,
                )

                // ── Meta badge row (star + first tag) ─────────────────────────
                if (hasMeta) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (meta.favorite) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "Starred",
                                tint = GixxerTokens.zoneMid,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        // Show the first tag as a small pill badge.
                        meta.tags.firstOrNull()?.let { tag ->
                            TagPill(tag = tag)
                        }
                        // If there are more tags, show a count badge.
                        if (meta.tags.size > 1) {
                            TagPill(tag = "+${meta.tags.size - 1}")
                        }
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // ── Sparkline ─────────────────────────────────────────────────────
            // Only show once samples are loaded AND the ride has samples.
            if (!sparklineSamples.isNullOrEmpty()) {
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(32.dp),
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        val pts = sparklineSamples
                        val maxSpeed = pts.maxOf { it.speedKmh }.toFloat().coerceAtLeast(1f)
                        val w = size.width
                        val h = size.height
                        val path = Path()
                        pts.forEachIndexed { i, sample ->
                            val x = (i.toFloat() / (pts.size - 1).coerceAtLeast(1)) * w
                            val y = h - (sample.speedKmh.toFloat() / maxSpeed) * h
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = accentColor,
                            style = Stroke(
                                width = 1.5.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
            }

            // ── Trailing control: select indicator or delete ─────────────────
            if (selectionMode) {
                Icon(
                    imageVector = if (selected) Icons.Filled.CheckCircle
                                  else Icons.Outlined.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) GixxerTokens.accent else GixxerTokens.textMuted,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete ride",
                        tint = GixxerTokens.textMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** Small rounded pill chip for a tag label in a row badge. */
@Composable
private fun TagPill(tag: String) {
    Box(
        modifier = Modifier
            .background(
                color = GixxerTokens.accent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.accent,
        )
    }
}

/**
 * Week-section header rendered above the first row of each week group.
 *
 * Label is one of: "THIS WEEK", "LAST WEEK", or "WEEK OF 19 MAY".
 */
@Composable
fun WeekSectionHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = GixxerTokens.textMuted,
        modifier = modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}
