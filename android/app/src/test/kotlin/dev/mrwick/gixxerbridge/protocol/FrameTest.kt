package dev.mrwick.gixxerbridge.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the shared frame helpers in [Frame.kt]. */
class FrameTest {

    @Test fun constants() {
        assertEquals(30, FRAME_LEN)
        assertEquals(0xA5.toByte(), HEADER)
        assertEquals(0x7F.toByte(), TERMINATOR)
    }

    @Test fun checksumKnownA531() {
        val f = hex("a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f")
        assertEquals(0x4C, checksum(f))
    }

    @Test fun checksumKnownA533() {
        val f = hex("a5333359323134003035303135344e4effffffffff010001ffffffff1a7f")
        assertEquals(0x1A, checksum(f))
    }

    @Test fun checksumKnownA536() {
        val f = hex("a53641524a554e000000000000000000000000000000ffffffffff46f77f")
        assertEquals(0xF7, checksum(f))
    }

    @Test fun isWellFormedPassesForRealFrames() {
        val frames = listOf(
            "a5312eff303038304d30353137504dffffff30352e364b3031ffffff4c7f",
            "a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f",
            "a5333359323134003035303135344e4effffffffff010001ffffffff1a7f",
            "a53641524a554e000000000000000000000000000000ffffffffff46f77f",
            "a5373030303031363732393034393131303030393834394e3439ffff267f",
        )
        for (h in frames) {
            val f = hex(h)
            assertTrue("frame failed integrity check: $h", isWellFormed(f))
        }
    }

    @Test fun isWellFormedRejectsBadHeader() {
        val bad = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
        bad[0] = 0x00
        assertFalse(isWellFormed(bad))
    }

    @Test fun isWellFormedRejectsBadTerminator() {
        val bad = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
        bad[29] = 0x00
        assertFalse(isWellFormed(bad))
    }

    @Test fun isWellFormedRejectsBadChecksum() {
        val bad = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
        bad[28] = (bad[28].toInt() xor 0x01).toByte()
        assertFalse(isWellFormed(bad))
    }

    @Test fun isWellFormedRejectsWrongLength() {
        val good = hex("a53108ff303132304d30353238504dffffff30352e314b3131ffffff1f7f")
        assertFalse(isWellFormed(good.copyOfRange(0, 29)))
        assertFalse(isWellFormed(good + byteArrayOf(0)))
    }
}

/** Decode a lowercase-hex string to a [ByteArray]. Shared across the test package. */
internal fun hex(s: String): ByteArray {
    require(s.length % 2 == 0) { "odd-length hex string: $s" }
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        out[i] = ((s[i * 2].digitToInt(16) shl 4) or s[i * 2 + 1].digitToInt(16)).toByte()
    }
    return out
}
