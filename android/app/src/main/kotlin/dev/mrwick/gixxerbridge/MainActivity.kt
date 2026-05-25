package dev.mrwick.gixxerbridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.appcompat.app.AppCompatActivity
import dev.mrwick.gixxerbridge.data.Settings
import dev.mrwick.gixxerbridge.ui.theme.GixxerTheme
import dev.mrwick.gixxerbridge.ui.theme.accentColorFor
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.mrwick.gixxerbridge.ui.compose.FrameComposerScreen
import dev.mrwick.gixxerbridge.ui.compose.FrameComposerViewModel
import dev.mrwick.gixxerbridge.ui.dashboard.DashboardScreen
import dev.mrwick.gixxerbridge.ui.dashboard.DashboardViewModel
import dev.mrwick.gixxerbridge.ui.home.HomeScreen
import dev.mrwick.gixxerbridge.ui.diagnostics.DiagnosticsScreen
import dev.mrwick.gixxerbridge.ui.inspector.InspectorScreen
import dev.mrwick.gixxerbridge.ui.inspector.InspectorViewModel
import dev.mrwick.gixxerbridge.ui.KeepScreenOnEffect
import dev.mrwick.gixxerbridge.ui.lock.AppLockGate
import dev.mrwick.gixxerbridge.ui.lock.AppLockViewModel
import dev.mrwick.gixxerbridge.ui.maintenance.ServiceHistoryScreen
import dev.mrwick.gixxerbridge.ui.maintenance.ServiceHistoryViewModel
import dev.mrwick.gixxerbridge.ui.mileage.MileageScreen
import dev.mrwick.gixxerbridge.ui.mileage.MileageViewModel
import dev.mrwick.gixxerbridge.ui.onboarding.OnboardingScreen
import dev.mrwick.gixxerbridge.ui.onboarding.OnboardingViewModel
import androidx.compose.ui.Alignment
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.flow.first
import dev.mrwick.gixxerbridge.ui.settings.AllowlistScreen
import dev.mrwick.gixxerbridge.ui.settings.AllowlistViewModel
import dev.mrwick.gixxerbridge.ui.settings.PairingScreen
import dev.mrwick.gixxerbridge.ui.settings.PairingViewModel
import dev.mrwick.gixxerbridge.ui.settings.SettingsScreen
import dev.mrwick.gixxerbridge.ui.settings.SettingsViewModel
import dev.mrwick.gixxerbridge.ui.stats.StatsScreen
import dev.mrwick.gixxerbridge.ui.stats.StatsViewModel
import dev.mrwick.gixxerbridge.ui.trips.PostRideSummaryHost
import dev.mrwick.gixxerbridge.ui.trips.TripDetailScreen
import dev.mrwick.gixxerbridge.ui.trips.TripsScreen
import dev.mrwick.gixxerbridge.ui.trips.TripsViewModel
import dev.mrwick.gixxerbridge.app.AppEvent
import dev.mrwick.gixxerbridge.app.AppEvents
import dev.mrwick.gixxerbridge.app.AppGraph
import dev.mrwick.gixxerbridge.ui.active.ActiveRideLayer
import kotlinx.coroutines.launch

/**
 * Entry activity. FragmentActivity for biometric prompt compatibility (see ui/lock).
 * Hosts a 5-tab bottom-nav (Home / Dashboard / Stats / Trips / Settings) + sub-routes
 * for pairing, allowlist, trip detail, composer, inspector.
 */
class MainActivity : AppCompatActivity() {

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore result */ }
    private val blePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* ignore result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        // PERF / correctness: declare edge-to-edge BEFORE super.onCreate so the
        // system handles status + navigation bar layout instead of the app
        // fighting it on every frame. Without this, Android 15+ logs warnings
        // and applies a default backwards-compat overlay that triggers extra
        // window inset recomputations on background/foreground transitions.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        // Auto-start the bike BLE service on every app launch. Cheap (idempotent
        // — service uses START_STICKY + the same singleton state when already
        // running) and removes the "where is the start button?" UX question we
        // used to handle with a button on Home.
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                this,
                android.content.Intent(this, dev.mrwick.gixxerbridge.ble.BikeBridgeService::class.java),
            )
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "auto-start BikeBridgeService threw", t)
        }
        setContent {
            // Re-render with the user's chosen accent. Falls back to cyan until
            // the first DataStore read completes (initial value below).
            val appCtx = applicationContext
            val settingsForTheme = remember { Settings(appCtx) }
            val accentName by settingsForTheme.themeAccent.collectAsState(initial = Settings.DEFAULT_ACCENT)
            GixxerTheme(accent = accentColorFor(accentName)) {
                OnboardingGate {
                    val lockVm: AppLockViewModel = viewModel(factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application))
                    AppLockGate(lockVm) {
                        AppShell()
                        // Drives FLAG_KEEP_SCREEN_ON on this activity's window based on
                        // the user's pref + live connection state. Cleared on dispose.
                        KeepScreenOnEffect()
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // ACCESS_FINE_LOCATION is needed at runtime on ALL API levels here, not
        // just for legacy BLE — RideLocationTracker uses FusedLocationProviderClient
        // to record GPS tracks for ride GPX export. The runtime prompt is the
        // only signal the user gives; without it RideLocationTracker.start() no-ops.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blePermLauncher.launch(arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ))
        } else {
            blePermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }
}

/**
 * Full-screen overlay that hides [content] until the first-run wizard is
 * complete. Mirrors the [AppLockGate] pattern.
 *
 * - While loading the flag (one DataStore read), shows a blank+spinner so we
 *   don't flash the app shell.
 * - When the flag is false, shows [OnboardingScreen]; completion flips the
 *   flag and this gate dismisses on the next recomposition.
 * - When true, just renders [content].
 */
@Composable
private fun OnboardingGate(content: @Composable () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // PERF: process-wide Settings singleton via AppGraph (audit finding 1.7).
    val settings = remember(ctx) { AppGraph.settings(ctx) }
    // null = still loading; we want to avoid showing the app shell briefly
    // before the gate kicks in for a never-onboarded user.
    val onboardingDone by produceState<Boolean?>(initialValue = null) {
        value = try {
            settings.onboardingComplete.first()
        } catch (_: Throwable) {
            // ASSUMED: if DataStore read fails for any reason, fall open so the
            // user isn't stuck on a spinner forever. Onboarding can be replayed
            // from settings later if we add a "reset onboarding" toggle.
            true
        }
        // After the initial read we don't need to keep observing — the wizard
        // setter triggers recomposition through the OnboardingViewModel path,
        // and the gate flips below via a second collector.
        settings.onboardingComplete.collect { value = it }
    }
    when (onboardingDone) {
        null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        false -> {
            val vm: OnboardingViewModel = viewModel()
            OnboardingScreen(vm)
        }
        true -> content()
    }
}

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Tab("home", "Home", Icons.Default.Home)
    data object Dashboard : Tab("dashboard", "Dashboard", Icons.Default.Speed)
    data object Stats : Tab("stats", "Stats", Icons.Default.BarChart)
    data object Trips : Tab("trips", "Trips", Icons.Default.Route)
    data object Settings : Tab("settings", "Settings", Icons.Default.Settings)
}

private val tabs = listOf(Tab.Home, Tab.Dashboard, Tab.Stats, Tab.Trips, Tab.Settings)

/** Adapter from the internal `Tab` sealed class to the public GixxerNavTab DTO
 *  that the custom bottom-nav composable consumes. */
private val navTabs: List<dev.mrwick.gixxerbridge.ui.nav.GixxerNavTab> = tabs.map { t ->
    dev.mrwick.gixxerbridge.ui.nav.GixxerNavTab(route = t.route, label = t.label, icon = t.icon)
}

@Composable
private fun AppShell() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Tab.Home.route
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? android.app.Activity

    // Post-ride summary: shows a Spotify-Wrapped-style dialog when a ride ends.
    val lastFinishedRideId by AppGraph.lastFinishedRideId.collectAsState()
    lastFinishedRideId?.let { rideId ->
        PostRideSummaryHost(
            rideId = rideId,
            onDismiss = { AppGraph.clearLastFinishedRide() },
        )
    }

    // Active-ride metric: read from Settings so ActiveRideLayer knows which
    // metric to display in the overlay lower third.
    val activeRideMetric by AppGraph.settings(context).activeRideMetric.collectAsState(
        initial = dev.mrwick.gixxerbridge.data.Settings.DEFAULT_ACTIVE_RIDE_METRIC,
    )

    // Process-wide one-shot event bus -> snackbar host. Lives at the shell so
    // any active screen (Dashboard, Settings, Trips, etc.) surfaces transient
    // signals fired from the foreground service (e.g. demo-mode auto-disable).
    // SnackbarHostState is remembered across recompositions so a snackbar that
    // started showing isn't lost when the user navigates between tabs.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val settingsForEvents = remember(context) { AppGraph.settings(context) }
    LaunchedEffect(Unit) {
        AppEvents.events.collect { event ->
            when (event) {
                is AppEvent.DemoModeAutoDisabled -> {
                    // Drop any currently-shown snackbar so back-to-back
                    // auto-disables (e.g. user re-enabled, next a537 arrived)
                    // don't queue up behind a stale one.
                    snackbarHostState.currentSnackbarData?.dismiss()
                    val result = snackbarHostState.showSnackbar(
                        message = "Real bike telemetry detected — Demo mode turned off.",
                        actionLabel = "Undo",
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        // User explicitly chose to re-enable. The next a537 will
                        // disable it again — that's by design (see BikeBridgeService).
                        scope.launch { settingsForEvents.setDemoMode(true) }
                    }
                }
            }
        }
    }

    // Root-level back handler: when we're on the Home tab, "back" should minimise
    // the app (moveTaskToBack) rather than call finish(). This keeps BikeBridgeService
    // alive in the background and avoids the Suzuki Connect-style "exit?" UX.
    BackHandler(enabled = currentRoute == Tab.Home.route) {
        activity?.moveTaskToBack(true)
    }
    // From any non-Home top-level tab, "back" pops to Home instead of exiting.
    // Sub-routes (pairing/allowlist/trip/composer/about/inspector/mileage) fall
    // through to NavHost's default behaviour, which pops the back stack normally.
    BackHandler(enabled = currentRoute != Tab.Home.route && currentRoute in tabs.map { it.route }) {
        nav.navigate(Tab.Home.route) {
            popUpTo(Tab.Home.route) { inclusive = false }
            launchSingleTop = true
        }
    }

    // Active-ride overlay: wraps the Scaffold so the overlay is full-bleed,
    // covering bottom nav and status bar. The underlying UI stays composed
    // behind the overlay so returning is instant.
    ActiveRideLayer(metric = activeRideMetric) {
    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            // Custom premium nav (see ui/nav/GixxerBottomNav.kt) — replaces the M3
            // NavigationBar pill indicator with a minimal icon+label+accent-dot.
            // currentRoute may be a sub-route (e.g. "pairing"), in which case no
            // tab matches and all dots are hidden — that's the correct visual.
            dev.mrwick.gixxerbridge.ui.nav.GixxerBottomNav(
                tabs = navTabs,
                currentRoute = currentRoute,
                onTabSelected = { tab ->
                    // Sub-route → tab tap: pop any sub-routes off and switch to
                    // the chosen tab. Without this branch the old code's
                    // popUpTo(Home) with saveState/restoreState could no-op when
                    // pairing screen was on top — user reported "tapping Home does
                    // nothing" from the pairing wizard.
                    val onSubRoute = currentRoute != null && currentRoute !in navTabs.map { it.route }
                    nav.navigate(tab.route) {
                        if (onSubRoute) {
                            // Hard pop — clear everything down to (and including)
                            // the start destination, then land on the chosen tab.
                            popUpTo(nav.graph.startDestinationId) { inclusive = false }
                        } else {
                            popUpTo(Tab.Home.route) { saveState = true }
                        }
                        launchSingleTop = true
                        restoreState = !onSubRoute
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = nav, startDestination = Tab.Home.route) {
                composable(Tab.Home.route) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    HomeScreen(
                        onOpenPairing = { nav.navigate("pairing") },
                        onStartRide = {
                            // No "manual ride start" API yet — rides auto-detect from
                            // telemetry. Sending the user to Trips is the most useful
                            // action this button can take today; future: bind to a
                            // manual rideLogger.startManual() once that exists.
                            nav.navigate(Tab.Trips.route) {
                                popUpTo(Tab.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        onOpenNav = {
                            // Launch Google Maps with no specific destination — opens
                            // the app to its main screen so the rider can search /
                            // navigate. Falls back to a generic geo: intent if Maps
                            // isn't installed (other map apps will handle it).
                            val launchMaps = ctx.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                            val intent = launchMaps ?: android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("geo:0,0?q="),
                            )
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { ctx.startActivity(intent) }
                            catch (_: android.content.ActivityNotFoundException) { /* no map app installed */ }
                        },
                        onOpenMaintenance = {
                            nav.navigate(Tab.Settings.route) {
                                popUpTo(Tab.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
                composable(Tab.Dashboard.route) {
                    DashboardScreen(
                        vm = viewModel(),
                        onOpenPairing = { nav.navigate("pairing") },
                    )
                }
                composable(Tab.Trips.route) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val vm: TripsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            TripsViewModel(ctx.applicationContext) as T
                    })
                    TripsScreen(
                        vm,
                        onOpenRide = { rideId -> nav.navigate("trip/$rideId") },
                        onOpenSettings = { nav.navigate(Tab.Settings.route) },
                    )
                }
                composable("trip/{rideId}") { backStackEntry ->
                    val rideId = backStackEntry.arguments?.getString("rideId")?.toLongOrNull() ?: 0L
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val vm: TripsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            TripsViewModel(ctx.applicationContext) as T
                    })
                    TripDetailScreen(rideId, vm)
                }
                composable(Tab.Stats.route) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val app = ctx.applicationContext as android.app.Application
                    val vm: StatsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            StatsViewModel(app) as T
                    })
                    StatsScreen(
                        vm,
                        onOpenSettings = { nav.navigate(Tab.Settings.route) },
                        onOpenMileage = { nav.navigate("mileage") },
                    )
                }
                composable("mileage") {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val app = ctx.applicationContext as android.app.Application
                    val vm: MileageViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            MileageViewModel(app) as T
                    })
                    MileageScreen(vm)
                }
                composable("service-history") {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val app = ctx.applicationContext as android.app.Application
                    val vm: ServiceHistoryViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            ServiceHistoryViewModel(app) as T
                    })
                    ServiceHistoryScreen(vm)
                }
                composable("inspector") {
                    val vm: InspectorViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            InspectorViewModel(AppGraph.frameStream) as T
                    })
                    InspectorScreen(vm)
                }
                composable("diagnostics") { DiagnosticsScreen() }
                composable(Tab.Settings.route) {
                    SettingsScreen(
                        vm = viewModel(),
                        safetyVm = viewModel(),
                        onOpenPairing = { nav.navigate("pairing") },
                        onOpenAllowlist = { nav.navigate("allowlist") },
                        onOpenInspector = { nav.navigate("inspector") },
                        onOpenDiagnostics = { nav.navigate("diagnostics") },
                        onOpenAbout = { nav.navigate("about") },
                        onOpenMileage = { nav.navigate("mileage") },
                        onOpenServiceHistory = { nav.navigate("service-history") },
                    )
                }
                composable("pairing") {
                    PairingScreen(vm = viewModel(), onPaired = { nav.popBackStack() })
                }
                composable("allowlist") {
                    AllowlistScreen(vm = viewModel())
                }
                composable("composer") {
                    FrameComposerScreen(vm = viewModel())
                }
                composable("about") {
                    dev.mrwick.gixxerbridge.ui.about.AboutScreen()
                }
            }
        }
    }
    } // end ActiveRideLayer
}
