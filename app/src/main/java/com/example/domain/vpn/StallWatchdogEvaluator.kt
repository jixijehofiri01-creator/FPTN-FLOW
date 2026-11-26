package com.example.domain.vpn

import java.net.InetAddress

enum class WatchdogAction {
    NONE,
    SEND_PROBE,
    RECONNECT,
}

enum class WatchdogSensitivity(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
) {
    GENTLE(
        title = "Мягкий (4G / Центр города)",
        shortTitle = "Мягкий",
        subtitle = "Максимальная терпимость к провалам LTE и глушилкам — исключает ложные переподключения",
    ),
    BALANCED(
        title = "Сбалансированный",
        shortTitle = "Баланс",
        subtitle = "Оптимальный баланс скорости реакции и устойчивости на мобильной сети",
    ),
    AGGRESSIVE(
        title = "Быстрый (жёсткий ТСПУ)",
        shortTitle = "Быстрый",
        subtitle = "Ускоренная диагностика заморозки 0 Б/с при агрессивном сбросе сессий",
    ),
}

/**
 * TCP-backoff-aware & cellular-jitter-tolerant silent tunnel stall detector + RFC-792 ICMP Echo
 * and RFC-1035 UDP:53 DNS keepalive packet builders.
 *
 * Designed specifically to catch genuine silent FPTN/TSPU 0 B/s blackholes WITHOUT false-triggering
 * reconnect loops on congested or EW-affected urban 4G/5G towers (e.g. central Moscow LTE jitter):
 * - Tracks `secondsWithoutTunnelRx`, cumulative `unansweredTxBytes` / `unansweredTxPackets`,
 *   `txActiveTicks` (distinct seconds with outbound packets), `consecutiveSendFailures`, and
 *   `sendFailureTicks` (distinct seconds with socket write errors).
 * - Pauses cleanly whenever `hasPhysicalNetwork == false` (cell handover / elevator / dead spot).
 * - Requires send failures across at least [minSendFailureTicks] distinct seconds so a single-tick
 *   burst of non-blocking socket errors never kills a live session.
 * - Requires outbound traffic across at least [minTxActiveTicks] distinct seconds so a single stray
 *   background UDP/QUIC burst never triggers reconnect.
 * - Sends active dual-protocol in-tunnel probes (ICMP Echo + UDP:53 DNS keepalive) at [probeThresholdSec]
 *   and halfway to [stallThresholdSec] so carriers/exits that drop ICMP still return an instant UDP reply.
 * - Completely idle sessions (screen off, TX == 0) NEVER false-trigger.
 */
class StallWatchdogEvaluator(
    private val probeThresholdSec: Int = DEFAULT_PROBE_THRESHOLD_SEC,
    private val stallThresholdSec: Int = DEFAULT_STALL_THRESHOLD_SEC,
    private val minUnansweredTxBytes: Long = DEFAULT_MIN_UNANSWERED_TX_BYTES,
    private val minUnansweredTxPackets: Int = DEFAULT_MIN_UNANSWERED_TX_PACKETS,
    private val maxSendFailures: Int = DEFAULT_MAX_SEND_FAILURES,
    private val cooldownSec: Int = DEFAULT_COOLDOWN_SEC,
    private val minTxActiveTicks: Int = DEFAULT_MIN_TX_ACTIVE_TICKS,
    private val minSendFailureTicks: Int = DEFAULT_MIN_SEND_FAILURE_TICKS,
) {
    private var secondsWithoutTunnelRx: Int = 0
    private var unansweredTxBytes: Long = 0L
    private var unansweredTxPackets: Int = 0
    private var txActiveTicks: Int = 0
    private var consecutiveSendFailures: Int = 0
    private var sendFailureTicks: Int = 0
    private var probesSentInWindow: Int = 0
    private var cooldownRemainingSec: Int = 0

    private val secondProbeThresholdSec: Int? =
        if (stallThresholdSec - probeThresholdSec >= 4) {
            probeThresholdSec + ((stallThresholdSec - probeThresholdSec) / 2)
        } else {
            null
        }

    fun evaluateTick(
        tunnelRxDeltaBytes: Long,
        tunnelTxDeltaBytes: Long,
        tunnelTxDeltaPackets: Int = if (tunnelTxDeltaBytes > 0L) 1 else 0,
        sendFailureDelta: Int = 0,
        wsAlive: Boolean = true,
        hasPhysicalNetwork: Boolean = true,
    ): WatchdogAction {
        if (cooldownRemainingSec > 0) {
            cooldownRemainingSec--
            if (tunnelRxDeltaBytes > 0L) {
                clearSilenceWindow()
            }
            return WatchdogAction.NONE
        }

        // Physical network temporarily lost (LTE handover / brief dead spot): pause instead of panic-looping
        if (!hasPhysicalNetwork) {
            clearSilenceWindow()
            return WatchdogAction.NONE
        }

        if (!wsAlive) {
            clearSilenceWindow()
            cooldownRemainingSec = cooldownSec
            return WatchdogAction.RECONNECT
        }

        if (tunnelRxDeltaBytes > 0L) {
            clearSilenceWindow()
            return WatchdogAction.NONE
        }

        if (sendFailureDelta > 0) {
            consecutiveSendFailures += sendFailureDelta
            sendFailureTicks++
            if (consecutiveSendFailures >= maxSendFailures && sendFailureTicks >= minSendFailureTicks) {
                clearSilenceWindow()
                cooldownRemainingSec = cooldownSec
                return WatchdogAction.RECONNECT
            }
        }

        if (tunnelTxDeltaBytes > 0L || tunnelTxDeltaPackets > 0) {
            unansweredTxBytes += tunnelTxDeltaBytes.coerceAtLeast(0L)
            unansweredTxPackets += tunnelTxDeltaPackets.coerceAtLeast(0)
            txActiveTicks++
        }

        // Only advance the stall timer once real outbound traffic is waiting for a reply
        val hasOutboundDemand = unansweredTxBytes >= MIN_DEMAND_BYTES || unansweredTxPackets >= 2
        if (!hasOutboundDemand) {
            secondsWithoutTunnelRx = 0
            probesSentInWindow = 0
            return WatchdogAction.NONE
        }

        secondsWithoutTunnelRx++

        val hasSustainedDemand = txActiveTicks >= minTxActiveTicks &&
            (unansweredTxBytes >= minUnansweredTxBytes || unansweredTxPackets >= minUnansweredTxPackets)

        if (secondsWithoutTunnelRx >= stallThresholdSec && hasSustainedDemand) {
            clearSilenceWindow()
            cooldownRemainingSec = cooldownSec
            return WatchdogAction.RECONNECT
        }

        if (probesSentInWindow == 0 && secondsWithoutTunnelRx >= probeThresholdSec) {
            probesSentInWindow = 1
            return WatchdogAction.SEND_PROBE
        }

        val secondProbeAt = secondProbeThresholdSec
        if (secondProbeAt != null && probesSentInWindow == 1 && secondsWithoutTunnelRx >= secondProbeAt) {
            probesSentInWindow = 2
            return WatchdogAction.SEND_PROBE
        }

        return WatchdogAction.NONE
    }

    /** Backwards-compatible simple wrapper returning true when reconnect should trigger. */
    fun onTick(rxDeltaBytes: Long, txDeltaBytes: Long): Boolean {
        return evaluateTick(
            tunnelRxDeltaBytes = rxDeltaBytes,
            tunnelTxDeltaBytes = txDeltaBytes,
            tunnelTxDeltaPackets = if (txDeltaBytes > 0L) 1 else 0,
        ) == WatchdogAction.RECONNECT
    }

    private fun clearSilenceWindow() {
        secondsWithoutTunnelRx = 0
        unansweredTxBytes = 0L
        unansweredTxPackets = 0
        txActiveTicks = 0
        consecutiveSendFailures = 0
        sendFailureTicks = 0
        probesSentInWindow = 0
    }

    fun reset() {
        clearSilenceWindow()
        cooldownRemainingSec = 0
    }

    companion object {
        const val DEFAULT_PROBE_THRESHOLD_SEC = 10
        const val DEFAULT_STALL_THRESHOLD_SEC = 24
        const val DEFAULT_MIN_UNANSWERED_TX_BYTES = 2400L
        const val DEFAULT_MIN_UNANSWERED_TX_PACKETS = 12
        const val DEFAULT_MAX_SEND_FAILURES = 16
        const val DEFAULT_COOLDOWN_SEC = 35
        const val DEFAULT_MIN_TX_ACTIVE_TICKS = 3
        const val DEFAULT_MIN_SEND_FAILURE_TICKS = 2
        private const val MIN_DEMAND_BYTES = 180L

        fun forSensitivity(
            sensitivity: WatchdogSensitivity,
            baseProbeSec: Int,
            baseStallSec: Int,
            baseCooldownSec: Int,
            isCellular: Boolean,
        ): StallWatchdogEvaluator {
            return when (sensitivity) {
                WatchdogSensitivity.GENTLE -> {
                    val probe = (baseProbeSec + if (isCellular) 4 else 1).coerceIn(8, 25)
                    val stall = (baseStallSec + if (isCellular) 8 else 2).coerceAtLeast(probe + 10).coerceIn(18, 55)
                    val cooldown = (baseCooldownSec + if (isCellular) 10 else 0).coerceIn(25, 90)
                    StallWatchdogEvaluator(
                        probeThresholdSec = probe,
                        stallThresholdSec = stall,
                        minUnansweredTxBytes = if (isCellular) 4096L else 2800L,
                        minUnansweredTxPackets = if (isCellular) 18 else 14,
                        maxSendFailures = if (isCellular) 24 else 18,
                        cooldownSec = cooldown,
                        minTxActiveTicks = if (isCellular) 5 else 4,
                        minSendFailureTicks = 3,
                    )
                }
                WatchdogSensitivity.BALANCED -> {
                    val probe = (baseProbeSec + if (isCellular) 3 else 0).coerceIn(5, 22)
                    val stall = (baseStallSec + if (isCellular) 6 else 0).coerceAtLeast(probe + 6).coerceIn(12, 45)
                    val cooldown = (baseCooldownSec + if (isCellular) 5 else 0).coerceIn(18, 75)
                    StallWatchdogEvaluator(
                        probeThresholdSec = probe,
                        stallThresholdSec = stall,
                        minUnansweredTxBytes = if (isCellular) 2400L else 1600L,
                        minUnansweredTxPackets = if (isCellular) 12 else 9,
                        maxSendFailures = if (isCellular) 16 else 12,
                        cooldownSec = cooldown,
                        minTxActiveTicks = if (isCellular) 3 else 2,
                        minSendFailureTicks = 2,
                    )
                }
                WatchdogSensitivity.AGGRESSIVE -> {
                    val probe = (baseProbeSec + if (isCellular) 1 else 0).coerceIn(3, 18)
                    val stall = (baseStallSec + if (isCellular) 2 else 0).coerceAtLeast(probe + 4).coerceIn(7, 35)
                    val cooldown = baseCooldownSec.coerceIn(12, 60)
                    StallWatchdogEvaluator(
                        probeThresholdSec = probe,
                        stallThresholdSec = stall,
                        minUnansweredTxBytes = 1024L,
                        minUnansweredTxPackets = 6,
                        maxSendFailures = 8,
                        cooldownSec = cooldown,
                        minTxActiveTicks = 2,
                        minSendFailureTicks = 2,
                    )
                }
            }
        }

        /**
         * Builds a valid 28-byte IPv4 ICMP Echo Request packet (RFC 792) with correct IPv4 & ICMP
         * checksums to probe live bidirectional connectivity inside the FPTN tunnel.
         */
        fun buildIpv4IcmpEchoRequest(
            sourceIpv4: String,
            destIpv4: String,
            identifier: Int = 0x4650,
            sequence: Int = 1,
        ): ByteArray? {
            val src = runCatching { InetAddress.getByName(sourceIpv4).address }.getOrNull() ?: return null
            val dst = runCatching { InetAddress.getByName(destIpv4).address }.getOrNull() ?: return null
            if (src.size != 4 || dst.size != 4) return null

            val totalLen = 28 // 20-byte IPv4 header + 8-byte ICMP Echo header
            val pkt = ByteArray(totalLen)

            // IPv4 Header
            pkt[0] = 0x45.toByte() // Version 4, IHL 5 (20 bytes)
            pkt[1] = 0x00
            pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            pkt[4] = ((sequence ushr 8) and 0xFF).toByte()
            pkt[5] = (sequence and 0xFF).toByte()
            pkt[6] = 0x40.toByte() // Don't Fragment
            pkt[7] = 0x00
            pkt[8] = 64.toByte()   // TTL
            pkt[9] = 1.toByte()    // Protocol = ICMP (1)
            System.arraycopy(src, 0, pkt, 12, 4)
            System.arraycopy(dst, 0, pkt, 16, 4)

            val ipChecksum = onesComplementChecksum(pkt, 0, 20)
            pkt[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
            pkt[11] = (ipChecksum and 0xFF).toByte()

            // ICMP Echo Request (Type 8, Code 0)
            pkt[20] = 8.toByte()
            pkt[21] = 0.toByte()
            pkt[24] = ((identifier ushr 8) and 0xFF).toByte()
            pkt[25] = (identifier and 0xFF).toByte()
            pkt[26] = ((sequence ushr 8) and 0xFF).toByte()
            pkt[27] = (sequence and 0xFF).toByte()

            val icmpChecksum = onesComplementChecksum(pkt, 20, 8)
            pkt[22] = ((icmpChecksum ushr 8) and 0xFF).toByte()
            pkt[23] = (icmpChecksum and 0xFF).toByte()

            return pkt
        }

        /**
         * Builds a valid 61-byte IPv4 + UDP:53 standard DNS A-query for `one.one.one.one` with
         * valid IPv4 & RFC-768 UDP pseudo-header checksums.
         *
         * Unlike ICMP Echo (which exit nodes or carrier firewalls frequently rate-limit or drop),
         * a tiny UDP:53 DNS query elicits an immediate UDP reply inside the FPTN tunnel and also
         * keeps Moscow mobile CGNAT TCP mappings warm during idle periods.
         */
        fun buildIpv4UdpDnsKeepalivePacket(
            sourceIpv4: String,
            destIpv4: String,
            txId: Int = 0x4650,
        ): ByteArray? {
            val src = runCatching { InetAddress.getByName(sourceIpv4).address }.getOrNull() ?: return null
            val dst = runCatching { InetAddress.getByName(destIpv4).address }.getOrNull() ?: return null
            if (src.size != 4 || dst.size != 4) return null

            // DNS payload (33 bytes): 12-byte header + QNAME "one.one.one.one" (17 bytes) + QTYPE(2) + QCLASS(2)
            val dns = ByteArray(33)
            dns[0] = ((txId ushr 8) and 0xFF).toByte()
            dns[1] = (txId and 0xFF).toByte()
            dns[2] = 0x01.toByte() // Standard query, Recursion Desired (RD=1)
            dns[3] = 0x00.toByte()
            dns[4] = 0x00.toByte(); dns[5] = 0x01.toByte() // QDCOUNT = 1
            // QNAME: [3]one[3]one[3]one[3]one[0]
            var pos = 12
            repeat(4) {
                dns[pos++] = 3.toByte()
                dns[pos++] = 'o'.code.toByte()
                dns[pos++] = 'n'.code.toByte()
                dns[pos++] = 'e'.code.toByte()
            }
            dns[pos++] = 0.toByte()
            dns[pos++] = 0x00.toByte(); dns[pos++] = 0x01.toByte() // QTYPE = A (1)
            dns[pos++] = 0x00.toByte(); dns[pos] = 0x01.toByte()   // QCLASS = IN (1)

            val udpLen = 8 + dns.size
            val totalLen = 20 + udpLen // 61 bytes
            val pkt = ByteArray(totalLen)

            // IPv4 Header
            pkt[0] = 0x45.toByte()
            pkt[1] = 0x00
            pkt[2] = ((totalLen ushr 8) and 0xFF).toByte()
            pkt[3] = (totalLen and 0xFF).toByte()
            pkt[4] = ((txId ushr 8) and 0xFF).toByte()
            pkt[5] = (txId and 0xFF).toByte()
            pkt[6] = 0x40.toByte() // Don't Fragment
            pkt[7] = 0x00
            pkt[8] = 64.toByte()
            pkt[9] = 17.toByte() // Protocol = UDP (17)
            System.arraycopy(src, 0, pkt, 12, 4)
            System.arraycopy(dst, 0, pkt, 16, 4)

            val ipChecksum = onesComplementChecksum(pkt, 0, 20)
            pkt[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
            pkt[11] = (ipChecksum and 0xFF).toByte()

            // UDP Header
            val srcPort = 49152 + (txId and 0x1FFF)
            val dstPort = 53
            pkt[20] = ((srcPort ushr 8) and 0xFF).toByte()
            pkt[21] = (srcPort and 0xFF).toByte()
            pkt[22] = ((dstPort ushr 8) and 0xFF).toByte()
            pkt[23] = (dstPort and 0xFF).toByte()
            pkt[24] = ((udpLen ushr 8) and 0xFF).toByte()
            pkt[25] = (udpLen and 0xFF).toByte()
            System.arraycopy(dns, 0, pkt, 28, dns.size)

            // UDP Checksum over IPv4 pseudo-header + UDP header + DNS payload
            var sum = 0
            sum += ((src[0].toInt() and 0xFF) shl 8) or (src[1].toInt() and 0xFF)
            sum += ((src[2].toInt() and 0xFF) shl 8) or (src[3].toInt() and 0xFF)
            sum += ((dst[0].toInt() and 0xFF) shl 8) or (dst[1].toInt() and 0xFF)
            sum += ((dst[2].toInt() and 0xFF) shl 8) or (dst[3].toInt() and 0xFF)
            sum += 17 // Protocol UDP
            sum += udpLen
            var i = 20
            while (i + 1 < totalLen) {
                sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
                i += 2
            }
            if (i < totalLen) {
                sum += (pkt[i].toInt() and 0xFF) shl 8
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            var udpChecksum = sum.inv() and 0xFFFF
            if (udpChecksum == 0) udpChecksum = 0xFFFF
            pkt[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
            pkt[27] = (udpChecksum and 0xFF).toByte()

            return pkt
        }

        private fun onesComplementChecksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0
            var i = offset
            val end = offset + length
            while (i + 1 < end) {
                val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                sum += word
                i += 2
            }
            if (i < end) {
                sum += (data[i].toInt() and 0xFF) shl 8
            }
            while ((sum ushr 16) != 0) {
                sum = (sum and 0xFFFF) + (sum ushr 16)
            }
            return sum.inv() and 0xFFFF
        }
    }
}
