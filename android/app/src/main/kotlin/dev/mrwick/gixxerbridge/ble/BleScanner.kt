package dev.mrwick.gixxerbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Active BLE scanner targeting Suzuki bikes (advertise service 0xFFF0).
 *
 * Designed for the pair-wizard. Caller starts a scan, observes `results`, picks one,
 * and stops the scan. Not used during normal app operation (BleClient.connect with
 * autoConnect handles the bike-key-on lifecycle).
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val _results = MutableStateFlow<Map<String, DiscoveredBike>>(emptyMap())
    val results: StateFlow<Map<String, DiscoveredBike>> = _results.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address ?: return
            val name = result.device.name ?: result.scanRecord?.deviceName ?: "(unknown)"
            _results.update { it + (mac to DiscoveredBike(mac, name, result.rssi)) }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }
    }

    fun start() {
        _results.value = emptyMap()
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SuzukiGatt.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, callback)
    }

    fun stop() {
        adapter?.bluetoothLeScanner?.stopScan(callback)
    }

    private fun <K, V> MutableStateFlow<Map<K, V>>.update(transform: (Map<K, V>) -> Map<K, V>) {
        value = transform(value)
    }
}

/** A bike seen by the scanner. */
data class DiscoveredBike(val mac: String, val name: String, val rssi: Int)
