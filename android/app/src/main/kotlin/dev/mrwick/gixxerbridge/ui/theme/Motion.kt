package dev.mrwick.gixxerbridge.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring

/**
 * The two named springs that cover ~all UI state in the GixxerBridge
 * design system. M3 Expressive spring physics — never duration-based tween.
 *
 * See docs/superpowers/specs/2026-05-25-ui-overhaul-design.md §"Motion".
 *
 * Forbidden in implementation:
 *   - tween() for any UI state — use one of these two
 *   - infiniteRepeatable for any non-loading purpose
 *   - Crossfade — use AnimatedContent with SpringStandard
 */
object Motion {
    /** Most state transitions: color, size, position, alpha. */
    val SpringStandard: AnimationSpec<Float> =
        spring(dampingRatio = 0.85f, stiffness = 400f)

    /** Sheet open, big number reveals, post-ride summary. */
    val SpringSoft: AnimationSpec<Float> =
        spring(dampingRatio = 0.75f, stiffness = 200f)
}
