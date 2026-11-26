package com.example.core.ui.flags

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import com.example.core.haptics.HapticEngine
import com.example.core.ui.components.clockDegreesToCameraVector
import com.example.core.ui.components.normalizeClockAngle
import com.example.core.ui.effects.CrackedGlassEffects
import com.example.domain.model.CountryFlagResolver
import com.example.domain.model.FlagMatrixDirection
import com.example.domain.model.VpnConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

enum class FlagPreviewMode {
    MATRIX_SWITCH,
    MYSTERY_SHATTER,
    DISCONNECT,
    ERROR_FROST,
}

/**
 * Triggerable demo request used in Appearance Settings so users can preview the
 * infinite World Flag Matrix transition, Mystery "?" Glass Shatter reveal,
 * smooth disconnect dock, or minimalist error frost on demand.
 */
data class FlagTransitionPreviewEvent(
    val id: Long,
    val fromCode: String?,
    val toCode: String,
    val mode: FlagPreviewMode = FlagPreviewMode.MATRIX_SWITCH,
)

private enum class MatrixTransitionPhase {
    HIDDEN,
    SILK_IDLE,
    FLATTEN_CLOTH,
    ZOOM_OUT,
    CRUISE,
    DECEL_LOCK,
    ZOOM_IN_UNFURL,
    DISCONNECT_FLATTEN,
    DISCONNECT_DOCK,
    ERROR_FROST,
    MYSTERY_SUSPENSE,
    MYSTERY_SHATTER,
}

/**
 * Maps any integer 2D grid coordinate `(col, row)` onto a freshly shuffled sovereign country pool
 * using a non-repeating prime row stride (17). Since 17 > visible columns and pool size is ~193,
 * no two simultaneously visible tiles ever duplicate the same country!
 */
internal fun mapMatrixCellToCountry(col: Int, row: Int, pool: List<String>): String {
    if (pool.isEmpty()) return "US"
    val n = pool.size
    val raw = col + row * 17
    val idx = ((raw % n) + n) % n
    return pool[idx]
}

private fun buildShuffledMatrixPool(excludeA: String?, excludeB: String?, seed: Int): List<String> {
    val exA = excludeA?.uppercase()
    val exB = excludeB?.uppercase()
    val filtered = CountryFlagResolver.WORLD_MATRIX_COUNTRY_CODES
        .filter { code -> code != exA && code != exB }
        .toMutableList()
    val rng = Random(seed.toLong() * 2654435761L xor System.nanoTime())
    for (i in filtered.lastIndex downTo 1) {
        val j = rng.nextInt(i + 1)
        val tmp = filtered[i]
        filtered[i] = filtered[j]
        filtered[j] = tmp
    }
    return filtered
}

private fun easeOutCubic(t: Float): Float {
    val u = 1f - t.coerceIn(0f, 1f)
    return 1f - u * u * u
}

private fun easeInOutCubic(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
}

private fun easeOutQuint(t: Float): Float {
    val u = 1f - t.coerceIn(0f, 1f)
    return 1f - u * u * u * u * u
}

/**
 * Hero country flag backdrop with silky 60/120fps World Flag Matrix transitions:
 * 1. Smoothly flattens the waving silk cloth (`clothMix: 1 -> 0`) before zooming symmetrically into cell (0, 0),
 *    firing a crisp tactile slot-dock detent (`onFlagDockSnap`) as it lands in the matrix.
 * 2. Keeps cell (0, 0) pinned to `outgoingCode` throughout ZOOM_OUT, CRUISE, and DECEL_LOCK — zero tile-swap glitch.
 * 3. Glides across ~195 freshly shuffled sovereign countries in the user's chosen direction & speed (auto-accelerating
 *    and homing smoothly once the destination server connects), optionally highlighting real available servers.
 * 4. Smoothly unfurls the target flag back into full waving GPU silk (`clothMix: 0 -> 1`).
 * 5. Uses `CompositingStrategy.Offscreen` + `BlendMode.DstIn` alpha feathering so ZERO horizontal rectangle seam
 *    ever appears over the Home screen background gradient!
 * 6. Supports smooth Disconnect Dock animation, minimalist Error Frost veil, and Pirate/Special "?" Glass Shatter.
 */
@Stable
private class MatrixAnimClock {
    var phaseProgress: Float = 0f
    var camX: Float = 0f
    var camY: Float = 0f
    var targetCellCol: Int = 0
    var targetCellRow: Int = 0
    var cruiseVelX: Float = 0f
    var cruiseVelY: Float = 0f
    // Frame tick read only inside DrawScope to avoid Compose recompositions during motion.
    var frameTick by mutableLongStateOf(0L)
}

@Composable
fun CinematicFlagHeroBackdrop(
    connectionState: VpnConnectionState,
    fallbackCountryCode: String?,
    enabled: Boolean,
    matrixTransitionEnabled: Boolean,
    backdropScale: Float,
    dimAmount: Float,
    showCoatOfArms: Boolean,
    physicsIntensity: Float,
    hapticEngine: HapticEngine?,
    modifier: Modifier = Modifier,
    previewEvent: FlagTransitionPreviewEvent? = null,
    matrixDirection: FlagMatrixDirection = FlagMatrixDirection.RIGHT_TO_LEFT,
    flagTiltDegrees: Float = 0f,
    matrixAngleDegrees: Float = 270f,
    matrixRandomAngle: Boolean = false,
    matrixSpeed: Float = 1.0f,
    matrixZoom: Float = 1.0f,
    highlightAvailableServers: Boolean = true,
    availableServerCountryCodes: Set<String> = emptySet(),
) {
    if (!enabled) return

    val context = LocalContext.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val tertiaryColor = MaterialTheme.colorScheme.tertiary
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceContainerHigh = MaterialTheme.colorScheme.surfaceContainerHigh

    val normalizedAvailableCodes = remember(availableServerCountryCodes) {
        availableServerCountryCodes.map { it.uppercase() }.toSet()
    }

    // Track settled silk flag so outgoing flag stays visible during switch/disconnect
    var settledCode by remember {
        mutableStateOf(
            when (connectionState) {
                is VpnConnectionState.Connected -> connectionState.server.countryCode ?: fallbackCountryCode
                else -> fallbackCountryCode
            },
        )
    }

    var phase by remember {
        mutableStateOf(
            if (connectionState is VpnConnectionState.Connected && !settledCode.isNullOrBlank()) {
                MatrixTransitionPhase.SILK_IDLE
            } else {
                MatrixTransitionPhase.HIDDEN
            },
        )
    }
    var outgoingCode by remember { mutableStateOf<String?>(null) }
    var targetCode by remember { mutableStateOf<String?>(null) }
    var targetReady by remember { mutableStateOf(false) }
    var activePreviewId by remember { mutableStateOf<Long?>(null) }
    var transitionSessionId by remember { mutableIntStateOf(0) }
    var sessionPool by remember { mutableStateOf(CountryFlagResolver.WORLD_MATRIX_COUNTRY_CODES) }

    // Render clock read only in the draw pass to avoid recompositions during transitions.
    val anim = remember { MatrixAnimClock() }
    var thumbsReadyTick by remember { mutableIntStateOf(0) }
    val coaVersion by com.example.data.remote.CoatOfArmsRepository.coaCacheVersion.collectAsStateWithLifecycle()

    // Warm pre-rounded world flag thumbnails off the main thread before drawing matrix tiles.
    LaunchedEffect(context, showCoatOfArms, matrixTransitionEnabled, transitionSessionId, outgoingCode, targetCode, coaVersion) {
        if (!matrixTransitionEnabled) return@LaunchedEffect
        withContext(Dispatchers.Default) {
            outgoingCode?.let {
                FlagTextureRenderer.getBitmap(context, it, showCoatOfArms)
                FlagTextureRenderer.getOrCreateRoundedThumbnail(context, it, showCoatOfArms)
            }
            targetCode?.let {
                FlagTextureRenderer.getBitmap(context, it, showCoatOfArms)
                FlagTextureRenderer.getOrCreateRoundedThumbnail(context, it, showCoatOfArms)
            }
            normalizedAvailableCodes.forEach { code ->
                FlagTextureRenderer.getOrCreateRoundedThumbnail(context, code, showCoatOfArms)
            }
            val pool = sessionPool
            for (i in pool.indices) {
                FlagTextureRenderer.getOrCreateRoundedThumbnail(context, pool[i], showCoatOfArms)
                if (i % 8 == 7) {
                    thumbsReadyTick++
                    yield()
                }
            }
        }
        thumbsReadyTick++
    }

    // React to explicit Appearance Settings preview demos
    LaunchedEffect(previewEvent?.id) {
        val evt = previewEvent ?: return@LaunchedEffect
        if (evt.id == activePreviewId) return@LaunchedEffect
        activePreviewId = evt.id
        anim.camX = 0f
        anim.camY = 0f
        anim.phaseProgress = 0f
        anim.frameTick++
        when (evt.mode) {
            FlagPreviewMode.MATRIX_SWITCH, FlagPreviewMode.MYSTERY_SHATTER -> {
                val from = evt.fromCode ?: settledCode ?: "DE"
                outgoingCode = from
                targetCode = evt.toCode
                targetReady = true
                sessionPool = buildShuffledMatrixPool(from, evt.toCode, transitionSessionId + 1)
                phase = MatrixTransitionPhase.FLATTEN_CLOTH
                transitionSessionId++
            }
            FlagPreviewMode.DISCONNECT -> {
                val from = evt.fromCode ?: settledCode ?: "DE"
                outgoingCode = from
                settledCode = from
                targetCode = null
                targetReady = false
                sessionPool = buildShuffledMatrixPool(from, null, transitionSessionId + 1)
                phase = MatrixTransitionPhase.DISCONNECT_FLATTEN
                transitionSessionId++
            }
            FlagPreviewMode.ERROR_FROST -> {
                targetReady = false
                phase = MatrixTransitionPhase.ERROR_FROST
                transitionSessionId++
            }
        }
    }

    // React to real VPN connection state transitions
    LaunchedEffect(connectionState, fallbackCountryCode, matrixTransitionEnabled) {
        when (connectionState) {
            is VpnConnectionState.SwitchingServer -> {
                val from = connectionState.fromServer?.countryCode ?: settledCode
                val to = connectionState.toServer.countryCode
                if (!matrixTransitionEnabled) {
                    if (!from.isNullOrBlank()) settledCode = from
                    phase = if (!settledCode.isNullOrBlank()) MatrixTransitionPhase.SILK_IDLE else MatrixTransitionPhase.HIDDEN
                } else {
                    outgoingCode = from
                    targetCode = to
                    targetReady = false
                    if (phase != MatrixTransitionPhase.FLATTEN_CLOTH &&
                        phase != MatrixTransitionPhase.ZOOM_OUT &&
                        phase != MatrixTransitionPhase.CRUISE
                    ) {
                        anim.camX = 0f
                        anim.camY = 0f
                        anim.phaseProgress = 0f
                        anim.frameTick++
                        sessionPool = buildShuffledMatrixPool(from, to, transitionSessionId + 1)
                        phase = if (!from.isNullOrBlank()) {
                            MatrixTransitionPhase.FLATTEN_CLOTH
                        } else {
                            MatrixTransitionPhase.CRUISE
                        }
                        transitionSessionId++
                    }
                }
            }

            is VpnConnectionState.Connecting, is VpnConnectionState.Reconnecting -> {
                val target = when (connectionState) {
                    is VpnConnectionState.Connecting -> connectionState.server.countryCode
                    is VpnConnectionState.Reconnecting -> connectionState.server.countryCode
                }
                if (matrixTransitionEnabled && !target.isNullOrBlank()) {
                    targetCode = target
                    targetReady = false
                    if (phase == MatrixTransitionPhase.HIDDEN || phase == MatrixTransitionPhase.ERROR_FROST) {
                        outgoingCode = null
                        anim.camX = 0f
                        anim.camY = 0f
                        anim.phaseProgress = 0f
                        anim.frameTick++
                        sessionPool = buildShuffledMatrixPool(null, target, transitionSessionId + 1)
                        phase = MatrixTransitionPhase.CRUISE
                        transitionSessionId++
                    } else if (phase == MatrixTransitionPhase.SILK_IDLE && settledCode != target) {
                        outgoingCode = settledCode
                        anim.camX = 0f
                        anim.camY = 0f
                        anim.phaseProgress = 0f
                        anim.frameTick++
                        sessionPool = buildShuffledMatrixPool(settledCode, target, transitionSessionId + 1)
                        phase = MatrixTransitionPhase.FLATTEN_CLOTH
                        transitionSessionId++
                    }
                }
            }

            is VpnConnectionState.Connected -> {
                val nextCode = connectionState.server.countryCode ?: fallbackCountryCode
                if (nextCode.isNullOrBlank()) {
                    phase = MatrixTransitionPhase.HIDDEN
                    settledCode = null
                } else if (!matrixTransitionEnabled) {
                    settledCode = nextCode
                    phase = MatrixTransitionPhase.SILK_IDLE
                } else if (
                    phase == MatrixTransitionPhase.FLATTEN_CLOTH ||
                    phase == MatrixTransitionPhase.ZOOM_OUT ||
                    phase == MatrixTransitionPhase.CRUISE
                ) {
                    targetCode = nextCode
                    targetReady = true
                } else if (phase == MatrixTransitionPhase.HIDDEN || phase == MatrixTransitionPhase.ERROR_FROST) {
                    outgoingCode = null
                    targetCode = nextCode
                    targetReady = true
                    anim.camX = 0f
                    anim.camY = 0f
                    anim.phaseProgress = 0f
                    anim.frameTick++
                    sessionPool = buildShuffledMatrixPool(null, nextCode, transitionSessionId + 1)
                    phase = MatrixTransitionPhase.CRUISE
                    transitionSessionId++
                } else if (phase == MatrixTransitionPhase.SILK_IDLE && settledCode != nextCode) {
                    outgoingCode = settledCode
                    targetCode = nextCode
                    targetReady = true
                    anim.camX = 0f
                    anim.camY = 0f
                    anim.phaseProgress = 0f
                    anim.frameTick++
                    sessionPool = buildShuffledMatrixPool(settledCode, nextCode, transitionSessionId + 1)
                    phase = MatrixTransitionPhase.FLATTEN_CLOTH
                    transitionSessionId++
                }
            }

            is VpnConnectionState.Disconnecting, is VpnConnectionState.Disconnected -> {
                if (activePreviewId == null) {
                    targetReady = false
                    if (matrixTransitionEnabled && phase == MatrixTransitionPhase.SILK_IDLE && !settledCode.isNullOrBlank()) {
                        outgoingCode = settledCode
                        anim.camX = 0f
                        anim.camY = 0f
                        anim.phaseProgress = 0f
                        anim.frameTick++
                        sessionPool = buildShuffledMatrixPool(settledCode, null, transitionSessionId + 1)
                        phase = MatrixTransitionPhase.DISCONNECT_FLATTEN
                        transitionSessionId++
                    } else if (phase != MatrixTransitionPhase.DISCONNECT_FLATTEN && phase != MatrixTransitionPhase.DISCONNECT_DOCK) {
                        phase = MatrixTransitionPhase.HIDDEN
                    }
                }
            }

            is VpnConnectionState.Error -> {
                if (activePreviewId == null) {
                    targetReady = false
                    anim.phaseProgress = 0f
                    anim.frameTick++
                    phase = MatrixTransitionPhase.ERROR_FROST
                    transitionSessionId++
                }
            }
        }
    }

    // Frame driver runs only while a transition phase is active so idle state consumes no CPU.
    LaunchedEffect(transitionSessionId) {
        if (transitionSessionId == 0) return@LaunchedEffect

        var currentPhase = phase
        if (currentPhase == MatrixTransitionPhase.SILK_IDLE || currentPhase == MatrixTransitionPhase.HIDDEN) return@LaunchedEffect

        val effectiveFlightAngle = if (matrixRandomAngle || matrixDirection == FlagMatrixDirection.RANDOM_EACH_TIME) {
            (Random(transitionSessionId * 31 + 7).nextInt(24) * 15).toFloat()
        } else {
            normalizeClockAngle(matrixAngleDegrees)
        }
        // Transform world clock direction into rotated grid local space so arrow matches on-screen motion!
        val localFlightAngle = normalizeClockAngle(effectiveFlightAngle - flagTiltDegrees)
        val (dirX, dirY) = clockDegreesToCameraVector(localFlightAngle)
        val speedFactor = matrixSpeed.coerceIn(0.40f, 2.20f)
        val baseCruiseSpeed = 2.85f * speedFactor

        var phaseElapsed = 0f
        var cruiseMinElapsed = 0f
        var decelStartCamX = 0f
        var decelStartCamY = 0f
        var mysteryPulseSent = -1
        var disconnectSnapSent = false
        var lastNanos = 0L

        while (true) {
            val stopLoop = withFrameNanos { frameNanos ->
                val dt = if (lastNanos == 0L) 0.016f else ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.045f)
                lastNanos = frameNanos

                val finished = when (currentPhase) {
                    MatrixTransitionPhase.FLATTEN_CLOTH -> {
                        val duration = 0.32f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p
                        anim.camX = 0f
                        anim.camY = 0f
                        anim.cruiseVelX = 0f
                        anim.cruiseVelY = 0f
                        if (p >= 1f) {
                            hapticEngine?.onFlagMatrixZoomOut()
                            currentPhase = MatrixTransitionPhase.ZOOM_OUT
                            phase = currentPhase
                            phaseElapsed = 0f
                            anim.phaseProgress = 0f
                        }
                        false
                    }

                    MatrixTransitionPhase.ZOOM_OUT -> {
                        val duration = 0.54f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p
                        anim.camX = 0f
                        anim.camY = 0f
                        anim.cruiseVelX = 0f
                        anim.cruiseVelY = 0f
                        if (p >= 1f) {
                            hapticEngine?.onFlagDockSnap()
                            currentPhase = MatrixTransitionPhase.CRUISE
                            phase = currentPhase
                            phaseElapsed = 0f
                            cruiseMinElapsed = 0f
                            anim.phaseProgress = 0f
                        }
                        false
                    }

                    MatrixTransitionPhase.CRUISE -> {
                        phaseElapsed += dt
                        cruiseMinElapsed += dt
                        val ramp = easeOutCubic((phaseElapsed / 0.42f).coerceIn(0f, 1f))
                        val homingBoost = if (targetReady) 1.38f else 1.0f
                        val vx = dirX * baseCruiseSpeed * ramp * homingBoost
                        val vy = dirY * baseCruiseSpeed * ramp * homingBoost
                        anim.cruiseVelX = vx
                        anim.cruiseVelY = vy
                        anim.camX += vx * dt
                        anim.camY += vy * dt
                        anim.phaseProgress = (phaseElapsed / 0.45f).coerceIn(0f, 1f)

                        val minCruiseTime = if (activePreviewId != null) 0.72f else 0.48f
                        if (targetReady && !targetCode.isNullOrBlank() && cruiseMinElapsed >= minCruiseTime) {
                            val lookAhead = (2.1f + 0.55f * speedFactor).coerceIn(2.0f, 3.4f)
                            val rawCol = anim.camX + dirX * lookAhead
                            val rawRow = anim.camY + dirY * lookAhead
                            var candCol = if (abs(dirX) > 0.1f) {
                                if (dirX > 0f) ceil(rawCol).toInt() else floor(rawCol).toInt()
                            } else {
                                rawCol.roundToInt()
                            }
                            var candRow = if (abs(dirY) > 0.1f) {
                                if (dirY > 0f) ceil(rawRow).toInt() else floor(rawRow).toInt()
                            } else {
                                rawRow.roundToInt()
                            }
                            if (candCol == 0 && candRow == 0) {
                                candCol += if (dirX >= 0f) 2 else -2
                            }
                            anim.targetCellCol = candCol
                            anim.targetCellRow = candRow
                            decelStartCamX = anim.camX
                            decelStartCamY = anim.camY
                            currentPhase = MatrixTransitionPhase.DECEL_LOCK
                            phase = currentPhase
                            phaseElapsed = 0f
                            anim.phaseProgress = 0f
                        }
                        false
                    }

                    MatrixTransitionPhase.DECEL_LOCK -> {
                        val duration = (0.76f / speedFactor.coerceIn(0.75f, 1.35f)).coerceIn(0.56f, 0.92f)
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        val e = easeOutQuint(p)
                        val prevX = anim.camX
                        val prevY = anim.camY
                        anim.camX = decelStartCamX + (anim.targetCellCol.toFloat() - decelStartCamX) * e
                        anim.camY = decelStartCamY + (anim.targetCellRow.toFloat() - decelStartCamY) * e
                        anim.cruiseVelX = (anim.camX - prevX) / dt
                        anim.cruiseVelY = (anim.camY - prevY) / dt
                        anim.phaseProgress = p
                        if (p >= 1f) {
                            anim.camX = anim.targetCellCol.toFloat()
                            anim.camY = anim.targetCellRow.toFloat()
                            anim.cruiseVelX = 0f
                            anim.cruiseVelY = 0f
                            hapticEngine?.onFlagDockSnap()
                            hapticEngine?.onFlagMatrixLockIn()
                            val dest = targetCode
                            if (CountryFlagResolver.isSpecialNonCountryFlag(dest)) {
                                mysteryPulseSent = -1
                                currentPhase = MatrixTransitionPhase.MYSTERY_SUSPENSE
                            } else {
                                settledCode = dest
                                currentPhase = MatrixTransitionPhase.ZOOM_IN_UNFURL
                            }
                            phase = currentPhase
                            phaseElapsed = 0f
                            anim.phaseProgress = 0f
                        }
                        false
                    }

                    MatrixTransitionPhase.ZOOM_IN_UNFURL -> {
                        val duration = 0.84f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p
                        if (p >= 1f) {
                            settledCode = targetCode
                            currentPhase = MatrixTransitionPhase.SILK_IDLE
                            phase = currentPhase
                            activePreviewId = null
                            true
                        } else {
                            false
                        }
                    }

                    MatrixTransitionPhase.DISCONNECT_FLATTEN -> {
                        val duration = 0.30f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p
                        if (p >= 1f) {
                            disconnectSnapSent = false
                            currentPhase = MatrixTransitionPhase.DISCONNECT_DOCK
                            phase = currentPhase
                            phaseElapsed = 0f
                            anim.phaseProgress = 0f
                        }
                        false
                    }

                    MatrixTransitionPhase.DISCONNECT_DOCK -> {
                        val duration = 0.62f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p
                        if (!disconnectSnapSent && p >= 0.58f) {
                            disconnectSnapSent = true
                            hapticEngine?.onFlagDockSnap()
                        }
                        if (p >= 1f) {
                            val wasPreview = activePreviewId != null
                            activePreviewId = null
                            if (wasPreview && !settledCode.isNullOrBlank()) {
                                currentPhase = MatrixTransitionPhase.SILK_IDLE
                            } else {
                                currentPhase = MatrixTransitionPhase.HIDDEN
                            }
                            phase = currentPhase
                            true
                        } else {
                            false
                        }
                    }

                    MatrixTransitionPhase.ERROR_FROST -> {
                        val duration = if (activePreviewId != null) 1.55f else 1.85f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p
                        if (p >= 1f) {
                            if (activePreviewId != null) {
                                activePreviewId = null
                                currentPhase = if (!settledCode.isNullOrBlank()) MatrixTransitionPhase.SILK_IDLE else MatrixTransitionPhase.HIDDEN
                                phase = currentPhase
                            }
                            true
                        } else {
                            false
                        }
                    }

                    MatrixTransitionPhase.MYSTERY_SUSPENSE -> {
                        val duration = 1.08f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p

                        val desiredPulse = when {
                            p >= 0.78f -> 2
                            p >= 0.46f -> 1
                            p >= 0.14f -> 0
                            else -> -1
                        }
                        if (desiredPulse > mysteryPulseSent) {
                            mysteryPulseSent = desiredPulse
                            hapticEngine?.onMysteryPulse(desiredPulse)
                        }

                        if (p >= 1f) {
                            settledCode = targetCode
                            hapticEngine?.onMysteryGlassShatter()
                            currentPhase = MatrixTransitionPhase.MYSTERY_SHATTER
                            phase = currentPhase
                            phaseElapsed = 0f
                            anim.phaseProgress = 0f
                        }
                        false
                    }

                    MatrixTransitionPhase.MYSTERY_SHATTER -> {
                        val duration = 1.02f
                        phaseElapsed += dt
                        val p = (phaseElapsed / duration).coerceIn(0f, 1f)
                        anim.phaseProgress = p
                        if (p >= 1f) {
                            settledCode = targetCode
                            currentPhase = MatrixTransitionPhase.SILK_IDLE
                            phase = currentPhase
                            activePreviewId = null
                            true
                        } else {
                            false
                        }
                    }

                    MatrixTransitionPhase.SILK_IDLE, MatrixTransitionPhase.HIDDEN -> true
                }
                anim.frameTick++
                finished
            }
            if (stopLoop) break
        }
    }

    // Pre-allocated native Paints & Feather Brushes for true zero-allocation 60/120fps Canvas drawing
    val bmpPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val fillPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val strokePaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val clipPath = remember { AndroidPath() }
    val shardPath = remember { AndroidPath() }
    val srcRect = remember { Rect() }
    val dstRect = remember { RectF() }

    val verticalDstInBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.08f to Color.White,
                0.56f to Color.White,
                0.84f to Color.White.copy(alpha = 0.22f),
                1.0f to Color.Transparent,
            ),
        )
    }
    val horizontalDstInBrush = remember {
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0.0f to Color.Transparent,
                0.13f to Color.White,
                0.87f to Color.White,
                1.0f to Color.Transparent,
            ),
        )
    }

    // Only discrete phase is read in composition; continuous progress stays in DrawScope.
    val silkCountryCode = when (phase) {
        MatrixTransitionPhase.FLATTEN_CLOTH,
        MatrixTransitionPhase.ZOOM_OUT,
        MatrixTransitionPhase.DISCONNECT_FLATTEN,
        MatrixTransitionPhase.DISCONNECT_DOCK,
        -> outgoingCode ?: settledCode
        MatrixTransitionPhase.ZOOM_IN_UNFURL, MatrixTransitionPhase.MYSTERY_SHATTER -> targetCode ?: settledCode
        else -> settledCode
    }
    val mountSilkLayer = phase == MatrixTransitionPhase.SILK_IDLE ||
        phase == MatrixTransitionPhase.FLATTEN_CLOTH ||
        phase == MatrixTransitionPhase.DISCONNECT_FLATTEN ||
        phase == MatrixTransitionPhase.ZOOM_OUT ||
        phase == MatrixTransitionPhase.DISCONNECT_DOCK ||
        phase == MatrixTransitionPhase.ZOOM_IN_UNFURL ||
        phase == MatrixTransitionPhase.MYSTERY_SHATTER

    val isMatrixCanvasVisible = phase != MatrixTransitionPhase.HIDDEN &&
        phase != MatrixTransitionPhase.SILK_IDLE &&
        phase != MatrixTransitionPhase.FLATTEN_CLOTH &&
        phase != MatrixTransitionPhase.DISCONNECT_FLATTEN

    Box(modifier = modifier) {
        // Layer 1: full-width GPU waving flag driven via draw-phase state providers.
        if (!silkCountryCode.isNullOrBlank() && mountSilkLayer) {
            WavingCountryFlagBackdrop(
                countryCode = silkCountryCode,
                isConnected = true,
                enabled = enabled,
                backdropScale = backdropScale,
                dimAmount = dimAmount,
                showCoatOfArms = showCoatOfArms,
                physicsIntensity = physicsIntensity,
                tiltDegrees = flagTiltDegrees,
                clothMixProvider = {
                    val tick = anim.frameTick
                    if (tick < 0L) 0f else {
                        val p = anim.phaseProgress
                        when (phase) {
                            MatrixTransitionPhase.SILK_IDLE -> 1f
                            MatrixTransitionPhase.FLATTEN_CLOTH, MatrixTransitionPhase.DISCONNECT_FLATTEN -> 1f - easeInOutCubic(p)
                            MatrixTransitionPhase.ZOOM_OUT, MatrixTransitionPhase.DISCONNECT_DOCK -> 0f
                            MatrixTransitionPhase.ZOOM_IN_UNFURL -> easeInOutCubic(((p - 0.52f) / 0.48f).coerceIn(0f, 1f))
                            MatrixTransitionPhase.MYSTERY_SHATTER -> easeOutCubic(((p - 0.16f) / 0.65f).coerceIn(0f, 1f))
                            else -> 0f
                        }
                    }
                },
                alphaProvider = if (phase == MatrixTransitionPhase.SILK_IDLE) null else {
                    {
                        val tick = anim.frameTick
                        if (tick < 0L) 0f else {
                            val p = anim.phaseProgress
                            val f = when (phase) {
                                MatrixTransitionPhase.FLATTEN_CLOTH, MatrixTransitionPhase.DISCONNECT_FLATTEN -> 1f
                                MatrixTransitionPhase.ZOOM_OUT, MatrixTransitionPhase.DISCONNECT_DOCK ->
                                    (1f - easeInOutCubic((p / 0.20f).coerceIn(0f, 1f))).coerceIn(0f, 1f)
                                MatrixTransitionPhase.ZOOM_IN_UNFURL ->
                                    easeInOutCubic(((p - 0.46f) / 0.24f).coerceIn(0f, 1f))
                                MatrixTransitionPhase.MYSTERY_SHATTER ->
                                    easeOutCubic(((p - 0.14f) / 0.60f).coerceIn(0f, 1f))
                                else -> 0f
                            }
                            f * 0.88f
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Layer 2: Hardware-accelerated Infinite 2D World Flag Matrix + Error Frost + Mystery "?" Glass Shatter
        if (isMatrixCanvasVisible) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) {
                if (anim.frameTick < 0L || thumbsReadyTick < 0 || coaVersion < 0) return@Canvas
                val w = size.width
                val h = size.height
                if (w <= 4f || h <= 4f) return@Canvas

                val p = anim.phaseProgress
                val camX = anim.camX
                val camY = anim.camY
                val targetCellCol = anim.targetCellCol
                val targetCellRow = anim.targetCellRow
                val cruiseVelX = anim.cruiseVelX
                val cruiseVelY = anim.cruiseVelY

                val zoomPref = matrixZoom.coerceIn(0.65f, 1.45f)
                val baseCardW = (w * 0.26f * zoomPref).coerceIn(72f, 236f)
                val baseCardH = baseCardW * 0.667f
                val gapX = baseCardW * 0.14f
                val gapY = baseCardH * 0.17f
                val stepX = baseCardW + gapX
                val stepY = baseCardH + gapY

                // EXACT 1:1 geometric match to SILK_FLAG_CLOTH_AGSL_SHADER at clothMix = 0 (zero snap/jump!)
                val aspect = w / h.coerceAtLeast(1f)
                val texAspect = 1.5f
                val clampedBackdropScale = backdropScale.coerceIn(0.85f, 1.55f)
                val exactHeroW = if (aspect > texAspect) {
                    w * clampedBackdropScale
                } else {
                    h * clampedBackdropScale * texAspect
                }
                val heroMatchScale = (exactHeroW / baseCardW).coerceAtLeast(1.2f)

                val centerX = w * 0.5f
                val heroLockBlend = when (phase) {
                    MatrixTransitionPhase.ZOOM_OUT -> 1f - easeInOutCubic(p)
                    MatrixTransitionPhase.DISCONNECT_DOCK -> 1f - easeInOutCubic((p / 0.62f).coerceIn(0f, 1f))
                    MatrixTransitionPhase.ZOOM_IN_UNFURL -> easeInOutCubic((p / 0.56f).coerceIn(0f, 1f))
                    else -> 0f
                }
                val centerY = h * (0.44f + 0.04f * heroLockBlend)

                val shaderHeroAlpha = (0.88f * (1f - dimAmount).coerceIn(0.15f, 1.0f)).coerceIn(0.18f, 0.92f)
                val gridTileDimAlpha = (1f - dimAmount * 0.55f).coerceIn(0.36f, 0.94f)
                val dimAlpha = gridTileDimAlpha + (shaderHeroAlpha - gridTileDimAlpha) * heroLockBlend

                val gridScale = when (phase) {
                    MatrixTransitionPhase.ZOOM_OUT -> {
                        val e = easeInOutCubic(p)
                        heroMatchScale + (1f - heroMatchScale) * e
                    }
                    MatrixTransitionPhase.DISCONNECT_DOCK -> {
                        val dockT = easeInOutCubic((p / 0.62f).coerceIn(0f, 1f))
                        heroMatchScale + (0.92f - heroMatchScale) * dockT
                    }
                    MatrixTransitionPhase.ZOOM_IN_UNFURL -> {
                        val e = easeInOutCubic((p / 0.56f).coerceIn(0f, 1f))
                        1f + (heroMatchScale - 1f) * e
                    }
                    MatrixTransitionPhase.MYSTERY_SUSPENSE -> 1f - 0.14f * easeOutCubic(p)
                    MatrixTransitionPhase.MYSTERY_SHATTER -> 0.86f
                    else -> 1f
                }

                val surroundingAlpha = when (phase) {
                    MatrixTransitionPhase.ZOOM_OUT -> easeOutCubic(((p - 0.16f) / 0.84f).coerceIn(0f, 1f)) * 0.82f
                    MatrixTransitionPhase.DISCONNECT_DOCK -> {
                        val fadeIn = easeOutCubic(((p - 0.14f) / 0.40f).coerceIn(0f, 1f))
                        val fadeOut = 1f - easeInOutCubic(((p - 0.56f) / 0.44f).coerceIn(0f, 1f))
                        fadeIn * fadeOut * 0.64f
                    }
                    MatrixTransitionPhase.CRUISE -> (0.84f * (0.55f + 0.45f * easeOutCubic(p))).coerceAtMost(0.88f)
                    MatrixTransitionPhase.DECEL_LOCK -> 0.85f * (1f - 0.22f * easeOutCubic(p))
                    MatrixTransitionPhase.ZOOM_IN_UNFURL -> (1f - easeOutCubic((p / 0.46f).coerceIn(0f, 1f))).coerceIn(0f, 1f) * 0.68f
                    MatrixTransitionPhase.MYSTERY_SUSPENSE -> (1f - easeOutCubic(p * 1.45f)).coerceIn(0f, 1f) * 0.55f
                    else -> 0f
                }

                val speedNorm = (hypot(cruiseVelX, cruiseVelY) / 4.2f).coerceIn(0f, 1f)
                val dynamicSkewDeg = -sign(if (abs(cruiseVelX) > 0.05f) cruiseVelX else 1f) * speedNorm * 2.8f
                val totalRotationDeg = flagTiltDegrees + dynamicSkewDeg

                drawIntoCanvas { composeCanvas ->
                    val nc = composeCanvas.nativeCanvas

                    // 2A. Atmospheric Crimson Cracked Tempered Glass + Detaching Shards (replaces thin rings)
                    if (phase == MatrixTransitionPhase.ERROR_FROST) {
                        CrackedGlassEffects.drawCrackedCrimsonGlassError(
                            nc = nc,
                            w = w,
                            h = h,
                            centerX = centerX,
                            centerY = centerY,
                            progress = p,
                            isPreview = activePreviewId != null,
                            errorColorArgb = errorColor.toArgb(),
                            fillPaint = fillPaint,
                            strokePaint = strokePaint,
                            shardPath = shardPath,
                        )
                    }

                    // 2B. Infinite 2D Sovereign World Flag Matrix (zero-clipPath pre-rounded blitting + tight culling)
                    val drawMatrix = phase != MatrixTransitionPhase.MYSTERY_SHATTER &&
                        phase != MatrixTransitionPhase.ERROR_FROST &&
                        !(phase == MatrixTransitionPhase.MYSTERY_SUSPENSE && surroundingAlpha <= 0.01f)

                    if (drawMatrix) {
                        nc.save()
                        nc.translate(centerX, centerY)
                        if (abs(totalRotationDeg) > 0.05f) {
                            nc.rotate(totalRotationDeg)
                        }
                        nc.scale(gridScale, gridScale)

                        // Tight viewport half-extents in rotated grid space so we ONLY touch truly visible tiles!
                        val rotRad = Math.toRadians(totalRotationDeg.toDouble())
                        val absCos = abs(cos(rotRad)).toFloat()
                        val absSin = abs(sin(rotRad)).toFloat()
                        val halfViewW = (w * 0.5f * absCos + h * 0.5f * absSin) / gridScale.coerceAtLeast(0.5f)
                        val halfViewH = (w * 0.5f * absSin + h * 0.5f * absCos) / gridScale.coerceAtLeast(0.5f)

                        val halfCols = (ceil((halfViewW + baseCardW * 0.5f) / stepX.coerceAtLeast(30f)).toInt()).coerceIn(1, 5)
                        val halfRows = (ceil((halfViewH + baseCardH * 0.5f) / stepY.coerceAtLeast(24f)).toInt()).coerceIn(1, 5)
                        val centerColInt = floor(camX + 0.5f).toInt()
                        val centerRowInt = floor(camY + 0.5f).toInt()
                        val cornerRadius = (baseCardH * 0.14f).coerceIn(7f, 22f)
                        val placeholderArgb = surfaceContainerHigh.copy(alpha = 0.55f).toArgb()
                        val accentArgb = tertiaryColor.toArgb()
                        val availArgb = primaryColor.toArgb()

                        val cullBoundX = halfViewW + baseCardW * 0.65f
                        val cullBoundY = halfViewH + baseCardH * 0.65f

                        for (r in (centerRowInt - halfRows)..(centerRowInt + halfRows)) {
                            val dyCells = r - camY
                            val cy = dyCells * stepY
                            if (abs(cy) > cullBoundY) continue

                            for (c in (centerColInt - halfCols)..(centerColInt + halfCols)) {
                                val dxCells = c - camX
                                val cx = dxCells * stepX
                                if (abs(cx) > cullBoundX) continue

                                // Keep cell (0, 0) pinned to outgoingCode across zoom/cruise/dock so the departing flag never swaps mid-flight.
                                val isOriginTile = c == 0 && r == 0 && !outgoingCode.isNullOrBlank() && (
                                    phase == MatrixTransitionPhase.ZOOM_OUT ||
                                        phase == MatrixTransitionPhase.CRUISE ||
                                        phase == MatrixTransitionPhase.DECEL_LOCK ||
                                        phase == MatrixTransitionPhase.DISCONNECT_DOCK
                                    )
                                val isTargetTile = c == targetCellCol && r == targetCellRow && !targetCode.isNullOrBlank() && (
                                    phase == MatrixTransitionPhase.DECEL_LOCK ||
                                        phase == MatrixTransitionPhase.ZOOM_IN_UNFURL ||
                                        phase == MatrixTransitionPhase.MYSTERY_SUSPENSE
                                    )

                                if (isTargetTile && phase == MatrixTransitionPhase.MYSTERY_SUSPENSE) continue

                                val distFromCenter = hypot(dxCells, dyCells)
                                val radialFalloff = (1f - (distFromCenter / 3.8f).coerceIn(0f, 0.88f))
                                val tileAlpha = when {
                                    isOriginTile && phase == MatrixTransitionPhase.ZOOM_OUT -> {
                                        val fadeIn = easeInOutCubic((p / 0.20f).coerceIn(0f, 1f))
                                        fadeIn * dimAlpha
                                    }
                                    isOriginTile && phase == MatrixTransitionPhase.DISCONNECT_DOCK -> {
                                        val fadeIn = easeInOutCubic((p / 0.20f).coerceIn(0f, 1f))
                                        val fadeOut = 1f - easeInOutCubic(((p - 0.58f) / 0.42f).coerceIn(0f, 1f))
                                        fadeIn * fadeOut * dimAlpha
                                    }
                                    isTargetTile && phase == MatrixTransitionPhase.ZOOM_IN_UNFURL -> {
                                        val fadeOut = 1f - easeInOutCubic(((p - 0.46f) / 0.24f).coerceIn(0f, 1f))
                                        fadeOut * dimAlpha
                                    }
                                    isTargetTile -> gridTileDimAlpha
                                    else -> surroundingAlpha * radialFalloff * gridTileDimAlpha
                                }
                                if (tileAlpha <= 0.015f) continue

                                val left = cx - baseCardW * 0.5f
                                val top = cy - baseCardH * 0.5f
                                dstRect.set(left, top, left + baseCardW, top + baseCardH)

                                val isMysteryCell = isTargetTile && CountryFlagResolver.isSpecialNonCountryFlag(targetCode)

                                if (isMysteryCell) {
                                    drawFrostedMysteryCard(
                                        nc = nc,
                                        rect = dstRect,
                                        cornerRadius = cornerRadius,
                                        alpha = tileAlpha,
                                        glowIntensity = if (phase == MatrixTransitionPhase.DECEL_LOCK) easeOutCubic(p) else 0.5f,
                                        crackProgress = 0f,
                                        primaryColorArgb = availArgb,
                                        tertiaryColorArgb = accentArgb,
                                        fillPaint = fillPaint,
                                        strokePaint = strokePaint,
                                        textPaint = textPaint,
                                    )
                                } else {
                                    val cellCode = when {
                                        isOriginTile -> outgoingCode!!
                                        isTargetTile -> targetCode!!
                                        else -> mapMatrixCellToCountry(c, r, sessionPool)
                                    }
                                    val isMorphingHero = (isOriginTile && (phase == MatrixTransitionPhase.ZOOM_OUT || phase == MatrixTransitionPhase.DISCONNECT_DOCK)) ||
                                        (isTargetTile && phase == MatrixTransitionPhase.ZOOM_IN_UNFURL)
                                    // Use pre-rounded cached thumbnails for matrix tiles; only the morphing hero tile uses full resolution.
                                    val bmp = if (isMorphingHero) {
                                        FlagTextureRenderer.getCachedBitmap(cellCode, showCoatOfArms)
                                            ?: FlagTextureRenderer.getBitmap(context, cellCode, showCoatOfArms)
                                    } else {
                                        FlagTextureRenderer.getCachedRoundedThumbnail(cellCode, showCoatOfArms)
                                    }
                                    val isAvailableServer = highlightAvailableServers &&
                                        !isTargetTile &&
                                        !isMorphingHero &&
                                        cellCode.uppercase() in normalizedAvailableCodes
                                    val tileCornerRadius = if (isMorphingHero) {
                                        cornerRadius * (1f - heroLockBlend)
                                    } else {
                                        cornerRadius
                                    }
                                    val cardChromeFactor = if (isMorphingHero) (1f - heroLockBlend) else 1f

                                    drawFlagCardTile(
                                        nc = nc,
                                        bitmap = bmp,
                                        preRounded = !isMorphingHero,
                                        rect = dstRect,
                                        cornerRadius = tileCornerRadius,
                                        alpha = tileAlpha,
                                        highlightAmount = if (isTargetTile && phase == MatrixTransitionPhase.DECEL_LOCK) easeOutCubic(p) else 0f,
                                        isAvailableServer = isAvailableServer,
                                        chromeFactor = cardChromeFactor,
                                        accentColorArgb = accentArgb,
                                        availableServerColorArgb = availArgb,
                                        placeholderArgb = placeholderArgb,
                                        clipPath = clipPath,
                                        srcRect = srcRect,
                                        bmpPaint = bmpPaint,
                                        fillPaint = fillPaint,
                                        strokePaint = strokePaint,
                                    )
                                }
                            }
                        }
                        nc.restore()
                    }

                    // 2C. Mystery "?" Suspense Phase
                    if (phase == MatrixTransitionPhase.MYSTERY_SUSPENSE) {
                        val grow = easeInOutCubic(p)
                        val cardScale = 1.0f + grow * 1.15f
                        val pulseWave = (sin(p * 3f * 2f * PI.toFloat()).coerceAtLeast(0f)) * (0.04f + 0.05f * p)
                        val finalScale = cardScale + pulseWave
                        val tremorX = if (p > 0.65f) sin(p * 85f) * 4.5f * ((p - 0.65f) / 0.35f) else 0f
                        val tremorY = if (p > 0.65f) cos(p * 97f) * 3.5f * ((p - 0.65f) / 0.35f) else 0f

                        val cw = baseCardW * finalScale
                        val ch = baseCardH * finalScale
                        dstRect.set(
                            centerX - cw * 0.5f + tremorX,
                            centerY - ch * 0.5f + tremorY,
                            centerX + cw * 0.5f + tremorX,
                            centerY + ch * 0.5f + tremorY,
                        )

                        fillPaint.color = tertiaryColor.copy(alpha = (0.12f + 0.28f * p)).toArgb()
                        nc.drawRoundRect(
                            dstRect.left - 18f,
                            dstRect.top - 18f,
                            dstRect.right + 18f,
                            dstRect.bottom + 18f,
                            ch * 0.22f,
                            ch * 0.22f,
                            fillPaint,
                        )

                        drawFrostedMysteryCard(
                            nc = nc,
                            rect = dstRect,
                            cornerRadius = (ch * 0.14f).coerceIn(14f, 34f),
                            alpha = 0.96f,
                            glowIntensity = 0.55f + 0.45f * p,
                            crackProgress = ((p - 0.12f) / 0.88f).coerceIn(0f, 1f),
                            primaryColorArgb = primaryColor.toArgb(),
                            tertiaryColorArgb = tertiaryColor.toArgb(),
                            fillPaint = fillPaint,
                            strokePaint = strokePaint,
                            textPaint = textPaint,
                        )
                    }

                    // 2D. Complex 42-Piece 3D Tumbling Crystal Glass Shatter
                    if (phase == MatrixTransitionPhase.MYSTERY_SHATTER) {
                        CrackedGlassEffects.drawComplexGlassShatter(
                            nc = nc,
                            centerX = centerX,
                            centerY = centerY,
                            baseW = baseCardW * 2.15f,
                            baseH = baseCardH * 2.15f,
                            progress = p,
                            fillPaint = fillPaint,
                            strokePaint = strokePaint,
                            shardPath = shardPath,
                        )
                    }
                }

                // Cached zero-allocation DstIn feathering masks
                drawRect(brush = verticalDstInBrush, blendMode = BlendMode.DstIn)
                drawRect(brush = horizontalDstInBrush, blendMode = BlendMode.DstIn)
            }
        }
    }
}

private fun drawFlagCardTile(
    nc: android.graphics.Canvas,
    bitmap: Bitmap?,
    preRounded: Boolean,
    rect: RectF,
    cornerRadius: Float,
    alpha: Float,
    highlightAmount: Float,
    isAvailableServer: Boolean,
    chromeFactor: Float = 1f,
    accentColorArgb: Int,
    availableServerColorArgb: Int,
    placeholderArgb: Int,
    clipPath: AndroidPath,
    srcRect: Rect,
    bmpPaint: Paint,
    fillPaint: Paint,
    strokePaint: Paint,
) {
    val alphaInt = (alpha * 255f).toInt().coerceIn(0, 255)
    if (alphaInt <= 2) return

    if (preRounded && bitmap != null && !bitmap.isRecycled) {
        // Fast path: draw pre-rounded bitmap directly without canvas save/clipPath/restore.
        srcRect.set(0, 0, bitmap.width, bitmap.height)
        bmpPaint.alpha = alphaInt
        nc.drawBitmap(bitmap, srcRect, rect, bmpPaint)
    } else if (bitmap == null || bitmap.isRecycled) {
        fillPaint.color = (placeholderArgb and 0x00FFFFFF) or ((alphaInt * 0.65f).toInt().coerceIn(0, 255) shl 24)
        nc.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
    } else {
        // Dynamic corner morph path ONLY for the single hero flag during zoom-in / zoom-out
        if (cornerRadius > 0.5f) {
            clipPath.reset()
            clipPath.addRoundRect(rect, cornerRadius, cornerRadius, AndroidPath.Direction.CW)
            nc.save()
            nc.clipPath(clipPath)
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            bmpPaint.alpha = alphaInt
            nc.drawBitmap(bitmap, srcRect, rect, bmpPaint)
            if (chromeFactor > 0.02f) {
                val sheenAlpha = (alpha * chromeFactor * 28f).toInt().coerceIn(0, 42)
                fillPaint.color = (sheenAlpha shl 24) or 0xFFFFFF
                nc.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.34f, fillPaint)
            }
            nc.restore()
        } else {
            srcRect.set(0, 0, bitmap.width, bitmap.height)
            bmpPaint.alpha = alphaInt
            nc.drawBitmap(bitmap, srcRect, rect, bmpPaint)
        }
    }

    if (chromeFactor <= 0.02f) return

    // Tile rim: target lock halo > available server accent highlight > crisp subtle glass rim
    when {
        highlightAmount > 0.02f -> {
            val glowAlpha = (highlightAmount * alpha * chromeFactor * 225f).toInt().coerceIn(0, 255)
            strokePaint.color = (accentColorArgb and 0x00FFFFFF) or (glowAlpha shl 24)
            strokePaint.strokeWidth = 2.6f + highlightAmount * 2.2f
            nc.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
        isAvailableServer -> {
            val rimAlpha = (alpha * chromeFactor * 195f).toInt().coerceIn(0, 230)
            strokePaint.color = (availableServerColorArgb and 0x00FFFFFF) or (rimAlpha shl 24)
            strokePaint.strokeWidth = 2.2f
            nc.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

            // Subtle glowing availability indicator dot in top-right corner
            val dotR = (rect.height() * 0.075f).coerceIn(3.2f, 6.2f)
            val dotX = rect.right - dotR * 2.1f
            val dotY = rect.top + dotR * 2.1f
            fillPaint.color = (availableServerColorArgb and 0x00FFFFFF) or (rimAlpha shl 24)
            nc.drawCircle(dotX, dotY, dotR, fillPaint)
            fillPaint.color = ((rimAlpha * 0.85f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
            nc.drawCircle(dotX, dotY, dotR * 0.45f, fillPaint)
        }
        else -> {
            val rimAlpha = (alpha * chromeFactor * 72f).toInt().coerceIn(0, 120)
            strokePaint.color = (rimAlpha shl 24) or 0xFFFFFF
            strokePaint.strokeWidth = 1.25f
            nc.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
    }
}

private fun drawFrostedMysteryCard(
    nc: android.graphics.Canvas,
    rect: RectF,
    cornerRadius: Float,
    alpha: Float,
    glowIntensity: Float,
    crackProgress: Float,
    primaryColorArgb: Int,
    tertiaryColorArgb: Int,
    fillPaint: Paint,
    strokePaint: Paint,
    textPaint: Paint,
) {
    val bodyAlpha = (alpha * 225f).toInt().coerceIn(0, 240)
    fillPaint.color = (bodyAlpha shl 24) or 0x161D2C
    nc.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)

    val sheenAlpha = (alpha * (38f + 34f * glowIntensity)).toInt().coerceIn(0, 110)
    fillPaint.color = (primaryColorArgb and 0x00FFFFFF) or (sheenAlpha shl 24)
    nc.drawRoundRect(
        rect.left + 2f,
        rect.top + 2f,
        rect.right - 2f,
        rect.top + rect.height() * 0.50f,
        cornerRadius * 0.85f,
        cornerRadius * 0.85f,
        fillPaint,
    )

    val borderAlpha = (alpha * (150f + 100f * glowIntensity)).toInt().coerceIn(0, 255)
    strokePaint.color = (tertiaryColorArgb and 0x00FFFFFF) or (borderAlpha shl 24)
    strokePaint.strokeWidth = 2.6f + glowIntensity * 1.4f
    nc.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

    val cx = rect.centerX()
    val cy = rect.centerY()
    textPaint.textSize = rect.height() * 0.58f
    textPaint.color = ((alpha * 245f).toInt().coerceIn(0, 255) shl 24) or 0xF5F8FF
    val fm = textPaint.fontMetrics
    val textY = cy - (fm.ascent + fm.descent) * 0.5f
    nc.drawText("?", cx, textY, textPaint)

    if (crackProgress > 0.02f) {
        val crackAlpha = (alpha * (140f + 115f * crackProgress)).toInt().coerceIn(0, 255)
        strokePaint.color = (crackAlpha shl 24) or 0xEBF6FF
        strokePaint.strokeWidth = 1.6f + crackProgress * 1.4f
        val rx = rect.width() * 0.48f
        val ry = rect.height() * 0.48f
        val rayCount = 7
        for (i in 0 until rayCount) {
            val baseAngle = (i.toFloat() / rayCount) * (2f * PI.toFloat()) + 0.35f
            val reach = crackProgress.coerceIn(0f, 1f)
            val midX = cx + cos(baseAngle + 0.14f) * rx * 0.45f * reach
            val midY = cy + sin(baseAngle + 0.14f) * ry * 0.45f * reach
            val endX = cx + cos(baseAngle - 0.08f) * rx * reach
            val endY = cy + sin(baseAngle - 0.08f) * ry * reach
            nc.drawLine(cx, cy, midX, midY, strokePaint)
            if (reach > 0.35f) {
                nc.drawLine(midX, midY, endX, endY, strokePaint)
            }
            if (reach > 0.58f) {
                val branchLen = (reach - 0.58f) / 0.42f
                val bx = midX + cos(baseAngle + 0.72f) * rx * 0.34f * branchLen
                val by = midY + sin(baseAngle + 0.72f) * ry * 0.34f * branchLen
                nc.drawLine(midX, midY, bx, by, strokePaint)
            }
        }
    }
}
