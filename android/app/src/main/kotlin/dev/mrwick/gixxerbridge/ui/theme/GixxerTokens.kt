package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * GixxerBridge design tokens — Wave 1.
 *
 * Single source of truth for color. Every screen must reference colors
 * through this object or through MaterialTheme.colorScheme (which is
 * wired from these tokens in Theme.kt). Hardcoded Color(0xFF...) literals
 * outside ui/theme/ are forbidden by Konsist test
 * (see HardcodedHexLintTest).
 *
 * See docs/superpowers/specs/2026-05-25-ui-overhaul-design.md §"Visual system"
 * for rationale.
 */
object GixxerTokens {

    // --- Foundation: Linear-style warm-neutral dark stack -----------------
    val bg = Color(0xFF0A0A0A)
    val surface = Color(0xFF161616)
    val surfaceElevated = Color(0xFF222222)
    val border = Color(0x14FFFFFF)             // 8% white

    val textPrimary = Color(0xFFFAFAFA)
    val textMuted = Color(0xFFA1A1AA)

    // --- Brand accent: two-tier red ---------------------------------------
    /** Everyday accent: active tab, connected dot, button outlines. ~1-3% surface. */
    val accent = Color(0xFFB91C1C)             // deep cherry
    /** Hero accent. ONE use only: SpeedDisplay underline ticker. */
    val accentHero = Color(0xFFD93B25)         // Suzuki-brand red

    // --- Semantic colors: locked, not interchangeable with accents --------
    val success = Color(0xFF22C55E)
    val warning = Color(0xFFF59E0B)
    val danger = Color(0xFFEF4444)
}
