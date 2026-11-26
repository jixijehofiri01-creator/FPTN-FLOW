package com.example.domain.vpn

import com.example.domain.model.VpnErrorInfo
import java.util.Locale

/**
 * Classifies native libfptn_native_lib.so error codes (including custom code 609 = Port not reachable)
 * and normalizes unset tunnel IP addresses ("0.0.0.0" -> "10.10.0.1").
 */
object VpnErrorClassifier {

    fun isTlsStreamTruncatedOrReset(code: Int, errorMessage: String?): Boolean {
        val msg = errorMessage.orEmpty()
        return code == 600 ||
            code == 0 ||
            msg.contains("stream truncated", ignoreCase = true) ||
            msg.contains("asio.ssl", ignoreCase = true) ||
            msg.contains("connection reset", ignoreCase = true) ||
            msg.contains("handshake", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("eof", ignoreCase = true) ||
            msg.contains("broken pipe", ignoreCase = true)
    }

    /**
     * Returns true when switching censorship strategy (SNI-REALITY -> SNI -> OBFUSCATION) or SNI
     * can recover the connection. Strictly excludes authentication errors (401/403), unreachable
     * server ports (609), and server-side HTTP 5xx outages.
     */
    fun isHealableConnectionError(code: Int, errorMessage: String?): Boolean {
        val msg = errorMessage.orEmpty()
        if (code == 401 || code == 403 || code == 609 || code in 500..599) return false
        if (msg.contains("port not reachable", ignoreCase = true)) return false
        return isTlsStreamTruncatedOrReset(code, msg) ||
            code == -1 ||
            code >= 600 ||
            msg.contains("tls", ignoreCase = true) ||
            msg.contains("ssl", ignoreCase = true) ||
            msg.contains("socket", ignoreCase = true)
    }

    /**
     * Returns true when switching to another server in the user's pool can resolve the failure
     * (port unreachable 609, connection timeout/reset, or 5xx server outage — never 401/403 auth).
     */
    fun shouldAutoFailoverServer(code: Int, errorMessage: String?): Boolean {
        if (code == 401 || code == 403) return false
        val msg = errorMessage.orEmpty()
        return code == 609 ||
            code == 0 ||
            code in 500..599 ||
            msg.contains("port not reachable", ignoreCase = true) ||
            msg.contains("connection refused", ignoreCase = true) ||
            msg.contains("timed out", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true)
    }

    fun classifyNativeHttpFailure(
        code: Int,
        errorMessage: String?,
        body: String?,
        endpoint: String,
        censorshipStrategy: String,
        sniHost: String,
        hasPhysicalNetwork: Boolean = true,
        isRussian: Boolean = Locale.getDefault().language.equals("ru", ignoreCase = true),
    ): VpnErrorInfo {
        if (!hasPhysicalNetwork) {
            return if (isRussian) {
                VpnErrorInfo(
                    title = "Нет доступа к сети",
                    details = "Отсутствует активное подключение Wi-Fi или мобильной сети при обращении к $endpoint.",
                    hint = "Проверьте интернет-соединение на устройстве и повторите попытку.",
                )
            } else {
                VpnErrorInfo(
                    title = "No network connection",
                    details = "Wi-Fi or mobile data is unavailable while connecting to $endpoint.",
                    hint = "Check your internet connection and try again.",
                )
            }
        }

        val errDetail = buildString {
            if (!errorMessage.isNullOrBlank()) {
                append(errorMessage.trim())
            }
            if (!body.isNullOrBlank()) {
                if (isNotEmpty()) append(" | ")
                append(body.trim().take(160))
            }
            if (isEmpty()) {
                append(
                    if (isRussian) {
                        "Сервер $endpoint не ответил (код $code)"
                    } else {
                        "Server $endpoint did not respond (code $code)"
                    },
                )
            }
        }

        val isPortUnreachable = code == 609 ||
            errDetail.contains("port not reachable", ignoreCase = true)
        val isTlsTruncated = code == 600 ||
            errDetail.contains("stream truncated", ignoreCase = true) ||
            errDetail.contains("asio.ssl", ignoreCase = true)

        return if (isRussian) {
            when {
                isPortUnreachable -> VpnErrorInfo(
                    title = "Порт сервера недоступен (код 609)",
                    details = "Сервер $endpoint не принял соединение: $errDetail",
                    hint = "Сервер недоступен или его порт фильтруется в вашей сети. Выберите другой сервер или включите автовыбор.",
                )
                isTlsTruncated -> VpnErrorInfo(
                    title = "TLS-соединение сброшено (код $code)",
                    details = "$errDetail (метод=$censorshipStrategy, SNI=$sniHost)",
                    hint = "Соединение прервано на текущем SNI. Нажмите «Авто-починка», переключите метод обхода или выберите другой SNI.",
                )
                code == 401 || code == 403 -> VpnErrorInfo(
                    title = "Токен отклонён сервером (HTTP $code)",
                    details = errDetail,
                    hint = "Обновите токен в разделе «Токены и серверы» или проверьте срок действия профиля.",
                )
                code == 0 -> VpnErrorInfo(
                    title = "Не удалось установить TLS-соединение",
                    details = "$errDetail (метод=$censorshipStrategy, SNI=$sniHost)",
                    hint = "Проверьте интернет, нажмите «Авто-починка» или выберите другой сервер.",
                )
                code in 500..599 -> VpnErrorInfo(
                    title = "Сервер FPTN временно недоступен (HTTP $code)",
                    details = errDetail,
                    hint = "Сервер перегружен или обслуживается. Выберите другой сервер из списка.",
                )
                else -> VpnErrorInfo(
                    title = "Ошибка подключения к серверу (код $code)",
                    details = errDetail,
                    hint = "Попробуйте «Авто-починка», другой сервер или откройте «Журнал» для подробностей.",
                )
            }
        } else {
            when {
                isPortUnreachable -> VpnErrorInfo(
                    title = "Server port unreachable (code 609)",
                    details = "Server $endpoint refused or dropped the connection: $errDetail",
                    hint = "The server may be offline or its port filtered on this network. Select another server or enable Auto.",
                )
                isTlsTruncated -> VpnErrorInfo(
                    title = "TLS handshake reset (code $code)",
                    details = "$errDetail (method=$censorshipStrategy, SNI=$sniHost)",
                    hint = "The TLS session was interrupted for this SNI. Tap Auto-fix, switch bypass method, or change SNI.",
                )
                code == 401 || code == 403 -> VpnErrorInfo(
                    title = "Token rejected by server (HTTP $code)",
                    details = errDetail,
                    hint = "Update your token in Tokens and servers or verify that the profile is still active.",
                )
                code == 0 -> VpnErrorInfo(
                    title = "Failed to establish TLS connection",
                    details = "$errDetail (method=$censorshipStrategy, SNI=$sniHost)",
                    hint = "Check your internet connection, tap Auto-fix, or pick another server.",
                )
                code in 500..599 -> VpnErrorInfo(
                    title = "FPTN server temporarily unavailable (HTTP $code)",
                    details = errDetail,
                    hint = "The server is overloaded or under maintenance. Try another server from the list.",
                )
                else -> VpnErrorInfo(
                    title = "Server connection error (code $code)",
                    details = errDetail,
                    hint = "Try Auto-fix, switch to another server, or inspect Logs for details.",
                )
            }
        }
    }

    fun normalizeAssignedIp(rawIp: String?, fallback: String = "10.10.0.1"): String {
        val cleaned = rawIp?.trim().orEmpty()
        if (cleaned.isEmpty() || cleaned == "0.0.0.0" || cleaned == "::") {
            return fallback
        }
        return cleaned
    }
}
