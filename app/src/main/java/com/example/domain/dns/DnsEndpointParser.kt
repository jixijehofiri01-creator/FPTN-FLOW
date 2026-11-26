package com.example.domain.dns

import com.example.domain.model.DnsCategory
import com.example.domain.model.SavedDnsEntry
import com.example.domain.model.VpnSettings
import com.example.domain.routing.CidrBlock
import org.json.JSONArray
import org.json.JSONObject

enum class DnsEndpointType {
    IPV4,
    DOH,
    DOT_HOST,
}

data class ParsedDnsEndpoint(
    val rawInput: String,
    val type: DnsEndpointType,
    val host: String,
    val dohUrl: String?,
    val ipv4Fallback: String?,
)

/**
 * Parses classic IPv4 DNS, DoH URLs (https://magicinfo.art/dns-query, https://dns.comss.one/dns-query),
 * and DoT/DoH hostnames (dns.google, dns.comss.one, dns.malw.link).
 * Also serializes/deserializes the user-editable Smart DNS list (add, delete, merge new defaults).
 * Used strictly inside the active VPN tunnel — never mutates system Private DNS outside VPN.
 */
object DnsEndpointParser {

    val defaultEntries: List<SavedDnsEntry>
        get() = VpnSettings.DEFAULT_DNS_ENTRIES

    fun parse(input: String): Result<ParsedDnsEndpoint> {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Адрес DNS пустой."))
        }

        // Case 1: Plain IPv4 address (e.g. 1.1.1.1, 8.8.8.8)
        if (CidrBlock.parseIpv4(trimmed) != null) {
            return Result.success(
                ParsedDnsEndpoint(
                    rawInput = trimmed,
                    type = DnsEndpointType.IPV4,
                    host = trimmed,
                    dohUrl = null,
                    ipv4Fallback = trimmed,
                ),
            )
        }

        // Case 2: Full HTTPS DoH URL (e.g. https://magicinfo.art/dns-query or https://dns.comss.one/dns-query)
        if (trimmed.startsWith("https://", ignoreCase = true)) {
            val withoutScheme = trimmed.substringAfter("://")
            val hostPart = withoutScheme.substringBefore("/").substringBefore(":").trim().lowercase()
            if (hostPart.isEmpty() || !hostPart.contains('.')) {
                return Result.failure(IllegalArgumentException("Некорректная DoH-ссылка: $trimmed"))
            }
            val normalizedUrl = if (withoutScheme.contains('/')) {
                trimmed
            } else {
                "https://$hostPart/dns-query"
            }
            val presetFallback = defaultEntries.firstOrNull {
                it.urlOrHost.equals(normalizedUrl, ignoreCase = true) ||
                    it.urlOrHost.contains(hostPart, ignoreCase = true)
            }?.fallbackIpv4
            return Result.success(
                ParsedDnsEndpoint(
                    rawInput = trimmed,
                    type = DnsEndpointType.DOH,
                    host = hostPart,
                    dohUrl = normalizedUrl,
                    ipv4Fallback = presetFallback,
                ),
            )
        }

        // Case 3: DoT/DoH hostname (e.g. dns.google, dns.comss.one, tls://dns.google, ://bezmezhau.com)
        val cleanedHost = trimmed
            .removePrefix("tls://")
            .removePrefix("dot://")
            .removePrefix("http://")
            .removePrefix("://")
            .substringBefore("/")
            .substringBefore(":")
            .trim()
            .lowercase()

        if (cleanedHost.isEmpty() || !cleanedHost.contains('.') || cleanedHost.contains(' ')) {
            return Result.failure(
                IllegalArgumentException("Укажите IPv4 (8.8.8.8), домен (dns.google) или DoH-ссылку (https://magicinfo.art/dns-query)."),
            )
        }

        val canonicalDohUrl = "https://$cleanedHost/dns-query"
        val presetFallback = defaultEntries.firstOrNull {
            it.urlOrHost.contains(cleanedHost, ignoreCase = true)
        }?.fallbackIpv4

        return Result.success(
            ParsedDnsEndpoint(
                rawInput = trimmed,
                type = DnsEndpointType.DOT_HOST,
                host = cleanedHost,
                dohUrl = canonicalDohUrl,
                ipv4Fallback = presetFallback,
            ),
        )
    }

    fun encodeEntries(entries: List<SavedDnsEntry>): String {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("title", entry.title)
                    put("urlOrHost", entry.urlOrHost)
                    put("subtitle", entry.subtitle)
                    if (entry.fallbackIpv4 != null) put("fallbackIpv4", entry.fallbackIpv4)
                    put("isBuiltIn", entry.isBuiltIn)
                    put("category", entry.category.name)
                },
            )
        }
        return arr.toString()
    }

    fun decodeEntries(json: String?): List<SavedDnsEntry>? {
        if (json == null) return null
        return runCatching {
            val arr = JSONArray(json)
            val decoded = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "").trim()
                    val url = obj.optString("urlOrHost", "").trim()
                    if (id.isEmpty() || url.isEmpty()) continue
                    val isBuiltIn = obj.optBoolean("isBuiltIn", false)
                    val categoryName = obj.optString("category", "")
                    val category = DnsCategory.entries.firstOrNull { it.name == categoryName }
                        ?: inferCategory(id = id, url = url, isBuiltIn = isBuiltIn)
                    add(
                        SavedDnsEntry(
                            id = id,
                            title = obj.optString("title", url),
                            urlOrHost = url,
                            subtitle = obj.optString("subtitle", ""),
                            fallbackIpv4 = obj.optString("fallbackIpv4", "").ifBlank { null },
                            isBuiltIn = isBuiltIn,
                            category = category,
                        ),
                    )
                }
            }
            // If user upgraded from v1.6 (only 5 built-ins, no category field), automatically enrich with new v1.7 Smart DNS entries
            val hadLegacySchema = (0 until arr.length()).any { idx ->
                arr.optJSONObject(idx)?.has("category") == false
            }
            if (hadLegacySchema && decoded.size <= 6) {
                val existingIds = decoded.map { it.id }.toSet()
                val customEntries = decoded.filter { !it.isBuiltIn }
                val mergedDefaults = defaultEntries.map { def ->
                    decoded.firstOrNull { it.id == def.id }?.copy(category = def.category) ?: def
                }
                val extras = customEntries.filter { it.id !in existingIds || !it.isBuiltIn }
                (mergedDefaults + extras).distinctBy { it.id }
            } else {
                decoded
            }
        }.getOrNull()
    }

    private fun inferCategory(id: String, url: String, isBuiltIn: Boolean): DnsCategory {
        defaultEntries.firstOrNull { it.id == id }?.let { return it.category }
        if (!isBuiltIn) return DnsCategory.CUSTOM
        val lower = url.lowercase()
        return when {
            lower.contains("cloudflare") || lower.contains("dns.google") || lower.contains("adguard") ->
                DnsCategory.PUBLIC_RESERVE
            lower.contains(".ru/") || lower.endsWith(".ru") -> DnsCategory.RU_ZONE_AI
            else -> DnsCategory.INTERNATIONAL_AI
        }
    }
}
