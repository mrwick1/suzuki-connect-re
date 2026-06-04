@file:OptIn(ExperimentalTextApi::class)

package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
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
 * The bundled variable fonts set the wght axis explicitly via FontVariation so
 * each weight instances correctly (and can be animated later). Variable axes
 * require BUNDLED fonts — Downloadable Google Fonts ship static — so the
 * condensed + body faces are downloadable. First-paint fallback is the system font.
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

// --- Temporary back-compat aliases (removed in Task 3 when Theme.kt is rewritten,
// the only remaining caller). Keep the build green after this task. ---
val InterFamily: FontFamily = HankenFamily
val GeistMonoFamily: FontFamily = SairaFamily
