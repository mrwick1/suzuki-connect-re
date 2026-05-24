package dev.mrwick.gixxerbridge.ble

import java.util.UUID

/**
 * Constants for the Suzuki bike's BLE GATT surface.
 * Verified in Phase 1 (NOTES.md "BLE GATT tree (M1)"):
 *   - one vendor service 0xFFF0
 *   - one write characteristic 0xFFF1 (phone -> bike)
 *   - one notify characteristic 0xFFF2 (bike -> phone)
 */
object SuzukiGatt {
    val SERVICE_UUID: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val WRITE_CHAR_UUID: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val NOTIFY_CHAR_UUID: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")
    val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /**
     * Standard Bluetooth SIG Device Information Service (0x180A).
     * The bike publishes these characteristics on connect (Phase 1 NOTES.md):
     *   0x2A23 System ID, 0x2A24 Model Number, 0x2A25 Serial Number,
     *   0x2A26 Firmware Rev, 0x2A28 Software Rev, 0x2A29 Manufacturer,
     *   0x2A2A IEEE Cert, 0x2A50 PnP ID.
     */
    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAR_MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val CHAR_MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val CHAR_SERIAL_NUMBER: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val CHAR_FIRMWARE_REV: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val CHAR_HARDWARE_REV: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val CHAR_SOFTWARE_REV: UUID = UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val CHAR_SYSTEM_ID: UUID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb")
    val CHAR_IEEE_CERT: UUID = UUID.fromString("00002a2a-0000-1000-8000-00805f9b34fb")
    val CHAR_PNP_ID: UUID = UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
}
