package dev.mrwick.gixxerbridge.data

import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyDismissCodecTest {
    @Test fun roundTrips() {
        val set = setOf(1_780_390_200_000L, 1_780_476_600_000L)
        assertEquals(set, JourneyDismissCodec.decode(JourneyDismissCodec.encode(set)))
    }

    @Test fun decodeBlankIsEmpty() {
        assertEquals(emptySet<Long>(), JourneyDismissCodec.decode(""))
        assertEquals(emptySet<Long>(), JourneyDismissCodec.decode("[]"))
    }
}
