package com.example.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

private val ShieldEase = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

private class SampledShieldGeometry(
    val sizeKey: Size,
    val shieldPath: Path,
    val ringPoints: List<Offset>,
    val shieldW: Float,
    val shieldH: Float,
    val topY: Float,
    val bottomY: Float,
    val cx: Float,
    val cy: Float,
)

private fun buildSampledShieldGeometry(canvasSize: Size, sampleCount: Int = 160): SampledShieldGeometry {
    val w = canvasSize.width
    val h = canvasSize.height
    val cx = w * 0.5f
    val cy = h * 0.5f
    val shieldW = canvasSize.minDimension * 0.48f
    val shieldH = canvasSize.minDimension * 0.56f
    val topY = cy - shieldH * 0.50f
    val shoulderY = topY + shieldH * 0.16f
    val midY = topY + shieldH * 0.54f
    val bottomY = topY + shieldH

    val shieldPath = Path().apply {
        moveTo(cx, topY)
        lineTo(cx + shieldW * 0.5f, shoulderY)
        lineTo(cx + shieldW * 0.5f, midY)
        cubicTo(
            cx + shieldW * 0.5f,
            topY + shieldH * 0.77f,
            cx + shieldW * 0.27f,
            topY + shieldH * 0.92f,
            cx,
            bottomY,
        )
        cubicTo(
            cx - shieldW * 0.27f,
            topY + shieldH * 0.92f,
            cx - shieldW * 0.5f,
            topY + shieldH * 0.77f,
            cx - shieldW * 0.5f,
            midY,
        )
        lineTo(cx - shieldW * 0.5f, shoulderY)
        close()
    }

    val measure = PathMeasure().apply { setPath(shieldPath, forceClosed = true) }
    val totalLen = measure.length
    val points = if (totalLen > 1f) {
        List(sampleCount) { i ->
            val dist = (i.toFloat() / sampleCount.toFloat()) * totalLen
            measure.getPosition(dist)
        }
    } else {
        emptyList()
    }

    return SampledShieldGeometry(
        sizeKey = canvasSize,
        shieldPath = shieldPath,
        ringPoints = points,
        shieldW = shieldW,
        shieldH = shieldH,
        topY = topY,
        bottomY = bottomY,
        cx = cx,
        cy = cy,
    )
}

/**
 * Minimalist translucent frosted-glass SVG Shield hero with silky 120fps animations:
 * - Translucent frosted glass interior (alpha 0.16..0.28) with subtle specular sheen & hairline inner edge
 *   so the waving flag & atmospheric aurora shine gracefully through the shield.
 * - Pre-sampled 160-point closed shield perimeter for seamless energy comets with zero apex stutter.
 * - Independent cross-fade opacities (`errorGlyphAlpha`, `switchArrowsAlpha`, `reconnectingArcAlpha`,
 *   `chevronsAlpha`) so transitions between ALL 6 states dissolve seamlessly without abrupt cuts.
 * - Sleek opposing directional arrows during `SwitchingServer` (top gliding →, bottom gliding ← with
 *   subtle fading kinetic trails — no distracting floating circle!).
 */
@Composable
fun MinimalistShieldHero(
    connectionState: VpnConnectionState,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
) {
    val isConnected = connectionState is VpnConnectionState.Connected
    val isSwitching = connectionState is VpnConnectionState.SwitchingServer
    val isReconnecting = connectionState is VpnConnectionState.Reconnecting
    val isConnecting = connectionState is VpnConnectionState.Connecting
    val isDisconnecting = connectionState is VpnConnectionState.Disconnecting
    val isBusy = isConnecting || isSwitching || isReconnecting || isDisconnecting
    val isError = connectionState is VpnConnectionState.Error

    val isDarkSurface = MaterialTheme.colorScheme.surface.red * 0.299f +
        MaterialTheme.colorScheme.surface.green * 0.587f +
        MaterialTheme.colorScheme.surface.blue * 0.114f < 0.45f
    val amberPrimary = if (isDarkSurface) Color(0xFFFFC145) else Color(0xFFD48800)
    val amberGlass = if (isDarkSurface) Color(0xFFFFC145).copy(alpha = 0.20f) else Color(0xFFFFD670).copy(alpha = 0.28f)
    val amberContent = if (isDarkSurface) Color(0xFFFFDF8E) else Color(0xFF8C5600)
    val disconnectingPrimary = if (isDarkSurface) Color(0xFFFF8A65) else Color(0xFFD84315)
    val disconnectingGlass = if (isDarkSurface) Color(0xFFFF8A65).copy(alpha = 0.19f) else Color(0xFFFFAB91).copy(alpha = 0.28f)
    val disconnectingContent = if (isDarkSurface) Color(0xFFFFBCA6) else Color(0xFFBF360C)

    val primaryColorState = animateColorAsState(
        targetValue = when {
            isConnected -> MaterialTheme.colorScheme.tertiary
            isError -> MaterialTheme.colorScheme.error
            isSwitching || isReconnecting -> amberPrimary
            isConnecting -> MaterialTheme.colorScheme.primary
            isDisconnecting -> disconnectingPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        },
        animationSpec = tween(durationMillis = 420, easing = ShieldEase),
        label = "shieldPrimary",
    )

    // Refined translucent frosted-glass interior tint
    val glassFillColorState = animateColorAsState(
        targetValue = when {
            isConnected -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (isDarkSurface) 0.26f else 0.34f)
            isError -> MaterialTheme.colorScheme.error.copy(alpha = if (isDarkSurface) 0.18f else 0.16f)
            isSwitching || isReconnecting -> amberGlass
            isConnecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            isDisconnecting -> disconnectingGlass
            else -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (isDarkSurface) 0.22f else 0.30f)
        },
        animationSpec = tween(durationMillis = 420, easing = ShieldEase),
        label = "shieldGlassFill",
    )

    val glyphColorState = animateColorAsState(
        targetValue = when {
            isConnected -> if (isDarkSurface) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.tertiary
            isError -> MaterialTheme.colorScheme.error
            isSwitching || isReconnecting -> amberContent
            isConnecting -> MaterialTheme.colorScheme.primary
            isDisconnecting -> disconnectingContent
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        },
        animationSpec = tween(durationMillis = 400, easing = ShieldEase),
        label = "glyphColor",
    )

    // Independent cross-fade opacities avoid abrupt visual cuts between connection states.
    val busyCometAlphaState = animateFloatAsState(
        targetValue = if (isBusy) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "busyCometAlpha",
    )
    val errorGlyphAlphaState = animateFloatAsState(
        targetValue = if (isError) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "errorGlyphAlpha",
    )
    val switchArrowsAlphaState = animateFloatAsState(
        targetValue = if (isSwitching) 1f else 0f,
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "switchArrowsAlpha",
    )
    val reconnectingArcAlphaState = animateFloatAsState(
        targetValue = if (isReconnecting) 1f else 0f,
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "reconnectingArcAlpha",
    )
    val disconnectingAlphaState = animateFloatAsState(
        targetValue = if (isDisconnecting) 1f else 0f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "disconnectingAlpha",
    )
    val chevronsAlphaState = animateFloatAsState(
        targetValue = if (!isError && !isSwitching && !isReconnecting && !isDisconnecting) 1f else 0f,
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "chevronsAlpha",
    )

    val activationProgress = remember { Animatable(if (isConnected) 1f else 0f) }
    val shieldPopScale = remember { Animatable(1f) }
    val shieldEchoProgress = remember { Animatable(1f) }
    val errorShakeX = remember { Animatable(0f) }
    val statusFlashProgress = remember { Animatable(1f) }

    val statusCategoryKey = when (connectionState) {
        is VpnConnectionState.Disconnected -> "disconnected"
        is VpnConnectionState.Connecting -> "connecting"
        is VpnConnectionState.SwitchingServer -> "switching_${connectionState.toServer.id}"
        is VpnConnectionState.Reconnecting -> "reconnecting"
        is VpnConnectionState.Connected -> "connected_${connectionState.server.id}"
        is VpnConnectionState.Disconnecting -> "disconnecting"
        is VpnConnectionState.Error -> "error"
    }

    LaunchedEffect(statusCategoryKey) {
        launch {
            statusFlashProgress.snapTo(0f)
            statusFlashProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 560, easing = ShieldEase),
            )
        }
        when {
            isConnected -> {
                launch {
                    activationProgress.snapTo(0f)
                    activationProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 820, easing = ShieldEase),
                    )
                }
                launch {
                    shieldPopScale.snapTo(0.92f)
                    shieldPopScale.animateTo(
                        targetValue = 1.055f,
                        animationSpec = tween(durationMillis = 210, easing = FastOutSlowInEasing),
                    )
                    shieldPopScale.animateTo(
                        targetValue = 1.0f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                }
                launch {
                    shieldEchoProgress.snapTo(0f)
                    shieldEchoProgress.animateTo(
                        targetValue = 1f,
                        animationSpec = tween(durationMillis = 900, easing = ShieldEase),
                    )
                }
            }
            isError -> {
                launch {
                    activationProgress.animateTo(0f, tween(320, easing = ShieldEase))
                }
                launch {
                    // Subtle minimalist double micro-shake
                    errorShakeX.snapTo(0f)
                    val offsets = listOf(-6.5f, 6.5f, -3.5f, 3.5f, 0f)
                    for (dx in offsets) {
                        errorShakeX.animateTo(dx, tween(durationMillis = 52, easing = FastOutSlowInEasing))
                    }
                }
            }
            else -> {
                launch {
                    activationProgress.animateTo(0f, tween(durationMillis = 380, easing = ShieldEase))
                }
                launch {
                    shieldPopScale.snapTo(0.965f)
                    shieldPopScale.animateTo(
                        targetValue = 1.025f,
                        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                    )
                    shieldPopScale.animateTo(1f, tween(280, easing = ShieldEase))
                }
            }
        }
    }

    // Gate infinite animations so Disconnected idle state consumes 0% CPU/GPU and causes 0 recompositions
    val needsInfiniteAnim = isConnected || isBusy || isError
    val infinite = if (needsInfiniteAnim) rememberInfiniteTransition(label = "shieldVectorAnim") else null
    val breathPhaseState = infinite?.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathPhase",
    )
    val tracePhaseState = if (isBusy && infinite != null) {
        infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "tracePhase",
        )
    } else {
        null
    }
    val sheenPhaseState = if (isConnected && infinite != null) {
        infinite.animateFloat(
            initialValue = -0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "sheenPhase",
        )
    } else {
        null
    }

    val chevronMeasure = remember { PathMeasure() }
    val geometryCache = remember { arrayOfNulls<SampledShieldGeometry>(1) }
    val sweepRightPath = remember { Path() }
    val sweepLeftPath = remember { Path() }
    val arrowTopPath = remember { Path() }
    val arrowBotPath = remember { Path() }
    val chevronFullPath = remember { Path() }
    val chevronPartialPath = remember { Path() }

    Box(
        modifier = modifier
            .size(size)
            .testTag("home_minimalist_shield_hero"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val primaryColor = primaryColorState.value
            val glassFillColor = glassFillColorState.value
            val glyphColor = glyphColorState.value
            val busyCometAlpha = busyCometAlphaState.value
            val errorGlyphAlpha = errorGlyphAlphaState.value
            val switchArrowsAlpha = switchArrowsAlphaState.value
            val reconnectingArcAlpha = reconnectingArcAlphaState.value
            val disconnectingAlpha = disconnectingAlphaState.value
            val chevronsAlpha = chevronsAlphaState.value
            val breathPhase = breathPhaseState?.value ?: 0f
            val tracePhase = tracePhaseState?.value ?: 0f
            val sheenPhase = sheenPhaseState?.value ?: -0.6f

            val geom = geometryCache[0]?.takeIf { it.sizeKey == this.size }
                ?: buildSampledShieldGeometry(this.size).also { geometryCache[0] = it }

            val cx = geom.cx
            val cy = geom.cy
            val shieldW = geom.shieldW
            val shieldH = geom.shieldH
            val topY = geom.topY
            val bottomY = geom.bottomY
            val shieldPath = geom.shieldPath
            val ringPoints = geom.ringPoints
            val outerR = this.size.minDimension * 0.46f

            // Subtle ambient translucent aura halo
            val haloActive = isConnected || isSwitching || isReconnecting || isDisconnecting || isError
            val haloExpand = if (haloActive) (breathPhase * 0.042f) else 0f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (haloActive) 0.14f else 0.05f),
                        primaryColor.copy(alpha = if (haloActive) 0.05f else 0.02f),
                        Color.Transparent,
                    ),
                    center = Offset(cx, cy),
                    radius = outerR * (1.06f + haloExpand),
                ),
                radius = outerR * (1.06f + haloExpand),
                center = Offset(cx, cy),
            )
            // Omit thin circular outline ring during Error state (uses cracked crimson glass backdrop instead)
            if (!isError) {
                drawCircle(
                    color = primaryColor.copy(alpha = if (haloActive) 0.22f else 0.11f),
                    radius = outerR * (0.93f + haloExpand * 0.35f),
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }

            // Soft status-change radial bloom flash
            val bloom = statusFlashProgress.value
            if (bloom in 0.001f..0.995f) {
                val bloomAlpha = (1f - bloom) * (1f - bloom) * 0.36f
                val bloomRadius = outerR * (0.76f + bloom * 0.56f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = bloomAlpha),
                            primaryColor.copy(alpha = bloomAlpha * 0.32f),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = bloomRadius,
                    ),
                    radius = bloomRadius,
                    center = Offset(cx, cy),
                )
            }

            // 1. Expanding SVG Shield Silhouette Echo wave on connect lock-in
            val echo = shieldEchoProgress.value
            if (isConnected && echo in 0.001f..0.999f) {
                val echoScale = 1f + echo * 0.42f
                val echoAlpha = (1f - echo) * 0.50f
                scale(scale = echoScale, pivot = Offset(cx, cy)) {
                    drawPath(
                        path = shieldPath,
                        color = primaryColor.copy(alpha = echoAlpha),
                        style = Stroke(
                            width = (3.0f * (1f - echo * 0.6f)).dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                }
            }

            // 2. Transform the actual translucent glass SVG Shield
            val busyBreathScale = 1f + (busyCometAlpha * (breathPhase - 0.5f) * 0.04f)
            val effectiveScale = shieldPopScale.value * busyBreathScale
            val shakePx = errorShakeX.value.dp.toPx()

            translate(left = shakePx, top = 0f) {
                scale(scale = effectiveScale, pivot = Offset(cx, cy)) {
                    // Frosted translucent glass interior gradient
                    clipPath(shieldPath) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isDarkSurface) 0.14f else 0.34f),
                                    glassFillColor,
                                    glassFillColor.copy(alpha = (glassFillColor.alpha * 0.72f).coerceIn(0.06f, 0.32f)),
                                ),
                                startY = topY,
                                endY = bottomY,
                            ),
                            topLeft = Offset(cx - shieldW * 0.6f, topY),
                            size = Size(shieldW * 1.2f, shieldH),
                        )

                        // Liquid translucent energy fill rising inside shield on connect
                        val act = activationProgress.value
                        if (act > 0.005f) {
                            val fillTop = bottomY - (shieldH * act)
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        primaryColor.copy(alpha = 0.22f * act),
                                        glassFillColor.copy(alpha = (glassFillColor.alpha * 1.15f).coerceAtMost(0.36f)),
                                    ),
                                    startY = fillTop,
                                    endY = bottomY,
                                ),
                                topLeft = Offset(cx - shieldW * 0.6f, fillTop),
                                size = Size(shieldW * 1.2f, bottomY - fillTop + 2f),
                            )

                            // Diagonal glass sheen sweeping gently across connected shield face
                            val sheenX = (cx - shieldW * 0.8f) + (shieldW * 1.6f * sheenPhase)
                            drawLine(
                                brush = Brush.linearGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.18f * act),
                                        Color.Transparent,
                                    ),
                                    start = Offset(sheenX - shieldW * 0.25f, topY),
                                    end = Offset(sheenX + shieldW * 0.25f, bottomY),
                                ),
                                start = Offset(sheenX - shieldW * 0.15f, topY - 12f),
                                end = Offset(sheenX + shieldW * 0.15f, bottomY + 12f),
                                strokeWidth = shieldW * 0.24f,
                            )
                        }

                        // Subtle hairline fracture lines inside frosted shield glass when Error is active
                        if (errorGlyphAlpha > 0.01f) {
                            val crackAlpha = errorGlyphAlpha * 0.44f
                            val crackStroke = 1.15.dp.toPx()
                            val crackColor = Color.White.copy(alpha = crackAlpha)
                            val fx = cx + shieldW * 0.16f
                            val fy = cy - shieldH * 0.18f
                            drawLine(crackColor, Offset(fx, fy), Offset(cx + shieldW * 0.44f, cy - shieldH * 0.34f), crackStroke, StrokeCap.Round)
                            drawLine(crackColor, Offset(fx, fy), Offset(cx - shieldW * 0.08f, cy - shieldH * 0.44f), crackStroke * 0.85f, StrokeCap.Round)
                            drawLine(crackColor, Offset(fx, fy), Offset(cx + shieldW * 0.36f, cy + shieldH * 0.04f), crackStroke * 0.8f, StrokeCap.Round)
                            drawLine(crackColor, Offset(cx - shieldW * 0.22f, cy + shieldH * 0.26f), Offset(cx - shieldW * 0.44f, cy + shieldH * 0.10f), crackStroke * 0.85f, StrokeCap.Round)
                        }

                        // Subtle upper glass specular highlight
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isDarkSurface) 0.12f else 0.24f),
                                    Color.Transparent,
                                ),
                                startY = topY,
                                endY = topY + shieldH * 0.42f,
                            ),
                            topLeft = Offset(cx - shieldW * 0.6f, topY),
                            size = Size(shieldW * 1.2f, shieldH * 0.42f),
                        )
                    }

                    // Hairline inner specular bevel
                    scale(scale = 0.945f, pivot = Offset(cx, cy)) {
                        drawPath(
                            path = shieldPath,
                            color = Color.White.copy(alpha = if (isDarkSurface) 0.18f else 0.36f),
                            style = Stroke(
                                width = 1.0.dp.toPx(),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            ),
                        )
                    }

                    // Crisp SVG shield outline
                    val act = activationProgress.value
                    val baseOutlineAlpha = 0.94f - busyCometAlpha * 0.62f
                    drawPath(
                        path = shieldPath,
                        color = primaryColor.copy(alpha = baseOutlineAlpha.coerceIn(0.28f, 0.94f)),
                        style = Stroke(
                            width = (2.8f + act * 0.7f).dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )

                    // 3. Seamless Pre-Sampled Closed-Ring Energy Comets (zero apex stutter)
                    val n = ringPoints.size
                    if (n >= 16) {
                        if (busyCometAlpha > 0.01f) {
                            val cometSteps = 34
                            val headFloat = tracePhase * n
                            val headIdx = headFloat.toInt() % n
                            val maxStroke = 4.0.dp.toPx()

                            for (offsetHalf in intArrayOf(0, n / 2)) {
                                for (step in 0 until cometSteps) {
                                    val t = (step + 1).toFloat() / cometSteps.toFloat()
                                    val idxA = (headIdx + offsetHalf - cometSteps + step + n * 2) % n
                                    val idxB = (idxA + 1) % n
                                    val segAlpha = (t * t) * busyCometAlpha
                                    val segWidth = maxStroke * (0.34f + 0.66f * t)
                                    drawLine(
                                        color = primaryColor.copy(alpha = segAlpha),
                                        start = ringPoints[idxA],
                                        end = ringPoints[idxB],
                                        strokeWidth = segWidth,
                                        cap = StrokeCap.Round,
                                    )
                                }
                                val sparkIdx = (headIdx + offsetHalf) % n
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.75f * busyCometAlpha),
                                    radius = 2.4.dp.toPx(),
                                    center = ringPoints[sparkIdx],
                                )
                            }
                        }

                        // 4. Symmetric Dual-Sweep Perimeter Lock-In on activation
                        if (isConnected && act in 0.01f..0.995f) {
                            val halfCount = n / 2
                            val sweepSteps = (halfCount * act).toInt().coerceAtLeast(1)
                            val sweepAlpha = ((1f - act) * 1.15f).coerceIn(0f, 0.88f)
                            val sweepWidth = 4.2.dp.toPx()

                            sweepRightPath.reset()
                            sweepRightPath.moveTo(ringPoints[0].x, ringPoints[0].y)
                            for (i in 1..sweepSteps.coerceAtMost(halfCount)) {
                                val pt = ringPoints[i % n]
                                sweepRightPath.lineTo(pt.x, pt.y)
                            }
                            sweepLeftPath.reset()
                            sweepLeftPath.moveTo(ringPoints[0].x, ringPoints[0].y)
                            for (i in 1..sweepSteps.coerceAtMost(halfCount)) {
                                val pt = ringPoints[(n - i) % n]
                                sweepLeftPath.lineTo(pt.x, pt.y)
                            }
                            val sweepStyle = Stroke(
                                width = sweepWidth,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                            )
                            drawPath(path = sweepRightPath, color = Color.White.copy(alpha = sweepAlpha), style = sweepStyle)
                            drawPath(path = sweepLeftPath, color = Color.White.copy(alpha = sweepAlpha), style = sweepStyle)
                        }
                    }

                    // 5. Inner SVG Vector Motifs — ALL smoothly cross-faded with independent alphas!

                    // 5A. Minimalist Error Exclamation (pulsing cleanly)
                    if (errorGlyphAlpha > 0.01f) {
                        val pulse = 0.80f + 0.20f * breathPhase
                        val a = errorGlyphAlpha * pulse
                        val strokeW = 3.6.dp.toPx()
                        drawLine(
                            color = glyphColor.copy(alpha = a),
                            start = Offset(cx, cy - shieldH * 0.16f),
                            end = Offset(cx, cy + shieldH * 0.07f),
                            strokeWidth = strokeW,
                            cap = StrokeCap.Round,
                        )
                        drawCircle(
                            color = glyphColor.copy(alpha = a),
                            radius = 2.6.dp.toPx(),
                            center = Offset(cx, cy + shieldH * 0.19f),
                        )
                    }

                    // 5B. Sleek Opposing Server-Switch Directional Arrows (NO floating circle!)
                    // Top arrow glides smoothly right (→) with fading kinetic trail;
                    // Bottom arrow glides smoothly left (←) with fading kinetic trail.
                    if (switchArrowsAlpha > 0.01f) {
                        clipPath(shieldPath) {
                            val arrowW = shieldW * 0.36f
                            val headLen = shieldW * 0.125f
                            val strokeW = 3.2.dp.toPx()
                            val swingPx = sin(tracePhase * PI * 2.0).toFloat() * shieldW * 0.11f
                            val trailLen = shieldW * 0.22f

                            val topYArrow = cy - shieldH * 0.105f
                            val botYArrow = cy + shieldH * 0.105f

                            // Top arrow (→) + fading kinetic trail behind tail
                            val topCx = cx + swingPx
                            val topLeft = topCx - arrowW * 0.5f
                            val topRight = topCx + arrowW * 0.5f
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        glyphColor.copy(alpha = 0.42f * switchArrowsAlpha),
                                    ),
                                    startX = topLeft - trailLen,
                                    endX = topLeft,
                                ),
                                start = Offset(topLeft - trailLen, topYArrow),
                                end = Offset(topLeft, topYArrow),
                                strokeWidth = strokeW * 0.72f,
                                cap = StrokeCap.Round,
                            )
                            arrowTopPath.reset()
                            arrowTopPath.moveTo(topLeft, topYArrow)
                            arrowTopPath.lineTo(topRight, topYArrow)
                            arrowTopPath.moveTo(topRight - headLen, topYArrow - headLen * 0.76f)
                            arrowTopPath.lineTo(topRight, topYArrow)
                            arrowTopPath.lineTo(topRight - headLen, topYArrow + headLen * 0.76f)
                            drawPath(
                                path = arrowTopPath,
                                color = glyphColor.copy(alpha = 0.96f * switchArrowsAlpha),
                                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )

                            // Bottom arrow (←) + fading kinetic trail behind tail
                            val botCx = cx - swingPx
                            val botLeft = botCx - arrowW * 0.5f
                            val botRight = botCx + arrowW * 0.5f
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        glyphColor.copy(alpha = 0.38f * switchArrowsAlpha),
                                        Color.Transparent,
                                    ),
                                    startX = botRight,
                                    endX = botRight + trailLen,
                                ),
                                start = Offset(botRight, botYArrow),
                                end = Offset(botRight + trailLen, botYArrow),
                                strokeWidth = strokeW * 0.72f,
                                cap = StrokeCap.Round,
                            )
                            arrowBotPath.reset()
                            arrowBotPath.moveTo(botRight, botYArrow)
                            arrowBotPath.lineTo(botLeft, botYArrow)
                            arrowBotPath.moveTo(botLeft + headLen, botYArrow - headLen * 0.76f)
                            arrowBotPath.lineTo(botLeft, botYArrow)
                            arrowBotPath.lineTo(botLeft + headLen, botYArrow + headLen * 0.76f)
                            drawPath(
                                path = arrowBotPath,
                                color = glyphColor.copy(alpha = 0.86f * switchArrowsAlpha),
                                style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                    }

                    // 5C. Minimalist Rotating Dual-Arc Sync Motif during Reconnecting
                    if (reconnectingArcAlpha > 0.01f) {
                        val arcR = shieldW * 0.24f
                        val arcTopLeft = Offset(cx - arcR, cy - arcR)
                        val arcSize = Size(arcR * 2f, arcR * 2f)
                        val baseAngle = tracePhase * 360f
                        val arcStroke = Stroke(width = 3.0.dp.toPx(), cap = StrokeCap.Round)
                        drawArc(
                            color = glyphColor.copy(alpha = 0.92f * reconnectingArcAlpha),
                            startAngle = baseAngle,
                            sweepAngle = 115f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = arcStroke,
                        )
                        drawArc(
                            color = glyphColor.copy(alpha = 0.56f * reconnectingArcAlpha),
                            startAngle = baseAngle + 180f,
                            sweepAngle = 115f,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = arcStroke,
                        )
                    }

                    // 5C-2. Minimalist Power-Down Contracting Arc + Vertical Power Line during Disconnecting
                    if (disconnectingAlpha > 0.01f) {
                        val contractScale = 1.0f - (breathPhase * 0.14f)
                        val pwrR = shieldW * 0.22f * contractScale
                        val pwrTopLeft = Offset(cx - pwrR, cy - pwrR)
                        val pwrSize = Size(pwrR * 2f, pwrR * 2f)
                        val pwrStroke = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                        val sweep = 240f * (0.72f + 0.28f * (1f - breathPhase))
                        val startAngle = -90f + (360f - sweep) * 0.5f
                        drawArc(
                            color = glyphColor.copy(alpha = 0.94f * disconnectingAlpha),
                            startAngle = startAngle,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = pwrTopLeft,
                            size = pwrSize,
                            style = pwrStroke,
                        )
                        drawLine(
                            color = glyphColor.copy(alpha = 0.96f * disconnectingAlpha),
                            start = Offset(cx, cy - pwrR * 1.15f),
                            end = Offset(cx, cy - pwrR * 0.05f),
                            strokeWidth = 3.2.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                        // Soft inward-shrinking echo ring showing unmistakable tunnel shutdown
                        val inwardPhase = 1f - tracePhase
                        val echoR = shieldW * (0.14f + inwardPhase * 0.24f)
                        drawCircle(
                            color = glyphColor.copy(alpha = (1f - inwardPhase) * 0.42f * disconnectingAlpha),
                            radius = echoR,
                            center = Offset(cx, cy),
                            style = Stroke(width = 1.6.dp.toPx()),
                        )
                    }

                    // 5D. Dual Minimalist Chevrons (Disconnected / Connecting / Connected)
                    if (chevronsAlpha > 0.01f) {
                        val chevronLift = when {
                            isConnected -> (breathPhase - 0.5f) * 3.2.dp.toPx()
                            isBusy -> (breathPhase - 0.5f) * 4.0.dp.toPx()
                            else -> 0f
                        }
                        val chevronHalfW = shieldW * 0.25f
                        val chevronHeight = shieldH * 0.11f
                        val chevronStroke = 3.2.dp.toPx()

                        fun drawAnimatedChevron(centerY: Float, drawFrac: Float, alpha: Float) {
                            val effectiveAlpha = alpha * chevronsAlpha
                            if (drawFrac <= 0.01f || effectiveAlpha <= 0.01f) return
                            chevronFullPath.reset()
                            chevronFullPath.moveTo(cx - chevronHalfW, centerY + chevronHeight * 0.5f)
                            chevronFullPath.lineTo(cx, centerY - chevronHeight * 0.5f)
                            chevronFullPath.lineTo(cx + chevronHalfW, centerY + chevronHeight * 0.5f)
                            chevronMeasure.setPath(chevronFullPath, forceClosed = false)
                            chevronPartialPath.reset()
                            chevronMeasure.getSegment(
                                startDistance = 0f,
                                stopDistance = chevronMeasure.length * drawFrac.coerceIn(0f, 1f),
                                destination = chevronPartialPath,
                                startWithMoveTo = true,
                            )
                            drawPath(
                                path = chevronPartialPath,
                                color = glyphColor.copy(alpha = effectiveAlpha),
                                style = Stroke(
                                    width = chevronStroke,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round,
                                ),
                            )
                        }

                        val lowerDrawFrac = if (isConnected) ((act - 0.12f) / 0.48f).coerceIn(0f, 1f) else 1f
                        val upperDrawFrac = if (isConnected) ((act - 0.30f) / 0.48f).coerceIn(0f, 1f) else 1f

                        drawAnimatedChevron(
                            centerY = cy - shieldH * 0.09f - chevronLift * 0.7f,
                            drawFrac = upperDrawFrac,
                            alpha = if (isConnected) 0.96f else 0.74f,
                        )
                        drawAnimatedChevron(
                            centerY = cy + shieldH * 0.10f - chevronLift * 0.35f,
                            drawFrac = lowerDrawFrac,
                            alpha = if (isConnected) 0.76f else 0.46f,
                        )
                    }
                }
            }
        }
    }
}
