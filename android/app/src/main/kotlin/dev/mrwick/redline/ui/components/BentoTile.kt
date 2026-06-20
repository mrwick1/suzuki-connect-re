package dev.mrwick.redline.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.mrwick.redline.ui.theme.GixxerTokens
import dev.mrwick.redline.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * A single bento tile (spec §6.2) — the building block that replaces grey
 * card-soup. A blue-tinted surface with a hairline edge and the "Reveal
 * Stagger-rise" entry: each tile lifts from +12dp + fades in, spring-settled,
 * staggered by [index] (~50ms each, capped). Tile *size* (set by the caller via
 * the modifier / grid span) encodes importance — exactly one Hero per screen.
 *
 * The rise is applied in [graphicsLayer] (draw-phase, never a relayout). Set
 * [animateEntry] = false for a static render (screenshot tests, or tiles that
 * shouldn't re-animate on recomposition).
 *
 * @param index position in the reveal order (drives the stagger delay).
 * @param container tile fill — defaults to the plane-1 cockpit surface.
 * @param onClick optional; makes the whole tile tappable (Card→detail handoff lives elsewhere).
 */
@Composable
fun BentoTile(
    modifier: Modifier = Modifier,
    index: Int = 0,
    animateEntry: Boolean = true,
    container: Color = MaterialTheme.colorScheme.surface,
    cornerRadius: androidx.compose.ui.unit.Dp = 20.dp,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val appear = remember { Animatable(if (animateEntry) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animateEntry) {
            delay((index * 50L).coerceAtMost(400L))
            appear.animateTo(1f, animationSpec = Motion.SpringSnap)
        }
    }
    val shape = RoundedCornerShape(cornerRadius)
    Column(
        modifier = modifier
            .graphicsLayer {
                translationY = (1f - appear.value) * 12.dp.toPx()
                alpha = appear.value
            }
            .clip(shape)
            .background(container)
            .border(1.dp, GixxerTokens.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}
