package dev.mrwick.gixxerbridge.safety

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Full-screen "Are you OK?" prompt shown when [CrashDetector] suspects a crash.
 *
 * The activity is launched from [dev.mrwick.gixxerbridge.ble.BikeBridgeService.onCrashSuspected]
 * with FLAG_ACTIVITY_NEW_TASK. A 10-second countdown runs; if the user does not tap
 * "I'm OK" in time, the activity finishes and the service fires the SOS.
 *
 * Communication back to the service is via a static volatile flag [okPressed]: the service
 * starts this activity, waits 10s, then checks [okPressed]. ASSUMED: only one SOS flow can
 * be live at a time (enforced by [CrashDetector]'s cooldown). If two crashes overlap within
 * 60s, the flag could be observed stale — acceptable given the cooldown matches.
 */
class SosScreen : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dismiss the in-tray "Crash detected" prompt if still visible.
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(SosController.NOTIF_ID_CRASH_PROMPT)

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFB00020)),
                    contentAlignment = Alignment.Center,
                ) {
                    var secondsLeft by remember { mutableStateOf(COUNTDOWN_SECONDS) }
                    LaunchedEffect(Unit) {
                        while (secondsLeft > 0) {
                            delay(1_000)
                            secondsLeft -= 1
                        }
                        // Timer ran out — leave okPressed=false; service will fire SOS.
                        finish()
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                    ) {
                        Text(
                            "POSSIBLE CRASH",
                            color = Color.White,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "SOS will be sent in $secondsLeft s",
                            color = Color.White,
                            fontSize = 24.sp,
                        )
                        Spacer(modifier = Modifier.height(48.dp))
                        Button(
                            onClick = {
                                okPressed = true
                                finish()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFFB00020),
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(96.dp),
                        ) {
                            Text(
                                "I'M OK",
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** Seconds the rider has to tap "I'm OK" before SOS auto-fires. */
        const val COUNTDOWN_SECONDS: Int = 10

        /**
         * Set to true when the rider taps "I'm OK". Read by
         * [dev.mrwick.gixxerbridge.ble.BikeBridgeService] after a 10s delay to
         * decide whether to fire SOS. Reset to false each time the service arms
         * a new prompt.
         */
        @Volatile
        var okPressed: Boolean = false
    }
}
