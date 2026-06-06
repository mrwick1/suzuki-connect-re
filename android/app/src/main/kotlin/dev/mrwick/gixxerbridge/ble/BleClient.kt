package dev.mrwick.gixxerbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import dev.mrwick.gixxerbridge.util.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-bike BLE client. Wraps Android's BluetoothGatt with a coroutine-friendly API.
 *
 * Per Phase 1 + R3 research:
 *   - Use connectGatt(autoConnect=true) for survive-disconnect semantics (assumption A15).
 *   - Subscribe to NOTIFY_CHAR_UUID; bike will not stream until we WRITE first (a536 identity).
 *   - Writes use WRITE_TYPE_DEFAULT (with response) for the 1 Hz heartbeat throughput we need (A3).
 *   - All Bluetooth* operations require BLUETOOTH_CONNECT runtime permission on API 31+.
 */
@SuppressLint("MissingPermission") // caller verifies permission before constructing
class BleClient(private val context: Context) {

    private val tag = "BleClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notifications: SharedFlow<ByteArray> = _notifications.asSharedFlow()

    private val _bikeInfo = MutableStateFlow<BikeInfo?>(null)
    val bikeInfo: StateFlow<BikeInfo?> = _bikeInfo.asStateFlow()

    private val gattRef = AtomicReference<BluetoothGatt?>(null)
    private val writeMutex = Mutex()
    private val readMutex = Mutex()
    private val pendingWrite = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val pendingRead = AtomicReference<CompletableDeferred<ByteArray?>?>(null)
    private val pendingDescriptor = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val pendingMtu = AtomicReference<CompletableDeferred<Int>?>(null)

    /** Last MAC passed to [connect]; used for bounded handshake-failure reconnects. */
    @Volatile private var lastMac: String? = null
    /** Consecutive handshake failures for the current MAC; reset on Ready / MAC change. */
    private var handshakeFailCount = 0
    private val maxHandshakeReconnects = 3

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Start a connection attempt to the given MAC. Returns immediately; observe `state` for progress.
     * Safe to call repeatedly — second call disconnects the previous attempt first.
     */
    fun connect(mac: String) {
        // Reset the handshake-failure budget only when the *target* changes, so
        // an internal reconnect (same MAC) keeps counting toward the cap instead
        // of looping forever.
        if (mac != lastMac) handshakeFailCount = 0
        lastMac = mac
        AppLog.i(tag, "connect() mac=$mac")
        scope.launch {
            disconnectInternal()
            val device: BluetoothDevice? = try {
                adapter?.getRemoteDevice(mac)
            } catch (e: IllegalArgumentException) {
                AppLog.e(tag, "invalid MAC: $mac", e)
                _state.value = ConnectionState.Failed("invalid MAC: $mac")
                return@launch
            }
            if (device == null) {
                AppLog.e(tag, "Bluetooth adapter unavailable")
                _state.value = ConnectionState.Failed("Bluetooth adapter unavailable")
                return@launch
            }
            AppLog.i(tag, "connectGatt autoConnect=true transport=LE bondState=${device.bondState}")
            _state.value = ConnectionState.Connecting
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, /* autoConnect = */ true, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, /* autoConnect = */ true, callback)
            }
            gattRef.set(gatt)
        }
    }

    /** Tear down the connection. Idempotent. */
    fun disconnect() {
        AppLog.i(tag, "disconnect()")
        scope.launch { disconnectInternal() }
    }

    /** Release resources permanently. After close(), this client can't be reused. */
    suspend fun close() {
        disconnectInternal()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
    }

    /**
     * PERF: synchronous teardown hook for use from
     * [android.app.Service.onDestroy], where suspending is not allowed.
     *
     * Cancels the internal [scope]'s job so any in-flight `connect()` /
     * `readDeviceInfo()` coroutines are torn down promptly instead of leaking
     * until the process is killed. The GATT layer is already closed by the
     * caller via [disconnect]. Idempotent.
     *
     * Prior to this hook, the scope only got cancelled by [close], which is a
     * `suspend` API and was never invoked anywhere in the app.
     */
    fun shutdown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private suspend fun disconnectInternal() {
        val gatt = gattRef.getAndSet(null) ?: return
        try {
            gatt.disconnect()
            gatt.close()
        } catch (t: Throwable) {
            Log.w(tag, "disconnect threw", t)
        }
        _state.value = ConnectionState.Idle
    }

    /**
     * A handshake step failed. The link layer is up but the GATT handshake
     * didn't complete, and `autoConnect` only retries the *link* on a
     * Disconnected — so without this a transient handshake hiccup (e.g. a
     * CCC-ack timeout) would leave the app permanently stuck until the user
     * re-toggles the bike MAC. Tear down and reconnect with linear backoff,
     * bounded by [maxHandshakeReconnects] so a genuinely-wrong device doesn't
     * spin forever; falls through to [ConnectionState.Failed] once exhausted.
     * The counter resets on a successful Ready or a MAC change.
     */
    private fun failHandshake(reason: String) {
        val mac = lastMac
        if (mac != null && handshakeFailCount < maxHandshakeReconnects) {
            handshakeFailCount++
            val backoff = 1_500L * handshakeFailCount
            AppLog.w(tag, "handshake failed ($reason) — reconnect ${handshakeFailCount}/$maxHandshakeReconnects in ${backoff}ms")
            scope.launch {
                disconnectInternal()
                delay(backoff)
                connect(mac)
            }
        } else {
            AppLog.e(tag, "handshake failed ($reason) — giving up after $handshakeFailCount retries")
            _state.value = ConnectionState.Failed(reason)
        }
    }

    /**
     * Write a 30-byte frame to 0xFFF1.
     * Returns true on success (or false on timeout/failure).
     * Suspends until onCharacteristicWrite or 2s timeout. Serializes writes via mutex.
     */
    suspend fun write(frame: ByteArray): Boolean {
        if (frame.size != 30) {
            AppLog.w(tag, "write: expected 30 bytes, got ${frame.size}")
            return false
        }
        return writeMutex.withLock {
            val gatt = gattRef.get() ?: run {
                AppLog.w(tag, "write: no gatt (state=${_state.value})")
                return@withLock false
            }
            val characteristic = gatt.getService(SuzukiGatt.SERVICE_UUID)
                ?.getCharacteristic(SuzukiGatt.WRITE_CHAR_UUID) ?: run {
                AppLog.w(tag, "write: 0xFFF1 characteristic missing")
                return@withLock false
            }

            val deferred = CompletableDeferred<Boolean>()
            val typeHex = "0x${"%02x".format(frame[1].toInt() and 0xFF)}"

            // Retry on transient BUSY. Right after the post-discovery handshake the GATT
            // stack reports WRITE_REQUEST_BUSY (201) on the first few writeCharacteristic
            // calls for ~1 s — observed in the diag log even after the CCC descriptor
            // ack returned. Heartbeats eventually succeed, but URGENT identity (a536)
            // never reaches the bike, which then drops the link. The official Suzuki
            // app gets ~50 ms between CCC ack and identity write (M0 pcap), so the
            // window is real but narrow on most stacks. We poll up to ~2 s.
            var lastCode: Int = Int.MIN_VALUE
            var lastBool: Boolean = false
            val deadline = System.currentTimeMillis() + 2_000
            var attempt = 0
            while (true) {
                attempt++
                pendingWrite.set(deferred)
                val startedOk: Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val code = gatt.writeCharacteristic(
                        characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                    )
                    lastCode = code
                    code == android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = frame
                    @Suppress("DEPRECATION")
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    val r = gatt.writeCharacteristic(characteristic)
                    lastBool = r
                    r
                }
                if (startedOk) break
                pendingWrite.set(null)
                if (System.currentTimeMillis() >= deadline) {
                    AppLog.w(
                        tag,
                        "write: rejected after $attempt attempts type=$typeHex " +
                            "lastCode=$lastCode lastBool=$lastBool (${writeStatusName(lastCode)})",
                    )
                    return@withLock false
                }
                // Short backoff before retry. The stack typically clears within 50-150 ms
                // after a fresh descriptor write.
                delay(50)
            }
            if (attempt > 1) {
                AppLog.i(tag, "write: ok type=$typeHex after $attempt attempts (busy backoff)")
            }
            val result = withTimeoutOrNull(2_000) { deferred.await() } ?: run {
                AppLog.w(tag, "write: ack timeout 2s (type=0x${"%02x".format(frame[1].toInt() and 0xFF)})")
                pendingWrite.set(null)
                false
            }
            if (result) AppLog.d(tag, "write ok type=0x${"%02x".format(frame[1].toInt() and 0xFF)}")
            result
        }
    }

    /**
     * Reproduces the post-discovery handshake that the official Suzuki Connect app
     * performs (per DISCOVERIES.md 2026-05-24 trace + the M0 pcap capture):
     *   1. requestMtu(65) — bike caps at 65 anyway; bumping past the default 23 avoids
     *      any chance of write fragmentation on 30-byte frames.
     *   2. ~500 ms quiet gap. The Suzuki app does this via a `postDelayed(500)` in
     *      `MyBleService.a(BleDevice)`; if we skip it, the next op races the GATT
     *      stack's internal queue and the descriptor write often fails fast.
     *   3. setCharacteristicNotification + write CCC=ENABLE_NOTIFICATION.
     *   4. **Wait for onDescriptorWrite** before flipping to Ready. This was the bug:
     *      we were marking Ready immediately, BikeBridgeService enqueued the identity
     *      (a536) frame, and writeCharacteristic returned false because the descriptor
     *      write was still in flight — Android allows only one outstanding GATT op.
     *      The bike never saw a536, sat in pairing mode, and dropped the link after
     *      ~30 s (observed CONN_TIMEOUT status=8 in the diag log).
     *   5. Mark Ready → BikeBridgeService's state collector enqueues identity, which
     *      now drains cleanly because the GATT queue is empty.
     *   6. Kick off the optional Device Info (0x180A) reads in the background.
     */
    private suspend fun runHandshake(gatt: BluetoothGatt) {
        // (1) MTU. Request 65 to match the Server Rx MTU seen in the M0 pcap.
        // ASSUMED (single capture): the bike caps at 65 — treat as a per-bike
        // observation, not a universal fact. Bumping past the default 23 avoids
        // write fragmentation on 30-byte frames.
        val mtuDeferred = CompletableDeferred<Int>()
        pendingMtu.set(mtuDeferred)
        val mtuReq = try { gatt.requestMtu(65) } catch (t: Throwable) {
            AppLog.w(tag, "requestMtu threw", t); false
        }
        if (!mtuReq) {
            AppLog.w(tag, "requestMtu(65) returned false; proceeding anyway")
            pendingMtu.set(null)
        } else {
            val mtu = withTimeoutOrNull(2_000) { mtuDeferred.await() } ?: -1
            when {
                mtu <= 0 -> AppLog.w(tag, "MTU negotiation failed/timeout; proceeding with default")
                // 30-byte frame + 3-byte ATT header = 33. Below this, writes would
                // fragment, which the bike's single-PDU frame format doesn't expect.
                mtu < 33 -> AppLog.w(tag, "negotiated MTU=$mtu < 33 — 30-byte frames may fragment")
            }
        }

        // (2) Quiet gap. Matches Suzuki app's postDelayed(500) before subscribing.
        delay(500)

        // (3) Subscribe to 0xFFF2 notify + write CCC = ENABLE_NOTIFICATION.
        val notifyChar = gatt.getService(SuzukiGatt.SERVICE_UUID)
            ?.getCharacteristic(SuzukiGatt.NOTIFY_CHAR_UUID)
        if (notifyChar == null) {
            AppLog.e(tag, "notify characteristic 0xFFF2 missing")
            failHandshake("notify characteristic 0xFFF2 missing")
            return
        }
        val enabled = gatt.setCharacteristicNotification(notifyChar, true)
        AppLog.i(tag, "setCharacteristicNotification(0xFFF2)=$enabled")
        if (!enabled) {
            failHandshake("setCharacteristicNotification failed")
            return
        }
        val ccc = notifyChar.getDescriptor(SuzukiGatt.CCC_DESCRIPTOR_UUID)
        if (ccc == null) {
            AppLog.e(tag, "CCC descriptor missing on 0xFFF2; cannot enable notify")
            failHandshake("CCC descriptor missing")
            return
        }

        // (4) Write CCC = ENABLE_NOTIFICATION and wait for the ack — the gate
        // before Ready. Retry a few times: the ack occasionally times out on a
        // congested stack right after discovery, and a single miss used to wedge
        // the connection permanently (now a bounded reconnect catches the rest).
        var cccOk = false
        for (cccAttempt in 1..3) {
            val descDeferred = CompletableDeferred<Boolean>()
            pendingDescriptor.set(descDeferred)
            val cccStarted = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        android.bluetooth.BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(ccc)
                }
            } catch (t: Throwable) {
                AppLog.e(tag, "writeDescriptor(CCC) threw", t); false
            }
            if (!cccStarted) {
                pendingDescriptor.set(null)
                AppLog.w(tag, "writeDescriptor(CCC) rejected (attempt $cccAttempt/3)")
                delay(250)
                continue
            }
            cccOk = withTimeoutOrNull(2_000) { descDeferred.await() } ?: false
            if (cccOk) break
            pendingDescriptor.set(null)
            AppLog.w(tag, "CCC descriptor write ack timeout (attempt $cccAttempt/3)")
            delay(300)
        }
        if (!cccOk) {
            failHandshake("CCC descriptor write did not ack")
            return
        }
        AppLog.i(tag, "CCC notify-enable ack received — handshake clear to send")

        // (5) Mark Ready. BikeBridgeService's state collector reacts immediately by
        // enqueueing the a536 identity frame to URGENT, which the drain writes next.
        handshakeFailCount = 0
        _state.value = ConnectionState.Ready
        AppLog.i(tag, "Ready — bike connected and notify subscription armed")

        // (6) Background device-info read.
        scope.launch { readDeviceInfo(gatt) }
    }

    /**
     * Read all standard Device Information Service (0x180A) characteristics serially
     * and publish a [BikeInfo] snapshot. Per BluetoothGatt contract, only one read
     * can be outstanding at a time, so this iterates with [readMutex] held.
     * Missing characteristics are skipped silently.
     */
    private suspend fun readDeviceInfo(gatt: BluetoothGatt) {
        val service = gatt.getService(SuzukiGatt.DEVICE_INFO_SERVICE) ?: return
        // ASSUMED: ASCII decode for the string characteristics matches what the bike
        // actually publishes (Phase 1 NOTES.md shows printable ASCII for these).
        suspend fun readString(uuid: java.util.UUID): String? {
            val c = service.getCharacteristic(uuid) ?: return null
            val raw = readCharacteristic(gatt, c) ?: return null
            return runCatching { String(raw, Charsets.US_ASCII).trim(' ', ' ', '\t', '\n', '\r') }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
        suspend fun readHex(uuid: java.util.UUID): String? {
            val c = service.getCharacteristic(uuid) ?: return null
            val raw = readCharacteristic(gatt, c) ?: return null
            return raw.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
        }

        val info = BikeInfo(
            manufacturer = readString(SuzukiGatt.CHAR_MANUFACTURER),
            modelNumber = readString(SuzukiGatt.CHAR_MODEL_NUMBER),
            serialNumber = readString(SuzukiGatt.CHAR_SERIAL_NUMBER),
            firmwareRevision = readString(SuzukiGatt.CHAR_FIRMWARE_REV),
            softwareRevision = readString(SuzukiGatt.CHAR_SOFTWARE_REV),
            hardwareRevision = readString(SuzukiGatt.CHAR_HARDWARE_REV),
            systemId = readHex(SuzukiGatt.CHAR_SYSTEM_ID),
            pnpId = readHex(SuzukiGatt.CHAR_PNP_ID),
            ieeeCert = readHex(SuzukiGatt.CHAR_IEEE_CERT),
        )
        _bikeInfo.value = info
    }

    /**
     * Issue a single characteristic read and suspend until [onCharacteristicRead]
     * delivers the value, or 2s elapses. Returns null on failure / timeout / GATT error.
     * Serialised via [readMutex] so callers can chain reads back-to-back.
     */
    private suspend fun readCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ): ByteArray? = readMutex.withLock {
        val deferred = CompletableDeferred<ByteArray?>()
        pendingRead.set(deferred)
        val started = try {
            gatt.readCharacteristic(characteristic)
        } catch (t: Throwable) {
            Log.w(tag, "readCharacteristic threw for ${characteristic.uuid}", t)
            false
        }
        if (!started) {
            pendingRead.set(null)
            return@withLock null
        }
        withTimeoutOrNull(2_000) { deferred.await() } ?: run {
            pendingRead.set(null)
            null
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val nState = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "state=$newState"
            }
            AppLog.i(tag, "onConnectionStateChange status=$status (${gattStatusName(status)}) newState=$nState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnectionState.Discovering
                    val ok = gatt.discoverServices()
                    AppLog.i(tag, "discoverServices started=$ok")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = ConnectionState.Disconnected(status)
                    AppLog.w(tag, "disconnected (autoConnect will retry); status=$status (${gattStatusName(status)})")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLog.e(tag, "service discovery failed status=$status (${gattStatusName(status)})")
                failHandshake("service discovery failed status=$status")
                return
            }
            val services = gatt.services.joinToString(", ") { it.uuid.toString().substring(0, 8) }
            AppLog.i(tag, "services discovered (${gatt.services.size}): $services")
            // Drive the rest of the handshake on our scope so we can `delay` and `await`
            // the descriptor/MTU callbacks. The Suzuki Connect APK does exactly this
            // sequence (see DISCOVERIES.md 2026-05-24 "Connection handshake fully
            // mapped") and the captured pcap (captures/m0-pairing-and-first-nav…)
            // confirms the order: MTU exchange → ~500ms gap → CCC write → wait for
            // ack → identity (a536) write → bike streams a537.
            scope.launch { runHandshake(gatt) }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            AppLog.i(tag, "onMtuChanged mtu=$mtu status=$status (${gattStatusName(status)})")
            pendingMtu.getAndSet(null)?.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else -1)
        }

        @Deprecated("Pre-Tiramisu API")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            AppLog.d(tag, "onDescriptorWrite uuid=${descriptor.uuid} status=$status (${gattStatusName(status)})")
            pendingDescriptor.getAndSet(null)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite.getAndSet(null)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Pre-Tiramisu API")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value
            val ok = status == BluetoothGatt.GATT_SUCCESS && bytes != null
            pendingRead.getAndSet(null)?.complete(if (ok) bytes!!.copyOf() else null)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            pendingRead.getAndSet(null)?.complete(if (ok) value.copyOf() else null)
        }

        @Deprecated("Pre-Tiramisu API")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == SuzukiGatt.NOTIFY_CHAR_UUID) {
                @Suppress("DEPRECATION")
                characteristic.value?.let { _notifications.tryEmit(it.copyOf()) }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == SuzukiGatt.NOTIFY_CHAR_UUID) {
                _notifications.tryEmit(value.copyOf())
            }
        }
    }

    /** Friendly name for a [BluetoothStatusCodes] return from `writeCharacteristic`
     *  (TIRAMISU+ overload). Helps the diag log say "BUSY" not "201". */
    private fun writeStatusName(code: Int): String = when (code) {
        android.bluetooth.BluetoothStatusCodes.SUCCESS -> "SUCCESS"
        100 -> "ERROR_BLUETOOTH_NOT_ALLOWED"
        101 -> "ERROR_BLUETOOTH_NOT_ENABLED"
        102 -> "ERROR_DEVICE_NOT_BONDED"
        103 -> "ERROR_GATT_WRITE_NOT_ALLOWED"
        201 -> "ERROR_GATT_WRITE_REQUEST_BUSY"
        Int.MIN_VALUE -> "(unset — pre-Tiramisu path)"
        else -> "code=$code"
    }

    /** Friendly name for a BluetoothGatt status int. Pulled from `gatt_api.h` /
     *  `BluetoothGatt` constants. Anything not listed is returned as-is. */
    private fun gattStatusName(status: Int): String = when (status) {
        BluetoothGatt.GATT_SUCCESS -> "SUCCESS"
        BluetoothGatt.GATT_READ_NOT_PERMITTED -> "READ_NOT_PERMITTED"
        BluetoothGatt.GATT_WRITE_NOT_PERMITTED -> "WRITE_NOT_PERMITTED"
        BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION -> "INSUFF_AUTH"
        BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED -> "REQ_NOT_SUPPORTED"
        BluetoothGatt.GATT_INVALID_OFFSET -> "INVALID_OFFSET"
        BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH -> "INVALID_ATTR_LEN"
        BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION -> "INSUFF_ENCRYPTION"
        BluetoothGatt.GATT_CONNECTION_CONGESTED -> "CONGESTED"
        BluetoothGatt.GATT_FAILURE -> "FAILURE"
        8 -> "CONN_TIMEOUT"          // 0x08 — bike out of range / powered off
        19 -> "REMOTE_USER_TERMINATED"// 0x13 — peer hung up
        22 -> "LOCAL_HOST_TERMINATED"// 0x16 — we hung up
        62 -> "FAILED_TO_ESTABLISH"  // 0x3E — typical with autoConnect when device gone
        133 -> "GATT_ERROR"          // 0x85 — generic; often "already bonded to another phone"
        147 -> "ATT_INVALID_HANDLE"
        else -> "$status"
    }
}
