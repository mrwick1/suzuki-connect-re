package dev.mrwick.gixxerbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.GixxerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h560dp-xxhdpi")
class TraceChartScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    private val ride = listOf(0.15f, 0.35f, 0.28f, 0.55f, 0.7f, 0.62f, 0.85f, 0.95f, 0.72f, 0.5f, 0.6f, 0.3f)

    @Test
    fun trace_variants_dark() {
        composeRule.setContent {
            GixxerTheme(darkTheme = true) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text("SPEED · TELEMETRY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TraceChart(
                        points = ride,
                        animateDraw = false,
                        strokeWidth = 4.dp,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                    Text("ROUTE · AS ART", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TraceChart(
                        points = listOf(0.4f, 0.5f, 0.45f, 0.62f, 0.58f, 0.75f, 0.7f, 0.82f),
                        animateDraw = false,
                        strokeWidth = 4.dp,
                        lineBrush = SolidColor(GixxerTokens.lushGreen),
                        areaColor = GixxerTokens.lushGreen,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
