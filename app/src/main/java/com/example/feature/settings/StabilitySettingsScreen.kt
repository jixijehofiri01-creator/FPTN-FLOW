package com.example.feature.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.components.SmoothSettingsSlider
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedSubtitle
import com.example.core.ui.localization.tr
import com.example.domain.vpn.WatchdogSensitivity
import com.example.feature.shared.FptnUiState
import kotlin.math.roundToInt

/**
 * Configures stall watchdog recovery, reconnect policies, protocol timeouts, and server ping interval.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StabilitySettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onUpdateAutoPingInterval: (Int) -> Unit,
    onToggleReconnectOnFailure: (Boolean) -> Unit,
    onToggleReconnectOnNetworkChange: (Boolean) -> Unit,
    onUpdateMaxReconnectAttempts: (Int) -> Unit,
    onUpdateReconnectDelaySec: (Int) -> Unit,
    onUpdateHandshakeTimeoutSec: (Int) -> Unit,
    onUpdateWsOpenTimeoutSec: (Int) -> Unit,
    onToggleStallWatchdog: (Boolean) -> Unit,
    onSelectWatchdogSensitivity: (WatchdogSensitivity) -> Unit = {},
    onUpdateWatchdogProbeSec: (Int) -> Unit,
    onUpdateWatchdogStallSec: (Int) -> Unit,
    onUpdateWatchdogCooldownSec: (Int) -> Unit,
    onUpdateKeepaliveIntervalSec: (Int) -> Unit = {},
    onUpdateNetworkSwitchDebounceSec: (Int) -> Unit = {},
    onToggleAutoRotateSniOnFail: (Boolean) -> Unit = {},
    onToggleAutoFailoverServer: (Boolean) -> Unit = {},
    onNavigateBack: () -> Unit,
) {
    val view = LocalView.current
    val isRu = LocalIsRussian.current
    val settings = uiState.settings

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_additional_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("additional_back_button"),
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
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = tr("Контроль зависаний (0 Б/с)", "Stall watchdog (0 B/s)"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                SettingsCardGroup {
                    StabilityToggleRow(
                        title = tr("Переподключение при зависании", "Reconnect on silent stall"),
                        subtitle = tr(
                            "Проверяет туннель пробами UDP:53 и ICMP, если сервер не отвечает",
                            "Probes the tunnel with UDP:53 and ICMP when the server goes silent",
                        ),
                        checked = settings.enableStallWatchdog,
                        testTag = "additional_watchdog_switch",
                        onCheckedChange = {
                            hapticEngine.performToggle(view, it)
                            onToggleStallWatchdog(it)
                        },
                    )
                    if (settings.enableStallWatchdog) {
                        SettingsDivider()
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = tr("Чувствительность", "Sensitivity"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = settings.watchdogSensitivity.localizedSubtitle(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                WatchdogSensitivity.entries.forEach { sensitivity ->
                                    FilterChip(
                                        selected = settings.watchdogSensitivity == sensitivity,
                                        onClick = {
                                            hapticEngine.performToggle(view, enabled = true)
                                            onSelectWatchdogSensitivity(sensitivity)
                                        },
                                        label = { Text(sensitivity.localizedShortTitle()) },
                                        modifier = Modifier.testTag("watchdog_sensitivity_${sensitivity.name}"),
                                    )
                                }
                            }
                        }
                        SettingsDivider()
                        StabilitySliderRow(
                            titleForValue = { v ->
                                val sec = v.roundToInt().coerceIn(2, 25)
                                tr(isRu, "Проверка туннеля через: $sec с", "Probe tunnel after: ${sec}s")
                            },
                            subtitle = tr(
                                "Через сколько секунд тишины отправить пробу",
                                "Seconds of silence before sending a probe",
                            ),
                            value = settings.watchdogProbeSec.toFloat(),
                            valueRange = 2f..25f,
                            testTag = "watchdog_probe_slider",
                            hapticEngine = hapticEngine,
                            onValueCommit = { raw ->
                                val next = raw.roundToInt().coerceIn(2, 25)
                                if (next != settings.watchdogProbeSec) onUpdateWatchdogProbeSec(next)
                            },
                        )
                        SettingsDivider()
                        StabilitySliderRow(
                            titleForValue = { v ->
                                val sec = v.roundToInt().coerceIn(4, 55)
                                tr(isRu, "Сброс сессии через: $sec с", "Reset session after: ${sec}s")
                            },
                            subtitle = tr(
                                "Интервал без ответа перед перезапуском",
                                "Unresponsive duration before restarting tunnel",
                            ),
                            value = settings.watchdogStallSec.toFloat(),
                            valueRange = 4f..55f,
                            testTag = "watchdog_stall_slider",
                            hapticEngine = hapticEngine,
                            onValueCommit = { raw ->
                                val next = raw.roundToInt().coerceIn(4, 55)
                                if (next != settings.watchdogStallSec) onUpdateWatchdogStallSec(next)
                            },
                        )
                        SettingsDivider()
                        StabilitySliderRow(
                            titleForValue = { v ->
                                val sec = v.roundToInt().coerceIn(10, 90)
                                tr(isRu, "Пауза между сбросами: $sec с", "Cooldown between resets: ${sec}s")
                            },
                            subtitle = tr(
                                "Защита от частых перезапусков в мобильной сети",
                                "Prevents rapid restart loops on unstable mobile networks",
                            ),
                            value = settings.watchdogCooldownSec.toFloat(),
                            valueRange = 10f..90f,
                            testTag = "watchdog_cooldown_slider",
                            hapticEngine = hapticEngine,
                            onValueCommit = { raw ->
                                val next = raw.roundToInt().coerceIn(10, 90)
                                if (next != settings.watchdogCooldownSec) onUpdateWatchdogCooldownSec(next)
                            },
                        )
                    }
                }

                Text(
                    text = tr("Переподключение и таймауты", "Reconnection & timeouts"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                SettingsCardGroup {
                    StabilityToggleRow(
                        title = tr("Переподключаться при обрыве", "Reconnect on disconnect"),
                        subtitle = tr(
                            "Восстанавливает туннель при потере соединения",
                            "Automatically restores the tunnel when connection drops",
                        ),
                        checked = settings.reconnectOnFailure,
                        testTag = "reconnect_switch",
                        onCheckedChange = {
                            hapticEngine.performToggle(view, it)
                            onToggleReconnectOnFailure(it)
                        },
                    )
                    SettingsDivider()
                    StabilityToggleRow(
                        title = tr("Переподключаться при смене сети", "Reconnect on network switch"),
                        subtitle = tr(
                            "Перезапуск при переходе между Wi-Fi и мобильной сетью",
                            "Restarts tunnel when switching between Wi-Fi and cellular",
                        ),
                        checked = settings.reconnectOnNetworkChange,
                        testTag = "reconnect_network_switch",
                        onCheckedChange = {
                            hapticEngine.performToggle(view, it)
                            onToggleReconnectOnNetworkChange(it)
                        },
                    )
                    SettingsDivider()
                    StabilityToggleRow(
                        title = tr("Подбор SNI при ошибке 600", "Rotate SNI on error 600"),
                        subtitle = tr(
                            "Пробует запасные SNI и метод обхода при обрыве TLS",
                            "Tries fallback SNI domains and bypass methods on TLS drop",
                        ),
                        checked = settings.autoRotateSniOnFail,
                        testTag = "auto_rotate_sni_switch",
                        onCheckedChange = {
                            hapticEngine.performToggle(view, it)
                            onToggleAutoRotateSniOnFail(it)
                        },
                    )
                    SettingsDivider()
                    StabilityToggleRow(
                        title = tr("Переход на резервный сервер", "Failover to backup server"),
                        subtitle = tr(
                            "Переключает сервер после 3 неудачных попыток",
                            "Switches to another server after 3 failed attempts",
                        ),
                        checked = settings.autoFailoverServer,
                        testTag = "auto_failover_server_switch",
                        onCheckedChange = {
                            hapticEngine.performToggle(view, it)
                            onToggleAutoFailoverServer(it)
                        },
                    )
                    SettingsDivider()
                    StabilitySliderRow(
                        titleForValue = { v ->
                            val n = v.roundToInt().coerceIn(3, 50)
                            tr(isRu, "Попыток переподключения: $n", "Max reconnect attempts: $n")
                        },
                        subtitle = tr("Максимум попыток до остановки", "Maximum attempts before giving up"),
                        value = settings.maxReconnectAttempts.toFloat(),
                        valueRange = 3f..50f,
                        testTag = "max_reconnect_slider",
                        hapticEngine = hapticEngine,
                        onValueCommit = { raw ->
                            val next = raw.roundToInt().coerceIn(3, 50)
                            if (next != settings.maxReconnectAttempts) onUpdateMaxReconnectAttempts(next)
                        },
                    )
                    SettingsDivider()
                    StabilitySliderRow(
                        titleForValue = { v ->
                            val sec = v.roundToInt().coerceIn(1, 10)
                            tr(isRu, "Начальная задержка: $sec с", "Initial retry delay: ${sec}s")
                        },
                        subtitle = tr(
                            "Пауза перед повтором (увеличивается до 10 с)",
                            "Backoff before retrying (scales up to 10s)",
                        ),
                        value = settings.reconnectDelaySec.toFloat(),
                        valueRange = 1f..10f,
                        testTag = "reconnect_delay_slider",
                        hapticEngine = hapticEngine,
                        onValueCommit = { raw ->
                            val next = raw.roundToInt().coerceIn(1, 10)
                            if (next != settings.reconnectDelaySec) onUpdateReconnectDelaySec(next)
                        },
                    )
                    SettingsDivider()
                    StabilitySliderRow(
                        titleForValue = { v ->
                            val sec = v.roundToInt().coerceIn(4, 20)
                            tr(isRu, "Таймаут HTTPS: $sec с", "HTTPS handshake timeout: ${sec}s")
                        },
                        subtitle = tr(
                            "Ожидание ответа login / dns от сервера",
                            "Wait timeout for server login / dns response",
                        ),
                        value = settings.handshakeTimeoutSec.toFloat(),
                        valueRange = 4f..20f,
                        testTag = "handshake_timeout_slider",
                        hapticEngine = hapticEngine,
                        onValueCommit = { raw ->
                            val next = raw.roundToInt().coerceIn(4, 20)
                            if (next != settings.handshakeTimeoutSec) onUpdateHandshakeTimeoutSec(next)
                        },
                    )
                    SettingsDivider()
                    StabilitySliderRow(
                        titleForValue = { v ->
                            val sec = v.roundToInt().coerceIn(5, 30)
                            tr(isRu, "Таймаут WebSocket: $sec с", "WebSocket open timeout: ${sec}s")
                        },
                        subtitle = tr(
                            "Ожидание открытия зашифрованного сокета",
                            "Wait timeout for establishing the encrypted socket",
                        ),
                        value = settings.wsOpenTimeoutSec.toFloat(),
                        valueRange = 5f..30f,
                        testTag = "ws_timeout_slider",
                        hapticEngine = hapticEngine,
                        onValueCommit = { raw ->
                            val next = raw.roundToInt().coerceIn(5, 30)
                            if (next != settings.wsOpenTimeoutSec) onUpdateWsOpenTimeoutSec(next)
                        },
                    )
                    SettingsDivider()
                    StabilitySliderRow(
                        titleForValue = { v ->
                            val sec = v.roundToInt().coerceIn(0, 60)
                            if (sec <= 0) {
                                tr(isRu, "Keepalive (CGNAT): Выкл", "Keepalive (CGNAT): Off")
                            } else {
                                tr(isRu, "Keepalive (CGNAT): каждые $sec с", "Keepalive (CGNAT): every ${sec}s")
                            }
                        },
                        subtitle = tr(
                            "Поддерживает NAT-сессию в мобильных сетях",
                            "Keeps carrier-grade NAT mappings alive on cellular",
                        ),
                        value = settings.keepaliveIntervalSec.toFloat(),
                        valueRange = 0f..60f,
                        testTag = "keepalive_interval_slider",
                        hapticEngine = hapticEngine,
                        onValueCommit = { raw ->
                            val next = raw.roundToInt().coerceIn(0, 60)
                            if (next != settings.keepaliveIntervalSec) onUpdateKeepaliveIntervalSec(next)
                        },
                    )
                    SettingsDivider()
                    StabilitySliderRow(
                        titleForValue = { v ->
                            val sec = v.roundToInt().coerceIn(1, 10)
                            tr(isRu, "Задержка при смене сети: $sec с", "Network switch debounce: ${sec}s")
                        },
                        subtitle = tr(
                            "Ожидание стабилизации LTE или Wi-Fi",
                            "Wait window for LTE or Wi-Fi link stabilization",
                        ),
                        value = settings.networkSwitchDebounceSec.toFloat(),
                        valueRange = 1f..10f,
                        testTag = "network_debounce_slider",
                        hapticEngine = hapticEngine,
                        onValueCommit = { raw ->
                            val next = raw.roundToInt().coerceIn(1, 10)
                            if (next != settings.networkSwitchDebounceSec) onUpdateNetworkSwitchDebounceSec(next)
                        },
                    )
                }

                Text(
                    text = tr("Мониторинг серверов", "Server monitoring"),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                SettingsCardGroup {
                    StabilitySliderRow(
                        titleForValue = { v ->
                            val snapped = ((v / 15f).roundToInt() * 15).coerceIn(15, 300)
                            tr(isRu, "Интервал проверки Ping: $snapped с", "Ping refresh interval: ${snapped}s")
                        },
                        subtitle = tr(
                            "Проверка задержки серверов при открытом приложении",
                            "Periodic server latency checks while the app is open",
                        ),
                        value = settings.autoPingIntervalSec.toFloat(),
                        valueRange = 15f..300f,
                        testTag = "auto_ping_slider",
                        hapticEngine = hapticEngine,
                        stepQuantum = 15,
                        onValueCommit = { raw ->
                            val snapped = ((raw / 15f).roundToInt() * 15).coerceIn(15, 300)
                            if (snapped != settings.autoPingIntervalSec) onUpdateAutoPingInterval(snapped)
                        },
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun StabilityToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag),
        )
    }
}

@Composable
private fun StabilitySliderRow(
    titleForValue: (Float) -> String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    testTag: String,
    hapticEngine: HapticEngine,
    stepQuantum: Int = 1,
    onValueCommit: (Float) -> Unit,
) {
    val view = LocalView.current
    var draftValue by remember { mutableFloatStateOf(value.coerceIn(valueRange.start, valueRange.endInclusive)) }
    var lastHapticStep by remember { mutableIntStateOf(value.roundToInt() / stepQuantum.coerceAtLeast(1)) }

    LaunchedEffect(value) {
        draftValue = value.coerceIn(valueRange.start, valueRange.endInclusive)
        lastHapticStep = value.roundToInt() / stepQuantum.coerceAtLeast(1)
    }

    Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
        Text(text = titleForValue(draftValue), style = MaterialTheme.typography.titleSmall)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                onValueCommit(finalVal)
            },
            modifier = Modifier.testTag(testTag),
        )
    }
}
