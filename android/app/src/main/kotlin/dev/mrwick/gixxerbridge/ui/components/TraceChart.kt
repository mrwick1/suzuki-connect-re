package dev.mrwick.gixxerbridge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * A telemetry trace that DRAWS ITSELF IN on entry (spec §6.1 / §8.2) — a smooth
 * line through [points] (each 0f..1f, left→right), revealed via
 * [PathMeasure.getSegment] driven by an [Animatable]. Doubles as the Home
 * "route-as-art" and the post-ride speed/lean trace. Optional soft area fill
 * underneath for the hero look.
 *
 * Geometry + brushes are built once in [drawWithCache]; only the revealed segment
 * re-runs per frame. Set [animateDraw] = false for a static (fully drawn) render.
 *
 * @param lineBrush stroke paint — defaults to the telemetry spectrum (cool→hot).
 * @param areaColor if set, fills under the line with this color → transparent.
 */
@Composable
fun TraceChart(
    points: List<Float>,
    modifier: Modifier = Modifier,
    lineBrush: Brush? = null,
    areaColor: Color? = null,
    strokeWidth: Dp = 3.dp,
    animateDraw: Boolean = true,
) {
    val drawn = remember { Animatable(if (animateDraw) 0f else 1f) }
    LaunchedEffect(points) {
        if (animateDraw) {
            drawn.snapTo(0f)
            drawn.animateTo(1f, animationSpec = tween(900))
        }
    }
    val cool = GixxerTokens.zoneCool
    val mid = GixxerTokens.zoneMid
    val hot = GixxerTokens.zoneHot

    Spacer(
        modifier.drawWithCache {
            val brush = lineBrush ?: Brush.horizontalGradient(listOf(cool, mid, hot))
            val line = Path()
            val n = points.size
            if (n >= 2) {
                val dx = size.width / (n - 1)
                fun y(i: Int) = size.height * (1f - points[i].coerceIn(0f, 1f))
                line.moveTo(0f, y(0))
                for (i in 0 until n - 1) {
                    val x0 = dx * i
                    val x1 = dx * (i + 1)
                    val cx = (x0 + x1) / 2f
                    line.cubicTo(cx, y(i), cx, y(i + 1), x1, y(i + 1))
                }
            }
            val area = Path().apply {
                if (n >= 2) {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            }
            val areaBrush = areaColor?.let {
                Brush.verticalGradient(listOf(it.copy(alpha = 0.28f), Color.Transparent))
            }
            val measure = PathMeasure().apply { setPath(line, false) }
            val total = measure.length
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            val segment = Path()

            onDrawBehind {
                if (areaBrush != null) drawPath(area, areaBrush)
                segment.reset()
                measure.getSegment(0f, total * drawn.value, segment, true)
                drawPath(segment, brush, style = stroke)
            }
        },
    )
}
