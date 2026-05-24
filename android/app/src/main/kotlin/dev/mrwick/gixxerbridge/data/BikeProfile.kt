package dev.mrwick.gixxerbridge.data

/**
 * Persisted identity for a paired bike.
 *
 * @property mac BLE MAC address in canonical "AA:BB:CC:DD:EE:FF" form.
 * @property name Device-advertised name (e.g. "SBM110202788").
 * @property pairedAtMillis Wall-clock epoch millis when pairing succeeded.
 */
data class BikeProfile(
    val mac: String,
    val name: String,
    val pairedAtMillis: Long,
)
