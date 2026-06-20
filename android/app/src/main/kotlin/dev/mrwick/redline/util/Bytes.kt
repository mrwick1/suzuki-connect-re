package dev.mrwick.redline.util

/** Slice with inclusive end (matches Python `bytes[start:endInclusive+1]`). */
fun ByteArray.sliceInclusive(start: Int, endInclusive: Int): ByteArray =
    sliceArray(start..endInclusive)

/** Treat bytes as ASCII; non-printable bytes (and high bytes) become '.'. */
fun ByteArray.toPrintableAscii(): String {
    val sb = StringBuilder(size)
    for (b in this) {
        val v = b.toInt() and 0xFF
        // Printable ASCII range: 0x20 (space) through 0x7E (~).
        sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
    }
    return sb.toString()
}

/** Sum of `this[1..27]` mod 256 — matches Suzuki's frame checksum. */
fun ByteArray.suzukiChecksum(): Int {
    // ASSUMED: this extension expects a full 30-byte frame buffer (indices 1..27 must exist).
    // Mirrors `dev.mrwick.redline.protocol.checksum()` and the Python `checksum()`.
    var sum = 0
    for (i in 1..27) {
        sum += (this[i].toInt() and 0xFF)
    }
    return sum and 0xFF
}

/** Structural ByteArray equality (delegates to [contentEquals], unlike `==` which is reference). */
fun ByteArray.contentEqualsTo(other: ByteArray): Boolean = this.contentEquals(other)

/** Convert an unsigned int 0..255 to a [Byte], masking off the high bits. */
fun Int.toByteUnsigned(): Byte = (this and 0xFF).toByte()
