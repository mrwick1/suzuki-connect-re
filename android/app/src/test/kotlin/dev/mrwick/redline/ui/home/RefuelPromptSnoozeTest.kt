package dev.mrwick.redline.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [isRefuelPromptSnoozed].
 *
 * This is the only pure logic extracted from the snooze feature — the rest
 * (DataStore persistence, ViewModel wiring) requires Robolectric / instrumented
 * tests. These tests validate the visibility predicate contract in isolation.
 */
class RefuelPromptSnoozeTest {

    // ------- Never-snoozed state -------------------------------------------

    @Test
    fun notSnoozed_whenSnoozedAtOdoIsNull() {
        // null snoozedAtFillOdo = "never snoozed" → prompt must show
        assertFalse(isRefuelPromptSnoozed(snoozedAtFillOdo = null, latestFillOdo = 12_345))
    }

    @Test
    fun notSnoozed_whenBothNull() {
        // no snooze + no fills → prompt shows (fresh install / no history)
        assertFalse(isRefuelPromptSnoozed(snoozedAtFillOdo = null, latestFillOdo = null))
    }

    // ------- Snoozed, no fills logged yet ----------------------------------

    @Test
    fun notSnoozed_whenSnoozedButNoFillsExist() {
        // Snooze was recorded but fills table is now empty (e.g. user deleted fills).
        // Cannot correlate → show the prompt rather than hide it indefinitely.
        assertFalse(isRefuelPromptSnoozed(snoozedAtFillOdo = 10_000, latestFillOdo = null))
    }

    // ------- Snooze active (same fill, no new fill logged) -----------------

    @Test
    fun snoozed_whenSnoozedAtOdoMatchesLatestFillOdo() {
        // Rider dismissed the prompt; latest fill odo hasn't changed → hidden
        assertTrue(isRefuelPromptSnoozed(snoozedAtFillOdo = 12_345, latestFillOdo = 12_345))
    }

    @Test
    fun snoozed_atZeroOdoEdgeCase() {
        // Edge case: first fill recorded at odo 0 (e.g. unit test / new bike)
        assertTrue(isRefuelPromptSnoozed(snoozedAtFillOdo = 0, latestFillOdo = 0))
    }

    // ------- Snooze re-armed (new fill logged after snooze) ----------------

    @Test
    fun notSnoozed_whenNewFillLoggedAfterSnooze() {
        // Rider filled up again → latest fill odo changed → re-armed
        assertFalse(isRefuelPromptSnoozed(snoozedAtFillOdo = 12_345, latestFillOdo = 14_680))
    }

    @Test
    fun notSnoozed_whenLatestFillOdoLessThanSnoozed() {
        // Unusual but possible: user edited/deleted a fill, new latest is earlier.
        // Odo changed → re-arm (don't assume direction).
        assertFalse(isRefuelPromptSnoozed(snoozedAtFillOdo = 14_680, latestFillOdo = 12_345))
    }

    // ------- Idempotency / boundary ------------------------------------------

    @Test
    fun notSnoozed_whenOdoDiffersBy1() {
        // Any change in odo re-arms; even 1 km difference counts as a new fill
        assertFalse(isRefuelPromptSnoozed(snoozedAtFillOdo = 12_345, latestFillOdo = 12_346))
        assertFalse(isRefuelPromptSnoozed(snoozedAtFillOdo = 12_345, latestFillOdo = 12_344))
    }
}
