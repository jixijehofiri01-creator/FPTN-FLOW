package com.example.core.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import com.example.core.performance.DevicePerformanceProfile
import com.example.domain.model.WaterWaveConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Customizable physical liquid-glass refraction water-wave AGSL shader (API 33+):
 * Exposes live uniforms for wave speed, refraction strength, smoothness (crest width),
 * elasticity (oscillation/bounce ripples), and subtle glass specular sheen.
 */
private const val WATER_WAVE_AGSL_SHADER = """
    uniform shader content;
    uniform float2 resolution;
    uniform float2 origin;
    uniform float progress;
    uniform float maxRadius;
    uniform float amplitude;
    uniform float smoothness;
    uniform float elasticity;
    uniform float waveTint;
    uniform float dispersion;
    uniform float ringWidthScale;
    uniform float isConnect;

    half4 main(float2 fragCoord) {
        float2 delta = fragCoord - origin;
        float dist = length(delta);
        float2 dir = dist > 0.001 ? (delta / dist) : float2(0.0, 0.0);

        float waveFront = progress * maxRadius;
        float ringWidth = min(resolution.x, resolution.y) * (0.14 + smoothness * 0.13) * clamp(ringWidthScale, 0.55, 1.65);
        float u = (dist - waveFront) / max(ringWidth, 1.0);

        // Smoothness widens the envelope; elasticity adds lively trailing liquid bounce rings
        float decay = mix(1.25, 0.52, clamp(smoothness / 1.6, 0.0, 1.0));
        float freq = 2.15 + elasticity * 1.95;
        float gaussian = exp(-(u * u * decay));
        float waveOsc = sin(u * freq) * gaussian;
        float crestSlope = cos(u * freq) * gaussian;

        float smoothFadeIn = smoothstep(0.0, 0.09, progress);
        float smoothFadeOut = pow(1.0 - progress, 1.08);
        float envelope = smoothFadeIn * smoothFadeOut;

        // Physical radial UI refraction across cards, text, and icons
        float displacement = waveOsc * amplitude * envelope;
        float chromaSpread = abs(crestSlope) * envelope * (amplitude * 0.072 * clamp(dispersion, 0.0, 1.8));

        float2 maxCoord = resolution - float2(1.0, 1.0);
        float2 coordR = clamp(fragCoord + dir * (displacement + chromaSpread), float2(0.0), maxCoord);
        float2 coordG = clamp(fragCoord + dir * displacement, float2(0.0), maxCoord);
        float2 coordB = clamp(fragCoord + dir * (displacement - chromaSpread), float2(0.0), maxCoord);

        half r = content.eval(coordR).r;
        half4 centerSample = content.eval(coordG);
        half b = content.eval(coordB).b;

        half3 refracted = half3(r, centerSample.g, b);

        // Refined physical glass specular highlight & subtle trough shadow
        float specular = clamp(crestSlope, 0.0, 1.0) * envelope * waveTint;
        float troughShadow = clamp(-crestSlope, 0.0, 1.0) * envelope * (waveTint * 0.42);
        half3 glassHighlight = mix(half3(0.86, 0.90, 0.98), half3(0.90, 0.99, 0.98), half(isConnect));

        half3 finalRgb = clamp(
            refracted + glassHighlight * half(specular) - half3(half(troughShadow)),
            half3(0.0),
            half3(1.0)
        );

        return half4(finalRgb, centerSample.a);
    }
"""

@Stable
class WaterWaveController {
    var waveId by mutableIntStateOf(0)
        private set
    var origin by mutableStateOf(Offset.Unspecified)
        private set
    var isConnectWave by mutableStateOf(true)
        private set

    fun triggerWave(originPx: Offset, isConnect: Boolean) {
        origin = originPx
        isConnectWave = isConnect
        waveId += 1
    }
}

fun Modifier.fullScreenWaterWave(
    controller: WaterWaveController,
    config: WaterWaveConfig = WaterWaveConfig(),
): Modifier =
    this then WaterWaveElement(
        waveId = controller.waveId,
        origin = controller.origin,
        isConnectWave = controller.isConnectWave,
        config = config,
    )

private data class WaterWaveElement(
    val waveId: Int,
    val origin: Offset,
    val isConnectWave: Boolean,
    val config: WaterWaveConfig,
) : ModifierNodeElement<WaterWaveNode>() {

    override fun create(): WaterWaveNode = WaterWaveNode(waveId, origin, isConnectWave, config)

    override fun update(node: WaterWaveNode) {
        node.updateParams(waveId, origin, isConnectWave, config)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "fullScreenWaterWave"
        properties["waveId"] = waveId
        properties["origin"] = origin
        properties["isConnectWave"] = isConnectWave
        properties["config"] = config
    }
}

private class WaterWaveNode(
    private var lastWaveId: Int,
    private var origin: Offset,
    private var isConnectWave: Boolean,
    private var config: WaterWaveConfig,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {

    private val progressAnim = Animatable(0f)
    private var activeJob: Job? = null
    private var runtimeShader: RuntimeShader? = null

    private val silkyEasing = CubicBezierEasing(0.18f, 0.82f, 0.26f, 1.0f)

    override fun onAttach() {
        super.onAttach()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && runtimeShader == null) {
            val allowShaders = runCatching {
                DevicePerformanceProfile.supportsGpuShaders(currentValueOf(LocalContext))
            }.getOrDefault(true)
            if (allowShaders) {
                runtimeShader = runCatching { RuntimeShader(WATER_WAVE_AGSL_SHADER) }.getOrNull()
            }
        }
    }

    override fun onDetach() {
        activeJob?.cancel()
        activeJob = null
        super.onDetach()
    }

    fun updateParams(waveId: Int, newOrigin: Offset, connect: Boolean, newConfig: WaterWaveConfig) {
        origin = newOrigin
        isConnectWave = connect
        config = newConfig
        if (waveId != lastWaveId && waveId > 0) {
            lastWaveId = waveId
            launchWaveAnimation()
        }
    }

    private fun launchWaveAnimation() {
        if (!isAttached) return
        activeJob?.cancel()
        val speedFactor = config.speed.coerceIn(0.35f, 1.8f)
        val baseMs = if (isConnectWave) 1420f else 1080f
        val durationMs = (baseMs / speedFactor).toInt().coerceIn(520, 3600)
        activeJob = coroutineScope.launch {
            progressAnim.snapTo(0f)
            progressAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = durationMs,
                    easing = silkyEasing,
                ),
            )
            progressAnim.snapTo(0f)
        }
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val p = progressAnim.value
                val w = placeable.width.toFloat()
                val h = placeable.height.toFloat()

                if (p <= 0.0001f || p >= 0.9999f || w <= 0f || h <= 0f) {
                    renderEffect = null
                    scaleX = 1f
                    scaleY = 1f
                    return@placeWithLayer
                }

                val center = if (origin == Offset.Unspecified) {
                    Offset(w * 0.5f, h * 0.5f)
                } else {
                    Offset(origin.x.coerceIn(0f, w), origin.y.coerceIn(0f, h))
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val shader = runtimeShader
                    if (shader != null) {
                        val maxRadius = hypot(
                            maxOf(center.x, w - center.x),
                            maxOf(center.y, h - center.y),
                        ) * 1.24f
                        val minDim = min(w, h)
                        val baseRatio = if (isConnectWave) 0.088f else 0.058f
                        val ampPx = (minDim * baseRatio * config.strength.coerceIn(0.25f, 1.8f))
                            .coerceIn(22f, 175f)
                        val tint = (if (isConnectWave) 0.36f else 0.24f) * config.tintStrength.coerceIn(0.05f, 1.0f)

                        shader.setFloatUniform("resolution", w, h)
                        shader.setFloatUniform("origin", center.x, center.y)
                        shader.setFloatUniform("progress", p)
                        shader.setFloatUniform("maxRadius", maxRadius)
                        shader.setFloatUniform("amplitude", ampPx)
                        shader.setFloatUniform("smoothness", config.smoothness.coerceIn(0.25f, 1.6f))
                        shader.setFloatUniform("elasticity", config.elasticity.coerceIn(0.15f, 1.5f))
                        shader.setFloatUniform("waveTint", tint)
                        shader.setFloatUniform("dispersion", config.dispersion.coerceIn(0.0f, 1.8f))
                        shader.setFloatUniform("ringWidthScale", config.ringWidth.coerceIn(0.55f, 1.65f))
                        shader.setFloatUniform("isConnect", if (isConnectWave) 1f else 0f)

                        renderEffect = RenderEffect
                            .createRuntimeShaderEffect(shader, "content")
                            .asComposeRenderEffect()
                        return@placeWithLayer
                    }
                }

                // Smooth single-pulse fallback for API < 33
                renderEffect = null
                val bell = sin(p * PI).toFloat()
                val scaleDelta = bell * (if (isConnectWave) 0.022f else 0.013f) * config.strength.coerceIn(0.4f, 1.5f)
                transformOrigin = TransformOrigin(center.x / w, center.y / h)
                scaleX = 1f + scaleDelta
                scaleY = 1f + scaleDelta
            }
        }
    }
}
