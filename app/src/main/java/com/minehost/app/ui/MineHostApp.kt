package com.minehost.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.minehost.app.CatalogViewModel
import com.minehost.app.MainViewModel
import com.minehost.app.data.ServerPhase
import com.minehost.app.ui.screens.CatalogScreen
import com.minehost.app.ui.screens.ConsoleScreen
import com.minehost.app.ui.screens.DashboardScreen
import com.minehost.app.ui.screens.OnboardingScreen
import com.minehost.app.ui.screens.SettingsScreen

private object Routes {
    const val DASHBOARD = "dashboard"
    const val CATALOG = "catalog"
    const val CONSOLE = "console"
    const val SETTINGS = "settings"
}

private data class BottomDestination(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit
)

@Composable
fun MineHostApp(viewModel: MainViewModel = viewModel()) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (onboardingComplete) {
            // Flag still loading: hold a plain background instead of flashing.
            null -> Box(modifier = Modifier.fillMaxSize())
            false -> OnboardingScreen(onFinish = viewModel::completeOnboarding)
            true -> MineHostContent(viewModel)
        }
    }
}

@Composable
private fun MineHostContent(viewModel: MainViewModel) {
    val catalogViewModel: CatalogViewModel = viewModel()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val commandHistory by viewModel.commandHistory.collectAsStateWithLifecycle()
    val routerCheck by viewModel.routerCheck.collectAsStateWithLifecycle()
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val playitTunnelState by viewModel.playitTunnelState.collectAsStateWithLifecycle()
    val playitConfigured by viewModel.playitConfigured.collectAsStateWithLifecycle()
    val playitClaimBusy by viewModel.playitClaimBusy.collectAsStateWithLifecycle()
    val autoOpenTunnel by viewModel.autoOpenTunnel.collectAsStateWithLifecycle()
    val profilesState by viewModel.profilesState.collectAsStateWithLifecycle()
    val routerChecking by viewModel.routerChecking.collectAsStateWithLifecycle()
    val routerMappingBusy by viewModel.routerMappingBusy.collectAsStateWithLifecycle()
    val catalogState by catalogViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // Asked once, after the walkthrough, instead of on a cold first frame.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = {}
    )
    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val destinations = listOf(
        BottomDestination(Routes.DASHBOARD, "Home") { Icon(Icons.Rounded.Home, contentDescription = null) },
        BottomDestination(Routes.CATALOG, "Catalog") { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
        BottomDestination(Routes.CONSOLE, "Console") { Icon(Icons.Rounded.Terminal, contentDescription = null) },
        BottomDestination(Routes.SETTINGS, "Settings") { Icon(Icons.Rounded.Settings, contentDescription = null) }
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        // Status bar and display cutouts are handled here (top and sides). The
        // bottom belongs to the navigation bar, and each screen applies its own
        // IME padding, so the keyboard inset is deliberately excluded.
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Top
        ),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                destinations.forEach { destination ->
                    val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (selected) return@NavigationBarItem
                            // Always pop back to Home and never restore a saved
                            // stack, so a tab always opens its own screen.
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        icon = destination.icon,
                        label = { androidx.compose.material3.Text(destination.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    config = config,
                    snapshot = snapshot,
                    snackbarHostState = snackbarHostState,
                    profiles = profilesState.profiles,
                    activeProfileId = profilesState.activeId,
                    onSelectProfile = viewModel::switchProfile,
                    onStart = viewModel::startServer,
                    onStop = viewModel::stopServer,
                    onRestart = viewModel::restartServer,
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenConsole = { navController.navigate(Routes.CONSOLE) },
                    onOpenCatalog = { navController.navigate(Routes.CATALOG) },
                    onCopyAddress = { viewModel.showMessage("Server address copied") }
                )
            }
            composable(Routes.CATALOG) {
                CatalogScreen(
                    state = catalogState,
                    onRefresh = catalogViewModel::refreshVersions,
                    onSelectPlatform = catalogViewModel::selectPlatform,
                    onSelectVersion = catalogViewModel::selectVersion,
                    onDownload = catalogViewModel::downloadSelected,
                    onOpenUrl = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }.onFailure {
                            viewModel.showMessage("No browser is available for that link")
                        }
                    },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) }
                )
            }
            composable(Routes.CONSOLE) {
                ConsoleScreen(
                    snapshot = snapshot,
                    commandHistory = commandHistory,
                    onClear = viewModel::clearLogs,
                    onStart = viewModel::startServer,
                    onStop = viewModel::stopServer,
                    onRestart = viewModel::restartServer,
                    onSendCommand = viewModel::sendCommand,
                    onCopyLog = viewModel::copyConsoleLog
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    config = config,
                    onSave = viewModel::saveConfig,
                    profiles = profilesState.profiles,
                    activeProfileId = profilesState.activeId,
                    onSelectProfile = viewModel::switchProfile,
                    onCreateProfile = viewModel::createProfile,
                    onDuplicateProfile = viewModel::duplicateProfile,
                    onDeleteProfile = viewModel::deleteProfile,
                    onImportCore = viewModel::importCore,
                    onExportWorld = viewModel::exportWorld,
                    onImportWorld = viewModel::importWorld,
                    routerCheck = routerCheck,
                    routerChecking = routerChecking,
                    routerMappingBusy = routerMappingBusy,
                    onCheckAndMap = { port -> viewModel.checkRouterAndMap(port) },
                    onRequestRouterMapping = viewModel::requestRouterMapping,
                    onRemoveRouterMapping = viewModel::removeRouterMapping,
                    tunnelState = tunnelState,
                    onStartTunnel = viewModel::startTunnel,
                    onStopTunnel = viewModel::stopTunnel,
                    playitTunnelState = playitTunnelState,
                    playitConfigured = playitConfigured,
                    playitClaimBusy = playitClaimBusy,
                    onStartPlayitClaim = viewModel::startPlayitClaim,
                    onStartPlayitTunnel = viewModel::startPlayitTunnel,
                    onStopPlayitTunnel = viewModel::stopPlayitTunnel,
                    onUnlinkPlayit = viewModel::unlinkPlayit,
                    autoOpenTunnel = autoOpenTunnel,
                    onSetAutoOpenTunnel = viewModel::setAutoOpenTunnel,
                    onImportResourcePack = viewModel::importResourcePack,
                    onImportModpack = viewModel::importModpack,
                    onImportPlugin = viewModel::importPlugin,
                    onRemovePlugin = viewModel::removePlugin,
                    // Derived here so Settings only recomposes on real transitions,
                    // not on every coalesced snapshot emission.
                    serverRunning = snapshot.phase == ServerPhase.RUNNING,
                    onMessage = viewModel::showMessage
                )
            }
        }
    }
}
