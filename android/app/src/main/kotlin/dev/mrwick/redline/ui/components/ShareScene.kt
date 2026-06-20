package dev.mrwick.redline.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer

/**
 * One-shot "render this scene to a shareable image" helper (spec §8.2 / §10).
 * Wrap a post-ride / Wrapped scene with [SceneCapture.modifier]; later call
 * [SceneCapture.capture] (off a coroutine, e.g. on a Share tap) to get an
 * [ImageBitmap] of exactly what's on screen. Uses a recorded [GraphicsLayer] —
 * one capture, not a per-frame cost.
 *
 * Usage:
 * ```
 * val scene = rememberSceneCapture()
 * Box(scene.modifier) { RideWrappedScene(...) }
 * // on share tap:
 * scope.launch { shareBitmap(scene.capture()) }
 * ```
 */
class SceneCapture(private val layer: GraphicsLayer) {
    /** Apply to the wrapper of the content you want to be capturable. */
    val modifier: Modifier = Modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }

    /** Capture the most recently recorded content as a bitmap. */
    suspend fun capture(): ImageBitmap = layer.toImageBitmap()
}

@Composable
fun rememberSceneCapture(): SceneCapture {
    val layer = rememberGraphicsLayer()
    return remember(layer) { SceneCapture(layer) }
}
