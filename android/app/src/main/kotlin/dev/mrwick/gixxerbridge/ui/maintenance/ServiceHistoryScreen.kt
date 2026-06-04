package dev.mrwick.gixxerbridge.ui.maintenance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.data.ServiceLogEntity
import dev.mrwick.gixxerbridge.ui.components.SkeletonCard
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Service history log: a list of past maintenance entries with
 * date / odometer / type / cost / notes. FAB opens an add dialog.
 *
 * Reached from Settings → Maintenance → "Service history".
 */
@Composable
fun ServiceHistoryScreen(vm: ServiceHistoryViewModel) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    // entries StateFlow seeds with emptyList() — gate the empty state
    // behind a short grace window so first paint shows skeletons.
    val bootDone by produceState(initialValue = false) {
        delay(250); value = true
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add service") },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                "SERVICE HISTORY",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 2.dp),
            )
            if (entries.isEmpty() && !bootDone) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(3) { SkeletonCard() }
                }
            } else if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Build,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(96.dp),
                        )
                        Text(
                            "No service entries yet — tap \"Add service\" after your next visit.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { e ->
                        ServiceRow(
                            entry = e,
                            onDelete = { vm.delete(e.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddServiceDialog(
            onDismiss = { showAdd = false },
            onConfirm = { odo, type, rupees, notes ->
                vm.add(odo, type, rupees, notes)
                showAdd = false
            },
        )
    }
}

/**
 * One row in the history list. Mirrors the styling of
 * [dev.mrwick.gixxerbridge.ui.mileage.MileageScreen]'s FillRow — same
 * date format, same trash-icon delete affordance.
 */
@Composable
private fun ServiceRow(entry: ServiceLogEntity, onDelete: () -> Unit) {
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d yyyy", Locale.US) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.type,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    buildString {
                        append(dateFmt.format(Date(entry.tMillis)))
                        append(" · ${entry.odometerKm} km")
                        entry.rupees?.let { append(" · ₹${"%.0f".format(it)}") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                entry.notes?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GixxerTokens.onSurfaceDim,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete service entry")
            }
        }
    }
}

/**
 * Modal dialog for adding a service entry. Type is selected from the
 * canonical chip list or typed as a custom string when "Other" is picked.
 * Submit is disabled until odometer parses and type is non-blank.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddServiceDialog(
    onDismiss: () -> Unit,
    onConfirm: (odometerKm: Int, type: String, rupees: Double?, notes: String?) -> Unit,
) {
    var odoText by remember { mutableStateOf("") }
    var rupeesText by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(ServiceHistoryViewModel.CANONICAL_TYPES.first()) }
    var customType by remember { mutableStateOf("") }

    val odo = odoText.toIntOrNull()
    val rupees = rupeesText.toDoubleOrNull()
    val isCustom = selectedType == "Other"
    val effectiveType = if (isCustom) customType.trim() else selectedType
    val canSubmit = odo != null && odo >= 0 && effectiveType.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log service") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = odoText,
                    onValueChange = { raw -> odoText = raw.filter { it.isDigit() }.take(7) },
                    label = { Text("Odometer (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Type",
                    style = MaterialTheme.typography.labelMedium,
                    color = GixxerTokens.onSurfaceDim,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ServiceHistoryViewModel.CANONICAL_TYPES.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t) },
                        )
                    }
                }
                if (isCustom) {
                    OutlinedTextField(
                        value = customType,
                        onValueChange = { customType = it.take(40) },
                        label = { Text("Custom type") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = rupeesText,
                    onValueChange = { raw -> rupeesText = sanitizeDecimal(raw) },
                    label = { Text("Rupees (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it.take(200) },
                    label = { Text("Notes (optional)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onConfirm(odo!!, effectiveType, rupees, notes.ifBlank { null })
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Strip everything but digits and a single decimal point. Forgiving input for
 * the rupees field; duplicated from MileageScreen because making it shared
 * isn't worth a new module yet.
 */
private fun sanitizeDecimal(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered.take(7)
    val head = filtered.substring(0, firstDot + 1)
    val tail = filtered.substring(firstDot + 1).filter { it != '.' }
    return (head + tail).take(7)
}
