package com.example.domain.routing

import com.example.domain.model.BypassRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress

enum class RouteDecision {
    VPN_TUNNEL,
    DIRECT_RF,
    DIRECT_LOCAL,
}

data class CidrBlock(
    val networkAddress: Long,
    val prefixLength: Int,
) {
    init {
        require(prefixLength in 0..32) { "Invalid IPv4 prefix length: $prefixLength" }
    }

    val mask: Long = if (prefixLength == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
    val startIp: Long = networkAddress and mask
    val endIp: Long = startIp or (mask.inv() and 0xFFFFFFFFL)

    fun contains(ip: Long): Boolean = (ip and mask) == startIp

    fun toCidrString(): String = "${formatIpv4(startIp)}/$prefixLength"

    fun addressString(): String = formatIpv4(startIp)

    companion object {
        fun parse(cidr: String): CidrBlock {
            val parts = cidr.trim().split("/")
            require(parts.size == 2) { "Invalid CIDR: $cidr" }
            val ip = parseIpv4(parts[0]) ?: throw IllegalArgumentException("Invalid IPv4: ${parts[0]}")
            val prefix = parts[1].toInt()
            return CidrBlock(ip, prefix)
        }

        fun parseIpv4(ip: String): Long? {
            val parts = ip.trim().split(".")
            if (parts.size != 4) return null
            var result = 0L
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                result = (result shl 8) or octet.toLong()
            }
            return result
        }

        fun formatIpv4(ip: Long): String {
            return "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
        }
    }
}

/**
 * Smart Regional & Custom Domain routing engine (v1.8):
 * - Enforces regional direct bypass for Russia, China, Iran, Vietnam (TLDs, domestic domains, GeoIP CIDRs).
 * - Strictly guarantees Telegram Data Center subnets and domains ALWAYS pass via VPN.
 * - Strictly prevents adding `.ru`/`.рф`/`.su`, FPTN/RU protected services, active region's bypass domains,
 *   or regional GeoIP-resolved domains to Force-VPN unless live-verified Root unlock (`allowOverrideRu`) is ON.
 * - Disabling regional bypass (`BypassRegion.NONE` or `smartRfEnabled = false`) also requires `allowOverrideRu`.
 */
object SmartRfRouter {

    /**
     * Mandatory Telegram DC IPv4 subnets that MUST NEVER bypass the VPN tunnel.
     */
    val telegramDcCidrs: List<CidrBlock> = listOf(
        "91.108.0.0/16",
        "91.105.192.0/23",
        "95.161.64.0/20",
        "149.154.160.0/20",
        "185.76.151.0/24",
    ).map(CidrBlock::parse)

    val telegramDomains: Set<String> = setOf(
        "telegram.org",
        "telegram.me",
        "t.me",
        "tdesktop.com",
        "telegra.ph",
        "telesco.pe",
        "cdn-telegram.org",
        "fragment.com",
    )

    val localBypassCidrs: List<CidrBlock> = listOf(
        "127.0.0.0/8",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "224.0.0.0/4",
    ).map(CidrBlock::parse)

    /**
     * Exact official FPTN reserved IPv4 subnets (matching ConnectionSubnets.java) plus loopback/link-local/multicast.
     * Excludes 10.10.0.0/16 (TUN), 172.16.0.0/12 (FPTN server NAT), and 192.168.0.0/16 (LAN/Wi-Fi router)
     * so local devices & FPTN server internal NAT never collide with VPN routes.
     */
    val officialReservedIpv4Cidrs: List<CidrBlock> = listOf(
        "10.10.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "224.0.0.0/4",
    ).map(CidrBlock::parse)

    /**
     * Exact official FPTN reserved IPv6 subnets (matching ConnectionSubnets.java):
     * local TUN (fd00::/64), FPTN server NAT (fc00:1::/64), and link-local (fe80::/10).
     */
    val officialReservedIpv6Cidrs: List<Pair<String, Int>> = listOf(
        "fd00::" to 64,
        "fc00:1::" to 64,
        "fe80::" to 10,
    )

    /**
     * Russian TLD suffixes and Punycode equivalent for .рф (xn--p1ai).
     */
    val russianTlds: Set<String> = setOf(".ru", ".su", ".рф", ".xn--p1ai")

    /**
     * Protected RU & FPTN compliance service roots that MUST NOT be forced into VPN without verified Root unlock.
     */
    val protectedFptnAndRuDomainRoots: Set<String> = BypassRegion.RUSSIA.directDomainRoots + setOf(
        "fptn.org",
        "batch.yandex.net",
        "vk-portal.net",
        "vkuser.net",
        "vkuseraudio.net",
        "vkuserlive.net",
        "vkuservideo.net",
        "sberdevices.ru",
        "sbermarket.ru",
        "kuper.ru",
        "magnit.ru",
        "perekrestok.ru",
        "pyaterochka.ru",
        "lenta.com",
        "wildberries.by",
        "ozon.by",
    )

    /**
     * Comprehensive RF GeoIP allocations covering Yandex (+ Yandex Cloud), VK / Mail.ru / OK / VK Cloud,
     * Sberbank / Cloud.ru, Gosuslugi / Rostelecom, T-Bank, VTB, Alfa-Bank, Ozon, Wildberries, Avito,
     * Selectel, Reg.ru, Timeweb, Beget, DDoS-Guard, Qrator, MTS, Megafon, Beeline, and major RU hosting.
     * Strictly verified to have ZERO overlap with [telegramDcCidrs].
     */
    val russianBypassCidrs: List<CidrBlock> = listOf(
        // Yandex Core & Yandex Cloud (AS13238, AS200350)
        "5.45.192.0/18",
        "5.255.192.0/18",
        "37.9.64.0/18",
        "37.140.128.0/18",
        "51.250.0.0/16",
        "77.88.0.0/18",
        "84.201.128.0/18",
        "87.250.224.0/19",
        "93.158.128.0/18",
        "95.108.128.0/17",
        "130.193.32.0/19",
        "141.8.128.0/18",
        "158.160.0.0/16",
        "178.154.128.0/17",
        "213.180.192.0/19",
        // VK / Mail.ru / OK / VK Cloud (AS47764, AS47541, AS47542)
        "79.137.128.0/17",
        "87.240.128.0/18",
        "94.100.176.0/20",
        "95.163.0.0/16",
        "95.213.0.0/16",
        "128.140.168.0/21",
        "185.16.148.0/22",
        "188.93.56.0/21",
        "195.211.20.0/22",
        "217.20.144.0/20",
        "217.69.128.0/20",
        // Sberbank / SberCloud / Cloud.ru (AS35237, AS202611)
        "84.252.128.0/18",
        "176.108.240.0/20",
        "185.157.96.0/22",
        "194.54.12.0/22",
        "194.186.0.0/16",
        "195.43.144.0/22",
        "213.24.128.0/18",
        // Gosuslugi / Rostelecom / State Services / Nalog / Mos.ru (AS12389)
        "46.61.128.0/17",
        "77.37.128.0/17",
        "95.167.0.0/16",
        "109.207.0.0/16",
        "188.254.0.0/16",
        "194.84.0.0/16",
        "195.208.0.0/16",
        "212.11.128.0/18",
        "213.59.0.0/16",
        // T-Bank (Tinkoff), VTB, Alfa-Bank, Gazprombank, NSPK / SBP
        "91.194.226.0/23",
        "91.213.144.0/22",
        "178.248.232.0/21",
        "185.15.208.0/22",
        "193.0.200.0/22",
        "193.104.76.0/22",
        "193.200.10.0/23",
        "195.14.32.0/19",
        "212.44.128.0/19",
        "217.12.96.0/20",
        // Ozon, Wildberries, Avito, Retail & Classifieds
        "45.134.12.0/22",
        "46.17.40.0/21",
        "89.208.192.0/18",
        "185.71.64.0/22",
        "185.178.208.0/22",
        "185.215.4.0/22",
        "193.32.16.0/20",
        "195.34.32.0/19",
        // Major RU Hosting, Cloud & Anti-DDoS CDNs (Selectel, Reg.ru, Timeweb, Beget, DDoS-Guard, Qrator, MTS/Megafon/Beeline)
        "5.101.152.0/21",
        "31.31.196.0/22",
        "37.140.192.0/21",
        "45.130.40.0/22",
        "62.109.0.0/19",
        "77.222.40.0/21",
        "81.177.0.0/16",
        "87.236.16.0/21",
        "90.156.128.0/17",
        "92.53.96.0/20",
        "176.59.0.0/16",
        "185.84.108.0/22",
        "185.104.248.0/22",
        "185.129.100.0/22",
        "188.120.224.0/19",
        "188.225.0.0/17",
        "193.106.168.0/22",
        "194.58.88.0/21",
        "195.210.46.0/23",
        "212.193.96.0/19",
        "213.189.192.0/19",
        "217.16.16.0/20",
    ).distinct().map(CidrBlock::parse)

    /**
     * Major mainland China ISP / domestic cloud GeoIP blocks (China Telecom, Unicom, Mobile, Alibaba, Tencent, Baidu)
     * — none overlap Telegram DC subnets.
     */
    val chinaBypassCidrs: List<CidrBlock> = listOf(
        "1.0.1.0/24",
        "14.0.0.0/11",
        "27.0.0.0/11",
        "36.0.0.0/10",
        "42.80.0.0/13",
        "47.92.0.0/14",
        "58.14.0.0/15",
        "59.32.0.0/12",
        "60.0.0.0/11",
        "61.128.0.0/10",
        "101.0.0.0/10",
        "106.0.0.0/10",
        "110.0.0.0/9",
        "112.0.0.0/9",
        "114.0.0.0/9",
        "116.0.0.0/9",
        "118.0.0.0/9",
        "120.0.0.0/9",
        "122.0.0.0/9",
        "124.0.0.0/10",
        "180.148.0.0/14",
        "182.0.0.0/10",
        "183.0.0.0/10",
        "218.0.0.0/10",
        "220.160.0.0/11",
        "221.0.0.0/10",
        "222.0.0.0/10",
        "223.64.0.0/10",
    ).map(CidrBlock::parse)

    /**
     * Major Iran domestic ISP / NIN GeoIP blocks (TCI, MCI, Irancell, Shatel, ParsOnline, Arvan, Shaparak)
     * — none overlap Telegram DC subnets.
     */
    val iranBypassCidrs: List<CidrBlock> = listOf(
        "2.144.0.0/14",
        "2.176.0.0/12",
        "5.22.0.0/17",
        "5.52.0.0/14",
        "5.112.0.0/12",
        "5.200.0.0/14",
        "31.2.128.0/17",
        "31.56.0.0/14",
        "37.98.0.0/15",
        "37.156.0.0/16",
        "37.254.0.0/15",
        "46.32.0.0/17",
        "46.100.0.0/15",
        "46.209.0.0/16",
        "78.38.0.0/15",
        "79.127.0.0/17",
        "80.191.0.0/16",
        "85.15.0.0/17",
        "89.165.0.0/16",
        "91.98.0.0/15",
        "94.182.0.0/15",
        "151.232.0.0/13",
        "185.143.232.0/22",
        "188.158.0.0/15",
        "217.218.0.0/15",
    ).map(CidrBlock::parse)

    /**
     * Major Vietnam ISP GeoIP blocks (VNPT, Viettel, FPT Telecom) — none overlap Telegram DC subnets.
     */
    val vietnamBypassCidrs: List<CidrBlock> = listOf(
        "14.160.0.0/11",
        "27.64.0.0/12",
        "42.112.0.0/12",
        "58.186.0.0/15",
        "103.1.236.0/22",
        "113.160.0.0/11",
        "115.72.0.0/13",
        "116.96.0.0/12",
        "117.0.0.0/13",
        "118.68.0.0/14",
        "123.16.0.0/12",
        "125.212.128.0/17",
        "171.224.0.0/11",
        "203.113.128.0/18",
        "210.245.0.0/16",
        "222.252.0.0/14",
    ).map(CidrBlock::parse)

    fun cidrsForRegion(region: BypassRegion): List<CidrBlock> = when (region) {
        BypassRegion.RUSSIA -> russianBypassCidrs
        BypassRegion.CHINA -> chinaBypassCidrs
        BypassRegion.IRAN -> iranBypassCidrs
        BypassRegion.VIETNAM -> vietnamBypassCidrs
        BypassRegion.NONE -> emptyList()
    }

    fun normalizeDomainRule(raw: String): String? {
        val cleaned = raw.trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore("/")
            .substringBefore(":")
            .trimStart('.', '*')
            .trimEnd('.')
        if (cleaned.isEmpty() || !cleaned.contains('.') || cleaned.contains(' ')) return null
        return cleaned
    }

    private fun matchesDomainSet(domain: String, rules: Set<String>): Boolean {
        return rules.any { rawRule ->
            val rule = rawRule.trim().lowercase().trimStart('.', '*').trimEnd('.')
            rule.isNotEmpty() && (domain == rule || domain.endsWith(".$rule"))
        }
    }

    /**
     * Returns true if [domain] belongs to protected RU/FPTN domains or the active [bypassRegion]'s
     * mandatory direct zones (excluding Telegram, which is always allowed via VPN).
     */
    fun isProtectedDomainForVpnOverride(
        domain: String,
        bypassRegion: BypassRegion = BypassRegion.RUSSIA,
    ): Boolean {
        val normalized = normalizeDomainRule(domain) ?: domain.trim().lowercase().trimEnd('.')
        if (normalized.isEmpty() || isTelegramDomain(normalized)) return false

        // Always protect Russian TLDs & FPTN/RU core service domains
        if (russianTlds.any { normalized.endsWith(it) }) return true
        if (matchesDomainSet(normalized, protectedFptnAndRuDomainRoots)) return true

        // Also protect active region's TLDs & domestic service roots
        if (bypassRegion != BypassRegion.NONE) {
            if (bypassRegion.tlds.any { normalized.endsWith(it) }) return true
            if (matchesDomainSet(normalized, bypassRegion.directDomainRoots)) return true
        }
        return false
    }

    /**
     * Checks whether adding [rawDomain] to Force-VPN (`customVpnDomains`) is blocked.
     * RU/FPTN server protection is PERMANENTLY locked (even with Root) so servers are never exposed to abuse/blocking.
     * Also checks resolved IPv4 addresses against Russian & active regional GeoIP CIDRs!
     * Returns null if allowed, or a localized Russian error message explaining why it is blocked.
     */
    @Suppress("UNUSED_PARAMETER")
    fun checkDomainVpnOverrideError(
        rawDomain: String,
        bypassRegion: BypassRegion = BypassRegion.RUSSIA,
        resolvedIpv4s: Set<String> = emptySet(),
        allowRootOverride: Boolean = false,
    ): String? {
        val normalized = normalizeDomainRule(rawDomain)
            ?: return "Введите корректный домен, например example.com"
        if (isTelegramDomain(normalized)) return null

        if (russianTlds.any { normalized.endsWith(it) }) {
            return "Домен $normalized (.ru/.рф/.su) защищён постоянно (включая Root-режим) для сохранности серверов FPTN."
        }
        if (matchesDomainSet(normalized, protectedFptnAndRuDomainRoots)) {
            return "Домен $normalized входит в постоянный защищённый список РФ/FPTN и всегда идёт напрямую (даже с Root)."
        }
        val activeRegion = BypassRegion.sanitize(bypassRegion)
        if (activeRegion.tlds.any { normalized.endsWith(it) } ||
            matchesDomainSet(normalized, activeRegion.directDomainRoots)
        ) {
            return "Домен $normalized относится к защищённой региональной зоне (${activeRegion.title}) и всегда идёт напрямую."
        }

        // Check resolved GeoIP addresses against RU + active region bypass CIDRs (excluding Telegram DCs)
        val geoCidrs = (russianBypassCidrs + cidrsForRegion(activeRegion)).distinct()
        val matchedIp = resolvedIpv4s.firstOrNull { ipStr ->
            val ip = CidrBlock.parseIpv4(ipStr) ?: return@firstOrNull false
            !telegramDcCidrs.any { it.contains(ip) } && geoCidrs.any { it.contains(ip) }
        }
        if (matchedIp != null) {
            return "Домен $normalized указывает на защищённый РФ/региональный GeoIP ($matchedIp) и не может идти через VPN (даже с Root)."
        }
        return null
    }

    /**
     * Regional protection is permanently active (even with Root): [BypassRegion.NONE] and
     * `smartRfEnabled = false` always normalize back to [BypassRegion.RUSSIA].
     */
    @Suppress("UNUSED_PARAMETER")
    fun effectiveRegion(
        smartRfEnabled: Boolean,
        bypassRegion: BypassRegion,
        allowOverrideRu: Boolean = false,
    ): BypassRegion = BypassRegion.sanitize(bypassRegion)

    fun decideForDomain(
        domain: String,
        smartRfEnabled: Boolean,
        bypassRegion: BypassRegion = BypassRegion.RUSSIA,
        customDirectDomains: Set<String> = emptySet(),
        customVpnDomains: Set<String> = emptySet(),
        allowOverrideRu: Boolean = false,
    ): RouteDecision {
        val normalized = domain.trim().lowercase().trimEnd('.')
        if (normalized.isEmpty()) return RouteDecision.VPN_TUNNEL

        // 1. Telegram domains ALWAYS go via VPN
        if (isTelegramDomain(normalized)) {
            return RouteDecision.VPN_TUNNEL
        }

        // 2. Local network hosts never enter external tunnel
        if (normalized == "localhost" || normalized.endsWith(".local") || normalized.endsWith(".lan")) {
            return RouteDecision.DIRECT_LOCAL
        }

        val activeRegion = effectiveRegion(smartRfEnabled, bypassRegion, allowOverrideRu = false)
        val isProtected = isProtectedDomainForVpnOverride(normalized, activeRegion)

        // 3. Protected RU/FPTN and regional bypass domains are ALWAYS direct (even with Root)
        if (isProtected) {
            return RouteDecision.DIRECT_RF
        }

        // 4. Explicit user Force-VPN domains (only non-protected domains)
        if (matchesDomainSet(normalized, customVpnDomains)) {
            return RouteDecision.VPN_TUNNEL
        }

        // 5. Explicit user Direct-Bypass domains
        if (matchesDomainSet(normalized, customDirectDomains)) {
            return RouteDecision.DIRECT_RF
        }

        // 6. Regional Smart Bypass (Russia / China / Iran / Vietnam)
        if (activeRegion.tlds.any { normalized.endsWith(it) } ||
            matchesDomainSet(normalized, activeRegion.directDomainRoots)
        ) {
            return RouteDecision.DIRECT_RF
        }

        return RouteDecision.VPN_TUNNEL
    }

    fun decideForIpv4(
        ipAddress: String,
        smartRfEnabled: Boolean,
        bypassRegion: BypassRegion = BypassRegion.RUSSIA,
        allowOverrideRu: Boolean = false,
    ): RouteDecision {
        val ip = CidrBlock.parseIpv4(ipAddress) ?: return RouteDecision.VPN_TUNNEL
        return decideForIpv4Long(ip, smartRfEnabled, bypassRegion, allowOverrideRu)
    }

    @Suppress("UNUSED_PARAMETER")
    fun decideForIpv4Long(
        ip: Long,
        smartRfEnabled: Boolean,
        bypassRegion: BypassRegion = BypassRegion.RUSSIA,
        allowOverrideRu: Boolean = false,
    ): RouteDecision {
        // 1. Telegram DC subnets ALWAYS go through the VPN tunnel.
        if (telegramDcCidrs.any { it.contains(ip) }) {
            return RouteDecision.VPN_TUNNEL
        }

        // 2. Local RFC1918 & loopback never enter external tunnel.
        if (localBypassCidrs.any { it.contains(ip) }) {
            return RouteDecision.DIRECT_LOCAL
        }

        // 3. Regional GeoIP bypass (RU protection is permanently enforced even with Root)
        val activeRegion = effectiveRegion(smartRfEnabled, bypassRegion, allowOverrideRu = false)
        if (cidrsForRegion(activeRegion).any { it.contains(ip) }) {
            return RouteDecision.DIRECT_RF
        }
        if (russianBypassCidrs.any { it.contains(ip) }) {
            return RouteDecision.DIRECT_RF
        }

        return RouteDecision.VPN_TUNNEL
    }

    fun isTelegramIp(ipAddress: String): Boolean {
        val ip = CidrBlock.parseIpv4(ipAddress) ?: return false
        return telegramDcCidrs.any { it.contains(ip) }
    }

    fun isTelegramDomain(domain: String): Boolean {
        val normalized = domain.trim().lowercase().trimEnd('.')
        return telegramDomains.any { tg -> normalized == tg || normalized.endsWith(".$tg") }
    }

    /**
     * Computes exact IPv4 routes to install into VpnService.Builder:
     * Starts from 0.0.0.0/0, subtracts active regional GeoIP bypass CIDRs,
     * server IP, and user custom direct IPs, then explicitly re-ensures all Telegram DC CIDRs
     * and permitted user Force-VPN IPs are routed through VPN with longest-prefix priority.
     */
    @Suppress("UNUSED_PARAMETER")
    fun computeEffectiveVpnRoutes(
        smartRfEnabled: Boolean,
        vpnServerIpv4: String? = null,
        bypassRegion: BypassRegion = BypassRegion.RUSSIA,
        extraDirectIpv4s: Set<String> = emptySet(),
        extraVpnIpv4s: Set<String> = emptySet(),
        allowOverrideRu: Boolean = false,
        vpnDnsIpv4s: Set<String> = emptySet(),
    ): List<CidrBlock> {
        val activeRegion = effectiveRegion(smartRfEnabled, bypassRegion, allowOverrideRu = false)
        val regionCidrs = cidrsForRegion(activeRegion)
        // RU & active region GeoIP protection is permanently enforced even with Root
        val protectedGeoCidrs = (russianBypassCidrs + regionCidrs).distinct()

        val excludes = mutableListOf<CidrBlock>()
        // Always exclude official FPTN reserved subnets (LAN, TUN, server NAT) to prevent routing conflicts.
        excludes.addAll(officialReservedIpv4Cidrs)
        if (vpnServerIpv4 != null) {
            CidrBlock.parseIpv4(vpnServerIpv4)?.let { serverIp ->
                excludes.add(CidrBlock(serverIp, 32))
            }
        }
        excludes.addAll(protectedGeoCidrs)
        extraDirectIpv4s.forEach { ipStr ->
            val ip = CidrBlock.parseIpv4(ipStr) ?: return@forEach
            if (!telegramDcCidrs.any { it.contains(ip) } && ipStr != vpnServerIpv4) {
                excludes.add(CidrBlock(ip, 32))
            }
        }

        val routed = subtractCidrs(
            base = listOf(CidrBlock(0L, 0)),
            excludes = excludes.distinct(),
        ).toMutableList()

        // Always append explicit /32 routes for configured VPN DNS servers (critical when server DNS is in 172.16.0.0/12 or 10.10.0.0/16!)
        vpnDnsIpv4s.forEach { dnsIp ->
            if (dnsIp != vpnServerIpv4) {
                CidrBlock.parseIpv4(dnsIp)?.let { ip ->
                    routed.add(CidrBlock(ip, 32))
                }
            }
        }

        // Always append explicit Telegram DC routes to guarantee longest-prefix tunnel routing.
        routed.addAll(telegramDcCidrs)

        // Append user Force-VPN IPv4s with /32 longest-prefix priority (never routing VPN server IP or protected RU/regional GeoIP)
        extraVpnIpv4s.forEach { ipStr ->
            if (ipStr != vpnServerIpv4) {
                CidrBlock.parseIpv4(ipStr)?.let { ip ->
                    val isBlockedGeo = !telegramDcCidrs.any { it.contains(ip) } &&
                        protectedGeoCidrs.any { it.contains(ip) }
                    if (!isBlockedGeo) {
                        routed.add(CidrBlock(ip, 32))
                    }
                }
            }
        }
        return routed
    }

    /**
     * Expands user/regional domain rules with `www.` variants so entering `example.ru`
     * also excludes `www.example.ru` when browsers redirect.
     */
    fun expandDomainVariants(domains: Set<String>, includeWww: Boolean = true): List<String> {
        val result = linkedSetOf<String>()
        domains.forEach { raw ->
            val clean = normalizeDomainRule(raw) ?: return@forEach
            result.add(clean)
            if (includeWww && !clean.startsWith("www.") && clean.count { it == '.' } == 1) {
                result.add("www.$clean")
            }
        }
        return result.toList()
    }

    fun resolveDomainsToIpv4s(domains: Set<String>, maxDomains: Int = 24): Set<String> {
        if (domains.isEmpty()) return emptySet()
        val resolved = linkedSetOf<String>()
        expandDomainVariants(domains, includeWww = true).take(maxDomains).forEach { clean ->
            try {
                InetAddress.getAllByName(clean)
                    .filterIsInstance<Inet4Address>()
                    .take(4)
                    .forEach { addr ->
                        addr.hostAddress?.let { ip ->
                            if (CidrBlock.parseIpv4(ip) != null) resolved.add(ip)
                        }
                    }
            } catch (_: Exception) {
                // Ignore unresolvable domains gracefully
            }
        }
        return resolved
    }

    /**
     * Fast bounded parallel DNS resolution for pre-populating direct/VPN routes on tunnel startup.
     * Supports injecting a physical-network resolver (`network.getAllByName`) so Russian/local GeoDNS
     * returns true local IPv4s before the TUN interface activates.
     */
    suspend fun resolveDomainsToIpv4sParallel(
        domains: Set<String>,
        includeWww: Boolean = true,
        maxDomains: Int = 96,
        totalTimeoutMs: Long = 1_600L,
        maxConcurrency: Int = 16,
        hostResolver: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    ): Set<String> {
        if (domains.isEmpty()) return emptySet()
        val targets = expandDomainVariants(domains, includeWww = includeWww).take(maxDomains)
        if (targets.isEmpty()) return emptySet()

        val resolved = java.util.Collections.synchronizedSet(linkedSetOf<String>())
        val semaphore = Semaphore(maxConcurrency.coerceIn(1, 32))
        withTimeoutOrNull(totalTimeoutMs) {
            coroutineScope {
                targets.map { host ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                hostResolver(host)
                                    .filterIsInstance<Inet4Address>()
                                    .take(4)
                                    .forEach { addr ->
                                        addr.hostAddress?.let { ip ->
                                            if (CidrBlock.parseIpv4(ip) != null) resolved.add(ip)
                                        }
                                    }
                            } catch (_: Exception) {
                                // Ignore individual DNS timeouts/NXDOMAIN
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        return synchronized(resolved) { resolved.toSet() }
    }

    /**
     * Exact binary prefix subtraction on IPv4 CIDR space.
     */
    fun subtractCidrs(base: List<CidrBlock>, excludes: List<CidrBlock>): List<CidrBlock> {
        var current = base
        for (exclude in excludes) {
            val next = mutableListOf<CidrBlock>()
            for (block in current) {
                next.addAll(subtractSingle(block, exclude))
            }
            current = next
        }
        return current
    }

    private fun subtractSingle(block: CidrBlock, exclude: CidrBlock): List<CidrBlock> {
        if (exclude.endIp < block.startIp || exclude.startIp > block.endIp) {
            return listOf(block)
        }
        if (exclude.startIp <= block.startIp && exclude.endIp >= block.endIp) {
            return emptyList()
        }
        if (block.prefixLength >= 32) {
            return emptyList()
        }

        val nextPrefix = block.prefixLength + 1
        val bit = 1L shl (32 - nextPrefix)
        val left = CidrBlock(block.startIp, nextPrefix)
        val right = CidrBlock(block.startIp or bit, nextPrefix)
        return subtractSingle(left, exclude) + subtractSingle(right, exclude)
    }

    fun resolveHostToIpv4(host: String): String? {
        if (CidrBlock.parseIpv4(host) != null) return host
        return try {
            InetAddress.getAllByName(host)
                .firstOrNull { it is Inet4Address }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
