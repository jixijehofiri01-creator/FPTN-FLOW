package com.example.feature.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.TrafficStats
import com.example.core.ui.localization.LocalIsRussian
import com.example.core.ui.theme.JetBrainsMonoFamily
import com.example.vpn.service.VpnStateHolder
import kotlinx.coroutines.delay

/**
 * Self-contained live telemetry card that isolates 1Hz traffic & timer ticks strictly to this card,
 * preventing HomeScreen, Flag Backdrop, and Weather Overlay from recomposing every second!
 */
@Composable
fun LiveSpeedTelemetryCard(
    connectedAtMillis: Long,
    modifier: Modifier = Modifier,
) {
    val stats by VpnStateHolder.trafficStats.collectAsStateWithLifecycle()
    var elapsedSeconds by remember(connectedAtMillis) {
        mutableLongStateOf(((System.currentTimeMillis() - connectedAtMillis) / 1000L).coerceAtLeast(0L))
    }
    LaunchedEffect(connectedAtMillis) {
        while (true) {
            elapsedSeconds = ((System.currentTimeMillis() - connectedAtMillis) / 1000L).coerceAtLeast(0L)
            delay(1000L)
        }
    }
    val durationText = remember(elapsedSeconds) {
        val hours = elapsedSeconds / 3600L
        val minutes = (elapsedSeconds % 3600L) / 60L
        val secs = elapsedSeconds % 60L
        if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, secs) else "%02d:%02d".format(minutes, secs)
    }
    SpeedTelemetryCard(
        stats = stats,
        sessionDurationText = durationText,
        modifier = modifier,
    )
}

@Composable
fun SpeedTelemetryCard(
    stats: TrafficStats,
    sessionDurationText: String,
    modifier: Modifier = Modifier,
) {
    val downColor = MaterialTheme.colorScheme.primary
    val upColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            val isRussian = LocalIsRussian.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MetricColumn(
                    label = stringResource(R.string.home_speed_download),
                    value = formatSpeed(stats.downloadBytesPerSec, isRussian),
                    subValue = formatTotal(stats.totalDownloadBytes, isRussian),
                    accent = downColor,
                )
                MetricColumn(
                    label = stringResource(R.string.home_speed_upload),
                    value = formatSpeed(stats.uploadBytesPerSec, isRussian),
                    subValue = formatTotal(stats.totalUploadBytes, isRussian),
                    accent = upColor,
                )
                MetricColumn(
                    label = stringResource(R.string.home_session_time),
                    value = sessionDurationText,
                    subValue = "AES / TLS 1.3",
                    accent = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp),
            ) {
                val width = size.width
                val height = size.height
                if (width <= 0f || height <= 0f) return@Canvas

                // Subtle horizontal reference grid
                for (step in 1..2) {
                    val y = height * (step / 3f)
                    drawLine(
                        color = gridColor,
                        start = Offset(0f, y),
                        end = Offset(width, y),
                        strokeWidth = 1.dp.toPx(),
                    )
                }

                val peak = maxOf(
                    stats.downloadHistory.maxOrNull() ?: 0f,
                    stats.uploadHistory.maxOrNull() ?: 0f,
                    10f,
                )

                drawSmoothSeries(
                    values = stats.downloadHistory,
                    maxValue = peak,
                    strokeColor = downColor,
                    fillBrush = Brush.verticalGradient(
                        colors = listOf(downColor.copy(alpha = 0.28f), Color.Transparent),
                        startY = 0f,
                        endY = height,
                    ),
                )
                drawSmoothSeries(
                    values = stats.uploadHistory,
                    maxValue = peak,
                    strokeColor = upColor,
                    fillBrush = null,
                )
            }
        }
    }
}

@Composable
private fun MetricColumn(
    label: String,
    value: String,
    subValue: String,
    accent: Color,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = accent,
        )
        Text(
            text = subValue,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSmoothSeries(
    values: List<Float>,
    maxValue: Float,
    strokeColor: Color,
    fillBrush: Brush?,
) {
    if (values.size < 2) return
    val stepX = size.width / (values.size - 1).coerceAtLeast(1)
    val paddingY = 6.dp.toPx()
    val usableHeight = (size.height - paddingY * 2).coerceAtLeast(1f)

    val points = values.mapIndexed { idx, v ->
        val norm = (v / maxValue).coerceIn(0f, 1f)
        Offset(
            x = idx * stepX,
            y = size.height - paddingY - norm * usableHeight,
        )
    }

    val linePath = Path().apply {
        moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val midX = (prev.x + curr.x) * 0.5f
            cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
        }
    }

    if (fillBrush != null) {
        val areaPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(path = areaPath, brush = fillBrush)
    }

    drawPath(
        path = linePath,
        color = strokeColor,
        style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round),
    )
}

fun formatSpeed(bytesPerSec: Long, isRussian: Boolean = true): String = when {
    bytesPerSec >= 1_048_576L -> if (isRussian) "%.1f МБ/с".format(bytesPerSec / 1_048_576f) else "%.1f MB/s".format(bytesPerSec / 1_048_576f)
    bytesPerSec >= 1_024L -> if (isRussian) "%.1f КБ/с".format(bytesPerSec / 1_024f) else "%.1f KB/s".format(bytesPerSec / 1_024f)
    else -> if (isRussian) "$bytesPerSec Б/с" else "$bytesPerSec B/s"
}

fun formatTotal(totalBytes: Long, isRussian: Boolean = true): String = when {
    totalBytes >= 1_073_741_824L -> if (isRussian) "%.2f ГБ".format(totalBytes / 1_073_741_824f) else "%.2f GB".format(totalBytes / 1_073_741_824f)
    totalBytes >= 1_048_576L -> if (isRussian) "%.1f МБ".format(totalBytes / 1_048_576f) else "%.1f MB".format(totalBytes / 1_048_576f)
    totalBytes >= 1_024L -> if (isRussian) "%.0f КБ".format(totalBytes / 1_024f) else "%.0f KB".format(totalBytes / 1_024f)
    else -> if (isRussian) "$totalBytes Б" else "$totalBytes B"
}
