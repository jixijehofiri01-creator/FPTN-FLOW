package com.example.feature.home

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.example.domain.atmosphere.SolarAtmosphereEngine
import com.example.domain.model.BackgroundGradientStyle
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsMode
import com.example.domain.model.FptnServer
import com.example.domain.model.HomeQuickTileId
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.ServerSortOrder
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnErrorInfo
import com.example.domain.model.WeatherEffectMode
import com.example.core.haptics.HapticEngine
import com.example.core.ui.flags.CinematicFlagHeroBackdrop
import com.example.feature.shared.FptnUiState
import kotlinx.coroutines.delay

private val SmoothEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

@Composable
fun HomeScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    bottomNavInset: Dp,
    applyNavBarsInset: Boolean = false,
    onToggleConnection: (originRootPx: Offset) -> Unit,
    onWaveOriginUpdated: (originRootPx: Offset) -> Unit,
    onSelectAutoServer: () -> Unit,
    onSelectServer: (FptnServer) -> Unit,
    onSelectSortOrder: (ServerSortOrder) -> Unit,
    onRefreshPing: () -> Unit,
    onRefreshHealth: () -> Unit,
    onVerifyRootLive: () -> Unit,
    onDismissRootRevokedWarning: () -> Unit,
    onSelectSlidingTunnel: (SlidingTunnelMode) -> Unit,
    onSelectBypassMethod: (BypassMethod) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSelectDnsMode: (DnsMode) -> Unit,
    onSelectDnsEntry: (SavedDnsEntry) -> Unit = {},
    onCycleDnsModeQuick: () -> Unit = {},
    onToggleRootModeQuick: (Boolean) -> Unit,
    onToggleStallWatchdogQuick: (Boolean) -> Unit,
    onSelectMtuQuick: (Int) -> Unit,
    onToggleSmartRfQuick: (Boolean) -> Unit,
    onSelectBypassRegion: (BypassRegion) -> Unit = {},
    onToggleAutoFailover: (Boolean) -> Unit = {},
    onToggleAutoSwitchPresetQuick: (Boolean) -> Unit,
    onToggleHapticsQuick: (Boolean) -> Unit,
    onAddQuickTile: (HomeQuickTileId) -> Unit,
    onRemoveQuickTile: (HomeQuickTileId) -> Unit,
    onMoveQuickTile: (HomeQuickTileId, Int) -> Unit,
    onResetQuickTiles: () -> Unit,
    onShowLockedMessage: () -> Unit,
    onDismissError: () -> Unit,
    onAutoHealConnection: () -> Unit = {},
    onRefreshWeatherPassive: () -> Unit = {},
    onNavigateToTokens: () -> Unit,
    onNavigateToPresets: () -> Unit,
    onNavigateToManualOverrides: () -> Unit = {},
    onNavigateToBypass: () -> Unit,
    onNavigateToDns: () -> Unit,
    onNavigateToTunneling: () -> Unit,
    onNavigateToPermissions: () -> Unit,
    onNavigateToLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showServerPicker by remember { mutableStateOf(false) }
    var dismissedPermissionsBanner by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        onRefreshHealth()
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            hapticEngine.performSuccess(view)
        }
        onRefreshHealth()
    }

    // Re-show permission banner if a previously granted permission is revoked later
    val permHealth = uiState.permissionHealth
    LaunchedEffect(
        permHealth.missingNotifications,
        permHealth.missingBatteryUnrestricted,
        permHealth.missingVpnConsent,
    ) {
        if (permHealth.hasMissingSystemPermissions) {
            dismissedPermissionsBanner = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefreshHealth()
                onRefreshWeatherPassive()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val connState = uiState.connectionState
    val isConnected = connState is VpnConnectionState.Connected
    val isVpnActive = uiState.isVpnActive
    val settings = uiState.settings

    // Periodically evaluate astronomical NOAA solar phase every 60s when enabled
    var solarMinuteTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(settings.enableSolarAtmosphere) {
        if (settings.enableSolarAtmosphere) {
            while (true) {
                solarMinuteTick = System.currentTimeMillis() / 60_000L
                delay(60_000L)
            }
        }
    }
    val solarState = remember(settings.enableSolarAtmosphere, solarMinuteTick) {
        if (settings.enableSolarAtmosphere) SolarAtmosphereEngine.evaluate() else null
    }

    // Cache last error info so AnimatedVisibility fades & collapses smoothly when Error clears
    var cachedErrorInfo by remember { mutableStateOf<VpnErrorInfo?>(null) }
    LaunchedEffect(connState) {
        if (connState is VpnConnectionState.Error) {
            cachedErrorInfo = connState.errorInfo
        }
    }

    val statusAccentState = animateColorAsState(
        targetValue = when (connState) {
            is VpnConnectionState.Connected -> MaterialTheme.colorScheme.tertiary
            is VpnConnectionState.SwitchingServer,
            is VpnConnectionState.Reconnecting,
            -> Color(0xFFE5A100)
            is VpnConnectionState.Connecting -> MaterialTheme.colorScheme.primary
            is VpnConnectionState.Disconnecting -> MaterialTheme.colorScheme.secondary
            is VpnConnectionState.Error -> MaterialTheme.colorScheme.error
            is VpnConnectionState.Disconnected -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 560, easing = SmoothEase),
        label = "statusAccent",
    )
    val secondaryAccent = MaterialTheme.colorScheme.secondary
    val bgColor = MaterialTheme.colorScheme.background

    val solarOverlayBrush = remember(solarState, settings.solarAtmosphereIntensity) {
        solarState?.let { s ->
            val mult = settings.solarAtmosphereIntensity.coerceIn(0.25f, 2.0f)
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to s.skyTopColor.copy(alpha = (s.overlayStrength * 0.92f * mult).coerceIn(0.06f, 0.62f)),
                    0.38f to s.skyMidColor.copy(alpha = (s.overlayStrength * 0.60f * mult).coerceIn(0.04f, 0.42f)),
                    0.72f to s.ambientGlowColor.copy(alpha = (s.overlayStrength * 0.30f * mult).coerceIn(0.02f, 0.24f)),
                    1.0f to Color.Transparent,
                ),
            )
        }
    }

    val effectiveWeatherMode = remember(settings.weatherEffectMode, uiState.liveWeather.condition) {
        when (settings.weatherEffectMode) {
            WeatherEffectMode.AUTO_LOCATION -> uiState.liveWeather.condition
            else -> settings.weatherEffectMode
        }
    }

    val activeHeroCountryCode = when (connState) {
        is VpnConnectionState.Connected -> connState.server.countryCode
        is VpnConnectionState.SwitchingServer -> connState.toServer.countryCode
        else -> uiState.activeServer?.countryCode
    }

    val availableServerCountryCodes = remember(uiState.enabledServers) {
        uiState.enabledServers.mapNotNull { it.countryCode?.uppercase() }.toSet()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .drawBehind {
                val statusAccent = statusAccentState.value
                // Perfectly centered, symmetric viewport-relative gradients (zero left/right imbalance on any aspect ratio!)
                val w = size.width
                val h = size.height
                val topCenter = Offset(w * 0.5f, h * 0.14f)
                val auroraRadius = maxOf(w, h) * 0.72f
                when (settings.backgroundGradientStyle) {
                    BackgroundGradientStyle.MINIMAL_CLEAN -> Unit
                    BackgroundGradientStyle.GLASS_AURORA -> {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to statusAccent.copy(alpha = if (isConnected) 0.20f else 0.11f),
                                    0.38f to secondaryAccent.copy(alpha = if (isConnected) 0.09f else 0.05f),
                                    0.78f to Color.Transparent,
                                    1.0f to Color.Transparent,
                                ),
                            ),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to statusAccent.copy(alpha = if (isConnected) 0.18f else 0.10f),
                                    0.48f to secondaryAccent.copy(alpha = if (isConnected) 0.08f else 0.04f),
                                    1.0f to Color.Transparent,
                                ),
                                center = topCenter,
                                radius = auroraRadius,
                            ),
                            radius = auroraRadius,
                            center = topCenter,
                        )
                    }
                    BackgroundGradientStyle.DEEP_COSMOS -> {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFF1D2B64).copy(alpha = if (isConnected) 0.40f else 0.24f),
                                    0.42f to statusAccent.copy(alpha = if (isConnected) 0.16f else 0.08f),
                                    0.82f to Color.Transparent,
                                    1.0f to Color.Transparent,
                                ),
                            ),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFF2B3E8C).copy(alpha = if (isConnected) 0.24f else 0.13f),
                                    0.55f to statusAccent.copy(alpha = if (isConnected) 0.10f else 0.05f),
                                    1.0f to Color.Transparent,
                                ),
                                center = topCenter,
                                radius = auroraRadius,
                            ),
                            radius = auroraRadius,
                            center = topCenter,
                        )
                    }
                    BackgroundGradientStyle.EMERALD_PULSE -> {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFF00897B).copy(alpha = if (isConnected) 0.30f else 0.15f),
                                    0.40f to Color(0xFF26A69A).copy(alpha = if (isConnected) 0.14f else 0.07f),
                                    0.80f to Color.Transparent,
                                    1.0f to Color.Transparent,
                                ),
                            ),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFF26A69A).copy(alpha = if (isConnected) 0.20f else 0.10f),
                                    0.55f to Color(0xFF00897B).copy(alpha = if (isConnected) 0.08f else 0.04f),
                                    1.0f to Color.Transparent,
                                ),
                                center = topCenter,
                                radius = auroraRadius,
                            ),
                            radius = auroraRadius,
                            center = topCenter,
                        )
                    }
                    BackgroundGradientStyle.SUNSET_GLOW -> {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFFD84315).copy(alpha = if (isConnected) 0.26f else 0.13f),
                                    0.40f to Color(0xFF8E24AA).copy(alpha = if (isConnected) 0.15f else 0.07f),
                                    0.80f to Color.Transparent,
                                    1.0f to Color.Transparent,
                                ),
                            ),
                        )
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color(0xFFFF7043).copy(alpha = if (isConnected) 0.18f else 0.09f),
                                    0.52f to Color(0xFF8E24AA).copy(alpha = if (isConnected) 0.09f else 0.04f),
                                    1.0f to Color.Transparent,
                                ),
                                center = topCenter,
                                radius = auroraRadius,
                            ),
                            radius = auroraRadius,
                            center = topCenter,
                        )
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Circadian NOAA time-of-day + seasonal atmospheric lighting with directional sun/moon horizon glow
        if (solarOverlayBrush != null && solarState != null) {
            val mult = settings.solarAtmosphereIntensity.coerceIn(0.25f, 2.0f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(solarOverlayBrush)
                    .drawBehind {
                        val glowCenter = Offset(
                            x = size.width * solarState.sunXFraction.coerceIn(0.05f, 0.95f),
                            y = size.height * solarState.sunYFraction.coerceIn(0.02f, 0.65f),
                        )
                        val glowRadius = (size.maxDimension * 0.62f).coerceAtLeast(180f)
                        val glowAlpha = (solarState.overlayStrength * 0.72f * mult).coerceIn(0.05f, 0.56f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to solarState.ambientGlowColor.copy(alpha = glowAlpha),
                                    0.42f to solarState.skyMidColor.copy(alpha = glowAlpha * 0.45f),
                                    1.0f to Color.Transparent,
                                ),
                                center = glowCenter,
                                radius = glowRadius,
                            ),
                            radius = glowRadius,
                            center = glowCenter,
                        )
                    },
            )
        }

        val isTabletTwoPane = maxWidth >= 760.dp

        // Large atmospheric silk-cloth flag backdrop with infinite World Flag Matrix & 360° clock-dial tilt/angle
        CinematicFlagHeroBackdrop(
            connectionState = connState,
            fallbackCountryCode = activeHeroCountryCode,
            enabled = settings.enableFlagBackdrop,
            matrixTransitionEnabled = settings.enableFlagMatrixTransition,
            backdropScale = settings.flagBackdropScale,
            dimAmount = settings.flagBackdropDim,
            showCoatOfArms = settings.flagShowCoatOfArms,
            physicsIntensity = settings.flagPhysicsIntensity,
            hapticEngine = hapticEngine,
            matrixDirection = settings.flagMatrixDirection,
            flagTiltDegrees = settings.flagTiltDegrees,
            matrixAngleDegrees = settings.flagMatrixAngleDegrees,
            matrixRandomAngle = settings.flagMatrixRandomAngle,
            matrixSpeed = settings.flagMatrixSpeed,
            matrixZoom = settings.flagMatrixZoom,
            highlightAvailableServers = settings.flagMatrixHighlightServers,
            availableServerCountryCodes = availableServerCountryCodes,
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isTabletTwoPane) 460.dp else 380.dp)
                .align(Alignment.TopCenter),
        )

        // 3D volumetric weather atmosphere + exact-frame synced ground splashes & micro-haptic raindrop taps
        if (effectiveWeatherMode != WeatherEffectMode.OFF && effectiveWeatherMode != WeatherEffectMode.AUTO_LOCATION) {
            WeatherAtmosphereOverlay(
                mode = effectiveWeatherMode,
                intensity = settings.weatherIntensity,
                windStrength = settings.weatherWindStrength,
                fogDepth = settings.weatherFogDepth,
                enableHaptics = settings.enableHaptics && settings.enableWeatherHaptics,
                onRaindropImpact = { strength, isDrizzle, isHeavy ->
                    hapticEngine.onRaindropImpact(
                        intensity = strength,
                        isDrizzle = isDrizzle,
                        isHeavy = isHeavy,
                    )
                },
                onThunderPulse = { hapticEngine.onDistantThunder() },
                modifier = Modifier.fillMaxSize(),
            )
        }

        val heroHeaderSection: @Composable () -> Unit = {
            val rootActiveMode = uiState.isRootAvailable && settings.useRootMode

            AssistChip(
                onClick = {
                    hapticEngine.performNavigationClick(view)
                    onNavigateToBypass()
                },
                label = {
                    Text(
                        text = if (rootActiveMode) {
                            "${stringResource(R.string.home_route_mode_root)} • ${settings.effectiveCensorshipStrategyName()}"
                        } else {
                            "${stringResource(R.string.home_route_mode_vpn)} • ${settings.effectiveCensorshipStrategyName()}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (rootActiveMode) Icons.Rounded.AdminPanelSettings else Icons.Rounded.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Bespoke Minimalist Animated Vector Shield Hero floating above the silky waving flag
            Box(
                modifier = Modifier.size(if (isTabletTwoPane) 192.dp else 176.dp),
                contentAlignment = Alignment.Center,
            ) {
                MinimalistShieldHero(
                    connectionState = connState,
                    size = if (isTabletTwoPane) 178.dp else 162.dp,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val statusTitleText = when (connState) {
                is VpnConnectionState.Disconnected -> stringResource(R.string.status_disconnected)
                is VpnConnectionState.Connecting -> stringResource(R.string.status_connecting)
                is VpnConnectionState.SwitchingServer -> stringResource(R.string.status_switching_server)
                is VpnConnectionState.Reconnecting -> stringResource(R.string.status_reconnecting)
                is VpnConnectionState.Connected -> stringResource(R.string.status_connected)
                is VpnConnectionState.Disconnecting -> stringResource(R.string.status_disconnecting)
                is VpnConnectionState.Error -> stringResource(R.string.status_error)
            }

            AnimatedContent(
                targetState = statusTitleText,
                transitionSpec = {
                    fadeIn(tween(260, easing = SmoothEase))
                        .togetherWith(fadeOut(tween(160)))
                },
                label = "statusTitleAnim",
            ) { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("home_status_title"),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            val subtitleText = when (connState) {
                is VpnConnectionState.Connected -> buildString {
                    append("${connState.server.cleanDisplayTitle} • ${connState.assignedIp}")
                    if (connState.isRootMode) append(" • Stealth")
                    if (!connState.activeDohUrl.isNullOrBlank()) {
                        append(" • DoH")
                    } else {
                        append(" • DNS ${connState.dnsServer}")
                    }
                }
                is VpnConnectionState.Connecting -> connState.stage
                is VpnConnectionState.SwitchingServer -> {
                    val fromName = connState.fromServer?.cleanName
                    if (!fromName.isNullOrBlank()) {
                        "$fromName → ${connState.toServer.cleanName} • ${connState.stage}"
                    } else {
                        "${connState.toServer.cleanName} • ${connState.stage}"
                    }
                }
                is VpnConnectionState.Reconnecting -> "${connState.reason} (${connState.attempt}/${connState.maxAttempts})"
                is VpnConnectionState.Disconnecting -> connState.stage
                is VpnConnectionState.Error -> connState.errorInfo.title
                is VpnConnectionState.Disconnected -> "SNI: ${settings.customSni}"
            }

            AnimatedContent(
                targetState = subtitleText,
                transitionSpec = {
                    fadeIn(tween(260, easing = SmoothEase))
                        .togetherWith(fadeOut(tween(150)))
                },
                label = "statusSubtitleAnim",
            ) { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connState is VpnConnectionState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        val statusBannersSection: @Composable () -> Unit = {
            // Background Root revocation warning (when Root revoked in Magisk/KernelSU)
            AnimatedVisibility(
                visible = permHealth.rootRevokedWarning,
                enter = fadeIn(tween(280, easing = SmoothEase)) + expandVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
                exit = fadeOut(tween(200)) + shrinkVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
            ) {
                Column {
                    RootRevokedWarningBanner(
                        onRecheckRoot = {
                            hapticEngine.performNavigationClick(view)
                            onVerifyRootLive()
                            onDismissRootRevokedWarning()
                        },
                        onDismiss = {
                            hapticEngine.performNavigationClick(view)
                            onDismissRootRevokedWarning()
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            // Live background permission alert with direct 1-tap system links
            val showPermBanner = !dismissedPermissionsBanner &&
                permHealth.hasMissingSystemPermissions &&
                uiState.enabledServers.isNotEmpty()
            AnimatedVisibility(
                visible = showPermBanner,
                enter = fadeIn(tween(300, easing = SmoothEase)) + expandVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
                exit = fadeOut(tween(220)) + shrinkVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
            ) {
                Column {
                    MissingPermissionsBanner(
                        missingNotifications = permHealth.missingNotifications,
                        missingBatteryUnrestricted = permHealth.missingBatteryUnrestricted,
                        missingVpnConsent = permHealth.missingVpnConsent,
                        onGrantNotifications = {
                            hapticEngine.performNavigationClick(view)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        },
                                    )
                                }
                            } else {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}"),
                                        ),
                                    )
                                }
                            }
                        },
                        onGrantBattery = {
                            hapticEngine.performNavigationClick(view)
                            val opened = runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                                true
                            }.getOrDefault(false)
                            if (!opened) {
                                runCatching {
                                    context.startActivity(
                                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                                    )
                                }
                            }
                        },
                        onGrantVpn = {
                            hapticEngine.performNavigationClick(view)
                            val intent = runCatching { VpnService.prepare(context) }.getOrNull()
                            if (intent != null) {
                                vpnConsentLauncher.launch(intent)
                            } else {
                                onRefreshHealth()
                            }
                        },
                        onOpenPermissions = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateToPermissions()
                        },
                        onDismiss = {
                            hapticEngine.performNavigationClick(view)
                            dismissedPermissionsBanner = true
                        },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            AnimatedVisibility(
                visible = connState is VpnConnectionState.Error,
                enter = fadeIn(tween(320, easing = SmoothEase)) + expandVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
                exit = fadeOut(tween(240, easing = SmoothEase)) + shrinkVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
            ) {
                val displayError = (connState as? VpnConnectionState.Error)?.errorInfo ?: cachedErrorInfo
                if (displayError != null) {
                    Column {
                        ConnectionErrorDiagnosticCard(
                            errorInfo = displayError,
                            onAutoHeal = {
                                hapticEngine.performSuccess(view)
                                onAutoHealConnection()
                            },
                            onOpenLogs = {
                                hapticEngine.performNavigationClick(view)
                                onNavigateToLogs()
                            },
                            onDismiss = {
                                hapticEngine.performNavigationClick(view)
                                onDismissError()
                            },
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }
        }

        val connectAndServerSection: @Composable () -> Unit = {
            if (uiState.enabledServers.isEmpty()) {
                EmptyTokenStateCard(
                    onAddToken = {
                        hapticEngine.performSuccess(view)
                        onNavigateToTokens()
                    },
                )
            } else {
                PullToConnectTrack(
                    connectionState = connState,
                    enabled = true,
                    hapticEngine = hapticEngine,
                    onToggleTriggered = onToggleConnection,
                    onOriginUpdated = onWaveOriginUpdated,
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Always unlocked — selecting a new server while connected seamlessly reconnects!
                ActiveServerSelectorCard(
                    server = uiState.activeServer,
                    autoSelect = settings.autoSelectBestServer,
                    isPinging = uiState.isPinging,
                    isVpnActive = isVpnActive,
                    showCoatOfArms = settings.flagShowCoatOfArms,
                    onOpenPicker = {
                        hapticEngine.performNavigationClick(view)
                        showServerPicker = true
                    },
                    onRefreshPing = {
                        hapticEngine.performNavigationClick(view)
                        onRefreshPing()
                    },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        val telemetryAndQuickGridSection: @Composable () -> Unit = {
            AnimatedVisibility(
                visible = isConnected,
                enter = fadeIn(tween(360, easing = SmoothEase)) + expandVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
                exit = fadeOut(tween(240)) + shrinkVertically(
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                ),
            ) {
                val connectedAt = (connState as? VpnConnectionState.Connected)?.connectedAtMillis ?: 0L
                Column {
                    LiveSpeedTelemetryCard(connectedAtMillis = connectedAt)
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            HomeQuickSettingsGrid(
                settings = settings,
                isRootAvailable = uiState.isRootAvailable,
                isVpnActive = isVpnActive,
                onSelectSlidingTunnel = { mode ->
                    hapticEngine.performToggle(view, enabled = true)
                    onSelectSlidingTunnel(mode)
                },
                onSelectBypassMethod = { method ->
                    hapticEngine.performToggle(view, enabled = true)
                    onSelectBypassMethod(method)
                },
                onApplyPreset = { presetId ->
                    hapticEngine.performSuccess(view)
                    onApplyPreset(presetId)
                },
                onSelectDnsMode = { mode ->
                    hapticEngine.performToggle(view, enabled = true)
                    onSelectDnsMode(mode)
                },
                onSelectDnsEntry = { entry ->
                    hapticEngine.performSuccess(view)
                    onSelectDnsEntry(entry)
                },
                onCycleDnsMode = {
                    hapticEngine.performToggle(view, enabled = true)
                    onCycleDnsModeQuick()
                },
                onToggleRootMode = { enabled ->
                    hapticEngine.performToggle(view, enabled)
                    onToggleRootModeQuick(enabled)
                },
                onToggleStallWatchdog = { enabled ->
                    hapticEngine.performToggle(view, enabled)
                    onToggleStallWatchdogQuick(enabled)
                },
                onSelectMtu = { mtu ->
                    hapticEngine.performToggle(view, enabled = true)
                    onSelectMtuQuick(mtu)
                },
                onToggleSmartRf = { enabled ->
                    hapticEngine.performToggle(view, enabled)
                    onToggleSmartRfQuick(enabled)
                },
                onSelectBypassRegion = { region ->
                    hapticEngine.performToggle(view, enabled = true)
                    onSelectBypassRegion(region)
                },
                onToggleAutoFailover = { enabled ->
                    hapticEngine.performToggle(view, enabled)
                    onToggleAutoFailover(enabled)
                },
                onToggleAutoSwitchPreset = { enabled ->
                    hapticEngine.performToggle(view, enabled)
                    onToggleAutoSwitchPresetQuick(enabled)
                },
                onToggleHaptics = { enabled ->
                    hapticEngine.performToggle(view, enabled)
                    onToggleHapticsQuick(enabled)
                },
                onAddQuickTile = { tile ->
                    hapticEngine.performSuccess(view)
                    onAddQuickTile(tile)
                },
                onRemoveQuickTile = { tile ->
                    hapticEngine.performDestructiveAction(view)
                    onRemoveQuickTile(tile)
                },
                onMoveQuickTile = { tile, delta ->
                    hapticEngine.performSliderStep(view)
                    onMoveQuickTile(tile, delta)
                },
                onResetQuickTiles = {
                    hapticEngine.performSuccess(view)
                    onResetQuickTiles()
                },
                onVerifyRootAccess = {
                    hapticEngine.performSuccess(view)
                    onVerifyRootLive()
                },
                onLockedAttempt = {
                    hapticEngine.performLockedReject(view)
                    onShowLockedMessage()
                },
                onOpenPresetsSettings = {
                    hapticEngine.performNavigationClick(view)
                    onNavigateToPresets()
                },
                onOpenManualOverrides = {
                    hapticEngine.performNavigationClick(view)
                    onNavigateToManualOverrides()
                },
                onOpenBypassSettings = {
                    hapticEngine.performNavigationClick(view)
                    onNavigateToBypass()
                },
                onOpenDnsSettings = {
                    hapticEngine.performNavigationClick(view)
                    onNavigateToDns()
                },
                onOpenTunnelingSettings = {
                    hapticEngine.performNavigationClick(view)
                    onNavigateToTunneling()
                },
                onOpenLogs = {
                    hapticEngine.performNavigationClick(view)
                    onNavigateToLogs()
                },
            )

            Spacer(modifier = Modifier.height(22.dp))
        }

        if (isTabletTwoPane) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .let { m -> if (applyNavBarsInset) m.navigationBarsPadding() else m }
                    .widthIn(max = 1180.dp)
                    .padding(horizontal = 28.dp, vertical = 16.dp)
                    .padding(bottom = bottomNavInset),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier
                        .weight(0.46f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    heroHeaderSection()
                    connectAndServerSection()
                }

                Column(
                    modifier = Modifier
                        .weight(0.54f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .animateContentSize(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    statusBannersSection()
                    telemetryAndQuickGridSection()
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .let { m -> if (applyNavBarsInset) m.navigationBarsPadding() else m }
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .padding(bottom = bottomNavInset),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                heroHeaderSection()
                statusBannersSection()
                connectAndServerSection()
                telemetryAndQuickGridSection()
            }
        }
    }

    if (showServerPicker) {
        ServerPickerSheet(
            servers = uiState.enabledServers,
            selectedServerId = settings.selectedServerId,
            autoSelectBest = settings.autoSelectBestServer,
            sortOrder = settings.serverSortOrder,
            isPinging = uiState.isPinging,
            showCoatOfArms = settings.flagShowCoatOfArms,
            hapticEngine = hapticEngine,
            onSelectSortOrder = onSelectSortOrder,
            onSelectAuto = onSelectAutoServer,
            onSelectServer = onSelectServer,
            onRefreshPing = onRefreshPing,
            onDismiss = { showServerPicker = false },
        )
    }
}
