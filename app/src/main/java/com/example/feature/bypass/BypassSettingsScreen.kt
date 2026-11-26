package com.example.feature.bypass

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.clipboard.ClipboardHelper
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedSubtitle
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.domain.model.BypassMethod
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.VpnSettings
import com.example.feature.shared.FptnUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BypassSettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onSelectBypassMethod: (BypassMethod) -> Unit,
    onSelectSniSpoofingMode: (SniSpoofingMode) -> Unit,
    onSaveCustomSni: (String) -> Unit,
    onResetDefaultSni: () -> Unit,
    onPickRandomSni: (regionOnly: Boolean) -> Unit = {},
    onStartSniScan: () -> Unit,
    onStopSniScan: () -> Unit,
    onSelectSlidingMode: (SlidingTunnelMode) -> Unit,
    onShowMessage: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isRu = LocalIsRussian.current
    var sniInput by remember(uiState.settings.customSni) { mutableStateOf(uiState.settings.customSni) }
    var sniModeListExpanded by remember { mutableStateOf(false) }
    var showSniCatalogSheet by remember { mutableStateOf(false) }

    val modeArrowRotation by animateFloatAsState(
        targetValue = if (sniModeListExpanded) 180f else 0f,
        label = "sniModeArrow",
    )

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_bypass_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("bypass_back_button"),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 1. Exact 2 official FPTN 1.3.28.0 bypass methods
                Text(
                    text = tr("Метод обхода блокировок", "Censorship bypass method"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )

                SettingsCardGroup {
                    BypassMethod.entries.forEachIndexed { idx, method ->
                        val selected = uiState.settings.bypassMethod == method
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bypass_method_${method.name}")
                                .clickable {
                                    hapticEngine.performSegmentTick(view)
                                    onSelectBypassMethod(method)
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    hapticEngine.performSegmentTick(view)
                                    onSelectBypassMethod(method)
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = method.localizedTitle(),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = method.localizedSubtitle(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (idx < BypassMethod.entries.lastIndex) {
                            SettingsDivider()
                        }
                    }
                }

                // Clear explanation: SNI depends on local ISP/TSPU rather than VPN server country
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = tr(
                            "Совет по SNI: домен маскировки подбирается под вашего локального провайдера (ТСПУ), а не под страну VPN-сервера. Режимы «SNI» и «Обфускация» работают на любом официальном сервере FPTN, а для «Reality» важно, чтобы выбранный домен открывался со стороны самого сервера.",
                            "SNI tip: choose a spoofing domain that works with your local ISP/DPI, not the VPN server country. Base SNI and Obfuscation work with any official FPTN server; for Reality, the chosen domain must also be reachable from the server.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }

                // 2. Official 15 SNI / Reality browser fingerprint modes (visible when SNI_REALITY is selected)
                AnimatedVisibility(
                    visible = uiState.settings.bypassMethod == BypassMethod.SNI_REALITY,
                    enter = fadeIn() + expandVertically(
                        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                    ),
                    exit = fadeOut() + shrinkVertically(
                        animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                    ),
                ) {
                    SettingsCardGroup {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bypass_sni_mode_header")
                                .clickable {
                                    hapticEngine.performSegmentTick(view)
                                    sniModeListExpanded = !sniModeListExpanded
                                }
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Fingerprint,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tr("Профиль подмены (SNI / Reality)", "Spoofing profile (SNI / Reality)"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "${uiState.settings.sniSpoofingMode.localizedTitle()} • ${uiState.settings.sniSpoofingMode.nativeStrategyName()}",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = JetBrainsMonoFamily,
                                    ),
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(modeArrowRotation),
                            )
                        }

                        AnimatedVisibility(visible = sniModeListExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                            ) {
                                SettingsDivider()
                                SniSpoofingMode.entries.forEach { mode ->
                                    val isChosen = uiState.settings.sniSpoofingMode == mode
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("sni_mode_${mode.name}")
                                            .clickable {
                                                hapticEngine.performSegmentTick(view)
                                                onSelectSniSpoofingMode(mode)
                                                sniModeListExpanded = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = isChosen,
                                            onClick = {
                                                hapticEngine.performSegmentTick(view)
                                                onSelectSniSpoofingMode(mode)
                                                sniModeListExpanded = false
                                            },
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = mode.localizedTitle(),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                text = mode.nativeStrategyName(),
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontFamily = JetBrainsMonoFamily,
                                                ),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Official SNI Domain + ~1,278 Catalog Picker + Real Native SniChecker Auto-Scan
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(
                            animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                        ),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = tr(
                                "SNI-домен и официальная база (${uiState.sniCatalog.size})",
                                "SNI domain & built-in catalog (${uiState.sniCatalog.size})",
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val activeRegion = uiState.settings.bypassRegion
                        val activeRegionLabel = activeRegion.localizedShortTitle()
                        Text(
                            text = tr(
                                "Официальный домен по умолчанию: ${VpnSettings.DEFAULT_SNI_DOMAIN}. Активный регион маршрутизации: $activeRegionLabel — автоподбор и каталог SNI ставят приоритетные домены вашего региона в начало списка.",
                                "Default upstream domain: ${VpnSettings.DEFAULT_SNI_DOMAIN}. Active routing region: $activeRegionLabel — auto-scan and catalog prioritize domains for your region first.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        OutlinedTextField(
                            value = sniInput,
                            onValueChange = { sniInput = it },
                            label = { Text(tr("SNI-домен маскировки", "Spoofing SNI domain")) },
                            placeholder = { Text(VpnSettings.DEFAULT_SNI_DOMAIN) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                            trailingIcon = {
                                IconButton(
                                    onClick = {
                                        hapticEngine.performConfirm(view)
                                        ClipboardHelper.copyText(context, "FPTN SNI", sniInput.trim())
                                        onShowMessage(
                                            tr(
                                                isRu,
                                                "SNI «${sniInput.trim()}» скопирован",
                                                "SNI \"${sniInput.trim()}\" copied",
                                            ),
                                        )
                                    },
                                    modifier = Modifier.testTag("bypass_copy_sni_button"),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = tr("Копировать SNI", "Copy SNI"),
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("bypass_sni_input"),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    hapticEngine.performSegmentTick(view)
                                    sniInput = VpnSettings.DEFAULT_SNI_DOMAIN
                                    onResetDefaultSni()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("bypass_reset_sni_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("По умолчанию", "Default"))
                            }

                            Button(
                                onClick = {
                                    hapticEngine.performConfirm(view)
                                    onSaveCustomSni(sniInput)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("bypass_save_sni_button"),
                            ) {
                                Text(tr("Сохранить", "Save"))
                            }
                        }

                        // Regional Priority SNI Quick Chips + Per-Device Randomizer
                        if (activeRegion.recommendedSnis.isNotEmpty()) {
                            Text(
                                text = tr(
                                    "Приоритетные SNI региона ($activeRegionLabel):",
                                    "Priority SNIs for $activeRegionLabel:",
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                activeRegion.recommendedSnis.take(8).forEach { domain ->
                                    FilterChip(
                                        selected = uiState.settings.customSni.equals(domain, ignoreCase = true),
                                        onClick = {
                                            hapticEngine.performConfirm(view)
                                            sniInput = domain
                                            onSaveCustomSni(domain)
                                        },
                                        label = {
                                            Text(
                                                text = domain,
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            )
                                        },
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onPickRandomSni(true)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("bypass_random_region_sni_button"),
                            ) {
                                Icon(Icons.Rounded.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Случ. региона", "Random regional"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onPickRandomSni(false)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(46.dp)
                                    .testTag("bypass_random_catalog_sni_button"),
                            ) {
                                Icon(Icons.Rounded.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Случ. из базы", "Random catalog"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }

                        // Browse full ~1,278 official SNI list
                        OutlinedButton(
                            onClick = {
                                hapticEngine.performSegmentTick(view)
                                showSniCatalogSheet = true
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("bypass_sni_catalog_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.List,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                tr(
                                    "Выбрать из базы SNI (${uiState.sniCatalog.size} доменов)",
                                    "Browse SNI catalog (${uiState.sniCatalog.size} domains)",
                                ),
                            )
                        }

                        // Start / Stop Native SniChecker auto-scan
                        val scanProgress = uiState.sniScanProgress
                        if (scanProgress.isScanning) {
                            Button(
                                onClick = {
                                    hapticEngine.performSegmentTick(view)
                                    onStopSniScan()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError,
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("bypass_sni_stop_scan_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(tr("Остановить автоподбор SNI", "Stop SNI scan"))
                            }

                            val progressFraction = if (scanProgress.totalCount > 0) {
                                scanProgress.checkedCount.toFloat() / scanProgress.totalCount.toFloat()
                            } else {
                                0f
                            }
                            LinearProgressIndicator(
                                progress = { progressFraction.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = tr(
                                    "Проверено ${scanProgress.checkedCount} из ${scanProgress.totalCount} • ${scanProgress.currentDomain}",
                                    "Checked ${scanProgress.checkedCount} of ${scanProgress.totalCount} • ${scanProgress.currentDomain}",
                                ),
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else {
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performSegmentTick(view)
                                    onStartSniScan()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("bypass_sni_scan_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Radar,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(tr("Автоподбор рабочего SNI", "Auto-scan working SNI"))
                            }
                        }

                        if (uiState.sniScanResults.isNotEmpty()) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                            )
                            Text(
                                text = tr(
                                    "Недавние результаты проверки (нажмите, чтобы применить):",
                                    "Recent scan results (tap to apply):",
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                uiState.sniScanResults.take(6).forEach { result ->
                                    val isCurrent = result.domain.equals(uiState.settings.customSni, ignoreCase = true)
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = if (isCurrent) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = result.reachable) {
                                                hapticEngine.performConfirm(view)
                                                sniInput = result.domain
                                                onSaveCustomSni(result.domain)
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = result.domain,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontFamily = JetBrainsMonoFamily,
                                                ),
                                                color = if (isCurrent) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurface
                                                },
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                text = if (result.reachable && result.rttMs != null) {
                                                    tr("${result.rttMs} мс", "${result.rttMs} ms")
                                                } else {
                                                    tr("Не подошёл", "Failed")
                                                },
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontFamily = JetBrainsMonoFamily,
                                                ),
                                                color = if (result.reachable) {
                                                    MaterialTheme.colorScheme.tertiary
                                                } else {
                                                    MaterialTheme.colorScheme.error
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. Exact 3 official FPTN 1.3.28.0 Sliding Tunnel strategies
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = tr("Стратегия туннеля (Скользящий туннель)", "Tunnel strategy (Rolling tunnel)"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Text(
                            text = tr(
                                "Что это такое: ТСПУ часто замедляет долгие HTTPS-соединения. Скользящий туннель внутри нативного ядра FPTN регулярно открывает свежие каналы и бесшовно переносит туда трафик без разрыва связи.",
                                "How it works: DPI often throttles long-lived HTTPS streams. Rolling tunnel inside native libfptn periodically opens fresh channels and seamlessly migrates traffic without dropping connections.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        SlidingTunnelMode.entries.forEachIndexed { idx, mode ->
                            val selected = uiState.settings.slidingTunnelMode == mode
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("sliding_mode_${mode.name}")
                                    .clickable {
                                        hapticEngine.performSegmentTick(view)
                                        onSelectSlidingMode(mode)
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = {
                                        hapticEngine.performSegmentTick(view)
                                        onSelectSlidingMode(mode)
                                    },
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.localizedTitle(),
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = mode.localizedSubtitle(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (idx < SlidingTunnelMode.entries.lastIndex) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    if (showSniCatalogSheet) {
        SniCatalogPickerSheet(
            catalog = uiState.sniCatalog,
            currentSni = uiState.settings.customSni,
            hapticEngine = hapticEngine,
            onSelectDomain = { domain ->
                sniInput = domain
                onSaveCustomSni(domain)
                showSniCatalogSheet = false
            },
            onDismiss = { showSniCatalogSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SniCatalogPickerSheet(
    catalog: List<String>,
    currentSni: String,
    hapticEngine: HapticEngine,
    onSelectDomain: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var search by remember { mutableStateOf("") }

    val filtered = remember(catalog, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) catalog else catalog.filter { it.contains(q) }
    }

    val hostNavBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        val sheetNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val safeNavBottom = maxOf(sheetNavBottom, hostNavBarBottom)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = safeNavBottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = tr("Официальная база SNI (${catalog.size})", "Built-in SNI catalog (${catalog.size})"),
                style = MaterialTheme.typography.titleLarge,
            )

            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text(tr("Поиск среди ${catalog.size} доменов…", "Search ${catalog.size} domains…")) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sni_catalog_search_input"),
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = filtered,
                    key = { it },
                    contentType = { "sni_domain" },
                ) { domain ->
                    val selected = domain.equals(currentSni, ignoreCase = true)
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                hapticEngine.performConfirm(view)
                                onSelectDomain(domain)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = domain,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = JetBrainsMonoFamily,
                                ),
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = tr("Выбран", "Selected"),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
