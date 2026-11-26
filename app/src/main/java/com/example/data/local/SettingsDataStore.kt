package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.domain.dns.DnsEndpointParser
import com.example.domain.model.AppLanguage
import com.example.domain.model.BackgroundGradientStyle
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsMode
import com.example.domain.model.FlagMatrixDirection
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.NetworkPresetCodec
import com.example.domain.model.ServerSortOrder
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemeMode
import com.example.domain.model.VpnSettings
import com.example.domain.model.WeatherEffectMode
import com.example.domain.vpn.WatchdogSensitivity
import com.example.vpn.sni.SniAutoScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

private val Context.fptnSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fptn_settings_prefs",
)

class SettingsDataStore(private val context: Context) {

    private val updateMutex = Mutex()

    @Volatile
    private var cachedFallbackSeed: Long = SecureRandom().nextLong()

    private object Keys {
        val SELECTED_SERVER_ID = longPreferencesKey("selected_server_id")
        val AUTO_SELECT_BEST_SERVER = booleanPreferencesKey("auto_select_best_server")
        val SERVER_SORT_ORDER = stringPreferencesKey("server_sort_order")
        val BYPASS_METHOD = stringPreferencesKey("bypass_method")
        val SNI_SPOOFING_MODE = stringPreferencesKey("sni_spoofing_mode")
        val CUSTOM_SNI = stringPreferencesKey("custom_sni")
        val SLIDING_TUNNEL_MODE = stringPreferencesKey("sliding_tunnel_mode")
        val ENABLE_OBFUSCATION = booleanPreferencesKey("enable_obfuscation")
        val SMART_RF_ROUTING = booleanPreferencesKey("smart_rf_routing")
        val BYPASS_REGION = stringPreferencesKey("bypass_region")
        val CUSTOM_DIRECT_DOMAINS = stringSetPreferencesKey("custom_direct_domains")
        val CUSTOM_VPN_DOMAINS = stringSetPreferencesKey("custom_vpn_domains")
        val UNLOCK_PROTECTED_RU_ROOT = booleanPreferencesKey("unlock_protected_ru_root")
        val USE_ROOT_MODE = booleanPreferencesKey("use_root_mode")
        val ALLOW_SECONDARY_VPN_CHAINING = booleanPreferencesKey("allow_secondary_vpn_chaining")
        val ROOT_TUN_INTERFACE_NAME = stringPreferencesKey("root_tun_interface_name")
        val ROOT_ADAPTIVE_BOOST_ENABLED = booleanPreferencesKey("root_adaptive_boost_enabled")
        val SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val DNS_MODE = stringPreferencesKey("dns_mode")
        val DNS_PRIMARY = stringPreferencesKey("dns_primary")
        val DNS_SECONDARY = stringPreferencesKey("dns_secondary")
        val CUSTOM_DNS_URL = stringPreferencesKey("custom_dns_url")
        val ENABLE_CUSTOM_DNS_URL = booleanPreferencesKey("enable_custom_dns_url")
        val SAVED_DNS_ENTRIES_JSON = stringPreferencesKey("saved_dns_entries_json")
        val NETWORK_PRESETS_JSON = stringPreferencesKey("network_presets_json")
        val ACTIVE_PRESET_ID = stringPreferencesKey("active_preset_id")
        val AUTO_SWITCH_PRESETS = booleanPreferencesKey("auto_switch_presets")
        val BLOCK_ADS_DNS = booleanPreferencesKey("block_ads_dns")
        val ALLOW_IPV6 = booleanPreferencesKey("allow_ipv6")
        val TUN_MTU = intPreferencesKey("tun_mtu")
        val AUTO_PING_INTERVAL_SEC = intPreferencesKey("auto_ping_interval_sec")
        val SHOW_TRAFFIC_NOTIFICATION = booleanPreferencesKey("show_traffic_notification")
        val ENABLE_HAPTICS = booleanPreferencesKey("enable_haptics")
        val ENABLE_WATER_WAVE_EFFECT = booleanPreferencesKey("enable_water_wave_effect")
        val WAVE_SPEED = floatPreferencesKey("wave_speed")
        val WAVE_STRENGTH = floatPreferencesKey("wave_strength")
        val WAVE_SMOOTHNESS = floatPreferencesKey("wave_smoothness")
        val WAVE_ELASTICITY = floatPreferencesKey("wave_elasticity")
        val WAVE_TINT_STRENGTH = floatPreferencesKey("wave_tint_strength")
        val WAVE_DISPERSION = floatPreferencesKey("wave_dispersion")
        val WAVE_RING_WIDTH = floatPreferencesKey("wave_ring_width")
        val ENABLE_FLAG_BACKDROP = booleanPreferencesKey("enable_flag_backdrop")
        val ENABLE_FLAG_MATRIX_TRANSITION = booleanPreferencesKey("enable_flag_matrix_transition")
        val FLAG_MATRIX_DIRECTION = stringPreferencesKey("flag_matrix_direction")
        val FLAG_TILT_DEGREES = floatPreferencesKey("flag_tilt_degrees")
        val FLAG_MATRIX_ANGLE_DEGREES = floatPreferencesKey("flag_matrix_angle_degrees")
        val FLAG_MATRIX_RANDOM_ANGLE = booleanPreferencesKey("flag_matrix_random_angle")
        val FLAG_ANGLE_SNAP_TO_TICKS = booleanPreferencesKey("flag_angle_snap_to_ticks")
        val FLAG_MATRIX_SPEED = floatPreferencesKey("flag_matrix_speed")
        val FLAG_MATRIX_ZOOM = floatPreferencesKey("flag_matrix_zoom")
        val FLAG_MATRIX_HIGHLIGHT_SERVERS = booleanPreferencesKey("flag_matrix_highlight_servers")
        val FLAG_BACKDROP_SCALE = floatPreferencesKey("flag_backdrop_scale")
        val FLAG_BACKDROP_DIM = floatPreferencesKey("flag_backdrop_dim")
        val FLAG_SHOW_COAT_OF_ARMS = booleanPreferencesKey("flag_show_coat_of_arms")
        val FLAG_PHYSICS_INTENSITY = floatPreferencesKey("flag_physics_intensity")
        val BACKGROUND_GRADIENT_STYLE = stringPreferencesKey("background_gradient_style")
        val ENABLE_SOLAR_ATMOSPHERE = booleanPreferencesKey("enable_solar_atmosphere")
        val SOLAR_ATMOSPHERE_INTENSITY = floatPreferencesKey("solar_atmosphere_intensity")
        val WEATHER_EFFECT_MODE = stringPreferencesKey("weather_effect_mode")
        val ENABLE_WEATHER_HAPTICS = booleanPreferencesKey("enable_weather_haptics")
        val WEATHER_INTENSITY = floatPreferencesKey("weather_intensity")
        val WEATHER_WIND_STRENGTH = floatPreferencesKey("weather_wind_strength")
        val WEATHER_FOG_DEPTH = floatPreferencesKey("weather_fog_depth")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val RECONNECT_ON_FAILURE = booleanPreferencesKey("reconnect_on_failure")
        val RECONNECT_ON_NETWORK_CHANGE = booleanPreferencesKey("reconnect_on_network_change")
        val AUTO_ROTATE_SNI_ON_FAIL = booleanPreferencesKey("auto_rotate_sni_on_fail")
        val AUTO_FAILOVER_SERVER = booleanPreferencesKey("auto_failover_server")
        val MAX_RECONNECT_ATTEMPTS = intPreferencesKey("max_reconnect_attempts")
        val RECONNECT_DELAY_SEC = intPreferencesKey("reconnect_delay_sec")
        val HANDSHAKE_TIMEOUT_SEC = intPreferencesKey("handshake_timeout_sec")
        val WS_OPEN_TIMEOUT_SEC = intPreferencesKey("ws_open_timeout_sec")
        val KEEPALIVE_INTERVAL_SEC = intPreferencesKey("keepalive_interval_sec")
        val NETWORK_SWITCH_DEBOUNCE_SEC = intPreferencesKey("network_switch_debounce_sec")
        val ENABLE_STALL_WATCHDOG = booleanPreferencesKey("enable_stall_watchdog")
        val WATCHDOG_SENSITIVITY = stringPreferencesKey("watchdog_sensitivity")
        val WATCHDOG_PROBE_SEC = intPreferencesKey("watchdog_probe_sec")
        val WATCHDOG_STALL_SEC = intPreferencesKey("watchdog_stall_sec")
        val WATCHDOG_COOLDOWN_SEC = intPreferencesKey("watchdog_cooldown_sec")
        val HOME_QUICK_TILES = stringPreferencesKey("home_quick_tiles")
        val MANUAL_OVERRIDES = stringSetPreferencesKey("manual_overrides")
        val INSTALL_RANDOM_SEED = longPreferencesKey("install_random_seed")
    }

    val settingsFlow: Flow<VpnSettings> = context.fptnSettingsDataStore.data.map { prefs ->
        val sortOrder = ServerSortOrder.entries.firstOrNull { it.name == prefs[Keys.SERVER_SORT_ORDER] }
            ?: ServerSortOrder.ORIGINAL
        val bypass = parseBypassMethodCompat(prefs[Keys.BYPASS_METHOD])
        val sniMode = SniSpoofingMode.entries.firstOrNull { it.name == prefs[Keys.SNI_SPOOFING_MODE] }
            ?: SniSpoofingMode.SNI
        val sliding = parseSlidingTunnelCompat(prefs[Keys.SLIDING_TUNNEL_MODE])
        val splitMode = SplitTunnelMode.entries.firstOrNull { it.name == prefs[Keys.SPLIT_TUNNEL_MODE] }
            ?: SplitTunnelMode.ALL_APPS
        val theme = ThemeMode.entries.firstOrNull { it.name == prefs[Keys.THEME_MODE] } ?: ThemeMode.SYSTEM
        val appLanguage = AppLanguage.entries.firstOrNull { it.name == prefs[Keys.APP_LANGUAGE] } ?: AppLanguage.SYSTEM
        val bypassRegion = BypassRegion.entries.firstOrNull { it.name == prefs[Keys.BYPASS_REGION] }
            ?: BypassRegion.RUSSIA
        val savedDns = DnsEndpointParser.decodeEntries(prefs[Keys.SAVED_DNS_ENTRIES_JSON])
            ?: VpnSettings.DEFAULT_DNS_ENTRIES

        val seed = prefs[Keys.INSTALL_RANDOM_SEED] ?: cachedFallbackSeed
        val presets = NetworkPresetCodec.decode(prefs[Keys.NETWORK_PRESETS_JSON])
            ?.takeIf { it.isNotEmpty() }
            ?: VpnSettings.generateRandomizedDefaultPresets(
                sniPool = SniAutoScanner.loadOfficialSniCatalog(context),
                seed = seed,
            )

        val quickTiles = NetworkPresetCodec.decodeQuickTiles(prefs[Keys.HOME_QUICK_TILES])
            ?: VpnSettings.DEFAULT_HOME_QUICK_TILES

        // Migrate legacy conflicting DNS booleans cleanly into single mutually-exclusive DnsMode
        val legacyCustomDoh = prefs[Keys.ENABLE_CUSTOM_DNS_URL] ?: false
        val legacyBlockAds = prefs[Keys.BLOCK_ADS_DNS] ?: false
        val resolvedDnsMode = DnsMode.entries.firstOrNull { it.name == prefs[Keys.DNS_MODE] }
            ?: when {
                legacyCustomDoh -> DnsMode.SMART_DNS_DOH
                legacyBlockAds -> DnsMode.ADBLOCK_DNS
                else -> DnsMode.AUTO_VPN
            }

        VpnSettings(
            selectedServerId = prefs[Keys.SELECTED_SERVER_ID],
            autoSelectBestServer = prefs[Keys.AUTO_SELECT_BEST_SERVER] ?: true,
            serverSortOrder = sortOrder,
            bypassMethod = bypass,
            sniSpoofingMode = sniMode,
            customSni = prefs[Keys.CUSTOM_SNI] ?: VpnSettings.DEFAULT_SNI_DOMAIN,
            slidingTunnelMode = sliding,
            enableObfuscation = prefs[Keys.ENABLE_OBFUSCATION] ?: true,
            smartRfRouting = true,
            bypassRegion = BypassRegion.sanitize(bypassRegion),
            customDirectDomains = prefs[Keys.CUSTOM_DIRECT_DOMAINS] ?: emptySet(),
            customVpnDomains = prefs[Keys.CUSTOM_VPN_DOMAINS] ?: emptySet(),
            unlockProtectedRuWithRoot = false,
            forceTelegramViaVpn = true,
            useRootMode = prefs[Keys.USE_ROOT_MODE] ?: false,
            allowSecondaryVpnChaining = prefs[Keys.ALLOW_SECONDARY_VPN_CHAINING] ?: true,
            rootTunInterfaceName = com.example.domain.root.SafeRootRulePlanner.sanitizeTunName(prefs[Keys.ROOT_TUN_INTERFACE_NAME]),
            rootAdaptiveBoostEnabled = prefs[Keys.ROOT_ADAPTIVE_BOOST_ENABLED] ?: false,
            splitTunnelMode = splitMode,
            selectedPackages = prefs[Keys.SELECTED_PACKAGES] ?: emptySet(),
            dnsMode = resolvedDnsMode,
            dnsPrimary = prefs[Keys.DNS_PRIMARY] ?: "1.1.1.1",
            dnsSecondary = prefs[Keys.DNS_SECONDARY] ?: "8.8.8.8",
            customDnsUrl = (prefs[Keys.CUSTOM_DNS_URL] ?: "").ifBlank { VpnSettings.DEFAULT_SMART_DNS_URL },
            enableCustomDnsUrl = resolvedDnsMode == DnsMode.SMART_DNS_DOH,
            savedDnsEntries = savedDns,
            networkPresets = presets,
            activePresetId = prefs[Keys.ACTIVE_PRESET_ID],
            autoSwitchPresetsByNetwork = prefs[Keys.AUTO_SWITCH_PRESETS] ?: false,
            blockAdsDns = resolvedDnsMode == DnsMode.ADBLOCK_DNS,
            allowIpv6 = prefs[Keys.ALLOW_IPV6] ?: false,
            tunMtu = (prefs[Keys.TUN_MTU] ?: 1400).coerceIn(1280, 1500),
            autoPingIntervalSec = prefs[Keys.AUTO_PING_INTERVAL_SEC] ?: 60,
            showTrafficInNotification = prefs[Keys.SHOW_TRAFFIC_NOTIFICATION] ?: true,
            enableHaptics = prefs[Keys.ENABLE_HAPTICS] ?: true,
            enableWaterWaveEffect = prefs[Keys.ENABLE_WATER_WAVE_EFFECT] ?: true,
            waveSpeed = (prefs[Keys.WAVE_SPEED] ?: 0.72f).coerceIn(0.35f, 1.8f),
            waveStrength = (prefs[Keys.WAVE_STRENGTH] ?: 0.85f).coerceIn(0.25f, 1.8f),
            waveSmoothness = (prefs[Keys.WAVE_SMOOTHNESS] ?: 0.75f).coerceIn(0.25f, 1.6f),
            waveElasticity = (prefs[Keys.WAVE_ELASTICITY] ?: 0.65f).coerceIn(0.15f, 1.5f),
            waveTintStrength = (prefs[Keys.WAVE_TINT_STRENGTH] ?: 0.30f).coerceIn(0.05f, 1.0f),
            waveDispersion = (prefs[Keys.WAVE_DISPERSION] ?: 0.85f).coerceIn(0.0f, 1.8f),
            waveRingWidth = (prefs[Keys.WAVE_RING_WIDTH] ?: 1.0f).coerceIn(0.55f, 1.65f),
            enableFlagBackdrop = prefs[Keys.ENABLE_FLAG_BACKDROP] ?: true,
            enableFlagMatrixTransition = prefs[Keys.ENABLE_FLAG_MATRIX_TRANSITION] ?: true,
            flagMatrixDirection = FlagMatrixDirection.entries.firstOrNull {
                it.name == prefs[Keys.FLAG_MATRIX_DIRECTION]
            } ?: FlagMatrixDirection.RIGHT_TO_LEFT,
            flagTiltDegrees = normalizeAngle360(prefs[Keys.FLAG_TILT_DEGREES] ?: 0f),
            flagMatrixAngleDegrees = normalizeAngle360(
                prefs[Keys.FLAG_MATRIX_ANGLE_DEGREES]
                    ?: (FlagMatrixDirection.entries.firstOrNull {
                        it.name == prefs[Keys.FLAG_MATRIX_DIRECTION]
                    } ?: FlagMatrixDirection.RIGHT_TO_LEFT).toClockDegrees(),
            ),
            flagMatrixRandomAngle = prefs[Keys.FLAG_MATRIX_RANDOM_ANGLE]
                ?: (prefs[Keys.FLAG_MATRIX_DIRECTION] == FlagMatrixDirection.RANDOM_EACH_TIME.name),
            flagAngleSnapToTicks = prefs[Keys.FLAG_ANGLE_SNAP_TO_TICKS] ?: true,
            flagMatrixSpeed = (prefs[Keys.FLAG_MATRIX_SPEED] ?: 1.0f).coerceIn(0.40f, 2.20f),
            flagMatrixZoom = (prefs[Keys.FLAG_MATRIX_ZOOM] ?: 1.0f).coerceIn(0.65f, 1.45f),
            flagMatrixHighlightServers = prefs[Keys.FLAG_MATRIX_HIGHLIGHT_SERVERS] ?: true,
            flagBackdropScale = (prefs[Keys.FLAG_BACKDROP_SCALE] ?: 1.15f).coerceIn(0.85f, 1.55f),
            flagBackdropDim = (prefs[Keys.FLAG_BACKDROP_DIM] ?: 0.42f).coerceIn(0.10f, 0.78f),
            flagShowCoatOfArms = prefs[Keys.FLAG_SHOW_COAT_OF_ARMS] ?: true,
            flagPhysicsIntensity = (prefs[Keys.FLAG_PHYSICS_INTENSITY] ?: 1.0f).coerceIn(0.0f, 1.6f),
            backgroundGradientStyle = BackgroundGradientStyle.entries.firstOrNull {
                it.name == prefs[Keys.BACKGROUND_GRADIENT_STYLE]
            } ?: BackgroundGradientStyle.GLASS_AURORA,
            enableSolarAtmosphere = prefs[Keys.ENABLE_SOLAR_ATMOSPHERE] ?: true,
            solarAtmosphereIntensity = (prefs[Keys.SOLAR_ATMOSPHERE_INTENSITY] ?: 1.15f).coerceIn(0.25f, 2.0f),
            weatherEffectMode = WeatherEffectMode.entries.firstOrNull {
                it.name == prefs[Keys.WEATHER_EFFECT_MODE]
            } ?: WeatherEffectMode.OFF,
            enableWeatherHaptics = prefs[Keys.ENABLE_WEATHER_HAPTICS] ?: true,
            weatherIntensity = (prefs[Keys.WEATHER_INTENSITY] ?: 0.75f).coerceIn(0.25f, 1.5f),
            weatherWindStrength = (prefs[Keys.WEATHER_WIND_STRENGTH] ?: 0.55f).coerceIn(0.0f, 1.5f),
            weatherFogDepth = (prefs[Keys.WEATHER_FOG_DEPTH] ?: 0.80f).coerceIn(0.25f, 1.5f),
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
            themeMode = theme,
            appLanguage = appLanguage,
            reconnectOnFailure = prefs[Keys.RECONNECT_ON_FAILURE] ?: true,
            reconnectOnNetworkChange = prefs[Keys.RECONNECT_ON_NETWORK_CHANGE] ?: true,
            autoRotateSniOnFail = prefs[Keys.AUTO_ROTATE_SNI_ON_FAIL] ?: true,
            autoFailoverServer = prefs[Keys.AUTO_FAILOVER_SERVER] ?: true,
            maxReconnectAttempts = (prefs[Keys.MAX_RECONNECT_ATTEMPTS] ?: 15).coerceIn(3, 50),
            reconnectDelaySec = (prefs[Keys.RECONNECT_DELAY_SEC] ?: 2).coerceIn(1, 10),
            handshakeTimeoutSec = (prefs[Keys.HANDSHAKE_TIMEOUT_SEC] ?: 8).coerceIn(4, 20),
            wsOpenTimeoutSec = (prefs[Keys.WS_OPEN_TIMEOUT_SEC] ?: 15).coerceIn(5, 30),
            keepaliveIntervalSec = (prefs[Keys.KEEPALIVE_INTERVAL_SEC] ?: 18).coerceIn(0, 60),
            networkSwitchDebounceSec = (prefs[Keys.NETWORK_SWITCH_DEBOUNCE_SEC] ?: 3).coerceIn(1, 10),
            enableStallWatchdog = prefs[Keys.ENABLE_STALL_WATCHDOG] ?: true,
            watchdogSensitivity = WatchdogSensitivity.entries.firstOrNull {
                it.name == prefs[Keys.WATCHDOG_SENSITIVITY]
            } ?: WatchdogSensitivity.GENTLE,
            watchdogProbeSec = (prefs[Keys.WATCHDOG_PROBE_SEC] ?: 10).coerceIn(2, 25),
            watchdogStallSec = (prefs[Keys.WATCHDOG_STALL_SEC] ?: 24).coerceIn(4, 55),
            watchdogCooldownSec = (prefs[Keys.WATCHDOG_COOLDOWN_SEC] ?: 35).coerceIn(10, 90),
            homeQuickTiles = quickTiles,
            manualOverrides = (prefs[Keys.MANUAL_OVERRIDES] ?: emptySet())
                .mapNotNull { raw -> ManualOverrideKey.entries.firstOrNull { it.name == raw } }
                .toSet(),
        )
    }

    /**
     * Ensures a stable per-installation random seed, randomized default presets, and unique per-device
     * initial SNI domain are persisted on first launch (prevents everyone sharing the same default SNI).
     */
    suspend fun ensureInstallSeedInitialized() {
        context.fptnSettingsDataStore.edit { prefs ->
            val seed = prefs[Keys.INSTALL_RANDOM_SEED] ?: cachedFallbackSeed.also {
                prefs[Keys.INSTALL_RANDOM_SEED] = it
            }
            val officialPool = SniAutoScanner.loadOfficialSniCatalog(context)
            if (prefs[Keys.NETWORK_PRESETS_JSON] == null) {
                val randomized = VpnSettings.generateRandomizedDefaultPresets(
                    sniPool = officialPool,
                    seed = seed,
                )
                prefs[Keys.NETWORK_PRESETS_JSON] = NetworkPresetCodec.encode(randomized)
            }
            if (prefs[Keys.CUSTOM_SNI] == null) {
                val region = BypassRegion.entries.firstOrNull { it.name == prefs[Keys.BYPASS_REGION] }
                    ?: BypassRegion.RUSSIA
                val candidatePool = (region.recommendedSnis + officialPool)
                    .map { it.trim().lowercase() }
                    .filter { it.contains('.') && !it.contains(' ') }
                    .distinct()
                val chosenSni = if (candidatePool.isNotEmpty()) {
                    val idx = kotlin.math.abs((seed xor 0x5DEECE66DL).hashCode()) % candidatePool.size
                    candidatePool[idx]
                } else {
                    VpnSettings.DEFAULT_SNI_DOMAIN
                }
                prefs[Keys.CUSTOM_SNI] = chosenSni
            }
        }
    }

    suspend fun getCurrentSettings(): VpnSettings = settingsFlow.first()

    suspend fun updateSettings(transform: (VpnSettings) -> VpnSettings) {
        updateMutex.withLock {
            val current = getCurrentSettings()
            val updated = transform(current)
            saveSettings(updated)
        }
    }

    suspend fun saveSettings(settings: VpnSettings) {
        context.fptnSettingsDataStore.edit { prefs ->
            if (prefs[Keys.INSTALL_RANDOM_SEED] == null) {
                prefs[Keys.INSTALL_RANDOM_SEED] = cachedFallbackSeed
            }
            if (settings.selectedServerId != null) {
                prefs[Keys.SELECTED_SERVER_ID] = settings.selectedServerId
            } else {
                prefs.remove(Keys.SELECTED_SERVER_ID)
            }
            prefs[Keys.AUTO_SELECT_BEST_SERVER] = settings.autoSelectBestServer
            prefs[Keys.SERVER_SORT_ORDER] = settings.serverSortOrder.name
            prefs[Keys.BYPASS_METHOD] = settings.bypassMethod.name
            prefs[Keys.SNI_SPOOFING_MODE] = settings.sniSpoofingMode.name
            prefs[Keys.CUSTOM_SNI] = settings.customSni
            prefs[Keys.SLIDING_TUNNEL_MODE] = settings.slidingTunnelMode.name
            prefs[Keys.ENABLE_OBFUSCATION] = settings.enableObfuscation
            prefs[Keys.SMART_RF_ROUTING] = true
            prefs[Keys.BYPASS_REGION] = BypassRegion.sanitize(settings.bypassRegion).name
            prefs[Keys.CUSTOM_DIRECT_DOMAINS] = settings.customDirectDomains
            prefs[Keys.CUSTOM_VPN_DOMAINS] = settings.customVpnDomains
            prefs[Keys.UNLOCK_PROTECTED_RU_ROOT] = false
            prefs[Keys.USE_ROOT_MODE] = settings.useRootMode
            prefs[Keys.ALLOW_SECONDARY_VPN_CHAINING] = settings.allowSecondaryVpnChaining
            prefs[Keys.ROOT_TUN_INTERFACE_NAME] = com.example.domain.root.SafeRootRulePlanner.sanitizeTunName(settings.rootTunInterfaceName)
            prefs[Keys.ROOT_ADAPTIVE_BOOST_ENABLED] = settings.rootAdaptiveBoostEnabled
            prefs[Keys.SPLIT_TUNNEL_MODE] = settings.splitTunnelMode.name
            prefs[Keys.SELECTED_PACKAGES] = settings.selectedPackages
            prefs[Keys.DNS_MODE] = settings.dnsMode.name
            prefs[Keys.DNS_PRIMARY] = settings.dnsPrimary
            prefs[Keys.DNS_SECONDARY] = settings.dnsSecondary
            prefs[Keys.CUSTOM_DNS_URL] = settings.customDnsUrl
            prefs[Keys.ENABLE_CUSTOM_DNS_URL] = settings.dnsMode == DnsMode.SMART_DNS_DOH
            prefs[Keys.SAVED_DNS_ENTRIES_JSON] = DnsEndpointParser.encodeEntries(settings.savedDnsEntries)
            prefs[Keys.NETWORK_PRESETS_JSON] = NetworkPresetCodec.encode(settings.networkPresets)
            if (settings.activePresetId != null) {
                prefs[Keys.ACTIVE_PRESET_ID] = settings.activePresetId
            } else {
                prefs.remove(Keys.ACTIVE_PRESET_ID)
            }
            prefs[Keys.AUTO_SWITCH_PRESETS] = settings.autoSwitchPresetsByNetwork
            prefs[Keys.BLOCK_ADS_DNS] = settings.dnsMode == DnsMode.ADBLOCK_DNS
            prefs[Keys.ALLOW_IPV6] = settings.allowIpv6
            prefs[Keys.TUN_MTU] = settings.tunMtu.coerceIn(1280, 1500)
            prefs[Keys.AUTO_PING_INTERVAL_SEC] = settings.autoPingIntervalSec
            prefs[Keys.SHOW_TRAFFIC_NOTIFICATION] = settings.showTrafficInNotification
            prefs[Keys.ENABLE_HAPTICS] = settings.enableHaptics
            prefs[Keys.ENABLE_WATER_WAVE_EFFECT] = settings.enableWaterWaveEffect
            prefs[Keys.WAVE_SPEED] = settings.waveSpeed.coerceIn(0.35f, 1.8f)
            prefs[Keys.WAVE_STRENGTH] = settings.waveStrength.coerceIn(0.25f, 1.8f)
            prefs[Keys.WAVE_SMOOTHNESS] = settings.waveSmoothness.coerceIn(0.25f, 1.6f)
            prefs[Keys.WAVE_ELASTICITY] = settings.waveElasticity.coerceIn(0.15f, 1.5f)
            prefs[Keys.WAVE_TINT_STRENGTH] = settings.waveTintStrength.coerceIn(0.05f, 1.0f)
            prefs[Keys.WAVE_DISPERSION] = settings.waveDispersion.coerceIn(0.0f, 1.8f)
            prefs[Keys.WAVE_RING_WIDTH] = settings.waveRingWidth.coerceIn(0.55f, 1.65f)
            prefs[Keys.ENABLE_FLAG_BACKDROP] = settings.enableFlagBackdrop
            prefs[Keys.ENABLE_FLAG_MATRIX_TRANSITION] = settings.enableFlagMatrixTransition
            prefs[Keys.FLAG_MATRIX_DIRECTION] = settings.flagMatrixDirection.name
            prefs[Keys.FLAG_TILT_DEGREES] = normalizeAngle360(settings.flagTiltDegrees)
            prefs[Keys.FLAG_MATRIX_ANGLE_DEGREES] = normalizeAngle360(settings.flagMatrixAngleDegrees)
            prefs[Keys.FLAG_MATRIX_RANDOM_ANGLE] = settings.flagMatrixRandomAngle
            prefs[Keys.FLAG_ANGLE_SNAP_TO_TICKS] = settings.flagAngleSnapToTicks
            prefs[Keys.FLAG_MATRIX_SPEED] = settings.flagMatrixSpeed.coerceIn(0.40f, 2.20f)
            prefs[Keys.FLAG_MATRIX_ZOOM] = settings.flagMatrixZoom.coerceIn(0.65f, 1.45f)
            prefs[Keys.FLAG_MATRIX_HIGHLIGHT_SERVERS] = settings.flagMatrixHighlightServers
            prefs[Keys.FLAG_BACKDROP_SCALE] = settings.flagBackdropScale.coerceIn(0.85f, 1.55f)
            prefs[Keys.FLAG_BACKDROP_DIM] = settings.flagBackdropDim.coerceIn(0.10f, 0.78f)
            prefs[Keys.FLAG_SHOW_COAT_OF_ARMS] = settings.flagShowCoatOfArms
            prefs[Keys.FLAG_PHYSICS_INTENSITY] = settings.flagPhysicsIntensity.coerceIn(0.0f, 1.6f)
            prefs[Keys.BACKGROUND_GRADIENT_STYLE] = settings.backgroundGradientStyle.name
            prefs[Keys.ENABLE_SOLAR_ATMOSPHERE] = settings.enableSolarAtmosphere
            prefs[Keys.SOLAR_ATMOSPHERE_INTENSITY] = settings.solarAtmosphereIntensity.coerceIn(0.25f, 2.0f)
            prefs[Keys.WEATHER_EFFECT_MODE] = settings.weatherEffectMode.name
            prefs[Keys.ENABLE_WEATHER_HAPTICS] = settings.enableWeatherHaptics
            prefs[Keys.WEATHER_INTENSITY] = settings.weatherIntensity.coerceIn(0.25f, 1.5f)
            prefs[Keys.WEATHER_WIND_STRENGTH] = settings.weatherWindStrength.coerceIn(0.0f, 1.5f)
            prefs[Keys.WEATHER_FOG_DEPTH] = settings.weatherFogDepth.coerceIn(0.25f, 1.5f)
            prefs[Keys.DYNAMIC_COLOR] = settings.dynamicColor
            prefs[Keys.THEME_MODE] = settings.themeMode.name
            prefs[Keys.APP_LANGUAGE] = settings.appLanguage.name
            prefs[Keys.RECONNECT_ON_FAILURE] = settings.reconnectOnFailure
            prefs[Keys.RECONNECT_ON_NETWORK_CHANGE] = settings.reconnectOnNetworkChange
            prefs[Keys.AUTO_ROTATE_SNI_ON_FAIL] = settings.autoRotateSniOnFail
            prefs[Keys.AUTO_FAILOVER_SERVER] = settings.autoFailoverServer
            prefs[Keys.MAX_RECONNECT_ATTEMPTS] = settings.maxReconnectAttempts.coerceIn(3, 50)
            prefs[Keys.RECONNECT_DELAY_SEC] = settings.reconnectDelaySec.coerceIn(1, 10)
            prefs[Keys.HANDSHAKE_TIMEOUT_SEC] = settings.handshakeTimeoutSec.coerceIn(4, 20)
            prefs[Keys.WS_OPEN_TIMEOUT_SEC] = settings.wsOpenTimeoutSec.coerceIn(5, 30)
            prefs[Keys.KEEPALIVE_INTERVAL_SEC] = settings.keepaliveIntervalSec.coerceIn(0, 60)
            prefs[Keys.NETWORK_SWITCH_DEBOUNCE_SEC] = settings.networkSwitchDebounceSec.coerceIn(1, 10)
            prefs[Keys.ENABLE_STALL_WATCHDOG] = settings.enableStallWatchdog
            prefs[Keys.WATCHDOG_SENSITIVITY] = settings.watchdogSensitivity.name
            prefs[Keys.WATCHDOG_PROBE_SEC] = settings.watchdogProbeSec.coerceIn(2, 25)
            prefs[Keys.WATCHDOG_STALL_SEC] = settings.watchdogStallSec.coerceIn(4, 55)
            prefs[Keys.WATCHDOG_COOLDOWN_SEC] = settings.watchdogCooldownSec.coerceIn(10, 90)
            prefs[Keys.HOME_QUICK_TILES] = NetworkPresetCodec.encodeQuickTiles(settings.homeQuickTiles)
            prefs[Keys.MANUAL_OVERRIDES] = settings.manualOverrides.map { it.name }.toSet()
        }
    }

    companion object {
        fun parseBypassMethodCompat(name: String?): BypassMethod {
            if (name == null) return BypassMethod.SNI_REALITY
            BypassMethod.entries.firstOrNull { it.name == name }?.let { return it }
            return when {
                name.contains("OBFUSC", ignoreCase = true) -> BypassMethod.TLS_OBFUSCATION
                else -> BypassMethod.SNI_REALITY
            }
        }

        fun parseSlidingTunnelCompat(name: String?): SlidingTunnelMode {
            if (name == null) return SlidingTunnelMode.ROLLING_TUNNEL
            SlidingTunnelMode.entries.firstOrNull { it.name == name }?.let { return it }
            return when {
                name.contains("TRIPLE", ignoreCase = true) -> SlidingTunnelMode.TRIPLE_TUNNEL
                name.contains("DUAL", ignoreCase = true) || name.contains("DOUBLE", ignoreCase = true) ->
                    SlidingTunnelMode.DUAL_TUNNEL
                else -> SlidingTunnelMode.ROLLING_TUNNEL
            }
        }

        fun normalizeAngle360(degrees: Float): Float {
            if (!degrees.isFinite()) return 0f
            val mod = degrees % 360f
            return if (mod < 0f) mod + 360f else mod
        }
    }
}
