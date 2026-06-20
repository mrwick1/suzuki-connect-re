package dev.mrwick.redline.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for [ClusterRangeFormatter]. No Android, no NavFrame. */
class ClusterRangeFormatterTest {

    @Test fun `formats a normal range into 4-char km slot`() {
        val r = ClusterRangeFormatter.format(140.0)
        // distNext carries the number (right-aligned, <=4 chars), unit 'K'.
        assertEquals("140", r.kmText)
        assertEquals("K", r.kmUnit)
        assertEquals(false, r.isUnavailable)
    }

    @Test fun `rounds to nearest km`() {
        assertEquals("141", ClusterRangeFormatter.format(140.6).kmText)
        assertEquals("140", ClusterRangeFormatter.format(140.4).kmText)
    }

    @Test fun `clamps a 4-plus-digit range to the 4-char slot`() {
        // 12345 km can't fit a 4-char slot; cap at 9999 rather than truncate-garbage.
        assertEquals("9999", ClusterRangeFormatter.format(12_345.0).kmText)
    }

    @Test fun `zero and negative collapse to zero`() {
        assertEquals("0", ClusterRangeFormatter.format(0.0).kmText)
        assertEquals("0", ClusterRangeFormatter.format(-5.0).kmText)
    }

    @Test fun `null range is unavailable`() {
        val r = ClusterRangeFormatter.format(null)
        assertEquals(true, r.isUnavailable)
        assertEquals("----", r.kmText)
    }

    @Test fun `label is the fixed RANGE marker`() {
        assertEquals("RANGE", ClusterRangeFormatter.LABEL)
    }
}
