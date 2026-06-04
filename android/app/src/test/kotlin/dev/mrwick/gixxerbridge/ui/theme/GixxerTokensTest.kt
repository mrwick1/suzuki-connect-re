package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class GixxerTokensTest {

    @Test
    fun canonical_tokens_have_expected_values() {
        // base (dark)
        assertEquals(Color(0xFF000308), GixxerTokens.inkBlack)
        assertEquals(Color(0xFF0A1424), GixxerTokens.cockpitSurface)
        assertEquals(Color(0xFF13233D), GixxerTokens.cockpitSurface2)
        assertEquals(Color(0xFFC7D0DC), GixxerTokens.liverySilver)
        assertEquals(Color(0xFFE8EEF6), GixxerTokens.onSurface)
        assertEquals(Color(0xFF9FB0C8), GixxerTokens.onSurfaceDim)
        assertEquals(Color(0x14FFFFFF), GixxerTokens.hairline)
        assertEquals(Color(0x1FFFFFFF), GixxerTokens.gaugeTrack)
        // brand
        assertEquals(Color(0xFF8FE03A), GixxerTokens.lushGreen)
        assertEquals(Color(0xFF0B5BD6), GixxerTokens.ecstarBlue)
        // telemetry spectrum
        assertEquals(Color(0xFF10D9C4), GixxerTokens.zoneCool)
        assertEquals(Color(0xFFF5A524), GixxerTokens.zoneMid)
        assertEquals(Color(0xFFFF2D78), GixxerTokens.zoneHot)
        assertEquals(Color(0xFFF2542D), GixxerTokens.dangerWarm)
        // TARMAC light
        assertEquals(Color(0xFFF4F7FB), GixxerTokens.paperBg)
        assertEquals(Color(0xFFFFFFFF), GixxerTokens.paperSurface)
        assertEquals(Color(0xFFE8F0FC), GixxerTokens.paperSurfaceTint)
        assertEquals(Color(0xFF0A1424), GixxerTokens.onPaper)
        assertEquals(Color(0xFF44546B), GixxerTokens.onPaperDim)
        assertEquals(Color(0xFF3E7D14), GixxerTokens.lushGreenLight)
    }

    @Test
    fun legacy_aliases_point_at_new_palette() {
        assertEquals(GixxerTokens.inkBlack, GixxerTokens.bg)
        assertEquals(GixxerTokens.cockpitSurface, GixxerTokens.surface)
        assertEquals(GixxerTokens.cockpitSurface2, GixxerTokens.surfaceElevated)
        assertEquals(GixxerTokens.hairline, GixxerTokens.border)
        assertEquals(GixxerTokens.onSurface, GixxerTokens.textPrimary)
        assertEquals(GixxerTokens.onSurfaceDim, GixxerTokens.textMuted)
        assertEquals(GixxerTokens.lushGreen, GixxerTokens.accent)
        assertEquals(GixxerTokens.lushGreen, GixxerTokens.accentHero)
        assertEquals(GixxerTokens.zoneCool, GixxerTokens.success)
        assertEquals(GixxerTokens.zoneMid, GixxerTokens.warning)
        assertEquals(GixxerTokens.dangerWarm, GixxerTokens.danger)
    }
}
