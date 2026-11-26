package com.example.feature.appearance

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.core.ui.components.SmoothSettingsSlider
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.R
import com.example.domain.atmosphere.Season
import com.example.domain.atmosphere.SolarAtmosphereEngine
import com.example.domain.model.AppLanguage
import com.example.domain.model.BackgroundGradientStyle
import com.example.domain.model.CountryFlagResolver
import com.example.domain.model.FlagMatrixDirection
import com.example.domain.model.FptnServer
import com.example.domain.model.ThemeMode
import com.example.domain.model.VpnConnectionState
import com.example.domain.model.VpnErrorInfo
import com.example.domain.model.WavePhysicsPreset
import com.example.domain.model.WeatherEffectMode
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.ClockAngleDialControl
import com.example.core.ui.components.SettingsCardGroup
import com.example.core.ui.components.SettingsDivider
import com.example.core.ui.components.describeClockDirectionRu
import com.example.core.ui.components.describeTiltAngleRu
import com.example.core.ui.flags.CinematicFlagHeroBackdrop
import com.example.core.ui.flags.FlagPreviewMode
import com.example.core.ui.flags.FlagTransitionPreviewEvent
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.localization.localizedShortTitle
import com.example.core.ui.localization.localizedSubtitle
import com.example.core.ui.localization.localizedTitle
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.feature.home.MinimalistShieldHero
import com.example.feature.home.WeatherAtmosphereOverlay
import com.example.feature.shared.FptnUiState
import kotlin.math.roundToInt

private enum class AppearanceTab(
    val titleRu: String,
    val titleEn: String,
    val icon: ImageVector,
) {
    BACKDROP_LIGHT("Фон и Свет", "Theme & Light", Icons.Rounded.WbSunny),
    FLAGS_MATRIX("Флаги и Сетка", "Flags & Grid", Icons.Rounded.Flag),
    WEATHER("Погода", "Weather", Icons.Rounded.Cloud),
    SHIELD_WAVE("Щит и Волна", "Shield & Wave", Icons.Rounded.Shield),
}

private enum class ShieldPreviewStatus(val labelRu: String, val labelEn: String) {
    DISCONNECTED("Откл", "Off"),
    CONNECTING("Подкл.", "Connect"),
    SWITCHING("Смена", "Switch"),
    RECONNECTING("Повтор", "Retry"),
    CONNECTED("Защищено", "Protected"),
    ERROR("Ошибка", "Error"),
}

private val PreviewFlagChoices = listOf(
    "RU" to "РФ",
    "DE" to "DE",
    "ES" to "ES",
    "US" to "US",
    "JP" to "JP",
    CountryFlagResolver.CODE_PIRATE to "Пират",
)

private fun seasonRepresentativeDay(season: Season): Int = when (season) {
    Season.WINTER -> 15
    Season.SPRING -> 105
    Season.SUMMER -> 196
    Season.AUTUMN -> 288
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    uiState: FptnUiState,
    hapticEngine: HapticEngine,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSelectAppLanguage: (AppLanguage) -> Unit = {},
    onSelectBackgroundGradient: (BackgroundGradientStyle) -> Unit,
    onToggleDynamicColor: (Boolean) -> Unit,
    onToggleHaptics: (Boolean) -> Unit,
    onToggleTrafficNotification: (Boolean) -> Unit,
    onToggleFlagBackdrop: (Boolean) -> Unit,
    onToggleFlagMatrixTransition: (Boolean) -> Unit = {},
    onSelectFlagMatrixDirection: (FlagMatrixDirection) -> Unit = {},
    onUpdateFlagTiltDegrees: (Float) -> Unit = {},
    onUpdateFlagMatrixAngleDegrees: (Float) -> Unit = {},
    onToggleFlagMatrixRandomAngle: (Boolean) -> Unit = {},
    onToggleFlagAngleSnapToTicks: (Boolean) -> Unit = {},
    onUpdateFlagMatrixSpeed: (Float) -> Unit = {},
    onUpdateFlagMatrixZoom: (Float) -> Unit = {},
    onToggleFlagMatrixHighlightServers: (Boolean) -> Unit = {},
    onToggleFlagCoatOfArms: (Boolean) -> Unit,
    onUpdateFlagScale: (Float) -> Unit,
    onUpdateFlagDim: (Float) -> Unit,
    onUpdateFlagPhysics: (Float) -> Unit,
    onToggleSolarAtmosphere: (Boolean) -> Unit,
    onUpdateSolarAtmosphereIntensity: (Float) -> Unit = {},
    onSelectWeatherEffectMode: (WeatherEffectMode) -> Unit,
    onToggleWeatherHaptics: (Boolean) -> Unit,
    onUpdateWeatherIntensity: (Float) -> Unit,
    onUpdateWeatherWindStrength: (Float) -> Unit = {},
    onUpdateWeatherFogDepth: (Float) -> Unit = {},
    onRefreshLiveWeather: () -> Unit,
    onToggleWaterWaveEffect: (Boolean) -> Unit,
    onUpdateWaveSpeed: (Float) -> Unit,
    onUpdateWaveStrength: (Float) -> Unit,
    onUpdateWaveSmoothness: (Float) -> Unit,
    onUpdateWaveElasticity: (Float) -> Unit,
    onUpdateWaveTint: (Float) -> Unit,
    onUpdateWaveDispersion: (Float) -> Unit,
    onUpdateWaveRingWidth: (Float) -> Unit,
    onApplyWavePreset: (WavePhysicsPreset) -> Unit,
    onResetWaveDefaults: () -> Unit,
    onTestWave: (isConnect: Boolean) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings = uiState.settings

    var activeTab by remember { mutableStateOf(AppearanceTab.BACKDROP_LIGHT) }
    var previewShieldStatus by remember { mutableStateOf(ShieldPreviewStatus.CONNECTED) }
    var previewFlagCode by remember { mutableStateOf(uiState.activeServer?.countryCode ?: "DE") }
    var previewMatrixEvent by remember { mutableStateOf<FlagTransitionPreviewEvent?>(null) }
    var previewEventCounter by remember { mutableLongStateOf(0L) }
    var previewHourOverride by remember { mutableStateOf<Float?>(null) }
    var previewSeasonOverride by remember { mutableStateOf<Season?>(null) }

    val availableServerCountryCodes = remember(uiState.enabledServers) {
        val real = uiState.enabledServers.mapNotNull { it.countryCode?.uppercase() }.toSet()
        real.ifEmpty { setOf("DE", "NL", "US", "JP", "FI", "GB") }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onRefreshLiveWeather()
        }
    }

    val effectiveWeather = when (settings.weatherEffectMode) {
        WeatherEffectMode.AUTO_LOCATION -> uiState.liveWeather.condition
        else -> settings.weatherEffectMode
    }

    val previewSolarState = remember(
        settings.enableSolarAtmosphere,
        previewHourOverride,
        previewSeasonOverride,
    ) {
        if (!settings.enableSolarAtmosphere) {
            null
        } else if (previewHourOverride == null && previewSeasonOverride == null) {
            SolarAtmosphereEngine.evaluate()
        } else {
            val nowState = SolarAtmosphereEngine.evaluate()
            val dayOfYear = previewSeasonOverride?.let(::seasonRepresentativeDay)
                ?: seasonRepresentativeDay(nowState.season)
            val hour = previewHourOverride ?: 13.5f
            SolarAtmosphereEngine.evaluate(overrideHour = hour, overrideDayOfYear = dayOfYear)
        }
    }

    val demoServer = remember(previewFlagCode) {
        FptnServer(
            id = 1L,
            name = previewFlagCode,
            host = "preview.fptn",
            port = 443,
            md5Fingerprint = "preview",
        )
    }

    val demoReason = tr("Демо", "Demo")
    val demoErrorTitle = tr("Демо ошибки", "Error preview")
    val demoErrorDetails = tr("Предпросмотр состояния ошибки", "Error state preview")
    val previewConnState: VpnConnectionState = remember(previewShieldStatus, demoServer, demoReason, demoErrorTitle, demoErrorDetails) {
        when (previewShieldStatus) {
            ShieldPreviewStatus.DISCONNECTED -> VpnConnectionState.Disconnected
            ShieldPreviewStatus.CONNECTING -> VpnConnectionState.Connecting(demoServer)
            ShieldPreviewStatus.SWITCHING -> VpnConnectionState.SwitchingServer(
                fromServer = demoServer.copy(name = "RU"),
                toServer = demoServer,
            )
            ShieldPreviewStatus.RECONNECTING -> VpnConnectionState.Reconnecting(
                server = demoServer,
                attempt = 1,
                maxAttempts = 5,
                reason = demoReason,
            )
            ShieldPreviewStatus.CONNECTED -> VpnConnectionState.Connected(
                server = demoServer,
                isRootMode = false,
                assignedIp = "10.10.0.1",
                dnsServer = "1.1.1.1",
                connectedAtMillis = System.currentTimeMillis(),
            )
            ShieldPreviewStatus.ERROR -> VpnConnectionState.Error(
                message = demoErrorTitle,
                errorInfo = VpnErrorInfo(
                    title = demoErrorTitle,
                    details = demoErrorDetails,
                    hint = "",
                ),
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_appearance_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            hapticEngine.performNavigationClick(view)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("appearance_back_button"),
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp),
            ) {
                // 1. Compact Category Tab Strip
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(AppearanceTab.entries, key = { it.name }) { tab ->
                        val selected = activeTab == tab
                        FilterChip(
                            selected = selected,
                            onClick = {
                                hapticEngine.performSegmentTick(view)
                                activeTab = tab
                            },
                            label = {
                                Text(
                                    text = tr(tab.titleRu, tab.titleEn),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            modifier = Modifier.testTag("appearance_tab_${tab.name.lowercase()}"),
                        )
                    }
                }

                // 2. Sticky Dedicated Live Preview Card (ALWAYS visible at top while scrolling settings below!)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    AnimatedContent(
                        targetState = activeTab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "appearanceStickyPreview",
                    ) { tab ->
                        when (tab) {
                            AppearanceTab.BACKDROP_LIGHT -> BackdropLightPreviewCard(
                                gradientStyle = settings.backgroundGradientStyle,
                                solarState = previewSolarState,
                                solarIntensity = settings.solarAtmosphereIntensity,
                                previewHourOverride = previewHourOverride,
                                previewSeasonOverride = previewSeasonOverride,
                                onSelectHourOverride = {
                                    hapticEngine.performSegmentTick(view)
                                    previewHourOverride = it
                                },
                                onSelectSeasonOverride = {
                                    hapticEngine.performSegmentTick(view)
                                    previewSeasonOverride = it
                                },
                            )

                            AppearanceTab.FLAGS_MATRIX -> FlagMatrixPreviewCard(
                                gradientStyle = settings.backgroundGradientStyle,
                                flagCode = previewFlagCode,
                                settings = settings,
                                availableServerCountryCodes = availableServerCountryCodes,
                                hapticEngine = hapticEngine,
                                previewEvent = previewMatrixEvent,
                                onSelectFlagCode = { code ->
                                    hapticEngine.performSegmentTick(view)
                                    previewFlagCode = code
                                },
                                onTriggerPreview = { mode, target ->
                                    hapticEngine.performSegmentTick(view)
                                    previewEventCounter++
                                    val from = previewFlagCode
                                    val dest = target ?: if (from == "DE") "JP" else "DE"
                                    if (mode == FlagPreviewMode.MATRIX_SWITCH || mode == FlagPreviewMode.MYSTERY_SHATTER) {
                                        previewFlagCode = dest
                                    }
                                    previewMatrixEvent = FlagTransitionPreviewEvent(
                                        id = previewEventCounter,
                                        fromCode = from,
                                        toCode = dest,
                                        mode = mode,
                                    )
                                },
                            )

                            AppearanceTab.WEATHER -> WeatherPreviewCard(
                                gradientStyle = settings.backgroundGradientStyle,
                                solarState = previewSolarState,
                                solarIntensity = settings.solarAtmosphereIntensity,
                                effectiveWeather = effectiveWeather,
                                intensity = settings.weatherIntensity,
                                windStrength = settings.weatherWindStrength,
                                fogDepth = settings.weatherFogDepth,
                                liveWeatherLabel = if (settings.weatherEffectMode == WeatherEffectMode.AUTO_LOCATION) {
                                    buildString {
                                        append(uiState.liveWeather.locationLabel)
                                        uiState.liveWeather.temperatureC?.let { append(" • %.1f°C".format(it)) }
                                    }
                                } else {
                                    null
                                },
                                enableHaptics = settings.enableHaptics && settings.enableWeatherHaptics,
                                hapticEngine = hapticEngine,
                            )

                            AppearanceTab.SHIELD_WAVE -> ShieldWavePreviewCard(
                                gradientStyle = settings.backgroundGradientStyle,
                                previewConnState = previewConnState,
                                previewShieldStatus = previewShieldStatus,
                                onSelectStatus = { st ->
                                    hapticEngine.performSegmentTick(view)
                                    previewShieldStatus = st
                                },
                                onTestWave = { isConnect ->
                                    hapticEngine.performSegmentTick(view)
                                    onTestWave(isConnect)
                                },
                            )
                        }
                    }
                }

                // 3. Independent Scrollable Controls Area directly below the Sticky Preview
                val controlsScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(controlsScrollState)
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (activeTab) {
                        AppearanceTab.BACKDROP_LIGHT -> {
                            AppearanceSectionCard(title = tr("Язык, тема и градиент фона", "Language, theme & backdrop")) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        text = tr("Язык интерфейса", "App language"),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        AppLanguage.entries.forEach { lang ->
                                            FilterChip(
                                                selected = settings.appLanguage == lang,
                                                onClick = {
                                                    hapticEngine.performSegmentTick(view)
                                                    onSelectAppLanguage(lang)
                                                },
                                                label = { Text(lang.localizedTitle()) },
                                                modifier = Modifier.testTag("lang_chip_${lang.name}"),
                                            )
                                        }
                                    }

                                    SettingsDivider()

                                    Text(
                                        text = tr("Тема оформления", "Theme"),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        ThemeMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = settings.themeMode == mode,
                                                onClick = {
                                                    hapticEngine.performSegmentTick(view)
                                                    onSelectThemeMode(mode)
                                                },
                                                label = { Text(mode.localizedTitle()) },
                                                modifier = Modifier.testTag("theme_chip_${mode.name}"),
                                            )
                                        }
                                    }

                                    SettingsDivider()

                                    Text(
                                        text = tr("Стиль фонового градиента (симметричный)", "Background gradient style (symmetric)"),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        BackgroundGradientStyle.entries.forEach { style ->
                                            FilterChip(
                                                selected = settings.backgroundGradientStyle == style,
                                                onClick = {
                                                    hapticEngine.performSegmentTick(view)
                                                    onSelectBackgroundGradient(style)
                                                },
                                                label = { Text(style.localizedTitle()) },
                                                modifier = Modifier.testTag("gradient_chip_${style.name}"),
                                            )
                                        }
                                    }

                                    SettingsDivider()

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tr("Динамические цвета Material You", "Material You dynamic color"), style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = tr("Адаптация палитры под обои Android 12+", "Adapt palette to Android 12+ wallpaper"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = settings.dynamicColor,
                                            onCheckedChange = {
                                                hapticEngine.performToggle(view, it)
                                                onToggleDynamicColor(it)
                                            },
                                        )
                                    }
                                }
                            }

                            AppearanceSectionCard(title = tr("Атмосферный свет (Циркадный NOAA)", "Atmospheric lighting (NOAA Solar)")) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tr("Свет от времени суток и сезона", "Time-of-day & seasonal lighting"), style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = tr("Рассвет, день, золотой час, закат и звёздная ночь", "Dawn, day, golden hour, dusk, and night glow"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = settings.enableSolarAtmosphere,
                                            onCheckedChange = {
                                                hapticEngine.performToggle(view, it)
                                                onToggleSolarAtmosphere(it)
                                            },
                                        )
                                    }

                                    AnimatedVisibility(visible = settings.enableSolarAtmosphere) {
                                        CompactSliderRow(
                                            label = tr("Интенсивность света", "Lighting intensity"),
                                            formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                            value = settings.solarAtmosphereIntensity,
                                            range = 0.25f..2.0f,
                                            onValueCommit = onUpdateSolarAtmosphereIntensity,
                                        )
                                    }
                                }
                            }

                            AppearanceSectionCard(title = tr("Тактильность и уведомления", "Haptics & notifications")) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tr("Тактильный отклик (вибрация)", "Haptic feedback"), style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = tr("Плавные микроимпульсы при переключении и стыковке флагов", "Subtle micro-pulses on toggles and flag snapping"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = settings.enableHaptics,
                                            onCheckedChange = {
                                                hapticEngine.performToggle(view, it)
                                                onToggleHaptics(it)
                                            },
                                        )
                                    }

                                    SettingsDivider()

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tr("Скорость трафика в уведомлении", "Live traffic speed in notification"), style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = tr("Показывать текущую скорость скачивания/отдачи в шторке", "Show live download/upload speed in status shade"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = settings.showTrafficInNotification,
                                            onCheckedChange = {
                                                hapticEngine.performToggle(view, it)
                                                onToggleTrafficNotification(it)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        AppearanceTab.FLAGS_MATRIX -> {
                            val isRu = LocalIsRussian.current
                            AppearanceSectionCard(title = tr("Флаг страны и шёлковая физика", "Country flag & silk physics")) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tr("Флаг на главном экране", "Flag on home screen"), style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = tr("Шёлковый GPU-флаг выбранного сервера за щитом", "Silk GPU-animated flag of active server behind the shield"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = settings.enableFlagBackdrop,
                                            onCheckedChange = {
                                                hapticEngine.performToggle(view, it)
                                                onToggleFlagBackdrop(it)
                                            },
                                        )
                                    }

                                    AnimatedVisibility(visible = settings.enableFlagBackdrop) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SettingsDivider()
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(tr("Гербы на флагах (HD 512px)", "Coats of arms on flags (HD 512px)"), style = MaterialTheme.typography.titleSmall)
                                                    Text(
                                                        text = tr("Отображать официальные гербы государств", "Render official heraldic state emblems"),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                Switch(
                                                    checked = settings.flagShowCoatOfArms,
                                                    onCheckedChange = {
                                                        hapticEngine.performToggle(view, it)
                                                        onToggleFlagCoatOfArms(it)
                                                    },
                                                )
                                            }
                                            CompactSliderRow(
                                                label = tr("Масштаб флага", "Flag scale"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.flagBackdropScale,
                                                range = 0.85f..1.55f,
                                                onValueCommit = onUpdateFlagScale,
                                            )
                                            CompactSliderRow(
                                                label = tr("Затемнение флага", "Flag dim"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.flagBackdropDim,
                                                range = 0.10f..0.78f,
                                                onValueCommit = onUpdateFlagDim,
                                            )
                                            val offLabel = tr("Откл", "Off")
                                            CompactSliderRow(
                                                label = tr("Физика ветра и наклона", "Wind & tilt physics"),
                                                formatValue = { v ->
                                                    if (v <= 0.02f) offLabel else "${(v * 100).roundToInt()}%"
                                                },
                                                value = settings.flagPhysicsIntensity,
                                                range = 0.0f..1.6f,
                                                onValueCommit = onUpdateFlagPhysics,
                                            )

                                            SettingsDivider()

                                            Text(
                                                text = tr("Наклон флага (часовая стрелка 0°–360°)", "Flag tilt (clock dial 0°–360°)"),
                                                style = MaterialTheme.typography.titleSmall,
                                            )
                                            Text(
                                                text = tr("Вращайте стрелку под любой угол — с привязкой к делениям 15° или свободно", "Drag dial to any angle with 15° snap or free 1° control"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            ClockAngleDialControl(
                                                angleDegrees = settings.flagTiltDegrees,
                                                snapToTicks = settings.flagAngleSnapToTicks,
                                                enabled = settings.enableFlagBackdrop,
                                                hapticEngine = hapticEngine,
                                                subtitleForAngle = { deg -> describeTiltAngleRu(deg, isRu) },
                                                onAngleChange = onUpdateFlagTiltDegrees,
                                                onToggleSnapToTicks = onToggleFlagAngleSnapToTicks,
                                                resetAngle = 0f,
                                                testTagPrefix = "flag_tilt_dial",
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(visible = settings.enableFlagBackdrop) {
                                AppearanceSectionCard(title = tr("Бесконечная 2D-сетка флагов при смене сервера", "2D flag matrix on server switch")) {
                                    Column(
                                        modifier = Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(tr("Анимация 2D-матрицы (~195 стран)", "2D matrix animation (~195 countries)"), style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    text = tr("Выравнивание ткани → в паз сетки → полёт к новой стране", "Flatten silk → dock in grid → fly to target country"),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            Switch(
                                                checked = settings.enableFlagMatrixTransition,
                                                onCheckedChange = {
                                                    hapticEngine.performToggle(view, it)
                                                    onToggleFlagMatrixTransition(it)
                                                },
                                            )
                                        }

                                        AnimatedVisibility(visible = settings.enableFlagMatrixTransition) {
                                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                                SettingsDivider()
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(tr("Случайное направление каждый раз", "Random direction each time"), style = MaterialTheme.typography.titleSmall)
                                                        Text(
                                                            text = tr("Автоматически подбирает новый угол полёта при каждой смене узла", "Picks a fresh travel angle on every server switch"),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    Switch(
                                                        checked = settings.flagMatrixRandomAngle,
                                                        onCheckedChange = {
                                                            hapticEngine.performToggle(view, it)
                                                            onToggleFlagMatrixRandomAngle(it)
                                                        },
                                                        modifier = Modifier.testTag("flag_matrix_random_angle_switch"),
                                                    )
                                                }

                                                Text(
                                                    text = tr("Угол движения сетки (0°–360°)", "Matrix travel angle (0°–360°)"),
                                                    style = MaterialTheme.typography.titleSmall,
                                                )
                                                val randomAngleLabel = tr("Случайный угол", "Random angle")
                                                ClockAngleDialControl(
                                                    angleDegrees = settings.flagMatrixAngleDegrees,
                                                    snapToTicks = settings.flagAngleSnapToTicks,
                                                    enabled = !settings.flagMatrixRandomAngle,
                                                    hapticEngine = hapticEngine,
                                                    subtitleForAngle = { deg ->
                                                        if (settings.flagMatrixRandomAngle) {
                                                            randomAngleLabel
                                                        } else {
                                                            describeClockDirectionRu(deg, isRu)
                                                        }
                                                    },
                                                    onAngleChange = onUpdateFlagMatrixAngleDegrees,
                                                    onToggleSnapToTicks = onToggleFlagAngleSnapToTicks,
                                                    resetAngle = 270f,
                                                    testTagPrefix = "flag_matrix_dial",
                                                )

                                                CompactSliderRow(
                                                    label = tr("Скорость прокрутки (автоускорение при наведении)", "Scroll speed (auto-boost on target)"),
                                                    formatValue = { v -> "${"%.2f".format(v)}×" },
                                                    value = settings.flagMatrixSpeed,
                                                    range = 0.40f..2.20f,
                                                    onValueCommit = onUpdateFlagMatrixSpeed,
                                                )

                                                CompactSliderRow(
                                                    label = tr("Масштаб карточек в сетке", "Grid tile zoom"),
                                                    formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                    value = settings.flagMatrixZoom,
                                                    range = 0.65f..1.45f,
                                                    onValueCommit = onUpdateFlagMatrixZoom,
                                                )

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(tr("Подсвечивать доступные серверы", "Highlight available servers"), style = MaterialTheme.typography.titleSmall)
                                                        Text(
                                                            text = tr("Акцентная рамка и индикатор на странах из вашего списка", "Accent border on countries present in your server list"),
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                    Switch(
                                                        checked = settings.flagMatrixHighlightServers,
                                                        onCheckedChange = {
                                                            hapticEngine.performToggle(view, it)
                                                            onToggleFlagMatrixHighlightServers(it)
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AppearanceTab.WEATHER -> {
                            AppearanceSectionCard(title = tr("Погодные эффекты на главном экране", "Weather effects on home screen")) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        WeatherEffectMode.entries.forEach { mode ->
                                            FilterChip(
                                                selected = settings.weatherEffectMode == mode,
                                                onClick = {
                                                    hapticEngine.performSegmentTick(view)
                                                    onSelectWeatherEffectMode(mode)
                                                },
                                                label = { Text(mode.localizedShortTitle()) },
                                            )
                                        }
                                    }

                                    if (settings.weatherEffectMode == WeatherEffectMode.AUTO_LOCATION) {
                                        val hasLoc = ContextCompat.checkSelfPermission(
                                            context,
                                            Manifest.permission.ACCESS_COARSE_LOCATION,
                                        ) == PackageManager.PERMISSION_GRANTED
                                        Column(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Text(
                                                text = buildString {
                                                    append(uiState.liveWeather.locationLabel)
                                                    uiState.liveWeather.temperatureC?.let { append(" • %.1f°C".format(it)) }
                                                },
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                if (!hasLoc) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            hapticEngine.performSegmentTick(view)
                                                            locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                                                        },
                                                    ) {
                                                        Icon(Icons.Rounded.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                                                        Spacer(Modifier.width(4.dp))
                                                        Text(tr("Геолокация", "Location"))
                                                    }
                                                }
                                                FilledTonalButton(
                                                    onClick = {
                                                        hapticEngine.performSegmentTick(view)
                                                        onRefreshLiveWeather()
                                                    },
                                                ) {
                                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                    Text(tr("Обновить погоду", "Refresh weather"))
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        text = settings.weatherEffectMode.localizedSubtitle(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    AnimatedVisibility(visible = settings.weatherEffectMode != WeatherEffectMode.OFF) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SettingsDivider()
                                            CompactSliderRow(
                                                label = tr("Интенсивность эффекта", "Effect intensity"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.weatherIntensity,
                                                range = 0.25f..1.5f,
                                                onValueCommit = onUpdateWeatherIntensity,
                                            )
                                            CompactSliderRow(
                                                label = tr("Сила ветра и дрейфа", "Wind & drift strength"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.weatherWindStrength,
                                                range = 0.0f..1.5f,
                                                onValueCommit = onUpdateWeatherWindStrength,
                                            )
                                            CompactSliderRow(
                                                label = tr("Глубина 3D-тумана и облаков", "3D fog & cloud depth"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.weatherFogDepth,
                                                range = 0.25f..1.5f,
                                                onValueCommit = onUpdateWeatherFogDepth,
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(tr("Тактильные капли, град и гром", "Rain, hail & thunder haptics"), style = MaterialTheme.typography.titleSmall)
                                                    Text(
                                                        text = tr("Точный микроимпульс в момент удара капли/градины и раскатов грома", "Micro-pulses synced with droplet impacts and thunder"),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                                Switch(
                                                    checked = settings.enableWeatherHaptics,
                                                    onCheckedChange = {
                                                        hapticEngine.performToggle(view, it)
                                                        onToggleWeatherHaptics(it)
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AppearanceTab.SHIELD_WAVE -> {
                            AppearanceSectionCard(title = tr("Стеклянная волна при подключении", "Liquid glass connection wave")) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tr("Эффект стеклянной волны (AGSL)", "AGSL glass wave effect"), style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = tr("Жидкое преломление экрана при подключении и отключении", "Optical refraction ripple on connect and disconnect"),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Switch(
                                            checked = settings.enableWaterWaveEffect,
                                            onCheckedChange = {
                                                hapticEngine.performToggle(view, it)
                                                onToggleWaterWaveEffect(it)
                                            },
                                        )
                                    }

                                    AnimatedVisibility(visible = settings.enableWaterWaveEffect) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            SettingsDivider()
                                            Text(tr("Готовые пресеты физики", "Physics presets"), style = MaterialTheme.typography.titleSmall)
                                            FlowRow(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                            ) {
                                                WavePhysicsPreset.entries.forEach { preset ->
                                                    AssistChip(
                                                        onClick = {
                                                            hapticEngine.performSegmentTick(view)
                                                            onApplyWavePreset(preset)
                                                        },
                                                        label = { Text(preset.localizedTitle()) },
                                                        leadingIcon = {
                                                            Icon(
                                                                Icons.Rounded.AutoAwesome,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(16.dp),
                                                            )
                                                        },
                                                    )
                                                }
                                            }

                                            CompactSliderRow(
                                                label = tr("Скорость волны", "Wave speed"),
                                                formatValue = { v -> "${"%.2f".format(v)}×" },
                                                value = settings.waveSpeed,
                                                range = 0.35f..1.8f,
                                                onValueCommit = onUpdateWaveSpeed,
                                            )
                                            CompactSliderRow(
                                                label = tr("Сила преломления", "Refraction strength"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.waveStrength,
                                                range = 0.25f..1.8f,
                                                onValueCommit = onUpdateWaveStrength,
                                            )
                                            CompactSliderRow(
                                                label = tr("Мягкость гребня", "Crest smoothness"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.waveSmoothness,
                                                range = 0.25f..1.6f,
                                                onValueCommit = onUpdateWaveSmoothness,
                                            )
                                            CompactSliderRow(
                                                label = tr("Упругость ряби", "Ripple elasticity"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.waveElasticity,
                                                range = 0.15f..1.5f,
                                                onValueCommit = onUpdateWaveElasticity,
                                            )
                                            CompactSliderRow(
                                                label = tr("Оттенок стекла", "Glass tint"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.waveTintStrength,
                                                range = 0.05f..1.0f,
                                                onValueCommit = onUpdateWaveTint,
                                            )
                                            CompactSliderRow(
                                                label = tr("Хроматическая дисперсия", "Chromatic dispersion"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.waveDispersion,
                                                range = 0.0f..1.8f,
                                                onValueCommit = onUpdateWaveDispersion,
                                            )
                                            CompactSliderRow(
                                                label = tr("Ширина кольца", "Ring width"),
                                                formatValue = { v -> "${(v * 100).roundToInt()}%" },
                                                value = settings.waveRingWidth,
                                                range = 0.55f..1.65f,
                                                onValueCommit = onUpdateWaveRingWidth,
                                            )

                                            OutlinedButton(
                                                onClick = {
                                                    hapticEngine.performSegmentTick(view)
                                                    onResetWaveDefaults()
                                                },
                                                modifier = Modifier.fillMaxWidth(),
                                            ) {
                                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text(tr("Сбросить параметры волны", "Reset wave defaults"))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
private fun Modifier.symmetricGradientBackdrop(
    gradientStyle: BackgroundGradientStyle,
    accent: Color,
    secondary: Color,
    bgColor: Color,
): Modifier = this
    .background(bgColor)
    .drawBehind {
        val w = size.width
        val h = size.height
        val topCenter = Offset(w * 0.5f, h * 0.16f)
        val radius = maxOf(w, h) * 0.78f
        when (gradientStyle) {
            BackgroundGradientStyle.MINIMAL_CLEAN -> Unit
            BackgroundGradientStyle.GLASS_AURORA -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to accent.copy(alpha = 0.24f),
                            0.46f to secondary.copy(alpha = 0.11f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to accent.copy(alpha = 0.22f),
                            0.52f to secondary.copy(alpha = 0.09f),
                            1.0f to Color.Transparent,
                        ),
                        center = topCenter,
                        radius = radius,
                    ),
                    radius = radius,
                    center = topCenter,
                )
            }
            BackgroundGradientStyle.DEEP_COSMOS -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF1D2B64).copy(alpha = 0.44f),
                            0.50f to accent.copy(alpha = 0.18f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                )
            }
            BackgroundGradientStyle.EMERALD_PULSE -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFF00897B).copy(alpha = 0.36f),
                            0.50f to Color(0xFF26A69A).copy(alpha = 0.16f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                )
            }
            BackgroundGradientStyle.SUNSET_GLOW -> {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFD84315).copy(alpha = 0.30f),
                            0.50f to Color(0xFF8E24AA).copy(alpha = 0.16f),
                            1.0f to Color.Transparent,
                        ),
                    ),
                )
            }
        }
    }

@Composable
private fun BackdropLightPreviewCard(
    gradientStyle: BackgroundGradientStyle,
    solarState: com.example.domain.atmosphere.SolarAtmosphereState?,
    solarIntensity: Float,
    previewHourOverride: Float?,
    previewSeasonOverride: Season?,
    onSelectHourOverride: (Float?) -> Unit,
    onSelectSeasonOverride: (Season?) -> Unit,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceContainerLow
    val accent = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .symmetricGradientBackdrop(gradientStyle, accent, secondary, bgColor),
            ) {
                if (solarState != null) {
                    val mult = solarIntensity.coerceIn(0.25f, 2.0f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to solarState.skyTopColor.copy(alpha = (solarState.overlayStrength * 0.92f * mult).coerceIn(0.06f, 0.62f)),
                                        0.45f to solarState.skyMidColor.copy(alpha = (solarState.overlayStrength * 0.60f * mult).coerceIn(0.04f, 0.42f)),
                                        1.0f to Color.Transparent,
                                    ),
                                ),
                            )
                            .drawBehind {
                                val glowCenter = Offset(
                                    x = size.width * solarState.sunXFraction.coerceIn(0.08f, 0.92f),
                                    y = size.height * solarState.sunYFraction.coerceIn(0.08f, 0.72f),
                                )
                                val r = size.maxDimension * 0.52f
                                val a = (solarState.overlayStrength * 0.74f * mult).coerceIn(0.06f, 0.58f)
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(solarState.ambientGlowColor.copy(alpha = a), Color.Transparent),
                                        center = glowCenter,
                                        radius = r,
                                    ),
                                    radius = r,
                                    center = glowCenter,
                                )
                            },
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.88f),
                                ),
                            ),
                        )
                        .padding(12.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = gradientStyle.localizedTitle(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = solarState?.let { "${it.phase.localizedTitle()} • ${it.season.localizedTitle()}" }
                                ?: tr("Циркадный свет выключен", "Solar lighting off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Compact inline time/season tester strip right inside the sticky preview card
            val nowLabel = tr("Сейчас", "Now")
            val dawnLabel = tr("Рассвет", "Dawn")
            val dayLabel = tr("День", "Day")
            val duskLabel = tr("Закат", "Dusk")
            val nightLabel = tr("Ночь", "Night")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val times = listOf(
                    null to nowLabel,
                    6.2f to dawnLabel,
                    13.0f to dayLabel,
                    19.4f to duskLabel,
                    23.2f to nightLabel,
                )
                items(times) { (hour, label) ->
                    FilterChip(
                        selected = previewHourOverride == hour,
                        onClick = { onSelectHourOverride(hour) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
                items(Season.entries) { season ->
                    FilterChip(
                        selected = previewSeasonOverride == season,
                        onClick = {
                            onSelectSeasonOverride(if (previewSeasonOverride == season) null else season)
                        },
                        label = { Text(season.localizedTitle(), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FlagMatrixPreviewCard(
    gradientStyle: BackgroundGradientStyle,
    flagCode: String,
    settings: com.example.domain.model.VpnSettings,
    availableServerCountryCodes: Set<String>,
    hapticEngine: HapticEngine,
    previewEvent: FlagTransitionPreviewEvent?,
    onSelectFlagCode: (String) -> Unit,
    onTriggerPreview: (FlagPreviewMode, String?) -> Unit,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceContainerLow
    val accent = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    val view = LocalView.current
    val demoConnected = remember(flagCode) {
        VpnConnectionState.Connected(
            server = FptnServer(id = 1L, name = flagCode, host = "demo", port = 443, md5Fingerprint = "demo"),
            isRootMode = false,
            assignedIp = "10.10.0.1",
            dnsServer = "1.1.1.1",
            connectedAtMillis = System.currentTimeMillis(),
        )
    }

    var previewShieldOverride by remember { mutableStateOf<VpnConnectionState?>(null) }
    LaunchedEffect(previewEvent?.id) {
        val ev = previewEvent ?: return@LaunchedEffect
        when (ev.mode) {
            FlagPreviewMode.ERROR_FROST -> {
                previewShieldOverride = VpnConnectionState.Error(message = "Демонстрация ошибки подключения")
                hapticEngine.onConnectionError(view)
                delay(1850L)
                previewShieldOverride = null
            }
            FlagPreviewMode.DISCONNECT -> {
                previewShieldOverride = VpnConnectionState.Disconnected
                delay(1450L)
                previewShieldOverride = null
            }
            else -> {
                previewShieldOverride = null
            }
        }
    }

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(152.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .symmetricGradientBackdrop(gradientStyle, accent, secondary, bgColor),
                contentAlignment = Alignment.Center,
            ) {
                CinematicFlagHeroBackdrop(
                    connectionState = previewShieldOverride ?: demoConnected,
                    fallbackCountryCode = flagCode,
                    enabled = settings.enableFlagBackdrop,
                    matrixTransitionEnabled = settings.enableFlagMatrixTransition,
                    backdropScale = settings.flagBackdropScale,
                    dimAmount = settings.flagBackdropDim,
                    showCoatOfArms = settings.flagShowCoatOfArms,
                    physicsIntensity = settings.flagPhysicsIntensity,
                    hapticEngine = hapticEngine,
                    previewEvent = previewEvent,
                    matrixDirection = settings.flagMatrixDirection,
                    flagTiltDegrees = settings.flagTiltDegrees,
                    matrixAngleDegrees = settings.flagMatrixAngleDegrees,
                    matrixRandomAngle = settings.flagMatrixRandomAngle,
                    matrixSpeed = settings.flagMatrixSpeed,
                    matrixZoom = settings.flagMatrixZoom,
                    highlightAvailableServers = settings.flagMatrixHighlightServers,
                    availableServerCountryCodes = availableServerCountryCodes,
                    modifier = Modifier.fillMaxSize(),
                )
                MinimalistShieldHero(
                    connectionState = previewShieldOverride ?: demoConnected,
                    size = 92.dp,
                )
            }

            // Inline test triggers & sample flag selector inside sticky card
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    AssistChip(
                        onClick = { onTriggerPreview(FlagPreviewMode.MATRIX_SWITCH, null) },
                        label = {
                            Text(
                                stringResource(R.string.appearance_preview_matrix),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = { onTriggerPreview(FlagPreviewMode.MYSTERY_SHATTER, CountryFlagResolver.CODE_PIRATE) },
                        label = {
                            Text(
                                stringResource(R.string.appearance_preview_pirate),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = { onTriggerPreview(FlagPreviewMode.DISCONNECT, flagCode) },
                        label = {
                            Text(
                                stringResource(R.string.appearance_preview_disconnect),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = { onTriggerPreview(FlagPreviewMode.ERROR_FROST, flagCode) },
                        label = {
                            Text(
                                stringResource(R.string.appearance_preview_error),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
                items(PreviewFlagChoices) { (code, label) ->
                    FilterChip(
                        selected = flagCode == code,
                        onClick = { onSelectFlagCode(code) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherPreviewCard(
    gradientStyle: BackgroundGradientStyle,
    solarState: com.example.domain.atmosphere.SolarAtmosphereState?,
    solarIntensity: Float,
    effectiveWeather: WeatherEffectMode,
    intensity: Float,
    windStrength: Float,
    fogDepth: Float,
    liveWeatherLabel: String?,
    enableHaptics: Boolean,
    hapticEngine: HapticEngine,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceContainerLow
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(164.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .symmetricGradientBackdrop(gradientStyle, accent, secondary, bgColor),
            ) {
                if (solarState != null) {
                    val mult = solarIntensity.coerceIn(0.25f, 2.0f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colorStops = arrayOf(
                                        0.0f to solarState.skyTopColor.copy(alpha = (solarState.overlayStrength * 0.72f * mult).coerceIn(0.05f, 0.50f)),
                                        1.0f to Color.Transparent,
                                    ),
                                ),
                            ),
                    )
                }

                if (effectiveWeather != WeatherEffectMode.OFF && effectiveWeather != WeatherEffectMode.AUTO_LOCATION) {
                    WeatherAtmosphereOverlay(
                        mode = effectiveWeather,
                        intensity = intensity,
                        windStrength = windStrength,
                        fogDepth = fogDepth,
                        enableHaptics = enableHaptics,
                        onRaindropImpact = { strength, isDrizzle, isHeavy ->
                            hapticEngine.onRaindropImpact(strength, isDrizzle, isHeavy)
                        },
                        onThunderPulse = { hapticEngine.onDistantThunder() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            // Dedicated non-overlapping weather status strip strictly below the preview canvas
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "${effectiveWeather.localizedShortTitle()} • ${(intensity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (!liveWeatherLabel.isNullOrBlank()) {
                    Text(
                        text = liveWeatherLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShieldWavePreviewCard(
    gradientStyle: BackgroundGradientStyle,
    previewConnState: VpnConnectionState,
    previewShieldStatus: ShieldPreviewStatus,
    onSelectStatus: (ShieldPreviewStatus) -> Unit,
    onTestWave: (Boolean) -> Unit,
) {
    val bgColor = MaterialTheme.colorScheme.surfaceContainerLow
    val accent = MaterialTheme.colorScheme.tertiary
    val secondary = MaterialTheme.colorScheme.secondary

    Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(142.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .symmetricGradientBackdrop(gradientStyle, accent, secondary, bgColor),
                contentAlignment = Alignment.Center,
            ) {
                MinimalistShieldHero(
                    connectionState = previewConnState,
                    size = 116.dp,
                )
            }

            // Dedicated strip below the shield canvas so test buttons NEVER overlap the shield
            LazyRow(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    AssistChip(
                        onClick = { onTestWave(true) },
                        label = { Text(tr("Волна +", "Wave +"), style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = {
                            Icon(Icons.Rounded.WaterDrop, contentDescription = null, modifier = Modifier.size(14.dp))
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = { onTestWave(false) },
                        label = { Text(tr("Волна −", "Wave −"), style = MaterialTheme.typography.labelSmall) },
                    )
                }
                items(ShieldPreviewStatus.entries) { st ->
                    FilterChip(
                        selected = previewShieldStatus == st,
                        onClick = { onSelectStatus(st) },
                        label = { Text(tr(st.labelRu, st.labelEn), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppearanceSectionCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
        SettingsCardGroup {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun CompactSliderRow(
    label: String,
    formatValue: (Float) -> String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueCommit: (Float) -> Unit,
) {
    var draftValue by remember { mutableFloatStateOf(value.coerceIn(range.start, range.endInclusive)) }
    LaunchedEffect(value) {
        draftValue = value.coerceIn(range.start, range.endInclusive)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatValue(draftValue),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = JetBrainsMonoFamily,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
        }
        SmoothSettingsSlider(
            value = value,
            valueRange = range,
            onDragValueChanged = { draftValue = it },
            onValueCommit = { finalVal ->
                draftValue = finalVal
                onValueCommit(finalVal)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
