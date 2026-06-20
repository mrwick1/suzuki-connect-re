package dev.mrwick.redline.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import dev.mrwick.redline.analytics.PersonalBests
import dev.mrwick.redline.analytics.StreakInfo
import dev.mrwick.redline.analytics.WeeklyTotal
import dev.mrwick.redline.ui.components.HeroNumeral
import dev.mrwick.redline.ui.theme.GixxerBrand
import dev.mrwick.redline.ui.theme.GixxerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h1100dp-xxhdpi")
class StatsOverviewScreenshotTest {

    @get:Rule val composeRule = createComposeRule()
    @get:Rule val roborazziRule = RoborazziRule(
        composeRule = composeRule,
        captureRoot = composeRule.onRoot(),
    )

    @Test
    fun stats_overview_rich_dark() {
        composeRule.setContent {
            GixxerTheme(darkTheme = true) {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column {
                        Text("STATS · THIS YEAR", style = MaterialTheme.typography.labelMedium, color = GixxerBrand.accent)
                        HeroNumeral("4280", color = MaterialTheme.colorScheme.onBackground, fontSize = 64.sp)
                        Text("KM RIDDEN", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatsOverview(
                        lifetime = WeeklyTotal(km = 12480, hours = 318.0, rides = 214),
                        streak = StreakInfo(current = 6, longest = 23),
                        bests = PersonalBests(longestRideKm = 142, topSpeedKmh = 118, bestFuelEconKml = 54.0, mostRidesInDay = 5),
                        avgPerRideKm = 58.0,
                        lifetimeAvgSpeed = 42.0,
                        ridesThisMonth = 18,
                    )
                }
            }
        }
        composeRule.onRoot().captureRoboImage()
    }
}
