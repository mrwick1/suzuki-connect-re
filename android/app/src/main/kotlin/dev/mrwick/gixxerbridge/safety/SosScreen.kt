package dev.mrwick.gixxerbridge.safety

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.ui.theme.GixxerMono
import dev.mrwick.gixxerbridge.ui.theme.GixxerTheme
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens
import dev.mrwick.gixxerbridge.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Full-screen SOS countdown prompt. Shown when [CrashDetector] suspects a crash.
 *
 * The activity is launched from [dev.mrwick.gixxerbridge.ble.BikeBridgeService.onCrashSuspected]
 * with FLAG_ACTIVITY_NEW_TASK. A [COUNTDOWN_SECONDS]-second countdown runs; if the user does not
 * tap "CANCEL SOS" or "I'M FINE" in time, the activity finishes and the service fires the SOS.
 *
 * Communication back to the service is via [okPressed]: the service starts this activity,
 * waits [COUNTDOWN_SECONDS] seconds, then checks [okPressed].
 *
 * Visual layers (Wave 5 redesign):
 *   1. Full-bleed danger-tinted background + radial countdown ring (Task A).
 *   2. [CrashPromptDialog] overlay: clearer AlertDialog with live countdown (Task B).
 *
 * Behavior: unchanged — no changes to countdown duration, SMS sending, or okPressed signaling.
 */
class SosScreen : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dismiss the in-tray "Crash detected" prompt if still visible.
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(SosController.NOTIF_ID_CRASH_PROMPT)

        // Read emergency contact for display (phone only; no name field exists yet).
        // runBlocking acceptable here — DataStore returns from disk cache in < 1 ms.
        val settings = Settings(this)
        val contactDisplay: String = runBlocking {
            settings.emergencyContactPhone.first()?.let { "+$it" } ?: "No contact set"
        }

        setContent {
            GixxerTheme {
                var secondsLeft by remember { mutableIntStateOf(COUNTDOWN_SECONDS) }

                LaunchedEffect(Unit) {
                    while (secondsLeft > 0) {
                        delay(1_000)
                        secondsLeft -= 1
                    }
                    // Timer ran out — okPressed stays false; service will fire SOS.
                    finish()
                }

                // Danger-tinted full-bleed background — communicates urgency without screaming.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(GixxerTokens.bg)
                        .background(GixxerTokens.danger.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    // ---- Background layer: radial ring + countdown readout (Task A) ----
                    // SpringSnap drives the ring — NOT infiniteRepeatable, NOT tween.
                    val ringFraction by animateFloatAsState(
                        targetValue = secondsLeft.toFloat() / COUNTDOWN_SECONDS.toFloat(),
                        animationSpec = Motion.SpringSnap,
                        label = "sosRingFraction",
                    )

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp),
                    ) {
                        // Above: emergency contact line.
                        Text(
                            text = "Emergency contact: $contactDisplay",
                            style = GixxerMono.body,
                            color = GixxerTokens.textMuted,
                            textAlign = TextAlign.Center,
                        )

                        Spacer(modifier = Modifier.height(48.dp))

                        // Hero: 120 dp CANCEL SOS button surrounded by shrinking outer ring.
                        val dangerColor = GixxerTokens.danger
                        val ringColor = GixxerTokens.danger.copy(alpha = 0.5f)

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(160.dp),
                        ) {
                            // Outer ring shrinks across countdown via SpringSnap.
                            Box(
                                modifier = Modifier
                                    .size(160.dp)
                                    .drawBehind {
                                        val strokeWidth = 6.dp.toPx()
                                        val inset = strokeWidth / 2f
                                        drawArc(
                                            color = ringColor,
                                            startAngle = -90f,
                                            sweepAngle = 360f * ringFraction,
                                            useCenter = false,
                                            style = Stroke(width = strokeWidth),
                                            topLeft = Offset(inset, inset),
                                            size = Size(
                                                size.width - strokeWidth,
                                                size.height - strokeWidth,
                                            ),
                                        )
                                    },
                            )

                            // Inner 120 dp cancel button.
                            FilledTonalButton(
                                onClick = {
                                    okPressed = true
                                    finish()
                                },
                                modifier = Modifier
                                    .size(120.dp)
                                    .clip(CircleShape),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = dangerColor,
                                    contentColor = GixxerTokens.textPrimary,
                                ),
                                shape = CircleShape,
                            ) {
                                Text(
                                    text = "CANCEL\nSOS",
                                    style = GixxerMono.body,
                                    color = GixxerTokens.textPrimary,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))

                        // Below: large countdown number + caption.
                        Text(
                            text = "$secondsLeft",
                            style = GixxerMono.headline,
                            color = GixxerTokens.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Sending in $secondsLeft seconds",
                            style = GixxerMono.body,
                            color = GixxerTokens.textMuted,
                            textAlign = TextAlign.Center,
                        )
                    }

                    // ---- Foreground overlay: CrashPromptDialog (Task B) ----
                    // The dialog sits above the radial background. "I'M FINE" maps to okPressed=true;
                    // "SEND NOW" maps to okPressed=false + immediate finish (service fires in < 1 s).
                    CrashPromptDialog(
                        secondsLeft = secondsLeft,
                        onImFine = {
                            okPressed = true
                            finish()
                        },
                        onSendNow = {
                            // okPressed stays false — service will fire SOS on its countdown expiry.
                            // Finishing the activity does NOT cancel the service's coroutine.
                            finish()
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** Seconds the rider has to tap "CANCEL SOS" before SOS auto-fires. */
        const val COUNTDOWN_SECONDS: Int = 10

        /**
         * Set to true when the rider taps "CANCEL SOS" or "I'M FINE". Read by
         * [dev.mrwick.gixxerbridge.ble.BikeBridgeService] after a [COUNTDOWN_SECONDS]-delay to
         * decide whether to fire SOS. Reset to false each time the service arms a new prompt.
         */
        @Volatile
        var okPressed: Boolean = false
    }
}
