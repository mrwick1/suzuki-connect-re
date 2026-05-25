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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * GixxerBridge theme — Wave 1 of the May 2026 UI overhaul.
 *
 * Colors flow from [GixxerTokens]. The `accent` parameter overrides the
 * primary slot (kept for the user-pickable accent in Settings until the
 * Wave 4 settings split removes it).
 *
 * Dark theme only — a matte black UI saves OLED power and reads well in
 * sunlight, which matters for a rider glancing at the phone.
 */
@Composable
fun GixxerTheme(
    accent: Color = GixxerTokens.accent,
    content: @Composable () -> Unit,
) {
    val scheme = GixxerColors.copy(
        primary = accent,
        primaryContainer = accent.copy(alpha = 0.7f),
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = GixxerTypography,
        shapes = GixxerShapes,
    ) {
        CompositionLocalProvider(content = content)
    }
}

/**
 * Named accent palette the user can pick in Settings. Wave 1 keeps the
 * accent picker working but the design system defaults to GixxerTokens.accent.
 */
val ACCENT_PALETTE: Map<String, Color> = linkedMapOf(
    "red" to GixxerTokens.accent,
    "cyan" to Color(0xFF22D3EE),
    "amber" to Color(0xFFFBBF24),
    "magenta" to Color(0xFFEC4899),
    "green" to Color(0xFF10B981),
    "orange" to Color(0xFFFB923C),
)

fun accentColorFor(name: String?): Color =
    ACCENT_PALETTE[name] ?: ACCENT_PALETTE.getValue("red")

// --- Color scheme: wired from GixxerTokens ---------------------------------

val GixxerColors = darkColorScheme(
    primary = GixxerTokens.accent,
    onPrimary = GixxerTokens.textPrimary,
    primaryContainer = GixxerTokens.accent.copy(alpha = 0.7f),
    onPrimaryContainer = GixxerTokens.textPrimary,
    secondary = GixxerTokens.warning,
    onSecondary = GixxerTokens.bg,
    tertiary = GixxerTokens.success,
    onTertiary = GixxerTokens.bg,
    error = GixxerTokens.danger,
    onError = GixxerTokens.textPrimary,
    background = GixxerTokens.bg,
    onBackground = GixxerTokens.textPrimary,
    surface = GixxerTokens.surface,
    onSurface = GixxerTokens.textPrimary,
    surfaceVariant = GixxerTokens.surfaceElevated,
    onSurfaceVariant = GixxerTokens.textMuted,
    surfaceContainer = GixxerTokens.surface,
    surfaceContainerHigh = GixxerTokens.surfaceElevated,
    surfaceContainerHighest = GixxerTokens.surfaceElevated,
    surfaceContainerLow = GixxerTokens.bg,
    outline = GixxerTokens.border,
    outlineVariant = GixxerTokens.border,
    inverseSurface = GixxerTokens.textPrimary,
    inverseOnSurface = GixxerTokens.bg,
)

/**
 * Brand-name aliases for places that need a color by intent rather than role.
 * Prefer MaterialTheme.colorScheme.* when possible; this is for the few
 * cluster-style flourishes that want explicit semantics.
 */
object GixxerBrand {
    val accent = GixxerTokens.accent
    val accentHero = GixxerTokens.accentHero
    val warning = GixxerTokens.warning
    val danger = GixxerTokens.danger
    val success = GixxerTokens.success
    val textSubtle = GixxerTokens.textMuted
}

// --- Typography: Inter + Geist Mono via Google Fonts ------------------------
//
// Type scale: opinionated weights (Airbnb 2025 modest 500/600 rather than
// heavy 700/800 except the speed display). Tabular numerics on every
// monospaced style — required to stop the layout jitter on per-frame
// value changes.

val GixxerTypography = Typography(
    displayLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 48.sp, lineHeight = 52.sp),
    displayMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 36.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 28.sp, lineHeight = 32.sp),

    headlineLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 24.sp, lineHeight = 28.sp),
    headlineMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 20.sp, lineHeight = 24.sp),
    headlineSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 18.sp, lineHeight = 22.sp),

    titleLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W600, fontSize = 20.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 20.sp),
    titleSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 18.sp),

    bodyLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W400, fontSize = 12.sp, lineHeight = 16.sp),

    labelLarge = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 13.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

/**
 * Monospaced text styles for live numerics — speed, odo, trip km, fuel
 * range, hex frames. Every style includes `tnum` so digits never shift
 * horizontally as values change.
 */
object GixxerMono {
    /** 144 sp Geist Mono — single use: SpeedDisplay. */
    val display = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W700, fontSize = 144.sp, lineHeight = 144.sp, fontFeatureSettings = "tnum")
    /** Post-ride summary numbers, large data values. */
    val headline = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W500, fontSize = 32.sp, lineHeight = 36.sp, fontFeatureSettings = "tnum")
    /** Odo, trip km, fuel range — most data lines. */
    val body = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    /** Caption-sized numerics — sub-text in cards. */
    val label = TextStyle(fontFamily = GeistMonoFamily, fontWeight = FontWeight.W500, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")
}

// --- Shapes: 8 / 12 / 16 / 28 (chips / small / cards / sheets) -------------

val GixxerShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
