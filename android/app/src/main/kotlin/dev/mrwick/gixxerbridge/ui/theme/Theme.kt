package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/**
 * GixxerBridge theme — REDLINE PRESS (2026-06-04). Dark default (OLED + showpiece);
 * TARMAC light is a sunlight-legibility safety mode. Colors flow from GixxerTokens.
 * See docs/superpowers/specs/2026-06-04-redline-press-design-system-design.md.
 */
@Composable
fun GixxerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) GixxerDarkColors else GixxerLightColors
    MaterialTheme(
        colorScheme = scheme,
        typography = GixxerTypography,
        shapes = GixxerShapes,
    ) {
        CompositionLocalProvider(content = content)
    }
}

// --- Color schemes (wired from GixxerTokens) -------------------------------
// primary = ecstarBlue (structural) so default Material components don't spray
// the rationed lush-green; green is applied explicitly via GixxerBrand.accent.

val GixxerDarkColors = darkColorScheme(
    primary = GixxerTokens.ecstarBlue,
    onPrimary = GixxerTokens.onSurface,
    secondary = GixxerTokens.lushGreen,
    onSecondary = GixxerTokens.inkBlack,
    tertiary = GixxerTokens.zoneCool,
    onTertiary = GixxerTokens.inkBlack,
    error = GixxerTokens.dangerWarm,
    onError = GixxerTokens.onSurface,
    background = GixxerTokens.inkBlack,
    onBackground = GixxerTokens.onSurface,
    surface = GixxerTokens.cockpitSurface,
    onSurface = GixxerTokens.onSurface,
    surfaceVariant = GixxerTokens.cockpitSurface2,
    onSurfaceVariant = GixxerTokens.onSurfaceDim,
    surfaceContainerLowest = GixxerTokens.inkBlack,
    surfaceContainerLow = GixxerTokens.inkBlack,
    surfaceContainer = GixxerTokens.cockpitSurface,
    surfaceContainerHigh = GixxerTokens.cockpitSurface2,
    surfaceContainerHighest = GixxerTokens.cockpitSurface2,
    outline = GixxerTokens.hairline,
    outlineVariant = GixxerTokens.hairline,
    inverseSurface = GixxerTokens.onSurface,
    inverseOnSurface = GixxerTokens.inkBlack,
)

val GixxerLightColors = lightColorScheme(
    primary = GixxerTokens.ecstarBlue,
    onPrimary = Color.White,
    secondary = GixxerTokens.lushGreenLight,
    onSecondary = Color.White,
    tertiary = GixxerTokens.zoneCool,
    onTertiary = GixxerTokens.onPaper,
    error = GixxerTokens.dangerWarm,
    onError = Color.White,
    background = GixxerTokens.paperBg,
    onBackground = GixxerTokens.onPaper,
    surface = GixxerTokens.paperSurface,
    onSurface = GixxerTokens.onPaper,
    surfaceVariant = GixxerTokens.paperSurfaceTint,
    onSurfaceVariant = GixxerTokens.onPaperDim,
    surfaceContainerLowest = GixxerTokens.paperBg,
    surfaceContainerLow = GixxerTokens.paperBg,
    surfaceContainer = GixxerTokens.paperSurface,
    surfaceContainerHigh = GixxerTokens.paperSurfaceTint,
    surfaceContainerHighest = GixxerTokens.paperSurfaceTint,
    outline = Color(0x1F000308),
    outlineVariant = Color(0x1F000308),
)

/**
 * Brand-name aliases for color-by-intent. Members used by existing screens are
 * kept; new intent members (structural, zone*) are added.
 */
object GixxerBrand {
    val accent = GixxerTokens.lushGreen
    val accentHero = GixxerTokens.lushGreen
    val structural = GixxerTokens.ecstarBlue
    val warning = GixxerTokens.zoneMid
    val danger = GixxerTokens.dangerWarm
    val success = GixxerTokens.zoneCool
    val textSubtle = GixxerTokens.onSurfaceDim
    val zoneCool = GixxerTokens.zoneCool
    val zoneMid = GixxerTokens.zoneMid
    val zoneHot = GixxerTokens.zoneHot
}

// --- Typography: Saira (display/title/numeral) + Hanken (body) -------------
val GixxerTypography = Typography(
    displayLarge = TextStyle(fontFamily = SairaCondensedFamily, fontWeight = FontWeight.W900, fontSize = 64.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = SairaCondensedFamily, fontWeight = FontWeight.W900, fontSize = 45.sp, lineHeight = 44.sp),
    displaySmall = TextStyle(fontFamily = SairaCondensedFamily, fontWeight = FontWeight.W700, fontSize = 36.sp, lineHeight = 38.sp),

    headlineLarge = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W700, fontSize = 28.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W700, fontSize = 24.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W600, fontSize = 20.sp, lineHeight = 24.sp),

    titleLarge = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W700, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W600, fontSize = 16.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W600, fontSize = 14.sp, lineHeight = 18.sp),

    bodyLarge = TextStyle(fontFamily = HankenFamily, fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = HankenFamily, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = HankenFamily, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp),

    labelLarge = TextStyle(fontFamily = HankenFamily, fontWeight = FontWeight.W600, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = HankenFamily, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = HankenFamily, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp),
)

/**
 * Live-numeric styles — speed, odo, trip km, range, hex frames. Saira numerals
 * with tnum + slashed-zero so digits never shift as values roll.
 */
object GixxerMono {
    val display = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W900, fontSize = 144.sp, lineHeight = 144.sp, fontFeatureSettings = "tnum, zero")
    val headline = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W700, fontSize = 32.sp, lineHeight = 36.sp, fontFeatureSettings = "tnum, zero")
    val body = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum, zero")
    val label = TextStyle(fontFamily = SairaFamily, fontWeight = FontWeight.W600, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum, zero")
}

// --- Shapes: chips 8 / small 12 / tiles 18 / sheets 28 ---------------------
val GixxerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// --- Retained for the legacy Settings accent picker (retired in a later plan)
val ACCENT_PALETTE: Map<String, Color> = linkedMapOf(
    "green" to GixxerTokens.lushGreen,
    "blue" to GixxerTokens.ecstarBlue,
    "cyan" to GixxerTokens.zoneCool,
    "amber" to GixxerTokens.zoneMid,
    "magenta" to GixxerTokens.zoneHot,
)

fun accentColorFor(name: String?): Color =
    ACCENT_PALETTE[name] ?: GixxerTokens.lushGreen
