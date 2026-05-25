package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.animation.core.SpringSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTest {

    @Test
    fun springStandard_uses_spec_stiffness_and_damping() {
        val s = Motion.SpringStandard as SpringSpec<Float>
        assertEquals(400f, s.stiffness, 0.001f)
        assertEquals(0.85f, s.dampingRatio, 0.001f)
    }

    @Test
    fun springSoft_uses_spec_stiffness_and_damping() {
        val s = Motion.SpringSoft as SpringSpec<Float>
        assertEquals(200f, s.stiffness, 0.001f)
        assertEquals(0.75f, s.dampingRatio, 0.001f)
    }
}
