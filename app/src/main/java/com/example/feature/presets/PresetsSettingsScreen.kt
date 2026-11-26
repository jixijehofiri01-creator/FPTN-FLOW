package com.example.feature.presets

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsMode
import com.example.domain.model.FptnServer
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.NetworkPreset
import com.example.domain.model.NetworkPresetTrigger
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.ServerSortOrder
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.VpnSettings
import com.example.domain.vpn.WatchdogSensitivity
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.localizedName
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.feature.shared.FptnUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PresetsSettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onToggleAutoSwitch: (Boolean) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSavePreset: (NetworkPreset) -> Unit,
    onDuplicatePreset: (String) -> Unit = {},
    onDeletePreset: (String) -> Unit,
    onRestoreDefaults: () -> Unit,
    onRegenerateRandomSnis: () -> Unit,
    onResetPresetOverride: () -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val view = LocalView.current
    var editingPreset by remember { mutableStateOf<NetworkPreset?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_presets_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("presets_back_button"),
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
                    .widthIn(max = 600.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Auto-switch by network type (Wi-Fi <-> Mobile)
                SettingsCardGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = tr("Автопереключение по типу сети", "Auto-switch by network type"),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tr(
                                    "При переходе Wi-Fi ↔ Мобильная сеть автоматически применяет подходящий пресет и мягко переподключает туннель.",
                                    "Automatically applies the matching preset when switching between Wi-Fi and mobile data, smoothly reconnecting the tunnel.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.settings.autoSwitchPresetsByNetwork,
                            onCheckedChange = {
                                hapticEngine.performToggle(view, it)
                                onToggleAutoSwitch(it)
                            },
                            modifier = Modifier.testTag("presets_auto_switch_toggle"),
                        )
                    }
                }

                // Forced-preset indicator card with 1-tap return to Auto
                if (ManualOverrideKey.PRESET in uiState.settings.manualOverrides) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("presets_forced_banner"),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f),
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
                                    imageVector = Icons.Rounded.PushPin,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = tr("Пресет выбран принудительно", "Preset manually pinned"),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                            Text(
                                text = tr(
                                    "Автопереключение пресетов при смене Wi-Fi ↔ 4G/5G не перезапишет этот выбор, пока вы не вернёте его на автомат.",
                                    "Auto-switching presets on Wi-Fi ↔ 4G/5G changes will not overwrite this choice until you reset it to auto.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.88f),
                            )
                            FilledTonalButton(
                                onClick = {
                                    hapticEngine.performToggle(view, enabled = false)
                                    onResetPresetOverride()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("presets_reset_override_button"),
                            ) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(tr("Вернуть на автомат", "Return to Auto"))
                            }
                        }
                    }
                }

                // Action row: Create custom preset + Randomize per-install SNI profiles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            val cur = uiState.settings
                            editingPreset = NetworkPreset(
                                id = "",
                                name = "",
                                trigger = NetworkPresetTrigger.MANUAL,
                                overrideBypass = true,
                                bypassMethod = cur.bypassMethod,
                                sniSpoofingMode = cur.sniSpoofingMode,
                                customSni = cur.customSni,
                                overrideSlidingTunnel = true,
                                slidingTunnelMode = cur.slidingTunnelMode,
                                dnsModeOverride = null,
                                enableCustomDnsUrl = false,
                                customDnsUrl = cur.customDnsUrl,
                                dnsPrimaryOverride = cur.dnsPrimary,
                                dnsSecondaryOverride = cur.dnsSecondary,
                                overrideWatchdog = true,
                                enableStallWatchdog = cur.enableStallWatchdog,
                                watchdogSensitivity = cur.watchdogSensitivity,
                                watchdogProbeSec = cur.watchdogProbeSec,
                                watchdogStallSec = cur.watchdogStallSec,
                                watchdogCooldownSec = cur.watchdogCooldownSec,
                                overrideReconnect = true,
                                reconnectOnFailure = cur.reconnectOnFailure,
                                reconnectOnNetworkChange = cur.reconnectOnNetworkChange,
                                maxReconnectAttempts = cur.maxReconnectAttempts,
                                reconnectDelaySec = cur.reconnectDelaySec,
                                handshakeTimeoutSec = cur.handshakeTimeoutSec,
                                wsOpenTimeoutSec = cur.wsOpenTimeoutSec,
                                keepaliveIntervalSecOverride = null,
                                networkSwitchDebounceSecOverride = null,
                                autoRotateSniOnFail = null,
                                autoFailoverServer = null,
                                overrideMtu = true,
                                tunMtu = cur.tunMtu,
                                smartRfRouting = cur.smartRfRouting,
                                smartRfRoutingOverride = null,
                                bypassRegionOverride = null,
                                splitTunnelModeOverride = null,
                                autoPingIntervalSecOverride = null,
                                serverSortOrderOverride = null,
                                allowIpv6Override = null,
                                useRootModeOverride = null,
                                preferredServerId = null,
                                forceOverrideManualLocks = false,
                                isBuiltIn = false,
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("presets_create_button"),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("Новый пресет", "New preset"))
                    }

                    FilledTonalButton(
                        onClick = {
                            hapticEngine.performSuccess(view)
                            onRegenerateRandomSnis()
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("presets_randomize_sni_button"),
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("Случайные SNI", "Random SNI"))
                    }
                }

                // Preset cards
                uiState.settings.networkPresets.forEach { preset ->
                    val isActive = uiState.settings.activePresetId == preset.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("preset_card_${preset.id}"),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = if (isActive) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = when (preset.trigger) {
                                        NetworkPresetTrigger.MOBILE -> Icons.Rounded.SignalCellularAlt
                                        NetworkPresetTrigger.WIFI -> Icons.Rounded.Wifi
                                        NetworkPresetTrigger.MANUAL -> Icons.Rounded.Tune
                                    },
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    val triggerLabel = preset.trigger.localizedTitle()
                                    val overrideLocksSuffix = if (preset.forceOverrideManualLocks) {
                                        tr(" • поверх фиксаций", " • overrides locks")
                                    } else {
                                        ""
                                    }
                                    Text(
                                        text = preset.localizedName(),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = tr("Триггер: $triggerLabel$overrideLocksSuffix", "Trigger: $triggerLabel$overrideLocksSuffix"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isActive) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primary,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(14.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = tr("Активен", "Active"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                        }
                                    }
                                }
                            }

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (preset.overrideBypass) {
                                    val bypassChipLabel = preset.bypassMethod.localizedShortTitle()
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(bypassChipLabel) },
                                    )
                                    AssistChip(
                                        onClick = {},
                                        label = {
                                            Text(
                                                text = "SNI: ${preset.customSni}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                            )
                                        },
                                    )
                                }
                                if (preset.overrideSlidingTunnel) {
                                    val slidingLabel = preset.slidingTunnelMode.localizedShortTitle()
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(slidingLabel) },
                                    )
                                }
                                if (preset.overrideMtu) {
                                    AssistChip(
                                        onClick = {},
                                        label = { Text("MTU ${preset.tunMtu}") },
                                    )
                                }
                                if (preset.overrideWatchdog) {
                                    val sensPrefix = preset.watchdogSensitivity?.localizedShortTitle()?.let { "$it " }.orEmpty()
                                    val watchdogLabel = if (preset.enableStallWatchdog) {
                                        tr(
                                            "Сторож ${sensPrefix}${preset.watchdogProbeSec}с/${preset.watchdogStallSec}с",
                                            "Watchdog ${sensPrefix}${preset.watchdogProbeSec}s/${preset.watchdogStallSec}s",
                                        )
                                    } else {
                                        tr("Сторож: Выкл", "Watchdog: Off")
                                    }
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(watchdogLabel) },
                                    )
                                }
                                preset.keepaliveIntervalSecOverride?.let { ka ->
                                    val kaLabel = if (ka <= 0) {
                                        tr("Keepalive: Выкл", "Keepalive: Off")
                                    } else {
                                        tr("Keepalive ${ka}с", "Keepalive ${ka}s")
                                    }
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(kaLabel) },
                                    )
                                }
                                preset.effectiveDnsModeOverride()?.let { dnsMode ->
                                    val labelText = when (dnsMode) {
                                        DnsMode.SMART_DNS_DOH -> {
                                            val matched = uiState.settings.savedDnsEntries.firstOrNull {
                                                it.urlOrHost.equals(preset.customDnsUrl, ignoreCase = true)
                                            }
                                            "DNS: ${matched?.localizedTitle() ?: "Smart DNS"}"
                                        }
                                        DnsMode.CUSTOM_IPV4 -> "DNS: ${preset.dnsPrimaryOverride.ifBlank { "IPv4" }}"
                                        else -> "DNS: ${dnsMode.localizedShortTitle()}"
                                    }
                                    AssistChip(onClick = {}, label = { Text(labelText) })
                                }
                                preset.preferredServerId?.let { srvId ->
                                    val srvLabel = if (srvId < 0L) {
                                        tr("Сервер: Авто", "Server: Auto")
                                    } else {
                                        val srv = uiState.enabledServers.firstOrNull { it.id == srvId }
                                        tr("Сервер: ${srv?.cleanName ?: "#$srvId"}", "Server: ${srv?.cleanName ?: "#$srvId"}")
                                    }
                                    AssistChip(onClick = {}, label = { Text(srvLabel) })
                                }
                                preset.bypassRegionOverride?.let { region ->
                                    val regionLabel = region.localizedTitle().substringBefore(" (")
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(tr("Регион: $regionLabel", "Region: $regionLabel")) },
                                    )
                                }
                                preset.splitTunnelModeOverride?.let { split ->
                                    val splitLabel = split.localizedTitle()
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(tr("Сплит: $splitLabel", "Split: $splitLabel")) },
                                    )
                                }
                                preset.allowIpv6Override?.let { ipv6 ->
                                    val ipv6Label = if (ipv6) tr("IPv6: Вкл", "IPv6: On") else tr("IPv6: Выкл", "IPv6: Off")
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(ipv6Label) },
                                    )
                                }
                                preset.useRootModeOverride?.let { rootOverride ->
                                    val rootLabel = if (rootOverride) tr("Root: Вкл", "Root: On") else tr("Root: Выкл", "Root: Off")
                                    AssistChip(
                                        onClick = {},
                                        label = { Text(rootLabel) },
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        hapticEngine.performSuccess(view)
                                        onApplyPreset(preset.id)
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("preset_apply_${preset.id}"),
                                ) {
                                    Text(if (isActive) tr("Применён", "Applied") else tr("Применить", "Apply"))
                                }
                                IconButton(
                                    onClick = {
                                        hapticEngine.performNavigationClick(view)
                                        onDuplicatePreset(preset.id)
                                    },
                                    modifier = Modifier.testTag("preset_duplicate_${preset.id}"),
                                ) {
                                    Icon(Icons.Rounded.ContentCopy, contentDescription = tr("Дублировать пресет", "Duplicate preset"))
                                }
                                IconButton(
                                    onClick = {
                                        hapticEngine.performNavigationClick(view)
                                        editingPreset = preset
                                    },
                                    modifier = Modifier.testTag("preset_edit_${preset.id}"),
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = tr("Редактировать пресет", "Edit preset"))
                                }
                                IconButton(
                                    onClick = {
                                        hapticEngine.performDestructiveAction(view)
                                        onDeletePreset(preset.id)
                                    },
                                    modifier = Modifier.testTag("preset_delete_${preset.id}"),
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = tr("Удалить пресет", "Delete preset"),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }

                // Restore built-in presets if deleted
                val hasAllBuiltIn = VpnSettings.DEFAULT_NETWORK_PRESETS.all { def ->
                    uiState.settings.networkPresets.any { it.id == def.id }
                }
                if (!hasAllBuiltIn) {
                    OutlinedButton(
                        onClick = {
                            hapticEngine.performSegmentTick(view)
                            onRestoreDefaults()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("presets_restore_defaults_button"),
                    ) {
                        Icon(Icons.Rounded.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(tr("Восстановить базовые пресеты", "Restore built-in presets"))
                    }
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }

    editingPreset?.let { draft ->
        PresetEditorDialog(
            initial = draft,
            isRootAvailable = uiState.isRootAvailable,
            isRuRootUnlocked = false,
            savedDnsEntries = uiState.settings.savedDnsEntries,
            enabledServers = uiState.enabledServers,
            sniCatalog = uiState.sniCatalog,
            onDismiss = { editingPreset = null },
            onSave = { saved ->
                onSavePreset(saved)
                editingPreset = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetEditorDialog(
    initial: NetworkPreset,
    isRootAvailable: Boolean,
    isRuRootUnlocked: Boolean,
    savedDnsEntries: List<SavedDnsEntry>,
    enabledServers: List<FptnServer>,
    sniCatalog: List<String>,
    onDismiss: () -> Unit,
    onSave: (NetworkPreset) -> Unit,
) {
    val isRu = LocalIsRussian.current
    var name by remember(initial) { mutableStateOf(initial.name) }
    var trigger by remember(initial) { mutableStateOf(initial.trigger) }
    var forceOverrideManualLocks by remember(initial) { mutableStateOf(initial.forceOverrideManualLocks) }

    // Server override
    var preferredServerId by remember(initial) { mutableStateOf(initial.preferredServerId) }
    var showServerList by remember { mutableStateOf(false) }

    // Bypass & TLS
    var overrideBypass by remember(initial) { mutableStateOf(initial.overrideBypass) }
    var bypassMethod by remember(initial) { mutableStateOf(initial.bypassMethod) }
    var sniSpoofingMode by remember(initial) { mutableStateOf(initial.sniSpoofingMode) }
    var showAllTlsProfiles by remember { mutableStateOf(false) }
    var sni by remember(initial) { mutableStateOf(initial.customSni) }

    // Sliding tunnel
    var overrideSlidingTunnel by remember(initial) { mutableStateOf(initial.overrideSlidingTunnel) }
    var slidingMode by remember(initial) { mutableStateOf(initial.slidingTunnelMode) }

    // Flexible unlimited DNS
    var dnsModeOverride by remember(initial) { mutableStateOf(initial.effectiveDnsModeOverride()) }
    var customDnsUrl by remember(initial) { mutableStateOf(initial.customDnsUrl.ifBlank { VpnSettings.DEFAULT_SMART_DNS_URL }) }
    var dnsPrimaryOverride by remember(initial) { mutableStateOf(initial.dnsPrimaryOverride.ifBlank { "1.1.1.1" }) }
    var dnsSecondaryOverride by remember(initial) { mutableStateOf(initial.dnsSecondaryOverride.ifBlank { "8.8.8.8" }) }
    var dnsSearchQuery by remember { mutableStateOf("") }

    // MTU, Routing, Split Tunnel, Auto-Ping, Sort, IPv6, Root
    var overrideMtu by remember(initial) { mutableStateOf(initial.overrideMtu) }
    var tunMtu by remember(initial) { mutableIntStateOf(initial.tunMtu) }
    var bypassRegionOverride by remember(initial) { mutableStateOf(initial.bypassRegionOverride) }
    var smartRfRouting by remember(initial) { mutableStateOf(initial.smartRfRouting) }
    var smartRfRoutingOverride by remember(initial) { mutableStateOf(initial.smartRfRoutingOverride) }
    var splitTunnelModeOverride by remember(initial) { mutableStateOf(initial.splitTunnelModeOverride) }
    var autoPingIntervalSecOverride by remember(initial) { mutableStateOf(initial.autoPingIntervalSecOverride) }
    var serverSortOrderOverride by remember(initial) { mutableStateOf(initial.serverSortOrderOverride) }
    var allowIpv6Override by remember(initial) { mutableStateOf(initial.allowIpv6Override) }
    var useRootOverride by remember(initial) { mutableStateOf(initial.useRootModeOverride) }

    // Watchdog & Reconnect & Timeouts
    var overrideWatchdog by remember(initial) { mutableStateOf(initial.overrideWatchdog) }
    var enableStallWatchdog by remember(initial) { mutableStateOf(initial.enableStallWatchdog) }
    var watchdogSensitivity by remember(initial) { mutableStateOf(initial.watchdogSensitivity) }
    var watchdogProbeSec by remember(initial) { mutableIntStateOf(initial.watchdogProbeSec) }
    var watchdogStallSec by remember(initial) { mutableIntStateOf(initial.watchdogStallSec) }
    var watchdogCooldownSec by remember(initial) { mutableIntStateOf(initial.watchdogCooldownSec) }

    var overrideReconnect by remember(initial) { mutableStateOf(initial.overrideReconnect) }
    var reconnectOnFailure by remember(initial) { mutableStateOf(initial.reconnectOnFailure) }
    var reconnectOnNetworkChange by remember(initial) { mutableStateOf(initial.reconnectOnNetworkChange) }
    var maxReconnectAttempts by remember(initial) { mutableIntStateOf(initial.maxReconnectAttempts) }
    var reconnectDelaySec by remember(initial) { mutableIntStateOf(initial.reconnectDelaySec) }
    var handshakeTimeoutSec by remember(initial) { mutableIntStateOf(initial.handshakeTimeoutSec) }
    var wsOpenTimeoutSec by remember(initial) { mutableIntStateOf(initial.wsOpenTimeoutSec) }
    var keepaliveIntervalSecOverride by remember(initial) { mutableStateOf(initial.keepaliveIntervalSecOverride) }
    var networkSwitchDebounceSecOverride by remember(initial) { mutableStateOf(initial.networkSwitchDebounceSecOverride) }
    var autoRotateSniOnFail by remember(initial) { mutableStateOf(initial.autoRotateSniOnFail) }
    var autoFailoverServer by remember(initial) { mutableStateOf(initial.autoFailoverServer) }

    // Custom user-added DNS servers first, then ALL built-in DNS servers (NO .take(5) limit!)
    val orderedDnsEntries = remember(savedDnsEntries, dnsSearchQuery) {
        val q = dnsSearchQuery.trim().lowercase()
        val sorted = savedDnsEntries.filter { !it.isBuiltIn } + savedDnsEntries.filter { it.isBuiltIn }
        if (q.isEmpty()) sorted else sorted.filter {
            it.title.lowercase().contains(q) || it.urlOrHost.lowercase().contains(q)
        }
    }

    val hostNavBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val dialogNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val safeNavBottom = maxOf(dialogNavBottom, hostNavBarBottom)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .padding(start = 12.dp, end = 12.dp, top = 18.dp, bottom = safeNavBottom + 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 580.dp)
                    .fillMaxHeight(0.92f)
                    .imePadding()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 8.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (initial.id.isBlank()) tr("Новый гибкий пресет", "New flexible preset") else tr("Настройка пресета", "Edit preset"),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = tr(
                                    "Отключайте ненужные блоки — они останутся «Не менять»",
                                    "Disable unused sections to leave those settings unchanged",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = tr("Закрыть", "Close"))
                        }
                    }

                    HorizontalDivider()

                    // Scrollable body
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(tr("Название (например: МТС Центр, Игры, Офис)", "Name (e.g., 5G City, Gaming, Office)")) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("preset_editor_name_input"),
                        )

                        Text(tr("Сеть / авто-триггер", "Network / auto-trigger"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            NetworkPresetTrigger.entries.forEach { t ->
                                val tLabel = t.localizedTitle()
                                FilterChip(
                                    selected = trigger == t,
                                    onClick = { trigger = t },
                                    label = { Text(tLabel) },
                                    modifier = Modifier.testTag("preset_trigger_${t.name}"),
                                )
                            }
                        }

                        PresetToggleRow(
                            title = tr("Применять поверх ручных фиксаций", "Apply over manual locks"),
                            subtitle = tr(
                                "При ручном запуске этого пресета снимает замки и применяет все включённые ниже блоки",
                                "Unlocks pinned parameters and applies all enabled sections below when triggered manually",
                            ),
                            checked = forceOverrideManualLocks,
                            onCheckedChange = { forceOverrideManualLocks = it },
                            testTag = "preset_force_locks_toggle",
                        )

                        HorizontalDivider()

                        // 1. Server binding (optional!)
                        Text(tr("Сервер в пресете", "Preset server"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = preferredServerId == null,
                                onClick = {
                                    preferredServerId = null
                                    showServerList = false
                                },
                                label = { Text(tr("Не менять", "Keep current")) },
                            )
                            FilterChip(
                                selected = preferredServerId == -1L,
                                onClick = {
                                    preferredServerId = -1L
                                    showServerList = false
                                },
                                label = { Text(tr("Авто (лучший пинг)", "Auto (lowest ping)")) },
                            )
                            if (enabledServers.isNotEmpty()) {
                                val chosenServer = enabledServers.firstOrNull { it.id == preferredServerId }
                                FilterChip(
                                    selected = (preferredServerId ?: 0L) > 0L || showServerList,
                                    onClick = { showServerList = !showServerList },
                                    label = {
                                        Text(
                                            chosenServer?.cleanName?.take(22) ?: tr("Выбрать узел (${enabledServers.size})", "Pick node (${enabledServers.size})"),
                                        )
                                    },
                                )
                            }
                        }
                        if (showServerList && enabledServers.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 180.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    enabledServers.forEach { srv ->
                                        val isSel = preferredServerId == srv.id
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    preferredServerId = srv.id
                                                    showServerList = false
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = srv.cleanDisplayTitle,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (isSel) {
                                                Icon(
                                                    Icons.Rounded.Check,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider()

                        // 2. Bypass & TLS Profile (all 15 profiles!)
                        PresetSectionToggleHeader(
                            title = tr("Обход блокировок, TLS и SNI", "Censorship bypass, TLS & SNI"),
                            checked = overrideBypass,
                            onCheckedChange = { overrideBypass = it },
                            testTag = "preset_override_bypass_toggle",
                        )
                        if (overrideBypass) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                BypassMethod.entries.forEach { m ->
                                    val mLabel = m.localizedShortTitle()
                                    FilterChip(
                                        selected = bypassMethod == m,
                                        onClick = { bypassMethod = m },
                                        label = { Text(mLabel) },
                                        modifier = Modifier.testTag("preset_bypass_${m.name}"),
                                    )
                                }
                            }

                            if (bypassMethod == BypassMethod.SNI_REALITY) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = tr(
                                            "Отпечаток браузера (${SniSpoofingMode.entries.size} профилей)",
                                            "Browser fingerprint (${SniSpoofingMode.entries.size} profiles)",
                                        ),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                    TextButton(onClick = { showAllTlsProfiles = !showAllTlsProfiles }) {
                                        Text(
                                            if (showAllTlsProfiles) {
                                                tr("Свернуть", "Collapse")
                                            } else {
                                                tr("Показать все ${SniSpoofingMode.entries.size}", "Show all ${SniSpoofingMode.entries.size}")
                                            },
                                        )
                                    }
                                }
                                val profilesToShow = if (showAllTlsProfiles) {
                                    SniSpoofingMode.entries
                                } else {
                                    listOf(
                                        SniSpoofingMode.SNI,
                                        SniSpoofingMode.SNI_REALITY_CHROME_149,
                                        SniSpoofingMode.SNI_REALITY_FIREFOX_151,
                                        SniSpoofingMode.SNI_REALITY_YANDEX_26_4,
                                        SniSpoofingMode.SNI_REALITY_SAFARI_26_5,
                                        sniSpoofingMode,
                                    ).distinct()
                                }
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    profilesToShow.forEach { profile ->
                                        val pLabel = profile.localizedTitle().substringBefore(" (")
                                        FilterChip(
                                            selected = sniSpoofingMode == profile,
                                            onClick = { sniSpoofingMode = profile },
                                            label = { Text(pLabel) },
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = sni,
                                onValueChange = { sni = it },
                                label = { Text(tr("SNI-домен", "SNI domain")) },
                                placeholder = { Text(VpnSettings.DEFAULT_SNI_DOMAIN) },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val pool = (BypassRegion.RUSSIA.recommendedSnis + sniCatalog)
                                                .map { it.trim().lowercase() }
                                                .filter { it.contains('.') && !it.equals(sni.trim(), ignoreCase = true) }
                                                .distinct()
                                            pool.randomOrNull()?.let { sni = it }
                                        },
                                        modifier = Modifier.testTag("preset_editor_random_sni_button"),
                                    ) {
                                        Icon(Icons.Rounded.Casino, contentDescription = tr("Случайный SNI", "Random SNI"))
                                    }
                                },
                                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("preset_editor_sni_input"),
                            )
                        }

                        HorizontalDivider()

                        // 3. Sliding tunnel
                        PresetSectionToggleHeader(
                            title = tr("Скользящий туннель", "Rolling tunnel"),
                            checked = overrideSlidingTunnel,
                            onCheckedChange = { overrideSlidingTunnel = it },
                            testTag = "preset_override_sliding_toggle",
                        )
                        if (overrideSlidingTunnel) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SlidingTunnelMode.entries.forEach { s ->
                                    val sLabel = s.localizedShortTitle()
                                    FilterChip(
                                        selected = slidingMode == s,
                                        onClick = { slidingMode = s },
                                        label = { Text(sLabel) },
                                        modifier = Modifier.testTag("preset_sliding_${s.name}"),
                                    )
                                }
                            }
                        }

                        HorizontalDivider()

                        // 4. Unlimited Flexible DNS (ALL saved & custom user DNS servers!)
                        Text(
                            text = tr("DNS в пресете (все серверы: ${savedDnsEntries.size})", "Preset DNS (all servers: ${savedDnsEntries.size})"),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = dnsModeOverride == null,
                                onClick = { dnsModeOverride = null },
                                label = { Text(tr("Не менять", "Keep current")) },
                                modifier = Modifier.testTag("preset_dns_mode_KEEP"),
                            )
                            DnsMode.entries.forEach { mode ->
                                val modeLabel = mode.localizedShortTitle()
                                FilterChip(
                                    selected = dnsModeOverride == mode,
                                    onClick = { dnsModeOverride = mode },
                                    label = { Text(modeLabel) },
                                    modifier = Modifier.testTag("preset_dns_mode_${mode.name}"),
                                )
                            }
                        }

                        if (dnsModeOverride == DnsMode.SMART_DNS_DOH) {
                            OutlinedTextField(
                                value = customDnsUrl,
                                onValueChange = { customDnsUrl = it },
                                label = { Text(tr("Адрес DoH / DoT Smart DNS", "Smart DNS DoH / DoT address")) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("preset_editor_custom_dns_input"),
                            )

                            OutlinedTextField(
                                value = dnsSearchQuery,
                                onValueChange = { dnsSearchQuery = it },
                                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                placeholder = { Text(tr("Поиск среди всех ${savedDnsEntries.size} DNS…", "Search across all ${savedDnsEntries.size} DNS…")) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("preset_editor_dns_search"),
                            )

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 220.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    orderedDnsEntries.forEach { entry ->
                                        val isSelected = customDnsUrl.trim().equals(entry.urlOrHost, ignoreCase = true)
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                Color.Transparent
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("preset_dns_entry_${entry.id}")
                                                .clickable { customDnsUrl = entry.urlOrHost },
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        Text(
                                                            text = entry.localizedTitle(),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = if (isSelected) {
                                                                MaterialTheme.colorScheme.onPrimaryContainer
                                                            } else {
                                                                MaterialTheme.colorScheme.onSurface
                                                            },
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        if (!entry.isBuiltIn) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                                            ) {
                                                                Text(
                                                                    text = tr("Свой", "Custom"),
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = entry.urlOrHost,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                                        color = if (isSelected) {
                                                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        },
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                if (isSelected) {
                                                    Icon(
                                                        Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.size(16.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (dnsModeOverride == DnsMode.CUSTOM_IPV4) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    value = dnsPrimaryOverride,
                                    onValueChange = { dnsPrimaryOverride = it },
                                    label = { Text(tr("Основной IPv4", "Primary IPv4")) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                                OutlinedTextField(
                                    value = dnsSecondaryOverride,
                                    onValueChange = { dnsSecondaryOverride = it },
                                    label = { Text(tr("Резервный IPv4", "Secondary IPv4")) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        HorizontalDivider()

                        // 5. MTU, Region, IPv6, Root
                        PresetSectionToggleHeader(
                            title = tr("MTU туннеля ($tunMtu байт)", "Tunnel MTU ($tunMtu bytes)"),
                            checked = overrideMtu,
                            onCheckedChange = { overrideMtu = it },
                            testTag = "preset_override_mtu_toggle",
                        )
                        if (overrideMtu) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                VpnSettings.MTU_PRESETS.forEach { m ->
                                    FilterChip(
                                        selected = tunMtu == m,
                                        onClick = { tunMtu = m },
                                        label = { Text("$m") },
                                        modifier = Modifier.testTag("preset_mtu_$m"),
                                    )
                                }
                            }
                            Slider(
                                value = tunMtu.toFloat(),
                                onValueChange = { tunMtu = (it / 10f).roundToInt() * 10 },
                                valueRange = 1280f..1500f,
                            )
                        }

                        Text(tr("Регион прямого обхода", "Direct bypass region"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = bypassRegionOverride == null,
                                onClick = { bypassRegionOverride = null },
                                label = { Text(tr("Не менять", "Keep current")) },
                            )
                            BypassRegion.selectableEntries.forEach { region ->
                                val rLabel = region.localizedTitle().substringBefore(" (")
                                FilterChip(
                                    selected = bypassRegionOverride == region,
                                    onClick = { bypassRegionOverride = region },
                                    label = { Text(rLabel) },
                                )
                            }
                        }
                        Text(
                            text = tr(
                                "Защита РФ (.ru / банки / Госуслуги) закреплена постоянно в каждом пресете.",
                                "RU protection (.ru / banks / gov services) stays permanently enabled in every preset.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(tr("Режим раздельного туннелирования", "Split tunneling mode"), style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = splitTunnelModeOverride == null,
                                onClick = { splitTunnelModeOverride = null },
                                label = { Text(tr("Не менять", "Keep current")) },
                            )
                            SplitTunnelMode.entries.forEach { mode ->
                                val mLabel = mode.localizedTitle()
                                FilterChip(
                                    selected = splitTunnelModeOverride == mode,
                                    onClick = { splitTunnelModeOverride = mode },
                                    label = { Text(mLabel) },
                                )
                            }
                        }

                        Text(tr("Сортировка списка серверов", "Server list sorting"), style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilterChip(
                                selected = serverSortOrderOverride == null,
                                onClick = { serverSortOrderOverride = null },
                                label = { Text(tr("Не менять", "Keep current")) },
                            )
                            ServerSortOrder.entries.forEach { order ->
                                val oLabel = order.localizedTitle()
                                FilterChip(
                                    selected = serverSortOrderOverride == order,
                                    onClick = { serverSortOrderOverride = order },
                                    label = { Text(oLabel) },
                                )
                            }
                        }

                        Text(tr("Интервал авто-пинга серверов", "Server auto-ping interval"), style = MaterialTheme.typography.labelMedium)
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            listOf(
                                null to tr("Не менять", "Keep"),
                                0 to tr("Выкл", "Off"),
                                30 to tr("30с", "30s"),
                                60 to tr("60с", "60s"),
                                120 to tr("120с", "120s"),
                                300 to tr("300с", "300s"),
                            ).forEach { (sec, lbl) ->
                                FilterChip(
                                    selected = autoPingIntervalSecOverride == sec,
                                    onClick = { autoPingIntervalSecOverride = sec },
                                    label = { Text(lbl) },
                                )
                            }
                        }

                        TriStateChipRow(
                            title = tr("Поддержка IPv6 в пресете", "IPv6 support in preset"),
                            value = allowIpv6Override,
                            onChange = { allowIpv6Override = it },
                        )

                        if (isRootAvailable) {
                            TriStateChipRow(
                                title = tr("Root Stealth (fptn0) в пресете", "Root Stealth (fptn0) in preset"),
                                value = useRootOverride,
                                onChange = { useRootOverride = it },
                            )
                        }

                        HorizontalDivider()

                        // 6. Stall Watchdog
                        PresetSectionToggleHeader(
                            title = tr("Сторож зависаний (Watchdog)", "Stall watchdog"),
                            checked = overrideWatchdog,
                            onCheckedChange = { overrideWatchdog = it },
                            testTag = "preset_override_watchdog_toggle",
                        )
                        if (overrideWatchdog) {
                            PresetToggleRow(
                                title = tr("Включить сторож (0 Б/с)", "Enable watchdog (0 B/s)"),
                                subtitle = tr(
                                    "Двойные пробы (UDP:53 DNS + ICMP) и авто-восстановление сессии",
                                    "Dual probes (UDP:53 DNS + ICMP) with automatic session recovery",
                                ),
                                checked = enableStallWatchdog,
                                onCheckedChange = { enableStallWatchdog = it },
                                testTag = "preset_watchdog_toggle",
                            )
                            if (enableStallWatchdog) {
                                Text(tr("Чувствительность сторожа", "Watchdog sensitivity"), style = MaterialTheme.typography.labelMedium)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    FilterChip(
                                        selected = watchdogSensitivity == null,
                                        onClick = { watchdogSensitivity = null },
                                        label = { Text(tr("Не менять", "Keep current")) },
                                    )
                                    WatchdogSensitivity.entries.forEach { sens ->
                                        val sLabel = sens.localizedShortTitle()
                                        FilterChip(
                                            selected = watchdogSensitivity == sens,
                                            onClick = { watchdogSensitivity = sens },
                                            label = { Text(sLabel) },
                                        )
                                    }
                                }
                                Text(
                                    tr(
                                        "Пробные пакеты (UDP:53 + ICMP) через: $watchdogProbeSec сек",
                                        "Probe packets (UDP:53 + ICMP) after: $watchdogProbeSec sec",
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = watchdogProbeSec.toFloat(),
                                    onValueChange = { value ->
                                        val nextProbe = value.roundToInt().coerceIn(2, 25)
                                        watchdogProbeSec = nextProbe
                                        if (watchdogStallSec < nextProbe + 2) {
                                            watchdogStallSec = (nextProbe + 2).coerceAtMost(55)
                                        }
                                    },
                                    valueRange = 2f..25f,
                                    modifier = Modifier.testTag("preset_watchdog_probe_slider"),
                                )
                                Text(
                                    tr(
                                        "Сброс зависшей сессии через: $watchdogStallSec сек",
                                        "Reset stalled session after: $watchdogStallSec sec",
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = watchdogStallSec.toFloat(),
                                    onValueChange = { value ->
                                        val nextStall = value.roundToInt().coerceIn(4, 55)
                                        watchdogStallSec = nextStall
                                        if (watchdogProbeSec > nextStall - 2) {
                                            watchdogProbeSec = (nextStall - 2).coerceAtLeast(2)
                                        }
                                    },
                                    valueRange = 4f..55f,
                                    modifier = Modifier.testTag("preset_watchdog_stall_slider"),
                                )
                                Text(
                                    tr(
                                        "Пауза после сброса (cooldown): $watchdogCooldownSec сек",
                                        "Post-reset cooldown: $watchdogCooldownSec sec",
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Slider(
                                    value = watchdogCooldownSec.toFloat(),
                                    onValueChange = { watchdogCooldownSec = it.roundToInt().coerceIn(10, 90) },
                                    valueRange = 10f..90f,
                                )
                            }
                        }

                        HorizontalDivider()

                        // 7. Reconnect, Timeouts, Keepalive & Debounce
                        PresetSectionToggleHeader(
                            title = tr("Переподключение, Keepalive и таймауты", "Reconnect, Keepalive & timeouts"),
                            checked = overrideReconnect,
                            onCheckedChange = { overrideReconnect = it },
                            testTag = "preset_override_reconnect_toggle",
                        )
                        if (overrideReconnect) {
                            PresetToggleRow(
                                title = tr("Автопереподключение при обрыве", "Auto-reconnect on disconnect"),
                                subtitle = null,
                                checked = reconnectOnFailure,
                                onCheckedChange = { reconnectOnFailure = it },
                                testTag = "preset_reconnect_failure_toggle",
                            )
                            PresetToggleRow(
                                title = tr("Переподключать при смене сети", "Reconnect on network change"),
                                subtitle = null,
                                checked = reconnectOnNetworkChange,
                                onCheckedChange = { reconnectOnNetworkChange = it },
                                testTag = "preset_reconnect_network_toggle",
                            )
                            Text(tr("Keepalive-пакеты (CGNAT)", "Keepalive packets (CGNAT)"), style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf(
                                    null to tr("Не менять", "Keep"),
                                    0 to tr("Выкл", "Off"),
                                    15 to tr("15с", "15s"),
                                    20 to tr("20с", "20s"),
                                    30 to tr("30с", "30s"),
                                    45 to tr("45с", "45s"),
                                ).forEach { (sec, lbl) ->
                                    FilterChip(
                                        selected = keepaliveIntervalSecOverride == sec,
                                        onClick = { keepaliveIntervalSecOverride = sec },
                                        label = { Text(lbl) },
                                    )
                                }
                            }
                            Text(tr("Задержка смены сети (debounce)", "Network change debounce"), style = MaterialTheme.typography.labelMedium)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                listOf(
                                    null to tr("Не менять", "Keep"),
                                    1 to tr("1с", "1s"),
                                    2 to tr("2с", "2s"),
                                    3 to tr("3с", "3s"),
                                    4 to tr("4с", "4s"),
                                    6 to tr("6с", "6s"),
                                ).forEach { (sec, lbl) ->
                                    FilterChip(
                                        selected = networkSwitchDebounceSecOverride == sec,
                                        onClick = { networkSwitchDebounceSecOverride = sec },
                                        label = { Text(lbl) },
                                    )
                                }
                            }
                            Text(
                                tr("Макс. попыток переподключения: $maxReconnectAttempts", "Max reconnect attempts: $maxReconnectAttempts"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = maxReconnectAttempts.toFloat(),
                                onValueChange = { maxReconnectAttempts = it.roundToInt().coerceIn(3, 50) },
                                valueRange = 3f..50f,
                                modifier = Modifier.testTag("preset_max_reconnect_slider"),
                            )
                            Text(
                                tr("Пауза между попытками: $reconnectDelaySec сек", "Delay between attempts: $reconnectDelaySec sec"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = reconnectDelaySec.toFloat(),
                                onValueChange = { reconnectDelaySec = it.roundToInt().coerceIn(1, 10) },
                                valueRange = 1f..10f,
                                steps = 8,
                            )
                            Text(
                                tr("Таймаут TLS-рукопожатия: $handshakeTimeoutSec сек", "TLS handshake timeout: $handshakeTimeoutSec sec"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = handshakeTimeoutSec.toFloat(),
                                onValueChange = { handshakeTimeoutSec = it.roundToInt().coerceIn(4, 20) },
                                valueRange = 4f..20f,
                                steps = 15,
                            )
                            Text(
                                tr("Таймаут WebSocket-туннеля: $wsOpenTimeoutSec сек", "WebSocket tunnel timeout: $wsOpenTimeoutSec sec"),
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Slider(
                                value = wsOpenTimeoutSec.toFloat(),
                                onValueChange = { wsOpenTimeoutSec = it.roundToInt().coerceIn(5, 30) },
                                valueRange = 5f..30f,
                            )
                            TriStateChipRow(
                                title = tr("Авто-ротация SNI при блокировке", "Auto-rotate SNI on block"),
                                value = autoRotateSniOnFail,
                                onChange = { autoRotateSniOnFail = it },
                            )
                            TriStateChipRow(
                                title = tr("Авто-переключение на резервный сервер", "Auto-failover to backup server"),
                                value = autoFailoverServer,
                                onChange = { autoFailoverServer = it },
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    HorizontalDivider()

                    // Pinned action buttons (always visible above gesture bar)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(tr("Отмена", "Cancel"))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                onSave(
                                    initial.copy(
                                        name = name.trim().ifBlank { tr(isRu, "Мой пресет", "My preset") },
                                        trigger = trigger,
                                        overrideBypass = overrideBypass,
                                        bypassMethod = bypassMethod,
                                        sniSpoofingMode = sniSpoofingMode,
                                        customSni = sni.trim().ifBlank { VpnSettings.DEFAULT_SNI_DOMAIN },
                                        overrideSlidingTunnel = overrideSlidingTunnel,
                                        slidingTunnelMode = slidingMode,
                                        dnsModeOverride = dnsModeOverride,
                                        enableCustomDnsUrl = dnsModeOverride == DnsMode.SMART_DNS_DOH,
                                        customDnsUrl = customDnsUrl.trim(),
                                        dnsPrimaryOverride = dnsPrimaryOverride.trim(),
                                        dnsSecondaryOverride = dnsSecondaryOverride.trim(),
                                        overrideWatchdog = overrideWatchdog,
                                        enableStallWatchdog = enableStallWatchdog,
                                        watchdogSensitivity = watchdogSensitivity,
                                        watchdogProbeSec = watchdogProbeSec,
                                        watchdogStallSec = watchdogStallSec,
                                        watchdogCooldownSec = watchdogCooldownSec,
                                        overrideReconnect = overrideReconnect,
                                        reconnectOnFailure = reconnectOnFailure,
                                        reconnectOnNetworkChange = reconnectOnNetworkChange,
                                        maxReconnectAttempts = maxReconnectAttempts,
                                        reconnectDelaySec = reconnectDelaySec,
                                        handshakeTimeoutSec = handshakeTimeoutSec,
                                        wsOpenTimeoutSec = wsOpenTimeoutSec,
                                        keepaliveIntervalSecOverride = keepaliveIntervalSecOverride,
                                        networkSwitchDebounceSecOverride = networkSwitchDebounceSecOverride,
                                        autoRotateSniOnFail = autoRotateSniOnFail,
                                        autoFailoverServer = autoFailoverServer,
                                        overrideMtu = overrideMtu,
                                        tunMtu = tunMtu,
                                        smartRfRouting = smartRfRouting,
                                        smartRfRoutingOverride = smartRfRoutingOverride,
                                        bypassRegionOverride = bypassRegionOverride,
                                        splitTunnelModeOverride = splitTunnelModeOverride,
                                        autoPingIntervalSecOverride = autoPingIntervalSecOverride,
                                        serverSortOrderOverride = serverSortOrderOverride,
                                        allowIpv6Override = allowIpv6Override,
                                        useRootModeOverride = useRootOverride,
                                        preferredServerId = preferredServerId,
                                        forceOverrideManualLocks = forceOverrideManualLocks,
                                    ),
                                )
                            },
                            modifier = Modifier.testTag("preset_editor_save_button"),
                        ) {
                            Text(tr("Сохранить", "Save"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetSectionToggleHeader(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (checked) {
                    tr("Применяется этим пресетом", "Applied by this preset")
                } else {
                    tr("Не менять (оставит текущую настройку)", "Keep unchanged (uses current setting)")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun PresetToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriStateChipRow(
    title: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            FilterChip(
                selected = value == null,
                onClick = { onChange(null) },
                label = { Text(tr("Не менять", "Keep current")) },
            )
            FilterChip(
                selected = value == true,
                onClick = { onChange(true) },
                label = { Text(tr("Включить", "Enable")) },
            )
            FilterChip(
                selected = value == false,
                onClick = { onChange(false) },
                label = { Text(tr("Выключить", "Disable")) },
            )
        }
    }
}
