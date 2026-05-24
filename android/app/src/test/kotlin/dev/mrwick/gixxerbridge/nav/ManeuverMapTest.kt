package dev.mrwick.gixxerbridge.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests for the text->maneuver-id heuristic in [ManeuverMap]. */
class ManeuverMapTest {

    @Test
    fun `null and empty input return generic arrow`() {
        assertEquals(ManeuverMap.GENERIC_ARROW, ManeuverMap.fromText(null))
        assertEquals(ManeuverMap.GENERIC_ARROW, ManeuverMap.fromText(""))
        assertEquals(ManeuverMap.GENERIC_ARROW, ManeuverMap.fromText("   "))
    }

    @Test
    fun `unknown text falls back to generic arrow`() {
        assertEquals(ManeuverMap.GENERIC_ARROW, ManeuverMap.fromText("dance the macarena"))
    }

    @Test
    fun `u-turn variants map to 23`() {
        assertEquals(23, ManeuverMap.fromText("Make a U-turn at MG Road"))
        assertEquals(23, ManeuverMap.fromText("Make a u turn"))
        assertEquals(23, ManeuverMap.fromText("Make a U at the next light"))
    }

    @Test
    fun `roundabout maps to 71`() {
        assertEquals(71, ManeuverMap.fromText("Enter the roundabout"))
        assertEquals(71, ManeuverMap.fromText("At the roundabout, take the exit"))
    }

    @Test
    fun `exit right and left are distinct`() {
        assertEquals(25, ManeuverMap.fromText("Take exit to the right"))
        assertEquals(24, ManeuverMap.fromText("Take exit on the left"))
        // Plain "Take exit" → right (default)
        assertEquals(25, ManeuverMap.fromText("Take exit 4B"))
    }

    @Test
    fun `slight and sharp variants beat plain turn`() {
        assertEquals(7, ManeuverMap.fromText("Slight right onto Highway 8"))
        assertEquals(6, ManeuverMap.fromText("Slight left onto Brigade Road"))
        assertEquals(5, ManeuverMap.fromText("Sharp right onto Service Road"))
        assertEquals(4, ManeuverMap.fromText("Sharp left onto Service Road"))
    }

    @Test
    fun `keep right and keep left`() {
        assertEquals(21, ManeuverMap.fromText("Keep right at the fork"))
        assertEquals(20, ManeuverMap.fromText("Keep left at the fork"))
    }

    @Test
    fun `plain turn left and right`() {
        assertEquals(3, ManeuverMap.fromText("Turn right onto MG Road"))
        assertEquals(3, ManeuverMap.fromText("Right onto MG Road"))
        assertEquals(3, ManeuverMap.fromText("Right on MG Road"))
        assertEquals(2, ManeuverMap.fromText("Turn left onto Brigade Road"))
        assertEquals(2, ManeuverMap.fromText("Left onto Brigade Road"))
        assertEquals(2, ManeuverMap.fromText("Left on Brigade Road"))
    }

    @Test
    fun `continue straight head all map to generic arrow`() {
        assertEquals(8, ManeuverMap.fromText("Continue straight for 2 km"))
        assertEquals(8, ManeuverMap.fromText("Go straight"))
        assertEquals(8, ManeuverMap.fromText("Head north on Outer Ring Road"))
    }

    @Test
    fun `arrive at destination maps to 50`() {
        assertEquals(50, ManeuverMap.fromText("You have arrived at your destination"))
        assertEquals(50, ManeuverMap.fromText("Destination on the right"))
        assertEquals(50, ManeuverMap.fromText("Arrive at MG Road"))
    }

    @Test
    fun `merge maps to 11`() {
        assertEquals(11, ManeuverMap.fromText("Merge onto Highway 4"))
    }

    @Test
    fun `slight-right beats turn-right (priority ordering)`() {
        // "Slight right" contains "right" — proves the priority cascade is correct.
        assertEquals(7, ManeuverMap.fromText("Slight right onto Service Road"))
        // And "sharp right" doesn't accidentally hit plain "right"
        assertEquals(5, ManeuverMap.fromText("Sharp right onto Service Road"))
    }

    @Test
    fun `u-turn beats turn-left (priority ordering)`() {
        // "U-turn" must not be parsed as "turn ... left/right"
        assertEquals(23, ManeuverMap.fromText("Make a u-turn at the next intersection"))
    }

    @Test
    fun `bitmap-hash registration works`() {
        ManeuverMap.registerBitmapHash(0xDEADBEEFL, 42)
        assertEquals(42, ManeuverMap.fromBitmapHash(0xDEADBEEFL))
        assertEquals(null, ManeuverMap.fromBitmapHash(0xABCDEF01L))
    }
}
