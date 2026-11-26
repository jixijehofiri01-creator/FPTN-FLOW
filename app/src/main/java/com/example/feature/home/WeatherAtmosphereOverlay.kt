package com.example.feature.home

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.example.core.performance.DevicePerformanceProfile
import com.example.core.performance.DevicePerformanceTier
import com.example.domain.model.WeatherEffectMode
import kotlin.math.sin
import kotlin.random.Random

private class WeatherParticle(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var length: Float = 16f,
    var strokeWidth: Float = 1.2f,
    var alpha: Float = 0.2f,
    var phase: Float = 0f,
    var groundY: Float = 0.94f,
    var respawnDelay: Float = 0f,
    var isHero: Boolean = false,
    var bounceCount: Int = 0,
    var depthLayer: Float = 0.5f,
)

private class SplashRipple(
    var x: Float = 0f,
    var y: Float = 0f,
    var progress: Float = 1.5f,
    var maxRadiusX: Float = 16f,
    var maxRadiusY: Float = 5f,
    var strength: Float = 0.6f,
    var dropletHeight: Float = 10f,
    var dropletSpread: Float = 8f,
)

private class LightningBoltState {
    var activeFlash: Float = 0f
    var originX: Float = 0.5f
    var seed: Int = 1
    var nextStrikeAtSec: Float = 3.2f
}

/**
 * Isolated 60/120fps zero-allocation Weather Atmosphere Overlay:
 * - True 3D volumetric raymarched AGSL fog & clouds on Android 13+ (with smooth 4-plane procedural
 *   fBm perspective fallback on API < 33 and low-end devices — zero flat circles!).
 * - Full flexible weather roster: CLEAR, PARTLY_CLOUDY, CLOUDY, FOG (3D volumetric), DRIZZLE,
 *   RAIN, HEAVY_RAIN, STORM (3D thunderheads + jagged forked lightning bolts), SNOW, BLIZZARD, HAIL.
 * - Live configurable [intensity], [windStrength], and [fogDepth] controls.
 * - Exact frame-locked impact sync: ground splashes + micro-haptics fire on the exact impact frame.
 */
@Composable
fun WeatherAtmosphereOverlay(
    mode: WeatherEffectMode,
    modifier: Modifier = Modifier,
    intensity: Float = 0.75f,
    windStrength: Float = 0.55f,
    fogDepth: Float = 0.80f,
    enableHaptics: Boolean = false,
    onRaindropImpact: ((strength: Float, isDrizzle: Boolean, isHeavy: Boolean) -> Unit)? = null,
    onThunderPulse: (() -> Unit)? = null,
) {
    if (mode == WeatherEffectMode.OFF || mode == WeatherEffectMode.AUTO_LOCATION) return

    val context = LocalContext.current
    val perfTier = remember(context) { DevicePerformanceProfile.resolveTier(context) }
    val useAgsl3dVolumetrics = remember(context) { DevicePerformanceProfile.supportsGpuShaders(context) }
    val isLowEndDevice = perfTier == DevicePerformanceTier.LOW_END

    val clampedIntensity = intensity.coerceIn(0.25f, 1.5f)
    val clampedWind = windStrength.coerceIn(0.0f, 1.5f)
    val clampedFogDepth = fogDepth.coerceIn(0.25f, 1.5f)
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.45f

    val hasVolumetric = WeatherVolumetricRenderer.hasVolumetricLayer(mode)

    val volumetricShader = remember(useAgsl3dVolumetrics, hasVolumetric) {
        if (useAgsl3dVolumetrics && hasVolumetric && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            WeatherVolumetricRenderer.createRuntimeShaderOrNull()
        } else {
            null
        }
    }

    val fallbackVolumetricTile = remember(volumetricShader, hasVolumetric) {
        if (hasVolumetric && volumetricShader == null) {
            WeatherVolumetricRenderer.createFallbackVolumetricTile()
        } else {
            null
        }
    }

    val particleCount = remember(mode, clampedIntensity, isLowEndDevice) {
        val base = when (mode) {
            WeatherEffectMode.CLEAR -> 16
            WeatherEffectMode.PARTLY_CLOUDY -> 10
            WeatherEffectMode.CLOUDY -> 0
            WeatherEffectMode.FOG -> 14 // subtle floating illuminated depth dust/mist motes
            WeatherEffectMode.DRIZZLE -> 26
            WeatherEffectMode.RAIN -> 36
            WeatherEffectMode.HEAVY_RAIN -> 50
            WeatherEffectMode.STORM -> 54
            WeatherEffectMode.SNOW -> 38
            WeatherEffectMode.BLIZZARD -> 60
            WeatherEffectMode.HAIL -> 34
            else -> 0
        }
        if (base == 0) {
            0
        } else {
            val deviceScale = if (isLowEndDevice) 0.62f else 1.0f
            (base * clampedIntensity * deviceScale).toInt().coerceIn(5, if (isLowEndDevice) 40 else 72)
        }
    }

    val simRng = remember(mode) { Random(System.nanoTime() xor (mode.ordinal * 7919L)) }

    val particles = remember(mode, particleCount) {
        List(particleCount) { idx ->
            WeatherParticle().also { p ->
                resetParticle(p, mode, simRng, initialSpawn = true, index = idx, total = particleCount)
            }
        }
    }

    val isRainLike = mode == WeatherEffectMode.DRIZZLE ||
        mode == WeatherEffectMode.RAIN ||
        mode == WeatherEffectMode.HEAVY_RAIN ||
        mode == WeatherEffectMode.STORM ||
        mode == WeatherEffectMode.HAIL

    val splashPool = remember(mode, isLowEndDevice) {
        val size = if (isRainLike) (if (isLowEndDevice) 10 else 20) else 0
        List(size) { SplashRipple() }
    }

    val lightningBolt = remember(mode) { LightningBoltState() }
    val reusableBoltPath = remember { Path() }
    val reusableHailPath = remember { Path() }

    val simClock = remember(mode) {
        object {
            var lastNanos: Long = System.nanoTime()
            var elapsedSec: Float = 0f
            var nextSplashIdx: Int = 0
            var lastHapticNanos: Long = 0L
        }
    }

    val transition = rememberInfiniteTransition(label = "weatherAtmosphereClock")
    val frameTicker = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 10_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "weatherFrameTicker",
    )

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTicker.value
        val w = size.width
        val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas

        val nowNanos = System.nanoTime()
        val rawDt = (nowNanos - simClock.lastNanos) / 1_000_000_000f
        simClock.lastNanos = nowNanos
        val dt = rawDt.coerceIn(0.001f, 0.050f)
        simClock.elapsedSec += dt
        val elapsedSec = simClock.elapsedSec

        // Update storm lightning state BEFORE drawing volumetric storm clouds so clouds illuminate internally!
        if (mode == WeatherEffectMode.STORM) {
            if (elapsedSec >= lightningBolt.nextStrikeAtSec) {
                lightningBolt.activeFlash = 1.0f
                lightningBolt.originX = 0.16f + simRng.nextFloat() * 0.68f
                lightningBolt.seed = simRng.nextInt(1, 999_999)
                val cooldown = (4.2f + simRng.nextFloat() * 5.8f) / (0.75f + clampedIntensity * 0.35f)
                lightningBolt.nextStrikeAtSec = elapsedSec + cooldown
                if (enableHaptics) {
                    onThunderPulse?.invoke()
                }
            } else if (lightningBolt.activeFlash > 0f) {
                lightningBolt.activeFlash = (lightningBolt.activeFlash - dt * 3.1f).coerceAtLeast(0f)
            }
        } else {
            lightningBolt.activeFlash = 0f
        }

        // 1. TRUE 3D VOLUMETRIC FOG & CLOUD LAYER (AGSL raymarcher on API 33+, 4-plane fBm fallback otherwise)
        if (hasVolumetric) {
            if (volumetricShader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                WeatherVolumetricRenderer.drawAgslVolumetricLayer(
                    drawScope = this,
                    shader = volumetricShader,
                    mode = mode,
                    elapsedSec = elapsedSec,
                    fogDepth = clampedFogDepth,
                    intensity = clampedIntensity,
                    windStrength = clampedWind,
                    isDark = isDark,
                    lightningFlash = lightningBolt.activeFlash,
                )
            } else if (fallbackVolumetricTile != null) {
                WeatherVolumetricRenderer.drawFallbackVolumetricLayers(
                    drawScope = this,
                    tile = fallbackVolumetricTile,
                    mode = mode,
                    elapsedSec = elapsedSec,
                    fogDepth = clampedFogDepth,
                    intensity = clampedIntensity,
                    windStrength = clampedWind,
                    isDark = isDark,
                    lightningFlash = lightningBolt.activeFlash,
                )
            }
        }

        // 2. CLEAR / PARTLY_CLOUDY SUNBEAM & RADIANCE OVERLAY
        if (mode == WeatherEffectMode.CLEAR || mode == WeatherEffectMode.PARTLY_CLOUDY) {
            val sunAlpha = (if (mode == WeatherEffectMode.CLEAR) 0.14f else 0.08f) * clampedIntensity
            val sunColor = if (isDark) Color(0xFFFFE09A) else Color(0xFFFFC95C)
            val sunCenter = Offset(w * 0.78f, h * 0.09f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        sunColor.copy(alpha = sunAlpha.coerceIn(0.03f, 0.24f)),
                        sunColor.copy(alpha = (sunAlpha * 0.32f).coerceIn(0.01f, 0.10f)),
                        Color.Transparent,
                    ),
                    center = sunCenter,
                    radius = w * 0.68f,
                ),
                radius = w * 0.68f,
                center = sunCenter,
            )
        }

        // 3. STORM JAGGED FORKED LIGHTNING BOLT + SKY ILLUMINATION
        if (mode == WeatherEffectMode.STORM && lightningBolt.activeFlash > 0.02f) {
            val flash = lightningBolt.activeFlash
            drawRect(
                color = Color(0xFFDCE8FF).copy(alpha = (flash * 0.16f).coerceIn(0f, 0.22f)),
                size = size,
            )
            if (flash > 0.25f) {
                drawForkedLightningBolt(
                    path = reusableBoltPath,
                    originX = w * lightningBolt.originX,
                    maxHeight = h * 0.62f,
                    seed = lightningBolt.seed,
                    alpha = ((flash - 0.25f) / 0.75f).coerceIn(0f, 1f),
                )
            }
        }

        // 4. PARTICLE SIMULATION & RENDERING
        val gustWave = sin(elapsedSec * 0.65f) * 0.06f + sin(elapsedSec * 1.45f) * 0.03f
        val windX = (clampedWind - 0.15f) * 0.34f + gustWave * (0.4f + clampedWind)

        val rainColor = when (mode) {
            WeatherEffectMode.DRIZZLE -> if (isDark) Color(0xFFCCE2FA) else Color(0xFF476994)
            WeatherEffectMode.STORM -> if (isDark) Color(0xFFD6E8FF) else Color(0xFF284878)
            else -> if (isDark) Color(0xFFC2DCFF) else Color(0xFF335B8C)
        }
        val snowColor = if (isDark) Color(0xFFF2F8FF) else Color(0xFF4E6884)
        val hailColor = if (isDark) Color(0xFFE6F4FF) else Color(0xFF6284A8)

        val minHapticIntervalNanos = when (mode) {
            WeatherEffectMode.DRIZZLE -> 230_000_000L
            WeatherEffectMode.RAIN -> 130_000_000L
            WeatherEffectMode.HEAVY_RAIN -> 78_000_000L
            WeatherEffectMode.STORM -> 68_000_000L
            WeatherEffectMode.HAIL -> 85_000_000L
            else -> Long.MAX_VALUE
        }

        for (i in particles.indices) {
            val p = particles[i]
            if (p.respawnDelay > 0f) {
                p.respawnDelay -= dt
                continue
            }

            when (mode) {
                WeatherEffectMode.CLEAR,
                WeatherEffectMode.PARTLY_CLOUDY,
                WeatherEffectMode.FOG,
                -> {
                    p.x += (p.vx + windX * 0.08f) * dt
                    p.y += (p.vy + sin(elapsedSec * 0.7f + p.phase) * 0.008f) * dt
                    if (p.x > 1.08f) p.x = -0.06f
                    if (p.x < -0.08f) p.x = 1.06f
                    if (p.y < -0.06f) p.y = 1.04f
                    if (p.y > 1.08f) p.y = -0.04f

                    val twinkle = 0.55f + 0.45f * sin(elapsedSec * 1.6f + p.phase)
                    val moteColor = when (mode) {
                        WeatherEffectMode.FOG -> if (isDark) Color(0xFFD8E8F8) else Color(0xFFFFFFFF)
                        else -> if (isDark) Color(0xFFFFE6A8) else Color(0xFFFFB74D)
                    }
                    val radiusPx = (p.length * (0.65f + p.depthLayer * 0.55f)).coerceIn(1.5f, 5.5f)
                    val alpha = (p.alpha * twinkle * clampedIntensity).coerceIn(0.03f, 0.34f)
                    drawCircle(
                        color = moteColor.copy(alpha = alpha),
                        radius = radiusPx,
                        center = Offset(p.x * w, p.y * h),
                    )
                }

                WeatherEffectMode.SNOW,
                WeatherEffectMode.BLIZZARD,
                -> {
                    val isBlizzard = mode == WeatherEffectMode.BLIZZARD
                    val flutter = sin(elapsedSec * (if (isBlizzard) 3.4f else 1.7f) + p.phase) *
                        (if (isBlizzard) 0.09f else 0.045f)
                    val windDrift = if (isBlizzard) {
                        -(0.32f + clampedWind * 0.58f) * (0.65f + p.depthLayer * 0.65f)
                    } else {
                        -windX * (0.28f + p.depthLayer * 0.35f)
                    }
                    p.x += (p.vx + windDrift + flutter) * dt
                    p.y += p.vy * (if (isBlizzard) (1.0f + clampedWind * 0.35f) else 1.0f) * dt

                    if (p.y > 1.06f || p.x < -0.15f || p.x > 1.15f) {
                        resetParticle(p, mode, simRng, initialSpawn = false, index = i, total = particles.size)
                        continue
                    }

                    val cx = p.x * w
                    val cy = p.y * h
                    val r = p.strokeWidth * (0.72f + p.depthLayer * 0.62f)
                    val a = (p.alpha * clampedIntensity).coerceIn(0.08f, 0.72f)
                    if (isBlizzard && p.depthLayer > 0.45f) {
                        // Fast wind-sheared snow streak
                        val tailDx = (8f + clampedWind * 14f) * p.depthLayer
                        val tailDy = -r * 2.2f
                        drawLine(
                            color = snowColor.copy(alpha = a * 0.85f),
                            start = Offset(cx + tailDx, cy + tailDy),
                            end = Offset(cx, cy),
                            strokeWidth = r.coerceIn(1.2f, 3.2f),
                            cap = StrokeCap.Round,
                        )
                    } else {
                        // Soft glowing 3D depth snowflake
                        drawCircle(
                            color = snowColor.copy(alpha = a * 0.30f),
                            radius = r * 2.1f,
                            center = Offset(cx, cy),
                        )
                        drawCircle(
                            color = snowColor.copy(alpha = a),
                            radius = r,
                            center = Offset(cx, cy),
                        )
                    }
                }

                WeatherEffectMode.HAIL -> {
                    val gravity = 2.65f
                    p.vy += gravity * dt
                    p.x += (p.vx - windX * 0.38f) * dt
                    p.y += p.vy * dt

                    if (p.y >= p.groundY) {
                        if (p.bounceCount == 0) {
                            // First impact: physical ice bounce + crisp splash ring + haptic tap!
                            p.y = p.groundY
                            p.vy = -p.vy * (0.34f + simRng.nextFloat() * 0.14f)
                            p.vx += (simRng.nextFloat() - 0.5f) * 0.42f
                            p.bounceCount = 1

                            if (splashPool.isNotEmpty()) {
                                val splash = splashPool[simClock.nextSplashIdx % splashPool.size]
                                simClock.nextSplashIdx++
                                splash.x = p.x * w
                                splash.y = p.groundY * h
                                splash.progress = 0f
                                splash.maxRadiusX = 9f + p.depthLayer * 9f
                                splash.maxRadiusY = 3.2f + p.depthLayer * 2.8f
                                splash.strength = 0.78f
                                splash.dropletHeight = 8f
                                splash.dropletSpread = 7f
                            }
                            if (enableHaptics && p.isHero && (nowNanos - simClock.lastHapticNanos) >= minHapticIntervalNanos) {
                                simClock.lastHapticNanos = nowNanos
                                onRaindropImpact?.invoke(
                                    (0.68f + p.depthLayer * 0.30f).coerceIn(0.45f, 1.0f),
                                    false,
                                    true,
                                )
                            }
                        } else {
                            resetParticle(p, mode, simRng, initialSpawn = false, index = i, total = particles.size)
                            continue
                        }
                    }

                    val cx = p.x * w
                    val cy = p.y * h
                    val r = (p.strokeWidth * (0.85f + p.depthLayer * 0.55f)).coerceIn(2.2f, 5.2f)
                    val a = (p.alpha * clampedIntensity).coerceIn(0.24f, 0.88f)
                    // Draw crisp faceted ice crystal diamond
                    reusableHailPath.reset()
                    reusableHailPath.moveTo(cx, cy - r * 1.15f)
                    reusableHailPath.lineTo(cx + r * 0.85f, cy)
                    reusableHailPath.lineTo(cx, cy + r * 1.15f)
                    reusableHailPath.lineTo(cx - r * 0.85f, cy)
                    reusableHailPath.close()
                    drawPath(path = reusableHailPath, color = hailColor.copy(alpha = a))
                    // Bright specular glint
                    drawCircle(
                        color = Color.White.copy(alpha = (a * 0.85f).coerceAtMost(0.95f)),
                        radius = r * 0.36f,
                        center = Offset(cx - r * 0.22f, cy - r * 0.28f),
                    )
                }

                WeatherEffectMode.DRIZZLE,
                WeatherEffectMode.RAIN,
                WeatherEffectMode.HEAVY_RAIN,
                WeatherEffectMode.STORM,
                -> {
                    val effectiveVx = p.vx - windX * (0.35f + clampedWind * 0.45f)
                    p.x += effectiveVx * dt
                    p.y += p.vy * dt

                    if (p.y >= p.groundY) {
                        val impactX = p.x * w
                        val impactY = p.groundY * h

                        if (splashPool.isNotEmpty() && (p.isHero || mode != WeatherEffectMode.DRIZZLE)) {
                            val splash = splashPool[simClock.nextSplashIdx % splashPool.size]
                            simClock.nextSplashIdx++
                            splash.x = impactX
                            splash.y = impactY
                            splash.progress = 0f
                            val scale = when (mode) {
                                WeatherEffectMode.DRIZZLE -> 0.58f
                                WeatherEffectMode.RAIN -> 0.92f
                                WeatherEffectMode.HEAVY_RAIN -> 1.18f
                                WeatherEffectMode.STORM -> 1.28f
                                else -> 1.0f
                            }
                            splash.maxRadiusX = (10f + p.strokeWidth * 6f) * scale
                            splash.maxRadiusY = (3.0f + p.strokeWidth * 1.8f) * scale
                            splash.strength = (p.alpha * 1.8f).coerceIn(0.25f, 1.0f)
                            splash.dropletHeight = (7f + p.strokeWidth * 4.5f) * scale
                            splash.dropletSpread = (5.5f + p.strokeWidth * 3.5f) * scale
                        }

                        if (enableHaptics && p.isHero && (nowNanos - simClock.lastHapticNanos) >= minHapticIntervalNanos) {
                            simClock.lastHapticNanos = nowNanos
                            val dropStrength = when (mode) {
                                WeatherEffectMode.DRIZZLE -> (0.18f + p.depthLayer * 0.16f)
                                WeatherEffectMode.RAIN -> (0.34f + p.depthLayer * 0.26f)
                                WeatherEffectMode.HEAVY_RAIN -> (0.58f + p.depthLayer * 0.30f)
                                WeatherEffectMode.STORM -> (0.68f + p.depthLayer * 0.30f)
                                else -> 0.4f
                            } * clampedIntensity.coerceIn(0.6f, 1.25f)
                            onRaindropImpact?.invoke(
                                dropStrength.coerceIn(0.15f, 1.0f),
                                mode == WeatherEffectMode.DRIZZLE,
                                mode == WeatherEffectMode.HEAVY_RAIN || mode == WeatherEffectMode.STORM,
                            )
                        }

                        resetParticle(p, mode, simRng, initialSpawn = false, index = i, total = particles.size)
                        continue
                    }

                    if (p.x < -0.18f) p.x += 1.24f
                    if (p.x > 1.18f) p.x -= 1.24f

                    val endX = p.x * w
                    val endY = p.y * h
                    val streakScale = p.length * (0.72f + p.depthLayer * 0.52f)
                    val slantX = -(effectiveVx / p.vy.coerceAtLeast(0.3f)) * streakScale
                    val startX = endX + slantX
                    val startY = endY - streakScale
                    val alpha = (p.alpha * clampedIntensity).coerceIn(0.06f, 0.58f)

                    drawLine(
                        color = rainColor.copy(alpha = alpha),
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = p.strokeWidth,
                        cap = StrokeCap.Round,
                    )
                }

                else -> Unit
            }
        }

        // 5. GROUND IMPACT SPLASH RIPPLES & ARCING MICRO-DROPLETS
        if (splashPool.isNotEmpty()) {
            val splashTint = if (mode == WeatherEffectMode.HAIL) hailColor else rainColor
            for (s in splashPool) {
                if (s.progress >= 1f) continue
                s.progress += dt * 3.1f
                if (s.progress >= 1f) continue

                val t = s.progress
                val fade = (1f - t) * (1f - t)
                val rx = s.maxRadiusX * (0.28f + 0.72f * t)
                val ry = s.maxRadiusY * (0.30f + 0.70f * t)
                val ringAlpha = (0.38f * s.strength * fade * clampedIntensity).coerceIn(0f, 0.52f)

                drawOval(
                    color = splashTint.copy(alpha = ringAlpha),
                    topLeft = Offset(s.x - rx, s.y - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = (1.35f - t * 0.65f).coerceAtLeast(0.5f)),
                )

                // Symmetrical arcing rebound micro-droplets
                val arcY = 4f * t * (1f - t) * s.dropletHeight
                val spreadX = t * s.dropletSpread
                val dropAlpha = (0.48f * s.strength * fade).coerceIn(0f, 0.56f)
                val dropRadius = (1.55f * (1f - t * 0.55f)).coerceAtLeast(0.6f)
                drawCircle(
                    color = splashTint.copy(alpha = dropAlpha),
                    radius = dropRadius,
                    center = Offset(s.x - spreadX, s.y - arcY),
                )
                drawCircle(
                    color = splashTint.copy(alpha = dropAlpha),
                    radius = dropRadius,
                    center = Offset(s.x + spreadX * 0.9f, s.y - arcY * 0.85f),
                )
            }
        }
    }
}

/**
 * Draws a crisp procedural forked lightning bolt with luminous outer halo and bright white core.
 */
private fun DrawScope.drawForkedLightningBolt(
    path: Path,
    originX: Float,
    maxHeight: Float,
    seed: Int,
    alpha: Float,
) {
    val rng = Random(seed)
    path.reset()
    var cx = originX
    var cy = 0f
    path.moveTo(cx, cy)

    val segments = 8
    val stepY = maxHeight / segments
    val branchPoints = ArrayList<Pair<Offset, Float>>(3)

    for (seg in 1..segments) {
        val jitter = (rng.nextFloat() - 0.5f) * size.width * 0.12f
        cx = (cx + jitter).coerceIn(size.width * 0.06f, size.width * 0.94f)
        cy = stepY * seg
        path.lineTo(cx, cy)
        if (seg in 2..5 && rng.nextFloat() < 0.58f) {
            branchPoints.add(Offset(cx, cy) to if (rng.nextBoolean()) 1f else -1f)
        }
    }

    // Outer electric violet-blue glow
    drawPath(
        path = path,
        color = Color(0xFF9EC5FF).copy(alpha = (alpha * 0.42f).coerceIn(0f, 0.55f)),
        style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    // Inner crisp white core
    drawPath(
        path = path,
        color = Color.White.copy(alpha = (alpha * 0.92f).coerceIn(0f, 0.96f)),
        style = Stroke(width = 2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )

    // Secondary jagged forks
    for ((start, dir) in branchPoints) {
        path.reset()
        var bx = start.x
        var by = start.y
        path.moveTo(bx, by)
        repeat(3) {
            bx += dir * (size.width * (0.025f + rng.nextFloat() * 0.045f))
            by += stepY * (0.55f + rng.nextFloat() * 0.45f)
            path.lineTo(bx, by)
        }
        drawPath(
            path = path,
            color = Color(0xFFB5D4FF).copy(alpha = (alpha * 0.34f).coerceIn(0f, 0.45f)),
            style = Stroke(width = 4.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            path = path,
            color = Color.White.copy(alpha = (alpha * 0.78f).coerceIn(0f, 0.85f)),
            style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

private fun resetParticle(
    p: WeatherParticle,
    mode: WeatherEffectMode,
    rng: Random,
    initialSpawn: Boolean,
    index: Int,
    total: Int,
) {
    val depth = rng.nextFloat()
    p.depthLayer = depth
    p.phase = rng.nextFloat() * 6.28318f
    p.bounceCount = 0
    p.isHero = depth > 0.66f

    when (mode) {
        WeatherEffectMode.CLEAR,
        WeatherEffectMode.PARTLY_CLOUDY,
        WeatherEffectMode.FOG,
        -> {
            p.x = rng.nextFloat()
            p.y = rng.nextFloat()
            p.vx = (rng.nextFloat() - 0.35f) * 0.028f
            p.vy = -0.008f - rng.nextFloat() * 0.018f
            p.length = 1.8f + depth * 3.0f
            p.strokeWidth = p.length
            p.alpha = if (mode == WeatherEffectMode.FOG) 0.14f + depth * 0.16f else 0.18f + depth * 0.22f
            p.groundY = 1.05f
            p.respawnDelay = 0f
        }

        WeatherEffectMode.DRIZZLE -> {
            p.x = -0.05f + rng.nextFloat() * 1.15f
            p.y = if (initialSpawn) rng.nextFloat() else (-0.08f - rng.nextFloat() * 0.20f)
            p.vx = -0.04f - rng.nextFloat() * 0.04f
            p.vy = 0.72f + depth * 0.52f
            p.length = 9f + depth * 11f
            p.strokeWidth = 0.85f + depth * 0.55f
            p.alpha = 0.16f + depth * 0.22f
            p.groundY = 0.72f + depth * 0.26f
            p.respawnDelay = if (initialSpawn) 0f else rng.nextFloat() * 0.30f
        }

        WeatherEffectMode.RAIN -> {
            p.x = -0.06f + rng.nextFloat() * 1.18f
            p.y = if (initialSpawn) rng.nextFloat() else (-0.10f - rng.nextFloat() * 0.22f)
            p.vx = -0.06f - rng.nextFloat() * 0.06f
            p.vy = 1.15f + depth * 0.80f
            p.length = 15f + depth * 17f
            p.strokeWidth = 1.0f + depth * 0.85f
            p.alpha = 0.18f + depth * 0.28f
            p.groundY = 0.70f + depth * 0.28f
            p.respawnDelay = if (initialSpawn) 0f else rng.nextFloat() * 0.18f
        }

        WeatherEffectMode.HEAVY_RAIN,
        WeatherEffectMode.STORM,
        -> {
            val stormMult = if (mode == WeatherEffectMode.STORM) 1.16f else 1.0f
            p.x = -0.08f + rng.nextFloat() * 1.24f
            p.y = if (initialSpawn) rng.nextFloat() else (-0.12f - rng.nextFloat() * 0.22f)
            p.vx = (-0.10f - rng.nextFloat() * 0.08f) * stormMult
            p.vy = (1.55f + depth * 1.05f) * stormMult
            p.length = (20f + depth * 22f) * stormMult
            p.strokeWidth = 1.15f + depth * 1.05f
            p.alpha = 0.22f + depth * 0.34f
            p.groundY = 0.68f + depth * 0.30f
            p.respawnDelay = if (initialSpawn) 0f else rng.nextFloat() * 0.10f
        }

        WeatherEffectMode.SNOW -> {
            p.x = if (initialSpawn) (index.toFloat() / total.coerceAtLeast(1)) else (-0.05f + rng.nextFloat() * 1.15f)
            p.y = if (initialSpawn) rng.nextFloat() else (-0.06f - rng.nextFloat() * 0.16f)
            p.vx = (rng.nextFloat() - 0.5f) * 0.04f
            p.vy = 0.08f + depth * 0.14f
            p.length = 2.5f + depth * 3.5f
            p.strokeWidth = 1.8f + depth * 2.8f
            p.alpha = 0.24f + depth * 0.44f
            p.groundY = 1.05f
            p.respawnDelay = if (initialSpawn) 0f else rng.nextFloat() * 0.25f
        }

        WeatherEffectMode.BLIZZARD -> {
            p.x = if (initialSpawn) rng.nextFloat() else (0.15f + rng.nextFloat() * 1.05f)
            p.y = if (initialSpawn) rng.nextFloat() else (-0.08f - rng.nextFloat() * 0.20f)
            p.vx = -0.18f - depth * 0.22f
            p.vy = 0.22f + depth * 0.36f
            p.length = 3.0f + depth * 4.2f
            p.strokeWidth = 1.6f + depth * 2.4f
            p.alpha = 0.26f + depth * 0.46f
            p.groundY = 1.05f
            p.respawnDelay = 0f
        }

        WeatherEffectMode.HAIL -> {
            p.x = -0.05f + rng.nextFloat() * 1.18f
            p.y = if (initialSpawn) rng.nextFloat() * 0.85f else (-0.08f - rng.nextFloat() * 0.25f)
            p.vx = -0.08f - rng.nextFloat() * 0.10f
            p.vy = 0.95f + depth * 0.70f
            p.length = 4.0f + depth * 4.0f
            p.strokeWidth = 2.4f + depth * 2.4f
            p.alpha = 0.42f + depth * 0.42f
            p.groundY = 0.72f + depth * 0.24f
            p.respawnDelay = if (initialSpawn) 0f else rng.nextFloat() * 0.22f
        }

        else -> {
            p.x = 0.5f
            p.y = -0.2f
            p.respawnDelay = 10f
        }
    }
}
