package dev.mrwick.gixxerbridge.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Bespoke motorcycle glyph language (spec §6.3) — the deliverable that replaces
 * default Material icons (AI-tell #4). 24dp keyline grid, locked 2dp stroke, round
 * caps/joins, built from a shared primitive vocabulary so the family reads as
 * siblings. Tint via the consuming `Icon(tint = …)`.
 *
 * This is the CORE set (maneuver straight/left/right/u-turn + fuel-drop + lean
 * wedge); the full ic_step maneuver table (slight/sharp variants, roundabout-exit-N,
 * merge, fork) extends this with the same builder.
 */
object GixxerIcons {

    private val STROKE = SolidColor(Color.Black) // recolored by Icon(tint=…)
    private const val W = 2f

    private fun stroked(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = STROKE,
                strokeLineWidth = W,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) { block() }
        }.build()

    private fun filled(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = STROKE) { block() }
        }.build()

    /** Continue straight — vertical shaft + chevron head. */
    val ManeuverStraight: ImageVector = stroked("ManeuverStraight") {
        moveTo(12f, 21f); lineTo(12f, 6f)
        moveTo(7f, 11f); lineTo(12f, 6f); lineTo(17f, 11f)
    }

    /** Turn right — up then a rounded corner to the right, head at the end. */
    val ManeuverRight: ImageVector = stroked("ManeuverRight") {
        moveTo(8f, 21f); lineTo(8f, 12f)
        curveTo(8f, 9.8f, 9.8f, 8f, 12f, 8f)
        lineTo(17f, 8f)
        moveTo(14f, 5f); lineTo(17f, 8f); lineTo(14f, 11f)
    }

    /** Turn left — mirror of [ManeuverRight]. */
    val ManeuverLeft: ImageVector = stroked("ManeuverLeft") {
        moveTo(16f, 21f); lineTo(16f, 12f)
        curveTo(16f, 9.8f, 14.2f, 8f, 12f, 8f)
        lineTo(7f, 8f)
        moveTo(10f, 5f); lineTo(7f, 8f); lineTo(10f, 11f)
    }

    /** U-turn — up the left, arc over the top, short way down on the right. */
    val ManeuverUTurn: ImageVector = stroked("ManeuverUTurn") {
        moveTo(7f, 21f); lineTo(7f, 11f)
        arcTo(5f, 5f, 0f, true, true, 17f, 11f)
        lineTo(17f, 15f)
        moveTo(14f, 12f); lineTo(17f, 15f); lineTo(20f, 12f)
    }

    /** Fuel drop — a filled teardrop (never the stock pump). */
    val FuelDrop: ImageVector = filled("FuelDrop") {
        moveTo(12f, 3f)
        curveTo(12f, 3f, 5f, 11f, 5f, 15.5f)
        arcTo(7f, 7f, 0f, false, false, 19f, 15.5f)
        curveTo(19f, 11f, 12f, 3f, 12f, 3f)
        close()
    }

    /** Lean wedge — a tilted bike-lean indicator (unique to motorcycles). */
    val LeanWedge: ImageVector = stroked("LeanWedge") {
        moveTo(4f, 20f); lineTo(20f, 20f)
        moveTo(4f, 20f); lineTo(18f, 8f)
    }
}
