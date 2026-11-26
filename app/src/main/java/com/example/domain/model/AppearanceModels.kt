package com.example.domain.model

import kotlin.random.Random

enum class AppLanguage(val tag: String) {
    SYSTEM(""),
    RU("ru"),
    EN("en"),
}

enum class ThemeMode(val title: String) {
    SYSTEM("Системная"),
    LIGHT("Светлая"),
    DARK("Тёмная"),
}

enum class BackgroundGradientStyle(
    val title: String,
    val subtitle: String,
) {
    GLASS_AURORA("Стекло и Аврора", "Мягкий градиент темы Material You"),
    DEEP_COSMOS("Глубокий космос", "Тёмный индиго-графит с акцентом"),
    EMERALD_PULSE("Изумрудный импульс", "Бирюзово-изумрудные оттенки"),
    SUNSET_GLOW("Тёплый закат", "Янтарно-коралловый градиент"),
    MINIMAL_CLEAN("Чистый минимал", "Однородный фон без свечения"),
}

enum class FlagMatrixDirection(
    val title: String,
    val shortTitle: String,
    val vx: Float,
    val vy: Float,
) {
    LEFT_TO_RIGHT("Слева направо →", "→ Вправо", -1.0f, 0.0f),
    RIGHT_TO_LEFT("Справа налево ←", "← Влево", 1.0f, 0.0f),
    UP_TO_DOWN("Сверху вниз ↓", "↓ Вниз", 0.0f, -1.0f),
    DOWN_TO_UP("Снизу вверх ↑", "↑ Вверх", 0.0f, 1.0f),
    DIAGONAL_SE("Диагональ ↘", "↘ Диаг.", -0.82f, -0.57f),
    DIAGONAL_NE("Диагональ ↗", "↗ Диаг.", -0.82f, 0.57f),
    RANDOM_EACH_TIME("Случайное", "Случайно", 1.0f, 0.0f);

    fun resolveVector(random: Random = Random.Default): Pair<Float, Float> {
        if (this != RANDOM_EACH_TIME) return vx to vy
        val concrete = entries.filter { it != RANDOM_EACH_TIME }
        val picked = concrete[random.nextInt(concrete.size)]
        return picked.vx to picked.vy
    }

    /**
     * Converts discrete direction into clock-face degrees:
     * 0° = 12 o'clock (up), 90° = 3 o'clock (right), 180° = 6 o'clock (down), 270° = 9 o'clock (left).
     */
    fun toClockDegrees(): Float = when (this) {
        LEFT_TO_RIGHT -> 90f
        RIGHT_TO_LEFT -> 270f
        UP_TO_DOWN -> 180f
        DOWN_TO_UP -> 0f
        DIAGONAL_SE -> 135f
        DIAGONAL_NE -> 45f
        RANDOM_EACH_TIME -> 270f
    }
}

enum class WeatherEffectMode(
    val title: String,
    val shortTitle: String,
    val subtitle: String,
) {
    OFF("Выкл", "Выкл", "Без погодных эффектов"),
    AUTO_LOCATION("По геолокации", "Авто", "Реальная погода Open-Meteo по приближённой локации"),
    CLEAR("Ясно / Солнце", "Ясно", "Мягкий солнечный свет и лёгкое мерцание"),
    PARTLY_CLOUDY("Переменная облачность", "Переменная", "Объёмные 3D-облака и мягкие солнечные лучи"),
    CLOUDY("Облачно (3D-облака)", "Облачно", "Многослойный объёмный 3D-покров облаков с глубиной"),
    FOG("3D-туман (объёмный)", "3D-туман", "Глубокий объёмный 3D-туман с параллаксом и светорассеянием"),
    DRIZZLE("Морось и дымка", "Морось", "Лёгкая 3D-дымка и мелкая морось с деликатными каплями"),
    RAIN("Дождь", "Дождь", "Капли дождя со всплесками и виброоткликом"),
    HEAVY_RAIN("Ливень", "Ливень", "Плотный ливень, ветер и частые брызги"),
    STORM("Гроза", "Гроза", "Грозовые 3D-тучи, ветвистые молнии, ливень и гром"),
    SNOW("Снег", "Снег", "Плавный объёмный снегопад с ветровым дрейфом"),
    BLIZZARD("Метель", "Метель", "Сильная вьюга с косым снегом и ледяной 3D-позёмкой"),
    HAIL("Град", "Град", "Ледяные градины с отскоком и чётким тактильным ударом"),
}

data class LiveWeatherState(
    val condition: WeatherEffectMode = WeatherEffectMode.OFF,
    val temperatureC: Double? = null,
    val windSpeedKmh: Double? = null,
    val isDay: Boolean = true,
    val locationLabel: String = "",
    val updatedAtMillis: Long = 0L,
)

enum class WavePhysicsPreset(
    val title: String,
    val config: WaterWaveConfig,
) {
    LIQUID_GLASS(
        "Жидкое стекло",
        WaterWaveConfig(speed = 0.74f, strength = 0.92f, smoothness = 0.80f, elasticity = 0.68f, tintStrength = 0.34f, dispersion = 1.05f, ringWidth = 1.00f),
    ),
    SILK_RIPPLE(
        "Шёлковая рябь",
        WaterWaveConfig(speed = 0.58f, strength = 0.65f, smoothness = 1.25f, elasticity = 0.45f, tintStrength = 0.24f, dispersion = 0.55f, ringWidth = 1.32f),
    ),
    HEAVY_WATER(
        "Тяжёлая вода",
        WaterWaveConfig(speed = 0.50f, strength = 1.38f, smoothness = 0.62f, elasticity = 1.05f, tintStrength = 0.46f, dispersion = 1.25f, ringWidth = 1.18f),
    ),
    CRYSTAL(
        "Кристалл",
        WaterWaveConfig(speed = 0.96f, strength = 1.05f, smoothness = 0.48f, elasticity = 0.82f, tintStrength = 0.38f, dispersion = 1.55f, ringWidth = 0.72f),
    ),
    DELICATE(
        "Деликатная",
        WaterWaveConfig(speed = 0.82f, strength = 0.45f, smoothness = 1.10f, elasticity = 0.35f, tintStrength = 0.16f, dispersion = 0.35f, ringWidth = 1.15f),
    ),
}

data class WaterWaveConfig(
    val speed: Float = 0.72f,
    val strength: Float = 0.85f,
    val smoothness: Float = 0.75f,
    val elasticity: Float = 0.65f,
    val tintStrength: Float = 0.30f,
    val dispersion: Float = 0.85f,
    val ringWidth: Float = 1.0f,
)

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class LogEntry(
    val id: Long,
    val timestampMillis: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)
