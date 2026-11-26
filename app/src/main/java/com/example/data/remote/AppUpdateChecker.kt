package com.example.data.remote

import android.os.Build
import com.example.BuildConfig
import com.example.core.config.FptnConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ReleaseApkAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val releaseNotes: String,
    val recommendedAsset: ReleaseApkAsset?,
    val devicePrimaryAbi: String?,
)

sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data class UpToDate(val currentVersion: String) : AppUpdateStatus
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateStatus
    data class NoReleasesYet(val currentVersion: String) : AppUpdateStatus
    data class Error(val message: String) : AppUpdateStatus
}

/**
 * Checks GitHub Releases for newer versions of FPTN Flow.
 * Opens the official release page or direct APK asset URL in the user's browser —
 * never requests REQUEST_INSTALL_PACKAGES or silently installs binaries.
 */
object AppUpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    suspend fun checkLatestRelease(
        currentVersion: String = BuildConfig.VERSION_NAME,
        supportedAbis: Array<String> = Build.SUPPORTED_ABIS ?: emptyArray(),
    ): AppUpdateStatus = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(FptnConstants.FORK_RELEASES_API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FPTN-Flow/$currentVersion (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext AppUpdateStatus.NoReleasesYet(currentVersion)
                }
                if (!response.isSuccessful) {
                    return@withContext AppUpdateStatus.Error("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@withContext AppUpdateStatus.Error("Empty response")
                }

                val json = JSONObject(body)
                val tagName = json.optString("tag_name", "").trim()
                if (tagName.isBlank()) {
                    return@withContext AppUpdateStatus.NoReleasesYet(currentVersion)
                }

                val htmlUrl = json.optString("html_url", "").ifBlank { FptnConstants.FORK_RELEASES_URL }
                val notes = json.optString("body", "").trim()
                val assets = parseApkAssets(json.optJSONArray("assets"))
                val normalizedRemote = normalizeVersion(tagName)

                if (!isNewerVersion(remoteVersion = tagName, currentVersion = currentVersion)) {
                    return@withContext AppUpdateStatus.UpToDate(currentVersion)
                }

                val recommendedAsset = pickRecommendedAsset(assets, supportedAbis.toList())
                AppUpdateStatus.UpdateAvailable(
                    AppUpdateInfo(
                        currentVersion = currentVersion,
                        latestVersion = normalizedRemote,
                        releaseUrl = htmlUrl,
                        releaseNotes = notes,
                        recommendedAsset = recommendedAsset,
                        devicePrimaryAbi = supportedAbis.firstOrNull(),
                    ),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            AppUpdateStatus.Error(e.message ?: e::class.java.simpleName)
        }
    }

    fun normalizeVersion(raw: String): String {
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    /**
     * Compares semantic dotted version strings (e.g. "v0.2" > "0.1", "0.10" > "0.2", "1.0.0" > "0.9.9").
     * A stable release ("0.2") is treated as newer than a prerelease with the same numeric parts ("0.2-beta").
     */
    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        val remoteNorm = normalizeVersion(remoteVersion)
        val currentNorm = normalizeVersion(currentVersion)
        if (remoteNorm.isBlank() || currentNorm.isBlank()) return false

        val remoteCore = remoteNorm.substringBefore('-').substringBefore('+')
        val currentCore = currentNorm.substringBefore('-').substringBefore('+')

        val remoteParts = remoteCore.split('.').map { it.toIntOrNull() ?: 0 }
        val currentParts = currentCore.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(remoteParts.size, currentParts.size)

        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r != c) return r > c
        }

        val remoteIsPre = remoteNorm.contains('-')
        val currentIsPre = currentNorm.contains('-')
        return currentIsPre && !remoteIsPre
    }

    fun pickRecommendedAsset(
        assets: List<ReleaseApkAsset>,
        supportedAbis: List<String>,
    ): ReleaseApkAsset? {
        if (assets.isEmpty()) return null
        for (abi in supportedAbis) {
            val lowerAbi = abi.lowercase()
            val match = assets.firstOrNull { it.name.lowercase().contains(lowerAbi) }
            if (match != null) return match
        }
        return assets.firstOrNull { it.name.lowercase().contains("universal") }
            ?: assets.firstOrNull()
    }

    private fun parseApkAssets(array: JSONArray?): List<ReleaseApkAsset> {
        if (array == null) return emptyList()
        val result = mutableListOf<ReleaseApkAsset>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val name = item.optString("name", "")
            val url = item.optString("browser_download_url", "")
            val size = item.optLong("size", 0L)
            if (name.endsWith(".apk", ignoreCase = true) && url.startsWith("https://")) {
                result += ReleaseApkAsset(name = name, downloadUrl = url, sizeBytes = size)
            }
        }
        return result
    }
}
