package com.example.domain.routing

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.example.R

/**
 * Exact official Russian apps security protection logic ported directly from
 * FptnClient-Android 1.3.28.0 (AppExclusion.java + always_excluded_apps.xml + app_exclusion_exceptions.xml).
 *
 * Uses prefix-hierarchy matching (`while (prefix.contains("."))`) across all 507 official rules:
 * e.g. `ru.dublgis.dgismobile` (2GIS) walks -> `ru.dublgis.dgismobile` -> `ru.dublgis` (MATCH!).
 *
 * Every matching app ALWAYS bypasses the VPN tunnel and cannot be overridden by user settings.
 */
object ProtectedRuApps {

    @Volatile
    private var cachedExcludedPrefixes: Set<String>? = null

    @Volatile
    private var cachedExceptions: Set<String>? = null

    /**
     * Built-in fallback prefix set used only when no Android Context is available (e.g. pure JVM unit tests).
     */
    private val fallbackPrefixes: Set<String> = setOf(
        "ru.dublgis",
        "ru.rostel",
        "ru.gosuslugi",
        "com.gnivc.nalog",
        "ru.nalog",
        "ru.mos",
        "ru.sberbankmobile",
        "ru.sberbank",
        "com.idamob.tinkoff",
        "ru.tinkoff",
        "ru.vtb24",
        "ru.vtb",
        "ru.alfabank",
        "ru.gazprombank",
        "ru.raiffeisennews",
        "ru.sovcomcard",
        "ru.mw",
        "ru.rshb",
        "ru.mts",
        "ru.ozon",
        "com.yandex",
        "ru.yandex",
        "ru.nspk",
        "ru.yoo.money",
        "com.wildberries",
        "com.avito",
        "ru.megafon",
        "ru.beeline",
        "ru.tele2",
        "ru.yota",
        "ru.russianpost",
        "ru.vk",
        "com.vkontakte",
        "ru.mail",
    )

    private val fallbackExceptions: Set<String> = setOf(
        "com.mycompany.app.soulbrowser",
        "com.perm.kate_new_6",
        "io.github",
        "ru.radioplayer",
    )

    fun loadExcludedPrefixes(context: Context): Set<String> {
        cachedExcludedPrefixes?.let { return it }
        synchronized(this) {
            cachedExcludedPrefixes?.let { return it }
            val loaded = runCatching {
                context.resources.getStringArray(R.array.always_excluded_apps)
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }.getOrDefault(fallbackPrefixes)
            cachedExcludedPrefixes = loaded
            return loaded
        }
    }

    fun loadExceptions(context: Context): Set<String> {
        cachedExceptions?.let { return it }
        synchronized(this) {
            cachedExceptions?.let { return it }
            val loaded = runCatching {
                context.resources.getStringArray(R.array.app_exclusion_exceptions)
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }.getOrDefault(fallbackExceptions)
            cachedExceptions = loaded
            return loaded
        }
    }

    fun getRuleCount(context: Context): Int = loadExcludedPrefixes(context).size

    /**
     * Exact official prefix-walking algorithm from FptnClient-Android AppExclusion.java:
     * Checks full package name first, then strips trailing segments while a dot remains.
     * Example: "ru.dublgis.dgismobile" -> checks "ru.dublgis.dgismobile", then "ru.dublgis" -> TRUE.
     */
    fun matchesPrefix(ruleSet: Set<String>, packageName: String): Boolean {
        val lower = packageName.trim().lowercase()
        if (lower.isEmpty()) return false
        if (lower in ruleSet) return true
        var prefix = lower
        while (prefix.contains('.')) {
            prefix = prefix.substringBeforeLast('.')
            if (prefix in ruleSet) return true
        }
        return false
    }

    /**
     * Context-aware check against all 507 official rules + exceptions + self package.
     */
    fun isProtected(context: Context, packageName: String): Boolean {
        val lower = packageName.trim().lowercase()
        if (lower.isEmpty()) return false
        if (lower == context.packageName.lowercase()) return true

        val exceptions = loadExceptions(context)
        if (matchesPrefix(exceptions, lower)) return false

        val rules = loadExcludedPrefixes(context)
        return matchesPrefix(rules, lower)
    }

    /**
     * Context-free fallback check (uses cached official rules if already loaded, otherwise fallback set).
     */
    fun isProtected(packageName: String): Boolean {
        val lower = packageName.trim().lowercase()
        if (lower.isEmpty()) return false
        val exceptions = cachedExceptions ?: fallbackExceptions
        if (matchesPrefix(exceptions, lower)) return false
        val rules = cachedExcludedPrefixes ?: fallbackPrefixes
        return matchesPrefix(rules, lower)
    }

    /**
     * Discovers all visible installed apps on this device that must ALWAYS bypass VPN
     * (matching official FptnClient-Android 507 rules + self package, without QUERY_ALL_PACKAGES).
     */
    fun resolveProtectedPackagesOnDevice(context: Context): Set<String> {
        val pm = context.packageManager
        val discovered = linkedSetOf<String>()
        discovered.add(context.packageName)

        // 1. All visible applications from PackageManager
        runCatching {
            val apps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
            for (app in apps) {
                val pkg = app.packageName ?: continue
                if (isProtected(context, pkg)) {
                    discovered.add(pkg)
                }
            }
        }

        // 2. All launcher activities (ensures full coverage under Android 11+ package visibility)
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (isProtected(context, pkg)) {
                    discovered.add(pkg)
                }
            }
        }

        // 3. Also explicitly include exact non-prefix rules so VpnService.Builder excludes them even if visibility is restricted
        val rules = loadExcludedPrefixes(context)
        for (rule in rules) {
            if (rule.count { it == '.' } >= 2) {
                discovered.add(rule)
            }
        }

        return discovered
    }
}
