package dev.mrwick.gixxerbridge.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

/**
 * Wave 1 lint rule: Color(0x…) literals are forbidden in ui/home/** —
 * every color must come from MaterialTheme.colorScheme or GixxerTokens
 * (which live in ui/theme/).
 *
 * Scope is narrow on purpose. Waves 2-5 will widen the scope to all of
 * ui/** as more screens are retokenized.
 */
class HardcodedHexLintTest {

    @Test
    fun `no hardcoded hex Color literals in ui_home`() {
        Konsist.scopeFromProject()
            .files
            .withPackage("dev.mrwick.gixxerbridge.ui.home..")
            .assertFalse { file ->
                HEX_COLOR_REGEX.containsMatchIn(file.text)
            }
    }

    companion object {
        private val HEX_COLOR_REGEX = Regex("""Color\(\s*0x[0-9A-Fa-f]{6,8}""")
    }
}
