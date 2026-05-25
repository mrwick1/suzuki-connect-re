package dev.mrwick.gixxerbridge.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.NavigateNext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import dev.mrwick.gixxerbridge.ui.home.components.EmptyState
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Trips list screen: one card per persisted ride, tap to open detail. */
@Composable
fun TripsScreen(
    vm: TripsViewModel,
    onOpenRide: (Long) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val rides by vm.rides.collectAsStateWithLifecycle()
    // The rides StateFlow seeds with emptyList() before Room's first emission,
    // so we can't distinguish "loading" from "loaded-empty" on the flow alone.
    // Show skeletons for a brief grace window after composition; either real
    // data arrives within it (skeletons disappear immediately) or the window
    // closes and we render the real empty state.
    val bootDone by produceState(initialValue = false) {
        delay(250); value = true
    }
    if (rides.isEmpty() && !bootDone) {
        LazyColumn(
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(3) { SkeletonCard() }
        }
        return
    }
    if (rides.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Outlined.Route,
                body = "No rides yet — take the bike for a spin.",
                ctaLabel = null,
                onCta = null,
            )
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rides, key = { it.id }) { ride ->
            RideRow(
                ride = ride,
                onClick = { onOpenRide(ride.id) },
                onDelete = { vm.delete(ride.id) },
            )
        }
    }
}

/**
 * One row in [TripsScreen]: title (date + distance) + caption (duration + avg speed).
 * Tap entire card → detail; delete icon on the right edge.
 */
@Composable
private fun RideRow(ride: RideEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val titleFmt = remember { SimpleDateFormat("d MMM", Locale.US) }
    val endMillis = ride.endedAtMillis ?: System.currentTimeMillis()
    val durationMin = (endMillis - ride.startedAtMillis) / 60_000
    val distance = max(0, (ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)
    val dateStr = titleFmt.format(Date(ride.startedAtMillis))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$dateStr · $distance km",
                    style = MaterialTheme.typography.titleSmall,
                    color = GixxerTokens.textPrimary,
                )
                Text(
                    text = "$durationMin min · ${"%.0f".format(ride.avgSpeedKmh)} km/h",
                    style = MaterialTheme.typography.bodySmall,
                    color = GixxerTokens.textMuted,
                )
            }
            // Delete is kept but less prominent; the main tap opens the ride.
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete ride",
                    tint = GixxerTokens.textMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(modifier = Modifier.size(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.NavigateNext,
                contentDescription = null,
                tint = GixxerTokens.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
