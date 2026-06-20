package dev.mrwick.redline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mrwick.redline.ui.theme.GixxerMono
import dev.mrwick.redline.ui.theme.GixxerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual contract for the Sweep gauge primitive: three fill levels with a center
 * numeral, rendered statically (no ignition animation) so the golden is deterministic.
 *
 * Regenerate: ./gradlew :app:recordRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h900dp-xxhdpi")
class SweepScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    @Composable
    private fun Gauge(value: Int, label: String, progress: Float) {
        Sweep(
            progress = progress,
            modifier = Modifier.size(150.dp),
            animateOnFirstComposition = false,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(value.toString(), style = GixxerMono.headline, color = MaterialTheme.colorScheme.onBackground)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Test
    fun sweep_three_levels_dark() {
        composeRule.setContent {
            GixxerTheme(darkTheme = true) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Gauge(34, "CRUISE", 0.25f)
                        Gauge(82, "KM/H", 0.60f)
                    }
                    Gauge(147, "REDLINE", 0.95f)
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
