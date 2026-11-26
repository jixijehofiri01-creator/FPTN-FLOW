package com.example.data.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class CoatOfArmsSpec(
    val wikimediaFilename: String,
    /** Horizontal center on the flag (0..1). */
    val cxFraction: Float = 0.50f,
    /** Vertical center on the flag (0..1). */
    val cyFraction: Float = 0.50f,
    /** Height of the coat of arms relative to flag height. */
    val heightFraction: Float = 0.52f,
    /**
     * When true, render the flag base without any hand-drawn crest first, then overlay
     * this authentic official Wikimedia coat of arms cleanly on top.
     */
    val overlayOnPlainFlag: Boolean = true,
)

/**
 * Persistent disk-cached authentic official Coat of Arms repository backed by Wikimedia Commons
 * public-domain national heraldry (`Special:FilePath/<Filename>.svg?width=256`).
 *
 * - Covers 58 sovereign countries (all popular VPN locations & European/CIS/global states).
 * - Decodes crisp transparent PNGs and persists them permanently to `filesDir/coa_cache/{CC}.png`
 *   so subsequent launches render instantaneously 100% offline.
 * - Exposes [coaCacheVersion] so Compose flag widgets seamlessly hot-upgrade the instant an official
 *   coat of arms finishes downloading.
 */
object CoatOfArmsRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val memoryCache = LruCache<String, Bitmap>(MAX_MEMORY_CACHE_ENTRIES)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedRecently = ConcurrentHashMap<String, Long>()
    private val downloadSemaphore = Semaphore(permits = 4)

    private val _coaCacheVersion = MutableStateFlow(0)
    val coaCacheVersion: StateFlow<Int> = _coaCacheVersion.asStateFlow()

    @Volatile
    var onCountryLoadedListener: ((String) -> Unit)? = null

    /**
     * Official Public Domain Wikimedia Commons SVG filenames & exact heraldic flag placement
     * for 58 sovereign countries.
     */
    val OFFICIAL_COAT_OF_ARMS_CATALOG: Map<String, CoatOfArmsSpec> = mapOf(
        // CIS & Eastern Europe
        "RU" to CoatOfArmsSpec("Coat_of_arms_of_Russia.svg", 0.50f, 0.50f, 0.54f),
        "UA" to CoatOfArmsSpec("Coat_of_arms_of_Ukraine.svg", 0.50f, 0.50f, 0.48f),
        "BY" to CoatOfArmsSpec("Emblem_of_Belarus_(2020–present).svg", 0.54f, 0.50f, 0.48f),
        "KZ" to CoatOfArmsSpec("Emblem_of_Kazakhstan.svg", 0.50f, 0.50f, 0.48f),
        "AM" to CoatOfArmsSpec("Coat_of_arms_of_Armenia.svg", 0.50f, 0.50f, 0.50f),
        "GE" to CoatOfArmsSpec("Greater_coat_of_arms_of_Georgia.svg", 0.50f, 0.50f, 0.50f),
        "AZ" to CoatOfArmsSpec("Emblem_of_Azerbaijan.svg", 0.50f, 0.50f, 0.46f),
        "MD" to CoatOfArmsSpec("Coat_of_arms_of_Moldova.svg", 0.50f, 0.50f, 0.52f),
        "UZ" to CoatOfArmsSpec("Emblem_of_Uzbekistan.svg", 0.50f, 0.50f, 0.46f),
        "KG" to CoatOfArmsSpec("Emblem_of_Kyrgyzstan.svg", 0.50f, 0.50f, 0.46f),
        // Western & Central Europe
        "DE" to CoatOfArmsSpec("Coat_of_arms_of_Germany.svg", 0.50f, 0.50f, 0.52f),
        "NL" to CoatOfArmsSpec("Royal_coat_of_arms_of_the_Netherlands.svg", 0.50f, 0.50f, 0.50f),
        "FR" to CoatOfArmsSpec("Coat_of_arms_of_France.svg", 0.50f, 0.50f, 0.48f),
        "GB" to CoatOfArmsSpec("Royal_Coat_of_Arms_of_the_United_Kingdom_(2022).svg", 0.50f, 0.50f, 0.48f),
        "IE" to CoatOfArmsSpec("Coat_of_arms_of_Ireland.svg", 0.50f, 0.50f, 0.48f),
        "AT" to CoatOfArmsSpec("Austria_Bundesadler.svg", 0.50f, 0.50f, 0.54f),
        "CH" to CoatOfArmsSpec("Coat_of_arms_of_Switzerland.svg", 0.50f, 0.50f, 0.46f),
        "BE" to CoatOfArmsSpec("Coat_of_arms_of_Belgium.svg", 0.50f, 0.50f, 0.48f),
        "LU" to CoatOfArmsSpec("Coat_of_arms_of_Luxembourg.svg", 0.50f, 0.50f, 0.48f),
        "IT" to CoatOfArmsSpec("Emblem_of_Italy.svg", 0.50f, 0.50f, 0.48f),
        "ES" to CoatOfArmsSpec("Coat_of_arms_of_Spain.svg", 0.34f, 0.50f, 0.46f),
        "PT" to CoatOfArmsSpec("Coat_of_arms_of_Portugal.svg", 0.40f, 0.50f, 0.50f),
        "PL" to CoatOfArmsSpec("Herb_Polski.svg", 0.50f, 0.50f, 0.52f),
        "CZ" to CoatOfArmsSpec("Coat_of_arms_of_the_Czech_Republic.svg", 0.58f, 0.50f, 0.48f),
        "SK" to CoatOfArmsSpec("Coat_of_arms_of_Slovakia.svg", 0.33f, 0.50f, 0.48f),
        "HU" to CoatOfArmsSpec("Coat_of_arms_of_Hungary.svg", 0.50f, 0.50f, 0.52f),
        "RO" to CoatOfArmsSpec("Coat_of_arms_of_Romania.svg", 0.50f, 0.50f, 0.50f),
        "BG" to CoatOfArmsSpec("Coat_of_arms_of_Bulgaria.svg", 0.50f, 0.50f, 0.48f),
        "HR" to CoatOfArmsSpec("Coat_of_arms_of_Croatia.svg", 0.50f, 0.48f, 0.48f),
        "SI" to CoatOfArmsSpec("Coat_of_arms_of_Slovenia.svg", 0.27f, 0.36f, 0.34f),
        "RS" to CoatOfArmsSpec("Coat_of_arms_of_Serbia.svg", 0.35f, 0.50f, 0.54f),
        "ME" to CoatOfArmsSpec("Coat_of_arms_of_Montenegro.svg", 0.50f, 0.50f, 0.56f),
        "AL" to CoatOfArmsSpec("Coat_of_arms_of_Albania.svg", 0.50f, 0.50f, 0.52f),
        "AD" to CoatOfArmsSpec("Coat_of_arms_of_Andorra.svg", 0.50f, 0.50f, 0.46f),
        "SM" to CoatOfArmsSpec("Coat_of_arms_of_San_Marino.svg", 0.50f, 0.50f, 0.52f),
        "VA" to CoatOfArmsSpec("Coat_of_arms_of_the_Vatican_City.svg", 0.75f, 0.50f, 0.50f),
        // Nordics & Baltics
        "FI" to CoatOfArmsSpec("Coat_of_arms_of_Finland.svg", 0.50f, 0.50f, 0.48f),
        "SE" to CoatOfArmsSpec("Great_coat_of_arms_of_Sweden.svg", 0.50f, 0.50f, 0.48f),
        "NO" to CoatOfArmsSpec("Coat_of_arms_of_Norway.svg", 0.50f, 0.50f, 0.48f),
        "DK" to CoatOfArmsSpec("National_coat_of_arms_of_Denmark.svg", 0.50f, 0.50f, 0.48f),
        "IS" to CoatOfArmsSpec("Coat_of_arms_of_Iceland.svg", 0.50f, 0.50f, 0.48f),
        "EE" to CoatOfArmsSpec("Coat_of_arms_of_Estonia.svg", 0.50f, 0.50f, 0.48f),
        "LV" to CoatOfArmsSpec("Coat_of_arms_of_Latvia.svg", 0.50f, 0.50f, 0.48f),
        "LT" to CoatOfArmsSpec("Coat_of_arms_of_Lithuania.svg", 0.50f, 0.50f, 0.50f),
        // Mediterranean & Middle East
        "GR" to CoatOfArmsSpec("Coat_of_arms_of_Greece.svg", 0.50f, 0.50f, 0.46f),
        "CY" to CoatOfArmsSpec("Coat_of_arms_of_Cyprus.svg", 0.50f, 0.50f, 0.48f),
        "TR" to CoatOfArmsSpec("Emblem_of_Turkey.svg", 0.50f, 0.50f, 0.44f),
        "IL" to CoatOfArmsSpec("Emblem_of_Israel.svg", 0.50f, 0.50f, 0.46f),
        "AE" to CoatOfArmsSpec("Emblem_of_the_United_Arab_Emirates.svg", 0.56f, 0.50f, 0.48f),
        // Americas & Asia-Pacific
        "US" to CoatOfArmsSpec("Greater_coat_of_arms_of_the_United_States.svg", 0.56f, 0.54f, 0.46f),
        "CA" to CoatOfArmsSpec("Arms_of_Canada.svg", 0.50f, 0.50f, 0.48f),
        "MX" to CoatOfArmsSpec("Coat_of_arms_of_Mexico.svg", 0.50f, 0.50f, 0.46f),
        "BR" to CoatOfArmsSpec("Coat_of_arms_of_Brazil.svg", 0.50f, 0.50f, 0.48f),
        "AR" to CoatOfArmsSpec("Coat_of_arms_of_Argentina.svg", 0.50f, 0.50f, 0.48f),
        "JP" to CoatOfArmsSpec("Imperial_Seal_of_Japan.svg", 0.50f, 0.50f, 0.46f),
        "KR" to CoatOfArmsSpec("Emblem_of_South_Korea.svg", 0.50f, 0.50f, 0.46f),
        "SG" to CoatOfArmsSpec("Coat_of_arms_of_Singapore.svg", 0.54f, 0.52f, 0.48f),
        "AU" to CoatOfArmsSpec("Coat_of_arms_of_Australia.svg", 0.54f, 0.52f, 0.46f),
        "IN" to CoatOfArmsSpec("Emblem_of_India.svg", 0.50f, 0.50f, 0.46f),
    )

    fun specFor(code: String): CoatOfArmsSpec? = OFFICIAL_COAT_OF_ARMS_CATALOG[code.uppercase()]

    fun buildDownloadUrl(spec: CoatOfArmsSpec, widthPx: Int = 512): String {
        val encoded = URLEncoder.encode(spec.wikimediaFilename, "UTF-8").replace("+", "%20")
        return "https://commons.wikimedia.org/wiki/Special:FilePath/$encoded?width=$widthPx"
    }

    /**
     * Non-blocking in-memory LRU lookup safe for UI / Compose rendering (zero disk or network I/O).
     */
    fun getMemoryCachedBitmap(code: String): Bitmap? {
        val norm = code.uppercase()
        val cached = memoryCache.get(norm)
        return if (cached != null && !cached.isRecycled) cached else null
    }

    /**
     * Synchronous lookup (memory LRU + local disk cache). Intended for background threads.
     */
    fun getCachedBitmap(context: Context, code: String): Bitmap? {
        val norm = code.uppercase()
        getMemoryCachedBitmap(norm)?.let { return it }
        val file = cacheFile(context, norm)
        if (file.exists() && file.length() > 128L) {
            val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            if (decoded != null && decoded.width > 16 && decoded.height > 16) {
                memoryCache.put(norm, decoded)
                return decoded
            }
        }
        return null
    }

    /**
     * Launches a deduplicated background load (from local disk cache first, then Wikimedia Commons)
     * of the official coat of arms PNG if not yet in memory cache.
     * Upon completion, invalidates flag texture cache for [code] and bumps [coaCacheVersion].
     */
    fun prefetchIfNeeded(context: Context, code: String) {
        val norm = code.uppercase()
        val spec = OFFICIAL_COAT_OF_ARMS_CATALOG[norm] ?: return
        if (getMemoryCachedBitmap(norm) != null) return

        val appCtx = context.applicationContext
        if (!inFlight.add(norm)) return

        scope.launch {
            try {
                // Fast local disk-cache hydration on Dispatchers.IO (never blocks UI thread)
                val existingFile = cacheFile(appCtx, norm)
                if (existingFile.exists() && existingFile.length() > 128L) {
                    val decoded = runCatching { BitmapFactory.decodeFile(existingFile.absolutePath) }.getOrNull()
                    if (decoded != null && decoded.width > 16 && decoded.height > 16) {
                        memoryCache.put(norm, decoded)
                        onCountryLoadedListener?.invoke(norm)
                        _coaCacheVersion.update { it + 1 }
                        return@launch
                    }
                }

                val lastFail = failedRecently[norm] ?: 0L
                if (System.currentTimeMillis() - lastFail < FAIL_RETRY_COOLDOWN_MS) return@launch

                downloadSemaphore.withPermit {
                    val url = buildDownloadUrl(spec, widthPx = 512)
                    val req = Request.Builder()
                        .url(url)
                        .header("User-Agent", "FptnFlow/0.1 (https://github.com/jixijehofiri01-creator/FPTN-FLOW; Android)")
                        .header("Accept", "image/png,image/*;q=0.9")
                        .get()
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            failedRecently[norm] = System.currentTimeMillis()
                            return@withPermit
                        }
                        val bytes = resp.body?.bytes()
                        if (bytes == null || bytes.size < 128) {
                            failedRecently[norm] = System.currentTimeMillis()
                            return@withPermit
                        }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp == null || bmp.width < 16 || bmp.height < 16) {
                            failedRecently[norm] = System.currentTimeMillis()
                            return@withPermit
                        }
                        val outFile = cacheFile(appCtx, norm)
                        outFile.parentFile?.mkdirs()
                        val tmp = File(outFile.parentFile, "$norm.png.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(outFile)) {
                            outFile.writeBytes(bytes)
                            tmp.delete()
                        }
                        memoryCache.put(norm, bmp)
                        failedRecently.remove(norm)
                        onCountryLoadedListener?.invoke(norm)
                        _coaCacheVersion.update { it + 1 }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                failedRecently[norm] = System.currentTimeMillis()
            } finally {
                inFlight.remove(norm)
            }
        }
    }

    private fun cacheFile(context: Context, normCode: String): File {
        val dir = File(context.filesDir, "coa_cache_512")
        return File(dir, "$normCode.png")
    }

    private const val MAX_MEMORY_CACHE_ENTRIES = 24
    private const val FAIL_RETRY_COOLDOWN_MS = 3 * 60 * 1000L
}
