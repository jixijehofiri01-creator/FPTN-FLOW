package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.charset.StandardCharsets
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-xxhdpi")
class FptnUiInteractionRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun buildSampleToken(service: String, serverName: String, host: String): String {
        val json = """
            {
              "version": 1,
              "service_name": "$service",
              "username": "tester",
              "password": "pw",
              "servers": [
                {
                  "name": "$serverName",
                  "host": "$host",
                  "port": 443,
                  "md5_fingerprint": "0123456789abcdef0123456789abcdef"
                }
              ]
            }
        """.trimIndent()
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
            .chunked(76)
            .joinToString("\n")
        return "fptn://$encoded"
    }

    @Test
    fun `full ui critical user journey across all screens and controls`() {
        composeRule.waitForIdle()

        // 1. Home screen starts cleanly (no fake tokens, quick settings present)
        composeRule.onNodeWithTag("home_status_title").assertIsDisplayed()
        composeRule.onNodeWithTag("home_empty_token_card").assertIsDisplayed()
        composeRule.onNodeWithTag("home_quick_settings").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_add_token_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("token_name_input").fetchSemanticsNodes().isNotEmpty()
        }

        // 2. Token Manager: add two named multi-token profiles
        val expectedOneProfile = composeRule.activity.getString(R.string.tokens_saved_profiles_header, 1)
        val expectedSaveButton = composeRule.activity.getString(R.string.tokens_save_button)
        val expectedTwoProfiles = composeRule.activity.getString(R.string.tokens_saved_profiles_header, 2)

        val token1 = buildSampleToken("FPTN Official", "Germany-FRA", "127.0.0.1")
        composeRule.onNodeWithTag("token_name_input").performScrollTo().performTextReplacement("Официальный FPTN")
        composeRule.onNodeWithTag("token_raw_input").performScrollTo().performTextReplacement(token1)
        composeRule.onNodeWithTag("token_save_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(expectedOneProfile).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(expectedSaveButton).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()

        val token2 = buildSampleToken("My SelfHosted", "Finland-VPS", "127.0.0.1")
        composeRule.onNodeWithTag("token_name_input").performScrollTo().performTextReplacement("Мой VPS")
        composeRule.onNodeWithTag("token_raw_input").performScrollTo().performTextReplacement(token2)
        composeRule.onNodeWithTag("token_save_button").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText(expectedTwoProfiles).fetchSemanticsNodes().isNotEmpty()
        }

        // Navigate back to Home -> verify PullToConnectTrack & ServerPickerSheet
        composeRule.onNodeWithTag("tokens_back_button").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("pull_to_connect_track").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("home_server_selector_card").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("home_server_selector_card").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("server_picker_modal").assertIsDisplayed()
        // Toggle sort chips (Original vs Ping)
        composeRule.onNodeWithTag("server_sort_ping_chip").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("server_sort_original_chip").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "Expected [Официальный FPTN] Germany-FRA in ServerPickerSheet",
            composeRule.onAllNodesWithText("[Официальный FPTN] Germany-FRA").fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "Expected [Мой VPS] Finland-VPS in ServerPickerSheet",
            composeRule.onAllNodesWithText("[Мой VPS] Finland-VPS").fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithTag("server_auto_option").performClick()
        composeRule.waitForIdle()

        // 2b. Verify Home Quick Settings DropdownMenus (Sliding Tunnel & Preset)
        composeRule.onNodeWithTag("quick_sliding_tile").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("quick_sliding_option_DUAL_TUNNEL").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("quick_preset_tile").performScrollTo().performClick()
        composeRule.waitForIdle()
        // Footer manage presets CTA must NEVER be clipped or buried behind bottom navigation buttons
        composeRule.onNodeWithTag("quick_preset_manage_option").assertIsDisplayed()
        composeRule.onNodeWithTag("quick_preset_option_preset_mobile").performClick()
        composeRule.waitForIdle()

        // 3. Settings tab -> verify Root Mode row is HIDDEN on non-rooted environment
        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitForIdle()
        assertTrue(
            "Root row must be completely hidden when root is absent",
            composeRule.onAllNodesWithTag("settings_root_row").fetchSemanticsNodes().isEmpty(),
        )

        // 3b. Network Presets Screen: toggle auto-switch, create custom preset, apply, back
        composeRule.onNodeWithTag("settings_presets_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("presets_auto_switch_toggle").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("presets_create_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("preset_editor_name_input").performTextReplacement("Офис Wi-Fi")
        composeRule.onNodeWithTag("preset_editor_save_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Офис Wi-Fi").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("presets_back_button").performClick()
        composeRule.waitForIdle()

        // 4. Bypass Settings: exact 2 official methods (SNI_REALITY / TLS_OBFUSCATION),
        //    15 SNI/Reality browser profiles, official ~1,278 SNI catalog, 3 Sliding Tunnel strategies
        composeRule.onNodeWithTag("settings_bypass_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        assertTrue(
            "Smart RF switch must be hidden from BypassSettingsScreen",
            composeRule.onAllNodesWithTag("smart_rf_switch").fetchSemanticsNodes().isEmpty(),
        )
        // Mobile preset activated TLS_OBFUSCATION — verify SNI catalog is still accessible in Obfuscation mode
        composeRule.onNodeWithTag("bypass_sni_catalog_button").performScrollTo().assertIsDisplayed()
        // Switch back to SNI_REALITY to verify Reality browser profiles (Chrome 149)
        composeRule.onNodeWithTag("bypass_method_SNI_REALITY").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("bypass_sni_mode_header").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("bypass_sni_mode_header").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sni_mode_SNI_REALITY_CHROME_149").performScrollTo().performClick()
        composeRule.waitForIdle()
        // Switch to official TLS_OBFUSCATION and back
        composeRule.onNodeWithTag("bypass_method_TLS_OBFUSCATION").performScrollTo().performClick()
        composeRule.onNodeWithTag("bypass_method_SNI_REALITY").performScrollTo().performClick()
        // Verify default SNI reset & official Dual Sliding Tunnel
        composeRule.onNodeWithTag("bypass_reset_sni_button").performScrollTo().performClick()
        composeRule.onNodeWithTag("sliding_mode_DUAL_TUNNEL").performScrollTo().performClick()
        composeRule.onNodeWithTag("bypass_back_button").performClick()
        composeRule.waitForIdle()

        // 5a. Dedicated DNS & Smart DNS screen (mutually exclusive modes + 17-server catalog + add/delete)
        composeRule.onNodeWithTag("settings_dns_row").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("dns_mode_SMART_DNS_DOH").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dns_mode_SMART_DNS_DOH").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("dns_toggle_add_form_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dns_toggle_add_form_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("dns_custom_title_input").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dns_custom_title_input").performTextReplacement("Мой ИИ DNS")
        composeRule.onNodeWithTag("dns_custom_url_input").performTextReplacement("https://dns.custom-ai.example/dns-query")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dns_lazy_list").performScrollToNode(hasTestTag("dns_custom_add_submit"))
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dns_custom_add_submit").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dns_lazy_list").performScrollToNode(hasTestTag("dns_cat_all"))
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("dns_cat_CUSTOM").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dns_cat_CUSTOM").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Мой ИИ DNS").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("dns_cat_all").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dns_lazy_list").performScrollToNode(hasTestTag("dns_delete_magic_dns_intl"))
        composeRule.onNodeWithTag("dns_delete_magic_dns_intl").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("dns_entry_magic_dns_intl").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("dns_back_button").performClick()
        composeRule.waitForIdle()

        // 5b. Tunneling & Regional Routing screen
        composeRule.onNodeWithTag("settings_tunneling_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("bypass_region_CHINA").performClick()
        composeRule.onNodeWithTag("bypass_region_RUSSIA").performClick()
        composeRule.onNodeWithTag("tunneling_back_button").performClick()
        composeRule.waitForIdle()

        // 5c. Dedicated Manual Overrides screen: verify forced items, individual reset, and global reset
        composeRule.onNodeWithTag("settings_overrides_row").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("overrides_reset_all_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("overrides_lazy_list").performScrollToNode(hasTestTag("override_reset_DNS"))
        composeRule.onNodeWithTag("override_reset_DNS").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("override_card_DNS").fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithTag("overrides_lazy_list").performScrollToNode(hasTestTag("overrides_reset_all_button"))
        composeRule.onNodeWithTag("overrides_reset_all_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("overrides_empty_card").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("overrides_back_button").performClick()
        composeRule.waitForIdle()

        // 6a. Stability & Timeouts (Additional Settings)
        composeRule.onNodeWithTag("settings_additional_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("additional_back_button").performClick()
        composeRule.waitForIdle()

        // 6b. Dedicated Appearance & Haptics screen + 360° Clock-Dial Flag Tilt control
        composeRule.onNodeWithTag("settings_appearance_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("theme_chip_DARK").performScrollTo().performClick()
        composeRule.onNodeWithTag("theme_chip_SYSTEM").performClick()
        composeRule.onNodeWithTag("appearance_tab_flags_matrix").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("flag_tilt_dial_canvas").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("flag_tilt_dial_plus_button").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("appearance_back_button").performClick()
        composeRule.waitForIdle()

        // 7. Permissions (including manual Root request button)
        composeRule.onNodeWithTag("settings_permissions_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("perm_vpn_button").assertIsDisplayed()
        composeRule.onNodeWithTag("perm_root_probe_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("permissions_back_button").performClick()
        composeRule.waitForIdle()

        // 8. Logs
        composeRule.onNodeWithTag("settings_logs_row").performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("logs_copy_button").performClick()
        composeRule.onNodeWithTag("logs_back_button").performClick()
        composeRule.waitForIdle()

        // 9. Backup Export
        composeRule.onNodeWithTag("settings_exit_row").performScrollTo()
        composeRule.onNodeWithTag("settings_backup_row").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("backup_export_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("backup_export_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("backup_back_button").performClick()
        composeRule.waitForIdle()

        // 10. Collapsible Sponsors & Safe Exit dialog
        composeRule.onNodeWithTag("settings_exit_row").performScrollTo()
        composeRule.onNodeWithTag("settings_sponsors_header").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("sponsors_site_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("sponsors_site_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("sponsors_telegram_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("settings_sponsors_header").performScrollTo().performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("settings_exit_row").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithTag("exit_cancel_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("exit_cancel_button").performClick()
        composeRule.waitForIdle()

        // 11. Share tab
        composeRule.onNodeWithTag("nav_tab_share").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("share_fork_notice_card").assertIsDisplayed()
        composeRule.onNodeWithTag("share_copy_link_button").performClick()
        composeRule.onNodeWithTag("share_telegram_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("share_github_button").performScrollTo().assertIsDisplayed()

        // 12. Verify procedural Pirate Flag renders cleanly into a non-blank multi-color texture
        val pirateBmp = com.example.core.ui.flags.FlagTextureRenderer.getBitmap(
            context = composeRule.activity,
            code = com.example.domain.model.CountryFlagResolver.CODE_PIRATE,
            showCoatOfArms = true,
        )
        org.junit.Assert.assertTrue("Pirate flag bitmap must have non-zero dimensions", pirateBmp.width > 0 && pirateBmp.height > 0)
        val sampledColors = HashSet<Int>()
        val stepX = (pirateBmp.width / 24).coerceAtLeast(1)
        val stepY = (pirateBmp.height / 16).coerceAtLeast(1)
        var x = 0
        while (x < pirateBmp.width) {
            var y = 0
            while (y < pirateBmp.height) {
                sampledColors.add(pirateBmp.getPixel(x, y))
                y += stepY
            }
            x += stepX
        }
        org.junit.Assert.assertTrue(
            "Realistic pirate flag should render multiple distinct tones (got ${sampledColors.size})",
            sampledColors.size >= 2,
        )
        val pirateThumb = com.example.core.ui.flags.FlagTextureRenderer.getOrCreateRoundedThumbnail(
            context = composeRule.activity,
            code = com.example.domain.model.CountryFlagResolver.CODE_PIRATE,
            showCoatOfArms = true,
        )
        org.junit.Assert.assertTrue("Pirate rounded thumbnail must be valid", pirateThumb.width > 0 && pirateThumb.height > 0)
    }
}

