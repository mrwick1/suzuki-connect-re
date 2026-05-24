package dev.mrwick.gixxerbridge.ui.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
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

// ASSUMED: MainActivity must extend FragmentActivity (or AppCompatActivity) for BiometricPrompt
// to attach. Current MainActivity extends ComponentActivity, which will cause promptBiometric()
// below to fail-open silently (the `as? FragmentActivity` cast returns null). Another agent
// owns MainActivity; flag this to the orchestrator so the base class can be changed.

/** Wraps content; if app lock is enabled and not yet unlocked, shows the lock UI instead. */
@Composable
fun AppLockGate(vm: AppLockViewModel, content: @Composable () -> Unit) {
    val enabled by vm.lockEnabled.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    if (!enabled || unlocked) {
        content()
    } else {
        AppLockScreen(onUnlock = { promptBiometric(ctx, onSuccess = { vm.markUnlocked() }) })
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
