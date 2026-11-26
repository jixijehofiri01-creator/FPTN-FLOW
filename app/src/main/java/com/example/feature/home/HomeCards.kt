package com.example.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsMode
import com.example.domain.model.FptnServer
import com.example.domain.model.HomeQuickTileId
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.VpnErrorInfo
import com.example.domain.model.VpnSettings
import com.example.core.ui.flags.ServerFlagIcon
import com.example.core.ui.localization.localizedDescription
import com.example.core.ui.localization.localizedName
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedSubtitle
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.vpn.root.RootShellManager

@Composable
fun ActiveServerSelectorCard(
    server: FptnServer?,
    autoSelect: Boolean,
    isPinging: Boolean,
    isVpnActive: Boolean = false,
    showCoatOfArms: Boolean = true,
    onOpenPicker: () -> Unit,
    onRefreshPing: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Distinct clickable server selector card (ALWAYS unlocked — switches & reconnects cleanly!)
        Card(
            modifier = Modifier
                .weight(1f)
                .testTag("home_server_selector_card")
                .clickable(onClick = onOpenPicker),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ServerFlagIcon(
                    countryCode = server?.countryCode,
                    isCensoredZone = server?.isCensoredZone == true,
                    size = 38.dp,
                    showCoatOfArms = showCoatOfArms,
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isVpnActive -> stringResource(R.string.home_server_tap_to_switch)
                            autoSelect -> stringResource(R.string.home_server_auto)
                            else -> stringResource(R.string.home_server_selector_label)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isVpnActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = server?.cleanDisplayTitle ?: stringResource(R.string.home_select_server_placeholder),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (server != null) {
                        Text(
                            text = server.endpoint,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                PingBadge(pingMs = server?.pingMs)
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.home_select_server_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }
        }

        // Clearly separated standalone squircle Ping Refresh button (zero mis-taps with server selector!)
        FilledTonalIconButton(
            onClick = onRefreshPing,
            enabled = !isPinging,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .size(56.dp)
                .testTag("home_refresh_ping_button"),
        ) {
            if (isPinging) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.2.dp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.home_ping_refresh),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectionErrorDiagnosticCard(
    errorInfo: VpnErrorInfo,
    onAutoHeal: () -> Unit = {},
    onOpenLogs: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_error_diagnostic_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = errorInfo.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                text = errorInfo.details,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = errorInfo.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.88f),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Button(
                    onClick = onAutoHeal,
                    modifier = Modifier.testTag("home_error_auto_heal_button"),
                ) {
                    Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.home_error_auto_heal))
                }
                FilledTonalButton(
                    onClick = onOpenLogs,
                    modifier = Modifier.testTag("home_error_logs_button"),
                ) {
                    Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.home_error_logs))
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("home_error_dismiss_button"),
                ) {
                    Text(stringResource(R.string.home_error_dismiss), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissingPermissionsBanner(
    missingNotifications: Boolean,
    missingBatteryUnrestricted: Boolean,
    missingVpnConsent: Boolean = false,
    onGrantNotifications: () -> Unit,
    onGrantBattery: () -> Unit,
    onGrantVpn: () -> Unit,
    onOpenPermissions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reasonNotif = stringResource(R.string.home_perm_reason_notifications)
    val reasonBattery = stringResource(R.string.home_perm_reason_battery)
    val reasonVpn = stringResource(R.string.home_perm_reason_vpn)
    val reason = buildList {
        if (missingNotifications) add(reasonNotif)
        if (missingBatteryUnrestricted) add(reasonBattery)
        if (missingVpnConsent) add(reasonVpn)
    }.joinToString(", ")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_permissions_banner"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.home_perm_banner_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.close_cd),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = stringResource(R.string.home_perm_banner_desc, reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (missingNotifications) {
                    AssistChip(
                        onClick = onGrantNotifications,
                        label = { Text(stringResource(R.string.home_perm_notifications)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("home_perm_fix_notifications"),
                    )
                }
                if (missingBatteryUnrestricted) {
                    AssistChip(
                        onClick = onGrantBattery,
                        label = { Text(stringResource(R.string.home_perm_battery)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("home_perm_fix_battery"),
                    )
                }
                if (missingVpnConsent) {
                    AssistChip(
                        onClick = onGrantVpn,
                        label = { Text(stringResource(R.string.home_perm_vpn)) },
                        leadingIcon = {
                            Icon(Icons.Rounded.VpnKey, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("home_perm_fix_vpn"),
                    )
                }
                AssistChip(
                    onClick = onOpenPermissions,
                    label = { Text(stringResource(R.string.home_perm_all)) },
                    modifier = Modifier.testTag("home_permissions_open_button"),
                )
            }
        }
    }
}

@Composable
fun RootRevokedWarningBanner(
    onRecheckRoot: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_root_revoked_banner"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = tr("Root-доступ отключён", "Root access revoked"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = tr(
                    "Проверка su показала отзыв прав в Magisk/KernelSU. Скрытый Root-режим и разблокировка защищённого списка РФ автоматически отключены.",
                    "Live su verification detected revoked root permissions in Magisk/KernelSU. Root Stealth mode has been turned off automatically.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.90f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(tr("Понятно", "Got it"), color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Spacer(modifier = Modifier.width(6.dp))
                FilledTonalButton(onClick = onRecheckRoot) {
                    Text(tr("Проверить Root", "Check Root"))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeQuickSettingsGrid(
    settings: VpnSettings,
    isRootAvailable: Boolean,
    isVpnActive: Boolean,
    onSelectSlidingTunnel: (SlidingTunnelMode) -> Unit,
    onSelectBypassMethod: (BypassMethod) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSelectDnsMode: (DnsMode) -> Unit,
    onSelectDnsEntry: (SavedDnsEntry) -> Unit = {},
    onSelectBypassRegion: (BypassRegion) -> Unit = {},
    onToggleAutoFailover: (Boolean) -> Unit = {},
    onCycleDnsMode: () -> Unit = {},
    onToggleRootMode: (Boolean) -> Unit,
    onToggleStallWatchdog: (Boolean) -> Unit,
    onSelectMtu: (Int) -> Unit,
    onToggleSmartRf: (Boolean) -> Unit,
    onToggleAutoSwitchPreset: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onAddQuickTile: (HomeQuickTileId) -> Unit,
    onRemoveQuickTile: (HomeQuickTileId) -> Unit,
    onMoveQuickTile: (HomeQuickTileId, Int) -> Unit,
    onResetQuickTiles: () -> Unit,
    onVerifyRootAccess: () -> Unit = {},
    onLockedAttempt: () -> Unit,
    onOpenPresetsSettings: () -> Unit,
    onOpenBypassSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onOpenTunnelingSettings: () -> Unit,
    onOpenManualOverrides: () -> Unit = {},
    onOpenLogs: () -> Unit,
) {
    var showCustomizer by remember { mutableStateOf(false) }

    val visibleTiles = remember(settings.homeQuickTiles, isRootAvailable) {
        settings.homeQuickTiles
            .distinct()
            .filter { tile -> !tile.requiresRoot || isRootAvailable }
    }

    val availableToAddCount = remember(visibleTiles, isRootAvailable) {
        HomeQuickTileId.entries.count { tile ->
            (!tile.requiresRoot || isRootAvailable) && tile !in visibleTiles
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_quick_settings"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("Быстрые настройки", "Quick controls"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(
                onClick = { showCustomizer = true },
                modifier = Modifier.testTag("home_quick_settings_customize_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(tr("Настроить", "Customize"))
            }
        }

        if (visibleTiles.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("home_quick_settings_empty_card")
                    .clickable { showCustomizer = true },
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = tr("Нет активных модулей", "No active quick tiles"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = tr("Нажмите, чтобы добавить нужные быстрые настройки", "Tap to add quick controls to the home screen"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            val rows = visibleTiles.chunked(2)
            rows.forEach { rowTiles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowTiles.forEach { tileId ->
                        val isTileLocked = isVpnActive && !tileId.liveEditableWhenConnected
                        val showLiveBadge = isVpnActive && tileId.liveEditableWhenConnected
                        Box(modifier = Modifier.weight(1f)) {
                            RenderQuickTile(
                                tileId = tileId,
                                settings = settings,
                                isRootAvailable = isRootAvailable,
                                isLocked = isTileLocked,
                                showLiveBadge = showLiveBadge,
                                onLockedAttempt = onLockedAttempt,
                                onSelectSlidingTunnel = onSelectSlidingTunnel,
                                onSelectBypassMethod = onSelectBypassMethod,
                                onApplyPreset = onApplyPreset,
                                onSelectDnsMode = onSelectDnsMode,
                                onSelectDnsEntry = onSelectDnsEntry,
                                onSelectBypassRegion = onSelectBypassRegion,
                                onToggleAutoFailover = onToggleAutoFailover,
                                onToggleRootMode = onToggleRootMode,
                                onToggleStallWatchdog = onToggleStallWatchdog,
                                onSelectMtu = onSelectMtu,
                                onToggleSmartRf = onToggleSmartRf,
                                onToggleAutoSwitchPreset = onToggleAutoSwitchPreset,
                                onToggleHaptics = onToggleHaptics,
                                onOpenPresetsSettings = onOpenPresetsSettings,
                                onOpenBypassSettings = onOpenBypassSettings,
                                onOpenDnsSettings = onOpenDnsSettings,
                                onOpenTunnelingSettings = onOpenTunnelingSettings,
                                onOpenManualOverrides = onOpenManualOverrides,
                                onOpenLogs = onOpenLogs,
                            )
                        }
                    }
                    if (rowTiles.size == 1) {
                        if (availableToAddCount > 0) {
                            QuickAddPlaceholderTile(
                                availableCount = availableToAddCount,
                                onClick = { showCustomizer = true },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    if (showCustomizer) {
        QuickTilesCustomizerSheet(
            activeTiles = visibleTiles,
            isRootAvailable = isRootAvailable,
            onAddTile = onAddQuickTile,
            onRemoveTile = onRemoveQuickTile,
            onMoveTile = onMoveQuickTile,
            onResetDefaults = onResetQuickTiles,
            onVerifyRootAccess = onVerifyRootAccess,
            onDismiss = { showCustomizer = false },
        )
    }
}

private data class QuickSheetOption(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val monospaceSubtitle: Boolean = false,
    val selected: Boolean = false,
    val badge: String? = null,
    val testTag: String? = null,
    val onClick: () -> Unit,
)

/**
 * Full-screen bottom-anchored modal picker sheet immune to bottom NavigationBar, system gesture bar,
 * and floating button overlap across all screen sizes and scroll positions.
 */
@Composable
private fun QuickOptionPickerDialog(
    title: String,
    subtitle: String? = null,
    options: List<QuickSheetOption>,
    secondarySectionTitle: String? = null,
    secondaryOptions: List<QuickSheetOption> = emptyList(),
    enableSearch: Boolean = false,
    footerActionLabel: String? = null,
    footerActionTestTag: String? = null,
    onFooterAction: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val q = searchQuery.trim().lowercase()
    val filteredPrimary = remember(options, q) {
        if (q.isEmpty()) options else options.filter {
            it.title.lowercase().contains(q) || (it.subtitle?.lowercase()?.contains(q) == true)
        }
    }
    val filteredSecondary = remember(secondaryOptions, q) {
        if (q.isEmpty()) secondaryOptions else secondaryOptions.filter {
            it.title.lowercase().contains(q) || (it.subtitle?.lowercase()?.contains(q) == true)
        }
    }

    val hostView = LocalView.current
    val density = LocalDensity.current
    val hostNavBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rootSystemNavBottomDp = with(density) {
        (ViewCompat.getRootWindowInsets(hostView)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: 0).toDp()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val dialogView = LocalView.current
        val dialogNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val dialogRootNavBottomDp = with(density) {
            (ViewCompat.getRootWindowInsets(dialogView)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
                ?.bottom ?: 0).toDp()
        }
        val safeNavBottom = maxOf(dialogNavBottom, hostNavBarBottom, rootSystemNavBottomDp, dialogRootNavBottomDp)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .statusBarsPadding()
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val maxSheetHeight = (maxHeight * 0.86f).coerceAtLeast(240.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .heightIn(max = maxSheetHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 12.dp, bottom = safeNavBottom + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Drag pill handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .width(38.dp)
                            .height(4.dp)
                            .background(
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = CircleShape,
                            ),
                    )

                    // Header row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!subtitle.isNullOrBlank()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("quick_picker_close_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.close_cd),
                            )
                        }
                    }

                    if (enableSearch) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            placeholder = { Text(tr("Поиск…", "Search…")) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("quick_picker_search_input"),
                        )
                    }

                    // weight(1f, fill = false) guarantees header + pinned footer button NEVER get pushed
                    // off-screen or behind Android's system navigation buttons!
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filteredPrimary, key = { it.key }) { item ->
                            QuickOptionRowCard(
                                item = item,
                                onClick = {
                                    onDismiss()
                                    item.onClick()
                                },
                            )
                        }

                        if (!secondarySectionTitle.isNullOrBlank() && filteredSecondary.isNotEmpty()) {
                            item(key = "secondary_header") {
                                Column {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    HorizontalDivider()
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Text(
                                        text = secondarySectionTitle,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            items(filteredSecondary, key = { it.key }) { item ->
                                QuickOptionRowCard(
                                    item = item,
                                    onClick = {
                                        onDismiss()
                                        item.onClick()
                                    },
                                )
                            }
                        }
                    }

                    if (!footerActionLabel.isNullOrBlank() && onFooterAction != null) {
                        HorizontalDivider()
                        OutlinedButton(
                            onClick = {
                                onDismiss()
                                onFooterAction()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .let { m -> if (footerActionTestTag != null) m.testTag(footerActionTestTag) else m },
                        ) {
                            Text(
                                text = footerActionLabel,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickOptionRowCard(
    item: QuickSheetOption,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (item.selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (item.selected) {
            BorderStroke(1.2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
        } else {
            BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        },
        modifier = Modifier
            .fillMaxWidth()
            .let { m -> if (item.testTag != null) m.testTag(item.testTag) else m }
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item.selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!item.badge.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                        ) {
                            Text(
                                text = item.badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (!item.subtitle.isNullOrBlank()) {
                    Text(
                        text = item.subtitle,
                        style = if (item.monospaceSubtitle) {
                            MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily)
                        } else {
                            MaterialTheme.typography.labelSmall
                        },
                        color = if (item.selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (item.selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = tr("Выбрано", "Selected"),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun RenderQuickTile(
    tileId: HomeQuickTileId,
    settings: VpnSettings,
    isRootAvailable: Boolean,
    isLocked: Boolean,
    showLiveBadge: Boolean,
    onLockedAttempt: () -> Unit,
    onSelectSlidingTunnel: (SlidingTunnelMode) -> Unit,
    onSelectBypassMethod: (BypassMethod) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSelectDnsMode: (DnsMode) -> Unit,
    onSelectDnsEntry: (SavedDnsEntry) -> Unit,
    onSelectBypassRegion: (BypassRegion) -> Unit,
    onToggleAutoFailover: (Boolean) -> Unit,
    onToggleRootMode: (Boolean) -> Unit,
    onToggleStallWatchdog: (Boolean) -> Unit,
    onSelectMtu: (Int) -> Unit,
    onToggleSmartRf: (Boolean) -> Unit,
    onToggleAutoSwitchPreset: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onOpenPresetsSettings: () -> Unit,
    onOpenBypassSettings: () -> Unit,
    onOpenDnsSettings: () -> Unit,
    onOpenTunnelingSettings: () -> Unit,
    onOpenManualOverrides: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    when (tileId) {
        HomeQuickTileId.PRESET -> {
            var presetSheetOpen by remember { mutableStateOf(false) }
            val activePreset = settings.networkPresets.firstOrNull { it.id == settings.activePresetId }
            val basePresetName = activePreset?.localizedName() ?: tr("Свой режим", "Custom")
            val activePresetName = if (ManualOverrideKey.PRESET in settings.manualOverrides) {
                "$basePresetName • ${tr("вручную", "manual")}"
            } else {
                basePresetName
            }
            QuickDropdownTile(
                icon = Icons.Rounded.Tune,
                title = tr("Пресет сети", "Network preset"),
                subtitle = activePresetName,
                testTag = "quick_preset_tile",
                isLocked = isLocked,
                onClick = { if (isLocked) onLockedAttempt() else presetSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )
            if (presetSheetOpen && !isLocked) {
                val forceBadge = tr("Поверх замков", "Force override")
                val flexibleFallback = tr("Гибкий пользовательский профиль", "Flexible custom profile")
                QuickOptionPickerDialog(
                    title = tr("Пресет сети", "Network preset"),
                    subtitle = tr("Применяется мгновенно с мягким переподключением", "Applies immediately with smooth tunnel reconnect"),
                    options = settings.networkPresets.map { preset ->
                        val bypassShort = if (preset.overrideBypass) preset.bypassMethod.localizedShortTitle() else null
                        val slidingShort = if (preset.overrideSlidingTunnel) preset.slidingTunnelMode.localizedShortTitle() else null
                        val dnsShort = preset.effectiveDnsModeOverride()?.localizedShortTitle()
                        val summary = buildString {
                            if (bypassShort != null) append(bypassShort)
                            if (slidingShort != null) {
                                if (isNotEmpty()) append(" • ")
                                append(slidingShort)
                            }
                            if (preset.overrideMtu) {
                                if (isNotEmpty()) append(" • ")
                                append("MTU ${preset.tunMtu}")
                            }
                            if (dnsShort != null) {
                                if (isNotEmpty()) append(" • ")
                                append("DNS: $dnsShort")
                            }
                        }.ifBlank { flexibleFallback }
                        QuickSheetOption(
                            key = "preset_${preset.id}",
                            title = preset.localizedName(),
                            subtitle = summary,
                            selected = preset.id == settings.activePresetId,
                            badge = if (preset.forceOverrideManualLocks) forceBadge else null,
                            testTag = "quick_preset_option_${preset.id}",
                            onClick = { onApplyPreset(preset.id) },
                        )
                    },
                    enableSearch = settings.networkPresets.size > 6,
                    footerActionLabel = tr("Управлять и создавать пресеты…", "Manage & create presets…"),
                    footerActionTestTag = "quick_preset_manage_option",
                    onFooterAction = onOpenPresetsSettings,
                    onDismiss = { presetSheetOpen = false },
                )
            }
        }

        HomeQuickTileId.SLIDING_TUNNEL -> {
            var tunnelSheetOpen by remember { mutableStateOf(false) }
            QuickDropdownTile(
                icon = Icons.Rounded.Sync,
                title = tr("Туннель", "Tunnel"),
                subtitle = settings.slidingTunnelMode.localizedShortTitle(),
                testTag = "quick_sliding_tile",
                isLocked = isLocked,
                onClick = { if (isLocked) onLockedAttempt() else tunnelSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )
            if (tunnelSheetOpen && !isLocked) {
                QuickOptionPickerDialog(
                    title = tr("Скользящий туннель", "Rolling tunnel"),
                    subtitle = tr("Выберите количество параллельных скользящих каналов", "Choose number of parallel rolling channels"),
                    options = SlidingTunnelMode.entries.map { mode ->
                        QuickSheetOption(
                            key = "sliding_${mode.name}",
                            title = mode.localizedTitle(),
                            subtitle = mode.nativeStrategyName,
                            monospaceSubtitle = true,
                            selected = settings.slidingTunnelMode == mode,
                            testTag = "quick_sliding_option_${mode.name}",
                            onClick = { onSelectSlidingTunnel(mode) },
                        )
                    },
                    onDismiss = { tunnelSheetOpen = false },
                )
            }
        }

        HomeQuickTileId.BYPASS_METHOD -> {
            var bypassSheetOpen by remember { mutableStateOf(false) }
            QuickDropdownTile(
                icon = Icons.Rounded.Security,
                title = settings.bypassMethod.localizedShortTitle(),
                subtitle = "SNI: ${settings.customSni}",
                testTag = "quick_bypass_tile",
                isLocked = isLocked,
                onClick = { if (isLocked) onLockedAttempt() else bypassSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )
            if (bypassSheetOpen && !isLocked) {
                QuickOptionPickerDialog(
                    title = tr("Метод обхода блокировок", "Censorship bypass method"),
                    subtitle = tr("Активный SNI: ${settings.customSni}", "Active SNI: ${settings.customSni}"),
                    options = BypassMethod.entries.map { method ->
                        QuickSheetOption(
                            key = "bypass_${method.name}",
                            title = method.localizedTitle(),
                            subtitle = method.localizedSubtitle(),
                            selected = settings.bypassMethod == method,
                            testTag = "quick_bypass_option_${method.name}",
                            onClick = { onSelectBypassMethod(method) },
                        )
                    },
                    footerActionLabel = tr("Настроить SNI и профили браузеров…", "Configure SNI & browser profiles…"),
                    footerActionTestTag = "quick_bypass_more_option",
                    onFooterAction = onOpenBypassSettings,
                    onDismiss = { bypassSheetOpen = false },
                )
            }
        }

        HomeQuickTileId.SMART_DNS -> {
            var dnsSheetOpen by remember { mutableStateOf(false) }
            val autoVpnLabel = tr("Авто (сервер FPTN)", "Auto (FPTN server)")
            val manualSuffix = tr("вручную", "manual")
            val dnsSubtitle = remember(settings.dnsMode, settings.customDnsUrl, settings.dnsPrimary, settings.manualOverrides, autoVpnLabel, manualSuffix) {
                val base = when (settings.dnsMode) {
                    DnsMode.AUTO_VPN -> autoVpnLabel
                    DnsMode.SMART_DNS_DOH -> settings.customDnsUrl.removePrefix("https://").substringBefore("/").ifBlank { "Smart DNS" }
                    DnsMode.ADBLOCK_DNS -> "AdGuard DoH"
                    DnsMode.CUSTOM_IPV4 -> settings.dnsPrimary
                }
                if (ManualOverrideKey.DNS in settings.manualOverrides) "$base • $manualSuffix" else base
            }
            QuickDropdownTile(
                icon = Icons.Rounded.Dns,
                title = "DNS: ${settings.dnsMode.localizedShortTitle()}",
                subtitle = dnsSubtitle,
                testTag = "quick_smart_dns_tile",
                isLocked = isLocked,
                onClick = { if (isLocked) onLockedAttempt() else dnsSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )
            if (dnsSheetOpen && !isLocked) {
                val orderedSavedDns = remember(settings.savedDnsEntries) {
                    val custom = settings.savedDnsEntries.filter { !it.isBuiltIn }
                    val builtIn = settings.savedDnsEntries.filter { it.isBuiltIn }
                    custom + builtIn
                }
                val customBadge = tr("Свой", "Custom")
                QuickOptionPickerDialog(
                    title = tr("Режим DNS и Smart DNS", "DNS & Smart DNS mode"),
                    subtitle = tr("Выбор фиксируется вручную и не сбрасывается авто-пресетами", "Selection is locked manually and preserved across auto-presets"),
                    options = DnsMode.entries.map { mode ->
                        QuickSheetOption(
                            key = "dns_mode_${mode.name}",
                            title = mode.localizedShortTitle(),
                            subtitle = mode.localizedTitle(),
                            selected = settings.dnsMode == mode,
                            testTag = "quick_dns_option_${mode.name}",
                            onClick = { onSelectDnsMode(mode) },
                        )
                    },
                    secondarySectionTitle = tr(
                        "Сохранённые Smart DNS (все: ${orderedSavedDns.size})",
                        "Saved Smart DNS (total: ${orderedSavedDns.size})",
                    ),
                    secondaryOptions = orderedSavedDns.map { entry ->
                        val isEntrySelected = settings.dnsMode == DnsMode.SMART_DNS_DOH &&
                            settings.customDnsUrl.equals(entry.urlOrHost, ignoreCase = true)
                        QuickSheetOption(
                            key = "dns_entry_${entry.id}",
                            title = entry.localizedTitle(),
                            subtitle = entry.urlOrHost,
                            monospaceSubtitle = true,
                            selected = isEntrySelected,
                            badge = if (!entry.isBuiltIn) customBadge else null,
                            testTag = "quick_dns_entry_${entry.id}",
                            onClick = { onSelectDnsEntry(entry) },
                        )
                    },
                    enableSearch = orderedSavedDns.size > 6,
                    footerActionLabel = tr("Открыть полный каталог и добавить свой DNS…", "Open full catalog & add custom DNS…"),
                    footerActionTestTag = "quick_dns_manage_option",
                    onFooterAction = onOpenDnsSettings,
                    onDismiss = { dnsSheetOpen = false },
                )
            }
        }

        HomeQuickTileId.STALL_WATCHDOG -> {
            QuickToggleTile(
                icon = Icons.Rounded.MonitorHeart,
                title = tr("Сторож зависаний", "Stall watchdog"),
                subtitle = if (settings.enableStallWatchdog) {
                    tr(
                        "Пинг ${settings.watchdogProbeSec}с / Сброс ${settings.watchdogStallSec}с",
                        "Probe ${settings.watchdogProbeSec}s / Reset ${settings.watchdogStallSec}s",
                    )
                } else {
                    tr("Выключен", "Off")
                },
                checked = settings.enableStallWatchdog,
                testTag = "quick_watchdog_tile",
                isLocked = isLocked,
                showLiveBadge = showLiveBadge,
                onToggle = { if (isLocked) onLockedAttempt() else onToggleStallWatchdog(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.MTU -> {
            var mtuSheetOpen by remember { mutableStateOf(false) }
            QuickDropdownTile(
                icon = Icons.Rounded.Speed,
                title = tr("MTU туннеля", "Tunnel MTU"),
                subtitle = tr("${settings.tunMtu} байт", "${settings.tunMtu} bytes"),
                testTag = "quick_mtu_tile",
                isLocked = isLocked,
                onClick = { if (isLocked) onLockedAttempt() else mtuSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )
            if (mtuSheetOpen && !isLocked) {
                QuickOptionPickerDialog(
                    title = tr("Размер MTU туннеля", "Tunnel MTU size"),
                    subtitle = tr("Меньший MTU помогает при фрагментации пакетов в 4G/5G и под ТСПУ", "Lower MTU avoids packet fragmentation on 4G/5G and strict DPI"),
                    options = VpnSettings.MTU_PRESETS.map { mtuValue ->
                        val desc = when (mtuValue) {
                            1500 -> tr("Стандартный (Wi-Fi)", "Standard (Wi-Fi)")
                            1450 -> tr("Увеличенный (Wi-Fi)", "Extended (Wi-Fi)")
                            1400 -> tr("Сбалансированный (по умолчанию)", "Balanced (default)")
                            1360 -> tr("Против фрагментации ТСПУ", "Anti-fragmentation (4G/DPI)")
                            else -> tr("Минимальный безопасный", "Minimum safe")
                        }
                        QuickSheetOption(
                            key = "mtu_$mtuValue",
                            title = tr("MTU $mtuValue байт", "MTU $mtuValue bytes"),
                            subtitle = desc,
                            selected = settings.tunMtu == mtuValue,
                            testTag = "quick_mtu_option_$mtuValue",
                            onClick = { onSelectMtu(mtuValue) },
                        )
                    },
                    onDismiss = { mtuSheetOpen = false },
                )
            }
        }

        HomeQuickTileId.ROOT_STEALTH -> {
            QuickToggleTile(
                icon = Icons.Rounded.AdminPanelSettings,
                title = "Root Stealth",
                subtitle = if (settings.useRootMode) {
                    tr("${settings.rootTunInterfaceName} (скрыт)", "${settings.rootTunInterfaceName} (hidden)")
                } else {
                    tr("Выключен", "Off")
                },
                checked = settings.useRootMode,
                testTag = "quick_root_stealth_tile",
                isLocked = isLocked,
                showLiveBadge = false,
                onToggle = { if (isLocked) onLockedAttempt() else onToggleRootMode(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.SMART_RF -> {
            QuickToggleTile(
                icon = Icons.Rounded.Public,
                title = tr("Обход: ${settings.bypassRegion.localizedShortTitle()}", "Bypass: ${settings.bypassRegion.localizedShortTitle()}"),
                subtitle = if (settings.smartRfRouting) tr("Регион напрямую", "Region direct") else tr("Весь трафик", "All traffic"),
                checked = settings.smartRfRouting,
                testTag = "quick_smart_rf_tile",
                isLocked = isLocked,
                showLiveBadge = false,
                onToggle = { if (isLocked) onLockedAttempt() else onToggleSmartRf(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.AUTO_SWITCH_PRESET -> {
            QuickToggleTile(
                icon = Icons.Rounded.SwapHoriz,
                title = tr("Авто-пресет", "Auto preset"),
                subtitle = if (settings.autoSwitchPresetsByNetwork) "Wi-Fi ↔ 4G/5G" else tr("Вручную", "Manual"),
                checked = settings.autoSwitchPresetsByNetwork,
                testTag = "quick_auto_preset_tile",
                isLocked = isLocked,
                showLiveBadge = false,
                onToggle = { if (isLocked) onLockedAttempt() else onToggleAutoSwitchPreset(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.SPLIT_APPS -> {
            QuickNavTile(
                icon = Icons.Rounded.Apps,
                title = tr("Приложения", "Apps"),
                subtitle = settings.splitTunnelMode.localizedTitle(),
                testTag = "quick_tunneling_tile",
                isLocked = isLocked,
                showLiveBadge = false,
                onClick = { if (isLocked) onLockedAttempt() else onOpenTunnelingSettings() },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.HAPTICS -> {
            QuickToggleTile(
                icon = Icons.Rounded.Vibration,
                title = tr("Виброотклик", "Haptics"),
                subtitle = if (settings.enableHaptics) tr("Включён", "On") else tr("Выключен", "Off"),
                checked = settings.enableHaptics,
                testTag = "quick_haptics_tile",
                isLocked = isLocked,
                showLiveBadge = showLiveBadge,
                onToggle = { onToggleHaptics(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.LOGS -> {
            QuickNavTile(
                icon = Icons.Rounded.BugReport,
                title = tr("Журнал", "Logs"),
                subtitle = tr("Логи и статус", "Logs & status"),
                testTag = "quick_logs_tile",
                isLocked = false,
                showLiveBadge = showLiveBadge,
                onClick = onOpenLogs,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.BYPASS_REGION -> {
            var regionSheetOpen by remember { mutableStateOf(false) }
            QuickDropdownTile(
                icon = Icons.Rounded.Public,
                title = tr("Регион обхода", "Bypass region"),
                subtitle = settings.bypassRegion.localizedShortTitle(),
                testTag = "quick_region_tile",
                isLocked = isLocked,
                onClick = { if (isLocked) onLockedAttempt() else regionSheetOpen = true },
                modifier = Modifier.fillMaxWidth(),
            )
            if (regionSheetOpen && !isLocked) {
                QuickOptionPickerDialog(
                    title = tr("Регион прямого обхода", "Direct bypass region"),
                    subtitle = tr("Местные банки, сервисы и госсайты идут напрямую без VPN", "Local banks, services, and gov sites connect directly"),
                    options = BypassRegion.selectableEntries.map { region ->
                        QuickSheetOption(
                            key = "region_${region.name}",
                            title = region.localizedTitle(),
                            subtitle = region.localizedSubtitle(),
                            selected = settings.bypassRegion == region,
                            badge = if (region.requiresRoot && !isRootAvailable) "Root" else null,
                            testTag = "quick_region_option_${region.name}",
                            onClick = { onSelectBypassRegion(region) },
                        )
                    },
                    onDismiss = { regionSheetOpen = false },
                )
            }
        }

        HomeQuickTileId.MANUAL_OVERRIDES -> {
            val count = settings.manualOverrides.size
            QuickNavTile(
                icon = Icons.Rounded.PushPin,
                title = tr("Ручные фиксации", "Manual locks"),
                subtitle = if (count > 0) tr("Зафиксировано: $count", "Locked: $count") else tr("Всё на автомате", "All automatic"),
                testTag = "quick_manual_overrides_tile",
                isLocked = false,
                showLiveBadge = showLiveBadge,
                onClick = onOpenManualOverrides,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        HomeQuickTileId.AUTO_FAILOVER -> {
            val active = settings.autoFailoverServer && settings.autoRotateSniOnFail
            QuickToggleTile(
                icon = Icons.Rounded.Shield,
                title = tr("Авто-резерв", "Auto failover"),
                subtitle = if (active) tr("Сервер + SNI", "Server + SNI") else tr("Вручную", "Manual"),
                checked = active,
                testTag = "quick_auto_failover_tile",
                isLocked = isLocked,
                showLiveBadge = showLiveBadge,
                onToggle = { onToggleAutoFailover(it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun QuickAddPlaceholderTile(
    availableCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .testTag("quick_add_module_tile")
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column {
                Text(
                    text = tr("Добавить модуль", "Add tile"),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tr("Ещё доступно: $availableCount", "Available: $availableCount"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTilesCustomizerSheet(
    activeTiles: List<HomeQuickTileId>,
    isRootAvailable: Boolean,
    onAddTile: (HomeQuickTileId) -> Unit,
    onRemoveTile: (HomeQuickTileId) -> Unit,
    onMoveTile: (HomeQuickTileId, Int) -> Unit,
    onResetDefaults: () -> Unit,
    onVerifyRootAccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val availableTiles = remember(activeTiles, isRootAvailable) {
        HomeQuickTileId.entries.filter { tile ->
            (!tile.requiresRoot || isRootAvailable) && tile !in activeTiles
        }
    }
    val hasSuBinary = remember(isRootAvailable) {
        !isRootAvailable && RootShellManager.hasSuBinaryOnDevice()
    }

    val hostView = LocalView.current
    val density = LocalDensity.current
    val hostNavBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val rootSystemNavBottomDp = with(density) {
        (ViewCompat.getRootWindowInsets(hostView)
            ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
            ?.bottom ?: 0).toDp()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val dialogView = LocalView.current
        val dialogNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val dialogRootNavBottomDp = with(density) {
            (ViewCompat.getRootWindowInsets(dialogView)
                ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
                ?.bottom ?: 0).toDp()
        }
        val safeNavBottom = maxOf(dialogNavBottom, hostNavBarBottom, rootSystemNavBottomDp, dialogRootNavBottomDp)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.48f))
                .statusBarsPadding()
                .imePadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            val maxSheetHeight = (maxHeight * 0.86f).coerceAtLeast(260.dp)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .heightIn(max = maxSheetHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 14.dp, bottom = safeNavBottom + 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = tr("Модули главного экрана", "Home screen quick tiles"),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = tr("Добавляйте, убирайте и меняйте порядок быстрых плиток", "Add, remove, and reorder quick control tiles"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onResetDefaults,
                    modifier = Modifier.testTag("quick_tiles_reset_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Restore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(tr("Сброс", "Reset"))
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Text(
                        text = tr("Активные (${activeTiles.size})", "Active (${activeTiles.size})"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (activeTiles.isEmpty()) {
                    item {
                        Text(
                            text = tr("Список пуст — добавьте нужные модули ниже.", "Empty — add tiles from the list below."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    itemsIndexed(activeTiles, key = { _, item -> "active_${item.name}" }) { index, tile ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tile.localizedTitle(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = tile.localizedDescription(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveTile(tile, -1) },
                                    enabled = index > 0,
                                    modifier = Modifier.testTag("quick_tile_up_${tile.name}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowUp,
                                        contentDescription = tr("Переместить выше", "Move up"),
                                    )
                                }
                                IconButton(
                                    onClick = { onMoveTile(tile, 1) },
                                    enabled = index < activeTiles.lastIndex,
                                    modifier = Modifier.testTag("quick_tile_down_${tile.name}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = tr("Переместить ниже", "Move down"),
                                    )
                                }
                                IconButton(
                                    onClick = { onRemoveTile(tile) },
                                    modifier = Modifier.testTag("quick_tile_remove_${tile.name}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = tr("Убрать модуль", "Remove tile"),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                if (availableTiles.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = tr("Доступные для добавления (${availableTiles.size})", "Available to add (${availableTiles.size})"),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(availableTiles, key = { "avail_${it.name}" }) { tile ->
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surfaceContainerLowest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAddTile(tile) },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tile.localizedTitle(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = tile.localizedDescription(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                FilledTonalIconButton(
                                    onClick = { onAddTile(tile) },
                                    modifier = Modifier.testTag("quick_tile_add_${tile.name}"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Add,
                                        contentDescription = tr("Добавить модуль", "Add tile"),
                                    )
                                }
                            }
                        }
                    }
                }

                if (hasSuBinary) {
                    item(key = "restore_root_tile_card") {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.AdminPanelSettings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tr("Плитка Root Stealth скрыта", "Root Stealth tile is hidden"),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                    Text(
                                        text = tr(
                                            "Если вы снова выдали права su в Magisk/KernelSU — нажмите проверить, перезапуск не нужен.",
                                            "If you granted su in Magisk/KernelSU, tap Verify — no restart needed.",
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                                    )
                                }
                                FilledTonalButton(
                                    onClick = onVerifyRootAccess,
                                    modifier = Modifier.testTag("quick_tiles_restore_root_button"),
                                ) {
                                    Text(tr("Проверить", "Verify"))
                                }
                            }
                        }
                    }
                }
            }

            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("quick_tiles_done_button"),
            ) {
                Text(tr("Готово", "Done"))
            }
                }
            }
        }
    }
}

@Composable
private fun LiveBadgePill() {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Text(
            text = "LIVE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun QuickDropdownTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    isLocked: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .testTag(testTag)
            .clickable(onClick = onClick)
            .alpha(if (isLocked) 0.56f else 1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Icon(
                    imageVector = if (isLocked) Icons.Rounded.Lock else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (isLocked) tr("Заблокировано при активном VPN", "Locked while VPN is active") else null,
                    tint = if (isLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(if (isLocked) 16.dp else 20.dp),
                )
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QuickToggleTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    isLocked: Boolean = false,
    showLiveBadge: Boolean = false,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .testTag(testTag)
            .clickable { onToggle(!checked) }
            .alpha(if (isLocked) 0.56f else 1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (checked) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = when {
                            isLocked -> MaterialTheme.colorScheme.onSurfaceVariant
                            checked -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    if (showLiveBadge) {
                        LiveBadgePill()
                    }
                }
                if (isLocked) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = tr("Заблокировано при активном VPN", "Locked while VPN is active"),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Switch(
                        checked = checked,
                        onCheckedChange = onToggle,
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QuickNavTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    isLocked: Boolean = false,
    showLiveBadge: Boolean = false,
    highlighted: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .testTag(testTag)
            .clickable(onClick = onClick)
            .alpha(if (isLocked) 0.56f else 1f),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) {
                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isLocked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                when {
                    isLocked -> Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = tr("Заблокировано при активном VPN", "Locked while VPN is active"),
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    showLiveBadge -> LiveBadgePill()
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun EmptyTokenStateCard(
    onAddToken: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("home_empty_token_card"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.home_no_tokens_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_no_tokens_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onAddToken,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("home_add_token_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.home_add_token_action))
            }
        }
    }
}
