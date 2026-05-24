package dev.mrwick.gixxerbridge.ui.trips

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.data.RideEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Detail screen for one ride: header with aggregates + monospaced per-sample list. */
@Composable
fun TripDetailScreen(rideId: Long, vm: TripsViewModel) {
    LaunchedEffect(rideId) { vm.loadSamples(rideId) }
    val samples by vm.selectedSamples.collectAsStateWithLifecycle()
    val rides by vm.rides.collectAsStateWithLifecycle()
    val ride: RideEntity? = remember(rides, rideId) { rides.firstOrNull { it.id == rideId } }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        if (ride != null) {
            Text(
                SimpleDateFormat("EEE, MMM d yyyy · HH:mm", Locale.US).format(Date(ride.startedAtMillis)),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Max speed: ${ride.maxSpeedKmh} km/h",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Avg speed: ${"%.1f".format(ride.avgSpeedKmh)} km/h",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Samples: ${ride.sampleCount}",
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(samples, key = { it.id }) { s ->
                Text(
                    String.format(
                        Locale.US,
                        "%tT  %3d km/h  odo=%6d  A=%.1f  B=%.1f  fuel=%s  km/L=%s",
                        Date(s.tMillis),
                        s.speedKmh,
                        s.odometerKm,
                        s.tripAKm,
                        s.tripBKm,
                        s.fuelBars?.toString() ?: "-",
                        s.fuelEconKml?.let { "%.1f".format(it) } ?: "-",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
