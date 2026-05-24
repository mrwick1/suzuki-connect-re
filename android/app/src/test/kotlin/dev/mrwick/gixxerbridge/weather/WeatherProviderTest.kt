package dev.mrwick.gixxerbridge.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for [parseSnapshot]. No network is touched — fixtures are pasted from a real
 * Open-Meteo response captured 2026-05-24 (latitude 12.97, longitude 77.59).
 *
 * MockWebServer is not used because it isn't on the test classpath; factoring JSON parsing into
 * [parseSnapshot] keeps the network boundary untested but the mapping fully covered.
 */
class WeatherProviderTest {

    /** Verbatim response body captured from Open-Meteo on 2026-05-24. */
    private val realFixture = """
        {
          "latitude": 12.970123,
          "longitude": 77.56364,
          "generationtime_ms": 0.11873245239257812,
          "utc_offset_seconds": 19800,
          "timezone": "Asia/Kolkata",
          "timezone_abbreviation": "GMT+5:30",
          "elevation": 914.0,
          "current_units": {
            "time": "iso8601",
            "interval": "seconds",
            "temperature_2m": "°C",
            "weather_code": "wmo code",
            "wind_speed_10m": "km/h"
          },
          "current": {
            "time": "2026-05-24T23:00",
            "interval": 900,
            "temperature_2m": 21.6,
            "weather_code": 3,
            "wind_speed_10m": 8.0
          }
        }
    """.trimIndent()

    @Test
    fun `parses real fixture into expected snapshot`() {
        val snap = parseSnapshot(realFixture, fetchedAt = 1_700_000_000_000L)
        assertNotNull("Snapshot must be non-null for a well-formed fixture", snap)
        snap!!
        // WMO 3 (overcast) -> Suzuki CLOUDY; temp 21.6 C and wind 8 km/h trigger no overrides.
        assertEquals(SuzukiWeather.CLOUDY, snap.suzukiCode)
        assertEquals(21.6, snap.tempCelsius!!, 0.0001)
        assertEquals(8.0, snap.windKmh!!, 0.0001)
        assertEquals(1_700_000_000_000L, snap.fetchedAt)
        assertEquals("open-meteo", snap.source)
    }

    @Test
    fun `applies WINDY override when wind exceeds 30 kmh in fixture`() {
        val windyFixture = """
            {
              "current": {
                "temperature_2m": 28.0,
                "weather_code": 1,
                "wind_speed_10m": 42.5
              }
            }
        """.trimIndent()
        val snap = parseSnapshot(windyFixture)
        assertNotNull(snap)
        assertEquals(SuzukiWeather.WINDY, snap!!.suzukiCode)
    }

    @Test
    fun `maps thunderstorm WMO 95 to SuzukiWeather THUNDER`() {
        val stormFixture = """
            {
              "current": {
                "temperature_2m": 24.0,
                "weather_code": 95,
                "wind_speed_10m": 12.0
              }
            }
        """.trimIndent()
        val snap = parseSnapshot(stormFixture)
        assertNotNull(snap)
        assertEquals(SuzukiWeather.THUNDER, snap!!.suzukiCode)
    }

    @Test
    fun `returns null on malformed JSON`() {
        assertNull(parseSnapshot("{not valid json"))
        assertNull(parseSnapshot(""))
    }

    @Test
    fun `returns null when current block is missing`() {
        val noCurrent = """{ "latitude": 12.0, "longitude": 77.0 }"""
        assertNull(parseSnapshot(noCurrent))
    }

    @Test
    fun `returns null when both weather_code and temperature are missing`() {
        val sparse = """{ "current": { "wind_speed_10m": 5.0 } }"""
        assertNull(parseSnapshot(sparse))
    }

    @Test
    fun `falls back to default Suzuki code when only temperature is present`() {
        val tempOnly = """{ "current": { "temperature_2m": 25.0 } }"""
        val snap = parseSnapshot(tempOnly)
        assertNotNull(snap)
        assertEquals(SUZUKI_DEFAULT_WEATHER, snap!!.suzukiCode)
        assertEquals(25.0, snap.tempCelsius!!, 0.0001)
        assertNull(snap.windKmh)
    }
}
