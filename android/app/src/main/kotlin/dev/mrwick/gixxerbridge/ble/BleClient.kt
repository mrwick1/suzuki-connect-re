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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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

    private val gattRef = AtomicReference<BluetoothGatt?>(null)
    private val writeMutex = Mutex()
    private val pendingWrite = AtomicReference<CompletableDeferred<Boolean>?>(null)

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Start a connection attempt to the given MAC. Returns immediately; observe `state` for progress.
     * Safe to call repeatedly — second call disconnects the previous attempt first.
     */
    fun connect(mac: String) {
        scope.launch {
            disconnectInternal()
            val device: BluetoothDevice? = try {
                adapter?.getRemoteDevice(mac)
            } catch (e: IllegalArgumentException) {
                _state.value = ConnectionState.Failed("invalid MAC: $mac")
                return@launch
            }
            if (device == null) {
                _state.value = ConnectionState.Failed("Bluetooth adapter unavailable")
                return@launch
            }
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
        scope.launch { disconnectInternal() }
    }

    /** Release resources permanently. After close(), this client can't be reused. */
    suspend fun close() {
        disconnectInternal()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin()
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
     * Write a 30-byte frame to 0xFFF1.
     * Returns true on success (or false on timeout/failure).
     * Suspends until onCharacteristicWrite or 2s timeout. Serializes writes via mutex.
     */
    suspend fun write(frame: ByteArray): Boolean {
        if (frame.size != 30) {
            Log.w(tag, "write: expected 30 bytes, got ${frame.size}")
            return false
        }
        return writeMutex.withLock {
            val gatt = gattRef.get() ?: return false
            val characteristic = gatt.getService(SuzukiGatt.SERVICE_UUID)
                ?.getCharacteristic(SuzukiGatt.WRITE_CHAR_UUID) ?: return false

            val deferred = CompletableDeferred<Boolean>()
            pendingWrite.set(deferred)

            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = frame
                @Suppress("DEPRECATION")
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (!ok) {
                pendingWrite.set(null)
                return@withLock false
            }
            withTimeoutOrNull(2_000) { deferred.await() } ?: run {
                pendingWrite.set(null)
                false
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnectionState.Discovering
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = ConnectionState.Disconnected(status)
                    // autoConnect=true means stack will re-attempt; we don't tear down here.
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.Failed("service discovery failed status=$status")
                return
            }
            val notifyChar = gatt.getService(SuzukiGatt.SERVICE_UUID)
                ?.getCharacteristic(SuzukiGatt.NOTIFY_CHAR_UUID)
            if (notifyChar == null) {
                _state.value = ConnectionState.Failed("notify characteristic 0xFFF2 missing")
                return
            }
            val enabled = gatt.setCharacteristicNotification(notifyChar, true)
            if (!enabled) {
                _state.value = ConnectionState.Failed("setCharacteristicNotification failed")
                return
            }
            val ccc = notifyChar.getDescriptor(SuzukiGatt.CCC_DESCRIPTOR_UUID)
            if (ccc != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    ccc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(ccc)
                }
            }
            _state.value = ConnectionState.Ready
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pendingWrite.getAndSet(null)?.complete(status == BluetoothGatt.GATT_SUCCESS)
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
}
