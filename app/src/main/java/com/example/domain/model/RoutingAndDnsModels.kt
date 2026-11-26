package com.example.domain.model

/** Mutually exclusive DNS strategy active inside the VPN tunnel. */
enum class DnsMode(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
) {
    AUTO_VPN(
        title = "Стандартный DNS сервера FPTN",
        shortTitle = "Авто (FPTN)",
        subtitle = "DNS сервера FPTN с минимальной задержкой",
    ),
    SMART_DNS_DOH(
        title = "Smart DNS (DoH)",
        shortTitle = "Smart DNS",
        subtitle = "Доступ к ИИ-сервисам через DoH внутри туннеля",
    ),
    ADBLOCK_DNS(
        title = "AdGuard DoH (блокировка рекламы)",
        shortTitle = "AdGuard",
        subtitle = "Фильтрация рекламы и трекеров через dns.adguard-dns.com",
    ),
    CUSTOM_IPV4(
        title = "Ручной IPv4 DNS",
        shortTitle = "IPv4 вручную",
        subtitle = "Основной и резервный IPv4-серверы без DoH-прокси",
    ),
}

enum class DnsCategory(
    val title: String,
    val description: String,
) {
    INTERNATIONAL_AI(
        title = "Международные домены",
        description = "Резолверы вне зоны .ru для ИИ-сервисов",
    ),
    RU_ZONE_AI(
        title = "Доменная зона .ru",
        description = "Серверы в зоне .ru с низким откликом из РФ",
    ),
    PUBLIC_RESERVE(
        title = "Базовые и резервные",
        description = "Глобальные DoH-резолверы (Cloudflare, Google, AdGuard)",
    ),
    CUSTOM(
        title = "Пользовательские",
        description = "Добавленные вручную DoH, DoT или IPv4 серверы",
    ),
}

data class SavedDnsEntry(
    val id: String,
    val title: String,
    val urlOrHost: String,
    val subtitle: String = "",
    val fallbackIpv4: String? = null,
    val isBuiltIn: Boolean = false,
    val category: DnsCategory = DnsCategory.CUSTOM,
)

/**
 * Regional direct-bypass profiles (TLDs, domestic domain roots, and curated SNI candidates).
 * Disabling regional bypass (`NONE`) requires live-verified Root access.
 */
enum class BypassRegion(
    val title: String,
    val subtitle: String,
    val tlds: Set<String>,
    val directDomainRoots: Set<String>,
    val recommendedSnis: List<String>,
    val requiresRoot: Boolean = false,
) {
    RUSSIA(
        title = "Россия (.ru / .рф / .su)",
        subtitle = "РФ-сайты, банки и госуслуги напрямую; Telegram через VPN",
        tlds = setOf(".ru", ".su", ".рф", ".xn--p1ai"),
        directDomainRoots = setOf(
            "ya.ru", "yandex.ru", "yandex.com", "yandex.net", "yastatic.net",
            "vk.com", "vk.ru", "vk.me", "vkvideo.ru", "userapi.com", "ok.ru", "mycdn.me", "mail.ru",
            "ozon.ru", "wildberries.ru", "wb.ru", "avito.ru", "megamarket.ru", "market.yandex.ru",
            "gosuslugi.ru", "nalog.gov.ru", "mos.ru", "gu-st.ru", "esia.gosuslugi.ru",
            "sberbank.ru", "sber.ru", "online.sberbank.ru", "tbank.ru", "tinkoff.ru",
            "vtb.ru", "alfabank.ru", "gazprombank.ru", "rshb.ru", "psbank.ru",
            "sovcombank.ru", "raiffeisen.ru", "mtsbank.ru", "open.ru",
            "mts.ru", "beeline.ru", "megafon.ru", "t2.ru", "tele2.ru", "rostelecom.ru", "rt.ru",
            "2gis.com", "2gis.ru", "kinopoisk.ru", "rutube.ru", "dzen.ru", "ivi.ru", "okko.tv",
            "pochta.ru", "rzd.ru", "aeroflot.ru", "cdek.ru", "hh.ru", "rbc.ru", "rambler.ru",
            "lenta.ru", "kp.ru", "ria.ru", "tass.ru", "auto.ru", "cian.ru", "drom.ru",
            "dns-shop.ru", "mvideo.ru", "citilink.ru", "lamoda.ru", "x5.ru", "habr.com",
        ),
        recommendedSnis = listOf(
            "ads.x5.ru", "ya.ru", "vk.com", "gosuslugi.ru", "ozon.ru",
            "sberbank.ru", "wildberries.ru", "dzen.ru", "tbank.ru", "mts.ru", "avito.ru", "rbc.ru",
        ),
    ),
    CHINA(
        title = "Китай (.cn / GFW)",
        subtitle = "Китайские сайты и CN-подсети напрямую; остальное в VPN",
        tlds = setOf(".cn", ".xn--fiqs8s", ".xn--fiqz9s"),
        directDomainRoots = setOf(
            "baidu.com", "qq.com", "weixin.qq.com", "wechat.com", "taobao.com", "tmall.com",
            "jd.com", "alipay.com", "alicdn.com", "aliyun.com", "bilibili.com", "zhihu.com",
            "weibo.com", "douyin.com", "163.com", "126.com", "ctrip.com", "meituan.com",
            "pinduoduo.com", "xiaohongshu.com", "mi.com", "huawei.com",
        ),
        recommendedSnis = listOf(
            "www.apple.com", "itunes.apple.com", "www.microsoft.com", "www.bing.com",
            "cdn.jsdelivr.net", "www.icloud.com", "www.baidu.com", "qq.com", "www.bilibili.com", "www.aliyun.com",
        ),
    ),
    IRAN(
        title = "Иран (.ir / NIN)",
        subtitle = "Иранские сайты и IR-подсети напрямую; остальное в VPN",
        tlds = setOf(".ir", ".xn--mgba3a4f16a"),
        directDomainRoots = setOf(
            "digikala.com", "aparat.com", "snapp.ir", "divar.ir", "shaparak.ir",
            "cafebazaar.ir", "rubika.ir", "eitaa.com", "bale.ai", "varzesh3.com",
            "telewebion.com", "namasha.com", "filimo.com", "torob.com", "sheypoor.com",
            "bankmellat.ir", "bmi.ir",
        ),
        recommendedSnis = listOf(
            "www.speedtest.net", "www.digikala.com", "www.aparat.com", "www.varzesh3.com",
            "www.cafebazaar.ir", "snapp.ir", "divar.ir", "www.cloudflare.com", "www.microsoft.com", "www.yahoo.com",
        ),
    ),
    VIETNAM(
        title = "Вьетнам (.vn)",
        subtitle = "Вьетнамские сайты и VN-подсети напрямую без VPN",
        tlds = setOf(".vn"),
        directDomainRoots = setOf(
            "zalo.me", "zingnews.vn", "vnexpress.net", "dantri.com.vn", "thanhnien.vn",
            "tuoitre.vn", "vietnamnet.vn", "shopee.vn", "tiki.vn", "lazada.vn",
            "thegioididong.com", "fpt.vn", "viettel.vn", "vnpt.vn", "vietcombank.com.vn",
            "momo.vn", "zalopay.vn",
        ),
        recommendedSnis = listOf(
            "vnexpress.net", "zalo.me", "fpt.vn", "viettel.vn", "shopee.vn",
            "www.google.com", "www.cloudflare.com", "www.microsoft.com",
        ),
    ),
    NONE(
        title = "Защита РФ активна",
        subtitle = "Отключение защиты РФ заблокировано для сохранности серверов FPTN",
        tlds = emptySet(),
        directDomainRoots = emptySet(),
        recommendedSnis = listOf("ads.x5.ru", "ya.ru", "vk.com"),
        requiresRoot = true,
    ),
    ;

    companion object {
        /** Regions selectable in UI — NONE is permanently disabled so RU/FPTN server protection stays locked even with Root. */
        val selectableEntries: List<BypassRegion>
            get() = entries.filter { it != NONE }

        fun sanitize(region: BypassRegion?): BypassRegion =
            if (region == null || region == NONE) RUSSIA else region
    }
}

enum class BypassMethod(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
) {
    SNI_REALITY(
        title = "Подмена домена (SNI / Reality)",
        shortTitle = "SNI / Reality",
        subtitle = "Маскировка TLS под домен с отпечатком браузера",
    ),
    TLS_OBFUSCATION(
        title = "Маскировка трафика (обфускация)",
        shortTitle = "Обфускация",
        subtitle = "Обфускация туннеля поверх SNI против DPI",
    ),
}

enum class SniSpoofingMode(
    val title: String,
    val group: String,
) {
    SNI("Подмена домена (базовый)", "Базовый"),
    SNI_REALITY_CHROME_149("Chrome 149 (Reality)", "Chrome"),
    SNI_REALITY_CHROME_148("Chrome 148 (Reality)", "Chrome"),
    SNI_REALITY_CHROME_147("Chrome 147 (Reality)", "Chrome"),
    SNI_REALITY_CHROME_146("Chrome 146 (Reality)", "Chrome"),
    SNI_REALITY_CHROME_145("Chrome 145 (Reality)", "Chrome"),
    SNI_REALITY_FIREFOX_151("Firefox 151 (Reality)", "Firefox"),
    SNI_REALITY_FIREFOX_150("Firefox 150 (Reality)", "Firefox"),
    SNI_REALITY_FIREFOX_149("Firefox 149 (Reality)", "Firefox"),
    SNI_REALITY_YANDEX_26_4("Яндекс Браузер 26.4 (Reality)", "Яндекс"),
    SNI_REALITY_YANDEX_26_3("Яндекс Браузер 26.3 (Reality)", "Яндекс"),
    SNI_REALITY_YANDEX_25("Яндекс Браузер 25 (Reality)", "Яндекс"),
    SNI_REALITY_YANDEX_24("Яндекс Браузер 24 (Reality)", "Яндекс"),
    SNI_REALITY_SAFARI_26_5("Safari 26.5 (Reality)", "Safari"),
    SNI_REALITY_SAFARI_26_4("Safari 26.4 (Reality)", "Safari");

    fun nativeStrategyName(): String = when (this) {
        SNI -> "SNI"
        // Verified against prebuilt libfptn_native_lib.so: Chrome 145/146/149 are compiled in,
        // while 147/148 are absent and would silently fall back to plain SNI in C++.
        SNI_REALITY_CHROME_148 -> "SNI-REALITY-CHROME-149"
        SNI_REALITY_CHROME_147 -> "SNI-REALITY-CHROME-146"
        else -> name.replace('_', '-')
    }
}

enum class SlidingTunnelMode(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
    val nativeStrategyName: String,
) {
    ROLLING_TUNNEL(
        title = "Скользящий туннель",
        shortTitle = "Одиночный",
        subtitle = "Один канал с фоновым обновлением сессии",
        nativeStrategyName = "rolling-tunnel",
    ),
    DUAL_TUNNEL(
        title = "Двойной скользящий туннель",
        shortTitle = "Двойной",
        subtitle = "Два параллельных канала для стабильности",
        nativeStrategyName = "dual-rolling-tunnel",
    ),
    TRIPLE_TUNNEL(
        title = "Тройной скользящий туннель",
        shortTitle = "Тройной",
        subtitle = "Три канала для нестабильных мобильных сетей",
        nativeStrategyName = "triple-rolling-tunnel",
    ),
}

enum class SplitTunnelMode(val title: String, val subtitle: String) {
    ALL_APPS("Все приложения", "Весь трафик (кроме РФ-исключений) идёт через VPN"),
    EXCLUDE_SELECTED("Исключить выбранные", "Выбранные приложения работают напрямую"),
    INCLUDE_SELECTED("Только выбранные", "Через VPN работают только отмеченные приложения"),
}

data class SniScanResult(
    val domain: String,
    val rttMs: Int?,
    val reachable: Boolean,
)

data class SniScanProgress(
    val isScanning: Boolean = false,
    val checkedCount: Int = 0,
    val totalCount: Int = 0,
    val currentDomain: String = "",
    val foundDomain: String? = null,
)
