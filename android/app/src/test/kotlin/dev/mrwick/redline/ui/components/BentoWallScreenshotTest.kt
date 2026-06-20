package dev.mrwick.redline.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mrwick.redline.ui.theme.GixxerBrand
import dev.mrwick.redline.ui.theme.GixxerMono
import dev.mrwick.redline.ui.theme.GixxerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Consolidated visual: the kit so far (BentoTile + Sweep + HeroNumeral +
 * OdometerNumber) assembled into a Home-like wall. Static render (no entry
 * animation) for a deterministic golden. Saira Condensed (HeroNumeral) falls back
 * to the system font under Robolectric; it's the real condensed face on device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h820dp-xxhdpi")
class BentoWallScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    @Test
    fun bento_wall_dark() {
        composeRule.setContent {
            GixxerTheme(darkTheme = true) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "GIXXER SF · AWAKE",
                        style = MaterialTheme.typography.labelMedium,
                        color = GixxerBrand.accent,
                    )
                    BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
                        Text("RANGE · KM", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HeroNumeral("182", fontSize = 88.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BentoTile(
                            modifier = Modifier.weight(1f).height(168.dp),
                            animateEntry = false,
                            container = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text("FUEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Sweep(
                                progress = 0.62f,
                                modifier = Modifier.size(110.dp).padding(top = 6.dp),
                                animateOnFirstComposition = false,
                            ) {
                                Text("62%", style = GixxerMono.body.copy(fontSize = 20.sp), color = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                        BentoTile(
                            modifier = Modifier.weight(1f).height(168.dp),
                            animateEntry = false,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("ODOMETER", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OdometerNumber(14982L, style = GixxerMono.display.copy(fontSize = 34.sp), color = MaterialTheme.colorScheme.onBackground)
                                Text("HEALTH", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("ALL GOOD", style = MaterialTheme.typography.titleMedium, color = GixxerBrand.success)
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
