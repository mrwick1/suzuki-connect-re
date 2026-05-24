package dev.mrwick.gixxerbridge.ui.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.analytics.CalendarDay
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * GitHub-style 7-row × N-column heatmap. Each column is one week (left =
 * oldest); rows top-to-bottom are Mon → Sun. The colour gradient runs from
 * a dim slate (no ride) to bright cyan (most-km day in the window).
 *
 * [days] is consumed in chronological order; the first column is padded with
 * blank cells if [days].first() doesn't fall on a Monday, so the grid stays
 * aligned to the calendar week. Total grid size is always 7 × ceil(N/7).
 */
@Composable
fun CalendarHeatmap(
    days: List<CalendarDay>,
    title: String = "Riding heatmap — last 12 weeks",
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            val activeDays = days.count { it.km > 0 }
            val totalKm = days.sumOf { it.km }
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "$activeDays days ridden · $totalKm km total",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Compute leading blanks so the first column starts on Monday.
            val leading = if (days.isEmpty()) 0 else {
                val firstDow = LocalDate.ofEpochDay(days.first().epochDay).dayOfWeek
                (firstDow.value - DayOfWeek.MONDAY.value + 7) % 7
            }
            val cells = leading + days.size
            val cols = (cells + 6) / 7
            val maxKm = days.maxOfOrNull { it.km }?.coerceAtLeast(1) ?: 1
            Canvas(modifier = Modifier.fillMaxWidth().height(112.dp)) {
                if (cols == 0) return@Canvas
                val gap = 3f
                val cellW = (size.width - (cols - 1) * gap) / cols
                val cellH = (size.height - 6 * gap) / 7f
                for (i in 0 until cells) {
                    val col = i / 7
                    val row = i % 7
                    val x = col * (cellW + gap)
                    val y = row * (cellH + gap)
                    val km = if (i < leading) -1 else days[i - leading].km
                    val color = when {
                        km < 0 -> Color.Transparent
                        km == 0 -> Color(0xFF1F2937)
                        else -> heatColor(km.toFloat() / maxKm)
                    }
                    if (color != Color.Transparent) {
                        drawRoundRect(
                            color = color,
                            topLeft = Offset(x, y),
                            size = Size(cellW, cellH),
                            cornerRadius = CornerRadius(2f, 2f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Legend strip.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "less",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                )
                Spacer(modifier = Modifier.size(6.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                    val c = if (f == 0f) Color(0xFF1F2937) else heatColor(f)
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(c),
                    )
                    Spacer(modifier = Modifier.size(3.dp))
                }
                Spacer(modifier = Modifier.size(3.dp))
                Text(
                    "more",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                )
            }
        }
    }
}

/**
 * Map [0,1] → a colour gradient from dim slate (#1F2937) → cyan (#22D3EE).
 * Used by [CalendarHeatmap] for "more km = brighter".
 */
private fun heatColor(intensity: Float): Color {
    val t = intensity.coerceIn(0f, 1f)
    val low = Color(0xFF1F2937)
    val high = Color(0xFF22D3EE)
    fun mix(a: Float, b: Float) = a + (b - a) * t
    return Color(
        red = mix(low.red, high.red),
        green = mix(low.green, high.green),
        blue = mix(low.blue, high.blue),
        alpha = 1f,
    )
}
