package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GixxerBridge design system.
 *
 * Inspired by the bike's own LCD cluster: deep navy backgrounds, soft cyan
 * accents, monospaced numerals for data, and a clear separation between
 * surface cards and the page background.
 *
 * One theme — dark only. Light theme isn't useful in a helmet, and a
 * matte black UI carries less power draw on the OLED panels typical of
 * recent Android phones.
 */
@Composable
fun GixxerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GixxerColors,
        typography = GixxerTypography,
        shapes = GixxerShapes,
    ) {
        CompositionLocalProvider(content = content)
    }
}

// --- Color palette ---------------------------------------------------------
//
// Sourced from the Tailwind slate / cyan / amber families. Mapped onto the
// Material3 ColorScheme roles so vanilla Material widgets pick up the brand
// automatically.

private val BrandCyan = Color(0xFF22D3EE)        // primary accent
private val BrandCyanDim = Color(0xFF0E7490)     // pressed / container
private val BrandAmber = Color(0xFFFBBF24)       // secondary / warning
private val BrandRed = Color(0xFFEF4444)         // destructive / error
private val BrandGreen = Color(0xFF10B981)       // success / online

// Surfaces stair-step from page background to top cards.
private val BgBase = Color(0xFF050B1A)           // page bg (almost black, slight blue)
private val BgSurface = Color(0xFF0F172A)        // standard card
private val BgSurfaceHigh = Color(0xFF1E293B)    // raised card / app bar
private val BgSurfaceMax = Color(0xFF334155)     // selected / focus tint
private val Outline = Color(0xFF334155)

private val TextPrimary = Color(0xFFF1F5F9)
private val TextSecondary = Color(0xFF94A3B8)
private val TextOnAccent = Color(0xFF052E36)

val GixxerColors = darkColorScheme(
    primary = BrandCyan,
    onPrimary = TextOnAccent,
    primaryContainer = BrandCyanDim,
    onPrimaryContainer = TextPrimary,
    secondary = BrandAmber,
    onSecondary = Color(0xFF1F1300),
    tertiary = BrandGreen,
    onTertiary = Color(0xFF003920),
    error = BrandRed,
    onError = Color.White,
    background = BgBase,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgSurfaceHigh,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = BgSurface,
    surfaceContainerHigh = BgSurfaceHigh,
    surfaceContainerHighest = BgSurfaceMax,
    surfaceContainerLow = Color(0xFF0A1020),
    outline = Outline,
    outlineVariant = Color(0xFF1F2937),
    inverseSurface = TextPrimary,
    inverseOnSurface = BgBase,
)

// Brand accents exposed for cluster-style flourishes that need them by name.
object GixxerBrand {
    val accent = BrandCyan
    val accentDim = BrandCyanDim
    val warning = BrandAmber
    val danger = BrandRed
    val success = BrandGreen
    val textSubtle = TextSecondary
}

// --- Typography ------------------------------------------------------------
//
// Two voices:
//   - sans (FontFamily.Default = Roboto on Android): all readable copy
//   - mono (FontFamily.Monospace): cluster-like numerals — speed, distance,
//     timestamps, hex frames in the Inspector. Monospace keeps digits
//     visually aligned across redraws.
//
// We keep Material3's role names so widgets pick this up automatically.

private val Sans = FontFamily.Default
private val Mono = FontFamily.Monospace

val GixxerTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 44.sp, lineHeight = 48.sp),
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

/** Monospaced text styles for data — speed, distance, hex frames, timestamps. */
object GixxerMono {
    val display = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 64.sp, lineHeight = 64.sp)
    val headline = TextStyle(fontFamily = Mono, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp)
    val body = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
}

// --- Shapes ---------------------------------------------------------------

val GixxerShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
