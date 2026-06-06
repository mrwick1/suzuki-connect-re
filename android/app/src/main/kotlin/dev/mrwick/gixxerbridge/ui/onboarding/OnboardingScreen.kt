package dev.mrwick.gixxerbridge.ui.onboarding

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TwoWheeler
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.ble.BikeBridgeService
import dev.mrwick.gixxerbridge.notifications.NotificationCaptureService
import dev.mrwick.gixxerbridge.ui.theme.GixxerTokens

/**
 * First-run wizard. Four steps:
 *   0 — welcome
 *   1 — runtime permissions (BLE, location, post-notifications, notification listener)
 *   2 — pair bike (BLE scan + tap to save)
 *   3 — start (launches the foreground service + dismisses)
 *
 * The wizard is rendered as a full-screen overlay by MainActivity; once
 * [OnboardingViewModel.complete] flips the settings flag, the gate drops it
 * and the app shell becomes visible.
 */
@Composable
fun OnboardingScreen(vm: OnboardingViewModel) {
    val step by vm.step.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { StepIndicator(step = step, total = OnboardingViewModel.MAX_STEP + 1) },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (step) {
                    0 -> WelcomeStep(onContinue = vm::next)
                    1 -> PermissionsStep(onContinue = vm::next, onBack = vm::back)
                    2 -> PairStep(vm = vm, onContinue = vm::next, onBack = vm::back)
                    3 -> StartStep(vm = vm, onBack = vm::back)
                    else -> WelcomeStep(onContinue = vm::next)
                }
            }
        }
    }
}

// ---------- Step 0: Welcome ----------

@Composable
private fun WelcomeStep(onContinue: () -> Unit) {
    StepScaffold(
        primaryAction = { Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") } },
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            Icons.Default.TwoWheeler,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "REDLINE",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "GOOGLE MAPS ON YOUR BIKE'S CLUSTER",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Local-only, no account.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Text(
            "Takes about a minute: grant permissions, pair your bike, you're done.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// ---------- Step 1: Permissions ----------

private data class PermSpec(val key: String, val label: String, val rationale: String)

@Composable
private fun PermissionsStep(onContinue: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // ASSUMED: this list mirrors the runtime perms MainActivity already requests; if more
    // are added later (e.g. ACTIVITY_RECOGNITION for fitness sensors), extend here.
    val runtimePerms: List<Pair<String, PermSpec>> = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT to PermSpec(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    "Bluetooth",
                    "Talk to your bike over BLE.",
                ))
                add(Manifest.permission.BLUETOOTH_SCAN to PermSpec(
                    Manifest.permission.BLUETOOTH_SCAN,
                    "Bluetooth scan",
                    "Find your bike when its key is ON.",
                ))
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION to PermSpec(
                Manifest.permission.ACCESS_FINE_LOCATION,
                "Location",
                "Record GPS tracks for your rides.",
            ))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS to PermSpec(
                    Manifest.permission.POST_NOTIFICATIONS,
                    "Notifications",
                    "Show the persistent service notification.",
                ))
            }
        }
    }

    // Force-recompose on resume so granted state refreshes after the user returns from
    // the system settings page (used by notification-listener entry which is not a
    // runtime perm).
    var tick by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val runtimeStatus: List<Pair<PermSpec, Boolean>> = remember(tick) {
        runtimePerms.map { (perm, spec) -> spec to isGranted(ctx, perm) }
    }
    val listenerGranted = remember(tick) { isListenerGranted(ctx) }

    val singleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    val multiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { tick++ }

    val allRuntimeGranted = runtimeStatus.all { it.second }
    val allGranted = allRuntimeGranted && listenerGranted

    StepScaffold(
        primaryAction = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (allGranted) "Continue" else "Skip for now")
                }
            }
        },
    ) {
        Text(
            "Permissions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "REDLINE needs these to work. Tap each, or grant all at once.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (!allRuntimeGranted) {
            Button(
                onClick = {
                    multiLauncher.launch(runtimePerms.map { it.first }.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Grant all") }
            Spacer(Modifier.height(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(runtimeStatus) { (spec, granted) ->
                PermissionRow(
                    label = spec.label,
                    rationale = spec.rationale,
                    granted = granted,
                    onGrant = { singleLauncher.launch(spec.key) },
                )
            }
            item {
                PermissionRow(
                    label = "Notification access",
                    rationale = "Read Google Maps directions, calls, SMS so we can mirror them to the cluster.",
                    granted = listenerGranted,
                    onGrant = { openListenerSettings(ctx) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, rationale: String, granted: Boolean, onGrant: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (granted) {
                GrantedPill()
            } else {
                Button(onClick = onGrant) { Text("Grant") }
            }
        }
    }
}

@Composable
private fun GrantedPill() {
    Surface(
        color = GixxerTokens.surface,
        contentColor = GixxerTokens.success,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text("Granted", style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ---------- Step 2: Pair bike ----------

@Composable
private fun PairStep(vm: OnboardingViewModel, onContinue: () -> Unit, onBack: () -> Unit) {
    val results by vm.scanResults.collectAsStateWithLifecycle()
    val bikeMac by vm.bikeMac.collectAsStateWithLifecycle()
    val bikes = results.values.sortedByDescending { it.rssi }

    DisposableEffect(Unit) {
        vm.startScan()
        onDispose { vm.stopScan() }
    }

    StepScaffold(
        primaryAction = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (bikeMac != null) "Continue" else "Skip — I'll pair later")
                }
            }
        },
    ) {
        Text(
            "Pair your bike",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Turn the bike's key ON, then tap it below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        if (bikeMac != null && bikes.none { it.mac == bikeMac }) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GixxerTokens.success)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Already paired", style = MaterialTheme.typography.titleMedium)
                        Text(
                            bikeMac ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (bikes.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Scanning… make sure the bike's key is ON.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(bikes, key = { it.mac }) { bike ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.pickBike(bike) },
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.TwoWheeler, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bike.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${bike.mac}  · ${if (bike.rssi == Int.MIN_VALUE) "saved" else "RSSI ${bike.rssi}"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { vm.pickBike(bike) }) { Text("Pair") }
                    }
                }
            }
        }
    }
}

// ---------- Step 3: Start ----------

@Composable
private fun StartStep(vm: OnboardingViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current

    StepScaffold(
        primaryAction = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                Button(
                    onClick = {
                        startBikeService(ctx)
                        vm.complete()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start REDLINE")
                }
            }
        },
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(
            Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = GixxerTokens.success,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "All set!",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Tap Start to begin. REDLINE will keep a tiny notification in your tray while it's running — that's the foreground service holding the BLE link.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

// ---------- Shared layout ----------

@Composable
private fun StepScaffold(
    primaryAction: @Composable () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        Spacer(Modifier.height(16.dp))
        primaryAction()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepIndicator(step: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            "Step ${step + 1} of $total",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(12.dp))
        repeat(total) { i ->
            val active = i <= step
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        if (active) GixxerTokens.accent
                        else GixxerTokens.surfaceElevated,
                        CircleShape,
                    ),
            )
            if (i < total - 1) Spacer(Modifier.width(6.dp))
        }
    }
}

// ---------- Helpers ----------

private fun isGranted(ctx: Context, perm: String): Boolean =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun isListenerGranted(ctx: Context): Boolean {
    val enabled = AndroidSettings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        ?: return false
    val component = ComponentName(ctx, NotificationCaptureService::class.java).flattenToString()
    return enabled.split(':').any { it == component }
}

private fun openListenerSettings(ctx: Context) {
    val intent = Intent(AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(intent)
}

private fun startBikeService(ctx: Context) {
    val intent = Intent(ctx, BikeBridgeService::class.java)
    ContextCompat.startForegroundService(ctx, intent)
}
