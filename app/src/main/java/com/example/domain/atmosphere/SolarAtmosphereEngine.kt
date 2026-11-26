package com.example.domain.atmosphere

import androidx.compose.ui.graphics.Color
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

enum class SolarPhase(
    val title: String,
    val shortTitle: String,
) {
    NIGHT("Ночь", "Ночь"),
    DAWN("Рассвет", "Рассвет"),
    MORNING("Утро", "Утро"),
    DAY("День", "День"),
    GOLDEN_HOUR("Закат", "Закат"),
    DUSK("Сумерки", "Сумерки"),
}

enum class Season(val title: String) {
    WINTER("Зима"),
    SPRING("Весна"),
    SUMMER("Лето"),
    AUTUMN("Осень"),
}

data class SolarAtmosphereState(
    val phase: SolarPhase,
    val season: Season,
    val localHour: Float,
    val dayOfYear: Int,
    val solarElevationDeg: Float,
    val warmth: Float,
    val skyTopColor: Color,
    val skyMidColor: Color,
    val ambientGlowColor: Color,
    val overlayStrength: Float,
    val sunXFraction: Float = 0.5f,
    val sunYFraction: Float = 0.28f,
)

/**
 * Pure astronomical NOAA solar elevation & seasonal circadian atmosphere calculator.
 * Uses local device time, day-of-year, and timezone (or optional latitude) so summer naturally has
 * earlier sunrise, higher solar arc, and longer golden evenings while winter shifts to crisp low-sun twilight.
 */
object SolarAtmosphereEngine {

    fun evaluate(
        calendar: Calendar = Calendar.getInstance(),
        overrideHour: Float? = null,
        overrideDayOfYear: Int? = null,
        latitudeDeg: Double? = null,
    ): SolarAtmosphereState {
        val dayOfYear = (overrideDayOfYear ?: calendar.get(Calendar.DAY_OF_YEAR)).coerceIn(1, 366)
        val localHour = (overrideHour ?: (
            calendar.get(Calendar.HOUR_OF_DAY) +
                calendar.get(Calendar.MINUTE) / 60f +
                calendar.get(Calendar.SECOND) / 3600f
            )).coerceIn(0f, 24f)

        val latDeg = latitudeDeg ?: estimateLatitudeFromTimeZone(calendar.timeZone)
        val elevationDeg = computeSolarElevationDeg(
            localHour = localHour.toDouble(),
            dayOfYear = dayOfYear,
            latitudeDeg = latDeg,
        ).toFloat()

        val season = seasonForDayOfYear(dayOfYear, latDeg >= 0.0)
        val phase = classifyPhase(elevationDeg = elevationDeg, localHour = localHour)

        // Smooth interpolation weights based on solar elevation & afternoon/morning warmth
        val isEvening = localHour >= 12f
        val (topColor, midColor, glowColor, warmth, strength) = computePalette(
            elevationDeg = elevationDeg,
            isEvening = isEvening,
            season = season,
        )

        // Directional sky arc: sunrise rises in East (left-top), noon high center, sunset glows West (right-mid)
        val dayProgress = ((localHour - 5f) / 15f).coerceIn(0f, 1f)
        val sunX = if (elevationDeg < -9f) 0.72f else (0.14f + dayProgress * 0.72f)
        val sunY = when {
            elevationDeg < -9f -> 0.16f // High moonlit glow at night
            else -> (0.48f - (elevationDeg.coerceIn(-6f, 65f) / 65f) * 0.34f).coerceIn(0.12f, 0.52f)
        }

        return SolarAtmosphereState(
            phase = phase,
            season = season,
            localHour = localHour,
            dayOfYear = dayOfYear,
            solarElevationDeg = elevationDeg,
            warmth = warmth,
            skyTopColor = topColor,
            skyMidColor = midColor,
            ambientGlowColor = glowColor,
            overlayStrength = strength,
            sunXFraction = sunX,
            sunYFraction = sunY,
        )
    }

    /**
     * Standard NOAA solar elevation formula:
     * sin(alpha) = sin(lat)*sin(decl) + cos(lat)*cos(decl)*cos(hourAngle)
     */
    fun computeSolarElevationDeg(
        localHour: Double,
        dayOfYear: Int,
        latitudeDeg: Double,
    ): Double {
        val gamma = 2.0 * PI * (dayOfYear - 1) / 365.0
        val declinationRad = 0.006918 -
            0.399912 * cos(gamma) +
            0.070257 * sin(gamma) -
            0.006758 * cos(2.0 * gamma) +
            0.000907 * sin(2.0 * gamma) -
            0.002697 * cos(3.0 * gamma) +
            0.00148 * sin(3.0 * gamma)

        val latRad = Math.toRadians(latitudeDeg.coerceIn(-68.0, 68.0))
        // Solar noon ~ 12:15 local civil time
        val hourAngleRad = Math.toRadians((localHour - 12.25) * 15.0)
        val sinAlt = (sin(latRad) * sin(declinationRad) +
            cos(latRad) * cos(declinationRad) * cos(hourAngleRad)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(asin(sinAlt))
    }

    private fun estimateLatitudeFromTimeZone(tz: TimeZone): Double {
        val id = tz.id.lowercase()
        return when {
            id.contains("moscow") || id.contains("samara") || id.contains("yekaterinburg") ||
                id.contains("novosibirsk") || id.contains("omsk") || id.contains("krasnoyarsk") -> 55.7
            id.contains("kaliningrad") || id.contains("minsk") || id.contains("kiev") ||
                id.contains("kyiv") || id.contains("warsaw") || id.contains("berlin") -> 52.5
            id.contains("helsinki") || id.contains("stockholm") || id.contains("oslo") -> 60.0
            id.contains("almaty") || id.contains("tashkent") || id.contains("baku") ||
                id.contains("tbilisi") || id.contains("yerevan") -> 43.2
            id.contains("dubai") || id.contains("riyadh") || id.contains("tehran") -> 31.0
            id.contains("shanghai") || id.contains("tokyo") || id.contains("seoul") -> 35.5
            id.contains("singapore") || id.contains("bangkok") || id.contains("ho_chi_minh") -> 10.8
            id.contains("sydney") || id.contains("melbourne") || id.contains("auckland") -> -34.0
            id.contains("sao_paulo") || id.contains("buenos_aires") -> -28.0
            id.contains("new_york") || id.contains("chicago") || id.contains("los_angeles") -> 40.7
            else -> 48.0
        }
    }

    fun seasonForDayOfYear(dayOfYear: Int, northernHemisphere: Boolean = true): Season {
        val north = when (dayOfYear) {
            in 60..151 -> Season.SPRING
            in 152..243 -> Season.SUMMER
            in 244..334 -> Season.AUTUMN
            else -> Season.WINTER
        }
        if (northernHemisphere) return north
        return when (north) {
            Season.WINTER -> Season.SUMMER
            Season.SPRING -> Season.AUTUMN
            Season.SUMMER -> Season.WINTER
            Season.AUTUMN -> Season.SPRING
        }
    }

    private fun classifyPhase(elevationDeg: Float, localHour: Float): SolarPhase = when {
        elevationDeg < -9f -> SolarPhase.NIGHT
        elevationDeg < 3.5f -> if (localHour < 12f) SolarPhase.DAWN else SolarPhase.DUSK
        elevationDeg < 16f -> if (localHour < 12f) SolarPhase.MORNING else SolarPhase.GOLDEN_HOUR
        else -> SolarPhase.DAY
    }

    private data class PaletteTuple(
        val top: Color,
        val mid: Color,
        val glow: Color,
        val warmth: Float,
        val strength: Float,
    )

    private fun computePalette(
        elevationDeg: Float,
        isEvening: Boolean,
        season: Season,
    ): PaletteTuple {
        // Subtle seasonal hue nudge
        val seasonGlowTint = when (season) {
            Season.WINTER -> Color(0xFF7CB8FF)
            Season.SPRING -> Color(0xFF7CE8C5)
            Season.SUMMER -> Color(0xFFFFD07B)
            Season.AUTUMN -> Color(0xFFFFA566)
        }

        val nightTop = Color(0xFF07102B)
        val nightMid = Color(0xFF152652)
        val nightGlow = Color(0xFF5682FF)

        val dawnTop = Color(0xFF47377A)
        val dawnMid = Color(0xFFF28E6B)
        val dawnGlow = Color(0xFFFFC48A)

        val goldenTop = Color(0xFF44235E)
        val goldenMid = Color(0xFFF06C3A)
        val goldenGlow = Color(0xFFFFA84D)

        val dayTop = Color(0xFF1E88F5)
        val dayMid = Color(0xFF72C6FF)
        val dayGlow = blendColor(Color(0xFFFFE799), seasonGlowTint, 0.30f)

        return when {
            elevationDeg <= -10f -> PaletteTuple(
                top = nightTop,
                mid = nightMid,
                glow = blendColor(nightGlow, seasonGlowTint, 0.25f),
                warmth = 0.08f,
                strength = 0.34f,
            )
            elevationDeg < 4f -> {
                val t = ((elevationDeg + 10f) / 14f).coerceIn(0f, 1f)
                val targetTop = if (isEvening) goldenTop else dawnTop
                val targetMid = if (isEvening) goldenMid else dawnMid
                val targetGlow = if (isEvening) goldenGlow else dawnGlow
                PaletteTuple(
                    top = blendColor(nightTop, targetTop, t),
                    mid = blendColor(nightMid, targetMid, t),
                    glow = blendColor(nightGlow, targetGlow, t),
                    warmth = 0.15f + 0.78f * t,
                    strength = 0.36f + 0.14f * t,
                )
            }
            elevationDeg < 22f -> {
                val t = ((elevationDeg - 4f) / 18f).coerceIn(0f, 1f)
                val srcTop = if (isEvening) goldenTop else dawnTop
                val srcMid = if (isEvening) goldenMid else dawnMid
                val srcGlow = if (isEvening) goldenGlow else dawnGlow
                PaletteTuple(
                    top = blendColor(srcTop, dayTop, t),
                    mid = blendColor(srcMid, dayMid, t),
                    glow = blendColor(srcGlow, dayGlow, t),
                    warmth = (0.92f - 0.38f * t).coerceIn(0.45f, 0.94f),
                    strength = 0.48f - 0.14f * t,
                )
            }
            else -> PaletteTuple(
                top = dayTop,
                mid = dayMid,
                glow = dayGlow,
                warmth = 0.54f,
                strength = 0.32f,
            )
        }
    }

    private fun blendColor(a: Color, b: Color, t: Float): Color {
        val c = t.coerceIn(0f, 1f)
        return Color(
            red = a.red + (b.red - a.red) * c,
            green = a.green + (b.green - a.green) * c,
            blue = a.blue + (b.blue - a.blue) * c,
            alpha = a.alpha + (b.alpha - a.alpha) * c,
        )
    }
}
