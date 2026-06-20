package dev.mrwick.redline.weather

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Background poller that keeps the latest WeatherSnapshot in memory.
 * Refreshes every [refreshIntervalMs]. Survives transient network errors.
 */
class WeatherCache(
    private val provider: WeatherProvider,
    private val latLngProvider: suspend () -> Pair<Double, Double>?,
    private val refreshIntervalMs: Long = 30 * 60 * 1000L,
) {
    private val _snapshot = MutableStateFlow<WeatherSnapshot?>(null)
    val snapshot: StateFlow<WeatherSnapshot?> = _snapshot.asStateFlow()

    private var job: Job? = null
    private var lastScope: CoroutineScope? = null
    // PERF: keep the last poll-allowed state so pause() is idempotent and
    // resume() can re-arm cheaply without callers tracking it.
    private var paused: Boolean = false

    fun start(scope: CoroutineScope) {
        if (job != null) return
        lastScope = scope
        paused = false
        job = scope.launch {
            while (true) {
                val coords = latLngProvider()
                if (coords != null) {
                    val s = provider.fetchCurrent(coords.first, coords.second)
                    if (s != null) _snapshot.value = s
                }
                delay(refreshIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastScope = null
        paused = false
    }

    /**
     * PERF: pause the polling loop without tearing down state.
     * Callers (BikeBridgeService) invoke this after extended bike disconnect —
     * if we haven't talked to a bike in >24h we don't need fresh weather for
     * the cluster, so the open-meteo round-trip every 30 min is pure waste.
     * Snapshot is retained so the moment we resume, the heartbeat / welcome
     * frame still has a usable value to send.
     */
    fun pause() {
        if (paused) return
        paused = true
        job?.cancel()
        job = null
    }

    /**
     * PERF: resume polling if previously paused. No-op if already running or
     * never started. Safe to call on every Ready transition.
     */
    fun resume() {
        if (!paused) return
        val scope = lastScope ?: return
        paused = false
        // job==null after pause(); start() guards on that and will re-launch.
        start(scope)
    }

    /** Convenience: (suzukiCode, tempByte) tuple for a533 building. */
    fun currentEncoded(): Pair<Int, Int> {
        val s = _snapshot.value ?: return SUZUKI_DEFAULT_WEATHER to 0
        return s.suzukiCode to celsiusToTempByte(s.tempCelsius)
    }
}
