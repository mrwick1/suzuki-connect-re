package dev.mrwick.redline.ui.cluster

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Umbrella
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import dev.mrwick.redline.protocol.NavFrame
import dev.mrwick.redline.ui.theme.GixxerTokens

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

/** What the current NavFrame represents — distinct visual modes. The bike's
 *  a531 frame fields are reused for non-nav data (idle clock + now-playing)
 *  by the producers in `nav/`; here we pick the right preview layout based
 *  on the unit-suffix bytes. */
private enum class ClusterMode { Nav, IdleClock, NowPlaying }

private fun NavFrame.mode(): ClusterMode = when {
    distTotalUnit == "C" && distNextUnit.trim().isEmpty() -> ClusterMode.IdleClock
    distNextUnit == "@" && distTotalUnit == "*" -> ClusterMode.NowPlaying
    else -> ClusterMode.Nav
}

@Composable
private fun ClusterBody(nav: NavFrame) {
    when (nav.mode()) {
        ClusterMode.Nav -> NavBody(nav)
        ClusterMode.IdleClock -> IdleClockBody(nav)
        ClusterMode.NowPlaying -> NowPlayingBody(nav)
    }
}

@Composable
private fun NavBody(nav: NavFrame) {
    // Real Maps nav frame — arrow + distance to next maneuver + ETA + total.
    Row(verticalAlignment = Alignment.CenterVertically) {
        ManeuverIcon(nav.maneuverId, modifier = Modifier.size(72.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = humanizeDistance(nav.distNext, nav.distNextUnit),
                style = MaterialTheme.typography.headlineMedium,
                color = GixxerTokens.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "ETA ${formatEta(nav.eta)}",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${humanizeDistance(nav.distTotal, nav.distTotalUnit)} to go",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
            )
        }
    }
}

@Composable
private fun IdleClockBody(nav: NavFrame) {
    // No nav active — bike shows clock + ambient temp + weather icon.
    val time = formatEta(nav.eta)
    val temp = nav.distTotal.toIntOrNull()
    val weatherCode = nav.distNext.toIntOrNull() ?: 0
    val (weatherLabel, weatherIcon) = weatherDescriptor(weatherCode)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = weatherIcon,
            contentDescription = weatherLabel,
            tint = GixxerTokens.textPrimary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = time,
                style = MaterialTheme.typography.headlineMedium,
                color = GixxerTokens.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (temp != null) "$temp °C · $weatherLabel" else weatherLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Idle clock — bike cluster",
                style = MaterialTheme.typography.bodySmall,
                color = GixxerTokens.textMuted,
            )
        }
    }
}

@Composable
private fun NowPlayingBody(nav: NavFrame) {
    // Music ticker — distNext + distTotal are the first 8 chars of trackTitle.
    val title = (nav.distNext + nav.distTotal).trim()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.MusicNote,
            contentDescription = "Now playing",
            tint = GixxerTokens.textPrimary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title.ifEmpty { "—" },
                style = MaterialTheme.typography.headlineMedium,
                color = GixxerTokens.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Now playing",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
            )
        }
    }
}

/** "0001" + "k" → "1 km". Trims leading zeros + maps the one-letter unit byte
 *  to a readable suffix. Returns "—" if [valueStr] is empty/garbage. */
private fun humanizeDistance(valueStr: String, unitStr: String): String {
    val v = valueStr.trimStart('0').ifEmpty { "0" }
    val u = when (unitStr.trim()) {
        "M", "m" -> "m"
        "k", "K" -> "km"
        "" -> ""
        else -> unitStr.trim()
    }
    return if (u.isEmpty()) v else "$v $u"
}

/** "1226PM" → "12:26 PM". Idempotent for already-formatted strings. */
private fun formatEta(eta: String): String {
    if (eta.contains(':')) return eta
    if (eta.length < 4) return eta
    val tail = eta.takeLast(2)
    val isAmPm = tail.equals("AM", ignoreCase = true) || tail.equals("PM", ignoreCase = true)
    val digits = if (isAmPm) eta.dropLast(2) else eta
    val ap = if (isAmPm) " ${tail.uppercase()}" else ""
    if (digits.length < 3) return eta
    val hh = digits.dropLast(2)
    val mm = digits.takeLast(2)
    return "$hh:$mm$ap"
}

/** Suzuki weather code (see WeatherCodeMap.kt) → (label, Material Symbol). */
private fun weatherDescriptor(code: Int): Pair<String, androidx.compose.ui.graphics.vector.ImageVector> = when (code) {
    1 -> "Sunny" to Icons.Outlined.WbSunny
    2 -> "Cloudy" to Icons.Outlined.Cloud
    3 -> "Fog" to Icons.Outlined.Cloud
    4 -> "Light rain" to Icons.Outlined.Umbrella
    5 -> "Thunderstorm" to Icons.Outlined.Bolt
    6 -> "Rain" to Icons.Outlined.Umbrella
    7 -> "Snow" to Icons.Outlined.AcUnit
    8 -> "Sleet" to Icons.Outlined.AcUnit
    9 -> "Hot" to Icons.Outlined.WbSunny
    10 -> "Cold" to Icons.Outlined.AcUnit
    11 -> "Windy" to Icons.Outlined.Air
    else -> "Clear" to Icons.Outlined.WbSunny
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
    // Bespoke REDLINE moto glyphs (see GixxerIcons). Mappls maneuver ids:
    // 2=left, 3=right, 23=u-turn; everything else → straight/forward.
    val glyph = when (maneuverId) {
        2 -> dev.mrwick.redline.ui.components.GixxerIcons.ManeuverLeft
        3 -> dev.mrwick.redline.ui.components.GixxerIcons.ManeuverRight
        23 -> dev.mrwick.redline.ui.components.GixxerIcons.ManeuverUTurn
        else -> dev.mrwick.redline.ui.components.GixxerIcons.ManeuverStraight
    }
    Icon(
        imageVector = glyph,
        contentDescription = null,
        tint = dev.mrwick.redline.ui.theme.GixxerBrand.accent,
        modifier = modifier,
    )
}
