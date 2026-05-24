package dev.mrwick.gixxerbridge.weather

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

    fun start(scope: CoroutineScope) {
        if (job != null) return
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
    }

    /** Convenience: (suzukiCode, tempByte) tuple for a533 building. */
    fun currentEncoded(): Pair<Int, Int> {
        val s = _snapshot.value ?: return SUZUKI_DEFAULT_WEATHER to 0
        return s.suzukiCode to celsiusToTempByte(s.tempCelsius)
    }
}
