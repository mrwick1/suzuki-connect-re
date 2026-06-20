package dev.mrwick.redline.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * REDLINE PRESS motion language — physics, never fade. Spring-based; interrupts
 * redirect (velocity continuity). See spec §6.1. (Material3 1.3.1 has no
 * MotionScheme.expressive(); these explicit springs are the system.)
 *
 * Forbidden in implementation: Crossfade; tween() for UI state;
 * infiniteRepeatable for anything non-loading (gate ambient loops to
 * visible+connected, pause off-screen).
 */
object Motion {
    /** Most state transitions / commits. */
    val SpringSnap: AnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 700f)

    /** Ignition sweep, big reveals, needle settle. */
    val SpringSweep: AnimationSpec<Float> = spring(dampingRatio = 0.55f, stiffness = 120f)

    /** Rewards / overshoot — gesture rewards, new records. */
    val SpringBouncy: AnimationSpec<Float> = spring(dampingRatio = 0.45f, stiffness = 500f)
}

/**
 * Two motion lanes (spec §3). Expressive = at-rest showpiece motion; Pure =
 * Active-ride / SOS / Diagnostics, where decorative motion is suppressed for
 * glanceability. Components read [LocalMotionLane] and drop non-essential
 * animation when it is [MotionLane.Pure].
 */
enum class MotionLane { Expressive, Pure }

val LocalMotionLane = staticCompositionLocalOf { MotionLane.Expressive }
