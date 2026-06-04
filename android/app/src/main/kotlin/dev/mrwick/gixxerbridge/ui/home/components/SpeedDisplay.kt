package dev.mrwick.gixxerbridge.ui.home.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerBrand
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * Live BLE-frame staleness fraction. 0.0 at lastUpdateMs, ramps linearly
 * to 1.0 at lastUpdateMs + intervalMs, then stays at 1.0. Returns 1.0 if
 * lastUpdateMs is 0 (never updated). Clamps to [0.0, 1.0].
 */
fun tickerFraction(nowMs: Long, lastUpdateMs: Long, intervalMs: Long): Float {
    if (lastUpdateMs <= 0L) return 1f
    val age = nowMs - lastUpdateMs
    if (age <= 0L) return 0f
    if (age >= intervalMs) return 1f
    return age.toFloat() / intervalMs.toFloat()
}

enum class SpeedState { Connected, Connecting, Disconnected }

/**
 * Hero speed figure: 144 sp tabular Geist Mono. 4 dp brand-red underline
 * ticker fills 0->100% across the BLE poll interval as silent staleness
 * indicator. Greys to textMuted when not Connected; ticker hides when
 * Disconnected.
 */
@Composable
fun SpeedDisplay(
    speedKmh: Int?,
    state: SpeedState,
    lastUpdateMs: Long,
    modifier: Modifier = Modifier,
    intervalMs: Long = 5_000L,
) {
    val number = speedKmh?.toString()?.padStart(3, ' ') ?: "  —"
    val color = if (state == SpeedState.Connected) GixxerTokens.textPrimary else GixxerTokens.textMuted

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(50)
        }
    }
    val targetFraction = tickerFraction(nowMs, lastUpdateMs, intervalMs)
    val fraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = Motion.SpringSnap,
        label = "speedTicker",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = number,
            style = GixxerMono.display.copy(color = color),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        if (state != SpeedState.Disconnected) {
            UnderlineTicker(fraction = fraction, color = GixxerBrand.accentHero)
        } else {
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun UnderlineTicker(fraction: Float, color: Color) {
    Box(modifier = Modifier.fillMaxWidth().height(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(4.dp)) {
            val w = size.width * fraction.coerceIn(0f, 1f)
            drawRect(
                color = color,
                topLeft = Offset(0f, 0f),
                size = Size(w, size.height),
            )
        }
    }
}
