package dev.mrwick.gixxerbridge

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.appcompat.app.AppCompatActivity
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
import dev.mrwick.gixxerbridge.ui.inspector.InspectorScreen
import dev.mrwick.gixxerbridge.ui.inspector.InspectorViewModel
import dev.mrwick.gixxerbridge.ui.lock.AppLockGate
import dev.mrwick.gixxerbridge.ui.lock.AppLockViewModel
import dev.mrwick.gixxerbridge.ui.settings.AllowlistScreen
import dev.mrwick.gixxerbridge.ui.settings.AllowlistViewModel
import dev.mrwick.gixxerbridge.ui.settings.PairingScreen
import dev.mrwick.gixxerbridge.ui.settings.PairingViewModel
import dev.mrwick.gixxerbridge.ui.settings.SettingsScreen
import dev.mrwick.gixxerbridge.ui.settings.SettingsViewModel
import dev.mrwick.gixxerbridge.ui.stats.StatsScreen
import dev.mrwick.gixxerbridge.ui.stats.StatsViewModel
import dev.mrwick.gixxerbridge.ui.trips.TripDetailScreen
import dev.mrwick.gixxerbridge.ui.trips.TripsScreen
import dev.mrwick.gixxerbridge.ui.trips.TripsViewModel
import dev.mrwick.gixxerbridge.app.AppGraph

/**
 * Entry activity. FragmentActivity for biometric prompt compatibility (see ui/lock).
 * Hosts a 5-tab bottom-nav (Home / Dashboard / Stats / Trips / Settings) + sub-routes
 * for pairing, allowlist, trip detail, composer, inspector.
 */
class MainActivity : AppCompatActivity() {

    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore result */ }
    private val blePermLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* ignore result */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val lockVm: AppLockViewModel = viewModel(factory = androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(application))
                AppLockGate(lockVm) {
                    AppShell()
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

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Tab("home", "Home", Icons.Default.Home)
    data object Dashboard : Tab("dashboard", "Dashboard", Icons.Default.Speed)
    data object Stats : Tab("stats", "Stats", Icons.Default.BarChart)
    data object Trips : Tab("trips", "Trips", Icons.Default.Route)
    data object Settings : Tab("settings", "Settings", Icons.Default.Settings)
}

private val tabs = listOf(Tab.Home, Tab.Dashboard, Tab.Stats, Tab.Trips, Tab.Settings)

@Composable
private fun AppShell() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: Tab.Home.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(Tab.Home.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            NavHost(navController = nav, startDestination = Tab.Home.route) {
                composable(Tab.Home.route) { HomeScreen(onOpenPairing = { nav.navigate("pairing") }) }
                composable(Tab.Dashboard.route) { DashboardScreen(viewModel()) }
                composable(Tab.Trips.route) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val vm: TripsViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            TripsViewModel(ctx.applicationContext) as T
                    })
                    TripsScreen(vm, onOpenRide = { rideId -> nav.navigate("trip/$rideId") })
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
                    StatsScreen(vm)
                }
                composable("inspector") {
                    val vm: InspectorViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                            InspectorViewModel(AppGraph.frameStream) as T
                    })
                    InspectorScreen(vm)
                }
                composable(Tab.Settings.route) {
                    SettingsScreen(
                        vm = viewModel(),
                        safetyVm = viewModel(),
                        onOpenPairing = { nav.navigate("pairing") },
                        onOpenAllowlist = { nav.navigate("allowlist") },
                        onOpenInspector = { nav.navigate("inspector") },
                        onOpenAbout = { nav.navigate("about") },
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
}
