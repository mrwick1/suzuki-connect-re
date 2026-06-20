package dev.mrwick.redline.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherCodeMapTest {

    @Test
    fun `wmoToSuzuki maps representative codes correctly`() {
        assertEquals(SuzukiWeather.SUNNY,      wmoToSuzuki(0))   // clear sky
        assertEquals(SuzukiWeather.RAIN,       wmoToSuzuki(65))  // heavy rain
        assertEquals(SuzukiWeather.THUNDER,    wmoToSuzuki(95))  // thunderstorm
        assertEquals(SuzukiWeather.SLEET,      wmoToSuzuki(99))  // thunderstorm w/ heavy hail
        assertEquals(SuzukiWeather.CLOUDY,     wmoToSuzuki(3))   // overcast
        assertEquals(SuzukiWeather.FOG,        wmoToSuzuki(45))  // fog
        assertEquals(SuzukiWeather.LIGHT_RAIN, wmoToSuzuki(53))  // moderate drizzle
        assertEquals(SuzukiWeather.SNOW,       wmoToSuzuki(73))  // moderate snow
    }

    @Test
    fun `wmoToSuzuki falls back to default for unknown codes`() {
        assertEquals(SUZUKI_DEFAULT_WEATHER, wmoToSuzuki(17))
        assertEquals(SUZUKI_DEFAULT_WEATHER, wmoToSuzuki(88))
        assertEquals(SUZUKI_DEFAULT_WEATHER, wmoToSuzuki(-1))
        assertEquals(SUZUKI_DEFAULT_WEATHER, wmoToSuzuki(9999))
    }

    @Test
    fun `celsiusToTempByte returns 0 when celsius is null`() {
        assertEquals(0, celsiusToTempByte(null))
    }

    @Test
    fun `celsiusToTempByte encodes 0C as 147`() {
        // 0 C = 32 F; ceil(32) + 115 = 147
        assertEquals(147, celsiusToTempByte(0.0))
    }

    @Test
    fun `celsiusToTempByte encodes 27C as 196`() {
        // 27 C = 80.6 F; ceil(80.6) = 81; 81 + 115 = 196
        assertEquals(196, celsiusToTempByte(27.0))
    }

    @Test
    fun `celsiusToTempByte clamps extreme values into 0-255`() {
        // Far below absolute zero / far above survivable temps shouldn't blow up.
        assertEquals(0, celsiusToTempByte(-200.0))
        assertEquals(255, celsiusToTempByte(200.0))
    }

    @Test
    fun `applyTempWindOverrides returns HOT when temp greater than 38C`() {
        assertEquals(SuzukiWeather.HOT, applyTempWindOverrides(SuzukiWeather.SUNNY, 40.0, 5.0))
    }

    @Test
    fun `applyTempWindOverrides returns WINDY when wind greater than 30kmh`() {
        assertEquals(SuzukiWeather.WINDY, applyTempWindOverrides(SuzukiWeather.SUNNY, 25.0, 35.0))
    }

    @Test
    fun `applyTempWindOverrides returns COLD when temp less than 5C`() {
        assertEquals(SuzukiWeather.COLD, applyTempWindOverrides(SuzukiWeather.SUNNY, 2.0, 5.0))
    }

    @Test
    fun `applyTempWindOverrides keeps original code when no override fires`() {
        assertEquals(SuzukiWeather.CLOUDY, applyTempWindOverrides(SuzukiWeather.CLOUDY, 25.0, 5.0))
    }

    @Test
    fun `applyTempWindOverrides prefers WINDY over HOT when both fire`() {
        // wind > 30 km/h is the first branch in the when expression.
        assertEquals(SuzukiWeather.WINDY, applyTempWindOverrides(SuzukiWeather.SUNNY, 45.0, 60.0))
    }

    @Test
    fun `applyTempWindOverrides handles null temp and wind gracefully`() {
        assertEquals(SuzukiWeather.CLOUDY, applyTempWindOverrides(SuzukiWeather.CLOUDY, null, null))
    }
}
