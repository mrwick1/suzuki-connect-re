package dev.mrwick.gixxerbridge.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * On-the-wire shape of Open-Meteo's `/v1/forecast` response — only the fields we consume.
 *
 * Verified 2026-05-24 against
 * https://api.open-meteo.com/v1/forecast?latitude=12.97&longitude=77.59&current=temperature_2m,weather_code,wind_speed_10m&timezone=auto
 * Real response contained extra top-level fields (latitude, longitude, generationtime_ms, utc_offset_seconds,
 * timezone, timezone_abbreviation, elevation, current_units) and an extra `interval` field inside `current`
 * — we ignore those via `ignoreUnknownKeys = true`.
 */
@Serializable
internal data class OpenMeteoResponse(
    val current: OpenMeteoCurrent? = null,
)

@Serializable
internal data class OpenMeteoCurrent(
    val temperature_2m: Double? = null,
    val weather_code: Int? = null,
    val wind_speed_10m: Double? = null,
)

// ASSUMED: ignoreUnknownKeys=true is correct because Open-Meteo adds metadata fields we don't need
// (and may add more in future); failing on them would needlessly break the client.
private val JSON: Json = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Open-Meteo HTTP client that returns a [WeatherSnapshot] ready for the Suzuki cluster.
 *
 * @param client OkHttp client to use; defaults to a fresh client with 5-second connect/read timeouts.
 */
class WeatherProvider(
    private val client: OkHttpClient = defaultClient(),
    // ASSUMED: making the base URL injectable keeps the class trivially testable against MockWebServer
    // or any local fixture server without leaking HTTP concerns into the public API.
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /**
     * Fetch current weather for the given coordinates.
     * Returns null on network error or unparseable response.
     */
    suspend fun fetchCurrent(latitude: Double, longitude: Double): WeatherSnapshot? =
        withContext(Dispatchers.IO) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", latitude.toString())
                .addQueryParameter("longitude", longitude.toString())
                .addQueryParameter("current", "temperature_2m,weather_code,wind_speed_10m")
                .addQueryParameter("timezone", "auto")
                .build()
            val request = Request.Builder().url(url).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: return@use null
                    parseSnapshot(body)
                }
            } catch (_: Exception) {
                // Any I/O or parse failure -> null; caller decides whether to use a cached value.
                null
            }
        }

    companion object {
        /** Default Open-Meteo forecast endpoint. */
        const val DEFAULT_BASE_URL: String = "https://api.open-meteo.com/v1/forecast"

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
    }
}

/**
 * Parse a raw Open-Meteo JSON response into a [WeatherSnapshot]. Returns null on parse failure
 * or if the response is missing both the weather code and temperature.
 *
 * Exposed (non-private) so tests can exercise JSON parsing without a network round-trip.
 */
internal fun parseSnapshot(json: String, fetchedAt: Long = System.currentTimeMillis()): WeatherSnapshot? {
    val parsed: OpenMeteoResponse = try {
        JSON.decodeFromString(OpenMeteoResponse.serializer(), json)
    } catch (_: Exception) {
        return null
    }
    val current = parsed.current ?: return null
    val wmo = current.weather_code
    val temp = current.temperature_2m
    val wind = current.wind_speed_10m
    if (wmo == null && temp == null) return null
    val baseCode = if (wmo != null) wmoToSuzuki(wmo) else SUZUKI_DEFAULT_WEATHER
    val finalCode = applyTempWindOverrides(baseCode, temp, wind)
    return WeatherSnapshot(
        suzukiCode = finalCode,
        tempCelsius = temp,
        windKmh = wind,
        fetchedAt = fetchedAt,
    )
}
