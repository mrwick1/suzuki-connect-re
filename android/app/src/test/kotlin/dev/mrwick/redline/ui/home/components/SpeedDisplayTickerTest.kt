package dev.mrwick.redline.ui.home.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedDisplayTickerTest {

    @Test
    fun ticker_returns_zero_at_lastUpdate_time() {
        val f = tickerFraction(nowMs = 1_000, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(0.0f, f, 0.001f)
    }

    @Test
    fun ticker_returns_half_at_midpoint() {
        val f = tickerFraction(nowMs = 3_500, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(0.5f, f, 0.001f)
    }

    @Test
    fun ticker_returns_one_at_or_after_interval() {
        val full = tickerFraction(nowMs = 6_000, lastUpdateMs = 1_000, intervalMs = 5_000)
        val stale = tickerFraction(nowMs = 9_999, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(1.0f, full, 0.001f)
        assertEquals(1.0f, stale, 0.001f)
    }

    @Test
    fun ticker_returns_one_when_no_last_update() {
        val f = tickerFraction(nowMs = 1_000, lastUpdateMs = 0, intervalMs = 5_000)
        assertEquals(1.0f, f, 0.001f)
    }

    @Test
    fun ticker_clamps_negative_age_to_zero() {
        val f = tickerFraction(nowMs = 500, lastUpdateMs = 1_000, intervalMs = 5_000)
        assertEquals(0.0f, f, 0.001f)
    }
}
