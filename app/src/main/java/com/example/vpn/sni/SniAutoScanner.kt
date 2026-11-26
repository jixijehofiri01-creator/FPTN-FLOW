package com.example.vpn.sni

import android.content.Context
import com.example.R
import com.example.core.config.FptnConstants
import com.example.core.logging.AppLogStore
import com.example.domain.model.BypassRegion
import com.example.domain.model.FptnServer
import com.example.domain.model.SniScanResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.fptn.vpn.services.snichecker.SniChecker
import org.fptn.vpn.services.websocket.FptnNativeLoader
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Official FPTN 1.3.28.0 SNI Catalog & Auto-Scanner:
 * - Loads all ~1,278 official SNI domains from res/raw/russia.sni (996) + res/raw/global.sni (282).
 * - Uses official native `org.fptn.vpn.services.snichecker.SniChecker` (BoringSSL) to test
 *   candidates directly against the user's selected FPTN server and chosen bypass strategy!
 * - Supports live progress reporting (checked / total / currentDomain) and instant cancellation.
 */
object SniAutoScanner {

    @Volatile
    private var cachedOfficialCatalog: List<String>? = null

    val fallbackDomains: List<String> = listOf(
        "ads.x5.ru",
        "vk.com",
        "ya.ru",
        "gosuslugi.ru",
        "sberbank.ru",
        "ozon.ru",
        "wildberries.ru",
        "dzen.ru",
        "mail.ru",
        "tbank.ru",
        "mts.ru",
        "rbc.ru",
        "avito.ru",
        "google.com",
        "cloudflare.com",
    )

    /**
     * Loads the complete official FPTN SNI catalog (~1,278 domains) from res/raw/russia.sni and res/raw/global.sni.
     */
    fun loadOfficialSniCatalog(context: Context): List<String> {
        cachedOfficialCatalog?.let { return it }
        synchronized(this) {
            cachedOfficialCatalog?.let { return it }
            val result = linkedSetOf<String>()
            // Always keep official default at the very top
            result.add("ads.x5.ru")

            fun readRaw(resId: Int) {
                runCatching {
                    context.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { rawLine ->
                            val domain = rawLine.trim().lowercase()
                            if (domain.isNotEmpty() && !domain.startsWith("#") && domain.contains('.')) {
                                result.add(domain)
                            }
                        }
                    }
                }
            }

            readRaw(R.raw.russia)
            readRaw(R.raw.global)

            val finalList = if (result.size > 1) result.toList() else fallbackDomains
            cachedOfficialCatalog = finalList
            return finalList
        }
    }

    /**
     * Loads the SNI catalog tailored for [bypassRegion]: prioritizes the region's curated whitelist
     * SNIs (Russia / China / Iran / Vietnam) before the full official FPTN catalog.
     */
    fun loadCatalogForRegion(context: Context, bypassRegion: BypassRegion): List<String> {
        val official = loadOfficialSniCatalog(context)
        if (bypassRegion.recommendedSnis.isEmpty()) return official
        val ordered = linkedSetOf<String>()
        bypassRegion.recommendedSnis.forEach { sni ->
            val clean = sni.trim().lowercase()
            if (clean.isNotEmpty() && clean.contains('.')) ordered.add(clean)
        }
        ordered.addAll(official)
        return ordered.toList()
    }

    /**
     * Runs real native server-verified SNI auto-scan using official `SniChecker` when native library & server
     * are available; falls back to direct TLS 443 probing if running without native binary (e.g. JVM tests).
     *
     * Stops immediately on the first verified working SNI (matching official FptnClient-Android behavior)
     * or when coroutine is cancelled by the user pressing "Остановить".
     */
    suspend fun autoScanForWorkingSni(
        domains: List<String>,
        server: FptnServer?,
        censorshipStrategyName: String,
        onProgress: (checked: Int, total: Int, currentDomain: String) -> Unit,
    ): List<SniScanResult> = withContext(Dispatchers.IO) {
        val candidates = domains.ifEmpty { fallbackDomains }
        val total = candidates.size
        val results = mutableListOf<SniScanResult>()

        val canUseNative = server != null && FptnNativeLoader.ensureLoaded()
        if (canUseNative && server != null) {
            AppLogStore.info(
                "SniScanner",
                "Запуск нативного SniChecker ($total доменов) для ${server.displayTitle} (стратегия=$censorshipStrategyName)…",
            )
            val checker = SniChecker(
                server.host,
                server.port,
                server.md5Fingerprint,
                censorshipStrategyName,
            )
            try {
                if (!checker.isValid()) {
                    AppLogStore.warn("SniScanner", "SniChecker не инициализировался — переключаемся на TLS-пробу")
                } else {
                    for ((index, domain) in candidates.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        onProgress(index + 1, total, domain)
                        val start = System.nanoTime()
                        val ok = runCatching { checker.checkSni(domain) }.getOrDefault(false)
                        val rttMs = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
                        if (ok) {
                            AppLogStore.info(
                                "SniScanner",
                                "Найден рабочий SNI для сервера: $domain (${rttMs} мс, проверено ${index + 1}/$total)",
                            )
                            results.add(0, SniScanResult(domain = domain, rttMs = rttMs, reachable = true))
                            return@withContext results
                        } else if (results.size < 12) {
                            results.add(SniScanResult(domain = domain, rttMs = null, reachable = false))
                        }
                    }
                    AppLogStore.warn("SniScanner", "Автоподбор завершён: из $total доменов ни один не прошёл проверку")
                    return@withContext results
                }
            } finally {
                checker.close()
            }
        }

        // Fallback: direct TLS 443 ClientHello handshake probe on first 20 domains
        val fallbackBatch = candidates.take(20)
        AppLogStore.info("SniScanner", "Запуск TLS 443 проверки (${fallbackBatch.size} доменов)…")
        for ((index, domain) in fallbackBatch.withIndex()) {
            currentCoroutineContext().ensureActive()
            onProgress(index + 1, fallbackBatch.size, domain)
            val res = probeSingleDomainTls(domain, timeoutMs = FptnConstants.SNI_FALLBACK_PROBE_TIMEOUT_MS)
            results.add(res)
            if (res.reachable) {
                AppLogStore.info("SniScanner", "Найден доступный SNI: ${res.domain} (${res.rttMs} мс)")
                break
            }
        }
        results.sortedWith(
            compareByDescending<SniScanResult> { it.reachable }
                .thenBy { it.rttMs ?: Int.MAX_VALUE },
        )
    }

    private suspend fun probeSingleDomainTls(domain: String, timeoutMs: Int): SniScanResult {
        val rawSocket = Socket()
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { rawSocket.close() }
        }
        return try {
            val start = System.nanoTime()
            val factory = SSLSocketFactory.getDefault() as? SSLSocketFactory
                ?: return SniScanResult(domain = domain, rttMs = null, reachable = false)
            rawSocket.use { sock ->
                sock.connect(InetSocketAddress(domain, 443), timeoutMs)
                sock.soTimeout = timeoutMs
                val sslSocket = factory.createSocket(sock, domain, 443, true) as? SSLSocket
                    ?: return SniScanResult(domain = domain, rttMs = null, reachable = false)
                sslSocket.use { ssl ->
                    val params = ssl.sslParameters
                    params.serverNames = listOf(SNIHostName(domain))
                    ssl.sslParameters = params
                    ssl.startHandshake()
                }
            }
            val rttMs = ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
            SniScanResult(domain = domain, rttMs = rttMs, reachable = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            SniScanResult(domain = domain, rttMs = null, reachable = false)
        } finally {
            cancelHandle?.dispose()
            runCatching { rawSocket.close() }
        }
    }
}
