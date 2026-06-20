package dev.mrwick.redline.ui.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mrwick.redline.ui.theme.GixxerTokens

/**
 * The one empty-state shape used everywhere in the app. Replaces the
 * "Start the service…" placeholder vomit on Dashboard and other screens.
 *
 * Layout: 64 dp icon (textMuted) + body line + single outlined CTA.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    body: String,
    ctaLabel: String?,
    onCta: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(PaddingValues(horizontal = 24.dp, vertical = 32.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = GixxerTokens.textMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = GixxerTokens.textMuted,
        )
        if (ctaLabel != null && onCta != null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onCta) {
                Text(ctaLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
