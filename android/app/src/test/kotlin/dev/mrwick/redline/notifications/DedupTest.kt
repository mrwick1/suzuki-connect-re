package dev.mrwick.redline.notifications

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DedupTest {

    @After fun cleanup() { Dedup.clearForTest() }

    @Test fun `first sighting always passes`() {
        assertTrue(Dedup.firstTime("abc"))
    }

    @Test fun `repeated immediate sightings are dropped`() {
        assertTrue(Dedup.firstTime("call:1234"))
        assertFalse(Dedup.firstTime("call:1234"))
        assertFalse(Dedup.firstTime("call:1234"))
    }

    @Test fun `different fingerprints don't interfere`() {
        assertTrue(Dedup.firstTime("call:1234"))
        assertTrue(Dedup.firstTime("call:5678"))
        assertTrue(Dedup.firstTime("sms:com.whatsapp:Alice:hello"))
        assertFalse(Dedup.firstTime("call:1234"))
    }

    @Test fun `clear resets state`() {
        Dedup.firstTime("x")
        Dedup.clearForTest()
        assertTrue(Dedup.firstTime("x"))
    }

    @Test fun `many distinct entries still tracked correctly`() {
        for (i in 0 until 200) {
            assertTrue("entry $i should be first-time", Dedup.firstTime("k$i"))
        }
        // First entries should still be remembered (size cap is 128 with prune;
        // we test that recent entries aren't immediately purged).
        assertFalse("last entry should still dedup", Dedup.firstTime("k199"))
    }
}
