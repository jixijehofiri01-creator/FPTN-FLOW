package com.example.feature.home

import android.graphics.Bitmap
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.domain.model.WeatherEffectMode
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hardware-accelerated 3D volumetric raymarched fog & cloud renderer (AGSL on Android 13+)
 * paired with a zero-allocation 4-plane perspective fBm volumetric fallback for API < 33 and low-end devices.
 *
 * Replaces flat 2D circles with genuine 3D perspective depth, domain-warped fBm turbulence,
 * directional light scattering, and Beer-Lambert front-to-back optical integration.
 */
internal object WeatherVolumetricRenderer {

    /**
     * Returns whether [mode] includes a 3D volumetric atmospheric layer (fog, clouds, mist, storm deck, or blizzard drift).
     */
    fun hasVolumetricLayer(mode: WeatherEffectMode): Boolean = when (mode) {
        WeatherEffectMode.FOG,
        WeatherEffectMode.CLOUDY,
        WeatherEffectMode.PARTLY_CLOUDY,
        WeatherEffectMode.STORM,
        WeatherEffectMode.BLIZZARD,
        WeatherEffectMode.DRIZZLE,
        -> true
        else -> false
    }

    fun modeKindFloat(mode: WeatherEffectMode): Float = when (mode) {
        WeatherEffectMode.FOG -> 0f
        WeatherEffectMode.CLOUDY -> 1f
        WeatherEffectMode.PARTLY_CLOUDY -> 2f
        WeatherEffectMode.STORM -> 3f
        WeatherEffectMode.BLIZZARD -> 4f
        WeatherEffectMode.DRIZZLE -> 5f
        else -> 0f
    }

    /**
     * AGSL 3D volumetric raymarched fBm fog & cloud deck shader.
     * Uses a 7-slice perspective camera march with 3D value noise, gentle domain warping,
     * directional forward-scattering sunlight/moonlight highlights, and internal storm lightning illumination.
     */
    private const val VOLUMETRIC_3D_WEATHER_AGSL = """
        uniform float2 resolution;
        uniform float time;
        uniform float modeKind;
        uniform float density;
        uniform float windSpeed;
        uniform float isDark;
        uniform float lightningFlash;

        float hash31(float3 p) {
            p = fract(p * 0.1031);
            p += dot(p, p.yzx + 33.33);
            return fract((p.x + p.y) * p.z);
        }

        float noise3D(float3 p) {
            float3 i = floor(p);
            float3 f = fract(p);
            float3 u = f * f * (3.0 - 2.0 * f);

            float n000 = hash31(i + float3(0.0, 0.0, 0.0));
            float n100 = hash31(i + float3(1.0, 0.0, 0.0));
            float n010 = hash31(i + float3(0.0, 1.0, 0.0));
            float n110 = hash31(i + float3(1.0, 1.0, 0.0));
            float n001 = hash31(i + float3(0.0, 0.0, 1.0));
            float n101 = hash31(i + float3(1.0, 0.0, 1.0));
            float n011 = hash31(i + float3(0.0, 1.0, 1.0));
            float n111 = hash31(i + float3(1.0, 1.0, 1.0));

            float nx00 = mix(n000, n100, u.x);
            float nx10 = mix(n010, n110, u.x);
            float nx01 = mix(n001, n101, u.x);
            float nx11 = mix(n011, n111, u.x);
            float nxy0 = mix(nx00, nx10, u.y);
            float nxy1 = mix(nx01, nx11, u.y);
            return mix(nxy0, nxy1, u.z);
        }

        float fbm3D(float3 p) {
            float v = 0.0;
            float amp = 0.54;
            for (int i = 0; i < 3; i++) {
                v += amp * noise3D(p);
                p = p * 2.07 + float3(13.7, -7.3, 5.1);
                amp *= 0.48;
            }
            return v;
        }

        half4 main(float2 fragCoord) {
            float2 safeRes = max(resolution, float2(1.0, 1.0));
            float2 uv = fragCoord / safeRes;
            float aspect = safeRes.x / safeRes.y;
            float2 ndc = float2((uv.x - 0.5) * 2.0 * aspect, (uv.y - 0.5) * 2.0);

            // Perspective 3D camera ray looking slightly forward into depth
            float3 rd = normalize(float3(ndc.x * 0.85, ndc.y * 0.85, 1.35));

            // Select vertical envelope & palette by modeKind:
            // 0=FOG, 1=CLOUDY, 2=PARTLY_CLOUDY, 3=STORM, 4=BLIZZARD, 5=DRIZZLE
            float isFog = step(abs(modeKind - 0.0), 0.4);
            float isCloudy = step(abs(modeKind - 1.0), 0.4);
            float isPartly = step(abs(modeKind - 2.0), 0.4);
            float isStorm = step(abs(modeKind - 3.0), 0.4);
            float isBlizzard = step(abs(modeKind - 4.0), 0.4);
            float isDrizzle = step(abs(modeKind - 5.0), 0.4);

            // Base palette (lit ridges vs shadowed undersides) tuned for dark & light themes
            float3 darkLit = float3(0.76, 0.84, 0.94);
            float3 darkShade = float3(0.18, 0.24, 0.34);
            float3 lightLit = float3(0.98, 0.99, 1.00);
            float3 lightShade = float3(0.56, 0.64, 0.74);

            float3 colLit = mix(lightLit, darkLit, isDark);
            float3 colShade = mix(lightShade, darkShade, isDark);

            // Mode-specific atmospheric grading
            colLit = mix(colLit, mix(float3(1.00, 0.98, 0.92), float3(0.84, 0.89, 0.97), isDark), isPartly);
            colLit = mix(colLit, mix(float3(0.68, 0.73, 0.82), float3(0.36, 0.42, 0.56), isDark), isStorm);
            colShade = mix(colShade, mix(float3(0.28, 0.32, 0.42), float3(0.08, 0.11, 0.18), isDark), isStorm);
            colLit = mix(colLit, mix(float3(0.94, 0.98, 1.00), float3(0.80, 0.90, 0.98), isDark), isBlizzard);

            float drift = time * (0.12 + windSpeed * 0.26);
            float zAdvance = time * (0.09 + windSpeed * 0.11);

            float3 accumColor = float3(0.0);
            float accumAlpha = 0.0;

            // Sun / moon scattering anchor in upper-center sky
            float2 lightPos = float2(0.52, isFog > 0.5 ? 0.34 : 0.16);
            float sunDist = length((uv - lightPos) * float2(aspect, 1.0));
            float sunGlow = exp(-sunDist * (isFog > 0.5 ? 2.2 : 3.2));

            // 7-slice front-to-back volumetric perspective raymarch
            for (int i = 0; i < 7; i++) {
                float fi = float(i);
                float depthNorm = fi / 6.0;
                float z = 0.72 + fi * 0.66 + depthNorm * depthNorm * 0.55;

                // 3D world position along the perspective ray
                float3 p = float3(rd.x * z, rd.y * z, z);

                // Stretch horizontally for billowing cloud strata / ground fog ribbons
                float scaleX = mix(1.05, 0.72, isCloudy + isStorm);
                float scaleY = mix(1.45, 1.95, isCloudy + isPartly + isStorm);
                float3 q = float3(
                    p.x * scaleX - drift * (0.55 + depthNorm * 0.75),
                    p.y * scaleY + sin(time * 0.14 + fi * 0.9) * 0.08,
                    p.z * 0.68 - zAdvance * (0.55 + isFog * 0.65)
                );

                // Subtle 3D domain warp for curling volumetric fog tendrils
                float warp = noise3D(q * 0.75 + float3(0.0, time * 0.06, -time * 0.08)) - 0.5;
                q.xy += float2(warp * 0.58, warp * 0.32);

                float n = fbm3D(q);

                // Mode-specific vertical volumetric envelope
                float env = 1.0;
                float threshold = 0.36;
                float sliceStrength = 0.28;

                if (isFog > 0.5) {
                    // Enveloping 3D fog: thicker in lower-mid ground plane + soft distant upper haze
                    float groundBank = smoothstep(0.02, 0.88, uv.y);
                    float midVeil = 0.42 + 0.58 * groundBank;
                    env = midVeil * (0.65 + 0.45 * (1.0 - depthNorm * 0.45));
                    threshold = 0.28 - density * 0.07;
                    sliceStrength = 0.30;
                } else if (isCloudy > 0.5) {
                    // Billowing 3D cloud deck across upper 72% with smooth bottom feather
                    env = (1.0 - smoothstep(0.18, 0.84, uv.y));
                    threshold = 0.36 - density * 0.08;
                    sliceStrength = 0.34;
                } else if (isPartly > 0.5) {
                    // Sculpted sunlit cumulus puffs in upper sky
                    env = (1.0 - smoothstep(0.08, 0.66, uv.y));
                    threshold = 0.48 - density * 0.06;
                    sliceStrength = 0.32;
                } else if (isStorm > 0.5) {
                    // Dense turbulent cumulonimbus canopy
                    env = (1.0 - smoothstep(0.24, 0.88, uv.y));
                    threshold = 0.31 - density * 0.07;
                    sliceStrength = 0.40;
                } else if (isBlizzard > 0.5) {
                    // Swirling icy ground snow-drift + blowing haze
                    float driftEnv = 0.32 + 0.68 * smoothstep(0.18, 0.96, uv.y);
                    env = driftEnv;
                    threshold = 0.34 - density * 0.06;
                    sliceStrength = 0.27;
                } else if (isDrizzle > 0.5) {
                    // Light translucent upper/mid atmospheric mist veil
                    env = 0.85 - 0.35 * abs(uv.y - 0.42);
                    threshold = 0.44 - density * 0.05;
                    sliceStrength = 0.16;
                }

                float localDensity = smoothstep(threshold, threshold + 0.34, n) * env * density;

                // Directional 3D self-shadowing & top-ridge lighting (cheap single offset sample)
                float nLight = fbm3D(q + float3(0.0, -0.34, -0.22));
                float diffuse = clamp((n - nLight) * 2.1 + 0.58, 0.0, 1.0);

                // Near slices are brighter/sharper, far slices fade into atmospheric perspective
                float depthHaze = mix(0.95, 0.62, depthNorm);
                float3 sliceCol = mix(colShade, colLit, diffuse * depthHaze);

                // Forward scattering glow around sun/moon or fog backlight
                float scatterAmt = (isPartly * 0.42 + isFog * 0.30 + isCloudy * 0.16) * sunGlow;
                float3 scatterTint = mix(float3(1.0, 0.95, 0.85), float3(0.75, 0.86, 1.0), isDark);
                sliceCol += scatterTint * scatterAmt * (0.45 + 0.55 * diffuse);

                // Internal storm cloud illumination during lightning strikes
                if (isStorm > 0.5 && lightningFlash > 0.005) {
                    float flashGlow = lightningFlash * (0.45 + 0.85 * (1.0 - diffuse)) * (0.5 + 0.5 * depthNorm);
                    sliceCol += float3(0.72, 0.82, 1.00) * flashGlow;
                }

                float sliceAlpha = clamp(localDensity * sliceStrength, 0.0, 0.58);
                float weight = (1.0 - accumAlpha) * sliceAlpha;
                accumColor += sliceCol * weight;
                accumAlpha += weight;
            }

            // Soft volumetric god-ray sheen for PARTLY_CLOUDY / FOG
            if ((isPartly > 0.5 || isFog > 0.5) && sunGlow > 0.05) {
                float rayWave = 0.65 + 0.35 * sin(uv.x * 11.0 - uv.y * 5.0 + time * 0.35);
                float rayAlpha = sunGlow * rayWave * (isPartly > 0.5 ? 0.10 : 0.07) * clamp(density, 0.3, 1.2);
                float3 rayTint = mix(float3(1.0, 0.96, 0.86), float3(0.72, 0.84, 0.98), isDark);
                accumColor += rayTint * rayAlpha * (1.0 - accumAlpha * 0.5);
                accumAlpha = clamp(accumAlpha + rayAlpha * 0.65, 0.0, 0.88);
            }

            // Cap alpha so UI text & VPN shield always stay crisp and readable!
            float maxCap = isFog > 0.5 ? 0.68 : (isStorm > 0.5 ? 0.62 : (isCloudy > 0.5 ? 0.58 : 0.50));
            float finalAlpha = clamp(accumAlpha, 0.0, maxCap);
            float scaleOut = accumAlpha > 0.0001 ? (finalAlpha / accumAlpha) : 0.0;
            return half4(accumColor * scaleOut, finalAlpha);
        }
    """

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun createRuntimeShaderOrNull(): RuntimeShader? = runCatching {
        RuntimeShader(VOLUMETRIC_3D_WEATHER_AGSL)
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun drawAgslVolumetricLayer(
        drawScope: DrawScope,
        shader: RuntimeShader,
        mode: WeatherEffectMode,
        elapsedSec: Float,
        fogDepth: Float,
        intensity: Float,
        windStrength: Float,
        isDark: Boolean,
        lightningFlash: Float,
    ) {
        val w = drawScope.size.width
        val h = drawScope.size.height
        if (w <= 1f || h <= 1f) return

        val effectiveDensity = (fogDepth * (0.65f + intensity * 0.35f)).coerceIn(0.22f, 1.55f)
        shader.setFloatUniform("resolution", w, h)
        shader.setFloatUniform("time", elapsedSec)
        shader.setFloatUniform("modeKind", modeKindFloat(mode))
        shader.setFloatUniform("density", effectiveDensity)
        shader.setFloatUniform("windSpeed", windStrength.coerceIn(0f, 1.6f))
        shader.setFloatUniform("isDark", if (isDark) 1f else 0f)
        shader.setFloatUniform("lightningFlash", lightningFlash.coerceIn(0f, 1f))
        drawScope.drawRect(brush = ShaderBrush(shader))
    }

    /**
     * Generates a smooth, seamless tileable procedural fBm cloud/mist bitmap ONCE for API < 33
     * and low-end devices — rendered across 4 perspective parallax depth planes with zero circles.
     */
    fun createFallbackVolumetricTile(width: Int = 160, height: Int = 96): ImageBitmap {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val v = y.toFloat() / height.toFloat()
            // Soft top/bottom vertical feather so tiled strips blend imperceptibly
            val vEdge = (sin(v * Math.PI).toFloat()).coerceIn(0f, 1f)
            val vMask = vEdge * vEdge
            for (x in 0 until width) {
                val u = x.toFloat() / width.toFloat()
                val n1 = tileableNoise2D(u * 4f, v * 3f, 4, 3, seed = 17)
                val n2 = tileableNoise2D(u * 8f + 1.3f, v * 6f + 0.7f, 8, 6, seed = 53)
                val n3 = tileableNoise2D(u * 16f + 2.9f, v * 12f + 1.9f, 16, 12, seed = 109)
                val fbm = n1 * 0.56f + n2 * 0.29f + n3 * 0.15f
                val density = ((fbm - 0.34f) / 0.48f).coerceIn(0f, 1f)
                val alpha = (density * density * vMask * 255f).roundToInt().coerceIn(0, 255)
                // Bright lit white-silver clouds tinted dynamically at draw time via ColorFilter
                val shade = (210 + (density * 45f).roundToInt()).coerceIn(0, 255)
                pixels[y * width + x] = (alpha shl 24) or (shade shl 16) or (shade shl 8) or shade
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    /**
     * Renders 4 perspective depth planes using [tile] with parallax scroll, vertical depth stratification,
     * and mode-specific tinting for devices without AGSL shaders (API < 33 or low-end tier).
     */
    fun drawFallbackVolumetricLayers(
        drawScope: DrawScope,
        tile: ImageBitmap,
        mode: WeatherEffectMode,
        elapsedSec: Float,
        fogDepth: Float,
        intensity: Float,
        windStrength: Float,
        isDark: Boolean,
        lightningFlash: Float,
    ) {
        val w = drawScope.size.width
        val h = drawScope.size.height
        if (w <= 1f || h <= 1f) return

        val effectiveDensity = (fogDepth * (0.65f + intensity * 0.35f)).coerceIn(0.25f, 1.5f)
        val baseTint = when (mode) {
            WeatherEffectMode.STORM -> if (isDark) Color(0xFF5D6E8F) else Color(0xFF7B8CA8)
            WeatherEffectMode.BLIZZARD -> if (isDark) Color(0xFFCFE6FA) else Color(0xFFE6F3FF)
            WeatherEffectMode.PARTLY_CLOUDY -> if (isDark) Color(0xFFDCE8F8) else Color(0xFFFFFFFF)
            WeatherEffectMode.FOG -> if (isDark) Color(0xFFC2D4E8) else Color(0xFFF0F5FA)
            else -> if (isDark) Color(0xFFC8D8EB) else Color(0xFFF4F7FB)
        }
        val tintWithFlash = if (mode == WeatherEffectMode.STORM && lightningFlash > 0.02f) {
            Color(
                red = (baseTint.red + lightningFlash * 0.38f).coerceAtMost(1f),
                green = (baseTint.green + lightningFlash * 0.42f).coerceAtMost(1f),
                blue = (baseTint.blue + lightningFlash * 0.50f).coerceAtMost(1f),
                alpha = 1f,
            )
        } else {
            baseTint
        }

        val baseAlpha = when (mode) {
            WeatherEffectMode.FOG -> if (isDark) 0.24f else 0.30f
            WeatherEffectMode.CLOUDY -> if (isDark) 0.22f else 0.28f
            WeatherEffectMode.STORM -> if (isDark) 0.26f else 0.30f
            WeatherEffectMode.PARTLY_CLOUDY -> if (isDark) 0.17f else 0.22f
            WeatherEffectMode.BLIZZARD -> if (isDark) 0.19f else 0.24f
            WeatherEffectMode.DRIZZLE -> if (isDark) 0.11f else 0.14f
            else -> 0.16f
        }

        val speedFactor = 0.22f + windStrength.coerceIn(0f, 1.5f) * 0.85f
        val colorFilter = ColorFilter.tint(tintWithFlash)

        // 4 perspective planes from far (0) to near (3)
        for (layer in 0 until 4) {
            val depth = layer / 3f // 0 = far, 1 = near
            val planeWidth = (w * (1.15f + depth * 0.55f)).roundToInt().coerceAtLeast(1)
            val planeHeight = when (mode) {
                WeatherEffectMode.FOG -> (h * (0.42f + depth * 0.22f)).roundToInt()
                WeatherEffectMode.BLIZZARD -> (h * (0.38f + depth * 0.20f)).roundToInt()
                else -> (h * (0.30f + depth * 0.16f)).roundToInt()
            }.coerceAtLeast(1)

            val yTop = when (mode) {
                WeatherEffectMode.FOG -> (h * (0.14f + depth * 0.26f) + sin(elapsedSec * 0.25f + layer * 1.4f) * h * 0.025f).roundToInt()
                WeatherEffectMode.BLIZZARD -> (h * (0.28f + depth * 0.24f)).roundToInt()
                else -> (-planeHeight * 0.14f + h * (depth * 0.18f) + sin(elapsedSec * 0.22f + layer * 1.7f) * h * 0.02f).roundToInt()
            }

            val driftPx = ((elapsedSec * (16f + depth * 34f) * speedFactor + layer * 137f) % planeWidth.toFloat())
            val startX = (-driftPx).roundToInt()
            val layerAlpha = (baseAlpha * (0.68f + depth * 0.45f) * effectiveDensity).coerceIn(0.04f, 0.44f)

            // Draw seamlessly wrapped horizontal strip (2 copies cover entire viewport)
            drawScope.drawImage(
                image = tile,
                dstOffset = IntOffset(startX, yTop),
                dstSize = IntSize(planeWidth, planeHeight),
                alpha = layerAlpha,
                colorFilter = colorFilter,
            )
            drawScope.drawImage(
                image = tile,
                dstOffset = IntOffset(startX + planeWidth, yTop),
                dstSize = IntSize(planeWidth, planeHeight),
                alpha = layerAlpha,
                colorFilter = colorFilter,
            )
        }
    }

    private fun tileableNoise2D(x: Float, y: Float, periodX: Int, periodY: Int, seed: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val fx = x - xi
        val fy = y - yi
        val ux = fx * fx * (3f - 2f * fx)
        val uy = fy * fy * (3f - 2f * fy)

        val x0 = ((xi % periodX) + periodX) % periodX
        val x1 = (x0 + 1) % periodX
        val y0 = ((yi % periodY) + periodY) % periodY
        val y1 = (y0 + 1) % periodY

        val n00 = hash2(x0, y0, seed)
        val n10 = hash2(x1, y0, seed)
        val n01 = hash2(x0, y1, seed)
        val n11 = hash2(x1, y1, seed)

        val nx0 = n00 + (n10 - n00) * ux
        val nx1 = n01 + (n11 - n01) * ux
        return nx0 + (nx1 - nx0) * uy
    }

    private fun hash2(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0x7FFFFFFF).toFloat() / 0x7FFFFFFF.toFloat()
    }
}
