package dev.mrwick.gixxerbridge.ui.stats.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Minimalist line chart, Compose Canvas only. Renders a polyline with filled
 * gradient fill underneath, dotted gridlines at quarter intervals, and dots at
 * each value.
 *
 * Values are auto-scaled to the data range (max * 1.1) with a small floor so a
 * flat-zero series doesn't divide by zero. An empty / single-point list draws
 * an empty axis with no line.
 */
@Composable
fun LineChart(
    values: List<Float>,
    label: String,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF22D3EE),
    yLabelFormatter: (Float) -> String = { "%.1f".format(it) },
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            val peak = values.maxOrNull() ?: 0f
            Text(
                if (values.isEmpty()) "no data" else "peak ${yLabelFormatter(peak)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF94A3B8),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                Canvas(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                    if (values.isEmpty()) {
                        drawLine(
                            color = Color(0xFF334155),
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1f,
                        )
                        return@Canvas
                    }
                    val maxV = (values.maxOrNull() ?: 0f).coerceAtLeast(1f) * 1.1f
                    // Gridlines at 25/50/75% of height.
                    val gridColor = Color(0xFF1E293B)
                    for (q in 1..3) {
                        val y = size.height * q / 4f
                        drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    }
                    // Baseline.
                    drawLine(
                        color = Color(0xFF334155),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.5f,
                    )
                    if (values.size == 1) {
                        // Lone point: just a dot in the centre.
                        val v = values[0]
                        val y = size.height - (v / maxV) * size.height
                        drawCircle(lineColor, radius = 6f, center = Offset(size.width / 2f, y))
                        return@Canvas
                    }
                    val xStep = size.width / (values.size - 1)
                    val pts = values.mapIndexed { i, v ->
                        Offset(i * xStep, size.height - (v / maxV) * size.height)
                    }
                    // Filled area under the line.
                    val area = Path().apply {
                        moveTo(pts.first().x, size.height)
                        pts.forEach { lineTo(it.x, it.y) }
                        lineTo(pts.last().x, size.height)
                        close()
                    }
                    drawPath(
                        path = area,
                        brush = Brush.verticalGradient(
                            colors = listOf(lineColor.copy(alpha = 0.35f), Color.Transparent),
                            startY = 0f,
                            endY = size.height,
                        ),
                    )
                    // Polyline.
                    for (i in 1 until pts.size) {
                        drawLine(
                            color = lineColor,
                            start = pts[i - 1],
                            end = pts[i],
                            strokeWidth = 3f,
                            cap = StrokeCap.Round,
                        )
                    }
                    pts.forEach { p ->
                        drawCircle(Color(0xFF0F172A), radius = 5f, center = p)
                        drawCircle(lineColor, radius = 3.5f, center = p)
                    }
                }
            }
        }
    }
}
