package dev.mrwick.gixxerbridge.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.location.LastParkedTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Find my bike" card on Home. Shows the location captured at the last disconnect,
 * with a one-tap "Open in Maps" button. Quiet when no snapshot exists yet.
 *
 * Product framing: solves the common "I parked somewhere big and forgot exactly
 * where" problem. The phone's GPS at the moment of disconnect is a good proxy
 * for the bike's parking spot — the phone is usually within a few metres of the
 * bike when the rider turns the key off.
 */
@Composable
fun LastParkedCard() {
    val context = LocalContext.current
    val tracker = remember { LastParkedTracker(context.applicationContext) }
    val parked by tracker.lastParked.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val p = parked ?: return

    val whenStr = remember(p.tMillis) {
        SimpleDateFormat("EEE, MMM d · HH:mm", Locale.US).format(Date(p.tMillis))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Bike was last parked", style = MaterialTheme.typography.labelLarge)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(whenStr, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${"%.5f".format(p.lat)}, ${"%.5f".format(p.lng)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(p.mapsUrl()))
                    context.startActivity(intent)
                }) { Text("Open in Maps") }
                OutlinedButton(onClick = { scope.launch { tracker.clear() } }) {
                    Text("Found it")
                }
            }
        }
    }
}
