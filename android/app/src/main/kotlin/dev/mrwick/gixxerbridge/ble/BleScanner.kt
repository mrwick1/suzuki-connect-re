package dev.mrwick.gixxerbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import dev.mrwick.gixxerbridge.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Active BLE scanner targeting Suzuki bikes (advertise service 0xFFF0).
 *
 * Designed for the pair-wizard. Caller starts a scan, observes `results`, picks one,
 * and stops the scan. Not used during normal app operation (BleClient.connect with
 * autoConnect handles the bike-key-on lifecycle).
 *
 * Devices that don't advertise a name (most phones / earbuds / beacons do not, because
 * the 31-byte adv packet fills up fast) get a best-effort label from [BleVendor] —
 * "Samsung", "Google (Fast Pair)", "Suzuki cluster (TI BLE)", etc. — derived from
 * manufacturer-specific data, service UUIDs, or the MAC OUI. Already-bonded devices
 * are added on start() so the user can re-pair a previously known bike even when
 * the bike isn't actively advertising.
 */
@SuppressLint("MissingPermission")
class BleScanner(context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    // Persistent diagnostic file capturing every SBM* (Suzuki cluster) device we see.
    // Used to empirically check whether the cluster's BLE MAC stays stable across
    // power cycles (DISCOVERIES.md 2026-05-25 — H1 vs H2). One JSONL line per
    // discovery event; capped at ~MAX_CLUSTER_HISTORY entries with simple
    // rewrite-on-overflow (oldest dropped).
    private val clusterHistoryFile: File by lazy {
        File(context.filesDir, "diag").apply { mkdirs() }
            .let { File(it, "cluster-mac-history.jsonl") }
    }

    private val _results = MutableStateFlow<Map<String, DiscoveredBike>>(emptyMap())
    val results: StateFlow<Map<String, DiscoveredBike>> = _results.asStateFlow()

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address ?: return
            val rawName = result.device.name ?: result.scanRecord?.deviceName
            val vendor = BleVendor.identify(result)
            val bonded = result.device.bondState == BluetoothDevice.BOND_BONDED
            val prev = _results.value[mac]
            val isNew = prev == null
            val bike = DiscoveredBike(
                mac = mac,
                name = rawName,
                vendor = vendor,
                rssi = result.rssi,
                bonded = bonded,
            )
            // RSSI updates every scan; only log once-per-device for noise control.
            _results.update { it + (mac to bike) }
            if (isNew) {
                AppLog.i(
                    "BleScanner",
                    "new device: $mac name=${rawName ?: "(no adv name)"} vendor=${vendor ?: "?"} bonded=$bonded rssi=${result.rssi}",
                )
                // Every fresh sighting of a Suzuki cluster (name SBM*) goes into the
                // diag history file so we can spot MAC rotation across power cycles
                // by examining several lines for the same serial. RSSI dedup is
                // already handled by isNew above (same MAC won't fire twice in the
                // same scan session); separate scans WILL re-emit, which is the
                // whole point — we want one row per scan/power-cycle.
                if (rawName != null && rawName.startsWith("SBM")) {
                    recordClusterSighting(rawName, mac, result.rssi)
                }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.e("BleScanner", "onScanFailed errorCode=$errorCode")
        }
    }

    fun start() {
        _results.value = emptyMap()
        // Seed with bonded LE devices first — they have OS-cached names ("SBM xxx") and
        // can be picked even when the bike isn't actively advertising right now.
        seedBonded()
        val scanner = adapter?.bluetoothLeScanner ?: run {
            AppLog.w("BleScanner", "BluetoothLeScanner null — adapter=${adapter} (BT off?)")
            return
        }
        // NO filter: the Suzuki cluster doesn't seem to include service UUID 0xFFF0
        // in its advertisement packet (limited adv space; service UUIDs typically
        // land in the scan response which a SERVICE filter doesn't see on all
        // ROMs). Without the filter we get every nearby BLE device; the UI sorts
        // by "looks like a Suzuki" (SBM-prefixed name or 74:B8:39 OUI) and RSSI.
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val ok = try {
            scanner.startScan(null, settings, callback)
            true
        } catch (t: Throwable) {
            AppLog.e("BleScanner", "startScan threw", t)
            false
        }
        AppLog.i("BleScanner", "startScan ok=$ok adapter=${adapter?.address} state=${adapter?.state} mode=LOW_LATENCY filter=none bonded-seeded=${_results.value.size}")
    }

    private fun seedBonded() {
        val bonded = adapter?.bondedDevices ?: return
        val seeded = mutableMapOf<String, DiscoveredBike>()
        for (d in bonded) {
            // type can be CLASSIC, LE, DUAL, or UNKNOWN — we only care about LE-capable ones.
            if (d.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue
            val mac = d.address ?: continue
            val name = d.name
            val vendor = if (mac.length >= 8) {
                BleVendor::class.java.let {
                    // Cheap OUI-only path (no ScanResult yet for bonded devices).
                    val oui = mac.substring(0, 8).uppercase()
                    BleVendorPublic.ouiVendor(oui)
                }
            } else null
            seeded[mac] = DiscoveredBike(
                mac = mac,
                name = name,
                vendor = vendor,
                rssi = Int.MIN_VALUE,  // unknown — sentinel handled in UI
                bonded = true,
            )
        }
        if (seeded.isNotEmpty()) {
            _results.value = seeded
            AppLog.i("BleScanner", "seeded ${seeded.size} bonded LE device(s) on scan start")
        }
    }

    fun stop() {
        adapter?.bluetoothLeScanner?.stopScan(callback)
        AppLog.i("BleScanner", "stopScan (saw ${_results.value.size} devices)")
    }

    /**
     * Append one JSONL row to [clusterHistoryFile] for a freshly-discovered
     * Suzuki cluster (advertised name `SBM*`). The file is intended to be
     * pulled by:
     *
     *   adb shell run-as dev.mrwick.gixxerbridge.debug cat files/diag/cluster-mac-history.jsonl
     *
     * across multiple power cycles to confirm whether a given SBM serial
     * always reports the same MAC (DISCOVERIES.md 2026-05-25 H1 vs H2).
     *
     * Format (single line, no nested objects):
     *   {"t":"2026-05-25T09:24:05.123Z","name":"SBM110202788","mac":"74:B8:39:54:DA:F1","rssi":-50}
     *
     * Caps the file at [MAX_CLUSTER_HISTORY] entries: on overflow we drop the
     * oldest N lines so the file size stays bounded. Hand-rolled JSON encoding
     * (no org.json import) to keep the dependency surface flat — the format
     * is fixed and the fields are sanitised below.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun recordClusterSighting(name: String, mac: String, rssi: Int) {
        try {
            val tsFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val ts = tsFmt.format(Date())
            // Conservative sanitisation: BLE names can contain arbitrary bytes,
            // so strip anything that would break JSON. MAC + ts are formatted by us.
            val safeName = name.filter { c -> c != '"' && c != '\\' && c.code in 0x20..0x7E }
            val line = """{"t":"$ts","name":"$safeName","mac":"$mac","rssi":$rssi}""" + "\n"

            val file = clusterHistoryFile
            // Append first, then prune. Cheaper than reading every write.
            file.appendText(line)
            // Cheap, infrequent prune: only check on every Nth append in expectation,
            // but the file is bounded so we just always read+rewrite when over cap.
            // O(MAX_CLUSTER_HISTORY) per write — fine at human scan rates.
            val all = file.readLines()
            if (all.size > MAX_CLUSTER_HISTORY) {
                val kept = all.takeLast(MAX_CLUSTER_HISTORY)
                file.writeText(kept.joinToString(separator = "\n", postfix = "\n"))
            }
            AppLog.i(
                "BleScanner",
                "cluster sighting recorded: name=$safeName mac=$mac rssi=$rssi (file=${file.absolutePath})",
            )
        } catch (t: Throwable) {
            // Don't let diag-file IO break scanning — it's purely observational.
            AppLog.w("BleScanner", "cluster history write failed", t)
        }
    }

    private fun <K, V> MutableStateFlow<Map<K, V>>.update(transform: (Map<K, V>) -> Map<K, V>) {
        value = transform(value)
    }

    private companion object {
        // Soft cap on cluster-history JSONL rows. Each line is ~95 bytes, so
        // 50 entries ≈ ~5 KB — comfortably small for an unbounded debug log
        // that nobody ever rotates.
        const val MAX_CLUSTER_HISTORY = 50
    }
}

/** A bike (or anything else BLE-shaped) seen by the scanner.
 *  - [name] is the actual BT name if advertised, else null.
 *  - [vendor] is our best-effort label from manufacturer data / OUI / service UUID.
 *  - [rssi] is [Int.MIN_VALUE] for bonded-but-not-currently-visible devices.
 *  - [bonded] true if Android has a pairing record for this MAC. */
data class DiscoveredBike(
    val mac: String,
    val name: String?,
    val vendor: String?,
    val rssi: Int,
    val bonded: Boolean = false,
) {
    /** Best human label for the row title. */
    val displayName: String
        get() = name ?: vendor ?: "(unknown device)"
}

/** Public OUI helper so [BleScanner.seedBonded] doesn't need a ScanResult. */
internal object BleVendorPublic {
    fun ouiVendor(oui: String): String? = OUI[oui]
    private val OUI: Map<String, String> = mapOf(
        "74:B8:39" to "Suzuki cluster (TI BLE)",
    )
}
