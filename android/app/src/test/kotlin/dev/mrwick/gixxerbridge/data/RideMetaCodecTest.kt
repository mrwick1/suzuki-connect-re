package dev.mrwick.gixxerbridge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [RideMetaCodec].
 *
 * No Android framework, no DataStore — only the encode/decode pure functions.
 * Covers round-trips for the happy path, empty map, multiple rides, special
 * characters in notes, and corrupt input resilience.
 */
class RideMetaCodecTest {

    // ── Round-trip: single ride ──────────────────────────────────────────────

    @Test
    fun roundTripSingleFavoritedRide() {
        val meta = RideMeta(favorite = true, tags = setOf("commute"), note = "Rainy morning")
        val map = mapOf(1_748_000_000_000L to meta)
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(map))

        assertEquals(1, decoded.size)
        val result = decoded[1_748_000_000_000L]!!
        assertTrue(result.favorite)
        assertEquals(setOf("commute"), result.tags)
        assertEquals("Rainy morning", result.note)
    }

    @Test
    fun roundTripDefaultMeta() {
        val meta = RideMeta()  // all defaults
        val map = mapOf(1_000L to meta)
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(map))

        val result = decoded[1_000L]!!
        assertFalse(result.favorite)
        assertTrue(result.tags.isEmpty())
        assertEquals("", result.note)
    }

    // ── Round-trip: empty map ────────────────────────────────────────────────

    @Test
    fun encodeEmptyMapProducesEmptyArray() {
        val encoded = RideMetaCodec.encode(emptyMap())
        assertEquals("[]", encoded)
    }

    @Test
    fun decodeEmptyStringReturnsEmptyMap() {
        assertTrue(RideMetaCodec.decode("").isEmpty())
    }

    @Test
    fun decodeEmptyArrayReturnsEmptyMap() {
        assertTrue(RideMetaCodec.decode("[]").isEmpty())
    }

    @Test
    fun roundTripEmptyMap() {
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(emptyMap()))
        assertTrue(decoded.isEmpty())
    }

    // ── Round-trip: multiple rides ───────────────────────────────────────────

    @Test
    fun roundTripMultipleRides() {
        val map = mapOf(
            1_748_000_000_000L to RideMeta(favorite = true, tags = setOf("commute", "daily"), note = ""),
            1_748_086_400_000L to RideMeta(favorite = false, tags = emptySet(), note = "Solo highway blast"),
            1_748_172_800_000L to RideMeta(favorite = true, tags = setOf("twisties"), note = "With the squad"),
        )
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(map))

        assertEquals(3, decoded.size)

        val r1 = decoded[1_748_000_000_000L]!!
        assertTrue(r1.favorite)
        assertEquals(setOf("commute", "daily"), r1.tags)
        assertEquals("", r1.note)

        val r2 = decoded[1_748_086_400_000L]!!
        assertFalse(r2.favorite)
        assertTrue(r2.tags.isEmpty())
        assertEquals("Solo highway blast", r2.note)

        val r3 = decoded[1_748_172_800_000L]!!
        assertTrue(r3.favorite)
        assertEquals(setOf("twisties"), r3.tags)
        assertEquals("With the squad", r3.note)
    }

    // ── Special characters in notes ──────────────────────────────────────────

    @Test
    fun roundTripSpecialCharsInNote() {
        val note = "Quotes \"here\", backslash \\, newline\nand tab\t. Unicode: ☀️🏍️"
        val map = mapOf(42L to RideMeta(note = note))
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(map))
        assertEquals(note, decoded[42L]!!.note)
    }

    @Test
    fun roundTripSpecialCharsInTags() {
        val tags = setOf("morning ride", "30°C heat", "café stop")
        val map = mapOf(99L to RideMeta(tags = tags))
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(map))
        assertEquals(tags, decoded[99L]!!.tags)
    }

    @Test
    fun roundTripLongNote() {
        val note = "A".repeat(1000)  // well beyond the 500-char UI cap; codec is not the gatekeeper
        val map = mapOf(1L to RideMeta(note = note))
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(map))
        assertEquals(note, decoded[1L]!!.note)
    }

    // ── Corrupt input resilience ─────────────────────────────────────────────

    @Test
    fun decodeCorruptJsonReturnsEmptyMap() {
        assertTrue(RideMetaCodec.decode("not json at all {{{{").isEmpty())
    }

    @Test
    fun decodePartialJsonReturnsEmptyMap() {
        assertTrue(RideMetaCodec.decode("[{\"k\":123,\"fav\":true").isEmpty())
    }

    @Test
    fun decodeBlankStringReturnsEmptyMap() {
        assertTrue(RideMetaCodec.decode("   ").isEmpty())
    }

    // ── Key stability ────────────────────────────────────────────────────────

    @Test
    fun longKeyPreservesFullPrecision() {
        // Ensure millisecond timestamps (which exceed Int range) survive the
        // JSON round-trip without truncation.
        val key = 1_748_086_412_345L
        val map = mapOf(key to RideMeta(favorite = true))
        val decoded = RideMetaCodec.decode(RideMetaCodec.encode(map))
        assertTrue(decoded.containsKey(key))
        assertTrue(decoded[key]!!.favorite)
    }
}
