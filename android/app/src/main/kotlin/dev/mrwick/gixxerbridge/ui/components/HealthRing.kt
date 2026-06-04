package dev.mrwick.gixxerbridge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion

/** Bike-health state — drives both the [HealthRing] shape and its color. */
enum class HealthState { Good, Caution, Fault }

/**
 * Bike-health rendered as a living shape (spec §6.1): a smooth rounded "cookie"
 * that morphs spikier as health degrades, color shifting along the telemetry
 * spectrum (cool → amber → warm-danger). Status as an object with moods, not a
 * coloured dot. The morph + color spring when [state] changes.
 *
 * Built on androidx.graphics.shapes [RoundedPolygon]/[Morph]; the path + matrix are
 * allocated once in [drawWithCache], re-stroked per frame as the morph advances.
 * Set [animate] = false for a static render.
 */
@Composable
fun HealthRing(
    state: HealthState,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 6.dp,
    animate: Boolean = true,
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    val good = remember { RoundedPolygon(numVertices = 12, rounding = CornerRounding(0.5f)) }
    val bad = remember {
        RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.62f, rounding = CornerRounding(0.12f))
    }
    val morph = remember { Morph(good, bad) }

    val target = when (state) {
        HealthState.Good -> 0f
        HealthState.Caution -> 0.5f
        HealthState.Fault -> 1f
    }
    val progress = remember { Animatable(target) }
    LaunchedEffect(target) { if (animate) progress.animateTo(target, animationSpec = Motion.SpringSnap) }

    val ringColor by animateColorAsState(
        targetValue = when (state) {
            HealthState.Good -> GixxerTokens.zoneCool
            HealthState.Caution -> GixxerTokens.zoneMid
            HealthState.Fault -> GixxerTokens.dangerWarm
        },
        label = "health-color",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val androidPath = android.graphics.Path()
                    val matrix = android.graphics.Matrix()
                    val radius = size.minDimension / 2f * 0.92f
                    val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
                    onDrawBehind {
                        androidPath.rewind()
                        morph.toPath(progress.value, androidPath)
                        matrix.reset()
                        matrix.setScale(radius, radius)
                        matrix.postTranslate(size.width / 2f, size.height / 2f)
                        androidPath.transform(matrix)
                        drawPath(androidPath.asComposePath(), color = ringColor, style = stroke)
                    }
                },
        )
        content?.invoke(this)
    }
}
