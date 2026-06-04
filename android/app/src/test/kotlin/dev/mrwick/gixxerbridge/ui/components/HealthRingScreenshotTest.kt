package dev.mrwick.gixxerbridge.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mrwick.gixxerbridge.ui.theme.GixxerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h400dp-xxhdpi")
class HealthRingScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    @Test
    fun health_states_dark() {
        composeRule.setContent {
            GixxerTheme(darkTheme = true) {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    listOf(
                        HealthState.Good to "GOOD",
                        HealthState.Caution to "CAUTION",
                        HealthState.Fault to "FAULT",
                    ).forEach { (state, label) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            HealthRing(state = state, modifier = Modifier.size(96.dp), animate = false)
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
