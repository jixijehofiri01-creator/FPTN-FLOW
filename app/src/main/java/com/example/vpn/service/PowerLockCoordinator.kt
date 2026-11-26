package com.example.vpn.service

import android.content.Context
import android.os.PowerManager

/**
 * Abstraction over Android [PowerManager.WakeLock] so ownership rules can be unit-tested
 * deterministically without mocking Android framework internals.
 */
internal interface PowerWakeLockHandle {
    val isHeld: Boolean
    fun acquire(timeoutMs: Long)
    fun release()
}

internal class AndroidWakeLockHandle(
    private val context: Context,
    private val tag: String,
) : PowerWakeLockHandle {
    private var wakeLock: PowerManager.WakeLock? = null

    override val isHeld: Boolean
        get() = wakeLock?.isHeld == true

    override fun acquire(timeoutMs: Long) {
        val pm = context.getSystemService(PowerManager::class.java) ?: return
        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
                setReferenceCounted(false)
            }
        }
        wakeLock?.acquire(timeoutMs)
    }

    override fun release() {
        val wl = wakeLock
        if (wl != null && wl.isHeld) {
            wl.release()
        }
    }
}

/**
 * Generation-scoped owner for VPN connection/reconnect WakeLocks.
 *
 * Prevents an older coroutine (for example, `triggerSoftReconnect` handing off to `beginConnect`
 * during Auto-Failover) from prematurely releasing a WakeLock acquired by a newer session.
 */
internal class PowerLockCoordinator(
    private val handle: PowerWakeLockHandle,
) {
    private val lock = Any()
    private var currentToken: Long = 0L

    fun acquire(timeoutMs: Long = 12_000L): Long = synchronized(lock) {
        val token = ++currentToken
        runCatching {
            handle.acquire(timeoutMs.coerceIn(5_000L, 30_000L))
        }
        token
    }

    fun releaseIfOwner(expectedToken: Long): Boolean = synchronized(lock) {
        if (expectedToken <= 0L || expectedToken != currentToken) {
            return@synchronized false
        }
        runCatching {
            if (handle.isHeld) {
                handle.release()
            }
        }
        true
    }

    fun forceRelease() {
        synchronized(lock) {
            currentToken++
            runCatching {
                if (handle.isHeld) {
                    handle.release()
                }
            }
        }
    }
}
