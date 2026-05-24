package dev.mrwick.gixxerbridge.ui.trips

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.data.RideEntity
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/** Trips list screen: one card per persisted ride, tap to open detail, swipe-less delete. */
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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsBike,
                    contentDescription = null,
                    tint = Color(0xFF334155),
                    modifier = Modifier.size(96.dp),
                )
                Text(
                    "No rides yet — take the bike for a spin.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF94A3B8),
                )
                TextButton(onClick = onOpenSettings) {
                    Text("or enable Demo mode to explore the app")
                }
            }
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

/** One row in [TripsScreen]: title (auto-name or date) + duration/distance/avg-speed summary + delete icon. */
@Composable
private fun RideRow(ride: RideEntity, onClick: () -> Unit, onDelete: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.US) }
    val endMillis = ride.endedAtMillis ?: System.currentTimeMillis()
    val durationMin = (endMillis - ride.startedAtMillis) / 60_000
    val distance = max(0, (ride.endOdoKm ?: ride.startOdoKm) - ride.startOdoKm)
    val formattedDate = dateFmt.format(Date(ride.startedAtMillis))
    // Show the auto-name (or user override) as the row title; fall back to
    // the date string for legacy rides that pre-date the naming feature.
    val title = ride.name?.takeIf { it.isNotBlank() } ?: formattedDate
    val hasName = title != formattedDate
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (hasName) {
                    Text(
                        formattedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8),
                    )
                }
                Text(
                    "$durationMin min · $distance km · avg ${"%.0f".format(ride.avgSpeedKmh)} km/h",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete ride")
            }
        }
    }
}
