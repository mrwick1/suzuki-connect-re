package dev.mrwick.gixxerbridge.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ui.home.components.ConnectionDot
import dev.mrwick.gixxerbridge.ui.home.components.QuickActionsRow
import dev.mrwick.gixxerbridge.ui.home.components.TodayHeroCard
import dev.mrwick.gixxerbridge.ui.theme.GixxerTheme
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden snapshot of the Wave 1 Home composition.
 *
 * Renders the three zones directly (ConnectionDot + TodayHeroCard + QuickActionsRow)
 * rather than the full HomeScreen — the latter needs a ViewModel + DataStore +
 * RideStore + AppGraph, which is over-scoped for a visual-contract guard.
 *
 * Regenerate goldens with: ./gradlew :app:recordRoborazziDebug
 * Verify with:             ./gradlew :app:verifyRoborazziDebug
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class HomeScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    @Test
    fun home_three_zones_default_state() {
        composeRule.setContent {
            GixxerTheme {
                Column(
                    modifier = Modifier
                        .background(GixxerTokens.bg)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionDot(state = ConnectionState.Ready)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Hi, Arjun",
                            color = GixxerTokens.textPrimary,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    TodayHeroCard(
                        todayKm = 12.4,
                        streakDays = 3,
                        nextServiceLabel = "Air filter",
                        nextServiceDueIn = "1240 km",
                        nextServiceOverdue = false,
                    )
                    QuickActionsRow(onStartRide = {}, onOpenNav = {}, onOpenPairing = {})
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
