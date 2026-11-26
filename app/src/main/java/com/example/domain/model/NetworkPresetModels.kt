package com.example.domain.model

import com.example.domain.vpn.WatchdogSensitivity

enum class NetworkPresetTrigger(val title: String) {
    MANUAL("Вручную"),
    MOBILE("Мобильная сеть"),
    WIFI("Wi-Fi"),
}

/** User-locked parameters preserved when auto-presets or network switches apply. */
enum class ManualOverrideKey(
    val title: String,
    val autoDescription: String,
    val category: String,
) {
    SERVER(
        title = "Сервер подключения",
        autoDescription = "Автовыбор сервера по минимальному пингу",
        category = "Подключение",
    ),
    PRESET(
        title = "Пресет сети",
        autoDescription = "Автоподбор пресета под Wi-Fi или 4G/5G",
        category = "Подключение",
    ),
    DNS(
        title = "DNS и Smart DNS",
        autoDescription = "Стандартный DNS сервера FPTN",
        category = "DNS и сеть",
    ),
    BYPASS_METHOD(
        title = "Метод обхода и TLS",
        autoDescription = "SNI / Reality из активного пресета",
        category = "Обход блокировок",
    ),
    CUSTOM_SNI(
        title = "SNI-домен",
        autoDescription = "Автоматический SNI пресета или региона",
        category = "Обход блокировок",
    ),
    SLIDING_TUNNEL(
        title = "Скользящий туннель",
        autoDescription = "Режим туннеля из активного пресета",
        category = "Обход блокировок",
    ),
    TUN_MTU(
        title = "MTU туннеля",
        autoDescription = "1400 байт (1360 на 4G/5G)",
        category = "DNS и сеть",
    ),
    WATCHDOG(
        title = "Контроль зависаний",
        autoDescription = "Мягкий профиль 10с / 24с с UDP/ICMP пробами",
        category = "Стабильность",
    ),
    RECONNECT(
        title = "Переподключение",
        autoDescription = "Автовосстановление при обрыве и смене сети",
        category = "Стабильность",
    ),
    ROUTING_REGION(
        title = "Регион обхода",
        autoDescription = "Россия (.ru / .рф / .su) напрямую, Telegram в VPN",
        category = "Маршрутизация",
    ),
}

/** Modular quick-action tiles displayed on the Home screen. */
enum class HomeQuickTileId(
    val title: String,
    val description: String,
    val requiresRoot: Boolean = false,
    val liveEditableWhenConnected: Boolean = true,
) {
    PRESET("Пресет сети", "Профиль под Wi-Fi, 4G/5G или ТСПУ"),
    SLIDING_TUNNEL("Скользящий туннель", "Одиночный / Двойной / Тройной"),
    BYPASS_METHOD("Метод обхода и SNI", "Обфускация / SNI / Reality"),
    SMART_DNS("DNS / Smart DNS", "Авто / Smart DNS / AdGuard / IPv4"),
    STALL_WATCHDOG(
        "Контроль зависаний",
        "Восстановление при простоях 0 Б/с",
        liveEditableWhenConnected = true,
    ),
    MTU("Размер MTU", "1400 / 1360 / 1280 / 1500"),
    ROOT_STEALTH("Root Stealth (fptn0)", "Скрытый TUN без значка VPN", requiresRoot = true),
    SMART_RF("Обход РФ", "РФ-подсети напрямую, Telegram в VPN"),
    AUTO_SWITCH_PRESET("Авто-пресет", "Смена профиля при переходе Wi-Fi ↔ 4G/5G"),
    SPLIT_APPS("Приложения", "Раздельное туннелирование"),
    HAPTICS(
        "Виброотклик",
        "Тактильная отдача интерфейса",
        liveEditableWhenConnected = true,
    ),
    LOGS(
        "Журнал",
        "Просмотр логов подключения",
        liveEditableWhenConnected = true,
    ),
    BYPASS_REGION(
        "Регион обхода",
        "Выбор региона маршрутизации",
        liveEditableWhenConnected = true,
    ),
    MANUAL_OVERRIDES(
        "Ручные фиксации",
        "Закреплённые параметры и сброс на авто",
        liveEditableWhenConnected = true,
    ),
    AUTO_FAILOVER(
        "Авто-резерв и SNI",
        "Смена сервера и SNI при сбое",
        liveEditableWhenConnected = true,
    ),
}

/** Reusable network profile overriding selected tunnel, bypass, DNS, and stability blocks. */
data class NetworkPreset(
    val id: String,
    val name: String,
    val trigger: NetworkPresetTrigger = NetworkPresetTrigger.MANUAL,
    // Per-block toggle: false keeps the user's current value unchanged.
    val overrideBypass: Boolean = true,
    val bypassMethod: BypassMethod = BypassMethod.SNI_REALITY,
    val sniSpoofingMode: SniSpoofingMode = SniSpoofingMode.SNI,
    val customSni: String = VpnSettings.DEFAULT_SNI_DOMAIN,
    val overrideSlidingTunnel: Boolean = true,
    val slidingTunnelMode: SlidingTunnelMode = SlidingTunnelMode.ROLLING_TUNNEL,
    val dnsModeOverride: DnsMode? = null,
    val enableCustomDnsUrl: Boolean = false,
    val customDnsUrl: String = "",
    val dnsPrimaryOverride: String = "",
    val dnsSecondaryOverride: String = "",
    val overrideWatchdog: Boolean = true,
    val enableStallWatchdog: Boolean = true,
    val watchdogSensitivity: WatchdogSensitivity? = null,
    val watchdogProbeSec: Int = 10,
    val watchdogStallSec: Int = 24,
    val watchdogCooldownSec: Int = 35,
    val overrideReconnect: Boolean = true,
    val reconnectOnFailure: Boolean = true,
    val reconnectOnNetworkChange: Boolean = true,
    val maxReconnectAttempts: Int = 15,
    val reconnectDelaySec: Int = 2,
    val handshakeTimeoutSec: Int = 8,
    val wsOpenTimeoutSec: Int = 15,
    val keepaliveIntervalSecOverride: Int? = null,
    val networkSwitchDebounceSecOverride: Int? = null,
    val autoRotateSniOnFail: Boolean? = null,
    val autoFailoverServer: Boolean? = null,
    val overrideMtu: Boolean = true,
    val tunMtu: Int = 1400,
    val smartRfRouting: Boolean = true,
    val smartRfRoutingOverride: Boolean? = null,
    val bypassRegionOverride: BypassRegion? = null,
    val splitTunnelModeOverride: SplitTunnelMode? = null,
    val autoPingIntervalSecOverride: Int? = null,
    val serverSortOrderOverride: ServerSortOrder? = null,
    val allowIpv6Override: Boolean? = null,
    val useRootModeOverride: Boolean? = null,
    // null = keep current server; -1L = auto best ping; >0L = specific server ID.
    val preferredServerId: Long? = null,
    val forceOverrideManualLocks: Boolean = false,
    val isBuiltIn: Boolean = false,
) {
    fun effectiveDnsModeOverride(): DnsMode? =
        dnsModeOverride ?: if (enableCustomDnsUrl) DnsMode.SMART_DNS_DOH else null
}
