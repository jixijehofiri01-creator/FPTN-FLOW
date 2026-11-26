package com.example

import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.BackupCodec
import com.example.data.repository.BackupFormat
import com.example.domain.dns.DnsEndpointParser
import com.example.domain.dns.DnsEndpointType
import com.example.domain.model.BypassMethod
import com.example.domain.model.BypassRegion
import com.example.domain.model.CountryFlagResolver
import com.example.domain.model.DnsCategory
import com.example.domain.model.DnsMode
import com.example.domain.model.FptnServer
import com.example.domain.model.HomeQuickTileId
import com.example.domain.model.NetworkPreset
import com.example.domain.model.NetworkPresetCodec
import com.example.domain.model.NetworkPresetTrigger
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.ServerSortOrder
import com.example.domain.model.SlidingTunnelMode
import com.example.domain.model.SniSpoofingMode
import com.example.domain.model.SplitTunnelMode
import com.example.domain.model.TokenProfile
import com.example.domain.model.VpnSettings
import com.example.domain.root.SafeRootRulePlanner
import com.example.domain.routing.CidrBlock
import com.example.domain.routing.ProtectedRuApps
import com.example.domain.routing.RouteDecision
import com.example.domain.routing.SmartRfRouter
import com.example.domain.token.FptnTokenParser
import com.example.domain.token.TokenParseResult
import com.example.domain.vpn.StallWatchdogEvaluator
import com.example.domain.vpn.VpnErrorClassifier
import com.example.domain.vpn.WatchdogAction
import com.example.core.haptics.HapticEngine
import com.example.vpn.dns.TunnelDohResolver
import com.example.vpn.sni.SniAutoScanner
import com.example.vpn.tunnel.FptnProtocolClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FptnDomainUnitTest {

    private val sampleOfficialJson = """
        {
          "version": 1,
          "service_name": "FPTN Official",
          "username": "user_real_01",
          "password": "secret_password_123",
          "servers": [
            {
              "name": "Netherlands-AMS",
              "host": "nl1.example.org",
              "port": 443,
              "md5_fingerprint": "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99"
            }
          ],
          "censored_zone_servers": [
            {
              "name": "Finland-HEL-Censored",
              "host": "fi1.example.org",
              "port": 8443,
              "md5_fingerprint": "11223344556677889900aabbccddeeff"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parse official fptnb brotli compressed token succeeds`() {
        val brotliBytes = encodeBrotliUncompressedStream(sampleOfficialJson.toByteArray(StandardCharsets.UTF_8))
        val encoded = Base64.getEncoder().encodeToString(brotliBytes)
        val token = "fptnb:$encoded"

        val result = FptnTokenParser.parse(token)
        assertTrue("Expected Success, got $result", result is TokenParseResult.Success)
        val parsed = (result as TokenParseResult.Success).token
        assertEquals("FPTN Official", parsed.serviceName)
        assertEquals("user_real_01", parsed.username)
        assertEquals(2, parsed.servers.size)
        assertEquals("aabbccddeeff00112233445566778899", parsed.servers[0].md5Fingerprint)
        assertFalse(parsed.servers[0].isCensoredZone)
        assertTrue(parsed.servers[1].isCensoredZone)
    }

    @Test
    fun `parse self-hosted multiline unpadded url-safe base64 token succeeds`() {
        val rawB64 = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(sampleOfficialJson.toByteArray(StandardCharsets.UTF_8))
        val multiline76 = rawB64.chunked(76).joinToString("\n")
        val token = "fptn://$multiline76"

        val result = FptnTokenParser.parse(token)
        assertTrue("Expected Success, got $result", result is TokenParseResult.Success)
        val parsed = (result as TokenParseResult.Success).token
        assertEquals("FPTN Official", parsed.serviceName)
        assertEquals(2, parsed.servers.size)
    }

    @Test
    fun `parse raw json token succeeds and rejects empty or invalid tokens`() {
        val ok = FptnTokenParser.parse(sampleOfficialJson)
        assertTrue(ok is TokenParseResult.Success)

        val empty = FptnTokenParser.parse("   ")
        assertTrue(empty is TokenParseResult.Failure)

        val broken = FptnTokenParser.parse("fptnb:@@@invalid!!!")
        assertTrue(broken is TokenParseResult.Failure)
    }

    @Test
    fun `smart rf router bypasses russian domains and ips while forcing telegram through vpn`() {
        assertEquals(RouteDecision.DIRECT_RF, SmartRfRouter.decideForDomain("gosuslugi.ru", smartRfEnabled = true))
        assertEquals(RouteDecision.DIRECT_RF, SmartRfRouter.decideForDomain("президент.рф", smartRfEnabled = true))
        assertEquals(RouteDecision.DIRECT_RF, SmartRfRouter.decideForDomain("archive.su", smartRfEnabled = true))
        // Protected .ru domains MUST ALWAYS stay DIRECT_RF even if smartRfEnabled=false or allowOverrideRu=true
        assertEquals(RouteDecision.DIRECT_RF, SmartRfRouter.decideForDomain("gosuslugi.ru", smartRfEnabled = false, allowOverrideRu = false))
        assertEquals(RouteDecision.DIRECT_RF, SmartRfRouter.decideForDomain("gosuslugi.ru", smartRfEnabled = false, allowOverrideRu = true))

        assertEquals(RouteDecision.DIRECT_RF, SmartRfRouter.decideForIpv4("77.88.55.242", smartRfEnabled = true))

        val telegramIps = listOf("91.108.4.5", "91.108.56.130", "149.154.167.50", "185.76.151.10", "95.161.64.15")
        for (tgIp in telegramIps) {
            assertEquals(
                "Telegram IP $tgIp must ALWAYS route via VPN",
                RouteDecision.VPN_TUNNEL,
                SmartRfRouter.decideForIpv4(tgIp, smartRfEnabled = true),
            )
        }
        assertEquals(RouteDecision.VPN_TUNNEL, SmartRfRouter.decideForDomain("t.me", smartRfEnabled = true))
        assertEquals(RouteDecision.VPN_TUNNEL, SmartRfRouter.decideForDomain("web.telegram.org", smartRfEnabled = true))

        val vpnRoutes = SmartRfRouter.computeEffectiveVpnRoutes(
            smartRfEnabled = true,
            vpnServerIpv4 = "203.0.113.10",
        )
        val tgSampleIp = CidrBlock.parseIpv4("149.154.167.50")!!
        val rfSampleIp = CidrBlock.parseIpv4("77.88.55.242")!!
        assertTrue(vpnRoutes.any { it.contains(tgSampleIp) })
        assertFalse(vpnRoutes.any { it.contains(rfSampleIp) })
    }

    @Test
    fun `official 507-rule protected ru apps covers 2gis banks gosuslugi and respects exceptions`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ruleCount = ProtectedRuApps.getRuleCount(context)
        assertTrue("Expected >= 500 official exclusion rules, got $ruleCount", ruleCount >= 500)

        assertTrue("2GIS must be protected via ru.dublgis prefix", ProtectedRuApps.isProtected(context, "ru.dublgis.dgismobile"))
        assertTrue(ProtectedRuApps.isProtected(context, "ru.sberbankmobile"))
        assertTrue(ProtectedRuApps.isProtected(context, "ru.rostel"))
        assertTrue(ProtectedRuApps.isProtected(context, "com.idamob.tinkoff.android"))
        assertTrue(ProtectedRuApps.isProtected(context, "ru.nspk.mirpay"))

        assertFalse(ProtectedRuApps.isProtected(context, "ru.radioplayer"))
        assertFalse(ProtectedRuApps.isProtected(context, "org.telegram.messenger"))
        assertFalse(ProtectedRuApps.isProtected(context, "com.android.chrome"))
    }

    @Test
    fun `official sni catalog loads over 1200 domains and official strategy names match native binary`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val catalog = SniAutoScanner.loadOfficialSniCatalog(context)
        assertTrue("Expected > 1200 official SNI domains, got ${catalog.size}", catalog.size > 1200)
        assertEquals("ads.x5.ru", catalog.first())

        assertEquals("SNI", SniSpoofingMode.SNI.nativeStrategyName())
        assertEquals("SNI-REALITY-CHROME-149", SniSpoofingMode.SNI_REALITY_CHROME_149.nativeStrategyName())
        assertEquals("SNI-REALITY-YANDEX-26-4", SniSpoofingMode.SNI_REALITY_YANDEX_26_4.nativeStrategyName())
        assertEquals("SNI-REALITY-SAFARI-26-5", SniSpoofingMode.SNI_REALITY_SAFARI_26_5.nativeStrategyName())

        val obfSettings = VpnSettings(bypassMethod = BypassMethod.TLS_OBFUSCATION)
        assertEquals("OBFUSCATION", obfSettings.effectiveCensorshipStrategyName())
        assertEquals("rolling-tunnel", SlidingTunnelMode.ROLLING_TUNNEL.nativeStrategyName)
        assertEquals("dual-rolling-tunnel", SlidingTunnelMode.DUAL_TUNNEL.nativeStrategyName)
        assertEquals("triple-rolling-tunnel", SlidingTunnelMode.TRIPLE_TUNNEL.nativeStrategyName)
    }

    @Test
    fun `dns endpoint parser handles doh urls dot hostnames ipv4 and mutable dns list codec`() {
        val comss = DnsEndpointParser.parse("https://dns.comss.one/dns-query").getOrThrow()
        assertEquals(DnsEndpointType.DOH, comss.type)
        assertEquals("dns.comss.one", comss.host)
        assertEquals("https://dns.comss.one/dns-query", comss.dohUrl)

        val googleHost = DnsEndpointParser.parse("dns.google").getOrThrow()
        assertEquals(DnsEndpointType.DOT_HOST, googleHost.type)
        assertEquals("dns.google", googleHost.host)
        assertEquals("https://dns.google/dns-query", googleHost.dohUrl)
        assertEquals("8.8.8.8", googleHost.ipv4Fallback)

        val ipv4 = DnsEndpointParser.parse("1.1.1.1").getOrThrow()
        assertEquals(DnsEndpointType.IPV4, ipv4.type)
        assertEquals("1.1.1.1", ipv4.ipv4Fallback)

        assertTrue(DnsEndpointParser.parse("not a valid dns").isFailure)

        val customList = listOf(
            SavedDnsEntry(id = "my_ai", title = "My AI DNS", urlOrHost = "https://dns.custom.ai/dns-query"),
        )
        val encoded = DnsEndpointParser.encodeEntries(customList)
        val decoded = DnsEndpointParser.decodeEntries(encoded)
        assertEquals(1, decoded?.size)
        assertEquals("My AI DNS", decoded?.first()?.title)
    }

    @Test
    fun `tunnel doh resolver detects ipv4 udp port 53 and synthesizes swapped reply packet`() {
        val query = ByteArray(40)
        query[0] = 0x45.toByte()
        query[2] = 0x00
        query[3] = 40.toByte()
        query[9] = 17.toByte()
        query[12] = 10; query[13] = 10; query[14] = 0; query[15] = 2
        query[16] = 1; query[17] = 1; query[18] = 1; query[19] = 1
        query[20] = 0xC8.toByte(); query[21] = 0x22.toByte()
        query[22] = 0x00; query[23] = 53.toByte()
        query[24] = 0x00; query[25] = 20.toByte()

        assertTrue(TunnelDohResolver.isIpv4UdpDnsQuery(query, query.size))

        val dnsReplyPayload = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        val replyPacket = TunnelDohResolver.buildIpv4UdpReplyPacket(query, 20, dnsReplyPayload)

        assertEquals(20 + 8 + dnsReplyPayload.size, replyPacket.size)
        assertEquals(1, replyPacket[12].toInt() and 0xFF)
        assertEquals(10, replyPacket[16].toInt() and 0xFF)
        assertEquals(2, replyPacket[19].toInt() and 0xFF)
        assertEquals(53, ((replyPacket[20].toInt() and 0xFF) shl 8) or (replyPacket[21].toInt() and 0xFF))
        assertEquals(0xC822, ((replyPacket[22].toInt() and 0xFF) shl 8) or (replyPacket[23].toInt() and 0xFF))
        assertNotNull(replyPacket)
    }

    @Test
    fun `safe root rule planner generates stealth tun snat dns nat pref 12600 masked fwmark and full cleanup`() {
        val apply = SafeRootRulePlanner.buildApplyCommands(
            appUid = 10245,
            vpnServerIpv4 = "203.0.113.55",
            tunInterfaceName = "fptn0",
            smartRfEnabled = true,
            excludedUids = setOf(10300),
            clientTunIpv4 = "10.10.0.1",
            dnsServerIpv4 = "1.1.1.1",
        )

        val safetyViolations = SafeRootRulePlanner.validateSafety(apply)
        assertTrue("Unexpected safety violations: $safetyViolations", safetyViolations.isEmpty())

        assertTrue(apply.any { it.contains("-N FPTN_OUTPUT") })
        assertTrue(apply.any { it.contains("-N FPTN_POSTROUTING") })
        assertTrue(apply.any { it.contains("-o fptn0 -j SNAT --to-source 10.10.0.1") })
        assertTrue(apply.any { it.contains("-N FPTN_DNS_NAT") })
        assertTrue(apply.any { it.contains("--to-destination 1.1.1.1:53") })
        assertTrue(apply.any { it.contains("--uid-owner 10245 -j RETURN") })
        assertTrue(apply.any { it.contains("-d 203.0.113.55/32 -j RETURN") })
        assertTrue(apply.any { it.contains("-d 192.168.0.0/16 -j RETURN") })
        assertTrue(apply.any { it.contains("pref ${SafeRootRulePlanner.RULE_PREF}") })
        assertTrue(apply.any { it.contains("--set-xmark ${SafeRootRulePlanner.FWMARK_MASK}") })

        val tgRuleIndex = apply.indexOfFirst { it.contains("91.108.0.0/16") && it.contains("--set-xmark") }
        val rfRuleIndex = apply.indexOfFirst { it.contains("77.88.0.0/18") && it.contains("-j RETURN") }
        assertTrue("Telegram DC mark rule missing", tgRuleIndex >= 0)
        assertTrue("RF bypass rule missing", rfRuleIndex >= 0)
        assertTrue("Telegram DC rule must precede RF bypass rule", tgRuleIndex < rfRuleIndex)

        val cleanup = SafeRootRulePlanner.buildCleanupCommands()
        assertTrue(cleanup.any { it.contains("-D OUTPUT -j FPTN_OUTPUT") })
        assertTrue(cleanup.any { it.contains("-F FPTN_OUTPUT") })
        assertTrue(cleanup.any { it.contains("-X FPTN_OUTPUT") })
        assertTrue(cleanup.any { it.contains("-D POSTROUTING -j FPTN_POSTROUTING") })
        assertTrue(cleanup.any { it.contains("-D OUTPUT -j FPTN_DNS_NAT") })
        assertTrue(cleanup.any { it.contains("ip rule del fwmark ${SafeRootRulePlanner.FWMARK_MASK} table 198") })
        assertTrue(cleanup.any { it.contains("ip rule del fwmark ${SafeRootRulePlanner.LEGACY_FWMARK_HEX} table 198") })
        assertTrue(cleanup.any { it.contains("ip route flush table 198") })
        assertTrue(cleanup.any { it.contains("ip link del dev fptn0") })
        assertTrue(cleanup.any { it.contains("ip tuntap del dev fptn0 mode tun") })
    }

    @Test
    fun `vpn error classifier explains native code 609 port not reachable and normalizes 0_0_0_0 ip`() {
        val err609 = VpnErrorClassifier.classifyNativeHttpFailure(
            code = 609,
            errorMessage = "Port not reachable",
            body = null,
            endpoint = "198.51.100.10:443",
            censorshipStrategy = "OBFUSCATION",
            sniHost = "vkvideo.ru",
            isRussian = true,
        )
        assertTrue("Expected 609 port unreachable title, got ${err609.title}", err609.title.contains("609"))
        assertTrue(err609.title.contains("Порт", ignoreCase = true))
        assertFalse("609 must NOT be misreported as token/auth failure", err609.title.contains("Токен", ignoreCase = true))

        val err609En = VpnErrorClassifier.classifyNativeHttpFailure(
            code = 609,
            errorMessage = "Port not reachable",
            body = null,
            endpoint = "198.51.100.10:443",
            censorshipStrategy = "OBFUSCATION",
            sniHost = "vkvideo.ru",
            isRussian = false,
        )
        assertTrue(err609En.title.contains("Port", ignoreCase = true))
        assertTrue(err609En.title.contains("609"))

        val err401 = VpnErrorClassifier.classifyNativeHttpFailure(
            code = 401,
            errorMessage = "Unauthorized",
            body = null,
            endpoint = "198.51.100.10:443",
            censorshipStrategy = "SNI",
            sniHost = "ads.x5.ru",
            isRussian = true,
        )
        assertTrue(err401.title.contains("401"))

        assertEquals("10.10.0.1", VpnErrorClassifier.normalizeAssignedIp("0.0.0.0"))
        assertEquals("10.10.0.1", VpnErrorClassifier.normalizeAssignedIp(""))
        assertEquals("10.10.0.1", VpnErrorClassifier.normalizeAssignedIp(null))
        assertEquals("10.10.0.42", VpnErrorClassifier.normalizeAssignedIp("10.10.0.42"))
    }

    @Test
    fun `stall watchdog handles tcp-backoff gaps sends active probe at 4s reconnects at 7s and triggers immediately on ws death or send failures`() {
        val watchdog = StallWatchdogEvaluator(
            probeThresholdSec = 4,
            stallThresholdSec = 7,
            minUnansweredTxBytes = 600L,
            minUnansweredTxPackets = 5,
            maxSendFailures = 6,
            cooldownSec = 18,
        )

        // 30s completely idle -> never fires
        repeat(30) {
            assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(tunnelRxDeltaBytes = 0L, tunnelTxDeltaBytes = 0L))
        }

        // 20s healthy traffic -> never fires
        repeat(20) {
            assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(tunnelRxDeltaBytes = 2048L, tunnelTxDeltaBytes = 512L))
        }

        // Bursty TCP-backoff traffic (0-byte gaps between retransmissions, RX == 0 the whole time):
        // sec 1: TX burst (240B, 2 pkts) -> NONE
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 240L, tunnelTxDeltaPackets = 2))
        // sec 2: 0-byte TCP backoff gap -> NONE (must NOT reset timer!)
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 0L, tunnelTxDeltaPackets = 0))
        // sec 3: TX retry (220B, 2 pkts) -> NONE
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 220L, tunnelTxDeltaPackets = 2))
        // sec 4: 0-byte TCP backoff gap -> hits probeThresholdSec (4s) -> SEND_PROBE
        assertEquals(WatchdogAction.SEND_PROBE, watchdog.evaluateTick(0L, 0L, tunnelTxDeltaPackets = 0))
        // sec 5: TX retry (220B, 2 pkts) -> NONE (probe already sent once in window)
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 220L, tunnelTxDeltaPackets = 2))
        // sec 6: 0-byte gap -> NONE
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 0L, tunnelTxDeltaPackets = 0))
        // sec 7: hits stallThresholdSec (7s) with 680B / 6 pkts unanswered -> RECONNECT!
        assertEquals(WatchdogAction.RECONNECT, watchdog.evaluateTick(0L, 0L, tunnelTxDeltaPackets = 0))

        // During 18s cooldown -> must not double-fire
        repeat(18) {
            assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 600L, tunnelTxDeltaPackets = 5))
        }

        // Native WebSocket dead (wsAlive = false) -> immediate RECONNECT
        assertEquals(WatchdogAction.RECONNECT, watchdog.evaluateTick(0L, 0L, wsAlive = false))

        // Reset and test >= 6 native send failures -> immediate RECONNECT
        watchdog.reset()
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 120L, sendFailureDelta = 3))
        assertEquals(WatchdogAction.RECONNECT, watchdog.evaluateTick(0L, 120L, sendFailureDelta = 3))
    }

    @Test
    fun `stall watchdog builds valid 28-byte rfc792 ipv4 icmp echo request packet`() {
        val pkt = StallWatchdogEvaluator.buildIpv4IcmpEchoRequest(
            sourceIpv4 = "10.10.0.1",
            destIpv4 = "1.1.1.1",
            identifier = 0x4650,
            sequence = 7,
        )
        assertNotNull(pkt)
        pkt!!
        assertEquals(28, pkt.size)
        assertEquals(0x45, pkt[0].toInt() and 0xFF) // IPv4, IHL=5
        assertEquals(28, ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF))
        assertEquals(1, pkt[9].toInt() and 0xFF) // Protocol = ICMP
        // Source 10.10.0.1
        assertEquals(10, pkt[12].toInt() and 0xFF)
        assertEquals(10, pkt[13].toInt() and 0xFF)
        assertEquals(0, pkt[14].toInt() and 0xFF)
        assertEquals(1, pkt[15].toInt() and 0xFF)
        // Dest 1.1.1.1
        assertEquals(1, pkt[16].toInt() and 0xFF)
        assertEquals(1, pkt[19].toInt() and 0xFF)
        // ICMP Type 8 (Echo Request), Code 0
        assertEquals(8, pkt[20].toInt() and 0xFF)
        assertEquals(0, pkt[21].toInt() and 0xFF)
        assertEquals(0x4650, ((pkt[24].toInt() and 0xFF) shl 8) or (pkt[25].toInt() and 0xFF))
        assertEquals(7, ((pkt[26].toInt() and 0xFF) shl 8) or (pkt[27].toInt() and 0xFF))
    }

    @Test
    fun `pull haptics intensity ramps up on connect and ramps down on disconnect`() {
        val steps = listOf(0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 1.0f)
        val connectCurve = steps.map { HapticEngine.computePullIntensity(it, isDisconnectingPull = false) }
        val disconnectCurve = steps.map { HapticEngine.computePullIntensity(it, isDisconnectingPull = true) }

        for (i in 0 until steps.lastIndex) {
            assertTrue(
                "Connect haptics must strictly increase: ${connectCurve[i]} < ${connectCurve[i + 1]}",
                connectCurve[i] < connectCurve[i + 1],
            )
            assertTrue(
                "Disconnect haptics must strictly decrease: ${disconnectCurve[i]} > ${disconnectCurve[i + 1]}",
                disconnectCurve[i] > disconnectCurve[i + 1],
            )
        }
        assertTrue("Connect start should be soft (< 0.25)", connectCurve.first() < 0.25f)
        assertTrue("Connect end should be strong (> 0.90)", connectCurve.last() > 0.90f)
        assertTrue("Disconnect start should be strong (> 0.80)", disconnectCurve.first() > 0.80f)
        assertTrue("Disconnect end should be soft (< 0.20)", disconnectCurve.last() < 0.20f)
    }

    @Test
    fun `network preset codec round-trips custom presets and applyPreset updates settings`() {
        val customPreset = NetworkPreset(
            id = "custom_mts",
            name = "МТС 5G Макс",
            trigger = NetworkPresetTrigger.MOBILE,
            bypassMethod = BypassMethod.TLS_OBFUSCATION,
            sniSpoofingMode = SniSpoofingMode.SNI_REALITY_CHROME_149,
            customSni = "vkvideo.ru",
            slidingTunnelMode = SlidingTunnelMode.TRIPLE_TUNNEL,
            enableCustomDnsUrl = true,
            customDnsUrl = "https://dns.comss.one/dns-query",
            enableStallWatchdog = true,
            watchdogProbeSec = 3,
            watchdogStallSec = 6,
            reconnectOnFailure = true,
            reconnectOnNetworkChange = false,
            maxReconnectAttempts = 30,
            tunMtu = 1360,
            smartRfRouting = false,
            useRootModeOverride = true,
            isBuiltIn = false,
        )
        val encoded = NetworkPresetCodec.encode(listOf(customPreset))
        val decoded = NetworkPresetCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.size)
        assertEquals(customPreset, decoded.first())

        val updated = VpnSettings().applyPreset(customPreset)
        assertEquals("custom_mts", updated.activePresetId)
        assertEquals(BypassMethod.TLS_OBFUSCATION, updated.bypassMethod)
        assertEquals(SniSpoofingMode.SNI_REALITY_CHROME_149, updated.sniSpoofingMode)
        assertEquals("vkvideo.ru", updated.customSni)
        assertEquals(SlidingTunnelMode.TRIPLE_TUNNEL, updated.slidingTunnelMode)
        assertTrue(updated.enableCustomDnsUrl)
        assertEquals("https://dns.comss.one/dns-query", updated.customDnsUrl)
        assertEquals(3, updated.watchdogProbeSec)
        assertEquals(6, updated.watchdogStallSec)
        assertFalse(updated.reconnectOnNetworkChange)
        assertEquals(30, updated.maxReconnectAttempts)
        assertEquals(1360, updated.tunMtu)
        assertTrue("smartRfRouting is permanently locked true even when preset sets false", updated.smartRfRouting)
        assertTrue(updated.useRootMode)

        val customTiles = listOf(HomeQuickTileId.PRESET, HomeQuickTileId.STALL_WATCHDOG, HomeQuickTileId.MTU, HomeQuickTileId.LOGS)
        val encodedTiles = NetworkPresetCodec.encodeQuickTiles(customTiles)
        assertEquals(customTiles, NetworkPresetCodec.decodeQuickTiles(encodedTiles))
    }

    @Test
    fun `dns collision guard and protobuf framing work reliably`() {
        assertEquals("1.1.1.1", FptnProtocolClient.resolveSafeDns("10.10.0.2", "10.10.0.2"))
        assertEquals("8.8.8.8", FptnProtocolClient.resolveSafeDns("8.8.8.8", "10.10.0.2"))

        val mockIpv4Packet = byteArrayOf(0x45, 0x00, 0x00, 0x1C, 0x01, 0x02, 0x03, 0x04)
        val framed = FptnProtocolClient.encodeProtobufIpPacket(mockIpv4Packet)
        val decoded = FptnProtocolClient.decodeProtobufIpPacket(framed)
        assertTrue(mockIpv4Packet.contentEquals(decoded))
    }

    @Test
    fun `backup codec v5 preserves multi-token profiles presets watchdog mtu quick tiles and custom dns list`() {
        val profiles = listOf(
            TokenProfile(
                id = 1,
                name = "Официальный FPTN",
                rawToken = "fptn:abc",
                serviceName = "FPTN",
                username = "u1",
                password = "p1",
                enabled = true,
            ),
            TokenProfile(
                id = 2,
                name = "Мой VPS",
                rawToken = "fptnb:xyz",
                serviceName = "VPS",
                username = "u2",
                password = "p2",
                enabled = false,
            ),
        )
        val customDnsList = listOf(
            SavedDnsEntry(id = "user_dns_1", title = "Мой ИИ DNS", urlOrHost = "https://dns.myai.example/dns-query"),
        )
        val customPresets = VpnSettings.DEFAULT_NETWORK_PRESETS + NetworkPreset(
            id = "preset_work",
            name = "Офис Wi-Fi",
            trigger = NetworkPresetTrigger.WIFI,
            bypassMethod = BypassMethod.SNI_REALITY,
            sniSpoofingMode = SniSpoofingMode.SNI_REALITY_YANDEX_26_4,
            customSni = "ads.x5.ru",
            slidingTunnelMode = SlidingTunnelMode.ROLLING_TUNNEL,
            enableCustomDnsUrl = false,
            customDnsUrl = "",
            enableStallWatchdog = false,
            watchdogProbeSec = 5,
            watchdogStallSec = 10,
            tunMtu = 1420,
            isBuiltIn = false,
        )
        val customTiles = listOf(HomeQuickTileId.PRESET, HomeQuickTileId.MTU, HomeQuickTileId.LOGS)
        val settings = VpnSettings(
            bypassMethod = BypassMethod.TLS_OBFUSCATION,
            sniSpoofingMode = SniSpoofingMode.SNI_REALITY_CHROME_149,
            customSni = "ads.x5.ru",
            slidingTunnelMode = SlidingTunnelMode.DUAL_TUNNEL,
            smartRfRouting = true,
            splitTunnelMode = SplitTunnelMode.EXCLUDE_SELECTED,
            selectedPackages = setOf("com.android.chrome"),
            dnsMode = DnsMode.SMART_DNS_DOH,
            dnsPrimary = "9.9.9.9",
            customDnsUrl = "https://dns.myai.example/dns-query",
            enableCustomDnsUrl = true,
            savedDnsEntries = customDnsList,
            serverSortOrder = ServerSortOrder.BY_PING,
            networkPresets = customPresets,
            activePresetId = "preset_work",
            autoSwitchPresetsByNetwork = true,
            tunMtu = 1360,
            enableWaterWaveEffect = false,
            reconnectOnNetworkChange = false,
            maxReconnectAttempts = 25,
            reconnectDelaySec = 4,
            handshakeTimeoutSec = 12,
            wsOpenTimeoutSec = 22,
            enableStallWatchdog = true,
            watchdogProbeSec = 3,
            watchdogStallSec = 8,
            watchdogCooldownSec = 24,
            homeQuickTiles = customTiles,
        )

        val encoded = BackupCodec.encode(profiles, settings)
        val decoded = BackupCodec.decode(encoded).getOrThrow()

        assertEquals(2, decoded.profiles.size)
        assertEquals("Официальный FPTN", decoded.profiles[0].name)
        assertEquals("Мой VPS", decoded.profiles[1].name)
        assertFalse(decoded.profiles[1].enabled)
        assertEquals(BypassMethod.TLS_OBFUSCATION, decoded.settings.bypassMethod)
        assertEquals(SniSpoofingMode.SNI_REALITY_CHROME_149, decoded.settings.sniSpoofingMode)
        assertEquals(SlidingTunnelMode.DUAL_TUNNEL, decoded.settings.slidingTunnelMode)
        assertEquals("ads.x5.ru", decoded.settings.customSni)
        assertEquals("https://dns.myai.example/dns-query", decoded.settings.customDnsUrl)
        assertEquals(1, decoded.settings.savedDnsEntries.size)
        assertEquals("Мой ИИ DNS", decoded.settings.savedDnsEntries.first().title)
        assertEquals(DnsMode.SMART_DNS_DOH, decoded.settings.dnsMode)
        assertTrue(decoded.settings.enableCustomDnsUrl)
        assertEquals(setOf("com.android.chrome"), decoded.settings.selectedPackages)
        assertEquals("9.9.9.9", decoded.settings.dnsPrimary)
        assertEquals(ServerSortOrder.BY_PING, decoded.settings.serverSortOrder)
        assertEquals(5, decoded.settings.networkPresets.size)
        assertEquals("preset_work", decoded.settings.activePresetId)
        assertTrue(decoded.settings.autoSwitchPresetsByNetwork)
        assertEquals(1360, decoded.settings.tunMtu)
        assertFalse(decoded.settings.enableWaterWaveEffect)
        assertFalse(decoded.settings.reconnectOnNetworkChange)
        assertEquals(25, decoded.settings.maxReconnectAttempts)
        assertEquals(4, decoded.settings.reconnectDelaySec)
        assertEquals(12, decoded.settings.handshakeTimeoutSec)
        assertEquals(22, decoded.settings.wsOpenTimeoutSec)
        assertEquals(3, decoded.settings.watchdogProbeSec)
        assertEquals(8, decoded.settings.watchdogStallSec)
        assertEquals(24, decoded.settings.watchdogCooldownSec)
        assertEquals(customTiles, decoded.settings.homeQuickTiles)
    }

    @Test
    fun `country flag resolver extracts strips personal emoji and resolves official server names`() {
        val personal = FptnServer(
            name = "🇩🇪 Мой Франкфурт #1",
            host = "1.2.3.4",
            port = 443,
            md5Fingerprint = "aa",
        )
        assertEquals("DE", personal.countryCode)
        assertEquals("Мой Франкфурт #1", personal.cleanName)
        assertFalse("Flag emoji must be stripped from cleanName", personal.cleanName.contains("🇩🇪"))

        val officialNl = FptnServer(
            name = "Netherlands-AMS",
            host = "nl1.example.org",
            port = 443,
            md5Fingerprint = "bb",
        )
        assertEquals("NL", officialNl.countryCode)
        assertEquals("Netherlands-AMS", officialNl.cleanName)

        val ruKz = CountryFlagResolver.resolve("Казахстан Алматы", "kz.vpn.net")
        assertEquals("KZ", ruKz.countryCode)

        val tldFallback = CountryFlagResolver.resolve("Custom Node 99", "edge.fr")
        assertEquals("FR", tldFallback.countryCode)
    }

    @Test
    fun `dns mode is strictly mutually exclusive and default smart dns catalog has 17 categorized servers`() {
        val defaults = VpnSettings.DEFAULT_DNS_ENTRIES
        assertTrue("Expected >= 17 built-in Smart DNS entries, got ${defaults.size}", defaults.size >= 17)
        assertTrue(defaults.any { it.category == DnsCategory.INTERNATIONAL_AI && it.urlOrHost.contains("magicinfo.art") })
        assertTrue(defaults.any { it.category == DnsCategory.RU_ZONE_AI })
        assertTrue(defaults.any { it.category == DnsCategory.PUBLIC_RESERVE })

        val s0 = VpnSettings()
        val sSmart = s0.withDnsMode(DnsMode.SMART_DNS_DOH, "https://magicinfo.art/dns-query")
        assertEquals(DnsMode.SMART_DNS_DOH, sSmart.dnsMode)
        assertTrue(sSmart.enableCustomDnsUrl)
        assertFalse("Smart DNS must NOT coexist with AdBlock DNS", sSmart.blockAdsDns)

        val sAd = sSmart.withDnsMode(DnsMode.ADBLOCK_DNS)
        assertEquals(DnsMode.ADBLOCK_DNS, sAd.dnsMode)
        assertTrue(sAd.blockAdsDns)
        assertFalse("AdBlock DNS must disable Smart DNS DoH", sAd.enableCustomDnsUrl)

        val sIpv4 = sAd.withDnsMode(DnsMode.CUSTOM_IPV4)
        assertEquals(DnsMode.CUSTOM_IPV4, sIpv4.dnsMode)
        assertFalse(sIpv4.blockAdsDns)
        assertFalse(sIpv4.enableCustomDnsUrl)
    }

    @Test
    fun `smart rf router honors custom direct and vpn domains regional bypass and root override while protecting telegram`() {
        // Custom direct domain overrides VPN default
        assertEquals(
            RouteDecision.DIRECT_RF,
            SmartRfRouter.decideForDomain(
                domain = "sub.mybank.com",
                smartRfEnabled = true,
                customDirectDomains = setOf("mybank.com"),
            ),
        )

        // Telegram ALWAYS stays in VPN even if user tries to put t.me in customDirectDomains
        assertEquals(
            RouteDecision.VPN_TUNNEL,
            SmartRfRouter.decideForDomain(
                domain = "t.me",
                smartRfEnabled = true,
                customDirectDomains = setOf("t.me"),
            ),
        )

        // Regional bypass for China (.cn) and Iran (.ir)
        assertEquals(
            RouteDecision.DIRECT_RF,
            SmartRfRouter.decideForDomain("baidu.cn", smartRfEnabled = true, bypassRegion = BypassRegion.CHINA),
        )
        assertEquals(
            RouteDecision.DIRECT_RF,
            SmartRfRouter.decideForDomain("site.ir", smartRfEnabled = true, bypassRegion = BypassRegion.IRAN),
        )
        // BypassRegion.NONE is permanently sanitized to RUSSIA; .ru protection CANNOT be disabled even with Root override
        assertEquals(
            RouteDecision.DIRECT_RF,
            SmartRfRouter.decideForDomain("gosuslugi.ru", smartRfEnabled = true, bypassRegion = BypassRegion.NONE, allowOverrideRu = false),
        )
        assertEquals(
            RouteDecision.DIRECT_RF,
            SmartRfRouter.decideForDomain("gosuslugi.ru", smartRfEnabled = true, bypassRegion = BypassRegion.NONE, allowOverrideRu = true),
        )

        // Custom VPN domain NEVER overrides protected .ru domains even when allowOverrideRu = true
        assertEquals(
            RouteDecision.DIRECT_RF,
            SmartRfRouter.decideForDomain(
                domain = "special.ru",
                smartRfEnabled = true,
                bypassRegion = BypassRegion.RUSSIA,
                customVpnDomains = setOf("special.ru"),
                allowOverrideRu = false,
            ),
        )
        assertEquals(
            RouteDecision.DIRECT_RF,
            SmartRfRouter.decideForDomain(
                domain = "special.ru",
                smartRfEnabled = true,
                bypassRegion = BypassRegion.RUSSIA,
                customVpnDomains = setOf("special.ru"),
                allowOverrideRu = true,
            ),
        )
    }

    @Test
    fun `backup codec supports schema v6 official compatible export and raw token auto-detect`() {
        val rawToken = "fptn:${Base64.getUrlEncoder().withoutPadding().encodeToString(sampleOfficialJson.toByteArray(StandardCharsets.UTF_8))}"
        val profiles = listOf(
            TokenProfile(
                id = 1,
                name = "Official",
                rawToken = rawToken,
                serviceName = "FPTN",
                username = "u1",
                password = "p1",
                enabled = true,
            ),
        )
        val settings = VpnSettings(
            bypassRegion = BypassRegion.CHINA,
            customDirectDomains = setOf("local.org"),
            customVpnDomains = setOf("chatgpt.com"),
            unlockProtectedRuWithRoot = true,
        ).withDnsMode(DnsMode.ADBLOCK_DNS)

        // Full round-trip (unlockProtectedRuWithRoot is permanently forced false)
        val fullJson = BackupCodec.encode(profiles, settings)
        val fullDecoded = BackupCodec.decode(fullJson).getOrThrow()
        assertEquals(BackupFormat.FULL_CLIENT, fullDecoded.detectedFormat)
        assertEquals(DnsMode.ADBLOCK_DNS, fullDecoded.settings.dnsMode)
        assertEquals(BypassRegion.CHINA, fullDecoded.settings.bypassRegion)
        assertEquals(setOf("local.org"), fullDecoded.settings.customDirectDomains)
        assertEquals(setOf("chatgpt.com"), fullDecoded.settings.customVpnDomains)
        assertFalse(fullDecoded.settings.unlockProtectedRuWithRoot)

        // Official-compatible export & auto-detect import
        val officialJson = BackupCodec.encodeOfficialCompatible(profiles, settings)
        val officialDecoded = BackupCodec.decode(officialJson).getOrThrow()
        assertEquals(BackupFormat.OFFICIAL_COMPATIBLE, officialDecoded.detectedFormat)
        assertEquals(1, officialDecoded.profiles.size)

        // Raw token auto-detect import
        val rawDecoded = BackupCodec.decode(rawToken).getOrThrow()
        assertEquals(BackupFormat.RAW_TOKENS, rawDecoded.detectedFormat)
        assertEquals(1, rawDecoded.profiles.size)
    }

    @Test
    fun `per-installation randomized default presets are deterministic per seed and vary across installs`() {
        val pool = listOf("a.example.ru", "b.example.ru", "c.example.ru", "d.example.ru", "e.example.ru", "f.example.ru")
        val p1a = VpnSettings.generateRandomizedDefaultPresets(pool, seed = 1001L)
        val p1b = VpnSettings.generateRandomizedDefaultPresets(pool, seed = 1001L)
        val p2 = VpnSettings.generateRandomizedDefaultPresets(pool, seed = 999999L)

        assertEquals(p1a, p1b)
        assertEquals(VpnSettings.DEFAULT_NETWORK_PRESETS.size, p1a.size)
        val snis1 = p1a.map { it.customSni }
        val snis2 = p2.map { it.customSni }
        assertTrue("Different install seeds should yield different preset SNI choices", snis1 != snis2)
    }

    private fun encodeBrotliUncompressedStream(data: ByteArray): ByteArray {
        require(data.isNotEmpty() && data.size <= 65536)
        val bits = BitWriter()
        bits.writeBits(0, 1)
        bits.writeBits(0, 1)
        bits.writeBits(0, 2)
        bits.writeBits(data.size - 1, 16)
        bits.writeBits(1, 1)
        bits.alignToByte()
        for (b in data) {
            bits.writeByte(b.toInt() and 0xFF)
        }
        bits.writeBits(1, 1)
        bits.writeBits(1, 1)
        return bits.toByteArray()
    }

    private class BitWriter {
        private val out = ByteArrayOutputStream()
        private var currentByte = 0
        private var bitPos = 0

        fun writeBits(value: Int, count: Int) {
            var v = value
            repeat(count) {
                if ((v and 1) != 0) {
                    currentByte = currentByte or (1 shl bitPos)
                }
                v = v ushr 1
                bitPos++
                if (bitPos == 8) {
                    out.write(currentByte)
                    currentByte = 0
                    bitPos = 0
                }
            }
        }

        fun alignToByte() {
            if (bitPos > 0) {
                out.write(currentByte)
                currentByte = 0
                bitPos = 0
            }
        }

        fun writeByte(b: Int) {
            alignToByte()
            out.write(b)
        }

        fun toByteArray(): ByteArray {
            alignToByte()
            return out.toByteArray()
        }
    }

    @Test
    fun `smart rf router permanently rejects protected ru domains in custom vpn list even with root`() {
        assertTrue(SmartRfRouter.checkDomainVpnOverrideError("gosuslugi.ru", allowRootOverride = false) != null)
        assertTrue(SmartRfRouter.checkDomainVpnOverrideError("sberbank.ru", allowRootOverride = false) != null)
        assertTrue(SmartRfRouter.checkDomainVpnOverrideError("vk.com", allowRootOverride = false) != null)
        assertTrue(SmartRfRouter.checkDomainVpnOverrideError("site.su", allowRootOverride = false) != null)
        assertTrue(SmartRfRouter.checkDomainVpnOverrideError("президент.рф", allowRootOverride = false) != null)
        assertTrue(
            SmartRfRouter.checkDomainVpnOverrideError(
                rawDomain = "custom-ru-mirror.com",
                resolvedIpv4s = setOf("77.88.55.242"),
                allowRootOverride = false,
            ) != null,
        )
        assertEquals(null, SmartRfRouter.checkDomainVpnOverrideError("youtube.com", allowRootOverride = false))
        assertTrue(
            "Protected .ru domain must stay blocked from Force-VPN even when allowRootOverride=true",
            SmartRfRouter.checkDomainVpnOverrideError("gosuslugi.ru", allowRootOverride = true) != null,
        )
    }

    @Test
    fun `country flag resolver supports pirate pride trans subdivisions and all iso flags`() {
        val pirate = com.example.domain.model.CountryFlagResolver.resolve("🏴‍☠️ Tortuga Fast")
        assertEquals(com.example.domain.model.CountryFlagResolver.CODE_PIRATE, pirate.countryCode)
        assertEquals("Tortuga Fast", pirate.cleanName)

        assertEquals(
            com.example.domain.model.CountryFlagResolver.CODE_PRIDE,
            com.example.domain.model.CountryFlagResolver.resolve("🏳️‍🌈 Amsterdam").countryCode,
        )
        assertEquals(
            com.example.domain.model.CountryFlagResolver.CODE_TRANS,
            com.example.domain.model.CountryFlagResolver.resolve("🏳️‍⚧️ Berlin").countryCode,
        )
        assertEquals(
            "JP",
            com.example.domain.model.CountryFlagResolver.resolve("🇯🇵 Tokyo #1").countryCode,
        )
    }

    @Test
    fun `backup codec v7 preserves wave flag backdrop coat of arms and gradient settings`() {
        val original = VpnSettings(
            waveSpeed = 0.55f,
            waveStrength = 1.25f,
            waveSmoothness = 0.90f,
            waveElasticity = 1.10f,
            waveTintStrength = 0.45f,
            enableFlagBackdrop = true,
            flagBackdropScale = 1.35f,
            flagBackdropDim = 0.52f,
            flagShowCoatOfArms = false,
            flagPhysicsIntensity = 1.20f,
            backgroundGradientStyle = com.example.domain.model.BackgroundGradientStyle.DEEP_COSMOS,
        )
        val json = BackupCodec.encode(emptyList(), original)
        val decoded = BackupCodec.decode(json).getOrThrow()
        assertEquals(0.55f, decoded.settings.waveSpeed, 0.001f)
        assertEquals(1.25f, decoded.settings.waveStrength, 0.001f)
        assertEquals(0.90f, decoded.settings.waveSmoothness, 0.001f)
        assertEquals(1.10f, decoded.settings.waveElasticity, 0.001f)
        assertEquals(0.45f, decoded.settings.waveTintStrength, 0.001f)
        assertEquals(1.35f, decoded.settings.flagBackdropScale, 0.001f)
        assertEquals(0.52f, decoded.settings.flagBackdropDim, 0.001f)
        assertFalse(decoded.settings.flagShowCoatOfArms)
        assertEquals(1.20f, decoded.settings.flagPhysicsIntensity, 0.001f)
        assertEquals(com.example.domain.model.BackgroundGradientStyle.DEEP_COSMOS, decoded.settings.backgroundGradientStyle)
    }

    @Test
    fun `solar atmosphere engine computes seasonal solar elevation phases and seasons`() {
        val summerNoon = com.example.domain.atmosphere.SolarAtmosphereEngine.evaluate(
            overrideHour = 12.25f,
            overrideDayOfYear = 172, // June 21
            latitudeDeg = 55.7,
        )
        val winterNoon = com.example.domain.atmosphere.SolarAtmosphereEngine.evaluate(
            overrideHour = 12.25f,
            overrideDayOfYear = 355, // Dec 21
            latitudeDeg = 55.7,
        )
        val midnight = com.example.domain.atmosphere.SolarAtmosphereEngine.evaluate(
            overrideHour = 0.5f,
            overrideDayOfYear = 355,
            latitudeDeg = 55.7,
        )

        assertTrue(
            "Summer noon elevation (${summerNoon.solarElevationDeg}) must exceed winter noon (${winterNoon.solarElevationDeg})",
            summerNoon.solarElevationDeg > winterNoon.solarElevationDeg + 30f,
        )
        assertEquals(com.example.domain.atmosphere.Season.SUMMER, summerNoon.season)
        assertEquals(com.example.domain.atmosphere.Season.WINTER, winterNoon.season)
        assertEquals(com.example.domain.atmosphere.SolarPhase.DAY, summerNoon.phase)
        assertEquals(com.example.domain.atmosphere.SolarPhase.NIGHT, midnight.phase)
        assertEquals(
            com.example.domain.atmosphere.Season.SPRING,
            com.example.domain.atmosphere.SolarAtmosphereEngine.seasonForDayOfYear(100, northernHemisphere = true),
        )
        assertEquals(
            com.example.domain.atmosphere.Season.AUTUMN,
            com.example.domain.atmosphere.SolarAtmosphereEngine.seasonForDayOfYear(100, northernHemisphere = false),
        )
    }

    @Test
    fun `vpn error classifier detects code 600 tls stream truncated and provides russian diagnostic`() {
        val msg = "handshake: stream truncated [asio.ssl.stream:1]"
        assertTrue(VpnErrorClassifier.isTlsStreamTruncatedOrReset(600, msg))
        assertTrue(VpnErrorClassifier.isTlsStreamTruncatedOrReset(0, "asio.ssl handshake reset"))
        assertFalse(VpnErrorClassifier.isTlsStreamTruncatedOrReset(401, "Unauthorized"))

        val err600 = VpnErrorClassifier.classifyNativeHttpFailure(
            code = 600,
            errorMessage = msg,
            body = null,
            endpoint = "203.0.113.10:443",
            censorshipStrategy = "SNI",
            sniHost = "vkvideo.ru",
            isRussian = true,
        )
        assertTrue("Expected code 600 in title, got ${err600.title}", err600.title.contains("600"))
        assertTrue("Expected TLS in title, got ${err600.title}", err600.title.contains("TLS", ignoreCase = true))
        assertTrue(err600.hint.contains("Авто-починка", ignoreCase = true))

        val err600En = VpnErrorClassifier.classifyNativeHttpFailure(
            code = 600,
            errorMessage = msg,
            body = null,
            endpoint = "203.0.113.10:443",
            censorshipStrategy = "SNI",
            sniHost = "vkvideo.ru",
            isRussian = false,
        )
        assertTrue(err600En.title.contains("TLS", ignoreCase = true))
        assertTrue(err600En.hint.contains("Auto-fix", ignoreCase = true))
    }

    @Test
    fun `backup codec v8 round-trips wave dispersion solar weather and self-healing flags`() {
        val original = VpnSettings(
            waveDispersion = 1.45f,
            waveRingWidth = 1.35f,
            enableSolarAtmosphere = false,
            weatherEffectMode = com.example.domain.model.WeatherEffectMode.STORM,
            enableWeatherHaptics = false,
            weatherIntensity = 1.25f,
            autoRotateSniOnFail = false,
            autoFailoverServer = false,
        )
        val json = BackupCodec.encode(emptyList(), original)
        val decoded = BackupCodec.decode(json).getOrThrow()

        assertEquals(1.45f, decoded.settings.waveDispersion, 0.001f)
        assertEquals(1.35f, decoded.settings.waveRingWidth, 0.001f)
        assertFalse(decoded.settings.enableSolarAtmosphere)
        assertEquals(com.example.domain.model.WeatherEffectMode.STORM, decoded.settings.weatherEffectMode)
        assertFalse(decoded.settings.enableWeatherHaptics)
        assertEquals(1.25f, decoded.settings.weatherIntensity, 0.001f)
        assertFalse(decoded.settings.autoRotateSniOnFail)
        assertFalse(decoded.settings.autoFailoverServer)
    }

    @Test
    fun `switching server state counts as busy tunnel and weather wmo mapping covers all conditions`() {
        val sampleServer = FptnServer(name = "DE-FRA", host = "de.example.org", port = 443, md5Fingerprint = "aa")
        assertTrue(com.example.domain.model.VpnConnectionState.SwitchingServer(null, sampleServer).isTunnelBusyOrActive)
        assertEquals(
            com.example.domain.model.WeatherEffectMode.CLEAR,
            com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(0),
        )
        assertEquals(
            com.example.domain.model.WeatherEffectMode.CLOUDY,
            com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(3),
        )
        assertEquals(
            com.example.domain.model.WeatherEffectMode.RAIN,
            com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(61),
        )
        assertEquals(
            com.example.domain.model.WeatherEffectMode.SNOW,
            com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(73),
        )
        assertEquals(
            com.example.domain.model.WeatherEffectMode.STORM,
            com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(95),
        )
    }

    @Test
    fun `manual overrides protect smart dns and custom sni from preset overwrites and round-trip in backup v9`() {
        val mobilePreset = VpnSettings.DEFAULT_NETWORK_PRESETS.first { it.id == "preset_mobile" }
        val base = VpnSettings()
            .withDnsMode(DnsMode.SMART_DNS_DOH, "https://magicinfo.art/dns-query")
            .copy(
                customSni = "custom.example.ru",
                manualOverrides = setOf(
                    com.example.domain.model.ManualOverrideKey.DNS,
                    com.example.domain.model.ManualOverrideKey.CUSTOM_SNI,
                ),
            )

        // Auto-switch (respectManualOverrides = true) MUST keep both Smart DNS and Custom SNI intact
        val afterAutoPreset = base.applyPreset(mobilePreset, respectManualOverrides = true)
        assertEquals(DnsMode.SMART_DNS_DOH, afterAutoPreset.dnsMode)
        assertTrue(afterAutoPreset.enableCustomDnsUrl)
        assertEquals("https://magicinfo.art/dns-query", afterAutoPreset.customDnsUrl)
        assertEquals("custom.example.ru", afterAutoPreset.customSni)

        // Manual preset switch without preset Smart DNS MUST still protect manually forced Smart DNS
        val afterManualPreset = base.applyPreset(mobilePreset, respectManualOverrides = false)
        assertEquals(DnsMode.SMART_DNS_DOH, afterManualPreset.dnsMode)
        assertTrue(afterManualPreset.enableCustomDnsUrl)
        assertEquals("https://magicinfo.art/dns-query", afterManualPreset.customDnsUrl)

        // BackupCodec v9 preserves manualOverrides
        val json = BackupCodec.encode(emptyList(), base)
        val decoded = BackupCodec.decode(json).getOrThrow()
        assertEquals(base.manualOverrides, decoded.settings.manualOverrides)
    }

    @Test
    fun `cellular watchdog ignores single burst and dead handover pauses and sends dual icmp probes`() {
        val watchdog = StallWatchdogEvaluator(
            probeThresholdSec = 6,
            stallThresholdSec = 12,
            minUnansweredTxBytes = 1200L,
            minUnansweredTxPackets = 8,
            minTxActiveTicks = 2,
            cooldownSec = 25,
        )

        // 1) Single-tick stray UDP/QUIC burst (only 1 TX-active tick) must NOT false-trigger RECONNECT
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 2000L, tunnelTxDeltaPackets = 12))
        repeat(15) {
            assertFalse(
                "Single-tick burst must not trigger false reconnect",
                watchdog.evaluateTick(0L, 0L, tunnelTxDeltaPackets = 0) == WatchdogAction.RECONNECT,
            )
        }

        // 2) LTE handover / dead spot (hasPhysicalNetwork = false) pauses and clears without firing
        watchdog.reset()
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 900L, tunnelTxDeltaPackets = 5, hasPhysicalNetwork = true))
        assertEquals(WatchdogAction.NONE, watchdog.evaluateTick(0L, 900L, tunnelTxDeltaPackets = 5, hasPhysicalNetwork = true))
        repeat(14) {
            assertEquals(
                WatchdogAction.NONE,
                watchdog.evaluateTick(0L, 500L, tunnelTxDeltaPackets = 3, hasPhysicalNetwork = false),
            )
        }

        // 3) Sustained genuine freeze with >= 2 TX ticks fires dual ICMP probes and then RECONNECT at 12s
        watchdog.reset()
        var probeCount = 0
        var reconnectCount = 0
        for (sec in 1..12) {
            val txBytes = if (sec in 1..3) 600L else 0L
            val txPkts = if (sec in 1..3) 4 else 0
            when (watchdog.evaluateTick(0L, txBytes, tunnelTxDeltaPackets = txPkts, hasPhysicalNetwork = true)) {
                WatchdogAction.SEND_PROBE -> probeCount++
                WatchdogAction.RECONNECT -> reconnectCount++
                WatchdogAction.NONE -> Unit
            }
        }
        assertEquals("Expected dual ICMP probes on 6s/12s window", 2, probeCount)
        assertEquals("Expected RECONNECT at 12s", 1, reconnectCount)
    }

    @Test
    fun `v2_1 flexible network preset codec round-trips all unbound fields and applyPreset respects loose coupling and forceOverride`() {
        val flexiblePreset = NetworkPreset(
            id = "custom_unbound_v21",
            name = "Гибкий Профиль 2.1",
            trigger = NetworkPresetTrigger.MANUAL,
            overrideBypass = false,
            bypassMethod = BypassMethod.TLS_OBFUSCATION,
            sniSpoofingMode = SniSpoofingMode.SNI_REALITY_SAFARI_26_5,
            customSni = "should-not-apply.ru",
            overrideSlidingTunnel = true,
            slidingTunnelMode = SlidingTunnelMode.TRIPLE_TUNNEL,
            dnsModeOverride = DnsMode.CUSTOM_IPV4,
            enableCustomDnsUrl = false,
            customDnsUrl = "",
            dnsPrimaryOverride = "9.9.9.9",
            dnsSecondaryOverride = "149.112.112.112",
            overrideWatchdog = true,
            enableStallWatchdog = true,
            watchdogProbeSec = 5,
            watchdogStallSec = 11,
            watchdogCooldownSec = 22,
            overrideReconnect = true,
            reconnectOnFailure = true,
            reconnectOnNetworkChange = false,
            maxReconnectAttempts = 20,
            reconnectDelaySec = 3,
            handshakeTimeoutSec = 11,
            wsOpenTimeoutSec = 18,
            autoRotateSniOnFail = true,
            autoFailoverServer = false,
            overrideMtu = true,
            tunMtu = 1360,
            smartRfRouting = true,
            bypassRegionOverride = BypassRegion.CHINA,
            allowIpv6Override = true,
            useRootModeOverride = false,
            preferredServerId = 42L,
            forceOverrideManualLocks = true,
            isBuiltIn = false,
        )

        val encoded = NetworkPresetCodec.encode(listOf(flexiblePreset))
        val decoded = NetworkPresetCodec.decode(encoded)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.size)
        assertEquals(flexiblePreset, decoded.first())

        // Base settings have manual locks on DNS + CUSTOM_SNI and a user Smart DNS
        val base = VpnSettings()
            .withDnsMode(DnsMode.SMART_DNS_DOH, "https://magicinfo.art/dns-query")
            .copy(
                bypassMethod = BypassMethod.SNI_REALITY,
                customSni = "keep-my-sni.ru",
                manualOverrides = setOf(
                    com.example.domain.model.ManualOverrideKey.DNS,
                    com.example.domain.model.ManualOverrideKey.CUSTOM_SNI,
                ),
            )

        // Because overrideBypass = false, bypass/SNI MUST remain untouched;
        // because forceOverrideManualLocks = true & dnsModeOverride = CUSTOM_IPV4, DNS & timeouts & region & server MUST apply!
        val applied = base.applyPreset(flexiblePreset, respectManualOverrides = true)
        assertEquals(BypassMethod.SNI_REALITY, applied.bypassMethod)
        assertEquals("keep-my-sni.ru", applied.customSni)
        assertEquals(SlidingTunnelMode.TRIPLE_TUNNEL, applied.slidingTunnelMode)
        assertEquals(DnsMode.CUSTOM_IPV4, applied.dnsMode)
        assertEquals("9.9.9.9", applied.dnsPrimary)
        assertEquals("149.112.112.112", applied.dnsSecondary)
        assertEquals(22, applied.watchdogCooldownSec)
        assertEquals(3, applied.reconnectDelaySec)
        assertEquals(11, applied.handshakeTimeoutSec)
        assertEquals(18, applied.wsOpenTimeoutSec)
        assertFalse(applied.autoFailoverServer)
        assertEquals(BypassRegion.CHINA, applied.bypassRegion)
        assertEquals(true, applied.allowIpv6)
        assertEquals(42L, applied.selectedServerId)
        assertFalse(applied.autoSelectBestServer)

        // Preset with dnsModeOverride == null ("Не менять") MUST preserve user's Smart DNS even when forceOverrideManualLocks = true
        val noDnsTouchPreset = flexiblePreset.copy(
            dnsModeOverride = null,
            enableCustomDnsUrl = false,
            customDnsUrl = "",
        )
        val preservedDns = base.applyPreset(noDnsTouchPreset, respectManualOverrides = false)
        assertEquals(DnsMode.SMART_DNS_DOH, preservedDns.dnsMode)
        assertTrue(preservedDns.enableCustomDnsUrl)
        assertEquals("https://magicinfo.art/dns-query", preservedDns.customDnsUrl)
    }

    @Test
    fun `world flag matrix excludes all non-country symbolic emoji flags and special detector matches them`() {
        val specials = listOf(
            CountryFlagResolver.CODE_PIRATE,
            CountryFlagResolver.CODE_PRIDE,
            CountryFlagResolver.CODE_TRANS,
            CountryFlagResolver.CODE_CHECKERED,
            CountryFlagResolver.CODE_WHITE_FLAG,
            CountryFlagResolver.CODE_BLACK_FLAG,
            CountryFlagResolver.CODE_RED_FLAG,
        )
        specials.forEach { code ->
            assertTrue("Expected $code to be classified as special non-country flag", CountryFlagResolver.isSpecialNonCountryFlag(code))
            assertFalse(
                "World Flag Matrix pool MUST NOT contain non-country symbolic flag $code",
                CountryFlagResolver.WORLD_MATRIX_COUNTRY_CODES.contains(code),
            )
        }
        listOf("RU", "DE", "NL", "US", "JP", "GB", "FR").forEach { sovereign ->
            assertFalse("Expected $sovereign to be sovereign, not special", CountryFlagResolver.isSpecialNonCountryFlag(sovereign))
        }
        assertTrue("World matrix pool should have >= 40 countries", CountryFlagResolver.WORLD_MATRIX_COUNTRY_CODES.size >= 40)
    }

    @Test
    fun `gentle cellular watchdog tolerates moscow lte micro-stalls and single-tick send error bursts`() {
        val watchdog = StallWatchdogEvaluator.forSensitivity(
            sensitivity = com.example.domain.vpn.WatchdogSensitivity.GENTLE,
            baseProbeSec = 10,
            baseStallSec = 24,
            baseCooldownSec = 35,
            isCellular = true,
        )

        // Single-tick burst of 15 send errors MUST NOT kill a live LTE tunnel
        assertEquals(
            WatchdogAction.NONE,
            watchdog.evaluateTick(tunnelRxDeltaBytes = 0L, tunnelTxDeltaBytes = 1024L, tunnelTxDeltaPackets = 8, sendFailureDelta = 15),
        )
        // Next tick receives response -> resets cleanly
        assertEquals(
            WatchdogAction.NONE,
            watchdog.evaluateTick(tunnelRxDeltaBytes = 512L, tunnelTxDeltaBytes = 128L, tunnelTxDeltaPackets = 1, sendFailureDelta = 0),
        )

        // Brief 12s LTE silence in central Moscow -> still NONE or at most SEND_PROBE, NEVER RECONNECT!
        var gotReconnectDuringBriefLteJitter = false
        repeat(12) {
            val action = watchdog.evaluateTick(
                tunnelRxDeltaBytes = 0L,
                tunnelTxDeltaBytes = 400L,
                tunnelTxDeltaPackets = 2,
                hasPhysicalNetwork = true,
            )
            if (action == WatchdogAction.RECONNECT) gotReconnectDuringBriefLteJitter = true
        }
        assertFalse("GENTLE cellular watchdog must never false-reconnect during 12s LTE jitter", gotReconnectDuringBriefLteJitter)

        // Valid 61-byte IPv4 + UDP:53 DNS keepalive packet builder
        val dnsPkt = StallWatchdogEvaluator.buildIpv4UdpDnsKeepalivePacket(
            sourceIpv4 = "10.10.0.2",
            destIpv4 = "1.1.1.1",
            txId = 0x5A3C,
        )
        assertNotNull(dnsPkt)
        dnsPkt!!
        assertEquals(61, dnsPkt.size)
        assertEquals(0x45, dnsPkt[0].toInt() and 0xFF) // IPv4, IHL=5
        assertEquals(17, dnsPkt[9].toInt() and 0xFF) // Protocol = UDP
        val destPort = ((dnsPkt[22].toInt() and 0xFF) shl 8) or (dnsPkt[23].toInt() and 0xFF)
        assertEquals(53, destPort)
        val udpChecksum = ((dnsPkt[26].toInt() and 0xFF) shl 8) or (dnsPkt[27].toInt() and 0xFF)
        assertTrue("UDP checksum must be non-zero", udpChecksum != 0)
    }

    @Test
    fun `weather repository distinguishes drizzle rain heavy rain snow and storm wmo codes`() {
        assertEquals(com.example.domain.model.WeatherEffectMode.DRIZZLE, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(51))
        assertEquals(com.example.domain.model.WeatherEffectMode.DRIZZLE, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(53))
        assertEquals(com.example.domain.model.WeatherEffectMode.DRIZZLE, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(55))
        assertEquals(com.example.domain.model.WeatherEffectMode.RAIN, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(61))
        assertEquals(com.example.domain.model.WeatherEffectMode.RAIN, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(63))
        assertEquals(com.example.domain.model.WeatherEffectMode.RAIN, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(80))
        assertEquals(com.example.domain.model.WeatherEffectMode.HEAVY_RAIN, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(65))
        assertEquals(com.example.domain.model.WeatherEffectMode.HEAVY_RAIN, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(81))
        assertEquals(com.example.domain.model.WeatherEffectMode.HEAVY_RAIN, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(82))
        assertEquals(com.example.domain.model.WeatherEffectMode.SNOW, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(71))
        assertEquals(com.example.domain.model.WeatherEffectMode.STORM, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(95))
    }

    @Test
    fun `v22 backup codec v10 and network preset codec preserve solar intensity watchdog sensitivity keepalive debounce and extra preset fields`() {
        val richPreset = NetworkPreset(
            id = "preset_v22_test",
            name = "Москва 4G Про",
            trigger = NetworkPresetTrigger.MOBILE,
            overrideBypass = true,
            bypassMethod = BypassMethod.TLS_OBFUSCATION,
            sniSpoofingMode = SniSpoofingMode.SNI_REALITY_CHROME_149,
            customSni = "ya.ru",
            overrideSlidingTunnel = true,
            slidingTunnelMode = SlidingTunnelMode.DUAL_TUNNEL,
            overrideWatchdog = true,
            enableStallWatchdog = true,
            watchdogSensitivity = com.example.domain.vpn.WatchdogSensitivity.GENTLE,
            watchdogProbeSec = 12,
            watchdogStallSec = 30,
            watchdogCooldownSec = 45,
            overrideReconnect = true,
            keepaliveIntervalSecOverride = 15,
            networkSwitchDebounceSecOverride = 5,
            smartRfRoutingOverride = false,
            splitTunnelModeOverride = SplitTunnelMode.EXCLUDE_SELECTED,
            autoPingIntervalSecOverride = 120,
            serverSortOrderOverride = ServerSortOrder.BY_PING,
            overrideMtu = true,
            tunMtu = 1360,
            isBuiltIn = false,
        )

        val encodedPreset = NetworkPresetCodec.encode(listOf(richPreset))
        val decodedPreset = NetworkPresetCodec.decode(encodedPreset)
        assertNotNull(decodedPreset)
        assertEquals(richPreset, decodedPreset!!.first())

        val settings = VpnSettings(
            solarAtmosphereIntensity = 1.65f,
            watchdogSensitivity = com.example.domain.vpn.WatchdogSensitivity.BALANCED,
            keepaliveIntervalSec = 22,
            networkSwitchDebounceSec = 6,
            networkPresets = listOf(richPreset),
        )
        val encodedBackup = BackupCodec.encode(emptyList(), settings)
        val decodedBackup = BackupCodec.decode(encodedBackup).getOrThrow()
        assertEquals(1.65f, decodedBackup.settings.solarAtmosphereIntensity, 0.001f)
        assertEquals(com.example.domain.vpn.WatchdogSensitivity.BALANCED, decodedBackup.settings.watchdogSensitivity)
        assertEquals(22, decodedBackup.settings.keepaliveIntervalSec)
        assertEquals(6, decodedBackup.settings.networkSwitchDebounceSec)
        assertEquals(richPreset, decodedBackup.settings.networkPresets.first())

        // Verify pinned manualOverrides remain untouchable while auto-switch applies preset fields
        val withPinnedDnsAndSni = VpnSettings(
            customSni = "pinned-sni.ru",
            manualOverrides = setOf(
                com.example.domain.model.ManualOverrideKey.CUSTOM_SNI,
                com.example.domain.model.ManualOverrideKey.DNS,
            ),
        ).withDnsMode(DnsMode.SMART_DNS_DOH, "https://magicinfo.art/dns-query")

        val afterAutoSwitch = withPinnedDnsAndSni.applyPreset(richPreset, respectManualOverrides = true)
        assertEquals("pinned-sni.ru", afterAutoSwitch.customSni)
        assertEquals(DnsMode.SMART_DNS_DOH, afterAutoSwitch.dnsMode)
        assertEquals(1360, afterAutoSwitch.tunMtu)
        assertEquals(15, afterAutoSwitch.keepaliveIntervalSec)
        assertEquals(5, afterAutoSwitch.networkSwitchDebounceSec)
        assertEquals(SplitTunnelMode.EXCLUDE_SELECTED, afterAutoSwitch.splitTunnelMode)
        assertEquals(120, afterAutoSwitch.autoPingIntervalSec)
        assertEquals(ServerSortOrder.BY_PING, afterAutoSwitch.serverSortOrder)
    }

    @Test
    fun `official coat of arms catalog covers 50 plus countries with wikimedia commons urls`() {
        val catalog = com.example.data.remote.CoatOfArmsRepository.OFFICIAL_COAT_OF_ARMS_CATALOG
        assertTrue("Expected at least 50 official coats of arms, got ${catalog.size}", catalog.size >= 50)
        listOf("RU", "DE", "NL", "FI", "US", "GB", "FR", "ES", "IT", "JP", "KZ", "UA", "PL").forEach { cc ->
            val spec = catalog[cc]
            assertNotNull("Missing official coat of arms for $cc", spec)
            val url = com.example.data.remote.CoatOfArmsRepository.buildDownloadUrl(spec!!)
            assertTrue("Expected Wikimedia Special:FilePath URL for $cc: $url", url.startsWith("https://commons.wikimedia.org/wiki/Special:FilePath/"))
            assertTrue("Expected 512px HD coat of arms URL for $cc: $url", url.contains("width=512"))
        }
    }

    @Test
    fun `v23 world flag matrix pool covers 190 plus sovereign countries with non-repeating grid stride and direction vectors`() {
        val pool = CountryFlagResolver.WORLD_MATRIX_COUNTRY_CODES
        assertTrue("Expected >= 190 sovereign countries in world matrix pool, got ${pool.size}", pool.size >= 190)
        assertEquals("World matrix pool must not contain duplicate country codes", pool.size, pool.distinct().size)
        pool.forEach { code ->
            assertFalse("Symbolic flag $code must never appear in sovereign matrix pool", CountryFlagResolver.isSpecialNonCountryFlag(code))
        }

        // Verify a 9x7 visible grid window has ZERO duplicate countries using prime stride 17
        val visible = mutableSetOf<String>()
        for (r in -3..3) {
            for (c in -4..4) {
                val cc = com.example.core.ui.flags.mapMatrixCellToCountry(c, r, pool)
                assertTrue("Duplicate country $cc found in visible 9x7 matrix window at ($c,$r)", visible.add(cc))
            }
        }
        assertEquals(63, visible.size)

        // Verify every FlagMatrixDirection resolves to a non-zero motion vector
        com.example.domain.model.FlagMatrixDirection.entries.forEach { dir ->
            val (vx, vy) = dir.resolveVector(kotlin.random.Random(42))
            assertTrue("Direction $dir must have non-zero magnitude", kotlin.math.hypot(vx, vy) > 0.5f)
        }
    }

    @Test
    fun `v23 backup codec v11 preserves matrix settings and official 1400 mtu default`() {
        val defaults = VpnSettings()
        assertEquals("Official FPTN server default MTU must be 1400", 1400, defaults.tunMtu)
        assertEquals(1400, VpnSettings.DEFAULT_NETWORK_PRESETS.first { it.id == "preset_wifi" }.tunMtu)

        val custom = VpnSettings(
            flagMatrixDirection = com.example.domain.model.FlagMatrixDirection.DIAGONAL_NE,
            flagMatrixSpeed = 1.65f,
            flagMatrixZoom = 1.25f,
            flagMatrixHighlightServers = false,
        )
        val encoded = BackupCodec.encode(emptyList(), custom)
        val decoded = BackupCodec.decode(encoded).getOrThrow()
        assertEquals(com.example.domain.model.FlagMatrixDirection.DIAGONAL_NE, decoded.settings.flagMatrixDirection)
        assertEquals(1.65f, decoded.settings.flagMatrixSpeed, 0.001f)
        assertEquals(1.25f, decoded.settings.flagMatrixZoom, 0.001f)
        assertFalse(decoded.settings.flagMatrixHighlightServers)
    }

    @Test
    fun `v23 tunnel doh resolver synthesizes valid rfc nodata reply for aaaa queries and skips a queries`() {
        fun buildIpv4DnsQueryPacket(qtype: Int): ByteArray {
            // Domain: "youtube.com" -> 7 youtube 3 com 0
            val dns = byteArrayOf(
                0xAB.toByte(), 0xCD.toByte(), // TXID
                0x01, 0x00, // Standard query, RD=1
                0x00, 0x01, // QDCOUNT=1
                0x00, 0x00, // ANCOUNT=0
                0x00, 0x00, // NSCOUNT=0
                0x00, 0x00, // ARCOUNT=0
                7, 'y'.code.toByte(), 'o'.code.toByte(), 'u'.code.toByte(), 't'.code.toByte(), 'u'.code.toByte(), 'b'.code.toByte(), 'e'.code.toByte(),
                3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
                0,
                ((qtype ushr 8) and 0xFF).toByte(), (qtype and 0xFF).toByte(),
                0x00, 0x01, // QCLASS=IN
            )
            val totalLen = 20 + 8 + dns.size
            val pkt = ByteArray(totalLen)
            pkt[0] = 0x45.toByte()
            pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            pkt[9] = 17.toByte() // UDP
            pkt[12] = 10; pkt[13] = 10; pkt[14] = 0; pkt[15] = 1
            pkt[16] = 1; pkt[17] = 1; pkt[18] = 1; pkt[19] = 1
            pkt[20] = 0xC1.toByte(); pkt[21] = 0x11.toByte()
            pkt[22] = 0x00; pkt[23] = 53.toByte()
            val udpLen = 8 + dns.size
            pkt[24] = ((udpLen ushr 8) and 0xFF).toByte()
            pkt[25] = (udpLen and 0xFF).toByte()
            System.arraycopy(dns, 0, pkt, 28, dns.size)
            return pkt
        }

        val aaaaPkt = buildIpv4DnsQueryPacket(qtype = 28)
        val aPkt = buildIpv4DnsQueryPacket(qtype = 1)

        assertEquals(4, TunnelDohResolver.getIpVersion(aaaaPkt, aaaaPkt.size))
        assertEquals(6, TunnelDohResolver.getIpVersion(byteArrayOf(0x60.toByte(), 0, 0, 0), 4))

        val nodataReply = TunnelDohResolver.trySynthesizeEmptyAaaaReply(aaaaPkt, aaaaPkt.size)
        assertNotNull("AAAA query must produce instant NODATA reply", nodataReply)
        nodataReply!!
        // Verify QR=1, RD=1, RA=1, RCODE=0 (NOERROR), ANCOUNT=0, TXID preserved
        assertEquals(0xAB, nodataReply[28].toInt() and 0xFF)
        assertEquals(0xCD, nodataReply[29].toInt() and 0xFF)
        assertEquals(0x81, nodataReply[30].toInt() and 0xFF)
        assertEquals(0x80, nodataReply[31].toInt() and 0xFF)
        assertEquals(0, nodataReply[34].toInt() and 0xFF)
        assertEquals(0, nodataReply[35].toInt() and 0xFF)

        assertEquals("IPv4 A query must NOT be blocked", null, TunnelDohResolver.trySynthesizeEmptyAaaaReply(aPkt, aPkt.size))
    }

    @Test
    fun `v23 vpn error classifier heals tls timeouts and resets while never healing auth 401 403 or port 609`() {
        assertTrue(VpnErrorClassifier.isHealableConnectionError(600, "stream truncated"))
        assertTrue(VpnErrorClassifier.isHealableConnectionError(0, "Operation timed out"))
        assertTrue(VpnErrorClassifier.isHealableConnectionError(-1, "Connection reset by peer"))
        assertFalse(VpnErrorClassifier.isHealableConnectionError(401, "Unauthorized"))
        assertFalse(VpnErrorClassifier.isHealableConnectionError(403, "Forbidden"))
        assertFalse(VpnErrorClassifier.isHealableConnectionError(609, "Port not reachable"))
        assertFalse(VpnErrorClassifier.isHealableConnectionError(502, "Bad Gateway"))
    }

    @Test
    fun `v24 official reserved subnets are excluded while internal server dns gets explicit host route`() {
        val routes = SmartRfRouter.computeEffectiveVpnRoutes(
            smartRfEnabled = true,
            vpnServerIpv4 = "198.51.100.25",
            vpnDnsIpv4s = setOf("172.20.0.1", "10.10.0.2"),
        )
        val lanRouter = CidrBlock.parseIpv4("192.168.1.1")!!
        val serverNatOther = CidrBlock.parseIpv4("172.20.0.55")!!
        val localTunOther = CidrBlock.parseIpv4("10.10.0.99")!!
        val internalDns1 = CidrBlock.parseIpv4("172.20.0.1")!!
        val internalDns2 = CidrBlock.parseIpv4("10.10.0.2")!!
        val publicGoogle = CidrBlock.parseIpv4("8.8.8.8")!!

        assertFalse("LAN 192.168.1.1 must be excluded from VPN", routes.any { it.contains(lanRouter) })
        assertFalse("FPTN server NAT 172.20.0.55 must be excluded from VPN", routes.any { it.contains(serverNatOther) })
        assertFalse("Local TUN subnet 10.10.0.99 must be excluded from VPN", routes.any { it.contains(localTunOther) })
        assertTrue("Internal FPTN server DNS 172.20.0.1 must have explicit /32 VPN route", routes.any { it.prefixLength == 32 && it.contains(internalDns1) })
        assertTrue("Internal FPTN server DNS 10.10.0.2 must have explicit /32 VPN route", routes.any { it.prefixLength == 32 && it.contains(internalDns2) })
        assertTrue("Public IP 8.8.8.8 must route via VPN", routes.any { it.contains(publicGoogle) })

        assertEquals(3, SmartRfRouter.officialReservedIpv6Cidrs.size)
        assertTrue(SmartRfRouter.officialReservedIpv6Cidrs.any { it.first == "fd00::" && it.second == 64 })
        assertTrue(SmartRfRouter.officialReservedIpv6Cidrs.any { it.first == "fc00:1::" && it.second == 64 })
        assertTrue(SmartRfRouter.officialReservedIpv6Cidrs.any { it.first == "fe80::" && it.second == 10 })
    }

    @Test
    fun `v24 all sni spoofing modes map strictly to strategies compiled into libfptn_native_lib`() {
        val supportedNativeStrategies = setOf(
            "SNI",
            "SNI-REALITY-CHROME-145",
            "SNI-REALITY-CHROME-146",
            "SNI-REALITY-CHROME-149",
            "SNI-REALITY-FIREFOX-149",
            "SNI-REALITY-FIREFOX-150",
            "SNI-REALITY-FIREFOX-151",
            "SNI-REALITY-SAFARI-26-4",
            "SNI-REALITY-SAFARI-26-5",
            "SNI-REALITY-YANDEX-24",
            "SNI-REALITY-YANDEX-25",
            "SNI-REALITY-YANDEX-26-3",
            "SNI-REALITY-YANDEX-26-4",
        )
        for (mode in SniSpoofingMode.entries) {
            val mapped = mode.nativeStrategyName()
            assertTrue(
                "Mode $mode mapped to unsupported native strategy '$mapped'",
                mapped in supportedNativeStrategies,
            )
        }
        assertEquals("SNI-REALITY-CHROME-149", SniSpoofingMode.SNI_REALITY_CHROME_148.nativeStrategyName())
        assertEquals("SNI-REALITY-CHROME-146", SniSpoofingMode.SNI_REALITY_CHROME_147.nativeStrategyName())

        val presets = VpnSettings.generateRandomizedDefaultPresets(listOf("ads.x5.ru", "ya.ru", "vk.com", "ozon.ru"), 42L)
        assertTrue(presets.all { it.sniSpoofingMode.nativeStrategyName() in supportedNativeStrategies })
    }

    @Test
    fun `v25 safe root rule planner bypasses pixel networkstack uid clamps tcpmss marks internal dns before rfc1918 and builds handover refresh`() {
        val apply = SafeRootRulePlanner.buildApplyCommands(
            appUid = 10245,
            vpnServerIpv4 = "203.0.113.55",
            tunInterfaceName = "fptn0",
            smartRfEnabled = true,
            excludedUids = setOf(10300),
            systemBypassUids = setOf(SafeRootRulePlanner.AID_NETWORK_STACK, 10099),
            clientTunIpv4 = "10.10.0.1",
            dnsServerIpv4 = "172.20.0.1",
        )

        val violations = SafeRootRulePlanner.validateSafety(apply)
        assertTrue("Unexpected safety violations: $violations", violations.isEmpty())

        // Pixel NetworkStack UID (1073) & CaptivePortalLogin UID (10099) must bypass BOTH FPTN_OUTPUT and FPTN_DNS_NAT
        assertTrue(apply.any { it.contains("FPTN_OUTPUT") && it.contains("--uid-owner 1073 -j RETURN") })
        assertTrue(apply.any { it.contains("FPTN_DNS_NAT") && it.contains("--uid-owner 1073 -j RETURN") })
        assertTrue(apply.any { it.contains("FPTN_OUTPUT") && it.contains("--uid-owner 10099 -j RETURN") })
        assertTrue(apply.any { it.contains("FPTN_DNS_NAT") && it.contains("--uid-owner 10099 -j RETURN") })

        // Internal FPTN server DNS (172.20.0.1/32) MUST be marked BEFORE RFC1918 172.16.0.0/12 bypass!
        val dnsMarkIdx = apply.indexOfFirst { it.contains("-d 172.20.0.1/32") && it.contains("--set-xmark") }
        val rfc172Idx = apply.indexOfFirst { it.contains("-d 172.16.0.0/12") && it.contains("-j RETURN") }
        assertTrue("Internal DNS mark rule missing", dnsMarkIdx >= 0)
        assertTrue("RFC1918 172.16.0.0/12 bypass rule missing", rfc172Idx >= 0)
        assertTrue("Internal DNS mark must precede RFC1918 bypass", dnsMarkIdx < rfc172Idx)

        // TCP MSS clamping on fptn0 prevents large media/image/Play Store packets from blackholing
        assertTrue(apply.any { it.contains("FPTN_MSS") && it.contains("--clamp-mss-to-pmtu") })
        // TCP/53 DNS DNAT alongside UDP/53 handles truncated >512B CDN/Play Store DNS replies
        assertTrue(apply.any { it.contains("FPTN_DNS_NAT") && it.contains("-p tcp --dport 53 -j DNAT --to-destination 172.20.0.1:53") })

        val refresh = SafeRootRulePlanner.buildNetworkRefreshCommands(
            tunInterfaceName = "fptn0",
            clientTunIpv4 = "10.10.0.1",
            mtu = 1360,
        )
        assertTrue(refresh.any { it.contains("mtu 1360") })
        assertTrue(refresh.any { it.contains("ip route flush cache") })

        val cleanup = SafeRootRulePlanner.buildCleanupCommands()
        assertTrue(cleanup.any { it.contains("-D POSTROUTING -j FPTN_MSS") })
        assertTrue(cleanup.any { it.contains("-X FPTN_MSS") })
    }

    @Test
    fun `v25 tunnel doh resolver computes valid non-zero rfc768 udp checksum normalizes question keys and supports high media concurrency`() {
        assertTrue("DoH maxRequestsPerHost must be >= 64 for burst media/CDN traffic", TunnelDohResolver.MAX_REQUESTS_PER_HOST >= 64)
        assertTrue("DoH maxConcurrentRequests must be >= 128", TunnelDohResolver.MAX_CONCURRENT_REQUESTS >= 128)
        assertTrue("DoH cache must hold >= 1024 entries", TunnelDohResolver.MAX_CACHE_ENTRIES >= 1024)

        val query = ByteArray(40)
        query[0] = 0x45.toByte()
        query[2] = 0x00; query[3] = 40.toByte()
        query[9] = 17.toByte()
        query[12] = 10; query[13] = 10; query[14] = 0; query[15] = 2
        query[16] = 1; query[17] = 1; query[18] = 1; query[19] = 1
        query[20] = 0xC8.toByte(); query[21] = 0x22.toByte()
        query[22] = 0x00; query[23] = 53.toByte()
        query[24] = 0x00; query[25] = 20.toByte()

        val dnsReply = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0)
        val replyPacket = TunnelDohResolver.buildIpv4UdpReplyPacket(query, 20, dnsReply)
        val udpChecksum = ((replyPacket[26].toInt() and 0xFF) shl 8) or (replyPacket[27].toInt() and 0xFF)
        assertTrue("RFC-768 UDP checksum must be computed and non-zero (was 0x0000)", udpChecksum != 0)

        // Two DNS queries for the same domain with DIFFERENT TXIDs and mixed case must produce IDENTICAL cache/singleflight keys
        val q1 = byteArrayOf(
            0x11, 0x22, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            3, 'C'.code.toByte(), 'D'.code.toByte(), 'N'.code.toByte(),
            4, 'T'.code.toByte(), 'E'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte(),
            0, 0x00, 0x01, 0x00, 0x01,
        )
        val q2 = byteArrayOf(
            0x99.toByte(), 0xAA.toByte(), 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            3, 'c'.code.toByte(), 'd'.code.toByte(), 'n'.code.toByte(),
            4, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            0, 0x00, 0x01, 0x00, 0x01,
        )
        assertEquals("cdn.test|1|1", TunnelDohResolver.extractNormalizedQuestionKey(q1))
        assertEquals(
            TunnelDohResolver.extractNormalizedQuestionKey(q1),
            TunnelDohResolver.extractNormalizedQuestionKey(q2),
        )
    }

    @Test
    fun `v25 disconnect anti-bounce guard blocks accidental double-tap reconnect during disconnecting and post-disconnect cooldown`() {
        val cooldown = com.example.feature.shared.FptnViewModel.DISCONNECT_RECONNECT_COOLDOWN_MS
        assertTrue("Cooldown should be >= 800ms", cooldown >= 800L)

        // Always blocked while actively Disconnecting
        assertTrue(
            com.example.feature.shared.FptnViewModel.isConnectBlockedByDisconnectCooldown(
                state = com.example.domain.model.VpnConnectionState.Disconnecting(),
                nowElapsedMs = 50_000L,
                lastDisconnectRequestElapsedMs = 1_000L,
                lastDisconnectedAtElapsedMs = 1_000L,
            ),
        )

        // Blocked within cooldown right after user tapped Disconnect
        assertTrue(
            com.example.feature.shared.FptnViewModel.isConnectBlockedByDisconnectCooldown(
                state = com.example.domain.model.VpnConnectionState.Disconnected,
                nowElapsedMs = 10_400L,
                lastDisconnectRequestElapsedMs = 10_000L,
                lastDisconnectedAtElapsedMs = 0L,
            ),
        )

        // Blocked within cooldown right after teardown finished transitioning to Disconnected
        assertTrue(
            com.example.feature.shared.FptnViewModel.isConnectBlockedByDisconnectCooldown(
                state = com.example.domain.model.VpnConnectionState.Disconnected,
                nowElapsedMs = 12_600L,
                lastDisconnectRequestElapsedMs = 10_000L,
                lastDisconnectedAtElapsedMs = 12_000L,
            ),
        )

        // Allowed cleanly once cooldown has elapsed
        assertFalse(
            com.example.feature.shared.FptnViewModel.isConnectBlockedByDisconnectCooldown(
                state = com.example.domain.model.VpnConnectionState.Disconnected,
                nowElapsedMs = 15_000L,
                lastDisconnectRequestElapsedMs = 10_000L,
                lastDisconnectedAtElapsedMs = 12_000L,
            ),
        )
    }

    @Test
    fun `v26 selinux policy rules replace self target with concrete app context to eliminate magiskpolicy errors`() {
        val rules = com.example.vpn.root.RootStealthTunOpener.buildSelinuxPolicyRules(
            appSelinuxType = "untrusted_app",
            suSelinuxType = "magisk",
        )
        assertTrue("Expected live SELinux rules to be non-empty", rules.size >= 6)
        for (rule in rules) {
            assertFalse(
                "SELinux live rule must NEVER use 'self' target (causes magiskpolicy 'target type self does not exist'): $rule",
                Regex("""\bself\b""").containsMatchIn(rule),
            )
            assertTrue(
                "SELinux live rule should reference app or su domain: $rule",
                rule.contains("untrusted_app") || rule.contains("magisk"),
            )
        }
        assertTrue(
            "Must allow untrusted_app -> untrusted_app tun_socket create/attach_queue",
            rules.any { it.contains("allow untrusted_app untrusted_app tun_socket") && it.contains("attach_queue") },
        )
        assertTrue(
            "Must allow untrusted_app -> magisk tun_socket relabelfrom/relabelto",
            rules.any { it.contains("allow untrusted_app magisk tun_socket") && it.contains("relabelto") },
        )
    }

    @Test
    fun `v26 safe root rule planner supports secondary vpn chaining custom tun interface and sanitization`() {
        assertEquals("fptn0", SafeRootRulePlanner.sanitizeTunName(""))
        assertEquals("fptn0", SafeRootRulePlanner.sanitizeTunName("tun0"))
        assertEquals("fptn0", SafeRootRulePlanner.sanitizeTunName("tun3"))
        assertEquals("fptn0", SafeRootRulePlanner.sanitizeTunName("fptn;rm -rf /"))
        assertEquals("fptn1", SafeRootRulePlanner.sanitizeTunName("fptn1"))
        assertEquals("wg_fptn0", SafeRootRulePlanner.sanitizeTunName("wg_fptn0"))

        val chainedCmds = SafeRootRulePlanner.buildApplyCommands(
            appUid = 10556,
            vpnServerIpv4 = "132.243.186.40",
            tunInterfaceName = "fptn1",
            smartRfEnabled = true,
            excludedUids = setOf(10200),
            allowSecondaryVpnChaining = true,
        )
        assertTrue(
            "Chained commands must pass strict safety validator",
            SafeRootRulePlanner.validateSafety(chainedCmds).isEmpty(),
        )
        assertTrue(
            "Must use custom kernel TUN interface name fptn1",
            chainedCmds.any { it.contains("dev fptn1") },
        )
        assertTrue(
            "Must mark FPTN appUid with Android protectedFromVpn mark 0x20000/0x20000 to prevent loop when another VPN is active",
            chainedCmds.any { it.contains("--uid-owner 10556") && it.contains("--set-xmark 0x20000/0x20000") },
        )
        assertTrue(
            "Must let secondary non-Root VPN tun+ traffic bypass FPTN_OUTPUT mangle chain",
            chainedCmds.any { it.contains("-A FPTN_OUTPUT -o tun+ -j RETURN") },
        )
        assertTrue(
            "Must let secondary non-Root VPN tun+ DNS bypass FPTN_DNS_NAT chain",
            chainedCmds.any { it.contains("-A FPTN_DNS_NAT -o tun+ -j RETURN") },
        )

        val isolatedCmds = SafeRootRulePlanner.buildApplyCommands(
            appUid = 10556,
            vpnServerIpv4 = "132.243.186.40",
            tunInterfaceName = "fptn0",
            smartRfEnabled = true,
            allowSecondaryVpnChaining = false,
        )
        assertTrue(SafeRootRulePlanner.validateSafety(isolatedCmds).isEmpty())
        assertFalse(
            "When chaining disabled, -o tun+ bypass must not be emitted",
            isolatedCmds.any { it.contains("-o tun+ -j RETURN") },
        )
    }

    @Test
    fun `v26 world flag matrix covers all countries with zero gaps and thumbnail cache fits entire pool`() {
        val codes = com.example.domain.model.CountryFlagResolver.WORLD_MATRIX_COUNTRY_CODES
        assertTrue("World matrix should contain >= 195 ISO country codes (got ${codes.size})", codes.size >= 195)
        assertEquals("World matrix must have zero duplicate ISO codes", codes.size, codes.toSet().size)
        assertTrue("HK must be included in world flag pool", "HK" in codes)
        assertTrue(
            "Every world matrix entry must be a standard 2-letter ISO code",
            codes.all { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } },
        )
        assertTrue(
            "FlagTextureRenderer thumbnail LRU cache (${com.example.core.ui.flags.FlagTextureRenderer.THUMB_CACHE_CAPACITY}) must fit ALL ${codes.size} world flags simultaneously to prevent blank gaps",
            com.example.core.ui.flags.FlagTextureRenderer.THUMB_CACHE_CAPACITY >= codes.size,
        )
    }

    @Test
    fun `v26 backup codec preserves root chaining and custom tun interface name`() {
        val original = VpnSettings(
            useRootMode = true,
            allowSecondaryVpnChaining = false,
            rootTunInterfaceName = "fptn2",
        )
        val encoded = BackupCodec.encode(emptyList(), original)
        val decoded = BackupCodec.decode(encoded).getOrThrow()
        assertTrue(decoded.settings.useRootMode)
        assertFalse(decoded.settings.allowSecondaryVpnChaining)
        assertEquals("fptn2", decoded.settings.rootTunInterfaceName)
    }

    @Test
    fun `v27 clock dial angle math and backup codec v12 round-trip preserve 360 degree flag angles`() {
        assertEquals(0f, com.example.core.ui.components.normalizeClockAngle(360f), 0.001f)
        assertEquals(315f, com.example.core.ui.components.normalizeClockAngle(-45f), 0.001f)
        assertEquals(0f, com.example.core.ui.components.normalizeClockAngle(Float.NaN), 0.001f)

        // 0° = 12 o'clock (visual up -> camera moves down: (0, +1))
        val (camX0, camY0) = com.example.core.ui.components.clockDegreesToCameraVector(0f)
        assertEquals(0f, camX0, 0.001f)
        assertEquals(1f, camY0, 0.001f)

        // 90° = 3 o'clock (visual right -> camera moves left: (-1, 0))
        val (camX90, camY90) = com.example.core.ui.components.clockDegreesToCameraVector(90f)
        assertEquals(-1f, camX90, 0.001f)
        assertEquals(0f, camY90, 0.001f)

        assertEquals(135f, com.example.domain.model.FlagMatrixDirection.DIAGONAL_SE.toClockDegrees(), 0.001f)
        assertEquals(270f, com.example.domain.model.FlagMatrixDirection.RIGHT_TO_LEFT.toClockDegrees(), 0.001f)

        val original = VpnSettings(
            flagTiltDegrees = 225f,
            flagMatrixAngleDegrees = 315f,
            flagMatrixRandomAngle = true,
            flagAngleSnapToTicks = false,
        )
        val encoded = BackupCodec.encode(emptyList(), original)
        val decoded = BackupCodec.decode(encoded).getOrThrow()
        assertEquals(225f, decoded.settings.flagTiltDegrees, 0.01f)
        assertEquals(315f, decoded.settings.flagMatrixAngleDegrees, 0.01f)
        assertTrue(decoded.settings.flagMatrixRandomAngle)
        assertFalse(decoded.settings.flagAngleSnapToTicks)
    }

    @Test
    fun `v28 root planner always bypasses AID_ROOT uid 0 and AID_NETWORK_STACK in output and dns chains`() {
        assertTrue(
            "DEFAULT_SYSTEM_BYPASS_UIDS must include AID_ROOT (0)",
            SafeRootRulePlanner.AID_ROOT in SafeRootRulePlanner.DEFAULT_SYSTEM_BYPASS_UIDS,
        )
        assertTrue(
            "DEFAULT_SYSTEM_BYPASS_UIDS must include AID_NETWORK_STACK (1073)",
            SafeRootRulePlanner.AID_NETWORK_STACK in SafeRootRulePlanner.DEFAULT_SYSTEM_BYPASS_UIDS,
        )

        val cmds = SafeRootRulePlanner.buildApplyCommands(
            appUid = 10556,
            vpnServerIpv4 = "132.243.186.40",
            tunInterfaceName = "fptn0",
            smartRfEnabled = true,
        )
        assertTrue(SafeRootRulePlanner.validateSafety(cmds).isEmpty())
        assertTrue(
            "Must bypass uid 0 in FPTN_OUTPUT",
            cmds.any { it.contains("-A FPTN_OUTPUT -m owner --uid-owner 0 -j RETURN") },
        )
        assertTrue(
            "Must bypass uid 0 in FPTN_DNS_NAT",
            cmds.any { it.contains("-A FPTN_DNS_NAT -m owner --uid-owner 0 -j RETURN") },
        )
    }

    @Test
    fun `v28 cracked glass error segments and 42 complex shatter shards are precomputed and finite`() {
        val segments = com.example.core.ui.effects.CrackedGlassEffects.errorCrackSegments
        assertTrue("Error crack network must contain multiple fracture segments", segments.size >= 30)
        assertTrue(
            "All crack segment coordinates must be finite",
            segments.all {
                it.x0.isFinite() && it.y0.isFinite() && it.x1.isFinite() && it.y1.isFinite() &&
                    it.thicknessFactor > 0f && it.revealThreshold in 0f..1f
            },
        )

        val shards = com.example.core.ui.effects.CrackedGlassEffects.complexGlassShards
        assertEquals("Complex shatter must precompute 42 glass shards & micro-splinters", 42, shards.size)
        assertTrue(
            "Every shard polygon must have at least 3 vertices (even float array >= 6) with finite values",
            shards.all { shard ->
                shard.verticesNorm.size >= 6 &&
                    shard.verticesNorm.size % 2 == 0 &&
                    shard.verticesNorm.all { v -> v.isFinite() } &&
                    shard.vx.isFinite() && shard.vy.isFinite()
            },
        )
    }

    @Test
    fun `v29 device performance profile classifies low-end balanced and flagship tiers accurately`() {
        val lowEndBudget = com.example.core.performance.SystemHeadroomSnapshot(
            isLowRamDevice = true,
            isPowerSaveMode = false,
            isLowMemoryNow = false,
            availableRamRatio = 0.40f,
            thermalStatus = 0,
            normalizedLoadPerCore = 0.25f,
            totalRamMb = 2048L,
            cpuCores = 4,
            sdkInt = 29,
        )
        assertEquals(
            com.example.core.performance.DevicePerformanceTier.LOW_END,
            com.example.core.performance.DevicePerformanceProfile.evaluatePerformanceTier(lowEndBudget),
        )

        val reducedMotionFlagship = com.example.core.performance.SystemHeadroomSnapshot(
            isLowRamDevice = false,
            isPowerSaveMode = false,
            isLowMemoryNow = false,
            availableRamRatio = 0.65f,
            thermalStatus = 0,
            normalizedLoadPerCore = 0.15f,
            animatorDurationScale = 0f,
            totalRamMb = 12288L,
            cpuCores = 8,
            sdkInt = 35,
        )
        assertEquals(
            com.example.core.performance.DevicePerformanceTier.LOW_END,
            com.example.core.performance.DevicePerformanceProfile.evaluatePerformanceTier(reducedMotionFlagship),
        )

        val balancedMidrange = com.example.core.performance.SystemHeadroomSnapshot(
            isLowRamDevice = false,
            isPowerSaveMode = false,
            isLowMemoryNow = false,
            availableRamRatio = 0.50f,
            thermalStatus = 0,
            normalizedLoadPerCore = 0.30f,
            totalRamMb = 4096L,
            cpuCores = 8,
            sdkInt = 31,
        )
        assertEquals(
            com.example.core.performance.DevicePerformanceTier.BALANCED,
            com.example.core.performance.DevicePerformanceProfile.evaluatePerformanceTier(balancedMidrange),
        )

        val flagship = com.example.core.performance.SystemHeadroomSnapshot(
            isLowRamDevice = false,
            isPowerSaveMode = false,
            isLowMemoryNow = false,
            availableRamRatio = 0.60f,
            thermalStatus = 0,
            normalizedLoadPerCore = 0.20f,
            totalRamMb = 8192L,
            cpuCores = 8,
            sdkInt = 34,
        )
        assertEquals(
            com.example.core.performance.DevicePerformanceTier.FLAGSHIP,
            com.example.core.performance.DevicePerformanceProfile.evaluatePerformanceTier(flagship),
        )
    }

    @Test
    fun `v29 adaptive root boost yields immediately to games thermal pressure low ram and power save`() {
        val coolIdle = com.example.core.performance.SystemHeadroomSnapshot(
            isLowRamDevice = false,
            isPowerSaveMode = false,
            isLowMemoryNow = false,
            availableRamRatio = 0.55f,
            thermalStatus = 0,
            normalizedLoadPerCore = 0.18f,
        )
        assertEquals(
            com.example.core.performance.AdaptiveBoostTier.MAX_TURBO,
            com.example.core.performance.DevicePerformanceProfile.evaluateAdaptiveBoostTier(coolIdle),
        )

        val lightLoad = coolIdle.copy(normalizedLoadPerCore = 0.50f)
        assertEquals(
            com.example.core.performance.AdaptiveBoostTier.BALANCED,
            com.example.core.performance.DevicePerformanceProfile.evaluateAdaptiveBoostTier(lightLoad),
        )

        val heavyGameCpu = coolIdle.copy(normalizedLoadPerCore = 0.85f)
        assertEquals(
            com.example.core.performance.AdaptiveBoostTier.YIELD_TO_GAMES,
            com.example.core.performance.DevicePerformanceProfile.evaluateAdaptiveBoostTier(heavyGameCpu),
        )

        val warmThermal = coolIdle.copy(thermalStatus = 2)
        assertEquals(
            com.example.core.performance.AdaptiveBoostTier.YIELD_TO_GAMES,
            com.example.core.performance.DevicePerformanceProfile.evaluateAdaptiveBoostTier(warmThermal),
        )

        val tightRam = coolIdle.copy(availableRamRatio = 0.12f)
        assertEquals(
            com.example.core.performance.AdaptiveBoostTier.YIELD_TO_GAMES,
            com.example.core.performance.DevicePerformanceProfile.evaluateAdaptiveBoostTier(tightRam),
        )

        val batterySaver = coolIdle.copy(isPowerSaveMode = true)
        assertEquals(
            com.example.core.performance.AdaptiveBoostTier.YIELD_TO_GAMES,
            com.example.core.performance.DevicePerformanceProfile.evaluateAdaptiveBoostTier(batterySaver),
        )
    }

    @Test
    fun `v29 backup codec v13 preserves rootAdaptiveBoostEnabled toggle`() {
        val original = VpnSettings(
            useRootMode = true,
            rootAdaptiveBoostEnabled = true,
            rootTunInterfaceName = "fptn2",
        )
        val json = BackupCodec.encode(emptyList(), original)
        val decoded = BackupCodec.decode(json).getOrThrow()
        assertTrue(decoded.settings.rootAdaptiveBoostEnabled)
        assertEquals("fptn2", decoded.settings.rootTunInterfaceName)
    }

    @Test
    fun `v30 expanded russian bypass cidrs cover major domestic asns with zero telegram overlap and bounded routes`() {
        val ruCidrs = SmartRfRouter.russianBypassCidrs
        val tgCidrs = SmartRfRouter.telegramDcCidrs
        assertTrue("Expected expanded RU bypass CIDR table (>= 60 prefixes), got ${ruCidrs.size}", ruCidrs.size >= 60)

        for (ru in ruCidrs) {
            for (tg in tgCidrs) {
                val overlaps = ru.startIp <= tg.endIp && tg.startIp <= ru.endIp
                assertFalse(
                    "RU bypass CIDR ${ru.toCidrString()} must NEVER overlap Telegram DC ${tg.toCidrString()}",
                    overlaps,
                )
            }
        }

        // Major Russian services & domestic clouds must bypass VPN directly
        val ruServiceIps = listOf(
            "77.88.55.242", // Yandex
            "51.250.1.10", // Yandex Cloud
            "158.160.1.10", // Yandex Cloud
            "194.54.12.10", // Sberbank
            "195.208.10.10", // Gosuslugi / Rostelecom
            "185.71.65.10", // Wildberries / RU DC
        )
        for (ip in ruServiceIps) {
            assertEquals("Expected $ip to route DIRECT_RF", RouteDecision.DIRECT_RF, SmartRfRouter.decideForIpv4(ip, smartRfEnabled = true))
        }

        // Compute effective VPN routes with expanded table + extra direct IPs and ensure it stays cleanly bounded
        val routes = SmartRfRouter.computeEffectiveVpnRoutes(
            smartRfEnabled = true,
            vpnServerIpv4 = "198.51.100.25",
            extraDirectIpv4s = setOf("203.0.113.44", "203.0.113.45"),
            extraVpnIpv4s = setOf("198.51.100.88"),
        )
        assertTrue("Expected Binder-safe route count <= 1500, got ${routes.size}", routes.size <= 1500)
        assertFalse("Extra direct IP 203.0.113.44 must be excluded from VPN routes", routes.any { it.contains(CidrBlock.parseIpv4("203.0.113.44")!!) })
        assertTrue("Extra VPN IP 198.51.100.88 must stay in VPN routes", routes.any { it.contains(CidrBlock.parseIpv4("198.51.100.88")!!) })
        assertTrue("Telegram IP 149.154.167.50 must stay in VPN routes", routes.any { it.contains(CidrBlock.parseIpv4("149.154.167.50")!!) })
    }

    @Test
    fun `v30 domain variant expansion adds www for apex domains and skips subdomains`() {
        val expanded = SmartRfRouter.expandDomainVariants(
            setOf("ozon.ru", "www.sberbank.ru", "api.vk.com", "https://Avito.ru/path"),
        )
        assertTrue("ozon.ru" in expanded)
        assertTrue("www.ozon.ru" in expanded)
        assertTrue("avito.ru" in expanded)
        assertTrue("www.avito.ru" in expanded)
        assertTrue("www.sberbank.ru" in expanded)
        assertTrue("api.vk.com" in expanded)
        assertFalse("www.api.vk.com" in expanded)
    }

    @Test
    fun `v30 safe root rule planner honors custom direct vpn ips protects ru ips and builds dynamic bypass command`() {
        val apply = SafeRootRulePlanner.buildApplyCommands(
            appUid = 10245,
            vpnServerIpv4 = "198.51.100.25",
            tunInterfaceName = "fptn0",
            smartRfEnabled = true,
            bypassCidrs = SmartRfRouter.russianBypassCidrs,
            extraDirectIpv4s = setOf("203.0.113.77", "149.154.167.50"), // Telegram IP must be ignored from direct bypass
            extraVpnIpv4s = setOf("203.0.113.88", "77.88.55.242"), // RU IP must be rejected from Force-VPN
            clientTunIpv4 = "10.10.0.1",
            dnsServerIpv4 = "172.20.0.1",
        )
        assertTrue("Safety violations: ${SafeRootRulePlanner.validateSafety(apply)}", SafeRootRulePlanner.validateSafety(apply).isEmpty())
        assertTrue(apply.any { it.contains("-d 203.0.113.77/32 -j RETURN") })
        assertFalse("Telegram IP must NEVER get direct RETURN rule", apply.any { it.contains("-d 149.154.167.50/32 -j RETURN") })
        assertTrue(apply.any { it.contains("-d 203.0.113.88/32 -j MARK") })
        assertFalse("Protected RU IP must NEVER get Force-VPN MARK rule", apply.any { it.contains("-d 77.88.55.242/32 -j MARK") })

        // Dynamic direct bypass command
        val dynOk = SafeRootRulePlanner.buildDynamicDirectBypassCommand("203.0.113.99", vpnServerIpv4 = "198.51.100.25", dnsServerIpv4 = "172.20.0.1")
        assertNotNull(dynOk)
        assertTrue(dynOk!!.contains("-I FPTN_OUTPUT 1 -d 203.0.113.99/32 -j RETURN"))
        assertEquals(null, SafeRootRulePlanner.buildDynamicDirectBypassCommand("149.154.167.50")) // Telegram blocked
        assertEquals(null, SafeRootRulePlanner.buildDynamicDirectBypassCommand("192.168.1.1")) // RFC1918 blocked
        assertEquals(null, SafeRootRulePlanner.buildDynamicDirectBypassCommand("198.51.100.25", vpnServerIpv4 = "198.51.100.25")) // Server blocked
    }

    @Test
    fun `v30 tunnel doh resolver extracts query domain and answer ipv4s for split dns`() {
        // Build IPv4 UDP:53 query for "sberbank.ru"
        val qDns = byteArrayOf(
            0x12, 0x34,
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            8, 's'.code.toByte(), 'b'.code.toByte(), 'e'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'a'.code.toByte(), 'n'.code.toByte(), 'k'.code.toByte(),
            2, 'r'.code.toByte(), 'u'.code.toByte(),
            0,
            0x00, 0x01, // QTYPE=A
            0x00, 0x01, // QCLASS=IN
        )
        val totalLen = 20 + 8 + qDns.size
        val pkt = ByteArray(totalLen)
        pkt[0] = 0x45.toByte()
        pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
        pkt[3] = (totalLen and 0xFF).toByte()
        pkt[9] = 17.toByte()
        pkt[22] = 0; pkt[23] = 53.toByte()
        val udpLen = 8 + qDns.size
        pkt[24] = ((udpLen ushr 8) and 0xFF).toByte()
        pkt[25] = (udpLen and 0xFF).toByte()
        System.arraycopy(qDns, 0, pkt, 28, qDns.size)

        assertEquals("sberbank.ru", TunnelDohResolver.extractDnsQueryDomain(pkt, pkt.size))

        // Build minimal RFC-1035 DNS answer with 2 A records: 194.54.14.131 & 194.54.14.132
        val respDns = qDns.copyOf(qDns.size + 32)
        respDns[2] = 0x81.toByte(); respDns[3] = 0x80.toByte() // QR=1, RD=1, RA=1
        respDns[7] = 0x02 // ANCOUNT=2
        var off = qDns.size
        // Answer 1: compressed ptr 0xC00C, TYPE=A, CLASS=IN, TTL=60, RDLEN=4, 194.54.14.131
        val ans1 = byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04, 194.toByte(), 54, 14, 131.toByte())
        val ans2 = byteArrayOf(0xC0.toByte(), 0x0C, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x3C, 0x00, 0x04, 194.toByte(), 54, 14, 132.toByte())
        System.arraycopy(ans1, 0, respDns, off, ans1.size)
        off += ans1.size
        System.arraycopy(ans2, 0, respDns, off, ans2.size)

        val ips = TunnelDohResolver.extractAnswerIpv4sFromDnsPayload(respDns)
        assertEquals(setOf("194.54.14.131", "194.54.14.132"), ips)
    }

    @Test
    fun `v30 expanded weather wmo mapping and backup codec v14 enforce permanent ru lock and 3d weather params`() {
        assertEquals(com.example.domain.model.WeatherEffectMode.CLEAR, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(0))
        assertEquals(com.example.domain.model.WeatherEffectMode.PARTLY_CLOUDY, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(2))
        assertEquals(com.example.domain.model.WeatherEffectMode.CLOUDY, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(3))
        assertEquals(com.example.domain.model.WeatherEffectMode.FOG, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(45))
        assertEquals(com.example.domain.model.WeatherEffectMode.FOG, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(48))
        assertEquals(com.example.domain.model.WeatherEffectMode.BLIZZARD, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(75))
        assertEquals(com.example.domain.model.WeatherEffectMode.BLIZZARD, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(77))
        assertEquals(com.example.domain.model.WeatherEffectMode.HAIL, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(96))
        assertEquals(com.example.domain.model.WeatherEffectMode.HAIL, com.example.data.remote.WeatherRepository.mapWmoCodeToEffect(99))

        assertFalse("BypassRegion.NONE must not appear in selectable UI regions", BypassRegion.NONE in BypassRegion.selectableEntries)
        assertEquals(BypassRegion.RUSSIA, BypassRegion.sanitize(BypassRegion.NONE))

        val original = VpnSettings(
            weatherEffectMode = com.example.domain.model.WeatherEffectMode.FOG,
            weatherWindStrength = 0.85f,
            weatherFogDepth = 1.25f,
            bypassRegion = BypassRegion.NONE,
            smartRfRouting = false,
            unlockProtectedRuWithRoot = true,
        )
        val json = BackupCodec.encode(emptyList(), original)
        val decoded = BackupCodec.decode(json).getOrThrow()
        assertEquals(com.example.domain.model.WeatherEffectMode.FOG, decoded.settings.weatherEffectMode)
        assertEquals(0.85f, decoded.settings.weatherWindStrength, 0.001f)
        assertEquals(1.25f, decoded.settings.weatherFogDepth, 0.001f)
        assertEquals(BypassRegion.RUSSIA, decoded.settings.bypassRegion)
        assertTrue("smartRfRouting must stay permanently locked true", decoded.settings.smartRfRouting)
        assertFalse("unlockProtectedRuWithRoot must stay permanently locked false", decoded.settings.unlockProtectedRuWithRoot)
    }

    @Test
    fun `app update checker compares semantic versions and selects matching ABI asset`() {
        assertTrue(com.example.data.remote.AppUpdateChecker.isNewerVersion("v0.2", "0.1"))
        assertTrue(com.example.data.remote.AppUpdateChecker.isNewerVersion("0.10", "0.2"))
        assertTrue(com.example.data.remote.AppUpdateChecker.isNewerVersion("v1.0.0", "0.9.9"))
        assertTrue(com.example.data.remote.AppUpdateChecker.isNewerVersion("v0.2", "0.2-beta"))
        assertFalse(com.example.data.remote.AppUpdateChecker.isNewerVersion("v0.1", "0.1"))
        assertFalse(com.example.data.remote.AppUpdateChecker.isNewerVersion("v0.0.9", "0.1"))
        assertFalse(com.example.data.remote.AppUpdateChecker.isNewerVersion("0.1-beta", "0.1"))

        val assets = listOf(
            com.example.data.remote.ReleaseApkAsset("FPTN-Flow-v0.2-armeabi-v7a.apk", "https://example.com/v7a.apk", 10L),
            com.example.data.remote.ReleaseApkAsset("FPTN-Flow-v0.2-arm64-v8a.apk", "https://example.com/v8a.apk", 12L),
            com.example.data.remote.ReleaseApkAsset("FPTN-Flow-v0.2-universal.apk", "https://example.com/uni.apk", 20L),
        )
        assertEquals(
            "FPTN-Flow-v0.2-arm64-v8a.apk",
            com.example.data.remote.AppUpdateChecker.pickRecommendedAsset(assets, listOf("arm64-v8a", "armeabi-v7a"))?.name,
        )
        assertEquals(
            "FPTN-Flow-v0.2-universal.apk",
            com.example.data.remote.AppUpdateChecker.pickRecommendedAsset(assets, listOf("riscv64"))?.name,
        )
    }
}



