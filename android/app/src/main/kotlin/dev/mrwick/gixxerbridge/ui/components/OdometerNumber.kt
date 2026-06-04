package dev.mrwick.gixxerbridge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono

/**
 * Odometer-style number: each digit rolls vertically when it changes, with the
 * whole number's direction (up vs down) choosing the slide direction — Robinhood's
 * "the transition carries the meaning" move (spec §6.1). Tabular + slashed-zero
 * figures (via [GixxerMono]) keep the width stable so digits never jitter.
 *
 * Only changed digits recompose, so this is cheap to drive at the eye's refresh
 * (throttle the source to ~10–15 Hz for live telemetry). The roll is best seen on
 * device; a static render just shows the figure.
 *
 * @param value the number to display.
 * @param style numeric text style — defaults to the Saira tnum+zero body face.
 * @param color text color (Unspecified → inherits LocalContentColor).
 * @param hapticOnChange fire a tiny haptic tick when the value changes.
 */
@Composable
fun OdometerNumber(
    value: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = GixxerMono.headline,
    color: Color = Color.Unspecified,
    hapticOnChange: Boolean = false,
) {
    var previous by remember { mutableStateOf(value) }
    val goingUp = value >= previous
    val haptics = LocalHapticFeedback.current
    SideEffect {
        if (value != previous && hapticOnChange) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        previous = value
    }

    Row(modifier) {
        val digits = value.toString()
        digits.forEachIndexed { index, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val dir = if (goingUp) 1 else -1
                    (slideInVertically { h -> dir * h } + fadeIn()) togetherWith
                        (slideOutVertically { h -> -dir * h } + fadeOut())
                },
                label = "odometer-digit-$index",
            ) { c ->
                androidx.compose.material3.Text(text = c.toString(), style = style, color = color)
            }
        }
    }
}
