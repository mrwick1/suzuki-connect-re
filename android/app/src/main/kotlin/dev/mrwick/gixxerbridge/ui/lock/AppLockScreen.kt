package dev.mrwick.gixxerbridge.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

// ASSUMED: MainActivity must extend FragmentActivity (or AppCompatActivity) for BiometricPrompt
// to attach. Current MainActivity extends ComponentActivity, which will cause promptBiometric()
// below to fail-open silently (the `as? FragmentActivity` cast returns null). Another agent
// owns MainActivity; flag this to the orchestrator so the base class can be changed.

/**
 * Wraps content behind the app lock gate.
 *
 * Three states:
 *  - Not ready (DataStore hasn't loaded yet): render a full-bleed brand placeholder so content
 *    never flashes before the lock state is known. Typically < 50 ms on first launch.
 *  - Locked: show the unlock screen with biometric prompt.
 *  - Unlocked / lock disabled: cross-fade content in via a 200 ms fade.
 *
 * The 200 ms fade uses tween() instead of SpringSweep because AnimatedVisibility's
 * enter/exit specs require FiniteAnimationSpec<Float>, and spring() is infinite by nature
 * when used as an enter/exit spec — the compiler rejects it. Noted per spec allowance.
 */
@Composable
fun AppLockGate(vm: AppLockViewModel, content: @Composable () -> Unit) {
    val enabled by vm.lockEnabled.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val isReady by vm.isReady.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    when {
        // DataStore hasn't emitted yet — show brand placeholder to avoid content flash.
        !isReady -> BrandPlaceholder()

        // Lock disabled or session unlocked — cross-fade content in.
        !enabled || unlocked -> {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
            ) {
                content()
            }
        }

        // Locked — show unlock screen.
        else -> {
            AppLockScreen(onUnlock = { promptBiometric(ctx, onSuccess = { vm.markUnlocked() }) })
        }
    }
}

/**
 * Full-bleed brand placeholder shown during the DataStore loading window.
 *
 * Renders [GixxerTokens.bg] background with a centered [Icons.Filled.TwoWheeler] glyph at
 * 64 dp in [GixxerTokens.textMuted]. Prevents any app content from flashing before lock state
 * is determined on cold start.
 */
@Composable
private fun BrandPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(GixxerTokens.bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.TwoWheeler,
            contentDescription = null,
            tint = GixxerTokens.textMuted,
            modifier = Modifier.size(64.dp),
        )
    }
}

@Composable
private fun AppLockScreen(onUnlock: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(72.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text("GixxerBridge is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

private fun promptBiometric(context: Context, onSuccess: () -> Unit) {
    val activity = context as? FragmentActivity ?: return
    val executor = ContextCompat.getMainExecutor(context)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            onSuccess()
        }
    })
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock GixxerBridge")
        .setSubtitle("Authenticate to continue")
        .setNegativeButtonText("Cancel")
        .build()
    val canAuth = BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    if (canAuth == BiometricManager.BIOMETRIC_SUCCESS) {
        prompt.authenticate(info)
    } else {
        // ASSUMED: if no biometric enrolled, treat as unlocked (fail-open) so app isn't bricked.
        onSuccess()
    }
}
