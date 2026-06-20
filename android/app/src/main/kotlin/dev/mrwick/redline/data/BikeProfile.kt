package dev.mrwick.redline.data

import androidx.compose.runtime.Immutable

/**
 * Persisted identity for a paired bike.
 *
 * @property mac BLE MAC address in canonical "AA:BB:CC:DD:EE:FF" form.
 * @property name Device-advertised name (e.g. "SBXXXXXXXXXX").
 * @property pairedAtMillis Wall-clock epoch millis when pairing succeeded.
 */
@Immutable
data class BikeProfile(
    val mac: String,
    val name: String,
    val pairedAtMillis: Long,
)
