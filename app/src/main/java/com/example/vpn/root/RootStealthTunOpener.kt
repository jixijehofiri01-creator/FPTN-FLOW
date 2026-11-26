package com.example.vpn.root

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import com.example.core.logging.AppLogStore
import com.example.domain.root.SafeRootRulePlanner
import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import com.topjohnwu.superuser.Shell
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * True Stealth Root TUN opener:
 * 1. Primary path (Android 14/15/16 Magisk/KernelSU compatible):
 *    Spawns [RootTunFdHelper] under `su` (`/system/bin/app_process`) with UID 0 + `CAP_NET_ADMIN`
 *    to open `/dev/tun` + `ioctl(TUNSETIFF, "fptn0")` directly in the kernel (no `ip tuntap` needed)
 *    and receives the live `fptn0` FileDescriptor via `SCM_RIGHTS` over an abstract `LocalServerSocket`.
 * 2. Fallback path:
 *    Live SELinux policy + `ip tuntap add` + direct JNA `open("/dev/tun")`.
 *
 * Because `VpnService.Builder.establish()` is NEVER called:
 * - Android shows NO VPN key icon in the status bar.
 * - `ConnectivityManager` registers NO `TRANSPORT_VPN` network (invisible to VPN-detecting apps).
 */
object RootStealthTunOpener {

    private const val O_RDWR = 2
    private const val IFF_TUN: Short = 0x0001
    private const val IFF_NO_PI: Short = 0x1000
    private const val TUNSETIFF: Long = 0x400454caL

    private interface LibC : Library {
        @Throws(LastErrorException::class)
        fun open(path: String, flags: Int): Int

        @Throws(LastErrorException::class)
        fun ioctl(fd: Int, request: Long, arg: ByteArray): Int

        fun close(fd: Int): Int
    }

    private val libc: LibC? by lazy {
        runCatching { Native.load("c", LibC::class.java) }
            .onFailure { ex ->
                AppLogStore.error("StealthTun", "Не удалось загрузить JNA libc: ${ex.message}")
            }
            .getOrNull()
    }

    @Volatile
    private var lastPatchedSelinuxKey: String? = null

    /**
     * Builds valid magiskpolicy / ksud live SELinux rules.
     * Note: `magiskpolicy --live` rejects the keyword `self` as target type ("target type self does not exist"),
     * so we MUST specify `$appSelinuxType` explicitly as both source and target!
     */
    fun buildSelinuxPolicyRules(appSelinuxType: String, suSelinuxType: String): List<String> = listOf(
        "allow $appSelinuxType $suSelinuxType fd use",
        "allow $appSelinuxType $suSelinuxType unix_stream_socket { connectto read write getattr }",
        "allow $suSelinuxType $appSelinuxType unix_stream_socket { connectto read write getattr }",
        "allow $appSelinuxType tun_device chr_file { open read write ioctl getattr }",
        "allow $appSelinuxType $suSelinuxType tun_socket { read write getattr setattr attach_queue relabelfrom relabelto }",
        "allow $appSelinuxType $appSelinuxType tun_socket { create attach_queue read write getattr setattr }",
    )

    fun openStealthTun(
        context: Context,
        appUid: Int,
        tunName: String = SafeRootRulePlanner.DEFAULT_TUN_NAME,
        clientIpv4: String = SafeRootRulePlanner.DEFAULT_CLIENT_IPV4,
        mtu: Int = 1500,
    ): Result<ParcelFileDescriptor> {
        val safeTunName = SafeRootRulePlanner.sanitizeTunName(tunName)
        val appSelinuxType = readSelfSelinuxType() ?: "untrusted_app"
        val probe = Shell.cmd(
            "if [ -c /dev/tun ]; then echo /dev/tun; elif [ -c /dev/net/tun ]; then echo /dev/net/tun; else echo MISSING; fi",
            "cat /proc/self/attr/current 2>/dev/null | cut -d: -f3 || echo magisk",
        ).exec()
        val tunDevicePath = probe.out.getOrNull(0)?.trim().orEmpty()
        if (tunDevicePath.isBlank() || tunDevicePath == "MISSING") {
            return Result.failure(IllegalStateException("Ядро устройства не содержит /dev/tun или /dev/net/tun."))
        }
        val suSelinuxType = probe.out.getOrNull(1)?.trim()
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9_]+")) }
            ?: "magisk"

        AppLogStore.info(
            "StealthTun",
            "Подготовка скрытого kernel TUN ($safeTunName, uid=$appUid, appSE=$appSelinuxType, suSE=$suSelinuxType, dev=$tunDevicePath)…",
        )

        // Apply live SELinux policy ONCE per process so reconnects stay instant and clean
        val selinuxKey = "$appSelinuxType|$suSelinuxType"
        if (lastPatchedSelinuxKey != selinuxKey) {
            val seRules = buildSelinuxPolicyRules(appSelinuxType, suSelinuxType)
            if (applyLiveSelinuxPolicy(seRules)) {
                lastPatchedSelinuxKey = selinuxKey
            }
        }

        // Primary path: Root app_process helper + SCM_RIGHTS FD passing (works on Android 14/15/16!)
        val scmResult = openViaRootScmRightsHelper(
            context = context,
            tunName = safeTunName,
            clientIpv4 = clientIpv4,
            mtu = mtu,
        )
        if (scmResult.isSuccess) {
            return scmResult
        }

        val scmErr = scmResult.exceptionOrNull()?.message ?: "unknown"
        AppLogStore.warn("StealthTun", "SCM_RIGHTS helper не сработал ($scmErr) — пробуем прямой fallback…")

        // Fallback path: direct open("/dev/tun") + ioctl(TUNSETIFF)
        return openDirectFallback(
            appUid = appUid,
            tunDevicePath = tunDevicePath,
            tunName = safeTunName,
            clientIpv4 = clientIpv4,
            mtu = mtu,
            primaryError = scmErr,
        )
    }

    private fun openViaRootScmRightsHelper(
        context: Context,
        tunName: String,
        clientIpv4: String,
        mtu: Int,
    ): Result<ParcelFileDescriptor> {
        val apkPath = context.applicationInfo.sourceDir
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        if (apkPath.isNullOrBlank()) {
            return Result.failure(IllegalStateException("Не найден путь к APK (sourceDir пуст)"))
        }

        val socketName = "fptn_stealth_tun_${Process.myPid()}_${UUID.randomUUID().toString().take(8)}"
        val receivedPfd = AtomicReference<ParcelFileDescriptor?>(null)
        val serverErr = AtomicReference<String?>(null)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val doneLatch = CountDownLatch(1)

        val serverSocket = try {
            LocalServerSocket(socketName)
        } catch (ex: Throwable) {
            return Result.failure(IllegalStateException("LocalServerSocket($socketName) failed: ${ex.message}", ex))
        }

        val acceptThread = Thread({
            var client: LocalSocket? = null
            try {
                client = serverSocket.accept()
                client.soTimeout = 5000
                val marker = client.inputStream.read()
                val fds = client.ancillaryFileDescriptors
                if (marker == 0x4F && !fds.isNullOrEmpty()) {
                    try {
                        val dupPfd = ParcelFileDescriptor.dup(fds[0])
                        if (timedOut.get() || !receivedPfd.compareAndSet(null, dupPfd)) {
                            runCatching { dupPfd.close() }
                        }
                    } finally {
                        // CRITICAL: close all raw SCM_RIGHTS FDs received on the LocalSocket right away!
                        // Otherwise fds[0] stays open forever in our process and locks kernel `fptn0` (EBUSY=16)
                        // on every subsequent reconnect even after TunPacketLoop closes dupPfd.
                        for (fd in fds) {
                            runCatching { Os.close(fd) }
                        }
                    }
                    // Send ACK byte so root helper exits cleanly
                    runCatching {
                        client.outputStream.write(byteArrayOf(0x41))
                        client.outputStream.flush()
                    }
                } else {
                    fds?.forEach { fd -> runCatching { Os.close(fd) } }
                    serverErr.set("Root helper вернул пустой FD (marker=$marker, fds=${fds?.size ?: 0})")
                }
            } catch (ex: Throwable) {
                serverErr.set("${ex.javaClass.simpleName}: ${ex.message}")
            } finally {
                runCatching { client?.close() }
                runCatching { serverSocket.close() }
                doneLatch.countDown()
            }
        }, "FptnStealthFdAccept").apply {
            isDaemon = true
            start()
        }

        val appProcessBin = if (File("/system/bin/app_process64").exists()) {
            "/system/bin/app_process64"
        } else {
            "/system/bin/app_process"
        }
        val escapedApk = apkPath.replace("'", "'\\''")
        val escapedLib = nativeLibDir.orEmpty().replace("'", "'\\''")
        val helperCmd = buildString {
            append("CLASSPATH='$escapedApk' ")
            if (escapedLib.isNotBlank()) {
                append("LD_LIBRARY_PATH='$escapedLib' ")
            }
            append("$appProcessBin /system/bin ")
            append("${RootTunFdHelper::class.java.name} ")
            append("'$socketName' '$tunName' '$clientIpv4' '$mtu'")
        }

        // Run helper in a separate root command thread so we can wait with a clean timeout
        val cmdResultRef = AtomicReference<Shell.Result?>(null)
        val cmdThread = Thread({
            cmdResultRef.set(Shell.cmd(helperCmd).exec())
        }, "FptnStealthHelperRun").apply {
            isDaemon = true
            start()
        }

        val received = doneLatch.await(7, TimeUnit.SECONDS)
        if (!received) {
            timedOut.set(true)
            runCatching { serverSocket.close() }
        }
        runCatching { acceptThread.join(400) }
        runCatching { cmdThread.join(1200) }

        val pfd = receivedPfd.getAndSet(null)
        if (pfd != null && !timedOut.get()) {
            AppLogStore.info(
                "StealthTun",
                "Скрытый интерфейс $tunName ($clientIpv4) открыт через Root SCM_RIGHTS! VpnService не используется — иконка VPN скрыта.",
            )
            return Result.success(pfd)
        } else if (pfd != null) {
            runCatching { pfd.close() }
        }

        val shellRes = cmdResultRef.get()
        val helperLog = buildString {
            val out = shellRes?.out?.joinToString(" | ").orEmpty()
            val err = shellRes?.err?.joinToString(" | ").orEmpty()
            if (err.isNotBlank()) append(err)
            if (out.isNotBlank()) {
                if (isNotEmpty()) append(" | ")
                append(out)
            }
            val sErr = serverErr.get()
            if (!sErr.isNullOrBlank()) {
                if (isNotEmpty()) append(" | ")
                append("socket: $sErr")
            }
            if (isEmpty()) append("таймаут ожидания FD от RootTunFdHelper")
        }
        return Result.failure(IllegalStateException(helperLog))
    }

    private fun openDirectFallback(
        appUid: Int,
        tunDevicePath: String,
        tunName: String,
        clientIpv4: String,
        mtu: Int,
        primaryError: String,
    ): Result<ParcelFileDescriptor> {
        val cLib = libc ?: return Result.failure(
            IllegalStateException("Сбой Stealth TUN: $primaryError (JNA libc недоступна)"),
        )

        val prepRes = Shell.cmd(
            "ip link set dev $tunName down 2>/dev/null || true",
            "ip link del dev $tunName 2>/dev/null || true",
            "ip tuntap del dev $tunName mode tun 2>/dev/null || true",
            "ip tuntap add dev $tunName mode tun user $appUid",
            "ip addr replace $clientIpv4/32 dev $tunName",
            "ip link set dev $tunName mtu $mtu up",
            "chmod 0666 $tunDevicePath",
        ).exec()

        if (!prepRes.isSuccess) {
            val errText = prepRes.err.joinToString(" | ").ifBlank { prepRes.out.joinToString(" | ") }
            AppLogStore.error("StealthTun", "Fallback ip tuntap не удался: $errText | Основная ошибка: $primaryError")
            return Result.failure(
                IllegalStateException("Stealth TUN недоступен: $primaryError (fallback: $errText)"),
            )
        }

        var rawFd = -1
        try {
            rawFd = cLib.open(tunDevicePath, O_RDWR)
            if (rawFd < 0) {
                throw IllegalStateException("open($tunDevicePath) вернул $rawFd")
            }

            val ifr = ByteArray(40)
            val nameBytes = tunName.toByteArray(Charsets.US_ASCII)
            System.arraycopy(nameBytes, 0, ifr, 0, nameBytes.size.coerceAtMost(15))
            val flags = (IFF_TUN.toInt() or IFF_NO_PI.toInt()).toShort()
            ByteBuffer.wrap(ifr, 16, 2).order(ByteOrder.nativeOrder()).putShort(flags)

            val rc = cLib.ioctl(rawFd, TUNSETIFF, ifr)
            if (rc < 0) {
                throw IllegalStateException("ioctl(TUNSETIFF, $tunName) вернул $rc")
            }

            Shell.cmd("chmod 0660 $tunDevicePath 2>/dev/null || true").exec()
            val pfd = ParcelFileDescriptor.adoptFd(rawFd)
            rawFd = -1
            AppLogStore.info(
                "StealthTun",
                "Скрытый интерфейс $tunName ($clientIpv4) открыт через прямой fallback! Иконка VPN скрыта.",
            )
            return Result.success(pfd)
        } catch (ex: Throwable) {
            if (rawFd >= 0) {
                runCatching { cLib.close(rawFd) }
            }
            Shell.cmd(
                "chmod 0660 $tunDevicePath 2>/dev/null || true",
                "ip link set dev $tunName down 2>/dev/null || true",
                "ip tuntap del dev $tunName mode tun 2>/dev/null || true",
            ).exec()
            val detail = ex.message ?: ex.javaClass.simpleName
            AppLogStore.error("StealthTun", "Fallback open(/dev/tun) не удался: $detail | Основная ошибка: $primaryError")
            return Result.failure(
                IllegalStateException("Не удалось открыть скрытый TUN ($tunName): $primaryError | fallback: $detail", ex),
            )
        }
    }

    private fun readSelfSelinuxType(): String? {
        return runCatching {
            val raw = File("/proc/self/attr/current").readText().trim().trimEnd('\u0000')
            val parts = raw.split(':')
            parts.getOrNull(2)?.takeIf { it.matches(Regex("[a-zA-Z0-9_]+")) }
        }.getOrNull()
    }

    private fun applyLiveSelinuxPolicy(rules: List<String>): Boolean {
        val joinedArgs = rules.joinToString(" ") { "\"$it\"" }
        val script = buildString {
            appendLine("MPATH=\"\"")
            appendLine("if command -v magisk >/dev/null 2>&1; then")
            appendLine("  MPDIR=\$(magisk --path 2>/dev/null)")
            appendLine("  if [ -n \"\$MPDIR\" ] && [ -x \"\$MPDIR/magiskpolicy\" ]; then MPATH=\"\$MPDIR/magiskpolicy\"; fi")
            appendLine("fi")
            appendLine("if [ -z \"\$MPATH\" ]; then")
            appendLine("  for cand in /debug_ramdisk/magiskpolicy /sbin/magiskpolicy /data/adb/magisk/magiskpolicy \$(command -v magiskpolicy 2>/dev/null) \$(command -v supolicy 2>/dev/null); do")
            appendLine("    if [ -n \"\$cand\" ] && [ -x \"\$cand\" ]; then MPATH=\"\$cand\"; break; fi")
            appendLine("  done")
            appendLine("fi")
            appendLine("if [ -n \"\$MPATH\" ]; then")
            appendLine("  echo \"POLICY_BIN=\$MPATH\"")
            appendLine("  \"\$MPATH\" --live $joinedArgs 2>&1")
            appendLine("elif [ -x /data/adb/ksu/bin/ksud ]; then")
            appendLine("  echo \"POLICY_BIN=/data/adb/ksu/bin/ksud\"")
            for (r in rules) {
                appendLine("  /data/adb/ksu/bin/ksud sepolicy patch \"$r\" 2>&1")
            }
            appendLine("else")
            appendLine("  echo \"POLICY_BIN=NONE\"")
            appendLine("fi")
        }
        val res = Shell.cmd(script).exec()
        val lines = (res.out + res.err).map { it.trim() }.filter { it.isNotEmpty() }
        val summary = lines.joinToString(" | ").take(260)
        AppLogStore.info("StealthTun", "SELinux live-политика: $summary")
        val hasPolicyError = lines.any { it.contains("does not exist", ignoreCase = true) || it.contains("POLICY_BIN=NONE") }
        return res.isSuccess && !hasPolicyError
    }
}
