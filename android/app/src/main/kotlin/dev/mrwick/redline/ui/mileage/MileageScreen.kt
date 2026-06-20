package dev.mrwick.redline.ui.mileage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.mrwick.redline.ui.theme.GixxerTokens
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.redline.data.FuelFillEntity
import dev.mrwick.redline.ui.components.BentoTile
import dev.mrwick.redline.ui.components.HeroNumeral
import dev.mrwick.redline.ui.components.SkeletonCard
import dev.mrwick.redline.ui.home.components.EmptyState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manual fuel-fill log. Big "Average km/L" card on top, list of past fills
 * below, FAB to add a new fill.
 *
 * "True mileage" here is rider-recorded ground truth — the bike's reported
 * fuel economy uses a black-box formula and tends to be optimistic.
 */
@Composable
fun MileageScreen(vm: MileageViewModel) {
    val fills by vm.fills.collectAsStateWithLifecycle()
    val avg by vm.averageKmPerL.collectAsStateWithLifecycle()
    val perTank by vm.perTank.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    // fills StateFlow seeds with emptyList() — gate the empty-state UI behind
    // a short grace window so first-paint shows skeletons instead of "no fills".
    val bootDone by produceState(initialValue = false) {
        delay(250); value = true
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Fill up") },
                containerColor = GixxerTokens.accent,
                contentColor = GixxerTokens.inkBlack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                "TRUE MILEAGE",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 2.dp),
            )
            AverageCard(avg)
            if (fills.isEmpty() && !bootDone) {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(3) { SkeletonCard() }
                }
            } else if (fills.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        icon = Icons.Outlined.LocalGasStation,
                        body = "No fills logged yet — tap \"Fill up\" after your next pump visit.",
                        ctaLabel = null,
                        onCta = null,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(fills, key = { it.id }) { f ->
                        FillRow(
                            fill = f,
                            kmPerL = perTank[f.id],
                            onDelete = { vm.delete(f.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAdd) {
        // Fetch the best odometer (live telemetry, else last ride) once when the
        // dialog opens; null until it resolves, then the field populates.
        val initialOdo by produceState<Int?>(initialValue = null) { value = vm.currentOdometer() }
        AddFillDialog(
            initialOdo = initialOdo,
            onDismiss = { showAdd = false },
            onConfirm = { odo, litres, rupees, note ->
                vm.addFill(odo, litres, rupees, note)
                showAdd = false
            },
        )
    }
}

/** Big km/L hero tile. Renders an em-dash when the trailing average is null. */
@Composable
private fun AverageCard(avg: Double?) {
    BentoTile(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        animateEntry = false,
        contentPadding = PaddingValues(20.dp),
    ) {
        Text(
            "LAST ${MileageViewModel.TRAILING_COUNT} TANKS · KM/L",
            style = MaterialTheme.typography.labelMedium,
            color = GixxerTokens.accent,
        )
        Spacer(modifier = Modifier.height(8.dp))
        HeroNumeral(
            text = avg?.let { "%.1f".format(it) } ?: "—",
            color = GixxerTokens.onSurface,
            fontSize = 56.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Your rider-recorded true mileage",
            style = MaterialTheme.typography.labelSmall,
            color = GixxerTokens.onSurfaceDim,
        )
    }
}

/**
 * One row per fill. Long-press the row to delete (matches existing
 * [dev.mrwick.redline.ui.trips.TripsScreen] interaction style — an
 * explicit trash icon — rather than a swipe, since Compose's swipe-to-dismiss
 * still has rough edges).
 */
@Composable
private fun FillRow(
    fill: FuelFillEntity,
    kmPerL: Double?,
    onDelete: () -> Unit,
) {
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d · HH:mm", Locale.US) }
    BentoTile(Modifier.fillMaxWidth(), animateEntry = false) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    dateFmt.format(Date(fill.tMillis)),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    buildString {
                        append("${fill.odometerKm} km · ${"%.2f".format(fill.litres)} L")
                        fill.rupees?.let { append(" · ₹${"%.0f".format(it)}") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    kmPerL?.let { "this tank: %.1f km/L".format(it) }
                        ?: "this tank: — (no prior fill)",
                    style = MaterialTheme.typography.labelSmall,
                    color = GixxerTokens.onSurfaceDim,
                )
                fill.note?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = GixxerTokens.onSurfaceDim,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete fill")
            }
        }
    }
}

/**
 * Modal dialog for logging a fuel fill. Odometer is pre-filled from
 * [initialOdo] (live telemetry or last ride) and stays editable. Litres, total
 * price, and odometer are required; note is optional. Submit enables once
 * odometer >= 0, litres > 0, and total price > 0.
 */
@Composable
private fun AddFillDialog(
    initialOdo: Int?,
    onDismiss: () -> Unit,
    onConfirm: (odometerKm: Int, litres: Double, rupees: Double?, note: String?) -> Unit,
) {
    // Re-key on initialOdo so the field populates when the async lookup resolves.
    var odoText by remember(initialOdo) { mutableStateOf(initialOdo?.toString() ?: "") }
    var litresText by remember { mutableStateOf("") }
    var priceText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    val odo = odoText.toIntOrNull()
    val litres = litresText.toDoubleOrNull()
    val price = priceText.toDoubleOrNull()
    val canSubmit = odo != null && odo >= 0 && litres != null && litres > 0.0 &&
        price != null && price > 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log fuel fill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = litresText,
                    onValueChange = { raw -> litresText = sanitizeDecimal(raw) },
                    label = { Text("Litres") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = priceText,
                    onValueChange = { raw -> priceText = sanitizeDecimal(raw) },
                    label = { Text("Total price (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = odoText,
                    onValueChange = { raw -> odoText = raw.filter { it.isDigit() }.take(7) },
                    label = { Text("Odometer (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(120) },
                    label = { Text("Note (optional)") },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = { onConfirm(odo!!, litres!!, price, note.ifBlank { null }) },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Strip everything but digits and a single decimal point. Keeps the input
 * field forgiving (rider may type quickly at the pump) without letting
 * arbitrary characters reach [String.toDoubleOrNull].
 */
private fun sanitizeDecimal(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == '.' }
    val firstDot = filtered.indexOf('.')
    if (firstDot < 0) return filtered.take(7)
    // Keep only the first '.'
    val head = filtered.substring(0, firstDot + 1)
    val tail = filtered.substring(firstDot + 1).filter { it != '.' }
    return (head + tail).take(7)
}
