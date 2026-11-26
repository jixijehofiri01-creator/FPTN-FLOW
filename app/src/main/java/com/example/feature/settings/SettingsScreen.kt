package com.example.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.rounded.SystemUpdate
import com.example.BuildConfig
import com.example.R
import com.example.core.config.FptnConstants
import com.example.core.haptics.HapticEngine
import com.example.core.logging.AppLogStore
import com.example.data.remote.AppUpdateStatus
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.localizedName
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedTitle
import com.example.domain.dns.DnsEndpointParser
import com.example.domain.model.AppLanguage
import com.example.domain.model.DnsMode
import com.example.feature.shared.FptnUiState
import com.example.vpn.root.RootShellManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    bottomNavInset: androidx.compose.ui.unit.Dp,
    onOpenTokens: () -> Unit,
    onOpenPresets: () -> Unit,
    onOpenManualOverrides: () -> Unit,
    onOpenBypass: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenTunneling: () -> Unit,
    onOpenRoot: () -> Unit,
    onOpenAdditional: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenBackup: () -> Unit,
    onRefreshProjectMeta: () -> Unit,
    appUpdateStatus: AppUpdateStatus = AppUpdateStatus.Idle,
    onCheckForUpdates: () -> Unit = {},
    onVerifyRootLive: () -> Unit = {},
    onSelectAppLanguage: (AppLanguage) -> Unit = {},
    onShowLockedMessage: () -> Unit,
    onConfirmExit: () -> Unit,
    applyNavBarsInset: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val isVpnActive = uiState.isVpnActive
    var sponsorsExpanded by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    val arrowRotationState = animateFloatAsState(
        targetValue = if (sponsorsExpanded) 180f else 0f,
        label = "sponsorsArrow",
    )
    val hasSuBinary by produceState(initialValue = false, key1 = uiState.isRootAvailable) {
        value = if (uiState.isRootAvailable) {
            true
        } else {
            withContext(Dispatchers.IO) { RootShellManager.hasSuBinaryOnDevice() }
        }
    }

    val dnsAutoLabel = stringResource(R.string.settings_dns_auto)
    val dnsAdguardLabel = stringResource(R.string.settings_dns_adguard)
    val activeDnsSummary = remember(
        uiState.settings.dnsMode,
        uiState.settings.customDnsUrl,
        uiState.settings.dnsPrimary,
        dnsAutoLabel,
        dnsAdguardLabel,
    ) {
        when (uiState.settings.dnsMode) {
            DnsMode.AUTO_VPN -> dnsAutoLabel
            DnsMode.SMART_DNS_DOH -> {
                val host = DnsEndpointParser.parse(uiState.settings.customDnsUrl).getOrNull()?.host
                    ?: uiState.settings.customDnsUrl
                "Smart DNS • $host"
            }
            DnsMode.ADBLOCK_DNS -> dnsAdguardLabel
            DnsMode.CUSTOM_IPV4 -> "IPv4 ${uiState.settings.dnsPrimary}"
        }
    }

    fun openOrRejectIfLocked(locked: Boolean, action: () -> Unit) {
        if (locked) {
            hapticEngine.performLockedReject(view)
            onShowLockedMessage()
        } else {
            hapticEngine.performNavigationClick(view)
            action()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .let { m -> if (applyNavBarsInset) m.navigationBarsPadding() else m },
    ) {
        val isTwoColumn = maxWidth >= 800.dp

        val connectionAndNetworkColumn: @Composable () -> Unit = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            SettingsSectionHeader(stringResource(R.string.settings_section_connection))
            SettingsCardGroup {
                SettingsRowItem(
                    icon = Icons.Rounded.VpnKey,
                    title = stringResource(R.string.settings_tokens_title),
                    subtitle = "${stringResource(R.string.settings_tokens_subtitle)} (${uiState.profiles.size})",
                    testTag = "settings_tokens_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenTokens) },
                )
                SettingsDivider()
                val activePresetLabel = uiState.settings.networkPresets
                    .firstOrNull { it.id == uiState.settings.activePresetId }?.localizedName()
                val presetsCountLabel = stringResource(R.string.settings_presets_count, uiState.settings.networkPresets.size)
                val presetsAutoSuffix = stringResource(R.string.settings_presets_auto_suffix)
                SettingsRowItem(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_presets_title),
                    subtitle = buildString {
                        append(activePresetLabel ?: presetsCountLabel)
                        if (uiState.settings.autoSwitchPresetsByNetwork) {
                            append(" • $presetsAutoSuffix")
                        }
                    },
                    testTag = "settings_presets_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenPresets) },
                )
                SettingsDivider()
                val overridesCount = uiState.settings.manualOverrides.size
                SettingsRowItem(
                    icon = Icons.Rounded.RestartAlt,
                    title = stringResource(R.string.settings_overrides_title),
                    subtitle = if (overridesCount > 0) {
                        stringResource(R.string.settings_overrides_active, overridesCount)
                    } else {
                        stringResource(R.string.settings_overrides_auto)
                    },
                    testTag = "settings_overrides_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenManualOverrides) },
                )
                SettingsDivider()
                SettingsRowItem(
                    icon = Icons.Rounded.Security,
                    title = stringResource(R.string.settings_bypass_title),
                    subtitle = "${uiState.settings.bypassMethod.localizedShortTitle()} • SNI ${uiState.settings.customSni}",
                    testTag = "settings_bypass_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenBypass) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_network))
            SettingsCardGroup {
                SettingsRowItem(
                    icon = Icons.Rounded.Dns,
                    title = stringResource(R.string.settings_dns_title),
                    subtitle = activeDnsSummary,
                    testTag = "settings_dns_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenDns) },
                )
                SettingsDivider()
                val customRulesCount = uiState.settings.customDirectDomains.size + uiState.settings.customVpnDomains.size
                val domainsCountLabel = stringResource(R.string.settings_domains_count, customRulesCount)
                val regionShortTitle = uiState.settings.bypassRegion.localizedShortTitle()
                val splitModeTitle = uiState.settings.splitTunnelMode.localizedTitle()
                SettingsRowItem(
                    icon = Icons.AutoMirrored.Rounded.AltRoute,
                    title = stringResource(R.string.settings_tunneling_title),
                    subtitle = buildString {
                        append(regionShortTitle)
                        append(" • ")
                        append(splitModeTitle)
                        if (customRulesCount > 0) append(" • $domainsCountLabel")
                    },
                    testTag = "settings_tunneling_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenTunneling) },
                )
                if (uiState.isRootAvailable) {
                    SettingsDivider()
                    SettingsRowItem(
                        icon = Icons.Rounded.AdminPanelSettings,
                        title = stringResource(R.string.settings_root_title),
                        subtitle = if (uiState.settings.useRootMode) {
                            stringResource(R.string.settings_root_active_subtitle)
                        } else {
                            stringResource(R.string.settings_root_subtitle)
                        },
                        testTag = "settings_root_row",
                        isLocked = false,
                        onClick = { openOrRejectIfLocked(false, onOpenRoot) },
                    )
                } else if (hasSuBinary) {
                    SettingsDivider()
                    SettingsRowItem(
                        icon = Icons.Rounded.AdminPanelSettings,
                        title = stringResource(R.string.settings_restore_root_title),
                        subtitle = stringResource(R.string.settings_restore_root_subtitle),
                        testTag = "settings_restore_root_row",
                        isLocked = false,
                        onClick = {
                            hapticEngine.performSuccess(view)
                            onVerifyRootLive()
                        },
                    )
                }
            }
            }
        }

        val stabilityAndSystemColumn: @Composable () -> Unit = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            SettingsSectionHeader(stringResource(R.string.settings_section_stability))
            SettingsCardGroup {
                SettingsRowItem(
                    icon = Icons.Rounded.MonitorHeart,
                    title = stringResource(R.string.settings_additional_title),
                    subtitle = if (uiState.settings.enableStallWatchdog) {
                        stringResource(
                            R.string.settings_watchdog_on_summary,
                            uiState.settings.watchdogProbeSec,
                            uiState.settings.watchdogStallSec,
                            uiState.settings.autoPingIntervalSec,
                        )
                    } else {
                        stringResource(
                            R.string.settings_watchdog_off_summary,
                            uiState.settings.autoPingIntervalSec,
                        )
                    },
                    testTag = "settings_additional_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenAdditional) },
                )
                SettingsDivider()
                val hapticsLabel = stringResource(
                    if (uiState.settings.enableHaptics) R.string.settings_appearance_haptics_on else R.string.settings_appearance_haptics_off,
                )
                val waveLabel = stringResource(
                    if (uiState.settings.enableWaterWaveEffect) R.string.settings_appearance_wave_on else R.string.settings_appearance_wave_off,
                )
                SettingsRowItem(
                    icon = Icons.Rounded.Palette,
                    title = stringResource(R.string.settings_appearance_title),
                    subtitle = "${uiState.settings.themeMode.localizedTitle()} • $hapticsLabel • $waveLabel",
                    testTag = "settings_appearance_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenAppearance) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_system))
            SettingsCardGroup {
                SettingsRowItem(
                    icon = Icons.Rounded.Shield,
                    title = stringResource(R.string.settings_permissions_title),
                    subtitle = stringResource(R.string.settings_permissions_subtitle),
                    testTag = "settings_permissions_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenPermissions) },
                )
                SettingsDivider()
                LogsSettingsRowItem(
                    onClick = { openOrRejectIfLocked(false, onOpenLogs) },
                )
                SettingsDivider()
                SettingsRowItem(
                    icon = Icons.Rounded.Backup,
                    title = stringResource(R.string.settings_backup_title),
                    subtitle = stringResource(R.string.settings_backup_subtitle),
                    testTag = "settings_backup_row",
                    isLocked = false,
                    onClick = { openOrRejectIfLocked(false, onOpenBackup) },
                )
                SettingsDivider()
                val updateSubtitle = when (appUpdateStatus) {
                    is AppUpdateStatus.Checking -> stringResource(R.string.settings_updates_checking_subtitle)
                    is AppUpdateStatus.UpToDate -> stringResource(R.string.settings_updates_up_to_date_subtitle)
                    is AppUpdateStatus.UpdateAvailable -> stringResource(
                        R.string.settings_updates_available_subtitle,
                        appUpdateStatus.info.latestVersion,
                    )
                    is AppUpdateStatus.NoReleasesYet -> stringResource(R.string.settings_updates_no_releases_subtitle)
                    is AppUpdateStatus.Error -> stringResource(R.string.settings_updates_error_subtitle)
                    is AppUpdateStatus.Idle -> stringResource(R.string.settings_updates_idle_subtitle)
                }
                SettingsRowItem(
                    icon = Icons.Rounded.SystemUpdate,
                    title = stringResource(R.string.settings_updates_title, BuildConfig.VERSION_NAME),
                    subtitle = updateSubtitle,
                    testTag = "settings_updates_row",
                    isLocked = false,
                    onClick = {
                        hapticEngine.performNavigationClick(view)
                        if (appUpdateStatus is AppUpdateStatus.UpdateAvailable) {
                            showUpdateDialog = true
                        } else {
                            onCheckForUpdates()
                        }
                    },
                )
                SettingsDivider()

                // Collapsible Sponsors & Project Info inside System section
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_sponsors_header")
                        .clickable {
                            hapticEngine.performNavigationClick(view)
                            sponsorsExpanded = !sponsorsExpanded
                            if (sponsorsExpanded && !uiState.projectMeta.isLiveLoaded) {
                                onRefreshProjectMeta()
                            }
                        }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_sponsors_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_sponsors_count, uiState.projectMeta.sponsors.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer { rotationZ = arrowRotationState.value },
                    )
                }

                AnimatedVisibility(visible = sponsorsExpanded) {
                    val meta = uiState.projectMeta
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 18.dp, end = 18.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = meta.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    hapticEngine.performNavigationClick(view)
                                    onRefreshProjectMeta()
                                },
                                enabled = !uiState.isLoadingProjectMeta,
                                modifier = Modifier.testTag("sponsors_refresh_button"),
                            ) {
                                if (uiState.isLoadingProjectMeta) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = stringResource(R.string.settings_sponsors_refresh_cd),
                                    )
                                }
                            }
                        }

                        if (meta.githubStars != null || meta.latestRelease != null) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                meta.githubStars?.let { stars ->
                                    AssistChip(
                                        onClick = {},
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Rounded.Star,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        },
                                        label = { Text("GitHub: $stars") },
                                    )
                                }
                                meta.latestRelease?.let { release ->
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(stringResource(R.string.settings_sponsors_release, release)) },
                                    )
                                }
                            }
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            meta.sponsors.forEach { sponsor ->
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = !sponsor.url.isNullOrBlank()) {
                                            hapticEngine.performNavigationClick(view)
                                            sponsor.url?.let { url ->
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                                }
                                            }
                                        },
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = sponsor.name,
                                                style = MaterialTheme.typography.titleSmall,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = sponsor.tier,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (!sponsor.url.isNullOrBlank()) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(FptnConstants.FORK_GITHUB_URL)),
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("sponsors_fork_github_button"),
                            ) {
                                Text("FPTN Flow GitHub")
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(FptnConstants.OFFICIAL_SITE_URL)),
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("sponsors_site_button"),
                            ) {
                                Text("fptn.org")
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse(FptnConstants.OFFICIAL_ANDROID_GITHUB_URL),
                                            ),
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("sponsors_github_button"),
                            ) {
                                Text("Original FPTN")
                            }
                            TextButton(
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(FptnConstants.OFFICIAL_TELEGRAM_CHANNEL_URL)),
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("sponsors_telegram_button"),
                            ) {
                                Text("Telegram")
                            }
                        }
                    }
                }

                SettingsDivider()
                SettingsRowItem(
                    icon = Icons.AutoMirrored.Rounded.ExitToApp,
                    title = stringResource(R.string.settings_exit_title),
                    subtitle = stringResource(R.string.settings_exit_subtitle),
                    isDestructive = true,
                    testTag = "settings_exit_row",
                    onClick = {
                        hapticEngine.performDestructiveAction(view)
                        showExitDialog = true
                    },
                )
            }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = if (isTwoColumn) 28.dp else 20.dp,
                    vertical = 16.dp,
                )
                .padding(bottom = bottomNavInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = if (isTwoColumn) 1040.dp else 600.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    CompactLanguageToggle(
                        isRussianActive = LocalIsRussian.current,
                        onSelectLanguage = { lang ->
                            hapticEngine.performSegmentTick(view)
                            onSelectAppLanguage(lang)
                        },
                    )
                }

                // VPN-active info banner (live settings trigger smooth reconnect)
                AnimatedVisibility(visible = isVpnActive) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_vpn_locked_banner"),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = stringResource(R.string.settings_vpn_active_banner),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                // Subtle update banner when a newer GitHub Release is detected
                val availableUpdate = (appUpdateStatus as? AppUpdateStatus.UpdateAvailable)?.info
                AnimatedVisibility(visible = availableUpdate != null) {
                    if (availableUpdate != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_update_available_banner")
                                .clickable {
                                    hapticEngine.performNavigationClick(view)
                                    showUpdateDialog = true
                                },
                            shape = MaterialTheme.shapes.large,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SystemUpdate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(
                                            R.string.settings_updates_banner_title,
                                            availableUpdate.latestVersion,
                                        ),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                    Text(
                                        text = stringResource(R.string.settings_updates_banner_subtitle),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                                    )
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                if (isTwoColumn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            connectionAndNetworkColumn()
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            stabilityAndSystemColumn()
                        }
                    }
                } else {
                    connectionAndNetworkColumn()
                    stabilityAndSystemColumn()
                }

                Spacer(modifier = Modifier.height(56.dp))
            }
        }
    }

    val updateInfo = (appUpdateStatus as? AppUpdateStatus.UpdateAvailable)?.info
    if (showUpdateDialog && updateInfo != null) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text(stringResource(R.string.settings_updates_dialog_title, updateInfo.latestVersion)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.settings_updates_dialog_current,
                            updateInfo.currentVersion,
                            updateInfo.latestVersion,
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (updateInfo.releaseNotes.isNotBlank()) {
                        Text(
                            text = updateInfo.releaseNotes.take(600),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                val targetUrl = updateInfo.recommendedAsset?.downloadUrl ?: updateInfo.releaseUrl
                val confirmLabel = if (updateInfo.recommendedAsset != null) {
                    stringResource(
                        R.string.settings_updates_download_abi,
                        updateInfo.devicePrimaryAbi ?: "APK",
                    )
                } else {
                    stringResource(R.string.settings_updates_open_releases)
                }
                TextButton(
                    onClick = {
                        hapticEngine.performConfirm(view)
                        showUpdateDialog = false
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
                        }
                    },
                    modifier = Modifier.testTag("update_download_confirm_button"),
                ) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (updateInfo.recommendedAsset != null) {
                        TextButton(
                            onClick = {
                                hapticEngine.performNavigationClick(view)
                                showUpdateDialog = false
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.releaseUrl)),
                                    )
                                }
                            },
                        ) {
                            Text("GitHub")
                        }
                    }
                    TextButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            showUpdateDialog = false
                        },
                        modifier = Modifier.testTag("update_dialog_cancel_button"),
                    ) {
                        Text(stringResource(R.string.tokens_cancel_button))
                    }
                }
            },
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text(stringResource(R.string.settings_exit_dialog_title)) },
            text = {
                Text(stringResource(R.string.settings_exit_dialog_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hapticEngine.performDestructiveAction(view)
                        showExitDialog = false
                        onConfirmExit()
                    },
                    modifier = Modifier.testTag("exit_confirm_button"),
                ) {
                    Text(stringResource(R.string.settings_exit_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        hapticEngine.performNavigationClick(view)
                        showExitDialog = false
                    },
                    modifier = Modifier.testTag("exit_cancel_button"),
                ) {
                    Text(stringResource(R.string.tokens_cancel_button))
                }
            },
        )
    }
}

@Composable
private fun CompactLanguageToggle(
    isRussianActive: Boolean,
    onSelectLanguage: (AppLanguage) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.testTag("settings_language_switcher"),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LanguageSegmentPill(
                label = "RU",
                selected = isRussianActive,
                testTag = "settings_lang_ru",
                onClick = { onSelectLanguage(AppLanguage.RU) },
            )
            LanguageSegmentPill(
                label = "EN",
                selected = !isRussianActive,
                testTag = "settings_lang_en",
                onClick = { onSelectLanguage(AppLanguage.EN) },
            )
        }
    }
}

@Composable
private fun LanguageSegmentPill(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "langPillBg",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "langPillText",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 6.dp, top = 4.dp),
    )
}

@Composable
private fun SettingsRowItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    testTag: String,
    isDestructive: Boolean = false,
    isLocked: Boolean = false,
    onClick: () -> Unit,
) {
    val accent = when {
        isLocked -> MaterialTheme.colorScheme.onSurfaceVariant
        isDestructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickable(onClick = onClick)
            .alpha(if (isLocked) 0.58f else 1f)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(24.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isDestructive && !isLocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = if (isLocked) Icons.Rounded.Lock else Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = if (isLocked) stringResource(R.string.settings_locked_cd) else null,
            tint = if (isLocked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (isLocked) 18.dp else 22.dp),
        )
    }
}

@Composable
private fun LogsSettingsRowItem(
    onClick: () -> Unit,
) {
    val logs by AppLogStore.logs.collectAsStateWithLifecycle()
    SettingsRowItem(
        icon = Icons.Rounded.BugReport,
        title = stringResource(R.string.settings_logs_title),
        subtitle = "${stringResource(R.string.settings_logs_subtitle)} (${logs.size})",
        testTag = "settings_logs_row",
        isLocked = false,
        onClick = onClick,
    )
}

