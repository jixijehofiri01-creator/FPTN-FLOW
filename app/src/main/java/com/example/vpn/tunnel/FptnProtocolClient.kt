package com.example.vpn.tunnel

import com.example.core.logging.AppLogStore
import com.example.domain.model.BypassMethod
import com.example.domain.model.FptnServer
import com.example.domain.model.VpnErrorInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

data class FptnHandshakeResult(
    val accessToken: String,
    val clientTunIp: String,
    val serverDnsIp: String,
)

class FptnConnectionException(
    val errorInfo: VpnErrorInfo,
    cause: Throwable? = null,
    val nativeCode: Int = -1,
    val rawError: String = "",
) : Exception(errorInfo.details, cause)

/**
 * FPTN HTTPS/TLS + WebSocket tunnel protocol client with detailed diagnostics:
 * 1. Verifies server X.509 certificate MD5 fingerprint (matching FPTN token md5_fingerprint).
 * 2. Authenticates via POST /api/v1/login and fetches DNS via GET /api/v1/dns.
 * 3. Upgrades to binary WebSocket tunnel (/fptn with automatic fallback to /api/v1/tun).
 * 4. Produces explicit, user-readable VpnErrorInfo diagnostics for every failure mode.
 */
class FptnProtocolClient(
    private val server: FptnServer,
    private val username: String,
    private val password: String,
    private val bypassMethod: BypassMethod,
    private val customSni: String,
    private val protectSocket: (Socket) -> Boolean,
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val httpClient: OkHttpClient = buildHttpClient()
    private var webSocket: WebSocket? = null

    fun performHandshake(fallbackDns: String): Result<FptnHandshakeResult> {
        return try {
            val baseUrl = "https://${server.host}:${server.port}"
            val loginPayload = JSONObject().apply {
                put("username", username)
                put("password", password)
            }.toString()

            AppLogStore.info(
                "FptnProto",
                "POST $baseUrl/api/v1/login (метод=${bypassMethod.title}, SNI=${customSni.ifBlank { server.host }})",
            )

            val loginRequest = Request.Builder()
                .url("$baseUrl/api/v1/login")
                .header("User-Agent", CHROME_USER_AGENT)
                .header("Accept", "application/json")
                .post(loginPayload.toRequestBody(jsonMediaType))
                .build()

            val accessToken: String
            var assignedClientIp = DEFAULT_TUN_CLIENT_IP
            httpClient.newCall(loginRequest).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val errInfo = classifyHttpFailure(
                        endpoint = "/api/v1/login",
                        code = response.code,
                        bodyPreview = bodyStr.take(180),
                    )
                    return Result.failure(FptnConnectionException(errInfo))
                }
                val json = runCatching { JSONObject(bodyStr) }.getOrNull()
                accessToken = json?.optString("access_token", "").orEmpty()
                    .ifBlank { json?.optString("token", "").orEmpty() }
                if (accessToken.isBlank()) {
                    return Result.failure(
                        FptnConnectionException(
                            VpnErrorInfo(
                                title = "Неожиданный ответ сервера",
                                details = "Ответ /api/v1/login (HTTP ${response.code}) не содержит access_token: ${bodyStr.take(100)}",
                                hint = "Если сервер работает в режиме Reality/BoringSSL, ему требуется нативная библиотека libfptn (session_id в ClientHello).",
                            ),
                        ),
                    )
                }
                val maybeIp = json?.optString("tun_ip", "")?.trim().orEmpty()
                if (maybeIp.isNotEmpty()) {
                    assignedClientIp = maybeIp
                }
            }

            // Query server DNS while strictly preventing client IP == DNS gateway collision.
            var resolvedDns = resolveSafeDns(fallbackDns, assignedClientIp)
            val dnsRequest = Request.Builder()
                .url("$baseUrl/api/v1/dns")
                .header("Authorization", "Bearer $accessToken")
                .header("User-Agent", CHROME_USER_AGENT)
                .get()
                .build()

            runCatching {
                httpClient.newCall(dnsRequest).execute().use { dnsResp ->
                    if (dnsResp.isSuccessful) {
                        val dnsJson = JSONObject(dnsResp.body?.string().orEmpty())
                        val serverDns = dnsJson.optString("dns", "").trim()
                        if (serverDns.isNotEmpty()) {
                            resolvedDns = resolveSafeDns(serverDns, assignedClientIp)
                        }
                    }
                }
            }

            Result.success(
                FptnHandshakeResult(
                    accessToken = accessToken,
                    clientTunIp = assignedClientIp,
                    serverDnsIp = resolvedDns,
                ),
            )
        } catch (ex: FptnConnectionException) {
            Result.failure(ex)
        } catch (ex: Exception) {
            Result.failure(FptnConnectionException(classifyException(ex, server, customSni), ex))
        }
    }

    fun openTunnelWebSocket(
        accessToken: String,
        clientTunIp: String,
        onPacketReceived: (ByteArray) -> Unit,
        onClosedOrFailed: (String) -> Unit,
    ): Result<Unit> {
        // Try primary upstream path /fptn first, then fallback to /api/v1/tun
        val paths = listOf("/fptn", "/api/v1/tun")
        var lastError: Throwable? = null

        for (path in paths) {
            val attempt = tryOpenWebSocketPath(
                path = path,
                accessToken = accessToken,
                clientTunIp = clientTunIp,
                onPacketReceived = onPacketReceived,
                onClosedOrFailed = onClosedOrFailed,
            )
            if (attempt.isSuccess) return attempt
            lastError = attempt.exceptionOrNull()
            AppLogStore.warn("FptnProto", "WebSocket $path не открылся: ${lastError?.message}")
        }

        val errInfo = when (lastError) {
            is FptnConnectionException -> lastError.errorInfo
            else -> classifyException(lastError ?: IllegalStateException("WebSocket failed"), server, customSni)
        }
        return Result.failure(FptnConnectionException(errInfo, lastError))
    }

    private fun tryOpenWebSocketPath(
        path: String,
        accessToken: String,
        clientTunIp: String,
        onPacketReceived: (ByteArray) -> Unit,
        onClosedOrFailed: (String) -> Unit,
    ): Result<Unit> {
        val openLatch = CountDownLatch(1)
        val openError = AtomicReference<Throwable?>(null)

        val wsRequest = Request.Builder()
            .url("wss://${server.host}:${server.port}$path")
            .header("Authorization", "Bearer $accessToken")
            .header("ClientIP", clientTunIp)
            .header("ClientVpnIP", clientTunIp)
            .header("User-Agent", CHROME_USER_AGENT)
            .header("X-FPTN-Fingerprint", bypassMethod.name)
            .build()

        val ws = httpClient.newWebSocket(
            wsRequest,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    AppLogStore.info("FptnProto", "WebSocket туннель $path установлен")
                    openLatch.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val ipPacket = decodeProtobufIpPacket(bytes.toByteArray())
                    if (ipPacket.isNotEmpty()) {
                        onPacketReceived(ipPacket)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    onClosedOrFailed("Туннель закрыт сервером ($code): $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detailed = if (response != null) {
                        val body = runCatching { response.body?.string()?.take(120) }.getOrNull().orEmpty()
                        FptnConnectionException(
                            classifyHttpFailure(path, response.code, body.ifBlank { t.message.orEmpty() }),
                            t,
                        )
                    } else {
                        t
                    }
                    openError.compareAndSet(null, detailed)
                    openLatch.countDown()
                    onClosedOrFailed("Ошибка канала FPTN ($path): ${detailed.message ?: t.javaClass.simpleName}")
                }
            },
        )

        val opened = openLatch.await(10, TimeUnit.SECONDS)
        if (!opened) {
            runCatching { ws.cancel() }
            return Result.failure(
                FptnConnectionException(
                    VpnErrorInfo(
                        title = "Таймаут открытия туннеля",
                        details = "Сервер ${server.endpoint} не ответил на WebSocket $path за 10 сек.",
                        hint = "Попробуйте другой сервер, включите «Автосканирование SNI» или переключите метод обхода.",
                    ),
                ),
            )
        }
        val err = openError.get()
        if (err != null) {
            runCatching { ws.cancel() }
            return Result.failure(err)
        }
        webSocket = ws
        return Result.success(Unit)
    }

    fun sendIpPacket(packet: ByteArray, length: Int): Boolean {
        val ws = webSocket ?: return false
        val framed = encodeProtobufIpPacket(packet, length)
        return ws.send(framed.toByteString())
    }

    fun close() {
        runCatching { webSocket?.close(1000, "Client disconnected") }
        webSocket = null
        runCatching { httpClient.dispatcher.executorService.shutdown() }
        runCatching { httpClient.connectionPool.evictAll() }
    }

    private fun buildHttpClient(): OkHttpClient {
        val trustManager = FingerprintTrustManager(server.md5Fingerprint)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val baseSslFactory = sslContext.socketFactory
        val sniToUse = customSni.trim().takeIf { it.isNotEmpty() }
        val protectedSslFactory = ProtectingSniSslSocketFactory(
            delegate = baseSslFactory,
            sniHost = sniToUse,
            protectSocket = protectSocket,
        )

        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .socketFactory(ProtectingSocketFactory(protectSocket))
            .sslSocketFactory(protectedSslFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    companion object {
        const val DEFAULT_TUN_CLIENT_IP = "10.10.0.2"
        const val SAFE_FALLBACK_DNS = "1.1.1.1"
        private const val CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

        fun classifyHttpFailure(endpoint: String, code: Int, bodyPreview: String): VpnErrorInfo {
            return when (code) {
                401, 403 -> VpnErrorInfo(
                    title = "Токен отклонён сервером (HTTP $code)",
                    details = "Сервер вернул HTTP $code на $endpoint. ${bodyPreview.take(100)}",
                    hint = "Проверьте актуальность токена FPTN или обновите его у бота/администратора.",
                )
                404, 405, 301, 302 -> VpnErrorInfo(
                    title = "Сервер перенаправил в режим маскировки (HTTP $code)",
                    details = "Запрос $endpoint получил HTTP $code — сервер FPTN принял клиент за обычный HTTPS-трафик или вернул сайт-прикрытие.",
                    hint = "Современные серверы FPTN (v0.4+) проверяют специальный TLS session_id из нативной C++ библиотеки libfptn (BoringSSL). Подробности в логах.",
                )
                else -> VpnErrorInfo(
                    title = "Ошибка сервера FPTN (HTTP $code)",
                    details = "Эндпоинт $endpoint ответил кодом $code: ${bodyPreview.take(120)}",
                    hint = "Попробуйте выбрать другой сервер в списке или проверить доступность узла пингом.",
                )
            }
        }

        fun classifyException(ex: Throwable, server: FptnServer, sni: String): VpnErrorInfo {
            val root = generateSequence(ex) { it.cause }.lastOrNull() ?: ex
            val msg = root.message ?: ex.message ?: root.javaClass.simpleName
            return when {
                root is CertificateException || msg.contains("MD5", ignoreCase = true) -> VpnErrorInfo(
                    title = "Не совпал TLS-сертификат сервера",
                    details = msg,
                    hint = "Частая причина: сервер FPTN в режиме Reality/anti-probing проксирует стандартный Android TLS к домену маскировки ($sni), так как ожидает кастомный session_id от C++ libfptn (BoringSSL), либо сертификат сервера в токене устарел.",
                )
                root is UnknownHostException -> VpnErrorInfo(
                    title = "Не удалось разрешить адрес сервера",
                    details = "DNS не нашёл хост ${server.host}: $msg",
                    hint = "Проверьте подключение к интернету или смените сервер.",
                )
                root is SocketTimeoutException -> VpnErrorInfo(
                    title = "Превышено время ожидания",
                    details = "Сервер ${server.endpoint} не ответил вовремя ($msg).",
                    hint = "Возможна блокировка по IP/SNI оператором. Запустите «Автосканирование SNI» в настройках обхода или выберите другой сервер.",
                )
                root is ConnectException -> VpnErrorInfo(
                    title = "Соединение отклонено сервером",
                    details = "Узел ${server.endpoint} недоступен или порт закрыт ($msg).",
                    hint = "Проверьте пинг серверов на Главной и выберите доступный узел.",
                )
                root is SSLHandshakeException || root is SSLException -> VpnErrorInfo(
                    title = "Сбой TLS-рукопожатия (DPI / SNI)",
                    details = "TLS-соединение с ${server.endpoint} (SNI=$sni) прервано: $msg",
                    hint = "Провайдер мог сбросить соединение по SNI. Попробуйте автосканирование SNI или переключите метод обхода.",
                )
                else -> VpnErrorInfo(
                    title = "Сбой подключения к FPTN",
                    details = msg,
                    hint = "Откройте раздел «Логи» для полного протокола обмена с сервером.",
                )
            }
        }

        fun resolveSafeDns(candidateDns: String, clientTunIp: String): String {
            val cleaned = candidateDns.trim()
            if (cleaned.isEmpty() || cleaned == clientTunIp) {
                return SAFE_FALLBACK_DNS
            }
            return cleaned
        }

        fun encodeProtobufIpPacket(packet: ByteArray, length: Int = packet.size): ByteArray {
            val out = ByteArrayOutputStream(length + 6)
            out.write(0x0A)
            writeVarint(out, length)
            out.write(packet, 0, length)
            return out.toByteArray()
        }

        fun decodeProtobufIpPacket(frame: ByteArray): ByteArray {
            if (frame.isEmpty()) return ByteArray(0)
            val versionNibble = (frame[0].toInt() ushr 4) and 0x0F
            if (versionNibble == 4 || versionNibble == 6) {
                return frame
            }
            if (frame[0] == 0x0A.toByte()) {
                var offset = 1
                var len = 0
                var shift = 0
                while (offset < frame.size && shift < 35) {
                    val b = frame[offset++].toInt() and 0xFF
                    len = len or ((b and 0x7F) shl shift)
                    if ((b and 0x80) == 0) break
                    shift += 7
                }
                if (len > 0 && offset + len <= frame.size) {
                    return frame.copyOfRange(offset, offset + len)
                }
            }
            return frame
        }

        private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
            var v = value
            while ((v and 0x7F.inv()) != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v and 0x7F)
        }
    }
}

internal class FingerprintTrustManager(
    private val expectedMd5Hex: String,
) : X509TrustManager {
    private val normalizedExpected: String = expectedMd5Hex.replace(":", "").trim()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (normalizedExpected.length != 32 || !normalizedExpected.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            throw CertificateException("Отсутствует или некорректен обязательный MD5-отпечаток сертификата сервера.")
        }
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("Сервер не предоставил X.509 сертификат.")
        val digest = MessageDigest.getInstance("MD5").digest(leaf.encoded)
        val actualHex = digest.joinToString("") { "%02x".format(it) }
        if (!actualHex.equals(normalizedExpected, ignoreCase = true)) {
            val subject = runCatching { leaf.subjectX500Principal?.name }.getOrNull().orEmpty()
            throw CertificateException(
                "Отпечаток MD5 сертификата не совпадает (ожидался $expectedMd5Hex, получен $actualHex; CN: $subject).",
            )
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private class ProtectingSocketFactory(
    private val protectSocket: (Socket) -> Boolean,
) : SocketFactory() {
    override fun createSocket(): Socket = Socket().also { protectSocket(it) }

    override fun createSocket(host: String, port: Int): Socket =
        createProtectedAndConnected(java.net.InetSocketAddress(host, port), null)

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        createProtectedAndConnected(
            java.net.InetSocketAddress(host, port),
            java.net.InetSocketAddress(localHost, localPort),
        )

    override fun createSocket(host: InetAddress, port: Int): Socket =
        createProtectedAndConnected(java.net.InetSocketAddress(host, port), null)

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        createProtectedAndConnected(
            java.net.InetSocketAddress(address, port),
            java.net.InetSocketAddress(localAddress, localPort),
        )

    private fun createProtectedAndConnected(
        remote: java.net.InetSocketAddress,
        local: java.net.InetSocketAddress?,
    ): Socket {
        val socket = Socket()
        protectSocket(socket)
        try {
            if (local != null) socket.bind(local)
            socket.connect(remote, 10_000)
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}

private class ProtectingSniSslSocketFactory(
    private val delegate: SSLSocketFactory,
    private val sniHost: String?,
    private val protectSocket: (Socket) -> Boolean,
) : SSLSocketFactory() {

    private val plainFactory = ProtectingSocketFactory(protectSocket)

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        protectSocket(s)
        return configureSni(delegate.createSocket(s, host, port, autoClose))
    }

    override fun createSocket(host: String, port: Int): Socket {
        val raw = plainFactory.createSocket(host, port)
        return configureSni(delegate.createSocket(raw, host, port, true))
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val raw = plainFactory.createSocket(host, port, localHost, localPort)
        return configureSni(delegate.createSocket(raw, host, port, true))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val raw = plainFactory.createSocket(host, port)
        return configureSni(delegate.createSocket(raw, host.hostAddress.orEmpty(), port, true))
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val raw = plainFactory.createSocket(address, port, localAddress, localPort)
        return configureSni(delegate.createSocket(raw, address.hostAddress.orEmpty(), port, true))
    }

    private fun configureSni(socket: Socket): Socket {
        if (socket is SSLSocket && !sniHost.isNullOrBlank()) {
            runCatching {
                val params = socket.sslParameters
                params.serverNames = listOf(SNIHostName(sniHost))
                socket.sslParameters = params
            }
        }
        return socket
    }
}
