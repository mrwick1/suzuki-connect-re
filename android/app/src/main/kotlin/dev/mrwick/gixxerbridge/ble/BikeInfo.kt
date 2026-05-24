package dev.mrwick.gixxerbridge.ble

/** Device Info Service snapshot we read from the bike on first connect. */
data class BikeInfo(
    val manufacturer: String? = null,
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val firmwareRevision: String? = null,
    val softwareRevision: String? = null,
    val hardwareRevision: String? = null,
    val systemId: String? = null,        // hex
    val pnpId: String? = null,           // hex
    val ieeeCert: String? = null,
)
