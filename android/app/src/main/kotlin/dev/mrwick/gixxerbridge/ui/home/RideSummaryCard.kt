package dev.mrwick.gixxerbridge.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.analytics.RideAnalytics
import dev.mrwick.gixxerbridge.app.AppGraph

/**
 * "Today / This week" summary on Home. Reads rides straight from [RideStore]
 * and runs the totals through [RideAnalytics.totalsFor]; the data shape is
 * small enough that a dedicated ViewModel would be over-engineering.
 *
 * Hidden entirely when there's no riding data in the last week, so the Home
 * screen doesn't show an always-zero placeholder.
 *
 * ASSUMED: `collectAsState` (rather than `collectAsStateWithLifecycle`) is fine
 * here — the underlying Room flow is cheap and other Home cards
 * ([LastParkedCard], [QuickDestinationsCard]) likewise use plain
 * `collectAsState`, so we match that convention.
 */
@Composable
fun RideSummaryCard() {
    val context = LocalContext.current
    // PERF: share the process-wide RideStore (audit finding 1.4). Also moved to
    // collectAsStateWithLifecycle so the Room flow stops emitting while the
    // host is backgrounded — saves wakeups and Compose work when the app isn't
    // visible.
    val store = remember(context) { AppGraph.rideStore(context) }
    val rides by store.observeRides().collectAsStateWithLifecycle(initialValue = emptyList())

    val today = remember(rides) { RideAnalytics.totalsFor(rides, days = 1L) }
    val week = remember(rides) { RideAnalytics.totalsFor(rides, days = 7L) }

    // Quiet when no data this week (avoid empty-state noise on a fresh install).
    if (week.km == 0 && week.rides == 0) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Riding summary", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatColumn(
                    label = "Today",
                    big = "${today.km} km",
                    sub = "${today.rides} ride${if (today.rides == 1) "" else "s"}",
                    modifier = Modifier.weight(1f),
                )
                StatColumn(
                    label = "This week",
                    big = "${week.km} km",
                    sub = "${week.rides} ride${if (week.rides == 1) "" else "s"}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatColumn(
    label: String,
    big: String,
    sub: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            big,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(sub, style = MaterialTheme.typography.bodySmall)
    }
}
