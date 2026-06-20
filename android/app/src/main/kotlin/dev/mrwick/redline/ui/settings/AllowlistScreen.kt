package dev.mrwick.redline.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Allowlist editor: per-installed-app toggle of which notifications mirror to the cluster. */
@Composable
fun AllowlistScreen(vm: AllowlistViewModel) {
    val allowed by vm.allowed.collectAsStateWithLifecycle()
    val installed by vm.installed.collectAsStateWithLifecycle()
    LazyColumn(contentPadding = PaddingValues(8.dp)) {
        items(installed, key = { it.pkg }) { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                    Text(app.pkg, style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = app.pkg in allowed,
                    onCheckedChange = { vm.toggle(app.pkg) },
                )
            }
            HorizontalDivider()
        }
    }
}
