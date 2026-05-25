package dev.mrwick.gixxerbridge.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the OEM-ported Mappls-ID -> cluster-byte translation in
 * [ManeuverMap.mapplsIdToClusterByte]. Source of truth: A0.C() in the
 * decompiled Suzuki Connect APK (see docs/superpowers/specs/2026-05-25-maneuver-id-rework-design.md).
 *
 * The "default" branch is what our Gixxer SF 150 hits; the "Burgman" branch
 * is preserved for correctness on the small set of bikes the OEM special-cases.
 */
class ManeuverMapTest {

    @Test
    fun `default branch table — straight maneuvers (0_dot_dot_7)`() {
        assertEquals(1, ManeuverMap.mapplsIdToClusterByte(0, null))
        assertEquals(2, ManeuverMap.mapplsIdToClusterByte(1, null))
        assertEquals(3, ManeuverMap.mapplsIdToClusterByte(2, null))
        assertEquals(4, ManeuverMap.mapplsIdToClusterByte(3, null))
        assertEquals(5, ManeuverMap.mapplsIdToClusterByte(4, null))
        assertEquals(6, ManeuverMap.mapplsIdToClusterByte(5, null))
        assertEquals(7, ManeuverMap.mapplsIdToClusterByte(6, null))
        assertEquals(8, ManeuverMap.mapplsIdToClusterByte(7, null))
    }

    @Test
    fun `default branch — 8,9,10 collapse to cluster byte 9`() {
        assertEquals(9, ManeuverMap.mapplsIdToClusterByte(8, null))
        assertEquals(9, ManeuverMap.mapplsIdToClusterByte(9, null))
        assertEquals(9, ManeuverMap.mapplsIdToClusterByte(10, null))
    }

    @Test
    fun `default branch — 26,27,28 collapse to 31`() {
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(26, null))
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(27, null))
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(28, null))
    }

    @Test
    fun `default branch — 30,31 collapse to 32`() {
        assertEquals(32, ManeuverMap.mapplsIdToClusterByte(30, null))
        assertEquals(32, ManeuverMap.mapplsIdToClusterByte(31, null))
    }

    @Test
    fun `default branch — keep-lane and turn variants`() {
        assertEquals(11, ManeuverMap.mapplsIdToClusterByte(11, null))
        assertEquals(12, ManeuverMap.mapplsIdToClusterByte(12, null))
        assertEquals(13, ManeuverMap.mapplsIdToClusterByte(13, null))
        assertEquals(14, ManeuverMap.mapplsIdToClusterByte(14, null))
        assertEquals(31, ManeuverMap.mapplsIdToClusterByte(15, null))
        assertEquals(32, ManeuverMap.mapplsIdToClusterByte(16, null))
        assertEquals(29, ManeuverMap.mapplsIdToClusterByte(17, null))
        assertEquals(30, ManeuverMap.mapplsIdToClusterByte(18, null))
        assertEquals(27, ManeuverMap.mapplsIdToClusterByte(19, null))
        assertEquals(28, ManeuverMap.mapplsIdToClusterByte(20, null))
        assertEquals(33, ManeuverMap.mapplsIdToClusterByte(21, null))
        assertEquals(34, ManeuverMap.mapplsIdToClusterByte(22, null))
        assertEquals(35, ManeuverMap.mapplsIdToClusterByte(23, null))
        assertEquals(36, ManeuverMap.mapplsIdToClusterByte(24, null))
        assertEquals(37, ManeuverMap.mapplsIdToClusterByte(25, null))
    }

    @Test
    fun `default branch — compass departures 50-57`() {
        assertEquals(40, ManeuverMap.mapplsIdToClusterByte(50, null))
        assertEquals(41, ManeuverMap.mapplsIdToClusterByte(51, null))
        assertEquals(42, ManeuverMap.mapplsIdToClusterByte(52, null))
        assertEquals(15, ManeuverMap.mapplsIdToClusterByte(53, null))
        assertEquals(16, ManeuverMap.mapplsIdToClusterByte(54, null))
        assertEquals(17, ManeuverMap.mapplsIdToClusterByte(55, null))
        assertEquals(18, ManeuverMap.mapplsIdToClusterByte(56, null))
        assertEquals(19, ManeuverMap.mapplsIdToClusterByte(57, null))
    }

    @Test
    fun `default branch — roundabouts 58-72`() {
        assertEquals(46, ManeuverMap.mapplsIdToClusterByte(58, null))
        assertEquals(47, ManeuverMap.mapplsIdToClusterByte(59, null))
        assertEquals(48, ManeuverMap.mapplsIdToClusterByte(60, null))
        assertEquals(49, ManeuverMap.mapplsIdToClusterByte(61, null))
        assertEquals(50, ManeuverMap.mapplsIdToClusterByte(62, null))
        assertEquals(51, ManeuverMap.mapplsIdToClusterByte(63, null))
        assertEquals(52, ManeuverMap.mapplsIdToClusterByte(64, null))
        assertEquals(20, ManeuverMap.mapplsIdToClusterByte(65, null))
        assertEquals(21, ManeuverMap.mapplsIdToClusterByte(66, null))
        assertEquals(22, ManeuverMap.mapplsIdToClusterByte(67, null))
        assertEquals(23, ManeuverMap.mapplsIdToClusterByte(68, null))
        assertEquals(24, ManeuverMap.mapplsIdToClusterByte(69, null))
        assertEquals(25, ManeuverMap.mapplsIdToClusterByte(70, null))
        assertEquals(26, ManeuverMap.mapplsIdToClusterByte(71, null))
        assertEquals(45, ManeuverMap.mapplsIdToClusterByte(72, null))
    }

    @Test
    fun `default branch — motorway exits 73,74,75 and u-turn 41`() {
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(73, null))
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(74, null))
        assertEquals(10, ManeuverMap.mapplsIdToClusterByte(75, null))
        assertEquals(39, ManeuverMap.mapplsIdToClusterByte(41, null))
    }

    @Test
    fun `Burgman branch — 58 and 74 diverge from default`() {
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "Burgman Street-TFT Edition"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "Burgman Street-TFT Edition"))

        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "e-ACCESS"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "e-ACCESS"))

        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "Access-TFT Edition"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "Access-TFT Edition"))

        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "Access"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, "Access"))
    }

    @Test
    fun `Burgman branch — all other rows match default`() {
        assertEquals(1, ManeuverMap.mapplsIdToClusterByte(0, "Burgman Street-TFT Edition"))
        assertEquals(4, ManeuverMap.mapplsIdToClusterByte(3, "Burgman Street-TFT Edition"))
        assertEquals(45, ManeuverMap.mapplsIdToClusterByte(72, "Burgman Street-TFT Edition"))
        assertEquals(10, ManeuverMap.mapplsIdToClusterByte(75, "Burgman Street-TFT Edition"))
    }

    @Test
    fun `SBS51 BTID triggers Burgman branch regardless of vehicleModel`() {
        // A0.java:646 + :660 check d.k().contains("SBS51") in addition to the
        // vehicle-name set. Any BTID containing that substring takes the
        // Burgman path for Mappls 58 and 74.
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, null, "SBS51-ABC123"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, null, "SBS51-ABC123"))

        // Substring match: contains, not equals.
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, null, "BIKE-SBS51-X"))
        assertEquals(38, ManeuverMap.mapplsIdToClusterByte(74, null, "BIKE-SBS51-X"))

        // BTID without SBS51 + vehicleModel not in Burgman set → default branch.
        assertEquals(46, ManeuverMap.mapplsIdToClusterByte(58, null, "GIXXER-001"))
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(74, null, "GIXXER-001"))

        // SBS51 in BTID overrides default-branch vehicleModel.
        assertEquals(44, ManeuverMap.mapplsIdToClusterByte(58, "Gixxer SF 150", "SBS51"))
    }

    @Test
    fun `unmapped Mappls IDs return null (cluster keeps previous glyph)`() {
        assertNull(ManeuverMap.mapplsIdToClusterByte(29, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(32, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(33, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(40, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(42, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(45, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(76, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(255, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(-1, null))
    }

    @Test
    fun `Mappls 36 (ferry) returns null — OEM leaves e0 untouched`() {
        assertNull(ManeuverMap.mapplsIdToClusterByte(36, null))
        assertNull(ManeuverMap.mapplsIdToClusterByte(36, "Burgman Street-TFT Edition"))
    }
}
