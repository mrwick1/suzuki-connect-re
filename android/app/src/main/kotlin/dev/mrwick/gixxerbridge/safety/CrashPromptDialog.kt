package dev.mrwick.gixxerbridge.safety

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * Material 3 AlertDialog shown when [CrashDetector] suspects a crash.
 *
 * Displays a live countdown from [secondsLeft] and offers two actions:
 * - "I'M FINE" (primary, outlined): cancel the SOS — calls [onImFine].
 * - "SEND NOW" (destructive, filled danger): skip the countdown and send immediately —
 *   calls [onSendNow].
 *
 * This composable is intentionally pure — no timers, no side-effects.
 * The countdown is driven externally by [SosScreen] so the service-owned
 * [SosScreen.okPressed] flag is the single authority on whether SOS fires.
 *
 * Visual spec: Wave 5 — clearer AlertDialog with live countdown body text.
 * Safety code NOT changed: no detection thresholds, no SMS logic touched here.
 */
@Composable
fun CrashPromptDialog(
    secondsLeft: Int,
    onImFine: () -> Unit,
    onSendNow: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable — rider must act */ },
        title = {
            Text(
                text = "Possible crash detected",
                style = MaterialTheme.typography.titleLarge,
                color = GixxerTokens.textPrimary,
            )
        },
        text = {
            Text(
                text = "We'll send your emergency contact a message in $secondsLeft seconds. " +
                    "Tap I'M FINE to cancel.",
                style = MaterialTheme.typography.bodyMedium,
                color = GixxerTokens.textMuted,
            )
        },
        confirmButton = {
            // "I'M FINE" — primary, outlined style to stand out without aggression.
            OutlinedButton(onClick = onImFine) {
                Text(
                    text = "I'M FINE",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            // "SEND NOW" — destructive, filled danger so the rider knows this fires immediately.
            FilledTonalButton(
                onClick = onSendNow,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = GixxerTokens.danger,
                    contentColor = GixxerTokens.textPrimary,
                ),
            ) {
                Text(
                    text = "SEND NOW",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        containerColor = GixxerTokens.surfaceElevated,
        titleContentColor = GixxerTokens.textPrimary,
        textContentColor = GixxerTokens.textMuted,
    )
}
