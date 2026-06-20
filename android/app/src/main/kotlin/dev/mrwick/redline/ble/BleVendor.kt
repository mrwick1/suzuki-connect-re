package dev.mrwick.redline.ble

import android.bluetooth.le.ScanResult

/**
 * Best-effort identification for BLE devices that don't advertise a Bluetooth name.
 *
 * Many phones, headphones, beacons, and trackers send empty/missing name in the
 * 31-byte advertising packet to save space. Without a name the pair UI shows raw
 * MACs ("43:CB:51:BA:A1:68"), which is confusing.
 *
 * We pull three signals from the scan record:
 *   1. Manufacturer-specific data → Bluetooth SIG company ID → vendor name
 *      (e.g. Apple, Samsung, Microsoft, Google). This is the best signal for
 *      randomized-MAC devices (phones, AirPods) where the OUI is meaningless.
 *   2. Service data UUIDs (16-bit) → BT SIG member service → vendor name
 *      (e.g. 0xFEF3 = Google Find My Device, 0xFEAA = Eddystone).
 *   3. MAC OUI (first 3 bytes) → IEEE allocation → vendor name, when the MAC
 *      is a public address. Important for our use-case because the Suzuki
 *      cluster uses a Texas Instruments OUI (74:B8:39) and broadcasts no name.
 *
 * Tables intentionally curated, not exhaustive — every entry costs a few bytes
 * in the APK and adds maintenance debt. We cover what's likely to show up
 * around a bike (rider's phone, earbuds, smartwatch, beacons) + Suzuki itself.
 */
object BleVendor {

    /** Map a [ScanResult] to a short human label, or null if we can't tell. */
    fun identify(result: ScanResult): String? {
        // 1. Manufacturer-specific data — keyed by 16-bit BT SIG company ID.
        result.scanRecord?.manufacturerSpecificData?.let { msd ->
            for (i in 0 until msd.size()) {
                val companyId = msd.keyAt(i)
                companyName(companyId)?.let { return it }
            }
        }
        // 2. Service data — keyed by ParcelUuid; we only know 16-bit member services.
        result.scanRecord?.serviceData?.keys?.forEach { uuid ->
            val short = shortUuid(uuid.toString())
            if (short != null) {
                serviceName(short)?.let { return it }
            }
        }
        // 3. Public MAC OUI.
        ouiName(result.device.address)?.let { return it }
        return null
    }

    /** Convert a 128-bit UUID string back to its 16-bit alias if it's a member service. */
    private fun shortUuid(full: String): Int? {
        // BT SIG base: 0000xxxx-0000-1000-8000-00805f9b34fb
        if (full.length < 36) return null
        if (!full.endsWith("-0000-1000-8000-00805f9b34fb", ignoreCase = true)) return null
        val hex = full.substring(4, 8)
        return runCatching { hex.toInt(16) }.getOrNull()
    }

    /** First 3 bytes of [mac] normalized to "XX:XX:XX" uppercase, or null if malformed. */
    private fun ouiOf(mac: String?): String? {
        if (mac == null || mac.length < 8) return null
        return mac.substring(0, 8).uppercase()
    }

    private fun ouiName(mac: String?): String? {
        val oui = ouiOf(mac) ?: return null
        return OUI_VENDORS[oui]
    }

    private fun companyName(companyId: Int): String? = COMPANY_IDS[companyId]

    private fun serviceName(short: Int): String? = SERVICE_UUIDS[short]

    // Bluetooth SIG "Assigned Numbers — Company Identifiers" subset.
    // Source: Bluetooth SIG specification (public).
    private val COMPANY_IDS: Map<Int, String> = mapOf(
        0x0006 to "Microsoft",
        0x0075 to "Samsung",
        0x004C to "Apple",
        0x00E0 to "Google",
        0x0087 to "Garmin",
        0x0157 to "Anhui Huami (Mi Band)",
        0x038F to "Xiaomi",
        0x0499 to "Ruuvi (sensor tag)",
        0x05A7 to "Sonos",
        0x015D to "Estimote (beacon)",
        0x0590 to "Tile (tracker)",
        0x0131 to "Cypress / Infineon",
        0x0059 to "Nordic Semiconductor",
        0x000D to "Texas Instruments",
        0x004F to "Logitech",
        0x002A to "JBL / Harman",
        0x0822 to "Realme",
        0x09A3 to "OnePlus",
        0x0A12 to "Suzuki Motor Corp",
    )

    // Bluetooth SIG "Assigned Numbers — 16-bit UUIDs for Members" subset.
    private val SERVICE_UUIDS: Map<Int, String> = mapOf(
        0xFFF0 to "Suzuki cluster",
        0xFEF3 to "Google (Fast Pair / Find My)",
        0xFE2C to "Google (Cast)",
        0xFEAA to "Eddystone beacon",
        0xFD6F to "Exposure Notification",
        0xFE9F to "Google chrome",
        0xFD5A to "Samsung",
        0xFD5F to "Oculus",
        0xFD82 to "Sony",
        0xFE03 to "Amazon",
        0xFD81 to "Microsoft",
        0xFDA0 to "Tile",
        0xFE61 to "Logitech",
        0x180F to "Battery Service",
        0x1812 to "HID (keyboard / controller)",
    )

    // IEEE OUI subset — only what we expect around the bike + Suzuki itself.
    // Format: "XX:XX:XX" uppercase.
    private val OUI_VENDORS: Map<String, String> = mapOf(
        "74:B8:39" to "Suzuki cluster (TI BLE)",
        "34:4C:60" to "Espressif (ESP32 board)",
        "30:AE:A4" to "Espressif (ESP32 board)",
        "AC:67:B2" to "Espressif (ESP32 board)",
        "F4:CF:A2" to "Espressif (ESP32 board)",
        "BC:34:00" to "Xiaomi",
        "F4:F5:DB" to "Xiaomi",
        "98:D8:63" to "Xiaomi",
        "DC:E0:99" to "Apple",
        "9C:8B:A0" to "Apple",
        "00:25:00" to "Apple",
        "F4:9D:8A" to "Apple (AirPods)",
        "78:F8:82" to "Samsung",
        "00:23:39" to "Samsung",
        "DC:71:96" to "Samsung",
        "F0:7B:CB" to "Samsung Galaxy Buds",
        "00:1A:7D" to "Garmin",
        "00:18:B4" to "JBL (Harman)",
        "AC:DE:48" to "OnePlus",
        "94:5D:65" to "Realme",
        "00:09:9B" to "Sony",
    )
}
