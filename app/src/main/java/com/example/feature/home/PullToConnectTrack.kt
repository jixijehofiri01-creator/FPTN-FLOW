package com.example.feature.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.VpnConnectionState
import com.example.core.haptics.HapticEngine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Pull-to-connect Material 3 track:
 * - Pull right to connect; thumb holds on the right while connecting or connected.
 * - Pull left or tap to disconnect; springs back to the left on error.
 * - Reports thumb root coordinates so the water wave launches from the thumb center on connect.
 */
@Composable
fun PullToConnectTrack(
    connectionState: VpnConnectionState,
    enabled: Boolean,
    hapticEngine: HapticEngine,
    onToggleTriggered: (originRootPx: Offset) -> Unit,
    onOriginUpdated: (originRootPx: Offset) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val isConnected = connectionState is VpnConnectionState.Connected
    val isSwitchingOrReconnecting = connectionState is VpnConnectionState.SwitchingServer ||
        connectionState is VpnConnectionState.Reconnecting
    val isConnecting = connectionState is VpnConnectionState.Connecting || isSwitchingOrReconnecting
    val isDisconnecting = connectionState is VpnConnectionState.Disconnecting
    val isError = connectionState is VpnConnectionState.Error

    val isDarkSurface = MaterialTheme.colorScheme.surface.red * 0.299f +
        MaterialTheme.colorScheme.surface.green * 0.587f +
        MaterialTheme.colorScheme.surface.blue * 0.114f < 0.45f
    val amberAccent = if (isDarkSurface) androidx.compose.ui.graphics.Color(0xFFFFC145) else androidx.compose.ui.graphics.Color(0xFFD48800)
    val amberTrack = if (isDarkSurface) androidx.compose.ui.graphics.Color(0xFF3E2D00).copy(alpha = 0.76f) else androidx.compose.ui.graphics.Color(0xFFFFEAB5)
    val amberOnThumb = if (isDarkSurface) androidx.compose.ui.graphics.Color(0xFF281B00) else androidx.compose.ui.graphics.Color.White
    val disconnectingAccent = if (isDarkSurface) androidx.compose.ui.graphics.Color(0xFFFF8A65) else androidx.compose.ui.graphics.Color(0xFFD84315)
    val disconnectingTrack = if (isDarkSurface) androidx.compose.ui.graphics.Color(0xFF3E1F18).copy(alpha = 0.80f) else androidx.compose.ui.graphics.Color(0xFFFFDDD2)
    val disconnectingOnThumb = if (isDarkSurface) androidx.compose.ui.graphics.Color(0xFF2B100A) else androidx.compose.ui.graphics.Color.White

    val trackColor by animateColorAsState(
        targetValue = when {
            isDisconnecting -> disconnectingTrack
            isConnected -> MaterialTheme.colorScheme.tertiaryContainer
            isSwitchingOrReconnecting -> amberTrack
            isConnecting -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
            isError -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "trackColor",
    )

    val thumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant
            isDisconnecting -> disconnectingAccent
            isConnected -> MaterialTheme.colorScheme.tertiary
            isSwitchingOrReconnecting -> amberAccent
            isError -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "thumbColor",
    )

    val onThumbColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
            isDisconnecting -> disconnectingOnThumb
            isConnected -> MaterialTheme.colorScheme.onTertiary
            isSwitchingOrReconnecting -> amberOnThumb
            isError -> MaterialTheme.colorScheme.onError
            else -> MaterialTheme.colorScheme.onPrimary
        },
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "onThumbColor",
    )

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val dragOffsetPx = remember { Animatable(0f) }
    var trackRootPos by remember { mutableStateOf(Offset.Zero) }
    var trackHeightPx by remember { mutableFloatStateOf(0f) }
    var lastMaxDragPx by remember { mutableFloatStateOf(1f) }

    // Only run the infinite pulse animation while actually connecting or disconnecting (0% idle recompositions)
    val shouldPulse = isConnecting || isDisconnecting
    val puckBreathingScaleState = if (shouldPulse) {
        val connectingPulse = rememberInfiniteTransition(label = "puckPulse")
        connectingPulse.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "puckBreath",
        )
    } else {
        null
    }

    // Sync thumb position smoothly with real VPN state:
    // RIGHT while Connecting, Reconnecting, or Connected; LEFT when Disconnected, Disconnecting, or Error!
    LaunchedEffect(connectionState, lastMaxDragPx) {
        if (lastMaxDragPx <= 1f) return@LaunchedEffect
        val target = when (connectionState) {
            is VpnConnectionState.Connecting,
            is VpnConnectionState.SwitchingServer,
            is VpnConnectionState.Reconnecting,
            is VpnConnectionState.Connected,
            -> lastMaxDragPx
            is VpnConnectionState.Disconnected,
            is VpnConnectionState.Disconnecting,
            is VpnConnectionState.Error,
            -> 0f
        }
        dragOffsetPx.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessLow,
            ),
        )
    }

    val actionLabel = when (connectionState) {
        is VpnConnectionState.SwitchingServer -> stringResource(R.string.swipe_switching_server, connectionState.toServer.cleanName)
        is VpnConnectionState.Reconnecting -> stringResource(R.string.swipe_reconnecting_progress, connectionState.attempt, connectionState.maxAttempts)
        is VpnConnectionState.Connecting -> stringResource(R.string.swipe_connecting_cancel)
        is VpnConnectionState.Disconnecting -> stringResource(R.string.swipe_disconnecting)
        is VpnConnectionState.Connected -> stringResource(R.string.swipe_connected_hint)
        else -> stringResource(R.string.swipe_to_connect)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(37.dp))
            .background(trackColor)
            .border(
                width = 1.dp,
                color = when {
                    isDisconnecting -> disconnectingAccent.copy(alpha = 0.55f)
                    isConnected -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f)
                    isConnecting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    isError -> MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = RoundedCornerShape(37.dp),
            )
            .onGloballyPositioned { coords ->
                trackRootPos = coords.positionInRoot()
                trackHeightPx = coords.size.height.toFloat()
            }
            .semantics { contentDescription = actionLabel }
            .testTag("pull_to_connect_track")
            .clickable(
                enabled = enabled && !isDisconnecting,
                role = Role.Button,
                onClick = {
                    val thumbCenterOffset = Offset(
                        x = trackRootPos.x + with(density) { 40.dp.toPx() } +
                            if (!isConnected && !isConnecting) lastMaxDragPx else dragOffsetPx.value,
                        y = trackRootPos.y + trackHeightPx * 0.5f,
                    )
                    onOriginUpdated(thumbCenterOffset)
                    if (!isConnected && !isConnecting) {
                        hapticEngine.onPullHoldLatch()
                        scope.launch {
                            dragOffsetPx.animateTo(
                                targetValue = lastMaxDragPx,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow,
                                ),
                            )
                        }
                    } else {
                        // Instant tactile & visual confirmation on Disconnect tap (<16ms)
                        hapticEngine.onDisconnectingStart()
                        scope.launch {
                            dragOffsetPx.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium,
                                ),
                            )
                        }
                    }
                    onToggleTriggered(thumbCenterOffset)
                },
            ),
    ) {
        val thumbSizeDp = 60.dp
        val paddingDp = 7.dp
        val maxDragPx = with(density) { (maxWidth - thumbSizeDp - paddingDp * 2).toPx().coerceAtLeast(1f) }
        SideEffect {
            if (lastMaxDragPx != maxDragPx) {
                lastMaxDragPx = maxDragPx
            }
        }
        val progress = (dragOffsetPx.value / maxDragPx).coerceIn(0f, 1f)

        // Report right-side wave anchor whenever layout updates
        LaunchedEffect(trackRootPos, trackHeightPx, maxDragPx) {
            if (trackHeightPx > 0f) {
                onOriginUpdated(
                    Offset(
                        x = trackRootPos.x + with(density) { (paddingDp + thumbSizeDp / 2).toPx() } + maxDragPx,
                        y = trackRootPos.y + trackHeightPx * 0.5f,
                    ),
                )
            }
        }

        // Smooth liquid fill behind the thumb
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction = (0.16f + progress * 0.84f).coerceIn(0f, 1f))
                .clip(RoundedCornerShape(37.dp))
                .background(thumbColor.copy(alpha = 0.14f + progress * 0.12f)),
        )

        // Centered guidance text with adaptive padding so thumb never covers label
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = if (progress > 0.5f) 22.dp else 74.dp,
                    end = if (progress > 0.5f) 74.dp else 22.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (isConnected && progress > 0.5f) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(18.dp),
                )
            }
            Text(
                text = when {
                    !isConnected && !isConnecting && progress > 0.68f -> stringResource(R.string.swipe_release_now)
                    isConnected && progress < 0.35f -> stringResource(R.string.swipe_release_to_disconnect)
                    else -> actionLabel
                },
                style = MaterialTheme.typography.titleSmall,
                color = when {
                    isDisconnecting -> disconnectingAccent
                    isConnected -> MaterialTheme.colorScheme.onTertiaryContainer
                    isConnecting -> MaterialTheme.colorScheme.onPrimaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
            )
            if (!isConnected && !isConnecting && !isDisconnecting && progress <= 0.68f) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(18.dp),
                )
            }
        }

        // Draggable smooth-physics puck
        Box(
            modifier = Modifier
                .padding(paddingDp)
                .offset { IntOffset(dragOffsetPx.value.roundToInt(), 0) }
                .size(thumbSizeDp)
                .graphicsLayer {
                    val s = puckBreathingScaleState?.value ?: 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(thumbColor)
                .testTag("connect_thumb_puck")
                .draggable(
                    enabled = enabled && !isDisconnecting && !isConnecting,
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = (dragOffsetPx.value + delta).coerceIn(0f, maxDragPx)
                        scope.launch { dragOffsetPx.snapTo(next) }
                        val frac = if (isConnected) 1f - (next / maxDragPx) else (next / maxDragPx)
                        hapticEngine.onPullProgress(frac, isDisconnectingPull = isConnected)
                    },
                    onDragStarted = {
                        hapticEngine.resetPullTension()
                    },
                    onDragStopped = {
                        val frac = dragOffsetPx.value / maxDragPx
                        val releaseCenter = Offset(
                            x = trackRootPos.x + with(density) { (paddingDp + thumbSizeDp / 2).toPx() } + dragOffsetPx.value,
                            y = trackRootPos.y + trackHeightPx * 0.5f,
                        )
                        hapticEngine.resetPullTension()

                        if (!isConnected) {
                            if (frac >= 0.62f) {
                                // Lock & HOLD on the right while connecting!
                                hapticEngine.onPullHoldLatch()
                                onOriginUpdated(
                                    Offset(
                                        x = trackRootPos.x + with(density) { (paddingDp + thumbSizeDp / 2).toPx() } + maxDragPx,
                                        y = trackRootPos.y + trackHeightPx * 0.5f,
                                    ),
                                )
                                scope.launch {
                                    dragOffsetPx.animateTo(
                                        targetValue = maxDragPx,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                                onToggleTriggered(releaseCenter)
                            } else {
                                scope.launch {
                                    dragOffsetPx.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            }
                        } else {
                            if (frac <= 0.38f) {
                                // Pull left to disconnect
                                scope.launch {
                                    dragOffsetPx.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                                onToggleTriggered(releaseCenter)
                            } else {
                                scope.launch {
                                    dragOffsetPx.animateTo(
                                        targetValue = maxDragPx,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isConnecting || isDisconnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = onThumbColor,
                    strokeWidth = 2.6.dp,
                )
            } else {
                Icon(
                    imageVector = if (isConnected) Icons.Rounded.Shield else Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = onThumbColor,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }
}
