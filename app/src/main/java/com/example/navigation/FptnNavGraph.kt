package com.example.navigation

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.core.haptics.HapticEngine
import com.example.core.ui.effects.WaterWaveController
import com.example.core.ui.effects.fullScreenWaterWave
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.rememberLocalizedEnvironment
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.FptnTheme
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.VpnConnectionState
import com.example.feature.appearance.AppearanceSettingsScreen
import com.example.feature.backup.BackupScreen
import com.example.feature.bypass.BypassSettingsScreen
import com.example.feature.dns.DnsSettingsScreen
import com.example.feature.home.HomeScreen
import com.example.feature.logs.LogsScreen
import com.example.feature.presets.ManualOverridesScreen
import com.example.feature.presets.PresetsSettingsScreen
import com.example.feature.root.RootSettingsScreen
import com.example.feature.routing.TunnelingSettingsScreen
import com.example.feature.settings.PermissionsScreen
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.StabilitySettingsScreen
import com.example.feature.share.ShareScreen
import com.example.feature.shared.FptnViewModel
import com.example.feature.tokens.TokenManagerScreen

object FptnRoutes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val SHARE = "share"

    const val SETTINGS_TOKENS = "settings_tokens"
    const val SETTINGS_PRESETS = "settings_presets"
    const val SETTINGS_OVERRIDES = "settings_overrides"
    const val SETTINGS_BYPASS = "settings_bypass"
    const val SETTINGS_DNS = "settings_dns"
    const val SETTINGS_TUNNELING = "settings_tunneling"
    const val SETTINGS_ROOT = "settings_root"
    const val SETTINGS_ADDITIONAL = "settings_additional"
    const val SETTINGS_APPEARANCE = "settings_appearance"
    const val SETTINGS_PERMISSIONS = "settings_permissions"
    const val SETTINGS_LOGS = "settings_logs"
    const val SETTINGS_BACKUP = "settings_backup"
}

private val M3EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
private val M3EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)
private val topLevelRouteSet = setOf(FptnRoutes.HOME, FptnRoutes.SETTINGS, FptnRoutes.SHARE)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.monolithicEnterTransition(): EnterTransition {
    val fromTop = initialState.destination.route in topLevelRouteSet
    val toTop = targetState.destination.route in topLevelRouteSet
    return if (fromTop && toTop) {
        // Silky M3 Fade-Through between bottom tabs (zero vertical jump)
        fadeIn(animationSpec = tween(durationMillis = 240, easing = M3EmphasizedDecelerate)) +
            scaleIn(
                initialScale = 0.975f,
                animationSpec = tween(durationMillis = 260, easing = M3EmphasizedDecelerate),
            )
    } else {
        // M3 Shared-Axis X forward into settings detail screen
        slideInHorizontally(
            initialOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() },
            animationSpec = tween(durationMillis = 320, easing = M3EmphasizedDecelerate),
        ) + fadeIn(animationSpec = tween(durationMillis = 240, easing = M3EmphasizedDecelerate))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.monolithicExitTransition(): ExitTransition {
    val fromTop = initialState.destination.route in topLevelRouteSet
    val toTop = targetState.destination.route in topLevelRouteSet
    return if (fromTop && toTop) {
        fadeOut(animationSpec = tween(durationMillis = 150, easing = M3EmphasizedAccelerate))
    } else {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -(fullWidth * 0.07f).toInt() },
            animationSpec = tween(durationMillis = 280, easing = M3EmphasizedDecelerate),
        ) + fadeOut(animationSpec = tween(durationMillis = 180, easing = M3EmphasizedAccelerate))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.monolithicPopEnterTransition(): EnterTransition {
    val fromTop = initialState.destination.route in topLevelRouteSet
    val toTop = targetState.destination.route in topLevelRouteSet
    return if (fromTop && toTop) {
        fadeIn(animationSpec = tween(durationMillis = 240, easing = M3EmphasizedDecelerate))
    } else {
        slideInHorizontally(
            initialOffsetX = { fullWidth -> -(fullWidth * 0.08f).toInt() },
            animationSpec = tween(durationMillis = 300, easing = M3EmphasizedDecelerate),
        ) + fadeIn(animationSpec = tween(durationMillis = 230, easing = M3EmphasizedDecelerate))
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.monolithicPopExitTransition(): ExitTransition {
    val fromTop = initialState.destination.route in topLevelRouteSet
    val toTop = targetState.destination.route in topLevelRouteSet
    return if (fromTop && toTop) {
        fadeOut(animationSpec = tween(durationMillis = 150, easing = M3EmphasizedAccelerate))
    } else {
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() },
            animationSpec = tween(durationMillis = 260, easing = M3EmphasizedAccelerate),
        ) + fadeOut(animationSpec = tween(durationMillis = 190, easing = M3EmphasizedAccelerate))
    }
}

private data class TopTabItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
)

private val topTabs = listOf(
    TopTabItem(
        route = FptnRoutes.HOME,
        labelRes = R.string.nav_home,
        selectedIcon = Icons.Rounded.Home,
        unselectedIcon = Icons.Outlined.Home,
        testTag = "nav_tab_home",
    ),
    TopTabItem(
        route = FptnRoutes.SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_tab_settings",
    ),
    TopTabItem(
        route = FptnRoutes.SHARE,
        labelRes = R.string.nav_share,
        selectedIcon = Icons.Rounded.Share,
        unselectedIcon = Icons.Outlined.Share,
        testTag = "nav_tab_share",
    ),
)

@Composable
fun FptnAppRoot(
    viewModel: FptnViewModel,
    deepLinkToken: String?,
    onDeepLinkConsumed: () -> Unit,
    onExitApp: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val baseContext = LocalContext.current
    val baseConfiguration = LocalConfiguration.current
    val localized = rememberLocalizedEnvironment(
        baseContext = baseContext,
        baseConfiguration = baseConfiguration,
        appLanguage = uiState.settings.appLanguage,
    )
    val context = localized.context
    val isRu = localized.isRussian
    val view = LocalView.current

    val hapticEngine = remember(baseContext) { HapticEngine(baseContext) }
    LaunchedEffect(uiState.settings.enableHaptics) {
        hapticEngine.isEnabled = uiState.settings.enableHaptics
    }

    val waterWaveController = remember { WaterWaveController() }
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    var lastWaveOrigin by remember { mutableStateOf(Offset.Zero) }
    var previousConnState by remember { mutableStateOf<VpnConnectionState>(VpnConnectionState.Disconnected) }

    LaunchedEffect(uiState.connectionState) {
        val current = uiState.connectionState
        val prev = previousConnState
        if (current::class != prev::class) {
            when (current) {
                is VpnConnectionState.Connecting -> {
                    hapticEngine.onConnectingStart()
                }
                is VpnConnectionState.SwitchingServer -> {
                    hapticEngine.onSwitchingServerStart()
                }
                is VpnConnectionState.Reconnecting -> {
                    hapticEngine.onReconnectingPulse()
                }
                is VpnConnectionState.Disconnecting -> {
                    hapticEngine.onDisconnectingStart()
                }
                is VpnConnectionState.Connected -> {
                    if (uiState.settings.enableWaterWaveEffect) {
                        waterWaveController.triggerWave(lastWaveOrigin, isConnect = true)
                    }
                    hapticEngine.onConnectWaveLaunch()
                }
                is VpnConnectionState.Error -> {
                    hapticEngine.onConnectionError(view)
                }
                is VpnConnectionState.Disconnected -> {
                    if (prev is VpnConnectionState.Connected || prev is VpnConnectionState.Disconnecting) {
                        if (uiState.settings.enableWaterWaveEffect) {
                            waterWaveController.triggerWave(lastWaveOrigin, isConnect = false)
                        }
                        hapticEngine.onDisconnectWaveLaunch()
                    }
                }
            }
        }
        previousConnState = current
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage
        if (msg != null) {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            viewModel.consumeSnackbar()
        }
    }

    LaunchedEffect(deepLinkToken) {
        if (!deepLinkToken.isNullOrBlank()) {
            viewModel.addTokenProfile(name = "", rawToken = deepLinkToken)
            onDeepLinkConsumed()
        }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.startVpnConnection()
        } else {
            hapticEngine.onConnectionError(view)
            viewModel.showMessage(
                tr(
                    isRu,
                    "Для запуска туннеля требуется системное разрешение VPN",
                    "System VPN permission is required to start the tunnel",
                ),
            )
        }
    }

    fun handleToggleVpn(originRootPx: Offset) {
        lastWaveOrigin = originRootPx
        val state = uiState.connectionState
        if (state is VpnConnectionState.Disconnecting) return
        val isStopping = state is VpnConnectionState.Connected ||
            state is VpnConnectionState.Connecting ||
            state is VpnConnectionState.SwitchingServer ||
            state is VpnConnectionState.Reconnecting
        if (isStopping) {
            viewModel.stopVpnConnection()
        } else {
            // In Stealth Root Mode, kernel TUN fptn0 does not require Android VpnService consent
            val useStealthRoot = uiState.isRootAvailable && uiState.settings.useRootMode
            if (useStealthRoot) {
                viewModel.startVpnConnection()
            } else {
                val prepareIntent = VpnService.prepare(context)
                if (prepareIntent != null) {
                    vpnPermissionLauncher.launch(prepareIntent)
                } else {
                    viewModel.startVpnConnection()
                }
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isTopLevelRoute = currentRoute == null || topTabs.any { it.route == currentRoute }

    // Never carry a stale snackbar from a previous screen over buttons on the newly opened screen.
    LaunchedEffect(currentRoute) {
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    CompositionLocalProvider(
        LocalContext provides localized.context,
        LocalConfiguration provides localized.configuration,
        LocalIsRussian provides localized.isRussian,
    ) {
        FptnTheme(
            themeMode = uiState.settings.themeMode,
            dynamicColor = uiState.settings.dynamicColor,
        ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .fullScreenWaterWave(
                    controller = waterWaveController,
                    config = uiState.settings.waterWaveConfig(),
                ),
        ) {
            val useNavigationRail = maxWidth >= 600.dp
            val showDockedBottomBar = !useNavigationRail && isTopLevelRoute
            // Docked NavigationBar sits strictly below the clipped NavHost viewport so cards,
            // lists, or buttons can ever slide behind the bottom navigation buttons.
            val bottomNavInset = 12.dp

            Row(modifier = Modifier.fillMaxSize()) {
                AnimatedVisibility(
                    visible = useNavigationRail,
                    enter = expandHorizontally(tween(260, easing = M3EmphasizedDecelerate)) + fadeIn(tween(220)),
                    exit = shrinkHorizontally(tween(200, easing = M3EmphasizedAccelerate)) + fadeOut(tween(150)),
                ) {
                    Row(modifier = Modifier.fillMaxHeight()) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier
                                .fillMaxHeight()
                                .statusBarsPadding()
                                .navigationBarsPadding(),
                        ) {
                            topTabs.forEach { tab ->
                                val selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true ||
                                    (tab.route == FptnRoutes.SETTINGS && currentRoute?.startsWith("settings_") == true)
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = {
                                        hapticEngine.performSegmentTick(view)
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes),
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    modifier = Modifier.testTag(tab.testTag),
                                )
                            }
                        }
                        VerticalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                        )
                    }
                }

                // Hard vertical split: NavHost lives in a clipped Box strictly ABOVE the docked NavigationBar.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clipToBounds(),
                    ) {
                        NavHost(
                            navController = navController,
                            startDestination = FptnRoutes.HOME,
                            modifier = Modifier.fillMaxSize(),
                            enterTransition = { monolithicEnterTransition() },
                            exitTransition = { monolithicExitTransition() },
                            popEnterTransition = { monolithicPopEnterTransition() },
                            popExitTransition = { monolithicPopExitTransition() },
                        ) {
                            composable(FptnRoutes.HOME) {
                                HomeScreen(
                                    uiState = uiState,
                                    hapticEngine = hapticEngine,
                                    bottomNavInset = bottomNavInset,
                                    applyNavBarsInset = useNavigationRail,
                                    onToggleConnection = ::handleToggleVpn,
                                onWaveOriginUpdated = { origin -> lastWaveOrigin = origin },
                                onSelectAutoServer = viewModel::selectAutoServer,
                                onSelectServer = viewModel::selectServer,
                                onSelectSortOrder = viewModel::updateServerSortOrder,
                                onRefreshPing = {
                                    viewModel.refreshPings(onSonarPulse = {
                                        hapticEngine.onPingSonarPulse()
                                    })
                                },
                                onRefreshHealth = { viewModel.refreshPermissionsAndRootHealth(verifyRoot = false) },
                                onVerifyRootLive = viewModel::verifyRootAccessLive,
                                onDismissRootRevokedWarning = viewModel::dismissRootRevokedWarning,
                                onSelectSlidingTunnel = viewModel::updateSlidingTunnelMode,
                                onSelectBypassMethod = viewModel::updateBypassMethod,
                                onApplyPreset = viewModel::applyNetworkPreset,
                                onSelectDnsMode = viewModel::selectDnsMode,
                                onSelectDnsEntry = viewModel::selectDnsEntry,
                                onCycleDnsModeQuick = viewModel::cycleDnsModeQuick,
                                onToggleRootModeQuick = viewModel::setRootModeEnabled,
                                onToggleStallWatchdogQuick = viewModel::setStallWatchdogEnabled,
                                onSelectMtuQuick = viewModel::updateTunMtu,
                                onToggleSmartRfQuick = viewModel::setSmartRfRouting,
                                onSelectBypassRegion = viewModel::updateBypassRegion,
                                onToggleAutoFailover = { enabled ->
                                    viewModel.setAutoFailoverServer(enabled)
                                    viewModel.setAutoRotateSniOnFail(enabled)
                                },
                                onToggleAutoSwitchPresetQuick = viewModel::setAutoSwitchPresetsByNetwork,
                                onToggleHapticsQuick = viewModel::setEnableHaptics,
                                onAddQuickTile = viewModel::addHomeQuickTile,
                                onRemoveQuickTile = viewModel::removeHomeQuickTile,
                                onMoveQuickTile = viewModel::moveHomeQuickTile,
                                onResetQuickTiles = viewModel::resetHomeQuickTiles,
                                onShowLockedMessage = {
                                    viewModel.showMessage(
                                        tr(
                                            isRu,
                                            "Сначала отключите VPN, чтобы изменить сетевые параметры без конфликтов",
                                            "Disconnect VPN first to change network settings cleanly",
                                        ),
                                    )
                                },
                                onDismissError = viewModel::dismissConnectionError,
                                onAutoHealConnection = viewModel::autoHealConnection,
                                onRefreshWeatherPassive = {
                                    if (uiState.settings.weatherEffectMode == com.example.domain.model.WeatherEffectMode.AUTO_LOCATION) {
                                        viewModel.refreshLiveWeather(force = false)
                                    }
                                },
                                onNavigateToTokens = {
                                    navController.navigate(FptnRoutes.SETTINGS_TOKENS)
                                },
                                onNavigateToPresets = {
                                    navController.navigate(FptnRoutes.SETTINGS_PRESETS)
                                },
                                onNavigateToManualOverrides = {
                                    navController.navigate(FptnRoutes.SETTINGS_OVERRIDES)
                                },
                                onNavigateToBypass = {
                                    navController.navigate(FptnRoutes.SETTINGS_BYPASS)
                                },
                                onNavigateToDns = {
                                    navController.navigate(FptnRoutes.SETTINGS_DNS)
                                },
                                onNavigateToTunneling = {
                                    navController.navigate(FptnRoutes.SETTINGS_TUNNELING)
                                },
                                onNavigateToPermissions = {
                                    navController.navigate(FptnRoutes.SETTINGS_PERMISSIONS)
                                },
                                onNavigateToLogs = {
                                    navController.navigate(FptnRoutes.SETTINGS_LOGS)
                                },
                            )
                        }

                        composable(FptnRoutes.SETTINGS) {
                            val appUpdateStatus by viewModel.appUpdateStatus.collectAsStateWithLifecycle()
                            SettingsScreen(
                                uiState = uiState,
                                appUpdateStatus = appUpdateStatus,
                                hapticEngine = hapticEngine,
                                bottomNavInset = bottomNavInset,
                                applyNavBarsInset = useNavigationRail,
                                onOpenTokens = { navController.navigate(FptnRoutes.SETTINGS_TOKENS) },
                                onOpenPresets = { navController.navigate(FptnRoutes.SETTINGS_PRESETS) },
                                onOpenManualOverrides = { navController.navigate(FptnRoutes.SETTINGS_OVERRIDES) },
                                onOpenBypass = { navController.navigate(FptnRoutes.SETTINGS_BYPASS) },
                                onOpenDns = { navController.navigate(FptnRoutes.SETTINGS_DNS) },
                                onOpenTunneling = { navController.navigate(FptnRoutes.SETTINGS_TUNNELING) },
                                onOpenRoot = { navController.navigate(FptnRoutes.SETTINGS_ROOT) },
                                onOpenAdditional = { navController.navigate(FptnRoutes.SETTINGS_ADDITIONAL) },
                                onOpenAppearance = { navController.navigate(FptnRoutes.SETTINGS_APPEARANCE) },
                                onOpenPermissions = { navController.navigate(FptnRoutes.SETTINGS_PERMISSIONS) },
                                onOpenLogs = { navController.navigate(FptnRoutes.SETTINGS_LOGS) },
                                onOpenBackup = { navController.navigate(FptnRoutes.SETTINGS_BACKUP) },
                                onCheckForUpdates = { viewModel.checkForAppUpdates(manual = true) },
                                onRefreshProjectMeta = { viewModel.refreshProjectMetaIfNeeded(force = true) },
                                onVerifyRootLive = viewModel::verifyRootAccessLive,
                                onSelectAppLanguage = viewModel::updateAppLanguage,
                                onShowLockedMessage = {
                                    viewModel.showMessage(
                                        tr(
                                            isRu,
                                            "Отключите VPN, чтобы изменить этот раздел без разрыва туннеля",
                                            "Disconnect VPN first to edit this section without dropping the tunnel",
                                        ),
                                    )
                                },
                                onConfirmExit = {
                                    viewModel.safeShutdownBeforeExit()
                                    onExitApp()
                                },
                            )
                        }

                        composable(FptnRoutes.SHARE) {
                            ShareScreen(
                                hapticEngine = hapticEngine,
                                bottomNavInset = bottomNavInset,
                                applyNavBarsInset = useNavigationRail,
                                onShowMessage = viewModel::showMessage,
                            )
                        }

                        composable(FptnRoutes.SETTINGS_TOKENS) {
                            TokenManagerScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onAddToken = viewModel::addTokenProfile,
                                onClearTokenError = viewModel::clearTokenError,
                                onRenameProfile = viewModel::renameTokenProfile,
                                onToggleProfileEnabled = viewModel::setTokenProfileEnabled,
                                onDeleteProfile = viewModel::deleteTokenProfile,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_PRESETS) {
                            PresetsSettingsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onToggleAutoSwitch = viewModel::setAutoSwitchPresetsByNetwork,
                                onApplyPreset = viewModel::applyNetworkPreset,
                                onSavePreset = viewModel::saveNetworkPreset,
                                onDuplicatePreset = viewModel::duplicateNetworkPreset,
                                onDeletePreset = viewModel::deleteNetworkPreset,
                                onRestoreDefaults = viewModel::restoreDefaultNetworkPresets,
                                onRegenerateRandomSnis = viewModel::regenerateRandomizedPresetSnis,
                                onResetPresetOverride = { viewModel.resetManualOverride(ManualOverrideKey.PRESET) },
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_OVERRIDES) {
                            ManualOverridesScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onResetOverride = viewModel::resetManualOverride,
                                onResetAll = viewModel::resetAllManualOverrides,
                                onSelectAutoServer = viewModel::selectAutoServer,
                                onSelectServer = viewModel::selectServer,
                                onApplyPreset = viewModel::applyNetworkPreset,
                                onSelectDnsMode = viewModel::selectDnsMode,
                                onSelectDnsEntry = viewModel::selectDnsEntry,
                                onSaveIpv4Dns = viewModel::updateDnsServers,
                                onSelectBypassMethod = viewModel::updateBypassMethod,
                                onSelectSniSpoofingMode = viewModel::updateSniSpoofingMode,
                                onSaveCustomSni = viewModel::updateCustomSni,
                                onPickRandomSni = { viewModel.pickRandomSni(regionOnly = true) },
                                onSelectSlidingTunnel = viewModel::updateSlidingTunnelMode,
                                onUpdateTunMtu = viewModel::updateTunMtu,
                                onToggleStallWatchdog = viewModel::setStallWatchdogEnabled,
                                onSelectWatchdogSensitivity = viewModel::updateWatchdogSensitivity,
                                onUpdateWatchdogProbeSec = viewModel::updateWatchdogProbeSec,
                                onUpdateWatchdogStallSec = viewModel::updateWatchdogStallSec,
                                onUpdateWatchdogCooldownSec = viewModel::updateWatchdogCooldownSec,
                                onToggleReconnectOnFailure = viewModel::setReconnectOnFailure,
                                onToggleReconnectOnNetworkChange = viewModel::setReconnectOnNetworkChange,
                                onUpdateMaxReconnectAttempts = viewModel::updateMaxReconnectAttempts,
                                onUpdateKeepaliveIntervalSec = viewModel::updateKeepaliveIntervalSec,
                                onUpdateNetworkSwitchDebounceSec = viewModel::updateNetworkSwitchDebounceSec,
                                onSelectBypassRegion = viewModel::updateBypassRegion,
                                onToggleSmartRfRouting = viewModel::setSmartRfRouting,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_BYPASS) {
                            BypassSettingsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onSelectBypassMethod = viewModel::updateBypassMethod,
                                onSelectSniSpoofingMode = viewModel::updateSniSpoofingMode,
                                onSaveCustomSni = { sni ->
                                    viewModel.updateCustomSni(sni)
                                    viewModel.showMessage(tr(isRu, "SNI обновлён: ${sni.trim()}", "SNI updated: ${sni.trim()}"))
                                },
                                onResetDefaultSni = viewModel::resetDefaultSni,
                                onPickRandomSni = viewModel::pickRandomSni,
                                onStartSniScan = { viewModel.startSniAutoScan() },
                                onStopSniScan = viewModel::stopSniAutoScan,
                                onSelectSlidingMode = viewModel::updateSlidingTunnelMode,
                                onShowMessage = viewModel::showMessage,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_DNS) {
                            DnsSettingsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onSelectDnsMode = viewModel::selectDnsMode,
                                onSelectDnsEntry = viewModel::selectDnsEntry,
                                onAddCustomDnsEntry = viewModel::addCustomDnsEntry,
                                onDeleteDnsEntry = viewModel::deleteDnsEntry,
                                onRestoreDefaultDns = viewModel::restoreDefaultDnsEntries,
                                onPingSmartDns = viewModel::pingSmartDnsEntries,
                                onSaveIpv4Dns = viewModel::updateDnsServers,
                                onResetDnsOverride = { viewModel.resetManualOverride(ManualOverrideKey.DNS) },
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_TUNNELING) {
                            TunnelingSettingsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onLoadApps = viewModel::loadInstalledAppsIfNeeded,
                                onSelectBypassRegion = viewModel::updateBypassRegion,
                                onAddCustomDirectDomain = viewModel::addCustomDirectDomain,
                                onRemoveCustomDirectDomain = viewModel::removeCustomDirectDomain,
                                onAddCustomVpnDomain = viewModel::addCustomVpnDomain,
                                onRemoveCustomVpnDomain = viewModel::removeCustomVpnDomain,
                                onToggleUnlockProtectedRuWithRoot = viewModel::setUnlockProtectedRuWithRoot,
                                onSelectSplitMode = viewModel::updateSplitTunnelMode,
                                onTogglePackage = viewModel::togglePackageSelection,
                                onAddManualPackage = viewModel::addManualPackage,
                                onToggleIpv6 = viewModel::setAllowIpv6,
                                onSelectMtu = viewModel::updateTunMtu,
                                onSelectQuickSni = { sni ->
                                    viewModel.updateCustomSni(sni)
                                    viewModel.showMessage(tr(isRu, "SNI обновлён: $sni", "SNI updated: $sni"))
                                },
                                onPickRandomSni = viewModel::pickRandomSni,
                                onVerifyRootLive = viewModel::verifyRootAccessLive,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_ROOT) {
                            RootSettingsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onVerifyRootLive = viewModel::verifyRootAccessLive,
                                onToggleRootMode = viewModel::setRootModeEnabled,
                                onToggleAdaptiveBoost = viewModel::setRootAdaptiveBoostEnabled,
                                onToggleSecondaryVpnChaining = viewModel::setAllowSecondaryVpnChaining,
                                onUpdateTunInterfaceName = viewModel::updateRootTunInterfaceName,
                                onCleanupRulesNow = viewModel::cleanupRootRulesNow,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_ADDITIONAL) {
                            StabilitySettingsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onUpdateAutoPingInterval = viewModel::updateAutoPingInterval,
                                onToggleReconnectOnFailure = viewModel::setReconnectOnFailure,
                                onToggleReconnectOnNetworkChange = viewModel::setReconnectOnNetworkChange,
                                onUpdateMaxReconnectAttempts = viewModel::updateMaxReconnectAttempts,
                                onUpdateReconnectDelaySec = viewModel::updateReconnectDelaySec,
                                onUpdateHandshakeTimeoutSec = viewModel::updateHandshakeTimeoutSec,
                                onUpdateWsOpenTimeoutSec = viewModel::updateWsOpenTimeoutSec,
                                onToggleStallWatchdog = viewModel::setStallWatchdogEnabled,
                                onSelectWatchdogSensitivity = viewModel::updateWatchdogSensitivity,
                                onUpdateWatchdogProbeSec = viewModel::updateWatchdogProbeSec,
                                onUpdateWatchdogStallSec = viewModel::updateWatchdogStallSec,
                                onUpdateWatchdogCooldownSec = viewModel::updateWatchdogCooldownSec,
                                onUpdateKeepaliveIntervalSec = viewModel::updateKeepaliveIntervalSec,
                                onUpdateNetworkSwitchDebounceSec = viewModel::updateNetworkSwitchDebounceSec,
                                onToggleAutoRotateSniOnFail = viewModel::setAutoRotateSniOnFail,
                                onToggleAutoFailoverServer = viewModel::setAutoFailoverServer,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_APPEARANCE) {
                            AppearanceSettingsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onSelectThemeMode = viewModel::updateThemeMode,
                                onSelectAppLanguage = viewModel::updateAppLanguage,
                                onSelectBackgroundGradient = viewModel::updateBackgroundGradientStyle,
                                onToggleDynamicColor = viewModel::setDynamicColor,
                                onToggleHaptics = viewModel::setEnableHaptics,
                                onToggleTrafficNotification = viewModel::setShowTrafficNotification,
                                onToggleFlagBackdrop = viewModel::setEnableFlagBackdrop,
                                onToggleFlagMatrixTransition = viewModel::setEnableFlagMatrixTransition,
                                onSelectFlagMatrixDirection = viewModel::updateFlagMatrixDirection,
                                onUpdateFlagTiltDegrees = viewModel::updateFlagTiltDegrees,
                                onUpdateFlagMatrixAngleDegrees = viewModel::updateFlagMatrixAngleDegrees,
                                onToggleFlagMatrixRandomAngle = viewModel::setFlagMatrixRandomAngle,
                                onToggleFlagAngleSnapToTicks = viewModel::setFlagAngleSnapToTicks,
                                onUpdateFlagMatrixSpeed = viewModel::updateFlagMatrixSpeed,
                                onUpdateFlagMatrixZoom = viewModel::updateFlagMatrixZoom,
                                onToggleFlagMatrixHighlightServers = viewModel::setFlagMatrixHighlightServers,
                                onToggleFlagCoatOfArms = viewModel::setFlagShowCoatOfArms,
                                onUpdateFlagScale = viewModel::updateFlagBackdropScale,
                                onUpdateFlagDim = viewModel::updateFlagBackdropDim,
                                onUpdateFlagPhysics = viewModel::updateFlagPhysicsIntensity,
                                onToggleSolarAtmosphere = viewModel::setEnableSolarAtmosphere,
                                onUpdateSolarAtmosphereIntensity = viewModel::updateSolarAtmosphereIntensity,
                                onSelectWeatherEffectMode = viewModel::setWeatherEffectMode,
                                onToggleWeatherHaptics = viewModel::setEnableWeatherHaptics,
                                onUpdateWeatherIntensity = viewModel::updateWeatherIntensity,
                                onUpdateWeatherWindStrength = viewModel::updateWeatherWindStrength,
                                onUpdateWeatherFogDepth = viewModel::updateWeatherFogDepth,
                                onRefreshLiveWeather = { viewModel.refreshLiveWeather(force = true) },
                                onToggleWaterWaveEffect = viewModel::setEnableWaterWaveEffect,
                                onUpdateWaveSpeed = viewModel::updateWaveSpeed,
                                onUpdateWaveStrength = viewModel::updateWaveStrength,
                                onUpdateWaveSmoothness = viewModel::updateWaveSmoothness,
                                onUpdateWaveElasticity = viewModel::updateWaveElasticity,
                                onUpdateWaveTint = viewModel::updateWaveTintStrength,
                                onUpdateWaveDispersion = viewModel::updateWaveDispersion,
                                onUpdateWaveRingWidth = viewModel::updateWaveRingWidth,
                                onApplyWavePreset = viewModel::applyWavePreset,
                                onResetWaveDefaults = viewModel::resetWaveDefaults,
                                onTestWave = { isConnect ->
                                    waterWaveController.triggerWave(Offset.Unspecified, isConnect = isConnect)
                                },
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_PERMISSIONS) {
                            PermissionsScreen(
                                uiState = uiState,
                                hapticEngine = hapticEngine,
                                onRequestRootAccess = viewModel::requestRootAccessManually,
                                onVerifyRootLive = viewModel::verifyRootAccessLive,
                                onShowMessage = viewModel::showMessage,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_LOGS) {
                            val liveLogs by viewModel.logsFlow.collectAsStateWithLifecycle()
                            LogsScreen(
                                logs = liveLogs,
                                hapticEngine = hapticEngine,
                                onExportLogs = viewModel::exportLogsText,
                                onClearLogs = viewModel::clearLogs,
                                onShowMessage = viewModel::showMessage,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }

                        composable(FptnRoutes.SETTINGS_BACKUP) {
                            BackupScreen(
                                hapticEngine = hapticEngine,
                                onExportBackup = viewModel::exportBackup,
                                onExportOfficialBackup = viewModel::exportOfficialCompatibleBackup,
                                onExportRawTokens = viewModel::exportRawTokensText,
                                onImportBackup = viewModel::importBackup,
                                onShowMessage = viewModel::showMessage,
                                onNavigateBack = { navController.popBackStack() },
                            )
                        }
                    }

                        // Floating SnackbarHost pinned at TopCenter (clear of TopAppBar back arrow & all bottom buttons/lists)
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .widthIn(max = 560.dp)
                                .statusBarsPadding()
                                .padding(top = 8.dp, start = 60.dp, end = 16.dp),
                        )
                    }

                    // Docked NavigationBar strictly outside NavHost: zero overlap with any list, card, or button!
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showDockedBottomBar,
                        enter = expandVertically(
                            expandFrom = Alignment.Top,
                            animationSpec = tween(durationMillis = 220, easing = M3EmphasizedDecelerate),
                        ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                        exit = shrinkVertically(
                            shrinkTowards = Alignment.Top,
                            animationSpec = tween(durationMillis = 180, easing = M3EmphasizedAccelerate),
                        ) + fadeOut(animationSpec = tween(durationMillis = 140)),
                    ) {
                        NavigationBar {
                            topTabs.forEach { tab ->
                                val selected =
                                    navBackStackEntry?.destination?.hierarchy?.any { it.route == tab.route } == true
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        hapticEngine.performSegmentTick(view)
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = stringResource(tab.labelRes),
                                        )
                                    },
                                    label = { Text(stringResource(tab.labelRes)) },
                                    modifier = Modifier.testTag(tab.testTag),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
