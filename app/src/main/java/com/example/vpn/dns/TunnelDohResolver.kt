package com.example.vpn.dns

import android.util.Base64
import com.example.core.logging.AppLogStore
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * High-concurrency RFC 8484 DNS-over-HTTPS (DoH) interceptor for UDP port 53 packets inside TunPacketLoop.
 * Operates ONLY while VPN is active; intercepts IPv4/UDP DNS queries leaving the TUN interface,
 * resolves them against the user's Smart DNS / DoH URL, and synthesizes a valid IPv4+UDP DNS reply.
 *
 * Reliability & Media/CDN hardening:
 * - High-concurrency OkHttp Dispatcher (`maxRequestsPerHost = 64`, `maxRequests = 128`) + HTTP/2
 *   multiplexed ConnectionPool(16) — eliminates the default OkHttp `maxRequestsPerHost = 5` bottleneck
 *   that previously queued and timed out burst CDN lookups in Play Store, Telegram, and Music apps!
 * - In-flight singleflight coalescing keyed by normalized `(qname, qtype, qclass)` so simultaneous
 *   UDP retries for the same CDN domain share ONE HTTP/2 stream.
 * - Real RFC-768 IPv4 UDP pseudo-header checksum on every synthesized reply (100% compatible with
 *   Linux netfilter/conntrack/NAT in Root Stealth Mode and Pixel kernels).
 * - Fast 1.5s/1.8s timeouts (2.2s call cap) + GET fallback ONLY on HTTP 400/405/415/501 (never double-timeouts!)
 *   + RCODE check (SERVFAIL/REFUSED/FORMERR/NOTIMP immediately fall back to in-tunnel VPN DNS).
 * - 1024-entry TTL-aware cache keyed by normalized question (ignoring dynamic EDNS cookies/IDs)
 *   with selective expiry eviction instead of full cache wipeout.
 */
class TunnelDohResolver(
    private val dohUrl: String,
    protectSocket: ((Socket) -> Boolean)? = null,
    private val bootstrapIpv4: String? = null,
    private val blockAaaa: Boolean = false,
) {
    private val dnsMessageMediaType = "application/dns-message".toMediaType()
    private val dohHost: String? = dohUrl.toHttpUrlOrNull()?.host

    private data class CachedDnsReply(
        val responseBytes: ByteArray,
        val expiresAtMillis: Long,
        val insertedAtMillis: Long,
    )

    private val replyCache = ConcurrentHashMap<String, CachedDnsReply>()
    private val inFlightQueries = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()
    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenUntilMs = AtomicLong(0L)

    @Volatile
    private var closed = false

    private val okHttpDispatcher = Dispatcher().apply {
        maxRequests = MAX_CONCURRENT_REQUESTS
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(okHttpDispatcher)
        .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1800, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .callTimeout(2200, TimeUnit.MILLISECONDS)
        .dns(
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    // Use known bootstrap IPv4 FIRST so the Smart DNS hostname NEVER leaks to the ISP's system DNS!
                    val fallbackIp = bootstrapIpv4?.trim()?.takeIf { it.isNotEmpty() }
                    if (fallbackIp != null && (dohHost == null || hostname.equals(dohHost, ignoreCase = true))) {
                        val addr = runCatching { InetAddress.getByName(fallbackIp) }.getOrNull()
                        if (addr != null) {
                            return listOf(addr)
                        }
                    }
                    val systemResult = runCatching { Dns.SYSTEM.lookup(hostname) }
                        .getOrNull()
                        ?.filterNot { it.isLoopbackAddress || it.isAnyLocalAddress }
                    if (!systemResult.isNullOrEmpty()) {
                        return systemResult
                    }
                    throw UnknownHostException("Unable to resolve DoH host: $hostname")
                }
            },
        )
        .apply {
            if (protectSocket != null) {
                socketFactory(
                    object : SocketFactory() {
                        override fun createSocket(): Socket = Socket().also { protectSocket(it) }
                        override fun createSocket(host: String, port: Int): Socket =
                            createProtectedAndConnected(InetSocketAddress(host, port), null)
                        override fun createSocket(
                            host: String,
                            port: Int,
                            localHost: InetAddress,
                            localPort: Int,
                        ): Socket = createProtectedAndConnected(
                            InetSocketAddress(host, port),
                            InetSocketAddress(localHost, localPort),
                        )
                        override fun createSocket(host: InetAddress, port: Int): Socket =
                            createProtectedAndConnected(InetSocketAddress(host, port), null)
                        override fun createSocket(
                            address: InetAddress,
                            port: Int,
                            localAddress: InetAddress,
                            localPort: Int,
                        ): Socket = createProtectedAndConnected(
                            InetSocketAddress(address, port),
                            InetSocketAddress(localAddress, localPort),
                        )

                        private fun createProtectedAndConnected(
                            remote: InetSocketAddress,
                            local: InetSocketAddress?,
                        ): Socket {
                            val socket = Socket()
                            protectSocket(socket)
                            try {
                                if (local != null) socket.bind(local)
                                socket.connect(remote, 2200)
                                return socket
                            } catch (e: Exception) {
                                runCatching { socket.close() }
                                throw e
                            }
                        }
                    },
                )
            }
        }
        .build()

    /**
     * Checks if the raw outbound packet is an IPv4 UDP port-53 DNS query.
     * If so, resolves via DoH (or returns instant NODATA for AAAA when [blockAaaa] is enabled)
     * and returns a ready-to-inject IPv4/UDP response packet; otherwise returns null so the
     * packet continues seamlessly through the VPN tunnel to a real UDP:53 upstream resolver.
     */
    fun tryHandleDnsPacket(packet: ByteArray, length: Int): ByteArray? {
        if (closed) return null
        if (!isIpv4UdpDnsQuery(packet, length)) return null
        if (blockAaaa) {
            trySynthesizeEmptyAaaaReply(packet, length)?.let { return it }
        }

        val now = System.currentTimeMillis()
        if (now < circuitOpenUntilMs.get()) {
            // Circuit-breaker open: fall back immediately (<1ms) to in-tunnel VPN DNS!
            return null
        }

        val ihl = (packet[0].toInt() and 0x0F) * 4
        val udpOffset = ihl
        val udpLen = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
        val dnsOffset = udpOffset + 8
        val dnsLen = (udpLen - 8).coerceAtMost(length - dnsOffset)
        if (dnsLen < 12) return null

        val dnsQuery = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)
        val cacheKey = extractNormalizedQuestionKey(dnsQuery, dnsLen)

        if (cacheKey != null) {
            val cached = replyCache[cacheKey]
            if (cached != null && cached.expiresAtMillis > now && cached.responseBytes.size >= 12) {
                val patched = patchTransactionId(cached.responseBytes, dnsQuery)
                return buildIpv4UdpReplyPacket(
                    originalQueryPacket = packet,
                    originalIhl = ihl,
                    dnsPayload = patched,
                )
            }
        }

        // Singleflight coalescing: concurrent retries for the same (qname, qtype, qclass) share ONE DoH stream
        val dnsReply: ByteArray? = if (cacheKey != null) {
            val freshFuture = CompletableFuture<ByteArray?>()
            val existingFuture = inFlightQueries.putIfAbsent(cacheKey, freshFuture)
            if (existingFuture != null) {
                runCatching { existingFuture.get(2300L, TimeUnit.MILLISECONDS) }.getOrNull()
                    ?.let { patchTransactionId(it, dnsQuery) }
            } else {
                try {
                    val fetched = fetchAndValidateDohReply(dnsQuery, cacheKey)
                    freshFuture.complete(fetched)
                    fetched
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    freshFuture.complete(null)
                    throw ce
                } catch (_: Exception) {
                    freshFuture.complete(null)
                    null
                } finally {
                    inFlightQueries.remove(cacheKey, freshFuture)
                }
            }
        } else {
            fetchAndValidateDohReply(dnsQuery, null)
        }

        if (dnsReply == null) {
            return null
        }

        // MTU safety guard: if DoH returned an oversized UDP payload (> 1300 bytes), fall back to
        // in-tunnel DNS so standard MTU/TC-truncation/TCP retry handles it cleanly without silent drops.
        if (dnsReply.size > MAX_SAFE_UDP_DNS_PAYLOAD) {
            return null
        }

        return buildIpv4UdpReplyPacket(
            originalQueryPacket = packet,
            originalIhl = ihl,
            dnsPayload = dnsReply,
        )
    }

    private fun fetchAndValidateDohReply(dnsQuery: ByteArray, cacheKey: String?): ByteArray? {
        val rawReply = queryDohWireFormat(dnsQuery)
        if (rawReply == null || rawReply.size < 12) {
            val fails = consecutiveFailures.incrementAndGet()
            if (fails >= CIRCUIT_BREAKER_THRESHOLD) {
                circuitOpenUntilMs.set(System.currentTimeMillis() + CIRCUIT_BREAKER_COOLDOWN_MS)
                consecutiveFailures.set(0)
                AppLogStore.warn(
                    "SmartDNS",
                    "DoH $dohUrl недоступен ($CIRCUIT_BREAKER_THRESHOLD сбоя подряд) — пауза 30с, DNS работает напрямую через туннель VPN",
                )
            }
            return null
        }

        // Validate DNS RCODE: SERVFAIL (2), REFUSED (5), FORMERR (1), NOTIMP (4) -> immediately
        // fall back to VPN tunnel DNS so media/Play Store/Telegram CDNs never fail on a hiccup!
        val rcode = rawReply[3].toInt() and 0x0F
        if (rcode == RCODE_FORMERR || rcode == RCODE_SERVFAIL || rcode == RCODE_NOTIMP || rcode == RCODE_REFUSED) {
            return null
        }

        consecutiveFailures.set(0)
        val patchedReply = patchTransactionId(rawReply, dnsQuery)

        if (cacheKey != null && rcode == RCODE_NOERROR && patchedReply.size <= MAX_SAFE_UDP_DNS_PAYLOAD) {
            val now = System.currentTimeMillis()
            val ttlMs = extractMinAnswerTtlMs(patchedReply)
            putCacheEntry(
                key = cacheKey,
                entry = CachedDnsReply(
                    responseBytes = patchedReply.copyOf(),
                    expiresAtMillis = now + ttlMs,
                    insertedAtMillis = now,
                ),
                now = now,
            )
        }

        return patchedReply
    }

    private val cacheEvictionLock = Any()

    private fun putCacheEntry(key: String, entry: CachedDnsReply, now: Long) {
        if (replyCache.size >= MAX_CACHE_ENTRIES) {
            synchronized(cacheEvictionLock) {
                if (replyCache.size >= MAX_CACHE_ENTRIES) {
                    replyCache.entries.removeIf { it.value.expiresAtMillis <= now }
                    if (replyCache.size >= MAX_CACHE_ENTRIES) {
                        // Evict oldest 25% of entries instead of wiping the entire hot cache
                        val toEvict = replyCache.entries
                            .toList()
                            .sortedBy { it.value.insertedAtMillis }
                            .take(MAX_CACHE_ENTRIES / 4)
                        for (old in toEvict) {
                            replyCache.remove(old.key)
                        }
                    }
                }
            }
        }
        replyCache[key] = entry
    }

    private fun patchTransactionId(reply: ByteArray, originalQuery: ByteArray): ByteArray {
        val copy = reply.copyOf()
        if (copy.size >= 2 && originalQuery.size >= 2) {
            copy[0] = originalQuery[0]
            copy[1] = originalQuery[1]
        }
        return copy
    }

    private fun queryDohWireFormat(dnsQuery: ByteArray): ByteArray? {
        var shouldTryGet = false
        try {
            val postReq = Request.Builder()
                .url(dohUrl)
                .header("Accept", "application/dns-message")
                .post(dnsQuery.toRequestBody(dnsMessageMediaType))
                .build()
            httpClient.newCall(postReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.bytes()
                    if (body != null && body.size >= 12) return body
                } else {
                    // Only try GET when the server explicitly rejects POST method/content-type
                    shouldTryGet = resp.code in listOf(400, 405, 415, 501)
                    if (shouldTryGet) {
                        AppLogStore.warn("SmartDNS", "DoH POST ответил HTTP ${resp.code} ($dohUrl) — пробуем GET")
                    }
                }
            }
        } catch (ex: Exception) {
            if (!closed && ex.message?.contains("Canceled", ignoreCase = true) != true) {
                // Do NOT waste another 2s doing GET on a network/socket timeout — fall back fast to VPN tunnel DNS!
                AppLogStore.warn("SmartDNS", "Таймаут/сбой DoH ($dohUrl): ${ex.message} — мгновенный резерв через туннель")
            }
            return null
        }

        if (!shouldTryGet) return null

        return try {
            val encodedQuery = Base64.encodeToString(
                dnsQuery,
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
            )
            val separator = if (dohUrl.contains('?')) '&' else '?'
            val getUrl = "$dohUrl${separator}dns=$encodedQuery"
            val getReq = Request.Builder()
                .url(getUrl)
                .header("Accept", "application/dns-message")
                .get()
                .build()
            httpClient.newCall(getReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()?.takeIf { it.size >= 12 }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun close() {
        closed = true
        replyCache.clear()
        inFlightQueries.values.forEach { runCatching { it.complete(null) } }
        inFlightQueries.clear()
        runCatching { httpClient.dispatcher.cancelAll() }
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    companion object {
        const val MAX_CONCURRENT_REQUESTS = 128
        const val MAX_REQUESTS_PER_HOST = 64
        const val MAX_CACHE_ENTRIES = 1024
        private const val MIN_CACHE_TTL_MS = 30_000L
        private const val DEFAULT_CACHE_TTL_MS = 90_000L
        private const val MAX_CACHE_TTL_MS = 300_000L
        private const val CIRCUIT_BREAKER_THRESHOLD = 3
        private const val CIRCUIT_BREAKER_COOLDOWN_MS = 30_000L
        private const val MAX_SAFE_UDP_DNS_PAYLOAD = 1300
        private const val DNS_QTYPE_AAAA = 28

        private const val RCODE_NOERROR = 0
        private const val RCODE_FORMERR = 1
        private const val RCODE_SERVFAIL = 2
        private const val RCODE_NOTIMP = 4
        private const val RCODE_REFUSED = 5

        fun getIpVersion(packet: ByteArray, length: Int): Int {
            if (length < 1) return 0
            return (packet[0].toInt() ushr 4) and 0x0F
        }

        fun isIpv4UdpDnsQuery(packet: ByteArray, length: Int): Boolean {
            if (length < 28) return false
            val version = (packet[0].toInt() ushr 4) and 0x0F
            if (version != 4) return false
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl + 8) return false
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 17) return false
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            return dstPort == 53
        }

        /**
         * Extracts a canonical `(lowercaseQname|qtype|qclass)` cache & singleflight key from the first DNS question.
         * Ignores dynamic transaction IDs and EDNS0 cookies/client-subnet records so cache hit rate stays ~95%.
         */
        fun extractNormalizedQuestionKey(dnsPayload: ByteArray, length: Int = dnsPayload.size): String? {
            if (length < 17) return null
            val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
            if (qdCount != 1) return null

            val sb = StringBuilder(48)
            var pos = 12
            var safety = 0
            while (pos < length && safety < 128) {
                val labelLen = dnsPayload[pos].toInt() and 0xFF
                if (labelLen == 0) {
                    pos++
                    break
                }
                if ((labelLen and 0xC0) == 0xC0) {
                    return null
                }
                if (pos + 1 + labelLen > length) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in 0 until labelLen) {
                    val ch = (dnsPayload[pos + 1 + i].toInt() and 0xFF).toChar()
                    sb.append(ch.lowercaseChar())
                }
                pos += 1 + labelLen
                safety++
            }
            if (pos + 4 > length || sb.isEmpty()) return null
            val qtype = ((dnsPayload[pos].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 1].toInt() and 0xFF)
            val qclass = ((dnsPayload[pos + 2].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 3].toInt() and 0xFF)
            return "${sb.toString().lowercase(Locale.ROOT)}|$qtype|$qclass"
        }

        /**
         * Parses minimum TTL from Answer RR section of a DNS reply, clamped to [30s..300s].
         */
        fun extractMinAnswerTtlMs(dnsReply: ByteArray): Long {
            val len = dnsReply.size
            if (len < 12) return DEFAULT_CACHE_TTL_MS
            val qdCount = ((dnsReply[4].toInt() and 0xFF) shl 8) or (dnsReply[5].toInt() and 0xFF)
            val anCount = ((dnsReply[6].toInt() and 0xFF) shl 8) or (dnsReply[7].toInt() and 0xFF)
            if (anCount <= 0) return MIN_CACHE_TTL_MS

            var pos = 12
            repeat(qdCount) {
                pos = skipDnsName(dnsReply, pos, len)
                if (pos < 0 || pos + 4 > len) return DEFAULT_CACHE_TTL_MS
                pos += 4
            }

            var minTtlSec = Long.MAX_VALUE
            repeat(anCount) {
                pos = skipDnsName(dnsReply, pos, len)
                if (pos < 0 || pos + 10 > len) return DEFAULT_CACHE_TTL_MS
                val ttl = ((dnsReply[pos + 4].toLong() and 0xFFL) shl 24) or
                    ((dnsReply[pos + 5].toLong() and 0xFFL) shl 16) or
                    ((dnsReply[pos + 6].toLong() and 0xFFL) shl 8) or
                    (dnsReply[pos + 7].toLong() and 0xFFL)
                val rdLength = ((dnsReply[pos + 8].toInt() and 0xFF) shl 8) or (dnsReply[pos + 9].toInt() and 0xFF)
                if (ttl in 1L..86_400L && ttl < minTtlSec) {
                    minTtlSec = ttl
                }
                pos += 10 + rdLength
                if (pos > len) return DEFAULT_CACHE_TTL_MS
            }
            if (minTtlSec == Long.MAX_VALUE) return DEFAULT_CACHE_TTL_MS
            return (minTtlSec * 1000L).coerceIn(MIN_CACHE_TTL_MS, MAX_CACHE_TTL_MS)
        }

        private fun skipDnsName(data: ByteArray, startPos: Int, len: Int): Int {
            var pos = startPos
            var safety = 0
            while (pos < len && safety < 128) {
                val b = data[pos].toInt() and 0xFF
                if (b == 0) return pos + 1
                if ((b and 0xC0) == 0xC0) return if (pos + 2 <= len) pos + 2 else -1
                pos += 1 + b
                safety++
            }
            return -1
        }

        /**
         * Inspects a DNS wire query payload and returns true if its first question is QTYPE=AAAA (28).
         */
        fun isAaaaDnsQueryPayload(dnsPayload: ByteArray, length: Int = dnsPayload.size): Boolean {
            if (length < 17) return false
            val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
            if (qdCount != 1) return false
            val pos = skipDnsName(dnsPayload, 12, length)
            if (pos < 0 || pos + 4 > length) return false
            val qtype = ((dnsPayload[pos].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 1].toInt() and 0xFF)
            return qtype == DNS_QTYPE_AAAA
        }

        /**
         * If [packet] is an IPv4/UDP DNS query asking strictly for QTYPE=AAAA (IPv6), synthesizes an
         * RFC-compliant empty NOERROR (NODATA) response packet so apps immediately use IPv4 inside VPN.
         */
        fun trySynthesizeEmptyAaaaReply(packet: ByteArray, length: Int): ByteArray? {
            if (!isIpv4UdpDnsQuery(packet, length)) return null
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val udpOffset = ihl
            val udpLen = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
            val dnsOffset = udpOffset + 8
            val dnsLen = (udpLen - 8).coerceAtMost(length - dnsOffset)
            if (dnsLen < 17) return null

            val dnsQuery = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)
            if (!isAaaaDnsQueryPayload(dnsQuery, dnsLen)) return null

            val afterName = skipDnsName(dnsQuery, 12, dnsLen)
            if (afterName < 0) return null
            val questionEnd = afterName + 4
            if (questionEnd > dnsLen) return null

            val replyDns = dnsQuery.copyOf(questionEnd)
            val rdBit = dnsQuery[2].toInt() and 0x01
            // QR=1 (response), Opcode=0, AA=0, TC=0, RD=copied
            replyDns[2] = (0x80 or rdBit).toByte()
            // RA=1 (recursion available), Z=0, RCODE=0 (NOERROR)
            replyDns[3] = 0x80.toByte()
            // QDCOUNT = 1, ANCOUNT = 0, NSCOUNT = 0, ARCOUNT = 0
            replyDns[4] = 0x00
            replyDns[5] = 0x01
            replyDns[6] = 0x00
            replyDns[7] = 0x00
            replyDns[8] = 0x00
            replyDns[9] = 0x00
            replyDns[10] = 0x00
            replyDns[11] = 0x00

            return buildIpv4UdpReplyPacket(
                originalQueryPacket = packet,
                originalIhl = ihl,
                dnsPayload = replyDns,
            )
        }

        fun buildIpv4UdpReplyPacket(
            originalQueryPacket: ByteArray,
            originalIhl: Int,
            dnsPayload: ByteArray,
        ): ByteArray {
            val ipHeaderLen = 20
            val udpHeaderLen = 8
            val totalLen = ipHeaderLen + udpHeaderLen + dnsPayload.size
            val out = ByteArray(totalLen)

            // IPv4 Header (20 bytes)
            out[0] = 0x45.toByte()
            out[1] = 0x00
            out[2] = ((totalLen ushr 8) and 0xFF).toByte()
            out[3] = (totalLen and 0xFF).toByte()
            out[4] = originalQueryPacket[4]
            out[5] = originalQueryPacket[5]
            out[6] = 0x40.toByte()
            out[7] = 0x00
            out[8] = 64.toByte()
            out[9] = 17.toByte()
            out[10] = 0x00
            out[11] = 0x00

            // Swap Src IP (12..15) and Dst IP (16..19)
            System.arraycopy(originalQueryPacket, 16, out, 12, 4)
            System.arraycopy(originalQueryPacket, 12, out, 16, 4)

            val ipChecksum = computeChecksum(out, 0, ipHeaderLen)
            out[10] = ((ipChecksum ushr 8) and 0xFF).toByte()
            out[11] = (ipChecksum and 0xFF).toByte()

            // UDP Header (8 bytes) — swap Src Port and Dst Port
            val udpLen = udpHeaderLen + dnsPayload.size
            out[20] = originalQueryPacket[originalIhl + 2]
            out[21] = originalQueryPacket[originalIhl + 3]
            out[22] = originalQueryPacket[originalIhl]
            out[23] = originalQueryPacket[originalIhl + 1]
            out[24] = ((udpLen ushr 8) and 0xFF).toByte()
            out[25] = (udpLen and 0xFF).toByte()
            out[26] = 0x00
            out[27] = 0x00

            System.arraycopy(dnsPayload, 0, out, ipHeaderLen + udpHeaderLen, dnsPayload.size)

            // Compute exact RFC-768 IPv4 UDP pseudo-header checksum (100% netfilter/conntrack/Pixel safe!)
            val udpChecksum = computeUdpIpv4Checksum(out, ipHeaderLen, udpLen)
            out[26] = ((udpChecksum ushr 8) and 0xFF).toByte()
            out[27] = (udpChecksum and 0xFF).toByte()

            return out
        }

        /**
         * Computes RFC-768 IPv4 UDP checksum over pseudo-header (SrcIP, DstIP, 0x0011, UdpLen) + UDP segment.
         * Per RFC 768, a computed result of 0x0000 is transmitted as 0xFFFF.
         */
        fun computeUdpIpv4Checksum(packet: ByteArray, ipHeaderLen: Int, udpLen: Int): Int {
            var sum = 0L
            // Pseudo-header: Src IP (12..15) + Dst IP (16..19)
            var i = 12
            while (i < 20) {
                sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
                i += 2
            }
            // Protocol UDP (17) + UDP Length
            sum += 17L
            sum += udpLen.toLong() and 0xFFFFL

            // UDP Header + Payload
            var u = ipHeaderLen
            val end = ipHeaderLen + udpLen
            while (u < end - 1) {
                sum += ((packet[u].toInt() and 0xFF) shl 8) or (packet[u + 1].toInt() and 0xFF)
                u += 2
            }
            if (u < end) {
                sum += (packet[u].toInt() and 0xFF) shl 8
            }

            while ((sum ushr 16) != 0L) {
                sum = (sum and 0xFFFFL) + (sum ushr 16)
            }
            val checksum = (sum.inv() and 0xFFFFL).toInt()
            return if (checksum == 0) 0xFFFF else checksum
        }

        private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
            var sum = 0L
            var i = offset
            val end = offset + length
            while (i < end - 1) {
                val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
                sum += word
                i += 2
            }
            if (i < end) {
                sum += (data[i].toInt() and 0xFF) shl 8
            }
            while ((sum ushr 16) != 0L) {
                sum = (sum and 0xFFFFL) + (sum ushr 16)
            }
            return (sum.inv() and 0xFFFFL).toInt()
        }

        /**
         * Synthesizes a valid RFC 4443 ICMPv6 Destination Unreachable (Type 1, Code 1 = Communication
         * Administratively Prohibited) reply for a dropped outbound IPv6 packet when IPv6 is disabled.
         * Prevents 30s silent TCP/QUIC blackholes & Google Play Services (`com.google.android.gms`) ANRs
         * by forcing instant (<1ms) Happy Eyeballs failover to IPv4 inside the VPN tunnel!
         */
        fun trySynthesizeIcmpv6AdminProhibited(packet: ByteArray, length: Int): ByteArray? {
            if (length < 40) return null
            val version = (packet[0].toInt() ushr 4) and 0x0F
            if (version != 6) return null
            val nextHeader = packet[6].toInt() and 0xFF
            // Never reply to ICMPv6 (58) or multicast destinations (ff00::/8) to prevent ICMP loops!
            if (nextHeader == 58) return null
            if ((packet[24].toInt() and 0xFF) == 0xFF) return null

            // Quote up to 512 bytes of original offending IPv6 packet (well within 1280 min IPv6 MTU)
            val quotedLen = length.coerceAtMost(512)
            val icmpPayloadLen = 8 + quotedLen
            val totalLen = 40 + icmpPayloadLen
            val out = ByteArray(totalLen)

            // IPv6 Header (40 bytes)
            out[0] = 0x60.toByte() // Version 6
            out[4] = ((icmpPayloadLen ushr 8) and 0xFF).toByte()
            out[5] = (icmpPayloadLen and 0xFF).toByte()
            out[6] = 58.toByte() // Next Header = ICMPv6
            out[7] = 64.toByte() // Hop Limit
            // Swap Dst IPv6 (24..39) -> Src IPv6 (8..23), Src IPv6 (8..23) -> Dst IPv6 (24..39)
            System.arraycopy(packet, 24, out, 8, 16)
            System.arraycopy(packet, 8, out, 24, 16)

            // ICMPv6 Header (8 bytes): Type=1 (Dest Unreachable), Code=1 (Admin Prohibited)
            out[40] = 1.toByte()
            out[41] = 1.toByte()
            // Checksum (42..43) + Unused (44..47) zeroed initially
            System.arraycopy(packet, 0, out, 48, quotedLen)

            // RFC 4443 ICMPv6 checksum over IPv6 pseudo-header + ICMPv6 message
            val checksum = computeIpv6UpperLayerChecksum(
                ipv6Packet = out,
                nextHeader = 58,
                upperLayerOffset = 40,
                upperLayerLength = icmpPayloadLen,
            )
            out[42] = ((checksum ushr 8) and 0xFF).toByte()
            out[43] = (checksum and 0xFF).toByte()
            return out
        }

        /**
         * Detects IPv4 TCP packets destined to port 853 (DNS-over-TLS / Android Private DNS).
         * Used when Smart DNS (DoH) is active to reject opportunistic DoT probes instantly so
         * Pixel/Android routes DNS through UDP:53 -> Smart DNS instead of silently bypassing it!
         */
        fun isIpv4TcpPort853(packet: ByteArray, length: Int): Boolean {
            if (length < 40) return false
            val version = (packet[0].toInt() ushr 4) and 0x0F
            if (version != 4) return false
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl + 20) return false
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 6) return false
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            return dstPort == 853
        }

        /**
         * Synthesizes a valid RFC 793 IPv4 TCP RST+ACK packet for a blocked outbound TCP SYN
         * (e.g. opportunistic DoT port 853) so Android Private DNS fails over to UDP:53 in <1ms!
         */
        fun trySynthesizeIpv4TcpRst(packet: ByteArray, length: Int): ByteArray? {
            if (length < 40) return null
            val version = (packet[0].toInt() ushr 4) and 0x0F
            if (version != 4) return null
            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (ihl < 20 || length < ihl + 20) return null
            val protocol = packet[9].toInt() and 0xFF
            if (protocol != 6) return null
            val flags = packet[ihl + 13].toInt() and 0xFF
            // Never RST an incoming RST to avoid loops
            if ((flags and 0x04) != 0) return null

            val totalLen = 40 // 20B IPv4 + 20B TCP
            val out = ByteArray(totalLen)
            out[0] = 0x45.toByte()
            out[2] = 0x00
            out[3] = 40.toByte()
            out[6] = 0x40.toByte() // DF
            out[8] = 64.toByte() // TTL
            out[9] = 6.toByte() // TCP
            System.arraycopy(packet, 16, out, 12, 4) // Src <- Orig Dst
            System.arraycopy(packet, 12, out, 16, 4) // Dst <- Orig Src
            val ipCs = computeChecksum(out, 0, 20)
            out[10] = ((ipCs ushr 8) and 0xFF).toByte()
            out[11] = (ipCs and 0xFF).toByte()

            // Swap TCP Src/Dst ports
            out[20] = packet[ihl + 2]
            out[21] = packet[ihl + 3]
            out[22] = packet[ihl]
            out[23] = packet[ihl + 1]

            // Orig Seq + 1 (for SYN) -> AckNum; Orig Ack -> SeqNum
            val origSeq = ((packet[ihl + 4].toLong() and 0xFFL) shl 24) or
                ((packet[ihl + 5].toLong() and 0xFFL) shl 16) or
                ((packet[ihl + 6].toLong() and 0xFFL) shl 8) or
                (packet[ihl + 7].toLong() and 0xFFL)
            val ackNum = (origSeq + 1L) and 0xFFFFFFFFL
            // SeqNum = 0 (bytes 24..27 already 0)
            out[28] = ((ackNum ushr 24) and 0xFFL).toByte()
            out[29] = ((ackNum ushr 16) and 0xFFL).toByte()
            out[30] = ((ackNum ushr 8) and 0xFFL).toByte()
            out[31] = (ackNum and 0xFFL).toByte()
            out[32] = 0x50.toByte() // Data offset = 5 (20 bytes)
            out[33] = 0x14.toByte() // RST + ACK

            // RFC 793 TCP checksum over IPv4 pseudo-header + 20B TCP header
            var sum = 0L
            var i = 12
            while (i < 20) {
                sum += ((out[i].toInt() and 0xFF) shl 8) or (out[i + 1].toInt() and 0xFF)
                i += 2
            }
            sum += 6L // TCP
            sum += 20L // TCP len
            var t = 20
            while (t < 40) {
                sum += ((out[t].toInt() and 0xFF) shl 8) or (out[t + 1].toInt() and 0xFF)
                t += 2
            }
            while ((sum ushr 16) != 0L) {
                sum = (sum and 0xFFFFL) + (sum ushr 16)
            }
            val tcpCs = (sum.inv() and 0xFFFFL).toInt()
            out[36] = ((tcpCs ushr 8) and 0xFF).toByte()
            out[37] = (tcpCs and 0xFF).toByte()
            return out
        }

        private fun computeIpv6UpperLayerChecksum(
            ipv6Packet: ByteArray,
            nextHeader: Int,
            upperLayerOffset: Int,
            upperLayerLength: Int,
        ): Int {
            var sum = 0L
            // IPv6 pseudo-header: Src (8..23) + Dst (24..39)
            var i = 8
            while (i < 40) {
                sum += ((ipv6Packet[i].toInt() and 0xFF) shl 8) or (ipv6Packet[i + 1].toInt() and 0xFF)
                i += 2
            }
            // Upper-Layer Packet Length (32-bit) + Next Header
            sum += (upperLayerLength ushr 16) and 0xFFFF
            sum += upperLayerLength and 0xFFFF
            sum += nextHeader and 0xFF

            var u = upperLayerOffset
            val end = upperLayerOffset + upperLayerLength
            while (u < end - 1) {
                sum += ((ipv6Packet[u].toInt() and 0xFF) shl 8) or (ipv6Packet[u + 1].toInt() and 0xFF)
                u += 2
            }
            if (u < end) {
                sum += (ipv6Packet[u].toInt() and 0xFF) shl 8
            }
            while ((sum ushr 16) != 0L) {
                sum = (sum and 0xFFFFL) + (sum ushr 16)
            }
            return (sum.inv() and 0xFFFFL).toInt()
        }

        /**
         * Extracts the lowercase QNAME domain (e.g. `"sberbank.ru"`) from an IPv4 UDP:53 DNS query packet.
         * Returns null if the packet is not a valid IPv4 UDP:53 DNS query.
         */
        fun extractDnsQueryDomain(packet: ByteArray, length: Int): String? {
            if (!isIpv4UdpDnsQuery(packet, length)) return null
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val udpOffset = ihl
            val udpLen = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
            val dnsOffset = udpOffset + 8
            val dnsLen = (udpLen - 8).coerceAtMost(length - dnsOffset)
            if (dnsLen < 17) return null

            val qdCount = ((packet[dnsOffset + 4].toInt() and 0xFF) shl 8) or (packet[dnsOffset + 5].toInt() and 0xFF)
            if (qdCount < 1) return null

            val sb = StringBuilder(64)
            var idx = dnsOffset + 12
            val end = dnsOffset + dnsLen
            var labels = 0
            while (idx < end && labels < 64) {
                val labelLen = packet[idx].toInt() and 0xFF
                if (labelLen == 0) break
                if ((labelLen and 0xC0) != 0) return null // Unexpected pointer in question QNAME
                idx++
                if (idx + labelLen > end) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (j in 0 until labelLen) {
                    sb.append((packet[idx + j].toInt() and 0xFF).toChar())
                }
                idx += labelLen
                labels++
            }
            if (sb.isEmpty()) return null
            return sb.toString().lowercase(Locale.US).trimEnd('.')
        }

        /**
         * Extracts all IPv4 (`TYPE=A`, `CLASS=IN`) answer addresses from a raw RFC-1035 DNS wire response payload.
         */
        fun extractAnswerIpv4sFromDnsPayload(dnsPayload: ByteArray, length: Int = dnsPayload.size): Set<String> {
            val safeLen = length.coerceAtMost(dnsPayload.size)
            if (safeLen < 12) return emptySet()
            val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
            val anCount = ((dnsPayload[6].toInt() and 0xFF) shl 8) or (dnsPayload[7].toInt() and 0xFF)
            if (anCount <= 0) return emptySet()

            var idx = 12
            repeat(qdCount) {
                idx = skipDnsName(dnsPayload, idx, safeLen)
                if (idx < 0 || idx + 4 > safeLen) return emptySet()
                idx += 4
            }

            val ips = linkedSetOf<String>()
            repeat(anCount.coerceAtMost(32)) {
                idx = skipDnsName(dnsPayload, idx, safeLen)
                if (idx < 0 || idx + 10 > safeLen) return ips
                val rType = ((dnsPayload[idx].toInt() and 0xFF) shl 8) or (dnsPayload[idx + 1].toInt() and 0xFF)
                val rClass = ((dnsPayload[idx + 2].toInt() and 0xFF) shl 8) or (dnsPayload[idx + 3].toInt() and 0xFF)
                val rdLen = ((dnsPayload[idx + 8].toInt() and 0xFF) shl 8) or (dnsPayload[idx + 9].toInt() and 0xFF)
                idx += 10
                if (idx + rdLen > safeLen) return ips
                if (rType == 1 && rClass == 1 && rdLen == 4) {
                    val b0 = dnsPayload[idx].toInt() and 0xFF
                    val b1 = dnsPayload[idx + 1].toInt() and 0xFF
                    val b2 = dnsPayload[idx + 2].toInt() and 0xFF
                    val b3 = dnsPayload[idx + 3].toInt() and 0xFF
                    if (!(b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) && b0 != 127) {
                        ips.add("$b0.$b1.$b2.$b3")
                    }
                }
                idx += rdLen
            }
            return ips
        }

        /**
         * Forwards an IPv4 UDP:53 DNS query directly over the physical network (bypassing the VPN tunnel via
         * `protectDatagramSocket`) so `.ru`/regional/custom-direct domains receive true local GeoDNS IPv4s
         * instead of foreign VPN-server DNS answers.
         *
         * Returns `(synthesizedIpv4UdpReplyPacket, extractedAnswerIpv4s)` on success, or null on timeout/failure.
         */
        fun forwardUdpDnsDirectly(
            packet: ByteArray,
            length: Int,
            upstreamDnsIpv4s: List<String>,
            protectDatagramSocket: (DatagramSocket) -> Boolean,
            timeoutMs: Int = 1_200,
        ): Pair<ByteArray, Set<String>>? {
            if (!isIpv4UdpDnsQuery(packet, length)) return null
            val ihl = (packet[0].toInt() and 0x0F) * 4
            val udpOffset = ihl
            val udpLen = ((packet[udpOffset + 4].toInt() and 0xFF) shl 8) or (packet[udpOffset + 5].toInt() and 0xFF)
            val dnsOffset = udpOffset + 8
            val dnsLen = (udpLen - 8).coerceAtMost(length - dnsOffset)
            if (dnsLen < 12) return null

            val dnsQuery = packet.copyOfRange(dnsOffset, dnsOffset + dnsLen)
            val txId0 = dnsQuery[0]
            val txId1 = dnsQuery[1]

            val targets = upstreamDnsIpv4s
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .ifEmpty { listOf("77.88.8.8", "1.1.1.1") }

            for (upstreamIp in targets.take(2)) {
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket().apply {
                        soTimeout = timeoutMs
                    }
                    if (!protectDatagramSocket(socket)) {
                        continue
                    }
                    val upstreamAddr = InetAddress.getByName(upstreamIp)
                    socket.send(DatagramPacket(dnsQuery, dnsQuery.size, upstreamAddr, 53))
                    val recvBuf = ByteArray(1500)
                    val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                    socket.receive(recvPacket)
                    val recvLen = recvPacket.length
                    if (recvLen >= 12 && recvBuf[0] == txId0 && recvBuf[1] == txId1) {
                        val rcode = recvBuf[3].toInt() and 0x0F
                        if (rcode == 0 || rcode == 3) { // NOERROR or NXDOMAIN
                            val dnsReplyPayload = recvBuf.copyOf(recvLen)
                            val answerIps = extractAnswerIpv4sFromDnsPayload(dnsReplyPayload, recvLen)
                            val replyPacket = buildIpv4UdpReplyPacket(
                                originalQueryPacket = packet,
                                originalIhl = ihl,
                                dnsPayload = dnsReplyPayload,
                            )
                            return replyPacket to answerIps
                        }
                    }
                } catch (_: Exception) {
                    // Try next upstream direct DNS
                } finally {
                    runCatching { socket?.close() }
                }
            }
            return null
        }
    }
}
