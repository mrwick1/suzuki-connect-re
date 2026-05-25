package dev.mrwick.gixxerbridge.ui.settings

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.BikeBridgeService
import dev.mrwick.gixxerbridge.ble.BleScanner
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.ble.DiscoveredBike
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.util.AppLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** ViewModel for [PairingScreen]; owns a [BleScanner] and writes the picked MAC into [Settings]. */
class PairingViewModel(app: Application) : AndroidViewModel(app) {
    private val tag = "PairingVM"
    private val scanner = BleScanner(app)
    private val settings = Settings(app)

    /** Live map of discovered bikes keyed by MAC (see [BleScanner.results]). */
    val results = scanner.results

    /** The currently-saved bike MAC (or null if none paired). UI shows this at the
     *  top of the pair screen because BLE scan results will NOT include a device
     *  that's already in an active GATT connection — autoConnect=true means once
     *  the bike powers on, it's "linked" not "advertising", and the scanner can't
     *  see it. Without this card the user has no way to forget / re-pair an
     *  already-paired bike short of clearing app data. */
    val pairedMac: kotlinx.coroutines.flow.StateFlow<String?> = settings.bikeMac
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)

    /** Live connection state of the saved bike, exposed for the "Currently paired"
     *  card. Falls back to Idle when the service hasn't started yet. */
    val pairedConnectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState> =
        kotlinx.coroutines.flow.MutableStateFlow<ConnectionState>(ConnectionState.Idle).also { mirror ->
            viewModelScope.launch {
                // Re-subscribe whenever AppGraph.bleClient is (re)published. Poll-style
                // because the client is a `@Volatile var` not a Flow.
                var lastClient: dev.mrwick.gixxerbridge.ble.BleClient? = null
                var job: kotlinx.coroutines.Job? = null
                while (true) {
                    val cur = AppGraph.bleClient
                    if (cur !== lastClient) {
                        job?.cancel()
                        lastClient = cur
                        job = if (cur != null) {
                            launch { cur.state.collect { mirror.value = it } }
                        } else {
                            launch { mirror.value = ConnectionState.Idle }
                        }
                    }
                    kotlinx.coroutines.delay(500)
                }
            }
        }

    fun forgetPairedBike() {
        viewModelScope.launch {
            AppLog.i(tag, "forgetPairedBike() — clearing MAC")
            settings.setBikeMac(null)
        }
    }

    /** UI-facing pairing lifecycle (separate from [ConnectionState] so the screen can
     *  show a clean "Saving / Connecting / Connected / Failed" sequence even before
     *  the service finishes service discovery). */
    sealed interface PairUiState {
        data object Idle : PairUiState
        data class Saving(val bike: DiscoveredBike) : PairUiState
        data class Connecting(val bike: DiscoveredBike, val phase: String) : PairUiState
        data class Connected(val bike: DiscoveredBike) : PairUiState
        data class Failed(val bike: DiscoveredBike, val reason: String) : PairUiState
    }
    private val _pairState = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val pairState: StateFlow<PairUiState> = _pairState.asStateFlow()

    init {
        scanner.start()
    }

    override fun onCleared() {
        scanner.stop()
    }

    /** Persist the chosen bike's MAC, then drive [pairState] from [ConnectionState].
     *  Calls [onDone] once the bike reports Ready (or after a 15 s timeout — pairing
     *  isn't strictly blocked on that, the service will keep retrying via autoConnect). */
    fun pickBike(bike: DiscoveredBike, onDone: () -> Unit) {
        AppLog.i(tag, "pickBike mac=${bike.mac} name=${bike.name} rssi=${bike.rssi}")
        viewModelScope.launch {
            _pairState.value = PairUiState.Saving(bike)
            settings.setBikeMac(bike.mac)
            AppLog.i(tag, "bike MAC saved to DataStore; waiting for BleClient to reach Ready")

            // If the BikeBridgeService isn't running yet (e.g. user paired before tapping
            // "Start GixxerBridge" on Home), start it now and wait for it to publish a
            // BleClient via AppGraph.bleClient. Previously we'd just claim Connected and
            // dismiss, which lied to the user and dropped them back to settings with no
            // bike actually attached.
            var client = AppGraph.bleClient
            if (client == null) {
                AppLog.i(tag, "bleClient null — starting BikeBridgeService and waiting for client publish")
                val ctx = getApplication<Application>()
                val intent = Intent(ctx, BikeBridgeService::class.java)
                try {
                    ContextCompat.startForegroundService(ctx, intent)
                } catch (t: Throwable) {
                    AppLog.e(tag, "startForegroundService threw", t)
                }
                _pairState.value = PairUiState.Connecting(bike, "Starting bike service…")
                // Poll up to 5 s for AppGraph.bleClient to appear; published in BikeBridgeService.onCreate.
                val started = withTimeoutOrNull(5_000L) {
                    while (AppGraph.bleClient == null) delay(50)
                    AppGraph.bleClient
                }
                if (started == null) {
                    AppLog.e(tag, "BikeBridgeService never published bleClient within 5s")
                    _pairState.value = PairUiState.Failed(bike, "Bike service did not start. Try again from Home → Start GixxerBridge.")
                    onDone()
                    return@launch
                }
                client = started
                AppLog.i(tag, "BikeBridgeService up; bleClient acquired")
            }

            val terminal = withTimeoutOrNull(15_000L) {
                client!!.state
                    .onEach { s ->
                        AppLog.d(tag, "BleClient.state=$s")
                        when (s) {
                            is ConnectionState.Connecting -> _pairState.value = PairUiState.Connecting(bike, "Connecting…")
                            is ConnectionState.Discovering -> _pairState.value = PairUiState.Connecting(bike, "Discovering services…")
                            else -> { /* terminal handled below */ }
                        }
                    }
                    .first { it is ConnectionState.Ready || it is ConnectionState.Failed }
            }
            when (terminal) {
                is ConnectionState.Ready -> _pairState.value = PairUiState.Connected(bike)
                is ConnectionState.Failed -> _pairState.value = PairUiState.Failed(bike, terminal.reason)
                null -> {
                    AppLog.w(tag, "pair-wait timed out after 15 s; calling onDone anyway (service will keep retrying)")
                }
                else -> { /* unreachable */ }
            }
            onDone()
        }
    }

    /** User dismissed an error from the overlay — go back to scanning. */
    fun clearPairState() { _pairState.value = PairUiState.Idle }
}
