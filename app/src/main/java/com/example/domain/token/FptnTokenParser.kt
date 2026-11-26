package com.example.domain.token

import com.example.domain.model.FptnServer
import org.brotli.dec.BrotliInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class ParsedToken(
    val version: Int,
    val serviceName: String,
    val username: String,
    val password: String,
    val servers: List<FptnServer>,
    val normalizedRawToken: String,
)

sealed class TokenParseResult {
    data class Success(val token: ParsedToken) : TokenParseResult()
    data class Failure(val reason: String) : TokenParseResult()
}

/**
 * Parses real FPTN access tokens across all official and self-hosted variants:
 * - fptnb:<Base64(Brotli(JSON))>
 * - fptn:<Base64(JSON)>
 * - fptn://<Base64(JSON)>
 * - Raw JSON string
 *
 * Tolerates multiline Base64 (line breaks every 76 chars), whitespace, URL-safe Base64 ('-' / '_'),
 * and omitted '=' padding.
 */
object FptnTokenParser {

    private const val PREFIX_BROTLI = "fptnb:"
    private const val PREFIX_BROTLI_URI = "fptnb://"
    private const val PREFIX_STANDARD = "fptn:"
    private const val PREFIX_STANDARD_URI = "fptn://"
    private const val MAX_DECOMPRESSED_BYTES = 2 * 1024 * 1024

    fun parse(rawInput: String): TokenParseResult {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) {
            return TokenParseResult.Failure("Токен пуст. Вставьте реальный токен FPTN.")
        }

        val jsonResult = decodeToJsonString(trimmed)
        val jsonString = when (jsonResult) {
            is DecodeResult.Ok -> jsonResult.json
            is DecodeResult.Err -> return TokenParseResult.Failure(jsonResult.message)
        }

        return parseJsonPayload(jsonString, trimmed)
    }

    private sealed class DecodeResult {
        data class Ok(val json: String) : DecodeResult()
        data class Err(val message: String) : DecodeResult()
    }

    private fun decodeToJsonString(input: String): DecodeResult {
        if (input.startsWith("{")) {
            return DecodeResult.Ok(input)
        }

        val lower = input.lowercase()
        val isBrotli: Boolean
        val encodedBody: String

        when {
            lower.startsWith(PREFIX_BROTLI_URI) -> {
                isBrotli = true
                encodedBody = input.substring(PREFIX_BROTLI_URI.length)
            }
            lower.startsWith(PREFIX_BROTLI) -> {
                isBrotli = true
                encodedBody = input.substring(PREFIX_BROTLI.length)
            }
            lower.startsWith(PREFIX_STANDARD_URI) -> {
                isBrotli = false
                encodedBody = input.substring(PREFIX_STANDARD_URI.length)
            }
            lower.startsWith(PREFIX_STANDARD) -> {
                isBrotli = false
                encodedBody = input.substring(PREFIX_STANDARD.length)
            }
            else -> {
                isBrotli = false
                encodedBody = input
            }
        }

        val rawBytes = decodeFlexibleBase64(encodedBody)
            ?: return DecodeResult.Err("Не удалось декодировать Base64. Проверьте целостность токена.")

        if (isBrotli) {
            return decompressBrotli(rawBytes)
        }

        val decodedText = String(rawBytes, StandardCharsets.UTF_8).trim()
        return if (decodedText.startsWith("{")) {
            DecodeResult.Ok(decodedText)
        } else {
            // Some self-hosted exporters omit the 'b' prefix while still compressing with Brotli.
            decompressBrotli(rawBytes)
        }
    }

    private fun decompressBrotli(compressed: ByteArray): DecodeResult {
        return try {
            BrotliInputStream(ByteArrayInputStream(compressed)).use { brotli ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var totalRead = 0
                while (true) {
                    val read = brotli.read(buffer)
                    if (read <= 0) break
                    totalRead += read
                    if (totalRead > MAX_DECOMPRESSED_BYTES) {
                        return DecodeResult.Err("Слишком большой распакованный токен.")
                    }
                    out.write(buffer, 0, read)
                }
                val text = out.toString(StandardCharsets.UTF_8.name()).trim()
                if (text.startsWith("{")) {
                    DecodeResult.Ok(text)
                } else {
                    DecodeResult.Err("Распакованные данные Brotli не являются JSON-конфигурацией FPTN.")
                }
            }
        } catch (ex: Exception) {
            DecodeResult.Err("Ошибка распаковки Brotli (fptnb:): ${ex.message ?: "повреждённые данные"}")
        }
    }

    internal fun decodeFlexibleBase64(raw: String): ByteArray? {
        val cleaned = buildString(raw.length) {
            for (ch in raw) {
                if (!ch.isWhitespace()) {
                    when (ch) {
                        '-' -> append('+')
                        '_' -> append('/')
                        else -> append(ch)
                    }
                }
            }
        }
        if (cleaned.isEmpty()) return null

        val mod = cleaned.length % 4
        if (mod == 1) return null
        val padded = when (mod) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }

        return try {
            decodeBase64Rfc4648(padded)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeBase64Rfc4648(input: String): ByteArray {
        val out = ByteArrayOutputStream((input.length * 3) / 4)
        var i = 0
        while (i < input.length) {
            val c0 = base64Value(input[i])
            val c1 = base64Value(input[i + 1])
            val c2Char = input[i + 2]
            val c3Char = input[i + 3]

            if (c0 < 0 || c1 < 0) throw IllegalArgumentException("Invalid Base64 char")
            out.write((c0 shl 2) or (c1 shr 4))

            if (c2Char != '=') {
                val c2 = base64Value(c2Char)
                if (c2 < 0) throw IllegalArgumentException("Invalid Base64 char")
                out.write(((c1 and 0x0F) shl 4) or (c2 shr 2))
                if (c3Char != '=') {
                    val c3 = base64Value(c3Char)
                    if (c3 < 0) throw IllegalArgumentException("Invalid Base64 char")
                    out.write(((c2 and 0x03) shl 6) or c3)
                }
            }
            i += 4
        }
        return out.toByteArray()
    }

    private fun base64Value(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    private fun parseJsonPayload(jsonText: String, rawToken: String): TokenParseResult {
        return try {
            val root = JSONObject(jsonText)
            val version = root.optInt("version", 1)
            val serviceName = root.optString("service_name", "").ifBlank { "FPTN Service" }
            val username = root.optString("username", "").trim()
            val password = root.optString("password", "").trim()

            if (username.isEmpty() || password.isEmpty()) {
                return TokenParseResult.Failure("В токене отсутствуют обязательные поля username или password.")
            }

            val servers = mutableListOf<FptnServer>()
            parseServerArray(root.optJSONArray("servers"), isCensoredZone = false, out = servers)
            parseServerArray(root.optJSONArray("censored_zone_servers"), isCensoredZone = true, out = servers)

            if (servers.isEmpty()) {
                return TokenParseResult.Failure("В токене не найдено ни одного корректного сервера.")
            }

            TokenParseResult.Success(
                ParsedToken(
                    version = version,
                    serviceName = serviceName,
                    username = username,
                    password = password,
                    servers = servers,
                    normalizedRawToken = rawToken,
                ),
            )
        } catch (ex: Exception) {
            TokenParseResult.Failure("Некорректная структура JSON токена: ${ex.message ?: "ошибка разбора"}")
        }
    }

    private fun parseServerArray(
        array: JSONArray?,
        isCensoredZone: Boolean,
        out: MutableList<FptnServer>,
    ) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            val host = obj.optString("host", "")
                .ifBlank { obj.optString("address", "") }
                .ifBlank { obj.optString("ip", "") }
                .trim()
            val port = obj.optInt("port", 443)
            if (host.isEmpty() || port !in 1..65535) continue

            val name = obj.optString("name", "").trim().ifBlank { "$host:$port" }
            val fingerprint = obj.optString("md5_fingerprint", "")
                .ifBlank { obj.optString("fingerprint", "") }
                .replace(":", "")
                .lowercase()
                .trim()

            out.add(
                FptnServer(
                    name = name,
                    host = host,
                    port = port,
                    md5Fingerprint = fingerprint,
                    isCensoredZone = isCensoredZone,
                ),
            )
        }
    }
}
