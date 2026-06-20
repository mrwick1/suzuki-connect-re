package dev.mrwick.redline.ui.about

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Settings-page link to the AboutScreen route. */
@Composable
fun AboutCardLink(onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(modifier = Modifier.padding(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("About REDLINE", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Version, build, BLE protocol summary, credits.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(">", style = MaterialTheme.typography.titleLarge)
        }
    }
}
