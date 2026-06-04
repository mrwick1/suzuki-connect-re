package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
 * Note: downloadable fonts (Hanken, Saira Condensed) fall back to the system
 * font under Robolectric (no Play Services in the test JVM) — expected. The
 * bundled Saira numerals + the color schemes are what this guards.
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
                    Spacer(Modifier.size(40.dp).background(c, RoundedCornerShape(10.dp)))
                }
            }
            Text("147", style = GixxerMono.headline, color = GixxerBrand.accent)
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
