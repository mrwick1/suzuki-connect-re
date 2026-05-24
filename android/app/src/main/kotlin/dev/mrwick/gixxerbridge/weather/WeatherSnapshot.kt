package dev.mrwick.gixxerbridge.weather

/**
 * A point-in-time weather observation prepared for the Suzuki cluster.
 *
 * @property suzukiCode  Cluster code (0..11) destined for a533 byte 21.
 * @property tempCelsius Source-of-truth temperature in degrees Celsius (null = unknown).
 * @property windKmh     Source-of-truth wind speed in km/h (null = unknown).
 * @property fetchedAt   Wall-clock millis when the snapshot was produced ([System.currentTimeMillis]).
 * @property source      Identifier for the upstream weather provider (e.g. "open-meteo").
 */
data class WeatherSnapshot(
    val suzukiCode: Int,
    val tempCelsius: Double?,
    val windKmh: Double?,
    val fetchedAt: Long,
    val source: String = "open-meteo",
)
