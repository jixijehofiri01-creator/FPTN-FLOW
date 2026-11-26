package com.example.core.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.io.File

/**
 * Coarse hardware/runtime tier driving graceful visual degradation across budget 2–3 GB devices
 * and flagship 120 Hz phones.
 */
enum class DevicePerformanceTier {
    LOW_END,
    BALANCED,
    FLAGSHIP,
}

/**
 * Dynamic Root resource boost level calculated from live CPU, RAM, and thermal headroom so VPN
 * throughput is maximized when idle while immediately yielding to foreground games and heavy apps.
 */
enum class AdaptiveBoostTier(
    val shortTitle: String,
    val threadNicePriority: Int,
    val tunTxQueueLen: Int,
) {
    INACTIVE(
        shortTitle = "Выключено",
        threadNicePriority = android.os.Process.THREAD_PRIORITY_DEFAULT,
        tunTxQueueLen = 500,
    ),
    YIELD_TO_GAMES(
        shortTitle = "Уступает играм/нагрузке",
        threadNicePriority = android.os.Process.THREAD_PRIORITY_DEFAULT,
        tunTxQueueLen = 500,
    ),
    BALANCED(
        shortTitle = "Сбалансировано",
        threadNicePriority = android.os.Process.THREAD_PRIORITY_MORE_FAVORABLE,
        tunTxQueueLen = 750,
    ),
    MAX_TURBO(
        shortTitle = "Макс. приоритет VPN",
        threadNicePriority = android.os.Process.THREAD_PRIORITY_DISPLAY,
        tunTxQueueLen = 1000,
    ),
}

/**
 * Pure immutable snapshot of system headroom used both at runtime and in JVM unit tests.
 */
data class SystemHeadroomSnapshot(
    val isLowRamDevice: Boolean,
    val isPowerSaveMode: Boolean,
    val isLowMemoryNow: Boolean,
    val availableRamRatio: Float,
    val thermalStatus: Int,
    val normalizedLoadPerCore: Float,
    val animatorDurationScale: Float = 1f,
    val totalRamMb: Long = 4096L,
    val cpuCores: Int = 4,
    val sdkInt: Int = Build.VERSION.SDK_INT,
)

/**
 * Evaluates device capabilities and live thermal/CPU/RAM headroom to keep UI silky-smooth on
 * low-end Android phones while unleashing full AGSL shaders and dynamic Root turbo on flagships.
 */
object DevicePerformanceProfile {

    private const val THERMAL_STATUS_NONE = 0
    private const val THERMAL_STATUS_LIGHT = 1
    private const val THERMAL_STATUS_MODERATE = 2

    /**
     * Pure deterministic tier classifier (unit-testable without Android Framework).
     */
    fun evaluatePerformanceTier(snapshot: SystemHeadroomSnapshot): DevicePerformanceTier {
        if (snapshot.isLowRamDevice ||
            snapshot.isPowerSaveMode ||
            snapshot.animatorDurationScale <= 0.01f ||
            snapshot.totalRamMb <= 3072L ||
            snapshot.cpuCores <= 4 ||
            snapshot.thermalStatus >= THERMAL_STATUS_MODERATE
        ) {
            return DevicePerformanceTier.LOW_END
        }
        if (snapshot.sdkInt >= Build.VERSION_CODES.TIRAMISU &&
            snapshot.totalRamMb >= 6144L &&
            snapshot.cpuCores >= 8 &&
            snapshot.thermalStatus <= THERMAL_STATUS_LIGHT
        ) {
            return DevicePerformanceTier.FLAGSHIP
        }
        return DevicePerformanceTier.BALANCED
    }

    /**
     * Pure deterministic Root resource boost selector (unit-testable):
     * - Immediately yields (`YIELD_TO_GAMES`) when a heavy game/app loads CPU, RAM gets tight,
     *   battery saver is on, or device temperature reaches MODERATE+.
     * - Applies `BALANCED` under light load or warm conditions.
     * - Applies `MAX_TURBO` when CPU, RAM, and thermals have ample headroom.
     */
    fun evaluateAdaptiveBoostTier(snapshot: SystemHeadroomSnapshot): AdaptiveBoostTier {
        if (snapshot.isPowerSaveMode ||
            snapshot.isLowMemoryNow ||
            snapshot.availableRamRatio < 0.18f ||
            snapshot.thermalStatus >= THERMAL_STATUS_MODERATE ||
            snapshot.normalizedLoadPerCore >= 0.72f
        ) {
            return AdaptiveBoostTier.YIELD_TO_GAMES
        }
        if (snapshot.isLowRamDevice ||
            snapshot.availableRamRatio < 0.30f ||
            snapshot.thermalStatus >= THERMAL_STATUS_LIGHT ||
            snapshot.normalizedLoadPerCore >= 0.42f
        ) {
            return AdaptiveBoostTier.BALANCED
        }
        return AdaptiveBoostTier.MAX_TURBO
    }

    /**
     * Samples current system headroom safely from Android services and `/proc/loadavg`.
     */
    fun sampleSystemHeadroom(context: Context): SystemHeadroomSnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        val memInfo = ActivityManager.MemoryInfo()
        val hasMemInfo = runCatching {
            am?.getMemoryInfo(memInfo)
            memInfo.totalMem > 0L
        }.getOrDefault(false)

        val totalRamMb = if (hasMemInfo) (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(512L) else 4096L
        val availRatio = if (hasMemInfo && memInfo.totalMem > 0L) {
            (memInfo.availMem.toFloat() / memInfo.totalMem.toFloat()).coerceIn(0f, 1f)
        } else {
            0.50f
        }
        val isLowRam = runCatching { am?.isLowRamDevice == true }.getOrDefault(false)
        val isLowMemNow = hasMemInfo && memInfo.lowMemory
        val isPowerSave = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm?.currentThermalStatus ?: THERMAL_STATUS_NONE }.getOrDefault(THERMAL_STATUS_NONE)
        } else {
            THERMAL_STATUS_NONE
        }
        val animScale = runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)

        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val loadPerCore = readNormalizedOneMinuteLoad(cores)

        return SystemHeadroomSnapshot(
            isLowRamDevice = isLowRam,
            isPowerSaveMode = isPowerSave,
            isLowMemoryNow = isLowMemNow,
            availableRamRatio = availRatio,
            thermalStatus = thermalStatus,
            normalizedLoadPerCore = loadPerCore,
            animatorDurationScale = animScale,
            totalRamMb = totalRamMb,
            cpuCores = cores,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    /**
     * Resolves the current UI/GPU performance tier for graceful visual degradation.
     */
    fun resolveTier(context: Context): DevicePerformanceTier =
        evaluatePerformanceTier(sampleSystemHeadroom(context))

    /**
     * Returns `true` only when hardware AGSL `RuntimeShader` effects are supported AND appropriate
     * for the device's current RAM, battery saver, thermal, and reduced-motion state.
     */
    fun supportsGpuShaders(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val snapshot = sampleSystemHeadroom(context)
        if (snapshot.isLowRamDevice || snapshot.isPowerSaveMode || snapshot.animatorDurationScale <= 0.01f) {
            return false
        }
        return evaluatePerformanceTier(snapshot) != DevicePerformanceTier.LOW_END
    }

    private fun readNormalizedOneMinuteLoad(cores: Int): Float = runCatching {
        val text = File("/proc/loadavg").takeIf { it.canRead() }?.readText()?.trim().orEmpty()
        val firstToken = text.substringBefore(' ').toFloatOrNull() ?: return@runCatching 0.20f
        (firstToken / cores.toFloat()).coerceIn(0f, 4f)
    }.getOrDefault(0.20f)
}
