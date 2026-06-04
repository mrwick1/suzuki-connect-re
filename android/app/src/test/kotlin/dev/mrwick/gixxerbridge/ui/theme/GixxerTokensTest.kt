package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class GixxerTokensTest {

    @Test
    fun canonical_tokens_have_expected_values() {
        assertEquals(Color(0xFF8FE03A), GixxerTokens.lushGreen)
        assertEquals(Color(0xFF0B5BD6), GixxerTokens.ecstarBlue)
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
