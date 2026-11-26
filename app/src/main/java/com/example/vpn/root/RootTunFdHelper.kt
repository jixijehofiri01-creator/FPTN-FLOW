package com.example.vpn.root

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.ParcelFileDescriptor
import com.sun.jna.LastErrorException
import com.sun.jna.Library
import com.sun.jna.Native
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root-privileged helper launched via `/system/bin/app_process` under `su` (UID 0 + CAP_NET_ADMIN).
 *
 * Why this solves Android 14/15/16 `Permission denied`:
 * - Unprivileged app processes (`untrusted_app`) lack `CAP_NET_ADMIN` and are blocked by Android 16
 *   SELinux/MCS from calling `open("/dev/tun")` + `ioctl(TUNSETIFF)`.
 * - Running inside root `app_process` lets the Linux kernel create & attach `fptn0` directly via
 *   `open("/dev/tun") + ioctl(TUNSETIFF)` (no `ip tuntap` binary required!), brings the link UP,
 *   and transfers the live open kernel TUN FileDescriptor back to our app over an abstract UNIX
 *   domain socket (`SCM_RIGHTS` via Android's `LocalSocket.setFileDescriptorsForSend`).
 */
object RootTunFdHelper {

    private const val O_RDWR = 2
    private const val IFF_TUN: Short = 0x0001
    private const val IFF_NO_PI: Short = 0x1000
    private const val TUNSETIFF: Long = 0x400454caL

    interface LibC : Library {
        @Throws(LastErrorException::class)
        fun open(path: String, flags: Int): Int

        @Throws(LastErrorException::class)
        fun ioctl(fd: Int, request: Long, arg: ByteArray): Int

        fun close(fd: Int): Int
    }

    private val VALID_TUN_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_-]{0,14}$")

    internal fun validateTunName(raw: String): String? =
        raw.trim().takeIf { VALID_TUN_NAME_REGEX.matches(it) }

    internal fun validateIpv4(raw: String): String? {
        val parts = raw.trim().split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val part = parts[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[i] = value
        }
        return "${octets[0]}.${octets[1]}.${octets[2]}.${octets[3]}"
    }

    internal fun clampMtu(raw: String?): Int = (raw?.toIntOrNull() ?: 1500).coerceIn(576, 9000)

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size < 4) {
            System.err.println("USAGE: RootTunFdHelper <socketName> <tunName> <clientIpv4> <mtu>")
            kotlin.system.exitProcess(2)
        }
        val socketName = args[0]
        val tunName = validateTunName(args[1]) ?: run {
            System.err.println("ERR: invalid tunName '${args[1]}'")
            kotlin.system.exitProcess(2)
        }
        val clientIpv4 = validateIpv4(args[2]) ?: run {
            System.err.println("ERR: invalid clientIpv4 '${args[2]}'")
            kotlin.system.exitProcess(2)
        }
        val mtu = clampMtu(args[3])

        val tunDevicePath = when {
            File("/dev/tun").exists() -> "/dev/tun"
            File("/dev/net/tun").exists() -> "/dev/net/tun"
            else -> {
                System.err.println("ERR: neither /dev/tun nor /dev/net/tun exists")
                kotlin.system.exitProcess(3)
            }
        }

        val cLib = try {
            Native.load("c", LibC::class.java)
        } catch (ex: Throwable) {
            System.err.println("ERR: JNA Native.load(c) failed: ${ex.javaClass.simpleName}: ${ex.message}")
            kotlin.system.exitProcess(4)
        }

        var rawFd = -1
        var pfd: ParcelFileDescriptor? = null
        try {
            // Best-effort cleanup of any stale interface with the same name
            runShellCommand(
                "ip link set dev $tunName down 2>/dev/null || true; " +
                    "ip link del dev $tunName 2>/dev/null || true; " +
                    "ip tuntap del dev $tunName mode tun 2>/dev/null || true",
            )

            rawFd = cLib.open(tunDevicePath, O_RDWR)
            if (rawFd < 0) {
                throw IllegalStateException("open($tunDevicePath) returned $rawFd")
            }

            // struct ifreq (40 bytes): ifr_name[16] + ifr_flags (uint16 native endian at offset 16)
            val ifr = ByteArray(40)
            val nameBytes = tunName.toByteArray(Charsets.US_ASCII)
            System.arraycopy(nameBytes, 0, ifr, 0, nameBytes.size.coerceAtMost(15))
            val flags = (IFF_TUN.toInt() or IFF_NO_PI.toInt()).toShort()
            ByteBuffer.wrap(ifr, 16, 2).order(ByteOrder.nativeOrder()).putShort(flags)

            var rc: Int
            try {
                rc = cLib.ioctl(rawFd, TUNSETIFF, ifr)
            } catch (firstEx: LastErrorException) {
                if (firstEx.errorCode == 16) { // EBUSY: previous FD still closing in kernel
                    runShellCommand(
                        "ip link set dev $tunName down 2>/dev/null || true; " +
                            "ip link del dev $tunName 2>/dev/null || true; " +
                            "ip tuntap del dev $tunName mode tun 2>/dev/null || true",
                    )
                    Thread.sleep(220L)
                    rc = cLib.ioctl(rawFd, TUNSETIFF, ifr)
                } else {
                    throw firstEx
                }
            }
            if (rc < 0) {
                throw IllegalStateException("ioctl(TUNSETIFF, $tunName) returned $rc")
            }

            // Bring up interface and assign IPv4 + MTU as root
            val ipErr = runShellCommand(
                "ip addr replace $clientIpv4/32 dev $tunName && ip link set dev $tunName mtu $mtu up",
            )
            if (ipErr != null) {
                throw IllegalStateException("ip addr/link up failed: $ipErr")
            }

            pfd = ParcelFileDescriptor.adoptFd(rawFd)
            rawFd = -1

            // Transfer the live kernel TUN fd to the app over abstract LocalSocket (SCM_RIGHTS)
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 4000
                socket.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                socket.outputStream.write(byteArrayOf(0x4F)) // 'O' = OK
                socket.outputStream.flush()
                // Wait for app to dup() the FD and send 1-byte ACK before we close our handle
                runCatching { socket.inputStream.read() }
            }

            println("OK:$tunName:$clientIpv4:$mtu")
            runCatching { pfd.close() }
            kotlin.system.exitProcess(0)
        } catch (ex: Throwable) {
            if (rawFd >= 0) {
                runCatching { cLib.close(rawFd) }
            }
            runCatching { pfd?.close() }
            System.err.println("ERR: ${ex.javaClass.simpleName}: ${ex.message}")
            kotlin.system.exitProcess(5)
        }
    }

    private fun runShellCommand(cmd: String): String? {
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val code = proc.waitFor()
            if (code == 0) null else "exit=$code $out"
        } catch (ex: Throwable) {
            ex.message ?: ex.javaClass.simpleName
        }
    }
}
