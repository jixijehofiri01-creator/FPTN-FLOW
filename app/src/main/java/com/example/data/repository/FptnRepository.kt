package com.example.data.repository

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.core.config.FptnConstants
import com.example.core.logging.AppLogStore
import com.example.data.local.ServerEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.TokenDao
import com.example.data.local.TokenProfileEntity
import com.example.data.local.TokenSecretCipher
import com.example.domain.model.FptnServer
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.TokenProfile
import com.example.domain.model.VpnSettings
import com.example.domain.token.FptnTokenParser
import com.example.domain.token.TokenParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class FptnRepository(
    private val context: Context,
    private val tokenDao: TokenDao,
    private val settingsDataStore: SettingsDataStore,
) {
    val profilesFlow: Flow<List<TokenProfile>> = tokenDao.observeAllProfilesWithServers()
        .map { rows -> rows.map { it.toDomain() } }

    val settingsFlow: Flow<VpnSettings> = settingsDataStore.settingsFlow

    suspend fun getSettings(): VpnSettings = settingsDataStore.getCurrentSettings()

    suspend fun updateSettings(transform: (VpnSettings) -> VpnSettings) {
        settingsDataStore.updateSettings(transform)
    }

    suspend fun addTokenProfile(
        customName: String,
        rawToken: String,
    ): Result<TokenProfile> = withContext(Dispatchers.IO) {
        when (val parsed = FptnTokenParser.parse(rawToken)) {
            is TokenParseResult.Failure -> {
                AppLogStore.warn("TokenParser", "Ошибка добавления токена: ${parsed.reason}")
                Result.failure(IllegalArgumentException(parsed.reason))
            }
            is TokenParseResult.Success -> {
                val token = parsed.token
                val profileName = customName.trim().ifBlank { token.serviceName }
                val profileId = tokenDao.insertProfileWithServers(
                    profile = TokenProfileEntity(
                        name = profileName,
                        rawToken = TokenSecretCipher.encrypt(token.normalizedRawToken),
                        serviceName = token.serviceName,
                        username = TokenSecretCipher.encrypt(token.username),
                        password = TokenSecretCipher.encrypt(token.password),
                        version = token.version,
                        enabled = true,
                    ),
                    serversFactory = { id ->
                        token.servers.map { srv ->
                            ServerEntity(
                                profileId = id,
                                name = srv.name,
                                host = srv.host,
                                port = srv.port,
                                md5Fingerprint = srv.md5Fingerprint,
                                isCensoredZone = srv.isCensoredZone,
                            )
                        }
                    },
                )
                AppLogStore.info(
                    "TokenManager",
                    "Добавлен профиль «$profileName» (${token.servers.size} серверов)",
                )
                val all = tokenDao.getAllProfilesWithServers()
                val created = all.firstOrNull { it.profile.id == profileId }?.toDomain()
                if (created != null) {
                    Result.success(created)
                } else {
                    Result.failure(IllegalStateException("Профиль сохранён, но не найден при чтении."))
                }
            }
        }
    }

    suspend fun renameProfile(profileId: Long, newName: String) {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return
        tokenDao.renameProfile(profileId, cleaned)
        AppLogStore.info("TokenManager", "Профиль #$profileId переименован в «$cleaned»")
    }

    suspend fun setProfileEnabled(profileId: Long, enabled: Boolean) {
        tokenDao.setProfileEnabled(profileId, enabled)
        AppLogStore.info(
            "TokenManager",
            "Профиль #$profileId ${if (enabled) "включён" else "отключён"}",
        )
    }

    suspend fun deleteProfile(profileId: Long) {
        tokenDao.deleteProfile(profileId)
        AppLogStore.info("TokenManager", "Профиль #$profileId удалён")
    }

    suspend fun getAllProfiles(): List<TokenProfile> = withContext(Dispatchers.IO) {
        tokenDao.getAllProfilesWithServers().map { it.toDomain() }
    }

    /**
     * Measures real TCP connection RTT to all enabled servers with bounded concurrency (Semaphore(8))
     * to prevent mobile carrier / CGNAT SYN-burst drops, prefers IPv4 to avoid broken-IPv6 timeouts,
     * and performs a fast staggered retry if all servers failed on pass 1 (e.g. waking radio).
     */
    suspend fun pingServers(
        servers: List<FptnServer>,
        timeoutMs: Int = FptnConstants.DEFAULT_PING_TIMEOUT_MS,
    ): Map<Long, Int?> =
        withContext(Dispatchers.IO) {
            if (servers.isEmpty()) return@withContext emptyMap()
            val limiter = kotlinx.coroutines.sync.Semaphore(permits = 8)

            suspend fun runPass(targets: List<FptnServer>, passTimeoutMs: Int): Map<Long, Int?> =
                coroutineScope {
                    targets.map { server ->
                        async {
                            limiter.withPermit {
                                val rtt = measureTcpPingCancellable(server.host, server.port, passTimeoutMs)
                                server.id to rtt
                            }
                        }
                    }.awaitAll().toMap()
                }

            var results = runPass(servers, timeoutMs)
            // If ZERO servers responded on pass 1 (often cellular radio wakeup or transient route switch),
            // pause 250ms and do one focused retry with a slightly wider timeout.
            if (results.values.all { it == null } && servers.isNotEmpty()) {
                kotlinx.coroutines.delay(250L)
                val retryResults = runPass(servers, (timeoutMs + 800).coerceAtMost(FptnConstants.RETRY_PING_TIMEOUT_MAX_MS))
                results = retryResults
            }

            val now = System.currentTimeMillis()
            for ((serverId, rtt) in results) {
                if (serverId > 0L) {
                    tokenDao.updateServerPing(serverId, rtt, now)
                }
            }
            results
        }

    private suspend fun measureTcpPingCancellable(
        host: String,
        port: Int,
        timeoutMs: Int = FptnConstants.DEFAULT_PING_TIMEOUT_MS,
    ): Int? {
        val socket = Socket()
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) runCatching { socket.close() }
        }
        return try {
            val targetAddr = resolvePreferredIpv4OrFirst(host) ?: return null
            val start = System.nanoTime()
            socket.use { sock ->
                sock.tcpNoDelay = true
                runCatching { sock.setSoLinger(true, 0) }
                sock.connect(InetSocketAddress(targetAddr, port), timeoutMs)
            }
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } finally {
            cancelHandle?.dispose()
            runCatching { socket.close() }
        }
    }

    fun measureTcpPingMs(host: String, port: Int, timeoutMs: Int = FptnConstants.DEFAULT_PING_TIMEOUT_MS): Int? {
        return try {
            // Prefer IPv4 address when resolving hostnames so broken carrier IPv6 doesn't stall TCP SYN!
            val targetAddr = resolvePreferredIpv4OrFirst(host) ?: return null
            val start = System.nanoTime()
            Socket().use { socket ->
                socket.tcpNoDelay = true
                runCatching { socket.setSoLinger(true, 0) }
                socket.connect(InetSocketAddress(targetAddr, port), timeoutMs)
            }
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            null
        }
    }

    private fun resolvePreferredIpv4OrFirst(host: String): java.net.InetAddress? = runCatching {
        val all = java.net.InetAddress.getAllByName(host)
        all.firstOrNull { it is java.net.Inet4Address } ?: all.firstOrNull()
    }.getOrNull()

    /**
     * Discovers launchable user apps via standard LAUNCHER intent query without QUERY_ALL_PACKAGES.
     */
    suspend fun loadLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val selfPkg = context.packageName
        resolved
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == selfPkg) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                InstalledAppInfo(
                    packageName = pkg,
                    appName = label,
                    isMandatoryRuBypass = com.example.domain.routing.ProtectedRuApps.isProtected(context, pkg),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
    }

    suspend fun ensureInstallSeedInitialized() {
        settingsDataStore.ensureInstallSeedInitialized()
    }

    suspend fun pingDnsEntries(
        entries: List<com.example.domain.model.SavedDnsEntry>,
        timeoutMs: Int = FptnConstants.DEFAULT_DNS_PING_TIMEOUT_MS,
    ): Map<String, Int?> = withContext(Dispatchers.IO) {
        coroutineScope {
            entries.map { entry ->
                async {
                    val parsed = com.example.domain.dns.DnsEndpointParser.parse(entry.urlOrHost).getOrNull()
                    val host = parsed?.host ?: entry.fallbackIpv4
                    val port = if (parsed?.type == com.example.domain.dns.DnsEndpointType.IPV4) 53 else 443
                    val rtt = if (!host.isNullOrBlank()) {
                        measureTcpPingCancellable(host, port, timeoutMs)
                    } else {
                        null
                    }
                    entry.id to rtt
                }
            }.awaitAll().toMap()
        }
    }

    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val profiles = tokenDao.getAllProfilesWithServers().map { it.toDomain() }
        val settings = settingsDataStore.getCurrentSettings()
        BackupCodec.encode(profiles, settings)
    }

    suspend fun exportOfficialCompatibleBackupJson(): String = withContext(Dispatchers.IO) {
        val profiles = tokenDao.getAllProfilesWithServers().map { it.toDomain() }
        val settings = settingsDataStore.getCurrentSettings()
        BackupCodec.encodeOfficialCompatible(profiles, settings)
    }

    suspend fun importBackupJson(jsonText: String): Result<Pair<Int, BackupFormat>> = withContext(Dispatchers.IO) {
        val decoded = BackupCodec.decode(jsonText).getOrElse { err ->
            return@withContext Result.failure(err)
        }
        var importedCount = 0
        for (entry in decoded.profiles) {
            val res = addTokenProfile(entry.name, entry.rawToken)
            val created = res.getOrNull()
            if (created != null) {
                if (!entry.enabled) {
                    tokenDao.setProfileEnabled(created.id, false)
                }
                importedCount++
            }
        }
        if (decoded.detectedFormat != BackupFormat.RAW_TOKENS) {
            settingsDataStore.saveSettings(decoded.settings)
        }
        AppLogStore.info(
            "Backup",
            "Импорт (${decoded.detectedFormat.title}): восстановлено профилей=$importedCount",
        )
        Result.success(importedCount to decoded.detectedFormat)
    }
}
