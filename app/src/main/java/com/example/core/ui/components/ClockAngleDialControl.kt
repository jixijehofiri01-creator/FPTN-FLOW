package com.example.core.ui.components

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.haptics.HapticEngine
import com.example.core.ui.localization.tr
import com.example.core.ui.theme.JetBrainsMonoFamily
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Interactive 360° clock-face angle dial for setting arbitrary flag tilt or 2D matrix travel angle.
 * Convention: 0° = 12 o'clock (↑), 90° = 3 o'clock (→), 180° = 6 o'clock (↓), 270° = 9 o'clock (←).
 * Supports continuous finger dragging, direct tap, optional 15° tick snapping with watch-crown haptics,
 * live numeric degree readout right next to the dial, and quick fine-step +/-/reset controls.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClockAngleDialControl(
    angleDegrees: Float,
    snapToTicks: Boolean,
    enabled: Boolean,
    hapticEngine: HapticEngine,
    subtitleForAngle: (Int) -> String,
    onAngleChange: (Float) -> Unit,
    onToggleSnapToTicks: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    resetAngle: Float = 0f,
    snapStepDegrees: Int = 15,
    testTagPrefix: String = "flag_clock_dial",
) {
    val view = LocalView.current
    val externalAngle = normalizeClockAngle(angleDegrees)
    var draftAngle by remember { mutableFloatStateOf(externalAngle) }
    var isDragging by remember { mutableStateOf(false) }

    LaunchedEffect(externalAngle, isDragging) {
        if (!isDragging) {
            draftAngle = externalAngle
        }
    }

    val normalizedAngle = normalizeClockAngle(draftAngle)
    val displayDeg = normalizedAngle.roundToInt() % 360

    val latestSnap by rememberUpdatedState(snapToTicks)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestOnAngleChange by rememberUpdatedState(onAngleChange)
    var lastHapticDeg by remember { mutableIntStateOf(displayDeg) }

    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainerHighest
    val outlineColor = MaterialTheme.colorScheme.outlineVariant
    val primaryColor = MaterialTheme.colorScheme.primary
    val onPrimaryColor = MaterialTheme.colorScheme.onPrimary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
    }

    fun applyTouchPoint(touch: Offset, width: Float, height: Float, commitImmediately: Boolean) {
        if (!latestEnabled) return
        val cx = width * 0.5f
        val cy = height * 0.5f
        val dx = touch.x - cx
        val dy = touch.y - cy
        if (dx * dx + dy * dy < 16f) return
        // Clock convention: 0° = up (negative Y), clockwise positive
        val rawRad = atan2(dx.toDouble(), -dy.toDouble())
        var deg = Math.toDegrees(rawRad).toFloat()
        if (deg < 0f) deg += 360f
        val finalDeg = if (latestSnap) {
            ((deg / snapStepDegrees).roundToInt() * snapStepDegrees).toFloat() % 360f
        } else {
            deg.roundToInt().toFloat() % 360f
        }
        val intDeg = finalDeg.roundToInt() % 360
        if (intDeg != lastHapticDeg) {
            if (latestSnap || intDeg % 5 == 0) {
                hapticEngine.performSegmentTick(view)
            }
            lastHapticDeg = intDeg
        }
        draftAngle = finalDeg
        if (commitImmediately) {
            latestOnAngleChange(finalDeg)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Left: Tactile 360° Clock-Face Dial
        Box(
            modifier = Modifier
                .size(144.dp)
                .testTag("${testTagPrefix}_canvas")
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        applyTouchPoint(
                            touch = pos,
                            width = size.width.toFloat(),
                            height = size.height.toFloat(),
                            commitImmediately = true,
                        )
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            isDragging = true
                            applyTouchPoint(
                                touch = pos,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                                commitImmediately = false,
                            )
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            applyTouchPoint(
                                touch = change.position,
                                width = size.width.toFloat(),
                                height = size.height.toFloat(),
                                commitImmediately = false,
                            )
                        },
                        onDragEnd = {
                            val committed = normalizeClockAngle(draftAngle)
                            isDragging = false
                            latestOnAngleChange(committed)
                        },
                        onDragCancel = {
                            val committed = normalizeClockAngle(draftAngle)
                            isDragging = false
                            latestOnAngleChange(committed)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                val cx = w * 0.5f
                val cy = h * 0.5f
                val outerR = minOf(w, h) * 0.47f
                val activeAlpha = if (enabled) 1f else 0.42f

                // Outer dial face
                drawCircle(
                    color = surfaceContainer.copy(alpha = 0.85f * activeAlpha),
                    radius = outerR,
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = outlineColor.copy(alpha = 0.7f * activeAlpha),
                    radius = outerR,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.6.dp.toPx()),
                )

                // 24 watch bezel divisions (every 15°); 45° / 90° major divisions highlighted
                val tickCount = 360 / snapStepDegrees
                for (i in 0 until tickCount) {
                    val deg = i * snapStepDegrees
                    val rad = Math.toRadians(deg.toDouble())
                    val sinA = sin(rad).toFloat()
                    val cosA = cos(rad).toFloat()
                    val isCardinal = deg % 90 == 0
                    val isHalfCardinal = deg % 45 == 0
                    val tickLen = when {
                        isCardinal -> 10.dp.toPx()
                        isHalfCardinal -> 7.dp.toPx()
                        else -> 4.5.dp.toPx()
                    }
                    val startR = outerR - tickLen - 2.dp.toPx()
                    val endR = outerR - 2.dp.toPx()
                    val isSnappedActive = displayDeg == deg
                    val tickColor = when {
                        isSnappedActive -> primaryColor
                        isCardinal -> onSurfaceVariant.copy(alpha = 0.85f * activeAlpha)
                        isHalfCardinal -> onSurfaceVariant.copy(alpha = 0.55f * activeAlpha)
                        else -> outlineColor.copy(alpha = 0.7f * activeAlpha)
                    }
                    drawLine(
                        color = tickColor,
                        start = Offset(cx + sinA * startR, cy - cosA * startR),
                        end = Offset(cx + sinA * endR, cy - cosA * endR),
                        strokeWidth = if (isCardinal || isSnappedActive) 2.2.dp.toPx() else 1.2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                }

                // Crisp cardinal degree numbers (0°, 90°, 180°, 270°)
                drawIntoCanvas { canvas ->
                    val nc = canvas.nativeCanvas
                    labelPaint.textSize = 9.5.dp.toPx()
                    labelPaint.color = onSurfaceVariant.copy(alpha = 0.78f * activeAlpha).toArgb()
                    val fm = labelPaint.fontMetrics
                    val textOffsetY = -(fm.ascent + fm.descent) * 0.5f
                    val labelR = outerR - 21.dp.toPx()
                    val cardinals = arrayOf(0 to "0°", 90 to "90°", 180 to "180°", 270 to "270°")
                    for ((deg, lbl) in cardinals) {
                        val rad = Math.toRadians(deg.toDouble())
                        val lx = cx + sin(rad).toFloat() * labelR
                        val ly = cy - cos(rad).toFloat() * labelR + textOffsetY
                        nc.drawText(lbl, lx, ly, labelPaint)
                    }
                }

                // Clock-hand shaft + directional arrowhead
                val handRad = Math.toRadians(normalizedAngle.toDouble())
                val sinH = sin(handRad).toFloat()
                val cosH = cos(handRad).toFloat()
                val handLen = outerR - 14.dp.toPx()
                val tipX = cx + sinH * handLen
                val tipY = cy - cosH * handLen

                // Soft glow under the hand tip
                drawCircle(
                    color = primaryColor.copy(alpha = 0.18f * activeAlpha),
                    radius = 12.dp.toPx(),
                    center = Offset(tipX, tipY),
                )

                // Counterweight tail behind hub
                val tailLen = 11.dp.toPx()
                drawLine(
                    color = primaryColor.copy(alpha = 0.65f * activeAlpha),
                    start = Offset(cx - sinH * tailLen, cy + cosH * tailLen),
                    end = Offset(cx, cy),
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Main clock-hand arrow shaft
                drawLine(
                    color = primaryColor.copy(alpha = activeAlpha),
                    start = Offset(cx, cy),
                    end = Offset(tipX, tipY),
                    strokeWidth = 3.2.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Crisp arrow wings near tip
                val wingLen = 9.dp.toPx()
                val wingAngle = 2.58 // ~148° back from tip
                val leftWingRad = handRad + wingAngle
                val rightWingRad = handRad - wingAngle
                drawLine(
                    color = primaryColor.copy(alpha = activeAlpha),
                    start = Offset(tipX, tipY),
                    end = Offset(tipX + sin(leftWingRad).toFloat() * wingLen, tipY - cos(leftWingRad).toFloat() * wingLen),
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = primaryColor.copy(alpha = activeAlpha),
                    start = Offset(tipX, tipY),
                    end = Offset(tipX + sin(rightWingRad).toFloat() * wingLen, tipY - cos(rightWingRad).toFloat() * wingLen),
                    strokeWidth = 2.6.dp.toPx(),
                    cap = StrokeCap.Round,
                )

                // Draggable handle ring at tip
                drawCircle(
                    color = primaryColor.copy(alpha = activeAlpha),
                    radius = 5.5.dp.toPx(),
                    center = Offset(tipX, tipY),
                )
                drawCircle(
                    color = onPrimaryColor.copy(alpha = activeAlpha),
                    radius = 2.2.dp.toPx(),
                    center = Offset(tipX, tipY),
                )

                // Center watch pivot hub
                drawCircle(
                    color = primaryColor.copy(alpha = activeAlpha),
                    radius = 5.dp.toPx(),
                    center = Offset(cx, cy),
                )
                drawCircle(
                    color = onPrimaryColor.copy(alpha = activeAlpha),
                    radius = 2.dp.toPx(),
                    center = Offset(cx, cy),
                )
            }
        }

        // Right: Live degrees badge, snap-to-ticks toggle, and fine step controls
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (enabled) 0.85f else 0.4f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "${displayDeg}°",
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = JetBrainsMonoFamily,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        maxLines = 1,
                        modifier = Modifier.testTag("${testTagPrefix}_degrees_text"),
                    )
                    Text(
                        text = subtitleForAngle(displayDeg),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FilterChip(
                selected = snapToTicks,
                enabled = enabled,
                onClick = {
                    hapticEngine.performToggle(view, !snapToTicks)
                    onToggleSnapToTicks(!snapToTicks)
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.GridOn,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                },
                label = {
                    Text(
                        text = if (snapToTicks) tr("Привязка ${snapStepDegrees}°", "Snap ${snapStepDegrees}°") else tr("Свободно 1°", "Free 1°"),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                modifier = Modifier.testTag("${testTagPrefix}_snap_chip"),
            )

            val step = if (snapToTicks) snapStepDegrees.toFloat() else 5f
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalIconButton(
                    onClick = {
                        hapticEngine.performSegmentTick(view)
                        val next = normalizeClockAngle(normalizedAngle - step)
                        onAngleChange(next)
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("${testTagPrefix}_minus_button"),
                ) {
                    Icon(Icons.Rounded.Remove, contentDescription = tr("Уменьшить угол", "Decrease angle"), modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(
                    onClick = {
                        hapticEngine.performSegmentTick(view)
                        onAngleChange(normalizeClockAngle(resetAngle))
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("${testTagPrefix}_reset_button"),
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = tr("Сбросить угол", "Reset angle"), modifier = Modifier.size(18.dp))
                }
                FilledTonalIconButton(
                    onClick = {
                        hapticEngine.performSegmentTick(view)
                        val next = normalizeClockAngle(normalizedAngle + step)
                        onAngleChange(next)
                    },
                    enabled = enabled,
                    modifier = Modifier
                        .size(38.dp)
                        .testTag("${testTagPrefix}_plus_button"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = tr("Увеличить угол", "Increase angle"), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

internal fun normalizeClockAngle(degrees: Float): Float {
    if (!degrees.isFinite()) return 0f
    val mod = degrees % 360f
    return if (mod < 0f) mod + 360f else mod
}

internal fun describeClockDirectionRu(deg: Int, isRussian: Boolean = true): String = when (deg % 360) {
    in 338..359, in 0..22 -> tr(isRussian, "Вверх ↑ (12 ч)", "Up ↑ (12 o'clock)")
    in 23..67 -> tr(isRussian, "Диагональ ↗", "Diagonal ↗")
    in 68..112 -> tr(isRussian, "Вправо → (3 ч)", "Right → (3 o'clock)")
    in 113..157 -> tr(isRussian, "Диагональ ↘", "Diagonal ↘")
    in 158..202 -> tr(isRussian, "Вниз ↓ (6 ч)", "Down ↓ (6 o'clock)")
    in 203..247 -> tr(isRussian, "Диагональ ↙", "Diagonal ↙")
    in 248..292 -> tr(isRussian, "Влево ← (9 ч)", "Left ← (9 o'clock)")
    else -> tr(isRussian, "Диагональ ↖", "Diagonal ↖")
}

internal fun describeTiltAngleRu(deg: Int, isRussian: Boolean = true): String {
    val norm = deg % 360
    return when {
        norm == 0 -> tr(isRussian, "Прямо (горизонт)", "Level (horizontal)")
        norm == 90 -> tr(isRussian, "Поворот 90° →", "Rotate 90° →")
        norm == 180 -> tr(isRussian, "Перевёрнут 180°", "Flipped 180°")
        norm == 270 -> tr(isRussian, "Поворот −90° ←", "Rotate −90° ←")
        norm < 180 -> tr(isRussian, "Наклон +${norm}° по часовой", "Tilt +${norm}° CW")
        else -> tr(isRussian, "Наклон −${360 - norm}° против час.", "Tilt −${360 - norm}° CCW")
    }
}

internal fun clockDegreesToCameraVector(degrees: Float): Pair<Float, Float> {
    // Visual motion arrow points toward clock angle: 0° = up (0, -1), 90° = right (+1, 0).
    // Camera moves OPPOSITE to visual flag flow so flags stream in the direction the arrow points!
    val rad = degrees * (PI / 180.0)
    val visualX = sin(rad).toFloat()
    val visualY = -cos(rad).toFloat()
    return (-visualX) to (-visualY)
}
