package com.example.data.remote

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.example.core.logging.AppLogStore
import com.example.domain.model.LiveWeatherState
import com.example.domain.model.WeatherEffectMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Play-compliant opt-in foreground local weather fetcher powered by Open-Meteo (free, no API key).
 * Uses ONLY ACCESS_COARSE_LOCATION (network/passive coarse location) while the app is open — never
 * fine GPS or background location. Caches weather for 30 minutes to conserve battery & bandwidth.
 */
class WeatherRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedWeather: LiveWeatherState? = null

    fun hasCoarseLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    suspend fun fetchLiveWeather(forceRefresh: Boolean = false): Result<LiveWeatherState> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = cachedWeather
            if (!forceRefresh && cached != null && (now - cached.updatedAtMillis) < CACHE_TTL_MS) {
                return@withContext Result.success(cached)
            }

            if (!hasCoarseLocationPermission()) {
                return@withContext Result.failure(
                    SecurityException("Нет разрешения на приблизительную геолокацию (ACCESS_COARSE_LOCATION)."),
                )
            }

            val loc = getBestCoarseLocation()
                ?: return@withContext Result.failure(
                    IllegalStateException("Не удалось получить приблизительные координаты устройства. Включите геолокацию или выберите погоду вручную."),
                )

            val lat = String.format(Locale.US, "%.3f", loc.latitude)
            val lon = String.format(Locale.US, "%.3f", loc.longitude)
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,wind_speed_10m,is_day&timezone=auto"

            try {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "FPTN-Android/1.9")
                    .get()
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("Open-Meteo HTTP ${resp.code}")
                    }
                    val body = resp.body?.string().orEmpty()
                    val root = JSONObject(body)
                    val current = root.optJSONObject("current")
                        ?: error("Некорректный ответ Open-Meteo")
                    val wmoCode = current.optInt("weather_code", 0)
                    val tempC = if (current.has("temperature_2m")) current.optDouble("temperature_2m") else null
                    val windKmh = if (current.has("wind_speed_10m")) current.optDouble("wind_speed_10m") else null
                    val isDay = current.optInt("is_day", 1) == 1
                    val tzName = root.optString("timezone", "")
                        .substringAfterLast('/')
                        .replace('_', ' ')

                    val state = LiveWeatherState(
                        condition = mapWmoCodeToEffect(wmoCode),
                        temperatureC = tempC,
                        windSpeedKmh = windKmh,
                        isDay = isDay,
                        locationLabel = tzName.ifBlank { "$lat, $lon" },
                        updatedAtMillis = now,
                    )
                    cachedWeather = state
                    AppLogStore.info(
                        "Weather",
                        "Погода обновлена (${state.locationLabel}): ${state.condition.shortTitle}, ${tempC?.let { "%.1f°C".format(it) } ?: "—"} (WMO=$wmoCode)",
                    )
                    Result.success(state)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (ex: Exception) {
                Result.failure(ex)
            }
        }

    @SuppressLint("MissingPermission")
    private fun getBestCoarseLocation(): Location? {
        if (!hasCoarseLocationPermission()) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
        return providers.mapNotNull { provider ->
            runCatching {
                if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxByOrNull { it.time }
    }

    companion object {
        private const val CACHE_TTL_MS = 30L * 60L * 1000L

        /**
         * Maps standard WMO weather interpretation codes (0..99) from Open-Meteo to distinct
         * precipitation & sky atmosphere modes (Drizzle vs Rain vs Heavy Rain vs Storm).
         */
        fun mapWmoCodeToEffect(wmoCode: Int): WeatherEffectMode = when (wmoCode) {
            0 -> WeatherEffectMode.CLEAR
            1, 2 -> WeatherEffectMode.PARTLY_CLOUDY
            3 -> WeatherEffectMode.CLOUDY
            45, 48 -> WeatherEffectMode.FOG
            51, 53, 55, 56, 57 -> WeatherEffectMode.DRIZZLE
            61, 63, 66, 80 -> WeatherEffectMode.RAIN
            65, 67, 81, 82 -> WeatherEffectMode.HEAVY_RAIN
            71, 73, 85 -> WeatherEffectMode.SNOW
            75, 77, 86 -> WeatherEffectMode.BLIZZARD
            95 -> WeatherEffectMode.STORM
            96, 99 -> WeatherEffectMode.HAIL
            else -> WeatherEffectMode.PARTLY_CLOUDY
        }
    }
}
