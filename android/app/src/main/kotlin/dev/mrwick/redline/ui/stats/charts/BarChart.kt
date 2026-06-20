package dev.mrwick.redline.ui.stats.charts

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
import androidx.compose.foundation.shape.CircleShape
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
import dev.mrwick.redline.ui.theme.GixxerTokens
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Two-series bar chart. [seriesA] is the "max" series and is drawn behind
 * [seriesB] (the "avg" series), so each ride shows up as a tall ghostly bar
 * with a shorter solid bar inside it. Both series must have the same length;
 * if [seriesB] is null, only one series is drawn.
 *
 * X-axis labels are optional and rendered below the bars; rotation is skipped
 * for simplicity — keep label strings short (3-4 chars) so they fit.
 */
@Composable
fun BarChart(
    seriesA: List<Float>,
    seriesB: List<Float>? = null,
    labels: List<String> = emptyList(),
    title: String,
    modifier: Modifier = Modifier,
    colorA: Color = GixxerTokens.liverySilver,
    colorB: Color = GixxerTokens.zoneMid,
    legendA: String = "max",
    legendB: String = "avg",
) {
    require(seriesB == null || seriesB.size == seriesA.size) {
        "seriesA and seriesB must have the same length"
    }
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (seriesB != null) {
                    LegendDot(colorA, legendA)
                    Spacer(modifier = Modifier.size(8.dp))
                    LegendDot(colorB, legendB)
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                if (seriesA.isEmpty()) return@Canvas
                val maxV = listOfNotNull(
                    seriesA.maxOrNull(),
                    seriesB?.maxOrNull(),
                ).max().coerceAtLeast(1f) * 1.1f
                val n = seriesA.size
                val slotW = size.width / n
                val barW = slotW * 0.55f
                val gridColor = GixxerTokens.cockpitSurface2
                for (q in 1..3) {
                    val y = size.height * q / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                }
                drawLine(
                    color = GixxerTokens.liverySilver,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.5f,
                )
                for (i in 0 until n) {
                    val cx = i * slotW + slotW / 2f
                    val aVal = seriesA[i]
                    val aH = (aVal / maxV) * size.height
                    drawRoundRect(
                        color = colorA,
                        topLeft = Offset(cx - barW / 2f, size.height - aH),
                        size = Size(barW, aH),
                        cornerRadius = CornerRadius(4f, 4f),
                    )
                    if (seriesB != null) {
                        val bVal = seriesB[i]
                        val bH = (bVal / maxV) * size.height
                        val innerW = barW * 0.55f
                        drawRoundRect(
                            color = colorB,
                            topLeft = Offset(cx - innerW / 2f, size.height - bH),
                            size = Size(innerW, bH),
                            cornerRadius = CornerRadius(3f, 3f),
                        )
                    }
                }
            }
            if (labels.isNotEmpty()) {
                Spacer(modifier = Modifier.size(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    labels.forEach { lbl ->
                        Text(
                            lbl,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = GixxerTokens.onSurfaceDim,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.onSurfaceDim,
        )
    }
}
