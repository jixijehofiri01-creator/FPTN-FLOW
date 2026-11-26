package com.example.core.ui.localization

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.example.core.performance.AdaptiveBoostTier
import com.example.data.repository.BackupFormat
import com.example.domain.atmosphere.Season
import com.example.domain.atmosphere.SolarPhase
import com.example.domain.model.AppLanguage
import com.example.domain.model.BackgroundGradientStyle
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.DnsCategory
import com.example.domain.model.DnsMode
import com.example.domain.model.FlagMatrixDirection
import com.example.domain.model.HomeQuickTileId
import com.example.domain.model.ManualOverrideKey
import com.example.domain.model.NetworkPreset
import com.example.domain.model.NetworkPresetTrigger
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.ServerSortOrder
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.ThemeMode
import com.example.domain.model.WavePhysicsPreset
import com.example.domain.model.WeatherEffectMode
import com.example.domain.vpn.WatchdogSensitivity
import java.util.Locale

val LocalIsRussian = staticCompositionLocalOf { false }

@Composable
fun tr(ru: String, en: String): String = if (LocalIsRussian.current) ru else en

fun tr(isRu: Boolean, ru: String, en: String): String = if (isRu) ru else en

/**
 * Wraps an Activity context with locale-overridden [Resources] while preserving Activity delegation
 * for `startActivity()` and system service lookups without recreating the Activity.
 */
class LocalizedActivityContextWrapper(
    base: Context,
    private val localizedResources: Resources,
) : ContextWrapper(base) {
    override fun getResources(): Resources = localizedResources
}

data class ResolvedLocalization(
    val context: Context,
    val configuration: Configuration,
    val isRussian: Boolean,
)

@Composable
fun rememberLocalizedEnvironment(
    baseContext: Context,
    baseConfiguration: Configuration,
    appLanguage: AppLanguage,
): ResolvedLocalization {
    val systemLocale = baseConfiguration.locales.takeIf { !it.isEmpty }?.get(0) ?: Locale.getDefault()
    val systemLangCode = systemLocale.language
    return remember(baseContext, appLanguage, systemLangCode) {
        val targetLocale = when (appLanguage) {
            AppLanguage.SYSTEM -> systemLocale
            AppLanguage.RU -> Locale("ru")
            AppLanguage.EN -> Locale.ENGLISH
        }
        val isRussian = targetLocale.language.equals("ru", ignoreCase = true)
        val newConfig = Configuration(baseConfiguration).apply {
            setLocale(targetLocale)
        }
        val localizedResources = baseContext.createConfigurationContext(newConfig).resources
        val wrappedContext = LocalizedActivityContextWrapper(baseContext, localizedResources)
        ResolvedLocalization(
            context = wrappedContext,
            configuration = newConfig,
            isRussian = isRussian,
        )
    }
}

@Composable
fun AppLanguage.localizedTitle(): String = when (this) {
    AppLanguage.SYSTEM -> tr("Системный", "System")
    AppLanguage.RU -> "Русский"
    AppLanguage.EN -> "English"
}

@Composable
fun ThemeMode.localizedTitle(): String = when (this) {
    ThemeMode.SYSTEM -> tr("Системная", "System")
    ThemeMode.LIGHT -> tr("Светлая", "Light")
    ThemeMode.DARK -> tr("Тёмная", "Dark")
}

@Composable
fun BackgroundGradientStyle.localizedTitle(): String = when (this) {
    BackgroundGradientStyle.GLASS_AURORA -> tr("Стекло и Аврора", "Glass & Aurora")
    BackgroundGradientStyle.DEEP_COSMOS -> tr("Глубокий космос", "Deep Cosmos")
    BackgroundGradientStyle.EMERALD_PULSE -> tr("Изумрудный импульс", "Emerald Pulse")
    BackgroundGradientStyle.SUNSET_GLOW -> tr("Тёплый закат", "Sunset Glow")
    BackgroundGradientStyle.MINIMAL_CLEAN -> tr("Чистый минимал", "Minimal Clean")
}

@Composable
fun BackgroundGradientStyle.localizedSubtitle(): String = when (this) {
    BackgroundGradientStyle.GLASS_AURORA -> tr("Мягкий градиент темы Material You", "Soft Material You tonal gradient")
    BackgroundGradientStyle.DEEP_COSMOS -> tr("Тёмный индиго-графит с акцентом", "Dark indigo-graphite with accent glow")
    BackgroundGradientStyle.EMERALD_PULSE -> tr("Бирюзово-изумрудные оттенки", "Cyan and emerald tonal accents")
    BackgroundGradientStyle.SUNSET_GLOW -> tr("Янтарно-коралловый градиент", "Amber and coral warm gradient")
    BackgroundGradientStyle.MINIMAL_CLEAN -> tr("Однородный фон без свечения", "Clean flat surface without glow")
}

@Composable
fun FlagMatrixDirection.localizedTitle(): String = when (this) {
    FlagMatrixDirection.LEFT_TO_RIGHT -> tr("Слева направо →", "Left to right →")
    FlagMatrixDirection.RIGHT_TO_LEFT -> tr("Справа налево ←", "Right to left ←")
    FlagMatrixDirection.UP_TO_DOWN -> tr("Сверху вниз ↓", "Top to bottom ↓")
    FlagMatrixDirection.DOWN_TO_UP -> tr("Снизу вверх ↑", "Bottom to top ↑")
    FlagMatrixDirection.DIAGONAL_SE -> tr("Диагональ ↘", "Diagonal ↘")
    FlagMatrixDirection.DIAGONAL_NE -> tr("Диагональ ↗", "Diagonal ↗")
    FlagMatrixDirection.RANDOM_EACH_TIME -> tr("Случайное", "Random")
}

@Composable
fun FlagMatrixDirection.localizedShortTitle(): String = when (this) {
    FlagMatrixDirection.LEFT_TO_RIGHT -> tr("→ Вправо", "→ Right")
    FlagMatrixDirection.RIGHT_TO_LEFT -> tr("← Влево", "← Left")
    FlagMatrixDirection.UP_TO_DOWN -> tr("↓ Вниз", "↓ Down")
    FlagMatrixDirection.DOWN_TO_UP -> tr("↑ Вверх", "↑ Up")
    FlagMatrixDirection.DIAGONAL_SE -> tr("↘ Диаг.", "↘ Diag")
    FlagMatrixDirection.DIAGONAL_NE -> tr("↗ Диаг.", "↗ Diag")
    FlagMatrixDirection.RANDOM_EACH_TIME -> tr("Случайно", "Random")
}

@Composable
fun WeatherEffectMode.localizedTitle(): String = when (this) {
    WeatherEffectMode.OFF -> tr("Выкл", "Off")
    WeatherEffectMode.AUTO_LOCATION -> tr("По геолокации", "Auto (location)")
    WeatherEffectMode.CLEAR -> tr("Ясно / Солнце", "Clear / Sun")
    WeatherEffectMode.PARTLY_CLOUDY -> tr("Переменная облачность", "Partly cloudy")
    WeatherEffectMode.CLOUDY -> tr("Облачно (3D-облака)", "Cloudy (3D clouds)")
    WeatherEffectMode.FOG -> tr("3D-туман (объёмный)", "3D Fog (volumetric)")
    WeatherEffectMode.DRIZZLE -> tr("Морось и дымка", "Drizzle & mist")
    WeatherEffectMode.RAIN -> tr("Дождь", "Rain")
    WeatherEffectMode.HEAVY_RAIN -> tr("Ливень", "Heavy rain")
    WeatherEffectMode.STORM -> tr("Гроза", "Thunderstorm")
    WeatherEffectMode.SNOW -> tr("Снег", "Snow")
    WeatherEffectMode.BLIZZARD -> tr("Метель", "Blizzard")
    WeatherEffectMode.HAIL -> tr("Град", "Hail")
}

@Composable
fun WeatherEffectMode.localizedShortTitle(): String = when (this) {
    WeatherEffectMode.OFF -> tr("Выкл", "Off")
    WeatherEffectMode.AUTO_LOCATION -> tr("Авто", "Auto")
    WeatherEffectMode.CLEAR -> tr("Ясно", "Clear")
    WeatherEffectMode.PARTLY_CLOUDY -> tr("Переменная", "Partly")
    WeatherEffectMode.CLOUDY -> tr("Облачно", "Cloudy")
    WeatherEffectMode.FOG -> tr("3D-туман", "3D Fog")
    WeatherEffectMode.DRIZZLE -> tr("Морось", "Drizzle")
    WeatherEffectMode.RAIN -> tr("Дождь", "Rain")
    WeatherEffectMode.HEAVY_RAIN -> tr("Ливень", "Downpour")
    WeatherEffectMode.STORM -> tr("Гроза", "Storm")
    WeatherEffectMode.SNOW -> tr("Снег", "Snow")
    WeatherEffectMode.BLIZZARD -> tr("Метель", "Blizzard")
    WeatherEffectMode.HAIL -> tr("Град", "Hail")
}

@Composable
fun WeatherEffectMode.localizedSubtitle(): String = when (this) {
    WeatherEffectMode.OFF -> tr("Без погодных эффектов", "No weather effects")
    WeatherEffectMode.AUTO_LOCATION -> tr("Реальная погода Open-Meteo по приближённой локации", "Live Open-Meteo weather using approximate location")
    WeatherEffectMode.CLEAR -> tr("Мягкий солнечный свет и лёгкое мерцание", "Soft sunlight and subtle shimmer")
    WeatherEffectMode.PARTLY_CLOUDY -> tr("Объёмные 3D-облака и мягкие солнечные лучи", "Volumetric 3D clouds and soft sun rays")
    WeatherEffectMode.CLOUDY -> tr("Многослойный объёмный 3D-покров облаков с глубиной", "Multi-layer volumetric 3D cloud deck")
    WeatherEffectMode.FOG -> tr("Глубокий объёмный 3D-туман с параллаксом и светорассеянием", "Deep volumetric 3D fog with parallax scattering")
    WeatherEffectMode.DRIZZLE -> tr("Лёгкая 3D-дымка и мелкая морось с деликатными каплями", "Light 3D mist and delicate drizzle droplets")
    WeatherEffectMode.RAIN -> tr("Капли дождя со всплесками и виброоткликом", "Raindrops with splashes and subtle haptics")
    WeatherEffectMode.HEAVY_RAIN -> tr("Плотный ливень, ветер и частые брызги", "Dense downpour, wind gusts, and frequent splashes")
    WeatherEffectMode.STORM -> tr("Грозовые 3D-тучи, ветвистые молнии, ливень и гром", "Storm 3D clouds, forked lightning, rain, and thunder")
    WeatherEffectMode.SNOW -> tr("Плавный объёмный снегопад с ветровым дрейфом", "Gentle volumetric snowfall with wind drift")
    WeatherEffectMode.BLIZZARD -> tr("Сильная вьюга с косым снегом и ледяной 3D-позёмкой", "Heavy blizzard with slanted snow and icy drift")
    WeatherEffectMode.HAIL -> tr("Ледяные градины с отскоком и чётким тактильным ударом", "Bouncing ice hailstones with crisp tactile impact")
}

@Composable
fun WavePhysicsPreset.localizedTitle(): String = when (this) {
    WavePhysicsPreset.LIQUID_GLASS -> tr("Жидкое стекло", "Liquid Glass")
    WavePhysicsPreset.SILK_RIPPLE -> tr("Шёлковая рябь", "Silk Ripple")
    WavePhysicsPreset.HEAVY_WATER -> tr("Тяжёлая вода", "Heavy Water")
    WavePhysicsPreset.CRYSTAL -> tr("Кристалл", "Crystal")
    WavePhysicsPreset.DELICATE -> tr("Деликатная", "Delicate")
}

@Composable
fun DnsMode.localizedTitle(): String = when (this) {
    DnsMode.AUTO_VPN -> tr("Стандартный DNS сервера FPTN", "Default FPTN server DNS")
    DnsMode.SMART_DNS_DOH -> "Smart DNS (DoH)"
    DnsMode.ADBLOCK_DNS -> tr("AdGuard DoH (блокировка рекламы)", "AdGuard DoH (ad blocking)")
    DnsMode.CUSTOM_IPV4 -> tr("Ручной IPv4 DNS", "Custom IPv4 DNS")
}

@Composable
fun DnsMode.localizedShortTitle(): String = when (this) {
    DnsMode.AUTO_VPN -> tr("Авто (FPTN)", "Auto (FPTN)")
    DnsMode.SMART_DNS_DOH -> "Smart DNS"
    DnsMode.ADBLOCK_DNS -> "AdGuard"
    DnsMode.CUSTOM_IPV4 -> tr("IPv4 вручную", "Custom IPv4")
}

@Composable
fun DnsMode.localizedSubtitle(): String = when (this) {
    DnsMode.AUTO_VPN -> tr("DNS сервера FPTN с минимальной задержкой", "FPTN server DNS with minimal latency")
    DnsMode.SMART_DNS_DOH -> tr("Доступ к ИИ-сервисам через DoH внутри туннеля", "Access AI services via in-tunnel DoH")
    DnsMode.ADBLOCK_DNS -> tr("Фильтрация рекламы и трекеров через dns.adguard-dns.com", "Block ads and trackers via dns.adguard-dns.com")
    DnsMode.CUSTOM_IPV4 -> tr("Основной и резервный IPv4-серверы без DoH-прокси", "Primary and secondary IPv4 servers without DoH proxy")
}

@Composable
fun DnsCategory.localizedTitle(): String = when (this) {
    DnsCategory.INTERNATIONAL_AI -> tr("Международные домены", "International domains")
    DnsCategory.RU_ZONE_AI -> tr("Доменная зона .ru", ".ru zone")
    DnsCategory.PUBLIC_RESERVE -> tr("Базовые и резервные", "Public & fallback")
    DnsCategory.CUSTOM -> tr("Пользовательские", "Custom")
}

@Composable
fun DnsCategory.localizedDescription(): String = when (this) {
    DnsCategory.INTERNATIONAL_AI -> tr("Резолверы вне зоны .ru для ИИ-сервисов", "Non-.ru resolvers for AI services")
    DnsCategory.RU_ZONE_AI -> tr("Серверы в зоне .ru с низким откликом из РФ", "Low-latency .ru resolvers")
    DnsCategory.PUBLIC_RESERVE -> tr("Глобальные DoH-резолверы (Cloudflare, Google, AdGuard)", "Global DoH resolvers (Cloudflare, Google, AdGuard)")
    DnsCategory.CUSTOM -> tr("Добавленные вручную DoH, DoT или IPv4 серверы", "User-added DoH, DoT, or IPv4 servers")
}

@Composable
fun BypassRegion.localizedTitle(): String = when (this) {
    BypassRegion.RUSSIA -> tr("Россия (.ru / .рф / .su)", "Russia (.ru / .рф / .su)")
    BypassRegion.CHINA -> tr("Китай (.cn / GFW)", "China (.cn / GFW)")
    BypassRegion.IRAN -> tr("Иран (.ir / NIN)", "Iran (.ir / NIN)")
    BypassRegion.VIETNAM -> tr("Вьетнам (.vn)", "Vietnam (.vn)")
    BypassRegion.NONE -> tr("Защита РФ активна", "RU protection active")
}

@Composable
fun BypassRegion.localizedShortTitle(): String = when (this) {
    BypassRegion.RUSSIA -> tr("Россия", "Russia")
    BypassRegion.CHINA -> tr("Китай", "China")
    BypassRegion.IRAN -> tr("Иран", "Iran")
    BypassRegion.VIETNAM -> tr("Вьетнам", "Vietnam")
    BypassRegion.NONE -> tr("Защита РФ", "RU Protection")
}

@Composable
fun BypassRegion.localizedSubtitle(): String = when (this) {
    BypassRegion.RUSSIA -> tr("РФ-сайты, банки и госуслуги напрямую; Telegram через VPN", "Russian sites, banks, and gov services direct; Telegram via VPN")
    BypassRegion.CHINA -> tr("Китайские сайты и CN-подсети напрямую; остальное в VPN", "Chinese domains and CN subnets direct; rest via VPN")
    BypassRegion.IRAN -> tr("Иранские сайты и IR-подсети напрямую; остальное в VPN", "Iranian domains and IR subnets direct; rest via VPN")
    BypassRegion.VIETNAM -> tr("Вьетнамские сайты и VN-подсети напрямую без VPN", "Vietnamese domains and VN subnets direct without VPN")
    BypassRegion.NONE -> tr("Отключение защиты РФ заблокировано для сохранности серверов FPTN", "Disabling RU protection is locked to safeguard FPTN servers")
}

@Composable
fun BypassMethod.localizedTitle(): String = when (this) {
    BypassMethod.SNI_REALITY -> tr("Подмена домена (SNI / Reality)", "Domain spoofing (SNI / Reality)")
    BypassMethod.TLS_OBFUSCATION -> tr("Маскировка трафика (обфускация)", "Traffic disguise (Obfuscation)")
}

@Composable
fun BypassMethod.localizedShortTitle(): String = when (this) {
    BypassMethod.SNI_REALITY -> "SNI / Reality"
    BypassMethod.TLS_OBFUSCATION -> tr("Обфускация", "Obfuscation")
}

@Composable
fun BypassMethod.localizedSubtitle(): String = when (this) {
    BypassMethod.SNI_REALITY -> tr("Маскировка TLS под домен с отпечатком браузера", "TLS domain spoofing with browser fingerprint")
    BypassMethod.TLS_OBFUSCATION -> tr("Обфускация туннеля поверх SNI против DPI", "Tunnel obfuscation over SNI against DPI")
}

@Composable
fun SniSpoofingMode.localizedTitle(): String = when (this) {
    SniSpoofingMode.SNI -> tr("Подмена домена (базовый)", "Domain spoofing (Base SNI)")
    SniSpoofingMode.SNI_REALITY_YANDEX_26_4 -> tr("Яндекс Браузер 26.4 (Reality)", "Yandex Browser 26.4 (Reality)")
    SniSpoofingMode.SNI_REALITY_YANDEX_26_3 -> tr("Яндекс Браузер 26.3 (Reality)", "Yandex Browser 26.3 (Reality)")
    SniSpoofingMode.SNI_REALITY_YANDEX_25 -> tr("Яндекс Браузер 25 (Reality)", "Yandex Browser 25 (Reality)")
    SniSpoofingMode.SNI_REALITY_YANDEX_24 -> tr("Яндекс Браузер 24 (Reality)", "Yandex Browser 24 (Reality)")
    else -> title
}

@Composable
fun SlidingTunnelMode.localizedTitle(): String = when (this) {
    SlidingTunnelMode.ROLLING_TUNNEL -> tr("Скользящий туннель", "Rolling tunnel")
    SlidingTunnelMode.DUAL_TUNNEL -> tr("Двойной скользящий туннель", "Dual rolling tunnel")
    SlidingTunnelMode.TRIPLE_TUNNEL -> tr("Тройной скользящий туннель", "Triple rolling tunnel")
}

@Composable
fun SlidingTunnelMode.localizedShortTitle(): String = when (this) {
    SlidingTunnelMode.ROLLING_TUNNEL -> tr("Одиночный", "Single")
    SlidingTunnelMode.DUAL_TUNNEL -> tr("Двойной", "Dual")
    SlidingTunnelMode.TRIPLE_TUNNEL -> tr("Тройной", "Triple")
}

@Composable
fun SlidingTunnelMode.localizedSubtitle(): String = when (this) {
    SlidingTunnelMode.ROLLING_TUNNEL -> tr("Один канал с фоновым обновлением сессии", "Single channel with background session renewal")
    SlidingTunnelMode.DUAL_TUNNEL -> tr("Два параллельных канала для стабильности", "Two parallel channels for higher stability")
    SlidingTunnelMode.TRIPLE_TUNNEL -> tr("Три канала для нестабильных мобильных сетей", "Three channels for unstable mobile networks")
}

@Composable
fun SplitTunnelMode.localizedTitle(): String = when (this) {
    SplitTunnelMode.ALL_APPS -> tr("Все приложения", "All apps")
    SplitTunnelMode.EXCLUDE_SELECTED -> tr("Исключить выбранные", "Exclude selected")
    SplitTunnelMode.INCLUDE_SELECTED -> tr("Только выбранные", "Selected only")
}

@Composable
fun SplitTunnelMode.localizedSubtitle(): String = when (this) {
    SplitTunnelMode.ALL_APPS -> tr("Весь трафик (кроме РФ-исключений) идёт через VPN", "All traffic (except RU exclusions) goes through VPN")
    SplitTunnelMode.EXCLUDE_SELECTED -> tr("Выбранные приложения работают напрямую", "Selected apps bypass the VPN")
    SplitTunnelMode.INCLUDE_SELECTED -> tr("Через VPN работают только отмеченные приложения", "Only selected apps use the VPN")
}

@Composable
fun ServerSortOrder.localizedTitle(): String = when (this) {
    ServerSortOrder.ORIGINAL -> tr("По порядку", "Original")
    ServerSortOrder.BY_PING -> tr("По пингу", "By ping")
}

@Composable
fun NetworkPresetTrigger.localizedTitle(): String = when (this) {
    NetworkPresetTrigger.MANUAL -> tr("Вручную", "Manual")
    NetworkPresetTrigger.MOBILE -> tr("Мобильная сеть", "Mobile")
    NetworkPresetTrigger.WIFI -> "Wi-Fi"
}

@Composable
fun ManualOverrideKey.localizedTitle(): String = when (this) {
    ManualOverrideKey.SERVER -> tr("Сервер подключения", "Connection server")
    ManualOverrideKey.PRESET -> tr("Пресет сети", "Network preset")
    ManualOverrideKey.DNS -> tr("DNS и Smart DNS", "DNS & Smart DNS")
    ManualOverrideKey.BYPASS_METHOD -> tr("Метод обхода и TLS", "Bypass method & TLS")
    ManualOverrideKey.CUSTOM_SNI -> tr("SNI-домен", "SNI domain")
    ManualOverrideKey.SLIDING_TUNNEL -> tr("Скользящий туннель", "Rolling tunnel")
    ManualOverrideKey.TUN_MTU -> tr("MTU туннеля", "Tunnel MTU")
    ManualOverrideKey.WATCHDOG -> tr("Контроль зависаний", "Stall watchdog")
    ManualOverrideKey.RECONNECT -> tr("Переподключение", "Reconnect")
    ManualOverrideKey.ROUTING_REGION -> tr("Регион обхода", "Bypass region")
}

@Composable
fun ManualOverrideKey.localizedAutoDescription(): String = when (this) {
    ManualOverrideKey.SERVER -> tr("Автовыбор сервера по минимальному пингу", "Auto-select server with lowest ping")
    ManualOverrideKey.PRESET -> tr("Автоподбор пресета под Wi-Fi или 4G/5G", "Auto-match preset for Wi-Fi or 4G/5G")
    ManualOverrideKey.DNS -> tr("Стандартный DNS сервера FPTN", "Default FPTN server DNS")
    ManualOverrideKey.BYPASS_METHOD -> tr("SNI / Reality из активного пресета", "SNI / Reality from active preset")
    ManualOverrideKey.CUSTOM_SNI -> tr("Автоматический SNI пресета или региона", "Automatic preset or regional SNI")
    ManualOverrideKey.SLIDING_TUNNEL -> tr("Режим туннеля из активного пресета", "Tunnel mode from active preset")
    ManualOverrideKey.TUN_MTU -> tr("1400 байт (1360 на 4G/5G)", "1400 bytes (1360 on 4G/5G)")
    ManualOverrideKey.WATCHDOG -> tr("Мягкий профиль 10с / 24с с UDP/ICMP пробами", "Gentle 10s / 24s profile with UDP/ICMP probes")
    ManualOverrideKey.RECONNECT -> tr("Автовосстановление при обрыве и смене сети", "Auto-recovery on drop and network change")
    ManualOverrideKey.ROUTING_REGION -> tr("Россия (.ru / .рф / .su) напрямую, Telegram в VPN", "Russia (.ru / .рф / .su) direct, Telegram via VPN")
}

@Composable
fun ManualOverrideKey.localizedCategory(): String = when (this) {
    ManualOverrideKey.SERVER, ManualOverrideKey.PRESET -> tr("Подключение", "Connection")
    ManualOverrideKey.DNS, ManualOverrideKey.TUN_MTU -> tr("DNS и сеть", "DNS & Network")
    ManualOverrideKey.BYPASS_METHOD, ManualOverrideKey.CUSTOM_SNI, ManualOverrideKey.SLIDING_TUNNEL -> tr("Обход блокировок", "Censorship bypass")
    ManualOverrideKey.WATCHDOG, ManualOverrideKey.RECONNECT -> tr("Стабильность", "Stability")
    ManualOverrideKey.ROUTING_REGION -> tr("Маршрутизация", "Routing")
}

@Composable
fun HomeQuickTileId.localizedTitle(): String = when (this) {
    HomeQuickTileId.PRESET -> tr("Пресет сети", "Network preset")
    HomeQuickTileId.SLIDING_TUNNEL -> tr("Скользящий туннель", "Rolling tunnel")
    HomeQuickTileId.BYPASS_METHOD -> tr("Метод обхода и SNI", "Bypass & SNI")
    HomeQuickTileId.SMART_DNS -> "DNS / Smart DNS"
    HomeQuickTileId.STALL_WATCHDOG -> tr("Контроль зависаний", "Stall watchdog")
    HomeQuickTileId.MTU -> tr("Размер MTU", "MTU size")
    HomeQuickTileId.ROOT_STEALTH -> "Root Stealth (fptn0)"
    HomeQuickTileId.SMART_RF -> tr("Обход РФ", "RU bypass")
    HomeQuickTileId.AUTO_SWITCH_PRESET -> tr("Авто-пресет", "Auto preset")
    HomeQuickTileId.SPLIT_APPS -> tr("Приложения", "Split apps")
    HomeQuickTileId.HAPTICS -> tr("Виброотклик", "Haptics")
    HomeQuickTileId.LOGS -> tr("Журнал", "Logs")
    HomeQuickTileId.BYPASS_REGION -> tr("Регион обхода", "Bypass region")
    HomeQuickTileId.MANUAL_OVERRIDES -> tr("Ручные фиксации", "Manual locks")
    HomeQuickTileId.AUTO_FAILOVER -> tr("Авто-резерв и SNI", "Auto failover")
}

@Composable
fun HomeQuickTileId.localizedDescription(): String = when (this) {
    HomeQuickTileId.PRESET -> tr("Профиль под Wi-Fi, 4G/5G или ТСПУ", "Profile for Wi-Fi, 4G/5G, or strict DPI")
    HomeQuickTileId.SLIDING_TUNNEL -> tr("Одиночный / Двойной / Тройной", "Single / Dual / Triple")
    HomeQuickTileId.BYPASS_METHOD -> tr("Обфускация / SNI / Reality", "Obfuscation / SNI / Reality")
    HomeQuickTileId.SMART_DNS -> tr("Авто / Smart DNS / AdGuard / IPv4", "Auto / Smart DNS / AdGuard / IPv4")
    HomeQuickTileId.STALL_WATCHDOG -> tr("Восстановление при простоях 0 Б/с", "Recover on silent 0 B/s stalls")
    HomeQuickTileId.MTU -> "1400 / 1360 / 1280 / 1500"
    HomeQuickTileId.ROOT_STEALTH -> tr("Скрытый TUN без значка VPN", "Hidden kernel TUN without VPN icon")
    HomeQuickTileId.SMART_RF -> tr("РФ-подсети напрямую, Telegram в VPN", "RU subnets direct, Telegram via VPN")
    HomeQuickTileId.AUTO_SWITCH_PRESET -> tr("Смена профиля при переходе Wi-Fi ↔ 4G/5G", "Switch profile on Wi-Fi ↔ 4G/5G change")
    HomeQuickTileId.SPLIT_APPS -> tr("Раздельное туннелирование", "Per-app split tunneling")
    HomeQuickTileId.HAPTICS -> tr("Тактильная отдача интерфейса", "Tactile UI feedback")
    HomeQuickTileId.LOGS -> tr("Просмотр логов подключения", "Inspect connection logs")
    HomeQuickTileId.BYPASS_REGION -> tr("Выбор региона маршрутизации", "Select regional bypass rules")
    HomeQuickTileId.MANUAL_OVERRIDES -> tr("Закреплённые параметры и сброс на авто", "Locked parameters & reset to auto")
    HomeQuickTileId.AUTO_FAILOVER -> tr("Смена сервера и SNI при сбое", "Rotate server & SNI on failure")
}

@Composable
fun WatchdogSensitivity.localizedTitle(): String = when (this) {
    WatchdogSensitivity.GENTLE -> tr("Мягкий (4G / Центр города)", "Gentle (4G / Urban)")
    WatchdogSensitivity.BALANCED -> tr("Сбалансированный", "Balanced")
    WatchdogSensitivity.AGGRESSIVE -> tr("Быстрый (жёсткий ТСПУ)", "Fast (Strict DPI)")
}

@Composable
fun WatchdogSensitivity.localizedShortTitle(): String = when (this) {
    WatchdogSensitivity.GENTLE -> tr("Мягкий", "Gentle")
    WatchdogSensitivity.BALANCED -> tr("Баланс", "Balanced")
    WatchdogSensitivity.AGGRESSIVE -> tr("Быстрый", "Fast")
}

@Composable
fun WatchdogSensitivity.localizedSubtitle(): String = when (this) {
    WatchdogSensitivity.GENTLE -> tr(
        "Максимальная терпимость к провалам LTE и глушилкам — исключает ложные переподключения",
        "High tolerance for LTE drops and cellular jitter — avoids false reconnects",
    )
    WatchdogSensitivity.BALANCED -> tr(
        "Оптимальный баланс скорости реакции и устойчивости на мобильной сети",
        "Balanced detection speed and mobile network resilience",
    )
    WatchdogSensitivity.AGGRESSIVE -> tr(
        "Ускоренная диагностика заморозки 0 Б/с при агрессивном сбросе сессий",
        "Faster 0 B/s blackhole detection under aggressive DPI resets",
    )
}

@Composable
fun AdaptiveBoostTier.localizedShortTitle(): String = when (this) {
    AdaptiveBoostTier.INACTIVE -> tr("Выключено", "Inactive")
    AdaptiveBoostTier.YIELD_TO_GAMES -> tr("Уступает играм/нагрузке", "Yielding to load")
    AdaptiveBoostTier.BALANCED -> tr("Сбалансировано", "Balanced")
    AdaptiveBoostTier.MAX_TURBO -> tr("Макс. приоритет VPN", "Max VPN priority")
}

@Composable
fun SolarPhase.localizedTitle(): String = when (this) {
    SolarPhase.NIGHT -> tr("Ночь", "Night")
    SolarPhase.DAWN -> tr("Рассвет", "Dawn")
    SolarPhase.MORNING -> tr("Утро", "Morning")
    SolarPhase.DAY -> tr("День", "Day")
    SolarPhase.GOLDEN_HOUR -> tr("Закат", "Golden hour")
    SolarPhase.DUSK -> tr("Сумерки", "Dusk")
}

@Composable
fun Season.localizedTitle(): String = when (this) {
    Season.WINTER -> tr("Зима", "Winter")
    Season.SPRING -> tr("Весна", "Spring")
    Season.SUMMER -> tr("Лето", "Summer")
    Season.AUTUMN -> tr("Осень", "Autumn")
}

@Composable
fun BackupFormat.localizedTitle(): String = when (this) {
    BackupFormat.FULL_CLIENT -> tr("Полная резервная копия (v14)", "Full client backup (v14)")
    BackupFormat.OFFICIAL_COMPATIBLE -> tr("Совместимый формат FPTN", "Official FPTN-compatible format")
    BackupFormat.RAW_TOKENS -> tr("Токены fptn:/fptnb:", "Raw fptn:/fptnb: tokens")
}

@Composable
fun NetworkPreset.localizedName(): String {
    if (!isBuiltIn) return name
    return when (id) {
        "preset_mobile" -> tr("Мобильная сеть (4G/5G)", "Mobile Network (4G/5G)")
        "preset_wifi" -> tr("Wi-Fi / Домашняя сеть", "Wi-Fi / Home Network")
        "preset_tspu_max" -> tr("ТСПУ Максимум", "DPI Maximum")
        "preset_low_latency" -> tr("Игры и звонки", "Games & Calls")
        else -> name
    }
}

@Composable
fun SavedDnsEntry.localizedTitle(): String {
    if (!isBuiltIn) return title
    return when (id) {
        "zapret_mirror_dns" -> tr("Zapret DNS (зеркало)", "Zapret DNS (mirror)")
        else -> title
    }
}

@Composable
fun SavedDnsEntry.localizedSubtitle(): String {
    if (!isBuiltIn) return subtitle
    return when (id) {
        "magic_dns_intl" -> tr("Стабильный резолвер для ИИ-сервисов", "Reliable resolver for AI services")
        "bezmezhau_dns" -> tr("Gemini, ChatGPT и Copilot", "Gemini, ChatGPT, and Copilot")
        "astracat_commonnet" -> tr("ODoH и фильтрация рекламы", "ODoH and ad filtering")
        "comss_smart" -> tr("ИИ-сервисы и фильтрация рекламы", "AI services and ad filtering")
        "wlrus_dns" -> tr("Резолвер в зоне .cfd для ИИ-сервисов", ".cfd resolver for AI services")
        "malw_smart" -> tr("Доступ к ИИ и зарубежным сервисам", "Access to AI and global services")
        "mafioznik_dns" -> tr("Альтернативный Smart DNS в зоне .xyz", "Alternative Smart DNS in .xyz")
        "zapret_mirror_dns" -> tr("Международное зеркало для ИИ-платформ", "International mirror for AI platforms")
        "dns_ai_ru" -> tr("Низкий пинг в зоне .ru для ИИ", "Low-ping .ru resolver for AI")
        "geohide_eu" -> tr("Европейский узел GeoHide", "GeoHide European node")
        "geohide_us" -> tr("Американский узел GeoHide", "GeoHide US node")
        "dagami_ru" -> tr("Резолвер в зоне .ru для AI API", ".ru resolver for AI APIs")
        "magic_dns_ru" -> tr("Российская ветка MagicDNS", "MagicDNS Russian mirror")
        "astracat_ru" -> tr("Российское зеркало Astracat", "Astracat Russian mirror")
        "cloudflare_doh" -> tr("Глобальный DoH-резолвер", "Global DoH resolver")
        "google_doh" -> tr("Глобальный DoH-резолвер Google", "Google global DoH resolver")
        "adguard_doh" -> tr("Фильтрация рекламы и трекеров", "Ads and trackers filtering")
        else -> subtitle
    }
}
