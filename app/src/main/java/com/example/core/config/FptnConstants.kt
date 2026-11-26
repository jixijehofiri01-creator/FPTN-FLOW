package com.example.core.config

/**
 * Centralized public links and protocol constants for FPTN Flow.
 * Keeps external URLs and timeouts out of UI screens and prevents magic-string duplication.
 */
object FptnConstants {
    const val OFFICIAL_SITE_URL = "https://fptn.org"
    const val OFFICIAL_GITHUB_ORG_URL = "https://github.com/fptn-project"
    const val OFFICIAL_ANDROID_GITHUB_URL = "https://github.com/fptn-project/FptnClient-Android"
    const val OFFICIAL_TELEGRAM_BOT_URL = "https://t.me/fptn_bot"
    const val OFFICIAL_TELEGRAM_CHANNEL_URL = "https://t.me/fptn_project"

    const val FORK_GITHUB_URL = "https://github.com/jixijehofiri01-creator/FPTN-FLOW"
    const val FORK_RELEASES_URL = "https://github.com/jixijehofiri01-creator/FPTN-FLOW/releases/latest"
    const val FORK_RELEASES_API_URL = "https://api.github.com/repos/jixijehofiri01-creator/FPTN-FLOW/releases/latest"

    const val DEFAULT_PING_TIMEOUT_MS = 2_000
    const val RETRY_PING_TIMEOUT_MAX_MS = 3_500
    const val DEFAULT_DNS_PING_TIMEOUT_MS = 2_200
    const val SNI_FALLBACK_PROBE_TIMEOUT_MS = 1_800
}
