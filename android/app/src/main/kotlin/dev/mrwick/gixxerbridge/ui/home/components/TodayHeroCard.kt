package dev.mrwick.gixxerbridge.ui.home.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * Single hero card for Home's today-zone. Replaces the multi-card stack
 * of RideSummary + BikeHealth + ServiceDue + ride streak. Three rows,
 * one card, no shadows. Each row: icon + label + tabular value.
 */
@Composable
fun TodayHeroCard(
    todayKm: Double?,
    streakDays: Int?,
    nextServiceLabel: String?,
    nextServiceDueIn: String?,
    nextServiceOverdue: Boolean,
    onNextServiceClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = GixxerTokens.surface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "TODAY",
                style = MaterialTheme.typography.labelMedium,
                color = GixxerTokens.textMuted,
            )
            Spacer(Modifier.height(16.dp))

            HeroRow(
                icon = Icons.Outlined.Speed,
                label = "Distance",
                value = todayKm?.let { "%.1f".format(it) + " km" } ?: "—",
                valueColor = GixxerTokens.textPrimary,
            )
            Spacer(Modifier.height(12.dp))

            HeroRow(
                icon = Icons.Outlined.LocalFireDepartment,
                label = "Ride streak",
                value = streakDays?.let { "$it ${if (it == 1) "day" else "days"}" } ?: "—",
                valueColor = GixxerTokens.textPrimary,
            )
            Spacer(Modifier.height(12.dp))

            HeroRow(
                icon = Icons.Outlined.Build,
                label = nextServiceLabel ?: "Next service",
                value = nextServiceDueIn ?: "Set a baseline",
                valueColor = if (nextServiceOverdue) GixxerTokens.danger else GixxerTokens.textPrimary,
                onClick = onNextServiceClick,
            )
        }
    }
}

@Composable
private fun HeroRow(
    icon: ImageVector,
    label: String,
    value: String,
    valueColor: Color,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) {
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)
    } else {
        Modifier.fillMaxWidth()
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = rowModifier) {
        Icon(icon, contentDescription = null, tint = GixxerTokens.textMuted, modifier = Modifier.width(20.dp).height(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = GixxerTokens.textMuted,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = GixxerMono.body.copy(color = valueColor),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
