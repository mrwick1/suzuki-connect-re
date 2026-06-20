package dev.mrwick.redline.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.mrwick.redline.ui.theme.SairaCondensedFamily

/**
 * The oversized condensed "masthead" numeral (spec §8.1) — Saira Condensed Black,
 * tabular figures, on a single line that is meant to BLEED off the edge.
 * The bleed itself is a layout decision the caller makes (place it with a negative
 * offset / let it overflow its parent); this component just supplies the giant,
 * non-wrapping figure.
 *
 * Default color is `colorScheme.secondary` — the theme-aware accent that role-swaps
 * to the darker lush-green in TARMAC light, so the numeral stays legible in both modes.
 *
 * @param text the figure (already formatted, e.g. "182" or "42.6").
 * @param fontSize how big; heroes run ~120–220sp. Line height is clamped tight.
 */
@Composable
fun HeroNumeral(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.secondary,
    fontSize: TextUnit = 160.sp,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        style = TextStyle(
            fontFamily = SairaCondensedFamily,
            fontWeight = FontWeight.W900,
            fontSize = fontSize,
            lineHeight = fontSize.value.times(0.86f).sp,
            letterSpacing = (-0.03).em,
            fontFeatureSettings = "tnum",
        ),
    )
}
