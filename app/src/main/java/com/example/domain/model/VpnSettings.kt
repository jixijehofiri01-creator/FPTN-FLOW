package com.example.domain.model

import com.example.domain.vpn.WatchdogSensitivity
import kotlin.random.Random

/**
 * Persisted application and tunnel configuration backed by Preferences DataStore.
 */
data class VpnSettings(
    val selectedServerId: Long? = null,
    val autoSelectBestServer: Boolean = true,
    val serverSortOrder: ServerSortOrder = ServerSortOrder.ORIGINAL,
    val bypassMethod: BypassMethod = BypassMethod.SNI_REALITY,
    val sniSpoofingMode: SniSpoofingMode = SniSpoofingMode.SNI,
    val customSni: String = DEFAULT_SNI_DOMAIN,
    val slidingTunnelMode: SlidingTunnelMode = SlidingTunnelMode.ROLLING_TUNNEL,
    val enableObfuscation: Boolean = true,
    val smartRfRouting: Boolean = true,
    val bypassRegion: BypassRegion = BypassRegion.RUSSIA,
    val customDirectDomains: Set<String> = emptySet(),
    val customVpnDomains: Set<String> = emptySet(),
    val unlockProtectedRuWithRoot: Boolean = false,
    val forceTelegramViaVpn: Boolean = true,
    val useRootMode: Boolean = false,
    val allowSecondaryVpnChaining: Boolean = true,
    val rootTunInterfaceName: String = "fptn0",
    val rootAdaptiveBoostEnabled: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val selectedPackages: Set<String> = emptySet(),
    val dnsMode: DnsMode = DnsMode.AUTO_VPN,
    val dnsPrimary: String = "1.1.1.1",
    val dnsSecondary: String = "8.8.8.8",
    val customDnsUrl: String = DEFAULT_SMART_DNS_URL,
    val enableCustomDnsUrl: Boolean = false,
    val savedDnsEntries: List<SavedDnsEntry> = DEFAULT_DNS_ENTRIES,
    val networkPresets: List<NetworkPreset> = DEFAULT_NETWORK_PRESETS,
    val activePresetId: String? = null,
    val autoSwitchPresetsByNetwork: Boolean = false,
    val blockAdsDns: Boolean = false,
    val allowIpv6: Boolean = false,
    val tunMtu: Int = 1400,
    val autoPingIntervalSec: Int = 60,
    val showTrafficInNotification: Boolean = true,
    val enableHaptics: Boolean = true,
    val enableWaterWaveEffect: Boolean = true,
    val waveSpeed: Float = 0.72f,
    val waveStrength: Float = 0.85f,
    val waveSmoothness: Float = 0.75f,
    val waveElasticity: Float = 0.65f,
    val waveTintStrength: Float = 0.30f,
    val waveDispersion: Float = 0.85f,
    val waveRingWidth: Float = 1.0f,
    val enableFlagBackdrop: Boolean = true,
    val enableFlagMatrixTransition: Boolean = true,
    val flagMatrixDirection: FlagMatrixDirection = FlagMatrixDirection.RIGHT_TO_LEFT,
    val flagTiltDegrees: Float = 0f,
    val flagMatrixAngleDegrees: Float = 270f,
    val flagMatrixRandomAngle: Boolean = false,
    val flagAngleSnapToTicks: Boolean = true,
    val flagMatrixSpeed: Float = 1.0f,
    val flagMatrixZoom: Float = 1.0f,
    val flagMatrixHighlightServers: Boolean = true,
    val flagBackdropScale: Float = 1.15f,
    val flagBackdropDim: Float = 0.42f,
    val flagShowCoatOfArms: Boolean = true,
    val flagPhysicsIntensity: Float = 1.0f,
    val backgroundGradientStyle: BackgroundGradientStyle = BackgroundGradientStyle.GLASS_AURORA,
    val enableSolarAtmosphere: Boolean = true,
    val solarAtmosphereIntensity: Float = 1.15f,
    val weatherEffectMode: WeatherEffectMode = WeatherEffectMode.OFF,
    val enableWeatherHaptics: Boolean = true,
    val weatherIntensity: Float = 0.75f,
    val weatherWindStrength: Float = 0.55f,
    val weatherFogDepth: Float = 0.80f,
    val dynamicColor: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val appLanguage: AppLanguage = AppLanguage.SYSTEM,
    val reconnectOnFailure: Boolean = true,
    val reconnectOnNetworkChange: Boolean = true,
    val autoRotateSniOnFail: Boolean = true,
    val autoFailoverServer: Boolean = true,
    val maxReconnectAttempts: Int = 15,
    val reconnectDelaySec: Int = 2,
    val handshakeTimeoutSec: Int = 8,
    val wsOpenTimeoutSec: Int = 15,
    val keepaliveIntervalSec: Int = 18,
    val networkSwitchDebounceSec: Int = 3,
    val enableStallWatchdog: Boolean = true,
    val watchdogSensitivity: WatchdogSensitivity = WatchdogSensitivity.GENTLE,
    val watchdogProbeSec: Int = 10,
    val watchdogStallSec: Int = 24,
    val watchdogCooldownSec: Int = 35,
    val homeQuickTiles: List<HomeQuickTileId> = DEFAULT_HOME_QUICK_TILES,
    val manualOverrides: Set<ManualOverrideKey> = emptySet(),
) {
    fun waterWaveConfig(): WaterWaveConfig = WaterWaveConfig(
        speed = waveSpeed.coerceIn(0.35f, 1.8f),
        strength = waveStrength.coerceIn(0.25f, 1.8f),
        smoothness = waveSmoothness.coerceIn(0.25f, 1.6f),
        elasticity = waveElasticity.coerceIn(0.15f, 1.5f),
        tintStrength = waveTintStrength.coerceIn(0.05f, 1.0f),
        dispersion = waveDispersion.coerceIn(0.0f, 1.8f),
        ringWidth = waveRingWidth.coerceIn(0.55f, 1.65f),
    )

    /** Produces the exact censorship_strategy_name expected by libfptn_native_lib.so. */
    fun effectiveCensorshipStrategyName(): String = when (bypassMethod) {
        BypassMethod.TLS_OBFUSCATION -> "OBFUSCATION"
        BypassMethod.SNI_REALITY -> sniSpoofingMode.nativeStrategyName()
    }

    /** Keeps legacy boolean DNS flags synchronized with the single source-of-truth [DnsMode]. */
    fun withDnsMode(mode: DnsMode, smartDnsUrl: String? = null): VpnSettings {
        val nextUrl = smartDnsUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: customDnsUrl.ifBlank { DEFAULT_SMART_DNS_URL }
        return copy(
            dnsMode = mode,
            customDnsUrl = nextUrl,
            enableCustomDnsUrl = mode == DnsMode.SMART_DNS_DOH,
            blockAdsDns = mode == DnsMode.ADBLOCK_DNS,
        )
    }

    fun applyPreset(
        preset: NetworkPreset,
        respectManualOverrides: Boolean = false,
    ): VpnSettings {
        val forceAll = preset.forceOverrideManualLocks
        val lockDns = !forceAll && ManualOverrideKey.DNS in manualOverrides
        val lockBypass = !forceAll && respectManualOverrides && ManualOverrideKey.BYPASS_METHOD in manualOverrides
        val lockSni = !forceAll && respectManualOverrides && ManualOverrideKey.CUSTOM_SNI in manualOverrides
        val lockSliding = !forceAll && respectManualOverrides && ManualOverrideKey.SLIDING_TUNNEL in manualOverrides
        val lockMtu = !forceAll && respectManualOverrides && ManualOverrideKey.TUN_MTU in manualOverrides
        val lockWatchdog = !forceAll && respectManualOverrides && ManualOverrideKey.WATCHDOG in manualOverrides
        val lockReconnect = !forceAll && respectManualOverrides && ManualOverrideKey.RECONNECT in manualOverrides
        val lockRouting = !forceAll && respectManualOverrides && ManualOverrideKey.ROUTING_REGION in manualOverrides
        val lockServer = !forceAll && respectManualOverrides && ManualOverrideKey.SERVER in manualOverrides

        // Loose DNS coupling: a null preset DNS mode always preserves the user's current DNS.
        val presetDnsMode = preset.effectiveDnsModeOverride()
        val applyPresetDns = presetDnsMode != null && when {
            forceAll -> true
            respectManualOverrides -> !lockDns
            else -> !lockDns || presetDnsMode != DnsMode.AUTO_VPN
        }

        val nextDnsMode = if (applyPresetDns && presetDnsMode != null) presetDnsMode else dnsMode
        val nextDnsUrl = if (applyPresetDns && preset.customDnsUrl.isNotBlank()) {
            preset.customDnsUrl.trim()
        } else {
            customDnsUrl.ifBlank { DEFAULT_SMART_DNS_URL }
        }
        val nextDnsPrimary = if (applyPresetDns && preset.dnsPrimaryOverride.isNotBlank()) {
            preset.dnsPrimaryOverride.trim()
        } else {
            dnsPrimary
        }
        val nextDnsSecondary = if (applyPresetDns && preset.dnsSecondaryOverride.isNotBlank()) {
            preset.dnsSecondaryOverride.trim()
        } else {
            dnsSecondary
        }

        val applyBypass = preset.overrideBypass && !lockBypass
        val applySni = preset.overrideBypass && !lockSni
        val applySliding = preset.overrideSlidingTunnel && !lockSliding
        val applyWatchdog = preset.overrideWatchdog && !lockWatchdog
        val applyReconnect = preset.overrideReconnect && !lockReconnect
        val applyMtu = preset.overrideMtu && !lockMtu

        val nextAutoSelectServer = when {
            lockServer || preset.preferredServerId == null -> autoSelectBestServer
            preset.preferredServerId < 0L -> true
            else -> false
        }
        val nextSelectedServerId = when {
            lockServer || preset.preferredServerId == null -> selectedServerId
            preset.preferredServerId < 0L -> null
            else -> preset.preferredServerId
        }
        val nextBypassRegion = when {
            lockRouting || preset.bypassRegionOverride == null -> BypassRegion.sanitize(bypassRegion)
            else -> BypassRegion.sanitize(preset.bypassRegionOverride)
        }
        // RU protection is permanently active even on rooted devices to protect FPTN servers
        val nextSmartRf = true

        return copy(
            activePresetId = preset.id,
            autoSelectBestServer = nextAutoSelectServer,
            selectedServerId = nextSelectedServerId,
            serverSortOrder = preset.serverSortOrderOverride ?: serverSortOrder,
            bypassMethod = if (applyBypass) preset.bypassMethod else bypassMethod,
            sniSpoofingMode = if (applyBypass) preset.sniSpoofingMode else sniSpoofingMode,
            customSni = if (applySni) preset.customSni.trim().ifBlank { DEFAULT_SNI_DOMAIN } else customSni,
            slidingTunnelMode = if (applySliding) preset.slidingTunnelMode else slidingTunnelMode,
            dnsMode = nextDnsMode,
            enableCustomDnsUrl = nextDnsMode == DnsMode.SMART_DNS_DOH,
            blockAdsDns = nextDnsMode == DnsMode.ADBLOCK_DNS,
            customDnsUrl = nextDnsUrl,
            dnsPrimary = nextDnsPrimary,
            dnsSecondary = nextDnsSecondary,
            enableStallWatchdog = if (applyWatchdog) preset.enableStallWatchdog else enableStallWatchdog,
            watchdogSensitivity = if (applyWatchdog) (preset.watchdogSensitivity ?: watchdogSensitivity) else watchdogSensitivity,
            watchdogProbeSec = if (applyWatchdog) preset.watchdogProbeSec.coerceIn(2, 25) else watchdogProbeSec,
            watchdogStallSec = if (applyWatchdog) preset.watchdogStallSec.coerceIn(4, 55) else watchdogStallSec,
            watchdogCooldownSec = if (applyWatchdog) preset.watchdogCooldownSec.coerceIn(10, 90) else watchdogCooldownSec,
            reconnectOnFailure = if (applyReconnect) preset.reconnectOnFailure else reconnectOnFailure,
            reconnectOnNetworkChange = if (applyReconnect) preset.reconnectOnNetworkChange else reconnectOnNetworkChange,
            maxReconnectAttempts = if (applyReconnect) preset.maxReconnectAttempts.coerceIn(3, 50) else maxReconnectAttempts,
            reconnectDelaySec = if (applyReconnect) preset.reconnectDelaySec.coerceIn(1, 10) else reconnectDelaySec,
            handshakeTimeoutSec = if (applyReconnect) preset.handshakeTimeoutSec.coerceIn(4, 20) else handshakeTimeoutSec,
            wsOpenTimeoutSec = if (applyReconnect) preset.wsOpenTimeoutSec.coerceIn(5, 30) else wsOpenTimeoutSec,
            keepaliveIntervalSec = if (applyReconnect && preset.keepaliveIntervalSecOverride != null) {
                preset.keepaliveIntervalSecOverride.coerceIn(0, 60)
            } else {
                keepaliveIntervalSec
            },
            networkSwitchDebounceSec = if (applyReconnect && preset.networkSwitchDebounceSecOverride != null) {
                preset.networkSwitchDebounceSecOverride.coerceIn(1, 10)
            } else {
                networkSwitchDebounceSec
            },
            autoRotateSniOnFail = preset.autoRotateSniOnFail ?: autoRotateSniOnFail,
            autoFailoverServer = preset.autoFailoverServer ?: autoFailoverServer,
            tunMtu = if (applyMtu) preset.tunMtu.coerceIn(1280, 1500) else tunMtu,
            bypassRegion = nextBypassRegion,
            smartRfRouting = nextSmartRf,
            unlockProtectedRuWithRoot = false,
            splitTunnelMode = preset.splitTunnelModeOverride ?: splitTunnelMode,
            autoPingIntervalSec = preset.autoPingIntervalSecOverride?.coerceIn(0, 600) ?: autoPingIntervalSec,
            allowIpv6 = preset.allowIpv6Override ?: allowIpv6,
            useRootMode = preset.useRootModeOverride ?: useRootMode,
        )
    }

    companion object {
        const val DEFAULT_SNI_DOMAIN = "ads.x5.ru"
        const val DEFAULT_SMART_DNS_URL = "https://magicinfo.art/dns-query"
        const val ADGUARD_DOH_URL = "https://dns.adguard-dns.com/dns-query"
        const val ADGUARD_IPV4 = "94.140.14.14"

        val MTU_PRESETS: List<Int> = listOf(1400, 1360, 1280, 1450, 1500)

        val DEFAULT_HOME_QUICK_TILES: List<HomeQuickTileId> = listOf(
            HomeQuickTileId.PRESET,
            HomeQuickTileId.SLIDING_TUNNEL,
            HomeQuickTileId.BYPASS_METHOD,
            HomeQuickTileId.SMART_DNS,
            HomeQuickTileId.STALL_WATCHDOG,
            HomeQuickTileId.ROOT_STEALTH,
        )

        val DEFAULT_NETWORK_PRESETS: List<NetworkPreset> = listOf(
            NetworkPreset(
                id = "preset_mobile",
                name = "Мобильная сеть (4G/5G)",
                trigger = NetworkPresetTrigger.MOBILE,
                bypassMethod = BypassMethod.TLS_OBFUSCATION,
                sniSpoofingMode = SniSpoofingMode.SNI_REALITY_CHROME_149,
                customSni = DEFAULT_SNI_DOMAIN,
                slidingTunnelMode = SlidingTunnelMode.DUAL_TUNNEL,
                enableCustomDnsUrl = false,
                customDnsUrl = "",
                enableStallWatchdog = true,
                watchdogSensitivity = WatchdogSensitivity.GENTLE,
                watchdogProbeSec = 10,
                watchdogStallSec = 24,
                watchdogCooldownSec = 40,
                reconnectOnFailure = true,
                reconnectOnNetworkChange = true,
                maxReconnectAttempts = 25,
                keepaliveIntervalSecOverride = 15,
                networkSwitchDebounceSecOverride = 4,
                tunMtu = 1360,
                smartRfRouting = true,
                useRootModeOverride = null,
                isBuiltIn = true,
            ),
            NetworkPreset(
                id = "preset_wifi",
                name = "Wi-Fi / Домашняя сеть",
                trigger = NetworkPresetTrigger.WIFI,
                bypassMethod = BypassMethod.SNI_REALITY,
                sniSpoofingMode = SniSpoofingMode.SNI_REALITY_FIREFOX_151,
                customSni = DEFAULT_SNI_DOMAIN,
                slidingTunnelMode = SlidingTunnelMode.ROLLING_TUNNEL,
                enableCustomDnsUrl = false,
                customDnsUrl = "",
                enableStallWatchdog = true,
                watchdogSensitivity = WatchdogSensitivity.BALANCED,
                watchdogProbeSec = 8,
                watchdogStallSec = 18,
                watchdogCooldownSec = 30,
                reconnectOnFailure = true,
                reconnectOnNetworkChange = true,
                maxReconnectAttempts = 15,
                keepaliveIntervalSecOverride = 25,
                networkSwitchDebounceSecOverride = 2,
                tunMtu = 1400,
                smartRfRouting = true,
                useRootModeOverride = null,
                isBuiltIn = true,
            ),
            NetworkPreset(
                id = "preset_tspu_max",
                name = "ТСПУ Максимум",
                trigger = NetworkPresetTrigger.MANUAL,
                bypassMethod = BypassMethod.TLS_OBFUSCATION,
                sniSpoofingMode = SniSpoofingMode.SNI_REALITY_YANDEX_26_4,
                customSni = DEFAULT_SNI_DOMAIN,
                slidingTunnelMode = SlidingTunnelMode.TRIPLE_TUNNEL,
                dnsModeOverride = DnsMode.SMART_DNS_DOH,
                enableCustomDnsUrl = true,
                customDnsUrl = DEFAULT_SMART_DNS_URL,
                enableStallWatchdog = true,
                watchdogSensitivity = WatchdogSensitivity.AGGRESSIVE,
                watchdogProbeSec = 6,
                watchdogStallSec = 14,
                watchdogCooldownSec = 25,
                reconnectOnFailure = true,
                reconnectOnNetworkChange = true,
                maxReconnectAttempts = 35,
                keepaliveIntervalSecOverride = 15,
                networkSwitchDebounceSecOverride = 3,
                tunMtu = 1360,
                smartRfRouting = true,
                useRootModeOverride = null,
                isBuiltIn = true,
            ),
            NetworkPreset(
                id = "preset_low_latency",
                name = "Игры и звонки",
                trigger = NetworkPresetTrigger.MANUAL,
                bypassMethod = BypassMethod.SNI_REALITY,
                sniSpoofingMode = SniSpoofingMode.SNI_REALITY_SAFARI_26_5,
                customSni = DEFAULT_SNI_DOMAIN,
                slidingTunnelMode = SlidingTunnelMode.ROLLING_TUNNEL,
                enableCustomDnsUrl = false,
                customDnsUrl = "",
                enableStallWatchdog = true,
                watchdogSensitivity = WatchdogSensitivity.GENTLE,
                watchdogProbeSec = 10,
                watchdogStallSec = 24,
                watchdogCooldownSec = 40,
                reconnectOnFailure = true,
                reconnectOnNetworkChange = true,
                maxReconnectAttempts = 10,
                keepaliveIntervalSecOverride = 20,
                networkSwitchDebounceSecOverride = 3,
                tunMtu = 1420,
                smartRfRouting = true,
                useRootModeOverride = null,
                isBuiltIn = true,
            ),
        )

        /**
         * Generates default network presets with per-installation randomized SNI domains
         * and Reality fingerprints to avoid shared DPI fingerprint correlation across users.
         */
        fun generateRandomizedDefaultPresets(
            sniPool: List<String>,
            seed: Long,
        ): List<NetworkPreset> {
            val cleanPool = sniPool.map { it.trim().lowercase() }
                .filter { it.contains('.') && !it.contains(' ') }
                .distinct()
            val rng = Random(seed)
            val shuffledSni = if (cleanPool.size >= 4) {
                cleanPool.shuffled(rng)
            } else {
                listOf("ads.x5.ru", "ya.ru", "vk.com", "ozon.ru").shuffled(rng)
            }
            val realityProfiles = listOf(
                SniSpoofingMode.SNI_REALITY_CHROME_149,
                SniSpoofingMode.SNI_REALITY_CHROME_146,
                SniSpoofingMode.SNI_REALITY_FIREFOX_151,
                SniSpoofingMode.SNI_REALITY_YANDEX_26_4,
                SniSpoofingMode.SNI_REALITY_SAFARI_26_5,
            ).shuffled(rng)

            return DEFAULT_NETWORK_PRESETS.mapIndexed { index, preset ->
                preset.copy(
                    customSni = shuffledSni[index % shuffledSni.size],
                    sniSpoofingMode = realityProfiles[index % realityProfiles.size],
                )
            }
        }

        /** Built-in Smart DNS and DoH catalog grouped by region and purpose. */
        val DEFAULT_DNS_ENTRIES: List<SavedDnsEntry> = listOf(
            SavedDnsEntry(
                id = "magic_dns_intl",
                title = "MagicDNS",
                urlOrHost = "https://magicinfo.art/dns-query",
                subtitle = "Стабильный резолвер для ИИ-сервисов",
                fallbackIpv4 = "217.144.190.216",
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "bezmezhau_dns",
                title = "Bezmezhau DNS",
                urlOrHost = "https://bezmezhau.com/dns-query",
                subtitle = "Gemini, ChatGPT и Copilot",
                fallbackIpv4 = "143.20.64.55",
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "astracat_commonnet",
                title = "Astracat (CommonNet)",
                urlOrHost = "https://commonnet.work/dns-query",
                subtitle = "ODoH и фильтрация рекламы",
                fallbackIpv4 = "108.165.164.224",
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "comss_smart",
                title = "Comss.one DNS",
                urlOrHost = "https://dns.comss.one/dns-query",
                subtitle = "ИИ-сервисы и фильтрация рекламы",
                fallbackIpv4 = "94.250.253.55",
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "wlrus_dns",
                title = "WLRus DNS",
                urlOrHost = "https://wlrus.cfd/dns-query",
                subtitle = "Резолвер в зоне .cfd для ИИ-сервисов",
                fallbackIpv4 = null,
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "malw_smart",
                title = "Malw.link DNS",
                urlOrHost = "https://dns.malw.link/dns-query",
                subtitle = "Доступ к ИИ и зарубежным сервисам",
                fallbackIpv4 = "179.254.166.171",
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "mafioznik_dns",
                title = "Mafioznik DNS",
                urlOrHost = "https://dns.mafioznik.xyz/dns-query",
                subtitle = "Альтернативный Smart DNS в зоне .xyz",
                fallbackIpv4 = null,
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "zapret_mirror_dns",
                title = "Zapret DNS (зеркало)",
                urlOrHost = "https://fuckingspeedtest.com/dns-query",
                subtitle = "Международное зеркало для ИИ-платформ",
                fallbackIpv4 = "83.217.211.149",
                isBuiltIn = true,
                category = DnsCategory.INTERNATIONAL_AI,
            ),
            SavedDnsEntry(
                id = "dns_ai_ru",
                title = "DNS-AI (.ru)",
                urlOrHost = "https://dns.dns-ai.ru/dns-query",
                subtitle = "Низкий пинг в зоне .ru для ИИ",
                fallbackIpv4 = null,
                isBuiltIn = true,
                category = DnsCategory.RU_ZONE_AI,
            ),
            SavedDnsEntry(
                id = "geohide_eu",
                title = "GeoHide DNS (EU)",
                urlOrHost = "https://eu.geohide.ru/dns-query",
                subtitle = "Европейский узел GeoHide",
                fallbackIpv4 = "143.20.64.192",
                isBuiltIn = true,
                category = DnsCategory.RU_ZONE_AI,
            ),
            SavedDnsEntry(
                id = "geohide_us",
                title = "GeoHide DNS (US)",
                urlOrHost = "https://us.geohide.ru/dns-query",
                subtitle = "Американский узел GeoHide",
                fallbackIpv4 = "143.20.64.192",
                isBuiltIn = true,
                category = DnsCategory.RU_ZONE_AI,
            ),
            SavedDnsEntry(
                id = "dagami_ru",
                title = "Dagami DNS",
                urlOrHost = "https://dns.dagami.ru/dns-query",
                subtitle = "Резолвер в зоне .ru для AI API",
                fallbackIpv4 = "48.102.213.179",
                isBuiltIn = true,
                category = DnsCategory.RU_ZONE_AI,
            ),
            SavedDnsEntry(
                id = "magic_dns_ru",
                title = "MagicDNS (RU)",
                urlOrHost = "https://dns.magicdns.ru/dns-query",
                subtitle = "Российская ветка MagicDNS",
                fallbackIpv4 = "109.120.177.170",
                isBuiltIn = true,
                category = DnsCategory.RU_ZONE_AI,
            ),
            SavedDnsEntry(
                id = "astracat_ru",
                title = "Astracat (RU)",
                urlOrHost = "https://dns.astracat.ru/dns-query",
                subtitle = "Российское зеркало Astracat",
                fallbackIpv4 = "135.106.217.200",
                isBuiltIn = true,
                category = DnsCategory.RU_ZONE_AI,
            ),
            SavedDnsEntry(
                id = "cloudflare_doh",
                title = "Cloudflare DoH (1.1.1.1)",
                urlOrHost = "https://cloudflare-dns.com/dns-query",
                subtitle = "Глобальный DoH-резолвер",
                fallbackIpv4 = "1.1.1.1",
                isBuiltIn = true,
                category = DnsCategory.PUBLIC_RESERVE,
            ),
            SavedDnsEntry(
                id = "google_doh",
                title = "Google Public DoH",
                urlOrHost = "https://dns.google/dns-query",
                subtitle = "Глобальный DoH-резолвер Google",
                fallbackIpv4 = "8.8.8.8",
                isBuiltIn = true,
                category = DnsCategory.PUBLIC_RESERVE,
            ),
            SavedDnsEntry(
                id = "adguard_doh",
                title = "AdGuard DoH",
                urlOrHost = "https://dns.adguard-dns.com/dns-query",
                subtitle = "Фильтрация рекламы и трекеров",
                fallbackIpv4 = "94.140.14.14",
                isBuiltIn = true,
                category = DnsCategory.PUBLIC_RESERVE,
            ),
        )
    }
}
