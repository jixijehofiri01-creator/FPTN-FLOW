package com.example.data.repository

import com.example.data.local.SettingsDataStore
import com.example.domain.dns.DnsEndpointParser
import com.example.domain.model.AppLanguage
import com.example.domain.model.BackgroundGradientStyle
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsMode
import com.example.domain.model.FlagMatrixDirection
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.NetworkPresetCodec
import com.example.domain.model.ServerSortOrder
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemeMode
import com.example.domain.model.TokenProfile
import com.example.domain.model.VpnSettings
import com.example.domain.model.WeatherEffectMode
import com.example.domain.vpn.WatchdogSensitivity
import org.json.JSONArray
import org.json.JSONObject

data class BackupPayload(
    val profiles: List<BackupProfileEntry>,
    val settings: VpnSettings,
    val detectedFormat: BackupFormat = BackupFormat.FULL_CLIENT,
)

enum class BackupFormat(val title: String) {
    FULL_CLIENT("Полная резервная копия (v14)"),
    OFFICIAL_COMPATIBLE("Совместимый формат FPTN"),
    RAW_TOKENS("Токены fptn:/fptnb:"),
}

data class BackupProfileEntry(
    val name: String,
    val rawToken: String,
    val enabled: Boolean,
)

object BackupCodec {

    private const val BACKUP_SCHEMA_VERSION = 14

    /**
     * Full client backup (schema v13): exports ALL tokens, presets, quick tiles, Smart DNS catalog,
     * custom domain rules, routing, timeouts, watchdog, self-healing, theme, haptics, solar/weather,
     * flag backdrop, and AGSL wave preferences.
     */
    fun encode(profiles: List<TokenProfile>, settings: VpnSettings): String {
        val root = JSONObject().apply {
            put("format", "fptn_client_full")
            put("schema_version", BACKUP_SCHEMA_VERSION)
            put("exported_at", System.currentTimeMillis())
            put(
                "profiles",
                JSONArray().apply {
                    profiles.forEach { profile ->
                        put(
                            JSONObject().apply {
                                put("name", profile.name)
                                put("raw_token", profile.rawToken)
                                put("enabled", profile.enabled)
                            },
                        )
                    }
                },
            )
            put(
                "settings",
                JSONObject().apply {
                    put("auto_select_best_server", settings.autoSelectBestServer)
                    put("server_sort_order", settings.serverSortOrder.name)
                    put("bypass_method", settings.bypassMethod.name)
                    put("sni_spoofing_mode", settings.sniSpoofingMode.name)
                    put("custom_sni", settings.customSni)
                    put("sliding_tunnel_mode", settings.slidingTunnelMode.name)
                    put("enable_obfuscation", settings.enableObfuscation)
                    put("smart_rf_routing", true)
                    put("bypass_region", BypassRegion.sanitize(settings.bypassRegion).name)
                    put("custom_direct_domains", JSONArray(settings.customDirectDomains.toList()))
                    put("custom_vpn_domains", JSONArray(settings.customVpnDomains.toList()))
                    put("unlock_protected_ru_root", false)
                    put("force_telegram_via_vpn", true)
                    put("use_root_mode", settings.useRootMode)
                    put("allow_secondary_vpn_chaining", settings.allowSecondaryVpnChaining)
                    put("root_tun_interface_name", settings.rootTunInterfaceName)
                    put("root_adaptive_boost_enabled", settings.rootAdaptiveBoostEnabled)
                    put("split_tunnel_mode", settings.splitTunnelMode.name)
                    put("selected_packages", JSONArray(settings.selectedPackages.toList()))
                    put("dns_mode", settings.dnsMode.name)
                    put("dns_primary", settings.dnsPrimary)
                    put("dns_secondary", settings.dnsSecondary)
                    put("custom_dns_url", settings.customDnsUrl)
                    put("enable_custom_dns_url", settings.dnsMode == DnsMode.SMART_DNS_DOH)
                    put("saved_dns_entries_json", DnsEndpointParser.encodeEntries(settings.savedDnsEntries))
                    put("network_presets_json", NetworkPresetCodec.encode(settings.networkPresets))
                    put("active_preset_id", settings.activePresetId ?: "")
                    put("auto_switch_presets", settings.autoSwitchPresetsByNetwork)
                    put("block_ads_dns", settings.dnsMode == DnsMode.ADBLOCK_DNS)
                    put("allow_ipv6", settings.allowIpv6)
                    put("tun_mtu", settings.tunMtu)
                    put("auto_ping_interval_sec", settings.autoPingIntervalSec)
                    put("show_traffic_in_notification", settings.showTrafficInNotification)
                    put("enable_haptics", settings.enableHaptics)
                    put("enable_water_wave_effect", settings.enableWaterWaveEffect)
                    put("wave_speed", settings.waveSpeed.toDouble())
                    put("wave_strength", settings.waveStrength.toDouble())
                    put("wave_smoothness", settings.waveSmoothness.toDouble())
                    put("wave_elasticity", settings.waveElasticity.toDouble())
                    put("wave_tint_strength", settings.waveTintStrength.toDouble())
                    put("wave_dispersion", settings.waveDispersion.toDouble())
                    put("wave_ring_width", settings.waveRingWidth.toDouble())
                    put("enable_flag_backdrop", settings.enableFlagBackdrop)
                    put("enable_flag_matrix_transition", settings.enableFlagMatrixTransition)
                    put("flag_matrix_direction", settings.flagMatrixDirection.name)
                    put("flag_tilt_degrees", settings.flagTiltDegrees.toDouble())
                    put("flag_matrix_angle_degrees", settings.flagMatrixAngleDegrees.toDouble())
                    put("flag_matrix_random_angle", settings.flagMatrixRandomAngle)
                    put("flag_angle_snap_to_ticks", settings.flagAngleSnapToTicks)
                    put("flag_matrix_speed", settings.flagMatrixSpeed.toDouble())
                    put("flag_matrix_zoom", settings.flagMatrixZoom.toDouble())
                    put("flag_matrix_highlight_servers", settings.flagMatrixHighlightServers)
                    put("flag_backdrop_scale", settings.flagBackdropScale.toDouble())
                    put("flag_backdrop_dim", settings.flagBackdropDim.toDouble())
                    put("flag_show_coat_of_arms", settings.flagShowCoatOfArms)
                    put("flag_physics_intensity", settings.flagPhysicsIntensity.toDouble())
                    put("background_gradient_style", settings.backgroundGradientStyle.name)
                    put("enable_solar_atmosphere", settings.enableSolarAtmosphere)
                    put("solar_atmosphere_intensity", settings.solarAtmosphereIntensity.toDouble())
                    put("weather_effect_mode", settings.weatherEffectMode.name)
                    put("enable_weather_haptics", settings.enableWeatherHaptics)
                    put("weather_intensity", settings.weatherIntensity.toDouble())
                    put("weather_wind_strength", settings.weatherWindStrength.toDouble())
                    put("weather_fog_depth", settings.weatherFogDepth.toDouble())
                    put("dynamic_color", settings.dynamicColor)
                    put("theme_mode", settings.themeMode.name)
                    put("app_language", settings.appLanguage.name)
                    put("reconnect_on_failure", settings.reconnectOnFailure)
                    put("reconnect_on_network_change", settings.reconnectOnNetworkChange)
                    put("auto_rotate_sni_on_fail", settings.autoRotateSniOnFail)
                    put("auto_failover_server", settings.autoFailoverServer)
                    put("max_reconnect_attempts", settings.maxReconnectAttempts)
                    put("reconnect_delay_sec", settings.reconnectDelaySec)
                    put("handshake_timeout_sec", settings.handshakeTimeoutSec)
                    put("ws_open_timeout_sec", settings.wsOpenTimeoutSec)
                    put("keepalive_interval_sec", settings.keepaliveIntervalSec)
                    put("network_switch_debounce_sec", settings.networkSwitchDebounceSec)
                    put("enable_stall_watchdog", settings.enableStallWatchdog)
                    put("watchdog_sensitivity", settings.watchdogSensitivity.name)
                    put("watchdog_probe_sec", settings.watchdogProbeSec)
                    put("watchdog_stall_sec", settings.watchdogStallSec)
                    put("watchdog_cooldown_sec", settings.watchdogCooldownSec)
                    put("home_quick_tiles", NetworkPresetCodec.encodeQuickTiles(settings.homeQuickTiles))
                    put("manual_overrides", JSONArray(settings.manualOverrides.map { it.name }))
                },
            )
        }
        return root.toString(2)
    }

    /**
     * Official-compatible export format: portable tokens + core FPTN parameters
     * (`sni_host`, `censorship_strategy`, `connection_strategy`, `bypass_methods`) that can be
     * transferred cleanly between official FptnClient installations and this client.
     */
    fun encodeOfficialCompatible(profiles: List<TokenProfile>, settings: VpnSettings): String {
        val root = JSONObject().apply {
            put("format", "fptn_official_compatible")
            put("version", 1)
            put("sni_host", settings.customSni)
            put("censorship_strategy", settings.effectiveCensorshipStrategyName())
            put("connection_strategy", settings.slidingTunnelMode.nativeStrategyName)
            put("reconnect_on_failure", settings.reconnectOnFailure)
            put("reconnect_on_network_change", settings.reconnectOnNetworkChange)
            put(
                "tokens",
                JSONArray().apply {
                    profiles.forEach { profile ->
                        put(
                            JSONObject().apply {
                                put("name", profile.name)
                                put("service_name", profile.serviceName)
                                put("token", profile.rawToken)
                            },
                        )
                    }
                },
            )
        }
        return root.toString(2)
    }

    /**
     * Universal auto-detecting decoder:
     * 1. Full client backup JSON (v1..v6).
     * 2. Official-compatible JSON (`tokens` array + `sni_host` / `censorship_strategy`).
     * 3. Plain text containing one or more `fptn:` / `fptnb:` tokens.
     */
    fun decode(rawText: String): Result<BackupPayload> = runCatching {
        val trimmed = rawText.trim()
        require(trimmed.isNotEmpty()) { "Файл или текст резервной копии пуст." }

        // Case 3: Plain text containing raw fptn:/fptnb: token(s)
        if (!trimmed.startsWith("{")) {
            val tokens = extractRawTokensFromText(trimmed)
            require(tokens.isNotEmpty()) {
                "Не найден JSON бэкапа или токены fptn:/fptnb:."
            }
            val entries = tokens.mapIndexed { idx, tok ->
                BackupProfileEntry(
                    name = if (tokens.size == 1) "FPTN Импорт" else "FPTN Импорт #${idx + 1}",
                    rawToken = tok,
                    enabled = true,
                )
            }
            return@runCatching BackupPayload(
                profiles = entries,
                settings = VpnSettings(),
                detectedFormat = BackupFormat.RAW_TOKENS,
            )
        }

        val root = JSONObject(trimmed)

        // Case 2: Official-compatible JSON format
        if (root.optString("format") == "fptn_official_compatible" ||
            (root.has("tokens") && !root.has("profiles"))
        ) {
            val tokensArr = root.optJSONArray("tokens") ?: JSONArray()
            val profiles = buildList {
                for (i in 0 until tokensArr.length()) {
                    val item = tokensArr.opt(i)
                    when (item) {
                        is JSONObject -> {
                            val tok = item.optString("token", item.optString("raw_token", "")).trim()
                            if (tok.isNotEmpty()) {
                                add(
                                    BackupProfileEntry(
                                        name = item.optString("name", item.optString("service_name", "FPTN Profile")),
                                        rawToken = tok,
                                        enabled = item.optBoolean("enabled", true),
                                    ),
                                )
                            }
                        }
                        is String -> {
                            val tok = item.trim()
                            if (tok.startsWith("fptn:", ignoreCase = true) || tok.startsWith("fptnb:", ignoreCase = true)) {
                                add(BackupProfileEntry(name = "FPTN #${i + 1}", rawToken = tok, enabled = true))
                            }
                        }
                    }
                }
            }
            val sniHost = root.optString("sni_host", VpnSettings.DEFAULT_SNI_DOMAIN)
                .ifBlank { VpnSettings.DEFAULT_SNI_DOMAIN }
            val strategy = root.optString("censorship_strategy", "SNI")
            val bypass = if (strategy.equals("OBFUSCATION", ignoreCase = true)) {
                com.example.domain.model.BypassMethod.TLS_OBFUSCATION
            } else {
                com.example.domain.model.BypassMethod.SNI_REALITY
            }
            val spoofing = SniSpoofingMode.entries.firstOrNull {
                it.nativeStrategyName().equals(strategy, ignoreCase = true)
            } ?: SniSpoofingMode.SNI
            val connStrategy = root.optString("connection_strategy", "rolling-tunnel")
            val sliding = SettingsDataStore.parseSlidingTunnelCompat(connStrategy)

            val settings = VpnSettings(
                customSni = sniHost,
                bypassMethod = bypass,
                sniSpoofingMode = spoofing,
                slidingTunnelMode = sliding,
                reconnectOnFailure = root.optBoolean("reconnect_on_failure", true),
                reconnectOnNetworkChange = root.optBoolean("reconnect_on_network_change", true),
            )
            return@runCatching BackupPayload(
                profiles = profiles,
                settings = settings,
                detectedFormat = BackupFormat.OFFICIAL_COMPATIBLE,
            )
        }

        // Case 1: Full client backup (v1..v6)
        val profilesArr = root.optJSONArray("profiles") ?: JSONArray()
        val profiles = buildList {
            for (i in 0 until profilesArr.length()) {
                val obj = profilesArr.optJSONObject(i) ?: continue
                val rawToken = obj.optString("raw_token", "").trim()
                if (rawToken.isEmpty()) continue
                add(
                    BackupProfileEntry(
                        name = obj.optString("name", "FPTN Profile"),
                        rawToken = rawToken,
                        enabled = obj.optBoolean("enabled", true),
                    ),
                )
            }
        }

        val s = root.optJSONObject("settings") ?: JSONObject()
        val packages = jsonArrayToStringSet(s.optJSONArray("selected_packages"))
        val customDirectDomains = jsonArrayToStringSet(s.optJSONArray("custom_direct_domains"))
        val customVpnDomains = jsonArrayToStringSet(s.optJSONArray("custom_vpn_domains"))

        val savedDns = DnsEndpointParser.decodeEntries(
            s.optString("saved_dns_entries_json", "").ifBlank { null },
        ) ?: VpnSettings.DEFAULT_DNS_ENTRIES

        val networkPresets = NetworkPresetCodec.decode(
            s.optString("network_presets_json", "").ifBlank { null },
        )?.takeIf { it.isNotEmpty() } ?: VpnSettings.DEFAULT_NETWORK_PRESETS

        val activePresetId = s.optString("active_preset_id", "").ifBlank { null }
        val homeQuickTiles = if (s.has("home_quick_tiles")) {
            NetworkPresetCodec.decodeQuickTiles(s.optString("home_quick_tiles"))
                ?: VpnSettings.DEFAULT_HOME_QUICK_TILES
        } else {
            VpnSettings.DEFAULT_HOME_QUICK_TILES
        }

        val legacyEnableCustomDns = s.optBoolean("enable_custom_dns_url", false)
        val legacyBlockAds = s.optBoolean("block_ads_dns", false)
        val dnsMode = DnsMode.entries.firstOrNull { it.name == s.optString("dns_mode") }
            ?: when {
                legacyEnableCustomDns -> DnsMode.SMART_DNS_DOH
                legacyBlockAds -> DnsMode.ADBLOCK_DNS
                else -> DnsMode.AUTO_VPN
            }

        val bypassRegion = BypassRegion.entries.firstOrNull { it.name == s.optString("bypass_region") }
            ?: BypassRegion.RUSSIA

        val settings = VpnSettings(
            autoSelectBestServer = s.optBoolean("auto_select_best_server", true),
            serverSortOrder = ServerSortOrder.entries.firstOrNull {
                it.name == s.optString("server_sort_order")
            } ?: ServerSortOrder.ORIGINAL,
            bypassMethod = SettingsDataStore.parseBypassMethodCompat(s.optString("bypass_method")),
            sniSpoofingMode = SniSpoofingMode.entries.firstOrNull {
                it.name == s.optString("sni_spoofing_mode")
            } ?: SniSpoofingMode.SNI,
            customSni = s.optString("custom_sni", VpnSettings.DEFAULT_SNI_DOMAIN),
            slidingTunnelMode = SettingsDataStore.parseSlidingTunnelCompat(s.optString("sliding_tunnel_mode")),
            enableObfuscation = s.optBoolean("enable_obfuscation", true),
            smartRfRouting = true,
            bypassRegion = BypassRegion.sanitize(bypassRegion),
            customDirectDomains = customDirectDomains,
            customVpnDomains = customVpnDomains,
            unlockProtectedRuWithRoot = false,
            forceTelegramViaVpn = true,
            useRootMode = s.optBoolean("use_root_mode", false),
            allowSecondaryVpnChaining = s.optBoolean("allow_secondary_vpn_chaining", true),
            rootTunInterfaceName = com.example.domain.root.SafeRootRulePlanner.sanitizeTunName(
                s.optString("root_tun_interface_name", "fptn0"),
            ),
            rootAdaptiveBoostEnabled = s.optBoolean("root_adaptive_boost_enabled", false),
            splitTunnelMode = SplitTunnelMode.entries.firstOrNull { it.name == s.optString("split_tunnel_mode") }
                ?: SplitTunnelMode.ALL_APPS,
            selectedPackages = packages,
            dnsMode = dnsMode,
            dnsPrimary = s.optString("dns_primary", "1.1.1.1"),
            dnsSecondary = s.optString("dns_secondary", "8.8.8.8"),
            customDnsUrl = s.optString("custom_dns_url", "").ifBlank { VpnSettings.DEFAULT_SMART_DNS_URL },
            enableCustomDnsUrl = dnsMode == DnsMode.SMART_DNS_DOH,
            savedDnsEntries = savedDns,
            networkPresets = networkPresets,
            activePresetId = activePresetId,
            autoSwitchPresetsByNetwork = s.optBoolean("auto_switch_presets", false),
            blockAdsDns = dnsMode == DnsMode.ADBLOCK_DNS,
            allowIpv6 = s.optBoolean("allow_ipv6", false),
            tunMtu = s.optInt("tun_mtu", 1400).coerceIn(1280, 1500),
            autoPingIntervalSec = s.optInt("auto_ping_interval_sec", 60),
            showTrafficInNotification = s.optBoolean("show_traffic_in_notification", true),
            enableHaptics = s.optBoolean("enable_haptics", true),
            enableWaterWaveEffect = s.optBoolean("enable_water_wave_effect", true),
            waveSpeed = s.optDouble("wave_speed", 0.72).toFloat().coerceIn(0.35f, 1.8f),
            waveStrength = s.optDouble("wave_strength", 0.85).toFloat().coerceIn(0.25f, 1.8f),
            waveSmoothness = s.optDouble("wave_smoothness", 0.75).toFloat().coerceIn(0.25f, 1.6f),
            waveElasticity = s.optDouble("wave_elasticity", 0.65).toFloat().coerceIn(0.15f, 1.5f),
            waveTintStrength = s.optDouble("wave_tint_strength", 0.30).toFloat().coerceIn(0.05f, 1.0f),
            waveDispersion = s.optDouble("wave_dispersion", 0.85).toFloat().coerceIn(0.0f, 1.8f),
            waveRingWidth = s.optDouble("wave_ring_width", 1.0).toFloat().coerceIn(0.55f, 1.65f),
            enableFlagBackdrop = s.optBoolean("enable_flag_backdrop", true),
            enableFlagMatrixTransition = s.optBoolean("enable_flag_matrix_transition", true),
            flagMatrixDirection = FlagMatrixDirection.entries.firstOrNull {
                it.name == s.optString("flag_matrix_direction")
            } ?: FlagMatrixDirection.RIGHT_TO_LEFT,
            flagTiltDegrees = SettingsDataStore.normalizeAngle360(s.optDouble("flag_tilt_degrees", 0.0).toFloat()),
            flagMatrixAngleDegrees = SettingsDataStore.normalizeAngle360(
                if (s.has("flag_matrix_angle_degrees")) {
                    s.optDouble("flag_matrix_angle_degrees", 270.0).toFloat()
                } else {
                    (FlagMatrixDirection.entries.firstOrNull {
                        it.name == s.optString("flag_matrix_direction")
                    } ?: FlagMatrixDirection.RIGHT_TO_LEFT).toClockDegrees()
                },
            ),
            flagMatrixRandomAngle = if (s.has("flag_matrix_random_angle")) {
                s.optBoolean("flag_matrix_random_angle", false)
            } else {
                s.optString("flag_matrix_direction") == FlagMatrixDirection.RANDOM_EACH_TIME.name
            },
            flagAngleSnapToTicks = s.optBoolean("flag_angle_snap_to_ticks", true),
            flagMatrixSpeed = s.optDouble("flag_matrix_speed", 1.0).toFloat().coerceIn(0.40f, 2.20f),
            flagMatrixZoom = s.optDouble("flag_matrix_zoom", 1.0).toFloat().coerceIn(0.65f, 1.45f),
            flagMatrixHighlightServers = s.optBoolean("flag_matrix_highlight_servers", true),
            flagBackdropScale = s.optDouble("flag_backdrop_scale", 1.15).toFloat().coerceIn(0.85f, 1.55f),
            flagBackdropDim = s.optDouble("flag_backdrop_dim", 0.42).toFloat().coerceIn(0.10f, 0.78f),
            flagShowCoatOfArms = s.optBoolean("flag_show_coat_of_arms", true),
            flagPhysicsIntensity = s.optDouble("flag_physics_intensity", 1.0).toFloat().coerceIn(0.0f, 1.6f),
            backgroundGradientStyle = BackgroundGradientStyle.entries.firstOrNull {
                it.name == s.optString("background_gradient_style")
            } ?: BackgroundGradientStyle.GLASS_AURORA,
            enableSolarAtmosphere = s.optBoolean("enable_solar_atmosphere", true),
            solarAtmosphereIntensity = s.optDouble("solar_atmosphere_intensity", 1.15).toFloat().coerceIn(0.25f, 2.0f),
            weatherEffectMode = WeatherEffectMode.entries.firstOrNull {
                it.name == s.optString("weather_effect_mode")
            } ?: WeatherEffectMode.OFF,
            enableWeatherHaptics = s.optBoolean("enable_weather_haptics", true),
            weatherIntensity = s.optDouble("weather_intensity", 0.75).toFloat().coerceIn(0.25f, 1.5f),
            weatherWindStrength = s.optDouble("weather_wind_strength", 0.55).toFloat().coerceIn(0.0f, 1.5f),
            weatherFogDepth = s.optDouble("weather_fog_depth", 0.80).toFloat().coerceIn(0.25f, 1.5f),
            dynamicColor = s.optBoolean("dynamic_color", true),
            themeMode = ThemeMode.entries.firstOrNull { it.name == s.optString("theme_mode") }
                ?: ThemeMode.SYSTEM,
            appLanguage = AppLanguage.entries.firstOrNull { it.name == s.optString("app_language") }
                ?: AppLanguage.SYSTEM,
            reconnectOnFailure = s.optBoolean("reconnect_on_failure", true),
            reconnectOnNetworkChange = s.optBoolean("reconnect_on_network_change", true),
            autoRotateSniOnFail = s.optBoolean("auto_rotate_sni_on_fail", true),
            autoFailoverServer = s.optBoolean("auto_failover_server", true),
            maxReconnectAttempts = s.optInt("max_reconnect_attempts", 15).coerceIn(3, 50),
            reconnectDelaySec = s.optInt("reconnect_delay_sec", 2).coerceIn(1, 10),
            handshakeTimeoutSec = s.optInt("handshake_timeout_sec", 8).coerceIn(4, 20),
            wsOpenTimeoutSec = s.optInt("ws_open_timeout_sec", 15).coerceIn(5, 30),
            keepaliveIntervalSec = s.optInt("keepalive_interval_sec", 18).coerceIn(0, 60),
            networkSwitchDebounceSec = s.optInt("network_switch_debounce_sec", 3).coerceIn(1, 10),
            enableStallWatchdog = s.optBoolean("enable_stall_watchdog", true),
            watchdogSensitivity = WatchdogSensitivity.entries.firstOrNull {
                it.name == s.optString("watchdog_sensitivity")
            } ?: WatchdogSensitivity.GENTLE,
            watchdogProbeSec = s.optInt("watchdog_probe_sec", 10).coerceIn(2, 25),
            watchdogStallSec = s.optInt("watchdog_stall_sec", 24).coerceIn(4, 55),
            watchdogCooldownSec = s.optInt("watchdog_cooldown_sec", 35).coerceIn(10, 90),
            homeQuickTiles = homeQuickTiles,
            manualOverrides = jsonArrayToStringSet(s.optJSONArray("manual_overrides"))
                .mapNotNull { raw -> ManualOverrideKey.entries.firstOrNull { it.name == raw } }
                .toSet(),
        )

        BackupPayload(
            profiles = profiles,
            settings = settings,
            detectedFormat = BackupFormat.FULL_CLIENT,
        )
    }

    private fun jsonArrayToStringSet(arr: JSONArray?): Set<String> = buildSet {
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val item = arr.optString(i, "").trim()
                if (item.isNotEmpty()) add(item)
            }
        }
    }

    private fun extractRawTokensFromText(text: String): List<String> {
        val regex = Regex("""fptnb?:[A-Za-z0-9+/=_\-]+""", RegexOption.IGNORE_CASE)
        return regex.findAll(text).map { it.value.trim() }.distinct().toList()
    }
}
