package dev.mrwick.redline.ui.home.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.mrwick.redline.ble.ConnectionState
import dev.mrwick.redline.ui.theme.GixxerBrand
import dev.mrwick.redline.ui.theme.GixxerTokens

/**
 * 12 dp shape-morphing connection-state dot. Replaces verbose
 * "Connected via Bluetooth · MAC: …" status text everywhere.
 *
 * Visual mapping:
 *   Idle / Disconnected — hollow circle, textMuted, static
 *   Connecting          — filled circle, accent, pulse (allowed loading anim)
 *   Discovering         — rounded square, warning, shape-morph from circle
 *   Ready               — filled circle, success, shape-morph to circle
 *   Failed              — filled circle, danger
 *
 * Hand-rolled with Animatable + animateDpAsState — does not pin M3
 * Expressive alpha just for this.
 */
@Composable
fun ConnectionDot(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = colorFor(state)
    // Motion.SpringSnap is typed AnimationSpec<Float>, but animateDpAsState
    // requires AnimationSpec<Dp>. Use spring<Dp> with the same physics parameters
    // so the corner-radius morph matches the design-system spring exactly.
    val cornerRadius by animateDpAsState(
        targetValue = if (state is ConnectionState.Discovering) 3.dp else 6.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "dotCornerRadius",
    )
    val filled = state !is ConnectionState.Idle && state !is ConnectionState.Disconnected

    val pulseAlpha = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state is ConnectionState.Connecting) {
            // Allowed loading-style infiniteRepeatable + tween — the single
            // exception to the no-tween rule, gated to the Connecting state.
            pulseAlpha.animateTo(
                targetValue = 0.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 700, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            )
        } else {
            pulseAlpha.snapTo(1f)
        }
    }

    Box(
        modifier = modifier
            .size(12.dp)
            .clip(RoundedCornerShape(cornerRadius))
            .then(
                if (filled) {
                    Modifier.background(color.copy(alpha = pulseAlpha.value))
                } else {
                    Modifier.border(1.dp, GixxerTokens.textMuted, RoundedCornerShape(cornerRadius))
                },
            ),
    )
}

private fun colorFor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Ready -> GixxerBrand.success
    is ConnectionState.Connecting -> GixxerBrand.accent
    is ConnectionState.Discovering -> GixxerBrand.warning
    is ConnectionState.Failed -> GixxerBrand.danger
    is ConnectionState.Disconnected -> GixxerTokens.textMuted
    is ConnectionState.Idle -> GixxerTokens.textMuted
}
