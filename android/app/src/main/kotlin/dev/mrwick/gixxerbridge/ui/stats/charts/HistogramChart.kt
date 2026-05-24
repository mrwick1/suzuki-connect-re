package dev.mrwick.gixxerbridge.ui.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mrwick.gixxerbridge.analytics.SpeedBucket

/**
 * Speed-distribution histogram. Each bar is one [SpeedBucket]; bar height
 * scales linearly to the largest bucket's sample count. Bars use a vertical
 * gradient (cool → warm) for visual interest. Empty data renders an empty
 * baseline.
 *
 * Every 3rd bucket gets a labelled tick to keep the axis legible at small
 * widths (e.g. "0", "30", "60", ...).
 */
@Composable
fun HistogramChart(
    buckets: List<SpeedBucket>,
    title: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            val total = buckets.sumOf { it.sampleCount }
            Text(
                if (total == 0) "no samples yet" else "$total samples · ${buckets.size} buckets",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                if (buckets.isEmpty()) return@Canvas
                val maxCount = (buckets.maxOf { it.sampleCount }).coerceAtLeast(1)
                val slotW = size.width / buckets.size
                val barW = slotW * 0.85f
                val gridColor = Color(0xFF1E293B)
                for (q in 1..3) {
                    val y = size.height * q / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                drawLine(
                    color = Color(0xFF334155),
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.5f,
                )
                buckets.forEachIndexed { i, b ->
                    val cx = i * slotW + slotW / 2f
                    val h = (b.sampleCount.toFloat() / maxCount.toFloat()) * size.height
                    if (h <= 0f) {
                        // Render a 2px dim sliver so empty buckets are still visible.
                        drawRoundRect(
                            color = Color(0xFF1F2937),
                            topLeft = Offset(cx - barW / 2f, size.height - 2f),
                            size = Size(barW, 2f),
                            cornerRadius = CornerRadius(1f, 1f),
                        )
                    } else {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFFEF4444), Color(0xFFF59E0B), Color(0xFF22D3EE)),
                                startY = 0f,
                                endY = size.height,
                            ),
                            topLeft = Offset(cx - barW / 2f, size.height - h),
                            size = Size(barW, h),
                            cornerRadius = CornerRadius(3f, 3f),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (buckets.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    buckets.forEachIndexed { i, b ->
                        Text(
                            text = if (i % 3 == 0) b.lowKmh.toString() else "",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
