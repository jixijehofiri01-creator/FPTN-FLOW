package com.example.core.ui.flags

import android.graphics.Canvas as AndroidCanvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * High-detail procedural Jolly Roger pirate flag renderer.
 *
 * Rendered ONCE into [FlagTextureRenderer]'s cached bitmaps (full-res hero cloth + pre-rounded
 * matrix thumbnails), delivering museum-grade visual realism at 0ms per-frame runtime cost:
 * - Weathered obsidian sailcloth with subtle diagonal canvas weave, stitched border hems, and
 *   dramatic radial lighting vignette.
 * - Anatomically sculpted crossed femur bones with dual-condyle joints, tapered shafts, 3D ivory
 *   shading gradients, and soft cast shadows onto the cloth.
 * - Anatomically sculpted Jolly Roger skull: shaded aged-ivory cranium, brow ridge, cheekbones,
 *   temporal hollows, battle-worn hairline skull crack, recessed orbital eye sockets with rim depth,
 *   pear-shaped nasal cavity with septum, and articulated maxilla/mandible jaw with individual teeth.
 */
object PirateFlagRenderer {

    private const val CLOTH_CENTER_COLOR = 0xFF1A1D24.toInt()
    private const val CLOTH_MID_COLOR = 0xFF0F1116.toInt()
    private const val CLOTH_EDGE_COLOR = 0xFF06070A.toInt()

    private const val BONE_HIGHLIGHT = 0xFFFBF8F0.toInt()
    private const val BONE_IVORY = 0xFFE8DFCD.toInt()
    private const val BONE_SHADE = 0xFFB8AA90.toInt()
    private const val BONE_DEEP_SHADE = 0xFF7A6E5A.toInt()
    private const val CAVITY_BLACK = 0xFF0A0B0F.toInt()
    private const val CAVITY_INNER_RIM = 0xFF2B2723.toInt()

    /**
     * Draws the realistic weathered pirate flag onto [canvas] spanning [w] x [h] pixels.
     */
    fun draw(canvas: AndroidCanvas, w: Float, h: Float) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val unit = minOf(w, h)

        drawWeatheredSailcloth(canvas, w, h, cx, cy, unit)

        val emblemRadius = h * 0.365f
        drawCrossedAnatomicalFemurs(canvas, cx, cy + emblemRadius * 0.14f, emblemRadius)
        drawAnatomicalSkull(canvas, cx, cy - emblemRadius * 0.08f, emblemRadius)
    }

    private fun drawWeatheredSailcloth(
        c: AndroidCanvas,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float,
        unit: Float,
    ) {
        // 1. Base radial-lit charcoal sailcloth
        val clothPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = CLOTH_MID_COLOR
            shader = RadialGradient(
                cx,
                cy * 0.92f,
                maxOf(w, h) * 0.68f,
                intArrayOf(CLOTH_CENTER_COLOR, CLOTH_MID_COLOR, CLOTH_EDGE_COLOR),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w, h, clothPaint)

        // 2. Subtle woven canvas crosshatch fibers (density scaled to bitmap size)
        val weaveStep = (unit / 42f).coerceIn(3f, 16f)
        val fiberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (weaveStep * 0.16f).coerceAtLeast(0.7f)
            color = 0x0CFFFFFF
        }
        var y = 0f
        while (y < h) {
            c.drawLine(0f, y, w, y, fiberPaint)
            y += weaveStep
        }
        fiberPaint.color = 0x08E5DEC9
        var x = 0f
        while (x < w) {
            c.drawLine(x, 0f, x, h, fiberPaint)
            x += weaveStep * 1.15f
        }

        // 3. Diagonal weathered cloth creases / subtle aged patina bands
        val creasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                w,
                h,
                intArrayOf(0x00000000, 0x10FFFFFF, 0x16000000, 0x0CFFFFFF, 0x22000000),
                floatArrayOf(0f, 0.28f, 0.52f, 0.74f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, w, h, creasePaint)

        // 4. Stitched nautical hem border near perimeter
        val inset = unit * 0.042f
        val stitchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (unit * 0.0045f).coerceAtLeast(1f)
            color = 0x22D6CCB8
            pathEffect = android.graphics.DashPathEffect(
                floatArrayOf(unit * 0.022f, unit * 0.014f),
                0f,
            )
        }
        c.drawRect(inset, inset, w - inset, h - inset, stitchPaint)
    }

    private fun drawCrossedAnatomicalFemurs(
        c: AndroidCanvas,
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        val halfLength = r * 1.24f
        val shaftHalfW = r * 0.105f

        // Soft cast drop shadow beneath crossed bones onto sailcloth
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x7A000000
        }
        for (angleDeg in floatArrayOf(27f, -27f)) {
            c.save()
            c.translate(cx, cy + r * 0.055f)
            c.rotate(angleDeg)
            drawSingleFemurShape(c, halfLength, shaftHalfW * 1.14f, shadowPaint, null, null)
            c.restore()
        }

        // Draw back femur (-27 deg), then subtle overlap shadow, then front femur (+27 deg)
        for ((index, angleDeg) in floatArrayOf(-27f, 27f).withIndex()) {
            c.save()
            c.translate(cx, cy)
            c.rotate(angleDeg)

            val boneGrad = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = BONE_IVORY
                shader = LinearGradient(
                    0f,
                    -shaftHalfW * 1.9f,
                    0f,
                    shaftHalfW * 1.9f,
                    intArrayOf(BONE_HIGHLIGHT, BONE_IVORY, BONE_SHADE, BONE_DEEP_SHADE),
                    floatArrayOf(0f, 0.36f, 0.78f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            val ridgeHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (r * 0.022f).coerceAtLeast(1.1f)
                strokeCap = Paint.Cap.ROUND
                color = 0x68FFFFFF
            }
            val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (r * 0.018f).coerceAtLeast(1f)
                color = 0x883A332A.toInt()
            }

            if (index == 1) {
                // Dark contact occlusion where upper femur crosses lower femur
                val contactShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = 0x55000000
                }
                c.drawOval(
                    RectF(-shaftHalfW * 2.2f, -shaftHalfW * 1.35f, shaftHalfW * 2.2f, shaftHalfW * 1.35f),
                    contactShadow,
                )
            }

            drawSingleFemurShape(c, halfLength, shaftHalfW, boneGrad, outlinePaint, ridgeHighlight)
            c.restore()
        }
    }

    private fun drawSingleFemurShape(
        c: AndroidCanvas,
        halfLen: Float,
        shaftHalfW: Float,
        fillPaint: Paint,
        outlinePaint: Paint?,
        highlightPaint: Paint?,
    ) {
        val neckW = shaftHalfW * 0.76f
        val endX = halfLen * 0.86f
        val knobR = shaftHalfW * 1.18f
        val knobOffsetY = shaftHalfW * 0.72f

        val bonePath = AndroidPath().apply {
            // Tapered anatomical femur shaft (narrower waist in middle, flaring toward epiphyses)
            moveTo(-endX, -shaftHalfW * 1.15f)
            quadTo(0f, -neckW, endX, -shaftHalfW * 1.15f)
            lineTo(endX, shaftHalfW * 1.15f)
            quadTo(0f, neckW, -endX, shaftHalfW * 1.15f)
            close()
        }
        c.drawPath(bonePath, fillPaint)

        // Sculpted dual-condyle knuckle joints at both ends of the femur
        for (dir in floatArrayOf(-1f, 1f)) {
            val kx = dir * (endX + knobR * 0.36f)
            c.drawCircle(kx, -knobOffsetY, knobR, fillPaint)
            c.drawCircle(kx, knobOffsetY, knobR * 0.96f, fillPaint)
            if (outlinePaint != null) {
                c.drawCircle(kx, -knobOffsetY, knobR, outlinePaint)
                c.drawCircle(kx, knobOffsetY, knobR * 0.96f, outlinePaint)
                // Inner condyle groove cleft
                c.drawLine(
                    kx + dir * knobR * 0.15f,
                    0f,
                    kx + dir * knobR * 0.85f,
                    0f,
                    outlinePaint,
                )
            }
        }
        if (outlinePaint != null) {
            c.drawPath(bonePath, outlinePaint)
        }
        if (highlightPaint != null) {
            c.drawLine(
                -endX * 0.80f,
                -neckW * 0.42f,
                endX * 0.80f,
                -neckW * 0.42f,
                highlightPaint,
            )
        }
    }

    private fun drawAnatomicalSkull(
        c: AndroidCanvas,
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        // 1. Deep contact shadow under skull over the crossed femurs
        val dropShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                cx,
                cy + r * 0.08f,
                r * 0.92f,
                intArrayOf(0xAA000000.toInt(), 0x44000000, 0x00000000),
                floatArrayOf(0.42f, 0.76f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(cx, cy + r * 0.08f, r * 0.92f, dropShadow)

        // 2. Sculpted upper cranium + flared cheekbones + narrowed maxilla silhouette
        val craniumPath = AndroidPath().apply {
            // Top center crown
            moveTo(cx, cy - r * 0.72f)
            // Right parietal dome curving down to temporal hollow
            cubicTo(
                cx + r * 0.56f, cy - r * 0.72f,
                cx + r * 0.72f, cy - r * 0.34f,
                cx + r * 0.64f, cy - r * 0.02f,
            )
            // Right zygomatic cheekbone flare
            cubicTo(
                cx + r * 0.70f, cy + r * 0.12f,
                cx + r * 0.61f, cy + r * 0.28f,
                cx + r * 0.43f, cy + r * 0.34f,
            )
            // Right maxilla tapering into jaw
            cubicTo(
                cx + r * 0.40f, cy + r * 0.50f,
                cx + r * 0.35f, cy + r * 0.66f,
                cx + r * 0.22f, cy + r * 0.73f,
            )
            // Chin base curve
            quadTo(cx, cy + r * 0.78f, cx - r * 0.22f, cy + r * 0.73f)
            // Left maxilla tapering upward
            cubicTo(
                cx - r * 0.35f, cy + r * 0.66f,
                cx - r * 0.40f, cy + r * 0.50f,
                cx - r * 0.43f, cy + r * 0.34f,
            )
            // Left zygomatic cheekbone flare
            cubicTo(
                cx - r * 0.61f, cy + r * 0.28f,
                cx - r * 0.70f, cy + r * 0.12f,
                cx - r * 0.64f, cy - r * 0.02f,
            )
            // Left parietal dome back to crown
            cubicTo(
                cx - r * 0.72f, cy - r * 0.34f,
                cx - r * 0.56f, cy - r * 0.72f,
                cx, cy - r * 0.72f,
            )
            close()
        }

        val skullBasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = BONE_IVORY
            shader = RadialGradient(
                cx - r * 0.14f,
                cy - r * 0.34f,
                r * 1.18f,
                intArrayOf(BONE_HIGHLIGHT, BONE_IVORY, BONE_SHADE, BONE_DEEP_SHADE),
                floatArrayOf(0f, 0.42f, 0.78f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawPath(craniumPath, skullBasePaint)

        // 3. Soft temporal & cheek contour shading (gives real 3D bone volume)
        val cheekShadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0x26594D3C
        }
        for (side in floatArrayOf(-1f, 1f)) {
            c.drawOval(
                RectF(
                    cx + side * r * 0.38f - r * 0.16f,
                    cy - r * 0.22f,
                    cx + side * r * 0.38f + r * 0.16f,
                    cy + r * 0.10f,
                ),
                cheekShadePaint,
            )
            // Sub-zygomatic hollow shadow under cheekbones
            c.drawOval(
                RectF(
                    cx + side * r * 0.36f - r * 0.13f,
                    cy + r * 0.22f,
                    cx + side * r * 0.36f + r * 0.13f,
                    cy + r * 0.42f,
                ),
                cheekShadePaint,
            )
        }

        // Specular forehead brow highlight
        val foreheadHighlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                cx - r * 0.08f,
                cy - r * 0.42f,
                r * 0.38f,
                intArrayOf(0x68FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawOval(
            RectF(cx - r * 0.42f, cy - r * 0.64f, cx + r * 0.34f, cy - r * 0.18f),
            foreheadHighlight,
        )

        // Subtle cranial suture & battle-worn hairline fracture on right forehead
        val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.014f).coerceAtLeast(0.9f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = 0x7A473D30
        }
        val crackPath = AndroidPath().apply {
            moveTo(cx + r * 0.16f, cy - r * 0.68f)
            lineTo(cx + r * 0.21f, cy - r * 0.54f)
            lineTo(cx + r * 0.15f, cy - r * 0.43f)
            lineTo(cx + r * 0.24f, cy - r * 0.34f)
            moveTo(cx + r * 0.15f, cy - r * 0.43f)
            lineTo(cx + r * 0.09f, cy - r * 0.36f)
        }
        c.drawPath(crackPath, crackPaint)

        // 4. Sculpted brow ridge shadow above orbits
        val browShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.055f).coerceAtLeast(2f)
            strokeCap = Paint.Cap.ROUND
            color = 0x36443A2C
        }
        val browPath = AndroidPath().apply {
            moveTo(cx - r * 0.45f, cy - r * 0.10f)
            quadTo(cx - r * 0.24f, cy - r * 0.22f, cx, cy - r * 0.11f)
            quadTo(cx + r * 0.24f, cy - r * 0.22f, cx + r * 0.45f, cy - r * 0.10f)
        }
        c.drawPath(browPath, browShadow)

        // 5. Recessed anatomical orbital eye sockets (asymmetrical ferocious tilt + rim bevel)
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.026f).coerceAtLeast(1.2f)
            color = CAVITY_INNER_RIM
        }
        for (side in floatArrayOf(-1f, 1f)) {
            val ex = cx + side * r * 0.245f
            val ey = cy + r * 0.03f
            val orbitPath = AndroidPath().apply {
                moveTo(ex - side * r * 0.16f, ey - r * 0.09f)
                cubicTo(
                    ex - side * r * 0.03f, ey - r * 0.19f,
                    ex + side * r * 0.19f, ey - r * 0.12f,
                    ex + side * r * 0.18f, ey + r * 0.02f,
                )
                cubicTo(
                    ex + side * r * 0.17f, ey + r * 0.16f,
                    ex - side * r * 0.05f, ey + r * 0.18f,
                    ex - side * r * 0.15f, ey + r * 0.08f,
                )
                cubicTo(
                    ex - side * r * 0.20f, ey + r * 0.02f,
                    ex - side * r * 0.20f, ey - r * 0.05f,
                    ex - side * r * 0.16f, ey - r * 0.09f,
                )
                close()
            }
            val socketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = CAVITY_BLACK
                shader = RadialGradient(
                    ex,
                    ey,
                    r * 0.22f,
                    intArrayOf(CAVITY_BLACK, 0xFF141317.toInt(), 0xFF28231E.toInt()),
                    floatArrayOf(0f, 0.72f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            c.drawPath(orbitPath, socketPaint)
            c.drawPath(orbitPath, rimPaint)
        }

        // 6. Inverted heart/pear-shaped nasal piriform aperture with central bony septum
        val noseTopY = cy + r * 0.13f
        val noseBotY = cy + r * 0.35f
        val nosePath = AndroidPath().apply {
            moveTo(cx, noseTopY)
            cubicTo(
                cx + r * 0.08f, noseTopY + r * 0.08f,
                cx + r * 0.135f, noseBotY - r * 0.02f,
                cx + r * 0.075f, noseBotY + r * 0.01f,
            )
            quadTo(cx + r * 0.02f, noseBotY + r * 0.02f, cx, noseBotY - r * 0.035f)
            quadTo(cx - r * 0.02f, noseBotY + r * 0.02f, cx - r * 0.075f, noseBotY + r * 0.01f)
            cubicTo(
                cx - r * 0.135f, noseBotY - r * 0.02f,
                cx - r * 0.08f, noseTopY + r * 0.08f,
                cx, noseTopY,
            )
            close()
        }
        val cavityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = CAVITY_BLACK
        }
        c.drawPath(nosePath, cavityPaint)
        val septumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.022f).coerceAtLeast(1f)
            color = BONE_SHADE
        }
        c.drawLine(cx, noseTopY + r * 0.04f, cx, noseBotY - r * 0.02f, septumPaint)

        // 7. Articulated grimacing teeth & maxilla/mandible line
        drawSculptedTeeth(c, cx, cy, r)

        // 8. Crisp dark bone contour rim so skull silhouette pops cleanly against the cloth & bones
        val skullOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.020f).coerceAtLeast(1.1f)
            color = 0x8A332C22.toInt()
        }
        c.drawPath(craniumPath, skullOutline)
    }

    private fun drawSculptedTeeth(
        c: AndroidCanvas,
        cx: Float,
        cy: Float,
        r: Float,
    ) {
        val mouthLeft = cx - r * 0.28f
        val mouthRight = cx + r * 0.28f
        val mouthMidY = cy + r * 0.54f
        val toothTopY = cy + r * 0.44f
        val toothBotY = cy + r * 0.65f

        // Dark mouth recess behind teeth
        val recessPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xD812100E.toInt()
        }
        c.drawRoundRect(
            RectF(mouthLeft, toothTopY, mouthRight, toothBotY),
            r * 0.06f,
            r * 0.06f,
            recessPaint,
        )

        // 6 upper + 6 lower individually contoured ivory teeth along a slight grin arc
        val teethCount = 6
        val slotW = (mouthRight - mouthLeft) / teethCount
        val toothFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = LinearGradient(
                0f,
                toothTopY,
                0f,
                toothBotY,
                intArrayOf(BONE_SHADE, BONE_HIGHLIGHT, BONE_IVORY, BONE_SHADE),
                floatArrayOf(0f, 0.35f, 0.68f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val toothBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.015f).coerceAtLeast(0.8f)
            color = CAVITY_BLACK
        }

        for (i in 0 until teethCount) {
            val tLeft = mouthLeft + i * slotW + slotW * 0.08f
            val tRight = mouthLeft + (i + 1) * slotW - slotW * 0.08f
            val centerFactor = 1f - kotlin.math.abs((i + 0.5f) / teethCount - 0.5f) * 2f
            val grinDip = centerFactor * r * 0.025f

            val upperRect = RectF(tLeft, toothTopY + grinDip * 0.3f, tRight, mouthMidY + grinDip)
            c.drawRoundRect(upperRect, r * 0.025f, r * 0.025f, toothFill)
            c.drawRoundRect(upperRect, r * 0.025f, r * 0.025f, toothBorder)

            val lowerRect = RectF(tLeft, mouthMidY + grinDip + r * 0.012f, tRight, toothBotY - grinDip * 0.2f)
            c.drawRoundRect(lowerRect, r * 0.025f, r * 0.025f, toothFill)
            c.drawRoundRect(lowerRect, r * 0.025f, r * 0.025f, toothBorder)
        }

        // Subtle gumline alveolar scallops above teeth
        val gumShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (r * 0.018f).coerceAtLeast(1f)
            color = 0x554E4234
        }
        c.drawLine(mouthLeft, toothTopY - r * 0.015f, mouthRight, toothTopY - r * 0.015f, gumShadow)
    }
}
