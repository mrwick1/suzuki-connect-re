package dev.mrwick.gixxerbridge.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [Greetings]' newline-serialization helpers and defaults.
 *
 * DataStore I/O is not exercised here (would need Robolectric / instrumented);
 * this validates the encode/decode round-trip used internally by the class,
 * plus the `{name}` substitution contract consumed by `WelcomeFrame`.
 */
class GreetingsTest {

    // ---------- Round-trip ----------

    @Test fun encodeJoinsWithNewlines() {
        assertEquals("a\nb\nc", encodeGreetings(listOf("a", "b", "c")))
    }

    @Test fun encodeDropsBlankAndTrimsWhitespace() {
        assertEquals("hello\nworld", encodeGreetings(listOf("  hello  ", "", "   ", "world")))
    }

    @Test fun decodeSplitsOnNewlines() {
        assertEquals(listOf("a", "b", "c"), decodeGreetings("a\nb\nc"))
    }

    @Test fun decodeNullReturnsDefaults() {
        assertEquals(Greetings.DEFAULT_GREETINGS, decodeGreetings(null))
    }

    @Test fun decodeBlankReturnsDefaults() {
        assertEquals(Greetings.DEFAULT_GREETINGS, decodeGreetings(""))
        assertEquals(Greetings.DEFAULT_GREETINGS, decodeGreetings("   \n  "))
    }

    @Test fun encodeDecodeRoundTripPreservesEntries() {
        val items = listOf("Hi {name}", "Ride safe", "Looking sharp")
        assertEquals(items, decodeGreetings(encodeGreetings(items)))
    }

    @Test fun encodeDecodeRoundTripDropsBlanks() {
        val items = listOf("Hi {name}", "", "Ride safe", "  ")
        // After encode, blanks are gone, so decode returns only the non-blanks.
        assertEquals(listOf("Hi {name}", "Ride safe"), decodeGreetings(encodeGreetings(items)))
    }

    @Test fun encodeAllBlankYieldsEmptyStringDecodingToDefaults() {
        // Empty serialized blob must round-trip back to a usable (non-empty) list
        // so WelcomeFrame always has something to pick.
        val encoded = encodeGreetings(listOf("", "  ", "\t"))
        assertEquals("", encoded)
        assertEquals(Greetings.DEFAULT_GREETINGS, decodeGreetings(encoded))
    }

    // ---------- Defaults ----------

    @Test fun defaultsAreNonEmpty() {
        assertTrue(Greetings.DEFAULT_GREETINGS.isNotEmpty())
    }

    @Test fun defaultsIncludeNamePlaceholderEntry() {
        // The {name} substitution is the headline feature; at least one default
        // entry should exercise it so first-time users immediately see it work.
        assertTrue(Greetings.DEFAULT_GREETINGS.any { "{name}" in it })
    }

    // ---------- renderGreeting ----------

    @Test fun renderSubstitutesName() {
        assertEquals("Hi Arjun", renderGreeting("Hi {name}", "Arjun"))
    }

    @Test fun renderLeavesNonPlaceholderUntouched() {
        assertEquals("Ride safe", renderGreeting("Ride safe", "Arjun"))
    }

    @Test fun renderSubstitutesEveryOccurrence() {
        assertEquals("Hi Arjun, hello Arjun!", renderGreeting("Hi {name}, hello {name}!", "Arjun"))
    }

    @Test fun renderWithEmptyNameLeavesPlaceholderGap() {
        // Rider may not have entered a name yet; we should not leave the literal
        // "{name}" on the cluster (looks broken). Empty string is fine — it
        // gets caught by the take(4)/padEnd in WelcomeFrame.
        assertNotEquals("Hi {name}", renderGreeting("Hi {name}", ""))
        assertEquals("Hi ", renderGreeting("Hi {name}", ""))
    }
}
