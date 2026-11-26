package com.example.feature.root

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.root.SafeRootRulePlanner
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.feature.shared.FptnUiState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RootSettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onVerifyRootLive: () -> Unit,
    onToggleRootMode: (Boolean) -> Unit,
    onToggleAdaptiveBoost: (Boolean) -> Unit,
    onToggleSecondaryVpnChaining: (Boolean) -> Unit,
    onUpdateTunInterfaceName: (String) -> Unit,
    onCleanupRulesNow: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val view = LocalView.current
    val currentTunName = SafeRootRulePlanner.sanitizeTunName(uiState.settings.rootTunInterfaceName)
    var customTunInput by remember(currentTunName) { mutableStateOf(currentTunName) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_root_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("root_back_button"),
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
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SettingsCardGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(
                                    "Использовать скрытый Root-режим (Stealth TUN)",
                                    "Use Stealth Root mode (Kernel TUN)",
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tr(
                                    "Открывает ядерный интерфейс $currentTunName в обход системного VpnService: без значка ключа VPN в шторке и без флага TRANSPORT_VPN.",
                                    "Opens kernel interface $currentTunName bypassing Android VpnService: no VPN key icon in the status bar and no TRANSPORT_VPN flag.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.settings.useRootMode,
                            onCheckedChange = {
                                hapticEngine.performToggle(view, it)
                                onToggleRootMode(it)
                            },
                            modifier = Modifier.testTag("root_mode_switch"),
                        )
                    }

                    SettingsDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(
                                    "Максимум ресурсов на подключение (Адаптивно)",
                                    "Adaptive Root performance boost",
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            val boostTierLabel = uiState.rootBoostTier.localizedShortTitle()
                            val boostStatus = if (uiState.settings.rootAdaptiveBoostEnabled) {
                                tr("Текущий режим: $boostTierLabel. ", "Current tier: $boostTierLabel. ")
                            } else {
                                ""
                            }
                            Text(
                                text = boostStatus + tr(
                                    "Динамически ускоряет Root TUN (приоритет потока и очередь $currentTunName), но мгновенно уступает CPU/RAM играм и тяжёлым приложениям при нагрузке или нагреве.",
                                    "Dynamically prioritizes Root TUN (worker thread nice and $currentTunName txqueuelen), yielding CPU/RAM immediately under heavy load or thermal pressure.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.settings.rootAdaptiveBoostEnabled,
                            onCheckedChange = {
                                hapticEngine.performToggle(view, it)
                                onToggleAdaptiveBoost(it)
                            },
                            modifier = Modifier.testTag("root_adaptive_boost_switch"),
                        )
                    }

                    SettingsDivider()

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr(
                                    "Цепочка с другим VPN (без конфликта с tun0)",
                                    "Chain with another VPN (no tun0 conflict)",
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tr(
                                    "Позволяет запустить поверх FPTN Root обычный не-Root VPN (WireGuard, v2rayNG и т.д.): трафик пойдёт цепочкой Приложения → Другой VPN (tun0) → FPTN Root ($currentTunName) → Интернет без петли маршрутов.",
                                    "Allows running a standard non-root VPN (WireGuard, v2rayNG, etc.) on top of FPTN Root: traffic flows Apps → Other VPN (tun0) → FPTN Root ($currentTunName) → Internet without routing loops.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.settings.allowSecondaryVpnChaining,
                            onCheckedChange = {
                                hapticEngine.performToggle(view, it)
                                onToggleSecondaryVpnChaining(it)
                            },
                            modifier = Modifier.testTag("root_vpn_chaining_switch"),
                        )
                    }
                }

                // Custom kernel TUN interface name selector
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.SettingsEthernet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = tr("Имя ядерного TUN-интерфейса", "Kernel TUN interface name"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            text = tr(
                                "Скрытый Root TUN не занимает системный tun0 Android. При желании выберите другое имя интерфейса (2–12 символов: a-z, 0-9, _).",
                                "Stealth Root TUN does not occupy Android's system tun0. You may pick a custom interface name (2–12 chars: a-z, 0-9, _).",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SafeRootRulePlanner.PRESET_TUN_NAMES.forEach { presetName ->
                                val selected = currentTunName == presetName
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        hapticEngine.performSegmentTick(view)
                                        customTunInput = presetName
                                        onUpdateTunInterfaceName(presetName)
                                    },
                                    label = {
                                        Text(
                                            text = presetName,
                                            fontFamily = JetBrainsMonoFamily,
                                        )
                                    },
                                    modifier = Modifier.testTag("root_tun_preset_$presetName"),
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedTextField(
                                value = customTunInput,
                                onValueChange = { customTunInput = it.lowercase().take(12) },
                                label = { Text(tr("Своё имя (например, fptn2)", "Custom name (e.g. fptn2)")) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("root_tun_custom_input"),
                            )
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performConfirm(view)
                                    val sanitized = SafeRootRulePlanner.sanitizeTunName(customTunInput)
                                    customTunInput = sanitized
                                    onUpdateTunInterfaceName(sanitized)
                                },
                                modifier = Modifier
                                    .height(52.dp)
                                    .testTag("root_tun_save_button"),
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null)
                            }
                        }
                    }
                }

                // Safety Architecture Guarantees
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
                        Text(
                            text = tr(
                                "Архитектура скрытого Root TUN и безопасность",
                                "Stealth Root TUN architecture & safety guarantees",
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        SafetyCheckRow(
                            tr(
                                "Ядерный TUN $currentTunName (${SafeRootRulePlanner.DEFAULT_CLIENT_IPV4}) открывается напрямую без Android VpnService — иконка VPN не появляется",
                                "Kernel TUN $currentTunName (${SafeRootRulePlanner.DEFAULT_CLIENT_IPV4}) opens directly without Android VpnService — no system VPN icon",
                            ),
                        )
                        SafetyCheckRow(
                            tr(
                                "Изолированные цепочки ${SafeRootRulePlanner.CHAIN_OUTPUT}, ${SafeRootRulePlanner.CHAIN_POSTROUTING} (SNAT) и ${SafeRootRulePlanner.CHAIN_DNS_NAT} (таблица ${SafeRootRulePlanner.ROUTING_TABLE_ID}, pref ${SafeRootRulePlanner.RULE_PREF}, mark ${SafeRootRulePlanner.FWMARK_HEX})",
                                "Isolated chains ${SafeRootRulePlanner.CHAIN_OUTPUT}, ${SafeRootRulePlanner.CHAIN_POSTROUTING} (SNAT), and ${SafeRootRulePlanner.CHAIN_DNS_NAT} (table ${SafeRootRulePlanner.ROUTING_TABLE_ID}, pref ${SafeRootRulePlanner.RULE_PREF}, mark ${SafeRootRulePlanner.FWMARK_HEX})",
                            ),
                        )
                        SafetyCheckRow(
                            tr(
                                "Совместимость с другим VPN: метка protectedFromVpn (${SafeRootRulePlanner.ANDROID_PROTECTED_FROM_VPN_MARK}) + bypass -o tun+ исключают петлю маршрутов",
                                "Secondary VPN compatibility: protectedFromVpn mark (${SafeRootRulePlanner.ANDROID_PROTECTED_FROM_VPN_MARK}) + -o tun+ bypass prevent routing loops",
                            ),
                        )
                        SafetyCheckRow(
                            tr(
                                "Системные цепочки netd, rp_filter и ip6tables DROP на wlan+ не изменяются",
                                "System netd chains, rp_filter, and wlan+ ip6tables DROP rules are never modified",
                            ),
                        )
                        SafetyCheckRow(
                            tr(
                                "Обязательный bypass UID приложения, NetworkStack (Google Pixel без «!»), 507 правил РФ-приложений, IP сервера и RFC1918 LAN",
                                "Mandatory bypass for app UID, NetworkStack (no \"!\" on Google Pixel), 507 RU app rules, server IP, and RFC1918 LAN",
                            ),
                        )
                        SafetyCheckRow(
                            tr(
                                "Синхронный откат (-D, -F, -X, ip rule del, удаление $currentTunName) выполняется первым шагом при отключении, onDestroy и старте",
                                "Synchronous teardown (-D, -F, -X, ip rule del, removing $currentTunName) runs first on disconnect, onDestroy, and startup",
                            ),
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val statusText = if (uiState.areRootRulesActive) {
                            tr("Активны ($currentTunName)", "Active ($currentTunName)")
                        } else {
                            tr("Чисто (не установлены)", "Clean (not installed)")
                        }
                        Text(
                            text = tr("Статус цепочек: $statusText", "Chain status: $statusText"),
                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                            color = if (uiState.areRootRulesActive) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )

                        OutlinedButton(
                            enabled = !uiState.isRootActionBusy,
                            onClick = {
                                if (!uiState.isRootActionBusy) {
                                    hapticEngine.performSegmentTick(view)
                                    onVerifyRootLive()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("root_verify_now_button"),
                        ) {
                            Icon(Icons.Rounded.Security, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (uiState.isRootActionBusy) {
                                    tr("Выполняется операция Root…", "Running root operation…")
                                } else {
                                    tr("Проверить права Root вручную", "Verify root access")
                                },
                            )
                        }

                        FilledTonalButton(
                            enabled = !uiState.isRootActionBusy,
                            onClick = {
                                if (!uiState.isRootActionBusy) {
                                    hapticEngine.performDestructiveAction(view)
                                    onCleanupRulesNow()
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("root_cleanup_now_button"),
                        ) {
                            Icon(Icons.Rounded.CleaningServices, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                if (uiState.isRootActionBusy) {
                                    tr("Очистка в фоне (можно выходить)…", "Cleaning up in background…")
                                } else {
                                    tr("Очистить Root-правила сейчас", "Clean up root rules now")
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun SafetyCheckRow(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
