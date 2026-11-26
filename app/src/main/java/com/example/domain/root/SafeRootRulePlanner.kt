package com.example.domain.root

import com.example.domain.routing.CidrBlock
import com.example.domain.routing.SmartRfRouter

/**
 * Generates strictly isolated Netfilter / policy-routing scripts for Stealth Safe Root Mode
 * (Magisk / KernelSU / APatch + kernel TUN fptn0 without Android VpnService):
 *
 * Safety & Stealth invariants:
 * 1. Uses ONLY dedicated custom chains:
 *    - mangle: FPTN_OUTPUT / FPTN_PREROUTING
 *    - nat:    FPTN_POSTROUTING (SNAT -> 10.10.0.1 on fptn0) / FPTN_DNS_NAT (UDP/53 -> tunnel DNS)
 * 2. Policy rule at pref 12600 (after AOSP RULE_PRIORITY_SECURE_VPN 12000, before default routes).
 * 3. NEVER modifies system netd chains, NEVER installs global ip6tables DROP on wlan+, NEVER alters rp_filter.
 * 4. Always bypasses our own app UID (--uid-owner), uid 0 (root), AID_NETWORK_STACK (1073), VPN server IP, loopback, and RFC1918 LAN.
 * 5. Forces Telegram DC CIDRs through the tunnel mark BEFORE any Smart RF bypass rules.
 * 6. Synchronous idempotent cleanup tears down all custom chains, ip rule, table 198, and fptn0.
 */
object SafeRootRulePlanner {

    const val CHAIN_OUTPUT = "FPTN_OUTPUT"
    const val CHAIN_PREROUTING = "FPTN_PREROUTING"
    const val CHAIN_POSTROUTING = "FPTN_POSTROUTING"
    const val CHAIN_POSTROUTING_MSS = "FPTN_MSS"
    const val CHAIN_DNS_NAT = "FPTN_DNS_NAT"
    const val ROUTING_TABLE_ID = 198
    /**
     * Upper-byte fwmark (bits 24..31 = 0x46000000/0xFF000000).
     * Preserves AOSP Fwmark lower 24 bits (netId 0..15, explicitlySelected bit 16,
     * protectedFromVpn bit 17 = 0x20000, permission bits 18..19) so Google Play Services,
     * NetworkStack, and secondary non-Root VpnService apps NEVER break or loop!
     */
    const val FWMARK_HEX = "0x46000000"
    const val FWMARK_MASK = "0x46000000/0xFF000000"
    const val LEGACY_FWMARK_HEX = "0x4650"

    /**
     * Policy routing priority 12600: strictly AFTER AOSP RULE_PRIORITY_SECURE_VPN (12000) &
     * RULE_PRIORITY_PROHIBIT_NON_VPN (12500), and strictly BEFORE RULE_PRIORITY_EXPLICIT_NETWORK (13000)
     * & RULE_PRIORITY_DEFAULT_NETWORK (22000). Enables deadlock-free chaining with another VpnService!
     */
    const val RULE_PREF = 12600
    const val DEFAULT_TUN_NAME = "fptn0"
    const val DEFAULT_CLIENT_IPV4 = "10.10.0.1"

    /**
     * Linux `root` UID (0): bypassing uid 0 guarantees `netd`, kernel helpers, and Magisk/KernelSU
     * root daemons NEVER loop through the FPTN tunnel.
     */
    const val AID_ROOT = 0

    /**
     * Android `AID_NETWORK_STACK` (1073): runs `NetworkMonitor` HTTP 204 / DNS connectivity validation
     * on Google Pixel & AOSP Android 10–16. Bypassing this UID prevents false "!" ("No Internet")
     * indicators and physical network demotion during Wi-Fi <-> Cellular / cell-tower handovers.
     */
    const val AID_NETWORK_STACK = 1073
    val DEFAULT_SYSTEM_BYPASS_UIDS: Set<Int> = setOf(AID_ROOT, AID_NETWORK_STACK)

    /**
     * Android AOSP Fwmark `protectedFromVpn` bit (bit 17 = 0x20000).
     * Marking FPTN's own UID sockets with 0x20000/0x20000 makes them bypass any secondary non-Root
     * Android `VpnService` (`tun0` at netd pref 12000), allowing seamless VPN chaining:
     * `Apps -> Other non-Root VPN (tun0) -> FPTN Stealth Root (fptn0) -> Internet` with ZERO deadlock!
     */
    const val ANDROID_PROTECTED_FROM_VPN_MARK = "0x20000/0x20000"

    val PRESET_TUN_NAMES = listOf("fptn0", "fptn1", "rmnet_fptn0", "wg_fptn0")
    private val SAFE_TUN_REGEX = Regex("^[a-z][a-z0-9_]{1,11}$")

    fun sanitizeTunName(raw: String?): String {
        val candidate = raw?.trim()?.lowercase().orEmpty()
        if (candidate.isBlank()) return DEFAULT_TUN_NAME
        // Never collide with Android's own VpnService `tun0..tun9` naming!
        if (candidate.startsWith("tun") && candidate.length <= 5) return DEFAULT_TUN_NAME
        return if (SAFE_TUN_REGEX.matches(candidate)) candidate else DEFAULT_TUN_NAME
    }

    private val FORBIDDEN_PATTERNS = listOf(
        "rp_filter",
        "ip6tables",
        "oem_out",
        "bw_",
        "st_",
        "netd",
        "wlan+",
    )

    fun buildApplyCommands(
        appUid: Int,
        vpnServerIpv4: String,
        tunInterfaceName: String = DEFAULT_TUN_NAME,
        clientTunIpv4: String = DEFAULT_CLIENT_IPV4,
        dnsServerIpv4: String = "1.1.1.1",
        smartRfEnabled: Boolean,
        excludedUids: Set<Int> = emptySet(),
        systemBypassUids: Set<Int> = DEFAULT_SYSTEM_BYPASS_UIDS,
        allowSecondaryVpnChaining: Boolean = true,
        bypassCidrs: List<CidrBlock> = SmartRfRouter.russianBypassCidrs,
        extraDirectIpv4s: Set<String> = emptySet(),
        extraVpnIpv4s: Set<String> = emptySet(),
    ): List<String> {
        val safeTunName = sanitizeTunName(tunInterfaceName)
        val commands = mutableListOf<String>()
        val combinedBypassUids = (excludedUids + systemBypassUids)
            .filter { it >= 0 && it != appUid }
            .sorted()

        // Start from a clean slate (without destroying the freshly-opened kernel TUN interface).
        commands.addAll(buildRulesOnlyCleanupCommands())

        // 1. Dedicated isolated chains in mangle table.
        require(appUid >= 0) { "Invalid appUid: $appUid" }
        val safeServerIp = sanitizeIpv4OrNull(vpnServerIpv4)
            ?: throw IllegalArgumentException("Invalid vpnServerIpv4 for root routing: '$vpnServerIpv4'")
        val safeClientIp = sanitizeIpv4OrNull(clientTunIpv4) ?: DEFAULT_CLIENT_IPV4
        val safeDnsIp = sanitizeIpv4OrNull(dnsServerIpv4)

        commands.add("iptables -w -t mangle -N $CHAIN_OUTPUT")
        commands.add("iptables -w -t mangle -N $CHAIN_PREROUTING")
        commands.add("iptables -w -t mangle -N $CHAIN_POSTROUTING_MSS")

        // Mandatory bypass: our own VPN process UID to prevent routing loops.
        // Always set Android's `protectedFromVpn` Fwmark bit (0x20000/0x20000) on FPTN's upstream
        // packets when secondary VPN chaining is enabled so another VpnService (tun0) NEVER captures
        // FPTN's own BoringSSL connection!
        if (allowSecondaryVpnChaining) {
            commands.add(
                "iptables -w -t mangle -A $CHAIN_OUTPUT -m owner --uid-owner $appUid -j MARK --set-xmark $ANDROID_PROTECTED_FROM_VPN_MARK",
            )
        }
        commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -m owner --uid-owner $appUid -j RETURN")

        // Coexistence with secondary non-Root VpnService / WireGuard apps (tun+, wg+):
        // Regular app packets matched by AOSP pref 12000 exit via `tun+` untouched to the other VPN app,
        // while that other VPN app's `protect()`ed upstream socket (bit 0x20000 set, exiting wlan/rmnet)
        // receives upper-byte mark `0x46000000/0xFF000000` WITHOUT wiping bit 0x20000, so it skips
        // pref 12000 and hits our pref 12600 -> tunnels cleanly through `$safeTunName`!
        if (allowSecondaryVpnChaining) {
            commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -o tun+ -j RETURN")
            commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -o wg+ -j RETURN")
        }

        // Mandatory bypass: Android NetworkStack / CaptivePortal + Per-App excluded UIDs
        // (prevents Google Pixel "!" No-Internet badge & preserves 507-rule protected RU apps).
        for (uid in combinedBypassUids) {
            if (uid >= 0) {
                commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -m owner --uid-owner $uid -j RETURN")
            }
        }

        // Mandatory bypass: VPN server endpoint itself.
        commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -d $safeServerIp/32 -j RETURN")

        // CRITICAL: explicitly mark the tunnel DNS server BEFORE RFC1918 localCidrs bypass so
        // internal FPTN server DNS addresses (e.g. 172.20.0.1 or 10.10.0.2) ALWAYS enter $safeTunName!
        if (safeDnsIp != null && safeDnsIp != safeServerIp) {
            commands.add(
                "iptables -w -t mangle -A $CHAIN_OUTPUT -d $safeDnsIp/32 -j MARK --set-xmark $FWMARK_MASK",
            )
            commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -d $safeDnsIp/32 -j RETURN")
        }

        // Mandatory bypass: loopback, RFC1918 LAN, link-local, multicast, broadcast.
        val localCidrs = listOf(
            "127.0.0.0/8",
            "10.0.0.0/8",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "224.0.0.0/4",
            "255.255.255.255/32",
        )
        for (cidr in localCidrs) {
            commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -d $cidr -j RETURN")
            commands.add("iptables -w -t mangle -A $CHAIN_PREROUTING -d $cidr -j RETURN")
        }

        // Mandatory rule: Telegram DC subnets ALWAYS get tunnel fwmark before RF bypass.
        for (tgCidr in SmartRfRouter.telegramDcCidrs) {
            commands.add(
                "iptables -w -t mangle -A $CHAIN_OUTPUT -d ${tgCidr.toCidrString()} -j MARK --set-xmark $FWMARK_MASK",
            )
            commands.add(
                "iptables -w -t mangle -A $CHAIN_OUTPUT -d ${tgCidr.toCidrString()} -j RETURN",
            )
        }

        // Regional + mandatory RU bypass CIDRs (RU protection is permanently enforced even with Root).
        val effectiveBypassCidrs = (SmartRfRouter.russianBypassCidrs + if (smartRfEnabled) bypassCidrs else emptyList()).distinct()

        // Explicit user Force-VPN IPv4s (never overriding VPN server IP or protected RU/regional GeoIP)
        for (rawVpnIp in extraVpnIpv4s) {
            val safeVpnIp = sanitizeIpv4OrNull(rawVpnIp) ?: continue
            if (safeVpnIp == safeServerIp || safeVpnIp == safeDnsIp) continue
            val ipLong = CidrBlock.parseIpv4(safeVpnIp) ?: continue
            val isProtectedGeo = !SmartRfRouter.telegramDcCidrs.any { it.contains(ipLong) } &&
                effectiveBypassCidrs.any { it.contains(ipLong) }
            if (!isProtectedGeo) {
                commands.add(
                    "iptables -w -t mangle -A $CHAIN_OUTPUT -d $safeVpnIp/32 -j MARK --set-xmark $FWMARK_MASK",
                )
                commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -d $safeVpnIp/32 -j RETURN")
            }
        }

        // Smart RF & Regional bypass: return RU + regional CIDR blocks directly to native routing table.
        for (rfCidr in effectiveBypassCidrs) {
            commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -d ${rfCidr.toCidrString()} -j RETURN")
        }

        // Explicit Direct-Bypass IPv4s (pre-resolved core RU services + user customDirectDomains, excluding Telegram DCs)
        for (rawDirectIp in extraDirectIpv4s) {
            val safeDirectIp = sanitizeIpv4OrNull(rawDirectIp) ?: continue
            if (safeDirectIp == safeServerIp || safeDirectIp == safeDnsIp) continue
            val ipLong = CidrBlock.parseIpv4(safeDirectIp) ?: continue
            if (!SmartRfRouter.telegramDcCidrs.any { it.contains(ipLong) } &&
                !effectiveBypassCidrs.any { it.contains(ipLong) }
            ) {
                commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -d $safeDirectIp/32 -j RETURN")
            }
        }

        // Non-destructively set ONLY upper byte 0x46000000/0xFF000000 so AOSP netId & protectedFromVpn stay intact!
        commands.add("iptables -w -t mangle -A $CHAIN_OUTPUT -j MARK --set-xmark $FWMARK_MASK")

        // Clamp TCP SYN MSS to $safeTunName PMTU so large images/media/Play Store payloads never blackhole on 1360/1400 MTU!
        commands.add(
            "iptables -w -t mangle -A $CHAIN_POSTROUTING_MSS -o $safeTunName -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --clamp-mss-to-pmtu",
        )

        // Attach our isolated chains to OUTPUT / PREROUTING / POSTROUTING.
        commands.add("iptables -w -t mangle -A OUTPUT -j $CHAIN_OUTPUT")
        commands.add("iptables -w -t mangle -A PREROUTING -j $CHAIN_PREROUTING")
        commands.add("iptables -w -t mangle -A POSTROUTING -j $CHAIN_POSTROUTING_MSS")

        // 2. Dedicated isolated NAT chains:
        //    - FPTN_POSTROUTING: SNAT packets leaving $safeTunName to 10.10.0.1 so FPTN server accepts & routes replies back!
        //    - FPTN_DNS_NAT: redirect non-excluded UDP/53 + TCP/53 queries to tunnel DNS (eliminates Root-mode DNS leaks).
        commands.add("iptables -w -t nat -N $CHAIN_POSTROUTING")
        commands.add("iptables -w -t nat -A $CHAIN_POSTROUTING -o $safeTunName -j SNAT --to-source $safeClientIp")
        commands.add("iptables -w -t nat -A POSTROUTING -j $CHAIN_POSTROUTING")

        if (safeDnsIp != null) {
            commands.add("iptables -w -t nat -N $CHAIN_DNS_NAT")
            commands.add("iptables -w -t nat -A $CHAIN_DNS_NAT -m owner --uid-owner $appUid -j RETURN")
            if (allowSecondaryVpnChaining) {
                commands.add("iptables -w -t nat -A $CHAIN_DNS_NAT -o tun+ -j RETURN")
                commands.add("iptables -w -t nat -A $CHAIN_DNS_NAT -o wg+ -j RETURN")
            }
            for (uid in combinedBypassUids) {
                if (uid >= 0) {
                    commands.add("iptables -w -t nat -A $CHAIN_DNS_NAT -m owner --uid-owner $uid -j RETURN")
                }
            }
            commands.add("iptables -w -t nat -A $CHAIN_DNS_NAT -p udp --dport 53 -j DNAT --to-destination $safeDnsIp:53")
            commands.add("iptables -w -t nat -A $CHAIN_DNS_NAT -p tcp --dport 53 -j DNAT --to-destination $safeDnsIp:53")
            commands.add("iptables -w -t nat -A OUTPUT -j $CHAIN_DNS_NAT")
        }

        // 3. Install isolated policy routing table directing marked packets to $safeTunName at pref 12600.
        commands.add("ip route replace default dev $safeTunName src $safeClientIp table $ROUTING_TABLE_ID")
        commands.add("ip rule add fwmark $FWMARK_MASK table $ROUTING_TABLE_ID pref $RULE_PREF")
        commands.add("ip route flush cache 2>/dev/null || true")

        return commands
    }

    /**
     * Strictly validates an IPv4 literal (4 decimal octets 0..255, zero shell metacharacters).
     */
    fun sanitizeIpv4OrNull(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > 15) return null
        val parts = trimmed.split('.')
        if (parts.size != 4) return null
        val normalized = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            normalized[i] = v
        }
        return "${normalized[0]}.${normalized[1]}.${normalized[2]}.${normalized[3]}"
    }

    /**
     * Generates a single safe iptables command to dynamically bypass a newly DNS-resolved
     * `.ru` / custom-direct IPv4 address during an active Root session (inserted at top of FPTN_OUTPUT).
     * Returns null for invalid, Telegram, loopback/RFC1918, server, or DNS IPs.
     */
    fun buildDynamicDirectBypassCommand(
        ipv4: String,
        vpnServerIpv4: String? = null,
        dnsServerIpv4: String? = null,
    ): String? {
        val safeIp = sanitizeIpv4OrNull(ipv4) ?: return null
        if (safeIp == vpnServerIpv4 || safeIp == dnsServerIpv4) return null
        val ipLong = CidrBlock.parseIpv4(safeIp) ?: return null
        if (SmartRfRouter.telegramDcCidrs.any { it.contains(ipLong) }) return null
        if (SmartRfRouter.localBypassCidrs.any { it.contains(ipLong) }) return null
        return "iptables -w -t mangle -C $CHAIN_OUTPUT -d $safeIp/32 -j RETURN 2>/dev/null || " +
            "iptables -w -t mangle -I $CHAIN_OUTPUT 1 -d $safeIp/32 -j RETURN"
    }

    /**
     * Lightweight, zero-downtime route/MTU refresh executed after Wi-Fi <-> Cellular or cell-tower handovers
     * so Android's routing table updates never leave $tunInterfaceName stale or orphaned.
     */
    fun buildNetworkRefreshCommands(
        tunInterfaceName: String = DEFAULT_TUN_NAME,
        clientTunIpv4: String = DEFAULT_CLIENT_IPV4,
        mtu: Int = 1360,
    ): List<String> {
        val safeTunName = sanitizeTunName(tunInterfaceName)
        val safeClientIp = sanitizeIpv4OrNull(clientTunIpv4) ?: DEFAULT_CLIENT_IPV4
        return listOf(
            "ip link set dev $safeTunName mtu ${mtu.coerceIn(1280, 1500)} up 2>/dev/null || true",
            "ip route replace default dev $safeTunName src $safeClientIp table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule del fwmark $FWMARK_MASK table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule del fwmark $LEGACY_FWMARK_HEX table $ROUTING_TABLE_ID 2>/dev/null || true",
            "ip rule add fwmark $FWMARK_MASK table $ROUTING_TABLE_ID pref $RULE_PREF",
            "ip route flush cache 2>/dev/null || true",
        )
    }

    /**
     * Cleans up routing rules (both current masked fwmark and legacy 0x4650) and iptables chains
     * without deleting the TUN interface.
     */
    fun buildRulesOnlyCleanupCommands(): List<String> = listOf(
        "ip rule del fwmark $FWMARK_MASK table $ROUTING_TABLE_ID 2>/dev/null || true",
        "ip rule del fwmark $LEGACY_FWMARK_HEX table $ROUTING_TABLE_ID 2>/dev/null || true",
        "ip route flush table $ROUTING_TABLE_ID 2>/dev/null || true",
        "iptables -w -t mangle -D OUTPUT -j $CHAIN_OUTPUT 2>/dev/null || true",
        "iptables -w -t mangle -D PREROUTING -j $CHAIN_PREROUTING 2>/dev/null || true",
        "iptables -w -t mangle -D POSTROUTING -j $CHAIN_POSTROUTING_MSS 2>/dev/null || true",
        "iptables -w -t mangle -F $CHAIN_OUTPUT 2>/dev/null || true",
        "iptables -w -t mangle -X $CHAIN_OUTPUT 2>/dev/null || true",
        "iptables -w -t mangle -F $CHAIN_PREROUTING 2>/dev/null || true",
        "iptables -w -t mangle -X $CHAIN_PREROUTING 2>/dev/null || true",
        "iptables -w -t mangle -F $CHAIN_POSTROUTING_MSS 2>/dev/null || true",
        "iptables -w -t mangle -X $CHAIN_POSTROUTING_MSS 2>/dev/null || true",
        "iptables -w -t nat -D POSTROUTING -j $CHAIN_POSTROUTING 2>/dev/null || true",
        "iptables -w -t nat -F $CHAIN_POSTROUTING 2>/dev/null || true",
        "iptables -w -t nat -X $CHAIN_POSTROUTING 2>/dev/null || true",
        "iptables -w -t nat -D OUTPUT -j $CHAIN_DNS_NAT 2>/dev/null || true",
        "iptables -w -t nat -F $CHAIN_DNS_NAT 2>/dev/null || true",
        "iptables -w -t nat -X $CHAIN_DNS_NAT 2>/dev/null || true",
        "ip route flush cache 2>/dev/null || true",
    )

    fun buildTunCleanupCommands(tunInterfaceName: String = DEFAULT_TUN_NAME): List<String> = listOf(
        "ip link set dev $tunInterfaceName down 2>/dev/null || true",
        "ip link del dev $tunInterfaceName 2>/dev/null || true",
        "ip tuntap del dev $tunInterfaceName mode tun 2>/dev/null || true",
    )

    /**
     * Full synchronous rollback script executed on disconnect, onDestroy, onTaskRemoved, and app startup.
     */
    fun buildCleanupCommands(tunInterfaceName: String = DEFAULT_TUN_NAME): List<String> =
        buildRulesOnlyCleanupCommands() + buildTunCleanupCommands(tunInterfaceName)

    /**
     * Verifies that generated commands never violate root-safety invariants.
     */
    fun validateSafety(commands: List<String>): List<String> {
        val violations = mutableListOf<String>()
        for (cmd in commands) {
            val lower = cmd.lowercase()
            for (forbidden in FORBIDDEN_PATTERNS) {
                if (lower.contains(forbidden)) {
                    violations.add("Forbidden pattern '$forbidden' found in command: $cmd")
                }
            }
        }
        return violations
    }
}
