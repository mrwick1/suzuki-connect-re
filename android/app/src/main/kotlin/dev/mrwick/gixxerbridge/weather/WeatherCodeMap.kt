package dev.mrwick.gixxerbridge.weather

/**
 * Suzuki cluster weather codes (byte 21 of the a533 frame).
 * Verified from decompiled C.r(String) in com.suzuki.application.fragment.C
 * (lines 1568-1614 of decompiled/jadx-out/.../C.java).
 */
object SuzukiWeather {
    const val UNKNOWN     = 0   // fallback path — verify on cluster
    const val SUNNY       = 1   // clear / mostly clear / sunny
    const val CLOUDY      = 2   // any cloud variant, hazy
    const val FOG         = 3   // fog / light fog
    const val LIGHT_RAIN  = 4   // showers, light rain
    const val THUNDER     = 5   // t-storms, thunderstorm
    const val RAIN        = 6   // steady rain, rainy
    const val SNOW        = 7   // snow, flurries, ice
    const val SLEET       = 8   // hail, sleet, freezing rain, rain+snow mix
    const val HOT         = 9   // "hot" — verify icon on cluster
    const val COLD        = 10  // "cold" — verify icon on cluster
    const val WINDY       = 11  // windy
}

/** Fallback Suzuki weather code returned when a WMO code is not in [WMO_TO_SUZUKI]. */
const val SUZUKI_DEFAULT_WEATHER = SuzukiWeather.SUNNY

/** WMO weather interpretation code -> Suzuki cluster weather code. */
val WMO_TO_SUZUKI: Map<Int, Int> = mapOf(
    0 to 1, 1 to 1, 2 to 2, 3 to 2,
    45 to 3, 48 to 3,
    51 to 4, 53 to 4, 55 to 4,
    56 to 8, 57 to 8,
    61 to 6, 63 to 6, 65 to 6,
    66 to 8, 67 to 8,
    71 to 7, 73 to 7, 75 to 7, 77 to 7,
    80 to 4, 81 to 4, 82 to 6,
    85 to 7, 86 to 7,
    95 to 5, 96 to 8, 99 to 8,
)

/** Map a WMO code to the corresponding Suzuki cluster code, or [SUZUKI_DEFAULT_WEATHER]. */
fun wmoToSuzuki(wmo: Int): Int = WMO_TO_SUZUKI[wmo] ?: SUZUKI_DEFAULT_WEATHER

/**
 * Suzuki's a533 byte 22 encoding: byte = ceil(temperature in Fahrenheit) + 115.
 * Range: 0 = unset (cluster shows no temp), otherwise byte - 115 = Fahrenheit.
 */
fun celsiusToTempByte(celsius: Double?): Int {
    if (celsius == null) return 0
    val fahrenheit = celsius * 9.0 / 5.0 + 32.0
    val byte = kotlin.math.ceil(fahrenheit).toInt() + 115
    return byte.coerceIn(0, 255)
}

/**
 * Optional refinement: if wind > 30 km/h, override with WINDY.
 * Hot/cold overrides similarly: > 38 C HOT, < 5 C COLD.
 * Returns the original code if no override applies.
 */
fun applyTempWindOverrides(suzukiCode: Int, tempCelsius: Double?, windKmh: Double?): Int = when {
    windKmh != null && windKmh > 30.0 -> SuzukiWeather.WINDY
    tempCelsius != null && tempCelsius > 38.0 -> SuzukiWeather.HOT
    tempCelsius != null && tempCelsius < 5.0 -> SuzukiWeather.COLD
    else -> suzukiCode
}
