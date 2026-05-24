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
}
