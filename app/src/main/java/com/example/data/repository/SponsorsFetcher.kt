package com.example.data.repository

import com.example.core.logging.AppLogStore
import com.example.domain.model.SponsorItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class FptnProjectMeta(
    val description: String,
    val githubStars: Int?,
    val latestRelease: String?,
    val sponsors: List<SponsorItem>,
    val isLiveLoaded: Boolean,
)

/**
 * Loads live project metadata & sponsors/contributors from official fptn-project GitHub endpoints,
 * backed by complete authentic built-in FPTN project links so the section is never blank offline.
 */
object SponsorsFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    val fallbackMeta = FptnProjectMeta(
        description = "FPTN (Free Protection Tunnel Network) — некоммерческий волонтёрский VPN-проект с открытым исходным кодом для обхода DPI и цензуры.",
        githubStars = null,
        latestRelease = null,
        sponsors = listOf(
            SponsorItem("Сообщество Boosty FPTN", "Спонсоры инфраструктуры серверов", "https://fptn.org"),
            SponsorItem("fptn-project Maintainers", "Разработка ядра libfptn и Android-клиента", "https://github.com/fptn-project"),
            SponsorItem("Волонтёры @fptn_project", "Тестирование обхода ТСПУ и поддержка узлов", "https://t.me/fptn_project"),
        ),
        isLiveLoaded = false,
    )

    suspend fun fetchLive(): FptnProjectMeta = withContext(Dispatchers.IO) {
        var stars: Int? = null
        var releaseTag: String? = null
        val loadedSponsors = mutableListOf<SponsorItem>()

        // 1. Fetch repo stats (stars & description) from official fptn-project/FptnClient-Android
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/fptn-project/FptnClient-Android")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FptnClient-Android-Fork")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    stars = json.optInt("stargazers_count", 0).takeIf { it > 0 }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }

        // 2. Fetch latest release tag
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/fptn-project/FptnClient-Android/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FptnClient-Android-Fork")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    releaseTag = json.optString("tag_name", "").takeIf { it.isNotBlank() }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }

        // 3. Fetch top contributors from official fptn-project/fptn & FptnClient-Android
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/fptn-project/FptnClient-Android/contributors?per_page=8")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "FptnClient-Android-Fork")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val arr = JSONArray(resp.body?.string().orEmpty())
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val login = obj.optString("login", "").trim()
                        val htmlUrl = obj.optString("html_url", "").takeIf { it.isNotBlank() }
                        val commits = obj.optInt("contributions", 0)
                        if (login.isNotEmpty() && !login.endsWith("[bot]")) {
                            loadedSponsors.add(
                                SponsorItem(
                                    name = "@$login",
                                    tier = "Контрибьютор FptnClient-Android ($commits комм.)",
                                    url = htmlUrl,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
        }

        val combined = buildList {
            addAll(fallbackMeta.sponsors)
            loadedSponsors.forEach { item ->
                if (none { it.name.equals(item.name, ignoreCase = true) }) {
                    add(item)
                }
            }
        }

        val liveOk = stars != null || releaseTag != null || loadedSponsors.isNotEmpty()
        if (liveOk) {
            AppLogStore.info("ProjectInfo", "Данные проекта FPTN обновлены (релиз: ${releaseTag ?: "—"}, участников: ${loadedSponsors.size})")
        }

        FptnProjectMeta(
            description = fallbackMeta.description,
            githubStars = stars,
            latestRelease = releaseTag,
            sponsors = combined,
            isLiveLoaded = liveOk,
        )
    }
}
