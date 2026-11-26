package com.example.domain.model

/** Parsed FPTN subscription profile with credentials and associated VPN servers. */
data class TokenProfile(
    val id: Long = 0L,
    val name: String,
    val rawToken: String,
    val serviceName: String,
    val username: String,
    val password: String,
    val version: Int = 1,
    val enabled: Boolean = true,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val servers: List<FptnServer> = emptyList(),
)

/** Individual VPN server endpoint parsed from an FPTN token. */
data class FptnServer(
    val id: Long = 0L,
    val profileId: Long = 0L,
    val profileName: String = "",
    val name: String,
    val host: String,
    val port: Int,
    val md5Fingerprint: String,
    val isCensoredZone: Boolean = false,
    val pingMs: Int? = null,
    val lastPingAtMillis: Long = 0L,
) {
    private val resolvedLabel: CountryFlagResolver.ResolvedServerLabel
        get() = CountryFlagResolver.resolve(name, host)

    /** ISO-3166-1 alpha-2 country code (e.g. NL, DE, GB) resolved from emoji or server name. */
    val countryCode: String?
        get() = resolvedLabel.countryCode?.let { if (it == "UK") "GB" else it }

    /** Server name with leading or inline Unicode flag emojis removed. */
    val cleanName: String
        get() = resolvedLabel.cleanName

    val cleanDisplayTitle: String
        get() = if (profileName.isNotBlank()) "[$profileName] $cleanName" else cleanName

    val displayTitle: String
        get() = cleanDisplayTitle

    val endpoint: String
        get() = "$host:$port"
}

/** Structured diagnostic payload rendered on VPN connection errors. */
data class VpnErrorInfo(
    val title: String,
    val details: String,
    val hint: String,
)

/** Lifecycle state machine of the active VPN tunnel. */
sealed class VpnConnectionState {
    data object Disconnected : VpnConnectionState()

    data class Connecting(
        val server: FptnServer,
        val stage: String = "Connecting…",
    ) : VpnConnectionState()

    data class SwitchingServer(
        val fromServer: FptnServer?,
        val toServer: FptnServer,
        val stage: String = "Switching server…",
    ) : VpnConnectionState()

    data class Reconnecting(
        val server: FptnServer,
        val attempt: Int,
        val maxAttempts: Int,
        val reason: String,
    ) : VpnConnectionState()

    data class Connected(
        val server: FptnServer,
        val isRootMode: Boolean,
        val assignedIp: String,
        val dnsServer: String,
        val connectedAtMillis: Long,
        val activeDohUrl: String? = null,
    ) : VpnConnectionState()

    data class Disconnecting(
        val stage: String = "Stopping tunnel…",
    ) : VpnConnectionState()

    data class Error(
        val message: String,
        val server: FptnServer? = null,
        val errorInfo: VpnErrorInfo = VpnErrorInfo(
            title = "Connection error",
            details = message,
            hint = "Check logs, token, or choose another server.",
        ),
    ) : VpnConnectionState()

    val isTunnelBusyOrActive: Boolean
        get() = this is Connected ||
            this is Connecting ||
            this is SwitchingServer ||
            this is Reconnecting ||
            this is Disconnecting
}

/** Live and historical throughput metrics sampled from the TUN packet loop. */
data class TrafficStats(
    val downloadBytesPerSec: Long = 0L,
    val uploadBytesPerSec: Long = 0L,
    val totalDownloadBytes: Long = 0L,
    val totalUploadBytes: Long = 0L,
    val downloadHistory: List<Float> = List(24) { 0f },
    val uploadHistory: List<Float> = List(24) { 0f },
)

enum class ServerSortOrder(val title: String) {
    ORIGINAL("По порядку"),
    BY_PING("По пингу"),
}

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isMandatoryRuBypass: Boolean = false,
)

data class SponsorItem(
    val name: String,
    val tier: String,
    val url: String? = null,
)
