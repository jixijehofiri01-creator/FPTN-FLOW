package com.example.feature.routing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.BypassRegion
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.VpnSettings
import com.example.domain.routing.ProtectedRuApps
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.CachedAppIcon
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedSubtitle
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.feature.shared.FptnUiState

private enum class AppListFilter {
    ALL,
    SELECTED,
    PROTECTED_RU,
}

private enum class DomainListTab {
    DIRECT,
    VPN,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TunnelingSettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onLoadApps: () -> Unit,
    onSelectBypassRegion: (BypassRegion) -> Unit,
    onAddCustomDirectDomain: (String) -> Unit,
    onRemoveCustomDirectDomain: (String) -> Unit,
    onAddCustomVpnDomain: (String) -> Unit,
    onRemoveCustomVpnDomain: (String) -> Unit,
    onToggleUnlockProtectedRuWithRoot: (Boolean) -> Unit,
    onSelectSplitMode: (SplitTunnelMode) -> Unit,
    onTogglePackage: (String) -> Unit,
    onAddManualPackage: (String) -> Unit,
    onToggleIpv6: (Boolean) -> Unit,
    onSelectMtu: (Int) -> Unit,
    onSelectQuickSni: (String) -> Unit = {},
    onPickRandomSni: (regionOnly: Boolean) -> Unit = {},
    onVerifyRootLive: () -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = uiState.settings
    // RU protection is permanently locked even on rooted devices to protect FPTN servers
    val ruUnlocked = false

    var domainTab by remember { mutableStateOf(DomainListTab.DIRECT) }
    var domainInput by remember { mutableStateOf("") }

    var searchQuery by remember { mutableStateOf("") }
    var manualPackageInput by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf(AppListFilter.ALL) }
    var protectedListExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onLoadApps()
    }

    val protectedRuleCount = remember { ProtectedRuApps.getRuleCount(context) }
    val detectedProtectedOnDevice = remember(uiState.installedApps) {
        uiState.installedApps.filter { it.isMandatoryRuBypass }
    }

    val filteredApps = remember(uiState.installedApps, searchQuery, appFilter, settings.selectedPackages) {
        val q = searchQuery.trim()
        uiState.installedApps.filter { app ->
            val matchesFilter = when (appFilter) {
                AppListFilter.ALL -> true
                AppListFilter.SELECTED -> app.packageName in settings.selectedPackages
                AppListFilter.PROTECTED_RU -> app.isMandatoryRuBypass
            }
            val matchesSearch = q.isBlank() ||
                app.appName.contains(q, ignoreCase = true) ||
                app.packageName.contains(q, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_tunneling_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("tunneling_back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // Section 1: Regional bypass + MTU + IPv6
            item {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = tr("Умный обход региона и туннель", "Regional bypass & tunnel"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    SettingsCardGroup {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = tr("Регион прямого доступа (без VPN)", "Direct-access region (bypass VPN)"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = settings.bypassRegion.localizedSubtitle(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val visibleRegions = BypassRegion.selectableEntries
                                visibleRegions.forEach { region ->
                                    val label = region.localizedShortTitle()
                                    FilterChip(
                                        selected = BypassRegion.sanitize(settings.bypassRegion) == region,
                                        onClick = {
                                            hapticEngine.performToggle(view, enabled = true)
                                            onSelectBypassRegion(region)
                                        },
                                        label = { Text(label) },
                                        modifier = Modifier.testTag("bypass_region_${region.name}"),
                                    )
                                }
                            }
                            run {
                                val regionInfo = BypassRegion.sanitize(settings.bypassRegion)
                                val tldsPreview = regionInfo.tlds.filterNot { it.contains("xn--") }.joinToString(", ")
                                Text(
                                    text = tr(
                                        "Зоны напрямую: $tldsPreview + региональный GeoIP и ${regionInfo.directDomainRoots.size} сервисов",
                                        "Direct zones: $tldsPreview + regional GeoIP and ${regionInfo.directDomainRoots.size} services",
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (regionInfo.recommendedSnis.isNotEmpty()) {
                                    Text(
                                        text = tr(
                                            "Приоритетные SNI региона (нажмите, чтобы применить):",
                                            "Priority regional SNIs (tap to apply):",
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        regionInfo.recommendedSnis.take(8).forEach { sni ->
                                            FilterChip(
                                                selected = settings.customSni.equals(sni, ignoreCase = true),
                                                onClick = {
                                                    hapticEngine.performConfirm(view)
                                                    onSelectQuickSni(sni)
                                                },
                                                label = {
                                                    Text(
                                                        text = sni,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                                    )
                                                },
                                            )
                                        }
                                        AssistChip(
                                            onClick = {
                                                hapticEngine.performSuccess(view)
                                                onPickRandomSni(true)
                                            },
                                            leadingIcon = {
                                                Icon(Icons.Rounded.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
                                            },
                                            label = { Text(tr("Случайный SNI", "Random SNI")) },
                                            modifier = Modifier.testTag("tunneling_random_region_sni_chip"),
                                        )
                                    }
                                }
                            }
                            if (!ruUnlocked) {
                                Text(
                                    text = tr(
                                        "Полное отключение регионального обхода заблокировано для защиты серверов FPTN.",
                                        "Disabling regional RU protection is locked to safeguard FPTN servers.",
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        SettingsDivider()

                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                tr("MTU туннеля: ${settings.tunMtu} байт", "Tunnel MTU: ${settings.tunMtu} bytes"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = tr(
                                    "Снизьте до 1420 или 1360 при мобильной сети 4G/5G или жёсткой фрагментации ТСПУ.",
                                    "Lower to 1420 or 1360 on 4G/5G cellular networks or strict DPI fragmentation.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                VpnSettings.MTU_PRESETS.forEach { mtuValue ->
                                    FilterChip(
                                        selected = settings.tunMtu == mtuValue,
                                        onClick = {
                                            hapticEngine.performToggle(view, enabled = true)
                                            onSelectMtu(mtuValue)
                                        },
                                        label = { Text("$mtuValue") },
                                        modifier = Modifier.testTag("mtu_chip_$mtuValue"),
                                    )
                                }
                            }
                        }

                        SettingsDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    tr("Поддержка IPv6 в туннеле", "Tunnel IPv6 support"),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = tr(
                                        "Отключайте при утечках IPv6 на мобильных операторах",
                                        "Disable to prevent IPv6 leaks on mobile carriers",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = settings.allowIpv6,
                                onCheckedChange = {
                                    hapticEngine.performToggle(view, it)
                                    onToggleIpv6(it)
                                },
                                modifier = Modifier.testTag("ipv6_switch"),
                            )
                        }
                    }
                }
            }

            // Section 2: Custom User Domain Rules (Direct vs Force-VPN)
            item {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = tr("Свои сайты и домены", "Custom websites & domains"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FilterChip(
                                    selected = domainTab == DomainListTab.DIRECT,
                                    onClick = {
                                        hapticEngine.performNavigationClick(view)
                                        domainTab = DomainListTab.DIRECT
                                    },
                                    label = {
                                        Text(
                                            tr(
                                                "Напрямую (${settings.customDirectDomains.size})",
                                                "Direct (${settings.customDirectDomains.size})",
                                            ),
                                        )
                                    },
                                    modifier = Modifier.testTag("domain_tab_direct"),
                                )
                                FilterChip(
                                    selected = domainTab == DomainListTab.VPN,
                                    onClick = {
                                        hapticEngine.performNavigationClick(view)
                                        domainTab = DomainListTab.VPN
                                    },
                                    label = {
                                        Text(
                                            tr(
                                                "Через VPN (${settings.customVpnDomains.size})",
                                                "Via VPN (${settings.customVpnDomains.size})",
                                            ),
                                        )
                                    },
                                    modifier = Modifier.testTag("domain_tab_vpn"),
                                )
                            }

                            Text(
                                text = when (domainTab) {
                                    DomainListTab.DIRECT -> tr(
                                        "Указанные домены (включая www и поддомены) и все .ru/.рф сайты открываются напрямую через Split-DNS + GeoIP РФ. Совет для Chrome: если включён «Безопасный DNS» стороннего провайдера, переведите его на «По умолчанию», чтобы сайты определялись мгновенно.",
                                        "Listed domains (including www and subdomains) and all .ru/.рф sites bypass the VPN via Split-DNS + GeoIP. Chrome tip: set Secure DNS to Default so local routing applies immediately.",
                                    )
                                    DomainListTab.VPN -> tr(
                                        "Указанные домены направляются через VPN. Домены зон .ru / .su / .рф, Госуслуги, банки и защищённый список РФ всегда идут напрямую (даже в Root-режиме), чтобы защитить серверы FPTN от блокировок.",
                                        "Listed domains are forced through the VPN. .ru / .su / .рф domains, banks, gov services, and the protected RU list always go direct (even in Root mode) to protect FPTN servers.",
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = domainInput,
                                    onValueChange = { domainInput = it },
                                    label = { Text(tr("Домен (например, example.com)", "Domain (e.g. example.com)")) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("custom_domain_input"),
                                )
                                FilledTonalIconButton(
                                    onClick = {
                                        val isValid = com.example.domain.routing.SmartRfRouter.normalizeDomainRule(domainInput) != null
                                        if (isValid) {
                                            hapticEngine.performSuccess(view)
                                        } else {
                                            hapticEngine.performLockedReject(view)
                                        }
                                        if (domainTab == DomainListTab.DIRECT) {
                                            onAddCustomDirectDomain(domainInput)
                                        } else {
                                            onAddCustomVpnDomain(domainInput)
                                        }
                                        if (isValid) {
                                            domainInput = ""
                                        }
                                    },
                                    enabled = domainInput.isNotBlank(),
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("custom_domain_add_button"),
                                ) {
                                    Icon(Icons.Rounded.Add, contentDescription = tr("Добавить домен", "Add domain"))
                                }
                            }

                            val activeDomains = if (domainTab == DomainListTab.DIRECT) {
                                settings.customDirectDomains.sorted()
                            } else {
                                settings.customVpnDomains.sorted()
                            }

                            if (activeDomains.isEmpty()) {
                                Text(
                                    text = tr("Список пока пуст", "No custom domains yet"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    activeDomains.forEach { domain ->
                                        InputChip(
                                            selected = false,
                                            onClick = {
                                                hapticEngine.performDestructiveAction(view)
                                                if (domainTab == DomainListTab.DIRECT) {
                                                    onRemoveCustomDirectDomain(domain)
                                                } else {
                                                    onRemoveCustomVpnDomain(domain)
                                                }
                                            },
                                            label = {
                                                Text(
                                                    text = domain,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                                )
                                            },
                                            trailingIcon = {
                                                Icon(
                                                    imageVector = Icons.Rounded.Close,
                                                    contentDescription = tr("Удалить $domain", "Remove $domain"),
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            },
                                            modifier = Modifier.testTag("custom_domain_chip_$domain"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 3: Official RU Protection (507 rules) + Optional Live Root Unlock
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    hapticEngine.performNavigationClick(view)
                                    protectedListExpanded = !protectedListExpanded
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = if (ruUnlocked) Icons.Rounded.LockOpen else Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                tint = if (ruUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr(
                                        "Защита РФ-приложений ($protectedRuleCount правил)",
                                        "Protected RU apps ($protectedRuleCount rules)",
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = tr(
                                        "Найдено на устройстве: ${detectedProtectedOnDevice.size}. Госуслуги, банки и .ru-домены всегда идут напрямую (закреплено постоянно, в том числе в Root-режиме, для сохранности серверов FPTN).",
                                        "Detected on device: ${detectedProtectedOnDevice.size}. Banking, gov services, and .ru domains always bypass VPN (enforced even in Root mode to protect FPTN servers).",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Icon(
                                imageVector = if (protectedListExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        AnimatedVisibility(visible = protectedListExpanded) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = tr(
                                        "Установленные защищённые приложения (${detectedProtectedOnDevice.size}):",
                                        "Installed protected apps (${detectedProtectedOnDevice.size}):",
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (detectedProtectedOnDevice.isEmpty()) {
                                    Text(
                                        text = tr(
                                            "На устройстве не обнаружено приложений из официального списка.",
                                            "No apps from the built-in exclusion list detected on this device.",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    detectedProtectedOnDevice.take(25).forEach { app ->
                                        Text(
                                            text = "• ${app.appName} (${app.packageName})",
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (detectedProtectedOnDevice.size > 25) {
                                        Text(
                                            text = tr(
                                                "… и ещё ${detectedProtectedOnDevice.size - 25}",
                                                "… and ${detectedProtectedOnDevice.size - 25} more",
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Section 4: Per-App VPN mode
            item {
                Column(
                    modifier = Modifier.widthIn(max = 600.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = tr("Приложения (Per-App VPN)", "Per-App VPN"),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    SettingsCardGroup {
                        SplitTunnelMode.entries.forEachIndexed { idx, mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("split_mode_${mode.name}")
                                    .clickable {
                                        hapticEngine.performToggle(view, enabled = true)
                                        onSelectSplitMode(mode)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = settings.splitTunnelMode == mode,
                                    onClick = {
                                        hapticEngine.performToggle(view, enabled = true)
                                        onSelectSplitMode(mode)
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mode.localizedTitle(), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        mode.localizedSubtitle(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (idx < SplitTunnelMode.entries.lastIndex) SettingsDivider()
                        }
                    }
                }
            }

            if (settings.splitTunnelMode != SplitTunnelMode.ALL_APPS) {
                item {
                    Column(
                        modifier = Modifier.widthIn(max = 600.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                            label = {
                                Text(
                                    tr(
                                        "Поиск приложений (${settings.selectedPackages.size} выбрано)",
                                        "Search apps (${settings.selectedPackages.size} selected)",
                                    ),
                                )
                            },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("per_app_search_input"),
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = appFilter == AppListFilter.ALL,
                                onClick = {
                                    hapticEngine.performNavigationClick(view)
                                    appFilter = AppListFilter.ALL
                                },
                                label = {
                                    Text(tr("Все (${uiState.installedApps.size})", "All (${uiState.installedApps.size})"))
                                },
                                modifier = Modifier.testTag("per_app_filter_all"),
                            )
                            FilterChip(
                                selected = appFilter == AppListFilter.SELECTED,
                                onClick = {
                                    hapticEngine.performNavigationClick(view)
                                    appFilter = AppListFilter.SELECTED
                                },
                                label = {
                                    Text(
                                        tr(
                                            "Выбранные (${settings.selectedPackages.size})",
                                            "Selected (${settings.selectedPackages.size})",
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag("per_app_filter_selected"),
                            )
                            FilterChip(
                                selected = appFilter == AppListFilter.PROTECTED_RU,
                                onClick = {
                                    hapticEngine.performNavigationClick(view)
                                    appFilter = AppListFilter.PROTECTED_RU
                                },
                                label = {
                                    Text(
                                        tr(
                                            "Защита РФ (${detectedProtectedOnDevice.size})",
                                            "RU protected (${detectedProtectedOnDevice.size})",
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag("per_app_filter_protected"),
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = manualPackageInput,
                                onValueChange = { manualPackageInput = it },
                                label = { Text(tr("Добавить пакет вручную", "Add package manually")) },
                                placeholder = { Text("org.telegram.messenger") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("manual_package_input"),
                            )
                            FilledTonalIconButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onAddManualPackage(manualPackageInput)
                                    manualPackageInput = ""
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("manual_package_add_button"),
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = tr("Добавить пакет", "Add package"))
                            }
                        }
                    }
                }

                if (uiState.isLoadingApps) {
                    item {
                        CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    }
                } else {
                    items(
                        items = filteredApps,
                        key = { it.packageName },
                        contentType = { "app_row" },
                    ) { app ->
                        val checked = app.packageName in settings.selectedPackages
                        val isProtectedLocked = app.isMandatoryRuBypass && !ruUnlocked

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 600.dp)
                                .clickable {
                                    if (isProtectedLocked) {
                                        hapticEngine.performLockedReject(view)
                                    } else {
                                        hapticEngine.performToggle(view, !checked)
                                    }
                                    onTogglePackage(app.packageName)
                                },
                            shape = MaterialTheme.shapes.medium,
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    isProtectedLocked ->
                                        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.35f)
                                    checked -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f)
                                    else -> MaterialTheme.colorScheme.surfaceContainerLow
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CachedAppIcon(
                                    packageName = app.packageName,
                                    size = 40.dp,
                                )

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.appName,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = JetBrainsMonoFamily,
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (app.isMandatoryRuBypass) {
                                        Text(
                                            text = if (ruUnlocked) {
                                                tr("Защита РФ • разблокировано через Root", "RU protection • unlocked via Root")
                                            } else {
                                                tr("Защита РФ • всегда в обход VPN", "RU protection • always bypasses VPN")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (ruUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }

                                if (isProtectedLocked) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = tr("Защищено", "Protected"),
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                } else {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = {
                                            hapticEngine.performToggle(view, it)
                                            onTogglePackage(app.packageName)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(contentType = "spacer") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}
