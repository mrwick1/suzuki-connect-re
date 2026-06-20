package dev.mrwick.redline.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import dev.mrwick.redline.ui.theme.GixxerTokens
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Animated shimmer placeholder. Loops a subtle 0.4 -> 0.8 alpha over 1.2 s.
 * Cheap: single infinite transition shared by all on-screen instances.
 */
@Composable
fun ShimmerBox(modifier: Modifier = Modifier, cornerRadius: Dp = 6.dp) {
    val infinite = rememberInfiniteTransition(label = "shimmer")
    val alpha by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(GixxerTokens.cockpitSurface2.copy(alpha = alpha)),
    )
}

/** A horizontal shimmer line at [widthFraction] of parent width. */
@Composable
fun SkeletonLine(
    widthFraction: Float = 1f,
    height: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    ShimmerBox(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height),
    )
}

/** A skeleton stand-in for a data card: three shimmer lines inside a Card. */
@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SkeletonLine(widthFraction = 0.4f, height = 12.dp)
            SkeletonLine(widthFraction = 0.7f, height = 20.dp)
            SkeletonLine(widthFraction = 0.5f, height = 14.dp)
        }
    }
}

/** A block-shaped shimmer placeholder for chart / map regions. */
@Composable
fun SkeletonBlock(height: Dp, modifier: Modifier = Modifier) {
    ShimmerBox(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        cornerRadius = 12.dp,
    )
}
