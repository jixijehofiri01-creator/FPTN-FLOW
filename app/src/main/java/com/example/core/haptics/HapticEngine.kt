package com.example.core.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Centralized Google Android Haptics engine implementing official Android Haptics guidelines:
 * - Hardware primitive capability checking via arePrimitivesSupported(...)
 * - VibrationAttributes.USAGE_TOUCH for system preference compliance
 * - Smooth, non-harsh wave-synced swell when VPN connects (matching single AGSL water wave)
 * - Official Google Haptics Error / Reject double-pulse pattern when connection fails or aborts
 * - Clean zero-amplitude waveform tail on fallback devices to avoid motor buzzing/ringing
 */
class HapticEngine(
    context: Context,
    @Volatile var isEnabled: Boolean = true,
) {
    private val vibrator: Vibrator? = resolveVibrator(context)
    private var lastPullTickStep: Int = -1
    private var lastRaindropHapticAtMs: Long = 0L
    private var lastDisconnectingHapticAtMs: Long = 0L

    private fun resolveVibrator(context: Context): Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    fun resetPullTension() {
        lastPullTickStep = -1
    }

    /**
     * Directional progressive tactile ticks while pulling the connect slider (`fraction` in 0f..1f):
     * - Connecting pull (`isDisconnectingPull = false`): crescendos smoothly from light (0.12) -> strong (0.96).
     * - Disconnecting pull (`isDisconnectingPull = true`): decrescendos smoothly from strong (0.94) -> light (0.12).
     */
    fun onPullProgress(fraction: Float, isDisconnectingPull: Boolean = false) {
        if (!isEnabled) return
        val clamped = fraction.coerceIn(0f, 1f)
        val step = (clamped * 15f).toInt()
        if (step <= 0 || step == lastPullTickStep) return
        lastPullTickStep = step

        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val scale = computePullIntensity(clamped, isDisconnectingPull)
        val useCrispTick = scale >= 0.58f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val preferredPrimitive = if (useCrispTick) {
                VibrationEffect.Composition.PRIMITIVE_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            }
            if (isPrimitiveSupported(vib, preferredPrimitive)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(preferredPrimitive, scale)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        val durationMs = (8L + (scale * 10f).toLong()).coerceIn(8L, 18L)
        vibrateDampedWaveform(vib, durationMs = durationMs, amplitude = (scale * 235).toInt())
    }

    /**
     * Firm latch when thumb locks on the right while waiting for VPN handshake.
     */
    fun onPullHoldLatch() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.72f)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.72f)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateDampedWaveform(vib, durationMs = 16L, amplitude = 165)
    }

    companion object {
        /**
         * Pure curve helper (unit-testable):
         * - Connect pull (`isDisconnectingPull = false`): ramps UP from 0.12 -> 0.96.
         * - Disconnect pull (`isDisconnectingPull = true`): ramps DOWN from 0.94 -> 0.12.
         */
        fun computePullIntensity(fraction: Float, isDisconnectingPull: Boolean): Float {
            val t = fraction.coerceIn(0f, 1f).toDouble()
            return if (!isDisconnectingPull) {
                (0.12 + 0.84 * Math.pow(t, 1.35)).toFloat().coerceIn(0.12f, 0.96f)
            } else {
                (0.94 - 0.82 * Math.pow(t, 1.15)).toFloat().coerceIn(0.12f, 0.94f)
            }
        }
    }

    /**
     * Expressive ascending crescendo motif fired ONLY when VPN reaches CONNECTED state:
     * gentle low spark -> rising swell -> confident surge -> crisp lock-in click.
     */
    fun onConnectWaveLaunch() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_SLOW_RISE,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.28f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.58f, 45)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.82f, 75)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.94f, 105)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_SLOW_RISE)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE, 0.72f)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        // Smooth ascending 4-beat crescendo waveform ending cleanly at 0
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 22L, 42L, 30L, 50L, 46L, 38L, 28L, 22L),
            amplitudes = intArrayOf(0, 58, 0, 118, 0, 178, 0, 232, 0),
        )
    }

    /**
     * Official Google Android Haptics Error / Reject feedback when connection fails or aborts:
     * Combines HapticFeedbackConstants.REJECT with a distinct crisp double-fall reject motif.
     */
    fun onConnectionError(view: View? = null) {
        if (!isEnabled) return
        if (view != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.80f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.88f, 65)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f, 55)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        // Standard double-pulse error/reject waveform ending cleanly at 0
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 24L, 65L, 32L, 20L),
            amplitudes = intArrayOf(0, 185, 0, 170, 0),
        )
    }

    /**
     * Non-error uneven decaying "battery winding down" motif («ТУ — Ту — ту»)
     * fired ONLY upon completing VPN disconnect: strong warm thud -> longer pause -> softer fall -> longest pause -> faint tap.
     */
    fun onDisconnectWaveLaunch() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.78f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.48f, 95)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.20f, 135)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        // Uneven decaying 3-pulse power-down waveform (non-error, ends cleanly at 0)
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 38L, 95L, 26L, 135L, 16L, 20L),
            amplitudes = intArrayOf(0, 195, 0, 118, 0, 52, 0),
        )
    }

    fun onPingSonarPulse() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.36f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.24f, 65)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 12L, 65L, 10L, 16L),
            amplitudes = intArrayOf(0, 75, 0, 45, 0),
        )
    }

    /**
     * Unique status-entry motif for CONNECTING: gentle soft spark rising smoothly.
     */
    fun onConnectingStart() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.26f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.46f, 40)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 14L, 40L, 22L, 16L),
            amplitudes = intArrayOf(0, 62, 0, 118, 0),
        )
    }

    /**
     * Unique status-entry motif for SWITCHING SERVER: crisp bidirectional swap cadence (→ then ←).
     */
    fun onSwitchingServerStart() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.56f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.52f, 52)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.62f, 48)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 16L, 52L, 20L, 48L, 16L, 16L),
            amplitudes = intArrayOf(0, 128, 0, 148, 0, 120, 0),
        )
    }

    /**
     * Unique status-entry motif for RECONNECTING: soft amber heartbeat pulse.
     */
    fun onReconnectingPulse() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.42f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.34f, 88)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 16L, 88L, 14L, 16L),
            amplitudes = intArrayOf(0, 96, 0, 74, 0),
        )
    }

    /**
     * Crisp 2-beat tactile release confirmation when entering DISCONNECTING state
     * so tapping Disconnect gives unmistakable immediate feedback (<16ms).
     */
    fun onDisconnectingStart() {
        if (!isEnabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastDisconnectingHapticAtMs < 300L) return
        lastDisconnectingHapticAtMs = now
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.65f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.44f, 42)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_LOW_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.60f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.38f, 44)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 18L, 42L, 14L, 14L),
            amplitudes = intArrayOf(0, 152, 0, 96, 0),
        )
    }

    /**
     * Impact-locked micro-haptic raindrop tap triggered on the exact frame a foreground drop hits ground.
     * Adapts both amplitude and minimum interval to drizzle vs rain vs heavy downpour/storm.
     */
    fun onRaindropImpact(
        intensity: Float = 0.75f,
        isDrizzle: Boolean = false,
        isHeavy: Boolean = false,
    ) {
        if (!isEnabled) return
        val now = android.os.SystemClock.elapsedRealtime()
        val minIntervalMs = when {
            isDrizzle -> 340L
            isHeavy -> 115L
            else -> 185L
        }
        if (now - lastRaindropHapticAtMs < minIntervalMs) return
        lastRaindropHapticAtMs = now

        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        val clamped = intensity.coerceIn(0.15f, 1.5f)
        val scale = when {
            isDrizzle -> (0.045f + clamped * 0.035f).coerceIn(0.04f, 0.095f)
            isHeavy -> (0.11f + clamped * 0.11f).coerceIn(0.10f, 0.28f)
            else -> (0.075f + clamped * 0.08f).coerceIn(0.065f, 0.20f)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val prim = if (isHeavy && scale >= 0.20f && isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_TICK)) {
                VibrationEffect.Composition.PRIMITIVE_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            }
            if (isPrimitiveSupported(vib, prim)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(prim, scale)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vib.hasAmplitudeControl()) {
            val dur = if (isHeavy) 8L else 5L
            vibrateDampedWaveform(vib, durationMs = dur, amplitude = (scale * 220f).toInt().coerceIn(10, 56))
        }
    }

    /**
     * Soft distant thunder roll for Thunderstorm weather atmosphere.
     */
    fun onDistantThunder() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.24f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.16f, 110)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.10f, 95)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 24L, 100L, 18L, 20L),
            amplitudes = intArrayOf(0, 52, 0, 32, 0),
        )
    }

    fun performSegmentTick(view: View) {
        if (!isEnabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
        } else {
            HapticFeedbackConstants.CLOCK_TICK
        }
        view.performHapticFeedback(constant)
    }

    fun performConfirm(view: View) {
        if (!isEnabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
        view.performHapticFeedback(constant)
    }

    fun performReject(view: View) {
        if (!isEnabled) return
        val constant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(constant)
    }

    /**
     * Expressive Material/Google Haptics for Settings Switch ON vs OFF:
     * - ON: crisp ascending click (LOW_TICK -> CLICK).
     * - OFF: soft descending release (TICK -> gentle LOW_TICK).
     */
    fun performToggle(view: View, enabled: Boolean) {
        if (!isEnabled) return
        val vib = vibrator
        if (vib != null && vib.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = if (enabled) {
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.34f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.62f, 28)
                        .compose()
                } else {
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.42f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.22f, 24)
                        .compose()
                }
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                if (enabled) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF,
            )
        } else {
            performSegmentTick(view)
        }
    }

    /**
     * Proportional tactile tick along a Slider (`fraction` in 0f..1f).
     */
    fun performSliderStep(view: View, fraction: Float = 0.5f) {
        if (!isEnabled) return
        val scale = (0.20f + fraction.coerceIn(0f, 1f) * 0.55f)
        val vib = vibrator
        if (vib != null && vib.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val prim = if (scale > 0.52f) {
                VibrationEffect.Composition.PRIMITIVE_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            }
            if (isPrimitiveSupported(vib, prim)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(prim, scale)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        performSegmentTick(view)
    }

    /**
     * Light, clean click for Settings navigation rows.
     */
    fun performNavigationClick(view: View) {
        if (!isEnabled) return
        val vib = vibrator
        if (vib != null && vib.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.38f)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        performSegmentTick(view)
    }

    /**
     * Distinct firm thud+fall motif for destructive actions (delete, clear logs, reset).
     */
    fun performDestructiveAction(view: View) {
        if (!isEnabled) return
        val vib = vibrator
        if (vib != null && vib.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.65f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.55f, 40)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        performReject(view)
    }

    /**
     * Crisp short double-tap reject when tapping a setting or tile locked during active VPN.
     */
    fun performLockedReject(view: View) {
        if (!isEnabled) return
        val vib = vibrator
        if (vib != null && vib.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_THUD)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.55f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.42f, 55)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        performReject(view)
    }

    /**
     * Subtle ascending chime for positive confirmations (fastest DNS found, backup saved, SNI matched).
     */
    fun performSuccess(view: View) {
        if (!isEnabled) return
        val vib = vibrator
        if (vib != null && vib.hasVibrator() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.50f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.58f, 45)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        performConfirm(view)
    }

    /**
     * Subtle soft drop-back detent when the hero flag zooms out into the infinite 2D World Flag Matrix.
     */
    fun onFlagMatrixZoomOut() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_QUICK_FALL,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.42f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.30f, 36)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateDampedWaveform(vib, durationMs = 14L, amplitude = 70)
    }

    /**
     * Crisp magnetic center lock-in detent when the destination sovereign country glides into focus.
     */
    fun onFlagMatrixLockIn() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.56f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.72f, 28)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 14L, 24L, 16L),
            amplitudes = intArrayOf(0, 125, 0, 185),
        )
    }

    /**
     * Light, precise mechanical "click into slot" ("встал в паз") detent fired when a flag
     * finishes shrinking into its matrix cell on switch/disconnect or locks into center before unfurling.
     */
    fun onFlagDockSnap() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_CLICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.44f)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
            if (isPrimitiveSupported(vib, VibrationEffect.Composition.PRIMITIVE_TICK)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.42f)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateDampedWaveform(vib, durationMs = 10L, amplitude = 92)
    }

    /**
     * Escalating heartbeat pulse (step = 0, 1, 2) while the frosted "?" glass tile builds tension
     * and hairline cracks spread across its surface before shattering.
     */
    fun onMysteryPulse(step: Int) {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val s = step.coerceIn(0, 2)
        val scale = when (s) {
            0 -> 0.40f
            1 -> 0.65f
            else -> 0.92f
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val comp = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, scale)
                if (s >= 1) {
                    comp.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, (scale * 0.78f).coerceAtMost(1f), 42)
                }
                vibrateWithTouchAttributes(vib, comp.compose())
                return
            }
        }
        val amp = (95 + s * 65).coerceIn(80, 235)
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 18L + s * 6L, 28L, 10L),
            amplitudes = intArrayOf(0, amp, 0, (amp * 0.65f).toInt()),
        )
    }

    /**
     * Sharp crystalline shatter burst synchronized with the frosted "?" glass breaking into shards:
     * strong central crack (CLICK + THUD) followed by cascading micro-ticks as glass shards scatter.
     */
    fun onMysteryGlassShatter() {
        if (!isEnabled) return
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val required = intArrayOf(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            )
            if (areAllPrimitivesSupported(vib, required)) {
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.92f, 12)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.72f, 24)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.50f, 34)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.34f, 44)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.22f, 52)
                    .compose()
                vibrateWithTouchAttributes(vib, effect)
                return
            }
        }
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, 28L, 14L, 16L, 20L, 12L, 26L, 8L),
            amplitudes = intArrayOf(0, 255, 0, 195, 0, 130, 0, 75),
        )
    }

    private fun isPrimitiveSupported(vib: Vibrator, primitiveId: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { vib.arePrimitivesSupported(primitiveId).firstOrNull() == true }
                .getOrDefault(false)
        } else {
            false
        }
    }

    @android.annotation.SuppressLint("WrongConstant")
    private fun areAllPrimitivesSupported(vib: Vibrator, primitives: IntArray): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { vib.arePrimitivesSupported(*primitives).all { it } }
                .getOrDefault(false)
        } else {
            false
        }
    }

    private fun vibrateWithTouchAttributes(vib: Vibrator, effect: VibrationEffect) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val attrs = VibrationAttributes.Builder()
                    .setUsage(VibrationAttributes.USAGE_TOUCH)
                    .build()
                vib.vibrate(effect, attrs)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vib.vibrate(effect)
            }
        }
    }

    private fun vibrateDampedWaveform(vib: Vibrator, durationMs: Long, amplitude: Int) {
        vibrateMultiStepWaveform(
            vib = vib,
            timings = longArrayOf(0L, durationMs, 14L),
            amplitudes = intArrayOf(0, amplitude.coerceIn(1, 255), 0),
        )
    }

    private fun vibrateMultiStepWaveform(vib: Vibrator, timings: LongArray, amplitudes: IntArray) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vib.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    VibrationEffect.createOneShot(timings.sum().coerceAtMost(40L), VibrationEffect.DEFAULT_AMPLITUDE)
                }
                vibrateWithTouchAttributes(vib, effect)
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(18L)
            }
        }
    }
}
