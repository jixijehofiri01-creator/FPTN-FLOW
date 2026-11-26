package com.example.core.ui.effects

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Immutable 3D glass shard geometry used by both the Error Shatter and Mystery Reveal animations.
 * Pre-computed once at class load for zero per-frame allocations at 60/120fps.
 */
internal data class ShatterGlassShard(
    val verticesNorm: FloatArray,
    val cx: Float,
    val cy: Float,
    val vx: Float,
    val vy: Float,
    val spinZRadPerSec: Float,
    val tumbleXRadPerSec: Float,
    val tumbleYRadPerSec: Float,
    val tintAlpha: Float,
    val highlightBoost: Float,
    val isMicroSplinter: Boolean,
    val delaySec: Float,
)

/**
 * Immutable crack segment in normalized viewport space [-0.5..+0.5] radiating from an impact center.
 */
internal data class CrackSegment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val thicknessFactor: Float,
    val revealThreshold: Float,
    val isPrimary: Boolean,
)

/**
 * Zero-allocation tempered cracked-glass & multi-layer 3D shattering glass effects:
 * 1. [drawCrackedCrimsonGlassError] — Replaces thin red rings with an atmospheric crimson backdrop,
 *    multi-branch tempered glass spiderweb cracks (dark depth shadow + sharp refractive specular rim),
 *    and detaching micro-shards tumbling with gravity.
 * 2. [drawComplexGlassShatter] — 42-piece multi-layer 3D shattering glass burst (large bevelled
 *    panels + medium wedges + sparkling diamond micro-splinters + specular tumble flashes).
 */
internal object CrackedGlassEffects {

    private fun hashJitter(seed: Int, amp: Float): Float {
        val v = sin(seed * 12.9898f + 78.233f) * 43758.5453f
        return ((v - floor(v)) * 2f - 1f) * amp
    }

    /**
     * Deterministic spiderweb tempered-glass fracture graph radiating from impact epicenter.
     */
    val errorCrackSegments: List<CrackSegment> by lazy {
        val originX = 0.03f
        val originY = -0.04f
        val rayCount = 11
        val ringPtsX = Array(3) { FloatArray(rayCount) }
        val ringPtsY = Array(3) { FloatArray(rayCount) }
        val ringRadii = floatArrayOf(0.11f, 0.25f, 0.44f)

        buildList {
            for (i in 0 until rayCount) {
                val baseAngle = (i.toFloat() / rayCount) * (2f * PI.toFloat()) + 0.18f
                var prevX = originX
                var prevY = originY
                for (step in 0..2) {
                    val ang = baseAngle + hashJitter(i * 31 + step * 17, 0.14f)
                    val rad = ringRadii[step] + hashJitter(i * 19 + step * 29, 0.038f)
                    val nx = originX + cos(ang) * rad
                    val ny = originY + sin(ang) * rad
                    ringPtsX[step][i] = nx
                    ringPtsY[step][i] = ny
                    add(
                        CrackSegment(
                            x0 = prevX,
                            y0 = prevY,
                            x1 = nx,
                            y1 = ny,
                            thicknessFactor = 1.0f - step * 0.24f,
                            revealThreshold = step * 0.22f + abs(hashJitter(i * 7 + step, 0.08f)),
                            isPrimary = true,
                        ),
                    )
                    // Forked jagged stress branch on mid & outer segments
                    if (step >= 1 && (i + step) % 2 == 0) {
                        val forkAng = ang + if (i % 2 == 0) 0.58f else -0.58f
                        val forkLen = 0.09f + abs(hashJitter(i * 43 + step * 11, 0.09f))
                        val fx = nx + cos(forkAng) * forkLen
                        val fy = ny + sin(forkAng) * forkLen
                        add(
                            CrackSegment(
                                x0 = nx,
                                y0 = ny,
                                x1 = fx,
                                y1 = fy,
                                thicknessFactor = 0.52f,
                                revealThreshold = 0.36f + step * 0.18f,
                                isPrimary = false,
                            ),
                        )
                    }
                    prevX = nx
                    prevY = ny
                }
            }

            // Concentric spiderweb cross-fractures bridging adjacent rays (crisp glass pane effect)
            for (step in 0..1) {
                for (i in 0 until rayCount) {
                    if ((i + step) % 3 == 2) continue // Natural asymmetric gaps
                    val next = (i + 1) % rayCount
                    add(
                        CrackSegment(
                            x0 = ringPtsX[step][i],
                            y0 = ringPtsY[step][i],
                            x1 = ringPtsX[step][next],
                            y1 = ringPtsY[step][next],
                            thicknessFactor = if (step == 0) 0.65f else 0.45f,
                            revealThreshold = 0.24f + step * 0.28f,
                            isPrimary = false,
                        ),
                    )
                }
            }
        }
    }

    /**
     * 42-piece multi-layer 3D shattering glass geometry:
     * - 10 inner primary triangular crystal shards
     * - 14 outer quadrilateral bevelled glass panels
     * - 18 high-velocity sparkling diamond micro-splinters
     */
    val complexGlassShards: List<ShatterGlassShard> by lazy {
        val innerCount = 10
        val outerCount = 14
        val microCount = 18
        val originX = 0.02f
        val originY = -0.03f

        buildList {
            val innerPtsX = FloatArray(innerCount)
            val innerPtsY = FloatArray(innerCount)
            for (i in 0 until innerCount) {
                val baseAngle = (i.toFloat() / innerCount) * (2f * PI.toFloat())
                val a = baseAngle + hashJitter(i * 17 + 3, 0.13f)
                val rad = 0.23f + hashJitter(i * 23 + 7, 0.055f)
                innerPtsX[i] = (originX + cos(a) * rad).coerceIn(-0.46f, 0.46f)
                innerPtsY[i] = (originY + sin(a) * rad).coerceIn(-0.46f, 0.46f)
            }

            // Layer 1: Inner core triangular shards
            for (i in 0 until innerCount) {
                val next = (i + 1) % innerCount
                val verts = floatArrayOf(
                    originX, originY,
                    innerPtsX[i], innerPtsY[i],
                    innerPtsX[next], innerPtsY[next],
                )
                val cx = (verts[0] + verts[2] + verts[4]) / 3f
                val cy = (verts[1] + verts[3] + verts[5]) / 3f
                val dist = hypot(cx, cy).coerceAtLeast(0.05f)
                val speed = 1.25f + abs(hashJitter(i * 31 + 11, 0.85f))
                add(
                    ShatterGlassShard(
                        verticesNorm = verts,
                        cx = cx,
                        cy = cy,
                        vx = (cx / dist) * speed + hashJitter(i * 41, 0.30f),
                        vy = (cy / dist) * speed - 0.42f + hashJitter(i * 53, 0.25f),
                        spinZRadPerSec = hashJitter(i * 61 + 5, 6.8f),
                        tumbleXRadPerSec = hashJitter(i * 73 + 2, 7.5f),
                        tumbleYRadPerSec = hashJitter(i * 79 + 9, 6.2f),
                        tintAlpha = 0.82f + hashJitter(i * 71, 0.10f),
                        highlightBoost = 0.65f + abs(hashJitter(i * 83, 0.35f)),
                        isMicroSplinter = false,
                        delaySec = 0f,
                    ),
                )
            }

            // Layer 2: Outer bevelled quadrilateral glass panels
            val outerPtsX = FloatArray(outerCount)
            val outerPtsY = FloatArray(outerCount)
            for (i in 0 until outerCount) {
                val a = (i.toFloat() / outerCount) * (2f * PI.toFloat()) + hashJitter(i * 19 + 13, 0.08f)
                val dx = cos(a)
                val dy = sin(a)
                val tx = if (abs(dx) > 0.001f) 0.5f / abs(dx) else 10f
                val ty = if (abs(dy) > 0.001f) 0.5f / abs(dy) else 10f
                val t = min(tx, ty)
                outerPtsX[i] = (dx * t).coerceIn(-0.5f, 0.5f)
                outerPtsY[i] = (dy * t).coerceIn(-0.5f, 0.5f)
            }
            for (i in 0 until outerCount) {
                val next = (i + 1) % outerCount
                val innerIdxA = (i * innerCount) / outerCount
                val innerIdxB = ((next * innerCount) / outerCount) % innerCount
                val verts = floatArrayOf(
                    innerPtsX[innerIdxA], innerPtsY[innerIdxA],
                    outerPtsX[i], outerPtsY[i],
                    outerPtsX[next], outerPtsY[next],
                    innerPtsX[innerIdxB], innerPtsY[innerIdxB],
                )
                val cx = (verts[0] + verts[2] + verts[4] + verts[6]) / 4f
                val cy = (verts[1] + verts[3] + verts[5] + verts[7]) / 4f
                val dist = hypot(cx, cy).coerceAtLeast(0.08f)
                val speed = 1.65f + abs(hashJitter(i * 37 + 19, 1.05f))
                add(
                    ShatterGlassShard(
                        verticesNorm = verts,
                        cx = cx,
                        cy = cy,
                        vx = (cx / dist) * speed + hashJitter(i * 43, 0.35f),
                        vy = (cy / dist) * speed - 0.28f + hashJitter(i * 59, 0.30f),
                        spinZRadPerSec = hashJitter(i * 67 + 9, 4.9f),
                        tumbleXRadPerSec = hashJitter(i * 89 + 3, 5.4f),
                        tumbleYRadPerSec = hashJitter(i * 97 + 1, 4.6f),
                        tintAlpha = 0.72f + hashJitter(i * 73, 0.14f),
                        highlightBoost = 0.48f + abs(hashJitter(i * 89, 0.45f)),
                        isMicroSplinter = false,
                        delaySec = 0.025f,
                    ),
                )
            }

            // Layer 3: 18 high-speed sparkling diamond micro-splinters
            for (i in 0 until microCount) {
                val a = (i.toFloat() / microCount) * (2f * PI.toFloat()) + hashJitter(i * 29 + 41, 0.25f)
                val startRad = 0.04f + abs(hashJitter(i * 47 + 7, 0.20f))
                val cx = originX + cos(a) * startRad
                val cy = originY + sin(a) * startRad
                val sz = 0.022f + abs(hashJitter(i * 53 + 13, 0.024f))
                val verts = floatArrayOf(
                    cx, cy - sz,
                    cx + sz * 0.62f, cy,
                    cx, cy + sz * 1.15f,
                    cx - sz * 0.55f, cy + sz * 0.12f,
                )
                val speed = 2.05f + abs(hashJitter(i * 61 + 23, 1.45f))
                add(
                    ShatterGlassShard(
                        verticesNorm = verts,
                        cx = cx,
                        cy = cy,
                        vx = cos(a) * speed,
                        vy = sin(a) * speed - 0.55f,
                        spinZRadPerSec = hashJitter(i * 71 + 17, 11.5f),
                        tumbleXRadPerSec = hashJitter(i * 83 + 19, 13.0f),
                        tumbleYRadPerSec = hashJitter(i * 101 + 5, 10.5f),
                        tintAlpha = 0.90f,
                        highlightBoost = 0.95f,
                        isMicroSplinter = true,
                        delaySec = abs(hashJitter(i * 13, 0.05f)),
                    ),
                )
            }
        }
    }

    /**
     * Draws an atmospheric crimson backdrop with realistic cracked tempered glass and detaching
     * crimson/white glass splinters (replaces the old thin red concentric circles).
     */
    fun drawCrackedCrimsonGlassError(
        nc: AndroidCanvas,
        w: Float,
        h: Float,
        centerX: Float,
        centerY: Float,
        progress: Float,
        isPreview: Boolean,
        errorColorArgb: Int,
        fillPaint: Paint,
        strokePaint: Paint,
        shardPath: AndroidPath,
    ) {
        val envelope = if (isPreview) {
            val inF = easeOutCubic((progress / 0.18f).coerceIn(0f, 1f))
            val outF = 1f - easeInOutCubic(((progress - 0.74f) / 0.26f).coerceIn(0f, 1f))
            inF * outF
        } else {
            easeOutCubic((progress / 0.28f).coerceIn(0f, 1f))
        }
        if (envelope <= 0.01f) return

        val rgb = errorColorArgb and 0x00FFFFFF
        val radius = max(w, h) * 0.76f

        // 1. Deep atmospheric crimson wash (NO thin concentric circles!)
        val prevShader = fillPaint.shader
        fillPaint.shader = RadialGradient(
            centerX,
            centerY,
            radius,
            intArrayOf(
                ((0x62 * envelope).toInt().coerceIn(0, 255) shl 24) or rgb,
                ((0x38 * envelope).toInt().coerceIn(0, 255) shl 24) or 0x7A0B14,
                0x00000000,
            ),
            floatArrayOf(0f, 0.60f, 1f),
            Shader.TileMode.CLAMP,
        )
        nc.drawRect(0f, 0f, w, h, fillPaint)
        fillPaint.shader = prevShader

        // 2. Impact flash burst right at shatter moment (first 24% of transition)
        val impactProgress = (progress / 0.24f).coerceIn(0f, 1f)
        if (impactProgress < 1f) {
            val flashAlpha = (1f - impactProgress).pow(2) * 0.56f * envelope
            fillPaint.color = ((flashAlpha * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFD9DE
            nc.drawCircle(centerX + w * 0.03f, centerY - h * 0.04f, min(w, h) * (0.16f + impactProgress * 0.28f), fillPaint)
        }

        // 3. Multi-layered tempered-glass spiderweb crack network (shadow bevel + bright refractive edge)
        val crackGrowth = if (isPreview) {
            easeOutCubic((progress / 0.32f).coerceIn(0f, 1f))
        } else {
            easeOutCubic((progress / 0.38f).coerceIn(0f, 1f))
        }
        val span = min(w, h) * 1.14f
        val baseStroke = (span * 0.0048f).coerceIn(1.6f, 3.4f)

        strokePaint.strokeCap = Paint.Cap.ROUND
        for (seg in errorCrackSegments) {
            val local = ((crackGrowth - seg.revealThreshold * 0.62f) / 0.38f).coerceIn(0f, 1f)
            if (local <= 0.01f) continue

            val x0 = centerX + seg.x0 * span
            val y0 = centerY + seg.y0 * span
            val x1 = centerX + (seg.x0 + (seg.x1 - seg.x0) * local) * span
            val y1 = centerY + (seg.y0 + (seg.y1 - seg.y0) * local) * span

            // Depth shadow fracture groove
            val shadowAlpha = (envelope * (if (seg.isPrimary) 195f else 135f)).toInt().coerceIn(0, 235)
            strokePaint.color = (shadowAlpha shl 24) or 0x240408
            strokePaint.strokeWidth = baseStroke * seg.thicknessFactor * 1.5f
            nc.drawLine(x0 + 1.5f, y0 + 1.9f, x1 + 1.5f, y1 + 1.9f, strokePaint)

            // Crisp refractive white-rose glass edge catching light
            val edgeAlpha = (envelope * (if (seg.isPrimary) 225f else 155f)).toInt().coerceIn(0, 245)
            strokePaint.color = (edgeAlpha shl 24) or 0xFFF0F2
            strokePaint.strokeWidth = baseStroke * seg.thicknessFactor * 0.80f
            nc.drawLine(x0, y0, x1, y1, strokePaint)
        }

        // 4. Detaching sparkling crimson-tinted glass micro-shards falling under gravity
        val shatterT = (progress * 1.35f).coerceIn(0f, 1.35f)
        val shardFade = (1f - ((shatterT - 0.35f) / 0.95f).coerceIn(0f, 1f)).pow(1.3f) * envelope
        if (shardFade > 0.01f) {
            drawGlassShardParticles(
                nc = nc,
                centerX = centerX,
                centerY = centerY,
                baseW = span * 0.62f,
                baseH = span * 0.62f,
                tSec = shatterT * 0.85f,
                shardFade = shardFade,
                bodyRgb = 0x5C121B,
                sheenRgb = 0xFFDCE2,
                rimRgb = 0xFFF2F4,
                onlyMicroAndInner = true,
                fillPaint = fillPaint,
                strokePaint = strokePaint,
                shardPath = shardPath,
            )
        }
    }

    /**
     * Renders the upgraded 42-piece complex 3D glass shatter explosion for Mystery Reveal
     * with impact shockwave refraction, bevelled dark glass backfaces, dynamic 3D specular glint,
     * and high-velocity diamond splinters.
     */
    fun drawComplexGlassShatter(
        nc: AndroidCanvas,
        centerX: Float,
        centerY: Float,
        baseW: Float,
        baseH: Float,
        progress: Float,
        fillPaint: Paint,
        strokePaint: Paint,
        shardPath: AndroidPath,
    ) {
        // 1. Multi-layer crystalline impact flash + refractive shockwave ring
        if (progress < 0.38f) {
            val flashT = (progress / 0.38f).coerceIn(0f, 1f)
            val flashAlpha = (1f - flashT).pow(2) * 0.64f
            val flashR = baseW * (0.36f + flashT * 1.55f)
            val prevShader = fillPaint.shader
            fillPaint.shader = RadialGradient(
                centerX,
                centerY,
                flashR.coerceAtLeast(10f),
                intArrayOf(
                    ((flashAlpha * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF,
                    ((flashAlpha * 140f).toInt().coerceIn(0, 255) shl 24) or 0xB8E4FF,
                    0x00000000,
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            nc.drawCircle(centerX, centerY, flashR, fillPaint)
            fillPaint.shader = prevShader
        }

        // 2. 42 tumbling 3D glass panels & sparkling micro-splinters
        val tSec = progress * 1.08f
        val shardFade = (1f - ((progress - 0.30f) / 0.70f).coerceIn(0f, 1f)).pow(1.35f)
        if (shardFade > 0.01f) {
            drawGlassShardParticles(
                nc = nc,
                centerX = centerX,
                centerY = centerY,
                baseW = baseW,
                baseH = baseH,
                tSec = tSec,
                shardFade = shardFade,
                bodyRgb = 0x1A2438,
                sheenRgb = 0xE2F4FF,
                rimRgb = 0xFFFFFF,
                onlyMicroAndInner = false,
                fillPaint = fillPaint,
                strokePaint = strokePaint,
                shardPath = shardPath,
            )
        }
    }

    private fun drawGlassShardParticles(
        nc: AndroidCanvas,
        centerX: Float,
        centerY: Float,
        baseW: Float,
        baseH: Float,
        tSec: Float,
        shardFade: Float,
        bodyRgb: Int,
        sheenRgb: Int,
        rimRgb: Int,
        onlyMicroAndInner: Boolean,
        fillPaint: Paint,
        strokePaint: Paint,
        shardPath: AndroidPath,
    ) {
        val gravity = 2.85f
        for (shard in complexGlassShards) {
            if (onlyMicroAndInner && !shard.isMicroSplinter && shard.verticesNorm.size > 6) {
                continue
            }
            val localT = (tSec - shard.delaySec).coerceAtLeast(0f)
            if (localT <= 0.001f) continue

            // Slight aerodynamic drag for organic glass deceleration
            val dragT = localT * (1f - 0.14f * min(localT, 1.2f))
            val dx = shard.vx * dragT * baseW
            val dy = (shard.vy * dragT + 0.5f * gravity * localT * localT) * baseH
            val angleDeg = Math.toDegrees((shard.spinZRadPerSec * localT).toDouble()).toFloat()
            val pivotX = centerX + shard.cx * baseW + dx
            val pivotY = centerY + shard.cy * baseH + dy

            // Simulated 3D foreshortening + specular glint flash when shard plane faces light
            val cosTx = cos(shard.tumbleXRadPerSec * localT)
            val cosTy = cos(shard.tumbleYRadPerSec * localT)
            val scaleX = (0.32f + 0.68f * abs(cosTy)).coerceIn(0.28f, 1.0f)
            val scaleY = (0.28f + 0.72f * abs(cosTx)).coerceIn(0.24f, 1.0f)
            val specularFlash = (abs(sin(shard.tumbleXRadPerSec * localT + shard.tumbleYRadPerSec * localT * 0.6f))).pow(5)

            nc.save()
            nc.translate(pivotX, pivotY)
            nc.rotate(angleDeg)
            nc.scale(scaleX, scaleY)

            shardPath.reset()
            val verts = shard.verticesNorm
            var i = 0
            while (i < verts.size) {
                val px = (verts[i] - shard.cx) * baseW
                val py = (verts[i + 1] - shard.cy) * baseH
                if (i == 0) shardPath.moveTo(px, py) else shardPath.lineTo(px, py)
                i += 2
            }
            shardPath.close()

            // Extruded glass edge bevel offset (gives tangible 3D thickness to shards)
            if (!shard.isMicroSplinter) {
                val bevelAlpha = (shardFade * 150f).toInt().coerceIn(0, 180)
                fillPaint.color = (bevelAlpha shl 24) or 0x0A101A
                nc.save()
                nc.translate(2.5f, 3.2f)
                nc.drawPath(shardPath, fillPaint)
                nc.restore()
            }

            // Translucent tinted crystal body
            val bodyAlpha = (shard.tintAlpha * shardFade * if (shard.isMicroSplinter) 165f else 205f)
                .toInt().coerceIn(0, 230)
            fillPaint.color = (bodyAlpha shl 24) or bodyRgb
            nc.drawPath(shardPath, fillPaint)

            // Dynamic refractive light glint flash as shard tumbles in 3D
            val sheenAlpha = ((shard.highlightBoost * 0.55f + specularFlash * 0.95f) * shardFade * 170f)
                .toInt().coerceIn(0, 225)
            fillPaint.color = (sheenAlpha shl 24) or sheenRgb
            nc.drawPath(shardPath, fillPaint)

            // Razor-sharp glass rim highlight
            val rimAlpha = ((0.70f + specularFlash * 0.30f) * shardFade * 245f).toInt().coerceIn(0, 255)
            strokePaint.color = (rimAlpha shl 24) or rimRgb
            strokePaint.strokeWidth = if (shard.isMicroSplinter) 1.5f else 2.2f
            nc.drawPath(shardPath, strokePaint)

            nc.restore()
        }
    }

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t.coerceIn(0f, 1f)
        return 1f - inv * inv * inv
    }

    private fun easeInOutCubic(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
    }
}
