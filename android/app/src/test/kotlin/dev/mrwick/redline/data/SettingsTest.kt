package dev.mrwick.redline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the conversion helpers and defaults exposed by [Settings].
 *
 * These intentionally do not exercise DataStore itself (that would require
 * Robolectric or an instrumented test); they only verify the null<->sentinel
 * conversion contract and the public default constants.
 */
class SettingsTest {

    // ---------- String <-> nullable ----------

    @Test fun encodeNullStringIsEmpty() {
        assertEquals("", encodeNullableString(null))
    }

    @Test fun encodeNonNullStringRoundTrips() {
        assertEquals("AA:BB:CC:DD:EE:FF", encodeNullableString("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun decodeEmptyStringIsNull() {
        assertNull(decodeNullableString(""))
    }

    @Test fun decodeMissingStringIsNull() {
        assertNull(decodeNullableString(null))
    }

    @Test fun decodeNonEmptyStringPassesThrough() {
        assertEquals("hello", decodeNullableString("hello"))
    }

    @Test fun stringRoundTripPreservesValue() {
        val original = "SBXXXXXXXXXX"
        assertEquals(original, decodeNullableString(encodeNullableString(original)))
    }

    @Test fun stringRoundTripPreservesNull() {
        assertNull(decodeNullableString(encodeNullableString(null)))
    }

    // ---------- Double <-> nullable ----------

    @Test fun encodeNullDoubleIsNaN() {
        assertTrue(encodeNullableDouble(null).isNaN())
    }

    @Test fun encodeNonNullDoubleRoundTrips() {
        assertEquals(12.97, encodeNullableDouble(12.97), 0.0)
    }

    @Test fun decodeNaNIsNull() {
        assertNull(decodeNullableDouble(Double.NaN))
    }

    @Test fun decodeMissingDoubleIsNull() {
        assertNull(decodeNullableDouble(null))
    }

    @Test fun decodeRegularDoublePassesThrough() {
        assertEquals(77.59, decodeNullableDouble(77.59))
    }

    @Test fun decodeZeroIsNotNull() {
        // Important edge case: 0.0 must round-trip to 0.0, not null.
        assertEquals(0.0, decodeNullableDouble(0.0))
        assertFalse(decodeNullableDouble(0.0) == null)
    }

    @Test fun doubleRoundTripPreservesValue() {
        val lat = 12.9716
        val lng = 77.5946
        assertEquals(lat, decodeNullableDouble(encodeNullableDouble(lat)))
        assertEquals(lng, decodeNullableDouble(encodeNullableDouble(lng)))
    }

    @Test fun doubleRoundTripPreservesNull() {
        assertNull(decodeNullableDouble(encodeNullableDouble(null)))
    }

    // ---------- Int <-> nullable ----------

    @Test fun encodeNullIntIsMinusOne() {
        assertEquals(-1, encodeNullableInt(null))
    }

    @Test fun encodeNonNullIntRoundTrips() {
        assertEquals(3500, encodeNullableInt(3500))
        assertEquals(0, encodeNullableInt(0))
    }

    @Test fun decodeMinusOneIsNull() {
        assertNull(decodeNullableInt(-1))
    }

    @Test fun decodeMissingIntIsNull() {
        assertNull(decodeNullableInt(null))
    }

    @Test fun decodeNonNegativeIntPassesThrough() {
        assertEquals(0, decodeNullableInt(0))
        assertEquals(120, decodeNullableInt(120))
    }

    @Test fun intRoundTripPreservesValue() {
        assertEquals(8000, decodeNullableInt(encodeNullableInt(8000)))
        assertEquals(0, decodeNullableInt(encodeNullableInt(0)))
    }

    @Test fun intRoundTripPreservesNull() {
        assertNull(decodeNullableInt(encodeNullableInt(null)))
    }

    // ---------- Public defaults ----------

    @Test fun defaultRiderNameIsRider() {
        assertEquals("Rider", Settings.DEFAULT_RIDER_NAME)
    }

    @Test fun defaultServiceIntervalIs5000Km() {
        assertEquals(5000, Settings.DEFAULT_SERVICE_INTERVAL_KM)
    }

    @Test fun defaultAllowlistIncludesDialerAndMessaging() {
        assertTrue("com.google.android.dialer" in Settings.DEFAULT_ALLOWLIST)
        assertTrue("com.google.android.apps.messaging" in Settings.DEFAULT_ALLOWLIST)
    }

    @Test fun defaultAllowlistIncludesCommonChatApps() {
        val expected = setOf(
            "com.whatsapp",
            "org.telegram.messenger",
            "com.discord",
            "com.slack",
        )
        assertTrue(
            "default allowlist missing some common chat apps",
            Settings.DEFAULT_ALLOWLIST.containsAll(expected),
        )
    }

    @Test fun defaultAllowlistIncludesAudioApps() {
        val expected = setOf(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "au.com.shiftyjelly.pocketcasts",
        )
        assertTrue(
            "default allowlist missing some audio apps",
            Settings.DEFAULT_ALLOWLIST.containsAll(expected),
        )
    }
}
