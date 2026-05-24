package dev.mrwick.gixxerbridge.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** First-run + re-pair wizard: scans for Suzuki bikes and saves the picked MAC. */
@Composable
fun PairingScreen(vm: PairingViewModel, onPaired: () -> Unit) {
    val results by vm.results.collectAsStateWithLifecycle()
    val bikes = results.values.sortedByDescending { it.rssi }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Text("Scanning for bikes...", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))
        if (bikes.isEmpty()) {
            Text(
                "No bikes found yet. Make sure the bike's key is ON.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn {
            items(bikes, key = { it.mac }) { bike ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { vm.pickBike(bike, onPaired) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(bike.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${bike.mac}  · RSSI ${bike.rssi}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
