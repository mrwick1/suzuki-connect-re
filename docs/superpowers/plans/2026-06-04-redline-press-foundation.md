# REDLINE PRESS — Foundation (Design-System Core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the discarded "Wave 1" theme (dark + red + Inter) with the REDLINE PRESS foundation — color tokens, fonts, typography, motion, and dual (dark + TARMAC light) color schemes — so the whole app instantly reskins to the new black + lush-green + Ecstar-blue identity.

**Architecture:** Rewrite the four files under `ui/theme/` **non-destructively**: every public symbol the app already references (`GixxerTokens.*`, `GixxerMono`, `GixxerBrand`, `Motion.Spring*`, `GixxerTheme`) keeps its name; only the *values* and *fonts* change, and new canonical names are added alongside. Because ~30 screens reference `GixxerTokens.*`, this reskins all of them for free with zero per-screen edits (they look transitional until their own migration plan). Saira + JetBrains Mono are bundled as variable `.ttf`; Saira Condensed + Hanken Grotesk come via the existing downloadable-Google-Fonts provider.

**Tech Stack:** Kotlin 2.1, Jetpack Compose (BOM 2026.05.00), Material3 1.3.1 (stable — no Expressive APIs), `ui-text-google-fonts`, JUnit + Robolectric + Roborazzi (screenshot), Konsist (lint). minSdk 29.

**Plan sequence (this is plan 1 of a short series):**
1. **Foundation** ← this plan (theme core; app reskins; independently testable)
2. Component kit — `Sweep`, `BentoGrid`/`BentoTile`, `OdometerNumber`, `HeroNumeral`, `TraceChart`, `HealthRing`, `SoulBike`, `ManeuverGlyph`, iconography (needs `androidx.graphics:graphics-shapes`)
3. Signature screens — Home "Living Cover", Post-ride "Ride Wrapped", Cluster "The Sweep"
4. (separate workstreams) long-tail screen rollout + per-screen analysis/research

Spec: `docs/superpowers/specs/2026-06-04-redline-press-design-system-design.md`.

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `app/src/main/kotlin/.../ui/theme/GixxerTokens.kt` | Color single-source-of-truth (canonical + legacy aliases) | Rewrite |
| `app/src/main/kotlin/.../ui/theme/GixxerFonts.kt` | Font families (Saira/SairaCondensed/Hanken/JetBrainsMono) | Rewrite |
| `app/src/main/kotlin/.../ui/theme/Theme.kt` | Typography, `GixxerMono`, color schemes, `GixxerBrand`, shapes, `GixxerTheme` | Rewrite |
| `app/src/main/kotlin/.../ui/theme/Motion.kt` | Named springs + `MotionLane` lane system | Rewrite |
| `app/src/main/res/font/saira_variable.ttf` | Bundled Saira variable font | Add (download) |
| `app/src/main/res/font/jetbrains_mono_variable.ttf` | Bundled JetBrains Mono variable font | Add (download) |
| `app/src/main/kotlin/.../MainActivity.kt:107` | Drop the obsolete `accent =` arg on `GixxerTheme` | Modify |
| `app/src/test/kotlin/.../ui/theme/GixxerTokensTest.kt` | Token value contract | Create |
| `app/src/test/kotlin/.../ui/theme/MotionTest.kt` | Spring value contract | Modify |
| `app/src/test/kotlin/.../ui/theme/ThemeSpecimenScreenshotTest.kt` | Visual contract (palette + type, dark + light) | Create |

Legacy aliases kept in `GixxerTokens`/`GixxerBrand`/`GixxerMono`/`Motion` are removed later, per-screen, as each screen migrates to canonical names. `ACCENT_PALETTE`/`accentColorFor` stay untouched (still used by the Settings accent picker; that screen is retired in a later plan).

---

## Task 1: Color tokens

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerTokens.kt`
- Test: `app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerTokensTest.kt`

- [ ] **Step 1: Write the failing test**

Create `GixxerTokensTest.kt`:

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class GixxerTokensTest {

    @Test
    fun canonical_tokens_have_expected_values() {
        assertEquals(Color(0xFF8FE03A), GixxerTokens.lushGreen)   // signature accent (bike stripe)
        assertEquals(Color(0xFF0B5BD6), GixxerTokens.ecstarBlue)  // structural
        assertEquals(Color(0xFF000308), GixxerTokens.inkBlack)
        assertEquals(Color(0xFFFF2D78), GixxerTokens.zoneHot)
        assertEquals(Color(0xFFF4F7FB), GixxerTokens.paperBg)
        assertEquals(Color(0xFF3E7D14), GixxerTokens.lushGreenLight)
    }

    @Test
    fun legacy_aliases_point_at_new_palette() {
        assertEquals(GixxerTokens.lushGreen, GixxerTokens.accent)
        assertEquals(GixxerTokens.inkBlack, GixxerTokens.bg)
        assertEquals(GixxerTokens.cockpitSurface, GixxerTokens.surface)
        assertEquals(GixxerTokens.onSurface, GixxerTokens.textPrimary)
        assertEquals(GixxerTokens.dangerWarm, GixxerTokens.danger)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*GixxerTokensTest*"`
Expected: FAIL — `lushGreen`/`inkBlack`/etc. unresolved (old token file has `bg`, `accent`, etc. but not the new names).

- [ ] **Step 3: Rewrite `GixxerTokens.kt`**

```kotlin
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
    val liverySilver = Color(0xFFC7D0DC)      // chrome sheen, secondary on-surface
    val onSurface = Color(0xFFE8EEF6)         // primary text/numerals on dark (not #FFF)
    val onSurfaceDim = Color(0xFF9FB0C8)      // secondary/caption on dark
    val hairline = Color(0x14FFFFFF)          // ~8% white separators
    val gaugeTrack = Color(0x1FFFFFFF)        // ~12% white unlit gauge track

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
    val accentHero = lushGreen
    val success = zoneCool
    val warning = zoneMid
    val danger = dangerWarm
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*GixxerTokensTest*"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerTokens.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerTokensTest.kt
git commit -m "feat(theme): REDLINE PRESS color tokens (black + lush-green + ecstar blue)"
```

---

## Task 2: Bundle fonts + font families

**Files:**
- Add: `app/src/main/res/font/saira_variable.ttf`, `app/src/main/res/font/jetbrains_mono_variable.ttf`
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerFonts.kt`

- [ ] **Step 1: Download the two variable fonts (OFL, bundled)**

```bash
cd android
mkdir -p app/src/main/res/font
curl -L -o app/src/main/res/font/saira_variable.ttf \
  "https://github.com/google/fonts/raw/main/ofl/saira/Saira%5Bwdth%2Cwght%5D.ttf"
curl -L -o app/src/main/res/font/jetbrains_mono_variable.ttf \
  "https://github.com/google/fonts/raw/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf"
```

- [ ] **Step 2: Verify the downloads are real TrueType files (not 404 HTML)**

Run: `cd android && ls -l app/src/main/res/font/ && file app/src/main/res/font/*.ttf`
Expected: each file is **> 100 KB** and `file` reports `TrueType Font data` / `OpenType`.
If a file is tiny (a few KB) it's a saved 404 page — find the correct path by browsing `https://github.com/google/fonts/tree/main/ofl/saira` (and `/ofl/jetbrainsmono`) for the exact `.ttf` filename, then re-download. `res/font` filenames must be lowercase `[a-z0-9_]`.

- [ ] **Step 3: Rewrite `GixxerFonts.kt`**

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import dev.mrwick.gixxerbridge.R
import androidx.compose.ui.text.googlefonts.Font as GoogleFontFont

/**
 * REDLINE PRESS type — Saira (70s motorsport, bundled variable) for display +
 * numerals, Saira Condensed (downloadable) for big condensed heroes, Hanken
 * Grotesk (downloadable) for body, JetBrains Mono (bundled variable) for the
 * Diagnostics / frame log. Replaces Inter + Geist Mono entirely.
 *
 * Variable axes require BUNDLED fonts (Downloadable Google Fonts ship static),
 * which is why Saira + JetBrains Mono are .ttf in res/font and the condensed +
 * body faces are downloadable. First-paint fallback is the system font, so
 * layout doesn't shift on font load.
 */
private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val SairaCondensedGF = GoogleFont("Saira Condensed")
private val HankenGF = GoogleFont("Hanken Grotesk")

/** Saira variable (bundled) — display, titles, and numerals; weight-animatable. */
val SairaFamily = FontFamily(
    Font(R.font.saira_variable, FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.saira_variable, FontWeight.W500, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.saira_variable, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.saira_variable, FontWeight.W700, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.saira_variable, FontWeight.W800, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
    Font(R.font.saira_variable, FontWeight.W900, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

/** Saira Condensed (downloadable, static) — big condensed display/masthead heroes. */
val SairaCondensedFamily = FontFamily(
    GoogleFontFont(googleFont = SairaCondensedGF, fontProvider = GoogleFontsProvider, weight = FontWeight.W700),
    GoogleFontFont(googleFont = SairaCondensedGF, fontProvider = GoogleFontsProvider, weight = FontWeight.W900),
)

/** Hanken Grotesk (downloadable) — body, lists, settings. Deliberately not Inter. */
val HankenFamily = FontFamily(
    GoogleFontFont(googleFont = HankenGF, fontProvider = GoogleFontsProvider, weight = FontWeight.W400),
    GoogleFontFont(googleFont = HankenGF, fontProvider = GoogleFontsProvider, weight = FontWeight.W500),
    GoogleFontFont(googleFont = HankenGF, fontProvider = GoogleFontsProvider, weight = FontWeight.W600),
    GoogleFontFont(googleFont = HankenGF, fontProvider = GoogleFontsProvider, weight = FontWeight.W700),
)

/** JetBrains Mono variable (bundled) — Diagnostics / frame log only. */
val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_variable, FontWeight.W400, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.jetbrains_mono_variable, FontWeight.W600, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)
```

Note: this removes `InterFamily` and `GeistMonoFamily`. They are referenced only inside `ui/theme/` (Theme.kt), which Task 3 rewrites — no other file breaks.

- [ ] **Step 4: Verify it compiles** (Theme.kt still references the old names until Task 3, so build after Task 3; here just confirm the font file resolves)

Run: `cd android && ./gradlew :app:compileDebugKotlin 2>&1 | grep -i "saira_variable\|jetbrains_mono_variable" || echo "font resources resolve"`
Expected: no "unresolved resource" error for the font ids (compile may still fail on Theme.kt's `InterFamily` — that's expected and fixed in Task 3).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/res/font/ \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/GixxerFonts.kt
git commit -m "feat(theme): bundle Saira + JetBrains Mono, add Hanken/Saira-Condensed families"
```

---

## Task 3: Typography, GixxerMono, GixxerBrand, shapes, color schemes, GixxerTheme

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Theme.kt`
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/MainActivity.kt:107`

- [ ] **Step 1: Rewrite `Theme.kt`**

```kotlin
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
// Brutal-contrast scale: one huge hero figure, supporting text small.

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
 * with tnum + slashed-zero so digits never shift as values roll. (JetBrainsMono
 * is reserved for the dev frame log; use it directly there.)
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
```

This deletes `GixxerColors`, `ACCENT_PALETTE`, and `accentColorFor` from `Theme.kt`. **`ACCENT_PALETTE`/`accentColorFor` are still used by Settings** — re-add them at the bottom of `Theme.kt` unchanged so those screens keep compiling (the accent picker is retired in a later plan):

```kotlin
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
```

- [ ] **Step 2: Update `MainActivity.kt` to drop the obsolete `accent` arg**

At `MainActivity.kt:107`, change:

```kotlin
GixxerTheme(accent = accentColorFor(accentName)) {
```
to:
```kotlin
GixxerTheme {
```

Then remove the now-unused `accentName`/`accentColorFor` references in `MainActivity` if the compiler flags them as unused (delete the line that computes `accentName` and the `import ...accentColorFor` if unused). Leave `accentColorFor` defined in Theme.kt for Settings.

- [ ] **Step 3: Verify the whole app compiles**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If an existing screen referenced `GixxerColors` (the deleted val) it will error — search `grep -rn "GixxerColors" app/src/main` and replace with `GixxerDarkColors`. (Current grep shows `GixxerColors` only in Theme.kt, so none expected.)

- [ ] **Step 4: Run the existing unit tests to confirm nothing regressed**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*GixxerTokensTest*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Theme.kt \
        android/app/src/main/kotlin/dev/mrwick/gixxerbridge/MainActivity.kt
git commit -m "feat(theme): Saira/Hanken typography, dark+TARMAC schemes, GixxerBrand intents"
```

---

## Task 4: Motion springs + motion lanes

**Files:**
- Modify: `app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Motion.kt`
- Modify: `app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/MotionTest.kt`

- [ ] **Step 1: Write the failing test** (replace `MotionTest.kt`)

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {

    @Test
    fun springSnap_values() {
        val s = Motion.SpringSnap as SpringSpec<Float>
        assertEquals(700f, s.stiffness, 0.001f)
        assertEquals(0.6f, s.dampingRatio, 0.001f)
    }

    @Test
    fun springSweep_values() {
        val s = Motion.SpringSweep as SpringSpec<Float>
        assertEquals(120f, s.stiffness, 0.001f)
        assertEquals(0.55f, s.dampingRatio, 0.001f)
    }

    @Test
    fun springBouncy_values() {
        val s = Motion.SpringBouncy as SpringSpec<Float>
        assertEquals(500f, s.stiffness, 0.001f)
        assertEquals(0.45f, s.dampingRatio, 0.001f)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*MotionTest*"`
Expected: FAIL — `Motion.SpringSnap` unresolved.

- [ ] **Step 3: Rewrite `Motion.kt`**

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * REDLINE PRESS motion language — physics, never fade. Spring-based; interrupts
 * redirect (velocity continuity). See spec §6.1. (Material3 1.3.1 has no
 * MotionScheme.expressive(); these explicit springs are the system.)
 *
 * Forbidden in implementation: Crossfade; tween() for UI state;
 * infiniteRepeatable for anything non-loading (gate ambient loops to
 * visible+connected, pause off-screen).
 */
object Motion {
    /** Most state transitions / commits. */
    val SpringSnap: AnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 700f)

    /** Ignition sweep, big reveals, needle settle. */
    val SpringSweep: AnimationSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 120f)

    /** Rewards / overshoot — gesture rewards, new records. */
    val SpringBouncy: AnimationSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 500f)
}

/**
 * Two motion lanes (spec §3). Expressive = at-rest showpiece motion; Pure =
 * Active-ride / SOS / Diagnostics, where decorative motion is suppressed for
 * glanceability. Components read [LocalMotionLane] and drop non-essential
 * animation when it is [MotionLane.Pure].
 */
enum class MotionLane { Expressive, Pure }

val LocalMotionLane = staticCompositionLocalOf { MotionLane.Expressive }
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*MotionTest*"`
Expected: PASS (3 tests).

- [ ] **Step 5: Confirm no screen referenced the old spring names**

Run: `cd android && grep -rn "SpringStandard\|SpringSoft" app/src/main || echo "no references — safe"`
Expected: "no references — safe". If any appear, replace `SpringStandard`→`Motion.SpringSnap`, `SpringSoft`→`Motion.SpringSweep` at those sites and rebuild.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/kotlin/dev/mrwick/gixxerbridge/ui/theme/Motion.kt \
        android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/MotionTest.kt
git commit -m "feat(theme): named springs (Snap/Sweep/Bouncy) + MotionLane system"
```

---

## Task 5: Theme specimen screenshot + full verification

**Files:**
- Create: `app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/ThemeSpecimenScreenshotTest.kt`

- [ ] **Step 1: Create the specimen screenshot test** (mirrors the harness in `HomeScreenshotTest.kt`)

```kotlin
package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual contract for the REDLINE PRESS foundation: palette chips + the type
 * voices, rendered in dark and TARMAC light. Proves the theme compiles, the
 * fonts resolve, and the schemes apply.
 *
 * Regenerate: ./gradlew :app:recordRoborazziDebug
 * Verify:     ./gradlew :app:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ThemeSpecimenScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    @Composable
    private fun Specimen() {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    GixxerTokens.lushGreen, GixxerTokens.ecstarBlue,
                    GixxerTokens.zoneCool, GixxerTokens.zoneMid, GixxerTokens.zoneHot,
                ).forEach { c ->
                    Spacer(
                        Modifier.size(40.dp).background(c, RoundedCornerShape(10.dp))
                    )
                }
            }
            Text("147", style = GixxerMono.display.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified.let { _ -> GixxerMono.headline.fontSize }), color = GixxerBrand.accent)
            Text("REDLINE PRESS", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground)
            Text("Every ride, printed like it mattered.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("ODO · TRIP · RANGE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Test
    fun specimen_dark() {
        composeRule.setContent { GixxerTheme(darkTheme = true) { Specimen() } }
        composeRule.onRoot().captureRoboImage()
    }

    @Test
    fun specimen_tarmac_light() {
        composeRule.setContent { GixxerTheme(darkTheme = false) { Specimen() } }
        composeRule.onRoot().captureRoboImage()
    }
}
```

(If the `GixxerMono.display.copy(...)` line draws a lint complaint, simplify the numeral line to `Text("147", style = GixxerMono.headline, color = GixxerBrand.accent)` — the goal is only to exercise the Saira numeral face.)

- [ ] **Step 2: Record the golden images**

Run: `cd android && ./gradlew :app:recordRoborazziDebug --tests "*ThemeSpecimenScreenshotTest*"`
Expected: BUILD SUCCESSFUL; two PNGs written under `app/src/test/snapshots/images/`. Open them and eyeball: black bg + green/blue/teal/amber/magenta chips in dark; paper-white bg in light; Saira condensed "REDLINE PRESS"; Hanken body line.

- [ ] **Step 3: Run the full unit-test + screenshot-verify suite**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL — `GixxerTokensTest`, `MotionTest`, the new specimen test, and the pre-existing `HomeScreenshotTest` (now rendering under the new theme — re-record it if it’s a verify test that fails on the intended reskin: `./gradlew :app:recordRoborazziDebug --tests "*HomeScreenshotTest*"`, then eyeball that Home reskinned to black+green and commit the updated golden).

- [ ] **Step 4: Confirm the Konsist hex-lint still passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "*HardcodedHexLintTest*"`
Expected: PASS (no `Color(0x…)` literals were added under `ui/home/`; all new literals live in `ui/theme/`).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/test/kotlin/dev/mrwick/gixxerbridge/ui/theme/ThemeSpecimenScreenshotTest.kt \
        android/app/src/test/snapshots/images/
git commit -m "test(theme): REDLINE PRESS specimen screenshots (dark + TARMAC) + golden refresh"
```

---

## Task 6: Build, install, and eyeball the reskinned app

**Files:** none (verification only)

- [ ] **Step 1: Assemble the debug APK**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install on the test phone (Redmi K20 Pro) if connected**

Run: `cd android && ./gradlew :app:installDebug` (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`)
Expected: success; if no device attached, skip and note it.

- [ ] **Step 3: Eyeball every screen reskinned, font loaded, no crashes**

Manually open Home, Stats, Trips, Settings, Diagnostics. Confirm: black background (`#000308`), lush-green accents where the old red was, Saira numerals on speed/odo, Hanken body text, no missing-font boxes, no crash. These screens look *transitional* (still old layouts) — that's expected; their redesign is later plans. Note anything that reads broken (not merely transitional) for the next plan.

- [ ] **Step 4: Final commit (if any eyeball fixes were needed)**

```bash
git add -A android/
git commit -m "chore(theme): foundation reskin verified on-device"
```

---

## Self-Review

**Spec coverage (spec §4–§6 = this plan's scope):**
- §4 color tokens (dark + telemetry spectrum + TARMAC light + role-swap) → Task 1 + Task 3 schemes. ✓
- §5 typography (Saira bundled + Hanken + JetBrains Mono, brutal-contrast scale, tnum+zero) → Task 2 + Task 3. ✓
- §6.1 motion (named springs, two lanes, forbidden list) → Task 4. ✓
- §6.2 shapes → Task 3 `GixxerShapes`. ✓ (Bento grid + 4-plane depth components = plan 2.)
- §6.3 iconography, The Sweep (§9), soul-bike (§7), signature screens (§8) → **deferred to plans 2 & 3** (called out in the Plan sequence). ✓
- §11 migration/enforcement (non-breaking aliases, keep Konsist) → Tasks 1/3 + Task 5 Step 4. ✓
- §12 testing (screenshot dark+light, motion test) → Tasks 4 & 5. ✓ (APCA + on-device SD855 profiling tracked for plan 3 when the cluster exists.)

**Placeholder scan:** No "TBD"/"handle edge cases"/"similar to". Every code step shows full file or exact diff. Font download has a real verification step. ✓

**Type consistency:** `GixxerTokens.lushGreen/ecstarBlue/inkBlack/zone*/paper*/lushGreenLight` defined in Task 1 and used identically in Task 3 schemes, Task 5 specimen. `GixxerMono`/`GixxerBrand`/`GixxerTypography`/`GixxerShapes` defined Task 3, used Task 5. `Motion.SpringSnap/SpringSweep/SpringBouncy` + `MotionLane`/`LocalMotionLane` defined Task 4, asserted in the same task's test. `SairaFamily/SairaCondensedFamily/HankenFamily/JetBrainsMonoFamily` defined Task 2, used Task 3. `GixxerTheme(darkTheme = …)` defined Task 3, used Task 5 + MainActivity. ✓

**Known follow-through risks (verify during execution, per no-assumptions rule):**
- Font raw URLs may move — Task 2 Step 2 verifies the bytes are real TTF.
- `HomeScreenshotTest` is a golden — Task 5 Step 3 re-records it for the intended reskin.
- `FontVariation`/bundled-`Font` overload availability on this Compose BOM — Task 3 Step 3 compile catches it; if `variationSettings` is unavailable, fall back to `Font(R.font.saira_variable, FontWeight.Wxxx)` (static weight, no axis animation) and note it.
