package dev.mrwick.gixxerbridge.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion

/**
 * "The Sweep" — the REDLINE PRESS system gauge primitive (spec §9). A ~270° arc
 * whose lit 0→[progress] portion runs the telemetry spectrum (cool → mid → hot),
 * over a dim track, with a round cap. Reused everywhere intensity matters: cluster
 * speed, fuel/range, RPM, service-interval countdown, stats.
 *
 * On first composition (and when [progress] changes) the lit arc springs to its
 * value via [Motion.SpringSweep] — the "ignition sweep" wake-up. Set
 * [animateOnFirstComposition] = false for a static render (e.g. screenshot tests,
 * or when the value should snap rather than sweep on entry).
 *
 * Budgeted for the cluster (spec §14): the brush / stroke / geometry are built once
 * in [drawWithCache]; only the lit-arc paint re-runs per frame as the spring advances.
 *
 * Expects a roughly square layout (pass e.g. `Modifier.size(220.dp)`); the arc is
 * sized from the smaller dimension via the box. The 90° gap sits at the bottom.
 *
 * @param progress 0f..1f fraction of the arc to light.
 * @param content optional center slot (e.g. the speed numeral), centered in the gauge.
 */
@Composable
fun Sweep(
    progress: Float,
    modifier: Modifier = Modifier,
    sweepAngle: Float = 270f,
    startAngle: Float = 135f,
    strokeWidth: Dp = 16.dp,
    trackColor: Color = GixxerTokens.gaugeTrack,
    coolColor: Color = GixxerTokens.zoneCool,
    midColor: Color = GixxerTokens.zoneMid,
    hotColor: Color = GixxerTokens.zoneHot,
    animateOnFirstComposition: Boolean = true,
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    val target = progress.coerceIn(0f, 1f)
    val lit = remember { Animatable(if (animateOnFirstComposition) 0f else target) }
    LaunchedEffect(target) { lit.animateTo(target, animationSpec = Motion.SpringSweep) }

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val sw = strokeWidth.toPx()
                    val topLeft = Offset(sw / 2f, sw / 2f)
                    val arcSize = Size(size.width - sw, size.height - sw)
                    val pivot = Offset(size.width / 2f, size.height / 2f)
                    val stroke = Stroke(width = sw, cap = StrokeCap.Round)
                    // Gradient lives in the gauge's own (pre-rotation) space so it
                    // tracks the arc once rotated. Stops are placed across the arc's
                    // fraction of the full circle; the unlit gap region is never drawn.
                    val arcFraction = sweepAngle / 360f
                    val brush = Brush.sweepGradient(
                        0f to coolColor,
                        arcFraction * 0.5f to midColor,
                        arcFraction to hotColor,
                        1f to coolColor,
                        center = pivot,
                    )
                    onDrawBehind {
                        rotate(degrees = startAngle, pivot = pivot) {
                            drawArc(
                                color = trackColor,
                                startAngle = 0f,
                                sweepAngle = sweepAngle,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = stroke,
                            )
                            drawArc(
                                brush = brush,
                                startAngle = 0f,
                                sweepAngle = sweepAngle * lit.value,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = stroke,
                            )
                        }
                    }
                },
        )
        content?.invoke(this)
    }
}
