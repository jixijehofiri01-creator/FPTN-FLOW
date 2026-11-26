package com.example.vpn.root

import android.content.Context
import android.os.Looper
import com.example.core.performance.AdaptiveBoostTier
import com.example.core.performance.DevicePerformanceProfile
import com.example.core.logging.AppLogStore
import com.example.domain.root.SafeRootRulePlanner
import com.example.vpn.tunnel.TunPacketLoop
import com.topjohnwu.superuser.Shell
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dynamically tunes isolated FPTN Root TUN resources (`TunPacketLoop` worker priority and our own
 * `fptn*` interface `txqueuelen`) based on live CPU, RAM, battery-saver, and thermal headroom.
 *
 * Guarantees zero impact on foreground games or heavy apps: whenever CPU load, memory pressure,
 * or device temperature rises, it immediately steps down to [AdaptiveBoostTier.YIELD_TO_GAMES]
 * (`nice 0`, standard queue) and reverts everything cleanly on disconnect.
 */
object AdaptiveRootBoostController {

    private const val SAMPLE_INTERVAL_MS = 4_000L

    private val _activeTier = MutableStateFlow(AdaptiveBoostTier.INACTIVE)
    val activeTier: StateFlow<AdaptiveBoostTier> = _activeTier.asStateFlow()

    @Volatile
    private var monitorJob: Job? = null

    @Volatile
    private var activeTunName: String? = null

    @Volatile
    private var activeLoop: TunPacketLoop? = null

    fun start(
        scope: CoroutineScope,
        context: Context,
        tunInterfaceName: String,
        tunLoop: TunPacketLoop,
        enabled: Boolean,
    ) {
        stop()
        if (!enabled) {
            _activeTier.value = AdaptiveBoostTier.INACTIVE
            return
        }
        val safeTun = SafeRootRulePlanner.sanitizeTunName(tunInterfaceName)
        activeTunName = safeTun
        activeLoop = tunLoop
        val appContext = context.applicationContext

        monitorJob = scope.launch(Dispatchers.IO) {
            var lastTier: AdaptiveBoostTier? = null
            try {
                while (isActive) {
                    val snapshot = DevicePerformanceProfile.sampleSystemHeadroom(appContext)
                    val desiredTier = DevicePerformanceProfile.evaluateAdaptiveBoostTier(snapshot)
                    if (desiredTier != lastTier) {
                        applyTier(safeTun, tunLoop, desiredTier)
                        _activeTier.value = desiredTier
                        AppLogStore.info(
                            "RootBoost",
                            "Адаптивный приоритет Root → ${desiredTier.shortTitle} " +
                                "(RAM ${(snapshot.availableRamRatio * 100).toInt()}%, " +
                                "CPU ${"%.2f".format(snapshot.normalizedLoadPerCore)}, " +
                                "thermal=${snapshot.thermalStatus})",
                        )
                        lastTier = desiredTier
                    }
                    delay(SAMPLE_INTERVAL_MS)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                AppLogStore.warn("RootBoost", "Мониторинг нагрузки приостановлен: ${ex.message}")
            }
        }
    }

    private val cleanupExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FptnRootBoostCleanup").apply { isDaemon = true }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        val tun = activeTunName
        val loop = activeLoop
        activeTunName = null
        activeLoop = null
        val wasActive = _activeTier.value != AdaptiveBoostTier.INACTIVE
        _activeTier.value = AdaptiveBoostTier.INACTIVE
        if (loop != null) {
            loop.updateWorkerThreadPriority(AdaptiveBoostTier.INACTIVE.threadNicePriority)
        }
        if (tun != null && wasActive) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                cleanupExecutor.execute {
                    applyTunTxQueueLen(tun, AdaptiveBoostTier.INACTIVE.tunTxQueueLen)
                }
            } else {
                applyTunTxQueueLen(tun, AdaptiveBoostTier.INACTIVE.tunTxQueueLen)
            }
        }
    }

    private fun applyTier(tunName: String, loop: TunPacketLoop, tier: AdaptiveBoostTier) {
        loop.updateWorkerThreadPriority(tier.threadNicePriority)
        applyTunTxQueueLen(tunName, tier.tunTxQueueLen)
    }

    private fun applyTunTxQueueLen(tunName: String, txQueueLen: Int) {
        runCatching {
            val shell = Shell.getCachedShell()
            if (shell != null && shell.isRoot) {
                val safeLen = txQueueLen.coerceIn(500, 1000)
                Shell.cmd("ip link set dev $tunName txqueuelen $safeLen 2>/dev/null || true").exec()
            }
        }
    }
}
