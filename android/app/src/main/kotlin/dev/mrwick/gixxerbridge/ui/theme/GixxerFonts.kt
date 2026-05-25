package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import dev.mrwick.gixxerbridge.R

/**
 * Downloadable Google Fonts — Inter (chrome) + Geist Mono (numerics).
 *
 * First-paint fallback is FontFamily.Default (Roboto) for Inter and
 * FontFamily.Monospace (Roboto Mono) for Geist Mono — both already in the
 * Android system, so layout doesn't shift on font load.
 */
private val GoogleFontsProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private val Inter = GoogleFont("Inter")
private val GeistMono = GoogleFont("Geist Mono")

val InterFamily: FontFamily = FontFamily(
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W400),
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W500),
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W600),
    Font(googleFont = Inter, fontProvider = GoogleFontsProvider, weight = FontWeight.W700),
)

val GeistMonoFamily: FontFamily = FontFamily(
    Font(googleFont = GeistMono, fontProvider = GoogleFontsProvider, weight = FontWeight.W400),
    Font(googleFont = GeistMono, fontProvider = GoogleFontsProvider, weight = FontWeight.W500),
    Font(googleFont = GeistMono, fontProvider = GoogleFontsProvider, weight = FontWeight.W700),
)
