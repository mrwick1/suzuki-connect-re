package dev.mrwick.gixxerbridge.util

/** Hex encode/decode helpers — lowercase output, tolerant input. */
object Hex {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** Encode bytes as lowercase hex with optional [separator] between byte pairs. */
    fun encode(bytes: ByteArray, separator: String = " "): String {
        if (bytes.isEmpty()) return ""
        // Pre-size the buffer: 2 chars per byte + (n-1) separators.
        val sb = StringBuilder(bytes.size * 2 + (bytes.size - 1) * separator.length)
        for (i in bytes.indices) {
            if (i > 0 && separator.isNotEmpty()) sb.append(separator)
            val v = bytes[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Encode a single byte as a 2-char lowercase hex string. */
    fun encodeByte(b: Byte): String {
        val v = b.toInt() and 0xFF
        return "${HEX_CHARS[v ushr 4]}${HEX_CHARS[v and 0x0F]}"
    }

    /**
     * Parse a hex string to bytes. Tolerates uppercase, spaces, colons, and a `0x` prefix.
     * Throws [IllegalArgumentException] on odd hex length or non-hex characters.
     */
    fun decode(hex: String): ByteArray {
        // Strip whitespace, colons, and any 0x / 0X prefixes (including mid-string ones
        // that may appear when each byte is written as "0xA5 0x37 …").
        val sb = StringBuilder(hex.length)
        var i = 0
        while (i < hex.length) {
            val c = hex[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ':' -> i++
                (c == '0') && i + 1 < hex.length && (hex[i + 1] == 'x' || hex[i + 1] == 'X') -> i += 2
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }

        val clean = sb.toString()
        require(clean.length % 2 == 0) {
            "hex string must have even length after normalization, got ${clean.length}: \"$clean\""
        }

        val out = ByteArray(clean.length / 2)
        for (j in out.indices) {
            val hi = hexDigit(clean[j * 2])
            val lo = hexDigit(clean[j * 2 + 1])
            out[j] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("non-hex character '$c' (0x${c.code.toString(16)})")
    }
}
