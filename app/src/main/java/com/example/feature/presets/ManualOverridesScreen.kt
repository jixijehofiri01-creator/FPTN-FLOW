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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.core.ui.components.SmoothSettingsSlider
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
import com.example.domain.dns.DnsEndpointParser
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsMode
import com.example.domain.model.FptnServer
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.VpnSettings
import com.example.domain.vpn.WatchdogSensitivity
import com.example.core.haptics.HapticEngine
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.localizedAutoDescription
import com.example.core.ui.localization.localizedCategory
import com.example.core.ui.localization.localizedName
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.feature.shared.FptnUiState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualOverridesScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onResetOverride: (ManualOverrideKey) -> Unit,
    onResetAll: () -> Unit,
    onSelectAutoServer: () -> Unit = {},
    onSelectServer: (FptnServer) -> Unit = {},
    onApplyPreset: (String) -> Unit = {},
    onSelectDnsMode: (DnsMode) -> Unit = {},
    onSelectDnsEntry: (SavedDnsEntry) -> Unit = {},
    onSaveIpv4Dns: (String, String) -> Unit = { _, _ -> },
    onSelectBypassMethod: (BypassMethod) -> Unit = {},
    onSelectSniSpoofingMode: (SniSpoofingMode) -> Unit = {},
    onSaveCustomSni: (String) -> Unit = {},
    onPickRandomSni: () -> Unit = {},
    onSelectSlidingTunnel: (SlidingTunnelMode) -> Unit = {},
    onUpdateTunMtu: (Int) -> Unit = {},
    onToggleStallWatchdog: (Boolean) -> Unit = {},
    onSelectWatchdogSensitivity: (WatchdogSensitivity) -> Unit = {},
    onUpdateWatchdogProbeSec: (Int) -> Unit = {},
    onUpdateWatchdogStallSec: (Int) -> Unit = {},
    onUpdateWatchdogCooldownSec: (Int) -> Unit = {},
    onToggleReconnectOnFailure: (Boolean) -> Unit = {},
    onToggleReconnectOnNetworkChange: (Boolean) -> Unit = {},
    onUpdateMaxReconnectAttempts: (Int) -> Unit = {},
    onUpdateKeepaliveIntervalSec: (Int) -> Unit = {},
    onUpdateNetworkSwitchDebounceSec: (Int) -> Unit = {},
    onSelectBypassRegion: (BypassRegion) -> Unit = {},
    onToggleSmartRfRouting: (Boolean) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val view = LocalView.current
    val settings = uiState.settings
    val activeOverrides = remember(settings.manualOverrides) {
        ManualOverrideKey.entries.filter { it in settings.manualOverrides }
    }
    val autoParameters = remember(settings.manualOverrides) {
        ManualOverrideKey.entries.filter { it !in settings.manualOverrides }
    }
    var editingKey by remember { mutableStateOf<ManualOverrideKey?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_overrides_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("overrides_back_button"),
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
                .padding(horizontal = 20.dp)
                .testTag("overrides_lazy_list"),
            contentPadding = PaddingValues(top = 4.dp, bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 600.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.62f),
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.PushPin,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (activeOverrides.isEmpty()) {
                                    tr("Зафиксированных параметров: 0", "Locked parameters: 0")
                                } else {
                                    tr(
                                        "Неприкосаемых настроек: ${activeOverrides.size}",
                                        "Pinned settings: ${activeOverrides.size}",
                                    )
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                        Text(
                            text = tr(
                                "Автопереключение пресетов (Wi-Fi ↔ Мобильная сеть) продолжает работать, а зафиксированные ниже параметры остаются неприкосаемыми. Любой пункт можно изменить прямо здесь или вернуть на автомат.",
                                "Auto-switching presets (Wi-Fi ↔ Cellular) keeps running while pinned parameters below stay untouched. You can adjust any item right here or return it to Auto.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f),
                        )
                        if (activeOverrides.isNotEmpty()) {
                            Button(
                                onClick = {
                                    hapticEngine.performSuccess(view)
                                    onResetAll()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("overrides_reset_all_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.RestartAlt,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(tr("Сбросить все на автомат", "Reset all to Auto"))
                            }
                        }
                    }
                }
            }

            if (activeOverrides.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp)
                            .testTag("overrides_empty_card"),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(34.dp),
                            )
                            Text(
                                text = tr("Все параметры сейчас на автомате", "All parameters are currently on Auto"),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = tr(
                                    "Ниже можно прямо сейчас изменить и закрепить любую настройку вручную — автосмена пресетов Wi-Fi ↔ 4G/5G при этом сохранится.",
                                    "You can configure and pin any setting below — automatic Wi-Fi ↔ 4G/5G preset switching stays active.",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                items(activeOverrides, key = { "pinned_${it.name}" }) { key ->
                    val currentValue = formatCurrentOverrideValue(key, uiState)
                    OverrideCardItem(
                        key = key,
                        currentValue = currentValue,
                        isPinned = true,
                        onEdit = {
                            hapticEngine.performNavigationClick(view)
                            editingKey = key
                        },
                        onReset = {
                            hapticEngine.performToggle(view, enabled = false)
                            onResetOverride(key)
                        },
                    )
                }
            }

            if (autoParameters.isNotEmpty()) {
                item {
                    Text(
                        text = tr("Остальные параметры (на автомате)", "Other parameters (Auto)"),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 600.dp)
                            .padding(top = 6.dp),
                    )
                }
                items(autoParameters, key = { "auto_${it.name}" }) { key ->
                    val currentValue = formatCurrentOverrideValue(key, uiState)
                    OverrideCardItem(
                        key = key,
                        currentValue = currentValue,
                        isPinned = false,
                        onEdit = {
                            hapticEngine.performNavigationClick(view)
                            editingKey = key
                        },
                        onReset = null,
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    editingKey?.let { key ->
        OverrideQuickEditorDialog(
            key = key,
            uiState = uiState,
            hapticEngine = hapticEngine,
            onDismiss = { editingKey = null },
            onSelectAutoServer = onSelectAutoServer,
            onSelectServer = onSelectServer,
            onApplyPreset = onApplyPreset,
            onSelectDnsMode = onSelectDnsMode,
            onSelectDnsEntry = onSelectDnsEntry,
            onSaveIpv4Dns = onSaveIpv4Dns,
            onSelectBypassMethod = onSelectBypassMethod,
            onSelectSniSpoofingMode = onSelectSniSpoofingMode,
            onSaveCustomSni = onSaveCustomSni,
            onPickRandomSni = onPickRandomSni,
            onSelectSlidingTunnel = onSelectSlidingTunnel,
            onUpdateTunMtu = onUpdateTunMtu,
            onToggleStallWatchdog = onToggleStallWatchdog,
            onSelectWatchdogSensitivity = onSelectWatchdogSensitivity,
            onUpdateWatchdogProbeSec = onUpdateWatchdogProbeSec,
            onUpdateWatchdogStallSec = onUpdateWatchdogStallSec,
            onUpdateWatchdogCooldownSec = onUpdateWatchdogCooldownSec,
            onToggleReconnectOnFailure = onToggleReconnectOnFailure,
            onToggleReconnectOnNetworkChange = onToggleReconnectOnNetworkChange,
            onUpdateMaxReconnectAttempts = onUpdateMaxReconnectAttempts,
            onUpdateKeepaliveIntervalSec = onUpdateKeepaliveIntervalSec,
            onUpdateNetworkSwitchDebounceSec = onUpdateNetworkSwitchDebounceSec,
            onSelectBypassRegion = onSelectBypassRegion,
            onToggleSmartRfRouting = onToggleSmartRfRouting,
        )
    }
}

@Composable
private fun OverrideCardItem(
    key: ManualOverrideKey,
    currentValue: String,
    isPinned: Boolean,
    onEdit: () -> Unit,
    onReset: (() -> Unit)?,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .testTag(if (isPinned) "override_card_${key.name}" else "override_auto_card_${key.name}"),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isPinned) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerLowest
            },
        ),
        border = if (isPinned) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isPinned) {
                        Icon(
                            imageVector = Icons.Rounded.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                    Text(
                        text = key.localizedTitle(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isPinned) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh
                    },
                ) {
                    Text(
                        text = if (isPinned) key.localizedCategory() else tr("Авто", "Auto"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isPinned) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = currentValue,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                    color = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            Text(
                text = tr("Автомат: ${key.localizedAutoDescription()}", "Auto: ${key.localizedAutoDescription()}"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.testTag(
                        if (isPinned) "override_edit_${key.name}" else "override_pin_edit_${key.name}",
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isPinned) tr("Изменить", "Edit") else tr("Настроить", "Configure"))
                }
                if (onReset != null) {
                    FilledTonalButton(
                        onClick = onReset,
                        modifier = Modifier.testTag("override_reset_${key.name}"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(tr("На автомат", "Reset to Auto"))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OverrideQuickEditorDialog(
    key: ManualOverrideKey,
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onDismiss: () -> Unit,
    onSelectAutoServer: () -> Unit,
    onSelectServer: (FptnServer) -> Unit,
    onApplyPreset: (String) -> Unit,
    onSelectDnsMode: (DnsMode) -> Unit,
    onSelectDnsEntry: (SavedDnsEntry) -> Unit,
    onSaveIpv4Dns: (String, String) -> Unit,
    onSelectBypassMethod: (BypassMethod) -> Unit,
    onSelectSniSpoofingMode: (SniSpoofingMode) -> Unit,
    onSaveCustomSni: (String) -> Unit,
    onPickRandomSni: () -> Unit,
    onSelectSlidingTunnel: (SlidingTunnelMode) -> Unit,
    onUpdateTunMtu: (Int) -> Unit,
    onToggleStallWatchdog: (Boolean) -> Unit,
    onSelectWatchdogSensitivity: (WatchdogSensitivity) -> Unit,
    onUpdateWatchdogProbeSec: (Int) -> Unit,
    onUpdateWatchdogStallSec: (Int) -> Unit,
    onUpdateWatchdogCooldownSec: (Int) -> Unit,
    onToggleReconnectOnFailure: (Boolean) -> Unit,
    onToggleReconnectOnNetworkChange: (Boolean) -> Unit,
    onUpdateMaxReconnectAttempts: (Int) -> Unit,
    onUpdateKeepaliveIntervalSec: (Int) -> Unit,
    onUpdateNetworkSwitchDebounceSec: (Int) -> Unit,
    onSelectBypassRegion: (BypassRegion) -> Unit,
    onToggleSmartRfRouting: (Boolean) -> Unit,
) {
    val view = LocalView.current
    val isRu = LocalIsRussian.current
    val settings = uiState.settings

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
                .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = safeNavBottom + 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 540.dp)
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
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = key.localizedTitle(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = tr(
                                    "Изменения применяются сразу и закрепляются от авто-перезаписи",
                                    "Changes apply immediately and are pinned against auto-overwrite",
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

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        when (key) {
                            ManualOverrideKey.SERVER -> {
                                FilterChip(
                                    selected = settings.autoSelectBestServer,
                                    onClick = {
                                        hapticEngine.performToggle(view, enabled = true)
                                        onSelectAutoServer()
                                        onDismiss()
                                    },
                                    label = { Text(tr("Авто (лучший пинг)", "Auto (lowest ping)")) },
                                )
                                uiState.enabledServers.forEach { server ->
                                    val selected = !settings.autoSelectBestServer && settings.selectedServerId == server.id
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                hapticEngine.performSuccess(view)
                                                onSelectServer(server)
                                                onDismiss()
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = server.cleanDisplayTitle,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            server.pingMs?.let { ping ->
                                                Text(
                                                    text = tr("$ping мс", "$ping ms"),
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                            }
                                            if (selected) {
                                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            ManualOverrideKey.PRESET -> {
                                settings.networkPresets.forEach { preset ->
                                    val selected = settings.activePresetId == preset.id
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                hapticEngine.performSuccess(view)
                                                onApplyPreset(preset.id)
                                                onDismiss()
                                            },
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = preset.localizedName(),
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    text = tr(
                                                        "Триггер: ${preset.trigger.localizedTitle()} • ${preset.bypassMethod.localizedShortTitle()} • MTU ${preset.tunMtu}",
                                                        "Trigger: ${preset.trigger.localizedTitle()} • ${preset.bypassMethod.localizedShortTitle()} • MTU ${preset.tunMtu}",
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            if (selected) {
                                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            ManualOverrideKey.DNS -> {
                                var dnsSearch by remember { mutableStateOf("") }
                                var primaryIp by remember(settings.dnsPrimary) { mutableStateOf(settings.dnsPrimary) }
                                var secondaryIp by remember(settings.dnsSecondary) { mutableStateOf(settings.dnsSecondary) }

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    DnsMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = settings.dnsMode == mode,
                                            onClick = {
                                                hapticEngine.performToggle(view, enabled = true)
                                                onSelectDnsMode(mode)
                                            },
                                            label = { Text(mode.localizedShortTitle()) },
                                        )
                                    }
                                }
                                if (settings.dnsMode == DnsMode.SMART_DNS_DOH) {
                                    OutlinedTextField(
                                        value = dnsSearch,
                                        onValueChange = { dnsSearch = it },
                                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                        placeholder = {
                                            Text(
                                                tr(
                                                    "Поиск среди всех ${settings.savedDnsEntries.size} DNS…",
                                                    "Search all ${settings.savedDnsEntries.size} DNS servers…",
                                                ),
                                            )
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    val filtered = remember(settings.savedDnsEntries, dnsSearch) {
                                        val q = dnsSearch.trim().lowercase()
                                        val ordered = settings.savedDnsEntries.filter { !it.isBuiltIn } +
                                            settings.savedDnsEntries.filter { it.isBuiltIn }
                                        if (q.isEmpty()) ordered else ordered.filter {
                                            it.title.lowercase().contains(q) || it.urlOrHost.lowercase().contains(q)
                                        }
                                    }
                                    filtered.forEach { entry ->
                                        val selected = settings.customDnsUrl.equals(entry.urlOrHost, ignoreCase = true)
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    hapticEngine.performSuccess(view)
                                                    onSelectDnsEntry(entry)
                                                    onDismiss()
                                                },
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(entry.localizedTitle(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(
                                                        entry.urlOrHost,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                if (selected) {
                                                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                                }
                                            }
                                        }
                                    }
                                } else if (settings.dnsMode == DnsMode.CUSTOM_IPV4) {
                                    OutlinedTextField(
                                        value = primaryIp,
                                        onValueChange = { primaryIp = it },
                                        label = { Text(tr("Основной IPv4 DNS", "Primary IPv4 DNS")) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = secondaryIp,
                                        onValueChange = { secondaryIp = it },
                                        label = { Text(tr("Резервный IPv4 DNS", "Secondary IPv4 DNS")) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Button(
                                        onClick = {
                                            hapticEngine.performSuccess(view)
                                            onSaveIpv4Dns(primaryIp, secondaryIp)
                                            onDismiss()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(tr("Применить IPv4 DNS", "Apply IPv4 DNS"))
                                    }
                                }
                            }

                            ManualOverrideKey.BYPASS_METHOD -> {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    BypassMethod.entries.forEach { method ->
                                        FilterChip(
                                            selected = settings.bypassMethod == method,
                                            onClick = {
                                                hapticEngine.performToggle(view, enabled = true)
                                                onSelectBypassMethod(method)
                                            },
                                            label = { Text(method.localizedShortTitle()) },
                                        )
                                    }
                                }
                                if (settings.bypassMethod == BypassMethod.SNI_REALITY) {
                                    Text(tr("TLS-отпечаток браузера", "Browser TLS fingerprint"), style = MaterialTheme.typography.labelLarge)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        SniSpoofingMode.entries.forEach { profile ->
                                            FilterChip(
                                                selected = settings.sniSpoofingMode == profile,
                                                onClick = {
                                                    hapticEngine.performToggle(view, enabled = true)
                                                    onSelectSniSpoofingMode(profile)
                                                },
                                                label = { Text(profile.localizedTitle().substringBefore(" (")) },
                                            )
                                        }
                                    }
                                }
                            }

                            ManualOverrideKey.CUSTOM_SNI -> {
                                var sniDraft by remember(settings.customSni) { mutableStateOf(settings.customSni) }
                                OutlinedTextField(
                                    value = sniDraft,
                                    onValueChange = { sniDraft = it },
                                    label = { Text(tr("SNI-домен", "SNI domain")) },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            hapticEngine.performNavigationClick(view)
                                            onPickRandomSni()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Icon(Icons.Rounded.Casino, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(tr("Случайный", "Random"))
                                    }
                                    Button(
                                        onClick = {
                                            hapticEngine.performSuccess(view)
                                            onSaveCustomSni(sniDraft)
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(tr("Применить", "Apply"))
                                    }
                                }
                                if (settings.bypassRegion.recommendedSnis.isNotEmpty()) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        settings.bypassRegion.recommendedSnis.forEach { domain ->
                                            FilterChip(
                                                selected = settings.customSni.equals(domain, ignoreCase = true),
                                                onClick = {
                                                    sniDraft = domain
                                                    onSaveCustomSni(domain)
                                                },
                                                label = { Text(domain) },
                                            )
                                        }
                                    }
                                }
                            }

                            ManualOverrideKey.SLIDING_TUNNEL -> {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    SlidingTunnelMode.entries.forEach { mode ->
                                        FilterChip(
                                            selected = settings.slidingTunnelMode == mode,
                                            onClick = {
                                                hapticEngine.performToggle(view, enabled = true)
                                                onSelectSlidingTunnel(mode)
                                                onDismiss()
                                            },
                                            label = { Text(mode.localizedTitle()) },
                                        )
                                    }
                                }
                            }

                            ManualOverrideKey.TUN_MTU -> {
                                Text(
                                    tr("Текущий MTU: ${settings.tunMtu} байт", "Current MTU: ${settings.tunMtu} bytes"),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    VpnSettings.MTU_PRESETS.forEach { m ->
                                        FilterChip(
                                            selected = settings.tunMtu == m,
                                            onClick = {
                                                hapticEngine.performToggle(view, enabled = true)
                                                onUpdateTunMtu(m)
                                            },
                                            label = { Text("$m") },
                                        )
                                    }
                                }
                                SmoothOverrideSlider(
                                    labelForValue = { v -> "MTU: ${((v / 10f).roundToInt() * 10).coerceIn(1280, 1500)}" },
                                    value = settings.tunMtu.toFloat(),
                                    valueRange = 1280f..1500f,
                                    hapticEngine = hapticEngine,
                                    stepQuantum = 10,
                                    onCommit = { raw ->
                                        val snapped = ((raw / 10f).roundToInt() * 10).coerceIn(1280, 1500)
                                        if (snapped != settings.tunMtu) onUpdateTunMtu(snapped)
                                    },
                                )
                            }

                            ManualOverrideKey.WATCHDOG -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(tr("Сторож зависаний (0 Б/с)", "Stall watchdog (0 B/s)"), style = MaterialTheme.typography.titleSmall)
                                    Switch(
                                        checked = settings.enableStallWatchdog,
                                        onCheckedChange = {
                                            hapticEngine.performToggle(view, it)
                                            onToggleStallWatchdog(it)
                                        },
                                    )
                                }
                                if (settings.enableStallWatchdog) {
                                    Text(tr("Чувствительность", "Sensitivity"), style = MaterialTheme.typography.labelLarge)
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        WatchdogSensitivity.entries.forEach { sens ->
                                            FilterChip(
                                                selected = settings.watchdogSensitivity == sens,
                                                onClick = {
                                                    hapticEngine.performToggle(view, enabled = true)
                                                    onSelectWatchdogSensitivity(sens)
                                                },
                                                label = { Text(sens.localizedShortTitle()) },
                                            )
                                        }
                                    }
                                    SmoothOverrideSlider(
                                        labelForValue = { v ->
                                            val sec = v.roundToInt().coerceIn(2, 25)
                                            tr(isRu, "Проба через: $sec сек", "Probe after: ${sec}s")
                                        },
                                        value = settings.watchdogProbeSec.toFloat(),
                                        valueRange = 2f..25f,
                                        hapticEngine = hapticEngine,
                                        onCommit = { onUpdateWatchdogProbeSec(it.roundToInt().coerceIn(2, 25)) },
                                    )
                                    SmoothOverrideSlider(
                                        labelForValue = { v ->
                                            val sec = v.roundToInt().coerceIn(4, 55)
                                            tr(isRu, "Сброс через: $sec сек", "Reset after: ${sec}s")
                                        },
                                        value = settings.watchdogStallSec.toFloat(),
                                        valueRange = 4f..55f,
                                        hapticEngine = hapticEngine,
                                        onCommit = { onUpdateWatchdogStallSec(it.roundToInt().coerceIn(4, 55)) },
                                    )
                                    SmoothOverrideSlider(
                                        labelForValue = { v ->
                                            val sec = v.roundToInt().coerceIn(10, 90)
                                            tr(isRu, "Пауза (cooldown): $sec сек", "Cooldown: ${sec}s")
                                        },
                                        value = settings.watchdogCooldownSec.toFloat(),
                                        valueRange = 10f..90f,
                                        hapticEngine = hapticEngine,
                                        onCommit = { onUpdateWatchdogCooldownSec(it.roundToInt().coerceIn(10, 90)) },
                                    )
                                }
                            }

                            ManualOverrideKey.RECONNECT -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(tr("Автопереподключение при обрыве", "Auto-reconnect on drop"), style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = settings.reconnectOnFailure,
                                        onCheckedChange = { onToggleReconnectOnFailure(it) },
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(tr("Реконнект при смене сети", "Reconnect on network change"), style = MaterialTheme.typography.bodyMedium)
                                    Switch(
                                        checked = settings.reconnectOnNetworkChange,
                                        onCheckedChange = { onToggleReconnectOnNetworkChange(it) },
                                    )
                                }
                                SmoothOverrideSlider(
                                    labelForValue = { v ->
                                        val sec = v.roundToInt().coerceIn(0, 60)
                                        if (sec <= 0) {
                                            tr(isRu, "Keepalive (CGNAT): Выкл", "Keepalive (CGNAT): Off")
                                        } else {
                                            tr(isRu, "Keepalive (CGNAT): $sec сек", "Keepalive (CGNAT): ${sec}s")
                                        }
                                    },
                                    value = settings.keepaliveIntervalSec.toFloat(),
                                    valueRange = 0f..60f,
                                    hapticEngine = hapticEngine,
                                    onCommit = { onUpdateKeepaliveIntervalSec(it.roundToInt().coerceIn(0, 60)) },
                                )
                                SmoothOverrideSlider(
                                    labelForValue = { v ->
                                        val sec = v.roundToInt().coerceIn(1, 10)
                                        tr(isRu, "Задержка смены сети (debounce): $sec сек", "Network switch debounce: ${sec}s")
                                    },
                                    value = settings.networkSwitchDebounceSec.toFloat(),
                                    valueRange = 1f..10f,
                                    hapticEngine = hapticEngine,
                                    onCommit = { onUpdateNetworkSwitchDebounceSec(it.roundToInt().coerceIn(1, 10)) },
                                )
                                SmoothOverrideSlider(
                                    labelForValue = { v ->
                                        val n = v.roundToInt().coerceIn(3, 50)
                                        tr(isRu, "Макс. попыток: $n", "Max attempts: $n")
                                    },
                                    value = settings.maxReconnectAttempts.toFloat(),
                                    valueRange = 3f..50f,
                                    hapticEngine = hapticEngine,
                                    onCommit = { onUpdateMaxReconnectAttempts(it.roundToInt().coerceIn(3, 50)) },
                                )
                            }

                            ManualOverrideKey.ROUTING_REGION -> {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    BypassRegion.selectableEntries.forEach { region ->
                                        FilterChip(
                                            selected = BypassRegion.sanitize(settings.bypassRegion) == region,
                                            onClick = {
                                                hapticEngine.performToggle(view, enabled = true)
                                                onSelectBypassRegion(region)
                                            },
                                            label = { Text(region.localizedShortTitle()) },
                                        )
                                    }
                                }
                                Text(
                                    text = tr(
                                        "Умный обход РФ и .ru-доменов всегда активен (даже в Root-режиме) для защиты серверов FPTN.",
                                        "Smart RU bypass and .ru domains always stay active (even in Root mode) to safeguard FPTN servers.",
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(tr("Готово", "Done"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun formatCurrentOverrideValue(key: ManualOverrideKey, uiState: FptnUiState): String {
    val s = uiState.settings
    return when (key) {
        ManualOverrideKey.SERVER -> if (s.autoSelectBestServer) {
            val node = uiState.activeServer?.cleanDisplayTitle ?: tr("лучший узел", "best node")
            tr("Авто • $node", "Auto • $node")
        } else {
            uiState.activeServer?.cleanDisplayTitle ?: tr("Выбран вручную", "Manually selected")
        }
        ManualOverrideKey.PRESET -> {
            val preset = s.networkPresets.firstOrNull { it.id == s.activePresetId }
            preset?.localizedName() ?: tr("Авто по сети (Wi-Fi / 4G)", "Auto by network (Wi-Fi / 4G)")
        }
        ManualOverrideKey.DNS -> when (s.dnsMode) {
            DnsMode.AUTO_VPN -> tr("Авто (сервер FPTN)", "Auto (FPTN server)")
            DnsMode.SMART_DNS_DOH -> {
                val host = DnsEndpointParser.parse(s.customDnsUrl).getOrNull()?.host ?: s.customDnsUrl
                "Smart DNS • $host"
            }
            DnsMode.ADBLOCK_DNS -> tr("AdGuard DoH (блокировка рекламы)", "AdGuard DoH (ad blocking)")
            DnsMode.CUSTOM_IPV4 -> "IPv4 ${s.dnsPrimary} / ${s.dnsSecondary}"
        }
        ManualOverrideKey.BYPASS_METHOD -> "${s.bypassMethod.localizedShortTitle()} • ${s.effectiveCensorshipStrategyName()}"
        ManualOverrideKey.CUSTOM_SNI -> s.customSni
        ManualOverrideKey.SLIDING_TUNNEL -> "${s.slidingTunnelMode.localizedTitle()} (${s.slidingTunnelMode.nativeStrategyName})"
        ManualOverrideKey.TUN_MTU -> tr("${s.tunMtu} байт", "${s.tunMtu} bytes")
        ManualOverrideKey.WATCHDOG -> if (s.enableStallWatchdog) {
            val sens = s.watchdogSensitivity.localizedShortTitle()
            tr(
                "$sens • проба ${s.watchdogProbeSec}с / сброс ${s.watchdogStallSec}с / пауза ${s.watchdogCooldownSec}с",
                "$sens • probe ${s.watchdogProbeSec}s / reset ${s.watchdogStallSec}s / cooldown ${s.watchdogCooldownSec}s",
            )
        } else {
            tr("Выключен вручную", "Manually disabled")
        }
        ManualOverrideKey.RECONNECT -> {
            val ka = if (s.keepaliveIntervalSec <= 0) {
                tr("выкл", "off")
            } else {
                tr("${s.keepaliveIntervalSec}с", "${s.keepaliveIntervalSec}s")
            }
            tr(
                "Keepalive $ka • Debounce ${s.networkSwitchDebounceSec}с • Попыток ${s.maxReconnectAttempts}",
                "Keepalive $ka • Debounce ${s.networkSwitchDebounceSec}s • Max ${s.maxReconnectAttempts}",
            )
        }
        ManualOverrideKey.ROUTING_REGION -> {
            val region = s.bypassRegion.localizedTitle()
            val modeLabel = if (s.smartRfRouting) tr("Обход вкл", "Bypass on") else tr("Весь трафик", "All traffic")
            "$region • $modeLabel"
        }
    }
}

@Composable
private fun SmoothOverrideSlider(
    labelForValue: (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    hapticEngine: HapticEngine,
    stepQuantum: Int = 1,
    onCommit: (Float) -> Unit,
) {
    val view = LocalView.current
    var draftValue by remember { mutableFloatStateOf(value.coerceIn(valueRange.start, valueRange.endInclusive)) }
    var lastHapticStep by remember { mutableIntStateOf(value.roundToInt() / stepQuantum.coerceAtLeast(1)) }

    LaunchedEffect(value) {
        draftValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
        lastHapticStep = value.roundToInt() / stepQuantum.coerceAtLeast(1)
    }

    Text(text = labelForValue(draftValue), style = MaterialTheme.typography.labelMedium)
    SmoothSettingsSlider(
        value = value,
        valueRange = valueRange,
        onDragValueChanged = { next ->
            draftValue = next
            val step = next.roundToInt() / stepQuantum.coerceAtLeast(1)
            if (step != lastHapticStep) {
                lastHapticStep = step
                hapticEngine.performSliderStep(view)
            }
        },
        onValueCommit = { finalVal ->
            draftValue = finalVal
            onCommit(finalVal)
        },
    )
}

