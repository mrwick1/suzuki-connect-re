package dev.mrwick.redline.ui.trips.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.mrwick.redline.ui.theme.GixxerTokens

/** Subtle connector shown between two consecutive trip rows of the same
 *  short-gap run, e.g. "⋮ 15 min later". */
@Composable
fun GapConnector(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(start = 40.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("⋮", style = MaterialTheme.typography.bodySmall, color = GixxerTokens.accent)
        Text(label, style = MaterialTheme.typography.labelSmall, color = GixxerTokens.textMuted)
    }
}
