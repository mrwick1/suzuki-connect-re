package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * GixxerBridge design tokens — REDLINE PRESS (2026-06-04 redesign).
 * Single source of truth for color. Hardcoded Color(0x…) outside ui/theme/ is
 * forbidden by Konsist (HardcodedHexLintTest).
 *
 * Palette = the owner's real bike: Glass Sparkle Black + Metallic Lush Green,
 * with Ecstar blue as a structural second voice, over a teal→amber→magenta
 * telemetry spectrum where color = data. See
 * docs/superpowers/specs/2026-06-04-redline-press-design-system-design.md §4.
 *
 * Legacy Wave-1 names (bg, surface, accent, …) are kept as ALIASES on the new
 * palette so existing screens reskin with zero edits; remove them per-screen as
 * each migrates to the canonical names.
 */
object GixxerTokens {

    // --- Canonical: base (dark) ------------------------------------------
    val inkBlack = Color(0xFF000308)          // app background — OLED-true, blue-biased
    val cockpitSurface = Color(0xFF0A1424)    // surface plane 1 (bento tiles)
    val cockpitSurface2 = Color(0xFF13233D)   // surface plane 2 (raised tiles / sheets)
    val liverySilver = Color(0xFFC7D0DC)      // chrome sheen, secondary on-surface Reserved for Wave-2 cluster/gauge components.
    val onSurface = Color(0xFFE8EEF6)         // primary text/numerals on dark (not #FFF)
    val onSurfaceDim = Color(0xFF9FB0C8)      // secondary/caption on dark
    val hairline = Color(0x14FFFFFF)          // ~8% white separators
    val gaugeTrack = Color(0x1FFFFFFF)        // ~12% white unlit gauge track Reserved for Wave-2 cluster/gauge components.

    // --- Canonical: brand -------------------------------------------------
    /** Signature accent = the bike's Metallic Lush Green stripe. ONE rationed
     *  moment per screen. Placeholder hex; tune to a real bike photo when the
     *  soul-bike vector is authored. */
    val lushGreen = Color(0xFF8FE03A)
    /** Structural / secondary brand. Nav, primary-action surfaces, rim-light. */
    val ecstarBlue = Color(0xFF0B5BD6)

    // --- Canonical: telemetry spectrum (color = data) --------------------
    val zoneCool = Color(0xFF10D9C4)          // cruise / eco / healthy / low
    val zoneMid = Color(0xFFF5A524)           // caution / transitional / mid
    val zoneHot = Color(0xFFFF2D78)           // hot / strain / near-redline / max
    val dangerWarm = Color(0xFFF2542D)        // faults / SOS — never #B91C1C

    // --- Canonical: TARMAC light mode ------------------------------------
    val paperBg = Color(0xFFF4F7FB)
    val paperSurface = Color(0xFFFFFFFF)
    val paperSurfaceTint = Color(0xFFE8F0FC)
    val onPaper = Color(0xFF0A1424)
    val onPaperDim = Color(0xFF44546B)
    val lushGreenLight = Color(0xFF3E7D14)    // green role-swap, legible on white

    // --- Legacy aliases (Wave-1 names → new palette) ---------------------
    val bg = inkBlack
    val surface = cockpitSurface
    val surfaceElevated = cockpitSurface2
    val border = hairline
    val textPrimary = onSurface
    val textMuted = onSurfaceDim
    val accent = lushGreen
    // accentHero was a distinct rationed hero color in Wave-1; collapsed to the
    // same accent transitionally — restore a dedicated token during per-screen
    // migration (spec §6) if the hero needs to diverge from the everyday accent.
    val accentHero = lushGreen
    val success = zoneCool
    val warning = zoneMid
    val danger = dangerWarm
}
