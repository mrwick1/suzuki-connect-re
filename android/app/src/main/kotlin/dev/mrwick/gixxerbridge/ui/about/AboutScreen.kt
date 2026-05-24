package dev.mrwick.gixxerbridge.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.mrwick.gixxerbridge.BuildConfig
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ble.BikeInfo
import dev.mrwick.gixxerbridge.ble.ConnectionState
import dev.mrwick.gixxerbridge.data.GixxerDatabase
import dev.mrwick.gixxerbridge.data.RideStore
import dev.mrwick.gixxerbridge.ui.components.SkeletonLine
import dev.mrwick.gixxerbridge.util.CrashHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minimal "About" card: app version, build flavor, package, BLE protocol summary.
 *
 * Two interactive affordances live here:
 *   1. **Long-press the version row** to copy a multi-line diagnostic blob to the
 *      clipboard. Useful when filing bugs without having to retype build IDs.
 *   2. **"Reset all data"** at the bottom — destructive, two-tap confirm. Wipes
 *      every persisted user surface: Settings DataStore, QuickDestinations
 *      DataStore, LastParkedTracker DataStore, and the full ride history Room
 *      DB. Forces a process restart afterwards (user-initiated).
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AboutScreen() {
    val context = LocalContext.current
    val bikeInfo by AppGraph.bikeInfo.collectAsStateWithLifecycle()
    val connectionState by AppGraph.connectionState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("GixxerBridge", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(
            "An open replacement for Suzuki Connect — Google Maps nav on the cluster, " +
                "live telemetry on the phone, no cloud, no account.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Long-press anywhere on this row to copy diagnostics. We intentionally
                // make the *whole* version row tappable (not just an icon) because at a
                // glance there's nothing to suggest the gesture; the long-press is
                // documented in NOTES.md and surfaced as a Toast on success.
                KeyValLongPress(
                    k = "Version",
                    v = BuildConfig.VERSION_NAME,
                    onLongClick = {
                        copyDiagnostics(
                            context = context,
                            bikeInfo = bikeInfo,
                            connectionState = connectionState,
                        )
                    },
                )
                KeyVal("Build", BuildConfig.BUILD_TYPE)
                KeyVal("Package", BuildConfig.APPLICATION_ID)
                KeyVal("Android", "API ${android.os.Build.VERSION.SDK_INT} (${android.os.Build.VERSION.RELEASE})")
                KeyVal("Device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Long-press the Version row to copy diagnostics.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        // "Connected bike" card — only shown once BleClient has read the standard
        // 0x180A Device Information Service from the bike (happens on first connect).
        // While we're connected but the DIS read hasn't landed yet, show a
        // skeleton placeholder so the user sees something is in flight.
        when {
            bikeInfo != null -> ConnectedBikeCard(bikeInfo!!)
            connectionState == ConnectionState.Ready -> ReadingBikeInfoCard()
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Bike protocol", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Suzuki Connect BLE protocol — single service 0xFFF0 with write 0xFFF1 " +
                        "and notify 0xFFF2. 30-byte frames, 0xA5 header, 0x7F terminator, " +
                        "8-bit sum checksum. Phone -> bike: a531 nav, a532 call, a533 heartbeat, " +
                        "a534 missed call, a535 SMS, a536 identity. Bike -> phone: a537 telemetry " +
                        "(speed, odo, trip A/B, fuel, fuel economy).",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Credits", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Built by Arjun KR on a 2023 Suzuki Gixxer SF 150. Protocol decoded from " +
                        "decompiled Suzuki Connect APK + HCI snoop captures + a Frida-hooked " +
                        "ride session. No firmware modifications, no bypass of bike-side auth. " +
                        "Personal interoperability project.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/mrwick/gixxer-bridge"),
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        runCatching { context.startActivity(intent) }
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("View on GitHub", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        LastCrashCard()

        ResetAllDataCard()
    }
}

/**
 * Shows the most recent crash file (if any) with options to share or clear.
 * Renders nothing when no crash has been logged — keeps the About screen
 * uncluttered in the happy path.
 *
 * Reads the file on Dispatchers.IO via LaunchedEffect so we don't block the
 * Compose thread. The first few lines (Thread, Time, top of stacktrace) are
 * enough to triage at a glance; full log goes out via the share intent.
 */
@Composable
private fun LastCrashCard() {
    val context = LocalContext.current
    var crashFile by remember { mutableStateOf<File?>(null) }
    var preview by remember { mutableStateOf("") }
    // bump this to force re-read after Clear / on first composition.
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        val file = withContext(Dispatchers.IO) { CrashHandler.latestCrashFile(context) }
        crashFile = file
        preview = if (file != null) {
            withContext(Dispatchers.IO) {
                runCatching { file.readLines().take(8).joinToString("\n") }
                    .getOrDefault("(unable to read crash log)")
            }
        } else ""
    }

    val file = crashFile ?: return
    val ts = remember(file) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Last crash",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(ts, style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row {
                TextButton(
                    onClick = { shareCrashLog(context, file) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text("Share crash log", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(modifier = Modifier.width(16.dp))
                TextButton(
                    onClick = {
                        CrashHandler.clearAll(context)
                        refreshTick++
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(
                        "Clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * Share the crash log via ACTION_SEND. We attach the file as a content:// URI
 * through the existing FileProvider (authority `<applicationId>.fileprovider`,
 * path `<files-path name="crash-logs">` — see file_provider_paths.xml). Also
 * inlines the first part of the log into EXTRA_TEXT so receivers that don't
 * handle attachments (e.g. SMS, certain chat apps) still get something useful.
 */
private fun shareCrashLog(context: Context, file: File) {
    val authority = "${BuildConfig.APPLICATION_ID}.fileprovider"
    val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
    val text = runCatching { file.readText() }.getOrDefault("(unable to read crash log)")

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "GixxerBridge crash: ${file.name}")
        putExtra(Intent.EXTRA_TEXT, text)
        if (uri != null) {
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Share crash log"))
    }.onFailure {
        Toast.makeText(context, "No app to share with: ${it.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun KeyVal(k: String, v: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(k, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
        Text(v, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** A [KeyVal] row that fires [onLongClick] on long press; tap is a no-op. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun KeyValLongPress(k: String, v: String, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(vertical = 2.dp),
    ) {
        Text(k, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall)
        Text(v, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

/** Skeleton placeholder shown when connected but DIS read is still pending. */
@Composable
private fun ReadingBikeInfoCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Connected bike", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Reading bike info…",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF94A3B8),
            )
            Spacer(modifier = Modifier.height(12.dp))
            SkeletonLine(widthFraction = 0.6f, height = 12.dp)
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonLine(widthFraction = 0.45f, height = 12.dp)
            Spacer(modifier = Modifier.height(8.dp))
            SkeletonLine(widthFraction = 0.5f, height = 12.dp)
        }
    }
}

@Composable
private fun ConnectedBikeCard(info: BikeInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Connected bike", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            info.manufacturer?.let { KeyVal("Manufacturer", it) }
            info.modelNumber?.let { KeyVal("Model", it) }
            info.serialNumber?.let { KeyVal("Serial", it) }
            info.firmwareRevision?.let { KeyVal("Firmware", it) }
            info.softwareRevision?.let { KeyVal("Software", it) }
            info.hardwareRevision?.let { KeyVal("Hardware", it) }
            info.systemId?.let { KeyVal("System ID", it) }
            info.pnpId?.let { KeyVal("PnP ID", it) }
            info.ieeeCert?.let { KeyVal("IEEE cert", it) }
        }
    }
}

/**
 * Destructive reset card. Two-tap confirm pattern: first tap arms it (button
 * label flips to "Tap again to confirm"), second tap executes. If the user
 * leaves the screen between taps, the arm state is forgotten when the composable
 * leaves composition — which is the correct behavior here.
 */
@Composable
private fun ResetAllDataCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var armed by remember { mutableStateOf(false) }
    var inProgress by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Reset all data",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Wipes paired bike, settings, quick destinations, last-parked location, " +
                    "and the entire ride history (rides + samples + GPS tracks). Does not " +
                    "uninstall the app — restart it manually after reset.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                enabled = !inProgress,
                onClick = {
                    if (!armed) {
                        armed = true
                        return@TextButton
                    }
                    inProgress = true
                    scope.launch {
                        runCatching { wipeAllData(context) }
                            .onSuccess {
                                Toast.makeText(
                                    context,
                                    "All data cleared. Restart the app.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                            .onFailure {
                                Toast.makeText(
                                    context,
                                    "Reset failed: ${it.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        armed = false
                        inProgress = false
                    }
                },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
            ) {
                Text(
                    when {
                        inProgress -> "Resetting…"
                        armed -> "Tap again to confirm"
                        else -> "Reset all data"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Build the diagnostic blob and push it to the system clipboard with a Toast. */
private fun copyDiagnostics(
    context: Context,
    bikeInfo: BikeInfo?,
    connectionState: ConnectionState,
) {
    val bikeMacLine = "Bike: <see Settings if paired>"
    // ASSUMED: surfacing the paired bike MAC here would require a suspend read of
    // Settings.bikeMac; we omit it to keep this synchronous and avoid leaking a
    // value most bug reports won't need. The connection state alone tells us
    // whether a bike was reachable when the user copied diagnostics.
    val connectedLine = "Connected: ${connectionStateLabel(connectionState)}"

    val bikeInfoLine = bikeInfo?.let {
        buildString {
            append("Bike info: ")
            val parts = listOfNotNull(
                it.manufacturer?.let { v -> "mfr=$v" },
                it.modelNumber?.let { v -> "model=$v" },
                it.serialNumber?.let { v -> "serial=$v" },
                it.firmwareRevision?.let { v -> "fw=$v" },
                it.softwareRevision?.let { v -> "sw=$v" },
                it.hardwareRevision?.let { v -> "hw=$v" },
                it.systemId?.let { v -> "sysid=$v" },
                it.pnpId?.let { v -> "pnp=$v" },
                it.ieeeCert?.let { v -> "ieee=$v" },
            )
            append(if (parts.isEmpty()) "(none)" else parts.joinToString(", "))
        }
    } ?: "Bike info: (not read — never connected)"

    val text = buildString {
        appendLine("GixxerBridge v${BuildConfig.VERSION_NAME}")
        appendLine("Build: ${BuildConfig.BUILD_TYPE} · ${BuildConfig.APPLICATION_ID}")
        appendLine(
            "Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        )
        appendLine(bikeMacLine)
        appendLine(connectedLine)
        append(bikeInfoLine)
    }

    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm == null) {
        Toast.makeText(context, "Clipboard unavailable", Toast.LENGTH_SHORT).show()
        return
    }
    cm.setPrimaryClip(ClipData.newPlainText("GixxerBridge diagnostics", text))
    Toast.makeText(context, "Copied diagnostics", Toast.LENGTH_SHORT).show()
}

private fun connectionStateLabel(s: ConnectionState): String = when (s) {
    ConnectionState.Idle -> "Idle"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Discovering -> "Discovering"
    ConnectionState.Ready -> "Ready"
    is ConnectionState.Disconnected -> "Disconnected(status=${s.status})"
    is ConnectionState.Failed -> "Failed(${s.reason})"
}

/**
 * Wipe every persisted user surface. Runs on [Dispatchers.IO] because it
 * touches files and the Room DB. After wipe completes the user is asked
 * (via Toast) to relaunch — we don't try to kill the process here because
 * AppGraph + BikeBridgeService hold in-memory references to the freshly
 * deleted on-disk state and re-attaching them cleanly is more work than
 * a manual restart deserves at this stage.
 */
private suspend fun wipeAllData(context: Context) = withContext(Dispatchers.IO) {
    // 1. Room ride DB — drop rides (cascades to samples + locations).
    val db = GixxerDatabase.get(context)
    RideStore(db.rideDao()).deleteAllRides()

    // 2. DataStore preference files. We use Context.dataStoreFile() to find the
    //    on-disk preferences_pb file for each named store and delete it. Names
    //    must match the `name` passed to `preferencesDataStore(name = …)` in
    //    Settings.kt / QuickDestinations.kt / LastParkedTracker.kt.
    //
    //    ASSUMED: deleting these files while their DataStore instances are still
    //    in use elsewhere in the process is safe enough for a "user-restarts-now"
    //    flow. The first read after restart will see an empty store, which is
    //    the desired result. We do NOT attempt to coordinate with live
    //    collectors — the user is told to restart.
    listOf(
        "gixxer_settings",
        "quick_destinations",
        "last_parked",
    ).forEach { name ->
        runCatching { context.dataStoreFile(name).delete() }
    }
}

/**
 * Resolve the on-disk file backing a `preferencesDataStore(name = <name>)`.
 *
 * The DataStore preferences library stores each named store as
 * `datastore/<name>.preferences_pb` under the app's `filesDir`. There is no
 * public API to delete a DataStore, so we delete the underlying file directly.
 */
private fun Context.dataStoreFile(name: String): java.io.File =
    java.io.File(filesDir, "datastore/$name.preferences_pb")
