package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {

    @Test
    fun springSnap_values() {
        val s = Motion.SpringSnap as SpringSpec<Float>
        assertEquals(700f, s.stiffness, 0.001f)
        assertEquals(0.6f, s.dampingRatio, 0.001f)
    }

    @Test
    fun springSweep_values() {
        val s = Motion.SpringSweep as SpringSpec<Float>
        assertEquals(120f, s.stiffness, 0.001f)
        assertEquals(0.55f, s.dampingRatio, 0.001f)
    }

    @Test
    fun springBouncy_values() {
        val s = Motion.SpringBouncy as SpringSpec<Float>
        assertEquals(500f, s.stiffness, 0.001f)
        assertEquals(0.45f, s.dampingRatio, 0.001f)
    }
}
