package com.example.vpn.root

import android.os.Build
import com.example.core.logging.AppLogStore
import com.example.domain.root.SafeRootRulePlanner
import com.example.domain.routing.CidrBlock
import com.example.domain.routing.SmartRfRouter
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object RootShellManager {

    /**
     * Reentrant mutex serializing all Netfilter / ip-rule mutations and teardowns so rapid
     * Connect/Disconnect toggles or network handovers can NEVER interleave iptables commands.
     */
    private val rootOpLock = ReentrantLock()

    private val _isRootAvailable = MutableStateFlow(false)
    val isRootAvailable: StateFlow<Boolean> = _isRootAvailable.asStateFlow()

    private val _areRulesActive = MutableStateFlow(false)
    val areRulesActive: StateFlow<Boolean> = _areRulesActive.asStateFlow()

    private val _lastProbeDetail = MutableStateFlow("Root не проверялся")
    val lastProbeDetail: StateFlow<String> = _lastProbeDetail.asStateFlow()

    private val _rootRevokedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val rootRevokedEvents: SharedFlow<Unit> = _rootRevokedEvents.asSharedFlow()

    @Volatile
    private var activeTunInterfaceName: String = SafeRootRulePlanner.DEFAULT_TUN_NAME

    @Volatile
    private var activeRootServerIpv4: String? = null

    @Volatile
    private var activeRootDnsIpv4: String? = null

    private val dynamicDirectBypassedIps = java.util.Collections.synchronizedSet(linkedSetOf<String>())

    /**
     * Zero-process fast check: returns true when Root was already confirmed and libsu's cached
     * root shell is alive, avoiding redundant `su -c id` spawns and Magisk toast spam on every connect!
     */
    fun isCachedRootSessionAlive(): Boolean {
        if (isRobolectricJvm()) return false
        return _isRootAvailable.value && runCatching { Shell.getCachedShell()?.isRoot == true }.getOrDefault(false)
    }

    fun currentActiveTunName(): String = activeTunInterfaceName

    private val knownSuExecutables = listOf(
        "/debug_ramdisk/su",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/vendor/bin/su",
        "/su/bin/su",
        "/product/bin/su",
        "/odm/bin/su",
    )

    private val knownRootIndicators = knownSuExecutables + listOf(
        "/data/adb/magisk/magisk",
        "/data/adb/ksu/bin/ksud",
        "/data/adb/ap/bin/apd",
    )

    private fun isRobolectricJvm(): Boolean =
        runCatching { Build.FINGERPRINT.equals("robolectric", ignoreCase = true) }.getOrDefault(false)

    @Suppress("DEPRECATION")
    fun configureShellFactory() {
        if (isRobolectricJvm()) return
        runCatching {
            Shell.enableVerboseLogging = false
            Shell.setDefaultBuilder(
                Shell.Builder.create()
                    .setFlags(Shell.FLAG_REDIRECT_STDERR)
                    .setTimeout(5),
            )
        }
    }

    fun hasSuBinaryOnDevice(): Boolean = !isRobolectricJvm() && hasSuInEnvironmentOrCommonPaths()

    private fun hasSuInEnvironmentOrCommonPaths(): Boolean {
        val pathEnv = runCatching { System.getenv("PATH").orEmpty() }.getOrDefault("")
        val pathHit = pathEnv.split(':')
            .filter { it.isNotBlank() && !it.startsWith("/usr") }
            .any { dir -> runCatching { File(dir, "su").exists() }.getOrDefault(false) }
        if (pathHit) return true
        return knownRootIndicators.any { path -> runCatching { File(path).exists() }.getOrDefault(false) }
    }

    /**
     * Spawns a BRAND-NEW `su -c id` child process (bypassing libsu's persistent cached su daemon!).
     * This is critical because revoking Root in Magisk/KernelSU/APatch does NOT kill an already-open
     * libsu shell process — it only rejects NEW `su` invocations.
     */
    private fun probeFreshSuProcess(): Pair<Boolean, String> {
        val candidates = buildList {
            add("su")
            knownSuExecutables.forEach { path ->
                if (runCatching { File(path).exists() }.getOrDefault(false)) {
                    add(path)
                }
            }
        }.distinct()

        var lastReason = "su отклонил запрос"
        for (suBin in candidates) {
            var process: Process? = null
            try {
                process = ProcessBuilder(suBin, "-c", "id")
                    .redirectErrorStream(true)
                    .start()
                val outputBuilder = StringBuilder()
                val readerThread = Thread({
                    runCatching {
                        process.inputStream.bufferedReader().use { reader ->
                            val buf = CharArray(512)
                            var read = reader.read(buf)
                            while (read > 0 && outputBuilder.length < 4096) {
                                outputBuilder.append(buf, 0, read)
                                read = reader.read(buf)
                            }
                        }
                    }
                }, "fptn-su-probe-reader").apply {
                    isDaemon = true
                    start()
                }
                val finished = waitForProcessCompat(process, 2200L)
                if (!finished) {
                    destroyProcessCompat(process)
                    runCatching { readerThread.join(150L) }
                    lastReason = "Таймаут ответа su ($suBin)"
                    continue
                }
                runCatching { readerThread.join(300L) }
                val output = outputBuilder.toString().trim()
                if (process.exitValue() == 0 && output.contains("uid=0")) {
                    return true to output
                }
                if (output.isNotBlank()) {
                    lastReason = output.take(120)
                }
            } catch (ex: Exception) {
                lastReason = ex.message?.take(120) ?: ex.javaClass.simpleName
            } finally {
                runCatching { process?.destroy() }
            }
        }
        return false to lastReason
    }

    private fun waitForProcessCompat(process: Process, timeoutMs: Long): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        }
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            try {
                process.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                if (System.nanoTime() >= deadlineNanos) return false
                Thread.sleep(25L)
            }
        }
    }

    private fun destroyProcessCompat(process: Process) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.destroyForcibly()
        } else {
            process.destroy()
        }
    }

    private fun closeCachedLibsuShell() {
        runCatching { Shell.getCachedShell()?.close() }
    }

    /**
     * Non-intrusive startup root detection compatible with Magisk, KernelSU, and APatch.
     * Always verifies with a fresh `su -c id` check when su is visible so revoked Magisk grants are caught immediately.
     */
    suspend fun detectRootAvailability(): Boolean = withContext(Dispatchers.IO) {
        if (isRobolectricJvm()) {
            _isRootAvailable.value = false
            _lastProbeDetail.value = "Root недоступен в тестовой среде Robolectric"
            return@withContext false
        }

        val suVisible = hasSuInEnvironmentOrCommonPaths()
        if (!suVisible && runCatching { Shell.isAppGrantedRoot() }.getOrNull() != true) {
            _isRootAvailable.value = false
            _lastProbeDetail.value = "Бинарный файл su не обнаружен в PATH (если используется KernelSU/DenyList — запросите вручную в Разрешениях)"
            return@withContext false
        }

        verifyRootNow()
    }

    /**
     * Explicit user-initiated Root probe (for Magisk / KernelSU / APatch where DenyList or
     * kernel-level su hides su paths from File.exists() until su is actually invoked).
     */
    suspend fun requestRootAccessExplicitly(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (isRobolectricJvm()) {
            val msg = "Root-проверка недоступна в тестовой среде"
            _lastProbeDetail.value = msg
            return@withContext false to msg
        }
        AppLogStore.info("RootMode", "Пользователь запросил проверку/выдачу прав Root (Magisk / KernelSU / APatch)…")
        val (freshOk, freshOut) = probeFreshSuProcess()
        val granted = if (freshOk) {
            ensureLibsuRootShell()
            _lastProbeDetail.value = "Root активен ($freshOut)"
            AppLogStore.info("RootMode", "Подтверждён Root-доступ: $freshOut")
            true
        } else {
            closeCachedLibsuShell()
            tryAcquireRootShell()
        }
        _isRootAvailable.value = granted
        val detail = _lastProbeDetail.value
        granted to detail
    }

    /**
     * TRUE live verification of Root access: spawns a fresh `su -c id` process so revoking Root in
     * Magisk / KernelSU / APatch is detected IMMEDIATELY (unlike checking libsu's cached shell).
     * When revoked, cleans up active rules if possible, closes the cached libsu shell, flips
     * [isRootAvailable] to false, and emits [rootRevokedEvents].
     */
    suspend fun verifyRootNow(): Boolean = withContext(Dispatchers.IO) {
        if (isRobolectricJvm()) {
            _isRootAvailable.value = false
            return@withContext false
        }
        val wasAvailable = _isRootAvailable.value
        val (freshOk, freshDetail) = probeFreshSuProcess()
        if (freshOk) {
            ensureLibsuRootShell()
            _isRootAvailable.value = true
            _lastProbeDetail.value = "Root подтверждён ($freshDetail)"
            return@withContext true
        }

        // Fresh su denied -> Root was revoked or is unavailable!
        if (_areRulesActive.value) {
            // Attempt to flush rules via any still-open cached shell BEFORE closing it so user keeps internet!
            cleanupRulesSynchronously()
        }
        closeCachedLibsuShell()
        _isRootAvailable.value = false
        _lastProbeDetail.value = "Root-права отозваны или недоступны ($freshDetail)"
        if (wasAvailable) {
            AppLogStore.warn("RootMode", "Живая проверка su: Root-доступ отозван в Magisk/KernelSU ($freshDetail)")
            _rootRevokedEvents.tryEmit(Unit)
        }
        false
    }

    private fun ensureLibsuRootShell() {
        runCatching {
            val cached = Shell.getCachedShell()
            if (cached == null || !cached.isRoot) {
                Shell.getShell()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun tryAcquireRootShell(): Boolean {
        val primary = runCatching {
            val shell = Shell.getShell()
            if (shell.isRoot) {
                val idRes = Shell.cmd("id").exec()
                val out = idRes.out.joinToString(" ").trim()
                val ok = idRes.isSuccess && out.contains("uid=0")
                if (ok) {
                    _lastProbeDetail.value = "Root активен ($out)"
                    AppLogStore.info("RootMode", "Подтверждён Root-доступ: $out")
                }
                ok
            } else {
                false
            }
        }.getOrDefault(false)

        if (primary) return true

        val fallback = runCatching {
            val altShell = Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(5)
                .build("su")
            val isRoot = altShell.isRoot
            runCatching { altShell.close() }
            if (isRoot) {
                _lastProbeDetail.value = "Root активен (прямой su без MOUNT_MASTER)"
                AppLogStore.info("RootMode", "Root подтверждён через прямой su")
            }
            isRoot
        }.getOrDefault(false)

        if (!fallback) {
            closeCachedLibsuShell()
            _lastProbeDetail.value = "Root-права не получены: убедитесь, что приложение НЕ находится в Magisk DenyList / Umount и разрешите доступ в Magisk/KernelSU."
            AppLogStore.warn("RootMode", _lastProbeDetail.value)
        }
        return fallback
    }

    /**
     * Applies strictly isolated Netfilter chains (FPTN_OUTPUT / FPTN_PREROUTING, table 198,
     * AOSP-safe upper-byte fwmark 0x46000000/0xFF000000 at pref 12600).
     * Reuses the already-open cached libsu root shell (ZERO extra `su -c id` toast spam on reconnect!),
     * and only triggers a fresh live `verifyRootNow()` if the cached shell is absent or fails.
     */
    suspend fun applyIsolatedRouting(
        appUid: Int,
        vpnServerIpv4: String,
        tunInterfaceName: String = SafeRootRulePlanner.DEFAULT_TUN_NAME,
        clientTunIpv4: String = SafeRootRulePlanner.DEFAULT_CLIENT_IPV4,
        dnsServerIpv4: String = "1.1.1.1",
        smartRfEnabled: Boolean,
        excludedUids: Set<Int> = emptySet(),
        systemBypassUids: Set<Int> = SafeRootRulePlanner.DEFAULT_SYSTEM_BYPASS_UIDS,
        allowSecondaryVpnChaining: Boolean = true,
        bypassCidrs: List<CidrBlock> = SmartRfRouter.russianBypassCidrs,
        extraDirectIpv4s: Set<String> = emptySet(),
        extraVpnIpv4s: Set<String> = emptySet(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isCachedRootSessionAlive() && !verifyRootNow()) {
            return@withContext Result.failure(IllegalStateException("Root-доступ не выдан или отозван в Magisk/KernelSU."))
        }

        var needsReverify = false
        val applyResult = rootOpLock.withLock {
            val safeTunName = SafeRootRulePlanner.sanitizeTunName(tunInterfaceName)
            activeTunInterfaceName = safeTunName
            activeRootServerIpv4 = SafeRootRulePlanner.sanitizeIpv4OrNull(vpnServerIpv4)
            activeRootDnsIpv4 = SafeRootRulePlanner.sanitizeIpv4OrNull(dnsServerIpv4)
            dynamicDirectBypassedIps.clear()
            dynamicDirectBypassedIps.addAll(extraDirectIpv4s)

            val commands = SafeRootRulePlanner.buildApplyCommands(
                appUid = appUid,
                vpnServerIpv4 = vpnServerIpv4,
                tunInterfaceName = safeTunName,
                clientTunIpv4 = clientTunIpv4,
                dnsServerIpv4 = dnsServerIpv4,
                smartRfEnabled = smartRfEnabled,
                excludedUids = excludedUids,
                systemBypassUids = systemBypassUids,
                allowSecondaryVpnChaining = allowSecondaryVpnChaining,
                bypassCidrs = bypassCidrs,
                extraDirectIpv4s = extraDirectIpv4s,
                extraVpnIpv4s = extraVpnIpv4s,
            )

            val violations = SafeRootRulePlanner.validateSafety(commands)
            if (violations.isNotEmpty()) {
                val errorMsg = "Блокировка небезопасных Root-правил: ${violations.joinToString("; ")}"
                AppLogStore.error("RootMode", errorMsg)
                return@withLock Result.failure(SecurityException(errorMsg))
            }

            val result = runCatching {
                Shell.cmd(*commands.toTypedArray()).exec()
            }.getOrElse { ex ->
                cleanupRulesSynchronously(safeTunName)
                return@withLock Result.failure(ex)
            }

            if (result.isSuccess) {
                _areRulesActive.value = true
                val chainLabel = if (allowSecondaryVpnChaining) " | VPN-chaining=ON" else ""
                AppLogStore.info(
                    "RootMode",
                    "Изолированные цепочки ${SafeRootRulePlanner.CHAIN_OUTPUT} + ${SafeRootRulePlanner.CHAIN_POSTROUTING_MSS} активированы (dev=$safeTunName, UID bypass=$appUid + NetworkStack ${systemBypassUids.size}, CIDRs=${bypassCidrs.size}, directIPs=${extraDirectIpv4s.size}, table=${SafeRootRulePlanner.ROUTING_TABLE_ID}$chainLabel)",
                )
                Result.success(Unit)
            } else {
                val errOutput = result.err.joinToString(" | ").ifBlank { "exit=${result.code}" }
                AppLogStore.error("RootMode", "Сбой применения Root-правил ($errOutput). Выполняется откат.")
                cleanupRulesSynchronously(safeTunName)
                needsReverify = true
                Result.failure(IllegalStateException("Не удалось применить Root-правила: $errOutput"))
            }
        }

        if (needsReverify) {
            // Reactively re-check if Root was revoked in Magisk/KernelSU outside the lock
            verifyRootNow()
        }
        applyResult
    }

    /**
     * Lightweight handover route/MTU refresh using the already-open cached root shell
     * (zero Magisk toast spam, <40ms) to keep kernel TUN policy routing seamless across
     * Wi-Fi <-> Cellular and cell-tower handovers.
     */
    suspend fun refreshRoutingOnNetworkChange(
        tunInterfaceName: String = activeTunInterfaceName,
        clientTunIpv4: String = SafeRootRulePlanner.DEFAULT_CLIENT_IPV4,
        mtu: Int = 1360,
    ): Boolean = withContext(Dispatchers.IO) {
        rootOpLock.withLock {
            if (!_areRulesActive.value) return@withLock false
            val safeTunName = SafeRootRulePlanner.sanitizeTunName(tunInterfaceName)
            val cmds = SafeRootRulePlanner.buildNetworkRefreshCommands(
                tunInterfaceName = safeTunName,
                clientTunIpv4 = clientTunIpv4,
                mtu = mtu,
            )
            runCatching {
                val shell = Shell.getCachedShell()
                if (shell != null && shell.isRoot) {
                    val res = Shell.cmd(*cmds.toTypedArray()).exec()
                    if (res.isSuccess) {
                        AppLogStore.info("RootMode", "Маршруты $safeTunName обновлены после смены сети (MTU=$mtu)")
                    }
                    res.isSuccess
                } else {
                    false
                }
            }.getOrDefault(false)
        }
    }

    /**
     * Dynamically installs a `/32 RETURN` rule in `FPTN_OUTPUT` for newly DNS-resolved `.ru` / custom-direct
     * IPv4 addresses during an active Root Stealth session so browsers immediately bypass VPN on the first SYN.
     */
    fun addDynamicDirectBypassIps(ipv4s: Collection<String>): Int {
        if (!_areRulesActive.value || ipv4s.isEmpty()) return 0
        val cmds = mutableListOf<String>()
        for (rawIp in ipv4s) {
            val safeIp = SafeRootRulePlanner.sanitizeIpv4OrNull(rawIp) ?: continue
            if (dynamicDirectBypassedIps.size >= 384) break
            if (!dynamicDirectBypassedIps.add(safeIp)) continue
            val cmd = SafeRootRulePlanner.buildDynamicDirectBypassCommand(
                ipv4 = safeIp,
                vpnServerIpv4 = activeRootServerIpv4,
                dnsServerIpv4 = activeRootDnsIpv4,
            ) ?: continue
            cmds.add(cmd)
        }
        if (cmds.isEmpty()) return 0
        return rootOpLock.withLock {
            if (!_areRulesActive.value) return@withLock 0
            runCatching {
                val shell = Shell.getCachedShell()
                if (shell != null && shell.isRoot) {
                    Shell.cmd(*cmds.toTypedArray()).exec()
                    cmds.size
                } else {
                    0
                }
            }.getOrDefault(0)
        }
    }

    /**
     * Removes iptables chains and policy routing rules immediately (before closing TUN FD)
     * so normal traffic resumes instantly.
     */
    fun cleanupRulesOnlySynchronously(): Boolean = rootOpLock.withLock {
        dynamicDirectBypassedIps.clear()
        val cleanupCommands = SafeRootRulePlanner.buildRulesOnlyCleanupCommands()
        val executed = runCatching {
            if (Shell.getCachedShell()?.isRoot == true || _isRootAvailable.value) {
                Shell.cmd(*cleanupCommands.toTypedArray()).exec().isSuccess
            } else {
                true
            }
        }.getOrDefault(false)
        _areRulesActive.value = false
        executed
    }

    /**
     * Tears down any residual kernel TUN interface after our TUN FileDescriptor is closed.
     */
    fun cleanupTunOnlySynchronously(tunName: String = activeTunInterfaceName): Boolean = rootOpLock.withLock {
        val namesToClean = linkedSetOf(
            SafeRootRulePlanner.sanitizeTunName(tunName),
            SafeRootRulePlanner.sanitizeTunName(activeTunInterfaceName),
        )
        val cleanupCommands = namesToClean.flatMap { SafeRootRulePlanner.buildTunCleanupCommands(it) }
        runCatching {
            if (Shell.getCachedShell()?.isRoot == true || _isRootAvailable.value) {
                Shell.cmd(*cleanupCommands.toTypedArray()).exec().isSuccess
            } else {
                true
            }
        }.getOrDefault(false)
    }

    /**
     * Synchronously removes all FPTN Netfilter chains, policy routing rules, and kernel TUN interface.
     */
    fun cleanupRulesSynchronously(tunName: String = activeTunInterfaceName): Boolean = rootOpLock.withLock {
        val safeTunName = SafeRootRulePlanner.sanitizeTunName(tunName)
        val namesToClean = linkedSetOf(
            safeTunName,
            SafeRootRulePlanner.sanitizeTunName(activeTunInterfaceName),
            SafeRootRulePlanner.DEFAULT_TUN_NAME,
        )
        val cleanupCommands = SafeRootRulePlanner.buildRulesOnlyCleanupCommands() +
            namesToClean.flatMap { SafeRootRulePlanner.buildTunCleanupCommands(it) }
        val executed = runCatching {
            if (Shell.getCachedShell()?.isRoot == true || _isRootAvailable.value) {
                val res = Shell.cmd(*cleanupCommands.toTypedArray()).exec()
                res.isSuccess
            } else {
                true
            }
        }.getOrDefault(false)

        if (_areRulesActive.value || executed) {
            AppLogStore.info(
                "RootMode",
                "Синхронная очистка изолированных Root-цепочек (${SafeRootRulePlanner.CHAIN_OUTPUT}) и $safeTunName завершена",
            )
        }
        _areRulesActive.value = false
        executed
    }

    suspend fun sweepOrphanRulesOnStartup() = withContext(Dispatchers.IO) {
        val rootAvailable = detectRootAvailability()
        if (rootAvailable) {
            cleanupRulesSynchronously()
        }
    }
}
