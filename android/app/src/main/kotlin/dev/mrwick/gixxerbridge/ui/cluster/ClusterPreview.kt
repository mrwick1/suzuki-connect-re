package dev.mrwick.gixxerbridge.ui.cluster

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.protocol.NavFrame
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * Faux-bike-cluster card showing the latest a531 frame sent to the bike. Goes
 * on the Home screen + Dashboard. Updates in real time via [ClusterState].
 *
 * ASSUMED: NavFrame currently does NOT carry weather or ambient-temperature
 * fields (only manoeuvre / distances / ETA / status). The spec mentioned a
 * weather tile "if non-zero"; with no such field in the data model we render
 * the status byte as a 4-segment "signal" bar instead (status='1' = full,
 * '0','2','4','6' = degraded states).
 */
@Composable
fun ClusterPreview(modifier: Modifier = Modifier) {
    val nav by ClusterState.latestNav.collectAsStateWithLifecycle()
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LivePulse()
                Spacer(Modifier.width(8.dp))
                Text(
                    "CLUSTER PREVIEW",
                    style = MaterialTheme.typography.labelMedium,
                    color = GixxerTokens.textMuted,
                )
                Spacer(Modifier.weight(1f))
                if (nav != null) {
                    StatusBars(nav!!.status)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (nav == null) {
                Text(
                    "Waiting for bike",
                    color = GixxerTokens.textMuted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                ClusterBody(nav!!)
            }
        }
    }
}

@Composable
private fun LivePulse() {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(GixxerTokens.accent.copy(alpha = alpha)),
    )
}

/**
 * 4-segment "signal" indicator. status='1' = healthy/full bars; '0','2','4','6'
 * are degraded; anything else falls back to 2 bars. Mirrors the bike's own
 * cluster behaviour where status governs whether the arrow is rendered.
 */
@Composable
private fun StatusBars(status: String) {
    val (filled, color) = when (status) {
        "1" -> 4 to GixxerTokens.accent
        "3" -> 3 to GixxerTokens.accent
        "5" -> 3 to GixxerTokens.accent
        "0", "2", "4", "6" -> 1 to GixxerTokens.warning
        else -> 2 to GixxerTokens.textMuted // token-mapped from 0xFF64748B
    }
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(4) { i ->
            val on = i < filled
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(if (on) color else GixxerTokens.surfaceElevated), // token-mapped from 0xFF1F2937
            )
        }
    }
}

@Composable
private fun ClusterBody(nav: NavFrame) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ManeuverIcon(nav.maneuverId, modifier = Modifier.size(72.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${nav.distNext} ${nav.distNextUnit}",
                style = MaterialTheme.typography.headlineMedium,
                color = GixxerTokens.textPrimary, // token-mapped from 0xFFA7F3D0
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "ETA ${nav.eta}",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${nav.distTotal} ${nav.distTotalUnit} to go",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted, // token-mapped from 0xFF64748B
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Renders a simple arrow per Mappls maneuver id.
 *
 * ASSUMED: The maneuver-id mapping below (2=left, 3=right, 23=u-turn,
 * 71=roundabout, 50=destination flag, 8=generic forward) is best-effort based
 * on the spec; concrete id semantics live in the bike's cluster firmware and
 * have not been exhaustively decoded. Unknown ids fall back to a forward arrow.
 */
@Composable
private fun ManeuverIcon(maneuverId: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val cx = size.width / 2
        val cy = size.height / 2
        val arrowColor = GixxerTokens.accent
        val stroke = Stroke(width = 6f, cap = StrokeCap.Round)
        when (maneuverId) {
            2 -> {
                // left arrow
                drawLine(arrowColor, Offset(cx + 20, cy - 20), Offset(cx - 20, cy), strokeWidth = 6f)
                drawLine(arrowColor, Offset(cx - 20, cy), Offset(cx + 20, cy + 20), strokeWidth = 6f)
                drawLine(arrowColor, Offset(cx - 20, cy), Offset(cx + 30, cy), strokeWidth = 6f)
            }
            3 -> {
                // right arrow
                drawLine(arrowColor, Offset(cx - 20, cy - 20), Offset(cx + 20, cy), strokeWidth = 6f)
                drawLine(arrowColor, Offset(cx + 20, cy), Offset(cx - 20, cy + 20), strokeWidth = 6f)
                drawLine(arrowColor, Offset(cx + 20, cy), Offset(cx - 30, cy), strokeWidth = 6f)
            }
            23 -> {
                // U-turn — partial arc with arrowhead
                drawArc(
                    color = arrowColor,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - 20, cy - 25),
                    size = Size(40f, 50f),
                    style = stroke,
                )
                drawLine(arrowColor, Offset(cx - 20, cy + 20), Offset(cx - 30, cy + 10), strokeWidth = 6f)
                drawLine(arrowColor, Offset(cx - 20, cy + 20), Offset(cx - 10, cy + 30), strokeWidth = 6f)
            }
            71 -> {
                // roundabout — circle
                drawCircle(arrowColor, radius = 25f, center = Offset(cx, cy), style = stroke)
            }
            50 -> {
                // destination flag — pole + flag
                drawLine(arrowColor, Offset(cx - 20, cy - 25), Offset(cx - 20, cy + 25), strokeWidth = 5f)
                drawRect(arrowColor, topLeft = Offset(cx - 20, cy - 25), size = Size(35f, 25f))
            }
            else -> {
                // generic forward arrow (covers id=8 plus anything we don't recognise)
                drawLine(arrowColor, Offset(cx, cy + 25), Offset(cx, cy - 25), strokeWidth = 6f)
                drawLine(arrowColor, Offset(cx, cy - 25), Offset(cx - 15, cy - 10), strokeWidth = 6f)
                drawLine(arrowColor, Offset(cx, cy - 25), Offset(cx + 15, cy - 10), strokeWidth = 6f)
            }
        }
    }
}
